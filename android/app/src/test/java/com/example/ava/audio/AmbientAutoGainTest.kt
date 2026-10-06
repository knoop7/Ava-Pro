package com.example.ava.audio

import com.example.ava.settings.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientAutoGainTest {

    @Test
    fun quietRoomAddsNothing() {
        val slider = 0.67f
        val gain = AmbientAutoGain.gain(slider, rmsAtDb(-50f))
        assertEquals(0f, gain, 0.001f)
        assertEquals(slider, AmbientAutoGain.output(slider, rmsAtDb(-50f)), 0.001f)
    }

    @Test
    fun loudRoomOnlyAdds() {
        val slider = 0.40f
        val out = AmbientAutoGain.output(slider, rmsAtDb(-16f))
        assertTrue(out > slider)
        assertTrue(out <= 1f)
        assertTrue(AmbientAutoGain.gain(slider, rmsAtDb(-16f)) > 0f)
    }

    @Test
    fun neverGoesBelowSlider() {
        val slider = 0.67f
        for (db in -60..-10 step 2) {
            val out = AmbientAutoGain.output(slider, rmsAtDb(db.toFloat()))
            assertTrue("db=$db out=$out", out + 1e-5f >= slider)
        }
    }

    @Test
    fun fullSliderHasNoRoomToBoost() {
        assertEquals(0f, AmbientAutoGain.gain(1f, rmsAtDb(-12f)), 0.001f)
        assertEquals(1f, AmbientAutoGain.output(1f, rmsAtDb(-12f)), 0.001f)
    }

    @Test
    fun respectsMaxAddAndCeiling() {
        val slider = PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME
        val gain = AmbientAutoGain.gain(slider, rmsAtDb(-12f))
        assertTrue(gain <= AmbientAutoGain.MAX_ADD + 1e-5f)
        assertTrue(slider + gain <= 1f + 1e-5f)
    }

    @Test
    fun virtualGearsNeverFallBelowSlider() {
        val slider = 0.40f
        val gears = AmbientAutoGain.virtualGearOutputs(slider)
        assertEquals(4, gears.size)
        assertEquals(slider, gears.first(), 0.001f)
        for (out in gears) {
            assertTrue(out + 1e-5f >= slider)
            assertTrue(out <= 1f + 1e-5f)
        }
        assertTrue(gears.last() >= gears.first())
    }

    @Test
    fun gainIsWholePercentagePoints() {
        val slider = 0.40f
        for (db in -50..-12) {
            val gain = AmbientAutoGain.gain(slider, rmsAtDb(db.toFloat()))
            val hundredths = Math.round(gain * 100f)
            assertEquals(hundredths / 100f, gain, 0.0001f)
        }
    }

    @Test
    fun noiseBandSteps() {
        assertEquals(AmbientAutoGain.NoiseBand.Quiet, AmbientAutoGain.noiseBand(rmsAtDb(-50f)))
        assertEquals(AmbientAutoGain.NoiseBand.Normal, AmbientAutoGain.noiseBand(rmsAtDb(-36f)))
        assertEquals(AmbientAutoGain.NoiseBand.Noisy, AmbientAutoGain.noiseBand(rmsAtDb(-26f)))
        assertEquals(AmbientAutoGain.NoiseBand.Loud, AmbientAutoGain.noiseBand(rmsAtDb(-16f)))
    }

    private fun rmsAtDb(db: Float): Float =
        Math.pow(10.0, (db / 20.0)).toFloat()
}
