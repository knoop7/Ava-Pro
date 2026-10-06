package com.example.ava.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.settings.BrowserSettings
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.ExperimentalSettings
import com.example.ava.settings.HomeLockSettings
import com.example.ava.settings.HomeLockTarget
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.QuickEntitySettings
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.SIDEBAR_ITEM_NAME_MAX_LENGTH
import com.example.ava.settings.SIDEBAR_SETTINGS_LABEL_KEY
import com.example.ava.settings.SIDEBAR_SWITCH_ITEM_KEYS
import com.example.ava.settings.HomeCornerButton
import com.example.ava.settings.SidebarDockKey
import com.example.ava.settings.SidebarItemKey
import com.example.ava.settings.SidebarPosition
import com.example.ava.settings.mergeSidebarItemOrder
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.settings.sidebarDockOrderOrDefault
import com.example.ava.settings.sidebarItemOrderOrDefault
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.touchpad.TouchPadMath
import com.example.ava.touchpad.TouchPadOverlay
import com.example.ava.touchpad.TouchPadPrefs
import com.example.ava.ui.Screen
import com.example.ava.ui.components.ExpandedTapTarget
import com.example.ava.ui.haptic.TickSlider
import com.example.ava.ui.screens.home.HomeSidebarActions
import com.example.ava.ui.screens.settings.components.HorizontalDissolveText
import com.example.ava.ui.screens.settings.components.DialogScope
import com.example.ava.ui.screens.settings.components.SelectSetting
import com.example.ava.ui.screens.settings.components.SettingSliderLabelRow
import com.example.ava.ui.screens.settings.components.TextDialog
import com.example.ava.ui.screens.settings.components.ThemedDropdownMenu
import com.example.ava.ui.screens.settings.components.ThemedDropdownMenuItem
import com.example.ava.ui.screens.settings.components.rememberSettingsTextScale
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsDescriptionTopPadding
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.ui.theme.SlateTertiary
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Menu rows shown before the order list scrolls inside its inset well. */
private const val SIDEBAR_ORDER_VISIBLE_ROWS = 5

