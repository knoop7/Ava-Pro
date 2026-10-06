package com.example.ava.lyrics

/** One timed word/syllable within a [LrcLine] (AMLL TTML / enhanced LRC). */
data class LrcWord(
    val beginMs: Long,
    val endMs: Long,
    val text: String,
)

data class LrcLine(
    val timeMs: Long,
    val text: String,
    /** Non-null when word-level sync is available (karaoke). */
    val words: List<LrcWord>? = null,
) {
    val isWordSynced: Boolean get() = !words.isNullOrEmpty()

    /**
     * Timed blank stamp (`[mm:ss.xx]` with no text) — instrumental / interlude
     * marker, not a sung line. Kept in the timeline so karaoke can end the
     * previous line before the gap instead of crawling through it.
     */
    val isInterlude: Boolean get() = text.isBlank()

    /** Last timed instant on this line (word end or line stamp). */
    fun lastTimedMs(): Long =
        words?.maxOfOrNull { it.endMs } ?: timeMs
}

object LrcParser {
    private val TIME_TAG = Regex("""\[(\d{1,2}):(\d{2})(?:[\.:](\d{1,3}))?\]""")
    /** Standard LRC global offset; positive delays lyrics (timestamps shift later). */
    private val OFFSET_TAG = Regex("""\[offset\s*:\s*([+-]?\d+)\]""", RegexOption.IGNORE_CASE)

    /**
     * Suggested modest lead if a caller *explicitly* wants early line switch
     * (positive = highlight before the LRC stamp). Not applied automatically —
     * [indexForPosition] defaults to 0; the overlay HUD also defaults to 0.
     */
    const val DISPLAY_LEAD_MS = 100L

    fun parse(lrc: String): List<LrcLine> {
        if (lrc.isBlank()) return emptyList()
        val parsed = mutableListOf<LrcLine>()
        var fileOffsetMs = 0L
        for (rawLine in lrc.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val offsetMatch = OFFSET_TAG.find(trimmed)
            if (offsetMatch != null && TIME_TAG.find(trimmed) == null) {
                fileOffsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: fileOffsetMs
                continue
            }
            val matches = TIME_TAG.findAll(trimmed).toList()
            if (matches.isEmpty()) continue
            // Keep blank text — empty stamps are interlude / instrumental markers.
            val text = TIME_TAG.replace(trimmed, "").trim()
            for (match in matches) {
                val timeMs = timestampToMs(match.groupValues)
                if (timeMs >= 0L) {
                    parsed.add(LrcLine((timeMs + fileOffsetMs).coerceAtLeast(0L), text))
                }
            }
        }
        val sorted = parsed
            .sortedBy { it.timeMs }
            .distinctBy { "${it.timeMs}|${it.text}" }
        // A blank stamp sharing a sung line's time is noise, not an interlude —
        // kept, it can shadow the sung line in indexForPosition for its whole window.
        val sungTimes = sorted.filterNot { it.isInterlude }.mapTo(HashSet()) { it.timeMs }
        return sorted.filter { !it.isInterlude || it.timeMs !in sungTimes }
    }

    /**
     * QQ Music (and similar) often return a timed placeholder for non-singable tracks, e.g.
     * 「此歌曲为纯音乐，请您欣赏」 / 「此歌曲为没有填词的口白，请您欣赏」.
     * When every lyric line is that notice, treat as no lyrics.
     */
    fun isInstrumentalOnly(lines: List<LrcLine>): Boolean {
        val sung = lines.filter { !it.isInterlude }
        if (sung.isEmpty()) return false
        return sung.all { isInstrumentalPlaceholderText(it.text) }
    }

