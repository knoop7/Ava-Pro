package com.example.ava.localllm.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAiObservationPackTest {
    private val large = """{"ok":true,"status":"observed","result":{"text":"${"页".repeat(1_600)}"}}"""

    @Test fun storedMessagesAreNeverEdited() {
        val messages = messagesWith("call-1", large)
        val seen = HashMap<String, Int>()
        repeat(4) { RemoteAiObservationPack.view(messages, seen) }
        assertEquals(large, toolContent(messages, "call-1"))
    }

    @Test fun smallResultsStayUntouched() {
        val messages = messagesWith("call-1", """{"ok":true,"status":"accepted"}""")
        val seen = HashMap<String, Int>()
        val view = RemoteAiObservationPack.view(messages, seen)
        repeat(3) { RemoteAiObservationPack.view(messages, seen) }
        assertEquals("""{"ok":true,"status":"accepted"}""", toolContent(view, "call-1"))
    }

    @Test fun largeResultIsSentTwiceThenPacked() {
        val messages = messagesWith("call-1", large)
        val seen = HashMap<String, Int>()
        val first = RemoteAiObservationPack.view(messages, seen)
        val second = RemoteAiObservationPack.view(messages, seen)
        assertEquals(large, toolContent(first, "call-1"))
        assertEquals(large, toolContent(second, "call-1"))
        val packed = JSONObject(toolContent(RemoteAiObservationPack.view(messages, seen), "call-1"))
        assertTrue(packed.getBoolean("packed"))
        assertEquals(large.length, packed.getInt("chars"))
        assertEquals(large.take(RemoteAiObservationPack.HEAD), packed.getString("head"))
        assertEquals(large.takeLast(RemoteAiObservationPack.TAIL), packed.getString("tail"))
        assertEquals(large, toolContent(messages, "call-1"))
    }

    @Test fun failuresAreNeverPacked() {
        val fail = """{"ok":false,"error":"tool_error","message":"${"x".repeat(1_600)}"}"""
        val messages = messagesWith("call-1", fail)
        val seen = HashMap<String, Int>()
        repeat(4) { RemoteAiObservationPack.view(messages, seen) }
        assertEquals(fail, toolContent(RemoteAiObservationPack.view(messages, seen), "call-1"))
    }

    @Test fun largeUiTreeIsPackedAfterOneSend() {
        val tree = largeTree()
        val messages = messagesWith("call-1", tree)
        val seen = HashMap<String, Int>()
        assertEquals(tree, toolContent(RemoteAiObservationPack.view(messages, seen), "call-1"))
        val packed = JSONObject(toolContent(RemoteAiObservationPack.view(messages, seen), "call-1"))
        assertTrue(packed.getBoolean("packed"))
        assertTrue(packed.getString("hint").contains("do not reuse an old index"))
        assertTrue(packed.has("labels"))
        assertFalse(packed.has("head"))
        assertEquals(tree, toolContent(messages, "call-1"))
    }

    @Test fun stubNeverLargerThanTheSource() {
        val stub = RemoteAiObservationPack.stubOf(large)
        assertTrue(stub != null && stub.length < large.length)
        assertFalse(RemoteAiObservationPack.isPacked(large))
        assertTrue(RemoteAiObservationPack.isPacked(stub!!))
    }

    private fun largeTree(): String {
        val nodes = JSONArray()
        repeat(40) { i ->
            nodes.put(JSONObject().put("index", i).put("text", "节点".repeat(20)).put("clickable", true))
        }
        return JSONObject()
            .put("ok", true)
            .put("status", "observed")
            .put("result", JSONObject().put("shown", 40).put("nodeCount", 40).put("nodes", nodes))
            .toString()
    }

    private fun messagesWith(id: String, content: String): JSONArray =
        JSONArray().put(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray().put(
                    JSONObject().put("type", "tool_result").put("tool_use_id", id).put("content", content),
                ),
            ),
        )

    private fun toolContent(messages: JSONArray, id: String): String {
        val content = messages.getJSONObject(0).getJSONArray("content")
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("tool_use_id") == id) return block.getString("content")
        }
        throw AssertionError("missing $id")
    }
}
