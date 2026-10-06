package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
enum class SidebarPosition {
    LEFT,
    RIGHT
}

@Serializable
enum class SidebarItemKey {
    /** Returns to the home screen. Sits above Device Control. */
    Home,
    /** Jumps straight into the Device Control page (restart / exit / pin to wall). */
    DeviceControl,
    /** Cursor trackpad overlay (One Hand Control-style remote). */
    TouchPad,
    VoiceMessage,
    Browser,
    Weather,
    SimpleClock,
    DreamClock,
    QuickEntity,
    /** HA `vinyl_cover_display` — expanded music overlay show/hide. */
    VinylCoverDisplay,
    HomeLock,
    Camera,
    MuteMicrophone,
    DarkMode
}

/**
 * Cap on a user-supplied sidebar label. The rows are one line of a narrow drawer, so a
 * long name wraps and pushes the whole list around; the input rejects it up front rather
 * than letting the layout deform after saving.
 */
const val SIDEBAR_ITEM_NAME_MAX_LENGTH = 16

/** Runtime switch rows in the home sidebar (below the entry/action section). */
val SIDEBAR_SWITCH_ITEM_KEYS: Set<SidebarItemKey> = setOf(
    SidebarItemKey.Camera,
    SidebarItemKey.MuteMicrophone,
    SidebarItemKey.DarkMode,
)

val DEFAULT_SIDEBAR_ITEM_ORDER: List<SidebarItemKey> = listOf(
    SidebarItemKey.Home,
    SidebarItemKey.DeviceControl,
    SidebarItemKey.TouchPad,
    SidebarItemKey.DarkMode,
    SidebarItemKey.VoiceMessage,
    SidebarItemKey.Browser,
    SidebarItemKey.Weather,
    SidebarItemKey.SimpleClock,
    SidebarItemKey.DreamClock,
    SidebarItemKey.QuickEntity,
    SidebarItemKey.VinylCoverDisplay,
    SidebarItemKey.HomeLock,
    SidebarItemKey.Camera,
    SidebarItemKey.MuteMicrophone,
)

/** Bottom dock pair. Separate from [SidebarItemKey] order: these two only swap with each other. */
@Serializable
enum class SidebarDockKey {
    Home,
    Settings,
}

/** Home-screen corner label. Home shows the home name; Service shows the live status and toggles it. The settings icon stays. */
@Serializable
enum class HomeCornerButton {
    SETTINGS,
    SERVICE,
}

val DEFAULT_SIDEBAR_DOCK_ORDER: List<SidebarDockKey> = listOf(
    SidebarDockKey.Home,
    SidebarDockKey.Settings,
)

@Serializable
data class SidebarSettings(
    val enableSidebar: Boolean = true,
    val sidebarPosition: SidebarPosition = SidebarPosition.LEFT,
    val showDeviceControl: Boolean = false,
    val showTouchPad: Boolean = false,
    val showVoiceMessage: Boolean = false,
    val showBrowser: Boolean = false,
    val showWeather: Boolean = false,
    val showSimpleClock: Boolean = false,
    val showDreamClock: Boolean = false,
    val showQuickEntity: Boolean = false,
    val showVinylCoverDisplay: Boolean = false,
    val showHomeLock: Boolean = true,
    val showCamera: Boolean = false,
    val showMuteMicrophone: Boolean = false,
    val showDarkMode: Boolean = false,
    /** On when the key is absent, so existing installs gain the row. */
    val showHome: Boolean = true,
    /**
     * Hides the drawer header as one bar: the menu title and the service-status
     * line. Off when the key is absent, so existing installs keep both.
     */
    val hideSidebarHeader: Boolean = false,
    /** Corner button on Home. Absent keys stay on Settings. */
    val homeCornerButton: HomeCornerButton = HomeCornerButton.SETTINGS,
    val hideHomeHeader: Boolean = false,
    val itemOrder: List<SidebarItemKey> = DEFAULT_SIDEBAR_ITEM_ORDER,
    /** Home / Settings footer order. Missing on old files stays Home then Settings. */
    val dockOrder: List<SidebarDockKey> = DEFAULT_SIDEBAR_DOCK_ORDER,
    /**
     * Per-item label overrides, keyed by [SidebarItemKey.name]. Absent means "use the
     * translated default". Stored by name rather than by enum so retiring an item later
     * leaves a harmless orphan entry instead of failing to deserialize.
     */
    val itemNames: Map<String, String> = emptyMap()
)

/**
 * Seed when `sidebar_settings.json` does not exist yet (fresh install).
 *
 * [SidebarSettings] field defaults stay off: SettingsSerializer encodes with
 * `encodeDefaults=false`, so an existing file that omitted these keys must keep
 * them off on upgrade. Only the DataStore empty-file default uses this seed.
 */
val NEW_USER_SIDEBAR_SETTINGS = SidebarSettings(
    showHome = true,
    showDeviceControl = true,
    showTouchPad = true,
    showDarkMode = true,
    itemOrder = DEFAULT_SIDEBAR_ITEM_ORDER,
)

