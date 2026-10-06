package com.example.ava.stopword

import android.util.Log
import com.example.microfeatures.StopWordEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Barge-in "stop" detector backed by the model-free native DSP engine.
 *
 * Reads the buffer through a duplicate — the caller's position is never
 * advanced, so it is safe to run before or after other detectors sharing
 * the same detection frame.
 */
class StopWordDetector : AutoCloseable {

    private val engine: StopWordEngine? = try {
        StopWordEngine()
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Native stop word engine unavailable", e)
        null
    }
    private var scratch = ShortArray(0)
    private var refScratch = ShortArray(0)

    /** The phrase reported with [com.example.ava.esphome.voicesatellite.VoiceSatelliteAudioInput.AudioResult.StopDetected]. */
    val phrase: String get() = STOP_PHRASE

    /**
     * @param playbackRef clean far-end reference PCM sample-parallel to [audio]
     *   (same AEC frames), or null when nothing plays. With it the DSP subtracts
     *   the predicted machine contribution per band, so a barge-in "stop" stays
     *   detectable during TTS/media playback and the playback itself can never
     *   walk the template.
     */
    @Synchronized
    fun detect(audio: ByteBuffer, playbackRef: ByteBuffer? = null): Boolean {
        val dsp = engine ?: return false
        val input = audio.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = input.remaining() / 2
        if (sampleCount == 0) return false
        if (scratch.size < sampleCount) scratch = ShortArray(sampleCount)
        input.asShortBuffer().get(scratch, 0, sampleCount)
        if (playbackRef != null) {
            val ref = playbackRef.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val refCount = (ref.remaining() / 2).coerceAtMost(sampleCount)
            if (refCount > 0) {
                if (refScratch.size < refCount) refScratch = ShortArray(refCount)
                ref.asShortBuffer().get(refScratch, 0, refCount)
                return dsp.processWithRef(scratch, sampleCount, refScratch, refCount)
            }
        }
        return dsp.process(scratch, sampleCount)
    }

    /**
     * Same as [detect] with the reference already unpacked to 16-bit samples —
     * the hardware-AEC capture path reads the far-end ring directly instead of
     * receiving AEC-frame-aligned buffers from a software canceller.
     */
    @Synchronized
    fun detect(audio: ByteBuffer, refPcm: ShortArray, refCount: Int): Boolean {
        val dsp = engine ?: return false
        val input = audio.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = input.remaining() / 2
        if (sampleCount == 0) return false
        if (scratch.size < sampleCount) scratch = ShortArray(sampleCount)
        input.asShortBuffer().get(scratch, 0, sampleCount)
        val n = refCount.coerceIn(0, minOf(refPcm.size, sampleCount))
        if (n == 0) return dsp.process(scratch, sampleCount)
        return dsp.processWithRef(scratch, sampleCount, refPcm, n)
    }

    fun lastConfidence(): Float = engine?.lastConfidence() ?: 0f

    /** Clear in-flight utterance state when detection is (re)enabled. */
    @Synchronized
    fun arm() {
        engine?.arm()
    }

    /** Full reset after a capture-path change (profile/source swap). */
    @Synchronized
    fun reset() {
        engine?.reset()
    }

    @Synchronized
    override fun close() {
        engine?.close()
    }

    companion object {
        private const val TAG = "StopWordDetector"
        const val STOP_PHRASE = "stop"
    }
}
