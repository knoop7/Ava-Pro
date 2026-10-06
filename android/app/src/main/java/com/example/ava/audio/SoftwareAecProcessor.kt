package com.example.ava.audio

import android.util.Log
import com.example.microfeatures.EchoCanceller
import com.example.microfeatures.EchoCanceller3
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming wrapper around the software echo canceller for the wake word capture loop.
 *
 * Engine: WebRTC AEC3 ([EchoCanceller3]) when it initializes; SpeexDSP [EchoCanceller] is
 * kept as a fallback if the AEC3 native path fails to construct. Both engines are driven by
 * the same two user settings (room type → [filterLengthMs], strength → [suppressDb]), each
 * interpreting them in its own terms.
 *
 * The engines need opposite reference alignment: Speex has no delay estimator and needs a
 * pre-aligned reference, AEC3 aligns internally and needs only a small scheduling cushion.
 * Whichever engine
 * wins sets [PlaybackReferenceBus.bulkDelayMs] accordingly.
 *
 * Accepts arbitrary-length 16kHz mono 16-bit PCM buffers, slices them into AEC frames,
 * pulls time-aligned playback reference frames from [PlaybackReferenceBus], and returns
 * the echo-cancelled audio. Sub-frame remainders are carried over to the next call, so the
 * returned buffer holds whole frames and can be shorter (or empty) than the input.
 */
