package com.example.esphomeproto.api

enum class VoiceAssistantFeature(val flag: Int) {
    VOICE_ASSISTANT(1 shl 0),
    SPEAKER(1 shl 1),
    API_AUDIO(1 shl 2),
    TIMERS(1 shl 3),
    ANNOUNCE(1 shl 4),
    START_CONVERSATION(1 shl 5),
    MULTI_CHANNEL_AUDIO(1 shl 6),
}