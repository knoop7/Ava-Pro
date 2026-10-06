package com.example.ava.ui.screens.settings.components

/**
 * Page-aware Voice Stats focus. Drives which sheet sections compose and which
 * settings Flows are collected — do not pile every store into one mega-state.
 */
enum class VoiceStatsFocus {
    /** Voice Config hub — system overview only; Detail pages keep their deep sections. */
    VoiceConfig,
    Wake,
    WakeLibrary,
    Microphone,
    Echo,
    VoicePrint,
    AudioEvent,
    StreamingTts,
    FeedbackAccent,
}

fun VoiceStatsFocus.displayLabel(): String = when (this) {
    VoiceStatsFocus.VoiceConfig -> "Voice Config"
    VoiceStatsFocus.Wake -> "Wake"
    VoiceStatsFocus.WakeLibrary -> "Wake Library"
    VoiceStatsFocus.Microphone -> "Microphone"
    VoiceStatsFocus.Echo -> "Echo"
    VoiceStatsFocus.VoicePrint -> "Voice Print"
    VoiceStatsFocus.AudioEvent -> "Audio Event"
    VoiceStatsFocus.StreamingTts -> "Streaming TTS"
    VoiceStatsFocus.FeedbackAccent -> "Feedback Accent"
}
