package com.example.ava.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.example.ava.R
import com.example.ava.homeassistant.HaManager
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.ui.AvaSystemChrome
import com.example.ava.ui.components.MdiIconMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Home Assistant sensor card: current reading plus a wave of everything Ava has seen
 * since the card was placed.
 *
 * Works for every domain, not just `sensor.*`. A numeric state draws a smooth curve; an
 * on/off style state (any domain — `binary_sensor`, `switch`, `lock`, `device_tracker`,
 * `person`, …) draws a step curve; anything else still shows the reading with no curve.
 *
 * The entity is picked per card in [AvaSensorWidgetConfigureActivity]; nothing is
 * hardcoded and nothing is shared between cards.
 */
object AvaSensorWidgets {

    private const val TAG = "AvaSensorWidgets"

    /** Bitmap band height. Mirrors `widget_ava_sensor.xml`'s wave view. */
    private const val WAVE_VIEW_DP = 72f
    private const val WAVE_BITMAP_MAX_W = 360
    private const val WAVE_BITMAP_MAX_H = 160

    private const val LABEL_DARK = 0xFFF0F4F8.toInt()
    private const val LABEL_LIGHT = 0xFF111C2A.toInt()
    private const val NAME_DARK = 0xFF94A3B8.toInt()
    private const val NAME_LIGHT = 0xFF64748B.toInt()
    private const val META_DARK = 0xFF64748B.toInt()
    private const val META_LIGHT = 0xFF94A3B8.toInt()
    private const val PILL_LIVE = 0xFF34D399.toInt()
    private const val PILL_LIVE_LIGHT = 0xFF0A8F7A.toInt()
    private const val PILL_WARM = 0xFFF59E0B.toInt()
    private const val PILL_IDLE = 0xFF94A3B8.toInt()
    private const val PILL_IDLE_LIGHT = 0xFF64748B.toInt()

    /** Attributes subscribed in `VoiceSatellite.subscribeWidgetSensors`. */
    private val WATCHED_ATTRIBUTES =
        setOf("unit_of_measurement", "device_class", "friendly_name")

    /**
     * Entities at least one card is watching. Kept in memory because
     * [onEntityState] sits on the Home Assistant state fan-out, which fires for every
     * subscribed entity in the app — it must not touch disk to decide "not mine".
     */
    @Volatile
    private var tracked: Set<String>? = null

    // ---- public API ----

