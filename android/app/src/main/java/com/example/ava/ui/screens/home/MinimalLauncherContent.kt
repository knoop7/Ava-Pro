package com.example.ava.ui.screens.home

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.R
import com.example.ava.esphome.Stopped
import com.example.ava.permissions.getVoiceSatellitePermissions
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.services.WebViewService
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.VoiceChannelSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.voiceChannelSettingsStore
import com.example.ava.ui.components.NewFeatureBadge
import com.example.ava.ui.rememberCompactSquareScreen
import com.example.ava.ui.rememberPaneIsLandscape
import com.example.ava.ui.glass.rememberLiquidGlassState
import com.example.ava.ui.screens.settings.components.AutoResizeText
import com.example.ava.ui.services.BindToService
import com.example.ava.ui.services.rememberLaunchWithMultiplePermissions
import com.example.ava.ui.services.startCoreServiceBestEffort
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.AccentBrown
import com.example.ava.ui.theme.SlateText
import com.example.ava.utils.OverviewBackdropCapture
import com.example.ava.utils.translate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class MinimalLauncherMetrics(
    val columns: Int,
    val iconSize: androidx.compose.ui.unit.Dp,
    val gridSpacing: androidx.compose.ui.unit.Dp,
    val cellPadding: androidx.compose.ui.unit.Dp,
    val gridPadding: PaddingValues,
    val labelSpacing: androidx.compose.ui.unit.Dp,
    val labelFontSize: androidx.compose.ui.unit.TextUnit,
    val labelMinFontSize: androidx.compose.ui.unit.TextUnit,
    val labelLineHeight: Dp
)

/**
 * Drawer geometry, resolved once.
 *
 * The column count travels in the result rather than being derived again at the grid,
 * because the two derivations have to agree exactly: the icon is sized to the cell, so a
 * grid laying out a different number of columns would give every icon the wrong width.
 */
@Composable
private fun rememberMinimalLauncherMetrics(contentPadding: PaddingValues): MinimalLauncherMetrics {
    val layoutDirection = LocalLayoutDirection.current
    val configuration = LocalConfiguration.current
    val fontScale = configuration.fontScale.coerceIn(0.85f, 1.35f)
    val screenWidthDp = configuration.screenWidthDp.toFloat()
    val startPad = contentPadding.calculateStartPadding(layoutDirection).value
    val endPad = contentPadding.calculateEndPadding(layoutDirection).value
    // The panel is already inset by the system bars, so that is the width to divide up.
    val panelWidthDp = (screenWidthDp - startPad - endPad).coerceAtLeast(1f)
    // The A–Z strip owns a lane on the trailing edge, so cells are sized on what is left
    // and the last column can never end up underneath it.
    val cell = MinimalLauncherGridSpec.allAppsCellSpec(
        panelWidthDp = panelWidthDp,
        reservedTrailingDp = MinimalLauncherGridSpec.ALL_APPS_FAST_SCROLLER_WIDTH_DP,
    )
    val baseLabelSp = (cell.cellWidthDp * 0.22f).coerceIn(10f, 13f)
    return MinimalLauncherMetrics(
        columns = cell.columns,
        iconSize = cell.iconDp.dp,
        gridSpacing = MinimalLauncherGridSpec.GRID_SPACING_DP.dp,
        cellPadding = MinimalLauncherGridSpec.CELL_PADDING_DP.dp,
        gridPadding = PaddingValues(
            start = MinimalLauncherGridSpec.ALL_APPS_GRID_PADDING_H_DP.dp,
            end = MinimalLauncherGridSpec.ALL_APPS_GRID_PADDING_H_DP.dp,
            top = MinimalLauncherGridSpec.ALL_APPS_GRID_PADDING_TOP_DP.dp,
            bottom = MinimalLauncherDrawerHandleHeight,
        ),
        labelSpacing = 6.dp,
        labelFontSize = (baseLabelSp / fontScale).sp,
        labelMinFontSize = (baseLabelSp * 0.78f / fontScale).coerceAtLeast(8f).sp,
        labelLineHeight = (baseLabelSp * 1.25f).coerceAtLeast(14f).dp
    )
}

private val MinimalLauncherCellCornerRadius = 20.dp

@Composable
internal fun MinimalLauncherEmptyHint(isDarkMode: Boolean) {
    val configuration = LocalConfiguration.current
    val shortestSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
    val densityDpi = configuration.densityDpi
    // Scale with physical size first, then gently with DPI so tiny/high-DPI panels stay readable.
    val sizeScale = when {
        shortestSide <= 320f -> 0.88f
        shortestSide <= 360f -> 0.94f
        shortestSide >= 960f -> 1.22f
        shortestSide >= 600f -> 1.12f
        else -> 1f
    }
    val dpiScale = when {
        densityDpi >= 560 -> 1.06f
        densityDpi >= 480 -> 1.03f
        densityDpi <= 160 -> 0.94f
        densityDpi <= 240 -> 0.97f
        else -> 1f
    }
    val fontScale = configuration.fontScale.coerceIn(0.85f, 1.35f)
    val uiScale = (sizeScale * dpiScale).coerceIn(0.86f, 1.28f)
    val titleSp = (16f * uiScale / fontScale).coerceIn(13f, 20f).sp
    val subtitleSp = (13f * uiScale / fontScale).coerceIn(11f, 16f).sp
    val gap = (8f * uiScale).coerceIn(6f, 12f).dp
    val horizontalPad = (28f * uiScale).coerceIn(20f, 48f).dp
    val titleColor = if (isDarkMode) Color(0xFF9CA3AF) else Color(0xFF64748B)
    val subtitleColor = if (isDarkMode) Color(0xFF6B7280) else Color(0xFF94A3B8)

    Box(modifier = Modifier.padding(horizontal = horizontalPad)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Text(
                text = stringResource(R.string.minimal_launcher_empty_hint_title),
                color = titleColor,
                fontSize = titleSp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.minimal_launcher_empty_hint_subtitle),
                color = subtitleColor,
                fontSize = subtitleSp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        NewFeatureBadge()
    }
}

/**
 * Only the gesture step fades on its own. The first step carries the undo, so it waits
 * for a real tap — nobody should lose that choice by looking away.
 */
private const val MINIMAL_LAUNCHER_GESTURE_STEP_MS = 9_000L

/** Line box tight to the glyphs, so the two lines of a notice sit close together. */
private val MinimalLauncherFlatText =
    TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))

/**
 * First-run coach, pinned to the bottom-end corner. Deliberately a small card and
 * never a full-width bar: in landscape a bar would stretch across the whole page.
 *
 * Two steps, because a seeded desktop hides the only long-press teacher we had — the
 * empty-desktop hint. Step one names what just happened and how to reach the rest of
 * the apps; it holds until tapped, so its undo cannot expire unnoticed. Step two
 * teaches the long-press, then fades on its own.
 */
@Composable
private fun MinimalLauncherSeedNotice(
    visible: Boolean,
    count: Int,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dismiss = rememberUpdatedState(onDismiss)
    var gestureStep by remember { mutableStateOf(false) }
    LaunchedEffect(count) { gestureStep = false }
    LaunchedEffect(visible, count, gestureStep) {
        if (!visible || !gestureStep) return@LaunchedEffect
        delay(MINIMAL_LAUNCHER_GESTURE_STEP_MS)
        dismiss.value()
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(240)) +
            slideInVertically(animationSpec = tween(280)) { it / 2 },
        exit = fadeOut(animationSpec = tween(160)) +
            slideOutVertically(animationSpec = tween(200)) { it / 3 },
    ) {
        val shape = RoundedCornerShape(18.dp)
        val titleColor = if (isDarkMode) Color(0xFFE5E7EB) else Color(0xFF1F2937)
        val subtitleColor = if (isDarkMode) Color(0xFF9CA3AF) else Color(0xFF6B7280)
        val actionColor = if (isDarkMode) Color(0xFF8AB4FF) else AccentBlue
        Column(
            modifier = Modifier
                .widthIn(max = 284.dp)
                .clip(shape)
                .background(if (isDarkMode) Color(0xE61B1F2A) else Color(0xF5FFFFFF))
                .border(
                    width = 1.dp,
                    color = if (isDarkMode) Color(0x1FFFFFFF) else Color(0x14000000),
                    shape = shape,
                )
                // Swallows taps so the card body cannot reach the desktop underneath and
                // trip its long-press menu. Only the two actions below do anything.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (gestureStep) {
                        Icons.Filled.TouchApp
                    } else {
                        Icons.Filled.KeyboardDoubleArrowUp
                    },
                    contentDescription = null,
                    tint = actionColor,
                    modifier = Modifier
                        .padding(end = 9.dp)
                        .size(16.dp),
                )
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = if (gestureStep) {
                            stringResource(R.string.minimal_launcher_seed_notice_gesture_title)
                        } else {
                            stringResource(R.string.minimal_launcher_seed_notice_title, count)
                        },
                        color = titleColor,
                        fontSize = 12.5.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MinimalLauncherFlatText,
                    )
                    Text(
                        text = if (gestureStep) {
                            stringResource(R.string.minimal_launcher_seed_notice_gesture_subtitle)
                        } else {
                            stringResource(R.string.minimal_launcher_seed_notice_subtitle)
                        },
                        color = subtitleColor,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        // Two lines, because a one-line cap truncates the longer locales.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MinimalLauncherFlatText,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!gestureStep) {
                    MinimalLauncherNoticeAction(
                        text = stringResource(R.string.minimal_launcher_seed_notice_undo),
                        color = subtitleColor,
                        onClick = onUndo,
                    )
                }
                MinimalLauncherNoticeAction(
                    text = if (gestureStep) {
                        stringResource(R.string.minimal_launcher_seed_notice_done)
                    } else {
                        stringResource(R.string.minimal_launcher_seed_notice_next)
                    },
                    color = actionColor,
                    onClick = { if (gestureStep) dismiss.value() else gestureStep = true },
                )
            }
        }
    }
}

