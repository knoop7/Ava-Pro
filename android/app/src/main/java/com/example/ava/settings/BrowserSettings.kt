package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream


/**
 * Visible-dashboard power only. Screensaver COVERED/HIDDEN dormancy is a
 * separate pipeline and must not read this value.
 */
enum class BrowserPowerMode {
    HIGH,
    ADAPTIVE,
    LOW,
    ;

    val stored: String
        get() = when (this) {
            HIGH -> "high"
            ADAPTIVE -> "adaptive"
            LOW -> "low"
        }

    companion object {
        fun fromStored(raw: String?): BrowserPowerMode = when (raw?.lowercase()) {
            "high" -> HIGH
            "low" -> LOW
            else -> ADAPTIVE
        }
    }
}

@Serializable
data class BrowserSettings(
    val haRemoteUrlEnabled: Boolean = false,
    val enableBrowserDisplay: Boolean = false,
    val enableBrowserVisible: Boolean = false,
    val pullRefreshEnabled: Boolean = true,
    val initialScale: Int = 0,  
    val showScaleSliderInHa: Boolean = false,
    /**
     * Master: install the steward runtime. The three feature switches below each gate
     * one implementation so users can turn them off independently.
     */
    val wsStewardEnabled: Boolean = true,
    /**
     * Entity-stream scheduling: scroll-time deferral, lite batching under pressure /
     * dormancy, and the park → suspend ladder while covered/hidden.
     */
    val wsStewardStreamEnabled: Boolean = false,
    /**
     * Chunked card rendering via `content-visibility` — off-screen cards skip
     * layout/paint/raster while entity data keeps flowing.
     *
     * Default off: on overlay WebViews, Chromium often reports an empty intersection,
     * so cards stay at the 180px placeholder and never paint. Existing installs keep
     * whatever is already in DataStore.
     */
    val wsStewardChunkedRenderingEnabled: Boolean = false,
    /**
     * Dormant quieting: freeze CSS animations and pause media while the browser is
     * hidden or covered. Replaces the old separate freeze/pause toggles in the UI.
     */
    val wsStewardDormantQuietEnabled: Boolean = false,
    /**
     * When stream scheduling is on, retarget `subscribe_entities` to the current page
     * (with domain-narrow fallback for opaque cards). Nested under the stream switch.
     */
    val wsStewardEntityTrimEnabled: Boolean = false,
    /**
     * Opaque card types for entity-trim passthrough, one HA card-editor line per line
     * (e.g. `type: custom:uix-forge`).
     *
     * Prefab defaults are shown until the user edits ([wsStewardOpaqueCardLinesCustom]).
     * After that, this string is authoritative — including an empty clear.
     */
    val wsStewardOpaqueCardLines: String = "",
    /** False = still on prefab defaults; true = user owns the list (may be empty). */
    val wsStewardOpaqueCardLinesCustom: Boolean = false,
    /**
     * Legacy field kept for JSON compat; runtime follows [wsStewardDormantQuietEnabled].
     */
    val wsStewardFreezeAnimationsEnabled: Boolean = false,
    /**
     * Legacy field kept for JSON compat; runtime follows [wsStewardDormantQuietEnabled].
     */
    val wsStewardPauseMediaEnabled: Boolean = false,
    /**
     * When on, keep entity-update lite batching while the browser is visible
     * (not only under ORANGE+ memory pressure). Hidden from the main steward UI —
     * stream scheduling already covers pressure / dormancy batching.
     */
    val wsStewardLiteAlwaysEnabled: Boolean = false,
    /**
     * Overlay policy: when the floating browser is hidden/covered or under
     * memory pressure, purge page cache more aggressively.
     */
    val wvProdMemoryFeaturesEnabled: Boolean = false,
    /**
     * Overlay policy: cut spare refresh / compositor work on the floating
     * browser (offscreen pre-raster off; best-effort engine features when available).
     */
    val wvProdFrameThrottleFeaturesEnabled: Boolean = false,
    val fontSize: Int = 100,  
    val touchEnabled: Boolean = true,
    val dragEnabled: Boolean = true,
    val hardwareAcceleration: Boolean = true,  
    /** Slide-out sidebar on the HA browser overlay. */
    val enableBrowserSidebar: Boolean = true,
    // Sidebar tool rows all default ON so a fresh install opens the sidebar to a
    // full toolset instead of an empty shell; each remains individually
    // switchable in browser settings. Only the userscript (Tampermonkey) toggle
    // stays off by default — it enables a script runtime, not just a row.
    /** Show HA kiosk collapse/expand row in the browser overlay sidebar. */
    val showBrowserHaKiosk: Boolean = true,
    /** Show the page-scale slider row in the browser overlay sidebar. */
    val showBrowserPageZoom: Boolean = true,
    /** Show Web Console row in the browser overlay sidebar. */
    val showBrowserWebConsole: Boolean = true,
    /** Show Clear Cache row in the browser overlay sidebar. */
    val showBrowserClearCache: Boolean = true,
    /** Show User-Agent picker row in the browser overlay sidebar. */
    val showBrowserUserAgent: Boolean = true,
    /** Show remote URL editor row in the browser overlay sidebar. */
    val showBrowserRemoteUrl: Boolean = true,
    val advancedControlEnabled: Boolean = false,
    /**
     * Visible-dashboard power: `high` | `adaptive` | `low`. Default adaptive.
     * Does not change screensaver cover/hide dormancy.
     */
    val browserPowerMode: String = "adaptive",
    val backKeyHideEnabled: Boolean = true,
    val tampermonkeyEnabled: Boolean = false,
    val userAgentMode: Int = 0,
    /**
     * Runtime syntax-lowering for custom Lovelace cards on frozen WebViews.
     * Default on. Modern engines skip the work; turn off to serve original
     * card bytes when a vendor WebView version is misread.
     */
    val legacyCompatEnabled: Boolean = true,
    val gestureNavigationEnabled: Boolean = false,
    val syncBrowserUrlEnabled: Boolean = true,
    /**
     * Loopback secure-context boost for plain-http pages. Off by default.
     * UI only shown when Voice Channel is off; runtime proxy lands later.
     */
    val secureContextProxyEnabled: Boolean = false,
    /** Render engine: 0 = system WebView, 1 = GeckoView (downloadable engine pack). */
    val browserEngine: Int = 0,
    /** Follow system dark mode - applies dark theme to WebView and Home Assistant dashboard. */
    val followSystemDarkMode: Boolean = true,
    /**
     * While the browser overlay is visible, hold [android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]
     * so the system does not time out the display. Default off.
     */
    val keepScreenOnEnabled: Boolean = false,
    /**
     * Left/right dual WebView tiles (system WebView only). Default off.
     * When on, HA exposes separate left/right browser URL text entities.
     */
    val splitViewEnabled: Boolean = false,
    /**
     * Dual-page layout weights, each 1–9 (default 5:5).
     * Left/top = [splitViewRatioLeft]; right/bottom = [splitViewRatioRight].
     */
    val splitViewRatioLeft: Int = 5,
    val splitViewRatioRight: Int = 5,
    /**
     * Hide HA header/sidebar chrome: `off` | `auto` | `css` | `plugin`.
     * Default `off` (same as Kiosk Satellite) — sidebar shows "Kiosk 覆盖";
     * when on, sidebar shows "展开" and flips back to `off`.
     * Turning on restores [haKioskModeLast] (default `auto`).
     */
    val haKioskMode: String = "off",
    /** Strategy restored when covering again from the sidebar (default `auto`). */
    val haKioskModeLast: String = "auto",
    val haKioskHideHeader: Boolean = true,
    val haKioskHideSidebar: Boolean = true,
) {
    companion object {
        val DEFAULT = BrowserSettings()

        /**
         * Built-in opaque type table shown in the steward editor as
         * `type: custom:…` lines (same format users copy from the card editor).
         */
        val DEFAULT_OPAQUE_CARD_LINES: String = listOf(
            "uix-forge",
            "decluttering-card",
            "streamline-card",
            "linked-lovelace",
            "config-template-card",
            "card-templater",
            "template-entity-row",
            "html-template",
            "jinja2-template",
            "tailwindcss-template-card",
            "auto-entities",
            "flex-table-card",
            "battery-state-card",
            "state-switch",
            "apexcharts-card",
            "plotly-graph",
            "sankey",
            "power-flow",
            "power-wheel",
            "sunsynk-power",
            "home-feed-card",
            "search-card",
            "floorplan",
            "ha-floorplan",
            "advanced-camera",
            "frigate",
            "xiaomi-vacuum-map",
            "upcoming-media",
            "sonos-card",
            "yet-another-media-player",
            "yamp",
            "history-explorer",
            "logbook-card",
            "scheduler-card",
            "mushroom-strategy",
        ).joinToString("\n") { "type: custom:$it" }

        /** Parse editor lines into lowercase type tokens for JS matching. */
        fun parseOpaqueCardTypeLines(text: String): List<String> {
            val typeLine = Regex("""(?i)^\s*type\s*:\s*(.+?)\s*$""")
            val tokenOk = Regex("""^[a-z0-9][a-z0-9_-]*$""")
            return text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
                .mapNotNull { line ->
                    val raw = typeLine.find(line)?.groupValues?.get(1)?.trim() ?: line
                    val cleaned = raw.trim().trim('"', '\'', '`')
                        .removePrefix("custom:")
                        .removePrefix("Custom:")
                        .trim()
                        .lowercase()
                    cleaned.takeIf { tokenOk.matches(it) }
                }
                .distinct()
                .toList()
        }

        /**
         * True once the user owns the list.
         * Also treats a non-blank stored value as custom (migrates pre-flag saves).
         */
        fun opaqueCardLinesIsCustom(stored: String, custom: Boolean): Boolean =
            custom || stored.isNotBlank()

        /** Text shown in the steward editor (prefab until the user customizes). */
        fun opaqueCardLinesForDisplay(stored: String, custom: Boolean): String =
            if (opaqueCardLinesIsCustom(stored, custom)) stored else DEFAULT_OPAQUE_CARD_LINES

        /**
         * Types to push into JS.
         * `null` → keep / restore built-in table; empty list → no type-based passthrough.
         */
        fun opaqueCardTypesForApply(stored: String, custom: Boolean): List<String>? =
            if (opaqueCardLinesIsCustom(stored, custom)) {
                parseOpaqueCardTypeLines(stored)
            } else {
                null
            }
    }
}

