package com.example.ava.utils

/**
 * Post-wrap cleanup for the 3-line TTS caption.
 *
 * StaticLayout wraps CJK by glyph, so a page can end on 1–3 leftover
 * characters or a lone `。`. The TextView then centres that stub — or
 * re-wraps the overflow onto the next line. This only moves wrap points.
 * Type size, gravity, and the 3-line plate stay with the caller.
 */
internal object CaptionLineBalance {
    /** Last line this short reads as a leftover, not a line. */
    const val ORPHAN_MAX = 3
    /** Steal down until the short line has at least this many glyphs. */
    const val ORPHAN_MIN_KEEP = 4

    private const val START_FORBIDDEN = "。，、．！？；：,.!?;:」』）】》〉〕］…—"
    private const val END_FORBIDDEN = "「『（【《〈〔［\"'"

    fun balance(
        lines: List<String>,
        measure: (String) -> Float,
        maxWidth: Float,
    ): List<String> {
        if (lines.isEmpty()) return emptyList()
        val out = lines.map { it.trimEnd() }.filter { it.isNotEmpty() }.toMutableList()
        if (out.size <= 1) return out
        val probe = out.first()
        // Dead paint would report 0 and merge every line into one page — the
        // TextView then wraps past maxLines and shows an ellipsis.
        if (measure(probe) <= 0f) return out
        applyKinsoku(out, measure, maxWidth)
        applyOrphans(out, measure, maxWidth)
        return out.filter { it.isNotEmpty() }
    }

    private fun applyKinsoku(
        lines: MutableList<String>,
        measure: (String) -> Float,
        maxWidth: Float,
    ) {
        var i = 1
        while (i < lines.size) {
            val cur = lines[i]
            val lead = leadingForbiddenLen(cur)
            if (lead == 0) {
                i++
                continue
            }
            val punct = cur.substring(0, lead)
            val rest = cur.substring(lead)
            val prev = lines[i - 1]
            val merged = prev + punct
            if (measure(merged) <= maxWidth) {
                lines[i - 1] = merged
                if (rest.isEmpty()) lines.removeAt(i) else lines[i] = rest
                continue
            }
            val (head, tail) = polishSplit(splitFromEnd(prev, 1))
            if (head.isEmpty() || tail.isEmpty()) {
                i++
                continue
            }
            val moved = tail + punct + rest
            if (measure(moved) <= maxWidth) {
                lines[i - 1] = head
                lines[i] = moved
            }
            i++
        }
    }

    private fun applyOrphans(
        lines: MutableList<String>,
        measure: (String) -> Float,
        maxWidth: Float,
    ) {
        var i = lines.lastIndex
        while (i >= 1) {
            val cur = lines[i]
            val units = visibleUnits(cur)
            if (units == 0) {
                lines.removeAt(i)
                i = (i - 1).coerceAtMost(lines.lastIndex)
                continue
            }
            if (units > ORPHAN_MAX) {
                i--
                continue
            }
            val prev = lines[i - 1]
            // Do not glue the orphan back onto the line above. StaticLayout
            // already decided that pair does not fit; merging it makes one
            // long line, one page, and a 4th-line ellipsis in the TextView.
            val maxSteal = (visibleUnits(prev) - ORPHAN_MIN_KEEP).coerceAtLeast(0)
            if (maxSteal <= 0) {
                i--
                continue
            }
            var bestHead: String? = null
            var bestTail: String? = null
            for (steal in 1..maxSteal) {
                val (head, tail) = polishSplit(splitFromEnd(prev, steal))
                if (head.isEmpty() || tail.isEmpty()) continue
                if (visibleUnits(head) < ORPHAN_MIN_KEEP) continue
                val newCur = tail + cur
                if (measure(newCur) > maxWidth) continue
                bestHead = head
                bestTail = newCur
                if (visibleUnits(newCur) >= ORPHAN_MIN_KEEP) break
            }
            if (bestHead != null && bestTail != null) {
                lines[i - 1] = bestHead
                lines[i] = bestTail
            }
            i--
        }
    }

    internal fun visibleUnits(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isWhitespace(cp)) n++
        }
        return n
    }

    internal fun splitFromEnd(s: String, units: Int): Pair<String, String> {
        if (units <= 0 || s.isEmpty()) return s to ""
        var taken = 0
        var i = s.length
        while (i > 0 && taken < units) {
            val cp = s.codePointBefore(i)
            i -= Character.charCount(cp)
            if (!Character.isWhitespace(cp)) taken++
        }
        return s.substring(0, i) to s.substring(i)
    }

    private fun polishSplit(split: Pair<String, String>): Pair<String, String> {
        var head = split.first
        var tail = split.second
        var guard = 0
        while (guard++ < 8 && head.isNotEmpty() && tail.isNotEmpty()) {
            if (endsWithForbidden(head) || startsWithForbidden(tail)) {
                val (h2, last) = splitFromEnd(head, 1)
                if (h2.isEmpty() || last.isEmpty()) break
                head = h2
                tail = last + tail
                continue
            }
            break
        }
        return head to tail
    }

    private fun leadingForbiddenLen(s: String): Int {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (Character.isWhitespace(cp) || !inSet(START_FORBIDDEN, s, i, i + n)) break
            i += n
        }
        return i
    }

    private fun startsWithForbidden(s: String): Boolean {
        if (s.isEmpty()) return false
        val n = Character.charCount(s.codePointAt(0))
        return inSet(START_FORBIDDEN, s, 0, n)
    }

    private fun endsWithForbidden(s: String): Boolean {
        if (s.isEmpty()) return false
        val cp = s.codePointBefore(s.length)
        val n = Character.charCount(cp)
        return inSet(END_FORBIDDEN, s, s.length - n, s.length)
    }

    private fun inSet(set: String, s: String, start: Int, end: Int): Boolean =
        set.contains(s.substring(start, end))
}
