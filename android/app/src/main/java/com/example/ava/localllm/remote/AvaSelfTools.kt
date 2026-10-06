package com.example.ava.localllm.remote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.provider.Settings
import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolArgumentCase
import com.example.ava.localllm.ToolDef
import com.example.ava.localllm.ToolParam
import com.example.ava.localllm.ToolParamType
import com.example.ava.clock.ClockAlert
import com.example.ava.clock.ClockAlertSensor
import com.example.ava.clock.ClockAlertStore
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.services.ClockAlertOverlayService
import com.example.ava.services.DreamClockService
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.weather.WeatherData
import com.example.ava.weather.WeatherService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * This speaker's body. Never routed through Home Assistant: screen, mic,
 * stop, local sensors, and this speaker's published ESPHome (entities → set).
 * Feature off (voice service not running) → empty schema. Unpublished
 * overlays do not appear in the action list or any tool JSON.
 */
object AvaSelfTools {

    const val NAME = "ava_self"
    const val PREFIX = "ava_self"

    fun ready(): Boolean = VoiceSatelliteService.getInstance() != null &&
        VoiceSatelliteService.isSatelliteStarted()

    /** Cheap snapshot for `## Now`. No sensor wait, no weather. */
    data class NowFacts(
        val brightness: Int? = null,
        val screenOn: Boolean? = null,
        val muted: Boolean? = null,
        val micLevel: Int? = null,
        val battery: Int? = null,
        val timerAvailable: Boolean = false,
        val timerRemainingSec: Int? = null,
        val timerPaused: Boolean? = null,
        val timerRinging: Boolean? = null,
        /** Null when browser display is not a feature of this Ava. */
        val browserDisplay: Boolean? = null,
        val clockAlertRinging: Boolean? = null,
        val clockAlertKind: String? = null,
        val clockAlertTime: String? = null,
        val clockAlertLabel: String? = null,
    )

    fun nowFacts(app: Context): NowFacts {
        val timerOn = DreamClockService.timerEnabled(app)
        val snap = DreamClockService.timerSnapshot()
        val remaining = (snap.remainingMs / 1000L).toInt().takeIf { snap.running || snap.paused }
        val soonest = ClockAlertStore.soonest(app)
        return NowFacts(
            brightness = readBrightnessPctOrNull(app),
            screenOn = ScreenControlUtils.panelOnState.value,
            muted = VoiceSatelliteService.isMicMuted(),
            micLevel = VoiceSatelliteService.micVolume()?.let { (it / 2f * 100f).toInt().coerceIn(0, 100) },
            battery = batteryPctOrNull(app),
            timerAvailable = timerOn,
            timerRemainingSec = remaining,
            timerPaused = true.takeIf { snap.paused },
            timerRinging = true.takeIf { timerOn && (snap.ringing || VoiceSatelliteService.isTimerRingingNow()) },
            browserDisplay = AvaPublishedEntities.browserDisplayOn(),
            clockAlertRinging = true.takeIf { ClockAlertOverlayService.isRinging() },
            clockAlertKind = soonest?.let {
                if (it.kind == ClockAlert.Kind.REMINDER) "reminder" else "alarm"
            },
            clockAlertTime = soonest?.dateTimeText(),
            clockAlertLabel = soonest?.label?.trim()?.ifEmpty { null },
        )
    }

    fun surface(ready: Boolean = ready(), app: Context? = null): HaToolSet {
        if (!ready) return HaToolSet(emptyList())
        val actions = ArrayList<String>().apply {
            add("screen")
            if (app == null || PlatformCapabilities.canWriteSettings(app)) add("brightness")
            add("mic")
            add("stop")
            add("read")
            add("settings")
            add("mods")
            add("alarm")
            add("reminder")
            if (app != null && DreamClockService.timerEnabled(app)) add("timer")
            if (AvaPublishedEntities.hasNone() || (app != null && AvaLocalFeatures.hasAny(app))) {
                add("entities")
                add("set")
            }
        }
        return schema(actions)
    }

