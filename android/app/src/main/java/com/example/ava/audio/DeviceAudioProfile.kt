package com.example.ava.audio

import android.media.AudioFormat
import android.media.MediaRecorder
import androidx.annotation.StringRes
import com.example.ava.R

data class DeviceAudioProfile(
    val id: String,
    val captureAudioSource: Int,
    val captureSampleRateInHz: Int,
    val captureChannelConfig: Int,
    val outputSampleRateInHz: Int,
    val outputChannelCount: Int,
    @StringRes val displayNameRes: Int
) {
    val requiresProcessing: Boolean
        get() = captureSampleRateInHz != outputSampleRateInHz || captureChannelCount != outputChannelCount

    val captureChannelCount: Int
        get() = when (captureChannelConfig) {
            AudioFormat.CHANNEL_IN_STEREO -> 2
            else -> 1
        }

    companion object {
        val STD_16K_MONO = DeviceAudioProfile(
            id = "std_16k_mono",
            captureAudioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            captureSampleRateInHz = 16000,
            captureChannelConfig = AudioFormat.CHANNEL_IN_MONO,
            outputSampleRateInHz = 16000,
            outputChannelCount = 1,
            displayNameRes = R.string.audio_profile_std_16k_mono
        )

        val DEFAULT = STD_16K_MONO

        val BROADCAST_48K_MONO = DeviceAudioProfile(
            id = "broadcast_48k_mono",
            captureAudioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            captureSampleRateInHz = 48000,
            captureChannelConfig = AudioFormat.CHANNEL_IN_MONO,
            outputSampleRateInHz = 16000,
            outputChannelCount = 1,
            displayNameRes = R.string.audio_profile_broadcast_48k_mono
        )

        val STEREO_INPUT_48K = DeviceAudioProfile(
            id = "stereo_input_48k",
            captureAudioSource = MediaRecorder.AudioSource.MIC,
            captureSampleRateInHz = 48000,
            captureChannelConfig = AudioFormat.CHANNEL_IN_STEREO,
            outputSampleRateInHz = 16000,
            outputChannelCount = 1,
            displayNameRes = R.string.audio_profile_stereo_input_48k
        )

        /** Many smart speakers only expose a live mic at 48 kHz stereo via VOICE_RECOGNITION. */
        val VOICE_RECOGNITION_STEREO_48K = DeviceAudioProfile(
            id = "voice_recognition_stereo_48k",
            captureAudioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            captureSampleRateInHz = 48000,
            captureChannelConfig = AudioFormat.CHANNEL_IN_STEREO,
            outputSampleRateInHz = 16000,
            outputChannelCount = 1,
            displayNameRes = R.string.audio_profile_voice_recognition_stereo_48k
        )

        val LOW_LATENCY_16K = DeviceAudioProfile(
            id = "low_latency_16k",
            captureAudioSource = MediaRecorder.AudioSource.UNPROCESSED,
            captureSampleRateInHz = 16000,
            captureChannelConfig = AudioFormat.CHANNEL_IN_MONO,
            outputSampleRateInHz = 16000,
            outputChannelCount = 1,
            displayNameRes = R.string.audio_profile_low_latency_16k
        )

        val VOICE_CALL_16K = DeviceAudioProfile(
            id = "voice_call_16k",
            captureAudioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            captureSampleRateInHz = 16000,
            captureChannelConfig = AudioFormat.CHANNEL_IN_MONO,
            outputSampleRateInHz = 16000,
            outputChannelCount = 1,
            displayNameRes = R.string.audio_profile_voice_call_16k
        )

        val UNPROCESSED_48K = DeviceAudioProfile(
            id = "unprocessed_48k",
            captureAudioSource = MediaRecorder.AudioSource.UNPROCESSED,
            captureSampleRateInHz = 48000,
            captureChannelConfig = AudioFormat.CHANNEL_IN_MONO,
            outputSampleRateInHz = 16000,
            outputChannelCount = 1,
            displayNameRes = R.string.audio_profile_unprocessed_48k
        )

        val COMPACT_8K = DeviceAudioProfile(
            id = "compact_8k",
            captureAudioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            captureSampleRateInHz = 8000,
            captureChannelConfig = AudioFormat.CHANNEL_IN_MONO,
            outputSampleRateInHz = 16000,
            outputChannelCount = 1,
            displayNameRes = R.string.audio_profile_compact_8k
        )

        val CD_QUALITY_44K = DeviceAudioProfile(
            id = "cd_quality_44k",
            captureAudioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            captureSampleRateInHz = 44100,
            captureChannelConfig = AudioFormat.CHANNEL_IN_MONO,
            outputSampleRateInHz = 16000,
            outputChannelCount = 1,
            displayNameRes = R.string.audio_profile_cd_quality_44k
        )

        val STUDIO_96K = DeviceAudioProfile(
            id = "studio_96k",
            captureAudioSource = MediaRecorder.AudioSource.MIC,
            captureSampleRateInHz = 96000,
            captureChannelConfig = AudioFormat.CHANNEL_IN_MONO,
            outputSampleRateInHz = 16000,
            outputChannelCount = 1,
            displayNameRes = R.string.audio_profile_studio_96k
        )

        val ULTRA_HD_192K = DeviceAudioProfile(
            id = "ultra_hd_192k",
            captureAudioSource = MediaRecorder.AudioSource.MIC,
            captureSampleRateInHz = 192000,
            captureChannelConfig = AudioFormat.CHANNEL_IN_MONO,
            outputSampleRateInHz = 16000,
            outputChannelCount = 1,
            displayNameRes = R.string.audio_profile_ultra_hd_192k
        )

        val ALL_PROFILES = listOf(
            STD_16K_MONO,
            BROADCAST_48K_MONO,
            STEREO_INPUT_48K,
            VOICE_RECOGNITION_STEREO_48K,
            LOW_LATENCY_16K,
            VOICE_CALL_16K,
            UNPROCESSED_48K,
            COMPACT_8K,
            CD_QUALITY_44K,
            STUDIO_96K,
            ULTRA_HD_192K
        )

        fun resolveById(id: String?): DeviceAudioProfile? {
            if (id.isNullOrBlank()) return null
            return ALL_PROFILES.find { it.id == id }
        }

        fun resolve(): DeviceAudioProfile = DEFAULT
    }
}