/** Footer 「设置」 label, stored beside item names without becoming a list row. */
const val SIDEBAR_SETTINGS_LABEL_KEY = "Settings"

/** User-chosen label for [key], or null to fall back to the built-in translation. */
fun SidebarSettings.customItemName(key: SidebarItemKey): String? =
    itemNames[key.name]?.takeIf { it.isNotBlank() }

/** User-chosen footer label, or null to fall back to the built-in translation. */
fun SidebarSettings.customLabel(storageKey: String): String? =
    itemNames[storageKey]?.takeIf { it.isNotBlank() }

fun sidebarDockOrderOrDefault(order: List<SidebarDockKey>?): List<SidebarDockKey> {
    val known = SidebarDockKey.entries
    val filtered = order.orEmpty().filter { it in known }.distinct()
    if (filtered.isEmpty()) return DEFAULT_SIDEBAR_DOCK_ORDER
    val missing = known.filter { it !in filtered }
    return filtered + missing
}

fun sidebarItemOrderOrDefault(order: List<SidebarItemKey>?): List<SidebarItemKey> {
    if (order.isNullOrEmpty()) return DEFAULT_SIDEBAR_ITEM_ORDER
    val known = SidebarItemKey.entries.toSet()
    val filtered = order.filter { it in known }
    val missing = SidebarItemKey.entries.filter { it !in filtered }
    val withMissing = if (missing.isEmpty()) {
        filtered
    } else {
        val result = filtered.toMutableList()
        for (key in missing) {
            insertSidebarItemDefault(result, key)
        }
        result
    }
    return placeHomeLockInEntrySection(withMissing)
}

/**
 * Home lock is an entry action. If it landed in/after the switch section
 * (e.g. appended as a newly added key), move it just above Camera.
 * Order among other entry items is left alone.
 */
private fun placeHomeLockInEntrySection(order: List<SidebarItemKey>): List<SidebarItemKey> {
    if (SidebarItemKey.HomeLock !in order) return order
    val homeLockIdx = order.indexOf(SidebarItemKey.HomeLock)
    val firstSwitchIdx = order.indexOfFirst { it in SIDEBAR_SWITCH_ITEM_KEYS }
    if (firstSwitchIdx < 0 || homeLockIdx < firstSwitchIdx) return order

    val without = order.filter { it != SidebarItemKey.HomeLock }.toMutableList()
    val cameraIdx = without.indexOf(SidebarItemKey.Camera)
    val insertAt = when {
        cameraIdx >= 0 -> cameraIdx
        else -> {
            val firstSwitch = without.indexOfFirst { it in SIDEBAR_SWITCH_ITEM_KEYS }
            if (firstSwitch >= 0) firstSwitch else without.size
        }
    }
    without.add(insertAt, SidebarItemKey.HomeLock)
    return without
}

private fun insertSidebarItemDefault(order: MutableList<SidebarItemKey>, key: SidebarItemKey) {
    when (key) {
        SidebarItemKey.Home -> order.add(0, key)
        // Appending would bury it under a long saved order; it belongs above the switch rows.
        SidebarItemKey.DeviceControl -> {
            val homeIdx = order.indexOf(SidebarItemKey.Home)
            if (homeIdx >= 0) order.add(homeIdx + 1, key) else order.add(0, key)
        }
        SidebarItemKey.TouchPad -> {
            val deviceIdx = order.indexOf(SidebarItemKey.DeviceControl)
            if (deviceIdx >= 0) order.add(deviceIdx + 1, key) else order.add(0, key)
        }
        SidebarItemKey.VinylCoverDisplay -> {
            val quickEntityIdx = order.indexOf(SidebarItemKey.QuickEntity)
            if (quickEntityIdx >= 0) order.add(quickEntityIdx + 1, key) else order.add(key)
        }
        SidebarItemKey.HomeLock -> {
            val cameraIdx = order.indexOf(SidebarItemKey.Camera)
            if (cameraIdx >= 0) order.add(cameraIdx, key) else order.add(key)
        }
        else -> order.add(key)
    }
}

fun mergeSidebarItemOrder(
    savedOrder: List<SidebarItemKey>,
    visibleOrder: List<SidebarItemKey>
): List<SidebarItemKey> {
    if (visibleOrder.isEmpty()) return savedOrder
    val visibleSet = visibleOrder.toSet()
    val queue = visibleOrder.toMutableList()
    val merged = savedOrder.map { key ->
        if (key in visibleSet) queue.removeAt(0) else key
    }
    return placeHomeLockInEntrySection(merged + queue)
}

/** Home-sidebar shortcut to turn on after a feature master is enabled. */
fun SidebarSettings.withOfferedHomeEntry(key: SidebarItemKey): SidebarSettings = when (key) {
    SidebarItemKey.Weather -> copy(showWeather = true)
    SidebarItemKey.SimpleClock -> copy(showSimpleClock = true)
    SidebarItemKey.DreamClock -> copy(showDreamClock = true)
    SidebarItemKey.VoiceMessage -> copy(showVoiceMessage = true)
    SidebarItemKey.QuickEntity -> copy(showQuickEntity = true)
    SidebarItemKey.Browser -> copy(showBrowser = true)
    else -> this
}

