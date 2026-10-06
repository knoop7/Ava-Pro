package com.example.ava.localllm.remote

import android.content.Context
import android.util.Log
import com.example.ava.localllm.DeviceIndex
import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.LocalLlmManager
import com.example.ava.settings.LocalLlmPath
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.RemoteAiSettingsStore
import com.example.ava.settings.snapRemoteHistoryTurns
import com.example.ava.settings.remoteAiSettingsStore
import com.example.ava.settings.resolvedPath
import com.example.ava.settings.failoverChain
import com.example.ava.settings.voiceProfile
import com.example.ava.settings.voiceWireKey
import com.example.ava.settings.wireLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Second seat after Home Assistant: a remote model (Claude / OpenAI / Ollama)
 * gets one prompt and one tool schema, then the host runs the calls.
 * Local on-device chat is the last seat, not a tool caller.
 */
class RemoteAiManager private constructor(context: Context) {
    private val app = context.applicationContext
    val settingsStore = RemoteAiSettingsStore(app.remoteAiSettingsStore)
    private val turnMutex = Mutex()
    private val historyLock = Any()
    private val sessionMemory = RemoteAiSessionMemory()
    private val history = mutableListOf<JSONObject>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seatDrops = CopyOnWriteArrayList<() -> Unit>()
    private val wireGeneration = AtomicInteger(0)
    @Volatile
    private var historyWireKey: String? = null

    init {
        // Tokens saved by earlier builds sit in clear until one write seals them.
        scope.launch {
            runCatching { settingsStore.sealSecretsOnce() }
                .onFailure { Log.w(TAG, "sealing stored tokens failed", it) }
        }
        scope.launch {
            var last: String? = null
            settingsStore.getFlow().collect { settings ->
                val key = settings.voiceWireKey()
                if (last != null && last != key) resetWire(settings.wireLabel())
                last = key
            }
        }
    }

    /**
     * The voice seat registers here so a model / host / token change can drop
     * the in-flight job. Does not restart the satellite — only this stream.
     */
    fun registerSeatDrop(drop: () -> Unit): () -> Unit {
        seatDrops += drop
        return { seatDrops.remove(drop) }
    }

    fun isReady(): Boolean {
        val path = LocalLlmManager.get()?.settingsStore?.getCached()?.resolvedPath()
        if (path != LocalLlmPath.REMOTE) return false
        return settingsStore.getCached().voiceProfile() != null
    }

    /** True when the user turned sentence streaming on; the caller then supplies a [RemoteAiSpeechStream]. */
    fun streamingEnabled(): Boolean = settingsStore.getCached().streaming

    /**
     * One user utterance, start to finish. With [speech] the model streams over
     * SSE and the finished answer is handed over whole once the turn settles;
     * the returned text is the same answer, for captions and history.
     */
    internal suspend fun turn(
        text: String,
        caller: suspend (service: String, entityId: String, data: Map<String, Any?>) -> Boolean?,
        speech: RemoteAiSpeechStream? = null,
    ): String? {
        return turnMutex.withLock {
            lastTurnResumable = false
            val closeWeb = settingsStore.getCached().toolsWeb
            val gen = wireGeneration.get()
            try {
                runSlices(text, caller, speech, gen)
            } finally {
                withContext(NonCancellable) {
                    if (closeWeb && AvaBrowserTools.closeIfLeft(app)) {
                        Log.i(TAG, "web overlay closed at turn end")
                    }
                }
            }
        }
    }

