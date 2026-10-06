package com.example.ava.wakelearn

import com.example.ava.settings.WakeWordEngine

/**
 * Frozen. Do not call from [WakeLearner].
 *
 * Hosttest ablation on clips that already wake showed Apple's 3 / 4 / 5 / 6 kHz
 * recipe is Siri-sibilant specific. Okay Nabu energy sits in 0.8–2 kHz vowels;
 * those four micro bins do not move live engines, so synthetic negatives here
 * would teach the verifier a gap we do not actually observe.
 *
 * Kept as a reference for the (wrong) mapping: HTK channels 25 / 29 / 33 / 35
 * peak near 3.04 / 3.98 / 5.16 / 5.85 kHz.
 */
object WakeLearnSpeakerNotch {
    /** Output channels whose triangle peak is nearest 3 / 4 / 5 / 6 kHz. */
    val MICRO_NOTCH_BINS = intArrayOf(25, 29, 33, 35)

    /** Keep this fraction of the bin (~−22 dB). Hard zero is outside the frontend's floor. */
    const val KEEP = 0.08f

    fun augment(
        engine: WakeWordEngine,
        samples: List<WakeLearnStore.Sample>,
    ): List<WakeLearnStore.Sample> {
        if (engine != WakeWordEngine.MICRO_WAKE_WORD) return samples
        val extra = ArrayList<WakeLearnStore.Sample>()
        for (sample in samples) {
            if (!sample.positive) continue
            val notched = applyMicro(sample.x) ?: continue
            extra.add(WakeLearnStore.Sample(false, sample.timestampMs, notched))
        }
        if (extra.isEmpty()) return samples
        return samples + extra
    }

    fun applyMicro(window: FloatArray): FloatArray? {
        val frames = MicroVerifierWindow.FRAMES / MicroVerifierWindow.POOL
        if (frames <= 0 || window.size % frames != 0) return null
        val dim = window.size / frames
        val lastBin = MICRO_NOTCH_BINS.max()
        if (dim <= lastBin) return null
        val out = window.copyOf()
        for (frame in 0 until frames) {
            val base = frame * dim
            for (bin in MICRO_NOTCH_BINS) {
                out[base + bin] *= KEEP
            }
        }
        return out
    }
}
