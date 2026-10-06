package com.example.ava.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import com.example.ava.notifications.FullscreenOverlayEscape
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.sensor.ScreenTouchSensor
import com.example.ava.services.ScreensaverController
import com.example.ava.services.OverlayOrientation
import com.example.ava.ui.BlurFadeRevealLayout
import com.example.ava.ui.applyQuickEntityOverlayViewSetup
import com.example.ava.ui.installOverlayImmersiveSticky
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unprivileged fallback for [ScreenControlUtils.setScreenOn]: a black overlay that fades in over
 * the screen and then pins the panel at its floor.
 *
 * Privileged panel power still wins. This exists so `screen_toggle` can darken the panel when
 * root / Shizuku `setDisplayPowerMode` is missing, without falling through to `lockNow()`.
 *
 * Three things have to line up for the result to read as "the screen went off" rather than "a
 * black window appeared":
 *
 *  - **It fades.** Dropping a fully opaque plate in one frame is the same visual event as the
 *    full-bright flash [bringToFrontIfVisible] goes out of its way to avoid. The plate animates
 *    in over [BlurFadeRevealLayout.REVEAL_IN_MS], matching the screensaver, which forces the
 *    window to `TRANSLUCENT` — an `OPAQUE` window cannot blend with what it is covering.
 *  - **It reaches the floor.** `LayoutParams.screenBrightness = 0f` only asks for the dimmest
 *    value the window manager will hand out, which on many panels is a visibly lit black, so the
 *    window attribute is paired with a [BLANKED_SYSTEM_BRIGHTNESS] write. See
 *    [lowerSystemBrightness] for why that write is safe here despite being the thing
 *    [com.example.ava.bluetooth.DarkWakePulse] deliberately refuses to do.
 *  - **It covers the status bar.** `FLAG_FULLSCREEN` only lays the window out under the bar, and
 *    `TYPE_APPLICATION_OVERLAY` sits below the status bar layer, so an unhidden bar is drawn on
 *    top of the plate. Hiding it needs [installOverlayImmersiveSticky] *and* a window that can
 *    take focus, hence the absence of `FLAG_NOT_FOCUSABLE` — which in turn is why keys are
 *    handled here alongside touches.
 *
 * Waking is deliberately not animated: a touch should reveal the screen immediately.
 */
object ScreenBlankOverlay {
    private const val TAG = "ScreenBlankOverlay"
    private const val MAIN_WAIT_MS = 2_000L
    private const val JUST_SHOWN_GENERATION = -2L

    /** Dimmest `Settings.System.SCREEN_BRIGHTNESS` that is still a legal value. */
    private const val BLANKED_SYSTEM_BRIGHTNESS = 1

    /** Dimmest the window manager will hand out. Window-scoped, so nothing to restore. */
    private const val BLANK_WINDOW_BRIGHTNESS = 0f

    private const val PREFS_NAME = "ava_screen_blank_overlay"
    private const val KEY_SAVED_BRIGHTNESS = "saved_brightness"
    private const val KEY_SAVED_BRIGHTNESS_MODE = "saved_brightness_mode"

    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null

    @Volatile
    private var showing = false

    @Volatile
    private var restackedAtGeneration = JUST_SHOWN_GENERATION

    @Volatile
    private var loggedOverlayDenied = false

    fun isShowing(): Boolean = showing

    fun show(context: Context): Boolean {
        if (!PlatformCapabilities.canDrawOverlays(context)) {
            if (!loggedOverlayDenied) {
                loggedOverlayDenied = true
                Log.w(TAG, "No overlay permission; cannot blank without a privileged shell")
            }
            return false
        }
        val ok = onMainSync {
            if (overlayView != null) {
                showing = true
                true
            } else {
                attach(context.applicationContext)
            }
        }
        if (ok) FullscreenOverlayEscape.sync(context)
        return ok
    }

    fun hide() {
        onMainSync {
            detach()
            true
        }
        appContext?.let { FullscreenOverlayEscape.sync(it) }
    }

    /**
     * Replay a brightness floor that outlived the process.
     *
     * [lowerSystemBrightness] persists the value it displaces precisely because this case
     * exists: if Ava dies while blanked, nothing in memory remembers what the panel should go
     * back to, and the user meets a screen stuck at brightness 1 with no way to explain it.
     */
    fun restoreSystemBrightnessAfterRestart(context: Context) {
        if (showing) return
        restoreSystemBrightness(context.applicationContext)
    }

