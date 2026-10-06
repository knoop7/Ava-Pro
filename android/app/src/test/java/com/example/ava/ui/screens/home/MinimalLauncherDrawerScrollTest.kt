package com.example.ava.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalLauncherDrawerScrollTest {

    @Test
    fun lineIndexUsesFixedColumns() {
        assertEquals(0, drawerGridLine(0, 4))
        assertEquals(0, drawerGridLine(3, 4))
        assertEquals(1, drawerGridLine(4, 4))
        assertEquals(4, drawerGridLine(17, 4))
        assertEquals(0, drawerGridLine(2, 0))
    }

    @Test
    fun nearbyRowsSkipTheTeleport() {
        assertNull(planDrawerGridApproachIndex(0, 7, columns = 4))
        assertNull(planDrawerGridApproachIndex(0, 32, columns = 4))
        assertNull(planDrawerGridApproachIndex(40, 8, columns = 4))
    }

    @Test
    fun farHopLandsATailAwaySoTheLastRowsCanSlide() {
        val down = planDrawerGridApproachIndex(0, 80, columns = 4)
        assertEquals(16 * 4, down)
        val up = planDrawerGridApproachIndex(80, 0, columns = 4)
        assertEquals(4 * 4, up)
    }

    @Test
    fun estimatedDeltaIsRowStrideMinusCurrentOffset() {
        val stride = 120f
        assertEquals(240f, drawerGridEstimatedDeltaPx(0, 0, 8, 4, stride), 0.01f)
        assertEquals(200f, drawerGridEstimatedDeltaPx(0, 40, 8, 4, stride), 0.01f)
        assertEquals(-120f, drawerGridEstimatedDeltaPx(8, 0, 4, 4, stride), 0.01f)
    }

    @Test
    fun visibleLineDeltaPutsTheRowOnTheContentEdge() {
        assertEquals(0f, drawerGridVisibleLineDeltaPx(22, 22), 0.01f)
        assertEquals(180f, drawerGridVisibleLineDeltaPx(202, 22), 0.01f)
        assertEquals(-40f, drawerGridVisibleLineDeltaPx(0, 40), 0.01f)
    }

    @Test
    fun rowStridePrefersMeasuredLineGaps() {
        val measured = drawerGridRowStridePx(listOf(22, 142, 262), fallbackItemHeight = 100, spacingPx = 10f)
        assertEquals(120f, measured, 0.01f)
        val fallback = drawerGridRowStridePx(listOf(22), fallbackItemHeight = 100, spacingPx = 10f)
        assertEquals(110f, fallback, 0.01f)
        assertTrue(drawerGridRowStridePx(emptyList(), 0, 0f) >= 1f)
    }
}
