package com.example.ava.wakelearn

/**
 * Trailing micro-frontend feature history, snapshotted as the micro engine's verifier
 * input the instant a model fires. microWakeWord models are streaming graphs with no
 * exposed input window, so the verifier sees the last [FRAMES] 10 ms feature frames
 * (1.2 s — covers the phrase plus its tail for every bundled model) mean-pooled in pairs
 * to keep the head small: [FRAMES] / [POOL] x featureDim values, 2400 for the 40-bin
 * frontend.
 */
class MicroVerifierWindow {
    private var featureDim = 0
    private var ring: FloatArray = FloatArray(0)
    private var writeFrame = 0
    private var filled = 0

    val dims: Int get() = if (featureDim == 0) 0 else (FRAMES / POOL) * featureDim

    fun push(features: FloatArray) {
        if (features.isEmpty()) return
        if (features.size != featureDim) {
            featureDim = features.size
            ring = FloatArray(FRAMES * featureDim)
            writeFrame = 0
            filled = 0
        }
        System.arraycopy(features, 0, ring, writeFrame * featureDim, featureDim)
        writeFrame = (writeFrame + 1) % FRAMES
        if (filled < FRAMES) filled++
    }

    /** Pooled window, oldest first; missing history (right after start) reads as zeros. */
    fun snapshot(): FloatArray? {
        if (featureDim == 0) return null
        val pooled = FRAMES / POOL
        val out = FloatArray(pooled * featureDim)
        // Chronological index 0 is the oldest of the FRAMES slots.
        val oldest = (writeFrame - filled + FRAMES) % FRAMES
        val missing = FRAMES - filled
        for (p in 0 until pooled) {
            for (k in 0 until POOL) {
                val chron = p * POOL + k
                if (chron < missing) continue
                val slot = (oldest + (chron - missing)) % FRAMES
                val base = slot * featureDim
                val dst = p * featureDim
                for (d in 0 until featureDim) out[dst + d] += ring[base + d] / POOL
            }
        }
        return out
    }

    fun reset() {
        writeFrame = 0
        filled = 0
        if (ring.isNotEmpty()) ring.fill(0f)
    }

    companion object {
        const val FRAMES = 120
        const val POOL = 2
        /** Dims for the standard 40-bin micro frontend. */
        const val DEFAULT_DIMS = FRAMES / POOL * 40
    }
}
