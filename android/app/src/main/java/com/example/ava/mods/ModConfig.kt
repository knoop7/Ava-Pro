package com.example.ava.mods

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class ModManifest(
    val id: String,
    val name: String,
    val version: String = "",
    val author: String = "",
    val description: String = "",
    @SerializedName("detail_description")
    val detailDescription: String = "",
    val icon: String = "mdi:puzzle",
    val libs: List<String>? = null,
    @SerializedName("jar_hash")
    val jarHash: String? = null,
    // Manager classes may optionally expose device-support hooks consumed by ModDeviceSupport
    // (getMinBrightness, sleepScreenForDark, wakeScreenFromDark, etc.).
    val manager: String? = null,
    /** Opt-in: mod manager may implement onVoicePipelineEvent(Context, String, Bundle). */
    @SerializedName("voice_pipeline")
    val voicePipeline: Boolean = false,
    /**
     * Opt-in: mod may claim a wake after chorus arbitration and before the HA
     * pipeline starts ([com.example.ava.mods.ModConversationEngine]).
     * Manager implements `boolean onWakeOffered(Context, Bundle)`.
     */
    @SerializedName("conversation_engine")
    val conversationEngine: Boolean = false,
    /** Opt-in: mod manager may implement bringOverlayToFrontIfActive(Context) for global overlay tint. */
    @SerializedName("overlay_z_order")
    val overlayZOrder: Boolean = false,
    /**
     * Opt-in: mod manager may implement bringOverlayToFrontIfActive(Context) for media-style
     * overlays that belong above the dashboard but below voice / notification foreground layers.
     */
    @SerializedName("overlay_below_voice")
    val overlayBelowVoice: Boolean = false,
    /**
     * Opt-in: mod manager implements playback-reference hooks for software AEC
     * ([com.example.ava.mods.ModPlaybackReference]).
     */
    @SerializedName("playback_reference")
    val playbackReference: Boolean = false,
    /**
     * Opt-in: mod owns audio routing ([com.example.ava.mods.ModAudioRouter]). The host
     * routes voice-reply (TTS) playback to the mod-chosen AudioAttributes usage and skips
     * its own STREAM_MUSIC overlay, so the mod can expose independent media / TTS / alert
     * volume controls. Manager implements `int getTtsAudioUsage(Context)`.
     */
    @SerializedName("audio_router")
    val audioRouter: Boolean = false,
    /**
     * Opt-in: mod implements ble_adv_proxy protocol for [ha-ble-adv](https://github.com/NicoIIT/ha-ble-adv).
     * Core registers ESPHome entities and scan hooks; mod owns transmit/dedup logic.
     */
    @SerializedName("ble_adv_proxy")
    val bleAdvProxy: Boolean = false,
    /**
     * When true (default for [bleAdvProxy] mods), the mod owns BLE scan/TX and Ava
     * [detect_enabled] stays off. Set false to keep the legacy unified proxy scan path.
     */
    @SerializedName("ble_adv_standalone")
    val bleAdvStandalone: Boolean? = null,
    /**
     * Opt-in: mod owns the device camera for LAN streaming (MJPEG / RTSP).
     * Ava remote camera settings stay disabled while this mod is enabled.
     */
    @SerializedName("camera_stream")
    val cameraStream: Boolean = false,
    /**
     * Opt-in: mod needs Ava accessibility (e.g. screen capture on API 30+ without
     * Shizuku/root). Surfaces Accessibility in the permission manager when the mod is enabled.
     */
    @SerializedName("needs_accessibility")
    val needsAccessibility: Boolean = false,
    val permissions: List<String>? = null,
    /**
     * Privileged / best-effort permissions (e.g. WRITE_SECURE_SETTINGS, READ_LOGS).
     * Missing optional permissions must not block mod enable; the mod should degrade.
     */
    @SerializedName("optional_permissions")
    val optionalPermissions: List<String>? = null,
    val config: List<ModConfigItem> = emptyList(),
    @SerializedName("status_panel")
    val statusPanel: List<ModStatusPanelItem> = emptyList(),
    val entities: List<ModEntity> = emptyList()
)

