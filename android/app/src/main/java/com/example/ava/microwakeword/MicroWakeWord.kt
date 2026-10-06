package com.example.ava.microwakeword

import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import kotlin.math.abs

private const val SAMPLES_PER_SECOND = 16000
private const val SAMPLES_PER_CHUNK = 160
private const val FEATURE_FRAME_MS = SAMPLES_PER_CHUNK * 1000 / SAMPLES_PER_SECOND
private const val REARM_LOW_SCORE_MS = 1000
private const val MIN_PROBABILITY_CUTOFF = 0.5f
private const val MAX_PROBABILITY_CUTOFF = 0.99f
/** Band below the cutoff where a rejected wake is worth a log line (#187). */
private const val NEAR_MISS_BAND = 0.15f
private const val NEAR_MISS_LOG_INTERVAL_MS = 2000L

/**
 * Bundled ESPHome models consume 3 frontend frames per infer ([1, 3, 40]).
 * Some trainers (Tater mixednet `--stride 2`) export [1, 2, 40]. Read the graph
 * instead of assuming 3 — a hard-coded stride throws and aborts every model
 * sharing the detector loop.
 *
 * Feed-shape compatibility only. Does not skip WakeSampleVerifier, loosen
 * cutoffs, or fail-open a streaming spike.
 */
internal object MicroFeatureStride {
    const val DEFAULT = 3

    fun resolve(featureFrameSize: Int, tensorFlatSize: Int): Int? {
        if (featureFrameSize <= 0 || tensorFlatSize <= 0) return null
        if (tensorFlatSize % featureFrameSize != 0) return null
        val stride = tensorFlatSize / featureFrameSize
        return stride.takeIf { it > 0 }
    }

    fun rearmWindows(stride: Int): Int {
        val modelOutputMs = FEATURE_FRAME_MS * stride.coerceAtLeast(1)
        return (REARM_LOW_SCORE_MS + modelOutputMs - 1) / modelOutputMs
    }
}

object WakeWordCutoffPolicy {
    private const val LEGACY_JARVIS_CUTOFF = 0.85f
    private const val CURRENT_JARVIS_CUTOFF = 0.97f
    private const val FLOAT_TOLERANCE = 0.0005f
    /** Slider may sit this far below the model's recommendation (far-field rooms). */
    private const val SLIDER_BELOW_MANIFEST = 0.25f
    /** Offline re-score slack below the strictness in force. See [resolveVerifyCutoff]. */
    private const val VERIFY_SLACK = 0.10f
    /** User explicitly selected precision well above the model recommendation. */
    private const val PRECISION_VERIFY_SLACK = 0.02f
    private const val PRECISION_CUTOFF_FLOOR = 0.90f
    private const val PRECISION_ABOVE_MANIFEST = 0.05f
    /**
     * Absolute floor for the re-score. Well under [MIN_PROBABILITY_CUTOFF] because this is a
     * derived value, not a user-facing strictness — but far enough above the ~0.01 a clip
     * without the phrase scores that the gate still rejects everything it is meant to.
     */
    internal const val MIN_VERIFY_CUTOFF = 0.3f
    /**
     * During far-end playback, strong streaming scores may skip offline verify (music/TTS
     * still muddy the clip). Weak scores must not ride this bypass — they fall through to
     * offline re-score on the matching pre-AEC ring.
     */
    private const val FAR_END_SKIP_FLOOR = 0.92f

    fun sanitizeManifestCutoff(value: Float): Float =
        value.coerceIn(MIN_PROBABILITY_CUTOFF, MAX_PROBABILITY_CUTOFF)

    /** Bounds the strictness slider offers for this model — also the accept range below. */
    fun sliderRange(manifestCutoff: Float): ClosedFloatingPointRange<Float> {
        val baseline = sanitizeManifestCutoff(manifestCutoff)
        val floor = (baseline - SLIDER_BELOW_MANIFEST).coerceAtLeast(MIN_PROBABILITY_CUTOFF)
        return floor..MAX_PROBABILITY_CUTOFF
    }

    fun resolveRequestedCutoff(
        modelId: String,
        manifestCutoff: Float,
        requestedCutoff: Float,
    ): Float {
        val baseline = sanitizeManifestCutoff(manifestCutoff)
        // The sensitivity fields are shared with openWakeWord, whose working range
        // (0.15..~0.80) sits mostly below this model's slider floor. A stored value
        // the micro UI could never produce is the other engine's setting — treat it
        // as unset instead of running e.g. hey_jarvis (0.97) at 0.65.
        val range = sliderRange(baseline)
        if (requestedCutoff < range.start || requestedCutoff > range.endInclusive) {
            return baseline
        }
        val requested = requestedCutoff
        val isLegacyBundledJarvisDefault = modelId == "hey_jarvis" &&
            abs(baseline - CURRENT_JARVIS_CUTOFF) <= FLOAT_TOLERANCE &&
            abs(requested - LEGACY_JARVIS_CUTOFF) <= FLOAT_TOLERANCE
        return if (isLegacyBundledJarvisDefault) baseline else requested
    }

