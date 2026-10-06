package com.example.ava.settings

import android.content.Context
import android.util.Log
import com.example.ava.sendspin.SendspinFormatCatalog
import com.example.ava.services.VoiceSatelliteService
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Headless provisioning: merge partial JSON patches into Ava DataStore settings.
 *
 * Supported top-level store keys in [EXTRA_SETTINGS_JSON] / [EXTRA_SETTINGS_FILE]:
 * `microphone`, `player`, `experimental`, `sendspin`, `voice_channel`, `screensaver`,
 * `voice_satellite`, `browser`, `notification`, `sidebar`, `quick_entity`,
 * `home_lock`, `bluetooth`, `mods`, `ha`, `mass_api`, `update`,
 * `settings_style`, `local_scenes`
 */
object AvaSettingsApplier {
    private const val TAG = "AvaSettingsApplier"

    const val EXTRA_SETTINGS_JSON = "settings_json"
    const val EXTRA_SETTINGS_FILE = "settings_file"
    const val EXTRA_SETTING_STORE = "setting_store"
    const val EXTRA_SETTING_KEY = "setting_key"
    /** Dot path, e.g. `microphone.voicePrintEnabled` or `sendspin.serverUrl`. */
    const val EXTRA_SETTING_PATH = "setting_path"
    const val EXTRA_SETTING_BOOL = "setting_bool"
    const val EXTRA_SETTING_INT = "setting_int"
    const val EXTRA_SETTING_FLOAT = "setting_float"
    const val EXTRA_SETTING_STRING = "setting_string"
    /** JSON fragment for lists/maps/enums, e.g. `["okay_nabu","ok_nabu"]`. */
    const val EXTRA_SETTING_JSON_VALUE = "setting_json"
    const val EXTRA_NO_RESTART = "no_restart"
    const val EXTRA_RESTART_SATELLITE = "restart_satellite"
    const val EXTRA_RESTART_SENDSPIN = "restart_sendspin"

    private val json = Json { ignoreUnknownKeys = true }

    enum class StoreId(val jsonKey: String) {
        MICROPHONE("microphone"),
        PLAYER("player"),
        EXPERIMENTAL("experimental"),
        SENDSPIN("sendspin"),
        VOICE_CHANNEL("voice_channel"),
        SCREENSAVER("screensaver"),
        VOICE_SATELLITE("voice_satellite"),
        BROWSER("browser"),
        NOTIFICATION("notification"),
        SIDEBAR("sidebar"),
        QUICK_ENTITY("quick_entity"),
        HOME_LOCK("home_lock"),
        BLUETOOTH("bluetooth"),
        MODS("mods"),
        HA("ha"),
        MASS_API("mass_api"),
        UPDATE("update"),
        SETTINGS_STYLE("settings_style"),
        LOCAL_SCENES("local_scenes"),
        ;

        companion object {
            fun fromJsonKey(key: String): StoreId? =
                entries.firstOrNull { it.jsonKey.equals(key, ignoreCase = true) }
        }
    }

    data class ApplyResult(
        val changedStores: Set<StoreId> = emptySet(),
        val errors: List<String> = emptyList(),
    ) {
        val success: Boolean get() = errors.isEmpty()

        fun needsSatelliteRestart(): Boolean =
            changedStores.any {
                it in setOf(
                    StoreId.MICROPHONE,
                    StoreId.EXPERIMENTAL,
                    StoreId.VOICE_CHANNEL,
                    StoreId.VOICE_SATELLITE,
                )
            }

        fun needsSendspinRestart(): Boolean = StoreId.SENDSPIN in changedStores
    }

