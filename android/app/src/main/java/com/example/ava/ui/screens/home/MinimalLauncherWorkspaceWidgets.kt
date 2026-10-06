package com.example.ava.ui.screens.home

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalConfiguration
import com.example.ava.ui.rememberPaneIsLandscape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.R
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.ui.screens.settings.components.AutoResizeText
import com.example.ava.widgets.AvaActionWidgets
import com.example.ava.widgets.AvaWidgetAction
import com.example.ava.ui.theme.SlateText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Launcher3 PageIndicatorMarker active/inactive transition. */
private const val PAGE_MARKER_ANIM_MS = 175

/** Launcher3 config_workspaceSpringLoadShrinkPercentage / 100. */
private const val SPRING_LOADED_SCALE = 0.87f
/** Launcher3 config_workspaceScrimAlpha / 100. */
private const val SPRING_LOADED_SCRIM = 0.55f
/** Launcher3 config_overlayTransitionTime. */
private const val SPRING_LOADED_MS = 300
/** Edge hover → next/prev page while dragging (tray add or desktop rearrange). */
private const val SPRING_HOVER_PAGE_MS = 500L

/** All Apps / Widgets tray → home (spring-loaded UI). Not desktop long-press. */
private fun isIncomingHomeDrag(session: MinimalLauncherDragSession?): Boolean {
    if (session?.phase != MinimalLauncherDragPhase.Dragging) return false
    return when (session.payload) {
        is MinimalLauncherDragPayload.FromAllApps,
        is MinimalLauncherDragPayload.FromWidgetsTray -> true
        else -> false
    }
}

/** Any live drag (tray or desktop) — enables edge paging + trailing empty page. */
private fun isActiveDrag(session: MinimalLauncherDragSession?): Boolean =
    session?.phase == MinimalLauncherDragPhase.Dragging