/** True when Browser Display is enabled and requested visible (HA switch / sidebar toggle). */
fun BrowserSettings.isBrowserDisplayActive(): Boolean =
    haRemoteUrlEnabled && enableBrowserDisplay && enableBrowserVisible

object BrowserSettingsSerializer : Serializer<BrowserSettings> {
    // Tolerate keys removed in newer versions so existing configs survive upgrades.
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: BrowserSettings = BrowserSettings.DEFAULT
    
    override suspend fun readFrom(input: InputStream): BrowserSettings {
        val bytes = input.readBytes()
        return try {
            val text = bytes.decodeToString()
            val obj = json.parseToJsonElement(text).jsonObject
            // Migrate legacy single-ratio field (left weight, right = 10 − left).
            val migrated = if (
                !obj.containsKey("splitViewRatioLeft") &&
                obj.containsKey("splitViewRatio")
            ) {
                val left = obj["splitViewRatio"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 9) ?: 5
                JsonObject(
                    obj.toMutableMap().apply {
                        put("splitViewRatioLeft", JsonPrimitive(left))
                        put("splitViewRatioRight", JsonPrimitive((10 - left).coerceIn(1, 9)))
                        remove("splitViewRatio")
                    },
                )
            } else {
                obj
            }
            json.decodeFromJsonElement(BrowserSettings.serializer(), migrated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Returning defaultValue here silently masked corruption AND let the
            // next write overwrite the user's file. Preserve the bytes and let
            // the corruption handler own the replacement instead.
            SettingsQuarantine.save("BrowserSettings", bytes, e)
            throw CorruptionException("Unable to read BrowserSettings", e)
        }
    }
    
    override suspend fun writeTo(t: BrowserSettings, output: OutputStream) {
        output.write(
            json.encodeToString(BrowserSettings.serializer(), t).toByteArray()
        )
    }
}

val Context.browserSettingsDataStore: DataStore<BrowserSettings> by dataStore(
    fileName = "browser_settings.json",
    serializer = BrowserSettingsSerializer,
    corruptionHandler = defaultCorruptionHandler(BrowserSettings.DEFAULT),
)


class BrowserSettingsStore(private val context: Context) {
    private val dataStore = context.browserSettingsDataStore

