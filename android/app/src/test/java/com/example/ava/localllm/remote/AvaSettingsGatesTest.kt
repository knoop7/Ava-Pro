package com.example.ava.localllm.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaSettingsGatesTest {

    @Test fun musicSpokenWordsFindTheDoor() {
        assertEquals("enable_music", AvaSettingsGates.match("放一首歌").first().id)
        assertEquals("enable_music", AvaSettingsGates.match("来点音乐").first().id)
        assertEquals("enable_music", AvaSettingsGates.resolve("enable_music")?.id)
        assertTrue(AvaSettingsGates.isGateId("enable_music"))
    }

    @Test fun idleScreensaverSpokenWordsFindTheDoor() {
        assertEquals("enable_idle_screensaver", AvaSettingsGates.match("动态屏保开了吗").first().id)
        assertEquals("enable_idle_screensaver", AvaSettingsGates.match("动态频表").first().id)
        assertEquals("enable_idle_screensaver", AvaSettingsGates.resolve("enable_idle_screensaver")?.id)
    }

    @Test fun weatherQuestionIsNotTheOverlayDoor() {
        assertTrue(AvaSettingsGates.match("今天天气怎么样").isEmpty())
        assertEquals("enable_weather", AvaSettingsGates.match("打开天气悬浮").first().id)
    }

    @Test fun enableWordsAreDetected() {
        assertTrue(AvaSettingsGates.wantsEnable("打开音乐"))
        assertTrue(AvaSettingsGates.wantsEnable("帮我开梦幻时钟"))
        assertFalse(AvaSettingsGates.wantsEnable("放一首歌"))
    }

    @Test fun catalogDoesNotIncludeVoiceSeatKillButtons() {
        assertFalse(AvaSettingsGates.ALL.any { it.id.contains("wake") || it.id.contains("kill") })
        assertTrue(AvaSettingsGates.ALL.map { it.id }.containsAll(listOf(
            "enable_music",
            "enable_dream_clock",
            "enable_simple_clock",
            "enable_weather",
            "enable_voice_message",
            "enable_browser",
            "enable_quick_entity",
            "enable_idle_screensaver",
        )))
    }
}