    suspend fun applyFromIntent(
        context: Context,
        settingsJson: String?,
        settingsFile: String?,
        settingStore: String?,
        settingKey: String?,
        settingPath: String?,
        settingBool: Boolean?,
        settingInt: Int?,
        settingFloat: Float?,
        settingString: String?,
        settingJsonValue: String?,
        noRestart: Boolean,
        forceRestartSatellite: Boolean?,
        forceRestartSendspin: Boolean?,
    ): ApplyResult {
        val patch = buildPatch(
            settingsJson = settingsJson,
            settingsFile = settingsFile,
            settingStore = settingStore,
            settingKey = settingKey,
            settingPath = settingPath,
            settingBool = settingBool,
            settingInt = settingInt,
            settingFloat = settingFloat,
            settingString = settingString,
            settingJsonValue = settingJsonValue,
            context = context,
        ) ?: return ApplyResult(errors = listOf("No settings payload provided"))

        val result = applyPatch(context, patch)
        if (!noRestart && result.success) {
            applyRestarts(
                context = context,
                result = result,
                forceRestartSatellite = forceRestartSatellite,
                forceRestartSendspin = forceRestartSendspin,
            )
        }
        return result
    }

    private fun buildPatch(
        settingsJson: String?,
        settingsFile: String?,
        settingStore: String?,
        settingKey: String?,
        settingPath: String?,
        settingBool: Boolean?,
        settingInt: Int?,
        settingFloat: Float?,
        settingString: String?,
        settingJsonValue: String?,
        context: Context,
    ): JsonObject? {
        val fromBulk = when {
            !settingsJson.isNullOrBlank() -> parseJsonObject(settingsJson)
            !settingsFile.isNullOrBlank() -> readSettingsFile(context, settingsFile)
            else -> null
        }?.let { bulk ->
            val guarded = withDeviceLocalEspHomeIdentity(bulk)
            if (guarded !== bulk) {
                Log.w(TAG, "Bulk payload carried voice_satellite.macAddress; dropped cloned ESPHome identity")
            }
            guarded
        }
        val fromSingle = buildSingleSettingPatch(
            settingStore = settingStore,
            settingKey = settingKey,
            settingPath = settingPath,
            settingBool = settingBool,
            settingInt = settingInt,
            settingFloat = settingFloat,
            settingString = settingString,
            settingJsonValue = settingJsonValue,
        )
        return when {
            fromBulk != null && fromSingle != null -> mergeJsonObjects(fromBulk, fromSingle)
            fromBulk != null -> fromBulk
            fromSingle != null -> fromSingle
            else -> null
        }
    }

    private fun buildSingleSettingPatch(
        settingStore: String?,
        settingKey: String?,
        settingPath: String?,
        settingBool: Boolean?,
        settingInt: Int?,
        settingFloat: Float?,
        settingString: String?,
        settingJsonValue: String?,
    ): JsonObject? {
        val value = resolveSettingValue(
            settingBool = settingBool,
            settingInt = settingInt,
            settingFloat = settingFloat,
            settingString = settingString,
            settingJsonValue = settingJsonValue,
        ) ?: return null

        val (storeKey, fieldKey) = when {
            !settingPath.isNullOrBlank() -> {
                val parts = settingPath.split('.', limit = 2)
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    throw IllegalArgumentException("Invalid setting_path: $settingPath")
                }
                parts[0] to parts[1]
            }
            !settingStore.isNullOrBlank() && !settingKey.isNullOrBlank() ->
                settingStore to settingKey
            else -> return null
        }

        StoreId.fromJsonKey(storeKey)
            ?: throw IllegalArgumentException("Unknown setting store: $storeKey")