@Composable
fun SidebarSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sidebarState by viewModel.sidebarSettingsState.collectAsStateWithLifecycle(null)
    val playerState by viewModel.playerSettingsState.collectAsStateWithLifecycle(PlayerSettings())
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val browserStore = remember { BrowserSettingsStore(context) }
    val browserSettings by browserStore.getFlow().collectAsStateWithLifecycle(BrowserSettings())
    val quickEntityStore = remember { QuickEntitySettingsStore(context.quickEntitySettingsStore) }
    val quickEntitySettings by quickEntityStore.getFlow().collectAsStateWithLifecycle(QuickEntitySettings())
    val sidebarEnabled = sidebarState?.enableSidebar ?: true
    val sidebarPosition = sidebarState?.sidebarPosition ?: SidebarPosition.LEFT
    val sidebarPositionOptions = remember { SidebarPosition.entries }
    val positionLeftLabel = stringResource(R.string.settings_sidebar_position_left)
    val positionRightLabel = stringResource(R.string.settings_sidebar_position_right)
    val homeLockState by viewModel.homeLockSettingsState.collectAsStateWithLifecycle(null)
    val homeLockEnabled = homeLockState?.enabled ?: false
    val hasVideoRecording = remember(context, experimentalState?.cameraEnabled, experimentalState?.cameraMode) {
        HomeSidebarActions.isVideoRecordingAvailable(
            context,
            experimentalState ?: ExperimentalSettings()
        )
    }

    val savedOrder = remember(sidebarState?.itemOrder) {
        sidebarItemOrderOrDefault(sidebarState?.itemOrder)
    }
    val savedDockOrder = remember(sidebarState?.dockOrder) {
        sidebarDockOrderOrDefault(sidebarState?.dockOrder)
    }

    val allItems = remember(playerState, browserSettings, quickEntitySettings, hasVideoRecording, homeLockEnabled, sidebarState) {
        buildMap {
            // Always offered: it navigates rather than driving an overlay, so it has
            // no satellite / permission prerequisite to be unavailable for.
            put(SidebarItemKey.Home, SidebarReorderableItem(
                key = SidebarItemKey.Home,
                labelRes = R.string.home_sidebar_home,
                subLabelRes = R.string.settings_sidebar_show_home_desc,
                checked = sidebarState?.showHome ?: true,
                onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowHome(it) } }
            ))
            put(SidebarItemKey.DeviceControl, SidebarReorderableItem(
                key = SidebarItemKey.DeviceControl,
                labelRes = R.string.settings_device_control_title,
                subLabelRes = R.string.settings_sidebar_show_device_control_desc,
                checked = sidebarState?.showDeviceControl ?: false,
                onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowDeviceControl(it) } }
            ))
            if (HomeSidebarActions.isTouchPadAvailable()) {
                put(SidebarItemKey.TouchPad, SidebarReorderableItem(
                    key = SidebarItemKey.TouchPad,
                    labelRes = R.string.home_sidebar_touch_pad,
                    subLabelRes = R.string.settings_sidebar_show_touch_pad_desc,
                    checked = sidebarState?.showTouchPad ?: false,
                    onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowTouchPad(it) } }
                ))
            }
            if (HomeSidebarActions.isVoiceMessageAvailable(playerState)) {
                put(SidebarItemKey.VoiceMessage, SidebarReorderableItem(
                    key = SidebarItemKey.VoiceMessage,
                    labelRes = R.string.home_sidebar_voice_message,
                    subLabelRes = R.string.settings_sidebar_show_voice_message_desc,
                    checked = sidebarState?.showVoiceMessage ?: false,
                    onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowVoiceMessage(it) } }
                ))
            }
            if (HomeSidebarActions.isBrowserAvailable(browserSettings)) {
                put(SidebarItemKey.Browser, SidebarReorderableItem(
                    key = SidebarItemKey.Browser,
                    labelRes = R.string.home_sidebar_browser,
                    subLabelRes = R.string.settings_sidebar_show_browser_desc,
                    checked = sidebarState?.showBrowser ?: false,
                    onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowBrowser(it) } }
                ))
            }
            if (HomeSidebarActions.isWeatherAvailable(playerState)) {
                put(SidebarItemKey.Weather, SidebarReorderableItem(
                    key = SidebarItemKey.Weather,
                    labelRes = R.string.home_sidebar_weather,
                    subLabelRes = R.string.settings_sidebar_show_weather_desc,
                    checked = sidebarState?.showWeather ?: false,
                    onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowWeather(it) } }
                ))
            }
            if (HomeSidebarActions.isSimpleClockAvailable(playerState)) {
                put(SidebarItemKey.SimpleClock, SidebarReorderableItem(
                    key = SidebarItemKey.SimpleClock,
                    labelRes = R.string.home_sidebar_simple_clock,
                    subLabelRes = R.string.settings_sidebar_show_simple_clock_desc,
                    checked = sidebarState?.showSimpleClock ?: false,
                    onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowSimpleClock(it) } }
                ))
            }
            if (HomeSidebarActions.isDreamClockAvailable(playerState)) {
                put(SidebarItemKey.DreamClock, SidebarReorderableItem(
                    key = SidebarItemKey.DreamClock,
                    labelRes = R.string.home_sidebar_dream_clock,
                    subLabelRes = R.string.settings_sidebar_show_dream_clock_desc,
                    checked = sidebarState?.showDreamClock ?: false,
                    onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowDreamClock(it) } }
                ))
            }
            if (HomeSidebarActions.isQuickEntityAvailable(quickEntitySettings)) {
                put(SidebarItemKey.QuickEntity, SidebarReorderableItem(
                    key = SidebarItemKey.QuickEntity,
                    labelRes = R.string.home_sidebar_quick_entity,
                    subLabelRes = R.string.settings_sidebar_show_quick_entity_desc,
                    checked = sidebarState?.showQuickEntity ?: false,
                    onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowQuickEntity(it) } }
                ))
            }
            if (HomeSidebarActions.isVinylCoverDisplayAvailable(playerState)) {
                put(SidebarItemKey.VinylCoverDisplay, SidebarReorderableItem(
                    key = SidebarItemKey.VinylCoverDisplay,
                    labelRes = R.string.home_sidebar_vinyl_cover_display,
                    subLabelRes = R.string.settings_sidebar_show_vinyl_cover_display_desc,
                    checked = sidebarState?.showVinylCoverDisplay ?: false,
                    onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowVinylCoverDisplay(it) } }
                ))
            }
            if (homeLockEnabled &&
                HomeLockTarget.resolved(homeLockState ?: HomeLockSettings()) == HomeLockTarget.HOME
            ) {
                put(SidebarItemKey.HomeLock, SidebarReorderableItem(
                    key = SidebarItemKey.HomeLock,
                    labelRes = R.string.home_sidebar_home_lock,
                    subLabelRes = R.string.settings_sidebar_show_home_lock_desc,
                    checked = sidebarState?.showHomeLock ?: true,
                    onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowHomeLock(it) } }
                ))
            }
            if (hasVideoRecording) {
                put(SidebarItemKey.Camera, SidebarReorderableItem(
                    key = SidebarItemKey.Camera,
                    labelRes = R.string.home_sidebar_camera,
                    subLabelRes = R.string.settings_sidebar_show_camera_desc,
                    checked = sidebarState?.showCamera ?: false,
                    onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowCamera(it) } }
                ))
            }
            put(SidebarItemKey.MuteMicrophone, SidebarReorderableItem(
                key = SidebarItemKey.MuteMicrophone,
                labelRes = R.string.home_sidebar_mute_microphone,
                subLabelRes = R.string.settings_sidebar_show_mute_microphone_desc,
                checked = sidebarState?.showMuteMicrophone ?: false,
                onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowMuteMicrophone(it) } }
            ))
            put(SidebarItemKey.DarkMode, SidebarReorderableItem(
                key = SidebarItemKey.DarkMode,
                labelRes = R.string.home_sidebar_dark_mode,
                subLabelRes = R.string.settings_sidebar_show_dark_mode_desc,
                checked = sidebarState?.showDarkMode ?: false,
                onCheckedChange = { coroutineScope.launch { viewModel.saveSidebarShowDarkMode(it) } }
            ))
        }
    }

    val entryKeys = remember(savedOrder, allItems) {
        savedOrder.filter { key ->
            key in allItems && key != SidebarItemKey.Home && key !in SIDEBAR_SWITCH_ITEM_KEYS
        }
    }
    val switchKeys = remember(savedOrder, allItems) {
        savedOrder.filter { it in allItems && it in SIDEBAR_SWITCH_ITEM_KEYS }
    }

    val entryDrag = remember { SidebarOrderDrag(entryKeys) }
    val switchDrag = remember { SidebarOrderDrag(switchKeys) }
    val dockDrag = remember { SidebarOrderDrag(savedDockOrder) }
    LaunchedEffect(entryKeys) { entryDrag.sync(entryKeys) }
    LaunchedEffect(switchKeys) { switchDrag.sync(switchKeys) }
    LaunchedEffect(savedDockOrder) { dockDrag.sync(savedDockOrder) }

    SidebarSettingsScreenContent(
        navController = navController,
        viewModel = viewModel,
        sidebarEnabled = sidebarEnabled,
        sidebarPosition = sidebarPosition,
        sidebarPositionOptions = sidebarPositionOptions,
        positionLeftLabel = positionLeftLabel,
        positionRightLabel = positionRightLabel,
        hideHomeHeader = sidebarState?.hideHomeHeader ?: false,
        hideSidebarHeader = sidebarState?.hideSidebarHeader ?: false,
        homeCornerButton = sidebarState?.homeCornerButton ?: HomeCornerButton.SETTINGS,
        showHideHomeHeader = sidebarEnabled && (playerState.enableMinimalLauncher),
        allItems = allItems,
        customNames = sidebarState?.itemNames.orEmpty(),
        onRename = { key, name ->
            coroutineScope.launch { viewModel.saveSidebarItemName(key, name) }
        },
        savedOrder = savedOrder,
        entryDrag = entryDrag,
        switchDrag = switchDrag,
        dockDrag = dockDrag,
        coroutineScope = coroutineScope
    )
}

