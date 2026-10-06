package com.example.ava.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AvaVoiceIncomingPhase {
    /** Incoming live call waiting for user tap-to-answer. */
    Ringing,
    Started,
    Ended,
    Hangup,
    /** Callee declined before answer (caller). */
    Declined,
    /** Callee did not answer within the ring timeout (caller). */
    NoAnswer,
    LegacyStub
}

enum class AvaVoiceMode(val wireValue: String) {
    Intercom("intercom"),
    Call("call");

    companion object {
        fun fromWire(value: String?): AvaVoiceMode =
            entries.firstOrNull { it.wireValue == value } ?: Intercom
    }
}

data class AvaVoiceIncomingMessage(
    val sessionId: Int = 0,
    val fromDeviceId: String,
    val fromName: String,
    val toDeviceId: String,
    val durationMs: Long = 0L,
    val totalBytes: Long = 0L,
    val sampleRate: Int = AvaVoiceAudioConfig.SAMPLE_RATE,
    val mode: AvaVoiceMode = AvaVoiceMode.Intercom,
    val delayMinutes: Int = 0,
    val phase: AvaVoiceIncomingPhase = AvaVoiceIncomingPhase.LegacyStub,
    val sourceHost: String = "",
    val receivedAtMs: Long = System.currentTimeMillis(),
    /**
     * Session IDs carried by HANGUP messages so teardown targets only the
     * matching sessions. Empty means legacy (device-level fallback).
     */
    val hangupSessionIds: List<Int> = emptyList()
)

object AvaVoiceInboundBus {
    private val _messages = MutableSharedFlow<AvaVoiceIncomingMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<AvaVoiceIncomingMessage> = _messages.asSharedFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    fun emit(message: AvaVoiceIncomingMessage) {
        _messages.tryEmit(message)
    }

    fun updatePlaybackProgress(progress: Float) {
        _playbackProgress.value = progress.coerceIn(0f, 1f)
    }

    fun resetPlaybackProgress() {
        _playbackProgress.value = 0f
    }
}
