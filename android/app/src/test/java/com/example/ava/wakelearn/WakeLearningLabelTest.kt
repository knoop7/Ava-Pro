package com.example.ava.wakelearn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLearningLabelTest {
    @Test
    fun blankTranscriptWithoutAnySpeechIsAFalseWake() {
        for (text in listOf(null, "", " ", "\n\t")) {
            assertEquals(WakeLearningLabel.NEGATIVE, WakeLearningLabel.fromTranscript(text, speechEvidence = false))
        }
    }

    @Test
    fun blankTranscriptAfterAudibleSpeechIsLostAudioNotAFalseWake() {
        for (text in listOf(null, "", " ")) {
            assertEquals(WakeLearningLabel.UNKNOWN, WakeLearningLabel.fromTranscript(text, speechEvidence = true))
        }
    }

    @Test
    fun validSingleCharacterResponsesAreNotNegativeExamples() {
        for (text in listOf("7", "开", " 是 ", "turn on the light")) {
            assertEquals(WakeLearningLabel.POSITIVE, WakeLearningLabel.fromTranscript(text, speechEvidence = false))
            assertEquals(WakeLearningLabel.POSITIVE, WakeLearningLabel.fromTranscript(text, speechEvidence = true))
        }
    }

    @Test
    fun earconResidualBeforeArmingIsNotSpeechEvidence() {
        val evidence = WakeSessionSpeechEvidence(rmsFloor = 0.008f, sustainMs = 240)
        repeat(50) { assertFalse(evidence.onFrame(0.05f, 20)) }
        assertFalse(evidence.observed)
    }

    @Test
    fun sustainedEnergyAfterArmingCountsExactlyOnce() {
        val evidence = WakeSessionSpeechEvidence(rmsFloor = 0.008f, sustainMs = 240)
        evidence.arm()
        repeat(11) { assertFalse(evidence.onFrame(0.02f, 20)) }
        assertTrue(evidence.onFrame(0.02f, 20))
        assertTrue(evidence.observed)
        assertFalse(evidence.onFrame(0.02f, 20))
    }

    @Test
    fun aSingleTransientDoesNotLatch() {
        val evidence = WakeSessionSpeechEvidence(rmsFloor = 0.008f, sustainMs = 240)
        evidence.arm()
        repeat(5) { evidence.onFrame(0.05f, 20) }
        evidence.onFrame(0.001f, 20)
        repeat(5) { evidence.onFrame(0.05f, 20) }
        assertFalse(evidence.observed)
    }

    @Test
    fun externalEvidenceAndResetBehave() {
        val evidence = WakeSessionSpeechEvidence()
        evidence.note()
        assertTrue(evidence.observed)
        evidence.reset()
        assertFalse(evidence.observed)
        assertFalse(evidence.onFrame(1f, 1000))
    }
}
