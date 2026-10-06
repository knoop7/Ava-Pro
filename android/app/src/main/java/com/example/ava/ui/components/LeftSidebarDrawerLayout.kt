package com.example.ava.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ava.settings.SidebarPosition
import com.example.ava.ui.AvaSystemChrome
import com.example.ava.ui.glass.LiquidGlass
import com.example.ava.ui.glass.liquidGlass
import com.example.ava.ui.glass.rememberLiquidGlassState
import com.example.ava.utils.AmbientBitmapBlur
import com.example.ava.utils.OverviewBackdropCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Remote-control hook for the edge drawer: kiosk remotes have no edge to swipe,
 * so [com.example.ava.MainActivity] forwards KEYCODE_MENU here and whichever
 * [LeftSidebarDrawerLayout] is currently mounted (home or a settings page)
 * toggles itself. Tick-based so a layout mounted after a press ignores it.
 */
/**
 * Vertical two-finger scroll into a Compose list under the cursor
 * (home / settings sidebar). Opening the drawer stays on the horizontal
 * [SidebarDrawerRemote] edge-swipe path.
 */
object PadContentScrollRemote {
    interface Sink {
        fun containsScreenPoint(x: Float, y: Float): Boolean
        fun onPadScroll(dx: Float, dy: Float): Boolean
    }

    private val sinks = linkedSetOf<Sink>()

    fun register(sink: Sink) {
        sinks.add(sink)
    }

    fun unregister(sink: Sink) {
        sinks.remove(sink)
    }

    fun nudge(x: Float, y: Float, dx: Float, dy: Float): Boolean {
        for (sink in sinks.toList().asReversed()) {
            if (sink.containsScreenPoint(x, y) && sink.onPadScroll(dx, dy)) {
                return true
            }
        }
        return false
    }
}

object SidebarDrawerRemote {
    private val _toggleTick = MutableStateFlow(0L)
    val toggleTick: StateFlow<Long> = _toggleTick.asStateFlow()

    fun requestToggle() {
        _toggleTick.value += 1L
    }

    /**
     * Same edge-swipe pipeline as the SystemUI handle. Home, settings, and the
     * browser overlay register here so a two-finger pad swipe can open either
     * side's drawer through the live sink — not a separate toggle.
     */
    interface EdgeSwipeSink {
        fun onPadEdgeSwipeBegin(): Boolean
        fun onPadEdgeSwipeNudge(dx: Float): Boolean
        fun onPadEdgeSwipeEnd(totalDx: Float, velocityPxPerSec: Float): Boolean
        fun onPadEdgeSwipeCancel(): Boolean
        fun onPadForceClose(): Boolean = false
    }

    private val sinks = linkedSetOf<EdgeSwipeSink>()
    private val active = mutableListOf<EdgeSwipeSink>()
    private var swipeTotalDx = 0f
    private var swipeVelocity = 0f
    private var swipeAt = 0L

    fun register(sink: EdgeSwipeSink) {
        sinks.add(sink)
    }

    fun unregister(sink: EdgeSwipeSink) {
        sinks.remove(sink)
        active.remove(sink)
    }

    fun beginEdgeSwipe(): Boolean {
        active.clear()
        swipeTotalDx = 0f
        swipeVelocity = 0f
        swipeAt = android.os.SystemClock.uptimeMillis()
        // List.asReversed() — Set.reversed() is SequencedSet (API 35) and
        // crashes older ART with NoSuchMethodError.
        for (sink in sinks.toList().asReversed()) {
            if (sink.onPadEdgeSwipeBegin()) {
                active.add(sink)
                break
            }
        }
        return active.isNotEmpty()
    }

    fun nudgeEdgeSwipe(dx: Float): Boolean {
        if (active.isEmpty()) return false
        val now = android.os.SystemClock.uptimeMillis()
        val dt = (now - swipeAt).coerceAtLeast(1L) / 1000f
        swipeVelocity = dx / dt
        swipeAt = now
        swipeTotalDx += dx
        var ok = false
        active.forEach { ok = it.onPadEdgeSwipeNudge(dx) || ok }
        return ok
    }