/**
 * Free-canvas Workspace: pages with absolute normalized placement (no cell grid),
 * icons + widgets, horizontal paging, drag to Remove / Uninstall / Info,
 * widgets sized from their own provider metrics and freely resizable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MinimalLauncherWorkspaceWidgets(
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false,
    showEmptyHint: Boolean = false,
    interactionEnabled: Boolean = true,
    /**
     * Horizontal paging. Size preview turns [interactionEnabled] off so icons cannot
     * be dragged, but overflow pages still have to be reachable.
     */
    pagingEnabled: Boolean = true,
    layoutLocked: Boolean = false,
    showDesktopLabels: Boolean = true,
    dragSession: MinimalLauncherDragSession?,
    onDragSessionChange: (MinimalLauncherDragSession?) -> Unit,
    onPageGeometryChange: (WorkspacePageGeometry?) -> Unit = {},
    onWorkspaceClick: () -> Unit = {},
    onWorkspaceLongClick: () -> Unit = {},
    /** Tapping the desktop's All Apps tile — the drawer has no gesture entry. */
    onOpenAllApps: () -> Unit = {},
    /** Tapping the desktop's Settings tile. */
    onOpenSettings: () -> Unit = {},
    /**
     * Pre-launch hook for the floating app window: return true to consume the
     * tap instead of the normal activity start (see [maybeLaunchInAppWindow]).
     */
    onWindowedLaunch: (MinimalLauncherApp) -> Boolean = { false },
    /**
     * Layout to draw instead of the persisted icon store. Content passes the size-adjust
     * preview through here; without it the workspace only grew boxes in place and the
     * overflow math never appeared on screen.
     */
    iconsOverride: List<PlacedMinimalLauncherIcon>? = null,
    /**
     * Keep the DragLayer composed, but stop it painting when a Compose overlay is
     * covering the desktop. HostViews and the nested ComposeView live in View-world
     * and can otherwise composite above [AnimatedVisibility] siblings (All Apps).
     *
     * Stay visible while dragging from a tray — that path unmounts the overlay on
     * purpose so the spring-loaded workspace is the drop target.
     */
    desktopLayerVisible: Boolean = true,
) {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val widgetsStored by MinimalLauncherWidgetsStore.widgetsFlow.collectAsStateWithLifecycle()
    val iconsStored by MinimalLauncherIconsStore.iconsFlow.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // Logical 320×320 reports w426dp×h354dp after bars. That is not landscape.
    val isLandscape = rememberPaneIsLandscape()
    // Port/land each have their own free-canvas placement — layout uses the active orientation only.
    val widgets = remember(widgetsStored, isLandscape) {
        widgetsStored.forLayout(isLandscape)
    }
    val icons = remember(iconsStored, iconsOverride, isLandscape) {
        (iconsOverride ?: iconsStored).forLayout(isLandscape)
    }
    var resizingId by remember { mutableStateOf<Int?>(null) }
    var pageGeometry by remember { mutableStateOf<WorkspacePageGeometry?>(null) }
    // Cancel delayed addResizeFrame if user starts another drag (was killing 2nd drag).
    var resizeScheduleGen by remember { mutableIntStateOf(0) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val dragLayerHolder = remember { arrayOfNulls<MinimalLauncherDragLayer>(1) }
    fun cancelScheduledResize() {
        resizeScheduleGen += 1
    }
    fun endDragSession() {
        dragLayerHolder[0]?.deactivateDrag()
        onDragSessionChange(null)
    }
    LaunchedEffect(pageGeometry) { onPageGeometryChange(pageGeometry) }
    LaunchedEffect(layoutLocked) {
        if (layoutLocked) {
            cancelScheduledResize()
            resizingId = null
            endDragSession()
        }
    }
    // Orientation swap loads the other placement — dismiss resize chrome tied to old spans
    // and drop the stale page geometry (old orientation's origin/size would misplace
    // the first drop until onGloballyPositioned refires).
    LaunchedEffect(isLandscape) {
        cancelScheduledResize()
        resizingId = null
        pageGeometry = null
    }

    // L3 addExtraEmptyScreenOnDrag: the trailing "+" page exists for EVERY live drag
    // (tray add or desktop rearrange) so items can move to a brand-new page; when
    // idle the pager holds only occupied pages — no ghost trailing blank.
    // Spring-loaded chrome stays tray-only (desktop rearrange keeps full scale).
    val incomingHomeDrag = isIncomingHomeDrag(dragSession)
    val activeDrag = isActiveDrag(dragSession)
    val showExtraEmpty = activeDrag
    val occupiedPages = remember(icons, widgets) {
        MinimalLauncherWorkspaceLayout.occupiedPageCount(icons, widgets)
    }
    val pageCount = remember(icons, widgets, showExtraEmpty) {
        MinimalLauncherWorkspaceLayout.pageCount(icons, widgets, withExtraEmpty = showExtraEmpty)
    }
    val extraEmptyIndex = remember(icons, widgets, showExtraEmpty) {
        if (showExtraEmpty) {
            MinimalLauncherWorkspaceLayout.extraEmptyPageIndex(
                icons, widgets, withExtraEmpty = true,
            )
        } else {
            -1
        }
    }
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val indicatorPageCount = pageCount

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val densityPx = density.density

    fun withFinger(session: MinimalLauncherDragSession, rawX: Float, rawY: Float) =
        session.copy(fingerX = rawX, fingerY = rawY)

    fun settleDrag(
        session: MinimalLauncherDragSession,
        settleTarget: MinimalLauncherSettleTarget? = null,
    ) {
        dragLayerHolder[0]?.deactivateDrag()
        // Keep overlay for fly-in settle + bar/ghost fade-out.
        onDragSessionChange(
            session.copy(
                phase = MinimalLauncherDragPhase.Settling,
                settleTarget = settleTarget,
            )
        )
    }

    fun finishDesktopDrag(session: MinimalLauncherDragSession) {
        if (session.phase != MinimalLauncherDragPhase.Dragging) return
        val showUninstall = dropTargetShowsUninstall(appContext, session.payload)
        when (resolveDropTarget(session, screenWidthPx, densityPx, isLandscape, showUninstall)) {
            MinimalLauncherDropTarget.Remove -> {
                when (val p = session.payload) {
                    is MinimalLauncherDragPayload.DesktopIcon ->
                        MinimalLauncherIconsStore.remove(appContext, p.icon.id)
                    is MinimalLauncherDragPayload.DesktopWidget ->
                        MinimalLauncherWidgetsStore.remove(appContext, p.widget.appWidgetId)
                    is MinimalLauncherDragPayload.FromWidgetsTray ->
                        MinimalLauncherWidgetHost.deleteAppWidgetId(appContext, p.appWidgetId)
                    is MinimalLauncherDragPayload.FromAllApps -> Unit
                }
                toastItemRemoved(appContext)
                stripEmptyScreens(appContext)
                settleDrag(session, settleTarget = null)
                return
            }
            MinimalLauncherDropTarget.Uninstall -> {
                // Icon stays put — MinimalLauncherAppsWatcher removes it once the
                // package is actually gone (Launcher3 UninstallDropTarget flow).
                packageNameOf(session.payload)?.let { requestUninstall(context, it) }
                settleDrag(session, settleTarget = null)
                return
            }
            MinimalLauncherDropTarget.Info -> {
                packageNameOf(session.payload)?.let { openAppDetails(context, it) }
                // Cancel tray widget bind if user opens info instead of dropping.
                (session.payload as? MinimalLauncherDragPayload.FromWidgetsTray)?.let {
                    MinimalLauncherWidgetHost.deleteAppWidgetId(appContext, it.appWidgetId)
                }
                settleDrag(session, settleTarget = null)
                return
            }
            MinimalLauncherDropTarget.None -> Unit
        }
        // Never commit a drop with geometry left over from another Pager page.
        val geo = pageGeometry?.takeIf { it.page == pagerState.currentPage }
        // Free-canvas drop at the finger. Never invent a stagger position when
        // geometry is missing (that looked like a random reshuffle after rotate).
        val placed = if (geo != null) {
            val targetIcons = icons.filter { it.screen == geo.page }
            val targetWidgets = widgets.filter { it.screen == geo.page }
            tryPlaceDrag(
                appContext, isLandscape, session, geo,
                targetIcons, targetWidgets, showDesktopLabels,
            )
        } else {
            false
        }
        if (!placed) {
            (session.payload as? MinimalLauncherDragPayload.FromWidgetsTray)?.let {
                MinimalLauncherWidgetHost.deleteAppWidgetId(appContext, it.appWidgetId)
            }
        }
        stripEmptyScreens(appContext)
        val settle = if (placed) {
            geo?.let {
                val targetIcons = icons.filter { icon -> icon.screen == it.page }
                val targetWidgets = widgets.filter { widget -> widget.screen == it.page }
                computeSettleTarget(
                    appContext, session, it, isLandscape,
                    targetIcons, targetWidgets, showDesktopLabels,
                )
            }
        } else {
            null
        }
        val droppedWidgetId = if (placed) {
            when (val p = session.payload) {
                is MinimalLauncherDragPayload.DesktopWidget -> p.widget.appWidgetId
                is MinimalLauncherDragPayload.FromWidgetsTray -> p.appWidgetId
                else -> null
            }
        } else {
            null
        }
        settleDrag(session, settleTarget = settle)
        if (droppedWidgetId != null && !layoutLocked) {
            val gen = resizeScheduleGen + 1
            resizeScheduleGen = gen
            mainHandler.postDelayed({
                if (gen != resizeScheduleGen) return@postDelayed
                resizingId = droppedWidgetId
            }, 280L)
        }
    }

    BackHandler(enabled = dragSession != null || resizingId != null) {
        when {
            dragSession != null -> {
                cancelScheduledResize()
                endDragSession()
                stripEmptyScreens(appContext)
            }
            else -> resizingId = null
        }
    }

    LaunchedEffect(Unit) {
        MinimalLauncherWidgetHost.get(appContext)
        MinimalLauncherWidgetsStore.load(appContext)
        MinimalLauncherIconsStore.load(appContext)
        stripEmptyScreens(appContext)
    }

    val latestDrag = rememberUpdatedState(dragSession)
    val latestOnDragSessionChange = rememberUpdatedState(onDragSessionChange)

    LaunchedEffect(pageCount) {
        if (pagerState.currentPage >= pageCount) {
            pagerState.scrollToPage((pageCount - 1).coerceAtLeast(0))
        }
    }
    // Idle: if user is parked on the reserved trailing blank, snap back home-side.
    LaunchedEffect(showExtraEmpty, occupiedPages) {
        if (!showExtraEmpty && pagerState.currentPage >= occupiedPages) {
            pagerState.scrollToPage((occupiedPages - 1).coerceAtLeast(0))
        }
    }

    // Prune desktop icons whose activities vanished. Tiles have no activity to resolve,
    // so they are exempt or this would wipe them on the very first composition.
    LaunchedEffect(icons) {
        icons.forEach { placed ->
            if (MinimalLauncherTiles.isTile(placed.packageName)) return@forEach
            if (resolveMinimalLauncherApp(appContext, placed.packageName, placed.activityName) == null) {
                MinimalLauncherIconsStore.remove(appContext, placed.id)
            }
        }
    }
    val emptyDesktop = icons.isEmpty() && widgets.isEmpty()
    val openOverview = rememberUpdatedState(onWorkspaceLongClick)
    // Keep detector installed always — removing pointerInput when drag starts CANCEL'd gestures.
    val overviewAllowed = rememberUpdatedState(
        interactionEnabled && dragSession == null
    )

    // While dragging the DragLayer owns the finger, so page changes happen via the
    // edge-hover scroll zone (below), never via Pager swipe.
    val pagerScrollEnabled =
        pagingEnabled && resizingId == null && dragSession == null

    // L3 DragController scroll zone: ANY live drag (tray add or desktop rearrange)
    // hovering the left/right band flips to the neighbor / trailing blank page.
    LaunchedEffect(
        dragSession?.fingerX,
        dragSession?.phase,
        dragSession?.payload,
        pageCount,
        pagerState.currentPage,
    ) {
        if (!isActiveDrag(dragSession)) return@LaunchedEffect
        val session = dragSession ?: return@LaunchedEffect
        if (pageCount <= 1) return@LaunchedEffect
        // L3-style narrow edge scroll zone. The old 22%/78% fractional bands were
        // wider than a whole column in landscape — hovering anywhere near the right
        // side flipped the page, making right-edge cells impossible to drop on.
        val edgeZonePx = with(density) { 32.dp.toPx() }
        val realWidthPx = context.resources.displayMetrics.widthPixels
            .toFloat()
            .coerceAtLeast(screenWidthPx)
        val leftBand = edgeZonePx
        val rightBand = realWidthPx - edgeZonePx
        fun hoverPageFor(x: Float): Int {
            val raw = when {
                x < leftBand -> pagerState.currentPage - 1
                x > rightBand -> pagerState.currentPage + 1
                else -> pagerState.currentPage
            }
            return raw.coerceIn(0, pageCount - 1)
        }
        val target = hoverPageFor(session.fingerX)
        if (target == pagerState.currentPage) return@LaunchedEffect
        delay(SPRING_HOVER_PAGE_MS)
        val still = latestDrag.value ?: return@LaunchedEffect
        if (!isActiveDrag(still)) return@LaunchedEffect
        val confirmed = hoverPageFor(still.fingerX)
        if (confirmed == target && confirmed != pagerState.currentPage) {
            pagerState.animateScrollToPage(confirmed)
        }
    }

    val parentComposition = rememberCompositionContext()
    val applyHover = rememberUpdatedState { rawX: Float, rawY: Float ->
        val current = latestDrag.value ?: return@rememberUpdatedState
        if (current.phase != MinimalLauncherDragPhase.Dragging) return@rememberUpdatedState
        latestOnDragSessionChange.value(withFinger(current, rawX, rawY))
    }
    val applyDrop = rememberUpdatedState { rawX: Float, rawY: Float ->
        val current = latestDrag.value ?: return@rememberUpdatedState
        if (current.phase != MinimalLauncherDragPhase.Dragging) return@rememberUpdatedState
        finishDesktopDrag(withFinger(current, rawX, rawY))
    }
    // Track prior session so AndroidView.update can deactivate after a real end,
    // without racing HostView.activateDrag() before dragSession Compose state commits.
    val layerSawDragging = remember { booleanArrayOf(false) }

    // Snapshot inputs for the nested ComposeHost — do NOT reassign host.content every
    // finger move (that remounted workspace AndroidViews and broke the 2nd drag).
    val dragSessionState = rememberUpdatedState(dragSession)
    val iconsState = rememberUpdatedState(icons)
    val widgetsState = rememberUpdatedState(widgets)
    val isDarkModeState = rememberUpdatedState(isDarkMode)
    val showEmptyHintState = rememberUpdatedState(showEmptyHint)
    val layoutLockedState = rememberUpdatedState(layoutLocked)
    val interactionEnabledState = rememberUpdatedState(interactionEnabled)
    val showDesktopLabelsState = rememberUpdatedState(showDesktopLabels)
    val resizingIdState = rememberUpdatedState(resizingId)
    val pagerScrollEnabledState = rememberUpdatedState(pagerScrollEnabled)
    val pageCountState = rememberUpdatedState(pageCount)
    val indicatorPageCountState = rememberUpdatedState(indicatorPageCount)
    val extraEmptyIndexState = rememberUpdatedState(extraEmptyIndex)
    val emptyDesktopState = rememberUpdatedState(emptyDesktop)
    val onWorkspaceClickState = rememberUpdatedState(onWorkspaceClick)
    val onWorkspaceLongClickState = rememberUpdatedState(onWorkspaceLongClick)
    val isLandscapeState = rememberUpdatedState(isLandscape)
    val onOpenAllAppsState = rememberUpdatedState(onOpenAllApps)
    val onOpenSettingsState = rememberUpdatedState(onOpenSettings)
    val onWindowedLaunchState = rememberUpdatedState(onWindowedLaunch)

    // Ancestor DragLayer (L3): owns MOVE/UP after startDrag. Must wrap HostViews.
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            MinimalLauncherDragLayer(ctx).apply {
                val host = MinimalLauncherDragLayerComposeHost(ctx).apply {
                    setParentCompositionContext(parentComposition)
                    // Set once — reads rememberUpdatedState so finger moves don't remount HostViews.
                    content = {
                        val session = dragSessionState.value
                        val pageIcons = iconsState.value
                        val pageWidgets = widgetsState.value
                        val dark = isDarkModeState.value
                        val hint = showEmptyHintState.value
                        val locked = layoutLockedState.value
                        val interact = interactionEnabledState.value
                        val showLabels = showDesktopLabelsState.value
                        val resizeId = resizingIdState.value
                        val scrollEnabled = pagerScrollEnabledState.value
                        val pages = pageCountState.value
                        val indicatorPages = indicatorPageCountState.value
                        val plusIndex = extraEmptyIndexState.value
                        val desktopEmpty = emptyDesktopState.value
                        val landscape = isLandscapeState.value
                        // Spring-loaded only when placing from Apps / Widgets tray.
                        val springTarget = if (isIncomingHomeDrag(session)) 1f else 0f
                        val springProgress by animateFloatAsState(
                            targetValue = springTarget,
                            animationSpec = tween(
                                SPRING_LOADED_MS,
                                easing = FastOutSlowInEasing,
                            ),
                            label = "springLoaded",
                        )
                        val workspaceScale = lerp(1f, SPRING_LOADED_SCALE, springProgress)
                        val sidePeek = 40.dp * springProgress
                        val pageGap = 14.dp * springProgress
                        val panelCorner = 18.dp * springProgress
                        val panelAlpha = springProgress * (if (dark) 0.20f else 0.38f)
                        val scrimAlpha = springProgress * SPRING_LOADED_SCRIM
                        val leftEdgeGlow = remember { Animatable(0f) }
                        val rightEdgeGlow = remember { Animatable(0f) }
                        val edgeGlowScope = rememberCoroutineScope()
                        val edgePageState = rememberUpdatedState(pagerState.currentPage)
                        val edgePageCountState = rememberUpdatedState(pages)
                        val edgeScrollEnabledState = rememberUpdatedState(scrollEnabled)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            if (!overviewAllowed.value) continue
                                            if (event.changes.count { it.pressed } < 2) continue
                                            var held = true
                                            val deadline = SystemClock.uptimeMillis() + 280L
                                            while (SystemClock.uptimeMillis() < deadline) {
                                                val remaining =
                                                    (deadline - SystemClock.uptimeMillis())
                                                        .coerceAtLeast(1L)
                                                val next = withTimeoutOrNull(remaining) {
                                                    awaitPointerEvent(PointerEventPass.Initial)
                                                }
                                                if (next == null) break
                                                if (next.changes.count { it.pressed } < 2) {
                                                    held = false
                                                    break
                                                }
                                            }
                                            if (!held) continue
                                            openOverview.value()
                                            while (true) {
                                                val up = awaitPointerEvent(PointerEventPass.Initial)
                                                if (up.changes.none { it.pressed }) break
                                            }
                                        }
                                    }
                                }
                                // Launcher3 PagedView dampedOverScroll: observe without
                                // consuming so HorizontalPager keeps full gesture ownership.
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        var lastX = 0f
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Final)
                                            val change = event.changes.firstOrNull() ?: continue
                                            if (change.pressed && !change.previousPressed) {
                                                lastX = change.position.x
                                                continue
                                            }
                                            if (change.pressed && change.previousPressed) {
                                                val dx = change.position.x - lastX
                                                lastX = change.position.x
                                                if (!edgeScrollEnabledState.value || dx == 0f) continue
                                                val pageIndex = edgePageState.value
                                                val lastPage =
                                                    (edgePageCountState.value - 1).coerceAtLeast(0)
                                                val pull = (kotlin.math.abs(dx) /
                                                    size.width.coerceAtLeast(1)) * 0.8f
                                                when {
                                                    pageIndex == 0 && dx > 0f -> {
                                                        val next =
                                                            (leftEdgeGlow.value + pull)
                                                                .coerceAtMost(0.5f)
                                                        edgeGlowScope.launch {
                                                            rightEdgeGlow.snapTo(0f)
                                                            leftEdgeGlow.snapTo(next)
                                                        }
                                                    }
                                                    pageIndex == lastPage && dx < 0f -> {
                                                        val next =
                                                            (rightEdgeGlow.value + pull)
                                                                .coerceAtMost(0.5f)
                                                        edgeGlowScope.launch {
                                                            leftEdgeGlow.snapTo(0f)
                                                            rightEdgeGlow.snapTo(next)
                                                        }
                                                    }
                                                }
                                            }
                                            if (!change.pressed && change.previousPressed) {
                                                edgeGlowScope.launch {
                                                    leftEdgeGlow.animateTo(
                                                        0f,
                                                        tween(
                                                            durationMillis = 600,
                                                            easing = FastOutSlowInEasing,
                                                        ),
                                                    )
                                                }
                                                edgeGlowScope.launch {
                                                    rightEdgeGlow.animateTo(
                                                        0f,
                                                        tween(
                                                            durationMillis = 600,
                                                            easing = FastOutSlowInEasing,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                        ) {
                            // DragLayer scrim — soft black behind shrunk pages.
                            if (scrimAlpha > 0.01f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = scrimAlpha))
                                )
                            }

                            // Scaled workspace + peeked neighbors (bg_screenpanel feel).
                            // Ghost / drop bar stay outside so they are not shrunk.
                            // ALWAYS clip — even during spring-loaded peek the scaled
                            // content (87%) is smaller than the full-size bounds, so
                            // nothing visible is cut. Prevents widget/icon residue from
                            // adjacent pages bleeding through during swipe or drag.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clipToBounds()
                                    .graphicsLayer {
                                        scaleX = workspaceScale
                                        scaleY = workspaceScale
                                        clip = true
                                    }
                            ) {
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clipToBounds(),
                                    userScrollEnabled = scrollEnabled,
                                    contentPadding = PaddingValues(horizontal = sidePeek),
                                    pageSpacing = pageGap,
                                    beyondViewportPageCount = 1,
                                ) { page ->
                                    val panelShape = RoundedCornerShape(panelCorner)
                                    // Overview-only insets (bg_screenpanel). Must stay 0 in
                                    // normal desktop — otherwise they stack on top of the
                                    // Launcher3 12dp workspace L/R margin from HomeScreen.
                                    val overviewOuterPadH = 2.dp * springProgress
                                    val overviewOuterPadV = 10.dp * springProgress
                                    val overviewInnerPad = 6.dp * springProgress
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            // Hard page mask: previous page HostViews / icons
                                            // cannot paint into this slot while paging.
                                            .clipToBounds()
                                            .padding(
                                                horizontal = overviewOuterPadH,
                                                vertical = overviewOuterPadV,
                                            )
                                            .then(
                                                if (panelAlpha > 0.01f) {
                                                    Modifier
                                                        .background(
                                                            Color.White.copy(alpha = panelAlpha),
                                                            panelShape,
                                                        )
                                                        .clip(panelShape)
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .padding(overviewInnerPad)
                                    ) {
                                        WorkspacePage(
                                            page = page,
                                            isLandscape = landscape,
                                            icons = pageIcons.filter { it.screen == page },
                                            widgets = pageWidgets.filter { it.screen == page },
                                            isDarkMode = dark,
                                            showEmptyHint = hint && page == 0 && desktopEmpty,
                                            layoutLocked = locked,
                                            interactionEnabled = interact,
                                            showDesktopLabels = showLabels,
                                            resizingId = resizeId,
                                            draggingWidgetId =
                                                (session?.payload as? MinimalLauncherDragPayload.DesktopWidget)
                                                    ?.widget?.appWidgetId,
                                            draggingIconId =
                                                (session?.payload as? MinimalLauncherDragPayload.DesktopIcon)
                                                    ?.icon?.id,
                                            onWorkspaceClick = onWorkspaceClickState.value,
                                            onWorkspaceLongClick = onWorkspaceLongClickState.value,
                                            onResizingIdChange = { resizingId = it },
                                            onPageGeometry = { origin, widthPx, heightPx ->
                                                if (pagerState.currentPage == page) {
                                                    pageGeometry = WorkspacePageGeometry(
                                                        originX = origin.x,
                                                        originY = origin.y,
                                                        widthPx = widthPx,
                                                        heightPx = heightPx,
                                                        page = page,
                                                    )
                                                }
                                            },
                                            onWidgetDragStart = { started ->
                                                cancelScheduledResize()
                                                resizingId = null
                                                onDragSessionChange(started)
                                            },
                                            onWidgetDragMove = { _, _, _ -> },
                                            onWidgetDragEnd = { _, _, _ -> },
                                            onWidgetDragCancel = { },
                                            onIconDragStart = { started ->
                                                cancelScheduledResize()
                                                resizingId = null
                                                onDragSessionChange(started)
                                            },
                                            onIconDragMove = iMove@{ rawX, rawY, iconId ->
                                                val current = latestDrag.value ?: return@iMove
                                                val payload = current.payload
                                                    as? MinimalLauncherDragPayload.DesktopIcon
                                                    ?: return@iMove
                                                if (payload.icon.id != iconId) return@iMove
                                                if (current.phase != MinimalLauncherDragPhase.Dragging) {
                                                    return@iMove
                                                }
                                                onDragSessionChange(withFinger(current, rawX, rawY))
                                            },
                                            onIconDragEnd = iEnd@{ rawX, rawY, iconId ->
                                                val current = latestDrag.value ?: return@iEnd
                                                val payload = current.payload
                                                    as? MinimalLauncherDragPayload.DesktopIcon
                                                    ?: return@iEnd
                                                if (payload.icon.id != iconId) return@iEnd
                                                if (current.phase != MinimalLauncherDragPhase.Dragging) {
                                                    return@iEnd
                                                }
                                                finishDesktopDrag(withFinger(current, rawX, rawY))
                                            },
                                            onIconLaunch = launch@{ app ->
                                                if (layoutLockedState.value) return@launch
                                                if (MinimalLauncherTiles.isTile(app.packageName)) {
                                                    if (
                                                        MinimalLauncherTiles.isSettings(
                                                            app.packageName,
                                                            app.activityName,
                                                        )
                                                    ) {
                                                        onOpenSettingsState.value()
                                                    } else {
                                                        onOpenAllAppsState.value()
                                                    }
                                                    return@launch
                                                }
                                                if (onWindowedLaunchState.value(app)) {
                                                    return@launch
                                                }
                                                try {
                                                    context.startActivity(
                                                        Intent()
                                                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                            .setComponent(
                                                                ComponentName(
                                                                    app.packageName,
                                                                    app.activityName,
                                                                )
                                                            )
                                                    )
                                                } catch (_: Exception) {
                                                    MinimalLauncherIconsStore.removePackage(
                                                        appContext, app.packageName
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                            }

                            WorkspaceEdgeGlow(
                                leftPull = leftEdgeGlow.value,
                                rightPull = rightEdgeGlow.value,
                                modifier = Modifier.fillMaxSize(),
                            )

                            if (indicatorPages > 1 || plusIndex >= 0) {
                                WorkspacePageIndicator(
                                    pageCount = indicatorPages,
                                    currentPage = pagerState.currentPage.coerceAtMost(
                                        (indicatorPages - 1).coerceAtLeast(0)
                                    ),
                                    extraEmptyIndex = plusIndex,
                                    isDarkMode = dark,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 10.dp)
                                        .graphicsLayer { alpha = 1f }
                                )
                            }

                            if (session != null) {
                                // Rulers and collision guides are strictly page-local.
                                // During Pager animation, suppress stale geometry from
                                // the page we just left instead of snapping against it.
                                val rulerPage = pagerState.currentPage
                                val rulerGeometry = pageGeometry?.takeIf {
                                    it.page == rulerPage
                                }
                                MinimalLauncherDragOverlay(
                                    session = session,
                                    isDarkMode = dark,
                                    screenWidthPx = screenWidthPx,
                                    showDesktopLabels = showDesktopLabelsState.value,
                                    pageGeometry = rulerGeometry,
                                    alignIcons = pageIcons.filter { it.screen == rulerPage },
                                    alignWidgets = pageWidgets.filter { it.screen == rulerPage },
                                    onSettled = {
                                        // Fade finished — clear session only; delayed resize may still arm.
                                        onDragSessionChange(null)
                                    }
                                )
                            }
                        }
                    }
                }
                addView(
                    host,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                )
            }
        },
        update = { layer ->
            dragLayerHolder[0] = layer
            MinimalLauncherDragLayerRegistry.layer = layer
            layer.onDragMove = { rawX, rawY -> applyHover.value(rawX, rawY) }
            layer.onDragEnd = { rawX, rawY -> applyDrop.value(rawX, rawY) }
            // View.INVISIBLE, not Compose alpha: graphicsLayer does not hide HostViews.
            // suppressDraw stays in effect even if AndroidView restores VISIBLE, and
            // the layer swallows child invalidates so a widget tick cannot restack
            // icons above Mod Store / browser on API 28–29.
            layer.suppressDraw = !desktopLayerVisible
            layer.visibility = if (desktopLayerVisible) View.VISIBLE else View.INVISIBLE
            layer.alpha = if (desktopLayerVisible) 1f else 0f
            val dragging = dragSession?.phase == MinimalLauncherDragPhase.Dragging
            // Deactivate only after we previously observed a live session. Never stomp
            // activateDrag() when session state has not committed yet (follow-finger loss).
            if (layerSawDragging[0] && !dragging) {
                layer.deactivateDrag()
            }
            layerSawDragging[0] = dragging
        }
    )
}

