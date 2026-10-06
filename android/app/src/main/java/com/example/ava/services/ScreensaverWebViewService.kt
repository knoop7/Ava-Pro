package com.example.ava.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.example.ava.settings.ScreensaverSettings
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.DawnEntitySlot
import com.example.ava.settings.isDawnWeatherEntityId
import com.example.ava.settings.resolveDawnWeatherEntityId
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.ui.BlurFadeRevealLayout
import com.example.ava.ui.components.MdiIconMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ScreensaverWebViewService : Service() {

    private var windowManager: WindowManager? = null
    private var containerView: BlurFadeRevealLayout? = null
    private var webView: WebView? = null
    private var currentUrl: String = ""
    private var hasTriedHttpFallback = false
    private var isPageLoaded = false
    private var loadStartTime = 0L
    private val loadTimeoutMs = 15000L
    private var isContainerAttached = false
    private var containerParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var healthMonitorActive = false
    private var lastRecoveryRefreshAt = 0L
    private var pendingRecoveryReason: String? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pixelShift = ScreensaverPixelShift(mainHandler)
    private var settingsJob: Job? = null
    private var pixelShiftEnabled = false
    private var smartAodEnabled = false
    private var smartAodMaskPercent = 100
    private var smartAodOverlay: SmartAodMaskOverlay? = null
    /** Bumped on show/destroy to invalidate in-flight reveal-out / pending reveal-in. */
    private var hideEpoch = 0
    /** True while waiting for first paint / page load before blur-fade-in. */
    private var pendingRevealAfterLoad = false
    /** [hideEpoch] for which cover-peer deepen / CPU throttle was already signaled. */
    private var coverPeersNotifiedEpoch = -1
    private val revealSettleRunnable = Runnable { startBlurFadeIn() }
    private val revealMaxWaitRunnable = Runnable {
        Log.w(TAG, "Reveal-in max wait reached; revealing anyway")
        pendingRevealAfterLoad = false
        startBlurFadeIn()
    }

    /** Dawn entity-slot dashboard (injected into dawn_wallpaper.html). */
    private var dawnSlotsEnabled = false
    private var dawnSlots: List<DawnEntitySlot> = List(4) { DawnEntitySlot() }
    private var dawnWeatherEntityId: String = ""
    /** Forecast hours from HA get_forecasts / forecast attr; empty → fall back to current weather. */
    private var dawnHourlyHours: JSONArray = JSONArray()
    /**
     * HA hourly is typically Now+future only. Cache recent hours locally so the strip
     * can still show ≤3h lookback (dusk→night continuity) after they leave the payload.
     * Key = epoch hour (floor(ms / 1h)).
     */
    private val dawnHourlyPastCache = LinkedHashMap<Long, JSONObject>()
    private val dawnHourlyPastCacheMaxAgeHours = 6
    /** Trimmed; empty = HTML uses built-in wallpaper API. */
    private var dawnWallpaperSourceUrl: String = ""
    /** Dawn slots clock: 24h ISO when true (default 12h + AM/PM). */
    private var dawnIsoTimeEnabled: Boolean = false
    /** Merge 3+ identical hourly icons into one segment (default off). */
    private var dawnMergeWeatherIconsEnabled: Boolean = false
    private val dawnEntityStates = mutableMapOf<String, String>()
    private val dawnEntityLabels = mutableMapOf<String, String>()
    private val dawnEntityUnits = mutableMapOf<String, String>()
    private val dawnEntityAttrs = mutableMapOf<String, MutableMap<String, String>>()
    /** Cached `data:image/png;base64,...` for built-in MDI drawables (no CDN webfont). */
    private val dawnIconDataUriCache = ConcurrentHashMap<String, String>()

    companion object {
        private const val TAG = "ScreensaverWebView"
        @Volatile
        private var instance: ScreensaverWebViewService? = null
        /** Minimum gap between automatic recovery reloads to avoid refresh storms. */
        private const val MIN_RECOVERY_INTERVAL_MS = 60_000L
        /** Debounce before acting on a detected failure. */
        private const val RECOVERY_DEBOUNCE_MS = 3_000L
        /** Soft reload for local asset pages (e.g. xiaomi wallpaper) that can stall over long runs. */
        private const val LOCAL_PREVENTIVE_REFRESH_INTERVAL_MS = 45 * 60_000L
        /** Do not treat a page as dead while it is still in its initial load window. */
        private const val MIN_LOAD_AGE_BEFORE_RECOVERY_MS = 8_000L
        /**
         * After [WebViewClient.onPageFinished], wait briefly so the first compositor frame
         * is present — starting the reveal immediately shows a blank WebView.
         */
        private const val REVEAL_SETTLE_MS = 120L
        /** Cap how long we stay GONE waiting for load before blur-fade-in anyway. */
        private const val REVEAL_MAX_WAIT_MS = 2_500L
        
        private const val VIDEO_COMPAT_JS = """(function(){if(window.__avaVideoCompat)return;window.__avaVideoCompat=true;var origPlay=HTMLVideoElement.prototype.play;HTMLVideoElement.prototype.play=function(){var self=this;try{var p=origPlay.call(this);if(p&&p.then){return p.catch(function(e){if(e.name==='AbortError'||e.name==='NotAllowedError'){console.warn('[Ava] video.play() '+e.name+', recovering');return undefined}throw e})}return p}catch(e){return Promise.reject(e)}};document.addEventListener('visibilitychange',function(){if(document.visibilityState==='visible'){document.querySelectorAll('video').forEach(function(v){if(v.paused&&!v.ended&&v.readyState>0){v.play()}})}})})();"""

        fun show(context: Context, url: String) {
            val svc = instance
            if (svc != null && svc.isActivelyDisplayed() && svc.currentUrl == url) return
            val intent = Intent(context, ScreensaverWebViewService::class.java).apply {
                action = "ACTION_SHOW"
                putExtra("url", url)
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, ScreensaverWebViewService::class.java).apply {
                action = "ACTION_HIDE"
            }
            context.startService(intent)
        }
        
        fun destroy(context: Context) {
            val intent = Intent(context, ScreensaverWebViewService::class.java).apply {
                action = "ACTION_DESTROY"
            }
            context.startService(intent)
        }

        fun updateUrl(context: Context, url: String) {
            val intent = Intent(context, ScreensaverWebViewService::class.java).apply {
                action = "ACTION_UPDATE"
                putExtra("url", url)
            }
            context.startService(intent)
        }
        
        fun pause(context: Context) {
            val intent = Intent(context, ScreensaverWebViewService::class.java).apply {
                action = "ACTION_PAUSE"
            }
            context.startService(intent)
        }
        
        fun resume(context: Context) {
            val intent = Intent(context, ScreensaverWebViewService::class.java).apply {
                action = "ACTION_RESUME"
            }
            context.startService(intent)
        }
        
        fun bringToFront(context: Context) {
            val intent = Intent(context, ScreensaverWebViewService::class.java).apply {
                action = "ACTION_BRING_TO_FRONT"
            }
            context.startService(intent)
        }

        /** Reassert the existing idle screensaver without starting a dormant service. */
        fun bringToFrontIfVisible() {
            instance?.bringToFrontIfControllerVisible()
        }

        /** Static Web screensaver AOD is on whenever the feature is enabled and showing. */
        fun isSmartAodShowing(): Boolean {
            val svc = instance ?: return false
            return svc.smartAodEnabled && svc.isActivelyDisplayed()
        }

        fun isOverlayShowing(): Boolean = instance?.isActivelyDisplayed() == true

        fun forceRefresh(context: Context) {
            val intent = Intent(context, ScreensaverWebViewService::class.java).apply {
                action = "ACTION_FORCE_REFRESH"
            }
            context.startService(intent)
        }

        /** Main-frame network errors that usually mean the page is unreachable, not user content. */
        private val RECOVERABLE_MAIN_FRAME_ERROR_CODES = setOf(
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_IO,
        )

        /** Reload Dawn slot config from settings and push into the live WebView (if any). */
        fun pushDawnSlotsFromSettings(context: Context) {
            instance?.reloadDawnSlotsFromSettings(context.applicationContext)
        }

        fun updateDawnEntityState(entityId: String, state: String) {
            instance?.onDawnEntityState(entityId, state)
        }

        fun updateDawnEntityLabel(entityId: String, label: String) {
            instance?.onDawnEntityLabel(entityId, label)
        }

        fun updateDawnEntityUnit(entityId: String, unit: String) {
            instance?.onDawnEntityUnit(entityId, unit)
        }

        fun updateDawnEntityAttribute(entityId: String, attribute: String, value: String) {
            instance?.onDawnEntityAttribute(entityId, attribute, value)
        }

        fun restoreDawnEntityCaches(
            states: Map<String, String>,
            labels: Map<String, String>,
            units: Map<String, String>,
            attributes: Map<String, Map<String, String>> = emptyMap(),
        ) {
            instance?.restoreDawnCaches(states, labels, units, attributes)
        }

        /** Push parsed hourly forecast rows (from weather.get_forecasts / forecast attr). */
        fun updateDawnHourlyForecast(weatherEntityId: String, hours: JSONArray) {
            instance?.onDawnHourlyForecast(weatherEntityId, hours)
        }

        /**
         * @param weatherEntityId entity id to attach hours to (must match current Dawn weather)
         * @param parseEntityId HA response key to prefer when extracting (may differ if id changed)
         */
        /**
         * @return true when at least one forecast row was applied to the Dawn strip.
         */
        fun updateDawnHourlyForecastJson(
            weatherEntityId: String,
            rawJson: String,
            parseEntityId: String = weatherEntityId,
        ): Boolean {
            val svc = instance ?: return false
            var hours = svc.parseForecastJsonToHours(rawJson, parseEntityId)
            if (hours.length() == 0 && parseEntityId != weatherEntityId) {
                hours = svc.parseForecastJsonToHours(rawJson, weatherEntityId)
            }
            if (hours.length() > 0) {
                svc.onDawnHourlyForecast(weatherEntityId, hours)
                return true
            }
            Log.w(
                TAG,
                "Dawn forecast parse yielded 0 hours for $weatherEntityId " +
                    "(parseAs=$parseEntityId, raw ${rawJson.length} chars: ${rawJson.take(180)})"
            )
            return false
        }
    }

    private val loadTimeoutRunnable = Runnable { onLoadTimeout() }
    private val localPreventiveRefreshRunnable = Runnable { runLocalPreventiveRefresh() }
    private val recoveryRefreshRunnable = Runnable { executeRecoveryRefresh() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsJob = serviceScope.launch {
            val screensaverStore = ScreensaverSettingsStore(applicationContext.screensaverSettingsStore)
            screensaverStore.getFlow().collect { settings ->
                    pixelShiftEnabled = settings.pixelShiftEnabled
                    smartAodEnabled = settings.smartAodEnabled
                    smartAodMaskPercent = settings.smartAodMaskPercent.coerceIn(
                        SmartAodMaskOverlay.MIN_PERCENT,
                        SmartAodMaskOverlay.MAX_PERCENT
                    )
                    syncPixelShift()
                    syncSmartAodMask()
                // Dawn weather strip uses ScreensaverSettings.dawnWeatherEntityId only —
                // never PlayerSettings.haWeatherEntity (that is for overlay / other UIs).
                applyDawnSettings(settings)
            }
        }
    }

    private fun reloadDawnSlotsFromSettings(context: Context) {
        serviceScope.launch {
            val settings = ScreensaverSettingsStore(context.screensaverSettingsStore).get()
            applyDawnSettings(settings)
        }
    }

    private fun applyDawnSettings(settings: ScreensaverSettings) {
        dawnSlotsEnabled = settings.dawnWallpaperEnabled && settings.enableDawnEntitySlots
        dawnSlots = (0 until 4).map { index ->
            com.example.ava.settings.sanitizeDawnEntitySlot(
                settings.dawnEntitySlots.getOrElse(index) { DawnEntitySlot() }
            )
        }
        // Dedicated field OR first weather.* in the 4 slots → bottom strip (never a capsule).
        val nextWeather = resolveDawnWeatherEntityId(settings)
        if (nextWeather != dawnWeatherEntityId) {
            dawnHourlyHours = JSONArray()
            dawnHourlyPastCache.clear()
        }
        dawnWeatherEntityId = nextWeather
        if (nextWeather.isEmpty()) {
            dawnHourlyHours = JSONArray()
            dawnHourlyPastCache.clear()
        }
        dawnWallpaperSourceUrl =
            com.example.ava.settings.sanitizeDawnWallpaperSourceUrl(settings.dawnWallpaperSourceUrl)
        dawnIsoTimeEnabled = settings.dawnIsoTimeEnabled
        dawnMergeWeatherIconsEnabled = settings.dawnMergeWeatherIconsEnabled
        injectDawnConfig()
    }

    private fun onDawnHourlyForecast(weatherEntityId: String, hours: JSONArray) {
        val id = weatherEntityId.trim()
        // Must match the configured Dawn weather ID — never invent / adopt an ID.
        if (dawnWeatherEntityId.isEmpty() || id.isEmpty() || id != dawnWeatherEntityId) return
        if (hours.length() == 0) return
        dawnHourlyHours = hours
        injectDawnHourly()
    }

    /**
     * Map HA weather condition → CSS / icon key used by the wallpaper page.
     * Full HA set: clear-night, cloudy, exceptional, fog, hail, lightning,
     * lightning-rainy, partlycloudy, pouring, rainy, snowy, snowy-rainy,
     * sunny, windy, windy-variant. Unknowns fall back to cloudy (never sunny).
     */
    private fun mapConditionCss(
        raw: String,
        hourOfDay: Int? = null,
        month: Int? = null,
        daily: Boolean = false,
    ): String {
        val key = raw.lowercase(Locale.ROOT).replace('_', '-').trim()
        val base = when {
            key.contains("clear-night") || key == "clearnight" || key == "night" -> "clear-night"
            key.contains("partlycloudy-night") || key.contains("partly-cloudy-night") -> "partlycloudy-night"
            key.contains("cloudy-night") -> "cloudy-night"
            key == "sunny" || key == "clear" -> "sunny"
            key.contains("partly") -> "partlycloudy"
            key.contains("lightning") || key.contains("thunder") -> "lightning-rainy"
            key.contains("hail") -> "hail"
            key.contains("pour") -> "pouring"
            key.contains("snowy-rainy") || (key.contains("snow") && key.contains("rain")) -> "snowy-rainy"
            key.contains("snow") -> "snowy"
            key.contains("rain") -> "rainy"
            key.contains("fog") || key.contains("haze") || key.contains("mist") -> "fog"
            key.contains("windy-variant") || key == "windy-variant" -> "windy-variant"
            key.contains("wind") -> "windy"
            key.contains("exceptional") || key.contains("dust") || key.contains("sand") -> "exceptional"
            key.contains("cloud") -> "cloudy"
            else -> "cloudy"
        }
        // Hourly rows: never show sun icons after dusk / before dawn.
        // Season-aware window matches the HTML continuous sky ramp.
        if (!daily && hourOfDay != null && isNightForecastHour(hourOfDay, month)) {
            return nightConditionVariant(base)
        }
        // Daily strip: force day-side labels (blue/gray palette), never night-* chips.
        if (daily) {
            return when (base) {
                "clear-night" -> "sunny"
                "partlycloudy-night" -> "partlycloudy"
                "cloudy-night" -> "cloudy"
                else -> base
            }
        }
        return base
    }

    /** Season-aware night window for icon correction (local hour, northern mid-lat). */
    private fun isNightForecastHour(hour: Int, month: Int? = null): Boolean {
        val m = month ?: (Calendar.getInstance().get(Calendar.MONTH) + 1)
        // Match HTML seasonSkyWindows: summer dusk~19, winter dusk~16:30→17.
        val (duskStart, dawnEnd) = when {
            m in 5..8 -> 19 to 7          // summer: still day at 18, night from 19
            m == 11 || m == 12 || m in 1..2 -> 17 to 9 // winter: already darkening mid-afternoon
            else -> 18 to 8              // spring / autumn
        }
        return hour < dawnEnd || hour >= duskStart
    }

    private fun nightConditionVariant(dayCondition: String): String {
        return when (dayCondition) {
            "sunny" -> "clear-night"
            "partlycloudy" -> "partlycloudy-night"
            "cloudy", "windy", "windy-variant", "exceptional" -> "cloudy-night"
            else -> dayCondition
        }
    }

    fun parseForecastJsonToHours(rawJson: String, weatherEntityId: String): JSONArray {
        val hours = JSONArray()
        try {
            val forecastArr = coerceForecastArray(rawJson, weatherEntityId) ?: return hours
            val daily = looksLikeDailyForecast(forecastArr)
            val outFmt = if (daily) {
                SimpleDateFormat("EEE", Locale.getDefault())
            } else {
                SimpleDateFormat("h a", Locale.US)
            }
            // Hourly strip: previous hour → next 8 max. Parse a wider window first, then clip.
            val scanLimit = minOf(forecastArr.length(), if (daily) 7 else 24)
            val parsed = ArrayList<JSONObject>(scanLimit)
            for (i in 0 until scanLimit) {
                val item = forecastArr.optJSONObject(i) ?: continue
                val datetime = readForecastDatetime(item)
                val forecastCal = parseForecastDatetimeMs(datetime)?.let { ms ->
                    Calendar.getInstance().apply { timeInMillis = ms }
                }
                val hourOfDay = forecastCal?.get(Calendar.HOUR_OF_DAY)
                val forecastMonth = forecastCal?.let { it.get(Calendar.MONTH) + 1 }
                val condition = mapConditionCss(
                    raw = item.optString("condition", "cloudy"),
                    hourOfDay = hourOfDay,
                    month = forecastMonth,
                    daily = daily,
                )
                val tempNum = readForecastTemperature(item)
                val tempLabel = if (tempNum.isNaN()) {
                    ""
                } else {
                    val rounded = kotlin.math.round(tempNum).toInt()
                    "${rounded}°"
                }
                val label = formatForecastHourLabel(
                    datetime = datetime,
                    outFmt = outFmt,
                    daily = daily,
                )
                parsed.add(
                    JSONObject().apply {
                        put("t", label)
                        put("temp", tempLabel)
                        put("condition", condition)
                        // Daily strip must stay day-palette (blue/gray) — never night dark.
                        put("daily", daily)
                        if (!daily && hourOfDay != null) {
                            put("hour", hourOfDay)
                        }
                        if (forecastMonth != null) {
                            put("month", forecastMonth)
                        }
                    }
                )
            }
            val windowed = if (daily) {
                parsed.take(7)
            } else {
                // Upstream is Now+future; merge short past cache, then band for WebView.
                val merged = mergeDawnHourlyWithPastCache(parsed, lookbackMax = 3)
                selectHourlyForecastWindow(merged, maxHours = 16, lookbackMax = 3)
            }
            windowed.forEach { hours.put(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse weather forecast JSON", e)
        }
        return hours
    }

    private fun epochHourKey(ms: Long = System.currentTimeMillis()): Long = ms / 3_600_000L

    private fun epochHourKeyForClockHour(hourOfDay: Int, nowMs: Long = System.currentTimeMillis()): Long {
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowH = now.get(Calendar.HOUR_OF_DAY)
        val nowEpoch = epochHourKey(nowMs)
        val deltaPast = (nowH - hourOfDay + 24) % 24
        return if (deltaPast <= 12) {
            nowEpoch - deltaPast
        } else {
            nowEpoch + ((hourOfDay - nowH + 24) % 24)
        }
    }

    /** Remember every hour row we see so past slots survive after HA drops them. */
    private fun ingestDawnHourlyPastCache(rows: List<JSONObject>) {
        if (rows.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val nowEpoch = epochHourKey(nowMs)
        for (row in rows) {
            if (row.optBoolean("daily", false)) continue
            if (!row.has("hour") || row.isNull("hour")) continue
            val h = row.optInt("hour", -1)
            if (h !in 0..23) continue
            val key = epochHourKeyForClockHour(h, nowMs)
            dawnHourlyPastCache[key] = JSONObject(row.toString())
        }
        val minKey = nowEpoch - dawnHourlyPastCacheMaxAgeHours
        val maxKey = nowEpoch + 16
        val stale = dawnHourlyPastCache.keys.filter { it < minKey || it > maxKey }
        stale.forEach { dawnHourlyPastCache.remove(it) }
    }

    /**
     * Combine ≤3h local past cache with upstream Now+future rows.
     * Fresh upstream wins on conflict.
     */
    private fun mergeDawnHourlyWithPastCache(
        upstream: List<JSONObject>,
        lookbackMax: Int = 3,
    ): List<JSONObject> {
        ingestDawnHourlyPastCache(upstream)
        val nowMs = System.currentTimeMillis()
        val nowEpoch = epochHourKey(nowMs)
        val byEpoch = LinkedHashMap<Long, JSONObject>()

        for (delta in lookbackMax downTo 1) {
            val key = nowEpoch - delta
            dawnHourlyPastCache[key]?.let { cached ->
                byEpoch[key] = JSONObject(cached.toString())
            }
        }
        for (row in upstream) {
            if (row.optBoolean("daily", false)) continue
            if (!row.has("hour") || row.isNull("hour")) continue
            val h = row.optInt("hour", -1)
            if (h !in 0..23) continue
            val key = epochHourKeyForClockHour(h, nowMs)
            byEpoch[key] = JSONObject(row.toString())
        }
        // Keep any cached near-future gaps until upstream catches up.
        for (delta in 0..15) {
            val key = nowEpoch + delta
            if (!byEpoch.containsKey(key)) {
                dawnHourlyPastCache[key]?.let { byEpoch[key] = JSONObject(it.toString()) }
            }
        }
        return byEpoch.entries.sortedBy { it.key }.map { it.value }
    }

    /**
     * Dynamic hourly band for the strip (and a wider cache for hour-roll slides):
     * look back at most 3 hours so Now sits near mid-strip, then fill forward.
     * Past hours come from [dawnHourlyPastCache] because HA usually omits them.
     */
    private fun selectHourlyForecastWindow(
        parsed: List<JSONObject>,
        maxHours: Int = 8,
        lookbackMax: Int = 3,
    ): List<JSONObject> {
        if (parsed.isEmpty()) return emptyList()
        val nowH = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val lookback = minOf(lookbackMax, maxOf(0, maxHours - 1))
        val startH = (nowH - lookback + 24) % 24

        fun hourOf(obj: JSONObject): Int? {
            if (!obj.has("hour") || obj.isNull("hour")) return null
            val h = obj.optInt("hour", -1)
            return if (h in 0..23) h else null
        }

        fun labelFor(hour: Int): String {
            if (hour == nowH) return "Now"
            if (dawnIsoTimeEnabled) return String.format(Locale.US, "%02d", hour)
            val h12 = hour % 12
            val display = if (h12 == 0) 12 else h12
            val ap = if (hour < 12) "AM" else "PM"
            return "$display $ap"
        }

        fun stamp(obj: JSONObject, hour: Int): JSONObject {
            return JSONObject(obj.toString()).apply {
                put("hour", hour)
                put("t", labelFor(hour))
            }
        }

        val byHour = LinkedHashMap<Int, JSONObject>()
        for (obj in parsed) {
            val h = hourOf(obj) ?: continue
            if (!byHour.containsKey(h)) byHour[h] = obj
        }

        if (byHour.isEmpty()) {
            return parsed.take(maxHours)
        }

        val ideal = ArrayList<JSONObject>(maxHours)
        for (i in 0 until maxHours) {
            val want = (startH + i) % 24
            val src = byHour[want] ?: continue
            ideal.add(stamp(src, want))
        }
        if (ideal.size >= minOf(maxHours, 6) || ideal.size == byHour.size) {
            return ideal.take(maxHours)
        }

        var start = parsed.indexOfFirst { hourOf(it) == startH }
        if (start < 0) start = parsed.indexOfFirst { hourOf(it) == nowH }
        if (start < 0) start = 0
        if (start > parsed.size - maxHours) {
            start = maxOf(0, parsed.size - maxHours)
        }
        return parsed.subList(start, minOf(start + maxHours, parsed.size)).mapNotNull { obj ->
            val h = hourOf(obj) ?: return@mapNotNull stamp(obj, nowH)
            stamp(obj, h)
        }
    }

    /** Daily rows usually carry templow and/or ~24h spacing (demo weather, met.no daily…). */
    private fun looksLikeDailyForecast(arr: JSONArray): Boolean {
        if (arr.length() == 0) return false
        val first = arr.optJSONObject(0) ?: return false
        if (first.has("templow") && !first.isNull("templow")) return true
        if (arr.length() < 2) return false
        val second = arr.optJSONObject(1) ?: return false
        val t0 = parseForecastDatetimeMs(readForecastDatetime(first)) ?: return false
        val t1 = parseForecastDatetimeMs(readForecastDatetime(second)) ?: return false
        return kotlin.math.abs(t1 - t0) >= 12L * 60L * 60L * 1000L
    }

    /**
     * Normalize HA forecast payloads into a JSONArray of hour objects.
     *
     * Supported shapes (non-exhaustive, all real in the wild):
     * - Raw attribute array: `[ {...}, ... ]`
     * - Attribute / service object with keys: `forecast`, `hourly_forecast`, `hourly`
     * - `weather.get_forecasts` REST: `{ "service_response": { "weather.x": { "forecast": [...] } } }`
     * - ESPHome / WS action-response: `{ "response": { "weather.x": { "forecast": [...] } } }`
     * - Flat: `{ "weather.x": { "forecast": [...] } }` or `{ "forecast": [...] }`
     * - Double-encoded JSON string values for those keys
     */
    private fun coerceForecastArray(rawJson: String, weatherEntityId: String): JSONArray? {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("[")) {
            return JSONArray(trimmed)
        }
        if (!trimmed.startsWith("{")) return null
        return extractForecastArray(JSONObject(trimmed), weatherEntityId)
    }

    private fun readForecastTemperature(item: JSONObject): Double {
        val keys = arrayOf(
            "temperature",
            "native_temperature",
            "templow", // some daily rows; ignore if NaN next
            "temp",
        )
        for (key in keys) {
            if (key == "templow") continue // never use low as primary for hourly strip
            if (!item.has(key) || item.isNull(key)) continue
            val n = item.optDouble(key, Double.NaN)
            if (!n.isNaN()) return n
            // String temps ("21.0", "21°C")
            val asStr = item.optString(key, "").replace("°C", "").replace("°F", "").replace("°", "").trim()
            asStr.toDoubleOrNull()?.let { return it }
        }
        return Double.NaN
    }

    private fun readForecastDatetime(item: JSONObject): String {
        val keys = arrayOf("datetime", "date_time", "time", "timestamp", "dt")
        for (key in keys) {
            if (!item.has(key) || item.isNull(key)) continue
            // Numeric epoch (s / ms)
            val asDouble = item.optDouble(key, Double.NaN)
            if (!asDouble.isNaN() && asDouble > 1_000_000) {
                val ms = if (asDouble < 1e12) (asDouble * 1000).toLong() else asDouble.toLong()
                return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(ms))
            }
            val asStr = item.optString(key, "").trim()
            if (asStr.isNotEmpty()) return asStr
        }
        return ""
    }

    private fun asJsonArray(value: Any?): JSONArray? {
        when (value) {
            null, JSONObject.NULL -> return null
            is JSONArray -> return value
            is String -> {
                val s = value.trim()
                if (s.isEmpty()) return null
                return try {
                    when {
                        s.startsWith("[") -> JSONArray(s)
                        s.startsWith("{") -> extractForecastArray(JSONObject(s), "")
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
            else -> return null
        }
    }

    private fun pickForecastArrays(obj: JSONObject?): List<JSONArray> {
        if (obj == null) return emptyList()
        // Prefer hourly-ish keys before generic/daily.
        val preferredKeys = arrayOf(
            "hourly_forecast",
            "hourly",
            "forecast_hourly",
            "forecast",
            "forecasts",
        )
        val found = mutableListOf<JSONArray>()
        for (key in preferredKeys) {
            if (!obj.has(key)) continue
            asJsonArray(obj.opt(key))?.let { found.add(it) }
        }
        return found
    }

    private fun extractForecastArray(root: JSONObject, weatherEntityId: String): JSONArray? {
        val envelopes = listOfNotNull(
            root.optJSONObject("service_response"),
            root.optJSONObject("response"),
            root.optJSONObject("data"),
            root,
        )

        fun preferHourly(arrays: List<JSONArray>): JSONArray? {
            // First non-empty array wins (keys already ordered hourly-first).
            return arrays.firstOrNull { it.length() > 0 }
        }

        val target = weatherEntityId.trim()
        for (envelope in envelopes) {
            if (target.isNotEmpty()) {
                preferHourly(pickForecastArrays(envelope.optJSONObject(target)))?.let { return it }
            }
            preferHourly(pickForecastArrays(envelope))?.let { return it }

            // Exact weather.* key first, then ANY weather.* (entity id may have changed
            // between request and response — e.g. weather.tuo_xia → weather.tuo_xian).
            var fallback: JSONArray? = null
            val keys = envelope.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!key.startsWith("weather.")) continue
                val arr = preferHourly(pickForecastArrays(envelope.optJSONObject(key))) ?: continue
                if (target.isEmpty() || key == target) return arr
                if (fallback == null) fallback = arr
            }
            if (fallback != null) return fallback

            // ESPHome sometimes stringifies the inner payload.
            val nested = envelope.opt("response")
            if (nested is String) {
                val inner = nested.trim()
                if (inner.startsWith("{")) {
                    try {
                        extractForecastArray(JSONObject(inner), target)?.let { return it }
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            }
        }
        return null
    }

    private fun parseForecastDatetimeMs(datetime: String): Long? {
        if (datetime.isBlank()) return null
        return try {
            val cleaned = datetime.trim()
                .replace(Regex("\\.\\d+"), "")
                .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
            val patterns = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd",
            )
            for (pattern in patterns) {
                try {
                    val parsed = SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }
                        .parse(cleaned)
                    if (parsed != null) return parsed.time
                } catch (_: Exception) {
                    // try next
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun formatForecastHourLabel(
        datetime: String,
        outFmt: SimpleDateFormat,
        daily: Boolean = false,
    ): String {
        if (datetime.isBlank()) return ""
        return try {
            val ms = parseForecastDatetimeMs(datetime) ?: return ""
            val parsed = Date(ms)
            val deltaMs = kotlin.math.abs(parsed.time - System.currentTimeMillis())
            when {
                !daily && deltaMs <= 50L * 60L * 1000L -> "Now"
                daily && deltaMs <= 18L * 60L * 60L * 1000L -> "Today"
                else -> outFmt.format(parsed)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun onDawnEntityState(entityId: String, state: String) {
        val id = entityId.trim()
        if (id.isEmpty()) return
        dawnEntityStates[id] = state
        injectDawnEntityUpdate(id)
    }

    private fun onDawnEntityLabel(entityId: String, label: String) {
        val id = entityId.trim()
        if (id.isEmpty()) return
        dawnEntityLabels[id] = label
        injectDawnEntityUpdate(id)
    }

    private fun onDawnEntityUnit(entityId: String, unit: String) {
        val id = entityId.trim()
        if (id.isEmpty()) return
        dawnEntityUnits[id] = unit
        injectDawnEntityUpdate(id)
    }

    private fun onDawnEntityAttribute(entityId: String, attribute: String, value: String) {
        val id = entityId.trim()
        val attr = attribute.trim()
        if (id.isEmpty() || attr.isEmpty()) return
        dawnEntityAttrs.getOrPut(id) { mutableMapOf() }[attr] = value
        injectDawnEntityUpdate(id)
    }

    private fun restoreDawnCaches(
        states: Map<String, String>,
        labels: Map<String, String>,
        units: Map<String, String>,
        attributes: Map<String, Map<String, String>> = emptyMap(),
    ) {
        dawnEntityStates.clear()
        dawnEntityLabels.clear()
        dawnEntityUnits.clear()
        dawnEntityAttrs.clear()
        dawnEntityStates.putAll(states)
        dawnEntityLabels.putAll(labels)
        dawnEntityUnits.putAll(units)
        attributes.forEach { (id, attrs) ->
            if (id.isBlank() || attrs.isEmpty()) return@forEach
            dawnEntityAttrs[id] = attrs.toMutableMap()
        }
        injectDawnConfig()
    }

    /**
     * HA may have already delivered Dawn entity/forecast updates before this WebView
     * existed (dropped on null [instance]). Ask VoiceSatellite to replay its caches,
     * then re-subscribe so HA re-sends current state when caches were empty.
     */
    private fun requestDawnDataRefreshFromVoiceSatellite(forceForecast: Boolean) {
        if (!isDawnWallpaperPage()) return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val vs = VoiceSatelliteService.getInstance()
                if (vs == null) {
                    Log.d(TAG, "Dawn refresh skipped: VoiceSatellite not running")
                    return@launch
                }
                // Replay caches, then resubscribe once. resubscribeDawnEntitySlots
                // already requests a (debounced) forecast — do not also push(force).
                vs.pushDawnScreensaverData(forceForecastRequest = false)
                vs.resubscribeDawnEntitySlots()
                // forceForecast reserved for callers that need an explicit refresh;
                // currently covered by subscribeDawnWeatherForecast inside resubscribe.
                if (forceForecast) {
                    Log.d(TAG, "Dawn refresh: forecast pull via resubscribe")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh Dawn data from VoiceSatellite", e)
            }
        }
    }

    private fun isDawnWallpaperPage(): Boolean {
        val url = currentUrl.ifBlank { webView?.url.orEmpty() }
        return url.contains("dawn_wallpaper.html", ignoreCase = true)
    }

    private fun injectDawnConfig() {
        if (!isPageLoaded || !isDawnWallpaperPage()) return
        val payload = buildDawnConfigJson()
        val js = "window.AvaDawn&&window.AvaDawn.setConfig($payload);"
        mainHandler.post {
            try {
                webView?.evaluateJavascript(js, null)
                // setConfig used to clear hourly when the array was empty; always
                // re-push hours if we have them so the strip cannot flash away.
                if (dawnSlotsEnabled && dawnWeatherEntityId.isNotEmpty() && dawnHourlyHours.length() > 0) {
                    injectDawnHourly()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inject Dawn slot config", e)
            }
        }
    }

    private fun injectDawnHourly() {
        if (!isPageLoaded || !isDawnWallpaperPage() || !dawnSlotsEnabled) return
        if (dawnWeatherEntityId.isEmpty()) return
        val hours = resolveHourlyForInject()
        if (hours.length() == 0) return
        val payload = JSONObject().apply {
            put("weatherEntityId", dawnWeatherEntityId)
            put("hours", hours)
        }
        val js = "window.AvaDawn&&window.AvaDawn.setHourly($payload);"
        mainHandler.post {
            try {
                webView?.evaluateJavascript(js, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inject Dawn hourly weather", e)
            }
        }
    }

    private fun resolveHourlyForInject(): JSONArray {
        if (dawnWeatherEntityId.isEmpty()) return JSONArray()
        return dawnHourlyHours
    }

    private fun injectDawnEntityUpdate(entityId: String) {
        if (!isPageLoaded || !isDawnWallpaperPage() || !dawnSlotsEnabled) return
        val slot = dawnSlots.firstOrNull { it.entityId.trim() == entityId } ?: return
        val name = resolveDawnDisplayName(slot, entityId)
        val iconKey = resolveDawnSlotIcon(slot, entityId, name)
        val payload = JSONObject().apply {
            put("entityId", entityId)
            put("icon", iconKey)
            put("iconSrc", mdiIconDataUri(iconKey))
            put("name", name)
            put("state", resolveDawnSlotState(entityId))
        }
        val js = "window.AvaDawn&&window.AvaDawn.updateEntity($payload);"
        mainHandler.post {
            try {
                webView?.evaluateJavascript(js, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inject Dawn entity update", e)
            }
        }
    }

    private fun buildDawnConfigJson(): String {
        // Capsules: filled non-weather.* slots only (deduped). weather.* → bottom strip.
        val slotsJson = JSONArray()
        if (dawnSlotsEnabled) {
            val seen = linkedSetOf<String>()
            dawnSlots.forEach { slot ->
                val entityId = slot.entityId.trim()
                if (entityId.isEmpty()) return@forEach
                if (!com.example.ava.settings.isAllowedDawnSlotEntityId(entityId)) return@forEach
                if (isDawnWeatherEntityId(entityId)) return@forEach
                if (!seen.add(entityId)) return@forEach
                val name = resolveDawnDisplayName(slot, entityId)
                val iconKey = resolveDawnSlotIcon(slot, entityId, name)
                slotsJson.put(
                    JSONObject().apply {
                        put("entityId", entityId)
                        put("icon", iconKey)
                        // Built-in drawable → data URI (WebView cannot load MDI CDN webfont reliably).
                        put("iconSrc", mdiIconDataUri(iconKey))
                        put("name", name)
                        put("state", resolveDawnSlotState(entityId))
                    }
                )
            }
        }
        val weatherId = if (dawnSlotsEnabled) dawnWeatherEntityId else ""
        val hourly = if (weatherId.isNotEmpty()) resolveHourlyForInject() else JSONArray()
        return JSONObject().apply {
            put("enabled", dawnSlotsEnabled)
            put("slots", slotsJson)
            put("weatherEntityId", weatherId)
            put("hourly", hourly)
            // Empty string = built-in default; never omit so HTML can clear a custom source.
            put("wallpaperSource", dawnWallpaperSourceUrl)
            put("isoTime", dawnIsoTimeEnabled)
            put("mergeWeatherIcons", dawnMergeWeatherIconsEnabled)
        }.toString()
    }

    private fun resolveDawnDisplayName(slot: DawnEntitySlot, entityId: String): String {
        if (slot.label.isNotBlank()) return slot.label.trim()
        return truncateDawnAutoName(
            dawnEntityLabels[entityId] ?: guessDawnLabelFromEntityId(entityId)
        )
    }

    /**
     * Prefer a carefully guessed icon when the stored one is a weak default
     * (HA logo / generic power / eye) that disagrees with entity id or label
     * (e.g. switch showing "HOME 温度" with a power glyph).
     */
    private fun resolveDawnSlotIcon(
        slot: DawnEntitySlot,
        entityId: String,
        displayName: String,
    ): String {
        val stored = slot.icon.trim().ifBlank { "mdi:home-assistant" }
        val guessed = com.example.ava.settings.guessDawnSlotIcon(entityId, displayName)
        val weakDefaults = setOf(
            "mdi:home-assistant",
            "mdi:home",
            "mdi:power",
            "mdi:eye",
        )
        return if (stored in weakDefaults && guessed != stored) guessed else stored
    }

    /** Render app MDI vector drawable to a white PNG data URI for the wallpaper WebView. */
    private fun mdiIconDataUri(icon: String): String {
        val key = icon.ifBlank { "mdi:home-assistant" }
        dawnIconDataUriCache[key]?.let { return it }
        val uri = try {
            val resId = MdiIconMapper.getIconResId(key)
            val drawable = ContextCompat.getDrawable(this, resId)?.mutate() ?: return ""
            // Tint white so icons read on dark glass capsules / wallpapers.
            drawable.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            val bmp = drawableToBitmap(drawable, 64)
            try {
                val baos = ByteArrayOutputStream()
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)) return ""
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                "data:image/png;base64,$b64"
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to encode MDI icon $key", e)
            ""
        }
        if (uri.isNotEmpty()) dawnIconDataUriCache[key] = uri
        return uri
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable) {
            val src = drawable.bitmap
            if (src != null && !src.isRecycled) {
                return Bitmap.createScaledBitmap(src, size, size, true)
            }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    /**
     * Capsule value:
     * - weather.* → temperature
     * - light.* / switch.* → On / Off (lights may append brightness %)
     * - otherwise HA state (+ unit)
     */
    private fun resolveDawnSlotState(entityId: String): String {
        val id = entityId.trim()
        if (id.startsWith("weather.")) {
            val temp = dawnEntityAttrs[id]?.get("temperature").orEmpty()
            if (temp.isNotBlank()) {
                val unit = dawnEntityUnits[id].orEmpty().ifBlank { "°" }
                val u = if (unit.startsWith("°") || unit == "%") unit else "°"
                return formatDawnState(temp, u)
            }
        }
        if (id.startsWith("light.") || id.startsWith("switch.")) {
            return formatDawnToggleState(id)
        }
        return formatDawnState(
            dawnEntityStates[id].orEmpty(),
            dawnEntityUnits[id].orEmpty()
        )
    }

    /** Binary / light entities: never dump raw brightness as the primary state. */
    private fun formatDawnToggleState(entityId: String): String {
        val raw = dawnEntityStates[entityId].orEmpty().trim()
        if (raw.isEmpty()) return "--"
        return when (raw.lowercase(Locale.ROOT)) {
            "unavailable", "unknown" -> "--"
            "off", "false", "0" -> "Off"
            "on", "true" -> {
                if (!entityId.startsWith("light.")) return "On"
                val bri = dawnEntityAttrs[entityId]?.get("brightness")
                    ?.trim()
                    ?.toDoubleOrNull()
                if (bri == null || bri <= 0) return "On"
                // HA brightness attribute is 0–255
                val pct = ((bri / 255.0) * 100.0).toInt().coerceIn(1, 100)
                "On · $pct%"
            }
            else -> formatDawnState(raw, "")
        }
    }

    private fun formatDawnState(rawState: String, unit: String = ""): String {
        if (rawState.isBlank()) return "--"
        return when (rawState.lowercase(Locale.ROOT)) {
            "open" -> "Open"
            "closed" -> "Closed"
            "locked" -> "Locked"
            "unlocked" -> "Unlocked"
            "on" -> "On"
            "off" -> "Off"
            "home" -> "Home"
            "not_home", "away" -> "Away"
            "unavailable", "unknown" -> "--"
            "detected" -> "Detected"
            "clear" -> "Clear"
            "active" -> "Active"
            "idle" -> "Idle"
            else -> {
                val number = rawState.toDoubleOrNull()
                if (number != null) {
                    val formatted = formatDawnSensorNumber(rawState)
                    val u = unit.trim()
                    if (u.isEmpty()) return formatted
                    return if (u.startsWith("°") || u == "%") "$formatted$u" else "$formatted $u"
                }
                rawState
                    .replace('_', ' ')
                    .split(' ')
                    .filter { it.isNotEmpty() }
                    .joinToString(" ") { part ->
                        part.replaceFirstChar { c ->
                            if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString()
                        }
                    }
            }
        }
    }

    private fun formatDawnSensorNumber(raw: String): String {
        val value = raw.toDoubleOrNull() ?: return raw
        val dotIdx = raw.indexOf('.')
        if (dotIdx < 0) return raw
        val decimals = raw.length - dotIdx - 1
        return if (decimals <= 1) raw else String.format(Locale.US, "%.1f", value)
    }

    private fun guessDawnLabelFromEntityId(entityId: String): String {
        val raw = entityId.substringAfter('.', entityId)
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString()
                }
            }
        return raw.ifBlank { entityId }
    }

    private fun truncateDawnAutoName(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val hasChinese = trimmed.any { it.code in 0x4E00..0x9FFF }
        if (hasChinese) {
            return if (trimmed.length > 7) trimmed.take(7) else trimmed
        }
        return trimmed
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString(" ")
    }

    private fun syncSmartAodMask() {
        smartAodOverlay?.sync(
            enabled = smartAodEnabled,
            maskPercent = smartAodMaskPercent,
            showing = isActivelyDisplayed()
        )
        OverlayZOrderCoordinator.syncFabForAod()
    }

    private fun syncPixelShift() {
        val container = containerView
        val page = webView
        // Translate the bleed-sized WebView inside a clipped black host — never the window.
        if (container == null || page == null || !isActivelyDisplayed()) {
            pixelShift.pause()
            return
        }
        pixelShift.attach(page)
        pixelShift.setEnabled(pixelShiftEnabled)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_SHOW" -> {
                val url = intent.getStringExtra("url") ?: ""
                showWebView(url)
            }
            "ACTION_HIDE" -> {
                hideWebView()
            }
            "ACTION_DESTROY" -> {
                destroyWebView()
                stopSelf()
            }
            "ACTION_UPDATE" -> {
                val url = intent.getStringExtra("url") ?: ""
                updateUrlInternal(url)
            }
            "ACTION_PAUSE" -> {
                // Pause only this WebView; pauseTimers() is process-global and would
                // also freeze the browser WebView running in the same process.
                webView?.onPause()
                pixelShift.pause()
                Log.d(TAG, "WebView paused")
            }
            "ACTION_RESUME" -> {
                webView?.onResume()
                if (!isPageLoaded && currentUrl.isNotBlank()) {
                    Log.d(TAG, "WebView resume: page not loaded, reloading")
                    webView?.reload()
                }
                syncPixelShift()
                Log.d(TAG, "WebView resumed")
            }
            "ACTION_BRING_TO_FRONT" -> {
                bringToFront()
                // Self hard-raise buries equal-type peers — put FAB / chrome / voice back above.
                OverlayZOrderCoordinator.raiseInteractiveAboveScreensaver()
            }
            "ACTION_FORCE_REFRESH" -> {
                performSoftReload("user_request")
            }
        }
        // Screensaver is re-shown by ScreensaverController when idle; no need to keep
        // an empty zombie service alive after a low-memory kill.
        return START_NOT_STICKY
    }

    private fun showWebView(url: String) {
        currentUrl = url
        hasTriedHttpFallback = false
        // Invalidate any in-flight reveal-out / pending reveal-in.
        hideEpoch += 1
        cancelPendingRevealIn()
        cancelRevealAnimator()
        // Soft mask fully closed until content is ready (not alpha=0 — that fights the mask).
        containerView?.snapHidden()

        if (containerView != null && webView != null) {
            // WebView exists, re-attach if hidden-detached then show
            attachContainerIfNeeded()
            bringToFront()
            // Idle screensaver must cover passive dashboards only — FAB / voice stay above.
            OverlayZOrderCoordinator.raiseInteractiveAboveScreensaver()
            webView?.onResume()
            syncPixelShift()

            // Restore high priority for hardware rendering
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView?.setRendererPriorityPolicy(
                    WebView.RENDERER_PRIORITY_IMPORTANT,
                    false
                )
            }

            // Check if needs reload
            if (!isPageLoaded) {
                Log.d(TAG, "showWebView: page not loaded, reloading")
                webView?.reload()
                scheduleLoadTimeoutCheck()
                scheduleRevealInWhenReady(pageAlreadyReady = false)
            } else {
                Log.d(TAG, "showWebView: page ready, revealing after settle")
                // Page stayed warm — re-inject + pull latest HA caches (may have arrived while hidden).
                injectDawnConfig()
                if (isDawnWallpaperPage()) {
                    requestDawnDataRefreshFromVoiceSatellite(forceForecast = true)
                }
                scheduleRevealInWhenReady(pageAlreadyReady = true)
            }
            startHealthMonitor()
            syncPixelShift()
            syncSmartAodMask()
            return
        }

        if (url.isBlank()) return

        try {
            webView = WebView(this)
            setupWebView()

            val params = WindowManager.LayoutParams().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    type = WindowManager.LayoutParams.TYPE_PHONE
                }
                @Suppress("DEPRECATION")
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                format = PixelFormat.TRANSLUCENT
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                OverlayOrientation.apply(this)
            }

            val aod = SmartAodMaskOverlay(this)
            smartAodOverlay = aod
            val container = BlurFadeRevealLayout(this).apply {
                // Host stays put; WebView shifts inside ±MAX_SHIFT bleed under clip.
                setBackgroundColor(Color.BLACK)
                clipChildren = true
                clipToPadding = true
                addView(webView, ScreensaverPixelShift.bleedLayoutParams())
                aod.attachTo(this)
                setOnTouchListener { view, event ->
                    if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                        ScreensaverController.onUserInteraction()
                        com.example.ava.sensor.ScreenTouchSensor.onUserTouch()
                    }
                    com.example.ava.sensor.ScreenGestureRecognizer.onTouchEvent(
                        view.context,
                        event,
                        view.width.coerceAtLeast(1),
                        view.height.coerceAtLeast(1),
                    )
                    true
                }
                snapHidden() // GONE until page-ready revealIn
            }

            containerView = container
            containerParams = params
            attachContainerIfNeeded()
            // Fresh addView is topmost in the TYPE_APPLICATION_OVERLAY tier — reassert FAB / voice.
            OverlayZOrderCoordinator.raiseInteractiveAboveScreensaver()
            loadUrl(url)
            startHealthMonitor()
            scheduleRevealInWhenReady(pageAlreadyReady = false)
            syncPixelShift()
            syncSmartAodMask()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show screensaver WebView", e)
        }
    }

    private fun cancelPendingRevealIn() {
        pendingRevealAfterLoad = false
        mainHandler.removeCallbacks(revealSettleRunnable)
        mainHandler.removeCallbacks(revealMaxWaitRunnable)
    }

    private fun cancelRevealAnimator() {
        containerView?.cancelReveal()
    }

    /**
     * Do not reveal until the page has content (or [REVEAL_MAX_WAIT_MS] elapses).
     * Immediate reveal on a blank WebView is what made the old fade-in look broken.
     */
    private fun scheduleRevealInWhenReady(pageAlreadyReady: Boolean) {
        cancelPendingRevealIn()
        if (pageAlreadyReady) {
            mainHandler.postDelayed(revealSettleRunnable, REVEAL_SETTLE_MS)
            return
        }
        pendingRevealAfterLoad = true
        mainHandler.postDelayed(revealMaxWaitRunnable, REVEAL_MAX_WAIT_MS)
    }

    private fun onPageReadyForReveal() {
        if (!pendingRevealAfterLoad) return
        pendingRevealAfterLoad = false
        mainHandler.removeCallbacks(revealMaxWaitRunnable)
        mainHandler.removeCallbacks(revealSettleRunnable)
        mainHandler.postDelayed(revealSettleRunnable, REVEAL_SETTLE_MS)
    }

    private fun startBlurFadeIn() {
        val container = containerView ?: return
        if (!isContainerAttached) return
        val epoch = hideEpoch
        // Attach may leave the host GONE; become visible only as blur-fade starts.
        if (container.width <= 0 || container.height <= 0) {
            container.post {
                if (epoch != hideEpoch) return@post
                if (!isContainerAttached) return@post
                container.revealIn(BlurFadeRevealLayout.REVEAL_IN_MS)
                syncPixelShift()
                syncSmartAodMask()
                notifyCoverPeersReady()
            }
            return
        }
        container.revealIn(BlurFadeRevealLayout.REVEAL_IN_MS)
        syncPixelShift()
        syncSmartAodMask()
        notifyCoverPeersReady()
    }

    /**
     * Once per show: let HA browser deepen COVERED dormancy and allow CPU throttle.
     * Must run after reveal starts so dual-pane + steward do not starve this WebView.
     */
    private fun notifyCoverPeersReady() {
        if (coverPeersNotifiedEpoch == hideEpoch) return
        coverPeersNotifiedEpoch = hideEpoch
        ScreensaverController.onScreensaverContentReady()
    }

    private fun revealOutThen(onHidden: () -> Unit) {
        val container = containerView
        if (container == null || !isContainerAttached) {
            onHidden()
            return
        }
        cancelPendingRevealIn()
        val epoch = hideEpoch
        if (container.visibility != View.VISIBLE || container.alpha <= 0.01f) {
            container.snapHidden()
            onHidden()
            return
        }
        container.revealOut(BlurFadeRevealLayout.REVEAL_OUT_MS) {
            if (epoch != hideEpoch) return@revealOut
            container.snapHidden()
            onHidden()
        }
    }

    private fun isActivelyDisplayed(): Boolean {
        return isContainerAttached &&
            containerView?.visibility == View.VISIBLE &&
            webView != null
    }

    private fun isLocalAssetUrl(url: String = currentUrl): Boolean {
        return url.startsWith("file://")
    }

    private fun startHealthMonitor() {
        if (healthMonitorActive) return
        healthMonitorActive = true
        scheduleLoadTimeoutCheck()
        if (isLocalAssetUrl()) {
            scheduleLocalPreventiveRefresh()
        }
    }

    private fun stopHealthMonitor() {
        healthMonitorActive = false
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        mainHandler.removeCallbacks(localPreventiveRefreshRunnable)
        mainHandler.removeCallbacks(recoveryRefreshRunnable)
        pendingRecoveryReason = null
    }

    private fun scheduleLoadTimeoutCheck() {
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        if (!healthMonitorActive) return
        mainHandler.postDelayed(loadTimeoutRunnable, loadTimeoutMs)
    }

    private fun cancelLoadTimeoutCheck() {
        mainHandler.removeCallbacks(loadTimeoutRunnable)
    }

    private fun scheduleLocalPreventiveRefresh() {
        mainHandler.removeCallbacks(localPreventiveRefreshRunnable)
        if (!healthMonitorActive || !isLocalAssetUrl()) return
        mainHandler.postDelayed(localPreventiveRefreshRunnable, LOCAL_PREVENTIVE_REFRESH_INTERVAL_MS)
    }

    private fun onLoadTimeout() {
        if (!isActivelyDisplayed() || isPageLoaded) return
        val elapsed = System.currentTimeMillis() - loadStartTime
        if (elapsed < loadTimeoutMs) {
            scheduleLoadTimeoutCheck()
            return
        }
        Log.w(TAG, "Screensaver load timeout after ${elapsed}ms")
        requestRecoveryRefresh("load_timeout")
    }

    private fun runLocalPreventiveRefresh() {
        scheduleLocalPreventiveRefresh()
        if (!isActivelyDisplayed() || !isLocalAssetUrl() || currentUrl.isBlank()) return
        if (!isPageLoaded) return
        val sinceLoad = System.currentTimeMillis() - loadStartTime
        if (sinceLoad < 30_000L) return
        Log.i(TAG, "Local asset preventive refresh after ${sinceLoad / 1000}s")
        performSoftReload("local_preventive")
    }

    private fun requestRecoveryRefresh(reason: String) {
        if (!isActivelyDisplayed() || currentUrl.isBlank()) return
        val now = System.currentTimeMillis()
        val sinceLoad = now - loadStartTime
        if (!isPageLoaded && sinceLoad < MIN_LOAD_AGE_BEFORE_RECOVERY_MS) {
            Log.d(TAG, "Recovery suppressed (initial load), reason=$reason")
            return
        }
        if (now - lastRecoveryRefreshAt < MIN_RECOVERY_INTERVAL_MS) {
            Log.d(TAG, "Recovery suppressed (cooldown), reason=$reason")
            return
        }
        pendingRecoveryReason = reason
        mainHandler.removeCallbacks(recoveryRefreshRunnable)
        mainHandler.postDelayed(recoveryRefreshRunnable, RECOVERY_DEBOUNCE_MS)
    }

    private fun executeRecoveryRefresh() {
        val reason = pendingRecoveryReason ?: return
        pendingRecoveryReason = null
        if (!isActivelyDisplayed() || currentUrl.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastRecoveryRefreshAt < MIN_RECOVERY_INTERVAL_MS) return
        lastRecoveryRefreshAt = now
        Log.i(TAG, "Recovery refresh: $reason")
        performSoftReload(reason)
    }

    private fun performSoftReload(reason: String) {
        if (webView == null || currentUrl.isBlank()) return
        hasTriedHttpFallback = false
        isPageLoaded = false
        loadStartTime = System.currentTimeMillis()
        webView?.reload()
        scheduleLoadTimeoutCheck()
        Log.d(TAG, "Soft reload ($reason) for $currentUrl")
    }
    
    private fun bringToFront() {
        OverlayZOrderCoordinator.bringToFront(windowManager, containerView, containerParams, TAG)
    }

    private fun bringToFrontIfControllerVisible() {
        // The container remains GONE while waiting for its first painted frame. Use the
        // controller's desired visibility so a concurrent dashboard restack cannot bury it.
        if (!ScreensaverController.isScreensaverVisible()) return
        // Full now-playing: soft-pause path owns hide; do not reassert over the song container.
        if (VinylCoverService.isFullPlayerBlockingScreensaver()) return
        // Same hole as Simple Clock: restacking this window punches through the AOD cover.
        if (smartAodEnabled && isActivelyDisplayed()) return
        bringToFront()
    }

    private fun attachContainerIfNeeded() {
        val view = containerView ?: return
        val params = containerParams ?: return
        if (view.isAttachedToWindow) {
            isContainerAttached = true
            return
        }
        try {
            // Soft mask closed until reveal-in after the page paints.
            view.snapHidden()
            windowManager?.addView(view, params)
            OverlayZOrderCoordinator.noteWindowAdded()
            isContainerAttached = true
        } catch (e: Exception) {
            isContainerAttached = false
            Log.e(TAG, "Failed to attach screensaver container", e)
        }
    }

    private fun detachContainerIfNeeded() {
        val view = containerView ?: return
        if (!view.isAttachedToWindow) {
            isContainerAttached = false
            return
        }
        try {
            windowManager?.removeView(view)
            isContainerAttached = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detach screensaver container", e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView?.let { wv ->
            val settings = wv.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            wv.setBackgroundColor(Color.BLACK)
            wv.isVerticalScrollBarEnabled = false
            wv.isHorizontalScrollBarEnabled = false
            wv.setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    ScreensaverController.onUserInteraction()
                    com.example.ava.sensor.ScreenTouchSensor.onUserTouch()
                }
                com.example.ava.sensor.ScreenGestureRecognizer.onTouchEvent(
                    view.context,
                    event,
                    view.width.coerceAtLeast(1),
                    view.height.coerceAtLeast(1),
                )
                true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isPageLoaded = false
                    loadStartTime = System.currentTimeMillis()
                    pendingRecoveryReason = null
                    mainHandler.removeCallbacks(recoveryRefreshRunnable)
                    scheduleLoadTimeoutCheck()
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoaded = true
                    cancelLoadTimeoutCheck()
                    view?.evaluateJavascript(VIDEO_COMPAT_JS, null)
                    injectDawnConfig()
                    // Replay HA caches that arrived before this page / service was ready.
                    if (isDawnWallpaperPage()) {
                        requestDawnDataRefreshFromVoiceSatellite(forceForecast = true)
                    }
                    onPageReadyForReveal()
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame != true) return
                    val status = errorResponse?.statusCode ?: return
                    // Only server/gateway failures — avoid refreshing on 404 or auth pages.
                    if (status in 500..599) {
                        Log.w(TAG, "Main frame HTTP $status for ${request.url}")
                        requestRecoveryRefresh("http_$status")
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame != true) return
                    val code = error?.errorCode ?: return
                    if (code in RECOVERABLE_MAIN_FRAME_ERROR_CODES) {
                        Log.w(TAG, "Main frame network error $code for ${request.url}")
                        requestRecoveryRefresh("net_$code")
                    }
                }
                
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    val url = view?.url ?: currentUrl
                    if (!hasTriedHttpFallback && url.startsWith("https://")) {
                        hasTriedHttpFallback = true
                        handler?.cancel()
                        val httpUrl = url.replaceFirst("https://", "http://")
                        view?.loadUrl(httpUrl)
                    } else if (
                        com.example.ava.webcompat.BrowserSslPolicy
                            .allowsCertificateError(error?.url ?: url, TAG)
                    ) {
                        // Self-signed certs on a LAN dashboard are expected; waive those only.
                        handler?.proceed()
                    } else {
                        handler?.cancel()
                    }
                }
                
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    Log.e(TAG, "Renderer crashed, didCrash=${detail?.didCrash()}, priority=${detail?.rendererPriorityAtExit()}")
                    webView?.let { wv ->
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.destroy()
                    }
                    webView = null
                    if (currentUrl.isNotBlank()) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            recreateWebView()
                        }, 500)
                    }
                    return true
                }
            }
        }
    }

    private fun recreateWebView() {
        try {
            webView = WebView(this)
            setupWebView()
            containerView?.let { container ->
                container.addView(webView, ScreensaverPixelShift.bleedLayoutParams())
                // Keep AOD cover above the recreated WebView.
                smartAodOverlay?.attachTo(container)
            }
            loadUrl(currentUrl)
            startHealthMonitor()
            syncPixelShift()
            syncSmartAodMask()
            Log.d(TAG, "WebView recreated after crash")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recreate WebView", e)
        }
    }

    private fun loadUrl(url: String) {
        if (url.isBlank()) return
        val finalUrl = when {
            url.startsWith("file://") -> url
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "https://$url"
        }
        isPageLoaded = false
        loadStartTime = System.currentTimeMillis()
        webView?.loadUrl(finalUrl)
        scheduleLoadTimeoutCheck()
    }

    private fun updateUrlInternal(url: String) {
        currentUrl = url
        hasTriedHttpFallback = false
        mainHandler.removeCallbacks(localPreventiveRefreshRunnable)
        if (containerView == null) {
            showWebView(url)
        } else {
            loadUrl(url)
            if (healthMonitorActive) {
                if (isLocalAssetUrl(url)) {
                    scheduleLocalPreventiveRefresh()
                }
            }
        }
    }

    private fun hideWebView() {
        OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
        // Center retract, then pause + detach; keep WebView alive for fast resume.
        try {
            pixelShift.pause()
            stopHealthMonitor()
            cancelPendingRevealIn()
            revealOutThen {
                try {
                    // Do not call the process-global pauseTimers() here — it would also freeze
                    // the browser WebView. onPause() alone keeps this WebView quiescent.
                    webView?.onPause()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        webView?.setRendererPriorityPolicy(
                            WebView.RENDERER_PRIORITY_BOUND,
                            false
                        )
                    }

                    detachContainerIfNeeded()
                    syncSmartAodMask()
                    Log.d(TAG, "WebView hidden, paused, and detached")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to finish hiding screensaver WebView", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide screensaver WebView", e)
        }
    }
    
    private fun destroyWebView() {
        // Tear down immediately — skip reveal so disable / process death stays snappy.
        try {
            hideEpoch += 1
            cancelPendingRevealIn()
            cancelRevealAnimator()
            pixelShift.detach()
            smartAodOverlay?.detach()
            smartAodOverlay = null
            webView?.let { wv ->
                wv.stopLoading()
                wv.onPause()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
            detachContainerIfNeeded()
            Log.d(TAG, "WebView destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy screensaver WebView", e)
        }
        stopHealthMonitor()
        containerView = null
        webView = null
        isPageLoaded = false
        isContainerAttached = false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped from recents: drop the overlay + WebView immediately so nothing
        // (media/JS) keeps running in the background.
        try {
            destroyWebView()
        } catch (e: Exception) {
            Log.e(TAG, "Error during onTaskRemoved cleanup", e)
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        settingsJob?.cancel()
        settingsJob = null
        pixelShift.detach()
        serviceScope.cancel()
        destroyWebView()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
