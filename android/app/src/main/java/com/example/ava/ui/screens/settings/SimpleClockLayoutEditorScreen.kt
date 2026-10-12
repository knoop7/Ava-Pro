package com.example.ava.ui.screens.settings

import android.content.Context
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.R
import com.example.ava.services.ScreensaverView
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.SimpleClockCustomLayout
import com.example.ava.settings.SimpleClockLayoutElement
import com.example.ava.settings.SimpleClockLayoutElementId
import com.example.ava.settings.SimpleClockPortraitStyle
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.resolveScreensaverWeatherEntityId
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ava.ui.AvaToast
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.example.ava.ui.VoiceAccentColors
import com.example.ava.ui.avaContentWindowInsets
import com.example.ava.ui.screens.settings.components.CustomAccentColorDialog
import com.example.ava.ui.screens.settings.components.CustomColorSwatch
import com.example.ava.ui.screens.settings.components.HorizontalDissolveText
import com.example.ava.ui.screens.settings.components.PresetColorSwatch
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollRow
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.ui.screens.settings.components.SettingSliderLabelRow
import com.example.ava.ui.haptic.TickSlider
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private typealias LayoutEditorMap = Map<SimpleClockLayoutElementId, SimpleClockLayoutElement>

private val LayoutEditorGuideColor = Color(0xFFFF4FD8)
private val LayoutEditorHandleColor = Color(0x66FFFFFF)
private val LayoutEditorHandleColorLight = Color(0x40000000)

/**
 * Remembered editor panel size, as a fraction of the editor area (same idea as the
 * launcher's remembered page geometry: plain SharedPreferences, one key per edge).
 */
private object LayoutEditorPanelPrefs {
    private const val PREFS = "simple_clock_layout_editor_panel"
    private const val KEY_WIDTH = "side_width_fraction"
    private const val KEY_HEIGHT = "bottom_height_fraction"
    const val DEFAULT_WIDTH = 0.48f
    const val DEFAULT_HEIGHT = 0.42f
    const val MAX_WIDTH = 0.65f
    const val MAX_HEIGHT = 0.62f

    fun width(context: Context): Float =
        prefs(context).getFloat(KEY_WIDTH, DEFAULT_WIDTH).takeIf { it.isFinite() } ?: DEFAULT_WIDTH

    fun height(context: Context): Float =
        prefs(context).getFloat(KEY_HEIGHT, DEFAULT_HEIGHT).takeIf { it.isFinite() } ?: DEFAULT_HEIGHT

