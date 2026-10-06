package com.example.ava.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import com.example.ava.net.GithubFetchGuard
import com.example.ava.net.GithubProxyUrls
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.example.ava.MainActivity
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.UpdateDownloadMethod
import com.example.ava.settings.UpdateSettings
import com.example.ava.settings.UpdateSettingsStore
import com.example.ava.settings.updateSettingsStore
import com.example.ava.utils.ScreenControlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Post-download / post-install behavior for software updates. */
data class UpdateInstallPolicy(
    val downloadMethod: UpdateDownloadMethod = UpdateDownloadMethod.BUILTIN,
    val reopenAfterUpdate: Boolean = true,
    val startServicesAfterInstall: Boolean = false,
    /** Manual picker may install older builds; entity path keeps refusal. */
    val allowDowngrade: Boolean = false,
) {
    companion object {
        fun fromSettings(settings: UpdateSettings): UpdateInstallPolicy = UpdateInstallPolicy(
            downloadMethod = settings.resolvedDownloadMethod(),
            reopenAfterUpdate = settings.reopenAfterUpdate,
            startServicesAfterInstall = settings.startServicesAfterInstall,
            allowDowngrade = false,
        )

        suspend fun load(context: Context): UpdateInstallPolicy {
            val settings = UpdateSettingsStore(context.applicationContext.updateSettingsStore).get()
            return fromSettings(settings)
        }
    }
}

@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val changelog: String = "",
    val forceUpdate: Boolean = false,
    /**
     * Optional publisher digest. When omitted or blank, stable channel uses **versionCode only**
     * (e.g. 0.6.6 → 0.6.7 in version.json without sha256 → update when versionCode rises).
     */
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val assetName: String = "",
)

data class UpdatePresentation(
    val info: UpdateInfo,
    val hasUpdate: Boolean,
    val checkFailed: Boolean = false,
)

enum class RemoteUpdatePhase {
    IDLE,
    CHECKING,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    REBOOTING,
    DONE,
    FAILED,
}

data class RemoteUpdateProgress(
    val phase: RemoteUpdatePhase,
    /** 0..100 while downloading/installing when known; otherwise null. */
    val percent: Int? = null,
    val message: String = "",
    val info: UpdateInfo? = null,
    /**
     * Only meaningful once the installer ran. False means the system install prompt is
     * showing and the package is not on the device yet.
     */
    val silentInstall: Boolean = false,
    /** True while DownloadManager is paused (tap the action button to resume). */
    val paused: Boolean = false,
)

