package com.example.ava.touchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View

/** Brief fade-in / fade-out dots. They are the last place, not a leftover map. */
internal class TouchPadAutoMarksView(context: Context) : View(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val marks = ArrayList<Mark>(MAX_MARKS)
    private var fillRgb = 0xE8E8E8
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val tick = object : Runnable {
        override fun run() {
            prune()
            invalidate()
            if (marks.isNotEmpty()) handler.postDelayed(this, 16L)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun setFillRgb(rgb: Int) {
        val next = rgb and 0xFFFFFF
        if (next == fillRgb) return
        fillRgb = next
        invalidate()
    }

    fun clear() {
        marks.clear()
        handler.removeCallbacks(tick)
        invalidate()
    }

    fun flash(step: TouchPadAutoStep) {
        when (step.type) {
            TouchPadAutoStepType.TAP ->
                flash(Kind.TAP, step.x, step.y, step.x, step.y)
            TouchPadAutoStepType.PRESS ->
                flash(Kind.PRESS, step.x, step.y, step.x, step.y)
            TouchPadAutoStepType.SLIDE ->
                flash(Kind.SLIDE, step.x, step.y, step.x2, step.y2)
            TouchPadAutoStepType.SCROLL ->
                flash(Kind.SCROLL, step.x, step.y, step.x + step.x2, step.y + step.y2)
            TouchPadAutoStepType.SIDEBAR ->
                flash(Kind.SCROLL, step.x, step.y, step.x + step.x2, step.y)
            TouchPadAutoStepType.BACK,
            TouchPadAutoStepType.SECONDARY,
            TouchPadAutoStepType.RECENTS ->
                flash(Kind.ACTION, step.x, step.y, step.x, step.y)
            TouchPadAutoStepType.DELAY,
            TouchPadAutoStepType.ROUTE,
            TouchPadAutoStepType.WINDOW,
            TouchPadAutoStepType.KEY,
            TouchPadAutoStepType.TEXT -> Unit
        }
    }

    fun flash(
        kind: Kind,
        x: Float,
        y: Float,
        x2: Float = x,
        y2: Float = y,
    ) {
        if (marks.size >= MAX_MARKS) marks.removeAt(0)
        marks.add(Mark(kind, x, y, x2, y2, SystemClock.uptimeMillis()))
        kick()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (marks.isEmpty()) return
        val density = resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()
        val radius = density * 6.2f
        stroke.strokeWidth = density * 2.1f
        for (mark in marks) {
            val fade = fade(now - mark.bornAt)
            if (fade <= 0.02f) continue
            val alpha = (220f * fade).toInt().coerceIn(0, 220)
            ink(alpha)
            val r = radius * (0.88f + 0.12f * fade)
            when (mark.kind) {
                Kind.SLIDE, Kind.SCROLL -> {
                    path.reset()
                    path.moveTo(mark.x, mark.y)
                    path.lineTo(mark.x2, mark.y2)
                    canvas.drawPath(path, stroke)
                    canvas.drawCircle(mark.x, mark.y, r * 0.72f, fill)
                    canvas.drawCircle(mark.x2, mark.y2, r, fill)
                }
                Kind.ACTION -> {
                    val s = r * 1.15f
                    canvas.drawRoundRect(
                        mark.x - s,
                        mark.y - s,
                        mark.x + s,
                        mark.y + s,
                        density,
                        density,
                        fill,
                    )
                }
                Kind.PRESS -> {
                    canvas.drawCircle(mark.x, mark.y, r, fill)
                    stroke.alpha = (alpha * 0.72f).toInt()
                    canvas.drawCircle(mark.x, mark.y, r * 1.55f, stroke)
                }
                Kind.TAP -> canvas.drawCircle(mark.x, mark.y, r, fill)
            }
        }
    }

    private fun fade(ageMs: Long): Float {
        if (ageMs <= 0L) return 0f
        if (ageMs < FADE_IN_MS) {
            val t = ageMs / FADE_IN_MS.toFloat()
            return t * t
        }
        val outAt = FADE_IN_MS + HOLD_MS
        if (ageMs < outAt) return 1f
        val t = ((ageMs - outAt) / FADE_OUT_MS.toFloat()).coerceIn(0f, 1f)
        val u = 1f - t
        return u * u
    }

    private fun prune() {
        val now = SystemClock.uptimeMillis()
        marks.removeAll { now - it.bornAt >= LIFE_MS }
    }

    private fun ink(alpha: Int) {
        val color = (alpha shl 24) or fillRgb
        fill.color = color
        stroke.color = color
    }

    private fun kick() {
        invalidate()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    enum class Kind { TAP, PRESS, SLIDE, SCROLL, ACTION }

    private class Mark(
        val kind: Kind,
        val x: Float,
        val y: Float,
        val x2: Float,
        val y2: Float,
        val bornAt: Long,
    )

    companion object {
        const val MAX_MARKS = 8
        const val FADE_IN_MS = 140L
        const val HOLD_MS = 220L
        const val FADE_OUT_MS = 420L
        const val LIFE_MS = FADE_IN_MS + HOLD_MS + FADE_OUT_MS
    }
}
