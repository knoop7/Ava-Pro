package com.example.ava.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.fleet.FleetNetwork
import com.example.ava.settings.VoiceTtsEngine
import com.example.ava.settings.VoiceTtsSettingsStore
import com.example.ava.settings.voiceTtsSettingsStore

@Composable
fun VoiceTtsSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val settings = remember { VoiceTtsSettingsStore(context.voiceTtsSettingsStore) }
    val seed = remember { settings.getCached() }
    val engine by settings.engine.collectAsStateWithLifecycle(seed.engine)

    VoiceModEnginePickerScreen(
        navController = navController,
        title = stringResource(R.string.settings_voice_tts_entry_title),
        caption = stringResource(R.string.settings_voice_tts_entry_desc),
        nativeTitle = stringResource(R.string.settings_ha_entry_title),
        nativeDesc = stringResource(R.string.settings_voice_stt_native_desc),
        modTitle = stringResource(R.string.settings_voice_tts_mod),
        modDesc = stringResource(R.string.settings_voice_tts_mod_desc),
        noteIntroBody = stringResource(R.string.settings_voice_tts_mod_note),
        noteIp = remember { FleetNetwork.getLocalIpAddress(context) ?: "—" },
        notePort = VoiceTtsSettingsStore.WYOMING_PORT,
        modId = VoiceTtsSettingsStore.HA_EDGE_TTS_MOD_ID,
        pipelineSelected = engine == VoiceTtsEngine.PIPELINE,
        onSelectPipeline = { settings.engine.set(VoiceTtsEngine.PIPELINE) },
        onSelectMod = { settings.engine.set(VoiceTtsEngine.HA_EDGE_TTS) },
        stillModSelected = { settings.getCached().engine == VoiceTtsEngine.HA_EDGE_TTS },
        revertToPipeline = { settings.engine.set(VoiceTtsEngine.PIPELINE) },
    )
}
