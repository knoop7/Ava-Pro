package com.example.ava.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import java.lang.ref.WeakReference

/**
 * Window + system-bar chrome that must match the live Ava theme before Compose draws.
 *
 * Activity recreation (rotation) briefly exposes the platform window layer; painting it here
 * avoids the default Material.Light white window and gray status/navigation bars.
 */
object AvaSystemChrome {
    /**
     * Last activity that applied immersive chrome. Overlay windows (browser sidebar)
     * have a Service context and cannot find an Activity by walking [View.getContext];
     * the listener on this activity is what actually owns the bars.
     */
    private var immersiveActivity: WeakReference<Activity>? = null

    /**
     * While true, [applyImmersiveMode] keeps the sidebar's edge-to-edge layout
     * (frost can fill a hidden bar strip). Which bars are actually hidden
     * follows [SystemBarsMode], same as the activity.
     */
    @Volatile
    private var suppressSystemBarsLikeOverlay = false

    /**
     * Kinds from [com.example.ava.notifications.FullscreenOverlayEscape.collectActive],
     * in that function's order. Empty means no trapping overlay is up, so the
     * activity paints ordinary chrome. The activity owns the bars for
     * [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] overlays.
     */
    private var fullscreenOverlayIds: List<String> = emptyList()

    private val overlayRoots = ArrayList<WeakReference<View>>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var modeListenerRegistered = false

