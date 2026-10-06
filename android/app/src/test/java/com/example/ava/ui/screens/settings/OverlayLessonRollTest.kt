package com.example.ava.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayLessonRollTest {

    @Test
    fun scrollYStartsAtTop() {
        assertEquals(0, overlayLessonScrollY(listOf(100, 80), 0, 12))
        assertEquals(0, overlayLessonScrollY(emptyList(), 3, 12))
        assertEquals(0, overlayLessonScrollY(listOf(100), -1, 12))
    }

    @Test
    fun scrollYStacksItemAndSpacing() {
        val heights = listOf(100, 80, 120)
        assertEquals(112, overlayLessonScrollY(heights, 1, 12))
        assertEquals(204, overlayLessonScrollY(heights, 2, 12))
        assertEquals(336, overlayLessonScrollY(heights, 3, 12))
    }
}
