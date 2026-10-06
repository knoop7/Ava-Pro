package com.example.ava.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.SliderDefaults
import com.example.ava.ui.haptic.TickSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.mods.ModCameraStreamBridge
import com.example.ava.settings.ExperimentalSettings
import com.example.ava.ui.Screen
import com.example.ava.ui.screens.home.HomeSidebarActions
import com.example.ava.ui.screens.settings.components.*
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ScreenControlUtils
import kotlinx.coroutines.launch
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon

enum class ScreensaverSettingsDestination {
    Root,
    Content,
    Behavior,
}

@Composable
fun ScreensaverSettingsScreen(
    navController: NavController,
    startDestination: ScreensaverSettingsDestination = ScreensaverSettingsDestination.Root,
    viewModel: SettingsViewModel = viewModel(),
) {
    when (startDestination) {
        ScreensaverSettingsDestination.Root ->
            ScreensaverSettingsRootScreen(navController, viewModel)
        ScreensaverSettingsDestination.Content ->
            ScreensaverContentSettingsScreen(navController, viewModel)
        ScreensaverSettingsDestination.Behavior ->
            ScreensaverBehaviorSettingsScreen(navController, viewModel)
    }
}

@Composable
private fun ScreensaverSettingsNavRow(
    label: String,
    subLabel: String,
    onClick: () -> Unit,
) {
    SettingRow(
        label = label,
        subLabel = subLabel,
        onClick = onClick,
    ) {
        SettingsChevronIcon(tint = Color(0xFF94A3B8))
    }
}

/**
 * Idle-seconds editor. The sibling "allow 0" switch immediately writes 0; this
 * row stays clickable so the user can still change the number. 0 is valid in
 * the dialog only while the stored value is already 0 (gate open). Confirming
 * 10–3600 writes that number and the switch turns off — one field, no extra flag.
 */
