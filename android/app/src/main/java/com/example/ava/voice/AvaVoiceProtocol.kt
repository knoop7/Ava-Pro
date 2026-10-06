package com.example.ava.voice

/**
 * Ava LAN voice protocol.
 *
 * - Control/signaling on [PORT] (UTF-8 text, same socket as discovery)
 * - PCM media on [AUDIO_PORT] (binary [AvaVoicePacket])
 *
 * Designed for async voice messages and live LAN calls without pulling in SIP/PJSIP.
 */
object AvaVoiceProtocol {
    const val PORT = 19848
    const val AUDIO_PORT = 19849
    /** Chunked JPEG video during live calls (separate from audio). */
    const val VIDEO_PORT = 19850
    const val VIDEO_START_PREFIX = "AVA_VOICE_VIDEO_START"
    const val VIDEO_STOP_PREFIX = "AVA_VOICE_VIDEO_STOP"
    const val BEACON_PREFIX = "AVA_VOICE_BEACON"
    const val QUERY_PREFIX = "AVA_VOICE_QUERY"
    const val MESSAGE_PREFIX = "AVA_VOICE_MSG"
    const val BEGIN_PREFIX = "AVA_VOICE_BEGIN"
    const val END_PREFIX = "AVA_VOICE_END"
    const val HANGUP_PREFIX = "AVA_VOICE_HANGUP"
    const val ANSWER_PREFIX = "AVA_VOICE_ANSWER"
    const val DECLINE_PREFIX = "AVA_VOICE_DECLINE"
    const val NO_ANSWER_PREFIX = "AVA_VOICE_NO_ANSWER"

    /** Max ring duration before callee reports no-answer to caller. */
    const val CALL_RING_TIMEOUT_MS = 60_000L

    const val BEACON_INTERVAL_MS = 5_000L
    const val DEVICE_STALE_MS = 30_000L
    /** Max length for one-shot voice messages / intercom PTT holds. */
    const val MAX_VOICE_MS = 60_000L

    /** Live calls are not capped to [MAX_VOICE_MS]; this is only for logging/telemetry guards. */
    const val MAX_CALL_MS = 4 * 60 * 60_000L
}

