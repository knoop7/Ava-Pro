package com.example.ava.ui.screens.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SliderDefaults
import com.example.ava.ui.haptic.TickSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.R
import com.example.ava.settings.MediaOverlayStyle
import com.example.ava.settings.VolumeFollowRule
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.SelectSetting
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getDialogBackground
import com.example.ava.ui.screens.settings.getTitleColor
import com.example.ava.ui.screens.settings.components.SettingSliderLabelRow
import kotlinx.coroutines.launch
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.settingsClickable

internal fun LazyListScope.mediaPlayerSettingsItems(
    viewModel: SettingsViewModel,
    uiState: UIState?,
    playerState: com.example.ava.settings.PlayerSettings?,
    enabled: Boolean,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    navController: androidx.navigation.NavController,
) {
    // Style / Mini / lyrics only matter while at least one media-control switch
    // can birth the overlay — hide the whole card when both HA + MA are off.
    val overlayStyleCardVisible =
        playerState?.enableHaVinylCover == true ||
            playerState?.enableSendspinVinylCover == true
    if (overlayStyleCardVisible) {
        item(key = "playback_section_overlay") {
            SettingsSectionLabel(stringResource(R.string.settings_media_overlay_style))
        }

        item(key = "playback_card_overlay") {
            SimpleCard {
                val styleMinimal = stringResource(R.string.settings_media_overlay_style_minimal)
                val styleMinimalDesc = stringResource(R.string.settings_media_overlay_style_minimal_desc)
                val styleDetailed = stringResource(R.string.settings_media_overlay_style_detailed)
                val styleDetailedDesc = stringResource(R.string.settings_media_overlay_style_detailed_desc)
                SelectSetting(
                    name = stringResource(R.string.settings_media_overlay_style),
                    description = stringResource(R.string.settings_media_overlay_style_desc),
                    selected = MediaOverlayStyle.fromStored(playerState?.mediaOverlayStyle),
                    items = MediaOverlayStyle.entries,
                    enabled = enabled,
                    key = { it.storageKey },
                    value = { style ->
                        when (style) {
                            MediaOverlayStyle.DETAILED -> styleDetailed
                            MediaOverlayStyle.MINIMAL, null -> styleMinimal
                        }
                    },
                    itemDescription = { style ->
                        when (style) {
                            MediaOverlayStyle.DETAILED -> styleDetailedDesc
                            else -> styleMinimalDesc
                        }
                    },
                    onConfirmRequest = { style ->
                        if (style != null) {
                            coroutineScope.launch {
                                viewModel.saveMediaOverlayStyle(style)
                                // Detailed-only HA attributes (album/position/duration) are
                                // subscribed at connect time; restart to apply the new set.
                                restartVoiceSatelliteServiceIfRunning()
                            }
                        }
                    },
                )

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_eq_mini_player),
                    subLabel = stringResource(R.string.settings_eq_mini_player_desc)
                ) {
                    ModernSwitch(
                        checked = playerState?.enableEqMiniPlayer == true,
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            togglePlaybackOverlaySetting(
                                context = context,
                                coroutineScope = coroutineScope,
                                enabling = checked,
                            ) {
                                viewModel.saveEqMiniPlayer(checked)
                            }
                        }
                    )
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_vinyl_cover_ha_display),
                    subLabel = stringResource(R.string.settings_vinyl_cover_ha_display_desc)
                ) {
                    ModernSwitch(
                        checked = playerState?.enableVinylCoverDisplay == true,
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                viewModel.saveVinylCoverDisplay(checked)
                                restartVoiceSatelliteServiceIfRunning()
                            }
                        }
                    )
                }
            }
        }
    }

    item(key = "playback_section_ha") {
        SettingsSectionLabel(stringResource(R.string.settings_playback_section_ha))
    }

    item(key = "playback_card_ha") {
        SimpleCard {
            SettingRow(
                label = stringResource(R.string.settings_expose_esphome_media_player),
                subLabel = stringResource(R.string.settings_expose_esphome_media_player_desc)
            ) {
                ModernSwitch(
                    checked = playerState?.exposeEsphomeMediaPlayerEntity != false,
                    enabled = enabled,
                    onCheckedChange = { checked ->
                        coroutineScope.launch {
                            viewModel.saveExposeEsphomeMediaPlayerEntity(checked)
                            restartVoiceSatelliteServiceIfRunning()
                        }
                    }
                )
            }

            val exposeEsphomePlayer = playerState?.exposeEsphomeMediaPlayerEntity != false
            if (exposeEsphomePlayer) {
                SettingsDivider()

                val haVinylCoverEnabled = playerState?.enableHaVinylCover ?: true
                SettingRow(
                    label = stringResource(R.string.settings_ha_vinyl_cover),
                    subLabel = stringResource(R.string.settings_ha_vinyl_cover_desc)
                ) {
                    ModernSwitch(
                        checked = haVinylCoverEnabled,
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            togglePlaybackOverlaySetting(
                                context = context,
                                coroutineScope = coroutineScope,
                                enabling = checked,
                            ) {
                                viewModel.saveHaVinylCover(checked)
                            }
                        }
                    )
                }

                SettingsDivider()
                MediaPlayerEntitySetting(
                    viewModel = viewModel,
                    uiState = uiState,
                    enabled = enabled,
                    coroutineScope = coroutineScope
                )

                SettingsDivider()
                val followIndependent = stringResource(R.string.settings_volume_follow_independent)
                val followIndependentDesc = stringResource(R.string.settings_volume_follow_independent_desc)
                val followDevice = stringResource(R.string.settings_volume_follow_device)
                val followDeviceDesc = stringResource(R.string.settings_volume_follow_device_desc)
                val followHa = stringResource(R.string.settings_volume_follow_ha)
                val followHaDesc = stringResource(R.string.settings_volume_follow_ha_desc)
                SelectSetting(
                    name = stringResource(R.string.settings_volume_follow_rule),
                    description = stringResource(R.string.settings_volume_follow_rule_desc),
                    selected = VolumeFollowRule.fromStored(uiState?.volumeFollowRule),
                    items = VolumeFollowRule.entries,
                    enabled = enabled,
                    key = { it.storageKey },
                    value = { rule ->
                        when (rule) {
                            VolumeFollowRule.FOLLOW_DEVICE -> followDevice
                            VolumeFollowRule.FOLLOW_HA -> followHa
                            VolumeFollowRule.INDEPENDENT, null -> followIndependent
                        }
                    },
                    itemDescription = { rule ->
                        when (rule) {
                            VolumeFollowRule.FOLLOW_DEVICE -> followDeviceDesc
                            VolumeFollowRule.FOLLOW_HA -> followHaDesc
                            else -> followIndependentDesc
                        }
                    },
                    onConfirmRequest = { rule ->
                        if (rule != null) {
                            coroutineScope.launch {
                                viewModel.saveVolumeFollowRule(rule)
                            }
                        }
                    },
                )

                SettingsDivider()
                MusicEqualizerEntryRow(enabled = enabled) {
                    navController.navigate(com.example.ava.ui.Screen.SETTINGS_INTERACTION_PLAYBACK_EQ_HA) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    item(key = "playback_section_ma") {
        SettingsSectionLabel(stringResource(R.string.settings_playback_section_ma))
    }

    item(key = "playback_card_ma") {
        SimpleCard(
            modifier = Modifier.padding(bottom = 20.dp)
        ) {
            val sendspinEnabled = uiState?.sendspinEnabled ?: true
            SettingRow(
                label = stringResource(R.string.settings_sendspin_enabled),
                subLabel = stringResource(R.string.settings_sendspin_enabled_desc)
            ) {
                ModernSwitch(
                    checked = sendspinEnabled,
                    enabled = enabled,
                    onCheckedChange = { checked ->
                        coroutineScope.launch {
                            viewModel.saveSendspinEnabled(checked)
                        }
                    }
                )
            }

            if (sendspinEnabled) {
                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_sendspin_vinyl_cover),
                    subLabel = stringResource(R.string.settings_sendspin_vinyl_cover_desc)
                ) {
                    ModernSwitch(
                        checked = playerState?.enableSendspinVinylCover ?: true,
                        enabled = enabled,
                        onCheckedChange = { checked ->
                            togglePlaybackOverlaySetting(
                                context = context,
                                coroutineScope = coroutineScope,
                                enabling = checked,
                            ) {
                                viewModel.saveSendspinVinylCover(checked)
                            }
                        }
                    )
                }

                SettingsDivider()
                Box(
                    modifier = Modifier.settingsClickable(enabled = enabled) {
                        navController.navigate(com.example.ava.ui.Screen.SETTINGS_INTERACTION_PLAYBACK_MASS_API) {
                            launchSingleTop = true
                        }
                    },
                ) {
                    SettingRow(
                        label = stringResource(R.string.settings_mass_api_entry),
                        subLabel = stringResource(R.string.settings_mass_api_entry_desc),
                    ) {
                        SettingsChevronIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                SettingsDivider()
                SendspinDeviceNameSetting(
                    currentName = uiState?.sendspinCustomDeviceName ?: "",
                    enabled = enabled,
                    onSave = { newName ->
                        coroutineScope.launch {
                            viewModel.saveSendspinDeviceName(newName)
                            com.example.ava.services.VoiceSatelliteService.getInstance()
                                ?.restartSendspinSession()
                        }
                    }
                )

                SettingsDivider()
                val lowMemoryMode = uiState?.sendspinLowMemoryMode ?: false
                SettingRow(
                    label = stringResource(R.string.settings_sendspin_low_memory_mode),
                    subLabel = stringResource(R.string.settings_sendspin_low_memory_mode_desc)
                ) {
                    ModernSwitch(
                        checked = lowMemoryMode,
                        enabled = enabled,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveSendspinLowMemoryMode(it)
                                // Await DataStore write so the new session reads the flag.
                                com.example.ava.services.VoiceSatelliteService.getInstance()
                                    ?.restartSendspinSession()
                            }
                        }
                    )
                }

                SettingsDivider()
                val preferredFormat = uiState?.sendspinPreferredFormat ?: "automatic"
                val formatOptions by viewModel.sendspinFormatOptions.collectAsStateWithLifecycle()
                var showFormatDialog by remember { mutableStateOf(false) }
                val formatAutoLabel = stringResource(R.string.settings_sendspin_stream_format_automatic)
                val effectivePreferredFormat =
                    formatOptions.firstOrNull { it.first == preferredFormat }?.first ?: "automatic"
                LaunchedEffect(formatOptions, preferredFormat) {
                    if (
                        preferredFormat != "automatic" &&
                        formatOptions.none { it.first == preferredFormat }
                    ) {
                        viewModel.saveSendspinPreferredFormat("automatic")
                        // save* is suspend; restart only after preferredFormat is persisted.
                        com.example.ava.services.VoiceSatelliteService.getInstance()
                            ?.restartSendspinSession()
                    }
                }
                val formatLabel =
                    formatOptions.firstOrNull { it.first == effectivePreferredFormat }?.second
                        ?: formatAutoLabel
                Box(modifier = Modifier.settingsClickable(enabled = enabled) { showFormatDialog = true }) {
                    SettingRow(
                        label = stringResource(R.string.settings_sendspin_stream_format),
                        subLabel = formatLabel
                    ) {
                        SettingsChevronIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (showFormatDialog) {
                    AlertDialog(
                        onDismissRequest = { showFormatDialog = false },
                        modifier = Modifier.widthIn(max = 350.dp),
                        title = {
                            Text(
                                text = stringResource(R.string.settings_sendspin_stream_format),
                                color = getTitleColor(),
                                fontWeight = FontWeight.Bold,
                                fontSize = settingsTitleTextSize()
                            )
                        },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CollapsibleDescriptionText(
                                    text = stringResource(R.string.settings_sendspin_stream_format_desc),
                                    fontSize = settingsBodyTextSize(),
                                    lineHeight = settingsBodyLineHeight(),
                                    color = getSettingsDescriptionColor(),
                                    topPadding = 0.dp,
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 350.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(0.dp)
                                ) {
                                    formatOptions.forEach { (key, label) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    showFormatDialog = false
                                                    coroutineScope.launch {
                                                        viewModel.saveSendspinPreferredFormat(key)
                                                        com.example.ava.services.VoiceSatelliteService
                                                            .getInstance()
                                                            ?.restartSendspinSession()
                                                    }
                                                }
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = key == effectivePreferredFormat,
                                                onClick = {
                                                    showFormatDialog = false
                                                    coroutineScope.launch {
                                                        viewModel.saveSendspinPreferredFormat(key)
                                                        com.example.ava.services.VoiceSatelliteService
                                                            .getInstance()
                                                            ?.restartSendspinSession()
                                                    }
                                                },
                                                colors = RadioButtonDefaults.colors(
                                                    selectedColor = getAccentColor(),
                                                    unselectedColor = Color(0xFF94A3B8)
                                                )
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = label,
                                                fontSize = settingsTitleTextSize(),
                                                color = getTitleColor()
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showFormatDialog = false }) {
                                Text(
                                    text = stringResource(R.string.label_ok),
                                    color = getAccentColor(),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        containerColor = getDialogBackground(),
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                SettingsDivider()

                val syncOffsetMs = uiState?.sendspinSyncOffsetMs ?: 0
                var sliderValue by remember(syncOffsetMs) { mutableFloatStateOf(syncOffsetMs.toFloat()) }

                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    SettingSliderLabelRow(
                        title = stringResource(R.string.settings_sendspin_sync_offset),
                        description = stringResource(R.string.settings_sendspin_sync_offset_desc),
                        badgeText = "${sliderValue.toInt()}ms",
                    )

                    TickSlider(
                        value = sliderValue,
                        onValueChange = { newValue ->
                            sliderValue = (newValue / 10f).toInt() * 10f
                        },
                        onValueChangeFinished = {
                            coroutineScope.launch {
                                viewModel.saveSendspinSyncOffsetMs(sliderValue.toInt())
                            }
                            com.example.ava.services.VoiceSatelliteService.getInstance()?.updateSendspinSyncOffset(sliderValue.toInt())
                        },
                        valueRange = -1000f..1000f,
                        steps = 199,
                        enabled = enabled,
                        colors = SliderDefaults.colors(
                            thumbColor = getAccentColor(),
                            activeTrackColor = getAccentColor(),
                            inactiveTrackColor = getSliderInactiveColor(),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        ),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                SettingsDivider()
                MusicEqualizerEntryRow(enabled = enabled) {
                    navController.navigate(com.example.ava.ui.Screen.SETTINGS_INTERACTION_PLAYBACK_EQ_MA) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }
}

private fun togglePlaybackOverlaySetting(
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    enabling: Boolean,
    onSave: suspend () -> Unit,
) {
    if (enabling && !checkOverlayPermission(context)) {
        requestOverlayPermission(context)
        return
    }
    coroutineScope.launch { onSave() }
}

@Composable
private fun MediaPlayerEntitySetting(
    viewModel: SettingsViewModel,
    uiState: UIState?,
    enabled: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val currentEntity = uiState?.haMediaPlayerEntity ?: ""
    var showDialog by remember { mutableStateOf(false) }
    val haPickerAvailable = com.example.ava.homeassistant.ui.rememberIsHaPickerAvailable()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsClickable(enabled = enabled) { showDialog = true }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.82f)) {
            Text(
                text = stringResource(R.string.settings_ha_media_player),
                fontSize = settingsTitleTextSize(),
                color = getLabelColor(),
                fontWeight = FontWeight.Medium
            )
            CollapsibleDescriptionText(
                text = stringResource(R.string.settings_ha_media_player_desc),
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
        SettingsChevronIcon(tint = com.example.ava.ui.theme.SlateTertiary)
    }

    if (showDialog) {
        if (haPickerAvailable) {
            com.example.ava.homeassistant.ui.HaEntityPickerDialog(
                title = stringResource(R.string.settings_ha_media_player),
                currentValue = currentEntity,
                domainFilter = com.example.ava.homeassistant.entity.HaEntityDomainFilter.MediaPlayer,
                onDismiss = { showDialog = false },
                onConfirm = { newValue ->
                    coroutineScope.launch {
                        viewModel.saveHaMediaPlayerEntity(newValue)
                        restartVoiceSatelliteServiceIfRunning()
                    }
                    showDialog = false
                },
            )
        } else {
            HaMediaPlayerDialog(
                currentValue = currentEntity,
                onDismiss = { showDialog = false },
                onConfirm = { newValue ->
                    coroutineScope.launch {
                        viewModel.saveHaMediaPlayerEntity(newValue)
                        restartVoiceSatelliteServiceIfRunning()
                    }
                    showDialog = false
                }
            )
        }
    }
}

@Composable
private fun SendspinDeviceNameSetting(
    currentName: String,
    enabled: Boolean,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    val defaultName = remember {
        val global = runCatching {
            android.provider.Settings.Global.getString(
                context.contentResolver,
                android.provider.Settings.Global.DEVICE_NAME
            )
        }.getOrNull()
        val label = if (!global.isNullOrBlank()) global
        else {
            val product = android.os.Build.PRODUCT
            val model = android.os.Build.MODEL
            if (product.isNotBlank() && product != model) "$model $product" else model
        }
        "Ava - $label"
    }
    val displayName = currentName.ifEmpty { defaultName }

    Box(modifier = Modifier.settingsClickable(enabled = enabled) { showDialog = true }) {
        SettingRow(
            label = stringResource(R.string.settings_sendspin_device_name),
            subLabel = displayName
        ) {
            SettingsChevronIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showDialog) {
        var textValue by remember { mutableStateOf(currentName.ifEmpty { defaultName }) }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.settings_sendspin_device_name),
                    fontWeight = FontWeight.Bold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor()
                )
            },
            text = {
                Column {
                    CollapsibleDescriptionText(
                        text = stringResource(R.string.settings_sendspin_device_name_desc),
                        fontSize = settingsBodyTextSize(),
                        lineHeight = settingsBodyLineHeight(),
                        color = getSettingsDescriptionColor(),
                        topPadding = 0.dp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        placeholder = {
                            Text(
                                text = defaultName,
                                fontSize = settingsBodyTextSize(),
                                color = getSettingsDescriptionColor()
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = settingsTitleTextSize(),
                            color = getLabelColor()
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFilledFieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = textValue.trim()
                    onSave(if (trimmed == defaultName) "" else trimmed)
                    showDialog = false
                }) {
                    Text(
                        text = stringResource(R.string.label_ok),
                        color = getAccentColor(),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(
                        text = stringResource(R.string.label_cancel),
                        color = getSettingsDescriptionColor()
                    )
                }
            },
            containerColor = getDialogBackground(),
            shape = RoundedCornerShape(20.dp)
        )
    }
}
