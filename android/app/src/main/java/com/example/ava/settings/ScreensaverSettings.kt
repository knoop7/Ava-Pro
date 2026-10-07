package com.example.ava.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import java.util.Locale
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DawnEntitySlot(
    val entityId: String = "",
    val icon: String = "mdi:home-assistant",
    val label: String = ""
)

/**
 * Dawn magazine slot entity domains (strict).
 * Anything else (person/climate/…) must be rejected so the UI cannot render junk.
 */
private val DAWN_SLOT_ALLOWED_PREFIXES = arrayOf(
    "sensor.",
    "binary_sensor.",
    "light.",
    "switch.",
    "weather.",
)

/**
 * Empty is allowed (clears the slot). Non-empty must use an allowed domain prefix
 * (`sensor.` / `binary_sensor.` / `light.` / `switch.` / `weather.`).
 */
fun isAllowedDawnSlotEntityId(entityId: String): Boolean {
    val id = entityId.trim().lowercase(Locale.ROOT)
    if (id.isEmpty()) return true
    val prefix = DAWN_SLOT_ALLOWED_PREFIXES.firstOrNull { id.startsWith(it) } ?: return false
    // Need a non-empty object id after the domain (reject bare "sensor.").
    return id.length > prefix.length
}

/**
 * True while the user is still typing a possibly-valid id
 * (domain stub, or allowed domain with empty/partial object id).
 * Used only for UI — persistence still requires [isAllowedDawnSlotEntityId].
 */
fun isPlausibleDawnSlotEntityPrefix(entityId: String): Boolean {
    val id = entityId.trim().lowercase(Locale.ROOT)
    if (id.isEmpty()) return false
    if (!id.contains('.')) {
        return listOf("sensor", "binary_sensor", "light", "switch", "weather")
            .any { it.startsWith(id) }
    }
    // e.g. "sensor." / "binary_sensor.abc" while finishing the object id
    return DAWN_SLOT_ALLOWED_PREFIXES.any { id.startsWith(it) }
}

/** Normalize a slot: wipe entity/icon/label when the id domain is not allowed. */
fun sanitizeDawnEntitySlot(slot: DawnEntitySlot): DawnEntitySlot {
    val id = slot.entityId.trim()
    if (id.isEmpty()) return DawnEntitySlot()
    if (!isAllowedDawnSlotEntityId(id)) return DawnEntitySlot()
    return slot.copy(entityId = id)
}

/** Empty / whitespace-only wallpaper source means “use built-in default”. */
fun sanitizeDawnWallpaperSourceUrl(raw: String?): String = raw?.trim().orEmpty()

/**
 * Screensaver idle timeout clamp, shared by the settings UI, HA number entity and
 * fleet/intent appliers: 0 (and anything below) = never auto-show on idle
 * (issue #174, Fully-Kiosk-style); otherwise the classic 10..3600 range.
 * 0 only silences the idle timer — the HA `screensaver_display` switch can still
 * force-show manually.
 */
fun normalizeScreensaverTimeoutSeconds(value: Int): Int =
    if (value <= 0) 0 else value.coerceIn(10, 3600)

/**
 * Weather strip entity for Dawn magazine.
 *
 * Prefer the dedicated [ScreensaverSettings.dawnWeatherEntityId] when it is `weather.*`.
 * Otherwise use the first `weather.*` found among the 4 capsule slots.
 * Non-weather IDs never become the hourly strip.
 */
fun resolveDawnWeatherEntityId(settings: ScreensaverSettings): String {
    val dedicated = settings.dawnWeatherEntityId.trim()
    if (dedicated.startsWith("weather.")) return dedicated
    for (slot in settings.dawnEntitySlots) {
        val id = slot.entityId.trim()
        if (id.startsWith("weather.") && isAllowedDawnSlotEntityId(id)) return id
    }
    return ""
}

/** Capsule slots must not render weather.* — those belong on the bottom hourly strip. */
fun isDawnWeatherEntityId(entityId: String): Boolean =
    entityId.trim().startsWith("weather.")

/**
 * Infer a capsule icon from an HA entity id.
 *
 * Rules (order matters):
 * 1. Clear domains win (`light.` → bulb, never a sensor metric).
 * 2. Measurement keywords (temp / humidity / lux…) win over the bare word "light".
 * 3. For `sensor.*`, "light" means illuminance (`mdi:brightness-5`), not a bulb.
 * 4. Bulb only for `light.*` / obvious fixture names / light-like `switch.*`.
 */
