package com.example.ava.fleet

import android.content.Context
import android.util.Log
import com.example.ava.backup.AvaBackupIncludeOptions
import com.example.ava.backup.AvaBackupManager
import com.example.ava.settings.AvaSettingsApplier
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.ava.settings.HaSettingsStore
import com.example.ava.settings.LocalScenesStore
import com.example.ava.settings.MassApiSettingsStore
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.NotificationSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.SendspinSettingsStore
import com.example.ava.settings.SettingsStyleSettingsStore
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.UpdateSettingsStore
import com.example.ava.settings.VoiceChannelSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.HomeLockPin
import com.example.ava.settings.HomeLockSettingsStore
import com.example.ava.settings.haSettingsStore
import com.example.ava.settings.homeLockSettingsStore
import com.example.ava.settings.localScenesSettingsStore
import com.example.ava.settings.massApiSettingsStore
import com.example.ava.settings.microphoneSettingsStore
import com.example.ava.settings.notificationSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.settings.sendspinSettingsStore
import com.example.ava.settings.settingsStyleSettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.settings.updateSettingsStore
import com.example.ava.settings.voiceChannelSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject as KxJsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Facade that bridges fleet HTTP routes to existing Ava backup/settings machinery.
 *
 * - Export/import reuse [AvaBackupManager] (format ava-backup v1).
 * - Snapshot builds the same JSON but returns it directly instead of writing to a file.
 * - Apply delegates to [AvaSettingsApplier.applyPatchAndRestart].
 */
object FleetSettingsService {
    private const val TAG = "FleetSettingsService"
    private val gson = Gson()
    private val kxJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ------------------------------------------------------------------
    // Export — full ava-backup v1 JSON (same as backup manager)
    // ------------------------------------------------------------------

    fun exportBackupJson(context: Context, options: AvaBackupIncludeOptions = defaultExportOptions()): String {
        return runBlocking(Dispatchers.IO) {
            val result = AvaBackupManager.export(context, options).getOrThrow()
            result.file.readText()
        }
    }

    private fun defaultExportOptions() = AvaBackupIncludeOptions(
        includeMods = true,
        includeRemoteBrowserUrl = true,
        includeSystemPermissions = false,
        includeHaCredentials = true,
        includeMassApiCredentials = true,
    )

    // ------------------------------------------------------------------
    // Import — accept ava-backup v1 JSON text, apply via backup path
    // ------------------------------------------------------------------

    data class ImportResult(
        val success: Boolean,
        val message: String,
        val needsSatelliteRestart: Boolean = false,
    )