object AppUpdater {
    private const val TAG = "AppUpdater"

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/knoop7/Ava/master/version.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // Legacy version.json may omit sha256; "sha256": null also maps to default "".
        coerceInputValues = true
    }
    private val updateMutex = Mutex()

    @Volatile
    private var isDownloading = false
    private var currentDownloadId: Long = -1
    private val _downloadPaused = MutableStateFlow(false)
    val downloadPaused = _downloadPaused.asStateFlow()
    private val _liveProgress = MutableStateFlow(RemoteUpdateProgress(RemoteUpdatePhase.IDLE))
    val liveProgress = _liveProgress.asStateFlow()

    private fun relayProgress(
        extra: (RemoteUpdateProgress) -> Unit,
    ): (RemoteUpdateProgress) -> Unit = { progress ->
        _liveProgress.value = progress
        extra(progress)
    }
    /** Leftover updater APKs (private + public Downloads). Never gecko / user files. */
    private const val MAX_KEPT_UPDATER_APKS = 2
    /** Skip-install older builds stay until this elapses, then the next sweep deletes them. */
    private const val SKIP_INSTALL_RETAIN_MS = 6L * 60 * 60 * 1000
    private const val RETAIN_PREFS = "ava_updater_retain"
    private const val KEY_RETAIN = "entries"
    /** Downloads.Impl — not in the public SDK. */
    private const val DOWNLOAD_CONTROL_RUN = 0
    private const val DOWNLOAD_CONTROL_PAUSED = 1
    private const val DOWNLOAD_STATUS_PENDING = 190
    private const val DOWNLOAD_STATUS_PAUSED_BY_APP = 193
    private val managedUpdaterApk = Regex("""^ava-\d[\w.\-]*\.apk$""", RegexOption.IGNORE_CASE)
    private val managedSystemCopyApk = Regex("""^ava-system-\d+\.apk$""", RegexOption.IGNORE_CASE)

    /**
     * Auto prompt path (MainActivity). Honors software "Later" skip for the same
     * stable candidate. Entity / manual check paths do not use this.
     */
    suspend fun checkUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val updateInfo = resolveStableCandidate(context) ?: return@withContext null
        if (!isStableUpdateAvailable(context, updateInfo)) return@withContext null
        if (UpdateArtifactStore.isSoftwarePromptSkipped(
                context,
                updateInfo.versionName,
                updateInfo.versionCode,
                updateInfo.sha256,
            )
        ) {
            Log.i(TAG, "Software auto prompt skipped for ${updateInfo.versionName}")
            return@withContext null
        }
        updateInfo
    }

    /** Persist "Later" for the in-app stable dialog only. */
    fun skipSoftwarePrompt(context: Context, updateInfo: UpdateInfo) {
        UpdateArtifactStore.skipSoftwarePrompt(
            context = context,
            versionName = updateInfo.versionName,
            versionCode = updateInfo.versionCode,
            sha256 = updateInfo.sha256,
        )
    }

    /** Always returns presentation data for manual update UI entry points (adb / broadcast). */
    suspend fun resolveManualUpdatePresentation(context: Context): UpdatePresentation =
        withContext(Dispatchers.IO) {
            val remote = resolveStableCandidate(context)
            val currentVersionCode = getVersionCode(context)
            val currentVersionName = getVersionName(context)
            if (remote != null) {
                UpdatePresentation(
                    info = remote,
                    hasUpdate = isStableUpdateAvailable(context, remote),
                )
            } else {
                UpdatePresentation(
                    info = UpdateInfo(
                        versionCode = currentVersionCode,
                        versionName = currentVersionName,
                        downloadUrl = "",
                        changelog = "",
                    ),
                    hasUpdate = false,
                    checkFailed = true,
                )
            }
        }

    /**
     * Software channel: **only** version.json (no GitHub digest fill-in).
     * - sha256 present → used for same-version rebuild detection + download verify
     * - sha256 omitted (legacy JSON) → versionCode comparison only
     */
    private suspend fun resolveStableCandidate(context: Context): UpdateInfo? {
        val base = fetchLatestRelease(context) ?: return null
        val normalizedSha = UpdateArtifactStore.normalizeSha256(base.sha256)
        val assetFromUrl = base.downloadUrl
            .substringAfterLast('/')
            .substringBefore('?')
            .takeIf { it.endsWith(".apk", ignoreCase = true) }
            .orEmpty()
        val resolved = base.copy(
            sha256 = normalizedSha,
            assetName = base.assetName.ifBlank { assetFromUrl },
        )
        Log.i(
            TAG,
            "version.json → ${resolved.versionName} (${resolved.versionCode}) " +
                "sha=${if (UpdateArtifactStore.isValidSha256(resolved.sha256)) resolved.sha256.take(12) + "…" else "(none)"}",
        )
        return resolved
    }

    private fun isStableUpdateAvailable(context: Context, remote: UpdateInfo): Boolean {
        val available = UpdateArtifactStore.isUpdateAvailable(
            context = context,
            remoteVersionCode = remote.versionCode,
            remoteSha256 = remote.sha256,
            installedVersionCode = getVersionCode(context),
            remoteSizeBytes = remote.sizeBytes,
        )
        if (!available && !UpdateArtifactStore.isValidSha256(remote.sha256)) {
            Log.d(
                TAG,
                "Stable update gate: versionCode-only (version.json has no sha256); " +
                    "remote=${remote.versionCode} installed=${getVersionCode(context)}",
            )
        }
        return available
    }

    /**
     * In-app / formal channel: version.json only (stable).
     */
    suspend fun performRemoteUpdate(
        context: Context,
        force: Boolean = false,
        policy: UpdateInstallPolicy? = null,
        onProgress: (RemoteUpdateProgress) -> Unit = {},
    ): Boolean = updateMutex.withLock {
        val notify = relayProgress(onProgress)
        val appContext = context.applicationContext
        val installPolicy = policy ?: UpdateInstallPolicy.load(appContext)
        fun progress(
            phase: RemoteUpdatePhase,
            percent: Int? = null,
            message: String = "",
            info: UpdateInfo? = null,
        ) {
            notify(RemoteUpdateProgress(phase, percent, message, info))
        }

        progress(RemoteUpdatePhase.CHECKING, message = "Checking version.json (stable)")
        val remote = resolveStableCandidate(appContext)
        if (remote == null) {
            progress(RemoteUpdatePhase.FAILED, message = "Failed to fetch version.json")
            return@withLock false
        }
        if (!force && !isStableUpdateAvailable(appContext, remote)) {
            progress(
                RemoteUpdatePhase.DONE,
                message = if (UpdateArtifactStore.isValidSha256(remote.sha256)) {
                    "Already up to date (version.json)"
                } else {
                    "Already up to date (versionCode; version.json has no sha256)"
                },
                info = remote,
            )
            return@withLock true
        }
        if (remote.downloadUrl.isBlank()) {
            progress(RemoteUpdatePhase.FAILED, message = "Empty download URL", info = remote)
            return@withLock false
        }

        installDownloadedApk(
            appContext = appContext,
            remote = remote,
            policy = installPolicy,
            onProgress = notify,
            trackStableJsonSha = true,
        )
    }

    /**
     * Manual settings picker: install a specific GitHub release APK.
     * Downgrade is allowed when [UpdateInstallPolicy.allowDowngrade] is true.
     * [forceReinstall] is kept for the settings Reinstall button. Same-artifact
     * after a pre-install SHA mark is no longer a verify failure — the installer
     * must still run so the system confirm window can appear.
     * [skipInstall] stops after a verified download (older versionCode cannot
     * overwrite the running app — caller shows uninstall / export JSON).
     */
    suspend fun performReleaseUpdate(
        context: Context,
        release: GitHubReleaseNotes.Release,
        policy: UpdateInstallPolicy? = null,
        forceReinstall: Boolean = false,
        skipInstall: Boolean = false,
        onProgress: (RemoteUpdateProgress) -> Unit = {},
    ): Boolean = updateMutex.withLock {
        val notify = relayProgress(onProgress)
        val appContext = context.applicationContext
        val installPolicy = policy ?: UpdateInstallPolicy.load(appContext).copy(allowDowngrade = true)
        fun progress(
            phase: RemoteUpdatePhase,
            percent: Int? = null,
            message: String = "",
            info: UpdateInfo? = null,
        ) {
            notify(RemoteUpdateProgress(phase, percent, message, info))
        }

        val remote = GitHubReleaseNotes.toUpdateInfo(release)
        if (remote == null) {
            progress(RemoteUpdatePhase.FAILED, message = "GitHub release has no Ava APK asset")
            return@withLock false
        }
        if (!installPolicy.allowDowngrade &&
            GitHubReleaseNotes.isClearlyOlder(release.tagName, getVersionName(appContext))
        ) {
            progress(
                RemoteUpdatePhase.FAILED,
                message = "Refusing downgrade: ${release.tagName} < ${getVersionName(appContext)}",
                info = remote,
            )
            return@withLock false
        }
        val older = GitHubReleaseNotes.isClearlyOlder(release.tagName, getVersionName(appContext))
        installDownloadedApk(
            appContext = appContext,
            remote = remote,
            policy = installPolicy,
            onProgress = notify,
            forceReinstall = forceReinstall,
            // Older versionCode cannot overwrite the running package; the
            // system installer would only show a failing confirm dialog.
            skipInstall = skipInstall || older,
        )
    }

    /**
     * HA update entity channel: GitHub release APK (beta allowed).
     * Same versionCode may reinstall; only smaller versionCode is rejected.
     */
    suspend fun performEntityUpdate(
        context: Context,
        release: GitHubReleaseNotes.Release,
        onProgress: (RemoteUpdateProgress) -> Unit = {},
    ): Boolean = updateMutex.withLock {
        val notify = relayProgress(onProgress)
        val appContext = context.applicationContext
        val installPolicy = UpdateInstallPolicy.load(appContext)
        fun progress(
            phase: RemoteUpdatePhase,
            percent: Int? = null,
            message: String = "",
            info: UpdateInfo? = null,
        ) {
            notify(RemoteUpdateProgress(phase, percent, message, info))
        }

        val remote = GitHubReleaseNotes.toUpdateInfo(release)
        if (remote == null) {
            progress(RemoteUpdatePhase.FAILED, message = "GitHub release has no Ava APK asset")
            return@withLock false
        }
        val channel = if (release.isBetaChannel()) "beta" else "stable"
        val assetLabel = remote.assetName.ifBlank { remote.versionName }
        val shaHint = UpdateArtifactStore.normalizeSha256(remote.sha256)
            .takeIf { UpdateArtifactStore.isValidSha256(it) }
            ?.let { " sha256=${it.take(12)}…" }
            .orEmpty()
        progress(
            RemoteUpdatePhase.CHECKING,
            message = "Entity target: $assetLabel ($channel)$shaHint",
            info = remote,
        )
        if (GitHubReleaseNotes.isClearlyOlder(release.tagName, getVersionName(appContext))) {
            progress(
                RemoteUpdatePhase.FAILED,
                message = "Refusing downgrade: ${release.tagName} < ${getVersionName(appContext)}",
                info = remote,
            )
            return@withLock false
        }
        // Skip only when the *running* APK already looks like this asset.
        // Stored publisher sha/size are written before the confirm window, so
        // they must not hide Install — that tap still has to open the prompt.
        val liveSize = UpdateArtifactStore.installedApkLength(appContext)
        val liveLooksInstalled = remote.sizeBytes > 0L && liveSize == remote.sizeBytes
        if (GitHubReleaseNotes.isSameVersionTuple(release.tagName, getVersionName(appContext)) &&
            liveLooksInstalled
        ) {
            progress(
                RemoteUpdatePhase.DONE,
                message = "Already installed this artifact (live size match)",
                info = remote,
            )
            return@withLock true
        }

        installDownloadedApk(
            appContext = appContext,
            remote = remote,
            policy = installPolicy,
            onProgress = notify,
        )
    }

    private suspend fun installDownloadedApk(
        appContext: Context,
        remote: UpdateInfo,
        policy: UpdateInstallPolicy,
        onProgress: (RemoteUpdateProgress) -> Unit,
        trackStableJsonSha: Boolean = false,
        forceReinstall: Boolean = false,
        skipInstall: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        // HA entity install is collected on Main (lifecycleScope). Hashing the APK
        // and PackageInstaller.latch.await must stay off that thread: the install
        // result is delivered on the main looper, so waiting for it there never
        // returns and the system shows an ANR for the whole update.
        fun progress(
            phase: RemoteUpdatePhase,
            percent: Int?,
            message: String,
            silent: Boolean = false,
            paused: Boolean = false,
        ) {
            onProgress(RemoteUpdateProgress(phase, percent, message, remote, silent, paused))
        }

        val viaSystem = policy.downloadMethod == UpdateDownloadMethod.SYSTEM
        progress(
            RemoteUpdatePhase.DOWNLOADING,
            0,
            if (viaSystem) "Downloading via system downloader" else "Downloading",
        )
        val apkFile = runCatching {
            if (viaSystem) {
                downloadApkViaSystemDownloader(appContext, remote) { pct, paused ->
                    progress(
                        RemoteUpdatePhase.DOWNLOADING,
                        pct,
                        if (paused) "Download paused" else "Downloading via system downloader",
                        paused = paused,
                    )
                }
            } else {
                downloadApkWithProgress(appContext, remote) { pct, paused ->
                    progress(
                        RemoteUpdatePhase.DOWNLOADING,
                        pct,
                        if (paused) "Download paused" else "Downloading",
                        paused = paused,
                    )
                }
            }
        }.getOrElse { e ->
            Log.e(TAG, "Download failed", e)
            progress(RemoteUpdatePhase.FAILED, null, e.message ?: "Download failed")
            return@withContext false
        }

        val expectSha = UpdateArtifactStore.jsonIncludesPublisherSha(remote.sha256)
        progress(
            RemoteUpdatePhase.VERIFYING,
            100,
            if (expectSha) "Verifying APK + version.json sha256" else "Verifying APK (version.json has no sha256)",
        )
        val fileSha = runCatching { UpdateArtifactStore.sha256Hex(apkFile) }.getOrElse { "" }
        val verified = verifyApk(
            context = appContext,
            apkFile = apkFile,
            expected = remote,
            fileSha256 = fileSha,
            trackStableJsonSha = trackStableJsonSha,
            forceReinstall = forceReinstall,
            allowDowngrade = policy.allowDowngrade,
        )
        if (verified != null) {
            progress(RemoteUpdatePhase.FAILED, null, verified)
            apkFile.delete()
            publicUpdaterApk(remote)?.delete()
            return@withContext false
        }

        if (skipInstall) {
            // Later / leaving the prompt must not delete this package. Hold
            // it for 6 hours, then sweep on the next open or when the timer fires.
            val keep = listOfNotNull(apkFile, publicUpdaterApk(remote))
            retainSkipInstallApks(appContext, keep)
            pruneUpdaterApks(appContext, protect = keep)
            scheduleSkipInstallSweep(appContext)
            progress(RemoteUpdatePhase.DONE, 100, "Downloaded; uninstall required for older build")
            return@withContext true
        }

        progress(
            RemoteUpdatePhase.INSTALLING,
            100,
            "Installing via ${SilentApkInstaller.describeCapability(appContext)}",
        )
        val shaToStore = UpdateArtifactStore.normalizeSha256(remote.sha256)
        val trackPublisherSha = UpdateArtifactStore.jsonIncludesPublisherSha(remote.sha256)
        val sizeToStore = apkFile.length().takeIf { it > 0L } ?: remote.sizeBytes
        // Persist BOTH publisher keys *before* install. Silent package replace
        // (Ava Pro / root / Shizuku / device owner) kills this process; anything
        // written after install() never runs, so HA keeps the previous rebuild
        // suffix (Installed 0.7.0.20e5cf77 vs Latest 0.7.0.bd21eaa1).
        if (trackPublisherSha) {
            UpdateArtifactStore.markInstalled(
                appContext,
                sha256 = shaToStore,
                assetName = remote.assetName,
                versionName = remote.versionName,
                sizeBytes = sizeToStore,
            )
            UpdateArtifactStore.markStableJsonSha(
                appContext,
                sha256 = shaToStore,
                versionName = remote.versionName,
                sizeBytes = sizeToStore,
            )
        }
        val install = SilentApkInstaller.install(appContext, apkFile, appContext.packageName)
        if (!install.success) {
            if (trackPublisherSha) {
                UpdateArtifactStore.clearTrackedIfPublisherSha(appContext, shaToStore)
                UpdateArtifactStore.clearStableJsonShaIfMatches(appContext, shaToStore)
            }
            progress(RemoteUpdatePhase.FAILED, null, "Install failed: ${install.message}")
            return@withContext false
        }
        if (!trackPublisherSha) {
            if (trackStableJsonSha) {
                UpdateArtifactStore.clearStableJsonSha(appContext)
            }
            Log.i(TAG, "version.json has no sha256; stable channel used versionCode-only tracking")
        }

        pruneUpdaterApks(appContext, protect = listOf(apkFile))
        applyPostInstallActions(appContext, install.silent, policy, onProgress, remote)
        true
    }

    private suspend fun applyPostInstallActions(
        appContext: Context,
        silent: Boolean,
        policy: UpdateInstallPolicy,
        onProgress: (RemoteUpdateProgress) -> Unit,
        remote: UpdateInfo,
    ) {
        fun progress(phase: RemoteUpdatePhase, message: String) {
            onProgress(RemoteUpdateProgress(phase, 100, message, remote, silent))
        }

        if (!silent) {
            progress(RemoteUpdatePhase.DONE, "Installed (user confirmation path)")
            return
        }
        delay(1200)
        if (policy.startServicesAfterInstall) {
            runCatching { startCoreServicesBestEffort(appContext) }
                .onFailure { Log.w(TAG, "startServicesAfterInstall failed", it) }
        }
        if (policy.reopenAfterUpdate) {
            progress(RemoteUpdatePhase.DONE, "Installed; reopening Ava")
            runCatching { reopenApp(appContext) }
                .onFailure { Log.w(TAG, "reopenAfterUpdate failed", it) }
            return
        }
        progress(RemoteUpdatePhase.REBOOTING, "Installed; rebooting if possible")
        val rebooted = ScreenControlUtils.rebootDevice(appContext)
        if (!rebooted) {
            Log.i(TAG, "Silent install ok; reboot unavailable (package replace will restart app)")
        }
        progress(
            RemoteUpdatePhase.DONE,
            if (rebooted) "Installed and reboot requested" else "Installed (no reboot capability)",
        )
    }

    private fun reopenApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        context.startActivity(intent)
    }

    /** Best-effort; package replace timing makes this unreliable — default off in settings. */
    private fun startCoreServicesBestEffort(context: Context) {
        val intent = Intent(context, VoiceSatelliteService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * System DownloadManager → public Downloads, wait until finished, copy into app files for install.
     * Unlike the old hand-off, this still continues to verify + [SilentApkInstaller].
     */
    private suspend fun downloadApkViaSystemDownloader(
        context: Context,
        updateInfo: UpdateInfo,
        onPercent: (Int, Boolean) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (isDownloading) error("Download already in progress")
        val appContext = context.applicationContext
        val downloadManager =
            appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadUrl = apkDownloadUrl(appContext, updateInfo.downloadUrl)
        val fileName = updateInfo.assetName.ifBlank { "Ava-${updateInfo.versionName}.apk" }

        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setTitle("Ava ${updateInfo.versionName}")
            setDescription("Downloading update...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType("application/vnd.android.package-archive")
            setAllowedOverRoaming(true)
            setAllowedOverMetered(true)
        }

        isDownloading = true
        _downloadPaused.value = false
        val downloadId = downloadManager.enqueue(request)
        currentDownloadId = downloadId
        onPercent(0, false)
        // Stay in Ava — notification-bar progress is enough. Opening the system
        // Downloads UI stole focus and made "system download" look broken.

        try {
            awaitDownloadManager(
                appContext,
                downloadManager,
                downloadId,
                onPercent = onPercent,
            )
            requireDownloadSucceeded(downloadManager, downloadId, expectedFile = null)
            onPercent(100, false)
            resolveDownloadedApk(appContext, downloadManager, downloadId)
        } finally {
            isDownloading = false
            currentDownloadId = -1
            _downloadPaused.value = false
        }
    }

    /** Copy DownloadManager result into app-private storage for verify/install. */
    private fun resolveDownloadedApk(
        appContext: Context,
        downloadManager: DownloadManager,
        downloadId: Long,
    ): File {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) error("DownloadManager query empty after success")
            val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val localUri = if (uriIdx >= 0) cursor.getString(uriIdx) else null
            val out = File(
                appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir,
                "Ava-system-${System.currentTimeMillis()}.apk",
            )
            if (out.exists()) out.delete()
            require(!localUri.isNullOrBlank()) { "System download finished but LOCAL_URI missing" }
            val uri = Uri.parse(localUri)
            when (uri.scheme) {
                "file" -> {
                    val src = File(uri.path ?: error("Empty file path from DownloadManager"))
                    src.copyTo(out, overwrite = true)
                }
                else -> {
                    appContext.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Cannot open DownloadManager URI" }
                        out.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            require(out.isFile && out.length() > 0L) { "Resolved system APK is empty" }
            return out
        }
    }

    /**
     * Same path as the in-app updater: system [DownloadManager] with notification-bar progress.
     */
    private suspend fun downloadApkWithProgress(
        context: Context,
        updateInfo: UpdateInfo,
        onPercent: (Int, Boolean) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (isDownloading) error("Download already in progress")
        val appContext = context.applicationContext
        val downloadManager =
            appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val downloadUrl = apkDownloadUrl(appContext, updateInfo.downloadUrl)
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: appContext.filesDir
        val apkFile = File(dir, "Ava-${updateInfo.versionName}.apk")
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setTitle("Ava ${updateInfo.versionName}")
            setDescription("Downloading update...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationUri(Uri.fromFile(apkFile))
            setMimeType("application/vnd.android.package-archive")
            setAllowedOverRoaming(true)
            setAllowedOverMetered(true)
        }

        isDownloading = true
        _downloadPaused.value = false
        val downloadId = downloadManager.enqueue(request)
        currentDownloadId = downloadId
        onPercent(0, false)

        try {
            awaitDownloadManager(
                appContext,
                downloadManager,
                downloadId,
                onPercent = onPercent,
            )
            requireDownloadSucceeded(downloadManager, downloadId, expectedFile = apkFile)
            onPercent(100, false)
            apkFile
        } finally {
            isDownloading = false
            currentDownloadId = -1
            _downloadPaused.value = false
        }
    }

    private suspend fun awaitDownloadManager(
        appContext: Context,
        downloadManager: DownloadManager,
        downloadId: Long,
        onPercent: (Int, Boolean) -> Unit,
    ): Unit = suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                try {
                    ctx.unregisterReceiver(this)
                } catch (_: Exception) {
                }
                // Delivered on the main looper. Reading the provider or running
                // onPercent here blocks the UI; the download coroutine does both.
                if (cont.isActive) cont.resume(Unit)
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }

        val progressJob = CoroutineScope(Dispatchers.IO).launch {
            pollDownloadProgress(downloadManager, downloadId, onPercent)
        }

        cont.invokeOnCancellation {
            progressJob.cancel()
            runCatching { downloadManager.remove(downloadId) }
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    /** Runs on the download coroutine, not the main-thread completion receiver. */
    private fun requireDownloadSucceeded(
        downloadManager: DownloadManager,
        downloadId: Long,
        expectedFile: File?,
    ) {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) error("DownloadManager query empty")
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                error("DownloadManager failed status=$status reason=$reason")
            }
        }
        if (expectedFile != null && !expectedFile.exists()) {
            error("APK missing after DownloadManager success")
        }
    }

    private suspend fun pollDownloadProgress(
        downloadManager: DownloadManager,
        downloadId: Long,
        onPercent: (Int, Boolean) -> Unit,
    ) {
        var lastPct = -1
        var lastPaused: Boolean? = null
        while (currentCoroutineContext().isActive) {
            delay(500)
            val query = DownloadManager.Query().setFilterById(downloadId)
            downloadManager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL ||
                    status == DownloadManager.STATUS_FAILED
                ) {
                    return
                }
                // Do not infer user-pause from STATUS_PAUSED — that also covers
                // waiting-for-network / retry, and would flip a just-resumed
                // download back to "paused" so Continue does nothing.
                val paused = _downloadPaused.value
                val downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                )
                val total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                )
                val pct = if (total > 0) {
                    ((downloaded * 100) / total).toInt().coerceIn(0, 99)
                } else {
                    lastPct.coerceAtLeast(0)
                }
                if (pct != lastPct || paused != lastPaused) {
                    lastPct = pct
                    lastPaused = paused
                    onPercent(pct, paused)
                }
            }
        }
    }

    fun isDownloadPauseable(): Boolean = isDownloading && currentDownloadId >= 0

    /** Pause a running DownloadManager job, or resume a paused one. */
    fun toggleDownloadPause(context: Context): Boolean {
        val id = currentDownloadId
        if (!isDownloading || id < 0) return false
        val appContext = context.applicationContext
        return if (_downloadPaused.value) {
            resumeManagedDownload(appContext, id)
        } else {
            pauseManagedDownload(appContext, id)
        }
    }

    private fun pauseManagedDownload(context: Context, id: Long): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        invokeHiddenDownloadControl(dm, "pauseDownload", id)
        val written = setDownloadControl(context, id, paused = true)
        if (written) {
            _downloadPaused.value = true
            Log.i(TAG, "Paused download id=$id")
            return true
        }
        Log.w(TAG, "Could not pause download id=$id")
        return false
    }

    private fun resumeManagedDownload(context: Context, id: Long): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        invokeHiddenDownloadControl(dm, "resumeDownload", id)
        val written = setDownloadControl(context, id, paused = false)
        // Always clear the local flag so the button cannot get stuck on
        // "paused" when DownloadManager still reports STATUS_PAUSED briefly.
        _downloadPaused.value = false
        if (written) {
            Log.i(TAG, "Resumed download id=$id")
        } else {
            Log.w(TAG, "Resume control write missed id=$id; local pause cleared")
        }
        return true
    }

    @Suppress("PrivateApi")
    private fun invokeHiddenDownloadControl(
        downloadManager: DownloadManager,
        method: String,
        id: Long,
    ): Boolean = runCatching {
        val fn = DownloadManager::class.java.getMethod(method, LongArray::class.java)
        fn.invoke(downloadManager, longArrayOf(id))
        true
    }.onFailure {
        Log.d(TAG, "$method via reflection unavailable: ${it.message}")
    }.getOrDefault(false)

    /**
     * Writes the same columns as hidden [DownloadManager.pauseDownload] /
     * resumeDownload. Resume must also set status back to pending — control=0
     * alone leaves many devices stuck in PAUSED_BY_APP.
     */
    private fun setDownloadControl(context: Context, id: Long, paused: Boolean): Boolean {
        val values = ContentValues().apply {
            put("control", if (paused) DOWNLOAD_CONTROL_PAUSED else DOWNLOAD_CONTROL_RUN)
            put("status", if (paused) DOWNLOAD_STATUS_PAUSED_BY_APP else DOWNLOAD_STATUS_PENDING)
        }
        val resolver = context.contentResolver
        val byId = listOf(
            Uri.parse("content://downloads/my_downloads/$id"),
            Uri.parse("content://downloads/all_downloads/$id"),
        )
        for (uri in byId) {
            val rows = runCatching { resolver.update(uri, values, null, null) }.getOrDefault(0)
            if (rows > 0) return true
        }
        val collection = Uri.parse("content://downloads/my_downloads")
        val rows = runCatching {
            resolver.update(collection, values, "_id=?", arrayOf(id.toString()))
        }.getOrDefault(0)
        if (rows > 0) return true
        Log.w(TAG, "Download control update wrote 0 rows id=$id paused=$paused")
        return false
    }

    /**
     * @return null when OK; otherwise a human-readable rejection reason.
     */
    private fun verifyApk(
        context: Context,
        apkFile: File,
        expected: UpdateInfo,
        fileSha256: String,
        trackStableJsonSha: Boolean = false,
        forceReinstall: Boolean = false,
        allowDowngrade: Boolean = false,
    ): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } ?: return "Unreadable APK"

        if (info.packageName != context.packageName) {
            return "Package mismatch: ${info.packageName}"
        }
        val apkCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        val current = getVersionCode(context)
        if (apkCode < current && !allowDowngrade) {
            return "Refusing downgrade: APK versionCode $apkCode < installed $current"
        }

        val expectedSha = UpdateArtifactStore.normalizeSha256(expected.sha256)
        val actualSha = UpdateArtifactStore.normalizeSha256(fileSha256)
        if (expectedSha.length == 64 && actualSha.length == 64 &&
            !expectedSha.equals(actualSha, ignoreCase = true)
        ) {
            return "SHA-256 mismatch: expected ${expectedSha.take(12)}… got ${actualSha.take(12)}…"
        }
        // Publisher sha is written *before* PackageInstaller confirm (silent
        // replace kills the process). Matching that stored digest is not a
        // failure — user tap / auto-update must still reach SilentApkInstaller
        // so the system confirm window can appear. Real rejects stay above:
        // unreadable APK, wrong package, downgrade, SHA mismatch.
        val alreadyTracked = when {
            !UpdateArtifactStore.isValidSha256(actualSha) -> false
            trackStableJsonSha &&
                UpdateArtifactStore.jsonIncludesPublisherSha(expected.sha256) -> {
                val storedJsonSha = UpdateArtifactStore.getStableJsonSha256(context)
                storedJsonSha != null &&
                    storedJsonSha.equals(expectedSha, ignoreCase = true) &&
                    actualSha.equals(expectedSha, ignoreCase = true)
            }
            else -> UpdateArtifactStore.matchesPublisherBaseline(context, actualSha)
        }
        if (alreadyTracked) {
            Log.i(
                TAG,
                if (forceReinstall) {
                    "Reinstall: publisher sha already tracked, opening installer"
                } else {
                    "Publisher sha already tracked; continuing to installer"
                },
            )
        }
        if (expected.versionCode > 0 && apkCode != expected.versionCode) {
            Log.w(TAG, "APK versionCode=$apkCode differs from manifest ${expected.versionCode}")
        }
        return null
    }

    private suspend fun fetchLatestRelease(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val urls = GithubProxyUrls.cautiousCandidates(appContext, VERSION_URL)
        if (urls.isEmpty()) {
            Log.w(TAG, "version.json mirrors cooling down; skip network")
            return@withContext null
        }
        for (urlString in urls) {
            var connection: HttpURLConnection? = null
            try {
                Log.d(TAG, "Fetching release info from: $urlString")
                connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                }
                val responseCode = connection.responseCode
                Log.d(TAG, "Response code: $responseCode for $urlString")
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val parsed = json.decodeFromString<UpdateInfo>(response)
                    GithubFetchGuard.rememberSuccess(urlString)
                    return@withContext parsed
                }
                GithubFetchGuard.rememberFailure(urlString)
                Log.w(TAG, "HTTP error: $responseCode for $urlString")
            } catch (e: Exception) {
                GithubFetchGuard.rememberFailure(urlString)
                Log.w(TAG, "Fetch release failed from $urlString: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }
        null
    }

    private fun apkDownloadUrl(context: Context, downloadUrl: String): String {
        val raw = downloadUrl.trim()
        if (raw.isEmpty()) return raw
        return GithubProxyUrls.candidates(context, raw).firstOrNull() ?: raw
    }

    fun getVersionName(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    /**
     * Dialog / legacy entry: same download → verify → [SilentApkInstaller] pipeline as
     * [performRemoteUpdate]. Always uses builtin download so the in-app update UI
     * is not replaced by the system Downloads screen.
     */
    fun downloadAndInstall(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (RemoteUpdateProgress) -> Unit = {},
    ) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            downloadAndInstallAwait(appContext, updateInfo, onProgress)
        }
    }

    suspend fun downloadAndInstallAwait(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (RemoteUpdateProgress) -> Unit = {},
    ): Boolean = updateMutex.withLock {
        val notify = relayProgress(onProgress)
        if (isDownloading) {
            Log.d(TAG, "Download already in progress, ignoring request")
            notify(
                RemoteUpdateProgress(
                    RemoteUpdatePhase.FAILED,
                    message = "Download already in progress",
                    info = updateInfo,
                ),
            )
            return@withLock false
        }
        val appContext = context.applicationContext
        val policy = UpdateInstallPolicy.load(appContext).copy(
            downloadMethod = UpdateDownloadMethod.BUILTIN,
        )
        installDownloadedApk(
            appContext = appContext,
            remote = updateInfo,
            policy = policy,
            onProgress = { progress ->
                Log.i(TAG, "downloadAndInstall: ${progress.message}")
                notify(progress)
            },
            trackStableJsonSha = true,
        )
    }

    /**
     * Public entry so other installers (e.g. the gecko engine multi-part downloader) can reuse the
     * FileProvider install flow.
     *
     * @param cleanupApksAfterMs when non-null, prune leftover updater APKs (keep at most 2)
     * after this delay. Pass `null` for gecko installs: deleting mid-confirmation breaks
     * PackageInstaller and those files are owned by [com.example.ava.webcompat.GeckoEngineInstaller].
     */
    fun installApkFile(
        context: Context,
        apkFile: File,
        cleanupApksAfterMs: Long? = 60_000L,
    ) = installApk(context, apkFile, cleanupApksAfterMs)

    private fun installApk(
        context: Context,
        apkFile: File,
        cleanupApksAfterMs: Long?,
    ) {
        try {
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found: ${apkFile.absolutePath}")
                return
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile,
                    )
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            if (cleanupApksAfterMs != null && cleanupApksAfterMs >= 0L) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    pruneUpdaterApks(context, protect = listOf(apkFile))
                }, cleanupApksAfterMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Install APK failed", e)
        }
    }

    private fun isManagedUpdaterApkName(name: String): Boolean {
        val n = name.trim()
        if (!n.endsWith(".apk", ignoreCase = true)) return false
        if (n.contains("gecko", ignoreCase = true)) return false
        return managedUpdaterApk.matches(n) || managedSystemCopyApk.matches(n)
    }

    private fun publicUpdaterApk(remote: UpdateInfo): File? {
        val name = remote.assetName.ifBlank { "Ava-${remote.versionName}.apk" }
        if (!isManagedUpdaterApkName(name)) return null
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: return null
        return File(dir, name).takeIf { it.isFile }
    }

    /** Drop skip-install APKs whose 6-hour hold has elapsed. Safe to call on resume. */
    fun sweepUpdaterArtifacts(context: Context) {
        val appContext = context.applicationContext
        sweepExpiredRetainedApks(appContext)
        pruneUpdaterApks(appContext)
    }

    private fun retainSkipInstallApks(context: Context, files: List<File>) {
        val until = System.currentTimeMillis() + SKIP_INSTALL_RETAIN_MS
        val map = loadRetainMap(context).toMutableMap()
        files.forEach { file ->
            if (!file.isFile) return@forEach
            val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            map[path] = until
        }
        saveRetainMap(context, map)
    }

    private fun scheduleSkipInstallSweep(context: Context) {
        val appContext = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            sweepUpdaterArtifacts(appContext)
        }, SKIP_INSTALL_RETAIN_MS)
    }

    private fun unexpiredRetainPaths(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        return loadRetainMap(context)
            .filter { it.value > now }
            .keys
    }

    private fun sweepExpiredRetainedApks(context: Context) {
        val now = System.currentTimeMillis()
        val map = loadRetainMap(context).toMutableMap()
        val deletedNames = mutableSetOf<String>()
        val iterator = map.iterator()
        while (iterator.hasNext()) {
            val (path, until) = iterator.next()
            val file = File(path)
            if (!file.isFile) {
                iterator.remove()
                continue
            }
            if (until > now) continue
            if (!isManagedUpdaterApkName(file.name)) {
                iterator.remove()
                continue
            }
            if (file.delete()) {
                deletedNames += file.name
                Log.i(TAG, "Expired skip-install APK: ${file.name}")
            }
            iterator.remove()
        }
        saveRetainMap(context, map)
        if (deletedNames.isNotEmpty()) {
            pruneDownloadManagerCopies(context, deletedNames)
        }
    }

    private fun loadRetainMap(context: Context): Map<String, Long> {
        val raw = context.applicationContext
            .getSharedPreferences(RETAIN_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RETAIN, "")
            .orEmpty()
        if (raw.isBlank()) return emptyMap()
        return raw.lineSequence().mapNotNull { line ->
            val sep = line.lastIndexOf('\t')
            if (sep <= 0) return@mapNotNull null
            val path = line.substring(0, sep)
            val until = line.substring(sep + 1).toLongOrNull() ?: return@mapNotNull null
            path to until
        }.toMap()
    }

    private fun saveRetainMap(context: Context, map: Map<String, Long>) {
        val raw = map.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        context.applicationContext
            .getSharedPreferences(RETAIN_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RETAIN, raw)
            .apply()
    }

    /**
     * Keep at most [MAX_KEPT_UPDATER_APKS] newest updater APKs across app-private
     * and public Downloads. Only [isManagedUpdaterApkName] files — never gecko,
     * never `Ava-custom*.apk`, never the user's other downloads.
     * Skip-install files still inside the 6-hour hold are never deleted here.
     */
    private fun pruneUpdaterApks(context: Context, protect: List<File> = emptyList()) {
        try {
            val protectedPaths = (
                protect.mapNotNull { file ->
                    file.takeIf { it.isFile }?.let { runCatching { it.canonicalPath }.getOrNull() }
                } + unexpiredRetainPaths(context)
            ).toSet()
            val candidates = mutableListOf<File>()
            val privateDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            privateDir?.listFiles()?.forEach { file ->
                if (file.isFile && isManagedUpdaterApkName(file.name)) candidates += file
            }
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            publicDir?.listFiles()?.forEach { file ->
                if (file.isFile && isManagedUpdaterApkName(file.name)) candidates += file
            }
            val unique = candidates.distinctBy {
                runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
            }
            if (unique.size <= MAX_KEPT_UPDATER_APKS) return
            val protectedFiles = unique.filter {
                runCatching { it.canonicalPath }.getOrNull() in protectedPaths
            }
            val others = unique
                .filter { runCatching { it.canonicalPath }.getOrNull() !in protectedPaths }
                .sortedByDescending { it.lastModified() }
            val keepSlots = (MAX_KEPT_UPDATER_APKS - protectedFiles.size).coerceAtLeast(0)
            val keep = protectedFiles + others.take(keepSlots)
            val keepPaths = keep.map {
                runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
            }.toSet()
            val deletedNames = mutableSetOf<String>()
            unique.forEach { file ->
                val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (path in keepPaths) return@forEach
                if (file.delete()) {
                    deletedNames += file.name
                    Log.i(TAG, "Pruned updater APK: ${file.name}")
                } else {
                    Log.w(TAG, "Could not prune updater APK: ${file.absolutePath}")
                }
            }
            if (deletedNames.isNotEmpty()) {
                pruneDownloadManagerCopies(context, deletedNames)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Updater APK prune failed", e)
        }
    }

    /** Clear DownloadManager rows only for files we just deleted. */
    private fun pruneDownloadManagerCopies(context: Context, deletedNames: Set<String>) {
        if (isDownloading || deletedNames.isEmpty()) return
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return
        val query = DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return
            val idIdx = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (idIdx < 0) return
            do {
                val id = cursor.getLong(idIdx)
                if (id == currentDownloadId) continue
                val title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty() else ""
                if (!title.startsWith("Ava ")) continue
                val localName = if (uriIdx >= 0) {
                    cursor.getString(uriIdx)?.substringAfterLast('/')?.substringBefore('?').orEmpty()
                } else {
                    ""
                }
                if (localName !in deletedNames) continue
                downloadManager.remove(id)
                Log.i(TAG, "Pruned DownloadManager entry id=$id name=$localName")
            } while (cursor.moveToNext())
        }
    }

    fun getVersionCode(context: Context): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            0
        }
    }
}
