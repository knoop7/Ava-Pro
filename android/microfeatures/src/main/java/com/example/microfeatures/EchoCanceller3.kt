package com.example.microfeatures

/**
 * WebRTC AEC3 acoustic echo canceller (mono).
 *
 * AEC3 estimates and tracks the render→capture delay internally (including clock
 * drift), so the playback reference must be fed *undelayed* — unlike the Speex
 * path, which needs a pre-aligned reference.
 *
 * The two user-facing choices map onto AEC3 as follows:
 *  - [filterLengthMs] (room type) sets the adaptive filter length.
 *  - [suppressDb] (cancellation strength) sets how much echo path loss AEC3
 *    assumes before its filter converges, how transparent the suppressor is
 *    during double-talk, and how much of the linear-filter output is blended
 *    back in to keep near-end speech audible when the suppressor closes.
 *
 * Feed 16-bit mono PCM at [sampleRate]; [process] accepts any whole multiple of
 * the 10 ms native block ([blockSize] samples).
 */
class EchoCanceller3(
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val filterLengthMs: Int = EchoCanceller.DEFAULT_FILTER_LENGTH_MS,
    val suppressDb: Int = EchoCanceller.DEFAULT_SUPPRESS_DB,
) : AutoCloseable {

    private var nativeHandle: Long = 0

    val isInitialized get() = nativeHandle != 0L

    /** AEC3 native cadence: 10 ms. */
    val blockSize: Int = sampleRate / 100

    private external fun nativeCreate(sampleRate: Int, filterLengthMs: Int, suppressDb: Int): Long
    private external fun nativeProcess(
        handle: Long,
        mic: ShortArray,
        ref: ShortArray,
        out: ShortArray,
        detectOut: ShortArray?,
        levelChange: Boolean,
    )
    private external fun nativeReset(handle: Long)
    private external fun nativeMetrics(handle: Long, out: FloatArray)
    private external fun nativeDestroy(handle: Long)

    init {
        nativeHandle = nativeCreate(sampleRate, filterLengthMs, suppressDb)
    }

    /**
     * Cancels echo from [mic] given playback reference [ref]; writes result to [out].
     * All arrays must be the same length, a whole multiple of [blockSize].
     *
     * Set [levelChange] = true right after the speaker output level changed outside
     * the reference path (device volume keys, MA volume, mute): AEC3 then re-adapts
     * its filter immediately instead of leaking echo while it slowly re-converges.
     *
     * [detectOut], when non-null, receives the wake-detection tap: the same AEC
     * pass remixed with a much higher linear-filter share, so barge-in speech
     * survives loud playback. Carries more residual music than [out]; feed it to
     * the wake word engine only, never uplink.
     */
    fun process(
        mic: ShortArray,
        ref: ShortArray,
        out: ShortArray,
        levelChange: Boolean = false,
        detectOut: ShortArray? = null,
    ) {
        if (!isInitialized) {
            mic.copyInto(out)
            detectOut?.let { mic.copyInto(it) }
            return
        }
        require(mic.size == out.size && ref.size == out.size && out.size % blockSize == 0) {
            "mic/ref/out must be equal whole-block lengths (block=$blockSize)"
        }
        require(detectOut == null || detectOut.size == out.size) {
            "detectOut must match out length"
        }
        nativeProcess(nativeHandle, mic, ref, out, detectOut, levelChange)
    }

    /**
     * Live metrics: `[echoReturnLossDb, erleDb, delayMs]`.
     * Values are AEC3's own estimates; delay is the tracked render→capture offset.
     */
    fun metrics(out: FloatArray = FloatArray(3)): FloatArray {
        if (isInitialized && out.size >= 3) nativeMetrics(nativeHandle, out)
        return out
    }

    /** Drops the adapted filter and delay estimate (fresh convergence). */
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
        const val DEFAULT_SAMPLE_RATE = 16000

        init {
            System.loadLibrary("microfeatures")
        }
    }
}
