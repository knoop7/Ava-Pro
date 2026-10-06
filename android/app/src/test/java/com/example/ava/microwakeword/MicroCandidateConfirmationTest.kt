package com.example.ava.microwakeword

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicroCandidateConfirmationTest {
    private fun detector() = MicroWakeWord(
        "test", "test", ByteBuffer.allocateDirect(1), 0.8f, 3, startArmed = true,
    )

    @Test
    fun onlyFullCandidatesRunSecondPass() {
        val detector = detector()
        var calls = 0
        val accept: (Float) -> Boolean = { calls++; true }
        assertFalse(detector.isWakeWordDetected(0.95f, accept))
        assertFalse(detector.isWakeWordDetected(0.95f, accept))
        assertEquals(0, calls)
        assertTrue(detector.isWakeWordDetected(0.95f, accept))
        assertEquals(1, calls)
    }

    @Test
    fun rejectedCandidateDoesNotConsumeRearm() {
        val detector = detector()
        repeat(3) { assertFalse(detector.isWakeWordDetected(0.95f) { false }) }
        assertEquals(0f, detector.lastDetectionProbability, 0f)
        assertTrue(detector.isWakeWordDetected(0.95f) { true })
    }

    @Test
    fun acceptedCandidateStillRequiresLowScoresToRearm() {
        val detector = detector()
        repeat(2) { assertFalse(detector.isWakeWordDetected(0.95f)) }
        assertTrue(detector.isWakeWordDetected(0.95f))
        repeat(100) { assertFalse(detector.isWakeWordDetected(0.95f)) }
    }

    @Test
    fun rejectionRetainsSlidingEvidenceButIncludesNewestFrame() {
        val detector = detector()
        repeat(3) { assertFalse(detector.isWakeWordDetected(0.95f) { false }) }
        // The latest complete window is still above 0.8 even as the word ends.
        assertTrue(detector.isWakeWordDetected(0.7f))
    }

    @Test
    fun rejectedCandidateCannotIgnoreNewLowEvidence() {
        val detector = detector()
        repeat(3) { assertFalse(detector.isWakeWordDetected(0.95f) { false }) }
        var confirmations = 0
        assertFalse(detector.isWakeWordDetected(0f) { confirmations++; true })
        assertEquals(0, confirmations)
    }

    @Test
    fun invalidScoreCannotCompleteCandidate() {
        val detector = detector()
        repeat(2) { assertFalse(detector.isWakeWordDetected(0.95f)) }
        assertFalse(detector.isWakeWordDetected(Float.NaN))
        repeat(2) { assertFalse(detector.isWakeWordDetected(0.95f)) }
        assertTrue(detector.isWakeWordDetected(0.95f))
    }

    @Test
    fun offlineSlackBelowLiveFloorIsNotSilentlyRaised() {
        val verify = MicroWakeWord.forVerification(
            "test", "test", ByteBuffer.allocateDirect(1), 0.5f, 0.4f, 1,
        )
        assertEquals(0.4f, verify.probabilityCutoff, 0f)
        assertTrue(verify.isWakeWordDetected(0.45f))
        val live = MicroWakeWord("test", "test", ByteBuffer.allocateDirect(1), 0.4f, 1, startArmed = true)
        assertEquals(0.5f, live.probabilityCutoff, 0f)
    }

    @Test
    fun warmupCanSuppressEventsWithoutLosingModelEvidence() {
        val verify = MicroWakeWord.forVerification(
            "test", "test", ByteBuffer.allocateDirect(1), 0.9f, 0.8f, 3,
        )
        repeat(3) { assertFalse(verify.isWakeWordDetected(0.95f) { false }) }
        assertTrue(verify.isWakeWordDetected(0.85f) { true })
    }
}