    fun saveWidth(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_WIDTH, value).apply()
    }

    fun saveHeight(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_HEIGHT, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Simple Clock custom layout editor (v1: landscape + simple portrait).
 *
 * The preview is the real [ScreensaverView] laid out at the device's real pixel size for
 * the chosen orientation and scaled down to fit, so what you see is what the clock draws.
 * Edits stay local until Save writes both orientations in one DataStore update.
 */
@Composable
fun SimpleClockLayoutEditorScreen(
    onBack: () -> Unit,
    onOpenStatusSettings: () -> Unit = {},
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val coroutineScope = rememberCoroutineScope()
    val store = remember { PlayerSettingsStore(context.playerSettingsStore) }
    val settings by store.getFlow().collectAsStateWithLifecycle(initialValue = null)
    // Display tab rows are enabled once the service settings load, like the settings pages.
    val satelliteState by settingsViewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val settingsEnabled = satelliteState != null
    val accent = getAccentColor()

    // Real panel size (status / nav bars included), like the overlay window uses.
    val realSize = remember(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }
    val longSide = max(realSize.first, realSize.second)
    val shortSide = min(realSize.first, realSize.second)

    // Saveable: opening the status slots page from the Display tab must not drop unsaved edits.
    var loaded by rememberSaveable { mutableStateOf(false) }
    var landscape by rememberSaveable(stateSaver = LayoutEditorMapSaver) { mutableStateOf<LayoutEditorMap>(emptyMap()) }
    var portrait by rememberSaveable(stateSaver = LayoutEditorMapSaver) { mutableStateOf<LayoutEditorMap>(emptyMap()) }
    var savedLandscape by rememberSaveable(stateSaver = LayoutEditorMapSaver) { mutableStateOf<LayoutEditorMap>(emptyMap()) }
    var savedPortrait by rememberSaveable(stateSaver = LayoutEditorMapSaver) { mutableStateOf<LayoutEditorMap>(emptyMap()) }
    var editPortrait by rememberSaveable { mutableStateOf(realSize.second > realSize.first) }
    // Open with Time selected so the element controls start expanded.
    var selected by remember { mutableStateOf<SimpleClockLayoutElementId?>(SimpleClockLayoutElementId.TIME) }
    var frame by remember { mutableStateOf<ScreensaverView.CustomLayoutFrame?>(null) }
    var guidesX by remember { mutableStateOf<List<Float>>(emptyList()) }
    var guidesY by remember { mutableStateOf<List<Float>>(emptyList()) }
    var previewOnly by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var panelWidthFraction by remember { mutableFloatStateOf(LayoutEditorPanelPrefs.width(context)) }
    var panelHeightFraction by remember { mutableFloatStateOf(LayoutEditorPanelPrefs.height(context)) }

    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        val stored = store.get()
        landscape = SimpleClockCustomLayout.toMap(stored.screensaverCustomLayoutLandscape)
        portrait = SimpleClockCustomLayout.toMap(stored.screensaverCustomLayoutPortrait)
        savedLandscape = landscape
        savedPortrait = portrait
        loaded = true
    }

    // External writes (Home Assistant text, Fleet) show up live: a clean editor
    // reloads; with unsaved edits only the free text blocks that changed outside take the new content.
    val storedLandscape = settings?.screensaverCustomLayoutLandscape
    val storedPortrait = settings?.screensaverCustomLayoutPortrait
    LaunchedEffect(storedLandscape, storedPortrait) {
        if (!loaded || storedLandscape == null || storedPortrait == null) return@LaunchedEffect
        val newLandscape = SimpleClockCustomLayout.toMap(storedLandscape)
        val newPortrait = SimpleClockCustomLayout.toMap(storedPortrait)
        if (newLandscape == savedLandscape && newPortrait == savedPortrait) return@LaunchedEffect
        if (landscape == savedLandscape && portrait == savedPortrait) {
            landscape = newLandscape
            portrait = newPortrait
        } else {
            landscape = layoutEditorMergeExternalText(landscape, savedLandscape, newLandscape)
            portrait = layoutEditorMergeExternalText(portrait, savedPortrait, newPortrait)
        }
        savedLandscape = newLandscape
        savedPortrait = newPortrait
        selected?.let { id -> if (id.isCustomText && id !in (if (editPortrait) portrait else landscape)) selected = null }
    }

    val targetWidth = if (editPortrait) shortSide else longSide
    val targetHeight = if (editPortrait) longSide else shortSide
    val dirty = loaded && (landscape != savedLandscape || portrait != savedPortrait)
    val current: LayoutEditorMap = if (editPortrait) portrait else landscape
    val liveFrame = frame?.takeIf {
        it.width == targetWidth && it.height == targetHeight && it.portrait == editPortrait
    }

    fun setCurrent(map: LayoutEditorMap) {
        if (editPortrait) portrait = map else landscape = map
    }

    fun updateElement(
        id: SimpleClockLayoutElementId,
        transform: (SimpleClockLayoutElement) -> SimpleClockLayoutElement?,
    ) {
        // Read state live: the gesture coroutine outlives the composition that made it.
        val live = if (editPortrait) portrait else landscape
        val base = live[id] ?: SimpleClockLayoutElement(id = id.key)
        val next = transform(base)?.copy(id = id.key)
        val map = live.toMutableMap()
        if (next == null || SimpleClockCustomLayout.isBuiltIn(next)) {
            map.remove(id)
        } else {
            map[id] = next
        }
        setCurrent(map)
    }

    val savedToast = stringResource(R.string.settings_simple_clock_layout_saved)
    val newTextLabel = stringResource(R.string.settings_simple_clock_layout_element_text)
    val previewHint = stringResource(R.string.settings_simple_clock_layout_preview_exit_hint)

    fun save(thenExit: Boolean) {
        val landscapeList = SimpleClockCustomLayout.fromMap(landscape)
        val portraitList = SimpleClockCustomLayout.fromMap(portrait)
        coroutineScope.launch {
            val before = store.get()
            store.saveScreensaverCustomLayout(
                landscape = landscapeList,
                portrait = portraitList,
            )
            // One HA text entity per free text block: adding / deleting blocks needs an HA rediscover.
            if (before.enableScreensaver &&
                SimpleClockCustomLayout.customTextIds(landscapeList, portraitList) !=
                SimpleClockCustomLayout.customTextIds(
                    before.screensaverCustomLayoutLandscape,
                    before.screensaverCustomLayoutPortrait,
                )
            ) {
                restartVoiceSatelliteServiceIfRunning()
            }
            savedLandscape = landscape
            savedPortrait = portrait
            AvaToast.show(context, savedToast)
            if (thenExit) onBack()
        }
    }

    BackHandler {
        when {
            previewOnly -> previewOnly = false
            dirty -> showDiscardDialog = true
            else -> onBack()
        }
    }

    // ---------- The real clock view ----------
    val clockView = remember { ScreensaverView(context, targetWidth, targetHeight) }
    DisposableEffect(clockView) {
        clockView.setLayoutEditorMode(true)
        clockView.onCustomLayoutFrame = { reported -> frame = reported }
        clockView.startUpdates()
        onDispose {
            clockView.onCustomLayoutFrame = null
            clockView.stopUpdates()
        }
    }
    LaunchedEffect(clockView, settings) {
        val s = settings ?: return@LaunchedEffect
        applyLayoutEditorClockSettings(clockView, s)
    }
    SideEffect {
        clockView.setCustomLayout(
            landscape = SimpleClockCustomLayout.fromMap(landscape),
            portrait = SimpleClockCustomLayout.fromMap(portrait),
        )
    }

    val configLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Keep the side panel and its buttons clear of nav bar / cutout (same insets
            // the other settings edit screens use).
            .windowInsetsPadding(avaContentWindowInsets(configLandscape).union(WindowInsets.displayCutout))
    ) {
        val deviceLandscape = maxWidth > maxHeight
        val showControls = !previewOnly
        // Side panel ~40% wide by default, bottom panel ~42% tall; both drag-resizable and
        // remembered. Never narrower / shorter than the controls need, never past ~55–62%.
        val maxPanelWidth = maxWidth * LayoutEditorPanelPrefs.MAX_WIDTH
        val minPanelWidth = minOf(240.dp, maxPanelWidth)
        val panelWidth = (maxWidth * panelWidthFraction).coerceIn(minPanelWidth, maxPanelWidth)
        val maxPanelHeight = maxHeight * LayoutEditorPanelPrefs.MAX_HEIGHT
        val minPanelHeight = minOf(200.dp, maxPanelHeight)
        val panelHeight = (maxHeight * panelHeightFraction).coerceIn(minPanelHeight, maxPanelHeight)
        val totalWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val totalHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val minWidthFraction = minPanelWidth / maxWidth
        val minHeightFraction = minPanelHeight / maxHeight
        val areaWidthDp = (if (showControls && deviceLandscape) maxWidth - panelWidth else maxWidth)
            .coerceAtLeast(1.dp)
        val areaHeightDp = (if (showControls && !deviceLandscape) maxHeight - panelHeight else maxHeight)
            .coerceAtLeast(1.dp)
        val areaWidthPx = with(density) { areaWidthDp.toPx() }.coerceAtLeast(1f)
        val areaHeightPx = with(density) { areaHeightDp.toPx() }.coerceAtLeast(1f)
        val fitScale = min(areaWidthPx / targetWidth, areaHeightPx / targetHeight)
            .coerceAtLeast(0.01f)
        val previewWidthDp = with(density) { (targetWidth * fitScale).toDp() }
        val previewHeightDp = with(density) { (targetHeight * fitScale).toDp() }
        val viewWidthDp = with(density) { targetWidth.toDp() }
        val viewHeightDp = with(density) { targetHeight.toDp() }

        val preview: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .size(areaWidthDp, areaHeightDp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(previewWidthDp, previewHeightDp)
                        .clipToBounds()
                        .border(1.dp, if (previewOnly) Color.Transparent else Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { clockView },
                        modifier = Modifier
                            .requiredSize(viewWidthDp, viewHeightDp)
                            .graphicsLayer {
                                scaleX = fitScale
                                scaleY = fitScale
                            }
                    )
                    if (previewOnly) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { previewOnly = false }
                        )
                    } else {
                        LayoutEditorOverlay(
                            frame = liveFrame,
                            selected = selected,
                            scale = fitScale,
                            guidesX = guidesX,
                            guidesY = guidesY,
                            accent = accent,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(fitScale, targetWidth, targetHeight, editPortrait) {
                                    val touchSlop = 12.dp.toPx() / fitScale
                                    val snapPx = 8.dp.toPx() / fitScale
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val f = layoutEditorLiveFrame(frame, targetWidth, targetHeight, editPortrait)
                                            ?: return@awaitEachGesture
                                        val hit = layoutEditorHitTest(
                                            f,
                                            down.position.x / fitScale,
                                            down.position.y / fitScale,
                                            touchSlop
                                        )
                                        selected = hit
                                        if (hit == null || !f.active) return@awaitEachGesture
                                        val base = f.defaultBounds[hit] ?: return@awaitEachGesture
                                        val start = (if (editPortrait) portrait else landscape)[hit]
                                        val moved = start != null && start.hasPosition
                                        var rawX = if (moved && start != null) start.x * targetWidth else base.centerX()
                                        var rawY = if (moved && start != null) start.y * targetHeight else base.centerY()
                                        var rawScale = start?.scale ?: 1f
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.changes.none { it.pressed }) break
                                            val pan = event.calculatePan()
                                            val zoom = event.calculateZoom()
                                            if (pan != Offset.Zero || zoom != 1f) {
                                                rawX += pan.x / fitScale
                                                rawY += pan.y / fitScale
                                                rawScale = (rawScale * zoom).coerceIn(
                                                    SimpleClockCustomLayout.MIN_SCALE,
                                                    SimpleClockCustomLayout.MAX_SCALE
                                                )
                                                val halfW = base.width() * rawScale / 2f
                                                val halfH = base.height() * rawScale / 2f
                                                val snapped = layoutEditorSnap(
                                                    frame = f,
                                                    id = hit,
                                                    centerX = rawX,
                                                    centerY = rawY,
                                                    halfWidth = halfW,
                                                    halfHeight = halfH,
                                                    threshold = snapPx,
                                                )
                                                guidesX = snapped.guidesX
                                                guidesY = snapped.guidesY
                                                val nx = (snapped.x / targetWidth).coerceIn(0f, 1f)
                                                val ny = (snapped.y / targetHeight).coerceIn(0f, 1f)
                                                val newScale = rawScale
                                                updateElement(hit) {
                                                    it.copy(x = nx, y = ny, scale = newScale, visible = true)
                                                }
                                            }
                                            event.changes.forEach { change ->
                                                if (change.positionChanged()) change.consume()
                                            }
                                        }
                                        guidesX = emptyList()
                                        guidesY = emptyList()
                                    }
                                }
                        )
                    }
                }
            }
        }

        val controls: @Composable (Modifier) -> Unit = { modifier ->
            LayoutEditorControls(
                modifier = modifier,
                accent = accent,
                editPortrait = editPortrait,
                onOrientationChange = {
                    editPortrait = it
                    selected = null
                },
                dirty = dirty,
                onSave = { save(thenExit = false) },
                onClose = { if (dirty) showDiscardDialog = true else onBack() },
                onReset = { showResetDialog = true },
                onPreview = {
                    selected = null
                    previewOnly = true
                    AvaToast.show(context, previewHint)
                },
                frame = liveFrame,
                portraitStyle = settings?.screensaverPortraitStyle,
                current = current,
                selected = selected,
                onSelect = { selected = it },
                onVisibleChange = { id, visible ->
                    val f = liveFrame
                    updateElement(id) { element ->
                        if (!visible) {
                            element.copy(visible = false)
                        } else {
                            var shown = element.copy(visible = true)
                            // Landscape date yields to status chips at its built-in spot;
                            // showing it again parks it just under the chips.
                            val status = f?.drawnBounds
                                ?.filterKeys { it.isStatusSlot }
                                ?.values
                                ?.maxByOrNull { it.bottom }
                            val dateBase = f?.defaultBounds?.get(SimpleClockLayoutElementId.DATE)
                            if (id == SimpleClockLayoutElementId.DATE && !shown.hasPosition &&
                                f != null && !f.portrait && status != null && dateBase != null
                            ) {
                                val y = status.bottom + dateBase.height()
                                shown = shown.copy(
                                    x = (dateBase.centerX() / f.width).coerceIn(0f, 1f),
                                    y = (y / f.height).coerceIn(0f, 1f),
                                )
                            }
                            shown
                        }
                    }
                },
                onScaleChange = { id, value ->
                    updateElement(id) { it.copy(scale = value) }
                },
                onCenterHorizontally = { id ->
                    val f = liveFrame
                    val base = f?.defaultBounds?.get(id)
                    if (f != null && base != null) {
                        updateElement(id) { element ->
                            val y = if (element.hasPosition) element.y else base.centerY() / f.height
                            element.copy(x = 0.5f, y = y.coerceIn(0f, 1f))
                        }
                    }
                },
                onResetElement = { id ->
                    updateElement(id) { null }
                    if (id.isCustomText) selected = null
                },
                onAddText = {
                    val live = if (editPortrait) portrait else landscape
                    val id = SimpleClockLayoutElementId.customTexts.firstOrNull { it !in live }
                    if (id != null) {
                        // New block: horizontally centered, first free horizontal band (top first,
                        // then bottom, then inwards) that doesn't sit on what the clock draws.
                        val y = layoutEditorFreeTextY(liveFrame, live)
                            ?: SimpleClockCustomLayout.defaultTextY(id)
                        updateElement(id) {
                            SimpleClockLayoutElement(id = id.key, x = 0.5f, y = y, text = newTextLabel)
                        }
                        selected = id
                    }
                },
                onColorChange = { id, hex -> updateElement(id) { it.copy(color = hex) } },
                onStyleChange = { id, bold, italic ->
                    updateElement(id) { it.copy(bold = bold, italic = italic) }
                },
                onHaDisplayChange = { id, value ->
                    // One switch per block, 1:1 with its HA entity: same value in both orientations.
                    landscape[id]?.let { landscape = landscape + (id to it.copy(haDisplay = value)) }
                    portrait[id]?.let { portrait = portrait + (id to it.copy(haDisplay = value)) }
                },
                onTextChange = { id, text ->
                    updateElement(id) { it.copy(text = text) }
                    // Emptying a free text block removes it.
                    if (id.isCustomText && text.isBlank()) selected = null
                },
                displaySettings = {
                    SimpleClockDisplaySettingsCards(
                        viewModel = settingsViewModel,
                        playerState = settings,
                        enabled = settingsEnabled,
                        coroutineScope = coroutineScope,
                        onOpenStatusSettings = onOpenStatusSettings,
                    )
                },
                behaviorSettings = {
                    SimpleClockBehaviorSettingsCard(
                        viewModel = settingsViewModel,
                        playerState = settings,
                        enabled = settingsEnabled,
                        coroutineScope = coroutineScope,
                    )
                },
            )
        }

        // One stable tree: the preview (and the AndroidView inside it) never moves to
        // another parent when controls show / hide, so the clock view is not re-attached.
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.align(if (showControls) Alignment.TopStart else Alignment.Center)) {
                preview()
            }
            if (showControls) {
                controls(
                    if (deviceLandscape) {
                        Modifier
                            .align(Alignment.CenterEnd)
                            .width(panelWidth)
                            .fillMaxHeight()
                    } else {
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(panelHeight)
                    }
                )
                // Drag handle on the panel's inner edge: drag to resize, size is remembered.
                if (deviceLandscape) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .offset(x = -(panelWidth - 10.dp))
                            .width(20.dp)
                            .fillMaxHeight()
                            .pointerInput(totalWidthPx, minWidthFraction) {
                                detectHorizontalDragGestures(
                                    onDragEnd = { LayoutEditorPanelPrefs.saveWidth(context, panelWidthFraction) },
                                    onDragCancel = { LayoutEditorPanelPrefs.saveWidth(context, panelWidthFraction) },
                                ) { change, dragAmount ->
                                    change.consume()
                                    panelWidthFraction = (panelWidthFraction - dragAmount / totalWidthPx)
                                        .coerceIn(minWidthFraction, LayoutEditorPanelPrefs.MAX_WIDTH)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 4.dp, height = 40.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isDarkModeEnabled()) LayoutEditorHandleColor else LayoutEditorHandleColorLight)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = -(panelHeight - 10.dp))
                            .fillMaxWidth()
                            .height(20.dp)
                            .pointerInput(totalHeightPx, minHeightFraction) {
                                detectVerticalDragGestures(
                                    onDragEnd = { LayoutEditorPanelPrefs.saveHeight(context, panelHeightFraction) },
                                    onDragCancel = { LayoutEditorPanelPrefs.saveHeight(context, panelHeightFraction) },
                                ) { change, dragAmount ->
                                    change.consume()
                                    panelHeightFraction = (panelHeightFraction - dragAmount / totalHeightPx)
                                        .coerceIn(minHeightFraction, LayoutEditorPanelPrefs.MAX_HEIGHT)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 40.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isDarkModeEnabled()) LayoutEditorHandleColor else LayoutEditorHandleColorLight)
                        )
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.settings_simple_clock_layout_discard_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = getTitleColor(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    save(thenExit = true)
                }) {
                    Text(
                        text = stringResource(R.string.settings_simple_clock_layout_save),
                        color = getAccentColor(),
                    )
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text(
                            text = stringResource(R.string.settings_simple_clock_layout_cancel),
                            color = getSettingsDescriptionColor(),
                        )
                    }
                    TextButton(onClick = {
                        showDiscardDialog = false
                        onBack()
                    }) {
                        Text(
                            text = stringResource(R.string.settings_simple_clock_layout_discard),
                            color = getSettingsDescriptionColor(),
                        )
                    }
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground(),
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.settings_simple_clock_layout_reset),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = getTitleColor(),
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_simple_clock_layout_reset_confirm),
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    selected = null
                    setCurrent(emptyMap())
                }) {
                    Text(
                        text = stringResource(R.string.settings_simple_clock_layout_reset),
                        color = getAccentColor(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(
                        text = stringResource(R.string.settings_simple_clock_layout_cancel),
                        color = getSettingsDescriptionColor(),
                    )
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground(),
        )
    }
}

