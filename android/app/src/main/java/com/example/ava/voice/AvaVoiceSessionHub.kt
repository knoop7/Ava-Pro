package com.example.ava.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

data class BufferedPcmSend(
    val bytes: Int,
    val durationMs: Long,
    val reached: List<AvaVoiceDevice>,
)

/**
 * Coordinates outbound recording sessions and inbound PCM playback sessions.
 */
object AvaVoiceSessionHub {
    private const val TAG = "AvaVoiceSessionHub"
    private const val INBOUND_SESSION_TIMEOUT_MS = AvaVoiceProtocol.MAX_VOICE_MS + 5_000L
    private const val LIVE_SESSION_IDLE_TIMEOUT_MS = 12_000L
    private const val INBOUND_END_GRACE_MS = 120L
    /** Let the peer open its inbound session before the first injected frame. */
    private const val BEGIN_SETTLE_MS = 160L
    /** Let the last audio datagrams land before END (older hubs play on END). */
    private const val END_SETTLE_MS = 80L
    // Hangup is signalled over lossy UDP, so retransmit it a few times; a single dropped packet
    // must not leave the peer stuck in the call UI.
    private const val HANGUP_RETRANSMIT_COUNT = 4
    private const val HANGUP_RETRANSMIT_INTERVAL_MS = 150L
    private const val HANGUP_DEDUP_MS = 2_000L

    // A dropped call BEGIN means the callee never rings at all, so retransmit it like hangup.
    private const val CALL_BEGIN_RETRANSMIT_COUNT = 4
    private const val CALL_BEGIN_RETRANSMIT_INTERVAL_MS = 150L
    // A decline or hangup can land between BEGIN copies; remember the session so the next copy
    // cannot re-create it and ring again.
    private const val ENDED_CALL_SESSION_MEMORY_MS = 2_000L

    /** Backoff before rebuilding the audio receive socket (port may still be held). */
    private const val LISTEN_RETRY_MS = 3_000L

    // Hard caps to bound resource usage from malformed/hostile peers on the LAN.
    private const val MAX_CONCURRENT_INBOUND_SESSIONS = 8
    private const val MAX_INBOUND_SESSION_BYTES =
        AvaVoiceProtocol.MAX_VOICE_MS * AvaVoiceAudioConfig.SAMPLE_RATE / 1000L *
            AvaVoiceAudioConfig.BYTES_PER_SAMPLE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var audioListenJob: Job? = null
    /**
     * Live audio receive socket. Held outside the loop so [stop] can close it — a blocking
     * `receive()` ignores coroutine cancellation and would keep [AvaVoiceProtocol.AUDIO_PORT]
     * bound, making the next [start] fail to bind and leaving the device permanently deaf.
     */
    private val audioSocket = AtomicReference<DatagramSocket?>(null)

    private val inboundPlayers = ConcurrentHashMap<Int, AvaVoiceStreamPlayer>()
    private val inboundSessions = ConcurrentHashMap<Int, InboundSession>()
    private val outboundSessions = ConcurrentHashMap<Int, OutboundSession>()
    private val messageBoardMode = AtomicBoolean(false)
    // Hangup is retransmitted 4×; ignore duplicates so we don't tear down streams repeatedly.
    private val lastHangupFromPeerMs = ConcurrentHashMap<String, Long>()
    // Call sessions torn down moments ago, keyed by session id so a genuine new call from the same
    // peer (which always carries a fresh id) is never blocked.
    private val recentlyEndedCallSessions = ConcurrentHashMap<Int, Long>()
    /** Caller-side: peers whose outbound mic leg is live after answer / return BEGIN. */
    private val _outboundCallConnectedPeerIds = MutableStateFlow<Set<String>>(emptySet())
    val outboundCallConnectedPeerIds: StateFlow<Set<String>> = _outboundCallConnectedPeerIds.asStateFlow()
    /** Caller-side: peers that declined, timed out, or hung up — still in the selected group UI. */
    private val _outboundCallDisconnectedPeerIds = MutableStateFlow<Set<String>>(emptySet())
    val outboundCallDisconnectedPeerIds: StateFlow<Set<String>> =
        _outboundCallDisconnectedPeerIds.asStateFlow()
    private val _callMicMuted = MutableStateFlow(false)
    val callMicMuted: StateFlow<Boolean> = _callMicMuted.asStateFlow()
    /**
     * Whether this hub currently holds the call speakerphone session. Several events (ring, answer,
     * inbound playback, return leg) all want it on, but teardown happens once — without this guard
     * the ref count never falls back to zero and speakerphone stays forced on after the call ends.
     */
    private val callAudioSessionHeld = AtomicBoolean(false)

    private data class InboundSession(
        val sessionId: Int,
        val fromDeviceId: String,
        val fromName: String,
        val toDeviceId: String,
        val sampleRate: Int,
        val mode: AvaVoiceMode,
        val delayMinutes: Int,
        val deliverAtMs: Long,
        val sourceHost: String = "",
        val pendingAnswer: Boolean = false,
        val frames: ConcurrentSkipListMap<Int, ByteArray> = ConcurrentSkipListMap(),
        val totalBytes: AtomicLong = AtomicLong(0),
        val lastActivityMs: AtomicLong = AtomicLong(System.currentTimeMillis()),
        // Opus decoder for live calls (null for buffered/PCM sessions). Touched only by the single
        // audio listen loop thread, alongside [lastSequence].
        val opusDecoder: AvaVoiceOpusDecoder? = null,
        var lastSequence: Int = -1
    )

    private data class OutboundSession(
        val sessionId: Int,
        val fromDeviceId: String,
        val fromName: String,
        val targets: List<AvaVoiceDevice>,
        val recorder: AvaVoiceStreamRecorder,
        val mode: AvaVoiceMode,
        val delayMinutes: Int,
        // Opus encoder for live calls (null for buffered/PCM sessions). Touched only by the single
        // recorder capture coroutine.
        val opusEncoder: AvaVoiceOpusEncoder? = null,
        val recorderStarted: AtomicBoolean = AtomicBoolean(false),
        var sequence: Int = 0,
        var totalBytes: Long = 0
    )

    fun start() {
        if (!running.compareAndSet(false, true)) {
            rebuildAudioSocketIfNeeded()
            return
        }
        audioListenJob = scope.launch { audioListenLoop() }
        AvaVoiceVideoBridge.start()
        Log.d(TAG, "audio listener started on port ${AvaVoiceProtocol.AUDIO_PORT}")
    }