fun guessDawnSlotIcon(entityId: String, displayHint: String = ""): String {
    val id = entityId.trim().lowercase(Locale.ROOT)
    if (id.isEmpty()) return "mdi:home-assistant"
    val domain = id.substringBefore('.', missingDelimiterValue = "")
    val objectId = id.substringAfter('.', missingDelimiterValue = id)
    // Friendly name / label often carries temperature · humidity when the object_id does not.
    val hint = displayHint.trim().lowercase(Locale.ROOT)
    val haystack = "$objectId $hint"

    when (domain) {
        "light" -> return "mdi:lightbulb"
        "fan" -> return "mdi:fan"
        "lock" -> return "mdi:lock"
        "person", "device_tracker" -> return "mdi:account"
        "climate" -> return "mdi:thermometer"
        "weather" -> return "mdi:thermometer"
        "cover" -> {
            return if (objectId.contains("garage") || objectId.contains("gate")) {
                "mdi:garage"
            } else {
                "mdi:blinds"
            }
        }
        "switch" -> {
            // Temperature-named switches (e.g. label "HOME temperature") are not power outlets.
            if (isTemperatureHint(haystack)) return "mdi:thermometer"
            if (isHumidityHint(haystack)) return "mdi:water-percent"
            // Many HA setups expose wall lights as switch.*_light — treat as bulb,
            // but never confuse with sensor illuminance (different domain).
            return if (isLightFixtureName(objectId) || isSwitchLightName(objectId)) {
                "mdi:lightbulb"
            } else {
                "mdi:power"
            }
        }
    }

    // Sensor metrics — always before any "light" → bulb fallback.
    // Also honor Chinese/English hints from friendly_name.
    when {
        isTemperatureHint(haystack) ||
            objectId.endsWith("_temp") || objectId.endsWith(".temp") ||
            objectId == "temp" -> return "mdi:thermometer"

        isHumidityHint(haystack) -> return "mdi:water-percent"

        containsToken(
            objectId,
            "illuminance", "illumination", "lux", "亮度", "光照", "光感",
            "light_level", "lightlevel", "light_intensity",
        ) -> return "mdi:brightness-5"

        // sensor.xxx_light / sensor.light → lux-style, NOT a lightbulb
        (domain == "sensor" || domain == "binary_sensor") &&
            isIlluminanceStyleLightName(objectId) -> return "mdi:brightness-5"

        containsToken(objectId, "battery", "电池") -> return "mdi:battery"
        containsToken(objectId, "solar", "pv", "光伏") -> return "mdi:solar-power"
        containsToken(objectId, "power", "watt", "功率") &&
            !objectId.contains("powerwall") -> return "mdi:flash"
        containsToken(objectId, "energy", "电量") &&
            !objectId.contains("battery") -> return "mdi:flash"
        containsToken(objectId, "motion", "occupancy", "pir", "人体", "移动") ->
            return "mdi:motion-sensor"
        containsToken(objectId, "garage", "gate") -> return "mdi:garage"
        containsToken(objectId, "lock", "锁") -> return "mdi:lock"
        containsToken(objectId, "window", "窗") -> return "mdi:window-closed"
        containsToken(objectId, "door", "门") && !objectId.contains("garage") ->
            return "mdi:door"
        containsToken(objectId, "curtain", "窗帘") -> return "mdi:curtains"
        containsToken(objectId, "blind", "百叶") -> return "mdi:blinds"
        containsToken(objectId, "fan", "风扇") -> return "mdi:fan"
    }

    // Fixture-style names only (bulb/lamp/…); bare "light" on sensors already handled.
    if (isLightFixtureName(objectId)) return "mdi:lightbulb"

    return when (domain) {
        "binary_sensor" -> "mdi:door"
        "sensor", "number" -> "mdi:eye"
        else -> "mdi:home-assistant"
    }
}

private fun containsToken(objectId: String, vararg tokens: String): Boolean {
    for (token in tokens) {
        if (token.isEmpty()) continue
        if (objectId.contains(token)) return true
    }
    return false
}

private fun isTemperatureHint(text: String): Boolean =
    containsToken(text, "temperature", "température", "温度", "气温", "体温")

private fun isHumidityHint(text: String): Boolean =
    containsToken(text, "humidity", "湿度")

/** Names that clearly mean a lamp/fixture, not an illuminance sensor. */
private fun isLightFixtureName(objectId: String): Boolean {
    if (containsToken(
            objectId,
            "lightbulb", "bulb", "lamp", "chandelier", "sconce",
            "灯带", "台灯", "吊灯", "壁灯", "筒灯", "射灯", "灯泡",
        )
    ) {
        return true
    }
    // Chinese bare "light", but not illuminance/brightness sensors
    if (objectId.contains("灯") &&
        !objectId.contains("光照") &&
        !objectId.contains("亮度") &&
        !objectId.contains("光感")
    ) {
        return true
    }
    return false
}