    /**
     * Offline confirmation cutoff: the strictness in force, minus cold-start slack.
     *
     * The re-score is structurally weaker than streaming, so holding it to the same bar (let
     * alone a stricter one) rejects real wakes. It starts from a cold frontend, has no rearm
     * or window state carried in, and works on a fixed clip whose edges the sliding window
     * can only partly fill. On the same utterance it lands ~0.85..0.99 where streaming saw
     * 0.996. A model shipping a 0.99 cutoff — Tater exports do — leaves no headroom for a
     * margin to even exist, so verify used to sit exactly at streaming and drop roughly a
     * third of genuine wakes.
     *
     * Default/recommended settings retain the proven 0.10 allowance. When the user explicitly
     * raises strictness well above the model recommendation, use only 0.02: deployment evidence
     * showed saturated false wakes re-scoring around 0.87 after streaming at 0.95–0.96, while
     * the strict-mode positive matrix still cleared the tighter re-score.
     *
     * The manifest is deliberately not a second floor here. An untouched strictness already
     * arrives as the manifest value (the detector starts there), so taking the stricter of the
     * two made every setting below the manifest inert — on hey_jarvis the slider spans
     * 0.72..0.99 while nothing under 0.97 could ever confirm a wake (#187). Manifest is used
     * only when no streaming cutoff is known.
     */
    fun resolveVerifyCutoff(
        streamingCutoff: Float,
        manifestCutoff: Float,
        forcePrecision: Boolean = false,
    ): Float {
        val baseline = effectiveCutoff(streamingCutoff, manifestCutoff)
        val slack = if (forcePrecision || isPrecisionOverride(streamingCutoff, manifestCutoff)) {
            PRECISION_VERIFY_SLACK
        } else {
            VERIFY_SLACK
        }
        return (baseline - slack).coerceIn(MIN_VERIFY_CUTOFF, baseline)
    }

    /**
     * Offline fires required to confirm a wake.
     *
     * Always 1: ESPHome/microWakeWord already confirm via sliding_window_size (mean over
     * several inferences). Their ignore/rearm window exists to *debounce* duplicate
     * triggers after a hit — not as a second accept gate. Requiring two offline fires
     * rejected real hey_jarvis wakes that peaked once (#166).
     */
    @Suppress("UNUSED_PARAMETER")
    fun minOfflineDetections(
        streamingCutoff: Float,
        manifestCutoff: Float = streamingCutoff,
    ): Int = 1

    /**
     * Floor for fail-open / far-end bypass: the strictness in force. Flooring this on the
     * manifest as well kept the setting inert on these paths too (#187); the far-end bypass
     * keeps its own absolute floor in [allowFarEndVerifySkip].
     */
    fun unverifiedWakeFloor(
        manifestCutoff: Float,
        streamingCutoff: Float,
    ): Float = effectiveCutoff(streamingCutoff, manifestCutoff)

    /** Strictness in force: the streaming cutoff, falling back to the manifest when unset. */
    private fun effectiveCutoff(streamingCutoff: Float, manifestCutoff: Float): Float =
        (if (streamingCutoff > 0f) streamingCutoff else manifestCutoff)
            .coerceIn(MIN_PROBABILITY_CUTOFF, MAX_PROBABILITY_CUTOFF)

    /**
     * Missing ring / extract / load errors: normal settings fail-open only after streaming
     * clears the active cutoff. Precision overrides fail closed because bypassing independent
     * evidence would make the user's stricter selection ineffective. [forcePrecision]
     * (the extra-strictness slider zone) has the same fail-closed semantics — it exists
     * for models whose manifest sits too high for the value-based override to ever engage.
     */
    fun allowUnverifiedStreamingWake(
        streamingConfidence: Float,
        manifestCutoff: Float,
        streamingCutoff: Float,
        forcePrecision: Boolean = false,
    ): Boolean {
        if (forcePrecision || isPrecisionOverride(streamingCutoff, manifestCutoff)) return false
        return streamingConfidence >= unverifiedWakeFloor(manifestCutoff, streamingCutoff)
    }

    fun allowFarEndVerifySkip(
        streamingConfidence: Float,
        manifestCutoff: Float,
        streamingCutoff: Float,
        forcePrecision: Boolean = false,
    ): Boolean {
        // A user-selected precision override must remain meaningful during TTS/music.
        // The cancelled ring follows the detection source, so verify instead of bypassing.
        if (forcePrecision || isPrecisionOverride(streamingCutoff, manifestCutoff)) return false
        val floor = maxOf(
            unverifiedWakeFloor(manifestCutoff, streamingCutoff),
            FAR_END_SKIP_FLOOR,
        )
        return streamingConfidence >= floor
    }

