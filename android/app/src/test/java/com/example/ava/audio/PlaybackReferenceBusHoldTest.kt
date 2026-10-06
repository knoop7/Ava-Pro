package com.example.ava.audio

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Live-call playback and satellite capture share [PlaybackReferenceBus]. Pausing
 * the satellite must not drop the call's far-end hold, or software AEC goes blind.
 */
class PlaybackReferenceBusHoldTest {

    @Before
    @After
    fun drainHolds() {
        repeat(8) { PlaybackReferenceBus.release() }
    }

    @Test
    fun lastReleaseTurnsTheBusOff() {
        assertFalse(PlaybackReferenceBus.active)
        assertTrue(PlaybackReferenceBus.acquire())
        assertTrue(PlaybackReferenceBus.active)
        assertFalse(PlaybackReferenceBus.acquire())
        assertTrue(PlaybackReferenceBus.active)
        assertFalse(PlaybackReferenceBus.release())
        assertTrue(PlaybackReferenceBus.active)
        assertTrue(PlaybackReferenceBus.release())
        assertFalse(PlaybackReferenceBus.active)
    }

    @Test
    fun extraReleaseStaysIdle() {
        assertTrue(PlaybackReferenceBus.release())
        assertFalse(PlaybackReferenceBus.active)
        assertTrue(PlaybackReferenceBus.acquire())
        assertTrue(PlaybackReferenceBus.active)
        assertTrue(PlaybackReferenceBus.release())
        assertFalse(PlaybackReferenceBus.active)
    }
}
