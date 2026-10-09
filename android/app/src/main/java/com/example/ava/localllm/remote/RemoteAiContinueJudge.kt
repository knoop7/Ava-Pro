package com.example.ava.localllm.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * 超级智能 per-turn continue judge for replies Home Assistant handled itself.
 *
 * While the HA reply plays, the voice model is asked one small question: should
 * the microphone stay open? No tools, no thinking, a tiny output cap. The answer
 * is read at TTS end; when it is late, failed or unreadable the keyword rule
 * decides alone. The judge can only end a session the keyword rule would keep:
 * a user sign-off or a reply that ends with a goodbye always ends it.
 *
 * Pure parsing and merging live here; the call is RemoteAiManager.judgeContinue.
 */
object RemoteAiContinueJudge {

    /** How long TTS end waits for a judge that has not answered yet. */
    const val JUDGE_GRACE_MS = 1_200L

    /** Output cap for the judge call; the answer is one short JSON object. */
    const val MAX_OUTPUT_TOKENS = 200

    /** Per-side character cap on what is sent, so a long HA answer stays cheap. */
    private const val MAX_TEXT_CHARS = 600

    const val SYSTEM_PROMPT =
        "You judge one turn of a hands-free voice conversation between a user and a smart-home voice assistant. " +
            "The assistant has already spoken its reply. Decide whether the microphone should stay open for the user's next sentence.\n" +
            "Answer true when the reply asks the user something, offers a next step or a choice, or the user is clearly in the middle of a task or a conversation.\n" +
            "Answer false when the user signals they are done (thanks, that's all, goodbye), or the request is fully finished and nothing in the reply invites an answer.\n" +
            "When unsure, answer true.\n" +
            "Reply with JSON only and nothing else: {\"continue\":true} or {\"continue\":false}"

    /** The single user message: this turn's two sides, trimmed. */
    fun userMessage(user: String, reply: String): String =
        "User said: ${clip(user).ifEmpty { "(nothing)" }}\nAssistant replied: ${clip(reply)}"

    fun messages(user: String, reply: String): JSONArray =
        JSONArray().put(JSONObject().put("role", "user").put("content", userMessage(user, reply)))

    private fun clip(text: String): String {
        val t = text.trim()
        return if (t.length <= MAX_TEXT_CHARS) t else t.take(MAX_TEXT_CHARS) + "…"
    }

    private val KEYS = listOf("continue", "keep_listening", "keepListening", "listen")
    private val YES = setOf("true", "yes", "continue", "y", "是", "继续")
    private val NO = setOf("false", "no", "stop", "end", "n", "否", "结束")

    /**
     * The model's answer as a decision, or null when it cannot be read.
     * Accepts `{"continue":true}` (also inside code fences or with text around it)
     * and a bare yes / no / true / false word.
     */
    fun parse(raw: String?): Boolean? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val open = text.indexOf('{')
        val close = text.lastIndexOf('}')
        if (open >= 0 && close > open) {
            val obj = runCatching { JSONObject(text.substring(open, close + 1)) }.getOrNull()
            if (obj != null) {
                for (key in KEYS) {
                    if (!obj.has(key)) continue
                    return word(obj.opt(key)?.toString())
                }
                return null
            }
        }
        return word(text)
    }

    private fun word(value: String?): Boolean? {
        if (value.isNullOrBlank()) return null
        val first = value.trim().lowercase()
            .replace("`", "")
            .split(Regex("[\\s\\p{P}]+"))
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return when (first) {
            in YES -> true
            in NO -> false
            else -> null
        }
    }

    /**
     * TTS-end decision. [keywordContinue] is the keyword rule (false after a user
     * sign-off or a reply ending in goodbye); it always wins when it says stop.
     * Otherwise the judge decides, and no judge means the keyword rule stands.
     * The turn cap is applied by the caller on top of this.
     */
    fun merge(judge: Boolean?, keywordContinue: Boolean): Boolean {
        if (!keywordContinue) return false
        return judge ?: keywordContinue
    }
}
