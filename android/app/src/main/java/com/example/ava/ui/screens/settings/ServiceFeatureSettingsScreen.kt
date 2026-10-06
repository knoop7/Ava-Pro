package com.example.ava.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SliderDefaults
import com.example.ava.ui.haptic.TickSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.os.Build
import android.widget.Toast
import com.example.ava.services.SatelliteRestartReason
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.ui.AvaToast
import com.example.ava.sensor.ScreenGestureCatalog
import com.example.ava.sensor.ScreenGestureCategory
import com.example.ava.sensor.ScreenTouchSensor
import com.example.ava.settings.PlayerSettings
import com.example.ava.ui.screens.home.MinimalLauncherAppsCache
import com.example.ava.ui.screens.home.MinimalLauncherFillLayout
import com.example.ava.ui.screens.home.MinimalLauncherIconsStore
import com.example.ava.ui.screens.home.MinimalLauncherPageGeometryStore
import com.example.ava.ui.screens.home.MinimalLauncherWidgetsStore
import com.example.ava.ui.screens.home.canExposeMinimalLauncherAppsToHa
import com.example.ava.ui.screens.home.distinctPackagesForPicker
import com.example.ava.ui.screens.home.filterMinimalLauncherApps
import com.example.ava.ui.screens.settings.components.AutoResizeText
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.SettingSliderLabelRow
import com.example.ava.ui.screens.settings.components.SettingsHelpBodyText
import com.example.ava.ui.screens.settings.components.UsageGuideDialog
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsDescriptionTopPadding
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.ui.theme.SlateTertiary as SubLabelColor
import com.example.ava.utils.BatteryOptimizationHelper
import com.example.ava.utils.DeviceCapabilities
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.utils.TouchSoundHelper
import kotlinx.coroutines.launch

enum class ServiceFeatureDestination {
    AutoRestart,
    MinimalLauncher,
    TouchSound,
    ScreenPower,
    ScreenBrightness,
    ScreenTouch,
    ScreenGestureSpatial,
    ScreenGestureDigits,
    ScreenGestureGeometry,
    ForceOrientation,
    Proximity,
}

@Composable
fun ServiceFeatureSettingsScreen(
    navController: NavController,
    destination: ServiceFeatureDestination,
    viewModel: SettingsViewModel = viewModel(),
) {
    when (destination) {
        ServiceFeatureDestination.AutoRestart -> AutoRestartSettingsContent(navController, viewModel)
        ServiceFeatureDestination.MinimalLauncher -> MinimalLauncherSettingsContent(navController, viewModel)
        ServiceFeatureDestination.TouchSound -> TouchSoundSettingsContent(navController)
        ServiceFeatureDestination.ScreenPower -> ScreenPowerSettingsContent(navController, viewModel)
        ServiceFeatureDestination.ScreenBrightness -> ScreenBrightnessSettingsContent(navController, viewModel)
        ServiceFeatureDestination.ScreenTouch -> ScreenTouchSettingsContent(navController, viewModel)
        ServiceFeatureDestination.ScreenGestureSpatial ->
            ScreenGestureTokensContent(navController, viewModel, ScreenGestureCategory.Spatial)
        ServiceFeatureDestination.ScreenGestureDigits ->
            ScreenGestureTokensContent(navController, viewModel, ScreenGestureCategory.Digits)
        ServiceFeatureDestination.ScreenGestureGeometry ->
            ScreenGestureTokensContent(navController, viewModel, ScreenGestureCategory.Geometry)
        ServiceFeatureDestination.ForceOrientation -> ForceOrientationSettingsContent(navController, viewModel)
        ServiceFeatureDestination.Proximity -> ProximitySettingsContent(navController, viewModel)
    }
}

