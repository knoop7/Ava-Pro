package com.example.ava.localllm.remote

import com.example.ava.homeassistant.entity.HaEntityLiveState
import com.example.ava.homeassistant.entity.HaEntitySummary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteAiExecutionLedgerTest {
    private val call = RemoteAiClient.Call("1", "ha_toggle", JSONObject().put("entity_id", "light.desk"))

    @Test fun noAckIsUnknownAndIsNotRetried() {
        val entity = HaEntitySummary("light.desk", "Desk", "light", "off")
        val result = RemoteAiHaTools.serviceResult(null, "light.toggle", entity)
        assertTrue(result.ok)
        assertEquals("unknown", result.status)
        assertFalse(result.toJson().getJSONObject("result").getBoolean("retry_safe"))
        assertFalse(RemoteAiHaTools.serviceResult(false, "light.toggle", entity).ok)
        assertEquals("accepted", RemoteAiHaTools.serviceResult(true, "light.toggle", entity).status)
        val live = HaEntityLiveState.fromRest(
            JSONObject("""{"entity_id":"light.desk","state":"on","attributes":{"brightness":128,"supported_features":44}}"""),
        )!!
        val spoken = RemoteAiHaTools.serviceResult(true, "light.turn_on", entity, live).toJson().getJSONObject("result")
        assertEquals(50, spoken.getInt("brightness_pct"))
        assertEquals("on", spoken.getString("state"))
        assertFalse(spoken.has("supported_features"))
        assertFalse(spoken.has("attributes"))
        val ledger = RemoteAiExecutionLedger()
        val index = ledger.begin(call)
        ledger.complete(index, result)
        assertEquals("unknown", ledger.replay(call)?.status)
    }

    @Test fun inFlightAndCompletedWritesSurviveRestoreWithNewCallIds() {
        val ledger = RemoteAiExecutionLedger()
        val index = ledger.begin(call)
        val interrupted = RemoteAiExecutionLedger(ledger.snapshot())
        assertEquals("unknown", interrupted.replay(call.copy(id = "new"))?.status)
        ledger.complete(index, AvaToolCallback.ok(JSONObject(), "applied"))
        val restored = RemoteAiExecutionLedger(ledger.snapshot())
        assertEquals("applied", restored.replay(call.copy(id = "another"))?.status)
        assertTrue(restored.replay(call)!!.toJson().getJSONObject("result").getBoolean("already_recorded"))
    }

    @Test fun compactionRetainsEveryWriteAndItsReplayProtection() {
        val ledger = RemoteAiExecutionLedger()
        repeat(RemoteAiExecutionLedger.MAX_WRITES) { i ->
            val write = call.copy(arguments = JSONObject().put("entity_id", "light.$i").put("text", "x".repeat(4500)))
            ledger.complete(ledger.begin(write), AvaToolCallback.ok(JSONObject(), "accepted"))
        }
        val memory = RemoteAiSessionMemory()
        memory.remember("Complete task", JSONArray().put(JSONObject().put("role", "assistant").put("content", "page".repeat(20000))),
            true, true, true, ledger.snapshot())
        val resume = memory.resumeFor("continue", true, true)!!
        assertEquals(RemoteAiExecutionLedger.MAX_WRITES, resume.ledger.length())
        assertTrue(resume.messages.toString().length <= 64_000)
        assertTrue(resume.messages.toString().contains("execution ledger compacted"))
        val restored = RemoteAiExecutionLedger(resume.ledger)
        val old = call.copy(arguments = JSONObject().put("text", "x".repeat(4500)).put("entity_id", "light.0"))
        assertNotNull(restored.replay(old))
        assertFalse(restored.hasCapacity(call))
    }

    @Test fun phoneStatusIsAReadAndClickIsAWrite() {
        assertFalse(RemoteAiExecutionLedger.isWrite("ava_phone", JSONObject().put("action", "status")))
        assertFalse(RemoteAiExecutionLedger.isWrite("ava_phone", JSONObject().put("action", "tree")))
        assertFalse(RemoteAiExecutionLedger.isWrite("ava_phone", JSONObject().put("action", "find")))
        assertTrue(RemoteAiExecutionLedger.isWrite("ava_phone", JSONObject().put("action", "enable")))
        assertTrue(RemoteAiExecutionLedger.isWrite("ava_phone", JSONObject().put("action", "launch")))
        assertTrue(RemoteAiExecutionLedger.isWrite("ava_phone", JSONObject().put("action", "click")))
        assertFalse(RemoteAiExecutionLedger.isWrite("ha_camera_snapshot", JSONObject().put("entity_id", "camera.gate")))
        assertFalse(RemoteAiExecutionLedger.isWrite("ava_page_read", JSONObject().put("mode", "page")))
        assertFalse(RemoteAiExecutionLedger.isWrite("ava_page_act", JSONObject().put("action", "scroll")))
        assertFalse(RemoteAiExecutionLedger.isWrite("ava_page_act", JSONObject().put("action", "text")))
        assertFalse(RemoteAiExecutionLedger.isWrite("ava_page_act", JSONObject().put("action", "search")))
        assertFalse(RemoteAiExecutionLedger.isWrite("ava_page_act", JSONObject().put("action", "exec_js")))
        assertTrue(RemoteAiExecutionLedger.isWrite("ava_page_act", JSONObject().put("action", "navigate")))
        assertTrue(RemoteAiExecutionLedger.isWrite("ava_page_act", JSONObject().put("action", "tap")))
        assertTrue(RemoteAiExecutionLedger.isWrite("ava_page_act", JSONObject().put("action", "back")))
        assertTrue(RemoteAiExecutionLedger.isWrite("ava_page_act", JSONObject().put("action", "restore")))
        assertFalse(RemoteAiExecutionLedger.isWrite("ava_shell", JSONObject().put("action", "dump")))
        assertTrue(RemoteAiExecutionLedger.isWrite("ava_shell", JSONObject().put("action", "tap")))
        assertTrue(RemoteAiExecutionLedger.isWrite("ava_shell", JSONObject().put("action", "swipe")))
        assertTrue(RemoteAiExecutionLedger.isWrite("ava_shell", JSONObject().put("action", "key")))
        assertFalse(RemoteAiExecutionLedger.isWrite("ava_self", JSONObject().put("action", "timer")))
        assertTrue(RemoteAiExecutionLedger.isWrite("ava_self", JSONObject().put("action", "timer").put("duration_s", 60)))
        assertTrue(RemoteAiExecutionLedger.isWrite("ava_self", JSONObject().put("action", "timer").put("cancel", true)))
    }
}
