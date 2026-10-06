package com.example.ava.esphome.voicesatellite

import android.util.Log
import com.example.ava.audio.SilenceDetector
import com.example.ava.esphome.Connected
import com.example.ava.esphome.EspHomeState
import com.example.ava.localllm.remote.TtsMdFilter
import com.example.ava.utils.LightKeywordDetector
import com.example.ava.voice.QuickWakePushToTalk
import com.example.esphomeproto.api.VoiceAssistantEvent
import com.example.esphomeproto.api.VoiceAssistantEventResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * HA assist_satellite run phase — independent of local UI [EspHomeState].
 *
 * Normal wake: start → [AwaitingHaDecision] → [Active] via RUN_START.
 * Config UI only: start → [AwaitingHaDecision] → RUN_END (no RUN_START) = intercept branch.
 * Do not add timers that "check" every wake; follow HA events.
 */
enum class HaPipelinePhase {
    /** No open HA run. */
    Idle,
    /** Start sent; waiting for RUN_START (Assist) or RUN_END (config intercept). */
    AwaitingHaDecision,
    /** RUN_START received — follow STT / INTENT / TTS / RUN_END. */
    Active,
}

class VoiceSatelliteStateMachine(
    private val scope: CoroutineScope,
    private val audioInput: VoiceSatelliteAudioInput,
    private val player: VoiceSatellitePlayer,
    private val state: MutableStateFlow<EspHomeState>,
    private val onStopSatellite: suspend () -> Unit,
    private val onTtsFinished: suspend () -> Unit,
    private val onConversationText: ((String, String) -> Unit)?,
    private val onProcessingStarted: (() -> Unit)? = null,
    private val onConversationId: ((String) -> Unit)? = null,
    private val onTtsStreamStart: (() -> Unit)? = null,
    private val onTtsStreamEnd: (() -> Unit)? = null,
    private val onDiscardPendingPcmTts: (() -> Unit)? = null,
    private val onFlushPendingPcmTts: (() -> Unit)? = null,
    private val onAbandonPcmTts: (() -> Unit)? = null,
    private val onDeviceAction: ((LightKeywordDetector.DeviceAction) -> Unit)? = null,
    private val onSendAudioEnd: (suspend () -> Unit)? = null,
    private val onSttText: ((String) -> Unit)? = null,
    private val onTtsText: ((String) -> Unit)? = null,
    private val onPipelineError: ((code: String, message: String) -> Unit)? = null,
    private val onTtsDurationReady: ((durationMs: Long, text: String) -> Unit)? = null,
    private val onTtsPlaybackStarted: ((text: String) -> Unit)? = null,
    private val onTtsProgressUpdate: ((currentMs: Long, totalMs: Long, text: String) -> Unit)? = null,
    private val onTtsPlaybackError: (() -> Unit)? = null,
    /** URL TTS actually settled (watchdog / ExoPlayer / focus). Chorus end uses this. */
    private val onTtsPlaybackSettled: (() -> Unit)? = null,
    /** Config UI intercept: RUN_END without RUN_START — suppress wake animation and exit quietly. */
    private val onConfigInterceptAbort: (suspend () -> Unit)? = null,
    /** FAB hold / long tap: splice a new HA listen instead of resting at the 15s VAD cap. */
    private val shouldRenewListen: (() -> Boolean)? = null,
    /** Returns false when the splice was declined (finger up / state moved on) so the caller can fall back. */
    private val onRenewListen: (suspend () -> Boolean)? = null,
    /** I1 RUN_END (or I2 RUN_START while dropping I1) — inject queue may advance. */
    private val onFabInjectOldSettled: (() -> Unit)? = null,
) {
    private var currentTtsText: String = ""
    private var pendingTtsDuration: Long = 0L
    private var silenceTimeoutJob: Job? = null
    private var sttVadEndJob: Job? = null
    /** VAD_END asked for a FAB splice; wait briefly for STT_END so chunks can be joined. */
    private var fabRenewPending = false
    private var fabRenewWaitJob: Job? = null
    /** After abort+re-wake, drop stale INTENT / TTS from the old run. */
    private var ignoreUntilRunStart = false
    /** Late I1 VAD_END before the new window's STT_START. */
    private var ignoreUntilSttStart = false
    /**
     * I1 INTENT / TTS / stt-no-text can arrive after I2 STT_START. Keep dropping
     * replies until I2's own STT_END. Clearing this at STT_START was too early:
     * I1's intent then closed the new mic and failPipeline killed the splice.
     */
    private var dropOldReply = false
    /** Consume the aborted run's RUN_END even if the next RUN_START already arrived. */
    private var dropNextRunEnd = false
    /** STT_END of the aborted window may land after the next RUN_START — stitch only. */
    private var expectTrailingSttEnd = false
    /** STT_END already launched this splice; do not treat a later STT as trailing. */
    private var renewFromSttEnd = false
    private var earlyUrlJob: Job? = null
    private var noPcmFallbackJob: Job? = null
    private var pcmStarveJob: Job? = null
    private var streamEndWatchdogJob: Job? = null
    /** Classic (non-streaming) URL TTS: recover when ExoPlayer never reaches ENDED. */
    private var classicUrlWatchdogJob: Job? = null
    private var sessionId = 0
    private val silenceDetector = SilenceDetector()
    /**
     * HA pipeline phase. [isWaking] mirrors [HaPipelinePhase.AwaitingHaDecision].
     */
    var haPipelinePhase: HaPipelinePhase = HaPipelinePhase.Idle
        private set
    /**
     * After we interrupt an Active run (stop + re-wake), the next RUN_END may belong
     * to the old pipeline. Consume that one without treating it as intercept abort.
     */
    private var expectStaleRunEnd = false
    var isWaking = false
        private set
    var isWakePhase = false
        private set
    /** HA VoiceCommandSegmenter heard speech in the current window. */
    val heardHaVad: Boolean get() = haVadStarted
    /** Old-window INTENT / TTS / ERROR must not touch the spliced listen. */
    fun isDroppingOldReply(): Boolean =
        dropOldReply || ignoreUntilRunStart || ignoreUntilSttStart
    var continueConversation = true
    private var stopWordProtectionEndTime = 0L
    private var haVadStarted = false
    private var stopRequested = false
    var intentEnded = false
        private set

    private var earlyTtsUrl: String? = null
    private var earlyTtsStarted = false
    private var assistantTextShown = false
    private var pcmStreamActive = false
    /** Speaker PCM was used this run (survives TTS_STREAM_END until session reset). */
    private var pcmTtsEverActive = false
    /** HA signaled speaker-mode PCM; may arrive before TTS_STREAM_START. */
    private var pcmTtsPending = false
    /** Upstream-style early URL TTS is playing; mutually exclusive with the PCM path. */
    private var urlTtsStreaming = false
    /**
     * HA opened a SPEAKER stream (TTS_STREAM_START) but has not delivered usable PCM.
     * Session completion for the URL reply waits for upstream [TTS_STREAM_END]
     * instead of ExoPlayer STATE_ENDED (progressive tts_proxy WAV often never ends).
     */
    private var awaitingUpstreamStreamEnd = false
    /** HA signaled URL generation is complete (TTS_END); wait for actual playback drain. */
    private var urlTtsGenerationEnded = false
    /** True once ExoPlayer has started outputting URL TTS audio. */
    private var urlTtsPlaybackStarted = false
    /** Valid SPEAKER PCM bytes seen this run (16-bit LE mono frames). */
    private var pcmBytesReceived = 0L
    /**
     * URL fallback won because no usable PCM bytes arrived (or PCM starved).
     * Once set, ignore late/empty PCM so URL and speaker never double-play.
     */
    private var urlFallbackCommitted = false
    /**
     * Continuous conversation only: progressive tts_proxy sometimes reports a short
     * [pendingTtsDuration] that expires while audio is still playing. After that, skip
     * duration hard-cut for this run and finish via stall / ENDED instead — otherwise
     * the cut immediately opens the next listen mic into leftover TTS.
     */
    private var classicUrlPreferStallCompletion = false

    /** Answer of [replyInterceptor] for one assistant reply. */
    enum class ReplyDecision {
        /** Play HA's reply as usual. */
        Pass,
        /**
         * Swallow this reply: no URL / PCM playback, no captions, no continue-conversation.
         * The interceptor speaks its own result after the run completes.
         */
        Claim,
        /**
         * Not decided yet (a structured verdict is still in flight). The machine holds
         * captions at INTENT_END, sets playback up as usual at TTS_START, and asks one
         * last time at TTS_END (or RUN_END when the pipeline has no TTS stage).
         */
        Defer,
    }

    /** Where in the run the reply text is being offered to [replyInterceptor]. */
    enum class ReplyStage { IntentEnd, TtsStart, TtsEnd }

    /** Local intent fallback hook. Called with the assistant reply text at each [ReplyStage]. */
    var replyInterceptor: ((speech: String, stage: ReplyStage) -> ReplyDecision)? = null
    /** Reply text the interceptor deferred on; re-offered at TTS_END / RUN_END. */
    private var deferredReply: String? = null
    /** Set when [replyInterceptor] claimed the current run's reply. */
    private var replySuppressed = false

    // Hot-path settings snapshots: these are read from ESPHome/audio callback threads
    // where runBlocking { DataStore } causes jank and can ANR. Kept fresh by scope-bound
    // collectors below; initial values mirror PlayerSettings defaults.
    @Volatile private var continuousConversationSnapshot = false
    @Volatile private var streamingTtsSubtitlesSnapshot = false
    @Volatile private var exitKeywordStopSnapshot = true
    @Volatile private var questionMarkContinueSnapshot = false
    @Volatile private var smartContinueSnapshot = false

    fun smartContinueOn(): Boolean = smartContinueSnapshot

    init {
        scope.launch {
            player.enableContinuousConversation.collect { continuousConversationSnapshot = it }
        }
        scope.launch {
            player.enableStreamingTtsSubtitles.collect { streamingTtsSubtitlesSnapshot = it }
        }
        scope.launch {
            player.enableExitKeywordStop.collect { exitKeywordStopSnapshot = it }
        }
        scope.launch {
            player.enableQuestionMarkContinue.collect { questionMarkContinueSnapshot = it }
        }
        scope.launch {
            player.enableSmartContinue.collect { smartContinueSnapshot = it }
        }
    }

    companion object {
        private const val TAG = "VoiceSatelliteStateMachine"
        /**
         * After HA STT_END with no VAD hangover already running: keep the uplink
         * open this long so a late-started listen (openWakeWord) still catches
         * the rest of the command. TTS_START / errors still cut immediately.
         */
        private const val STT_END_HANGOVER_MS = 1_500L
        /** Brief window for speaker PCM (TTS_STREAM_START) to win over deferred URL playback. */
        private const val EARLY_URL_DEFER_MS = 200L
        /**
         * After [tts_start_streaming], wait this long for real PCM bytes before URL fallback.
         * Decision is byte-based: zero/invalid PCM → URL; any valid PCM cancels the wait.
         */
        private const val STREAMING_NO_PCM_FALLBACK_MS = 1_500L
        /** ~100ms of 16 kHz 16-bit mono — enough to prove the speaker stream is alive. */
        private const val MIN_PCM_BYTES_ALIVE = 3_200L
        /** After PCM starts, if bytes stay below [MIN_PCM_BYTES_ALIVE], abandon for URL. */
        private const val PCM_STARVE_FALLBACK_MS = 1_200L
        /**
         * After [TTS_END] while PCM is active: force-end only if [TTS_STREAM_END]
         * never arrives AND incoming bytes have stalled this long. Do NOT cap from
         * TTS_END — HA keeps pushing PCM after generation; a 10s wall clock cut
         * long streaming replies mid-sentence (#191). Mirrors [CLASSIC_URL_STALL_MS].
         */
        private const val PCM_STREAM_END_STALL_MS = 12_000L
        /** How often to sample [pcmBytesReceived] while waiting for STREAM_END. */
        private const val PCM_STREAM_END_POLL_MS = 500L
        /**
         * Safety ceiling so a runaway PCM stream cannot duck music forever.
         * Mirrors [CLASSIC_URL_ABSOLUTE_MAX_MS].
         */
        private const val PCM_STREAM_END_ABSOLUTE_MAX_MS = 120_000L
        /**
         * URL-only reply after empty SPEAKER START: wait this long for upstream
         * [TTS_STREAM_END] before forcing session completion (safety net only).
         * Keep near classic URL timeouts — 90s left music ducked far too long.
         */
        private const val URL_UPSTREAM_STREAM_END_MISSING_MS = 25_000L
        /**
         * Classic URL TTS (streaming mode off): if playback never becomes audible after
         * TTS_END, force session completion so Sendspin duck cannot stick forever.
         */
        private const val CLASSIC_URL_NO_START_MS = 10_000L
        /**
         * After audible start with unknown duration: force complete only if playback
         * position stops advancing for this long (rebuffer / true end without ENDED).
         * Do NOT use a wall-clock cap from start — HA tts_proxy often has no duration
         * and replies routinely exceed 20s.
         */
        private const val CLASSIC_URL_STALL_MS = 12_000L
        /** How often to sample position while waiting for stall or late duration. */
        private const val CLASSIC_URL_STALL_POLL_MS = 500L
        /**
         * Safety ceiling so a runaway stream cannot duck music forever. Normal replies
         * finish via ENDED or stall detection long before this; 2 minutes covers even
         * verbose LLM answers.
         */
        private const val CLASSIC_URL_ABSOLUTE_MAX_MS = 120_000L
        /**
         * Continuous rebuffering longer than this triggers a lightweight network
         * probe: if the TTS endpoint is unreachable (network died mid-TTS), end the
         * session instead of waiting for [CLASSIC_URL_ABSOLUTE_MAX_MS]. If the
         * endpoint answers, keep waiting — generation is just slow.
         */
        private const val CLASSIC_URL_BUFFERING_PROBE_MS = 30_000L
        /** TCP connect timeout for the reachability probe (single SYN, IO thread). */
        private const val CLASSIC_URL_PROBE_TIMEOUT_MS = 1_500L
        /** Extra slack after a known ExoPlayer duration before forcing completion. */
        private const val CLASSIC_URL_DURATION_MARGIN_MS = 2_000L
        /**
         * Treat position as advanced only if it moves by at least this many ms.
         */
        private const val CLASSIC_URL_POSITION_EPS_MS = 50L
    }

    fun handleVoiceEvent(voiceEvent: VoiceAssistantEventResponse) {
        Log.d(
            TAG,
            "HA event=${voiceEvent.eventType} phase=$haPipelinePhase ui=${state.value} " +
                "isWaking=$isWaking expectStale=$expectStaleRunEnd",
        )
        when (voiceEvent.eventType) {
            VoiceAssistantEvent.VOICE_ASSISTANT_ERROR -> {
                val code = voiceEvent.stringField("code") ?: "unknown"
                val message = voiceEvent.stringField("message") ?: ""
                if (isDroppingOldReply() && isRenewablePipelineError(code)) {
                    Log.d(TAG, "PIPELINE_ERROR $code ignored, belongs to spliced-out window")
                    return
                }
                if (
                    isRenewablePipelineError(code) &&
                    QuickWakePushToTalk.isHoldTurn &&
                    !QuickWakePushToTalk.isHolding
                ) {
                    Log.d(TAG, "PIPELINE_ERROR $code ignored, hold already released")
                    return
                }
                if (shouldRenewListen?.invoke() == true && isRenewablePipelineError(code)) {
                    Log.d(TAG, "PIPELINE_ERROR $code during FAB listen — renew")
                    clearFabRenewWait()
                    fabRenewPending = false
                    scope.launch {
                        // Declined splice: HA's run is already dead, so end the
                        // turn like any other pipeline error instead of staying
                        // Listening on a channel nobody is reading.
                        if (onRenewListen?.invoke() != true) failPipeline(code, message)
                    }
                    return
                }
                failPipeline(code, message)
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_RUN_START -> {
                Log.d(TAG, "RUN_START received, phase=$haPipelinePhase stopRequested=$stopRequested")

                if (ignoreIfStopRequested("RUN_START")) {
                    return
                }
                ignoreUntilRunStart = false

                // HA accepted a real Assist pipeline (not config intercept).
                setHaPhase(HaPipelinePhase.Active)
                expectStaleRunEnd = false
                silenceTimeoutJob?.cancel()
                silenceTimeoutJob = null
                silenceDetector.reset()
                haVadStarted = false
                intentEnded = false
                val incomingUrl = voiceEvent.stringField("url")
                earlyTtsUrl = if (dropOldReply) {
                    if (incomingUrl != null) Log.d(TAG, "RUN_START TTS url dropped, FAB splice")
                    onFabInjectOldSettled?.invoke()
                    null
                } else incomingUrl
                earlyTtsStarted = false
                assistantTextShown = false
                pcmStreamActive = false
                pcmTtsEverActive = false
                pcmTtsPending = false
                urlTtsStreaming = false
                awaitingUpstreamStreamEnd = false
                urlTtsGenerationEnded = false
                urlTtsPlaybackStarted = false
                pcmBytesReceived = 0L
                urlFallbackCommitted = false
                replySuppressed = false
                deferredReply = null
                // Stale duration from the previous reply (or an announcement) must not
                // drive this run's completion watchdog — it hard-cut longer replies at
                // the OLD reply's length + margin when the new stream has no duration.
                pendingTtsDuration = 0L
                classicUrlPreferStallCompletion = false
                earlyUrlJob?.cancel()
                earlyUrlJob = null
                noPcmFallbackJob?.cancel()
                noPcmFallbackJob = null
                pcmStarveJob?.cancel()
                pcmStarveJob = null
                streamEndWatchdogJob?.cancel()
                streamEndWatchdogJob = null
                classicUrlWatchdogJob?.cancel()
                classicUrlWatchdogJob = null
                onDiscardPendingPcmTts?.invoke()
                // Guards the Listening→Processing hand-off, where stop inference
                // switches on and could still be holding wake-phrase audio. The
                // detector now arms (dropping in-flight state) at that exact
                // transition, and its template needs ~240 ms to complete, so this
                // only has to outlast one fresh match. The old 2 s blanket sat
                // right on top of the window where HA returns fast, and silently
                // ate real stops as "Ignoring stop during protection period".
                stopWordProtectionEndTime = System.currentTimeMillis() + 500

                if (earlyTtsUrl != null) {
                    Log.d(TAG, "RUN_START early TTS url cached")
                }

                if (state.value == Connected) {
                    state.value = Listening
                }

                player.ttsPlayer.runStart {
                    scope.launch { onTtsFinished() }
                }
                // Do not open the mic uplink while the wake earcon is still
                // playing: RUN_START beats the sound (~250 ms after wake), and
                // an open uplink ships the earcon's AEC-convergence residual to
                // HA, whose VAD reads it as user speech and closes the STT leg
                // before the user has spoken (observed: VAD_START mid-chime,
                // VAD_END ~0.7 s after it, stt-no-text-recognized). The
                // playWakeSound completion callback — or its watchdog for long
                // custom sounds — opens the uplink instead. Every other path
                // (continue-conversation, search listen, HA-initiated runs,
                // wake sound disabled) has isWakePhase == false here and keeps
                // today's behaviour.
                if (isWakePhase) {
                    Log.d(TAG, "RUN_START: uplink held until wake sound completes")
                } else {
                    audioInput.isStreaming = true
                }
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_STT_START -> {
                ignoreUntilRunStart = false
                ignoreUntilSttStart = false
                // dropOldReply stays up: I1 INTENT often lands after this.
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_STT_VAD_START -> {
                Log.d(TAG, "STT_VAD_START received, user started speaking")
                haVadStarted = true
                silenceDetector.forceStartSpeaking()
                silenceTimeoutJob?.cancel()
                silenceTimeoutJob = null
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_STT_VAD_END -> {
                Log.d(TAG, "STT_VAD_END received, local isSpeaking=${silenceDetector.isSpeaking}, sessionId=$sessionId")
                if (ignoreUntilRunStart || ignoreUntilSttStart) {
                    Log.d(TAG, "STT_VAD_END ignored, FAB listen renew in flight")
                    return
                }
                if (shouldRenewListen?.invoke() == true) {
                    Log.d(TAG, "STT_VAD_END: FAB splice, stay listening")
                    fabRenewPending = true
                    ignoreUntilRunStart = true
                    ignoreUntilSttStart = true
                    dropOldReply = true
                    dropNextRunEnd = true
                    sttVadEndJob?.cancel()
                    sttVadEndJob = null
                    scheduleFabRenewWait()
                    return
                }

                val delayMs = if (silenceDetector.isSpeaking) 3500L else 2000L
                Log.d(TAG, "STT_VAD_END: delaying ${delayMs}ms before stopping audio")

                state.value = Processing
                onProcessingStarted?.invoke()
                scheduleSttHangover(delayMs, "STT_VAD_END")
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_STT_END -> {
                val sttText = voiceEvent.stringField("text")
                Log.d(TAG, "STT_END received, text: $sttText")
                if (expectTrailingSttEnd) {
                    expectTrailingSttEnd = false
                    if (!sttText.isNullOrBlank()) onSttText?.invoke(sttText)
                    return
                }
                // VAD_END armed both flags; the splice must win over the stale-event
                // gate or this window's STT_END is swallowed and the renew only fires
                // from the 2 s fallback — a 2 s deaf gap on every 15 s cap.
                if (fabRenewPending) {
                    clearFabRenewWait()
                    fabRenewPending = false
                    launchFabRenewIfStillWanted(sttText)
                    return
                }
                if (ignoreUntilRunStart || ignoreUntilSttStart) {
                    if (!sttText.isNullOrBlank()) onSttText?.invoke(sttText)
                    return
                }
                // 15s cap often emits STT_END with no VAD_END. Splice on the
                // text event itself so we do not wait for a pending flag.
                if (shouldRenewListen?.invoke() == true) {
                    launchFabRenewIfStillWanted(sttText)
                    return
                }

                dropOldReply = false

                if (isSTTError(sttText)) {
                    Log.w(TAG, "STT returned error, stopping session: $sttText")
                    sttVadEndJob?.cancel()
                    sttVadEndJob = null
                    stopUplinkAudio()
                    scope.launch { onStopSatellite() }
                    return
                }

                if (!sttText.isNullOrBlank()) {
                    onSttText?.invoke(sttText)
                }

                if (state.value == Listening) {
                    state.value = Processing
                }

                // Hangover: do not yank the mic the instant HA returns text. If VAD_END
                // already scheduled a longer wait, keep that. Otherwise wait ~1.5 s so
                // a late openWakeWord start still captures the rest of the command.
                if (sttVadEndJob?.isActive == true) {
                    Log.d(TAG, "STT_END: VAD hangover already running, leaving uplink open")
                } else {
                    scheduleSttHangover(STT_END_HANGOVER_MS, "STT_END")
                }
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_INTENT_START -> {
                if (ignoreStaleRenew("INTENT_START") || ignoreIfStopRequested("INTENT_START")) {
                    return
                }
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_INTENT_PROGRESS -> {
                if (ignoreStaleRenew("INTENT_PROGRESS") || ignoreIfStopRequested("INTENT_PROGRESS")) {
                    return
                }
                if (isStreamingTtsMode() && voiceEvent.flagField("tts_start_streaming")) {
                    // Prefer PCM when HA sends speaker chunks. Fall back to URL only when
                    // no valid PCM bytes arrive (byte-based, not a blind early URL start).
                    pcmTtsPending = true
                    Log.d(TAG, "INTENT_PROGRESS tts_start_streaming=1, awaiting PCM bytes")
                    scheduleNoPcmByteFallback()
                }
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_INTENT_END -> {
                if (ignoreStaleRenew("INTENT_END")) {
                    return
                }
                intentEnded = true
                voiceEvent.stringField("conversation_id")?.let { id ->
                    Log.d(TAG, "INTENT_END conversation_id=$id")
                    onConversationId?.invoke(id)
                }
                voiceEvent.stringField("continue_conversation")?.let { value ->
                    continueConversation = value == "1"
                    Log.d(TAG, "INTENT_END continue_conversation=$continueConversation")
                }
                voiceEvent.stringField("speech")?.let { speech ->
                    applyAssistantSpeech(speech, fromIntentEnd = true)
                }
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_TTS_START -> {
                if (ignoreStaleRenew("TTS_START") || ignoreIfStopRequested("TTS_START")) {
                    return
                }
                Log.d(TAG, "TTS_START received, stopping audio input")
                sttVadEndJob?.cancel()
                sttVadEndJob = null
                stopUplinkAudio()
                silenceTimeoutJob?.cancel()
                silenceTimeoutJob = null

                val ttsText = voiceEvent.stringField("text")

                if (isTTSAboutError(ttsText)) {
                    Log.w(TAG, "TTS is about error, stopping session")
                    scope.launch { onStopSatellite() }
                    return
                }

                state.value = Responding
                if (!ttsText.isNullOrBlank()) {
                    // Decide interception before any playback scheduling below.
                    applyAssistantSpeech(ttsText, fromIntentEnd = false)
                }
                if (replySuppressed) {
                    Log.d(TAG, "TTS_START: reply suppressed by local intent fallback")
                    return
                }
                if (isStreamingTtsMode() && !pcmStreamActive && !urlTtsStreaming &&
                    !urlFallbackCommitted && !player.ttsPlayer.ttsPlayed
                ) {
                    pcmTtsPending = true
                    // Short defer if TTS_START arrives with still-zero PCM; byte watchdog
                    // from INTENT_PROGRESS may already be running with a longer window.
                    scheduleDeferredEarlyUrlTts()
                    scheduleNoPcmByteFallback()
                }
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_TTS_END -> {
                if (ignoreStaleRenew("TTS_END") || ignoreIfStopRequested("TTS_END")) {
                    return
                }
                settleDeferredReply(showCaptionIfUnseen = false)
                if (replySuppressed) {
                    // Local fallback owns the answer: finish this run silently, exactly like
                    // the "no URL" branch below, so every watchdog / duck state unwinds.
                    Log.d(TAG, "TTS_END: reply suppressed, completing silently")
                    pcmTtsPending = false
                    earlyUrlJob?.cancel()
                    earlyUrlJob = null
                    noPcmFallbackJob?.cancel()
                    noPcmFallbackJob = null
                    onDiscardPendingPcmTts?.invoke()
                    player.ttsPlayer.markAsPlayed()
                    player.ttsPlayer.triggerCompletion()
                    return
                }
                val ttsUrl = voiceEvent.stringField("url") ?: earlyTtsUrl
                if (!ttsUrl.isNullOrBlank()) {
                    earlyTtsUrl = ttsUrl
                }
                pcmTtsPending = false
                if (!pcmStreamActive) {
                    onDiscardPendingPcmTts?.invoke()
                }
                Log.d(
                    TAG,
                    "TTS_END received, ttsUrl=$ttsUrl, ttsPlayed=${player.ttsPlayer.ttsPlayed}, " +
                        "earlyTtsStarted=$earlyTtsStarted, pcmStreamActive=$pcmStreamActive, " +
                        "urlTtsStreaming=$urlTtsStreaming, state=${state.value}",
                )
                if (pcmStreamActive) {
                    // Starved PCM with URL fallback already committed: finish via URL path.
                    if (urlFallbackCommitted) {
                        Log.d(TAG, "TTS_END: URL fallback already committed over PCM")
                        urlTtsGenerationEnded = true
                        markUrlTtsGenerationComplete()
                        if (!awaitingUpstreamStreamEnd) {
                            scheduleClassicUrlCompletionWatchdog("TTS_END (URL fallback)")
                        }
                        return
                    }
                    Log.d(TAG, "TTS_END: PCM stream active, waits for TTS_STREAM_END")
                    scheduleMissingStreamEndFallback()
                    return
                }
                if (pcmTtsEverActive && !urlFallbackCommitted) {
                    Log.d(TAG, "TTS_END: PCM drain handles completion")
                    return
                }
                if (urlTtsStreaming || player.ttsPlayer.ttsPlayed) {
                    Log.d(TAG, "TTS_END: URL stream generation done, completing after playback drains")
                    // Keep urlTtsStreaming while playback continues so an empty
                    // TTS_STREAM_START cannot cancel a working URL reply.
                    urlTtsGenerationEnded = true
                    markUrlTtsGenerationComplete()
                    if (!awaitingUpstreamStreamEnd) {
                        scheduleClassicUrlCompletionWatchdog("TTS_END (url already playing)")
                    }
                    return
                }
                if (state.value == Responding && !player.ttsPlayer.ttsPlayed) {
                    Log.d(TAG, "TTS_END: playing url=$ttsUrl, text='${currentTtsText.take(20)}...'")
                    if (!ttsUrl.isNullOrBlank()) {
                        // Mark URL path active before play so late empty SPEAKER events
                        // cannot steal and silence the reply.
                        earlyUrlJob?.cancel()
                        earlyUrlJob = null
                        noPcmFallbackJob?.cancel()
                        noPcmFallbackJob = null
                        urlTtsStreaming = true
                        setupTtsCallbacks()
                        player.ttsPlayer.markAsPlayed()
                        player.ttsPlayer.playTts(ttsUrl)
                        if (awaitingUpstreamStreamEnd) {
                            // Empty SPEAKER already opened. Completion is on TTS_STREAM_END
                            // when it arrives — previously that event was ignored while URL played.
                            Log.d(TAG, "TTS_END: URL playing; will complete on TTS_STREAM_END (do not ignore)")
                            scheduleMissingUpstreamStreamEndFallback()
                        } else {
                            // Classic URL-only path: prefer ExoPlayer ENDED, with watchdog
                            // because progressive tts_proxy often never reaches STATE_ENDED.
                            urlTtsGenerationEnded = true
                            markUrlTtsGenerationComplete()
                            scheduleClassicUrlCompletionWatchdog("TTS_END (classic URL)")
                        }
                    } else {
                        Log.d(TAG, "TTS_END: No URL, triggering completion")
                        player.ttsPlayer.markAsPlayed()
                        player.ttsPlayer.triggerCompletion()
                    }
                } else {
                    Log.d(TAG, "TTS_END: skipped, state=${state.value}, ttsPlayed=${player.ttsPlayer.ttsPlayed}")
                }
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_TTS_STREAM_START -> {
                if (ignoreStaleRenew("TTS_STREAM_START") || ignoreIfStopRequested("TTS_STREAM_START")) {
                    return
                }
                if (!isStreamingTtsMode()) {
                    Log.d(TAG, "TTS_STREAM_START ignored, streaming TTS mode off")
                    return
                }
                if (replySuppressed) {
                    Log.d(TAG, "TTS_STREAM_START ignored, reply suppressed")
                    return
                }
                if (urlFallbackCommitted) {
                    Log.d(
                        TAG,
                        "TTS_STREAM_START ignored, URL fallback committed (pcmBytes=$pcmBytesReceived)",
                    )
                    return
                }
                // HA may send STREAM_START/END with zero PCM (non-WAV TTS result).
                // Never cancel URL or start an empty AudioTrack without proof of life.
                if (pcmBytesReceived < MIN_PCM_BYTES_ALIVE) {
                    pcmTtsPending = true
                    awaitingUpstreamStreamEnd = true
                    Log.d(
                        TAG,
                        "TTS_STREAM_START deferred until PCM bytes arrive (have=$pcmBytesReceived)",
                    )
                    scheduleNoPcmByteFallback()
                    return
                }
                if (player.ttsPlayer.ttsPlayed && !player.ttsPlayer.isPlaying && !urlTtsStreaming) {
                    Log.d(TAG, "TTS_STREAM_START ignored, URL TTS already completed")
                    return
                }
                earlyUrlJob?.cancel()
                earlyUrlJob = null
                if (urlTtsStreaming || player.ttsPlayer.isPlaying) {
                    Log.d(TAG, "TTS_STREAM_START: PCM taking over from URL stream")
                    player.ttsPlayer.cancelActivePlayback()
                    urlTtsStreaming = false
                    urlTtsGenerationEnded = false
                    urlTtsPlaybackStarted = false
                    classicUrlWatchdogJob?.cancel()
                    classicUrlWatchdogJob = null
                    player.ttsPlayer.onPlaybackEnded = null
                }
                if (pcmStreamActive) {
                    Log.d(TAG, "TTS_STREAM_START received, PCM already playing")
                    earlyTtsStarted = true
                    return
                }
                Log.d(TAG, "TTS_STREAM_START received with live PCM")
                activatePcmTtsStream(earlyFromBufferedPcm = false)
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_TTS_STREAM_END -> {
                if (ignoreStaleRenew("TTS_STREAM_END") || ignoreIfStopRequested("TTS_STREAM_END")) {
                    return
                }
                streamEndWatchdogJob?.cancel()
                streamEndWatchdogJob = null
                if (replySuppressed) {
                    Log.d(TAG, "TTS_STREAM_END ignored, reply suppressed")
                    return
                }
                if (!pcmStreamActive) {
                    // Empty SPEAKER cycle: START then END with no usable audio.
                    if (!player.ttsPlayer.ttsPlayed && !urlTtsStreaming) {
                        Log.d(
                            TAG,
                            "TTS_STREAM_END with no PCM playback (bytes=$pcmBytesReceived), " +
                                "falling back to URL if available",
                        )
                        awaitingUpstreamStreamEnd = false
                        if (!tryStartEarlyUrlTts(commitFallback = true) && earlyTtsUrl.isNullOrBlank()) {
                            Log.d(TAG, "TTS_STREAM_END: no URL either, completing silently")
                            player.ttsPlayer.markAsPlayed()
                            player.ttsPlayer.triggerCompletion()
                        }
                        return
                    }
                    // BUG was here: URL already playing → we used to return and IGNORE
                    // TTS_STREAM_END. Upstream did send it (often with a timing skew vs
                    // local AudioTrack); ignoring left Responding stuck because progressive
                    // tts_proxy WAV never hits ExoPlayer STATE_ENDED.
                    if (awaitingUpstreamStreamEnd || urlTtsStreaming || urlTtsGenerationEnded) {
                        Log.d(
                            TAG,
                            "TTS_STREAM_END: handle URL-only empty SPEAKER close " +
                                "(pcmBytes=$pcmBytesReceived) — must not ignore",
                        )
                        awaitingUpstreamStreamEnd = false
                        urlTtsGenerationEnded = true
                        // Only complete the session. Do not cancel playback here:
                        // STREAM_END can arrive slightly before/after local audio due to skew.
                        completeUrlTtsSession("TTS_STREAM_END (URL-only; was previously ignored)")
                    } else {
                        Log.d(
                            TAG,
                            "TTS_STREAM_END ignored: no URL session " +
                                "(ttsPlayed=${player.ttsPlayer.ttsPlayed}, " +
                                "urlTtsStreaming=$urlTtsStreaming)",
                        )
                    }
                    return
                }
                // Activated but starved: prefer URL over draining silence.
                if (pcmBytesReceived < MIN_PCM_BYTES_ALIVE &&
                    !earlyTtsUrl.isNullOrBlank() &&
                    !urlFallbackCommitted
                ) {
                    Log.w(
                        TAG,
                        "TTS_STREAM_END with insufficient PCM ($pcmBytesReceived), abandoning for URL",
                    )
                    abandonPcmForUrlFallback()
                    return
                }
                Log.d(TAG, "TTS_STREAM_END received")
                awaitingUpstreamStreamEnd = false
                pcmStreamActive = false
                pcmTtsPending = false
                pcmStarveJob?.cancel()
                pcmStarveJob = null
                onTtsStreamEnd?.invoke()
            }

            VoiceAssistantEvent.VOICE_ASSISTANT_RUN_END -> {
                if (dropNextRunEnd) {
                    dropNextRunEnd = false
                    Log.d(TAG, "RUN_END dropped, FAB listen splice")
                    onFabInjectOldSettled?.invoke()
                    return
                }
                if (ignoreIfStopRequested("RUN_END")) {
                    return
                }
                val wasTtsPlayed = player.ttsPlayer.ttsPlayed
                Log.d(
                    TAG,
                    "RUN_END received, phase=$haPipelinePhase ui=${state.value}, " +
                        "isWaking=$isWaking, ttsPlayed=$wasTtsPlayed expectStale=$expectStaleRunEnd",
                )

                when (haPipelinePhase) {
                    HaPipelinePhase.AwaitingHaDecision -> {
                        // Config UI branch only: assist_satellite/intercept_wake_word
                        // ends with RUN_END and never sends RUN_START. Leave Listening
                        // immediately — do not treat this as a normal Assist session.
                        // After barge-in stop+re-wake, the first RUN_END may be stale.
                        if (expectStaleRunEnd) {
                            Log.d(TAG, "RUN_END while awaiting: ignoring stale end from interrupted pipeline")
                            expectStaleRunEnd = false
                            return
                        }
                        Log.i(
                            TAG,
                            "HA config intercept/abort (RUN_END before RUN_START) — config branch, leave Listening",
                        )
                        audioInput.isStreaming = false
                        setHaPhase(HaPipelinePhase.Idle)
                        scope.launch {
                            onConfigInterceptAbort?.invoke() ?: onStopSatellite()
                        }
                        return
                    }
                    HaPipelinePhase.Idle -> {
                        Log.d(TAG, "RUN_END while Idle — ignore")
                        return
                    }
                    HaPipelinePhase.Active -> {
                        // Normal Assist pipeline completion.
                    }
                }

                if (shouldRenewListen?.invoke() == true) {
                    Log.d(TAG, "RUN_END (Active): FAB inject window, splice")
                    onFabInjectOldSettled?.invoke()
                    markRenewInFlight()
                    scope.launch {
                        if (onRenewListen?.invoke() != true) settleDeclinedRenew()
                    }
                    return
                }

                // Pipeline without a TTS stage: the deferred INTENT_END text never met TTS_END.
                settleDeferredReply(showCaptionIfUnseen = true)
                audioInput.isStreaming = false
                setHaPhase(HaPipelinePhase.Idle)

                when (state.value) {
                    is Listening, is Processing -> {
                        Log.d(TAG, "RUN_END (Active): resetting stuck state (Listening/Processing)")
                        scope.launch { onStopSatellite() }
                    }
                    is Responding -> {
                        if (replySuppressed) {
                            // Swallowed HA miss: TTS_END already had a URL, but we
                            // never play it. markAsPlayed() would make this branch
                            // wait forever for ExoPlayer ENDED on audio that was
                            // never started — the local seat then thinks TTS is
                            // still "fetching".
                            Log.d(TAG, "RUN_END (Active): swallowed HA TTS, not waiting for playback")
                        } else if (!wasTtsPlayed) {
                            Log.d(TAG, "RUN_END (Active): Responding but no TTS played, resetting state")
                            scope.launch { onStopSatellite() }
                        } else {
                            Log.d(TAG, "RUN_END (Active): Responding with TTS, waiting for playback completion")
                        }
                    }
                    else -> {
                        Log.d(TAG, "RUN_END (Active): state is ${state.value}, no action needed")
                    }
                }
            }

            else -> {}
        }
    }

    fun isReceivingPcmStream(): Boolean = pcmStreamActive

    fun shouldBufferIncomingPcmTts(): Boolean =
        !stopRequested &&
            isStreamingTtsMode() &&
            pcmTtsPending &&
            !pcmStreamActive &&
            !urlFallbackCommitted

    /** False once URL fallback won — drop late SPEAKER chunks to avoid double-play. */
    fun shouldAcceptIncomingPcmTts(): Boolean =
        !stopRequested && isStreamingTtsMode() && !urlFallbackCommitted

    /**
     * Count valid SPEAKER PCM (16-bit LE, even-sized). Real bytes cancel the no-PCM
     * URL fallback; format-invalid chunks are ignored so silence/garbage cannot keep us waiting.
     */
    fun onIncomingPcmTtsBytes(raw: ByteArray): Boolean {
        if (!shouldAcceptIncomingPcmTts()) return false
        if (!isValidSpeakerPcmChunk(raw)) {
            Log.d(TAG, "Ignoring invalid SPEAKER PCM chunk size=${raw.size}")
            return false
        }
        pcmBytesReceived += raw.size.toLong()
        if (pcmBytesReceived >= MIN_PCM_BYTES_ALIVE) {
            noPcmFallbackJob?.cancel()
            noPcmFallbackJob = null
            pcmStarveJob?.cancel()
            pcmStarveJob = null
        }
        return true
    }

    /** Start PCM playback as soon as buffered audio is available (before TTS_STREAM_START). */
    fun tryActivatePcmTtsStreamEarly(): Boolean {
        if (!shouldBufferIncomingPcmTts()) return false
        if (pcmBytesReceived <= 0L) return false
        // If URL reply is already audible, wait for proof-of-life before stealing.
        if ((urlTtsStreaming || player.ttsPlayer.isPlaying) &&
            pcmBytesReceived < MIN_PCM_BYTES_ALIVE
        ) {
            return false
        }
        activatePcmTtsStream(earlyFromBufferedPcm = true)
        return true
    }

    private fun isValidSpeakerPcmChunk(raw: ByteArray): Boolean {
        // ESPHome SPEAKER TTS is 16 kHz 16-bit mono PCM — reject odd-sized / empty frames.
        return raw.isNotEmpty() && raw.size % 2 == 0
    }

    private fun activatePcmTtsStream(earlyFromBufferedPcm: Boolean) {
        if (stopRequested || pcmStreamActive || urlFallbackCommitted || replySuppressed) return
        earlyTtsStarted = true
        noPcmFallbackJob?.cancel()
        noPcmFallbackJob = null
        earlyUrlJob?.cancel()
        earlyUrlJob = null
        if (earlyFromBufferedPcm) {
            Log.d(TAG, "Early PCM TTS playback (buffered chunks ready, play while receiving)")
            stopUplinkAudio()
            silenceTimeoutJob?.cancel()
            silenceTimeoutJob = null
        }
        if (state.value !is Responding) {
            state.value = Responding
        }
        setupTtsCallbacks()
        player.ttsPlayer.cancelActivePlayback()
        player.ttsPlayer.markAsPlayed()
        onTtsStreamStart?.invoke()
        pcmStreamActive = true
        pcmTtsEverActive = true
        pcmTtsPending = false
        awaitingUpstreamStreamEnd = false
        onFlushPendingPcmTts?.invoke()
        schedulePcmStarveFallback()
    }

    /**
     * Upstream-style early URL TTS: when HA streams the reply as a progressive URL
     * (no speaker PCM), begin playback on the cached RUN_START url. Mutually exclusive
     * with the PCM path via [urlTtsStreaming]/ttsPlayed/[urlFallbackCommitted] guards.
     */
    private fun tryStartEarlyUrlTts(commitFallback: Boolean = false): Boolean {
        if (stopRequested || urlTtsStreaming || pcmStreamActive) return false
        if (player.ttsPlayer.ttsPlayed) return false
        val url = earlyTtsUrl
        if (url.isNullOrBlank()) return false
        Log.d(TAG, "Early URL TTS streaming, url=$url, commitFallback=$commitFallback")
        urlTtsStreaming = true
        earlyTtsStarted = true
        pcmTtsPending = false
        if (commitFallback) {
            urlFallbackCommitted = true
        }
        onDiscardPendingPcmTts?.invoke()
        if (state.value !is Responding) {
            state.value = Responding
        }
        stopUplinkAudio()
        silenceTimeoutJob?.cancel()
        silenceTimeoutJob = null
        setupTtsCallbacks()
        player.ttsPlayer.markAsPlayed()
        player.ttsPlayer.playTts(url)
        return true
    }

    /**
     * Byte-based silence guard: after streaming is signaled, if no valid PCM bytes
     * arrive within [STREAMING_NO_PCM_FALLBACK_MS], play the cached URL and lock out PCM.
     */
    private fun scheduleNoPcmByteFallback() {
        if (urlFallbackCommitted || urlTtsStreaming || pcmStreamActive) return
        if (earlyTtsUrl.isNullOrBlank()) return
        if (noPcmFallbackJob?.isActive == true) return
        val currentSession = sessionId
        noPcmFallbackJob = scope.launch {
            delay(STREAMING_NO_PCM_FALLBACK_MS)
            if (sessionId != currentSession) return@launch
            if (urlFallbackCommitted || urlTtsStreaming || pcmStreamActive) return@launch
            if (player.ttsPlayer.ttsPlayed) return@launch
            if (pcmBytesReceived >= MIN_PCM_BYTES_ALIVE) return@launch
            Log.d(
                TAG,
                "No usable PCM bytes after ${STREAMING_NO_PCM_FALLBACK_MS}ms " +
                    "(received=$pcmBytesReceived), falling back to URL",
            )
            if (tryStartEarlyUrlTts(commitFallback = true)) {
                Log.d(TAG, "URL fallback started (byte watchdog)")
            }
        }
    }

    /**
     * After PCM playback starts, if bytes never reach an audible threshold, abandon
     * the speaker stream and commit to URL so underrun silence does not strand the user.
     */
    private fun schedulePcmStarveFallback() {
        pcmStarveJob?.cancel()
        if (urlFallbackCommitted || earlyTtsUrl.isNullOrBlank()) return
        val bytesAtStart = pcmBytesReceived
        val currentSession = sessionId
        pcmStarveJob = scope.launch {
            delay(PCM_STARVE_FALLBACK_MS)
            if (sessionId != currentSession) return@launch
            if (urlFallbackCommitted || !pcmStreamActive) return@launch
            if (pcmBytesReceived >= MIN_PCM_BYTES_ALIVE) return@launch
            Log.w(
                TAG,
                "PCM starved after start (bytes=$pcmBytesReceived, atStart=$bytesAtStart), " +
                    "abandoning speaker for URL",
            )
            abandonPcmForUrlFallback()
        }
    }

    private fun abandonPcmForUrlFallback() {
        if (stopRequested || urlFallbackCommitted) return
        val url = earlyTtsUrl
        if (url.isNullOrBlank()) return
        urlFallbackCommitted = true
        pcmStreamActive = false
        pcmTtsPending = false
        pcmTtsEverActive = false
        pcmStarveJob?.cancel()
        pcmStarveJob = null
        noPcmFallbackJob?.cancel()
        noPcmFallbackJob = null
        streamEndWatchdogJob?.cancel()
        streamEndWatchdogJob = null
        onAbandonPcmTts?.invoke()
        onDiscardPendingPcmTts?.invoke()
        urlTtsStreaming = true
        earlyTtsStarted = true
        if (state.value !is Responding) {
            state.value = Responding
        }
        setupTtsCallbacks()
        player.ttsPlayer.markAsPlayed()
        player.ttsPlayer.playTts(url)
        Log.d(TAG, "URL fallback after PCM starve, url=$url")
    }

    /**
     * After [TTS_END] while speaker PCM is active, recover if upstream never sends
     * [TTS_STREAM_END] (otherwise the session hangs in Responding).
     *
     * Stall detection — keep going while PCM bytes still arrive; only force-end
     * after [PCM_STREAM_END_STALL_MS] without new bytes, or the absolute max.
     */
    private fun scheduleMissingStreamEndFallback() {
        if (streamEndWatchdogJob?.isActive == true) return
        val currentSession = sessionId
        streamEndWatchdogJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var lastBytes = pcmBytesReceived
            var lastAdvanceAt = startedAt
            while (true) {
                delay(PCM_STREAM_END_POLL_MS)
                if (sessionId != currentSession) return@launch
                if (!pcmStreamActive) return@launch

                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= PCM_STREAM_END_ABSOLUTE_MAX_MS) {
                    Log.w(
                        TAG,
                        "TTS_STREAM_END missing after ${PCM_STREAM_END_ABSOLUTE_MAX_MS}ms " +
                            "(pcmBytes=$pcmBytesReceived), recovering",
                    )
                    finishMissingPcmStreamEnd()
                    return@launch
                }

                val bytes = pcmBytesReceived
                if (bytes > lastBytes) {
                    lastBytes = bytes
                    lastAdvanceAt = System.currentTimeMillis()
                    continue
                }

                val stalledFor = System.currentTimeMillis() - lastAdvanceAt
                if (stalledFor >= PCM_STREAM_END_STALL_MS) {
                    Log.w(
                        TAG,
                        "TTS_STREAM_END missing after ${stalledFor}ms stall " +
                            "(pcmBytes=$pcmBytesReceived), recovering",
                    )
                    finishMissingPcmStreamEnd()
                    return@launch
                }
            }
        }
    }

    private fun finishMissingPcmStreamEnd() {
        if (!pcmStreamActive) return
        if (pcmBytesReceived < MIN_PCM_BYTES_ALIVE &&
            !earlyTtsUrl.isNullOrBlank() &&
            !urlFallbackCommitted
        ) {
            abandonPcmForUrlFallback()
            return
        }
        pcmStreamActive = false
        pcmTtsPending = false
        pcmStarveJob?.cancel()
        pcmStarveJob = null
        onTtsStreamEnd?.invoke()
    }

    /**
     * Empty SPEAKER + URL reply: recover if upstream never sends [TTS_STREAM_END]
     * (otherwise Responding hangs because progressive WAV never hits ExoPlayer ENDED).
     */
    private fun scheduleMissingUpstreamStreamEndFallback() {
        if (streamEndWatchdogJob?.isActive == true) return
        val currentSession = sessionId
        streamEndWatchdogJob = scope.launch {
            delay(URL_UPSTREAM_STREAM_END_MISSING_MS)
            if (sessionId != currentSession) return@launch
            if (!awaitingUpstreamStreamEnd) return@launch
            if (state.value !is Responding) return@launch
            Log.w(
                TAG,
                "TTS_STREAM_END missing after ${URL_UPSTREAM_STREAM_END_MISSING_MS}ms " +
                    "for URL-only reply, completing",
            )
            awaitingUpstreamStreamEnd = false
            urlTtsGenerationEnded = true
            player.ttsPlayer.cancelActivePlayback()
            completeUrlTtsSession("missing TTS_STREAM_END (URL-only upstream)")
        }
    }

    /**
     * Give speaker PCM ([TTS_STREAM_START]) a brief head start before URL-only fallback
     * at TTS_START. The longer byte watchdog ([scheduleNoPcmByteFallback]) covers the
     * INTENT_PROGRESS wait; this only catches the immediate post-TTS_START gap.
     */
    private fun scheduleDeferredEarlyUrlTts() {
        if (earlyTtsUrl.isNullOrBlank()) return
        if (urlFallbackCommitted) return
        earlyUrlJob?.cancel()
        earlyUrlJob = scope.launch {
            delay(EARLY_URL_DEFER_MS)
            if (pcmStreamActive || urlTtsStreaming || urlFallbackCommitted) return@launch
            if (player.ttsPlayer.ttsPlayed) return@launch
            if (!pcmTtsPending) return@launch
            if (pcmBytesReceived >= MIN_PCM_BYTES_ALIVE) return@launch
            if (tryStartEarlyUrlTts(commitFallback = true)) {
                Log.d(TAG, "Deferred early URL TTS started (no speaker PCM bytes)")
            }
        }
    }

    /**
     * TTS_END means URL generation is done. Complete only after audio has actually played
     * out — never while ExoPlayer is still buffering (isPlaying=false before first frame).
     */
    private fun markUrlTtsGenerationComplete() {
        if (!urlTtsGenerationEnded) return
        if (player.ttsPlayer.isPlaying || player.ttsPlayer.isPaused) {
            // Playing, or started-but-rebuffering (chunked TTS often starves right
            // when HA finishes generating). A rebuffer at the TTS_END instant used
            // to be treated as "already idle" and hard-cut the reply mid-sentence.
            player.ttsPlayer.onPlaybackEnded = {
                player.ttsPlayer.onPlaybackEnded = null
                if (urlTtsGenerationEnded && state.value is Responding) {
                    completeUrlTtsSession("ExoPlayer playback ended after TTS_END")
                }
            }
            return
        }
        if (urlTtsPlaybackStarted && player.ttsPlayer.isStopped) {
            // Truly over (ExoPlayer idle/ended), not a buffer gap.
            completeUrlTtsSession("URL playback already stopped after TTS_END")
        }
        // else: still preparing; onTtsPlaybackStarted will call markUrlTtsGenerationComplete again
    }

    /**
     * Classic URL TTS (no empty-SPEAKER upstream close): recover when ExoPlayer never
     * reaches STATE_ENDED, or never becomes audible (e.g. focus stolen by Sendspin).
     * Not used when [awaitingUpstreamStreamEnd] — that path has its own watchdog.
     *
     * - Not started yet: [CLASSIC_URL_NO_START_MS] wall clock.
     * - Known duration: remaining + margin (updated if duration arrives late).
     * - Unknown duration: stall detection — keep going while position advances;
     *   only force-complete after [CLASSIC_URL_STALL_MS] without progress
     *   (or [CLASSIC_URL_ABSOLUTE_MAX_MS] absolute safety).
     * - Continuous conversation: if a reported duration expires while audio is still
     *   audible, abandon duration hard-cut for this run ([classicUrlPreferStallCompletion]).
     */
    private fun scheduleClassicUrlCompletionWatchdog(reason: String) {
        if (awaitingUpstreamStreamEnd) return
        if (!urlTtsGenerationEnded && !urlTtsStreaming) return
        classicUrlWatchdogJob?.cancel()
        val currentSession = sessionId

        if (!urlTtsPlaybackStarted) {
            Log.d(TAG, "Classic URL no-start watchdog ${CLASSIC_URL_NO_START_MS}ms ($reason)")
            classicUrlWatchdogJob = scope.launch {
                delay(CLASSIC_URL_NO_START_MS)
                if (sessionId != currentSession) return@launch
                if (state.value !is Responding) return@launch
                if (!urlTtsStreaming && !urlTtsGenerationEnded) return@launch
                if (urlTtsPlaybackStarted) {
                    // Started while waiting — hand off to stall/duration path.
                    scheduleClassicUrlCompletionWatchdog("playback started during no-start wait")
                    return@launch
                }
                Log.w(
                    TAG,
                    "Classic URL TTS never started after ${CLASSIC_URL_NO_START_MS}ms ($reason), forcing session complete",
                )
                player.ttsPlayer.cancelActivePlayback()
                completeUrlTtsSession("classic URL watchdog ($reason)")
            }
            return
        }

        if (pendingTtsDuration > 0L && !classicUrlPreferStallCompletion) {
            val remaining = (pendingTtsDuration - player.ttsPlayer.currentPosition)
                .coerceAtLeast(0L)
            val timeoutMs = remaining + CLASSIC_URL_DURATION_MARGIN_MS
            Log.d(
                TAG,
                "Classic URL duration watchdog ${timeoutMs}ms " +
                    "(remaining=$remaining duration=$pendingTtsDuration, $reason)",
            )
            classicUrlWatchdogJob = scope.launch {
                delay(timeoutMs)
                if (sessionId != currentSession) return@launch
                if (state.value !is Responding) return@launch
                if (!urlTtsStreaming && !urlTtsGenerationEnded) return@launch

                // Continuous only: bogus short duration must not hard-cut mid-reply.
                // Non-continuous keeps the historical cancel+complete behavior.
                if (isContinuousConversationEnabled() &&
                    (player.ttsPlayer.isPlaying || player.ttsPlayer.isPaused)
                ) {
                    classicUrlPreferStallCompletion = true
                    Log.w(
                        TAG,
                        "Classic URL duration expired while TTS still audible " +
                            "(pos=${player.ttsPlayer.currentPosition} " +
                            "duration=$pendingTtsDuration, $reason); " +
                            "continuous conversation → stall completion, no hard-cut",
                    )
                    scheduleClassicUrlCompletionWatchdog(
                        "continuous: duration untrusted while playing",
                    )
                    return@launch
                }

                Log.w(
                    TAG,
                    "Classic URL TTS incomplete after duration+margin ($reason), forcing session complete",
                )
                player.ttsPlayer.cancelActivePlayback()
                completeUrlTtsSession("classic URL watchdog ($reason)")
            }
            return
        }

        Log.d(TAG, "Classic URL stall watchdog ($reason)")
        classicUrlWatchdogJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var lastPos = player.ttsPlayer.currentPosition
            var lastAdvanceAt = System.currentTimeMillis()
            var bufferingSince = 0L
            while (true) {
                delay(CLASSIC_URL_STALL_POLL_MS)
                if (sessionId != currentSession) return@launch
                if (state.value !is Responding) return@launch
                if (!urlTtsStreaming && !urlTtsGenerationEnded) return@launch

                // Late duration from progressive timeline — switch to duration path
                // (unless continuous already marked that duration untrusted).
                if (pendingTtsDuration > 0L && !classicUrlPreferStallCompletion) {
                    scheduleClassicUrlCompletionWatchdog("late duration ${pendingTtsDuration}ms")
                    return@launch
                }

                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= CLASSIC_URL_ABSOLUTE_MAX_MS) {
                    Log.w(
                        TAG,
                        "Classic URL TTS hit absolute max ${CLASSIC_URL_ABSOLUTE_MAX_MS}ms ($reason)",
                    )
                    player.ttsPlayer.cancelActivePlayback()
                    completeUrlTtsSession("classic URL absolute max ($reason)")
                    return@launch
                }

                val pos = player.ttsPlayer.currentPosition
                if (pos >= lastPos + CLASSIC_URL_POSITION_EPS_MS) {
                    lastPos = pos
                    lastAdvanceAt = System.currentTimeMillis()
                    bufferingSince = 0L
                    continue
                }
                // Still audible: do not stall-kill while ExoPlayer says it is playing.
                // Natural ENDED / idle completion remains the primary finish path.
                if (player.ttsPlayer.isPlaying) {
                    bufferingSince = 0L
                    continue
                }
                // Rebuffering (generation slower than playback on chunked tts_proxy)
                // is not a stall — keep waiting. But if buffering drags on, run a
                // lightweight reachability probe so a dead network doesn't hold the
                // session until the absolute max.
                if (player.ttsPlayer.isPaused) {
                    val now = System.currentTimeMillis()
                    if (bufferingSince == 0L) bufferingSince = now
                    if (now - bufferingSince >= CLASSIC_URL_BUFFERING_PROBE_MS) {
                        if (isTtsEndpointReachable()) {
                            // Endpoint alive: generation is slow, give it another window.
                            bufferingSince = System.currentTimeMillis()
                        } else {
                            if (sessionId != currentSession) return@launch
                            if (state.value !is Responding) return@launch
                            Log.w(
                                TAG,
                                "Classic URL TTS buffering >${CLASSIC_URL_BUFFERING_PROBE_MS}ms " +
                                    "and endpoint unreachable ($reason), forcing session complete",
                            )
                            player.ttsPlayer.cancelActivePlayback()
                            completeUrlTtsSession("classic URL network dead while buffering ($reason)")
                            return@launch
                        }
                    }
                    lastAdvanceAt = System.currentTimeMillis()
                    continue
                }
                bufferingSince = 0L

                val stalledFor = System.currentTimeMillis() - lastAdvanceAt
                if (stalledFor >= CLASSIC_URL_STALL_MS) {
                    Log.w(
                        TAG,
                        "Classic URL TTS stalled ${stalledFor}ms at pos=$pos ($reason), forcing session complete",
                    )
                    player.ttsPlayer.cancelActivePlayback()
                    completeUrlTtsSession("classic URL stall ($reason)")
                    return@launch
                }
            }
        }
    }

    /**
     * Lightweight reachability probe for the current TTS stream endpoint: one TCP
     * connect (retried once) with a short timeout, on the IO dispatcher. Only runs
     * after [CLASSIC_URL_BUFFERING_PROBE_MS] of continuous rebuffering, so the
     * normal playback path never pays for it. Returns true when unsure — cutting a
     * live reply is worse than waiting for the absolute-max ceiling.
     */
    private suspend fun isTtsEndpointReachable(): Boolean {
        val url = player.ttsPlayer.lastPlayedUrl ?: return true
        val (host, port) = try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return true
            val h = uri.host ?: return true
            val p = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
            h to p
        } catch (_: Exception) {
            return true
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repeat(2) { attempt ->
                try {
                    java.net.Socket().use { socket ->
                        socket.connect(
                            java.net.InetSocketAddress(host, port),
                            CLASSIC_URL_PROBE_TIMEOUT_MS.toInt(),
                        )
                    }
                    return@withContext true
                } catch (e: Exception) {
                    Log.w(TAG, "TTS endpoint probe failed (attempt ${attempt + 1}): $host:$port ${e.message}")
                }
            }
            false
        }
    }

    private fun completeUrlTtsSession(reason: String) {
        if (state.value !is Responding) {
            awaitingUpstreamStreamEnd = false
            urlTtsGenerationEnded = false
            urlTtsPlaybackStarted = false
            urlTtsStreaming = false
            classicUrlWatchdogJob?.cancel()
            classicUrlWatchdogJob = null
            return
        }
        if (!urlTtsStreaming && !urlTtsGenerationEnded) return
        Log.d(TAG, "URL TTS session complete: $reason")
        streamEndWatchdogJob?.cancel()
        streamEndWatchdogJob = null
        classicUrlWatchdogJob?.cancel()
        classicUrlWatchdogJob = null
        awaitingUpstreamStreamEnd = false
        urlTtsGenerationEnded = false
        urlTtsPlaybackStarted = false
        urlTtsStreaming = false
        player.ttsPlayer.onPlaybackEnded = null
        player.ttsPlayer.triggerCompletion()
        onTtsPlaybackSettled?.invoke()
    }

    fun processAudioEnergy(audioBytes: ByteArray) {
        if (state.value !is Listening) return
        silenceDetector.processAudio(audioBytes)
    }

    /** Before playing announce/TTS audio from VoiceAssistantAnnounceRequest. */
    fun prepareTtsPlayback() {
        setupTtsCallbacks()
        player.ttsPlayer.markAsPlayed()
    }

    /**
     * Same as [prepareTtsPlayback] but for a reply Ava synthesised itself (local intent
     * fallback): captions / duration relays need the text, which no HA event carried.
     */
    fun prepareLocalReplyPlayback(text: String) {
        currentTtsText = text
        pendingTtsDuration = 0L
        prepareTtsPlayback()
    }

    fun cancelWakeTimeout() {
        // No-op: config intercept is handled by RUN_END while AwaitingHaDecision, not a timer.
    }

    /**
     * Call before a new wake when the previous Active run was interrupted
     * (stop + re-wake) so the next RUN_END is not treated as config intercept.
     */
    fun markInterruptedPipeline() {
        expectStaleRunEnd = true
        Log.d(TAG, "markInterruptedPipeline: next RUN_END while awaiting may be stale")
    }

    fun prepareForNewWake() {
        silenceTimeoutJob?.cancel()
        silenceTimeoutJob = null
        sttVadEndJob?.cancel()
        sttVadEndJob = null
        clearFabRenewWait()
        fabRenewPending = false
        sessionId++
        silenceDetector.reset()
        haVadStarted = false
        stopRequested = false
        setHaPhase(HaPipelinePhase.AwaitingHaDecision)
    }

    fun abandonFabRenewIfPending() {
        if (!fabRenewPending) return
        fabRenewPending = false
        clearFabRenewWait()
    }

    /**
     * Push-to-talk release: this window should finalize. Do not let
     * [expectTrailingSttEnd] steal I2's STT_END. [acceptReplies] when the
     * live window is old enough that inject / INTENT may settle.
     */
    fun noteManualSpeechEnd(acceptReplies: Boolean = false) {
        fabRenewPending = false
        clearFabRenewWait()
        expectTrailingSttEnd = false
        ignoreUntilRunStart = false
        ignoreUntilSttStart = false
        if (acceptReplies) dropOldReply = false
    }

    fun isFabRenewArmed(): Boolean =
        fabRenewPending || ignoreUntilRunStart || ignoreUntilSttStart || dropOldReply

    fun markRenewInFlight() {
        val alreadyArmed = ignoreUntilRunStart || ignoreUntilSttStart || dropOldReply
        ignoreUntilRunStart = true
        ignoreUntilSttStart = true
        dropOldReply = true
        dropNextRunEnd = true
        if (!alreadyArmed) {
            expectTrailingSttEnd = !renewFromSttEnd
            renewFromSttEnd = false
        }
        fabRenewPending = false
        clearFabRenewWait()
    }

    fun setWaking(value: Boolean) {
        isWaking = value
        if (value) {
            if (haPipelinePhase == HaPipelinePhase.Idle) {
                setHaPhase(HaPipelinePhase.AwaitingHaDecision)
            }
            silenceTimeoutJob?.cancel()
            silenceTimeoutJob = null
            sttVadEndJob?.cancel()
            sttVadEndJob = null
            silenceDetector.reset()
        }
    }

    fun setWakePhase(value: Boolean) {
        isWakePhase = value
    }

    fun reset() {
        silenceTimeoutJob?.cancel()
        silenceTimeoutJob = null
        sttVadEndJob?.cancel()
        sttVadEndJob = null
        clearFabRenewWait()
        fabRenewPending = false
        ignoreUntilRunStart = false
        ignoreUntilSttStart = false
        dropOldReply = false
        dropNextRunEnd = false
        expectTrailingSttEnd = false
        renewFromSttEnd = false
        sessionId++
        silenceDetector.reset()
        setHaPhase(HaPipelinePhase.Idle)
        expectStaleRunEnd = false
        isWakePhase = false
        continueConversation = true
        stopWordProtectionEndTime = 0L
        haVadStarted = false
        stopRequested = false
        intentEnded = false
        earlyTtsUrl = null
        earlyTtsStarted = false
        assistantTextShown = false
        pcmStreamActive = false
        pcmTtsEverActive = false
        pcmTtsPending = false
        urlTtsStreaming = false
        awaitingUpstreamStreamEnd = false
        urlTtsGenerationEnded = false
        urlTtsPlaybackStarted = false
        pcmBytesReceived = 0L
        urlFallbackCommitted = false
        replySuppressed = false
        deferredReply = null
        earlyUrlJob?.cancel()
        earlyUrlJob = null
        noPcmFallbackJob?.cancel()
        noPcmFallbackJob = null
        pcmStarveJob?.cancel()
        pcmStarveJob = null
        streamEndWatchdogJob?.cancel()
        streamEndWatchdogJob = null
        classicUrlWatchdogJob?.cancel()
        classicUrlWatchdogJob = null
        onDiscardPendingPcmTts?.invoke()
        currentTtsText = ""
        audioInput.clearTtsWakeEchoRisk()
        pendingTtsDuration = 0L
        classicUrlPreferStallCompletion = false
    }

    private fun isContinuousConversationEnabled(): Boolean = continuousConversationSnapshot

    private fun setHaPhase(phase: HaPipelinePhase) {
        if (haPipelinePhase != phase) {
            Log.d(TAG, "haPipelinePhase $haPipelinePhase → $phase")
        }
        haPipelinePhase = phase
        isWaking = phase == HaPipelinePhase.AwaitingHaDecision
    }

    fun setStopRequested(value: Boolean) {
        stopRequested = value
    }

    /**
     * Same gate as RUN_START: after stop-word / abort, late HA voice events must not
     * re-enter Responding or start TTS.
     */
    private fun ignoreIfStopRequested(eventName: String): Boolean {
        if (!stopRequested) return false
        Log.d(TAG, "$eventName ignored, stop was requested")
        return true
    }

    private fun ignoreStaleRenew(eventName: String): Boolean {
        if (!ignoreUntilRunStart && !ignoreUntilSttStart && !dropOldReply) return false
        Log.d(TAG, "$eventName ignored, FAB listen renew in flight")
        return true
    }

    private fun launchFabRenewIfStillWanted(sttText: String?) {
        if (!sttText.isNullOrBlank()) onSttText?.invoke(sttText)
        if (shouldRenewListen?.invoke() == true) {
            renewFromSttEnd = true
            // Arm gates before the coroutine so a fast RUN_END cannot settle.
            markRenewInFlight()
            scope.launch {
                if (onRenewListen?.invoke() != true) settleDeclinedRenew()
            }
        } else {
            settleDeclinedRenew()
        }
    }

    private fun scheduleFabRenewWait() {
        fabRenewWaitJob?.cancel()
        fabRenewWaitJob = scope.launch {
            delay(2_000)
            if (!fabRenewPending) return@launch
            fabRenewPending = false
            if (shouldRenewListen?.invoke() != true) {
                settleDeclinedRenew()
                return@launch
            }
            // This job is the one prepareForNewWake() cancels. Awaiting the
            // splice here dies at the first suspend inside the start request:
            // no new pipeline, no RUN_START watchdog, and this window's
            // stt-no-text / RUN_END are then dropped as stale. Arm the gates
            // first, then splice on a sibling, same as STT_END.
            markRenewInFlight()
            scope.launch {
                if (onRenewListen?.invoke() != true) settleDeclinedRenew()
            }
        }
    }

    /**
     * A FAB splice was requested at VAD_END but did not happen (finger lifted,
     * turn moved on, or the re-wake was refused). HA's VAD already closed this
     * window, so continue it as a normal end-of-speech instead of staying parked
     * in Listening with the stale-event gate still up.
     */
    private fun settleDeclinedRenew() {
        renewFromSttEnd = false
        ignoreUntilRunStart = false
        ignoreUntilSttStart = false
        dropOldReply = false
        if (state.value == Listening) {
            state.value = Processing
            onProcessingStarted?.invoke()
        }
        if (sttVadEndJob?.isActive != true) {
            scheduleSttHangover(STT_END_HANGOVER_MS, "FAB renew declined")
        }
    }

    private fun failPipeline(code: String, message: String) {
        Log.e(TAG, "PIPELINE_ERROR: code=$code, message=$message, phase=$haPipelinePhase ui=${state.value}")
        onPipelineError?.invoke(code, message)
        audioInput.isStreaming = false
        setHaPhase(HaPipelinePhase.Idle)
        scope.launch { onStopSatellite() }
    }

    private fun clearFabRenewWait() {
        fabRenewWaitJob?.cancel()
        fabRenewWaitJob = null
    }

    private fun isRenewablePipelineError(code: String): Boolean =
        code.startsWith("stt-no-text") ||
            code.contains("timeout") ||
            code.contains("timed-out")

    fun isStopWordProtected(): Boolean {
        return System.currentTimeMillis() < stopWordProtectionEndTime
    }

    private fun isStreamingTtsMode(): Boolean = streamingTtsSubtitlesSnapshot

    private fun scheduleSttHangover(delayMs: Long, reason: String) {
        val currentSession = sessionId
        sttVadEndJob?.cancel()
        sttVadEndJob = scope.launch {
            delay(delayMs)
            if (sessionId != currentSession) {
                Log.d(TAG, "$reason: session changed ($currentSession→$sessionId), skipping audio stop")
                return@launch
            }
            if (state.value == Processing && audioInput.isStreaming) {
                Log.d(TAG, "$reason: delayed audio stop completed after ${delayMs}ms")
                stopUplinkAudio()
            }
        }
    }

    private fun stopUplinkAudio() {
        if (!audioInput.isStreaming) return
        audioInput.isStreaming = false
        scope.launch { onSendAudioEnd?.invoke() }
    }

    private fun claimReply(stage: ReplyStage) {
        Log.i(TAG, "assistant reply claimed by local intent fallback at $stage")
        replySuppressed = true
        deferredReply = null
        // The swallowed reply must not re-open the mic for a follow-up turn.
        continueConversation = false
    }

    /**
     * Last chance for a deferred reply: TTS_END (URL ready, nothing played yet) or RUN_END
     * when the pipeline has no TTS stage. Claim → suppressed like any other claim; otherwise
     * the reply is finally shown if it never reached the caption path.
     */
    private fun settleDeferredReply(showCaptionIfUnseen: Boolean) {
        val speech = deferredReply ?: return
        deferredReply = null
        if (replySuppressed) return
        if (replyInterceptor?.invoke(speech, ReplyStage.TtsEnd) == ReplyDecision.Claim) {
            claimReply(ReplyStage.TtsEnd)
            return
        }
        if (showCaptionIfUnseen && currentTtsText.isEmpty()) {
            val spoken = TtsMdFilter.apply(speech)
            if (spoken.isNotEmpty()) {
                currentTtsText = spoken
                onTtsText?.invoke(spoken)
                if (!assistantTextShown) {
                    assistantTextShown = true
                    onConversationText?.invoke("assistant", spoken)
                }
            }
        }
    }

    private fun applyAssistantSpeech(speech: String, fromIntentEnd: Boolean) {
        if (speech.isBlank() || isTTSAboutError(speech)) return
        if (replySuppressed) return
        val stage = if (fromIntentEnd) ReplyStage.IntentEnd else ReplyStage.TtsStart
        when (replyInterceptor?.invoke(speech, stage) ?: ReplyDecision.Pass) {
            ReplyDecision.Claim -> {
                claimReply(stage)
                return
            }
            ReplyDecision.Defer -> {
                deferredReply = speech
                if (fromIntentEnd) {
                    // Hold captions; TTS_START re-offers the same text.
                    Log.d(TAG, "assistant reply deferred at INTENT_END")
                    return
                }
                // TTS_START: set playback up normally, final answer comes at TTS_END.
                Log.d(TAG, "assistant reply still deferred at TTS_START")
            }
            ReplyDecision.Pass -> deferredReply = null
        }
        val spoken = TtsMdFilter.apply(speech)
        if (spoken.isNotEmpty()) {
            currentTtsText = spoken
            // Self-wake screen: a response that mentions the wake phrase will utter it
            // through the speaker, and its echo would trigger the wake engines.
            audioInput.noteTtsTextForWakeEchoRisk(spoken)
            onTtsText?.invoke(spoken)
            if (!assistantTextShown) {
                if (!isStreamingTtsMode()) {
                    // Text is ready early for standard TTS; floating captions intentionally wait
                    // for playback (VoiceSatelliteService onTtsDurationReady). Keep the callback
                    // for non-overlay consumers / logging.
                    assistantTextShown = true
                    onConversationText?.invoke("assistant", spoken)
                }
            }
        }
        LightKeywordDetector.detectDeviceAction(speech)?.let { action ->
            onDeviceAction?.invoke(action)
        }
        if (!fromIntentEnd && !smartContinueSnapshot) {
            if (exitKeywordStopSnapshot) {
                if (LightKeywordDetector.isExitKeyword(speech)) {
                    continueConversation = false
                }
            } else if (
                questionMarkContinueSnapshot &&
                !LightKeywordDetector.endsWithQuestionMark(speech)
            ) {
                continueConversation = false
            }
        }
    }

    private fun isSTTError(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lowerText = text.lowercase()
        return lowerText.contains("list index out of range") ||
            lowerText.contains("索引超出范围") ||
            lowerText.contains("索引错误") ||
            (lowerText.contains("index") && lowerText.contains("range") && lowerText.contains("error"))
    }

    private fun isTTSAboutError(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lowerText = text.lowercase()
        return lowerText.contains("list index out of range") ||
            lowerText.contains("索引超出范围") ||
            lowerText.contains("索引错误") ||
            (lowerText.contains("index") && lowerText.contains("range") && lowerText.contains("error"))
    }

    private fun setupTtsCallbacks() {
        val textForPlayback = currentTtsText
        player.ttsPlayer.onTtsDurationReady = durationReady@{ durationMs ->
            if (durationMs <= 0L || durationMs == pendingTtsDuration) return@durationReady
            pendingTtsDuration = durationMs
            // Progressive HA tts_proxy often learns duration seconds after start — forward
            // late duration so floating caption paging can actually run.
            onTtsDurationReady?.invoke(durationMs, textForPlayback)
            if (urlTtsGenerationEnded && !awaitingUpstreamStreamEnd) {
                scheduleClassicUrlCompletionWatchdog("duration ready ${durationMs}ms")
            }
        }
        player.ttsPlayer.onTtsPlaybackStarted = {
            if (urlTtsStreaming || urlTtsGenerationEnded) {
                urlTtsPlaybackStarted = true
            }
            Log.d(TAG, "TTS playback started, duration=$pendingTtsDuration, text='${textForPlayback.take(20)}...'")
            onTtsDurationReady?.invoke(pendingTtsDuration, textForPlayback)
            onTtsPlaybackStarted?.invoke(textForPlayback)
            if (urlTtsGenerationEnded) {
                markUrlTtsGenerationComplete()
            }
            if (urlTtsGenerationEnded && !awaitingUpstreamStreamEnd) {
                scheduleClassicUrlCompletionWatchdog("playback started")
            }
        }
        player.ttsPlayer.onTtsProgressUpdate = { currentMs, totalMs ->
            onTtsProgressUpdate?.invoke(currentMs, totalMs, textForPlayback)
        }
        player.ttsPlayer.onTtsPlaybackError = {
            Log.w(TAG, "TTS playback error, url unreachable or source error")
            onTtsPlaybackError?.invoke()
        }
        player.ttsPlayer.onAudioFocusLoss = audioFocusLoss@{
            if (state.value !is Responding) return@audioFocusLoss
            if (!urlTtsStreaming && !urlTtsGenerationEnded) return@audioFocusLoss
            Log.w(TAG, "URL TTS lost audio focus while Responding, completing session")
            player.ttsPlayer.cancelActivePlayback()
            completeUrlTtsSession("audio focus loss")
        }
    }
}
