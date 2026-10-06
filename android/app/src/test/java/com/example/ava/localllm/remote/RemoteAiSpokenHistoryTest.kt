package com.example.ava.localllm.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteAiSpokenHistoryTest {
    @Test fun keepsSpokenRepliesAndDropsHostEnglishStubs() {
        assertEquals("灯开了", RemoteAiSpokenHistory.keepAssistant("灯开了"))
        assertEquals("It's on.", RemoteAiSpokenHistory.keepAssistant("It's on."))
        assertNull(RemoteAiSpokenHistory.keepAssistant(""))
        assertNull(
            RemoteAiSpokenHistory.keepAssistant(
                "This turn used the browser. Continue that browser task to retrieve its results; this record contains no page text.",
            ),
        )
        assertNull(RemoteAiSpokenHistory.keepAssistant("""{"ok":true,"status":"accepted"}"""))
        assertNull(RemoteAiSpokenHistory.keepAssistant("Host note, not speech: execution ledger compacted."))
        assertEquals("灯开了", RemoteAiSpokenHistory.keepAssistant("<think>很长的分析</think>灯开了"))
        assertNull(RemoteAiSpokenHistory.keepAssistant("<think>只有思考</think>"))
        assertEquals("灯开了", RemoteAiSpokenHistory.keepAssistant("<|think|>很长的分析<|/think|>灯开了"))
        assertNull(RemoteAiSpokenHistory.keepAssistant("<|think|>只有思考<|/think|>"))
        assertEquals("灯开了", RemoteAiSpokenHistory.keepAssistant("很长的分析</think>灯开了"))
        assertNull(RemoteAiSpokenHistory.keepAssistant("<think>只有思考"))
        assertNull(RemoteAiSpokenHistory.keepAssistant("先看要不要开灯<tool_call>{\"name\":\"ha_state\"}</tool_call>"))
    }
}