    fun endEdgeSwipe(commit: Boolean, velocityPxPerSec: Float = swipeVelocity): Boolean {
        val sinks = active.toList()
        active.clear()
        if (sinks.isEmpty()) return false
        var ok = false
        for (sink in sinks) {
            ok = if (commit) {
                sink.onPadEdgeSwipeEnd(swipeTotalDx, velocityPxPerSec)
            } else {
                sink.onPadEdgeSwipeCancel()
            } || ok
        }
        return ok
    }

    /** Rewind drawers to closed so the next recorded edge-swipe can open them again. */
    fun requestClose(): Boolean {
        endEdgeSwipe(commit = false)
        var ok = false
        for (sink in sinks.toList()) {
            ok = sink.onPadForceClose() || ok
        }
        return ok
    }
}

/**
 * Attach point for the drawer's function list: content marks its list container
 * with this requester ([androidx.compose.ui.focus.focusRequester] + focusGroup)
 * and the opening drawer sends D-pad focus straight into the first list row —
 * skipping chrome above it (title chip, search field) that has no focus ring.
 */
val LocalSidebarDrawerListFocus = staticCompositionLocalOf<FocusRequester?> { null }

/**
 * Closed drawer stays composed so MENU can requestFocus into a live list.
 * Descendants that honor this stay unfocusable while shut, so D-pad cannot
 * land on off-screen rows. Default true for every surface outside the drawer.
 */
val LocalSidebarDrawerFocusEnabled = staticCompositionLocalOf { true }

/**
 * Settings pages default to painting the remote accent ring. The drawer starts
 * this at false so an opening panel does not flash a leftover selection; a
 * later remote key on the open drawer turns it on, a finger down turns it off.
 */
val LocalSidebarFocusRingVisible = staticCompositionLocalOf { true }

fun Modifier.sidebarDrawerFocusable(): Modifier = composed {
    val enabled = LocalSidebarDrawerFocusEnabled.current
    focusProperties { canFocus = enabled }
}

/** Flings faster than this (in drawer-fractions-per-second) commit to open/close regardless of
 *  how far the drag traveled — matches the "flick to open" feel of mainstream edge drawers. */
private const val FLING_VELOCITY_THRESHOLD = 1.2f

/** A fully-open drawer must leave at least this much tappable scrim beside it, so
 *  tap-outside-to-close always has a target (Material modal drawers reserve the same strip). */
private val DISMISS_SCRIM_MIN_WIDTH = 56.dp

/** Floor for the clamped panel so drawer content stays usable on degenerate configurations. */
private val CLAMPED_DRAWER_MIN_WIDTH = 160.dp

/** Liquid Glass: floating menu inset. Portrait 30dp, landscape 20dp. */
private val GLASS_PANEL_VERTICAL_GAP_PORTRAIT = 30.dp
private val GLASS_PANEL_VERTICAL_GAP_LANDSCAPE = 20.dp

/** Liquid Glass: fixed 10dp gap from the screen edge — the menu never sits flush. */
private val GLASS_PANEL_EDGE_INSET = 10.dp

private val GLASS_PANEL_CORNER = 20.dp

/**
 * Extra inward width on top of the SystemUI-matched handle strip (26–30dp).
 * OEM edge-gesture windows sit on both sides; left and right use the same grab.
 */
private val HANDLE_HIT_WIDTH_EXPANSION = SystemStyleEdgeHandleSpec.HIT_WIDTH_EXPANSION_DP.dp

/**
 * Vertical reach around the visible slider (106dp resting). Full-height hit was
 * opening the drawer from corner taps and edge widgets; keep the extra width on
 * this band only.
 */