    internal fun schema(actions: List<String>): HaToolSet {
        val timerOn = "timer" in actions
        val clockOn = "alarm" in actions || "reminder" in actions
        val actionHelp = buildString {
            append("screen=on/off/lock, brightness=screen brightness, mic=mute/level, stop=end talk or silence whatever is ringing, read=this device's sensors plus the weather it already holds")
            if ("settings" in actions) append(", settings=read the current Ava settings path, or open a page and return that page as text (live names, live on/off, writable, visible labels). set a writable id to turn a switch")
            if ("mods" in actions) append(", mods=this device's mod store as text (catalog, installed, on/off, updates); omit target to list, target= a name to read one")
            if ("alarm" in actions) append(", alarm=a clock face on this device (明天12点), not a length; hour= and minute= to set, year/month/day or days= from today for a later day, 提醒我 with no task is still an alarm, omit to list, cancel=true closes")
            if ("reminder" in actions) append(", reminder=a clock face plus a task name in target; 提醒我 alone is an alarm, do not ask")
            if (timerOn) append(", timer=a length to count down (明天定时10秒钟 is 10 seconds), not a clock face; omit duration to snapshot remaining")
            append(", entities=find this device's own features by name, set=change one feature")
        }
        val toolHelp = buildString {
            append("Control THIS device only: its screen, mic, stop, sensors, and its own app features. Never ha_* for this device. Current screen brightness, screen on/off, mic, and battery are in ## Now — speak those; do not call read just for them.")
            if ("mods" in actions) append(" This device's mod store is action=mods; the host returns the catalog as text — no screenshot. on=true downloads or enables; on=false disables; press=true uninstalls. Zip import is the store page file picker.")
            if (clockOn) append(" A clock face (12点, 7:00), plus an optional date, is action=alarm when unnamed, or action=reminder only when they named a task (target=). 提醒我 alone is an alarm — set it, do not ask what to remind. hour= 0-23 and minute= 0-59. Optional year/month/day, or days= from today (1=tomorrow, 2=the day after). Omit the date for the next clock time today or tomorrow. A length (10 seconds, 5 minutes) is not a clock time. That is not a house alarm panel. At most 20. If the list is full, cancel one or tell the user; do not add again.")
            if (timerOn) append(" A spoken length is action=timer, even if the sentence also says 明天, 定时, 闹钟, or 提醒 (明天定时10秒钟 is duration_s=10). A clock face such as 明天12点 is not a countdown. Remaining is in ## Now while it runs — if they asked how much is left, speak that, or omit duration to snapshot remaining_s. Start with a duration; do not use entities or set. Do not ask which. While it runs, cancel=true; stop what=alarm silences whatever is ringing.")
            append(" A feature named under ## What this device owns: call set directly with its target. Use entities only when the spoken name is not listed there.")
        }
        return HaToolSet(
            listOf(
                ToolDef(
                    NAME,
                    toolHelp,
                    buildList {
                        add(
                            ToolParam(
                                "action",
                                ToolParamType.Enum(actions),
                                actionHelp,
                                required = true,
                            ),
                        )
                        add(
                            ToolParam(
                                "target",
                                ToolParamType.Str,
                                "set: the id from the prompt list, or the spoken name. entities: the spoken name to look up (omit to list all). settings: omit to read the current settings path (here/path); or a page name to open that page. mods: omit to list the catalog; or a spoken mod name.",
                                required = false,
                            ),
                        )
                        add(ToolParam("value", ToolParamType.Str, "set: option text, numeric string for a number, or text (empty string clears a text field)", required = false))
                        add(ToolParam("press", ToolParamType.Bool, "set: true presses a button; mods: true uninstalls", required = false))
                        add(ToolParam("on", ToolParamType.Bool, "screen or switch on/off; for a lock, true locks and false unlocks; for mods, true downloads or enables and false disables", required = false))
                        add(ToolParam("lock", ToolParamType.Bool, "true locks this device (sleep + keyguard)", required = false))
                        add(ToolParam("mute", ToolParamType.Bool, "mic mute", required = false))
                        add(ToolParam("level", ToolParamType.Int(0, 100), "brightness or mic percent", required = false))
                        add(ToolParam("step", ToolParamType.Int(-100, 100), "relative percent", required = false))
                        add(
                            ToolParam(
                                "what",
                                ToolParamType.Enum(listOf("talk", "alarm")),
                                "for stop: talk=end voice session, alarm=stop whatever is ringing on this device. Default talk",
                                required = false,
                            ),
                        )
                        if (clockOn) {
                            add(ToolParam("hour", ToolParamType.Int(0, 23), "alarm/reminder: clock hour", required = false))
                            add(ToolParam("minute", ToolParamType.Int(0, 59), "alarm/reminder: clock minute", required = false))
                            add(ToolParam("year", ToolParamType.Int(2024, 2100), "alarm/reminder: calendar year", required = false))
                            add(ToolParam("month", ToolParamType.Int(1, 12), "alarm/reminder: calendar month", required = false))
                            add(ToolParam("day", ToolParamType.Int(1, 31), "alarm/reminder: calendar day of month", required = false))
                            add(ToolParam("days", ToolParamType.Int(0, 366), "alarm/reminder: days from today; 0=today, 1=tomorrow, 2=the day after. Ignore when year/month/day is set", required = false))
                        }
                        if (timerOn) {
                            add(ToolParam("duration_s", ToolParamType.Int(1, 86_400), "timer: seconds to count down", required = false))
                            add(ToolParam("hours", ToolParamType.Int(0, 24), "timer: hours", required = false))
                            add(ToolParam("minutes", ToolParamType.Int(0, 1_440), "timer: minutes", required = false))
                            add(ToolParam("seconds", ToolParamType.Int(0, 59), "timer: extra seconds with hours/minutes", required = false))
                            add(ToolParam("pause", ToolParamType.Bool, "timer: true pauses, false resumes", required = false))
                            add(ToolParam("cancel", ToolParamType.Bool, "timer: true stops the countdown", required = false))
                        }
                    },
                    argumentCases = buildList {
                        add(ToolArgumentCase(action = "screen", fields = setOf("on", "lock"), exactlyOne = setOf("on", "lock")))
                        add(ToolArgumentCase(action = "brightness", fields = setOf("level", "step"), exactlyOne = setOf("level", "step")))
                        add(ToolArgumentCase(action = "mic", fields = setOf("mute", "level", "step"), exactlyOne = setOf("mute", "level", "step")))
                        add(ToolArgumentCase(action = "stop", fields = setOf("what")))
                        add(ToolArgumentCase(action = "read", fields = emptySet()))
                        if ("settings" in actions) add(ToolArgumentCase(action = "settings", fields = setOf("target")))
                        if ("mods" in actions) {
                            add(ToolArgumentCase(action = "mods", fields = setOf("target")))
                            add(ToolArgumentCase(action = "mods", fields = setOf("target", "on"), required = setOf("target", "on")))
                            add(ToolArgumentCase(action = "mods", fields = setOf("target", "press"), required = setOf("target", "press")))
                        }
                        add(ToolArgumentCase(action = "entities", fields = setOf("target")))
                        add(ToolArgumentCase(action = "set", fields = setOf("target", "on", "value", "press"), required = setOf("target"), exactlyOne = setOf("on", "value", "press")))
                        if ("alarm" in actions) {
                            add(ToolArgumentCase(action = "alarm", fields = emptySet()))
                            add(ToolArgumentCase(action = "alarm", fields = setOf("hour", "minute", "target", "year", "month", "day", "days"), required = setOf("hour")))
                            add(ToolArgumentCase(action = "alarm", fields = setOf("cancel"), required = setOf("cancel")))
                        }
                        if ("reminder" in actions) {
                            add(ToolArgumentCase(action = "reminder", fields = emptySet()))
                            add(ToolArgumentCase(action = "reminder", fields = setOf("hour", "minute", "target", "year", "month", "day", "days"), required = setOf("hour")))
                            add(ToolArgumentCase(action = "reminder", fields = setOf("cancel"), required = setOf("cancel")))
                        }
                        if (timerOn) {
                            add(ToolArgumentCase(action = "timer", fields = emptySet()))
                            add(
                                ToolArgumentCase(
                                    action = "timer",
                                    fields = setOf("duration_s", "hours", "minutes", "seconds"),
                                    atLeastOne = setOf("duration_s", "hours", "minutes", "seconds"),
                                ),
                            )
                            add(ToolArgumentCase(action = "timer", fields = setOf("pause"), required = setOf("pause")))
                            add(ToolArgumentCase(action = "timer", fields = setOf("cancel"), required = setOf("cancel")))
                        }
                    },
                ),
            ),
        )
    }

