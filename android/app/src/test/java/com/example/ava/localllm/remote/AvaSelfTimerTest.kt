package com.example.ava.localllm.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaSelfTimerTest {
    @Test fun schemaOmitsTimerFieldsUntilTheActionIsOn() {
        val off = AvaSelfTools.schema(listOf("read", "stop")).tools.single()
        assertFalse(off.params.any { it.name == "duration_s" })
        assertFalse(off.description.contains("flip-clock"))
        assertFalse(off.params.single { it.name == "action" }.description.contains("timer="))
        val on = AvaSelfTools.schema(listOf("read", "timer")).tools.single()
        assertTrue(on.params.any { it.name == "duration_s" })
        assertTrue(on.description.contains("action=timer"))
        assertTrue(on.description.contains("明天定时10秒钟"))
        assertTrue(on.description.contains("not a countdown"))
        assertTrue(on.description.contains("do not use entities or set"))
        assertTrue(on.description.contains("stop what=alarm silences whatever is ringing"))
        assertTrue(on.description.contains("how much is left"))
        assertTrue(on.description.contains("snapshot remaining_s"))
        assertTrue(on.params.single { it.name == "action" }.description.contains("timer="))
        assertTrue(on.params.single { it.name == "what" }.description.contains("whatever is ringing"))
    }

    @Test fun spokenCountdownIsNotTheBindSetting() {
        assertFalse(AvaPublishedEntities.prefersTimerSetting("倒计时"))
        assertFalse(AvaPublishedEntities.prefersTimerSetting("countdown"))
        assertFalse(AvaPublishedEntities.prefersTimerSetting("timer"))
        assertTrue(AvaPublishedEntities.prefersTimerSetting("timer entity"))
        assertTrue(AvaPublishedEntities.prefersTimerSetting("倒计时实体"))
        assertTrue(AvaPublishedEntities.prefersTimerSetting("绑定计时器"))
        val bind = AvaPublishedEntities.Item(
            id = "dream_clock_timer",
            name = "梦幻时钟倒计时实体",
            kind = "text",
            category = "config",
            state = null,
        )
        val display = AvaPublishedEntities.Item(
            id = "dream_clock_display",
            name = "梦幻时钟",
            kind = "switch",
            category = "none",
            state = "off",
        )
        val both = listOf(bind, display)
        assertEquals(listOf(display), AvaPublishedEntities.filterBindTimer("倒计时", both))
        assertTrue(AvaPublishedEntities.filterBindTimer("倒计时", listOf(bind)).isEmpty())
        assertEquals(both, AvaPublishedEntities.filterBindTimer("timer entity", both))
    }

    @Test fun displaySwitchDoesNotRewriteTheStateItAlreadyHas() {
        assertTrue(AvaPublishedEntities.displaySwitchUnchanged("browser_display", "on", true))
        assertTrue(AvaPublishedEntities.displaySwitchUnchanged("browser_display", "off", false))
        assertFalse(AvaPublishedEntities.displaySwitchUnchanged("browser_display", "off", true))
        assertFalse(AvaPublishedEntities.displaySwitchUnchanged("browser_display", "on", false))
        assertFalse(AvaPublishedEntities.displaySwitchUnchanged("dream_clock_display", "on", true))
    }

    @Test fun durationReadsSecondsOrHoursMinutes() {
        assertEquals(300_000L, AvaSelfTools.timerDurationMs(JSONObject().put("duration_s", 300)))
        assertEquals(150_000L, AvaSelfTools.timerDurationMs(JSONObject().put("minutes", 2).put("seconds", 30)))
        assertEquals(3_600_000L, AvaSelfTools.timerDurationMs(JSONObject().put("hours", 1)))
        assertNull(AvaSelfTools.timerDurationMs(JSONObject()))
        assertNull(AvaSelfTools.timerDurationMs(JSONObject().put("seconds", 0)))
    }

    @Test fun timerStartNeedsADurationAndPauseNeedsTheFlag() {
        val tools = AvaSelfTools.schema(listOf("timer"))
        val def = tools.tools.single()
        assertNull(AvaToolCallback.validate(def, JSONObject().put("action", "timer").put("duration_s", 60)))
        assertNull(AvaToolCallback.validate(def, JSONObject().put("action", "timer").put("pause", true)))
        assertNull(AvaToolCallback.validate(def, JSONObject().put("action", "timer").put("cancel", true)))
        assertNull(AvaToolCallback.validate(def, JSONObject().put("action", "timer")))
        assertNotNull(AvaToolCallback.validate(def, JSONObject().put("action", "timer").put("duration_s", 60).put("pause", true)))
    }
}