private val HANDLE_HIT_VERTICAL_PADDING = SystemStyleEdgeHandleSpec.HIT_VERTICAL_PAD_DP.dp

/**
 * Home-screen edge drawer only. Browser display uses [com.example.ava.services.BrowserSidebarOverlayView]
 * layered inside [com.example.ava.services.WebViewService] (same pattern as pull-to-refresh).
 *
 * The SystemUI-style handle is a **fixed** edge overlay: it morphs in place while the drawer
 * panel slides underneath. Open gestures are handled on that same handle node (like the View port).
 */
@Composable
fun LeftSidebarDrawerLayout(
    isDarkMode: Boolean,
    panelColor: Color,
    modifier: Modifier = Modifier,
    position: SidebarPosition = SidebarPosition.LEFT,
    drawerWidth: Dp = 288.dp,
    handleHitWidth: Dp? = null,
    handleTuckInset: Dp = 0.dp,
    /**
     * When false the drawer chrome is hidden and gestures are off. The [content]
     * slot stays in place so a parent NavHost is not remounted.
     */
    enabled: Boolean = true,
    drawerContent: @Composable ColumnScope.(closeDrawer: () -> Unit) -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val handleMetrics = rememberSystemStyleEdgeHandleMetrics()
    val resolvedHandleHitWidth =
        handleHitWidth ?: (handleMetrics.hitWidth + HANDLE_HIT_WIDTH_EXPANSION)
    val resolvedHandleHitHeight =
        handleMetrics.pivotHeight + HANDLE_HIT_VERTICAL_PADDING * 2
    // Compare, don't `when (position)` — a null enum from restored settings NPEs on ordinal().
    val isRightDrawer = position == SidebarPosition.RIGHT
    var isOpen by rememberSaveable(isRightDrawer) { mutableStateOf(false) }
    var isEdgeDragging by remember(isRightDrawer) { mutableStateOf(false) }
    var edgeDragDistancePx by remember(isRightDrawer) { mutableFloatStateOf(0f) }
    var handleRevealTick by remember { mutableLongStateOf(0L) }
    val revealHandle = { handleRevealTick += 1L }
    val revealHandleState = rememberUpdatedState(revealHandle)
    val coroutineScope = rememberCoroutineScope()
    val viewConfiguration = LocalViewConfiguration.current
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var snapJob by remember { mutableStateOf<Job?>(null) }

    val openFraction = remember(isRightDrawer) { Animatable(if (isOpen) 1f else 0f) }
    val glass by rememberLiquidGlassState()

    val density = LocalDensity.current
    // Extreme display scales (e.g. misreported-density panels at 200% interface scale)
    // can shrink the effective screen below the requested drawer width. An unclamped
    // panel then covers the dismiss scrim completely — with the edge handle unmounted
    // at full open, nothing on screen can close the drawer. Clamp so a tappable strip
    // always survives; screens wider than drawer + strip are byte-for-byte unaffected.
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val effectiveDrawerWidth = drawerWidth.coerceAtMost(
        (screenWidthDp - DISMISS_SCRIM_MIN_WIDTH).coerceAtLeast(CLAMPED_DRAWER_MIN_WIDTH)
    )
    val glassVerticalGap =
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            GLASS_PANEL_VERTICAL_GAP_LANDSCAPE
        } else {
            GLASS_PANEL_VERTICAL_GAP_PORTRAIT
        }
    val edgeInset = if (glass.enabled) GLASS_PANEL_EDGE_INSET else 0.dp
    val drawerWidthPx = with(density) { (effectiveDrawerWidth + edgeInset).roundToPx() }
    val tuckPx = with(density) { handleTuckInset.roundToPx() }
    val closedOffsetPx = (drawerWidthPx + tuckPx).toFloat()
    val directionSign = if (isRightDrawer) 1f else -1f
    val offsetXPx = (directionSign * closedOffsetPx * (1f - openFraction.value)).roundToInt()
    val view = LocalView.current
    val sidebarShowing = isOpen || isEdgeDragging || openFraction.value > 0.02f
    // API 31+: live RenderEffect on the page. Below: freeze a raw snapshot and
    // re-stack-blur it whenever the intensity slider (viewBlurRadiusPx) moves.
    // The glass slab stays thick on the fake path so old devices do not go see-through.
    val nativePageBlur = glass.enabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        glass.viewBlurRadiusPx > 0.5f
    val fakeBlurPipeline = glass.enabled &&
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    val pageBlurActive = nativePageBlur
    val fakeBlurSpec = if (fakeBlurPipeline) {
        LiquidGlass.fakeBlurSpec(glass.viewBlurRadiusPx)
    } else {
        null
    }
    val fakeBlurAmount = if (fakeBlurPipeline) {
        LiquidGlass.fakeBlurAmount(glass.viewBlurRadiusPx)
    } else {
        0f
    }
    var fakeBlurRaw by remember { mutableStateOf<Bitmap?>(null) }
    var fakeBlurBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val holdFakeRaw = fakeBlurPipeline && sidebarShowing
    LaunchedEffect(holdFakeRaw) {
        if (!holdFakeRaw) {
            val staleRaw = fakeBlurRaw
            val staleBlur = fakeBlurBitmap
            fakeBlurRaw = null
            fakeBlurBitmap = null
            try {
                delay(64)
            } finally {
                staleRaw?.takeUnless { it.isRecycled }?.recycle()
                staleBlur?.takeUnless { it.isRecycled }?.recycle()
            }
            return@LaunchedEffect
        }
        if (fakeBlurRaw != null) return@LaunchedEffect
        val window = view.context.findActivity()?.window
        val captured = OverviewBackdropCapture.captureUnblurred(
            view = view,
            window = window,
            maxEdgePx = 640,
        )
        ensureActive()
        if (captured != null) fakeBlurRaw = captured
    }
    val specKey = fakeBlurSpec?.quantKey
    LaunchedEffect(fakeBlurRaw, specKey) {
        val src = fakeBlurRaw
        val spec = fakeBlurSpec
        if (src == null || src.isRecycled || spec == null) {
            val stale = fakeBlurBitmap
            fakeBlurBitmap = null
            try {
                delay(16)
            } finally {
                stale?.takeUnless { it.isRecycled }?.recycle()
            }
            return@LaunchedEffect
        }
        val next = withContext(Dispatchers.Default) {
            AmbientBitmapBlur.create(
                source = src,
                maxEdgePx = spec.maxEdgePx,
                radius = spec.radius,
            )
        }
        ensureActive()
        val stale = fakeBlurBitmap
        fakeBlurBitmap = next
        try {
            delay(16)
        } finally {
            if (stale !== next) stale?.takeUnless { it.isRecycled }?.recycle()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            fakeBlurRaw?.takeUnless { it.isRecycled }?.recycle()
            fakeBlurBitmap?.takeUnless { it.isRecycled }?.recycle()
            fakeBlurRaw = null
            fakeBlurBitmap = null
        }
    }
    DisposableEffect(sidebarShowing) {
        if (!sidebarShowing) return@DisposableEffect onDispose {}
        // Same suppression as WebView / Quick Entity overlays: hide status + nav
        // and keep pressing them down so page frost fills the strip they occupied.
        AvaSystemChrome.setOverlayBarSuppression(true, view)
        onDispose {
            AvaSystemChrome.setOverlayBarSuppression(false, view)
        }
    }
    LaunchedEffect(sidebarShowing) {
        if (!sidebarShowing) return@LaunchedEffect
        while (true) {
            delay(80)
            AvaSystemChrome.setOverlayBarSuppression(true, view)
        }
    }

    LaunchedEffect(Unit) {
        revealHandle()
    }

    fun settleTo(open: Boolean, velocityPxPerSec: Float = 0f) {
        isOpen = open
        val fractionVelocity = (-directionSign * velocityPxPerSec) / closedOffsetPx
        snapJob?.cancel()
        settleJob?.cancel()
        settleJob = coroutineScope.launch {
            openFraction.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = 600f
                ),
                initialVelocity = fractionVelocity
            )
            edgeDragDistancePx = 0f
            if (!open) {
                revealHandle()
            }
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled && isOpen) {
            settleTo(false)
        }
    }

    val closeDrawer = { settleTo(false) }
    val isOpenState = rememberUpdatedState(isOpen)
    val settleToState = rememberUpdatedState { open: Boolean, velocity: Float ->
        settleTo(open, velocity)
    }

    // Remote-control MENU key. Consume per instance: a layout composed after
    // the press (e.g. right after navigation) must not replay a stale tick.
    val menuToggleTick by SidebarDrawerRemote.toggleTick.collectAsState()
    var lastMenuToggleTick by remember { mutableLongStateOf(menuToggleTick) }
    LaunchedEffect(menuToggleTick, enabled) {
        if (menuToggleTick == lastMenuToggleTick) return@LaunchedEffect
        lastMenuToggleTick = menuToggleTick
        if (enabled) {
            settleTo(!isOpenState.value)
        }
    }

    // D-pad support: an opening drawer must take focus, otherwise arrow keys keep
    // navigating the (covered) content behind it. While closed, its rows are also
    // barred from focus so initial focus search never lands on offscreen items.
    val drawerFocusRequester = remember { FocusRequester() }
    val drawerListFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var drawerOwnedFocus by remember { mutableStateOf(false) }
    var sidebarRingVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isOpen) {
        sidebarRingVisible = false
        if (isOpen) {
            drawerOwnedFocus = true
            // One frame so the panel's focusProperties(canFocus) flip lands first.
            withFrameNanos { }
            // Swipe-open on a cold start must not paint a selection. MENU / D-pad
            // arm RemoteFocusSession first, then this lands on the function list.
            if (!RemoteFocusSession.isActive) return@LaunchedEffect
            val enteredList = runCatching { drawerListFocusRequester.requestFocus() }.isSuccess
            if (!enteredList) {
                runCatching { drawerFocusRequester.requestFocus() }
            }
        } else if (drawerOwnedFocus) {
            drawerOwnedFocus = false
            focusManager.clearFocus()
        }
    }

    val swipeOpenThresholdPx = with(density) { 36.dp.toPx() }
    val enabledState = rememberUpdatedState(enabled)
    val closedOffsetState = rememberUpdatedState(closedOffsetPx)
    val directionSignState = rememberUpdatedState(directionSign)
    val swipeThresholdState = rememberUpdatedState(swipeOpenThresholdPx)
    val lifecycleOwner = LocalLifecycleOwner.current
    var hostResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            hostResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hostResumedState = rememberUpdatedState(hostResumed)
    DisposableEffect(Unit) {
        var totalX = 0f
        var startDistance = 0f
        val sink = object : SidebarDrawerRemote.EdgeSwipeSink {
            override fun onPadEdgeSwipeBegin(): Boolean {
                if (!enabledState.value || !hostResumedState.value) return false
                revealHandleState.value()
                settleJob?.cancel()
                snapJob?.cancel()
                totalX = 0f
                val closed = closedOffsetState.value
                startDistance = openFraction.value * closed
                isEdgeDragging = true
                edgeDragDistancePx = startDistance
                return true
            }

            override fun onPadEdgeSwipeNudge(dx: Float): Boolean {
                if (!isEdgeDragging) return false
                totalX += dx
                val closed = closedOffsetState.value
                val next = (startDistance + (-directionSignState.value * totalX))
                    .coerceIn(0f, closed.coerceAtLeast(0f))
                edgeDragDistancePx = next
                snapJob?.cancel()
                snapJob = coroutineScope.launch {
                    openFraction.snapTo(if (closed > 0f) next / closed else 0f)
                }
                return true
            }

            override fun onPadEdgeSwipeEnd(totalDx: Float, velocityPxPerSec: Float): Boolean {
                if (!isEdgeDragging) return false
                isEdgeDragging = false
                val sign = directionSignState.value
                val closed = closedOffsetState.value
                val directedVelocity = -sign * velocityPxPerSec
                val directedTotalPx = -sign * totalX
                val fling = FLING_VELOCITY_THRESHOLD * closed
                val next = (startDistance + directedTotalPx).coerceIn(0f, closed.coerceAtLeast(0f))
                val shouldOpen = when {
                    directedVelocity > fling -> true
                    directedVelocity < -fling -> false
                    startDistance >= closed * 0.5f -> next >= closed * 0.5f
                    else -> directedTotalPx >= swipeThresholdState.value
                }
                settleToState.value(shouldOpen, velocityPxPerSec)
                return true
            }

            override fun onPadEdgeSwipeCancel(): Boolean {
                if (!isEdgeDragging) return false
                isEdgeDragging = false
                settleToState.value(isOpenState.value, 0f)
                return true
            }

            override fun onPadForceClose(): Boolean {
                val showing = isOpenState.value || isEdgeDragging || openFraction.value > 0.02f
                if (!showing) return false
                isEdgeDragging = false
                settleToState.value(false, 0f)
                return true
            }
        }
        SidebarDrawerRemote.register(sink)
        onDispose { SidebarDrawerRemote.unregister(sink) }
    }
    val touchSlopPx = viewConfiguration.touchSlop
    val drawerProgress = openFraction.value
    // Scrim only after fully open — showing it mid-animation lets the opening
    // finger-up / stray click immediately close the drawer again.
    val showDismiss = isOpen && drawerProgress >= 0.98f
    val showHandleSlot = drawerProgress < 1f
    val panelSpan = effectiveDrawerWidth + edgeInset
    val rowWidth =
        if (drawerProgress < 1f) panelSpan + resolvedHandleHitWidth else panelSpan

    val dismissInteractionSource = remember { MutableInteractionSource() }
    val rootAlignment = if (isRightDrawer) Alignment.CenterEnd else Alignment.CenterStart
    val handleAlignment = if (isRightDrawer) Alignment.CenterEnd else Alignment.CenterStart

    // Do NOT key on isOpen — restarting pointerInput mid/after UP cancels the gesture
    // and previously raced settle animations (drawer opened then snapped shut).
    val handleGestureModifier = Modifier.pointerInput(
        isRightDrawer,
        closedOffsetPx,
        swipeOpenThresholdPx,
        touchSlopPx,
    ) {
        val velocityTracker = VelocityTracker()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (isOpenState.value) {
                // Fully-open handle is usually unmounted; if still settling, ignore.
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUp() || !change.pressed) break
                }
                return@awaitEachGesture
            }

            revealHandleState.value()
            velocityTracker.resetTracking()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            var totalDragX = 0f
            var dragging = false

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                if (change.changedToUp() || !change.pressed) {
                    val wasDragging = dragging
                    isEdgeDragging = false
                    if (!wasDragging) {
                        settleToState.value(true, 0f)
                    } else {
                        val velocityPxPerSec = velocityTracker.calculateVelocity().x
                        val directedVelocity = -directionSign * velocityPxPerSec
                        val directedTotalPx = -directionSign * totalDragX
                        val flingThresholdPxPerSec = FLING_VELOCITY_THRESHOLD * closedOffsetPx
                        val shouldOpen = when {
                            directedVelocity > flingThresholdPxPerSec -> true
                            directedVelocity < -flingThresholdPxPerSec -> false
                            else -> directedTotalPx >= swipeOpenThresholdPx
                        }
                        edgeDragDistancePx = if (shouldOpen) {
                            edgeDragDistancePx.coerceAtLeast(0f)
                        } else {
                            0f
                        }
                        settleToState.value(shouldOpen, velocityPxPerSec)
                    }
                    break
                }

                val dx = change.positionChange().x
                val dy = change.positionChange().y
                if (dx == 0f && dy == 0f) continue
                totalDragX += dx
                velocityTracker.addPosition(
                    change.uptimeMillis,
                    Offset(totalDragX, 0f),
                )

                if (!dragging && abs(totalDragX) >= touchSlopPx) {
                    dragging = true
                    isEdgeDragging = true
                    settleJob?.cancel()
                    edgeDragDistancePx = 0f
                }
                if (dragging) {
                    change.consume()
                    edgeDragDistancePx = (-directionSign * totalDragX).coerceAtLeast(0f)
                    val next = (edgeDragDistancePx / closedOffsetPx).coerceIn(0f, 1f)
                    // pointerInput is a restricted coroutine — cannot call Animatable.snapTo
                    // here. Cancel-before-launch so late snaps cannot race settleTo.
                    snapJob?.cancel()
                    snapJob = coroutineScope.launch { openFraction.snapTo(next) }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(WindowInsets.systemBars)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (isOpenState.value) continue
                                if (event.changes.any { it.pressed && !it.previousPressed }) {
                                    revealHandleState.value()
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = rootAlignment
    ) {
        // Native frost lives on the page layer; fake frost is a snapshot overlay
        // so the live tree is never asked for RenderEffect below API 31.
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Draw into the system-bar strips so frost covers the nav-bar
                // region once overlay suppression hides the bar itself.
                .consumeWindowInsets(WindowInsets.systemBars)
                .then(
                    if (pageBlurActive) {
                        Modifier.graphicsLayer {
                            clip = false
                            // Blur + saturation boost: plain Gaussian drains colour and
                            // reads as grey plastic; the boost is what makes it glass.
                            val radius = glass.viewBlurRadiusPx * openFraction.value
                            renderEffect = LiquidGlass.backdropEffect(radius)?.asComposeRenderEffect()
                        }
                    } else Modifier
                ),
            content = content,
        )

        val fakeBlurImage = remember(fakeBlurBitmap) {
            fakeBlurBitmap
                ?.takeUnless { it.isRecycled }
                ?.let { runCatching { it.asImageBitmap() }.getOrNull() }
        }
        if (fakeBlurImage != null && fakeBlurSpec != null && openFraction.value > 0.01f) {
            val frost = openFraction.value.coerceIn(0f, 1f)
            Image(
                bitmap = fakeBlurImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = frost }
                    .focusProperties { canFocus = false },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(
                            alpha = (if (isDarkMode) 0.18f else 0.12f) *
                                frost * fakeBlurAmount.coerceIn(0f, 1f),
                        ),
                    )
                    .focusProperties { canFocus = false },
            )
        }

        if (!enabled) return@Box

        if (showDismiss) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = dismissInteractionSource,
                        indication = null,
                        onClick = closeDrawer
                    )
                    // Tap-to-dismiss only. A full-screen clickable with no ring
                    // would swallow D-pad (OK closes the drawer; left/right vanish).
                    .focusProperties { canFocus = false }
            )
        }

        // Glass: a floating rounded menu — 30dp top/bottom in portrait, 20dp in
        // landscape, a fixed 10dp gap from the screen edge (travels with the
        // panel, never flush), all four corners 20dp. Off: the original full-bleed slab.
        val panelShape: Shape = if (glass.enabled) {
            RoundedCornerShape(GLASS_PANEL_CORNER)
        } else {
            RectangleShape
        }

        val panelColumn: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .width(effectiveDrawerWidth + edgeInset)
                    .fillMaxHeight()
                    .then(
                        if (glass.enabled) {
                            Modifier.padding(
                                start = if (isRightDrawer) 0.dp else GLASS_PANEL_EDGE_INSET,
                                end = if (isRightDrawer) GLASS_PANEL_EDGE_INSET else 0.dp,
                                top = glassVerticalGap,
                                bottom = glassVerticalGap,
                            )
                        } else {
                            Modifier
                        }
                    )
                    .then(
                        if (glass.enabled) {
                            Modifier.liquidGlass(
                                glass, panelShape, isDarkMode, panelColor,
                                hasBackdropBlur = pageBlurActive,
                                // Fake (pre-31) frost is a page overlay, not a live
                                // backdrop; keep the slab thick so it does not go see-through.
                                // No drop shadow on the drawer. The fake shadow was
                                // shifted down and its clip fringe sat on the bottom
                                // rim — a dark band on the lower edge in light mode.
                                // The specular rim is the glass edge, top and bottom.
                                shadow = false,
                            )
                        } else {
                            Modifier.background(panelColor)
                        }
                    )
                    .focusRequester(drawerFocusRequester)
                    .focusProperties { canFocus = isOpen }
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                        if (isOpen &&
                            event.type == KeyEventType.KeyDown &&
                            RemoteFocusSession.isRemoteNavKey(event.key.keyCode.toInt())
                        ) {
                            sidebarRingVisible = true
                        }
                        false
                    }
                    .pointerInput(isOpen) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (isOpen &&
                                    event.changes.any { it.pressed && !it.previousPressed }
                                ) {
                                    sidebarRingVisible = false
                                }
                            }
                        }
                    }
                    // Tap-eater (keeps panel taps off the dismiss scrim) as a raw
                    // gesture, NOT clickable: a clickable here would be a focus
                    // target and swallow the D-pad focus meant for the rows.
                    .pointerInput(Unit) { detectTapGestures { } },
                content = {
                    CompositionLocalProvider(
                        LocalSidebarDrawerListFocus provides drawerListFocusRequester,
                        LocalSidebarDrawerFocusEnabled provides isOpen,
                        LocalSidebarFocusRingVisible provides sidebarRingVisible,
                    ) {
                        drawerContent(closeDrawer)
                    }
                }
            )
        }

        if (isRightDrawer) {
            Row(
                modifier = Modifier
                    .width(rowWidth)
                    .fillMaxHeight()
                    .offset { IntOffset(offsetXPx, 0) }
            ) {
                if (drawerProgress < 1f) {
                    Spacer(
                        modifier = Modifier
                            .width(resolvedHandleHitWidth)
                            .fillMaxHeight()
                    )
                }
                panelColumn()
            }
        } else {
            Row(
                modifier = Modifier
                    .width(rowWidth)
                    .fillMaxHeight()
                    .offset { IntOffset(offsetXPx, 0) }
            ) {
                panelColumn()
                if (drawerProgress < 1f) {
                    Spacer(
                        modifier = Modifier
                            .width(resolvedHandleHitWidth)
                            .fillMaxHeight()
                    )
                }
            }
        }

        if (showHandleSlot) {
            SystemStyleEdgeHandle(
                isDarkMode = isDarkMode,
                onClick = { settleTo(!isOpen) },
                mirrored = isRightDrawer,
                canShow = drawerProgress < 1f,
                isDragging = isEdgeDragging,
                dragDistancePx = edgeDragDistancePx,
                drawerOpenFraction = drawerProgress,
                fadeWithDrawerOpen = isOpen && drawerProgress < 1f,
                revealTick = handleRevealTick,
                onReveal = revealHandle,
                // hitWidth / hitHeight are the touch box around the visible slider.
                // The drawn bar stays edge-anchored and vertically centered.
                metrics = handleMetrics.copy(hitWidth = resolvedHandleHitWidth),
                hitHeight = resolvedHandleHitHeight,
                gesturesEnabled = false,
                modifier = Modifier
                    .align(handleAlignment)
                    .then(handleGestureModifier),
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
