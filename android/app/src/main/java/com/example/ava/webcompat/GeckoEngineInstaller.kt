package com.example.ava.webcompat

import android.app.Activity
import android.app.DownloadManager
import com.example.ava.net.GithubProxyUrls
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.ava.ui.AvaToast
import com.example.ava.R
import com.example.ava.update.AppUpdater
import com.example.ava.update.SilentApkInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tukaani.xz.XZInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads and installs the separate `gecko` flavor APK (the version that bundles GeckoView).
 * The default lite APK does not contain GeckoView, so its size is unchanged; users on old devices
 * fetch this larger build on demand.
 *
 * Install pipeline (state machine):
 *   FETCHING → DOWNLOADING → DECOMPRESSING → VERIFYING → INSTALLING → IDLE
 *
 * Each phase is persisted so a process kill after download (xz on disk) can resume at
 * DECOMPRESSING instead of restarting the download or getting stuck.
 */
object GeckoEngineInstaller {

    private const val TAG = "GeckoEngineInstaller"

    private const val GECKO_BASE_URL =
        "https://raw.githubusercontent.com/knoop7/Ava/master/gecko-engine/"

    private const val PREFS = "gecko_engine_state"
    private const val KEY_DM_ID = "dm_id"
    private const val KEY_BASE = "base"
    private const val KEY_SHA = "sha"
    private const val KEY_SIZE = "size"
    private const val KEY_PHASE = "phase"
    private const val KEY_PROGRESS = "progress"

    private const val INSTALLED_PREFS = "gecko_engine_installed"
    private const val KEY_INSTALLED_SHA = "installed_sha"
    private const val KEY_INSTALLED_SIZE = "installed_size"
    private const val KEY_INSTALLED_PACKAGE_UPDATE_TIME = "installed_package_update_time"

    enum class UpdateResult { UNSUPPORTED, NOT_INSTALLED, UP_TO_DATE, UPDATE_AVAILABLE, NETWORK_ERROR }

    enum class InstallPhase {
        IDLE,
        FETCHING,
        DOWNLOADING,
        DECOMPRESSING,
        VERIFYING,
        INSTALLING,
        FAILED
    }

    data class InstallSnapshot(
        val phase: InstallPhase,
        val progress: Int,
        val inProgress: Boolean
    ) {
        val isActive: Boolean
            get() = inProgress || phase != InstallPhase.IDLE && phase != InstallPhase.FAILED
    }

    private const val DECOMPRESS_BUFFER = 256 * 1024
    private const val THROTTLE_EVERY_BYTES = 4L * 1024 * 1024
    private const val POLL_INTERVAL_MS = 600L
    /** How long we keep the pipeline "in progress" while the user confirms the system installer. */
    private const val AWAIT_PACKAGE_INSTALL_MS = 5L * 60L * 1000L
    private const val PACKAGE_VISIBLE_POLL_MS = 1_000L
    /** Avoid reopening the system installer every settings poll / onResume. */
    private const val INSTALL_REOFFER_COOLDOWN_MS = 30_000L
    /**
     * Settings UI polls every 500ms and auto-calls [resumePendingInstall]. Without a cooldown,
     * a leftover APK / dismissed installer re-fetches the remote manifest every few seconds.
     */
    private const val RESUME_COOLDOWN_MS = 60_000L
    /** Soft cache for idle prune only — install/update paths always hit the network. */
    private const val MANIFEST_CACHE_MS = 15L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var inProgress = false

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var currentPhase = InstallPhase.IDLE

    @Volatile
    private var currentProgress = 0

    @Volatile
    private var lastInstallOfferAtMs = 0L

    @Volatile
    private var lastResumeAttemptAtMs = 0L

    @Volatile
    private var cachedManifestBase: String? = null

    @Volatile
    private var cachedManifest: GeckoManifest? = null

    @Volatile
    private var cachedManifestAtMs = 0L

    @Serializable
    private data class GeckoManifest(
        val sha256: String,
        val size: Long
    )

    private data class SavedState(
        val dmId: Long,
        val base: String,
        val sha: String,
        val size: Long
    )

    private fun manifestBaseName(): String? {
        val abis = Build.SUPPORTED_ABIS ?: return null
        return when {
            abis.any { it.equals("arm64-v8a", ignoreCase = true) } -> "gecko-arm64"
            abis.any { it.equals("armeabi-v7a", ignoreCase = true) || it.equals("armeabi", ignoreCase = true) } -> "gecko-armv7"
            else -> null
        }
    }

    fun isDeviceSupported(): Boolean = manifestBaseName() != null

    /** True while this process is actively running the install pipeline. */
    fun isInstallInProgress(): Boolean = inProgress

    fun cancelInstall(context: Context) {
        if (!inProgress) return
        cancelRequested = true
        val appContext = context.applicationContext
        scope.launch {
            val saved = loadState(appContext)
            saved?.dmId?.let { removeDownload(appContext, it) }
            cleanupLocalFiles(appContext)
            clearState(appContext)
            setPhase(appContext, InstallPhase.FAILED, 0)
            withContext(Dispatchers.Main) {
                toastRes(appContext, R.string.gecko_engine_cancelled)
            }
        }
    }

