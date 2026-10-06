package com.example.ava.touchpad

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Translucent record clock: one hand per minute, no digits. */
internal class TouchPadRecordDialDrawable : Drawable() {
    var startedAt = SystemClock.uptimeMillis()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = TouchPadTheme.RECORD_FILL
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = TouchPadTheme.RECORD_TICK
    }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = TouchPadTheme.RECORD_HAND
    }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = TouchPadTheme.RECORD_HAND
    }

    fun restart() {
        startedAt = SystemClock.uptimeMillis()
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val radius = min(b.width(), b.height()) / 2f
        if (radius <= 1f) return
        canvas.drawCircle(cx, cy, radius, fill)
        tick.strokeWidth = (radius * 0.07f).coerceAtLeast(1.2f)
        for (i in 0 until 12) {
            val rad = Math.toRadians(i * 30.0 - 90.0)
            val inner = radius * if (i % 3 == 0) 0.68f else 0.76f
            val outer = radius * 0.90f
            canvas.drawLine(
                cx + cos(rad).toFloat() * inner,
                cy + sin(rad).toFloat() * inner,
                cx + cos(rad).toFloat() * outer,
                cy + sin(rad).toFloat() * outer,
                tick,
            )
        }
        val turn = ((SystemClock.uptimeMillis() - startedAt) % TouchPadTheme.RECORD_SWEEP_MS) /
            TouchPadTheme.RECORD_SWEEP_MS.toFloat()
        val rad = (turn * Math.PI * 2.0) - Math.PI / 2.0
        hand.strokeWidth = (radius * 0.11f).coerceAtLeast(1.6f)
        canvas.drawLine(
            cx,
            cy,
            cx + cos(rad).toFloat() * radius * 0.58f,
            cy + sin(rad).toFloat() * radius * 0.58f,
            hand,
        )
        canvas.drawCircle(cx, cy, radius * 0.12f, hub)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = (android.graphics.Color.alpha(TouchPadTheme.RECORD_FILL) * alpha) / 255
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        tick.colorFilter = colorFilter
        hand.colorFilter = colorFilter
        hub.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
