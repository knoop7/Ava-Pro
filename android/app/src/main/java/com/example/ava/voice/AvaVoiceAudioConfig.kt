package com.example.ava.voice

import android.media.AudioFormat
import android.media.MediaRecorder

/** PCM voice parameters shared by record, playback, and wire format. */
object AvaVoiceAudioConfig {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BYTES_PER_SAMPLE = 2
    const val FRAME_MS = 20
    const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * BYTES_PER_SAMPLE

    /** Extra capture latency for call noise suppression (user-tolerated; cleaner uplink). */
    const val CALL_NOISE_DELAY_MS = 300

    /** Used for intercom, buffered messages, and live call capture (same mic path everywhere). */
    const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC

    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val PLAYBACK_CHANNEL = AudioFormat.CHANNEL_OUT_MONO

    /** Live-call playback boost — keep moderate to avoid clipping / harsh distortion. */
    const val CALL_PLAYBACK_GAIN = 1.25f

    /** Buffered message / intercom playback boost. */
    const val MESSAGE_PLAYBACK_GAIN = 1.35f

    /** Outbound call waiting tone (ringback) — 2s loop, quiet speaker playback. */
    const val DEFAULT_VOICE_CALL_RINGBACK = "asset:///sounds/voice_call_ringback.wav"
    const val CALL_RINGBACK_VOLUME = 0.2f
}
