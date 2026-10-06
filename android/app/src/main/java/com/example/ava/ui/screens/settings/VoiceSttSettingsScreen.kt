package com.example.ava.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.fleet.FleetNetwork
import com.example.ava.homeassistant.HaManager
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.VoiceSttEngine
import com.example.ava.settings.VoiceSttSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.settings.voiceSttSettingsStore
import com.example.ava.ui.screens.settings.components.SettingsHelpBodyText
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.voice.FinishedSpeaking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

@Composable
fun VoiceSttSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val settings = remember { VoiceSttSettingsStore(context.voiceSttSettingsStore) }
    val seed = remember { settings.getCached() }
    val engine by settings.engine.collectAsStateWithLifecycle(seed.engine)
    val mac = remember {
        VoiceSatelliteSettingsStore(context.voiceSatelliteSettingsStore).getCached().macAddress
    }
    val ha = remember { HaManager.ensure(context) }

    VoiceModEnginePickerScreen(
        navController = navController,
        title = stringResource(R.string.settings_voice_stt_entry_title),
        caption = stringResource(R.string.settings_voice_stt_entry_desc),
        nativeTitle = stringResource(R.string.settings_ha_entry_title),
        nativeDesc = stringResource(R.string.settings_voice_stt_native_desc),
        modTitle = stringResource(R.string.settings_voice_stt_mod),
        modDesc = stringResource(R.string.settings_voice_stt_mod_desc),
        noteIntroBody = stringResource(R.string.settings_voice_stt_mod_note),
        noteIp = remember { FleetNetwork.getLocalIpAddress(context) ?: "—" },
        notePort = VoiceSttSettingsStore.WYOMING_PORT,
        modId = VoiceSttSettingsStore.HA_STT_MOD_ID,
        pipelineSelected = engine == VoiceSttEngine.PIPELINE,
        onSelectPipeline = { settings.engine.set(VoiceSttEngine.PIPELINE) },
        onSelectMod = { settings.engine.set(VoiceSttEngine.HA_STT_ENGINE) },
        stillModSelected = { settings.getCached().engine == VoiceSttEngine.HA_STT_ENGINE },
        revertToPipeline = { settings.engine.set(VoiceSttEngine.PIPELINE) },
        selectOnlyAfterDownload = true,
        footer = {
            item(key = "finished_speaking") {
                FinishedSpeakingCard(ha = ha, mac = mac)
            }
        },
    )
}

@Composable
private fun FinishedSpeakingCard(ha: HaManager, mac: String) {
    val scope = rememberCoroutineScope()
    var entityId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val accent = getAccentColor()

    LaunchedEffect(mac) {
        val (id, option) = ha.finishedSpeaking(mac)
        entityId = id
        selected = option
        loaded = true
        if (id == null) return@LaunchedEffect
        val subscription = ha.watchFinishedSpeaking(id) { state ->
            val next = FinishedSpeaking.canonical(state) ?: return@watchFinishedSpeaking
            scope.launch { selected = next }
        }
        if (subscription == null) return@LaunchedEffect
        try {
            awaitCancellation()
        } finally {
            ha.unwatchFinishedSpeaking(subscription)
        }
    }

    SimpleCard {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.settings_finished_speaking_title),
                fontSize = settingsTitleTextSize(base = 17f),
                fontWeight = FontWeight.Medium,
                color = getLabelColor(),
                modifier = Modifier.padding(top = 8.dp),
            )
            SettingsHelpBodyText(
                text = stringResource(R.string.settings_finished_speaking_desc),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (loaded && entityId == null) {
                Text(
                    text = stringResource(R.string.settings_finished_speaking_missing),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            FinishedSpeaking.options.forEach { option ->
                val label = when (option) {
                    FinishedSpeaking.DEFAULT -> stringResource(R.string.settings_finished_speaking_default)
                    FinishedSpeaking.RELAXED -> stringResource(R.string.settings_finished_speaking_relaxed)
                    else -> stringResource(R.string.settings_finished_speaking_aggressive)
                }
                Text(
                    text = label,
                    fontSize = settingsBodyTextSize(),
                    fontWeight = if (option == selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (option == selected) accent else getLabelColor(),
                    modifier = Modifier
                        .clickable(enabled = entityId != null) {
                            val previous = selected
                            selected = option
                            scope.launch {
                                if (!ha.setFinishedSpeaking(mac, option)) selected = previous
                            }
                        }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}
