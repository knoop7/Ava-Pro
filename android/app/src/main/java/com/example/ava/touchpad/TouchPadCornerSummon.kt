package com.example.ava.touchpad

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.example.ava.ui.haptic.OverlayHaptics

/**
 * Triple-tap the bottom-left corner to open the Touch Pad.
 * Screen-space taps (Ava activity) and a tiny always-on overlay share the same counter.
 */
internal object TouchPadCornerSummon {
    private var downX = 0f
    private var downY = 0f
    private var downInCorner = false
    private var tapCount = 0
    private var lastTapAt = 0L

    fun onScreenTouch(
        event: MotionEvent,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): Boolean {
        val size = TouchPadMath.dp(TouchPadMath.CORNER_SUMMON_SIZE_DP, density)
        val slop = TouchPadMath.TAP_SLOP_PX
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downInCorner = TouchPadMath.leftCornerContains(
                    event.x,
                    event.y,
                    screenW.toFloat(),
                    screenH.toFloat(),
                    size,
                )
                if (!downInCorner) reset()
                return false
            }
            MotionEvent.ACTION_UP -> {
                if (!downInCorner) return false
                val travel = TouchPadMath.hypot(event.x - downX, event.y - downY)
                if (travel > slop) {
                    reset()
                    return false
                }
                if (!TouchPadMath.leftCornerContains(
                        event.x,
                        event.y,
                        screenW.toFloat(),
                        screenH.toFloat(),
                        size,
                    )
                ) {
                    reset()
                    return false
                }
                return noteTap()
            }
            MotionEvent.ACTION_CANCEL -> {
                reset()
                return false
            }
            else -> return false
        }
    }

    fun onHotCornerTouch(event: MotionEvent): Boolean {
        val slop = TouchPadMath.TAP_SLOP_PX
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downInCorner = true
                return false
            }
            MotionEvent.ACTION_UP -> {
                val travel = TouchPadMath.hypot(event.x - downX, event.y - downY)
                if (travel > slop) {
                    reset()
                    return false
                }
                return noteTap()
            }
            MotionEvent.ACTION_CANCEL -> {
                reset()
                return false
            }
            else -> return false
        }
    }

    fun reset() {
        tapCount = 0
        lastTapAt = 0L
        downInCorner = false
    }

    private fun noteTap(): Boolean {
        val now = SystemClock.uptimeMillis()
        tapCount = TouchPadMath.nextCornerSummonTap(now, lastTapAt, tapCount)
        lastTapAt = now
        if (!TouchPadMath.cornerSummonReady(tapCount)) return false
        reset()
        return true
    }
}

internal class TouchPadCornerSummonWindow(
    private val service: AccessibilityService,
    private val onSummon: () -> Unit,
) {
    private val windowManager =
        service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    fun attach() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (view != null) return
        val density = service.resources.displayMetrics.density
        val size = TouchPadMath.dp(TouchPadMath.CORNER_SUMMON_SIZE_DP.toInt(), density)
        val corner = object : View(service) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                val ready = TouchPadCornerSummon.onHotCornerTouch(event)
                if (event.actionMasked == MotionEvent.ACTION_UP && ready) {
                    OverlayHaptics.click(service, this)
                    onSummon()
                }
                return true
            }
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            com.example.ava.services.OverlayOrientation.apply(this)
        }
        try {
            windowManager.addView(corner, params)
            view = corner
        } catch (_: Exception) {
            view = null
        }
    }

    fun detach() {
        val current = view ?: return
        view = null
        runCatching { windowManager.removeView(current) }
        TouchPadCornerSummon.reset()
    }

    fun setVisible(visible: Boolean) {
        view?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) TouchPadCornerSummon.reset()
    }
}
