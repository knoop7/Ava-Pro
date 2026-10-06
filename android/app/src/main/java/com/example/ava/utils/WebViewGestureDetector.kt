package com.example.ava.utils

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

enum class BrowserNavHintKind {
    BACK,
    FORWARD,
    REFRESH,
}

/**
 * Edge-swipe navigation for the browser overlay. Works with both WebView and GeckoView by
 * routing back/forward through callbacks instead of touching [android.webkit.WebView] directly.
 */
class WebViewGestureDetector(
    context: Context,
    private val canGoBack: () -> Boolean,
    private val canGoForward: () -> Boolean,
    private val onGoBack: () -> Unit,
    private val onGoForward: () -> Unit,
    private val onGestureAction: ((GestureAction) -> Unit)? = null,
    /**
     * Lets callers (e.g. the browser edge sidebar handle) claim a screen region as their own.
     * When a touch starts inside this zone, back/forward navigation is skipped entirely so the
     * two edge gestures never fight over the same touch stream.
     */
    private val isInExcludedZone: ((x: Float, y: Float) -> Boolean)? = null,
    private val onHintProgress: ((BrowserNavHintKind, Float) -> Unit)? = null,
    private val onHintEnd: ((committed: Boolean) -> Unit)? = null,
) {

    sealed class GestureAction {
        object SwipeLeft : GestureAction()
        object SwipeRight : GestureAction()
    }

    companion object {
        private const val EDGE_ZONE_WIDTH_DP = 32
        private const val NAVIGATION_SWIPE_DISTANCE_DP = 72
    }

    private var startX = 0f
    private var startY = 0f
    private var isEdgeSwipe = false
    private var navigationTriggered = false
    private var excludedZone = false
    private var hintLive = false
    private val edgeZoneWidthPx = context.resources.displayMetrics.density * EDGE_ZONE_WIDTH_DP
    private val navigationSwipeDistancePx =
        context.resources.displayMetrics.density * NAVIGATION_SWIPE_DISTANCE_DP
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isEdgeSwipe = startX < edgeZoneWidthPx
                navigationTriggered = false
                excludedZone = isInExcludedZone?.invoke(startX, startY) == true
                endHint(committed = false)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!navigationTriggered && dispatchHint(event)) {
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!navigationTriggered && dispatchHint(event)) {
                    isEdgeSwipe = false
                    return true
                }
                endHint(committed = false)
                isEdgeSwipe = false
            }
            MotionEvent.ACTION_CANCEL -> {
                endHint(committed = false)
                isEdgeSwipe = false
            }
        }

        return navigationTriggered
    }

    /** @return true when this event committed back/forward. */
    private fun dispatchHint(event: MotionEvent): Boolean {
        val hint = currentHint(event)
        if (hint != null && hint.progress >= 1f) {
            when (hint.kind) {
                BrowserNavHintKind.BACK -> {
                    navigationTriggered = true
                    onGoBack()
                    onGestureAction?.invoke(GestureAction.SwipeRight)
                }
                BrowserNavHintKind.FORWARD -> {
                    navigationTriggered = true
                    onGoForward()
                    onGestureAction?.invoke(GestureAction.SwipeLeft)
                }
                BrowserNavHintKind.REFRESH -> {
                    endHint(committed = false)
                    return false
                }
            }
            endHint(committed = true)
            return true
        }
        if (hint == null) {
            endHint(committed = false)
        } else {
            hintLive = true
            onHintProgress?.invoke(hint.kind, hint.progress)
        }
        return false
    }

    private fun endHint(committed: Boolean) {
        if (!hintLive && !committed) return
        hintLive = false
        onHintEnd?.invoke(committed)
    }

    private fun currentHint(event: MotionEvent): BrowserNavHint? {
        if (excludedZone) return null
        return browserNavHint(
            diffX = event.x - startX,
            diffY = event.y - startY,
            isEdgeSwipe = isEdgeSwipe,
            excludedZone = false,
            canGoBack = canGoBack(),
            canGoForward = canGoForward(),
            touchSlop = touchSlop,
            distancePx = navigationSwipeDistancePx,
        )
    }
}

internal data class BrowserNavHint(
    val kind: BrowserNavHintKind,
    val progress: Float,
)

internal fun browserNavHint(
    diffX: Float,
    diffY: Float,
    isEdgeSwipe: Boolean,
    excludedZone: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    touchSlop: Float,
    distancePx: Float,
): BrowserNavHint? {
    if (excludedZone) return null
    if (abs(diffX) < touchSlop || abs(diffX) < abs(diffY)) return null
    val span = distancePx.coerceAtLeast(1f)
    return when {
        diffX > 0f && isEdgeSwipe && canGoBack ->
            BrowserNavHint(BrowserNavHintKind.BACK, (diffX / span).coerceIn(0f, 1f))
        diffX < 0f && canGoForward ->
            BrowserNavHint(BrowserNavHintKind.FORWARD, ((-diffX) / span).coerceIn(0f, 1f))
        else -> null
    }
}
