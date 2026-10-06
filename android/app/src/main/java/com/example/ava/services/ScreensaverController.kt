package com.example.ava.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.ava.mods.ModCameraStreamBridge
import com.example.ava.mods.ModDeviceSupport
import com.example.ava.sensor.EnvironmentSensorManager
import com.example.ava.settings.ScreensaverSettings
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.voice.AvaVoiceHaController
import com.example.ava.voice.AvaVoiceSessionHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

object ScreensaverController {
    private const val TAG = "ScreensaverController"
    private const val DARK_OFF_THRESHOLD_LUX = 1.5f
    private const val LIGHT_RESTORE_THRESHOLD_LUX = 4.0f
    private const val DARK_TRANSITION_DEBOUNCE_MS = 1500L
    private const val LIGHT_TRANSITION_DEBOUNCE_MS = 1500L
    private const val IDLE_CHECK_MIN_INTERVAL_MS = 500L
    private const val IDLE_CHECK_MAX_INTERVAL_MS = 5000L
    private const val PERSON_WAKE_DEBOUNCE_MS = 1500L
    /** Let a same-moment touch or proximity wake stamp interaction before we cover. */
    private const val SCREEN_ON_SHOW_DELAY_MS = 400L
    /**
     * Extra settle after [ProcessLifecycleOwner] ON_STOP before arming Background Pause.
     * ProcessLifecycleOwner already ignores config-change blips; this covers rapid
     * Home → immediately back into Ava so we do not hide screensaver / reset idle.
     */
    private const val BACKGROUND_PAUSE_CONFIRM_MS = 900L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var settingsStore: ScreensaverSettingsStore? = null
    private var currentSettings: ScreensaverSettings = ScreensaverSettings()

    @Volatile private var lastInteractionAt = System.currentTimeMillis()
    private var idleJob: Job? = null
    private var sensorManager: EnvironmentSensorManager? = null
    private var motionJob: Job? = null
    private var lightSensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var lightSensorRegistered = false
    private val lightSensorHandler = Handler(Looper.getMainLooper())

    @Volatile private var isScreensaverVisible = false
    private val screensaverLock = Any()

    /**
     * True while MainActivity is on a settings-like Compose route (not home).
     * Idle screensaver must not cover active configuration UI.
     */
    @Volatile private var settingsUiForeground = false

    /** Debounce for camera person-wake so a sticky face does not spam dismiss. */
    @Volatile private var lastPersonWakeAt = 0L

    fun isScreensaverVisible(): Boolean = isScreensaverVisible

    /** Called from [com.example.ava.ui.MainNavHost] when the nav route changes. */
    fun setSettingsUiForeground(active: Boolean) {
        settingsUiForeground = active
        if (active) {
            lastInteractionAt = System.currentTimeMillis()
        }
    }

    /**
     * Run frame-diff occupancy while person-wake is on and recording delivers frames.
     * Must run even when screensaver is hidden — occupancy suppresses idle show.
     */
    fun shouldProbeRecordingMotion(): Boolean {
        if (!isPersonWakeEffective()) return false
        if (AvaVoiceSessionHub.hasLiveCallSessions()) return false
        if (AvaVoiceHaController.hasActiveSession()) return false
        return true
    }

    /**
     * Rising edge of scene occupancy while screensaver is visible → dismiss.
     */
    fun onRecordingMotionDetected() {
        if (!isPersonWakeEffective()) return
        if (!isScreensaverVisible) return
        if (AvaVoiceSessionHub.hasLiveCallSessions()) return
        if (AvaVoiceHaController.hasActiveSession()) return
        val now = System.currentTimeMillis()
        if (now - lastPersonWakeAt < PERSON_WAKE_DEBOUNCE_MS) return
        lastPersonWakeAt = now
        Log.d(TAG, "Camera motion while screensaver visible — dismissing")
        onUserInteraction()
    }

    /**
     * Occupancy cleared (person left empty scene). Start idle countdown from now —
     * do not poke [lastInteractionAt] every idle tick while occupied (that wrecks the timeline).
     */
    fun onSceneOccupancyCleared() {
        if (!isPersonWakeEffective()) return
        lastInteractionAt = System.currentTimeMillis()
        Log.d(TAG, "Scene occupancy cleared — idle timer restarted")
    }

    /**
     * Wall-panel rule: do not steal the layer while something intentional is on screen.
     * Mini music FAB alone does not suppress (see [VinylCoverService.isFullPlayerBlockingScreensaver]).
     * Persistent voice-message board overlay is also excluded — only call / inbound popups count.
     * Camera occupancy is handled separately in [checkIdleAndShow] (no per-tick timer reset).
     *
     * Intentionally ignores browser warm-up / WebView health-steward state — those must
     * not gate or reset the idle screensaver (cold start was stuck never firing).
     */
    fun shouldSuppressIdleScreensaver(): Boolean {
        if (VinylCoverService.isFullPlayerBlockingScreensaver()) return true
        if (settingsUiForeground) return true
        if (VoiceSatelliteService.getInstance()?.isVoiceAssistantPipelineActive() == true) return true
        if (NotificationOverlayService.isOverlayVisible()) return true
        if (AvaVoiceSessionHub.hasLiveCallSessions()) return true
        if (AvaVoiceHaController.hasActiveSession()) return true
        if (VoiceMessagePlaybackOverlayService.isActivelyShowing()) return true
        return false
    }

