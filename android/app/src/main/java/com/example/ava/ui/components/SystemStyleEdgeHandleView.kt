package com.example.ava.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * View port of MiSystemUI [GlobalGestureAnimationView] + edge hit strip from
 * [GlobalGestureControlImpl] — shared with Compose [SystemStyleEdgeHandle].
 *
 * Fixed at the screen edge (does not slide with the drawer). Drag distance drives
 * shorten → arrow morph; any [showIndicator] / touch reveal fades in immediately.
 */
class SystemStyleEdgeHandleView(
    context: Context,
    private val mirrored: Boolean,
    private val onClick: () -> Unit,
    private val onDragStart: () -> Unit,
    private val onDrag: (deltaPx: Float) -> Unit,
    private val onDragEnd: (totalDeltaPx: Float, velocityPxPerSec: Float) -> Unit,
    private val onDragCancel: () -> Unit,
) : View(context) {

    var isDarkMode: Boolean = false
        set(value) {
            field = value
            pivotColor = if (value) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            invalidate()
        }

    private val metrics = SystemStyleEdgeHandleSpec.pxMetrics(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = metrics.strokeWidthPx
    }
    private val path = Path()

    private var pivotColor = 0xFF000000.toInt()
    private var pivotAlpha = 0f
    private var pivotHeight = metrics.pivotHeightPx
    private var pivotControlX = metrics.edgeOffsetPx
    private var pivotControlXOffset = 0f
    private var dragDistance = 0
    /** Drawer open 0…1 — multiplies draw alpha so the arrow fades as the panel opens. */
    private var drawerOpenFraction = 0f

    private val presentAnimator = ValueAnimator.ofFloat(0f, SystemStyleEdgeHandleSpec.PRESENT_ALPHA).apply {
        duration = SystemStyleEdgeHandleSpec.PRESENT_MS
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            pivotAlpha = it.animatedValue as Float
            invalidate()
        }
    }
    private val looseAnimator = ValueAnimator().apply {
        duration = SystemStyleEdgeHandleSpec.HIDE_MS
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { anim ->
            pivotHeight = anim.animatedValue as Float
            val remain = 1f - anim.animatedFraction
            pivotControlX = metrics.edgeOffsetPx + pivotControlXOffset * remain
            pivotAlpha = SystemStyleEdgeHandleSpec.PRESENT_ALPHA * remain
            if (remain <= 0f) {
                dragDistance = 0
                pivotAlpha = 0f
            }
            invalidate()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { animateHideInternal() }

    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var lastRawX = 0f
    private var isFingerDragging = false
    private var velocitySampleRawX = 0f
    private var velocitySampleTimeMs = 0L
    private var smoothedVelocityPxPerSec = 0f
    private var enabledForShow = true

    init {
        isClickable = true
        layoutParams = FrameLayout.LayoutParams(
            metrics.hitWidthPx.toInt().coerceAtLeast(1),
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
    }

    val hitWidthPx: Int get() = metrics.hitWidthPx.toInt().coerceAtLeast(1)

    fun setCanShow(canShow: Boolean) {
        enabledForShow = canShow
        if (!canShow) {
            cancelAnims()
            handler.removeCallbacks(autoHideRunnable)
            pivotAlpha = 0f
            pivotHeight = metrics.pivotHeightPx
            pivotControlX = metrics.edgeOffsetPx
            dragDistance = 0
            invalidate()
        }
    }

    /** Sync with drawer open progress so the handle fades out as the sidebar appears. */
    fun setDrawerOpenFraction(fraction: Float) {
        val next = fraction.coerceIn(0f, 1f)
        if (drawerOpenFraction == next) return
        drawerOpenFraction = next
        invalidate()
    }

    /** SystemUI showIndicator — fade in (if needed) and arm 3s auto-hide. */
    fun showIndicator() {
        if (!enabledForShow) return
        cancelAnims()
        handler.removeCallbacks(autoHideRunnable)
        if (pivotAlpha < SystemStyleEdgeHandleSpec.PRESENT_ALPHA - 0.01f) {
            pivotControlX = metrics.edgeOffsetPx
            pivotHeight = metrics.pivotHeightPx
            presentAnimator.setFloatValues(pivotAlpha.coerceAtLeast(0f), SystemStyleEdgeHandleSpec.PRESENT_ALPHA)
            presentAnimator.start()
        } else {
            pivotAlpha = SystemStyleEdgeHandleSpec.PRESENT_ALPHA
            invalidate()
        }
        handler.postDelayed(autoHideRunnable, SystemStyleEdgeHandleSpec.AUTO_HIDE_MS)
    }

    /**
     * SystemUI setDistance — live morph while pulling.
     * [distancePx] is directed open travel (positive toward open).
     */
    fun setDistance(distancePx: Float) {
        if (!enabledForShow) return
        looseAnimator.cancel()
        presentAnimator.cancel()
        pivotAlpha = SystemStyleEdgeHandleSpec.PRESENT_ALPHA
        handler.removeCallbacks(autoHideRunnable)
        updateDistance(distancePx.toInt().coerceAtLeast(0))
    }

    /**
     * Release morph.
     * - [fadeWithDrawer]: settling open — freeze morph, keep PRESENT; drawer fade owns hide.
     * - otherwise (close / cancel): snap morph to idle bar, keep PRESENT; caller should
     *   [showIndicator] once fully closed so the bar stays briefly then auto-hides.
     */
    fun animateReset(fadeWithDrawer: Boolean = false) {
        cancelAnims()
        handler.removeCallbacks(autoHideRunnable)
        if (fadeWithDrawer) {
            pivotAlpha = SystemStyleEdgeHandleSpec.PRESENT_ALPHA
            invalidate()
            return
        }
        dragDistance = 0
        pivotHeight = metrics.pivotHeightPx
        pivotControlX = metrics.edgeOffsetPx
        pivotControlXOffset = 0f
        pivotAlpha = SystemStyleEdgeHandleSpec.PRESENT_ALPHA
        invalidate()
    }

    fun notifyGlobalTouch() {
        if (enabledForShow && !isFingerDragging) {
            showIndicator()
        }
    }

    private fun updateDistance(distance: Int) {
        dragDistance = distance
        val (h, cx) = SystemStyleEdgeHandleSpec.morphPivot(
            distancePx = distance.toFloat(),
            maxHeightPx = metrics.pivotHeightPx,
            minHeightPx = metrics.pivotMinHeightPx,
            edgeOffsetPx = metrics.edgeOffsetPx,
            thresholdDonePx = metrics.thresholdDonePx,
            thresholdArrowEndPx = metrics.thresholdArrowEndPx,
        )
        pivotHeight = h
        pivotControlX = cx
        invalidate()
    }

    private fun animateHideInternal() {
        handler.removeCallbacks(autoHideRunnable)
        presentAnimator.cancel()
        pivotControlXOffset = pivotControlX - metrics.edgeOffsetPx
        looseAnimator.setFloatValues(pivotHeight, metrics.pivotHeightPx)
        looseAnimator.start()
    }

    private fun cancelAnims() {
        presentAnimator.cancel()
        looseAnimator.cancel()
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                lastRawX = downRawX
                isFingerDragging = false
                velocitySampleRawX = event.rawX
                velocitySampleTimeMs = event.eventTime
                smoothedVelocityPxPerSec = 0f
                if (enabledForShow) showIndicator()
            }
            MotionEvent.ACTION_MOVE -> {
                val totalDelta = event.rawX - downRawX
                if (!isFingerDragging && abs(totalDelta) > touchSlopPx) {
                    isFingerDragging = true
                    onDragStart()
                }
                if (isFingerDragging) {
                    onDrag(event.rawX - lastRawX)
                    lastRawX = event.rawX
                }
                val dtMs = (event.eventTime - velocitySampleTimeMs).coerceAtLeast(1L)
                val instantVelocity = (event.rawX - velocitySampleRawX) / dtMs * 1000f
                smoothedVelocityPxPerSec = smoothedVelocityPxPerSec * 0.3f + instantVelocity * 0.7f
                velocitySampleRawX = event.rawX
                velocitySampleTimeMs = event.eventTime
            }
            MotionEvent.ACTION_UP -> {
                if (isFingerDragging) {
                    onDragEnd(event.rawX - downRawX, smoothedVelocityPxPerSec)
                } else {
                    performClick()
                }
                isFingerDragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isFingerDragging) {
                    onDragCancel()
                }
                isFingerDragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val openFade = SystemStyleEdgeHandleSpec.openFade(drawerOpenFraction)
        val drawAlpha = pivotAlpha * openFade
        if (drawAlpha <= 0.01f) return
        paint.color = pivotColor
        paint.alpha = (255f * drawAlpha).toInt().coerceIn(0, 255)

        val cy = height / 2f
        val halfH = pivotHeight / 2f
        val halfW = metrics.strokeWidthPx / 2f
        // SystemUI onDraw (left): base = offset+w/2, control = controlX+w/2
        val baseX = if (mirrored) {
            width - metrics.edgeOffsetPx - halfW
        } else {
            metrics.edgeOffsetPx + halfW
        }
        val bow = pivotControlX - metrics.edgeOffsetPx
        val controlX = if (mirrored) baseX - bow else baseX + bow

        path.rewind()
        path.moveTo(baseX, cy - halfH)
        path.lineTo(controlX, cy)
        path.lineTo(baseX, cy + halfH)
        canvas.drawPath(path, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAnims()
        handler.removeCallbacks(autoHideRunnable)
    }
}
