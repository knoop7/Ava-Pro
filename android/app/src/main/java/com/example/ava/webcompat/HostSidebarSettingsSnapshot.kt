package com.example.ava.webcompat

import android.content.Context
import com.example.ava.R
import com.example.ava.esphome.Connected
import com.example.ava.esphome.Stopped
import com.example.ava.settings.VideoRecordingStateManager
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.BrowserSettings
import com.example.ava.settings.DarkModeManager
import com.example.ava.settings.ExperimentalSettings
import com.example.ava.settings.MicrophoneSettings
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.QuickEntitySettings
import com.example.ava.settings.SidebarSettings
import com.example.ava.settings.browserSettingsDataStore
import com.example.ava.settings.experimentalSettingsDataStore
import com.example.ava.settings.microphoneSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.utils.translate
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HostSidebarSettingsSnapshot(
    val sidebar: SidebarSettings = SidebarSettings(),
    val player: PlayerSettings = PlayerSettings(),
    val browser: BrowserSettings = BrowserSettings.DEFAULT,
    val quickEntity: QuickEntitySettings = QuickEntitySettings(),
    val experimental: ExperimentalSettings = ExperimentalSettings.DEFAULT,
    val microphone: MicrophoneSettings = MicrophoneSettings(),
    val satelliteStarted: Boolean = false,
    val satelliteStatusText: String = "",
    val voiceChannelEnabled: Boolean = true,
    val darkMode: Boolean = false,
    val videoRecordingEnabled: Boolean = false,
)

object HostSidebarSettingsSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: HostSidebarSettingsSnapshot): String =
        json.encodeToString(HostSidebarSettingsSnapshot.serializer(), snapshot)

    fun decode(raw: String): HostSidebarSettingsSnapshot? = try {
        json.decodeFromString(HostSidebarSettingsSnapshot.serializer(), raw)
    } catch (_: Exception) {
        null
    }

    suspend fun capture(context: Context): HostSidebarSettingsSnapshot =
        HostSidebarSettingsSnapshotCoordinator.capture(context)

    suspend fun buildSnapshot(
        context: Context,
        bundle: SettingsStoreBundle,
    ): HostSidebarSettingsSnapshot {
        val appContext = context.applicationContext
        val satelliteStarted = VoiceSatelliteService.isSatelliteStarted()
        val satelliteStatusText = resolveSatelliteStatusText(
            appContext,
            satelliteStarted,
            bundle.voiceChannelEnabled,
        )
        return HostSidebarSettingsSnapshot(
            sidebar = bundle.sidebar,
            player = bundle.player,
            browser = bundle.browser,
            quickEntity = bundle.quickEntity,
            experimental = bundle.experimental,
            microphone = bundle.microphone,
            satelliteStarted = satelliteStarted,
            satelliteStatusText = satelliteStatusText,
            voiceChannelEnabled = bundle.voiceChannelEnabled,
            darkMode = DarkModeManager.getInstance(appContext).isDarkMode(),
            videoRecordingEnabled = VideoRecordingStateManager.getInstance(appContext).isEnabled(),
        )
    }

    suspend fun apply(context: Context, snapshot: HostSidebarSettingsSnapshot) {
        val appContext = context.applicationContext
        appContext.sidebarSettingsStore.updateData { snapshot.sidebar }
        appContext.playerSettingsStore.updateData { snapshot.player }
        appContext.browserSettingsDataStore.updateData { snapshot.browser }
        appContext.quickEntitySettingsStore.updateData { snapshot.quickEntity }
        appContext.experimentalSettingsDataStore.updateData { snapshot.experimental }
        appContext.microphoneSettingsStore.updateData { snapshot.microphone }
        if (EngineCapabilities.GECKO_BUNDLED) {
            if (DarkModeManager.getInstance(appContext).isDarkMode() != snapshot.darkMode) {
                DarkModeManager.getInstance(appContext).darkMode.set(snapshot.darkMode)
            }
            val recordingState = VideoRecordingStateManager.getInstance(appContext)
            if (recordingState.isEnabled() != snapshot.videoRecordingEnabled) {
                recordingState.setEnabled(snapshot.videoRecordingEnabled)
            }
            GeckoSatelliteStatusHolder.applySnapshot(snapshot)
        }
    }

    private suspend fun resolveSatelliteStatusText(
        context: Context,
        satelliteStarted: Boolean,
        voiceChannelEnabled: Boolean
    ): String {
        val resources = context.resources
        if (!satelliteStarted) {
            return resources.getString(R.string.satellite_state_stopped)
        }
        val service = VoiceSatelliteService.getInstance()
            ?: return resources.getString(R.string.status_disconnected)
        val state = service.voiceSatelliteState.first()
        return if (voiceChannelEnabled) {
            state.translate(resources)
        } else {
            when (state) {
                is Connected -> resources.getString(R.string.status_connected)
                else -> resources.getString(R.string.status_stopped)
            }
        }
    }
}
