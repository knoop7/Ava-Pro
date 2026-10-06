package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class SettingsStyleSettings(
    /** Landscape: settings list on the left, pages on the right. */
    val landscapeSplit: Boolean = true,
    /** Bottom pull-handle on the main settings list. */
    val mainHandle: Boolean = true,
    /**
     * iOS 26-style Liquid Glass: translucent surfaces with real-time backdrop blur, a
     * specular rim highlight and a soft top sheen. Applies to floating overlays only
     * (dashboard chrome pills, notification cards, HA switch, volume, toast,
     * Dream Clock sheet and show/hide transitions) — not app-window title bars,
     * and the settings UI itself stays flat by design.
     * Off = flat translucent surfaces and alpha-only fades — the stutter-free path for
     * low-end hardware.
     */
    val liquidGlassEnabled: Boolean = false,
    /**
     * 0–100. 50 is the default look; 100 is a stronger frost (more blur, brighter rim).
     */
    val liquidGlassIntensity: Int = LIQUID_GLASS_DEFAULT_INTENSITY,
    /**
     * Backdrop blur behind the glass. API 31+ uses live RenderEffect / window blur.
     * Below that, in-app sidebars freeze a stack-blurred snapshot (same helper as
     * overview). Off keeps the glass finish — rim, sheen, tint — but skips blur.
     */
    val liquidGlassBlur: Boolean = true,
    /** Specular bloom that follows the finger while a glass surface is pressed. */
    val liquidGlassPressGlow: Boolean = true,
    /**
     * When a second floating app window opens, squeeze it beside the one already
     * open. Off leaves each window on its own saved frame.
     */
    val overlaySplitEnabled: Boolean = false,
    /**
     * Share of the pair, each 1–9 (default 5:5).
     * First window = [overlaySplitRatioLeft] (left, or top in portrait);
     * the one that opens next = [overlaySplitRatioRight].
     */
    val overlaySplitRatioLeft: Int = 5,
    val overlaySplitRatioRight: Int = 5,
) {
    companion object {
        const val LIQUID_GLASS_MIN_INTENSITY = 0
        const val LIQUID_GLASS_MAX_INTENSITY = 100
        const val LIQUID_GLASS_DEFAULT_INTENSITY = 50
    }
}

/**
 * In-memory copy of [SettingsStyleSettings] so toggling a switch
 * rebuilds the settings shell on the same frame. DataStore syncs afterward.
 * Survives Activity recreation (rotation).
 *
 * Overlay services read [liquidGlassEnabled] / [liquidGlassIntensity] synchronously via
 * [LiquidGlassSession] so a flip in settings restyles floating windows on their next repaint.
 */
object SettingsStyleSession {
    private val _landscapeSplit = MutableStateFlow(true)
    val landscapeSplit: StateFlow<Boolean> = _landscapeSplit.asStateFlow()

    private val _mainHandle = MutableStateFlow(true)
    val mainHandle: StateFlow<Boolean> = _mainHandle.asStateFlow()

    private val _liquidGlassEnabled = MutableStateFlow(false)
    val liquidGlassEnabled: StateFlow<Boolean> = _liquidGlassEnabled.asStateFlow()

    private val _liquidGlassIntensity =
        MutableStateFlow(SettingsStyleSettings.LIQUID_GLASS_DEFAULT_INTENSITY)
    val liquidGlassIntensity: StateFlow<Int> = _liquidGlassIntensity.asStateFlow()

    private val _liquidGlassBlur = MutableStateFlow(true)
    val liquidGlassBlur: StateFlow<Boolean> = _liquidGlassBlur.asStateFlow()

    private val _liquidGlassPressGlow = MutableStateFlow(true)
    val liquidGlassPressGlow: StateFlow<Boolean> = _liquidGlassPressGlow.asStateFlow()

    private val _overlaySplitEnabled = MutableStateFlow(false)
    val overlaySplitEnabled: StateFlow<Boolean> = _overlaySplitEnabled.asStateFlow()

    private val _overlaySplitRatioLeft = MutableStateFlow(5)
    val overlaySplitRatioLeft: StateFlow<Int> = _overlaySplitRatioLeft.asStateFlow()

    private val _overlaySplitRatioRight = MutableStateFlow(5)
    val overlaySplitRatioRight: StateFlow<Int> = _overlaySplitRatioRight.asStateFlow()

    /** True after disk or a user toggle has written the split switch and ratio. */
    @Volatile
    var overlaySplitHydrated: Boolean = false
        private set

