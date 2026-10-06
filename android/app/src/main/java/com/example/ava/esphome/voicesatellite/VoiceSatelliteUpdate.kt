package com.example.ava.esphome.voicesatellite

import android.content.Context
import android.util.Log
import com.example.ava.R
import com.example.ava.esphome.EspHomeDevice
import com.example.ava.esphome.entities.UpdateEntity
import com.example.ava.esphome.entities.UpdateEntityState
import com.example.ava.update.AppUpdater
import com.example.ava.update.GitHubReleaseNotes
import com.example.ava.update.RemoteUpdatePhase
import com.example.ava.update.SilentApkInstaller
import com.example.ava.update.UpdateArtifactStore
import com.example.ava.update.UpdateInfo
import com.example.esphomeproto.api.EntityCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * HA `update` entity installs GitHub APKs (beta allowed).
 * Entity availability: newer tag, or same-tag rebuild when a publisher digest was
 * tracked by the updater. In-app software prompts use version.json only.
 */
class VoiceSatelliteUpdate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val device: EspHomeDevice,
) {
    private val mutex = Mutex()
    private var checkJob: Job? = null
    private var installJob: Job? = null
    private var entity: UpdateEntity? = null
    private var lastSoftwareStable: UpdateInfo? = null
    private var lastNotes: GitHubReleaseNotes.EntityNotes? = null
    private var lastReleaseUrl: String = RELEASES_PAGE

    fun init() {
        if (entity != null) return
        val updateEntity = UpdateEntity(
            key = "firmware_update".hashCode(),
            name = context.getString(R.string.entity_firmware_update),
            objectId = "firmware_update",
            icon = "mdi:cellphone-arrow-down",
            deviceClass = "firmware",
            entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC,
            onCheck = { requestCheck(forceRefresh = true) },
            onInstall = { requestInstall() },
        )
        entity = updateEntity
        device.addEntity(updateEntity)
        publishBaseline()
        requestCheck()
        Log.i(TAG, "Firmware update entity registered (capability=${SilentApkInstaller.describeCapability(context)})")
    }

    fun dispose() {
        checkJob?.cancel()
        checkJob = null
        installJob?.cancel()
        installJob = null
        val existing = entity ?: return
        runCatching { device.removeEntity(existing) }
        entity = null
        lastNotes = null
        lastSoftwareStable = null
        Log.i(TAG, "Firmware update entity removed")
    }

    fun isRegistered(): Boolean = entity != null

    /** Footer under the GitHub body — install tip only, no technical dump. */
    private fun installTipFooter(): String = when {
        SilentApkInstaller.describeCapability(context) == "shizuku" ->
            "Install: Shizuku is authorized on this device. Tap Update to download and install automatically. " +
                "If no install prompt appears, open the GitHub link above and install the release APK manually."
        SilentApkInstaller.describeCapability(context) == "root" ->
            "Install: Root access is available on this device. Tap Update to download and install automatically. " +
                "If no install prompt appears, open the GitHub link above and install the release APK manually."
        SilentApkInstaller.describeCapability(context) == "device_owner" ->
            "Install: This device is managed as device owner. Tap Update to download and install automatically. " +
                "If no install prompt appears, open the GitHub link above and install the release APK manually."
        else ->
            "Install: Tap Update to download the package. Confirm the system install prompt when it appears. " +
                "If no install prompt appears, open the GitHub link above and install the release APK manually."
    }

    private fun publishBaseline(
        notes: GitHubReleaseNotes.EntityNotes? = lastNotes,
    ) {
        val e = entity ?: return
        val currentName = AppUpdater.getVersionName(context)
        lastReleaseUrl = notes?.releaseUrl() ?: RELEASES_PAGE
        val summary = notes?.formatSummary(installTipFooter())
            ?: buildString {
                appendLine("_No GitHub release notes available._")
                appendLine()
                appendLine("---")
                append(installTipFooter())
            }.trim()

        // Same version + same sha → HA must stay off (do not fall back to softwareStable name).
        if (notes?.entityHasUpdate != true) {
            e.publish(
                UpdateEntityState(
                    inProgress = false,
                    hasProgress = false,
                    progress = 0f,
                    currentVersion = currentName,
                    latestVersion = currentName,
                    title = "",
                    releaseSummary = summary,
                    releaseUrl = lastReleaseUrl,
                ),
            )
            return
        }

        // Either persisted publisher digest — never on-disk APK hash.
        val baselineSha = UpdateArtifactStore.getPublisherBaselineSha256(context)
        e.publish(
            UpdateEntityState(
                inProgress = false,
                hasProgress = false,
                progress = 0f,
                currentVersion = notes.entityCurrentLabel(currentName, baselineSha),
                latestVersion = notes.entityLatestLabel(currentName, notes.latestPublisherSha),
                title = "",
                releaseSummary = summary,
                releaseUrl = lastReleaseUrl,
            ),
        )
    }

    fun requestCheck(forceRefresh: Boolean = false) {
        if (checkJob?.isActive == true || installJob?.isActive == true) return
        checkJob = scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val e = entity ?: return@withLock
                val current = AppUpdater.getVersionName(context)
                e.publish(
                    e.current().copy(
                        inProgress = true,
                        hasProgress = false,
                        currentVersion = current,
                        releaseSummary = "Checking version.json + GitHub releases…",
                    ),
                )

                val presentation = runCatching {
                    AppUpdater.resolveManualUpdatePresentation(context)
                }.getOrNull()
                val softwareStable = presentation?.info?.takeUnless { presentation.checkFailed }
                lastSoftwareStable = softwareStable

                val notes = runCatching {
                    GitHubReleaseNotes.fetchForEntity(
                        context = context,
                        softwareStableVersion = softwareStable?.versionName ?: current,
                        installedVersionName = current,
                        installedVersionCode = AppUpdater.getVersionCode(context),
                        softwareStableSha256 = softwareStable?.sha256,
                        forceRefresh = forceRefresh,
                    )
                }.onFailure {
                    Log.w(TAG, "GitHub notes fetch failed: ${it.message}")
                }.getOrNull()
                lastNotes = notes

                if (notes == null && softwareStable == null) {
                    e.publish(
                        UpdateEntityState(
                            inProgress = false,
                            hasProgress = false,
                            currentVersion = current,
                            latestVersion = current,
                            title = "",
                            releaseSummary = "Update check failed (GitHub / version.json / network)",
                            releaseUrl = RELEASES_PAGE,
                        ),
                    )
                    return@withLock
                }

                val asset = notes?.entityTarget?.apkAsset()
                Log.i(
                    TAG,
                    "Entity target=${notes?.entityTarget?.tagName} " +
                        "asset=${asset?.name} sha=${asset?.sha256Hex?.take(16)} " +
                        "hasUpdate=${notes?.entityHasUpdate} " +
                        "baselineSha=${UpdateArtifactStore.getPublisherBaselineSha256(context)?.take(16)} " +
                        "jsonSha=${softwareStable?.sha256?.take(16)} " +
                        "latestPublisher=${notes?.latestPublisherSha?.take(16)}",
                )
                publishBaseline(notes)
            }
        }
    }

    private fun requestInstall() {
        if (installJob?.isActive == true) {
            Log.w(TAG, "Install already in progress")
            return
        }
        // Satellite scope is Main. Download, SHA-256, and the installer latch
        // have to leave that thread or the UI ANRs for the whole update.
        installJob = scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val e = entity ?: return@withLock
                var notesSnapshot = lastNotes
                if (notesSnapshot?.entityTarget == null) {
                    val current = AppUpdater.getVersionName(context)
                    val software = lastSoftwareStable?.versionName ?: current
                    notesSnapshot = runCatching {
                        GitHubReleaseNotes.fetchForEntity(
                            context = context,
                            softwareStableVersion = software,
                            installedVersionName = current,
                            installedVersionCode = AppUpdater.getVersionCode(context),
                            softwareStableSha256 = lastSoftwareStable?.sha256,
                            forceRefresh = true,
                        )
                    }.getOrNull()
                    lastNotes = notesSnapshot
                }
                val release = notesSnapshot?.entityTarget
                if (release == null) {
                    e.publish(
                        e.current().copy(
                            inProgress = false,
                            releaseSummary = "No GitHub APK target for entity install",
                        ),
                    )
                    return@withLock
                }
                if (notesSnapshot?.entityHasUpdate == false) {
                    e.publish(
                        e.current().copy(
                            inProgress = false,
                            latestVersion = AppUpdater.getVersionName(context),
                            releaseSummary = notesSnapshot.formatSummary(installTipFooter()),
                        ),
                    )
                    return@withLock
                }

                val notes = notesSnapshot
                val notesBody = notes?.formatSummary(installTipFooter()).orEmpty()
                val currentVersion = AppUpdater.getVersionName(context)
                val latestVersion = notes?.entityLatestLabel(
                    currentVersion,
                    notes.latestPublisherSha,
                ).orEmpty()
                val ok = AppUpdater.performEntityUpdate(context, release) { progress ->
                    val pct = progress.percent
                    val inProgress = progress.phase != RemoteUpdatePhase.DONE &&
                        progress.phase != RemoteUpdatePhase.FAILED &&
                        progress.phase != RemoteUpdatePhase.IDLE
                    // Entity state is collected on Main. The full GitHub body on every
                    // percent rebuilds a large frame there for the whole download.
                    val summary = if (inProgress) {
                        progress.message.ifBlank { "Updating" }
                    } else {
                        notesBody.ifBlank { progress.info?.changelog.orEmpty() }
                    }
                    e.publish(
                        UpdateEntityState(
                            inProgress = inProgress,
                            hasProgress = pct != null,
                            progress = (pct ?: 0).toFloat(),
                            currentVersion = currentVersion,
                            latestVersion = latestVersion.ifBlank { progress.info?.versionName.orEmpty() },
                            title = "",
                            releaseSummary = summary,
                            releaseUrl = lastReleaseUrl,
                        ),
                    )
                }
                if (!ok) {
                    publishBaseline(lastNotes)
                }
            }
        }
    }

    companion object {
        private const val TAG = "VoiceSatelliteUpdate"
        private const val RELEASES_PAGE = "https://github.com/knoop7/Ava/releases"
    }
}
