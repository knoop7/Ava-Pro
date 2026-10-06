package com.example.ava.localllm.remote

import android.content.Context
import com.example.ava.R
import com.example.ava.mods.ModCameraStreamBridge
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.ava.settings.HomeLockSession
import com.example.ava.settings.HomeLockSettingsStore
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.SettingsStoreImpl
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.homeLockSettingsStore
import com.example.ava.settings.microphoneSettingsStore
import com.example.ava.settings.normalizeScreensaverTimeoutSeconds
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.ui.Screen
import com.example.ava.utils.TouchSoundHelper
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Live on/off (and a few numbers) for THIS Ava settings page — DataStore
 * switches that are not HA entities. The receipt carries state + writable.
 * Writes inject through the same stores the settings UI uses. No screenshot.
 * Do not dump this table into the prompt.
 */
internal object AvaSettingsPrefs {

    enum class Kind { Switch, Number, Text }

    data class Pref(
        val id: String,
        val route: String,
        val titleRes: Int,
        val fallback: String,
        val keys: Array<out String>,
        val kind: Kind = Kind.Switch,
    )

    val ALL: List<Pref> = listOf(
        pref(
            "enable_idle_screensaver", Screen.SETTINGS_SCREENSAVER,
            R.string.settings_screensaver_enable, "Idle screensaver",
            "动态屏保", "动态屏", "动态频表", "闲置屏保", "闲置屏",
        ),
        pref(
            "screensaver_ha_display", Screen.SETTINGS_SCREENSAVER,
            R.string.settings_screensaver_ha_display, "Screensaver HA display",
            "屏保 ha",
        ),
        pref(
            "screensaver_timeout", Screen.SETTINGS_SCREENSAVER,
            R.string.settings_screensaver_timeout, "Screensaver timeout",
            "屏保超时", "闲置时间",
            kind = Kind.Number,
        ),
        pref(
            "screensaver_timeout_visible", Screen.SETTINGS_SCREENSAVER,
            R.string.settings_screensaver_timeout_visible, "Screensaver timeout in HA",
            "屏保超时实体",
        ),
        pref(
            "screensaver_timeout_zero", Screen.SETTINGS_SCREENSAVER,
            R.string.settings_screensaver_timeout_zero_switch, "Allow timeout 0",
            "允许0", "允许零",
        ),
        pref(
            "dawn_magazine", Screen.SETTINGS_SCREENSAVER_CONTENT,
            R.string.settings_dawn_wallpaper, "Dawn magazine",
            "画报", "黎明", "dawn",
        ),
        pref(
            "screensaver_url_visible", Screen.SETTINGS_SCREENSAVER_CONTENT,
            R.string.settings_screensaver_url_visible, "Screensaver URL in HA",
            "屏保网址实体",
        ),
        pref(
            "screensaver_dark_off", Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            R.string.settings_screensaver_dark_off, "Dark off",
            "黑暗关屏",
        ),
        pref(
            "screensaver_pixel_shift", Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            R.string.settings_screensaver_pixel_shift, "Pixel shift",
            "像素漂移",
        ),
        pref(
            "screensaver_smart_aod", Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            R.string.settings_screensaver_smart_aod, "Screensaver AOD",
            "屏保 aod",
        ),
        pref(
            "screensaver_cpu_throttle", Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            R.string.settings_screensaver_smart_cpu_throttle, "Screensaver CPU throttle",
            "屏保降频",
        ),
        pref(
            "screensaver_person_wake", Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            R.string.settings_screensaver_person_wake, "Person wake",
            "有人检测", "人来唤醒",
        ),
        pref(
            "screensaver_keep_on_overlays", Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            R.string.settings_screensaver_keep_on_overlays, "Smart screensaver exit",
            "屏保智能退出", "智能退出",
        ),
        pref(
            "screensaver_background_pause", Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            R.string.settings_screensaver_background_pause, "Background pause",
            "后台暂停",
        ),
        pref(
            "screensaver_motion_on", Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            R.string.settings_screensaver_motion_on, "Proximity wake",
            "接近唤醒",
        ),
        pref(
            "screensaver_show_after_screen_on", Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            R.string.settings_screensaver_show_after_screen_on, "Show after screen on",
            "开屏后显示", "开屏屏保",
        ),
        pref(
            "screensaver_ha_two_way", Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            R.string.settings_screensaver_ha_switch_two_way, "Screensaver two-way switch",
            "屏保双向",
        ),
        pref(
            "noise_suppressor", Screen.SETTINGS_VOICE_NOISE_SUPPRESSION,
            R.string.label_noise_suppressor, "Noise suppressor",
            "降噪", "硬件降噪",
        ),
        pref(
            "software_ns", Screen.SETTINGS_VOICE_NOISE_SUPPRESSION,
            R.string.label_software_ns, "Software noise suppression",
            "软件降噪",
        ),
        pref(
            "echo_cancellation", Screen.SETTINGS_VOICE_ECHO_CANCELLATION,
            R.string.settings_voice_echo_cancellation_entry_title, "Echo cancellation",
            "回声消除", "aec",
        ),
        pref(
            "voice_print", Screen.SETTINGS_VOICE_PRINT,
            R.string.settings_voice_print_enabled, "Voiceprint",
            "声纹",
        ),
        pref(
            "minimal_launcher", Screen.SETTINGS_SERVICE_MINIMAL_LAUNCHER,
            R.string.settings_minimal_launcher, "Minimal launcher",
            "极简桌面",
        ),
        pref(
            "auto_restart", Screen.SETTINGS_SERVICE_AUTO_RESTART,
            R.string.settings_auto_restart, "Auto restart",
            "自动重启", "保活",
        ),
        pref(
            "touch_sound", Screen.SETTINGS_SERVICE_TOUCH_SOUND,
            R.string.settings_touch_sound, "Touch sound",
            "按键音",
        ),
        pref(
            "sidebar_enable", Screen.SETTINGS_SIDEBAR,
            R.string.settings_sidebar_enable, "Sidebar",
            "侧栏",
        ),
        pref(
            "home_lock", Screen.SETTINGS_HOME_LOCK,
            R.string.settings_home_pin_lock, "Home lock",
            "锁屏密码", "home lock",
        ),
        pref(
            "camera_enable", Screen.SETTINGS_CAMERA,
            R.string.settings_camera_enabled, "Remote camera",
            "摄像头", "远程摄像头",
        ),
        pref(
            "occupancy", Screen.SETTINGS_OCCUPANCY,
            R.string.settings_occupancy, "Occupancy",
            "占用", "在席",
        ),
        pref(
            "environment_sensor", Screen.SETTINGS_ENVIRONMENT,
            R.string.settings_environment_sensor, "Environment sensors",
            "环境传感",
        ),
        pref(
            "proximity_sensor", Screen.SETTINGS_SERVICE_PROXIMITY,
            R.string.settings_proximity_sensor, "Proximity sensor",
            "距离感应", "接近传感",
        ),
    )