data class ModStatusPanelItem(
    val type: String,
    val label: String,
    val description: String = "",
    @SerializedName("detail_description")
    val detailDescription: String = "",
    @SerializedName("read")
    val read: String? = null,
    @SerializedName("press")
    val press: String? = null,
    @SerializedName("listener_id")
    val listenerId: String? = null,
    /** Optional status text for [type] download_strip (e.g. getModelStatusDisplay). */
    @SerializedName("status_read")
    val statusRead: String? = null,
    @SerializedName("status_listener_id")
    val statusListenerId: String? = null,
    /** Substrings that mean download complete when matched in status text (case-insensitive). */
    @SerializedName("ready_keywords")
    val readyKeywords: List<String>? = null,
    /** Substrings that mean an error when matched in status text (case-insensitive). */
    @SerializedName("error_keywords")
    val errorKeywords: List<String>? = null,
    /** Substrings that mean active download when matched in status text (case-insensitive). */
    @SerializedName("downloading_keywords")
    val downloadingKeywords: List<String>? = null,
    /** Config keys rendered below the strip in the same card (e.g. recognition_language). */
    @SerializedName("inline_config_keys")
    val inlineConfigKeys: List<String>? = null,
    /** UI layout: [progress_primary] (large %) or default compact strip. */
    val layout: String? = null,
    /** Substrings that mean paused when matched in status text (case-insensitive). */
    @SerializedName("paused_keywords")
    val pausedKeywords: List<String>? = null,
    @SerializedName("actions")
    val actions: List<ModStatusPanelAction>? = null,
)

data class ModStatusPanelAction(
    val label: String = "",
    @SerializedName("press")
    val press: String,
    @SerializedName("style")
    val style: String? = null,
    /** Visible in these phases: idle, downloading, paused, ready, error. Empty = always. */
    @SerializedName("show_when")
    val showWhen: List<String>? = null,
)

fun ModStatusPanelAction.normalized(): ModStatusPanelAction = copy(
    label = label.orEmptyMod(),
    press = press.orEmptyMod(),
    showWhen = showWhen.orEmptyModList().map { it.orEmptyMod() }.filter { it.isNotBlank() },
)

fun ModStatusPanelItem.normalized(): ModStatusPanelItem = copy(
    type = type.orEmptyMod(),
    label = label.orEmptyMod(),
    description = description.orEmptyMod(),
    detailDescription = detailDescription.orEmptyMod(),
    read = read?.orEmptyMod()?.takeIf { it.isNotBlank() },
    press = press?.orEmptyMod()?.takeIf { it.isNotBlank() },
    listenerId = listenerId?.orEmptyMod()?.takeIf { it.isNotBlank() },
    statusRead = statusRead?.orEmptyMod()?.takeIf { it.isNotBlank() },
    statusListenerId = statusListenerId?.orEmptyMod()?.takeIf { it.isNotBlank() },
    readyKeywords = readyKeywords.orEmptyModList().map { it.orEmptyMod() }.filter { it.isNotBlank() },
    errorKeywords = errorKeywords.orEmptyModList().map { it.orEmptyMod() }.filter { it.isNotBlank() },
    downloadingKeywords = downloadingKeywords.orEmptyModList().map { it.orEmptyMod() }.filter { it.isNotBlank() },
    inlineConfigKeys = inlineConfigKeys.orEmptyModList().map { it.orEmptyMod() }.filter { it.isNotBlank() },
    layout = layout?.orEmptyMod()?.takeIf { it.isNotBlank() },
    pausedKeywords = pausedKeywords.orEmptyModList().map { it.orEmptyMod() }.filter { it.isNotBlank() },
    actions = actions.orEmptyModList().mapNotNull { action ->
        val normalized = action.normalized()
        normalized.takeIf { it.press.isNotBlank() }
    },
)

