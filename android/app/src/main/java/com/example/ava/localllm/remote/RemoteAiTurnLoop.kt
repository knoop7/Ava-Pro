package com.example.ava.localllm.remote

import com.example.ava.localllm.HaToolSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider-independent execution: every result is observed before the final spoken reply.
 *
 * The turn is bounded by time, not by a small round count. A provider drop
 * between rounds is re-requested on the spot from the transcript already in
 * [run]'s messages — nothing is redone, the model simply gets asked again.
 * When time runs low the loop stops taking actions and returns a checkpoint;
 * the host starts another slice instead of asking the user to say continue.
 */
internal object RemoteAiTurnLoop {
    data class Outcome(val text: String, val browserOnly: Boolean, val exhausted: Boolean)

    /** Practical cap on model rounds; the time budget is the real boundary. */
    const val MAX_ROUNDS = 48
    /** One round past this is refused and asked again, not executed. */
    const val MAX_TOOL_CALLS = 16
    /** Extra same-request attempts after OkHttp's own transport retries gave up. */
    const val REQUEST_RECOVERIES = 3
    /** Do not start a new action round with less than this left. */
    private const val ROUND_RESERVE_NS = 45_000_000_000L
    /** Give up an in-place retry when less than this remains. */
    private const val FINAL_RESERVE_NS = 12_000_000_000L

    /** Later rounds keep the full tool set. Same-batch mixing is gated in [run], not here. */
    fun catalog(tools: HaToolSet, @Suppress("UNUSED_PARAMETER") browserOnly: Boolean): HaToolSet = tools

    /**
     * Reads with no shared state may run side by side. Writes stay strictly
     * in order so the ledger's "started / returned / not executed" story per
     * slot holds under cancellation; browser calls stay in order because they
     * share one overlay and flip the browser-only phase.
     */
    internal fun parallelSafe(call: RemoteAiClient.Call): Boolean =
        !RemoteAiExecutionLedger.isWrite(call.name, call.arguments) &&
            !call.name.startsWith(AvaBrowserTools.PREFIX) &&
            !call.name.startsWith(AvaPageTools.PREFIX)

    /**
     * A round with too many calls, or repeated ids, is not executed.
     * The transcript keeps a legal batch and the model is asked again.
     */
    private fun rejectOversizedBatch(
        messages: JSONArray,
        reply: RemoteAiClient.Turn,
        browserOnly: Boolean,
        checkpoint: (Boolean) -> Unit,
    ): Boolean {
        val tooMany = reply.calls.size > MAX_TOOL_CALLS
        val duplicateIds = reply.calls.map { it.id }.distinct().size != reply.calls.size
        if (!tooMany && !duplicateIds) return false
        val kept = reply.calls.distinctBy { it.id }.let { unique ->
            if (unique.size > MAX_TOOL_CALLS) unique.take(MAX_TOOL_CALLS) else unique
        }
        val note = if (tooMany) {
            "At most $MAX_TOOL_CALLS tool calls per round. None of these ran. Split the work and call again."
        } else {
            "Tool call ids in one round must be unique. None of these ran. Call again with distinct ids."
        }
        RemoteAiClient.appendAssistant(messages, reply.copy(calls = kept))
        RemoteAiClient.appendToolResults(
            messages,
            kept.map { it to AvaToolCallback.fail("invalid_request", note).toWire() },
        )
        checkpoint(browserOnly)
        return true
    }