    /**
     * Soft-pause screensaver **display** (hide only — never mirrors HA `screensaver_display`
     * OFF). Same path as Settings → 后台暂停 / Background Pause:
     * - switch on + confirmed Ava process background (not cold host-only, not a blip), or
     * - full now-playing surface is up (must not cover the song container).
     */
    private fun shouldSoftPauseScreensaverDisplay(): Boolean {
        if (currentSettings.backgroundPauseEnabled && backgroundPauseConfirmed) {
            // Self-heal race: confirm job can set the latch after ON_START already cleared it,
            // which would soft-pause forever while Ava UI is foreground (screensaver never shows).
            if (isAppInForeground) {
                backgroundPauseConfirmed = false
                Log.w(TAG, "Background Pause latch cleared — process is foreground")
            } else {
                return true
            }
        }
        if (VinylCoverService.isFullPlayerBlockingScreensaver()) return true
        return false
    }

    /**
     * Immediate soft-hide when a pause condition becomes true (expand full player,
     * Background Pause edge). Idle loop also polls this — no HA two-way OFF.
     */
    fun refreshSoftPauseDisplay() {
        if (appContext == null) return
        if (!shouldSoftPauseScreensaverDisplay()) return
        if (!isScreensaverVisible) return
        stopScreensaver()
    }

    /**
     * Full now-playing surface released (collapsed to mini FAB, or overlay tucked).
     * Soft-pause alone only *blocks* show — without this edge callback the idle
     * countdown never restarts and screensaver stays dead until some other poke.
     */
    fun onFullPlayerReleased() {
        if (appContext == null) return
        // Still soft-paused for Background Pause — do not arm idle under another app.
        if (shouldSoftPauseScreensaverDisplay()) return
        resetIdleTimer()
        Log.d(TAG, "Full player released — idle timer restarted")
    }

    private fun isCameraOccupancyBlockingIdle(): Boolean {
        return isPersonWakeEffective() && ScreensaverFrameMotionProbe.isSceneOccupied()
    }

    /** Person-wake needs core video frames; Camera Stream mod owns the camera instead. */
    private fun isPersonWakeEffective(): Boolean {
        if (!currentSettings.personWakeEnabled) return false
        val ctx = appContext ?: return currentSettings.personWakeEnabled
        return !ModCameraStreamBridge.isActive(ctx)
    }

    /**
     * Process-level Ava UI in STARTED+ ([ProcessLifecycleOwner]).
     * Default false: host-only cold boot must not look like "other app foreground".
     */
    @Volatile private var isAppInForeground = false
    /** True after Ava UI has been process-foreground at least once this process. */
    @Volatile private var hasSeenAvaUiForeground = false
    /**
     * True only after sustained process background (debounced). Cleared on return.
     * Unlike a sticky "eligible" latch, sudden leave→enter cancels before this arms.
     */
    @Volatile private var backgroundPauseConfirmed = false
    private var backgroundPauseConfirmJob: Job? = null
    /** Guards confirm-job set vs foreground clear (see [onProcessEnteredBackground]). */
    private val backgroundPauseLock = Any()
    private var processLifecycleRegistered = false
    private var isScreenOffByDark = false
    /** Original path: pause WebView before core screen-off (all devices without mod dark hook). */
    private var screensaverPausedForDark = false
    /** Mod path only: detach overlay so KEEP_SCREEN_ON does not block mod sleep (Echo Show). */
    private var screensaverHiddenForModDark = false
    private var wasEnabled = false
    private var wasVisible = true
    /** First DataStore emission is a baseline, not an HA rising-edge force-show. */
    private var settingsHydrated = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenOnShowJob: Job? = null
    private var lastObservedLux: Float? = null
    private var darkCandidateSinceMs: Long? = null
    private var lightCandidateSinceMs: Long? = null
    private var darkTransitionJob: Job? = null
    private var lightTransitionJob: Job? = null
    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                handleLightLevel(event.values.firstOrNull() ?: return)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start(context: Context) {
        val firstStart = appContext == null
        appContext = context.applicationContext
        if (firstStart) {
            settingsStore = ScreensaverSettingsStore(context.screensaverSettingsStore)
            registerProcessLifecycleObserver()
            scope.launch {
                settingsStore?.getFlow()?.distinctUntilChanged()?.collectLatest { settings ->
                    applyScreensaverSettings(settings)
                }
            }
            scope.launch {
                // First value is the current panel, not a rising edge. A cold start
                // with the screen already on must not cover the dashboard.
                var tracking = false
                var panelOn = true
                ScreenControlUtils.panelOnState.collect { on ->
                    if (!tracking) {
                        tracking = true
                        panelOn = on
                        return@collect
                    }
                    val rose = on && !panelOn
                    panelOn = on
                    if (!on) {
                        screenOnShowJob?.cancel()
                        screenOnShowJob = null
                        return@collect
                    }
                    if (rose) scheduleShowAfterScreenOn()
                }
            }
        }
        // Service (re)start: always refresh idle clock so a long dual-pane cold create
        // is not racing a stale lastInteractionAt from an earlier process touch.
        onHostServiceStarted()
    }

