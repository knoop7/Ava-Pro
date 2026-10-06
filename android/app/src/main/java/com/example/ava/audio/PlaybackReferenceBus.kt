package com.example.ava.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Far-end (playback) reference bus for software AEC.
 *
 * Every playback path (ExoPlayer media/TTS/wake sounds, Sendspin) pushes its PCM here.
 * The bus converts each stream to 16kHz mono, mixes overlapping streams onto a shared
 * timeline ring buffer, and the microphone capture loop pulls time-aligned reference
 * frames from it.
 *
 * Alignment model: a writer's first sample is anchored at `readPos + bulkDelay`, i.e. the
 * reference is assumed to come out of the speaker roughly [bulkDelayMs] after it is
 * written to the AudioTrack. The remaining mismatch (device output latency, mic input
 * latency, room reverb) is absorbed by the AEC adaptive filter tail.
 *
 * The delay is engine-dependent, see [bulkDelayMs].
 */
object PlaybackReferenceBus {
    const val SAMPLE_RATE = 16000

    /** Speex has no delay estimator, so its reference must arrive pre-aligned. */
    const val SPEEX_BULK_DELAY_MS = 100

    /**
     * AEC3 aligns internally and needs the reference *ahead* of the acoustic echo.
     * Keep a small positive cushion so the mic reader never outruns the playback
     * writer (0 ms made ref frames go silent under scheduling jitter → echo leaked
     * in bursts). 40 ms is well below typical device+acoustic path delay, so AEC3's
     * estimator still sees a positive lag to track.
     */
    const val AEC3_BULK_DELAY_MS = 40

    /**
     * Writer→speaker bulk delay used for alignment. Set by [SoftwareAecProcessor] to
     * match whichever engine it constructed; read by stats.
     */
    @Volatile
    var bulkDelayMs: Int = SPEEX_BULK_DELAY_MS

    private const val RING_SECONDS = 4
    private const val RING_SIZE = SAMPLE_RATE * RING_SECONDS
    private val delaySamples: Int get() = SAMPLE_RATE * bulkDelayMs / 1000
    /** Far-end is considered "playing" for this long after the last write (covers echo tail). */
    const val PLAYBACK_RECENT_MS = 250L

    /**
     * Shared far-end live window for AEC, barge-in, and telemetry. Covers TTS
     * sentence gaps (300–500 ms) and the old 600 ms barge-in hold.
     */
    const val FAR_END_WRITE_HOLD_MS = 1_500L

    /** Keep canceller / barge-in up after the ring goes silent (room tail). */
    const val FAR_END_ECHO_TAIL_MS = 300L

    /**
     * True while at least one capture/playback holder has [acquire]d the bus.
     * Writers skip mix work when false. Use [acquire]/[release] — do not assign.
     */
    @Volatile
    var active = false
        private set

    /** Satellite capture and live-call record/play each hold one ref. */
    private val holders = AtomicInteger(0)

    /**
     * Hold the far-end bus so writers mix reference PCM.
     * @return true when this call turned the bus on (first holder).
     */
    fun acquire(): Boolean {
        val count = holders.incrementAndGet()
        active = true
        return count == 1
    }

    /**
     * Drop one hold. [active] stays true while another holder remains —
     * pausing the satellite during a call must not mute the call AEC reference.
     * @return true when this call turned the bus off (last holder).
     */
    fun release(): Boolean {
        val count = holders.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        if (count > 0) return false
        active = false
        return true
    }

    /**
     * Wall-clock of the last speaker output-level change that the reference cannot
     * see (device STREAM_MUSIC volume / mute — applied in the Android mixer *after*
     * the PCM we tee). SP/MA volume runs in device-volume mode, so every slider or
     * key change rescales the acoustic echo while the reference stays put; AEC3 is
     * told via its `level_change` flag so it re-adapts instead of leaking.
     */
    @Volatile
    private var levelChangeMs = 0L

    /** How long [hasRecentLevelChange] stays true after [noteLevelChange]. */
    const val LEVEL_CHANGE_WINDOW_MS = 150L

    /** Call whenever device music volume / mute changes. Cheap; safe from any thread. */
    fun noteLevelChange() {
        levelChangeMs = System.currentTimeMillis()
    }

    /** True briefly after a level change; the capture loop forwards it to AEC3. */
    fun hasRecentLevelChange(windowMs: Long = LEVEL_CHANGE_WINDOW_MS): Boolean =
        levelChangeMs != 0L && (System.currentTimeMillis() - levelChangeMs) <= windowMs

    /** Wall-clock of the most recent non-empty far-end write; gates barge-in detection. */
    @Volatile
    private var lastWriteMs = 0L

