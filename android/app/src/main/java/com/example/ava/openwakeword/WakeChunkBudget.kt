package com.example.ava.openwakeword

/**
 * Real-time budget for one openWakeWord scheduler tick: 640 samples at 16 kHz = 40 ms.
 * Matches [com.example.microfeatures.OpenWakeWordEngine.CHUNK_SAMPLES]; kept numeric so
 * unit tests never load the native library.
 */
data class WakeEngineBudgetProbe(
    val avgMs: Double = 0.0,
    val peakMs: Double = 0.0,
    val budgetMs: Int = WakeChunkBudget.BUDGET_MS,
    val samples: Int = 0,
    /** How many chunks in this window individually exceeded [budgetMs]. */
    val overChunks: Int = 0,
) {
    /**
     * A single GC/scheduler spike inside an otherwise fast window is not a real
     * overrun, so the flag needs a majority of chunks over budget rather than a
     * mean that one outlier can drag past the line. avgMs/peakMs still show the
     * raw numbers.
     */
    val overBudget: Boolean
        get() = samples > 0 && overChunks * 2 > samples

    fun formatDisplay(): String {
        if (samples <= 0) return "—"
        return String.format("%.1f avg · %.0f peak / %d ms", avgMs, peakMs, budgetMs)
    }
}

internal object WakeChunkBudget {
    const val BUDGET_MS = 40
    /** ~10 s of 40 ms ticks, so Voice Stats does not twitch on a single GC spike. */
    const val WINDOW_CHUNKS = 250
    /** Don't paint a 1-chunk window over a completed 10 s reading. */
    const val MIN_DISPLAY_CHUNKS = 16
}

/** Rolling 10 s mean/peak of [processChunk] wall time for Voice Stats. No logcat. */
internal class WakeChunkBudgetTracker(
    private val budgetMs: Double = WakeChunkBudget.BUDGET_MS.toDouble(),
    private val windowChunks: Int = WakeChunkBudget.WINDOW_CHUNKS,
    private val minDisplayChunks: Int = WakeChunkBudget.MIN_DISPLAY_CHUNKS,
) {
    private var count = 0
    private var sumNs = 0L
    private var peakNs = 0L
    private var overCount = 0
    private var lastSnapshot = WakeEngineBudgetProbe(budgetMs = budgetMs.toInt())

    fun reset() {
        count = 0
        sumNs = 0L
        peakNs = 0L
        overCount = 0
        lastSnapshot = WakeEngineBudgetProbe(budgetMs = budgetMs.toInt())
    }

    fun snapshot(): WakeEngineBudgetProbe {
        if (count >= minDisplayChunks) return current()
        if (lastSnapshot.samples > 0) return lastSnapshot
        return current()
    }

    fun record(elapsedNs: Long) {
        if (elapsedNs < 0L) return
        count++
        sumNs += elapsedNs
        if (elapsedNs > peakNs) peakNs = elapsedNs
        if (elapsedNs > budgetMs * 1_000_000.0) overCount++
        if (count < windowChunks) return
        lastSnapshot = current()
        count = 0
        sumNs = 0L
        peakNs = 0L
        overCount = 0
    }

    private fun current(): WakeEngineBudgetProbe {
        if (count <= 0) return WakeEngineBudgetProbe(budgetMs = budgetMs.toInt())
        return WakeEngineBudgetProbe(
            avgMs = (sumNs / count.toDouble()) / 1_000_000.0,
            peakMs = peakNs / 1_000_000.0,
            budgetMs = budgetMs.toInt(),
            samples = count,
            overChunks = overCount,
        )
    }
}