    suspend fun execute(call: AvaToolCallback.Call, app: Context): AvaToolCallback.Result {
        if (!ready()) return AvaToolCallback.fail("no_session", "Voice satellite is not running.")
        return when (call.arguments.optString("action").trim()) {
            "screen" -> screen(call.arguments, app)
            "brightness" -> brightness(call.arguments, app)
            "mic" -> mic(call.arguments)
            "stop" -> stop(call.arguments)
            "timer" -> timer(call.arguments, app)
            "alarm" -> clockAlert(ClockAlert.Kind.ALARM, call.arguments, app)
            "reminder" -> clockAlert(ClockAlert.Kind.REMINDER, call.arguments, app)
            "read" -> read(app)
            "settings" -> settings(call.arguments, app)
            "mods" -> mods(call.arguments, app)
            "entities" -> entities(call.arguments, app)
            "set" -> set(call.arguments, app)
            else -> AvaToolCallback.fail("invalid_request", "unknown action")
        }
    }

    private fun screen(args: JSONObject, app: Context): AvaToolCallback.Result {
        val lock = optBool(args, "lock")
        val on = optBool(args, "on")
        if (lock == true) {
            if (!ScreenControlUtils.lockScreen(app)) {
                return AvaToolCallback.fail("tool_error", "could not lock this screen")
            }
            return AvaToolCallback.ok(JSONObject().put("action", "screen").put("locked", true), status = "accepted")
        }
        if (on == null) return AvaToolCallback.fail("invalid_request", "screen requires on=true/false or lock=true; use read for status")
        if (!ScreenControlUtils.setScreenOn(app, on)) {
            return AvaToolCallback.fail("tool_error", if (on) "could not wake this screen" else "could not blank this screen")
        }
        return AvaToolCallback.ok(
            JSONObject()
                .put("action", "screen")
                .put("on", on)
                .put("brightness", readBrightnessPct(app)),
            status = "applied",
        )
    }