    /** Current phase/progress for UI; includes pending work after a process kill. */
    fun getInstallSnapshot(context: Context): InstallSnapshot {
        if (inProgress) {
            return InstallSnapshot(currentPhase, currentProgress, inProgress = true)
        }
        return detectPendingSnapshot(context.applicationContext)
    }

    private fun downloadUrl(context: Context, url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return raw
        return GithubProxyUrls.candidates(context, raw).firstOrNull() ?: raw
    }

    private fun githubUrlCandidates(context: Context, url: String): List<String> =
        GithubProxyUrls.candidates(context, GithubProxyUrls.toDirectUrl(url))


    fun downloadAndInstall(context: Context) {
        lastResumeAttemptAtMs = 0L
        start(context, allowNewDownload = true)
    }

    fun resumePendingInstall(context: Context) {
        val appContext = context.applicationContext
        val snapshot = detectPendingSnapshot(appContext)
        if (snapshot.phase == InstallPhase.IDLE) {
            pruneStaleArtifactsIfIdle(appContext)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastResumeAttemptAtMs > 0L &&
            now - lastResumeAttemptAtMs < RESUME_COOLDOWN_MS
        ) {
            Log.d(TAG, "Skip resumePendingInstall (cooldown)")
            return
        }
        lastResumeAttemptAtMs = now
        start(context, allowNewDownload = false)
    }

    /** Drop leftover download artifacts when the install pipeline is idle (e.g. after app resume). */
    fun pruneStaleArtifactsIfIdle(context: Context) {
        if (inProgress) return
        val appContext = context.applicationContext
        if (detectPendingSnapshot(appContext).phase != InstallPhase.IDLE) return
        scope.launch {
            val base = manifestBaseName() ?: return@launch
            val manifest = fetchManifest(appContext, base, allowCache = true) ?: return@launch
            cleanupStaleDownloadArtifacts(appContext, base, manifest)
        }
    }

    private fun start(context: Context, allowNewDownload: Boolean) {
        if (inProgress) {
            Log.d(TAG, "Gecko engine install already in progress")
            if (allowNewDownload) {
                scope.launch {
                    toastRes(context.applicationContext, R.string.gecko_engine_toast_install_in_progress)
                }
            }
            return
        }
        inProgress = true
        cancelRequested = false
        val appContext = context.applicationContext
        scope.launch {
            try {
                run(appContext, allowNewDownload)
            } catch (e: Exception) {
                Log.e(TAG, "Gecko engine install failed", e)
                setPhase(appContext, InstallPhase.FAILED, 0)
                if (allowNewDownload) {
                    toastRes(
                        appContext,
                        R.string.gecko_engine_toast_install_failed,
                        e.message ?: "unknown"
                    )
                }
            } finally {
                inProgress = false
            }
        }
    }

