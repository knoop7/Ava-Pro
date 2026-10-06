package com.example.ava.localllm.remote

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.ava.mods.ModManager
import com.example.ava.mods.ModPermissionCoordinator
import com.example.ava.mods.ModPermissions
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.ui.Screen
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Live text of THIS device's mod store for the model — catalog, installed,
 * on/off, updates, and writable inject. Not a screenshot. Do not dump
 * store.json into the prompt.
 */
internal object AvaModStore {

    const val LIST_CAP = 40
    const val DESC_CAP = 160
    const val DETAIL_CAP = 400

    data class Row(
        val id: String,
        val name: String,
        val version: String = "",
        val author: String = "",
        val description: String = "",
        val detail: String = "",
        val group: String = "feature",
        val installed: Boolean = false,
        val enabled: Boolean = false,
        val update: Boolean = false,
        val imported: Boolean = false,
    )

    fun normalize(raw: String): String {
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

    fun isImportAsk(spoken: String): Boolean {
        val q = normalize(spoken)
        if (q.isEmpty()) return false
        return q == "import" || q == "zip" || q.contains("导入") || q.contains("本地安装") ||
            q.contains("本地zip") || q.contains("local zip") || q.contains("import zip")
    }

    fun isStoreAsk(spoken: String): Boolean {
        val q = normalize(spoken)
        if (q == "mod" || q == "mods" || q == "store" || q == "模组" || q == "模组商店" ||
            q == "mod store" || q == "modstore"
        ) {
            return true
        }
        return q.contains("模组商店") || q.contains("mod store")
    }

    fun looksLikeCatalogAsk(spoken: String): Boolean {
        if (isStoreAsk(spoken)) return true
        val q = normalize(spoken)
        val topic = q.contains("模组") || q.contains("mods") || q.contains("store") ||
            q.contains("商店") || q.contains("目录") || q.contains("catalog")
        if (!topic) return false
        val write = q.contains("安装") || q.contains("下载") || q.contains("卸载") ||
            q.contains("删除") || q.contains("开启") || q.contains("关闭") ||
            q.contains("import") || q.contains("install") || q.contains("download") ||
            q.contains("enable") || q.contains("disable") || q.contains("uninstall") ||
            q.contains("update") || q.contains("更新")
        return !write
    }

    fun resolve(spoken: String, rows: List<Row>): List<Row> {
        val q = normalize(spoken)
        if (q.length < 2 || isStoreAsk(spoken) || isImportAsk(spoken) || looksLikeCatalogAsk(spoken)) {
            return emptyList()
        }
        val tokens = q.split(' ').filter { it.length >= 3 && it !in TOKEN_STOP }
        val scored = ArrayList<Pair<Row, Int>>(rows.size)
        for (row in rows) {
            val id = normalize(row.id)
            val name = normalize(row.name)
            var score = when {
                id == q || name == q -> 1_000
                id.startsWith(q) || name.startsWith(q) -> 800 + q.length
                id.contains(q) || name.contains(q) -> 500 + q.length
                q.contains(name) && name.length >= 3 -> 400 + name.length
                q.contains(id) && id.length >= 3 -> 350 + id.length
                else -> 0
            }
            if (score == 0) {
                for (token in tokens) {
                    val tokenScore = when {
                        id == token || name == token -> 900
                        id.startsWith(token) || name.startsWith(token) -> 700 + token.length
                        id.contains(token) || name.contains(token) -> 480 + token.length
                        else -> 0
                    }
                    if (tokenScore > score) score = tokenScore
                }
            }
            if (score > 0) scored.add(row to score)
        }
        if (scored.isEmpty()) return emptyList()
        val best = scored.maxOf { it.second }
        return scored.filter { it.second == best }.map { it.first }.distinctBy { it.id }
    }

    fun rowJson(row: Row, detailed: Boolean = false): JSONObject {
        val writable = true
        val state = when {
            row.installed && row.enabled -> "on"
            row.installed -> "off"
            else -> "available"
        }
        val out = JSONObject()
            .put("id", row.id)
            .put("name", row.name)
            .put("kind", "mod")
            .put("state", state)
            .put("installed", row.installed)
            .put("enabled", row.enabled)
            .put("writable", writable)
            .put("via", "ava_self")
            .put("group", row.group)
        if (row.version.isNotBlank()) out.put("version", row.version)
        if (row.author.isNotBlank()) out.put("author", row.author)
        if (row.update) out.put("update", true)
        if (row.imported) out.put("imported", true)
        clip(row.description, DESC_CAP)?.let { out.put("description", it) }
        if (detailed) clip(row.detail.ifBlank { row.description }, DETAIL_CAP)?.let { out.put("detail", it) }
        out.put("hint", rowHint(row))
        return out
    }

    fun listBody(rows: List<Row>, stale: Boolean = false): JSONObject {
        val store = JSONArray()
        val installed = JSONArray()
        var updates = 0
        for (row in rows.take(LIST_CAP)) {
            val json = rowJson(row)
            store.put(json)
            if (row.installed) installed.put(json)
            if (row.update) updates++
        }
        val out = JSONObject()
            .put("action", "mods")
            .put("count", rows.size)
            .put("mods", store)
            .put("installed_count", rows.count { it.installed })
            .put("update_count", updates)
        if (installed.length() > 0) out.put("installed", installed)
        if (rows.size > LIST_CAP) out.put("truncated", true)
        if (stale) out.put("stale", true)
        out.put("hint", listHint(stale))
        return out
    }

    fun listHint(stale: Boolean = false): String {
        val staleBit = if (stale) " Catalog refresh failed — this is the last known list. " else " "
        return "This store as text — no screenshot.$staleBit" +
            "mods[] is the live catalog and installed list (name, on/off, update, writable). " +
            "Speak from it. target= an id to read one. on=true downloads or turns on; on=false turns off; press=true uninstalls. " +
            "Zip import needs the file picker on the store page — do not invent a path. " +
            "Do not tap visible[] to download; the download icon has no name."
    }

    suspend fun execute(
        app: Context,
        target: String,
        on: Boolean?,
        press: Boolean?,
    ): AvaToolCallback.Result {
        if (isImportAsk(target)) return importFail()
        val ready = runCatching { load(app, refresh = target.isBlank() || isStoreAsk(target)) }.getOrElse {
            return AvaToolCallback.fail("tool_error", it.message ?: "mod store failed")
        }
        if (on != null && target.isBlank()) {
            return AvaToolCallback.fail("invalid_request", "target is required to change a mod. Omit on to list.")
        }
        if (press == true && target.isBlank()) {
            return AvaToolCallback.fail("invalid_request", "target is required to uninstall a mod.")
        }
        if (target.isBlank() || isStoreAsk(target) || looksLikeCatalogAsk(target)) {
            return AvaToolCallback.ok(listBody(ready.rows, ready.stale))
        }
        val hits = resolve(target, ready.rows)
        if (hits.isEmpty()) {
            return AvaToolCallback.fail(
                "not_found",
                "not a store mod. Call mods with no target to list the catalog.",
            )
        }
        if (hits.size > 1) {
            return AvaToolCallback.ok(
                listBody(hits, ready.stale).put(
                    "hint",
                    "More than one mod matches. Call again with the id.",
                ),
                status = "accepted",
            )
        }
        val row = hits.first()
        if (press == true) return uninstall(ready.manager, row)
        if (on == true) return turnOn(app, ready.manager, row)
        if (on == false) return turnOff(ready.manager, row)
        return AvaToolCallback.ok(rowJson(row, detailed = true).put("action", "mods"))
    }

    suspend fun trySet(
        app: Context,
        spoken: String,
        on: Boolean?,
        press: Boolean,
    ): AvaToolCallback.Result? {
        if (spoken.isBlank() || isStoreAsk(spoken) || isImportAsk(spoken)) return null
        val ready = runCatching { load(app, refresh = false) }.getOrNull() ?: return null
        var hits = resolve(spoken, ready.rows)
        if (hits.isEmpty() && ready.manager.storeMods.value.isEmpty()) {
            val again = runCatching { load(app, refresh = true) }.getOrNull() ?: return null
            hits = resolve(spoken, again.rows)
            if (hits.isEmpty()) return null
            return applySet(app, again.manager, hits, spoken, on, press)
        }
        if (hits.isEmpty()) return null
        return applySet(app, ready.manager, hits, spoken, on, press)
    }

    suspend fun attach(app: Context, body: JSONObject, refreshIfEmpty: Boolean): JSONObject {
        val route = body.optString("route").ifBlank { body.optString("here") }
        if (route != Screen.MOD_STORE) return body
        val ready = runCatching {
            val manager = ModManager.getInstance(app)
            manager.ensureRegistryLoaded()
            val empty = manager.storeMods.value.isEmpty()
            load(app, refresh = refreshIfEmpty || empty)
        }.getOrElse {
            body.put("mods", JSONArray())
            body.put("mods_hint", it.message ?: "mod store failed")
            return body
        }
        val listed = listBody(ready.rows, ready.stale)
        body.put("mods", listed.getJSONArray("mods"))
        listed.optJSONArray("installed")?.let { body.put("installed", it) }
        body.put("installed_count", listed.optInt("installed_count"))
        body.put("update_count", listed.optInt("update_count"))
        if (ready.stale) body.put("stale", true)
        body.put("hint", storePageHint(body.optBoolean("will_restore")))
        return body
    }

    fun linkVisible(visible: JSONArray, mods: JSONArray?) {
        if (mods == null || mods.length() == 0) return
        AvaSettingsLive.linkPoints(visible, mods)
    }

    private data class Ready(
        val manager: ModManager,
        val rows: List<Row>,
        val stale: Boolean,
    )

    private suspend fun load(app: Context, refresh: Boolean): Ready {
        val manager = ModManager.getInstance(app)
        manager.ensureRegistryLoaded()
        var stale = false
        if (refresh || manager.storeMods.value.isEmpty()) {
            val result = manager.refreshStore()
            if (result.isFailure) stale = true
        }
        return Ready(manager, snapshot(manager), stale)
    }

    internal fun snapshot(manager: ModManager): List<Row> {
        val byId = LinkedHashMap<String, Row>()
        for (store in manager.storeMods.value) {
            byId[store.id] = Row(
                id = store.id,
                name = store.name.ifBlank { store.id },
                version = store.version,
                author = store.author,
                description = store.description,
                detail = store.detailDescription,
                group = if (store.path.contains("/devices/", ignoreCase = true)) "device" else "feature",
            )
        }
        for (installed in manager.installedMods.value) {
            val manifest = manager.getCachedManifest(installed.id)
            val prior = byId[installed.id]
            byId[installed.id] = Row(
                id = installed.id,
                name = prior?.name?.takeIf { it.isNotBlank() }
                    ?: manifest?.name?.takeIf { it.isNotBlank() }
                    ?: installed.id,
                version = installed.version.ifBlank { prior?.version.orEmpty() },
                author = prior?.author?.ifBlank { manifest?.author.orEmpty() }.orEmpty()
                    .ifBlank { manifest?.author.orEmpty() },
                description = prior?.description?.ifBlank { manifest?.description.orEmpty() }.orEmpty()
                    .ifBlank { manifest?.description.orEmpty() },
                detail = prior?.detail?.ifBlank { manifest?.detailDescription.orEmpty() }.orEmpty()
                    .ifBlank { manifest?.detailDescription.orEmpty() },
                group = prior?.group ?: "installed",
                installed = true,
                enabled = installed.enabled,
                update = manager.hasUpdate(installed.id),
                imported = installed.fromLocalImport,
            )
        }
        return byId.values.toList()
    }

    private suspend fun applySet(
        app: Context,
        manager: ModManager,
        hits: List<Row>,
        spoken: String,
        on: Boolean?,
        press: Boolean,
    ): AvaToolCallback.Result {
        if (hits.size > 1) {
            val names = hits.joinToString { "${it.name} (${it.id})" }
            return AvaToolCallback.fail("ungrounded", "\"$spoken\" matches more than one mod: $names")
        }
        val row = hits.first()
        if (press) return uninstall(manager, row)
        if (on == true) return turnOn(app, manager, row)
        if (on == false) return turnOff(manager, row)
        return AvaToolCallback.ok(rowJson(row, detailed = true).put("action", "mods"))
    }

    private suspend fun turnOn(app: Context, manager: ModManager, row: Row): AvaToolCallback.Result {
        val needDownload = !row.installed || row.update
        if (needDownload) {
            if (row.imported && row.update) {
                return AvaToolCallback.fail(
                    "invalid_request",
                    "This install came from a local zip. Store updates do not apply. Import a new zip on the store page.",
                )
            }
            val downloaded = manager.downloadMod(row.id)
            if (downloaded.isFailure) {
                return AvaToolCallback.fail(
                    "tool_error",
                    downloaded.exceptionOrNull()?.message ?: "download failed",
                )
            }
        }
        val fresh = snapshot(manager).firstOrNull { it.id == row.id } ?: row.copy(installed = true)
        permissionBlock(app, manager, fresh)?.let { block ->
            if (manager.isEnabled(fresh.id)) {
                manager.setModEnabled(fresh.id, false)
            }
            return AvaToolCallback.ok(block, status = "accepted")
        }
        if (!manager.isEnabled(fresh.id)) {
            val enabled = manager.setModEnabled(fresh.id, true)
            if (enabled.isFailure) {
                return AvaToolCallback.fail(
                    "tool_error",
                    enabled.exceptionOrNull()?.message ?: "could not enable this mod",
                )
            }
        }
        restartSatellite()
        val now = snapshot(manager).firstOrNull { it.id == row.id } ?: fresh.copy(enabled = true)
        val body = rowJson(now, detailed = true)
            .put("action", "mods")
            .put("applied", if (needDownload && row.update) "updated" else if (needDownload) "downloaded" else "enabled")
        if (ModPermissionCoordinator.accessibilityStillMissing(app, now.id)) {
            body.put("need", "accessibility")
            body.put(
                "hint",
                "Mod is on. This mod also wants Accessibility — the host can open that system page if they agree.",
            )
        } else {
            body.put("hint", "Voice satellite is restarting so the mod can load.")
        }
        return AvaToolCallback.ok(body, status = "accepted")
    }

    private suspend fun turnOff(manager: ModManager, row: Row): AvaToolCallback.Result {
        if (!row.installed) {
            return AvaToolCallback.fail("not_found", "${row.name} is not installed.")
        }
        if (!row.enabled) {
            return AvaToolCallback.ok(
                rowJson(row, detailed = true).put("action", "mods").put("applied", "already_off"),
                status = "observed",
            )
        }
        val result = manager.setModEnabled(row.id, false)
        if (result.isFailure) {
            return AvaToolCallback.fail(
                "tool_error",
                result.exceptionOrNull()?.message ?: "could not disable this mod",
            )
        }
        restartSatellite()
        val now = snapshot(manager).firstOrNull { it.id == row.id } ?: row.copy(enabled = false)
        return AvaToolCallback.ok(
            rowJson(now, detailed = true).put("action", "mods").put("applied", "disabled")
                .put("hint", "Voice satellite is restarting so the mod can unload."),
            status = "accepted",
        )
    }

    private suspend fun uninstall(manager: ModManager, row: Row): AvaToolCallback.Result {
        if (!row.installed) {
            return AvaToolCallback.fail("not_found", "${row.name} is not installed.")
        }
        val wasOn = row.enabled
        val result = manager.deleteMod(row.id)
        if (result.isFailure) {
            return AvaToolCallback.fail(
                "tool_error",
                result.exceptionOrNull()?.message ?: "could not uninstall this mod",
            )
        }
        if (wasOn) restartSatellite()
        return AvaToolCallback.ok(
            JSONObject()
                .put("action", "mods")
                .put("id", row.id)
                .put("name", row.name)
                .put("installed", false)
                .put("enabled", false)
                .put("applied", "uninstalled")
                .put("hint", if (wasOn) "Voice satellite is restarting so the mod can unload." else "Uninstalled."),
            status = "accepted",
        )
    }

    private fun permissionBlock(app: Context, manager: ModManager, row: Row): JSONObject? {
        val required = manager.getRequiredPermissions(row.id)
        val missingPrivileged = required.filter { perm ->
            ModPermissions.requiresPrivilegedGrant(perm) &&
                ContextCompat.checkSelfPermission(app, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPrivileged.isNotEmpty() && !ModPermissionCoordinator.canUsePrivilegedShell()) {
            return needBody(row, "shizuku", "This mod needs a privileged grant (Shizuku or root). Turn it on on the store page after that is ready.")
        }
        val missingRuntime = manager.getMissingPermissions(row.id)
            .filter { ModPermissions.requiresRuntimeGrant(app, it) }
        if (missingRuntime.isNotEmpty()) {
            return needBody(
                row,
                "permission",
                "This mod needs a permission the user must grant. Open the store page and turn it on there.",
            ).put("permissions", JSONArray(missingRuntime.map { it.substringAfterLast('.') }))
        }
        return null
    }

    private fun needBody(row: Row, need: String, hint: String): JSONObject {
        return rowJson(row.copy(enabled = false), detailed = true)
            .put("action", "mods")
            .put("enabled", false)
            .put("need", need)
            .put("hint", hint)
            .put(
                "next_action",
                JSONObject()
                    .put("tool", AvaSelfTools.NAME)
                    .put("arguments", JSONObject().put("action", "settings").put("target", "模组")),
            )
    }

    private fun importFail(): AvaToolCallback.Result = AvaToolCallback.fail(
        "invalid_request",
        "Zip import needs the file picker on the store page. Ask the user to pick the file there. Do not invent a path.",
        JSONObject()
            .put("action", "mods")
            .put("need", "file_picker")
            .put(
                "next_action",
                JSONObject()
                    .put("tool", AvaSelfTools.NAME)
                    .put("arguments", JSONObject().put("action", "settings").put("target", "模组")),
            ),
    )

    private fun restartSatellite() {
        VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
    }

    private fun rowHint(row: Row): String = when {
        row.update -> "ava_self action=mods target=${row.id} on=true"
        row.installed && row.enabled -> "on=false disables; press=true uninstalls"
        row.installed -> "ava_self action=mods target=${row.id} on=true"
        else -> "ava_self action=mods target=${row.id} on=true"
    }

    private fun storePageHint(willRestore: Boolean): String {
        val restore = if (willRestore) {
            " Host restores origin when this turn ends — do not leave the user in Ava settings."
        } else {
            ""
        }
        return listHint() + restore
    }

    private fun clip(text: String, cap: Int): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return if (t.length <= cap) t else t.take(cap - 1).trimEnd() + "…"
    }

    private val TOKEN_STOP = setOf(
        "show", "with", "from", "this", "that", "have", "store", "mods", "make",
        "support", "device", "home", "assistant", "the", "and", "for", "off", "on",
        "open", "turn", "help", "please", "just", "want",
    )
}