    fun forRoute(route: String): List<Pref> = ALL.filter { it.route == route }

    fun resolve(spoken: String): Pref? {
        val t = spoken.trim()
        if (t.isEmpty()) return null
        ALL.firstOrNull { it.id.equals(t, ignoreCase = true) }?.let { return it }
        val q = t.lowercase(Locale.ROOT)
        val hits = ArrayList<Pair<Pref, Int>>()
        for (pref in ALL) {
            var best = 0
            for (key in pref.keys) {
                if (key.length >= 2 && q.contains(key.lowercase(Locale.ROOT))) {
                    best = maxOf(best, key.length)
                }
            }
            if (best > 0) hits.add(pref to best)
        }
        return hits.maxWithOrNull(compareBy<Pair<Pref, Int>> { it.second }.thenBy { it.first.id })?.first
    }

    fun row(app: Context, pref: Pref, fresh: Boolean = false): JSONObject {
        val name = runCatching { app.getString(pref.titleRes) }.getOrNull()?.trim().orEmpty()
            .ifBlank { pref.fallback }
        val snap = read(app, pref, fresh)
        return AvaSettingsLive.pointRow(
            id = pref.id,
            name = name,
            kind = when (pref.kind) {
                Kind.Switch -> "switch"
                Kind.Number -> "number"
                Kind.Text -> "text"
            },
            state = snap,
            options = null,
            writable = true,
        )
    }