    fun syncFromSettings(settings: SettingsStyleSettings) {
        _landscapeSplit.value = settings.landscapeSplit
        _mainHandle.value = settings.mainHandle
        _liquidGlassEnabled.value = settings.liquidGlassEnabled
        _liquidGlassIntensity.value = settings.liquidGlassIntensity.coerceIn(
            SettingsStyleSettings.LIQUID_GLASS_MIN_INTENSITY,
            SettingsStyleSettings.LIQUID_GLASS_MAX_INTENSITY,
        )
        _liquidGlassBlur.value = settings.liquidGlassBlur
        _liquidGlassPressGlow.value = settings.liquidGlassPressGlow
        // A settings screen can sync the serializer default (switch off) before
        // DataStore emits. That must not wipe a split switch already read from disk.
        if (!overlaySplitHydrated) {
            _overlaySplitEnabled.value = settings.overlaySplitEnabled
            _overlaySplitRatioLeft.value = settings.overlaySplitRatioLeft.coerceIn(1, 9)
            _overlaySplitRatioRight.value = settings.overlaySplitRatioRight.coerceIn(1, 9)
        }
    }

    /** Disk copy of the overlay-split switch is in the session. Later placeholders stay out. */
    fun retainOverlaySplitFromDisk() {
        overlaySplitHydrated = true
    }

    /**
     * Cold start just read the store. Install that switch and ratio even when a
     * placeholder already set [overlaySplitHydrated]. Skipping this left the
     * session off, so the restore never armed the pair.
     */
    fun adoptOverlaySplitFromDisk(settings: SettingsStyleSettings) {
        _overlaySplitEnabled.value = settings.overlaySplitEnabled
        _overlaySplitRatioLeft.value = settings.overlaySplitRatioLeft.coerceIn(1, 9)
        _overlaySplitRatioRight.value = settings.overlaySplitRatioRight.coerceIn(1, 9)
        overlaySplitHydrated = true
    }

    fun setLandscapeSplit(enabled: Boolean) {
        _landscapeSplit.value = enabled
    }

    fun setMainHandle(enabled: Boolean) {
        _mainHandle.value = enabled
    }

    fun setLiquidGlassEnabled(enabled: Boolean) {
        _liquidGlassEnabled.value = enabled
    }

    fun setLiquidGlassIntensity(intensity: Int) {
        _liquidGlassIntensity.value = intensity.coerceIn(
            SettingsStyleSettings.LIQUID_GLASS_MIN_INTENSITY,
            SettingsStyleSettings.LIQUID_GLASS_MAX_INTENSITY,
        )
    }

    fun setLiquidGlassBlur(enabled: Boolean) {
        _liquidGlassBlur.value = enabled
    }

    fun setLiquidGlassPressGlow(enabled: Boolean) {
        _liquidGlassPressGlow.value = enabled
    }

    fun setOverlaySplitEnabled(enabled: Boolean) {
        _overlaySplitEnabled.value = enabled
        overlaySplitHydrated = true
    }

    fun setOverlaySplitRatio(left: Int, right: Int) {
        _overlaySplitRatioLeft.value = left.coerceIn(1, 9)
        _overlaySplitRatioRight.value = right.coerceIn(1, 9)
        overlaySplitHydrated = true
    }
}

val Context.settingsStyleSettingsStore: DataStore<SettingsStyleSettings> by dataStore(
    fileName = "settings_style_settings.json",
    serializer = SettingsSerializer(SettingsStyleSettings.serializer(), SettingsStyleSettings()),
    corruptionHandler = defaultCorruptionHandler(SettingsStyleSettings()),
)

class SettingsStyleSettingsStore(dataStore: DataStore<SettingsStyleSettings>) :
    SettingsStoreImpl<SettingsStyleSettings>(dataStore, SettingsStyleSettings()) {

    val landscapeSplit = SettingState(getFlow().map { it.landscapeSplit }) { value ->
        update { it.copy(landscapeSplit = value) }
    }

    val mainHandle = SettingState(getFlow().map { it.mainHandle }) { value ->
        update { it.copy(mainHandle = value) }
    }

    val liquidGlassEnabled = SettingState(getFlow().map { it.liquidGlassEnabled }) { value ->
        update { it.copy(liquidGlassEnabled = value) }
    }

    val liquidGlassIntensity = SettingState(getFlow().map { it.liquidGlassIntensity }) { value ->
        update {
            it.copy(
                liquidGlassIntensity = value.coerceIn(
                    SettingsStyleSettings.LIQUID_GLASS_MIN_INTENSITY,
                    SettingsStyleSettings.LIQUID_GLASS_MAX_INTENSITY,
                ),
            )
        }
    }

    val liquidGlassBlur = SettingState(getFlow().map { it.liquidGlassBlur }) { value ->
        update { it.copy(liquidGlassBlur = value) }
    }

    val liquidGlassPressGlow = SettingState(getFlow().map { it.liquidGlassPressGlow }) { value ->
        update { it.copy(liquidGlassPressGlow = value) }
    }

    val overlaySplitEnabled = SettingState(getFlow().map { it.overlaySplitEnabled }) { value ->
        update { it.copy(overlaySplitEnabled = value) }
    }

    suspend fun setOverlaySplitRatio(left: Int, right: Int) {
        update {
            it.copy(
                overlaySplitRatioLeft = left.coerceIn(1, 9),
                overlaySplitRatioRight = right.coerceIn(1, 9),
            )
        }
    }
}
