package com.example.ava.localllm.remote

import org.json.JSONArray
import org.json.JSONObject

/** Restricted transcripts never enter ordinary device-control history. Lookups do not consume state. */
internal class RemoteAiSessionMemory(private val now: () -> Long = System::currentTimeMillis) {
    data class Resume(
        val originalUser: String,
        val messages: JSONArray,
        val browserOnly: Boolean,
        val unfinished: Boolean,
        val ledger: JSONArray = JSONArray(),
        val awaitingContinuation: Boolean = unfinished,
    )
    private var saved: Resume? = null
    private var savedAt = 0L

    fun clear() { saved = null }

    fun resumeFor(text: String, historyEnabled: Boolean, browserEnabled: Boolean): Resume? {
        val previous = saved ?: return null
        if (now() - savedAt > 15 * 60_000L || previous.browserOnly && !browserEnabled) {
            clear()
            return null
        }
        if (previous.browserOnly && !historyEnabled) {
            clear()
            return null
        }
        if (!historyEnabled && !previous.unfinished) {
            clear()
            return null
        }
        val t = text.trim().lowercase().trimEnd('.', '!', '?', '。', '！', '？')
        if (CANCEL.containsMatchIn(t)) { clear(); return null }
        val intent = t.replace(Regex("^(?:好(?:的)?|可以)[,，\\s]*(?=继续|繼續|接着|接著)|^(?:yes|ok|okay)[,\\s]+(?=continue|resume)"), "")
        // A task the host had to stop resumes when the same request is said again.
        val sameRequest = previous.awaitingContinuation && compact(t) == compact(previous.originalUser) && compact(t).isNotEmpty()
        if (!sameRequest && !isFollowUp(intent, previous.browserOnly)) return null
        return previous.copy(messages = JSONArray(previous.messages.toString()), ledger = JSONArray(previous.ledger.toString()))
    }

    private fun compact(raw: String): String =
        raw.trim().lowercase().trimEnd('.', '!', '?', '。', '！', '？').replace(Regex("[\\s,，、。！？!?.]+"), "")

    fun remember(
        originalUser: String, messages: JSONArray, browserOnly: Boolean, unfinished: Boolean, historyEnabled: Boolean,
        ledger: JSONArray = JSONArray(), awaitingContinuation: Boolean = unfinished,
    ) {
        if (browserOnly && !historyEnabled) { clear(); return }
        if (!unfinished && !browserOnly && (!historyEnabled || ledger.length() == 0)) { clear(); return }
        val copy = if (messages.toString().length <= 64_000) JSONArray(messages.toString()) else compact(originalUser, ledger)
        saved = Resume(originalUser, copy, browserOnly, unfinished, JSONArray(ledger.toString()), awaitingContinuation)
        savedAt = now()
    }

    private fun compact(originalUser: String, ledger: JSONArray): JSONArray {
        val summary = JSONArray()
        var summaryChars = 0
        for (i in 0 until ledger.length()) {
            val entry = ledger.getJSONObject(i)
            val wire = entry.getJSONObject("wire")
            val row = JSONObject().put("step", i + 1).put("tool", entry.optString("tool").take(64))
                .put("status", wire.optString("status", "failed").take(24))
                .put("arguments_preview", entry.optJSONObject("arguments").toString().take(160))
                .put("key", entry.optString("key"))
            if (summaryChars + row.toString().length > 46_000) break
            summaryChars += row.toString().length
            summary.put(row)
        }
        return JSONArray().put(JSONObject().put("role", "user").put("content", originalUser.take(2048)))
            .put(JSONObject().put("role", "user").put("content",
                "Host note, not speech: execution ledger compacted. Do not repeat writes. " +
                    "Speak the user's language.\n$summary"))
    }

    private fun isFollowUp(t: String, browserOnly: Boolean): Boolean {
        if (browserOnly && DEVICE_REQUEST.containsMatchIn(t) && !Regex("网页|網頁|浏览器|瀏覽器|\\b(browser|page|website)\\b").containsMatchIn(t)) return false
        // Bare 继续 / continue is not a host keyword. HA already treats it as
        // media resume, and two characters is a real Chinese command.
        if (Regex("^(继续|繼續|接着|接著).+").containsMatchIn(t)) return true
        if (Regex("^(please\\s+)?(continue|resume)\\s+\\S").containsMatchIn(t)) return true
        if (!browserOnly) return false
        if (t in setOf("第一个", "第二个", "第三个", "上一个", "下一个", "往下", "往上", "粘贴", "貼上")) return true
        return Regex("刚才|剛才|(?:这个|那个|這個|那個)(?:网页|網頁|结果|結果|链接|連結)|第[一二三四五六七八九十0-9]+个(?:结果|链接|网页)").containsMatchIn(t) ||
            Regex("^(scroll|paste)\\b|\\b(first|second|third|next|previous)\\s+(result|link|page)\\b").containsMatchIn(t)
    }

    companion object {
        private val CANCEL = Regex("(?:不要|别|別|不用|不再|先不)(?:再)?(?:继续|繼續|接着|接著)|(?:取消|停止)(?:上个|上個|之前的)?(?:任务|任務|网页|網頁|继续|繼續)|\\b(?:do not|don't|stop|cancel|forget)\\s+(?:the\\s+)?(?:continu\\w*|brows\\w*|previous\\s+task|task)\\b")
        private val DEVICE_REQUEST = Regex("(?:开|開|关|關|调|調|锁|鎖).{0,12}(?:灯|燈|空调|空調|门锁|門鎖|音量)|\\b(play|pause|stop)\\s+.{0,20}(song|music|track)\\b|\\bturn\\b.{0,35}\\b(light|fan|switch|thermostat)\\b")
    }
}
