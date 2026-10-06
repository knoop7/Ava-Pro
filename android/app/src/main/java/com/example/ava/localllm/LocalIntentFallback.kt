package com.example.ava.localllm

import android.content.Context
import android.util.Log
import com.example.ava.esphome.voicesatellite.HaAssistMissDetector
import com.example.ava.esphome.voicesatellite.VoiceSatelliteStateMachine.ReplyDecision
import com.example.ava.esphome.voicesatellite.VoiceSatelliteStateMachine.ReplyStage
import com.example.ava.homeassistant.HaAssistVerdict
import com.example.ava.homeassistant.HaManager
import com.example.ava.homeassistant.HaWsClient
import com.example.ava.R
import com.example.ava.localllm.remote.RemoteAiFailSpeak
import com.example.ava.localllm.remote.RemoteAiSpeechStream
import com.example.ava.localllm.remote.TtsMdFilter
import com.example.ava.ui.AvaToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Glue between one voice run and the remote AI seat.
 *
 * Front / rear guard: HA first. On a stock miss the seat is remote AI (tools).
 * The main miss is Assist's "抱歉，找不到名为 {} 的设备". Detection order:
 *  1. that device-miss family + official `no_intent` / `no_valid_targets` templates
 *     ([HaAssistMissDetector]). Instant, offline, no token.
 *  2. structured [HaAssistVerdict] from the pipeline debug log when the HA WebSocket is
 *     signed in with an admin token. Catches replies that are not stock text, e.g. an LLM
 *     agent's free-text refusal that HA still classifies as `no_intent_match`.
 * If the conversation stage itself failed after STT, [onPipelineError] takes the
 * transcript without any reply to inspect.
 *
 * Two routes: the HA pipeline keeps its own Processing / RUN_END / idle. This
 * seat reports busy through [onBusy] so UI can stay on 处理中 after the channel
 * has already gone idle.
 */