    private suspend fun runSlices(
        text: String,
        caller: suspend (service: String, entityId: String, data: Map<String, Any?>) -> Boolean?,
        speech: RemoteAiSpeechStream?,
        gen: Int,
    ): String? {
        var slice = 0
        var handoff: SliceHandoff? = null
        while (true) {
            ensureWire(gen)
            slice++
            val deadline = System.nanoTime() + TURN_BUDGET_MS * 1_000_000L
            val progress = TurnProgress()
            try {
                val result = withTimeout(TURN_HARD_LIMIT_MS) {
                    runTurn(text, caller, speech, progress, deadline, startSession = slice == 1, handoff = handoff, gen = gen)
                }
                handoff = result.handoff
                if (result.keepGoing && slice < MAX_SLICES) {
                    Log.i(TAG, "task unfinished after slice $slice; host continuing ${slice + 1}/$MAX_SLICES")
                    continue
                }
                if (result.keepGoing) {
                    lastTurnResumable = true
                    Log.w(TAG, "task still unfinished after $MAX_SLICES slices")
                    val line = result.text?.takeUnless { it.isBlank() }
                        ?: RemoteAiFailSpeak.pick(app, resumable = true)
                    if (speech != null) speech.say(line)
                    return line
                }
                return result.text
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                handoff = progress.handoff ?: handoff
                Log.w(TAG, "slice $slice hit hard limit; host continuing from checkpoint")
                if (slice >= MAX_SLICES) throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                handoff = progress.handoff ?: handoff
                if (!RemoteAiClient.canResumeCheckpoint(e) || slice >= MAX_SLICES) throw e
                Log.w(TAG, "slice $slice dropped (${e.message}); host continuing from checkpoint")
                delay(RemoteAiClient.retryDelayMs(e, slice))
            }
        }
    }

    private class TurnProgress {
        var beganWork = false
        var handoff: SliceHandoff? = null
    }

    private data class SliceHandoff(
        val originalUser: String,
        val messages: JSONArray,
        val ledger: JSONArray,
        val browserOnly: Boolean,
    )

    private data class SliceResult(
        val text: String?,
        val keepGoing: Boolean,
        val handoff: SliceHandoff? = null,
    )

    /**
     * True only when the host had to stop after real work and still has a checkpoint.
     * Silent slices do not set this; FailSpeak must not teach "说继续" as the default ending.
     */
    @Volatile
    var lastTurnResumable: Boolean = false
        private set

