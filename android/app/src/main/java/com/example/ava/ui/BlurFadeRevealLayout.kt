package com.example.ava.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.example.ava.ui.glass.LiquidGlass

/**
 * Screensaver enter/exit: blur → sharp + fade (+ slight scale).
 *
 * - API 31+: real [RenderEffect] blur radius animates down (in) / up (out).
 * - API 21–30: alpha + scale only — same motion language, safe on low-end GPUs.
 *
 * Keep the host [View.GONE] while idle so it cannot steal touches and reset the
 * screensaver idle timer (a VISIBLE transparent overlay would).
 */
class BlurFadeRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var revealAnimator: Animator? = null

    init {
        // Start hidden until revealIn().
        alpha = 0f
        scaleX = START_SCALE
        scaleY = START_SCALE
        visibility = View.GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w * 0.5f
        pivotY = h * 0.5f
    }

    fun cancelReveal() {
        revealAnimator?.let { anim ->
            try {
                anim.removeAllListeners()
                anim.cancel()
            } catch (_: Exception) {
            }
        }
        revealAnimator = null
    }

    fun snapHidden() {
        cancelReveal()
        clearBlur()
        alpha = 0f
        scaleX = START_SCALE
        scaleY = START_SCALE
        // GONE — not VISIBLE+alpha0 — avoids full-screen touch shield resetting idle timer.
        visibility = View.GONE
    }

    fun snapShown() {
        cancelReveal()
        clearBlur()
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        visibility = View.VISIBLE
    }

    fun revealIn(durationMs: Long = REVEAL_IN_MS, onEnd: (() -> Unit)? = null) {
        cancelReveal()
        visibility = View.VISIBLE
        alpha = 0f
        scaleX = START_SCALE
        scaleY = START_SCALE
        applyBlur(LiquidGlass.revealBlurRadiusPx().coerceAtMost(MAX_BLUR_PX * 2f))

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                applyProgress(a.animatedValue as Float)
            }
            addListener(endListener {
                clearBlur()
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
                onEnd?.invoke()
            })
        }
        revealAnimator = anim
        anim.start()
    }

    fun revealOut(durationMs: Long = REVEAL_OUT_MS, onEnd: (() -> Unit)? = null) {
        cancelReveal()
        visibility = View.VISIBLE
        val startAlpha = alpha.coerceIn(0.001f, 1f)
        if (startAlpha <= 0.001f) {
            snapHidden()
            onEnd?.invoke()
            return
        }
        // Capture current visual as "fully shown" baseline for reverse progress.
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        clearBlur()

        val anim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = durationMs
            interpolator = AccelerateInterpolator()
            addUpdateListener { a ->
                applyProgress(a.animatedValue as Float)
            }
            addListener(endListener {
                snapHidden()
                onEnd?.invoke()
            })
        }
        revealAnimator = anim
        anim.start()
    }

    /**
     * @param t 0 = hidden (blurred / faded), 1 = fully sharp & opaque
     */
    private fun applyProgress(t: Float) {
        val p = t.coerceIn(0f, 1f)
        alpha = p
        val scale = START_SCALE + (1f - START_SCALE) * p
        scaleX = scale
        scaleY = scale
        // Blur strongest when hidden; clear when fully shown. Peak follows glass intensity.
        applyBlur(LiquidGlass.revealBlurRadiusPx().coerceAtMost(MAX_BLUR_PX * 2f) * (1f - p))
    }

    private fun applyBlur(radiusPx: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Liquid Glass off → alpha + scale only, same as the API < 31 path.
        if (!LiquidGlass.enabled) {
            clearBlur()
            return
        }
        try {
            if (radiusPx < 0.5f) {
                setRenderEffect(null)
            } else {
                setRenderEffect(
                    RenderEffect.createBlurEffect(
                        radiusPx,
                        radiusPx,
                        Shader.TileMode.CLAMP
                    )
                )
            }
        } catch (_: Exception) {
            setRenderEffect(null)
        }
    }

    private fun clearBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                setRenderEffect(null)
            } catch (_: Exception) {
            }
        }
    }

    private fun endListener(onEnd: (() -> Unit)?): AnimatorListenerAdapter {
        return object : AnimatorListenerAdapter() {
            private var canceled = false
            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }
            override fun onAnimationEnd(animation: Animator) {
                if (canceled) return
                revealAnimator = null
                onEnd?.invoke()
            }
        }
    }

    companion object {
        /** Slightly slower so a quick glance / dismiss doesn't feel harsh. */
        const val REVEAL_IN_MS = 620L
        const val REVEAL_OUT_MS = 480L
        /** Match preview: slight overscale while blurred. */
        private const val START_SCALE = 1.04f
        private const val MAX_BLUR_PX = 18f

        /** Prefer enter duration when a single constant is needed by callers. */
        const val REVEAL_MS = REVEAL_IN_MS
    }
}
