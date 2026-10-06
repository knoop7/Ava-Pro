package com.example.ava.microwakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowScoreRearmGateTest {
    @Test
    fun requiresAllLowScoreWindowsBeforeAllowingScoring() {
        val gate = LowScoreRearmGate(requiredLowScoreWindows = 3)

        assertFalse(gate.allowScoring(probability = 0.2f, cutoff = 0.9f))
        assertFalse(gate.allowScoring(probability = 0.3f, cutoff = 0.9f))
        assertFalse(gate.allowScoring(probability = 0.4f, cutoff = 0.9f))
        assertTrue(gate.allowScoring(probability = 0.95f, cutoff = 0.9f))
    }

    @Test
    fun highScorePausesLowScoreSequence() {
        val gate = LowScoreRearmGate(requiredLowScoreWindows = 2)

        assertFalse(gate.allowScoring(probability = 0.2f, cutoff = 0.9f))
        assertFalse(gate.allowScoring(probability = 0.95f, cutoff = 0.9f))
        assertFalse(gate.allowScoring(probability = 0.2f, cutoff = 0.9f))
        assertTrue(gate.allowScoring(probability = 0.95f, cutoff = 0.9f))
    }

    @Test
    fun explicitArmAllowsImmediateScoring() {
        val gate = LowScoreRearmGate(requiredLowScoreWindows = 50)
        assertFalse(gate.allowScoring(probability = 0.95f, cutoff = 0.8f))
        gate.arm()
        assertTrue(gate.allowScoring(probability = 0.95f, cutoff = 0.8f))
    }

    @Test
    fun disarmRequiresAnotherLowScoreSequence() {
        val gate = LowScoreRearmGate(requiredLowScoreWindows = 1, startArmed = true)

        assertTrue(gate.allowScoring(probability = 0.95f, cutoff = 0.9f))
        gate.disarm()
        assertFalse(gate.allowScoring(probability = 0.2f, cutoff = 0.9f))
        assertTrue(gate.allowScoring(probability = 0.95f, cutoff = 0.9f))
    }

    @Test
    fun onlyMigratesLegacyBundledJarvisDefault() {
        assertEquals(
            0.97f,
            WakeWordCutoffPolicy.resolveRequestedCutoff("hey_jarvis", 0.97f, 0.85f),
            0.0001f,
        )
        assertEquals(
            0.83f,
            WakeWordCutoffPolicy.resolveRequestedCutoff("hey_jarvis", 0.97f, 0.83f),
            0.0001f,
        )
        assertEquals(
            0.85f,
            WakeWordCutoffPolicy.resolveRequestedCutoff("hey_jarvis", 0.5f, 0.85f),
            0.0001f,
        )
        assertEquals(
            0.85f,
            WakeWordCutoffPolicy.resolveRequestedCutoff("hey_mycroft", 0.97f, 0.85f),
            0.0001f,
        )
    }

    @Test
    fun leftoverOpenCutoffDoesNotLoosenMicroModels() {
        // The sensitivity fields are shared with openWakeWord (working range 0.15..0.80).
        // A stored value below this model's own slider floor is the other engine's
        // setting — it must not run hey_jarvis at 0.65. (This test existed twice with
        // partially different assertions; this is the union of both.)
        assertEquals(
            0.97f,
            WakeWordCutoffPolicy.resolveRequestedCutoff("hey_jarvis", 0.97f, 0.65f),
            0.0001f,
        )
        assertEquals(
            0.97f,
            WakeWordCutoffPolicy.resolveRequestedCutoff("hey_jarvis", 0.97f, 0.15f),
            0.0001f,
        )
        assertEquals(
            0.9f,
            WakeWordCutoffPolicy.resolveRequestedCutoff("hey_marcel", 0.9f, 0.3f),
            0.0001f,
        )
        assertEquals(
            0.99f,
            WakeWordCutoffPolicy.resolveRequestedCutoff("hey_marcel", 0.99f, 0.5f),
            0.0001f,
        )
        // Anything the micro slider itself can produce is still honored.
        assertEquals(
            0.72f,
            WakeWordCutoffPolicy.resolveRequestedCutoff("hey_jarvis", 0.97f, 0.72f),
            0.0001f,
        )
        assertEquals(
            0.75f,
            WakeWordCutoffPolicy.resolveRequestedCutoff("hey_jarvis", 0.97f, 0.75f),
            0.0001f,
        )
        val range = WakeWordCutoffPolicy.sliderRange(0.97f)
        assertEquals(0.72f, range.start, 0.0001f)
        assertEquals(0.99f, range.endInclusive, 0.0001f)
    }

    @Test
    fun offlineVerifyTracksRequestedStrictness() {
        // User loosened streaming to 0.75; the manifest is not a second floor → 0.65.
        assertEquals(
            0.65f,
            WakeWordCutoffPolicy.resolveVerifyCutoff(0.75f, 0.9f),
            0.0001f,
        )
        // No streaming cutoff known → fall back to the manifest.
        assertEquals(
            0.80f,
            WakeWordCutoffPolicy.resolveVerifyCutoff(0f, 0.9f),
            0.0001f,
        )
        // Explicit precision override: keep only 0.02 cold-start slack.
        assertEquals(
            0.93f,
            WakeWordCutoffPolicy.resolveVerifyCutoff(0.95f, 0.9f),
            0.0001f,
        )
        // One sliding-window fire is enough (ESPHome accept semantics; #166).
        assertEquals(1, WakeWordCutoffPolicy.minOfflineDetections(0.99f, 0.9f))
        assertEquals(1, WakeWordCutoffPolicy.minOfflineDetections(0.97f, 0.97f))
        assertEquals(1, WakeWordCutoffPolicy.minOfflineDetections(0.96f))
        assertEquals(1, WakeWordCutoffPolicy.minOfflineDetections(0.75f))
    }

    @Test
    fun offlineVerifyIsNeverStricterThanStreaming() {
        // The re-score runs cold — no rearm state, no warmed frontend, clip edges the
        // sliding window can only partly fill — so it must not out-demand streaming.
        for (baseline in listOf(0.5f, 0.6f, 0.72f, 0.85f, 0.97f, 0.99f)) {
            val verify = WakeWordCutoffPolicy.resolveVerifyCutoff(baseline, baseline)
            assertTrue("verify $verify should not exceed streaming $baseline", verify <= baseline)
            assertTrue("verify $verify should stay well above non-wake audio", verify >= 0.3f)
        }
        // A 0.99 manifest (Tater exports ship one) used to pin verify at 0.99, where the
        // cold-start penalty alone rejected roughly a third of genuine wakes.
        assertEquals(
            0.89f,
            WakeWordCutoffPolicy.resolveVerifyCutoff(0.99f, 0.99f),
            0.0001f,
        )
        // Floor holds even if the baseline sits at the bottom of the slider.
        assertEquals(
            0.4f,
            WakeWordCutoffPolicy.resolveVerifyCutoff(0.5f, 0.5f),
            0.0001f,
        )
    }

    @Test
    fun heyJarvisDefaultOfflineVerifyAcceptsSingleFire() {
        // Bundled hey_jarvis: manifest 0.97 → verify 0.87; one offline fire confirms.
        assertEquals(
            0.87f,
            WakeWordCutoffPolicy.resolveVerifyCutoff(0.97f, 0.97f),
            0.0001f,
        )
        assertEquals(1, WakeWordCutoffPolicy.minOfflineDetections(0.97f, 0.97f))
        assertEquals(1, WakeWordCutoffPolicy.minOfflineDetections(0.78f, 0.97f))
        // Deliberately loosened for a far-field room: verify follows the setting (#187),
        // it is no longer pinned to the manifest where the slider could not reach it.
        assertEquals(
            0.68f,
            WakeWordCutoffPolicy.resolveVerifyCutoff(0.78f, 0.97f),
            0.0001f,
        )
    }

    @Test
    fun unverifiedWakeUsesRequestedStrictness() {
        // Loose user cutoff 0.75: the manifest no longer overrides it.
        assertFalse(
            WakeWordCutoffPolicy.allowUnverifiedStreamingWake(0.70f, 0.9f, 0.75f),
        )
        assertTrue(
            WakeWordCutoffPolicy.allowUnverifiedStreamingWake(0.82f, 0.9f, 0.75f),
        )
        assertTrue(
            WakeWordCutoffPolicy.allowUnverifiedStreamingWake(0.90f, 0.9f, 0.75f),
        )
        // No streaming cutoff known → manifest is the floor.
        assertFalse(
            WakeWordCutoffPolicy.allowUnverifiedStreamingWake(0.82f, 0.9f, 0f),
        )
        // Strict user cutoff 0.99: must clear 0.99, not merely manifest 0.9.
        assertFalse(
            WakeWordCutoffPolicy.allowUnverifiedStreamingWake(0.95f, 0.9f, 0.99f),
        )
        assertFalse(
            WakeWordCutoffPolicy.allowUnverifiedStreamingWake(0.99f, 0.9f, 0.99f),
        )
        // Untouched strict manifests retain normal fail-open behavior.
        assertTrue(
            WakeWordCutoffPolicy.allowUnverifiedStreamingWake(0.97f, 0.97f, 0.97f),
        )
    }

    @Test
    fun farEndSkipRequiresStrongStreamingHit() {
        assertFalse(WakeWordCutoffPolicy.allowFarEndVerifySkip(0.82f, 0.9f, 0.9f))
        assertFalse(WakeWordCutoffPolicy.allowFarEndVerifySkip(0.90f, 0.9f, 0.9f))
        assertTrue(WakeWordCutoffPolicy.allowFarEndVerifySkip(0.92f, 0.9f, 0.9f))
        assertTrue(WakeWordCutoffPolicy.allowFarEndVerifySkip(0.96f, 0.9f, 0.9f))
        // A precision override never bypasses the independent ring re-score.
        assertFalse(WakeWordCutoffPolicy.allowFarEndVerifySkip(0.95f, 0.9f, 0.99f))
        assertFalse(WakeWordCutoffPolicy.allowFarEndVerifySkip(0.99f, 0.9f, 0.99f))
        // A strict manifest at its untouched recommendation is not a user override.
        assertFalse(WakeWordCutoffPolicy.allowFarEndVerifySkip(0.93f, 0.97f, 0.97f))
        assertTrue(WakeWordCutoffPolicy.allowFarEndVerifySkip(0.97f, 0.97f, 0.97f))
    }

    @Test
    fun forcePrecisionMatchesAUserRaisedOverride() {
        // A high-manifest model (hey_jarvis 0.97) never trips the value-based override
        // at its own ceiling; extra-strictness still forces the tight slack / fail-closed.
        assertEquals(
            0.95f,
            WakeWordCutoffPolicy.resolveVerifyCutoff(0.97f, 0.97f, forcePrecision = true),
            0.0001f,
        )
        assertFalse(
            WakeWordCutoffPolicy.allowUnverifiedStreamingWake(
                0.97f, 0.97f, 0.97f, forcePrecision = true,
            ),
        )
        assertFalse(
            WakeWordCutoffPolicy.allowFarEndVerifySkip(
                0.97f, 0.97f, 0.97f, forcePrecision = true,
            ),
        )
        // Without the flag the same values keep the proven 0.10 slack and fail-open.
        assertEquals(
            0.87f,
            WakeWordCutoffPolicy.resolveVerifyCutoff(0.97f, 0.97f),
            0.0001f,
        )
        assertTrue(
            WakeWordCutoffPolicy.allowUnverifiedStreamingWake(0.97f, 0.97f, 0.97f),
        )
    }

    @Test
    fun precisionOverrideTightensOnlyUserRaisedStrictness() {
        assertEquals(
            0.93f,
            WakeWordCutoffPolicy.resolveVerifyCutoff(0.95f, 0.85f),
            0.0001f,
        )
        assertEquals(
            0.97f,
            WakeWordCutoffPolicy.resolveVerifyCutoff(0.99f, 0.85f),
            0.0001f,
        )
        // Untouched high-cutoff models retain the proven 0.10 cold-start allowance.
        assertEquals(
            0.87f,
            WakeWordCutoffPolicy.resolveVerifyCutoff(0.97f, 0.97f),
            0.0001f,
        )
    }

    @Test
    fun featureStrideMatchesBundledAndTaterGraphs() {
        // Frontend always emits 40 bins; bundled ESPHome is [1, 3, 40].
        assertEquals(3, MicroFeatureStride.resolve(40, 120))
        assertEquals(34, MicroFeatureStride.rearmWindows(3))
        // Tater mixednet --stride 2 is [1, 2, 40].
        assertEquals(2, MicroFeatureStride.resolve(40, 80))
        assertEquals(50, MicroFeatureStride.rearmWindows(2))
        assertEquals(1, MicroFeatureStride.resolve(40, 40))
        assertEquals(null, MicroFeatureStride.resolve(40, 100))
        assertEquals(null, MicroFeatureStride.resolve(0, 120))
        assertEquals(null, MicroFeatureStride.resolve(40, 0))
    }

    @Test
    fun retargetKeepsArmedGateArmedAndRescalesInProgressWait() {
        val armed = LowScoreRearmGate(requiredLowScoreWindows = 34, startArmed = true)
        armed.retargetRequiredWindows(50)
        assertTrue(armed.allowScoring(probability = 0.95f, cutoff = 0.9f))
        armed.disarm()
        repeat(50) { assertFalse(armed.allowScoring(probability = 0.2f, cutoff = 0.9f)) }
        assertTrue(armed.allowScoring(probability = 0.95f, cutoff = 0.9f))

        val waiting = LowScoreRearmGate(requiredLowScoreWindows = 34, startArmed = false)
        waiting.retargetRequiredWindows(2)
        assertFalse(waiting.allowScoring(probability = 0.2f, cutoff = 0.9f))
        assertFalse(waiting.allowScoring(probability = 0.2f, cutoff = 0.9f))
        assertTrue(waiting.allowScoring(probability = 0.95f, cutoff = 0.9f))
    }
}
