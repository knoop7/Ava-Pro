package com.example.ava.microwakeword

import android.content.res.AssetManager
import android.util.Log
import com.example.microfeatures.MicroFrontend
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Cold, finite-clip VAD used off the audio thread, only to rescue quiet pre-roll. */
internal object WakePreRollSpeechDetector {
    class Session(model: ByteBuffer, cutoff: Float, window: Int) : AutoCloseable {
        private val frontend = MicroFrontend()
        private val vad = MicroVad(model, cutoff, window)
        private val chunk = ByteBuffer.allocateDirect(320).order(ByteOrder.LITTLE_ENDIAN)
        private var processedSamples = 0
        private var speechStart: Int? = null

        /**
         * Feed more 16 kHz mono PCM16. Returns the byte offset (into everything pushed so
         * far) from which speech should be replayed, or null while no speech is found.
         */
        fun push(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): Int? {
            speechStart?.let { return it }
            var cursor = offset
            val end = offset + length
            while (cursor < end) {
                val count = minOf(chunk.remaining(), end - cursor)
                chunk.put(pcm, cursor, count)
                cursor += count
                if (chunk.hasRemaining()) continue
                chunk.flip()
                val output = frontend.processSamples(chunk)
                chunk.clear()
                processedSamples += 160
                if (output.features.isEmpty()) continue
                vad.processAudioFeatures(output.features)
                check(vad.isOperational) { "Pre-roll VAD could not initialize" }
                // Do not use allowsWake(): it intentionally fails open on model
                // initialization failure, which is not evidence for replaying audio.
                if (vad.voiceDetected) {
                    // Preserve phonemes before the streaming VAD's decision.
                    speechStart = (processedSamples - 9_600).coerceAtLeast(0) * 2
                    return speechStart
                }
            }
            return null
        }

        override fun close() {
            try { vad.close() } finally { frontend.close() }
        }
    }

    /**
     * Loads the bundled VAD once per process. The model bytes are shared read-only;
     * every [MicroVad] duplicates the buffer before rewinding, so concurrent sessions
     * are safe.
     */
    class Loader(private val assets: AssetManager, private val path: String = "vad") {
        private class Model(val bytes: ByteBuffer, val cutoff: Float, val window: Int)

        private val model: Model? by lazy {
            runCatching {
                val provider = AssetWakeWordProvider(assets, path)
                val meta = provider.getWakeWords().firstOrNull()?.wakeWord ?: return@runCatching null
                Model(
                    provider.loadWakeWordModel(meta.model),
                    meta.micro.probability_cutoff,
                    meta.micro.sliding_window_size,
                )
            }.onFailure { Log.w(TAG, "pre-roll VAD model unavailable", it) }.getOrNull()
        }

        /** Null when the asset is missing or unreadable; callers keep the legacy onset. */
        fun newSession(): Session? = model?.let { Session(it.bytes, it.cutoff, it.window) }
    }

    private const val TAG = "WakePreRollSpeechDetector"
}
