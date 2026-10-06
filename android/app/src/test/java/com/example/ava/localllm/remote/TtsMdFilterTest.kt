package com.example.ava.localllm.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsMdFilterTest {

    @Test
    fun stripsTrailingPeriodAndChineseStop() {
        assertEquals("灯已打开", TtsMdFilter.apply("灯已打开。"))
        assertEquals("The light is on", TtsMdFilter.apply("The light is on."))
    }

    @Test
    fun keepsCommaAndEnumerationPause() {
        assertEquals("红、绿、蓝", TtsMdFilter.apply("红、绿、蓝。"))
        assertEquals("one, two, three", TtsMdFilter.apply("one, two, three."))
        assertEquals("先开灯，再关窗", TtsMdFilter.apply("先开灯，再关窗。"))
    }

    @Test
    fun stripsBoldAndOtherMarkdown() {
        assertEquals("现在播放夜曲", TtsMdFilter.apply("现在播放**夜曲**。"))
        assertEquals("打开客厅灯", TtsMdFilter.apply("__打开__客厅灯。"))
        assertEquals("搜索结果", TtsMdFilter.apply("# 搜索结果"))
        assertEquals("点这里", TtsMdFilter.apply("[点这里](https://example.com)"))
        assertEquals("用 代码 执行", TtsMdFilter.apply("用 `代码` 执行。"))
    }

    @Test
    fun stripsBracketsQuotesAndOtherStops() {
        assertEquals("客厅灯", TtsMdFilter.apply("客厅灯（主灯）"))
        assertEquals("备注已记下", TtsMdFilter.apply("备注已记下！"))
        assertEquals("要继续吗", TtsMdFilter.apply("要继续吗？"))
        assertEquals("引用内容", TtsMdFilter.apply("「引用内容」"))
    }

    @Test
    fun keepsDecimalAndClock() {
        assertEquals("现在25.5度", TtsMdFilter.apply("现在25.5度。"))
        assertEquals("约10:30出发", TtsMdFilter.apply("约10:30出发。"))
    }

    @Test
    fun keepsEnglishApostrophe() {
        assertEquals("I don't know", TtsMdFilter.apply("I don't know."))
    }

    @Test
    fun dropsThinkBlockBeforeSpeaking() {
        assertEquals("灯已打开", TtsMdFilter.apply("<think>先分析该不该调音量</think>灯已打开。"))
        assertEquals("", TtsMdFilter.apply("<think>只有思考没有答句"))
        assertEquals("灯已打开", TtsMdFilter.apply("<|think|>先分析<|/think|>灯已打开。"))
        assertEquals("", TtsMdFilter.apply("<|think|>只有思考没有答句"))
        assertEquals("灯已打开", TtsMdFilter.apply("先分析该不该调音量</think>灯已打开。"))
        assertEquals("灯已打开", TtsMdFilter.apply("<div>灯已打开</div>。"))
    }

    @Test
    fun dropsThinkFencesBeforePunctuationStrip() {
        assertEquals("灯已打开", TtsMdFilter.apply("【思考】先分析该不该调音量【/思考】灯已打开。"))
        assertEquals("灯已打开", TtsMdFilter.apply("[思考]先分析[/思考]灯已打开！"))
        assertEquals("灯已打开", TtsMdFilter.apply("<reasoning>内部推理</reasoning>灯已打开。"))
        assertEquals("It's on", TtsMdFilter.apply("<Think>do not speak this</think>It's on."))
        assertEquals("灯已打开", TtsMdFilter.apply("（思考：先看音量）灯已打开。"))
    }

    @Test
    fun dropsLeadingDisplayJunk() {
        assertEquals("灯已打开", TtsMdFilter.apply("\uFEFF灯已打开。"))
        assertEquals("灯已打开", TtsMdFilter.apply("\u200B灯已打开。"))
        assertEquals("灯已打开", TtsMdFilter.apply("<|im_start|>assistant\n灯已打开。<|im_end|>"))
        assertEquals("灯已打开", TtsMdFilter.apply("|im_start|灯已打开。"))
        assertEquals("灯已打开", TtsMdFilter.apply("[spk:0]灯已打开。"))
        assertEquals("搜索结果", TtsMdFilter.apply("#搜索结果"))
    }
}
