package com.example.ava.lyrics

/**
 * Minimal AMLL / Apple-style TTML parser — word-level [LrcLine.words] from `<span>`.
 * Line-level-only TTML (no spans) is ignored here; callers fall back to LRC sources.
 */
object TtmlParser {
    private val P_BLOCK = Regex(
        """<p\s+begin="([^"]+)"\s+end="([^"]+)"[^>]*>(.*?)</p>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val SPAN = Regex(
        """<span\s+begin="([^"]+)"\s+end="([^"]+)"[^>]*>(.*?)</span>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    fun parse(ttml: String): List<LrcLine> {
        if (ttml.isBlank()) return emptyList()
        val lines = ArrayList<LrcLine>()
        for (block in P_BLOCK.findAll(ttml)) {
            val lineBegin = ttmlTimeToMs(block.groupValues[1]) ?: continue
            val inner = block.groupValues[3]
            val words = ArrayList<LrcWord>()
            for (span in SPAN.findAll(inner)) {
                val begin = ttmlTimeToMs(span.groupValues[1]) ?: continue
                val end = ttmlTimeToMs(span.groupValues[2]) ?: continue
                val text = unescapeXml(span.groupValues[3]).trim()
                if (text.isEmpty()) continue
                words.add(LrcWord(begin.coerceAtLeast(0L), end.coerceAtLeast(begin), text))
            }
            if (words.isEmpty()) continue
            val text = words.joinToString("") { it.text }
            if (text.isBlank()) continue
            lines.add(
                LrcLine(
                    timeMs = lineBegin.coerceAtLeast(0L),
                    text = text,
                    words = words,
                ),
            )
        }
        return lines
            .sortedBy { it.timeMs }
            .distinctBy { "${it.timeMs}|${it.text}" }
    }

    /** `mm:ss.xxx`, optional `hh:`, or bare seconds (`27.173`). */
    internal fun ttmlTimeToMs(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (':' !in t) {
            return (t.toDoubleOrNull()?.times(1_000.0)?.toLong())
        }
        val parts = t.split(':')
        if (parts.size < 2) return null
        val secFrac = parts.last().toDoubleOrNull() ?: return null
        val secMs = (secFrac * 1_000.0).toLong()
        return when (parts.size) {
            2 -> {
                val min = parts[0].toLongOrNull() ?: return null
                min * 60_000L + secMs
            }
            else -> {
                val hours = parts[0].toLongOrNull() ?: return null
                val min = parts[1].toLongOrNull() ?: return null
                hours * 3_600_000L + min * 60_000L + secMs
            }
        }
    }

    private fun unescapeXml(text: String): String =
        text
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""\s+"""), " ")
}
