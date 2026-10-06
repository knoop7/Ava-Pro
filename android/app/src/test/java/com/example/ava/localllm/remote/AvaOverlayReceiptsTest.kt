package com.example.ava.localllm.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaOverlayReceiptsTest {

    @Test fun catalogCoversEveryVoiceOverlay() {
        val ids = AvaOverlayReceipts.ALL.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf(
            "browser_display",
            "dream_clock_display",
            "simple_clock_display",
            "screensaver_display",
            "weather_display",
            "voice_message_display",
            "vinyl_cover_display",
            "quick_entity_display",
            AvaOverlayReceipts.RESEARCH,
            AvaOverlayReceipts.APP_WINDOW,
        )))
        assertEquals(10, AvaOverlayReceipts.ALL.size)
        assertEquals(AvaOverlayReceipts.ALL.size, AvaOverlayReceipts.ALL.distinctBy { it.kind }.size)
    }

    @Test fun kindsMapFromEntityIds() {
        assertEquals("ha_browser", AvaOverlayReceipts.kind("browser_display"))
        assertEquals("dream_clock", AvaOverlayReceipts.kind("dream_clock_display"))
        assertEquals("simple_clock", AvaOverlayReceipts.kind("simple_clock_display"))
        assertEquals("screensaver", AvaOverlayReceipts.kind("screensaver_display"))
        assertEquals("weather", AvaOverlayReceipts.kind("weather_display"))
        assertEquals("voice_message", AvaOverlayReceipts.kind("voice_message_display"))
        assertEquals("vinyl", AvaOverlayReceipts.kind("vinyl_cover_display"))
        assertEquals("quick_entity", AvaOverlayReceipts.kind("quick_entity_display"))
        assertEquals("research", AvaOverlayReceipts.kind(AvaOverlayReceipts.RESEARCH))
        assertEquals("app_window", AvaOverlayReceipts.kind(AvaOverlayReceipts.APP_WINDOW))
        assertNull(AvaOverlayReceipts.kind("restart_service"))
        assertFalse(AvaOverlayReceipts.isOverlayTarget("manual_wake"))
        assertFalse(AvaOverlayReceipts.isOverlayTarget("tts_volume"))
    }

    @Test fun attachPutsCallbackEvenWhenWindowIsDown() {
        val body = JSONObject().put("on", true)
        AvaOverlayReceipts.attach(body, "weather_display", true)
        val overlays = body.getJSONArray("opened_overlays")
        assertTrue(overlays.length() >= 1)
        val first = overlays.getJSONObject(0)
        assertEquals("weather", first.getString("kind"))
        assertEquals("weather_display", first.getString("id"))
        assertFalse(first.getBoolean("visible"))
        assertFalse(body.getBoolean("overlay_opened"))
        assertTrue(body.getString("hint").contains("opened_overlays"))
        assertFalse(body.getString("hint").contains("screenshot"))
    }

    @Test fun hideReceiptMarksClosedWhenWindowIsDown() {
        val body = JSONObject().put("on", false)
        AvaOverlayReceipts.attach(body, "browser_display", false)
        assertFalse(body.getBoolean("overlay_opened"))
        assertTrue(body.getBoolean("overlay_closed"))
        assertEquals("ha_browser", body.getJSONArray("opened_overlays").getJSONObject(0).getString("kind"))
    }

    @Test fun unknownIdDoesNotInventARow() {
        val body = JSONObject()
        AvaOverlayReceipts.attach(body, "restart_service", true)
        assertFalse(body.has("opened_overlays"))
        assertNotNull(AvaOverlayReceipts.spec("dream_clock_display"))
    }
}
