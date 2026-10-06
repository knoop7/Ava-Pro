package com.example.ava.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserInterludeTest {

    private fun sung(timeMs: Long, text: String = "词") = LrcLine(timeMs, text)
    private fun blank(timeMs: Long) = LrcLine(timeMs, "")

    // A 20s instrumental annotated with several blank stamps must read as ONE
    // interlude: head row displayable with the full span, tail stamps hidden.
    @Test
    fun blankRunMergesIntoSingleInterlude() {
        val lines = listOf(
            sung(10_000, "A"),
            blank(20_000),
            blank(22_000),
            blank(24_000),
            sung(40_000, "B"),
        )
        assertEquals(40_000, LrcParser.singingEndMs(lines, 1))
        assertEquals(20_000, LrcParser.interludeSpanMs(lines, 1))
        assertTrue(LrcParser.isDisplayableInterlude(lines, 1))
        assertFalse(LrcParser.isDisplayableInterlude(lines, 2))
        assertFalse(LrcParser.isDisplayableInterlude(lines, 3))
    }

    @Test
    fun subThreeSecondGapStaysHidden() {
        val lines = listOf(sung(10_000, "A"), blank(20_000), sung(22_000, "B"))
        assertFalse(LrcParser.isDisplayableInterlude(lines, 1))
        assertEquals(0, LrcParser.interludeMarkCount(LrcParser.interludeSpanMs(lines, 1)))
    }

    @Test
    fun normalGapIsDisplayableWithMarks() {
        val lines = listOf(sung(10_000, "A"), blank(20_000), sung(30_000, "B"))
        assertTrue(LrcParser.isDisplayableInterlude(lines, 1))
        assertEquals(3, LrcParser.interludeMarkCount(LrcParser.interludeSpanMs(lines, 1)))
    }

    @Test
    fun trailingBlankIsNeverDisplayable() {
        val lines = listOf(sung(10_000, "A"), blank(20_000))
        assertTrue(LrcParser.isTrailingInterlude(lines, 1))
        assertFalse(LrcParser.isDisplayableInterlude(lines, 1))
        // Trailing run: every member hidden.
        val run = listOf(sung(10_000, "A"), blank(20_000), blank(30_000))
        assertFalse(LrcParser.isDisplayableInterlude(run, 1))
        assertFalse(LrcParser.isDisplayableInterlude(run, 2))
    }

    // A blank stamp on a sung line's exact time must not survive parse — kept,
    // it shadows the sung line in indexForPosition for the whole window.
    @Test
    fun parseDropsBlankStampDuplicatingSungTime() {
        val raw = """
            [00:10.00]第一句
            [00:20.00]第二句
            [00:20.00]
            [00:30.00]第三句
        """.trimIndent()
        val lines = LrcParser.parse(raw)
        assertEquals(3, lines.size)
        assertTrue(lines.none { it.isInterlude })
        val idx = LrcParser.indexForPosition(lines, 25_000)
        assertEquals("第二句", lines[idx].text)
    }

    // Sung line right before a blank still ends hard at the blank stamp.
    @Test
    fun sungLineEndsAtInterludeStamp() {
        val lines = listOf(sung(10_000, "A"), blank(20_000), sung(40_000, "B"))
        assertEquals(20_000, LrcParser.singingEndMs(lines, 0))
    }

    // Space + comma must synthesize a dwell: the sweep/pan holds flat through
    // their time share instead of racing linearly to the line end.
    private fun longestPlateau(text: String): Int {
        var prev = -1f
        var run = 0
        var longest = 0
        for (step in 0..1000) {
            val f = LrcParser.lineOnlySweepFraction(text, step / 1000f)
            assertTrue("sweep must be monotonic", f >= prev)
            run = if (f == prev) run + 1 else 0
            if (run > longest) longest = run
            prev = f
        }
        return longest
    }

    @Test
    fun sweepDwellsOnSpaceAndComma() {
        // Comma then space merge into one hold (same lit width on both sides).
        assertTrue(longestPlateau("你好， 世界") > 50)
        assertTrue(longestPlateau("AB, CD") > 50)
        // Control: no pause chars → no comparable plateau.
        assertTrue(longestPlateau("你好世界") < 20)
    }
}