class LocalIntentFallback(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Returns true / false when HA acknowledged, null when the outcome is unknown (ESPHome path). */
    private val callService: suspend (service: String, entityId: String, data: Map<String, Any?>) -> Boolean?,
    private val isSessionIdle: () -> Boolean,
    /** Play [text]; [url] is the synthesised audio or null when TTS was unavailable (caption only). */
    private val speak: suspend (text: String, url: String?) -> Unit,
    /** Play the phrases a streamed turn hands over, in order; returns once the last one ended. */
    private val speakStream: suspend (LocalReplySource) -> Unit,
    /**
     * Remote-AI seat only. True while this instance owns the turn after a HA miss.
     * Must not be the HA channel's Processing — that state is reset on RUN_END.
     */
    private val onBusy: (Boolean) -> Unit,
) {
    private sealed class Lookup {
        data object Running : Lookup()
        data class Done(val verdict: HaAssistVerdict?) : Lookup()
    }

    @Volatile
    private var lastSttText: String = ""
    @Volatile
    private var claimed = false
    /** This run's STT was Latin filler, not a real short reply; do not seat the model. */
    @Volatile
    private var shortRefused = false
    @Volatile
    private var lookup: Lookup? = null
    private var lookupJob: Job? = null
    private var job: Job? = null
    /** Bumped on a new HA run or cancel so a stale [run] cannot clear a newer seat. */
    private var seat = 0
    private val unbindWire: () -> Unit =
        com.example.ava.localllm.remote.RemoteAiManager.getInstance(context).registerSeatDrop {
            if (job?.isActive == true || claimed) cancel()
        }

    fun onRunStart() {
        lastSttText = ""
        claimed = false
        shortRefused = false
        lookupJob?.cancel()
        lookupJob = null
        lookup = null
        // New HA run is the HA route; drop a leftover remote seat.
        dropSeat()
    }

    fun onSttText(text: String) {
        lastSttText = text
    }

    /** [com.example.ava.esphome.voicesatellite.VoiceSatelliteStateMachine.replyInterceptor]. */
    fun intercept(speech: String, stage: ReplyStage): ReplyDecision {
        // Once this run is ours every later reply text for it stays swallowed.
        if (shortRefused) return ReplyDecision.Claim
        if (claimed && job?.isActive == true) return ReplyDecision.Claim
        val text = lastSttText.trim()
        if (text.isEmpty()) return ReplyDecision.Pass
        if (SttTranscript.isShort(text) && HaAssistMissDetector.shouldHandoff(speech)) {
            Log.i(TAG, "short stt ${text.length} chars; ask to repeat ha='${speech.take(40)}'")
            refuseShort()
            return ReplyDecision.Claim
        }
        val remote = com.example.ava.localllm.remote.RemoteAiManager.get()
        if (remote?.isReady() != true) return ReplyDecision.Pass

        // 1. Primary: "抱歉找不到…设备" (TTS may drop comma / 名为). Then no_intent + templates.
        if (HaAssistMissDetector.shouldHandoff(speech)) {
            Log.i(TAG, "assist miss → local AI stt='${text.take(40)}' ha='${speech.take(40)}'")
            return claim(speech)
        }

        // 2. Structured verdict, only when signed in. The debug log is written before
        //    HA emits INTENT_END, so it is readable from the first stage on.
        val ha = HaManager.get()?.takeIf { it.connectionState.value is HaWsClient.ConnectionState.Connected }
            ?: return ReplyDecision.Pass
        return when (val state = lookup) {
            null -> {
                if (stage == ReplyStage.TtsEnd) return ReplyDecision.Pass
                lookup = Lookup.Running
                lookupJob = scope.launch(Dispatchers.IO) {
                    val verdict = runCatching { ha.fetchAssistVerdict(text) }
                        .onFailure { Log.w(TAG, "verdict lookup failed", it) }
                        .getOrNull()
                    lookup = Lookup.Done(verdict)
                    Log.i(
                        TAG,
                        "verdict type=${verdict?.responseType} code=${verdict?.errorCode} " +
                            "local=${verdict?.processedLocally} for '${text.take(40)}'",
                    )
                }
                ReplyDecision.Defer
            }
            Lookup.Running -> {
                if (stage == ReplyStage.TtsEnd) {
                    Log.i(TAG, "verdict still in flight at TTS_END; HA reply plays")
                    ReplyDecision.Pass
                } else {
                    ReplyDecision.Defer
                }
            }
            is Lookup.Done -> {
                if (state.verdict?.isMiss != true) return ReplyDecision.Pass
                if (SttTranscript.isShort(text)) {
                    refuseShort()
                    ReplyDecision.Claim
                } else {
                    claim(speech)
                }
            }
        }
    }

    private fun claim(speech: String): ReplyDecision =
        if (takeOver(speech)) ReplyDecision.Claim else ReplyDecision.Pass

    /**
     * Pipeline `error` event. Only the intent stage counts: `intent-failed` is what the
     * pipeline raises when the conversation agent (LLM upstream, network) throws, and
     * `intent-not-supported` means the configured agent is gone. STT / TTS / wake codes
     * are not ours to rescue. Returns true when the transcript was handed over.
     */
    fun onPipelineError(code: String): Boolean {
        if (code != "intent-failed" && code != "intent-not-supported") return false
        return takeOver(originalReply = "")
    }

    private fun takeOver(originalReply: String): Boolean {
        val text = lastSttText.trim()
        if (text.isEmpty()) return false
        if (SttTranscript.isShort(text)) return refuseShort()
        val remote = com.example.ava.localllm.remote.RemoteAiManager.get()
        if (remote?.isReady() != true) return false
        // A finished seat used to leave [claimed] true until the next HA
        // RUN_START. If that run never arrives (API drop, pipeline miss),
        // later errors were swallowed and only a host-service restart healed it.
        if (claimed && job?.isActive == true) return false
        claimed = true
        val mySeat = ++seat
        onBusy(true)
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                run(text, originalReply, mySeat)
            } finally {
                if (seat == mySeat) {
                    claimed = false
                    onBusy(false)
                }
            }
        }
        return true
    }

    fun cancel() {
        Log.i(TAG, "cancel remote AI seat")
        dropSeat()
        lookupJob?.cancel()
        lookupJob = null
        claimed = false
        shortRefused = false
        lastSttText = ""
    }

    /** Satellite going away — stop listening for model-wire resets. */
    fun release() {
        unbindWire()
        cancel()
    }

    private fun refuseShort(): Boolean {
        if (shortRefused) return true
        shortRefused = true
        Log.i(TAG, "short stt; ask to repeat")
        AvaToast.show(
            context,
            R.string.pipeline_stt_too_short,
            tag = "stt-short",
            durationMs = AvaToast.LONG_MS,
        )
        return true
    }

    private fun dropSeat() {
        seat++
        job?.cancel()
        job = null
        onBusy(false)
    }

    private suspend fun run(text: String, originalReply: String, mySeat: Int) {
        // Assist's stock miss ("Sorry, I could not find a device named …") is
        // why we took the turn. Never speak it — every language ships that
        // template, and repeating it is the bug the user already heard.
        var reply: String? = null
        var handled = false
        val remote = com.example.ava.localllm.remote.RemoteAiManager.get()
        if (remote?.isReady() == true && remote.streamingEnabled()) {
            runStreaming(remote, text, originalReply, mySeat)
            return
        }
        if (remote?.isReady() == true) {
            var failed = false
            var failError: Throwable? = null
            val remoteText = runCatching { remote.turn(text, callService) }
                .onFailure {
                    if (it is CancellationException && it !is kotlinx.coroutines.TimeoutCancellationException) throw it
                    if (seat != mySeat) throw CancellationException("remote AI seat dropped")
                    Log.e(TAG, "remote AI failed", it)
                    failed = true
                    failError = it
                }
                .getOrNull()
            if (!remoteText.isNullOrBlank()) {
                handled = true
                reply = remoteText
            } else if (failed) {
                reply = RemoteAiFailSpeak.pick(context, remote.lastTurnResumable, failError)
            }
        }
        if (seat != mySeat) {
            Log.i(TAG, "seat dropped; skip reply")
            return
        }
        coroutineContext.ensureActive()
        if (originalReply.isNotBlank()) {
            Log.i(TAG, "swallowed HA miss '${originalReply.take(40)}' handled=$handled")
        }
        // Let the swallowed HA run unwind (TTS_END → completion → Connected) before we speak.
        withTimeoutOrNull(SESSION_IDLE_WAIT_MS) {
            while (!isSessionIdle()) delay(50)
        }
        if (seat != mySeat) {
            Log.i(TAG, "seat dropped after HA idle wait; skip reply")
            return
        }
        if (reply.isNullOrBlank()) {
            Log.i(TAG, "handled=$handled, no spoken reply")
            return
        }
        val spoken = TtsMdFilter.apply(reply)
        if (spoken.isBlank()) {
            Log.i(TAG, "handled=$handled, empty after tts filter")
            return
        }
        val url = runCatching { HaManager.get()?.synthesizeWithPipelineTts(spoken) }.getOrNull()
        if (url == null) Log.w(TAG, "tts_get_url unavailable; caption only")
        if (seat != mySeat) {
            Log.i(TAG, "seat dropped before speak; skip reply")
            return
        }
        withContext(Dispatchers.Main) { speak(spoken, url) }
    }

    /**
     * Streaming seat. The model turn runs over SSE; the finished answer is
     * handed to the speaker whole once the turn settles and the swallowed HA
     * run has unwound. A provider failure is spoken through the same queue.
     */
    private suspend fun runStreaming(
        remote: com.example.ava.localllm.remote.RemoteAiManager,
        text: String,
        originalReply: String,
        mySeat: Int,
    ) = coroutineScope {
        val stream = RemoteAiSpeechStream(this) { sentence ->
            HaManager.get()?.synthesizeWithPipelineTts(sentence)
        }
        val producer = launch {
            try {
                val spoken = remote.turn(text, callService, stream)
                if (originalReply.isNotBlank()) {
                    Log.i(TAG, "swallowed HA miss '${originalReply.take(40)}' handled=${!spoken.isNullOrBlank()} streamed=${stream.producedChars}")
                }
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Log.e(TAG, "remote AI timed out", e)
                if (seat == mySeat) stream.say(RemoteAiFailSpeak.pick(context, remote.lastTurnResumable, e))
            } catch (e: Throwable) {
                Log.e(TAG, "remote AI failed", e)
                if (seat == mySeat) stream.say(RemoteAiFailSpeak.pick(context, remote.lastTurnResumable, e))
            } finally {
                stream.close()
            }
        }
        // Let the swallowed HA run unwind (TTS_END → completion → Connected) before we speak.
        withTimeoutOrNull(SESSION_IDLE_WAIT_MS) {
            while (!isSessionIdle()) delay(50)
        }
        if (seat != mySeat) {
            Log.i(TAG, "seat dropped after HA idle wait; skip streamed reply")
            producer.cancel()
            return@coroutineScope
        }
        withContext(Dispatchers.Main) { speakStream(stream) }
        producer.join()
        Log.i(TAG, "streamed reply done chars=${stream.producedChars} spoken='${stream.spoken.take(60)}'")
    }

    private companion object {
        const val TAG = "LocalIntentFallback"
        const val SESSION_IDLE_WAIT_MS = 6_000L
    }
}
