package com.example.ava.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ZoomIn
import com.example.ava.settings.isHaKioskModeOn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SliderDefaults
import com.example.ava.ui.haptic.TickSlider
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.BrowserSettings
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.ExperimentalSettings
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.ava.settings.HomeLockSession
import com.example.ava.settings.HomeCornerButton
import com.example.ava.settings.HomeLockSettings
import com.example.ava.settings.HomeLockSettingsStore
import com.example.ava.settings.MicrophoneSettings
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.QuickEntitySettings
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.SIDEBAR_SWITCH_ITEM_KEYS
import com.example.ava.settings.SIDEBAR_SETTINGS_LABEL_KEY
import com.example.ava.settings.SidebarDockKey
import com.example.ava.settings.SidebarItemKey
import com.example.ava.settings.SidebarSettings
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.VideoRecordingStateManager
import com.example.ava.settings.customItemName
import com.example.ava.settings.customLabel
import com.example.ava.settings.homeLockSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.settings.sidebarDockOrderOrDefault
import com.example.ava.settings.sidebarItemOrderOrDefault
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.settings.microphoneSettingsStore
import com.example.ava.touchpad.TouchPadOverlay
import com.example.ava.ui.Screen
import com.example.ava.ui.components.LocalSidebarDrawerListFocus
import com.example.ava.ui.components.PadContentScrollRemote
import com.example.ava.ui.glass.LiquidGlassSwitch
import com.example.ava.ui.glass.SettingsGlassEnter
import com.example.ava.ui.glass.liquidGlass
import com.example.ava.ui.glass.rememberLiquidGlassState
import com.example.ava.ui.components.sidebarDrawerFocusable
import com.example.ava.ui.services.VoiceSatelliteCompactStatus
import com.example.ava.ui.services.rememberToggleVoiceSatellite
import com.example.ava.mods.ModBleAdvProxyBridge
import com.example.ava.ui.screens.settings.checkOverlayPermission
import com.example.ava.ui.screens.settings.filterSettingsSearch
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getSliderInactiveColor
import com.example.ava.ui.screens.settings.ModernSwitch
import com.example.ava.ui.screens.settings.requestOverlayPermission
import com.example.ava.ui.screens.settings.resolveSettingsSearchHit
import com.example.ava.ui.screens.settings.SettingsSearchCatalog
import com.example.ava.ui.screens.settings.SettingsSidebarSearchField
import com.example.ava.ui.screens.settings.SettingsSidebarSearchResults
import com.example.ava.ui.screens.settings.components.settingsFocusHighlight
import com.example.ava.ui.theme.SlateBackground
import com.example.ava.utils.DeviceFeatureManager
import kotlinx.coroutines.launch
import com.example.ava.ui.theme.SlateSecondary
import com.example.ava.ui.theme.SlateSecondaryDark
import com.example.ava.ui.theme.SlateText
import com.example.ava.webcompat.BrowserEngine
import com.example.ava.webcompat.EngineCapabilities
import com.example.ava.webcompat.GeckoSatelliteStatusHolder
import kotlin.math.roundToInt

private val LocalSidebarPressGeneration = staticCompositionLocalOf { 0 }
private val LocalSidebarCancelPress = staticCompositionLocalOf { false }

/**
 * Vertical scroll can win after a row already emitted [PressInteraction.Press].
 * Without an explicit Cancel, the ripple/"预选" highlight sticks across day/night themes.
 */
@Composable
private fun rememberSidebarRowInteractionSource(
    pressGeneration: Int,
    cancelPress: Boolean,
): MutableInteractionSource {
    val interactionSource = remember(pressGeneration) { MutableInteractionSource() }
    val activePress = remember(pressGeneration) { mutableStateOf<PressInteraction.Press?>(null) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> activePress.value = interaction
                is PressInteraction.Release -> activePress.value = null
                is PressInteraction.Cancel -> activePress.value = null
            }
        }
    }
    LaunchedEffect(cancelPress, interactionSource) {
        if (!cancelPress) return@LaunchedEffect
        val press = activePress.value ?: return@LaunchedEffect
        interactionSource.emit(PressInteraction.Cancel(press))
    }
    return interactionSource
}

/** Renamed rows win over the shipped translation; clearing the name restores it. */
@Composable
private fun sidebarLabel(
    settings: SidebarSettings,
    key: SidebarItemKey,
    defaultRes: Int,
): String = settings.customItemName(key) ?: stringResource(defaultRes)

