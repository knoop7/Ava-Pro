package com.example.ava.webcompat

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Page memory probe + reload policy for the self-healing browser ladder.
 *
 * HA's frontend has documented leaks (icon `until()` chains — frontend PR #52743,
 * history-graph dialogs — issue #25888, card-mod / browser_mod) that grow until the
 * renderer dies. On 32-bit engines that is an address-space abort at ~240MB PSS,
 * visible as a crash-and-rebuild flicker loop. Kiosk browsers solve this with blind
 * scheduled reloads; this guard instead measures the JS heap and reloads only when
 * genuinely needed, preferring hidden/covered moments so the user never sees it.
 */
object BrowserMemoryGuard {

    /**
     * Returns compact JSON `{"u":usedHeap,"l":heapLimit,"n":lightDomNodes}`.
     * `performance.memory` is Chromium-only (present since ancient versions, so
     * WebView 83 works); Gecko reports zeros and falls back to the node backstop.
     */
    val probeJs: String = """
        (function() {
          try {
            var m = (window.performance && performance.memory) ? performance.memory : null;
            var u = (m && m.usedJSHeapSize) ? m.usedJSHeapSize : 0;
            var l = (m && m.jsHeapSizeLimit) ? m.jsHeapSizeLimit : 0;
            var n = 0;
            try { n = document.getElementsByTagName('*').length; } catch (e) {}
            return '{"u":' + u + ',"l":' + l + ',"n":' + n + '}';
          } catch (e) { return '{}'; }
        })();
    """.trimIndent()

    const val SAMPLE_INTERVAL_MS = 60_000L

    /** used/limit that arms an opportunistic reload at the next hidden/covered sample. */
    const val ELEVATED_RATIO = 0.75

    /** used/limit that justifies reloading even while visible (better than a crash). */
    const val CRITICAL_RATIO = 0.90

    /** Light-DOM node backstops for engines without performance.memory. */
    const val ELEVATED_NODES = 45_000
    const val CRITICAL_NODES = 80_000

    /** Consecutive critical samples required before a visible reload is allowed. */
    const val CRITICAL_STREAK_FOR_VISIBLE_RELOAD = 3

    /** Hidden/covered reloads still wait for a confirming second sample. */
    const val ELEVATED_STREAK_FOR_HIDDEN_RELOAD = 2

    /** Never reload twice within this window, whatever the readings say. */
    const val RELOAD_COOLDOWN_MS = 10 * 60_000L

    data class Sample(val used: Long, val limit: Long, val nodes: Int) {
        val ratio: Double get() = if (limit > 0) used.toDouble() / limit else 0.0
        // Node counts are a BACKSTOP for engines without performance.memory (Gecko).
        // When the heap ratio is available it is authoritative: a busy Lovelace
        // dashboard legitimately exceeds the node thresholds, and letting nodes
        // override a healthy heap made the guard reload on every hidden moment.
        val elevated: Boolean
            get() = if (limit > 0) ratio >= ELEVATED_RATIO else nodes >= ELEVATED_NODES
        val critical: Boolean
            get() = if (limit > 0) ratio >= CRITICAL_RATIO else nodes >= CRITICAL_NODES

        override fun toString(): String {
            val usedMb = used / (1024 * 1024)
            val limitMb = limit / (1024 * 1024)
            return "heap=${usedMb}MB/${limitMb}MB (${(ratio * 100).toInt()}%) nodes=$nodes"
        }
    }

    /**
     * Parses an evaluateJavascript result. WebView returns the value JSON-encoded
     * (a quoted string); the Gecko console bridge returns it raw. `null`/blank/
     * malformed results (probe raced a navigation) yield null.
     */
    fun parse(result: String?): Sample? {
        val raw = result?.trim().orEmpty()
        if (raw.isEmpty() || raw == "null") return null
        return try {
            val text = if (raw.startsWith("\"")) {
                JSONTokener(raw).nextValue() as? String ?: return null
            } else {
                raw
            }
            val obj = JSONObject(text)
            if (!obj.has("u")) return null
            Sample(obj.optLong("u"), obj.optLong("l"), obj.optInt("n"))
        } catch (e: Exception) {
            null
        }
    }
}