    /**
     * End of the **sung** window for [index]: next timeline stamp (sung or
     * interlude). When the next sung line is far and there is no interlude
     * marker, soft-cap by language-aware speech length so karaoke does not
     * crawl through an unmarked instrumental gap.
     *
     * Soft-cap is sweep-only — it does **not** invent a ♫ row. Visible 卡间奏
     * still requires a blank stamp that passes [isDisplayableInterlude] (≥2 marks).
     */
    fun singingEndMs(lines: List<LrcLine>, index: Int): Long {
        if (index !in lines.indices) return 0L
        val line = lines[index]
        if (line.isInterlude) {
            // Consecutive blank stamps are ONE interlude: fill until the next
            // sung stamp, or judging per-fragment hides/duplicates the ♫ row.
            val nextSung = (index + 1..lines.lastIndex)
                .firstOrNull { !lines[it].isInterlude }
            return if (nextSung != null) {
                lines[nextSung].timeMs
            } else {
                line.timeMs + DEFAULT_INTERLUDE_HOLD_MS
            }
        }
        val wordEnd = line.words?.maxOfOrNull { it.endMs }
        if (wordEnd != null && wordEnd > line.timeMs) {
            // Word-sync already bounds the fill; still honor an earlier interlude.
            val nextInterlude = (index + 1..lines.lastIndex)
                .firstOrNull { lines[it].isInterlude }
                ?.let { lines[it].timeMs }
            return if (nextInterlude != null) minOf(wordEnd, nextInterlude) else wordEnd
        }
        val next = lines.getOrNull(index + 1)
        val nextBoundary = next?.timeMs
        if (nextBoundary == null) {
            return line.timeMs + estimatedSingSpanMs(line.text)
        }
        // Explicit interlude stamp → hard end (this is the whole point).
        if (next.isInterlude) return nextBoundary

        val estimated = estimatedSingSpanMs(line.text)
        val gap = nextBoundary - line.timeMs
        // Next sung line is soon enough → normal line spacing.
        if (gap <= estimated + UNMARKED_INTERLUDE_SLACK_MS) {
            return nextBoundary
        }
        // Large gap without a blank stamp → treat the excess as interlude.
        return (line.timeMs + estimated).coerceAtMost(nextBoundary)
    }

    /** Duration of an interlude stamp → next timeline stamp. */
    fun interludeSpanMs(lines: List<LrcLine>, index: Int): Long {
        if (index !in lines.indices) return 0L
        val start = lines[index].timeMs
        val end = singingEndMs(lines, index)
        return (end - start).coerceAtLeast(0L)
    }

    /**
     * How many interlude marks (0..5) for [spanMs].
     * Sub-3s gaps → 0 (too short for a real 卡间奏 — keep timeline, hide ♫).
     * Longer instrumental → 2..5.
     * **Never 1** — a single-note row is unreliable telemetry; skip straight to 0 or ≥2.
     */
    fun interludeMarkCount(spanMs: Long): Int = when {
        spanMs < 3_000L -> 0
        spanMs < 6_000L -> 2
        spanMs < 12_000L -> 3
        spanMs < 24_000L -> 4
        else -> 5
    }

    /** Text marks for an interlude (♪ × count), never emoji. Empty when not displayable. */
    fun interludeDisplayText(spanMs: Long): String {
        val n = interludeMarkCount(spanMs)
        if (n <= 0) return ""
        return INTERLUDE_MARK_CHAR.toString().repeat(n)
    }

    /**
     * Blank stamp after the last sung line (song outro / trailing empty tag).
     * Kept for timeline math; UI must not show ♫ for these.
     */
    fun isTrailingInterlude(lines: List<LrcLine>, index: Int): Boolean {
        if (index !in lines.indices || !lines[index].isInterlude) return false
        return (index + 1..lines.lastIndex).none { !lines[it].isInterlude }
    }

    /**
     * Mid-song blank stamp long enough to show as 卡间奏 (≥2 ♫).
     * Brief gaps and trailing outro stamps stay in the timeline but are not rows.
     */
    fun isDisplayableInterlude(lines: List<LrcLine>, index: Int): Boolean {
        if (index !in lines.indices || !lines[index].isInterlude) return false
        if (isTrailingInterlude(lines, index)) return false
        // Only the head of a blank run paints — continuation stamps stay
        // timeline-only (the head's span already covers the whole gap).
        if (index > 0 && lines[index - 1].isInterlude) return false
        return interludeMarkCount(interludeSpanMs(lines, index)) >= 2
    }