/**
 * `sensor` object_ids where "light" means brightness/illuminance
 * (e.g. sensor.living_light, sensor.light_level) — not a controllable bulb.
 */
private fun isIlluminanceStyleLightName(objectId: String): Boolean {
    if (isLightFixtureName(objectId)) return false
    if (objectId == "light" || objectId.startsWith("light_") || objectId.endsWith("_light")) {
        return true
    }
    // token boundary: *_light_* or light as whole segment
    return Regex("""(^|_)light($|_)""").containsMatchIn(objectId)
}

/** `switch.*` names that are clearly lighting loads. */
private fun isSwitchLightName(objectId: String): Boolean {
    if (containsToken(objectId, "灯") &&
        !objectId.contains("光照") &&
        !objectId.contains("亮度")
    ) {
        return true
    }
    return objectId == "light" ||
        objectId.startsWith("light_") ||
        objectId.endsWith("_light") ||
        Regex("""(^|_)light($|_)""").containsMatchIn(objectId)
}

@Serializable
data class ScreensaverSettings(
    val enabled: Boolean = false,
    val visible: Boolean = true,
    val enableHaDisplay: Boolean = false,
    /**
     * When [enableHaDisplay] is on: keep HA `screensaver_display` aligned with whether
     * the screensaver is actually showing. Default off (control-only switch).
     * User dismiss turns the switch off, but idle timeout may show again and turns it back on.
     */
    val haSwitchTwoWayEnabled: Boolean = false,
    val screensaverUrl: String = "https://flipflow.neverup.cn/clock.html",
    val screensaverUrlVisible: Boolean = false,
    /** Dawn · Magazine Screensaver master switch. SerialName keeps existing installs. */
    @SerialName("xiaomiWallpaperEnabled")
    val dawnWallpaperEnabled: Boolean = false,
    /**
     * When Dawn magazine is on: show up to 4 HA entity capsules (dashboard layout)
     * instead of the classic bottom-left poem composition. Default off.
     */
    @SerialName("enableXiaomiEntitySlots")
    val enableDawnEntitySlots: Boolean = false,
    /**
     * When true (and Dawn slots on): expose 4 HA text entities
     * `dawn_entity_slot_N` (legacy wire id `xiaomi_entity_slot_N`) for quick entity-ID writes.
     * Default off — entities appear only after Voice Satellite restart (not mid-run).
     */
    @SerialName("enableXiaomiEntityHaSlots")
    val enableDawnEntityHaSlots: Boolean = false,
    @SerialName("xiaomiEntitySlots")
    val dawnEntitySlots: List<DawnEntitySlot> = List(4) { DawnEntitySlot() },
    /**
     * Optional dedicated weather entity for the Dawn magazine hourly strip.
     * Empty (default) = hide weather chrome. Does **not** reuse PlayerSettings.haWeatherEntity.
     */
    @SerialName("xiaomiWeatherEntityId")
    val dawnWeatherEntityId: String = "",
    /**
     * Optional custom wallpaper source for Dawn magazine.
     * Empty (default) = built-in random wallpaper API. Whitespace-only is treated as empty.
     */
    val dawnWallpaperSourceUrl: String = "",
    /**
     * When true: Dawn slots clock uses 24-hour ISO-style time (00–23, no AM/PM).
     * Default off → 12-hour with AM/PM.
     */
    val dawnIsoTimeEnabled: Boolean = false,
    /**
     * When true: merge 3+ consecutive identical hourly weather icons into one segment.
     * Default off → one icon cell per hour.
     */
    val dawnMergeWeatherIconsEnabled: Boolean = false,
    val timeoutSeconds: Int = 300,
    /**
     * When true (and screensaver enabled), expose [timeoutSeconds] as HA number
     * `screensaver_timeout` under the Config entity category. Default off.
     */
    val screensaverTimeoutVisible: Boolean = false,
    val darkOffEnabled: Boolean = false,
    /** Whole-layer ±2–8px translation while idle screensaver is visible. Default off. */
    val pixelShiftEnabled: Boolean = false,
    /**
     * Static dark AOD cover over the Web screensaver at [smartAodMaskPercent] strength.
     * No idle timer or fade. Independent of Simple Clock AOD. Default off.
     */
    val smartAodEnabled: Boolean = false,
    /**
     * Screensaver AOD cover strength: 100 = fully opaque, 5 = lightest. Default 100.
     */
    val smartAodMaskPercent: Int = 100,
    /**
     * Root only: while idle screensaver is visible, switch CPU governors to powersave;
     * restore on hide / wake. Default off. UI hidden when root is unavailable.
     */
    val smartCpuThrottleEnabled: Boolean = false,
    /**
     * Frame-diff while HA video recording is active (IBM-style):
     * motion dismisses screensaver; quiet ~5–8s means left, then idle may start.
     * Default off. UI when video recording capability is available.
     */
    val personWakeEnabled: Boolean = false,
    /**
     * When true (default): notification scenes stay on top of the screensaver.
     * When false: showing a notification dismisses the screensaver.
     */
    val keepOnOverlays: Boolean = true,
    /**
     * Pause screensaver display while another app owns the process foreground.
     * Default off — does not gate vinyl / floating-window / notification z-order
     * (those soft-pause or raise themselves independently).
     */
    val backgroundPauseEnabled: Boolean = false,
    val motionOnEnabled: Boolean = false,
    /**
     * When the panel rises from off to on (HA display switch, not a touch or
     * proximity wake), show the magazine or custom page immediately.
     * Default off. Idle timeout 0 does not block this path.
     */
    val showAfterScreenOn: Boolean = false
)