    private fun isPrecisionOverride(streamingCutoff: Float, manifestCutoff: Float): Boolean {
        if (streamingCutoff <= 0f) return false
        val manifest = sanitizeManifestCutoff(manifestCutoff)
        return streamingCutoff + FLOAT_TOLERANCE >= PRECISION_CUTOFF_FLOOR &&
            streamingCutoff + FLOAT_TOLERANCE >= manifest + PRECISION_ABOVE_MANIFEST
    }
}

class MicroWakeWord(
    val id: String,
    val wakeWord: String,
    private val model: ByteBuffer,
    probabilityCutoff: Float,
    private val slidingWindowSize: Int,
    startArmed: Boolean = false,
) : AutoCloseable {
    private val recommendedProbabilityCutoff =
        WakeWordCutoffPolicy.sanitizeManifestCutoff(probabilityCutoff)

    @Volatile
    var probabilityCutoff: Float = recommendedProbabilityCutoff
        private set
    
    fun setProbabilityCutoff(value: Float) {
        val resolved = WakeWordCutoffPolicy.resolveRequestedCutoff(
            modelId = id,
            manifestCutoff = recommendedProbabilityCutoff,
            requestedCutoff = value,
        )
        if (abs(resolved - value) > 0.0005f) {
            Log.i(
                TAG,
                "id=$id stored cutoff ${"%.3f".format(value)} is a legacy default or " +
                    "another engine's setting — using ${"%.3f".format(resolved)}",
            )
        }
        probabilityCutoff = resolved
    }
    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private var inputTensorBuffer: TensorBuffer? = null
    private var outputTensorBuffer: ProbabilityTensorBuffer? = null
    private val probabilities = ArrayDeque<Float>(slidingWindowSize)
    private val rearmGate = LowScoreRearmGate(
        MicroFeatureStride.rearmWindows(MicroFeatureStride.DEFAULT),
        startArmed,
    )
    private var featureStride: Int = MicroFeatureStride.DEFAULT
    private var lastNearMissLogMs = 0L

    private var initializationFailed = false
    private var initializationError: String? = null

    private fun initializeIfNeeded(): Boolean {
        if (initializationFailed) return false
        if (isInitialized) return true
        
        return try {
            val tempModel = model.duplicate()
            tempModel.rewind()
            interpreter = Interpreter(tempModel)

            interpreter?.allocateTensors()
            val inputDetails = interpreter!!.getInputTensor(0)
            val inputQuantParams = inputDetails.quantizationParams()
            inputTensorBuffer = TensorBuffer.create(
                inputDetails.dataType(),
                inputDetails.shape(),
                inputQuantParams.scale,
                inputQuantParams.zeroPoint
            )

            val outputDetails = interpreter!!.getOutputTensor(0)
            val outputQuantParams = outputDetails.quantizationParams()
            require(outputDetails.numElements() == 1) { "Wake model must output one probability" }
            outputTensorBuffer = ProbabilityTensorBuffer(
                outputDetails.dataType(), outputQuantParams.scale, outputQuantParams.zeroPoint,
            )

            isInitialized = true
            true
        } catch (e: UnsatisfiedLinkError) {
            initializationFailed = true
            initializationError = "TensorFlow Lite native library not available (Android 5-6 may not be supported)"
            Log.e(TAG, initializationError, e)
            false
        } catch (e: Exception) {
            initializationFailed = true
            initializationError = "Failed to initialize TensorFlow Lite interpreter: ${e.message}"
            Log.e(TAG, initializationError, e)
            false
        }
    }

    @Volatile
    var lastDetectionProbability: Float = 0f
        private set

    /** Most recent per-frame probability (updated every infer, not only on wake). */
    @Volatile
    var lastFrameProbability: Float = 0f
        private set

    /** Current sliding-window mean (0 when the window is not yet full). */
    @Synchronized
    fun currentWindowAverage(): Float {
        if (probabilities.isEmpty()) return 0f
        return probabilities.average().toFloat()
    }

    @Synchronized
    fun processAudioFeatures(
        features: FloatArray,
        confirmCandidate: (Float) -> Boolean = { true },
    ): Boolean {
        if (features.isEmpty())
            return false

        if (!initializeIfNeeded()) {
            return false
        }

        val tensorBuffer = inputTensorBuffer ?: return false
        val stride = MicroFeatureStride.resolve(features.size, tensorBuffer.flatSize)
        if (stride == null) {
            initializationFailed = true
            initializationError =
                "Unexpected feature size ${features.size} for tensor size ${tensorBuffer.flatSize}"
            Log.e(TAG, "id=$id $initializationError")
            return false
        }
        if (featureStride != stride) {
            featureStride = stride
            rearmGate.retargetRequiredWindows(MicroFeatureStride.rearmWindows(stride))
            if (stride != MicroFeatureStride.DEFAULT) {
                Log.i(TAG, "id=$id streaming stride=$stride (tensor=${tensorBuffer.flatSize})")
            }
        }

        tensorBuffer.put(features)
        if (!tensorBuffer.isComplete)
            return false

        val probability = getWakeWordProbability(tensorBuffer.getTensor())
        tensorBuffer.clear()
        return isWakeWordDetected(probability, confirmCandidate)
    }

    private fun getWakeWordProbability(input: ByteBuffer): Float {
        val output = outputTensorBuffer ?: return 0f
        output.buffer.clear()
        interpreter?.run(input, output.buffer) ?: return 0f
        return output.probability()
    }

    internal fun isWakeWordDetected(
        probability: Float,
        confirmCandidate: (Float) -> Boolean = { true },
    ): Boolean {
        if (!probability.isFinite()) {
            probabilities.clear()
            lastFrameProbability = 0f
            return false
        }
        lastFrameProbability = probability
        if (!rearmGate.allowScoring(probability, probabilityCutoff)) {
            probabilities.clear()
            return false
        }
        
        if (probabilities.size == slidingWindowSize)
            probabilities.removeFirst()
        probabilities.add(probability)
        
        if (probabilities.size < slidingWindowSize) return false
        val average = probabilities.average().toFloat()
        if (average <= probabilityCutoff) {
            logNearMiss(average)
            return false
        }
        // Keep the rolling window on rejection so the next inference can use
        // the latest full evidence. Only a confirmed wake consumes the window.
        if (!confirmCandidate(average)) return false
        probabilities.clear()
        lastDetectionProbability = average
        rearmGate.disarm()
        return true
    }

    /**
     * A wake rejected just under the cutoff otherwise leaves no trace at all, so it reads
     * exactly like a dead microphone in logcat (#187). Throttled — this runs per frame.
     */
    private fun logNearMiss(average: Float) {
        if (average < probabilityCutoff - NEAR_MISS_BAND) return
        val now = System.currentTimeMillis()
        if (now - lastNearMissLogMs < NEAR_MISS_LOG_INTERVAL_MS) return
        lastNearMissLogMs = now
        Log.d(
            TAG,
            "id=$id wake rejected avg=${"%.3f".format(average)} " +
                "cutoff=${"%.3f".format(probabilityCutoff)}",
        )
    }

    @Synchronized
    fun reset() {
        // Match ESPHome reset semantics: clear probability state while retaining the streaming
        // model variables, then require about one second of below-cutoff output before scoring.
        probabilities.clear()
        rearmGate.disarm()
        lastDetectionProbability = 0f
        lastFrameProbability = 0f
        inputTensorBuffer?.clear()
    }

    @Synchronized
    override fun close() {
        interpreter?.close()
    }

    companion object {
        private const val TAG = "MicroWakeWord"

        internal fun forVerification(
            id: String,
            wakeWord: String,
            model: ByteBuffer,
            manifestCutoff: Float,
            verifyCutoff: Float,
            slidingWindowSize: Int,
        ): MicroWakeWord {
            require(verifyCutoff.isFinite() &&
                verifyCutoff in WakeWordCutoffPolicy.MIN_VERIFY_CUTOFF..MAX_PROBABILITY_CUTOFF)
            return MicroWakeWord(id, wakeWord, model, manifestCutoff, slidingWindowSize, startArmed = true)
                .apply { probabilityCutoff = verifyCutoff }
        }
    }
}

internal class LowScoreRearmGate(
    requiredLowScoreWindows: Int,
    startArmed: Boolean = false,
) {
    private var requiredLowScoreWindows = requiredLowScoreWindows.coerceAtLeast(0)
    private var remaining = if (startArmed) 0 else this.requiredLowScoreWindows

    fun allowScoring(probability: Float, cutoff: Float): Boolean {
        if (remaining == 0) return true
        remaining = if (probability < cutoff) {
            (remaining - 1).coerceAtLeast(0)
        } else {
            remaining
        }
        return false
    }

    fun arm() {
        remaining = 0
    }

    fun disarm() {
        remaining = requiredLowScoreWindows
    }

    /**
     * Keep the ~1s low-score debounce when the graph's frame stride differs from
     * the bundled default. Only retargets an in-progress wait; an already-armed
     * gate stays armed.
     */
    fun retargetRequiredWindows(windows: Int) {
        val newRequired = windows.coerceAtLeast(0)
        if (newRequired == requiredLowScoreWindows) return
        if (remaining > 0) {
            remaining = newRequired
        }
        requiredLowScoreWindows = newRequired
    }
}