    private suspend fun runTurn(
        text: String,
        caller: suspend (service: String, entityId: String, data: Map<String, Any?>) -> Boolean?,
        speech: RemoteAiSpeechStream?,
        progress: TurnProgress,
        deadlineNanos: Long,
        startSession: Boolean,
        handoff: SliceHandoff?,
        gen: Int,
    ): SliceResult {
        val spoken = text.trim()
        if (spoken.isEmpty()) return SliceResult(null, false)
        ensureWire(gen)
        val cached = settingsStore.get()
        val chain = cached.failoverChain()
        var profile = chain.firstOrNull() ?: return SliceResult(null, false)
        val failover = RemoteAiFailover(chain)
        rememberWire(cached.voiceWireKey())
        val turnStarted = System.nanoTime()
        var rounds = 0
        var inputTokens = 0
        var outputTokens = 0
        var cachedTokens = 0
        var toolCalls = 0
        val music = if (cached.toolsMusic) AvaMusicTools.ready() else AvaMusicTools.Ready(false, false)
        val web = cached.toolsWeb && AvaBrowserTools.ready(app)
        val page = AvaPageTools.ready(app)
        val self = cached.toolsSelf && AvaSelfTools.ready()
        val voice = cached.toolsVoice && AvaVoiceTools.ready(app)
        val phone = cached.toolsPhone
        val turnTool = PlayerSettingsStore(app.playerSettingsStore).getCached().enableSmartContinue
        if (turnTool) AvaTurnTools.begin()
        val tools = HaToolSet(
            RemoteAiHaTools.surface(house = cached.toolsHa).tools +
                AvaMusicTools.surface(music).tools +
                AvaBrowserTools.surface(web).tools +
                AvaPageTools.surface(page).tools +
                AvaSelfTools.surface(self, app).tools +
                AvaVoiceTools.surface(app, voice).tools +
                AvaPhoneTools.surface(phone).tools +
                AvaShellTools.surface().tools +
                (if (turnTool) AvaTurnTools.surface().tools else emptyList()),
        )
        var index = LocalLlmManager.get()?.deviceIndexSnapshot() ?: DeviceIndex()
        if (cached.toolsHa && index.size == 0) {
            LocalLlmManager.get()?.refreshTools()
            index = LocalLlmManager.get()?.deviceIndexSnapshot() ?: index
        }
        val keep = snapRemoteHistoryTurns(cached.historyTurns)
        val ctx = AvaToolCallback.context(app)
        val shell = AvaShellTools.ready()
        val env = RemoteAiPrompt.env(app, cached.extraPrompt, haTools = cached.toolsHa,
            musicPlay = music.mass, musicTransport = music.sendspin, webTools = web, pageTools = page, selfTools = self, voiceTools = voice,
            phoneTools = phone, shellTools = shell, turnTools = turnTool)
        val resume = handoff?.let {
            RemoteAiSessionMemory.Resume(it.originalUser, JSONArray(it.messages.toString()), it.browserOnly, true,
                JSONArray(it.ledger.toString()), awaitingContinuation = true)
        } ?: sessionMemory.resumeFor(spoken, keep > 0, web)
        val messages = resume?.messages ?: snapshotHistory(keep)
        if (resume != null) Log.i(TAG, "resuming unfinished task '${resume.originalUser.take(40)}' ledger=${resume.ledger.length()}")
        // A replayed turn already carries this utterance at the tail.
        val tail = messages.optJSONObject(messages.length() - 1)
        if (!(tail != null && tail.optString("role") == "user" && tail.opt("content") == spoken)) {
            messages.put(JSONObject().put("role", "user").put("content", spoken))
        }
        AvaPageTools.rememberUtterance(resume?.originalUser ?: spoken)
        AvaSettingsPoints.rememberUtterance(resume?.originalUser ?: spoken)
        if (startSession) {
            if (web) AvaBrowserTools.beginTurn()
            AvaPhoneTools.beginTurn()
        }
        val ledger = RemoteAiExecutionLedger(resume?.ledger ?: JSONArray())
        val packed = HashMap<String, Int>()
        var browserOnly = resume?.browserOnly == true
        var completed = false
        fun saveHandoff() {
            progress.handoff = SliceHandoff(resume?.originalUser ?: spoken, JSONArray(messages.toString()),
                ledger.snapshot(), browserOnly)
        }
        fun checkpoint(restricted: Boolean) {
            browserOnly = restricted
            saveHandoff()
            sessionMemory.remember(resume?.originalUser ?: spoken, messages, restricted, true, keep > 0,
                ledger.snapshot(), awaitingContinuation = false)
        }
        try {
            checkpoint(browserOnly)
            // A resumed browser follow-up is still on the page. A browser open
            // inside this same utterance is not: the next round may still use
            // house tools, so those instructions stay in the prompt.
            val resumedBrowser = handoff == null && resume?.browserOnly == true
            var turnNudges = 0
            val outcome = RemoteAiTurnLoop.run(messages, tools, resume?.browserOnly == true,
                request = { activeTools, browserOnly, _ ->
                    val base = RemoteAiPrompt.text(if (resumedBrowser) env.forPhase(true) else env)
                    val phase = buildString {
                        append("\n${AvaUiHere.sessionLine(app)}\n")
                        if (web) append("\nBrowser session (host state): ${AvaBrowserTools.sessionState()}\n")
                        if (browserOnly && !resumedBrowser) {
                            append("\nA browser page is open in this turn. Do not mix house, music, self, voice, or phone calls into the same batch as a browser open. Those tools stay available on the next round of this request.\n")
                        }
                        if (resume?.unfinished == true) append("The host is continuing an unfinished task. Do not ask the user. Use the recorded results; unknown writes may already have run. Do not repeat successful or uncertain writes. A replayed result means no new command was sent.\n")
                    }
                    val sink: ((String) -> Unit)? = if (speech == null) null else speech::push
                    val prompt = RemoteAiPrompt.Text(base.stable, base.live + phase)
                    val view = RemoteAiObservationPack.view(messages, packed)
                    val reply = run {
                        while (true) {
                            ensureWire(gen)
                            val active = failover.current() ?: throw RemoteAiFailoverExhausted()
                            profile = active
                            try {
                                return@run RemoteAiClient.chat(active, prompt, view, activeTools, sink, think = cached.thinking)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                if (!RemoteAiClient.isRetryableTransport(e)) throw e
                                when (failover.noteFault()) {
                                    RemoteAiFailover.After.RetrySame ->
                                        delay(RemoteAiClient.retryDelayMs(e, failover.faultsOnCurrent))
                                    RemoteAiFailover.After.Advance ->
                                        Log.w(
                                            TAG,
                                            "continuation stream ${active.wireLabel()} → ${failover.current()?.wireLabel()} after ${RemoteAiFailover.FAULTS} faults",
                                        )
                                    RemoteAiFailover.After.GiveUp -> throw RemoteAiFailoverExhausted(e)
                                }
                            }
                        }
                        throw RemoteAiFailoverExhausted()
                    }
                    progress.beganWork = true
                    rounds++
                    toolCalls += reply.calls.size
                    reply.usage?.let {
                        inputTokens += it.input
                        outputTokens += it.output
                        cachedTokens += it.cachedInput
                    }
                    Log.i(
                        TAG,
                        "round $rounds ${profile.kind.name.lowercase()} ${reply.elapsedMs}ms" +
                            (reply.usage?.let { " in=${it.input} cached=${it.cachedInput} out=${it.output}" } ?: "") +
                            " calls=${reply.calls.size} chars=${reply.text.length}" +
                            (if (sink != null) " stream" else "") +
                            (if (reply.text.isNotEmpty()) " preview='${reply.text.take(48)}'" else ""),
                    )
                    reply
                },
                execute = { remoteCall, activeTools, browserOnly ->
                    val call = AvaToolCallback.fromRemote(remoteCall)
                    val started = System.nanoTime()
                    val result = AvaToolCallback.invoke(activeTools, call, ctx.copy(browserOnly = browserOnly)) { input, toolCtx ->
                        when {
                            input.name.startsWith(AvaSelfTools.PREFIX) -> AvaSelfTools.execute(input, app)
                            input.name.startsWith(AvaBrowserTools.PREFIX) -> AvaBrowserTools.execute(input, app)
                            input.name.startsWith(AvaPageTools.PREFIX) -> AvaPageTools.execute(input, app)
                            input.name.startsWith(AvaVoiceTools.PREFIX) -> AvaVoiceTools.execute(input, app)
                            input.name == AvaTurnTools.NAME -> AvaTurnTools.execute(input)
                            input.name == AvaPhoneTools.NAME -> AvaPhoneTools.execute(input, app)
                            input.name == AvaShellTools.NAME -> AvaShellTools.execute(input, app)
                            input.name.startsWith(AvaMusicTools.PREFIX) -> AvaMusicTools.execute(input, app)
                            else -> RemoteAiHaTools.execute(input, toolCtx, index, caller, app)
                        }
                    }
                    Log.i(TAG, "tool ${call.name} ${(System.nanoTime() - started) / 1_000_000L}ms ok=${result.ok} status=${result.status}" +
                        (result.error?.let { " error=${it.type}" } ?: ""))
                    result
                },
                ledger = ledger,
                checkpoint = ::checkpoint,
                deadlineNanos = deadlineNanos,
                onRecover = { e, attempt ->
                    Log.w(
                        TAG,
                        "provider dropped mid-task (${e.message}); asking again from checkpoint $attempt/${RemoteAiTurnLoop.REQUEST_RECOVERIES} rounds=$rounds " +
                            RemoteAiClient.describeAsk(profile, messages, tools, speech != null),
                    )
                },
                finishGate = if (!turnTool) null else { _ ->
                    when {
                        AvaTurnTools.called() -> null
                        turnNudges >= 2 -> null
                        else -> {
                            turnNudges++
                            AvaTurnTools.NUDGE
                        }
                    }
                },
            )
            val keepGoing = outcome.exhausted
            sessionMemory.remember(resume?.originalUser ?: spoken, messages, outcome.browserOnly,
                keepGoing || ledger.hasUncertainWrites(), keep > 0, ledger.snapshot(),
                awaitingContinuation = keepGoing)
            completed = true
            lastTurnResumable = false
            browserOnly = outcome.browserOnly
            saveHandoff()
            if (!keepGoing) {
                AvaPageTools.onTurnFinished(app)
                AvaSettingsPoints.onTurnFinished()
                rememberTurn(spoken, RemoteAiSpokenHistory.keepAssistant(outcome.text), keep)
                if (speech != null && outcome.text.isNotBlank()) speech.say(outcome.text)
            }
            Log.i(
                TAG,
                "slice done ${(System.nanoTime() - turnStarted) / 1_000_000L}ms rounds=$rounds tools=$toolCalls" +
                    " in=$inputTokens cached=$cachedTokens out=$outputTokens exhausted=${outcome.exhausted} keepGoing=$keepGoing",
            )
            return SliceResult(outcome.text, keepGoing, progress.handoff)
        } catch (e: Throwable) {
            if (!completed) {
                val wireChanged = e is CancellationException &&
                    e !is kotlinx.coroutines.TimeoutCancellationException &&
                    wireGeneration.get() != gen
                if (wireChanged) {
                    sessionMemory.clear()
                    lastTurnResumable = false
                    Log.i(TAG, "slice dropped; wire changed")
                } else {
                    val worked = rounds > 0 || resume != null
                    if (worked) {
                        saveHandoff()
                        sessionMemory.remember(resume?.originalUser ?: spoken, messages, browserOnly, true, keep > 0,
                            ledger.snapshot(), awaitingContinuation = true)
                    } else {
                        sessionMemory.clear()
                    }
                    lastTurnResumable = worked && RemoteAiClient.canResumeCheckpoint(e)
                    Log.i(TAG, "slice left unfinished after $rounds rounds, $toolCalls tools; resumable=$lastTurnResumable")
                }
            }
            throw e
        }
    }

