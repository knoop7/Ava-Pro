package com.example.ava.microwakeword

import android.util.Log
import java.nio.ByteBuffer

/**
 * A no-op wake word detector that never detects anything.
 * Used as a fallback when the real wake word engine fails to initialize
 * (e.g., TensorFlow Lite not supported on Android 5-6, or ONNX runtime unavailable).
 */
class NoOpWakeWordDetector(
    private val reason: String
) : WakeWordEngineDetector {
    
    init {
        Log.w(TAG, "NoOpWakeWordDetector created: $reason")
    }

    override fun detect(audio: ByteBuffer): List<WakeWordEngineDetector.DetectionResult> {
        return emptyList()
    }

    override fun setActiveWakeWords(wakeWordIds: List<String>) {
        if (wakeWordIds.isNotEmpty()) {
            Log.w(TAG, "Cannot set wake words, detector disabled: $reason")
        }
    }

    override fun reset() {
        // No-op
    }

    override fun updateProbabilityCutoff(wakeWordId: String, cutoff: Float) {
        // No-op
    }

    override fun close() {
        // No-op
    }

    companion object {
        private const val TAG = "NoOpWakeWordDetector"
    }
}