@Composable
private fun ScreensaverTimeoutSetting(
    timeoutSeconds: Int?,
    enabled: Boolean,
    onConfirm: (Int) -> Unit,
) {
    val allowZero = timeoutSeconds == 0
    val rangeError = if (allowZero) {
        stringResource(R.string.settings_screensaver_timeout_zero_range)
    } else {
        stringResource(R.string.validation_range, 10, 3600)
    }
    DialogSettingItem(
        name = stringResource(R.string.settings_screensaver_timeout),
        description = stringResource(R.string.settings_screensaver_timeout_desc),
        value = timeoutSeconds?.toString() ?: "",
        enabled = enabled,
    ) {
        TextDialog(
            title = stringResource(R.string.settings_screensaver_timeout),
            value = timeoutSeconds?.toString() ?: "",
            validation = { input ->
                val parsed = input.toIntOrNull()
                when {
                    parsed != null && parsed in 10..3600 -> null
                    parsed == 0 && allowZero -> null
                    else -> rangeError
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            onConfirmRequest = { input -> input.toIntOrNull()?.let(onConfirm) },
        )
    }
}

/** L2: first card always expanded; other sections are entries only when master switch is on. */
@Composable
private fun ScreensaverSettingsRootScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val screensaverState by viewModel.screensaverSettingsState.collectAsStateWithLifecycle(null)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val ready = screensaverState != null
    val masterOn = screensaverState?.enabled == true

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_group_screensaver),
    ) {
        item {
            SimpleCard {
                val screensaverMasterRow: @Composable () -> Unit = {
                    SettingRow(
                        label = stringResource(R.string.settings_screensaver_enable),
                        subLabel = stringResource(R.string.settings_screensaver_enable_desc),
                    ) {
                        ModernSwitch(
                            checked = masterOn,
                            enabled = ready,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveScreensaverEnabled(it)
                                    kotlinx.coroutines.delay(100)
                                    restartVoiceSatelliteServiceIfRunning()
                                }
                            },
                        )
                    }
                }

                if (!masterOn) {
                    // Single row — no HA twin visible, so no well.
                    screensaverMasterRow()
                }

                if (masterOn) {
                    SettingsInsetWell {
                        screensaverMasterRow()

                        SettingsWellDivider()

                        SettingRow(
                            label = stringResource(R.string.settings_screensaver_ha_display),
                            subLabel = stringResource(R.string.settings_screensaver_ha_display_desc),
                        ) {
                            ModernSwitch(
                                checked = screensaverState?.enableHaDisplay ?: false,
                                enabled = ready,
                                onCheckedChange = {
                                    coroutineScope.launch {
                                        viewModel.saveScreensaverHaDisplay(it)
                                        kotlinx.coroutines.delay(100)
                                        restartVoiceSatelliteServiceIfRunning()
                                    }
                                },
                            )
                        }
                    }

                    // Two adjacent wells — divider between them.
                    SettingsDivider()

                    SettingsInsetWell {
                        ScreensaverTimeoutSetting(
                            timeoutSeconds = screensaverState?.timeoutSeconds,
                            enabled = ready,
                            onConfirm = {
                                coroutineScope.launch { viewModel.saveScreensaverTimeout(it) }
                            },
                        )

                        SettingsWellDivider()

                        SettingRow(
                            label = stringResource(R.string.settings_screensaver_timeout_visible),
                            subLabel = stringResource(R.string.settings_screensaver_timeout_visible_desc),
                        ) {
                            ModernSwitch(
                                checked = screensaverState?.screensaverTimeoutVisible ?: false,
                                enabled = ready,
                                onCheckedChange = {
                                    coroutineScope.launch {
                                        viewModel.saveScreensaverTimeoutVisible(it)
                                        kotlinx.coroutines.delay(100)
                                        restartVoiceSatelliteServiceIfRunning()
                                    }
                                },
                            )
                        }

                        SettingsWellDivider()

                        // "Allow 0" edits the same timeout number, so it lives in this group.
                        SettingRow(
                            label = stringResource(R.string.settings_screensaver_timeout_zero_switch),
                            subLabel = stringResource(R.string.settings_screensaver_timeout_zero_switch_desc),
                        ) {
                            ModernSwitch(
                                checked = screensaverState?.timeoutSeconds == 0,
                                enabled = ready,
                                onCheckedChange = { on ->
                                    coroutineScope.launch {
                                        // Write the number itself, not a parallel boolean.
                                        // HA/fleet injecting 0 then shows as on; injecting 10–3600 shows as off.
                                        viewModel.saveScreensaverTimeout(if (on) 0 else 300)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (masterOn) {
            item {
                SimpleCard {
                    ScreensaverSettingsNavRow(
                        label = stringResource(R.string.settings_screensaver_entry_content_title),
                        subLabel = stringResource(R.string.settings_screensaver_entry_content_desc),
                        onClick = {
                            navController.navigate(Screen.SETTINGS_SCREENSAVER_CONTENT) {
                                launchSingleTop = true
                            }
                        },
                    )
                    SettingsDivider()
                    ScreensaverSettingsNavRow(
                        label = stringResource(R.string.settings_screensaver_entry_behavior_title),
                        subLabel = stringResource(R.string.settings_screensaver_entry_behavior_desc),
                        onClick = {
                            navController.navigate(Screen.SETTINGS_SCREENSAVER_BEHAVIOR) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
        }
    }
}

/** L3: wallpaper / URL content source. */
@Composable
private fun ScreensaverContentSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val screensaverState by viewModel.screensaverSettingsState.collectAsStateWithLifecycle(null)
    val coroutineScope = rememberCoroutineScope()
    val ready = screensaverState != null
    val masterOn = screensaverState?.enabled == true

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_screensaver_entry_content_title),
    ) {
        if (!masterOn) {
            item {
                SimpleCard {
                    SettingRow(
                        label = stringResource(R.string.settings_screensaver_enable),
                        subLabel = stringResource(R.string.settings_screensaver_master_off_hint),
                    ) {}
                }
            }
            return@SettingsDetailScreen
        }

        // Card 1: Dawn magazine
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_dawn_wallpaper),
                    subLabel = stringResource(R.string.settings_dawn_wallpaper_desc),
                ) {
                    ModernSwitch(
                        checked = screensaverState?.dawnWallpaperEnabled ?: false,
                        enabled = ready,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveDawnWallpaperEnabled(it)
                                kotlinx.coroutines.delay(100)
                                restartVoiceSatelliteServiceIfRunning()
                            }
                        },
                    )
                }

                if (screensaverState?.dawnWallpaperEnabled == true) {
                    SettingsDivider()

                    TextSetting(
                        name = stringResource(R.string.settings_dawn_wallpaper_source),
                        description = stringResource(R.string.settings_dawn_wallpaper_source_desc),
                        dialogHint = stringResource(R.string.settings_dawn_wallpaper_source_hint),
                        value = screensaverState?.dawnWallpaperSourceUrl ?: "",
                        enabled = ready,
                        onConfirmRequest = {
                            coroutineScope.launch {
                                viewModel.saveDawnWallpaperSourceUrl(it)
                            }
                        },
                    )

                    SettingsDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_dawn_iso_time),
                        subLabel = stringResource(R.string.settings_dawn_iso_time_desc),
                    ) {
                        ModernSwitch(
                            checked = screensaverState?.dawnIsoTimeEnabled ?: false,
                            enabled = ready,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveDawnIsoTimeEnabled(it)
                                }
                            },
                        )
                    }

                    SettingsDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_dawn_merge_weather_icons),
                        subLabel = stringResource(R.string.settings_dawn_merge_weather_icons_desc),
                    ) {
                        ModernSwitch(
                            checked = screensaverState?.dawnMergeWeatherIconsEnabled ?: false,
                            enabled = ready,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveDawnMergeWeatherIconsEnabled(it)
                                }
                            },
                        )
                    }

                    DawnEntitySlotsSettingsSection(
                        enabled = ready,
                        coroutineScope = coroutineScope,
                        onEditSlot = { index ->
                            navController.navigate("dawn_entity_slot_edit/$index") {
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
        }

        // Card 2: custom screensaver URL
        item {
            SimpleCard {
                SettingsInsetWell {
                    TextSetting(
                        name = stringResource(R.string.settings_screensaver_url),
                        description = stringResource(R.string.settings_screensaver_url_desc),
                        dialogHint = stringResource(R.string.settings_screensaver_url_hint),
                        value = screensaverState?.screensaverUrl ?: "",
                        enabled = ready && !(screensaverState?.dawnWallpaperEnabled ?: false),
                        onConfirmRequest = {
                            coroutineScope.launch {
                                viewModel.saveScreensaverUrl(it)
                                kotlinx.coroutines.delay(100)
                                restartVoiceSatelliteServiceIfRunning()
                            }
                        },
                    )

                    SettingsWellDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_screensaver_url_visible),
                        subLabel = stringResource(R.string.settings_screensaver_url_visible_desc),
                    ) {
                        ModernSwitch(
                            checked = screensaverState?.screensaverUrlVisible ?: false,
                            enabled = ready,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveScreensaverUrlVisible(it)
                                    kotlinx.coroutines.delay(100)
                                    restartVoiceSatelliteServiceIfRunning()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** L3: two cards — display behavior (power/burn-in) vs wake/pause. */
@Composable
private fun ScreensaverBehaviorSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val screensaverState by viewModel.screensaverSettingsState.collectAsStateWithLifecycle(null)
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val ready = screensaverState != null
    val masterOn = screensaverState?.enabled == true
    val hasRoot = remember { RootUtils.isRootAvailable() }

    var cameraStreamModActive by remember {
        mutableStateOf(ModCameraStreamBridge.isActive(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraStreamModActive = ModCameraStreamBridge.isActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(cameraStreamModActive) {
        if (cameraStreamModActive) {
            ModCameraStreamBridge.applyHostPolicySuspend(context)
        }
    }

    // Hidden while Camera Stream mod owns the camera — person-wake needs core video frames.
    val showPersonWake = !cameraStreamModActive && (
        screensaverState?.personWakeEnabled == true ||
            HomeSidebarActions.isVideoRecordingAvailable(
                context,
                experimentalState ?: ExperimentalSettings(),
            )
        )

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_screensaver_entry_behavior_title),
    ) {
        if (!masterOn) {
            item {
                SimpleCard {
                    SettingRow(
                        label = stringResource(R.string.settings_screensaver_enable),
                        subLabel = stringResource(R.string.settings_screensaver_master_off_hint),
                    ) {}
                }
            }
            return@SettingsDetailScreen
        }

        // Card 1: behavior while screensaver is showing (HA switch sync, power, burn-in).
        item {
            SimpleCard {
                if (screensaverState?.enableHaDisplay == true) {
                    SettingRow(
                        label = stringResource(R.string.settings_screensaver_ha_switch_two_way),
                        subLabel = stringResource(R.string.settings_screensaver_ha_switch_two_way_desc),
                    ) {
                        ModernSwitch(
                            checked = screensaverState?.haSwitchTwoWayEnabled ?: false,
                            enabled = ready,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveScreensaverHaSwitchTwoWay(it)
                                }
                            },
                        )
                    }

                    SettingsDivider()
                }

                SettingRow(
                    label = stringResource(R.string.settings_screensaver_dark_off),
                    subLabel = stringResource(R.string.settings_screensaver_dark_off_desc),
                ) {
                    ModernSwitch(
                        checked = screensaverState?.darkOffEnabled ?: false,
                        enabled = ready,
                        onCheckedChange = {
                            coroutineScope.launch {
                                if (!it || ScreenControlUtils.ensureScreenOffPermission(context)) {
                                    viewModel.saveScreensaverDarkOff(it)
                                }
                            }
                        },
                    )
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_screensaver_pixel_shift),
                    subLabel = stringResource(R.string.settings_screensaver_pixel_shift_desc),
                ) {
                    ModernSwitch(
                        checked = screensaverState?.pixelShiftEnabled ?: false,
                        enabled = ready,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveScreensaverPixelShift(it)
                            }
                        },
                    )
                }

                SettingsDivider()
                Spacer(modifier = Modifier.height(8.dp))

                SettingRow(
                    label = stringResource(R.string.settings_screensaver_smart_aod),
                    subLabel = stringResource(R.string.settings_screensaver_smart_aod_desc),
                ) {
                    ModernSwitch(
                        checked = screensaverState?.smartAodEnabled ?: false,
                        enabled = ready,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveScreensaverSmartAodEnabled(it)
                            }
                        },
                    )
                }

                if (screensaverState?.smartAodEnabled == true) {
                    SettingsDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    val maskPercent =
                        (screensaverState?.smartAodMaskPercent ?: 100).coerceIn(5, 100)
                    var maskSlider by remember(maskPercent) { mutableFloatStateOf(maskPercent.toFloat()) }
                    SettingSliderLabelRow(
                        title = stringResource(R.string.settings_screensaver_smart_aod_mask),
                        description = stringResource(R.string.settings_screensaver_smart_aod_mask_desc),
                        badgeText = "${maskSlider.toInt()}%",
                    )
                    TickSlider(
                        value = maskSlider,
                        onValueChange = { maskSlider = it },
                        onValueChangeFinished = {
                            val snapped = maskSlider.toInt().coerceIn(5, 100)
                            maskSlider = snapped.toFloat()
                            coroutineScope.launch {
                                viewModel.saveScreensaverSmartAodMaskPercent(snapped)
                            }
                        },
                        enabled = ready,
                        valueRange = 5f..100f,
                        steps = 94,
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

                if (hasRoot) {
                    SettingsDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_screensaver_smart_cpu_throttle),
                        subLabel = stringResource(R.string.settings_screensaver_smart_cpu_throttle_desc),
                    ) {
                        ModernSwitch(
                            checked = screensaverState?.smartCpuThrottleEnabled ?: false,
                            enabled = ready,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveScreensaverSmartCpuThrottle(it)
                                }
                            },
                        )
                    }
                }
            }
        }

        // Card 2: wake / pause (exit or suppress screensaver).
        item {
            SimpleCard {
                if (showPersonWake) {
                    SettingRow(
                        label = stringResource(R.string.settings_screensaver_person_wake),
                        subLabel = stringResource(R.string.settings_screensaver_person_wake_desc),
                    ) {
                        ModernSwitch(
                            checked = screensaverState?.personWakeEnabled ?: false,
                            enabled = ready,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveScreensaverPersonWake(it)
                                }
                            },
                        )
                    }

                    SettingsDivider()
                }

                SettingRow(
                    label = stringResource(R.string.settings_screensaver_keep_on_overlays),
                    subLabel = stringResource(R.string.settings_screensaver_keep_on_overlays_desc),
                ) {
                    ModernSwitch(
                        checked = screensaverState?.keepOnOverlays ?: true,
                        enabled = ready,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveScreensaverKeepOnOverlays(it)
                            }
                        },
                    )
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_screensaver_background_pause),
                    subLabel = stringResource(R.string.settings_screensaver_background_pause_desc),
                ) {
                    ModernSwitch(
                        checked = screensaverState?.backgroundPauseEnabled ?: false,
                        enabled = ready,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveScreensaverBackgroundPause(it)
                            }
                        },
                    )
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_screensaver_motion_on),
                    subLabel = stringResource(R.string.settings_screensaver_motion_on_desc),
                ) {
                    ModernSwitch(
                        checked = screensaverState?.motionOnEnabled ?: false,
                        enabled = ready,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveScreensaverMotionOn(it)
                            }
                        },
                    )
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_screensaver_show_after_screen_on),
                    subLabel = stringResource(R.string.settings_screensaver_show_after_screen_on_desc),
                ) {
                    ModernSwitch(
                        checked = screensaverState?.showAfterScreenOn ?: false,
                        enabled = ready,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveScreensaverShowAfterScreenOn(it)
                            }
                        },
                    )
                }
            }
        }
    }
}
