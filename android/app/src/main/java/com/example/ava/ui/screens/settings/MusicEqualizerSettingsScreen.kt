package com.example.ava.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SliderDefaults
import com.example.ava.ui.haptic.TickSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.audio.eq.MusicEqGains
import com.example.ava.audio.eq.MusicEqPreset
import com.example.ava.audio.eq.MusicEqSource
import com.example.ava.audio.eq.toHaMusicEqGains
import com.example.ava.audio.eq.toMusicEqGains
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.SettingSliderLabelRow
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.SwitchSetting
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import kotlinx.coroutines.launch

@Composable
fun MusicEqualizerSettingsScreen(
    navController: NavController,
    source: MusicEqSource,
    viewModel: SettingsViewModel = viewModel(),
) {
    val coroutineScope = rememberCoroutineScope()
    val playerState by viewModel.playerSettingsState.collectAsStateWithLifecycle(null)
    val sendspinState by viewModel.sendspinSettingsState.collectAsStateWithLifecycle(null)
    val enabled = when (source) {
        MusicEqSource.HA -> playerState != null
        MusicEqSource.SENDSPIN -> sendspinState != null
    }
    val gains = when (source) {
        MusicEqSource.HA -> playerState?.toHaMusicEqGains() ?: MusicEqGains.FLAT
        MusicEqSource.SENDSPIN -> sendspinState?.toMusicEqGains() ?: MusicEqGains.FLAT
    }

    fun persist(next: MusicEqGains) {
        coroutineScope.launch {
            when (source) {
                MusicEqSource.HA -> viewModel.saveHaMusicEq(next)
                MusicEqSource.SENDSPIN -> viewModel.saveSendspinMusicEq(next)
            }
        }
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_music_eq_title),
    ) {
        item(key = "music_eq_switch_card") {
            SimpleCard {
                SwitchSetting(
                    name = stringResource(R.string.settings_music_eq_enable),
                    description = stringResource(R.string.settings_music_eq_enable_desc),
                    value = gains.enabled,
                    enabled = enabled,
                    onCheckedChange = { checked -> persist(gains.copy(enabled = checked)) },
                )
                if (gains.enabled) {
                    SettingsDivider()
                    EqPresetPicker(
                        gains = gains,
                        enabled = enabled,
                        onSelect = { preset -> persist(preset.applyTo(gains)) },
                    )
                    SettingsDivider()
                    SwitchSetting(
                        name = stringResource(R.string.settings_music_eq_adaptive),
                        description = stringResource(R.string.settings_music_eq_adaptive_desc),
                        value = gains.adaptiveEnabled,
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            persist(gains.copy(adaptiveEnabled = checked))
                        },
                    )
                }
            }
        }

        if (gains.enabled) {
            item(key = "music_eq_bands_card") {
                SimpleCard {
                    EqBandSlider(
                        title = stringResource(R.string.settings_music_eq_band_bass),
                        valueDb = gains.bassDb,
                        enabled = enabled,
                        onCommit = { persist(gains.withBandDb(0, it)) },
                    )
                    SettingsDivider()
                    EqBandSlider(
                        title = stringResource(R.string.settings_music_eq_band_low_mid),
                        valueDb = gains.lowMidDb,
                        enabled = enabled,
                        onCommit = { persist(gains.withBandDb(1, it)) },
                    )
                    SettingsDivider()
                    EqBandSlider(
                        title = stringResource(R.string.settings_music_eq_band_mid),
                        valueDb = gains.midDb,
                        enabled = enabled,
                        onCommit = { persist(gains.withBandDb(2, it)) },
                    )
                    SettingsDivider()
                    EqBandSlider(
                        title = stringResource(R.string.settings_music_eq_band_upper_mid),
                        valueDb = gains.upperMidDb,
                        enabled = enabled,
                        onCommit = { persist(gains.withBandDb(3, it)) },
                    )
                    SettingsDivider()
                    EqBandSlider(
                        title = stringResource(R.string.settings_music_eq_band_treble),
                        valueDb = gains.trebleDb,
                        enabled = enabled,
                        onCommit = { persist(gains.withBandDb(4, it)) },
                    )
                }
            }

            item(key = "music_eq_reset") {
                TextButton(
                    onClick = {
                        persist(
                            gains.copy(
                                bassDb = 0f,
                                lowMidDb = 0f,
                                midDb = 0f,
                                upperMidDb = 0f,
                                trebleDb = 0f,
                            )
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_music_eq_reset),
                        color = getAccentColor(),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** Same picker chrome as the software-update version list. */
@Composable
private fun EqPresetPicker(
    gains: MusicEqGains,
    enabled: Boolean,
    onSelect: (MusicEqPreset) -> Unit,
) {
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableIntStateOf(0) }
    var triggerHeightPx by remember { mutableIntStateOf(0) }
    val current = MusicEqPreset.matching(gains)
    val border = getSliderInactiveColor()
    val menuBg = getDialogBackground()
    val pickBg = getInputBackground()
    val labelColor = getTitleColor()
    val subColor = getSlateMutedColor()
    val accent = getAccentColor()
    val menuMaxHeight = 260.dp

    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.settings_music_eq_preset),
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.Medium,
            color = labelColor,
        )
        CollapsibleDescriptionText(
            text = stringResource(R.string.settings_music_eq_preset_desc),
            fontSize = settingsBodyTextSize(),
            lineHeight = settingsBodyLineHeight(),
            color = getSettingsDescriptionColor(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        triggerWidthPx = coords.size.width
                        triggerHeightPx = coords.size.height
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, border, RoundedCornerShape(14.dp))
                    .background(pickBg)
                    .clickable(enabled = enabled) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = current?.let { stringResource(it.labelRes) }
                        ?: stringResource(R.string.settings_music_eq_preset_custom),
                    modifier = Modifier.weight(1f),
                    color = if (current == null) accent else labelColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SettingsChevronIcon(tint = subColor, base = 20f)
            }
            if (expanded && triggerWidthPx > 0) {
                val menuOffsetY = triggerHeightPx + with(density) { 6.dp.roundToPx() }
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(0, menuOffsetY),
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Surface(
                        modifier = Modifier
                            .width(with(density) { triggerWidthPx.toDp() })
                            .heightIn(max = menuMaxHeight)
                            .shadow(
                                elevation = 12.dp,
                                shape = RoundedCornerShape(16.dp),
                                ambientColor = Color(0x2E0F172A),
                                spotColor = Color(0x2E0F172A),
                            ),
                        shape = RoundedCornerShape(16.dp),
                        color = menuBg,
                        border = BorderStroke(1.dp, border),
                    ) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = menuMaxHeight)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            MusicEqPreset.entries.forEach { preset ->
                                val isCurrent = preset == current
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expanded = false
                                            onSelect(preset)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(preset.labelRes),
                                        fontSize = 14.sp,
                                        fontWeight = if (isCurrent) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        },
                                        color = if (isCurrent) accent else labelColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EqBandSlider(
    title: String,
    valueDb: Float,
    enabled: Boolean,
    onCommit: (Float) -> Unit,
) {
    var sliderValue by remember(valueDb) { mutableFloatStateOf(valueDb) }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        SettingSliderLabelRow(
            title = title,
            badgeText = String.format("%+.0f dB", sliderValue),
        )
        TickSlider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onCommit(MusicEqGains.clampDb(sliderValue)) },
            valueRange = MusicEqGains.MIN_DB..MusicEqGains.MAX_DB,
            steps = ((MusicEqGains.MAX_DB - MusicEqGains.MIN_DB).toInt() * 2) - 1,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = getAccentColor(),
                activeTrackColor = getAccentColor(),
                inactiveTrackColor = getSliderInactiveColor(),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
internal fun MusicEqualizerEntryRow(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        SettingRow(
            label = stringResource(R.string.settings_music_eq_title),
            subLabel = stringResource(R.string.settings_music_eq_entry_desc),
        ) {
            SettingsChevronIcon(tint = Color(0xFF94A3B8))
        }
    }
}
