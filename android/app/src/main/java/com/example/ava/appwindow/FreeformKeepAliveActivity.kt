package com.example.ava.appwindow

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.example.ava.R
import java.lang.ref.WeakReference

/**
 * Two jobs, one activity.
 *
 * On Android 7.0–8.1 (API 24–27) this is an off-screen 1×1 window that keeps
 * the system freeform workspace from collapsing when focus changes. [ensureRunning]
 * is the only entry for that path and stays a no-op everywhere else.
 *
 * On Android 10+ (API 29+) some devices (Meta Portal) silence AudioRecord unless
 * this uid has a visible activity. [ensureMicVisible] parks a non-focusable
 * freeform sliver while the voice satellite is running, and only when
 * `enable_freeform_support` is already 1. Ava does not turn that flag on. A
 * fullscreen launch (freeform not actually active) finishes immediately and is
 * not retried. Covers while the screen is on retry with a backoff that caps at
 * 30s and does not stop. Screen-off does not start the activity; the next
 * screen-on parks it again. [releaseMicVisible] drops the sliver when the
 * satellite stops.
 */
class FreeformKeepAliveActivity : Activity() {

    private var initialLaunch = true

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 29) {
            // Manifest theme is a floating dialog, which API 29 can force
            // fullscreen. That launch is rejected below.
            setTheme(R.style.FreeformMicSliver)
        }
        super.onCreate(savedInstanceState)

        // Let every touch fall through to whatever is underneath — this window
        // must never intercept input. Position/size come from the launch bounds
        // set by [ensureRunning] or [ensureMicVisible]; we deliberately don't
        // move the window ourselves here.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        window.setFlags(
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        )
        if (Build.VERSION.SDK_INT >= 29) {
            // The sliver only exists so the uid counts as visible. It must not
            // take keys or taps away from the app in front.
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            )
        }

        instanceRef = WeakReference(this)
        running = true
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 29) {
            if (!micWanted) {
                finish()
                return
            }
            // Flag is on but freeform did not take (typical: settings changed
            // without a reboot). Finish at once so we never cover the screen.
            if (!inFreeformWindow()) {
                micWindowRejected = true
                mainHandler.removeCallbacks(repinRunnable)
                mainHandler.removeCallbacks(confirmRunnable)
                finish()
                return
            }
            keepAliveResumed = true
            resumedAtMs = SystemClock.uptimeMillis()
            mainHandler.removeCallbacks(repinRunnable)
            mainHandler.removeCallbacks(confirmRunnable)
            return
        }
        // If we've been resumed and there are no freeform windows left (the user
        // closed the app's window, collapsing the workspace), our job is done.
        if (!initialLaunch && !isInMultiWindowMode) {
            finish()
        }
        initialLaunch = false
    }

    /** Freeform task, including a windowing mode that lands ahead of [isInMultiWindowMode]. */
    private fun inFreeformWindow(): Boolean {
        when (windowingModeOrNull()) {
            WINDOWING_MODE_FREEFORM -> return true
            WINDOWING_MODE_FULLSCREEN -> return false
        }
        return isInMultiWindowMode
    }

    /**
     * [android.content.res.Configuration.windowConfiguration] is not in the
     * public android.jar. Read the hidden getter when the device has it.
     */
    private fun windowingModeOrNull(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            val config = resources.configuration
            val windowConfig = config.javaClass.getMethod("getWindowConfiguration").invoke(config)
            windowConfig.javaClass.getMethod("getWindowingMode").invoke(windowConfig) as Int
        }.getOrNull()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= 29) {
            keepAliveResumed = false
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT < 29) return
        if (isFinishing || !micWanted || micWindowRejected || micLaunchBlocked) return
        // Another Ava activity is already visible, so the uid is not silent.
        if (otherResumed > 0) return
        // Display-off stops every activity. Launching here can wake the panel
        // and burns the backoff. SCREEN_ON parks the sliver again.
        if (!screenInteractive()) return
        val visibleForMs = SystemClock.uptimeMillis() - resumedAtMs
        if (visibleForMs >= STABLE_VISIBLE_MS) {
            repinAttempt = 0
            loggedSlowRepin = false
        } else {
            noteShortCover()
        }
        scheduleRepin()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        keepAliveResumed = false
        if (instanceRef?.get() === this) {
            instanceRef = null
        }
    }

    companion object {
        private const val TAG = "FreeformKeepAlive"

        /** From android.app.ActivityManager.StackId (hidden, pre-P). */
        private const val FREEFORM_WORKSPACE_STACK_ID = 2

        /** From android.app.WindowConfiguration. */
        private const val WINDOWING_MODE_FULLSCREEN = 1

        /** From android.app.WindowConfiguration. WINDOWING_MODE_FREEFORM. */
        internal const val WINDOWING_MODE_FREEFORM = 5

        private const val LAUNCH_WINDOWING_MODE_KEY = "android.activity.windowingMode"

        /** Below this, WindowManager does not count a freeform window as visible. */
        internal const val MIN_VISIBLE_WIDTH_DP = 48
        internal const val MIN_VISIBLE_HEIGHT_DP = 32

        /** Default minimum freeform task. The rest hangs off the bottom-right. */
        internal const val MIN_TASK_DP = 220

        private const val STABLE_VISIBLE_MS = 1_000L

        /** Backoff step that reaches the 30s cap. Further covers stay there. */
        private const val REPIN_ATTEMPT_CAP = 6

        private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

        @Volatile
        private var running = false

        @Volatile
        private var micWanted = false

        @Volatile
        private var micWindowRejected = false

        @Volatile
        private var micLaunchBlocked = false

        @Volatile
        private var keepAliveResumed = false

        @Volatile
        private var instanceRef: WeakReference<FreeformKeepAliveActivity>? = null

        private var appContext: Context? = null
        private var watchingOtherActivities = false
        private var watchingScreen = false
        private var otherResumed = 0
        private var repinAttempt = 0
        private var resumedAtMs = 0L
        private var loggedSlowRepin = false

        private val repinRunnable = Runnable {
            if (!micRepinAllowed()) return@Runnable
            if (!screenInteractive()) return@Runnable
            val app = appContext ?: return@Runnable
            if (!launchMic(app) && !micLaunchBlocked) {
                noteShortCover()
                scheduleRepin()
            }
        }

        /** startActivity can return without the window ever resuming. */
        private val confirmRunnable = Runnable {
            if (!micRepinAllowed()) return@Runnable
            if (!screenInteractive()) return@Runnable
            noteShortCover()
            scheduleRepin()
        }

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!micWanted || micLaunchBlocked || micWindowRejected) return
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        mainHandler.removeCallbacks(repinRunnable)
                        mainHandler.removeCallbacks(confirmRunnable)
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        repinAttempt = 0
                        loggedSlowRepin = false
                        // Post the runnable itself. scheduleRepin() checks
                        // isInteractive immediately, and that flag can still be
                        // false in this broadcast.
                        mainHandler.removeCallbacks(repinRunnable)
                        mainHandler.postDelayed(repinRunnable, freeformMicRepinDelayMs(0))
                    }
                }
            }
        }

        /**
         * Park the keep-alive activity in the freeform stack, off-screen, if it
         * isn't already there. No-op outside API 24–27 (28+ doesn't need it for
         * workspace persistence; the mic sliver is [ensureMicVisible]).
         */
        fun ensureRunning(context: Context) {
            if (Build.VERSION.SDK_INT !in 24..27) return
            if (running) return

            val dm = context.resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels

            val intent = Intent(context, FreeformKeepAliveActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }

            val options = ActivityOptions.makeBasic()
            try {
                val setStackId = ActivityOptions::class.java
                    .getMethod("setLaunchStackId", Int::class.javaPrimitiveType)
                setStackId.invoke(options, FREEFORM_WORKSPACE_STACK_ID)
            } catch (e: Exception) {
                Log.w(TAG, "setLaunchStackId reflection failed", e)
            }
            // Bottom-right, one pixel past the edge — same off-screen park spot
            // Taskbar uses for its invisible freeform activity.
            options.setLaunchBounds(Rect(w, h, w + 1, h + 1))

            try {
                context.startActivity(intent, options.toBundle())
            } catch (e: Exception) {
                Log.w(TAG, "keep-alive startActivity failed", e)
            }
        }

        /**
         * Show the mic-visibility sliver. No-op below API 29, when freeform
         * support is off, or after this process already saw a fullscreen launch
         * or a background-activity block. Safe to call from any thread.
         */
        fun ensureMicVisible(context: Context) {
            if (Build.VERSION.SDK_INT < 29) return
            val app = context.applicationContext
            if (Looper.myLooper() == Looper.getMainLooper()) {
                ensureMicVisibleOnMain(app)
            } else {
                mainHandler.post { ensureMicVisibleOnMain(app) }
            }
        }

        /** Drop the mic sliver. No-op below API 29. Safe to call from any thread. */
        fun releaseMicVisible() {
            if (Build.VERSION.SDK_INT < 29) return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                releaseMicVisibleOnMain()
            } else {
                mainHandler.post { releaseMicVisibleOnMain() }
            }
        }

        private fun ensureMicVisibleOnMain(app: Context) {
            if (micLaunchBlocked || micWindowRejected) return
            val support = Settings.Global.getInt(app.contentResolver, "enable_freeform_support", 0)
            if (!freeformMicKeepAliveApplies(Build.VERSION.SDK_INT, support)) return
            micWanted = true
            appContext = app
            watchOtherActivities(app)
            watchScreen(app)
            if (keepAliveResumed) return
            if (!screenInteractive()) return
            if (!launchMic(app) && !micLaunchBlocked) {
                noteShortCover()
                scheduleRepin()
            }
        }

        private fun releaseMicVisibleOnMain() {
            micWanted = false
            micWindowRejected = false
            micLaunchBlocked = false
            repinAttempt = 0
            loggedSlowRepin = false
            mainHandler.removeCallbacks(repinRunnable)
            mainHandler.removeCallbacks(confirmRunnable)
            val activity = instanceRef?.get() ?: return
            if (!activity.isFinishing) activity.finish()
        }

        private fun micRepinAllowed(): Boolean =
            micWanted && !micLaunchBlocked && !micWindowRejected &&
                otherResumed == 0 && !keepAliveResumed

        private fun scheduleRepin() {
            if (!micWanted || micLaunchBlocked || micWindowRejected) return
            if (!screenInteractive()) return
            mainHandler.removeCallbacks(repinRunnable)
            mainHandler.postDelayed(repinRunnable, freeformMicRepinDelayMs(repinAttempt))
        }

        private fun noteShortCover() {
            if (repinAttempt >= REPIN_ATTEMPT_CAP) return
            repinAttempt += 1
            if (repinAttempt == REPIN_ATTEMPT_CAP && !loggedSlowRepin) {
                loggedSlowRepin = true
                Log.w(TAG, "mic keep-alive still covered, retrying every 30s")
            }
        }

        private fun launchMic(context: Context): Boolean {
            val dm = context.resources.displayMetrics
            val intent = Intent(context, FreeformKeepAliveActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            }
            val options = ActivityOptions.makeBasic()
            options.setLaunchBounds(
                freeformMicSliverBounds(dm.widthPixels, dm.heightPixels, dm.density),
            )
            val bundle = options.toBundle() ?: Bundle()
            bundle.putInt(LAUNCH_WINDOWING_MODE_KEY, WINDOWING_MODE_FREEFORM)
            return try {
                context.startActivity(intent, bundle)
                mainHandler.removeCallbacks(confirmRunnable)
                mainHandler.postDelayed(confirmRunnable, STABLE_VISIBLE_MS)
                true
            } catch (e: Exception) {
                Log.w(TAG, "mic keep-alive startActivity failed", e)
                // Android 12+ rejects activity starts from a foreground service.
                // One failure is enough; further retries just spam logcat.
                if (Build.VERSION.SDK_INT >= 31) micLaunchBlocked = true
                false
            }
        }

        private fun watchOtherActivities(context: Context) {
            if (watchingOtherActivities) return
            val app = context.applicationContext as? Application ?: return
            watchingOtherActivities = true
            app.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: Activity) {
                        if (activity is FreeformKeepAliveActivity) return
                        otherResumed += 1
                    }

                    override fun onActivityPaused(activity: Activity) {
                        if (activity is FreeformKeepAliveActivity) return
                        otherResumed = (otherResumed - 1).coerceAtLeast(0)
                        if (micRepinAllowed()) scheduleRepin()
                    }

                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) = Unit
                },
            )
        }

        private fun watchScreen(context: Context) {
            if (watchingScreen) return
            val app = context.applicationContext
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_SCREEN_ON)
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    app.registerReceiver(screenReceiver, filter)
                }
                watchingScreen = true
            } catch (e: Exception) {
                Log.w(TAG, "mic keep-alive screen receiver failed", e)
            }
        }

        private fun screenInteractive(): Boolean {
            val app = appContext ?: return true
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            return pm.isInteractive
        }

        internal fun freeformMicKeepAliveApplies(sdkInt: Int, freeformSupport: Int): Boolean =
            sdkInt >= 29 && freeformSupport != 0

        internal fun freeformMicSliverBounds(widthPx: Int, heightPx: Int, density: Float): Rect {
            val scale = if (density > 0f) density else 1f
            val visibleW = (MIN_VISIBLE_WIDTH_DP * scale).toInt().coerceAtLeast(1)
            val visibleH = (MIN_VISIBLE_HEIGHT_DP * scale).toInt().coerceAtLeast(1)
            val task = (MIN_TASK_DP * scale).toInt().coerceAtLeast(maxOf(visibleW, visibleH))
            val left = (widthPx - visibleW).coerceAtLeast(0)
            val top = (heightPx - visibleH).coerceAtLeast(0)
            return Rect(left, top, left + task, top + task)
        }

        /**
         * First cover retries quickly. Covers that never stay visible back off
         * (1s, 2s, 4s, …) and cap at 30s. The cap is a delay, not a stop.
         */
        internal fun freeformMicRepinDelayMs(consecutiveShortCovers: Int): Long {
            if (consecutiveShortCovers <= 0) return 300L
            val steps = (consecutiveShortCovers - 1).coerceAtMost(5)
            return (1_000L shl steps).coerceAtMost(30_000L)
        }
    }
}