/**
 * All Apps / Widgets tray → workspace drop (Content root / panel end).
 * Mirrors [finishDesktopDrag] place + L3 animateViewIntoPosition settle target.
 */
fun finishIncomingWorkspaceDrag(
    context: android.content.Context,
    isLandscape: Boolean,
    session: MinimalLauncherDragSession,
    pageGeometry: WorkspacePageGeometry?,
    showDesktopLabels: Boolean = true,
    @Suppress("UNUSED_PARAMETER") icons: List<PlacedMinimalLauncherIcon> = emptyList(),
    @Suppress("UNUSED_PARAMETER") widgets: List<PlacedMinimalLauncherWidget> = emptyList(),
): MinimalLauncherDragSession? {
    if (session.phase != MinimalLauncherDragPhase.Dragging) return session
    val appContext = context.applicationContext
    val metrics = context.resources.displayMetrics
    val showUninstall = dropTargetShowsUninstall(appContext, session.payload)
    when (
        resolveDropTarget(
            session,
            metrics.widthPixels.toFloat(),
            metrics.density,
            isLandscape,
            showUninstall,
        )
    ) {
        MinimalLauncherDropTarget.Remove -> {
            (session.payload as? MinimalLauncherDragPayload.FromWidgetsTray)?.let {
                MinimalLauncherWidgetHost.deleteAppWidgetId(appContext, it.appWidgetId)
            }
            return session.copy(phase = MinimalLauncherDragPhase.Settling, settleTarget = null)
        }
        MinimalLauncherDropTarget.Uninstall -> {
            packageNameOf(session.payload)?.let { requestUninstall(context, it) }
            return session.copy(phase = MinimalLauncherDragPhase.Settling, settleTarget = null)
        }
        MinimalLauncherDropTarget.Info -> {
            packageNameOf(session.payload)?.let { openAppDetails(context, it) }
            (session.payload as? MinimalLauncherDragPayload.FromWidgetsTray)?.let {
                MinimalLauncherWidgetHost.deleteAppWidgetId(appContext, it.appWidgetId)
            }
            return session.copy(phase = MinimalLauncherDragPhase.Settling, settleTarget = null)
        }
        MinimalLauncherDropTarget.None -> Unit
    }
    val geo = pageGeometry
    val icons = MinimalLauncherIconsStore.iconsFlow.value.forLayout(isLandscape)
    val widgets = MinimalLauncherWidgetsStore.widgetsFlow.value.forLayout(isLandscape)
    val placed = if (geo != null) {
        val targetIcons = icons.filter { it.screen == geo.page }
        val targetWidgets = widgets.filter { it.screen == geo.page }
        tryPlaceDrag(
            appContext, isLandscape, session, geo,
            targetIcons, targetWidgets, showDesktopLabels,
        )
    } else {
        false
    }
    if (!placed) {
        (session.payload as? MinimalLauncherDragPayload.FromWidgetsTray)?.let {
            MinimalLauncherWidgetHost.deleteAppWidgetId(appContext, it.appWidgetId)
        }
    }
    stripEmptyScreens(appContext)
    val settle = if (placed) {
        geo?.let {
            val targetIcons = icons.filter { icon -> icon.screen == it.page }
            val targetWidgets = widgets.filter { widget -> widget.screen == it.page }
            computeSettleTarget(
                appContext, session, it, isLandscape,
                targetIcons, targetWidgets, showDesktopLabels,
            )
        }
    } else {
        null
    }
    return session.copy(phase = MinimalLauncherDragPhase.Settling, settleTarget = settle)
}