    private fun brightness(args: JSONObject, app: Context): AvaToolCallback.Result {
        if (!PlatformCapabilities.canWriteSettings(app)) {
            return AvaToolCallback.fail("tool_call_blocked", "WRITE_SETTINGS is off")
        }
        val current = readBrightnessPct(app)
        val next = nextPercent(args, current)
            ?: return AvaToolCallback.fail("invalid_request", "brightness requires level or step; use read for status")
        if (!writeBrightnessPct(app, next)) return AvaToolCallback.fail("tool_error", "brightness was not applied")
        return AvaToolCallback.ok(JSONObject().put("action", "brightness").put("level", readBrightnessPct(app)), status = "applied")
    }

    private fun mic(args: JSONObject): AvaToolCallback.Result {
        val mute = optBool(args, "mute")
        if (mute != null) {
            VoiceSatelliteService.setMicMute(mute)
            return AvaToolCallback.ok(
                JSONObject().put("action", "mic").put("muted", VoiceSatelliteService.isMicMuted()), status = "applied",
            )
        }
        val current = ((VoiceSatelliteService.micVolume() ?: 1f) / 2f * 100f).toInt().coerceIn(0, 100)
        val next = nextPercent(args, current)
            ?: return AvaToolCallback.fail("invalid_request", "mic requires mute, level, or step; use read for status")
        VoiceSatelliteService.setMicVolume(next / 100f * 2f)
        return AvaToolCallback.ok(
            JSONObject()
                .put("action", "mic")
                .put("muted", VoiceSatelliteService.isMicMuted())
                .put("level", next), status = "applied",
        )
    }

    private fun stop(args: JSONObject): AvaToolCallback.Result {
        val what = args.optString("what").trim().ifEmpty { "talk" }
        return when (what) {
            "alarm" -> {
                val clock = ClockAlertOverlayService.isRinging()
                val timer = VoiceSatelliteService.isTimerRingingNow()
                if (!clock && !timer) {
                    return AvaToolCallback.fail("not_found", "nothing is ringing")
                }
                if (clock) ClockAlertOverlayService.silence()
                if (timer) VoiceSatelliteService.getInstance()?.stopTimerSound()
                AvaToolCallback.ok(JSONObject().put("action", "stop").put("what", "alarm"), status = "accepted")
            }
            else -> {
                VoiceSatelliteService.stopVoiceSession()
                AvaToolCallback.ok(JSONObject().put("action", "stop").put("what", "talk"), status = "accepted")
            }
        }
    }

