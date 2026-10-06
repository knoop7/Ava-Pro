package com.example.ava.wakelearn

import com.example.ava.settings.WakeWordEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLearnSpeakerNotchTest {
    private val frames = MicroVerifierWindow.FRAMES / MicroVerifierWindow.POOL
    private val dim = 40

    private fun window(fill: Float = 1f) = FloatArray(frames * dim) { fill }

    @Test
    fun scoopsOnlyTheFourSpeakerNotchBins() {
        val src = window(1f)
        val out = WakeLearnSpeakerNotch.applyMicro(src)
        assertNotNull(out)
        val notch = WakeLearnSpeakerNotch.MICRO_NOTCH_BINS.toSet()
        for (frame in 0 until frames) {
            val base = frame * dim
            for (bin in 0 until dim) {
                val got = out!![base + bin]
                if (bin in notch) {
                    assertEquals(WakeLearnSpeakerNotch.KEEP, got, 1e-6f)
                } else {
                    assertEquals(1f, got, 0f)
                }
            }
        }
        assertEquals(1f, src[25], 0f)
    }

    @Test
    fun rejectsWindowsThatAreNotTheMicroFrontend() {
        assertNull(WakeLearnSpeakerNotch.applyMicro(FloatArray(64)))
        assertNull(WakeLearnSpeakerNotch.applyMicro(FloatArray(frames * 20)))
    }

    @Test
    fun augmentAddsOneNotchedNegativePerMicroPositive() {
        val pos = WakeLearnStore.Sample(true, 10L, window(0.7f))
        val neg = WakeLearnStore.Sample(false, 11L, window(0.2f))
        val out = WakeLearnSpeakerNotch.augment(WakeWordEngine.MICRO_WAKE_WORD, listOf(pos, neg))
        assertEquals(3, out.size)
        assertTrue(out[0].positive)
        assertTrue(!out[1].positive && out[1].timestampMs == 11L)
        assertTrue(!out[2].positive && out[2].timestampMs == 10L)
        assertEquals(0.7f * WakeLearnSpeakerNotch.KEEP, out[2].x[25], 1e-5f)
        assertEquals(0.7f, out[2].x[0], 1e-5f)
    }

    @Test
    fun openWakeWordWindowsAreLeftAlone() {
        val samples = listOf(WakeLearnStore.Sample(true, 1L, FloatArray(16 * 96) { 1f }))
        val out = WakeLearnSpeakerNotch.augment(WakeWordEngine.OPEN_WAKE_WORD, samples)
        assertSame(samples, out)
    }

    @Test
    fun trainerLearnsToVetoTheNotchedCopyOfAGenuineWake() {
        val genuine = List(8) { i ->
            val x = window(0.4f)
            for (frame in 0 until frames) {
                val base = frame * dim
                for (bin in WakeLearnSpeakerNotch.MICRO_NOTCH_BINS) x[base + bin] = 1.2f
            }
            WakeLearnStore.Sample(true, 1_000L + i, x)
        }
        val samples = WakeLearnSpeakerNotch.augment(WakeWordEngine.MICRO_WAKE_WORD, genuine)
        val result = OnDeviceVerifierTrainer.train(
            samples, prior = null, dims = frames * dim, vetoThreshold = 0.5f,
        )
        assertNotNull(result)
        val head = result!!.head
        assertTrue(head.score(genuine[0].x) >= 0.5f)
        val notched = WakeLearnSpeakerNotch.applyMicro(genuine[0].x)!!
        assertTrue(head.score(notched) < 0.5f)
    }
}