    /**
     * Voice satellite came up. Reset idle so cold-start browser reconcile is not
     * immediately covered by a leftover countdown.
     * Always arms the idle loop when screensaver is enabled — Background Pause must not
     * prevent the job from existing after the host service is up.
     */
    fun onHostServiceStarted() {
        lastInteractionAt = System.currentTimeMillis()
        // Re-sync process UI state (MainActivity may already be up before host onCreate).
        syncProcessForegroundFromLifecycle()
        cancelBackgroundPauseConfirm("host-start")
        // If host restarted while Ava UI is already away, re-arm the settle timer
        // (cancel alone would leave Background Pause stuck off until the next leave).
        if (!isAppInForeground && hasSeenAvaUiForeground && currentSettings.backgroundPauseEnabled) {
            onProcessEnteredBackground()
        } else if (!hasSeenAvaUiForeground) {
            Log.d(
                TAG,
                "Host cold start — Background Pause inactive until Ava UI was foreground " +
                    "(processForeground=$isAppInForeground)",
            )
        }
        if (currentSettings.enabled) {
            ensureIdleJob()
        }
        Log.d(TAG, "Host service started — idle timer reset / ensureIdleJob")
    }

    private fun registerProcessLifecycleObserver() {
        if (processLifecycleRegistered) return
        processLifecycleRegistered = true
        val runOnMain = Runnable {
            syncProcessForegroundFromLifecycle()
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        onProcessEnteredForeground()
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        onProcessEnteredBackground()
                    }
                },
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runOnMain.run()
        } else {
            mainHandler.post(runOnMain)
        }
    }

    private fun syncProcessForegroundFromLifecycle() {
        val started = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        synchronized(backgroundPauseLock) {
            isAppInForeground = started
            if (started) {
                hasSeenAvaUiForeground = true
                backgroundPauseConfirmed = false
            }
        }
    }

    private fun onProcessEnteredForeground() {
        cancelBackgroundPauseConfirm("foreground")
        val wasBackgroundConfirmed: Boolean
        synchronized(backgroundPauseLock) {
            wasBackgroundConfirmed = backgroundPauseConfirmed
            isAppInForeground = true
            hasSeenAvaUiForeground = true
            backgroundPauseConfirmed = false
        }
        // Returning from a confirmed other-app stay: fresh idle, not instant cover.
        if (wasBackgroundConfirmed && currentSettings.backgroundPauseEnabled) {
            lastInteractionAt = System.currentTimeMillis()
            Log.d(TAG, "Ava UI foreground after Background Pause — idle timer reset")
        }
    }

    private fun onProcessEnteredBackground() {
        synchronized(backgroundPauseLock) {
            isAppInForeground = false
        }
        // Never treat "never showed Ava UI" (host-only boot) as other-app background.
        if (!hasSeenAvaUiForeground) {
            Log.d(TAG, "Process background ignored — Ava UI never foreground this process")
            return
        }
        if (!currentSettings.backgroundPauseEnabled) return
        // Debounce rapid leave→enter; ProcessLifecycleOwner already skipped config changes.
        backgroundPauseConfirmJob?.cancel()
        backgroundPauseConfirmJob = scope.launch {
            delay(BACKGROUND_PAUSE_CONFIRM_MS)
            val armed = synchronized(backgroundPauseLock) {
                if (isAppInForeground || !hasSeenAvaUiForeground) return@synchronized false
                if (!currentSettings.backgroundPauseEnabled) return@synchronized false
                backgroundPauseConfirmed = true
                true
            }
            if (!armed) return@launch
            stopScreensaver()
        }
    }

    private fun cancelBackgroundPauseConfirm(reason: String) {
        if (backgroundPauseConfirmJob != null) {
            backgroundPauseConfirmJob?.cancel()
            backgroundPauseConfirmJob = null
            Log.d(TAG, "Background Pause confirm cancelled ($reason)")
        }
    }

    /**
     * Voice satellite stopped (Service destroy or user soft-stop). Tear down idle WebView
     * screensaver so it cannot float without a live satellite.
     */
    fun onHostServiceStopped() {
        screenOnShowJob?.cancel()
        screenOnShowJob = null
        destroyScreensaver()
        stopIdleJob()
        stopSensors()
        releaseWakeLock()
        Log.d(TAG, "Host service stopped — screensaver torn down")
    }

    /**
     * Panel just turned on. Wait briefly so a touch or proximity wake can stamp
     * [lastInteractionAt] first — those paths want the page underneath, not a cover.
     * HA display-on does not stamp interaction, so the magazine or custom page shows.
     * Timeout 0 is intentionally not consulted.
     */
    private fun scheduleShowAfterScreenOn() {
        val panelOnAt = System.currentTimeMillis()
        screenOnShowJob?.cancel()
        screenOnShowJob = scope.launch {
            delay(SCREEN_ON_SHOW_DELAY_MS)
            showScreensaverAfterScreenOn(panelOnAt)
        }
    }

    private fun showScreensaverAfterScreenOn(panelOnAt: Long) {
        val context = appContext ?: return
        if (!shouldShowScreensaverAfterScreenOn(
                settings = currentSettings,
                panelStillOn = ScreenControlUtils.panelOnState.value,
                satelliteStarted = VoiceSatelliteService.isSatelliteStarted(),
                alreadyVisible = isScreensaverVisible,
                softPaused = shouldSoftPauseScreensaverDisplay(),
                suppressed = shouldSuppressIdleScreensaver(),
                lastInteractionAt = lastInteractionAt,
                panelOnAt = panelOnAt,
            )
        ) {
            return
        }
        val effectiveUrl = getEffectiveScreensaverUrl(currentSettings)
        synchronized(screensaverLock) {
            if (isScreensaverVisible) return
            if (!shouldShowScreensaverAfterScreenOn(
                    settings = currentSettings,
                    panelStillOn = ScreenControlUtils.panelOnState.value,
                    satelliteStarted = VoiceSatelliteService.isSatelliteStarted(),
                    alreadyVisible = false,
                    softPaused = shouldSoftPauseScreensaverDisplay(),
                    suppressed = shouldSuppressIdleScreensaver(),
                    lastInteractionAt = lastInteractionAt,
                    panelOnAt = panelOnAt,
                )
            ) {
                return
            }
            ScreensaverWebViewService.show(context, effectiveUrl)
            isScreensaverVisible = true
            onScreensaverBecameVisible()
            syncHaVisibleOnAfterShow()
            Log.d(TAG, "Screensaver shown after screen on")
        }
    }

    private fun applyScreensaverSettings(settings: ScreensaverSettings) {
        val oldSettings = currentSettings
        currentSettings = settings

        // Cold hydrate: adopt persisted HA switch state without treating it as a user/HA edge.
        if (!settingsHydrated) {
            settingsHydrated = true
            wasVisible = settings.visible
            wasEnabled = settings.enabled
            lastInteractionAt = System.currentTimeMillis()
            if (!settings.enabled) {
                destroyScreensaver()
                stopIdleJob()
                stopSensors()
                return
            }
            if (canDisplayScreensaver(settings)) {
                ensureIdleJob()
            } else {
                stopIdleJob()
            }
            updateSensors()
            Log.d(
                TAG,
                "Screensaver settings hydrated (enabled=${settings.enabled}, " +
                    "haDisplay=${settings.enableHaDisplay}, visible=${settings.visible}) — no force-show"
            )
            return
        }

        // Reset timer when any setting changes
        if (oldSettings.timeoutSeconds != settings.timeoutSeconds ||
            oldSettings.screensaverUrl != settings.screensaverUrl ||
            oldSettings.dawnWallpaperEnabled != settings.dawnWallpaperEnabled
        ) {
            resetIdleTimer()
        }

        // Toggling Background Pause on while Ava is foreground must not keep a stale latch
        // (e.g. confirm raced after the last return) or idle show stays soft-paused forever.
        if (settings.backgroundPauseEnabled != oldSettings.backgroundPauseEnabled) {
            cancelBackgroundPauseConfirm("setting-toggle")
            synchronized(backgroundPauseLock) {
                if (isAppInForeground || !settings.backgroundPauseEnabled) {
                    backgroundPauseConfirmed = false
                }
            }
            if (isAppInForeground && settings.backgroundPauseEnabled) {
                Log.d(TAG, "Background Pause enabled in foreground — latch clear, idle may show")
            }
        }

        if (!settings.enabled) {
            destroyScreensaver()
            stopIdleJob()
            stopSensors()
            wasEnabled = false
            return
        }

        // Control mode: OFF destroys overlay and stops idle.
        // Two-way: OFF only hides; idle timer keeps running so timeout can show again.
        if (settings.enableHaDisplay && !settings.visible && wasVisible) {
            if (settings.haSwitchTwoWayEnabled) {
                stopScreensaver()
                // Fresh idle countdown from this hide (same as touch dismiss).
                lastInteractionAt = System.currentTimeMillis()
                Log.d(TAG, "Two-way: HA switch OFF — hide, idle timer continues")
            } else {
                destroyScreensaver()
                stopIdleJob()
                Log.d(TAG, "Screensaver destroyed and idle job stopped on switch OFF")
            }
        }

        // ON = show immediately (skip if idle already presented it). Never on first hydrate.
        // Do not defer for browser warm-up / health steward — screensaver is independent.
        if (settings.enableHaDisplay && settings.visible && !wasVisible) {
            val effectiveUrl = getEffectiveScreensaverUrl(settings)
            // Soft-stop leaves the Android Service instance alive; require the live satellite.
            val hostUp = VoiceSatelliteService.isSatelliteStarted()
            when {
                !hostUp ->
                    Log.d(TAG, "HA switch ON ignored — voice satellite not started")
                effectiveUrl.isNotBlank() && !isScreensaverVisible -> {
                    // Soft-pause (background-pause / full player) hides display only —
                    // do not latch visible ON while display is paused.
                    if (shouldSoftPauseScreensaverDisplay()) {
                        lastInteractionAt = System.currentTimeMillis()
                        Log.d(TAG, "HA switch ON deferred — screensaver display soft-paused")
                    } else {
                        ScreensaverWebViewService.show(requireContext(), effectiveUrl)
                        isScreensaverVisible = true
                        onScreensaverBecameVisible()
                        Log.d(TAG, "Screensaver created and shown on switch ON")
                    }
                }
            }
        }
        wasVisible = settings.visible
        if (!wasEnabled && settings.enabled) {
            lastInteractionAt = System.currentTimeMillis()
            wasEnabled = true
        }
        if (canDisplayScreensaver(settings)) {
            ensureIdleJob()
        } else {
            stopIdleJob()
        }
        updateSensors()
        val effectiveUrl = getEffectiveScreensaverUrl(settings)
        if (effectiveUrl.isBlank()) {
            stopScreensaver()
        }

        val shouldShow = isHaForcedVisible(settings)
        if (isScreensaverVisible && effectiveUrl.isNotBlank() && shouldShow) {
            ScreensaverWebViewService.updateUrl(requireContext(), effectiveUrl)
        }

        // CPU throttle: only react to the toggle itself here. Initial apply after
        // show waits for onScreensaverContentReady so cold load is not starved.
        if (oldSettings.smartCpuThrottleEnabled != settings.smartCpuThrottleEnabled) {
            syncCpuThrottleForVisibility(visible = isScreensaverVisible)
        } else if (!isScreensaverVisible) {
            syncCpuThrottleForVisibility(visible = false)
        }
    }

    fun onUserInteraction() {
        resetIdleTimer()
        VinylCoverService.pokeUserDismissHideIfArmed()
        if (appContext == null) return
        if (isScreensaverVisible) {
            stopScreensaver()
            syncHaVisibleOffAfterUserDismiss()
        }
    }

    /**
     * Dismiss the screensaver for an incoming or auto-answered call so the call UI
     * is not hidden behind the clock/photo overlay. Also wakes the display if it
     * is off so the caller gets visual feedback on photo-frame-style devices.
     *
     * Routed through [onUserInteraction] so the two-way HA `screensaver_display`
     * switch mirrors the hide, exactly like a touch dismiss.
     */
    fun dismissForIncomingCall() {
        if (isScreensaverVisible) {
            Log.d(TAG, "Screensaver dismissed for incoming call")
        }
        onUserInteraction()
        appContext?.let { ScreenControlUtils.wakeScreen(it) }
    }

    /**
     * Notification scene (and similar alerts). Default [ScreensaverSettings.keepOnOverlays]
     * leaves the screensaver up so the overlay can sit on top. When that switch is off,
     * dismiss like a touch so the alert is not covered.
     */
    fun dismissForTransientOverlay() {
        if (currentSettings.keepOnOverlays) return
        if (isScreensaverVisible) {
            Log.d(TAG, "Screensaver dismissed for overlay")
        }
        onUserInteraction()
        appContext?.let { ScreenControlUtils.wakeScreen(it) }
    }

    /**
     * Lightweight activity signal for high-frequency gestures (move/scroll).
     * It updates idle timestamp without recreating the idle coroutine each frame.
     */
    fun onUserActivity() {
        lastInteractionAt = System.currentTimeMillis()
        VinylCoverService.pokeUserDismissHideIfArmed()
        if (currentSettings.enabled && idleJob == null) {
            ensureIdleJob()
        }
    }
    
    /**
     * Force reset idle timer - call this on any user interaction
     */
    fun resetIdleTimer() {
        lastInteractionAt = System.currentTimeMillis()
        // Cancel and restart idle job to ensure fresh timing
        idleJob?.cancel()
        idleJob = null
        if (currentSettings.enabled) {
            ensureIdleJob()
        }
    }

    private fun stopIdleJob() {
        idleJob?.cancel()
        idleJob = null
    }

    private fun ensureIdleJob() {
        if (idleJob != null) return
        idleJob = scope.launch {
            while (isActive) {
                val satelliteStarted = VoiceSatelliteService.isSatelliteStarted()
                if (satelliteStarted && currentSettings.enabled) {
                    // Background Pause / full player: soft-hide display only (no HA OFF).
                    if (shouldSoftPauseScreensaverDisplay()) {
                        if (isScreensaverVisible) {
                            stopScreensaver()
                        }
                        delay(IDLE_CHECK_MAX_INTERVAL_MS)
                        continue
                    }

                    // Two-way: visible is HA state mirror, not an idle gate — timer always runs.
                    val shouldShow = isIdleAllowedByHaSwitch(currentSettings)
                    
                    if (shouldShow) {
                        checkIdleAndShow()
                    }
                    
                    delay(calculateIdleDelayMs(shouldShow))
                } else {
                    delay(IDLE_CHECK_MAX_INTERVAL_MS)
                }
            }
        }
    }
    
    private fun calculateIdleDelayMs(shouldShow: Boolean): Long {
        if (!shouldShow) return IDLE_CHECK_MAX_INTERVAL_MS
        if (isScreensaverVisible) return IDLE_CHECK_MAX_INTERVAL_MS
        // timeout 0 = idle show disabled — no deadline to chase, avoid 500ms hot polling.
        if (currentSettings.timeoutSeconds <= 0) return IDLE_CHECK_MAX_INTERVAL_MS
        
        val timeoutMs = currentSettings.timeoutSeconds * 1000L
        val elapsed = System.currentTimeMillis() - lastInteractionAt
        val remaining = timeoutMs - elapsed
        
        return when {
            remaining <= 0L -> IDLE_CHECK_MIN_INTERVAL_MS
            remaining <= IDLE_CHECK_MAX_INTERVAL_MS -> remaining.coerceAtLeast(IDLE_CHECK_MIN_INTERVAL_MS)
            else -> IDLE_CHECK_MAX_INTERVAL_MS
        }
    }

    private fun checkIdleAndShow() {
        val context = appContext ?: return
        if (shouldSoftPauseScreensaverDisplay()) return
        if (!canDisplayScreensaver(currentSettings)) return
        val effectiveUrl = getEffectiveScreensaverUrl(currentSettings)
        if (effectiveUrl.isBlank()) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) return
        // Suppress = block show only. Do NOT refresh lastInteractionAt here — that made
        // cold start / overlays perpetually postpone the idle deadline.
        if (shouldSuppressIdleScreensaver()) return
        // Occupancy: block show only — timer is restarted once on vacant edge in the probe.
        if (isCameraOccupancyBlockingIdle()) return
        synchronized(screensaverLock) {
            if (isScreensaverVisible) return
            val elapsed = System.currentTimeMillis() - lastInteractionAt
            if (elapsed < currentSettings.timeoutSeconds * 1000L) return
            
            ScreensaverWebViewService.show(context, effectiveUrl)
            isScreensaverVisible = true
            onScreensaverBecameVisible()
            syncHaVisibleOnAfterShow()
            Log.d(TAG, "Screensaver shown after ${elapsed}ms idle")
        }
    }
    
    private fun getEffectiveScreensaverUrl(settings: ScreensaverSettings): String {
        return if (settings.dawnWallpaperEnabled) {
            "file:///android_asset/dawn_wallpaper.html"
        } else {
            settings.screensaverUrl
        }
    }

    private fun onScreensaverBecameVisible() {
        // Light COVERED only — defer CPU throttle until content paints so the third
        // WebView is not starved during cold load (dual-pane HA still holds process budget).
        WebViewService.pause(requireContext())
        QuickEntityOverlayService.notifyScreensaverCovered()
        OverlayZOrderCoordinator.syncFabForAod()
    }

    /**
     * Screensaver page revealed (or max-wait). Safe to deepen browser dormancy / CPU throttle.
     */
    fun onScreensaverContentReady() {
        if (!isScreensaverVisible) return
        syncCpuThrottleForVisibility(visible = true)
        WebViewService.notifyCoverOverlayReady()
    }

    private fun syncCpuThrottleForVisibility(visible: Boolean) {
        val context = appContext ?: return
        scope.launch(Dispatchers.IO) {
            if (visible && currentSettings.smartCpuThrottleEnabled) {
                ScreensaverCpuThrottle.apply(context)
            } else {
                ScreensaverCpuThrottle.restore(context)
            }
        }
    }

    private fun stopScreensaver() {
        synchronized(screensaverLock) {
            if (!isScreensaverVisible) return
            ScreensaverWebViewService.hide(requireContext())
            isScreensaverVisible = false
            Log.d(TAG, "Screensaver hidden")
        }
        WebViewService.resume(requireContext())
        QuickEntityOverlayService.notifyScreensaverUncovered()
        OverlayZOrderCoordinator.syncFabForAod()
        syncCpuThrottleForVisibility(visible = false)
    }

    /**
     * Opt-in ([ScreensaverSettings.haSwitchTwoWayEnabled]): user dismiss (touch / proximity /
     * person-wake) also turns HA `screensaver_display` off so the switch matches the screen.
     * Idle timer still runs; after [ScreensaverSettings.timeoutSeconds] the screensaver may
     * show again and [syncHaVisibleOnAfterShow] turns the switch back on.
     * Background pause and blank-URL stops do not use this path.
     */
    private fun syncHaVisibleOffAfterUserDismiss() {
        val settings = currentSettings
        if (!settings.enableHaDisplay || !settings.haSwitchTwoWayEnabled) return
        if (!settings.visible) return
        val store = settingsStore ?: return
        scope.launch {
            store.visible.set(false)
            Log.d(TAG, "HA screensaver_display cleared after user dismiss (two-way)")
        }
    }

    /** Two-way: idle (or dark-wake) show → mirror ON onto HA `screensaver_display`. */
    private fun syncHaVisibleOnAfterShow() {
        val settings = currentSettings
        if (!settings.enableHaDisplay || !settings.haSwitchTwoWayEnabled) return
        if (settings.visible) return
        val store = settingsStore ?: return
        scope.launch {
            store.visible.set(true)
            Log.d(TAG, "HA screensaver_display set after show (two-way)")
        }
    }
    
    private fun destroyScreensaver() {
        synchronized(screensaverLock) {
            ScreensaverWebViewService.destroy(requireContext())
            isScreensaverVisible = false
            Log.d(TAG, "Screensaver destroyed")
        }
        WebViewService.resume(requireContext())
        QuickEntityOverlayService.notifyScreensaverUncovered()
        OverlayZOrderCoordinator.syncFabForAod()
        syncCpuThrottleForVisibility(visible = false)
    }

    private fun updateSensors() {
        // Settings collector outlives soft-stop; do not re-arm sensors without a live satellite.
        if (!VoiceSatelliteService.isSatelliteStarted()) {
            stopSensors()
            return
        }
        updateLightSensor()
        updateProximityMotion()
    }

    private fun updateLightSensor() {
        if (!currentSettings.darkOffEnabled) {
            stopLightSensor()
            return
        }
        val context = requireContext()
        if (lightSensorManager == null) {
            lightSensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            lightSensor = lightSensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        }
        if (lightSensor == null) {
            stopLightSensor()
            return
        }
        if (!lightSensorRegistered) {
            val registered = lightSensorManager?.registerListener(
                lightSensorListener,
                lightSensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                lightSensorHandler
            ) == true
            if (registered) {
                lightSensorRegistered = true
                Log.d(TAG, "Direct screensaver light sensor registered: ${lightSensor?.name}")
            } else {
                lightSensorRegistered = false
                Log.w(TAG, "Failed to register direct screensaver light sensor")
            }
        }
    }

    private fun stopLightSensor() {
        darkTransitionJob?.cancel()
        darkTransitionJob = null
        lightTransitionJob?.cancel()
        lightTransitionJob = null
        if (lightSensorRegistered) {
            lightSensorManager?.unregisterListener(lightSensorListener)
            lightSensorRegistered = false
        }
        darkCandidateSinceMs = null
        lightCandidateSinceMs = null
        lastObservedLux = null
        if (motionJob == null) {
            lightSensor = null
            lightSensorManager = null
            sensorManager?.stopListening()
            sensorManager = null
        }
    }

    private fun updateProximityMotion() {
        if (!currentSettings.motionOnEnabled) {
            stopProximityMotion()
            return
        }
        if (sensorManager == null) {
            sensorManager = EnvironmentSensorManager(requireContext())
            sensorManager?.startListening()
        }
        if (sensorManager?.hasProximitySensor != true) {
            stopProximityMotion()
            return
        }
        if (motionJob != null) return
        motionJob = scope.launch {
            sensorManager?.proximity?.collectLatest { distance ->
                if (distance == null) return@collectLatest
                handleMotion(distance)
            }
        }
    }

    private fun stopProximityMotion() {
        motionJob?.cancel()
        motionJob = null
        if (!lightSensorRegistered) {
            sensorManager?.stopListening()
            sensorManager = null
        }
    }

    private fun stopSensors() {
        stopLightSensor()
        stopProximityMotion()
    }

    private fun handleLightLevel(lux: Float) {
        if (!currentSettings.darkOffEnabled) return
        lastObservedLux = lux
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        val isInteractive = powerManager.isInteractive

        val now = System.currentTimeMillis()

        if (!isScreenOffByDark) {
            lightCandidateSinceMs = null
            lightTransitionJob?.cancel()
            lightTransitionJob = null
            if (lux <= DARK_OFF_THRESHOLD_LUX && isInteractive) {
                if (darkCandidateSinceMs == null) {
                    darkCandidateSinceMs = now
                    darkTransitionJob?.cancel()
                    darkTransitionJob = scope.launch {
                        delay(DARK_TRANSITION_DEBOUNCE_MS)
                        val latestLux = lastObservedLux ?: return@launch
                        val powerManagerNow = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
                        if (!currentSettings.darkOffEnabled || isScreenOffByDark || !powerManagerNow.isInteractive) return@launch
                        if (latestLux > DARK_OFF_THRESHOLD_LUX) return@launch
                        Log.d(TAG, "Dark detected (lux=$latestLux), turning off screen")
                        screensaverPausedForDark = false
                        screensaverHiddenForModDark = false
                        if (isScreensaverVisible) {
                            if (ModDeviceSupport.hasSleepScreenForDarkHook(requireContext())) {
                                ScreensaverWebViewService.hide(requireContext())
                                screensaverHiddenForModDark = true
                            } else {
                                ScreensaverWebViewService.pause(requireContext())
                                screensaverPausedForDark = true
                            }
                        }
                        acquireWakeLock()
                        val modSlept = ModDeviceSupport.trySleepScreenForDark(requireContext())
                        if (!modSlept) {
                            ScreenControlUtils.setScreenOn(requireContext(), false)
                        } else {
                            Log.d(TAG, "Dark off handled by device support mod")
                        }
                        isScreenOffByDark = true
                        darkCandidateSinceMs = null
                    }
                }
                return
            } else {
                darkTransitionJob?.cancel()
                darkTransitionJob = null
                darkCandidateSinceMs = null
                return
            }
        }

        darkCandidateSinceMs = null
        darkTransitionJob?.cancel()
        darkTransitionJob = null
        if (lux >= LIGHT_RESTORE_THRESHOLD_LUX) {
            if (lightCandidateSinceMs == null) {
                lightCandidateSinceMs = now
                lightTransitionJob?.cancel()
                lightTransitionJob = scope.launch {
                    delay(LIGHT_TRANSITION_DEBOUNCE_MS)
                    val latestLux = lastObservedLux ?: return@launch
                    if (!currentSettings.darkOffEnabled || !isScreenOffByDark) return@launch
                    if (latestLux < LIGHT_RESTORE_THRESHOLD_LUX) return@launch

                    Log.d(TAG, "Light restored (lux=$latestLux), turning on screen")
                    val modWoke = ModDeviceSupport.tryWakeScreenFromDark(requireContext())
                    if (!modWoke) {
                        ScreenControlUtils.setScreenOn(requireContext(), true)
                    } else {
                        Log.d(TAG, "Screen wake handled by device support mod")
                    }

                    when {
                        screensaverHiddenForModDark -> {
                            if (shouldSoftPauseScreensaverDisplay()) {
                                screensaverHiddenForModDark = false
                                Log.d(TAG, "Dark wake: skip screensaver — display soft-paused")
                            } else {
                                ScreensaverWebViewService.bringToFront(requireContext())
                                screensaverHiddenForModDark = false
                            }
                        }
                        screensaverPausedForDark || isScreensaverVisible -> {
                            if (shouldSoftPauseScreensaverDisplay()) {
                                screensaverPausedForDark = false
                                if (isScreensaverVisible) {
                                    stopScreensaver()
                                    Log.d(TAG, "Dark wake: soft-pause screensaver display")
                                }
                            } else {
                                ScreensaverWebViewService.resume(requireContext())
                                screensaverPausedForDark = false
                            }
                        }
                    }
                    releaseWakeLock()
                    isScreenOffByDark = false
                    lightCandidateSinceMs = null

                    if (!canDisplayScreensaver(currentSettings)) {
                        return@launch
                    }

                    val effectiveUrl = getEffectiveScreensaverUrl(currentSettings)
                    if (effectiveUrl.isNotBlank() &&
                        !shouldSuppressIdleScreensaver() &&
                        !isCameraOccupancyBlockingIdle()
                    ) {
                        synchronized(screensaverLock) {
                            if (!isScreensaverVisible) {
                                val elapsed = System.currentTimeMillis() - lastInteractionAt
                                if (elapsed >= currentSettings.timeoutSeconds * 1000L) {
                                    ScreensaverWebViewService.show(requireContext(), effectiveUrl)
                                    isScreensaverVisible = true
                                    onScreensaverBecameVisible()
                                    syncHaVisibleOnAfterShow()
                                    Log.d(TAG, "Screensaver restored after dark wake")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            lightTransitionJob?.cancel()
            lightTransitionJob = null
            lightCandidateSinceMs = null
        }
    }

    private fun handleMotion(distance: Float) {
        if (!currentSettings.motionOnEnabled) return
        if (shouldSoftPauseScreensaverDisplay()) return
        val sensorMax = sensorManager?.proximityMaxRange ?: return
        val isNear = distance < sensorMax
        if (isNear) {
            ScreenControlUtils.setScreenOn(requireContext(), true)
            onUserInteraction()
        }
    }

    private val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L
    
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Ava:DarkSensorWakeLock"
        ).apply {
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        Log.d(TAG, "WakeLock acquired for dark sensor with ${WAKELOCK_TIMEOUT_MS}ms timeout")
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun canDisplayScreensaver(settings: ScreensaverSettings): Boolean {
        if (!settings.enabled) return false
        // timeout 0 = never auto-show on idle (issue #174) — overrides enabled. Guards
        // idle job, checkIdleAndShow, and dark-wake restore, where a raw
        // "elapsed >= 0*1000" would otherwise show *immediately*. The HA
        // `screensaver_display` switch ON path and [showScreensaverAfterScreenOn] do not
        // go through here, so those force-shows keep working.
        if (settings.timeoutSeconds <= 0) return false
        // Soft-stop keeps VoiceSatelliteService alive; only a live satellite may show idle cover.
        if (!VoiceSatelliteService.isSatelliteStarted()) return false
        // Control mode only: visible=false means "do not allow idle". Two-way keeps idle alive.
        if (!isIdleAllowedByHaSwitch(settings)) return false
        if (getEffectiveScreensaverUrl(settings).isBlank()) return false
        return true
    }

    /**
     * Whether idle may present the screensaver.
     * Control ([enableHaDisplay] without two-way): gated by [ScreensaverSettings.visible].
     * Two-way: always allowed while enabled — timer owns re-entry; [visible] mirrors display.
     */
    private fun isIdleAllowedByHaSwitch(settings: ScreensaverSettings): Boolean {
        if (!settings.enableHaDisplay) return true
        if (settings.haSwitchTwoWayEnabled) return true
        return settings.visible
    }

    /** For URL refresh while showing: control mode needs visible; two-way follows runtime. */
    private fun isHaForcedVisible(settings: ScreensaverSettings): Boolean {
        if (!settings.enableHaDisplay) return true
        if (settings.haSwitchTwoWayEnabled) return isScreensaverVisible || settings.visible
        return settings.visible
    }

    private fun requireContext(): Context = checkNotNull(appContext) { "ScreensaverController not started" }
}

/**
 * Panel rose from off to on. Timeout 0 does not block this. A touch or proximity
 * wake stamps [lastInteractionAt] inside [userWakeWindowMs] of [panelOnAt] and must
 * leave the page underneath alone. Control-mode HA off still wins. Two-way may show
 * and then mirror the switch on.
 */
internal fun shouldShowScreensaverAfterScreenOn(
    settings: ScreensaverSettings,
    panelStillOn: Boolean,
    satelliteStarted: Boolean,
    alreadyVisible: Boolean,
    softPaused: Boolean,
    suppressed: Boolean,
    lastInteractionAt: Long,
    panelOnAt: Long,
    userWakeWindowMs: Long = SCREEN_ON_USER_WAKE_WINDOW_MS,
): Boolean {
    if (!panelStillOn) return false
    if (!settings.enabled || !settings.showAfterScreenOn) return false
    if (!satelliteStarted || alreadyVisible || softPaused || suppressed) return false
    if (lastInteractionAt >= panelOnAt - userWakeWindowMs) return false
    if (settings.enableHaDisplay && !settings.haSwitchTwoWayEnabled && !settings.visible) return false
    val url = if (settings.dawnWallpaperEnabled) {
        "file:///android_asset/dawn_wallpaper.html"
    } else {
        settings.screensaverUrl
    }
    return url.isNotBlank()
}

internal const val SCREEN_ON_USER_WAKE_WINDOW_MS = 1_500L