fun shouldHideHomeChrome(sidebarSettings: SidebarSettings, playerSettings: PlayerSettings): Boolean =
    sidebarSettings.enableSidebar &&
        playerSettings.enableMinimalLauncher &&
        sidebarSettings.hideHomeHeader

val Context.sidebarSettingsStore: DataStore<SidebarSettings> by dataStore(
    fileName = "sidebar_settings.json",
    serializer = SettingsSerializer(SidebarSettings.serializer(), NEW_USER_SIDEBAR_SETTINGS),
    corruptionHandler = defaultCorruptionHandler(SidebarSettings())
)

class SidebarSettingsStore(dataStore: DataStore<SidebarSettings>) :
    SettingsStoreImpl<SidebarSettings>(dataStore, SidebarSettings()) {

    val enableSidebar = SettingState(getFlow().map { it.enableSidebar }) { value ->
        update { it.copy(enableSidebar = value) }
    }
    val sidebarPosition = SettingState(getFlow().map { it.sidebarPosition }) { value ->
        update { it.copy(sidebarPosition = value) }
    }
    val showDeviceControl = SettingState(getFlow().map { it.showDeviceControl }) { value ->
        update { it.copy(showDeviceControl = value) }
    }
    val showTouchPad = SettingState(getFlow().map { it.showTouchPad }) { value ->
        update { it.copy(showTouchPad = value) }
    }
    val showVoiceMessage = SettingState(getFlow().map { it.showVoiceMessage }) { value ->
        update { it.copy(showVoiceMessage = value) }
    }
    val showBrowser = SettingState(getFlow().map { it.showBrowser }) { value ->
        update { it.copy(showBrowser = value) }
    }
    val showWeather = SettingState(getFlow().map { it.showWeather }) { value ->
        update { it.copy(showWeather = value) }
    }
    val showSimpleClock = SettingState(getFlow().map { it.showSimpleClock }) { value ->
        update { it.copy(showSimpleClock = value) }
    }
    val showDreamClock = SettingState(getFlow().map { it.showDreamClock }) { value ->
        update { it.copy(showDreamClock = value) }
    }
    val showQuickEntity = SettingState(getFlow().map { it.showQuickEntity }) { value ->
        update { it.copy(showQuickEntity = value) }
    }
    val showVinylCoverDisplay = SettingState(getFlow().map { it.showVinylCoverDisplay }) { value ->
        update { it.copy(showVinylCoverDisplay = value) }
    }
    val showHomeLock = SettingState(getFlow().map { it.showHomeLock }) { value ->
        update { it.copy(showHomeLock = value) }
    }
    val showCamera = SettingState(getFlow().map { it.showCamera }) { value ->
        update { it.copy(showCamera = value) }
    }
    val showMuteMicrophone = SettingState(getFlow().map { it.showMuteMicrophone }) { value ->
        update { it.copy(showMuteMicrophone = value) }
    }
    val showDarkMode = SettingState(getFlow().map { it.showDarkMode }) { value ->
        update { it.copy(showDarkMode = value) }
    }
    val showHome = SettingState(getFlow().map { it.showHome }) { value ->
        update { it.copy(showHome = value) }
    }
    val hideSidebarHeader = SettingState(getFlow().map { it.hideSidebarHeader }) { value ->
        update { it.copy(hideSidebarHeader = value) }
    }
    val homeCornerButton = SettingState(getFlow().map { it.homeCornerButton }) { value ->
        update { it.copy(homeCornerButton = value) }
    }
    val hideHomeHeader = SettingState(getFlow().map { it.hideHomeHeader }) { value ->
        update { it.copy(hideHomeHeader = value) }
    }
    val itemOrder = SettingState(getFlow().map { sidebarItemOrderOrDefault(it.itemOrder) }) { value ->
        update { it.copy(itemOrder = sidebarItemOrderOrDefault(value)) }
    }
    val dockOrder = SettingState(getFlow().map { sidebarDockOrderOrDefault(it.dockOrder) }) { value ->
        update { it.copy(dockOrder = sidebarDockOrderOrDefault(value)) }
    }

    /**
     * Turn on a home-sidebar shortcut after its feature master is enabled.
     * Does not open the overlay — the row is the later enter path.
     */
    suspend fun offerHomeEntry(key: SidebarItemKey) {
        update { it.withOfferedHomeEntry(key) }
    }

    /** Blank clears the override, putting the row back on its translated default. */
    suspend fun setItemName(key: SidebarItemKey, name: String) {
        setCustomLabel(key.name, name)
    }

    /** Blank clears the override, putting the label back on its translated default. */
    suspend fun setCustomLabel(storageKey: String, name: String) {
        val trimmed = name.trim().take(SIDEBAR_ITEM_NAME_MAX_LENGTH)
        update { current ->
            val names = current.itemNames.toMutableMap()
            if (trimmed.isEmpty()) names.remove(storageKey) else names[storageKey] = trimmed
            current.copy(itemNames = names)
        }
    }
}