enum class AvaVoiceDeviceType(val wireValue: String) {
    PHONE("phone"),
    TABLET("tablet"),
    SPEAKER("speaker"),
    TV("tv"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String): AvaVoiceDeviceType =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

data class AvaVoiceDevice(
    val id: String,
    val name: String,
    val host: String,
    val type: AvaVoiceDeviceType,
    val lastSeenMs: Long = System.currentTimeMillis(),
    /**
     * Optional cluster HTTP port advertised on the existing beacon wire format.
     * `null` = peer did not advertise (older Ava). `0` = Ava identity present, cluster off.
     * `>0` = cluster **agent** port (API; normally [com.example.ava.fleet.FleetManager.DEFAULT_PORT]).
     */
    val clusterPort: Int? = null,
    /**
     * Whether the peer is serving the website SPA.
     * `null` = not advertised (legacy peers with [clusterPort]>0 ran full console).
     */
    val webConsole: Boolean? = null,
    /**
     * New Ava builds advertise peer sync-offset control (`syncOffsetPeer=1` on beacon).
     * Independent of voice-message / fleet switches — presence UDP only.
     */
    val syncOffsetPeer: Boolean = false,
    /**
     * Whether LAN voice messaging / calls are enabled on this peer
     * (`voiceMessaging=0|1` on beacon).
     * `null` = not advertised (legacy Ava) — treat as reachable for compatibility.
     * `false` = master switch off — hide from voice pickers (presence may still run).
     */
    val voiceMessaging: Boolean? = null,
    /**
     * Bayesian occupancy verdict (`occupied=0|1` on beacon). Feeds peer rooms'
     * occupancy *prior*. `null` = peer does not run the occupancy sensor — it
     * cannot testify about the house either way.
     */
    val occupied: Boolean? = null,
    /**
     * One-shot settings-clone TCP port (`clonePort=<port>` on beacon). Present only
     * while the peer's "Send this device's configuration" screen is open; absent
     * (`null`) otherwise and on older builds.
     */
    val clonePort: Int? = null,
    /**
     * Whole seconds left on the sender's clone window (`cloneRemain=<sec>`), snapshotted
     * at [lastSeenMs]. `null` = older sender that only advertised [clonePort].
     */
    val cloneRemainSec: Int? = null,
    /**
     * Advertised [android.os.Build.MODEL] (`model=` on beacon). Blank on older
     * peers. Host / model match key — targeting still uses [name]. Not spoken.
     */
    val model: String = "",
) {
    val stableKey: String get() = id

    /**
     * Host-side match blob: name plus kind and model. Clone UI may show it.
     * Not for speech or the AI roster — use [rosterLine].
     */
    fun identityLabel(): String {
        val kind = type.wireValue.takeIf { type != AvaVoiceDeviceType.UNKNOWN }
        val reported = model.trim()
        val bits = ArrayList<String>(2)
        if (!kind.isNullOrBlank()) bits.add(kind)
        if (reported.isNotBlank() && !reported.equals(name, ignoreCase = true)) bits.add(reported)
        return if (bits.isEmpty()) name else "$name（${bits.joinToString("，")}）"
    }

    /** Additive keys for the model. Speak [name]; type and model are match-only. */
    fun rosterLine(): String {
        val parts = ArrayList<String>(3)
        val spoken = name.trim()
        if (spoken.isNotEmpty()) parts.add(spoken)
        val kind = type.wireValue.takeIf { type != AvaVoiceDeviceType.UNKNOWN }
        if (!kind.isNullOrBlank()) parts.add("type=$kind")
        val reported = model.trim()
        if (reported.isNotBlank() && !reported.equals(name, ignoreCase = true)) {
            parts.add("model=$reported")
        }
        return parts.joinToString("  ")
    }

    /** Every valid beacon is an Ava LAN identity (`AVA_VOICE_BEACON`). */
    val identity: String get() = "ava"

    /**
     * Suitable for voice-message / call device lists.
     * Legacy peers (no flag) stay visible; explicit `voiceMessaging=0` is hidden.
     */
    val offersVoiceMessaging: Boolean get() = voiceMessaging != false

    /**
     * Milliseconds left on this peer's clone window, interpolating from the last
     * advertised [cloneRemainSec] and [lastSeenMs]. `null` when the peer did not
     * advertise a remain field.
     */
    fun cloneRemainingMs(nowWallMs: Long = System.currentTimeMillis()): Long? {
        val advertisedSec = cloneRemainSec ?: return null
        return (advertisedSec * 1000L - (nowWallMs - lastSeenMs)).coerceAtLeast(0L)
    }
}

/**
 * Wire format (backward compatible):
 * `AVA_VOICE_BEACON|{id}|{name}|{type}|{hostIp}`
 * Optional trailing fields (ignored by older parsers that only read parts[0..4]):
 * `|clusterPort={0|8888}|webConsole={0|1}|syncOffsetPeer={0|1}|voiceMessaging={0|1}[|occupied={0|1}][|clonePort={port}[|cloneRemain={sec}]][|model={Build.MODEL}]`
 */
internal fun buildBeacon(
    deviceId: String,
    deviceName: String,
    deviceType: AvaVoiceDeviceType,
    hostIp: String,
    clusterPort: Int = 0,
    webConsole: Boolean = false,
    syncOffsetPeer: Boolean = true,
    voiceMessaging: Boolean = false,
    occupied: Boolean? = null,
    clonePort: Int = 0,
    cloneRemainSec: Int = 0,
    model: String = "",
): String = buildString {
    append(
        listOf(
            AvaVoiceProtocol.BEACON_PREFIX,
            deviceId,
            deviceName.replace('|', ' '),
            deviceType.wireValue,
            hostIp,
        ).joinToString("|"),
    )
    // Always append so fleet can mark Ava identity + cluster on/off without a new UDP protocol.
    append("|clusterPort=").append(clusterPort.coerceIn(0, 65535))
    append("|webConsole=").append(if (webConsole) 1 else 0)
    // New builds: Ava↔Ava sync-offset peer control (ignored by older parsers).
    append("|syncOffsetPeer=").append(if (syncOffsetPeer) 1 else 0)
    // Voice overlay master ([PlayerSettings.enableVoiceMessageOverlay]); presence may
    // still run when this is 0 so sync-offset / fleet keep working.
    append("|voiceMessaging=").append(if (voiceMessaging) 1 else 0)
    // Occupancy verdict rides the identity beacon (same rule: no second UDP protocol).
    // Absent entirely when the Bayesian occupancy sensor is off.
    occupied?.let { append("|occupied=").append(if (it) 1 else 0) }
    // Settings-clone offer rides the identity beacon too. Absent unless the
    // one-shot clone window is currently open, so receivers can filter on presence.
    if (clonePort in 1..65535) {
        append("|clonePort=").append(clonePort)
        if (cloneRemainSec > 0) append("|cloneRemain=").append(cloneRemainSec)
    }
    val advertisedModel = model.trim().replace('|', ' ').replace('=', ' ')
    if (advertisedModel.isNotEmpty()) append("|model=").append(advertisedModel)
}

internal fun buildVoiceBegin(
    sessionId: Int,
    fromDeviceId: String,
    fromName: String,
    toDeviceId: String,
    sampleRate: Int = AvaVoiceAudioConfig.SAMPLE_RATE,
    mode: AvaVoiceMode = AvaVoiceMode.Intercom,
    delayMinutes: Int = 0
): String = listOf(
    AvaVoiceProtocol.BEGIN_PREFIX,
    sessionId.toString(),
    fromDeviceId,
    toDeviceId,
    fromName.replace('|', ' '),
    sampleRate.toString(),
    mode.wireValue,
    delayMinutes.coerceIn(0, 1440).toString()
).joinToString("|")

internal fun buildVoiceEnd(
    sessionId: Int,
    fromDeviceId: String,
    toDeviceId: String,
    totalBytes: Long,
    durationMs: Long
): String = listOf(
    AvaVoiceProtocol.END_PREFIX,
    sessionId.toString(),
    fromDeviceId,
    toDeviceId,
    totalBytes.toString(),
    durationMs.toString()
).joinToString("|")

/**
 * Wire format: `AVA_VOICE_HANGUP|from|to[|sessionId1,sessionId2,…]`
 *
 * The trailing session-ID field is optional for backward compatibility with older
 * peers that only emit `from|to`. When present, the receiver should only tear down
 * sessions whose IDs appear in the list, avoiding cross-session teardown during
 * rapid hang-up/start churn.
 */
internal fun buildVoiceHangup(
    fromDeviceId: String,
    toDeviceId: String,
    sessionIds: List<Int> = emptyList()
): String = buildString {
    append(AvaVoiceProtocol.HANGUP_PREFIX)
    append('|').append(fromDeviceId)
    append('|').append(toDeviceId)
    if (sessionIds.isNotEmpty()) {
        append('|').append(sessionIds.joinToString(","))
    }
}

internal fun buildVoiceAnswer(
    sessionId: Int,
    fromDeviceId: String,
    toDeviceId: String
): String = listOf(
    AvaVoiceProtocol.ANSWER_PREFIX,
    sessionId.toString(),
    fromDeviceId,
    toDeviceId
).joinToString("|")

/**
 * Wire format: `AVA_VOICE_DECLINE|from|to[|sessionId]`
 * The trailing session ID is optional for backward compatibility; when present the
 * caller ignores declines that no longer match an active outbound session.
 */
internal fun buildVoiceDecline(
    fromDeviceId: String,
    toDeviceId: String,
    sessionId: Int = 0
): String = buildString {
    append(AvaVoiceProtocol.DECLINE_PREFIX)
    append('|').append(fromDeviceId)
    append('|').append(toDeviceId)
    if (sessionId != 0) append('|').append(sessionId)
}

/**
 * Wire format: `AVA_VOICE_NO_ANSWER|from|to[|sessionId]` — see [buildVoiceDecline].
 */
internal fun buildVoiceNoAnswer(
    fromDeviceId: String,
    toDeviceId: String,
    sessionId: Int = 0
): String = buildString {
    append(AvaVoiceProtocol.NO_ANSWER_PREFIX)
    append('|').append(fromDeviceId)
    append('|').append(toDeviceId)
    if (sessionId != 0) append('|').append(sessionId)
}

internal fun parseVoiceBegin(message: String): AvaVoiceIncomingMessage? {
    if (!message.startsWith("${AvaVoiceProtocol.BEGIN_PREFIX}|")) return null
    val parts = message.split('|')
    if (parts.size < 5) return null
    val sessionId = parts[1].toIntOrNull() ?: return null
    val fromId = parts[2]
    val toId = parts[3]
    if (fromId.isBlank() || toId.isBlank()) return null
    val fromName = parts[4].takeIf { it.isNotBlank() } ?: fromId
    val sampleRate = parts.getOrNull(5)?.toIntOrNull() ?: AvaVoiceAudioConfig.SAMPLE_RATE
    val mode = AvaVoiceMode.fromWire(parts.getOrNull(6))
    val delayMinutes = parts.getOrNull(7)?.toIntOrNull()?.coerceIn(0, 1440) ?: 0
    return AvaVoiceIncomingMessage(
        sessionId = sessionId,
        fromDeviceId = fromId,
        fromName = fromName,
        toDeviceId = toId,
        sampleRate = sampleRate,
        mode = mode,
        delayMinutes = delayMinutes,
        phase = AvaVoiceIncomingPhase.Started
    )
}

internal fun parseVoiceEnd(message: String): AvaVoiceIncomingMessage? {
    if (!message.startsWith("${AvaVoiceProtocol.END_PREFIX}|")) return null
    val parts = message.split('|')
    if (parts.size < 6) return null
    val sessionId = parts[1].toIntOrNull() ?: return null
    val fromId = parts[2]
    val toId = parts[3]
    val totalBytes = parts[4].toLongOrNull() ?: return null
    val durationMs = parts[5].toLongOrNull() ?: return null
    if (fromId.isBlank() || toId.isBlank()) return null
    return AvaVoiceIncomingMessage(
        sessionId = sessionId,
        fromDeviceId = fromId,
        fromName = fromId,
        toDeviceId = toId,
        totalBytes = totalBytes,
        durationMs = durationMs.coerceAtLeast(300L),
        phase = AvaVoiceIncomingPhase.Ended
    )
}

internal fun parseVoiceAnswer(message: String): AvaVoiceIncomingMessage? {
    if (!message.startsWith("${AvaVoiceProtocol.ANSWER_PREFIX}|")) return null
    val parts = message.split('|')
    if (parts.size < 4) return null
    val sessionId = parts[1].toIntOrNull() ?: return null
    val fromId = parts[2]
    val toId = parts[3]
    if (fromId.isBlank() || toId.isBlank()) return null
    return AvaVoiceIncomingMessage(
        sessionId = sessionId,
        fromDeviceId = fromId,
        fromName = fromId,
        toDeviceId = toId,
        mode = AvaVoiceMode.Call,
        phase = AvaVoiceIncomingPhase.Started
    )
}

internal fun parseVoiceDecline(message: String): AvaVoiceIncomingMessage? {
    if (!message.startsWith("${AvaVoiceProtocol.DECLINE_PREFIX}|")) return null
    val parts = message.split('|')
    if (parts.size < 3) return null
    val fromId = parts[1]
    val toId = parts[2]
    if (fromId.isBlank() || toId.isBlank()) return null
    return AvaVoiceIncomingMessage(
        sessionId = parts.getOrNull(3)?.toIntOrNull() ?: 0,
        fromDeviceId = fromId,
        fromName = fromId,
        toDeviceId = toId,
        mode = AvaVoiceMode.Call,
        phase = AvaVoiceIncomingPhase.Declined
    )
}

internal fun parseVoiceNoAnswer(message: String): AvaVoiceIncomingMessage? {
    if (!message.startsWith("${AvaVoiceProtocol.NO_ANSWER_PREFIX}|")) return null
    val parts = message.split('|')
    if (parts.size < 3) return null
    val fromId = parts[1]
    val toId = parts[2]
    if (fromId.isBlank() || toId.isBlank()) return null
    return AvaVoiceIncomingMessage(
        sessionId = parts.getOrNull(3)?.toIntOrNull() ?: 0,
        fromDeviceId = fromId,
        fromName = fromId,
        toDeviceId = toId,
        mode = AvaVoiceMode.Call,
        phase = AvaVoiceIncomingPhase.NoAnswer
    )
}

internal fun parseVoiceHangup(message: String): AvaVoiceIncomingMessage? {
    if (!message.startsWith("${AvaVoiceProtocol.HANGUP_PREFIX}|")) return null
    val parts = message.split('|')
    if (parts.size < 3) return null
    val fromId = parts[1]
    val toId = parts[2]
    if (fromId.isBlank() || toId.isBlank()) return null
    return AvaVoiceIncomingMessage(
        fromDeviceId = fromId,
        fromName = fromId,
        toDeviceId = toId,
        mode = AvaVoiceMode.Call,
        phase = AvaVoiceIncomingPhase.Hangup,
        hangupSessionIds = parseSessionIdList(parts.getOrNull(3))
    )
}

private fun parseSessionIdList(raw: String?): List<Int> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
}

