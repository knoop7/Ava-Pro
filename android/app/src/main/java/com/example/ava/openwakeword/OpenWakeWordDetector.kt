package com.example.ava.openwakeword

import android.util.Log
import com.example.ava.microwakeword.MicroVad
import com.example.ava.microwakeword.WakeWordEngineDetector
import com.example.ava.microwakeword.WakeWordLiveProbe
import com.example.ava.microwakeword.WakeWordProvider
import com.example.ava.microwakeword.WakeVadPolicy
import com.example.ava.settings.WakeWordEngine
import com.example.ava.utils.fillFrom
import com.example.ava.wakelearn.WakeLearnTuning
import com.example.microfeatures.MicroFrontend
import com.example.microfeatures.OpenKeywordNativeConfig
import com.example.microfeatures.OpenWakeWordEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpenWakeWordDetector(
    private val provider: OpenWakeWordProvider,
    private val vadProvider: WakeWordProvider? = null,
) : WakeWordEngineDetector {
    private var engine: OpenWakeWordEngine? = null
    private var inputGainLinear = 1f
    private val chunkBuffer = FloatArray(OpenWakeWordEngine.CHUNK_SAMPLES)
    private var chunkFill = 0
    private val pendingCutoffs = mutableMapOf<String, Float>()
    private var loggedNullEngine = false

    // Neural voice gate, same model and post-hoc role as the micro detector's:
    // the native engine's only speech check is an RMS energy lookback, which
    // passes any loud non-speech (TV, music, machinery). The gate never blocks
    // scoring — it only suppresses a wake the classifier already accepted, and
    // it fails open if the model or TFLite runtime is unavailable.
    private var vad: MicroVad? = null
    private var vadInitFailed = false
    private var vadFrontend: MicroFrontend? = null
    private var vadFrontendInitFailed = false
    private val vadBuffer = ByteBuffer.allocateDirect(VAD_BYTES_PER_CHUNK)

    private val activeKeywordIds = mutableListOf<String>()

    /**
     * Extra-strictness levels per wake word id. Level 2 bumps the consecutive-hit gate
     * (non-verifier models) and switches the VAD wake gate to the strict rule; level 1
     * is handled by the caller (offline re-verify of the mic clip).
     */
    private val extraStrictness = mutableMapOf<String, Int>()

    /** Loaded keyword metadata for Voice Stats (id / phrase / manifest threshold). */
    private data class KeywordProbeInfo(
        val id: String,
        val phrase: String,
        val manifestThreshold: Float,
        val hasBuiltInVerifier: Boolean,
        // Sidecar near-word verifier head (`<id>_verifier.onnx`) is loaded; its veto
        // threshold is baked into the native config per extra-strictness level.
        val hasVerifierHead: Boolean = false,
    )

    private val loadedKeywords = mutableListOf<KeywordProbeInfo>()
    private val chunkBudget = WakeChunkBudgetTracker()

    // Offline echo-verify engine: burst-scores a finite PCM clip (the playback
    // reference) with the same keyword configs as the live engine. Its own lock —
    // never `this` — so a ~100–400 ms burst pass cannot stall the live detect()
    // path mid-playback. Lock order is always `this` → [offlineVerifyLock].
    private val offlineVerifyLock = Any()
    private var offlineVerifyEngine: OpenWakeWordEngine? = null
    private var offlineVerifyConfigs: Array<OpenKeywordNativeConfig> = emptyArray()
    private var offlineVerifyActiveId: String? = null
    private val offlineVerifyThresholds = mutableMapOf<String, Float>()

    // Pause expensive native inference after an adaptive 150–250 ms silence run.
    // Confident silence closes quickly; VAD values near the speech boundary get
    // more time. Closing is deferred until this call's PCM has reached native.
    private var computeGateOpen = true
    private var closeComputeGateAfterDetect = false
    private var gateSilenceSamples = 0L
    private var vadSpeechSeen = false
    // Parallel state for the strict VAD rule (extra-strictness level 2): stricter
    // decision (average AND two frames) and a shorter trigger hangover.
    private var strictGateSilenceSamples = 0L
    private var vadStrictSpeechSeen = false
    // Audio samples processed since last reset/init. The VAD model needs
    // warmup before its output is reliable; during the warmup window
    // vadSpeechSeen stays false to prevent spurious cold-start detections.
    private var totalSamplesProcessed = 0L

    private fun ensureVad(): MicroVad? {
        val vp = vadProvider ?: return null
        if (vadInitFailed) return null
        vad?.let { return it }
        return try {
            val vadMeta = vp.getWakeWords().firstOrNull { it.wakeWord.type == "micro" }
            if (vadMeta == null) {
                vadInitFailed = true
                Log.w(TAG, "No micro VAD manifest found, voice gate disabled")
                null
            } else {
                MicroVad(
                    model = vp.loadWakeWordModel(vadMeta.wakeWord.model),
                    probabilityCutoff = vadMeta.wakeWord.micro.probability_cutoff,
                    slidingWindowSize = vadMeta.wakeWord.micro.sliding_window_size,
                ).also {
                    vad = it
                    Log.i(
                        TAG,
                        "micro VAD gate enabled cutoff=${vadMeta.wakeWord.micro.probability_cutoff} " +
                            "window=${vadMeta.wakeWord.micro.sliding_window_size}",
                    )
                }
            }
        } catch (e: Exception) {
            vadInitFailed = true
            Log.e(TAG, "Failed to load micro VAD model, voice gate disabled", e)
            null
        }
    }

    private fun ensureVadFrontend(): MicroFrontend? {
        if (vadFrontendInitFailed) return null
        vadFrontend?.let { return it }
        return try {
            MicroFrontend().also { vadFrontend = it }
        } catch (e: UnsatisfiedLinkError) {
            vadFrontendInitFailed = true
            Log.e(TAG, "MicroFrontend native library not available, voice gate disabled", e)
            null
        } catch (e: Exception) {
            vadFrontendInitFailed = true
            Log.e(TAG, "Failed to initialize MicroFrontend for VAD, voice gate disabled", e)
            null
        }
    }

    /**
     * Feed this call's audio through the VAD's own feature frontend. The native
     * openWakeWord engine consumes float mel windows internally, so the VAD gets
     * its 10 ms micro features from a parallel [MicroFrontend] over the same PCM
     * (ungained, exactly as the micro detector feeds it).
     */
    private fun feedVad(audio: ByteBuffer): MicroVad? {
        val gate = ensureVad() ?: return null
        val fe = ensureVadFrontend() ?: return null
        try {
            val src = audio.duplicate()
            vadBuffer.fillFrom(src)
            while (vadBuffer.flip().remaining() == VAD_BYTES_PER_CHUNK) {
                val out = fe.processSamples(vadBuffer)
                vadBuffer.position(vadBuffer.position() + out.samplesRead * VAD_BYTES_PER_SAMPLE)
                vadBuffer.compact()
                vadBuffer.fillFrom(src)
                if (out.features.isNotEmpty()) gate.processAudioFeatures(out.features)
            }
            vadBuffer.compact()
        } catch (e: Exception) {
            Log.e(TAG, "VAD feature path failed, voice gate disabled", e)
            vadInitFailed = true
            vadBuffer.clear()
            return null
        }
        return gate
    }

    @Synchronized
    override fun setActiveWakeWords(wakeWordIds: List<String>) {
        engine?.close()
        engine = null
        chunkFill = 0
        chunkBudget.reset()
        computeGateOpen = true
        closeComputeGateAfterDetect = false
        gateSilenceSamples = 0
        vadSpeechSeen = false
        strictGateSilenceSamples = 0
        vadStrictSpeechSeen = false
        totalSamplesProcessed = 0
        activeKeywordIds.clear()
        activeKeywordIds.addAll(wakeWordIds)
        loadedKeywords.clear()
        synchronized(offlineVerifyLock) {
            offlineVerifyEngine?.close()
            offlineVerifyEngine = null
            offlineVerifyConfigs = emptyArray()
            offlineVerifyActiveId = null
        }
        if (wakeWordIds.isEmpty()) return

        runCatching {
            val embedding = provider.loadEmbedding()
            val keywords = wakeWordIds.mapNotNull { id ->
                runCatching {
                    val model = provider.loadModel(id)
                    // Touching hasBuiltInVerifier here is what loads the model bytes,
                    // which this path needs anyway. Verifier models (official
                    // hey_jarvis) are single-spike by design: they keep their
                    // manifest gate instead of the hits=2 upgrade, and a cross-engine
                    // leftover cutoff falls back to the manifest instead of the 0.80
                    // ceiling, which measured 0/8 recall on them.
                    val hasVerifier = model.hasBuiltInVerifier
                    // pendingCutoffs keeps the raw stored setting; resolution happens
                    // here, against the real manifest. Writing the resolved value back
                    // would let a pre-load resolve (manifest unknown, assumed 0.5)
                    // permanently clobber a setting that is valid for this model.
                    val requested = pendingCutoffs[model.id] ?: -1f
                    val cutoff = OpenWakeWordCutoffPolicy.resolveRequestedCutoff(
                        manifestCutoff = model.threshold,
                        requestedCutoff = requested,
                        hasBuiltInVerifier = hasVerifier,
                    )
                    if (requested > 0f && kotlin.math.abs(requested - cutoff) > 0.0005f) {
                        Log.i(
                            TAG,
                            "id=${model.id} stored cutoff ${"%.2f".format(requested)} " +
                                "outside open working range — using ${"%.2f".format(cutoff)}",
                        )
                    }
                    val extraLevel = extraStrictness[model.id] ?: 0
                    val verifierHead = provider.loadVerifier(model.id)
                    val gate = OpenWakeWordCutoffPolicy.applyExtraStrictness(
                        gate = OpenWakeWordCutoffPolicy.effectiveGate(
                            manifestWindow = model.manifest.openwakeword?.slidingWindowSize ?: 1,
                            manifestHits = model.manifest.openwakeword?.requiredHits ?: 1,
                            hasBuiltInVerifier = hasVerifier,
                        ),
                        hasBuiltInVerifier = hasVerifier,
                        extraLevel = extraLevel,
                        hasVerifierHead = verifierHead != null,
                    )
                    if (hasVerifier) {
                        Log.i(
                            TAG,
                            "id=${model.id} has a built-in verifier (ONNX If) — " +
                                "manifest gate window=${gate.slidingWindow} " +
                                "hits=${gate.requiredHits} runs as written",
                        )
                    }
                    val verifierThreshold = if (verifierHead != null) {
                        WakeLearnTuning.effectiveVetoThreshold(
                            OpenWakeWordCutoffPolicy.verifierThreshold(extraLevel),
                        )
                    } else {
                        0f
                    }
                    if (verifierHead != null) {
                        Log.i(
                            TAG,
                            "id=${model.id} near-word verifier head loaded " +
                                "(${verifierHead.dims} dims) veto below ${"%.2f".format(verifierThreshold)} " +
                                "at extra level $extraLevel",
                        )
                    }
                    val config = OpenKeywordNativeConfig(
                        id = model.id,
                        displayName = model.displayName,
                        onnxBytes = model.onnxBytes,
                        threshold = cutoff,
                        requiredHits = gate.requiredHits,
                        cooldownMs = model.cooldownMs,
                        stopClassifier = model.manifest.stopClassifier,
                        // The high-confidence fast lane stays on at every strictness: on the
                        // 1,056-clip matrix it changed no accept/reject decision (negatives
                        // peak 0.798, under its 0.85 floor), so disabling it buys no rejection
                        // and only costs recall once hits=3 becomes 5 consecutive 40 ms ticks.
                        allowAlternatePaths = model.manifest.alternatePaths,
                        hasBuiltInVerifier = hasVerifier,
                        slidingWindowSize = gate.slidingWindow,
                        rescueBytes = provider.loadRescue(model.id),
                        verifierWeights = verifierHead?.weights,
                        verifierBias = verifierHead?.bias ?: 0f,
                        verifierThreshold = verifierThreshold,
                        requiresStrictVad = OpenWakeWordCutoffPolicy.strictVadFor(
                            extraLevel = extraLevel,
                            hasVerifierHead = verifierHead != null,
                            hasBuiltInVerifier = hasVerifier,
                        ),
                    )
                    loadedKeywords.add(
                        KeywordProbeInfo(
                            id = model.id,
                            phrase = model.displayName.ifBlank { model.id },
                            manifestThreshold = model.threshold,
                            hasBuiltInVerifier = hasVerifier,
                            hasVerifierHead = verifierHead != null,
                        ),
                    )
                    config
                }.onFailure { t ->
                    Log.w(TAG, "skip missing openWakeWord model id=$id", t)
                }.getOrNull()
            }.toTypedArray()
            if (keywords.isEmpty()) {
                loadedKeywords.clear()
                Log.w(TAG, "no loadable openWakeWord models in $wakeWordIds")
                return
            }
            engine = OpenWakeWordEngine.create(embedding, keywords)?.also { created ->
                created.setActiveKeywords(keywords.map { it.id }.toTypedArray())
            }
            if (engine == null) {
                Log.e(TAG, "OpenWakeWordEngine.create returned null for $wakeWordIds")
            } else {
                loggedNullEngine = false
                // Same configs (resolved thresholds, gates, rescue heads) feed the
                // lazily-created echo-verify engine, so both judge PCM identically.
                synchronized(offlineVerifyLock) {
                    offlineVerifyConfigs = keywords
                }
            }
        }.onFailure { t ->
            Log.e(TAG, "Failed to initialize openWakeWord keywords=$wakeWordIds", t)
        }
    }

    @Synchronized
    fun setInputGainLinear(gainLinear: Float) {
        inputGainLinear = gainLinear.coerceAtLeast(0f)
    }

    @Synchronized
    override fun detect(audio: ByteBuffer): List<WakeWordEngineDetector.DetectionResult> {
        val nativeEngine = engine
        if (nativeEngine == null) {
            if (!loggedNullEngine) {
                Log.e(TAG, "detect skipped — native engine is null")
                loggedNullEngine = true
            }
            return emptyList()
        }
        val detections = mutableListOf<WakeWordEngineDetector.DetectionResult>()
        val voiceGate = feedVad(audio)
        val speechRecentlySeen = updateComputeGate(nativeEngine, voiceGate, audio.remaining() / 2)
        val strictRecentlySeen = strictSpeechRecentlySeen(voiceGate)
        nativeEngine.setTriggerGates(speechRecentlySeen, strictRecentlySeen)
        val input = audio.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= 2) {
            // openWakeWord wants int16-range float, not +/-1.
            val sample = input.short.toFloat() * inputGainLinear
            chunkBuffer[chunkFill] = sample
            chunkFill++
            if (chunkFill == OpenWakeWordEngine.CHUNK_SAMPLES) {
                val startedNs = System.nanoTime()
                val result = nativeEngine.processChunk(chunkBuffer)
                chunkBudget.record(System.nanoTime() - startedNs)
                result.vetoedModelId?.let { vetoed ->
                    Log.i(TAG, "wake '$vetoed' vetoed by verifier head")
                    WakeLearnTuning.recordVeto(WakeWordEngine.OPEN_WAKE_WORD, vetoed)
                }
                val modelId = result.modelId
                if (result.detected && modelId != null) {
                    val strictVad = OpenWakeWordCutoffPolicy.strictVadFor(
                        extraLevel = extraStrictness[modelId] ?: 0,
                        hasVerifierHead = loadedKeywords.any { it.id == modelId && it.hasVerifierHead },
                        hasBuiltInVerifier = loadedKeywords.any { it.id == modelId && it.hasBuiltInVerifier },
                    )
                    val vadAllows = WakeVadPolicy.allowsWake(
                        strict = strictVad,
                        vadAvailable = voiceGate != null,
                        normalDecision = speechRecentlySeen,
                        strictDecision = strictRecentlySeen,
                    )
                    if (!vadAllows) {
                        Log.w(
                            TAG,
                            "wake '$modelId' conf=${"%.3f".format(result.score)} " +
                                "suppressed: VAD saw no speech" +
                                (if (strictVad) " [strict]" else "") +
                                " (vadProb=${"%.3f".format(voiceGate?.lastProbability ?: 0f)})",
                        )
                    } else {
                        detections.add(
                            WakeWordEngineDetector.DetectionResult(
                                wakeWordId = modelId,
                                wakeWordPhrase = result.wakeWordPhrase ?: modelId,
                                confidence = result.score.coerceIn(0f, 1f),
                                verifierWindow = nativeEngine.lastFireWindow(),
                            ),
                        )
                    }
                }
                chunkFill = 0
            }
        }
        finishComputeGateUpdate(nativeEngine)
        return detections
    }

    /** Advance VAD state and schedule an idle compute pause after this PCM is processed. */
    private fun updateComputeGate(engine: OpenWakeWordEngine, gate: MicroVad?, samples: Int): Boolean {
        totalSamplesProcessed += samples
        val speech = gate == null || gate.allowsWake()
        if (speech) {
            closeComputeGateAfterDetect = false
            gateSilenceSamples = 0
            if (gate != null && totalSamplesProcessed >= VAD_WARMUP_SAMPLES) {
                vadSpeechSeen = true
            }
            if (!computeGateOpen) {
                engine.setVoiceGate(true)
                computeGateOpen = true
            }
        } else {
            gateSilenceSamples += samples
            if (computeGateOpen &&
                gateSilenceSamples >= adaptiveGateCloseSamples(gate)
            ) {
                closeComputeGateAfterDetect = true
            }
        }
        // Strict rule tracks its own silence run; compute pausing stays on the normal
        // rule so extra strictness never changes when native inference sleeps.
        if (gate == null || gate.allowsWakeStrict()) {
            strictGateSilenceSamples = 0
            if (gate != null && totalSamplesProcessed >= VAD_WARMUP_SAMPLES) {
                vadStrictSpeechSeen = true
            }
        } else {
            strictGateSilenceSamples += samples
        }
        return gate == null ||
            (vadSpeechSeen && gateSilenceSamples <= TRIGGER_VAD_HANGOVER_SAMPLES)
    }

    /** Strict-rule counterpart of [updateComputeGate]'s return value (level 2 keywords). */
    private fun strictSpeechRecentlySeen(gate: MicroVad?): Boolean =
        gate == null ||
            (vadStrictSpeechSeen && strictGateSilenceSamples <= TRIGGER_VAD_HANGOVER_STRICT_SAMPLES)

    private fun adaptiveGateCloseSamples(gate: MicroVad?): Long {
        if (gate == null) return Long.MAX_VALUE
        val cutoff = gate.probabilityCutoff.coerceAtLeast(0.01f)
        val boundaryRatio = (gate.currentWindowMax() / cutoff).coerceIn(0f, 1f)
        val closeMs = GATE_CLOSE_MIN_MS +
            ((GATE_CLOSE_MAX_MS - GATE_CLOSE_MIN_MS) * boundaryRatio).toInt()
        return closeMs.toLong() * SAMPLES_PER_MS
    }

    private fun finishComputeGateUpdate(engine: OpenWakeWordEngine) {
        if (!closeComputeGateAfterDetect || !computeGateOpen) return
        engine.setVoiceGate(false)
        computeGateOpen = false
        closeComputeGateAfterDetect = false
    }

    /**
     * One row per loaded keyword (like micro), with the live per-model score and the
     * effective native threshold (manifest value or runtime sensitivity override).
     */
    @Synchronized
    fun liveProbe(): List<WakeWordLiveProbe> {
        val nativeEngine = engine
        if (nativeEngine == null || loadedKeywords.isEmpty()) {
            // Engine not up yet: still list the selected ids so the card shows what
            // is pending instead of a blank probe.
            return activeKeywordIds.map { id ->
                WakeWordLiveProbe(
                    id = id,
                    phrase = id,
                    windowAvg = 0f,
                    lastProb = 0f,
                    cutoff = OpenWakeWordCutoffPolicy.resolveRequestedCutoff(
                        manifestCutoff = OpenWakeWordCutoffPolicy.DEFAULT_THRESHOLD,
                        requestedCutoff = pendingCutoffs[id] ?: -1f,
                    ),
                )
            }
        }
        return buildList {
            for (kw in loadedKeywords) {
                val score = nativeEngine.keywordScore(kw.id)
                add(
                    WakeWordLiveProbe(
                        id = kw.id,
                        phrase = kw.phrase,
                        windowAvg = score,
                        lastProb = score,
                        cutoff = nativeEngine.keywordThreshold(
                            kw.id,
                            pendingCutoffs[kw.id] ?: kw.manifestThreshold,
                        ),
                    ),
                )
            }
            vad?.let { gate ->
                add(
                    WakeWordLiveProbe(
                        id = "vad",
                        phrase = "VAD",
                        windowAvg = gate.currentWindowMax(),
                        lastProb = gate.lastProbability,
                        cutoff = gate.probabilityCutoff,
                    ),
                )
            }
        }
    }

    @Synchronized
    fun budgetProbe(): WakeEngineBudgetProbe = chunkBudget.snapshot()

    /**
     * Burst-score a finite 16 kHz mono clip: does it contain [wakeWordId]'s phrase?
     * Used by the TTS-echo screen on the playback reference — the near-word class
     * ("hey Travis" on a hey_jarvis model) passes AEC, VAD, and the text screen, and
     * scores 0.94+ even at a −30 dB residual, so the only reliable evidence is that
     * the playback itself fires the same model.
     *
     * Runs the same native pipeline (thresholds, hit gates, verifier/rescue heads) on
     * a second engine instance, so live streaming state is untouched. The first call
     * pays the engine build (tens of ms: the OWWEMBD1 embedding blob decodes with no
     * protobuf walk, warm start dominates) once per active-set; subsequent calls cost
     * only the clip's chunk inferences. Deliberately NOT synchronized on `this`: the
     * live detect() path must keep consuming mic frames while this burst runs.
     */
    fun scoreOfflinePcm(
        pcm16Mono: ShortArray,
        wakeWordId: String,
        extraLevel: Int = 0,
    ): Boolean {
        // Same linear gain the live path applies. The ring stores pre-gain PCM;
        // scoring it at unity made extra-strictness either a no-op (gain=1) or
        // deaf (adapted gain > 1).
        val gain = synchronized(this) { inputGainLinear }
        synchronized(offlineVerifyLock) {
            val configs = offlineVerifyConfigs
            val config = configs.firstOrNull { it.id == wakeWordId } ?: return false
            val verifier = ensureOfflineVerifyEngineLocked() ?: return false
            if (offlineVerifyActiveId != wakeWordId) {
                verifier.setActiveKeywords(arrayOf(wakeWordId))
                offlineVerifyActiveId = wakeWordId
            }
            val streamingCutoff = offlineVerifyThresholds[wakeWordId] ?: config.threshold
            val verifyCutoff = OpenWakeWordCutoffPolicy.offlineVerifyCutoff(
                streamingCutoff = streamingCutoff,
                extraLevel = extraLevel,
                hasBuiltInVerifier = config.hasBuiltInVerifier,
            )
            val raised = kotlin.math.abs(verifyCutoff - streamingCutoff) > 0.0005f
            if (raised) verifier.updateThreshold(wakeWordId, verifyCutoff)
            // Hard reset, not the soft reset(): the persistent engine otherwise
            // keeps the previous verify's mel/embedding residue (which splices
            // with this clip's leading silence into a fake "phrase + tail" hit
            // on stale audio) and its engine-time cooldown (last_trigger_ms
            // survives soft reset; a 1.6 s burst never gets past a 2 s
            // cooldown, so every verify after a confirmed one auto-failed).
            // resetForVerify also leaves official warmup/lookback off so a
            // short hey_jarvis spike after leading quiet is not zeroed.
            verifier.resetForVerify()
            verifier.setVoiceGate(true)
            val startedNs = System.nanoTime()
            val chunk = FloatArray(OpenWakeWordEngine.CHUNK_SAMPLES)
            var offset = 0
            var fired = false
            var peak = 0f
            try {
                while (offset + OpenWakeWordEngine.CHUNK_SAMPLES <= pcm16Mono.size) {
                    for (i in 0 until OpenWakeWordEngine.CHUNK_SAMPLES) {
                        chunk[i] = pcm16Mono[offset + i].toFloat() * gain
                    }
                    val result = verifier.processChunk(chunk)
                    if (result.score > peak) peak = result.score
                    if (result.detected && result.modelId == wakeWordId) {
                        fired = true
                        break
                    }
                    offset += OpenWakeWordEngine.CHUNK_SAMPLES
                }
            } finally {
                if (raised) verifier.updateThreshold(wakeWordId, streamingCutoff)
            }
            Log.i(
                TAG,
                "offline verify id=$wakeWordId fired=$fired peak=${"%.3f".format(peak)} " +
                    "cutoff=${"%.2f".format(verifyCutoff)} extra=$extraLevel " +
                    "gain=${"%.2f".format(gain)} samples=${pcm16Mono.size} " +
                    "${(System.nanoTime() - startedNs) / 1_000_000}ms",
            )
            return fired
        }
    }

    @Synchronized
    override fun reset() {
        chunkFill = 0
        engine?.reset()
        vadFrontend?.reset()
        vad?.reset()
        vadBuffer.clear()
        vadSpeechSeen = false
        vadStrictSpeechSeen = false
        totalSamplesProcessed = 0
        gateSilenceSamples = 0
        strictGateSilenceSamples = 0
        closeComputeGateAfterDetect = false
    }

    /** Create (or return) the persistent burst-scoring engine. Caller holds [offlineVerifyLock]. */
    private fun ensureOfflineVerifyEngineLocked(): OpenWakeWordEngine? {
        offlineVerifyEngine?.let { return it }
        val configs = offlineVerifyConfigs
        if (configs.isEmpty()) return null
        val startedNs = System.nanoTime()
        val verifier = runCatching {
            OpenWakeWordEngine.create(provider.loadEmbedding(), configs)
        }.onFailure { t ->
            Log.w(TAG, "offline verify engine create failed", t)
        }.getOrNull() ?: return null
        for ((id, threshold) in offlineVerifyThresholds) {
            verifier.updateThreshold(id, threshold)
        }
        offlineVerifyEngine = verifier
        offlineVerifyActiveId = null
        Log.i(
            TAG,
            "offline verify engine created in " +
                "${(System.nanoTime() - startedNs) / 1_000_000} ms",
        )
        return verifier
    }

    /**
     * Build the offline verify engine ahead of the first wake. Extra-strictness level 1+
     * re-scores every wake through it, and the lazy first build costs ~1 s that would
     * otherwise land inside a live wake's confirmation window. Not synchronized on
     * `this` for the same reason as [scoreOfflinePcm].
     */
    fun prewarmOfflineVerify() {
        synchronized(offlineVerifyLock) { ensureOfflineVerifyEngineLocked() }
    }

    @Synchronized
    override fun updateExtraStrictness(wakeWordId: String, level: Int) {
        val previous = extraStrictness[wakeWordId] ?: 0
        if (level > 0) extraStrictness[wakeWordId] = level else extraStrictness.remove(wakeWordId)
        // The consecutive-hits gate and the verifier veto threshold are baked into the
        // native keyword config, so crossing the level-2 boundary on a loaded
        // non-verifier model, or any level change on a model with a verifier head,
        // needs an engine rebuild. Each keyword's VAD rule is also baked into native config.
        val hitsGateChanged = (previous >= 2) != (level >= 2) &&
            loadedKeywords.any { it.id == wakeWordId && !it.hasBuiltInVerifier && !it.hasVerifierHead }
        val verifierThresholdChanged = previous != level &&
            loadedKeywords.any { it.id == wakeWordId && it.hasVerifierHead }
        val vadRuleChanged = loadedKeywords.any {
            it.id == wakeWordId &&
                OpenWakeWordCutoffPolicy.strictVadFor(
                    previous,
                    it.hasVerifierHead,
                    it.hasBuiltInVerifier,
                ) !=
                OpenWakeWordCutoffPolicy.strictVadFor(
                    level,
                    it.hasVerifierHead,
                    it.hasBuiltInVerifier,
                )
        }
        if ((hitsGateChanged || verifierThresholdChanged || vadRuleChanged) && engine != null) {
            Log.i(TAG, "id=$wakeWordId extra strictness $previous -> $level — reloading engine")
            setActiveWakeWords(activeKeywordIds.toList())
        }
    }

    /**
     * On-device learning wrote a new `<id>_verifier.bin`. The head is baked into the
     * native keyword config, so the engine (and the offline verify twin, which shares
     * the configs) is rebuilt with the same active set.
     */
    @Synchronized
    override fun reloadVerifier(wakeWordId: String) {
        if (engine == null || wakeWordId !in activeKeywordIds) return
        Log.i(TAG, "id=$wakeWordId verifier head changed — reloading engine")
        setActiveWakeWords(activeKeywordIds.toList())
    }

    @Synchronized
    override fun updateProbabilityCutoff(wakeWordId: String, cutoff: Float) {
        // Keep the raw setting: if models are not loaded yet the manifest is unknown,
        // and resolving now against the 0.5 default must not overwrite the stored value.
        pendingCutoffs[wakeWordId] = cutoff
        val loaded = loadedKeywords.find { it.id == wakeWordId }
        val manifest = loaded?.manifestThreshold ?: OpenWakeWordCutoffPolicy.DEFAULT_THRESHOLD
        val resolved = OpenWakeWordCutoffPolicy.resolveRequestedCutoff(
            manifestCutoff = manifest,
            requestedCutoff = cutoff,
            hasBuiltInVerifier = loaded?.hasBuiltInVerifier == true,
        )
        if (kotlin.math.abs(resolved - cutoff) > 0.0005f && cutoff > 0f) {
            Log.i(
                TAG,
                "id=$wakeWordId stored cutoff ${"%.2f".format(cutoff)} " +
                    "outside open working range — using ${"%.2f".format(resolved)}",
            )
        }
        engine?.updateThreshold(wakeWordId, resolved)
        synchronized(offlineVerifyLock) {
            offlineVerifyThresholds[wakeWordId] = resolved
            offlineVerifyEngine?.updateThreshold(wakeWordId, resolved)
        }
    }

    @Synchronized
    fun hasBuiltInVerifier(wakeWordId: String): Boolean =
        loadedKeywords.any { it.id == wakeWordId && it.hasBuiltInVerifier }

    @Synchronized
    fun getProbabilityCutoff(wakeWordId: String): Float? {
        val loaded = loadedKeywords.find { it.id == wakeWordId }
        val manifest = loaded?.manifestThreshold
        val pending = pendingCutoffs[wakeWordId]
        if (manifest == null && pending == null) return null
        return OpenWakeWordCutoffPolicy.resolveRequestedCutoff(
            manifestCutoff = manifest ?: OpenWakeWordCutoffPolicy.DEFAULT_THRESHOLD,
            requestedCutoff = pending ?: -1f,
            hasBuiltInVerifier = loaded?.hasBuiltInVerifier == true,
        )
    }

    @Synchronized
    override fun close() {
        engine?.close()
        engine = null
        synchronized(offlineVerifyLock) {
            offlineVerifyEngine?.close()
            offlineVerifyEngine = null
            offlineVerifyConfigs = emptyArray()
            offlineVerifyActiveId = null
            offlineVerifyThresholds.clear()
        }
        chunkFill = 0
        chunkBudget.reset()
        computeGateOpen = true
        closeComputeGateAfterDetect = false
        gateSilenceSamples = 0
        vadSpeechSeen = false
        strictGateSilenceSamples = 0
        vadStrictSpeechSeen = false
        loadedKeywords.clear()
        vad?.close()
        vad = null
        vadFrontend?.close()
        vadFrontend = null
        vadBuffer.clear()
    }

    companion object {
        private const val TAG = "OpenWakeWordDetector"
        private const val VAD_SAMPLES_PER_CHUNK = 160
        private const val VAD_BYTES_PER_SAMPLE = 2
        private const val VAD_BYTES_PER_CHUNK = VAD_SAMPLES_PER_CHUNK * VAD_BYTES_PER_SAMPLE

        private const val GATE_CLOSE_MIN_MS = 150
        private const val GATE_CLOSE_MAX_MS = 250
        private const val SAMPLES_PER_MS = 16L

        /** 480 ms covers measured word-end emission (<=213 ms) without a long false-wake tail. */
        private const val TRIGGER_VAD_HANGOVER_SAMPLES = 7_680L

        /**
         * Extra-strictness (level 2) hangover: 320 ms still clears the measured <=213 ms
         * word-end emission with margin while cutting the false-wake tail further.
         */
        private const val TRIGGER_VAD_HANGOVER_STRICT_SAMPLES = 5_120L

        /**
         * Minimum audio samples before the VAD gate is trusted (500 ms at 16 kHz).
         * Prevents cold-start artifacts from immediately opening the gate on the
         * first frame after a pipeline restart.
         */
        private const val VAD_WARMUP_SAMPLES = 8_000L
    }
}
