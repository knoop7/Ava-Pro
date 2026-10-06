package com.example.ava.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.example.ava.R
import com.example.ava.appwindow.AppWindowGeometryStore
import com.example.ava.ui.glass.LiquidGlass
import com.example.ava.settings.DarkModeManager
import com.example.ava.ui.haptic.OverlayHaptics
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.appwindow.AppWindowPermissions
import com.example.ava.appwindow.AppWindowScrcpy
import com.example.ava.appwindow.FreeformAppWindow
import com.example.ava.appwindow.ScrcpyControl
import com.example.ava.touchpad.TouchPadMath
import com.example.ava.touchpad.TouchPadPageScroll
import com.example.ava.ui.AvaToast
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hosts one **independent** floating overlay per app, each mirroring that app on
 * its own scrcpy virtual display. Windows are never reused across apps: opening
 * a second app spawns a second window; re-opening an app just fronts its
 * existing one. Each window fills itself with the mirror, floats an auto-hiding
 * title bar (white stroke-style pin / rotate / close, aligned in a row) on top,
 * and shows a bottom-right corner bracket for resizing. Windows are born
 * pinned (the title-bar pin toggles it off) and while pinned keep their
 * z-order through [OverlayZOrderCoordinator]'s reassert passes (see
 * [bringPinnedToFront]) rather than polling. Windows join the Esper / FAB
 * overlay sublayer ([OverlayZOrderCoordinator.voiceSublayerFlags]). A fresh add
 * or user raise would still bury captions —
 * [OverlayZOrderCoordinator.raiseVoiceAboveAppWindows] climbs those, not the
 * mic (a small floating window must not twitch the disc). Corner-bracket or
 * three-finger pinch resizing rebuilds the virtual display at the new window
 * size so the app re-lays-out. Every session — the cold-start one included —
 * runs under a first-frame watchdog and a liveness probation; failures
 * escalate restart → revert-to-last-good → give up, so a black or crashed
 * mirror heals itself instead of waiting for a manual resize.
 * Portrait and landscape remember their own frame per app. See
 * [AppWindowScrcpy] for the plumbing.
 */
