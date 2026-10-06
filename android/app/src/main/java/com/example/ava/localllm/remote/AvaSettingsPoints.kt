package com.example.ava.localllm.remote

import android.content.Context
import com.example.ava.services.WebViewService
import com.example.ava.ui.MainNavigationCoordinator
import com.example.ava.ui.Screen
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Buried points for Ava's own settings tree. The host opens a route and
 * returns this page as text (live names, state, writable, visible taps) —
 * no screenshot. House / HA overlay settings stay on `ava_page_*`.
 * Do not dump this table into the prompt.
 *
 * Intent:
 * - "Ava设置" is always this tree.
 * - "HA设置" / "hash设置" is Home Assistant `/config/dashboard`.
 * - Bare "设置" (no clock time, no task) while the HA overlay is closed is this tree.
 * - Bare "设置" while that overlay is open, or "在这个悬浮窗里", stays on the page.
 * - "设置" plus a clock time or a reminder task is not this tree.
 *
 * Voice-seat kill buttons and camera snapshots are not points.
 */
internal object AvaSettingsPoints {

    enum class Intent { AVA, HA, OVERLAY, NONE }

    data class Page(
        val route: String,
        val name: String,
        val group: String,
        val keys: Array<out String>,
        val weight: Int = 0,
        val points: Array<out String> = emptyArray(),
        val children: Array<out String> = emptyArray(),
    )

    val BLOCKED = setOf(
        "restart_service",
        "reboot_device",
        "kill_app",
        "manual_wake",
        "wake_word_engine",
        "take_snapshot",
        "camera_snapshot",
        "firmware_update",
    )

