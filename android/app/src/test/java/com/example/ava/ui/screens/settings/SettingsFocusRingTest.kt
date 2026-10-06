package com.example.ava.ui.screens.settings

import com.example.ava.ui.screens.settings.components.paintSettingsFocusRing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFocusRingTest {

    @Test
    fun settingsPagePaintsWhenFocusedAndRemoteArmed() {
        assertTrue(paintSettingsFocusRing(focused = true, remoteArmed = true, sidebarRingVisible = true))
    }

    @Test
    fun drawerStaysTransparentUntilRevealed() {
        assertFalse(paintSettingsFocusRing(focused = true, remoteArmed = true, sidebarRingVisible = false))
    }

    @Test
    fun drawerPaintsAfterRemoteReveal() {
        assertTrue(paintSettingsFocusRing(focused = true, remoteArmed = true, sidebarRingVisible = true))
    }

    @Test
    fun neverPaintsWithoutFocusOrRemote() {
        assertFalse(paintSettingsFocusRing(focused = false, remoteArmed = true, sidebarRingVisible = true))
        assertFalse(paintSettingsFocusRing(focused = true, remoteArmed = false, sidebarRingVisible = true))
        assertFalse(paintSettingsFocusRing(focused = true, remoteArmed = false, sidebarRingVisible = false))
    }
}
