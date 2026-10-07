package com.example.ava.openwakeword

/**
 * Cutoff bounds for openWakeWord, parallel to [com.example.ava.microwakeword.WakeWordCutoffPolicy].
 *
 * Community ONNX models (and the bundled ok_nabu) are calibrated near 0.5. On the
 * real engine they peak around 0.84–0.91; a 0.87 cutoff already drops recall, and
 * 0.92 fires nothing. Micro's 0.5–0.99 slider — and a leftover 0.97 from a
 * bundled hey_jarvis — therefore pin the detector above what the model can score.
 */
object OpenWakeWordCutoffPolicy {
    const val MIN_THRESHOLD = 0.15f
    const val MAX_THRESHOLD = 0.99f
    const val DEFAULT_THRESHOLD = 0.5f

    /**
     * Strictest the slider (and a stored override) may go. Above this the classifier
     * no longer has headroom: ok_nabu measured 6/8 at 0.80, 5/8 at 0.87, 0/8 at 0.92.
     *
     * The ceiling is a recall limit, not a statement that strictness is useless. An
     * earlier note here claimed negatives sit at ~0.001 and buy no rejection; that came
     * from two isolated clips. Against 23 min of continuous speech the negative peak is
     * 0.77, so everything between the 0.5 default and this ceiling is live rejection.
     */
    const val SLIDER_CEILING = 0.80f

    /** Verifier heads have narrow, speaker-dependent peaks; 0.80 measured 0/8. */
    const val VERIFIER_SLIDER_CEILING = 0.65f

    /** Room below the model's recommendation for far-field / quiet speech. */
    private const val SLIDER_BELOW_MANIFEST = 0.35f

    /**
     * Gate for manifests that never chose one: raw per-frame scores, two consecutive
     * frames above cutoff.
     *
     * Default streaming gate. Extra-strictness level 1+ adds an offline re-score;
     * this consecutive-hit rule still rejects isolated spikes at level 0. Measured on 23 min
     * of continuous non-wake speech (ok_nabu, threshold 0.5, unity gain): every false
     * positive is a single-frame spike — 14 runs above 0.5, all length 1, still all
     * length 1 at 0.6 and 0.7 — while every firing padded positive holds 3–5 consecutive
     * frames above cutoff. Two consecutive hits therefore reject every observed spike
     * with a frame of headroom on every positive, and unlike the former mean-of-3 floor
     * they cannot be dragged over the cutoff by one huge frame beside near-silence
     * ({0.99, 0.3, 0.99} averages 0.76 but never shows two consecutive hits). Latency
     * preserves recall while rejecting isolated spikes; the native scheduler now
     * evaluates the two embedding phases on alternating 40 ms ticks.
     */
    const val DEFAULT_SLIDING_WINDOW = 1
    const val DEFAULT_REQUIRED_HITS = 2

    /**
     * Extra-strictness level 2 hit gate for non-verifier models. Measured positives hold
     * 3–5 consecutive frames above cutoff, so three hits stay inside the genuine-wake
     * envelope while rejecting any two-frame fluke the default gate would pass.
     */
    const val EXTRA_STRICT_REQUIRED_HITS = 3

    /**
     * Offline re-score lift at extra-strictness level 1. Streaming already cleared the
     * native cutoff; a same-threshold replay of the same clip is a no-op. This is the
     * independent second pass the slider copy promises, kept inside measured peak
     * headroom (community models 0.84–0.91, verifier voices vary).
     */
    const val EXTRA_VERIFY_L1_DELTA = 0.04f

    /**
     * Level 2 uses the same lift as level 1 when the streaming bar is already at the
     * slider ceiling (0.80 + 0.08 = 0.88 sits on the measured recall cliff: 5/8 at
     * 0.87, 0/8 at 0.92). Level 2's extra rejection is hits=3 + stricter VAD, not a
     * second cutoff hike past headroom.
     */
    const val EXTRA_VERIFY_L2_DELTA = 0.08f

    /**
     * Hard cap for the offline bar. Community peaks are 0.84–0.91; 0.84 is the last
     * step that still clears a typical positive after the slider has already been
     * pinned at 0.80. 0.90 was past the cliff and made extra-strictness feel deaf.
     * Verifier heads measured 0/8 at 0.80, so they stop earlier.
     */
    const val EXTRA_VERIFY_CAP = 0.84f
    const val EXTRA_VERIFY_VERIFIER_CAP = 0.68f

    /** The gate a keyword actually runs with. */
    data class GateParams(val slidingWindow: Int, val requiredHits: Int)