/**
 * Free-canvas drop: finger point, then smart-align edges/centers to siblings
 * and the page itself. Icons are auto-separated (no stack / no zero-gap).
 * Widgets stay free-canvas (may overlap icons).
 */
private fun tryPlaceDrag(
    appContext: android.content.Context,
    isLandscape: Boolean,
    session: MinimalLauncherDragSession,
    geo: WorkspacePageGeometry,
    icons: List<PlacedMinimalLauncherIcon>,
    widgets: List<PlacedMinimalLauncherWidget>,
    showDesktopLabels: Boolean,
): Boolean {
    if (geo.widthPx <= 0f || geo.heightPx <= 0f) return false
    val density = appContext.resources.displayMetrics.density
    val thresh = alignThreshNorm(geo.widthPx, geo.heightPx, density)
    val (iconW, iconH) = iconBoxNorm(
        appContext, geo.widthPx, geo.heightPx, showLabel = showDesktopLabels,
    )

    fun alignWidget(
        raw: CanvasPoint,
        w: Float,
        h: Float,
        exceptWidgetId: Int? = null,
    ): CanvasPoint {
        val guides = buildAlignGuides(
            geo.page, emptyList(), widgets, iconW, iconH, exceptIconId = null, exceptWidgetId,
        )
        return smartAlignTopLeft(raw.x, raw.y, w, h, guides, thresh)
    }

    fun alignIcon(raw: CanvasPoint, exceptIconId: String? = null): CanvasPoint =
        placeIconWithAlign(
            raw.x, raw.y, iconW, iconH,
            geo.page, icons, widgets, exceptIconId,
            thresh, geo.widthPx, geo.heightPx, density,
        ).point

    return when (val p = session.payload) {
        is MinimalLauncherDragPayload.FromAllApps -> {
            val raw = freeDropTopLeft(session, geo, iconW, iconH)
            val point = alignIcon(raw)
            MinimalLauncherIconsStore.placeAt(
                appContext, isLandscape, p.app.packageName, p.app.activityName,
                geo.page, point.x, point.y,
            )
            true
        }
        is MinimalLauncherDragPayload.DesktopIcon -> {
            val raw = freeDropTopLeft(session, geo, iconW, iconH)
            val point = alignIcon(raw, exceptIconId = p.icon.id)
            MinimalLauncherIconsStore.move(
                appContext, isLandscape, p.icon.id, geo.page, point.x, point.y,
            )
        }
        is MinimalLauncherDragPayload.DesktopWidget -> {
            val active = p.widget.placement(isLandscape)
            val raw = freeDropTopLeft(session, geo, active.w, active.h)
            val point = alignWidget(raw, active.w, active.h, exceptWidgetId = p.widget.appWidgetId)
            MinimalLauncherWidgetsStore.move(
                appContext, isLandscape, p.widget.appWidgetId, geo.page, point.x, point.y,
            )
        }
        is MinimalLauncherDragPayload.FromWidgetsTray -> {
            val wCap = if (isLandscape) MINIMAL_LAUNCHER_WIDGET_MAX_LAND_W_FRAC else 1f
            val wNorm = (session.widthPx / geo.widthPx)
                .coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, wCap)
            val hNorm = (session.heightPx / geo.heightPx)
                .coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
            val raw = freeDropTopLeft(session, geo, wNorm, hNorm)
            val pageEmpty = icons.isEmpty() && widgets.isEmpty()
            val point = if (pageEmpty && isAvaTitleDescriptionWidget(p.provider)) {
                avaEmptyPageDropPoint(raw, wNorm, hNorm)
            } else {
                alignWidget(raw, wNorm, hNorm, exceptWidgetId = p.appWidgetId)
            }
            MinimalLauncherWidgetsStore.placeAt(
                appContext, isLandscape, p.appWidgetId, p.provider,
                geo.page, point.x, point.y, wNorm, hNorm,
            ) != null
        }
    }
}

/** Screen-space top-left of the ghost after drop (L3 animateViewIntoPosition). */
private fun computeSettleTarget(
    appContext: android.content.Context,
    session: MinimalLauncherDragSession,
    geo: WorkspacePageGeometry,
    isLandscape: Boolean,
    icons: List<PlacedMinimalLauncherIcon>,
    widgets: List<PlacedMinimalLauncherWidget>,
    showDesktopLabels: Boolean,
): MinimalLauncherSettleTarget? {
    if (geo.widthPx <= 0f || geo.heightPx <= 0f) return null
    val (iw, ih) = when (val p = session.payload) {
        is MinimalLauncherDragPayload.FromWidgetsTray -> {
            val wCap = if (isLandscape) MINIMAL_LAUNCHER_WIDGET_MAX_LAND_W_FRAC else 1f
            (session.widthPx / geo.widthPx).coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, wCap) to
                (session.heightPx / geo.heightPx).coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
        }
        is MinimalLauncherDragPayload.DesktopWidget -> {
            val active = p.widget.placement(isLandscape)
            active.w to active.h
        }
        else -> iconBoxNorm(
            appContext, geo.widthPx, geo.heightPx, showLabel = showDesktopLabels,
        )
    }
    val (iconW, iconH) = iconBoxNorm(
        appContext, geo.widthPx, geo.heightPx, showLabel = showDesktopLabels,
    )
    val exceptIcon = (session.payload as? MinimalLauncherDragPayload.DesktopIcon)?.icon?.id
    val exceptWidget = when (val p = session.payload) {
        is MinimalLauncherDragPayload.DesktopWidget -> p.widget.appWidgetId
        is MinimalLauncherDragPayload.FromWidgetsTray -> p.appWidgetId
        else -> null
    }
    val raw = freeDropTopLeft(session, geo, iw, ih)
    val density = android.content.res.Resources.getSystem().displayMetrics.density
    val thresh = alignThreshNorm(geo.widthPx, geo.heightPx, density)
    val isIconDrop = when (session.payload) {
        is MinimalLauncherDragPayload.DesktopIcon,
        is MinimalLauncherDragPayload.FromAllApps -> true
        else -> false
    }
    val point = if (isIconDrop) {
        placeIconWithAlign(
            raw.x, raw.y, iconW, iconH,
            geo.page, icons, widgets, exceptIcon,
            thresh, geo.widthPx, geo.heightPx, density,
        ).point
    } else {
        val payload = session.payload
        val pageEmpty = icons.isEmpty() && widgets.isEmpty()
        if (pageEmpty &&
            payload is MinimalLauncherDragPayload.FromWidgetsTray &&
            isAvaTitleDescriptionWidget(payload.provider)
        ) {
            avaEmptyPageDropPoint(raw, iw, ih)
        } else {
            val guides = buildAlignGuides(
                geo.page, emptyList(), widgets, iconW, iconH, exceptIcon, exceptWidget,
            )
            smartAlignTopLeft(raw.x, raw.y, iw, ih, guides, thresh)
        }
    }
    val w = session.widthPx.coerceAtLeast(1)
    val h = session.heightPx.coerceAtLeast(1)
    val screenX = geo.originX + point.x * geo.widthPx
    val screenY = geo.originY + point.y * geo.heightPx
    return MinimalLauncherSettleTarget(screenX, screenY, w, h)
}

