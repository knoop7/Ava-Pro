package com.example.ava.microwakeword

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicroVadDecisionTest {
    @Test
    fun singleSpikeDoesNotOpenVoiceGate() {
        assertFalse(MicroVadDecision.allowsWake(listOf(0.99f), 5, 0.5f))
        assertFalse(MicroVadDecision.allowsWake(listOf(0.1f, 0.99f, 0.1f), 5, 0.5f))
    }

    @Test
    fun twoSpeechFramesPreserveFastSpeech() {
        assertTrue(MicroVadDecision.allowsWake(listOf(0.7f, 0.8f), 5, 0.5f))
    }

    @Test
    fun sustainedWindowAverageOpensGate() {
        assertTrue(MicroVadDecision.allowsWake(listOf(0.45f, 0.55f, 0.6f, 0.5f, 0.7f), 5, 0.5f))
    }

    @Test
    fun strictRuleRequiresAverageAndTwoFrames() {
        // Two hot frames open the normal OR-gate, but the window average (0.3) fails AND.
        assertTrue(MicroVadDecision.allowsWake(listOf(0.7f, 0.8f), 5, 0.5f))
        assertFalse(MicroVadDecision.allowsWakeStrict(listOf(0.7f, 0.8f), 5, 0.5f))
        // A just-over-average window (0.554) with only one frame above cutoff fails AND.
        assertTrue(MicroVadDecision.allowsWake(listOf(0.4f, 0.45f, 0.49f, 0.48f, 0.95f), 5, 0.5f))
        assertFalse(MicroVadDecision.allowsWakeStrict(listOf(0.4f, 0.45f, 0.49f, 0.48f, 0.95f), 5, 0.5f))
        // Both conditions hold: average > 0.5 and two frames > 0.5.
        assertTrue(MicroVadDecision.allowsWakeStrict(listOf(0.6f, 0.7f, 0.8f, 0.4f, 0.6f), 5, 0.5f))
    }

    @Test
    fun missingVadFailsOpenAtEveryStrictness() {
        // A broken or absent VAD model must never deafen the device, not even in
        // extreme mode: the VAD is a gate on top of the classifier, not evidence
        // the classifier lacks.
        assertTrue(WakeVadPolicy.allowsWake(strict = false, vadAvailable = false, normalDecision = false, strictDecision = false))
        assertTrue(WakeVadPolicy.allowsWake(strict = true, vadAvailable = false, normalDecision = false, strictDecision = false))
    }

    @Test
    fun extremeModeRequiresStrictVadDecisionWhenVadRuns() {
        assertFalse(WakeVadPolicy.allowsWake(strict = true, vadAvailable = true, normalDecision = true, strictDecision = false))
        assertTrue(WakeVadPolicy.allowsWake(strict = true, vadAvailable = true, normalDecision = true, strictDecision = true))
        assertTrue(WakeVadPolicy.allowsWake(strict = false, vadAvailable = true, normalDecision = true, strictDecision = false))
        assertFalse(WakeVadPolicy.allowsWake(strict = false, vadAvailable = true, normalDecision = false, strictDecision = false))
    }
}
