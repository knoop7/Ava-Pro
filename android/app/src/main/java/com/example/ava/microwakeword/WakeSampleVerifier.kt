package com.example.ava.microwakeword

import android.util.Log
import com.example.microfeatures.MicroFrontend
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Offline wake confirmation on a ring-buffer PCM clip (same capture pattern as voiceprint).
 *
 * Streaming micro only sees a short sliding average; this re-scores the contiguous wake
 * utterance so chance spikes are rejected. Matches ESPHome/microWakeWord accept semantics:
 * one sliding-window mean above the cutoff from
 * [WakeWordCutoffPolicy.resolveVerifyCutoff], which sits *below* streaming to pay for this
 * pass starting cold. Rearm remains a streaming debounce, not a second offline accept
 * gate (#166).
 */
object WakeSampleVerifier {
    private const val TAG = "WakeSampleVerifier"
    private const val SAMPLES_PER_CHUNK = 160
    /** Let noise-reduction settle on the clip before arming the sliding window. */
    private const val ARM_FEATURE_FRAMES = 35

    data class Result(
        val confirmed: Boolean,
        val peakAverage: Float,
        val detections: Int,
        val elapsedMs: Long,
        val verifyCutoff: Float = 0f,
    )

    fun verify(
        pcm16Mono: ShortArray,
        model: ByteBuffer,
        wakeWordId: String,
        wakeWordPhrase: String,
        probabilityCutoff: Float,
        slidingWindowSize: Int,
        manifestCutoff: Float = probabilityCutoff,
        forcePrecision: Boolean = false,
    ): Result {
        val started = System.nanoTime()
        if (pcm16Mono.size < SAMPLES_PER_CHUNK * 8) {
            return Result(false, 0f, 0, elapsedMs(started))
        }

        val verifyCutoff = WakeWordCutoffPolicy.resolveVerifyCutoff(
            streamingCutoff = probabilityCutoff,
            manifestCutoff = manifestCutoff,
            forcePrecision = forcePrecision,
        )
        if (MicroNonVerbalVoiceGuard.isSustainedHum(pcm16Mono)) {
            return Result(false, 0f, 0, elapsedMs(started), verifyCutoff)
        }

        var frontend: MicroFrontend? = null
        var wakeWord: MicroWakeWord? = null
        return try {
            frontend = MicroFrontend()
            wakeWord = MicroWakeWord.forVerification(
                id = wakeWordId,
                wakeWord = wakeWordPhrase,
                model = model,
                manifestCutoff = manifestCutoff,
                verifyCutoff = verifyCutoff,
                slidingWindowSize = slidingWindowSize,
            )

            val chunk = ByteBuffer.allocateDirect(SAMPLES_PER_CHUNK * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            var peakAverage = 0f
            var detections = 0
            var offset = 0
            var featureFrames = 0

            while (offset + SAMPLES_PER_CHUNK <= pcm16Mono.size) {
                chunk.clear()
                for (i in 0 until SAMPLES_PER_CHUNK) {
                    chunk.putShort(pcm16Mono[offset + i])
                }
                chunk.flip()
                offset += SAMPLES_PER_CHUNK

                val output = frontend.processSamples(chunk)
                if (output.features.isEmpty()) continue

                // Warm both the frontend and the streaming network. Dropping
                // these frames removes the word onset on a short ring history.
                val canConfirm = featureFrames++ >= ARM_FEATURE_FRAMES
                val fired = wakeWord.processAudioFeatures(output.features) { canConfirm }
                if (!canConfirm) continue
                peakAverage = max(peakAverage, wakeWord.currentWindowAverage())
                if (fired) {
                    detections++
                    peakAverage = max(peakAverage, wakeWord.lastDetectionProbability)
                }
            }

            // Contiguous clip must still clear the model once — streaming spike alone is not enough.
            val minDetections = WakeWordCutoffPolicy.minOfflineDetections(
                streamingCutoff = probabilityCutoff,
                manifestCutoff = manifestCutoff,
            )
            val confirmed = detections >= minDetections
            val elapsed = elapsedMs(started)
            Log.i(
                TAG,
                "sample verify id=$wakeWordId confirmed=$confirmed detections=$detections " +
                    "minDetections=$minDetections " +
                    "peak=${"%.3f".format(peakAverage)} cutoff=${"%.3f".format(verifyCutoff)} " +
                    "streamCutoff=${"%.3f".format(probabilityCutoff)} " +
                    "manifest=${"%.3f".format(manifestCutoff)} " +
                    "samples=${pcm16Mono.size} ${elapsed}ms",
            )
            Result(confirmed, peakAverage, detections, elapsed, verifyCutoff)
        } catch (e: Exception) {
            Log.w(TAG, "sample verify failed for $wakeWordId", e)
            Result(false, 0f, 0, elapsedMs(started))
        } finally {
            wakeWord?.close()
            frontend?.close()
        }
    }

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L
}
