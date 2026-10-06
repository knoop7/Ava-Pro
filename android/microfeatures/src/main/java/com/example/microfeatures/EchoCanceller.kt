package com.example.microfeatures

/**
 * Software acoustic echo canceller (SpeexDSP MDF + residual echo suppression).
 *
 * Feed near-end mic frames together with time-aligned far-end playback reference
 * frames. Both must be 16-bit mono PCM at [sampleRate], exactly [frameSize] samples
 * per call.
 */
class EchoCanceller(
    val frameSize: Int = DEFAULT_FRAME_SIZE,
    filterLengthMs: Int = DEFAULT_FILTER_LENGTH_MS,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    suppressDb: Int = DEFAULT_SUPPRESS_DB,
    suppressActiveDb: Int = DEFAULT_SUPPRESS_ACTIVE_DB,
) : AutoCloseable {

    private var nativeHandle: Long = 0

    val isInitialized get() = nativeHandle != 0L

    /** Effective filter tail after rounding to whole frames. */
    val filterLengthMs: Int

    @Volatile
    var suppressDb: Int = suppressDb
        private set

    @Volatile
    var suppressActiveDb: Int = suppressActiveDb
        private set

    private external fun nativeCreate(
        frameSize: Int,
        filterLength: Int,
        sampleRate: Int,
        suppressDb: Int,
        suppressActiveDb: Int,
    ): Long

    private external fun nativeSetSuppress(handle: Long, suppressDb: Int, suppressActiveDb: Int)
    private external fun nativeProcess(handle: Long, mic: ShortArray, ref: ShortArray, out: ShortArray)
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)

    init {
        val filterLength = (filterLengthMs * sampleRate / 1000 / frameSize)
            .coerceAtLeast(1) * frameSize
        this.filterLengthMs = filterLength * 1000 / sampleRate
        nativeHandle = nativeCreate(
            frameSize,
            filterLength,
            sampleRate,
            suppressDb,
            suppressActiveDb,
        )
    }

    /**
     * Cancels echo from [mic] given playback reference [ref]; writes result to [out].
     * All arrays must be exactly [frameSize] samples.
     *
     * After Speex residual NLP: capped near-end makeup (gated by warm-up + ERLE;
     * never resurrects NLP-killed frames). Strength tiers only change suppress dB.
     */
    fun process(mic: ShortArray, ref: ShortArray, out: ShortArray) {
        if (!isInitialized) {
            mic.copyInto(out)
            return
        }
        require(mic.size == frameSize && ref.size == frameSize && out.size == frameSize) {
            "mic/ref/out must be $frameSize samples"
        }
        nativeProcess(nativeHandle, mic, ref, out)
    }

    /** Hot-update Speex residual NLP suppress levels (no filter rebuild). */
    fun setEchoSuppress(suppressDb: Int, suppressActiveDb: Int) {
        if (!isInitialized) return
        this.suppressDb = suppressDb
        this.suppressActiveDb = suppressActiveDb
        nativeSetSuppress(nativeHandle, suppressDb, suppressActiveDb)
    }

    fun reset() {
        if (isInitialized) nativeReset(nativeHandle)
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }

    companion object {
        /** 20ms at 16kHz. */
        const val DEFAULT_FRAME_SIZE = 320
        /** Adaptive filter tail; matches SoftwareAecRoom.LIVING (adaptive / default home). */
        const val DEFAULT_FILTER_LENGTH_MS = 240
        const val DEFAULT_SAMPLE_RATE = 16000
        /** Historical Speex residual suppress defaults used by Ava. */
        const val DEFAULT_SUPPRESS_DB = -45
        const val DEFAULT_SUPPRESS_ACTIVE_DB = -25

        init {
            System.loadLibrary("microfeatures")
        }
    }
}