    /**
     * Rough human sing duration for one line (CJK syllable vs Latin unit).
     * Pause glyphs (comma / space / phrase punct) **are** included — they
     * lengthen the soft-cap window so karaoke does not wipe to the end before
     * the breath. [lineOnlySweepFraction] still reshapes progress inside that
     * window (dwell on pauses, advance on sung glyphs).
     */
    fun estimatedSingSpanMs(text: String): Long {
        var cjk = 0
        var latinLetters = 0
        var pauseUnits = 0f
        for (ch in text) {
            when {
                isCjkLyricChar(ch) -> cjk++
                ch.isLetter() -> latinLetters++
                // Same relative units as [lineOnlySweepFraction] time weights.
                isLyricPauseChar(ch) -> pauseUnits += lyricPauseTimeWeight(ch)
                ch.isDigit() -> pauseUnits += CHAR_TIME_OTHER
                else -> pauseUnits += CHAR_TIME_OTHER
            }
        }
        val cjkDominant = cjk >= latinLetters
        val units = cjk + latinLetters / 2f + pauseUnits
        val msPerUnit = if (cjkDominant) 280L else 200L
        val raw = (units * msPerUnit).toLong()
        return raw.coerceIn(MIN_ESTIMATED_SING_MS, MAX_ESTIMATED_SING_MS)
    }

    /**
     * Line-only karaoke: map linear time progress 0..1 → visual sweep 0..1 with
     * synthetic holds on spaces / phrase punctuation (no word-sync timestamps).
     * Sung glyphs advance by visual weight; pause glyphs dwell without crawling.
     */
    fun lineOnlySweepFraction(text: String, progress01: Float): Float {
        val p = progress01.coerceIn(0f, 1f)
        if (text.isEmpty()) return p
        if (p <= 0f) return 0f
        if (p >= 1f) return 1f

        var totalTime = 0f
        var totalVisual = 0f
        val timeW = FloatArray(text.length)
        val visualW = FloatArray(text.length)
        for (i in text.indices) {
            val ch = text[i]
            val tw = lyricCharTimeWeight(ch)
            val vw = lyricCharVisualWeight(ch)
            timeW[i] = tw
            visualW[i] = vw
            totalTime += tw
            totalVisual += vw
        }
        if (totalTime <= 1e-3f || totalVisual <= 1e-3f) return p

        val target = p * totalTime
        var accTime = 0f
        var accVisual = 0f
        for (i in text.indices) {
            val tw = timeW[i]
            val vw = visualW[i]
            val ch = text[i]
            if (target < accTime + tw) {
                val pause = isLyricPauseChar(ch)
                return if (pause) {
                    // Whitespace: hold before the gap (prior syllable fully lit).
                    // Phrase punct: hold with the mark lit (breath after the phrase).
                    val held = if (ch.isWhitespace() || ch == '\u3000') {
                        accVisual
                    } else {
                        accVisual + vw
                    }
                    (held / totalVisual).coerceIn(0f, 1f)
                } else {
                    val local = (target - accTime) / tw.coerceAtLeast(1e-3f)
                    ((accVisual + vw * local) / totalVisual).coerceIn(0f, 1f)
                }
            }
            accTime += tw
            accVisual += vw
        }
        return 1f
    }

    private fun isCjkLyricChar(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0x3040..0x30FF ||
            code in 0xAC00..0xD7AF
    }

    /** True for breaths we synthesize in line-only karaoke (space / phrase punct). */
    private fun isLyricPauseChar(ch: Char): Boolean {
        if (ch.isWhitespace() || ch == '\u3000') return true
        return when (ch) {
            ',', '，', '、',
            '.', '。',
            '!', '！',
            '?', '？',
            ';', '；',
            ':', '：',
            '…', '—', '～', '~',
            -> true
            else -> false
        }
    }