    private fun clockAlert(kind: ClockAlert.Kind, args: JSONObject, app: Context): AvaToolCallback.Result {
        val action = if (kind == ClockAlert.Kind.REMINDER) "reminder" else "alarm"
        if (optBool(args, "cancel") == true) {
            if (ClockAlertOverlayService.isRinging()) {
                ClockAlertOverlayService.silence()
                ClockAlertSensor.publish(app)
                return AvaToolCallback.ok(JSONObject().put("action", action).put("ringing", false), status = "accepted")
            }
            ClockAlertSensor.markVoiceHold()
            val spoken = args.optString("target").trim()
            val items = ClockAlertStore.list(app).filter { it.kind == kind && it.enabled }
            val hit = when {
                spoken.isEmpty() -> items.firstOrNull()
                else -> items.firstOrNull { it.id == spoken || it.label.contains(spoken, ignoreCase = true) }
            }
            if (hit == null) return AvaToolCallback.fail("not_found", "no $action")
            ClockAlertStore.remove(app, hit.id)
            ClockAlertSensor.publish(app)
            return AvaToolCallback.ok(JSONObject().put("action", action).put("id", hit.id).put("closed", true), status = "applied")
        }
        if (!args.has("hour")) {
            val arr = JSONArray()
            ClockAlertStore.list(app).filter { it.kind == kind && it.enabled }.forEach { item ->
                arr.put(clockAlertJson(item))
            }
            return AvaToolCallback.ok(JSONObject().put("action", action).put("items", arr), status = "observed")
        }
        val hour = args.optInt("hour").coerceIn(0, 23)
        val minute = args.optInt("minute").coerceIn(0, 59)
        val label = args.optString("target").trim()
        val storeKind = if (kind == ClockAlert.Kind.REMINDER && label.isEmpty()) ClockAlert.Kind.ALARM else kind
        val storeAction = if (storeKind == ClockAlert.Kind.REMINDER) "reminder" else "alarm"
        if (!PlatformCapabilities.canDrawOverlays(app)) {
            return AvaToolCallback.fail("tool_error", "the alarm overlay needs overlay permission")
        }
        if (ClockAlertStore.enabledCount(app) >= ClockAlertStore.MAX) {
            return AvaToolCallback.fail(
                "tool_error",
                "already ${ClockAlertStore.MAX}; remove one before adding another",
            )
        }
        ClockAlertSensor.markVoiceHold()
        val at = ClockAlert.atMillis(
            hour = hour,
            minute = minute,
            year = optInt(args, "year"),
            month = optInt(args, "month"),
            day = optInt(args, "day"),
            daysFromToday = optInt(args, "days"),
            fromMillis = System.currentTimeMillis(),
        )
        val item = ClockAlertStore.add(app, storeKind, hour, minute, label, atMillis = at)
            ?: return AvaToolCallback.fail(
                "tool_error",
                "already ${ClockAlertStore.MAX}; remove one before adding another",
            )
        ClockAlertSensor.publish(app)
        return AvaToolCallback.ok(clockAlertJson(item).put("action", storeAction), status = "applied")
    }

    private suspend fun timer(args: JSONObject, app: Context): AvaToolCallback.Result {
        if (!DreamClockService.timerEnabled(app)) {
            return AvaToolCallback.fail("not_found", "this device has no flip-clock timer")
        }
        if (optBool(args, "cancel") == true) {
            val snap = DreamClockService.timerSnapshot()
            if (!snap.running && !snap.paused && !snap.ringing && !VoiceSatelliteService.isTimerRingingNow()) {
                return AvaToolCallback.fail("not_found", "no countdown is running")
            }
            DreamClockService.voiceTimer(app, "cancel")
            VoiceSatelliteService.getInstance()?.stopTimerSound()
            return AvaToolCallback.ok(JSONObject().put("action", "timer").put("running", false), status = "accepted")
        }
        optBool(args, "pause")?.let { pause ->
            val snap = DreamClockService.timerSnapshot()
            if (!snap.running && !snap.paused) {
                return AvaToolCallback.fail("not_found", "no countdown is running")
            }
            DreamClockService.voiceTimer(app, if (pause) "pause" else "resume")
            return AvaToolCallback.ok(
                JSONObject()
                    .put("action", "timer")
                    .put("paused", pause)
                    .put("remaining_s", (snap.remainingMs / 1000L).toInt()),
                status = "accepted",
            )
        }
        val durationMs = timerDurationMs(args)
        if (durationMs == null) return peekTimer()
        if (!PlatformCapabilities.canDrawOverlays(app)) {
            return AvaToolCallback.fail("tool_error", "the flip clock needs overlay permission")
        }
        DreamClockService.voiceTimer(app, "start", durationMs)
        val seconds = (durationMs / 1000L).toInt()
        val body = JSONObject()
            .put("action", "timer")
            .put("running", true)
            .put("duration_s", seconds)
            .put("remaining_s", seconds)
        AvaOverlayReceipts.awaitAndAttach(body, "dream_clock_display", true)
        return AvaToolCallback.ok(body, status = "accepted")
    }

    private fun peekTimer(): AvaToolCallback.Result {
        val snap = DreamClockService.timerSnapshot()
        val ringing = snap.ringing || VoiceSatelliteService.isTimerRingingNow()
        val body = JSONObject().put("action", "timer")
        when {
            ringing -> body.put("ringing", true).put("remaining_s", 0)
            snap.running || snap.paused -> {
                body.put("running", snap.running)
                if (snap.paused) body.put("paused", true)
                body.put("remaining_s", (snap.remainingMs / 1000L).toInt().coerceAtLeast(0))
            }
            else -> body.put("running", false)
        }
        return AvaToolCallback.ok(body, status = "observed")
    }

