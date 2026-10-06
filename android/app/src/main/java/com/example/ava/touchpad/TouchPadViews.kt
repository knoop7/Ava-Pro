package com.example.ava.touchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.example.ava.R
import com.example.ava.ui.haptic.OverlayHaptics
import kotlin.math.min
import kotlin.math.sin

internal enum class TouchPadCursorMode { NONE, SLIDE, HOLD, AUTO_PLAY, AUTO_PAUSE }

internal class TouchPadCursorView(
    context: Context,
    cursorRgb: Int,
    private val spec: Spec = Spec(),
) : View(context) {
    data class Spec(
        val dotRadiusDp: Float = TouchPadMath.CURSOR_DOT_RADIUS_DP,
        val shadowRadiusDp: Float = TouchPadMath.CURSOR_SHADOW_RADIUS_DP,
        val tapPulseExtra: Float = TouchPadMath.TAP_PULSE_EXTRA,
        val tapPulseMs: Long = TouchPadMath.TAP_PULSE_MS,
    ) {
        companion object {
            fun ai(): Spec = Spec(
                dotRadiusDp = TouchPadMath.AI_CURSOR_DOT_RADIUS_DP,
                shadowRadiusDp = TouchPadMath.AI_CURSOR_SHADOW_RADIUS_DP,
                tapPulseExtra = TouchPadMath.AI_TAP_PULSE_EXTRA,
                tapPulseMs = TouchPadMath.AI_TAP_PULSE_MS,
            )
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var mode = TouchPadCursorMode.NONE
    private var modeAt = 0L
    private var fillColor = 0xFF000000.toInt() or (cursorRgb and 0xFFFFFF)
    private val holdTint = -8719406
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (fillColor and 0x00FFFFFF) or 0x99000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadowShader: RadialGradient? = null
    private var shadowForW = 0
    private var shadowForH = 0
    private var pulseAt = 0L
    private var pulseExtra = TouchPadMath.DOUBLE_TAP_PULSE_EXTRA
    private var pulseDurationMs = TouchPadMath.DOUBLE_TAP_PULSE_MS
    private var nudgeX = 0f
    private var nudgeY = 0f
    private var nudgeAt = 0L
    private var nowX = 0f
    private var nowY = 0f
    private val trail = ArrayDeque<TrailDot>(TouchPadMath.CURSOR_TRAIL_POINTS)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trailStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trailPath = Path()
    private val anim = object : Runnable {
        override fun run() {
            pruneTrail()
            invalidate()
            if (mode != TouchPadCursorMode.NONE || pulsing() || nudging() || trail.isNotEmpty()) {
                handler.postDelayed(this, 16L)
            }
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = context.getString(R.string.touch_pad_cursor)
    }

    fun setFillRgb(rgb: Int, lightMode: Boolean = false) {
        // Light mode cursor is slightly transparent white-ish for a softer feel
        val alpha = if (lightMode) 0xE8 else 0xFF
        val next = (alpha shl 24) or (rgb and 0xFFFFFF)
        if (next == fillColor) return
        fillColor = next
        ringPaint.color = (fillColor and 0x00FFFFFF) or 0x99000000.toInt()
        invalidate()
    }

    fun nudgeScroll(dx: Float, dy: Float) {
        val max = TouchPadMath.dp(TouchPadMath.SCROLL_NUDGE_DP, resources.displayMetrics.density)
        val length = TouchPadMath.hypot(dx, dy).coerceAtLeast(0.01f)
        nudgeX = (dx / length) * max
        nudgeY = (dy / length) * max
        nudgeAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(anim)
        handler.post(anim)
        invalidate()
    }

    fun playTapPulse() {
        playPulse(spec.tapPulseExtra, spec.tapPulseMs)
    }

    fun playDoubleTapPulse() {
        playPulse(TouchPadMath.DOUBLE_TAP_PULSE_EXTRA, TouchPadMath.DOUBLE_TAP_PULSE_MS)
    }

    fun follow(screenX: Float, screenY: Float) {
        nowX = screenX
        nowY = screenY
        val last = trail.lastOrNull()
        if (last != null &&
            TouchPadMath.hypot(screenX - last.x, screenY - last.y) < 0.6f
        ) {
            last.at = SystemClock.uptimeMillis()
        } else {
            if (trail.size >= TouchPadMath.CURSOR_TRAIL_POINTS) trail.removeFirst()
            trail.addLast(TrailDot(screenX, screenY, SystemClock.uptimeMillis()))
        }
        handler.removeCallbacks(anim)
        handler.post(anim)
        invalidate()
    }

    fun clearTrail() {
        trail.clear()
        invalidate()
    }

    private fun playPulse(extra: Float, durationMs: Long) {
        pulseExtra = extra
        pulseDurationMs = durationMs
        pulseAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(anim)
        handler.post(anim)
        invalidate()
    }

    fun setMode(next: TouchPadCursorMode) {
        if (mode == next) return
        mode = next
        if (next != TouchPadCursorMode.NONE) {
            modeAt = SystemClock.uptimeMillis()
            handler.removeCallbacks(anim)
            handler.post(anim)
        } else if (!pulsing()) {
            handler.removeCallbacks(anim)
        }
        invalidate()
    }

    private fun pulsing(): Boolean {
        if (pulseAt <= 0L) return false
        return SystemClock.uptimeMillis() - pulseAt < pulseDurationMs
    }

    private fun nudging(): Boolean {
        if (nudgeAt <= 0L) return false
        return SystemClock.uptimeMillis() - nudgeAt < TouchPadMath.SCROLL_NUDGE_MS
    }

    private fun pruneTrail() {
        val cutoff = SystemClock.uptimeMillis() - TouchPadMath.CURSOR_TRAIL_MS
        while (trail.isNotEmpty() && trail.first().at < cutoff) trail.removeFirst()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(anim)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val density = resources.displayMetrics.density
        val pulseScale = TouchPadMath.cursorPulseScale(
            SystemClock.uptimeMillis() - pulseAt,
            pulseDurationMs,
            pulseExtra,
        )
        val holdScale = if (mode == TouchPadCursorMode.HOLD) TouchPadMath.HOLD_SCALE else 1f
        val radius = TouchPadMath.dp(spec.dotRadiusDp, density)
        val shadowR = TouchPadMath.dp(spec.shadowRadiusDp, density)
        if (shadowShader == null || shadowForW != width || shadowForH != height) {
            shadowForW = width
            shadowForH = height
            shadowShader = RadialGradient(
                cx,
                cy,
                shadowR,
                intArrayOf(
                    Color.argb(70, 0, 0, 0),
                    Color.argb(30, 0, 0, 0),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0.18f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        shadowPaint.shader = shadowShader
        val nudgeDecay = TouchPadMath.cursorNudgeDecay(SystemClock.uptimeMillis() - nudgeAt)
        canvas.save()
        canvas.translate(nudgeX * nudgeDecay, nudgeY * nudgeDecay)
        drawTrail(canvas, cx, cy, radius, density)
        canvas.scale(pulseScale * holdScale, pulseScale * holdScale, cx, cy)
        canvas.drawCircle(cx, cy, shadowR, shadowPaint)
        if (pulsing() && mode != TouchPadCursorMode.HOLD && mode != TouchPadCursorMode.AUTO_PAUSE) {
            val t = ((SystemClock.uptimeMillis() - pulseAt).toFloat() /
                pulseDurationMs.toFloat()).coerceIn(0f, 1f)
            // Ease-out fade so the ring melts away instead of snapping off
            val eased = 1f - t * t
            ringPaint.alpha = (eased * 90f).toInt().coerceIn(0, 90)
            canvas.drawCircle(cx, cy, radius + 0.6f * radius * t, ringPaint)
        }
        when (mode) {
            TouchPadCursorMode.NONE -> fillPaint.color = fillColor
            TouchPadCursorMode.SLIDE,
            TouchPadCursorMode.AUTO_PLAY -> {
                val period = if (mode == TouchPadCursorMode.AUTO_PLAY) 860L else 520L
                val t = ((SystemClock.uptimeMillis() - modeAt) % period) / period.toFloat()
                ringPaint.alpha = ((1f - t * t) * 88f).toInt().coerceIn(0, 88)
                canvas.drawCircle(cx, cy, radius + 0.72f * radius * t, ringPaint)
                fillPaint.color = fillColor
            }
            TouchPadCursorMode.HOLD -> {
                val wave = (((SystemClock.uptimeMillis() - modeAt) % 900L) / 900f)
                // Gentler sine pulse: only mix 35% toward holdTint
                val raw = (((sin(wave * Math.PI * 2.0) + 1.0) / 2.0).toFloat()).coerceIn(0f, 1f)
                val t = raw * 0.35f
                fillPaint.color = Color.argb(
                    Color.alpha(fillColor),
                    mix(Color.red(fillColor), Color.red(holdTint), t),
                    mix(Color.green(fillColor), Color.green(holdTint), t),
                    mix(Color.blue(fillColor), Color.blue(holdTint), t),
                )
            }
            TouchPadCursorMode.AUTO_PAUSE -> {
                val wave = (((SystemClock.uptimeMillis() - modeAt) % 1400L) / 1400f)
                val raw = (((sin(wave * Math.PI * 2.0) + 1.0) / 2.0).toFloat()).coerceIn(0f, 1f)
                fillPaint.color = Color.argb(
                    (Color.alpha(fillColor) * (0.62f + 0.28f * raw)).toInt().coerceIn(80, 255),
                    Color.red(fillColor),
                    Color.green(fillColor),
                    Color.blue(fillColor),
                )
            }
        }
        if (mode == TouchPadCursorMode.AUTO_PAUSE) {
            val barW = radius * 0.42f
            val barH = radius * 1.42f
            val gap = radius * 0.32f
            val r = density
            canvas.drawRoundRect(
                cx - gap - barW,
                cy - barH / 2f,
                cx - gap,
                cy + barH / 2f,
                r,
                r,
                fillPaint,
            )
            canvas.drawRoundRect(
                cx + gap,
                cy - barH / 2f,
                cx + gap + barW,
                cy + barH / 2f,
                r,
                r,
                fillPaint,
            )
        } else {
            canvas.drawCircle(cx, cy, radius, fillPaint)
        }
        canvas.restore()
    }

    private fun drawTrail(canvas: Canvas, cx: Float, cy: Float, radius: Float, density: Float) {
        pruneTrail()
        if (trail.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val life = TouchPadMath.CURSOR_TRAIL_MS.toFloat()
        trailPath.reset()
        var started = false
        for (dot in trail) {
            val k = (1f - (now - dot.at) / life).coerceIn(0f, 1f)
            if (k <= 0f) continue
            val lx = cx + (dot.x - nowX)
            val ly = cy + (dot.y - nowY)
            if (!started) {
                trailPath.moveTo(lx, ly)
                started = true
            } else {
                trailPath.lineTo(lx, ly)
            }
            trailPaint.color = Color.argb(
                (Color.alpha(fillColor) * 0.42f * k * k).toInt().coerceIn(0, 110),
                Color.red(fillColor),
                Color.green(fillColor),
                Color.blue(fillColor),
            )
            canvas.drawCircle(lx, ly, radius * (0.18f + 0.62f * k), trailPaint)
        }
        if (started) {
            trailStroke.strokeWidth = density * 2.4f
            trailStroke.color = Color.argb(
                (Color.alpha(fillColor) * 0.28f).toInt().coerceIn(0, 80),
                Color.red(fillColor),
                Color.green(fillColor),
                Color.blue(fillColor),
            )
            canvas.drawPath(trailPath, trailStroke)
        }
    }

    private fun mix(from: Int, to: Int, t: Float): Int =
        (from + (to - from) * t).toInt()

    private class TrailDot(var x: Float, var y: Float, var at: Long)
}

internal class TouchPadCloseView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -688523269
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    init {
        contentDescription = context.getString(R.string.touch_pad_close)
        isClickable = true
    }

    fun setStrokeColor(color: Int) {
        if (paint.color == color) return
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val arm = min(width, height) * 0.22f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, paint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, paint)
    }
}

/** Same L-bracket as the floating app window, hugging the bottom-right corner. */
internal class TouchPadCornerHandleView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59000000
        style = Paint.Style.STROKE
        strokeWidth = density * 5.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = density * 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init {
        contentDescription = context.getString(R.string.touch_pad_resize)
    }

    fun setColors(strokeColor: Int, haloColor: Int) {
        if (stroke.color == strokeColor && halo.color == haloColor) return
        stroke.color = strokeColor
        halo.color = haloColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = density * 9f
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

internal class TouchPadSurfaceView(
    context: Context,
    private val doubleTapTimeoutMs: Long,
    private val holdDelayMs: Long,
    private val listener: Listener,
) : View(context) {
    interface Listener {
        fun onPadTouched()
        fun onCursorBegan()
        fun onCursorDelta(dx: Float, dy: Float)
        fun onTap()
        fun onSecondaryTap()
        fun onDoubleTap()
        fun onHoldBegan()
        fun onHoldDelta(dx: Float, dy: Float)
        fun onHoldEnded()
        fun onHoldCancelled()
        fun onSwipeBegan()
        fun onSwipeDelta(dx: Float, dy: Float)
        fun onSwipeEnded()
        fun onSwipeCancelled()
        fun onTwoFingerBegan()
        fun onTwoFingerScroll(dx: Float, dy: Float)
        fun onTwoFingerBack()
        fun onTwoFingerEnded()
        fun onTwoFingerCancelled()
        fun onWindowLiftBegan(): Boolean
        fun onWindowLiftDelta(dx: Float, dy: Float)
        fun onWindowLiftEnded()
        fun onWindowLiftCancelled()
        fun onWindowPinchBegan(): Boolean
        fun onWindowPinchScale(scale: Float)
        fun onWindowPinchEnded()
        fun onWindowPinchCancelled()
        fun onThreeFingerRecents()
        /** Three-finger pull down: pad haptics while held, shade commits on lift. */
        fun onThreeFingerShadeArmed()
        fun onThreeFingerShadeDelta(dx: Float, dy: Float)
        fun onThreeFingerNotifications(dx: Float, dy: Float)
        fun onThreeFingerShadeCancelled()
        /** MacBook-style three-finger drag: hold at cursor, move, release on lift. */
        fun onThreeFingerDragBegan()
        fun onThreeFingerDragDelta(dx: Float, dy: Float)
        fun onThreeFingerDragEnded()
        fun onThreeFingerDragCancelled()
        fun onInteracted()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TouchPadTheme.palette(false).surface
        style = Paint.Style.FILL
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var lightGlass = false
    private var hintDx = 0f
    private var hintDy = 0f
    private var hintAt = 0L
    private val contactX = FloatArray(10)
    private val contactY = FloatArray(10)
    private var contactCount = 0
    private var contactsLive = false
    private var contactShownAt = 0L
    private val hintAnim = object : Runnable {
        override fun run() {
            invalidate()
            val elapsed = SystemClock.uptimeMillis() - hintAt
            val fadeMs = contactFadeMs()
            if (contactsLive || elapsed < fadeMs) {
                handler.postDelayed(this, 16L)
            } else if (contactCount > 0) {
                clearContacts()
            }
        }
    }
    private val clearContactsRunnable = Runnable { clearContacts() }
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var firstFingerAt = 0L
    private var twoFingerArrivalGap = 0L
    private var moving = false
    private var swiping = false
    private var holding = false
    private var secondTap = false
    private var waitingSecondTap = false
    private var twoFinger = false
    private var threeFinger = false
    private var threeFingerKind = TouchPadMath.ThreeFingerKind.NONE
    private var threeFingerOriginX = 0f
    private var threeFingerOriginY = 0f
    private var threeFingerDragging = false
    private var threeFingerShading = false
    private var threeFingerLastX = 0f
    private var threeFingerLastY = 0f
    private var windowLift = false
    private var pinching = false
    private var ignoreUntilUp = false
    private var skipPinch = false
    private var pinchSpan = 0f
    private var pinchStartSpan = 0f
    private var twoFingerTapCandidate = false
    private var twoFingerScroll = false
    private var skipCentroid = false
    private var twoFingerAt = 0L
    private var twoFingerOriginX = 0f
    private var twoFingerOriginY = 0f
    private var centroidX = 0f
    private var centroidY = 0f
    private var scrollAccX = 0f
    private var scrollAccY = 0f
    private var twoFingerMaxMove = 0f
    private val holdRunnable = Runnable {
        if (!moving && !swiping && !secondTap && !twoFinger && !threeFinger && !pinching) {
            holding = true
            waitingSecondTap = false
            listener.onHoldBegan()
            listener.onInteracted()
        }
    }
    private val tapRunnable = Runnable {
        waitingSecondTap = false
        OverlayHaptics.tick(context, this)
        listener.onTap()
        listener.onInteracted()
    }

    init {
        contentDescription = context.getString(R.string.touch_pad_touch_area)
        isClickable = true
        setWillNotDraw(false)
    }

    fun setFill(color: Int, lightGlass: Boolean = this.lightGlass) {
        this.lightGlass = lightGlass
        if (fill.color == color) {
            invalidate()
            return
        }
        fill.color = color
        invalidate()
    }

    fun showScrollHint(dx: Float, dy: Float) {
        if (TouchPadMath.hypot(dx, dy) < 0.2f) return
        hintDx = dx
        hintDy = dy
        hintAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(hintAnim)
        handler.post(hintAnim)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(holdRunnable)
        handler.removeCallbacks(tapRunnable)
        handler.removeCallbacks(hintAnim)
        handler.removeCallbacks(clearContactsRunnable)
        clearContacts()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        drawContacts(canvas)
    }

    private fun contactFadeMs(): Long =
        if (contactCount >= 3) TouchPadMath.THREE_CONTACT_FADE_MS else TouchPadMath.CONTACT_FADE_MS

    private fun contactPeakAlpha(): Int =
        if (contactCount >= 3) TouchPadMath.THREE_CONTACT_PEAK_ALPHA else TouchPadMath.SCROLL_PRESS_PEAK_ALPHA

    private fun contactAlpha(): Int {
        if (contactCount <= 0) return 0
        val peak = contactPeakAlpha()
        val fadeMs = contactFadeMs()
        val now = SystemClock.uptimeMillis()
        if (contactsLive) {
            if (contactCount < 3 || contactShownAt <= 0L) return peak
            val t = ((now - contactShownAt).toFloat() / TouchPadMath.THREE_CONTACT_IN_MS)
                .coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            return (eased * peak).toInt().coerceIn(0, peak)
        }
        val elapsed = now - hintAt
        if (hintAt <= 0L || elapsed >= fadeMs) return 0
        return TouchPadMath.scrollHintAlpha(elapsed.toFloat() / fadeMs.toFloat(), peak)
    }

    private fun drawContacts(canvas: Canvas) {
        val alpha = contactAlpha()
        if (alpha <= 0) return
        val density = resources.displayMetrics.density
        val three = contactCount >= 3
        val radiusDp = if (lightGlass) {
            TouchPadMath.SCROLL_PRESS_RADIUS_DP
        } else {
            TouchPadMath.SCROLL_PRESS_RADIUS_DARK_DP
        }
        val radius = TouchPadMath.dp(radiusDp, density)
            .coerceAtLeast(8f)
            .let { if (three) it * 1.05f else it }
        val maxLean = TouchPadMath.dp(TouchPadMath.SCROLL_PRESS_LEAN_DP, density)
        val lean = TouchPadMath.scrollPressLean(TouchPadMath.hypot(hintDx, hintDy), maxLean)
        val rgb = if (lightGlass) 0x2C2C2E else 0xFFFFFF
        val r = Color.red(rgb)
        val g = Color.green(rgb)
        val b = Color.blue(rgb)
        val haloScale = when {
            three && lightGlass -> TouchPadMath.THREE_CONTACT_HALO_SCALE
            three -> TouchPadMath.THREE_CONTACT_HALO_SCALE_DARK
            lightGlass -> TouchPadMath.CONTACT_HALO_SCALE
            else -> TouchPadMath.CONTACT_HALO_SCALE_DARK
        }
        // Same RGB at every stop — Color.TRANSPARENT is black@0 and interpolates
        // into a dark ring (the "black hole") on dark glass.
        val clear = Color.argb(0, r, g, b)
        val halo = Color.argb((alpha * haloScale).toInt().coerceIn(0, 255), r, g, b)
        for (i in 0 until contactCount) {
            val center = leaned(i, lean)
            pressPaint.shader = RadialGradient(
                center.x,
                center.y,
                radius,
                intArrayOf(clear, halo, clear),
                floatArrayOf(0.06f, 0.36f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(center.x, center.y, radius, pressPaint)
        }
        pressPaint.shader = null
    }

    private fun leaned(index: Int, lean: Float): TouchPadMath.Point =
        TouchPadMath.scrollPressCenter(
            contactX[index],
            contactY[index],
            hintDx,
            hintDy,
            lean,
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (TouchPadPageScroll.isGeneratedGesture(event)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fadeContacts()
            else -> captureContacts(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val isSecond = event.pointerCount == 1 &&
                    waitingSecondTap &&
                    System.currentTimeMillis() - downAt <= doubleTapTimeoutMs
                secondTap = isSecond
                if (isSecond) {
                    handler.removeCallbacks(tapRunnable)
                    waitingSecondTap = false
                }
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downAt = System.currentTimeMillis()
                firstFingerAt = SystemClock.uptimeMillis()
                moving = false
                swiping = false
                holding = false
                clearTwoFinger()
                listener.onPadTouched()
                if (!secondTap) {
                    handler.postDelayed(holdRunnable, holdDelayMs)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val wasHolding = holding
                if (holding) {
                    holding = false
                    listener.onHoldCancelled()
                }
                if (pinching) {
                    recapturePinch(event)
                    captureContacts(event)
                    return true
                }
                if (threeFinger) {
                    recaptureThreeFinger(event)
                    return true
                }
                // Three-finger watch must not steal a committed one/two-finger stream.
                if (event.pointerCount >= 3) {
                    if (windowLift) {
                        skipCentroid = true
                        captureCentroid(event)
                        return true
                    }
                    if (wasHolding || swiping || moving || twoFingerScroll) {
                        return true
                    }
                    beginThreeFingerWatch(event)
                    return true
                }
                if (windowLift) {
                    skipCentroid = true
                    captureCentroid(event)
                    return true
                }
                if (event.pointerCount >= 2 && !swiping && !twoFinger) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    beginTwoFinger(event, tapOk = !moving && !wasHolding)
                } else if (twoFinger && event.pointerCount >= 2) {
                    skipCentroid = true
                    captureCentroid(event)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (pinching) {
                    if (event.pointerCount <= 2) {
                        listener.onWindowPinchEnded()
                        listener.onInteracted()
                        pinching = false
                        ignoreUntilUp = true
                        fadeContacts()
                    } else {
                        recapturePinch(event)
                        captureContacts(event)
                    }
                    return true
                }
                if (threeFinger) {
                    captureCentroidAndSpan(event)
                    captureContacts(event)
                    if (event.pointerCount <= 3) {
                        finishThreeFinger(commit = true)
                        ignoreUntilUp = true
                    } else {
                        recaptureThreeFinger(event)
                    }
                    return true
                }
                if (windowLift) {
                    skipCentroid = true
                    captureCentroid(event)
                    return true
                }
                if (twoFinger) {
                    skipCentroid = true
                    if (event.pointerCount <= 2) {
                        flushTwoFingerScroll()
                    } else {
                        captureCentroid(event)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignoreUntilUp) return true
                if (pinching) {
                    handlePinchMove(event)
                    captureContacts(event)
                    return true
                }
                if (threeFinger) {
                    handleThreeFingerMove(event)
                    return true
                }
                if (windowLift) {
                    handleWindowLiftMove(event)
                    return true
                }
                if (twoFinger) {
                    handleTwoFingerMove(event)
                    return true
                }
                val dxFromDown = event.x - downX
                val dyFromDown = event.y - downY
                val dist = TouchPadMath.hypot(dxFromDown, dyFromDown)
                val stepX = event.x - lastX
                val stepY = event.y - lastY
                when {
                    swiping -> listener.onSwipeDelta(stepX, stepY)
                    holding -> {
                        listener.onHoldDelta(stepX, stepY)
                        listener.onInteracted()
                    }
                    else -> {
                        if (secondTap && dist > TouchPadMath.TAP_SLOP_PX) {
                            swiping = true
                            listener.onSwipeBegan()
                            listener.onSwipeDelta(dxFromDown, dyFromDown)
                            listener.onInteracted()
                        } else if (!moving && !holding && dist > TouchPadMath.TAP_SLOP_PX) {
                            moving = true
                            handler.removeCallbacks(holdRunnable)
                            listener.onCursorBegan()
                        }
                        if (moving && !secondTap) {
                            listener.onCursorDelta(stepX, stepY)
                            listener.onInteracted()
                        }
                    }
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                handler.removeCallbacks(holdRunnable)
                if (pinching) {
                    listener.onWindowPinchEnded()
                    listener.onInteracted()
                    fadeContacts()
                    resetTransient()
                    return true
                }
                if (threeFinger) {
                    captureCentroidAndSpan(event)
                    finishThreeFinger(commit = true)
                    resetTransient()
                    return true
                }
                if (ignoreUntilUp) {
                    fadeContacts()
                    resetTransient()
                    return true
                }
                if (windowLift) {
                    listener.onWindowLiftEnded()
                    listener.onInteracted()
                    resetTransient()
                    return true
                }
                if (twoFinger) {
                    finishTwoFinger()
                    fadeContacts()
                    resetTransient()
                    return true
                }
                val dist = TouchPadMath.hypot(event.x - downX, event.y - downY)
                when {
                    swiping -> {
                        listener.onSwipeEnded()
                        listener.onInteracted()
                    }
                    holding -> {
                        listener.onHoldEnded()
                        listener.onInteracted()
                    }
                    secondTap && dist <= TouchPadMath.TAP_SLOP_PX -> {
                        OverlayHaptics.click(context, this)
                        listener.onDoubleTap()
                        listener.onTap()
                        listener.onInteracted()
                    }
                    !moving && dist <= TouchPadMath.TAP_SLOP_PX -> {
                        OverlayHaptics.click(context, this)
                        listener.onTap()
                        listener.onInteracted()
                    }
                }
                resetTransient()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                handler.removeCallbacks(holdRunnable)
                handler.removeCallbacks(tapRunnable)
                if (pinching) {
                    listener.onWindowPinchCancelled()
                }
                if (threeFinger) {
                    finishThreeFinger(commit = false)
                }
                if (windowLift) {
                    listener.onWindowLiftCancelled()
                }
                if (twoFinger) {
                    twoFingerTapCandidate = false
                    flushTwoFingerScroll()
                    listener.onTwoFingerCancelled()
                }
                if (swiping) listener.onSwipeCancelled()
                if (holding) listener.onHoldEnded()
                fadeContacts()
                resetTransient()
                waitingSecondTap = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginThreeFingerWatch(event: MotionEvent) {
        if (twoFinger) {
            twoFinger = false
            listener.onTwoFingerCancelled()
        }
        handler.removeCallbacks(holdRunnable)
        handler.removeCallbacks(tapRunnable)
        waitingSecondTap = false
        secondTap = false
        threeFinger = true
        threeFingerKind = TouchPadMath.ThreeFingerKind.NONE
        skipCentroid = true
        ignoreUntilUp = false
        moving = false
        swiping = false
        parent?.requestDisallowInterceptTouchEvent(true)
        captureCentroidAndSpan(event)
        captureContacts(event)
        pinchStartSpan = pinchSpan
        threeFingerOriginX = centroidX
        threeFingerOriginY = centroidY
    }

    private fun handleThreeFingerMove(event: MotionEvent) {
        if (event.pointerCount < 3) return
        if (skipCentroid) {
            skipCentroid = false
            captureCentroidAndSpan(event)
            captureContacts(event)
            threeFingerLastX = centroidX
            threeFingerLastY = centroidY
            return
        }
        val prevX = centroidX
        val prevY = centroidY
        captureCentroidAndSpan(event)
        captureContacts(event)
        if (threeFingerKind == TouchPadMath.ThreeFingerKind.NONE) {
            threeFingerKind = TouchPadMath.threeFingerKindWithDrag(
                pinchStartSpan,
                pinchSpan,
                threeFingerOriginX,
                threeFingerOriginY,
                centroidX,
                centroidY,
            )
            if (threeFingerKind == TouchPadMath.ThreeFingerKind.PINCH &&
                listener.onWindowPinchBegan()
            ) {
                pinching = true
                threeFinger = false
                skipPinch = true
                listener.onWindowPinchScale(TouchPadMath.pinchScale(pinchStartSpan, pinchSpan))
                listener.onInteracted()
                return
            }
            if (threeFingerKind == TouchPadMath.ThreeFingerKind.PINCH) {
                threeFingerKind = TouchPadMath.ThreeFingerKind.NONE
            }
            if (threeFingerKind == TouchPadMath.ThreeFingerKind.DRAG) {
                threeFingerDragging = true
                threeFingerLastX = prevX
                threeFingerLastY = prevY
                listener.onThreeFingerDragBegan()
            }
            if (threeFingerKind == TouchPadMath.ThreeFingerKind.SWIPE &&
                !threeFingerShading
            ) {
                val swipeDx = centroidX - threeFingerOriginX
                val swipeDy = centroidY - threeFingerOriginY
                if (TouchPadMath.threeFingerSwipeIsNotifications(swipeDx, swipeDy)) {
                    threeFingerShading = true
                    threeFingerLastX = threeFingerOriginX
                    threeFingerLastY = threeFingerOriginY
                    listener.onThreeFingerShadeArmed()
                }
            }
        }
        if (threeFingerDragging) {
            val dx = centroidX - threeFingerLastX
            val dy = centroidY - threeFingerLastY
            threeFingerLastX = centroidX
            threeFingerLastY = centroidY
            listener.onThreeFingerDragDelta(dx, dy)
        } else if (threeFingerShading) {
            val dx = centroidX - threeFingerLastX
            val dy = centroidY - threeFingerLastY
            threeFingerLastX = centroidX
            threeFingerLastY = centroidY
            listener.onThreeFingerShadeDelta(dx, dy)
        }
        listener.onInteracted()
    }

    private fun recaptureThreeFinger(event: MotionEvent) {
        val previousSpan = pinchSpan
        val previousStart = pinchStartSpan
        captureCentroidAndSpan(event)
        captureContacts(event)
        pinchStartSpan = TouchPadMath.pinchRenormalizeStartSpan(
            previousStart,
            previousSpan,
            pinchSpan,
        )
        skipCentroid = true
    }

    private fun finishThreeFinger(commit: Boolean) {
        if (threeFingerDragging) {
            if (commit) {
                listener.onThreeFingerDragEnded()
            } else {
                listener.onThreeFingerDragCancelled()
            }
            threeFingerDragging = false
        } else if (threeFingerShading) {
            if (commit) {
                listener.onThreeFingerNotifications(
                    centroidX - threeFingerOriginX,
                    centroidY - threeFingerOriginY,
                )
                listener.onInteracted()
            } else {
                listener.onThreeFingerShadeCancelled()
            }
            threeFingerShading = false
        } else if (commit) {
            val dx = centroidX - threeFingerOriginX
            val dy = centroidY - threeFingerOriginY
            val travel = TouchPadMath.hypot(dx, dy)
            when {
                TouchPadMath.threeFingerSwipeIsRecents(dx, dy) -> {
                    listener.onThreeFingerRecents()
                    listener.onInteracted()
                }
                threeFingerKind == TouchPadMath.ThreeFingerKind.NONE &&
                    TouchPadMath.threeFingerIsTap(travel) -> {
                    listener.onSecondaryTap()
                    listener.onInteracted()
                }
            }
        }
        threeFinger = false
        threeFingerKind = TouchPadMath.ThreeFingerKind.NONE
        fadeContacts()
    }

    private fun handlePinchMove(event: MotionEvent) {
        if (event.pointerCount < 2) return
        if (skipPinch) {
            skipPinch = false
            captureCentroidAndSpan(event)
            captureContacts(event)
            return
        }
        captureCentroidAndSpan(event)
        captureContacts(event)
        listener.onWindowPinchScale(TouchPadMath.pinchScale(pinchStartSpan, pinchSpan))
        listener.onInteracted()
    }

    private fun recapturePinch(event: MotionEvent) {
        val previousSpan = pinchSpan
        val previousStart = pinchStartSpan
        captureCentroidAndSpan(event)
        captureContacts(event)
        pinchStartSpan = TouchPadMath.pinchRenormalizeStartSpan(
            previousStart,
            previousSpan,
            pinchSpan,
        )
        skipPinch = true
    }

    private fun captureCentroidAndSpan(event: MotionEvent) {
        val points = ArrayList<TouchPadMath.Point>(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            points.add(TouchPadMath.Point(event.getX(i), event.getY(i)))
        }
        val center = TouchPadMath.pinchCentroid(points)
        centroidX = center.x
        centroidY = center.y
        pinchSpan = TouchPadMath.pinchSpan(points)
    }

    private fun claimWindowLift(event: MotionEvent): Boolean {
        if (!listener.onWindowLiftBegan()) return false
        if (twoFinger) {
            twoFinger = false
            listener.onTwoFingerCancelled()
        }
        handler.removeCallbacks(holdRunnable)
        handler.removeCallbacks(tapRunnable)
        waitingSecondTap = false
        secondTap = false
        windowLift = true
        skipCentroid = true
        moving = false
        swiping = false
        parent?.requestDisallowInterceptTouchEvent(true)
        captureCentroid(event)
        return true
    }

    private fun handleWindowLiftMove(event: MotionEvent) {
        if (event.pointerCount < 1) return
        if (skipCentroid) {
            skipCentroid = false
            captureCentroid(event)
            return
        }
        val previousX = centroidX
        val previousY = centroidY
        captureCentroid(event)
        listener.onWindowLiftDelta(centroidX - previousX, centroidY - previousY)
        listener.onInteracted()
    }

    private fun beginTwoFinger(event: MotionEvent, tapOk: Boolean) {
        handler.removeCallbacks(holdRunnable)
        handler.removeCallbacks(tapRunnable)
        waitingSecondTap = false
        secondTap = false
        twoFinger = true
        twoFingerTapCandidate = tapOk
        twoFingerScroll = false
        twoFingerAt = SystemClock.uptimeMillis()
        twoFingerArrivalGap = if (firstFingerAt > 0L) {
            (twoFingerAt - firstFingerAt).coerceAtLeast(0L)
        } else {
            0L
        }
        twoFingerMaxMove = 0f
        scrollAccX = 0f
        scrollAccY = 0f
        skipCentroid = true
        moving = false
        captureCentroid(event)
        captureContacts(event)
        twoFingerOriginX = centroidX
        twoFingerOriginY = centroidY
        listener.onTwoFingerBegan()
    }

    private fun handleTwoFingerMove(event: MotionEvent) {
        if (event.pointerCount < 2) return
        if (skipCentroid) {
            skipCentroid = false
            captureCentroid(event)
            captureContacts(event)
            return
        }
        val previousX = centroidX
        val previousY = centroidY
        captureCentroid(event)
        captureContacts(event)
        val dx = centroidX - previousX
        val dy = centroidY - previousY
        twoFingerMaxMove = maxOf(
            twoFingerMaxMove,
            TouchPadMath.hypot(centroidX - twoFingerOriginX, centroidY - twoFingerOriginY),
        )
        if (twoFingerMaxMove > TouchPadMath.TAP_SLOP_PX) {
            twoFingerTapCandidate = false
            twoFingerScroll = true
        }
        if (!twoFingerScroll) return
        scrollAccX += dx
        scrollAccY += dy
        listener.onTwoFingerScroll(dx, dy)
        listener.onInteracted()
    }

    private fun finishTwoFinger() {
        val elapsed = SystemClock.uptimeMillis() - twoFingerAt
        val kind = TouchPadMath.twoFingerTapKind(
            tapCandidate = twoFingerTapCandidate,
            scrolled = twoFingerScroll,
            maxMovePx = twoFingerMaxMove,
            elapsedMs = elapsed,
            arrivalGapMs = twoFingerArrivalGap,
        )
        when (kind) {
            TouchPadMath.TwoFingerTapKind.BACK -> {
                listener.onTwoFingerBack()
                listener.onTwoFingerCancelled()
            }
            TouchPadMath.TwoFingerTapKind.NONE -> {
                flushTwoFingerScroll()
                listener.onTwoFingerEnded()
            }
        }
        listener.onInteracted()
    }

    private fun flushTwoFingerScroll() {
        scrollAccX = 0f
        scrollAccY = 0f
    }

    private fun captureContacts(event: MotionEvent) {
        val skip = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            event.actionIndex
        } else {
            -1
        }
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            if (n >= contactX.size) break
            contactX[n] = event.getX(i)
            contactY[n] = event.getY(i)
            n++
        }
        val wasLive = contactsLive
        val prevCount = contactCount
        contactCount = n
        contactsLive = n > 0
        if ((!wasLive && contactsLive) || (prevCount < 3 && n >= 3)) {
            contactShownAt = SystemClock.uptimeMillis()
        }
        hintAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(hintAnim)
        handler.post(hintAnim)
        invalidate()
    }

    private fun fadeContacts() {
        if (contactCount <= 0 && !contactsLive) return
        contactsLive = false
        hintAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(hintAnim)
        handler.removeCallbacks(clearContactsRunnable)
        handler.post(hintAnim)
        handler.postDelayed(clearContactsRunnable, contactFadeMs())
        invalidate()
    }

    private fun clearContacts() {
        handler.removeCallbacks(hintAnim)
        handler.removeCallbacks(clearContactsRunnable)
        contactCount = 0
        contactsLive = false
        contactShownAt = 0L
        hintAt = 0L
        hintDx = 0f
        hintDy = 0f
        invalidate()
    }

    private fun captureCentroid(event: MotionEvent) {
        var x = 0f
        var y = 0f
        val n = event.pointerCount
        if (n <= 0) return
        for (i in 0 until n) {
            x += event.getX(i)
            y += event.getY(i)
        }
        centroidX = x / n
        centroidY = y / n
    }

    private fun clearTwoFinger() {
        twoFinger = false
        threeFinger = false
        threeFingerKind = TouchPadMath.ThreeFingerKind.NONE
        threeFingerDragging = false
        threeFingerShading = false
        windowLift = false
        pinching = false
        ignoreUntilUp = false
        skipPinch = false
        pinchSpan = 0f
        pinchStartSpan = 0f
        twoFingerTapCandidate = false
        twoFingerScroll = false
        skipCentroid = false
        twoFingerMaxMove = 0f
        twoFingerArrivalGap = 0L
        scrollAccX = 0f
        scrollAccY = 0f
    }

    private fun resetTransient() {
        moving = false
        swiping = false
        holding = false
        secondTap = false
        clearTwoFinger()
    }
}
