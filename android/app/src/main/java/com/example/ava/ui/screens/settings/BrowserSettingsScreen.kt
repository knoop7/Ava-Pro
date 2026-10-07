package com.example.ava.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.settings.BrowserPowerMode
import com.example.ava.settings.BrowserSettings
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.SidebarItemKey
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.VoiceChannelSettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.settings.voiceChannelSettingsStore
import com.example.ava.ui.Screen
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.webcompat.WebViewRuntime
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.HorizontalDissolveText
import com.example.ava.ui.screens.settings.components.IntSetting
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.ui.screens.settings.components.DropdownSelectSetting
import com.example.ava.ui.screens.settings.components.SelectSetting
import com.example.ava.ui.screens.settings.components.UsageGuideDialog
import com.example.ava.ui.screens.settings.components.rememberSettingsTextScale
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsDescriptionTopPadding
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsCaptionTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.webcompat.WebViewProductionFeatures
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.AvaToast

enum class BrowserSettingsDestination {
    Root,
    Ha,
    Display,
    Split,
    Touch,
    Compat,
    Sidebar,
    Steward,
}

enum class RenderMode(val displayName: String) {
    HARDWARE("Hardware"),
    SOFTWARE("Software"),
}

private suspend fun saveHaRemoteUrlEnabled(
    context: android.content.Context,
    store: BrowserSettingsStore,
    enabled: Boolean,
) {
    store.setHaRemoteUrlEnabled(enabled)
    if (enabled) {
        SidebarSettingsStore(context.sidebarSettingsStore).offerHomeEntry(SidebarItemKey.Browser)
    }
    restartVoiceSatelliteServiceIfRunning()
}

@Composable
fun BrowserSettingsScreen(
    navController: NavController,
    startDestination: BrowserSettingsDestination = BrowserSettingsDestination.Root,
) {
    when (startDestination) {
        BrowserSettingsDestination.Root -> BrowserSettingsRootScreen(navController)
        BrowserSettingsDestination.Ha -> BrowserSettingsHaScreen(navController)
        BrowserSettingsDestination.Display -> BrowserSettingsDisplayScreen(navController)
        BrowserSettingsDestination.Split -> BrowserSettingsSplitScreen(navController)
        BrowserSettingsDestination.Touch -> BrowserSettingsTouchScreen(navController)
        BrowserSettingsDestination.Compat -> BrowserSettingsCompatScreen(navController)
        BrowserSettingsDestination.Sidebar -> BrowserSettingsSidebarScreen(navController)
        BrowserSettingsDestination.Steward -> BrowserSettingsStewardScreen(navController)
    }
}

@Composable
private fun BrowserSettingsNavRow(
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

@Composable
private fun BrowserSettingsRootScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browserSettingsStore = remember { BrowserSettingsStore(context) }
    val haRemoteUrlEnabled by browserSettingsStore.haRemoteUrlEnabled.collectAsState(initial = true)
    val enableBrowserDisplay by browserSettingsStore.enableBrowserDisplay.collectAsState(initial = false)
    val advancedControlEnabled by browserSettingsStore.advancedControlEnabled.collectAsState(initial = false)
    val browserPowerMode by browserSettingsStore.browserPowerMode.collectAsState(
        initial = BrowserPowerMode.ADAPTIVE,
    )
    val syncBrowserUrlEnabled by browserSettingsStore.syncBrowserUrlEnabled.collectAsState(initial = true)
    val secureContextProxyEnabled by browserSettingsStore.secureContextProxyEnabled.collectAsState(initial = false)
    var showAdvancedHelpDialog by remember { mutableStateOf(false) }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_group_browser),
    ) {
        // HA integration: expanded on the root page by default (aligned with device services "configure on the first card")
        item {
            SimpleCard {
                BrowserHaIntegrationContent(
                    haRemoteUrlEnabled = haRemoteUrlEnabled,
                    enableBrowserDisplay = enableBrowserDisplay,
                    advancedControlEnabled = advancedControlEnabled,
                    browserPowerMode = browserPowerMode,
                    syncBrowserUrlEnabled = syncBrowserUrlEnabled,
                    secureContextProxyEnabled = secureContextProxyEnabled,
                    onHaRemoteUrlEnabledChange = { enabled ->
                        val hasOverlayPermission = checkOverlayPermission(context)
                        if (enabled && !hasOverlayPermission) {
                            requestOverlayPermission(context)
                        } else {
                            scope.launch {
                                saveHaRemoteUrlEnabled(context, browserSettingsStore, enabled)
                            }
                        }
                    },
                    onEnableBrowserDisplayChange = { enabled ->
                        scope.launch {
                            browserSettingsStore.setEnableBrowserDisplay(enabled)
                            if (!enabled) {
                                com.example.ava.services.WebViewService.destroy(context)
                            }
                            restartVoiceSatelliteServiceIfRunning()
                        }
                    },
                    onAdvancedControlEnabledChange = { enabled ->
                        scope.launch {
                            browserSettingsStore.setAdvancedControlEnabled(enabled)
                            restartVoiceSatelliteServiceIfRunning()
                        }
                        if (enabled) {
                            showAdvancedHelpDialog = true
                        }
                    },
                    onBrowserPowerModeChange = { mode ->
                        scope.launch { browserSettingsStore.setBrowserPowerMode(mode) }
                    },
                    onSyncBrowserUrlEnabledChange = { enabled ->
                        scope.launch {
                            browserSettingsStore.setSyncBrowserUrlEnabled(enabled)
                        }
                    },
                    onSecureContextProxyEnabledChange = { enabled ->
                        scope.launch {
                            browserSettingsStore.setSecureContextProxyEnabled(enabled)
                        }
                    },
                    onOpenHelp = { showAdvancedHelpDialog = true },
                )
            }
        }

        // Other entries: shown only after the master switch is on (same as the screensaver entry card)
        if (haRemoteUrlEnabled) {
            item {
                SimpleCard {
                    BrowserSettingsNavRow(
                        label = stringResource(R.string.settings_browser_entry_display_title),
                        subLabel = stringResource(R.string.settings_browser_entry_display_desc),
                        onClick = {
                            navController.navigate(Screen.SETTINGS_BROWSER_DISPLAY) { launchSingleTop = true }
                        },
                    )
                    SettingsDivider()
                    BrowserSettingsNavRow(
                        label = stringResource(R.string.settings_browser_entry_touch_title),
                        subLabel = stringResource(R.string.settings_browser_entry_touch_desc),
                        onClick = {
                            navController.navigate(Screen.SETTINGS_BROWSER_TOUCH) { launchSingleTop = true }
                        },
                    )
                    SettingsDivider()
                    BrowserSettingsNavRow(
                        label = stringResource(R.string.settings_browser_entry_sidebar_title),
                        subLabel = stringResource(R.string.settings_browser_entry_sidebar_desc),
                        onClick = {
                            navController.navigate(Screen.SETTINGS_BROWSER_SIDEBAR) { launchSingleTop = true }
                        },
                    )
                    SettingsDivider()
                    BrowserSettingsNavRow(
                        label = stringResource(R.string.settings_browser_entry_steward_title),
                        subLabel = stringResource(R.string.settings_browser_entry_steward_desc),
                        onClick = {
                            navController.navigate(Screen.SETTINGS_BROWSER_STEWARD) { launchSingleTop = true }
                        },
                    )
                    SettingsDivider()
                    BrowserSettingsNavRow(
                        label = stringResource(R.string.settings_browser_entry_compat_title),
                        subLabel = stringResource(R.string.settings_browser_entry_compat_desc),
                        onClick = {
                            navController.navigate(Screen.SETTINGS_BROWSER_COMPAT) { launchSingleTop = true }
                        },
                    )
                }
            }
        }
    }

    if (showAdvancedHelpDialog) {
        BrowserAdvancedControlHelpDialog(onDismiss = { showAdvancedHelpDialog = false })
    }
}