/** Legacy stub control message — kept for backward compatibility. */
internal fun parseVoiceMessage(message: String): AvaVoiceIncomingMessage? {
    if (!message.startsWith("${AvaVoiceProtocol.MESSAGE_PREFIX}|")) return null
    val parts = message.split('|')
    if (parts.size < 4) return null
    val fromId = parts[1]
    val toId = parts[2]
    val durationMs = parts[3].toLongOrNull() ?: return null
    if (fromId.isBlank() || toId.isBlank()) return null
    val fromName = parts.getOrNull(4)?.takeIf { it.isNotBlank() } ?: fromId
    return AvaVoiceIncomingMessage(
        fromDeviceId = fromId,
        fromName = fromName,
        toDeviceId = toId,
        durationMs = durationMs.coerceAtLeast(300L),
        phase = AvaVoiceIncomingPhase.LegacyStub
    )
}

internal fun buildVoiceVideoStart(
    sessionId: Int,
    fromDeviceId: String,
    toDeviceId: String
): String = listOf(
    AvaVoiceProtocol.VIDEO_START_PREFIX,
    sessionId.toString(),
    fromDeviceId,
    toDeviceId
).joinToString("|")

internal fun buildVoiceVideoStop(
    sessionId: Int,
    fromDeviceId: String,
    toDeviceId: String
): String = listOf(
    AvaVoiceProtocol.VIDEO_STOP_PREFIX,
    sessionId.toString(),
    fromDeviceId,
    toDeviceId
).joinToString("|")

