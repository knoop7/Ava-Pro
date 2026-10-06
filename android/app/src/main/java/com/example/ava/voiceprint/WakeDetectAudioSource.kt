package com.example.ava.voiceprint

/**
 * Which capture tap wake detection listens to. The offline-verify ring must
 * store this same tap — extra-strictness re-scores the clip, so a different
 * buffer (raw vs NS/AEC) is a false reject, not a second opinion.
 */
enum class WakeDetectAudioTap {
    DETECT_TAP,
    STREAM,
    PROCESSED,
    VS_WAKE,
}

object WakeDetectAudioSource {
    fun tap(
        openEngine: Boolean,
        softwareNsOn: Boolean,
        softwareAecOn: Boolean,
        bargeIn: Boolean,
        detectTapAvailable: Boolean,
    ): WakeDetectAudioTap = when {
        openEngine -> when {
            bargeIn && detectTapAvailable -> WakeDetectAudioTap.DETECT_TAP
            softwareNsOn -> WakeDetectAudioTap.STREAM
            bargeIn -> WakeDetectAudioTap.STREAM
            else -> WakeDetectAudioTap.VS_WAKE
        }
        softwareAecOn -> if (bargeIn) {
            if (detectTapAvailable) WakeDetectAudioTap.DETECT_TAP else WakeDetectAudioTap.STREAM
        } else {
            WakeDetectAudioTap.PROCESSED
        }
        else -> WakeDetectAudioTap.STREAM
    }

    fun logLabel(tap: WakeDetectAudioTap): String = when (tap) {
        WakeDetectAudioTap.DETECT_TAP -> "aecDetect"
        WakeDetectAudioTap.STREAM -> "stream"
        WakeDetectAudioTap.PROCESSED, WakeDetectAudioTap.VS_WAKE -> "raw"
    }
}