/**
 * Launcher3 EdgeEffectCompat (g/j): radius = (edgeHeight * 0.5) / sin(30°) so the
 * clipped crescent spans the full page top→bottom at full pull. Do not cap depth
 * with a fixed dp — that left large portrait/landscape top/bottom gutters.
 */
@Composable
private fun WorkspaceEdgeGlow(
    leftPull: Float,
    rightPull: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        // Match EdgeEffectCompat.setSize(height, width) circle geometry.
        val sin30 = 0.5f
        val cos30 = 0.8660254f
        val edgeHeight = size.height.coerceAtLeast(1f)
        val radius = (edgeHeight * 0.5f) / sin30
        val maxDepth = radius * (1f - cos30)

        fun depthFor(pull: Float): Float {
            val progress = (pull / 0.5f).coerceIn(0f, 1f)
            val decelerated = 1f - (1f - progress) * (1f - progress)
            return maxDepth * decelerated
        }

        if (leftPull > 0.001f) {
            drawCircle(
                color = Color.White.copy(alpha = leftPull.coerceIn(0f, 0.5f)),
                radius = radius,
                center = Offset(
                    x = -radius + depthFor(leftPull),
                    y = edgeHeight * 0.5f,
                ),
            )
        }
        if (rightPull > 0.001f) {
            drawCircle(
                color = Color.White.copy(alpha = rightPull.coerceIn(0f, 0.5f)),
                radius = radius,
                center = Offset(
                    x = size.width + radius - depthFor(rightPull),
                    y = edgeHeight * 0.5f,
                ),
            )
        }
    }
}

/**
 * Launcher3 [PageIndicator] / [PageIndicatorMarker]:
 * - Active marker: full alpha + scale 1 (175ms)
 * - Inactive marker: dim + scale 0.5 (175ms)
 * - Trailing EXTRA_EMPTY page uses "+" (ic_pageindicator_add) instead of a dot
 */
@Composable
private fun WorkspacePageIndicator(
    pageCount: Int,
    currentPage: Int,
    extraEmptyIndex: Int,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val markerColor = if (isDarkMode) Color.White else Color(0xFF1E293B)
    Row(
        modifier = modifier.animateContentSize(animationSpec = tween(PAGE_MARKER_ANIM_MS)),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val isActive = index == currentPage
            val isAddPage = index == extraEmptyIndex
            WorkspacePageMarker(
                active = isActive,
                addPage = isAddPage,
                color = markerColor,
            )
        }
    }
}

@Composable
private fun WorkspacePageMarker(
    active: Boolean,
    addPage: Boolean,
    color: Color,
) {
    val animSpec = tween<Float>(PAGE_MARKER_ANIM_MS, easing = FastOutSlowInEasing)
    // Launcher3: active → alpha 1 / scale 1; inactive → active layer alpha 0 scale 0.5
    val activeAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = animSpec,
        label = "pageMarkerActiveAlpha",
    )
    val activeScale by animateFloatAsState(
        targetValue = if (active) 1f else 0.5f,
        animationSpec = animSpec,
        label = "pageMarkerActiveScale",
    )
    val inactiveAlpha by animateFloatAsState(
        targetValue = if (active) 0f else 1f,
        animationSpec = animSpec,
        label = "pageMarkerInactiveAlpha",
    )

    Box(
        modifier = Modifier.size(12.dp),
        contentAlignment = Alignment.Center
    ) {
        // Inactive layer (dot or "+")
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = inactiveAlpha },
            contentAlignment = Alignment.Center
        ) {
            if (addPage) {
                PageIndicatorAddGlyph(color = color.copy(alpha = 0.85f))
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(color.copy(alpha = 0.32f), CircleShape)
                )
            }
        }
        // Active layer (filled current)
        Box(
            modifier = Modifier
                .size(7.dp)
                .graphicsLayer {
                    alpha = activeAlpha
                    scaleX = activeScale
                    scaleY = activeScale
                }
                .background(color, CircleShape)
        )
    }
}