    val ALL: List<Page> = listOf(
        page(
            Screen.SETTINGS, "Ava settings", "root", 30,
            "ava设置", "阿瓦设置", "ava settings", "本机设置", "设备设置",
            children = arrayOf(
                Screen.SETTINGS_CONNECTION, Screen.SETTINGS_HA, Screen.SETTINGS_VOICE_TTS,
                Screen.SETTINGS_SERVICE, Screen.SETTINGS_INTERACTION, Screen.SETTINGS_BROWSER,
                Screen.SETTINGS_SCREENSAVER, Screen.SETTINGS_BLUETOOTH, Screen.SETTINGS_EXPERIMENTAL,
                Screen.SETTINGS_ROOT,
            ),
        ),
        page(
            Screen.SETTINGS_CONNECTION, "Connection", "connection", 12,
            "连接", "connection", "服务器",
            children = arrayOf(
                Screen.SETTINGS_HA, Screen.SETTINGS_HA_LOCAL_LLM, Screen.SETTINGS_VOICE_WAKE,
                Screen.SETTINGS_VOICE_MICROPHONE, Screen.SETTINGS_VOICE_PRINT, Screen.SETTINGS_VOICE_STT,
                Screen.SETTINGS_VOICE_TTS, Screen.SETTINGS_VOICE_STREAMING_TTS,
                Screen.SETTINGS_VOICE_AUDIO_EVENT, Screen.SETTINGS_VOICE_FEEDBACK_ACCENT,
            ),
        ),
        page(Screen.SETTINGS_HA, "Home Assistant account", "connection", 10, "hass 账号", "ha 账号", "连接 home assistant",
            children = arrayOf(Screen.SETTINGS_HA_LOCAL_LLM)),
        page(Screen.SETTINGS_HA_LOCAL_LLM, "Local LLM", "connection", 10, "本地模型", "local llm", "远程模型",
            children = arrayOf(Screen.SETTINGS_HA_LOCAL_LLM_REMOTE, Screen.SETTINGS_HA_LOCAL_LLM_PROMPT)),
        page(Screen.SETTINGS_HA_LOCAL_LLM_REMOTE, "Remote AI", "connection", 8, "远程 ai", "remote ai", "failover"),
        page(Screen.SETTINGS_HA_LOCAL_LLM_PROMPT, "Remote prompt", "connection", 6, "提示词", "prompt"),
        page(Screen.SETTINGS_VOICE_WAKE, "Wake word", "voice", 8, "唤醒词", "wake word",
            children = arrayOf(Screen.SETTINGS_VOICE_WAKE_LIBRARY, Screen.SETTINGS_VOICE_WAKE_LEARN)),
        page(Screen.SETTINGS_VOICE_WAKE_LIBRARY, "Wake library", "voice", 4, "唤醒词库"),
        page(Screen.SETTINGS_VOICE_WAKE_LEARN, "Wake learn", "voice", 4, "唤醒学习"),
        page(
            Screen.SETTINGS_VOICE_MICROPHONE, "Microphone", "voice", 12,
            "麦克风", "话筒", "microphone",
            points = arrayOf("microphone_volume"),
            children = arrayOf(Screen.SETTINGS_VOICE_NOISE_SUPPRESSION, Screen.SETTINGS_VOICE_ECHO_CANCELLATION),
        ),
        page(Screen.SETTINGS_VOICE_NOISE_SUPPRESSION, "Noise suppression", "voice", 6, "降噪", "noise suppression",
            points = arrayOf("noise_suppressor", "software_ns")),
        page(Screen.SETTINGS_VOICE_ECHO_CANCELLATION, "Echo cancellation", "voice", 6, "回声消除", "echo",
            points = arrayOf("echo_cancellation")),
        page(Screen.SETTINGS_VOICE_PRINT, "Voice print", "voice", 6, "声纹", "voice print",
            points = arrayOf("voice_print")),
        page(Screen.SETTINGS_VOICE_STT, "Speech to text", "voice", 10, "识别", "stt", "语音识别"),
        page(
            Screen.SETTINGS_VOICE_TTS, "Talking", "voice", 20,
            "talking", "说话", "讲话", "播报", "朗读", "tts", "语音播报",
            points = arrayOf("tts_volume"),
        ),
        page(Screen.SETTINGS_VOICE_STREAMING_TTS, "Streaming talking", "voice", 8, "流式播报", "streaming tts", "自动增益"),
        page(Screen.SETTINGS_VOICE_AUDIO_EVENT, "Audio event", "voice", 6, "音频事件"),
        page(Screen.SETTINGS_VOICE_FEEDBACK_ACCENT, "Voice accent", "voice", 6, "口音", "反馈音色"),
        page(
            Screen.SETTINGS_SERVICE, "Device service", "service", 8,
            "设备服务", "服务设置",
            children = arrayOf(
                Screen.SETTINGS_SERVICE_DEVICE_CONTROL, Screen.SETTINGS_SERVICE_DEVICE_LOGS,
                Screen.SETTINGS_SERVICE_AUTO_RESTART, Screen.SETTINGS_SOFTWARE_UPDATE,
                Screen.SETTINGS_SERVICE_MINIMAL_LAUNCHER, Screen.SETTINGS_SERVICE_TOUCH_SOUND,
                Screen.SETTINGS_SERVICE_SCREEN_POWER, Screen.SETTINGS_SERVICE_SCREEN_BRIGHTNESS,
                Screen.SETTINGS_SERVICE_SCREEN_TOUCH, Screen.SETTINGS_SERVICE_FORCE_ORIENTATION,
                Screen.SETTINGS_SERVICE_PROXIMITY, Screen.SETTINGS_ENVIRONMENT, Screen.SETTINGS_DIAGNOSTIC,
                Screen.SETTINGS_STYLE, Screen.SETTINGS_SIDEBAR, Screen.SETTINGS_HOME_LOCK,
                Screen.SETTINGS_INTERACTION_INTERFACE,
            ),
        ),
        page(Screen.SETTINGS_SERVICE_DEVICE_CONTROL, "Device control", "service", 8, "设备控制",
            points = arrayOf("lock_screen", "screen_brightness")),
        page(Screen.SETTINGS_SERVICE_DEVICE_LOGS, "Device logs", "service", 4, "日志"),
        page(Screen.SETTINGS_SERVICE_AUTO_RESTART, "Keep running", "service", 4, "保活", "自动重启",
            points = arrayOf("auto_restart")),
        page(Screen.SETTINGS_SOFTWARE_UPDATE, "Software update", "service", 6, "软件更新", "升级",
            children = arrayOf(Screen.SETTINGS_SOFTWARE_UPDATE_PREFS)),
        page(Screen.SETTINGS_SOFTWARE_UPDATE_PREFS, "Update prefs", "service", 3, "更新偏好"),
        page(Screen.SETTINGS_SERVICE_MINIMAL_LAUNCHER, "Minimal launcher", "service", 6, "极简桌面",
            points = arrayOf("minimal_launcher", "minimal_launcher_app"),
            children = arrayOf(Screen.SETTINGS_SERVICE_MINIMAL_LAUNCHER_APPS)),
        page(Screen.SETTINGS_SERVICE_MINIMAL_LAUNCHER_APPS, "Launcher apps", "service", 4, "桌面应用", "应用窗口"),
        page(Screen.SETTINGS_SERVICE_TOUCH_SOUND, "Touch sound", "service", 4, "按键音",
            points = arrayOf("touch_sound")),
        page(Screen.SETTINGS_SERVICE_SCREEN_POWER, "Screen power", "service", 6, "屏幕电源"),
        page(Screen.SETTINGS_SERVICE_SCREEN_BRIGHTNESS, "Screen brightness", "service", 10, "亮度", "屏幕亮度",
            points = arrayOf("screen_brightness")),
        page(
            Screen.SETTINGS_SERVICE_SCREEN_TOUCH, "Screen touch", "service", 6, "触控", "手势",
            points = arrayOf("screen_touch", "screen_gesture"),
            children = arrayOf(
                Screen.SETTINGS_SERVICE_SCREEN_GESTURE_SPATIAL,
                Screen.SETTINGS_SERVICE_SCREEN_GESTURE_DIGITS,
                Screen.SETTINGS_SERVICE_SCREEN_GESTURE_GEOMETRY,
            ),
        ),
        page(Screen.SETTINGS_SERVICE_SCREEN_GESTURE_SPATIAL, "Spatial gestures", "service", 3, "空间手势"),
        page(Screen.SETTINGS_SERVICE_SCREEN_GESTURE_DIGITS, "Digit gestures", "service", 3, "数字手势"),
        page(Screen.SETTINGS_SERVICE_SCREEN_GESTURE_GEOMETRY, "Geometry gestures", "service", 3, "几何手势"),
        page(Screen.SETTINGS_SERVICE_FORCE_ORIENTATION, "Orientation", "service", 4, "方向锁定", "横屏", "竖屏"),
        page(Screen.SETTINGS_SERVICE_PROXIMITY, "Proximity", "service", 4, "距离感应",
            points = arrayOf("proximity_sensor")),
        page(Screen.SETTINGS_STYLE, "Settings style", "service", 8, "设置风格", "玻璃", "liquid glass"),
        page(Screen.SETTINGS_SIDEBAR, "Sidebar", "service", 8, "侧栏", "sidebar",
            points = arrayOf("sidebar_enable"),
            children = arrayOf(Screen.SETTINGS_SIDEBAR_TOUCH_PAD)),
        page(Screen.SETTINGS_SIDEBAR_TOUCH_PAD, "Touch pad", "service", 6, "触控板", "touch pad",
            points = arrayOf("touch_pad_take")),
        page(Screen.SETTINGS_HOME_LOCK, "Home lock", "service", 6, "锁屏密码", "home lock",
            points = arrayOf("home_lock")),
        page(
            Screen.SETTINGS_INTERACTION, "Interaction", "interaction", 8,
            "交互", "扩展",
            children = arrayOf(
                Screen.SETTINGS_INTERACTION_INTERFACE, Screen.SETTINGS_INTERACTION_PLAYBACK,
                Screen.SETTINGS_INTERACTION_SCENE, Screen.SETTINGS_INTERACTION_VOICE_MESSAGE,
                Screen.SETTINGS_INTERACTION_QUICK_ENTITY, Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK,
                Screen.SETTINGS_INTERACTION_DREAM_CLOCK_APPEARANCE, Screen.SETTINGS_MEDIA_PLAYER,
            ),
        ),
        page(Screen.SETTINGS_INTERACTION_INTERFACE, "Home interface", "interaction", 8, "主界面", "首页界面"),
        page(
            Screen.SETTINGS_INTERACTION_PLAYBACK, "Playback", "interaction", 8, "播放", "播放器", "音乐",
            children = arrayOf(
                Screen.SETTINGS_INTERACTION_PLAYBACK_EQ_HA,
                Screen.SETTINGS_INTERACTION_PLAYBACK_EQ_MA,
                Screen.SETTINGS_INTERACTION_PLAYBACK_MASS_API,
            ),
        ),
        page(Screen.SETTINGS_INTERACTION_PLAYBACK_EQ_HA, "HA equalizer", "interaction", 4, "均衡器"),
        page(Screen.SETTINGS_INTERACTION_PLAYBACK_EQ_MA, "Music Assistant equalizer", "interaction", 4, "mass 均衡器"),
        page(Screen.SETTINGS_INTERACTION_PLAYBACK_MASS_API, "Music Assistant API", "interaction", 4, "mass api"),
        page(
            Screen.SETTINGS_INTERACTION_SCENE, "Scenes", "interaction", 6, "场景", "通知样式",
            children = arrayOf(
                Screen.SETTINGS_INTERACTION_SCENE_BANNER,
                Screen.SETTINGS_INTERACTION_SCENE_LIBRARY,
                Screen.SETTINGS_INTERACTION_SCENE_GENERAL,
            ),
        ),
        page(Screen.SETTINGS_INTERACTION_SCENE_BANNER, "Scene banner", "interaction", 3, "场景横幅"),
        page(Screen.SETTINGS_INTERACTION_SCENE_LIBRARY, "Scene library", "interaction", 3, "场景库"),
        page(Screen.SETTINGS_INTERACTION_SCENE_GENERAL, "Scene general", "interaction", 3, "场景通用"),
        page(Screen.SETTINGS_INTERACTION_VOICE_MESSAGE, "Voice message", "interaction", 8, "语音留言",
            points = arrayOf("voice_message_display", "voice_message_delay_minutes", "voice_target")),
        page(Screen.SETTINGS_INTERACTION_QUICK_ENTITY, "Quick entities", "interaction", 8, "快捷实体", "快捷开关",
            points = arrayOf("quick_entity_slot_1", "quick_entity_slot_2", "quick_entity_slot_3", "quick_entity_slot_4")),
        page(Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK, "Simple clock", "interaction", 8, "简易时钟",
            children = arrayOf(Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK_APPEARANCE, Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK_STATUS)),
        page(Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK_APPEARANCE, "Simple clock appearance", "interaction", 8, "简易时钟外观",
            points = arrayOf("simple_clock_display")),
        page(Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK_STATUS, "Clock status slots", "interaction", 6, "状态槽",
            points = arrayOf("simple_clock_status_slot_1", "simple_clock_status_slot_2", "simple_clock_status_slot_3")),
        page(Screen.SETTINGS_INTERACTION_DREAM_CLOCK_APPEARANCE, "Dream clock", "interaction", 8, "梦幻时钟", "flip clock",
            points = arrayOf("dream_clock_display", "dream_clock_timer", "dream_clock_flip_style")),
        page(
            Screen.SETTINGS_BROWSER, "Browser display", "browser", 10,
            "浏览器显示", "悬浮窗设置",
            points = arrayOf("browser_display", "browser_refresh", "browser_scale", "browser_power_mode"),
            children = arrayOf(
                Screen.SETTINGS_BROWSER_HA, Screen.SETTINGS_BROWSER_DISPLAY, Screen.SETTINGS_BROWSER_SPLIT,
                Screen.SETTINGS_BROWSER_TOUCH, Screen.SETTINGS_BROWSER_STEWARD, Screen.SETTINGS_BROWSER_SIDEBAR,
                Screen.SETTINGS_BROWSER_COMPAT,
            ),
        ),
        page(Screen.SETTINGS_BROWSER_HA, "Browser HA", "browser", 8, "浏览器地址", "远程网页"),
        page(Screen.SETTINGS_BROWSER_DISPLAY, "Browser chrome", "browser", 6, "浏览器外观"),
        page(Screen.SETTINGS_BROWSER_SPLIT, "Split view", "browser", 6, "分屏"),
        page(Screen.SETTINGS_BROWSER_TOUCH, "Browser touch", "browser", 4, "浏览器触控"),
        page(Screen.SETTINGS_BROWSER_STEWARD, "Steward", "browser", 4, "steward", "网页管家"),
        page(Screen.SETTINGS_BROWSER_SIDEBAR, "Browser sidebar", "browser", 4, "浏览器侧栏"),
        page(Screen.SETTINGS_BROWSER_COMPAT, "Browser compat", "browser", 4, "兼容"),
        page(Screen.SETTINGS_SCREENSAVER, "Screensaver", "screensaver", 10,
            "动态屏保", "动态屏", "动态频表", "闲置屏保", "屏保", "screensaver",
            points = arrayOf("enable_idle_screensaver", "screensaver_timeout", "screensaver_ha_display",
                "screensaver_timeout_visible", "screensaver_timeout_zero"),
            children = arrayOf(Screen.SETTINGS_SCREENSAVER_CONTENT, Screen.SETTINGS_SCREENSAVER_BEHAVIOR)),
        page(Screen.SETTINGS_SCREENSAVER_CONTENT, "Screensaver content", "screensaver", 6, "屏保内容", "画报", "dawn",
            points = arrayOf("dawn_magazine", "screensaver_url_visible",
                "xiaomi_entity_slot_1", "xiaomi_entity_slot_2", "xiaomi_entity_slot_3")),
        page(Screen.SETTINGS_SCREENSAVER_BEHAVIOR, "Screensaver behavior", "screensaver", 4, "屏保行为",
            points = arrayOf("screensaver_dark_off", "screensaver_pixel_shift", "screensaver_smart_aod",
                "screensaver_cpu_throttle", "screensaver_person_wake", "screensaver_keep_on_overlays",
                "screensaver_background_pause",
                "screensaver_motion_on", "screensaver_show_after_screen_on", "screensaver_ha_two_way")),
        page(Screen.SETTINGS_BLUETOOTH, "Bluetooth", "bluetooth", 8, "蓝牙", "bluetooth",
            points = arrayOf("bluetooth_rssi_threshold", "bluetooth_away_delay", "bluetooth_proxy_scan_mode", "bluetooth_proxy_scan_power")),
        page(
            Screen.SETTINGS_EXPERIMENTAL, "Experimental", "advanced", 6, "实验", "高级",
            children = arrayOf(
                Screen.MOD_STORE, Screen.SETTINGS_CAMERA, Screen.SETTINGS_OCCUPANCY,
                Screen.SETTINGS_INTENT_LAUNCHER, Screen.SETTINGS_CLUSTER_MANAGEMENT, Screen.SETTINGS_BACKUP_RESTORE,
            ),
        ),
        page(Screen.MOD_STORE, "Mod store", "advanced", 4, "模组", "模组商店", "mod store", "mod"),
        page(Screen.SETTINGS_CAMERA, "Camera", "advanced", 4, "摄像头",
            points = arrayOf("camera_enable", "video_camera", "video_recording")),
        page(Screen.SETTINGS_OCCUPANCY, "Occupancy", "advanced", 4, "占用", "在席",
            points = arrayOf("occupancy")),
        page(Screen.SETTINGS_INTENT_LAUNCHER, "Intent launcher", "advanced", 4, "intent", "adb总控", "adb 总控",
            points = arrayOf("intent_launcher_status")),
        page(Screen.SETTINGS_CLUSTER_MANAGEMENT, "Cluster", "advanced", 4, "集群"),
        page(Screen.SETTINGS_BACKUP_RESTORE, "Backup", "advanced", 6, "备份", "恢复", "克隆",
            children = arrayOf(Screen.SETTINGS_BACKUP_CLONE_SEND, Screen.SETTINGS_BACKUP_CLONE_RECEIVE)),
        page(Screen.SETTINGS_BACKUP_CLONE_SEND, "Clone send", "advanced", 3, "发送克隆"),
        page(Screen.SETTINGS_BACKUP_CLONE_RECEIVE, "Clone receive", "advanced", 3, "接收克隆"),
        page(Screen.SETTINGS_ENVIRONMENT, "Environment sensors", "service", 6, "环境传感", "光照",
            points = arrayOf("environment_sensor", "light_sensor")),
        page(Screen.SETTINGS_DIAGNOSTIC, "Diagnostics", "service", 4, "诊断"),
        page(Screen.SETTINGS_ROOT, "Permissions", "root", 6, "权限", "root",
            children = arrayOf(Screen.SETTINGS_PERMISSION_MANAGER)),
        page(Screen.SETTINGS_PERMISSION_MANAGER, "Permission manager", "root", 4, "权限管理"),
        page(Screen.SETTINGS_MEDIA_PLAYER, "Media player", "interaction", 6, "媒体播放", "音乐", "黑胶",
            points = arrayOf("vinyl_cover_display")),
    )

