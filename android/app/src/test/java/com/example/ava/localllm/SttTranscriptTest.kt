package com.example.ava.localllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttTranscriptTest {
    @Test
    fun fillerIsShortRealRepliesAreNot() {
        assertFalse(SttTranscript.isShort(""))
        assertFalse(SttTranscript.isShort("   "))
        assertTrue(SttTranscript.isShort("ah"))
        assertTrue(SttTranscript.isShort("uh"))
        assertTrue(SttTranscript.isShort("um"))
        assertTrue(SttTranscript.isShort("a"))
        assertFalse(SttTranscript.isShort("hi"))
        assertFalse(SttTranscript.isShort("  HI.  "))
        assertFalse(SttTranscript.isShort("ok"))
        assertFalse(SttTranscript.isShort("yes"))
        assertFalse(SttTranscript.isShort("no"))
        assertFalse(SttTranscript.isShort("hello"))
        assertFalse(SttTranscript.isShort("stop"))
        assertFalse(SttTranscript.isShort("嗯"))
        assertFalse(SttTranscript.isShort("不行"))
        assertFalse(SttTranscript.isShort("继续"))
        assertFalse(SttTranscript.isShort("关灯"))
        assertFalse(SttTranscript.isShort("关一下台灯"))
        assertFalse(SttTranscript.isShort("㐀"))
        assertFalse(SttTranscript.isShort("豈"))
        assertTrue(SttTranscript.isShort("ab"))
    }

    @Test
    fun forDisplayDropsATrailingSentencePeriod() {
        assertEquals("关灯", SttTranscript.forDisplay("关灯。"))
        assertEquals("turn on the light", SttTranscript.forDisplay("turn on the light."))
        assertEquals("关灯", SttTranscript.forDisplay("  关灯．  "))
        assertEquals("开灯？", SttTranscript.forDisplay("开灯？"))
    }
}
