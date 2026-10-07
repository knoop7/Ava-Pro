package com.example.ava.localllm.remote

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.example.ava.homeassistant.HaManager
import com.example.ava.localllm.HaSpokenHear
import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolArgumentCase
import com.example.ava.localllm.ToolDef
import com.example.ava.localllm.ToolParam
import com.example.ava.localllm.ToolParamType
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.voice.AvaVoiceDevice
import com.example.ava.voice.AvaVoiceDeviceType
import com.example.ava.voice.AvaVoiceDiscovery
import com.example.ava.voice.AvaVoiceHaController
import com.example.ava.voice.AvaVoiceNetwork
import com.example.ava.voice.AvaVoiceTtsInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ava-owned LAN voice. peers / call / hang_up / redial stay on the local
 * voice stack. message injects TTS into the same clip path — it does not
 * open the microphone.
 *
 * Master switch off or no overlay permission → empty schema. The live roster
 * is in the prompt's `## Now` section; peers only refreshes it and adds ids.
 */
object AvaVoiceTools {

    const val NAME = "ava_voice"
    const val PREFIX = "ava_voice"

    private const val DISCOVERY_WAIT_MS = 2_000L
    private const val SEND_WAIT_MS = 20_000L
    private val BROADCAST_EXACT = setOf(
        "all", "everyone", "everybody", "anyone",
        "allavas", "alldevices", "allnearby",
        "所有", "全部", "大家", "每人", "广播",
    )
    private val BROADCAST_STEMS = listOf(
        "穷发", "群发",
        "全屋", "全家", "整屋", "全房", "全宅",
        "所有人", "所有设备", "所有的人", "所有的设备",
        "全家设备", "全屋设备", "全部设备",
        "每个房间", "各房间", "所有房间",
        "wholehouse", "entirehouse", "allrooms",
        "everyroom", "everydevice", "wholefamily", "allfamily",
    )

