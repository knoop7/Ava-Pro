package com.example.ava.widgets

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.ava.MainActivity
import com.example.ava.R
import com.example.ava.esphome.Connected
import com.example.ava.esphome.Disconnected
import com.example.ava.esphome.ServerError
import com.example.ava.esphome.Stopped
import com.example.ava.services.MediaOverlayMemoryCache
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.DarkModeManager
import com.example.ava.ui.AvaSystemChrome
import com.example.ava.utils.AvaProcessControl

/**
 * Smart-home tile widgets. Every action uses [R.layout.widget_ava_tile] with a
 * coloured circle icon, status dot, large label, subtitle, and status pill.
 */
enum class AvaWidgetAction(
    val layout: Int,
    val backgroundLight: Int,
    val backgroundDark: Int,
    val circleDrawable: Int,
    val iconDrawable: Int,
    val provider: Class<out AppWidgetProvider>,
) {
    RESTART(
        layout = R.layout.widget_ava_tile,
        backgroundLight = R.drawable.widget_tile_bg_light,
        backgroundDark = R.drawable.widget_tile_bg_dark,
        circleDrawable = R.drawable.widget_action_circle_blue,
        iconDrawable = R.drawable.widget_action_ic_restart,
        provider = AvaRestartWidgetProvider::class.java,
    ),
    EXIT(
        layout = R.layout.widget_ava_tile,
        backgroundLight = R.drawable.widget_tile_bg_light,
        backgroundDark = R.drawable.widget_tile_bg_dark,
        circleDrawable = R.drawable.widget_action_circle_red,
        iconDrawable = R.drawable.widget_action_ic_exit,
        provider = AvaExitWidgetProvider::class.java,
    ),
    SERVICE(
        layout = R.layout.widget_ava_tile,
        backgroundLight = R.drawable.widget_tile_bg_light,
        backgroundDark = R.drawable.widget_tile_bg_dark,
        circleDrawable = R.drawable.widget_action_circle_cyan,
        iconDrawable = R.drawable.widget_action_ic_service,
        provider = AvaServiceWidgetProvider::class.java,
    ),
    DARK_MODE(
        layout = R.layout.widget_ava_tile,
        backgroundLight = R.drawable.widget_tile_bg_light,
        backgroundDark = R.drawable.widget_tile_bg_dark,
        circleDrawable = R.drawable.widget_action_circle_purple,
        iconDrawable = R.drawable.widget_action_ic_dark_mode,
        provider = AvaDarkModeWidgetProvider::class.java,
    ),
    MUSIC(
        layout = R.layout.widget_ava_tile,
        backgroundLight = R.drawable.widget_tile_bg_light,
        backgroundDark = R.drawable.widget_tile_bg_dark,
        circleDrawable = R.drawable.widget_action_circle_amber,
        iconDrawable = R.drawable.widget_action_ic_music,
        provider = AvaMusicWidgetProvider::class.java,
    ),
    NETWORK(
        layout = R.layout.widget_ava_tile,
        backgroundLight = R.drawable.widget_tile_bg_light,
        backgroundDark = R.drawable.widget_tile_bg_dark,
        circleDrawable = R.drawable.widget_action_circle_green,
        iconDrawable = R.drawable.widget_action_ic_network,
        provider = AvaNetworkWidgetProvider::class.java,
    ),
}

/**
 * Rendering and tap handling for all tile widgets.
 *
 * Restart and exit use a two-tap arm/confirm pattern. Everything else fires
 * on the first tap.
 */
object AvaActionWidgets {
    const val ACTION_TAP = "com.example.ava.widget.ACTION_TAP"
    const val ACTION_DISARM = "com.example.ava.widget.ACTION_DISARM"

    private const val PREFS = "ava_action_widgets"
    private const val SERVICE_PREFS = "ava_prefs"
    private const val KEY_USER_STOPPED = "service_user_stopped"
    private const val ARM_WINDOW_MS = 5_000L

    private const val LABEL_DARK = 0xFFF0F4F8.toInt()
    private const val LABEL_LIGHT = 0xFF1B2430.toInt()
    private const val SUB_DARK = 0x99F0F4F8.toInt()
    private const val SUB_LIGHT = 0x991B2430.toInt()

    private const val PILL_ON = 0xFF34D399.toInt()
    private const val PILL_OFF = 0xFF94A3B8.toInt()
    private const val PILL_ARMED = 0xFFF59E0B.toInt()
    private const val PILL_ERROR = 0xFFEF4444.toInt()
    private const val PILL_ON_LIGHT = 0xFF0A8F7A.toInt()
    private const val PILL_OFF_LIGHT = 0xFF64748B.toInt()
    private const val PILL_ERROR_LIGHT = 0xFFDC2626.toInt()

