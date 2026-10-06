package com.example.ava.mods

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class ModConfigStore(private val context: Context) {
    companion object {
        private const val TAG = "ModConfigStore"
        private const val CONFIG_DIR = "mod_configs"
    }

    private val gson = Gson()
    private val configDir = File(context.filesDir, CONFIG_DIR).apply { mkdirs() }

    fun getResolvedConfig(modId: String, manifest: ModManifest?): Map<String, String> {
        val defaults = manifest?.config
            ?.associate { item -> item.key to defaultValueFor(item) }
            .orEmpty()
        val itemsByKey = manifest?.config?.associateBy { it.key }.orEmpty()
        return defaults + readConfig(modId).mapValues { (key, value) ->
            val item = itemsByKey[key]
            sanitizeConfigValue(item, value)
        }
    }

    fun saveConfig(modId: String, values: Map<String, String>) {
        val file = configFile(modId)
        val tempFile = File(configDir, "$modId.tmp")
        tempFile.writeText(gson.toJson(values))
        if (!tempFile.renameTo(file)) {
            throw IllegalStateException("Failed to save config for mod: $modId")
        }
    }

    fun deleteConfig(modId: String) {
        configFile(modId).delete()
    }

    private fun readConfig(modId: String): Map<String, String> {
        val file = configFile(modId)
        if (!file.exists()) return emptyMap()

        return try {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            gson.fromJson<Map<String, Any?>>(file.readText(), type)
                ?.mapNotNull { (key, value) ->
                    if (key.isNullOrBlank()) return@mapNotNull null
                    when (value) {
                        null -> key to ""
                        is String -> key to value
                        is Number, is Boolean -> key to value.toString()
                        else -> null
                    }
                }
                ?.toMap()
                ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read config for $modId", e)
            emptyMap()
        }
    }

    private fun configFile(modId: String): File = File(configDir, "$modId.json")

    private fun defaultValueFor(item: ModConfigItem): String {
        item.defaultValue?.let { return it }
        return when (item.type) {
            "switch" -> "true"
            "select" -> item.options?.firstOrNull().orEmpty()
            "number" -> item.min?.toInt()?.toString() ?: "0"
            else -> ""
        }
    }

    private fun sanitizeConfigValue(item: ModConfigItem?, value: String): String {
        if (item?.type != "text") {
            return value
        }

        val normalized = value.replace('\r', '\n')
        val lines = normalized.split('\n')
        if (lines.size <= 1 && !normalized.contains("Use ") && !normalized.contains("\n+")) {
            return normalized.trim()
        }

        return lines.firstNotNullOfOrNull { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> null
                trimmed.startsWith("Use ") -> null
                trimmed.startsWith("+") -> null
                else -> trimmed
            }
        }.orEmpty()
    }
}
