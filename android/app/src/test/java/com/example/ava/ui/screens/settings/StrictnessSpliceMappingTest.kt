package com.example.ava.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictnessSpliceMappingTest {
    @Test
    fun lastDetentIsTheBarEnd() {
        assertEquals(
            1f,
            StrictnessSpliceMapping.handleFraction(
                value = 0.65f,
                lo = 0.15f,
                hi = 0.65f,
                extraLevel = 2,
                extraZoneEnabled = true,
            ),
            0.0001f,
        )
        val atEnd = StrictnessSpliceMapping.fromFraction(
            fraction = 1f,
            lo = 0.15f,
            hi = 0.65f,
            extraZoneEnabled = true,
        )
        assertEquals(2, atEnd.extraLevel)
        assertEquals(0.65f, atEnd.value, 0.0001f)
    }

    @Test
    fun draggingPastTheSpliceEntersLevelOneThenTwo() {
        val atSplice = StrictnessSpliceMapping.fromFraction(0.72f, 0.15f, 0.65f, true)
        assertEquals(0, atSplice.extraLevel)
        assertEquals(0.65f, atSplice.value, 0.0001f)

        val justPast = StrictnessSpliceMapping.fromFraction(0.721f, 0.15f, 0.65f, true)
        assertEquals(1, justPast.extraLevel)

        val intoMax = StrictnessSpliceMapping.fromFraction(0.861f, 0.15f, 0.65f, true)
        assertEquals(2, intoMax.extraLevel)
    }

    @Test
    fun nativeCeilingHandleStaysAtTheSpliceNotTheBarEnd() {
        val fraction = StrictnessSpliceMapping.handleFraction(
            value = 0.65f,
            lo = 0.15f,
            hi = 0.65f,
            extraLevel = 0,
            extraZoneEnabled = true,
        )
        assertEquals(StrictnessSpliceMapping.SOLID_FRACTION, fraction, 0.0001f)
        assertTrue(fraction < 1f)
    }

    @Test
    fun extraZoneOffUsesTheFullBar() {
        val atEnd = StrictnessSpliceMapping.fromFraction(1f, 0.5f, 0.99f, extraZoneEnabled = false)
        assertEquals(0, atEnd.extraLevel)
        assertEquals(0.99f, atEnd.value, 0.0001f)
        assertEquals(
            1f,
            StrictnessSpliceMapping.handleFraction(0.99f, 0.5f, 0.99f, 2, extraZoneEnabled = false),
            0.0001f,
        )
    }
}
