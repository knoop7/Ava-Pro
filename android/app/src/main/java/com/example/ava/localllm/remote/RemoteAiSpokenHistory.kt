package com.example.ava.localllm.remote

/**
 * Voice history is spoken turns only. Host notes and tool JSON stay out —
 * they pull the next reply into English and make the model ramble.
 */
internal object RemoteAiSpokenHistory {

    fun keepAssistant(text: String): String? {
        val spoken = RemoteAiThinkFilter.spoken(text)
        if (spoken.isEmpty()) return null
        if (spoken.startsWith("{") || spoken.startsWith("[")) return null
        if (HOST_STUBS.any { spoken.startsWith(it) }) return null
        return spoken
    }

    private val HOST_STUBS = listOf(
        "This turn used the browser.",
        "The page/conversation text was compacted.",
        "Host execution records",
        "Host note, not speech",
        "Tool budget exhausted.",
        "Turn budget used up.",
        "The user is continuing an unfinished task.",
    )
}