    suspend fun importBackupJson(
        context: Context,
        jsonText: String,
        dryRun: Boolean = false,
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val root = JsonParser.parseString(jsonText).asJsonObject
            val format = root.get("format")?.asString
            if (format != "ava-backup") {
                return@withContext ImportResult(false, "invalid_format")
            }

            if (dryRun) {
                val partitions = root.getAsJsonObject("settings")?.keySet().orEmpty()
                val hasMods = root.has("mods")
                if (partitions.isEmpty() && !hasMods) {
                    return@withContext ImportResult(false, "no_settings_in_payload")
                }
                val known = AvaSettingsApplier.StoreId.entries.map { it.jsonKey }.toSet()
                val unknown = partitions.filter { it !in known }
                return@withContext ImportResult(
                    success = unknown.isEmpty(),
                    message = if (unknown.isEmpty()) {
                        "dry_run_ok:${partitions.size}" + if (hasMods) "+mods" else ""
                    } else {
                        "unknown_partitions:${unknown.joinToString()}"
                    },
                )
            }

            val settings = root.getAsJsonObject("settings")
            var needsRestart = false
            var message = "ok"

            if (settings != null && settings.size() > 0) {
                val patchMap = mutableMapOf<String, KxJsonObject>()
                AvaSettingsApplier.StoreId.entries.forEach { storeId ->
                    // Backup settings never include the fleet UI "mods" map — that lives at root.mods.
                    if (storeId == AvaSettingsApplier.StoreId.MODS) return@forEach
                    settings.get(storeId.jsonKey)?.let { element ->
                        patchMap[storeId.jsonKey] = gsonToKxJsonObject(element)
                    }
                }
                // Fleet UI templates may embed settings.mods (enabled + config).
                settings.get("mods")?.let { element ->
                    patchMap["mods"] = gsonToKxJsonObject(element)
                }

                // Keep ESPHome identity device-local when transferring full backups/templates (Ava#147).
                // Intentional single-field edits still go through AvaSettingsApplier directly.
                patchMap["voice_satellite"]?.let { vs ->
                    patchMap["voice_satellite"] = AvaBackupManager.withoutClonedEspHomeIdentity(vs)
                }
                patchMap["mass_api"]?.let { massApi ->
                    patchMap["mass_api"] = AvaBackupManager.withoutClonedMassApiCert(massApi)
                }

                if (patchMap.isNotEmpty()) {
                    val applierResult = AvaSettingsApplier.applyPatchAndRestart(context, KxJsonObject(patchMap))
                    if (applierResult.errors.isNotEmpty()) {
                        Log.w(TAG, "Import partial errors: ${applierResult.errors}")
                        message = "partial: ${applierResult.errors.joinToString()}"
                    }
                    needsRestart = applierResult.needsSatelliteRestart()
                }
            } else if (!root.has("mods")) {
                return@withContext ImportResult(false, "no_settings_in_payload")
            }

            root.getAsJsonObject("mods")?.let { mods ->
                runCatching {
                    AvaBackupManager.importModsPartition(context, mods)
                    needsRestart = true
                }.onFailure {
                    Log.e(TAG, "mods import failed", it)
                    return@withContext ImportResult(false, "mods_import_failed:${it.message}")
                }
            }

            ImportResult(
                success = true,
                message = message,
                needsSatelliteRestart = needsRestart,
            )
        } catch (e: Exception) {
            Log.e(TAG, "importBackupJson failed", e)
            ImportResult(false, e.message ?: "import_error")
        }
    }

    // ------------------------------------------------------------------
    // Snapshot — grouped JSON of current store values (for console read)
    // ------------------------------------------------------------------

    fun getSettingsSnapshot(context: Context): JSONObject {
        val settings = JSONObject()

        fun <T> readStore(get: suspend () -> T): T = runBlocking(Dispatchers.IO) { get() }

        // Use kotlinx.serialization (not Gson): under R8, Gson reflects obfuscated
        // field names (a/b/c) while SerialDescriptor keeps original JSON keys.
        settings.put("microphone", kxTree { readStore { MicrophoneSettingsStore(context.microphoneSettingsStore).get() } })
        settings.put("player", kxTree { readStore { PlayerSettingsStore(context.playerSettingsStore).get() } })
        settings.put("voice_satellite", kxTree { readStore { VoiceSatelliteSettingsStore(context.voiceSatelliteSettingsStore).get() } })
        settings.put("voice_channel", kxTree { readStore { VoiceChannelSettingsStore(context.voiceChannelSettingsStore).get() } })
        settings.put("sendspin", kxTree { readStore { SendspinSettingsStore(context.sendspinSettingsStore).get() } })
        settings.put("browser", kxTree { readStore { BrowserSettingsStore(context).get() } })
        settings.put("notification", kxTree { readStore { NotificationSettingsStore(context.notificationSettingsStore).get() } })
        settings.put("sidebar", kxTree { readStore { SidebarSettingsStore(context.sidebarSettingsStore).get() } })
        settings.put("quick_entity", kxTree { readStore { QuickEntitySettingsStore(context.quickEntitySettingsStore).get() } })
        settings.put("screensaver", kxTree { readStore { ScreensaverSettingsStore(context.screensaverSettingsStore).get() } })
        settings.put("experimental", kxTree { readStore { ExperimentalSettingsStore(context).get() } })
        settings.put("ha", kxTree { readStore { HaSettingsStore(context.haSettingsStore).get() } })
        settings.put(
            "mass_api",
            kxTree { readStore { MassApiSettingsStore(context.massApiSettingsStore).get() } }
                .put("clientCertPassword", ""),
        )
        settings.put("update", kxTree { readStore { UpdateSettingsStore(context.updateSettingsStore).get() } })
        settings.put("settings_style", kxTree { readStore { SettingsStyleSettingsStore(context.settingsStyleSettingsStore).get() } })
        settings.put("local_scenes", kxTree { readStore { LocalScenesStore(context.localScenesSettingsStore).get() } })
        settings.put("bluetooth", FleetBluetoothSettings.snapshot(context))
        settings.put("home_lock", homeLockSnapshot(context))
        settings.put("mods", FleetModsService.snapshot(context))

        val meta = JSONObject()
        for (p in FleetSettingsCatalog.PARTITIONS) {
            meta.put(p.key, JSONObject().put("tier", p.tier.name.lowercase()))
        }

        return JSONObject()
            .put("ok", true)
            .put("revision", FleetSettingsRevision.current())
            .put("settings", settings)
            .put("meta", meta)
            .put("schema", FleetSettingsCatalog.schemaJson(context))
            .put("ts", System.currentTimeMillis())
    }

    // ------------------------------------------------------------------
    // Apply partial patch — delegates to AvaSettingsApplier
    // ------------------------------------------------------------------

    data class ApplyPatchResult(
        val success: Boolean,
        val changedStores: List<String>,
        val errors: List<String>,
        val needsSatelliteRestart: Boolean,
    )

    suspend fun applyPatch(context: Context, partialSettingsJson: String): ApplyPatchResult {
        return try {
            val element = kxJson.parseToJsonElement(partialSettingsJson.trim())
            require(element is KxJsonObject) { "Patch must be a JSON object" }
            val result = AvaSettingsApplier.applyPatchAndRestart(context, element)
            ApplyPatchResult(
                success = result.success,
                changedStores = result.changedStores.map { it.jsonKey },
                errors = result.errors,
                needsSatelliteRestart = result.needsSatelliteRestart(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "applyPatch failed", e)
            ApplyPatchResult(
                success = false,
                changedStores = emptyList(),
                errors = listOf(e.message ?: "apply_error"),
                needsSatelliteRestart = false,
            )
        }
    }

    // ------------------------------------------------------------------
    // HA-published switches snapshot
    // ------------------------------------------------------------------

    fun listHaPublishedSwitches(context: Context): JSONObject {
        return JSONObject()
            .put("switches", FleetSettingsCatalog.haPublishedSwitchesSnapshot(context))
            .put("ts", System.currentTimeMillis())
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun homeLockSnapshot(context: Context): JSONObject {
        val s = runBlocking(Dispatchers.IO) {
            HomeLockSettingsStore(context.homeLockSettingsStore).get()
        }
        return JSONObject()
            .put("enabled", s.enabled)
            .put("lockTarget", s.lockTarget)
            .put("idleTimeoutSeconds", s.idleTimeoutSeconds)
            .put("pinLength", HomeLockPin.resolvedPinLength(s))
            .put("shuffleKeypad", s.shuffleKeypad)
            .put("antiBruteForce", s.antiBruteForce)
            .put("pinSet", s.pinHash.isNotBlank())
            // Write-only helper: leave empty in snapshot; set a digit PIN via apply to change it.
            .put("pin", "")
    }

    private inline fun <reified T> kxTree(block: () -> T): JSONObject {
        return JSONObject(kxJson.encodeToString(block()))
    }

    private fun gsonToKxJsonObject(element: com.google.gson.JsonElement): KxJsonObject {
        return kxJson.parseToJsonElement(element.toString()).jsonObject
    }
}