    /**
     * Cover overlays that restacked above us without a remove+add of the live window
     * (that flashes a full-bright frame). Add a second black plate first, then drop the old one.
     *
     * [stackGeneration] is [com.example.ava.services.OverlayZOrderCoordinator.stackGeneration]:
     * skip when nothing else has moved since we last sat on top.
     */
    fun bringToFrontIfVisible(stackGeneration: Long) {
        if (!showing) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { bringToFrontIfVisible(stackGeneration) }
            return
        }
        if (restackedAtGeneration == JUST_SHOWN_GENERATION) {
            restackedAtGeneration = stackGeneration
            return
        }
        if (stackGeneration == restackedAtGeneration) return
        if (swapRaise()) {
            restackedAtGeneration = stackGeneration
        }
    }

    private fun attach(context: Context): Boolean {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            Log.w(TAG, "No WindowManager; cannot blank")
            return false
        }
        val params = createLayoutParams(startDark = false)
        val view = createView(context, startDark = false)
        return runCatching { wm.addView(view, params) }
            .onSuccess {
                appContext = context
                windowManager = wm
                overlayView = view
                windowParams = params
                showing = true
                restackedAtGeneration = JUST_SHOWN_GENERATION
                // Keys only reach a focusable window through a focused view.
                view.post { view.requestFocus() }
                fadeToDark(context, view)
                Log.i(TAG, "Blank overlay up, fading to dark")
            }
            .onFailure { Log.w(TAG, "Failed to add blank overlay", it) }
            .isSuccess
    }

    /**
     * Animate the plate in, then take the panel to its floor. Brightness lands at the end
     * rather than alongside the fade because by then the screen is already black, so the step
     * from "dimmest the compositor allows" to [BLANKED_SYSTEM_BRIGHTNESS] is invisible.
     */
    private fun fadeToDark(context: Context, view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .setDuration(BlurFadeRevealLayout.REVEAL_IN_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (!showing || overlayView !== view) return@withEndAction
                applyBlankedBrightness(context, view)
            }
            .start()
    }

    private fun applyBlankedBrightness(context: Context, view: View) {
        val params = windowParams
        if (params != null && params.screenBrightness != BLANK_WINDOW_BRIGHTNESS) {
            params.screenBrightness = BLANK_WINDOW_BRIGHTNESS
            runCatching { windowManager?.updateViewLayout(view, params) }
                .onFailure { Log.w(TAG, "Failed to pin window brightness", it) }
        }
        lowerSystemBrightness(context)
    }

    /**
     * Take `Settings.System.SCREEN_BRIGHTNESS` down to [BLANKED_SYSTEM_BRIGHTNESS].
     *
     * The window attribute cannot get there on its own — `0f` means "the dimmest this display
     * supports", which is not the same as the dimmest value the *setting* accepts. Writing the
     * setting can be, and that is exactly the cost
     * [com.example.ava.bluetooth.DarkWakePulse] declines to pay: a value someone else now has
     * to restore, which outlives this process. The displaced value is therefore committed to
     * disk before the write, and replayed by [restoreSystemBrightnessAfterRestart] on the next
     * cold start — the case an in-memory restore is guaranteed to lose.
     *
     * Auto brightness is parked in manual for the duration, or the light sensor would overwrite
     * the floor within a frame or two and the panel would drift back up on its own.
     */
    private fun lowerSystemBrightness(context: Context) {
        if (!PlatformCapabilities.canWriteSettings(context)) {
            Log.d(TAG, "No WRITE_SETTINGS; blank stays at window brightness only")
            return
        }
        runCatching {
            val resolver = context.contentResolver
            val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            val mode = Settings.System.getInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            if (current > BLANKED_SYSTEM_BRIGHTNESS) {
                prefs(context).edit()
                    .putInt(KEY_SAVED_BRIGHTNESS, current)
                    .putInt(KEY_SAVED_BRIGHTNESS_MODE, mode)
                    .commit()
            }
            if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            }
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, BLANKED_SYSTEM_BRIGHTNESS)
            Log.i(TAG, "Panel floored at $BLANKED_SYSTEM_BRIGHTNESS (was $current, mode $mode)")
        }.onFailure { Log.w(TAG, "Failed to lower system brightness", it) }
    }

    /** No-op unless [lowerSystemBrightness] left something to put back. */
    private fun restoreSystemBrightness(context: Context) {
        val prefs = prefs(context)
        val saved = prefs.getInt(KEY_SAVED_BRIGHTNESS, -1)
        if (saved < 0) return
        val savedMode = prefs.getInt(
            KEY_SAVED_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        prefs.edit().remove(KEY_SAVED_BRIGHTNESS).remove(KEY_SAVED_BRIGHTNESS_MODE).commit()
        if (!PlatformCapabilities.canWriteSettings(context)) return
        runCatching {
            val resolver = context.contentResolver
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, saved)
            // Mode last: restoring automatic first would let the sensor pick the value instead.
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, savedMode)
            Log.i(TAG, "Panel restored to $saved (mode $savedMode)")
        }.onFailure { Log.w(TAG, "Failed to restore system brightness", it) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun swapRaise(): Boolean {
        val wm = windowManager ?: return false
        val context = appContext ?: return false
        // Already dark: the incoming plate replaces a black screen, so it must not fade in
        // (that would show the content underneath) and must not re-apply the floor.
        val incoming = createView(context, startDark = true)
        val params = createLayoutParams(startDark = true)
        return runCatching { wm.addView(incoming, params) }
            .onSuccess {
                val outgoing = overlayView
                overlayView = incoming
                windowParams = params
                incoming.post { incoming.requestFocus() }
                if (outgoing != null) {
                    outgoing.animate().cancel()
                    runCatching { wm.removeView(outgoing) }
                        .onFailure { Log.w(TAG, "Failed to drop outgoing blank plate", it) }
                }
            }
            .onFailure { Log.w(TAG, "Failed to raise blank overlay", it) }
            .isSuccess
    }

    private fun detach() {
        showing = false
        restackedAtGeneration = JUST_SHOWN_GENERATION
        val context = appContext
        appContext = null
        windowParams = null
        val view = overlayView
        overlayView = null
        val wm = windowManager
        windowManager = null
        view?.animate()?.cancel()
        // Before the window goes: while it is up its own brightness override wins, so the panel
        // only comes back once the plate is gone. Restoring afterwards would expose one frame at
        // whatever the platform picks in between.
        if (context != null) restoreSystemBrightness(context)
        if (view == null) return
        runCatching { wm?.removeView(view) }
            .onFailure { Log.w(TAG, "Failed to remove blank overlay", it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createView(context: Context, startDark: Boolean): View {
        return View(context).apply {
            setBackgroundColor(Color.BLACK)
            alpha = if (startDark) 1f else 0f
            applyQuickEntityOverlayViewSetup()
            installOverlayImmersiveSticky()
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    ScreenTouchSensor.onUserTouch()
                    wake(context)
                }
                true
            }
            // The window has to be focusable to hide the status bar, which means keys land here
            // instead of behind the plate. Swallow them all — including Back — and wake, so a
            // key press cannot reach the app the user cannot see.
            setOnKeyListener { _, _, event ->
                if (event.action == KeyEvent.ACTION_DOWN) wake(context)
                true
            }
        }
    }

    private fun wake(context: Context) {
        ScreensaverController.onUserInteraction()
        val app = context.applicationContext
        mainHandler.post { ScreenControlUtils.setScreenOn(app, true) }
    }

    private fun createLayoutParams(startDark: Boolean): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            PlatformCapabilities.overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            // TRANSLUCENT, not OPAQUE: the plate fades in, and an opaque window has nothing to
            // blend against.
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            screenBrightness = if (startDark) {
                BLANK_WINDOW_BRIGHTNESS
            } else {
                // Leave brightness alone until the fade lands, or the panel would darken in one
                // step while the plate is still transparent.
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            PlatformCapabilities.applyDisplayCutoutShortEdges(this)
            OverlayOrientation.apply(this)
        }
    }

    private fun onMainSync(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result = false
        val done = CountDownLatch(1)
        mainHandler.post {
            result = block()
            done.countDown()
        }
        return if (done.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS)) result else false
    }
}