    /**
     * Wall-clock of the most recent non-empty write *attempt* — updated even when
     * [active] is false. Diagnostics only; does not drive AEC / barge-in.
     */
    @Volatile
    private var lastSignalMs = 0L

    /** Wall-clock of the last reference frame that had energy (set by the AEC reader). */
    @Volatile
    private var lastRefEnergyMs = 0L

    /** Latest absolute sample position any writer has mixed up to (diagnostics). */
    @Volatile
    private var diagLatestWritePos = 0L

    @Volatile
    private var diagSamplesWritten = 0L

    @Volatile
    private var diagReanchorCount = 0L

    /**
     * True when far-end audio was queued within [windowMs].
     * Barge-in and the canceller use [isFarEndActive] instead — write time alone
     * misses burst-queued PCM still sitting in the ring.
     */
    fun hasRecentPlayback(windowMs: Long = PLAYBACK_RECENT_MS): Boolean =
        active && lastWriteMs != 0L && (System.currentTimeMillis() - lastWriteMs) <= windowMs

    /** Capture loop saw non-silent reference in the ring. */
    fun noteRefEnergy() {
        lastRefEnergyMs = System.currentTimeMillis()
    }

    /**
     * One ruler for the canceller, wake barge-in, and far-end telemetry.
     * True when writers are live, the ring still has energy, or the room tail
     * has not drained. Do not gate barge-in on [hasRecentPlayback] alone —
     * burst-queued PCM ages [lastWriteMs] out while the speaker is still playing.
     */
    fun isFarEndActive(
        writeHoldMs: Long = FAR_END_WRITE_HOLD_MS,
        echoTailMs: Long = FAR_END_ECHO_TAIL_MS,
    ): Boolean {
        if (!active) return false
        val now = System.currentTimeMillis()
        if (lastWriteMs != 0L && now - lastWriteMs <= writeHoldMs) return true
        if (lastRefEnergyMs != 0L && now - lastRefEnergyMs <= echoTailMs) return true
        return false
    }

    /**
     * Diagnostic far-end activity: true when any writer saw PCM recently, regardless of
     * [active]. Safe for stats UI when software AEC is off; do not use for barge-in.
     */
    fun hasRecentPlaybackSignal(windowMs: Long = PLAYBACK_RECENT_MS): Boolean =
        (System.currentTimeMillis() - lastSignalMs) <= windowMs

    /**
     * Fixed attenuation applied when reading the mixed reference out of the ring.
     *
     * Overlapping writers (TTS over music, wake chime over media) each arrive near
     * full scale; summing them in int16 with per-write clamping *clipped the
     * reference* exactly during overlaps, while the real speaker mix (float mixer ×
     * master volume) does not clip. That nonlinearity is unlearnable for the linear
     * AEC filter → residual echo bursts right when barge-in matters most. The ring
     * is now float (no per-write clamp) and this −6 dB headroom lets two full-scale
     * streams sum cleanly; the constant scale is absorbed by the adaptive filter.
     */
    private const val MIX_HEADROOM = 0.5f

    private val lock = Any()
    private val ring = FloatArray(RING_SIZE)

    /** Absolute sample position the mic side has consumed up to. */
    private var readPos = 0L

    /**
     * Trailing copy of the mixed reference exactly as [read] handed it to the AEC.
     * Echo-verify evidence: when a wake fires during far-end playback, re-scoring
     * this window with the same wake model tells whether the playback *itself*
     * contains the (near-)phrase — the self-trigger class neither AEC residual
     * suppression nor the TTS-text screen can reject (near-words like "hey Travis"
     * score 0.94+ on hey_jarvis at a −30 dB residual). Consumed reads are cleared
     * from [ring], so verification needs this separate history.
     */
    private const val HISTORY_SECONDS = 3
    private const val HISTORY_SIZE = SAMPLE_RATE * HISTORY_SECONDS
    private val historyRing = ShortArray(HISTORY_SIZE)

    /** Wall-clock of the last [read] pass — history is only meaningful while fresh. */
    @Volatile
    private var historyReadMs = 0L

    /** History older than this is stale (AEC bypassed / capture stopped mid-flight). */
    private const val HISTORY_FRESH_MS = 1_000L

    fun resetReader() {
        synchronized(lock) {
            ring.fill(0f)
            readPos = 0
            historyRing.fill(0)
        }
        lastRefEnergyMs = 0L
        historyReadMs = 0L
    }

    /** Clears diagnostic counters only (does not touch the AEC ring). */
    fun resetDiagnostics() {
        diagLatestWritePos = 0L
        diagSamplesWritten = 0L
        diagReanchorCount = 0L
    }

