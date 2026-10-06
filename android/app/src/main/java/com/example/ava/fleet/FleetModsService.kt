package com.example.ava.fleet

import android.content.Context
import android.util.Log
import com.example.ava.mods.ModConfigItem
import com.example.ava.mods.ModManager
import com.example.ava.mods.ModManagerBridge
import com.example.ava.services.VoiceSatelliteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fleet bridge for the Ava Mod Store: list installed mods, read/write config, toggle enable.
 *
 * Snapshot shape under settings.mods:
 * ```
 * {
 *   "<modId>": {
 *     "enabled": true,
 *     "name": "...",
 *     "version": "...",
 *     "config": { "key": "value" },
 *     "schema": [ { "type","key","label",... } ]
 *   }
 * }
 * ```
 */
object FleetModsService {
    private const val TAG = "FleetModsService"

    fun snapshot(context: Context): JSONObject {
        val app = context.applicationContext
        val mm = ModManager.getInstance(app)
        runBlocking(Dispatchers.IO) { mm.ensureRegistryLoaded() }

        val root = JSONObject()
        for (mod in mm.installedMods.value) {
            val manifest = mm.getCachedManifest(mod.id) ?: mm.getModManifest(mod.id)
            val config = mm.getResolvedConfig(mod.id, manifest)
            val schema = JSONArray()
            for (item in manifest?.config.orEmpty()) {
                schema.put(schemaItemJson(item))
            }
            val missing = runCatching { mm.getMissingPermissions(mod.id).size }.getOrDefault(0)
            val hasUpdate = runCatching { mm.hasUpdate(mod.id) }.getOrDefault(false)
            root.put(
                mod.id,
                JSONObject()
                    .put("enabled", mod.enabled)
                    .put("name", manifest?.name?.ifBlank { mod.id } ?: mod.id)
                    .put("version", mod.version.ifBlank { manifest?.version.orEmpty() })
                    .put("author", manifest?.author.orEmpty())
                    .put("description", manifest?.description.orEmpty())
                    .put("icon", manifest?.icon.orEmpty())
                    .put("hasUpdate", hasUpdate)
                    .put("missingPermissions", missing)
                    .put("installedAt", mod.installedAt)
                    .put("config", mapToJson(config))
                    .put("schema", schema),
            )
        }
        return root
    }

    /**
     * Apply a partial mods patch. Each top-level key is a mod id; values may include
     * `enabled` and/or `config` (string map). Meta fields (name, schema, …) are ignored.
     */
    fun apply(context: Context, patch: JSONObject): Boolean {
        if (patch.length() == 0) return false
        val app = context.applicationContext
        val mm = ModManager.getInstance(app)
        runBlocking(Dispatchers.IO) { mm.ensureRegistryLoaded() }

        var changed = false
        var needsRestart = false
        val keys = patch.keys()
        while (keys.hasNext()) {
            val modId = keys.next()
            val entry = patch.optJSONObject(modId) ?: continue
            if (!mm.isInstalled(modId)) {
                Log.w(TAG, "skip unknown mod $modId")
                continue
            }

            if (entry.has("enabled")) {
                val enabled = entry.optBoolean("enabled")
                if (mm.isEnabled(modId) != enabled) {
                    val result = runBlocking(Dispatchers.IO) { mm.setModEnabled(modId, enabled) }
                    if (result.isSuccess) {
                        changed = true
                        needsRestart = true
                    } else {
                        Log.w(TAG, "setModEnabled($modId) failed: ${result.exceptionOrNull()?.message}")
                    }
                }
            }

            if (entry.has("config")) {
                val cfgObj = entry.optJSONObject("config") ?: JSONObject()
                val map = jsonToStringMap(cfgObj)
                val current = mm.getResolvedConfig(modId)
                if (map != current) {
                    val result = runBlocking(Dispatchers.IO) { mm.saveModConfig(modId, map) }
                    if (result.isSuccess) {
                        changed = true
                        needsRestart = true
                        val manifest = mm.getCachedManifest(modId) ?: mm.getModManifest(modId)
                        ModManagerBridge.syncConfig(
                            modId = modId,
                            managerClassName = manifest?.manager,
                            context = app,
                            configValues = map,
                        )
                    } else {
                        Log.w(TAG, "saveModConfig($modId) failed: ${result.exceptionOrNull()?.message}")
                    }
                }
            }
        }

        if (needsRestart) {
            runCatching {
                VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
            }.onFailure { Log.w(TAG, "satellite restart after mods apply: ${it.message}") }
        }
        return changed
    }

    private fun schemaItemJson(item: ModConfigItem): JSONObject {
        val options = JSONArray()
        item.options.orEmpty().forEach { options.put(it) }
        return JSONObject()
            .put("type", item.type)
            .put("key", item.key)
            .put("label", item.label)
            .put("description", item.description)
            .put("defaultValue", item.defaultValue ?: JSONObject.NULL)
            .put("options", options)
            .put("min", item.min ?: JSONObject.NULL)
            .put("max", item.max ?: JSONObject.NULL)
            .put("step", item.step ?: JSONObject.NULL)
            .put("enabledWhen", item.enabledWhen ?: JSONObject.NULL)
    }

    private fun mapToJson(map: Map<String, String>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        return o
    }

    private fun jsonToStringMap(obj: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k) ?: continue
            out[k] = when (v) {
                is Boolean -> v.toString()
                is Number -> v.toString()
                JSONObject.NULL -> ""
                else -> v.toString()
            }
        }
        return out
    }
}
