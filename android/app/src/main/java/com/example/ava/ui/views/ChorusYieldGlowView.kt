package com.example.ava.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.ava.ui.VoiceAccentColors
import kotlin.math.sqrt

/**
 * Three static dots at the bottom center for 一呼百应 losers.
 * Wake-word accent color. Level only changes size; no extra glow layer.
 */
class ChorusYieldGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var accent = VoiceAccentColors.WAKE_WORD_1
    private var level = 0f
    private var target = 0f

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = false
        isFocusable = false
        rebuildPaint()
    }

    fun setAccentColor(color: Int) {
        accent = color
        rebuildPaint()
        invalidate()
    }

    fun feedLevel(raw: Float) {
        val unit = raw.coerceIn(0f, 1f)
        target = (unit * 0.35f + sqrt(unit) * 0.65f).coerceIn(0f, 1f)
        level += (target - level) * 0.28f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = resources.displayMetrics.density
        val diameter = (DOT_MIN_DP + DOT_GROW_DP * level) * density
        val radius = diameter / 2f
        val gap = DOT_GAP_DP * density
        val inset = INSET_DP * density
        val step = diameter + gap
        val y = h - inset - radius
        val midX = w / 2f
        val xs = floatArrayOf(midX - step, midX, midX + step)
        for (x in xs) {
            canvas.drawCircle(x, y, radius, dotPaint)
        }
    }

    private fun rebuildPaint() {
        dotPaint.color = Color.argb(
            FILL_ALPHA,
            Color.red(accent),
            Color.green(accent),
            Color.blue(accent),
        )
    }

    private companion object {
        const val DOT_MIN_DP = 14f
        const val DOT_GROW_DP = 16f
        const val DOT_GAP_DP = 14f
        const val INSET_DP = 20f
        const val FILL_ALPHA = 210
    }
}