        return buildJsonObject {
            put(storeKey, buildJsonObject { put(fieldKey, value) })
        }
    }

    private fun resolveSettingValue(
        settingBool: Boolean?,
        settingInt: Int?,
        settingFloat: Float?,
        settingString: String?,
        settingJsonValue: String?,
    ): JsonElement? {
        val provided = listOfNotNull(
            settingBool?.let { "bool" },
            settingInt?.let { "int" },
            settingFloat?.let { "float" },
            settingString?.let { "string" },
            settingJsonValue?.let { "json" },
        )
        if (provided.isEmpty()) return null
        if (provided.size > 1) {
            throw IllegalArgumentException("Provide only one of setting_bool/int/float/string/json")
        }
        return when (provided.single()) {
            "bool" -> JsonPrimitive(settingBool!!)
            "int" -> JsonPrimitive(settingInt!!)
            "float" -> JsonPrimitive(settingFloat!!)
            "string" -> JsonPrimitive(settingString!!)
            else -> json.parseToJsonElement(settingJsonValue!!)
        }
    }

    /**
     * Keep ESPHome identity device-local in bulk payloads (Ava#147, issue #201).
     *
     * The settings UI never exposes `macAddress`, so its presence in a bulk
     * `voice_satellite` patch means the payload is a cloned export blob. Cloning
     * identity across a fleet gives every tablet the same name (and worse, the
     * same HA unique_id): the devices then fight over mDNS and all end up as
     * "name (2)" clones. Dropping `name` + `macAddress` lets each target keep
     * its own device-derived unique default instead.
     *
     * A payload carrying `name` WITHOUT `macAddress` is deliberate per-device
     * naming (scripted provisioning) and passes through untouched, as do
     * single-field edits via setting_store/setting_path.
     */
    internal fun withDeviceLocalEspHomeIdentity(bulk: JsonObject): JsonObject {
        val storeKey = StoreId.VOICE_SATELLITE.jsonKey
        val vs = bulk[storeKey] as? JsonObject ?: return bulk
        if (!vs.containsKey("macAddress")) return bulk
        val cleaned = com.example.ava.backup.AvaBackupManager.withoutClonedEspHomeIdentity(vs)
        return JsonObject(bulk.toMutableMap().apply { put(storeKey, cleaned) })
    }

    private fun readSettingsFile(context: Context, path: String): JsonObject {
        val file = resolveReadableFile(context, path)
        val text = file.readText()
        return parseJsonObject(text)
    }

    private fun parseJsonObject(text: String): JsonObject {
        val element = json.parseToJsonElement(text.trim())
        require(element is JsonObject) { "Settings JSON must be a JSON object" }
        return element
    }

    private fun resolveReadableFile(context: Context, rawPath: String): File {
        val normalized = rawPath.trim()
        require(normalized.isNotEmpty()) { "settings_file is empty" }
        require(!normalized.contains("..")) { "settings_file must not contain .." }

        val file = File(normalized)
        val allowedRoots = buildList {
            add(context.filesDir.canonicalFile)
            context.getExternalFilesDir(null)?.canonicalFile?.let { add(it) }
            add(File("/sdcard").canonicalFile)
            add(File("/storage/emulated/0").canonicalFile)
            context.applicationInfo.dataDir?.let { add(File(it).canonicalFile) }
        }
        val canonical = file.canonicalFile
        val allowed = allowedRoots.any { root ->
            canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
        }
        require(allowed) { "settings_file not in an allowed directory: $normalized" }
        require(canonical.isFile) { "settings_file does not exist: $normalized" }
        return canonical
    }

    suspend fun applyPatch(context: Context, patch: JsonObject): ApplyResult {
        val appContext = context.applicationContext
        val changed = mutableSetOf<StoreId>()
        val errors = mutableListOf<String>()

        patch.forEach { (storeKey, storePatch) ->
            val storeId = StoreId.fromJsonKey(storeKey)
            if (storeId == null) {
                errors += "Unknown settings store: $storeKey"
                return@forEach
            }
            if (storePatch !is JsonObject) {
                errors += "Store $storeKey must be a JSON object"
                return@forEach
            }
            try {
                val applied = applyStorePatch(appContext, storeId, storePatch)
                if (applied) changed += storeId
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply $storeKey", e)
                errors += "$storeKey: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        if (changed.isNotEmpty()) {
            Log.i(TAG, "Applied settings to: ${changed.joinToString { it.jsonKey }}")
        }
        return ApplyResult(changedStores = changed, errors = errors)
    }

    /**
     * Apply a patch and trigger service restarts (satellite / sendspin) when needed.
     * Use this from callers that don't go through [applyFromIntent] — e.g. the fleet
     * console — so that changed settings actually take effect without a manual app restart.
     */
    suspend fun applyPatchAndRestart(
        context: Context,
        patch: JsonObject,
        forceRestartSatellite: Boolean? = null,
        forceRestartSendspin: Boolean? = null,
    ): ApplyResult {
        val result = applyPatch(context, patch)
        if (result.success) {
            applyRestarts(
                context = context,
                result = result,
                forceRestartSatellite = forceRestartSatellite,
                forceRestartSendspin = forceRestartSendspin,
            )
        }
        return result
    }

    private suspend fun applyStorePatch(
        context: Context,
        storeId: StoreId,
        patch: JsonObject,
    ): Boolean {
        return when (storeId) {
            StoreId.MICROPHONE -> {
                val store = MicrophoneSettingsStore(context.microphoneSettingsStore)
                mergeAndUpdate(store, MicrophoneSettings.serializer(), patch)
            }
            StoreId.PLAYER -> {
                val store = PlayerSettingsStore(context.playerSettingsStore)
                mergeAndUpdate(store, PlayerSettings.serializer(), patch)
            }
            StoreId.EXPERIMENTAL -> {
                val store = ExperimentalSettingsStore(context)
                mergeAndUpdate(store, ExperimentalSettings.serializer(), patch)
            }
            StoreId.SENDSPIN -> {
                val store = SendspinSettingsStore(context.sendspinSettingsStore)
                mergeAndUpdate(store, SendspinSettings.serializer(), patch) { it.normalized() }
            }
            StoreId.VOICE_CHANNEL -> {
                val store = VoiceChannelSettingsStore(context.voiceChannelSettingsStore)
                mergeAndUpdate(store, VoiceChannelSettings.serializer(), patch)
            }
            StoreId.SCREENSAVER -> {
                val store = ScreensaverSettingsStore(context.screensaverSettingsStore)
                mergeAndUpdate(store, ScreensaverSettings.serializer(), patch) { it.normalized() }
            }
            StoreId.VOICE_SATELLITE -> {
                val store = VoiceSatelliteSettingsStore(context.voiceSatelliteSettingsStore)
                mergeAndUpdate(store, VoiceSatelliteSettings.serializer(), patch)
            }
            StoreId.BROWSER -> {
                val store = BrowserSettingsStore(context)
                val current = store.get()
                val merged = mergePatch(current, patch, BrowserSettings.serializer())
                if (merged == current) return false
                store.update { merged }
                BrowserSettingsStore.syncMirrorsFromDataStore(context)
                true
            }
            StoreId.NOTIFICATION -> {
                val store = NotificationSettingsStore(context.notificationSettingsStore)
                mergeAndUpdate(store, NotificationSettings.serializer(), patch)
            }
            StoreId.SIDEBAR -> {
                val store = SidebarSettingsStore(context.sidebarSettingsStore)
                mergeAndUpdate(store, SidebarSettings.serializer(), patch)
            }
            StoreId.QUICK_ENTITY -> {
                val store = QuickEntitySettingsStore(context.quickEntitySettingsStore)
                mergeAndUpdate(store, QuickEntitySettings.serializer(), patch)
            }
            StoreId.HOME_LOCK -> applyHomeLockPatch(context, patch)
            StoreId.BLUETOOTH -> {
                com.example.ava.fleet.FleetBluetoothSettings.apply(
                    context,
                    org.json.JSONObject(patch.toString()),
                )
            }
            StoreId.MODS -> {
                com.example.ava.fleet.FleetModsService.apply(
                    context,
                    org.json.JSONObject(patch.toString()),
                )
            }
            StoreId.HA -> {
                val store = HaSettingsStore(context.haSettingsStore)
                mergeAndUpdate(store, HaSettings.serializer(), patch) { it.normalized() }
            }
            StoreId.MASS_API -> {
                val store = MassApiSettingsStore(context.massApiSettingsStore)
                mergeAndUpdate(store, MassApiSettings.serializer(), patch) { it.normalized() }
            }
            StoreId.UPDATE -> {
                val store = UpdateSettingsStore(context.updateSettingsStore)
                mergeAndUpdate(store, UpdateSettings.serializer(), patch)
            }
            StoreId.SETTINGS_STYLE -> {
                val store = SettingsStyleSettingsStore(context.settingsStyleSettingsStore)
                val changed = mergeAndUpdate(store, SettingsStyleSettings.serializer(), patch)
                if (changed) SettingsStyleSession.syncFromSettings(store.get())
                changed
            }
            StoreId.LOCAL_SCENES -> {
                val store = LocalScenesStore(context.localScenesSettingsStore)
                mergeAndUpdate(store, LocalScenesSettings.serializer(), patch)
            }
        }
    }

    private fun jsonBool(patch: JsonObject, key: String): Boolean? {
        val el = patch[key] as? JsonPrimitive ?: return null
        return el.booleanOrNull ?: el.contentOrNull?.toBooleanStrictOrNull()
    }

    private suspend fun applyHomeLockPatch(context: Context, patch: JsonObject): Boolean {
        val store = HomeLockSettingsStore(context.homeLockSettingsStore)
        var changed = false
        val pin = (patch["pin"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (pin.isNotEmpty()) {
            runCatching {
                store.setPin(pin)
                changed = true
            }.onFailure { Log.w(TAG, "home_lock pin rejected: ${it.message}") }
        }
        jsonBool(patch, "enabled")?.let { v ->
            if (store.get().enabled != v) {
                store.setEnabled(v)
                changed = true
            }
        }
        (patch["lockTarget"] as? JsonPrimitive)?.contentOrNull?.let { raw ->
            val target = HomeLockTarget.fromStored(raw)
            if (HomeLockTarget.resolved(store.get()) != target) {
                store.setLockTarget(target)
                changed = true
            }
        }
        (patch["idleTimeoutSeconds"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { v ->
            val clamped = HomeLockPin.clampIdleTimeoutSeconds(v)
            if (store.get().idleTimeoutSeconds != clamped) {
                store.setIdleTimeoutSeconds(clamped)
                changed = true
            }
        }
        (patch["pinLength"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { v ->
            if (HomeLockPin.resolvedPinLength(store.get()) != HomeLockPin.clampPinLength(v)) {
                store.setPinLength(v)
                changed = true
            }
        }
        jsonBool(patch, "shuffleKeypad")?.let { v ->
            if (store.get().shuffleKeypad != v) {
                store.setShuffleKeypad(v)
                changed = true
            }
        }
        jsonBool(patch, "antiBruteForce")?.let { v ->
            if (store.get().antiBruteForce != v) {
                store.setAntiBruteForce(v)
                changed = true
            }
        }
        (patch["pinHash"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { hash ->
            if (store.get().pinHash != hash) {
                store.update { it.copy(pinHash = hash) }
                changed = true
            }
        }
        return changed
    }

    private suspend fun <T> mergeAndUpdate(
        store: SettingsStore<T>,
        serializer: KSerializer<T>,
        patch: JsonObject,
        normalize: (T) -> T = { it },
    ): Boolean {
        val current = store.get()
        val merged = normalize(mergePatch(current, patch, serializer))
        if (merged == current) return false
        store.update { merged }
        return true
    }

    /** Keep fleet/intent patches inside the same bounds as SettingState setters. */
    private fun ScreensaverSettings.normalized(): ScreensaverSettings = copy(
        timeoutSeconds = normalizeScreensaverTimeoutSeconds(timeoutSeconds),
        dawnWallpaperSourceUrl = sanitizeDawnWallpaperSourceUrl(dawnWallpaperSourceUrl),
    )

    private fun HaSettings.normalized(): HaSettings = copy(
        serverUrl = HaSettingsStore.normalizeHaUrl(serverUrl),
        accessToken = accessToken.trim(),
        preferredPipeline = preferredPipeline.trim(),
    )

    private fun MassApiSettings.normalized(): MassApiSettings = copy(
        serverUrl = MassApiSettingsStore.normalizeServerUrl(serverUrl),
        username = username.trim(),
        authToken = authToken.trim(),
        clientCertAlias = clientCertAlias.trim(),
    )

    private fun SendspinSettings.normalized(): SendspinSettings {
        val rule = VolumeFollowRule.fromSettings(this)
        return copy(
            serverUrl = serverUrl.trim(),
            syncOffsetMs = syncOffsetMs.coerceIn(-5000, 5000),
            volume = volume.coerceIn(0, 100),
            preferredFormat = SendspinFormatCatalog.normalizePreferredFormat(preferredFormat),
            useDeviceVolume = true,
            // Keep legacy boolean aligned; Fleet hides syncDeviceVolumeWithHa.
            volumeFollowRule = rule.storageKey,
            syncDeviceVolumeWithHa = rule.mirrorsDeviceToHa,
        )
    }

    private fun <T> mergePatch(current: T, patch: JsonObject, serializer: KSerializer<T>): T {
        val currentJson = json.encodeToJsonElement(serializer, current).jsonObject
        val mergedJson = mergeJsonObjects(currentJson, patch)
        return json.decodeFromJsonElement(serializer, mergedJson)
    }

    private fun mergeJsonObjects(base: JsonObject, patch: JsonObject): JsonObject {
        val result = base.toMutableMap()
        patch.forEach { (key, value) ->
            val existing = result[key]
            result[key] = if (existing is JsonObject && value is JsonObject) {
                mergeJsonObjects(existing, value)
            } else {
                value
            }
        }
        return JsonObject(result)
    }

    private fun applyRestarts(
        context: Context,
        result: ApplyResult,
        forceRestartSatellite: Boolean?,
        forceRestartSendspin: Boolean?,
    ) {
        val service = VoiceSatelliteService.getInstance()
        val restartSatellite = forceRestartSatellite == true ||
            (forceRestartSatellite != false && result.needsSatelliteRestart())
        val restartSendspin = forceRestartSendspin == true ||
            (forceRestartSendspin != false && result.needsSendspinRestart())

        if (service == null) {
            if (restartSatellite || restartSendspin) {
                Log.i(TAG, "Service not running; persisted settings will apply on next start")
            }
            return
        }

        if (restartSatellite) {
            if (result.changedStores.contains(StoreId.VOICE_CHANNEL)) {
                val enabled = runCatching {
                    kotlinx.coroutines.runBlocking {
                        VoiceChannelSettingsStore(context.applicationContext.voiceChannelSettingsStore).get().enabled
                    }
                }.getOrDefault(true)
                service.applyVoiceChannelChange(enabled)
            } else {
                val rebuildPipeline = result.changedStores.any {
                    it == StoreId.VOICE_SATELLITE ||
                        it == StoreId.MICROPHONE ||
                        it == StoreId.EXPERIMENTAL
                }
                service.restartVoiceSatellite(
                    if (rebuildPipeline) {
                        com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE
                    } else {
                        com.example.ava.services.SatelliteRestartReason.SETTINGS
                    },
                )
            }
        } else if (restartSendspin) {
            // Prefer soft session restart (keeps vinyl shell). If MA was just
            // disabled, restartSendspinSession tears down without arming rebind.
            service.restartSendspinSession()
        }
    }
}