internal data class AvaVoiceVideoSignal(
    val sessionId: Int,
    val fromDeviceId: String,
    val toDeviceId: String
)

internal fun parseVoiceVideoStart(message: String): AvaVoiceVideoSignal? {
    if (!message.startsWith("${AvaVoiceProtocol.VIDEO_START_PREFIX}|")) return null
    val parts = message.split('|')
    if (parts.size < 4) return null
    val sessionId = parts[1].toIntOrNull() ?: return null
    val fromId = parts[2]
    val toId = parts[3]
    if (fromId.isBlank() || toId.isBlank()) return null
    return AvaVoiceVideoSignal(sessionId, fromId, toId)
}

internal fun parseVoiceVideoStop(message: String): AvaVoiceVideoSignal? {
    if (!message.startsWith("${AvaVoiceProtocol.VIDEO_STOP_PREFIX}|")) return null
    val parts = message.split('|')
    if (parts.size < 4) return null
    val sessionId = parts[1].toIntOrNull() ?: return null
    val fromId = parts[2]
    val toId = parts[3]
    if (fromId.isBlank() || toId.isBlank()) return null
    return AvaVoiceVideoSignal(sessionId, fromId, toId)
}

internal fun buildVoiceMessage(
    fromDeviceId: String,
    fromName: String,
    toDeviceId: String,
    durationMs: Long
): String = listOf(
    AvaVoiceProtocol.MESSAGE_PREFIX,
    fromDeviceId,
    toDeviceId,
    durationMs.toString(),
    fromName.replace('|', ' ')
).joinToString("|")