    /**
     * Both of this device's lists as one: the published ESPHome entities, then
     * the overlays that act whether or not they were published. A published
     * entity wins a tie on id, because its setter writes the same field and
     * also mirrors the result back to Home Assistant.
     */
    private fun entities(args: JSONObject, app: Context): AvaToolCallback.Result {
        val spoken = args.optString("target").trim()
        val published = if (spoken.isEmpty()) {
            AvaPublishedEntities.catalog(noneOnly = true)
        } else {
            AvaPublishedEntities.resolve(spoken)
        }
        val taken = published.mapTo(HashSet()) { it.id }
        val local = if (spoken.isEmpty()) {
            AvaLocalFeatures.catalog(app)
        } else {
            AvaLocalFeatures.resolve(app, spoken)
        }.filterNot { it.id in taken }
        val count = published.size + local.size
        if (count == 0) {
            if (spoken.isNotEmpty()) {
                AvaSettingsGates.resolve(spoken)?.let { gate ->
                    return AvaToolCallback.ok(AvaSettingsGates.door(app, spoken, gate), status = "accepted")
                }
                AvaSettingsPrefs.resolve(spoken)?.let { pref ->
                    return AvaToolCallback.ok(
                        AvaSettingsPrefs.row(app, pref)
                            .put("action", "entities")
                            .put("hint", "Look at state for live on/off. set this id to turn it. No screenshot."),
                        status = "observed",
                    )
                }
            }
            return AvaToolCallback.fail(
                "not_found",
                if (spoken.isEmpty()) {
                    "this speaker has no switchable overlays."
                } else {
                    "not a feature of this Ava. Do not ha_search this speaker."
                },
            )
        }
        if (count == 1) {
            val item = published.firstOrNull()
            val extra = if (item != null) {
                AvaPublishedEntities.nextAction(item)
            } else {
                AvaLocalFeatures.nextAction(local.first())
            }
            return AvaToolCallback.ok(
                JSONObject()
                    .put("action", "entities")
                    .put("count", 1)
                    .put("entity", item?.toJson() ?: local.first().toJson())
                    .put("hint", extra.optString("hint"))
                    .put("next_action", extra.getJSONObject("next_action")),
            )
        }
        val list = JSONArray()
        for (item in published.take(LIST_CAP)) list.put(item.toJson())
        for (feature in local.take((LIST_CAP - published.size).coerceAtLeast(0))) {
            list.put(feature.toJson())
        }
        val out = JSONObject()
            .put("action", "entities")
            .put("count", count)
            .put("entities", list)
        if (count > list.length()) out.put("truncated", true)
        out.put(
            "hint",
            if (spoken.isEmpty()) {
                "Pick the entry the user meant and call set with its id."
            } else {
                "More than one entry matches. For open or close, use the one whose id ends in _display."
            },
        )
        return AvaToolCallback.ok(out)
    }

    private suspend fun set(args: JSONObject, app: Context): AvaToolCallback.Result {
        val spoken = args.optString("target").trim()
        if (spoken.isEmpty()) {
            return AvaToolCallback.fail("invalid_request", "target is required. Call entities first.")
        }
        if (AvaSettingsPoints.isBlocked(spoken)) {
            return AvaToolCallback.fail("tool_call_blocked", "that control would break this voice seat")
        }
        AvaSettingsGates.resolve(spoken)?.let { gate ->
            val on = optBool(args, "on")
            if (!AvaSettingsGates.isOn(app, gate) || AvaSettingsGates.isGateId(spoken) || on != null) {
                if (on == null && !AvaSettingsGates.wantsEnable(spoken) && !AvaSettingsGates.isGateId(spoken)) {
                    val door = AvaSettingsGates.door(app, spoken, gate)
                    return AvaToolCallback.ok(door, status = "accepted")
                }
                return AvaToolCallback.ok(AvaSettingsGates.set(app, gate, on != false), status = "accepted")
            }
        }
        val hits = AvaPublishedEntities.resolve(spoken)
        if (hits.size > 1) {
            val names = hits.joinToString { "${it.name} (${it.id})" }
            return AvaToolCallback.fail("ungrounded", "\"$spoken\" matches more than one: $names")
        }
        hits.firstOrNull()?.let { item ->
            if (AvaSettingsPoints.isBlocked(item.id)) {
                return AvaToolCallback.fail("tool_call_blocked", "that control would break this voice seat")
            }
            if (item.id.equals("tts_volume", ignoreCase = true)) {
                return AvaToolCallback.fail(
                    "invalid_request",
                    "That is Ava's speaking volume. Call ava_volume target=tts with level or step. Do not ava_self it.",
                )
            }
            return item.inject(
                on = optBool(args, "on"),
                value = if (args.has("value")) args.getString("value") else null,
                press = optBool(args, "press") == true,
            )
        }
        val local = AvaLocalFeatures.resolve(app, spoken)
        if (local.isEmpty()) {
            AvaSettingsPrefs.trySet(
                app = app,
                spoken = spoken,
                on = optBool(args, "on"),
                value = if (args.has("value")) args.getString("value") else null,
            )?.let { return it }
            AvaModStore.trySet(
                app = app,
                spoken = spoken,
                on = optBool(args, "on"),
                press = optBool(args, "press") == true,
            )?.let { return it }
            return AvaToolCallback.fail(
                "not_found",
                "not a feature of this Ava. Do not ha_search this speaker.",
            )
        }
        if (local.size > 1) {
            val names = local.joinToString { "${it.name} (${it.id})" }
            return AvaToolCallback.fail("ungrounded", "\"$spoken\" matches more than one: $names")
        }
        val feature = local.first()
        val on = optBool(args, "on") ?: return AvaToolCallback.fail("invalid_request", "this switch requires on=true or on=false")
        AvaLocalFeatures.set(app, feature.id, on)
            ?: return AvaToolCallback.fail("tool_error", "cannot set ${feature.name}")
        val body = feature.toJson().put("state", if (on) "on" else "off").put("on", on)
        if (AvaOverlayReceipts.isOverlayTarget(feature.id)) {
            AvaOverlayReceipts.awaitAndAttach(body, feature.id, on)
        }
        return AvaToolCallback.ok(body, status = "applied")
    }

