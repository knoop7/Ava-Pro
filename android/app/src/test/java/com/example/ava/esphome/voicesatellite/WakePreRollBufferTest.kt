package com.example.ava.esphome.voicesatellite

import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class WakePreRollBufferTest {
    private fun frame(value: Int): ByteString {
        val pcm = ByteBuffer.allocate(640).order(ByteOrder.LITTLE_ENDIAN)
        repeat(320) { pcm.putShort(value.toShort()) }
        return ByteString.copyFrom(pcm.array())
    }

    @Test
    fun quietSpeechCanBeRecoveredWithoutAnEnergyRise() {
        val buffer = WakePreRollBuffer()
        repeat(65) { buffer.append(frame(393)) } // RMS ~= 0.012, constant for 1.3 s.
        val snapshot = buffer.snapshot()
        assertNull(snapshot.legacyStart)
        assertTrue(snapshot.replay().isEmpty())
        assertEquals(65 * 640, snapshot.replay(0).sumOf { it.size() })
    }

    @Test
    fun loudEndingDoesNotEraseTheQuietBeginning() {
        val buffer = WakePreRollBuffer()
        repeat(50) { buffer.append(frame(393)) }
        repeat(10) { buffer.append(frame(1000)) }
        val snapshot = buffer.snapshot()
        assertTrue(snapshot.legacyStart!! > 0)
        assertEquals(60 * 640, snapshot.bytes)
        assertEquals(frame(393), snapshot.replay(0).first())
        assertTrue(snapshot.replay().sumOf { it.size() } < snapshot.bytes)
    }

    @Test
    fun laterVadOnsetCannotShortenTheExistingReplay() {
        val buffer = WakePreRollBuffer()
        repeat(10) { buffer.append(frame(1000)) }
        val snapshot = buffer.snapshot()
        assertEquals(0, snapshot.legacyStart)
        assertFalse(snapshot.needsSpeechRescue)
        assertEquals(snapshot.replay(), snapshot.replay(640))
    }

    @Test
    fun silenceNeedsPositiveSpeechEvidence() {
        val buffer = WakePreRollBuffer()
        repeat(20) { buffer.append(frame(0)) }
        assertTrue(buffer.snapshot().replay().isEmpty())
    }

    @Test
    fun capAndSnapshotKeepChronologicalImmutableAudio() {
        val buffer = WakePreRollBuffer(maxBytes = 1280)
        buffer.append(frame(1))
        buffer.append(frame(2))
        val old = buffer.snapshot()
        buffer.append(frame(3))
        assertEquals(listOf(frame(2), frame(3)), buffer.snapshot().replay(0))
        buffer.clear()
        assertEquals(listOf(frame(1), frame(2)), old.replay(0))
        assertEquals(0, buffer.snapshot().bytes)
    }

    @Test
    fun oversizedFrameIsBoundedAndMalformedFrameIgnored() {
        val buffer = WakePreRollBuffer(maxBytes = 640)
        buffer.append(frame(1).concat(frame(2)))
        buffer.append(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
        assertEquals(listOf(frame(2)), buffer.snapshot().replay(0))
    }

    @Test
    fun unverifiedCustomSpeechCuesCannotEnableRescue() {
        assertTrue(WakePreRollCuePolicy.supportsSpeechRescue("asset:///sounds/wake_word_triggered.wav"))
        assertTrue(WakePreRollCuePolicy.supportsSpeechRescue("asset:///sounds/continuous_prompt.wav"))
        assertFalse(WakePreRollCuePolicy.supportsSpeechRescue("https://example.com/hello.wav"))
        assertFalse(WakePreRollCuePolicy.isKnown("https://example.com/hello.wav"))
        assertFalse(WakePreRollCuePolicy.supportsSpeechRescue(null))
    }

    @Test
    fun scannedCuesBecomeEligibleOnlyWhenSpeechFree() {
        val quiet = "content://media/1/quiet-chime"
        val spoken = "content://media/1/spoken-yes"
        val broken = "content://media/1/undecodable"
        try {
            assertTrue(WakePreRollCuePolicy.beginScan(quiet))
            assertFalse(WakePreRollCuePolicy.beginScan(quiet)) // already in flight
            WakePreRollCuePolicy.endScan(quiet, isSpeechFree = true)
            assertTrue(WakePreRollCuePolicy.isKnown(quiet))
            assertTrue(WakePreRollCuePolicy.supportsSpeechRescue(quiet))
            assertFalse(WakePreRollCuePolicy.beginScan(quiet)) // known: never rescanned

            assertTrue(WakePreRollCuePolicy.beginScan(spoken))
            WakePreRollCuePolicy.endScan(spoken, isSpeechFree = false)
            assertTrue(WakePreRollCuePolicy.isKnown(spoken))
            assertFalse(WakePreRollCuePolicy.supportsSpeechRescue(spoken))

            assertTrue(WakePreRollCuePolicy.beginScan(broken))
            WakePreRollCuePolicy.endScan(broken, isSpeechFree = null)
            assertTrue(WakePreRollCuePolicy.isKnown(broken))
            assertFalse(WakePreRollCuePolicy.supportsSpeechRescue(broken))

            // An interrupted scan leaves the cue unknown so a later wake retries.
            WakePreRollCuePolicy.abandonScan(quiet)
            assertFalse(WakePreRollCuePolicy.isKnown(quiet))
            assertTrue(WakePreRollCuePolicy.beginScan(quiet))
        } finally {
            listOf(quiet, spoken, broken).forEach(WakePreRollCuePolicy::abandonScan)
        }
    }

    @Test
    fun runStartCannotFlushWhilePreRollIsBeingClassified() {
        val gate = MicAudioDeliveryGate()
        val token = gate.beginPreRoll()
        gate.onRunStart()
        assertFalse(gate.canFlush)
        assertTrue(gate.finishPreRoll(token))
        assertTrue(gate.canFlush)
    }

    @Test
    fun finishingPreRollDoesNotInventRunStart() {
        val gate = MicAudioDeliveryGate()
        val token = gate.beginPreRoll()
        gate.finishPreRoll(token)
        assertFalse(gate.canFlush)
        gate.onRunStart()
        assertTrue(gate.canFlush)
    }

    @Test
    fun cancelledSessionCannotReleaseANewerSessionsHold() {
        val gate = MicAudioDeliveryGate()
        val old = gate.beginPreRoll()
        gate.reset()
        val current = gate.beginPreRoll()
        gate.onRunStart()
        assertFalse(gate.finishPreRoll(old))
        assertFalse(gate.canFlush)
        assertTrue(gate.finishPreRoll(current))
        assertTrue(gate.canFlush)
    }
}