    suspend fun run(
        messages: JSONArray,
        tools: HaToolSet,
        initiallyBrowserOnly: Boolean,
        rounds: Int = MAX_ROUNDS,
        request: suspend (HaToolSet, Boolean, Boolean) -> RemoteAiClient.Turn,
        execute: suspend (RemoteAiClient.Call, HaToolSet, Boolean) -> AvaToolCallback.Result,
        ledger: RemoteAiExecutionLedger = RemoteAiExecutionLedger(),
        checkpoint: (Boolean) -> Unit = {},
        deadlineNanos: Long = Long.MAX_VALUE,
        onRecover: (Throwable, Int) -> Unit = { _, _ -> },
        /** Non-null text refuses a tool-less reply and asks the model again. */
        finishGate: ((RemoteAiClient.Turn) -> String?)? = null,
    ): Outcome {
        var browserOnly = initiallyBrowserOnly
        val failures = HashMap<String, Int>()

        fun remaining(): Long = if (deadlineNanos == Long.MAX_VALUE) Long.MAX_VALUE else deadlineNanos - System.nanoTime()

        /** Same request again after a transport drop; the transcript has not moved. */
        suspend fun ask(active: HaToolSet, restricted: Boolean, final: Boolean): RemoteAiClient.Turn {
            var attempt = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    return request(active, restricted, final)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!RemoteAiClient.isRetryableTransport(e) || attempt >= REQUEST_RECOVERIES) throw e
                    attempt++
                    val wait = RemoteAiClient.retryDelayMs(e, attempt)
                    if (remaining() < wait * 1_000_000L + FINAL_RESERVE_NS) throw e
                    onRecover(e, attempt)
                    delay(wait)
                }
            }
        }

        var round = 0
        while (round < rounds) {
            if (remaining() < ROUND_RESERVE_NS) break
            round++
            val reply = ask(catalog(tools, browserOnly), browserOnly, false)
            if (rejectOversizedBatch(messages, reply, browserOnly, checkpoint)) continue
            if (reply.calls.isEmpty()) {
                val note = finishGate?.invoke(reply)?.trim().orEmpty()
                if (note.isNotEmpty()) {
                    RemoteAiClient.appendAssistant(messages, reply.copy(calls = emptyList()))
                    messages.put(JSONObject().put("role", "user").put("content", note))
                    checkpoint(browserOnly)
                    continue
                }
                RemoteAiClient.appendAssistant(messages, reply.copy(calls = emptyList()))
                return Outcome(reply.text, browserOnly, false)
            }
            RemoteAiClient.appendAssistant(messages, reply)
            // Create a protocol-complete batch immediately. Each slot is committed separately.
            RemoteAiClient.appendToolResults(messages, reply.calls.map { it to AvaToolCallback.fail("not_executed", "This call has not run.").toWire() })
            val slots = messages.getJSONObject(messages.length() - 1).getJSONArray("content")
            var browserInThisBatch = false

            fun commit(index: Int, call: RemoteAiClient.Call, result: AvaToolCallback.Result) {
                val signature = RemoteAiExecutionLedger.signature(call)
                if (!result.ok) failures[signature] = (failures[signature] ?: 0) + 1
                if (result.ok && call.name.startsWith(AvaBrowserTools.PREFIX) && call.name != AvaBrowserTools.HIDE) {
                    browserOnly = true
                    browserInThisBatch = true
                }
                slots.getJSONObject(index).put("content", result.toWire())
                val image = result.imagePath?.trim().orEmpty()
                if (image.isNotEmpty()) slots.getJSONObject(index).put("image_path", image)
                else slots.getJSONObject(index).remove("image_path")
                checkpoint(browserOnly)
            }

            /** Host-side verdicts that need no execution; null means the call should run. */
            fun gate(call: RemoteAiClient.Call, active: HaToolSet): AvaToolCallback.Result? {
                val signature = RemoteAiExecutionLedger.signature(call)
                val laterDeviceInBrowserBatch = browserInThisBatch &&
                    !call.name.startsWith(AvaBrowserTools.PREFIX) &&
                    call.name != RemoteAiHaTools.GUIDE
                return when {
                    laterDeviceInBrowserBatch -> AvaToolCallback.fail("stage_restricted", "This batch already opened the browser. Device actions run in the next model round, not this one.")
                    (failures[signature] ?: 0) >= 2 -> AvaToolCallback.fail("repeated_failure", "This call failed twice. Do not repeat it; explain the failure or ask for missing information.")
                    else -> null
                }
            }

            var index = 0
            while (index < reply.calls.size) {
                currentCoroutineContext().ensureActive()
                val call = reply.calls[index]
                if (parallelSafe(call)) {
                    var end = index
                    while (end < reply.calls.size && parallelSafe(reply.calls[end])) end++
                    val active = catalog(tools, browserOnly)
                    val batch = reply.calls.subList(index, end)
                    val results = coroutineScope {
                        batch.map { read ->
                            async {
                                gate(read, active) ?: execute(read, active, browserOnly)
                            }
                        }.awaitAll()
                    }
                    currentCoroutineContext().ensureActive()
                    for ((offset, result) in results.withIndex()) commit(index + offset, batch[offset], result)
                    index = end
                    continue
                }
                val active = catalog(tools, browserOnly)
                val gated = gate(call, active)
                val replay = ledger.replay(call)
                val result = when {
                    gated != null && gated.error?.type == "stage_restricted" -> gated
                    replay != null -> replay
                    gated != null -> gated
                    !ledger.hasCapacity(call) -> AvaToolCallback.fail("execution_history_full", "This task reached its execution-record limit. Stop and ask for a new explicit task; no records were discarded.")
                    else -> {
                        val entry = ledger.begin(call)
                        if (entry != null) slots.getJSONObject(index).put("content", RemoteAiExecutionLedger.uncertain().toWire())
                        checkpoint(browserOnly)
                        val outcome = execute(call, active, browserOnly)
                        currentCoroutineContext().ensureActive()
                        ledger.complete(entry, outcome)
                        outcome
                    }
                }
                commit(index, call, result)
                index++
            }
            // The spoken line and ava_turn often arrive together. Once that call
            // is accepted, that line is the reply — do not drop it and ask again.
            if (finishGate != null &&
                reply.calls.all { it.name == AvaTurnTools.NAME } &&
                reply.text.isNotBlank() &&
                finishGate.invoke(reply).isNullOrBlank()
            ) {
                return Outcome(reply.text, browserOnly, false)
            }
        }
        // Time or rounds ran out. Leave the last tool results in place; the host
        // starts another slice. Do not invent a spoken "continue?" line.
        return Outcome("", browserOnly, true)
    }
}
