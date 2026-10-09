package com.example.ava.ui.screens.home

import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.ava.MainActivity
import com.example.ava.R
import com.example.ava.services.AccessibilityBridge
import com.example.ava.services.OverlayLayerSplit
import com.example.ava.services.WebViewService
import com.example.ava.services.QuickEntityOverlayService
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.permissions.OverlayPermission
import com.example.ava.touchpad.TouchPadOverlay
import com.example.ava.ui.AvaToast
import com.example.ava.ui.MainNavigationCoordinator
import com.example.ava.ui.Screen
import com.example.ava.ui.screens.settings.requestAccessibilityPermission
import com.example.ava.ui.screens.settings.requestOverlayPermission
import com.example.ava.settings.BrowserSettings
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.DarkModeManager
import com.example.ava.settings.CameraMode
import com.example.ava.settings.ExperimentalSettings
import com.example.ava.settings.HomeLockSettings
import com.example.ava.settings.HomeLockTarget
import com.example.ava.settings.MicrophoneSettings
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.VideoRecordingStateManager
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.QuickEntitySettings
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.resolvedCameraMode
import com.example.ava.utils.DeviceCapabilities
import com.example.ava.webcompat.EngineCapabilities
import com.example.ava.webcompat.GeckoSatelliteStatusHolder
import com.example.ava.webcompat.HostSidebarCommandBridge
import com.example.ava.webcompat.HostSidebarSettingsContract
import org.json.JSONObject

object HomeSidebarActions {

    fun isSatelliteStarted(): Boolean {
        if (EngineCapabilities.GECKO_BUNDLED) {
            return GeckoSatelliteStatusHolder.isStarted()
        }
        return VoiceSatelliteService.isSatelliteStarted()
    }

    private fun canPersistVisibleOn(visible: Boolean): Boolean =
        !visible || isSatelliteStarted()

    fun isVoiceMessageAvailable(settings: PlayerSettings): Boolean =
        settings.enableVoiceMessageOverlay

    fun isBrowserAvailable(settings: BrowserSettings): Boolean =
        settings.haRemoteUrlEnabled

    fun isWeatherAvailable(settings: PlayerSettings): Boolean =
        settings.enableWeatherOverlay

    fun isSimpleClockAvailable(settings: PlayerSettings): Boolean =
        settings.enableScreensaver

    fun isDreamClockAvailable(settings: PlayerSettings): Boolean =
        settings.enableDreamClock

    fun isQuickEntityAvailable(settings: QuickEntitySettings): Boolean =
        settings.enableQuickEntity

    /** HA `vinyl_cover_display` exposure must be on — otherwise hide the sidebar row. */
    fun isVinylCoverDisplayAvailable(settings: PlayerSettings): Boolean =
        settings.enableVinylCoverDisplay

    fun isHomeLockAvailable(settings: HomeLockSettings): Boolean =
        settings.enabled && HomeLockTarget.resolved(settings) == HomeLockTarget.HOME

    fun isVideoRecordingAvailable(context: Context, settings: ExperimentalSettings): Boolean =
        settings.cameraEnabled &&
            settings.resolvedCameraMode() == CameraMode.VIDEO &&
            DeviceCapabilities.hasCamera(context)

    fun isMuteMicrophoneAvailable(): Boolean = true

    fun isDarkModeAvailable(): Boolean = true

    fun isTouchPadAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !EngineCapabilities.GECKO_BUNDLED

    fun isTouchPadSelected(): Boolean = TouchPadOverlay.isShowing()

    fun toggleTouchPad(context: Context): Boolean {
        if (!ensureTouchPadReady(context)) return false
        return TouchPadOverlay.toggle()
    }

    fun openTouchPad(context: Context): Boolean {
        if (!ensureTouchPadReady(context)) return false
        if (TouchPadOverlay.isShowing()) return true
        return TouchPadOverlay.show()
    }