    /**
     * Force-close the audio socket so [audioListenLoop] exits with an error and re-binds.
     * Called when [start] is invoked but the hub is already running — a service restart
     * while another holder kept the hub alive may leave a wedged socket that blocks
     * `receive()` forever without delivering packets.
     */
    private fun rebuildAudioSocketIfNeeded() {
        val socket = audioSocket.get() ?: return
        if (socket.isClosed) {
            audioSocket.compareAndSet(socket, null)
            Log.d(TAG, "audio socket already closed, cleared for rebuild")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Close before cancel: unblocks receive() so the port is free for the next start().
        audioSocket.getAndSet(null)?.let { runCatching { it.close() } }
        audioListenJob?.cancel()
        audioListenJob = null
        AvaVoiceVideoBridge.stop()
        outboundSessions.values.forEach { it.recorder.stopImmediate() }
        outboundSessions.clear()
        inboundPlayers.values.forEach { it.release() }
        inboundPlayers.clear()
        AvaVoiceMessageBoard.clear()
        inboundSessions.clear()
        recentlyEndedCallSessions.clear()
        lastHangupFromPeerMs.clear()
        // Sessions are gone, so this releases the speakerphone and re-arms it for the next call.
        maybeLeaveCallAudioSession()
        AvaVoiceCallRingback.stop()
        AvaVoiceTransport.close()
        AvaVoiceMessenger.onHubStopped()
        _outboundCallConnectedPeerIds.value = emptySet()
        _outboundCallDisconnectedPeerIds.value = emptySet()
        resetCallMicMuted()
        Log.d(TAG, "stopped")
    }

    /** Mute outbound mic for all live call sessions (sends silence, keeps stream alive). */
    fun setCallMicMuted(muted: Boolean) {
        _callMicMuted.value = muted
        outboundSessions.values
            .filter { it.mode == AvaVoiceMode.Call }
            .forEach { it.recorder.setMicMuted(muted) }
    }

    private fun resetCallMicMuted() {
        if (!_callMicMuted.value) return
        _callMicMuted.value = false
        outboundSessions.values.forEach { it.recorder.setMicMuted(false) }
    }

    /** Clear caller-side per-peer UI tracking (call screen entry / fresh outbound). */
    fun resetOutboundCallPeerState() {
        _outboundCallConnectedPeerIds.value = emptySet()
        _outboundCallDisconnectedPeerIds.value = emptySet()
        resetCallMicMuted()
    }

    private fun markOutboundCallPeerLeft(peerDeviceId: String) {
        _outboundCallConnectedPeerIds.update { it - peerDeviceId }
        _outboundCallDisconnectedPeerIds.update { it + peerDeviceId }
    }

    /**
     * Drop one callee from an active outbound call without tearing down other peers.
     * Ends the outbound session only when this was the last target.
     */
    fun disconnectOutboundCallPeer(fromDeviceId: String, peerDeviceId: String) {
        markOutboundCallPeerLeft(peerDeviceId)
        val sessionEntry = outboundSessions.entries.firstOrNull { (_, session) ->
            session.mode == AvaVoiceMode.Call &&
                session.fromDeviceId == fromDeviceId &&
                session.targets.any { it.id == peerDeviceId }
        } ?: return
        val (sessionId, session) = sessionEntry
        val remaining = session.targets.filter { it.id != peerDeviceId }
        if (remaining.isEmpty()) {
            scope.launch {
                runCatching { AvaVoiceMessenger.stopOutboundSession(sessionId) }
            }
            return
        }
        outboundSessions[sessionId] = session.copy(targets = remaining)
        AvaVoiceMessenger.updateCallSessionTargets(sessionId, remaining)
    }

    /**
     * Conference join (auto-answer mode): extend this device's live outbound call leg to a
     * second caller so they hear us too. The peer never saw this session's BEGIN, so it is
     * sent here (retransmitted like the initial call BEGIN — one lost UDP packet would leave
     * the peer deaf to us for the whole call; its hub dedupes duplicates by session id).
     * No-op when no outbound call leg exists yet; [startOutbound] then picks the peer up
     * when the mic leg is created.
     */
    private fun joinPeerToOutboundCall(localDeviceId: String, peer: AvaVoiceDevice) {
        if (peer.host.isBlank()) return
        val entry = outboundSessions.entries.firstOrNull { (_, session) ->
            session.mode == AvaVoiceMode.Call && session.fromDeviceId == localDeviceId
        } ?: return
        val (sessionId, session) = entry
        if (session.targets.any { it.id == peer.id }) return
        val updated = session.targets + peer
        outboundSessions[sessionId] = session.copy(targets = updated)
        AvaVoiceMessenger.updateCallSessionTargets(sessionId, updated)
        _outboundCallConnectedPeerIds.update { it + peer.id }
        _outboundCallDisconnectedPeerIds.update { it - peer.id }
        val payload = buildVoiceBegin(
            sessionId = sessionId,
            fromDeviceId = localDeviceId,
            fromName = session.fromName,
            toDeviceId = peer.id,
            mode = AvaVoiceMode.Call,
            delayMinutes = 0
        )
        scope.launch {
            repeat(CALL_BEGIN_RETRANSMIT_COUNT) { attempt ->
                if (outboundSessions[sessionId] == null) return@launch
                AvaVoiceTransport.sendControl(peer.host, payload)
                if (attempt < CALL_BEGIN_RETRANSMIT_COUNT - 1) {
                    delay(CALL_BEGIN_RETRANSMIT_INTERVAL_MS)
                }
            }
        }
        Log.d(
            TAG,
            "conference: joined peer=${peer.id} into outbound call session=$sessionId " +
                "targets=${updated.size}"
        )
    }

    fun setMessageBoardMode(enabled: Boolean) {
        messageBoardMode.set(enabled)
    }

    /** True while any live call stream is active on this device (inbound or outbound). */
    fun hasLiveCallSessions(): Boolean =
        outboundSessions.values.any { it.mode == AvaVoiceMode.Call } ||
            inboundSessions.values.any { it.mode == AvaVoiceMode.Call }

    private fun canProcessInboundMedia(): Boolean =
        AvaVoiceNetwork.isFeatureEnabled() &&
            (AvaVoiceNetwork.isReceiveEnabled() ||
                AvaVoiceNetwork.isCallDuplexActive() ||
                hasLiveCallSessions())

    fun handleControlMessage(message: String, localDeviceId: String, sourceHost: String) {
        if (!AvaVoiceNetwork.isFeatureEnabled()) return
        parseVoiceBegin(message)?.let { begin ->
            if (begin.toDeviceId != localDeviceId) return
            if (begin.fromDeviceId == localDeviceId) return
            // Duplex call BEGIN: only the master switch is required so the outbound (send) device
            // can hear the peer even when enableVoiceMessageReceive is off.
            if (begin.mode == AvaVoiceMode.Call) {
                onInboundBegin(begin.copy(sourceHost = sourceHost))
                return
            }
            if (!canProcessInboundMedia()) return
            onInboundBegin(begin.copy(sourceHost = sourceHost))
            return
        }
        // Call signaling must reach the outbound (send) side even when receive is off.
        parseVoiceHangup(message)?.let { hangup ->
            if (hangup.toDeviceId != localDeviceId) return
            if (hangup.fromDeviceId == localDeviceId) return
            onInboundHangup(hangup.copy(sourceHost = sourceHost))
            return
        }
        parseVoiceDecline(message)?.let { decline ->
            if (decline.toDeviceId != localDeviceId) return
            if (decline.fromDeviceId == localDeviceId) return
            onInboundDecline(decline.copy(sourceHost = sourceHost))
            return
        }
        parseVoiceNoAnswer(message)?.let { noAnswer ->
            if (noAnswer.toDeviceId != localDeviceId) return
            if (noAnswer.fromDeviceId == localDeviceId) return
            onInboundNoAnswer(noAnswer.copy(sourceHost = sourceHost))
            return
        }
        parseVoiceAnswer(message)?.let { answer ->
            if (answer.toDeviceId != localDeviceId) return
            if (answer.fromDeviceId == localDeviceId) return
            val outbound = outboundSessions[answer.sessionId]
            if (outbound == null || outbound.mode != AvaVoiceMode.Call) {
                // ANSWER for a session this device already abandoned (rapid hang-up/start
                // churn). Without a NAK the peer stays "in call" against a dead session
                // while this side rings a fresh one — the classic split-brain (#203).
                // Retransmitted like hangup: a single lost NAK would leave the peer
                // streaming to nobody forever (its idle cleanup defers to its own live
                // outbound leg). Duplicates are deduped by the peer's hangup window.
                Log.w(
                    TAG,
                    "stale answer from=${answer.fromDeviceId} session=${answer.sessionId} — sending hangup NAK"
                )
                val nakPayload =
                    buildVoiceHangup(localDeviceId, answer.fromDeviceId, listOf(answer.sessionId))
                scope.launch {
                    repeat(HANGUP_RETRANSMIT_COUNT) { attempt ->
                        AvaVoiceTransport.sendControl(sourceHost, nakPayload)
                        if (attempt < HANGUP_RETRANSMIT_COUNT - 1) {
                            delay(HANGUP_RETRANSMIT_INTERVAL_MS)
                        }
                    }
                }
                return
            }
            AvaVoiceCallRingback.stop()
            startAnsweredOutboundCall(answer.sessionId, answer.fromDeviceId)
            Log.d(TAG, "peer answered call from=${answer.fromDeviceId} session=${answer.sessionId}")
            return
        }
        parseVoiceVideoStart(message)?.let { signal ->
            if (signal.toDeviceId != localDeviceId) return
            if (signal.fromDeviceId == localDeviceId) return
            AvaVoiceVideoBridge.handlePeerVideoStart(signal)
            return
        }
        parseVoiceVideoStop(message)?.let { signal ->
            if (signal.toDeviceId != localDeviceId) return
            if (signal.fromDeviceId == localDeviceId) return
            AvaVoiceVideoBridge.handlePeerVideoStop(signal)
            return
        }
        if (!canProcessInboundMedia()) return
        parseVoiceEnd(message)?.let { end ->
            if (end.toDeviceId != localDeviceId) return
            if (end.fromDeviceId == localDeviceId) return
            onInboundEnd(end.copy(sourceHost = sourceHost))
            return
        }
        parseVoiceMessage(message)?.let { legacy ->
            if (legacy.toDeviceId != localDeviceId) return
            if (legacy.fromDeviceId == localDeviceId) return
            AvaVoiceInboundBus.emit(legacy)
        }
    }

    fun sendVoiceHangup(
        fromDeviceId: String,
        targets: List<AvaVoiceDevice>,
        sessionIdsByTarget: Map<String, List<Int>> = emptyMap()
    ) {
        if (targets.isEmpty()) return
        // Payloads are built before launching: callers tear local sessions down right after
        // this returns, and the session IDs must reflect the state at hang-up time.
        val payloads = targets.map { target ->
            val sessionIds = sessionIdsByTarget[target.id]
                ?: findCallSessionIds(fromDeviceId, target.id)
            target.host to buildVoiceHangup(fromDeviceId, target.id, sessionIds)
        }
        scope.launch {
            repeat(HANGUP_RETRANSMIT_COUNT) { attempt ->
                payloads.forEach { (host, payload) ->
                    AvaVoiceTransport.sendControl(host, payload)
                }
                if (attempt < HANGUP_RETRANSMIT_COUNT - 1) {
                    delay(HANGUP_RETRANSMIT_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * Every peer with a live call session on this device (outbound targets plus inbound
     * callers with a known host). Lets a global hang-up (HA `hang_up`) notify peers even
     * when the local UI session tracking has desynced or was never created.
     */
    fun activeCallPeers(): List<AvaVoiceDevice> {
        val peers = LinkedHashMap<String, AvaVoiceDevice>()
        outboundSessions.values
            .filter { it.mode == AvaVoiceMode.Call }
            .forEach { session ->
                session.targets.forEach { target -> peers.putIfAbsent(target.id, target) }
            }
        inboundSessions.values
            .filter { it.mode == AvaVoiceMode.Call && it.sourceHost.isNotBlank() }
            .forEach { session ->
                peers.putIfAbsent(
                    session.fromDeviceId,
                    AvaVoiceDevice(
                        id = session.fromDeviceId,
                        name = session.fromName,
                        host = session.sourceHost,
                        type = AvaVoiceDeviceType.UNKNOWN
                    )
                )
            }
        return peers.values.toList()
    }

    /**
     * Snapshot the live call session IDs toward each target. Must be taken *before*
     * local teardown so the outgoing HANGUP can carry the IDs it is ending.
     */
    fun captureCallSessionIds(
        fromDeviceId: String,
        targets: List<AvaVoiceDevice>
    ): Map<String, List<Int>> =
        targets.associate { target -> target.id to findCallSessionIds(fromDeviceId, target.id) }

    /**
     * Collect all active call session IDs between [localDeviceId] and [peerDeviceId]
     * (both outbound and inbound directions) for inclusion in HANGUP messages.
     */
    private fun findCallSessionIds(localDeviceId: String, peerDeviceId: String): List<Int> {
        val ids = mutableListOf<Int>()
        outboundSessions.entries.forEach { (sessionId, session) ->
            if (session.mode == AvaVoiceMode.Call &&
                session.fromDeviceId == localDeviceId &&
                session.targets.any { it.id == peerDeviceId }
            ) {
                ids.add(sessionId)
            }
        }
        inboundSessions.entries.forEach { (sessionId, session) ->
            if (session.mode == AvaVoiceMode.Call &&
                session.fromDeviceId == peerDeviceId &&
                session.toDeviceId == localDeviceId
            ) {
                ids.add(sessionId)
            }
        }
        return ids
    }

    fun stopInboundCallStreams(fromDeviceIds: Set<String>) {
        if (fromDeviceIds.isEmpty()) return
        inboundSessions.entries
            .filter { it.value.fromDeviceId in fromDeviceIds && it.value.mode == AvaVoiceMode.Call }
            .forEach { (sessionId, _) ->
                inboundSessions.remove(sessionId)
                inboundPlayers.remove(sessionId)?.release()
                rememberEndedCallSession(sessionId)
            }
        AvaVoiceVideoBridge.resetAll()
        resetCallMicMuted()
        maybeLeaveCallAudioSession()
        maybeLeaveCallDuplex()
    }

    /**
     * BEGIN is retransmitted, so a decline or hangup can land between copies; without this the next
     * copy re-creates the session and the phone rings again after the user already dismissed it.
     */
    private fun rememberEndedCallSession(sessionId: Int) {
        val now = System.currentTimeMillis()
        recentlyEndedCallSessions.entries.removeAll {
            now - it.value > ENDED_CALL_SESSION_MEMORY_MS
        }
        recentlyEndedCallSessions[sessionId] = now
    }

    private fun isRecentlyEndedCallSession(sessionId: Int): Boolean {
        val endedAtMs = recentlyEndedCallSessions[sessionId] ?: return false
        if (System.currentTimeMillis() - endedAtMs > ENDED_CALL_SESSION_MEMORY_MS) {
            recentlyEndedCallSessions.remove(sessionId)
            return false
        }
        return true
    }

    /** User tapped answer on the incoming-call overlay. */
    fun answerInboundCall(sessionId: Int) {
        val session = inboundSessions[sessionId] ?: return
        if (session.mode != AvaVoiceMode.Call || !session.pendingAnswer) return
        startInboundCallPlayback(session, notifyUi = true)
        if (session.sourceHost.isNotBlank()) {
            val host = session.sourceHost
            val payload = buildVoiceAnswer(sessionId, session.toDeviceId, session.fromDeviceId)
            // Sent once on purpose. Older builds dedup BEGIN but not ANSWER, so retransmitting
            // would replay the caller's "connected" UI once per copy. A lost ANSWER is already
            // covered by the return-leg BEGIN this device sends right after answering.
            scope.launch { AvaVoiceTransport.sendControl(host, payload) }
        }
        Log.d(TAG, "inbound call answered session=$sessionId from=${session.fromName}")
    }

    /** User declined before answering. */
    fun declineInboundCall(fromDeviceId: String) {
        val sessions = inboundSessions.entries.filter {
            it.value.mode == AvaVoiceMode.Call && it.value.fromDeviceId == fromDeviceId
        }
        if (sessions.isEmpty()) return
        val (sessionId, session) = sessions.first()
        stopInboundCallStreams(setOf(fromDeviceId))
        if (session.sourceHost.isNotBlank()) {
            val host = session.sourceHost
            val payload = buildVoiceDecline(session.toDeviceId, session.fromDeviceId, sessionId)
            scope.launch { AvaVoiceTransport.sendControl(host, payload) }
        }
        Log.d(TAG, "inbound call declined from=$fromDeviceId session=$sessionId")
    }

    /** Ring timeout — notify caller and tear down pending session. */
    fun timeoutInboundCall(fromDeviceId: String) {
        val sessions = inboundSessions.entries.filter {
            it.value.mode == AvaVoiceMode.Call &&
                it.value.fromDeviceId == fromDeviceId &&
                it.value.pendingAnswer
        }
        if (sessions.isEmpty()) return
        val (sessionId, session) = sessions.first()
        stopInboundCallStreams(setOf(fromDeviceId))
        if (session.sourceHost.isNotBlank()) {
            scope.launch {
                val payload = buildVoiceNoAnswer(session.toDeviceId, session.fromDeviceId, sessionId)
                repeat(HANGUP_RETRANSMIT_COUNT) { attempt ->
                    AvaVoiceTransport.sendControl(session.sourceHost, payload)
                    if (attempt < HANGUP_RETRANSMIT_COUNT - 1) {
                        delay(HANGUP_RETRANSMIT_INTERVAL_MS)
                    }
                }
            }
        }
        Log.d(TAG, "inbound call no-answer from=$fromDeviceId session=$sessionId")
    }

    private fun ensureCallAudioSession() {
        val context = AvaVoiceNetwork.applicationContext() ?: return
        if (!callAudioSessionHeld.compareAndSet(false, true)) return
        AvaVoiceCallAudioSession.enter(context)
    }

    private fun maybeLeaveCallAudioSession() {
        val hasInboundCall = inboundSessions.values.any { it.mode == AvaVoiceMode.Call }
        val hasOutboundCall = outboundSessions.values.any { it.mode == AvaVoiceMode.Call }
        if (hasInboundCall || hasOutboundCall) return
        val context = AvaVoiceNetwork.applicationContext() ?: return
        if (!callAudioSessionHeld.compareAndSet(true, false)) return
        AvaVoiceCallAudioSession.leave(context)
    }

    /** True when a live call already has an outbound mic stream open to any of [targetIds]. */
    fun hasActiveCallOutboundTo(fromDeviceId: String, targetIds: Set<String>): Boolean {
        if (targetIds.isEmpty()) return false
        return outboundSessions.values.any { session ->
            session.mode == AvaVoiceMode.Call &&
                session.fromDeviceId == fromDeviceId &&
                session.targets.any { it.id in targetIds }
        }
    }

    fun isActiveCallSession(sessionId: Int): Boolean {
        val outbound = outboundSessions[sessionId]
        if (outbound?.mode == AvaVoiceMode.Call) return true
        val inbound = inboundSessions[sessionId]
        return inbound?.mode == AvaVoiceMode.Call && !inbound.pendingAnswer
    }

    /**
     * True when outbound call leg [sessionId] still serves a peer beyond [ownedPeerIds].
     * That means the leg was handed off in a conference (a joined caller outlived the
     * peer this controller created it for), so the creating UI must not stop it.
     */
    fun callSessionServesOtherPeers(sessionId: Int, ownedPeerIds: Set<String>): Boolean {
        val session = outboundSessions[sessionId] ?: return false
        if (session.mode != AvaVoiceMode.Call) return false
        return session.targets.any { it.id !in ownedPeerIds }
    }

    /**
     * True when any call session (ringing or live, inbound or outbound) involves a peer
     * other than [callerDeviceId] — i.e. this device is single-line "busy" for a new call.
     */
    private fun isBusyWithOtherCallPeer(callerDeviceId: String): Boolean {
        return inboundSessions.values.any {
            it.mode == AvaVoiceMode.Call && it.fromDeviceId != callerDeviceId
        } || outboundSessions.values.any { session ->
            session.mode == AvaVoiceMode.Call && session.targets.none { it.id == callerDeviceId }
        }
    }

    /** Resolve the shared call session id for UI video toggles. */
    fun findCallSessionId(localDeviceId: String, peerDeviceIds: Set<String>): Int? {
        if (peerDeviceIds.isEmpty()) return null
        outboundSessions.entries.firstOrNull { (_, session) ->
            session.mode == AvaVoiceMode.Call &&
                session.fromDeviceId == localDeviceId &&
                session.targets.any { it.id in peerDeviceIds }
        }?.key?.let { return it }
        inboundSessions.entries.firstOrNull { (_, session) ->
            session.mode == AvaVoiceMode.Call &&
                !session.pendingAnswer &&
                session.fromDeviceId in peerDeviceIds
        }?.key?.let { return it }
        return null
    }

    suspend fun startOutbound(
        fromDeviceId: String,
        fromName: String,
        targets: List<AvaVoiceDevice>,
        mode: AvaVoiceMode = AvaVoiceMode.Intercom,
        delayMinutes: Int = 0
    ): Int? = withContext(Dispatchers.IO) {
        if (!AvaVoiceNetwork.isFeatureEnabled()) return@withContext null
        if (targets.isEmpty()) return@withContext null
        if (mode == AvaVoiceMode.Call) {
            val targetIds = targets.map { it.id }.toSet()
            outboundSessions.entries.firstOrNull { (_, session) ->
                session.mode == AvaVoiceMode.Call &&
                    session.fromDeviceId == fromDeviceId &&
                    session.targets.any { it.id in targetIds }
            }?.let { (sessionId, _) ->
                Log.d(TAG, "outbound call reusing session=$sessionId peers=$targetIds")
                resetOutboundCallPeerState()
                return@withContext sessionId
            }
        }
        val callReturnLeg = mode == AvaVoiceMode.Call && targets.any { target ->
            inboundSessions.values.any { inbound ->
                inbound.mode == AvaVoiceMode.Call &&
                    !inbound.pendingAnswer &&
                    inbound.fromDeviceId == target.id &&
                    inbound.toDeviceId == fromDeviceId
            }
        }
        // Auto-answer conference: a return leg created while other callers are already live
        // must reach them too. joinPeerToOutboundCall only covers callers that arrive after
        // the leg exists; without this, a caller that auto-answered in first would stay deaf.
        val effectiveTargets = if (callReturnLeg && !AvaVoiceNetwork.isCallAnswerRequired()) {
            val known = targets.map { it.id }.toSet()
            val joiners = inboundSessions.values
                .filter { inbound ->
                    inbound.mode == AvaVoiceMode.Call && !inbound.pendingAnswer &&
                        inbound.fromDeviceId != fromDeviceId &&
                        inbound.fromDeviceId !in known &&
                        inbound.sourceHost.isNotBlank()
                }
                .distinctBy { it.fromDeviceId }
                .map { inbound ->
                    AvaVoiceDevice(
                        id = inbound.fromDeviceId,
                        name = inbound.fromName,
                        host = inbound.sourceHost,
                        type = AvaVoiceDeviceType.UNKNOWN
                    )
                }
            targets + joiners
        } else {
            targets
        }
        val sessionId = Random.nextInt(1, Int.MAX_VALUE)
        // Live calls are Opus-compressed (~10x less bandwidth, in-band FEC). Buffered messages stay
        // PCM so the message board / replay path is unchanged.
        val opusEncoder = if (mode == AvaVoiceMode.Call) {
            AvaVoiceOpusEncoder().takeIf { it.isReady }
        } else {
            null
        }
        val recorder = AvaVoiceStreamRecorder(callCaptureEnhance = mode == AvaVoiceMode.Call) { frame ->
            val session = outboundSessions[sessionId] ?: return@AvaVoiceStreamRecorder
            val encoder = session.opusEncoder
            val payload: ByteArray
            val format: Int
            if (encoder != null) {
                val encoded = encoder.encode(frame)
                if (encoded.isEmpty()) return@AvaVoiceStreamRecorder // soft drop, keep the stream alive
                payload = encoded
                format = AvaVoicePacket.FORMAT_OPUS
            } else {
                payload = frame
                format = AvaVoicePacket.FORMAT_PCM
            }
            // Account in PCM bytes so duration math in END/markEnded stays codec-independent.
            session.totalBytes += frame.size
            val packet = AvaVoicePacket.encode(
                sessionId = sessionId,
                sequence = session.sequence++,
                flags = AvaVoicePacket.FLAG_DATA,
                payload = payload,
                format = format
            )
            session.targets.forEach { target ->
                AvaVoiceTransport.sendAudioSync(target.host, packet)
            }
        }
        if (mode == AvaVoiceMode.Call) {
            AvaVoiceNetwork.enterCallDuplex()
        }
        val session = OutboundSession(
            sessionId = sessionId,
            fromDeviceId = fromDeviceId,
            fromName = fromName,
            targets = effectiveTargets,
            recorder = recorder,
            mode = mode,
            delayMinutes = delayMinutes.coerceIn(0, 1440),
            opusEncoder = opusEncoder
        )
        outboundSessions[sessionId] = session
        resetOutboundCallPeerState()
        val beginPayloads = effectiveTargets.map { target ->
            Triple(
                target.id,
                target.host,
                buildVoiceBegin(
                    sessionId = sessionId,
                    fromDeviceId = fromDeviceId,
                    fromName = fromName,
                    toDeviceId = target.id,
                    mode = mode,
                    delayMinutes = session.delayMinutes
                )
            )
        }
        beginPayloads.forEach { (_, host, payload) -> AvaVoiceTransport.sendControl(host, payload) }
        if (mode == AvaVoiceMode.Call) {
            // A dropped BEGIN means the callee never rings at all. Buffered messages must not be
            // retransmitted — a duplicate BEGIN there resets the receiving session and discards
            // frames already collected.
            scope.launch {
                repeat(CALL_BEGIN_RETRANSMIT_COUNT - 1) {
                    delay(CALL_BEGIN_RETRANSMIT_INTERVAL_MS)
                    if (outboundSessions[sessionId] == null) return@launch
                    // A peer that already answered or declined clearly got the BEGIN. Skipping it
                    // matters most for a peer that declined within the burst: another copy would
                    // make its phone ring a second time.
                    val settled = _outboundCallConnectedPeerIds.value +
                        _outboundCallDisconnectedPeerIds.value
                    beginPayloads.forEach { (peerId, host, payload) ->
                        if (peerId in settled) return@forEach
                        AvaVoiceTransport.sendControl(host, payload)
                    }
                }
            }
        }
        if (mode == AvaVoiceMode.Call && !callReturnLeg) {
            ensureCallAudioSession()
            AvaVoiceCallRingback.start()
            Log.d(TAG, "outbound call ringing session=$sessionId targets=${targets.size}")
            return@withContext sessionId
        }
        if (!startOutboundRecorder(session)) {
            outboundSessions.remove(sessionId)
            if (mode == AvaVoiceMode.Call) {
                maybeLeaveCallAudioSession()
                // Balances the enterCallDuplex() above; the mic-busy path is the common failure.
                maybeLeaveCallDuplex()
            }
            effectiveTargets.forEach { target ->
                AvaVoiceTransport.sendControl(
                    target.host,
                    buildVoiceEnd(
                        sessionId = sessionId,
                        fromDeviceId = fromDeviceId,
                        toDeviceId = target.id,
                        totalBytes = 0L,
                        durationMs = 0L
                    )
                )
            }
            return@withContext null
        }
        Log.d(TAG, "outbound started session=$sessionId targets=${effectiveTargets.size}")
        if (mode == AvaVoiceMode.Call) {
            ensureCallAudioSession()
        }
        sessionId
    }

    /**
     * Leave a clip the receiver already knows how to play: BEGIN + PCM frames + END.
     * No microphone. Used when TTS is injected as a voice message.
     */
    suspend fun sendBufferedPcm(
        fromDeviceId: String,
        fromName: String,
        targets: List<AvaVoiceDevice>,
        pcm: ByteArray,
        delayMinutes: Int = 0,
    ): BufferedPcmSend? = withContext(Dispatchers.IO) {
        if (!AvaVoiceNetwork.isFeatureEnabled()) return@withContext null
        if (targets.isEmpty() || pcm.isEmpty()) return@withContext null
        val maxBytes = MAX_INBOUND_SESSION_BYTES.toInt()
        val clipped = if (pcm.size > maxBytes) pcm.copyOf(maxBytes) else pcm
        val sessionId = Random.nextInt(1, Int.MAX_VALUE)
        val delay = delayMinutes.coerceIn(0, 1440)
        val reached = targets.filter { target ->
            AvaVoiceTransport.sendControl(
                target.host,
                buildVoiceBegin(
                    sessionId = sessionId,
                    fromDeviceId = fromDeviceId,
                    fromName = fromName,
                    toDeviceId = target.id,
                    mode = AvaVoiceMode.Intercom,
                    delayMinutes = delay,
                ),
            )
        }
        if (reached.isEmpty()) return@withContext null
        // Hold-to-talk waits on the first AudioRecord frame (~20ms+) so BEGIN
        // is already in the peer session table. Inject has no mic — without a
        // pause, audio UDP lands first and older hubs drop every frame.
        delay(BEGIN_SETTLE_MS)
        var sequence = 0
        var offset = 0
        var framesSent = 0
        while (offset < clipped.size) {
            val length = minOf(AvaVoiceAudioConfig.FRAME_BYTES, clipped.size - offset)
            val packet = AvaVoicePacket.encode(
                sessionId = sessionId,
                sequence = sequence++,
                flags = AvaVoicePacket.FLAG_DATA,
                payload = clipped,
                offset = offset,
                length = length,
                format = AvaVoicePacket.FORMAT_PCM,
            )
            var delivered = false
            reached.forEach { target ->
                if (AvaVoiceTransport.sendAudioSync(target.host, packet)) delivered = true
            }
            if (delivered) framesSent++
            offset += length
            if (sequence % 8 == 0) yield()
        }
        if (framesSent == 0) return@withContext null
        delay(END_SETTLE_MS)
        val durationMs = (
            clipped.size * 1000L /
                (AvaVoiceAudioConfig.SAMPLE_RATE * AvaVoiceAudioConfig.BYTES_PER_SAMPLE)
            ).coerceAtMost(AvaVoiceProtocol.MAX_VOICE_MS)
        reached.forEach { target ->
            AvaVoiceTransport.sendControl(
                target.host,
                buildVoiceEnd(
                    sessionId = sessionId,
                    fromDeviceId = fromDeviceId,
                    toDeviceId = target.id,
                    totalBytes = clipped.size.toLong(),
                    durationMs = durationMs,
                ),
            )
        }
        Log.d(TAG, "buffered pcm sent session=$sessionId bytes=${clipped.size} duration=$durationMs peers=${reached.size}")
        BufferedPcmSend(bytes = clipped.size, durationMs = durationMs, reached = reached)
    }

    private fun startAnsweredOutboundCall(sessionId: Int, peerDeviceId: String? = null) {
        val session = outboundSessions[sessionId] ?: return
        if (session.mode != AvaVoiceMode.Call) return
        val wasRecording = session.recorderStarted.get()
        if (startOutboundRecorder(session)) {
            ensureCallAudioSession()
            // Multi-target: B may already have started the recorder; still mark C connected.
            if (!peerDeviceId.isNullOrBlank()) {
                notifyOutboundCallConnected(session, peerDeviceId)
            } else if (!wasRecording) {
                notifyOutboundCallConnected(session, peerDeviceId)
            }
            Log.d(TAG, "outbound call connected session=$sessionId peer=$peerDeviceId")
        } else {
            outboundSessions.remove(sessionId)
            maybeLeaveCallAudioSession()
            maybeLeaveCallDuplex()
            AvaVoiceMessenger.stopLocalCallStreams(session.fromDeviceId, session.targets)
            Log.w(TAG, "outbound call recorder failed after answer session=$sessionId")
        }
    }

    private fun startAnsweredOutboundCall(fromDeviceId: String, peerDeviceId: String) {
        val session = outboundSessions.values.firstOrNull { candidate ->
            candidate.mode == AvaVoiceMode.Call &&
                candidate.fromDeviceId == fromDeviceId &&
                candidate.targets.any { it.id == peerDeviceId }
        } ?: return
        startAnsweredOutboundCall(session.sessionId, peerDeviceId)
    }

    /** Caller UI: peer picked up (ANSWER) or return audio leg (BEGIN) started the outbound mic. */
    private fun notifyOutboundCallConnected(session: OutboundSession, peerDeviceId: String?) {
        val peerId = peerDeviceId?.takeIf { it.isNotBlank() }
            ?: session.targets.singleOrNull()?.id
            ?: return
        val peerName = session.targets.firstOrNull { it.id == peerId }?.name ?: peerId
        _outboundCallConnectedPeerIds.update { it + peerId }
        AvaVoiceInboundBus.emit(
            AvaVoiceIncomingMessage(
                sessionId = session.sessionId,
                fromDeviceId = peerId,
                fromName = peerName,
                toDeviceId = session.fromDeviceId,
                mode = AvaVoiceMode.Call,
                phase = AvaVoiceIncomingPhase.Started
            )
        )
    }

    private fun clearOutboundCallConnected(session: OutboundSession) {
        val peerIds = session.targets.map { it.id }.toSet()
        if (peerIds.isEmpty()) return
        _outboundCallConnectedPeerIds.update { it - peerIds }
    }

    private fun startOutboundRecorder(session: OutboundSession): Boolean {
        if (!session.recorderStarted.compareAndSet(false, true)) return true
        if (session.recorder.start()) return true
        session.recorderStarted.set(false)
        return false
    }

    suspend fun stopOutbound(sessionId: Int): Boolean = withContext(Dispatchers.IO) {
        val session = outboundSessions.remove(sessionId) ?: return@withContext false
        if (session.mode == AvaVoiceMode.Call) {
            clearOutboundCallConnected(session)
            AvaVoiceVideoBridge.resetAll()
            resetCallMicMuted()
        }
        val (totalBytes, durationMs) = session.recorder.stopAndJoin()
        val reportDuration = if (session.mode == AvaVoiceMode.Call) {
            durationMs.coerceAtMost(AvaVoiceProtocol.MAX_CALL_MS)
        } else {
            durationMs.coerceAtMost(AvaVoiceProtocol.MAX_VOICE_MS)
        }
        session.targets.forEach { target ->
            AvaVoiceTransport.sendControl(
                target.host,
                buildVoiceEnd(
                    sessionId = sessionId,
                    fromDeviceId = session.fromDeviceId,
                    toDeviceId = target.id,
                    totalBytes = totalBytes,
                    durationMs = reportDuration
                )
            )
        }
        Log.d(TAG, "outbound ended session=$sessionId mode=${session.mode} bytes=$totalBytes duration=$reportDuration")
        if (session.mode == AvaVoiceMode.Call) {
            AvaVoiceCallRingback.stop()
            maybeLeaveCallAudioSession()
            maybeLeaveCallDuplex()
        }
        totalBytes > 0 || reportDuration >= 300L
    }

    private fun maybeLeaveCallDuplex() {
        if (!hasLiveCallSessions()) {
            AvaVoiceNetwork.leaveCallDuplex()
        }
    }

    private fun onInboundBegin(begin: AvaVoiceIncomingMessage) {
        if (begin.mode == AvaVoiceMode.Call) {
            if (isRecentlyEndedCallSession(begin.sessionId)) {
                Log.d(TAG, "inbound call begin ignored, session=${begin.sessionId} already ended")
                return
            }
            // Single inbound playback track per peer: ignore duplicate/spurious BEGIN so we never
            // open a second AudioTrack for the same caller.
            val existing = inboundSessions.entries.firstOrNull {
                it.value.mode == AvaVoiceMode.Call && it.value.fromDeviceId == begin.fromDeviceId
            }
            if (existing != null) {
                if (existing.key == begin.sessionId) {
                    existing.value.lastActivityMs.set(System.currentTimeMillis())
                    return
                }
                Log.w(
                    TAG,
                    "inbound call migrate session ${existing.key} -> ${begin.sessionId} " +
                        "peer=${begin.fromDeviceId}"
                )
                inboundPlayers.remove(existing.key)?.release()
                inboundSessions.remove(existing.key)
            }
        }
        if (!inboundSessions.containsKey(begin.sessionId) &&
            inboundSessions.size >= MAX_CONCURRENT_INBOUND_SESSIONS
        ) {
            Log.w(TAG, "inbound session limit reached, dropping session=${begin.sessionId}")
            return
        }
        // Callee's outbound BEGIN after answer is the return audio leg — not a new incoming call UI.
        val duplexReturnLeg = begin.mode == AvaVoiceMode.Call &&
            hasActiveCallOutboundTo(begin.toDeviceId, setOf(begin.fromDeviceId))
        if (begin.mode == AvaVoiceMode.Call && !duplexReturnLeg && isBusyWithOtherCallPeer(begin.fromDeviceId)) {
            // Ring mode is single-line: the overlay holds one message slot and can't ring a
            // second call over a live one, so a second caller is busy-declined at the source.
            // Auto-answer mode instead conferences the caller in: inbound playback mixes
            // per-session, and joinPeerToOutboundCall below extends our mic leg to them.
            if (AvaVoiceNetwork.isCallAnswerRequired()) {
                if (begin.sourceHost.isNotBlank()) {
                    val payload = buildVoiceDecline(begin.toDeviceId, begin.fromDeviceId, begin.sessionId)
                    scope.launch { AvaVoiceTransport.sendControl(begin.sourceHost, payload) }
                }
                Log.d(
                    TAG,
                    "inbound call busy-declined from=${begin.fromDeviceId} session=${begin.sessionId}"
                )
                return
            }
            Log.d(
                TAG,
                "inbound call conference join from=${begin.fromDeviceId} session=${begin.sessionId}"
            )
        }
        if (duplexReturnLeg) {
            AvaVoiceCallRingback.stop()
            startAnsweredOutboundCall(begin.toDeviceId, begin.fromDeviceId)
        }
        val opusDecoder = if (begin.mode == AvaVoiceMode.Call) {
            AvaVoiceOpusDecoder(AvaVoiceAudioConfig.SAMPLE_RATE).takeIf { it.isReady }
        } else {
            null
        }
        val needsAnswer = begin.mode == AvaVoiceMode.Call &&
            AvaVoiceNetwork.isCallAnswerRequired() &&
            !duplexReturnLeg
        inboundSessions[begin.sessionId] = InboundSession(
            sessionId = begin.sessionId,
            fromDeviceId = begin.fromDeviceId,
            fromName = begin.fromName,
            toDeviceId = begin.toDeviceId,
            sampleRate = begin.sampleRate,
            mode = begin.mode,
            delayMinutes = begin.delayMinutes,
            deliverAtMs = System.currentTimeMillis() + begin.delayMinutes.coerceIn(0, 1440) * 60_000L,
            sourceHost = begin.sourceHost,
            pendingAnswer = needsAnswer,
            opusDecoder = opusDecoder
        )
        inboundPlayers[begin.sessionId]?.release()
        inboundPlayers.remove(begin.sessionId)
        if (begin.mode == AvaVoiceMode.Call) {
            AvaVoiceInboundBus.resetPlaybackProgress()
            if (needsAnswer) {
                AvaVoiceInboundBus.emit(begin.copy(phase = AvaVoiceIncomingPhase.Ringing))
                scheduleInboundIdleCleanup(begin.sessionId, AvaVoiceProtocol.CALL_RING_TIMEOUT_MS + 5_000L)
                Log.d(TAG, "inbound call ringing session=${begin.sessionId} from=${begin.fromName}")
            } else {
                val session = inboundSessions[begin.sessionId] ?: return
                startInboundCallPlayback(session, notifyUi = !duplexReturnLeg)
                if (!duplexReturnLeg) {
                    joinPeerToOutboundCall(
                        localDeviceId = begin.toDeviceId,
                        peer = AvaVoiceDevice(
                            id = begin.fromDeviceId,
                            name = begin.fromName,
                            host = begin.sourceHost,
                            type = AvaVoiceDeviceType.UNKNOWN
                        )
                    )
                }
                Log.d(
                    TAG,
                    "inbound begin live call session=${begin.sessionId} from=${begin.fromName} " +
                        "duplexReturn=$duplexReturnLeg"
                )
            }
        } else {
            scheduleInboundIdleCleanup(begin.sessionId, INBOUND_SESSION_TIMEOUT_MS)
            Log.d(TAG, "inbound begin buffered session=${begin.sessionId} from=${begin.fromName}")
        }
    }

    private fun onInboundEnd(end: AvaVoiceIncomingMessage) {
        scope.launch {
            delay(INBOUND_END_GRACE_MS)
            val mode = inboundSessions[end.sessionId]?.mode ?: end.mode
            if (mode == AvaVoiceMode.Call) {
                // Live duplex: peers may emit END when an outbound encoder restarts or the mic
                // pipeline rebinds — that must not tear down our inbound speaker track. HANGUP and
                // idle-timeout are the only authoritative call teardown signals.
                Log.d(TAG, "inbound live end ignored session=${end.sessionId} from=${end.fromDeviceId}")
                return@launch
            } else {
                playBufferedInbound(end)
            }
        }
    }

    private fun endRealtimeInbound(end: AvaVoiceIncomingMessage) {
        val session = inboundSessions.remove(end.sessionId)
        if (session == null) {
            Log.w(TAG, "inbound live end without begin session=${end.sessionId}")
            return
        }
        val totalBytes = session.totalBytes.get()
        // NOTE: An END only means this particular stream stopped — NOT that the call is over.
        // In the two-way design a device runs overlapping/duplicate streams that emit END during
        // normal churn, so END must stay non-terminating (emit Ended, ignored by the call UI).
        // Authoritative teardown comes from the retransmitted HANGUP and the idle-timeout fallback.
        val ended = end.copy(
            fromDeviceId = session.fromDeviceId,
            fromName = session.fromName,
            toDeviceId = session.toDeviceId,
            sampleRate = session.sampleRate,
            mode = session.mode,
            totalBytes = totalBytes,
            phase = AvaVoiceIncomingPhase.Ended
        )
        val player = inboundPlayers[session.sessionId]
        player?.markEnded(totalBytes, ended.durationMs)
        AvaVoiceInboundBus.emit(ended)
        scope.launch {
            player?.awaitDrain()
            player?.release()
            inboundPlayers.remove(session.sessionId)
        }
        Log.d(TAG, "inbound live ended session=${end.sessionId} bytes=$totalBytes")
    }

    private fun startInboundCallPlayback(session: InboundSession, notifyUi: Boolean = true) {
        val updated = session.copy(pendingAnswer = false)
        inboundSessions[session.sessionId] = updated
        ensureCallAudioSession()
        val player = AvaVoiceStreamPlayer(
            session.sessionId,
            session.sampleRate,
            playbackGain = AvaVoiceAudioConfig.CALL_PLAYBACK_GAIN
        )
        inboundPlayers[session.sessionId] = player
        if (!player.start()) {
            inboundPlayers.remove(session.sessionId)?.release()
            inboundSessions.remove(session.sessionId)
            maybeLeaveCallAudioSession()
            Log.w(TAG, "inbound call player failed session=${session.sessionId}")
            return
        }
        if (notifyUi) {
            AvaVoiceInboundBus.emit(
                AvaVoiceIncomingMessage(
                    sessionId = session.sessionId,
                    fromDeviceId = session.fromDeviceId,
                    fromName = session.fromName,
                    toDeviceId = session.toDeviceId,
                    sampleRate = session.sampleRate,
                    mode = session.mode,
                    sourceHost = session.sourceHost,
                    phase = AvaVoiceIncomingPhase.Started
                )
            )
        }
        scheduleInboundIdleCleanup(session.sessionId, LIVE_SESSION_IDLE_TIMEOUT_MS)
    }

    /**
     * True when a call response (DECLINE/NO_ANSWER) carries a session ID that no longer
     * matches any active outbound call session toward that peer — i.e. it belongs to a
     * session this device already abandoned during hang-up/start churn. Session ID 0
     * means a legacy peer that doesn't send the field; those are never treated as stale.
     */
    private fun isStaleCallResponse(sessionId: Int, peerDeviceId: String): Boolean {
        if (sessionId == 0) return false
        val session = outboundSessions[sessionId] ?: return true
        return session.mode != AvaVoiceMode.Call || session.targets.none { it.id == peerDeviceId }
    }

    private fun onInboundDecline(decline: AvaVoiceIncomingMessage) {
        if (isStaleCallResponse(decline.sessionId, decline.fromDeviceId)) {
            Log.d(
                TAG,
                "inbound decline ignored — stale session=${decline.sessionId} from=${decline.fromDeviceId}"
            )
            return
        }
        onOutboundCallPeerLeft(
            localDeviceId = decline.toDeviceId,
            peer = AvaVoiceDevice(
                id = decline.fromDeviceId,
                name = decline.fromName,
                host = decline.sourceHost,
                type = AvaVoiceDeviceType.UNKNOWN
            ),
            phase = AvaVoiceIncomingPhase.Declined
        )
        Log.d(TAG, "inbound call peer declined from=${decline.fromDeviceId}")
    }

    private fun onInboundNoAnswer(noAnswer: AvaVoiceIncomingMessage) {
        if (isStaleCallResponse(noAnswer.sessionId, noAnswer.fromDeviceId)) {
            Log.d(
                TAG,
                "inbound no-answer ignored — stale session=${noAnswer.sessionId} from=${noAnswer.fromDeviceId}"
            )
            return
        }
        onOutboundCallPeerLeft(
            localDeviceId = noAnswer.toDeviceId,
            peer = AvaVoiceDevice(
                id = noAnswer.fromDeviceId,
                name = noAnswer.fromName,
                host = noAnswer.sourceHost,
                type = AvaVoiceDeviceType.UNKNOWN
            ),
            phase = AvaVoiceIncomingPhase.NoAnswer
        )
        Log.d(TAG, "inbound call peer no-answer from=${noAnswer.fromDeviceId}")
    }

    private fun onInboundHangup(hangup: AvaVoiceIncomingMessage) {
        val now = System.currentTimeMillis()
        val last = lastHangupFromPeerMs[hangup.fromDeviceId]
        if (last != null && now - last < HANGUP_DEDUP_MS) {
            Log.d(TAG, "inbound call hangup deduped from=${hangup.fromDeviceId}")
            return
        }
        lastHangupFromPeerMs[hangup.fromDeviceId] = now
        if (hangup.hangupSessionIds.isNotEmpty()) {
            val activeForPeer = inboundSessions.entries.filter {
                it.value.mode == AvaVoiceMode.Call && it.value.fromDeviceId == hangup.fromDeviceId
            }
            val hasMatchingSession = activeForPeer.any { it.key in hangup.hangupSessionIds } ||
                outboundSessions.entries.any { (sid, s) ->
                    s.mode == AvaVoiceMode.Call && sid in hangup.hangupSessionIds &&
                        s.targets.any { it.id == hangup.fromDeviceId }
                }
            if (!hasMatchingSession) {
                Log.d(
                    TAG,
                    "inbound call hangup ignored — session IDs ${hangup.hangupSessionIds} " +
                        "don't match any active session from=${hangup.fromDeviceId}"
                )
                return
            }
        }
        onOutboundCallPeerLeft(
            localDeviceId = hangup.toDeviceId,
            peer = AvaVoiceDevice(
                id = hangup.fromDeviceId,
                name = hangup.fromName,
                host = hangup.sourceHost,
                type = AvaVoiceDeviceType.UNKNOWN
            ),
            phase = AvaVoiceIncomingPhase.Hangup
        )
        Log.d(TAG, "inbound call hangup from=${hangup.fromDeviceId} sessions=${hangup.hangupSessionIds}")
    }

    private fun onOutboundCallPeerLeft(
        localDeviceId: String,
        peer: AvaVoiceDevice,
        phase: AvaVoiceIncomingPhase
    ) {
        // Snapshot before teardown: on a peer HANGUP, echo back the session IDs this
        // device is dropping. The peer may hold a leg the original signal predates —
        // e.g. its return-leg BEGIN created a session here after it hung up — and that
        // ghost leg would otherwise ring/linger there for the full ring timeout.
        // Ignored (stale) hangups never reach this method, so echoes cannot loop.
        val endedSessionIds = if (phase == AvaVoiceIncomingPhase.Hangup) {
            findCallSessionIds(localDeviceId, peer.id)
        } else {
            emptyList()
        }
        stopInboundCallStreams(setOf(peer.id))
        disconnectOutboundCallPeer(localDeviceId, peer.id)
        if (_outboundCallConnectedPeerIds.value.isNotEmpty()) {
            AvaVoiceCallRingback.stop()
        }
        if (!hasLiveCallSessions()) {
            maybeLeaveCallDuplex()
        }
        if (endedSessionIds.isNotEmpty() && peer.host.isNotBlank()) {
            val echoPayload = buildVoiceHangup(localDeviceId, peer.id, endedSessionIds)
            scope.launch { AvaVoiceTransport.sendControl(peer.host, echoPayload) }
        }
        AvaVoiceInboundBus.emit(
            AvaVoiceIncomingMessage(
                fromDeviceId = peer.id,
                fromName = peer.name,
                toDeviceId = localDeviceId,
                mode = AvaVoiceMode.Call,
                phase = phase,
                sourceHost = peer.host
            )
        )
    }

    private fun playBufferedInbound(end: AvaVoiceIncomingMessage) {
        val session = inboundSessions.remove(end.sessionId)
        if (session == null) {
            Log.w(TAG, "inbound end without begin session=${end.sessionId}")
            return
        }
        val frames = session.frames.values.toList()
        val playableBytes = session.totalBytes.get()
        if (frames.isEmpty() || playableBytes <= 0L) {
            Log.w(TAG, "inbound end without audio session=${end.sessionId} bytes=$playableBytes")
            return
        }
        AvaVoiceMessageBoard.put(
            AvaVoiceMessageBoardEntry(
                sessionId = session.sessionId,
                sampleRate = session.sampleRate,
                totalBytes = playableBytes,
                durationMs = end.durationMs,
                frames = frames
            )
        )

        scope.launch {
            AvaVoiceInboundBus.resetPlaybackProgress()
            val remainingDelayMs = (session.deliverAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remainingDelayMs > 0L) {
                delay(remainingDelayMs)
            }
            val started = end.copy(
                fromDeviceId = session.fromDeviceId,
                fromName = session.fromName,
                toDeviceId = session.toDeviceId,
                sampleRate = session.sampleRate,
                totalBytes = playableBytes,
                mode = session.mode,
                delayMinutes = session.delayMinutes,
                phase = AvaVoiceIncomingPhase.Started
            )

            if (messageBoardMode.get()) {
                AvaVoiceInboundBus.emit(started)
                AvaVoiceInboundBus.emit(started.copy(phase = AvaVoiceIncomingPhase.Ended))
                Log.d(TAG, "inbound board ready session=${end.sessionId} bytes=$playableBytes")
                return@launch
            }

            val player = AvaVoiceStreamPlayer(session.sessionId, session.sampleRate)
            inboundPlayers[session.sessionId]?.release()
            inboundPlayers[session.sessionId] = player
            if (!player.start()) {
                inboundPlayers.remove(session.sessionId)?.release()
                Log.w(TAG, "buffered player failed session=${session.sessionId}")
                return@launch
            }
            AvaVoiceInboundBus.emit(started)
            player.markEnded(playableBytes, end.durationMs)
            AvaVoiceInboundBus.emit(started.copy(phase = AvaVoiceIncomingPhase.Ended))

            frames.forEach { frame ->
                player.writePcm(frame)
            }
            player.awaitDrain()
            player.release()
            inboundPlayers.remove(session.sessionId)
        }
        Log.d(TAG, "inbound end session=${end.sessionId} bytes=$playableBytes")
    }

    private fun scheduleInboundIdleCleanup(sessionId: Int, timeoutMs: Long) {
        scope.launch {
            delay(timeoutMs)
            val session = inboundSessions[sessionId] ?: return@launch
            val idleMs = System.currentTimeMillis() - session.lastActivityMs.get()
            if (idleMs < timeoutMs) {
                scheduleInboundIdleCleanup(sessionId, timeoutMs)
                return@launch
            }
            // A live call has to survive a network blip. Dropping the session here makes the peer
            // permanently inaudible: resumed packets no longer match a session, and nothing asks
            // the peer to re-send BEGIN. Only reap once this device has left the call as well.
            // [InboundSession.toDeviceId] is this device, so this asks "is our own leg still up?".
            if (session.mode == AvaVoiceMode.Call &&
                hasActiveCallOutboundTo(session.toDeviceId, setOf(session.fromDeviceId))
            ) {
                scheduleInboundIdleCleanup(sessionId, timeoutMs)
                return@launch
            }
            inboundSessions.remove(sessionId)
            inboundPlayers.remove(sessionId)?.release()
            if (session.mode == AvaVoiceMode.Call) {
                // Emit Ended (non-terminating) rather than Hangup: a stale/duplicate inbound stream
                // that never received audio would otherwise idle-out and falsely hang up a live call.
                AvaVoiceInboundBus.emit(
                    AvaVoiceIncomingMessage(
                        sessionId = session.sessionId,
                        fromDeviceId = session.fromDeviceId,
                        fromName = session.fromName,
                        toDeviceId = session.toDeviceId,
                        sampleRate = session.sampleRate,
                        mode = session.mode,
                        phase = AvaVoiceIncomingPhase.Ended
                    )
                )
            }
            Log.w(TAG, "inbound session idle timeout session=$sessionId mode=${session.mode}")
        }
    }

    /**
     * Supervises [audioReceiveSession]. A dead audio socket is invisible from the outside: the call
     * UI stays up, control signaling keeps working, and the peer simply goes silent forever.
     * Rebuild until the hub stops.
     */
    private suspend fun audioListenLoop() {
        while (running.get() && scope.isActive) {
            val clean = audioReceiveSession()
            if (!running.get() || !scope.isActive) return
            if (!clean) delay(LISTEN_RETRY_MS)
        }
    }

    /** @return true when the loop exited because the hub stopped, false on error. */
    private suspend fun audioReceiveSession(): Boolean {
        var socket: DatagramSocket? = null
        try {
            // SO_REUSEADDR only takes effect on an unbound socket, so bind explicitly — otherwise
            // a socket lingering from a previous hub instance blocks the audio port for good.
            socket = DatagramSocket(null as SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(AvaVoiceProtocol.AUDIO_PORT))
            }
            audioSocket.getAndSet(socket)?.let { old ->
                if (old !== socket) runCatching { old.close() }
            }
            val buffer = ByteArray(2048)
            while (running.get() && scope.isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val decoded = AvaVoicePacket.decode(packet.data, packet.length) ?: continue
                val session = inboundSessions[decoded.sessionId] ?: continue
                if (session.mode != AvaVoiceMode.Call && !AvaVoiceNetwork.isReceiveEnabled()) continue
                if ((decoded.flags and AvaVoicePacket.FLAG_DATA) == 0) continue
                session.lastActivityMs.set(System.currentTimeMillis())
                if (session.mode == AvaVoiceMode.Call) {
                    if (session.pendingAnswer) continue
                    // Live calls must run indefinitely, so we neither retain frames nor apply the
                    // buffered byte cap (which previously silenced a call after ~60s of audio).
                    // A cheap consecutive-sequence guard drops immediate duplicates.
                    if (decoded.sequence == session.lastSequence) continue
                    session.lastSequence = decoded.sequence
                    val pcm = if (decoded.format == AvaVoicePacket.FORMAT_OPUS) {
                        val decoder = session.opusDecoder
                        if (decoder == null) {
                            Log.w(TAG, "opus packet without decoder session=${decoded.sessionId}")
                            continue
                        }
                        decoder.decode(decoded.payload).also { decodedPcm ->
                            if (decodedPcm.isEmpty()) {
                                Log.w(TAG, "opus decode empty seq=${decoded.sequence} session=${decoded.sessionId}")
                            }
                        }
                    } else {
                        decoded.payload
                    }
                    if (pcm.isEmpty()) continue
                    session.totalBytes.addAndGet(pcm.size.toLong())
                    val player = inboundPlayers[decoded.sessionId] ?: continue
                    val decoder = session.opusDecoder
                    if (decoder != null && decoded.format == AvaVoicePacket.FORMAT_OPUS) {
                        decoder.splitIntoFrames(pcm).forEach { frame -> player.writePcm(frame) }
                    } else {
                        player.writePcm(pcm)
                    }
                } else {
                    // Buffered messages are bounded to guard against malformed/hostile peers.
                    if (session.totalBytes.get() >= MAX_INBOUND_SESSION_BYTES) continue
                    if (session.frames.putIfAbsent(decoded.sequence, decoded.payload) == null) {
                        session.totalBytes.addAndGet(decoded.payload.size.toLong())
                    }
                }
            }
            return true
        } catch (e: Exception) {
            if (running.get()) {
                Log.w(TAG, "audio listen error: ${e.javaClass.simpleName}: ${e.message} — retrying")
            }
            return false
        } finally {
            audioSocket.compareAndSet(socket, null)
            runCatching { socket?.close() }
        }
    }
}
