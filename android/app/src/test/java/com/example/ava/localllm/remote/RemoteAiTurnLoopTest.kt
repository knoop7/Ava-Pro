package com.example.ava.localllm.remote

import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolDef
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteAiTurnLoopTest {
    private val tools = HaToolSet(listOf("ava_web_search", "ha_turn_on", "ha_guide").map { ToolDef(it, "", emptyList()) })
    private fun call(name: String, id: String = "1", args: JSONObject = JSONObject()) = RemoteAiClient.Call(id, name, args)

    @Test fun tooManyToolCallsAreRefusedAndTheModelIsAskedAgain() = runBlocking {
        val messages = JSONArray()
        val many = (1..17).map { call("ha_turn_on", it.toString()) }
        var requests = 0
        var executions = 0
        val result = RemoteAiTurnLoop.run(messages, tools, false, rounds = 2,
            request = { _, _, _ ->
                requests++
                if (requests == 1) RemoteAiClient.Turn("", many) else RemoteAiClient.Turn("Split it", emptyList())
            },
            execute = { _, _, _ -> executions++; AvaToolCallback.ok(JSONObject()) })
        assertEquals(0, executions)
        assertEquals(2, requests)
        assertEquals("Split it", result.text)
        val slots = messages.getJSONObject(1).getJSONArray("content")
        assertEquals(RemoteAiTurnLoop.MAX_TOOL_CALLS, slots.length())
        assertEquals("invalid_request", JSONObject(slots.getJSONObject(0).getString("content")).getString("error"))
    }

    @Test fun duplicateToolCallIdsAreRefusedWithoutExecuting() = runBlocking {
        val messages = JSONArray()
        var executions = 0
        val result = RemoteAiTurnLoop.run(messages, tools, false, rounds = 2,
            request = { _, _, _ ->
                if (messages.length() == 0) {
                    RemoteAiClient.Turn("", listOf(call("ha_turn_on", "same"), call("ha_turn_on", "same")))
                } else {
                    RemoteAiClient.Turn("Once", emptyList())
                }
            },
            execute = { _, _, _ -> executions++; AvaToolCallback.ok(JSONObject()) })
        assertEquals(0, executions)
        assertEquals("Once", result.text)
        val wire = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("content")
        assertTrue(JSONObject(wire).getString("message").contains("unique"))
    }

    @Test fun exhaustedRoundLeavesToolResultsAndDoesNotInventSpeech() = runBlocking {
        val messages = JSONArray()
        var requests = 0
        val result = RemoteAiTurnLoop.run(messages, tools, false, rounds = 1,
            request = { _, _, final ->
                requests++
                assertFalse(final)
                RemoteAiClient.Turn("I will turn it on", listOf(call("ha_turn_on")))
            }, execute = { _, _, _ -> AvaToolCallback.ok(JSONObject(), "accepted") })
        assertEquals(1, requests)
        assertTrue(result.exhausted)
        assertEquals("", result.text)
        val wire = messages.getJSONObject(messages.length() - 1).getJSONArray("content").getJSONObject(0).getString("content")
        assertEquals("accepted", JSONObject(wire).getString("status"))
    }

    @Test fun browsingBlocksLaterDeviceCallsInTheSameBatchButLaterRoundsKeepHouseTools() = runBlocking {
        val executed = mutableListOf<String>()
        var request = 0
        val result = RemoteAiTurnLoop.run(JSONArray(), tools, false,
            request = { available, restricted, _ ->
                when (request++) {
                    0 -> RemoteAiClient.Turn("", listOf(call("ava_web_search"), call("ha_turn_on", "2")))
                    1 -> {
                        assertTrue(restricted)
                        assertTrue(available.tools.any { it.name == "ha_guide" })
                        assertTrue(available.tools.any { it.name == "ha_turn_on" })
                        RemoteAiClient.Turn("", listOf(call("ha_turn_on", "3")))
                    }
                    else -> RemoteAiClient.Turn("Done", emptyList())
                }
            }, execute = { call, _, _ -> executed += call.name; AvaToolCallback.ok(JSONObject()) })
        assertEquals(listOf("ava_web_search", "ha_turn_on"), executed)
        assertTrue(result.browserOnly)
        assertEquals("Done", result.text)
    }

    @Test fun readingTheHomeAssistantPageDoesNotEnterBrowserOnly() = runBlocking {
        val pageTools = HaToolSet(listOf("ava_page_read", "ha_turn_on", "ha_guide").map { ToolDef(it, "", emptyList()) })
        var request = 0
        val result = RemoteAiTurnLoop.run(JSONArray(), pageTools, false,
            request = { available, restricted, _ ->
                if (request++ == 0) RemoteAiClient.Turn("", listOf(call("ava_page_read"))) else {
                    assertFalse(restricted)
                    assertTrue(available.tools.any { it.name == "ha_turn_on" })
                    RemoteAiClient.Turn("Kitchen lights are on", emptyList())
                }
            }, execute = { _, _, _ -> AvaToolCallback.ok(JSONObject()) })
        assertFalse(result.browserOnly)
        assertEquals("Kitchen lights are on", result.text)
    }

    @Test fun failedBrowserOperationDoesNotLockUnrelatedTools() = runBlocking {
        var request = 0
        val result = RemoteAiTurnLoop.run(JSONArray(), tools, false,
            request = { available, restricted, _ ->
                if (request++ == 0) RemoteAiClient.Turn("", listOf(call("ava_web_search"))) else {
                    assertFalse(restricted)
                    assertTrue(available.tools.any { it.name == "ha_turn_on" })
                    RemoteAiClient.Turn("Search failed", emptyList())
                }
            }, execute = { _, _, _ -> AvaToolCallback.fail("invalid_request", "missing query") })
        assertFalse(result.browserOnly)
    }

    @Test fun repeatedFailedWriteIsNotExecutedAThirdTimeEvenIfKeysAreReordered() = runBlocking {
        var requests = 0
        var executions = 0
        RemoteAiTurnLoop.run(JSONArray(), tools, false, rounds = 3,
            request = { _, _, final ->
                if (final) RemoteAiClient.Turn("Failed twice", emptyList()) else {
                    val args = if (requests % 2 == 0) JSONObject().put("a", 1).put("b", 2) else JSONObject().put("b", 2).put("a", 1)
                    RemoteAiClient.Turn("", listOf(call("ha_turn_on", (++requests).toString(), args)))
                }
            }, execute = { _, _, _ -> executions++; AvaToolCallback.fail("tool_error", "offline") })
        assertEquals(2, executions)
    }
    @Test fun partialBatchSurvivesCancellationWithoutReplayingEarlierWrites() = runBlocking {
        val messages = JSONArray()
        val ledger = RemoteAiExecutionLedger()
        var checkpointMessages = JSONArray()
        var checkpointLedger = JSONArray()
        val calls = listOf("a", "b", "c").map { id -> call("ha_turn_on", id, JSONObject().put("entity_id", id)) }
        try {
            RemoteAiTurnLoop.run(messages, tools, false, rounds = 1,
                request = { _, _, _ -> RemoteAiClient.Turn("", calls) },
                execute = { call, _, _ ->
                    if (call.id == "b") throw CancellationException("interrupted during second write")
                    AvaToolCallback.ok(JSONObject(), "accepted")
                }, ledger = ledger, checkpoint = {
                    checkpointMessages = JSONArray(messages.toString())
                    checkpointLedger = ledger.snapshot()
                })
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        val slots = checkpointMessages.getJSONObject(checkpointMessages.length() - 1).getJSONArray("content")
        assertEquals("accepted", JSONObject(slots.getJSONObject(0).getString("content")).getString("status"))
        assertEquals("unknown", JSONObject(slots.getJSONObject(1).getString("content")).getString("status"))
        assertEquals("not_executed", JSONObject(slots.getJSONObject(2).getString("content")).getString("error"))
        val executed = mutableListOf<String>()
        RemoteAiTurnLoop.run(checkpointMessages, tools, false, rounds = 1,
            request = { _, _, final -> if (final) RemoteAiClient.Turn("Summary", emptyList()) else RemoteAiClient.Turn("", calls.map { it.copy(id = "new-" + it.id) }) },
            execute = { call, _, _ -> executed += call.arguments.getString("entity_id"); AvaToolCallback.ok(JSONObject(), "accepted") },
            ledger = RemoteAiExecutionLedger(checkpointLedger))
        assertEquals(listOf("c"), executed)
    }

    @Test fun providerFailureAfterWriteLeavesAUsableCheckpoint() = runBlocking {
        val memory = RemoteAiSessionMemory()
        val ledger = RemoteAiExecutionLedger()
        val messages = JSONArray()
        var requests = 0
        try {
            RemoteAiTurnLoop.run(messages, tools, false,
                request = { _, _, _ ->
                    if (requests++ > 0) throw java.io.IOException("provider disconnected")
                    RemoteAiClient.Turn("", listOf(call("ha_turn_on")))
                }, execute = { _, _, _ -> AvaToolCallback.ok(JSONObject(), "accepted") }, ledger = ledger,
                checkpoint = { restricted -> memory.remember("turn on", messages, restricted, true, true, ledger.snapshot(), true) })
            fail("expected provider failure")
        } catch (_: java.io.IOException) { }
        val restored = memory.resumeFor("turn on", true, true)!!
        assertEquals("accepted", restored.ledger.getJSONObject(0).getJSONObject("wire").getString("status"))
    }

    @Test fun unknownWriteIsNotExecutedAgainWithinTheSameTurn() = runBlocking {
        var requests = 0
        var executions = 0
        RemoteAiTurnLoop.run(JSONArray(), tools, false, rounds = 2,
            request = { _, _, final -> if (final) RemoteAiClient.Turn("Unconfirmed", emptyList()) else RemoteAiClient.Turn("", listOf(call("ha_turn_on", (++requests).toString()))) },
            execute = { _, _, _ -> executions++; AvaToolCallback.unknown("No acknowledgement") })
        assertEquals(1, executions)
    }

    @Test fun providerDropMidTaskIsAskedAgainFromTheSameTranscript() = runBlocking {
        val messages = JSONArray()
        var requests = 0
        var executions = 0
        var recovered = 0
        val result = RemoteAiTurnLoop.run(messages, tools, false,
            request = { _, _, _ ->
                requests++
                when (requests) {
                    1 -> RemoteAiClient.Turn("", listOf(call("ha_turn_on")))
                    2 -> throw java.net.SocketException("Connection reset")
                    else -> RemoteAiClient.Turn("Light is on", emptyList())
                }
            },
            execute = { _, _, _ -> executions++; AvaToolCallback.ok(JSONObject(), "accepted") },
            onRecover = { _, _ -> recovered++ })
        assertEquals(1, executions)
        assertEquals(1, recovered)
        assertEquals("Light is on", result.text)
        assertFalse(result.exhausted)
        val tail = messages.getJSONObject(messages.length() - 1).getJSONArray("content").getJSONObject(0)
        assertEquals("Light is on", tail.getString("text"))
    }

    @Test fun fatalProviderErrorIsNotRetried() = runBlocking {
        var requests = 0
        try {
            RemoteAiTurnLoop.run(JSONArray(), tools, false,
                request = { _, _, _ -> requests++; throw java.io.IOException("remote AI HTTP 401") },
                execute = { _, _, _ -> fail("must not execute"); AvaToolCallback.ok() })
            fail("expected failure")
        } catch (_: java.io.IOException) { }
        assertEquals(1, requests)
    }

    @Test fun lowTimeBudgetStopsWithoutAskingTheUser() = runBlocking {
        val result = RemoteAiTurnLoop.run(JSONArray(), tools, false,
            request = { _, _, _ -> fail("must not request"); RemoteAiClient.Turn("", emptyList()) },
            execute = { _, _, _ -> fail("must not execute"); AvaToolCallback.ok() },
            deadlineNanos = System.nanoTime() + 20_000_000_000L)
        assertTrue(result.exhausted)
        assertEquals("", result.text)
    }

    @Test fun noTimeLeftDoesNotInventAContinueQuestion() = runBlocking {
        val messages = JSONArray()
        val result = RemoteAiTurnLoop.run(messages, tools, false,
            request = { _, _, _ -> fail("must not request"); RemoteAiClient.Turn("", emptyList()) },
            execute = { _, _, _ -> fail("must not execute"); AvaToolCallback.ok() },
            deadlineNanos = System.nanoTime() + 1_000_000_000L)
        assertEquals("", result.text)
        assertTrue(result.exhausted)
        assertEquals(0, messages.length())
    }

    @Test fun bareReplyIsRefusedUntilTheFinishGateAllowsIt() = runBlocking {
        val messages = JSONArray()
        var requests = 0
        val result = RemoteAiTurnLoop.run(messages, tools, false, rounds = 3,
            request = { _, _, _ ->
                requests++
                RemoteAiClient.Turn("not yet", emptyList())
            },
            execute = { _, _, _ -> AvaToolCallback.ok(JSONObject()) },
            finishGate = { if (requests < 2) "call ava_turn" else null })
        assertEquals(2, requests)
        assertEquals("not yet", result.text)
        assertEquals("call ava_turn", messages.getJSONObject(1).getString("content"))
    }

    @Test fun spokenLineWithOnlyAvaTurnIsTheReply() = runBlocking {
        AvaTurnTools.begin()
        val turnTools = HaToolSet(tools.tools + AvaTurnTools.surface().tools)
        val result = RemoteAiTurnLoop.run(JSONArray(), turnTools, false,
            request = { _, _, _ ->
                RemoteAiClient.Turn("灯开了", listOf(call(AvaTurnTools.NAME, args = JSONObject().put("continue", true))))
            },
            execute = { remote, _, _ -> AvaTurnTools.execute(AvaToolCallback.fromRemote(remote)) },
            finishGate = { if (AvaTurnTools.called()) null else AvaTurnTools.NUDGE })
        assertEquals("灯开了", result.text)
        assertTrue(AvaTurnTools.keepListening())
    }

}