/** Mirrors what the Simple Clock service pushes into its view. */
private val LayoutEditorJson = Json { ignoreUnknownKeys = true }

/** Element map <-> JSON (same sanitize as storage) so editor state survives navigation. */
private val LayoutEditorMapSaver = Saver<LayoutEditorMap, String>(
    save = { map ->
        LayoutEditorJson.encodeToString(
            ListSerializer(SimpleClockLayoutElement.serializer()),
            SimpleClockCustomLayout.fromMap(map)
        )
    },
    restore = { raw ->
        runCatching {
            SimpleClockCustomLayout.toMap(
                LayoutEditorJson.decodeFromString(ListSerializer(SimpleClockLayoutElement.serializer()), raw)
            )
        }.getOrDefault(emptyMap())
    }
)

private fun applyLayoutEditorClockSettings(view: ScreensaverView, settings: PlayerSettings) {
    view.setWallpaperDarkOverlayEnabled(settings.screensaverWallpaperDarkOverlayEnabled)
    view.updateWallpaperSettings(
        url = settings.screensaverWallpaperUrl,
        refreshSeconds = settings.screensaverWallpaperRefreshSeconds,
        dualPane = settings.screensaverWallpaperDualPane
    )
    view.updateStatusSlots(
        enabled = settings.enableScreensaverStatusSlots,
        slots = settings.screensaverStatusSlots
    )
    view.set12HourEnabled(settings.screensaver12HourEnabled)
    view.setPortraitStyle(SimpleClockPortraitStyle.fromStored(settings.screensaverPortraitStyle))
    view.setWeatherEnabled(
        enabled = settings.enableScreensaverWeather,
        hasEntity = resolveScreensaverWeatherEntityId(settings).isNotEmpty()
    )
    val voice = VoiceSatelliteService.getInstance() ?: return
    if (settings.enableScreensaverWeather) {
        voice.getCachedClockWeather()?.let { view.updateWeather(it) }
    }
    if (settings.enableScreensaverStatusSlots) {
        val entityIds = settings.screensaverStatusSlots
            .map { it.entityId.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (entityIds.isNotEmpty()) {
            view.restoreStatusEntityCaches(
                states = voice.getQuickEntityStates().filterKeys { it in entityIds },
                labels = emptyMap(),
                units = voice.getQuickEntityUnits().filterKeys { it in entityIds }
            )
        }
    }
}

private fun layoutEditorLiveFrame(
    frame: ScreensaverView.CustomLayoutFrame?,
    width: Int,
    height: Int,
    portrait: Boolean,
): ScreensaverView.CustomLayoutFrame? =
    frame?.takeIf { it.width == width && it.height == height && it.portrait == portrait }

/** Smallest drawn element under the finger, so small items stay pickable over big ones. */
private fun layoutEditorHitTest(
    frame: ScreensaverView.CustomLayoutFrame,
    x: Float,
    y: Float,
    slop: Float,
): SimpleClockLayoutElementId? {
    var best: SimpleClockLayoutElementId? = null
    var bestArea = Float.MAX_VALUE
    for ((id, rect) in frame.drawnBounds) {
        if (x >= rect.left - slop && x <= rect.right + slop &&
            y >= rect.top - slop && y <= rect.bottom + slop
        ) {
            val area = rect.width() * rect.height()
            if (area < bestArea) {
                bestArea = area
                best = id
            }
        }
    }
    return best
}

private data class LayoutEditorSnap(
    val x: Float,
    val y: Float,
    val guidesX: List<Float>,
    val guidesY: List<Float>,
)

/**
 * Snaps the element's center / edges to the screen center lines, the logo-style safe
 * margins, and the other elements' centers and edges.
 */
private fun layoutEditorSnap(
    frame: ScreensaverView.CustomLayoutFrame,
    id: SimpleClockLayoutElementId,
    centerX: Float,
    centerY: Float,
    halfWidth: Float,
    halfHeight: Float,
    threshold: Float,
): LayoutEditorSnap {
    val width = frame.width.toFloat()
    val height = frame.height.toFloat()
    val margin = min(width, height) * 0.05f
    val linesX = mutableListOf(width / 2f, margin, width - margin)
    val linesY = mutableListOf(height / 2f, margin, height - margin)
    for ((otherId, rect) in frame.drawnBounds) {
        if (otherId == id) continue
        linesX += listOf(rect.left, rect.centerX(), rect.right)
        linesY += listOf(rect.top, rect.centerY(), rect.bottom)
    }
    fun snapAxis(center: Float, half: Float, lines: List<Float>): Pair<Float, Float?> {
        var bestDelta = 0f
        var bestLine: Float? = null
        var bestDistance = threshold
        for (offset in floatArrayOf(-half, 0f, half)) {
            for (line in lines) {
                val delta = line - (center + offset)
                if (abs(delta) <= bestDistance) {
                    bestDistance = abs(delta)
                    bestDelta = delta
                    bestLine = line
                }
            }
        }
        return (center + bestDelta) to bestLine
    }
    val (x, lineX) = snapAxis(centerX, halfWidth, linesX)
    val (y, lineY) = snapAxis(centerY, halfHeight, linesY)
    return LayoutEditorSnap(
        x = x,
        y = y,
        guidesX = listOfNotNull(lineX),
        guidesY = listOfNotNull(lineY),
    )
}

@Composable
private fun LayoutEditorOverlay(
    frame: ScreensaverView.CustomLayoutFrame?,
    selected: SimpleClockLayoutElementId?,
    scale: Float,
    guidesX: List<Float>,
    guidesY: List<Float>,
    accent: Color,
    modifier: Modifier,
) {
    Canvas(modifier = modifier) {
        val f = frame ?: return@Canvas
        if (!f.active) return@Canvas
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        fun RectF.scaled(): Pair<Offset, Size> =
            Offset(left * scale, top * scale) to Size(width() * scale, height() * scale)
        for ((id, rect) in f.drawnBounds) {
            val (topLeft, size) = rect.scaled()
            if (id == selected) {
                drawRect(
                    color = accent,
                    topLeft = topLeft,
                    size = size,
                    style = Stroke(width = 2.dp.toPx())
                )
                val handle = 6.dp.toPx()
                listOf(
                    topLeft,
                    Offset(topLeft.x + size.width, topLeft.y),
                    Offset(topLeft.x, topLeft.y + size.height),
                    Offset(topLeft.x + size.width, topLeft.y + size.height),
                ).forEach { corner ->
                    drawRect(
                        color = accent,
                        topLeft = Offset(corner.x - handle / 2f, corner.y - handle / 2f),
                        size = Size(handle, handle)
                    )
                }
            } else {
                drawRect(
                    color = Color(0x66FFFFFF),
                    topLeft = topLeft,
                    size = size,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = dash)
                )
            }
        }
        guidesX.forEach { x ->
            drawLine(
                color = LayoutEditorGuideColor,
                start = Offset(x * scale, 0f),
                end = Offset(x * scale, this.size.height),
                strokeWidth = 1.dp.toPx()
            )
        }
        guidesY.forEach { y ->
            drawLine(
                color = LayoutEditorGuideColor,
                start = Offset(0f, y * scale),
                end = Offset(this.size.width, y * scale),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

@Composable
private fun LayoutEditorControls(
    modifier: Modifier,
    accent: Color,
    editPortrait: Boolean,
    onOrientationChange: (Boolean) -> Unit,
    dirty: Boolean,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onReset: () -> Unit,
    onPreview: () -> Unit,
    frame: ScreensaverView.CustomLayoutFrame?,
    portraitStyle: String?,
    current: LayoutEditorMap,
    selected: SimpleClockLayoutElementId?,
    onSelect: (SimpleClockLayoutElementId?) -> Unit,
    onVisibleChange: (SimpleClockLayoutElementId, Boolean) -> Unit,
    onScaleChange: (SimpleClockLayoutElementId, Float) -> Unit,
    onCenterHorizontally: (SimpleClockLayoutElementId) -> Unit,
    onResetElement: (SimpleClockLayoutElementId) -> Unit,
    onColorChange: (SimpleClockLayoutElementId, String) -> Unit,
    onStyleChange: (SimpleClockLayoutElementId, Boolean, Boolean) -> Unit,
    onHaDisplayChange: (SimpleClockLayoutElementId, Boolean) -> Unit,
    onTextChange: (SimpleClockLayoutElementId, String) -> Unit,
    onAddText: () -> Unit,
    displaySettings: @Composable () -> Unit,
    behaviorSettings: @Composable () -> Unit,
) {
    val labelColor = getLabelColor()
    val descColor = getSettingsDescriptionColor()
    Column(modifier = modifier.background(layoutEditorPanelBackground())) {
        // Top actions stay pinned: close / title / save never scroll away.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LayoutEditorButton(text = stringResource(R.string.settings_simple_clock_layout_close), onClick = onClose)
            Text(
                text = stringResource(R.string.settings_simple_clock_layout_editor_title),
                color = getTitleColor(),
                fontSize = settingsTitleTextSize(16f),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            LayoutEditorButton(
                text = stringResource(R.string.settings_simple_clock_layout_save),
                onClick = onSave,
                highlighted = dirty,
                accent = accent
            )
        }
        SettingsDivider()

        // Ava's edge-dissolve scroller: soft top / bottom fade only while the panel overflows.
        SettingsEdgeFadeScrollColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            handoffOverscrollToParent = false,
        ) {
            // Module: canvas (orientation, reset, full preview).
            SimpleCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Segmented landscape / portrait switch in one inset pill.
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(getSettingsInsetWellColor())
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        LayoutEditorChip(
                            text = stringResource(R.string.settings_simple_clock_layout_landscape),
                            selected = !editPortrait,
                            accent = accent,
                            segmented = true,
                            onClick = { onOrientationChange(false) }
                        )
                        LayoutEditorChip(
                            text = stringResource(R.string.settings_simple_clock_layout_portrait),
                            selected = editPortrait,
                            accent = accent,
                            segmented = true,
                            onClick = { onOrientationChange(true) }
                        )
                    }
                    // Reset this orientation sits at the far end of the same row.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        LayoutEditorButton(
                            text = stringResource(R.string.settings_simple_clock_layout_reset),
                            onClick = onReset,
                            quiet = true,
                            accent = accent
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                LayoutEditorButton(
                    text = stringResource(R.string.settings_simple_clock_layout_preview),
                    onClick = onPreview,
                    modifier = Modifier.fillMaxWidth(),
                    tall = true
                )
            }

            // ---------- Section 1: layout (caption under the orientation / preview card) ----------
            SettingsCaptionDivider(text = stringResource(R.string.settings_simple_clock_layout_section_layout))

            // Module: elements (pick one, then tune it).
            val inactive = frame != null && !frame.active
            SimpleCard {
                if (frame != null && inactive) {
                    // Magazine portrait is editable too; only odd panels / split panes stay built-in.
                    Text(
                        text = stringResource(R.string.settings_simple_clock_layout_inactive_other),
                        color = descColor,
                        fontSize = settingsBodyTextSize(),
                        lineHeight = 19.sp
                    )
                } else {
                    SettingsEdgeFadeScrollRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Status chips only for slots that are configured (one chip per slot);
                        // free text chips only for blocks the user added.
                        SimpleClockLayoutElementId.entries.filter { id ->
                            when {
                                id.isStatusSlot -> frame?.defaultBounds?.containsKey(id) == true
                                id.isCustomText -> current.containsKey(id)
                                else -> true
                            }
                        }.forEach { id ->
                            LayoutEditorChip(
                                text = layoutEditorElementName(id),
                                selected = id == selected,
                                accent = accent,
                                onClick = { onSelect(if (id == selected) null else id) }
                            )
                        }
                        // Up to 8 free text blocks.
                        if (SimpleClockLayoutElementId.customTexts.any { it !in current }) {
                            LayoutEditorChip(
                                text = "+ " + stringResource(R.string.settings_simple_clock_layout_add_text),
                                selected = false,
                                accent = accent,
                                outlined = true,
                                onClick = onAddText
                            )
                        }
                    }

                    val id = selected
                    if (id == null) {
                        Text(
                            text = stringResource(R.string.settings_simple_clock_layout_hint),
                            color = descColor,
                            fontSize = settingsBodyTextSize(),
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(top = 14.dp)
                        )
                    } else if (frame != null && frame.defaultBounds[id] == null) {
                        Text(
                            text = stringResource(R.string.settings_simple_clock_layout_unavailable_element),
                            color = descColor,
                            fontSize = settingsBodyTextSize(),
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(top = 14.dp)
                        )
                    } else {
                        val element = current[id]
                        val shown = frame?.drawnBounds?.containsKey(id) ?: (element?.visible ?: true)
                        Spacer(modifier = Modifier.height(12.dp))
                        SettingsDivider()
                        SettingRow(label = stringResource(R.string.settings_simple_clock_layout_visible)) {
                            ModernSwitch(checked = shown, onCheckedChange = { onVisibleChange(id, it) })
                        }
                        SettingsDivider()
                        val scaleValue = element?.scale ?: 1f
                        Spacer(modifier = Modifier.height(14.dp))
                        SettingSliderLabelRow(
                            title = stringResource(R.string.settings_simple_clock_layout_size),
                            badgeText = "${(scaleValue * 100f).toInt()}%"
                        )
                        TickSlider(
                            value = scaleValue,
                            // Light detent at 100% so the default size is easy to hit again.
                            onValueChange = { onScaleChange(id, if (abs(it - 1f) < 0.03f) 1f else it) },
                            valueRange = SimpleClockCustomLayout.MIN_SCALE..SimpleClockCustomLayout.MAX_SCALE,
                            enabled = shown,
                            colors = SliderDefaults.colors(
                                thumbColor = accent,
                                activeTrackColor = accent,
                            )
                        )
                        if (id.supportsColor) {
                            SettingsDivider()
                            Spacer(modifier = Modifier.height(14.dp))
                            LayoutEditorColorRow(
                                color = element?.color.orEmpty(),
                                accent = accent,
                                onColorChange = { onColorChange(id, it) }
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                        if (id.supportsText) {
                            SettingsDivider()
                            LayoutEditorTextRow(
                                id = id,
                                text = element?.text.orEmpty(),
                                onTextChange = { onTextChange(id, it) }
                            )
                        }
                        if (id.isCustomText) {
                            val bold = element?.bold == true
                            val italic = element?.italic == true
                            Row(
                                modifier = Modifier.padding(bottom = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LayoutEditorChip(
                                    text = stringResource(R.string.settings_simple_clock_layout_bold),
                                    selected = bold,
                                    accent = accent,
                                    onClick = { onStyleChange(id, !bold, italic) }
                                )
                                LayoutEditorChip(
                                    text = stringResource(R.string.settings_simple_clock_layout_italic),
                                    selected = italic,
                                    accent = accent,
                                    onClick = { onStyleChange(id, bold, !italic) }
                                )
                            }
                            // This block's own Home Assistant text entity (text.simple_clock_custom_text_N).
                            SettingsDivider()
                            SettingRow(
                                label = stringResource(R.string.settings_simple_clock_layout_text_ha),
                                subLabel = stringResource(R.string.settings_simple_clock_layout_text_ha_desc)
                            ) {
                                ModernSwitch(
                                    checked = element?.haDisplay == true,
                                    onCheckedChange = { onHaDisplayChange(id, it) }
                                )
                            }
                        }
                        SettingsDivider()
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LayoutEditorButton(
                                text = stringResource(R.string.settings_simple_clock_layout_center_h),
                                onClick = { onCenterHorizontally(id) },
                                modifier = Modifier.weight(1f),
                                tall = true
                            )
                            LayoutEditorButton(
                                // Free text: this deletes the block; built-in elements go back to default.
                                text = stringResource(
                                    if (id.isCustomText) {
                                        R.string.settings_simple_clock_layout_delete
                                    } else {
                                        R.string.settings_simple_clock_layout_reset_element
                                    }
                                ),
                                onClick = { onResetElement(id) },
                                modifier = Modifier.weight(1f),
                                tall = true
                            )
                        }
                    }
                }
            }

            // ---------- Section 2: what the clock shows ----------
            SettingsCaptionDivider(text = stringResource(R.string.settings_simple_clock_layout_section_display))
            displaySettings()

            // ---------- Section 3: clock behavior (burn-in shift, dark screen-off, smart AOD) ----------
            SettingsCaptionDivider(text = stringResource(R.string.settings_simple_clock_layout_section_behavior))
            behaviorSettings()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** Same page background as Ava settings screens (black / soft gray), so cards read as modules. */
@Composable
private fun layoutEditorPanelBackground(): Color =
    if (isDarkModeEnabled()) Color.Black else Color(0xFFF9FAFB)

private fun layoutEditorElementLabel(id: SimpleClockLayoutElementId): Int = when (id) {
    SimpleClockLayoutElementId.TIME -> R.string.settings_simple_clock_layout_element_time
    SimpleClockLayoutElementId.DATE -> R.string.settings_simple_clock_layout_element_date
    SimpleClockLayoutElementId.WEATHER -> R.string.settings_simple_clock_layout_element_weather
    SimpleClockLayoutElementId.STATUS_1,
    SimpleClockLayoutElementId.STATUS_2,
    SimpleClockLayoutElementId.STATUS_3 -> R.string.settings_simple_clock_layout_element_status
    SimpleClockLayoutElementId.LOGO -> R.string.settings_simple_clock_layout_element_logo
    SimpleClockLayoutElementId.TEXT_1,
    SimpleClockLayoutElementId.TEXT_2,
    SimpleClockLayoutElementId.TEXT_3,
    SimpleClockLayoutElementId.TEXT_4,
    SimpleClockLayoutElementId.TEXT_5,
    SimpleClockLayoutElementId.TEXT_6,
    SimpleClockLayoutElementId.TEXT_7,
    SimpleClockLayoutElementId.TEXT_8 -> R.string.settings_simple_clock_layout_element_text
}

/** Chip name; status slots get their slot number ("Status 2"). */
@Composable
private fun layoutEditorElementName(id: SimpleClockLayoutElementId): String {
    val label = stringResource(layoutEditorElementLabel(id))
    return when {
        id.isStatusSlot -> "$label ${id.statusSlotNumber}"
        id.isCustomText -> "$label ${id.textNumber}"
        else -> label
    }
}

/**
 * Element color: same swatches as the voice accent picker. Default (built-in colors),
 * the Ava accent, the seven rainbow presets, and the HSV custom picker.
 */
@Composable
private fun LayoutEditorColorRow(color: String, accent: Color, onColorChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val accentHex = SimpleClockCustomLayout.colorHex(accent.toArgb())
    val presets = remember(accentHex) {
        (listOf(accentHex) + VoiceAccentColors.RAINBOW_PRESETS.map { SimpleClockCustomLayout.colorHex(it.second) })
            .distinct()
    }
    val isCustom = color.isNotEmpty() && color !in presets
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.settings_simple_clock_layout_color),
            color = getLabelColor(),
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.Medium
        )
        SettingsEdgeFadeScrollRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CustomColorSwatch(
                selectedColor = if (isCustom) color else "",
                selected = isCustom,
                enabled = true,
                onClick = { showPicker = true }
            )
            PresetColorSwatch(
                color = Color.White,
                selected = color.isEmpty(),
                enabled = true,
                onClick = { onColorChange("") }
            )
            presets.forEach { hex ->
                PresetColorSwatch(
                    color = Color(SimpleClockCustomLayout.parseColor(hex) ?: android.graphics.Color.WHITE),
                    selected = color == hex,
                    enabled = true,
                    onClick = { onColorChange(hex) }
                )
            }
        }
    }
    if (showPicker) {
        CustomAccentColorDialog(
            initialHex = color.ifEmpty { accentHex },
            onDismiss = { showPicker = false },
            onConfirm = { hex ->
                onColorChange(SimpleClockCustomLayout.parseColor(hex)?.let { SimpleClockCustomLayout.colorHex(it) }.orEmpty())
                showPicker = false
            }
        )
    }
}

/** Date: format pattern. Status slot: shown name. Opens the same text dialog as other clock settings. */
@Composable
private fun LayoutEditorTextRow(
    id: SimpleClockLayoutElementId,
    text: String,
    onTextChange: (String) -> Unit,
) {
    var showDialog by remember(id) { mutableStateOf(false) }
    val isDate = id == SimpleClockLayoutElementId.DATE
    val isFreeText = id.isCustomText
    val label = stringResource(
        when {
            isDate -> R.string.settings_simple_clock_layout_date_format
            isFreeText -> R.string.settings_simple_clock_layout_text_content
            else -> R.string.settings_simple_clock_layout_status_name
        }
    )
    val defaultLabel = stringResource(R.string.settings_simple_clock_layout_text_default)
    // Settings-style row: label left, current value + chevron right (opens the text dialog).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = getLabelColor(),
            fontSize = settingsTitleTextSize(),
            fontWeight = FontWeight.Medium
        )
        HorizontalDissolveText(
            text = text.ifEmpty { defaultLabel },
            color = getSettingsDescriptionColor(),
            fontSize = settingsBodyTextSize(),
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 6.dp)
        )
        SettingsChevronIcon(tint = getSettingsDescriptionColor())
    }
    if (showDialog) {
        val invalid = stringResource(R.string.settings_simple_clock_layout_date_format_invalid)
        SimpleClockTextDialog(
            title = label,
            description = stringResource(
                when {
                    isDate -> R.string.settings_simple_clock_layout_date_format_desc
                    isFreeText -> R.string.settings_simple_clock_layout_text_content_desc
                    else -> R.string.settings_simple_clock_layout_status_name_desc
                }
            ),
            currentValue = text,
            placeholder = if (isDate) "yyyy-MM-dd EEEE" else defaultLabel,
            showClearAction = true,
            clearContentDescription = defaultLabel,
            roomy = true,
            maxLength = SimpleClockCustomLayout.maxTextLength(id),
            multiLine = isFreeText,
            validation = if (isDate) {
                { value -> if (value.isBlank() || layoutEditorIsDatePattern(value.trim())) null else invalid }
            } else {
                null
            },
            onDismiss = { showDialog = false },
            onConfirm = { value ->
                onTextChange(value.trim().take(SimpleClockCustomLayout.maxTextLength(id)))
                showDialog = false
            }
        )
    }
}

