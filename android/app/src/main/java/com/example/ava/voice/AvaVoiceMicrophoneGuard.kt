package com.example.ava.voice

import com.example.ava.services.VoiceSatelliteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Mutual exclusion between voice-message capture and wake-word / assistant microphone. */
object AvaVoiceMicrophoneGuard {
    /** Time for the wake-word mic pipeline to close before PTT [AudioRecord] opens. */
    private const val MIC_HANDOFF_MS = 120L

    fun isHeld(): Boolean = VoiceSatelliteService.getInstance()?.isVoiceMessageMicHeld() == true

    fun tryAcquire(): MicAcquireResult {
        val service = VoiceSatelliteService.getInstance()
            ?: return MicAcquireResult.ServiceUnavailable
        if (service.isVoiceAssistantPipelineActive()) {
            return MicAcquireResult.AssistantActive
        }
        return if (service.acquireMicrophoneForVoiceMessage()) {
            MicAcquireResult.Granted
        } else {
            MicAcquireResult.Busy
        }
    }

    /**
     * Branch: outbound call / message we started. Yield assistant capture, then
     * wait for the HAL. TTS and the remote seat stay; hang-up restores wake.
     * Manual PTT while the assistant is live still hits [tryAcquire] busy.
     */
    suspend fun yieldAssistantMicForVoiceSession() {
        withContext(Dispatchers.Main.immediate) {
            VoiceSatelliteService.getInstance()?.yieldMicrophoneForVoiceCall()
        }
        awaitPipelineHandoff()
    }

    /** Wait until the satellite microphone pipeline has released the hardware. */
    suspend fun awaitPipelineHandoff() {
        delay(MIC_HANDOFF_MS)
    }

    /**
     * Restore wake-word / assistant microphone after voice message capture ends.
     * Must run only after [AvaVoiceStreamRecorder] has fully stopped.
     */
    suspend fun releaseSmoothly() {
        delay(MIC_HANDOFF_MS)
        withContext(Dispatchers.Main.immediate) {
            VoiceSatelliteService.getInstance()?.releaseMicrophoneForVoiceMessage()
        }
    }

    fun release() {
        VoiceSatelliteService.getInstance()?.releaseMicrophoneForVoiceMessage()
    }

    fun forceRelease() {
        VoiceSatelliteService.getInstance()?.forceReleaseVoiceMessageMicrophone()
    }

    /** Stop an active stream (if any) and always restore the satellite microphone. */
    suspend fun finishVoiceMessageMic(sessionId: Int) {
        if (sessionId != 0) {
            AvaVoiceMessenger.stopVoiceStream(sessionId)
        }
        if (isHeld()) {
            releaseSmoothly()
        }
    }
}

sealed interface MicAcquireResult {
    data object Granted : MicAcquireResult
    data object AssistantActive : MicAcquireResult
    data object ServiceUnavailable : MicAcquireResult
    data object Busy : MicAcquireResult
}
