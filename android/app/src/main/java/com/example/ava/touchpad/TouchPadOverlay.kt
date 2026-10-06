package com.example.ava.touchpad

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.ava.R
import com.example.ava.services.AccessibilityBridge
import com.example.ava.services.AiBrowserService
import com.example.ava.services.AppWindowService
import com.example.ava.services.OverlayOrientation
import com.example.ava.services.OverlayZOrderCoordinator
import com.example.ava.services.ScreensaverController
import com.example.ava.services.WebViewService
import com.example.ava.utils.ScreenBlankOverlay
import com.example.ava.settings.DarkModeManager
import com.example.ava.settings.SettingsStyleSession
import com.example.ava.settings.SidebarPosition
import com.example.ava.ui.MainNavigationCoordinator
import com.example.ava.ui.components.PadContentScrollRemote
import com.example.ava.ui.components.SidebarDrawerRemote
import com.example.ava.ui.components.SystemStyleEdgeHandleSpec
import com.example.ava.ui.glass.LiquidGlass
import com.example.ava.ui.glass.LiquidGlassDrawable
import com.example.ava.ui.haptic.OverlayHaptics
import com.example.ava.ui.haptic.overlayScrollHapticIntensity
import com.example.ava.ui.screens.home.HomeLayoutSettingsMirror
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One Hand Control 1.2.6 Touch Pad replica: accessibility overlay trackpad +
 * on-screen cursor, injecting taps/holds/swipes with [AccessibilityService.dispatchGesture].
 * Two-finger scroll is live (Apple trackpad): node actions while fingers stay down.
 * Two-finger tap together is Back.
 */
object TouchPadOverlay {
    private const val TAG = "TouchPadOverlay"

    private val visibleInternal = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = visibleInternal.asStateFlow()

    @Volatile
    private var session: Session? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun bind(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        session?.takeIf { it.service !== service }?.dismiss(animate = false)
        session?.takeIf { it.service !== service }?.detachSummonCorner()
        if (session?.service !== service) {
            session = Session(service).also { it.syncSummonCorner() }
        }
    }

    fun unbind(service: AccessibilityService) {
        val current = session ?: return
        if (current.service === service) {
            heldForCover = false
            current.detachSummonCorner()
            current.dismiss(animate = false)
            session = null
        }
    }

    fun isShowing(): Boolean = visibleInternal.value

    fun show(): Boolean {
        if (isCovering()) return false
        heldForCover = false
        val current = session ?: return false
        return current.show()
    }

