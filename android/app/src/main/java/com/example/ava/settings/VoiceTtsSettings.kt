package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Which text-to-speech path the voice hub uses.
 *
 * [PIPELINE] is the default: Home Assistant's current pipeline TTS.
 * [HA_EDGE_TTS] is the store slice `ha-edge-tts`.
 */
@Serializable
enum class VoiceTtsEngine {
    PIPELINE,
    HA_EDGE_TTS,
}

@Serializable
data class VoiceTtsSettings(
    val engine: VoiceTtsEngine = VoiceTtsEngine.PIPELINE,
)

val Context.voiceTtsSettingsStore: DataStore<VoiceTtsSettings> by dataStore(
    fileName = "voice_tts_settings.json",
    serializer = SettingsSerializer(VoiceTtsSettings.serializer(), VoiceTtsSettings()),
    corruptionHandler = defaultCorruptionHandler(VoiceTtsSettings()),
)

class VoiceTtsSettingsStore(dataStore: DataStore<VoiceTtsSettings>) :
    SettingsStoreImpl<VoiceTtsSettings>(dataStore, VoiceTtsSettings()) {

    val engine = SettingState(getFlow().map { it.engine }) { value ->
        update { it.copy(engine = value) }
    }

    companion object {
        const val HA_EDGE_TTS_MOD_ID = "ha-edge-tts"
        const val WYOMING_PORT = 10301
    }
}