@Composable
private fun BrowserHaIntegrationContent(
    haRemoteUrlEnabled: Boolean,
    enableBrowserDisplay: Boolean,
    advancedControlEnabled: Boolean,
    browserPowerMode: BrowserPowerMode,
    syncBrowserUrlEnabled: Boolean,
    secureContextProxyEnabled: Boolean,
    onHaRemoteUrlEnabledChange: (Boolean) -> Unit,
    onEnableBrowserDisplayChange: (Boolean) -> Unit,
    onAdvancedControlEnabledChange: (Boolean) -> Unit,
    onBrowserPowerModeChange: (BrowserPowerMode) -> Unit,
    onSyncBrowserUrlEnabledChange: (Boolean) -> Unit,
    onSecureContextProxyEnabledChange: (Boolean) -> Unit,
    onOpenHelp: () -> Unit,
) {
    val context = LocalContext.current
    val hasOverlayPermission = checkOverlayPermission(context)
    val voiceChannelStore = remember { VoiceChannelSettingsStore(context.voiceChannelSettingsStore) }
    val voiceChannelEnabled by voiceChannelStore.enabled.collectAsStateWithLifecycle(true)
    val powerHighLabel = stringResource(R.string.settings_browser_power_mode_high)
    val powerAdaptiveLabel = stringResource(R.string.settings_browser_power_mode_adaptive)
    val powerLowLabel = stringResource(R.string.settings_browser_power_mode_low)
    val powerHighDesc = stringResource(R.string.settings_browser_power_mode_high_desc)
    val powerAdaptiveDesc = stringResource(R.string.settings_browser_power_mode_adaptive_desc)
    val powerLowDesc = stringResource(R.string.settings_browser_power_mode_low_desc)
    // HTTP page boost is only relevant when native voice is off.
    val showHttpPageBoost = !voiceChannelEnabled

    LaunchedEffect(voiceChannelEnabled, secureContextProxyEnabled) {
        if (voiceChannelEnabled && secureContextProxyEnabled) {
            onSecureContextProxyEnabledChange(false)
        }
    }

    val remoteUrlMasterRow: @Composable () -> Unit = {
        SettingRow(
            label = stringResource(R.string.settings_browser_ha_remote_url),
            subLabel = if (hasOverlayPermission) {
                stringResource(R.string.settings_browser_ha_remote_url_desc)
            } else {
                stringResource(R.string.settings_overlay_permission_required)
            },
        ) {
            ModernSwitch(
                checked = haRemoteUrlEnabled,
                onCheckedChange = onHaRemoteUrlEnabledChange,
            )
        }
    }

    if (!haRemoteUrlEnabled) {
        remoteUrlMasterRow()
        return
    }

    SettingsInsetWell {
        remoteUrlMasterRow()

        SettingsWellDivider()
        SettingRow(
            label = stringResource(R.string.settings_browser_ha_display_switch),
            subLabel = stringResource(R.string.settings_browser_ha_display_switch_desc),
        ) {
            ModernSwitch(
                checked = enableBrowserDisplay,
                onCheckedChange = onEnableBrowserDisplayChange,
            )
        }
    }

    SettingsDivider()
    BrowserPowerModeSettingRow(
        selected = browserPowerMode,
        highLabel = powerHighLabel,
        adaptiveLabel = powerAdaptiveLabel,
        lowLabel = powerLowLabel,
        highDesc = powerHighDesc,
        adaptiveDesc = powerAdaptiveDesc,
        lowDesc = powerLowDesc,
        onSelect = onBrowserPowerModeChange,
    )

    if (showHttpPageBoost) {
        SettingsDivider()
        SettingRow(
            label = stringResource(R.string.settings_browser_http_page_boost),
            subLabel = stringResource(R.string.settings_browser_http_page_boost_desc),
        ) {
            ModernSwitch(
                checked = secureContextProxyEnabled,
                onCheckedChange = onSecureContextProxyEnabledChange,
            )
        }
    }

    SettingsDivider()
    SettingRow(
        label = stringResource(R.string.settings_browser_sync_url),
        subLabel = stringResource(R.string.settings_browser_sync_url_desc),
    ) {
        ModernSwitch(
            checked = syncBrowserUrlEnabled,
            onCheckedChange = onSyncBrowserUrlEnabledChange,
        )
    }

    SettingsDivider()
    SettingRow(
        label = stringResource(R.string.settings_browser_advanced_control),
        subLabel = stringResource(R.string.settings_browser_advanced_control_desc),
    ) {
        ModernSwitch(
            checked = advancedControlEnabled,
            onCheckedChange = onAdvancedControlEnabledChange,
        )
    }

    SettingsDivider()
    SettingRow(
        label = stringResource(R.string.settings_usage_guide_title),
        onClick = onOpenHelp,
    ) {
        SettingRowHelpMark()
    }
}

@Composable
private fun BrowserPowerModeSettingRow(
    selected: BrowserPowerMode,
    highLabel: String,
    adaptiveLabel: String,
    lowLabel: String,
    highDesc: String,
    adaptiveDesc: String,
    lowDesc: String,
    onSelect: (BrowserPowerMode) -> Unit,
) {
    val selectedDesc = when (selected) {
        BrowserPowerMode.HIGH -> highDesc
        BrowserPowerMode.ADAPTIVE -> adaptiveDesc
        BrowserPowerMode.LOW -> lowDesc
    }
    DropdownSelectSetting(
        name = stringResource(R.string.settings_browser_power_mode),
        description = selectedDesc,
        selected = selected,
        items = listOf(
            BrowserPowerMode.HIGH,
            BrowserPowerMode.ADAPTIVE,
            BrowserPowerMode.LOW,
        ),
        value = { mode ->
            when (mode) {
                BrowserPowerMode.HIGH -> highLabel
                BrowserPowerMode.ADAPTIVE -> adaptiveLabel
                BrowserPowerMode.LOW -> lowLabel
            }
        },
        onSelect = onSelect,
    )
}