@Composable
fun HomeSidebarContent(
    navController: NavController,
    isDarkMode: Boolean,
    onClose: () -> Unit
) {
    HomeSidebarContent(
        isDarkMode = isDarkMode,
        onClose = onClose,
        onSettingsClick = { route ->
            navController.navigate(route) {
                popUpTo(Screen.HOME)
                launchSingleTop = true
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeSidebarContent(
    isDarkMode: Boolean,
    onClose: () -> Unit,
    /**
     * Opens a settings route. Rows that live inside Settings hand their own route in
     * so they land on the page itself instead of dropping the user at the root.
     */
    onSettingsClick: (route: String) -> Unit,
    interactiveSatelliteStatus: Boolean = true,
    /** When true, append browser-only maintenance rows (overlay sidebar only). */
    includeBrowserTools: Boolean = false,
    onBrowserWebConsole: (() -> Unit)? = null,
    onBrowserClearCache: (() -> Unit)? = null,
    onBrowserUserAgent: (() -> Unit)? = null,
    onBrowserRemoteUrl: (() -> Unit)? = null,
    onBrowserTampermonkey: (() -> Unit)? = null,
    onBrowserToggleHaKiosk: (() -> Unit)? = null,
    /** Settings host sidebar only — never home or the browser overlay. */
    includeSettingsSearch: Boolean = false,
    onSettingsSearchNavigate: ((route: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sidebarStore = remember { SidebarSettingsStore(context.sidebarSettingsStore) }
    val sidebarSettings by sidebarStore.getFlow().collectAsStateWithLifecycle(SidebarSettings())
    val serviceButtonActive = sidebarSettings.homeCornerButton == HomeCornerButton.SERVICE
    val toggleMainService = rememberToggleVoiceSatellite()
    val playerStore = remember { PlayerSettingsStore(context.playerSettingsStore) }
    val playerSettings by playerStore.getFlow().collectAsStateWithLifecycle(PlayerSettings())
    val browserStore = remember { BrowserSettingsStore(context) }
    val browserSettings by browserStore.getFlow().collectAsStateWithLifecycle(BrowserSettings())
    val quickEntityStore = remember { QuickEntitySettingsStore(context.quickEntitySettingsStore) }
    val quickEntitySettings by quickEntityStore.getFlow().collectAsStateWithLifecycle(QuickEntitySettings())
    val experimentalStore = remember { ExperimentalSettingsStore(context) }
    val experimentalSettings by experimentalStore.getFlow().collectAsStateWithLifecycle(ExperimentalSettings())
    val homeLockStore = remember { HomeLockSettingsStore(context.homeLockSettingsStore) }
    val homeLockSettings by homeLockStore.getFlow().collectAsStateWithLifecycle(HomeLockSettings())
    val microphoneStore = remember { MicrophoneSettingsStore(context.microphoneSettingsStore) }
    val microphoneSettings by microphoneStore.getFlow().collectAsStateWithLifecycle(MicrophoneSettings())
    val videoRecordingState = remember { VideoRecordingStateManager.getInstance(context) }
    val videoRecordingEnabled by videoRecordingState.enabledState.collectAsStateWithLifecycle(false)
    val touchPadVisible by TouchPadOverlay.visible.collectAsStateWithLifecycle(false)

    val satelliteStarted = rememberHomeSidebarSatelliteStarted()
    // Home / browser sidebars remount out of frost into Settings — arm the
    // blur→clear handoff. The settings-host sidebar stays mounted (search), so
    // it must not arm or every in-settings hop would flash frost again.
    val goSettings: (String) -> Unit = { route ->
        if (!includeSettingsSearch) {
            SettingsGlassEnter.armFromSidebarFrost()
        }
        onSettingsClick(route)
    }
    fun onFeatureClick(action: suspend () -> Unit) {
        if (!satelliteStarted) return
        if (!checkOverlayPermission(context)) {
            requestOverlayPermission(context)
            return
        }
        coroutineScope.launch { action() }
    }

    val textColor = if (isDarkMode) Color.White else SlateText
    val secondaryColor = if (isDarkMode) SlateSecondaryDark else SlateSecondary
    val dividerColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFE5E7EB)
    // Settings sidebar: section dividers share the 8dp inset of the search
    // underline (SettingsSidebarSearchField) so both lines end flush.
    val sectionDividerModifier = if (includeSettingsSearch) {
        Modifier.padding(horizontal = 8.dp)
    } else {
        Modifier
    }
    val sidebarScale = rememberHomeSidebarTextScale()
    val showSidebarHeader = !sidebarSettings.hideSidebarHeader
    val sidebarGlass by rememberLiquidGlassState()
    val scrollState = rememberScrollState()
    val glassScrollMax = remember { intArrayOf(0) }
    var glassScrollMaxPx by remember { mutableIntStateOf(0) }
    var glassScrollPx by remember { mutableFloatStateOf(0f) }
    val glassScrollable = rememberScrollableState { delta ->
        val old = glassScrollPx
        val new = (old - delta).coerceIn(0f, glassScrollMax[0].toFloat())
        glassScrollPx = new
        old - new
    }
    var pressGeneration by remember { mutableIntStateOf(0) }
    val scrolling = if (sidebarGlass.enabled) {
        glassScrollable.isScrollInProgress
    } else {
        scrollState.isScrollInProgress
    }
    LaunchedEffect(sidebarGlass.enabled) {
        glassScrollPx = 0f
    }
    val hostView = LocalView.current
    var listScreenLeft by remember { mutableFloatStateOf(0f) }
    var listScreenTop by remember { mutableFloatStateOf(0f) }
    var listScreenRight by remember { mutableFloatStateOf(0f) }
    var listScreenBottom by remember { mutableFloatStateOf(0f) }
    DisposableEffect(scrollState, glassScrollable, sidebarGlass.enabled) {
        val glass = sidebarGlass.enabled
        val sink = object : PadContentScrollRemote.Sink {
            override fun containsScreenPoint(x: Float, y: Float): Boolean =
                x >= listScreenLeft && x < listScreenRight &&
                    y >= listScreenTop && y < listScreenBottom &&
                    listScreenRight > listScreenLeft

            override fun onPadScroll(dx: Float, dy: Float): Boolean {
                if (kotlin.math.abs(dy) < 0.2f && kotlin.math.abs(dx) < 0.2f) return false
                coroutineScope.launch {
                    if (glass) {
                        val max = glassScrollMax[0].toFloat()
                        glassScrollPx = (glassScrollPx + dy).coerceIn(0f, max)
                    } else {
                        scrollState.scrollBy(dy)
                    }
                }
                return true
            }
        }
        PadContentScrollRemote.register(sink)
        onDispose { PadContentScrollRemote.unregister(sink) }
    }
    LaunchedEffect(scrolling) {
        if (scrolling) pressGeneration++
    }
    val requestClose = {
        pressGeneration++
        onClose()
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissSearchInput = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    CompositionLocalProvider(
        LocalSidebarPressGeneration provides pressGeneration,
        LocalSidebarCancelPress provides scrolling,
    ) {
    // Only horizontal insets live on the container. Vertical insets sit inside
    // the scrolling content (or on the pinned header) so the scroll viewport
    // reaches the panel's physical top/bottom edges instead of a fixed frame.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(start = 12.dp, end = 8.dp)
            .then(
                if (sidebarGlass.enabled) {
                    // Dissolve inside the 20dp corners so rows fade into the glass,
                    // not a panel-colour scrim (that would read as a dirty rim).
                        Modifier.drawerVerticalEdgeDissolve(
                        showTop = true,
                        showBottom = !sidebarSettings.showHome,
                        fadeToColor = Color.Transparent,
                        // glassMode multiplies 1.5×; this lands at the 20dp
                        // inset / corner so rows dissolve inside the slab.
                        fadeHeight = 20.dp / 1.5f,
                        glassMode = true,
                    )
                } else {
                    Modifier
                }
            )
    ) {
        val sidebarHeaderTopInset = if (sidebarGlass.enabled) {
            Modifier.padding(top = 16.dp)
        } else {
            Modifier.statusBarsPadding().padding(top = 24.dp)
        }
        if (includeSettingsSearch && showSidebarHeader) {
            HomeSidebarTitleRow(
                isDarkMode = isDarkMode,
                textColor = textColor,
                sidebarScale = sidebarScale,
                interactiveSatelliteStatus = interactiveSatelliteStatus,
                modifier = Modifier
                    .then(sidebarHeaderTopInset)
                    .dismissSettingsSearchFocusOnPress(
                        enabled = true,
                        focusManager = focusManager,
                        hideKeyboard = { keyboard?.hide() },
                    ),
            )
        }

        var settingsSearchQuery by remember { mutableStateOf("") }
        val searching = includeSettingsSearch && settingsSearchQuery.isNotBlank()
        val showBluetoothSearch = remember {
            !ModBleAdvProxyBridge.isStandalone(context)
        }
        val visibleSearchEntries = remember(showBluetoothSearch) {
            SettingsSearchCatalog.visible(
                showBrowser = DeviceFeatureManager.shouldShowBrowserSettings(),
                showScreensaver = DeviceFeatureManager.shouldShowScreensaverSettings(),
                showExperimental = DeviceFeatureManager.shouldShowExperimentalSettings(),
                showBluetooth = showBluetoothSearch,
                showCamera = DeviceFeatureManager.shouldShowCameraSettings(),
            )
        }
        val settingsSearchHits = if (includeSettingsSearch) {
            visibleSearchEntries.map { entry ->
                resolveSettingsSearchHit(
                    title = stringResource(entry.titleRes),
                    path = stringResource(entry.groupTitleRes),
                    subtitle = entry.subtitleRes?.let { stringResource(it) },
                    route = entry.route,
                )
            }
        } else {
            emptyList()
        }
        val settingsSearchResults = if (searching) {
            filterSettingsSearch(settingsSearchQuery, settingsSearchHits)
        } else {
            emptyList()
        }

        if (includeSettingsSearch) {
            Column(modifier = if (showSidebarHeader) Modifier else sidebarHeaderTopInset) {
                SettingsSidebarSearchField(
                    query = settingsSearchQuery,
                    onQueryChange = { settingsSearchQuery = it },
                    textColor = textColor,
                    secondaryColor = secondaryColor,
                    dividerColor = dividerColor,
                    sidebarScale = sidebarScale,
                )
            }
        }

        val sidebarPanelColor = if (isDarkMode) Color.Black else SlateBackground
        // Dissolve under the pinned search: rows fade as they scroll into that
        // seam. Glass used to skip this and only fade the panel corners, so
        // the list hard-cut into the search field.
        val showSidebarTopDissolve = includeSettingsSearch && if (sidebarGlass.enabled) {
            glassScrollPx > 2f
        } else {
            scrollState.maxValue > 0 && scrollState.value > 2
        }
        // Same seam as the search field: rows dissolve into the pinned dock
        // divider only while more list remains below it.
        val showSidebarBottomDissolve = sidebarSettings.showHome && if (sidebarGlass.enabled) {
            glassScrollMaxPx > 0 && glassScrollPx < glassScrollMaxPx - 2
        } else {
            scrollState.maxValue > 0 && scrollState.value < scrollState.maxValue - 2
        }
        val searchSeamFade = (16f * sidebarScale).dp
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val origin = coords.localToWindow(Offset.Zero)
                    val loc = IntArray(2)
                    hostView.getLocationOnScreen(loc)
                    val win = IntArray(2)
                    hostView.getLocationInWindow(win)
                    val left = loc[0] - win[0] + origin.x
                    val top = loc[1] - win[1] + origin.y
                    listScreenLeft = left
                    listScreenTop = top
                    listScreenRight = left + coords.size.width
                    listScreenBottom = top + coords.size.height
                }
                .dismissSettingsSearchFocusOnPress(
                    enabled = includeSettingsSearch,
                    focusManager = focusManager,
                    hideKeyboard = { keyboard?.hide() },
                )
                .then(
                    if (sidebarGlass.enabled) {
                        // Canvas clip, not clipToBounds(). verticalScroll's clip is a
                        // graphicsLayer FBO; light mode clears the unpainted gaps to
                        // white, so a sheet sits still while the rows move.
                        Modifier.drawWithContent {
                            val content = this
                            clipRect { content.drawContent() }
                        }.drawerVerticalEdgeDissolve(
                            showTop = showSidebarTopDissolve,
                            showBottom = showSidebarBottomDissolve,
                            fadeToColor = Color.Transparent,
                            fadeHeight = searchSeamFade,
                            glassMode = true,
                        )
                    } else {
                        Modifier.drawerVerticalEdgeDissolve(
                            showTop = showSidebarTopDissolve,
                            showBottom = showSidebarBottomDissolve,
                            fadeToColor = sidebarPanelColor,
                            fadeHeight = searchSeamFade,
                            preferScrim = true,
                        )
                    }
                )
        ) {
        // Kill the platform overscroll glow: on pre-Android-12 devices the grey
        // EdgeEffect arcs read as stray shadow masks at both ends of this viewport.
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        // The opening drawer sends D-pad focus here (LocalSidebarDrawerListFocus):
        // the function list is the entry point, chrome above it is skipped.
        val listFocusRequester = LocalSidebarDrawerListFocus.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (listFocusRequester != null) {
                        Modifier
                            .focusRequester(listFocusRequester)
                            .focusGroup()
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (sidebarGlass.enabled) {
                        Modifier.glassSidebarScroll(
                            glassScrollable,
                            glassScrollPx,
                            glassScrollMax,
                        ) { max ->
                            if (glassScrollMaxPx != max) glassScrollMaxPx = max
                        }
                    } else {
                        Modifier.verticalScroll(scrollState)
                    }
                )
                // Applied after verticalScroll: these insets belong to the content
                // and scroll away with it, so rows glide to the panel edge.
                .then(
                    if (includeSettingsSearch) {
                        Modifier.padding(bottom = if (sidebarGlass.enabled) 16.dp else 24.dp)
                    } else if (sidebarGlass.enabled) {
                        Modifier.padding(top = 16.dp, bottom = 16.dp)
                    } else {
                        Modifier
                            .statusBarsPadding()
                            .padding(top = 24.dp, bottom = 24.dp)
                    }
                )
        ) {
        if (!includeSettingsSearch && showSidebarHeader) {
            HomeSidebarTitleRow(
                isDarkMode = isDarkMode,
                textColor = textColor,
                sidebarScale = sidebarScale,
                interactiveSatelliteStatus = interactiveSatelliteStatus,
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        if (searching) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsSidebarSearchResults(
                results = settingsSearchResults,
                textColor = textColor,
                secondaryColor = secondaryColor,
                isDarkMode = isDarkMode,
                sidebarScale = sidebarScale,
                onResultClick = { hit ->
                    settingsSearchQuery = ""
                    dismissSearchInput()
                    requestClose()
                    (onSettingsSearchNavigate ?: onSettingsClick).invoke(hit.route)
                },
            )
        } else {
        var showedBrowserTools = false
        if (includeBrowserTools) {
            val showWebConsole = browserSettings.showBrowserWebConsole
            val showClearCache = browserSettings.showBrowserClearCache
            val showUserAgent = browserSettings.showBrowserUserAgent
            val showRemoteUrl = browserSettings.showBrowserRemoteUrl
            // Userscripts are WebView-only; gated by the sidebar-settings switch.
            val showTampermonkey = browserSettings.tampermonkeyEnabled &&
                BrowserEngine.effectiveEngine(context, browserSettings.browserEngine) != BrowserEngine.GECKO
            // Off by default in browser sidebar settings; placed above Web Console.
            val showHaKiosk = browserSettings.showBrowserHaKiosk &&
                onBrowserToggleHaKiosk != null
            val haKioskOn = browserSettings.isHaKioskModeOn()
            // Page-scale slider row, placed directly above the kiosk collapse row.
            val showPageZoom = browserSettings.showBrowserPageZoom
            // No card at all when every tool is off — empty rounded shell looks broken.
            if (showHaKiosk || showWebConsole || showClearCache || showUserAgent ||
                showRemoteUrl || showTampermonkey || showPageZoom
            ) {
                showedBrowserTools = true
                val toolsCardColor = if (isDarkMode) Color(0xFF1E2126) else Color(0xFFF1F5F9)
                val toolsCardShape = RoundedCornerShape(24.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (sidebarGlass.enabled) {
                                // Glass-on-glass inside the drawer: no second shadow, it
                                // would read as a card stacked on the panel.
                                Modifier.liquidGlass(
                                    sidebarGlass, toolsCardShape, isDarkMode, toolsCardColor,
                                    shadow = false,
                                )
                            } else {
                                Modifier.clip(toolsCardShape).background(toolsCardColor)
                            }
                        )
                        .padding(6.dp)
                ) {
                    if (showRemoteUrl) {
                        HomeSidebarEntryRow(
                            label = stringResource(R.string.settings_browser_ha_remote_url),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            selected = false,
                            showTrailingArrow = false,
                            icon = Icons.Outlined.Link,
                            onTintedSurface = true,
                            onClick = {
                                requestClose()
                                onBrowserRemoteUrl?.invoke()
                            }
                        )
                    }
                    if (showPageZoom) {
                        HomeSidebarPageZoomRow(
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            currentScale = browserSettings.initialScale,
                            onCommit = { value ->
                                coroutineScope.launch { browserStore.setInitialScale(value) }
                            },
                        )
                    }
                    if (showHaKiosk) {
                        // Visible chrome → "Kiosk 收起"; hidden → "Kiosk 展开".
                        HomeSidebarEntryRow(
                            label = stringResource(
                                if (haKioskOn) {
                                    R.string.browser_sidebar_ha_kiosk_expand
                                } else {
                                    R.string.browser_sidebar_ha_kiosk_collapse
                                },
                            ),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            selected = haKioskOn,
                            showTrailingArrow = false,
                            icon = if (haKioskOn) {
                                Icons.Outlined.FullscreenExit
                            } else {
                                Icons.Outlined.Fullscreen
                            },
                            onTintedSurface = true,
                            onClick = {
                                requestClose()
                                onBrowserToggleHaKiosk?.invoke()
                            }
                        )
                    }
                    if (showWebConsole) {
                        HomeSidebarEntryRow(
                            label = stringResource(R.string.browser_sidebar_web_console),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            selected = false,
                            showTrailingArrow = false,
                            icon = Icons.Outlined.Terminal,
                            onTintedSurface = true,
                            onClick = {
                                requestClose()
                                onBrowserWebConsole?.invoke()
                            }
                        )
                    }
                    if (showUserAgent) {
                        HomeSidebarEntryRow(
                            label = stringResource(R.string.settings_browser_useragent),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            selected = false,
                            showTrailingArrow = false,
                            icon = Icons.Outlined.Language,
                            onTintedSurface = true,
                            onClick = {
                                requestClose()
                                onBrowserUserAgent?.invoke()
                            }
                        )
                    }
                    if (showTampermonkey) {
                        HomeSidebarEntryRow(
                            label = stringResource(R.string.settings_browser_tampermonkey),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            selected = false,
                            showTrailingArrow = false,
                            iconRes = R.drawable.ic_tampermonkey,
                            onTintedSurface = true,
                            onClick = {
                                requestClose()
                                onBrowserTampermonkey?.invoke()
                            }
                        )
                    }
                    if (showClearCache) {
                        HomeSidebarEntryRow(
                            label = stringResource(R.string.browser_sidebar_clear_cache),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            selected = false,
                            showTrailingArrow = false,
                            icon = Icons.Outlined.CleaningServices,
                            onTintedSurface = true,
                            onClick = {
                                requestClose()
                                onBrowserClearCache?.invoke()
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // No search field on this host: the line under the header goes away with the header.
        // The settings search underline stays, even when the header is hidden.
        if (!includeSettingsSearch && (showSidebarHeader || showedBrowserTools)) {
            HorizontalDivider(color = dividerColor)
        }
        Spacer(modifier = Modifier.height(8.dp))

        val orderedKeys = remember(sidebarSettings.itemOrder) {
            sidebarItemOrderOrDefault(sidebarSettings.itemOrder)
        }
        val entryKeys = remember(orderedKeys) {
            orderedKeys.filter { it !in SIDEBAR_SWITCH_ITEM_KEYS }
        }
        val switchKeys = remember(orderedKeys) {
            orderedKeys.filter { it in SIDEBAR_SWITCH_ITEM_KEYS }
        }
        val videoRecordingAvailable =
            HomeSidebarActions.isVideoRecordingAvailable(context, experimentalSettings)
        var renderedAny = false
        entryKeys.forEach { key ->
            when (key) {
                SidebarItemKey.Home -> Unit
                SidebarItemKey.DeviceControl -> {
                    if (sidebarSettings.showDeviceControl) {
                        renderedAny = true
                        HomeSidebarEntryRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.DeviceControl, R.string.settings_device_control_title),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            // Navigation, not an overlay: no satellite or draw-over gate.
                            enabled = true,
                            selected = false,
                            onClick = {
                                requestClose()
                                goSettings(Screen.SETTINGS_SERVICE_DEVICE_CONTROL)
                            }
                        )
                    }
                }
                SidebarItemKey.TouchPad -> {
                    if (sidebarSettings.showTouchPad && HomeSidebarActions.isTouchPadAvailable()) {
                        renderedAny = true
                        HomeSidebarEntryRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.TouchPad, R.string.home_sidebar_touch_pad),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            enabled = true,
                            selected = touchPadVisible,
                            onClick = {
                                requestClose()
                                HomeSidebarActions.toggleTouchPad(context)
                            }
                        )
                    }
                }
                SidebarItemKey.VoiceMessage -> {
                    if (sidebarSettings.showVoiceMessage && HomeSidebarActions.isVoiceMessageAvailable(playerSettings)) {
                        renderedAny = true
                        HomeSidebarEntryRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.VoiceMessage, R.string.home_sidebar_voice_message),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            enabled = satelliteStarted,
                            selected = HomeSidebarActions.isVoiceMessageSelected(playerSettings),
                            onClick = {
                                onFeatureClick {
                                    HomeSidebarActions.toggleVoiceMessage(playerStore)
                                }
                            }
                        )
                    }
                }
                SidebarItemKey.Browser -> {
                    if (sidebarSettings.showBrowser && HomeSidebarActions.isBrowserAvailable(browserSettings)) {
                        renderedAny = true
                        HomeSidebarEntryRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.Browser, R.string.home_sidebar_browser),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            enabled = satelliteStarted,
                            selected = HomeSidebarActions.isBrowserSelected(browserSettings),
                            onClick = {
                                onFeatureClick {
                                    HomeSidebarActions.toggleBrowser(browserStore)
                                }
                            }
                        )
                    }
                }
                SidebarItemKey.Weather -> {
                    if (sidebarSettings.showWeather && HomeSidebarActions.isWeatherAvailable(playerSettings)) {
                        renderedAny = true
                        HomeSidebarEntryRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.Weather, R.string.home_sidebar_weather),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            enabled = satelliteStarted,
                            selected = HomeSidebarActions.isWeatherSelected(playerSettings),
                            onClick = {
                                onFeatureClick {
                                    HomeSidebarActions.toggleWeather(playerStore)
                                }
                            }
                        )
                    }
                }
                SidebarItemKey.SimpleClock -> {
                    if (sidebarSettings.showSimpleClock && HomeSidebarActions.isSimpleClockAvailable(playerSettings)) {
                        renderedAny = true
                        HomeSidebarEntryRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.SimpleClock, R.string.home_sidebar_simple_clock),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            enabled = satelliteStarted,
                            selected = HomeSidebarActions.isSimpleClockSelected(playerSettings),
                            onClick = {
                                onFeatureClick {
                                    HomeSidebarActions.toggleSimpleClock(playerStore)
                                }
                            }
                        )
                    }
                }
                SidebarItemKey.DreamClock -> {
                    if (sidebarSettings.showDreamClock && HomeSidebarActions.isDreamClockAvailable(playerSettings)) {
                        renderedAny = true
                        HomeSidebarEntryRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.DreamClock, R.string.home_sidebar_dream_clock),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            enabled = satelliteStarted,
                            selected = HomeSidebarActions.isDreamClockSelected(playerSettings),
                            onClick = {
                                onFeatureClick {
                                    HomeSidebarActions.toggleDreamClock(playerStore)
                                }
                            }
                        )
                    }
                }
                SidebarItemKey.QuickEntity -> {
                    if (sidebarSettings.showQuickEntity && HomeSidebarActions.isQuickEntityAvailable(quickEntitySettings)) {
                        renderedAny = true
                        HomeSidebarEntryRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.QuickEntity, R.string.home_sidebar_quick_entity),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            enabled = satelliteStarted,
                            selected = HomeSidebarActions.isQuickEntitySelected(quickEntitySettings),
                            onClick = {
                                onFeatureClick {
                                    HomeSidebarActions.toggleQuickEntity(context, quickEntityStore)
                                }
                            }
                        )
                    }
                }
                SidebarItemKey.VinylCoverDisplay -> {
                    if (sidebarSettings.showVinylCoverDisplay &&
                        HomeSidebarActions.isVinylCoverDisplayAvailable(playerSettings)
                    ) {
                        renderedAny = true
                        HomeSidebarEntryRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.VinylCoverDisplay, R.string.home_sidebar_vinyl_cover_display),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            enabled = satelliteStarted,
                            selected = HomeSidebarActions.isVinylCoverDisplaySelected(playerSettings),
                            onClick = {
                                onFeatureClick {
                                    HomeSidebarActions.toggleVinylCoverDisplay(playerStore)
                                }
                            }
                        )
                    }
                }
                SidebarItemKey.HomeLock -> {
                    if (sidebarSettings.showHomeLock &&
                        HomeSidebarActions.isHomeLockAvailable(homeLockSettings)
                    ) {
                        renderedAny = true
                        HomeSidebarEntryRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.HomeLock, R.string.home_sidebar_home_lock),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            isDarkMode = isDarkMode,
                            sidebarScale = sidebarScale,
                            enabled = true,
                            selected = false,
                            onClick = {
                                HomeLockSession.lock()
                                requestClose()
                            }
                        )
                    }
                }
                SidebarItemKey.Camera,
                SidebarItemKey.MuteMicrophone,
                SidebarItemKey.DarkMode -> Unit
            }
        }
        var switchSectionStarted = false
        switchKeys.forEach { key ->
            when (key) {
                SidebarItemKey.Camera -> {
                    if (sidebarSettings.showCamera && videoRecordingAvailable) {
                        if (renderedAny && !switchSectionStarted) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(modifier = sectionDividerModifier, color = dividerColor)
                            Spacer(modifier = Modifier.height(8.dp))
                            switchSectionStarted = true
                        }
                        renderedAny = true
                        HomeSidebarSwitchRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.Camera, R.string.home_sidebar_camera),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            sidebarScale = sidebarScale,
                            glassEnabled = sidebarGlass.enabled,
                            checked = videoRecordingEnabled,
                            enabled = satelliteStarted,
                            onCheckedChange = { enabled ->
                                coroutineScope.launch {
                                    HomeSidebarActions.setVideoRecording(context, enabled)
                                }
                            }
                        )
                    }
                }
                SidebarItemKey.MuteMicrophone -> {
                    if (sidebarSettings.showMuteMicrophone && HomeSidebarActions.isMuteMicrophoneAvailable()) {
                        if (renderedAny && !switchSectionStarted) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(modifier = sectionDividerModifier, color = dividerColor)
                            Spacer(modifier = Modifier.height(8.dp))
                            switchSectionStarted = true
                        }
                        renderedAny = true
                        HomeSidebarSwitchRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.MuteMicrophone, R.string.home_sidebar_mute_microphone),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            sidebarScale = sidebarScale,
                            glassEnabled = sidebarGlass.enabled,
                            checked = HomeSidebarActions.isMuteMicrophoneSelected(microphoneSettings),
                            enabled = satelliteStarted,
                            onCheckedChange = { muted ->
                                coroutineScope.launch {
                                    HomeSidebarActions.setMuteMicrophone(muted)
                                }
                            }
                        )
                    }
                }
                SidebarItemKey.DarkMode -> {
                    if (sidebarSettings.showDarkMode && HomeSidebarActions.isDarkModeAvailable()) {
                        if (renderedAny && !switchSectionStarted) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(modifier = sectionDividerModifier, color = dividerColor)
                            Spacer(modifier = Modifier.height(8.dp))
                            switchSectionStarted = true
                        }
                        renderedAny = true
                        HomeSidebarSwitchRow(
                            label = sidebarLabel(sidebarSettings, SidebarItemKey.DarkMode, R.string.home_sidebar_dark_mode),
                            textColor = textColor,
                            secondaryColor = secondaryColor,
                            sidebarScale = sidebarScale,
                            glassEnabled = sidebarGlass.enabled,
                            checked = HomeSidebarActions.isDarkModeSelected(context),
                            enabled = true,
                            onCheckedChange = { enabled ->
                                HomeSidebarActions.setDarkMode(context, enabled)
                            }
                        )
                    }
                }
                else -> Unit
            }
        }

        // The pinned home/settings dock draws its own top rule. A second line here
        // sits directly under Mute Microphone, the last switch row.
        if (renderedAny && !sidebarSettings.showHome) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(modifier = sectionDividerModifier, color = dividerColor)
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (!sidebarSettings.showHome) {
            HomeSidebarEntryRow(
                label = sidebarSettings.customLabel(SIDEBAR_SETTINGS_LABEL_KEY)
                    ?: stringResource(R.string.label_settings),
                textColor = textColor,
                secondaryColor = secondaryColor,
                isDarkMode = isDarkMode,
                sidebarScale = sidebarScale,
                selected = false,
                showTrailingArrow = false,
                onClick = {
                    requestClose()
                    goSettings(Screen.SETTINGS)
                }
            )
        }
        }
        }
        }
        }
        if (sidebarSettings.showHome) {
            val homeLabel = sidebarLabel(sidebarSettings, SidebarItemKey.Home, R.string.home_sidebar_home)
            val settingsLabel = sidebarSettings.customLabel(SIDEBAR_SETTINGS_LABEL_KEY)
                ?: stringResource(R.string.label_settings)
            val dockButtons = sidebarDockOrderOrDefault(sidebarSettings.dockOrder).map { key ->
                when (key) {
                    SidebarDockKey.Home -> HomeSidebarDockButton(
                        label = homeLabel,
                        iconRes = R.drawable.ic_ava_logo,
                        liveServiceStatus = serviceButtonActive,
                        onClick = {
                            if (serviceButtonActive) {
                                toggleMainService()
                            } else {
                                requestClose()
                                coroutineScope.launch {
                                    HomeSidebarActions.openHome(context, browserStore)
                                }
                            }
                        },
                    )
                    SidebarDockKey.Settings -> HomeSidebarDockButton(
                        label = settingsLabel,
                        iconRes = R.drawable.settings_24px,
                        onClick = {
                            requestClose()
                            goSettings(Screen.SETTINGS)
                        },
                    )
                }
            }
            HomeSidebarBottomDock(
                buttons = dockButtons,
                isDarkMode = isDarkMode,
                secondaryColor = secondaryColor,
                dividerColor = dividerColor,
                sidebarScale = sidebarScale,
                glassEnabled = sidebarGlass.enabled,
            )
        }
    }
    }
}