@Composable
private fun AutoRestartSettingsContent(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val playerState by viewModel.playerSettingsState.collectAsStateWithLifecycle(null)
    var showAutoRestartGuide by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasOverlayPermission by remember {
        mutableStateOf(com.example.ava.platform.PlatformCapabilities.canDrawOverlays(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission =
                    com.example.ava.platform.PlatformCapabilities.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_service_keep_running),
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_start_service_on_app_open),
                    subLabel = stringResource(R.string.settings_start_service_on_app_open_desc),
                ) {
                    ModernSwitch(
                        checked = playerState?.startServiceOnAppOpen ?: false,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveStartServiceOnAppOpen(it)
                            }
                        },
                    )
                }

                if (playerState?.startServiceOnAppOpen == true) {
                    SettingsDivider()
                    val delaySeconds = PlayerSettings.clampStartServiceOnAppOpenDelaySeconds(
                        playerState?.startServiceOnAppOpenDelaySeconds
                            ?: PlayerSettings.DEFAULT_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS,
                    )
                    var delaySlider by remember(delaySeconds) { mutableFloatStateOf(delaySeconds.toFloat()) }
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SettingSliderLabelRow(
                            title = stringResource(R.string.settings_start_service_on_app_open_delay),
                            description = stringResource(R.string.settings_start_service_on_app_open_delay_desc),
                            badgeText = "${delaySlider.toInt()}s",
                        )
                        TickSlider(
                            value = delaySlider,
                            onValueChange = { delaySlider = it },
                            onValueChangeFinished = {
                                val snapped = PlayerSettings.clampStartServiceOnAppOpenDelaySeconds(
                                    delaySlider.toInt(),
                                )
                                delaySlider = snapped.toFloat()
                                coroutineScope.launch {
                                    viewModel.saveStartServiceOnAppOpenDelaySeconds(snapped)
                                }
                            },
                            valueRange = PlayerSettings.MIN_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS.toFloat()..
                                PlayerSettings.MAX_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS.toFloat(),
                            // Discrete 1s steps between 3..30 inclusive → 26 interior ticks.
                            steps = PlayerSettings.MAX_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS -
                                PlayerSettings.MIN_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS - 1,
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
            }
        }

        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_auto_restart),
                    subLabel = stringResource(R.string.settings_auto_restart_desc),
                ) {
                    ModernSwitch(
                        checked = playerState?.enableAutoRestart ?: false,
                        onCheckedChange = {
                            if (it && !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) {
                                context.startActivity(
                                    BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context),
                                )
                            }
                            coroutineScope.launch {
                                viewModel.saveAutoRestart(it)
                            }
                        },
                    )
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_crash_self_heal),
                    subLabel = stringResource(R.string.settings_crash_self_heal_desc),
                ) {
                    ModernSwitch(
                        checked = playerState?.enableCrashSelfHeal ?: false,
                        onCheckedChange = { enabled ->
                            if (enabled && !com.example.ava.platform.PlatformCapabilities.canDrawOverlays(context)) {
                                requestOverlayPermission(context)
                            }
                            coroutineScope.launch {
                                viewModel.saveCrashSelfHeal(enabled)
                            }
                        },
                    )
                }

                if (playerState?.enableCrashSelfHeal == true && !hasOverlayPermission) {
                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_crash_self_heal_overlay_title),
                        subLabel = stringResource(R.string.settings_crash_self_heal_overlay_desc),
                        onClick = { requestOverlayPermission(context) },
                    ) {
                        SettingRowHelpMark()
                    }
                }

                SettingsDivider()

                SettingRow(
                    label = stringResource(R.string.settings_usage_guide_title),
                    onClick = { showAutoRestartGuide = true },
                ) {
                    SettingRowHelpMark()
                }
            }
        }

        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_keep_cpu_awake_screen_off),
                    subLabel = stringResource(R.string.settings_keep_cpu_awake_screen_off_desc),
                ) {
                    ModernSwitch(
                        checked = playerState?.keepCpuAwakeOnScreenOff ?: true,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveKeepCpuAwakeOnScreenOff(it)
                            }
                        },
                    )
                }
            }
        }
    }

    if (showAutoRestartGuide) {
        val copyText = buildString {
            appendLine(stringResource(R.string.settings_auto_restart_usage_overview_title))
            appendLine(stringResource(R.string.settings_auto_restart_usage_overview))
            appendLine()
            appendLine(stringResource(R.string.settings_auto_restart_usage_howto_title))
            appendLine(stringResource(R.string.settings_auto_restart_usage_howto))
            appendLine()
            appendLine(stringResource(R.string.settings_auto_restart_usage_how_title))
            appendLine(stringResource(R.string.settings_auto_restart_usage_how))
            appendLine()
            appendLine(stringResource(R.string.settings_auto_restart_usage_troubleshoot_title))
            appendLine(stringResource(R.string.settings_auto_restart_usage_troubleshoot))
        }
        UsageGuideDialog(
            onDismissRequest = { showAutoRestartGuide = false },
            title = stringResource(R.string.settings_usage_guide_title),
            copyText = copyText,
        ) {
            Text(
                text = stringResource(R.string.settings_auto_restart_usage_overview_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_auto_restart_usage_overview),
                fontSize = settingsBodyTextSize(),
                color = getLabelColor(),
                lineHeight = settingsBodyLineHeight(),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.settings_auto_restart_usage_howto_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_auto_restart_usage_howto),
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.settings_auto_restart_usage_how_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_auto_restart_usage_how),
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.settings_auto_restart_usage_troubleshoot_title),
                fontWeight = FontWeight.SemiBold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_auto_restart_usage_troubleshoot),
                fontSize = settingsBodyTextSize(),
                color = getLabelColor(),
                lineHeight = settingsBodyLineHeight(),
            )
        }
    }
}

