package com.example.ava.localllm.remote

import com.example.ava.ui.Screen
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaUiHereTest {

    @After
    fun reset() {
        AvaUiHere.overrideForTest(null)
    }

    @Test
    fun emptyStackIsSystem() {
        val ui = AvaUiHere.build(AvaUiHere.Snapshot())
        assertEquals(AvaUiHere.SYSTEM, ui.getString("top"))
        assertEquals("ava_phone", ui.getString("use"))
        assertTrue(ui.getString("hint").contains("here_ui"))
        assertFalse(ui.getString("hint").contains("screenshot"))
    }

    @Test
    fun avaHomeWhenResumed() {
        val ui = AvaUiHere.build(AvaUiHere.Snapshot(avaResumed = true, avaRoute = Screen.HOME))
        assertEquals(AvaUiHere.AVA, ui.getString("top"))
        assertEquals("home", ui.getString("kind"))
        assertEquals(Screen.HOME, ui.getString("path"))
        assertEquals("ava_self", ui.getString("use"))
        assertFalse(ui.has("under"))
    }

    @Test
    fun avaSettingsPathWhenInSettings() {
        val ui = AvaUiHere.build(
            AvaUiHere.Snapshot(avaResumed = true, avaRoute = Screen.SETTINGS_VOICE_TTS),
        )
        assertEquals(AvaUiHere.AVA, ui.getString("top"))
        assertEquals("settings", ui.getString("kind"))
        assertEquals(Screen.SETTINGS_VOICE_TTS, ui.getString("path"))
    }

    @Test
    fun hassCoversAvaSettings() {
        val ui = AvaUiHere.build(
            AvaUiHere.Snapshot(
                hassVisible = true,
                hassPath = "https://ha.local/lovelace/home",
                avaResumed = true,
                avaRoute = Screen.SETTINGS_VOICE_TTS,
            ),
        )
        assertEquals(AvaUiHere.HASS, ui.getString("top"))
        assertEquals("ha_browser", ui.getString("kind"))
        assertEquals("ava_page", ui.getString("use"))
        assertEquals(AvaUiHere.AVA, ui.getString("under"))
        assertEquals(Screen.SETTINGS_VOICE_TTS, ui.getString("under_path"))
        assertTrue(openSurfaces(ui).containsAll(listOf(AvaUiHere.HASS, AvaUiHere.AVA)))
    }

    @Test
    fun dreamClockCoversHass() {
        val ui = AvaUiHere.build(
            AvaUiHere.Snapshot(
                visibleIds = setOf("dream_clock_display"),
                hassVisible = true,
                hassPath = "https://ha.local/lovelace/home",
                avaResumed = true,
                avaRoute = Screen.HOME,
            ),
        )
        assertEquals(AvaUiHere.OVERLAY, ui.getString("top"))
        assertEquals("dream_clock", ui.getString("kind"))
        assertEquals("ava_self", ui.getString("use"))
        assertEquals(AvaUiHere.HASS, ui.getString("under"))
    }

    @Test
    fun weatherPlateDoesNotStealHass() {
        val ui = AvaUiHere.build(
            AvaUiHere.Snapshot(
                visibleIds = setOf("weather_display"),
                hassVisible = true,
                hassPath = "/lovelace/home",
                avaResumed = true,
                avaRoute = Screen.HOME,
            ),
        )
        assertEquals(AvaUiHere.HASS, ui.getString("top"))
        assertEquals("ava_page", ui.getString("use"))
        assertTrue(openKinds(ui).contains("weather"))
    }

    @Test
    fun weatherAloneIsOverlay() {
        val ui = AvaUiHere.build(
            AvaUiHere.Snapshot(visibleIds = setOf("weather_display"), avaResumed = true, avaRoute = Screen.HOME),
        )
        assertEquals(AvaUiHere.OVERLAY, ui.getString("top"))
        assertEquals("weather", ui.getString("kind"))
        assertEquals("ava_self", ui.getString("use"))
        assertEquals(AvaUiHere.AVA, ui.getString("under"))
    }

    @Test
    fun appWindowBeatsHass() {
        val ui = AvaUiHere.build(
            AvaUiHere.Snapshot(
                visibleIds = setOf(AvaOverlayReceipts.APP_WINDOW),
                hassVisible = true,
                avaResumed = true,
                avaRoute = Screen.HOME,
            ),
        )
        assertEquals(AvaUiHere.OVERLAY, ui.getString("top"))
        assertEquals("app_window", ui.getString("kind"))
        assertEquals("ava_shell", ui.getString("use"))
    }

    @Test
    fun researchUsesWebTools() {
        val ui = AvaUiHere.build(
            AvaUiHere.Snapshot(visibleIds = setOf(AvaOverlayReceipts.RESEARCH), avaResumed = true),
        )
        assertEquals(AvaUiHere.OVERLAY, ui.getString("top"))
        assertEquals("research", ui.getString("kind"))
        assertEquals("ava_web", ui.getString("use"))
    }

    @Test
    fun vinylFabDoesNotCoverHass() {
        val ui = AvaUiHere.build(
            AvaUiHere.Snapshot(
                visibleIds = setOf("vinyl_cover_display"),
                vinylFull = false,
                hassVisible = true,
            ),
        )
        assertEquals(AvaUiHere.HASS, ui.getString("top"))
        assertTrue(openKinds(ui).contains("vinyl"))
    }

    @Test
    fun vinylFullBeatsDreamClock() {
        val ui = AvaUiHere.build(
            AvaUiHere.Snapshot(
                visibleIds = setOf("vinyl_cover_display", "dream_clock_display"),
                vinylFull = true,
                hassVisible = true,
            ),
        )
        assertEquals("vinyl", ui.getString("kind"))
    }

    @Test
    fun vinylFullCoversHass() {
        val ui = AvaUiHere.build(
            AvaUiHere.Snapshot(
                visibleIds = setOf("vinyl_cover_display"),
                vinylFull = true,
                hassVisible = true,
            ),
        )
        assertEquals(AvaUiHere.OVERLAY, ui.getString("top"))
        assertEquals("vinyl", ui.getString("kind"))
    }

    @Test
    fun systemKeepsPackage() {
        val ui = AvaUiHere.build(AvaUiHere.Snapshot(systemPackage = "com.android.settings"))
        assertEquals(AvaUiHere.SYSTEM, ui.getString("top"))
        assertEquals("com.android.settings", ui.getString("package"))
        assertEquals("ava_phone", ui.getString("use"))
    }

    @Test
    fun attachAndSessionLineCarryTop() {
        AvaUiHere.overrideForTest(
            AvaUiHere.Snapshot(hassVisible = true, hassPath = "/config/dashboard"),
        )
        val body = AvaUiHere.attach(JSONObject().put("ok", true))
        val here = body.getJSONObject("here_ui")
        assertEquals(AvaUiHere.HASS, here.getString("top"))
        assertEquals("ava_page", here.getString("use"))
        val line = AvaUiHere.sessionLine()
        assertTrue(line.contains("top=hass"))
        assertTrue(line.contains("use=ava_page"))
        assertTrue(line.contains("Operate this layer"))
    }

    @Test
    fun extractPrefersOnScreenText() {
        val here = AvaUiHere.build(AvaUiHere.Snapshot(systemPackage = "com.tencent.mm"))
        val tree = JSONObject()
            .put(
                "nodes",
                org.json.JSONArray()
                    .put(JSONObject().put("text", "微信").put("packageName", "com.tencent.mm"))
                    .put(JSONObject().put("text", "通讯录").put("packageName", "com.tencent.mm")),
            )
        val ui = AvaUiHere.extractFrom(here, tree, mapOf("com.tencent.mm" to "WeChat"), emptyList())
        assertEquals("text", ui.getString("seen_via"))
        assertEquals("WeChat", ui.getString("name"))
        assertEquals("微信", ui.getJSONArray("texts").getString(0))
        assertFalse(ui.has("tree"))
        assertFalse(ui.getString("hint").contains("screenshot"))
    }

    @Test
    fun extractFallsBackToWindowTreeWhenNoText() {
        val here = AvaUiHere.build(AvaUiHere.Snapshot(systemPackage = "com.android.settings"))
        val tree = JSONObject()
            .put(
                "windows",
                org.json.JSONArray().put(
                    JSONObject().put("title", "Accessibility").put("focused", true).put("layer", 2),
                ),
            )
            .put("nodes", org.json.JSONArray())
        val ui = AvaUiHere.extractFrom(
            here,
            tree,
            mapOf("com.android.settings" to "Settings"),
            listOf("com.android.settings"),
        )
        assertEquals("tree", ui.getString("seen_via"))
        assertEquals("Settings", ui.getJSONArray("tree").getJSONObject(0).getString("name"))
        assertFalse(ui.has("texts"))
    }

    @Test
    fun attachDoesNotWipeAnExtract() {
        val extracted = JSONObject().put("here_ui", JSONObject().put("top", "system").put("seen_via", "text"))
        val again = AvaUiHere.attach(extracted)
        assertEquals("text", again.getJSONObject("here_ui").getString("seen_via"))
    }

    @Test
    fun attachResultPutsHereOnFailDetails() {
        AvaUiHere.overrideForTest(AvaUiHere.Snapshot(avaResumed = true, avaRoute = Screen.HOME))
        val failed = AvaUiHere.attachResult(AvaToolCallback.fail("invalid_request", "wrong family"))
        val details = failed.error!!.details!!
        assertEquals(AvaUiHere.AVA, details.getJSONObject("here_ui").getString("top"))
    }

    private fun openSurfaces(ui: JSONObject): List<String> {
        val open = ui.getJSONArray("open")
        return (0 until open.length()).map { open.getJSONObject(it).getString("surface") }
    }

    private fun openKinds(ui: JSONObject): List<String> {
        val open = ui.getJSONArray("open")
        return (0 until open.length()).map { open.getJSONObject(it).getString("kind") }
    }
}