    @Volatile private var lastUtterance = ""
    private val originLock = Any()
    @Volatile private var origin: String? = null
    @Volatile private var leftOrigin = false

    fun rememberUtterance(text: String) {
        lastUtterance = text.trim()
    }

    /**
     * When the turn is done, leave Ava settings. Mid-task / keepGoing
     * must not call this — same rule as the HA overlay trail.
     */
    fun onTurnFinished() {
        val dest = synchronized(originLock) {
            if (!leftOrigin) {
                origin = null
                return
            }
            val dest = origin ?: Screen.HOME
            origin = null
            leftOrigin = false
            dest
        }
        MainNavigationCoordinator.requestNavigation(dest)
    }

    fun overlayOpen(): Boolean = WebViewService.isBrowserOverlayVisible()

    fun intent(utterance: String = lastUtterance, overlayOpen: Boolean = overlayOpen()): Intent {
        val q = utterance.trim()
        if (q.length < 2) return Intent.NONE
        if (containsAny(q, AVA_KEYS)) return Intent.AVA
        if (containsAny(q, OVERLAY_KEYS)) return Intent.OVERLAY
        if (containsAny(q, HA_KEYS) || containsAny(q, HA_THEME_KEYS)) return Intent.HA
        if (hasSpecificPage(q)) return Intent.AVA
        if (containsAny(q, SETTINGS_KEYS)) {
            if (!isBareSettings(q)) return Intent.NONE
            return if (overlayOpen) Intent.HA else Intent.AVA
        }
        return Intent.NONE
    }