@Composable
private fun SidebarSettingsScreenContent(
    navController: NavController,
    viewModel: SettingsViewModel,
    sidebarEnabled: Boolean,
    sidebarPosition: SidebarPosition,
    sidebarPositionOptions: List<SidebarPosition>,
    positionLeftLabel: String,
    positionRightLabel: String,
    hideHomeHeader: Boolean,
    showHideHomeHeader: Boolean,
    hideSidebarHeader: Boolean,
    homeCornerButton: HomeCornerButton,
    allItems: Map<SidebarItemKey, SidebarReorderableItem>,
    customNames: Map<String, String>,
    onRename: (SidebarItemKey, String) -> Unit,
    savedOrder: List<SidebarItemKey>,
    entryDrag: SidebarOrderDrag<SidebarItemKey>,
    switchDrag: SidebarOrderDrag<SidebarItemKey>,
    dockDrag: SidebarOrderDrag<SidebarDockKey>,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_sidebar)
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_sidebar_enable),
                    subLabel = stringResource(R.string.settings_sidebar_enable_desc)
                ) {
                    ModernSwitch(
                        checked = sidebarEnabled,
                        onCheckedChange = {
                            coroutineScope.launch {
                                viewModel.saveSidebarEnabled(it)
                            }
                        }
                    )
                }

                if (sidebarEnabled) {
                    SettingsDivider()
                    SelectSetting(
                        name = stringResource(R.string.settings_sidebar_position),
                        description = stringResource(R.string.settings_sidebar_position_desc),
                        selected = sidebarPosition,
                        items = sidebarPositionOptions,
                        key = { it.name },
                        value = {
                            when (it) {
                                SidebarPosition.LEFT -> positionLeftLabel
                                SidebarPosition.RIGHT -> positionRightLabel
                                null -> ""
                            }
                        },
                        onConfirmRequest = { selected ->
                            if (selected != null) {
                                coroutineScope.launch {
                                    viewModel.saveSidebarPosition(selected)
                                }
                            }
                        }
                    )
                    if (showHideHomeHeader) {
                        SettingsDivider()
                        SettingRow(
                            label = stringResource(R.string.settings_sidebar_hide_home_header),
                            subLabel = stringResource(R.string.settings_sidebar_hide_home_header_desc),
                        ) {
                            ModernSwitch(
                                checked = hideHomeHeader,
                                onCheckedChange = {
                                    coroutineScope.launch {
                                        viewModel.saveSidebarHideHomeHeader(it)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (sidebarEnabled) {
            item {
                SimpleCard {
                    SettingRow(
                        label = stringResource(R.string.settings_sidebar_reorder_hint),
                        subLabel = stringResource(R.string.settings_sidebar_reorder_desc)
                    ) {}
                    SettingsInsetWell {
                        SettingRow(
                            label = stringResource(R.string.settings_sidebar_hide_header),
                            subLabel = stringResource(R.string.settings_sidebar_hide_header_desc),
                        ) {
                            ModernSwitch(
                                checked = hideSidebarHeader,
                                enabled = sidebarEnabled,
                                onCheckedChange = {
                                    coroutineScope.launch {
                                        viewModel.saveSidebarHideHeader(it)
                                    }
                                },
                            )
                        }
                    }
                    if (entryDrag.order.isNotEmpty()) {
                        SettingsInsetWell {
                            SidebarOrderSectionLabel(stringResource(R.string.settings_sidebar_order_menu))
                            SettingsWellDivider()
                            SidebarOrderViewport(
                                itemCount = entryDrag.order.size,
                                rowHeightPx = entryDrag.rowHeightPx,
                            ) {
                                SidebarKeyedOrderRows(
                                    keys = entryDrag.order,
                                    allItems = allItems,
                                    customNames = customNames,
                                    drag = entryDrag,
                                    enabled = sidebarEnabled,
                                    onRename = onRename,
                                    onCommit = { reordered ->
                                        coroutineScope.launch {
                                            viewModel.saveSidebarItemOrder(
                                                mergeSidebarItemOrder(savedOrder, reordered),
                                            )
                                        }
                                    },
                                    onSettingsClick = { key ->
                                        navController.navigate(sidebarItemSettingsRoute(key)) {
                                            launchSingleTop = true
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (switchDrag.order.isNotEmpty()) {
                        SettingsInsetWell {
                            SidebarOrderSectionLabel(stringResource(R.string.settings_sidebar_order_switches))
                            SettingsWellDivider()
                            SidebarKeyedOrderRows(
                                keys = switchDrag.order,
                                allItems = allItems,
                                customNames = customNames,
                                drag = switchDrag,
                                enabled = sidebarEnabled,
                                onRename = onRename,
                                onCommit = { reordered ->
                                    coroutineScope.launch {
                                        viewModel.saveSidebarItemOrder(
                                            mergeSidebarItemOrder(savedOrder, reordered),
                                        )
                                    }
                                },
                                onSettingsClick = { key ->
                                    navController.navigate(sidebarItemSettingsRoute(key)) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                    }
                    SettingsInsetWell {
                        SidebarOrderSectionLabel(stringResource(R.string.settings_sidebar_order_dock))
                        SettingsWellDivider()
                        dockDrag.order.forEachIndexed { index, key ->
                            if (index > 0) SettingsWellDivider()
                            when (key) {
                                SidebarDockKey.Home -> {
                                    val item = allItems[SidebarItemKey.Home] ?: return@forEachIndexed
                                    val defaultLabel = stringResource(item.labelRes)
                                    val serviceLabel =
                                        stringResource(R.string.settings_sidebar_home_button_service)
                                    val customName = customNames[SidebarItemKey.Home.name].orEmpty()
                                    val serviceSelected = homeCornerButton == HomeCornerButton.SERVICE
                                    SidebarReorderableRow(
                                        dragKey = key,
                                        label = if (serviceSelected) {
                                            stringResource(R.string.ava_tile_sub_service)
                                        } else {
                                            customName.ifBlank { defaultLabel }
                                        },
                                        defaultLabel = defaultLabel,
                                        customName = customName,
                                        onRename = { name -> onRename(SidebarItemKey.Home, name) },
                                        subLabel = stringResource(
                                            if (serviceSelected) {
                                                R.string.ava_widget_service_desc
                                            } else {
                                                item.subLabelRes
                                            },
                                        ),
                                        checked = item.checked,
                                        showSettingsLink = true,
                                        showRename = !serviceSelected,
                                        settingsChoices = listOf(defaultLabel, serviceLabel),
                                        settingsChoiceIndex = if (serviceSelected) 1 else 0,
                                        onSettingsChoice = { index ->
                                            coroutineScope.launch {
                                                viewModel.saveHomeCornerButton(
                                                    if (index == 1) {
                                                        HomeCornerButton.SERVICE
                                                    } else {
                                                        HomeCornerButton.SETTINGS
                                                    },
                                                )
                                            }
                                        },
                                        enabled = sidebarEnabled,
                                        isDragging = dockDrag.dragging == key,
                                        onMeasureRowHeight = dockDrag::onMeasure,
                                        onDragStart = { dockDrag.start(key) },
                                        onDragEnd = { travel ->
                                            dockDrag.finish(save = true, travelY = travel) { reordered ->
                                                coroutineScope.launch {
                                                    viewModel.saveSidebarDockOrder(reordered)
                                                }
                                            }
                                        },
                                        onDragCancel = { dockDrag.finish(save = false, travelY = 0f) {} },
                                        onCheckedChange = item.onCheckedChange,
                                        onSettingsClick = {},
                                    )
                                }
                                SidebarDockKey.Settings -> {
                                    val defaultLabel = stringResource(R.string.label_settings)
                                    val customName = customNames[SIDEBAR_SETTINGS_LABEL_KEY].orEmpty()
                                    SidebarReorderableRow(
                                        dragKey = key,
                                        label = customName.ifBlank { defaultLabel },
                                        defaultLabel = defaultLabel,
                                        customName = customName,
                                        onRename = { name ->
                                            coroutineScope.launch {
                                                viewModel.saveSidebarSettingsLabel(name)
                                            }
                                        },
                                        subLabel = stringResource(R.string.settings_sidebar_rename_hint),
                                        checked = true,
                                        showSwitch = false,
                                        showSettingsLink = false,
                                        enabled = sidebarEnabled,
                                        isDragging = dockDrag.dragging == key,
                                        onMeasureRowHeight = dockDrag::onMeasure,
                                        onDragStart = { dockDrag.start(key) },
                                        onDragEnd = { travel ->
                                            dockDrag.finish(save = true, travelY = travel) { reordered ->
                                                coroutineScope.launch {
                                                    viewModel.saveSidebarDockOrder(reordered)
                                                }
                                            }
                                        },
                                        onDragCancel = { dockDrag.finish(save = false, travelY = 0f) {} },
                                        onCheckedChange = {},
                                        onSettingsClick = {},
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
private fun SidebarOrderSectionLabel(text: String) {
    Text(
        text = text,
        color = getSettingsDescriptionColor(),
        fontSize = settingsBodyTextSize(),
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun SidebarOrderViewport(
    itemCount: Int,
    rowHeightPx: Float,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (itemCount <= SIDEBAR_ORDER_VISIBLE_ROWS) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
        return
    }
    val density = LocalDensity.current
    val maxHeight = if (rowHeightPx > 0f) {
        with(density) { (rowHeightPx * SIDEBAR_ORDER_VISIBLE_ROWS).toDp() }
    } else {
        (84 * SIDEBAR_ORDER_VISIBLE_ROWS).dp
    }
    SettingsEdgeFadeScrollColumn(
        maxHeight = maxHeight,
        verticalArrangement = Arrangement.Top,
        handoffOverscrollToParent = true,
        content = content,
    )
}

@Composable
private fun SidebarKeyedOrderRows(
    keys: List<SidebarItemKey>,
    allItems: Map<SidebarItemKey, SidebarReorderableItem>,
    customNames: Map<String, String>,
    drag: SidebarOrderDrag<SidebarItemKey>,
    enabled: Boolean,
    onRename: (SidebarItemKey, String) -> Unit,
    onCommit: (List<SidebarItemKey>) -> Unit,
    onSettingsClick: (SidebarItemKey) -> Unit,
) {
    keys.forEachIndexed { index, itemKey ->
        val item = allItems[itemKey] ?: return@forEachIndexed
        if (index > 0) SettingsWellDivider()
        val defaultLabel = stringResource(item.labelRes)
        val customName = customNames[itemKey.name].orEmpty()
        key(itemKey) {
            SidebarReorderableRow(
                dragKey = itemKey,
                label = customName.ifBlank { defaultLabel },
                defaultLabel = defaultLabel,
                customName = customName,
                onRename = { name -> onRename(itemKey, name) },
                subLabel = stringResource(item.subLabelRes),
                checked = item.checked,
                enabled = enabled,
                isDragging = drag.dragging == itemKey,
                onMeasureRowHeight = drag::onMeasure,
                onDragStart = { drag.start(itemKey) },
                onDragEnd = { travel -> drag.finish(save = true, travelY = travel, commit = onCommit) },
                onDragCancel = { drag.finish(save = false, travelY = 0f) {} },
                onCheckedChange = item.onCheckedChange,
                onSettingsClick = { onSettingsClick(itemKey) },
            )
        }
    }
}

private class SidebarOrderDrag<T>(initial: List<T>) {
    var order by mutableStateOf(initial)
        private set
    var dragging by mutableStateOf<T?>(null)
        private set
    var rowHeightPx by mutableFloatStateOf(0f)
        private set

    fun sync(saved: List<T>) {
        if (dragging == null) order = saved
    }

    fun onMeasure(heightPx: Float) {
        if (rowHeightPx <= 0f && heightPx > 0f) rowHeightPx = heightPx
    }

    fun start(key: T) {
        dragging = key
    }

    fun finish(save: Boolean, travelY: Float, commit: (List<T>) -> Unit) {
        val key = dragging ?: return
        val from = order.indexOf(key)
        if (save && from >= 0 && rowHeightPx > 0f) {
            val to = (from + (travelY / rowHeightPx).roundToInt()).coerceIn(0, order.lastIndex)
            if (from != to) {
                val reordered = order.toMutableList().apply { add(to, removeAt(from)) }
                order = reordered
                commit(reordered)
            }
        }
        dragging = null
    }
}

/** Existing feature page for each drawer row. Only Touch Pad is a new third-level page. */
private fun sidebarItemSettingsRoute(key: SidebarItemKey): String = when (key) {
    SidebarItemKey.DeviceControl -> Screen.SETTINGS_SERVICE_DEVICE_CONTROL
    SidebarItemKey.TouchPad -> Screen.SETTINGS_SIDEBAR_TOUCH_PAD
    SidebarItemKey.VoiceMessage -> Screen.SETTINGS_INTERACTION_VOICE_MESSAGE
    SidebarItemKey.Browser -> Screen.SETTINGS_BROWSER
    SidebarItemKey.Weather -> Screen.SETTINGS_INTERACTION_SCENE
    SidebarItemKey.SimpleClock -> Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK_APPEARANCE
    SidebarItemKey.DreamClock -> Screen.SETTINGS_INTERACTION_DREAM_CLOCK_APPEARANCE
    SidebarItemKey.QuickEntity -> Screen.SETTINGS_INTERACTION_QUICK_ENTITY
    SidebarItemKey.VinylCoverDisplay -> Screen.SETTINGS_INTERACTION_PLAYBACK
    SidebarItemKey.HomeLock -> Screen.SETTINGS_HOME_LOCK
    SidebarItemKey.Camera -> Screen.SETTINGS_CAMERA
    SidebarItemKey.MuteMicrophone -> Screen.SETTINGS_VOICE_MICROPHONE
    SidebarItemKey.DarkMode -> Screen.SETTINGS_INTERACTION_INTERFACE
    SidebarItemKey.Home -> Screen.SETTINGS_SIDEBAR
}

@Composable
fun TouchPadSettingsScreen(navController: NavController) {
    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.touch_pad_prefs_title),
    ) {
        item {
            TouchPadPrefsCard()
        }
        item {
            TouchPadHaCard()
        }
    }
}

private data class SidebarReorderableItem(
    val key: SidebarItemKey,
    val labelRes: Int,
    val subLabelRes: Int,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit
)

/**
 * Press the handle or rename icon to reorder. A short tap (icon only) still
 * renames. Small finger noise is eaten so the list does not scroll away and the row
 * does not follow until the hold has landed and the finger has clearly moved.
 */
private fun Modifier.sidebarReorderPress(
    key: Any,
    onTap: (() -> Unit)?,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onDragCancel: () -> Unit,
): Modifier = pointerInput(key) {
    val touchSlop = viewConfiguration.touchSlop
    // Wider than system slop: a wobble must not become a scroll or a row jump.
    val armSlop = touchSlop * 3f
    val holdMs = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pointerId = down.id
        val origin = down.position
        val downTime = down.uptimeMillis
        var armed = false
        var anchorY = origin.y
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!change.pressed) {
                    when {
                        armed -> onDragEnd(change.position.y - anchorY)
                        onTap != null -> onTap()
                    }
                    break
                }
                val dx = change.position.x - origin.x
                val dy = change.position.y - origin.y
                if (!armed) {
                    if (hypot(dx, dy) > armSlop) {
                        // A real slide before the hold lands belongs to the list.
                        break
                    }
                    change.consume()
                    if (change.uptimeMillis - downTime >= holdMs) {
                        armed = true
                        anchorY = change.position.y
                        onDragStart()
                        onDrag(0f)
                    }
                } else {
                    // Absolute offset from the hold point. Summing deltas jitters
                    // when a frame is dropped or the list shifts under the finger.
                    change.consume()
                    onDrag(change.position.y - anchorY)
                }
            }
        } catch (cancelled: CancellationException) {
            if (armed) onDragCancel()
            throw cancelled
        }
    }
}

@Composable
private fun SidebarReorderableRow(
    dragKey: Any,
    label: String,
    defaultLabel: String,
    customName: String,
    dialogValue: String = customName,
    dialogPlaceholder: String = defaultLabel,
    onRename: (String) -> Unit,
    subLabel: String,
    checked: Boolean,
    enabled: Boolean,
    isDragging: Boolean,
    onMeasureRowHeight: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: (Float) -> Unit,
    onDragCancel: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onSettingsClick: () -> Unit,
    showSwitch: Boolean = true,
    showSettingsLink: Boolean = true,
    showRename: Boolean = true,
    settingsChoices: List<String> = emptyList(),
    settingsChoiceIndex: Int = 0,
    onSettingsChoice: ((Int) -> Unit)? = null,
) {
    val rowScale = rememberSettingsTextScale()
    val dragHandleDescription = stringResource(R.string.settings_sidebar_drag_handle_desc)
    // Handle and rename icon share one press: hold to reorder, tap the icon to rename.
    // Title and description stay one line and scroll sideways on their own.
    // The switch and settings icon keep their own hit targets.
    // Draw-only. Writing this must not recompose the list on every pointer move.
    val followY = remember { mutableFloatStateOf(0f) }
    val renameScope = remember { DialogScope() }
    val renaming by renameScope.isDialogOpen.collectAsStateWithLifecycle()
    val tooLong = stringResource(
        R.string.settings_sidebar_rename_too_long,
        SIDEBAR_ITEM_NAME_MAX_LENGTH,
    )
    // Faint pencil = "this row can be renamed"; it lights up to the accent once the row
    // carries a custom name, so the list also shows at a glance what was changed.
    val renameTint by animateColorAsState(
        targetValue = if (customName.isNotBlank()) {
            getAccentColor()
        } else {
            SlateTertiary.copy(alpha = 0.45f)
        },
        animationSpec = tween(durationMillis = 220),
        label = "sidebarRenameTint",
    )

    if (renaming) {
        with(renameScope) {
            TextDialog(
                title = stringResource(R.string.settings_sidebar_rename_title),
                description = stringResource(R.string.settings_sidebar_rename_hint),
                value = dialogValue,
                placeholder = dialogPlaceholder,
                validation = { input ->
                    if (input.trim().length > SIDEBAR_ITEM_NAME_MAX_LENGTH) tooLong else null
                },
                onConfirmRequest = onRename,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .onGloballyPositioned { coordinates ->
                onMeasureRowHeight(coordinates.size.height.toFloat())
            }
            .graphicsLayer {
                translationY = followY.floatValue
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = (16f * rowScale).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((8f * rowScale).dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = (36f * rowScale).dp, height = (48f * rowScale).dp)
                    .sidebarReorderPress(
                        key = dragKey,
                        onTap = null,
                        onDragStart = onDragStart,
                        onDrag = { followY.floatValue = it },
                        onDragEnd = { travel ->
                            followY.floatValue = 0f
                            onDragEnd(travel)
                        },
                        onDragCancel = {
                            followY.floatValue = 0f
                            onDragCancel()
                        },
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_sidebar_drag_handle_24px),
                    contentDescription = dragHandleDescription,
                    tint = SlateTertiary.copy(alpha = if (isDragging) 0.85f else 0.45f),
                    modifier = Modifier.size((22f * rowScale).dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy((6f * rowScale).dp),
                ) {
                    HorizontalDissolveText(
                        text = label,
                        fontSize = settingsTitleTextSize(),
                        color = getLabelColor(),
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (showRename) Icon(
                        imageVector = Icons.Outlined.DriveFileRenameOutline,
                        contentDescription = stringResource(R.string.settings_sidebar_rename_title),
                        tint = renameTint,
                        modifier = Modifier
                            .size((18f * rowScale).dp)
                            .sidebarReorderPress(
                                key = dragKey,
                                onTap = { renameScope.openDialog() },
                                onDragStart = onDragStart,
                                onDrag = { followY.floatValue = it },
                                onDragEnd = { travel ->
                                    followY.floatValue = 0f
                                    onDragEnd(travel)
                                },
                                onDragCancel = {
                                    followY.floatValue = 0f
                                    onDragCancel()
                                },
                            ),
                    )
                }
                if (subLabel.isNotEmpty()) {
                    HorizontalDissolveText(
                        text = subLabel,
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor(),
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = settingsDescriptionTopPadding()),
                    )
                }
            }
            if (showSettingsLink || showSwitch) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy((12f * rowScale).dp),
                ) {
                    if (showSettingsLink) {
                        val gearChooses = onSettingsChoice != null && settingsChoices.isNotEmpty()
                        var gearMenuOpen by remember(dragKey) { mutableStateOf(false) }
                        Box {
                            ExpandedTapTarget(
                                onClick = {
                                    if (gearChooses) gearMenuOpen = true else onSettingsClick()
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_sidebar_settings_24px),
                                    contentDescription = stringResource(R.string.label_settings),
                                    tint = SlateTertiary.copy(alpha = 0.55f),
                                    modifier = Modifier.size((18f * rowScale).dp),
                                )
                            }
                            if (gearChooses) {
                                ThemedDropdownMenu(
                                    expanded = gearMenuOpen,
                                    onDismissRequest = { gearMenuOpen = false },
                                ) {
                                    settingsChoices.forEachIndexed { index, choice ->
                                        ThemedDropdownMenuItem(
                                            text = choice,
                                            selected = index == settingsChoiceIndex,
                                            onClick = {
                                                gearMenuOpen = false
                                                onSettingsChoice?.invoke(index)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (showSwitch) {
                        ModernSwitch(
                            checked = checked,
                            enabled = enabled,
                            onCheckedChange = onCheckedChange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchPadPrefsCard() {
    val context = LocalContext.current
    val prefs = remember { TouchPadPrefs(context) }
    var opacity by remember { mutableIntStateOf(prefs.opacity) }
    var sensitivity by remember { mutableIntStateOf(prefs.cursorSensitivity) }
    var cornerSummon by remember { mutableStateOf(prefs.cornerSummonEnabled) }
    SimpleCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            SettingSliderLabelRow(
                title = stringResource(R.string.touch_pad_opacity),
                badgeText = "$opacity%",
            )
            TickSlider(
                value = opacity.toFloat(),
                onValueChange = { opacity = it.toInt() },
                onValueChangeFinished = { prefs.opacity = opacity },
                valueRange = TouchPadMath.MIN_OPACITY.toFloat()..TouchPadMath.MAX_OPACITY.toFloat(),
                steps = TouchPadMath.MAX_OPACITY - TouchPadMath.MIN_OPACITY - 1,
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
        SettingsDivider()
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            SettingSliderLabelRow(
                title = stringResource(R.string.touch_pad_cursor_sensitivity),
                badgeText = "$sensitivity%",
            )
            TickSlider(
                value = sensitivity.toFloat(),
                onValueChange = { sensitivity = it.toInt() },
                onValueChangeFinished = { prefs.cursorSensitivity = sensitivity },
                valueRange = TouchPadMath.MIN_SENSITIVITY.toFloat()..TouchPadMath.MAX_SENSITIVITY.toFloat(),
                steps = (TouchPadMath.MAX_SENSITIVITY - TouchPadMath.MIN_SENSITIVITY) / 5 - 1,
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
        SettingsDivider()
        SettingRow(
            label = stringResource(R.string.touch_pad_corner_summon),
            subLabel = stringResource(R.string.touch_pad_corner_summon_subtitle),
        ) {
            ModernSwitch(
                checked = cornerSummon,
                onCheckedChange = {
                    cornerSummon = it
                    prefs.cornerSummonEnabled = it
                    TouchPadOverlay.syncSummonCorner()
                },
            )
        }
    }
}

@Composable
private fun TouchPadHaCard() {
    val context = LocalContext.current
    val prefs = remember { TouchPadPrefs(context) }
    var enabled by remember { mutableStateOf(prefs.haSelectEnabled) }
    val scope = rememberCoroutineScope()
    SimpleCard {
        SettingRow(
            label = stringResource(R.string.touch_pad_ha_select),
            subLabel = stringResource(R.string.touch_pad_ha_select_desc),
        ) {
            ModernSwitch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.haSelectEnabled = it
                    scope.launch {
                        kotlinx.coroutines.delay(100)
                        restartVoiceSatelliteServiceIfRunning()
                    }
                },
            )
        }
    }
}

