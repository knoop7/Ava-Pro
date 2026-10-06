package com.example.ava.services

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.ava.settings.SettingsStyleSession
import com.example.ava.settings.SidebarPosition
import com.example.ava.settings.SidebarSettings
import com.example.ava.ui.AvaSystemChrome
import com.example.ava.ui.components.SidebarDrawerRemote
import com.example.ava.ui.components.SystemStyleEdgeHandleSpec
import com.example.ava.ui.components.SystemStyleEdgeHandleView
import com.example.ava.ui.glass.LiquidGlass
import com.example.ava.ui.glass.LiquidGlassDrawable
import com.example.ava.ui.screens.home.HomeSidebarContent
import com.example.ava.ui.screens.home.HomeSidebarMetrics
import com.example.ava.ui.theme.AvaTheme
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private val PANEL_COLOR_LIGHT = 0xFFF8FAFC.toInt()

/**
 * Browser-only edge sidebar layered inside [WebViewService]'s overlay container.
 *
 * Always [ViewGroup.LayoutParams.MATCH_PARENT] like home [com.example.ava.ui.components.LeftSidebarDrawerLayout]
 * so opening never morphs host width (that double-jumped and blanked the WebView). Closed / mid-open
 * touches outside the handle + panel fall through to the page underneath.
 *
 * Handle visual is a fixed [SystemStyleEdgeHandleView] (1:1 with home Compose /
 * MiSystemUI GlobalGestureAnimationView). The panel slides underneath; the stroke
 * stays on the screen edge and morphs with pull distance.
 */
