package com.example.ava.ui.screens.settings

import com.example.ava.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchCatalogTest {
    private val wake = resolveSettingsSearchHit(
        title = "Wake word",
        path = "Voice Config",
        subtitle = "Custom wake words and wake sounds",
        route = Screen.SETTINGS_VOICE_WAKE,
    )
    private val brightness = resolveSettingsSearchHit(
        title = "Screen Brightness",
        path = "Device Controls",
        subtitle = "Sync screen brightness with Home Assistant",
        route = Screen.SETTINGS_SERVICE_SCREEN_BRIGHTNESS,
    )
    private val browser = resolveSettingsSearchHit(
        title = "Web Browser",
        path = "Web Browser",
        subtitle = "Display · Touch · Compat",
        route = Screen.SETTINGS_BROWSER,
    )

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(filterSettingsSearch("   ", listOf(wake, brightness)).isEmpty())
    }

    @Test
    fun titleTokenMatches() {
        val hits = filterSettingsSearch("wake", listOf(wake, brightness))
        assertEquals(listOf(Screen.SETTINGS_VOICE_WAKE), hits.map { it.route })
    }

    @Test
    fun pathAndSubtitleTokensMustAllMatch() {
        val hits = filterSettingsSearch("screen brightness", listOf(wake, brightness))
        assertEquals(listOf(Screen.SETTINGS_SERVICE_SCREEN_BRIGHTNESS), hits.map { it.route })
    }

    @Test
    fun chineseSubstringMatches() {
        val mic = resolveSettingsSearchHit(
            title = "麦克风",
            path = "语音配置",
            subtitle = "智能降噪、回声消除与增益",
            route = Screen.SETTINGS_VOICE_MICROPHONE,
        )
        val hits = filterSettingsSearch("降噪", listOf(wake, mic))
        assertEquals(listOf(Screen.SETTINGS_VOICE_MICROPHONE), hits.map { it.route })
    }

    @Test
    fun hidesGatedGroups() {
        val visible = SettingsSearchCatalog.visible(
            showBrowser = false,
            showScreensaver = false,
            showExperimental = true,
            showBluetooth = false,
            showCamera = false,
        )
        assertFalse(visible.any { it.route == Screen.SETTINGS_BROWSER })
        assertFalse(visible.any { it.route == Screen.SETTINGS_BLUETOOTH })
        assertFalse(visible.any { it.route == Screen.SETTINGS_CAMERA })
        assertTrue(visible.any { it.route == Screen.SETTINGS_VOICE_WAKE })
        assertTrue(visible.any { it.route == Screen.MOD_STORE })
    }

    @Test
    fun cameraNeedsExperimentalAndCameraGates() {
        val hidden = SettingsSearchCatalog.visible(
            showBrowser = true,
            showScreensaver = true,
            showExperimental = true,
            showBluetooth = true,
            showCamera = false,
        )
        assertFalse(hidden.any { it.route == Screen.SETTINGS_CAMERA })
        val shown = SettingsSearchCatalog.visible(
            showBrowser = true,
            showScreensaver = true,
            showExperimental = true,
            showBluetooth = true,
            showCamera = true,
        )
        assertTrue(shown.any { it.route == Screen.SETTINGS_CAMERA })
        assertTrue(shown.any { it.route == browser.route })
    }

    @Test
    fun searchNavStaysOnSettingsHomeForVoiceConfig() {
        assertEquals(
            SettingsSearchNavAction.PopToSettingsHome,
            settingsSearchNavAction(Screen.SETTINGS),
        )
        assertEquals(
            SettingsSearchNavAction.PopToSettingsHome,
            settingsSearchNavAction(Screen.SETTINGS_CONNECTION),
        )
    }

    @Test
    fun searchNavUsesGroupRootAndPage() {
        assertEquals(
            SettingsSearchNavAction.GroupRoot(Screen.SETTINGS_BROWSER),
            settingsSearchNavAction(Screen.SETTINGS_BROWSER),
        )
        assertEquals(
            SettingsSearchNavAction.Page(Screen.SETTINGS_VOICE_WAKE),
            settingsSearchNavAction(Screen.SETTINGS_VOICE_WAKE),
        )
    }
}