@Composable
private fun BrowserSettingsHaScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browserSettingsStore = remember { BrowserSettingsStore(context) }
    val haRemoteUrlEnabled by browserSettingsStore.haRemoteUrlEnabled.collectAsState(initial = true)
    val enableBrowserDisplay by browserSettingsStore.enableBrowserDisplay.collectAsState(initial = false)
    val advancedControlEnabled by browserSettingsStore.advancedControlEnabled.collectAsState(initial = false)
    val browserPowerMode by browserSettingsStore.browserPowerMode.collectAsState(
        initial = BrowserPowerMode.ADAPTIVE,
    )
    val syncBrowserUrlEnabled by browserSettingsStore.syncBrowserUrlEnabled.collectAsState(initial = true)
    val secureContextProxyEnabled by browserSettingsStore.secureContextProxyEnabled.collectAsState(initial = false)
    var showAdvancedHelpDialog by remember { mutableStateOf(false) }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_browser_entry_ha_title),
    ) {
        item {
            SimpleCard {
                BrowserHaIntegrationContent(
                    haRemoteUrlEnabled = haRemoteUrlEnabled,
                    enableBrowserDisplay = enableBrowserDisplay,
                    advancedControlEnabled = advancedControlEnabled,
                    browserPowerMode = browserPowerMode,
                    syncBrowserUrlEnabled = syncBrowserUrlEnabled,
                    secureContextProxyEnabled = secureContextProxyEnabled,
                    onHaRemoteUrlEnabledChange = { enabled ->
                        val hasOverlayPermission = checkOverlayPermission(context)
                        if (enabled && !hasOverlayPermission) {
                            requestOverlayPermission(context)
                        } else {
                            scope.launch {
                                saveHaRemoteUrlEnabled(context, browserSettingsStore, enabled)
                            }
                        }
                    },
                    onEnableBrowserDisplayChange = { enabled ->
                        scope.launch {
                            browserSettingsStore.setEnableBrowserDisplay(enabled)
                            if (!enabled) {
                                com.example.ava.services.WebViewService.destroy(context)
                            }
                            restartVoiceSatelliteServiceIfRunning()
                        }
                    },
                    onAdvancedControlEnabledChange = { enabled ->
                        scope.launch {
                            browserSettingsStore.setAdvancedControlEnabled(enabled)
                            restartVoiceSatelliteServiceIfRunning()
                        }
                        if (enabled) {
                            showAdvancedHelpDialog = true
                        }
                    },
                    onBrowserPowerModeChange = { mode ->
                        scope.launch { browserSettingsStore.setBrowserPowerMode(mode) }
                    },
                    onSyncBrowserUrlEnabledChange = { enabled ->
                        scope.launch {
                            browserSettingsStore.setSyncBrowserUrlEnabled(enabled)
                        }
                    },
                    onSecureContextProxyEnabledChange = { enabled ->
                        scope.launch {
                            browserSettingsStore.setSecureContextProxyEnabled(enabled)
                        }
                    },
                    onOpenHelp = { showAdvancedHelpDialog = true },
                )
            }
        }
    }

    if (showAdvancedHelpDialog) {
        BrowserAdvancedControlHelpDialog(onDismiss = { showAdvancedHelpDialog = false })
    }
}

@Composable
private fun BrowserAdvancedControlHelpDialog(onDismiss: () -> Unit) {
    val helpTitle = stringResource(R.string.settings_usage_guide_title)
    val helpDesc = stringResource(R.string.advanced_control_help_desc)
    val callTitle = stringResource(R.string.advanced_control_call_title)
    val callContent = stringResource(R.string.advanced_control_call_content)
    val commandsTitle = stringResource(R.string.advanced_control_commands_title)
    val commandsContent = stringResource(R.string.advanced_control_commands_content)
    val aiUrl = "https://gemini.google.com/gem/ee3cb858f9d0"
    val helpCopyText = buildString {
        appendLine(helpDesc)
        appendLine()
        appendLine(callTitle)
        appendLine(callContent)
        appendLine()
        appendLine(commandsTitle)
        appendLine(commandsContent)
        appendLine()
        appendLine(stringResource(R.string.advanced_control_ai_link_title))
        appendLine(aiUrl)
    }

    UsageGuideDialog(
        onDismissRequest = onDismiss,
        title = helpTitle,
        copyText = helpCopyText,
    ) {
        Text(
            text = helpDesc,
            fontSize = settingsBodyTextSize(),
            color = getLabelColor(),
            lineHeight = settingsBodyLineHeight(),
        )
        Text(
            text = callTitle,
            fontWeight = FontWeight.SemiBold,
            fontSize = settingsTitleTextSize(),
            color = getTitleColor(),
        )
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                text = callContent,
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
            )
        }
        Text(
            text = commandsTitle,
            fontWeight = FontWeight.SemiBold,
            fontSize = settingsTitleTextSize(),
            color = getTitleColor(),
        )
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                text = commandsContent,
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
            )
        }
        Text(
            text = stringResource(R.string.advanced_control_ai_link_title),
            fontWeight = FontWeight.SemiBold,
            fontSize = settingsTitleTextSize(),
            color = getTitleColor(),
        )
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                text = aiUrl,
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
            )
        }
    }
}

@Composable
private fun BrowserSettingsDisplayScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browserSettingsStore = remember { BrowserSettingsStore(context) }
    val keepScreenOnEnabled by browserSettingsStore.keepScreenOnEnabled.collectAsState(initial = false)
    val pullRefreshEnabled by browserSettingsStore.pullRefreshEnabled.collectAsState(initial = true)
    val initialScale by browserSettingsStore.initialScale.collectAsState(initial = 0)
    val fontSize by browserSettingsStore.fontSize.collectAsState(initial = 100)
    val showScaleSliderInHa by browserSettingsStore.showScaleSliderInHa.collectAsState(initial = false)
    val hardwareAcceleration by browserSettingsStore.hardwareAcceleration.collectAsState(initial = true)
    val followSystemDarkMode by browserSettingsStore.followSystemDarkMode.collectAsState(initial = true)
    val browserEngine by browserSettingsStore.browserEngine.collectAsState(initial = 0)
    val isGeckoEngine = browserEngine == com.example.ava.webcompat.BrowserEngine.GECKO
    val renderMode = if (hardwareAcceleration) RenderMode.HARDWARE else RenderMode.SOFTWARE
    val hardwareLabel = stringResource(R.string.settings_browser_render_hardware)
    val softwareLabel = stringResource(R.string.settings_browser_render_software)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_browser_entry_display_title),
    ) {
        item { SettingsSectionLabel(stringResource(R.string.settings_browser_section_display_layout)) }
        item {
            SimpleCard {
                SettingsInsetWell {
                    IntSetting(
                        name = stringResource(R.string.settings_browser_initial_scale),
                        description = stringResource(R.string.settings_browser_initial_scale_desc),
                        value = initialScale,
                        enabled = true,
                        validation = { value ->
                            if (value != null && value in 0..500) null
                            else context.getString(R.string.validation_range, 0, 500)
                        },
                        onConfirmRequest = { value ->
                            value?.let { scope.launch { browserSettingsStore.setInitialScale(it) } }
                        },
                    )

                    SettingsWellDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_browser_scale_slider_entity),
                        subLabel = stringResource(R.string.settings_browser_scale_slider_entity_desc),
                    ) {
                        ModernSwitch(
                            checked = showScaleSliderInHa,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    browserSettingsStore.setShowScaleSliderInHa(enabled)
                                    restartVoiceSatelliteServiceIfRunning()
                                }
                            },
                        )
                    }
                }

                SettingsDivider()
                IntSetting(
                    name = stringResource(R.string.settings_browser_font_size),
                    description = stringResource(R.string.settings_browser_font_size_desc),
                    value = fontSize,
                    enabled = true,
                    validation = { value ->
                        if (value != null && value in 50..300) null
                        else context.getString(R.string.validation_range, 50, 300)
                    },
                    onConfirmRequest = { value ->
                        value?.let { scope.launch { browserSettingsStore.setFontSize(it) } }
                    },
                )

                if (!isGeckoEngine) {
                    SettingsDivider()
                    BrowserSettingsNavRow(
                        label = stringResource(R.string.settings_browser_split_view),
                        subLabel = stringResource(R.string.settings_browser_split_view_entry_desc),
                        onClick = {
                            navController.navigate(Screen.SETTINGS_BROWSER_SPLIT) { launchSingleTop = true }
                        },
                    )
                }
            }
        }

        item { SettingsSectionLabel(stringResource(R.string.settings_browser_section_display_behavior)) }
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_browser_pull_refresh),
                    subLabel = stringResource(R.string.settings_browser_pull_refresh_desc),
                ) {
                    ModernSwitch(
                        checked = pullRefreshEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { browserSettingsStore.setPullRefreshEnabled(enabled) }
                        },
                    )
                }

                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_browser_keep_screen_on),
                    subLabel = stringResource(R.string.settings_browser_keep_screen_on_desc),
                ) {
                    ModernSwitch(
                        checked = keepScreenOnEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { browserSettingsStore.setKeepScreenOnEnabled(enabled) }
                        },
                    )
                }

                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_browser_follow_dark_mode),
                    subLabel = stringResource(R.string.settings_browser_follow_dark_mode_desc),
                ) {
                    ModernSwitch(
                        checked = followSystemDarkMode,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                browserSettingsStore.setFollowSystemDarkMode(enabled)
                                com.example.ava.services.WebViewService.refreshDarkMode(context)
                            }
                        },
                    )
                }

                SettingsDivider()
                SelectSetting(
                    name = stringResource(R.string.settings_browser_render_mode),
                    description = stringResource(R.string.settings_browser_render_mode_desc),
                    selected = renderMode,
                    items = RenderMode.entries.toList(),
                    enabled = true,
                    key = { it.name },
                    value = {
                        when (it) {
                            RenderMode.HARDWARE -> hardwareLabel
                            RenderMode.SOFTWARE -> softwareLabel
                            null -> ""
                        }
                    },
                    onConfirmRequest = { mode ->
                        scope.launch {
                            browserSettingsStore.setHardwareAcceleration(mode == RenderMode.HARDWARE)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SplitRatioDigitField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val fieldBg = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFF1F5F9)
    val textColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF334155)

    BasicTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(1)
            onValueChange(digits)
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.Medium,
            color = textColor,
            textAlign = TextAlign.Center,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        cursorBrush = SolidColor(getAccentColor()),
        modifier = Modifier
            .size(width = 44.dp, height = 40.dp)
            .background(fieldBg, RoundedCornerShape(10.dp))
            .padding(horizontal = 4.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.Center) { inner() }
        },
    )
}