    private suspend fun settings(args: JSONObject, app: Context): AvaToolCallback.Result {
        val spoken = args.optString("target").trim()
        val inSettings = AvaSettingsPoints.inSettings()
        val askingHere = AvaSettingsPoints.isHere(spoken.ifBlank { "" }) ||
            (spoken.isEmpty() && AvaSettingsPoints.isHere())
        if (askingHere) {
            val top = AvaUiHere.peek().optString("top")
            if (inSettings && top == AvaUiHere.AVA) {
                return AvaToolCallback.ok(AvaSettingsPoints.peek(app), status = "observed")
            }
            return AvaToolCallback.ok(AvaUiHere.extract(app, deep = true), status = "observed")
        }
        if (spoken.isEmpty() && inSettings) {
            return AvaToolCallback.ok(AvaSettingsPoints.peek(app), status = "observed")
        }
        val overlay = AvaSettingsPoints.overlayOpen()
        val decided = if (spoken.isNotEmpty()) {
            AvaSettingsPoints.intent(spoken, overlay)
        } else {
            AvaSettingsPoints.intent(overlayOpen = overlay)
        }
        when (decided) {
            AvaSettingsPoints.Intent.HA -> return AvaToolCallback.fail(
                "invalid_request",
                "That is Home Assistant settings. Use ava_page_act action=navigate path=/config/dashboard.",
                JSONObject().put(
                    "next_action",
                    JSONObject()
                        .put("tool", AvaPageTools.ACT)
                        .put("arguments", JSONObject().put("action", "navigate").put("path", "/config/dashboard")),
                ),
            )
            AvaSettingsPoints.Intent.OVERLAY -> return AvaToolCallback.fail(
                "invalid_request",
                "They meant the open overlay. Use ava_page_read, then tap idx=.",
                JSONObject().put(
                    "next_action",
                    JSONObject().put("tool", AvaPageTools.READ).put("arguments", JSONObject()),
                ),
            )
            AvaSettingsPoints.Intent.NONE -> {
                if (AvaSettingsPoints.refuseSettingsOpen(spoken)) {
                    return AvaToolCallback.fail(
                        "invalid_request",
                        "That is not Ava settings. A clock time or a reminder task is not a settings page.",
                    )
                }
            }
            else -> Unit
        }
        val body = AvaSettingsPoints.open(app, spoken)
        return AvaToolCallback.ok(body, status = "accepted")
    }

    private suspend fun mods(args: JSONObject, app: Context): AvaToolCallback.Result {
        return AvaModStore.execute(
            app = app,
            target = args.optString("target").trim(),
            on = optBool(args, "on"),
            press = optBool(args, "press"),
        )
    }

    private suspend fun read(app: Context): AvaToolCallback.Result {
        val out = JSONObject().put("action", "read")
        out.put("screen_on", ScreenControlUtils.panelOnState.value)
        out.put("muted", VoiceSatelliteService.isMicMuted())
        VoiceSatelliteService.micVolume()?.let {
            out.put("mic_level", (it / 2f * 100f).toInt().coerceIn(0, 100))
        }
        out.put("brightness", readBrightnessPct(app))
        out.put("battery", batteryPct(app))
        readLightLux(app)?.let { out.put("light_lux", it.toInt()) }
        if (DreamClockService.timerEnabled(app)) {
            val snap = DreamClockService.timerSnapshot()
            if (snap.ringing || VoiceSatelliteService.isTimerRingingNow()) {
                out.put("timer", "ringing")
            } else if (snap.running || snap.paused) {
                out.put("timer_remaining_s", (snap.remainingMs / 1000L).toInt())
                if (snap.paused) out.put("timer_paused", true)
            }
        }
        WeatherService.getCachedWeather()?.let { out.put("weather", weatherJson(it)) }
        return AvaToolCallback.ok(out)
    }

