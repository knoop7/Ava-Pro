package com.example.ava.multidevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChorusWinnerTest {

    @Test
    fun louderDeviceWinsEvenWithLaterClock() {
        val winner = pickChorusWinner(
            myId = "ava_slow_clock",
            myTimestamp = 1_000L,
            myScore = packScore(0.99f, 0.05f),
            claims = listOf(
                ChorusClaim("ava_closer", timestamp = 9_000L, score = packScore(0.80f, 0.40f)),
            ),
        )
        assertEquals("ava_closer", winner)
    }

    @Test
    fun earlierTimestampDoesNotBeatAClearlyLouderPeer() {
        val winner = pickChorusWinner(
            myId = "ava_first_pipeline",
            myTimestamp = 100L,
            myScore = packScore(0.70f, 0.08f),
            claims = listOf(
                ChorusClaim("ava_late_but_loud", timestamp = 400L, score = packScore(0.70f, 0.35f)),
            ),
        )
        assertEquals("ava_late_but_loud", winner)
    }

    @Test
    fun nearTieDoesNotAlwaysPickTheLexicographicallyFirstId() {
        val a = pickChorusWinner(
            myId = "ava_aaa",
            myTimestamp = 2_000L,
            myScore = 1_000,
            claims = listOf(ChorusClaim("ava_zzz", timestamp = 2_000L, score = 1_000)),
        )
        val b = pickChorusWinner(
            myId = "ava_aaa",
            myTimestamp = 8_000L,
            myScore = 1_000,
            claims = listOf(ChorusClaim("ava_zzz", timestamp = 8_000L, score = 1_000)),
        )
        assertTrue(a == "ava_aaa" || a == "ava_zzz")
        assertTrue(b == "ava_aaa" || b == "ava_zzz")
        assertNotEquals(
            "close scores should rotate across time buckets, not lock ava_aaa",
            a,
            b,
        )
    }

    @Test
    fun packScorePrefersRmsOverConfidence() {
        val quietHighConf = packScore(0.99f, 0.04f)
        val loudLowConf = packScore(0.55f, 0.30f)
        assertTrue(loudLowConf > quietHighConf)
    }

    /**
     * Guards the reason [com.example.ava.esphome.voicesatellite.VoiceSatelliteAudioInput
     * .lastDetectRms] reports a windowed peak rather than the newest frame.
     *
     * A streaming detector crosses its threshold on the quiet frame *after* the
     * phrase, so a per-frame ruler hands every device rms=0.000 (observed on
     * device: score=850 from conf=0.85, rms=0.000). Proximity then contributes
     * nothing and the box that merely scored higher takes the seat, which is the
     * opposite of what [packScorePrefersRmsOverConfidence] is protecting.
     */
    @Test
    fun zeroRmsCollapsesRankingToConfidenceAlone() {
        val nearDeviceQuietFrame = packScore(0.85f, 0f)
        val farDeviceConfidentFrame = packScore(0.99f, 0f)
        assertTrue(farDeviceConfidentFrame > nearDeviceQuietFrame)

        // Same two devices, ranked on the peak each actually heard: near wins.
        val nearDevicePeak = packScore(0.85f, 0.30f)
        val farDevicePeak = packScore(0.99f, 0.05f)
        assertTrue(nearDevicePeak > farDevicePeak)
    }
}
