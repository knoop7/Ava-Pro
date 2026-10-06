package com.example.ava.localllm.remote

import com.example.ava.localllm.*
import kotlinx.coroutines.runBlocking
import com.example.ava.massapi.MassSearchItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteAiContractTest {
    private fun validate(tools: HaToolSet, name: String, json: String) =
        AvaToolCallback.validate(tools.tools.single { it.name == name }, JSONObject(json))

    @Test fun inputRequiresBothRefAndTextButAllowsAnExplicitEmptyText() {
        val tools = AvaBrowserTools.surface(true)
        assertNotNull(validate(tools, AvaBrowserTools.ACT, """{"action":"input","ref":"1:0"}"""))
        assertNull(validate(tools, AvaBrowserTools.ACT, """{"action":"input","ref":"1:0","text":""}"""))
        assertNotNull(validate(tools, AvaBrowserTools.ACT, """{"action":"click","ref":"1:0","text":"extra"}"""))
    }

    @Test fun openAndCopyRejectConflictingArguments() {
        val tools = AvaBrowserTools.surface(true)
        assertNotNull(validate(tools, AvaBrowserTools.OPEN, """{"n":1,"url":"https://example.com"}"""))
        assertNotNull(validate(tools, AvaBrowserTools.OPEN, "{}"))
        assertNull(validate(tools, AvaBrowserTools.OPEN, """{"n":100,"keep":false}"""))
        assertNotNull(validate(tools, AvaBrowserTools.ACT, """{"action":"copy","ref":"1:0","text":"extra"}"""))
    }

    @Test fun volumeAndSeekUseTheirOwnFields() {
        val tools = AvaMusicTools.surface(AvaMusicTools.Ready(true, true))
        assertNotNull(validate(tools, AvaMusicTools.VOLUME, """{"level":20,"step":10}"""))
        assertNotNull(validate(tools, AvaMusicTools.VOLUME, """{"mute":false}"""))
        assertNull(validate(tools, AvaMusicTools.VOLUME, """{"target":"tts","step":10}"""))
        assertNull(validate(tools, AvaMusicTools.VOLUME, """{"target":"device","level":40}"""))
        assertNotNull(validate(tools, AvaMusicTools.CONTROL, """{"action":"seek","offset_s":10}"""))
        assertNull(validate(tools, AvaMusicTools.CONTROL, """{"action":"seek","position_s":10}"""))
        assertTrue(AvaMusicTools.surface(AvaMusicTools.Ready(false, false)).tools.any { it.name == AvaMusicTools.VOLUME })
    }

    @Test fun brightnessRejectsSharedFieldsFromOtherActions() {
        val def = AvaSelfTools.schema(listOf("brightness")).tools.single()
        assertNotNull(AvaToolCallback.validate(def, JSONObject("""{"action":"brightness","value":"50"}""")))
        assertNotNull(AvaToolCallback.validate(def, JSONObject("""{"action":"brightness"}""")))
        assertNull(AvaToolCallback.validate(def, JSONObject("""{"action":"brightness","level":50}""")))
        assertNotNull(AvaToolCallback.validate(def, JSONObject("""{"action":"brightness","level":"50"}""")))
    }

    @Test fun haServiceDataIsAnObjectAndExtraTopLevelFieldsAreRejected() {
        val tools = RemoteAiHaTools.surface()
        assertNull(validate(tools, RemoteAiHaTools.CALL_SERVICE, """{"domain":"light","service":"turn_on","entity_id":"light.a","data":{"rgb_color":[1,2,3]}}"""))
        assertNotNull(validate(tools, RemoteAiHaTools.CALL_SERVICE, """{"domain":"light","service":"turn_on","entity_id":"light.a","color_name":"red"}"""))
        assertNotNull(validate(tools, RemoteAiHaTools.CALL_SERVICE, """{"domain":"light","service":"turn_on","entity_id":"light.a","data":"{}"}"""))
        assertNull(validate(tools, RemoteAiHaTools.CAMERA_SNAPSHOT, """{"entity_id":"门口摄像头"}"""))
        assertNotNull(validate(tools, RemoteAiHaTools.CAMERA_SNAPSHOT, "{}"))
        assertTrue(tools.tools.any { it.name == RemoteAiHaTools.CAMERA_SNAPSHOT })
    }

    @Test fun schemaContainsExecutableBranchConstraintsAndCallbackPreservesStatus() {
        val defs = JSONArray(AvaBrowserTools.surface(true).toJsonSchema())
        val open = (0 until defs.length()).map { defs.getJSONObject(it) }.single { it.getString("name") == AvaBrowserTools.OPEN }
        val schema = open.getJSONObject("parameters")
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(2, schema.getJSONArray("oneOf").getJSONObject(0).getJSONArray("oneOf").length())
        assertEquals("queued", AvaToolCallback.ok(JSONObject(), "queued").toJson().getString("status"))
        assertEquals("read_elements_again", AvaToolCallback.fail("stale_ref", "expired").toJson().getString("recovery"))
        assertEquals(
            "ask_enable_accessibility",
            AvaToolCallback.fail("need_accessibility", "enable Ava").toJson().getString("recovery"),
        )
        assertEquals(
            "ask_adb_grant",
            AvaToolCallback.fail("need_overlay", "overlay").toJson().getString("recovery"),
        )
    }

    @Test fun phoneLaunchRequiresTargetAndClickNeedsAnIndexOrQuery() {
        val tools = AvaPhoneTools.surface(true)
        assertNull(validate(tools, AvaPhoneTools.NAME, """{"action":"status"}"""))
        assertNull(validate(tools, AvaPhoneTools.NAME, """{"action":"enable"}"""))
        assertNotNull(validate(tools, AvaPhoneTools.NAME, """{"action":"launch"}"""))
        assertNull(validate(tools, AvaPhoneTools.NAME, """{"action":"launch","target":"WeChat"}"""))
        assertNotNull(validate(tools, AvaPhoneTools.NAME, """{"action":"click"}"""))
        assertNull(validate(tools, AvaPhoneTools.NAME, """{"action":"click","query":"Me"}"""))
        assertNull(validate(tools, AvaPhoneTools.NAME, """{"action":"click","index":0}"""))
        assertTrue(AvaPhoneTools.surface(false).isEmpty)
    }
    @Test fun malformedWriteNeverReachesExecutor() = runBlocking {
        var ran = false
        val result = AvaToolCallback.invoke(AvaSelfTools.schema(listOf("brightness")),
            AvaToolCallback.Call("1", AvaSelfTools.NAME, JSONObject("""{"action":"brightness","value":"50"}""")),
            AvaToolCallback.Context(null, "Ava", "zh", true)) { _, _ ->
            ran = true
            AvaToolCallback.ok()
        }
        assertFalse(ran)
        assertFalse(result.ok)
    }

    @Test fun queueAcknowledgementDoesNotClaimPlaybackStarted() {
        val hit = MassSearchItem(uri = "library://track/1", name = "Song", mediaType = "track")
        val result = AvaMusicTools.playbackResult(hit, "next").toJson()
        assertEquals("queued", result.getString("status"))
        assertTrue(result.getJSONObject("result").getBoolean("queued"))
        assertFalse(result.getJSONObject("result").getBoolean("playback_confirmed"))
        assertFalse(result.getJSONObject("result").has("started"))
    }

}
