package com.example.ava.mods

import android.content.Context
import android.util.Log
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.screensaverSettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Host bridge for the optional camera-stream mod.
 *
 * When the mod is installed and enabled it owns the device camera for LAN
 * forwarding (MJPEG / RTSP). Ava's built-in remote camera must stay off so the
 * two pipelines do not fight over Camera2. Screensaver person-wake (which needs
 * core video frames) is also turned off and hidden.
 *
 * **No mod / disabled → [isActive] is false;** policy helpers return immediately.
 */
object ModCameraStreamBridge {
    private const val TAG = "ModCameraStream"
    /** Fallback id when older packages omit the `camera_stream` manifest flag. */
    const val MOD_ID = "camera-stream-mod"

    @Volatile
    private var cachedGeneration = -1

    @Volatile
    private var cachedActive = false

    @Volatile
    private var cachedOwnerName: String? = null

    fun invalidateCache() {
        cachedGeneration = -1
        cachedActive = false
        cachedOwnerName = null
    }

    /** True while an enabled camera-stream mod package is present on disk. */
    fun isActive(context: Context): Boolean {
        val generation = ModManager.getInstance(context).registryGeneration
        if (generation == cachedGeneration) {
            return cachedActive
        }
        return refreshActivitySnapshot(context.applicationContext, generation)
    }

    /**
     * Display name of the mod currently owning the camera, or null when none does.
     * Any mod may declare `camera_stream`, so UI hints must name the actual owner
     * instead of assuming the historical camera-stream mod.
     */
    fun activeOwnerName(context: Context): String? {
        val generation = ModManager.getInstance(context).registryGeneration
        if (generation != cachedGeneration) {
            refreshActivitySnapshot(context.applicationContext, generation)
        }
        return cachedOwnerName
    }

    /**
     * Force-disable Ava core remote camera (and screensaver person-wake) when the
     * mod owns the camera. Safe for existing installs that still have those on.
     *
     * @param restartService when true (default), restart the voice satellite so a running
     * HA camera pipeline tears down. Pass false from inside [VoiceSatellite.start] to avoid
     * a recursive restart.
     */
    fun applyHostPolicy(context: Context, restartService: Boolean = true) {
        if (!isActive(context)) return
        val appContext = context.applicationContext
        val cameraDisabled = runBlocking { disableCoreCamera(appContext) }
        val personWakeDisabled = runBlocking { disablePersonWake(appContext) }
        reportHostPolicy(cameraDisabled, personWakeDisabled, restartService)
    }

    /**
     * Suspending form of [applyHostPolicy]. Callers already in a coroutine must use this:
     * the blocking form parks its thread on two DataStore round-trips, which stalls the
     * frame when invoked from a `LaunchedEffect` on the main dispatcher.
     */
    suspend fun applyHostPolicySuspend(context: Context, restartService: Boolean = true) {
        if (!isActive(context)) return
        val appContext = context.applicationContext
        val cameraDisabled = disableCoreCamera(appContext)
        val personWakeDisabled = disablePersonWake(appContext)
        reportHostPolicy(cameraDisabled, personWakeDisabled, restartService)
    }

    /** @return true if core camera was on and has now been turned off. */
    private suspend fun disableCoreCamera(appContext: Context): Boolean = runCatching {
        withContext(Dispatchers.IO) {
            val store = ExperimentalSettingsStore(appContext)
            if (!store.get().cameraEnabled) return@withContext false
            store.setCameraEnabled(false)
            true
        }
    }.onFailure {
        Log.w(TAG, "applyHostPolicy camera failed", it)
    }.getOrDefault(false)

    /** @return true if person-wake was on and has now been turned off. */
    private suspend fun disablePersonWake(appContext: Context): Boolean = runCatching {
        withContext(Dispatchers.IO) {
            val store = ScreensaverSettingsStore(appContext.screensaverSettingsStore)
            if (!store.get().personWakeEnabled) return@withContext false
            store.personWakeEnabled.set(false)
            true
        }
    }.onFailure {
        Log.w(TAG, "applyHostPolicy personWake failed", it)
    }.getOrDefault(false)

    private fun reportHostPolicy(
        cameraDisabled: Boolean,
        personWakeDisabled: Boolean,
        restartService: Boolean,
    ) {
        if (cameraDisabled) {
            Log.i(TAG, "disabled Ava core camera (camera-stream mod owns camera)")
            if (restartService) {
                VoiceSatelliteService.getInstance()
                    ?.restartVoiceSatellite(com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE)
            }
        }
        if (personWakeDisabled) {
            Log.i(TAG, "disabled screensaver person-wake (needs core video frames)")
        }
    }

    /** Boot / registry reload: apply ownership policy if the mod is active. */
    fun syncHostPolicies(context: Context) {
        if (isActive(context)) {
            applyHostPolicy(context)
        }
    }

    private fun refreshActivitySnapshot(context: Context, generation: Int): Boolean {
        val modManager = ModManager.getInstance(context)
        val manifest = resolveEnabledManifest(modManager)

        cachedGeneration = generation
        cachedActive = manifest != null
        cachedOwnerName = manifest?.name?.takeIf { it.isNotBlank() } ?: manifest?.id
        return cachedActive
    }

    private fun resolveEnabledManifest(modManager: ModManager): ModManifest? {
        val manifest = modManager.getEnabledManifests().firstOrNull { candidate ->
            ownsCameraStream(candidate) && !candidate.manager.isNullOrBlank()
        } ?: return null
        if (!modManager.isEnabled(manifest.id)) return null
        if (!isModPackagePresent(modManager, manifest)) return null
        return manifest
    }

    private fun ownsCameraStream(manifest: ModManifest): Boolean =
        manifest.cameraStream || manifest.id == MOD_ID

    private fun isModPackagePresent(modManager: ModManager, manifest: ModManifest): Boolean {
        val modDir = modManager.getModDir(manifest.id) ?: return false
        val libs = manifest.libs.orEmpty()
        if (libs.isEmpty()) return false
        return libs.all { lib ->
            lib.endsWith(".jar") && File(modDir, lib).isFile
        }
    }
}
