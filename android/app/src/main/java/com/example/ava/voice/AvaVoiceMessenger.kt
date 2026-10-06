package com.example.ava.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object AvaVoiceMessenger {
    private const val TAG = "AvaVoiceMessenger"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSessions = ConcurrentHashMap<Int, SessionMeta>()

    private data class SessionMeta(
        val fromDeviceId: String,
        val fromName: String,
        val targets: List<AvaVoiceDevice>,
        val mode: AvaVoiceMode
    )

    /** True when this device already has a live outbound call stream to any of [targetIds]. */
    fun hasActiveCallOutboundTo(fromDeviceId: String, targetIds: Set<String>): Boolean {
        if (targetIds.isEmpty()) return false
        if (activeSessions.entries.any { (_, meta) ->
                meta.fromDeviceId == fromDeviceId &&
                    meta.mode == AvaVoiceMode.Call &&
                    meta.targets.any { it.id in targetIds }
            }
        ) {
            return true
        }
        return AvaVoiceSessionHub.hasActiveCallOutboundTo(fromDeviceId, targetIds)
    }

    suspend fun startVoiceStream(
        fromDeviceId: String,
        fromName: String,
        targets: List<AvaVoiceDevice>,
        mode: AvaVoiceMode = AvaVoiceMode.Intercom,
        delayMinutes: Int = 0
    ): Int? = withContext(Dispatchers.IO) {
        if (!AvaVoiceNetwork.isFeatureEnabled()) {
            Log.w(TAG, "startVoiceStream blocked: voice message master switch off")
            return@withContext null
        }
        if (mode == AvaVoiceMode.Call) {
            val targetIds = targets.map { it.id }.toSet()
            activeSessions.entries.firstOrNull { (_, meta) ->
                meta.fromDeviceId == fromDeviceId &&
                    meta.mode == AvaVoiceMode.Call &&
                    meta.targets.any { it.id in targetIds }
            }?.let { (sessionId, _) ->
                if (AvaVoiceSessionHub.hasActiveCallOutboundTo(fromDeviceId, targetIds)) {
                    AvaVoiceSessionHub.resetOutboundCallPeerState()
                    return@withContext sessionId
                }
                activeSessions.remove(sessionId)
            }
        }
        AvaVoiceMicrophoneGuard.awaitPipelineHandoff()
        val sessionId = AvaVoiceSessionHub.startOutbound(fromDeviceId, fromName, targets, mode, delayMinutes)
        if (sessionId == null) {
            AvaVoiceMicrophoneGuard.releaseSmoothly()
            return@withContext null
        }
        activeSessions.putIfAbsent(sessionId, SessionMeta(fromDeviceId, fromName, targets, mode))
        sessionId
    }

    suspend fun stopVoiceStream(sessionId: Int): Boolean = withContext(Dispatchers.IO) {
        val meta = activeSessions.remove(sessionId)
        if (meta == null) {
            Log.w(TAG, "stop unknown session=$sessionId")
            if (AvaVoiceMicrophoneGuard.isHeld()) {
                AvaVoiceMicrophoneGuard.releaseSmoothly()
            }
            return@withContext false
        }
        try {
            val ok = AvaVoiceSessionHub.stopOutbound(sessionId)
            Log.d(TAG, "stopped session=$sessionId ok=$ok")
            ok
        } finally {
            AvaVoiceMicrophoneGuard.releaseSmoothly()
        }
    }

    /** Tear down any voice-message/call capture and return the wake-word mic to the satellite. */
    fun abortAllSessionsAndReleaseMic() {
        val sessionIds = activeSessions.keys.toList()
        activeSessions.clear()
        if (sessionIds.isEmpty()) {
            scope.launch {
                AvaVoiceMicrophoneGuard.forceRelease()
            }
            return
        }
        scope.launch {
            sessionIds.forEach { sessionId ->
                runCatching { AvaVoiceSessionHub.stopOutbound(sessionId) }
            }
            AvaVoiceMicrophoneGuard.forceRelease()
        }
    }

    fun hangupVoiceCall(fromDeviceId: String, targets: List<AvaVoiceDevice>) {
        if (!AvaVoiceNetwork.isFeatureEnabled()) return
        // Capture before teardown: the HANGUP wire message carries the session IDs being
        // ended so a peer can reject stale hang-ups during rapid hang-up/start churn.
        val sessionIdsByTarget = AvaVoiceSessionHub.captureCallSessionIds(fromDeviceId, targets)
        AvaVoiceSessionHub.stopInboundCallStreams(targets.map { it.id }.toSet())
        stopLocalCallStreams(fromDeviceId, targets)
        AvaVoiceSessionHub.sendVoiceHangup(fromDeviceId, targets, sessionIdsByTarget)
    }

    internal fun onHubStopped() {
        activeSessions.clear()
        scope.launch {
            AvaVoiceMicrophoneGuard.forceRelease()
        }
    }

    suspend fun stopOutboundSession(sessionId: Int): Boolean = withContext(Dispatchers.IO) {
        activeSessions.remove(sessionId)
        AvaVoiceSessionHub.stopOutbound(sessionId)
    }

    fun updateCallSessionTargets(sessionId: Int, targets: List<AvaVoiceDevice>) {
        activeSessions.computeIfPresent(sessionId) { _, meta -> meta.copy(targets = targets) }
    }

    fun stopLocalCallStreams(fromDeviceId: String, targets: List<AvaVoiceDevice>) {
        val targetIds = targets.map { it.id }.toSet()
        val matched = activeSessions.entries.filter { entry ->
            entry.value.fromDeviceId == fromDeviceId &&
                entry.value.mode == AvaVoiceMode.Call &&
                entry.value.targets.any { it.id in targetIds }
        }
        if (matched.isEmpty()) {
            scope.launch { AvaVoiceMicrophoneGuard.forceRelease() }
            return
        }
        matched.forEach { entry ->
            val sessionId = entry.key
            val leaving = entry.value.targets.filter { it.id in targetIds }
            val remaining = entry.value.targets.filter { it.id !in targetIds }
            if (remaining.isNotEmpty()) {
                // Conference leg still serves other peers: drop only the leaving ones and
                // keep the mic running. The hub syncs activeSessions via
                // updateCallSessionTargets, so no bookkeeping here.
                leaving.forEach { peer ->
                    AvaVoiceSessionHub.disconnectOutboundCallPeer(fromDeviceId, peer.id)
                }
                return@forEach
            }
            activeSessions.remove(sessionId)
            scope.launch {
                try {
                    AvaVoiceSessionHub.stopOutbound(sessionId)
                } finally {
                    AvaVoiceMicrophoneGuard.releaseSmoothly()
                }
            }
        }
    }
}