    /**
     * Hide under AOD, the screen-blank plate, or the Web screensaver.
     * Keep the windows; only the FAB-style yield (INVISIBLE + not touchable).
     * Bring the pad back when that cover lifts, if it was up.
     */
    fun syncForCover() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { syncForCover() }
            return
        }
        if (isCovering()) {
            val current = session ?: return
            if (!current.isShowing) return
            heldForCover = true
            current.yieldToCover()
        } else if (heldForCover) {
            heldForCover = false
            session?.restoreFromCover()
        }
    }

    @Volatile
    private var heldForCover = false

    private fun isCovering(): Boolean =
        OverlayZOrderCoordinator.shouldYieldVoiceToAod() ||
            ScreenBlankOverlay.isShowing() ||
            ScreensaverController.isScreensaverVisible()

    fun syncSummonCorner() {
        session?.syncSummonCorner()
    }

    fun toggle(): Boolean {
        val current = session ?: return false
        return if (current.isShowing && !current.isFadingOut) {
            current.dismiss(animate = true)
            false
        } else {
            current.show()
        }
    }

    fun hide() {
        heldForCover = false
        session?.dismiss(animate = true)
    }

    fun onConfigurationChanged() {
        session?.scheduleRebuild()
    }

    /** Play a saved take without opening the pad window. */
    fun playTakeHeadless(index: Int): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { playTakeHeadless(index) }
            return session != null
        }
        return session?.playTakeHeadless(index) == true
    }

    private class Session(val service: AccessibilityService) {
        private val windowManager =
            service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        private val prefs = TouchPadPrefs(service)
        private val handler = Handler(Looper.getMainLooper())
        private var fadeGeneration = 0
        var isFadingOut = false
            private set

        private var pad: FrameLayout? = null
        private var padParams: WindowManager.LayoutParams? = null
        private var cursor: TouchPadCursorView? = null
        private var cursorParams: WindowManager.LayoutParams? = null
        private var marks: TouchPadAutoMarksView? = null
        private var tip: LinearLayout? = null
        private var surface: TouchPadSurfaceView? = null
        private var pinButton: ImageView? = null
        private var autoButton: ImageView? = null
        private var autoStarHost: FrameLayout? = null
        private var compactDelete: ImageView? = null
        private var compactChrome = false
        private var trashFlight: Animator? = null
        private var autoPanel: TouchPadAutoPanel? = null
        private var autoBadges: TouchPadAutoBadges? = null
        private var autoSlotsOpen = false
        private val auto = TouchPadAuto().also { next ->
            val stored = TouchPadAutoStore.decode(prefs.autoLibrary)
            next.restoreLibrary(stored.first, stored.second)
            next.loop = prefs.autoLoop
        }
        private var preserveAuto = false
        private var playGen = 0
        private val playRunnable = Runnable { advanceAutoPlay() }
        private var lastAutoPage: TouchPadAutoPage? = null
        private var chromeBreathing = false
        private val sceneTick = Runnable { tickAutoScene() }
        private var padAck: AnimatorSet? = null
        private var glideGen = 0
        private var sceneArmed = false
        private var headlessPlay = false
        private var lastPublishedMask = auto.filledMask().joinToString()
        private var closeButton: TouchPadCloseView? = null
        private var titleBar: LinearLayout? = null
        private var divider: View? = null
        private var bodyBackground: GradientDrawable? = null
        private var styleJob: Job? = null
        private val styleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var resizeHandle: TouchPadCornerHandleView? = null
        private val hideHandleRunnable = Runnable {
            resizeHandle?.animate()
                ?.alpha(0f)
                ?.setDuration(180L)
                ?.setInterpolator(AccelerateInterpolator())
                ?.start()
        }
        private val homePrefs =
            service.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        private val darkModeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_DARK_MODE) applyTheme()
        }
        private var listeningDarkMode = false
        private var lastScreenW = 0
        private var lastScreenH = 0
        private var rebuildRetryPending = false
        private val rebuildRunnable = Runnable { rebuildForRotation() }
        private var scrollLive = false
        private var scrollStartX = 0f
        private var scrollStartY = 0f
        private var scrollTargetX = 0f
        private var scrollTargetY = 0f
        private var scrollAccX = 0f
        private var scrollAccY = 0f
        private var scrollNode: AccessibilityNodeInfo? = null
        private var scrollSearched = false
        private var scrollFindAt = 0L
        private var scrollUsedNode = false
        private var scrollUsedPage = false
        private var windowDragging = false
        private var windowLift = false
        private var windowPinch = false
        private var pinchTickPx = 0f
        private var twoFingerSidebar = false
        private var twoFingerAxis = 0
        private var twoFingerAccX = 0f
        private var twoFingerAccY = 0f
        private var twoFingerBeganAt = 0L
        private var twoFingerSurfaceDrag = false
        private var scrollHapticTravel = 0f
        private var scrollHapticStep = 0
        private var lastScrollVx = 0f
        private var lastScrollVy = 0f
        private var lastScrollAt = 0L

        private var cursorX = 0f
        private var cursorY = 0f
        private var swipeStartX = 0f
        private var swipeStartY = 0f
        private var swipeEndX = 0f
        private var swipeEndY = 0f
        private var holdStartX = 0f
        private var holdStartY = 0f
        private var holdEndX = 0f
        private var holdEndY = 0f
        private var holdActive = false
        private var holdMoved = false
        private var holdBeganAt = 0L
        private val holdReleaseRunnable = Runnable {
            dispatchThreeFingerDragEnd(holdStartX, holdStartY)
        }
        private var suppressTapPulse = false
        private var threeFingerDragActive = false
        private var threeFingerDragX = 0f
        private var threeFingerDragY = 0f
        private var threeFingerShadeActive = false
        private var shadeHapticTravel = 0f
        private var shadeHapticStep = 0
        private var shadeCommitPadDy = 0f
        private val shadeCommitRunnable = Runnable { injectCommittedShadePull() }
        private var tipFadeGeneration = 0
        private var cursorRevealed = false
        private var suppressCursorReveal = false
        private val hideCursorRunnable = Runnable { fadeCursorOut() }
        private var summonCorner: TouchPadCornerSummonWindow? = null
        private val idleCloseRunnable = Runnable {
            if (pad == null || isFadingOut || coverYielded) return@Runnable
            // Auto holds the pad: dismissing here would drop the overlay while
            // the user still thinks they are recording or playing.
            if (auto.holdsIdleClose()) return@Runnable
            dismiss(animate = true)
        }

        val isShowing: Boolean get() = pad != null
        private var coverYielded = false

        fun syncSummonCorner() {
            if (prefs.cornerSummonEnabled) {
                attachSummonCorner()
                summonCorner?.setVisible(pad == null)
            } else {
                detachSummonCorner()
            }
        }

        fun attachSummonCorner() {
            if (!prefs.cornerSummonEnabled) {
                detachSummonCorner()
                return
            }
            if (summonCorner != null) return
            summonCorner = TouchPadCornerSummonWindow(service) { show() }
            summonCorner?.attach()
            summonCorner?.setVisible(pad == null)
        }

        fun detachSummonCorner() {
            summonCorner?.detach()
            summonCorner = null
        }

        @SuppressLint("ClickableViewAccessibility")
        fun show(animate: Boolean = true): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
            if (isCovering()) return false
            if (coverYielded) {
                restoreFromCover()
                return pad != null
            }
            if (pad != null) {
                if (isFadingOut) fadeInWindows()
                summonCorner?.setVisible(false)
                return true
            }
            summonCorner?.setVisible(false)
            tearDownImmediate(updateVisible = false)
            LiquidGlass.ensureLoaded(service)
            val metrics = realMetrics()
            lastScreenW = metrics.widthPixels
            lastScreenH = metrics.heightPixels
            cursorX = metrics.widthPixels / 2f
            cursorY = metrics.heightPixels / 2f
            val density = service.resources.displayMetrics.density
            val padW = TouchPadMath.padWidthPx(
                prefs.padWidthDp,
                density,
                metrics.widthPixels,
            )
            val padH = TouchPadMath.padHeightPx(
                prefs.padHeightDp,
                density,
                metrics.heightPixels,
            )
            val chromePx = TouchPadMath.titleChromePx(density)
            val padTopPx = TouchPadMath.titlePadTopPx(density)
            val padBottomPx = TouchPadMath.titlePadBottomPx(density)
            val barPx = TouchPadMath.titleBarPx(density)
            val totalH = padH + barPx
            val context = service
            val root = FrameLayout(context)
            val bodyBg = GradientDrawable().apply {
                cornerRadius = TouchPadMath.dp(14, density).toFloat()
            }
            root.background = bodyBg
            root.elevation = TouchPadMath.dp(8, density).toFloat()
            root.clipToOutline = true
            root.alpha = if (animate) 0f else 1f
            bodyBackground = bodyBg

            val title = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, padTopPx, TouchPadMath.dp(6, density), padBottomPx)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    barPx,
                )
            }
            title.addView(
                View(context),
                LinearLayout.LayoutParams(chromePx * 2, LinearLayout.LayoutParams.MATCH_PARENT),
            )
            title.addView(
                View(context),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            val close = TouchPadCloseView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    TouchPadMath.dp(26, density),
                    TouchPadMath.dp(26, density),
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    OverlayHaptics.click(service, this)
                    dismiss(animate = true)
                }
                setOnTouchListener { view, event ->
                    if (TouchPadPageScroll.isGeneratedGesture(event)) return@setOnTouchListener true
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            suppressCursorReveal = true
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_UP -> {
                            if (view.isEnabled) view.performClick()
                        }
                        MotionEvent.ACTION_CANCEL ->
                            if (!isFadingOut) suppressCursorReveal = false
                    }
                    true
                }
            }
            title.addView(close)
            closeButton = close
            titleBar = title
            root.addView(title)

            val padView = TouchPadSurfaceView(
                context = context,
                doubleTapTimeoutMs = prefs.doubleTapTimeoutMs,
                holdDelayMs = prefs.holdDelayMs,
                listener = surfaceListener(),
            )
            padView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                padH,
            ).apply { topMargin = barPx }
            root.addView(padView)
            surface = padView

            val dividerView = View(context).apply {
                background = GradientDrawable()
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    TouchPadMath.dp(1, density),
                ).apply { topMargin = barPx }
            }
            root.addView(dividerView)
            divider = dividerView

            val panel = TouchPadAutoPanel(context).apply {
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    padH,
                ).apply { topMargin = barPx }
                setListener(autoPanelListener())
            }
            root.addView(panel)
            autoPanel = panel

            val pin = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(chromePx, chromePx).apply {
                    gravity = Gravity.TOP or Gravity.START
                    topMargin = padTopPx
                }
                val iconPad = TouchPadMath.dp(TouchPadMath.TITLE_ICON_PAD_DP, density)
                setPadding(iconPad, iconPad, iconPad, iconPad)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                setOnClickListener {
                    OverlayHaptics.click(service, this)
                    setPinned(!prefs.pinned)
                    bounceChrome(this)
                    scheduleIdleClose()
                }
            }
            root.addView(pin)
            pinButton = pin
            refreshPinIcon()

            val starHost = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(chromePx, chromePx).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = chromePx
                    topMargin = padTopPx
                }
                clipChildren = false
                clipToPadding = false
            }
            val autoView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                val iconPad = TouchPadMath.dp(TouchPadMath.TITLE_ICON_PAD_DP, density)
                setPadding(iconPad, iconPad, iconPad, iconPad)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                setImageResource(R.drawable.ic_touch_pad_auto)
                setOnClickListener {
                    OverlayHaptics.click(service, this)
                    bounceChrome(this)
                    onAutoChromeClicked()
                }
            }
            starHost.addView(autoView)
            root.addView(starHost)
            autoStarHost = starHost
            autoButton = autoView
            refreshAutoIcon()

            val compactTrash = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(chromePx, chromePx).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = chromePx
                    topMargin = padTopPx
                }
                val iconPad = TouchPadMath.dp(TouchPadMath.TITLE_ICON_PAD_DP, density)
                setPadding(iconPad, iconPad, iconPad, iconPad)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageResource(R.drawable.ic_touch_pad_auto_delete)
                visibility = View.INVISIBLE
                alpha = 0f
                isClickable = false
                contentDescription = context.getString(R.string.touch_pad_auto_delete)
                setOnClickListener {
                    if (!compactChrome) return@setOnClickListener
                    OverlayHaptics.click(service, this)
                    bounceChrome(this)
                    deleteTake(auto.selected)
                }
            }
            root.addView(compactTrash)
            compactDelete = compactTrash

            val badges = TouchPadAutoBadges(context).apply {
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    TouchPadMath.autoSlotStripPx(padW, chromePx, density),
                    chromePx,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = chromePx * 2 + TouchPadMath.dp(TouchPadMath.AUTO_SLOT_STAR_GAP_DP, density)
                    topMargin = padTopPx
                }
                setListener(autoBadgeListener())
                attachStar(starHost)
            }
            root.addView(badges)
            autoBadges = badges

            val handleSize = TouchPadMath.dp(TouchPadMath.HANDLE_SIZE_DP, density)
            val handle = TouchPadCornerHandleView(context).apply {
                alpha = 0f
                layoutParams = FrameLayout.LayoutParams(handleSize, handleSize).apply {
                    gravity = Gravity.END or Gravity.BOTTOM
                }
            }
            root.addView(handle)
            resizeHandle = handle
            // Title (and the X) must sit above the pad face. Badges stay
            // above the title drag strip so 1–5 still scroll; the strip
            // stops short of the X so it cannot paint over the close.
            title.bringToFront()
            pin.bringToFront()
            starHost.bringToFront()
            badges.bringToFront()
            compactTrash.bringToFront()
            handle.bringToFront()

            val params = WindowManager.LayoutParams(
                padW,
                totalH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                OverlayOrientation.apply(this)
            }
            placePad(params, metrics, padW, totalH, density)
            placeCursorAbovePad(metrics, params, padW, density)
            attachTitleDrag(title, root, params)
            attachResize(handle, root, params)
            applyTheme(root)

            try {
                windowManager.addView(root, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create touch pad overlay", e)
                preserveAuto = false
                visibleInternal.value = false
                return false
            }
            pad = root
            padParams = params
            listenDarkMode(true)
            listenStyle(true)
            root.post {
                restoreOrClamp(root, params, metrics)
                placeCursorAbovePad(metrics, params, params.width, density)
                updateCursorWindow()
            }
            addMarks(metrics)
            addCursor(density, animate)
            maybeShowTip(metrics, density, params.y, animate)
            applyAutoChrome()
            preserveAuto = false
            if (animate) fadeInWindows() else {
                fadeGeneration++
                isFadingOut = false
                visibleInternal.value = true
                scheduleIdleClose()
            }
            return true
        }

        fun scheduleRebuild() {
            handler.removeCallbacks(rebuildRunnable)
            handler.post(rebuildRunnable)
        }

        fun yieldToCover() {
            if (coverYielded) return
            val root = pad ?: return
            val params = padParams ?: return
            coverYielded = true
            handler.removeCallbacks(idleCloseRunnable)
            overlayWindows().forEach { view ->
                view.animate().cancel()
                view.animate().setListener(null)
                view.visibility = View.INVISIBLE
            }
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (root.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(root, params) }
            }
        }

        fun restoreFromCover() {
            if (!coverYielded) return
            if (isCovering()) return
            coverYielded = false
            val root = pad ?: return
            val params = padParams ?: return
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            root.visibility = View.VISIBLE
            root.alpha = 1f
            tip?.let { view ->
                view.visibility = View.VISIBLE
                view.alpha = 1f
            }
            if (root.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(root, params) }
            }
            scheduleIdleClose()
        }

        private fun rebuildForRotation() {
            if (pad == null || isFadingOut || coverYielded) return
            val metrics = realMetrics()
            if (metrics.widthPixels == lastScreenW && metrics.heightPixels == lastScreenH) {
                if (!rebuildRetryPending) {
                    rebuildRetryPending = true
                    handler.postDelayed(rebuildRunnable, 64L)
                }
                return
            }
            rebuildRetryPending = false
            lastScreenW = metrics.widthPixels
            lastScreenH = metrics.heightPixels
            preserveAuto = true
            tearDownImmediate(updateVisible = false)
            show(animate = false)
        }

        fun dismiss(animate: Boolean = true) {
            if (isFadingOut) return
            heldForCover = false
            coverYielded = false
            isFadingOut = true
            suppressCursorReveal = true
            val fadeCursor = cursorRevealed
            if (!fadeCursor) hideCursorImmediate()
            stopAutoForDismiss()
            if (pad == null && cursor == null && tip == null && marks == null) {
                tearDownImmediate()
                return
            }
            if (!animate) {
                tearDownImmediate()
                return
            }
            fadeOutWindows(fadeCursor)
        }

        private fun stopAutoForDismiss() {
            abortPlayClock()
            watchAutoScene(false)
            if (auto.page == TouchPadAutoPage.RECORD) finishAutoRecord()
            auto.haltPlay()
        }

        private fun fadeInWindows() {
            isFadingOut = false
            suppressCursorReveal = false
            fadeGeneration++
            visibleInternal.value = true
            scheduleIdleClose()
            listOfNotNull(pad, tip).forEach { view ->
                view.animate().cancel()
                view.animate().setListener(null)
                view.visibility = View.VISIBLE
                view.animate()
                    .alpha(1f)
                    .setDuration(TouchPadMath.FADE_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        private fun fadeOutWindows(fadeCursor: Boolean = cursorRevealed) {
            isFadingOut = true
            val gen = ++fadeGeneration
            padAck?.cancel()
            padAck = null
            handler.removeCallbacks(hideCursorRunnable)
            handler.removeCallbacks(idleCloseRunnable)
            cursorRevealed = false
            finishHoldIfNeeded()
            threeFingerShadeActive = false
            handler.removeCallbacks(shadeCommitRunnable)
            setCursorMode(TouchPadCursorMode.NONE)
            marks?.clear()
            val host = pad ?: cursor ?: tip
            val begin = Runnable {
                if (gen != fadeGeneration || !isFadingOut) return@Runnable
                startFadeOut(gen, fadeCursor)
            }
            if (host != null && host.isAttachedToWindow) {
                host.post(begin)
            } else {
                handler.post(begin)
            }
        }

        private fun startFadeOut(gen: Int, fadeCursor: Boolean) {
            if (gen != fadeGeneration || !isFadingOut) return
            val chrome = listOfNotNull(pad, tip).filter { it.isAttachedToWindow }
            val closeChrome = {
                fadeViews(chrome, gen, TouchPadMath.FADE_OUT_MS) {
                    handler.post {
                        if (gen != fadeGeneration || !isFadingOut) return@post
                        tearDownImmediate()
                    }
                }
            }
            val cursorView = cursor?.takeIf {
                fadeCursor && it.isAttachedToWindow && it.alpha > 0.02f
            }
            if (cursorView == null) {
                hideCursorImmediate()
                closeChrome()
                return
            }
            fadeViews(listOf(cursorView), gen, TouchPadMath.FADE_OUT_MS) {
                cursorView.visibility = View.INVISIBLE
                cursorView.alpha = 0f
                handler.postDelayed({
                    if (gen != fadeGeneration || !isFadingOut) return@postDelayed
                    closeChrome()
                }, TouchPadMath.FADE_OUT_SETTLE_MS)
            }
        }

        private fun fadeViews(
            views: List<View>,
            gen: Int,
            durationMs: Long,
            then: () -> Unit,
        ) {
            val live = views.filter { it.isAttachedToWindow }
            if (live.isEmpty()) {
                then()
                return
            }
            val startedAt = SystemClock.uptimeMillis()
            var remaining = live.size
            val finish = {
                val left = (durationMs - (SystemClock.uptimeMillis() - startedAt))
                    .coerceAtLeast(0L)
                handler.postDelayed({
                    if (gen != fadeGeneration || !isFadingOut) return@postDelayed
                    then()
                }, left)
            }
            live.forEach { view ->
                val from = view.alpha.coerceIn(0f, 1f)
                view.animate().cancel()
                view.animate().setListener(null)
                view.alpha = from
                view.animate()
                    .withLayer()
                    .alpha(0f)
                    .setDuration(durationMs)
                    .setInterpolator(AccelerateInterpolator())
                    .setListener(object : AnimatorListenerAdapter() {
                        private var canceled = false
                        override fun onAnimationCancel(animation: Animator) {
                            canceled = true
                        }
                        override fun onAnimationEnd(animation: Animator) {
                            view.animate().setListener(null)
                            if (canceled || gen != fadeGeneration || !isFadingOut) return
                            remaining--
                            if (remaining > 0) return
                            finish()
                        }
                    })
                    .start()
            }
        }

        private fun overlayWindows(): List<View> =
            listOfNotNull(pad, marks, cursor, tip)

        private fun tearDownImmediate(updateVisible: Boolean = true) {
            handler.removeCallbacks(rebuildRunnable)
            rebuildRetryPending = false
            fadeGeneration++
            coverYielded = false
            isFadingOut = false
            padAck?.cancel()
            padAck = null
            pad?.let { view ->
                view.scaleX = 1f
                view.scaleY = 1f
                view.clipToOutline = true
            }
            listenDarkMode(false)
            listenStyle(false)
            handler.removeCallbacks(hideHandleRunnable)
            handler.removeCallbacks(hideCursorRunnable)
            handler.removeCallbacks(idleCloseRunnable)
            handler.removeCallbacks(holdReleaseRunnable)
            handler.removeCallbacks(playRunnable)
            chromeBreathing = false
            handler.removeCallbacks(sceneTick)
            AvaAutoKeys.sink = null
            AvaAutoKeys.textSink = null
            autoSlotsOpen = false
            autoButton?.animate()?.cancel()
            autoButton?.rotation = 0f
            autoButton?.alpha = 1f
            autoStarHost?.animate()?.cancel()
            autoStarHost?.translationX = 0f
            compactDelete?.animate()?.cancel()
            abortTrashFlight()
            compactDelete?.visibility = View.GONE
            compactDelete?.alpha = 0f
            compactDelete?.translationX = 0f
            compactDelete?.translationY = 0f
            compactDelete?.scaleX = 1f
            compactDelete?.scaleY = 1f
            compactDelete?.rotation = 0f
            pinButton?.animate()?.cancel()
            pinButton?.alpha = 1f
            pinButton?.visibility = View.VISIBLE
            compactChrome = false
            haltAuto(reset = !preserveAuto)
            if (!preserveAuto) lastAutoPage = null
            cursorRevealed = false
            suppressCursorReveal = false
            overlayWindows().forEach { view ->
                view.animate().cancel()
                view.animate().setListener(null)
            }
            resizeHandle?.animate()?.cancel()
            if (twoFingerSidebar) {
                SidebarDrawerRemote.endEdgeSwipe(commit = false)
            }
            if (twoFingerSurfaceDrag) {
                TouchPadPageScroll.endContentDrag()
            }
            twoFingerSidebar = false
            twoFingerAxis = 0
            twoFingerAccX = 0f
            twoFingerAccY = 0f
            twoFingerBeganAt = 0L
            twoFingerSurfaceDrag = false
            if (windowPinch) {
                AppWindowService.endPadPinch(commit = false)
            }
            if (windowDragging || windowLift) {
                AppWindowService.endPadDrag(commit = false)
                WebViewService.endPadLift(commit = false)
                AiBrowserService.endPadLift(commit = false)
            }
            windowDragging = false
            windowLift = false
            windowPinch = false
            pinchTickPx = 0f
            TouchPadPageScroll.end()
            finishHoldIfNeeded()
            threeFingerShadeActive = false
            handler.removeCallbacks(shadeCommitRunnable)
            setCursorMode(TouchPadCursorMode.NONE)
            removeTip(markSeen = false)
            pad?.let { runCatching { windowManager.removeView(it) } }
            pad = null
            padParams = null
            surface = null
            pinButton = null
            autoButton = null
            autoStarHost = null
            compactDelete = null
            autoBadges = null
            autoPanel = null
            closeButton = null
            titleBar = null
            divider = null
            bodyBackground = null
            resizeHandle = null
            marks?.let { runCatching { windowManager.removeView(it) } }
            marks = null
            cursor?.let { runCatching { windowManager.removeView(it) } }
            cursor = null
            cursorParams = null
            holdActive = false
            suppressTapPulse = false
            resetScrollQueue()
            if (updateVisible) {
                visibleInternal.value = false
                if (prefs.cornerSummonEnabled) summonCorner?.setVisible(true)
            }
        }

        private fun surfaceListener() = object : TouchPadSurfaceView.Listener {
            override fun onPadTouched() {
                if (!allowPadCursor()) return
                scheduleIdleClose()
                revealCursorNow()
            }

            override fun onCursorBegan() {
            }

            override fun onCursorDelta(dx: Float, dy: Float) {
                moveCursor(realMetrics(), dx, dy)
            }

            override fun onTap() {
                if (!allowPadCursor()) return
                if (!suppressTapPulse) cursor?.playTapPulse()
                suppressTapPulse = false
                dispatchTap(cursorX, cursorY)
            }

            override fun onDoubleTap() {
                suppressTapPulse = true
                cursor?.playDoubleTapPulse()
            }

            override fun onHoldBegan() {
                holdStartX = cursorX
                holdStartY = cursorY
                holdEndX = cursorX
                holdEndY = cursorY
                holdMoved = false
                holdBeganAt = SystemClock.uptimeMillis()
                handler.removeCallbacks(holdReleaseRunnable)
                // A normal long-press belongs to the target under the cursor.
                // Moving the Ava overlay is reserved for its explicit window handle.
                windowDragging = false
                holdActive = true
                setCursorMode(TouchPadCursorMode.HOLD)
                OverlayHaptics.heavy(service, surface)
                dispatchThreeFingerDragStart(cursorX, cursorY)
            }

            override fun onHoldDelta(dx: Float, dy: Float) {
                val moved = moveCursor(realMetrics(), dx, dy, stable = true)
                if (windowDragging) {
                    AppWindowService.nudgePadDrag(moved.x, moved.y)
                    return
                }
                if (!holdActive) return
                holdEndX = cursorX
                holdEndY = cursorY
                val density = service.resources.displayMetrics.density
                if (!holdMoved) {
                    val follow = hypot(
                        (holdEndX - holdStartX).toDouble(),
                        (holdEndY - holdStartY).toDouble(),
                    ).toFloat()
                    holdMoved = follow >= TouchPadMath.dp(TouchPadMath.HOLD_FOLLOW_MIN_DP, density)
                }
                dispatchThreeFingerDragMove(cursorX, cursorY)
            }

            override fun onHoldEnded() {
                if (windowDragging) {
                    AppWindowService.endPadDrag(commit = true)
                    windowDragging = false
                    setCursorMode(TouchPadCursorMode.NONE)
                    return
                }
                finishHoldIfNeeded(immediate = false)
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onHoldCancelled() {
                if (windowDragging) {
                    AppWindowService.endPadDrag(commit = false)
                    windowDragging = false
                }
                finishHoldIfNeeded(immediate = true)
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onSwipeBegan() {
                swipeStartX = cursorX
                swipeStartY = cursorY
                swipeEndX = cursorX
                swipeEndY = cursorY
                setCursorMode(TouchPadCursorMode.SLIDE)
            }

            override fun onSwipeDelta(dx: Float, dy: Float) {
                val metrics = realMetrics()
                val scale = TouchPadMath.sensitivityScale(prefs.cursorSensitivity)
                swipeEndX = TouchPadMath.move(swipeEndX, dx, scale, 0f, metrics.widthPixels.toFloat())
                swipeEndY = TouchPadMath.move(swipeEndY, dy, scale, 0f, metrics.heightPixels.toFloat())
                if (auto.page == TouchPadAutoPage.RECORD) {
                    pinCursorTo(swipeEndX, swipeEndY)
                }
            }

            override fun onSwipeEnded() {
                val density = service.resources.displayMetrics.density
                val dist = hypot(
                    (swipeEndX - swipeStartX).toDouble(),
                    (swipeEndY - swipeStartY).toDouble(),
                ).toFloat()
                val min = TouchPadMath.dp(TouchPadMath.SLIDE_MIN_DP, density)
                when {
                    dist >= min -> dispatchSlide(
                        swipeStartX,
                        swipeStartY,
                        swipeEndX,
                        swipeEndY,
                        prefs.slideDurationMs,
                    )
                    auto.page == TouchPadAutoPage.RECORD &&
                        dist > TouchPadMath.TAP_SLOP_PX ->
                        noteAutoSlide(
                            swipeStartX,
                            swipeStartY,
                            swipeEndX,
                            swipeEndY,
                            prefs.slideDurationMs,
                        )
                }
                // A failed drag is a cancelled drag; never turn it into a click.
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onSwipeCancelled() {
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onTwoFingerBegan() {
                scrollUsedPage = false
                twoFingerSidebar = false
                twoFingerAxis = 0
                twoFingerAccX = 0f
                twoFingerAccY = 0f
                twoFingerBeganAt = SystemClock.uptimeMillis()
                twoFingerSurfaceDrag = false
                resetScrollHaptics()
                TouchPadPageScroll.begin(cursorX, cursorY)
                if (!TouchPadPageScroll.hitsInjectedOverlay(cursorX, cursorY)) {
                    bindScrollNode()
                }
            }

            override fun onSecondaryTap() {
                OverlayHaptics.click(service, surface)
                noteAutoSecondary(cursorX, cursorY)
                dispatchSecondaryTap(cursorX, cursorY)
            }

            override fun onTwoFingerBack() {
                OverlayHaptics.click(service, surface)
                noteAutoBack(cursorX, cursorY)
                dispatchBack()
            }

            override fun onTwoFingerScroll(dx: Float, dy: Float) {
                twoFingerAccX += dx
                twoFingerAccY += dy
                if (twoFingerAxis == 0) {
                    if (TouchPadMath.hypot(twoFingerAccX, twoFingerAccY) <= TouchPadMath.TAP_SLOP_PX) {
                        surface?.showScrollHint(dx, dy)
                        return
                    }
                    twoFingerAxis = if (TouchPadMath.twoFingerPrefersHorizontal(twoFingerAccX, twoFingerAccY)) {
                        1
                    } else {
                        2
                    }
                    if (twoFingerAxis == 1 && twoFingerBindsAvaSidebar()) {
                        twoFingerSidebar = SidebarDrawerRemote.beginEdgeSwipe()
                        if (twoFingerSidebar) {
                            playScrollPressHaptic()
                            val scale = TouchPadMath.sensitivityScale(prefs.cursorSensitivity)
                            SidebarDrawerRemote.nudgeEdgeSwipe(twoFingerAccX * scale)
                            surface?.showScrollHint(dx, dy)
                            return
                        }
                    }
                    twoFingerAxis = 2
                    playScrollPressHaptic()
                    if (!twoFingerBindsAvaSidebar()) {
                        val scale = TouchPadMath.sensitivityScale(prefs.cursorSensitivity)
                        twoFingerSurfaceDrag = TouchPadPageScroll.beginContentDrag(cursorX, cursorY)
                        if (twoFingerSurfaceDrag) {
                            TouchPadPageScroll.nudgeContentDrag(
                                cursorX + twoFingerAccX * scale,
                                cursorY + twoFingerAccY * scale,
                            )
                        }
                        noteScrollSlideHaptic(TouchPadMath.hypot(dx, dy) * scale)
                        surface?.showScrollHint(dx, dy)
                        return
                    }
                }
                if (twoFingerSidebar) {
                    val scale = TouchPadMath.sensitivityScale(prefs.cursorSensitivity)
                    SidebarDrawerRemote.nudgeEdgeSwipe(dx * scale)
                    noteScrollSlideHaptic(abs(dx * scale))
                    surface?.showScrollHint(dx, dy)
                    return
                }
                if (!twoFingerBindsAvaSidebar()) {
                    val scale = TouchPadMath.sensitivityScale(prefs.cursorSensitivity)
                    if (twoFingerSurfaceDrag) {
                        TouchPadPageScroll.nudgeContentDrag(
                            cursorX + twoFingerAccX * scale,
                            cursorY + twoFingerAccY * scale,
                        )
                    }
                    noteScrollSlideHaptic(TouchPadMath.hypot(dx, dy) * scale)
                    surface?.showScrollHint(dx, dy)
                    return
                }
                val scale = TouchPadMath.scrollScale(prefs.cursorSensitivity)
                enqueueScroll(dx * scale, dy * scale)
                noteScrollSlideHaptic(TouchPadMath.hypot(dx, dy) * scale)
                surface?.showScrollHint(dx, dy)
            }

            override fun onTwoFingerEnded() {
                if (twoFingerSidebar) {
                    val scale = TouchPadMath.sensitivityScale(prefs.cursorSensitivity)
                    val dx = twoFingerAccX * scale
                    val held = (SystemClock.uptimeMillis() - twoFingerBeganAt).coerceAtLeast(80L)
                    noteAutoSidebar(cursorX, cursorY, dx, held)
                    SidebarDrawerRemote.endEdgeSwipe(commit = true)
                    twoFingerSidebar = false
                    twoFingerAxis = 0
                    TouchPadPageScroll.end()
                    resetScrollQueue()
                    resetScrollHaptics()
                    setCursorMode(TouchPadCursorMode.NONE)
                    return
                }
                val scale = if (twoFingerBindsAvaSidebar()) {
                    TouchPadMath.scrollScale(prefs.cursorSensitivity)
                } else {
                    TouchPadMath.sensitivityScale(prefs.cursorSensitivity)
                }
                val dx = twoFingerAccX * scale
                val dy = twoFingerAccY * scale
                val held = (SystemClock.uptimeMillis() - twoFingerBeganAt).coerceAtLeast(80L)
                if (twoFingerSurfaceDrag) {
                    noteAutoSlide(cursorX, cursorY, cursorX + dx, cursorY + dy, held)
                    TouchPadPageScroll.endContentDrag()
                    twoFingerSurfaceDrag = false
                    twoFingerAxis = 0
                    TouchPadPageScroll.end()
                    resetScrollQueue()
                    resetScrollHaptics()
                    setCursorMode(TouchPadCursorMode.NONE)
                    return
                }
                if (twoFingerAxis == 2) {
                    if (twoFingerBindsAvaSidebar()) {
                        noteAutoScroll(cursorX, cursorY, dx, dy)
                    } else {
                        val x1 = cursorX
                        val y1 = cursorY
                        val x2 = cursorX + dx
                        val y2 = cursorY + dy
                        val travel = held.coerceIn(
                            TouchPadMath.SCREEN_SWIPE_MIN_MS,
                            TouchPadAuto.MAX_SWIPE_MS,
                        )
                        noteAutoSlide(x1, y1, x2, y2, travel)
                        handler.postDelayed({
                            injectScreenSwipe(
                                x1,
                                y1,
                                x2,
                                y2,
                                TouchPadMath.screenSwipeDuration(held),
                                null,
                            )
                        }, TouchPadMath.SCREEN_SWIPE_SETTLE_MS)
                    }
                }
                TouchPadPageScroll.end()
                finishScrollQueue()
                resetScrollHaptics()
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onTwoFingerCancelled() {
                if (twoFingerSidebar) {
                    SidebarDrawerRemote.endEdgeSwipe(commit = false)
                    twoFingerSidebar = false
                }
                if (twoFingerSurfaceDrag) {
                    TouchPadPageScroll.endContentDrag()
                    twoFingerSurfaceDrag = false
                }
                twoFingerAxis = 0
                TouchPadPageScroll.end()
                resetScrollQueue()
                resetScrollHaptics()
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onWindowLiftBegan(): Boolean {
                windowLift = AppWindowService.beginPadDrag(
                    cursorX,
                    cursorY,
                    snapUnlessTop = true,
                )
                if (!windowLift) {
                    windowLift = WebViewService.beginPadLift() ||
                        AiBrowserService.beginPadLift()
                }
                if (windowLift) {
                    OverlayHaptics.heavy(service, surface)
                }
                return windowLift
            }

            override fun onWindowLiftDelta(dx: Float, dy: Float) {
                val moved = moveCursor(realMetrics(), dx, dy)
                if (AppWindowService.nudgePadDrag(moved.x, moved.y)) return
                WebViewService.nudgePadLift(moved.y)
                AiBrowserService.nudgePadLift(moved.y)
            }

            override fun onWindowLiftEnded() {
                if (!windowLift) return
                windowLift = false
                if (!AppWindowService.endPadDrag(commit = true)) {
                    WebViewService.endPadLift(commit = true)
                    AiBrowserService.endPadLift(commit = true)
                }
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onWindowLiftCancelled() {
                if (!windowLift) return
                windowLift = false
                AppWindowService.endPadDrag(commit = false)
                WebViewService.endPadLift(commit = false)
                AiBrowserService.endPadLift(commit = false)
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onWindowPinchBegan(): Boolean {
                windowPinch = AppWindowService.beginPadPinch(cursorX, cursorY)
                if (windowPinch) {
                    val frame = AppWindowService.padWindowFrame()
                    pinchTickPx = if (frame != null) {
                        hypot(frame.width.toFloat(), frame.height.toFloat())
                    } else {
                        0f
                    }
                    OverlayHaptics.heavy(service, surface)
                }
                return windowPinch
            }

            override fun onWindowPinchScale(scale: Float) {
                if (!windowPinch) return
                if (!AppWindowService.applyPadPinch(scale)) return
                val frame = AppWindowService.padWindowFrame() ?: return
                val next = hypot(frame.width.toFloat(), frame.height.toFloat())
                val step = TouchPadMath.dp(
                    TouchPadMath.RESIZE_TICK_DP,
                    service.resources.displayMetrics.density,
                )
                if (TouchPadMath.travelTickChanged(pinchTickPx, next, step)) {
                    OverlayHaptics.tick(service, surface)
                }
                pinchTickPx = next
            }

            override fun onWindowPinchEnded() {
                if (!windowPinch) return
                windowPinch = false
                AppWindowService.endPadPinch(commit = true)
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onWindowPinchCancelled() {
                if (!windowPinch) return
                windowPinch = false
                AppWindowService.endPadPinch(commit = false)
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onThreeFingerRecents() {
                OverlayHaptics.click(service, surface)
                noteAutoRecents(cursorX, cursorY)
                AccessibilityBridge.recents()
            }

            override fun onThreeFingerShadeArmed() {
                threeFingerShadeActive = true
                shadeHapticTravel = 0f
                shadeHapticStep = 0
                OverlayHaptics.heavy(service, surface)
            }

            override fun onThreeFingerShadeDelta(dx: Float, dy: Float) {
                if (!threeFingerShadeActive) return
                noteShadeSlideHaptic(TouchPadMath.hypot(dx, dy))
            }

            override fun onThreeFingerNotifications(dx: Float, dy: Float) {
                threeFingerShadeActive = false
                OverlayHaptics.click(service, surface)
                commitShadeFromPad(dy)
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onThreeFingerShadeCancelled() {
                threeFingerShadeActive = false
                handler.removeCallbacks(shadeCommitRunnable)
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onThreeFingerDragBegan() {
                threeFingerDragActive = true
                threeFingerDragX = cursorX
                threeFingerDragY = cursorY
                setCursorMode(TouchPadCursorMode.HOLD)
                OverlayHaptics.heavy(service, surface)
                dispatchThreeFingerDragStart(cursorX, cursorY)
            }

            override fun onThreeFingerDragDelta(dx: Float, dy: Float) {
                if (!threeFingerDragActive) return
                moveCursor(realMetrics(), dx, dy, stable = true)
                dispatchThreeFingerDragMove(cursorX, cursorY)
            }

            override fun onThreeFingerDragEnded() {
                if (!threeFingerDragActive) return
                noteAutoPath(threeFingerDragX, threeFingerDragY, cursorX, cursorY)
                threeFingerDragActive = false
                dispatchThreeFingerDragEnd(cursorX, cursorY)
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onThreeFingerDragCancelled() {
                if (!threeFingerDragActive) return
                threeFingerDragActive = false
                dispatchThreeFingerDragEnd(threeFingerDragX, threeFingerDragY)
                setCursorMode(TouchPadCursorMode.NONE)
            }

            override fun onInteracted() {
                if (!allowPadCursor()) return
                scheduleIdleClose()
                revealCursorNow()
            }
        }

        private fun applyStoredSize(metrics: DisplayMetrics, root: FrameLayout) {
            val density = service.resources.displayMetrics.density
            val params = padParams ?: return
            val barPx = TouchPadMath.titleBarPx(density)
            val padW = TouchPadMath.padWidthPx(
                prefs.padWidthDp,
                density,
                metrics.widthPixels,
            )
            val padH = TouchPadMath.padHeightPx(
                prefs.padHeightDp,
                density,
                metrics.heightPixels,
            )
            params.width = padW
            params.height = padH + barPx
            clampPad(params, params.width, params.height, density)
            syncBodySize(padH, barPx)
            runCatching { windowManager.updateViewLayout(root, params) }
            savePadNorm(params)
            autoBadges?.setPadWidth(params.width, TouchPadMath.titleChromePx(density))
            if (isCompactSlotChrome() != compactChrome) syncAutoBadges()
        }

        private fun setPinned(value: Boolean) {
            prefs.pinned = value
            refreshPinIcon()
        }

        private fun refreshPinIcon() {
            val pinned = prefs.pinned
            pinButton?.setImageResource(
                if (pinned) R.drawable.ic_appwin_pin_filled else R.drawable.ic_appwin_pin,
            )
            pinButton?.contentDescription = service.getString(
                if (pinned) R.string.touch_pad_unpin else R.string.touch_pad_pin,
            )
        }

        private fun refreshAutoIcon() {
            val button = autoButton ?: return
            val recording = auto.page == TouchPadAutoPage.RECORD
            val density = service.resources.displayMetrics.density
            if (recording) {
                button.setImageResource(R.drawable.ic_touch_pad_auto_pause)
                button.setColorFilter(TouchPadTheme.RECORD_ICON)
                button.background = recordPausePlate(density)
                val pad = TouchPadMath.dp(TouchPadMath.TITLE_ICON_PAD_DP, density)
                button.setPadding(pad, pad, pad, pad)
            } else {
                button.setImageResource(R.drawable.ic_touch_pad_auto)
                button.background = null
                button.setColorFilter(autoIconColor())
                val pad = TouchPadMath.dp(TouchPadMath.TITLE_ICON_PAD_DP, density)
                button.setPadding(pad, pad, pad, pad)
            }
            button.contentDescription = service.getString(
                when {
                    recording -> R.string.touch_pad_auto_stop_record
                    auto.open -> R.string.touch_pad_auto_exit
                    else -> R.string.touch_pad_auto
                },
            )
        }

        private fun autoIconColor(): Int {
            val dark = DarkModeManager.getInstance(service).isDarkMode()
            return TouchPadTheme.palette(dark).icon
        }

        private fun recordPausePlate(density: Float): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TouchPadMath.dp(6, density).toFloat()
                setColor(TouchPadTheme.RECORD_FILL)
            }

        private fun applyFaceMetrics() {
            val density = service.resources.displayMetrics.density
            val barPx = TouchPadMath.titleBarPx(density)
            val width = padParams?.width
                ?: TouchPadMath.dp(TouchPadMath.DEFAULT_SIZE_DP, density)
            val height = ((padParams?.height ?: 0) - barPx).coerceAtLeast(1)
            val widthDp = width / density
            val heightDp = height / density
            val iconDp = TouchPadMath.autoFaceIconDp(minOf(widthDp, heightDp))
            autoPanel?.setFaceMetrics(
                iconDp,
                TouchPadMath.autoFaceTextSp(iconDp),
                widthDp,
                heightDp,
            )
        }

        private fun syncBodySize(padH: Int, titlePx: Int) {
            val apply = { view: View? ->
                view?.layoutParams = (view.layoutParams as? FrameLayout.LayoutParams)?.apply {
                    height = padH
                    topMargin = titlePx
                }
            }
            apply(surface)
            apply(autoPanel)
            applyFaceMetrics()
        }

        private fun autoPanelListener() = object : TouchPadAutoPanel.Listener {
            override fun onRecord() {
                if (!auto.beginRecord()) return
                persistAutoTakes()
                bindAutoScene()
                OverlayHaptics.click(service, autoPanel)
                applyAutoChrome()
                ackPad()
            }

            override fun onPlay() {
                if (!auto.beginPlay()) return
                OverlayHaptics.click(service, autoPanel)
                startAutoPlay()
                ackPad()
            }

            override fun onPause() {
                if (!auto.pausePlay()) return
                OverlayHaptics.click(service, autoPanel)
                abortPlayClock()
                applyAutoChrome()
                ackPad()
            }

            override fun onResume() {
                if (!auto.resumePlay()) return
                OverlayHaptics.click(service, autoPanel)
                startAutoPlay()
                ackPad()
            }

            override fun onReplay() {
                if (!auto.replayPlay()) return
                OverlayHaptics.click(service, autoPanel)
                startAutoPlay()
                ackPad()
            }

            override fun onEnd() {
                OverlayHaptics.click(service, autoPanel)
                haltAuto(reset = false)
                applyAutoChrome()
                ackPad()
            }

            override fun onLoop() {
                OverlayHaptics.click(service, autoPanel)
                toggleAutoLoop()
                autoPanel?.setLoop(auto.loop)
                scheduleIdleClose()
            }
        }

        private fun onAutoChromeClicked() {
            if (autoSlotsOpen && auto.page != TouchPadAutoPage.RECORD) {
                autoSlotsOpen = false
                OverlayHaptics.click(service, autoButton)
                syncAutoBadges()
                scheduleIdleClose()
                return
            }
            when {
                !auto.open -> {
                    auto.open()
                    applyAutoChrome()
                    ackPad()
                }
                auto.page == TouchPadAutoPage.RECORD -> {
                    finishAutoRecord()
                    applyAutoChrome()
                    ackPad()
                }
                else -> {
                    haltAuto(reset = true)
                    applyAutoChrome()
                    ackPad()
                }
            }
            scheduleIdleClose()
        }

        private fun applyAutoChrome() {
            if (headlessPlay) return
            val recording = auto.open && auto.page == TouchPadAutoPage.RECORD
            val showPad = !auto.open || recording
            val animate = lastAutoPage != null && lastAutoPage != auto.page
            lastAutoPage = if (auto.open) auto.page else null
            surface?.visibility = if (showPad) View.VISIBLE else View.GONE
            autoPanel?.let { panel ->
                if (!auto.open || recording) {
                    panel.animate().cancel()
                    panel.visibility = View.GONE
                    panel.alpha = 1f
                } else {
                    val dark = DarkModeManager.getInstance(service).isDarkMode()
                    val palette = TouchPadTheme.palette(dark)
                    panel.setColors(palette.icon, palette.divider)
                    applyFaceMetrics()
                    panel.visibility = View.VISIBLE
                    panel.show(auto.page, auto.hasTake(), auto.loop, animate)
                }
            }
            refreshAutoIcon()
            syncAutoBadges()
            syncAutoCursor()
            syncAutoMarks()
            syncChromeRecord()
            if (holdsCursorVisible()) revealCursorNow() else if (!auto.open) cursor?.clearTrail()
        }

        private fun syncAutoCursor() {
            val next = when {
                !auto.open -> TouchPadCursorMode.NONE
                auto.page == TouchPadAutoPage.PAUSED -> TouchPadCursorMode.AUTO_PAUSE
                auto.page == TouchPadAutoPage.PLAY ||
                    auto.page == TouchPadAutoPage.RECORD -> TouchPadCursorMode.AUTO_PLAY
                else -> TouchPadCursorMode.NONE
            }
            setCursorMode(next)
        }

        private fun syncChromeRecord() {
            val view = autoButton
            val live = auto.page == TouchPadAutoPage.RECORD
            if (!live) {
                chromeBreathing = false
                view?.animate()?.cancel()
                view?.rotation = 0f
                view?.alpha = 1f
                return
            }
            if (view == null) return
            view.rotation = 0f
            if (!chromeBreathing) {
                chromeBreathing = true
                pulseRecordChrome(view)
            }
        }

        private fun pulseRecordChrome(view: View) {
            if (!chromeBreathing || auto.page != TouchPadAutoPage.RECORD) {
                view.animate().cancel()
                view.alpha = 1f
                return
            }
            view.animate().cancel()
            view.animate()
                .alpha(0.42f)
                .setDuration(720L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (!chromeBreathing || auto.page != TouchPadAutoPage.RECORD) {
                        view.alpha = 1f
                        return@withEndAction
                    }
                    view.animate()
                        .alpha(1f)
                        .setDuration(720L)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { pulseRecordChrome(view) }
                        .start()
                }
                .start()
        }

        private fun bindAutoScene() {
            auto.bindScene(AvaAutoScene.capture(service, cursorX, cursorY))
            watchAutoScene(true)
        }

        private fun restoreAutoScene(): Boolean =
            AvaAutoScene.restore(service, auto.currentScene())

        private fun watchAutoScene(on: Boolean) {
            handler.removeCallbacks(sceneTick)
            if (on && auto.page == TouchPadAutoPage.RECORD) {
                AvaAutoKeys.sink = AvaAutoKeys.Sink { keyCode, action ->
                    auto.recordKey(keyCode, action, SystemClock.uptimeMillis())
                }
                AvaAutoKeys.textSink = AvaAutoKeys.TextSink { text ->
                    auto.recordText(text, SystemClock.uptimeMillis())
                }
                handler.postDelayed(sceneTick, 280L)
            } else {
                AvaAutoKeys.sink = null
                AvaAutoKeys.textSink = null
            }
        }

        private fun tickAutoScene() {
            if (auto.page != TouchPadAutoPage.RECORD) return
            auto.recordSceneIfChanged(
                AvaAutoScene.capture(service, cursorX, cursorY),
                SystemClock.uptimeMillis(),
            )
            handler.postDelayed(sceneTick, 280L)
        }

        private fun autoBadgeListener() = object : TouchPadAutoBadges.Listener {
            override fun onExpand() {
                if (auto.page == TouchPadAutoPage.RECORD) return
                autoSlotsOpen = !autoSlotsOpen
                OverlayHaptics.click(service, autoBadges)
                syncAutoBadges()
            }

            override fun onPlay(index: Int) {
                playBadge(index)
            }

            override fun onPick(index: Int) {
                if (auto.page == TouchPadAutoPage.RECORD) return
                if (auto.page == TouchPadAutoPage.PLAY || auto.page == TouchPadAutoPage.PAUSED) {
                    haltAuto(reset = false)
                }
                if (!auto.open) auto.open()
                if (!auto.select(index)) return
                persistAutoTakes()
                OverlayHaptics.click(service, autoBadges)
                applyAutoChrome()
            }

            override fun onDelete(index: Int) {
                deleteTake(index)
            }
        }

        private fun syncAutoBadges() {
            val badges = autoBadges ?: return
            val density = service.resources.displayMetrics.density
            val chromePx = TouchPadMath.titleChromePx(density)
            badges.setColor(autoIconColor())
            if (auto.page == TouchPadAutoPage.RECORD) autoSlotsOpen = false
            val compact = isCompactSlotChrome()
            val animate = pad?.isAttachedToWindow == true
            val entering = compact && !compactChrome
            val leaving = !compact && compactChrome
            badges.bind(
                auto.filledMask(),
                auto.selected,
                hidden = auto.page == TouchPadAutoPage.RECORD,
                expanded = autoSlotsOpen,
                titlePx = chromePx,
                compact = compact && !(entering && animate),
                padWidthPx = padParams?.width ?: pad?.width ?: 0,
            )
            if (leaving && animate) {
                val inline = badges.inlineTrash()
                inline?.alpha = 0f
                badges.setInlineTrashVisible(true)
            }
            applyCompactChrome(compact, animate)
        }

        private fun isCompactSlotChrome(): Boolean {
            if (!autoSlotsOpen) return false
            if (auto.page == TouchPadAutoPage.RECORD) return false
            if (auto.filledMask().none { it }) return false
            val density = service.resources.displayMetrics.density
            val chromePx = TouchPadMath.titleChromePx(density)
            val width = padParams?.width ?: pad?.width ?: 0
            if (width <= 0) return false
            return TouchPadMath.autoSlotChromeCompact(width, chromePx, density)
        }

        private fun canDeleteSelected(): Boolean {
            val mask = auto.filledMask()
            val index = auto.selected
            return index in mask.indices && mask[index]
        }

        private data class TrashPose(val cx: Float, val cy: Float, val scale: Float)

        private fun compactTrashRestPose(): TrashPose? {
            val trash = compactDelete ?: return null
            val density = service.resources.displayMetrics.density
            val chrome = TouchPadMath.titleChromePx(density).toFloat()
            val top = TouchPadMath.titlePadTopPx(density).toFloat()
            val w = if (trash.width > 0) trash.width.toFloat() else chrome
            val h = if (trash.height > 0) trash.height.toFloat() else chrome
            return TrashPose(cx = chrome + w / 2f, cy = top + h / 2f, scale = 1f)
        }

        private fun applyTrashPose(pose: TrashPose) {
            val trash = compactDelete ?: return
            val rest = compactTrashRestPose() ?: return
            if (trash.width > 0) {
                trash.pivotX = trash.width / 2f
                trash.pivotY = trash.height / 2f
            }
            trash.translationX = pose.cx - rest.cx
            trash.translationY = pose.cy - rest.cy
            trash.scaleX = pose.scale
            trash.scaleY = pose.scale
        }

        private fun abortTrashFlight() {
            val running = trashFlight
            trashFlight = null
            running?.cancel()
        }

        private fun settleTrashChrome() {
            val trash = compactDelete ?: return
            val canDelete = compactChrome && canDeleteSelected()
            trash.animate().cancel()
            trash.rotation = 0f
            if (compactChrome) {
                applyTrashPose(compactTrashRestPose() ?: return)
                trash.alpha = if (canDelete) 1f else 0.38f
                trash.visibility = View.VISIBLE
                trash.isClickable = canDelete
                trash.isFocusable = canDelete
            } else {
                trash.translationX = 0f
                trash.translationY = 0f
                trash.scaleX = 1f
                trash.scaleY = 1f
                trash.alpha = 0f
                trash.visibility = View.INVISIBLE
                trash.isClickable = false
                trash.isFocusable = false
                autoBadges?.setInlineTrashVisible(true)
            }
            autoBadges?.bringToFront()
            closeButton?.bringToFront()
            if (compactChrome) trash.bringToFront()
            resizeHandle?.bringToFront()
        }

        private fun finishTrashCrossfade() {
            if (compactChrome) {
                val badges = autoBadges
                val density = service.resources.displayMetrics.density
                val chromePx = TouchPadMath.titleChromePx(density)
                badges?.bind(
                    auto.filledMask(),
                    auto.selected,
                    hidden = auto.page == TouchPadAutoPage.RECORD,
                    expanded = autoSlotsOpen,
                    titlePx = chromePx,
                    compact = true,
                    padWidthPx = padParams?.width ?: pad?.width ?: 0,
                )
            } else {
                val inline = autoBadges?.inlineTrash()
                val shown = if (canDeleteSelected()) 1f else 0.38f
                inline?.alpha = shown
                autoBadges?.setInlineTrashVisible(true)
            }
            settleTrashChrome()
        }

        private fun crossfadeTrash(toCompact: Boolean, overlayPeak: Float, inlinePeak: Float) {
            val trash = compactDelete ?: return
            val rest = compactTrashRestPose() ?: return
            abortTrashFlight()
            val inline = autoBadges?.inlineTrash()
            trash.bringToFront()
            closeButton?.bringToFront()
            trash.visibility = View.VISIBLE
            trash.isClickable = false
            trash.rotation = 0f
            applyTrashPose(rest)
            if (toCompact) {
                trash.alpha = 0f
                inline?.visibility = View.VISIBLE
            } else {
                trash.alpha = overlayPeak
                inline?.visibility = View.VISIBLE
                inline?.alpha = 0f
                autoBadges?.setInlineTrashVisible(true)
            }
            val overlayFrom = if (toCompact) 0f else overlayPeak
            val overlayTo = if (toCompact) overlayPeak else 0f
            val inlineFrom = if (toCompact) inlinePeak else 0f
            val inlineTo = if (toCompact) 0f else inlinePeak
            val flight = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = TouchPadMath.AUTO_TRASH_SLIDE_MS
                interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
                addUpdateListener { animator ->
                    val t = animator.animatedValue as Float
                    val fadeOut = TouchPadMath.trashFadeOut(t)
                    val fadeIn = TouchPadMath.trashFadeIn(t)
                    trash.alpha = overlayFrom * fadeOut + overlayTo * fadeIn
                    inline?.alpha = inlineFrom * fadeOut + inlineTo * fadeIn
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (trashFlight !== animation) return
                        trashFlight = null
                        finishTrashCrossfade()
                    }
                })
            }
            trashFlight = flight
            flight.start()
        }

        private fun applyCompactChrome(
            compact: Boolean,
            animate: Boolean,
        ) {
            val pin = pinButton ?: return
            val star = autoStarHost ?: return
            val trash = compactDelete ?: return
            val density = service.resources.displayMetrics.density
            val chromePx = TouchPadMath.titleChromePx(density).toFloat()
            val canDelete = compact && canDeleteSelected()
            val starX = if (compact) -chromePx else 0f
            val pinAlpha = if (compact) 0f else 1f
            val trashAlpha = when {
                !compact -> 0f
                canDelete -> 1f
                else -> 0.38f
            }
            pin.isClickable = !compact
            val same = compact == compactChrome
            if (same) {
                if (trashFlight == null) {
                    trash.alpha = trashAlpha
                    trash.isClickable = canDelete
                    trash.isFocusable = canDelete
                }
                return
            }
            compactChrome = compact
            val ease = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            val slideMs = TouchPadMath.AUTO_TRASH_SLIDE_MS
            if (!animate) {
                abortTrashFlight()
                pin.animate().cancel()
                star.animate().cancel()
                trash.animate().cancel()
                pin.alpha = pinAlpha
                pin.visibility = if (compact) View.INVISIBLE else View.VISIBLE
                star.translationX = starX
                finishTrashCrossfade()
                return
            }
            if (!compact) pin.visibility = View.VISIBLE
            pin.animate().cancel()
            pin.animate()
                .alpha(pinAlpha)
                .setDuration(slideMs)
                .setInterpolator(ease)
                .withEndAction {
                    if (compactChrome) pin.visibility = View.INVISIBLE
                }
                .start()
            star.animate().cancel()
            star.animate()
                .translationX(starX)
                .setDuration(slideMs)
                .setInterpolator(ease)
                .start()
            val rest = compactTrashRestPose()
            val inlinePeak = if (canDeleteSelected()) 1f else 0.38f
            if (compact) {
                if (rest != null) crossfadeTrash(toCompact = true, overlayPeak = trashAlpha, inlinePeak = inlinePeak)
                else settleTrashChrome()
            } else {
                val startFly = {
                    if (rest != null) {
                        crossfadeTrash(toCompact = false, overlayPeak = inlinePeak, inlinePeak = inlinePeak)
                    } else {
                        settleTrashChrome()
                    }
                }
                val inline = autoBadges?.inlineTrash()
                if (inline == null || (inline.isLaidOut && inline.width > 0)) {
                    trash.post { if (!compactChrome) startFly() }
                } else {
                    inline.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            v: View,
                            left: Int,
                            top: Int,
                            right: Int,
                            bottom: Int,
                            oldLeft: Int,
                            oldTop: Int,
                            oldRight: Int,
                            oldBottom: Int,
                        ) {
                            v.removeOnLayoutChangeListener(this)
                            if (!compactChrome) startFly()
                        }
                    })
                }
            }
        }

        fun playTakeHeadless(index: Int): Boolean {
            if (auto.page == TouchPadAutoPage.RECORD) return false
            if (index !in 0 until TouchPadAuto.MAX_TAKES) return false
            if (!auto.filledMask()[index]) return false
            if (isShowing) dismiss(animate = false)
            if (headlessPlay) {
                finishHeadlessPlay()
            } else if (auto.page == TouchPadAutoPage.PLAY || auto.page == TouchPadAutoPage.PAUSED) {
                abortPlayClock(clearTrail = true)
                auto.haltPlay()
            }
            headlessPlay = true
            if (!auto.open) auto.open()
            if (!auto.select(index) || !auto.beginPlay()) {
                finishHeadlessPlay()
                return false
            }
            startAutoPlay()
            return true
        }

        private fun playBadge(index: Int) {
            if (auto.page == TouchPadAutoPage.RECORD) return
            if (auto.page == TouchPadAutoPage.PLAY || auto.page == TouchPadAutoPage.PAUSED) {
                haltAuto(reset = false)
            }
            if (!auto.open) auto.open()
            if (!auto.select(index)) return
            persistAutoTakes()
            if (!auto.beginPlay()) return
            OverlayHaptics.click(service, autoBadges)
            startAutoPlay()
            ackPad()
        }

        private fun startAutoPlay() {
            abortPlayClock(clearTrail = !headlessPlay)
            resetScrollQueue()
            if (!headlessPlay) marks?.clear()
            sceneArmed = false
            if (!headlessPlay) applyAutoChrome()
            handler.postDelayed(playRunnable, TouchPadAuto.PLAY_SETTLE_MS)
        }

        /** Drop the live play clock so a later run cannot share its callbacks. */
        private fun abortPlayClock(clearTrail: Boolean = false) {
            playGen++
            glideGen++
            handler.removeCallbacks(playRunnable)
            SidebarDrawerRemote.endEdgeSwipe(commit = false)
            if (clearTrail) cursor?.clearTrail()
        }

        private fun persistAutoTakes() {
            prefs.autoLibrary = TouchPadAutoStore.encode(auto.snapshotLibrary(), auto.selected)
            val mask = auto.filledMask().joinToString()
            if (mask != lastPublishedMask) {
                lastPublishedMask = mask
                TouchPadHa.notifyLibraryChanged(service)
            }
        }

        private fun finishHeadlessPlay() {
            if (!headlessPlay) return
            headlessPlay = false
            abortPlayClock(clearTrail = false)
            auto.haltPlay()
            auto.close()
            lastAutoPage = null
        }

        private fun toggleAutoLoop() {
            auto.loop = !auto.loop
            prefs.autoLoop = auto.loop
        }

        private fun deleteTake(index: Int) {
            if (auto.page == TouchPadAutoPage.RECORD) return
            val playing =
                (auto.page == TouchPadAutoPage.PLAY || auto.page == TouchPadAutoPage.PAUSED) &&
                    auto.selected == index
            if (playing) {
                if (headlessPlay) finishHeadlessPlay()
                else abortPlayClock(clearTrail = true)
            }
            if (!auto.clearTake(index)) return
            persistAutoTakes()
            OverlayHaptics.click(service, autoBadges)
            applyAutoChrome()
        }

        private fun finishAutoRecord() {
            watchAutoScene(false)
            auto.finishRecord()
            persistAutoTakes()
        }

        private fun haltAuto(reset: Boolean) {
            if (headlessPlay) {
                finishHeadlessPlay()
                return
            }
            abortPlayClock(clearTrail = true)
            if (auto.page == TouchPadAutoPage.RECORD) finishAutoRecord()
            auto.haltPlay()
            if (reset) {
                auto.close()
                lastAutoPage = null
            }
        }

        private fun advanceAutoPlay() {
            val gen = playGen
            if (auto.page != TouchPadAutoPage.PLAY) return
            if (!auto.hasTake()) {
                if (headlessPlay) {
                    finishHeadlessPlay()
                    return
                }
                haltAuto(reset = false)
                applyAutoChrome()
                ackPad()
                return
            }
            if (!sceneArmed) {
                sceneArmed = true
                if (restoreAutoScene()) {
                    val wait = if (auto.sceneWindows.isNotEmpty() ||
                        MainNavigationCoordinator.isSettingsLikeRoute(auto.sceneRoute)
                    ) {
                        720L
                    } else {
                        TouchPadAuto.SCENE_SETTLE_MS
                    }
                    handler.postDelayed(playRunnable, wait)
                    return
                }
            }
            if (auto.finishedPass()) {
                if (!headlessPlay && auto.loop && auto.wrapPlayCursor()) {
                    sceneArmed = false
                    handler.postDelayed(playRunnable, TouchPadAuto.LOOP_GAP_MS)
                    return
                }
                if (headlessPlay) {
                    finishHeadlessPlay()
                    return
                }
                abortPlayClock(clearTrail = true)
                auto.parkAfterPass()
                applyAutoChrome()
                ackPad()
                return
            }
            val step = auto.consumePlayStep() ?: return
            showAutoCursor(step)
            flashAutoMark(step)
            val again = {
                if (gen == playGen && auto.page == TouchPadAutoPage.PLAY) {
                    handler.post(playRunnable)
                }
            }
            when (step.type) {
                TouchPadAutoStepType.DELAY ->
                    handler.postDelayed(playRunnable, step.durationMs)
                TouchPadAutoStepType.TAP -> {
                    dispatchTap(step.x, step.y)
                    handler.postDelayed(
                        playRunnable,
                        step.durationMs.coerceAtLeast(TouchPadMath.TAP_DURATION_MS) +
                            TouchPadAuto.TAP_TAIL_MS,
                    )
                }
                TouchPadAutoStepType.PRESS ->
                    dispatchPress(step.x, step.y, step.durationMs) { again() }
                TouchPadAutoStepType.SLIDE ->
                    dispatchSlide(
                        step.x,
                        step.y,
                        step.x2,
                        step.y2,
                        TouchPadAuto.playSwipeMs(step.durationMs),
                    ) { again() }
                TouchPadAutoStepType.BACK -> {
                    dispatchBack()
                    handler.postDelayed(playRunnable, TouchPadAuto.TAP_TAIL_MS + 80L)
                }
                TouchPadAutoStepType.SECONDARY -> {
                    dispatchSecondaryTap(step.x, step.y)
                    handler.postDelayed(playRunnable, TouchPadAuto.TAP_TAIL_MS + 80L)
                }
                TouchPadAutoStepType.RECENTS -> {
                    AccessibilityBridge.recents()
                    handler.postDelayed(playRunnable, TouchPadAuto.RECENTS_SETTLE_MS)
                }
                TouchPadAutoStepType.SCROLL -> {
                    enqueueScroll(step.x2, step.y2)
                    finishScrollQueue()
                    handler.postDelayed(playRunnable, TouchPadMath.SCROLL_PRESS_MS + 40L)
                }
                TouchPadAutoStepType.SIDEBAR ->
                    dispatchSidebar(step.x2, step.durationMs, gen) { again() }
                TouchPadAutoStepType.ROUTE -> {
                    AvaAutoScene.restore(service, TouchPadAutoScene(route = step.extra))
                    handler.postDelayed(playRunnable, TouchPadAuto.SCENE_SETTLE_MS)
                }
                TouchPadAutoStepType.WINDOW -> {
                    AvaAutoScene.applyWindow(service, step.extra, step.x >= 0.5f)
                    handler.postDelayed(playRunnable, TouchPadAuto.SCENE_SETTLE_MS)
                }
                TouchPadAutoStepType.KEY -> {
                    if (step.durationMs.toInt() != android.view.KeyEvent.ACTION_UP) {
                        AvaAutoKeys.inject(step.keyCode)
                    }
                    handler.postDelayed(playRunnable, TouchPadAuto.KEY_PLAY_MS)
                }
                TouchPadAutoStepType.TEXT -> {
                    AccessibilityBridge.setFocusedText(step.extra)
                    handler.postDelayed(
                        playRunnable,
                        TouchPadAuto.textPlayMs(step.extra.length),
                    )
                }
            }
        }

        private fun showAutoCursor(step: TouchPadAutoStep) {
            if (headlessPlay) return
            when (step.type) {
                TouchPadAutoStepType.DELAY -> revealCursorNow()
                TouchPadAutoStepType.TAP,
                TouchPadAutoStepType.PRESS -> {
                    val far = TouchPadMath.hypot(step.x - cursorX, step.y - cursorY) >=
                        TouchPadMath.TAP_SLOP_PX
                    if (far && step.type == TouchPadAutoStepType.TAP) {
                        glideCursor(
                            cursorX,
                            cursorY,
                            step.x,
                            step.y,
                            TouchPadMath.AUTO_STREAK_MS,
                        ) { cursor?.playTapPulse() }
                    } else {
                        pinCursorTo(step.x, step.y)
                        cursor?.playTapPulse()
                    }
                }
                TouchPadAutoStepType.SLIDE -> glideCursor(
                    step.x,
                    step.y,
                    step.x2,
                    step.y2,
                    step.durationMs,
                )
                TouchPadAutoStepType.BACK,
                TouchPadAutoStepType.SECONDARY,
                TouchPadAutoStepType.RECENTS,
                TouchPadAutoStepType.SCROLL,
                TouchPadAutoStepType.SIDEBAR -> {
                    pinCursorTo(step.x, step.y)
                    cursor?.playTapPulse()
                }
                TouchPadAutoStepType.ROUTE,
                TouchPadAutoStepType.WINDOW,
                TouchPadAutoStepType.KEY,
                TouchPadAutoStepType.TEXT -> revealCursorNow()
            }
        }

        private fun twoFingerBindsAvaSidebar(): Boolean {
            val avaPkg = service.packageName
            val atCursor = TouchPadScroller.packageAt(service, cursorX, cursorY)
            val active = service.rootInActiveWindow?.packageName?.toString().orEmpty()
            return TouchPadMath.twoFingerBindsSidebar(
                appWindowUnderCursor = AppWindowService.containsScreenPoint(cursorX, cursorY),
                avaBrowserUnderCursor = WebViewService.containsScreenPoint(cursorX, cursorY) ||
                    AiBrowserService.containsScreenPoint(cursorX, cursorY),
                avaActivityResumed = MainNavigationCoordinator.isActivityResumed(),
                foregroundPackage = TouchPadMath.twoFingerForegroundPackage(
                    atCursor,
                    active,
                    avaPkg,
                ),
                avaPackage = avaPkg,
            )
        }

        private fun noteAutoSceneNow(x: Float = cursorX, y: Float = cursorY) {
            if (auto.page != TouchPadAutoPage.RECORD) return
            auto.recordSceneIfChanged(AvaAutoScene.capture(service, x, y), SystemClock.uptimeMillis())
        }

        private fun noteAutoTap(x: Float, y: Float) {
            auto.recordTap(x, y, SystemClock.uptimeMillis())
            stampAutoMark(x, y)
            noteAutoSceneNow(x, y)
        }

        private fun noteAutoPress(x: Float, y: Float, durationMs: Long) {
            auto.recordPress(x, y, SystemClock.uptimeMillis(), durationMs)
            if (auto.page != TouchPadAutoPage.RECORD) return
            pinCursorTo(x, y)
            cursor?.playTapPulse()
            marks?.flash(TouchPadAutoMarksView.Kind.PRESS, x, y)
            noteAutoSceneNow(x, y)
        }

        private fun noteAutoSlide(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
        ) {
            auto.recordSlide(x1, y1, x2, y2, SystemClock.uptimeMillis(), durationMs)
            if (auto.page != TouchPadAutoPage.RECORD) return
            pinCursorTo(x2, y2)
            cursor?.playTapPulse()
            marks?.flash(TouchPadAutoMarksView.Kind.SLIDE, x1, y1, x2, y2)
            noteAutoSceneNow(x2, y2)
        }

        private fun noteAutoBack(x: Float, y: Float) {
            auto.recordBack(x, y, SystemClock.uptimeMillis())
            stampAutoMark(x, y)
            noteAutoSceneNow(x, y)
        }

        private fun noteAutoSecondary(x: Float, y: Float) {
            auto.recordSecondary(x, y, SystemClock.uptimeMillis())
            stampAutoMark(x, y)
            noteAutoSceneNow(x, y)
        }

        private fun noteAutoRecents(x: Float, y: Float) {
            auto.recordRecents(x, y, SystemClock.uptimeMillis())
            stampAutoMark(x, y)
            noteAutoSceneNow(x, y)
        }

        private fun noteAutoScroll(x: Float, y: Float, dx: Float, dy: Float) {
            if (TouchPadMath.hypot(dx, dy) < TouchPadMath.TAP_SLOP_PX) return
            auto.recordScroll(x, y, dx, dy, SystemClock.uptimeMillis())
            stampAutoMark(x, y)
        }

        private fun noteAutoSidebar(x: Float, y: Float, dx: Float, durationMs: Long) {
            auto.recordSidebar(x, y, dx, SystemClock.uptimeMillis(), durationMs)
            if (auto.page != TouchPadAutoPage.RECORD) return
            pinCursorTo(x, y)
            cursor?.playTapPulse()
            marks?.flash(TouchPadAutoMarksView.Kind.SCROLL, x, y, x + dx, y)
            noteAutoSceneNow(x, y)
        }

        private fun stampAutoMark(x: Float, y: Float) {
            if (auto.page != TouchPadAutoPage.RECORD) return
            pinCursorTo(x, y)
            cursor?.playTapPulse()
            marks?.flash(TouchPadAutoMarksView.Kind.TAP, x, y)
        }

        private fun flashAutoMark(step: TouchPadAutoStep) {
            if (headlessPlay) return
            marks?.setFillRgb(themedCursorRgb())
            marks?.flash(step)
        }

        private fun noteAutoPath(x1: Float, y1: Float, x2: Float, y2: Float) {
            val travel = TouchPadMath.hypot(x2 - x1, y2 - y1)
            if (travel >= TouchPadMath.TAP_SLOP_PX) {
                noteAutoSlide(x1, y1, x2, y2, prefs.slideDurationMs)
            } else {
                val held = (SystemClock.uptimeMillis() - holdBeganAt).coerceAtLeast(1L)
                noteAutoPress(x1, y1, held)
            }
        }

        private fun bounceChrome(view: View) {
            view.animate().cancel()
            view.scaleX = 0.82f
            view.scaleY = 0.82f
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        /** Whole-pad bloom so a mode change is visible, not silent. */
        private fun ackPad(strong: Boolean = false) {
            val view = pad ?: return
            if (!view.isAttachedToWindow || isFadingOut) return
            view.post {
                if (pad !== view || !view.isAttachedToWindow || isFadingOut) return@post
                if (view.width <= 0 || view.height <= 0) return@post
                view.pivotX = view.width / 2f
                view.pivotY = view.height / 2f
                val peak = if (strong) 1.08f else 1.045f
                padAck?.cancel()
                view.scaleX = 1f
                view.scaleY = 1f
                view.clipToOutline = false
                val grow = ObjectAnimator.ofPropertyValuesHolder(
                    view,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, peak),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, peak),
                ).apply {
                    duration = if (strong) 150L else 110L
                    interpolator = DecelerateInterpolator()
                }
                val settle = ObjectAnimator.ofPropertyValuesHolder(
                    view,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, peak, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, peak, 1f),
                ).apply {
                    duration = if (strong) 200L else 150L
                    interpolator = DecelerateInterpolator()
                }
                padAck = AnimatorSet().apply {
                    playSequentially(grow, settle)
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            restorePadAck(view)
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            restorePadAck(view)
                        }
                    })
                    start()
                }
            }
        }

        private fun restorePadAck(view: View) {
            if (pad === view) {
                view.scaleX = 1f
                view.scaleY = 1f
                view.clipToOutline = true
            }
        }

        private fun showResizeHandle() {
            handler.removeCallbacks(hideHandleRunnable)
            resizeHandle?.animate()?.cancel()
            resizeHandle?.animate()
                ?.alpha(1f)
                ?.setDuration(180L)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
        }

        private fun scheduleHandleHide() {
            handler.removeCallbacks(hideHandleRunnable)
            handler.postDelayed(hideHandleRunnable, TouchPadMath.HANDLE_HIDE_MS)
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun attachResize(
            handle: View,
            root: FrameLayout,
            params: WindowManager.LayoutParams,
        ) {
            val density = service.resources.displayMetrics.density
            val minW = TouchPadMath.dp(TouchPadMath.MIN_SIZE_DP, density)
            val minH = minW + TouchPadMath.titleBarPx(density)
            var startX = 0f
            var startY = 0f
            var originW = 0
            var originH = 0
            handle.setOnTouchListener { _, event ->
                if (TouchPadPageScroll.isGeneratedGesture(event)) return@setOnTouchListener true
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        originW = params.width
                        originH = params.height
                        showResizeHandle()
                        OverlayHaptics.click(service, handle)
                        scheduleIdleClose()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        applyLiveWindowSize(
                            root,
                            params,
                            originW + (event.rawX - startX).toInt(),
                            originH + (event.rawY - startY).toInt(),
                            minW,
                            minH,
                        )
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        persistPadSize(params)
                        savePadNorm(params)
                        scheduleHandleHide()
                        true
                    }
                    else -> false
                }
            }
        }

        private fun applyLiveWindowSize(
            root: FrameLayout,
            params: WindowManager.LayoutParams,
            widthPx: Int,
            heightPx: Int,
            minW: Int,
            minH: Int,
        ) {
            val metrics = realMetrics()
            val density = service.resources.displayMetrics.density
            val barPx = TouchPadMath.titleBarPx(density)
            val maxW = metrics.widthPixels - TouchPadMath.dp(24, density)
            val maxH = metrics.heightPixels - TouchPadMath.dp(96, density)
            params.width = TouchPadMath.clamp(widthPx, minW, maxOf(minW, maxW))
            params.height = TouchPadMath.clamp(heightPx, minH, maxOf(minH, maxH))
            clampPad(params, params.width, params.height, density)
            syncBodySize((params.height - barPx).coerceAtLeast(minW), barPx)
            runCatching { windowManager.updateViewLayout(root, params) }
            val chromePx = TouchPadMath.titleChromePx(density)
            autoBadges?.setPadWidth(params.width, chromePx)
            if (isCompactSlotChrome() != compactChrome) syncAutoBadges()
        }

        private fun persistPadSize(params: WindowManager.LayoutParams) {
            val density = service.resources.displayMetrics.density
            val barPx = TouchPadMath.titleBarPx(density)
            val padH = (params.height - barPx).coerceAtLeast(1)
            prefs.padWidthDp = (params.width / density).roundToInt()
            prefs.padHeightDp = (padH / density).roundToInt()
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun attachTitleDrag(
            title: LinearLayout,
            root: FrameLayout,
            params: WindowManager.LayoutParams,
        ) {
            var startX = 0
            var startY = 0
            var downRawX = 0f
            var downRawY = 0f
            var dragged = false
            title.setOnTouchListener { _, event ->
                if (TouchPadPageScroll.isGeneratedGesture(event)) return@setOnTouchListener true
                if (prefs.pinned) return@setOnTouchListener true
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        dragged = false
                        scheduleIdleClose()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        dragged = true
                        params.x = startX + (event.rawX - downRawX).toInt()
                        params.y = startY + (event.rawY - downRawY).toInt()
                        val density = service.resources.displayMetrics.density
                        clampPad(params, params.width, params.height, density)
                        runCatching { windowManager.updateViewLayout(root, params) }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragged) savePadNorm(params)
                        dragged = false
                        true
                    }
                    else -> true
                }
            }
        }

        private fun applyTheme(host: FrameLayout? = pad) {
            val root = host ?: return
            val dark = DarkModeManager.getInstance(service).isDarkMode()
            val palette = TouchPadTheme.palette(dark)
            val density = service.resources.displayMetrics.density
            val corner = TouchPadMath.dp(14, density).toFloat()
            if (LiquidGlass.enabled) {
                bodyBackground = null
                // Own slab on this overlay. Never FLAG_BLUR_BEHIND — that flag
                // blooms a disc behind TYPE_ACCESSIBILITY_OVERLAY on some OEMs.
                val glass = LiquidGlass.applyTo(
                    root,
                    cornerRadiusPx = corner,
                    tint = TouchPadTheme.glassTint(dark, prefs.opacity),
                    light = !dark,
                    withPressGlow = false,
                    windowBacked = false,
                    ownedMaterial = true,
                )
                glass.setDensity(density)
                root.elevation = 0f
                surface?.setFill(Color.TRANSPARENT, palette.lightGlass)
                bindPadPressGlow(glass)
            } else {
                surface?.setOnTouchListener(null)
                val body = (root.background as? GradientDrawable) ?: GradientDrawable().apply {
                    cornerRadius = corner
                }
                body.cornerRadius = corner
                body.setColor(TouchPadTheme.bodyColor(dark, prefs.opacity))
                body.setStroke(TouchPadMath.dp(1, density), palette.stroke)
                root.background = body
                root.elevation = TouchPadMath.dp(8, density).toFloat()
                bodyBackground = body
                surface?.setFill(palette.surface, palette.lightGlass)
            }
            titleBar?.background = null
            divider?.alpha = 1f
            (divider?.background as? GradientDrawable)?.setColor(palette.divider)
            padParams?.let { params ->
                val cleared = LiquidGlass.clearWindowBlur(params)
                if (cleared && root.isAttachedToWindow) {
                    runCatching { windowManager.updateViewLayout(root, params) }
                }
            }
            closeButton?.setStrokeColor(palette.close)
            pinButton?.setColorFilter(palette.icon)
            autoButton?.setColorFilter(palette.icon)
            compactDelete?.setColorFilter(palette.icon)
            autoBadges?.setColor(palette.icon)
            autoPanel?.setColors(palette.icon, palette.divider)
            if (auto.open) applyAutoChrome() else {
                autoButton?.background = null
                autoButton?.setColorFilter(palette.icon)
                syncAutoBadges()
            }
            resizeHandle?.setColors(palette.handleStroke, palette.handleHalo)
            cursor?.setFillRgb(themedCursorRgb(dark), lightMode = !dark)
            marks?.setFillRgb(themedCursorRgb(dark))
            root.invalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun bindPadPressGlow(glass: LiquidGlassDrawable) {
            val padView = surface ?: return
            val titlePx = titleBar?.height?.toFloat()
                ?: titleBar?.layoutParams?.height?.toFloat()
                ?: 0f
            padView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_MOVE -> {
                        var x = 0f
                        var y = 0f
                        val n = event.pointerCount.coerceAtLeast(1)
                        for (i in 0 until n) {
                            x += event.getX(i)
                            y += event.getY(i)
                        }
                        glass.showPress(x / n, y / n + titlePx)
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_POINTER_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        if (event.actionMasked != MotionEvent.ACTION_POINTER_UP ||
                            event.pointerCount <= 1
                        ) {
                            glass.clearPress()
                        }
                    }
                }
                false
            }
        }

        private fun listenStyle(on: Boolean) {
            styleJob?.cancel()
            styleJob = null
            if (!on) return
            LiquidGlass.ensureLoaded(service)
            styleJob = styleScope.launch {
                combine(
                    SettingsStyleSession.liquidGlassEnabled,
                    SettingsStyleSession.liquidGlassIntensity,
                    SettingsStyleSession.liquidGlassPressGlow,
                ) { _, _, _ -> }
                    .collect { applyTheme() }
            }
        }

        private fun themedCursorRgb(dark: Boolean = DarkModeManager.getInstance(service).isDarkMode()): Int {
            val custom = prefs.cursorColorRgb
            return if (custom == TouchPadPrefs.DEFAULT_CURSOR_RGB) {
                TouchPadTheme.palette(dark).cursorRgb
            } else {
                custom
            }
        }

        private fun listenDarkMode(on: Boolean) {
            if (on == listeningDarkMode) return
            listeningDarkMode = on
            if (on) {
                homePrefs.registerOnSharedPreferenceChangeListener(darkModeListener)
            } else {
                homePrefs.unregisterOnSharedPreferenceChangeListener(darkModeListener)
            }
        }

        private fun addMarks(metrics: DisplayMetrics) {
            if (marks != null) return
            val view = TouchPadAutoMarksView(service)
            val params = WindowManager.LayoutParams(
                metrics.widthPixels,
                metrics.heightPixels,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                OverlayOrientation.apply(this)
            }
            try {
                windowManager.addView(view, params)
                marks = view
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create touch pad marks overlay", e)
            }
        }

        private fun syncAutoMarks() {
            val view = marks ?: return
            view.setFillRgb(themedCursorRgb())
            if (!auto.open ||
                auto.page == TouchPadAutoPage.ARM
            ) {
                view.clear()
            }
        }

        private fun addCursor(density: Float, animate: Boolean) {
            val size = TouchPadMath.dp(TouchPadMath.CURSOR_TRAIL_SIZE_DP, density)
            val view = TouchPadCursorView(service, themedCursorRgb()).apply {
                alpha = 0f
            }
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val half = size / 2f
                x = (cursorX - half).toInt()
                y = (cursorY - half).toInt()
                OverlayOrientation.apply(this)
            }
            try {
                windowManager.addView(view, params)
                cursor = view
                cursorParams = params
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create cursor overlay", e)
            }
        }

        private fun maybeShowTip(
            metrics: DisplayMetrics,
            density: Float,
            padY: Int,
            animate: Boolean,
        ) {
            if (prefs.tipSeenOnce || tip != null) return
            val width = minOf(
                metrics.widthPixels - TouchPadMath.dp(32, density),
                TouchPadMath.dp(340, density),
            ).coerceAtLeast(TouchPadMath.dp(240, density))
            val dark = DarkModeManager.getInstance(service).isDarkMode()
            val corner = TouchPadMath.dp(16, density).toFloat()
            val column = LinearLayout(service).apply {
                alpha = if (animate) 0f else 1f
                orientation = LinearLayout.VERTICAL
                setPadding(
                    TouchPadMath.dp(16, density),
                    TouchPadMath.dp(14, density),
                    TouchPadMath.dp(16, density),
                    TouchPadMath.dp(12, density),
                )
                if (LiquidGlass.enabled) {
                    LiquidGlass.applyTo(
                        this,
                        cornerRadiusPx = corner,
                        tint = TouchPadTheme.glassTint(dark, prefs.opacity.coerceAtLeast(72)),
                        light = !dark,
                        windowBacked = false,
                        ownedMaterial = true,
                    ).setDensity(density)
                    elevation = 0f
                } else {
                    background = GradientDrawable().apply {
                        setColor(-266855388)
                        setStroke(TouchPadMath.dp(1, density), -13288633)
                        cornerRadius = corner
                    }
                    elevation = TouchPadMath.dp(10, density).toFloat()
                }
            }
            val title = TextView(service).apply {
                text = service.getString(R.string.touch_pad_tip_title)
                setTextColor(-657413)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
            }
            column.addView(title)
            val body = TextView(service).apply {
                text = service.getString(R.string.touch_pad_tip_body)
                setTextColor(-3090718)
                textSize = 14f
                setLineSpacing(TouchPadMath.dp(4f, density), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = TouchPadMath.dp(8, density) }
            }
            column.addView(body)
            val button = Button(service).apply {
                text = service.getString(R.string.touch_pad_tip_got_it)
                setTextColor(-7476225)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                minHeight = 0
                minWidth = 0
                minimumHeight = 0
                minimumWidth = 0
                setPadding(TouchPadMath.dp(12, density), 0, TouchPadMath.dp(12, density), 0)
                background = GradientDrawable().apply {
                    setColor(445508607)
                    cornerRadius = TouchPadMath.dp(16, density).toFloat()
                }
                setOnClickListener {
                    OverlayHaptics.click(service, this)
                    scheduleIdleClose()
                    removeTip(markSeen = true, animate = true)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    TouchPadMath.dp(34, density),
                ).apply {
                    gravity = Gravity.END
                    topMargin = TouchPadMath.dp(10, density)
                }
            }
            column.addView(button)
            val params = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = ((metrics.widthPixels - width) / 2)
                    .coerceAtLeast(TouchPadMath.dp(8, density))
                y = TouchPadMath.clamp(
                    padY - TouchPadMath.dp(190, density),
                    TouchPadMath.dp(48, density),
                    (metrics.heightPixels - TouchPadMath.dp(220, density))
                        .coerceAtLeast(TouchPadMath.dp(48, density)),
                )
                OverlayOrientation.apply(this)
            }
            try {
                windowManager.addView(column, params)
                tip = column
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create touch pad tip overlay", e)
            }
        }

        private fun removeTip(markSeen: Boolean, animate: Boolean = false) {
            if (markSeen) prefs.tipSeenOnce = true
            val view = tip ?: return
            tipFadeGeneration++
            view.animate().cancel()
            view.animate().setListener(null)
            if (!animate) {
                runCatching { windowManager.removeView(view) }
                if (tip === view) tip = null
                return
            }
            val token = tipFadeGeneration
            view.animate()
                .alpha(0f)
                .scaleX(TouchPadMath.ENTER_SCALE)
                .scaleY(TouchPadMath.ENTER_SCALE)
                .setDuration(TouchPadMath.FADE_MS)
                .setInterpolator(AccelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.animate().setListener(null)
                        if (token != tipFadeGeneration || tip !== view) return
                        runCatching { windowManager.removeView(view) }
                        tip = null
                    }
                })
                .start()
        }

        private fun placePad(
            params: WindowManager.LayoutParams,
            metrics: DisplayMetrics,
            widthPx: Int,
            heightPx: Int,
            density: Float,
        ) {
            val saved = prefs.savedNormOrNull()
            val bounds = padBounds(metrics, widthPx, heightPx, density)
            if (saved != null) {
                params.x = TouchPadMath.lerpNorm(saved.first, bounds.minX, bounds.maxX)
                params.y = TouchPadMath.lerpNorm(saved.second, bounds.minY, bounds.maxY)
            } else {
                params.x = TouchPadMath.clamp(
                    (metrics.widthPixels - widthPx) / 2,
                    bounds.minX,
                    bounds.maxX,
                )
                params.y = TouchPadMath.clamp(
                    (metrics.heightPixels - heightPx) - TouchPadMath.dp(
                        TouchPadMath.DEFAULT_BOTTOM_GAP_DP,
                        density,
                    ),
                    bounds.minY,
                    bounds.maxY,
                )
            }
        }

        private fun restoreOrClamp(
            root: FrameLayout,
            params: WindowManager.LayoutParams,
            metrics: DisplayMetrics,
        ) {
            val density = service.resources.displayMetrics.density
            if (clampPad(params, params.width, params.height, density)) {
                runCatching { windowManager.updateViewLayout(root, params) }
            }
        }

        private fun placeCursorAbovePad(
            metrics: DisplayMetrics,
            params: WindowManager.LayoutParams,
            padWidthPx: Int,
            density: Float,
        ) {
            val half = TouchPadMath.dp(TouchPadMath.CURSOR_SIZE_DP, density) / 2f
            val gap = TouchPadMath.dp(TouchPadMath.CURSOR_ABOVE_GAP_DP, density)
            cursorX = TouchPadMath.clamp(
                params.x + padWidthPx / 2f,
                half,
                metrics.widthPixels - half,
            )
            cursorY = TouchPadMath.clamp(
                params.y - gap - half,
                half,
                metrics.heightPixels - half,
            )
        }

        private fun moveCursor(
            metrics: DisplayMetrics,
            dx: Float,
            dy: Float,
            stable: Boolean = false,
        ): TouchPadMath.Point {
            val scale = if (stable) {
                TouchPadMath.holdPointerScale(prefs.cursorSensitivity, dx, dy)
            } else {
                TouchPadMath.pointerScale(prefs.cursorSensitivity, dx, dy)
            }
            val nextX = TouchPadMath.move(cursorX, dx, scale, 0f, metrics.widthPixels.toFloat())
            val nextY = TouchPadMath.move(cursorY, dy, scale, 0f, metrics.heightPixels.toFloat())
            val applied = TouchPadMath.Point(nextX - cursorX, nextY - cursorY)
            cursorX = nextX
            cursorY = nextY
            updateCursorWindow()
            return applied
        }

        private fun updateCursorWindow() {
            val view = cursor ?: return
            val params = cursorParams ?: return
            val half = cursorWindowHalf()
            params.x = (cursorX - half).toInt()
            params.y = (cursorY - half).toInt()
            runCatching { windowManager.updateViewLayout(view, params) }
        }

        private fun cursorWindowHalf(): Float =
            TouchPadMath.dp(
                TouchPadMath.CURSOR_TRAIL_SIZE_DP,
                service.resources.displayMetrics.density,
            ) / 2f

        private fun holdsCursorVisible(): Boolean =
            auto.page == TouchPadAutoPage.PLAY ||
                auto.page == TouchPadAutoPage.PAUSED ||
                auto.page == TouchPadAutoPage.RECORD

        private fun pinCursorTo(x: Float, y: Float, streak: Boolean = true) {
            if (isFadingOut) return
            val metrics = realMetrics()
            val half = TouchPadMath.dp(
                TouchPadMath.CURSOR_SIZE_DP,
                service.resources.displayMetrics.density,
            ) / 2f
            cursorX = TouchPadMath.clamp(x, half, metrics.widthPixels - half)
            cursorY = TouchPadMath.clamp(y, half, metrics.heightPixels - half)
            if (streak) cursor?.follow(cursorX, cursorY)
            updateCursorWindow()
            revealCursorNow()
        }

        private fun glideCursor(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
            then: (() -> Unit)? = null,
        ) {
            val gen = ++glideGen
            pinCursorTo(x1, y1)
            val start = SystemClock.uptimeMillis()
            val dur = durationMs.coerceAtLeast(16L)
            val tick = object : Runnable {
                override fun run() {
                    if (gen != glideGen || auto.page != TouchPadAutoPage.PLAY) return
                    val t = ((SystemClock.uptimeMillis() - start).toFloat() / dur.toFloat())
                        .coerceIn(0f, 1f)
                    pinCursorTo(
                        TouchPadMath.lerp(x1, x2, t),
                        TouchPadMath.lerp(y1, y2, t),
                    )
                    if (t < 1f) {
                        handler.postDelayed(this, 16L)
                    } else {
                        then?.invoke()
                    }
                }
            }
            handler.post(tick)
        }

        private fun setCursorMode(mode: TouchPadCursorMode) {
            cursor?.setMode(mode)
        }

        private fun scheduleIdleClose() {
            handler.removeCallbacks(idleCloseRunnable)
            if (auto.holdsIdleClose()) return
            val idleMs = prefs.idleCloseMs
            if (idleMs <= 0L || pad == null || isFadingOut) return
            handler.postDelayed(idleCloseRunnable, idleMs)
        }

        private fun allowPadCursor(): Boolean =
            !isFadingOut && !suppressCursorReveal

        private fun hideCursorImmediate() {
            handler.removeCallbacks(hideCursorRunnable)
            val view = cursor ?: return
            view.animate().cancel()
            view.animate().setListener(null)
            view.alpha = 0f
            view.visibility = View.INVISIBLE
            cursorRevealed = false
        }

        private fun revealCursorNow() {
            if (!allowPadCursor()) return
            val view = cursor ?: return
            handler.removeCallbacks(hideCursorRunnable)
            view.animate().cancel()
            view.animate().setListener(null)
            view.visibility = View.VISIBLE
            view.alpha = 1f
            cursorRevealed = true
            if (holdsCursorVisible()) return
            handler.postDelayed(hideCursorRunnable, TouchPadMath.CURSOR_IDLE_MS)
        }

        private fun fadeCursorOut() {
            val view = cursor ?: return
            if (!cursorRevealed) return
            if (holdsCursorVisible()) return
            cursorRevealed = false
            view.animate().cancel()
            view.animate().setListener(null)
            view.animate()
                .alpha(0f)
                .setDuration(TouchPadMath.FADE_MS)
                .setInterpolator(AccelerateInterpolator())
                .start()
        }

        private fun holdDragLive(): Boolean =
            dragLocal || dragStroke != null || dragStrokeBusy

        /**
         * Hold-drag is live: DOWN starts when hold is recognized, MOVE
         * follows the cursor, UP lands on lift. A replayed press-then-slide
         * after lift was late and often split into two gestures.
         */
        private fun finishHoldIfNeeded(immediate: Boolean = true) {
            if (!holdActive) return
            holdActive = false
            handler.removeCallbacks(holdReleaseRunnable)
            if (!holdDragLive()) {
                if (!holdMoved && !immediate) fallbackStationaryHold()
                return
            }
            if (immediate || holdMoved) {
                noteAutoPath(holdStartX, holdStartY, cursorX, cursorY)
                dispatchThreeFingerDragEnd(cursorX, cursorY)
                return
            }
            val held = SystemClock.uptimeMillis() - holdBeganAt
            val need = (
                ViewConfiguration.getLongPressTimeout() + TouchPadMath.HOLD_EXTRA_MS
                ).toLong()
            val remain = (need - held).coerceAtLeast(0L)
            if (remain == 0L) {
                noteAutoPress(
                    holdStartX,
                    holdStartY,
                    (SystemClock.uptimeMillis() - holdBeganAt).coerceAtLeast(1L),
                )
                dispatchThreeFingerDragEnd(holdStartX, holdStartY)
            } else {
                handler.postDelayed(holdReleaseRunnable, remain)
            }
        }

        private fun fallbackStationaryHold() {
            val duration = (
                ViewConfiguration.getLongPressTimeout() + TouchPadMath.HOLD_EXTRA_MS
                ).toLong()
            noteAutoPress(holdStartX, holdStartY, duration)
            if (pointOnPad(holdStartX, holdStartY)) {
                TouchPadScroller.contextClick(service, holdStartX, holdStartY)
                return
            }
            dispatchPress(holdStartX, holdStartY, duration)
        }

        private fun dispatchTap(x: Float, y: Float) {
            noteAutoTap(x, y)
            if (injectLocalTap(x, y)) return
            // Origin on our pad: subtract the pad origin to detect, then
            // click the layer underneath. A system gesture would hit the pad.
            if (pointOnPad(x, y)) {
                TouchPadScroller.clickThrough(service, x, y)
                return
            }
            if (TouchPadPageScroll.hitsInjectedOverlay(x, y)) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            val path = Path().apply { moveTo(x, y) }
            try {
                service.dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(
                            GestureDescription.StrokeDescription(
                                path,
                                0L,
                                TouchPadMath.TAP_DURATION_MS,
                            ),
                        )
                        .build(),
                    gestureCallback(),
                    null,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch tap gesture", e)
            }
        }

        private fun dispatchSecondaryTap(x: Float, y: Float) {
            if (AppWindowService.secondaryTapAt(x, y)) return
            if (WebViewService.secondaryTapAt(x, y)) return
            if (AiBrowserService.secondaryTapAt(x, y)) return
            if (TouchPadScroller.contextClick(service, x, y)) return
        }

        private fun dispatchBack() {
            if (AppWindowService.backAt(cursorX, cursorY)) return
            if (WebViewService.padGoBack()) return
            if (AiBrowserService.goBack()) return
            if (MainNavigationCoordinator.isProgramHome()) return
            AccessibilityBridge.back()
        }

        private fun injectLocalTap(x: Float, y: Float): Boolean =
            AppWindowService.tapAt(x, y) ||
                WebViewService.tapAt(x, y) ||
                AiBrowserService.tapAt(x, y)

        private fun pointOnPad(x: Float, y: Float): Boolean {
            val view = pad
            if (view != null && view.isAttachedToWindow && view.width > 0) {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                return TouchPadMath.padContainsScreen(
                    x,
                    y,
                    loc[0].toFloat(),
                    loc[1].toFloat(),
                    view.width.toFloat(),
                    view.height.toFloat(),
                )
            }
            val params = padParams ?: return false
            return TouchPadMath.padContainsScreen(
                x,
                y,
                params.x.toFloat(),
                params.y.toFloat(),
                params.width.toFloat(),
                params.height.toFloat(),
            )
        }

        private fun bindScrollNode() {
            if (scrollNode != null) return
            val now = SystemClock.uptimeMillis()
            if (scrollSearched && now - scrollFindAt < 80L) return
            scrollSearched = true
            scrollFindAt = now
            scrollNode = TouchPadScroller.findScrollable(service, cursorX, cursorY)
            scrollUsedNode = scrollUsedNode || scrollNode != null
        }

        /** Replay a recorded two-finger horizontal swipe through [SidebarDrawerRemote]. */
        private fun dispatchSidebar(
            dx: Float,
            durationMs: Long,
            gen: Int,
            onDone: () -> Unit,
        ) {
            if (abs(dx) < TouchPadMath.TAP_SLOP_PX ||
                !twoFingerBindsAvaSidebar() ||
                !SidebarDrawerRemote.beginEdgeSwipe()
            ) {
                onDone()
                return
            }
            val dur = durationMs.coerceAtLeast(80L)
            val ticks = (dur / 16L).toInt().coerceIn(1, 32)
            val slice = dx / ticks.toFloat()
            val velocity = dx / (dur / 1000f)
            var step = 1
            val tick = object : Runnable {
                override fun run() {
                    if (gen != playGen || auto.page != TouchPadAutoPage.PLAY) {
                        SidebarDrawerRemote.endEdgeSwipe(commit = false)
                        return
                    }
                    SidebarDrawerRemote.nudgeEdgeSwipe(slice)
                    if (step >= ticks) {
                        SidebarDrawerRemote.endEdgeSwipe(
                            commit = true,
                            velocityPxPerSec = velocity,
                        )
                        handler.postDelayed({
                            if (gen == playGen && auto.page == TouchPadAutoPage.PLAY) {
                                onDone()
                            }
                        }, TouchPadAuto.SIDEBAR_TAIL_MS)
                    } else {
                        step++
                        handler.postDelayed(this, 16L)
                    }
                }
            }
            handler.post(tick)
        }

        private fun enqueueScroll(dx: Float, dy: Float) {
            if (TouchPadMath.hypot(dx, dy) < 0.5f) return
            val now = SystemClock.uptimeMillis()
            if (!scrollLive) {
                scrollLive = true
                lastScrollAt = now
            } else if (now > lastScrollAt) {
                lastScrollAt = now
            }
            scrollAccX += dx
            scrollAccY += dy
            if (TouchPadPageScroll.nudge(cursorX, cursorY, dx, dy)) {
                scrollUsedPage = true
                return
            }
            if (TouchPadPageScroll.overlayUnderCursor(cursorX, cursorY)) {
                scrollUsedPage = true
                return
            }
            if (PadContentScrollRemote.nudge(cursorX, cursorY, dx, dy)) {
                scrollUsedPage = true
                return
            }
            bindScrollNode()
            if (scrollUsedNode) pumpLiveNodeScroll()
        }

        private fun pumpLiveNodeScroll() {
            val node = liveScrollNode() ?: return
            val density = service.resources.displayMetrics.density
            val step = TouchPadScroller.stepPx(node, density)
            while (TouchPadMath.hypot(scrollAccX, scrollAccY) >= step) {
                val mag = TouchPadMath.hypot(scrollAccX, scrollAccY).coerceAtLeast(1f)
                val takeX = scrollAccX * (step / mag)
                val takeY = scrollAccY * (step / mag)
                val amount = TouchPadScroller.scrollAmount(node, takeX, takeY, step)
                val ok = TouchPadScroller.scroll(node, takeX, takeY, amount)
                scrollAccX -= takeX
                scrollAccY -= takeY
                if (!ok) return
            }
        }

        private fun liveScrollNode(): AccessibilityNodeInfo? {
            val current = scrollNode
            if (current != null && runCatching { current.refresh() }.getOrDefault(false)) {
                return current
            }
            TouchPadScroller.recycle(current)
            scrollNode = null
            scrollSearched = false
            bindScrollNode()
            return scrollNode
        }

        private fun finishScrollQueue() {
            resetScrollQueue()
        }

        private fun resetScrollHaptics() {
            scrollHapticTravel = 0f
            scrollHapticStep = 0
        }

        private fun playScrollPressHaptic() {
            OverlayHaptics.play(
                service,
                overlayScrollHapticIntensity(0),
                surface,
            )
            scrollHapticStep = 1
            scrollHapticTravel = 0f
        }

        private fun noteScrollSlideHaptic(distancePx: Float) {
            val prev = scrollHapticTravel
            scrollHapticTravel += distancePx.coerceAtLeast(0f)
            val step = TouchPadMath.dp(
                TouchPadMath.SWIPE_TICK_DP,
                service.resources.displayMetrics.density,
            )
            if (!TouchPadMath.travelTickChanged(prev, scrollHapticTravel, step)) return
            OverlayHaptics.play(
                service,
                overlayScrollHapticIntensity(scrollHapticStep),
                surface,
            )
            scrollHapticStep++
        }

        private fun resetScrollQueue() {
            scrollLive = false
            scrollAccX = 0f
            scrollAccY = 0f
            scrollSearched = false
            scrollFindAt = 0L
            scrollUsedNode = false
            scrollUsedPage = false
            lastScrollVx = 0f
            lastScrollVy = 0f
            lastScrollAt = 0L
            TouchPadScroller.recycle(scrollNode)
            scrollNode = null
        }

        private fun dispatchPress(
            x: Float,
            y: Float,
            durationMs: Long,
            onDone: ((Boolean) -> Unit)? = null,
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                onDone?.invoke(false)
                return false
            }
            if (pointOnPad(x, y)) {
                onDone?.invoke(false)
                return false
            }
            if (injectOverlayPress(x, y, durationMs, onDone)) return true
            val path = Path().apply { moveTo(x, y) }
            return try {
                val accepted = service.dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(
                            GestureDescription.StrokeDescription(
                                path,
                                0L,
                                durationMs.coerceAtLeast(1L),
                            ),
                        )
                        .build(),
                    gestureCallback { completed -> onDone?.invoke(completed) },
                    null,
                )
                if (!accepted) {
                    onDone?.invoke(false)
                }
                accepted
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch press gesture", e)
                onDone?.invoke(false)
                false
            }
        }

        private fun dispatchSlide(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
            onDone: (() -> Unit)? = null,
        ): Boolean {
            noteAutoSlide(x1, y1, x2, y2, durationMs)
            return injectScreenSwipe(x1, y1, x2, y2, durationMs, onDone)
        }

        /**
         * One finger down, drag, up — the same stroke a real screen swipe uses.
         * Must run after pad fingers are up; a live overlay touch cancels it.
         */
        private fun injectScreenSwipe(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
            onDone: (() -> Unit)?,
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                onDone?.invoke()
                return false
            }
            if (pointOnPad(x1, y1) || pointOnPad(x2, y2)) {
                onDone?.invoke()
                return false
            }
            if (injectOverlaySlide(x1, y1, x2, y2, durationMs, onDone)) return true
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            return try {
                val accepted = service.dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(
                            GestureDescription.StrokeDescription(
                                path,
                                0L,
                                durationMs.coerceAtLeast(TouchPadMath.SCREEN_SWIPE_MIN_MS),
                            ),
                        )
                        .build(),
                    gestureCallback { onDone?.invoke() },
                    null,
                )
                if (!accepted) {
                    onDone?.invoke()
                }
                accepted
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch slide gesture", e)
                onDone?.invoke()
                false
            }
        }

        private fun injectOverlayPress(
            x: Float,
            y: Float,
            durationMs: Long,
            onDone: ((Boolean) -> Unit)?,
        ): Boolean {
            if (!TouchPadPageScroll.beginContentDrag(x, y)) return false
            handler.postDelayed({
                TouchPadPageScroll.endContentDrag()
                onDone?.invoke(true)
            }, durationMs.coerceAtLeast(1L))
            return true
        }

        private fun injectOverlaySlide(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
            onDone: (() -> Unit)?,
        ): Boolean {
            if (!TouchPadPageScroll.beginContentDrag(x1, y1)) return false
            val dur = durationMs.coerceAtLeast(16L)
            val ticks = (dur / 16L).toInt().coerceIn(1, 24)
            var step = 1
            val tick = object : Runnable {
                override fun run() {
                    val t = step.toFloat() / ticks.toFloat()
                    TouchPadPageScroll.nudgeContentDrag(
                        TouchPadMath.lerp(x1, x2, t),
                        TouchPadMath.lerp(y1, y2, t),
                    )
                    if (step >= ticks) {
                        TouchPadPageScroll.endContentDrag()
                        onDone?.invoke()
                    } else {
                        step++
                        handler.postDelayed(this, 16L)
                    }
                }
            }
            handler.post(tick)
            return true
        }

        private fun gestureCallback(
            then: ((Boolean) -> Unit)? = null,
        ): AccessibilityService.GestureResultCallback {
            return object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.post { then?.invoke(true) }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    handler.post { then?.invoke(false) }
                }
            }
        }

        private var dragStroke: GestureDescription.StrokeDescription? = null
        private var dragStrokeBusy = false
        private var dragLocal = false
        private var dragLastX = 0f
        private var dragLastY = 0f
        private var dragPendingX = 0f
        private var dragPendingY = 0f
        private var dragPendingEnd = false

        /**
         * Pad fingers cancel any live [dispatchGesture]. Wait for lift, then
         * either dismiss the shade or inject one complete status-bar swipe.
         */
        private fun commitShadeFromPad(padDy: Float) {
            handler.removeCallbacks(shadeCommitRunnable)
            shadeCommitPadDy = padDy
            handler.postDelayed(shadeCommitRunnable, TouchPadMath.SCREEN_SWIPE_SETTLE_MS)
        }

        private fun injectCommittedShadePull() {
            if (AccessibilityBridge.notificationsAreShowing()) {
                if (AccessibilityBridge.dismissNotifications()) return
                injectShadeSwipe(open = false)
                return
            }
            if (!injectShadeSwipe(open = true)) {
                AccessibilityBridge.notifications()
            }
        }

        private fun injectShadeSwipe(open: Boolean): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return if (open) {
                    AccessibilityBridge.notifications()
                } else {
                    AccessibilityBridge.dismissNotifications()
                }
            }
            val metrics = realMetrics()
            val density = service.resources.displayMetrics.density
            val grabY = TouchPadMath.shadeGrabY(statusBarHeightPx(), density)
            val maxY = (metrics.heightPixels - 8).toFloat().coerceAtLeast(grabY + 8f)
            val travel = TouchPadMath.shadeSwipeTravelPx(
                shadeCommitPadDy,
                padFaceHeightPx(),
                metrics.heightPixels,
            )
            val startY: Float
            val endY: Float
            if (open) {
                startY = grabY
                endY = (grabY + travel).coerceAtMost(maxY)
            } else {
                startY = TouchPadMath.shadeExpandedGrabY(metrics.heightPixels)
                    .coerceIn(grabY, maxY)
                endY = (startY - travel).coerceAtLeast(grabY)
            }
            if (abs(endY - startY) < TouchPadMath.THREE_FINGER_RECENTS_MIN_PX) {
                return false
            }
            val x = shadeSwipeX(startY, endY, metrics.widthPixels)
            val duration = TouchPadMath.screenSwipeDuration(
                (abs(endY - startY) / 2.5f).toLong(),
            )
            return injectShadeScreenSwipe(x, startY, x, endY, duration) { completed ->
                if (open) {
                    if (completed) {
                        AccessibilityBridge.markNotificationsOpen(true)
                    } else {
                        AccessibilityBridge.notifications()
                    }
                } else if (completed) {
                    AccessibilityBridge.markNotificationsOpen(false)
                }
            }
        }

        /** Vertical swipe X that misses the pad so the overlay does not eat the stroke. */
        private fun shadeSwipeX(startY: Float, endY: Float, screenWidthPx: Int): Float {
            val left = 8f
            val right = (screenWidthPx - 8).toFloat().coerceAtLeast(left)
            val mid = (left + right) / 2f
            val cursor = TouchPadMath.clamp(cursorX, left, right)
            val box = padScreenBox() ?: return cursor
            val lo = minOf(startY, endY)
            val hi = maxOf(startY, endY)
            if (hi < box.top || lo > box.bottom) return cursor
            val candidates = listOf(
                cursor,
                mid,
                left,
                right,
                (box.left - 16f).coerceAtLeast(left),
                (box.right + 16f).coerceAtMost(right),
            )
            return candidates.firstOrNull { x -> x < box.left || x > box.right } ?: mid
        }

        private fun padScreenBox(): TouchPadMath.Box? {
            val view = pad
            if (view != null && view.isAttachedToWindow && view.width > 0) {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                return TouchPadMath.Box(
                    loc[0].toFloat(),
                    loc[1].toFloat(),
                    loc[0] + view.width.toFloat(),
                    loc[1] + view.height.toFloat(),
                )
            }
            val params = padParams ?: return null
            return TouchPadMath.Box(
                params.x.toFloat(),
                params.y.toFloat(),
                params.x + params.width.toFloat(),
                params.y + params.height.toFloat(),
            )
        }

        /**
         * Status-bar swipe after pad lift. Never steals into [injectOverlaySlide];
         * that would drag Ava chrome instead of the shade.
         */
        private fun injectShadeScreenSwipe(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
            onDone: ((Boolean) -> Unit)?,
        ): Boolean {
            if (pointOnPad(x1, y1) || pointOnPad(x2, y2)) return false
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            return try {
                val accepted = service.dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(
                            GestureDescription.StrokeDescription(
                                path,
                                0L,
                                durationMs.coerceAtLeast(TouchPadMath.SCREEN_SWIPE_MIN_MS),
                            ),
                        )
                        .build(),
                    gestureCallback { completed -> onDone?.invoke(completed) },
                    null,
                )
                accepted
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch shade swipe", e)
                false
            }
        }

        private fun padFaceHeightPx(): Float {
            val density = service.resources.displayMetrics.density
            val total = padParams?.height?.toFloat()
                ?: surface?.height?.toFloat()
                ?: 1f
            return (total - TouchPadMath.titleBarPx(density).toFloat()).coerceAtLeast(1f)
        }

        private fun statusBarHeightPx(): Int {
            val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) {
                val px = service.resources.getDimensionPixelSize(id)
                if (px > 0) return px
            }
            return TouchPadMath.dp(24, service.resources.displayMetrics.density)
        }

        private fun noteShadeSlideHaptic(distancePx: Float) {
            val prev = shadeHapticTravel
            shadeHapticTravel += distancePx.coerceAtLeast(0f)
            val step = TouchPadMath.dp(
                TouchPadMath.SWIPE_TICK_DP,
                service.resources.displayMetrics.density,
            )
            if (!TouchPadMath.travelTickChanged(prev, shadeHapticTravel, step)) return
            OverlayHaptics.play(
                service,
                overlayScrollHapticIntensity(shadeHapticStep.coerceAtLeast(1)),
                surface,
            )
            shadeHapticStep++
        }

        /**
         * MacBook-style three-finger drag: hold at the cursor, then follow.
         * Overlay windows get a local pointer stream. The desktop uses a
         * continuing accessibility stroke (next segment waits for the callback).
         */
        private fun dispatchThreeFingerDragStart(x: Float, y: Float): Boolean {
            resetGestureDrag()
            dragLocal = false
            if (TouchPadPageScroll.beginContentDrag(x, y)) {
                dragLocal = true
                return true
            }
            if (pointOnPad(x, y)) return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            dragLastX = x
            dragLastY = y
            dragPendingX = x
            dragPendingY = y
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 24L, true)
            return dispatchContinuingStroke(stroke, ending = false)
        }

        private fun dispatchThreeFingerDragMove(x: Float, y: Float) {
            if (dragLocal) {
                TouchPadPageScroll.nudgeContentDrag(x, y)
                return
            }
            if (dragStroke == null && !dragStrokeBusy) return
            dragPendingX = x
            dragPendingY = y
            if (!dragStrokeBusy) flushGestureDrag()
        }

        private fun dispatchThreeFingerDragEnd(x: Float, y: Float) {
            if (dragLocal) {
                TouchPadPageScroll.endContentDrag()
                dragLocal = false
                return
            }
            dragPendingX = x
            dragPendingY = y
            dragPendingEnd = true
            if (!dragStrokeBusy) flushGestureDrag()
        }

        private fun flushGestureDrag() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                resetGestureDrag()
                return
            }
            val previous = dragStroke
            if (previous == null) {
                resetGestureDrag()
                return
            }
            val x = dragPendingX
            val y = dragPendingY
            val ending = dragPendingEnd
            if (!ending && TouchPadMath.hypot(x - dragLastX, y - dragLastY) < 0.5f) return
            val path = Path().apply {
                moveTo(dragLastX, dragLastY)
                lineTo(x, y)
            }
            dragLastX = x
            dragLastY = y
            dragPendingEnd = false
            val next = previous.continueStroke(path, 0L, 16L, !ending)
            dispatchContinuingStroke(next, ending)
        }

        private fun dispatchContinuingStroke(
            stroke: GestureDescription.StrokeDescription,
            ending: Boolean,
        ): Boolean {
            dragStrokeBusy = true
            dragStroke = if (ending) null else stroke
            try {
                val accepted = service.dispatchGesture(
                    GestureDescription.Builder().addStroke(stroke).build(),
                    gestureCallback { completed ->
                        dragStrokeBusy = false
                        if (!completed) {
                            resetGestureDrag()
                            return@gestureCallback
                        }
                        if (ending) {
                            resetGestureDrag()
                            return@gestureCallback
                        }
                        if (dragPendingEnd ||
                            TouchPadMath.hypot(dragPendingX - dragLastX, dragPendingY - dragLastY) >= 0.5f
                        ) {
                            flushGestureDrag()
                        }
                    },
                    null,
                )
                if (!accepted) {
                    resetGestureDrag()
                    return false
                }
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch three-finger drag", e)
                resetGestureDrag()
                return false
            }
        }

        private fun resetGestureDrag() {
            dragStroke = null
            dragStrokeBusy = false
            dragPendingEnd = false
        }

        private fun clampPad(
            params: WindowManager.LayoutParams,
            widthPx: Int,
            heightPx: Int,
            density: Float,
        ): Boolean {
            val metrics = realMetrics()
            val bounds = padBounds(metrics, widthPx, heightPx, density)
            val x = TouchPadMath.clamp(params.x, bounds.minX, bounds.maxX)
            val y = TouchPadMath.clamp(params.y, bounds.minY, bounds.maxY)
            val changed = x != params.x || y != params.y
            params.x = x
            params.y = y
            return changed
        }

        private fun savePadNorm(params: WindowManager.LayoutParams) {
            val metrics = realMetrics()
            val density = service.resources.displayMetrics.density
            val bounds = padBounds(metrics, params.width, params.height, density)
            prefs.saveNorm(
                TouchPadMath.toNorm(params.x, bounds.minX, bounds.maxX),
                TouchPadMath.toNorm(params.y, bounds.minY, bounds.maxY),
            )
        }

        private fun padBounds(
            metrics: DisplayMetrics,
            widthPx: Int,
            heightPx: Int,
            density: Float,
        ): TouchPadMath.Bounds {
            val (extraLeft, extraRight) = sidebarEdgeReserves()
            return TouchPadMath.overlayBounds(
                metrics.widthPixels,
                metrics.heightPixels,
                widthPx,
                heightPx,
                TouchPadMath.dp(TouchPadMath.MARGIN_H_DP, density),
                TouchPadMath.dp(TouchPadMath.MARGIN_V_DP, density),
                extraLeftPx = extraLeft,
                extraRightPx = extraRight,
            )
        }

        /** Keep the pad off the same left/right grab strip the sidebar uses. */
        private fun sidebarEdgeReserves(): Pair<Int, Int> {
            val layout = HomeLayoutSettingsMirror.readCached(service)
            if (!layout.enableSidebar) return 0 to 0
            val edge = SystemStyleEdgeHandleSpec.expandedHitWidthPx(service).roundToInt()
            return if (layout.sidebarPosition == SidebarPosition.RIGHT) {
                0 to edge
            } else {
                edge to 0
            }
        }

        @Suppress("DEPRECATION")
        private fun realMetrics(): DisplayMetrics {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            return metrics
        }
    }
}