/** Flat text button sized for the corner notice — no ripple slab, just a tap target. */
@Composable
private fun MinimalLauncherNoticeAction(
    text: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = color,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        style = MinimalLauncherFlatText,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MinimalLauncherContent(
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val excludePackage = appContext.packageName
    // Small square (≤680px): the pulled sheet is full-bleed, so size its grid
    // on the full width. Desktop padding stays on the workspace only.
    val fillPullContainer = rememberCompactSquareScreen()
    val metrics = rememberMinimalLauncherMetrics(
        if (fillPullContainer) PaddingValues(0.dp) else contentPadding,
    )
    val coroutineScope = rememberCoroutineScope()
    val playerSettingsStore = remember { PlayerSettingsStore(appContext.playerSettingsStore) }
    val playerSettings by playerSettingsStore.getFlow().collectAsStateWithLifecycle(PlayerSettings())

    var overviewOpen by remember { mutableStateOf(false) }
    var overviewBlurBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var appsOpen by remember { mutableStateOf(false) }
    var widgetsOpen by remember { mutableStateOf(false) }
    var wallpaperPickerOpen by remember { mutableStateOf(false) }
    var pendingWallpaperUri by remember { mutableStateOf("") }
    var pendingWallpaperScale by remember { mutableFloatStateOf(1f) }
    var pendingWallpaperOffsetX by remember { mutableFloatStateOf(0f) }
    var pendingWallpaperOffsetY by remember { mutableFloatStateOf(0f) }
    var dragSession by remember { mutableStateOf<MinimalLauncherDragSession?>(null) }
    var pageGeometry by remember { mutableStateOf<WorkspacePageGeometry?>(null) }
    var iconSizeOpen by remember { mutableStateOf(false) }
    // Layout as it stood when the adjuster opened. Every apply re-fits from this, never
    // from the previous result, so sliding back and forth cannot compound the mapping.
    var iconSizeSnapshot by remember {
        mutableStateOf<List<PlacedMinimalLauncherIcon>>(emptyList())
    }
    var iconSizeStartStep by remember { mutableIntStateOf(0) }
    // > 0 while the freshly seeded first-run page can still be undone.
    var seededCount by remember { mutableIntStateOf(0) }
    val placedWidgetsStored by MinimalLauncherWidgetsStore.widgetsFlow.collectAsStateWithLifecycle()
    val widgetsLandscape = rememberPaneIsLandscape()
    val placedWidgets = remember(placedWidgetsStored, widgetsLandscape) {
        placedWidgetsStored.forLayout(widgetsLandscape)
    }
    val placedIconsStored by MinimalLauncherIconsStore.iconsFlow.collectAsStateWithLifecycle()
    val iconSizePreviewStep by MinimalLauncherIconSizeStore.previewFlow
        .collectAsStateWithLifecycle()
    // While a size is being previewed, show the layout applying it would produce. Growing
    // the boxes without re-fitting them would look like a bug — icons overlapping and
    // running off the edge — and would hide the result the user is choosing between.
    val previewedIcons = remember(
        placedIconsStored,
        placedWidgetsStored,
        iconSizePreviewStep,
        iconSizeStartStep,
        iconSizeSnapshot,
        playerSettings.minimalLauncherShowDesktopLabels,
        pageGeometry,
    ) {
        val step = iconSizePreviewStep
        if (step == null || step == iconSizeStartStep || iconSizeSnapshot.isEmpty()) {
            placedIconsStored
        } else {
            val pages = MinimalLauncherPageGeometryStore.read(appContext)
                ?: pageGeometry?.let { geometry ->
                    val width = geometry.widthPx
                    val height = geometry.heightPx
                    val shortEdge = minOf(width, height)
                    val longEdge = maxOf(width, height)
                    MinimalLauncherPageGeometryStore.Pages(
                        portrait = MinimalLauncherDefaultLayout.PageSize(shortEdge, longEdge),
                        landscape = MinimalLauncherDefaultLayout.PageSize(longEdge, shortEdge),
                    )
                }
            pages?.let { measured ->
                MinimalLauncherIconSizeReflow.reflow(
                    context = appContext,
                    snapshot = iconSizeSnapshot,
                    widgets = placedWidgetsStored,
                    portrait = measured.portrait,
                    landscape = measured.landscape,
                    showDesktopLabels = playerSettings.minimalLauncherShowDesktopLabels,
                    fromStep = iconSizeStartStep,
                    toStep = step,
                )
            } ?: placedIconsStored
        }
    }
    val placedIcons = remember(previewedIcons, widgetsLandscape) {
        previewedIcons.forLayout(widgetsLandscape)
    }
    val iconsLayoutLoaded by MinimalLauncherIconsStore.isLoadedFlow.collectAsStateWithLifecycle()
    val widgetsLayoutLoaded by MinimalLauncherWidgetsStore.isLoadedFlow.collectAsStateWithLifecycle()
    val desktopLayoutReady = iconsLayoutLoaded && widgetsLayoutLoaded
    val layoutLocked by MinimalLauncherLayoutLockStore.lockedFlow.collectAsStateWithLifecycle()
    val emptyHintBadgeSeen by MinimalLauncherEmptyHintStore.badgeSeenFlow.collectAsStateWithLifecycle()
    val seedDecided by MinimalLauncherSeedStore.decidedFlow.collectAsStateWithLifecycle()
    val seedLoaded by MinimalLauncherSeedStore.isLoadedFlow.collectAsStateWithLifecycle()
    val pickWallpaper = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some document providers grant access without supporting persistence.
        }
        pendingWallpaperUri = uri.toString()
        pendingWallpaperScale = 1f
        pendingWallpaperOffsetX = 0f
        pendingWallpaperOffsetY = 0f
    }

    // Tracks pack/shape already applied from DataStore so the default
    // PlayerSettings() placeholder cannot wipe a freshly loaded pack on rotation.
    var appliedIconPack by remember { mutableStateOf<String?>(null) }
    var appliedIconShape by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(excludePackage) {
        // Seat size feeds every placement calculation, including first-run seeding, so it
        // has to be known before any layout is read or drawn.
        MinimalLauncherIconSizeStore.load(appContext)
        // Hydrate desktop layout first so the empty-hint does not flash while
        // settings / package scan are still in flight on cold start.
        MinimalLauncherWidgetsStore.load(appContext)
        MinimalLauncherIconsStore.load(appContext)
        MinimalLauncherLayoutLockStore.load(appContext)
        MinimalLauncherEmptyHintStore.load(appContext)
        MinimalLauncherSeedStore.load(appContext)

        // Wait for real DataStore settings — avoid applying default "" pack which
        // clears icons, then racing a load that gets wiped again on rotation.
        val settings = playerSettingsStore.getFlow().first()
        MinimalLauncherIconPackManager.setShape(
            IconShape.fromKey(settings.minimalLauncherIconShape)
        )
        MinimalLauncherIconPackManager.apply(appContext, settings.minimalLauncherIconPack)
        appliedIconPack = settings.minimalLauncherIconPack
        appliedIconShape = settings.minimalLauncherIconShape
        // apply() already force-reloads when the pack changes; otherwise warm peek.
        MinimalLauncherAppsCache.load(appContext, excludePackage)
        val warmSize = (metrics.iconSize.value * appContext.resources.displayMetrics.density)
            .toInt()
            .coerceAtLeast(48)
        warmShapedIconCache(MinimalLauncherAppsCache.appsFlow.value, warmSize)
        MinimalLauncherWidgetHost.get(appContext)
        MinimalLauncherIconPackManager.discoverPacks(appContext)
    }

    // Subsequent setting changes from the UI.
    val iconPackPkg = playerSettings.minimalLauncherIconPack
    LaunchedEffect(iconPackPkg) {
        val known = appliedIconPack ?: return@LaunchedEffect
        if (iconPackPkg == known) return@LaunchedEffect
        MinimalLauncherIconPackManager.apply(appContext, iconPackPkg)
        appliedIconPack = iconPackPkg
        val warmSize = (metrics.iconSize.value * appContext.resources.displayMetrics.density)
            .toInt()
            .coerceAtLeast(48)
        warmShapedIconCache(MinimalLauncherAppsCache.appsFlow.value, warmSize)
    }

    val iconShapeKey = playerSettings.minimalLauncherIconShape
    LaunchedEffect(iconShapeKey) {
        val known = appliedIconShape ?: return@LaunchedEffect
        if (iconShapeKey == known) return@LaunchedEffect
        MinimalLauncherIconPackManager.setShape(IconShape.fromKey(iconShapeKey))
        appliedIconShape = iconShapeKey
        val warmSize = (metrics.iconSize.value * appContext.resources.displayMetrics.density)
            .toInt()
            .coerceAtLeast(48)
        warmShapedIconCache(MinimalLauncherAppsCache.appsFlow.value, warmSize)
    }

    LaunchedEffect(layoutLocked) {
        if (layoutLocked) {
            // Leave overview alone — lock chrome lives there; close other edit surfaces.
            appsOpen = false
            widgetsOpen = false
            dragSession = null
        }
    }

    LaunchedEffect(desktopLayoutReady, placedIcons, placedWidgets, seededCount) {
        if (!desktopLayoutReady) return@LaunchedEffect
        // A page we just seeded does not count as the user having used the desktop —
        // undoing it has to bring the long-press hint back.
        if (seededCount > 0) return@LaunchedEffect
        if (placedIcons.isNotEmpty() || placedWidgets.isNotEmpty()) {
            MinimalLauncherEmptyHintStore.markSeen(appContext)
        }
    }

    // Add/remove/update is driven process-wide by MinimalLauncherAppsWatcher's
    // LauncherApps.Callback (started from AvaApplication), so this just observes the resulting
    // cache instead of owning its own broadcast receiver tied to this composable's lifecycle.
    val apps by MinimalLauncherAppsCache.appsFlow.collectAsStateWithLifecycle()
    val appsCacheLoading by MinimalLauncherAppsCache.isLoadingFlow.collectAsStateWithLifecycle()
    val visibleApps = remember(apps, playerSettings.minimalLauncherVisiblePackages) {
        filterMinimalLauncherApps(apps, playerSettings.minimalLauncherVisiblePackages)
    }

    // First run only: hand the new user a page of common apps instead of a blank
    // canvas. An install that already has a desktop layout is recognised and left
    // alone, and the decision is recorded once either way.
    LaunchedEffect(
        seedLoaded,
        seedDecided,
        desktopLayoutReady,
        appsCacheLoading,
        apps,
        pageGeometry,
    ) {
        if (!seedLoaded || seedDecided || !desktopLayoutReady) return@LaunchedEffect
        if (MinimalLauncherIconsStore.hasPersistedLayout(appContext) ||
            emptyHintBadgeSeen ||
            placedIcons.isNotEmpty() ||
            placedWidgets.isNotEmpty()
        ) {
            MinimalLauncherSeedStore.markDecided(appContext)
            return@LaunchedEffect
        }
        // The package scan is async: seeding before it lands would write an empty page
        // and burn the one-shot decision.
        if (appsCacheLoading || apps.isEmpty()) return@LaunchedEffect
        val geometry = pageGeometry ?: return@LaunchedEffect
        if (geometry.widthPx <= 1f || geometry.heightPx <= 1f) return@LaunchedEffect
        // Only the active orientation reports a real page. The other one is the same
        // window with its edges swapped — close enough for a seat the user can drag.
        val active = MinimalLauncherDefaultLayout.PageSize(geometry.widthPx, geometry.heightPx)
        val swapped = MinimalLauncherDefaultLayout.PageSize(geometry.heightPx, geometry.widthPx)
        // Null means no layout was computable from this page, so the decision stays open:
        // marking it here is what turned one bad attempt into a permanently blank desktop.
        // Re-running on the next geometry or app-list change costs a handful of
        // PackageManager queries and is the only way back for a user who hit that.
        // Capability apps (phone, camera, …) come from the full scan, not the All Apps
        // whitelist — that filter is a later preference and would otherwise blank the
        // first-run page on a device that already had one package selected.
        val seeded = MinimalLauncherDefaultLayout.seedFirstRunDesktop(
            context = appContext,
            apps = apps,
            portrait = if (widgetsLandscape) swapped else active,
            landscape = if (widgetsLandscape) active else swapped,
            showDesktopLabels = playerSettings.minimalLauncherShowDesktopLabels,
        ) ?: return@LaunchedEffect
        MinimalLauncherSeedStore.markDecided(appContext)
        seededCount = seeded
    }

    // Safety net only: a cheap per-package existence recheck (no PackageManager re-scan) in case
    // a callback was missed, e.g. the process was killed between the uninstall and next resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    var homeResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner, appContext) {
        // Listen only while Home is RESUMED. STARTED still covers NavHost Crossfade
        // (Mod Store sits on top) and HostView ticks then paint above that page.
        homeResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (homeResumed) {
            MinimalLauncherWidgetHost.startListening(appContext)
        }
        val observer = LifecycleEventObserver { _, _ ->
            val resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            homeResumed = resumed
            if (resumed) {
                MinimalLauncherWidgetHost.startListening(appContext)
            } else {
                MinimalLauncherWidgetHost.stopListening()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            homeResumed = false
            MinimalLauncherWidgetHost.stopListening()
        }
    }
    LaunchedEffect(homeResumed, appContext) {
        if (homeResumed) {
            MinimalLauncherAppsCache.verifyInstalled(appContext)
        }
    }
    val browserCovering by WebViewService.browserOverlayVisible().collectAsStateWithLifecycle()

    val draggingFromApps = dragSession?.payload is MinimalLauncherDragPayload.FromAllApps &&
        dragSession?.phase == MinimalLauncherDragPhase.Dragging
    val draggingFromWidgetsTray =
        dragSession?.payload is MinimalLauncherDragPayload.FromWidgetsTray &&
            dragSession?.phase == MinimalLauncherDragPhase.Dragging
    val draggingFromTray = draggingFromApps || draggingFromWidgetsTray
    val anyOverlay = (appsOpen && !draggingFromApps) ||
        (widgetsOpen && !draggingFromWidgetsTray) ||
        overviewOpen ||
        wallpaperPickerOpen
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val view = LocalView.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val densityPx = density.density

    fun releaseOverviewBlur() {
        overviewBlurBitmap?.takeUnless { it.isRecycled }?.recycle()
        overviewBlurBitmap = null
    }

    fun requestOpenOverview() {
        if (overviewOpen || appsOpen || widgetsOpen || dragSession != null) return
        coroutineScope.launch {
            val window = (context as? Activity)?.window
            val blurred = OverviewBackdropCapture.captureBlurred(view, window)
            releaseOverviewBlur()
            overviewBlurBitmap = blurred
            overviewOpen = true
        }
    }

    LaunchedEffect(overviewOpen) {
        if (overviewOpen) {
            MinimalLauncherEmptyHintStore.markSeen(appContext)
        } else {
            releaseOverviewBlur()
        }
    }

    DisposableEffect(Unit) {
        onDispose { releaseOverviewBlur() }
    }

    fun closeAppsAfterAllAppsDrag() {
        appsOpen = false
    }

    fun onWorkspaceDragSessionChange(next: MinimalLauncherDragSession?) {
        val prev = dragSession
        dragSession = next
        // L3: leaving All Apps drag restores workspace (tray closed).
        if (prev?.payload is MinimalLauncherDragPayload.FromAllApps &&
            (next == null || next.phase == MinimalLauncherDragPhase.Settling)
        ) {
            closeAppsAfterAllAppsDrag()
        }
    }

    val dragSessionState = rememberUpdatedState(dragSession)
    val pageGeometryState = rememberUpdatedState(pageGeometry)
    val placedIconsState = rememberUpdatedState(placedIcons)
    val placedWidgetsState = rememberUpdatedState(placedWidgets)
    val contextState = rememberUpdatedState(context)
    val widgetsLandscapeState = rememberUpdatedState(widgetsLandscape)
    val showDesktopLabelsState = rememberUpdatedState(
        playerSettings.minimalLauncherShowDesktopLabels
    )

    fun closeIconSizeAdjuster() {
        MinimalLauncherIconSizeStore.cancelPreview()
        iconSizeOpen = false
        iconSizeSnapshot = emptyList()
    }

    // An uncommitted preview is read by placement code too, so it must never outlive the
    // adjuster — leaving it set would silently resize drops after the launcher is gone.
    DisposableEffect(Unit) {
        onDispose { MinimalLauncherIconSizeStore.cancelPreview() }
    }
    // The snapshot and the notches both belong to one orientation. Rotating mid-adjust
    // invalidates them, so back out rather than apply a mapping across canvases.
    LaunchedEffect(widgetsLandscape) {
        if (iconSizeOpen) closeIconSizeAdjuster()
    }

    BackHandler(enabled = anyOverlay || iconSizeOpen || dragSession != null) {
        when {
            dragSession != null -> {
                (dragSession?.payload as? MinimalLauncherDragPayload.FromWidgetsTray)?.let {
                    MinimalLauncherWidgetHost.deleteAppWidgetId(appContext, it.appWidgetId)
                }
                MinimalLauncherDragLayerRegistry.layer?.deactivateDrag()
                dragSession = null
                if (draggingFromApps || appsOpen) appsOpen = false
            }
            iconSizeOpen -> closeIconSizeAdjuster()
            wallpaperPickerOpen -> wallpaperPickerOpen = false
            widgetsOpen -> widgetsOpen = false
            appsOpen -> appsOpen = false
            overviewOpen -> overviewOpen = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Backup follow path: after All Apps chrome unmounts, Compose CANCEL'd the
            // cell gesture — keep MOVE/UP on the root (L3 DragController ownership).
            .pointerInput(draggingFromTray) {
                if (!draggingFromTray) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        val rawX = loc[0] + change.position.x
                        val rawY = loc[1] + change.position.y
                        change.consume()
                        val current = dragSessionState.value
                        if (current == null ||
                            current.phase != MinimalLauncherDragPhase.Dragging
                        ) {
                            break
                        }
                        val fromApps = current.payload is MinimalLauncherDragPayload.FromAllApps
                        val fromWidgets =
                            current.payload is MinimalLauncherDragPayload.FromWidgetsTray
                        if (!fromApps && !fromWidgets) break
                        if (change.changedToUp() || !change.pressed) {
                            // Root owns UP after tray chrome unmounts (same for Apps + Widgets).
                            // Do not skip when DragLayer is active — Initial consume steals the UP.
                            dragSession = finishIncomingWorkspaceDrag(
                                context = contextState.value,
                                isLandscape = widgetsLandscapeState.value,
                                session = current.copy(fingerX = rawX, fingerY = rawY),
                                pageGeometry = pageGeometryState.value,
                                showDesktopLabels = showDesktopLabelsState.value,
                                icons = placedIconsState.value,
                                widgets = placedWidgetsState.value,
                            )
                            if (fromApps) closeAppsAfterAllAppsDrag()
                            MinimalLauncherDragLayerRegistry.layer?.deactivateDrag()
                            break
                        }
                        dragSession = current.copy(fingerX = rawX, fingerY = rawY)
                    }
                }
            }
    ) {
        // Full-bleed fixed grid pages (Launcher3 Workspace / CellLayout) — no stacked
        // widget column + leftover blank weight region.
        MinimalLauncherWorkspaceWidgets(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            isDarkMode = isDarkMode,
            // Only after prefs hydrate — cold-start empty lists are not a real empty
            // desktop. Waiting for the seed decision also stops the hint from flashing
            // on a first run that is about to be filled.
            showEmptyHint = desktopLayoutReady &&
                seedDecided &&
                !anyOverlay &&
                !iconSizeOpen &&
                placedWidgets.isEmpty() &&
                placedIcons.isEmpty() &&
                !emptyHintBadgeSeen,
            // Keep true while dragging — WorkspaceWidgets owns click/paging locks.
            // Gating on dragSession==null used to tear down icon pointerInput mid-drag.
            // The size adjuster deliberately leaves the desktop visible so the preview is
            // the real thing, so interaction is what has to be suppressed instead.
            interactionEnabled = !anyOverlay && !iconSizeOpen,
            pagingEnabled = !anyOverlay,
            layoutLocked = layoutLocked,
            showDesktopLabels = playerSettings.minimalLauncherShowDesktopLabels,
            dragSession = dragSession,
            onDragSessionChange = { onWorkspaceDragSessionChange(it) },
            onPageGeometryChange = { geometry ->
                pageGeometry = geometry
                // Settings cannot measure a workspace page, so record the real one for
                // the one-tap fill to read back later.
                geometry?.let {
                    MinimalLauncherPageGeometryStore.remember(appContext, it.widthPx, it.heightPx)
                }
            },
            onWorkspaceClick = {
                when {
                    widgetsOpen -> widgetsOpen = false
                    appsOpen -> appsOpen = false
                    overviewOpen -> overviewOpen = false
                }
            },
            onWorkspaceLongClick = {
                // Allowed even when layout-locked so the user can reopen overview to unlock.
                requestOpenOverview()
            },
            onWindowedLaunch = { app ->
                maybeLaunchInAppWindow(context, playerSettings, app.packageName)
            },
            // A tapped tile, not a swipe: a vertical fling here would race the system
            // gesture bar on Android 12+ and steal scrolls from list widgets.
            onOpenAllApps = { appsOpen = true },
            onOpenSettings = onOpenSettings,
            // Same list the slider is choosing between, so overflow pages show up
            // while dragging — not only after Apply writes the store.
            iconsOverride = previewedIcons,
            // anyOverlay is already false while dragging from Apps / Widgets, so the
            // spring-loaded desktop stays on screen as the drop target.
            // Pin the View-world layer unless Home is the resumed surface: Nav
            // Crossfade / browser overlay used to let HostViews pop above Mod Store.
            desktopLayerVisible = homeResumed && !anyOverlay && !browserCovering,
        )

        AnimatedVisibility(
            visible = overviewOpen && !appsOpen && !widgetsOpen,
            modifier = Modifier.zIndex(1f),
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(160)),
        ) {
            MinimalLauncherOverview(
                isDarkMode = isDarkMode,
                blurredBackdrop = overviewBlurBitmap,
                onDismiss = { overviewOpen = false },
                onOpenApps = {
                    overviewOpen = false
                    appsOpen = true
                },
                onOpenWidgets = {
                    overviewOpen = false
                    widgetsOpen = true
                },
                onOpenSettings = {
                    overviewOpen = false
                    onOpenSettings()
                }
            )
        }

        if (wallpaperPickerOpen) {
            MinimalLauncherWallpaperPicker(
                uriString = pendingWallpaperUri,
                scale = pendingWallpaperScale,
                offsetX = pendingWallpaperOffsetX,
                offsetY = pendingWallpaperOffsetY,
                onPickPhoto = { pickWallpaper.launch(arrayOf("image/*")) },
                onClearWallpaper = {
                    val old = playerSettings.minimalLauncherWallpaperUri
                    coroutineScope.launch {
                        playerSettingsStore.update {
                            it.copy(
                                minimalLauncherWallpaperMode = "none",
                                minimalLauncherWallpaperUri = "",
                                minimalLauncherWallpaperScale = 1f,
                                minimalLauncherWallpaperOffsetX = 0f,
                                minimalLauncherWallpaperOffsetY = 0f,
                            )
                        }
                        if (old.isNotBlank()) {
                            try {
                                context.contentResolver.releasePersistableUriPermission(
                                    android.net.Uri.parse(old),
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            } catch (_: SecurityException) {
                                // The previous provider may not have persisted a grant.
                            }
                        }
                        wallpaperPickerOpen = false
                    }
                },
                onTransformChange = { scale, offsetX, offsetY ->
                    pendingWallpaperScale = scale
                    pendingWallpaperOffsetX = offsetX
                    pendingWallpaperOffsetY = offsetY
                },
                onSetWallpaper = {
                    val selected = pendingWallpaperUri
                    if (selected.isNotBlank()) {
                        val old = playerSettings.minimalLauncherWallpaperUri
                        val selectedScale = pendingWallpaperScale
                        val selectedOffsetX = pendingWallpaperOffsetX
                        val selectedOffsetY = pendingWallpaperOffsetY
                        coroutineScope.launch {
                            playerSettingsStore.update {
                                it.copy(
                                    minimalLauncherWallpaperMode = "image",
                                    minimalLauncherWallpaperUri = selected,
                                    minimalLauncherWallpaperScale = selectedScale,
                                    minimalLauncherWallpaperOffsetX = selectedOffsetX,
                                    minimalLauncherWallpaperOffsetY = selectedOffsetY,
                                )
                            }
                            if (old.isNotBlank() && old != selected) {
                                try {
                                    context.contentResolver.releasePersistableUriPermission(
                                        android.net.Uri.parse(old),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                } catch (_: SecurityException) {
                                    // The previous provider may not have persisted a grant.
                                }
                            }
                        }
                        wallpaperPickerOpen = false
                    }
                },
            )
        }

        // All Apps open/close — L3-style overlay (config_overlayRevealTime ≈ 220ms):
        // scrim fades; content scales + lightly slides up (Material reveal stand-in).
        // Skip AnimatedVisibility while dragging so DragLayer handoff is instant (no exit shrink).
        if (!draggingFromApps) {
            AnimatedVisibility(
                visible = appsOpen,
                modifier = Modifier.zIndex(1f),
                enter = fadeIn(
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                ),
                exit = fadeOut(
                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                ),
            ) {
                MinimalLauncherAppsPanel(
                    apps = visibleApps,
                    appsLoading = appsCacheLoading && apps.isEmpty(),
                    metrics = metrics,
                    isDarkMode = isDarkMode,
                    contentPadding = contentPadding,
                    onDismiss = { appsOpen = false },
                    onAppClick = click@{ app ->
                        if (maybeLaunchInAppWindow(context, playerSettings, app.packageName)) {
                            appsOpen = false
                            return@click
                        }
                        try {
                            context.startActivity(
                                Intent()
                                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    .setComponent(ComponentName(app.packageName, app.activityName))
                            )
                            appsOpen = false
                        } catch (_: Exception) {
                            MinimalLauncherAppsCache.removePackage(app.packageName)
                        }
                    },
                    onAppDragStart = { app, rawX, rawY, widthPx, heightPx, grabX, grabY ->
                        val packActive =
                            MinimalLauncherIconPackManager.currentPackageFlow.value.isNotBlank()
                        val shape = if (packActive) {
                            IconShape.SYSTEM
                        } else {
                            MinimalLauncherIconPackManager.iconShapeFlow.value
                        }
                        val preview = try {
                            applyIconShape(
                                app.icon,
                                shape,
                                widthPx.coerceIn(48, 192),
                                iconPackActive = packActive,
                            )
                        } catch (_: Exception) {
                            null
                        }
                        dragSession = MinimalLauncherDragSession(
                            payload = MinimalLauncherDragPayload.FromAllApps(app),
                            fingerX = rawX,
                            fingerY = rawY,
                            grabOffsetX = grabX,
                            grabOffsetY = grabY,
                            widthPx = widthPx,
                            heightPx = heightPx,
                            phase = MinimalLauncherDragPhase.Dragging,
                            previewBitmap = preview,
                        )
                        // Hand off before tray unmount CANCEL (L3 DragLayer).
                        val layer = MinimalLauncherDragLayerRegistry.layer
                        layer?.activateDrag()
                        layer?.post { layer.activateDrag() }
                    },
                    onAppDragMove = move@{ rawX, rawY ->
                        val current = dragSession ?: return@move
                        if (current.payload !is MinimalLauncherDragPayload.FromAllApps) return@move
                        if (current.phase != MinimalLauncherDragPhase.Dragging) return@move
                        dragSession = current.copy(fingerX = rawX, fingerY = rawY)
                    },
                    onAppDragEnd = end@{ rawX, rawY ->
                        // Prefer DragLayer / root relay — cell UP is often lost after unmount.
                        if (MinimalLauncherDragLayerRegistry.layer?.isDragActive == true) return@end
                        val current = dragSession ?: return@end
                        if (current.payload !is MinimalLauncherDragPayload.FromAllApps) return@end
                        if (current.phase != MinimalLauncherDragPhase.Dragging) return@end
                        dragSession = finishIncomingWorkspaceDrag(
                            context = context,
                            isLandscape = widgetsLandscape,
                            session = current.copy(fingerX = rawX, fingerY = rawY),
                            pageGeometry = pageGeometry,
                            showDesktopLabels =
                                playerSettings.minimalLauncherShowDesktopLabels,
                            icons = placedIcons,
                            widgets = placedWidgets,
                        )
                        closeAppsAfterAllAppsDrag()
                    },
                )
            }
        }

        if (widgetsOpen) {
            MinimalLauncherWidgetsPanel(
                isDarkMode = isDarkMode,
                contentPadding = contentPadding,
                onDismiss = { widgetsOpen = false },
                onWidgetDragReady = { appWidgetId, provider, widthNorm, heightNorm, preview, rawX, rawY ->
                    widgetsOpen = false
                    // Prefer current page geometry (spring-scaled visual page) over
                    // full displayMetrics so ghost size matches drop math.
                    val pageW = pageGeometry?.widthPx?.takeIf { it > 1f } ?: screenWidthPx
                    val pageH = pageGeometry?.heightPx?.takeIf { it > 1f } ?: screenHeightPx
                    val w = (widthNorm * pageW).toInt().coerceIn(64, pageW.toInt().coerceAtLeast(64))
                    val h = (heightNorm * pageH).toInt().coerceIn(64, pageH.toInt().coerceAtLeast(64))
                    // Keep payload norms consistent with the ghost we actually show.
                    val wn = (w / pageW).coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
                    val hn = (h / pageH).coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
                    dragSession = MinimalLauncherDragSession(
                        payload = MinimalLauncherDragPayload.FromWidgetsTray(
                            appWidgetId = appWidgetId,
                            provider = provider,
                            widthNorm = wn,
                            heightNorm = hn,
                        ),
                        fingerX = rawX,
                        fingerY = rawY,
                        grabOffsetX = w * 0.5f,
                        grabOffsetY = h * 0.5f,
                        widthPx = w,
                        heightPx = h,
                        phase = MinimalLauncherDragPhase.Dragging,
                        previewBitmap = preview,
                    )
                    val layer = MinimalLauncherDragLayerRegistry.layer
                    layer?.activateDrag()
                    layer?.post { layer.activateDrag() }
                },
            )
        }

        // Detents come from the page on screen right now, so the notches match this
        // orientation's real capacity rather than a guess.
        val iconSizeDetents = remember(
            pageGeometry?.widthPx,
            pageGeometry?.heightPx,
            densityPx,
            playerSettings.minimalLauncherShowDesktopLabels,
        ) {
            val geometry = pageGeometry
            if (geometry == null) {
                emptyList()
            } else {
                minimalLauncherIconSizeDetents(
                    pageWidthPx = geometry.widthPx,
                    pageHeightPx = geometry.heightPx,
                    density = densityPx,
                    showDesktopLabels = playerSettings.minimalLauncherShowDesktopLabels,
                )
            }
        }

        // Lock control only accompanies the long-press overview (four actions).
        // Never on idle desktop, All Apps, widgets tray, or while dragging.
        val showLayoutLock = overviewOpen && !appsOpen && !widgetsOpen
        AnimatedVisibility(
            visible = showLayoutLock,
            modifier = Modifier
                .align(Alignment.TopEnd)
                // Above the overview's zIndex(1). Without this, the full-size
                // overview layer covers wallpaper / size / lock even though
                // they are declared later.
                .zIndex(2f)
                .padding(contentPadding)
                .padding(top = 2.dp, end = 2.dp),
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(140)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MinimalLauncherWallpaperButton(
                    onClick = {
                        val isImage = playerSettings.minimalLauncherWallpaperMode == "image" ||
                            (
                                playerSettings.minimalLauncherWallpaperMode.isBlank() &&
                                    playerSettings.minimalLauncherWallpaperUri.isNotBlank()
                            )
                        pendingWallpaperUri = if (isImage) {
                            playerSettings.minimalLauncherWallpaperUri
                        } else {
                            ""
                        }
                        pendingWallpaperScale =
                            if (isImage) playerSettings.minimalLauncherWallpaperScale else 1f
                        pendingWallpaperOffsetX =
                            if (isImage) playerSettings.minimalLauncherWallpaperOffsetX else 0f
                        pendingWallpaperOffsetY =
                            if (isImage) playerSettings.minimalLauncherWallpaperOffsetY else 0f
                        overviewOpen = false
                        wallpaperPickerOpen = true
                    },
                )
                // Hidden when this page has no room to offer a second size — better than a
                // button that opens a slider with a single stop on it.
                if (iconSizeDetents.size > 1) {
                    MinimalLauncherIconSizeButton(
                        onClick = {
                            iconSizeSnapshot = MinimalLauncherIconsStore.iconsFlow.value
                            val step = MinimalLauncherIconSizeStore.stepFlow.value
                            iconSizeStartStep = step
                            // Seed the preview so the desktop is already in preview mode,
                            // which is what puts the ghost seats on an empty page before
                            // the first notch is felt.
                            MinimalLauncherIconSizeStore.preview(step)
                            overviewOpen = false
                            iconSizeOpen = true
                        },
                    )
                }
                MinimalLauncherLayoutLockButton(
                    locked = layoutLocked,
                    onToggle = { MinimalLauncherLayoutLockStore.toggle(appContext) },
                )
            }
        }

        MinimalLauncherIconSizeSheet(
            visible = iconSizeOpen && dragSession == null,
            detents = iconSizeDetents,
            currentStep = iconSizeStartStep,
            isDarkMode = isDarkMode,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(contentPadding)
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            onPreview = { MinimalLauncherIconSizeStore.preview(it) },
            onConfirm = { step ->
                // Persist the very layout on screen rather than recomputing it, so what
                // was previewed and what gets saved cannot drift apart.
                val reflowed = previewedIcons
                MinimalLauncherIconSizeStore.commit(appContext, step)
                if (reflowed != placedIconsStored) {
                    MinimalLauncherIconsStore.replaceAll(appContext, reflowed)
                }
                iconSizeOpen = false
                iconSizeSnapshot = emptyList()
            },
            onCancel = { closeIconSizeAdjuster() },
        )

        MinimalLauncherSeedNotice(
            visible = seededCount > 0 && !anyOverlay && !iconSizeOpen && dragSession == null,
            count = seededCount,
            isDarkMode = isDarkMode,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(contentPadding)
                .padding(end = 10.dp, bottom = 10.dp),
            onUndo = {
                // Seeding only runs on an empty desktop, so everything here is ours.
                MinimalLauncherIconsStore.replaceAll(appContext, emptyList())
                MinimalLauncherWidgetsStore.replaceAll(appContext, emptyList())
                seededCount = 0
            },
            onDismiss = { seededCount = 0 },
        )
    }
}