    /**
     * Read-only far-end bus metrics for Voice Stats. Safe with soft AEC off.
     * Ages are `-1` when the corresponding stamp has never been set.
     */
    fun diagnosticsSnapshot(): PlaybackRefDiagnostics {
        val now = System.currentTimeMillis()
        val signalAge = if (lastSignalMs == 0L) -1L else (now - lastSignalMs).coerceAtLeast(0L)
        val writeAge = if (lastWriteMs == 0L) -1L else (now - lastWriteMs).coerceAtLeast(0L)
        val aheadSamples: Long
        synchronized(lock) {
            aheadSamples = (diagLatestWritePos - readPos).coerceAtLeast(0L)
        }
        return PlaybackRefDiagnostics(
            signalAgeMs = signalAge,
            writeAgeMs = writeAge,
            writerAheadSamples = aheadSamples,
            writerAheadMs = aheadSamples * 1000L / SAMPLE_RATE,
            reanchorCount = diagReanchorCount,
            samplesWritten = diagSamplesWritten,
            busActive = active,
        )
    }

    /**
     * Pulls [count] reference samples aligned with "now". Consumed samples are
     * cleared so overlapping writers can keep mix-adding. Returns silence when
     * nothing is playing.
     *
     * The software AEC reads whole frames ([count] = size); the hardware-AEC
     * capture path reuses a grown-on-demand scratch and passes the exact mic
     * chunk size so [readPos] stays paced by the mic clock.
     */
    fun read(out: ShortArray, count: Int = out.size) {
        val n = count.coerceIn(0, out.size)
        if (n == 0) return
        synchronized(lock) {
            var pos = readPos
            for (i in 0 until n) {
                val idx = (pos % RING_SIZE).toInt()
                // Single clamp on the final mix (with headroom) instead of clipping
                // per-writer int16 partial sums — see [MIX_HEADROOM].
                out[i] = (ring[idx] * MIX_HEADROOM)
                    .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                    .toInt()
                    .toShort()
                historyRing[(pos % HISTORY_SIZE).toInt()] = out[i]
                ring[idx] = 0f
                pos++
            }
            readPos = pos
        }
        historyReadMs = System.currentTimeMillis()
    }

    /**
     * Trailing [ms] of the reference the AEC consumed, ending at "now" (mic-aligned
     * [readPos]). Null when no capture loop is pulling the bus (no software AEC), when
     * the history is stale (AEC bypassed mid-speech), or when less audio than requested
     * has flowed — echo verification must fail open to the normal wake path, never
     * judge a half-empty window.
     */
    fun recentReferencePcm(ms: Int): ShortArray? {
        if (historyReadMs == 0L ||
            System.currentTimeMillis() - historyReadMs > HISTORY_FRESH_MS
        ) {
            return null
        }
        val want = (ms * SAMPLE_RATE / 1000).coerceAtMost(HISTORY_SIZE)
        if (want <= 0) return null
        synchronized(lock) {
            if (readPos < want) return null
            val out = ShortArray(want)
            val start = readPos - want
            for (i in 0 until want) {
                out[i] = historyRing[((start + i) % HISTORY_SIZE).toInt()]
            }
            return out
        }
    }

    private fun mixAt(absolutePos: Long, sample: Float) {
        val idx = (absolutePos % RING_SIZE).toInt()
        ring[idx] += sample
    }

    fun createWriter(sampleRate: Int, channels: Int): Writer =
        Writer(sampleRate, channels, isFloat = false)

    /** Float overload — ExoPlayer's float audio sink delivers normalised [-1f, 1f] samples. */
    fun createFloatWriter(sampleRate: Int, channels: Int): Writer =
        Writer(sampleRate, channels, isFloat = true)

