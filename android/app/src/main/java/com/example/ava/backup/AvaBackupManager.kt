package com.example.ava.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.example.ava.fleet.FleetBluetoothSettings
import com.example.ava.lyrics.LyricDisplayRuntime
import com.example.ava.mods.ModConfigStore
import com.example.ava.mods.ModManager
import com.example.ava.mods.ModRegistry
import com.example.ava.mods.parseModRegistry
import com.example.ava.settings.AvaSettingsApplier
import com.example.ava.settings.BrowserSettings
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.DarkModeManager
import com.example.ava.settings.DisplayScale
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.ava.settings.HaSettingsStore
import com.example.ava.settings.HomeLockSettingsStore
import com.example.ava.settings.LocalScenesStore
import com.example.ava.settings.MassApiSettingsStore
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.NotificationSettings
import com.example.ava.settings.NotificationSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.QuickEntitySettings
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.SendspinSettingsStore
import com.example.ava.settings.SettingsStyleSettingsStore
import com.example.ava.settings.SidebarSettings
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.UpdateSettingsStore
import com.example.ava.settings.VoiceChannelSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject as KxJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AvaBackupIncludeOptions(
    val includeStores: Set<String> = PORTABLE_STORE_KEYS,
    val includeMods: Boolean = true,
    val includeRemoteBrowserUrl: Boolean = false,
    val includeSystemPermissions: Boolean = false,
    /** Home Assistant URL + long-lived token. Off unless the user ticks it. */
    val includeHaCredentials: Boolean = false,
    /** Music Assistant API URL / user / password / token. Off unless ticked. */
    val includeMassApiCredentials: Boolean = false,
    /** Current desktop icon placement (minimal launcher). */
    val includeDesktopLayout: Boolean = false,
    /** Current desktop widget placement and sizes. */
    val includeWidgetLayout: Boolean = false,
) {
    fun includesStore(key: String): Boolean = key in includeStores

    fun withStore(key: String, included: Boolean): AvaBackupIncludeOptions {
        val next = if (included) includeStores + key else includeStores - key
        return copy(
            includeStores = next,
            includeHaCredentials = if (key == "ha" && !included) false else includeHaCredentials,
            includeMassApiCredentials = if (key == "mass_api" && !included) false else includeMassApiCredentials,
            includeRemoteBrowserUrl = if (key == "voice_satellite" && !included) false else includeRemoteBrowserUrl,
        )
    }

    fun withStores(keys: Set<String>, included: Boolean): AvaBackupIncludeOptions {
        val next = if (included) includeStores + keys else includeStores - keys
        return copy(
            includeStores = next,
            includeHaCredentials = if ("ha" !in next) false else includeHaCredentials,
            includeMassApiCredentials = if ("mass_api" !in next) false else includeMassApiCredentials,
            includeRemoteBrowserUrl = if ("voice_satellite" !in next) false else includeRemoteBrowserUrl,
        )
    }

    fun hasAnyInclude(): Boolean =
        includeStores.isNotEmpty() ||
            includeMods ||
            includeHaCredentials ||
            includeMassApiCredentials ||
            includeRemoteBrowserUrl ||
            includeDesktopLayout ||
            includeWidgetLayout ||
            includeSystemPermissions

    /**
     * Payload / apply set: only rows the picker is actually showing.
     * Drops leftover default keys that presence hid on the sender.
     */
    fun restrictToVisible(
        storeKeys: Set<String>,
        mods: Boolean,
        haCredentials: Boolean,
        massCredentials: Boolean,
        remoteUrl: Boolean,
        desktopLayout: Boolean,
        widgetLayout: Boolean,
        system: Boolean,
    ): AvaBackupIncludeOptions = copy(
        includeStores = includeStores.intersect(storeKeys),
        includeMods = includeMods && mods,
        includeHaCredentials = includeHaCredentials && haCredentials,
        includeMassApiCredentials = includeMassApiCredentials && massCredentials,
        includeRemoteBrowserUrl = includeRemoteBrowserUrl && remoteUrl,
        includeDesktopLayout = includeDesktopLayout && desktopLayout,
        includeWidgetLayout = includeWidgetLayout && widgetLayout,
        includeSystemPermissions = includeSystemPermissions && system,
    )

    companion object {
        val PORTABLE_STORE_KEYS: Set<String> = linkedSetOf(
            "voice_satellite",
            "microphone",
            "voice_channel",
            "player",
            "sendspin",
            "ha",
            "mass_api",
            "quick_entity",
            "local_scenes",
            "browser",
            "screensaver",
            "notification",
            "sidebar",
            "home_lock",
            "bluetooth",
            "settings_style",
            "experimental",
            "update",
        )
    }
}