@Composable
private fun MinimalLauncherWallpaperPicker(
    uriString: String,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onPickPhoto: () -> Unit,
    onClearWallpaper: () -> Unit,
    onTransformChange: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    onSetWallpaper: () -> Unit,
) {
    val bitmap = rememberMinimalLauncherWallpaperBitmap(uriString).value
    val transformState = rememberUpdatedState(Triple(scale, offsetX, offsetY))
    val onTransformChangeState = rememberUpdatedState(onTransformChange)
    val canSet = bitmap != null && uriString.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        if (bitmap != null) {
            MinimalLauncherWallpaperImage(
                bitmap = bitmap,
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val current = transformState.value
                            val nextScale = (current.first * zoom).coerceIn(1f, 4f)
                            val viewWidth = size.width.toFloat().coerceAtLeast(1f)
                            val viewHeight = size.height.toFloat().coerceAtLeast(1f)
                            val baseScale = maxOf(
                                viewWidth / bitmap.width.coerceAtLeast(1),
                                viewHeight / bitmap.height.coerceAtLeast(1),
                            )
                            val maxOffsetX = (
                                bitmap.width * baseScale * nextScale - viewWidth
                                ).coerceAtLeast(0f) / (2f * viewWidth)
                            val maxOffsetY = (
                                bitmap.height * baseScale * nextScale - viewHeight
                                ).coerceAtLeast(0f) / (2f * viewHeight)
                            onTransformChangeState.value(
                                nextScale,
                                (current.second + pan.x / viewWidth)
                                    .coerceIn(-maxOffsetX, maxOffsetX),
                                (current.third + pan.y / viewHeight)
                                    .coerceIn(-maxOffsetY, maxOffsetY),
                            )
                        }
                    },
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth()
                .heightIn(min = 56.dp, max = 56.dp)
                .background(Color.Black.copy(alpha = 0.40f)),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .clickable(enabled = canSet, onClick = onSetWallpaper)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(
                        R.drawable.ic_minimal_launcher_wallpaper_accept
                    ),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (canSet) 1f else 0.42f),
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(R.string.minimal_launcher_wallpaper_set),
                    color = Color.White.copy(alpha = if (canSet) 1f else 0.42f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 2.dp, max = 2.dp)
                    .background(Color.Black.copy(alpha = 0.32f)),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                MinimalLauncherWallpaperActionTile(
                    iconRes = R.drawable.ic_minimal_launcher_photos,
                    label = stringResource(R.string.minimal_launcher_wallpaper_pick_image),
                    onClick = onPickPhoto,
                )
                MinimalLauncherWallpaperActionTile(
                    iconRes = R.drawable.ic_minimal_launcher_wallpaper_none,
                    label = stringResource(R.string.minimal_launcher_wallpaper_none),
                    onClick = onClearWallpaper,
                )
            }
        }
    }
}