    private fun rememberWire(key: String) {
        synchronized(historyLock) {
            if (historyWireKey != key) {
                history.clear()
                sessionMemory.clear()
                historyWireKey = key
            }
        }
    }

    private fun ensureWire(gen: Int) {
        if (wireGeneration.get() != gen) throw CancellationException("remote AI wire changed")
    }

    /**
     * Drop the in-flight stream and forget the unfinished checkpoint so the
     * next ask uses the new model from a blank transcript. Not a satellite restart.
     */
    private fun resetWire(label: String) {
        val gen = wireGeneration.incrementAndGet()
        synchronized(historyLock) {
            history.clear()
            sessionMemory.clear()
            historyWireKey = null
        }
        lastTurnResumable = false
        RemoteAiClient.evictConnections()
        Log.i(TAG, "wire reset gen=$gen $label")
        for (drop in seatDrops) {
            runCatching { drop() }
                .onFailure { Log.w(TAG, "seat drop failed", it) }
        }
    }

    private fun snapshotHistory(keepPairs: Int): JSONArray = synchronized(historyLock) {
        trimLocked(keepPairs)
        val out = JSONArray()
        for (item in history) {
            out.put(JSONObject(item.toString()))
        }
        out
    }

    private fun rememberTurn(user: String, assistant: String?, keepPairs: Int) {
        if (user.isBlank() || assistant.isNullOrBlank() || keepPairs <= 0) return
        synchronized(historyLock) {
            history += JSONObject().put("role", "user").put("content", user)
            history += JSONObject().put("role", "assistant").put("content", assistant)
            trimLocked(keepPairs)
        }
    }

    private fun trimLocked(keepPairs: Int) {
        val max = keepPairs.coerceAtLeast(0) * 2
        while (history.size > max) {
            history.removeAt(0)
        }
    }

    companion object {
        private const val TAG = "RemoteAiManager"
        /** Long tasks (research, app driving, a chain of writes) get this before the host starts a silent slice. */
        private const val TURN_BUDGET_MS = 10 * 60_000L
        private const val TURN_HARD_LIMIT_MS = TURN_BUDGET_MS + 60_000L
        private const val MAX_SLICES = 8

        @Volatile
        private var instance: RemoteAiManager? = null

        fun getInstance(context: Context): RemoteAiManager =
            instance ?: synchronized(this) {
                instance ?: RemoteAiManager(context).also { instance = it }
            }

        fun get(): RemoteAiManager? = instance
    }
}