    private suspend fun run(appContext: Context, allowNewDownload: Boolean) {
        if (cancelRequested) { setPhase(appContext, InstallPhase.IDLE, 0); return }
        val base = manifestBaseName()
        if (base == null) {
            Log.w(TAG, "Unsupported ABI: ${Build.SUPPORTED_ABIS?.joinToString()}")
            if (allowNewDownload) {
                toastRes(appContext, R.string.settings_browser_engine_gecko_unsupported)
            }
            clearState(appContext)
            return
        }

        val downloadDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: run {
                fail(appContext, allowNewDownload)
                return
            }
        val xzFile = File(downloadDir, "$base.apk.xz")
        val finalApk = File(downloadDir, "$base.apk")
        val saved = loadState(appContext)

        setPhase(appContext, InstallPhase.FETCHING, 0)
        if (allowNewDownload && saved?.toManifest() == null && !xzFile.exists()) {
            toastPhase(appContext, InstallPhase.FETCHING)
        } else if (!allowNewDownload) {
            val pending = detectPendingSnapshot(appContext).phase
            // Auto-resume must not replay the long INSTALLING toast pair every cooldown tick.
            if (pending != InstallPhase.INSTALLING) {
                toastPhase(appContext, pending)
            }
        }

        val manifest = fetchManifest(appContext, base) ?: saved?.toManifest()
        if (manifest == null) {
            fail(appContext, allowNewDownload, R.string.gecko_engine_toast_fetch_failed)
            return
        }

        if (finalizeIfAlreadyInstalled(appContext, finalApk.takeIf { it.exists() }, manifest)) {
            runCatching { if (xzFile.exists()) xzFile.delete() }
            setPhase(appContext, InstallPhase.IDLE, 0)
            return
        }

        cleanupStaleDownloadArtifacts(appContext, base, manifest)

        // Verified APK already on disk → launch installer and wait until PackageManager sees it.
        if (finalApk.exists() && finalApk.length() == manifest.size) {
            val sha = sha256(finalApk)
            if (sha.equals(manifest.sha256, ignoreCase = true)) {
                clearDownloadState(appContext)
                setPhase(appContext, InstallPhase.INSTALLING, 100)
                if (allowNewDownload) {
                    toastPhase(appContext, InstallPhase.INSTALLING)
                }
                finishWithPackageInstall(appContext, finalApk, manifest)
                return
            }
            finalApk.delete()
        }

        var needDownload = !isXzArtifactCurrent(appContext, base, manifest, xzFile, saved)

        if (!needDownload) {
            saveState(appContext, SavedState(saved?.dmId ?: -1L, base, manifest.sha256, manifest.size))
        } else if (saved != null && saved.dmId != -1L && saved.base == base &&
            saved.sha.equals(manifest.sha256, ignoreCase = true)
        ) {
            when (queryStatus(appContext, saved.dmId)) {
                DownloadManager.STATUS_SUCCESSFUL -> needDownload = false
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED -> {
                    setPhase(appContext, InstallPhase.DOWNLOADING, queryDownloadProgress(appContext, saved.dmId))
                    toastPhase(appContext, InstallPhase.DOWNLOADING)
                    needDownload = !pollDownload(appContext, saved.dmId, xzFile)
                    if (needDownload) removeDownload(appContext, saved.dmId)
                }
                else -> removeDownload(appContext, saved.dmId)
            }
        }

        if (needDownload) {
            if (!allowNewDownload) {
                if (!(xzFile.exists() && xzFile.length() > 0L)) {
                    clearState(appContext)
                    return
                }
            } else {
                if (xzFile.exists()) xzFile.delete()
                if (finalApk.exists()) finalApk.delete()
                setPhase(appContext, InstallPhase.DOWNLOADING, 0)
                toastPhase(appContext, InstallPhase.DOWNLOADING)
                val id = enqueueDownload(
                    appContext,
                    downloadUrl(appContext, GECKO_BASE_URL + "$base.apk.xz"),
                    xzFile,
                )
                saveState(appContext, SavedState(id, base, manifest.sha256, manifest.size))
                if (!pollDownload(appContext, id, xzFile)) {
                    removeDownload(appContext, id)
                    fail(appContext, allowNewDownload, R.string.gecko_engine_toast_download_failed)
                    return
                }
            }
        } else {
            saveState(appContext, SavedState(saved?.dmId ?: -1L, base, manifest.sha256, manifest.size))
        }

        if (!xzFile.exists() || xzFile.length() == 0L) {
            fail(appContext, allowNewDownload, R.string.gecko_engine_toast_download_failed)
            return
        }

        if (cancelRequested) { setPhase(appContext, InstallPhase.IDLE, 0); return }

        setPhase(appContext, InstallPhase.DECOMPRESSING, 0)
        toastPhase(appContext, InstallPhase.DECOMPRESSING)
        val actualSha = decompressXz(xzFile, finalApk, manifest.size) { progress ->
            setPhase(appContext, InstallPhase.DECOMPRESSING, progress)
        }
        xzFile.delete()

        setPhase(appContext, InstallPhase.VERIFYING, 100)
        if (finalApk.length() != manifest.size || !actualSha.equals(manifest.sha256, ignoreCase = true)) {
            Log.e(TAG, "Verify failed: size=${finalApk.length()}/${manifest.size} sha=$actualSha/${manifest.sha256}")
            finalApk.delete()
            fail(appContext, allowNewDownload, R.string.gecko_engine_toast_verify_failed)
            return
        }

        clearDownloadState(appContext)
        setPhase(appContext, InstallPhase.INSTALLING, 100)
        toastPhase(appContext, InstallPhase.INSTALLING)
        finishWithPackageInstall(appContext, finalApk, manifest)
    }

    /**
     * Offer the system/silent installer once, keep [inProgress] true while waiting, and only
     * [markInstalled] after PackageManager exposes the exact artifact from [manifest].
     *
     * Previous bug: we marked prefs + set IDLE immediately after starting the installer, left the
     * APK on disk, then settings polling treated that APK as a fresh INSTALLING job and reopened
     * the installer — users had to confirm install twice before Ava "detected" the pack. AppUpdater
     * also deleted `gecko-*.apk` after 60s mid-confirmation.
     */
    private suspend fun finishWithPackageInstall(
        appContext: Context,
        apk: File,
        manifest: GeckoManifest,
    ) {
        if (finalizeIfAlreadyInstalled(appContext, apk, manifest)) {
            setPhase(appContext, InstallPhase.IDLE, 0)
            return
        }

        val installed = offerInstallAndAwait(appContext, apk, manifest)
        if (cancelRequested) {
            setPhase(appContext, InstallPhase.IDLE, 0)
            return
        }
        if (installed) {
            markInstalled(appContext, manifest)
            runCatching { apk.delete() }
            clearState(appContext)
            setPhase(appContext, InstallPhase.IDLE, 0)
            GeckoEngineRootSetup.maybeApply(appContext)
            GeckoEngineRootSetup.ensureOverlayForLaunch(appContext)
            Log.i(TAG, "Gecko engine pack install confirmed by PackageManager")
        } else {
            // Keep APK for resume; do not markInstalled. Phase INSTALLING so UI can resume later
            // without treating prefs SHA as proof the pack exists.
            setPhase(appContext, InstallPhase.INSTALLING, 100)
            Log.w(TAG, "Gecko install UI finished without PackageManager detecting the pack yet")
        }
    }