/**
 * Unsaved edits + an outside change (HA / Fleet): free text blocks whose stored copy changed take
 * the new text / bold / italic (or appear / disappear); everything else keeps the user's edits.
 */
private fun layoutEditorMergeExternalText(
    working: LayoutEditorMap,
    oldSaved: LayoutEditorMap,
    newSaved: LayoutEditorMap,
): LayoutEditorMap {
    val out = working.toMutableMap()
    for (id in SimpleClockLayoutElementId.customTexts) {
        val before = oldSaved[id]
        val after = newSaved[id]
        if (before == after) continue
        val mine = out[id]
        when {
            after == null -> out.remove(id)
            mine == null -> out[id] = after
            else -> out[id] = mine.copy(
                text = after.text,
                bold = after.bold,
                italic = after.italic,
                haDisplay = after.haDisplay,
            )
        }
    }
    return out
}

/** Center-column y (0–1) for a new text block that doesn't overlap drawn elements, or null. */
private fun layoutEditorFreeTextY(
    frame: ScreensaverView.CustomLayoutFrame?,
    current: LayoutEditorMap,
): Float? {
    if (frame == null || frame.width <= 0 || frame.height <= 0) return null
    val w = frame.width.toFloat()
    val h = frame.height.toFloat()
    val halfBand = min(w, h) * 0.045f
    val halfWidth = w * 0.2f
    val taken = frame.drawnBounds.values.toMutableList()
    for ((id, element) in current) {
        if (id.isCustomText && element.hasPosition && id !in frame.drawnBounds) {
            taken += android.graphics.RectF(
                element.x * w - halfWidth, element.y * h - halfBand,
                element.x * w + halfWidth, element.y * h + halfBand,
            )
        }
    }
    val candidates = listOf(0.1f, 0.9f, 0.18f, 0.82f, 0.26f, 0.74f, 0.34f, 0.66f, 0.5f)
    return candidates.firstOrNull { y ->
        val top = y * h - halfBand
        val bottom = y * h + halfBand
        taken.none { r -> r.top < bottom && r.bottom > top && r.left < w / 2f + halfWidth && r.right > w / 2f - halfWidth }
    }
}