    /**
     * Apply the user's extra-strictness level on top of [effectiveGate]'s result.
     *
     * Only the consecutive-hits gate (window <= 1) is tightened: a manifest that chose a
     * mean-over-window gate was tuned deliberately and bumping its hit count would change
     * its semantics, and verifier models are single-spike by design — hits=2 alone
     * measured 2/8 recall loss on the official hey_jarvis, so they are never bumped.
     *
     * A model with a near-word verifier head ([hasVerifierHead]) is never bumped either:
     * the head already takes the matrix to 0/192 false accepts at every level, while
     * hits=3 (5 consecutive 40 ms ticks) costs 253 -> 167 of 864 positives for no
     * further measurable rejection. Level 2 keeps its other layers (offline re-score
     * at +0.08, verifier veto at 0.85). Heads-less models — a fresh download before
     * on-device learning has produced one — keep hits=3 as their only extra rejection.
     */
    fun applyExtraStrictness(
        gate: GateParams,
        hasBuiltInVerifier: Boolean,
        extraLevel: Int,
        hasVerifierHead: Boolean = false,
    ): GateParams =
        if (extraLevel >= 2 && !hasBuiltInVerifier && !hasVerifierHead && gate.slidingWindow <= 1) {
            GateParams(gate.slidingWindow, maxOf(gate.requiredHits, EXTRA_STRICT_REQUIRED_HITS))
        } else {
            gate
        }

    /**
     * Whether level 2 should switch the VAD to its strict rule (window average AND two
     * frames, 320 ms hangover). Like the hit bump, the strict rule is a stand-in for
     * missing evidence. A sidecar head or a built-in ONNX verifier already judges every
     * fire; stacking the AND-rule on top only removes genuine quiet / short / far-field
     * wakes (official hey_jarvis is a two-syllable spike that often fails the window
     * average), so those models stay on the normal VAD rule.
     */
    fun strictVadFor(
        extraLevel: Int,
        hasVerifierHead: Boolean,
        hasBuiltInVerifier: Boolean = false,
    ): Boolean =
        extraLevel >= 2 && !hasVerifierHead && !hasBuiltInVerifier

    /**
     * Cutoff the offline burst-scorer uses at extra-strictness [extraLevel].
     * Level 0 keeps the streaming bar (echo-reference path). Level 1+ raises it.
     */
    fun offlineVerifyCutoff(
        streamingCutoff: Float,
        extraLevel: Int,
        hasBuiltInVerifier: Boolean = false,
    ): Float {
        val base = sanitize(streamingCutoff)
        if (extraLevel < 1) return base
        val delta = if (extraLevel >= 2) EXTRA_VERIFY_L2_DELTA else EXTRA_VERIFY_L1_DELTA
        val cap = if (hasBuiltInVerifier) EXTRA_VERIFY_VERIFIER_CAP else EXTRA_VERIFY_CAP
        return minOf(base + delta, cap).coerceAtLeast(base)
    }

    /**
     * Veto threshold for a model's near-word verifier head (`<id>_verifier.onnx`) at
     * each extra-strictness level. Calibrated on the ok_nabu head with
     * tools/oww-verifier/train_verifier.py (leave-one-voice-out plus eight never-seen
     * speakers) and confirmed end-to-end on the 1,056-clip matrix; see the values'
     * comments for the measured trade-off. 0 disables the veto.
     */
    fun verifierThreshold(extraLevel: Int): Float = when {
        extraLevel >= 2 -> VERIFIER_THRESHOLD_L2
        extraLevel >= 1 -> VERIFIER_THRESHOLD_L1
        else -> VERIFIER_THRESHOLD_L0
    }

    /**
     * ok_nabu head, engine end-to-end (tools/oww-stress/run_matrix.sh, threshold 0.5,
     * default gate). Base model without the head: 11/192 near-word false accepts on
     * the stress matrix, 40/192 on Mandarin conversation ("OK 那不要", "OK 那部电影"),
     * 11/448 on eight never-seen English speakers. With the head at 0.50 / 0.70 / 0.85:
     * 0/192, 0/192 and 4 / 2 / 2 of 448 respectively, while positive recall is
     * unchanged at every threshold (253/864 matrix, 37/192 never-seen speakers,
     * 28/48 Mandarin-accented). Genuine wakes score far above 0.85, so the ladder
     * costs nothing in recall and only buys rejection margin on unseen voices.
     */
    const val VERIFIER_THRESHOLD_L0 = 0.50f
    const val VERIFIER_THRESHOLD_L1 = 0.70f
    const val VERIFIER_THRESHOLD_L2 = 0.85f

    /**
     * Missing ring / extract / mark: level 1 fail-opens (streaming already cleared
     * the native bar; an infrastructure miss must not deafen strict+). Level 2 stays
     * fail-closed — that is the "max verify, soft far-field may miss" contract.
     */
    fun extraStrictnessAllowUnverified(extraLevel: Int): Boolean = extraLevel in 1 until 2