    companion object {
        // The browser engine preference lives in DataStore (async). Some entry points
        // (e.g. WebViewService.show on the main thread) need it synchronously, so we mirror
        // the engine value into a tiny SharedPreferences file that can be read instantly.
        private const val MIRROR_PREFS = "browser_settings_mirror"
        private const val KEY_ENGINE = "browser_engine"
        private const val KEY_FOLLOW_DARK_MODE = "follow_dark_mode"
        private const val KEY_WV_PROD_MEMORY = "wv_prod_memory_features"
        private const val KEY_WV_PROD_FRAME = "wv_prod_frame_throttle_features"

        /** Keep sync mirror aligned with DataStore (delegate path reads mirror, not DataStore). */
        suspend fun syncEngineMirrorFromDataStore(context: Context) {
            syncMirrorsFromDataStore(context)
        }

        /** Sync engine + dark-mode + WebView production-feature mirrors from DataStore. */
        suspend fun syncMirrorsFromDataStore(context: Context) {
            val settings = context.browserSettingsDataStore.data.first()
            writeEngineMirror(context, settings.browserEngine.coerceIn(0, 1))
            writeFollowDarkModeMirror(context, settings.followSystemDarkMode)
            writeWvProdFeaturesMirror(
                context,
                settings.wvProdMemoryFeaturesEnabled,
                settings.wvProdFrameThrottleFeaturesEnabled,
            )
        }

        /** Synchronous, crash-safe read of the last-known browser engine preference. */
        fun getCachedEngine(context: Context): Int = try {
            context.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_ENGINE, 0)
        } catch (e: Exception) {
            0
        }