class SoftwareAecProcessor(
    filterLengthMs: Int = EchoCanceller.DEFAULT_FILTER_LENGTH_MS,
    suppressDb: Int = EchoCanceller.DEFAULT_SUPPRESS_DB,
    suppressActiveDb: Int = EchoCanceller.DEFAULT_SUPPRESS_ACTIVE_DB,
) : AutoCloseable {
    private val requestedFilterLengthMs = filterLengthMs
    private val requestedSuppressDb = suppressDb
    private val requestedSuppressActiveDb = suppressActiveDb

    private val aec3: EchoCanceller3? = runCatching {
        EchoCanceller3(filterLengthMs = filterLengthMs, suppressDb = suppressDb)
    }
        .onFailure { Log.e(TAG, "Failed to init AEC3, falling back to Speex", it) }
        .getOrNull()
        ?.takeIf { it.isInitialized }

    private val aec: EchoCanceller? = if (aec3 != null) {
        null
    } else {
        runCatching {
            EchoCanceller(
                filterLengthMs = filterLengthMs,
                suppressDb = suppressDb,
                suppressActiveDb = suppressActiveDb,
            )
        }
            .onFailure { Log.e(TAG, "Failed to init software AEC, passing through", it) }
            .getOrNull()
            ?.takeIf { it.isInitialized }
    }

    private val frameSize = EchoCanceller.DEFAULT_FRAME_SIZE
    private val micFrame = ShortArray(frameSize)
    private val refFrame = ShortArray(frameSize)
    private val outFrame = ShortArray(frameSize)
    private val detectFrame = ShortArray(frameSize)
    private val carry = ShortArray(frameSize)
    private var carryLen = 0

    // Always-on far-end telemetry (one logcat line per ~2 s of active playback).
    // Purpose: field diagnosis of "wake dies above X% volume" — the line shows
    // whether the mic is clipping (nonlinear echo a linear AEC cannot cancel),
    // whether the reference is present/sane, and what AEC3 thinks of the echo path.
    private var telWindowStartMs = 0L
    private var telMicSq = 0.0
    private var telRefSq = 0.0
    private var telOutSq = 0.0
    private var telMicPeak = 0
    private var telClipped = 0L
    private var telSamples = 0L
    private val telMetrics = FloatArray(3)
    private var output = ByteBuffer.allocateDirect(frameSize * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
    private var detectOut = ByteBuffer.allocateDirect(frameSize * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
    private var refOut = ByteBuffer.allocateDirect(frameSize * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
    private val empty = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Wake-detection tap from the last [process] call: same frames as its return
     * value, but mixed with a much higher AEC3 linear-filter share so barge-in
     * speech survives loud playback (offline model: +15 dB wake SNR at full
     * volume). More residual music than the uplink output — feed the wake word
     * engine only. Valid until the next [process] call; AEC3 only (equals the
     * uplink output on the Speex fallback).
     */
    var detectOutput: ByteBuffer = empty
        private set

    /**
     * Clean playback reference frames consumed by the last [process] call,
     * sample-parallel with [detectOutput] (same AEC frames, same length).
     * Machine-floor input for the stop-word DSP: it learns per-band echo-path
     * gains from this signal and detects on the energy above the predicted
     * residual. Valid until the next [process] call.
     */
    var referenceOutput: ByteBuffer = empty
        private set

    /**
     * Normalized (0–1) RMS of the reference frames consumed by the last [process]
     * call that produced output. Double-talk ruler for the capture loop: the echo
     * residual at the detect tap cannot exceed echo-path gain × this value, while
     * near-end speech can. Holds its previous value across sub-frame calls that
     * consume no reference.
     */
    var lastRefRms: Float = 0f
        private set

    val isActive get() = aec3 != null || aec != null
    /** True when the WebRTC AEC3 engine is live (Speex knobs are then inert). */
    val isAec3Active get() = aec3 != null
    val filterLengthMs: Int get() = aec?.filterLengthMs ?: requestedFilterLengthMs
    val suppressDb: Int get() = aec?.suppressDb ?: requestedSuppressDb
    val suppressActiveDb: Int get() = aec?.suppressActiveDb ?: requestedSuppressActiveDb

    init {
        PlaybackReferenceBus.bulkDelayMs = if (aec3 != null) {
            PlaybackReferenceBus.AEC3_BULK_DELAY_MS
        } else {
            PlaybackReferenceBus.SPEEX_BULK_DELAY_MS
        }
        SoftAecProbe.noteEngine(active = isActive, aec3 = isAec3Active)
        SoftAecProbe.noteDesign(
            filterLengthMs = this.filterLengthMs,
            suppressDb = this.suppressDb,
            suppressActiveDb = this.suppressActiveDb,
        )
        if (aec3 != null) {
            Log.i(TAG, "Software AEC engine: WebRTC AEC3 (block=${aec3.blockSize})")
        } else if (aec != null) {
            Log.i(TAG, "Software AEC engine: SpeexDSP fallback")
        }
    }

    /**
     * Input must be 16kHz mono 16-bit LE PCM. Returns whole echo-cancelled frames only; a
     * sub-frame tail is held for the next call, so output lags input by under one frame.
     *
     * Every returned sample has been through the canceller. Emitting the sub-frame tail as raw
     * mic audio (to keep the sample count identical) spliced un-cancelled echo between AEC
     * frames, which was audible as a periodic click at the mic read rate.
     */
    fun process(input: ByteBuffer): ByteBuffer {
        if (!isActive) {
            detectOutput = rewindCopy(input)
            referenceOutput = empty
            return rewindCopy(input)
        }
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val inputSamples = src.remaining() / 2
        if (inputSamples <= 0) {
            detectOutput = rewindCopy(input)
            referenceOutput = empty
            return rewindCopy(input)
        }

        val totalSamples = carryLen + inputSamples
        val frames = totalSamples / frameSize
        val probe = SoftAecProbe.enabled

        if (frames == 0) {
            while (src.remaining() >= 2) {
                carry[carryLen++] = src.short
            }
            detectOutput = empty
            referenceOutput = empty
            return empty
        }

        val outputSamples = frames * frameSize
        if (output.capacity() < outputSamples * 2) {
            output = ByteBuffer.allocateDirect(outputSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        }
        output.clear()
        if (detectOut.capacity() < outputSamples * 2) {
            detectOut = ByteBuffer.allocateDirect(outputSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        }
        detectOut.clear()
        if (refOut.capacity() < outputSamples * 2) {
            refOut = ByteBuffer.allocateDirect(outputSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        }
        refOut.clear()

        // Device volume / mute changed: the echo just rescaled but the reference
        // did not (device volume is applied after the tee). Tell AEC3 once so it
        // re-adapts immediately instead of leaking echo while re-converging.
        val levelChange = aec3 != null && PlaybackReferenceBus.hasRecentLevelChange()

        var refSquareSum = 0.0
        repeat(frames) {
            var filled = 0
            if (carryLen > 0) {
                carry.copyInto(micFrame, 0, 0, carryLen)
                filled = carryLen
                carryLen = 0
            }
            while (filled < frameSize) {
                micFrame[filled++] = src.short
            }
            PlaybackReferenceBus.read(refFrame)
            // Quiet-room skip: AEC3 on a zero reference is a full filter pass
            // (~15–20% of a core on MI 9). Always consume the ring first so
            // burst writes and speaker delay stay aligned; never reset the
            // adapted filter just because playback paused.
            val frameRefSq = refFrameSquareSum(refFrame)
            if (frameRefSq > 0.0) PlaybackReferenceBus.noteRefEnergy()
            refSquareSum += frameRefSq
            val runCanceller = PlaybackReferenceBus.isFarEndActive()
            if (runCanceller) {
                if (aec3 != null) {
                    aec3.process(micFrame, refFrame, outFrame, levelChange, detectFrame)
                } else {
                    aec?.process(micFrame, refFrame, outFrame)
                    outFrame.copyInto(detectFrame)
                }
            } else {
                micFrame.copyInto(outFrame)
                micFrame.copyInto(detectFrame)
            }
            if (probe) SoftAecProbe.noteFrame(micFrame, refFrame, outFrame, frameSize)
            accumulateTelemetry()
            for (i in 0 until frameSize) {
                output.putShort(outFrame[i])
                detectOut.putShort(detectFrame[i])
                refOut.putShort(refFrame[i])
            }
        }

        while (src.remaining() >= 2) {
            carry[carryLen++] = src.short
        }

        lastRefRms =
            (kotlin.math.sqrt(refSquareSum / outputSamples) / Short.MAX_VALUE).toFloat()
        output.flip()
        detectOut.flip()
        refOut.flip()
        detectOutput = detectOut
        referenceOutput = refOut
        return output
    }

    /**
     * Accumulates per-frame stats while far-end playback is active; publishes a
     * summary to [SoftAecProbe] (and one logcat line) per [TELEMETRY_INTERVAL_MS].
     * Gated on the stats sheet being open ([SoftAecProbe.enabled]) — zero work and
     * zero log traffic otherwise. Runs on the capture thread; cost is one pass
     * over samples already in cache (negligible next to AEC).
     */
    private fun accumulateTelemetry() {
        if (!SoftAecProbe.enabled || !PlaybackReferenceBus.isFarEndActive()) {
            // Reset between playback sessions so a window never spans idle gaps.
            telWindowStartMs = 0L
            telSamples = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (telWindowStartMs == 0L) {
            telWindowStartMs = now
            telMicSq = 0.0; telRefSq = 0.0; telOutSq = 0.0
            telMicPeak = 0; telClipped = 0L; telSamples = 0L
        }
        for (i in 0 until frameSize) {
            val m = micFrame[i].toInt()
            val am = if (m < 0) -m else m
            if (am > telMicPeak) telMicPeak = am
            if (am >= CLIP_THRESHOLD) telClipped++
            telMicSq += m.toDouble() * m
            val r = refFrame[i].toDouble()
            telRefSq += r * r
            val o = outFrame[i].toDouble()
            telOutSq += o * o
        }
        telSamples += frameSize
        if (now - telWindowStartMs < TELEMETRY_INTERVAL_MS || telSamples <= 0) return

        val inv = 1.0 / telSamples
        val micRms = kotlin.math.sqrt(telMicSq * inv)
        val refRms = kotlin.math.sqrt(telRefSq * inv)
        val outRms = kotlin.math.sqrt(telOutSq * inv)
        val clipPct = telClipped * 100.0 / telSamples
        val erleDb = if (micRms > 1.0 && outRms > 1.0) {
            20.0 * kotlin.math.log10(micRms / outRms)
        } else {
            0.0
        }
        aec3?.metrics(telMetrics)
        SoftAecProbe.noteFarendWindow(
            micClipPct = clipPct.toFloat(),
            micPeak = telMicPeak,
            aec3ErlDb = telMetrics[0],
            aec3ErleDb = telMetrics[1],
            aec3DelayMs = telMetrics[2],
        )
        Log.i(
            TAG,
            "farend telemetry: mic[rms=%.0f peak=%d clip=%.2f%%] ref[rms=%.0f] out[rms=%.0f] ".format(
                micRms, telMicPeak, clipPct, refRms, outRms,
            ) +
                "erle=%.1fdB aec3[erl=%.1f erle=%.1f delay=%.0fms]".format(
                    erleDb, telMetrics[0], telMetrics[1], telMetrics[2],
                ),
        )
        telWindowStartMs = now
        telMicSq = 0.0; telRefSq = 0.0; telOutSq = 0.0
        telMicPeak = 0; telClipped = 0L; telSamples = 0L
    }

    private fun rewindCopy(input: ByteBuffer): ByteBuffer {
        return input.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            rewind()
        }
    }

    fun setEchoSuppress(suppressDb: Int, suppressActiveDb: Int) {
        aec?.setEchoSuppress(suppressDb, suppressActiveDb)
        SoftAecProbe.noteDesign(
            filterLengthMs = this.filterLengthMs,
            suppressDb = this.suppressDb,
            suppressActiveDb = this.suppressActiveDb,
        )
    }

    /** One pass serves both the quiet-room skip (any energy?) and [lastRefRms]. */
    private fun refFrameSquareSum(frame: ShortArray): Double {
        var sum = 0.0
        for (sample in frame) {
            val s = sample.toDouble()
            sum += s * s
        }
        return sum
    }

    private fun resetEngine() {
        aec3?.reset()
        aec?.reset()
        carryLen = 0
    }

    fun reset() {
        resetEngine()
        PlaybackReferenceBus.resetReader()
    }

    override fun close() {
        aec3?.close()
        aec?.close()
    }

    companion object {
        private const val TAG = "SoftwareAecProcessor"
        private const val TELEMETRY_INTERVAL_MS = 2000L
        /** |sample| at/above this counts as an ADC clip (int16 full scale ±32767). */
        private const val CLIP_THRESHOLD = 32700
    }
}