    fun attach(app: Context, route: String, points: JSONArray) {
        val seen = HashSet<String>()
        for (i in 0 until points.length()) {
            points.optJSONObject(i)?.optString("id")?.trim()?.takeIf { it.isNotEmpty() }?.let { seen.add(it) }
        }
        for (pref in forRoute(route)) {
            val live = row(app, pref)
            val idx = (0 until points.length()).firstOrNull { i ->
                points.optJSONObject(i)?.optString("id") == pref.id
            }
            if (idx != null) {
                points.put(idx, live)
            } else {
                points.put(live)
            }
            seen.add(pref.id)
        }
        for (gate in AvaSettingsGates.ALL) {
            if (gate.route != route || gate.id in seen) continue
            points.put(
                AvaSettingsLive.pointRow(
                    id = gate.id,
                    name = gate.name,
                    kind = "switch",
                    state = if (AvaSettingsGates.isOn(app, gate)) "on" else "off",
                    options = null,
                    writable = true,
                ),
            )
        }
    }

    suspend fun trySet(
        app: Context,
        spoken: String,
        on: Boolean?,
        value: String?,
    ): AvaToolCallback.Result? {
        val pref = resolve(spoken) ?: return null
        if (pref.kind == Kind.Switch) {
            if (on == null) {
                return AvaToolCallback.ok(row(app, pref).put("action", "settings"), status = "observed")
            }
            val err = writeSwitch(app, pref, on)
            if (err != null) return AvaToolCallback.fail("tool_error", err)
        } else {
            val raw = value?.trim()
                ?: return AvaToolCallback.fail("invalid_request", "${pref.fallback} needs value=")
            val err = writeValue(app, pref, raw)
            if (err != null) return AvaToolCallback.fail("invalid_request", err)
        }
        if (needsRestart(pref.id)) {
            VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
        }
        val body = row(app, pref, fresh = true)
            .put("action", "set")
            .put("applied", true)
            .put("hint", "Look at this receipt for the live on/off. No screenshot.")
        return AvaToolCallback.ok(body, status = "accepted")
    }

    private fun read(app: Context, pref: Pref, fresh: Boolean = false): String? = runCatching {
        when (pref.id) {
            "enable_idle_screensaver" -> onOff(saver(app, fresh).enabled)
            "screensaver_ha_display" -> onOff(saver(app, fresh).enableHaDisplay)
            "screensaver_timeout" -> saver(app, fresh).timeoutSeconds.toString()
            "screensaver_timeout_visible" -> onOff(saver(app, fresh).screensaverTimeoutVisible)
            "screensaver_timeout_zero" -> onOff(saver(app, fresh).timeoutSeconds == 0)
            "dawn_magazine" -> onOff(saver(app, fresh).dawnWallpaperEnabled)
            "screensaver_url_visible" -> onOff(saver(app, fresh).screensaverUrlVisible)
            "screensaver_dark_off" -> onOff(saver(app, fresh).darkOffEnabled)
            "screensaver_pixel_shift" -> onOff(saver(app, fresh).pixelShiftEnabled)
            "screensaver_smart_aod" -> onOff(saver(app, fresh).smartAodEnabled)
            "screensaver_cpu_throttle" -> onOff(saver(app, fresh).smartCpuThrottleEnabled)
            "screensaver_person_wake" -> onOff(saver(app, fresh).personWakeEnabled)
            "screensaver_keep_on_overlays" -> onOff(saver(app, fresh).keepOnOverlays)
            "screensaver_background_pause" -> onOff(saver(app, fresh).backgroundPauseEnabled)
            "screensaver_motion_on" -> onOff(saver(app, fresh).motionOnEnabled)
            "screensaver_show_after_screen_on" -> onOff(saver(app, fresh).showAfterScreenOn)
            "screensaver_ha_two_way" -> onOff(saver(app, fresh).haSwitchTwoWayEnabled)
            "noise_suppressor" -> onOff(mic(app, fresh).noiseSuppressorEnabled)
            "software_ns" -> onOff(mic(app, fresh).softwareNsEnabled)
            "echo_cancellation" -> onOff(mic(app, fresh).softwareAecEnabled)
            "voice_print" -> onOff(mic(app, fresh).voicePrintEnabled)
            "minimal_launcher" -> onOff(player(app, fresh).enableMinimalLauncher)
            "auto_restart" -> onOff(player(app, fresh).enableAutoRestart)
            "touch_sound" -> onOff(TouchSoundHelper.isEnabled(app))
            "sidebar_enable" -> onOff(sidebar(app, fresh).enableSidebar)
            "home_lock" -> onOff(lock(app, fresh).enabled)
            "camera_enable" -> onOff(experimental(app, fresh).cameraEnabled)
            "occupancy" -> onOff(experimental(app, fresh).occupancyEnabled)
            "environment_sensor" -> onOff(experimental(app, fresh).environmentSensorEnabled)
            "proximity_sensor" -> onOff(experimental(app, fresh).proximitySensorEnabled)
            else -> null
        }
    }.getOrNull()