    fun sanitize(value: Float): Float = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    /**
     * Resolve the manifest's (window, hits) into the gate detection runs with.
     *
     * Every manifest our own [OpenWakeWordManifest.wrapOnnx] historically wrote — and
     * therefore every installed download — carries a literal `window=1, hits=1`: raw
     * single-frame firing that no model author chose and that measurably false-wakes
     * (14 in 23 min of speech). Only that never-chosen combination is upgraded to the
     * measured default gate. Anything else was set deliberately, by a model author or
     * an earlier bundled manifest, and is respected as written — a widened window may
     * cost latency but never fires more often than what was tuned.
     *
     * [hasBuiltInVerifier] exempts models that carry their own second-stage verifier
     * (an ONNX `If` gating a verifier network, e.g. the official hey_jarvis). Those
     * models emit a single-frame spike per utterance *by design* — the verifier already
     * did the debouncing — so measured on hey_jarvis the hits=2 upgrade costs 2/8
     * recall while buying nothing: on genuine negatives (near-confusables, 2 speakers
     * of non-wake speech, silence) the verifier holds every score under 0.33 and both
     * hits=1 and hits=2 fire exactly zero times. For them the manifest gate runs as
     * written.
     */
    fun effectiveGate(
        manifestWindow: Int,
        manifestHits: Int,
        hasBuiltInVerifier: Boolean = false,
    ): GateParams =
        if (manifestWindow <= 1 && manifestHits <= 1 && !hasBuiltInVerifier) {
            GateParams(DEFAULT_SLIDING_WINDOW, DEFAULT_REQUIRED_HITS)
        } else {
            GateParams(maxOf(manifestWindow, 1), maxOf(manifestHits, 1))
        }

    fun sliderRange(
        manifestCutoff: Float,
        hasBuiltInVerifier: Boolean = false,
    ): ClosedFloatingPointRange<Float> {
        val baseline = sanitizeManifest(manifestCutoff)
        val lo = (baseline - SLIDER_BELOW_MANIFEST).coerceAtLeast(MIN_THRESHOLD)
        // A manifest above the ceiling is the model author's own calibration point;
        // capping under it would silently loosen an untouched slider. The ceiling
        // exists to stop micro-style 0.87–0.99 values, not to override the manifest.
        val ceiling = if (hasBuiltInVerifier) VERIFIER_SLIDER_CEILING else SLIDER_CEILING
        val hi = maxOf(ceiling, baseline).coerceAtLeast(lo)
        return lo..hi
    }

    /**
     * Effective trigger cutoff.
     *
     * Sensitivity is stored in one pair of fields shared by both engines, so a leftover
     * 0.97 from micro hey_jarvis / hey_luna lands here regularly.
     *
     * Resolution is one-directional: an out-of-reach *strict* request clamps down to the
     * ceiling, it never falls back to the manifest. Returning the baseline made "stricter
     * than we allow" resolve to the loosest value the model has — a stored 0.97 ran at
     * 0.50. Measured on 23 min of continuous non-wake speech (ok_nabu, unity gain), that
     * inversion is worth 14 false wakes versus 0 at the 0.80 ceiling, with the same 6/8
     * recall either way (true peaks 0.84–0.91, so 0.80 still clears them).
     *
     * Verifier models are the exception ([hasBuiltInVerifier]). Their score is a narrow
     * spike whose height varies with the speaker — the official hey_jarvis peaks anywhere
     * from 0.33 to 0.98 across voices — so the 0.80 ceiling is not "strict", it is deaf:
     * measured 0/8 positives at 0.80 versus 5/8+ at the 0.50 manifest. And the clamp buys
     * nothing there, because the built-in verifier already holds every genuine negative
     * under 0.33. For them an out-of-range leftover (from micro or the old generic open
     * slider) is treated as unset, like the micro side treats open's leftovers.
     *
     * Below the floor stays "unset": 0.15 already sits in the noise, so a lower value
     * carries no legible intent.
     */
    fun resolveRequestedCutoff(
        manifestCutoff: Float,
        requestedCutoff: Float,
        hasBuiltInVerifier: Boolean = false,
    ): Float {
        val baseline = sanitizeManifest(manifestCutoff)
        if (requestedCutoff <= 0f) return baseline
        val range = sliderRange(baseline, hasBuiltInVerifier)
        if (requestedCutoff < range.start) return baseline
        if (hasBuiltInVerifier && requestedCutoff > range.endInclusive) return baseline
        return minOf(requestedCutoff, range.endInclusive)
    }

    private fun sanitizeManifest(value: Float): Float =
        sanitize(if (value > 0f) value else DEFAULT_THRESHOLD)
}
