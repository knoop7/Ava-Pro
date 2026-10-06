package com.example.ava.microwakeword

import android.util.Log
import com.example.ava.openwakeword.OpenWakeWordCutoffPolicy
import com.example.ava.settings.WakeWordEngine
import com.example.ava.utils.fillFrom
import com.example.ava.wakelearn.LinearVerifierHead
import com.example.ava.wakelearn.MicroVerifierWindow
import com.example.ava.wakelearn.WakeLearnStore
import com.example.ava.wakelearn.WakeLearnTuning
import com.example.microfeatures.MicroFrontend
import java.nio.ByteBuffer

private const val SAMPLES_PER_CHUNK = 160
private const val BYTES_PER_SAMPLE = 2
private const val BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * BYTES_PER_SAMPLE

class WakeWordDetector(
    private val wakeWordProvider: WakeWordProvider,
    private val vadProvider: WakeWordProvider? = null,
    /** On-device learned verifier heads (micro has no factory heads). */
    private val learnStore: WakeLearnStore? = null,
) : WakeWordEngineDetector {
    private var frontend: MicroFrontend? = null
    private var frontendInitFailed = false
    private val buffer = ByteBuffer.allocateDirect(BYTES_PER_CHUNK)
    private val wakeWords by lazy { wakeWordProvider.getWakeWords() }
    private var activeWakeWords = listOf<MicroWakeWord>()
    private var vad: MicroVad? = null
    private var vadInitFailed = false
    /** Extra-strictness levels per wake word id (level 2 switches to the strict VAD rule). */
    private val extraStrictness = mutableMapOf<String, Int>()
    // Trailing feature history every model fire is judged and remembered by. Fed the
    // same frames the models get, so the verifier and the learner see exactly the
    // audio evidence that produced the fire.
    private val verifierWindow = MicroVerifierWindow()
    private val verifierHeads = mutableMapOf<String, LinearVerifierHead>()
    // A vetoed utterance keeps re-firing the model for a few more frames; count it once.
    private val lastVetoAtMs = mutableMapOf<String, Long>()

    private fun ensureVad(): MicroVad? {
        val provider = vadProvider ?: return null
        if (vadInitFailed) return null
        vad?.let { return it }
        return try {
            val vadMeta = provider.getWakeWords().firstOrNull { it.wakeWord.type == "micro" }
            if (vadMeta == null) {
                vadInitFailed = true
                Log.w(TAG, "No micro VAD manifest found, voice gate disabled")
                null
            } else {
                MicroVad(
                    model = provider.loadWakeWordModel(vadMeta.wakeWord.model),
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

    private fun ensureFrontend(): MicroFrontend? {
        if (frontendInitFailed) return null
        if (frontend != null) return frontend
        return try {
            MicroFrontend().also { frontend = it }
        } catch (e: UnsatisfiedLinkError) {
            frontendInitFailed = true
            Log.e(TAG, "MicroFrontend native library not available", e)
            null
        } catch (e: Exception) {
            frontendInitFailed = true
            Log.e(TAG, "Failed to initialize MicroFrontend", e)
            null
        }
    }

    @Synchronized
    override fun detect(audio: ByteBuffer): List<WakeWordEngineDetector.DetectionResult> {
        val fe = ensureFrontend() ?: return emptyList()
        val voiceGate = ensureVad()
        val detections = mutableListOf<WakeWordEngineDetector.DetectionResult>()
        try {
            buffer.fillFrom(audio)
            while (buffer.flip().remaining() == BYTES_PER_CHUNK) {
                val processOutput = fe.processSamples(buffer)
                buffer.position(buffer.position() + processOutput.samplesRead * BYTES_PER_SAMPLE)
                buffer.compact()
                buffer.fillFrom(audio)
                if (processOutput.features.isEmpty())
                    continue
                // Always feed every feature frame — micro_wake_word models expect a
                // contiguous spectrogram. Per-chunk RMS gating fragments the stream and
                // breaks detection (especially quiet / far-field speech).
                voiceGate?.processAudioFeatures(processOutput.features)
                verifierWindow.push(processOutput.features)
                for (wakeWord in activeWakeWords) {
                    val result = try {
                        wakeWord.processAudioFeatures(processOutput.features) { confidence ->
                            confirmCandidate(wakeWord, confidence, voiceGate)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "wake '${wakeWord.wakeWord}' infer failed", e)
                        false
                    }
                    if (!result) continue
                    val window = verifierWindow.snapshot()
                    if (!detections.any { it.wakeWordId == wakeWord.id })
                        detections.add(
                            WakeWordEngineDetector.DetectionResult(
                                wakeWordId = wakeWord.id,
                                wakeWordPhrase = wakeWord.wakeWord,
                                confidence = wakeWord.lastDetectionProbability.coerceIn(0f, 1f),
                                verifierWindow = window,
                            ),
                        )
                }
            }
            buffer.compact()
        } catch (e: Exception) {
            Log.e(TAG, "Error during wake word detection", e)
            buffer.clear()
        }
        return detections
    }

    private fun confirmCandidate(wakeWord: MicroWakeWord, confidence: Float, voiceGate: MicroVad?): Boolean {
        val level = extraStrictness[wakeWord.id] ?: 0
        val head = verifierHeads[wakeWord.id]
        val strictVad = OpenWakeWordCutoffPolicy.strictVadFor(
            extraLevel = level,
            hasVerifierHead = head != null,
        )
        if (!WakeVadPolicy.allowsWake(
                strict = strictVad,
                vadAvailable = voiceGate != null,
                normalDecision = voiceGate?.allowsWake() == true,
                strictDecision = voiceGate?.allowsWakeStrict() == true,
            )
        ) {
            Log.w(TAG, "wake '${wakeWord.wakeWord}' conf=${"%.3f".format(confidence)} " +
                "suppressed: VAD saw no speech" + (if (strictVad) " [strict]" else ""))
            return false
        }
        if (head == null) return true
        val window = verifierWindow.snapshot() ?: return false
        if (window.size != head.dims) return false
        val score = head.score(window)
        val threshold = WakeLearnTuning.effectiveVetoThreshold(
            OpenWakeWordCutoffPolicy.verifierThreshold(level),
        )
        if (score.isFinite() && score >= threshold) return true
        val now = System.currentTimeMillis()
        val last = lastVetoAtMs[wakeWord.id] ?: 0L
        if (now - last >= VETO_DEBOUNCE_MS) {
            Log.w(TAG, "wake '${wakeWord.wakeWord}' conf=${"%.3f".format(confidence)} " +
                "vetoed by verifier ${"%.3f".format(score)} < ${"%.2f".format(threshold)}")
            WakeLearnTuning.recordVeto(WakeWordEngine.MICRO_WAKE_WORD, wakeWord.id)
        }
        lastVetoAtMs[wakeWord.id] = now
        return false
    }

    @Synchronized
    override fun setActiveWakeWords(wakeWordIds: List<String>) {
        for (wakeWord in activeWakeWords)
            wakeWord.close()
        activeWakeWords = buildList {
            for (wakeWordId in wakeWordIds) {
                val wakeWordWithId = wakeWords.firstOrNull { it.id == wakeWordId }
                if (wakeWordWithId == null) {
                    Log.w(TAG, "Wake word with id $wakeWordId not found")
                    continue
                }
                try {
                    add(
                        MicroWakeWord(
                            wakeWordWithId.id,
                            wakeWordWithId.wakeWord.wake_word,
                            wakeWordProvider.loadWakeWordModel(wakeWordWithId.wakeWord.model),
                            wakeWordWithId.wakeWord.micro.probability_cutoff,
                            wakeWordWithId.wakeWord.micro.sliding_window_size,
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load wake word model for $wakeWordId", e)
                }
            }
        }
        verifierHeads.clear()
        for (id in wakeWordIds) loadVerifierHead(id)
    }

    private fun loadVerifierHead(wakeWordId: String) {
        val head = learnStore?.readHead(WakeWordEngine.MICRO_WAKE_WORD, wakeWordId)
        if (head == null) {
            verifierHeads.remove(wakeWordId)
            return
        }
        verifierHeads[wakeWordId] = head
        Log.i(TAG, "id=$wakeWordId learned verifier head loaded (${head.dims} dims)")
    }

    @Synchronized
    override fun reloadVerifier(wakeWordId: String) {
        loadVerifierHead(wakeWordId)
    }

    @Synchronized
    override fun reset() {
        // Drop frontend and probability-window residue left by TTS or the previous session.
        // Each model then requires fresh below-cutoff output before it can score another wake.
        frontend?.reset()
        buffer.clear()
        vad?.reset()
        verifierWindow.reset()
        for (wakeWord in activeWakeWords) {
            wakeWord.reset()
        }
    }

    @Synchronized
    override fun updateProbabilityCutoff(wakeWordId: String, cutoff: Float) {
        activeWakeWords.find { it.id == wakeWordId }?.setProbabilityCutoff(cutoff)
    }

    @Synchronized
    override fun updateExtraStrictness(wakeWordId: String, level: Int) {
        if (level > 0) extraStrictness[wakeWordId] = level else extraStrictness.remove(wakeWordId)
    }

    @Synchronized
    fun getProbabilityCutoff(wakeWordId: String): Float? {
        return activeWakeWords.find { it.id == wakeWordId }?.probabilityCutoff
    }

    /** Live sliding-window probs + cutoffs for Voice Stats (microWakeWord only). */
    @Synchronized
    fun liveProbe(): List<WakeWordLiveProbe> = buildList {
        for (model in activeWakeWords) {
            add(
                WakeWordLiveProbe(
                    id = model.id,
                    phrase = model.wakeWord,
                    windowAvg = model.currentWindowAverage(),
                    lastProb = model.lastFrameProbability,
                    cutoff = model.probabilityCutoff,
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

    @Synchronized
    override fun close() {
        frontend?.close()
        frontend = null
        vad?.close()
        vad = null
        for (model in activeWakeWords)
            model.close()
    }

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val VETO_DEBOUNCE_MS = 1500L
    }
}
