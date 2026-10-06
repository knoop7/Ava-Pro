package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class QuickEntitySlot(
    val entityId: String = "",
    val entityType: String = "switch",
    val icon: String = "mdi:home-assistant",
    val label: String = "",
    val size: String = "1x1",
    val color: String = "",
    /**
     * Cover-crop window inside the camera tile: 0 shows the start of the overflow
     * axis (left / top), 1 shows the end (right / bottom). Default 0.5 is centered.
     * Only the overflowing axis is used; the other stays at 0.5.
     */
    val cameraPanX: Float = 0.5f,
    val cameraPanY: Float = 0.5f,
    /**
     * 1 = cover (fill the tile, crop overflow). Pinch-out goes down to contain
     * (whole frame visible, letterbox) so the picture cannot shrink away.
     * Pinch-in goes up to 3× cover. Clamped per tile/frame at draw time.
     */
    val cameraZoom: Float = 1f,
)

@Serializable
data class QuickEntitySettings(
    val enableQuickEntity: Boolean = false,
    val enableQuickEntityDisplay: Boolean = false,
    val enableHaSlots: Boolean = false,
    val slots: List<QuickEntitySlot> = List(6) { QuickEntitySlot() },
    /**
     * Smart power-saving AOD for Quick Entity panel: after idle wait, fade in a dark cover
     * at [smartAodMaskPercent]; any interrupt fades it out and the wait restarts. Default off.
     */
    val smartAodEnabled: Boolean = false,
    /** Idle seconds before entering (or re-entering) Quick Entity smart AOD. */
    val smartAodTimeoutSeconds: Int = 60,
    /**
     * Idle AOD cover strength: 100 = fully opaque (deepest), 5 = lightest. Default 100.
     * At or below [SMART_AOD_TAP_THROUGH_MAX_PERCENT] the tiles stay legible and taps
     * pass straight through the cover; above it the first tap only wakes the panel.
     */
    val smartAodMaskPercent: Int = 100,
    /**
     * When true, long-press drag-reorder on the Quick Entity panel is disabled so
     * tiles stay put. Default unlocked.
     */
    val layoutLocked: Boolean = false,
) {
    companion object {
        /** Deepest mask (percent) at which AOD taps still act directly on the tiles. */
        const val SMART_AOD_TAP_THROUGH_MAX_PERCENT = 85
    }
}

val Context.quickEntitySettingsStore: DataStore<QuickEntitySettings> by dataStore(
    fileName = "quick_entity_settings.json",
    serializer = SettingsSerializer(QuickEntitySettings.serializer(), QuickEntitySettings()),
    corruptionHandler = defaultCorruptionHandler(QuickEntitySettings())
)