@Composable
private fun MinimalLauncherSettingsContent(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val playerState by viewModel.playerSettingsState.collectAsStateWithLifecycle(null)
    val enabled = playerState?.enableMinimalLauncher ?: false
    val selectedCount = playerState?.minimalLauncherVisiblePackages?.size ?: 0
    val currentIconPack = playerState?.minimalLauncherIconPack ?: ""
    val currentIconShape = playerState?.minimalLauncherIconShape ?: "squircle"

    val iconPacks by com.example.ava.ui.screens.home.MinimalLauncherIconPackManager
        .availableFlow.collectAsStateWithLifecycle()

    var showIconPackDialog by remember { mutableStateOf(false) }
    var showIconShapeDialog by remember { mutableStateOf(false) }
    var showClearDesktopDialog by remember { mutableStateOf(false) }

    // ---- one-tap "place every app on the desktop" ----
    val appContext = remember { context.applicationContext }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        // Reaching settings directly (not through the launcher) leaves these unloaded,
        // and the fill needs to know what is already placed before it adds anything.
        if (!MinimalLauncherIconsStore.isLoadedFlow.value) MinimalLauncherIconsStore.load(appContext)
        if (!MinimalLauncherWidgetsStore.isLoadedFlow.value) MinimalLauncherWidgetsStore.load(appContext)
        MinimalLauncherAppsCache.load(appContext, appContext.packageName)
    }
    val allApps by MinimalLauncherAppsCache.appsFlow.collectAsStateWithLifecycle()
    val pickerApps = remember(allApps) { allApps.distinctPackagesForPicker() }
    val allLauncherPackages = remember(pickerApps) { pickerApps.map { it.packageName } }
    val selectedPackages = playerState?.minimalLauncherVisiblePackages.orEmpty()
    val canExposeHa = remember(enabled, selectedPackages, allLauncherPackages) {
        enabled && canExposeMinimalLauncherAppsToHa(selectedPackages, allLauncherPackages)
    }
    val haDisplayOn = playerState?.enableMinimalLauncherHaDisplay == true
    LaunchedEffect(enabled, haDisplayOn, selectedPackages, allLauncherPackages) {
        if (!haDisplayOn) return@LaunchedEffect
        if (!canExposeMinimalLauncherAppsToHa(selectedPackages, allLauncherPackages) || !enabled) {
            viewModel.saveMinimalLauncherHaDisplay(false, allLauncherPackages)
        }
    }
    val placedIcons by MinimalLauncherIconsStore.iconsFlow.collectAsStateWithLifecycle()
    // The "choose apps" whitelist decides what the desktop may show, so the fill has to
    // obey it — an empty whitelist means every app.
    val fillApps = remember(allApps, playerState?.minimalLauncherVisiblePackages) {
        filterMinimalLauncherApps(allApps, playerState?.minimalLauncherVisiblePackages.orEmpty())
    }
    val pendingFill = remember(fillApps, placedIcons) {
        MinimalLauncherFillLayout.pendingCount(fillApps)
    }
    val fillSubLabel = when {
        fillApps.isEmpty() -> stringResource(R.string.settings_minimal_launcher_fill_desktop_scanning)
        pendingFill == 0 -> stringResource(R.string.settings_minimal_launcher_fill_desktop_done)
        else -> stringResource(R.string.settings_minimal_launcher_fill_desktop_desc, pendingFill)
    }
    val fillDesktop = {
        val geometry = MinimalLauncherPageGeometryStore.read(appContext)
        val message = if (geometry == null) {
            // Nothing has ever measured a workspace page, so there is no grid to fill.
            context.getString(R.string.settings_minimal_launcher_fill_desktop_no_geometry)
        } else {
            val added = MinimalLauncherFillLayout.fill(
                context = appContext,
                apps = fillApps,
                portrait = geometry.portrait,
                landscape = geometry.landscape,
                showDesktopLabels = playerState?.minimalLauncherShowDesktopLabels ?: true,
            )
            if (added > 0) {
                context.getString(R.string.settings_minimal_launcher_fill_desktop_added, added)
            } else {
                context.getString(R.string.settings_minimal_launcher_fill_desktop_done)
            }
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    val placedIconCount = placedIcons.size
    val clearSubLabel = if (placedIconCount == 0) {
        stringResource(R.string.settings_minimal_launcher_clear_desktop_empty)
    } else {
        stringResource(R.string.settings_minimal_launcher_clear_desktop_desc, placedIconCount)
    }
    val clearDesktopIcons = {
        // Only the icon layer. Widgets, wallpaper, the app whitelist, and the first-run
        // seed flag stay put — emptying the list still writes KEY_ITEMS, so seeding
        // cannot run again on an install that already had a desktop.
        MinimalLauncherIconsStore.replaceAll(appContext, emptyList())
        Toast.makeText(
            context,
            context.getString(R.string.settings_minimal_launcher_clear_desktop_done),
            Toast.LENGTH_SHORT,
        ).show()
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_minimal_launcher),
    ) {
        item {
            SimpleCard {
                val minimalLauncherMasterRow: @Composable () -> Unit = {
                    SettingRow(
                        label = stringResource(R.string.settings_minimal_launcher),
                        subLabel = stringResource(R.string.settings_minimal_launcher_desc),
                    ) {
                        ModernSwitch(
                            checked = enabled,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveMinimalLauncherEnabled(it)
                                }
                            },
                        )
                    }
                }

                if (enabled) {
                    SettingsInsetWell {
                        minimalLauncherMasterRow()

                        SettingsWellDivider()

                        val haSubLabel = when {
                            selectedPackages.isEmpty() ->
                                stringResource(R.string.settings_minimal_launcher_ha_display_need_selection)
                            allLauncherPackages.isNotEmpty() &&
                                allLauncherPackages.all { it in selectedPackages } ->
                                stringResource(R.string.settings_minimal_launcher_ha_display_all_selected)
                            else ->
                                stringResource(R.string.settings_minimal_launcher_ha_display_desc)
                        }
                        SettingRow(
                            label = stringResource(R.string.settings_minimal_launcher_ha_display),
                            subLabel = haSubLabel,
                        ) {
                            ModernSwitch(
                                checked = haDisplayOn && canExposeHa,
                                enabled = canExposeHa,
                                onCheckedChange = { checked ->
                                    coroutineScope.launch {
                                        viewModel.saveMinimalLauncherHaDisplay(checked, allLauncherPackages)
                                    }
                                },
                            )
                        }
                    }
                } else {
                    minimalLauncherMasterRow()
                }

                if (enabled) {
                    SettingsDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_minimal_launcher_apps),
                        subLabel = if (selectedCount > 0) {
                            stringResource(
                                R.string.settings_minimal_launcher_apps_selected_count,
                                selectedCount,
                            )
                        } else {
                            stringResource(R.string.settings_minimal_launcher_apps_all)
                        },
                        onClick = {
                            navController.navigate(com.example.ava.ui.Screen.SETTINGS_SERVICE_MINIMAL_LAUNCHER_APPS) {
                                launchSingleTop = true
                            }
                        },
                    ) {
                        SettingRowChevron()
                    }
                }
            }
        }

        if (enabled) {
            item {
                SettingsSectionLabel(stringResource(R.string.settings_minimal_launcher_section_layout))
            }
            item {
                SimpleCard {
                    SettingRow(
                        label = stringResource(R.string.settings_minimal_launcher_fill_desktop),
                        subLabel = fillSubLabel,
                        onClick = { fillDesktop() },
                    ) {
                        SettingRowChevron()
                    }

                    SettingsDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_minimal_launcher_clear_desktop),
                        subLabel = clearSubLabel,
                        onClick = {
                            if (placedIconCount == 0) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_minimal_launcher_clear_desktop_empty),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                showClearDesktopDialog = true
                            }
                        },
                    ) {
                        SettingRowChevron()
                    }
                }
            }

            item {
                SettingsSectionLabel(stringResource(R.string.settings_minimal_launcher_section_appearance))
            }
            item {
                SimpleCard {
                    SettingRow(
                        label = stringResource(R.string.settings_minimal_launcher_show_desktop_labels),
                        subLabel = stringResource(R.string.settings_minimal_launcher_show_desktop_labels_desc),
                    ) {
                        ModernSwitch(
                            checked = playerState?.minimalLauncherShowDesktopLabels ?: true,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveMinimalLauncherShowDesktopLabels(it)
                                }
                            },
                        )
                    }

                    SettingsDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_minimal_launcher_icon_shape),
                        subLabel = when (currentIconShape) {
                            "circle" -> stringResource(R.string.settings_minimal_launcher_icon_shape_circle)
                            "squircle" -> stringResource(R.string.settings_minimal_launcher_icon_shape_squircle)
                            "rounded_square" -> stringResource(R.string.settings_minimal_launcher_icon_shape_rounded_square)
                            else -> stringResource(R.string.settings_minimal_launcher_icon_shape_system)
                        },
                        onClick = { showIconShapeDialog = true },
                    ) {
                        SettingRowChevron()
                    }

                    SettingsDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_minimal_launcher_icon_pack),
                        subLabel = if (currentIconPack.isBlank()) {
                            stringResource(R.string.settings_minimal_launcher_icon_pack_none)
                        } else {
                            iconPacks.firstOrNull { it.packageName == currentIconPack }
                                ?.label?.toString() ?: currentIconPack
                        },
                        onClick = { showIconPackDialog = true },
                    ) {
                        SettingRowChevron()
                    }
                }
            }
        }
    }

    if (showIconShapeDialog) {
        IconShapePickerDialog(
            current = currentIconShape,
            onSelect = { shape ->
                showIconShapeDialog = false
                coroutineScope.launch { viewModel.saveMinimalLauncherIconShape(shape) }
            },
            onDismiss = { showIconShapeDialog = false },
        )
    }

    if (showIconPackDialog) {
        IconPackPickerDialog(
            packs = iconPacks,
            current = currentIconPack,
            onSelect = { pkg ->
                showIconPackDialog = false
                coroutineScope.launch { viewModel.saveMinimalLauncherIconPack(pkg) }
            },
            onDismiss = { showIconPackDialog = false },
        )
    }

    if (showClearDesktopDialog) {
        AlertDialog(
            onDismissRequest = { showClearDesktopDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.settings_minimal_launcher_clear_desktop_confirm_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor(),
                )
            },
            text = {
                SettingsHelpBodyText(
                    text = stringResource(R.string.settings_minimal_launcher_clear_desktop_confirm),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDesktopDialog = false
                        clearDesktopIcons()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_minimal_launcher_clear_desktop_action),
                        color = Color(0xFFEF4444),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDesktopDialog = false }) {
                    Text(
                        text = stringResource(R.string.label_cancel),
                        color = getAccentColor(),
                    )
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground(),
        )
    }
}