@Composable
internal fun SplitRatioEditor(
    left: Int,
    right: Int,
    onCommit: (Int, Int) -> Unit,
) {
    var leftText by remember(left) { mutableStateOf(left.toString()) }
    var rightText by remember(right) { mutableStateOf(right.toString()) }
    val colonColor = getSettingsDescriptionColor()

    fun tryCommit(nextLeft: String, nextRight: String) {
        val l = nextLeft.toIntOrNull()
        val r = nextRight.toIntOrNull()
        if (l != null && r != null && l in 1..9 && r in 1..9) {
            if (l != left || r != right) onCommit(l, r)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SplitRatioDigitField(
            value = leftText,
            onValueChange = { next ->
                leftText = next
                tryCommit(next, rightText)
            },
        )
        Text(
            text = ":",
            color = colonColor,
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        SplitRatioDigitField(
            value = rightText,
            onValueChange = { next ->
                rightText = next
                tryCommit(leftText, next)
            },
        )
    }
}

@Composable
private fun BrowserSettingsSplitScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browserSettingsStore = remember { BrowserSettingsStore(context) }
    val splitViewEnabled by browserSettingsStore.splitViewEnabled.collectAsState(initial = false)
    val splitViewRatioLeft by browserSettingsStore.splitViewRatioLeft.collectAsState(initial = 5)
    val splitViewRatioRight by browserSettingsStore.splitViewRatioRight.collectAsState(initial = 5)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_browser_split_view),
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_browser_split_view),
                    subLabel = stringResource(R.string.settings_browser_split_view_desc),
                ) {
                    ModernSwitch(
                        checked = splitViewEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                browserSettingsStore.setSplitViewEnabled(enabled)
                                restartVoiceSatelliteServiceIfRunning()
                            }
                        },
                    )
                }
            }
        }

        if (splitViewEnabled) {
            item { SettingsSectionLabel(stringResource(R.string.settings_browser_section_split_layout)) }
            item {
                SimpleCard {
                    SettingRow(
                        label = stringResource(R.string.settings_browser_split_view_ratio),
                        subLabel = stringResource(R.string.settings_browser_split_view_ratio_desc),
                    ) {
                        SplitRatioEditor(
                            left = splitViewRatioLeft,
                            right = splitViewRatioRight,
                            onCommit = { left, right ->
                                scope.launch { browserSettingsStore.setSplitViewRatio(left, right) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserSettingsTouchScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browserSettingsStore = remember { BrowserSettingsStore(context) }
    val touchEnabled by browserSettingsStore.touchEnabled.collectAsState(initial = true)
    val dragEnabled by browserSettingsStore.dragEnabled.collectAsState(initial = true)
    val backKeyHideEnabled by browserSettingsStore.backKeyHideEnabled.collectAsState(initial = true)
    val gestureNavigationEnabled by browserSettingsStore.gestureNavigationEnabled.collectAsState(initial = false)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_browser_entry_touch_title),
    ) {
        item { SettingsSectionLabel(stringResource(R.string.settings_browser_section_touch)) }
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_browser_touch),
                    subLabel = stringResource(R.string.settings_browser_touch_desc),
                ) {
                    ModernSwitch(
                        checked = touchEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { browserSettingsStore.setTouchEnabled(enabled) }
                        },
                    )
                }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_browser_drag),
                    subLabel = stringResource(R.string.settings_browser_drag_desc),
                ) {
                    ModernSwitch(
                        checked = dragEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { browserSettingsStore.setDragEnabled(enabled) }
                        },
                    )
                }
            }
        }

        item { SettingsSectionLabel(stringResource(R.string.settings_browser_section_nav)) }
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_browser_gesture_navigation),
                    subLabel = stringResource(R.string.settings_browser_gesture_navigation_desc),
                ) {
                    ModernSwitch(
                        checked = gestureNavigationEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                browserSettingsStore.setGestureNavigationEnabled(enabled)
                                com.example.ava.services.WebViewService.destroy(context)
                            }
                        },
                    )
                }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_browser_back_key_hide),
                    subLabel = stringResource(R.string.settings_browser_back_key_hide_desc),
                ) {
                    ModernSwitch(
                        checked = backKeyHideEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { browserSettingsStore.setBackKeyHideEnabled(enabled) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserSettingsStewardScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browserSettingsStore = remember { BrowserSettingsStore(context) }
    val wsStewardEnabled by browserSettingsStore.wsStewardEnabled.collectAsState(initial = true)
    val wsStewardStreamEnabled by browserSettingsStore.wsStewardStreamEnabled
        .collectAsState(initial = true)
    val wsStewardChunkedRenderingEnabled by browserSettingsStore.wsStewardChunkedRenderingEnabled
        .collectAsState(initial = true)
    val wsStewardDormantQuietEnabled by browserSettingsStore.wsStewardDormantQuietEnabled
        .collectAsState(initial = true)
    val wsStewardEntityTrimEnabled by browserSettingsStore.wsStewardEntityTrimEnabled
        .collectAsState(initial = true)
    val wvProdMemoryFeaturesEnabled by browserSettingsStore.wvProdMemoryFeaturesEnabled
        .collectAsState(initial = false)
    val wvProdFrameThrottleFeaturesEnabled by browserSettingsStore.wvProdFrameThrottleFeaturesEnabled
        .collectAsState(initial = false)
    // Engine version gates honest reporting: old engines ignore unknown flags, and
    // chunked rendering below Chromium 85 has nothing to run on.
    val chromeMajor = remember { WebViewRuntime.cachedInfo(context).majorVersion }
    var engineFlagState by remember {
        mutableStateOf(WebViewProductionFeatures.engineFlagState(chromeMajor))
    }
    val storedOpaqueLines by browserSettingsStore.wsStewardOpaqueCardLines
        .collectAsState(initial = "")
    val storedOpaqueCustom by browserSettingsStore.wsStewardOpaqueCardLinesCustom
        .collectAsState(initial = false)
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val fieldBg = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFF1F5F9)
    val textColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF334155)
    // Prefab until the user edits; empty after clear is intentional and must stick.
    var opaqueDraft by remember {
        mutableStateOf(
            BrowserSettings.opaqueCardLinesForDisplay(storedOpaqueLines, storedOpaqueCustom),
        )
    }
    var opaqueTouched by remember { mutableStateOf(false) }
    LaunchedEffect(storedOpaqueLines, storedOpaqueCustom) {
        if (opaqueTouched) return@LaunchedEffect
        val next = BrowserSettings.opaqueCardLinesForDisplay(
            storedOpaqueLines,
            storedOpaqueCustom,
        )
        if (next != opaqueDraft) opaqueDraft = next
    }
    LaunchedEffect(opaqueDraft, opaqueTouched) {
        if (!opaqueTouched) return@LaunchedEffect
        delay(450)
        if (opaqueDraft != storedOpaqueLines || !storedOpaqueCustom) {
            browserSettingsStore.setWsStewardOpaqueCardLines(opaqueDraft, custom = true)
        }
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_browser_entry_steward_title),
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_browser_ws_steward),
                    subLabel = stringResource(R.string.settings_browser_ws_steward_desc),
                ) {
                    ModernSwitch(
                        checked = wsStewardEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { browserSettingsStore.setWsStewardEnabled(enabled) }
                        },
                    )
                }
            }
        }
        if (wsStewardEnabled) {
            item { SettingsSectionLabel(stringResource(R.string.settings_browser_section_steward_features)) }
            item {
                SimpleCard {
                    SettingRow(
                        label = stringResource(R.string.settings_browser_ws_steward_stream),
                        subLabel = stringResource(R.string.settings_browser_ws_steward_stream_desc),
                    ) {
                        ModernSwitch(
                            checked = wsStewardStreamEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    browserSettingsStore.setWsStewardStreamEnabled(enabled)
                                }
                            },
                        )
                    }
                    if (wsStewardStreamEnabled) {
                        SettingsDivider()
                        SettingRow(
                            label = stringResource(R.string.settings_browser_ws_steward_entity_trim),
                            subLabel = stringResource(R.string.settings_browser_ws_steward_entity_trim_desc),
                        ) {
                            ModernSwitch(
                                checked = wsStewardEntityTrimEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        browserSettingsStore.setWsStewardEntityTrimEnabled(enabled)
                                    }
                                },
                            )
                        }
                        if (wsStewardEntityTrimEnabled) {
                            SettingsDivider()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_browser_ws_steward_opaque_cards),
                                    fontSize = settingsTitleTextSize(),
                                    fontWeight = FontWeight.Medium,
                                    color = textColor,
                                )
                                CollapsibleDescriptionText(
                                    text = stringResource(R.string.settings_browser_ws_steward_opaque_cards_desc),
                                    fontSize = settingsBodyTextSize(),
                                    lineHeight = settingsBodyLineHeight(),
                                    color = getSettingsDescriptionColor(),
                                    topPadding = settingsDescriptionTopPadding(),
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                BasicTextField(
                                    value = opaqueDraft,
                                    onValueChange = {
                                        opaqueDraft = it
                                        opaqueTouched = true
                                    },
                                    textStyle = TextStyle(
                                        fontSize = 13.sp,
                                        color = textColor,
                                        lineHeight = 18.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    ),
                                    cursorBrush = SolidColor(getAccentColor()),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(500.dp)
                                        .background(fieldBg, RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                )
                            }
                        }
                    }
                    SettingsDivider()
                    // Chromium <85 has no content-visibility at all; 85–97 falls back to a
                    // fixed placeholder height (page-side CSS.supports grade). Say so here
                    // instead of offering a switch that silently does nothing or breaks.
                    val chunkEngineUnsupported = chromeMajor in 1..84
                    val chunkSubLabel = when {
                        chunkEngineUnsupported ->
                            stringResource(R.string.settings_browser_ws_steward_chunked_unsupported)
                        chromeMajor in 85..97 ->
                            stringResource(R.string.settings_browser_ws_steward_chunked_desc) +
                                " " +
                                stringResource(R.string.settings_browser_ws_steward_chunked_legacy)
                        else -> stringResource(R.string.settings_browser_ws_steward_chunked_desc)
                    }
                    SettingRow(
                        label = stringResource(R.string.settings_browser_ws_steward_chunked),
                        subLabel = chunkSubLabel,
                    ) {
                        ModernSwitch(
                            checked = wsStewardChunkedRenderingEnabled && !chunkEngineUnsupported,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    browserSettingsStore.setWsStewardChunkedRenderingEnabled(enabled)
                                }
                            },
                            enabled = !chunkEngineUnsupported,
                        )
                    }
                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_browser_ws_steward_dormant_quiet),
                        subLabel = stringResource(R.string.settings_browser_ws_steward_dormant_quiet_desc),
                    ) {
                        ModernSwitch(
                            checked = wsStewardDormantQuietEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    browserSettingsStore.setWsStewardDormantQuietEnabled(enabled)
                                }
                            },
                        )
                    }
                }
            }
            item { SettingsSectionLabel(stringResource(R.string.settings_browser_section_wv_prod)) }
            item {
                SimpleCard {
                    SettingRow(
                        label = stringResource(R.string.settings_browser_wv_prod_memory),
                        subLabel = stringResource(R.string.settings_browser_wv_prod_memory_desc),
                    ) {
                        ModernSwitch(
                            checked = wvProdMemoryFeaturesEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    browserSettingsStore.setWvProdMemoryFeaturesEnabled(enabled)
                                    engineFlagState =
                                        WebViewProductionFeatures.engineFlagState(chromeMajor)
                                }
                            },
                        )
                    }
                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_browser_wv_prod_frame),
                        subLabel = stringResource(R.string.settings_browser_wv_prod_frame_desc),
                    ) {
                        ModernSwitch(
                            checked = wvProdFrameThrottleFeaturesEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    browserSettingsStore.setWvProdFrameThrottleFeaturesEnabled(enabled)
                                    engineFlagState =
                                        WebViewProductionFeatures.engineFlagState(chromeMajor)
                                }
                            },
                        )
                    }
                    engineFlagStateText(engineFlagState)?.let { note ->
                        SettingsDivider()
                        Text(
                            text = note,
                            fontSize = settingsBodyTextSize(),
                            lineHeight = settingsBodyLineHeight(),
                            color = getSettingsDescriptionColor(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Chromium reads its command line only when the WebView provider boots, and the file lives in
 * `/data/local/tmp`, which most retail devices refuse to write. The store writes it on every flip;
 * this is how the screen says which half of the switch actually landed.
 */
@Composable
private fun engineFlagStateText(state: WebViewProductionFeatures.EngineFlagState): String? =
    when (state) {
        WebViewProductionFeatures.EngineFlagState.ACTIVE ->
            stringResource(R.string.settings_browser_wv_prod_engine_active)
        WebViewProductionFeatures.EngineFlagState.RESTART_REQUIRED ->
            stringResource(R.string.settings_browser_wv_prod_engine_restart)
        WebViewProductionFeatures.EngineFlagState.UNAVAILABLE ->
            stringResource(R.string.settings_browser_wv_prod_engine_unavailable)
        WebViewProductionFeatures.EngineFlagState.ENGINE_TOO_OLD ->
            stringResource(R.string.settings_browser_wv_prod_engine_too_old)
        WebViewProductionFeatures.EngineFlagState.UNKNOWN -> null
    }

@Composable
private fun BrowserSettingsSidebarScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browserSettingsStore = remember { BrowserSettingsStore(context) }
    val enableBrowserSidebar by browserSettingsStore.enableBrowserSidebar.collectAsState(initial = true)
    val showBrowserHaKiosk by browserSettingsStore.showBrowserHaKiosk.collectAsState(initial = true)
    val showBrowserPageZoom by browserSettingsStore.showBrowserPageZoom.collectAsState(initial = true)
    val showBrowserWebConsole by browserSettingsStore.showBrowserWebConsole.collectAsState(initial = true)
    val showBrowserClearCache by browserSettingsStore.showBrowserClearCache.collectAsState(initial = true)
    val showBrowserUserAgent by browserSettingsStore.showBrowserUserAgent.collectAsState(initial = true)
    val showBrowserRemoteUrl by browserSettingsStore.showBrowserRemoteUrl.collectAsState(initial = true)
    val tampermonkeyEnabled by browserSettingsStore.tampermonkeyEnabled.collectAsState(initial = false)
    val browserEngine by browserSettingsStore.browserEngine.collectAsState(initial = 0)
    val tampermonkeySupported = remember(browserEngine) {
        com.example.ava.webcompat.BrowserEngine.effectiveEngine(context, browserEngine) !=
            com.example.ava.webcompat.BrowserEngine.GECKO
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_browser_entry_sidebar_title),
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_browser_sidebar_enable),
                    subLabel = stringResource(R.string.settings_browser_sidebar_enable_desc),
                ) {
                    ModernSwitch(
                        checked = enableBrowserSidebar,
                        onCheckedChange = { enabled ->
                            scope.launch { browserSettingsStore.setEnableBrowserSidebar(enabled) }
                        },
                    )
                }
            }
        }

        if (enableBrowserSidebar) {
            item { SettingsSectionLabel(stringResource(R.string.settings_browser_section_sidebar_tools)) }
            item {
                SimpleCard {
                    SettingRow(
                        label = stringResource(R.string.settings_browser_ha_remote_url),
                        subLabel = stringResource(R.string.settings_browser_sidebar_show_remote_url_desc),
                    ) {
                        ModernSwitch(
                            checked = showBrowserRemoteUrl,
                            onCheckedChange = { enabled ->
                                scope.launch { browserSettingsStore.setShowBrowserRemoteUrl(enabled) }
                            },
                        )
                    }
                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_browser_initial_scale),
                        subLabel = stringResource(R.string.settings_browser_sidebar_show_page_zoom_desc),
                    ) {
                        ModernSwitch(
                            checked = showBrowserPageZoom,
                            onCheckedChange = { enabled ->
                                scope.launch { browserSettingsStore.setShowBrowserPageZoom(enabled) }
                            },
                        )
                    }
                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_browser_sidebar_show_ha_kiosk),
                        subLabel = stringResource(R.string.settings_browser_sidebar_show_ha_kiosk_desc),
                    ) {
                        ModernSwitch(
                            checked = showBrowserHaKiosk,
                            onCheckedChange = { enabled ->
                                scope.launch { browserSettingsStore.setShowBrowserHaKiosk(enabled) }
                            },
                        )
                    }
                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_browser_sidebar_show_web_console),
                        subLabel = stringResource(R.string.settings_browser_sidebar_show_web_console_desc),
                    ) {
                        ModernSwitch(
                            checked = showBrowserWebConsole,
                            onCheckedChange = { enabled ->
                                scope.launch { browserSettingsStore.setShowBrowserWebConsole(enabled) }
                            },
                        )
                    }
                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_browser_useragent),
                        subLabel = stringResource(R.string.settings_browser_sidebar_show_useragent_desc),
                    ) {
                        ModernSwitch(
                            checked = showBrowserUserAgent,
                            onCheckedChange = { enabled ->
                                scope.launch { browserSettingsStore.setShowBrowserUserAgent(enabled) }
                            },
                        )
                    }
                    if (tampermonkeySupported) {
                        SettingsDivider()
                        SettingRow(
                            label = stringResource(R.string.settings_browser_tampermonkey),
                            subLabel = stringResource(R.string.settings_browser_tampermonkey_desc),
                        ) {
                            ModernSwitch(
                                checked = tampermonkeyEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch { browserSettingsStore.setTampermonkeyEnabled(enabled) }
                                },
                            )
                        }
                    }
                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_browser_sidebar_show_clear_cache),
                        subLabel = stringResource(R.string.settings_browser_sidebar_show_clear_cache_desc),
                    ) {
                        ModernSwitch(
                            checked = showBrowserClearCache,
                            onCheckedChange = { enabled ->
                                scope.launch { browserSettingsStore.setShowBrowserClearCache(enabled) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserSettingsCompatScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browserSettingsStore = remember { BrowserSettingsStore(context) }
    val userAgentMode by browserSettingsStore.userAgentMode.collectAsState(initial = 0)
    val legacyCompatEnabled by browserSettingsStore.legacyCompatEnabled.collectAsState(initial = true)
    val browserEngine by browserSettingsStore.browserEngine.collectAsState(initial = 0)
    var showUserAgentDialog by remember { mutableStateOf(false) }
    var showEngineDialog by remember { mutableStateOf(false) }
    var showGeckoUninstallDialog by remember { mutableStateOf(false) }
    var geckoPackInstalled by remember {
        mutableStateOf(com.example.ava.webcompat.BrowserEngine.isGeckoAvailable(context))
    }
    val geckoDeviceSupported = remember { com.example.ava.webcompat.GeckoEngineInstaller.isDeviceSupported() }
    val geckoEnginePackExternal = remember { !com.example.ava.webcompat.EngineCapabilities.GECKO_BUNDLED }
    var geckoInstallInProgress by remember { mutableStateOf(false) }
    var geckoInstallSnapshot by remember {
        mutableStateOf(com.example.ava.webcompat.GeckoEngineInstaller.getInstallSnapshot(context))
    }
    var geckoUpdateResult by remember {
        mutableStateOf<com.example.ava.webcompat.GeckoEngineInstaller.UpdateResult?>(null)
    }

    LaunchedEffect(Unit) {
        while (true) {
            geckoPackInstalled = com.example.ava.webcompat.BrowserEngine.isGeckoAvailable(context)
            val snapshot = com.example.ava.webcompat.GeckoEngineInstaller.getInstallSnapshot(context)
            geckoInstallSnapshot = snapshot
            geckoInstallInProgress = snapshot.isActive
            // 500ms keeps install progress lively; idle only needs a slow sweep to
            // notice out-of-band pack installs/removals (package + file checks are
            // not free — don't burn them 2×/s while nothing is happening).
            delay(if (snapshot.isActive) 500 else 3_000)
        }
    }
    LaunchedEffect(geckoInstallSnapshot.phase, geckoInstallSnapshot.inProgress) {
        if (geckoInstallSnapshot.inProgress) return@LaunchedEffect
        when (geckoInstallSnapshot.phase) {
            com.example.ava.webcompat.GeckoEngineInstaller.InstallPhase.DECOMPRESSING,
            com.example.ava.webcompat.GeckoEngineInstaller.InstallPhase.INSTALLING,
            com.example.ava.webcompat.GeckoEngineInstaller.InstallPhase.VERIFYING ->
                com.example.ava.webcompat.GeckoEngineInstaller.resumePendingInstall(context)
            else -> Unit
        }
    }
    LaunchedEffect(showEngineDialog) {
        if (!showEngineDialog) {
            geckoUpdateResult = null
            return@LaunchedEffect
        }
        geckoPackInstalled = com.example.ava.webcompat.BrowserEngine.isGeckoAvailable(context)
        if (!geckoEnginePackExternal || !geckoDeviceSupported ||
            !geckoPackInstalled ||
            !com.example.ava.webcompat.BrowserEngine.isGeckoEnginePackInstalled(context)
        ) {
            geckoUpdateResult = null
            return@LaunchedEffect
        }
        geckoUpdateResult = null
        com.example.ava.webcompat.GeckoEngineInstaller.checkForUpdate(context) { result ->
            geckoUpdateResult = result
        }
    }
    val isGeckoEngine = browserEngine == com.example.ava.webcompat.BrowserEngine.GECKO && geckoPackInstalled

    LaunchedEffect(geckoPackInstalled) {
        if (geckoPackInstalled && geckoEnginePackExternal) {
            com.example.ava.webcompat.GeckoEngineRootSetup.maybeApply(context)
        }
    }

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_browser_entry_compat_title),
    ) {
        item { SettingsSectionLabel(stringResource(R.string.settings_browser_section_engine)) }
        item {
            SimpleCard {
                val engineLabels = listOf(
                    stringResource(R.string.settings_browser_engine_system),
                    if (geckoPackInstalled) stringResource(R.string.settings_browser_engine_gecko)
                    else stringResource(R.string.settings_browser_engine_gecko_missing),
                )
                val engineStatusFetching = stringResource(R.string.gecko_engine_status_fetching)
                val engineStatusDownloading = stringResource(
                    R.string.gecko_engine_status_downloading,
                    geckoInstallSnapshot.progress,
                )
                val engineStatusDecompressing = stringResource(
                    R.string.gecko_engine_status_decompressing,
                    geckoInstallSnapshot.progress,
                )
                val engineStatusVerifying = stringResource(R.string.gecko_engine_status_verifying)
                val engineStatusInstallReady = stringResource(R.string.gecko_engine_status_install_ready)
                val engineStatusFailed = stringResource(R.string.gecko_engine_status_failed)
                val engineStatusUnsupported = stringResource(R.string.settings_browser_engine_gecko_unsupported)
                val engineStatusText = when (geckoInstallSnapshot.phase) {
                    com.example.ava.webcompat.GeckoEngineInstaller.InstallPhase.FETCHING -> engineStatusFetching
                    com.example.ava.webcompat.GeckoEngineInstaller.InstallPhase.DOWNLOADING -> engineStatusDownloading
                    com.example.ava.webcompat.GeckoEngineInstaller.InstallPhase.DECOMPRESSING -> engineStatusDecompressing
                    com.example.ava.webcompat.GeckoEngineInstaller.InstallPhase.VERIFYING -> engineStatusVerifying
                    com.example.ava.webcompat.GeckoEngineInstaller.InstallPhase.INSTALLING -> engineStatusInstallReady
                    com.example.ava.webcompat.GeckoEngineInstaller.InstallPhase.FAILED -> engineStatusFailed
                    else -> when {
                        !geckoDeviceSupported && browserEngine == com.example.ava.webcompat.BrowserEngine.GECKO ->
                            engineStatusUnsupported
                        else -> engineLabels.getOrElse(browserEngine) { engineLabels[0] }
                    }
                }
                val engineAlreadyInProgressMsg = stringResource(R.string.gecko_engine_toast_install_in_progress)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (geckoInstallInProgress) {
                                AvaToast.show(
                                    context,
                                    engineAlreadyInProgressMsg,
                                    durationMs = AvaToast.LONG_MS,
                                )
                            }
                            showEngineDialog = true
                        }
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_browser_engine),
                            color = getTitleColor(),
                            fontSize = settingsTitleTextSize(),
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = engineStatusText,
                            color = getAccentColor(),
                            fontSize = settingsBodyTextSize(),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                SettingsDivider()
                val userAgentLabels = listOf(
                    stringResource(R.string.useragent_default),
                    stringResource(R.string.useragent_desktop),
                    stringResource(R.string.useragent_macos),
                    stringResource(R.string.useragent_ios),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showUserAgentDialog = true }
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_browser_useragent),
                            color = getTitleColor(),
                            fontSize = settingsTitleTextSize(),
                            fontWeight = FontWeight.Medium,
                        )
                        CollapsibleDescriptionText(
                            text = stringResource(R.string.settings_browser_useragent_desc),
                            fontSize = settingsBodyTextSize(),
                            lineHeight = settingsBodyLineHeight(),
                            color = getSettingsDescriptionColor(),
                        )
                        Text(
                            text = userAgentLabels.getOrElse(userAgentMode) { userAgentLabels[0] },
                            color = getAccentColor(),
                            fontSize = settingsBodyTextSize(),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_browser_legacy_compat),
                    subLabel = stringResource(R.string.settings_browser_legacy_compat_desc),
                ) {
                    ModernSwitch(
                        checked = legacyCompatEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { browserSettingsStore.setLegacyCompatEnabled(enabled) }
                        },
                    )
                }
            }
        }

        item { SettingsSectionLabel(stringResource(R.string.settings_browser_section_maintain)) }
        item {
            SimpleCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            com.example.ava.services.WebViewService.clearBrowserCache(context)
                            AvaToast.show(
                                context,
                                context.getString(R.string.settings_browser_cache_cleared),
                            )
                        }
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_browser_clear_cache),
                            color = getLabelColor(),
                            fontSize = settingsTitleTextSize(),
                            fontWeight = FontWeight.Medium,
                        )
                        CollapsibleDescriptionText(
                            text = stringResource(R.string.settings_browser_clear_cache_desc),
                            fontSize = settingsBodyTextSize(),
                            lineHeight = settingsBodyLineHeight(),
                            color = getSettingsDescriptionColor(),
                        )
                    }
                }
                SettingsDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            com.example.ava.services.WebViewService.clearCookiesAndHistory(context)
                            AvaToast.show(
                                context,
                                context.getString(R.string.settings_browser_cookies_history_cleared),
                            )
                        }
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_browser_clear_cookies_history),
                            color = getLabelColor(),
                            fontSize = settingsTitleTextSize(),
                            fontWeight = FontWeight.Medium,
                        )
                        CollapsibleDescriptionText(
                            text = stringResource(R.string.settings_browser_clear_cookies_history_desc),
                            fontSize = settingsBodyTextSize(),
                            lineHeight = settingsBodyLineHeight(),
                            color = getSettingsDescriptionColor(),
                        )
                    }
                }
            }
        }
    }

    if (showUserAgentDialog) {
        val options = listOf(
            stringResource(R.string.useragent_default),
            stringResource(R.string.useragent_desktop),
            stringResource(R.string.useragent_macos),
            stringResource(R.string.useragent_ios),
        )
        BrowserChoiceDialog(
            title = stringResource(R.string.settings_browser_useragent),
            description = stringResource(R.string.settings_browser_useragent_desc),
            options = options,
            selectedIndex = userAgentMode,
            onSelect = { index ->
                scope.launch { browserSettingsStore.setUserAgentMode(index) }
                showUserAgentDialog = false
            },
            onDismiss = { showUserAgentDialog = false },
        )
    }

    if (showEngineDialog) {
        val options = listOf(
            stringResource(R.string.settings_browser_engine_system),
            if (geckoPackInstalled) stringResource(R.string.settings_browser_engine_gecko)
            else stringResource(R.string.settings_browser_engine_gecko_missing),
        )
        val unsupportedMsg = stringResource(R.string.settings_browser_engine_gecko_unsupported)
        val alreadyInProgressMsg = stringResource(R.string.gecko_engine_toast_install_in_progress)
        val switchedGeckoMsg = stringResource(R.string.gecko_engine_toast_switched_gecko)
        val switchedSystemMsg = stringResource(R.string.gecko_engine_toast_switched_system)
        val updateButtonLabel = stringResource(R.string.gecko_engine_update_button)
        val uninstallLabel = stringResource(R.string.gecko_engine_uninstall)
        val showGeckoActionButton = geckoEnginePackExternal &&
            geckoDeviceSupported &&
            geckoPackInstalled &&
            com.example.ava.webcompat.BrowserEngine.isGeckoEnginePackInstalled(context) &&
            !geckoInstallInProgress
        val pickEngine: (Int) -> Unit = { index ->
            when {
                index == com.example.ava.webcompat.BrowserEngine.GECKO && !geckoDeviceSupported -> {
                    AvaToast.show(context, unsupportedMsg, durationMs = AvaToast.LONG_MS)
                }
                index == com.example.ava.webcompat.BrowserEngine.GECKO && geckoPackInstalled -> {
                    if (browserEngine != index) {
                        scope.launch {
                            browserSettingsStore.setBrowserEngine(index)
                            restartVoiceSatelliteServiceIfRunning()
                            AvaToast.show(context, switchedGeckoMsg, tag = AvaToast.HA_SYNC_TAG)
                        }
                    }
                }
                index == com.example.ava.webcompat.BrowserEngine.GECKO && !geckoPackInstalled -> {
                    if (geckoInstallInProgress) {
                        AvaToast.show(context, alreadyInProgressMsg, durationMs = AvaToast.LONG_MS)
                    } else {
                        scope.launch {
                            browserSettingsStore.setBrowserEngine(index)
                            restartVoiceSatelliteServiceIfRunning()
                        }
                        com.example.ava.webcompat.GeckoEngineInstaller.downloadAndInstall(context)
                    }
                }
                index == com.example.ava.webcompat.BrowserEngine.SYSTEM && browserEngine != index -> {
                    scope.launch {
                        browserSettingsStore.setBrowserEngine(index)
                        restartVoiceSatelliteServiceIfRunning()
                        AvaToast.show(context, switchedSystemMsg, tag = AvaToast.HA_SYNC_TAG)
                    }
                }
                index == com.example.ava.webcompat.BrowserEngine.SYSTEM -> {
                    scope.launch { browserSettingsStore.setBrowserEngine(index) }
                }
            }
            showEngineDialog = false
        }
        val titleActionLabel: String?
        val titleActionColor: Color
        val titleActionClick: (() -> Unit)?
        when {
            geckoInstallInProgress -> {
                titleActionLabel = stringResource(R.string.gecko_engine_cancel_install)
                titleActionColor = Color(0xFFEF4444)
                titleActionClick = {
                    com.example.ava.webcompat.GeckoEngineInstaller.cancelInstall(context)
                }
            }
            showGeckoActionButton &&
                geckoUpdateResult == com.example.ava.webcompat.GeckoEngineInstaller.UpdateResult.UPDATE_AVAILABLE -> {
                titleActionLabel = updateButtonLabel
                titleActionColor = getAccentColor()
                titleActionClick = {
                    com.example.ava.webcompat.GeckoEngineInstaller.downloadAndInstall(context)
                    showEngineDialog = false
                }
            }
            showGeckoActionButton &&
                (
                    geckoUpdateResult == com.example.ava.webcompat.GeckoEngineInstaller.UpdateResult.UP_TO_DATE ||
                        geckoUpdateResult == com.example.ava.webcompat.GeckoEngineInstaller.UpdateResult.NETWORK_ERROR
                    ) -> {
                titleActionLabel = uninstallLabel
                titleActionColor = Color(0xFFEF4444)
                titleActionClick = { showGeckoUninstallDialog = true }
            }
            else -> {
                titleActionLabel = null
                titleActionColor = Color.Unspecified
                titleActionClick = null
            }
        }
        BrowserChoiceDialog(
            title = stringResource(R.string.settings_browser_engine),
            description = stringResource(R.string.settings_browser_engine_gecko_hint),
            options = options,
            selectedIndex = browserEngine,
            onSelect = pickEngine,
            onDismiss = { showEngineDialog = false },
            titleActionLabel = titleActionLabel,
            titleActionColor = titleActionColor,
            onTitleAction = titleActionClick,
        )
    }

    if (showGeckoUninstallDialog) {
        val confirmTitleSize = settingsTitleTextSize(base = 17f)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showGeckoUninstallDialog = false },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .widthIn(max = 420.dp),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = {
                Text(
                    text = stringResource(R.string.gecko_engine_uninstall_confirm_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = confirmTitleSize,
                    lineHeight = (confirmTitleSize.value * 1.18f).sp,
                    color = getTitleColor(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                CollapsibleDescriptionText(
                    text = stringResource(R.string.gecko_engine_uninstall_confirm_message),
                    fontSize = settingsBodyTextSize(),
                    lineHeight = settingsBodyLineHeight(),
                    color = getSettingsDescriptionColor(),
                    topPadding = 0.dp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGeckoUninstallDialog = false
                        showEngineDialog = false
                        scope.launch {
                            if (browserEngine == com.example.ava.webcompat.BrowserEngine.GECKO) {
                                browserSettingsStore.setBrowserEngine(com.example.ava.webcompat.BrowserEngine.SYSTEM)
                                restartVoiceSatelliteServiceIfRunning()
                            }
                        }
                        geckoPackInstalled = false
                        com.example.ava.webcompat.GeckoEngineInstaller.uninstall(context)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.gecko_engine_uninstall_confirm_ok),
                        color = Color(0xFFEF4444),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showGeckoUninstallDialog = false }) {
                    Text(
                        text = stringResource(R.string.gecko_engine_uninstall_cancel),
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
private fun BrowserChoiceDialog(
    title: String,
    description: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    titleActionLabel: String? = null,
    titleActionColor: Color = Color.Unspecified,
    onTitleAction: (() -> Unit)? = null,
) {
    val titleSize = settingsTitleTextSize(base = 17f)
    val optionSize = settingsTitleTextSize()
    val actionMaxWidth = (132f * rememberSettingsTextScale()).dp
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .widthIn(max = 420.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = titleSize,
                    lineHeight = (titleSize.value * 1.18f).sp,
                    color = getTitleColor(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!titleActionLabel.isNullOrBlank() && onTitleAction != null) {
                    HorizontalDissolveText(
                        text = titleActionLabel,
                        fontSize = settingsCaptionTextSize(base = 13f),
                        color = titleActionColor,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .widthIn(max = actionMaxWidth)
                            .clickable(onClick = onTitleAction)
                            .padding(horizontal = 2.dp, vertical = 6.dp),
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selectedIndex == index,
                            onClick = { onSelect(index) },
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                selectedColor = getAccentColor(),
                                unselectedColor = Color(0xFF94A3B8),
                            ),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            fontSize = optionSize,
                            lineHeight = (optionSize.value * 1.2f).sp,
                            color = getTitleColor(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (description.isNotBlank()) {
                    CollapsibleDescriptionText(
                        text = description,
                        fontSize = settingsBodyTextSize(),
                        lineHeight = settingsBodyLineHeight(),
                        color = getSettingsDescriptionColor(),
                        topPadding = 6.dp,
                    )
                }
            }
        },
        confirmButton = {},
        shape = RoundedCornerShape(20.dp),
        containerColor = getDialogBackground(),
    )
}