    private val modeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key != SystemBarsMode.PREF_KEY) return@OnSharedPreferenceChangeListener
            mainHandler.post { reapplyCurrentChrome() }
        }

    fun isDarkMode(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, false)

    fun backgroundColor(darkMode: Boolean): Int =
        if (darkMode) android.graphics.Color.BLACK else 0xFFF8FAFC.toInt()

    fun isLandscape(configuration: Configuration): Boolean =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Synchronous chrome for the first frame after [Activity.onCreate].
     * Uses solid themed system bars; [applyImmersiveMode] may switch them transparent later.
     */
    fun applyEarlyWindowChrome(activity: Activity) {
        val darkMode = isDarkMode(activity)
        val color = backgroundColor(darkMode)
        val window = activity.window
        val isLandscape = isLandscape(activity.resources.configuration)

        window.setBackgroundDrawable(ColorDrawable(color))
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            @Suppress("DEPRECATION")
            window.statusBarColor = color
            @Suppress("DEPRECATION")
            window.navigationBarColor = color
        }

        applyModeLayoutFlags(window, isLandscape, SystemBarsMode.read(activity))
        applyBarIconContrast(window, darkMode)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    fun applyThemedWindowChrome(activity: Activity, darkMode: Boolean) {
        applySolidWindowBackground(activity, darkMode)
        applyBarIconContrast(activity.window, darkMode)
    }

    /** Solid themed window fill for status/navigation chrome. */
    fun applySolidWindowBackground(activity: Activity, darkMode: Boolean) {
        val color = backgroundColor(darkMode)
        val window = activity.window
        window.setBackgroundDrawable(ColorDrawable(color))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            window.statusBarColor = color
            @Suppress("DEPRECATION")
            window.navigationBarColor = color
        }
    }

    /** Re-read [SystemBarsMode] and paint the current activity. Used when Home Assistant changes the select. */
    fun reapplyImmersiveMode() {
        val activity = immersiveActivity?.get() ?: return
        val landscape = isLandscape(activity.resources.configuration)
        applyImmersiveMode(activity, landscape)
        installImmersiveModeListener(activity, landscape)
    }

    /**
     * Current trapping overlays, from [com.example.ava.notifications.FullscreenOverlayEscape].
     * A change reapplies chrome so the bars follow [SystemBarsMode] for whichever
     * overlay is up, and restores ordinary chrome when the last one closes.
     */
    fun onFullscreenOverlaysChanged(activeIds: List<String>) {
        if (activeIds == fullscreenOverlayIds) return
        fullscreenOverlayIds = activeIds.toList()
        reapplyCurrentChrome()
    }

    fun trackOverlayRoot(view: View) {
        overlayRoots.removeAll { it.get() == null || it.get() === view }
        overlayRoots.add(WeakReference(view))
    }

    fun applyImmersiveMode(activity: Activity, isLandscape: Boolean) {
        immersiveActivity = WeakReference(activity)
        ensureModeListener(activity)
        if (overlayChromeHeld()) {
            applyOverlayStyleImmersive(activity)
            return
        }
        val mode = SystemBarsMode.read(activity)
        if (mode != SystemBarsMode.HIDE_NAV) {
            applyConfiguredSystemBars(activity, isLandscape, mode)
            return
        }
        clearOverlayLayoutFlags(activity.window)

        val window = activity.window

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyLayoutFlags(window, isLandscape)
        if (!isLandscape) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        applyBarIconContrast(window, isDarkMode(activity))
        hideNavigationBars(window, isLandscape)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    fun installImmersiveModeListener(activity: Activity, isLandscape: Boolean) {
        if (overlayChromeHeld()) {
            installConfiguredImmersiveListener(
                activity,
                isLandscape,
                SystemBarsMode.read(activity),
                revealDelayMs = 50L,
            )
            return
        }
        val mode = SystemBarsMode.read(activity)
        if (mode != SystemBarsMode.HIDE_NAV) {
            installConfiguredImmersiveListener(activity, isLandscape, mode)
            return
        }
        val decorView = activity.window.decorView
        @Suppress("DEPRECATION")
        val pendingRunnable = Runnable { applyImmersiveMode(activity, isLandscape) }
        @Suppress("DEPRECATION")
        decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            // Portrait [HIDE_NAV] never asks for FULLSCREEN. Overlay chrome
            // installs the mode listener above, so a visible status bar here
            // is not a reveal to undo.
            val missingNav = visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0
            val missingFullscreen = isLandscape &&
                visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
            if (missingNav || missingFullscreen) {
                decorView.removeCallbacks(pendingRunnable)
                decorView.postDelayed(pendingRunnable, 500L)
            }
        }
    }

    fun clearImmersiveModeListener(activity: Activity) {
        @Suppress("DEPRECATION")
        activity.window.decorView.setOnSystemUiVisibilityChangeListener(null)
    }

    /**
     * Explicit status/navigation choice. [SystemBarsMode.HIDE_NAV] never reaches
     * here — that path stays the original portrait/landscape immersive flags.
     */
    private fun applyConfiguredSystemBars(
        activity: Activity,
        isLandscape: Boolean,
        mode: SystemBarsMode,
    ) {
        val hideStatus = mode.hidesStatus(isLandscape)
        val hideNav = mode.hidesNavigation
        val window = activity.window
        clearOverlayLayoutFlags(window)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyConfiguredLayoutFlags(window, hideStatus, hideNav)
        if (!hideStatus) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        val darkMode = isDarkMode(activity)
        val barColor = backgroundColor(darkMode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            window.statusBarColor =
                if (hideStatus) android.graphics.Color.TRANSPARENT else barColor
            @Suppress("DEPRECATION")
            window.navigationBarColor =
                if (hideNav) android.graphics.Color.TRANSPARENT else barColor
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = !hideNav
        }
        applyBarIconContrast(window, darkMode)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        var hideTypes = 0
        var showTypes = 0
        if (hideStatus) {
            hideTypes = hideTypes or WindowInsetsCompat.Type.statusBars()
        } else {
            showTypes = showTypes or WindowInsetsCompat.Type.statusBars()
        }
        if (hideNav) {
            hideTypes = hideTypes or WindowInsetsCompat.Type.navigationBars()
        } else {
            showTypes = showTypes or WindowInsetsCompat.Type.navigationBars()
        }
        if (hideTypes != 0) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(hideTypes)
        } else {
            @Suppress("DEPRECATION")
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_BARS_BY_SWIPE
        }
        if (showTypes != 0) {
            controller.show(showTypes)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun installConfiguredImmersiveListener(
        activity: Activity,
        isLandscape: Boolean,
        mode: SystemBarsMode,
        revealDelayMs: Long = 500L,
    ) {
        val decorView = activity.window.decorView
        val hideStatus = mode.hidesStatus(isLandscape)
        val hideNav = mode.hidesNavigation
        if (!hideStatus && !hideNav) {
            @Suppress("DEPRECATION")
            decorView.setOnSystemUiVisibilityChangeListener(null)
            return
        }
        @Suppress("DEPRECATION")
        val pendingRunnable = Runnable { applyImmersiveMode(activity, isLandscape) }
        @Suppress("DEPRECATION")
        decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            val missingNav = hideNav &&
                visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0
            val missingFullscreen = hideStatus &&
                visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
            if (missingNav || missingFullscreen) {
                decorView.removeCallbacks(pendingRunnable)
                decorView.postDelayed(pendingRunnable, revealDelayMs)
            }
        }
    }

    private fun applyModeLayoutFlags(window: Window, isLandscape: Boolean, mode: SystemBarsMode) {
        if (mode == SystemBarsMode.HIDE_NAV) {
            applyLayoutFlags(window, isLandscape)
        } else {
            applyConfiguredLayoutFlags(window, mode.hidesStatus(isLandscape), mode.hidesNavigation)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyConfiguredLayoutFlags(window: Window, hideStatus: Boolean, hideNav: Boolean) {
        applyConfiguredLayoutFlags(window.decorView, hideStatus, hideNav)
    }

    @Suppress("DEPRECATION")
    private fun applyConfiguredLayoutFlags(view: View, hideStatus: Boolean, hideNav: Boolean) {
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (hideNav || hideStatus) {
            flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        if (hideNav) flags = flags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        if (hideStatus) flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN
        view.systemUiVisibility = flags
    }

    /**
     * Show status/nav bars and drop IMMERSIVE_STICKY so a system dialog
     * (KeyChain chooser, document picker) can receive taps. Pair with
     * [clearImmersiveModeListener] before start, then [applyImmersiveMode]
     * + [installImmersiveModeListener] when the dialog finishes.
     *
     * API 21–29: clear HIDE_NAVIGATION / FULLSCREEN via systemUiVisibility.
     * API 30–36: WindowInsetsController.show.
     */
    fun showSystemBarsForDialog(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.getInsetsController(window, window.decorView).show(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars(),
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    /**
     * Edge-to-edge layout while a sidebar is on screen, so page frost can fill a
     * strip whose bar this mode hides. The bars themselves follow [SystemBarsMode].
     * False restores ordinary chrome unless a fullscreen overlay is still up.
     */
    fun setOverlayBarSuppression(enabled: Boolean, view: View? = null) {
        val changed = suppressSystemBarsLikeOverlay != enabled
        suppressSystemBarsLikeOverlay = enabled
        if (view != null) {
            applyOverlayStyleSystemUi(view)
            trackOverlayRoot(view)
        }
        val activity = immersiveActivity?.get() ?: view?.let(::findActivity)
        if (activity != null) {
            val landscape = isLandscape(activity.resources.configuration)
            if (overlayChromeHeld()) {
                applyOverlayStyleImmersive(activity)
            } else {
                applyImmersiveMode(activity, landscape)
            }
            if (changed) installImmersiveModeListener(activity, landscape)
        }
    }

    /**
     * API 30+ owns the bars on [WindowInsetsController], not the deprecated
     * `systemUiVisibility` bits. Overlay windows: a no-op unless this view's
     * window is focused ([FLAG_NOT_FOCUSABLE] overlays never drive the bars —
     * the activity behind them must, via [setOverlayBarSuppression]).
     */
    fun hideNavigationBars(view: View) {
        applyOverlayStyleSystemUi(view)
        trackOverlayRoot(view)
        val activity = immersiveActivity?.get() ?: findActivity(view) ?: return
        val landscape = isLandscape(activity.resources.configuration)
        if (overlayChromeHeld()) {
            applyOverlayStyleImmersive(activity)
        } else {
            applyImmersiveMode(activity, landscape)
        }
    }

    private fun findActivity(view: View): Activity? {
        var ctx: Context = view.context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /**
     * System-UI bits for an overlay root. Hides only the bars [SystemBarsMode]
     * hides. [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] windows ignore this;
     * the activity path in [applyOverlayStyleImmersive] is what moves the bars.
     * FLAG_FULLSCREEN on the overlay LayoutParams stays put — it is a z-order
     * bucket, not the bar switch.
     */
    fun applyOverlayStyleSystemUi(view: View) {
        val mode = SystemBarsMode.read(view.context)
        val landscape = isLandscape(view.resources.configuration)
        applyConfiguredLayoutFlags(view, mode.hidesStatus(landscape), mode.hidesNavigation)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        view.windowInsetsController?.let { controller ->
            applyPlatformBarVisibility(
                controller,
                mode.hidesStatus(landscape),
                mode.hidesNavigation,
            )
        }
    }

    /**
     * True when [visibility] is missing a bar the current [SystemBarsMode] wants hidden.
     * Callers re-assert only then, so a mode that shows a bar is not fought forever.
     */
    @Suppress("DEPRECATION")
    fun overlayBarsNeedReassert(view: View, visibility: Int): Boolean {
        val mode = SystemBarsMode.read(view.context)
        val landscape = isLandscape(view.resources.configuration)
        val missingNav = mode.hidesNavigation &&
            visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0
        val missingStatus = mode.hidesStatus(landscape) &&
            visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
        return missingNav || missingStatus
    }

    private fun applyOverlayStyleImmersive(activity: Activity) {
        val mode = SystemBarsMode.read(activity)
        val landscape = isLandscape(activity.resources.configuration)
        val hideStatus = mode.hidesStatus(landscape)
        val hideNav = mode.hidesNavigation
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyOverlayStyleSystemUi(window.decorView)
        // LAYOUT_NO_LIMITS lets frost fill a strip whose bar is hidden.
        // FLAG_FULLSCREEN is only for a hidden status bar. Overlay z-order
        // flags live on the overlay window, not here.
        val layoutFlags = overlayLayoutFlags(hideStatus)
        window.addFlags(layoutFlags)
        if (!hideStatus) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        if (hideStatus) {
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        }
        if (hideNav) {
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }
        window.attributes = window.attributes.apply {
            flags = if (hideStatus) {
                flags or layoutFlags
            } else {
                (flags or layoutFlags) and WindowManager.LayoutParams.FLAG_FULLSCREEN.inv()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val darkMode = isDarkMode(activity)
        val barColor = backgroundColor(darkMode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            window.statusBarColor =
                if (hideStatus) android.graphics.Color.TRANSPARENT else barColor
            @Suppress("DEPRECATION")
            window.navigationBarColor =
                if (hideNav) android.graphics.Color.TRANSPARENT else barColor
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = !hideNav
        }
        applyBarIconContrast(window, darkMode)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        applyCompatBarVisibility(controller, hideStatus, hideNav)
    }

    private fun overlayChromeHeld(): Boolean =
        suppressSystemBarsLikeOverlay || fullscreenOverlayIds.isNotEmpty()

    private fun ensureModeListener(context: Context) {
        if (modeListenerRegistered) return
        modeListenerRegistered = true
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(modeListener)
    }

    private fun reapplyCurrentChrome() {
        val activity = immersiveActivity?.get()
        if (activity != null) {
            val landscape = isLandscape(activity.resources.configuration)
            applyImmersiveMode(activity, landscape)
            installImmersiveModeListener(activity, landscape)
        }
        val roots = overlayRoots.mapNotNull { it.get() }
        overlayRoots.clear()
        roots.forEach { view ->
            overlayRoots.add(WeakReference(view))
            applyOverlayStyleSystemUi(view)
        }
    }

    private fun applyCompatBarVisibility(
        controller: WindowInsetsControllerCompat,
        hideStatus: Boolean,
        hideNav: Boolean,
    ) {
        var hideTypes = 0
        var showTypes = 0
        if (hideStatus) {
            hideTypes = hideTypes or WindowInsetsCompat.Type.statusBars()
        } else {
            showTypes = showTypes or WindowInsetsCompat.Type.statusBars()
        }
        if (hideNav) {
            hideTypes = hideTypes or WindowInsetsCompat.Type.navigationBars()
        } else {
            showTypes = showTypes or WindowInsetsCompat.Type.navigationBars()
        }
        if (hideTypes != 0) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(hideTypes)
        } else {
            @Suppress("DEPRECATION")
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_BARS_BY_SWIPE
        }
        if (showTypes != 0) controller.show(showTypes)
    }

    private fun applyPlatformBarVisibility(
        controller: android.view.WindowInsetsController,
        hideStatus: Boolean,
        hideNav: Boolean,
    ) {
        var hideTypes = 0
        var showTypes = 0
        if (hideStatus) {
            hideTypes = hideTypes or WindowInsets.Type.statusBars()
        } else {
            showTypes = showTypes or WindowInsets.Type.statusBars()
        }
        if (hideNav) {
            hideTypes = hideTypes or WindowInsets.Type.navigationBars()
        } else {
            showTypes = showTypes or WindowInsets.Type.navigationBars()
        }
        if (hideTypes != 0) {
            controller.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(hideTypes)
        } else {
            @Suppress("DEPRECATION")
            controller.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_SWIPE
        }
        if (showTypes != 0) controller.show(showTypes)
    }

    private fun clearOverlayLayoutFlags(window: Window) {
        window.clearFlags(OVERLAY_BAR_FLAGS_TO_CLEAR)
        window.attributes = window.attributes.apply {
            flags = flags and OVERLAY_BAR_FLAGS_TO_CLEAR.inv()
        }
    }

    private fun hideNavigationBars(window: Window, isLandscape: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isLandscape) {
            controller.hide(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars(),
            )
        } else {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    @Suppress("DEPRECATION")
    private fun applyLayoutFlags(window: Window, isLandscape: Boolean) {
        val uiOptions = if (isLandscape) {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        window.decorView.systemUiVisibility = uiOptions
    }

    private fun applyBarIconContrast(window: Window, darkMode: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val lightBars = !darkMode
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
    }
}

@Suppress("DEPRECATION")
private fun overlayLayoutFlags(hideStatus: Boolean): Int {
    var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
    if (hideStatus) flags = flags or WindowManager.LayoutParams.FLAG_FULLSCREEN
    return flags
}

@Suppress("DEPRECATION")
private val OVERLAY_BAR_FLAGS_TO_CLEAR =
    WindowManager.LayoutParams.FLAG_FULLSCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
        WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
