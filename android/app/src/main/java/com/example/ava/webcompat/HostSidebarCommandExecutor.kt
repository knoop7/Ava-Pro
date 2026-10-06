package com.example.ava.webcompat

import android.content.Context
import android.util.Log
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.DarkModeManager
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.ui.screens.home.HomeSidebarActions
import org.json.JSONObject

object HostSidebarCommandExecutor {
    private const val TAG = "HostSidebarCommandExec"

    suspend fun execute(context: Context, command: String, payloadJson: String?) {
        val appContext = context.applicationContext
        try {
            when (command) {
                HostSidebarSettingsContract.CMD_TOGGLE_VOICE_MESSAGE ->
                    HomeSidebarActions.toggleVoiceMessage(PlayerSettingsStore(appContext.playerSettingsStore))
                HostSidebarSettingsContract.CMD_OPEN_LAUNCHER ->
                    HomeSidebarActions.openHome(appContext, BrowserSettingsStore(appContext))
                HostSidebarSettingsContract.CMD_TOGGLE_BROWSER ->
                    HomeSidebarActions.toggleBrowser(BrowserSettingsStore(appContext))
                HostSidebarSettingsContract.CMD_TOGGLE_WEATHER ->
                    HomeSidebarActions.toggleWeather(PlayerSettingsStore(appContext.playerSettingsStore))
                HostSidebarSettingsContract.CMD_TOGGLE_SIMPLE_CLOCK ->
                    HomeSidebarActions.toggleSimpleClock(PlayerSettingsStore(appContext.playerSettingsStore))
                HostSidebarSettingsContract.CMD_TOGGLE_DREAM_CLOCK ->
                    HomeSidebarActions.toggleDreamClock(PlayerSettingsStore(appContext.playerSettingsStore))
                HostSidebarSettingsContract.CMD_TOGGLE_QUICK_ENTITY ->
                    HomeSidebarActions.toggleQuickEntity(
                        appContext,
                        QuickEntitySettingsStore(appContext.quickEntitySettingsStore)
                    )
                HostSidebarSettingsContract.CMD_TOGGLE_VINYL_COVER_DISPLAY ->
                    HomeSidebarActions.toggleVinylCoverDisplay(
                        PlayerSettingsStore(appContext.playerSettingsStore)
                    )
                HostSidebarSettingsContract.CMD_SET_VIDEO_RECORDING -> {
                    val enabled = JSONObject(payloadJson ?: "{}").optBoolean("enabled", false)
                    HomeSidebarActions.setVideoRecording(appContext, enabled)
                }
                HostSidebarSettingsContract.CMD_SET_MIC_MUTE -> {
                    val muted = JSONObject(payloadJson ?: "{}").optBoolean("muted", false)
                    HomeSidebarActions.setMuteMicrophone(muted)
                }
                HostSidebarSettingsContract.CMD_SET_DARK_MODE -> {
                    val enabled = JSONObject(payloadJson ?: "{}").optBoolean("enabled", false)
                    DarkModeManager.getInstance(appContext).setDarkMode(enabled)
                    BrowserEngine.refreshGeckoDarkMode(appContext)
                }
                HostSidebarSettingsContract.CMD_SET_USER_AGENT_MODE -> {
                    val mode = JSONObject(payloadJson ?: "{}").optInt("mode", 0).coerceIn(0, 3)
                    BrowserSettingsStore(appContext).setUserAgentMode(mode)
                }
                else -> Log.w(TAG, "Unknown sidebar command: $command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute sidebar command=$command", e)
        } finally {
            HostSidebarSettingsBridge.pushToGeckoPack(appContext)
        }
    }
}