    private fun lyricCharTimeWeight(ch: Char): Float = when {
        isCjkLyricChar(ch) -> CHAR_TIME_CJK
        ch.isLetter() -> CHAR_TIME_LATIN
        else -> lyricPauseTimeWeight(ch)
    }

    private fun lyricPauseTimeWeight(ch: Char): Float = when {
        ch == ' ' || ch == '\t' -> CHAR_TIME_SPACE
        ch == '\u3000' -> CHAR_TIME_IDEOGRAPHIC_SPACE
        ch == ',' || ch == '，' || ch == '、' -> CHAR_TIME_COMMA
        ch == '.' || ch == '。' ||
            ch == '!' || ch == '！' ||
            ch == '?' || ch == '？' ||
            ch == ';' || ch == '；' ||
            ch == ':' || ch == '：' ||
            ch == '…' -> CHAR_TIME_PERIOD
        ch == '—' || ch == '～' || ch == '~' -> CHAR_TIME_DASH
        // Digits / leftover symbols: tiny so they are not free skips.
        else -> CHAR_TIME_OTHER
    }

    /** Relative glyph width for mapping time → sweep head (not font metrics). */
    private fun lyricCharVisualWeight(ch: Char): Float = when {
        isCjkLyricChar(ch) -> CHAR_VISUAL_CJK
        ch.isLetter() || ch.isDigit() -> CHAR_VISUAL_LATIN
        ch == ' ' || ch == '\t' -> CHAR_VISUAL_SPACE
        ch == '\u3000' -> CHAR_VISUAL_IDEOGRAPHIC_SPACE
        isLyricPauseChar(ch) -> CHAR_VISUAL_PUNCT
        else -> CHAR_VISUAL_OTHER
    }

    fun isInstrumentalPlaceholderText(text: String): Boolean {
        val normalized = text
            .trim()
            .replace(" ", "")
            .replace("　", "")
            .replace("，", ",")
            .replace("。", "")
        if (normalized.isEmpty()) return false
        val enjoy = normalized.contains("请您欣赏") || normalized.contains("请欣赏")
        // 「此歌曲为纯音乐，请您欣赏」
        if (normalized.contains("此歌曲为纯音乐")) return true
        if (normalized.contains("纯音乐") && enjoy) return true
        if (normalized.contains("此歌曲") && normalized.contains("纯音乐") && enjoy) return true
        // 「此歌曲为没有填词的口白，请您欣赏」
        if (normalized.contains("没有填词的口白")) return true
        if (normalized.contains("此歌曲") && normalized.contains("口白") && enjoy) return true
        if (normalized.contains("没有填词") && normalized.contains("口白")) return true
        return false
    }

    /**
     * Active line i: lines[i].timeMs <= pos < lines[i+1].timeMs (half-open interval).
     * Returns -1 when [positionMs] is still before the first timed line (no early highlight).
     *
     * [leadMs] advances the playhead for display (positive = earlier line switch).
     * Default 0 — do not invent lead; the overlay applies only the user's HUD offset.
     */
    fun indexForPosition(
        lines: List<LrcLine>,
        positionMs: Long,
        leadMs: Long = 0L,
    ): Int {
        if (lines.isEmpty()) return -1
        val pos = (positionMs + leadMs).coerceAtLeast(0L)
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= pos) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /**
     * Whether timed lyrics plausibly belong to a track of [durationMs].
     *
     * Checks last-line time vs track length (and first→last span). Skips when
     * duration is unknown/short so we never reject for lack of metadata.
     */
    fun fitsTrackDuration(lines: List<LrcLine>, durationMs: Long): Boolean {
        if (lines.isEmpty()) return false
        if (durationMs < MIN_DURATION_FOR_FIT_MS) return true
        val first = lines.minOf { it.timeMs }
        val last = lastTimedMs(lines)
        // Lyrics running well past the track → wrong (often live/extended).
        if (last > durationMs + LAST_LINE_OVERSHOOT_MS) return false
        // Last line ends far too early on a normal-length song → wrong cut/version.
        if (last < durationMs * LAST_LINE_MIN_RATIO_NUM / LAST_LINE_MIN_RATIO_DEN) return false
        // Tiny lyric span vs long track → incomplete / wrong match.
        val span = (last - first).coerceAtLeast(0L)
        if (span < durationMs * SPAN_MIN_RATIO_NUM / SPAN_MIN_RATIO_DEN) return false
        return true
    }

