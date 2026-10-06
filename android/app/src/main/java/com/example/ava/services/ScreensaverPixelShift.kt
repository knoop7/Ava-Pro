package com.example.ava.services

import android.os.Handler
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Subtle burn-in mitigation for long-lived screensaver overlays.
 *
 * Prefer a [Sink] that offsets drawn content while the opaque plate stays pinned
 * (Simple Clock). When a [View] must translate (WebView / Dawn), keep the host clipped
 * and size the child with at most [MAX_SHIFT_PX] (5px) bleed on each edge so edges
 * never open and magazine layouts stay stable.
 */
class ScreensaverPixelShift(
    private val handler: Handler,
    private val intervalMs: Long = INTERVAL_MS,
) {
    fun interface Sink {
        fun applyShift(dx: Float, dy: Float)
    }

    private var identity: Any? = null
    private var sink: Sink? = null
    private var reset: (() -> Unit)? = null
    private var enabled: Boolean = false
    /** True while hidden/disabled — next arm snaps to origin instead of ticking immediately. */
    private var resting: Boolean = true
    private val random = Random.Default

    private val tick = object : Runnable {
        override fun run() {
            val target = sink
            if (!enabled || target == null) return
            val id = identity
            if (id is View && !id.isAttachedToWindow) return
            target.applyShift(randomShiftPx(), randomShiftPx())
            handler.postDelayed(this, intervalMs)
        }
    }

    /** Translate [view] via [View.setTranslationX]/[View.setTranslationY]. */
    fun attach(view: View) {
        attachSink(
            identity = view,
            sink = Sink { dx, dy ->
                view.translationX = dx
                view.translationY = dy
            },
            reset = {
                view.translationX = 0f
                view.translationY = 0f
            },
        )
    }

    /**
     * Apply shifts through a custom sink (e.g. canvas content offset) without moving
     * the host window or the opaque background layer.
     */
    fun attachSink(
        identity: Any,
        sink: Sink,
        reset: () -> Unit = { sink.applyShift(0f, 0f) },
    ) {
        // Identity only — callers often pass fresh lambdas each sync.
        if (this.identity === identity) {
            this.sink = sink
            this.reset = reset
            sync("reattach")
            return
        }
        this.reset?.invoke()
        this.identity = identity
        this.sink = sink
        this.reset = reset
        sync("attach")
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) {
            if (enabled) ensureScheduled()
            return
        }
        this.enabled = enabled
        if (!enabled) resting = true
        sync(if (enabled) "enable" else "disable")
    }

    fun detach() {
        handler.removeCallbacks(tick)
        reset?.invoke()
        identity = null
        sink = null
        reset = null
        enabled = false
        resting = true
    }

    /** Stop the timer without snapping — hide must not twitch a live offset. */
    fun pause() {
        handler.removeCallbacks(tick)
        resting = true
        Log.d(TAG, "pause: hold offset")
    }

    fun resumeIfEnabled() {
        sync("resume")
    }

    private fun sync(reason: String) {
        handler.removeCallbacks(tick)
        val target = sink
        if (target == null || !enabled) {
            reset?.invoke()
            resting = true
            Log.d(TAG, "sync($reason): idle")
            return
        }
        if (resting) {
            // Show / first arm: origin now, first drift after the interval — not immediately.
            reset?.invoke()
            resting = false
            handler.postDelayed(tick, intervalMs)
            Log.d(TAG, "sync($reason): origin + schedule")
            return
        }
        // Settings collect / same-identity reattach while already showing: do not move.
        ensureScheduled()
        Log.d(TAG, "sync($reason): keep")
    }

    private fun ensureScheduled() {
        handler.removeCallbacks(tick)
        if (enabled && sink != null) {
            handler.postDelayed(tick, intervalMs)
        }
    }

    private fun randomShiftPx(): Float {
        val magnitude = MIN_SHIFT_PX + random.nextFloat() * (MAX_SHIFT_PX - MIN_SHIFT_PX)
        return if (random.nextBoolean()) magnitude else -magnitude
    }

    companion object {
        private const val TAG = "ScreensaverPixelShift"
        private const val INTERVAL_MS = 45_000L
        /** Inclusive floor; Dawn / WebView magazine layouts stay within 3–5px. */
        private const val MIN_SHIFT_PX = 3f
        /** Inclusive ceiling and bleed budget (px per edge). */
        const val MAX_SHIFT_PX = 5f

        /**
         * MATCH_PARENT child oversized by exactly [MAX_SHIFT_PX] on each side so a
         * translated layer never exposes the host. Use only under a clipped parent.
         */
        fun bleedLayoutParams(): FrameLayout.LayoutParams {
            val bleed = ceil(MAX_SHIFT_PX.toDouble()).toInt() // 5
            return FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                leftMargin = -bleed
                topMargin = -bleed
                rightMargin = -bleed
                bottomMargin = -bleed
            }
        }
    }
}
