package com.example.ava.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPadThemeTest {
    @Test
    fun darkChromeIsNeutralNotBlueBlack() {
        val dark = TouchPadTheme.palette(true)
        assertEquals(0x1C1D1F, dark.bodyRgb)
        assertEquals(0x26D6D6D6, dark.divider)
    }

    @Test
    fun lightChromeUsesGrayAndLowerAlpha() {
        val night = TouchPadTheme.palette(true)
        val day = TouchPadTheme.palette(false)
        assertEquals(0xD8DADC, day.bodyRgb)
        assertTrue(TouchPadTheme.overlayAlpha(false, 88) < TouchPadTheme.overlayAlpha(true, 88))
        assertTrue(day.lightGlass)
        assertTrue(!night.lightGlass)
        assertEquals(0x7A7E82, day.cursorRgb)
        assertEquals(0xE8E8E8, night.cursorRgb)
        assertEquals(0xFF7A7E82.toInt(), day.handleStroke)
    }
}
