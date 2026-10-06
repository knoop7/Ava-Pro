package com.example.ava.appwindow

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeformMicKeepAliveTest {

    @Test
    fun micSliverStaysOffBelowAndroid10AndWhenFreeformIsOff() {
        assertFalse(FreeformKeepAliveActivity.freeformMicKeepAliveApplies(28, 1))
        assertFalse(FreeformKeepAliveActivity.freeformMicKeepAliveApplies(29, 0))
        assertTrue(FreeformKeepAliveActivity.freeformMicKeepAliveApplies(29, 1))
        assertTrue(FreeformKeepAliveActivity.freeformMicKeepAliveApplies(36, 1))
    }

    @Test
    fun sliverKeepsMinimumVisiblePixelsOnScreen() {
        val mdpi = FreeformKeepAliveActivity.freeformMicSliverBounds(1280, 800, 1f)
        assertEquals(Rect(1232, 768, 1452, 988), mdpi)

        val xhdpi = FreeformKeepAliveActivity.freeformMicSliverBounds(1920, 1200, 2f)
        assertEquals(Rect(1824, 1136, 2264, 1576), xhdpi)
    }

    @Test
    fun sliverClampsOntoATinyDisplay() {
        val bounds = FreeformKeepAliveActivity.freeformMicSliverBounds(20, 10, 1f)
        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertTrue(bounds.width() >= FreeformKeepAliveActivity.MIN_VISIBLE_WIDTH_DP)
        assertTrue(bounds.height() >= FreeformKeepAliveActivity.MIN_VISIBLE_HEIGHT_DP)
    }

    @Test
    fun repinBackoffCapsAndKeepsRetrying() {
        assertEquals(300L, FreeformKeepAliveActivity.freeformMicRepinDelayMs(0))
        assertEquals(1_000L, FreeformKeepAliveActivity.freeformMicRepinDelayMs(1))
        assertEquals(2_000L, FreeformKeepAliveActivity.freeformMicRepinDelayMs(2))
        assertEquals(4_000L, FreeformKeepAliveActivity.freeformMicRepinDelayMs(3))
        assertEquals(16_000L, FreeformKeepAliveActivity.freeformMicRepinDelayMs(5))
        assertEquals(30_000L, FreeformKeepAliveActivity.freeformMicRepinDelayMs(6))
        assertEquals(30_000L, FreeformKeepAliveActivity.freeformMicRepinDelayMs(20))
    }
}
