package com.example.ava.localllm.remote

import android.content.Context
import com.example.ava.services.SatelliteRestartReason
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.MassApiSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.RemoteAiSettingsStore
import com.example.ava.settings.SidebarItemKey
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.massApiSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.settings.remoteAiSettingsStore
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.ui.Screen
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Master switches in Ava settings. A feature that is off is not missing —
 * the settings tree is the door. Do not dump this table into the prompt.
 */
internal object AvaSettingsGates {

    data class Gate(
        val id: String,
        val name: String,
        val route: String,
        val keys: Array<out String>,
        val needsServer: Boolean = false,
    )

    val ALL: List<Gate> = listOf(
        Gate("enable_music", "Music", Screen.SETTINGS_MEDIA_PLAYER,
            arrayOf("音乐", "放歌", "听歌", "放一首", "来一首", "播放歌曲", "播放音乐", "music", "黑胶", "播放器")),
        Gate("enable_mass", "Music Assistant", Screen.SETTINGS_INTERACTION_PLAYBACK_MASS_API,
            arrayOf("music assistant", "mass api", "mass"), needsServer = true),
        Gate("enable_dream_clock", "Dream clock", Screen.SETTINGS_INTERACTION_DREAM_CLOCK_APPEARANCE,
            arrayOf("梦幻时钟", "flip clock", "dream clock")),
        Gate("enable_simple_clock", "Simple clock", Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK,
            arrayOf("简易时钟", "simple clock")),
        Gate("enable_idle_screensaver", "Idle screensaver", Screen.SETTINGS_SCREENSAVER,
            arrayOf("动态屏保", "动态屏", "动态频表", "闲置屏保", "闲置屏", "idle screensaver")),
        Gate("enable_weather", "Weather overlay", Screen.SETTINGS_INTERACTION,
            arrayOf("天气悬浮", "天气窗", "weather overlay", "weather display")),
        Gate("enable_voice_message", "Voice message", Screen.SETTINGS_INTERACTION_VOICE_MESSAGE,
            arrayOf("语音留言", "voice message")),
        Gate("enable_browser", "Browser display", Screen.SETTINGS_BROWSER,
            arrayOf("浏览器显示", "browser display")),
        Gate("enable_quick_entity", "Quick entities", Screen.SETTINGS_INTERACTION_QUICK_ENTITY,
            arrayOf("快捷实体", "快捷开关", "quick entity")),
    )

    private val BY_ID = ALL.associateBy { it.id }

    fun isGateId(raw: String): Boolean = BY_ID.containsKey(raw.trim())

