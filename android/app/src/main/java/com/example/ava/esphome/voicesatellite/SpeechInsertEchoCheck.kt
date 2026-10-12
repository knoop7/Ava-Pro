package com.example.ava.esphome.voicesatellite

/**
 * Issue #252: the transcript of a turn that a speech insert started. A real
 * interruption is a sentence; Ava's own reply heard back through the mic is a
 * fragment of what it just said ("现在是下午5点21分" → "521"). Either way the
 * session ends instead of answering it or reopening the mic again.
 */
internal object SpeechInsertEchoCheck {

    /** Nothing a person would barge in with: one CJK character, or ≤2 Latin letters / digits. */
    fun isTooShort(transcript: String): Boolean {
        val n = normalize(transcript)
        if (n.isEmpty()) return true
        val cjk = n.count { isCjk(it) }
        return if (cjk == 0) n.length <= 2 else cjk <= 1 && n.length - cjk == 0
    }

    /** The transcript is (a mishearing of) a piece of [reply]. */
    fun echoesReply(transcript: String, reply: String): Boolean {
        val t = normalize(transcript)
        val r = normalize(reply)
        if (t.isEmpty() || r.isEmpty()) return false
        if (r.contains(t)) return true
        // Numbers come back without their units: "5点21分" → "521".
        val tDigits = t.filter { it.isDigit() }
        if (tDigits.isNotEmpty() && tDigits.length * 2 >= t.length &&
            r.filter { it.isDigit() }.contains(tDigits)
        ) {
            return true
        }
        val cjk = t.count { isCjk(it) }
        if (cjk * 2 >= t.length) {
            // Short CJK fragment that is one run of the reply with at most one
            // character misheard. "打开客厅灯" after "客厅灯已经打开了" is not.
            if (t.length > MAX_CJK_ECHO_CHARS) return false
            return longestCommonRun(t, r) * 10 >= t.length * 8
        }
        // Latin: a few words, every one of them in the reply.
        val tWords = words(transcript)
        if (tWords.isEmpty() || tWords.size > MAX_LATIN_ECHO_WORDS) return false
        val rWords = words(reply).toHashSet()
        return tWords.all { it in rWords }
    }

    private fun longestCommonRun(t: String, r: String): Int {
        var best = 0
        for (i in t.indices) {
            for (j in r.indices) {
                var k = 0
                while (i + k < t.length && j + k < r.length && t[i + k] == r[j + k]) k++
                if (k > best) best = k
            }
        }
        return best
    }

    private fun normalize(s: String): String = buildString {
        for (c in s.lowercase()) if (c.isLetterOrDigit()) append(c)
    }

    private fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    private const val MAX_CJK_ECHO_CHARS = 8
    private const val MAX_LATIN_ECHO_WORDS = 4
}
