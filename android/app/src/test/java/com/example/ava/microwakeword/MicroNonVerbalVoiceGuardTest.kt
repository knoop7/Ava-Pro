package com.example.ava.microwakeword

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicroNonVerbalVoiceGuardTest {
    @Test
    fun sustainedHarmonicHumIsRejected() {
        val pcm = ShortArray(7_680) { i ->
            val fundamental = sin(2.0 * PI * 250.0 * i / 16_000.0)
            val harmonic = 0.20 * sin(2.0 * PI * 500.0 * i / 16_000.0)
            ((fundamental + harmonic) * 8_000).toInt().toShort()
        }
        assertTrue(MicroNonVerbalVoiceGuard.isSustainedHum(pcm))
    }

    @Test
    fun changingNonPeriodicFramesAreAllowed() {
        var state = 0x12345678
        val pcm = ShortArray(7_680) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            (state shr 17).toShort()
        }
        assertFalse(MicroNonVerbalVoiceGuard.isSustainedHum(pcm))
    }

    @Test
    fun shortTonalBurstIsAllowed() {
        val pcm = ShortArray(2_000) { i ->
            (sin(2.0 * PI * 300.0 * i / 16_000.0) * 8_000).toInt().toShort()
        }
        assertFalse(MicroNonVerbalVoiceGuard.isSustainedHum(pcm))
    }
}