    /** True when the utterance is 设置 / settings plus openers, and nothing else. */
    internal fun isBareSettings(utterance: String): Boolean {
        var rest = utterance.lowercase(Locale.ROOT)
        if (SETTINGS_KEYS.none { rest.contains(it) }) return false
        for (key in SETTINGS_KEYS.sortedByDescending { it.length }) {
            rest = rest.replace(key, " ", ignoreCase = true)
        }
        for (filler in SETTINGS_OPENERS.sortedByDescending { it.length }) {
            rest = rest.replace(filler, " ", ignoreCase = true)
        }
        return rest.replace(Regex("[\\s\\p{Punct}]+"), "").isEmpty()
    }

    /** 设置闹钟 / 设置提醒 — do not open the settings tree. */
    internal fun refuseSettingsOpen(spoken: String): Boolean {
        val q = spoken.trim().ifBlank { lastUtterance }
        if (q.length < 2) return false
        if (hasSpecificPage(q)) return false
        return containsAny(q, SETTINGS_KEYS) && !isBareSettings(q)
    }

    fun match(utterance: String, limit: Int = 3): List<Page> {
        val q = utterance.trim()
        if (q.length < 2) return emptyList()
        val hits = ArrayList<Pair<Page, Int>>()
        for (page in ALL) {
            var best = 0
            for (key in page.keys) {
                if (key.length >= 2 && q.contains(key, ignoreCase = true)) {
                    best = maxOf(best, key.length * 10 + page.weight)
                }
            }
            for (id in page.points) {
                if (id.length >= 2 && q.contains(id, ignoreCase = true)) {
                    best = maxOf(best, id.length * 10 + page.weight)
                }
            }
            if (best > 0) hits.add(page to best)
        }
        return hits.sortedWith(compareByDescending<Pair<Page, Int>> { it.second }.thenBy { it.first.route })
            .map { it.first }
            .distinctBy { it.route }
            .take(limit)
    }

