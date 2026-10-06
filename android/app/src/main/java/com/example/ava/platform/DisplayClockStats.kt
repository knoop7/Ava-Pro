package com.example.ava.platform

/**
 * HAL vsync ([android.view.Choreographer] frame time) minus [System.nanoTime].
 * Positive = HAL is in the future (the system_server warning). No OEM lists —
 * thresholds are the measured nanoseconds.
 */
object DisplayClockStats {
    const val WINDOW_MS = 2_000L
    const val MIN_SAMPLES = 40
    const val FUTURE_WARN_NS = 1_000_000L
    const val FUTURE_UNSTABLE_NS = 2_000_000L
    const val LATE_UNSTABLE_NS = 8_000_000L
    const val LATE_RATIO_UNSTABLE = 0.05f

    fun percentile(sortedAsc: LongArray, size: Int, percent: Int): Long {
        if (size <= 0) return 0L
        val i = ((size - 1) * percent.coerceIn(0, 100)) / 100
        return sortedAsc[i]
    }

    fun futureOver1ms(values: LongArray, size: Int): Int {
        var n = 0
        var i = 0
        while (i < size) {
            if (values[i] > FUTURE_WARN_NS) n++
            i++
        }
        return n
    }

    fun lateOver8ms(values: LongArray, size: Int): Int {
        var n = 0
        var i = 0
        while (i < size) {
            if (values[i] < -LATE_UNSTABLE_NS) n++
            i++
        }
        return n
    }

    fun futureRatio(values: LongArray, size: Int): Float {
        if (size <= 0) return 0f
        var n = 0
        var i = 0
        while (i < size) {
            if (values[i] > 0L) n++
            i++
        }
        return n.toFloat() / size.toFloat()
    }

    /**
     * First window must be full. Hysteresis: enter frost above 2ms p95 or 5%
     * late-by-8ms; leave only when p95 is under 1ms and nothing was 8ms late.
     */
    fun decideFrost(
        previouslyFrost: Boolean,
        ready: Boolean,
        skewP95Ns: Long,
        lateOver8msRatio: Float,
    ): Boolean {
        if (!ready) return previouslyFrost
        val unstable = skewP95Ns > FUTURE_UNSTABLE_NS ||
            lateOver8msRatio >= LATE_RATIO_UNSTABLE
        if (unstable) return true
        val stable = skewP95Ns < FUTURE_WARN_NS && lateOver8msRatio <= 0f
        return if (previouslyFrost) !stable else false
    }
}
