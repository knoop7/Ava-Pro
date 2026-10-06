package com.example.ava.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.ava.settings.RecordingPath

/**
 * Resolves a preferred [AudioDeviceInfo] for mic capture from [RecordingPath].
 * Orthogonal to [MediaRecorder.AudioSource] processing mode.
 */
object MicCaptureRouting {
    private const val TAG = "MicCaptureRouting"

    fun resolvePreferredInputDevice(context: Context, path: RecordingPath): AudioDeviceInfo? {
        if (path == RecordingPath.AUTO) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "recordingPath=$path ignored: AudioDeviceInfo needs API 23+")
            return null
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: run {
            Log.w(TAG, "AudioManager unavailable")
            return null
        }
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (inputs.isNullOrEmpty()) {
            Log.w(TAG, "recordingPath=$path: no input devices enumerated")
            return null
        }
        val selected = when (path) {
            RecordingPath.AUTO -> null
            RecordingPath.BUILTIN ->
                inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            RecordingPath.USB ->
                inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                    ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
                    ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
        }
        if (selected == null) {
            Log.w(
                TAG,
                "recordingPath=$path: no matching input " +
                    "(available=${inputs.joinToString { typeName(it.type) }})",
            )
        }
        return selected
    }

    fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        else -> "type_$type"
    }
}
