package com.example.ava.touchpad

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.services.AccessibilityBridge
import com.example.ava.services.OverlayOrientation
import com.example.ava.services.OverlayZOrderCoordinator
import com.example.ava.services.ScreensaverController
import com.example.ava.settings.DarkModeManager
import com.example.ava.utils.ScreenBlankOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Touch-pad cursor for AI phone clicks: same dot, slightly larger,
 * [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] so the tap punches through.
 *
 * Does not open or require the pad. If Accessibility is off the host
 * fires [android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS].
 */
object AiPhonePointer {
    private const val TAG = "AiPhonePointer"

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var cursor: TouchPadCursorView? = null
    private var params: WindowManager.LayoutParams? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var generation = 0
    private var glideGeneration = 0

    private val hideRunnable = Runnable { fadeAndRemove() }

    suspend fun appear(app: Context, x: Float, y: Float) {
        val shown = withContext(Dispatchers.Main.immediate) {
            showNow(app.applicationContext, x, y)
        }
        if (shown) delay(TouchPadMath.AI_APPEAR_MS)
    }

    suspend fun glide(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        withContext(Dispatchers.Main.immediate) {
            if (cursor == null) return@withContext
            pin(x1, y1, streak = false)
            cursor?.setMode(TouchPadCursorMode.SLIDE)
            val gen = ++glideGeneration
            val dur = durationMs.coerceAtLeast(16L)
            suspendCancellableCoroutine { cont ->
                val start = SystemClock.uptimeMillis()
                val tick = object : Runnable {
                    override fun run() {
                        if (gen != glideGeneration || cursor == null) {
                            if (cont.isActive) cont.resume(Unit)
                            return
                        }
                        val t = ((SystemClock.uptimeMillis() - start).toFloat() / dur)
                            .coerceIn(0f, 1f)
                        pin(
                            TouchPadMath.lerp(x1, x2, t),
                            TouchPadMath.lerp(y1, y2, t),
                        )
                        if (t < 1f) {
                            handler.postDelayed(this, 16L)
                        } else if (cont.isActive) {
                            cursor?.setMode(TouchPadCursorMode.NONE)
                            cont.resume(Unit)
                        }
                    }
                }
                cont.invokeOnCancellation {
                    handler.removeCallbacks(tick)
                    cursor?.setMode(TouchPadCursorMode.NONE)
                }
                handler.post(tick)
            }
        }
    }

    fun confirmTap() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            pulseThenLinger()
        } else {
            handler.post { pulseThenLinger() }
        }
    }

    fun dismiss() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeNow()
        } else {
            handler.post { removeNow() }
        }
    }

    fun tryInjectLocal(x: Float, y: Float): Boolean = AvaInAppHost.tap(x, y)

    fun tryClickThrough(app: Context, x: Float, y: Float): Boolean {
        val service = AccessibilityBridge.getConnectedService() ?: return false
        return TouchPadScroller.clickThrough(service, x, y, skipHostPackage = app.packageName)
    }

    private fun pulseThenLinger() {
        val view = cursor ?: return
        view.setMode(TouchPadCursorMode.NONE)
        view.playTapPulse()
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, TouchPadMath.AI_LINGER_MS)
    }

    private fun showNow(app: Context, x: Float, y: Float): Boolean {
        if (isCovered()) return false
        handler.removeCallbacks(hideRunnable)
        generation++
        val service = AccessibilityBridge.getConnectedService()
        val host = service ?: app
        if (!ensureWindow(host, service != null)) return false
        val view = cursor ?: return false
        val dark = DarkModeManager.getInstance(app).isDarkMode()
        view.setFillRgb(cursorRgb(app, dark), lightMode = !dark)
        view.setMode(TouchPadCursorMode.NONE)
        view.clearTrail()
        pin(x, y, streak = false)
        view.animate().cancel()
        view.animate().setListener(null)
        view.visibility = View.VISIBLE
        view.alpha = 1f
        return true
    }

    private fun isCovered(): Boolean =
        OverlayZOrderCoordinator.shouldYieldVoiceToAod() ||
            ScreenBlankOverlay.isShowing() ||
            ScreensaverController.isScreensaverVisible()

    private fun cursorRgb(app: Context, dark: Boolean): Int {
        val custom = TouchPadPrefs(app).cursorColorRgb
        return if (custom == TouchPadPrefs.DEFAULT_CURSOR_RGB) {
            TouchPadTheme.palette(dark).cursorRgb
        } else {
            custom
        }
    }

    private fun ensureWindow(host: Context, accessibility: Boolean): Boolean {
        if (cursor != null && params != null && windowManager != null) return true
        val wm = host.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val density = host.resources.displayMetrics.density
        val size = TouchPadMath.dp(TouchPadMath.CURSOR_TRAIL_SIZE_DP, density)
        val type = if (accessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (PlatformCapabilities.canDrawOverlays(host)) {
            PlatformCapabilities.overlayWindowType()
        } else {
            return false
        }
        val view = TouchPadCursorView(host, cursorRgb(host, DarkModeManager.getInstance(host).isDarkMode()), TouchPadCursorView.Spec.ai()).apply {
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            type,
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
        OverlayZOrderCoordinator.applyNotTouchableWindowAlpha(lp)
        return try {
            wm.addView(view, lp)
            windowManager = wm
            cursor = view
            params = lp
            true
        } catch (e: Exception) {
            if (accessibility && PlatformCapabilities.canDrawOverlays(host)) {
                lp.type = PlatformCapabilities.overlayWindowType()
                return try {
                    wm.addView(view, lp)
                    windowManager = wm
                    cursor = view
                    params = lp
                    true
                } catch (retry: Exception) {
                    Log.e(TAG, "Failed to create AI cursor overlay", retry)
                    false
                }
            }
            Log.e(TAG, "Failed to create AI cursor overlay", e)
            false
        }
    }

    private fun pin(x: Float, y: Float, streak: Boolean = true) {
        val view = cursor ?: return
        val lp = params ?: return
        val wm = windowManager ?: return
        val metrics = view.resources.displayMetrics
        val half = TouchPadMath.dp(TouchPadMath.CURSOR_SIZE_DP, metrics.density) / 2f
        cursorX = TouchPadMath.clamp(x, half, metrics.widthPixels - half)
        cursorY = TouchPadMath.clamp(y, half, metrics.heightPixels - half)
        if (streak) view.follow(cursorX, cursorY)
        val windowHalf = TouchPadMath.dp(TouchPadMath.CURSOR_TRAIL_SIZE_DP, metrics.density) / 2f
        lp.x = (cursorX - windowHalf).roundToInt()
        lp.y = (cursorY - windowHalf).roundToInt()
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun fadeAndRemove() {
        val view = cursor ?: return
        val token = generation
        view.animate().cancel()
        view.animate().setListener(null)
        view.animate()
            .alpha(0f)
            .setDuration(TouchPadMath.FADE_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (token == generation) removeNow()
            }
            .start()
    }

    private fun removeNow() {
        generation++
        glideGeneration++
        handler.removeCallbacks(hideRunnable)
        val view = cursor ?: return
        view.animate().cancel()
        view.animate().setListener(null)
        runCatching { windowManager?.removeView(view) }
        cursor = null
        params = null
        windowManager = null
    }
}