@Composable
private fun IconShapePickerDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val shapes = listOf(
        "circle" to R.string.settings_minimal_launcher_icon_shape_circle,
        "squircle" to R.string.settings_minimal_launcher_icon_shape_squircle,
        "rounded_square" to R.string.settings_minimal_launcher_icon_shape_rounded_square,
        "system" to R.string.settings_minimal_launcher_icon_shape_system,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_minimal_launcher_icon_shape),
                fontWeight = FontWeight.Bold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CollapsibleDescriptionText(
                    text = stringResource(R.string.settings_minimal_launcher_icon_shape_desc),
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                    topPadding = 0.dp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                shapes.forEachIndexed { index, (key, labelRes) ->
                    val selected = current == key
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(key) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { onSelect(key) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = getAccentColor(),
                                unselectedColor = Color(0xFF94A3B8),
                            ),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(labelRes),
                            fontSize = settingsTitleTextSize(),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) getAccentColor() else getLabelColor(),
                        )
                    }
                    if (index < shapes.lastIndex) {
                        SettingsDivider()
                    }
                }
            }
        },
        confirmButton = {},
        shape = RoundedCornerShape(20.dp),
        containerColor = getDialogBackground(),
    )
}

@Composable
private fun IconPackPickerDialog(
    packs: List<com.example.ava.ui.screens.home.IconPackInfo>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_minimal_launcher_icon_pack),
                fontWeight = FontWeight.Bold,
                fontSize = settingsTitleTextSize(),
                color = getTitleColor(),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CollapsibleDescriptionText(
                    text = stringResource(R.string.settings_minimal_launcher_icon_pack_desc),
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                    topPadding = 0.dp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val noneSelected = current.isBlank()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect("") }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = noneSelected,
                        onClick = { onSelect("") },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = getAccentColor(),
                            unselectedColor = Color(0xFF94A3B8),
                        ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_minimal_launcher_icon_pack_none),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = if (noneSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (noneSelected) getAccentColor() else getLabelColor(),
                    )
                }
                if (packs.isEmpty()) {
                    SettingsDivider()
                    Text(
                        text = stringResource(R.string.settings_minimal_launcher_icon_pack_no_packs),
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor(),
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                } else {
                    packs.forEach { pack ->
                        SettingsDivider()
                        val selected = current == pack.packageName
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(pack.packageName) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onSelect(pack.packageName) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = getAccentColor(),
                                    unselectedColor = Color(0xFF94A3B8),
                                ),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val bitmap = remember(pack.packageName) {
                                try {
                                    pack.icon.toBitmap(48, 48)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .size(28.dp),
                                )
                            }
                            Text(
                                text = pack.label.toString(),
                                fontSize = settingsTitleTextSize(),
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) getAccentColor() else getLabelColor(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        shape = RoundedCornerShape(20.dp),
        containerColor = getDialogBackground(),
    )
}

@Composable
private fun TouchSoundSettingsContent(navController: NavController) {
    val context = LocalContext.current
    var touchSoundEnabled by remember { mutableStateOf(TouchSoundHelper.isEnabled(context)) }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_touch_sound),
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_touch_sound),
                    subLabel = stringResource(R.string.settings_touch_sound_desc),
                ) {
                    ModernSwitch(
                        checked = touchSoundEnabled,
                        onCheckedChange = {
                            touchSoundEnabled = it
                            TouchSoundHelper.setEnabled(context, it)
                            if (it) TouchSoundHelper.playClick(context)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenPowerSettingsContent(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_screen_power_control),
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_screen_power_control),
                    subLabel = stringResource(R.string.settings_screen_power_control_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.screenPowerControlHaDisplayEnabled ?: true,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                if (enabled) {
                                    ScreenControlUtils.ensureScreenOffPermission(context)
                                }
                                viewModel.saveScreenPowerControlHaDisplayEnabled(enabled)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenBrightnessSettingsContent(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val hasWriteSettings = com.example.ava.platform.PlatformCapabilities.canWriteSettings(context)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_screen_brightness),
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_screen_brightness),
                    subLabel = if (hasWriteSettings) {
                        stringResource(R.string.settings_screen_brightness_desc)
                    } else {
                        stringResource(R.string.settings_write_settings_permission_required)
                    },
                ) {
                    ModernSwitch(
                        checked = experimentalState?.screenBrightnessEnabled ?: false,
                        onCheckedChange = { enabled ->
                            if (enabled && !hasWriteSettings) {
                                // ACTION_MANAGE_WRITE_SETTINGS is API 23+.
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@ModernSwitch
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                )
                                intent.data = android.net.Uri.parse("package:${context.packageName}")
                                context.startActivity(intent)
                            } else {
                                coroutineScope.launch {
                                    viewModel.saveScreenBrightnessEnabled(enabled)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenTouchSettingsContent(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val mutexMessage = stringResource(R.string.settings_screen_gesture_vertical_mutex)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_screen_touch),
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_screen_touch),
                    subLabel = stringResource(R.string.settings_screen_touch_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.screenTouchSensorEnabled ?: false,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                viewModel.saveScreenTouchSensorEnabled(enabled)
                            }
                        },
                    )
                }

                if (experimentalState?.screenTouchSensorEnabled == true) {
                    SettingsDivider()
                    val savedDelay = ScreenTouchSensor.clampAwayDelaySeconds(
                        experimentalState?.screenTouchAwayDelay
                            ?: ScreenTouchSensor.DEFAULT_AWAY_DELAY_SECONDS,
                    )
                    var delaySlider by remember(savedDelay) { mutableFloatStateOf(savedDelay.toFloat()) }
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SettingSliderLabelRow(
                            title = stringResource(R.string.settings_screen_touch_away_delay),
                            description = stringResource(R.string.settings_screen_touch_away_delay_desc),
                            badgeText = "${delaySlider.toInt()}s",
                        )
                        TickSlider(
                            value = delaySlider,
                            onValueChange = { delaySlider = it },
                            onValueChangeFinished = {
                                val snapped = ScreenTouchSensor.clampAwayDelaySeconds(delaySlider.toInt())
                                delaySlider = snapped.toFloat()
                                coroutineScope.launch {
                                    viewModel.saveScreenTouchAwayDelay(snapped)
                                }
                            },
                            valueRange = ScreenTouchSensor.MIN_AWAY_DELAY_SECONDS.toFloat()..
                                ScreenTouchSensor.MAX_AWAY_DELAY_SECONDS.toFloat(),
                            steps = ScreenTouchSensor.MAX_AWAY_DELAY_SECONDS -
                                ScreenTouchSensor.MIN_AWAY_DELAY_SECONDS - 1,
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
            }
        }

        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_screen_gesture),
                    subLabel = stringResource(R.string.settings_screen_gesture_desc),
                ) {
                    ModernSwitch(
                        checked = experimentalState?.screenGestureEnabled ?: false,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                viewModel.saveScreenGestureEnabled(enabled)
                            }
                        },
                    )
                }

                if (experimentalState?.screenGestureEnabled == true) {
                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_screen_gesture_spatial),
                        subLabel = stringResource(R.string.settings_screen_gesture_spatial_desc),
                    ) {
                        ModernSwitch(
                            checked = experimentalState?.screenGestureSpatialEnabled ?: false,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    if (viewModel.saveScreenGestureSpatialEnabled(enabled)) {
                                        AvaToast.show(context, mutexMessage)
                                    }
                                }
                            },
                        )
                    }
                    if (experimentalState?.screenGestureSpatialEnabled == true) {
                        SettingRow(
                            label = stringResource(R.string.settings_screen_gesture_pick),
                            subLabel = stringResource(R.string.settings_screen_gesture_pick_desc),
                            onClick = {
                                navController.navigate(
                                    com.example.ava.ui.Screen.SETTINGS_SERVICE_SCREEN_GESTURE_SPATIAL,
                                ) { launchSingleTop = true }
                            },
                        ) { SettingRowChevron() }
                    }

                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_screen_gesture_digits),
                        subLabel = stringResource(R.string.settings_screen_gesture_digits_desc),
                    ) {
                        ModernSwitch(
                            checked = experimentalState?.screenGestureDigitsEnabled ?: false,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    if (viewModel.saveScreenGestureDigitsEnabled(enabled)) {
                                        AvaToast.show(context, mutexMessage)
                                    }
                                }
                            },
                        )
                    }
                    if (experimentalState?.screenGestureDigitsEnabled == true) {
                        SettingRow(
                            label = stringResource(R.string.settings_screen_gesture_pick),
                            subLabel = stringResource(R.string.settings_screen_gesture_pick_desc),
                            onClick = {
                                navController.navigate(
                                    com.example.ava.ui.Screen.SETTINGS_SERVICE_SCREEN_GESTURE_DIGITS,
                                ) { launchSingleTop = true }
                            },
                        ) { SettingRowChevron() }
                    }

                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_screen_gesture_geometry),
                        subLabel = stringResource(R.string.settings_screen_gesture_geometry_desc),
                    ) {
                        ModernSwitch(
                            checked = experimentalState?.screenGestureGeometryEnabled ?: false,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    viewModel.saveScreenGestureGeometryEnabled(enabled)
                                }
                            },
                        )
                    }
                    if (experimentalState?.screenGestureGeometryEnabled == true) {
                        SettingRow(
                            label = stringResource(R.string.settings_screen_gesture_pick),
                            subLabel = stringResource(R.string.settings_screen_gesture_pick_desc),
                            onClick = {
                                navController.navigate(
                                    com.example.ava.ui.Screen.SETTINGS_SERVICE_SCREEN_GESTURE_GEOMETRY,
                                ) { launchSingleTop = true }
                            },
                        ) { SettingRowChevron() }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenGestureTokensContent(
    navController: NavController,
    viewModel: SettingsViewModel,
    category: ScreenGestureCategory,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val mutexMessage = stringResource(R.string.settings_screen_gesture_vertical_mutex)
    val titleRes = when (category) {
        ScreenGestureCategory.Spatial -> R.string.settings_screen_gesture_spatial
        ScreenGestureCategory.Digits -> R.string.settings_screen_gesture_digits
        ScreenGestureCategory.Geometry -> R.string.settings_screen_gesture_geometry
    }
    val selected = when (category) {
        ScreenGestureCategory.Spatial -> ScreenGestureCatalog.resolvedCategoryTokens(
            experimentalState?.screenGestureSpatialTokens.orEmpty(),
            category,
        )
        ScreenGestureCategory.Digits -> ScreenGestureCatalog.resolvedCategoryTokens(
            experimentalState?.screenGestureDigitTokens.orEmpty(),
            category,
        )
        ScreenGestureCategory.Geometry -> ScreenGestureCatalog.resolvedCategoryTokens(
            experimentalState?.screenGestureGeometryTokens.orEmpty(),
            category,
        )
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(titleRes),
    ) {
        item {
            SimpleCard {
                ScreenGestureCatalog.tokensFor(category).forEachIndexed { index, token ->
                    if (index > 0) SettingsDivider()
                    SettingRow(label = stringResource(token.labelRes)) {
                        ModernSwitch(
                            checked = token.id in selected,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    if (viewModel.saveScreenGestureToken(category, token.id, enabled)) {
                                        AvaToast.show(context, mutexMessage)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ForceOrientationSettingsContent(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val hasOverlayPermission = com.example.ava.platform.PlatformCapabilities.canDrawOverlays(context)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_force_orientation),
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_force_orientation),
                    subLabel = if (hasOverlayPermission) {
                        stringResource(R.string.settings_force_orientation_desc)
                    } else {
                        stringResource(R.string.settings_overlay_permission_required)
                    },
                ) {
                    ModernSwitch(
                        checked = (experimentalState?.forceOrientationEnabled == true) && hasOverlayPermission,
                        onCheckedChange = { enabled ->
                            if (enabled && !hasOverlayPermission) {
                                requestOverlayPermission(context)
                            } else {
                                coroutineScope.launch {
                                    viewModel.saveForceOrientationEnabled(enabled)
                                    // initForceOrientation() only runs during satellite start().
                                    restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
                                }
                            }
                        },
                    )
                }

                if (experimentalState?.forceOrientationEnabled == true) {
                    SettingsDivider()
                    val currentMode = experimentalState?.forceOrientationMode ?: "portrait"
                    val portraitLabel = stringResource(R.string.settings_orientation_portrait)
                    val landscapeLabel = stringResource(R.string.settings_orientation_landscape)
                    val autoLabel = stringResource(R.string.settings_orientation_auto)
                    val modes = listOf(
                        "portrait" to portraitLabel,
                        "landscape" to landscapeLabel,
                        "auto" to autoLabel,
                    )

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_force_orientation_mode),
                                fontSize = settingsTitleTextSize(),
                                fontWeight = FontWeight.Medium,
                                color = getTitleColor(),
                            )
                            CollapsibleDescriptionText(
                                text = stringResource(R.string.settings_force_orientation_mode_desc),
                                fontSize = settingsBodyTextSize(),
                                lineHeight = settingsBodyLineHeight(),
                                color = getSettingsDescriptionColor(),
                                topPadding = settingsDescriptionTopPadding(),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            modes.forEach { (mode, label) ->
                                val isSelected = currentMode == mode
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            coroutineScope.launch {
                                                viewModel.saveForceOrientationMode(mode)
                                                restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
                                            }
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) getAccentColor() else getSliderInactiveColor(),
                                ) {
                                    AutoResizeText(
                                        text = label,
                                        fontSize = settingsTitleTextSize(),
                                        minFontSize = 10.sp,
                                        maxLines = 1,
                                        softWrap = true,
                                        overflow = TextOverflow.Clip,
                                        color = if (isSelected) Color.White else getSettingsDescriptionColor(),
                                        style = TextStyle(textAlign = TextAlign.Center),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 12.dp),
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
private fun ProximitySettingsContent(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val hasProximitySensor = DeviceCapabilities.hasProximitySensor(context)
    val deviceNotSupportedText = stringResource(R.string.settings_device_not_supported)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_proximity_sensor),
    ) {
        item {
            SimpleCard {
                val proximityOn = hasProximitySensor &&
                    (experimentalState?.proximitySensorEnabled == true)
                val proximityMasterRow: @Composable () -> Unit = {
                    SettingRow(
                        label = stringResource(R.string.settings_proximity_sensor),
                        subLabel = if (hasProximitySensor) {
                            stringResource(R.string.settings_proximity_sensor_desc)
                        } else {
                            deviceNotSupportedText
                        },
                    ) {
                        ModernSwitch(
                            checked = if (hasProximitySensor) {
                                experimentalState?.proximitySensorEnabled ?: false
                            } else {
                                false
                            },
                            enabled = hasProximitySensor,
                            onCheckedChange = {
                                if (hasProximitySensor) {
                                    coroutineScope.launch {
                                        viewModel.saveProximitySensorEnabled(it)
                                        // initProximitySensor() only runs during satellite start().
                                        restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
                                    }
                                }
                            },
                        )
                    }
                }

                if (proximityOn) {
                    SettingsInsetWell {
                        proximityMasterRow()

                        SettingsWellDivider()
                        SettingRow(
                            label = stringResource(R.string.settings_proximity_send_to_hass),
                            subLabel = stringResource(R.string.settings_proximity_send_to_hass_desc),
                        ) {
                            ModernSwitch(
                                checked = experimentalState?.proximitySendToHass ?: false,
                                onCheckedChange = {
                                    coroutineScope.launch {
                                        viewModel.saveProximitySendToHass(it)
                                        // Distance entity registration + publish loop are init-time.
                                        restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
                                    }
                                },
                            )
                        }
                    }
                } else {
                    proximityMasterRow()
                }

                if (proximityOn) {
                    if (experimentalState?.proximitySendToHass == true) {
                        SettingsDivider()
                        val proximityHassUpdateInterval = experimentalState?.proximityHassUpdateInterval ?: 20
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            SettingSliderLabelRow(
                                title = stringResource(R.string.settings_proximity_hass_update_interval),
                                description = stringResource(R.string.settings_proximity_hass_update_interval_desc),
                                badgeText = "${proximityHassUpdateInterval}s",
                            )
                            TickSlider(
                                value = proximityHassUpdateInterval.toFloat(),
                                onValueChange = {
                                    coroutineScope.launch {
                                        viewModel.saveProximityHassUpdateInterval(it.toInt())
                                    }
                                },
                                valueRange = 5f..120f,
                                steps = 22,
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

                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_proximity_wake_screen),
                        subLabel = stringResource(R.string.settings_proximity_wake_screen_desc),
                    ) {
                        ModernSwitch(
                            checked = experimentalState?.proximityWakeScreen ?: true,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveProximityWakeScreen(it)
                                    restartVoiceSatelliteServiceIfRunning()
                                }
                            },
                        )
                    }

                    val proximityWakeEnabled = experimentalState?.proximityWakeScreen != false
                    if (proximityWakeEnabled) {
                        SettingsDivider()
                        val proximityAwayDelay = experimentalState?.proximityAwayDelay ?: 30
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            SettingSliderLabelRow(
                                title = stringResource(R.string.settings_proximity_away_delay),
                                description = stringResource(R.string.settings_proximity_away_delay_desc),
                                badgeText = "${proximityAwayDelay}s",
                            )
                            TickSlider(
                                value = proximityAwayDelay.toFloat(),
                                onValueChange = {
                                    coroutineScope.launch {
                                        viewModel.saveProximityAwayDelay(it.toInt())
                                    }
                                },
                                valueRange = 10f..120f,
                                steps = 10,
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

                    SettingsDivider()
                    val autoUnlockAvailable = proximityWakeEnabled
                    SettingRow(
                        label = stringResource(R.string.settings_auto_unlock),
                        subLabel = stringResource(R.string.settings_auto_unlock_desc),
                    ) {
                        ModernSwitch(
                            checked = autoUnlockAvailable && (experimentalState?.proximityAutoUnlock == true),
                            enabled = autoUnlockAvailable,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    viewModel.saveProximityAutoUnlock(it)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