/** Small "+" glyph for the EXTRA_EMPTY page (Launcher3 ic_pageindicator_add). */
@Composable
private fun PageIndicatorAddGlyph(color: Color) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val stroke = 1.6.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val arm = size.minDimension * 0.32f
        drawLine(
            color = color,
            start = Offset(cx - arm, cy),
            end = Offset(cx + arm, cy),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(cx, cy - arm),
            end = Offset(cx, cy + arm),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspacePage(
    page: Int,
    isLandscape: Boolean,
    icons: List<PlacedMinimalLauncherIcon>,
    widgets: List<PlacedMinimalLauncherWidget>,
    isDarkMode: Boolean,
    showEmptyHint: Boolean,
    layoutLocked: Boolean,
    interactionEnabled: Boolean,
    showDesktopLabels: Boolean,
    resizingId: Int?,
    draggingWidgetId: Int?,
    draggingIconId: String?,
    onWorkspaceClick: () -> Unit,
    onWorkspaceLongClick: () -> Unit,
    onResizingIdChange: (Int?) -> Unit,
    onPageGeometry: (origin: Offset, widthPx: Float, heightPx: Float) -> Unit,
    onWidgetDragStart: (MinimalLauncherDragSession) -> Unit,
    onWidgetDragMove: (rawX: Float, rawY: Float, appWidgetId: Int) -> Unit,
    onWidgetDragEnd: (rawX: Float, rawY: Float, appWidgetId: Int) -> Unit,
    onWidgetDragCancel: () -> Unit,
    onIconDragStart: (MinimalLauncherDragSession) -> Unit,
    onIconDragMove: (rawX: Float, rawY: Float, iconId: String) -> Unit,
    onIconDragEnd: (rawX: Float, rawY: Float, iconId: String) -> Unit,
    onIconLaunch: (MinimalLauncherApp) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val density = LocalDensity.current
    // Subscribe so HostView/icon hide after DragView is ready (not on drag start).
    val ghostReady = MinimalLauncherDragLayerRegistry.ghostReady

    val idleDesktop = draggingWidgetId == null && draggingIconId == null && resizingId == null
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onGloballyPositioned { coords ->
                // Visual screen bounds AFTER parent graphicsLayer scale (spring-loaded
                // 0.87). Using raw coords.size left width/height unscaled while
                // positionOnScreen moved — right/bottom of the visual page were unreachable.
                val origin = coords.positionOnScreen()
                val bounds = coords.boundsInWindow()
                onPageGeometry(
                    origin,
                    bounds.width.coerceAtLeast(1f),
                    bounds.height.coerceAtLeast(1f),
                )
            }
            .then(
                if (interactionEnabled && idleDesktop) {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onWorkspaceClick,
                        onLongClick = onWorkspaceLongClick
                    )
                } else {
                    Modifier
                }
            )
    ) {
        val pageWidthPx = with(density) { maxWidth.toPx() }
        val pageHeightPx = with(density) { maxHeight.toPx() }
        // Collected, not just read: the size adjuster publishes an uncommitted preview
        // and the icons on screen are its preview surface, so a recomposition has to
        // follow every notch. A non-null preview also means the adjuster is open.
        val previewSizeStep by MinimalLauncherIconSizeStore.previewFlow.collectAsState()
        val committedSizeStep by MinimalLauncherIconSizeStore.stepFlow.collectAsState()
        val sizeStep = previewSizeStep ?: committedSizeStep
        val (iconBoxWFrac, iconBoxHFrac) = iconBoxNorm(
            context, pageWidthPx, pageHeightPx,
            showLabel = showDesktopLabels, sizeStep = sizeStep,
        )
        val labelledIconBoxHFrac = iconBoxNorm(
            context, pageWidthPx, pageHeightPx, showLabel = true, sizeStep = sizeStep,
        ).second
        val iconBoxW: Dp = maxWidth * iconBoxWFrac
        val iconBoxH: Dp = maxHeight * iconBoxHFrac
        // Keep the drawable at exactly its prior/default size when labels hide;
        // only the collision/layout seat becomes shorter.
        val iconVisualBoxH: Dp = maxHeight * labelledIconBoxHFrac

        if (showEmptyHint) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                MinimalLauncherEmptyHint(isDarkMode = isDarkMode)
            }
        }

        // A fresh install has an empty desktop, which is precisely when someone is most
        // likely to set icon size — and with no icons there is nothing for the preview to
        // resize. Outline the seats the chosen size produces instead.
        if (previewSizeStep != null && icons.isEmpty() && widgets.isEmpty()) {
            MinimalLauncherIconSizeGhostSeats(
                pageWidthPx = pageWidthPx,
                pageHeightPx = pageHeightPx,
                showDesktopLabels = showDesktopLabels,
                isDarkMode = isDarkMode,
                sizeStep = sizeStep,
            )
        }

        val iconPackPkg = MinimalLauncherIconPackManager.currentPackageFlow.collectAsState().value
        icons.forEach { placed ->
            val app = remember(
                placed.id,
                placed.packageName,
                placed.activityName,
                iconPackPkg,
            ) {
                MinimalLauncherTiles.resolve(appContext, placed.packageName, placed.activityName)
                    ?: resolveMinimalLauncherApp(
                        appContext,
                        placed.packageName,
                        placed.activityName,
                    )
            } ?: return@forEach
            // Include label visibility in identity. When disabled, dispose the old
            // AutoResizeText subtree completely instead of retaining a stale slot.
            key(placed.id, showDesktopLabels) {
                val isDragging = draggingIconId == placed.id
                Box(
                    modifier = Modifier
                        .offset(x = maxWidth * placed.x, y = maxHeight * placed.y)
                        .size(width = iconBoxW, height = iconBoxH)
                        .clipToBounds()
                ) {
                    DesktopIconCell(
                    app = app,
                    isDarkMode = isDarkMode,
                    boxW = iconBoxW,
                    visualBoxH = iconVisualBoxH,
                    canInteract = interactionEnabled && !layoutLocked,
                    layoutLocked = layoutLocked && interactionEnabled,
                    isDragging = isDragging,
                    showLabel = showDesktopLabels,
                    onLaunch = { onIconLaunch(app) },
                    onLockedLongPress = onWorkspaceLongClick,
                    onDragStart = { rawX, rawY, widthPx, heightPx, grabX, grabY, preview ->
                        onIconDragStart(
                            MinimalLauncherDragSession(
                                payload = MinimalLauncherDragPayload.DesktopIcon(placed),
                                fingerX = rawX,
                                fingerY = rawY,
                                grabOffsetX = grabX,
                                grabOffsetY = grabY,
                                widthPx = widthPx,
                                heightPx = heightPx,
                                phase = MinimalLauncherDragPhase.Dragging,
                                previewBitmap = preview,
                            )
                        )
                    },
                    onDragMove = { rawX, rawY -> onIconDragMove(rawX, rawY, placed.id) },
                    onDragEnd = { rawX, rawY -> onIconDragEnd(rawX, rawY, placed.id) },
                    )
                }
            }
        }

        widgets.forEach { placed ->
            // Never let Compose reuse one provider instance's AndroidView slot for another.
            // This is essential when two widgets of the same provider use different sizes.
            key(placed.appWidgetId) {
                val isDraggingThis = draggingWidgetId == placed.appWidgetId
                // AndroidView can briefly retain a stale/full-page measured size while its
                // widget moves between Pager pages. The persisted normalized rect is the
                // only authoritative size and must be used for provider option updates.
                val expectedHostWidthPx =
                    (placed.w * pageWidthPx).roundToInt().coerceAtLeast(1)
                val expectedHostHeightPx =
                    (placed.h * pageHeightPx).roundToInt().coerceAtLeast(1)
                // No outer padding — stored rect IS the visual HostView area so the
                // resize frame can hug the widget's real display surface.
                Box(
                    modifier = Modifier
                        .offset(x = maxWidth * placed.x, y = maxHeight * placed.y)
                        .size(width = maxWidth * placed.w, height = maxHeight * placed.h)
                        .clipToBounds()
                ) {
                    if (MinimalLauncherTiles.isCard(placed.provider)) {
                        DesktopCard(
                            widthPx = expectedHostWidthPx,
                            heightPx = expectedHostHeightPx,
                            isDarkMode = isDarkMode,
                            layoutLocked = layoutLocked || !interactionEnabled,
                            hidden = isDraggingThis && ghostReady,
                            onLockedLongPress = {
                                if (layoutLocked && interactionEnabled) onWorkspaceLongClick()
                            },
                            onDragStart = { rawX, rawY, view ->
                                onResizingIdChange(null)
                                val loc = IntArray(2)
                                view.getLocationOnScreen(loc)
                                val w = view.width.coerceAtLeast(1)
                                val h = view.height.coerceAtLeast(1)
                                onWidgetDragStart(
                                    MinimalLauncherDragSession(
                                        payload = MinimalLauncherDragPayload.DesktopWidget(placed),
                                        fingerX = rawX,
                                        fingerY = rawY,
                                        grabOffsetX = rawX - loc[0],
                                        grabOffsetY = rawY - loc[1],
                                        widthPx = w,
                                        heightPx = h,
                                        phase = MinimalLauncherDragPhase.Dragging,
                                        // The sentinel provider has no package to fall back
                                        // to, so name ours for the ghost's plate.
                                        previewBitmap = captureDragPreviewBitmap(view)
                                            ?: createWidgetDragFallbackBitmap(
                                                appContext, appContext.packageName, w, h,
                                            ),
                                    )
                                )
                            },
                        )
                    } else {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds(),
                            factory = { ctx ->
                                FrameLayout(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    clipChildren = true
                                    clipToPadding = true
                                    outlineProvider = android.view.ViewOutlineProvider.BOUNDS
                                    clipToOutline = true
                                    val info = AppWidgetManager.getInstance(ctx)
                                        .getAppWidgetInfo(placed.appWidgetId)
                                    if (info == null) {
                                        MinimalLauncherWidgetsStore.remove(
                                            appContext, placed.appWidgetId
                                        )
                                    } else {
                                        val hostView = MinimalLauncherWidgetHost.createView(
                                            ctx, placed.appWidgetId, info
                                        )
                                        // Zero HostView chrome padding so RemoteViews fill the
                                        // stored rect; size options still carry provider padding
                                        // semantics inside pushWidgetHostSize.
                                        hostView.setPadding(0, 0, 0, 0)
                                        addView(
                                            hostView,
                                            FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        )
                                        post {
                                            pushWidgetHostSize(
                                                hostView,
                                                context = ctx,
                                                hostWidthPx = expectedHostWidthPx,
                                                hostHeightPx = expectedHostHeightPx,
                                            )
                                        }
                                    }
                                }
                            },
                            update = { frame ->
                                var hostView = frame.getChildAt(0) as? AppWidgetHostView
                                val info = AppWidgetManager.getInstance(frame.context)
                                    .getAppWidgetInfo(placed.appWidgetId)
                                if (info == null) {
                                    MinimalLauncherWidgetsStore.remove(
                                        appContext, placed.appWidgetId
                                    )
                                    return@AndroidView
                                }
                                if (hostView !is MinimalLauncherAppWidgetHostView) {
                                    frame.removeAllViews()
                                    hostView = MinimalLauncherWidgetHost.createView(
                                        frame.context, placed.appWidgetId, info
                                    )
                                    hostView.setPadding(0, 0, 0, 0)
                                    frame.addView(
                                        hostView,
                                        FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                } else if (hostView.appWidgetId != placed.appWidgetId) {
                                    // Defensive only: keyed composition makes this impossible.
                                    hostView.setAppWidget(placed.appWidgetId, info)
                                }
                                hostView.setPadding(0, 0, 0, 0)
                                // L3: hide source only after DragView.show()+move() (ghostReady).
                                hostView.visibility =
                                    if (isDraggingThis && ghostReady) {
                                        View.INVISIBLE
                                    } else {
                                        View.VISIBLE
                                    }
                                fun applySizeNow() {
                                    pushWidgetHostSize(
                                        hostView,
                                        context = frame.context,
                                        hostWidthPx = expectedHostWidthPx,
                                        hostHeightPx = expectedHostHeightPx,
                                    )
                                }
                                applySizeNow()
                                hostView.asMinimalLauncherHostView()?.bindWorkspaceGestures(
                                    layoutLocked = layoutLocked || !interactionEnabled,
                                    onLongPress = {
                                        if (layoutLocked && interactionEnabled) {
                                            onWorkspaceLongClick()
                                        }
                                    },
                                    onDragStart = { rawX, rawY ->
                                        onResizingIdChange(null)
                                        val loc = IntArray(2)
                                        hostView.getLocationOnScreen(loc)
                                        val w = hostView.width.coerceAtLeast(1)
                                        val h = hostView.height.coerceAtLeast(1)
                                        // Snapshot while still VISIBLE (update sets INVISIBLE
                                        // after the session). HW RemoteViews often fail
                                        // view.draw — always keep a DragView bitmap.
                                        val preview = captureDragPreviewBitmap(hostView)
                                            ?: createWidgetDragFallbackBitmap(
                                                frame.context,
                                                placed.provider.packageName,
                                                w,
                                                h,
                                            )
                                        onWidgetDragStart(
                                            MinimalLauncherDragSession(
                                                payload = MinimalLauncherDragPayload
                                                    .DesktopWidget(placed),
                                                fingerX = rawX,
                                                fingerY = rawY,
                                                grabOffsetX = rawX - loc[0],
                                                grabOffsetY = rawY - loc[1],
                                                widthPx = w,
                                                heightPx = h,
                                                phase = MinimalLauncherDragPhase.Dragging,
                                                previewBitmap = preview,
                                            )
                                        )
                                    },
                                    onDragMove = { _, _ -> },
                                    onDragEnd = { _, _ -> },
                                    onDragCancel = { },
                                )
                            }
                        )
                    }
                }
            }
        }

        // Resize overlay rendered at the PAGE level so handles extend
        // beyond the widget Box and can actually receive touch events.
        val resizingWidget = if (resizingId != null && draggingWidgetId == null && !layoutLocked) {
            widgets.find { it.appWidgetId == resizingId }
        } else null
        if (resizingWidget != null) {
            val info = remember(resizingWidget.appWidgetId) {
                AppWidgetManager.getInstance(appContext)
                    .getAppWidgetInfo(resizingWidget.appWidgetId)
            }
            val (minWNorm, minHNorm) = remember(info, pageWidthPx, pageHeightPx) {
                widgetMinSizeNorm(appContext, info, pageWidthPx, pageHeightPx)
            }
            val (iconW, iconH) = iconBoxNorm(
                context, pageWidthPx, pageHeightPx, showLabel = showDesktopLabels,
            )
            val resizeGuides = remember(
                icons, widgets, resizingWidget.appWidgetId, page, iconW, iconH,
            ) {
                buildAlignGuides(
                    page = page,
                    icons = icons,
                    widgets = widgets,
                    iconW = iconW,
                    iconH = iconH,
                    exceptWidgetId = resizingWidget.appWidgetId,
                )
            }
            WidgetResizeFrame(
                placed = resizingWidget,
                providerInfo = info,
                pageWidthPx = pageWidthPx,
                pageHeightPx = pageHeightPx,
                minWNorm = minWNorm,
                minHNorm = minHNorm,
                guides = resizeGuides,
                isDarkMode = isDarkMode,
                onBoundsChange = { x, y, w, h, persistDisk ->
                    MinimalLauncherWidgetsStore.updateBounds(
                        appContext, isLandscape, resizingWidget.appWidgetId, x, y, w, h,
                        persistDisk = persistDisk,
                    )
                },
                onDismiss = { onResizingIdChange(null) },
                onBeginMove = { rawX, rawY, widthPx, heightPx, grabX, grabY ->
                    onResizingIdChange(null)
                    onWidgetDragStart(
                        MinimalLauncherDragSession(
                            payload = MinimalLauncherDragPayload.DesktopWidget(resizingWidget),
                            fingerX = rawX,
                            fingerY = rawY,
                            grabOffsetX = grabX,
                            grabOffsetY = grabY,
                            widthPx = widthPx,
                            heightPx = heightPx,
                            phase = MinimalLauncherDragPhase.Dragging,
                            previewBitmap = createWidgetDragFallbackBitmap(
                                appContext,
                                resizingWidget.provider.packageName,
                                widthPx,
                                heightPx,
                            ),
                        )
                    )
                },
            )
        }
    }
}

/**
 * A card the launcher draws itself instead of hosting as a widget — see
 * [MinimalLauncherTiles.SERVICE_CARD] for why the first-run one cannot be bound.
 *
 * The widget's own RemoteViews are inflated into the very host view class the bound
 * widgets use, so long-press-to-drag, the resize frame and the drag snapshot all behave
 * exactly as they do for a real widget. Only the tap is ours: a launcher-drawn card has no
 * widget id, so there is no PendingIntent to fire.
 */