@Composable
private fun Modifier.dismissSettingsSearchFocusOnPress(
    enabled: Boolean,
    focusManager: FocusManager,
    hideKeyboard: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(focusManager) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.changedToDown() }) {
                    focusManager.clearFocus(force = true)
                    hideKeyboard()
                }
            }
        }
    }
}

private data class HomeSidebarDockButton(
    val label: String,
    val iconRes: Int,
    val onClick: () -> Unit,
    val liveServiceStatus: Boolean = false,
)

@Composable
private fun HomeSidebarBottomDock(
    buttons: List<HomeSidebarDockButton>,
    isDarkMode: Boolean,
    secondaryColor: Color,
    dividerColor: Color,
    sidebarScale: Float,
    glassEnabled: Boolean,
) {
    val mark = if (isDarkMode) Color.White else Color(0xFF5C5C5C)
    val iconSize = (22f * sidebarScale).dp
    val labelSize = (12f * sidebarScale).sp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (glassEnabled) Modifier else Modifier.navigationBarsPadding()),
    ) {
        HorizontalDivider(color = dividerColor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 8.dp),
        ) {
            buttons.forEach { button ->
                HomeSidebarDockCell(
                    label = button.label,
                    iconRes = button.iconRes,
                    mark = mark,
                    labelColor = secondaryColor,
                    iconSize = iconSize,
                    labelSize = labelSize,
                    onClick = button.onClick,
                    liveServiceStatus = button.liveServiceStatus,
                    isDarkMode = isDarkMode,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HomeSidebarDockCell(
    label: String,
    iconRes: Int,
    mark: Color,
    labelColor: Color,
    iconSize: androidx.compose.ui.unit.Dp,
    labelSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
    liveServiceStatus: Boolean = false,
    isDarkMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(top = 10.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = mark,
            modifier = Modifier.size(iconSize),
        )
        if (liveServiceStatus) {
            VoiceSatelliteCompactStatus(
                isDarkMode = isDarkMode,
                interactive = false,
            )
        } else {
            Text(
                text = label,
                color = labelColor,
                fontSize = labelSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HomeSidebarTitleRow(
    isDarkMode: Boolean,
    textColor: Color,
    sidebarScale: Float,
    interactiveSatelliteStatus: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.home_sidebar_title),
            color = textColor,
            fontSize = HomeSidebarMetrics.titleTextSize(sidebarScale),
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.5).sp,
            modifier = Modifier.weight(1f, fill = false)
        )
        // Touch-only: no focus ring exists here, so remote focus must never
        // land on the chip (it sits above the function list in D-pad order).
        Box(modifier = Modifier.focusProperties { canFocus = false }) {
            VoiceSatelliteCompactStatus(
                isDarkMode = isDarkMode,
                interactive = interactiveSatelliteStatus
            )
        }
    }
}

@Composable
private fun rememberHomeSidebarSatelliteStarted(): Boolean {
    if (EngineCapabilities.GECKO_BUNDLED) {
        GeckoSatelliteStatusHolder.started.collectAsStateWithLifecycle(false)
        return true
    }
    // Observe soft-start flag only — do not bind/AUTO_CREATE just to paint the sidebar.
    val started by VoiceSatelliteService.satelliteStarted.collectAsStateWithLifecycle(false)
    return started
}

/**
 * Page-scale slider (same 0–500% range as the `browser_scale` HA number entity).
 * Both surfaces read and write BrowserSettings.initialScale, so HA-side moves show
 * up here live and vice versa. Committing only on release matters: WebViewService
 * hard-reloads every pane when initialScale changes, so per-tick commits would
 * reload the page dozens of times per drag. 0 keeps its existing "auto viewport"
 * meaning and is labelled instead of shown as 0%.
 */
@Composable
private fun HomeSidebarPageZoomRow(
    textColor: Color,
    secondaryColor: Color,
    isDarkMode: Boolean,
    sidebarScale: Float,
    currentScale: Int,
    onCommit: (Int) -> Unit,
) {
    // Collapsed by default: renders as a regular icon entry row (same metrics as
    // HomeSidebarEntryRow) with the live value trailing; tapping expands the
    // slider in place instead of parking a bare slider in the list.
    var expanded by remember { mutableStateOf(false) }
    // Live drag value; null when idle so external (HA entity) changes show through.
    var dragValue by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(currentScale) { dragValue = null }
    val shown = (dragValue ?: currentScale.toFloat()).coerceIn(0f, 500f)
    val shownInt = shown.roundToInt()
    val shape = RoundedCornerShape(HomeSidebarMetrics.rowCornerRadius(sidebarScale))
    val iconSize = HomeSidebarMetrics.iconSize(sidebarScale)
    val interactionSource = rememberSidebarRowInteractionSource(
        pressGeneration = LocalSidebarPressGeneration.current,
        cancelPress = LocalSidebarCancelPress.current,
    )
    // Tools-shell ripple strength (row always sits on the tinted tools card).
    val rippleColor = if (isDarkMode) {
        Color.White.copy(alpha = 0.18f)
    } else {
        Color.Black.copy(alpha = 0.12f)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .settingsFocusHighlight(
                    cornerRadius = HomeSidebarMetrics.rowCornerRadius(sidebarScale),
                    horizontalOutset = 0.dp,
                )
                .clip(shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = rippleColor),
                    onClick = { expanded = !expanded },
                )
                .padding(
                    horizontal = HomeSidebarMetrics.entryPaddingHorizontal(sidebarScale),
                    vertical = HomeSidebarMetrics.entryPaddingVertical(sidebarScale),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                HomeSidebarMetrics.entryPaddingHorizontal(sidebarScale)
            )
        ) {
            Icon(
                imageVector = Icons.Outlined.ZoomIn,
                contentDescription = null,
                tint = secondaryColor,
                modifier = Modifier.size(iconSize)
            )
            Text(
                text = stringResource(R.string.settings_browser_initial_scale),
                color = textColor,
                fontSize = HomeSidebarMetrics.entryTextSize(sidebarScale),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            // Value only while expanded; collapsed row stays as clean as its siblings.
            if (expanded) {
                Text(
                    text = if (shownInt == 0) {
                        stringResource(R.string.browser_sidebar_page_zoom_auto)
                    } else {
                        "$shownInt%"
                    },
                    color = secondaryColor,
                    fontSize = HomeSidebarMetrics.entryTextSize(sidebarScale),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            TickSlider(
                value = shown,
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    val landed = dragValue ?: return@TickSlider
                    // 5% detents; keeps values tidy across both control surfaces.
                    val snapped = ((landed / 5f).roundToInt() * 5).coerceIn(0, 500)
                    dragValue = snapped.toFloat()
                    if (snapped != currentScale) onCommit(snapped)
                },
                valueRange = 0f..500f,
                colors = SliderDefaults.colors(
                    thumbColor = getAccentColor(),
                    activeTrackColor = getAccentColor(),
                    inactiveTrackColor = getSliderInactiveColor(),
                ),
                modifier = Modifier
                    .sidebarDrawerFocusable()
                    .padding(
                        horizontal = HomeSidebarMetrics.entryPaddingHorizontal(sidebarScale),
                    ),
            )
        }
    }
}

@Composable
private fun HomeSidebarSwitchRow(
    label: String,
    textColor: Color,
    secondaryColor: Color,
    sidebarScale: Float,
    glassEnabled: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Lights up when the inner Switch (the actual focus target) has focus.
            .settingsFocusHighlight(
                cornerRadius = HomeSidebarMetrics.rowCornerRadius(sidebarScale),
                horizontalOutset = 0.dp,
            )
            .padding(
                horizontal = HomeSidebarMetrics.entryPaddingHorizontal(sidebarScale),
                vertical = HomeSidebarMetrics.switchPaddingVertical(sidebarScale),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = if (enabled) textColor else secondaryColor,
            fontSize = HomeSidebarMetrics.entryTextSize(sidebarScale),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (glassEnabled) {
            LiquidGlassSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        } else {
            ModernSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun HomeSidebarEntryRow(
    label: String,
    textColor: Color,
    secondaryColor: Color,
    isDarkMode: Boolean,
    sidebarScale: Float,
    enabled: Boolean = true,
    selected: Boolean,
    onClick: () -> Unit,
    iconRes: Int? = null,
    icon: ImageVector? = null,
    showTrailingArrow: Boolean = true,
    /** Stronger press ripple when the row sits on a tinted card (tools shell). */
    onTintedSurface: Boolean = false,
) {
    val selectedBackground = when {
        onTintedSurface && isDarkMode -> Color(0xFF3A3F48)
        onTintedSurface -> Color(0xFFE2E8F0)
        isDarkMode -> Color(0xFF2A2D33)
        else -> Color(0xFFF1F5F9)
    }
    val shape = RoundedCornerShape(HomeSidebarMetrics.rowCornerRadius(sidebarScale))
    val labelColor = if (enabled) textColor else secondaryColor
    val iconColor = if (enabled) secondaryColor else secondaryColor.copy(alpha = 0.6f)
    val showSelected = enabled && selected
    val iconSize = HomeSidebarMetrics.iconSize(sidebarScale)
    val interactionSource = rememberSidebarRowInteractionSource(
        pressGeneration = LocalSidebarPressGeneration.current,
        cancelPress = LocalSidebarCancelPress.current,
    )
    val rippleColor = if (isDarkMode) {
        Color.White.copy(alpha = if (onTintedSurface) 0.18f else 0.12f)
    } else {
        Color.Black.copy(alpha = if (onTintedSurface) 0.12f else 0.08f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            // Remote focus ring; before clip so the outline is not cut off.
            .settingsFocusHighlight(
                cornerRadius = HomeSidebarMetrics.rowCornerRadius(sidebarScale),
                horizontalOutset = 0.dp,
            )
            .clip(shape)
            .background(if (showSelected) selectedBackground else Color.Transparent)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(color = rippleColor),
                onClick = onClick,
            )
            .padding(
                horizontal = HomeSidebarMetrics.entryPaddingHorizontal(sidebarScale),
                vertical = HomeSidebarMetrics.entryPaddingVertical(sidebarScale),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HomeSidebarMetrics.entryPaddingHorizontal(sidebarScale))
    ) {
        when {
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(iconSize)
            )
            iconRes != null -> Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(iconSize)
            )
        }
        Text(
            text = label,
            color = labelColor,
            fontSize = HomeSidebarMetrics.entryTextSize(sidebarScale),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (showTrailingArrow) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * Scroll without [verticalScroll]'s clipToBounds / placeWithLayer. Those are
 * RenderNodes; in light mode the FBO clears to the window's white, and that
 * sheet sits behind the rows on translucent glass.
 */
private fun Modifier.glassSidebarScroll(
    state: ScrollableState,
    offsetPx: Float,
    maxHolder: IntArray,
    onMaxPx: (Int) -> Unit,
): Modifier = this
    .scrollable(state = state, orientation = Orientation.Vertical)
    .layout { measurable, constraints ->
        val placeable = measurable.measure(
            constraints.copy(maxHeight = Constraints.Infinity)
        )
        val viewportH = constraints.maxHeight
        val width = placeable.width.coerceAtMost(constraints.maxWidth)
        val max = (placeable.height - viewportH).coerceAtLeast(0)
        maxHolder[0] = max
        onMaxPx(max)
        val y = -offsetPx.roundToInt().coerceIn(0, max)
        layout(width, viewportH) {
            placeable.placeRelative(0, y)
        }
    }

