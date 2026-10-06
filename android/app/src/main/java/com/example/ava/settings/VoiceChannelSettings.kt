package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class VoiceChannelSettings(
    val enabled: Boolean = true
)

val Context.voiceChannelSettingsStore: DataStore<VoiceChannelSettings> by dataStore(
    fileName = "voice_channel_settings.json",
    serializer = SettingsSerializer(VoiceChannelSettings.serializer(), VoiceChannelSettings()),
    corruptionHandler = defaultCorruptionHandler(VoiceChannelSettings())
)

class VoiceChannelSettingsStore(dataStore: DataStore<VoiceChannelSettings>) :
    SettingsStoreImpl<VoiceChannelSettings>(dataStore, VoiceChannelSettings()) {
    val enabled =
        SettingState(getFlow().map { it.enabled }) { value -> update { it.copy(enabled = value) } }
}