    fun render(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        options: Bundle = appWidgetManager.getAppWidgetOptions(appWidgetId),
    ) {
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        val views = buildViews(
            context = context,
            config = AvaSensorWidgetStore.read(context, appWidgetId),
            widthDp = widthDp,
            heightDp = heightDp,
        )
        views.setOnClickPendingIntent(R.id.ava_sensor_root, configureIntent(context, appWidgetId))
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    fun refreshAll(context: Context) {
        val app = context.applicationContext
        val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return
        AvaSensorWidgetStore.widgetIds(app).forEach { id ->
            runCatching { render(app, manager, id) }
        }
    }

    /** Preview for the launcher's own widget picker, which renders off-host. */
    fun cardViews(context: Context, widthDp: Int, heightDp: Int): RemoteViews =
        buildViews(
            context = context,
            config = AvaSensorWidgetStore.read(context, AppWidgetManager.INVALID_APPWIDGET_ID),
            widthDp = widthDp,
            heightDp = heightDp,
        )

    fun forget(context: Context, appWidgetId: Int) {
        AvaSensorWidgetStore.delete(context, appWidgetId)
        tracked = null
        requestResubscribe(context)
    }

    /** A card's entity was (re)picked: re-derive the watch set and ask HA for it. */
    fun onEntityChanged(context: Context) {
        tracked = null
        requestResubscribe(context)
    }

    /**
     * Home Assistant pushed state for some entity. Called from the satellite's state
     * fan-out on a background coroutine, for *every* subscribed entity, so the
     * not-mine path stays allocation free and off disk.
     */
    fun onEntityState(context: Context, entityId: String, attribute: String, state: String) {
        if (entityId !in tracked(context)) return
        runCatching { apply(context.applicationContext, entityId, attribute, state) }
    }

    /**
     * Pre-populates sparklines from HA's history REST API for every tracked entity
     * whose widget has fewer samples than history provides. Runs off the main thread;
     * safe to call from a coroutine after [onEntityChanged] or subscription setup.
     */
    suspend fun backfillFromHistory(context: Context) {
        val app = context.applicationContext
        val ha = HaManager.get() ?: return
        val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return
        for (entityId in tracked(app)) {
            val history = try {
                ha.fetchEntityHistory(entityId)
            } catch (e: Exception) {
                Log.w(TAG, "History backfill failed for $entityId: ${e.message}")
                continue
            }
            if (history.isEmpty()) continue
            for (appWidgetId in AvaSensorWidgetStore.widgetIdsFor(app, entityId)) {
                val samples = history.mapNotNull { (epoch, state) ->
                    val plot = state.toFloatOrNull()
                        ?: if (state.lowercase(Locale.ROOT) in ON_STATES) 1f
                        else if (state.lowercase(Locale.ROOT) in OFF_STATES) 0f
                        else null
                    plot?.let { AvaSensorSample(epoch, it) }
                }
                val resampled = resampleHistory(samples)
                if (AvaSensorWidgetStore.backfillSamples(app, appWidgetId, resampled)) {
                    runCatching { render(app, manager, appWidgetId) }
                }
            }
        }
    }

    /**
     * Down-samples raw history into evenly-spaced buckets that match
     * [AvaSensorWidgetStore.SAMPLE_INTERVAL_MS] / [AvaSensorWidgetStore.CAPACITY].
     */
    private fun resampleHistory(raw: List<AvaSensorSample>): List<AvaSensorSample> {
        if (raw.size <= 2) return raw
        val interval = AvaSensorWidgetStore.SAMPLE_INTERVAL_MS
        val out = mutableListOf(raw.first())
        var nextBucket = raw.first().at + interval
        for (s in raw) {
            if (s.at >= nextBucket) {
                out.add(s)
                nextBucket = s.at + interval
            }
        }
        if (out.last().at != raw.last().at) out.add(raw.last())
        return out.takeLast(AvaSensorWidgetStore.CAPACITY)
    }

    // ---- state ingestion ----

    private fun apply(context: Context, entityId: String, attribute: String, state: String) {
        if (attribute.isNotBlank() && attribute !in WATCHED_ATTRIBUTES) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        for (appWidgetId in AvaSensorWidgetStore.widgetIdsFor(context, entityId)) {
            val before = AvaSensorWidgetStore.read(context, appWidgetId)
            var sampled = false
            when (attribute) {
                "unit_of_measurement" ->
                    AvaSensorWidgetStore.writeSnapshot(context, appWidgetId, unit = state)
                "device_class" ->
                    AvaSensorWidgetStore.writeSnapshot(context, appWidgetId, deviceClass = state)
                "friendly_name" ->
                    AvaSensorWidgetStore.writeSnapshot(context, appWidgetId, label = state.trim())
                else -> {
                    AvaSensorWidgetStore.writeSnapshot(context, appWidgetId, value = state)
                    readingOf(context, state, before.unit).plot?.let { plot ->
                        sampled = AvaSensorWidgetStore.appendSample(context, appWidgetId, plot)
                    }
                }
            }
            if (sampled || AvaSensorWidgetStore.read(context, appWidgetId) != before) {
                runCatching { render(context, manager, appWidgetId) }
            }
        }
    }

    private fun tracked(context: Context): Set<String> =
        tracked ?: AvaSensorWidgetStore.entityIds(context.applicationContext).also { tracked = it }

    private fun requestResubscribe(context: Context) {
        val service = VoiceSatelliteService.getInstance() ?: return
        runCatching {
            service.lifecycleScope.launch { service.resubscribeWidgetSensors() }
        }
    }

    // ---- reading interpretation ----

    /**
     * How one Home Assistant state renders.
     *
     * [plot] is what lands on the wave; null means the state cannot be charted (free
     * text like `heat_cool` or a timestamp) and the card shows the reading alone.
     */
    private data class Reading(
        val display: String,
        val unit: String,
        val plot: Float?,
        val stepped: Boolean,
        val available: Boolean,
    )

    private val ON_STATES = setOf(
        "on", "open", "opening", "home", "detected", "unlocked", "playing", "active",
        "above_horizon", "connected", "charging", "wet", "motion", "occupied", "present",
        "running", "heat", "cool", "cleaning", "armed_away", "armed_home", "unlocking",
    )
    private val OFF_STATES = setOf(
        "off", "closed", "closing", "not_home", "clear", "locked", "paused", "idle",
        "standby", "below_horizon", "disconnected", "discharging", "dry", "unoccupied",
        "not_present", "stopped", "docked", "disarmed", "locking",
    )
    private val UNAVAILABLE_STATES = setOf("unavailable", "unknown", "none", "null", "")

    private fun readingOf(context: Context, raw: String, unit: String): Reading {
        val state = raw.trim()
        val lower = state.lowercase(Locale.ROOT)
        if (lower in UNAVAILABLE_STATES) {
            return Reading(
                display = context.getString(R.string.ava_widget_sensor_unavailable_value),
                unit = "",
                plot = null,
                stepped = false,
                available = false,
            )
        }
        state.toFloatOrNull()?.let { number ->
            return Reading(
                display = formatNumber(number),
                unit = unit,
                plot = number,
                stepped = false,
                available = true,
            )
        }
        if (lower in ON_STATES || lower in OFF_STATES) {
            val on = lower in ON_STATES
            return Reading(
                display = context.getString(
                    if (on) R.string.ava_widget_sensor_state_on
                    else R.string.ava_widget_sensor_state_off,
                ),
                unit = "",
                plot = if (on) 1f else 0f,
                stepped = true,
                available = true,
            )
        }
        return Reading(
            display = state.replace('_', ' ').replaceFirstChar { it.uppercaseChar() },
            unit = "",
            plot = null,
            stepped = false,
            available = true,
        )
    }

    /** Trims noise from Home Assistant's full float precision without losing detail. */
    private fun formatNumber(value: Float): String {
        val magnitude = abs(value)
        return when {
            magnitude >= 1000f -> value.roundToInt().toString()
            magnitude >= 100f -> String.format(Locale.US, "%.0f", value)
            magnitude >= 10f -> String.format(Locale.US, "%.1f", value).trimTrailingZero()
            else -> String.format(Locale.US, "%.2f", value).trimTrailingZero()
        }
    }

    private fun String.trimTrailingZero(): String =
        if (contains('.')) trimEnd('0').trimEnd('.') else this

    // ---- appearance per device class ----

    /**
     * Icon the user picked for this entity in the Quick Entity panel, or null.
     * The panel's untouched placeholder (`mdi:home-assistant`) is ignored so the
     * device-class auto icon still wins for slots the user never customised.
     * DataStore serves reads from its in-memory cache after the first load, so the
     * blocking bridge is cheap at widget-render cadence.
     */
    private fun quickSlotIconRes(context: Context, entityId: String): Int? {
        val slots = runCatching {
            runBlocking { context.quickEntitySettingsStore.data.first().slots }
        }.getOrNull() ?: return null
        val icon = slots.firstOrNull { it.entityId == entityId }
            ?.icon
            ?.takeIf { it.isNotBlank() && it != "mdi:home-assistant" }
            ?: return null
        return MdiIconMapper.iconMap[icon]
    }

    /** Badge glyph + accent colour. Falls back to unit and domain when HA sends no class. */
    private data class Look(val icon: Int, val circle: Int, val accent: Int)

    private fun lookOf(entityId: String, deviceClass: String, unit: String): Look {
        val amber = Look(R.drawable.mdi_thermometer, R.drawable.widget_action_circle_amber, 0xFFF59E0B.toInt())
        val blue = Look(R.drawable.mdi_water_percent, R.drawable.widget_action_circle_blue, 0xFF3B82F6.toInt())
        val green = Look(R.drawable.mdi_flash, R.drawable.widget_action_circle_green, 0xFF22C55E.toInt())
        val purple = Look(R.drawable.mdi_gauge, R.drawable.widget_action_circle_purple, 0xFF8B5CF6.toInt())
        val cyan = Look(R.drawable.mdi_gauge, R.drawable.widget_action_circle_cyan, 0xFF06B6D4.toInt())

        when (deviceClass.lowercase(Locale.ROOT)) {
            "temperature" -> return amber
            "humidity", "moisture", "water", "precipitation", "precipitation_intensity" -> return blue
            "power", "energy", "current", "voltage", "apparent_power", "reactive_power",
            "power_factor", "battery",
            -> return green
            "pressure", "atmospheric_pressure", "wind_speed", "speed" -> return purple
            "illuminance" -> return amber.copy(icon = R.drawable.mdi_brightness_5)
            "carbon_dioxide", "carbon_monoxide", "pm25", "pm10", "pm1", "aqi", "nitrogen_dioxide",
            "ozone", "sulphur_dioxide", "volatile_organic_compounds",
            -> return purple.copy(icon = R.drawable.mdi_leaf)
            "motion", "occupancy", "presence" -> return cyan.copy(icon = R.drawable.mdi_motion_sensor)
            "door", "garage_door", "opening" -> return cyan.copy(icon = R.drawable.mdi_door)
            "window" -> return cyan.copy(icon = R.drawable.mdi_window_open)
            "lock" -> return Look(R.drawable.mdi_lock, R.drawable.widget_action_circle_red, 0xFFEF4444.toInt())
            "smoke", "gas", "heat", "problem", "safety" ->
                return Look(R.drawable.mdi_smoke_detector, R.drawable.widget_action_circle_red, 0xFFEF4444.toInt())
            "connectivity" -> return green.copy(icon = R.drawable.mdi_wifi)
        }

        // No device_class: unit is the next best hint.
        when (unit.trim()) {
            "°C", "°F", "K", "℃", "℉" -> return amber
            "%" -> return if (entityId.contains("humid")) blue else green
            "W", "kW", "A", "mA", "V", "mV", "kWh", "Wh", "VA" -> return green
            "hPa", "mbar", "bar", "Pa", "kPa", "psi", "inHg" -> return purple
            "lx", "lm" -> return amber.copy(icon = R.drawable.mdi_brightness_5)
            "ppm", "ppb", "µg/m³", "mg/m³" -> return purple.copy(icon = R.drawable.mdi_leaf)
        }

        return when (entityId.substringBefore('.')) {
            "lock" -> Look(R.drawable.mdi_lock, R.drawable.widget_action_circle_red, 0xFFEF4444.toInt())
            "light", "switch" -> green.copy(icon = R.drawable.mdi_power)
            "binary_sensor" -> cyan.copy(icon = R.drawable.mdi_motion_sensor)
            "device_tracker", "person" -> cyan.copy(icon = R.drawable.mdi_home)
            "weather" -> blue.copy(icon = R.drawable.mdi_weather_windy)
            else -> cyan
        }
    }

    // ---- card rendering ----

    private fun buildViews(
        context: Context,
        config: AvaSensorConfig,
        widthDp: Int,
        heightDp: Int,
    ): RemoteViews {
        val dark = AvaSystemChrome.isDarkMode(context)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val views = RemoteViews(context.packageName, R.layout.widget_ava_sensor)

        views.setInt(
            R.id.ava_sensor_root, "setBackgroundResource",
            if (dark) R.drawable.widget_tile_bg_dark else R.drawable.widget_tile_bg_light,
        )

        // Same tier thresholds as the action tiles, so a row of cards agrees on scale.
        val tiny = widthDp < 76 || heightDp < 64
        val shortWide = !tiny && heightDp < 100 && widthDp > heightDp
        val compact = !tiny && !shortWide && (widthDp < 120 || heightDp < 128)
        val snug = !tiny && !shortWide && !compact && heightDp < 180
        val large = !tiny && !shortWide && !compact && widthDp >= 220 && heightDp >= 200

        val padDp = when {
            tiny -> 8f
            shortWide -> 10f
            compact -> 12f
            snug -> 12f
            large -> 18f
            else -> 14f
        }
        val badgeDp = when {
            tiny -> 24f
            shortWide -> 28f
            compact -> 32f
            snug -> 32f
            large -> 46f
            else -> 38f
        }
        val waveDp = when {
            tiny -> 0f
            shortWide -> 20f
            compact -> 24f
            snug -> 24f
            large -> 56f
            else -> 40f
        }

        val padPx = (padDp * density).toInt()
        views.setViewPadding(
            R.id.ava_sensor_content,
            padPx, padPx, padPx, padPx + (waveDp * density).toInt(),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewLayoutWidth(R.id.ava_sensor_badge, badgeDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.ava_sensor_badge, badgeDp, TypedValue.COMPLEX_UNIT_DIP)
        }
        val badgePadPx = (badgeDp * (if (large) 0.22f else 0.26f) * density).toInt()
        views.setViewPadding(R.id.ava_sensor_badge, badgePadPx, badgePadPx, badgePadPx, badgePadPx)

        return if (config.configured) {
            fillConfigured(
                context, views, config, dark, density,
                widthDp, padDp, waveDp, tiny, shortWide, compact, snug, large,
            )
        } else {
            fillEmpty(context, views, dark, density, widthDp, padDp, tiny, shortWide, compact, snug, large)
        }
    }

    /** No entity yet: the card's whole job is to say "tap me". */
    private fun fillEmpty(
        context: Context,
        views: RemoteViews,
        dark: Boolean,
        density: Float,
        widthDp: Int,
        padDp: Float,
        tiny: Boolean,
        shortWide: Boolean,
        compact: Boolean,
        snug: Boolean,
        large: Boolean,
    ): RemoteViews {
        views.setImageViewResource(R.id.ava_sensor_badge, R.drawable.mdi_gauge)
        views.setInt(
            R.id.ava_sensor_badge, "setBackgroundResource",
            R.drawable.widget_action_circle_slate,
        )

        val name = context.getString(R.string.ava_widget_sensor_name)
        val hint = context.getString(R.string.ava_widget_sensor_tap_to_configure)
        views.setContentDescription(R.id.ava_sensor_badge, hint)
        views.setTextViewText(R.id.ava_sensor_name, name)
        views.setTextColor(R.id.ava_sensor_name, if (dark) NAME_DARK else NAME_LIGHT)
        views.setTextViewText(R.id.ava_sensor_value, hint)
        views.setTextColor(R.id.ava_sensor_value, if (dark) NAME_DARK else NAME_LIGHT)
        views.setViewVisibility(R.id.ava_sensor_unit, View.GONE)
        views.setViewVisibility(R.id.ava_sensor_dot, View.GONE)
        views.setViewVisibility(R.id.ava_sensor_meta, View.GONE)

        val usableWidth = (widthDp - padDp * 2).coerceAtLeast(24f)
        val hintSp = fitTextSp(usableWidth, hint, if (tiny) 10f else if (large) 15f else 13f)
        views.setTextViewTextSize(R.id.ava_sensor_value, TypedValue.COMPLEX_UNIT_SP, hintSp)
        views.setTextViewTextSize(
            R.id.ava_sensor_name, TypedValue.COMPLEX_UNIT_SP, if (tiny) 9f else 12f,
        )

        // Dashed placeholder where the wave will go once data arrives.
        views.setViewVisibility(R.id.ava_sensor_wave, if (tiny) View.GONE else View.VISIBLE)
        if (!tiny) {
            views.setImageViewBitmap(
                R.id.ava_sensor_wave,
                drawGhost(density, widthDp, if (shortWide || compact || snug) 20f else 32f),
            )
        }

        if (tiny || shortWide || compact) {
            views.setViewVisibility(R.id.ava_sensor_footer, View.GONE)
        } else {
            views.setViewVisibility(R.id.ava_sensor_footer, View.VISIBLE)
            views.setInt(R.id.ava_sensor_pill, "setBackgroundResource", R.drawable.widget_tile_pill_off)
            views.setImageViewResource(R.id.ava_sensor_pill_dot, R.drawable.widget_tile_dot_off)
            views.setTextViewText(
                R.id.ava_sensor_pill_text,
                context.getString(R.string.ava_widget_sensor_pick_entity),
            )
            views.setTextColor(
                R.id.ava_sensor_pill_text, if (dark) PILL_IDLE else PILL_IDLE_LIGHT,
            )
            views.setTextViewTextSize(R.id.ava_sensor_pill_text, TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        return views
    }

    private fun fillConfigured(
        context: Context,
        views: RemoteViews,
        config: AvaSensorConfig,
        dark: Boolean,
        density: Float,
        widthDp: Int,
        padDp: Float,
        waveDp: Float,
        tiny: Boolean,
        shortWide: Boolean,
        compact: Boolean,
        snug: Boolean,
        large: Boolean,
    ): RemoteViews {
        val reading = readingOf(context, config.value, config.unit)
        val look = lookOf(config.entityId, config.deviceClass, config.unit)
        val name = config.label.ifEmpty { prettyName(config.entityId) }

        // Quick Entity panel icon wins when the entity is configured there;
        // otherwise fall back to the device-class auto icon.
        val badgeIcon = quickSlotIconRes(context, config.entityId) ?: look.icon
        views.setImageViewResource(R.id.ava_sensor_badge, badgeIcon)
        views.setInt(R.id.ava_sensor_badge, "setBackgroundResource", look.circle)
        views.setContentDescription(R.id.ava_sensor_badge, name)

        views.setTextViewText(R.id.ava_sensor_name, name)
        views.setTextColor(R.id.ava_sensor_name, if (dark) NAME_DARK else NAME_LIGHT)
        views.setTextViewText(R.id.ava_sensor_value, reading.display)
        views.setTextColor(R.id.ava_sensor_value, if (dark) LABEL_DARK else LABEL_LIGHT)
        views.setTextViewText(R.id.ava_sensor_unit, reading.unit)
        views.setTextColor(R.id.ava_sensor_unit, if (dark) NAME_DARK else NAME_LIGHT)
        views.setViewVisibility(
            R.id.ava_sensor_unit,
            if (reading.unit.isEmpty()) View.GONE else View.VISIBLE,
        )

        // ---- text sizes ----
        val usableWidth = (widthDp - padDp * 2).coerceAtLeast(24f)
        val valueMaxSp = when {
            tiny -> 16f
            shortWide -> 20f
            compact -> 22f
            snug -> 22f
            large -> 34f
            else -> 27f
        }
        val valueRoom = usableWidth - if (reading.unit.isEmpty()) 0f else 18f
        views.setTextViewTextSize(
            R.id.ava_sensor_value,
            TypedValue.COMPLEX_UNIT_SP,
            fitTextSp(valueRoom, reading.display, valueMaxSp),
        )
        views.setTextViewTextSize(
            R.id.ava_sensor_name,
            TypedValue.COMPLEX_UNIT_SP,
            fitTextSp(usableWidth, name, if (tiny) 9f else if (large) 14f else 12f),
        )
        views.setTextViewTextSize(
            R.id.ava_sensor_unit,
            TypedValue.COMPLEX_UNIT_SP,
            if (tiny) 9f else if (large) 15f else 13f,
        )

        // ---- wave ----
        val plotted = config.samples.map { it.value }
        val showWave = !tiny && waveDp > 0f
        views.setViewVisibility(R.id.ava_sensor_wave, if (showWave) View.VISIBLE else View.GONE)
        if (showWave) {
            val bitmap = if (plotted.isEmpty()) {
                drawGhost(density, widthDp, waveDp)
            } else {
                drawWave(density, widthDp, waveDp, plotted, look.accent, reading.stepped)
            }
            views.setImageViewBitmap(R.id.ava_sensor_wave, bitmap)
        }

        // ---- status dot + footer ----
        val dotRes = when {
            !reading.available -> R.drawable.widget_tile_dot_off
            plotted.size < 2 -> R.drawable.widget_tile_dot_armed
            else -> R.drawable.widget_tile_dot_on
        }
        views.setImageViewResource(R.id.ava_sensor_dot, dotRes)
        views.setViewVisibility(
            R.id.ava_sensor_dot,
            if (tiny || shortWide) View.GONE else View.VISIBLE,
        )

        if (tiny || shortWide || compact) {
            views.setViewVisibility(R.id.ava_sensor_footer, View.GONE)
            return views
        }

        views.setViewVisibility(R.id.ava_sensor_footer, View.VISIBLE)
        val pillBg = when {
            !reading.available -> R.drawable.widget_tile_pill_off
            plotted.size < 2 -> R.drawable.widget_tile_pill_armed
            else -> R.drawable.widget_tile_pill_on
        }
        val pillColor = when {
            !reading.available -> if (dark) PILL_IDLE else PILL_IDLE_LIGHT
            plotted.size < 2 -> PILL_WARM
            else -> if (dark) PILL_LIVE else PILL_LIVE_LIGHT
        }
        views.setInt(R.id.ava_sensor_pill, "setBackgroundResource", pillBg)
        views.setImageViewResource(R.id.ava_sensor_pill_dot, dotRes)
        views.setTextViewText(R.id.ava_sensor_pill_text, pillText(context, reading, config.samples))
        views.setTextColor(R.id.ava_sensor_pill_text, pillColor)
        views.setTextViewTextSize(
            R.id.ava_sensor_pill_text, TypedValue.COMPLEX_UNIT_SP, if (large) 12f else 10f,
        )

        val meta = metaText(context, config.samples, reading)
        views.setTextViewText(R.id.ava_sensor_meta, meta)
        views.setTextColor(R.id.ava_sensor_meta, if (dark) META_DARK else META_LIGHT)
        views.setTextViewTextSize(
            R.id.ava_sensor_meta, TypedValue.COMPLEX_UNIT_SP, if (large) 12f else 10f,
        )
        views.setViewVisibility(
            R.id.ava_sensor_meta,
            if (meta.isEmpty()) View.GONE else View.VISIBLE,
        )
        return views
    }

    /**
     * Pill carries link state and direction — never a threshold verdict, because the
     * card has no idea what "high" means for an arbitrary entity.
     */
    private fun pillText(
        context: Context,
        reading: Reading,
        samples: List<AvaSensorSample>,
    ): String {
        if (!reading.available) return context.getString(R.string.ava_widget_sensor_offline)
        if (samples.size < 2) return context.getString(R.string.ava_widget_sensor_collecting)
        val delta = samples.last().value - samples[samples.size - 2].value
        val step = when {
            abs(delta) < 0.0001f -> return context.getString(R.string.ava_widget_sensor_steady)
            delta > 0f -> "↑"
            else -> "↓"
        }
        return if (reading.stepped) step else "$step ${formatNumber(abs(delta))}"
    }

    /** Range across the collected window, e.g. `3h ↑24.3 ↓21.4`. */
    private fun metaText(
        context: Context,
        samples: List<AvaSensorSample>,
        reading: Reading,
    ): String {
        if (samples.size < 2 || reading.stepped) return ""
        val values = samples.map { it.value }
        val spanMinutes = ((samples.last().at - samples.first().at) / 60_000L).toInt()
        val span = when {
            spanMinutes < 60 -> context.getString(R.string.ava_widget_sensor_span_minutes, spanMinutes)
            else -> context.getString(R.string.ava_widget_sensor_span_hours, spanMinutes / 60)
        }
        return "$span  ↑${formatNumber(values.max())}  ↓${formatNumber(values.min())}"
    }

    private fun prettyName(entityId: String): String =
        entityId.substringAfter('.', entityId)
            .replace('_', ' ')
            .replaceFirstChar { it.uppercaseChar() }

    /**
     * Pick a font size that fits [text] in [availableWidthDp] without overflow. CJK
     * glyphs are roughly square, Latin ones about half an em wide.
     */
    private fun fitTextSp(availableWidthDp: Float, text: String, maxSp: Float): Float {
        if (text.isEmpty()) return maxSp
        val length = text.codePointCount(0, text.length).coerceAtLeast(1)
        val cjk = text.count { it.code in 0x2E80..0x9FFF || it.code in 0x3000..0x303F }
        val avgGlyphEm = (cjk * 1.0f + (length - cjk) * 0.58f) / length
        return minOf(maxSp, availableWidthDp / (length * avgGlyphEm)).coerceIn(8f, maxSp)
    }

    // ---- wave drawing ----

    /**
     * Bitmap sizing. The view is a fixed [WAVE_VIEW_DP] band scaled with `fitXY`, so the
     * curve's height is controlled by how much of the bitmap it occupies rather than by
     * the view — `RemoteViews` cannot resize a view before API 31.
     */
    private fun waveBitmap(density: Float, widthDp: Int): Bitmap {
        val w = (widthDp * density).toInt().coerceIn(48, WAVE_BITMAP_MAX_W)
        val h = (WAVE_VIEW_DP * density).toInt().coerceIn(32, WAVE_BITMAP_MAX_H)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    /** Dashed baseline for "configured but nothing recorded yet". */
    private fun drawGhost(density: Float, widthDp: Int, bandDp: Float): Bitmap {
        val bitmap = waveBitmap(density, widthDp)
        val canvas = Canvas(bitmap)
        val y = bitmap.height - bandDp / WAVE_VIEW_DP * bitmap.height * 0.5f
        val dash = bitmap.width / 46f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x5994A3B8
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * (bitmap.height / WAVE_VIEW_DP)
            pathEffect = DashPathEffect(floatArrayOf(dash, dash), 0f)
        }
        val inset = bitmap.width * 0.06f
        canvas.drawLine(inset, y, bitmap.width - inset, y, paint)
        return bitmap
    }

    /**
     * Gradient area fill under a smooth curve, bleeding to both side edges.
     *
     * On/off histories are drawn [stepped] — interpolating between two discrete states
     * would invent readings that never happened.
     */
    private fun drawWave(
        density: Float,
        widthDp: Int,
        bandDp: Float,
        values: List<Float>,
        accent: Int,
        stepped: Boolean,
    ): Bitmap {
        val bitmap = waveBitmap(density, widthDp)
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val scale = h / WAVE_VIEW_DP
        val bandTop = h - (bandDp * scale)
        val strokePx = 2f * scale
        val top = bandTop + strokePx
        val bottom = h - strokePx

        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0.0001f }

        val points = if (values.size == 1 || range == null) {
            // A flat history is still a fact worth drawing; park it mid band.
            val y = top + (bottom - top) * 0.45f
            listOf(0f to y, w to y)
        } else {
            values.mapIndexed { index, value ->
                val x = w * index / (values.size - 1).toFloat()
                val y = bottom - (value - min) / range * (bottom - top)
                x to y
            }
        }

        val line = if (stepped) steppedPath(points) else smoothPath(points)
        val area = Path(line).apply {
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }

        canvas.drawPath(
            area,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, bandTop, 0f, h,
                    withAlpha(accent, 0.45f), withAlpha(accent, 0f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        canvas.drawPath(
            line,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            },
        )
        return bitmap
    }

    /** Catmull-Rom control points expressed as cubic beziers, so the line reads as a wave. */
    private fun smoothPath(points: List<Pair<Float, Float>>): Path {
        val path = Path()
        path.moveTo(points[0].first, points[0].second)
        for (i in 0 until points.size - 1) {
            val p0 = points.getOrElse(i - 1) { points[i] }
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points.getOrElse(i + 2) { p2 }
            path.cubicTo(
                p1.first + (p2.first - p0.first) / 6f,
                p1.second + (p2.second - p0.second) / 6f,
                p2.first - (p3.first - p1.first) / 6f,
                p2.second - (p3.second - p1.second) / 6f,
                p2.first,
                p2.second,
            )
        }
        return path
    }

    private fun steppedPath(points: List<Pair<Float, Float>>): Path {
        val path = Path()
        path.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            path.lineTo(points[i].first, points[i - 1].second)
            path.lineTo(points[i].first, points[i].second)
        }
        return path
    }

    private fun withAlpha(color: Int, fraction: Float): Int = Color.argb(
        (255 * fraction).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    // ---- tap target ----

    private fun configureIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, AvaSensorWidgetConfigureActivity::class.java)
            .setAction("${context.packageName}.SENSOR_CONFIGURE.$appWidgetId")
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, appWidgetId, intent, flags)
    }
}

/** Host receiver for the sensor card. All behaviour lives in [AvaSensorWidgets]. */
class AvaSensorWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        AvaSensorWidgets.onEntityChanged(context)
        appWidgetIds.forEach { AvaSensorWidgets.render(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        AvaSensorWidgets.render(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { AvaSensorWidgets.forget(context, it) }
        super.onDeleted(context, appWidgetIds)
    }
}