@Composable
private fun MinimalLauncherWallpaperActionTile(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 106.5.dp, height = 94.5.dp)
            .background(Color.Black.copy(alpha = 0.40f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MinimalLauncherWallpaperButton(
    onClick: () -> Unit,
) {
    // Overview sits on a darkened scrim — keep icons white in both themes.
    val tint = Color.White.copy(alpha = 0.88f)
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_minimal_launcher_wallpaper),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun MinimalLauncherLayoutLockButton(
    locked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val tint = Color.White.copy(alpha = 0.88f)
    IconButton(
        onClick = onToggle,
        modifier = modifier.size(44.dp),
    ) {
        Icon(
            imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Overview entry chrome sizes.
 * Continuous [vmin]/360 scale (same baseline as media / dashboard chrome) so satellite
 * state labels grow on tablets; soft landscape boost keeps phone landscape readable.
 * Small square panels use the same gate as WeatherOverlay / HomePinLock
 * (`|w−h| < 50 && w ≤ 500`) so the 4-action row does not overflow.
 */
private data class OverviewActionMetrics(
    val iconSize: androidx.compose.ui.unit.Dp,
    val labelSp: androidx.compose.ui.unit.TextUnit,
    val labelMinSp: androidx.compose.ui.unit.TextUnit,
    val gap: androidx.compose.ui.unit.Dp,
    val padH: androidx.compose.ui.unit.Dp,
    val padV: androidx.compose.ui.unit.Dp,
    val rowGap: androidx.compose.ui.unit.Dp,
    /** Match WeatherOverlay `isSmallSquareScreen` — tighten + equal-width cells. */
    val isSmallSquare: Boolean = false,
)

private const val OVERVIEW_PHONE_LOCK_DP = 600f
private const val OVERVIEW_SCALE_MIN = 1f
private const val OVERVIEW_SCALE_MAX = 1.55f
private const val OVERVIEW_SCALE_ABS_MAX = 1.72f

@Composable
private fun rememberOverviewActionMetrics(): OverviewActionMetrics {
    val configuration = LocalConfiguration.current
    val w = configuration.screenWidthDp.toFloat()
    val h = configuration.screenHeightDp.toFloat()
    val vmin = minOf(w, h)
    // Same square / landscape gates as WeatherOverlayService.drawUILayer.
    val compactSquare = rememberCompactSquareScreen()
    val isSmallSquare = compactSquare || (kotlin.math.abs(w - h) < 50f && w <= 500f)
    val isLandscape = rememberPaneIsLandscape()
    val isPhone = vmin < OVERVIEW_PHONE_LOCK_DP
    val isPhoneLandscape = isPhone && isLandscape
    // Small square: fixed compact metrics (no landscape boost — near-square is never landscape).
    if (isSmallSquare) {
        return OverviewActionMetrics(
            iconSize = 28.dp,
            labelSp = 11.sp,
            labelMinSp = 8.sp,
            gap = 4.dp,
            padH = 2.dp,
            padV = 6.dp,
            rowGap = 2.dp,
            isSmallSquare = true,
        )
    }
    // Phones stay at 1× (no lift). Tablets grow from the 600dp floor.
    val baseScale = if (isPhone) {
        1f
    } else {
        (vmin / OVERVIEW_PHONE_LOCK_DP).coerceIn(OVERVIEW_SCALE_MIN, OVERVIEW_SCALE_MAX)
    }
    // Keep the pre-existing phone-landscape readability boost; tablets get a milder one.
    val landscapeBoost = when {
        isPhoneLandscape -> 1.58f
        isLandscape && !isPhone -> 1.18f
        else -> 1f
    }
    val scale = (baseScale * landscapeBoost).coerceIn(OVERVIEW_SCALE_MIN, OVERVIEW_SCALE_ABS_MAX)
    val labelMax = if (isPhone) 18f else 23f
    val labelMinMax = if (isPhone) 14f else 16f
    return OverviewActionMetrics(
        iconSize = (34f * scale).coerceIn(32f, if (isPhone) 54f else 56f).dp,
        // Phone baseline back to 13sp; tablets may grow past the old 18sp cap.
        labelSp = (13f * scale).coerceIn(12f, labelMax).sp,
        labelMinSp = (10f * scale).coerceIn(9f, labelMinMax).sp,
        gap = (8f * scale).coerceIn(6f, 14f).dp,
        padH = (12f * scale).coerceIn(10f, if (isPhone) 20f else 22f).dp,
        padV = (10f * scale).coerceIn(8f, 16f).dp,
        rowGap = ((if (isLandscape) 18f else 10f) * scale.coerceAtMost(1.35f)).dp,
        isSmallSquare = false,
    )
}

@Composable
private fun MinimalLauncherOverview(
    isDarkMode: Boolean,
    blurredBackdrop: Bitmap?,
    onDismiss: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenWidgets: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val accent = if (isDarkMode) AccentBrown else AccentBlue
    val metrics = rememberOverviewActionMetrics()
    // Light tint over the blurred desktop so actions stay readable.
    val scrimAlpha = if (isDarkMode) 0.30f else 0.22f
    val backdropImage = remember(blurredBackdrop) {
        blurredBackdrop
            ?.takeUnless { it.isRecycled }
            ?.let { runCatching { it.asImageBitmap() }.getOrNull() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        if (backdropImage != null) {
            Image(
                bitmap = backdropImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha)),
        )
        // Small square: share width evenly so long service labels cannot push siblings off-screen.
        // Other form factors keep the original wrap-content, centered row.
        Row(
            horizontalArrangement = Arrangement.spacedBy(metrics.rowGap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(if (metrics.isSmallSquare) Modifier.fillMaxWidth() else Modifier)
                .padding(horizontal = if (metrics.isSmallSquare) 10.dp else 16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            val actionMod = if (metrics.isSmallSquare) {
                Modifier.weight(1f)
            } else {
                Modifier
            }
            OverviewServiceAction(
                accent = accent,
                metrics = metrics,
                modifier = actionMod,
            )
            OverviewAction(
                icon = Icons.Filled.Apps,
                label = stringResource(R.string.overview_apps),
                metrics = metrics,
                onClick = onOpenApps,
                modifier = actionMod,
            )
            OverviewAction(
                icon = Icons.Filled.Widgets,
                label = stringResource(R.string.widget_button_text),
                metrics = metrics,
                onClick = onOpenWidgets,
                modifier = actionMod,
            )
            OverviewAction(
                icon = Icons.Filled.Settings,
                label = stringResource(R.string.label_settings),
                metrics = metrics,
                onClick = onOpenSettings,
                modifier = actionMod,
            )
        }
    }
}

@Composable
private fun OverviewPressable(
    metrics: OverviewActionMetrics,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (iconTint: Color, labelColor: Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            !enabled -> 1f
            pressed -> 0.90f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "overviewPressScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.45f,
        animationSpec = tween(160),
        label = "overviewEnabledAlpha",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(metrics.gap),
        modifier = modifier
            .then(if (metrics.isSmallSquare) Modifier.fillMaxWidth() else Modifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = metrics.padH, vertical = metrics.padV)
    ) {
        content(Color.White, Color.White)
    }
}

@Composable
private fun OverviewServiceAction(
    accent: Color,
    metrics: OverviewActionMetrics,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    var service by remember { mutableStateOf<VoiceSatelliteService?>(null) }
    val voiceChannelStore = remember { VoiceChannelSettingsStore(appContext.voiceChannelSettingsStore) }
    val voiceChannelEnabled by voiceChannelStore.enabled.collectAsStateWithLifecycle(true)
    BindToService(
        onConnected = { service = it },
        onDisconnected = { service = null }
    )
    val resources = LocalContext.current.resources
    val genericServiceLabel = stringResource(R.string.overview_service)
    val currentService = service
    val serviceStateFlow = remember(currentService) {
        currentService?.voiceSatelliteState ?: kotlinx.coroutines.flow.flowOf(Stopped)
    }
    val serviceState by serviceStateFlow.collectAsStateWithLifecycle(Stopped)
    val isStarted = currentService != null && serviceState !is Stopped
    val statusLabel = remember(currentService, serviceState, voiceChannelEnabled, resources, genericServiceLabel) {
        when {
            currentService == null -> genericServiceLabel
            voiceChannelEnabled -> serviceState.translate(resources)
            else -> genericServiceLabel
        }
    }
    val registerStartPermissions = rememberLaunchWithMultiplePermissions(
        onPermissionGranted = { startCoreServiceBestEffort(appContext) },
        onPermissionDenied = { }
    )
    val registerTogglePermissions = rememberLaunchWithMultiplePermissions(
        onPermissionGranted = { service?.startVoiceSatellite() },
        onPermissionDenied = { }
    )
    val requiredPermissions = remember(voiceChannelEnabled) {
        getVoiceSatellitePermissions(voiceChannelEnabled)
    }
    OverviewPressable(
        metrics = metrics,
        onClick = {
            val bound = service
            when {
                bound == null -> registerStartPermissions(requiredPermissions)
                isStarted -> bound.stopVoiceSatellite()
                else -> registerTogglePermissions(requiredPermissions)
            }
        },
        modifier = modifier,
    ) { _, _ ->
        OverviewActionVisual(
            painter = painterResource(R.drawable.power_24px),
            label = statusLabel,
            metrics = metrics,
            iconTint = if (isStarted) accent else Color.White,
            labelColor = Color.White,
        )
    }
}

@Composable
private fun OverviewAction(
    icon: ImageVector,
    label: String,
    metrics: OverviewActionMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverviewPressable(
        metrics = metrics,
        onClick = onClick,
        modifier = modifier,
    ) { iconTint, labelColor ->
        OverviewActionVisual(
            imageVector = icon,
            label = label,
            metrics = metrics,
            iconTint = iconTint,
            labelColor = labelColor,
        )
    }
}

@Composable
private fun OverviewActionVisual(
    label: String,
    metrics: OverviewActionMetrics,
    iconTint: Color,
    labelColor: Color,
    imageVector: ImageVector? = null,
    painter: Painter? = null,
) {
    if (imageVector != null) {
        Icon(
            imageVector = imageVector,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(metrics.iconSize)
        )
    } else if (painter != null) {
        Icon(
            painter = painter,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(metrics.iconSize)
        )
    }
    AutoResizeText(
        text = label,
        fontSize = metrics.labelSp,
        minFontSize = metrics.labelMinSp,
        color = labelColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .then(if (metrics.isSmallSquare) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = metrics.labelSp.value.dp * 1.15f),
        style = TextStyle(textAlign = TextAlign.Center)
    )
}

@Composable
private fun AnimatedVisibilityScope.MinimalLauncherAppsPanel(
    apps: List<MinimalLauncherApp>,
    appsLoading: Boolean,
    metrics: MinimalLauncherMetrics,
    isDarkMode: Boolean,
    contentPadding: PaddingValues,
    onDismiss: () -> Unit,
    onAppClick: (MinimalLauncherApp) -> Unit,
    onAppDragStart: (
        app: MinimalLauncherApp,
        rawX: Float,
        rawY: Float,
        widthPx: Int,
        heightPx: Int,
        grabX: Float,
        grabY: Float,
    ) -> Unit,
    onAppDragMove: (rawX: Float, rawY: Float) -> Unit,
    onAppDragEnd: (rawX: Float, rawY: Float) -> Unit,
) {
    val panelColor = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val fillPane = rememberCompactSquareScreen()
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    // Occasional drawer: spatial + anti-teleport. Ease-out/drawer curves start
    // in the first frame; no enter delay. Scale stays ≥0.95 — never from 0.
    val contentEnter = if (reduceMotion) {
        fadeIn(animationSpec = tween(160, easing = AvaEaseOut))
    } else {
        scaleIn(
            initialScale = 0.95f,
            animationSpec = tween(220, easing = AvaEaseDrawer),
        ) + fadeIn(
            animationSpec = tween(220, easing = AvaEaseDrawer),
        ) + slideInVertically(
            animationSpec = tween(220, easing = AvaEaseDrawer),
            initialOffsetY = { (it * 0.04f).toInt() },
        )
    }
    val contentExit = if (reduceMotion) {
        fadeOut(animationSpec = tween(120, easing = AvaEaseOut))
    } else {
        scaleOut(
            targetScale = 0.96f,
            animationSpec = tween(160, easing = AvaEaseOut),
        ) + fadeOut(
            animationSpec = tween(140, easing = AvaEaseOut),
        ) + slideOutVertically(
            animationSpec = tween(160, easing = AvaEaseOut),
            targetOffsetY = { (it * 0.03f).toInt() },
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(if (fillPane) PaddingValues(0.dp) else contentPadding)
    ) {
        // Deliberately not clickable. Closing on a tap anywhere turned all the whitespace
        // between icons into an accidental exit; the overlay handle is the way out.
        Box(
            modifier = Modifier
                .animateEnterExit(enter = contentEnter, exit = contentExit)
                .fillMaxSize()
                .clip(RoundedCornerShape(if (fillPane) 0.dp else 20.dp))
                .background(panelColor),
        ) {
            val gridState = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            val letterFollow = remember(gridState) { DrawerGridLetterFollow(gridState) }
            val spacingPx = with(LocalDensity.current) { metrics.gridSpacing.toPx() }
            val sections = remember(apps) { minimalLauncherAppSections(apps) }
            DisposableEffect(letterFollow) {
                onDispose { letterFollow.cancel() }
            }
            LaunchedEffect(gridState, letterFollow) {
                snapshotFlow { gridState.isScrollInProgress }
                    .collect { scrolling ->
                        if (scrolling && !letterFollow.isFollowing) {
                            letterFollow.cancel()
                        }
                    }
            }
            val showTopFade by remember {
                derivedStateOf { gridState.drawerShowTopFade() }
            }
            val showBottomFade by remember {
                derivedStateOf { gridState.drawerShowBottomFade() }
            }
            val launcherGlass by rememberLiquidGlassState()
            val scrollSectionIndex by remember {
                derivedStateOf {
                    sectionIndexForVisibleItem(gridState.firstVisibleItemIndex, sections)
                }
            }
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .drawerVerticalEdgeDissolve(
                            showTop = showTopFade,
                            showBottom = showBottomFade,
                            fadeToColor = panelColor,
                            preferScrim = true,
                            glassMode = launcherGlass.enabled,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (appsLoading) {
                        HomeCenterLoadingRing(isDarkMode = isDarkMode)
                    } else {
                        LauncherAppGrid(
                            apps = apps,
                            metrics = metrics,
                            isDarkMode = isDarkMode,
                            contentPadding = metrics.gridPadding,
                            state = gridState,
                            onAppClick = onAppClick,
                            onAppDragStart = onAppDragStart,
                            onAppDragMove = onAppDragMove,
                            onAppDragEnd = onAppDragEnd,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .width(MinimalLauncherGridSpec.ALL_APPS_FAST_SCROLLER_WIDTH_DP.dp)
                        .fillMaxHeight(),
                )
            }
            if (!appsLoading) {
                // Full-panel draw so the Niagara handle can sit over the grid.
                // The reserved lane stays 22.dp; the scroller itself adds a
                // little extra hit width toward the icons.
                MinimalLauncherFastScroller(
                    sections = sections,
                    isDarkMode = isDarkMode,
                    width = MinimalLauncherGridSpec.ALL_APPS_FAST_SCROLLER_WIDTH_DP.dp,
                    scrollSectionIndex = scrollSectionIndex,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f),
                    onJump = { index ->
                        letterFollow.jumpTo(
                            scope,
                            index,
                            metrics.columns,
                            spacingPx,
                            reduceMotion = reduceMotion,
                        )
                    },
                )
            }
            MinimalLauncherDrawerCloseBar(
                isDarkMode = isDarkMode,
                onClose = onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .zIndex(2f)
                    .padding(
                        end = MinimalLauncherGridSpec.ALL_APPS_FAST_SCROLLER_WIDTH_DP.dp,
                    ),
            )
        }
    }
}

@Composable
private fun LauncherAppGrid(
    apps: List<MinimalLauncherApp>,
    metrics: MinimalLauncherMetrics,
    isDarkMode: Boolean,
    contentPadding: PaddingValues,
    state: LazyGridState,
    onAppClick: (MinimalLauncherApp) -> Unit,
    onAppDragStart: (
        app: MinimalLauncherApp,
        rawX: Float,
        rawY: Float,
        widthPx: Int,
        heightPx: Int,
        grabX: Float,
        grabY: Float,
    ) -> Unit,
    onAppDragMove: (rawX: Float, rawY: Float) -> Unit,
    onAppDragEnd: (rawX: Float, rawY: Float) -> Unit,
) {
    // Collect once for the whole grid — per-cell StateFlow collect was scroll jank.
    val iconShape by MinimalLauncherIconPackManager.iconShapeFlow.collectAsStateWithLifecycle()
    val iconPackPkg by MinimalLauncherIconPackManager.currentPackageFlow.collectAsStateWithLifecycle()
    val flingBehavior = rememberDrawerGridFlingBehavior()
    LazyVerticalGrid(
        columns = GridCells.Fixed(metrics.columns),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
        verticalArrangement = Arrangement.spacedBy(metrics.gridSpacing),
        flingBehavior = flingBehavior,
    ) {
        itemsIndexed(
            apps,
            key = { _, app -> "${app.packageName}/${app.activityName}" },
            contentType = { _, _ -> "app" },
        ) { _, app ->
            MinimalLauncherAppCell(
                app = app,
                metrics = metrics,
                isDarkMode = isDarkMode,
                iconShape = iconShape,
                iconPackPkg = iconPackPkg,
                onClick = { onAppClick(app) },
                onDragStart = { rawX, rawY, w, h, gx, gy ->
                    onAppDragStart(app, rawX, rawY, w, h, gx, gy)
                },
                onDragMove = onAppDragMove,
                onDragEnd = onAppDragEnd,
            )
        }
    }
}

@Composable
private fun MinimalLauncherAppCell(
    app: MinimalLauncherApp,
    metrics: MinimalLauncherMetrics,
    isDarkMode: Boolean,
    iconShape: IconShape,
    iconPackPkg: String,
    onClick: () -> Unit,
    onDragStart: (
        rawX: Float, rawY: Float, widthPx: Int, heightPx: Int, grabX: Float, grabY: Float
    ) -> Unit,
    onDragMove: (rawX: Float, rawY: Float) -> Unit,
    onDragEnd: (rawX: Float, rawY: Float) -> Unit,
) {
    val labelColor = if (isDarkMode) Color(0xFFE5E7EB) else SlateText
    val cellShape = RoundedCornerShape(MinimalLauncherCellCornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(120, easing = AvaEaseOut),
        label = "drawerIconPress",
    )
    val layoutCoordsRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var lastRawX by remember { mutableFloatStateOf(0f) }
    var lastRawY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val iconSizePx = with(density) { metrics.iconSize.roundToPx().coerceAtLeast(1) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cellShape)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .onGloballyPositioned { layoutCoordsRef[0] = it }
            .pointerInput(app.packageName, app.activityName, iconSizePx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val origin = layoutCoordsRef[0]
                            ?.takeIf { it.isAttached }
                            ?.positionOnScreen()
                            ?: Offset.Zero
                        val rawX = origin.x + offset.x
                        val rawY = origin.y + offset.y
                        lastRawX = rawX
                        lastRawY = rawY
                        val iconLeft = (size.width - iconSizePx) * 0.5f
                        val iconTop = with(density) { metrics.cellPadding.toPx() }
                        onDragStart(
                            rawX,
                            rawY,
                            iconSizePx,
                            iconSizePx,
                            offset.x - iconLeft,
                            offset.y - iconTop,
                        )
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        // After tray handoff, root/DragLayer owns MOVE with screen raw coords.
                        if (MinimalLauncherDragLayerRegistry.layer?.isDragActive == true) {
                            return@detectDragGesturesAfterLongPress
                        }
                        val origin = layoutCoordsRef[0]
                            ?.takeIf { it.isAttached }
                            ?.positionOnScreen()
                            ?: return@detectDragGesturesAfterLongPress
                        lastRawX = origin.x + change.position.x
                        lastRawY = origin.y + change.position.y
                        onDragMove(lastRawX, lastRawY)
                    },
                    onDragEnd = {
                        if (MinimalLauncherDragLayerRegistry.layer?.isDragActive != true) {
                            onDragEnd(lastRawX, lastRawY)
                        }
                    },
                    // Tray unmount / chrome hide CANCEL'd the stream and used to call
                    // onDragEnd → instant desktop place. L3 ignores CANCEL; DragLayer/root owns UP.
                    onDragCancel = {
                        MinimalLauncherDragLayerRegistry.layer?.activateDrag()
                    },
                )
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(metrics.cellPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        val bitmap = rememberShapedIconBitmap(
            drawable = app.icon,
            packageName = app.packageName,
            activityName = app.activityName,
            sizePx = iconSizePx,
            shape = iconShape,
            packPkg = iconPackPkg,
        )
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
        Image(
            bitmap = imageBitmap,
            contentDescription = app.label.toString(),
            modifier = Modifier.size(metrics.iconSize)
        )
        Text(
            text = app.label.toString(),
            fontSize = metrics.labelFontSize,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = metrics.labelSpacing)
                .fillMaxWidth()
                .heightIn(min = metrics.labelLineHeight),
            style = TextStyle(textAlign = TextAlign.Center),
        )
    }
}
