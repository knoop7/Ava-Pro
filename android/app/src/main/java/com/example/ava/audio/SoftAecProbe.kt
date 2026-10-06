package com.example.ava.audio

import com.example.microfeatures.EchoCanceller
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Gated soft-AEC diagnostics for Voice Stats (Echo focus).
 *
 * [enabled] is flipped by the stats sheet: when false, process-path probes are no-ops
 * (no RMS work, no counter traffic). Never drives AEC / barge-in / capture behavior.
 */
object SoftAecProbe {
    @Volatile
    var enabled: Boolean = false

    @Volatile private var micRms = 0f
    @Volatile private var refRms = 0f
    @Volatile private var outRms = 0f
    @Volatile private var cancelDb = 0f
    @Volatile private var refNonZeroPct = 0f
    @Volatile private var micClipPct = 0f
    @Volatile private var micPeak = 0
    @Volatile private var aec3ErlDb = 0f
    @Volatile private var aec3ErleDb = 0f
    @Volatile private var aec3DelayMs = 0f
    @Volatile private var framesProcessed = 0L
    @Volatile private var framesBypassed = 0L
    @Volatile private var engineActive = false
    @Volatile private var engineIsAec3 = false
    @Volatile private var lastProcessAtMs = 0L
    @Volatile private var designFilterLengthMs = EchoCanceller.DEFAULT_FILTER_LENGTH_MS
    @Volatile private var designSuppressDb = EchoCanceller.DEFAULT_SUPPRESS_DB
    @Volatile private var designSuppressActiveDb = EchoCanceller.DEFAULT_SUPPRESS_ACTIVE_DB

    fun reset() {
        micRms = 0f
        refRms = 0f
        outRms = 0f
        cancelDb = 0f
        refNonZeroPct = 0f
        micClipPct = 0f
        micPeak = 0
        aec3ErlDb = 0f
        aec3ErleDb = 0f
        aec3DelayMs = 0f
        framesProcessed = 0L
        framesBypassed = 0L
        engineActive = false
        lastProcessAtMs = 0L
        // Keep designFilterLengthMs / suppress* — set by live SoftwareAecProcessor.
        PlaybackReferenceBus.resetDiagnostics()
    }

    /** Called by the constructed processor: records which engine won and whether it is live. */
    fun noteEngine(active: Boolean, aec3: Boolean) {
        engineIsAec3 = aec3
        if (!enabled) return
        engineActive = active
    }

    /** Called by the stats poller, which only knows liveness — never the engine kind. */
    fun noteEngineActive(active: Boolean) {
        if (!enabled) return
        engineActive = active
    }

    /** Live design numbers from the constructed processor (not hard-coded). */
    fun noteDesign(filterLengthMs: Int, suppressDb: Int, suppressActiveDb: Int) {
        designFilterLengthMs = filterLengthMs
        designSuppressDb = suppressDb
        designSuppressActiveDb = suppressActiveDb
    }

    fun noteBypass() {
        if (!enabled) return
        framesBypassed++
    }

    /**
     * Windowed far-end telemetry from [SoftwareAecProcessor] (~2 s aggregates while
     * playback is active): mic clip ratio/peak and AEC3's own echo-path estimates.
     * Key for volume-dependent wake failures — clip% > ~1% means nonlinear echo.
     */
    fun noteFarendWindow(
        micClipPct: Float,
        micPeak: Int,
        aec3ErlDb: Float,
        aec3ErleDb: Float,
        aec3DelayMs: Float,
    ) {
        if (!enabled) return
        this.micClipPct = micClipPct
        this.micPeak = micPeak
        this.aec3ErlDb = aec3ErlDb
        this.aec3ErleDb = aec3ErleDb
        this.aec3DelayMs = aec3DelayMs
    }

