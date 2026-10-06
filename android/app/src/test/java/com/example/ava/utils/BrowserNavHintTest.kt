package com.example.ava.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserNavHintTest {

    @Test
    fun excludedZoneNeverHints() {
        assertNull(
            browserNavHint(
                diffX = 80f,
                diffY = 0f,
                isEdgeSwipe = true,
                excludedZone = true,
                canGoBack = true,
                canGoForward = true,
                touchSlop = 8f,
                distancePx = 72f,
            ),
        )
    }

    @Test
    fun slopAndVerticalStaySilent() {
        assertNull(hint(diffX = 6f, diffY = 0f, isEdgeSwipe = true))
        assertNull(hint(diffX = 40f, diffY = 50f, isEdgeSwipe = true))
    }

    @Test
    fun backNeedsLeftEdgeAndHistory() {
        assertNull(hint(diffX = 40f, diffY = 0f, isEdgeSwipe = false, canGoBack = true))
        assertNull(hint(diffX = 40f, diffY = 0f, isEdgeSwipe = true, canGoBack = false))
        val shown = hint(diffX = 36f, diffY = 0f, isEdgeSwipe = true, canGoBack = true)
        assertEquals(BrowserNavHintKind.BACK, shown!!.kind)
        assertEquals(0.5f, shown.progress, 0.001f)
    }

    @Test
    fun forwardFromAnywhereWhenHistoryExists() {
        assertNull(hint(diffX = -40f, diffY = 0f, isEdgeSwipe = false, canGoForward = false))
        val shown = hint(diffX = -72f, diffY = 0f, isEdgeSwipe = false, canGoForward = true)
        assertEquals(BrowserNavHintKind.FORWARD, shown!!.kind)
        assertEquals(1f, shown.progress, 0.001f)
    }

    @Test
    fun progressClampsAtCommitDistance() {
        val over = hint(diffX = 200f, diffY = 0f, isEdgeSwipe = true, canGoBack = true)
        assertEquals(1f, over!!.progress, 0.001f)
        val shy = hint(diffX = 71f, diffY = 0f, isEdgeSwipe = true, canGoBack = true)
        assertEquals(71f / 72f, shy!!.progress, 0.001f)
    }

    private fun hint(
        diffX: Float,
        diffY: Float,
        isEdgeSwipe: Boolean,
        canGoBack: Boolean = false,
        canGoForward: Boolean = false,
    ): BrowserNavHint? = browserNavHint(
        diffX = diffX,
        diffY = diffY,
        isEdgeSwipe = isEdgeSwipe,
        excludedZone = false,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        touchSlop = 8f,
        distancePx = 72f,
    )
}
