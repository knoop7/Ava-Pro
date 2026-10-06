package com.example.ava.localllm.remote

import com.example.ava.R
import com.example.ava.ui.Screen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaSettingsLiveTest {

    @Test fun talkingPageUsesTheCatalogTitle() {
        assertEquals(
            R.string.settings_voice_tts_entry_title,
            AvaSettingsLive.titleRes(Screen.SETTINGS_VOICE_TTS),
        )
        assertEquals(
            R.string.settings_voice_microphone_entry_title,
            AvaSettingsLive.titleRes(Screen.SETTINGS_VOICE_MICROPHONE),
        )
    }

    @Test fun liveNamePrefersTheOnScreenString() {
        assertEquals("TTS 音量", AvaSettingsLive.pickName("TTS 音量", "TTS volume", null, "tts_volume"))
        assertEquals("TTS volume", AvaSettingsLive.pickName(null, "TTS volume", null, "tts_volume"))
        assertEquals("tts volume", AvaSettingsLive.pickName(null, null, null, "tts_volume"))
    }

    @Test fun visibleKeepsSwitchChecked() {
        val tree = JSONObject().put(
            "nodes",
            JSONArray().put(
                node(0, "Enable Idle Screensaver", clickable = true, x = 80, y = 200)
                    .put("checkable", true)
                    .put("checked", true),
            ),
        )
        val visible = AvaSettingsLive.compactVisible(tree)!!
        val first = visible.getJSONObject(0)
        assertTrue(first.getBoolean("checkable"))
        assertTrue(first.getBoolean("checked"))
    }

    @Test fun visibleKeepsTextAndTapCenterAndSkipsPassword() {
        val tree = JSONObject().put(
            "nodes",
            JSONArray()
                .put(node(0, "TTS volume", clickable = true, x = 120, y = 340))
                .put(node(1, "secret", password = true, x = 10, y = 10))
                .put(node(2, "", clickable = true, x = 400, y = 800)),
        )
        val visible = AvaSettingsLive.compactVisible(tree)!!
        assertEquals(2, visible.length())
        val first = visible.getJSONObject(0)
        assertEquals("TTS volume", first.getString("text"))
        assertEquals(120, first.getInt("x"))
        assertEquals(340, first.getInt("y"))
        assertTrue(first.getBoolean("clickable"))
        assertFalse(first.has("screenshot"))
        assertEquals(2, visible.getJSONObject(1).getInt("index"))
    }

    @Test fun visiblePrefersLabeledRowsWhenCapped() {
        val nodes = JSONArray()
        repeat(5) { i -> nodes.put(node(i, "", clickable = true, x = i, y = i)) }
        nodes.put(node(9, "Talking", clickable = true, x = 50, y = 60))
        val visible = AvaSettingsLive.compactVisible(JSONObject().put("nodes", nodes), cap = 2)!!
        assertEquals(2, visible.length())
        assertEquals("Talking", visible.getJSONObject(0).getString("text"))
        assertEquals(0, visible.getJSONObject(1).getInt("index"))
    }

    @Test fun visibleTextLinksBackToAPointId() {
        val visible = JSONArray().put(node(0, "TTS volume", clickable = true, x = 1, y = 2))
        val compact = AvaSettingsLive.compactVisible(JSONObject().put("nodes", visible))!!
        val points = JSONArray().put(
            AvaSettingsLive.pointRow("tts_volume", "TTS volume", "number", "40", null, true),
        )
        AvaSettingsLive.linkPoints(compact, points)
        assertEquals("tts_volume", compact.getJSONObject(0).getString("point"))
    }

    @Test fun missingTreeIsNotAScreenshot() {
        assertNull(AvaSettingsLive.compactVisible(null))
        assertNull(AvaSettingsLive.compactVisible(JSONObject().put("nodes", JSONArray())))
    }

    private fun node(
        index: Int,
        text: String,
        clickable: Boolean = false,
        password: Boolean = false,
        x: Int = 0,
        y: Int = 0,
    ) = JSONObject()
        .put("index", index)
        .put("text", text)
        .put("contentDescription", "")
        .put("clickable", clickable)
        .put("editable", false)
        .put("scrollable", false)
        .put("password", password)
        .put(
            "bounds",
            JSONObject()
                .put("left", x - 1)
                .put("top", y - 1)
                .put("right", x + 1)
                .put("bottom", y + 1)
                .put("centerX", x)
                .put("centerY", y),
        )
}
