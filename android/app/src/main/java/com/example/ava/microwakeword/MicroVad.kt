package com.example.ava.microwakeword

import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

class MicroVad(
    private val model: ByteBuffer,
    val probabilityCutoff: Float,
    private val slidingWindowSize: Int,
) : AutoCloseable {
    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private var initializationFailed = false
    private var inputTensorBuffer: TensorBuffer? = null
    internal val isOperational: Boolean get() = isInitialized && !initializationFailed
    private var outputTensorBuffer: ProbabilityTensorBuffer? = null
    private val probabilities = ArrayDeque<Float>(slidingWindowSize)

    @Volatile
    var lastProbability: Float = 0f
        private set

    @Volatile
    var voiceDetected: Boolean = false
        private set

    /** Strict-mode decision: window average AND two frames must both clear the cutoff. */
    @Volatile
    var strictVoiceDetected: Boolean = false
        private set

    fun allowsWake(): Boolean = initializationFailed || voiceDetected

    /**
     * Extra-strictness gate (level 2): both rules of [MicroVadDecision] must hold, not
     * either. Still fails open on init failure — a broken VAD model must not silently
     * kill the wake word on the device; the classifier and offline re-score remain.
     */
    fun allowsWakeStrict(): Boolean = initializationFailed || strictVoiceDetected

    @Synchronized
    fun currentWindowMax(): Float = probabilities.maxOrNull() ?: 0f

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
            require(outputDetails.numElements() == 1) { "VAD model must output one probability" }
            outputTensorBuffer = ProbabilityTensorBuffer(
                outputDetails.dataType(), outputQuantParams.scale, outputQuantParams.zeroPoint,
            )

            isInitialized = true
            true
        } catch (e: UnsatisfiedLinkError) {
            initializationFailed = true
            Log.e(TAG, "TensorFlow Lite native library not available for VAD", e)
            false
        } catch (e: Exception) {
            initializationFailed = true
            Log.e(TAG, "Failed to initialize VAD interpreter: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun processAudioFeatures(features: FloatArray) {
        if (features.isEmpty()) return
        if (!initializeIfNeeded()) return

        val tensorBuffer = inputTensorBuffer ?: return
        if (MicroFeatureStride.resolve(features.size, tensorBuffer.flatSize) == null) {
            initializationFailed = true
            Log.e(
                TAG,
                "Unexpected VAD feature size ${features.size} " +
                    "for tensor size ${tensorBuffer.flatSize}",
            )
            return
        }

        tensorBuffer.put(features)
        if (!tensorBuffer.isComplete) return

        val probability = getVoiceProbability(tensorBuffer.getTensor())
        tensorBuffer.clear()
        lastProbability = probability

        if (probabilities.size == slidingWindowSize)
            probabilities.removeFirst()
        probabilities.add(probability)

        voiceDetected = MicroVadDecision.allowsWake(
            probabilities = probabilities,
            windowSize = slidingWindowSize,
            cutoff = probabilityCutoff,
        )
        strictVoiceDetected = MicroVadDecision.allowsWakeStrict(
            probabilities = probabilities,
            windowSize = slidingWindowSize,
            cutoff = probabilityCutoff,
        )
    }

    private fun getVoiceProbability(input: ByteBuffer): Float {
        val output = outputTensorBuffer ?: return 0f
        output.buffer.clear()
        interpreter?.run(input, output.buffer) ?: return 0f
        return output.probability()
    }

    @Synchronized
    fun reset() {
        probabilities.clear()
        voiceDetected = false
        strictVoiceDetected = false
        lastProbability = 0f
        inputTensorBuffer?.clear()
    }

    @Synchronized
    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        private const val TAG = "MicroVad"
    }
}

/** ESPHome-style window average with a two-frame fallback for short/fast speech. */
internal object MicroVadDecision {
    fun allowsWake(
        probabilities: Collection<Float>,
        windowSize: Int,
        cutoff: Float,
    ): Boolean {
        if (windowSize <= 0 || probabilities.isEmpty()) return false
        // Missing startup entries count as zero, matching a fixed zero-initialized window.
        val average = probabilities.sum() / windowSize
        if (average > cutoff) return true
        return probabilities.count { it > cutoff } >= 2
    }

    /**
     * Extra-strictness (level 2) rule: the normal decision's OR becomes an AND —
     * the window average must clear the cutoff AND at least two frames must clear it,
     * so neither a single hot frame nor a diffuse just-over-average run opens the gate.
     */
    fun allowsWakeStrict(
        probabilities: Collection<Float>,
        windowSize: Int,
        cutoff: Float,
    ): Boolean {
        if (windowSize <= 0 || probabilities.isEmpty()) return false
        val average = probabilities.sum() / windowSize
        return average > cutoff && probabilities.count { it > cutoff } >= 2
    }
}

/**
 * VAD availability policy shared by both wake engines. The VAD is a suppressor layered
 * on the classifier: when it is absent or failed to initialize it cannot veto, at any
 * strictness. Level 2 only changes which decision rule applies while the VAD runs.
 */
internal object WakeVadPolicy {
    fun allowsWake(
        strict: Boolean,
        vadAvailable: Boolean,
        normalDecision: Boolean,
        strictDecision: Boolean,
    ): Boolean = !vadAvailable || (if (strict) strictDecision else normalDecision)
}
