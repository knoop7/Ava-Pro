package com.example.ava.localllm

/**
 * Final STT used for a turn. The 1–3 / 1–5 length window is only for
 * leftover Latin filler ("ah", "uh"). Real replies in that window still
 * go to the model — "hi", "ok", "不行", "继续". Chinese is never measured
 * by length: two characters is a command.
 */
internal object SttTranscript {
    private const val LATIN_SHORT_MAX = 3

    private val KEEP = setOf(
        "hi", "hey", "yo", "ok", "okay", "yes", "no", "bye",
        "yeah", "yep", "nah", "hello",
        "不行", "好的", "继续", "接着", "繼續", "接著",
    )

    /** Caption / bubble only. Upstream STT often tacks on a sentence period. */
    fun forDisplay(text: String): String = normalize(text)

    fun isShort(text: String): Boolean {
        val spoken = normalize(text)
        if (spoken.isEmpty() || spoken.lowercase() in KEEP || spoken.any(::isHan)) return false
        return spoken.length <= LATIN_SHORT_MAX
    }

    private fun normalize(text: String): String =
        text.trim().trimEnd('.', '。', '．', '!', '！', '?', '？').trim()

    /**
     * BMP Han. [Character.UnicodeScript] is API 24 — loading this class on
     * Android 5–6 throws [NoClassDefFoundError] as soon as STT text is classified.
     */
    private fun isHan(ch: Char): Boolean {
        val c = ch.code
        return c in 0x2E80..0x2FD5 ||
            c in 0x3400..0x4DBF ||
            c in 0x4E00..0x9FFF ||
            c in 0xF900..0xFAFF
    }
}