    private suspend fun writeSwitch(app: Context, pref: Pref, on: Boolean): String? {
        val ctx = app.applicationContext
        return runCatching {
            when (pref.id) {
                "enable_idle_screensaver" -> ScreensaverSettingsStore(ctx.screensaverSettingsStore).enabled.set(on)
                "screensaver_ha_display" -> ScreensaverSettingsStore(ctx.screensaverSettingsStore).enableHaDisplay.set(on)
                "screensaver_timeout_visible" ->
                    ScreensaverSettingsStore(ctx.screensaverSettingsStore).screensaverTimeoutVisible.set(on)
                "screensaver_timeout_zero" ->
                    ScreensaverSettingsStore(ctx.screensaverSettingsStore).timeoutSeconds.set(if (on) 0 else 300)
                "dawn_magazine" -> ScreensaverSettingsStore(ctx.screensaverSettingsStore).dawnWallpaperEnabled.set(on)
                "screensaver_url_visible" ->
                    ScreensaverSettingsStore(ctx.screensaverSettingsStore).screensaverUrlVisible.set(on)
                "screensaver_dark_off" -> ScreensaverSettingsStore(ctx.screensaverSettingsStore).darkOffEnabled.set(on)
                "screensaver_pixel_shift" ->
                    ScreensaverSettingsStore(ctx.screensaverSettingsStore).pixelShiftEnabled.set(on)
                "screensaver_smart_aod" -> ScreensaverSettingsStore(ctx.screensaverSettingsStore).smartAodEnabled.set(on)
                "screensaver_cpu_throttle" ->
                    ScreensaverSettingsStore(ctx.screensaverSettingsStore).smartCpuThrottleEnabled.set(on)
                "screensaver_person_wake" -> {
                    if (on && ModCameraStreamBridge.isActive(ctx)) {
                        return@runCatching "camera-stream mod owns the camera — person wake stays off"
                    }
                    ScreensaverSettingsStore(ctx.screensaverSettingsStore).personWakeEnabled.set(on)
                }
                "screensaver_keep_on_overlays" ->
                    ScreensaverSettingsStore(ctx.screensaverSettingsStore).keepOnOverlays.set(on)
                "screensaver_background_pause" ->
                    ScreensaverSettingsStore(ctx.screensaverSettingsStore).backgroundPauseEnabled.set(on)
                "screensaver_motion_on" ->
                    ScreensaverSettingsStore(ctx.screensaverSettingsStore).motionOnEnabled.set(on)
                "screensaver_show_after_screen_on" ->
                    ScreensaverSettingsStore(ctx.screensaverSettingsStore).showAfterScreenOn.set(on)
                "screensaver_ha_two_way" ->
                    ScreensaverSettingsStore(ctx.screensaverSettingsStore).haSwitchTwoWayEnabled.set(on)
                "noise_suppressor" -> MicrophoneSettingsStore(ctx.microphoneSettingsStore).noiseSuppressorEnabled.set(on)
                "software_ns" -> MicrophoneSettingsStore(ctx.microphoneSettingsStore).softwareNsEnabled.set(on)
                "echo_cancellation" -> MicrophoneSettingsStore(ctx.microphoneSettingsStore).softwareAecEnabled.set(on)
                "voice_print" -> MicrophoneSettingsStore(ctx.microphoneSettingsStore).voicePrintEnabled.set(on)
                "minimal_launcher" -> PlayerSettingsStore(ctx.playerSettingsStore).enableMinimalLauncher.set(on)
                "auto_restart" -> PlayerSettingsStore(ctx.playerSettingsStore).enableAutoRestart.set(on)
                "touch_sound" -> TouchSoundHelper.setEnabled(ctx, on)
                "sidebar_enable" -> SidebarSettingsStore(ctx.sidebarSettingsStore).enableSidebar.set(on)
                "home_lock" -> {
                    HomeLockSettingsStore(ctx.homeLockSettingsStore).setEnabled(on)
                    HomeLockSession.unlock()
                }
                "camera_enable" -> ExperimentalSettingsStore(ctx).setCameraEnabled(on)
                "occupancy" -> ExperimentalSettingsStore(ctx).setOccupancyEnabled(on)
                "environment_sensor" -> ExperimentalSettingsStore(ctx).setEnvironmentSensorEnabled(on)
                "proximity_sensor" -> ExperimentalSettingsStore(ctx).setProximitySensorEnabled(on)
                else -> return@runCatching "not a writable switch"
            }
            null
        }.getOrElse { it.message ?: "could not write ${pref.id}" }
    }

