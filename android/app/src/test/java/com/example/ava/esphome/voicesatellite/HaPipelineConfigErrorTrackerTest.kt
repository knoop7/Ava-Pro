package com.example.ava.esphome.voicesatellite

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HaPipelineConfigErrorTrackerTest {

    @Before
    @After
    fun reset() {
        HaPipelineConfigErrorTracker.resetForTest()
    }

    @Test
    fun twoLineMissesDoNotReachTheTip() {
        repeat(2) {
            assertTrue(HaPipelineConfigErrorTracker.note(HaPipelineConfigErrorTracker.HA_DISCONNECTED))
        }
        assertEquals(2, HaPipelineConfigErrorTracker.debugStreak())
        assertEquals(2, HaPipelineConfigErrorTracker.THRESHOLD - 1)
    }

    @Test
    fun thirdLineMissReachesTheThreshold() {
        repeat(3) {
            HaPipelineConfigErrorTracker.note(HaPipelineConfigErrorTracker.HA_DISCONNECTED)
        }
        assertEquals(HaPipelineConfigErrorTracker.THRESHOLD, HaPipelineConfigErrorTracker.debugStreak())
    }

    @Test
    fun liveSttClearsTheStreak() {
        repeat(3) { HaPipelineConfigErrorTracker.note(HaPipelineConfigErrorTracker.HA_NOT_SUBSCRIBED) }
        HaPipelineConfigErrorTracker.note("stt-no-text")
        assertEquals(0, HaPipelineConfigErrorTracker.debugStreak())
    }

    @Test
    fun wakeNoiseDoesNotCountOrReset() {
        HaPipelineConfigErrorTracker.note(HaPipelineConfigErrorTracker.HA_DISCONNECTED)
        assertFalse(HaPipelineConfigErrorTracker.note("wake-word-error"))
        assertEquals(1, HaPipelineConfigErrorTracker.debugStreak())
    }

    @Test
    fun recoveredSubscribeClearsTheStreak() {
        repeat(4) { HaPipelineConfigErrorTracker.note(HaPipelineConfigErrorTracker.HA_DISCONNECTED) }
        HaPipelineConfigErrorTracker.onPipelineRecovered()
        assertEquals(0, HaPipelineConfigErrorTracker.debugStreak())
    }
}