@Composable
private fun DesktopCard(
    widthPx: Int,
    heightPx: Int,
    isDarkMode: Boolean,
    layoutLocked: Boolean,
    hidden: Boolean,
    onLockedLongPress: () -> Unit,
    onDragStart: (rawX: Float, rawY: Float, view: View) -> Unit,
) {
    val density = LocalDensity.current.density.coerceAtLeast(0.01f)
    val widthDp = (widthPx / density).roundToInt()
    val heightDp = (heightPx / density).roundToInt()
    val action = AvaWidgetAction.SERVICE
    // Launcher-drawn card, not a bound AppWidget: AppWidgetManager refreshes never reach
    // it. serviceTileStateKey carries the full coarse state (running / disconnected /
    // error / stopped) so intermediate transitions repaint too, not just start/stop.
    val serviceTileState by VoiceSatelliteService.serviceTileStateKey.collectAsStateWithLifecycle()
    val onDragStartState = rememberUpdatedState(onDragStart)
    val onLockedLongPressState = rememberUpdatedState(onLockedLongPress)
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        factory = { ctx ->
            MinimalLauncherAppWidgetHostView(ctx).apply { setPadding(0, 0, 0, 0) }
        },
        update = { host ->
            val signature = listOf(widthDp, heightDp, serviceTileState, isDarkMode, action)
            if (host.tag != signature || host.childCount == 0) {
                host.tag = signature
                paintDesktopCard(host, action, widthDp, heightDp)
            }
            host.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            host.bindWorkspaceGestures(
                layoutLocked = layoutLocked,
                onLongPress = { if (layoutLocked) onLockedLongPressState.value() },
                onDragStart = { rawX, rawY -> onDragStartState.value(rawX, rawY, host) },
                onDragMove = { _, _ -> },
                onDragEnd = { _, _ -> },
                onDragCancel = { },
            )
        },
    )
}

private fun paintDesktopCard(host: ViewGroup, action: AvaWidgetAction, widthDp: Int, heightDp: Int) {
    val views = AvaActionWidgets.cardViews(host.context, action, widthDp, heightDp)
    val card = runCatching { views.apply(host.context, host) }.getOrNull() ?: return
    card.isClickable = true
    card.isFocusable = true
    card.setOnClickListener {
        AvaActionWidgets.onCardTap(host.context, action)
    }
    host.removeAllViews()
    host.addView(
        card,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ),
    )
}

@Composable
private fun DesktopIconCell(
    app: MinimalLauncherApp,
    isDarkMode: Boolean,
    boxW: Dp,
    visualBoxH: Dp,
    canInteract: Boolean,
    layoutLocked: Boolean,
    isDragging: Boolean,
    showLabel: Boolean,
    onLaunch: () -> Unit,
    onLockedLongPress: () -> Unit,
    onDragStart: (
        rawX: Float,
        rawY: Float,
        widthPx: Int,
        heightPx: Int,
        grabX: Float,
        grabY: Float,
        preview: android.graphics.Bitmap?,
    ) -> Unit,
    onDragMove: (rawX: Float, rawY: Float) -> Unit,
    onDragEnd: (rawX: Float, rawY: Float) -> Unit,
) {
    val labelColor = if (isDarkMode) Color(0xFFE5E7EB) else SlateText
    val density = LocalDensity.current
    val view = LocalView.current
    // Fill the seat tightly so visual gutters match the no-overlap box.
    // visualBoxH is always the labelled seat height so drawable size stays stable
    // when labels hide — keep subtracting the label band either way.
    val labelBand = if (showLabel) (boxW * 0.20f).coerceIn(14.dp, 24.dp) else 0.dp
    val cellPad = 1.dp
    val iconBudget = (visualBoxH - cellPad * 2 - labelBand).coerceAtLeast(24.dp)
    // Never exceed the seat — a hard min larger than boxW was overflowing small packs.
    val iconSize = minOf(boxW * 0.88f, iconBudget).coerceAtLeast(24.dp)
    val labelSp = (iconSize.value * 0.16f).coerceIn(9f, 15f).sp
    var lastRawX by remember { mutableFloatStateOf(0f) }
    var lastRawY by remember { mutableFloatStateOf(0f) }
    var layoutCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val iconSizePx = with(density) { iconSize.roundToPx().coerceAtLeast(1) }
    val bitmap = rememberShapedIconBitmap(
        drawable = app.icon,
        packageName = app.packageName,
        activityName = app.activityName,
        sizePx = iconSizePx,
    )
    val lightEdgeShadow = remember(bitmap, isDarkMode, density.density) {
        if (isDarkMode) null
        else createSubtleIconEdgeShadow(
            source = bitmap,
            blurRadiusPx = 1.25f * density.density,
        )
    }
    val renderedBitmap = lightEdgeShadow?.bitmap ?: bitmap
    val renderedIconSize = iconSize + with(density) {
        ((lightEdgeShadow?.paddingPx ?: 0) * 2).toDp()
    }
    val canInteractState = rememberUpdatedState(canInteract)
    val layoutLockedState = rememberUpdatedState(layoutLocked)
    val onLockedLongPressState = rememberUpdatedState(onLockedLongPress)
    val isDraggingState = rememberUpdatedState(isDragging)
    val onDragStartState = rememberUpdatedState(onDragStart)
    val onDragMoveState = rememberUpdatedState(onDragMove)
    val onDragEndState = rememberUpdatedState(onDragEnd)
    val hideSeat = isDragging && MinimalLauncherDragLayerRegistry.ghostReady

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { layoutCoords = it }
            // Top Remove/Info is SearchDropTargetBar in MinimalLauncherDragOverlay.
            .pointerInput(app.packageName, app.activityName) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        if (!canInteractState.value) {
                            if (layoutLockedState.value) {
                                onLockedLongPressState.value()
                            }
                            return@detectDragGesturesAfterLongPress
                        }
                        val origin = layoutCoords?.takeIf { it.isAttached }?.positionOnScreen()
                            ?: Offset.Zero
                        val rawX = origin.x + offset.x
                        val rawY = origin.y + offset.y
                        lastRawX = rawX
                        lastRawY = rawY
                        val padPx = with(density) { cellPad.toPx() }
                        val iconLeft = (size.width - iconSizePx) * 0.5f
                        val iconTop = padPx
                        // Clamp grab into the icon bitmap now so ghost + drop agree.
                        val (grabX, grabY) = effectiveGrabPx(
                            offset.x - iconLeft,
                            offset.y - iconTop,
                            iconSizePx,
                            iconSizePx,
                        )
                        onDragStartState.value(
                            rawX,
                            rawY,
                            iconSizePx,
                            iconSizePx,
                            grabX,
                            grabY,
                            bitmap,
                        )
                        // Hand off to DragLayer immediately — only rawX/rawY updates after this.
                        val layer = view.findMinimalLauncherDragLayer()
                        layer?.activateDrag()
                        layer?.post { layer.activateDrag() }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        // DragLayer already owns MOVE with MotionEvent.rawX/rawY.
                        // Do not also push layout-space coords (fought the ghost under spring scale).
                        if (view.findMinimalLauncherDragLayer()?.isDragActive == true) return@detectDragGesturesAfterLongPress
                        val origin = layoutCoords?.takeIf { it.isAttached }?.positionOnScreen()
                            ?: return@detectDragGesturesAfterLongPress
                        lastRawX = origin.x + change.position.x
                        lastRawY = origin.y + change.position.y
                        onDragMoveState.value(lastRawX, lastRawY)
                    },
                    onDragEnd = {
                        // Layer UP may also finish — finishDesktopDrag is idempotent.
                        if (view.findMinimalLauncherDragLayer()?.isDragActive != true) {
                            onDragEndState.value(lastRawX, lastRawY)
                        }
                    },
                    onDragCancel = {
                        // Expected after handoff / pager noise — Layer continues MOVE/UP.
                        // Re-assert ownership if startDrag already ran.
                        view.findMinimalLauncherDragLayer()?.activateDrag()
                    },
                )
            }
            // Do not flip enabled with isDragging — that CANCEL'd the first MOVE.
            .clickable(
                enabled = canInteract,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (!isDraggingState.value) onLaunch()
                },
            )
            .padding(cellPad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer { alpha = if (hideSeat) 0f else 1f },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = remember(renderedBitmap) { renderedBitmap.asImageBitmap() },
                contentDescription = app.label.toString(),
                modifier = Modifier.size(renderedIconSize),
            )
        }
        if (showLabel) {
            AutoResizeText(
                text = app.label.toString(),
                fontSize = labelSp,
                minFontSize = (labelSp.value * 0.72f).coerceAtLeast(8f).sp,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .fillMaxWidth()
                    .heightIn(min = 12.dp, max = labelBand)
                    .graphicsLayer { alpha = if (hideSeat) 0f else 1f },
                style = TextStyle(textAlign = TextAlign.Center)
            )
        }
    }
}

private data class IconEdgeShadow(
    val bitmap: android.graphics.Bitmap,
    val paddingPx: Int,
)

/**
 * Adds only a faint alpha-edge shadow. The source pixels are copied unchanged,
 * so light mode does not recolor or otherwise restyle app/icon-pack artwork.
 */
private fun createSubtleIconEdgeShadow(
    source: android.graphics.Bitmap,
    blurRadiusPx: Float,
): IconEdgeShadow {
    val radius = blurRadiusPx.coerceAtLeast(0.5f)
    val padding = kotlin.math.ceil(radius * 3f).toInt().coerceAtLeast(2)
    val output = android.graphics.Bitmap.createBitmap(
        source.width + padding * 2,
        source.height + padding * 2,
        android.graphics.Bitmap.Config.ARGB_8888,
    )
    val canvas = android.graphics.Canvas(output)
    val maskPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = android.graphics.BlurMaskFilter(
            radius,
            android.graphics.BlurMaskFilter.Blur.NORMAL,
        )
    }
    val maskOffset = IntArray(2)
    val blurredAlpha = source.extractAlpha(maskPaint, maskOffset)
    val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(42, 0, 0, 0)
    }
    // extractAlpha returns the true blurred silhouette and its alignment offset.
    // Drawing that mask avoids rectangular or uneven halos on round/cornered icons.
    canvas.drawBitmap(
        blurredAlpha,
        padding + maskOffset[0].toFloat(),
        padding + maskOffset[1].toFloat(),
        shadowPaint,
    )
    canvas.drawBitmap(source, padding.toFloat(), padding.toFloat(), null)
    blurredAlpha.recycle()
    return IconEdgeShadow(output, padding)
}



private enum class ResizeEdge { Left, Right, Top, Bottom }