    /** Last meaningful timestamp — word [endMs] when present, else line [timeMs]. */
    fun lastTimedMs(lines: List<LrcLine>): Long =
        lines.maxOf { line ->
            line.words?.maxOfOrNull { it.endMs } ?: line.timeMs
        }

    private fun timestampToMs(groups: List<String>): Long {
        val minutes = groups.getOrNull(1)?.toLongOrNull() ?: return -1L
        val seconds = groups.getOrNull(2)?.toLongOrNull() ?: return -1L
        val fraction = groups.getOrNull(3).orEmpty()
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> (fraction.toLongOrNull() ?: 0L) * 100L
            2 -> (fraction.toLongOrNull() ?: 0L) * 10L
            else -> fraction.toLongOrNull() ?: 0L
        }
        return minutes * 60_000L + seconds * 1_000L + fractionMs
    }

    private const val MIN_DURATION_FOR_FIT_MS = 90_000L
    /** Reject lyrics that run this far past track end (live / extended cuts). */
    private const val LAST_LINE_OVERSHOOT_MS = 12_000L
    /** last >= duration * 55/100 — was 45%; too loose for radio edits. */
    private const val LAST_LINE_MIN_RATIO_NUM = 55L
    private const val LAST_LINE_MIN_RATIO_DEN = 100L
    /** span >= duration * 40/100 */
    private const val SPAN_MIN_RATIO_NUM = 40L
    private const val SPAN_MIN_RATIO_DEN = 100L

    private const val MIN_ESTIMATED_SING_MS = 900L
    private const val MAX_ESTIMATED_SING_MS = 12_000L
    /**
     * If the gap to the next sung line exceeds estimated sing + this slack,
     * soft-cap the **sweep** at the estimate (unmarked instrumental remainder).
     * Does not create blank stamps or ♫ glyphs — only stops the wipe crawling.
     */
    private const val UNMARKED_INTERLUDE_SLACK_MS = 2_500L

    /** Trailing interlude with no next stamp — hold window for mark count / sweep. */
    private const val DEFAULT_INTERLUDE_HOLD_MS = 8_000L

    /** Beamed eighth notes (U+266B) — text symbol, not emoji. */
    private const val INTERLUDE_MARK_CHAR = '\u266B'

    // Line-only karaoke time weights (1.0 ≈ one CJK syllable).
    // Pause weights stay light — dwell, don't steal the sung tempo.
    private const val CHAR_TIME_CJK = 1.0f
    private const val CHAR_TIME_LATIN = 0.5f
    private const val CHAR_TIME_SPACE = 0.22f
    private const val CHAR_TIME_IDEOGRAPHIC_SPACE = 0.25f
    /** Phrase comma / enumeration pause — short breath, not a rest. */
    private const val CHAR_TIME_COMMA = 0.48f
    private const val CHAR_TIME_PERIOD = 0.55f
    private const val CHAR_TIME_DASH = 0.40f
    private const val CHAR_TIME_OTHER = 0.08f

    // Visual weights for sweep-head mapping (pause glyphs stay narrow).
    private const val CHAR_VISUAL_CJK = 1.0f
    private const val CHAR_VISUAL_LATIN = 0.55f
    private const val CHAR_VISUAL_SPACE = 0.28f
    private const val CHAR_VISUAL_IDEOGRAPHIC_SPACE = 0.55f
    private const val CHAR_VISUAL_PUNCT = 0.35f
    private const val CHAR_VISUAL_OTHER = 0.40f
}