/** Sender ticks vs the rows the sender's picker actually showed. */
data class AvaBackupPeek(
    val selected: AvaBackupIncludeOptions,
    val catalog: AvaBackupIncludeOptions,
)

data class AvaBackupExportResult(
    val file: File,
    val fileName: String,
)

data class AvaBackupImportResult(
    val success: Boolean,
    val message: String,
    val needsSatelliteRestart: Boolean = false,
)

object AvaBackupManager {
    private const val TAG = "AvaBackupManager"
    private const val FORMAT = "ava-backup"
    private const val VERSION = 1

    /** Device-local ESPHome identity fields — never clone via backup/template transfer. */
    private val ESPHOME_NODE_IDENTITY_KEYS = setOf("macAddress", "name", "bluetoothMacAddress")
    private val HA_SECRET_KEYS = setOf("serverUrl", "accessToken")
    private val MASS_API_SECRET_KEYS = setOf("serverUrl", "username", "password", "authToken")
    /** KeyChain / PKCS#12 aliases are device-local; never clone across tablets. */
    private val MASS_API_DEVICE_LOCAL_KEYS = setOf("clientCertAlias", "clientCertPassword")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val gson = Gson()

    fun formatBackupFileName(now: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        return "ava-$stamp-backup.json"
    }

    /**
     * Fallback include set when the picker has not seeded from this device yet.
     * Secrets, remote URL, desktop/widget layout, and root grants stay opt-in.
     * The picker replaces this with the device's first remembered presence.
     */
    val CLONE_INCLUDE_OPTIONS = AvaBackupIncludeOptions(
        includeStores = emptySet(),
        includeMods = false,
        includeRemoteBrowserUrl = false,
        includeSystemPermissions = false,
        includeHaCredentials = false,
        includeMassApiCredentials = false,
        includeDesktopLayout = false,
        includeWidgetLayout = false,
    )

    val FILE_INCLUDE_OPTIONS = AvaBackupIncludeOptions(
        includeStores = emptySet(),
        includeMods = false,
    )

    /**
     * Selection made on the Backup page's clone tab, consumed by the clone send screen.
     * Falls back to [CLONE_INCLUDE_OPTIONS] when that screen is reached directly.
     */
    @Volatile
    var cloneSendOptions: AvaBackupIncludeOptions = CLONE_INCLUDE_OPTIONS

    /** Rows visible on the sender picker (checked and unchecked). */
    @Volatile
    var cloneSendCatalog: AvaBackupIncludeOptions? = null

