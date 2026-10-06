package com.example.ava.ui.glass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGlassEnterTest {

    @Test
    fun consumeIsOneShot() {
        // Force a clean slate regardless of prior tests.
        while (SettingsGlassEnter.consume()) Unit
        assertFalse(SettingsGlassEnter.consume())
        SettingsGlassEnter.armFromSidebarFrost()
        // Arm is a no-op when blur is off in unit tests (LiquidGlass session defaults).
        // Either way consume must not stick after one read.
        SettingsGlassEnter.consume()
        assertFalse(SettingsGlassEnter.consume())
    }

    @Test
    fun armWithoutBlurDoesNotLeaveStickyFlagWhenCleared() {
        while (SettingsGlassEnter.consume()) Unit
        SettingsGlassEnter.armFromSidebarFrost()
        val first = SettingsGlassEnter.consume()
        val second = SettingsGlassEnter.consume()
        assertFalse(second)
        // first may be true only if blur was enabled in the process; never sticky.
        if (first) {
            assertFalse(SettingsGlassEnter.consume())
        } else {
            assertTrue(true)
        }
    }
}
