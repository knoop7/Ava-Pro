package com.example.ava.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AvaVoiceTtsInjectTest {
    @Test
    fun padsOneSecondOfFrameAlignedSilence() {
        val speech = ByteArray(AvaVoiceAudioConfig.FRAME_BYTES) { 7 }
        val padded = AvaVoiceTtsInject.padTrailingSilence(speech)
        val silence = AvaVoiceAudioConfig.SAMPLE_RATE / 1000 *
            AvaVoiceTtsInject.TRAILING_SILENCE_MS *
            AvaVoiceAudioConfig.BYTES_PER_SAMPLE
        assertEquals(speech.size + silence, padded.size)
        assertEquals(0, padded.size % AvaVoiceAudioConfig.FRAME_BYTES)
        assertArrayEquals(speech, padded.copyOf(speech.size))
        assertArrayEquals(ByteArray(silence), padded.copyOfRange(speech.size, padded.size))
    }

    @Test
    fun leavesEmptyPcmAlone() {
        val empty = ByteArray(0)
        assertSame(empty, AvaVoiceTtsInject.padTrailingSilence(empty))
    }
}