class QuickEntitySettingsStore(dataStore: DataStore<QuickEntitySettings>) :
    SettingsStoreImpl<QuickEntitySettings>(dataStore, QuickEntitySettings()) {
    
    val enableQuickEntity = SettingState(getFlow().map { it.enableQuickEntity }) { value ->
        update { it.copy(enableQuickEntity = value) }
    }
    
    val enableQuickEntityDisplay = SettingState(getFlow().map { it.enableQuickEntityDisplay }) { value ->
        update { it.copy(enableQuickEntityDisplay = value) }
    }
    
    val enableHaSlots = SettingState(getFlow().map { it.enableHaSlots }) { value ->
        update { it.copy(enableHaSlots = value) }
    }
    
    val slots = SettingState(getFlow().map { it.slots }) { value ->
        update { it.copy(slots = value) }
    }

    val smartAodEnabled = SettingState(getFlow().map { it.smartAodEnabled }) { value ->
        update { it.copy(smartAodEnabled = value) }
    }

    val smartAodTimeoutSeconds = SettingState(getFlow().map { it.smartAodTimeoutSeconds }) { value ->
        update { it.copy(smartAodTimeoutSeconds = value.coerceIn(10, 3600)) }
    }

    val smartAodMaskPercent = SettingState(getFlow().map { it.smartAodMaskPercent }) { value ->
        update { it.copy(smartAodMaskPercent = value.coerceIn(5, 100)) }
    }

    val layoutLocked = SettingState(getFlow().map { it.layoutLocked }) { value ->
        update { it.copy(layoutLocked = value) }
    }
    
    suspend fun updateSlot(index: Int, slot: QuickEntitySlot) {
        update { settings ->
            val newSlots = settings.slots.toMutableList()
            if (index in newSlots.indices) {
                newSlots[index] = slot
            }
            settings.copy(slots = newSlots)
        }
    }
    
    fun slotEntityId(index: Int) = SettingState(
        getFlow().map { it.slots.getOrNull(index)?.entityId ?: "" }
    ) { entityId ->
        update { settings ->
            val newSlots = settings.slots.toMutableList()
            if (index in newSlots.indices) {
                if (entityId.isEmpty()) {
                    newSlots[index] = QuickEntitySlot()
                } else {
                    val type = when {
                        entityId.startsWith("sensor.") || entityId.startsWith("binary_sensor.") -> "sensor"
                        entityId.startsWith("button.") || entityId.startsWith("script.") || entityId.startsWith("scene.") -> "button"
                        entityId.startsWith("timer.") -> "timer"
                        entityId.startsWith("camera.") -> "camera"
                        else -> "switch"
                    }
                    val name = entityId.substringAfter(".").lowercase()
                    val icon = guessIconFromEntityId(entityId, name)
                    val rawName = entityId.substringAfter(".")
                    val fullLabel = rawName
                        .replace("_", " ")
                        .split(" ")
                        .filter { it.isNotEmpty() }
                        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                        .trim()
                        .ifEmpty { rawName }
                    val hasChinese = fullLabel.any { it.code > 0x4E00 && it.code < 0x9FFF }
                    val maxLen = if (hasChinese) 7 else 20
                    val label = if (fullLabel.length > maxLen) fullLabel.take(maxLen) else fullLabel
                    val autoColor = if (icon.contains("lightbulb") || icon.contains("lamp") || icon.contains("ceiling-light") || icon.contains("led-strip") || icon.contains("floor-lamp") || icon.contains("brightness")) {
                        "yellow"
                    } else {
                        com.example.ava.ui.components.MdiColorMapper.stableRandomSoftColor(entityId)
                    }
                    val keepView = newSlots[index].entityId == entityId
                    newSlots[index] = newSlots[index].copy(
                        entityId = entityId,
                        entityType = type,
                        icon = icon,
                        label = label,
                        color = autoColor,
                        cameraPanX = if (keepView) newSlots[index].cameraPanX else 0.5f,
                        cameraPanY = if (keepView) newSlots[index].cameraPanY else 0.5f,
                        cameraZoom = if (keepView) newSlots[index].cameraZoom else 1f,
                    )
                }
            }
            settings.copy(slots = newSlots)
        }
    }
    
    private fun guessIconFromEntityId(entityId: String, name: String): String {
        val keywordIcons = listOf(
            "humidity" to "mdi:water-percent",
            "temperature" to "mdi:thermometer",
            "temp" to "mdi:thermometer",
            "illuminance" to "mdi:brightness-5",
            "lux" to "mdi:brightness-5",
            "power" to "mdi:flash",
            "energy" to "mdi:flash",
            "motion" to "mdi:motion-sensor",
            "occupancy" to "mdi:motion-sensor",
            "door" to "mdi:door",
            "window" to "mdi:window-closed",
            "smoke" to "mdi:smoke-detector",
            "fire" to "mdi:fire",
            "water" to "mdi:water",
            "moisture" to "mdi:water",
            "wifi" to "mdi:wifi",
            "bluetooth" to "mdi:bluetooth",
            "fan" to "mdi:fan",
            "light" to "mdi:lightbulb",
            "lamp" to "mdi:lamp",
            "bulb" to "mdi:lightbulb",
            "ceiling" to "mdi:ceiling-light",
            "led" to "mdi:led-strip",
            "floor_lamp" to "mdi:floor-lamp",
            "lock" to "mdi:lock",
            "plug" to "mdi:power-plug",
            "socket" to "mdi:power-socket",
            "camera" to "mdi:camera",
            "tv" to "mdi:television",
            "television" to "mdi:television",
            "speaker" to "mdi:speaker",
            "vacuum" to "mdi:robot-vacuum",
            "curtain" to "mdi:curtains",
            "blind" to "mdi:blinds",
            "garage" to "mdi:garage",
            "ac" to "mdi:air-conditioner",
            "air_condition" to "mdi:air-conditioner",
            "washer" to "mdi:washer",
            "washing" to "mdi:washing-machine",
            "fridge" to "mdi:fridge",
            "kettle" to "mdi:kettle",
            "bell" to "mdi:bell",
            "flower" to "mdi:flower",
            "cat" to "mdi:cat",
            "dog" to "mdi:dog",
            "shield" to "mdi:shield-check",
            "account" to "mdi:account",
            "person" to "mdi:account",
            "location" to "mdi:map-marker",
            "gps" to "mdi:map-marker"
        )
        for ((keyword, icon) in keywordIcons) {
            if (name.contains(keyword)) return icon
        }
        return when {
            entityId.startsWith("light.") -> "mdi:lightbulb"
            entityId.startsWith("switch.") -> "mdi:power"
            entityId.startsWith("fan.") -> "mdi:fan"
            entityId.startsWith("cover.") -> "mdi:blinds"
            entityId.startsWith("climate.") -> "mdi:air-conditioner"
            entityId.startsWith("button.") -> "mdi:gesture-tap-button"
            entityId.startsWith("script.") -> "mdi:gesture-tap-button"
            entityId.startsWith("scene.") -> "mdi:home"
            entityId.startsWith("camera.") -> "mdi:camera"
            entityId.startsWith("lock.") -> "mdi:lock"
            entityId.startsWith("timer.") -> "mdi:timer"
            else -> "mdi:home-assistant"
        }
    }

    suspend fun clearAllSlots() {
        update { settings ->
            settings.copy(slots = List(6) { QuickEntitySlot() })
        }
    }
}
