package com.example.ava.services

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.ava.R
import com.example.ava.utils.BrowserNavHintKind
import kotlin.math.roundToInt

/**
 * Tiny chevron for the browser overlay (back / forward / pull-refresh).
 * Idle [GONE] so it costs no draw; while a gesture is live it only writes
 * alpha / translation / rotation on a hardware layer. Always a child of the
 * overlay pane — never a second window.
 */
@SuppressLint("RtlHardcoded")
class BrowserNavHintView(context: Context) : ImageView(context) {

    private val travelPx = 10f * resources.displayMetrics.density
    private val refreshTravelPx = 6f * resources.displayMetrics.density
    private val insetPx = (18f * resources.displayMetrics.density).roundToInt()
    private var kind: BrowserNavHintKind? = null
    private var hiding = false
    private var refreshHoldAnimator: ObjectAnimator? = null
    var isHoldingRefresh: Boolean = false
        private set

    init {
        visibility = GONE
        alpha = 0f
        isClickable = false
        isLongClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
        scaleType = ScaleType.CENTER_INSIDE
        val pad = (12f * resources.displayMetrics.density).roundToInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x66000000)
        }
        setImageResource(R.drawable.mdi_arrow_left)
    }

    fun attach(
        container: FrameLayout,
        kind: BrowserNavHintKind = BrowserNavHintKind.BACK,
    ) {
        if (parent === container) {
            applyKindLayout(kind)
            return
        }
        (parent as? ViewGroup)?.removeView(this)
        val size = (56f * resources.displayMetrics.density).roundToInt()
        val lp = FrameLayout.LayoutParams(size, size)
        applyKindLayout(kind, lp)
        if (kind == BrowserNavHintKind.REFRESH) {
            container.addView(this, lp)
            return
        }
        val sidebarAt = (0 until container.childCount).firstOrNull { index ->
            container.getChildAt(index) is BrowserSidebarOverlayView
        }
        if (sidebarAt != null) {
            container.addView(this, sidebarAt, lp)
        } else {
            container.addView(this, lp)
        }
    }

    fun show(next: BrowserNavHintKind, progress: Float) {
        if (isHoldingRefresh) {
            if (next == BrowserNavHintKind.REFRESH) return
            stopRefreshHold()
        }
        animate().cancel()
        hiding = false
        if (kind != next) {
            kind = next
            setImageResource(
                when (next) {
                    BrowserNavHintKind.BACK -> R.drawable.mdi_arrow_left
                    BrowserNavHintKind.FORWARD -> R.drawable.mdi_arrow_right
                    BrowserNavHintKind.REFRESH -> R.drawable.mdi_refresh
                },
            )
            applyKindLayout(next)
            rotation = 0f
        }
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
        val p = progress.coerceIn(0f, 1f)
        alpha = 0.18f + p * 0.30f
        when (next) {
            BrowserNavHintKind.BACK -> {
                translationX = p * travelPx
                translationY = 0f
                rotation = 0f
            }
            BrowserNavHintKind.FORWARD -> {
                translationX = -p * travelPx
                translationY = 0f
                rotation = 0f
            }
            BrowserNavHintKind.REFRESH -> {
                translationX = 0f
                translationY = p * refreshTravelPx
                rotation = p * 45f
            }
        }
    }

    /** Stay in place and spin while the page actually reloads. */
    fun holdRefreshing() {
        animate().cancel()
        hiding = false
        isHoldingRefresh = true
        kind = BrowserNavHintKind.REFRESH
        setImageResource(R.drawable.mdi_refresh)
        applyKindLayout(BrowserNavHintKind.REFRESH)
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
        alpha = 0.48f
        translationX = 0f
        translationY = refreshTravelPx
        val start = rotation
        refreshHoldAnimator?.cancel()
        refreshHoldAnimator = ObjectAnimator.ofFloat(this, View.ROTATION, start, start + 360f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    fun dismiss(committed: Boolean) {
        if (hiding || (visibility != VISIBLE && alpha <= 0f)) return
        hiding = true
        val holding = isHoldingRefresh
        stopRefreshHold()
        animate().cancel()
        val fade = animate().alpha(0f)
        if (kind == BrowserNavHintKind.REFRESH && !holding) {
            fade.rotation(0f).translationY(0f)
        }
        fade.setDuration(if (committed) 180L else 160L)
            .withEndAction { rest() }
            .start()
    }

    fun release() {
        animate().cancel()
        rest()
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun applyKindLayout(
        kind: BrowserNavHintKind,
        lp: FrameLayout.LayoutParams? = layoutParams as? FrameLayout.LayoutParams,
    ) {
        if (lp == null) return
        when (kind) {
            BrowserNavHintKind.BACK -> {
                lp.gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
                lp.leftMargin = insetPx
                lp.rightMargin = insetPx
                lp.topMargin = 0
            }
            BrowserNavHintKind.FORWARD -> {
                lp.gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                lp.leftMargin = insetPx
                lp.rightMargin = insetPx
                lp.topMargin = 0
            }
            BrowserNavHintKind.REFRESH -> {
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.leftMargin = 0
                lp.rightMargin = 0
                lp.topMargin = insetPx
            }
        }
        if (layoutParams === lp) {
            layoutParams = lp
        }
    }

    private fun stopRefreshHold() {
        isHoldingRefresh = false
        refreshHoldAnimator?.cancel()
        refreshHoldAnimator = null
    }

    private fun rest() {
        stopRefreshHold()
        hiding = false
        alpha = 0f
        translationX = 0f
        translationY = 0f
        rotation = 0f
        visibility = GONE
        setLayerType(LAYER_TYPE_NONE, null)
        kind = null
    }
}
