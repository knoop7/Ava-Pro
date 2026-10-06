package com.example.ava.webcompat

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.webkit.WebView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Scroll assist for a Chromium [WebView] under the overlay pull-refresh.
 *
 * 1. Protect mid-page flings from parent interceptors (using JS-reported HA scroll when
 *    [WebView.canScrollVertically] lies about shadow-DOM scrollers).
 * 2. Drive `__avaScrollGate.pin` from the native gesture so freeze does not depend
 *    on window/shadow `scroll` events (those miss Lovelace flings).
 */
object BrowserScrollAssist {

    private const val MOVE_SLOP_PX = 14f
    private const val MIN_FLING_HOLD_MS = 420
    private const val MAX_FLING_HOLD_MS = 900

    /**
     * @param pullZonePx thin top strip in px (e.g. 96dp) — never half the screen
     * @param pullRefreshEnabled whether SwipeRefresh is active for this pane
     * @param haCanScrollUp JS-reported "content not at top" (shadow scrollers)
     * @param onActionDown focused-pane / side effects for ACTION_DOWN
     * @param shouldConsume if true, the listener swallows the event (touch/drag off)
     */
    @SuppressLint("ClickableViewAccessibility")
    fun install(
        webView: WebView,
        pullZonePx: () -> Int,
        pullRefreshEnabled: () -> Boolean,
        haCanScrollUp: () -> Boolean,
        onActionDown: () -> Unit,
        shouldConsume: (MotionEvent) -> Boolean,
    ) {
        var downX = 0f
        var downY = 0f
        var freezeArmed = false
        var tracker: VelocityTracker? = null

        fun atDocumentTop(): Boolean =
            !webView.canScrollVertically(-1) && !haCanScrollUp()

        fun pinJs(down: Boolean, holdMs: Int = 0) {
            val js = if (down) {
                "try{window.__avaScrollGate&&window.__avaScrollGate.pin(true)}catch(e){}"
            } else {
                "try{window.__avaScrollGate&&window.__avaScrollGate.pin(false,$holdMs)}catch(e){}"
            }
            webView.evaluateJavascript(js, null)
        }

        fun flingHoldMs(vt: VelocityTracker?): Int {
            if (vt == null) return MIN_FLING_HOLD_MS
            vt.computeCurrentVelocity(1000)
            val vy = abs(vt.yVelocity)
            // ~1ms per 2.5 px/s, clamped — covers short inertia on low-end panels.
            return min(MAX_FLING_HOLD_MS, max(MIN_FLING_HOLD_MS, (vy / 2.5f).toInt()))
        }

        webView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    freezeArmed = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    onActionDown()
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    if (abs(event.x - downX) > MOVE_SLOP_PX ||
                        abs(event.y - downY) > MOVE_SLOP_PX
                    ) {
                        val pullingDownForRefresh = pullRefreshEnabled() &&
                            atDocumentTop() &&
                            downY <= pullZonePx().toFloat() &&
                            event.y >= downY
                        if (!pullingDownForRefresh) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (!freezeArmed && !pullingDownForRefresh) {
                            freezeArmed = true
                            pinJs(true)
                        }
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    if (freezeArmed) {
                        pinJs(false, flingHoldMs(tracker))
                    }
                    freezeArmed = false
                    tracker?.recycle()
                    tracker = null
                }
            }
            shouldConsume(event)
        }
    }
}