    private suspend fun writeValue(app: Context, pref: Pref, raw: String): String? {
        val ctx = app.applicationContext
        return when (pref.id) {
            "screensaver_timeout" -> {
                val n = raw.toIntOrNull() ?: return "timeout needs a number"
                ScreensaverSettingsStore(ctx.screensaverSettingsStore)
                    .timeoutSeconds.set(normalizeScreensaverTimeoutSeconds(n))
                null
            }
            else -> "not a writable value"
        }
    }

    private fun needsRestart(id: String): Boolean = id in RESTART

    private fun saver(app: Context, fresh: Boolean = false) =
        ScreensaverSettingsStore(app.applicationContext.screensaverSettingsStore).let { snap(it, fresh) }

    private fun mic(app: Context, fresh: Boolean = false) =
        MicrophoneSettingsStore(app.applicationContext.microphoneSettingsStore).let { snap(it, fresh) }

    private fun player(app: Context, fresh: Boolean = false) =
        PlayerSettingsStore(app.applicationContext.playerSettingsStore).let { snap(it, fresh) }

    private fun sidebar(app: Context, fresh: Boolean = false) =
        SidebarSettingsStore(app.applicationContext.sidebarSettingsStore).let { snap(it, fresh) }

    private fun lock(app: Context, fresh: Boolean = false) =
        HomeLockSettingsStore(app.applicationContext.homeLockSettingsStore).let { snap(it, fresh) }

    private fun experimental(app: Context, fresh: Boolean = false) =
        ExperimentalSettingsStore(app.applicationContext).let { snap(it, fresh) }

    private fun <T> snap(store: SettingsStoreImpl<T>, fresh: Boolean): T =
        if (fresh) runBlocking { store.get() } else store.getCached()

    private fun onOff(on: Boolean): String = if (on) "on" else "off"

    private fun pref(
        id: String,
        route: String,
        titleRes: Int,
        fallback: String,
        vararg keys: String,
        kind: Kind = Kind.Switch,
    ) = Pref(id, route, titleRes, fallback, keys, kind)

    private val RESTART = setOf(
        "enable_idle_screensaver",
        "screensaver_ha_display",
        "screensaver_timeout_visible",
        "dawn_magazine",
        "screensaver_url_visible",
        "camera_enable",
        "occupancy",
        "environment_sensor",
        "proximity_sensor",
        "voice_print",
        "noise_suppressor",
        "software_ns",
        "echo_cancellation",
    )
}