    fun resolve(raw: String): Page? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        ALL.firstOrNull { it.route.equals(t, ignoreCase = true) }?.let { return it }
        ALL.firstOrNull { page -> page.points.any { it.equals(t, ignoreCase = true) } }?.let { return it }
        return match(t, 1).firstOrNull()
    }

    fun isBlocked(id: String): Boolean = id.trim().lowercase(Locale.ROOT) in BLOCKED

    fun here(): String? = MainNavigationCoordinator.currentRoute()

    fun inSettings(route: String? = here()): Boolean =
        MainNavigationCoordinator.isSettingsLikeRoute(route)

    fun isHere(utterance: String = lastUtterance): Boolean = containsAny(utterance.trim(), HERE_KEYS)

    fun pageAt(route: String?): Page? {
        val t = route?.trim().orEmpty()
        if (t.isEmpty()) return null
        ALL.firstOrNull { it.route.equals(t, ignoreCase = true) }?.let { return it }
        return ALL.filter { t.startsWith(it.route) && it.route.isNotEmpty() }
            .maxByOrNull { it.route.length }
    }

    fun crumbs(route: String?, app: Context? = null): JSONArray {
        val t = route?.trim().orEmpty()
        val out = JSONArray()
        if (t.isEmpty()) return out
        val seen = HashSet<String>()
        for (page in ALL.sortedBy { it.route.length }) {
            if (t == page.route || t.startsWith("${page.route}/")) {
                if (seen.add(page.route)) {
                    val name = if (app != null) AvaSettingsLive.title(app, page) else page.name
                    out.put(JSONObject().put("name", name).put("route", page.route))
                }
            }
        }
        if (out.length() == 0 || out.optJSONObject(out.length() - 1)?.optString("route") != t) {
            pageAt(t)?.let { last ->
                if (last.route != t) {
                    val name = if (app != null) AvaSettingsLive.title(app, last) else last.name
                    out.put(JSONObject().put("name", name).put("route", t))
                }
            } ?: out.put(JSONObject().put("name", t.substringAfterLast('/')).put("route", t))
        }
        return out
    }

    suspend fun peek(app: Context): JSONObject {
        val route = here().orEmpty()
        if (!inSettings(route)) {
            return JSONObject()
                .put("action", "settings")
                .put("here", route.ifBlank { Screen.HOME })
                .put("path", route.ifBlank { Screen.HOME })
                .put("in_settings", false)
                .put("hint", "Not in Ava settings. Call settings target= a page name to open one. Omit target later to read here/path.")
        }
        val page = pageAt(route) ?: ALL.first { it.route == Screen.SETTINGS }
        return paint(locate(receipt(app, page), route, opened = false, app), retries = 0, app = app)
    }

    suspend fun open(app: Context, spoken: String = ""): JSONObject {
        val query = spoken.trim().ifBlank { lastUtterance }
        if (isHere(query) || (query.isEmpty() && inSettings())) {
            return peek(app)
        }
        if (refuseSettingsOpen(query) && resolve(query) == null) {
            return JSONObject()
                .put("action", "settings")
                .put("hint", "That is not Ava settings. A clock time or a reminder task is not a settings page.")
        }
        val page = resolve(query) ?: ALL.first { it.route == Screen.SETTINGS }
        synchronized(originLock) {
            seedOriginLocked(MainNavigationCoordinator.currentRoute())
            leftOrigin = true
        }
        MainNavigationCoordinator.requestNavigation(page.route)
        val landed = awaitHere(page.route)
        delay(150)
        val live = pageAt(landed) ?: page
        val body = locate(receipt(app, live), landed ?: page.route, opened = true, app)
        AvaSettingsGates.attach(app, body, query)
        return paint(body, retries = 2, app = app)
    }

    fun receipt(app: Context, page: Page): JSONObject {
        val published = AvaPublishedEntities.catalog(noneOnly = false).associateBy { it.id }
        val local = runCatching { AvaLocalFeatures.catalog(app).associateBy { it.id } }.getOrDefault(emptyMap())
        val points = JSONArray()
        for (id in page.points) {
            if (isBlocked(id)) continue
            val item = published[id]
            val feature = local[id]
            val writable = AvaSettingsLive.writable(id, hasSetter = item != null, local = feature != null)
            points.put(
                AvaSettingsLive.pointRow(
                    id = id,
                    name = AvaSettingsLive.pickName(
                        AvaSettingsLive.entityLabel(app, id),
                        item?.name,
                        feature?.name,
                        id,
                    ),
                    kind = item?.kind ?: if (feature != null) "switch" else "text",
                    state = item?.state ?: feature?.let { if (it.on) "on" else "off" },
                    options = item?.options,
                    writable = writable,
                    min = item?.min,
                    max = item?.max,
                ),
            )
        }
        val menus = JSONArray()
        val byRoute = ALL.associateBy { it.route }
        for (child in page.children) {
            byRoute[child]?.let { dest ->
                menus.put(JSONObject().put("name", AvaSettingsLive.title(app, dest)).put("route", dest.route))
            }
        }
        val out = JSONObject()
            .put("action", "settings")
            .put("route", page.route)
            .put("page", AvaSettingsLive.title(app, page))
        if (menus.length() > 0) out.put("menus", menus)
        AvaSettingsPrefs.attach(app, page.route, points)
        if (points.length() > 0) out.put("points", points)
        synchronized(originLock) {
            origin?.let { out.put("origin", it) }
            if (leftOrigin) out.put("will_restore", true)
            out.put("hint", receiptHint(leftOrigin, page.route))
        }
        return out
    }

    private suspend fun paint(body: JSONObject, retries: Int, app: Context): JSONObject {
        val route = body.optString("route").ifBlank { body.optString("here") }
        if (route == Screen.MOD_STORE) {
            AvaModStore.attach(app, body, refreshIfEmpty = retries > 0)
        }
        val visible = AvaSettingsLive.takeVisible(retries)
        if (visible != null && visible.length() > 0) {
            AvaSettingsLive.linkPoints(visible, body.optJSONArray("points"))
            AvaModStore.linkVisible(visible, body.optJSONArray("mods"))
            body.put("visible", visible)
            body.put("visible_via", "in_app")
        }
        return body
    }

    private fun receiptHint(willRestore: Boolean, route: String = ""): String {
        val restore = if (willRestore) {
            " Host restores origin when this turn ends — do not leave the user in Ava settings."
        } else {
            ""
        }
        if (route == Screen.MOD_STORE) {
            return AvaModStore.listHint() + restore
        }
        return "This page as text — no screenshot. page/menus/points use the labels on screen; points have name, live on/off or value, writable. " +
            "set a writable id, or ava_volume when via says so. visible[] switches also carry checked when the host can see it. " +
            "visible[] is Ava's own window — tap with ava_phone only when here_ui.top is ava. " +
            "If top is hass or overlay, those taps hit the window above and this page does not change. " +
            "Omit target to read the page again. settings target= a menu name opens it.$restore Voice-seat kill buttons are not listed."
    }

    private fun locate(body: JSONObject, route: String, opened: Boolean, app: Context): JSONObject {
        body.put("here", route)
        body.put("path", route)
        body.put("crumbs", crumbs(route, app))
        body.put("in_settings", inSettings(route))
        body.put("opened", opened)
        return body
    }

    private suspend fun awaitHere(want: String): String? {
        repeat(8) {
            val now = here()
            if (now == want || (now != null && now.startsWith("$want/"))) return now
            delay(100)
        }
        return here()
    }

    private fun seedOriginLocked(here: String?) {
        if (origin != null) return
        origin = when {
            here.isNullOrBlank() -> Screen.HOME
            MainNavigationCoordinator.isSettingsLikeRoute(here) -> Screen.HOME
            MainNavigationCoordinator.isRestorableRoute(here) -> here
            else -> Screen.HOME
        }
    }

    internal fun resetForTest() {
        lastUtterance = ""
        synchronized(originLock) {
            origin = null
            leftOrigin = false
        }
    }

    internal fun markOpenedForTest(here: String?) {
        synchronized(originLock) {
            seedOriginLocked(here)
            leftOrigin = true
        }
    }

    internal fun originForTest(): String? = synchronized(originLock) { origin }

    internal fun willRestoreForTest(): Boolean = synchronized(originLock) { leftOrigin }

    private val AVA_KEYS = arrayOf("ava设置", "阿瓦设置", "ava settings", "ava 设置", "本机设置", "设备设置")
    private val HA_KEYS = arrayOf(
        "ha设置", "ha 设置", "hash设置", "hash 设置", "hass设置", "hass 设置",
        "home assistant设置", "home assistant 设置", "homeassistant设置",
    )
    private val HA_THEME_KEYS = arrayOf("换个主题", "换主题", "改主题", "主题", "换肤", "外观")
    private val SETTINGS_KEYS = arrayOf("设置", "设定", "settings")
    private val SETTINGS_OPENERS = arrayOf(
        "打开", "进入", "进到", "帮我", "请", "一下", "来到", "去", "进",
        "open", "please", "go to",
    )
    private val HERE_KEYS = arrayOf(
        "当前路径", "哪个页面", "什么页面", "现在这个", "当前页面", "这个页面", "这里", "here",
        "当前界面", "什么界面", "哪个界面", "现在在哪", "在什么界面", "界面下",
    )
    private val OVERLAY_KEYS = arrayOf("这个悬浮窗", "当前悬浮窗", "这个窗口里", "在这个页面", "当前这个页面", "悬浮窗里面")

    private fun containsAny(q: String, keys: Array<String>): Boolean =
        keys.any { it.length >= 2 && q.contains(it, ignoreCase = true) }

    private fun hasSpecificPage(q: String): Boolean =
        match(q).any { page ->
            page.keys.any { key ->
                key.length >= 3 &&
                    q.contains(key, ignoreCase = true) &&
                    key !in SETTINGS_KEYS
            } || page.points.any { id -> q.contains(id, ignoreCase = true) }
        }

    private fun page(
        route: String,
        name: String,
        group: String,
        weight: Int,
        vararg keys: String,
        points: Array<out String> = emptyArray(),
        children: Array<out String> = emptyArray(),
    ) = Page(route, name, group, keys, weight, points, children)
}