val Context.screensaverSettingsStore: DataStore<ScreensaverSettings> by dataStore(
    fileName = "screensaver_settings.json",
    serializer = SettingsSerializer(ScreensaverSettings.serializer(), ScreensaverSettings()),
    corruptionHandler = defaultCorruptionHandler(ScreensaverSettings())
)

class ScreensaverSettingsStore(dataStore: DataStore<ScreensaverSettings>) :
    SettingsStoreImpl<ScreensaverSettings>(dataStore, ScreensaverSettings()) {
    val enabled =
        SettingState(getFlow().map { it.enabled }) { value -> update { it.copy(enabled = value) } }
    val visible =
        SettingState(getFlow().map { it.visible }) { value -> update { it.copy(visible = value) } }
    val enableHaDisplay =
        SettingState(getFlow().map { it.enableHaDisplay }) { value -> update { it.copy(enableHaDisplay = value) } }
    val haSwitchTwoWayEnabled =
        SettingState(getFlow().map { it.haSwitchTwoWayEnabled }) { value ->
            update { it.copy(haSwitchTwoWayEnabled = value) }
        }
    val screensaverUrl =
        SettingState(getFlow().map { it.screensaverUrl }) { value -> update { it.copy(screensaverUrl = value) } }
    val timeoutSeconds =
        SettingState(getFlow().map { it.timeoutSeconds }) { value ->
            update { it.copy(timeoutSeconds = normalizeScreensaverTimeoutSeconds(value)) }
        }
    val screensaverTimeoutVisible =
        SettingState(getFlow().map { it.screensaverTimeoutVisible }) { value ->
            update { it.copy(screensaverTimeoutVisible = value) }
        }
    val darkOffEnabled =
        SettingState(getFlow().map { it.darkOffEnabled }) { value -> update { it.copy(darkOffEnabled = value) } }
    val pixelShiftEnabled =
        SettingState(getFlow().map { it.pixelShiftEnabled }) { value ->
            update { it.copy(pixelShiftEnabled = value) }
        }
    val smartAodEnabled =
        SettingState(getFlow().map { it.smartAodEnabled }) { value ->
            update { it.copy(smartAodEnabled = value) }
        }
    val smartAodMaskPercent =
        SettingState(getFlow().map { it.smartAodMaskPercent }) { value ->
            update { it.copy(smartAodMaskPercent = value.coerceIn(5, 100)) }
        }
    val smartCpuThrottleEnabled =
        SettingState(getFlow().map { it.smartCpuThrottleEnabled }) { value ->
            update { it.copy(smartCpuThrottleEnabled = value) }
        }
    val personWakeEnabled =
        SettingState(getFlow().map { it.personWakeEnabled }) { value ->
            update { it.copy(personWakeEnabled = value) }
        }
    val keepOnOverlays =
        SettingState(getFlow().map { it.keepOnOverlays }) { value ->
            update { it.copy(keepOnOverlays = value) }
        }
    val backgroundPauseEnabled =
        SettingState(getFlow().map { it.backgroundPauseEnabled }) { value ->
            update { it.copy(backgroundPauseEnabled = value) }
        }
    val motionOnEnabled =
        SettingState(getFlow().map { it.motionOnEnabled }) { value -> update { it.copy(motionOnEnabled = value) } }
    val showAfterScreenOn =
        SettingState(getFlow().map { it.showAfterScreenOn }) { value ->
            update { it.copy(showAfterScreenOn = value) }
        }
    val screensaverUrlVisible =
        SettingState(getFlow().map { it.screensaverUrlVisible }) { value -> update { it.copy(screensaverUrlVisible = value) } }
    val dawnWallpaperEnabled =
        SettingState(getFlow().map { it.dawnWallpaperEnabled }) { value -> update { it.copy(dawnWallpaperEnabled = value) } }
    val enableDawnEntitySlots =
        SettingState(getFlow().map { it.enableDawnEntitySlots }) { value ->
            update {
                it.copy(
                    enableDawnEntitySlots = value,
                    // HA text entities only make sense while capsules are on.
                    enableDawnEntityHaSlots = if (value) it.enableDawnEntityHaSlots else false,
                )
            }
        }
    val enableDawnEntityHaSlots =
        SettingState(getFlow().map { it.enableDawnEntityHaSlots }) { value ->
            update { it.copy(enableDawnEntityHaSlots = value) }
        }
    val dawnEntitySlots =
        SettingState(getFlow().map { it.dawnEntitySlots }) { value ->
            update {
                val normalized = value.map { slot -> sanitizeDawnEntitySlot(slot) }.toMutableList()
                while (normalized.size < 4) normalized.add(DawnEntitySlot())
                it.copy(dawnEntitySlots = normalized.take(4))
            }
        }
    val dawnWeatherEntityId =
        SettingState(getFlow().map { it.dawnWeatherEntityId }) { value ->
            val trimmed = value.trim()
            update {
                it.copy(
                    dawnWeatherEntityId = if (trimmed.startsWith("weather.")) trimmed else ""
                )
            }
        }
    val dawnWallpaperSourceUrl =
        SettingState(getFlow().map { it.dawnWallpaperSourceUrl }) { value ->
            update { it.copy(dawnWallpaperSourceUrl = sanitizeDawnWallpaperSourceUrl(value)) }
        }
    val dawnIsoTimeEnabled =
        SettingState(getFlow().map { it.dawnIsoTimeEnabled }) { value ->
            update { it.copy(dawnIsoTimeEnabled = value) }
        }
    val dawnMergeWeatherIconsEnabled =
        SettingState(getFlow().map { it.dawnMergeWeatherIconsEnabled }) { value ->
            update { it.copy(dawnMergeWeatherIconsEnabled = value) }
        }

    suspend fun updateDawnEntitySlot(index: Int, slot: DawnEntitySlot) {
        update { settings ->
            val newSlots = settings.dawnEntitySlots.toMutableList()
            while (newSlots.size < 4) newSlots.add(DawnEntitySlot())
            if (index in newSlots.indices) {
                newSlots[index] = sanitizeDawnEntitySlot(slot)
            }
            settings.copy(dawnEntitySlots = newSlots.take(4))
        }
    }

    /**
     * HA text-entity binding for one Xiaomi capsule slot (same idea as Simple Clock
     * [PlayerSettingsStore.statusSlotEntityId]): writing an entity ID auto-fills icon.
     * Rejects domains outside sensor / binary_sensor / light / switch / weather.
     */
    fun dawnSlotEntityId(index: Int) = SettingState(
        getFlow().map { it.dawnEntitySlots.getOrNull(index)?.entityId ?: "" }
    ) { entityId ->
        update { settings ->
            val newSlots = settings.dawnEntitySlots.toMutableList()
            while (newSlots.size < 4) newSlots.add(DawnEntitySlot())
            if (index !in newSlots.indices) return@update settings
            val trimmed = entityId.trim()
            if (trimmed.isEmpty()) {
                newSlots[index] = DawnEntitySlot()
            } else if (!isAllowedDawnSlotEntityId(trimmed)) {
                Log.w("DawnSlots", "Rejected Dawn slot entity id (domain not allowed): $trimmed")
                // Keep previous slot — do not accept arbitrary domains from HA text entities.
                return@update settings
            } else {
                val icon = guessDawnSlotIcon(trimmed)
                val previous = newSlots[index]
                newSlots[index] = DawnEntitySlot(
                    entityId = trimmed,
                    icon = if (previous.icon.isNotBlank() &&
                        previous.icon != "mdi:home-assistant" &&
                        previous.entityId == trimmed
                    ) previous.icon else icon,
                    label = ""
                )
            }
            settings.copy(dawnEntitySlots = newSlots.take(4))
        }
    }
}