/**
 * Free-canvas AppWidgetResizeFrame at PAGE level.
 *
 * Forced free resize: provider minWidth/minResize are IGNORED so the user can
 * shrink/grow arbitrarily (floor ≈ 40dp). No ruler snap while resizing — snap
 * was expanding widgets back toward siblings/page edges.
 *
 * Stable full-page host keeps handle [pointerInput] nodes alive while the rect
 * changes; bounds commit on finger-up.
 */
@Composable
private fun WidgetResizeFrame(
    placed: PlacedMinimalLauncherWidget,
    @Suppress("UNUSED_PARAMETER") providerInfo: AppWidgetProviderInfo?,
    pageWidthPx: Float,
    pageHeightPx: Float,
    @Suppress("UNUSED_PARAMETER") minWNorm: Float,
    @Suppress("UNUSED_PARAMETER") minHNorm: Float,
    @Suppress("UNUSED_PARAMETER") guides: CanvasGuideLines,
    isDarkMode: Boolean,
    onBoundsChange: (x: Float, y: Float, w: Float, h: Float, persistDisk: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onBeginMove: (
        rawX: Float, rawY: Float, widthPx: Int, heightPx: Int, grabX: Float, grabY: Float
    ) -> Unit,
) {
    val density = LocalDensity.current
    val pageW = pageWidthPx.coerceAtLeast(1f)
    val pageH = pageHeightPx.coerceAtLeast(1f)
    // Force floor in px → norm. Never use provider minWidth (blocks shrink).
    val minW = (MINIMAL_LAUNCHER_WIDGET_FORCE_MIN_DP * density.density / pageW)
        .coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 0.25f)
    val minH = (MINIMAL_LAUNCHER_WIDGET_FORCE_MIN_DP * density.density / pageH)
        .coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 0.25f)
    val touch = 48.dp
    val touchPx = with(density) { touch.toPx() }
    val touchHalfPx = touchPx * 0.5f

    val frameColor = if (isDarkMode) {
        Color.White.copy(alpha = 0.88f)
    } else {
        Color(0xFF8A93A3)
    }

    var xNorm by remember(placed.appWidgetId) { mutableFloatStateOf(placed.x) }
    var yNorm by remember(placed.appWidgetId) { mutableFloatStateOf(placed.y) }
    var wNorm by remember(placed.appWidgetId) { mutableFloatStateOf(placed.w) }
    var hNorm by remember(placed.appWidgetId) { mutableFloatStateOf(placed.h) }
    var draggingEdge by remember { mutableStateOf<ResizeEdge?>(null) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    val resizeView = LocalView.current

    val latestOnBounds = rememberUpdatedState(onBoundsChange)
    val latestMinW = rememberUpdatedState(minW)
    val latestMinH = rememberUpdatedState(minH)
    val latestPageW = rememberUpdatedState(pageW)
    val latestPageH = rememberUpdatedState(pageH)
    val latestBeginMove = rememberUpdatedState(onBeginMove)

    LaunchedEffect(placed.x, placed.y, placed.w, placed.h) {
        if (draggingEdge == null) {
            xNorm = placed.x
            yNorm = placed.y
            wNorm = placed.w
            hNorm = placed.h
        }
    }

    fun clampRect(x: Float, y: Float, w: Float, h: Float): CanvasRect {
        val mw = latestMinW.value
        val mh = latestMinH.value
        val nw = w.coerceIn(mw, 1f)
        val nh = h.coerceIn(mh, 1f)
        // When shrinking from left/top, prefer keeping the opposite edge:
        // recompute x/y from the fixed right/bottom if clamp raised w/h.
        val nx = x.coerceIn(0f, (1f - nw).coerceAtLeast(0f))
        val ny = y.coerceIn(0f, (1f - nh).coerceAtLeast(0f))
        return CanvasRect(nx, ny, nw, nh)
    }

    fun applyDelta(edge: ResizeEdge, dragAmount: Offset) {
        val pw = latestPageW.value
        val ph = latestPageH.value
        val mw = latestMinW.value
        val mh = latestMinH.value
        val dx = dragAmount.x / pw
        val dy = dragAmount.y / ph
        val left = xNorm
        val top = yNorm
        val right = xNorm + wNorm
        val bottom = yNorm + hNorm
        // Opposite edge stays fixed — user can freely grow/shrink past provider mins.
        val rect = when (edge) {
            ResizeEdge.Right -> {
                val nr = (right + dx).coerceIn(left + mw, 1f)
                clampRect(left, top, nr - left, bottom - top)
            }
            ResizeEdge.Left -> {
                val nl = (left + dx).coerceIn(0f, right - mw)
                clampRect(nl, top, right - nl, bottom - top)
            }
            ResizeEdge.Bottom -> {
                val nb = (bottom + dy).coerceIn(top + mh, 1f)
                clampRect(left, top, right - left, nb - top)
            }
            ResizeEdge.Top -> {
                val nt = (top + dy).coerceIn(0f, bottom - mh)
                clampRect(left, nt, right - left, bottom - nt)
            }
        }
        xNorm = rect.x
        yNorm = rect.y
        wNorm = rect.w
        hNorm = rect.h
    }

    fun persistCommit() {
        draggingEdge = null
        latestOnBounds.value(xNorm, yNorm, wNorm, hNorm, true)
    }

    val leftPx = xNorm * pageW
    val topPx = yNorm * pageH
    val widthPx = wNorm * pageW
    val heightPx = hNorm * pageH

    // Stable full-page host. Dismiss layer is BEHIND handles so it cannot steal drags.
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

        // Visual border over the widget rect.
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(leftPx.roundToInt(), topPx.roundToInt())
                }
                .size(
                    width = with(density) { widthPx.toDp() },
                    height = with(density) { heightPx.toDp() },
                )
                .onGloballyPositioned { coords ->
                    screenOrigin = coords.positionOnScreen()
                }
                .border(width = 1.5.dp, color = frameColor, shape = RoundedCornerShape(2.dp))
                .pointerInput(placed.appWidgetId) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val w = size.width.coerceAtLeast(1)
                            val h = size.height.coerceAtLeast(1)
                            val (gx, gy) = effectiveGrabPx(offset.x, offset.y, w, h)
                            latestBeginMove.value(
                                screenOrigin.x + offset.x,
                                screenOrigin.y + offset.y,
                                w,
                                h,
                                gx,
                                gy,
                            )
                            resizeView.findMinimalLauncherDragLayer()?.activateDrag()
                        },
                        onDrag = { change, _ -> change.consume() },
                        onDragEnd = { },
                        onDragCancel = { },
                    )
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

        @Composable
        fun EdgeHandle(edge: ResizeEdge, cx: Float, cy: Float) {
            val barLong = 22.dp
            val barThick = 3.dp
            val horizontal = edge == ResizeEdge.Left || edge == ResizeEdge.Right
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (cx - touchHalfPx).roundToInt(),
                            (cy - touchHalfPx).roundToInt(),
                        )
                    }
                    .size(touch)
                    .pointerInput(edge) {
                        detectDragGestures(
                            onDragStart = { draggingEdge = edge },
                            onDragEnd = { persistCommit() },
                            onDragCancel = { persistCommit() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                applyDelta(edge, dragAmount)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .then(
                            if (horizontal) Modifier.size(width = barThick, height = barLong)
                            else Modifier.size(width = barLong, height = barThick)
                        )
                        .background(frameColor, RoundedCornerShape(1.5.dp))
                )
            }
        }

        EdgeHandle(ResizeEdge.Left, leftPx, topPx + heightPx * 0.5f)
        EdgeHandle(ResizeEdge.Right, leftPx + widthPx, topPx + heightPx * 0.5f)
        EdgeHandle(ResizeEdge.Top, leftPx + widthPx * 0.5f, topPx)
        EdgeHandle(ResizeEdge.Bottom, leftPx + widthPx * 0.5f, topPx + heightPx)
    }
}

/**
 * Launcher3 [AppWidgetResizeFrame.getWidgetSizeRanges] + force HostView measure.
 * Receives pixels derived from the persisted free-canvas rect, never a transient
 * AndroidView measurement, so RemoteViews reflow to the exact desktop box.
 */
private fun pushWidgetHostSize(
    hostView: AppWidgetHostView,
    context: Context,
    hostWidthPx: Int,
    hostHeightPx: Int,
) {
    if (hostWidthPx <= 0 || hostHeightPx <= 0) return
    forceHostViewExactSize(hostView, hostWidthPx, hostHeightPx)

    // Resizing one item recomposes the whole page. Do not re-broadcast unchanged
    // dimensions to every provider instance: each AppWidget id owns its own options.
    val reportedSize = hostWidthPx to hostHeightPx
    if (hostView.getTag(R.id.tag_minimal_launcher_widget_reported_size) == reportedSize) {
        return
    }

    val density = context.resources.displayMetrics.density.coerceAtLeast(0.01f)
    // Desktop HostView padding is zeroed so RemoteViews fill the stored rect.
    // Report that exact pixel box (min == max) so the widget reflows to it.
    val widthDp = hostWidthPx / density
    val heightDp = hostHeightPx / density
    val minW = widthDp.coerceAtLeast(0f)
    val minH = heightDp.coerceAtLeast(0f)
    val maxW = minW
    val maxH = minH

    val opts = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minW.roundToInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minH.roundToInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxW.roundToInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxH.roundToInt())
    }
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hostView.updateAppWidgetSize(opts, listOf(SizeF(minW, minH)))
        } else {
            @Suppress("DEPRECATION")
            hostView.updateAppWidgetSize(
                opts,
                minW.roundToInt(),
                minH.roundToInt(),
                maxW.roundToInt(),
                maxH.roundToInt(),
            )
        }
        // updateAppWidgetSize already stores options and dispatches the change to
        // this hostView's appWidgetId. Calling updateAppWidgetOptions again would
        // duplicate provider callbacks during live resize.
        hostView.setTag(R.id.tag_minimal_launcher_widget_reported_size, reportedSize)
        hostView.requestLayout()
        hostView.invalidate()
    } catch (_: Exception) {
    }
}

/** Force EXACTLY measure/layout so stubborn RemoteViews fill the cell. */
private fun forceHostViewExactSize(hostView: AppWidgetHostView, widthPx: Int, heightPx: Int) {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    // Keep MATCH_PARENT so Compose parent size changes still flow in.
    val lp = hostView.layoutParams
        ?: FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).also { hostView.layoutParams = it }
    if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
        lp.height != ViewGroup.LayoutParams.MATCH_PARENT
    ) {
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        hostView.layoutParams = lp
    }
    hostView.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
    )
    hostView.layout(0, 0, w, h)
}

