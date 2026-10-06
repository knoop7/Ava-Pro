package com.example.ava.localllm.remote

import com.example.ava.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaSettingsPrefsTest {

    @Test fun idleScreensaverSpokenWordsFindTheSwitch() {
        assertEquals("enable_idle_screensaver", AvaSettingsPrefs.resolve("动态屏保开了吗")?.id)
        assertEquals("enable_idle_screensaver", AvaSettingsPrefs.resolve("动态频表")?.id)
        assertEquals("enable_idle_screensaver", AvaSettingsPrefs.resolve("enable_idle_screensaver")?.id)
    }

    @Test fun otherPageSwitchesResolve() {
        assertEquals("noise_suppressor", AvaSettingsPrefs.resolve("硬件降噪")?.id)
        assertEquals("voice_print", AvaSettingsPrefs.resolve("声纹")?.id)
        assertEquals("dawn_magazine", AvaSettingsPrefs.resolve("画报")?.id)
        assertEquals("screensaver_show_after_screen_on", AvaSettingsPrefs.resolve("开屏后显示")?.id)
        assertEquals("sidebar_enable", AvaSettingsPrefs.resolve("侧栏")?.id)
    }

    @Test fun screensaverPageCarriesTheMasterSwitch() {
        val ids = AvaSettingsPrefs.forRoute(Screen.SETTINGS_SCREENSAVER).map { it.id }
        assertTrue(ids.contains("enable_idle_screensaver"))
        assertTrue(ids.contains("screensaver_timeout"))
        assertTrue(AvaSettingsPrefs.ALL.all { it.keys.isNotEmpty() })
    }
}
