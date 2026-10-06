package com.example.ava.localllm.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * SoL-Pi ObservationPack, voice-sized. The stored turn is never edited.
 * [view] copies the array for one provider request: a large successful
 * tool result is sent verbatim [FULL_SENDS] times, then replaced with a
 * head/tail receipt. A UI tree is packed after one send (indexes die).
 * Failures stay intact. A pack error returns the original.
 */
internal object RemoteAiObservationPack {

    const val FULL_SENDS = 2
    const val MIN_CHARS = 1_600
    const val HEAD = 256
    const val TAIL = 192

    fun view(messages: JSONArray, seen: MutableMap<String, Int>): JSONArray {
        val copy = runCatching { JSONArray(messages.toString()) }.getOrNull() ?: return messages
        return runCatching {
            projectLocked(copy, seen)
            copy
        }.getOrElse { messages }
    }

    internal fun stubOf(raw: String): String? {
        if (raw.length < MIN_CHARS || isPacked(raw)) return null
        val stub = treeStub(raw) ?: JSONObject()
            .put("packed", true)
            .put("chars", raw.length)
            .put("head", raw.take(HEAD))
            .put("tail", raw.takeLast(TAIL))
            .put(
                "hint",
                "Host packed this observation after it was sent twice. " +
                    "Re-call the same tool if you need more than the excerpt.",
            )
            .toString()
        return stub.takeIf { it.length < raw.length }
    }

    internal fun isUiTree(raw: String): Boolean = treeResult(raw) != null

    private fun treeResult(raw: String): JSONObject? {
        if (!raw.startsWith("{")) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.has("ok") && !json.optBoolean("ok")) return null
        val result = json.optJSONObject("result") ?: return null
        if (!result.has("nodes")) return null
        return result
    }

    private fun treeStub(raw: String): String? {
        val result = treeResult(raw) ?: return null
        val nodes = result.optJSONArray("nodes") ?: JSONArray()
        val labels = JSONArray()
        for (i in 0 until nodes.length()) {
            if (labels.length() >= 8) break
            val node = nodes.optJSONObject(i) ?: continue
            val text = node.optString("text").ifBlank { node.optString("contentDescription") }
            if (text.isNotBlank()) labels.put(text.take(40))
        }
        return JSONObject()
            .put("packed", true)
            .put("chars", raw.length)
            .put("shown", result.optInt("shown", result.optInt("count", nodes.length())))
            .put("nodeCount", result.optInt("nodeCount", nodes.length()))
            .put("labels", labels)
            .put(
                "hint",
                "Host packed this UI tree. Indexes from it are no longer valid. " +
                    "Find or tree again; do not reuse an old index.",
            )
            .toString()
    }

    internal fun isPacked(raw: String): Boolean =
        raw.startsWith("{") && runCatching { JSONObject(raw).optBoolean("packed") }.getOrDefault(false)

    private fun projectLocked(messages: JSONArray, seen: MutableMap<String, Int>) {
        for (i in 0 until messages.length()) {
            val content = messages.optJSONObject(i)?.opt("content") as? JSONArray ?: continue
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                if (block.optString("type") != "tool_result") continue
                val id = block.optString("tool_use_id")
                if (id.isEmpty()) continue
                val raw = block.opt("content") as? String ?: continue
                if (raw.length < MIN_CHARS || skip(raw)) continue
                val n = (seen[id] ?: 0) + 1
                seen[id] = n
                val cap = if (isUiTree(raw)) 1 else FULL_SENDS
                if (n <= cap) continue
                val stub = stubOf(raw) ?: continue
                block.put("content", stub)
            }
        }
    }

    private fun skip(raw: String): Boolean {
        if (!raw.startsWith("{")) return false
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        if (json.optBoolean("packed")) return true
        return json.has("ok") && !json.optBoolean("ok")
    }
}
