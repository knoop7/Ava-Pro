package com.example.ava.ui.screens.settings

import com.example.ava.R
import com.example.ava.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSplitBackTest {
    @Test
    fun splitHomepageOverlayHidesVoiceConfigBack() {
        assertTrue(
            shouldHideSettingsSplitBack(
                splitActive = true,
                ownerRoute = "语音配置",
                currentRoute = Screen.SETTINGS,
            ),
        )
    }

    @Test
    fun splitGroupRootHidesBack() {
        assertTrue(
            shouldHideSettingsSplitBack(
                splitActive = true,
                ownerRoute = Screen.SETTINGS_CONNECTION,
                currentRoute = Screen.SETTINGS_CONNECTION,
            ),
        )
    }

    @Test
    fun splitVoiceSubpageKeepsBack() {
        assertFalse(
            shouldHideSettingsSplitBack(
                splitActive = true,
                ownerRoute = Screen.SETTINGS_VOICE_WAKE,
                currentRoute = Screen.SETTINGS_VOICE_WAKE,
            ),
        )
    }

    @Test
    fun stackedModeKeepsBack() {
        assertFalse(
            shouldHideSettingsSplitBack(
                splitActive = false,
                ownerRoute = Screen.SETTINGS_CONNECTION,
                currentRoute = Screen.SETTINGS_CONNECTION,
            ),
        )
    }

    @Test
    fun splitHomepageOverlayUsesConnectionIcon() {
        assertEquals(
            R.drawable.esphome_24px,
            settingsSplitGroupLeadingIcon("语音配置", Screen.SETTINGS),
        )
    }

    @Test
    fun splitGroupRootUsesMatchingCardIcon() {
        assertEquals(
            R.drawable.mdi_cog_transfer,
            settingsSplitGroupLeadingIcon(Screen.SETTINGS_SERVICE, Screen.SETTINGS_SERVICE),
        )
    }

    @Test
    fun splitVoiceSubpageHasNoLeadingIcon() {
        assertNull(
            settingsSplitGroupLeadingIcon(
                Screen.SETTINGS_VOICE_WAKE,
                Screen.SETTINGS_VOICE_WAKE,
            ),
        )
    }
}