    private suspend fun offerInstallAndAwait(
        appContext: Context,
        apk: File,
        manifest: GeckoManifest,
    ): Boolean {
        if (!apk.exists() || apk.length() <= 0L) return false
        val previousUpdateTime = installedPackageUpdateTime(appContext)

        val silent = withContext(Dispatchers.IO) {
            SilentApkInstaller.install(appContext, apk, BrowserEngine.GECKO_ENGINE_PACKAGE)
        }
        Log.i(
            TAG,
            "SilentApkInstaller result success=${silent.success} silent=${silent.silent} " +
                "method=${silent.method} msg=${silent.message}",
        )

        when {
            silent.success && silent.silent ->
                return awaitPackCurrent(
                    appContext,
                    manifest,
                    previousUpdateTime,
                    timeoutMs = 30_000L,
                )
            // Interactive already opened the system installer — do NOT open it a second time.
            silent.success && silent.method == SilentApkInstaller.Method.INTERACTIVE -> {
                lastInstallOfferAtMs = SystemClock.elapsedRealtime()
                return awaitPackCurrent(
                    appContext,
                    manifest,
                    previousUpdateTime,
                    timeoutMs = AWAIT_PACKAGE_INSTALL_MS,
                )
            }
            else -> {
                maybeLaunchInteractiveInstaller(appContext, apk)
                return awaitPackCurrent(
                    appContext,
                    manifest,
                    previousUpdateTime,
                    timeoutMs = AWAIT_PACKAGE_INSTALL_MS,
                )
            }
        }
    }

    private suspend fun maybeLaunchInteractiveInstaller(appContext: Context, apk: File) {
        val now = SystemClock.elapsedRealtime()
        if (lastInstallOfferAtMs > 0L &&
            now - lastInstallOfferAtMs < INSTALL_REOFFER_COOLDOWN_MS
        ) {
            Log.d(TAG, "Skip re-offer gecko installer (cooldown)")
            return
        }
        lastInstallOfferAtMs = now
        withContext(Dispatchers.Main) {
            // Do not schedule AppUpdater's 60s wipe — we own gecko-*.apk until PM confirms.
            AppUpdater.installApkFile(appContext, apk, cleanupApksAfterMs = null)
        }
    }