    /**
     * Called once per Speex frame after [EchoCanceller.process].
     * [mic]/[ref]/[out] are 16-bit PCM frame buffers of equal length.
     */
    fun noteFrame(mic: ShortArray, ref: ShortArray, out: ShortArray, length: Int = mic.size) {
        if (!enabled || length <= 0) return
        val n = length.coerceAtMost(minOf(mic.size, ref.size, out.size))
        if (n <= 0) return

        var micSq = 0.0
        var refSq = 0.0
        var outSq = 0.0
        var refNz = 0
        for (i in 0 until n) {
            val m = mic[i].toDouble()
            val r = ref[i].toDouble()
            val o = out[i].toDouble()
            micSq += m * m
            refSq += r * r
            outSq += o * o
            if (ref[i] != 0.toShort()) refNz++
        }
        val inv = 1.0 / n
        val scale = 1f / 32768f
        val micN = (sqrt(micSq * inv) * scale).toFloat()
        val refN = (sqrt(refSq * inv) * scale).toFloat()
        val outN = (sqrt(outSq * inv) * scale).toFloat()
        // ERLE-ish: how much the mic energy dropped after cancellation.
        // Positive when out is quieter than mic (echo removed). Cap extremes.
        val erle = when {
            micN < 1e-5f -> 0f
            outN < 1e-6f -> 40f
            else -> (20f * log10((micN / outN).toDouble())).toFloat().coerceIn(-20f, 40f)
        }

        micRms = micN
        refRms = refN
        outRms = outN
        cancelDb = erle
        refNonZeroPct = (refNz * 100f) / n
        framesProcessed++
        engineActive = true
        lastProcessAtMs = System.currentTimeMillis()
    }

    fun snapshot(): SoftAecProbeSnapshot {
        val now = System.currentTimeMillis()
        val bus = PlaybackReferenceBus.diagnosticsSnapshot()
        return SoftAecProbeSnapshot(
            enabled = enabled,
            engineActive = engineActive,
            engineIsAec3 = engineIsAec3,
            micRms = micRms,
            refRms = refRms,
            outRms = outRms,
            cancelDb = cancelDb,
            refNonZeroPct = refNonZeroPct,
            micClipPct = micClipPct,
            micPeak = micPeak,
            aec3ErlDb = aec3ErlDb,
            aec3ErleDb = aec3ErleDb,
            aec3DelayMs = aec3DelayMs,
            framesProcessed = framesProcessed,
            framesBypassed = framesBypassed,
            processAgeMs = if (lastProcessAtMs == 0L) -1L else (now - lastProcessAtMs).coerceAtLeast(0L),
            signalAgeMs = bus.signalAgeMs,
            writeAgeMs = bus.writeAgeMs,
            writerAheadMs = bus.writerAheadMs,
            writerAheadSamples = bus.writerAheadSamples,
            reanchorCount = bus.reanchorCount,
            samplesWritten = bus.samplesWritten,
            busActive = bus.busActive,
            bulkDelayMs = PlaybackReferenceBus.bulkDelayMs,
            filterLengthMs = designFilterLengthMs,
            frameSize = EchoCanceller.DEFAULT_FRAME_SIZE,
            sampleRateHz = PlaybackReferenceBus.SAMPLE_RATE,
            suppressDb = designSuppressDb,
            suppressActiveDb = designSuppressActiveDb,
        )
    }
}

data class SoftAecProbeSnapshot(
    val enabled: Boolean,
    val engineActive: Boolean,
    /** True when WebRTC AEC3 is the live engine, false for the SpeexDSP fallback. */
    val engineIsAec3: Boolean,
    val micRms: Float,
    val refRms: Float,
    val outRms: Float,
    /** Approximate ERLE in dB (mic vs out). Higher = more cancellation. */
    val cancelDb: Float,
    val refNonZeroPct: Float,
    /** % of mic samples at/near int16 full scale over the last far-end window. */
    val micClipPct: Float,
    /** Peak |mic sample| over the last far-end window (32767 = hard clip). */
    val micPeak: Int,
    /** AEC3's own echo-path estimates from the last far-end window. */
    val aec3ErlDb: Float,
    val aec3ErleDb: Float,
    val aec3DelayMs: Float,
    val framesProcessed: Long,
    val framesBypassed: Long,
    val processAgeMs: Long,
    val signalAgeMs: Long,
    val writeAgeMs: Long,
    val writerAheadMs: Long,
    val writerAheadSamples: Long,
    val reanchorCount: Long,
    val samplesWritten: Long,
    val busActive: Boolean,
    val bulkDelayMs: Int,
    val filterLengthMs: Int,
    val frameSize: Int,
    val sampleRateHz: Int,
    val suppressDb: Int,
    val suppressActiveDb: Int,
)