    /** Backup payload for the clone send window. Never written to shared storage. */
    suspend fun exportForClone(
        context: Context,
        options: AvaBackupIncludeOptions = CLONE_INCLUDE_OPTIONS,
        catalog: AvaBackupIncludeOptions? = cloneSendCatalog,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            buildExportJson(context.applicationContext, options, catalog)
                .toByteArray(Charsets.UTF_8)
        }
    }

    suspend fun export(
        context: Context,
        options: AvaBackupIncludeOptions,
    ): Result<AvaBackupExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val payload = buildExportJson(appContext, options, catalog = null)
            val fileName = formatBackupFileName()
            val dir = File(appContext.cacheDir, "backups").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(payload)
            AvaBackupExportResult(file = file, fileName = fileName)
        }
    }

    fun createShareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun peekIncludes(context: Context, uri: Uri): Result<AvaBackupIncludeOptions> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = readBackupRoot(context, uri)
                parseIncludes(root)
            }
        }

    suspend fun import(
        context: Context,
        uri: Uri,
        options: AvaBackupIncludeOptions,
    ): AvaBackupImportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            val root = readBackupRoot(context, uri)
            createAutoSnapshot(appContext, "pre-import")
            importRoot(appContext, root, options)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            AvaBackupImportResult(false, e.message ?: "import_failed")
        }
    }

    /**
     * Apply a clone payload received over the LAN (see AvaCloneTransfer) after
     * snapshotting the current configuration. [options] is the receiver's selection
     * from the confirm step; `null` falls back to everything the sender packed.
     */
    suspend fun importFromCloneBytes(
        context: Context,
        payload: ByteArray,
        options: AvaBackupIncludeOptions? = null,
    ): AvaBackupImportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            val root = JsonParser.parseString(payload.decodeToString()).asJsonObject
            if (root.get("format")?.asString != FORMAT) {
                throw IllegalStateException("invalid_format")
            }
            createAutoSnapshot(appContext, "pre-clone")
            importRoot(appContext, root, options ?: parseIncludes(root))
        } catch (e: Exception) {
            Log.e(TAG, "Clone import failed", e)
            AvaBackupImportResult(false, e.message ?: "import_failed")
        }
    }

    /** Sender ticks plus the rows the sender picker showed. */
    fun peekCloneIncludes(payload: ByteArray): Result<AvaBackupPeek> = runCatching {
        val root = JsonParser.parseString(payload.decodeToString()).asJsonObject
        if (root.get("format")?.asString != FORMAT) {
            throw IllegalStateException("invalid_format")
        }
        val selected = parseIncludes(root)
        AvaBackupPeek(
            selected = selected,
            catalog = parseShownCatalog(root.getAsJsonObject("includes"), selected),
        )
    }

    private suspend fun importRoot(
        appContext: Context,
        root: JsonObject,
        options: AvaBackupIncludeOptions,
    ): AvaBackupImportResult {
        var needsRestart = false

        root.getAsJsonObject("settings")?.let { settings ->
            val applyResult = applySettingsFromBackup(appContext, settings, options)
            if (applyResult.errors.isNotEmpty()) {
                Log.w(TAG, "Settings import warnings: ${applyResult.errors}")
            }
            needsRestart = needsRestart || applyResult.needsSatelliteRestart()
        }

        if (options.includeMods) {
            root.getAsJsonObject("mods")?.let { mods ->
                importMods(appContext, mods)
                needsRestart = true
            }
        }

        if (options.includeSystemPermissions) {
            root.getAsJsonObject("system")?.let { system ->
                importSystemPrefs(appContext, system)
            }
        }

        if (options.includeDesktopLayout) {
            root.get("desktop")?.let { desktop ->
                AvaLauncherLayoutBackup.importDesktop(appContext, desktop)
            }
        }
        if (options.includeWidgetLayout) {
            root.get("widgets")?.let { widgets ->
                AvaLauncherLayoutBackup.importWidgets(appContext, widgets)
            }
        }

        return AvaBackupImportResult(
            success = true,
            message = "success",
            needsSatelliteRestart = needsRestart,
        )
    }

    private const val AUTO_SNAPSHOT_KEEP = 3

    /**
     * Full local snapshot (settings, mods, credentials, root grants) written to
     * app-private storage before a destructive apply, so a bad import is always
     * recoverable. Best-effort: a snapshot failure must not block the apply the
     * user asked for — it is logged instead.
     */
    private fun createAutoSnapshot(context: Context, tag: String) {
        try {
            val payload = buildExportJson(
                context,
                AvaBackupIncludeOptions(
                    includeStores = AvaBackupIncludeOptions.PORTABLE_STORE_KEYS,
                    includeMods = true,
                    includeRemoteBrowserUrl = true,
                    includeSystemPermissions = true,
                    includeHaCredentials = true,
                    includeMassApiCredentials = true,
                    includeDesktopLayout = true,
                    includeWidgetLayout = true,
                ),
            )
            val dir = File(context.filesDir, "backups/auto").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(dir, "ava-$stamp-$tag.json").writeText(payload)
            dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                ?.sortedByDescending { it.name }
                ?.drop(AUTO_SNAPSHOT_KEEP)
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "auto snapshot ($tag) failed: ${e.message}")
        }
    }

    private fun readBackupRoot(context: Context, uri: Uri): JsonObject {
        val appContext = context.applicationContext
        val text = appContext.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().decodeToString()
        } ?: throw IllegalStateException("read_failed")

        val root = JsonParser.parseString(text).asJsonObject
        val format = root.get("format")?.asString
        if (format != FORMAT) {
            throw IllegalStateException("invalid_format")
        }
        return root
    }

    private fun parseStoreKeys(element: com.google.gson.JsonElement?): Set<String>? {
        if (element == null || !element.isJsonArray) return null
        return element.asJsonArray
            .mapNotNull { el ->
                el.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
            .filter { it in AvaBackupIncludeOptions.PORTABLE_STORE_KEYS }
            .toSet()
    }

    private fun parseIncludeFlags(obj: JsonObject?): AvaBackupIncludeOptions {
        val stores = parseStoreKeys(obj?.get("stores")).orEmpty()
        return AvaBackupIncludeOptions(
            includeStores = stores,
            includeMods = obj?.get("mods")?.asBoolean ?: false,
            includeRemoteBrowserUrl = obj?.get("remote_browser_url")?.asBoolean
                ?: obj?.get("auth_tokens")?.asBoolean
                ?: false,
            includeSystemPermissions = obj?.get("system_permissions")?.asBoolean ?: false,
            includeHaCredentials = obj?.get("ha_credentials")?.asBoolean ?: false,
            includeMassApiCredentials = obj?.get("mass_api_credentials")?.asBoolean ?: false,
            includeDesktopLayout = obj?.get("desktop_layout")?.asBoolean ?: false,
            includeWidgetLayout = obj?.get("widget_layout")?.asBoolean ?: false,
        )
    }

    /** Rows the sender picker showed. Falls back to packed ticks for older payloads. */
    private fun parseShownCatalog(
        includes: JsonObject?,
        selected: AvaBackupIncludeOptions,
    ): AvaBackupIncludeOptions {
        val shown = includes?.getAsJsonObject("shown") ?: return selected
        val parsed = parseIncludeFlags(shown)
        return if (parsed.hasAnyInclude() || shown.has("stores")) parsed else selected
    }

    private fun writeIncludesObject(options: AvaBackupIncludeOptions): JsonObject {
        val includes = JsonObject()
        val stores = com.google.gson.JsonArray()
        options.includeStores.sorted().forEach { stores.add(it) }
        includes.add("stores", stores)
        includes.addProperty("mods", options.includeMods)
        includes.addProperty("remote_browser_url", options.includeRemoteBrowserUrl)
        includes.addProperty("system_permissions", options.includeSystemPermissions)
        includes.addProperty("ha_credentials", options.includeHaCredentials)
        includes.addProperty("mass_api_credentials", options.includeMassApiCredentials)
        includes.addProperty("desktop_layout", options.includeDesktopLayout)
        includes.addProperty("widget_layout", options.includeWidgetLayout)
        return includes
    }

    private fun parseIncludes(root: JsonObject): AvaBackupIncludeOptions {
        val includes = root.getAsJsonObject("includes")
        val flags = parseIncludeFlags(includes)
        val backupMods = if (includes?.has("mods") == true) flags.includeMods else true
        val settings = root.getAsJsonObject("settings")
        val listedStores = parseStoreKeys(includes?.get("stores"))
        val presentStores = settings?.keySet()
            ?.filter { it in AvaBackupIncludeOptions.PORTABLE_STORE_KEYS }
            ?.toSet()
            .orEmpty()
        val includeStores = when {
            listedStores != null -> listedStores
            presentStores.isNotEmpty() -> presentStores
            else -> AvaBackupIncludeOptions.PORTABLE_STORE_KEYS
        }
        return AvaBackupIncludeOptions(
            includeStores = includeStores,
            includeMods = backupMods && root.has("mods"),
            includeRemoteBrowserUrl = flags.includeRemoteBrowserUrl &&
                settings?.getAsJsonObject("voice_satellite")?.has("haRemoteUrl") == true,
            includeSystemPermissions = flags.includeSystemPermissions && root.has("system"),
            includeHaCredentials = flags.includeHaCredentials &&
                objectHasAnyKey(settings?.getAsJsonObject("ha"), HA_SECRET_KEYS),
            includeMassApiCredentials = flags.includeMassApiCredentials &&
                objectHasAnyKey(settings?.getAsJsonObject("mass_api"), MASS_API_SECRET_KEYS),
            includeDesktopLayout = flags.includeDesktopLayout && root.has("desktop"),
            includeWidgetLayout = flags.includeWidgetLayout && root.has("widgets"),
        )
    }

    private fun buildExportJson(
        context: Context,
        options: AvaBackupIncludeOptions,
        catalog: AvaBackupIncludeOptions? = null,
    ): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", VERSION)
        root.addProperty("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))

        val includes = writeIncludesObject(options)
        if (catalog != null) {
            includes.add("shown", writeIncludesObject(catalog))
        }
        root.add("includes", includes)

        root.add("settings", exportSettings(context, options))

        if (options.includeMods) {
            root.add("mods", exportMods(context))
        }

        if (options.includeSystemPermissions) {
            root.add("system", exportSystemPrefs(context))
        }

        if (options.includeDesktopLayout) {
            root.add("desktop", AvaLauncherLayoutBackup.exportDesktop(context))
        }
        if (options.includeWidgetLayout) {
            root.add("widgets", AvaLauncherLayoutBackup.exportWidgets(context))
        }

        return gson.toJson(root)
    }

    private fun exportSettings(context: Context, options: AvaBackupIncludeOptions): JsonObject {
        // kotlinx.serialization keeps real keys under R8; Gson would emit a/b/c.
        val settings = JsonObject()
        fun addStore(key: String, value: () -> com.google.gson.JsonElement) {
            if (options.includesStore(key)) settings.add(key, value())
        }
        addStore("microphone") { kxGsonElement(runBlockingGet { MicrophoneSettingsStore(context.microphoneSettingsStore).get() }) }
        addStore("player") { kxGsonElement(runBlockingGet { PlayerSettingsStore(context.playerSettingsStore).get() }) }
        addStore("experimental") { kxGsonElement(runBlockingGet { ExperimentalSettingsStore(context).get() }) }
        addStore("sendspin") { kxGsonElement(runBlockingGet { SendspinSettingsStore(context.sendspinSettingsStore).get() }) }
        addStore("voice_channel") { kxGsonElement(runBlockingGet { VoiceChannelSettingsStore(context.voiceChannelSettingsStore).get() }) }
        addStore("screensaver") { kxGsonElement(runBlockingGet { ScreensaverSettingsStore(context.screensaverSettingsStore).get() }) }
        addStore("voice_satellite") { kxGsonElement(runBlockingGet { VoiceSatelliteSettingsStore(context.voiceSatelliteSettingsStore).get() }) }
        addStore("browser") { kxGsonElement(runBlockingGet { BrowserSettingsStore(context).get() }) }
        addStore("notification") { kxGsonElement(runBlockingGet { NotificationSettingsStore(context.notificationSettingsStore).get() }) }
        addStore("sidebar") { kxGsonElement(runBlockingGet { SidebarSettingsStore(context.sidebarSettingsStore).get() }) }
        addStore("quick_entity") { kxGsonElement(runBlockingGet { QuickEntitySettingsStore(context.quickEntitySettingsStore).get() }) }
        addStore("ha") { kxGsonElement(runBlockingGet { HaSettingsStore(context.haSettingsStore).get() }) }
        addStore("mass_api") { kxGsonElement(runBlockingGet { MassApiSettingsStore(context.massApiSettingsStore).get() }) }
        addStore("update") { kxGsonElement(runBlockingGet { UpdateSettingsStore(context.updateSettingsStore).get() }) }
        addStore("settings_style") { kxGsonElement(runBlockingGet { SettingsStyleSettingsStore(context.settingsStyleSettingsStore).get() }) }
        addStore("local_scenes") { kxGsonElement(runBlockingGet { LocalScenesStore(context.localScenesSettingsStore).get() }) }
        addStore("home_lock") {
            val current = runBlockingGet { HomeLockSettingsStore(context.homeLockSettingsStore).get() }
            kxGsonElement(
                current.copy(
                    failedAttempts = 0,
                    lockoutLevel = 0,
                    lockoutUntilEpochMs = 0L,
                ),
            )
        }
        addStore("bluetooth") {
            JsonParser.parseString(FleetBluetoothSettings.snapshot(context).toString())
        }

        // SharedPreferences that live next to the DataStore rows they belong to.
        settings.getAsJsonObject("settings_style")?.let { style ->
            style.addProperty("displayScale", DisplayScale.getScale(context))
            style.addProperty("darkMode", DarkModeManager.getInstance(context).isDarkMode())
        }
        settings.getAsJsonObject("player")?.let { player ->
            LyricDisplayRuntime.ensureLoaded(context)
            player.addProperty("lyricLeadMs", LyricDisplayRuntime.currentLeadMs())
            player.addProperty("lyricFollowStep", LyricDisplayRuntime.currentFollowStep())
        }

        // ESPHome node identity must stay device-local. Cloning macAddress/name/bluetooth
        // MAC across tablets makes HA treat them as one device (Ava#147 / #211).
        settings.getAsJsonObject("voice_satellite")?.let { vs ->
            vs.remove("macAddress")
            vs.remove("name")
            vs.remove("bluetoothMacAddress")
        }

        if (!options.includeRemoteBrowserUrl) {
            val voiceSatellite = settings.getAsJsonObject("voice_satellite")
            voiceSatellite?.remove("haRemoteUrl")
        }

        if (!options.includeHaCredentials) {
            settings.getAsJsonObject("ha")?.let { ha ->
                HA_SECRET_KEYS.forEach { ha.remove(it) }
            }
        }

        settings.getAsJsonObject("mass_api")?.let { massApi ->
            MASS_API_DEVICE_LOCAL_KEYS.forEach { massApi.remove(it) }
            if (!options.includeMassApiCredentials) {
                MASS_API_SECRET_KEYS.forEach { massApi.remove(it) }
            }
        }

        return settings
    }

    private inline fun <reified T> kxGsonElement(value: T): com.google.gson.JsonElement =
        JsonParser.parseString(json.encodeToString(value))

    private fun exportMods(context: Context): JsonObject {
        val modManager = ModManager.getInstance(context)
        runBlockingGet { modManager.refreshRegistryFromDisk() }
        val registryFile = File(context.filesDir, "mods/registry.json")
        val registry = if (registryFile.exists()) {
            gson.parseModRegistry(registryFile.readText())
        } else {
            ModRegistry()
        }

        val modsRoot = JsonObject()
        modsRoot.add("registry", gson.toJsonTree(registry))

        val configs = JsonObject()
        val states = JsonObject()
        val packages = JsonObject()

        for (installed in registry.mods) {
            val modId = installed.id
            val configFile = File(context.filesDir, "mod_configs/$modId.json")
            if (configFile.exists()) {
                configs.add(modId, JsonParser.parseString(configFile.readText()))
            }

            val stateFile = File(context.filesDir, "mod_states/$modId.json")
            if (stateFile.exists()) {
                states.add(modId, JsonParser.parseString(stateFile.readText()))
            }

            val modDir = File(context.filesDir, "mods/$modId")
            if (modDir.isDirectory) {
                val pkg = JsonObject()
                val files = JsonObject()
                modDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = file.relativeTo(modDir).path.replace('\\', '/')
                    val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    files.addProperty(relative, encoded)
                }
                pkg.add("files", files)
                packages.add(modId, pkg)
            }
        }

        modsRoot.add("configs", configs)
        modsRoot.add("states", states)
        modsRoot.add("packages", packages)
        return modsRoot
    }

    private fun exportSystemPrefs(context: Context): JsonObject {
        val system = JsonObject()
        val modPermPrefs = context.getSharedPreferences("mod_permission_requests", Context.MODE_PRIVATE).all
        system.add("mod_permission_requests", gson.toJsonTree(modPermPrefs))
        return system
    }

    private suspend fun applySettingsFromBackup(
        context: Context,
        settings: JsonObject,
        options: AvaBackupIncludeOptions,
    ): AvaSettingsApplier.ApplyResult {
        val patchMap = mutableMapOf<String, KxJsonObject>()
        AvaSettingsApplier.StoreId.entries.forEach { storeId ->
            if (!options.includesStore(storeId.jsonKey)) return@forEach
            settings.get(storeId.jsonKey)?.let { element ->
                patchMap[storeId.jsonKey] = gsonToKxJsonObject(element)
            }
        }

        // Keep local ESPHome identity even when restoring older backups that still
        // embedded macAddress/name. Omitting keys lets mergePatch preserve current values.
        patchMap["voice_satellite"]?.let { vs ->
            patchMap["voice_satellite"] = withoutClonedEspHomeIdentity(vs)
        }

        if (!options.includeRemoteBrowserUrl) {
            val currentUrl = VoiceSatelliteSettingsStore(context.voiceSatelliteSettingsStore).get().haRemoteUrl
            patchMap["voice_satellite"]?.let { vs ->
                val merged = vs.toMutableMap()
                merged["haRemoteUrl"] = JsonPrimitive(currentUrl)
                patchMap["voice_satellite"] = KxJsonObject(merged)
            }
        }

        if (!options.includeHaCredentials) {
            patchMap["ha"]?.let { ha ->
                val current = HaSettingsStore(context.haSettingsStore).get()
                patchMap["ha"] = withPreservedKeys(
                    ha,
                    mapOf(
                        "serverUrl" to current.serverUrl,
                        "accessToken" to current.accessToken,
                    ),
                )
            }
        }

        patchMap["mass_api"]?.let { massApi ->
            var next = withoutClonedMassApiCert(massApi)
            if (!options.includeMassApiCredentials) {
                val current = MassApiSettingsStore(context.massApiSettingsStore).get()
                next = withPreservedKeys(
                    next,
                    mapOf(
                        "serverUrl" to current.serverUrl,
                        "username" to current.username,
                        "password" to current.password,
                        "authToken" to current.authToken,
                    ),
                )
            }
            patchMap["mass_api"] = next
        }

        val applierResult = AvaSettingsApplier.applyPatch(context, KxJsonObject(patchMap))

        if (options.includesStore("settings_style")) {
            settings.getAsJsonObject("settings_style")?.let { style ->
                style.get("displayScale")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asFloat
                    ?.let { DisplayScale.setScale(context, it) }
                style.get("darkMode")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.let { el ->
                        val enabled = when {
                            el.asJsonPrimitive.isBoolean -> el.asBoolean
                            else -> el.asString.toBooleanStrictOrNull()
                        }
                        if (enabled != null) {
                            DarkModeManager.getInstance(context).setDarkMode(enabled)
                        }
                    }
            }
        }
        if (options.includesStore("player")) {
            settings.getAsJsonObject("player")?.let { player ->
                LyricDisplayRuntime.ensureLoaded(context)
                player.get("lyricLeadMs")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asLong
                    ?.let { LyricDisplayRuntime.setLeadMs(context, it) }
                player.get("lyricFollowStep")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asInt
                    ?.let { LyricDisplayRuntime.setFollowStep(context, it) }
            }
        }

        if (options.includesStore("browser")) {
            applyFullStoreJson(context, settings, "browser") { decoded ->
                BrowserSettingsStore(context).update {
                    json.decodeFromJsonElement(BrowserSettings.serializer(), decoded)
                }
            }
        }
        if (options.includesStore("notification")) {
            applyFullStoreJson(context, settings, "notification") { decoded ->
                NotificationSettingsStore(context.notificationSettingsStore).update {
                    json.decodeFromJsonElement(NotificationSettings.serializer(), decoded)
                }
            }
        }
        if (options.includesStore("sidebar")) {
            applyFullStoreJson(context, settings, "sidebar") { decoded ->
                SidebarSettingsStore(context.sidebarSettingsStore).update {
                    json.decodeFromJsonElement(SidebarSettings.serializer(), decoded)
                }
            }
        }
        if (options.includesStore("quick_entity")) {
            applyFullStoreJson(context, settings, "quick_entity") { decoded ->
                QuickEntitySettingsStore(context.quickEntitySettingsStore).update {
                    json.decodeFromJsonElement(QuickEntitySettings.serializer(), decoded)
                }
            }
        }

        return applierResult
    }

    private suspend fun applyFullStoreJson(
        context: Context,
        settings: JsonObject,
        key: String,
        apply: suspend (JsonElement) -> Unit,
    ) {
        if (!settings.has(key)) return
        val element = gsonToKxJsonElement(settings.get(key))
        apply(element)
    }

    private fun gsonToKxJsonObject(element: com.google.gson.JsonElement): KxJsonObject {
        return json.parseToJsonElement(element.toString()).jsonObject
    }

    /**
     * Drop ESPHome node identity (`macAddress`, `name`, `bluetoothMacAddress`) from a
     * voice_satellite settings patch. These must stay device-local so HA can tell
     * tablets apart (Ava#147) and so cloned Bluetooth scanner MACs cannot collide.
     */
    fun withoutClonedEspHomeIdentity(voiceSatellite: KxJsonObject): KxJsonObject =
        withoutKeys(voiceSatellite, ESPHOME_NODE_IDENTITY_KEYS)

    /** Drop KeyChain / PKCS#12 fields so a backup cannot transplant another tablet's cert. */
    fun withoutClonedMassApiCert(massApi: KxJsonObject): KxJsonObject =
        withoutKeys(massApi, MASS_API_DEVICE_LOCAL_KEYS)

    fun withoutHaSecrets(ha: KxJsonObject): KxJsonObject =
        withoutKeys(ha, HA_SECRET_KEYS)

    fun withoutMassApiSecrets(massApi: KxJsonObject): KxJsonObject =
        withoutKeys(massApi, MASS_API_SECRET_KEYS)

    private fun withoutKeys(obj: KxJsonObject, keys: Set<String>): KxJsonObject {
        if (keys.none { obj.containsKey(it) }) return obj
        return KxJsonObject(obj.toMutableMap().apply { keys.forEach { remove(it) } })
    }

    private fun withPreservedKeys(obj: KxJsonObject, values: Map<String, String>): KxJsonObject {
        val merged = obj.toMutableMap()
        values.forEach { (key, value) -> merged[key] = JsonPrimitive(value) }
        return KxJsonObject(merged)
    }

    private fun objectHasAnyKey(obj: JsonObject?, keys: Set<String>): Boolean {
        if (obj == null) return false
        return keys.any { key ->
            val el = obj.get(key) ?: return@any false
            el.isJsonPrimitive && el.asJsonPrimitive.isString && el.asString.isNotBlank()
        }
    }

    /** Apply a mods partition from an ava-backup payload (registry + configs + optional packages). */
    suspend fun importModsPartition(context: Context, mods: JsonObject) {
        importMods(context, mods)
    }

    private suspend fun importMods(context: Context, mods: JsonObject) {
        val modManager = ModManager.getInstance(context)
        val configStore = ModConfigStore(context)
        val modsDir = File(context.filesDir, "mods").apply { mkdirs() }

        val registry = mods.get("registry")?.let {
            gson.parseModRegistry(gson.toJson(it))
        } ?: ModRegistry()

        mods.getAsJsonObject("packages")?.entrySet()?.forEach { (modId, pkgElement) ->
            val pkg = pkgElement.asJsonObject
            val files = pkg.getAsJsonObject("files") ?: return@forEach
            val modDir = File(modsDir, modId)
            if (modDir.exists()) modDir.deleteRecursively()
            modDir.mkdirs()
            files.entrySet().forEach { (relative, b64) ->
                val target = File(modDir, relative)
                target.parentFile?.mkdirs()
                val bytes = Base64.decode(b64.asString, Base64.NO_WRAP)
                target.writeBytes(bytes)
            }
            modManager.reloadManifestFromDisk(modId)
        }

        mods.getAsJsonObject("configs")?.entrySet()?.forEach { (modId, cfg) ->
            val mapType = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            val values: Map<String, String> = gson.fromJson(cfg, mapType)
            configStore.saveConfig(modId, values)
        }

        mods.getAsJsonObject("states")?.entrySet()?.forEach { (modId, state) ->
            val stateFile = File(context.filesDir, "mod_states/$modId.json")
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(gson.toJson(state))
        }

        File(modsDir, "registry.json").writeText(gson.toJson(registry))
        modManager.refreshRegistryFromDisk()
    }

    private fun importSystemPrefs(context: Context, system: JsonObject) {
        system.getAsJsonObject("mod_permission_requests")?.entrySet()?.orEmpty()?.let { entries ->
            val editor = context.getSharedPreferences("mod_permission_requests", Context.MODE_PRIVATE).edit()
            entries.forEach { (key, value) ->
                when {
                    value.isJsonPrimitive && value.asJsonPrimitive.isBoolean ->
                        editor.putBoolean(key, value.asBoolean)
                }
            }
            editor.apply()
        }
    }

    private fun gsonToKxJsonElement(element: com.google.gson.JsonElement): JsonElement {
        return json.parseToJsonElement(element.toString())
    }

    private fun <T> runBlockingGet(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