    fun ready(app: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) return false
        return PlayerSettingsStore(app.playerSettingsStore).getCached().enableVoiceMessageOverlay
    }

    fun surface(app: Context, ready: Boolean = ready(app)): HaToolSet {
        if (!ready) return HaToolSet(emptyList())
        val settings = PlayerSettingsStore(app.playerSettingsStore).getCached()
        val actions = ArrayList<String>().apply {
            add("peers")
            if (settings.enableVoiceOverlayCall) {
                add("call")
                add("video_call")
            }
            if (settings.enableVoiceOverlayIntercom) add("message")
            add("hang_up")
            add("redial")
        }
        return HaToolSet(
            listOf(
                ToolDef(
                    NAME,
                    "Reach a nearby Ava over the LAN from this speaker. Never ha_* for this. peers lists the reachable names. call / video_call need one target. message needs a target and text you write — the spoken line the other room hears, not a splice of the user's command. hang_up ends the session; redial calls the last target again.",
                    listOf(
                        ToolParam(
                            "action",
                            ToolParamType.Enum(actions),
                            "peers=list nearby Avas, call=live audio, video_call=live audio + camera, message=speak a line you write on the other Ava, hang_up=end the session, redial=call the last target again",
                            required = true,
                        ),
                        ToolParam(
                            "target",
                            ToolParamType.Str,
                            "The user's word for the other Ava, as plain text: a name, brand, or model word that fits one roster line (e.g. the value after model=, never the literal text \"model=\"). If it fits exactly one line, pass it and act; do not ask first. Required for call / video_call / message. \"all\" only when they meant every nearby Ava; a named device is never all.",
                            required = false,
                        ),
                        ToolParam(
                            "text",
                            ToolParamType.Str,
                            "message only. The spoken line YOU write for the other room. Remove the leave-a-message wrapper and the target name from what the user said; keep any words they dictated; if they gave only the intent, compose a short line. Never pass the user's whole utterance. Required for message.",
                            required = false,
                        ),
                    ),
                    argumentCases = listOf(
                        ToolArgumentCase(action = "peers", fields = emptySet()),
                        ToolArgumentCase(action = "call", fields = setOf("target"), required = setOf("target")),
                        ToolArgumentCase(action = "video_call", fields = setOf("target"), required = setOf("target")),
                        ToolArgumentCase(action = "message", fields = setOf("target", "text"), required = setOf("target", "text")),
                        ToolArgumentCase(action = "hang_up", fields = emptySet()),
                        ToolArgumentCase(action = "redial", fields = emptySet()),
                    ),
                ),
            ),
        )
    }

    suspend fun execute(call: AvaToolCallback.Call, app: Context): AvaToolCallback.Result {
        if (!ready(app)) return AvaToolCallback.fail("no_session", "Voice messaging is off.")
        if (!ensureStack()) {
            return AvaToolCallback.fail("no_session", "Voice satellite is not running.")
        }
        val args = call.arguments
        val settings = PlayerSettingsStore(app.playerSettingsStore).getCached()
        return when (args.optString("action").trim()) {
            "peers" -> peers(args)
            "call" -> start(app, args, "call", settings.enableVoiceOverlayCall)
            "video_call" -> start(app, args, "video_call", settings.enableVoiceOverlayCall)
            "message" -> leaveMessage(app, args, settings.enableVoiceOverlayIntercom, settings.voiceMessageDelayMinutes)
            "hang_up" -> hangUp(app)
            "redial" -> redial(app)
            else -> AvaToolCallback.fail("invalid_request", "unknown action")
        }
    }

    private suspend fun peers(args: JSONObject): AvaToolCallback.Result {
        val devices = awaitPeers()
        val list = JSONArray()
        for (device in devices) list.put(peerJson(device))
        val out = JSONObject()
            .put("action", "peers")
            .put("this", thisJson())
            .put("count", devices.size)
            .put("peers", list)
        if (devices.isEmpty()) {
            out.put("hint", "No nearby Ava right now. Ask the user which device, or try again.")
        } else {
            out.put("hint", "Use name or id as target. type and model are match keys only — speak the name, never type or model. Do not describe what a peer runs on, and do not decide what it can do.")
        }
        return AvaToolCallback.ok(out)
    }

    private suspend fun leaveMessage(
        app: Context,
        args: JSONObject,
        allowed: Boolean,
        delayMinutes: Int,
    ): AvaToolCallback.Result {
        if (!allowed) return AvaToolCallback.fail("tool_call_blocked", "message is off")
        val spoken = unwrapTarget(args.optString("target").trim())
        if (spoken.isEmpty()) {
            return AvaToolCallback.fail(
                "ungrounded",
                "Ask which Ava by name.",
            )
        }
        val text = args.optString("text").trim()
        if (text.isEmpty()) {
            return AvaToolCallback.fail("invalid_request", "text is required. Write the spoken line the other room should hear.")
        }
        if (HaManager.get() == null) {
            return AvaToolCallback.fail("no_session", "Home Assistant is needed to speak the message.")
        }
        val peers = awaitPeers()
        val match = ground(spoken, peers)
        val broadcast = match.device == null && isBroadcast(spoken)
        val targets = when {
            match.device != null -> listOf(match.device)
            broadcast -> {
                if (peers.isEmpty()) {
                    return AvaToolCallback.fail("not_found", "no nearby Ava right now")
                }
                peers
            }
            else -> return AvaToolCallback.fail(match.error, match.message)
        }
        val outcome = withTimeoutOrNull(SEND_WAIT_MS) {
            AvaVoiceTtsInject.send(app, text, targets, delayMinutes)
        } ?: return AvaToolCallback.fail(
            "tool_error",
            "the message timed out before it was sent. Tell the user it did not go out.",
        )
        if (!outcome.ok || outcome.reached.isEmpty()) {
            return AvaToolCallback.fail(
                "tool_error",
                outcome.error ?: "the clip did not leave this device. Tell the user it was not sent.",
            )
        }
        val list = JSONArray()
        for (device in outcome.reached) list.put(peerJson(device))
        return AvaToolCallback.ok(
            JSONObject()
                .put("action", "message")
                .put("mode", "message")
                .put("text", text)
                .put("sent", true)
                .put("broadcast", broadcast)
                .put("count", outcome.reached.size)
                .put("targets", list)
                .put("bytes", outcome.bytes)
                .put("duration_ms", outcome.durationMs)
                .put("hint", "Clip left this device. Not proof they played it."),
            status = "applied",
        )
    }

    private suspend fun start(
        app: Context,
        args: JSONObject,
        mode: String,
        allowed: Boolean,
    ): AvaToolCallback.Result {
        if (!allowed) return AvaToolCallback.fail("tool_call_blocked", "$mode is off")
        val spoken = unwrapTarget(args.optString("target").trim())
        if (spoken.isEmpty()) {
            return AvaToolCallback.fail("invalid_request", "target is required. Call peers first if you do not know the device.")
        }
        val match = ground(spoken, awaitPeers())
        val device = match.device
            ?: return AvaToolCallback.fail(match.error, match.message)
        val payload = linkedMapOf<String, Any>(
            "action" to "start",
            "mode" to mode,
            "targets" to listOf(device.id),
        )
        AvaVoiceHaController.execute(app, payload)
        if (!AvaVoiceHaController.hasActiveSession()) {
            return AvaToolCallback.fail("tool_error", "could not start $mode with ${device.name}")
        }
        return AvaToolCallback.ok(
            JSONObject()
                .put("action", mode)
                .put("mode", mode)
                .put("target", peerJson(device)).put("connection_confirmed", false), status = "accepted",
        )
    }

    private suspend fun hangUp(app: Context): AvaToolCallback.Result {
        val wasActive = AvaVoiceHaController.hasActiveSession()
        AvaVoiceHaController.hangUp(app)
        return AvaToolCallback.ok(
            JSONObject().put("action", "hang_up").put("was_active", wasActive), status = "accepted",
        )
    }

    private suspend fun redial(app: Context): AvaToolCallback.Result {
        AvaVoiceHaController.execute(app, mapOf("action" to "redial"))
        val session = AvaVoiceHaController.uiSession.value
            ?: return AvaToolCallback.fail("not_found", "no previous call to redial")
        val targets = JSONArray()
        for (device in session.targets) targets.put(peerJson(device))
        return AvaToolCallback.ok(
            JSONObject()
                .put("action", "redial")
                .put("mode", session.mode)
                .put("targets", targets).put("connection_confirmed", false), status = "accepted",
        )
    }

    private suspend fun ensureStack(): Boolean {
        if (AvaVoiceNetwork.isFeatureEnabled()) return true
        VoiceSatelliteService.requestVoiceMessageSync()
        delay(400)
        return AvaVoiceNetwork.isFeatureEnabled()
    }

    private suspend fun awaitPeers(): List<AvaVoiceDevice> {
        var list = livePeers()
        if (list.isNotEmpty()) return list
        AvaVoiceDiscovery.pokeLanDiscovery()
        delay(400)
        list = livePeers()
        if (list.isNotEmpty()) return list
        delay(DISCOVERY_WAIT_MS)
        return livePeers()
    }

    /**
     * The nearby Avas as the prompt names them, without waiting on discovery.
     * Empty until a beacon lands, which is the honest answer at that moment.
     */
    fun roster(): List<AvaVoiceDevice> = livePeers()

    /** This Ava's spoken LAN name. Type and model stay off the prompt. */
    fun selfLabel(): String = AvaVoiceDiscovery.localName().trim()

    private fun livePeers(): List<AvaVoiceDevice> {
        val self = AvaVoiceDiscovery.localId()
        return AvaVoiceDiscovery.devices.value
            .filter { it.offersVoiceMessaging && it.id != self }
            .sortedBy { it.name.lowercase() }
    }

    private data class Grounded(
        val device: AvaVoiceDevice? = null,
        val error: String = "not_found",
        val message: String = "",
    )

    /**
     * Name first, then model. Both accept a close spoken miss. A type word
     * is last and only when it still names one peer.
     */
    private fun ground(spoken: String, peers: List<AvaVoiceDevice>): Grounded {
        if (peers.isEmpty()) {
            return Grounded(error = "not_found", message = "no nearby Ava right now")
        }
        val needle = normalizeName(spoken)
        if (needle.isEmpty()) {
            return Grounded(error = "invalid_request", message = "target is empty")
        }
        val exactName = peers.filter { device ->
            normalizeName(device.name) == needle || device.id.equals(spoken, ignoreCase = true)
        }
        pickOne(exactName, spoken)?.let { return it }

        val exactModel = peers.filter { device -> hearToken(device.model, needle) && normalizeName(device.model) == needle }
        pickOne(exactModel, spoken)?.let { return it }

        val nameContains = peers.filter { device ->
            val name = normalizeName(device.name)
            name.contains(needle) || (name.isNotEmpty() && needle.contains(name))
        }
        pickOne(nameContains, spoken)?.let { return it }

        val modelContains = peers.filter { device ->
            val reported = normalizeName(device.model)
            reported.isNotEmpty() && (reported.contains(needle) || needle.contains(reported))
        }
        pickOne(modelContains, spoken)?.let { return it }

        val labelContains = peers.filter { device ->
            normalizeName(device.identityLabel()).contains(needle)
        }
        pickOne(labelContains, spoken)?.let { return it }

        val nameNear = peers.filter { device ->
            HaSpokenHear.close(normalizeName(device.name), needle, loose = true)
        }
        pickOne(nameNear, spoken)?.let { return it }

        val modelNear = peers.filter { device ->
            val reported = normalizeName(device.model)
            reported.isNotEmpty() && HaSpokenHear.close(reported, needle, loose = true)
        }
        pickOne(modelNear, spoken)?.let { return it }

        parseTypeWord(spoken)?.let { kind ->
            pickOne(peers.filter { it.type == kind }, spoken)?.let { return it }
        }
        return Grounded(
            error = "ungrounded",
            message = "no unique Ava named \"$spoken\". Ask which one by name or model.",
        )
    }

    private fun hearToken(raw: String, needle: String): Boolean {
        val token = normalizeName(raw)
        return token.isNotEmpty() && token == needle
    }

    /** Host-side only: they meant everyone — not a device name. */
    private fun isBroadcast(spoken: String): Boolean {
        val key = normalizeName(spoken)
        if (key.isEmpty()) return false
        if (key in BROADCAST_EXACT) return true
        return BROADCAST_STEMS.any { stem ->
            key == stem || (stem.length >= 2 && key.contains(stem))
        }
    }

    /** Host-side only: strip a call wrapper so the spoken name still grounds. */
    private fun unwrapTarget(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        for (marker in listOf("打电话给", "打给")) {
            val at = s.indexOf(marker)
            if (at >= 0) s = s.substring(at + marker.length).trim()
        }
        if (s.startsWith("给") && s.endsWith("打电话")) {
            s = s.removePrefix("给").removeSuffix("打电话").trim()
        }
        val lower = s.lowercase()
        for (marker in listOf("call ", "phone ", "ring ")) {
            val at = lower.indexOf(marker)
            if (at >= 0) s = s.substring(at + marker.length).trim()
        }
        for (suffix in listOf("打电话", "的电话")) {
            if (s.endsWith(suffix) && s.length > suffix.length) {
                s = s.dropLast(suffix.length).trim()
            }
        }
        return stripIdentityCaption(s.ifBlank { raw.trim() })
    }

    /** Drop a leftover name (type, model) caption if the model still copied one. */
    private fun stripIdentityCaption(raw: String): String {
        var s = raw.trim()
        if (s.endsWith("）")) {
            val at = s.lastIndexOf("（")
            if (at > 0) s = s.substring(0, at).trim()
        }
        if (s.endsWith(")")) {
            val at = s.lastIndexOf(" (")
            if (at > 0) s = s.substring(0, at).trim()
        }
        return s
    }

    private fun normalizeName(raw: String): String =
        raw.trim().lowercase().replace(Regex("[\\s_-]+"), "")

    private fun pickOne(hits: List<AvaVoiceDevice>, spoken: String): Grounded? {
        if (hits.isEmpty()) return null
        if (hits.size == 1) return Grounded(device = hits.first())
        val names = hits.joinToString { it.name }
        return Grounded(
            error = "ungrounded",
            message = "\"$spoken\" matches more than one Ava: $names. Ask which one by name.",
        )
    }

    private fun parseTypeWord(raw: String): AvaVoiceDeviceType? {
        val key = raw.trim().lowercase()
        return when (key) {
            "phone", "手机", "电话" -> AvaVoiceDeviceType.PHONE
            "tablet", "平板", "平板电脑" -> AvaVoiceDeviceType.TABLET
            "tv", "电视", "电视机" -> AvaVoiceDeviceType.TV
            "speaker", "音箱", "音响", "喇叭" -> AvaVoiceDeviceType.SPEAKER
            "unknown" -> AvaVoiceDeviceType.UNKNOWN
            else -> AvaVoiceDeviceType.entries.firstOrNull { it.wireValue == key }
        }
    }

    /** Spoken [name] for targeting; [type] and [model] are match keys, not speech. */
    private fun peerJson(device: AvaVoiceDevice): JSONObject {
        val out = JSONObject()
            .put("name", device.name)
            .put("id", device.id)
            .put("type", device.type.wireValue)
        val reported = device.model.trim()
        if (reported.isNotEmpty()) out.put("model", reported)
        return out
    }

    private fun thisJson(): JSONObject =
        JSONObject()
            .put("name", AvaVoiceDiscovery.localName())
            .put("id", AvaVoiceDiscovery.localId())
}
