package com.example.microfeatures

/**
 * Model-free "stop" word detector (native DSP, see StopWordDsp.cpp).
 *
 * Matches the phonetic time structure of an isolated spoken "stop" on
 * 16 kHz mono PCM16 — no TFLite/ONNX model involved.
 */
class StopWordEngine : AutoCloseable {

    private var nativeHandle: Long = nativeCreate()

    val isReady: Boolean get() = nativeHandle != 0L

    private external fun nativeCreate(): Long
    private external fun nativeProcess(handle: Long, pcm: ShortArray, count: Int): Boolean
    private external fun nativeProcessWithRef(
        handle: Long,
        pcm: ShortArray,
        count: Int,
        ref: ShortArray?,
        refCount: Int,
    ): Boolean
    private external fun nativeLastConfidence(handle: Long): Float
    private external fun nativeArm(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)

    /** Feed PCM16 mono 16 kHz samples; true when a completed "stop" was recognized. */
    fun process(pcm: ShortArray, count: Int = pcm.size): Boolean {
        if (nativeHandle == 0L || count <= 0) return false
        return nativeProcess(nativeHandle, pcm, count.coerceAtMost(pcm.size))
    }

    /**
     * Same, with the clean playback reference sample-parallel to the mic tap
     * (ref[i] played when pcm[i] was captured). The detector learns per-band
     * echo-path gains from it and classifies on the energy above the predicted
     * machine contribution, so "stop" stays detectable while TTS/media plays
     * and the machine can never stop itself. Null/short ref = silence.
     */
    fun processWithRef(pcm: ShortArray, count: Int, ref: ShortArray?, refCount: Int): Boolean {
        if (nativeHandle == 0L || count <= 0) return false
        return nativeProcessWithRef(
            nativeHandle,
            pcm,
            count.coerceAtMost(pcm.size),
            ref,
            if (ref != null) refCount.coerceAtMost(ref.size) else 0,
        )
    }

    fun lastConfidence(): Float {
        if (nativeHandle == 0L) return 0f
        return nativeLastConfidence(nativeHandle)
    }

    /** Clear in-flight utterance state, keep the learned noise floor. */
    fun arm() {
        if (nativeHandle != 0L) nativeArm(nativeHandle)
    }

    /** Full reset including the adaptive noise floor (capture path changed). */
    fun reset() {
        if (nativeHandle != 0L) nativeReset(nativeHandle)
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }

    companion object {
        init {
            System.loadLibrary("microfeatures")
        }
    }
}