    /**
     * The weather this device is already holding. Whatever draws the weather
     * overlay or the simple clock subscribes to the house weather entity and
     * parks each update here, so answering "how is it outside" costs nothing:
     * no house read, no round trip, and no web search. Only present once
     * something has subscribed, which is why the key is left out when empty
     * rather than reported as zeroes.
     */
    internal fun weatherJson(data: WeatherData): JSONObject {
        val out = JSONObject()
            .put("condition", data.condition)
            .put("temperature", data.temperature)
            .put("unit", data.temperatureUnit)
        if (data.humidity > 0) out.put("humidity_pct", data.humidity)
        if (data.windSpeed > 0f) {
            out.put("wind", "${data.windDirection} ${data.windSpeed}${data.windSpeedUnit}".trim())
        }
        if (data.visibility > 0f) {
            putWeatherNumber(out, "visibility", data.visibility)
            if (data.visibilityUnit.isNotBlank()) out.put("visibility_unit", data.visibilityUnit)
        }
        if (data.pressure > 0f) {
            putWeatherNumber(out, "pressure", data.pressure)
            if (data.pressureUnit.isNotBlank()) out.put("pressure_unit", data.pressureUnit)
        }
        if (data.aqi > 0) out.put("aqi", data.aqi)
        if (data.pm25 > 0) out.put("pm25", data.pm25)
        if (data.city.isNotBlank()) out.put("place", data.city)
        return out
    }

    private fun putWeatherNumber(into: JSONObject, key: String, value: Float) {
        if (kotlin.math.abs(value - value.toLong()) < 1e-3f) into.put(key, value.toLong())
        else into.put(key, value.toDouble())
    }

    internal fun timerDurationMs(args: JSONObject): Long? {
        val seconds = optInt(args, "duration_s")
        val hours = optInt(args, "hours") ?: 0
        val minutes = optInt(args, "minutes") ?: 0
        val extra = optInt(args, "seconds") ?: 0
        val total = when {
            seconds != null -> seconds
            hours > 0 || minutes > 0 || args.has("seconds") -> hours * 3600 + minutes * 60 + extra
            else -> return null
        }
        if (total <= 0) return null
        return total.coerceAtMost(86_400) * 1000L
    }

    private fun nextPercent(args: JSONObject, current: Int): Int? {
        val level = optInt(args, "level")
        val step = optInt(args, "step")
        if (level == null && step == null) return null
        return (level ?: (current + (step ?: 0))).coerceIn(0, 100)
    }

    private fun readBrightnessPctOrNull(app: Context): Int? = try {
        val raw = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        (raw.coerceIn(0, 255) * 100 / 255f + 0.5f).toInt().coerceIn(0, 100)
    } catch (_: Exception) {
        null
    }

    private fun readBrightnessPct(app: Context): Int = readBrightnessPctOrNull(app) ?: 50

    private fun batteryPctOrNull(app: Context): Int? {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return null
        return pct.takeIf { it in 0..100 }
    }

    private fun writeBrightnessPct(app: Context, percent: Int): Boolean {
        val value = (percent.coerceIn(0, 100) * 255 / 100f + 0.5f).toInt().coerceIn(1, 255)
        return Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }

    private fun batteryPct(app: Context): Int = batteryPctOrNull(app) ?: 0

    private suspend fun readLightLux(app: Context): Float? {
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return null
        return withTimeoutOrNull(800) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values.firstOrNull())
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }

    private const val LIST_CAP = 24

    private fun clockAlertJson(item: ClockAlert): JSONObject {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = item.nextAtMillis }
        return JSONObject()
            .put("id", item.id)
            .put("hour", item.hour)
            .put("minute", item.minute)
            .put("year", cal.get(java.util.Calendar.YEAR))
            .put("month", cal.get(java.util.Calendar.MONTH) + 1)
            .put("day", cal.get(java.util.Calendar.DAY_OF_MONTH))
            .put("at", item.nextAtMillis)
            .put("label", item.label)
    }

    private fun optBool(args: JSONObject, key: String): Boolean? {
        if (!args.has(key) || args.opt(key) == JSONObject.NULL) return null
        return when (val raw = args.opt(key)) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> null
        }
    }

    private fun optInt(args: JSONObject, key: String): Int? {
        if (!args.has(key) || args.opt(key) == JSONObject.NULL) return null
        return when (val raw = args.opt(key)) {
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }
}
