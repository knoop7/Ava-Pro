package com.example.ava.localllm.remote

import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolDef
import com.example.ava.settings.RemoteAiKind
import com.example.ava.settings.RemoteAiProfile
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteAiStreamParseTest {
    private fun source(vararg lines: String) = Buffer().writeUtf8(lines.joinToString("\n") + "\n")

    @Test fun claudeStreamCollectsTextAndToolInputFragments() {
        val seen = StringBuilder()
        val turn = RemoteAiClient.streamClaude(
            source(
                """data: {"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":90}}}""",
                """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好的"}}""",
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"，开灯。"}}""",
                """data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"ha_turn_on","input":{}}}""",
                """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"entity_id\":\"客"}}""",
                """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"厅灯\"}"}}""",
                """data: {"type":"message_delta","usage":{"output_tokens":7}}""",
            ),
        ) { seen.append(it) }
        assertEquals("好的，开灯。", seen.toString())
        assertEquals("好的，开灯。", turn.text)
        assertEquals("ha_turn_on", turn.calls.single().name)
        assertEquals("客厅灯", turn.calls.single().arguments.getString("entity_id"))
        assertEquals(100, turn.usage!!.input)
        assertEquals(90, turn.usage!!.cachedInput)
        assertEquals(7, turn.usage!!.output)
    }

    @Test fun openAiStreamReassemblesToolCallsByIndexAndKeepsReasoning() {
        val seen = StringBuilder()
        val turn = RemoteAiClient.streamOpenAi(
            source(
                """data: {"choices":[{"delta":{"reasoning_content":"think "}}]}""",
                """data: {"choices":[{"delta":{"content":"On"}}]}""",
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"ha_turn_on","arguments":"{\"en"}}]}}]}""",
                """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"c2","function":{"name":"ha_state","arguments":"{}"}}]}}]}""",
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"tity_id\":\"a\"}"}}]}}]}""",
                """data: {"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":16}}}""",
                "data: [DONE]",
            ),
        ) { seen.append(it) }
        assertEquals("On", seen.toString())
        assertEquals(listOf("c1", "c2"), turn.calls.map { it.id })
        assertEquals("a", turn.calls[0].arguments.getString("entity_id"))
        assertEquals("think ", turn.reasoning)
        assertEquals(16, turn.usage!!.cachedInput)
    }

    @Test fun responsesStreamUsesCallIdAndDoneArguments() {
        val turn = RemoteAiClient.streamResponses(
            source(
                """data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"ha_turn_on"}}""",
                """data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"entity_id\""}""",
                """data: {"type":"response.function_call_arguments.done","item_id":"fc_1","arguments":"{\"entity_id\":\"x\"}"}""",
                """data: {"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_1","summary":[]}}""",
                """data: {"type":"response.output_text.delta","delta":"ok"}""",
                """data: {"type":"response.completed","response":{"usage":{"input_tokens":5,"output_tokens":2}}}""",
            ),
        ) { }
        assertEquals("ok", turn.text)
        assertEquals("call_1", turn.calls.single().id)
        assertEquals("x", turn.calls.single().arguments.getString("entity_id"))
        assertEquals("rs_1", turn.reasoningItems.single().getString("id"))
        assertEquals(5, turn.usage!!.input)
    }

    @Test fun ollamaNdjsonStreamCarriesToolCallsAndThinking() {
        val turn = RemoteAiClient.streamOllama(
            source(
                """{"message":{"role":"assistant","content":"","thinking":"hm"},"done":false}""",
                """{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"ha_state","arguments":{"entity_id":"a"}}}]},"done":false}""",
                """{"message":{"role":"assistant","content":"done"},"done":true,"prompt_eval_count":3,"eval_count":1}""",
            ),
        ) { }
        assertEquals("done", turn.text)
        assertEquals("hm", turn.reasoning)
        assertEquals("a", turn.calls.single().arguments.getString("entity_id"))
        assertEquals(3, turn.usage!!.input)
    }

    @Test fun openAiStreamDropsChatTemplateThinkFenceFromContent() {
        val turn = RemoteAiClient.streamOpenAi(
            source(
                """data: {"choices":[{"delta":{"content":"<|think|>plan<|/think|>"}}]}""",
                """data: {"choices":[{"delta":{"content":"灯已打开"}}]}""",
                "data: [DONE]",
            ),
        ) { }
        assertEquals("灯已打开", turn.text)
        assertEquals("plan", turn.reasoning)
    }

    @Test fun openAiStreamKeepsAnswerAfterCloseFenceWithoutOpenTag() {
        val turn = RemoteAiClient.streamOpenAi(
            source(
                """data: {"choices":[{"delta":{"content":"先看音量"}}]}""",
                """data: {"choices":[{"delta":{"content":"</think>灯已打开"}}]}""",
                "data: [DONE]",
            ),
        ) { }
        assertEquals("灯已打开", turn.text)
        assertEquals("先看音量", turn.reasoning)
    }

    @Test fun openAiStreamReadsVllmReasoningField() {
        val turn = RemoteAiClient.streamOpenAi(
            source(
                """data: {"choices":[{"delta":{"reasoning":"plan "}}]}""",
                """data: {"choices":[{"delta":{"content":"On"}}]}""",
                "data: [DONE]",
            ),
        ) { }
        assertEquals("On", turn.text)
        assertEquals("plan ", turn.reasoning)
    }

    @Test fun openAiStreamAcceptsPlainJsonWhenHostIgnoresSse() {
        val seen = StringBuilder()
        val turn = RemoteAiClient.streamOpenAi(
            source("""{"choices":[{"message":{"content":"灯已打开","reasoning_content":"ok"}}],"usage":{"prompt_tokens":2,"completion_tokens":1}}"""),
        ) { seen.append(it) }
        assertEquals("灯已打开", seen.toString())
        assertEquals("灯已打开", turn.text)
        assertEquals("ok", turn.reasoning)
        assertEquals(2, turn.usage!!.input)
    }

    @Test fun streamErrorEventsSurfaceAsIo() {
        try {
            RemoteAiClient.streamClaude(source("""data: {"type":"error","error":{"message":"overloaded"}}""")) { }
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("overloaded"))
        }
    }

    @Test fun reasoningRoundTripsPerWire() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
        val call = RemoteAiClient.Call("c1", "ha_state", JSONObject().put("entity_id", "a"))
        RemoteAiClient.appendAssistant(messages, RemoteAiClient.Turn("", listOf(call), reasoning = "cot",
            reasoningItems = listOf(JSONObject().put("type", "reasoning").put("id", "rs_1"))))
        RemoteAiClient.appendToolResults(messages, listOf(call to "{}"))
        val system = RemoteAiPrompt.Text("host", "clock")

        val openAi = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.OPENAI, "t", baseUrl = "https://x.example/v1", model = "m"), system, messages, HaToolSet.empty())
        assertEquals("cot", openAi.getJSONArray("messages").getJSONObject(2).getString("reasoning_content"))
        assertEquals(8192, openAi.getInt("max_tokens"))
        assertFalse(openAi.has("chat_template_kwargs"))

        val official = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.OPENAI, "t", baseUrl = "https://api.openai.com/v1", model = "m"), system, messages, HaToolSet.empty(), stream = true)
        assertTrue(official.has("max_completion_tokens"))
        assertTrue(official.getBoolean("stream"))
        assertTrue(official.has("stream_options"))
        assertFalse(official.has("chat_template_kwargs"))
        val compat = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.OPENAI, "t", baseUrl = "https://x.example/v1", model = "m"), system, messages, HaToolSet.empty(), stream = true)
        assertTrue(compat.getBoolean("stream"))
        assertFalse(compat.has("stream_options"))
        assertFalse(compat.has("chat_template_kwargs"))

        val ollama = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.OLLAMA, "t", baseUrl = "http://localhost:11434", model = "m"), system, messages, HaToolSet.empty(), stream = true)
        assertEquals("cot", ollama.getJSONArray("messages").getJSONObject(2).getString("thinking"))
        assertTrue(ollama.getBoolean("stream"))
        assertFalse(ollama.has("think"))
        val ollamaOff = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.OLLAMA, "t", baseUrl = "http://localhost:11434", model = "m"), system, messages, HaToolSet.empty(), think = false)
        assertFalse(ollamaOff.getBoolean("think"))
        val compatOff = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.OPENAI, "t", baseUrl = "https://x.example/v1", model = "m"), system, messages, HaToolSet.empty(), think = false)
        assertFalse(compatOff.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        val deepSeekOff = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.OPENAI, "t", baseUrl = "https://api.deepseek.com", model = "m"), system, messages, HaToolSet.empty(), think = false)
        assertEquals("disabled", deepSeekOff.getJSONObject("thinking").getString("type"))
        assertFalse(deepSeekOff.has("chat_template_kwargs"))
        val officialOff = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.OPENAI, "t", baseUrl = "https://api.openai.com/v1", model = "m"), system, messages, HaToolSet.empty(), think = false)
        assertFalse(officialOff.has("chat_template_kwargs"))
        assertFalse(officialOff.has("thinking"))

        val responses = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.OPENAI_RESPONSES, "t", baseUrl = "https://api.openai.com/v1", model = "m"), system, messages, HaToolSet.empty())
        val input = responses.getJSONArray("input")
        assertEquals("host\n\nclock", responses.getString("instructions"))
        assertEquals("reasoning", input.getJSONObject(1).getString("type"))
        assertEquals("function_call", input.getJSONObject(2).getString("type"))
        assertEquals("c1", input.getJSONObject(2).getString("call_id"))
        assertEquals("function_call_output", input.getJSONObject(3).getString("type"))

        val claude = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.CLAUDE, "t", model = "m"), system, messages, HaToolSet.empty())
        val assistant = claude.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        for (i in 0 until assistant.length()) assertEquals("tool_use", assistant.getJSONObject(i).getString("type"))
    }

    @Test fun emptyReasoningContentStillRoundTripsWhenToolsAreOn() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
        val call = RemoteAiClient.Call("c1", "ha_state", JSONObject().put("entity_id", "a"))
        RemoteAiClient.appendAssistant(messages, RemoteAiClient.Turn("", listOf(call), reasoning = ""))
        val tools = HaToolSet(listOf(ToolDef("ha_state", "", emptyList())))
        val body = RemoteAiClient.requestBody(
            RemoteAiProfile("t", RemoteAiKind.OPENAI, "t", baseUrl = "https://x.example/v1", model = "m"),
            RemoteAiPrompt.Text("host", "clock"),
            messages,
            tools,
        )
        val assistant = body.getJSONArray("messages").getJSONObject(2)
        assertEquals("", assistant.getString("reasoning_content"))
        assertEquals("c1", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test fun oneOfSchemaOnlyReachesClaude() {
        val params = listOf("level", "step").map {
            com.example.ava.localllm.ToolParam(it, com.example.ava.localllm.ToolParamType.Int(0, 100), "", required = false)
        }
        val tools = HaToolSet(listOf(ToolDef("ava_volume", "", params, argumentCases = listOf(
            com.example.ava.localllm.ToolArgumentCase(fields = setOf("level", "step"), exactlyOne = setOf("level", "step")),
        ))))
        val system = RemoteAiPrompt.Text("host", "clock")
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
        val claude = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.CLAUDE, "t", model = "m"), system, messages, tools)
        assertTrue(claude.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema").has("oneOf"))
        val openAi = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.OPENAI, "t", baseUrl = "https://x.example/v1", model = "m"), system, messages, tools)
        assertFalse(openAi.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters").has("oneOf"))
        assertTrue(openAi.getBoolean("parallel_tool_calls"))
        val responses = RemoteAiClient.requestBody(RemoteAiProfile("t", RemoteAiKind.OPENAI_RESPONSES, "t", model = "m"), system, messages, tools, stream = true)
        val tool = responses.getJSONArray("tools").getJSONObject(0)
        assertEquals("ava_volume", tool.getString("name"))
        assertFalse(tool.getBoolean("strict"))
        assertTrue(responses.getBoolean("stream"))
    }
}