data class ModConfigItem(
    val type: String,
    val key: String,
    val label: String,
    val description: String = "",
    val dialogHint: String = "",
    val placeholder: String = "",
    val defaultValue: String? = null,
    val enabledWhen: String? = null,
    val options: List<String>? = null,
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null
)

data class ModEntity(
    val type: String,
    val id: String,
    val name: String,
    val icon: String = "",
    val category: String? = null,
    val enabledWhen: String? = null,
    val enabledByConfig: String? = null,
    val on: String? = null,
    val off: String? = null,
    @SerializedName("class")
    val deviceClass: String? = null,
    val gpio: Int? = null,
    val unit: String? = null,
    @SerializedName("accuracy_decimals")
    val accuracyDecimals: Int? = null,
    val options: List<String>? = null,
    val read: String? = null,
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
    val mode: String? = null,
    @SerializedName("refresh_interval_ms")
    val refreshIntervalMs: Long? = null,
    val set: String? = null,
    val press: String? = null
)

data class ModStore(
    val version: Int = 1,
    val baseUrl: String = "",
    val mods: List<StoreMod> = emptyList()
)

data class StoreMod(
    val id: String,
    val name: String,
    val version: String = "",
    val author: String = "",
    val description: String = "",
    @SerializedName("detail_description")
    val detailDescription: String = "",
    val path: String,
    @SerializedName("jar_hash")
    val jarHash: String? = null
)

/** Gson may deserialize omitted JSON string fields as null instead of Kotlin defaults. */
fun String?.orEmptyMod(): String = this ?: ""

private fun <T> List<T>?.orEmptyModList(): List<T> = this ?: emptyList()

fun StoreMod.normalized(): StoreMod? {
    val safeId = id.orEmptyMod()
    val safePath = path.orEmptyMod()
    if (safeId.isBlank() || safePath.isBlank()) return null
    return copy(
        id = safeId,
        name = name.orEmptyMod().ifBlank { safeId },
        version = version.orEmptyMod(),
        author = author.orEmptyMod(),
        description = description.orEmptyMod(),
        detailDescription = detailDescription.orEmptyMod(),
        path = safePath,
    )
}

fun ModManifest.normalized(): ModManifest = copy(
    id = id.orEmptyMod(),
    name = name.orEmptyMod().ifBlank { id.orEmptyMod() },
    version = version.orEmptyMod(),
    author = author.orEmptyMod(),
    description = description.orEmptyMod(),
    detailDescription = detailDescription.orEmptyMod(),
    icon = icon.orEmptyMod().ifBlank { "mdi:puzzle" },
    config = config.orEmptyModList(),
    statusPanel = statusPanel.orEmptyModList().map { it.normalized() },
    entities = entities.orEmptyModList(),
)

fun InstalledMod.normalized(): InstalledMod? {
    val safeId = id.orEmptyMod()
    if (safeId.isBlank()) return null
    return copy(
        id = safeId,
        version = version.orEmptyMod(),
    )
}

fun ModRegistry.normalized(): ModRegistry = copy(
    mods = mods.orEmptyModList().mapNotNull { it.normalized() },
)

fun ModStore.normalized(): ModStore = copy(
    baseUrl = baseUrl.orEmptyMod(),
    mods = mods.orEmptyModList().mapNotNull { it.normalized() },
)

fun Gson.parseModManifest(json: String): ModManifest =
    fromJson(json, ModManifest::class.java).normalized()

fun Gson.parseModStore(json: String): ModStore =
    fromJson(json, ModStore::class.java).normalized()

fun Gson.parseModRegistry(json: String): ModRegistry =
    fromJson(json, ModRegistry::class.java).normalized()

data class InstalledMod(
    val id: String,
    val version: String,
    val enabled: Boolean = true,
    val installedAt: Long = System.currentTimeMillis(),
    /** True when installed via local zip import — not governed by store.json updates. */
    val fromLocalImport: Boolean = false,
)

data class ModRegistry(
    val version: Int = 1,
    val mods: List<InstalledMod> = emptyList()
)
