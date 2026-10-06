package com.example.ava.wakelearn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OnDeviceVerifierTrainerTest {
    private val dims = 64

    /** Two clusters: genuine wakes shifted +1 on the first 8 dims, false wakes -1. */
    private fun cluster(positive: Boolean, n: Int, rng: Random, spread: Float = 0.6f) = List(n) { i ->
        val x = FloatArray(dims) { d ->
            val base = if (d < 8) (if (positive) 1f else -1f) else 0f
            base + (rng.nextFloat() - 0.5f) * 2f * spread
        }
        WakeLearnStore.Sample(positive, 1_000L + i, x)
    }

    @Test
    fun headRoundTripsThroughTheSharedContainer() {
        val head = LinearVerifierHead(FloatArray(dims) { it * 0.01f - 0.3f }, -1.25f)
        val parsed = LinearVerifierHead.parse(head.toBytes(), dims)
        assertNotNull(parsed)
        assertArrayEquals(head.weights, parsed!!.weights, 0f)
        assertEquals(head.bias, parsed.bias, 0f)
        assertNull(LinearVerifierHead.parse(head.toBytes(), dims + 1))
        assertNull(LinearVerifierHead.parse(head.toBytes().copyOf(20), null))
        assertNull(LinearVerifierHead.parse("not a head".toByteArray(), null))
    }

    @Test
    fun learnsToVetoHouseholdFalseWakesWhileKeepingGenuineOnes() {
        val rng = Random(7)
        val samples = cluster(true, 24, rng) + cluster(false, 12, rng)
        val result = OnDeviceVerifierTrainer.train(samples, prior = null, dims = dims, vetoThreshold = 0.5f)
        assertNotNull("trainer must publish on separable household data", result)
        val head = result!!.head
        val fresh = cluster(true, 20, Random(99)) + cluster(false, 20, Random(98))
        val keptPositives = fresh.filter { it.positive }.count { head.score(it.x) >= 0.5f }
        val vetoedNegatives = fresh.filterNot { it.positive }.count { head.score(it.x) < 0.5f }
        assertTrue("kept $keptPositives/20 genuine wakes", keptPositives >= 19)
        assertTrue("vetoed $vetoedNegatives/20 false wakes", vetoedNegatives >= 19)
        assertTrue(result.report.heldOutPositivePass >= OnDeviceVerifierTrainer.MIN_POSITIVE_RETENTION)
    }

    @Test
    fun refusesToPublishBelowTheMinimumEvidence() {
        val rng = Random(3)
        assertNull(OnDeviceVerifierTrainer.train(cluster(true, 5, rng) + cluster(false, 3, rng), null, dims, 0.5f))
        assertNull(OnDeviceVerifierTrainer.train(cluster(true, 10, rng) + cluster(false, 2, rng), null, dims, 0.5f))
        // Wrong-dimension samples are ignored rather than crashing the fit.
        val odd = List(10) { WakeLearnStore.Sample(true, it.toLong(), FloatArray(dims + 3)) }
        assertNull(OnDeviceVerifierTrainer.train(odd + cluster(false, 4, rng), null, dims, 0.5f))
    }

    @Test
    fun refusesToPublishWhenLabelsAreNoise() {
        // Identical distributions for both labels: any fit would only hurt genuine wakes.
        val rng = Random(11)
        val samples = cluster(true, 20, rng, spread = 3f).map {
            WakeLearnStore.Sample(rng.nextBoolean(), it.timestampMs, it.x)
        }.let { s ->
            // Guarantee both classes are present in enough numbers.
            s + cluster(true, 8, rng, spread = 3f).map { WakeLearnStore.Sample(true, it.timestampMs, it.x) } +
                cluster(true, 8, rng, spread = 3f).map { WakeLearnStore.Sample(false, it.timestampMs, it.x) }
        }
        val result = OnDeviceVerifierTrainer.train(samples, null, dims, 0.5f)
        // Either no head, or one whose cross-validated positive retention cleared the bar.
        if (result != null) {
            assertTrue(result.report.heldOutPositivePass >= OnDeviceVerifierTrainer.MIN_POSITIVE_RETENTION)
        }
    }

    @Test
    fun priorHeadAnchorsTheFitAndIsOnlyReplacedWhenBetter() {
        val rng = Random(5)
        // A factory head that already separates the clusters along dim 0.
        val prior = LinearVerifierHead(FloatArray(dims) { if (it == 0) 2f else 0f }, 0f)
        val samples = cluster(true, 12, rng) + cluster(false, 6, rng)
        val result = OnDeviceVerifierTrainer.train(samples, prior, dims, 0.5f)
        assertNotNull(result)
        // The learned head keeps the prior's decision on the clusters and stays close to it
        // on dims the data cannot inform (noise dims carry tiny weights).
        val noiseWeight = result!!.head.weights.drop(8).maxOf { kotlin.math.abs(it) }
        val signalWeight = kotlin.math.abs(result.head.weights[0])
        assertTrue("signal $signalWeight should dominate noise $noiseWeight", signalWeight > noiseWeight * 3)
        assertFalse(result.report.heldOutNegativeVeto < result.report.priorHeldOutNegativeVeto)
    }

    @Test
    fun microWindowPoolsPairsOldestFirstAndZeroFillsMissingHistory() {
        val w = MicroVerifierWindow()
        assertNull(w.snapshot())
        // Push two frames of a 4-dim frontend: frame k has all values = k.
        repeat(2) { k -> w.push(FloatArray(4) { (k + 1).toFloat() }) }
        val snap = w.snapshot()!!
        assertEquals(MicroVerifierWindow.FRAMES / MicroVerifierWindow.POOL * 4, snap.size)
        // Only the newest pooled slot has data: mean of frames 1 and 2 = 1.5.
        val last = snap.size - 4
        assertEquals(1.5f, snap[last], 1e-6f)
        assertEquals(0f, snap[last - 4], 1e-6f)
        // Overfill: the ring keeps the newest FRAMES frames.
        repeat(MicroVerifierWindow.FRAMES + 10) { w.push(FloatArray(4) { 7f }) }
        assertTrue(w.snapshot()!!.all { kotlin.math.abs(it - 7f) < 1e-6f })
        w.reset()
        assertTrue(w.snapshot()!!.all { it == 0f })
    }
}