internal fun parseBeacon(message: String, sourceHost: String): AvaVoiceDevice? {
    if (!message.startsWith("${AvaVoiceProtocol.BEACON_PREFIX}|")) return null
    val parts = message.split('|')
    if (parts.size < 5) return null
    val id = parts[1]
    val name = parts[2]
    val type = AvaVoiceDeviceType.fromWire(parts[3])
    val host = parts[4].ifBlank { sourceHost }
    if (id.isBlank() || name.isBlank()) return null
    var clusterPort: Int? = null
    var webConsole: Boolean? = null
    var syncOffsetPeer = false
    var voiceMessaging: Boolean? = null
    var occupied: Boolean? = null
    var clonePort: Int? = null
    var cloneRemainSec: Int? = null
    var model = ""
    for (i in 5 until parts.size) {
        val part = parts[i]
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val key = part.substring(0, eq)
        val value = part.substring(eq + 1)
        when (key) {
            "clusterPort" -> clusterPort = value.toIntOrNull()?.coerceIn(0, 65535)
            "webConsole" -> webConsole = value == "1" || value.equals("true", ignoreCase = true)
            "syncOffsetPeer" ->
                syncOffsetPeer = value == "1" || value.equals("true", ignoreCase = true)
            "voiceMessaging" ->
                voiceMessaging = value == "1" || value.equals("true", ignoreCase = true)
            "occupied" -> occupied = value == "1" || value.equals("true", ignoreCase = true)
            "clonePort" -> clonePort = value.toIntOrNull()?.takeIf { it in 1..65535 }
            "cloneRemain" -> cloneRemainSec = value.toIntOrNull()?.takeIf { it > 0 }
            "model" -> model = value.trim()
        }
    }
    return AvaVoiceDevice(
        id = id,
        name = name,
        host = host,
        type = type,
        clusterPort = clusterPort,
        webConsole = webConsole,
        syncOffsetPeer = syncOffsetPeer,
        voiceMessaging = voiceMessaging,
        occupied = occupied,
        clonePort = clonePort,
        cloneRemainSec = cloneRemainSec,
        model = model,
    )
}
