package com.example.ava.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalLauncherFastScrollerTest {

    @Test
    fun letterIndexMapsEvenSlotsAndClampsPastTheEnds() {
        val top = 20f
        val height = 260f
        assertEquals(0, niagaraLetterIndex(0f, top, height, 26))
        assertEquals(0, niagaraLetterIndex(top, top, height, 26))
        assertEquals(12, niagaraLetterIndex(top + height / 2f, top, height, 26))
        assertEquals(25, niagaraLetterIndex(top + height - 0.1f, top, height, 26))
        assertEquals(25, niagaraLetterIndex(top + height + 40f, top, height, 26))
        assertEquals(-1, niagaraLetterIndex(top, top, height, 0))
    }

    @Test
    fun handleEasesInsideTheActiveSlotAndFollowsOutside() {
        val center = 100f
        val slot = 20f
        assertEquals(center, niagaraHandleCenterY(center, center, slot), 0.01f)
        val inside = niagaraHandleCenterY(center + 4f, center, slot)
        assertTrue(inside > center)
        assertTrue(inside < center + 4f)
        assertEquals(center + 30f, niagaraHandleCenterY(center + 30f, center, slot), 0.01f)
    }

    @Test
    fun fanIsStrongestOnTheFingerAndFallsOff() {
        assertEquals(0f, niagaraFanWeight(0.1f, 0f), 0f)
        val onFinger = niagaraFanWeight(0f, 0.2f)
        val nearby = niagaraFanWeight(0.1f, 0.2f)
        val far = niagaraFanWeight(0.5f, 0.2f)
        assertEquals(1f, onFinger, 0.01f)
        assertTrue(nearby < onFinger)
        assertTrue(far < nearby)
    }

    @Test
    fun ropePullGrowsWhenTheFingerYanksInward() {
        assertEquals(88f, niagaraRopePull(88f, 120f, 0f), 0.01f)
        assertEquals(108f, niagaraRopePull(88f, 120f, 20f), 0.01f)
        assertEquals(120f, niagaraRopePull(88f, 120f, 80f), 0.01f)
        assertEquals(0f, niagaraRopePull(0f, 120f, 20f), 0.01f)
    }

    @Test
    fun ropeFanNormStaysWideEnoughForABend() {
        assertEquals(0f, niagaraRopeFanNorm(0f, 400f), 0f)
        assertEquals(FAST_SCROLLER_ROPE_MIN_NORM, niagaraRopeFanNorm(20f, 400f), 0.01f)
        assertEquals(0.5f, niagaraRopeFanNorm(200f, 400f), 0.01f)
    }

    @Test
    fun letterSizeFillsMostOfTheSlot() {
        assertEquals(18f, fastScrollerLetterPx(20f), 0.01f)
        val landscape = fastScrollerLetterPx(8f)
        assertEquals(8f * FAST_SCROLLER_LETTER_FILL, landscape, 0.01f)
        assertTrue(landscape >= 1f)
    }

    @Test
    fun portraitPacksTightThenGrowsOnTallerPanels() {
        val compact = 16f
        val roomy = 20f
        val short = fastScrollerSlotHeight(
            available = 16f * 26,
            count = 26,
            compactSlotPx = compact,
            roomySlotPx = roomy,
            stretchToFill = false,
        )
        assertEquals(16f, short, 0.01f)
        val mid = fastScrollerSlotHeight(
            available = 16f * 26 + 4f * 26,
            count = 26,
            compactSlotPx = compact,
            roomySlotPx = roomy,
            stretchToFill = false,
        )
        assertEquals(20f, mid, 0.01f)
        val tall = fastScrollerSlotHeight(
            available = 40f * 26,
            count = 26,
            compactSlotPx = compact,
            roomySlotPx = roomy,
            stretchToFill = false,
        )
        assertEquals(20f, tall, 0.01f)
    }

    @Test
    fun landscapeFillsTheInsetTrack() {
        val slot = fastScrollerSlotHeight(
            available = 260f,
            count = 26,
            compactSlotPx = 16f,
            roomySlotPx = 20f,
            stretchToFill = true,
        )
        assertEquals(10f, slot, 0.01f)
        val track = fastScrollerTrackMetrics(
            viewportHeight = 304f,
            count = 26,
            insetPx = 22f,
            compactSlotPx = 16f,
            roomySlotPx = 20f,
            stretchToFill = true,
        )
        assertEquals(22f, track.top, 0.01f)
        assertEquals(260f, track.height, 0.01f)
    }

    @Test
    fun portraitTrackCentersWhenShorterThanThePanel() {
        val track = fastScrollerTrackMetrics(
            viewportHeight = 22f * 2 + 20f * 26 + 80f,
            count = 26,
            insetPx = 22f,
            compactSlotPx = 16f,
            roomySlotPx = 20f,
            stretchToFill = false,
        )
        assertEquals(20f, track.slot, 0.01f)
        assertEquals(22f + 40f, track.top, 0.01f)
    }
}