class BrowserSidebarOverlayView(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    viewModelStoreOwner: ViewModelStoreOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
    private val onSettingsClick: (route: String) -> Unit,
    /** Fired only when open/closed intent actually flips (not mid-drag settle to same state). */
    private val onOpenChanged: ((Boolean) -> Unit)? = null,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val drawerWidthPx = resolveDrawerWidthPx(context)
    private val handleHitWidthPx =
        SystemStyleEdgeHandleSpec.expandedHitWidthPx(context).roundToInt().coerceAtLeast(1)
    /** Slider height + pad — same band as home [LeftSidebarDrawerLayout]. */
    private val handleHitHeightPx =
        SystemStyleEdgeHandleSpec.expandedHitHeightPx(context).roundToInt().coerceAtLeast(1)
    private val edgeInsetPx: Int
        get() = if (LiquidGlass.enabled) (10f * density).roundToInt() else 0
    private val closedOffsetPx: Float
        get() = (drawerWidthPx + edgeInsetPx).toFloat()
    private val slideRowWidthPx: Int
        get() = drawerWidthPx + edgeInsetPx + handleHitWidthPx

    private var position = SidebarPosition.LEFT
    private var isOpen = false
    private var openFraction = 0f
    private var isEdgeDragging = false
    private var isDarkMode = false
    private var edgeDragPx = 0f
    private var glassJob: Job? = null
    /** True while sibling page views carry a Liquid Glass RenderEffect frost. */
    private var pageFrostLive = false

    private val openFractionProperty =
        object : FloatPropertyCompat<BrowserSidebarOverlayView>("browserSidebarOpenFraction") {
            override fun getValue(obj: BrowserSidebarOverlayView) = obj.openFraction
            override fun setValue(obj: BrowserSidebarOverlayView, value: Float) {
                obj.applyOpenFraction(value, notify = false)
            }
        }
    private val settleAnimation = SpringAnimation(this, openFractionProperty).apply {
        spring = SpringForce().apply {
            dampingRatio = SPRING_DAMPING_RATIO
            stiffness = SPRING_STIFFNESS
        }
        setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA)
        addEndListener { _, canceled, _, _ ->
            if (!canceled && !isEdgeDragging) {
                applyOpenFraction(if (isOpen) 1f else 0f, notify = false)
                if (!isOpen) {
                    // Sidebar closed: show edge bar briefly, then auto-hide.
                    handleView.showIndicator()
                }
            }
        }
    }

    private val dismissView = View(context).apply {
        setOnClickListener { setOpen(false, animate = true) }
    }
    private val panelCompose = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        // AndroidComposeView (the child) inherits the light-theme window colour as
        // an opaque fill. Clear it once. Calling setBackgroundColor() on every
        // layout rebuilds a ColorDrawable and requestLayout()s during layout —
        // a 60 fps "second layout pass" storm that floods logcat until adb dies.
        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val child = (v as? ViewGroup)?.getChildAt(0) ?: return@addOnLayoutChangeListener
            if (child.background == null) return@addOnLayoutChangeListener
            child.post {
                if (child.background != null) child.background = null
            }
        }
    }
    private val hitSpacer = View(context).apply {
        // Invisible hit strip that rides with the slide row for gesture capture width.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private lateinit var handleView: SystemStyleEdgeHandleView
    private val slideRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    /** Fixed edge indication — slider-sized hit box, does not translate with [slideRow]. */
    private val handleOverlay = FrameLayout(context)
    private val padEdgeSink = object : SidebarDrawerRemote.EdgeSwipeSink {
        override fun onPadEdgeSwipeBegin(): Boolean {
            if (!isAttachedToWindow || visibility != VISIBLE) return false
            settleAnimation.cancel()
            edgeDragPx = openFraction * closedOffsetPx
            isEdgeDragging = true
            handleView.setDistance(edgeDragPx)
            updateHandleVisibility()
            pulseOverlayBarSuppression(true)
            return true
        }

        override fun onPadEdgeSwipeNudge(dx: Float): Boolean {
            if (!isEdgeDragging) return false
            applyEdgeDrag(dx)
            return true
        }

        override fun onPadEdgeSwipeEnd(totalDx: Float, velocityPxPerSec: Float): Boolean {
            if (!isEdgeDragging) return false
            finishEdgeDrag(totalDx, velocityPxPerSec)
            return true
        }

        override fun onPadEdgeSwipeCancel(): Boolean {
            if (!isEdgeDragging) return false
            cancelEdgeDrag()
            return true
        }

        override fun onPadForceClose(): Boolean {
            if (!isAttachedToWindow || visibility != VISIBLE) return false
            if (!isOpen && !isEdgeDragging && openFraction <= 0.02f) return false
            isEdgeDragging = false
            setOpen(false, animate = true)
            return true
        }
    }

    init {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        clipChildren = false
        handleView = createHandleView(mirrored = false)
        addView(
            dismissView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        addView(
            slideRow,
            LayoutParams(slideRowWidthPx, LayoutParams.MATCH_PARENT)
        )
        addView(
            handleOverlay,
            LayoutParams(handleHitWidthPx, handleHitHeightPx).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        )
        panelCompose.layoutParams = LinearLayout.LayoutParams(drawerWidthPx, LayoutParams.MATCH_PARENT).apply {
            if (LiquidGlass.enabled) {
                val vGap = glassVerticalGapPx()
                topMargin = vGap
                bottomMargin = vGap
            }
        }
        hitSpacer.layoutParams = LinearLayout.LayoutParams(handleHitWidthPx, LayoutParams.MATCH_PARENT)
        updatePanelContent()
        applySlideLayout()
        resetInteractionState()
    }

    /**
     * Closed / mid-open: only the handle strip and visible panel consume touches so the WebView
     * keeps receiving the rest. Fully open: whole overlay (dismiss scrim + panel).
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!shouldConsumeTouch(event)) return false
        return super.dispatchTouchEvent(event)
    }

    private fun shouldConsumeTouch(event: MotionEvent): Boolean {
        if (isEdgeDragging) return true
        if (isOpen && openFraction >= 0.98f) return true
        if (openFraction > 0.01f && isTouchOnSlideRow(event)) return true
        return isTouchOnHandle(event)
    }

    private fun isTouchOnHandle(event: MotionEvent): Boolean {
        if (openFraction >= 1f) return false
        if (handleOverlay.visibility != VISIBLE) return false
        val left = handleOverlay.x
        val right = left + handleOverlay.width
        val top = handleOverlay.y
        val bottom = top + handleOverlay.height
        return event.x in left..right && event.y in top..bottom
    }

    private fun isTouchOnSlideRow(event: MotionEvent): Boolean {
        val left = slideRow.x + slideRow.translationX
        val right = left + slideRow.width
        val top = slideRow.y
        val bottom = top + slideRow.height
        return event.x in left..right && event.y in top..bottom
    }

    private fun createHandleView(mirrored: Boolean): SystemStyleEdgeHandleView {
        return SystemStyleEdgeHandleView(
            context = context,
            mirrored = mirrored,
            onClick = { toggleOpen() },
            onDragStart = {
                settleAnimation.cancel()
                edgeDragPx = openFraction * closedOffsetPx
                isEdgeDragging = true
                handleView.setDistance(edgeDragPx)
                updateHandleVisibility()
                pulseOverlayBarSuppression(true)
            },
            onDrag = { deltaPx -> applyEdgeDrag(deltaPx) },
            onDragEnd = { totalDeltaPx, velocityPxPerSec -> finishEdgeDrag(totalDeltaPx, velocityPxPerSec) },
            onDragCancel = { cancelEdgeDrag() },
        ).also { it.isDarkMode = isDarkMode }
    }

    fun applySettings(settings: SidebarSettings, darkMode: Boolean) {
        val positionChanged = position != settings.sidebarPosition
        position = settings.sidebarPosition
        if (darkMode != isDarkMode) {
            isDarkMode = darkMode
            handleView.isDarkMode = darkMode
            updatePanelContent()
        }
        applyPanelBackground()
        if (positionChanged) {
            applySlideLayout()
            applyOpenFraction(if (isOpen) 1f else 0f, notify = false)
        }
        updateHandleVisibility()
        ensureFullSizeOverlay()
        invalidate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPanelBackground()
    }

    /** Portrait 30dp, landscape 20dp — matches home/settings [LeftSidebarDrawerLayout]. */
    private fun glassVerticalGapPx(): Int {
        val portrait =
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        return ((if (portrait) 30f else 20f) * density).roundToInt()
    }

    /**
     * Glass panel over the page. API 31+: page siblings pick up the same openFraction-
     * scaled frost as home [com.example.ava.ui.components.LeftSidebarDrawerLayout] so
     * open / close reads as blur ↔ clear instead of a hard cut. The panel still carries
     * its own translucent material (rim + sheen). Flat colour when Liquid Glass is off.
     * Pre-31 keeps panel-only glass — stacking a freeze-frame over a live WebView was
     * rejected as a soft wash of the whole dashboard.
     */
    private fun applyPanelBackground() {
        LiquidGlass.ensureLoaded(context)
        val glass = LiquidGlass.enabled
        val cornerPx = if (glass) 20f * density else 0f
        val vGap = if (glass) glassVerticalGapPx() else 0
        val edge = if (glass) (10f * density).roundToInt() else 0
        (panelCompose.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            val start = if (position == SidebarPosition.LEFT) edge else 0
            val end = if (position == SidebarPosition.RIGHT) edge else 0
            if (lp.topMargin != vGap || lp.bottomMargin != vGap ||
                lp.marginStart != start || lp.marginEnd != end
            ) {
                lp.topMargin = vGap
                lp.bottomMargin = vGap
                lp.marginStart = start
                lp.marginEnd = end
                panelCompose.layoutParams = lp
            }
        }
        (slideRow.layoutParams as? LayoutParams)?.let { lp ->
            if (lp.width != slideRowWidthPx) {
                lp.width = slideRowWidthPx
                slideRow.layoutParams = lp
            }
        }
        if (glass) {
            panelCompose.background = LiquidGlassDrawable(
                cornerRadiusPx = cornerPx,
                tint = if (isDarkMode) {
                    LiquidGlassDrawable.DEFAULT_DARK_TINT
                } else {
                    LiquidGlassDrawable.DEFAULT_LIGHT_TINT
                },
                light = !isDarkMode,
            ).also { it.setDensity(density) }
            panelCompose.clipToOutline = true
            panelCompose.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            // Do not elevate this ComposeView. Platform elevation + an opaque
            // outline treats the panel as an occluder; in light mode the skipped
            // backdrop is the window's white background, so scrolling rows ride
            // a solid white sheet. Home/settings glass draws its own shadow.
            panelCompose.elevation = 0f
            slideRow.clipChildren = false
            slideRow.clipToPadding = false
        } else {
            panelCompose.background = null
            panelCompose.clipToOutline = false
            panelCompose.elevation = 0f
            panelCompose.setBackgroundColor(
                if (isDarkMode) android.graphics.Color.BLACK else PANEL_COLOR_LIGHT
            )
        }
        applyOpenFraction(openFraction, notify = false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SidebarDrawerRemote.register(padEdgeSink)
        glassJob?.cancel()
        glassJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            combine(
                SettingsStyleSession.liquidGlassEnabled,
                SettingsStyleSession.liquidGlassIntensity,
                SettingsStyleSession.liquidGlassBlur,
            ) { _, _, _ -> }
                .collect {
                    applyPanelBackground()
                    updatePageFrost(openFraction)
                }
        }
        if (glassJob == null) applyPanelBackground()
    }

    override fun onDetachedFromWindow() {
        SidebarDrawerRemote.unregister(padEdgeSink)
        removeCallbacks(suppressNavBars)
        glassJob?.cancel()
        glassJob = null
        clearPageFrost()
        super.onDetachedFromWindow()
    }

    fun resetInteractionState() {
        settleAnimation.cancel()
        isOpen = false
        openFraction = 0f
        edgeDragPx = 0f
        isEdgeDragging = false
        applyOpenFraction(0f, notify = false)
        clearPageFrost()
        handleView.setCanShow(true)
        handleView.showIndicator()
        pulseOverlayBarSuppression(false)
    }

    /** ACTION_GLOBAL_TOUCH_DOWN equivalent — any WebView tap re-presents the stroke. */
    fun notifyGlobalTouch() {
        if (openFraction < 1f) handleView.notifyGlobalTouch()
    }

    fun isPointInHandleZone(containerX: Float, containerY: Float): Boolean {
        if (openFraction >= 1f) return false
        if (handleOverlay.visibility != VISIBLE) return false
        val left = x + handleOverlay.x
        val right = left + handleOverlay.width
        val top = y + handleOverlay.y
        val bottom = top + handleOverlay.height
        return containerX in left..right && containerY in top..bottom
    }

    /**
     * True when the sidebar itself owns this point — the slider hit box while closed, the
     * whole window once the panel is out (the dismiss scrim goes live there).
     *
     * Used by the overlay container to keep the sidebar reachable while page touch is disabled.
     */
    fun wantsTouchAt(containerX: Float, containerY: Float): Boolean {
        if (visibility != VISIBLE) return false
        if (openFraction > 0f) return true
        return isPointInHandleZone(containerX, containerY)
    }

    private fun applyEdgeDrag(deltaPx: Float) {
        val directedDelta = when (position) {
            SidebarPosition.LEFT -> deltaPx
            SidebarPosition.RIGHT -> -deltaPx
        }
        edgeDragPx = (edgeDragPx + directedDelta).coerceIn(0f, closedOffsetPx.toFloat())
        handleView.setDistance(edgeDragPx)
        applyOpenFraction(edgeDragPx / closedOffsetPx.toFloat(), notify = false)
    }

    private fun finishEdgeDrag(totalDeltaPx: Float, velocityPxPerSec: Float) {
        isEdgeDragging = false
        val directedTotal = when (position) {
            SidebarPosition.LEFT -> totalDeltaPx
            SidebarPosition.RIGHT -> -totalDeltaPx
        }
        val directedVelocity = when (position) {
            SidebarPosition.LEFT -> velocityPxPerSec
            SidebarPosition.RIGHT -> -velocityPxPerSec
        }
        val flingThresholdPxPerSec = FLING_VELOCITY_THRESHOLD_FRACTION * closedOffsetPx
        val shouldOpen = when {
            directedVelocity > flingThresholdPxPerSec -> true
            directedVelocity < -flingThresholdPxPerSec -> false
            else -> directedTotal >= SWIPE_OPEN_THRESHOLD_DP * density
        }
        if (shouldOpen) {
            // Keep last morph; hand fade-out to drawer open progress.
            handleView.animateReset(fadeWithDrawer = true)
        } else {
            edgeDragPx = 0f
            handleView.animateReset(fadeWithDrawer = false)
        }
        updateHandleVisibility()
        setOpen(shouldOpen, animate = true, startVelocityPxPerSec = directedVelocity)
    }

    private fun cancelEdgeDrag() {
        isEdgeDragging = false
        edgeDragPx = 0f
        handleView.animateReset(fadeWithDrawer = false)
        updateHandleVisibility()
        setOpen(isOpen, animate = true)
    }

    fun refreshPanelContent() {
        updatePanelContent()
    }

    private fun updatePanelContent() {
        panelCompose.setContent {
            AvaTheme(darkTheme = isDarkMode) {
                HomeSidebarContent(
                    isDarkMode = isDarkMode,
                    onClose = { setOpen(false, animate = true) },
                    onSettingsClick = onSettingsClick,
                    interactiveSatelliteStatus = false,
                    includeBrowserTools = true,
                    onBrowserWebConsole = {
                        WebViewService.showWebConsole(context)
                    },
                    onBrowserClearCache = {
                        WebViewService.clearBrowserCache(context)
                    },
                    onBrowserUserAgent = {
                        WebViewService.showUserAgentPicker(context)
                    },
                    onBrowserRemoteUrl = {
                        WebViewService.showRemoteUrlEditor(context)
                    },
                    onBrowserTampermonkey = {
                        WebViewService.showTampermonkeyPicker(context)
                    },
                    onBrowserToggleHaKiosk = {
                        WebViewService.toggleHaKioskMode(context)
                    },
                )
            }
        }
    }

    private fun applySlideLayout() {
        val mirrored = position == SidebarPosition.RIGHT
        if (this::handleView.isInitialized) {
            handleOverlay.removeAllViews()
        }
        handleView = createHandleView(mirrored = mirrored)
        handleOverlay.addView(
            handleView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        (handleOverlay.layoutParams as LayoutParams).apply {
            width = handleHitWidthPx
            height = handleHitHeightPx
            gravity = when (position) {
                SidebarPosition.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
                SidebarPosition.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
            }
        }
        handleOverlay.layoutParams = handleOverlay.layoutParams

        slideRow.removeAllViews()
        when (position) {
            SidebarPosition.LEFT -> {
                slideRow.addView(panelCompose)
                slideRow.addView(hitSpacer)
                (slideRow.layoutParams as LayoutParams).apply {
                    width = slideRowWidthPx
                    gravity = Gravity.START
                }
            }
            SidebarPosition.RIGHT -> {
                slideRow.addView(hitSpacer)
                slideRow.addView(panelCompose)
                (slideRow.layoutParams as LayoutParams).apply {
                    width = slideRowWidthPx
                    gravity = Gravity.END
                }
            }
        }
        slideRow.layoutParams = slideRow.layoutParams
    }

    /** Public for the remote-control MENU key ([WebViewService.toggleBrowserSidebar]). */
    fun toggleOpen() {
        setOpen(!isOpen, animate = true)
    }

    /** Fully open (settled) — remote nav keys should drive the sidebar, not the page. */
    fun isSidebarOpen(): Boolean = isOpen && openFraction >= 0.98f

    /**
     * Close with the normal spring and suspend until frost + panel have settled shut.
     * Used before jumping into Settings so the page is not torn down mid-blur.
     */
    suspend fun closeAnimatedAndAwait() {
        if (openFraction <= 0.02f && !isOpen && !settleAnimation.isRunning) {
            applyOpenFraction(0f, notify = false)
            clearPageFrost()
            return
        }
        suspendCancellableCoroutine { cont ->
            val listener = object : DynamicAnimation.OnAnimationEndListener {
                override fun onAnimationEnd(
                    animation: DynamicAnimation<*>,
                    canceled: Boolean,
                    value: Float,
                    velocity: Float,
                ) {
                    // setOpen cancels a prior spring before starting the close —
                    // ignore that cancel and wait for the close to finish.
                    if (canceled) return
                    settleAnimation.removeEndListener(this)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            cont.invokeOnCancellation {
                settleAnimation.removeEndListener(listener)
            }
            settleAnimation.addEndListener(listener)
            setOpen(false, animate = true)
            if (!settleAnimation.isRunning) {
                settleAnimation.removeEndListener(listener)
                applyOpenFraction(0f, notify = false)
                clearPageFrost()
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    /**
     * Remote D-pad routing while the sidebar is open. The overlay window is
     * usually FLAG_NOT_FOCUSABLE, so hardware keys land on MainActivity and are
     * forwarded here; injecting into the ComposeView drives Compose focus
     * navigation between the sidebar rows. Always consumes while open so
     * arrows/enter never leak into the WebView underneath; BACK closes.
     */
    fun dispatchRemoteKey(event: android.view.KeyEvent): Boolean {
        if (!isSidebarOpen()) return false
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_BACK -> {
                if (event.action == android.view.KeyEvent.ACTION_UP) {
                    setOpen(false, animate = true)
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
            android.view.KeyEvent.KEYCODE_TAB,
            android.view.KeyEvent.KEYCODE_PAGE_UP,
            android.view.KeyEvent.KEYCODE_PAGE_DOWN,
            -> {
                if (!panelCompose.hasFocus()) {
                    panelCompose.requestFocus()
                }
                panelCompose.dispatchKeyEvent(event)
                return true
            }
            else -> return false
        }
    }

    private val suppressNavBars = object : Runnable {
        override fun run() {
            if (!isOpen && openFraction <= 0.02f) return
            AvaSystemChrome.setOverlayBarSuppression(true, this@BrowserSidebarOverlayView)
            postDelayed(this, 80)
        }
    }

    private fun pulseOverlayBarSuppression(on: Boolean) {
        removeCallbacks(suppressNavBars)
        AvaSystemChrome.setOverlayBarSuppression(on, this)
        if (on) post(suppressNavBars)
    }

    fun setOpen(open: Boolean, animate: Boolean, startVelocityPxPerSec: Float = 0f) {
        val target = if (open) 1f else 0f
        if (isOpen == open && openFraction == target && !settleAnimation.isRunning) {
            updateHandleVisibility()
            ensureFullSizeOverlay()
            pulseOverlayBarSuppression(open)
            return
        }
        val flipped = isOpen != open
        isOpen = open
        updateHandleVisibility()
        ensureFullSizeOverlay()
        pulseOverlayBarSuppression(open)
        if (!animate) {
            settleAnimation.cancel()
            applyOpenFraction(target, notify = false)
            if (!open) {
                handleView.showIndicator()
            }
            if (flipped) onOpenChanged?.invoke(open)
            return
        }
        settleAnimation.cancel()
        settleAnimation.setStartValue(openFraction)
        settleAnimation.setStartVelocity(startVelocityPxPerSec / closedOffsetPx)
        settleAnimation.animateToFinalPosition(target)
        if (flipped) onOpenChanged?.invoke(open)
    }

    private fun applyOpenFraction(fraction: Float, notify: Boolean) {
        openFraction = fraction.coerceIn(0f, 1f)
        val offset = when (position) {
            SidebarPosition.LEFT -> -closedOffsetPx * (1f - openFraction)
            SidebarPosition.RIGHT -> closedOffsetPx * (1f - openFraction)
        }
        slideRow.translationX = offset
        handleView.setDrawerOpenFraction(openFraction)
        if (openFraction >= 1f) {
            edgeDragPx = 0f
        }
        updateHandleVisibility()
        updateDismissLayer()
        updatePageFrost(openFraction)
        if (notify) {
            isOpen = openFraction >= 1f
        }
    }

    /**
     * Match home drawer frost: scale the same-window blur on page siblings by
     * [openFraction] so open/close is blur ↔ clear, not a cut. API 31+ only.
     */
    private fun updatePageFrost(fraction: Float) {
        LiquidGlass.ensureLoaded(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !LiquidGlass.blurEnabled) {
            clearPageFrost()
            return
        }
        val radius = LiquidGlass.viewBlurRadiusPx() * fraction.coerceIn(0f, 1f)
        if (radius < 0.5f) {
            clearPageFrost()
            return
        }
        val effect = LiquidGlass.backdropEffect(radius) ?: run {
            clearPageFrost()
            return
        }
        val parent = parent as? ViewGroup ?: return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            if (child === this) continue
            try {
                child.setRenderEffect(effect)
            } catch (_: Exception) {
                // SurfaceView / OEM quirks — leave that sibling alone.
            }
        }
        pageFrostLive = true
    }

    private fun clearPageFrost() {
        if (!pageFrostLive) return
        val parent = parent as? ViewGroup
        if (parent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i) ?: continue
                if (child === this) continue
                try {
                    child.setRenderEffect(null)
                } catch (_: Exception) {
                }
            }
        }
        pageFrostLive = false
    }

    private fun updateHandleVisibility() {
        // Stay mounted until fully open so open-progress fade can finish.
        // Keep [slideRow] width + spacer layout constant — GONE/shrink at 1f was the second jump.
        val showHandleSlot = openFraction < 1f
        handleOverlay.visibility = if (showHandleSlot) VISIBLE else INVISIBLE
        handleView.setCanShow(openFraction < 1f)
        hitSpacer.visibility = INVISIBLE
    }

    private fun updateDismissLayer() {
        // Only after fully open — mid-open scrim steals the release and closes again.
        val showDismiss = isOpen && openFraction >= 0.98f
        dismissView.visibility = if (showDismiss) VISIBLE else GONE
        dismissView.isClickable = showDismiss
    }

    /**
     * Overlay stays full-size for the whole open/close cycle. Never morph narrow→MATCH_PARENT
     * mid-settle (that forced parent requestLayout and blanked the WebView).
     */
    private fun ensureFullSizeOverlay() {
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width == LayoutParams.MATCH_PARENT &&
            lp.height == LayoutParams.MATCH_PARENT &&
            lp.gravity == (Gravity.TOP or Gravity.START)
        ) {
            return
        }
        lp.width = LayoutParams.MATCH_PARENT
        lp.height = LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.TOP or Gravity.START
        layoutParams = lp
    }

    companion object {
        private const val SWIPE_OPEN_THRESHOLD_DP = 36f
        private const val SPRING_DAMPING_RATIO = 1f
        private const val SPRING_STIFFNESS = 600f
        private const val FLING_VELOCITY_THRESHOLD_FRACTION = 1.2f

        private fun resolveDrawerWidthPx(context: Context): Int {
            val configuration = context.resources.configuration
            val shortestSideDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
            val longestSideDp = maxOf(configuration.screenWidthDp, configuration.screenHeightDp)
            val isLandscape =
                configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val drawerWidthDp = HomeSidebarMetrics.drawerWidthDp(
                shortestSideDp = shortestSideDp,
                longestSideDp = longestSideDp,
                isLandscape = isLandscape,
            )
            return (drawerWidthDp * context.resources.displayMetrics.density).roundToInt()
        }

        fun attach(
            container: ViewGroup,
            lifecycleOwner: LifecycleOwner,
            viewModelStoreOwner: ViewModelStoreOwner,
            savedStateRegistryOwner: SavedStateRegistryOwner,
            settings: SidebarSettings,
            darkMode: Boolean,
            onSettingsClick: (route: String) -> Unit,
            onOpenChanged: ((Boolean) -> Unit)? = null,
        ): BrowserSidebarOverlayView? {
            val overlay = BrowserSidebarOverlayView(
                context = container.context,
                lifecycleOwner = lifecycleOwner,
                viewModelStoreOwner = viewModelStoreOwner,
                savedStateRegistryOwner = savedStateRegistryOwner,
                onSettingsClick = onSettingsClick,
                onOpenChanged = onOpenChanged,
            )
            overlay.applySettings(settings, darkMode)
            container.clipChildren = false
            container.addView(
                overlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
            )
            return overlay
        }
    }
}
