package com.example.ava.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AvaVoiceCallController(
    private val scope: CoroutineScope,
    private val localDeviceId: String,
    private val localDeviceName: String,
    private val targets: List<AvaVoiceDevice>,
    private val onMicBlocked: () -> Unit = {},
    private val onMicReady: () -> Unit = {},
    private val onSessionStarted: (Int) -> Unit = {}
) {
    private var activeSessionId: Int = 0
    private var startJob: Job? = null

    fun startMicrophone() {
        val targetIds = targets.map { it.id }.toSet()
        if (AvaVoiceMessenger.hasActiveCallOutboundTo(localDeviceId, targetIds)) {
            onMicReady()
            return
        }
        if (activeSessionId != 0 || startJob?.isActive == true) return
        when (AvaVoiceMicrophoneGuard.tryAcquire()) {
            MicAcquireResult.Granted -> {
                startJob = scope.launch {
                    val sessionId = AvaVoiceMessenger.startVoiceStream(
                        fromDeviceId = localDeviceId,
                        fromName = localDeviceName,
                        targets = targets,
                        mode = AvaVoiceMode.Call,
                        delayMinutes = 0
                    )
                    if (sessionId == null) {
                        onMicBlocked()
                    } else {
                        activeSessionId = sessionId
                        onSessionStarted(sessionId)
                        onMicReady()
                    }
                }
            }
            MicAcquireResult.AssistantActive,
            MicAcquireResult.Busy,
            MicAcquireResult.ServiceUnavailable -> {
                onMicBlocked()
            }
        }
    }

    fun stopMicrophone() {
        scope.launch {
            startJob?.join()
            startJob = null
            val sessionId = activeSessionId
            activeSessionId = 0
            if (sessionId != 0 &&
                AvaVoiceSessionHub.callSessionServesOtherPeers(sessionId, targets.map { it.id }.toSet())
            ) {
                // Conference handoff: the leg outlived this controller's peer and now serves
                // a joined caller. Teardown (and the mic guard release) belongs to whichever
                // hang-up path ends that call — stopping here would mute the survivors.
                return@launch
            }
            AvaVoiceMicrophoneGuard.finishVoiceMessageMic(sessionId)
        }
    }

    fun hangup() {
        // Hanging up a conference means leaving it entirely: include every live call peer
        // (joined callers are in the hub but not in this controller's targets).
        val peers = LinkedHashMap<String, AvaVoiceDevice>()
        targets.forEach { peers.putIfAbsent(it.id, it) }
        AvaVoiceSessionHub.activeCallPeers().forEach { peers.putIfAbsent(it.id, it) }
        AvaVoiceMessenger.hangupVoiceCall(localDeviceId, peers.values.toList())
    }
}
