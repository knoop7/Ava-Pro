package com.example.ava.localllm.remote

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Host records, separate from page text. A started write stays uncertain until a result arrives. */
internal class RemoteAiExecutionLedger(saved: JSONArray = JSONArray()) {
    private val entries = JSONArray(saved.toString())
    private val protectedWrites = HashMap<String, JSONObject>()

    init {
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val wire = entry.getJSONObject("wire")
            if (entry.optString("state") == "started" || wire.optBoolean("ok")) {
                protectedWrites[entry.getString("key")] = wire
            }
        }
    }

    fun hasUncertainWrites(): Boolean = (0 until entries.length()).any {
        val entry = entries.getJSONObject(it)
        entry.optString("state") == "started" || entry.getJSONObject("wire").optString("status") == "unknown"
    }

    fun snapshot(): JSONArray = JSONArray(entries.toString())
    fun hasCapacity(call: RemoteAiClient.Call): Boolean = !isWrite(call.name, call.arguments) || entries.length() < MAX_WRITES

    fun replay(call: RemoteAiClient.Call): AvaToolCallback.Result? {
        val wire = protectedWrites[signature(call)] ?: return null
        val details = wire.optJSONObject("result")?.let { JSONObject(it.toString()) } ?: JSONObject()
        details.put("already_recorded", true).put("retry_safe", false)
            .put("execution_note", "Recorded result from this task; no new command was sent. Do not repeat this write automatically.")
        return AvaToolCallback.ok(details, wire.optString("status", "unknown"))
    }

    fun begin(call: RemoteAiClient.Call): Int? {
        if (!isWrite(call.name, call.arguments)) return null
        check(entries.length() < MAX_WRITES)
        val index = entries.length()
        val args = call.arguments.toString()
        entries.put(JSONObject().put("key", signature(call)).put("tool", call.name).put("call_id", call.id)
            .put("arguments", if (args.length <= 4096) JSONObject(args) else JSONObject().put("preview", args.take(4096)).put("truncated", true))
            .put("state", "started").put("wire", uncertain().toJson()))
        return index
    }

    fun complete(index: Int?, result: AvaToolCallback.Result) {
        if (index == null) return
        val entry = entries.getJSONObject(index)
        val wire = result.toJson()
        if (wire.toString().length > 4096) {
            wire.put("result", JSONObject().put("summary", "Large result omitted; execution status retained."))
            if (wire.has("message")) wire.put("message", wire.optString("message").take(512))
            if (wire.has("error")) wire.put("error", wire.optString("error").take(128))
            wire.remove("details")
        }
        entry.put("state", "returned").put("wire", wire)
        // An unacknowledged write must not be retried even within the same turn.
        if (result.ok && result.status == "unknown") protectedWrites[entry.getString("key")] = wire
    }

    companion object {
        const val MAX_WRITES = 512

        fun uncertain(): AvaToolCallback.Result = AvaToolCallback.unknown(
            "Execution was started but no result was recorded. It may already have run. Inspect state or ask the user; do not retry automatically.")

        fun isWrite(name: String, args: JSONObject): Boolean = when (name.trim()) {
            "ha_guide", "ha_search", "ha_state", "ha_camera_snapshot", "ava_music_now", "ava_web_search", "ava_web_open", "ava_web_read", "ava_web_hide", "ava_page_read", "ava_turn" -> false
            "ava_self" -> when (val action = args.optString("action")) {
                "read", "entities" -> false
                "timer" -> args.has("duration_s") || args.has("hours") || args.has("minutes") ||
                    args.has("seconds") || args.has("pause") || args.has("cancel")
                else -> true
            }
            "ava_voice" -> args.optString("action") != "peers"
            "ava_phone" -> args.optString("action") !in setOf("status", "tree", "find")
            "ava_shell" -> args.optString("action") != "dump"
            "ava_web_act" -> args.optString("action") in setOf("click", "input", "paste")
            "ava_page_act" -> args.optString("action") in setOf("tap", "type", "key", "navigate", "back", "restore")
            else -> true
        }

        fun signature(call: RemoteAiClient.Call): String = MessageDigest.getInstance("SHA-256")
            .digest((call.name.trim() + canonical(call.arguments)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
            is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.opt(it)) }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
    }
}
