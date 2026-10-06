package com.example.ava.wakelearn

import android.util.Log
import com.example.ava.settings.WakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device wake personalization: every accepted wake parks its classifier window; what
 * the user does next labels it.
 *
 *   STT returns text                      -> genuine wake (positive)
 *   "stop" said while still listening    -> unwanted session (negative feedback)
 *   empty STT / stt-no-text AND the       -> nobody was talking to the device (negative)
 *   device heard no speech either
 *   empty STT / stt-no-text but speech    -> audio may have been lost upstream (unknown)
 *   was observed locally or by HA's VAD
 *   anything else (network error, run     -> unknown, discarded
 *   torn down before STT, a new wake)
 *
 * Labelled windows accumulate in [WakeLearnStore]; every few new samples the head is
 * refit ([OnDeviceVerifierTrainer]) with the factory head as prior, and if it passes
 * cross-validation it is written as `<id>_verifier.bin` and the engine reloads it. The
 * household's actual false wakes therefore become the verifier's negatives — the one
 * source of hard negatives no offline corpus can provide.
 */
class WakeLearner(
    private val store: WakeLearnStore,
    private val scope: CoroutineScope,
    /** Factory / imported head for a wake word (never the learned one), or null. */
    private val priorHead: (WakeWordEngine, String) -> LinearVerifierHead?,
    /** Veto threshold the engine will apply at default strictness. */
    private val vetoThreshold: (WakeWordEngine, String) -> Float,
    /** Called off the audio thread after a new head has been written. */
    private val onHeadUpdated: (WakeWordEngine, String) -> Unit,
    /** Every training attempt's cross-validation outcome, published or refused. */
    private val onReport: (WakeWordEngine, String, WakeLearnTuning.TrainReport) -> Unit = { _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Pending(val engine: WakeWordEngine, val id: String, val window: FloatArray, val atMs: Long) {
        /** Someone audibly spoke during this session (pre-roll, local energy, HA VAD). */
        var speechEvidence = false
    }

    private val lock = Any()
    private var pending: Pending? = null
    private val unlabelledSinceTrain = HashMap<String, Int>()
    private val trainMutex = Mutex()

    fun onWakeAccepted(engine: WakeWordEngine, wakeWordId: String, window: FloatArray?) {
        if (!WakeLearnTuning.enabled || window == null || wakeWordId.isBlank()) return
        synchronized(lock) {
            pending = Pending(engine, wakeWordId, window, clock())
        }
    }

    /**
     * Speech was observed during the pending session. Blank STT after this is treated
     * as lost audio rather than as proof of a false wake.
     */
    fun onSpeechEvidence() {
        synchronized(lock) { pending?.speechEvidence = true }
    }

    /** STT finished. Blank text is a negative only when nobody was heard speaking. */
    fun onSttText(text: String?) = verdict(text, emptyReason = "stt_empty")

    /** Pipeline error: only the explicit no-speech code can be evidence, same rule as blank STT. */
    fun onPipelineError(code: String?) {
        if (code != null && code.startsWith("stt-no-text")) {
            verdict(null, emptyReason = code)
        } else {
            discard("error:$code")
        }
    }

    private fun verdict(text: String?, emptyReason: String) {
        val evidence = synchronized(lock) { pending?.speechEvidence ?: return }
        when (WakeLearningLabel.fromTranscript(text, evidence)) {
            WakeLearningLabel.POSITIVE -> label(WakeLearningLabel.POSITIVE, reason = "stt_text")
            WakeLearningLabel.NEGATIVE -> label(WakeLearningLabel.NEGATIVE, reason = emptyReason)
            WakeLearningLabel.UNKNOWN -> discard("$emptyReason:speech_heard")
        }
    }

    /** "Stop" while the device is still listening and nothing has been transcribed. */
    fun onStopWordWhileListening() {
        label(WakeLearningLabel.NEGATIVE, reason = "stop_word")
    }

    /** Session over without a verdict (torn down early, new wake, manual cancel). */
    fun onSessionDiscarded(reason: String) {
        discard(reason)
    }

    private fun discard(reason: String) {
        synchronized(lock) {
            if (pending != null) {
                Log.d(TAG, "pending wake sample discarded ($reason)")
                pending = null
            }
        }
    }

    private fun label(value: WakeLearningLabel, reason: String) {
        require(value != WakeLearningLabel.UNKNOWN)
        val positive = value == WakeLearningLabel.POSITIVE
        val p = synchronized(lock) {
            val cur = pending ?: return
            pending = null
            cur
        }
        if (clock() - p.atMs > PENDING_TTL_MS) {
            Log.d(TAG, "pending wake sample expired before verdict ($reason)")
            return
        }
        scope.launch(Dispatchers.IO) {
            store.append(p.engine, p.id, WakeLearnStore.Sample(positive, p.atMs, p.window))
            val key = "${p.engine}/${p.id}"
            val n = synchronized(lock) {
                val v = (unlabelledSinceTrain[key] ?: 0) + 1
                unlabelledSinceTrain[key] = v
                v
            }
            Log.i(
                TAG,
                "wake sample labelled ${if (positive) "POSITIVE" else "NEGATIVE"} ($reason) " +
                    "engine=${p.engine} id=${p.id} dims=${p.window.size} newSinceTrain=$n",
            )
            val every = WakeLearnTuning.retrainEvery
            if (n >= every || (!positive && n >= minOf(every, RETRAIN_EVERY_ON_NEGATIVE))) {
                retrain(p.engine, p.id)
            }
        }
    }

    /** Refit now from everything stored (settings page). True when a head was published. */
    suspend fun retrainNow(engine: WakeWordEngine, wakeWordId: String): Boolean = retrain(engine, wakeWordId)

    private suspend fun retrain(engine: WakeWordEngine, id: String): Boolean {
        trainMutex.withLock {
            val samples = store.load(engine, id)
            val dims = samples.lastOrNull()?.x?.size ?: return false
            val prior = priorHead(engine, id)
            val threshold = vetoThreshold(engine, id)
            val started = System.nanoTime()
            val result = OnDeviceVerifierTrainer.train(
                samples, prior, dims, threshold,
                minPositiveRetention = WakeLearnTuning.minPositiveRetention,
            )
            val ms = (System.nanoTime() - started) / 1_000_000
            val report = result?.report ?: OnDeviceVerifierTrainer.crossValidate(samples, prior, dims, threshold)
            report?.let {
                onReport(
                    engine, id,
                    WakeLearnTuning.TrainReport(
                        timestampMs = clock(), published = result != null,
                        positives = it.positives, negatives = it.negatives,
                        cvPositivePass = it.heldOutPositivePass, cvNegativeVeto = it.heldOutNegativeVeto,
                        priorNegativeVeto = it.priorHeldOutNegativeVeto,
                    ),
                )
            }
            if (result == null) {
                Log.i(
                    TAG,
                    "retrain engine=$engine id=$id: no publishable head yet " +
                        "(${samples.size} samples ${ms}ms) $report",
                )
                return false
            }
            store.writeHead(engine, id, result.head)
            synchronized(lock) { unlabelledSinceTrain["$engine/$id"] = 0 }
            Log.i(
                TAG,
                "retrain engine=$engine id=$id published new verifier head: ${result.report} " +
                    "(${samples.size} samples ${ms}ms)",
            )
            onHeadUpdated(engine, id)
            return true
        }
    }

    companion object {
        private const val TAG = "WakeLearner"
        private const val PENDING_TTL_MS = 45_000L
        /** A false wake is worth refitting for sooner than the regular cadence. */
        private const val RETRAIN_EVERY_ON_NEGATIVE = 2
    }
}