    // ---- public API ----

    fun render(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        action: AvaWidgetAction,
        options: Bundle = appWidgetManager.getAppWidgetOptions(appWidgetId),
    ) {
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        val views = buildViews(
            context = context,
            action = action,
            widthDp = widthDp,
            heightDp = heightDp,
            armed = isArmed(context, appWidgetId),
        )
        views.setOnClickPendingIntent(
            R.id.ava_action_widget_root,
            tapIntent(context, action, appWidgetId),
        )
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    fun cardViews(
        context: Context,
        action: AvaWidgetAction,
        widthDp: Int,
        heightDp: Int,
    ): RemoteViews = buildViews(
        context = context,
        action = action,
        widthDp = widthDp,
        heightDp = heightDp,
        armed = false,
    )

    fun onCardTap(context: Context, action: AvaWidgetAction) {
        when (action) {
            AvaWidgetAction.SERVICE -> toggleCoreService(context)
            AvaWidgetAction.DARK_MODE -> {
                val dm = DarkModeManager.getInstance(context)
                dm.setDarkMode(!dm.isDarkMode())
            }
            AvaWidgetAction.MUSIC -> dispatchPlayPause(context)
            AvaWidgetAction.NETWORK -> openWifiSettings(context)
            else -> Unit
        }
    }

    fun refreshAll(context: Context) {
        val app = context.applicationContext
        val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return
        AvaWidgetAction.values().forEach { action ->
            val ids: IntArray = runCatching {
                manager.getAppWidgetIds(ComponentName(app, action.provider))
            }.getOrNull() ?: IntArray(0)
            for (id in ids) {
                runCatching { render(app, manager, id, action) }
            }
        }
    }

    fun onTap(context: Context, appWidgetId: Int, action: AvaWidgetAction) {
        val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return

        when (action) {
            AvaWidgetAction.SERVICE -> {
                toggleCoreService(context)
                render(context, manager, appWidgetId, action)
            }
            AvaWidgetAction.DARK_MODE -> {
                val dm = DarkModeManager.getInstance(context)
                dm.setDarkMode(!dm.isDarkMode())
            }
            AvaWidgetAction.MUSIC -> {
                dispatchPlayPause(context)
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { render(context, manager, appWidgetId, action) }
                }, 800)
            }
            AvaWidgetAction.NETWORK -> {
                openWifiSettings(context)
            }
            AvaWidgetAction.RESTART, AvaWidgetAction.EXIT -> {
                handleArmedTap(context, manager, appWidgetId, action)
            }
        }
    }

    fun onDisarm(context: Context, appWidgetId: Int, action: AvaWidgetAction) {
        forget(context, appWidgetId)
        val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
        runCatching { render(context, manager, appWidgetId, action) }
    }

    fun forget(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(armedKey(appWidgetId))
            .apply()
    }

    // ---- tile rendering ----

    private fun buildViews(
        context: Context,
        action: AvaWidgetAction,
        widthDp: Int,
        heightDp: Int,
        armed: Boolean,
    ): RemoteViews {
        val dark = AvaSystemChrome.isDarkMode(context)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val views = RemoteViews(context.packageName, R.layout.widget_ava_tile)

        // ---- card background & icon badge ----
        views.setInt(
            R.id.ava_action_widget_root, "setBackgroundResource",
            if (dark) action.backgroundDark else action.backgroundLight,
        )
        views.setInt(
            R.id.ava_action_widget_icon, "setBackgroundResource",
            action.circleDrawable,
        )
        val state = resolveState(context, action, armed)
        val iconRes = if (state.iconRes != 0) state.iconRes else action.iconDrawable
        views.setImageViewResource(R.id.ava_action_widget_icon, iconRes)

        // ---- text content ----
        views.setContentDescription(R.id.ava_action_widget_icon, state.label)
        views.setTextViewText(R.id.ava_action_widget_label, state.label)
        views.setTextColor(R.id.ava_action_widget_label, if (dark) LABEL_DARK else LABEL_LIGHT)

        views.setTextViewText(R.id.ava_tile_subtitle, state.subtitle)
        views.setTextColor(R.id.ava_tile_subtitle, if (dark) SUB_DARK else SUB_LIGHT)

        views.setImageViewResource(R.id.ava_tile_dot, state.dotRes)
        views.setInt(R.id.ava_tile_pill, "setBackgroundResource", state.pillBgRes)
        views.setImageViewResource(R.id.ava_tile_pill_dot, state.dotRes)
        views.setTextViewText(R.id.ava_tile_pill_text, state.pillText)
        views.setTextColor(R.id.ava_tile_pill_text, state.pillColor(dark))

        // ---- adaptive sizing based on tile dimensions ----
        val tiny = widthDp < 72 || heightDp < 72
        val compact = !tiny && (widthDp < 110 || heightDp < 96)
        val snug = !tiny && !compact && heightDp < 130
        val large = !tiny && !compact && widthDp >= 220 && heightDp >= 180
        val shortWide = !tiny && heightDp < 90 && widthDp > heightDp

        // Root padding — shrinks for small tiles, grows for large
        val padDp = when {
            tiny -> 6f
            shortWide -> 8f
            compact -> 10f
            snug -> 10f
            large -> 18f
            else -> 14f
        }
        val padPx = (padDp * density).toInt()
        views.setViewPadding(R.id.ava_tile_content, padPx, padPx, padPx, padPx)

        // Icon size — scale down for short-wide rectangles so text has room
        val iconSizeDp = when {
            tiny -> 24f
            shortWide -> 28f
            compact -> 36f
            snug -> 36f
            large -> 48f
            else -> 40f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewLayoutWidth(
                R.id.ava_action_widget_icon, iconSizeDp, TypedValue.COMPLEX_UNIT_DIP,
            )
            views.setViewLayoutHeight(
                R.id.ava_action_widget_icon, iconSizeDp, TypedValue.COMPLEX_UNIT_DIP,
            )
        }

        // Icon internal padding — controls glyph size within the circle
        val iconPadDp = iconSizeDp * when {
            tiny -> 0.30f
            compact -> 0.26f
            snug -> 0.26f
            large -> 0.18f
            else -> 0.22f
        }
        val iconPadPx = (iconPadDp * density).toInt()
        views.setViewPadding(
            R.id.ava_action_widget_icon,
            iconPadPx, iconPadPx, iconPadPx, iconPadPx,
        )

        // Label — adaptive to both tile width and text length
        val usableWidth = (widthDp - padDp * 2).coerceAtLeast(20f)
        val labelMaxSp = when {
            tiny -> 11f
            shortWide -> 13f
            compact -> 14f
            snug -> 14f
            large -> 22f
            else -> 17f
        }
        val labelSp = fitTextSp(usableWidth, heightDp.toFloat(), state.label, labelMaxSp)
        views.setTextViewTextSize(
            R.id.ava_action_widget_label, TypedValue.COMPLEX_UNIT_SP, labelSp,
        )

        // Subtitle — scales with tile height
        val subSp = when {
            large -> 14f
            snug -> 10f
            compact -> 10f
            else -> 12f
        }
        views.setTextViewTextSize(
            R.id.ava_tile_subtitle, TypedValue.COMPLEX_UNIT_SP, subSp,
        )

        // Pill text — scales gently
        val pillSp = when {
            large -> 13f
            snug -> 10f
            compact -> 10f
            else -> 11f
        }
        views.setTextViewTextSize(
            R.id.ava_tile_pill_text, TypedValue.COMPLEX_UNIT_SP, pillSp,
        )

        // Pill max width — prevents overflow for long pill text
        val pillMaxPx = ((usableWidth - 24f) * density).toInt().coerceAtLeast(1)
        views.setInt(R.id.ava_tile_pill_text, "setMaxWidth", pillMaxPx)

        // ---- visibility per tier ----
        when {
            tiny -> {
                views.setViewVisibility(R.id.ava_tile_dot, View.GONE)
                views.setViewVisibility(R.id.ava_tile_subtitle, View.GONE)
                views.setViewVisibility(R.id.ava_tile_pill, View.GONE)
                views.setViewVisibility(R.id.ava_tile_body, View.VISIBLE)
            }
            compact -> {
                views.setViewVisibility(R.id.ava_tile_dot, View.GONE)
                views.setViewVisibility(R.id.ava_tile_subtitle, View.GONE)
                views.setViewVisibility(R.id.ava_tile_pill, View.GONE)
                views.setViewVisibility(R.id.ava_tile_body, View.VISIBLE)
            }
            snug -> {
                views.setViewVisibility(R.id.ava_tile_dot, View.VISIBLE)
                views.setViewVisibility(R.id.ava_tile_pill, View.VISIBLE)
                views.setViewVisibility(R.id.ava_tile_subtitle, View.GONE)
                views.setViewVisibility(R.id.ava_tile_body, View.VISIBLE)
            }
            else -> {
                views.setViewVisibility(R.id.ava_tile_dot, View.VISIBLE)
                views.setViewVisibility(R.id.ava_tile_pill, View.VISIBLE)
                views.setViewVisibility(R.id.ava_tile_body, View.VISIBLE)
                views.setViewVisibility(
                    R.id.ava_tile_subtitle,
                    if (state.subtitle.isEmpty()) View.GONE else View.VISIBLE,
                )
            }
        }

        return views
    }

    /**
     * Pick a font size that fits the label within the available width without
     * overflow. CJK characters get a wider estimate per glyph than Latin.
     */
    private fun fitTextSp(
        availableWidthDp: Float,
        availableHeightDp: Float,
        text: String,
        maxSp: Float,
    ): Float {
        val length = text.codePointCount(0, text.length).coerceAtLeast(1)
        val cjk = text.count { it.code in 0x2E80..0x9FFF || it.code in 0x3000..0x303F }
        val avgGlyphEm = if (length > 0) {
            (cjk * 1.0f + (length - cjk) * 0.56f) / length
        } else 0.56f
        val widthFit = availableWidthDp / (length * avgGlyphEm)
        val heightFit = availableHeightDp * 0.13f
        return minOf(maxSp, widthFit, heightFit).coerceIn(8f, maxSp)
    }

    // ---- state resolution ----

    private data class TileState(
        val label: String,
        val subtitle: String,
        val pillText: String,
        val dotRes: Int,
        val pillBgRes: Int,
        private val active: Boolean,
        private val warn: Boolean = false,
        private val armed: Boolean = false,
        private val error: Boolean = false,
        val iconRes: Int = 0,
    ) {
        fun pillColor(dark: Boolean): Int = when {
            armed -> PILL_ARMED
            error -> if (dark) PILL_ERROR else PILL_ERROR_LIGHT
            warn -> PILL_ARMED
            active -> if (dark) PILL_ON else PILL_ON_LIGHT
            else -> if (dark) PILL_OFF else PILL_OFF_LIGHT
        }
    }

    private fun resolveState(
        context: Context,
        action: AvaWidgetAction,
        armed: Boolean,
    ): TileState {
        if (armed) return TileState(
            label = context.getString(R.string.ava_widget_tap_again),
            subtitle = "",
            pillText = context.getString(R.string.ava_tile_pill_confirm),
            dotRes = R.drawable.widget_tile_dot_armed,
            pillBgRes = R.drawable.widget_tile_pill_armed,
            active = false,
            armed = true,
        )
        return when (action) {
            AvaWidgetAction.SERVICE -> serviceState(context)
            AvaWidgetAction.RESTART -> restartState(context)
            AvaWidgetAction.EXIT -> exitState(context)
            AvaWidgetAction.DARK_MODE -> darkModeState(context)
            AvaWidgetAction.MUSIC -> musicState(context)
            AvaWidgetAction.NETWORK -> networkState(context)
        }
    }

    private fun serviceState(context: Context): TileState {
        val started = VoiceSatelliteService.isSatelliteStarted()
        val svc = VoiceSatelliteService.getInstance()
        val state = if (started) svc?.getState() else null
        val label = context.getString(
            if (started) R.string.label_stop_service else R.string.label_start_service,
        )
        val subtitle = context.getString(R.string.ava_tile_sub_service)
        return when (state) {
            is Connected -> TileState(
                label = label, subtitle = subtitle,
                pillText = context.getString(R.string.ava_tile_pill_running),
                dotRes = R.drawable.widget_tile_dot_on,
                pillBgRes = R.drawable.widget_tile_pill_on,
                active = true,
            )
            is Disconnected -> TileState(
                label = label, subtitle = subtitle,
                pillText = context.getString(R.string.ava_tile_pill_disconnected),
                dotRes = R.drawable.widget_tile_dot_armed,
                pillBgRes = R.drawable.widget_tile_pill_armed,
                active = false, warn = true,
            )
            is ServerError -> TileState(
                label = label, subtitle = subtitle,
                pillText = context.getString(R.string.ava_tile_pill_error),
                dotRes = R.drawable.widget_tile_dot_error,
                pillBgRes = R.drawable.widget_tile_pill_error,
                active = false, error = true,
            )
            else -> if (started) {
                TileState(
                    label = label, subtitle = subtitle,
                    pillText = context.getString(R.string.ava_tile_pill_running),
                    dotRes = R.drawable.widget_tile_dot_on,
                    pillBgRes = R.drawable.widget_tile_pill_on,
                    active = true,
                )
            } else {
                TileState(
                    label = label, subtitle = subtitle,
                    pillText = context.getString(R.string.ava_tile_pill_stopped),
                    dotRes = R.drawable.widget_tile_dot_off,
                    pillBgRes = R.drawable.widget_tile_pill_off,
                    active = false,
                )
            }
        }
    }

    private fun restartState(context: Context) = TileState(
        label = context.getString(R.string.settings_device_control_restart),
        subtitle = context.getString(R.string.ava_tile_sub_control),
        pillText = context.getString(R.string.ava_tile_pill_act),
        dotRes = R.drawable.widget_tile_dot_off,
        pillBgRes = R.drawable.widget_tile_pill_off,
        active = false,
    )

    private fun exitState(context: Context) = TileState(
        label = context.getString(R.string.settings_device_control_kill),
        subtitle = context.getString(R.string.ava_tile_sub_control),
        pillText = context.getString(R.string.ava_tile_pill_act),
        dotRes = R.drawable.widget_tile_dot_off,
        pillBgRes = R.drawable.widget_tile_pill_off,
        active = false,
    )

    private fun darkModeState(context: Context): TileState {
        val on = DarkModeManager.getInstance(context).isDarkMode()
        return TileState(
            label = context.getString(if (on) R.string.ava_tile_dark_mode else R.string.ava_tile_light_mode),
            subtitle = context.getString(R.string.ava_tile_sub_display),
            pillText = context.getString(if (on) R.string.ava_tile_pill_on else R.string.ava_tile_pill_off),
            dotRes = if (on) R.drawable.widget_tile_dot_on else R.drawable.widget_tile_dot_off,
            pillBgRes = if (on) R.drawable.widget_tile_pill_on else R.drawable.widget_tile_pill_off,
            active = on,
            iconRes = if (on) R.drawable.widget_action_ic_dark_mode else R.drawable.widget_action_ic_light_mode,
        )
    }

    private fun musicState(context: Context): TileState {
        val snap = MediaOverlayMemoryCache.get()
        val playing = snap.isPlaying
        val hasMedia = snap.songTitle.isNotEmpty() && snap.songTitle !=
            context.getString(R.string.media_overlay_waiting_for_media)
        val subtitle = when {
            hasMedia -> buildMusicSubtitle(snap)
            else -> context.getString(R.string.ava_tile_sub_music)
        }
        val pillText = context.getString(
            when {
                playing -> R.string.ava_tile_pill_playing
                hasMedia -> R.string.ava_tile_pill_paused
                else -> R.string.ava_tile_pill_stopped
            },
        )
        return TileState(
            label = if (hasMedia) snap.songTitle else context.getString(R.string.ava_tile_no_media),
            subtitle = subtitle,
            pillText = pillText,
            dotRes = if (playing) R.drawable.widget_tile_dot_on else R.drawable.widget_tile_dot_off,
            pillBgRes = if (playing) R.drawable.widget_tile_pill_on else R.drawable.widget_tile_pill_off,
            active = playing,
        )
    }

    private fun buildMusicSubtitle(snap: MediaOverlayMemoryCache.Snapshot): String {
        val parts = mutableListOf<String>()
        if (snap.artistName.isNotEmpty()) parts.add(snap.artistName)
        else if (snap.albumName.isNotEmpty()) parts.add(snap.albumName)
        if (snap.totalTimeMs > 0L) {
            val cur = fmtMmSs(snap.currentTimeMs)
            val tot = fmtMmSs(snap.totalTimeMs)
            parts.add("$cur / $tot")
        }
        return parts.joinToString(" · ").ifEmpty { "" }
    }

    private fun fmtMmSs(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    private fun networkState(context: Context): TileState {
        val wifi = wifiSnapshot(context)
        return when {
            wifi.level == 2 -> TileState(
                label = wifi.ssid,
                subtitle = context.getString(R.string.ava_tile_sub_network),
                pillText = context.getString(R.string.ava_tile_pill_strong),
                dotRes = R.drawable.widget_tile_dot_on,
                pillBgRes = R.drawable.widget_tile_pill_on,
                active = true,
            )
            wifi.level == 1 -> TileState(
                label = wifi.ssid,
                subtitle = context.getString(R.string.ava_tile_sub_network),
                pillText = context.getString(R.string.ava_tile_pill_weak),
                dotRes = R.drawable.widget_tile_dot_armed,
                pillBgRes = R.drawable.widget_tile_pill_armed,
                active = false,
                warn = true,
            )
            else -> TileState(
                label = context.getString(R.string.ava_tile_no_network),
                subtitle = context.getString(R.string.ava_tile_sub_network),
                pillText = context.getString(R.string.ava_tile_pill_disconnected),
                dotRes = R.drawable.widget_tile_dot_off,
                pillBgRes = R.drawable.widget_tile_pill_off,
                active = false,
            )
        }
    }

    // ---- WiFi helpers ----

    private data class WifiSnapshot(val ssid: String, val level: Int)

    private fun wifiSnapshot(context: Context): WifiSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val hasWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm?.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            @Suppress("DEPRECATION")
            cm?.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }

        if (!hasWifi) return WifiSnapshot("", 0)

        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val info = wm?.connectionInfo
        val rssi = info?.rssi ?: -100
        val raw = info?.ssid?.removeSurrounding("\"") ?: ""
        val ssid = if (raw == "<unknown ssid>" || raw.isBlank()) "WiFi" else raw

        val level = when {
            rssi >= -55 -> 2
            rssi >= -75 -> 1
            else -> 1
        }
        return WifiSnapshot(ssid, level)
    }

    // ---- tap actions ----

    private fun handleArmedTap(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        action: AvaWidgetAction,
    ) {
        if (!isArmed(context, appWidgetId)) {
            setArmedUntil(context, appWidgetId, SystemClock.elapsedRealtime() + ARM_WINDOW_MS)
            render(context, manager, appWidgetId, action)
            scheduleDisarm(context, appWidgetId, action)
            return
        }
        forget(context, appWidgetId)
        render(context, manager, appWidgetId, action)
        when (action) {
            AvaWidgetAction.RESTART -> AvaProcessControl.restartAva(context)
            AvaWidgetAction.EXIT -> AvaProcessControl.exitAva(context)
            else -> Unit
        }
    }

    private fun toggleCoreService(context: Context) {
        val app = context.applicationContext
        if (VoiceSatelliteService.isSatelliteStarted()) {
            VoiceSatelliteService.getInstance()?.stopVoiceSatellite()
            return
        }
        if (!hasMicPermission(app)) {
            openAva(app)
            return
        }
        app.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USER_STOPPED, false)
            .apply()
        runCatching {
            ContextCompat.startForegroundService(
                app, Intent(app, VoiceSatelliteService::class.java),
            )
        }
    }

    private fun dispatchPlayPause(context: Context) {
        val service = VoiceSatelliteService.getInstance() ?: return
        val snap = MediaOverlayMemoryCache.get()
        if (snap.isSendspinSource) {
            val cmd = if (snap.isPlaying) "pause" else "play"
            service.sendspinManager?.sendMediaCommand(cmd)
        } else {
            service.lifecycleScope.launch {
                service._voiceSatellite.value?.player?.haMediaPlayPause()
            }
        }
    }

    private fun openWifiSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAva(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }
    }

    // ---- arming ----

    private fun scheduleDisarm(context: Context, appWidgetId: Int, action: AvaWidgetAction) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, action.provider)
            .setAction(ACTION_DISARM)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(action, appWidgetId, disarm = true),
            intent,
            pendingIntentFlags(),
        )
        runCatching {
            alarm.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + ARM_WINDOW_MS,
                pending,
            )
        }
    }

    private fun tapIntent(
        context: Context,
        action: AvaWidgetAction,
        appWidgetId: Int,
    ): PendingIntent {
        val intent = Intent(context, action.provider)
            .setAction(ACTION_TAP)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context,
            requestCode(action, appWidgetId, disarm = false),
            intent,
            pendingIntentFlags(),
        )
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun requestCode(action: AvaWidgetAction, appWidgetId: Int, disarm: Boolean): Int =
        appWidgetId * 16 + action.ordinal + if (disarm) 8 else 0

    private fun isArmed(context: Context, appWidgetId: Int): Boolean {
        val until = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(armedKey(appWidgetId), 0L)
        return until > SystemClock.elapsedRealtime()
    }

    private fun setArmedUntil(context: Context, appWidgetId: Int, until: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(armedKey(appWidgetId), until)
            .apply()
    }

    private fun armedKey(appWidgetId: Int) = "armed_until_$appWidgetId"
}
