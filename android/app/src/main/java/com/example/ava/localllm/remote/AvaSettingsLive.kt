package com.example.ava.localllm.remote

import android.content.Context
import com.example.ava.touchpad.AvaInAppHost
import com.example.ava.ui.screens.settings.SettingsSearchCatalog
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Live text of THIS Ava settings page for the model — names the user sees,
 * on/off, whether a row can be written, and visible tap centers.
 * Not a screenshot. Do not dump strings.xml into the prompt.
 */
internal object AvaSettingsLive {

    const val VISIBLE_CAP = 40
    private val SLOT = Regex("^(.*_slot)_(\\d+)$")

    fun titleRes(route: String): Int? =
        SettingsSearchCatalog.entries.firstOrNull { it.route == route }?.titleRes

    fun title(app: Context, page: AvaSettingsPoints.Page): String =
        stringOrNull(app, titleRes(page.route)) ?: page.name

    fun entityLabel(app: Context, id: String): String? {
        val pkg = app.packageName
        val res = app.resources
        val exact = res.getIdentifier("entity_$id", "string", pkg)
        if (exact != 0) {
            val raw = runCatching { app.getString(exact) }.getOrNull()?.trim().orEmpty()
            if (raw.isNotEmpty() && '%' !in raw) return raw
        }
        val slot = SLOT.matchEntire(id) ?: return null
        val base = res.getIdentifier("entity_${slot.groupValues[1]}", "string", pkg)
        val n = slot.groupValues[2].toIntOrNull() ?: return null
        if (base == 0) return null
        return runCatching { app.getString(base, n) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun pickName(i18n: String?, published: String?, local: String?, id: String): String =
        i18n?.takeIf { it.isNotBlank() }
            ?: published?.takeIf { it.isNotBlank() }
            ?: local?.takeIf { it.isNotBlank() }
            ?: humanize(id)

    fun humanize(id: String): String = id.trim().replace('_', ' ').replace(Regex("\\s+"), " ")

    fun writable(id: String, hasSetter: Boolean, local: Boolean): Boolean {
        if (AvaSettingsPoints.isBlocked(id)) return false
        if (id == "tts_volume") return true
        return hasSetter || local
    }

    fun via(id: String, writable: Boolean): String? = when {
        id == "tts_volume" -> "ava_volume"
        writable -> "ava_self"
        else -> null
    }

    fun pointHint(id: String, writable: Boolean): String = when {
        id == "tts_volume" -> "Call ava_volume target=tts with level or step."
        writable -> "ava_self action=set target=$id"
        else -> "Not a write point. Open a menu name, or tap visible[] text."
    }

    fun pointRow(
        id: String,
        name: String,
        kind: String,
        state: String?,
        options: List<String>?,
        writable: Boolean,
        min: Float? = null,
        max: Float? = null,
    ): JSONObject {
        val row = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("kind", kind)
            .put("writable", writable)
        if (!state.isNullOrBlank()) row.put("state", state)
        min?.let { row.put("min", it) }
        max?.let { row.put("max", it) }
        if (!options.isNullOrEmpty()) {
            val arr = JSONArray()
            options.forEach { arr.put(it) }
            row.put("options", arr)
        }
        via(id, writable)?.let { row.put("via", it) }
        row.put("hint", pointHint(id, writable))
        return row
    }

    fun compactVisible(tree: JSONObject?, cap: Int = VISIBLE_CAP): JSONArray? {
        if (tree == null) return null
        val raw = AvaPhoneTools.compactTree(tree).optJSONArray("nodes") ?: return null
        val labeled = JSONArray()
        val bare = JSONArray()
        for (i in 0 until raw.length()) {
            val node = raw.optJSONObject(i) ?: continue
            if (node.optBoolean("password")) continue
            val text = node.optString("text").trim()
            val desc = node.optString("contentDescription").trim()
            if (text.isNotEmpty() || desc.isNotEmpty()) labeled.put(node)
            else if (node.optBoolean("clickable") || node.optBoolean("editable") || node.optBoolean("checkable")) {
                bare.put(node)
            }
        }
        val kept = JSONArray()
        for (i in 0 until labeled.length()) {
            if (kept.length() >= cap) break
            kept.put(labeled.getJSONObject(i))
        }
        for (i in 0 until bare.length()) {
            if (kept.length() >= cap) break
            kept.put(bare.getJSONObject(i))
        }
        return if (kept.length() == 0) null else kept
    }

    fun linkPoints(visible: JSONArray, points: JSONArray?) {
        if (points == null || points.length() == 0) return
        val keys = ArrayList<Pair<String, String>>(points.length())
        for (i in 0 until points.length()) {
            val point = points.optJSONObject(i) ?: continue
            val id = point.optString("id").trim()
            if (id.isEmpty()) continue
            keys += normalize(id) to id
            keys += normalize(id.replace('_', ' ')) to id
            keys += normalize(point.optString("name")) to id
        }
        if (keys.isEmpty()) return
        for (i in 0 until visible.length()) {
            val node = visible.optJSONObject(i) ?: continue
            val text = normalize(node.optString("text"))
            val desc = normalize(node.optString("contentDescription"))
            val hit = keys.firstOrNull { (key, _) ->
                key.length >= 2 && (text == key || desc == key || text.contains(key) || desc.contains(key))
            } ?: continue
            node.put("point", hit.second)
        }
    }

    suspend fun takeVisible(retries: Int = 0): JSONArray? {
        if (!AvaInAppHost.isInFront()) return null
        repeat(retries + 1) { step ->
            if (step > 0) delay(80)
            val kept = compactVisible(AvaInAppHost.dumpTree())
            if (kept != null && kept.length() > 0) {
                AvaPhoneTools.rememberNodes(kept)
                return kept
            }
        }
        return null
    }

    private fun stringOrNull(app: Context, resId: Int?): String? {
        if (resId == null || resId == 0) return null
        return runCatching { app.getString(resId) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun normalize(raw: String): String {
        val lower = raw.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return ""
        val sb = StringBuilder(lower.length)
        var space = false
        for (ch in lower) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
                space = false
            } else if (!space) {
                sb.append(' ')
                space = true
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }
}
