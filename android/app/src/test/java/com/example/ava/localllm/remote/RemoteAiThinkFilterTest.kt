package com.example.ava.localllm.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteAiThinkFilterTest {
    @Test fun dropsClosedThinkAndKeepsTheAnswer() {
        assertEquals("灯已打开", RemoteAiThinkFilter.spoken("<think>先看音量再决定</think>灯已打开"))
        assertEquals("It's on", RemoteAiThinkFilter.spoken("<thinking>check the light</thinking>\nIt's on"))
    }

    @Test fun dropsTruncatedThinkSoItIsNotSpoken() {
        assertEquals("", RemoteAiThinkFilter.spoken("<think>还在分析用户是不是要调TTS"))
    }

    @Test fun leavesOrdinarySpeechAlone() {
        assertEquals("I think so", RemoteAiThinkFilter.spoken("I think so"))
        assertEquals("灯开了", RemoteAiThinkFilter.spoken("灯开了"))
    }

    @Test fun dropsMismatchedCaseAndOtherThinkFences() {
        assertEquals("灯已打开", RemoteAiThinkFilter.spoken("<Think>先看音量</think>灯已打开"))
        assertEquals("灯已打开", RemoteAiThinkFilter.spoken("<reasoning>不要读出来</reasoning>灯已打开"))
        assertEquals("灯已打开", RemoteAiThinkFilter.spoken("【思考】先看音量再决定【/思考】灯已打开"))
        assertEquals("It's on", RemoteAiThinkFilter.spoken("[think]check the light[/think]It's on"))
        assertEquals("灯已打开", RemoteAiThinkFilter.spoken("（思考：先看音量）灯已打开"))
        assertEquals("灯已打开", RemoteAiThinkFilter.spoken("<|think|>先看音量<|/think|>灯已打开"))
        assertEquals("灯已打开", RemoteAiThinkFilter.spoken("<|begin_of_thought|>plan<|end_of_thought|>灯已打开"))
        assertEquals("灯已打开", RemoteAiThinkFilter.spoken("◁think▷不要读出来◁/think▷灯已打开"))
        assertEquals("", RemoteAiThinkFilter.spoken("<|think|>还在分析没有答句"))
    }

    @Test fun keepsAnswerAfterCloseFenceWhenOpenTagWasNeverEmitted() {
        assertEquals("灯已打开", RemoteAiThinkFilter.spoken("先看音量再决定</think>灯已打开"))
        assertEquals("当然喜欢", RemoteAiThinkFilter.spoken("The user asks 你喜欢我吗 again\n</think>\n当然喜欢"))
        assertEquals("先看音量再决定", RemoteAiThinkFilter.split("先看音量再决定</think>灯已打开").thought)
    }

    @Test fun treatsToolCallAsImplicitEndOfThink() {
        val split = RemoteAiThinkFilter.split("先看要不要开灯\n<tool_call>{\"name\":\"ha_state\"}</tool_call>")
        assertEquals("", split.spoken)
        assertEquals("先看要不要开灯", split.thought)
        assertEquals("灯已打开", RemoteAiThinkFilter.spoken("想完了<tool_call>x</tool_call>\n灯已打开"))
    }
}