private fun layoutEditorIsDatePattern(pattern: String): Boolean = runCatching {
    java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date())
}.isSuccess

/** Pill chip in Ava's settings palette: inset-well fill, accent when selected. */
@Composable
private fun LayoutEditorChip(
    text: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    /** Inside a segmented pill: transparent when not selected. */
    segmented: Boolean = false,
    /** Action chip (e.g. add text): accent outline instead of a fill. */
    outlined: Boolean = false,
) {
    val fill = when {
        selected -> accent
        segmented || outlined -> Color.Transparent
        else -> getSettingsInsetWellColor()
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .then(
                if (outlined && !selected) {
                    Modifier.border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = when {
                selected -> Color.White
                outlined -> accent
                else -> getLabelColor()
            },
            fontSize = settingsBodyTextSize(),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/** Rounded action button in Ava's settings palette. */
@Composable
private fun LayoutEditorButton(
    text: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    accent: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    /** Text-only action (accent text, no fill), e.g. reset at the end of a row. */
    quiet: Boolean = false,
    /** Full-size action row (preview / element actions). */
    tall: Boolean = false,
) {
    val fill = when {
        highlighted && accent != Color.Unspecified -> accent
        quiet -> Color.Transparent
        else -> getSettingsInsetWellColor()
    }
    val textColor = when {
        highlighted && accent != Color.Unspecified -> Color.White
        quiet && accent != Color.Unspecified -> accent
        else -> getLabelColor()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(if (tall) 16.dp else 14.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (tall) 12.dp else 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = settingsBodyTextSize(),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