    /**
     * Per-stream writer: downmixes to mono, linearly resamples to 16kHz, and mix-adds
     * onto the shared timeline. Not thread-safe; use one writer per stream thread.
     *
     * Supports both 16-bit little-endian and 32-bit float native-order PCM. Float samples
     * are scaled to int16 range so the shared ring buffer stays a single uniform format.
     */
    class Writer(
        private val sampleRate: Int,
        private val channels: Int,
        private val isFloat: Boolean,
    ) {
        /** Absolute timeline cursor for this stream's next output sample. */
        private var cursor = -1L

        /** Linear resampler state: source position (fractional) and previous sample. */
        private var srcPos = 0.0
        private var prevSample = 0f
        private var hasPrev = false
        private var step = sampleRate.toDouble() / SAMPLE_RATE
        private val bytesPerFrame: Int = (if (isFloat) 4 else 2) * channels

        /**
         * Retunes the resample ratio in place (playback-speed fold). Keeps the
         * timeline cursor and resampler state, so unlike recreating the writer it
         * causes no reference discontinuity: a new writer re-anchors at
         * `readPos + bulkDelay`, silently dropping the accumulated writer-ahead
         * offset — a delay jump the AEC must re-converge through every time.
         */
        fun updateSourceRate(newSampleRate: Int) {
            if (newSampleRate <= 0) return
            synchronized(lock) {
                step = newSampleRate.toDouble() / SAMPLE_RATE
            }
        }

        /**
         * Reusable mono-mix scratch buffer. Grown on demand and held by the writer so
         * the audio callback path stays allocation-free in steady state (matters on
         * low-RAM hardware like Echo Show / Portal Mini where per-callback FloatArrays
         * would add steady GC churn).
         */
        private var monoBuf: FloatArray = FloatArray(0)

        fun write(pcm: ByteBuffer) {
            if (sampleRate <= 0 || channels <= 0 || bytesPerFrame <= 0) return
            // Float audio uses platform-native byte order (matches PlaybackEnergyTee);
            // 16-bit integer PCM in Android audio frames is little-endian.
            val byteOrder = if (isFloat) ByteOrder.nativeOrder() else ByteOrder.LITTLE_ENDIAN
            val buf = pcm.duplicate().order(byteOrder)
            val frameCount = buf.remaining() / bytesPerFrame
            if (frameCount <= 0) return
            // Diagnostic stamp before the AEC gate so Far-end stats work with soft AEC off.
            val now = System.currentTimeMillis()
            lastSignalMs = now
            if (!active) return
            lastWriteMs = now

            if (monoBuf.size < frameCount) monoBuf = FloatArray(frameCount)
            val mono = monoBuf

            if (isFloat) {
                // Float input in [-1, 1] → scaled to int16 range so the rest of the
                // pipeline (float mix ring, read-time clamp) treats every source uniformly.
                val scale = Short.MAX_VALUE.toFloat()
                for (f in 0 until frameCount) {
                    var acc = 0f
                    for (c in 0 until channels) acc += buf.float
                    mono[f] = (acc / channels) * scale
                }
            } else {
                for (f in 0 until frameCount) {
                    var acc = 0
                    for (c in 0 until channels) acc += buf.short.toInt()
                    mono[f] = acc.toFloat() / channels
                }
            }
            writeMono(mono, frameCount)
        }

        fun write(pcm16: ByteArray, offset: Int, length: Int) {
            if (sampleRate <= 0 || channels <= 0 || isFloat) return
            // Route through ByteBuffer write so lastSignalMs updates even when !active.
            write(ByteBuffer.wrap(pcm16, offset, length))
        }

        private fun writeMono(mono: FloatArray, count: Int) {
            if (count <= 0) return
            synchronized(lock) {
                reanchorIfNeededLocked(count)
                if (!hasPrev) {
                    prevSample = mono[0]
                    hasPrev = true
                }
                val cursorBefore = cursor
                // Linear interpolation resample sourceRate -> 16k.
                while (true) {
                    val i = srcPos.toInt()
                    if (i >= count) break
                    val frac = (srcPos - i).toFloat()
                    val a = if (i == 0) prevSample else mono[i - 1]
                    val b = mono[i]
                    val sample = a + (b - a) * frac
                    mixAt(cursor, sample)
                    cursor++
                    srcPos += step
                }
                srcPos -= count
                prevSample = mono[count - 1]
                if (SoftAecProbe.enabled) {
                    if (cursor > diagLatestWritePos) diagLatestWritePos = cursor
                    val produced = (cursor - cursorBefore).coerceAtLeast(0L)
                    diagSamplesWritten += produced
                }
            }
        }

        private fun reanchorIfNeededLocked(incomingFrames: Int) {
            val anchor = readPos + delaySamples
            if (cursor < 0) {
                cursor = anchor
                return
            }
            // Stream fell behind real time (pause/seek) or mic reader was reset.
            if (cursor < readPos) {
                if (SoftAecProbe.enabled) diagReanchorCount++
                cursor = anchor
                srcPos = 0.0
                hasPrev = false
                return
            }
            // Stream buffered too far ahead for the ring; re-anchor instead of wrapping.
            val incomingOut = (incomingFrames / step).toLong() + 1
            if (cursor + incomingOut > readPos + RING_SIZE) {
                if (SoftAecProbe.enabled) diagReanchorCount++
                cursor = anchor
                srcPos = 0.0
                hasPrev = false
            }
        }
    }
}

/** Far-end bus numbers for Echo Voice Stats (read-only). */
data class PlaybackRefDiagnostics(
    val signalAgeMs: Long,
    val writeAgeMs: Long,
    val writerAheadSamples: Long,
    val writerAheadMs: Long,
    val reanchorCount: Long,
    val samplesWritten: Long,
    val busActive: Boolean,
)