        /** Flush the engine mirror before a SHOW so delegation does not read default 0. */
        fun rememberEngine(context: Context, engine: Int) {
            writeEngineMirror(context, engine.coerceIn(0, 1))
        }

        /** Synchronous read used before dispatching work to the external Gecko engine pack. */
        fun getCachedFollowSystemDarkMode(context: Context): Boolean = try {
            context.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_FOLLOW_DARK_MODE, true)
        } catch (e: Exception) {
            true
        }

        private fun writeEngineMirror(context: Context, engine: Int) {
            try {
                context.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_ENGINE, engine).commit()
            } catch (e: Exception) {
                // Mirror is best-effort; never let it crash settings updates.
            }
        }

        private fun writeFollowDarkModeMirror(context: Context, enabled: Boolean) {
            try {
                context.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_FOLLOW_DARK_MODE, enabled).apply()
            } catch (e: Exception) {
                // Mirror is best-effort; never let it crash settings updates.
            }
        }

        fun getCachedWvProdMemoryFeatures(context: Context): Boolean = try {
            context.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WV_PROD_MEMORY, false)
        } catch (_: Exception) {
            false
        }

        fun getCachedWvProdFrameThrottleFeatures(context: Context): Boolean = try {
            context.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WV_PROD_FRAME, false)
        } catch (_: Exception) {
            false
        }

        fun writeWvProdFeaturesMirror(
            context: Context,
            memoryEnabled: Boolean,
            frameThrottleEnabled: Boolean,
        ) {
            try {
                context.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_WV_PROD_MEMORY, memoryEnabled)
                    .putBoolean(KEY_WV_PROD_FRAME, frameThrottleEnabled)
                    .apply()
            } catch (_: Exception) {
                // Mirror is best-effort; never let it crash settings updates.
            }
        }

        fun normalizeHaKioskMode(mode: String?): String = when (mode) {
            "auto", "css", "plugin", "off" -> mode
            else -> "off"
        }
    }

    fun getFlow(): Flow<BrowserSettings> = dataStore.data
    
    suspend fun get(): BrowserSettings = dataStore.data.first()
    
    suspend fun update(transform: (BrowserSettings) -> BrowserSettings) {
        dataStore.updateData(transform)
    }
    
    
    val haRemoteUrlEnabled: Flow<Boolean> = getFlow().map { it.haRemoteUrlEnabled }
    val pullRefreshEnabled: Flow<Boolean> = getFlow().map { it.pullRefreshEnabled }
    val initialScale: Flow<Int> = getFlow().map { it.initialScale }
    val showScaleSliderInHa: Flow<Boolean> = getFlow().map { it.showScaleSliderInHa }
    val wsStewardEnabled: Flow<Boolean> = getFlow().map { it.wsStewardEnabled }
    val wsStewardStreamEnabled: Flow<Boolean> = getFlow().map { it.wsStewardStreamEnabled }
    val wsStewardChunkedRenderingEnabled: Flow<Boolean> =
        getFlow().map { it.wsStewardChunkedRenderingEnabled }
    val wsStewardDormantQuietEnabled: Flow<Boolean> =
        getFlow().map { it.wsStewardDormantQuietEnabled }
    val wsStewardEntityTrimEnabled: Flow<Boolean> = getFlow().map { it.wsStewardEntityTrimEnabled }
    val wsStewardOpaqueCardLines: Flow<String> = getFlow().map { it.wsStewardOpaqueCardLines }
    val wsStewardOpaqueCardLinesCustom: Flow<Boolean> =
        getFlow().map { it.wsStewardOpaqueCardLinesCustom }
    val wsStewardFreezeAnimationsEnabled: Flow<Boolean> =
        getFlow().map { it.wsStewardFreezeAnimationsEnabled }
    val wsStewardPauseMediaEnabled: Flow<Boolean> =
        getFlow().map { it.wsStewardPauseMediaEnabled }
    val wsStewardLiteAlwaysEnabled: Flow<Boolean> =
        getFlow().map { it.wsStewardLiteAlwaysEnabled }
    val wvProdMemoryFeaturesEnabled: Flow<Boolean> =
        getFlow().map { it.wvProdMemoryFeaturesEnabled }
    val wvProdFrameThrottleFeaturesEnabled: Flow<Boolean> =
        getFlow().map { it.wvProdFrameThrottleFeaturesEnabled }
    val fontSize: Flow<Int> = getFlow().map { it.fontSize }
    val touchEnabled: Flow<Boolean> = getFlow().map { it.touchEnabled }
    val dragEnabled: Flow<Boolean> = getFlow().map { it.dragEnabled }
    val hardwareAcceleration: Flow<Boolean> = getFlow().map { it.hardwareAcceleration }
    val enableBrowserSidebar: Flow<Boolean> = getFlow().map { it.enableBrowserSidebar }
    val showBrowserHaKiosk: Flow<Boolean> = getFlow().map { it.showBrowserHaKiosk }
    val showBrowserPageZoom: Flow<Boolean> = getFlow().map { it.showBrowserPageZoom }
    val showBrowserWebConsole: Flow<Boolean> = getFlow().map { it.showBrowserWebConsole }
    val showBrowserClearCache: Flow<Boolean> = getFlow().map { it.showBrowserClearCache }
    val showBrowserUserAgent: Flow<Boolean> = getFlow().map { it.showBrowserUserAgent }
    val showBrowserRemoteUrl: Flow<Boolean> = getFlow().map { it.showBrowserRemoteUrl }
    val advancedControlEnabled: Flow<Boolean> = getFlow().map { it.advancedControlEnabled }
    val browserPowerMode: Flow<BrowserPowerMode> =
        getFlow().map { BrowserPowerMode.fromStored(it.browserPowerMode) }
    val backKeyHideEnabled: Flow<Boolean> = getFlow().map { it.backKeyHideEnabled }
    val tampermonkeyEnabled: Flow<Boolean> = getFlow().map { it.tampermonkeyEnabled }
    val userAgentMode: Flow<Int> = getFlow().map { it.userAgentMode }
    val legacyCompatEnabled: Flow<Boolean> = getFlow().map { it.legacyCompatEnabled }
    val gestureNavigationEnabled: Flow<Boolean> = getFlow().map { it.gestureNavigationEnabled }
    val syncBrowserUrlEnabled: Flow<Boolean> = getFlow().map { it.syncBrowserUrlEnabled }
    val secureContextProxyEnabled: Flow<Boolean> = getFlow().map { it.secureContextProxyEnabled }
    val browserEngine: Flow<Int> = getFlow().map { it.browserEngine }
    val followSystemDarkMode: Flow<Boolean> = getFlow().map { it.followSystemDarkMode }
    val keepScreenOnEnabled: Flow<Boolean> = getFlow().map { it.keepScreenOnEnabled }
    val splitViewEnabled: Flow<Boolean> = getFlow().map { it.splitViewEnabled }
    val splitViewRatioLeft: Flow<Int> = getFlow().map { it.splitViewRatioLeft.coerceIn(1, 9) }
    val splitViewRatioRight: Flow<Int> = getFlow().map { it.splitViewRatioRight.coerceIn(1, 9) }
    val enableBrowserDisplay: Flow<Boolean> = getFlow().map { it.enableBrowserDisplay }
    val enableBrowserVisible: SettingState<Boolean> = SettingState(
        getFlow().map { it.enableBrowserVisible },
        { value -> update { it.copy(enableBrowserVisible = value) } }
    )
    
    suspend fun setEnableBrowserDisplay(enabled: Boolean) {
        update { it.copy(enableBrowserDisplay = enabled) }
    }
    
    suspend fun setHaRemoteUrlEnabled(enabled: Boolean) {
        update { it.copy(haRemoteUrlEnabled = enabled) }
    }
    
    suspend fun setPullRefreshEnabled(enabled: Boolean) {
        update { it.copy(pullRefreshEnabled = enabled) }
    }
    
    suspend fun setInitialScale(scale: Int) {
        update { it.copy(initialScale = scale.coerceIn(0, 500)) }
    }

    suspend fun setShowScaleSliderInHa(enabled: Boolean) {
        update { it.copy(showScaleSliderInHa = enabled) }
    }

    suspend fun setWsStewardEnabled(enabled: Boolean) {
        update { it.copy(wsStewardEnabled = enabled) }
    }

    suspend fun setWsStewardStreamEnabled(enabled: Boolean) {
        update { it.copy(wsStewardStreamEnabled = enabled) }
    }

    suspend fun setWsStewardChunkedRenderingEnabled(enabled: Boolean) {
        update { it.copy(wsStewardChunkedRenderingEnabled = enabled) }
    }

    /** Mirrors into the legacy freeze/pause fields so older readers stay consistent. */
    suspend fun setWsStewardDormantQuietEnabled(enabled: Boolean) {
        update {
            it.copy(
                wsStewardDormantQuietEnabled = enabled,
                wsStewardFreezeAnimationsEnabled = enabled,
                wsStewardPauseMediaEnabled = enabled,
            )
        }
    }

    suspend fun setWsStewardEntityTrimEnabled(enabled: Boolean) {
        update { it.copy(wsStewardEntityTrimEnabled = enabled) }
    }

    suspend fun setWsStewardOpaqueCardLines(lines: String, custom: Boolean = true) {
        update {
            it.copy(
                wsStewardOpaqueCardLines = lines,
                wsStewardOpaqueCardLinesCustom = custom,
            )
        }
    }

    suspend fun setWsStewardFreezeAnimationsEnabled(enabled: Boolean) {
        update { it.copy(wsStewardFreezeAnimationsEnabled = enabled) }
    }

    suspend fun setWsStewardPauseMediaEnabled(enabled: Boolean) {
        update { it.copy(wsStewardPauseMediaEnabled = enabled) }
    }

    suspend fun setWsStewardLiteAlwaysEnabled(enabled: Boolean) {
        update { it.copy(wsStewardLiteAlwaysEnabled = enabled) }
    }

    suspend fun setWvProdMemoryFeaturesEnabled(enabled: Boolean) {
        update { it.copy(wvProdMemoryFeaturesEnabled = enabled) }
        val next = get()
        Companion.writeWvProdFeaturesMirror(
            context,
            next.wvProdMemoryFeaturesEnabled,
            next.wvProdFrameThrottleFeaturesEnabled,
        )
        runCatching {
            com.example.ava.webcompat.WebViewProductionFeatures.applyDesired(
                next.wvProdMemoryFeaturesEnabled,
                next.wvProdFrameThrottleFeaturesEnabled,
            )
        }
    }

    suspend fun setWvProdFrameThrottleFeaturesEnabled(enabled: Boolean) {
        update { it.copy(wvProdFrameThrottleFeaturesEnabled = enabled) }
        val next = get()
        Companion.writeWvProdFeaturesMirror(
            context,
            next.wvProdMemoryFeaturesEnabled,
            next.wvProdFrameThrottleFeaturesEnabled,
        )
        runCatching {
            com.example.ava.webcompat.WebViewProductionFeatures.applyDesired(
                next.wvProdMemoryFeaturesEnabled,
                next.wvProdFrameThrottleFeaturesEnabled,
            )
        }
    }
    
    suspend fun setFontSize(size: Int) {
        update { it.copy(fontSize = size.coerceIn(50, 300)) }
    }
    
    suspend fun setTouchEnabled(enabled: Boolean) {
        update { it.copy(touchEnabled = enabled) }
    }
    
    suspend fun setDragEnabled(enabled: Boolean) {
        update { it.copy(dragEnabled = enabled) }
    }
    
    suspend fun setHardwareAcceleration(enabled: Boolean) {
        update { it.copy(hardwareAcceleration = enabled) }
    }

    suspend fun setEnableBrowserSidebar(enabled: Boolean) {
        update { it.copy(enableBrowserSidebar = enabled) }
    }

    suspend fun setShowBrowserHaKiosk(enabled: Boolean) {
        update { it.copy(showBrowserHaKiosk = enabled) }
    }

    suspend fun setShowBrowserPageZoom(enabled: Boolean) {
        update { it.copy(showBrowserPageZoom = enabled) }
    }

    suspend fun setShowBrowserWebConsole(enabled: Boolean) {
        update { it.copy(showBrowserWebConsole = enabled) }
    }

    suspend fun setShowBrowserClearCache(enabled: Boolean) {
        update { it.copy(showBrowserClearCache = enabled) }
    }

    suspend fun setShowBrowserUserAgent(enabled: Boolean) {
        update { it.copy(showBrowserUserAgent = enabled) }
    }

    suspend fun setShowBrowserRemoteUrl(enabled: Boolean) {
        update { it.copy(showBrowserRemoteUrl = enabled) }
    }
    
    suspend fun setAdvancedControlEnabled(enabled: Boolean) {
        update { it.copy(advancedControlEnabled = enabled) }
    }

    suspend fun setBrowserPowerMode(mode: BrowserPowerMode) {
        // Same-value writes still make DataStore emit. HA then re-pushes the
        // select state; an automation on that entity can bounce the command
        // forever. Skip the write so the overlay never sees a fake change.
        update { current ->
            if (current.browserPowerMode == mode.stored) current
            else current.copy(browserPowerMode = mode.stored)
        }
    }
    
    suspend fun setBackKeyHideEnabled(enabled: Boolean) {
        update { it.copy(backKeyHideEnabled = enabled) }
    }
    
    suspend fun setTampermonkeyEnabled(enabled: Boolean) {
        update { it.copy(tampermonkeyEnabled = enabled) }
    }
    
    suspend fun setUserAgentMode(mode: Int) {
        update { it.copy(userAgentMode = mode.coerceIn(0, 3)) }
    }

    suspend fun setLegacyCompatEnabled(enabled: Boolean) {
        update { it.copy(legacyCompatEnabled = enabled) }
    }
    
    suspend fun setGestureNavigationEnabled(enabled: Boolean) {
        update { it.copy(gestureNavigationEnabled = enabled) }
    }

    suspend fun setSyncBrowserUrlEnabled(enabled: Boolean) {
        update { it.copy(syncBrowserUrlEnabled = enabled) }
    }

    suspend fun setSecureContextProxyEnabled(enabled: Boolean) {
        update { it.copy(secureContextProxyEnabled = enabled) }
    }

    suspend fun setBrowserEngine(engine: Int) {
        val coerced = engine.coerceIn(0, 1)
        update { current ->
            if (coerced == 1) {
                // Split tiles and Tampermonkey are system-WebView-only.
                current.copy(
                    browserEngine = coerced,
                    tampermonkeyEnabled = false,
                    splitViewEnabled = false,
                )
            } else {
                current.copy(browserEngine = coerced)
            }
        }
        writeEngineMirror(context, coerced)
    }

    suspend fun setFollowSystemDarkMode(enabled: Boolean) {
        update { it.copy(followSystemDarkMode = enabled) }
        writeFollowDarkModeMirror(context, enabled)
    }

    suspend fun setKeepScreenOnEnabled(enabled: Boolean) {
        update { it.copy(keepScreenOnEnabled = enabled) }
    }

    suspend fun setSplitViewEnabled(enabled: Boolean) {
        update { current ->
            val engine = current.browserEngine.coerceIn(0, 1)
            // Never allow split while Gecko is selected.
            current.copy(splitViewEnabled = enabled && engine == 0)
        }
    }

    suspend fun setSplitViewRatio(left: Int, right: Int) {
        update {
            it.copy(
                splitViewRatioLeft = left.coerceIn(1, 9),
                splitViewRatioRight = right.coerceIn(1, 9),
            )
        }
    }

    /**
     * Drawer-style toggle: off ↔ last strategy (`auto` by default).
     * @return the mode after the flip
     */
    suspend fun toggleHaKioskMode(): String {
        var nextMode = "off"
        update { cur ->
            val current = normalizeHaKioskMode(cur.haKioskMode)
            if (current == "off") {
                nextMode = normalizeHaKioskMode(cur.haKioskModeLast).let {
                    if (it == "off") "auto" else it
                }
                cur.copy(haKioskMode = nextMode)
            } else {
                nextMode = "off"
                cur.copy(haKioskModeLast = current, haKioskMode = "off")
            }
        }
        return nextMode
    }
}

/** True when HA header/sidebar chrome should be hidden. */
fun BrowserSettings.isHaKioskModeOn(): Boolean =
    BrowserSettingsStore.normalizeHaKioskMode(haKioskMode) != "off"
