package com.example.ava.localllm.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaModStoreTest {

    private val catalog = listOf(
        AvaModStore.Row(
            id = "airplay-receiver",
            name = "AirPlay Receiver",
            version = "1.0.64",
            description = "Cast from iPhone or Mac.",
            group = "feature",
        ),
        AvaModStore.Row(
            id = "camera-stream-mod",
            name = "Camera Stream",
            version = "1.1.0",
            description = "Forward this device camera to Frigate.",
            group = "feature",
        ),
        AvaModStore.Row(
            id = "camera-vision-mod",
            name = "Camera Vision",
            version = "1.9.0",
            description = "On-device camera with QR and face detection.",
            group = "feature",
        ),
        AvaModStore.Row(
            id = "flashlight-mod",
            name = "Flashlight",
            version = "1.1.1",
            description = "Flip the torch on or off from Home Assistant.",
            installed = true,
            enabled = true,
            group = "feature",
        ),
        AvaModStore.Row(
            id = "echo-show-support",
            name = "Echo Show Support",
            version = "1.1.8",
            description = "Hardware compatibility for Echo Show.",
            group = "device",
            update = true,
            installed = true,
            enabled = true,
        ),
    )

    @Test fun spokenNameResolvesOneMod() {
        val hits = AvaModStore.resolve("帮我装 AirPlay", catalog)
        assertEquals(1, hits.size)
        assertEquals("airplay-receiver", hits.single().id)
        assertEquals("airplay-receiver", AvaModStore.resolve("airplay-receiver", catalog).single().id)
    }

    @Test fun cameraStaysAmbiguous() {
        val hits = AvaModStore.resolve("camera", catalog)
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.id == "camera-stream-mod" })
        assertTrue(hits.any { it.id == "camera-vision-mod" })
    }

    @Test fun descriptionDoesNotStealAMatch() {
        assertTrue(AvaModStore.resolve("Home Assistant", catalog).isEmpty())
        assertTrue(AvaModStore.resolve("Frigate", catalog).isEmpty())
    }

    @Test fun storeWordsAreAListNotAMod() {
        assertTrue(AvaModStore.isStoreAsk("模组"))
        assertTrue(AvaModStore.isStoreAsk("模组商店"))
        assertTrue(AvaModStore.isStoreAsk("打开模组商店"))
        assertTrue(AvaModStore.isStoreAsk("mod store"))
        assertTrue(AvaModStore.looksLikeCatalogAsk("有哪些模组"))
        assertTrue(AvaModStore.looksLikeCatalogAsk("show me mods"))
        assertTrue(AvaModStore.resolve("模组", catalog).isEmpty())
        assertTrue(AvaModStore.resolve("mods", catalog).isEmpty())
        assertTrue(AvaModStore.resolve("show me mods", catalog).isEmpty())
        assertFalse(AvaModStore.looksLikeCatalogAsk("安装 airplay 模组"))
        assertEquals("echo-show-support", AvaModStore.resolve("echo show", catalog).single().id)
    }

    @Test fun importAskIsTheFilePicker() {
        assertTrue(AvaModStore.isImportAsk("导入zip"))
        assertTrue(AvaModStore.isImportAsk("import zip"))
        assertTrue(AvaModStore.isImportAsk("本地安装"))
        assertTrue(AvaModStore.resolve("导入", catalog).isEmpty())
    }

    @Test fun listReceiptIsTextNotAScreenshot() {
        val body = AvaModStore.listBody(catalog)
        assertEquals("mods", body.getString("action"))
        assertEquals(5, body.getInt("count"))
        assertEquals(2, body.getInt("installed_count"))
        assertEquals(1, body.getInt("update_count"))
        assertFalse(body.has("screenshot"))
        assertTrue(body.getString("hint").contains("no screenshot"))
        assertTrue(body.getString("hint").contains("file picker"))
        assertTrue(body.getString("hint").contains("download icon"))
        val flashlight = (0 until body.getJSONArray("mods").length())
            .map { body.getJSONArray("mods").getJSONObject(it) }
            .first { it.getString("id") == "flashlight-mod" }
        assertEquals("on", flashlight.getString("state"))
        assertTrue(flashlight.getBoolean("writable"))
        assertEquals("ava_self", flashlight.getString("via"))
        assertFalse(flashlight.has("screenshot"))
    }

    @Test fun availableRowSaysHowToDownload() {
        val row = AvaModStore.rowJson(catalog.first(), detailed = true)
        assertEquals("available", row.getString("state"))
        assertFalse(row.getBoolean("installed"))
        assertTrue(row.getString("hint").contains("on=true"))
        assertTrue(row.getString("description").contains("Cast from iPhone"))
        assertFalse(row.has("screenshot"))
    }

    @Test fun schemaAcceptsModsListAndWrites() {
        val def = AvaSelfTools.schema(listOf("read", "settings", "mods", "set")).tools.single()
        assertTrue(def.params.single { it.name == "action" }.description.contains("mods="))
        assertNull(AvaToolCallback.validate(def, JSONObject().put("action", "mods")))
        assertNull(AvaToolCallback.validate(def, JSONObject().put("action", "mods").put("target", "airplay")))
        assertNull(
            AvaToolCallback.validate(
                def,
                JSONObject().put("action", "mods").put("target", "airplay").put("on", true),
            ),
        )
        assertNull(
            AvaToolCallback.validate(
                def,
                JSONObject().put("action", "mods").put("target", "flashlight-mod").put("press", true),
            ),
        )
        assertNotNull(
            AvaToolCallback.validate(
                def,
                JSONObject().put("action", "mods").put("on", true),
            ),
        )
    }

    @Test fun visibleTextCanLinkAModId() {
        val visible = JSONArray().put(
            JSONObject()
                .put("index", 0)
                .put("text", "AirPlay Receiver")
                .put("contentDescription", "")
                .put("clickable", true)
                .put("x", 10)
                .put("y", 20),
        )
        val mods = JSONArray().put(AvaModStore.rowJson(catalog.first()))
        AvaModStore.linkVisible(visible, mods)
        assertEquals("airplay-receiver", visible.getJSONObject(0).getString("point"))
    }
}
