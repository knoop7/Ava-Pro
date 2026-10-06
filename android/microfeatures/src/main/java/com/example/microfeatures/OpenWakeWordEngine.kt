package com.example.microfeatures

data class OpenKeywordNativeConfig(
    val id: String,
    val displayName: String,
    val onnxBytes: ByteArray,
    val threshold: Float,
    val requiredHits: Int,
    val cooldownMs: Int,
    val stopClassifier: Boolean,
    // Two-observation high-confidence shortcut on the 40 ms grid. Kept as a knob for
    // host A/B runs (probe OWW_NO_FAST_LANE); production always leaves it on.
    val allowFastLane: Boolean = true,
    // Tempo-remapped and silence-tail-patched windows (one-breath "wake + command"
    // recall). On unless a manifest explicitly confines the model to the main path.
    val allowAlternatePaths: Boolean = true,
    // Built-in verifier heads get a candidate-time sustained-hum guard. Generic
    // community classifiers stay untouched until separately calibrated.
    val hasBuiltInVerifier: Boolean = false,
    // 0 = unset, so the native engine applies its own averaging default. Callers that
    // know the model's window (the detector reads it off the manifest) pass it through.
    val slidingWindowSize: Int = 0,
    // Optional glue-rescue head: tiny sidecar classifier over the same embedding
    // window that spots the wake word anywhere in the window, rescuing wake words
    // spoken in one breath with the command. Null = none.
    val rescueBytes: ByteArray? = null,
    // Optional near-word verifier head over the same window — a logistic layer
    // sigmoid(w·x + b) with FIRE_WINDOW_SIZE weights — consulted only when the base
    // classifier would fire. Below verifierThreshold the fire is vetoed without
    // charging cooldown. Null weights or threshold 0 = no veto.
    val verifierWeights: FloatArray? = null,
    val verifierBias: Float = 0f,
    val verifierThreshold: Float = 0f,
    val requiresStrictVad: Boolean = false,
)

data class OpenWakeWordDetectionResult(
    val detected: Boolean,
    val score: Float,
    val modelId: String?,
    val wakeWordPhrase: String?,
    /** Keyword whose fire the verifier head vetoed in this chunk, or null. */
    val vetoedModelId: String? = null,
)

class OpenWakeWordEngine private constructor(private var handle: Long) : AutoCloseable {
    fun reset() {
        if (handle != 0L) nativeReset(handle)
    }

    /**
     * Hard reset for offline burst re-scoring: clears mel/embedding context, the
     * engine clock, and per-keyword cooldown, then warm-starts on silence (a few
     * ms). The soft [reset] keeps context and cooldown, which on a persistent
     * verify engine lets the previous clip's residue score as a fake hit and
     * lets an engine-time cooldown swallow real ones.
     */
    fun resetForVerify() {
        if (handle != 0L) nativeResetForVerify(handle)
    }

    /**
     * Compute gate driven by an external VAD. While false, chunks only run the
     * mel frontend (microseconds) into a short native backlog; the embedding and
     * classifiers — ~96% of chunk compute — are skipped. Switching back to true
     * keeps the hot graph and feeds every parked row without a receptive-field refill.
     */
    fun setVoiceGate(speechPresent: Boolean) {
        if (handle != 0L) nativeSetVoiceGate(handle, speechPresent)
    }

    fun setTriggerGates(normal: Boolean, strict: Boolean) {
        if (handle != 0L) nativeSetTriggerGates(handle, normal, strict)
    }

    fun setActiveKeywords(ids: Array<String>) {
        if (handle != 0L) nativeSetActiveKeywords(handle, ids)
    }

    fun updateThreshold(id: String, cutoff: Float) {
        if (handle != 0L) nativeUpdateThreshold(handle, id, cutoff)
    }

    fun processChunk(samples: FloatArray): OpenWakeWordDetectionResult {
        if (handle == 0L) return OpenWakeWordDetectionResult(false, 0f, null, null, null)
        return nativeProcessChunk(handle, samples)
    }

    /** Last classifier score for one keyword (diagnostics / Voice Stats). */
    fun keywordScore(id: String): Float {
        if (handle == 0L) return 0f
        return nativeKeywordScore(handle, id)
    }

    /** Effective trigger threshold for one keyword (includes runtime updates). */
    fun keywordThreshold(id: String, fallback: Float = 0.5f): Float {
        if (handle == 0L) return fallback
        return nativeKeywordThreshold(handle, id, fallback)
    }

    /**
     * The exact 16x96 classifier input behind the most recent accepted fire (a copy,
     * [FIRE_WINDOW_SIZE] floats), or null before any fire. Read right after a chunk
     * reports `detected`; on-device learning labels it from the session outcome.
     */
    fun lastFireWindow(): FloatArray? {
        if (handle == 0L) return null
        return nativeLastFireWindow(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeResetForVerify(handle: Long)
    private external fun nativeSetVoiceGate(handle: Long, speechPresent: Boolean)
    private external fun nativeSetTriggerGates(handle: Long, normal: Boolean, strict: Boolean)
    private external fun nativeSetActiveKeywords(handle: Long, ids: Array<String>)
    private external fun nativeUpdateThreshold(handle: Long, id: String, cutoff: Float)
    private external fun nativeKeywordScore(handle: Long, id: String): Float
    private external fun nativeKeywordThreshold(handle: Long, id: String, fallback: Float): Float
    private external fun nativeProcessChunk(handle: Long, samples: FloatArray): OpenWakeWordDetectionResult
    private external fun nativeLastFireWindow(handle: Long): FloatArray?

    companion object {
        init {
            System.loadLibrary("microfeatures")
            initJni(OpenWakeWordDetectionResult::class.java)
        }

        @JvmStatic
        private external fun initJni(resultClass: Class<OpenWakeWordDetectionResult>)

        fun create(
            embeddingBytes: ByteArray,
            keywords: Array<OpenKeywordNativeConfig>,
        ): OpenWakeWordEngine? {
            val handle = nativeCreate(embeddingBytes, keywords)
            return if (handle == 0L) null else OpenWakeWordEngine(handle)
        }

        @JvmStatic
        private external fun nativeCreate(
            embeddingBytes: ByteArray,
            keywords: Array<OpenKeywordNativeConfig>,
        ): Long

        /** 40 ms at 16 kHz; native A/B embedding phases emit alternately. */
        const val CHUNK_SAMPLES = 640

        /** Classifier / verifier input: 16 embeddings x 96 dims. */
        const val FIRE_WINDOW_SIZE = 16 * 96
    }
}
