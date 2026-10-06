package com.example.ava.voiceprint

data class VoicePrintResult(
    val state: VoicePrintState,
    val statusText: String = state.statusText(),
    val confidence: Float = 0f,
    val quality: Float = 0f,
    val userScores: FloatArray = floatArrayOf(0f, 0f),
) {
    companion object {
        fun disabled() = VoicePrintResult(VoicePrintState.Disabled)
    }
}