class AppWindowService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var titleBarHeightPx: Int = 0

    private val windows = mutableListOf<Win>()
    private var padDragWin: Win? = null
    private var padOriginX = 0
    private var padOriginY = 0
    private var padSnapUnlessTop = false
    private var padSnapAnimator: ValueAnimator? = null
    private var padPinchWin: Win? = null
    private var padPinchOriginX = 0
    private var padPinchOriginY = 0
    private var padPinchOriginW = 0
    private var padPinchOriginH = 0
    private var padPinchCenterX = 0f
    private var padPinchCenterY = 0f
    private var padScrollWin: Win? = null
    private var padContentDragWin: Win? = null
    private val homePrefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
    private val darkModeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_DARK_MODE) windows.forEach { it.applyChrome() }
    }

    // All session starts/stops run on this one thread, so a server is never
    // launched while another (this window's previous one) is still being killed.
    private val sessionExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppWindowSession").apply { isDaemon = true }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        titleBarHeightPx = dp(34f).roundToInt()
        // Clear orphan window servers left behind by a previous process. Live
        // windows use random scids, so this only ever runs before we own any.
        runShellAsync("pkill -f 'app_process.*ava-appwin-server[.]jar' || true", "AppWindowOrphanCleanup")
        homePrefs.registerOnSharedPreferenceChangeListener(darkModeListener)
        LiquidGlass.ensureLoaded(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)
        if (pkg.isNullOrBlank()) {
            if (windows.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        // Rapid repeated opens of the same app mean the last window was stuck or
        // never showed video — self-heal instead of handing back the same dud.
        val needsRepair = recordOpenAndCheckRepair(pkg)
        schedulePermGuard()
        val existing = windows.firstOrNull { it.pkg == pkg }
        if (existing != null) {
            // Most-recently-used goes last: coordinator reasserts raise windows
            // in list order, so the window the user just asked for stays on top
            // of the other floating windows after every restack.
            windows.remove(existing)
            windows.add(existing)
            if (needsRepair) existing.repair() else existing.front()
        } else {
            if (needsRepair) hardRepairCleanup(pkg)
            val win = Win(pkg)
            windows.add(win)
            if (!win.build()) {
                windows.remove(win)
                if (windows.isEmpty()) stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        windows.forEach { it.onConfigChanged() }
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        val snapshot = windows.toList()
        windows.clear()
        snapshot.forEach { it.destroy() }
        OverlayZOrderCoordinator.syncFabForAod()
        homePrefs.unregisterOnSharedPreferenceChangeListener(darkModeListener)
        mainHandler.removeCallbacksAndMessages(null)
        sessionExecutor.shutdown()
        super.onDestroy()
    }

    // ---- shared helpers ----

    private fun safeUpdate(view: View, lp: WindowManager.LayoutParams) {
        try {
            if (view.isAttachedToWindow) windowManager?.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    private fun clampToScreen(lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        lp.width = lp.width.coerceIn(dp(200f).roundToInt(), dm.widthPixels)
        lp.height = lp.height.coerceIn(dp(200f).roundToInt(), dm.heightPixels)
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - lp.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - lp.height).coerceAtLeast(0))
    }

    private fun resolveLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun isAppProcessAlive(pkg: String): Boolean = when {
        RootUtils.isRootAvailable() -> runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof $pkg")).waitFor() == 0
        }.getOrDefault(true)
        ShizukuUtils.isShizukuPermissionGranted() ->
            ShizukuUtils.executeCommand("pidof $pkg").first == 0
        else -> true // No shell to ask; assume alive rather than flap.
    }

    private fun runShellAsync(command: String, threadName: String) {
        Thread({ execShellBlocking(command) }, threadName).apply { isDaemon = true; start() }
    }

    private fun execShellBlocking(command: String) {
        when {
            RootUtils.isRootAvailable() -> runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
            }
            ShizukuUtils.isShizukuPermissionGranted() -> ShizukuUtils.executeCommand(command)
            else -> {}
        }
    }

    // ---- trapped permission-dialog guard ----

    private val permGuardRunnable = Runnable { runPermGuard() }
    private var lastPermRescueToastAt = 0L

    private fun schedulePermGuard() {
        mainHandler.removeCallbacks(permGuardRunnable)
        mainHandler.postDelayed(permGuardRunnable, PERM_GUARD_PERIOD_MS)
    }

    /**
     * While any window is open, watch for a runtime-permission dialog stuck on
     * a *virtual* display. Such a dialog force-hides every overlay window in
     * the system (`HIDE_NON_SYSTEM_OVERLAY_WINDOWS`, anti-tapjacking) — the
     * mirror that would show it included — so all floating windows silently go
     * invisible with nothing for the user to answer. Verified live on the vivo
     * Android 11 device. Rescue: re-grant the windowed apps' permissions (so
     * the interrupted app's retry passes without a new prompt), then kill the
     * dialog; the windows reappear the same instant. Pre-granting at launch
     * ([AppWindowPermissions.grantAll]) makes this a rare second line of
     * defense. Dialogs on the main display (id 0) are the user's to answer and
     * are left alone.
     */
    private fun runPermGuard() {
        if (windows.isEmpty()) return
        val backend = when {
            RootUtils.isRootAvailable() -> "root"
            ShizukuUtils.isShizukuPermissionGranted() -> "shizuku"
            else -> null
        }
        if (backend == null) {
            schedulePermGuard()
            return
        }
        val pkgs = windows.map { it.pkg }
        Thread({
            val display = AppWindowPermissions.permissionDialogDisplay(backend)
            if (display > 0) {
                Log.w(TAG, "permission dialog trapped on display $display; rescuing windows")
                pkgs.forEach { AppWindowPermissions.grantAll(it, backend, force = true) }
                AppWindowPermissions.dismissPermissionDialog(backend)
                mainHandler.post { maybeToastPermRescue() }
            }
            mainHandler.post { if (windows.isNotEmpty()) schedulePermGuard() }
        }, "AppWindowPermGuard").apply {
            isDaemon = true
            start()
        }
    }

    private fun maybeToastPermRescue() {
        val now = System.currentTimeMillis()
        if (now - lastPermRescueToastAt < PERM_RESCUE_TOAST_COOLDOWN_MS) return
        lastPermRescueToastAt = now
        AvaToast.show(
            applicationContext,
            R.string.app_window_perm_rescued,
            durationMs = AvaToast.LONG_MS,
        )
    }

    /**
     * Record this open of [pkg] and report whether repeated rapid opens warrant a
     * hard repair. History is persisted so it survives the service being torn
     * down when the last window closes (the common close→reopen loop).
     */
    private fun recordOpenAndCheckRepair(pkg: String): Boolean {
        val prefs = getSharedPreferences(REOPEN_PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val recent = prefs.getString(pkg, "").orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { now - it in 0 until REOPEN_WINDOW_MS } + now
        if (recent.size >= REPAIR_THRESHOLD) {
            prefs.edit().remove(pkg).apply() // reset so it doesn't retrigger every open
            return true
        }
        prefs.edit().putString(pkg, recent.joinToString(",")).apply()
        return false
    }

    /**
     * Before rebuilding a closed-then-reopened window, clear the app's bad state
     * (and any zombie window server, but only when no other live window would be
     * harmed). Enqueued on the session thread so it finishes before the new
     * session starts on that same thread.
     */
    private fun hardRepairCleanup(pkg: String) {
        // Silent, automatic self-heal — no toast.
        val nukeServers = windows.isEmpty()
        sessionExecutor.execute {
            execShellBlocking("am force-stop $pkg")
            if (nukeServers) execShellBlocking("pkill -f 'app_process.*ava-appwin-server[.]jar' || true")
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun evenClamp(value: Int): Int {
        // H.264 hardware encoders (Qualcomm OMX) reject widths that aren't a
        // multiple of 16. even-only clamping produced 936x992 here, which
        // passed scrcpy's multiple-of-8 check then died in the encoder.
        val v = value.coerceAtLeast(208)
        return v - (v % 16)
    }

    /**
     * One floating window: its own view tree, layout params, scrcpy session,
     * pin state and rebuild probation. Everything is per-instance so multiple
     * windows are fully independent.
     */
    private inner class Win(val pkg: String) {
        private lateinit var root: FrameLayout
        private lateinit var params: WindowManager.LayoutParams
        private var textureView: TextureView? = null
        private var currentSurface: Surface? = null
        private var savedTexture: SurfaceTexture? = null
        private var titleBar: FrameLayout? = null
        private var titleLabel: TextView? = null
        private var pinButton: ImageView? = null
        private var rotateButton: ImageView? = null
        private var closeButton: ImageView? = null
        private var resizeHandle: View? = null
        private var closing = false
        private var session: AppWindowScrcpy? = null
        /** Windows are born pinned: floating on top is the whole point, and the
         *  user can always demote one via the title-bar pin toggle. */
        private var pinned = true
        /** Snapshot window masking a user-triggered restack — see [raiseToFrontMasked]. */
        private var raiseCover: ImageView? = null
        private val dropCoverRunnable = Runnable { dropRaiseCover() }
        /**
         * Parked snapshot over title-bar auto-hide. TextureView + sibling alpha
         * twitches the mirror; this cover fades instead, then stays GONE until
         * the 10-minute reap so the next hide does not pay addView.
         */
        private var hideCover: ImageView? = null
        private var hideCoverLp: WindowManager.LayoutParams? = null
        private val reapHideCoverRunnable = Runnable { destroyHideCover() }
        /** [OverlayZOrderCoordinator.stackGeneration] at our last restack, so a
         *  coordinator reassert only pays the remove+add when something else
         *  actually moved (same guard the other overlays use). */
        private var restackedAtGeneration = -1L

        private var videoW = 0
        private var videoH = 0
        private var contentLeft = 0f
        private var contentTop = 0f
        private var contentW = 0f
        private var contentH = 0f
        private var padContentDragX = 0f
        private var padContentDragY = 0f
        private var padContentDragDown = false
        private var padScrollX = 0f
        private var padScrollY = 0f
        private var padScrollDown = false
        private var padScrollWheel = false

        /** Consecutive failed sessions (died or never showed a frame). Reset by a
         *  probation pass; drives the restart → revert → give-up escalation. */
        private var failureStreak = 0
        private var lastGoodW = 0
        private var lastGoodH = 0
        private var probing = false
        /** Whether the *current* session has rendered at least one frame. */
        private var firstFrameSeen = false
        /** Whether *any* session for this window has ever painted a frame. Unlike
         *  [lastGoodW] (seeded to the window size on the first start, so never a
         *  reliable "did we render" signal) this stays false until a real frame
         *  arrives — it's what decides a mirror-never-came-up degrade. */
        private var everRenderedFrame = false

        private val hideTitleBarRunnable = Runnable { hideTitleBar() }
        private val hideHandleRunnable = Runnable { hideResizeHandle() }
        private val probeRunnable = Runnable { probeMirroredApp(firstCheck = true) }
        private val reprobeRunnable = Runnable { probeMirroredApp(firstCheck = false) }
        // First-frame watchdog: a session that never paints (server encoder died,
        // launch race, connect timeout) used to leave a black window forever —
        // the user's manual resize "fixed" it only because resize restarts the
        // session. Now the restart happens by itself.
        private val firstFrameWatchdog = Runnable {
            if (session != null && !firstFrameSeen) {
                Log.w(TAG, "no first frame from $pkg, restarting session")
                registerSessionFailure()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        fun build(): Boolean {
            val wm = windowManager ?: return false
            val geometry = AppWindowGeometryStore.read(this@AppWindowService, pkg)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val lp = WindowManager.LayoutParams(
                geometry.width,
                geometry.height,
                layoutType,
                // HARDWARE_ACCELERATED is required for TextureView. NOT_TOUCH_MODAL
                // lets taps outside the frame reach the dashboard. FLAG_FULLSCREEN
                // joins the Esper / FAB WM sublayer so the mic can climb this window.
                OverlayZOrderCoordinator.voiceSublayerFlags() or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = geometry.x
                y = geometry.y
                OverlayZOrderCoordinator.applyVoiceSublayerLayout(this)
                // No enter/exit animation: restacks re-add this window, and the
                // system's default overlay animation would make it twitch.
                windowAnimations = R.style.AppWindowNoAnimation
            }
            // Cascade fresh windows so a second app doesn't land exactly on the first.
            if (!AppWindowGeometryStore.hasSaved(this@AppWindowService, pkg)) {
                val step = (dp(26f) * windows.size).roundToInt()
                lp.x += step
                lp.y += step
                clampToScreen(lp)
            }
            params = lp

            val container = FrameLayout(this@AppWindowService).apply {
                alpha = 0f
                background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                    cornerRadius = dp(14f)
                }
                clipToOutline = true
            }

            val texture = TextureView(this@AppWindowService).apply {
                isOpaque = false
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            textureView = texture

            // While the title bar is hidden it stops receiving touches, so this thin
            // strip at the very top reveals the bar (and drags the window) instead.
            val revealStrip = View(this@AppWindowService).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(18f).roundToInt(),
                )
            }

            val bar = FrameLayout(this@AppWindowService).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    titleBarHeightPx,
                )
                // Flat tint over the mirrored app. Glass here stacked a rim/sheen
                // on a 34dp chrome strip and read as a second material on the window.
                background = GradientDrawable().apply { setColor(0x8024282C.toInt()) }
            }
            titleBar = bar
            val title = TextView(this@AppWindowService).apply {
                text = resolveLabel(pkg)
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f).roundToInt(), 0, dp(120f).roundToInt(), 0)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            // Right side, outer to inner: close (X), rotate, pin — same size, all
            // white stroke-style glyphs, evenly spaced so they read as one row.
            val iconPad = dp(7.5f).roundToInt()
            val pin = ImageView(this@AppWindowService).apply {
                // Filled glyph from the start — pinned is the default state.
                setImageResource(R.drawable.ic_appwin_pin_filled)
                setColorFilter(Color.WHITE)
                setPadding(iconPad, iconPad, iconPad, iconPad)
                layoutParams = FrameLayout.LayoutParams(
                    titleBarHeightPx,
                    titleBarHeightPx,
                    Gravity.END,
                ).apply { rightMargin = titleBarHeightPx * 2 }
                setOnClickListener {
                    OverlayHaptics.click(this@AppWindowService, this)
                    setPinned(!pinned)
                    scheduleTitleBarHide()
                }
            }
            pinButton = pin
            val rotate = ImageView(this@AppWindowService).apply {
                setImageResource(R.drawable.ic_appwin_rotate)
                setColorFilter(Color.WHITE)
                setPadding(iconPad, iconPad, iconPad, iconPad)
                layoutParams = FrameLayout.LayoutParams(
                    titleBarHeightPx,
                    titleBarHeightPx,
                    Gravity.END,
                ).apply { rightMargin = titleBarHeightPx }
                setOnClickListener {
                    OverlayHaptics.click(this@AppWindowService, this)
                    rotateWindow()
                    scheduleTitleBarHide()
                }
            }
            rotateButton = rotate
            val close = ImageView(this@AppWindowService).apply {
                setImageResource(R.drawable.ic_appwin_close)
                setColorFilter(Color.WHITE)
                setPadding(iconPad, iconPad, iconPad, iconPad)
                layoutParams = FrameLayout.LayoutParams(
                    titleBarHeightPx,
                    titleBarHeightPx,
                    Gravity.END,
                )
                setOnClickListener {
                    OverlayHaptics.click(this@AppWindowService, this)
                    close()
                }
            }
            closeButton = close
            titleLabel = title
            bar.addView(title)
            bar.addView(pin)
            bar.addView(rotate)
            bar.addView(close)

            val handleSize = dp(40f).roundToInt()
            val handle = CornerBracketView(this@AppWindowService).apply {
                layoutParams = FrameLayout.LayoutParams(
                    handleSize,
                    handleSize,
                    Gravity.END or Gravity.BOTTOM,
                )
            }
            resizeHandle = handle

            container.addView(texture)
            container.addView(revealStrip)
            container.addView(bar)
            container.addView(handle)
            root = container
            applyChrome()

            val barInteraction: (Boolean) -> Unit = { active ->
                if (active) showTitleBar() else scheduleTitleBarHide()
            }
            attachMove(title, barInteraction)
            attachMove(revealStrip, barInteraction)
            attachResize(handle)
            attachSurfaceTouch(texture)
            texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyMirrorTransform() }

            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    val saved = savedTexture
                    if (saved != null && saved !== st) {
                        // Re-attached after a pin restack: keep decoding into the
                        // original texture instead of restarting the whole session.
                        texture.setSurfaceTexture(saved)
                        return
                    }
                    savedTexture = st
                    currentSurface = Surface(st)
                    startSession()
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = false

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    if (!firstFrameSeen) {
                        firstFrameSeen = true
                        everRenderedFrame = true
                        mainHandler.removeCallbacks(firstFrameWatchdog)
                    }
                }
            }

            return try {
                wm.addView(container, lp)
                OverlayZOrderCoordinator.noteWindowAdded()
                restackedAtGeneration = OverlayZOrderCoordinator.stackGeneration
                OverlayZOrderCoordinator.raiseVoiceAboveAppWindows()
                container.animate()
                    .alpha(1f)
                    .setDuration(FADE_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                showTitleBar()
                scheduleTitleBarHide()
                showResizeHandle()
                scheduleHandleHide()
                true
            } catch (e: Exception) {
                Log.e(TAG, "addView failed", e)
                false
            }
        }

        /** Bring an already-open window to the foreground and flash its chrome. */
        fun front() {
            if (closing) return
            raiseToFrontMasked()
            showTitleBar()
            scheduleTitleBarHide()
        }

        /** Self-heal a window that looks stuck (user reopened it repeatedly):
         *  drop any probation state and cleanly restart the session, which tears
         *  down this window's server by scid and relaunches the app fresh.
         *  Silent and automatic — no toast. */
        fun repair() {
            if (closing) return
            cancelProbation()
            failureStreak = 0
            restartSession()
            front()
        }

        // ---- session lifecycle ----

        /** [displayW]/[displayH] override the virtual-display resolution; by
         *  default it follows the window size. Main thread only. Every session
         *  (cold start included) runs under the first-frame watchdog plus a
         *  liveness probation, so a session that dies or stays black recovers
         *  by itself instead of leaving a black window. */
        private fun startSession(displayW: Int = 0, displayH: Int = 0) {
            if (closing) return
            if (session != null) return
            val surface = currentSurface ?: return
            val dpi = resources.displayMetrics.densityDpi
            val w = evenClamp(if (displayW > 0) displayW else params.width)
            val h = evenClamp(if (displayH > 0) displayH else params.height)
            videoW = w
            videoH = h
            if (lastGoodW <= 0 || lastGoodH <= 0) {
                lastGoodW = w
                lastGoodH = h
            }
            applyMirrorTransform()
            val s = AppWindowScrcpy(applicationContext, pkg, w, h, dpi)
            s.onSessionEnded = { mainHandler.post { onSessionDied(s) } }
            session = s
            sessionExecutor.execute {
                if (!s.start(surface)) mainHandler.post { onSessionDied(s) }
            }
            firstFrameSeen = false
            mainHandler.removeCallbacks(firstFrameWatchdog)
            mainHandler.postDelayed(firstFrameWatchdog, FIRST_FRAME_TIMEOUT_MS)
            beginProbation()
        }

        private fun restartSession(displayW: Int = 0, displayH: Int = 0) {
            if (closing) return
            val old = session
            session = null
            if (old != null) sessionExecutor.execute { old.stopBlocking() }
            startSession(displayW, displayH)
        }

        // ---- session health: probation, first-frame watchdog fallback ----

        private fun beginProbation() {
            cancelProbation()
            probing = true
            mainHandler.postDelayed(probeRunnable, PROBATION_MS)
        }

        private fun cancelProbation() {
            probing = false
            mainHandler.removeCallbacks(probeRunnable)
            mainHandler.removeCallbacks(reprobeRunnable)
        }

        private fun probeMirroredApp(firstCheck: Boolean) {
            if (!probing) return
            val s = session ?: return
            sessionExecutor.execute {
                val alive = s.isActive() && isAppProcessAlive(pkg)
                mainHandler.post {
                    if (!probing || s !== session) return@post
                    when {
                        // Healthy means the app process survived *and* we have
                        // actually rendered it — alive-but-black must not reset
                        // the streak or the watchdog loop would never converge.
                        alive && firstFrameSeen -> {
                            probing = false
                            failureStreak = 0
                            lastGoodW = videoW
                            lastGoodH = videoH
                        }
                        firstCheck -> mainHandler.postDelayed(reprobeRunnable, REPROBE_MS)
                        else -> registerSessionFailure()
                    }
                }
            }
        }

        private fun onSessionDied(dead: AppWindowScrcpy) {
            if (dead !== session) return
            session = null
            if (closing) return
            registerSessionFailure()
        }

        /**
         * A session died unexpectedly or never produced a frame. Escalate:
         * restart in place → revert to the last known-good frame (one toast) →
         * stop retrying, so a doomed app can't keep us force-stop-looping it.
         * The streak only resets on a probation pass or a user action
         * (resize / rotate / reopen all start a fresh monitored session).
         */
        private fun registerSessionFailure() {
            if (closing) return
            cancelProbation()
            mainHandler.removeCallbacks(firstFrameWatchdog)
            failureStreak++
            when {
                failureStreak > MAX_SESSION_FAILURES -> {
                    if (!everRenderedFrame) {
                        // Never rendered a single frame across the whole
                        // escalation: the mirror pipeline can't come up on this
                        // device at all (e.g. SELinux blocks even the relayed
                        // socket, or the virtual display can't be created). Don't
                        // leave a black window — degrade to a system freeform
                        // window, or fullscreen if even that won't take.
                        Log.w(TAG, "$pkg mirror never started; degrading off scrcpy")
                        degradeOffMirror()
                    } else {
                        Log.w(TAG, "$pkg failed $failureStreak sessions; waiting for user")
                        if (session?.isActive() != true) session = null
                    }
                }
                failureStreak == MAX_SESSION_FAILURES -> {
                    Toast.makeText(
                        this@AppWindowService,
                        R.string.app_window_resize_fallback,
                        Toast.LENGTH_LONG,
                    ).show()
                    revertToLastGood()
                }
                else -> restartSession()
            }
        }

        /**
         * The scrcpy mirror can't come up on this device. Tear this black
         * window down — [destroy] kills the server, which destroys its virtual
         * display and the app instance on it, so there's no stray fullscreen
         * copy left behind and no `am force-stop` race with the relaunch — then
         * reopen the app as a system freeform window ([FreeformAppWindow], which
         * itself falls back to a plain fullscreen start when freeform won't
         * take). A one-off toast explains the switch.
         */
        private fun degradeOffMirror() {
            val pkgName = pkg
            if (padPinchWin === this) padPinchWin = null
            if (padDragWin === this) padDragWin = null
            if (padScrollWin === this) padScrollWin = null
            if (padContentDragWin === this) padContentDragWin = null
            endPadScroll()
            endPadContentDrag()
            destroy()
            windows.remove(this)
            if (windows.isEmpty()) stopSelf()
            AvaToast.show(
                applicationContext,
                R.string.app_window_mirror_blocked,
                tag = AvaToast.APP_WINDOW_TAG,
                durationMs = AvaToast.LONG_MS,
            )
            val launched = FreeformAppWindow.launch(applicationContext, pkgName) {
                AvaToast.show(
                    applicationContext,
                    R.string.app_window_freeform_reboot,
                    tag = AvaToast.APP_WINDOW_TAG,
                    durationMs = AvaToast.LONG_MS,
                )
            }
            if (!launched) {
                applicationContext.packageManager
                    .getLaunchIntentForPackage(pkgName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { runCatching { applicationContext.startActivity(it) } }
            }
        }

        private fun revertToLastGood() {
            if (lastGoodW <= 0 || lastGoodH <= 0) {
                restartSession()
                return
            }
            params.width = lastGoodW
            params.height = lastGoodH
            clampToScreen(params)
            safeUpdate(root, params)
            saveGeometry()
            restartSession(lastGoodW, lastGoodH)
        }

        /** After a resize gesture, rebuild the virtual display at the new window
         *  size so the app re-lays-out (not just scales). [startSession] arms
         *  the watchdog and probation on its own. */
        private fun maybeRestartForResize() {
            if (evenClamp(params.width) == videoW && evenClamp(params.height) == videoH) return
            restartSession()
        }

        private fun rotateWindow() {
            val w = params.width
            params.width = params.height
            params.height = w
            clampToScreen(params)
            safeUpdate(root, params)
            saveGeometry()
            restartSession()
        }

        private fun applyMirrorTransform() {
            val view = textureView ?: return
            val vw = view.width.toFloat()
            val vh = view.height.toFloat()
            if (vw <= 0f || vh <= 0f || videoW <= 0 || videoH <= 0) return
            val scale = min(vw / videoW, vh / videoH)
            contentW = videoW * scale
            contentH = videoH * scale
            contentLeft = (vw - contentW) / 2f
            contentTop = (vh - contentH) / 2f
            val matrix = Matrix()
            matrix.setScale(contentW / vw, contentH / vh)
            matrix.postTranslate(contentLeft, contentTop)
            view.setTransform(matrix)
        }

        // ---- pin / z-order ----

        fun applyChrome() {
            val dark = DarkModeManager.getInstance(this@AppWindowService).isDarkMode()
            val barColor = if (dark) 0x8024282C.toInt() else 0xB3E6E8EA.toInt()
            val fg = if (dark) Color.WHITE else 0xFF2C2C2E.toInt()
            val handleStroke = if (dark) Color.WHITE else 0xFF3C4043.toInt()
            val handleHalo = if (dark) 0x59000000 else 0x33000000
            (titleBar?.background as? GradientDrawable)?.setColor(barColor)
            titleLabel?.setTextColor(fg)
            pinButton?.setColorFilter(fg)
            rotateButton?.setColorFilter(fg)
            closeButton?.setColorFilter(fg)
            (resizeHandle as? CornerBracketView)?.setColors(handleStroke, handleHalo)
        }

        private fun setPinned(value: Boolean) {
            pinned = value
            // State reads through the glyph itself: stroke outline when free,
            // solid fill when pinned. No shadow, no tint change.
            pinButton?.setImageResource(
                if (value) R.drawable.ic_appwin_pin_filled else R.drawable.ic_appwin_pin,
            )
            if (value) raiseToFrontMasked()
        }

        /**
         * Hook for [OverlayZOrderCoordinator]: during a stack reassert every
         * overlay is re-raised in tier order, and this window takes its slot
         * only while pinned. The generation guard skips the remove+add blink
         * when nothing else has restacked since our last raise.
         */
        fun isAttached(): Boolean =
            this::root.isInitialized && root.isAttachedToWindow

        fun isPadTarget(): Boolean = !closing && this::root.isInitialized

        fun currentX(): Int = if (this::params.isInitialized) params.x else 0

        fun currentY(): Int = if (this::params.isInitialized) params.y else 0

        fun currentFrame(): TouchPadMath.WindowFrame {
            if (!this::params.isInitialized) return TouchPadMath.WindowFrame(0, 0, 0, 0)
            return TouchPadMath.WindowFrame(params.x, params.y, params.width, params.height)
        }

        fun containsScreen(x: Float, y: Float): Boolean {
            if (!isPadTarget()) return false
            if (root.isAttachedToWindow && root.width > 0) {
                val loc = IntArray(2)
                root.getLocationOnScreen(loc)
                return x >= loc[0] && x < loc[0] + root.width &&
                    y >= loc[1] && y < loc[1] + root.height
            }
            if (!this::params.isInitialized) return false
            return x >= params.x && x < params.x + params.width &&
                y >= params.y && y < params.y + params.height
        }

        fun injectBack(): Boolean {
            if (!isPadTarget() || session == null) return false
            session?.sendBack()
            return true
        }

        fun injectSecondaryTap(screenX: Float, screenY: Float): Boolean {
            if (!isPadTarget() || session == null) return false
            val (cw, ch) = contentExtent()
            if (cw < 2f || ch < 2f) return false
            val mapped = screenToContent(screenX, screenY) ?: return false
            val x = mapped.first.coerceIn(0f, cw)
            val y = mapped.second.coerceIn(0f, ch)
            session?.sendMouseClick(
                x,
                y,
                cw.roundToInt(),
                ch.roundToInt(),
                ScrcpyControl.BUTTON_SECONDARY,
            )
            return true
        }

        fun dispatchScreenTap(screenX: Float, screenY: Float): Boolean {
            val surface = textureView
            if (surface != null && surface.isAttachedToWindow) {
                return TouchPadPageScroll.dispatchScreenTap(surface, screenX, screenY)
            }
            if (!this::root.isInitialized) return false
            return TouchPadPageScroll.dispatchScreenTap(root, screenX, screenY)
        }

        fun beginPadScroll(screenX: Float, screenY: Float): Boolean {
            if (!isPadTarget() || session == null) return false
            val (cw, ch) = contentExtent()
            if (cw < 2f || ch < 2f) return false
            val mapped = screenToContent(screenX, screenY) ?: return false
            padScrollX = mapped.first.coerceIn(0f, cw)
            padScrollY = mapped.second.coerceIn(0f, ch)
            padScrollWheel = true
            padScrollDown = false
            return true
        }

        fun nudgePadScroll(dx: Float, dy: Float): Boolean {
            if (!isPadTarget() || session == null) return false
            val (cw, ch) = contentExtent()
            if (cw < 2f || ch < 2f) return false
            sendContentScroll(
                padScrollX,
                padScrollY,
                TouchPadMath.scrollWheelAxis(dx),
                TouchPadMath.scrollWheelAxis(dy),
            )
            return true
        }

        fun endPadScroll() {
            if (padScrollDown) {
                sendContentTouch(ScrcpyControl.ACTION_UP, padScrollX, padScrollY)
            }
            padScrollDown = false
            padScrollWheel = false
        }

        fun beginPadContentDrag(screenX: Float, screenY: Float): Boolean {
            if (!isPadTarget() || session == null) return false
            val (cw, ch) = contentExtent()
            if (cw < 2f || ch < 2f) return false
            val mapped = screenToContent(screenX, screenY) ?: return false
            padContentDragX = mapped.first.coerceIn(0f, cw)
            padContentDragY = mapped.second.coerceIn(0f, ch)
            sendContentTouch(ScrcpyControl.ACTION_DOWN, padContentDragX, padContentDragY)
            padContentDragDown = true
            return true
        }

        fun nudgePadContentDrag(screenX: Float, screenY: Float): Boolean {
            if (!padContentDragDown || session == null) return false
            val (cw, ch) = contentExtent()
            if (cw < 2f || ch < 2f) return false
            val mapped = screenToContent(screenX, screenY) ?: return false
            padContentDragX = mapped.first.coerceIn(0f, cw)
            padContentDragY = mapped.second.coerceIn(0f, ch)
            sendContentTouch(ScrcpyControl.ACTION_MOVE, padContentDragX, padContentDragY)
            return true
        }

        fun endPadContentDrag() {
            if (padContentDragDown) {
                sendContentTouch(ScrcpyControl.ACTION_UP, padContentDragX, padContentDragY)
            }
            padContentDragDown = false
        }

        private fun contentExtent(): Pair<Float, Float> {
            if (contentW > 1f && contentH > 1f) return contentW to contentH
            val view = textureView ?: return 0f to 0f
            return view.width.toFloat() to view.height.toFloat()
        }

        private fun screenToContent(screenX: Float, screenY: Float): Pair<Float, Float>? {
            val surface = textureView
            if (surface != null && surface.isAttachedToWindow) {
                val loc = IntArray(2)
                surface.getLocationOnScreen(loc)
                val vx = screenX - loc[0]
                val vy = screenY - loc[1]
                val cx = if (contentW > 1f) vx - contentLeft else vx
                val cy = if (contentH > 1f) vy - contentTop else vy
                return cx to cy
            }
            if (!this::params.isInitialized) return null
            val bodyTop = params.y + titleBarHeightPx
            val vx = screenX - params.x
            val vy = screenY - bodyTop
            val cx = if (contentW > 1f) vx - contentLeft else vx
            val cy = if (contentH > 1f) vy - contentTop else vy
            return cx to cy
        }

        private fun sendContentTouch(action: Int, x: Float, y: Float) {
            val (cw, ch) = contentExtent()
            if (cw < 1f || ch < 1f) return
            session?.sendTouch(action, x, y, cw.roundToInt(), ch.roundToInt())
        }

        private fun sendContentScroll(x: Float, y: Float, hScroll: Float, vScroll: Float) {
            val (cw, ch) = contentExtent()
            if (cw < 1f || ch < 1f) return
            session?.sendScroll(x, y, cw.roundToInt(), ch.roundToInt(), hScroll, vScroll)
        }

        fun preparePadDrag() {
            showTitleBar()
        }

        fun preparePadPinch() {
            showTitleBar()
            showResizeHandle()
        }

        fun applyPadFrame(x: Int, y: Int, width: Int, height: Int) {
            if (!isAttached() || !this::params.isInitialized) return
            params.x = x
            params.y = y
            params.width = width
            params.height = height
            clampToScreen(params)
            safeUpdate(root, params)
        }

        fun commitPadPinch() {
            saveGeometry()
            scheduleTitleBarHide()
            scheduleHandleHide()
            maybeRestartForResize()
        }

        fun finishPadPinch() {
            scheduleTitleBarHide()
            scheduleHandleHide()
        }

        fun nudgePad(dx: Int, dy: Int) {
            if (!isAttached() || !this::params.isInitialized) return
            params.x += dx
            params.y += dy
            clampToScreen(params)
            safeUpdate(root, params)
        }

        fun finishPadDrag(save: Boolean) {
            scheduleTitleBarHide()
            if (save) saveGeometry()
        }

        fun animatePadTo(x: Int, y: Int, onStart: (ValueAnimator) -> Unit) {
            if (!this::params.isInitialized) return
            val fromX = params.x
            val fromY = params.y
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    params.x = (fromX + (x - fromX) * t).roundToInt()
                    params.y = (fromY + (y - fromY) * t).roundToInt()
                    clampToScreen(params)
                    safeUpdate(root, params)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        finishPadDrag(save = false)
                    }
                })
            }
            onStart(anim)
            anim.start()
        }

        fun restackIfPinned() {
            if (closing || !pinned) return
            if (restackedAtGeneration == OverlayZOrderCoordinator.stackGeneration) return
            Log.d(TAG, "restack pinned window $pkg")
            raiseToFront()
        }

        private fun raiseToFront(mask: Boolean = true) {
            if (closing) return
            if (OverlayZOrderCoordinator.bringToFront(
                    windowManager, root, params, TAG, mask = mask, raiseMic = false,
                )
            ) {
                restackedAtGeneration = OverlayZOrderCoordinator.stackGeneration
            }
        }

        /**
         * User-triggered raise (pin click / app re-open). The hard restack is
         * remove+add — the window vanishes for a frame, which reads as a
         * flicker. Mask it the way ScreenBlankOverlay's swap-raise masks its
         * plate: float a pixel-identical snapshot in its own window on top,
         * restack the real window behind it, and drop the fake once the real
         * one has painted again. Coordinator restacks ([restackIfPinned]) stay
         * unmasked — they must run inline in the coordinator's tier order.
         */
        private fun raiseToFrontMasked() {
            if (closing) return
            val wm = windowManager
            val shot = if (raiseCover == null) snapshotWindow() else null
            if (wm == null || shot == null) {
                raiseToFront()
                OverlayZOrderCoordinator.raiseVoiceAboveAppWindows()
                return
            }
            val cover = ImageView(this@AppWindowService).apply {
                setImageBitmap(shot)
                scaleType = ImageView.ScaleType.FIT_XY
                background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                    cornerRadius = dp(14f)
                }
                clipToOutline = true
            }
            val coverLp = WindowManager.LayoutParams(
                params.width,
                params.height,
                params.type,
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = params.x
                y = params.y
                OverlayZOrderCoordinator.applyVoiceSublayerLayout(this)
                windowAnimations = R.style.AppWindowNoAnimation
            }
            try {
                wm.addView(cover, coverLp)
            } catch (e: Exception) {
                Log.w(TAG, "raise cover failed", e)
                raiseToFront()
                OverlayZOrderCoordinator.raiseVoiceAboveAppWindows()
                return
            }
            raiseCover = cover
            // Reap the cover even if the pre-draw below never comes.
            mainHandler.postDelayed(dropCoverRunnable, RAISE_COVER_MAX_MS)
            cover.viewTreeObserver.addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        cover.viewTreeObserver.removeOnPreDrawListener(this)
                        // Restack only after the cover's first frame commits,
                        // then keep the fake one beat longer so the re-added
                        // window underneath has painted.
                        cover.post {
                            raiseToFront(mask = false)
                            OverlayZOrderCoordinator.raiseVoiceAboveAppWindows()
                            mainHandler.postDelayed(dropCoverRunnable, RAISE_COVER_LINGER_MS)
                        }
                        return true
                    }
                },
            )
        }

        private fun dropRaiseCover() {
            mainHandler.removeCallbacks(dropCoverRunnable)
            val cover = raiseCover ?: return
            raiseCover = null
            runCatching { if (cover.isAttachedToWindow) windowManager?.removeView(cover) }
        }

        private fun presentHideCover(shot: Bitmap): Boolean {
            val wm = windowManager ?: return false
            val existing = hideCover
            if (existing != null && existing.isAttachedToWindow) {
                recycleHideCoverBitmap(existing)
                existing.setImageBitmap(shot)
                existing.alpha = 1f
                existing.visibility = View.VISIBLE
                hideCoverLp?.let { lp ->
                    lp.x = params.x
                    lp.y = params.y
                    lp.width = params.width
                    lp.height = params.height
                    runCatching { wm.updateViewLayout(existing, lp) }
                }
                mainHandler.removeCallbacks(reapHideCoverRunnable)
                return true
            }
            val cover = ImageView(this@AppWindowService).apply {
                setImageBitmap(shot)
                scaleType = ImageView.ScaleType.FIT_XY
            }
            val lp = WindowManager.LayoutParams(
                params.width,
                params.height,
                params.type,
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = params.x
                y = params.y
                OverlayZOrderCoordinator.applyVoiceSublayerLayout(this)
                windowAnimations = R.style.AppWindowNoAnimation
            }
            return try {
                wm.addView(cover, lp)
                hideCover = cover
                hideCoverLp = lp
                mainHandler.removeCallbacks(reapHideCoverRunnable)
                true
            } catch (e: Exception) {
                Log.w(TAG, "hide cover failed", e)
                shot.recycle()
                false
            }
        }

        private fun fadeHideCover() {
            val cover = hideCover ?: return
            cover.animate().cancel()
            cover.animate()
                .alpha(0f)
                .setDuration(220L)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { parkHideCover(visible = false) }
                .start()
        }

        private fun parkHideCover(visible: Boolean) {
            val cover = hideCover ?: return
            cover.animate().cancel()
            if (visible) {
                cover.alpha = 1f
                cover.visibility = View.VISIBLE
                mainHandler.removeCallbacks(reapHideCoverRunnable)
                return
            }
            cover.alpha = 1f
            cover.visibility = View.GONE
            recycleHideCoverBitmap(cover)
            mainHandler.removeCallbacks(reapHideCoverRunnable)
            mainHandler.postDelayed(reapHideCoverRunnable, HIDE_COVER_REAP_MS)
        }

        private fun destroyHideCover() {
            mainHandler.removeCallbacks(reapHideCoverRunnable)
            val cover = hideCover ?: return
            hideCover = null
            hideCoverLp = null
            cover.animate().cancel()
            recycleHideCoverBitmap(cover)
            runCatching { if (cover.isAttachedToWindow) windowManager?.removeView(cover) }
        }

        private fun recycleHideCoverBitmap(cover: ImageView) {
            val bitmap = (cover.drawable as? BitmapDrawable)?.bitmap
            cover.setImageDrawable(null)
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }

        /** Compose what this window currently shows: the mirror frame must be
         *  read back through [TextureView.getBitmap] (its content never reaches
         *  a software canvas), the chrome on top draws normally. */
        private fun snapshotWindow(): Bitmap? {
            val tv = textureView ?: return null
            val w = root.width
            val h = root.height
            if (w <= 0 || h <= 0) return null
            return runCatching {
                val shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(shot)
                canvas.drawColor(Color.BLACK)
                tv.getBitmap()?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                for (chrome in arrayOf(titleBar, resizeHandle)) {
                    if (chrome == null || chrome.visibility != View.VISIBLE || chrome.alpha <= 0f) {
                        continue
                    }
                    canvas.saveLayerAlpha(
                        0f,
                        0f,
                        w.toFloat(),
                        h.toFloat(),
                        (chrome.alpha * 255f).roundToInt(),
                    )
                    canvas.translate(chrome.x, chrome.y)
                    chrome.draw(canvas)
                    canvas.restore()
                }
                shot
            }.getOrNull()
        }

        // ---- title bar + handle auto-hide ----

        private fun showTitleBar() {
            val bar = titleBar ?: return
            mainHandler.removeCallbacks(hideTitleBarRunnable)
            parkHideCover(visible = false)
            bar.animate().cancel()
            bar.visibility = View.VISIBLE
            bar.animate().alpha(1f).setDuration(120L).start()
        }

        private fun scheduleTitleBarHide() {
            mainHandler.removeCallbacks(hideTitleBarRunnable)
            mainHandler.postDelayed(hideTitleBarRunnable, TITLE_BAR_HIDE_MS)
        }

        private fun hideTitleBar() {
            val bar = titleBar ?: return
            if (bar.visibility != View.VISIBLE || bar.alpha <= 0.02f) return
            val shot = snapshotWindow()
            bar.animate().cancel()
            bar.alpha = 0f
            bar.visibility = View.INVISIBLE
            if (shot != null && presentHideCover(shot)) {
                fadeHideCover()
            } else {
                shot?.recycle()
            }
        }

        private fun showResizeHandle() {
            mainHandler.removeCallbacks(hideHandleRunnable)
            resizeHandle?.animate()?.cancel()
            resizeHandle?.alpha = 1f
        }

        private fun scheduleHandleHide() {
            mainHandler.removeCallbacks(hideHandleRunnable)
            mainHandler.postDelayed(hideHandleRunnable, HANDLE_HIDE_MS)
        }

        /**
         * Do not alpha-animate the live handle — TextureView recomposites an
         * opaque frame on each tick, the same twitch as title-bar fade.
         */
        private fun hideResizeHandle() {
            val handle = resizeHandle ?: return
            handle.animate().cancel()
            handle.alpha = 0f
        }

        // ---- touch handlers ----

        @SuppressLint("ClickableViewAccessibility")
        private fun attachMove(handle: View, onInteraction: (Boolean) -> Unit) {
            var startX = 0f
            var startY = 0f
            var originX = 0
            var originY = 0
            handle.setOnTouchListener { _, e ->
                if (TouchPadPageScroll.isGeneratedGesture(e)) return@setOnTouchListener true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.rawX; startY = e.rawY; originX = params.x; originY = params.y
                        onInteraction(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = originX + (e.rawX - startX).roundToInt()
                        params.y = originY + (e.rawY - startY).roundToInt()
                        safeUpdate(root, params); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        saveGeometry()
                        onInteraction(false)
                        true
                    }
                    else -> false
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun attachResize(handle: View) {
            val minW = dp(200f).roundToInt()
            val minH = dp(200f).roundToInt()
            var startX = 0f
            var startY = 0f
            var originW = 0
            var originH = 0
            handle.setOnTouchListener { _, e ->
                if (TouchPadPageScroll.isGeneratedGesture(e)) return@setOnTouchListener true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.rawX; startY = e.rawY; originW = params.width; originH = params.height
                        showResizeHandle()
                        OverlayHaptics.click(this@AppWindowService, handle)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.width = (originW + (e.rawX - startX).roundToInt()).coerceAtLeast(minW)
                        params.height = (originH + (e.rawY - startY).roundToInt()).coerceAtLeast(minH)
                        safeUpdate(root, params); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        saveGeometry()
                        scheduleHandleHide()
                        // Rebuild the virtual display at the new size so the app
                        // re-lays-out to fit, under probation for crash recovery.
                        maybeRestartForResize()
                        true
                    }
                    else -> false
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun attachSurfaceTouch(surface: TextureView) {
            surface.setOnTouchListener { v, e ->
                if (TouchPadPageScroll.isGeneratedGesture(e)) return@setOnTouchListener true
                val action = when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> ScrcpyControl.ACTION_DOWN
                    MotionEvent.ACTION_MOVE -> ScrcpyControl.ACTION_MOVE
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ScrcpyControl.ACTION_UP
                    else -> return@setOnTouchListener false
                }
                if (contentW > 0f && contentH > 0f) {
                    session?.sendTouch(
                        action,
                        e.x - contentLeft,
                        e.y - contentTop,
                        contentW.roundToInt(),
                        contentH.roundToInt(),
                    )
                } else {
                    session?.sendTouch(action, e.x, e.y, v.width, v.height)
                }
                true
            }
        }

        // ---- geometry / config ----

        private fun saveGeometry() {
            AppWindowGeometryStore.save(
                this@AppWindowService,
                pkg,
                AppWindowGeometryStore.Geometry(params.x, params.y, params.width, params.height),
            )
        }

        fun onConfigChanged() {
            val g = AppWindowGeometryStore.read(this@AppWindowService, pkg)
            params.x = g.x
            params.y = g.y
            params.width = g.width
            params.height = g.height
            safeUpdate(root, params)
            showTitleBar()
            scheduleTitleBarHide()
        }

        // ---- teardown ----

        /** User pressed the close button: kill the app and remove this window. */
        fun close() {
            if (closing) return
            closing = true
            if (!this::root.isInitialized || !root.isAttachedToWindow) {
                finishClose()
                return
            }
            freezeForFade()
            root.animate().cancel()
            root.animate().setListener(null)
            root.animate()
                .alpha(0f)
                .setDuration(FADE_MS)
                .setInterpolator(AccelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        root.animate().setListener(null)
                        finishClose()
                    }
                })
                .start()
        }

        /**
         * TextureView + parent alpha flickers: the mirror surface recomposites
         * opaque for a frame on each fade tick, and a restack mid-fade does it
         * again. Freeze the last frame, hide the live surface, then fade that.
         */
        private fun freezeForFade() {
            if (padScrollWin === this) padScrollWin = null
            if (padContentDragWin === this) padContentDragWin = null
            endPadScroll()
            endPadContentDrag()
            dropRaiseCover()
            destroyHideCover()
            mainHandler.removeCallbacks(hideTitleBarRunnable)
            mainHandler.removeCallbacks(hideHandleRunnable)
            mainHandler.removeCallbacks(firstFrameWatchdog)
            cancelProbation()
            titleBar?.animate()?.cancel()
            resizeHandle?.animate()?.cancel()
            val shot = snapshotWindow()
            if (shot != null) {
                val freeze = ImageView(this@AppWindowService).apply {
                    setImageBitmap(shot)
                    scaleType = ImageView.ScaleType.FIT_XY
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                }
                root.addView(freeze)
            }
            textureView?.visibility = View.INVISIBLE
            titleBar?.visibility = View.INVISIBLE
            resizeHandle?.visibility = View.INVISIBLE
            val old = session
            session = null
            if (old != null) sessionExecutor.execute { old.stopBlocking() }
        }

        private fun finishClose() {
            OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
            runShellAsync("am force-stop $pkg", "AppWindowKill")
            if (padPinchWin === this) padPinchWin = null
            if (padDragWin === this) padDragWin = null
            if (padScrollWin === this) padScrollWin = null
            if (padContentDragWin === this) padContentDragWin = null
            endPadScroll()
            endPadContentDrag()
            destroy()
            windows.remove(this)
            if (windows.isEmpty()) {
                OverlayZOrderCoordinator.syncFabForAod()
                stopSelf()
            }
        }

        /** Tear down views/session without touching the window list (used by both
         *  [close] and service [onDestroy]). */
        fun destroy() {
            if (this::root.isInitialized) {
                root.animate().cancel()
                root.animate().setListener(null)
            }
            cancelProbation()
            dropRaiseCover()
            destroyHideCover()
            mainHandler.removeCallbacks(firstFrameWatchdog)
            mainHandler.removeCallbacks(hideTitleBarRunnable)
            mainHandler.removeCallbacks(hideHandleRunnable)
            session?.stop()
            session = null
            runCatching {
                if (root.isAttachedToWindow) windowManager?.removeView(root)
            }
            currentSurface?.release()
            currentSurface = null
            savedTexture?.release()
            savedTexture = null
        }
    }

    /** Equal-armed "L" hugging the bottom-right corner: one path, identical
     *  arm lengths, dark halo behind the white stroke for contrast (no shadow
     *  layer, so it stays crisp on the hardware-accelerated overlay). */
    private inner class CornerBracketView(context: Context) : View(context) {
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x59000000
            style = Paint.Style.STROKE
            strokeWidth = dp(5.5f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        fun setColors(strokeColor: Int, haloColor: Int) {
            if (stroke.color == strokeColor && halo.color == haloColor) return
            stroke.color = strokeColor
            halo.color = haloColor
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = dp(9f)
            val len = (min(width, height) - 2f * inset) * 0.9f
            val x = width - inset
            val y = height - inset
            path.rewind()
            path.moveTo(x, y - len)
            path.lineTo(x, y)
            path.lineTo(x - len, y)
            canvas.drawPath(path, halo)
            canvas.drawPath(path, stroke)
        }
    }

    companion object {
        private const val TAG = "AppWindowService"
        private const val EXTRA_PACKAGE = "package"
        private const val TITLE_BAR_HIDE_MS = 3000L
        private const val HANDLE_HIDE_MS = 1400L
        private const val FADE_MS = 230L

        // Snapshot mask over a user-triggered restack: how long the fake stays
        // after the restack, and a hard cap in case its first draw never comes.
        private const val RAISE_COVER_LINGER_MS = 160L
        private const val RAISE_COVER_MAX_MS = 600L
        /** Park the title-bar hide snapshot; reap the window after this idle. */
        private const val HIDE_COVER_REAP_MS = 10L * 60L * 1000L

        /** Live service, so [OverlayZOrderCoordinator] can reach the windows
         *  during its stack reasserts. Set in [onCreate], cleared in [onDestroy]. */
        @Volatile
        private var activeInstance: AppWindowService? = null

        /**
         * Called by [OverlayZOrderCoordinator] at the pinned-app-window tier of
         * every stack reassert. This is how pinned windows hold their place among
         * the many other overlays — the coordinator re-raises each layer in
         * order, instead of every window polling and fighting on its own.
         */
        /** A mirrored app window is in the list (add happens before [Win.build]).
         *  Do not wait for [View.isAttachedToWindow] — that stays false through
         *  addView on some ROMs, which made the AOD yield think nothing was up. */
        fun hasWindowAttached(): Boolean {
            val svc = activeInstance ?: return false
            return svc.windows.isNotEmpty()
        }

        fun openPackages(): List<String> {
            val svc = activeInstance ?: return emptyList()
            return svc.windows.map { it.pkg }
        }

        fun containsScreenPoint(x: Float, y: Float): Boolean {
            val svc = activeInstance ?: return false
            return svc.windows.asReversed().any { it.containsScreen(x, y) }
        }

        fun packageAt(x: Float, y: Float): String? {
            val svc = activeInstance ?: return null
            return svc.windows.asReversed().firstOrNull { it.containsScreen(x, y) }?.pkg
        }

        fun tapAt(screenX: Float, screenY: Float): Boolean {
            val svc = activeInstance ?: return false
            val win = svc.windows.asReversed().firstOrNull {
                it.isPadTarget() && it.containsScreen(screenX, screenY)
            } ?: return false
            return win.dispatchScreenTap(screenX, screenY)
        }

        fun backAt(screenX: Float, screenY: Float): Boolean {
            val svc = activeInstance ?: return false
            val win = svc.windows.asReversed().firstOrNull {
                it.isPadTarget() && it.containsScreen(screenX, screenY)
            } ?: return false
            return win.injectBack()
        }

        fun secondaryTapAt(screenX: Float, screenY: Float): Boolean {
            val svc = activeInstance ?: return false
            val win = svc.windows.asReversed().firstOrNull {
                it.isPadTarget() && it.containsScreen(screenX, screenY)
            } ?: return false
            return win.injectSecondaryTap(screenX, screenY)
        }

        /**
         * Two-finger scroll at the cursor origin. The point stays put; only the
         * hand travel on the pad becomes wheel delta — not a drag stroke length.
         */
        fun beginPadScroll(x: Float, y: Float): Boolean {
            val svc = activeInstance ?: return false
            if (svc.padPinchWin != null || svc.padDragWin != null || svc.padContentDragWin != null) {
                return false
            }
            if (svc.padScrollWin != null) return svc.padScrollWin?.isPadTarget() == true
            val win = svc.windows.asReversed().firstOrNull {
                it.isPadTarget() && it.containsScreen(x, y)
            } ?: return false
            if (!win.beginPadScroll(x, y)) return false
            svc.padScrollWin = win
            return true
        }

        fun nudgePadScroll(dx: Float, dy: Float): Boolean {
            val win = activeInstance?.padScrollWin ?: return false
            return win.nudgePadScroll(dx, dy)
        }

        fun endPadScroll() {
            val svc = activeInstance ?: return
            val win = svc.padScrollWin ?: return
            svc.padScrollWin = null
            win.endPadScroll()
        }

        fun beginPadContentDrag(x: Float, y: Float): Boolean {
            val svc = activeInstance ?: return false
            if (svc.padPinchWin != null || svc.padDragWin != null || svc.padScrollWin != null) {
                return false
            }
            if (svc.padContentDragWin != null) {
                return svc.padContentDragWin?.isPadTarget() == true
            }
            val win = svc.windows.asReversed().firstOrNull {
                it.isPadTarget() && it.containsScreen(x, y)
            } ?: return false
            if (!win.beginPadContentDrag(x, y)) return false
            svc.padContentDragWin = win
            return true
        }

        fun nudgePadContentDrag(x: Float, y: Float): Boolean {
            val win = activeInstance?.padContentDragWin ?: return false
            return win.nudgePadContentDrag(x, y)
        }

        fun endPadContentDrag() {
            val svc = activeInstance ?: return
            val win = svc.padContentDragWin ?: return
            svc.padContentDragWin = null
            win.endPadContentDrag()
        }

        fun beginPadDrag(x: Float, y: Float, snapUnlessTop: Boolean): Boolean {
            val svc = activeInstance ?: return false
            if (svc.padPinchWin != null || svc.padScrollWin != null || svc.padContentDragWin != null) {
                return false
            }
            svc.padSnapAnimator?.cancel()
            svc.padSnapAnimator = null
            val hit = svc.windows.asReversed().firstOrNull {
                it.isPadTarget() && it.containsScreen(x, y)
            }
            val win = hit ?: if (snapUnlessTop) {
                svc.windows.asReversed().firstOrNull { it.isPadTarget() }
            } else {
                null
            } ?: return false
            svc.padDragWin = win
            svc.padOriginX = win.currentX()
            svc.padOriginY = win.currentY()
            svc.padSnapUnlessTop = snapUnlessTop
            win.preparePadDrag()
            return true
        }

        fun nudgePadDrag(dx: Float, dy: Float): Boolean {
            val win = activeInstance?.padDragWin ?: return false
            win.nudgePad(dx.roundToInt(), dy.roundToInt())
            return true
        }

        fun endPadDrag(commit: Boolean): Boolean {
            val svc = activeInstance ?: return false
            val win = svc.padDragWin ?: return false
            svc.padDragWin = null
            val keep = commit && (
                !svc.padSnapUnlessTop ||
                    TouchPadMath.liftShouldCommit(
                        win.currentY(),
                        svc.resources.displayMetrics.heightPixels,
                    )
                )
            if (keep) {
                win.finishPadDrag(save = true)
                return true
            }
            win.animatePadTo(svc.padOriginX, svc.padOriginY) { animator ->
                svc.padSnapAnimator = animator
            }
            return false
        }

        /** Frontmost live window frame, or the window currently being pinched. */
        fun padWindowFrame(): TouchPadMath.WindowFrame? {
            val svc = activeInstance ?: return null
            val win = svc.padPinchWin
                ?: svc.windows.asReversed().firstOrNull { it.isPadTarget() }
                ?: return null
            return win.currentFrame()
        }

        fun padWindowFrameAt(x: Float, y: Float): TouchPadMath.WindowFrame? {
            val svc = activeInstance ?: return null
            val win = svc.windows.asReversed().firstOrNull {
                it.isPadTarget() && it.containsScreen(x, y)
            } ?: return null
            return win.currentFrame()
        }

        /**
         * Start a live pinch-resize of the window under ([x], [y]), or the
         * frontmost window if the cursor is elsewhere. Remembers start size and
         * center; [applyPadPinch] scales around that center until [endPadPinch].
         */
        fun beginPadPinch(x: Float, y: Float): Boolean {
            val svc = activeInstance ?: return false
            if (svc.padDragWin != null || svc.padScrollWin != null || svc.padContentDragWin != null) {
                return false
            }
            svc.padSnapAnimator?.cancel()
            svc.padSnapAnimator = null
            val win = svc.windows.asReversed().firstOrNull {
                it.isPadTarget() && it.containsScreen(x, y)
            } ?: svc.windows.asReversed().firstOrNull { it.isPadTarget() }
                ?: return false
            val frame = win.currentFrame()
            if (frame.width <= 0 || frame.height <= 0) return false
            svc.padPinchWin = win
            svc.padPinchOriginX = frame.x
            svc.padPinchOriginY = frame.y
            svc.padPinchOriginW = frame.width
            svc.padPinchOriginH = frame.height
            svc.padPinchCenterX = frame.x + frame.width / 2f
            svc.padPinchCenterY = frame.y + frame.height / 2f
            win.preparePadPinch()
            return true
        }

        /** Live WindowManager resize. Virtual display rebuild waits for [endPadPinch]. */
        fun applyPadPinch(scale: Float): Boolean {
            val svc = activeInstance ?: return false
            val win = svc.padPinchWin ?: return false
            if (!win.isPadTarget()) return false
            val dm = svc.resources.displayMetrics
            val min = svc.dp(200f).roundToInt()
            val frame = TouchPadMath.pinchFrame(
                originW = svc.padPinchOriginW,
                originH = svc.padPinchOriginH,
                centerX = svc.padPinchCenterX,
                centerY = svc.padPinchCenterY,
                scale = scale,
                minW = min,
                minH = min,
                maxW = dm.widthPixels,
                maxH = dm.heightPixels,
            )
            win.applyPadFrame(frame.x, frame.y, frame.width, frame.height)
            return true
        }

        /** Commit saves geometry and rebuilds the mirror; cancel rolls size back. */
        fun endPadPinch(commit: Boolean): Boolean {
            val svc = activeInstance ?: return false
            val win = svc.padPinchWin ?: return false
            svc.padPinchWin = null
            if (!win.isPadTarget()) return false
            if (commit) {
                win.commitPadPinch()
                return true
            }
            win.applyPadFrame(
                svc.padPinchOriginX,
                svc.padPinchOriginY,
                svc.padPinchOriginW,
                svc.padPinchOriginH,
            )
            win.finishPadPinch()
            return false
        }

        fun bringPinnedToFront() {
            val svc = activeInstance ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                svc.windows.forEach { it.restackIfPinned() }
            } else {
                svc.mainHandler.post { svc.windows.forEach { it.restackIfPinned() } }
            }
        }

        /** Close [packageName]'s floating window if one is open; silent no-op
         *  otherwise. Used by the HA launcher select's "(close window)" rows. */
        fun closeWindow(packageName: String) {
            val svc = activeInstance ?: return
            svc.mainHandler.post {
                svc.windows.firstOrNull { it.pkg == packageName }?.close()
            }
        }

        fun closeAllWindows() {
            val svc = activeInstance ?: return
            svc.mainHandler.post {
                svc.windows.toList().forEach { it.close() }
            }
        }

        // Session health: first liveness probe, one grace re-probe for slow cold
        // starts, how long a session may stay frameless before the watchdog
        // restarts it, and how many straight failures before we stop retrying.
        private const val PROBATION_MS = 6000L
        private const val REPROBE_MS = 4000L
        private const val FIRST_FRAME_TIMEOUT_MS = 8000L
        private const val MAX_SESSION_FAILURES = 2

        // Trapped permission-dialog guard: poll cadence while windows are open,
        // and how often the rescue toast may repeat if an app keeps re-asking.
        private const val PERM_GUARD_PERIOD_MS = 5000L
        private const val PERM_RESCUE_TOAST_COOLDOWN_MS = 30_000L

        // Self-heal: N opens of the same app within the window trigger a hard
        // repair on that open (the user is clearly re-opening a stuck window).
        private const val REOPEN_PREFS = "app_window_reopen"
        private const val REOPEN_WINDOW_MS = 45_000L
        private const val REPAIR_THRESHOLD = 3

        /** Open [packageName] in its own floating window (a second app gets a
         *  second window; re-opening the same app fronts its existing one). */
        fun start(context: Context, packageName: String) {
            val intent = Intent(context, AppWindowService::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
            context.startService(intent)
        }
    }
}