    private fun ensureTouchPadReady(context: Context): Boolean {
        if (!isTouchPadAvailable()) return false
        if (!OverlayPermission.isGranted(context)) {
            requestOverlayPermission(context, R.string.touch_pad_needs_overlay)
            return false
        }
        if (!AccessibilityBridge.isEnabled(context)) {
            requestAccessibilityPermission(context, R.string.touch_pad_needs_accessibility)
            return false
        }
        if (!AccessibilityBridge.isServiceConnected()) {
            AvaToast.show(context, R.string.touch_pad_needs_accessibility)
            return false
        }
        return true
    }

    fun isVideoRecordingSelected(context: Context): Boolean =
        VideoRecordingStateManager.getInstance(context).isEnabled()

    fun isMuteMicrophoneSelected(settings: MicrophoneSettings): Boolean =
        settings.muted

    fun isDarkModeSelected(context: Context): Boolean =
        DarkModeManager.getInstance(context).isDarkMode()

    fun setDarkMode(context: Context, enabled: Boolean) {
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarCommandBridge.send(
                HostSidebarSettingsContract.CMD_SET_DARK_MODE,
                JSONObject().put("enabled", enabled).toString()
            )
            return
        }
        DarkModeManager.getInstance(context).setDarkMode(enabled)
    }

    suspend fun setVideoRecording(context: Context, enabled: Boolean) {
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarCommandBridge.send(
                HostSidebarSettingsContract.CMD_SET_VIDEO_RECORDING,
                JSONObject().put("enabled", enabled).toString()
            )
            return
        }
        if (!isSatelliteStarted()) return
        VoiceSatelliteService.setVideoRecording(enabled)
    }

    suspend fun setMuteMicrophone(muted: Boolean) {
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarCommandBridge.send(
                HostSidebarSettingsContract.CMD_SET_MIC_MUTE,
                JSONObject().put("muted", muted).toString()
            )
            return
        }
        if (!isSatelliteStarted()) return
        VoiceSatelliteService.setMicMute(muted)
    }

    suspend fun toggleVoiceMessage(playerSettingsStore: PlayerSettingsStore): Boolean {
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarCommandBridge.send(HostSidebarSettingsContract.CMD_TOGGLE_VOICE_MESSAGE)
            val settings = playerSettingsStore.get()
            return !(settings.enableVoiceMessageOverlay && settings.enableVoiceMessageOverlayVisible)
        }
        val settings = playerSettingsStore.get()
        val nextVisible = !settings.enableVoiceMessageOverlayVisible
        setVoiceMessageVisible(playerSettingsStore, nextVisible)
        return nextVisible
    }

    suspend fun setVoiceMessageVisible(
        playerSettingsStore: PlayerSettingsStore,
        visible: Boolean
    ) {
        if (!canPersistVisibleOn(visible)) return
        val settings = playerSettingsStore.get()
        val masterWasOff = !settings.enableVoiceMessageOverlay
        val displayWasOff = !settings.enableVoiceMessageOverlayDisplay

        if (visible) {
            if (masterWasOff) {
                playerSettingsStore.enableVoiceMessageOverlay.set(true)
            }
            if (displayWasOff) {
                playerSettingsStore.enableVoiceMessageOverlayDisplay.set(true)
            }
        }

        playerSettingsStore.enableVoiceMessageOverlayVisible.set(visible)

        if (visible && (masterWasOff || displayWasOff)) {
            VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
        }
    }

    fun isVoiceMessageSelected(settings: PlayerSettings): Boolean {
        return settings.enableVoiceMessageOverlay && settings.enableVoiceMessageOverlayVisible
    }

    suspend fun toggleBrowser(browserSettingsStore: BrowserSettingsStore): Boolean {
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarCommandBridge.send(HostSidebarSettingsContract.CMD_TOGGLE_BROWSER)
            val settings = browserSettingsStore.get()
            return !(settings.haRemoteUrlEnabled && settings.enableBrowserVisible)
        }
        val settings = browserSettingsStore.get()
        val nextVisible = !settings.enableBrowserVisible
        setBrowserVisible(browserSettingsStore, nextVisible)
        return nextVisible
    }

    suspend fun setBrowserVisible(
        browserSettingsStore: BrowserSettingsStore,
        visible: Boolean
    ) {
        if (!canPersistVisibleOn(visible)) return
        val settings = browserSettingsStore.get()
        val displayWasOff = !settings.enableBrowserDisplay

        if (visible && displayWasOff) {
            browserSettingsStore.setEnableBrowserDisplay(true)
        }

        browserSettingsStore.enableBrowserVisible.set(visible)

        if (visible && displayWasOff) {
            VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
        }
    }

    fun isBrowserSelected(settings: BrowserSettings): Boolean {
        return settings.haRemoteUrlEnabled && settings.enableBrowserVisible
    }

    suspend fun toggleWeather(playerSettingsStore: PlayerSettingsStore): Boolean {
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarCommandBridge.send(HostSidebarSettingsContract.CMD_TOGGLE_WEATHER)
            val settings = playerSettingsStore.get()
            return !(settings.enableWeatherOverlay && settings.enableWeatherOverlayVisible)
        }
        val settings = playerSettingsStore.get()
        val nextVisible = !settings.enableWeatherOverlayVisible
        setWeatherVisible(playerSettingsStore, nextVisible)
        return nextVisible
    }

    suspend fun setWeatherVisible(
        playerSettingsStore: PlayerSettingsStore,
        visible: Boolean
    ) {
        if (!canPersistVisibleOn(visible)) return
        val settings = playerSettingsStore.get()
        val displayWasOff = !settings.enableWeatherOverlayDisplay

        if (visible && displayWasOff) {
            playerSettingsStore.enableWeatherOverlayDisplay.set(true)
        }

        playerSettingsStore.enableWeatherOverlayVisible.set(visible)

        if (visible && displayWasOff) {
            VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
        }
    }

    fun isWeatherSelected(settings: PlayerSettings): Boolean {
        return settings.enableWeatherOverlay && settings.enableWeatherOverlayVisible
    }

    suspend fun toggleSimpleClock(playerSettingsStore: PlayerSettingsStore): Boolean {
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarCommandBridge.send(HostSidebarSettingsContract.CMD_TOGGLE_SIMPLE_CLOCK)
            val settings = playerSettingsStore.get()
            return !(settings.enableScreensaver && settings.enableScreensaverVisible)
        }
        val settings = playerSettingsStore.get()
        val nextVisible = !settings.enableScreensaverVisible
        setSimpleClockVisible(playerSettingsStore, nextVisible)
        return nextVisible
    }

    suspend fun setSimpleClockVisible(
        playerSettingsStore: PlayerSettingsStore,
        visible: Boolean
    ) {
        if (!canPersistVisibleOn(visible)) return
        val settings = playerSettingsStore.get()
        val displayWasOff = !settings.enableScreensaverDisplay

        if (visible && displayWasOff) {
            playerSettingsStore.enableScreensaverDisplay.set(true)
        }

        playerSettingsStore.enableScreensaverVisible.set(visible)

        if (visible && displayWasOff) {
            VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
        }
    }

    fun isSimpleClockSelected(settings: PlayerSettings): Boolean {
        return settings.enableScreensaver && settings.enableScreensaverVisible
    }

    suspend fun toggleDreamClock(playerSettingsStore: PlayerSettingsStore): Boolean {
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarCommandBridge.send(HostSidebarSettingsContract.CMD_TOGGLE_DREAM_CLOCK)
            val settings = playerSettingsStore.get()
            return !(settings.enableDreamClock && settings.enableDreamClockVisible)
        }
        val settings = playerSettingsStore.get()
        val nextVisible = !settings.enableDreamClockVisible
        setDreamClockVisible(playerSettingsStore, nextVisible)
        return nextVisible
    }

    suspend fun setDreamClockVisible(
        playerSettingsStore: PlayerSettingsStore,
        visible: Boolean
    ) {
        if (!canPersistVisibleOn(visible)) return
        val settings = playerSettingsStore.get()
        val displayWasOff = !settings.enableDreamClockDisplay

        if (visible && displayWasOff) {
            playerSettingsStore.enableDreamClockDisplay.set(true)
        }

        playerSettingsStore.enableDreamClockVisible.set(visible)

        if (visible && displayWasOff) {
            VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
        }
    }

    fun isDreamClockSelected(settings: PlayerSettings): Boolean {
        return settings.enableDreamClock && settings.enableDreamClockVisible
    }

    suspend fun toggleQuickEntity(
        context: Context,
        quickEntitySettingsStore: QuickEntitySettingsStore
    ): Boolean {
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarCommandBridge.send(HostSidebarSettingsContract.CMD_TOGGLE_QUICK_ENTITY)
            val settings = quickEntitySettingsStore.get()
            return !(settings.enableQuickEntity && settings.enableQuickEntityDisplay)
        }
        val settings = quickEntitySettingsStore.get()
        val nextVisible = !settings.enableQuickEntityDisplay
        setQuickEntityVisible(context, quickEntitySettingsStore, nextVisible)
        return nextVisible
    }

    suspend fun setQuickEntityVisible(
        context: Context,
        quickEntitySettingsStore: QuickEntitySettingsStore,
        visible: Boolean
    ) {
        if (!canPersistVisibleOn(visible)) return
        quickEntitySettingsStore.enableQuickEntityDisplay.set(visible)
        if (visible) {
            QuickEntityOverlayService.show(context)
        } else {
            QuickEntityOverlayService.hide(context)
        }
    }

    fun isQuickEntitySelected(settings: QuickEntitySettings): Boolean {
        return settings.enableQuickEntity && settings.enableQuickEntityDisplay
    }

    suspend fun toggleVinylCoverDisplay(playerSettingsStore: PlayerSettingsStore): Boolean {
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarCommandBridge.send(HostSidebarSettingsContract.CMD_TOGGLE_VINYL_COVER_DISPLAY)
            val settings = playerSettingsStore.get()
            return !(settings.enableVinylCoverDisplay && settings.enableVinylCoverVisible)
        }
        val settings = playerSettingsStore.get()
        val nextVisible = !settings.enableVinylCoverVisible
        setVinylCoverDisplayVisible(playerSettingsStore, nextVisible)
        return nextVisible
    }

    suspend fun setVinylCoverDisplayVisible(
        playerSettingsStore: PlayerSettingsStore,
        visible: Boolean
    ) {
        if (!canPersistVisibleOn(visible)) return
        // Do not auto-enable [PlayerSettings.enableVinylCoverDisplay] — the sidebar
        // row is only offered when that HA entity exposure is already on.
        playerSettingsStore.enableVinylCoverVisible.set(visible)
    }

    fun isVinylCoverDisplaySelected(settings: PlayerSettings): Boolean {
        return settings.enableVinylCoverDisplay && settings.enableVinylCoverVisible
    }

    /**
     * One-way. Hides the browser overlay when it is up, then shows the home screen.
     * Does not open the browser again.
     */
    suspend fun openHome(context: Context, browserSettingsStore: BrowserSettingsStore) {
        OverlayLayerSplit.closeForBrowserNavigation(context)
        if (EngineCapabilities.GECKO_BUNDLED) {
            HostSidebarCommandBridge.send(HostSidebarSettingsContract.CMD_OPEN_LAUNCHER)
            return
        }
        setBrowserVisible(browserSettingsStore, false)
        WebViewService.hide(context)
        val appContext = context.applicationContext
        appContext.startActivity(
            Intent(appContext, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra("navigate_to", Screen.HOME)
            }
        )
        MainNavigationCoordinator.requestNavigation(Screen.HOME)
    }
}
