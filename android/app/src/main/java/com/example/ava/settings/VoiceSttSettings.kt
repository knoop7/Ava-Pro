package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Which speech-to-text path the voice hub uses.
 *
 * [PIPELINE] is the default: Home Assistant's current pipeline STT, no extra pack.
 * [HA_STT_ENGINE] is the store slice `ha-stt-engine` (SenseVoice on this device).
 */
@Serializable
enum class VoiceSttEngine {
    PIPELINE,
    HA_STT_ENGINE,
}

@Serializable
data class VoiceSttSettings(
    val engine: VoiceSttEngine = VoiceSttEngine.PIPELINE,
)

val Context.voiceSttSettingsStore: DataStore<VoiceSttSettings> by dataStore(
    fileName = "voice_stt_settings.json",
    serializer = SettingsSerializer(VoiceSttSettings.serializer(), VoiceSttSettings()),
    corruptionHandler = defaultCorruptionHandler(VoiceSttSettings()),
)

class VoiceSttSettingsStore(dataStore: DataStore<VoiceSttSettings>) :
    SettingsStoreImpl<VoiceSttSettings>(dataStore, VoiceSttSettings()) {

    val engine = SettingState(getFlow().map { it.engine }) { value ->
        update { it.copy(engine = value) }
    }

    companion object {
        const val HA_STT_MOD_ID = "ha-stt-engine"
        const val WYOMING_PORT = 10300
    }
}