    fun resolve(raw: String): Gate? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        BY_ID[t]?.let { return it }
        return match(t, 1).firstOrNull()
    }

    fun match(utterance: String, limit: Int = 3): List<Gate> {
        val q = utterance.trim()
        if (q.length < 2) return emptyList()
        val hits = ArrayList<Pair<Gate, Int>>()
        for (gate in ALL) {
            var best = 0
            for (key in gate.keys) {
                if (key.length >= 2 && q.contains(key, ignoreCase = true)) {
                    best = maxOf(best, key.length)
                }
            }
            if (best > 0) hits.add(gate to best)
        }
        return hits.sortedWith(compareByDescending<Pair<Gate, Int>> { it.second }.thenBy { it.first.id })
            .map { it.first }
            .distinctBy { it.id }
            .take(limit)
    }

    fun wantsEnable(utterance: String): Boolean {
        val q = utterance.lowercase(Locale.ROOT)
        return ENABLE_KEYS.any { q.contains(it) }
    }

    fun isOn(app: Context, gate: Gate): Boolean = runCatching {
        when (gate.id) {
            "enable_music" -> player(app).enableVinylCover
            "enable_mass" -> {
                val mass = MassApiSettingsStore(app.massApiSettingsStore).getCached()
                mass.enabled && mass.serverUrl.isNotBlank()
            }
            "enable_dream_clock" -> player(app).enableDreamClock
            "enable_simple_clock" -> player(app).enableScreensaver
            "enable_idle_screensaver" ->
                ScreensaverSettingsStore(app.applicationContext.screensaverSettingsStore).getCached().enabled
            "enable_weather" -> player(app).enableWeatherOverlay
            "enable_voice_message" -> player(app).enableVoiceMessageOverlay
            "enable_browser" -> AvaPublishedEntities.catalog(noneOnly = false).any { it.id == "browser_display" }
            "enable_quick_entity" ->
                QuickEntitySettingsStore(app.quickEntitySettingsStore).getCached().enableQuickEntity
            else -> false
        }
    }.getOrDefault(false)

    fun needsServer(app: Context, gate: Gate): Boolean {
        if (!gate.needsServer) return false
        return runCatching {
            MassApiSettingsStore(app.massApiSettingsStore).getCached().serverUrl.isBlank()
        }.getOrDefault(true)
    }

    fun door(app: Context, spoken: String, gate: Gate): JSONObject {
        val ask = !wantsEnable(spoken) && !isGateId(spoken)
        val on = isOn(app, gate)
        val out = JSONObject()
            .put("action", "settings")
            .put("gate", gate.id)
            .put("name", gate.name)
            .put("route", gate.route)
            .put("on", on)
            .put("ask", ask && !on)
        if (!on) {
            out.put(
                "hint",
                if (ask) {
                    "This feature is off. Ask one short question, then ava_self action=set target=${gate.id} on=true. That is the door. Do not ha_search."
                } else {
                    "This feature is off. Call ava_self action=set target=${gate.id} on=true. That is the door."
                },
            )
            out.put(
                "next_action",
                JSONObject()
                    .put("tool", AvaSelfTools.NAME)
                    .put(
                        "arguments",
                        JSONObject()
                            .put("action", if (ask) "settings" else "set")
                            .put("target", if (ask) gate.keys.first() else gate.id)
                            .also { if (!ask) it.put("on", true) },
                    ),
            )
        } else if (needsServer(app, gate)) {
            out.put("needs_server", true)
            out.put("hint", "The door is open. Ask for the Music Assistant address; do not invent a URL.")
        }
        return out
    }

    fun attach(app: Context, body: JSONObject, spoken: String) {
        val hits = match(spoken).ifEmpty { ALL.filter { it.route == body.optString("route") } }
            .filter { !isOn(app, it) }
        if (hits.isEmpty()) return
        val arr = JSONArray()
        for (gate in hits) arr.put(door(app, spoken, gate))
        body.put("gates", arr)
        val prior = body.optString("hint")
        val extra = "A listed feature is off. If they asked for it, ask one short question, then set target= the enable_ id on=true. That is the door."
        body.put("hint", if (prior.isBlank()) extra else "$prior $extra")
    }

    suspend fun set(app: Context, gate: Gate, on: Boolean): JSONObject {
        runCatching { apply(app, gate, on) }
        VoiceSatelliteService.getInstance()?.restartVoiceSatellite(SatelliteRestartReason.SETTINGS)
        val body = JSONObject()
            .put("action", "set")
            .put("id", gate.id)
            .put("name", gate.name)
            .put("on", on)
            .put("opened", true)
        if (on && needsServer(app, gate)) {
            body.put("needs_server", true)
            body.put("hint", "Feature door is open. Ask for the Music Assistant address; do not invent a URL.")
        } else {
            body.put("hint", "Feature door is ${if (isOn(app, gate)) "open" else "closed"}. Look at this receipt. No screenshot.")
        }
        AvaSettingsPoints.open(app, gate.route)
        return body
    }

    private suspend fun apply(app: Context, gate: Gate, on: Boolean) {
        val ctx = app.applicationContext
        val player = PlayerSettingsStore(ctx.playerSettingsStore)
        val sidebar = SidebarSettingsStore(ctx.sidebarSettingsStore)
        when (gate.id) {
            "enable_music" -> {
                player.enableVinylCover.set(on)
                player.enableVinylCoverDisplay.set(on)
                RemoteAiSettingsStore(ctx.remoteAiSettingsStore).toolsMusic.set(on)
                if (on) {
                    val mass = MassApiSettingsStore(ctx.massApiSettingsStore)
                    if (mass.getCached().serverUrl.isNotBlank()) mass.enabled.set(true)
                }
            }
            "enable_mass" -> {
                val mass = MassApiSettingsStore(ctx.massApiSettingsStore)
                if (on && mass.getCached().serverUrl.isBlank()) return
                mass.enabled.set(on)
                RemoteAiSettingsStore(ctx.remoteAiSettingsStore).toolsMusic.set(on)
            }
            "enable_dream_clock" -> {
                player.update { it.copy(enableDreamClock = on, enableDreamClockDisplay = on, enableDreamClockVisible = false) }
                if (on) sidebar.offerHomeEntry(SidebarItemKey.DreamClock)
            }
            "enable_simple_clock" -> {
                player.update { it.copy(enableScreensaver = on, enableScreensaverDisplay = on, enableScreensaverVisible = false) }
                if (on) sidebar.offerHomeEntry(SidebarItemKey.SimpleClock)
            }
            "enable_idle_screensaver" -> {
                ScreensaverSettingsStore(ctx.screensaverSettingsStore).enabled.set(on)
            }
            "enable_weather" -> {
                player.update { it.copy(enableWeatherOverlay = on, enableWeatherOverlayDisplay = on, enableWeatherOverlayVisible = false) }
                if (on) sidebar.offerHomeEntry(SidebarItemKey.Weather)
            }
            "enable_voice_message" -> {
                if (on) {
                    player.enableVoiceMessageOverlay.set(true)
                    player.enableVoiceMessageOverlayDisplay.set(true)
                    sidebar.offerHomeEntry(SidebarItemKey.VoiceMessage)
                } else {
                    player.update { it.copy(enableVoiceMessageOverlay = false, enableVoiceMessageOverlayVisible = false) }
                }
            }
            "enable_browser" -> {
                val browser = BrowserSettingsStore(ctx)
                browser.setEnableBrowserDisplay(on)
                if (on) browser.enableBrowserVisible.set(true)
                if (on) sidebar.offerHomeEntry(SidebarItemKey.Browser)
            }
            "enable_quick_entity" -> {
                val quick = QuickEntitySettingsStore(ctx.quickEntitySettingsStore)
                quick.enableQuickEntity.set(on)
                quick.enableQuickEntityDisplay.set(on)
                if (on) sidebar.offerHomeEntry(SidebarItemKey.QuickEntity)
            }
        }
    }

    private fun player(app: Context) = PlayerSettingsStore(app.applicationContext.playerSettingsStore).getCached()

    private val ENABLE_KEYS = arrayOf("打开", "开启", "开一下", "帮我开", "启用", "enable", "turn on", "switch on")
}