    private suspend fun awaitPackCurrent(
        appContext: Context,
        manifest: GeckoManifest,
        previousUpdateTime: Long?,
        timeoutMs: Long,
    ): Boolean {
        if (installedPackageMatches(appContext, manifest)) return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var checkedUpdateTime: Long? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancelRequested) return false
            val updateTime = installedPackageUpdateTime(appContext)
            if (updateTime != null &&
                updateTime != previousUpdateTime &&
                updateTime != checkedUpdateTime
            ) {
                checkedUpdateTime = updateTime
                if (installedPackageMatches(appContext, manifest)) return true
                // User confirmed a fresh sideload. sourceDir hash/size can disagree
                // with the downloaded APK on some OEMs (compressed base.apk).
                if (BrowserEngine.isGeckoEnginePackInstalled(appContext)) return true
            }
            delay(PACKAGE_VISIBLE_POLL_MS)
            setPhase(appContext, InstallPhase.INSTALLING, 100)
        }
        if (installedPackageMatches(appContext, manifest)) return true
        val finalUpdateTime = installedPackageUpdateTime(appContext)
        return finalUpdateTime != null &&
            finalUpdateTime != previousUpdateTime &&
            BrowserEngine.isGeckoEnginePackInstalled(appContext)
    }

    /** Finalize only when the installed package is the exact artifact described by the manifest. */
    private fun finalizeIfAlreadyInstalled(
        appContext: Context,
        apk: File?,
        manifest: GeckoManifest,
    ): Boolean {
        if (!installedPackageMatches(appContext, manifest)) return false
        markInstalled(appContext, manifest)
        apk?.let { runCatching { if (it.exists()) it.delete() } }
        clearState(appContext)
        GeckoEngineRootSetup.maybeApply(appContext)
        return true
    }

    private suspend fun fail(
        appContext: Context,
        showToast: Boolean,
        toastResId: Int = R.string.gecko_engine_toast_install_failed
    ) {
        setPhase(appContext, InstallPhase.FAILED, 0)
        clearState(appContext)
        if (showToast) toastRes(appContext, toastResId)
    }

    private fun detectPendingSnapshot(context: Context): InstallSnapshot {
        val base = manifestBaseName() ?: return InstallSnapshot(InstallPhase.IDLE, 0, false)
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return InstallSnapshot(InstallPhase.IDLE, 0, false)
        val xzFile = File(downloadDir, "$base.apk.xz")
        val finalApk = File(downloadDir, "$base.apk")
        val packInstalled = BrowserEngine.isGeckoEnginePackInstalled(context)
        val persistedPhase = loadPersistedPhase(context)
        val saved = loadState(context)
        val hasActiveDownload = saved != null && saved.dmId != -1L && when (queryStatus(context, saved.dmId)) {
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED,
            DownloadManager.STATUS_SUCCESSFUL -> true
            else -> false
        }
        val hasActivePersistedPhase = when (persistedPhase) {
            InstallPhase.FETCHING,
            InstallPhase.DOWNLOADING,
            InstallPhase.DECOMPRESSING,
            InstallPhase.VERIFYING,
            InstallPhase.INSTALLING -> true
            else -> false
        }

        // Pack already present + no DM job:
        // - no .apk/.xz → clear sticky phase, IDLE (nothing to resume)
        // - leftover artifact but phase already IDLE/FAILED → IDLE (orphan; prune cleans)
        // - leftover artifact + active INSTALLING/… phase → keep pending (update confirm)
        //   Auto-resume is rate-limited by RESUME_COOLDOWN_MS.
        if (packInstalled && !hasActiveDownload) {
            val hasInstallerArtifact =
                (finalApk.exists() && finalApk.length() > 0L) ||
                    (xzFile.exists() && xzFile.length() > 0L)
            if (!hasInstallerArtifact) {
                if (hasActivePersistedPhase) {
                    setPhase(context, InstallPhase.IDLE, 0)
                }
                return InstallSnapshot(InstallPhase.IDLE, 0, false)
            }
            // Pack is already visible. A leftover APK is only an update job when we
            // still have saved download state whose sha is not the installed one.
            // Otherwise settings polling would re-offer the installer forever.
            val leftoverIsPendingUpdate = saved != null &&
                saved.sha.isNotBlank() &&
                !saved.sha.equals(
                    context.getSharedPreferences(INSTALLED_PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_INSTALLED_SHA, null),
                    ignoreCase = true,
                )
            if (!hasActivePersistedPhase || !leftoverIsPendingUpdate) {
                if (hasActivePersistedPhase && !leftoverIsPendingUpdate) {
                    setPhase(context, InstallPhase.IDLE, 0)
                }
                return InstallSnapshot(InstallPhase.IDLE, 0, false)
            }
        }

        if (finalApk.exists() && finalApk.length() > 0L) {
            return InstallSnapshot(
                InstallPhase.INSTALLING,
                loadPersistedProgress(context).coerceAtLeast(0),
                false,
            )
        }
        if (xzFile.exists() && xzFile.length() > 0L) {
            return InstallSnapshot(InstallPhase.DECOMPRESSING, 0, false)
        }
        if (packInstalled) {
            return InstallSnapshot(InstallPhase.IDLE, 0, false)
        }

        if (saved != null && saved.dmId != -1L) {
            when (queryStatus(context, saved.dmId)) {
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED ->
                    return InstallSnapshot(
                        InstallPhase.DOWNLOADING,
                        queryDownloadProgress(context, saved.dmId),
                        false
                    )
                DownloadManager.STATUS_SUCCESSFUL ->
                    return InstallSnapshot(InstallPhase.DECOMPRESSING, 0, false)
                else -> { /* fall through */ }
            }
        }

        if (persistedPhase != InstallPhase.IDLE && persistedPhase != InstallPhase.FAILED) {
            return InstallSnapshot(persistedPhase, loadPersistedProgress(context), false)
        }
        return InstallSnapshot(InstallPhase.IDLE, 0, false)
    }

    private fun setPhase(context: Context, phase: InstallPhase, progress: Int) {
        currentPhase = phase
        currentProgress = progress.coerceIn(0, 100)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHASE, phase.name)
            .putInt(KEY_PROGRESS, currentProgress)
            .apply()
    }

    private fun loadPersistedPhase(context: Context): InstallPhase {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHASE, InstallPhase.IDLE.name) ?: InstallPhase.IDLE.name
        return runCatching { InstallPhase.valueOf(name) }.getOrDefault(InstallPhase.IDLE)
    }

    private fun loadPersistedProgress(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PROGRESS, 0)

    private suspend fun toastPhase(context: Context, phase: InstallPhase) {
        when (phase) {
            InstallPhase.FETCHING -> toastRes(context, R.string.gecko_engine_toast_fetching)
            InstallPhase.DOWNLOADING -> toastRes(context, R.string.gecko_engine_toast_downloading, currentProgress)
            InstallPhase.DECOMPRESSING -> toastRes(context, R.string.gecko_engine_toast_decompressing, currentProgress)
            InstallPhase.VERIFYING -> toastRes(context, R.string.gecko_engine_toast_verifying)
            InstallPhase.INSTALLING -> {
                toastRes(context, R.string.gecko_engine_toast_install_prompt)
                delay(3_500)
                toastRes(context, R.string.gecko_engine_toast_install_wait)
            }
            else -> Unit
        }
    }

    fun checkForUpdate(context: Context, onResult: (UpdateResult) -> Unit) {
        val appContext = context.applicationContext
        scope.launch {
            val result = evaluateUpdate(appContext)
            withContext(Dispatchers.Main) {
                try {
                    onResult(result)
                } catch (e: Exception) {
                    Log.e(TAG, "checkForUpdate callback failed", e)
                }
            }
        }
    }

    private suspend fun evaluateUpdate(appContext: Context): UpdateResult {
        return try {
            val base = manifestBaseName()
            when {
                base == null -> UpdateResult.UNSUPPORTED
                !BrowserEngine.isGeckoEnginePackInstalled(appContext) -> UpdateResult.NOT_INSTALLED
                else -> {
                    val manifest = fetchManifest(appContext, base) ?: return UpdateResult.NETWORK_ERROR
                    cleanupStaleDownloadArtifacts(appContext, base, manifest)
                    val artifact = installedPackageArtifact(appContext)
                    val prefs = appContext.getSharedPreferences(INSTALLED_PREFS, Context.MODE_PRIVATE)
                    val pinnedSha = prefs.getString(KEY_INSTALLED_SHA, null)
                    val pinnedUpdateTime = prefs.getLong(KEY_INSTALLED_PACKAGE_UPDATE_TIME, -1L)
                    // After a confirmed sideload we pin the remote sha to that package
                    // generation. OEM sourceDir hashing can disagree with the download.
                    if (artifact != null &&
                        pinnedSha != null &&
                        pinnedSha.equals(manifest.sha256, ignoreCase = true) &&
                        pinnedUpdateTime == artifact.updateTime
                    ) {
                        UpdateResult.UP_TO_DATE
                    } else {
                        val localSha = resolveInstalledSha(appContext)
                        if (localSha != null &&
                            localSha.equals(manifest.sha256, ignoreCase = true)
                        ) {
                            UpdateResult.UP_TO_DATE
                        } else {
                            UpdateResult.UPDATE_AVAILABLE
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "evaluateUpdate failed", e)
            UpdateResult.NETWORK_ERROR
        }
    }

    private fun resolveInstalledSha(context: Context): String? {
        val artifact = installedPackageArtifact(context) ?: return null
        val prefs = context.getSharedPreferences(INSTALLED_PREFS, Context.MODE_PRIVATE)
        val cachedSha = prefs.getString(KEY_INSTALLED_SHA, null)
        val cachedSize = prefs.getLong(KEY_INSTALLED_SIZE, -1L)
        val cachedUpdateTime = prefs.getLong(KEY_INSTALLED_PACKAGE_UPDATE_TIME, -1L)
        if (cachedSha != null &&
            cachedSize == artifact.apk.length() &&
            cachedUpdateTime == artifact.updateTime
        ) {
            return cachedSha
        }

        return try {
            val computed = sha256(artifact.apk)
            markInstalled(context, GeckoManifest(computed, artifact.apk.length()))
            computed
        } catch (e: Exception) {
            Log.w(TAG, "resolveInstalledSha failed", e)
            null
        }
    }

    private data class InstalledPackageArtifact(
        val apk: File,
        val updateTime: Long,
    )

    private fun installedPackageArtifact(context: Context): InstalledPackageArtifact? {
        return try {
            val info = context.packageManager.getPackageInfo(BrowserEngine.GECKO_ENGINE_PACKAGE, 0)
            val sourceDir = info.applicationInfo?.sourceDir ?: return null
            val apk = File(sourceDir)
            if (!apk.isFile || apk.length() <= 0L) return null
            InstalledPackageArtifact(apk, info.lastUpdateTime)
        } catch (_: Exception) {
            null
        }
    }

    private fun installedPackageUpdateTime(context: Context): Long? =
        installedPackageArtifact(context)?.updateTime

    private fun installedPackageMatches(context: Context, manifest: GeckoManifest): Boolean {
        val artifact = installedPackageArtifact(context) ?: return false
        if (artifact.apk.length() != manifest.size) return false
        val actualSha = resolveInstalledSha(context) ?: return false
        return actualSha.equals(manifest.sha256, ignoreCase = true)
    }

    fun uninstall(context: Context) {
        val appContext = context.applicationContext
        cleanupLocalFiles(appContext)
        clearState(appContext)
        clearInstalledInfo(appContext)
        GeckoEngineRootSetup.clearAppliedFlag(appContext)
        setPhase(appContext, InstallPhase.IDLE, 0)

        val launcher = context.findActivityContext() ?: context
        val uri = Uri.fromParts("package", BrowserEngine.GECKO_ENGINE_PACKAGE, null)
        val uninstallIntent = Intent(Intent.ACTION_DELETE, uri).apply {
            if (launcher !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val intentToLaunch = if (uninstallIntent.resolveActivity(appContext.packageManager) != null) {
            uninstallIntent
        } else {
            Log.w(TAG, "No handler for ACTION_DELETE, falling back to app details")
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).apply {
                if (launcher !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        try {
            launcher.startActivity(intentToLaunch)
            scope.launch {
                toastRes(appContext, R.string.gecko_engine_uninstall_launched)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Launch uninstall failed", e)
            scope.launch {
                toastRes(appContext, R.string.gecko_engine_uninstall_failed)
            }
        }
    }

    private tailrec fun Context.findActivityContext(): Activity? {
        return when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivityContext()
            else -> null
        }
    }

    private fun cleanupLocalFiles(context: Context) {
        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            listOf(
                "gecko-arm64.apk.xz", "gecko-arm64.apk",
                "gecko-armv7.apk.xz", "gecko-armv7.apk"
            ).forEach { name ->
                val f = File(dir, name)
                if (f.exists()) f.delete()
            }
            loadState(context)?.dmId?.let { removeDownload(context, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup local files failed", e)
        }
    }

    /**
     * Remove download artifacts that no longer match the remote manifest or installed engine.
     * Prevents stale multi-hundred-MB .xz/.apk files from filling storage and blocking installs.
     */
    private fun cleanupStaleDownloadArtifacts(
        context: Context,
        base: String,
        remoteManifest: GeckoManifest
    ) {
        if (inProgress) return
        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            val otherBase = if (base == "gecko-arm64") "gecko-armv7" else "gecko-arm64"
            for (name in listOf("$otherBase.apk.xz", "$otherBase.apk")) {
                val f = File(dir, name)
                if (f.exists() && f.delete()) {
                    Log.i(TAG, "Removed other-ABI artifact: $name")
                }
            }

            val xzFile = File(dir, "$base.apk.xz")
            val apkFile = File(dir, "$base.apk")
            val saved = loadState(context)
            // Package presence and prefs alone are not proof of an update. The installed APK must
            // match the exact artifact advertised by the remote manifest.
            val engineUpToDate = installedPackageMatches(context, remoteManifest)

            if (saved != null &&
                (!saved.sha.equals(remoteManifest.sha256, ignoreCase = true) ||
                    saved.size != remoteManifest.size ||
                    saved.base != base)
            ) {
                Log.i(TAG, "Saved download state is stale (${saved.sha}), clearing")
                removeDownload(context, saved.dmId)
                clearState(context)
                if (xzFile.exists()) xzFile.delete()
                if (apkFile.exists()) apkFile.delete()
            }

            if (apkFile.exists()) {
                val apkSha = runCatching { sha256(apkFile) }.getOrNull()
                val matchesRemote = apkFile.length() == remoteManifest.size &&
                    apkSha?.equals(remoteManifest.sha256, ignoreCase = true) == true
                when {
                    engineUpToDate -> {
                        Log.i(TAG, "Engine up to date, removing leftover APK installer artifact")
                        apkFile.delete()
                    }
                    !matchesRemote -> {
                        Log.i(TAG, "Removing stale APK artifact (${apkFile.length()} bytes)")
                        apkFile.delete()
                    }
                }
            }

            if (xzFile.exists() && !isXzArtifactCurrent(context, base, remoteManifest, xzFile, loadState(context))) {
                Log.i(TAG, "Removing stale XZ artifact (${xzFile.length()} bytes)")
                xzFile.delete()
                loadState(context)?.dmId?.let { removeDownload(context, it) }
            } else if (engineUpToDate && xzFile.exists()) {
                Log.i(TAG, "Engine up to date, removing leftover XZ artifact")
                xzFile.delete()
                loadState(context)?.dmId?.let { removeDownload(context, it) }
                clearState(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "cleanupStaleDownloadArtifacts failed", e)
        }
    }

    /** True when an on-disk .xz can be resumed for [remoteManifest] (matches saved state). */
    private fun isXzArtifactCurrent(
        context: Context,
        base: String,
        remoteManifest: GeckoManifest,
        xzFile: File,
        saved: SavedState?
    ): Boolean {
        if (!xzFile.exists() || xzFile.length() <= 0L) return false
        if (saved == null || saved.base != base) return false
        if (!saved.sha.equals(remoteManifest.sha256, ignoreCase = true)) return false
        if (saved.size != remoteManifest.size) return false
        if (saved.dmId != -1L) {
            when (queryStatus(context, saved.dmId)) {
                DownloadManager.STATUS_FAILED -> return false
            }
        }
        return true
    }

    private fun markInstalled(context: Context, manifest: GeckoManifest) {
        try {
            context.getSharedPreferences(INSTALLED_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_INSTALLED_SHA, manifest.sha256)
                .putLong(KEY_INSTALLED_SIZE, manifest.size)
                .putLong(
                    KEY_INSTALLED_PACKAGE_UPDATE_TIME,
                    installedPackageUpdateTime(context) ?: -1L,
                )
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "markInstalled failed", e)
        }
    }

    private fun clearInstalledInfo(context: Context) {
        try {
            context.getSharedPreferences(INSTALLED_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "clearInstalledInfo failed", e)
        }
    }

    private suspend fun decompressXz(
        src: File,
        dest: File,
        expectedSize: Long,
        onProgress: (Int) -> Unit
    ): String = withContext(Dispatchers.IO) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val digest = MessageDigest.getInstance("SHA-256")
        XZInputStream(src.inputStream().buffered()).use { xin ->
            dest.outputStream().buffered().use { out ->
                val buf = ByteArray(DECOMPRESS_BUFFER)
                var sinceThrottle = 0L
                var written = 0L
                while (true) {
                    val n = xin.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    written += n
                    sinceThrottle += n
                    if (expectedSize > 0L) {
                        onProgress(((written * 100) / expectedSize).toInt().coerceIn(0, 99))
                    }
                    yield()
                    if (cancelRequested) break
                    if (sinceThrottle >= THROTTLE_EVERY_BYTES) {
                        sinceThrottle = 0
                        delay(4)
                    }
                }
                out.flush()
            }
        }
        onProgress(100)
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(DECOMPRESS_BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun fetchManifest(
        context: Context,
        base: String,
        allowCache: Boolean = false,
    ): GeckoManifest? =
        withContext(Dispatchers.IO) {
            if (allowCache) {
                val cached = cachedManifest
                val cachedBase = cachedManifestBase
                val cachedAt = cachedManifestAtMs
                if (cached != null &&
                    cachedBase == base &&
                    cachedAt > 0L &&
                    SystemClock.elapsedRealtime() - cachedAt < MANIFEST_CACHE_MS
                ) {
                    return@withContext cached
                }
            }
            val direct = GECKO_BASE_URL + "$base.json"
            for (urlString in githubUrlCandidates(context, direct)) {
                var conn: HttpURLConnection? = null
                try {
                    Log.d(TAG, "Fetching gecko manifest from $urlString")
                    conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        instanceFollowRedirects = true
                    }
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        Log.e(TAG, "Manifest HTTP ${conn.responseCode} for $urlString")
                        continue
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val manifest = json.decodeFromString<GeckoManifest>(body)
                    cachedManifestBase = base
                    cachedManifest = manifest
                    cachedManifestAtMs = SystemClock.elapsedRealtime()
                    return@withContext manifest
                } catch (e: Exception) {
                    Log.e(TAG, "Fetch manifest failed from $urlString", e)
                } finally {
                    conn?.disconnect()
                }
            }
            null
        }

    private fun enqueueDownload(context: Context, url: String, dest: File): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val title = context.getString(R.string.gecko_engine_download_notification_title)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(title)
            setDescription(dest.name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationUri(Uri.fromFile(dest))
            setMimeType("application/octet-stream")
            setAllowedOverRoaming(true)
            setAllowedOverMetered(true)
        }
        return dm.enqueue(request)
    }

    private suspend fun pollDownload(context: Context, id: Long, dest: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                while (true) {
                    if (cancelRequested) return@withContext false
                    delay(POLL_INTERVAL_MS)
                    val progress = queryDownloadProgress(context, id)
                    setPhase(context, InstallPhase.DOWNLOADING, progress)
                    when (queryStatus(context, id)) {
                        DownloadManager.STATUS_SUCCESSFUL ->
                            return@withContext dest.exists() && dest.length() > 0
                        DownloadManager.STATUS_FAILED -> return@withContext false
                        else -> { /* keep waiting */ }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } catch (e: Exception) {
                Log.e(TAG, "Poll download failed: ${dest.name}", e)
                false
            }
        }

    private fun queryDownloadProgress(context: Context, id: Long): Int {
        if (id == -1L) return 0
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return try {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return 0
                val downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (total <= 0L) return 0
                ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Query download progress failed for id=$id", e)
            0
        }
    }

    private fun queryStatus(context: Context, id: Long): Int {
        if (id == -1L) return DownloadManager.STATUS_FAILED
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return try {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) DownloadManager.STATUS_FAILED
                else c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Query status failed for id=$id", e)
            DownloadManager.STATUS_FAILED
        }
    }

    private fun removeDownload(context: Context, id: Long) {
        if (id == -1L) return
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(id)
        } catch (e: Exception) {
            Log.e(TAG, "Remove download failed for id=$id", e)
        }
    }

    private fun loadState(context: Context): SavedState? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = p.getString(KEY_BASE, null) ?: return null
        val sha = p.getString(KEY_SHA, null) ?: return null
        val size = p.getLong(KEY_SIZE, -1L)
        if (size <= 0L) return null
        return SavedState(p.getLong(KEY_DM_ID, -1L), base, sha, size)
    }

    private fun saveState(context: Context, state: SavedState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DM_ID, state.dmId)
            .putString(KEY_BASE, state.base)
            .putString(KEY_SHA, state.sha)
            .putLong(KEY_SIZE, state.size)
            .apply()
    }

    private fun clearState(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_DM_ID)
            .remove(KEY_BASE)
            .remove(KEY_SHA)
            .remove(KEY_SIZE)
            .remove(KEY_PHASE)
            .remove(KEY_PROGRESS)
            .apply()
        currentPhase = InstallPhase.IDLE
        currentProgress = 0
    }

    /** Drop DownloadManager resume keys but keep the current INSTALLING phase for UI/resume. */
    private fun clearDownloadState(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_DM_ID)
            .remove(KEY_BASE)
            .remove(KEY_SHA)
            .remove(KEY_SIZE)
            .apply()
    }

    private fun SavedState.toManifest() = GeckoManifest(sha, size)

    private suspend fun toastRes(context: Context, resId: Int, vararg args: Any) =
        withContext(Dispatchers.Main) {
            val msg = if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)
            AvaToast.show(context, msg, tag = AvaToast.HA_SYNC_TAG, durationMs = AvaToast.LONG_MS)
        }
}
