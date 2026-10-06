package com.example.ava.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import kotlin.math.roundToInt

/**
 * Drop-in frosted-glass overlay that blurs whatever is drawn in the [BlurTarget]
 * beneath it. Inspired by Candy Browser's StatusBarFrostedGlass — adapted for
 * Ava's minSdk 21 (uses [PorterDuffXfermode] instead of API-29 [android.graphics.BlendMode]).
 *
 * Usage:
 * ```
 * val host = FrostedGlassHost(context)
 * host.addContentView(myView)                // the view to be blurred
 * windowManager.addView(host, layoutParams)  // instead of myView directly
 * host.setFrostedGlassVisible(true)          // show / hide
 * ```
 *
 * The overlay fades from a tinted blur at the top to fully transparent at the
 * bottom, matching the iOS-style status bar glass look. Height, blur radius,
 * and tint are configurable.
 */
class FrostedGlassHost(context: Context) : FrameLayout(context) {

    val blurTarget = BlurTarget(context)

    private val blurView = FrostedBlurView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
        setupWith(blurTarget, DEFAULT_SCALE_FACTOR, true)
            .setOverlayColor(android.graphics.Color.TRANSPARENT)
    }

    private var blurAutoUpdateEnabled = true

    init {
        addView(blurTarget, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(blurView, LayoutParams(LayoutParams.MATCH_PARENT, 0))
    }

    /** Adds [view] as the content that the blur captures. */
    fun addContentView(view: View) {
        blurTarget.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The content view sitting inside the blur target (first child, if any). */
    fun contentView(): View? = if (blurTarget.childCount > 0) blurTarget.getChildAt(0) else null

    /**
     * Show or hide the frosted-glass overlay.
     *
     * @param visible   whether the overlay is drawn
     * @param heightPx  overlay height in pixels (0 = full parent height)
     * @param blurRadius blur strength in px — 14 is a good starting point
     * @param tintColor ARGB tint applied as a gradient over the blur
     */
    fun setFrostedGlassVisible(
        visible: Boolean,
        heightPx: Int = 0,
        blurRadius: Float = DEFAULT_BLUR_RADIUS,
        tintColor: Int = DEFAULT_TINT,
    ) {
        val effectiveHeight = if (heightPx > 0) heightPx else this.height
        val show = visible && effectiveHeight > 0
        blurView.visibility = if (show) View.VISIBLE else View.GONE
        setBlurAutoUpdate(show)
        if (!show) return

        blurView.updateFade(effectiveHeight, tintColor)
        blurView.setBlurRadius(blurRadius)
        val lp = blurView.layoutParams
        if (lp.height != effectiveHeight) {
            lp.height = effectiveHeight
            blurView.layoutParams = lp
        }
    }

    fun release() {
        setBlurAutoUpdate(false)
    }

    private fun setBlurAutoUpdate(enabled: Boolean) {
        if (blurAutoUpdateEnabled == enabled) return
        blurView.setBlurAutoUpdate(enabled)
        blurAutoUpdateEnabled = enabled
    }

    companion object {
        const val DEFAULT_BLUR_RADIUS = 14f
        private const val DEFAULT_SCALE_FACTOR = 1f
        /** Semi-transparent black — subtle darkening over the blur. */
        const val DEFAULT_TINT = 0x60000000.toInt()
    }
}

/**
 * Custom [BlurView] subclass that overlays a gradient-masked tint on top of
 * the blur, fading from opaque at the top to transparent at the bottom.
 *
 * Uses [PorterDuffXfermode] (API 1+) instead of [android.graphics.BlendMode]
 * (API 29+) for full backward compatibility with Ava's minSdk 21.
 */
private class FrostedBlurView(context: Context) : BlurView(context) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fadeHeight = 0
    private var tint = android.graphics.Color.TRANSPARENT
    private var gradientCacheHeight = 0

    fun updateFade(fadeHeightPx: Int, tint: Int) {
        if (this.fadeHeight == fadeHeightPx && this.tint == tint) return
        this.fadeHeight = fadeHeightPx
        this.tint = tint
        gradientCacheHeight = 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradientCacheHeight = 0
    }

    override fun draw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        rebuildGradients()
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.draw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restoreToCount(layer)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
    }

    private fun rebuildGradients() {
        if (gradientCacheHeight == height) return
        val h = fadeHeight.coerceAtLeast(1).toFloat()
        maskPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            android.graphics.Color.WHITE,
            android.graphics.Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        tintPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                tint.withAlpha(0.54f),
                tint.withAlpha(0.24f),
                android.graphics.Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        gradientCacheHeight = height
    }
}

private fun Int.withAlpha(alpha: Float): Int =
    (this and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
