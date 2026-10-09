package com.example.ava.sendspin

import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SendspinPcmAudioOutput(
    private val lowMemoryMode: Boolean = false,
    context: Context? = null,
) {
    private val audioManager = context?.applicationContext
        ?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val tag = "PcmAudioOutput"

    @Volatile
    private var track: AudioTrack? = null
    private val started = AtomicBoolean(false)
    
    // Track active writers to prevent releasing native object while in use
    private val writingCount = AtomicInteger(0)

    // Lock for synchronizing start/stop/pause operations
    private val lock = Any()

    private var currentSampleRate = 48000
    private var currentChannels = 2
    private var currentBitDepth = 16
    /** PCM layout from decoder before normalization. */
    @Volatile
    private var sourceFormat = SendspinPcmProcessor.SourceFormat()
    @Volatile
    private var playbackGain = SendspinPcmProcessor.PlaybackGain()
    private val limiterState = SendspinPcmProcessor.LimiterState()
    private val musicEqEngine = com.example.ava.audio.eq.MusicEqEngine(
        com.example.ava.audio.eq.MusicEqSource.SENDSPIN,
    )

    // Last reported minimum buffer size from AudioTrack HAL (bytes)
    @Volatile
    private var lastMinBufBytes: Int = 0
    @Volatile
    private var lastConfiguredBufferBytes: Int = 0

    // Dynamic latency estimation state (frames)
    private val totalFramesWritten = AtomicLong(0L)
    /** Bumped whenever [totalFramesWritten] restarts from zero (flush/recreate) — stale-anchor guard. */
    private val trackGeneration = AtomicLong(0L)
    private var playbackHeadRaw: Long = 0L
    private var playbackHeadWraps: Long = 0L
    private var smoothedLatencyUs: Long = 0L
    
    // Current playback speed
    @Volatile
    private var currentPlaybackSpeed: Float = 1.0f

    // Soft start: brief volume ramp after (re)start to avoid clicks/pops
    @Volatile
    private var softStartBeganAtMs: Long = 0L
    @Volatile
    private var targetVolume: Float = 1.0f

    // Far-end reference writer for software AEC (recreated on output format/speed change)
    @Volatile
    private var aecRefWriter: com.example.ava.audio.PlaybackReferenceBus.Writer? = null
    private var aecRefRate = 0
    private var aecRefChannels = 0
    private var aecRefSpeed = 1.0f
    private var aecRefWriterCreatedAtNs = 0L

    // Splice fade: next written chunk crossfades from the last written samples to mask timeline jumps
    private val spliceFadePending = AtomicBoolean(false)
    /** Last 16-bit sample per channel from the previous write; crossfade origin for splices. */
    private var lastWrittenFrame = IntArray(0)

    /** Call before writing audio that follows a timeline jump (catch-up drop / skip). */
    fun requestSpliceFade() {
        spliceFadePending.set(true)
    }
    
    // DAC timestamp for precision sync (AirPlay 2 style)
    private val audioTimestamp = AudioTimestamp()
    @Volatile
    private var dacTimestampsStable = false
    private var consecutiveValidTimestamps = 0
    private var lastDacFramePosition = 0L
    private var lastDacTimeUs = 0L
    private var lastTimestampQueryNs = 0L
    private var cachedDacPlayedFrames: Long? = null
    private var dacGlitchCooldownUntilNs = 0L
    /**
     * Last DAC-confirmed `playbackHeadPosition − dacFrames` (HAL/route latency
     * in frames). Applied to the playbackHeadPosition fallback in
     * [getPlaybackPositionSnapshot] so the presentation clock does not lead the
     * speaker by the whole HAL latency during the DAC warm-up / glitch window.
     * Survives AudioTrack rebuilds on purpose: the route is the same.
     */
    private var lastHeadToDacLeadFrames = 0L

    /**
     * OS-reported write→port latency (AudioTrack.getLatency / AudioManager
     * getOutputLatency), EMA-smoothed. Independent of [AudioTrack.getTimestamp],
     * matching sendspin-js `baseLatency + outputLatency`.
     */
    @Volatile
    private var smoothedReportedOutputLatencyUs = 0L
    private var lastLoggedPostPresentationUs = Long.MIN_VALUE
    @Volatile
    private var getLatencyMethod: java.lang.reflect.Method? = null
    @Volatile
    private var getLatencyUnsupported = false
    @Volatile
    private var getOutputLatencyMethod: java.lang.reflect.Method? = null
    @Volatile
    private var getOutputLatencyUnsupported = false
    
    companion object {
        private const val TIMESTAMP_STABLE_READS = 5
        /** Min interval between HAL getTimestamp calls — reduces stall/retrograde churn. */
        private const val TIMESTAMP_QUERY_INTERVAL_NS = 50_000_000L
        /** After retrograde/stall, fall back to playbackHeadPosition for this long. */
        private const val DAC_GLITCH_COOLDOWN_NS = 1_500_000_000L
        private const val SOFT_START_RAMP_MS = 35L
        /** flush() increments the underrun counter, so back-to-back revives never settle. */
        private const val UNDERRUN_REVIVE_COOLDOWN_MS = 1_500L
        private const val MAX_PIPELINE_LATENCY_US = 2_000_000L
        /** Last DAC-confirmed pipeline latency per format, used to warm-start scheduling after restarts. */
        private val stableLatencyCacheUs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    private fun latencyCacheKey(): String = "$currentSampleRate:$currentChannels:$currentBitDepth"

    fun isStarted(): Boolean = started.get()

    /** [AudioTrack.getUnderrunCount] is API 24. Snapshot so a revive runs once per underrun. */
    private var lastUnderrunCount = 0
    private var lastUnderrunReviveElapsed = 0L

    /**
     * After a HAL underrun the track may leave PLAYING while still initialized.
     * Android 8+ : [AudioTrack.play] again is enough; do not flush.
     * Android 7 and below: AudioFlinger disables the track and leaves
     * playState at PLAYING (`releaseBuffer() track disabled due to previous
     * underrun`). [AudioTrack.play] does not clear that. pause + flush + play does.
     */
    fun ensurePlaying(): Boolean {
        if (!started.get()) return false
        synchronized(lock) {
            val t = track ?: return false
            if (t.state != AudioTrack.STATE_INITIALIZED) return false
            if (revivePreOreoUnderrunLocked(t)) return true
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) return true
            return try {
                t.play()
                true
            } catch (e: Exception) {
                Log.w(tag, "ensurePlaying failed", e)
                false
            }
        }
    }

    private fun revivePreOreoUnderrunLocked(t: AudioTrack): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (writingCount.get() != 0) return false
        val now = SystemClock.elapsedRealtime()
        // pause+flush itself bumps the underrun counter, so a revive every
        // tick restarts the track a few times a second and the UI stalls with it.
        if (now - lastUnderrunReviveElapsed < UNDERRUN_REVIVE_COOLDOWN_MS) {
            noteUnderrunBaselineLocked(t)
            return false
        }
        val underruns = t.underrunCount
        if (underruns <= lastUnderrunCount) return false
        lastUnderrunCount = underruns
        lastUnderrunReviveElapsed = now
        Log.w(tag, "pre-O underrun $underruns; reviving AudioTrack")
        return try {
            t.pause()
            t.flush()
            t.play()
            noteUnderrunBaselineLocked(t)
            true
        } catch (e: Exception) {
            Log.w(tag, "pre-O underrun revive failed", e)
            false
        }
    }

    private fun noteUnderrunBaselineLocked(t: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        lastUnderrunCount = t.underrunCount
    }

    fun setSourceBitDepth(bitDepth: Int) {
        sourceFormat = SendspinPcmProcessor.SourceFormat(bitDepth)
    }

    fun setSourceFormat(format: SendspinPcmProcessor.SourceFormat) {
        sourceFormat = format
    }

    fun setPlaybackGain(gain: SendspinPcmProcessor.PlaybackGain) {
        playbackGain = gain
    }

    fun resetLimiterState() {
        limiterState.reset()
    }

    fun start(sampleRate: Int, channels: Int, bitDepth: Int) {
        synchronized(lock) {
            // Check if we can reuse the existing track
            val existingTrack = track
            if (existingTrack != null &&
                currentSampleRate == sampleRate &&
                currentChannels == channels &&
                currentBitDepth == bitDepth &&
                existingTrack.state == AudioTrack.STATE_INITIALIZED) {
                
                // Just flush and restart playback (Resume)
                try {
                    // Always flush before restarting to clear old data
                    existingTrack.pause()
                    existingTrack.flush()
                    // PlaybackParams mid-stream is an Android 8+ HAL. Below that it
                    // disables the track the same way an underrun does.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            existingTrack.playbackParams = PlaybackParams().setSpeed(1.0f)
                        } catch (_: Exception) {}
                    }
                    existingTrack.play()
                    noteUnderrunBaselineLocked(existingTrack)
                    resetLatencyEstimatorLocked()
                    beginSoftStartLocked(existingTrack)
                    started.set(true)
                    return
                } catch (e: Exception) {
                    Log.w(tag, "Failed to reuse AudioTrack, recreating...", e)
                    // Fall through to full recreate
                }
            }

            // Full recreate needed (format changed or track invalid)
            stop()

            require(bitDepth in listOf(16, 24, 32)) { "Unsupported bit depth: $bitDepth. Must be 16, 24, or 32-bit PCM" }

            val channelMask = when (channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                else -> error("Unsupported channel count: $channels")
            }

            val encoding = when (bitDepth) {
                16 -> AudioFormat.ENCODING_PCM_16BIT
                24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                32 -> AudioFormat.ENCODING_PCM_32BIT
                else -> error("Unsupported bit depth: $bitDepth")
            }

            // Ensure valid sample rate
            val safeSampleRate = sampleRate.coerceAtLeast(4000)

            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(safeSampleRate)
                .setChannelMask(channelMask)
                .build()

            val minBuf = AudioTrack.getMinBufferSize(safeSampleRate, channelMask, encoding)
            lastMinBufBytes = minBuf
            val bytesPerFrame = channels * (bitDepth / 8)
            
            val bufferMs = if (lowMemoryMode) 0.12 else 0.08
            val bufferTarget = (safeSampleRate * bufferMs * bytesPerFrame).toInt()
            val minMultiplier = if (lowMemoryMode) 3 else 2
            
            val bufferBytes = max(minBuf * minMultiplier, bufferTarget)
            lastConfiguredBufferBytes = bufferBytes

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            try {
                val audioTrack = AudioTrack(
                    attrs,
                    format,
                    bufferBytes,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(tag, "AudioTrack init failed (state=${audioTrack.state})")
                    try { audioTrack.release() } catch (_: Exception) {}
                    started.set(false)
                    track = null
                    return
                }

                audioTrack.play()
                track = audioTrack
                noteUnderrunBaselineLocked(audioTrack)
                resetLatencyEstimatorLocked()
                beginSoftStartLocked(audioTrack)
                started.set(true)

                currentSampleRate = safeSampleRate
                currentChannels = channels
                currentBitDepth = bitDepth
            } catch (e: Exception) {
                Log.e(tag, "Failed to create/start AudioTrack", e)
                started.set(false)
                track = null
            }
        }
    }

    /**
     * Pauses and flushes the audio track but keeps the instance alive for reuse.
     * Use this for seeking or stopping temporarily.
     */
    fun pause() {
        started.set(false)

        // Wait briefly for active writers to exit before touching AudioTrack state.
        var attempts = 30 // up to 300ms
        while (writingCount.get() > 0 && attempts > 0) {
            try { Thread.sleep(10) } catch (_: Exception) {}
            attempts--
        }

        synchronized(lock) {
            val t = track
            if (t != null && t.state == AudioTrack.STATE_INITIALIZED) {
                try {
                    t.pause()
                    t.flush()
                    resetLatencyEstimatorLocked()
                } catch (e: Exception) {
                    Log.w(tag, "Error pausing/flushing AudioTrack", e)
                    stopInternal(t)
                }
            }
        }
    }

    fun writePcm(pcm: ByteArray): Boolean {
        if (pcm.isEmpty()) return true
        if (!started.get()) return false

        val processed = SendspinPcmProcessor.process(
            pcm,
            sourceFormat,
            playbackGain,
            limiterState,
            musicEqEngine,
            currentSampleRate,
            currentChannels,
        )
        if (processed.isEmpty()) return true
        if (spliceFadePending.compareAndSet(true, false)) {
            applySpliceCrossfade16(processed)
        }
        captureLastFrame16(processed)

        
        // Signal we are using the track
        writingCount.incrementAndGet()
        try {
            // Double check state after increment
            if (!started.get()) return false
            
            val t = track ?: return false
            
            if (t.state == AudioTrack.STATE_UNINITIALIZED) return false
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                if (!ensurePlaying()) return false
            }
            
            var off = 0
            val bytesPerFrame = currentChannels * (currentBitDepth / 8)
            var zeroWriteCount = 0
            val maxZeroWrites = 50
            val writeStartTime = System.currentTimeMillis()
            val maxWriteTime = 500L
            
            while (off < processed.size && System.currentTimeMillis() - writeStartTime < maxWriteTime) {
                if (!started.get()) break
                updateSoftStartGain()
                
                try {
                    val n = t.write(processed, off, processed.size - off, AudioTrack.WRITE_NON_BLOCKING)
                    if (n < 0) {
                        Log.w(tag, "AudioTrack.write() returned error: $n")
                        markTrackDeadAndRelease(t, "write_error_$n")
                        return false
                    }
                    if (n == 0) {
                        zeroWriteCount++
                        if (zeroWriteCount > maxZeroWrites) {
                            val blockingN = t.write(processed, off, processed.size - off, AudioTrack.WRITE_BLOCKING)
                            if (blockingN < 0) {
                                markTrackDeadAndRelease(t, "blocking_write_error_$blockingN")
                                return false
                            }
                            if (blockingN == 0) {
                                return off > 0
                            }
                            if (bytesPerFrame > 0) {
                                totalFramesWritten.addAndGet((blockingN / bytesPerFrame).toLong())
                            }
                            feedAecReference(processed, off, blockingN)
                            off += blockingN
                            zeroWriteCount = 0
                            continue
                        }
                        Thread.yield()
                        Thread.sleep(1)
                        continue
                    }
                    zeroWriteCount = 0
                    if (bytesPerFrame > 0) {
                        totalFramesWritten.addAndGet((n / bytesPerFrame).toLong())
                    }
                    feedAecReference(processed, off, n)
                    off += n
                } catch (e: Exception) {
                    Log.e(tag, "Error writing to AudioTrack", e)
                    markTrackDeadAndRelease(t, "write_exception")
                    return false
                }
            }
            
            if (off < processed.size) {
                return off > 0
            }
            return true
        } catch (e: Exception) {
            Log.e(tag, "Error in writePcm", e)
            return false
        } finally {
            writingCount.decrementAndGet()
        }
    }

    /**
     * Software AEC far-end tap. Fed only with the byte ranges the AudioTrack actually
     * accepted, so the reference timeline stays sample-accurate even when a
     * non-blocking write drops its tail under load (feeding unplayed bytes would
     * permanently shift the reference ahead of the real echo).
     *
     * The processed PCM is always 16-bit little-endian ([SendspinPcmProcessor] output)
     * and already includes duck/volume gain.
     */
    private fun feedAecReference(pcm16: ByteArray, offset: Int, length: Int) {
        if (!com.example.ava.audio.PlaybackReferenceBus.active || length < 2) return
        if (currentBitDepth != 16) return
        var writer = aecRefWriter
        val formatChanged = writer == null || aecRefRate != currentSampleRate || aecRefChannels != currentChannels
        val speed = currentPlaybackSpeed
        // Fold speed into the writer's source rate so the reference does not drift
        // against the mic timeline (up to 15ms/s uncompensated, which exhausts the AEC
        // filter tail within seconds of continuous correction).
        val effectiveRate = (currentSampleRate * speed).toInt().coerceAtLeast(4000)
        if (formatChanged) {
            aecRefRate = currentSampleRate
            aecRefChannels = currentChannels
            aecRefSpeed = speed
            aecRefWriterCreatedAtNs = System.nanoTime()
            writer = com.example.ava.audio.PlaybackReferenceBus.createWriter(effectiveRate, currentChannels)
            aecRefWriter = writer
        } else if (kotlin.math.abs(speed - aecRefSpeed) > 0.001f) {
            // Speed-only change: retune the resampler in place. Recreating the writer
            // here re-anchored the reference at readPos + bulkDelay, dropping the
            // accumulated writer-ahead offset — a delay jump on every sync
            // micro-correction (as often as 200 ms apart during stream startup) that
            // forced AEC3 to re-converge while echo leaked. In-place retune keeps the
            // timeline continuous, so no rate limit is needed anymore.
            aecRefSpeed = speed
            writer?.updateSourceRate(effectiveRate)
        }
        writer?.write(pcm16, offset, length)
    }

    private fun markTrackDeadAndRelease(t: AudioTrack, reason: String) {
        Log.w(tag, "Marking AudioTrack dead ($reason), forcing recreate")
        started.set(false)

        synchronized(lock) {
            if (track === t) {
                track = null
                resetLatencyEstimatorLocked()
            }
        }

        try { t.pause() } catch (_: Exception) {}
        try { t.flush() } catch (_: Exception) {}
        try { t.stop() } catch (_: Exception) {}
        try { t.release() } catch (_: Exception) {}
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!started.get()) return
        // Variable rate is Android 8+. API 23–25 accept PlaybackParams and then
        // disable the track on this class of HAL. Those builds stay at 1.0x.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        // Clamp speed to valid range (0.5x to 2.0x typical for AudioTrack)
        val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
        
        // Skip if speed hasn't changed (avoid redundant calls)
        if (kotlin.math.abs(clampedSpeed - currentPlaybackSpeed) < 0.0001f) {
            return
        }
        
        synchronized(lock) {
            val t = track ?: return
            if (!started.get()) return
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                return
            }
            
            // On low-end devices, playState might briefly not be PLAYING even though track is active.
            // Check both PLAYING and PAUSED to handle edge cases.
            val isPlayable = t.playState == AudioTrack.PLAYSTATE_PLAYING || 
                            t.playState == AudioTrack.PLAYSTATE_PAUSED
            if (!isPlayable) {
                return
            }

            try {
                // PlaybackParams requires API 23+
                val params = PlaybackParams().setSpeed(clampedSpeed)
                t.playbackParams = params
                currentPlaybackSpeed = clampedSpeed
            } catch (e: UnsupportedOperationException) {
            } catch (e: Exception) {
                Log.w(tag, "Failed to set playback speed to $clampedSpeed: ${e.message}")
            }
        }
    }

    /**
     * Get the current playback speed (1.0 = normal speed).
     */
    fun getCurrentPlaybackSpeed(): Float = currentPlaybackSpeed

    /**
     * Get the smoothed latency in milliseconds.
     */
    fun getSmoothedLatencyMs(): Double = smoothedLatencyUs / 1000.0

    fun getConfiguredStaticDelayUs(): Long {
        val bytesPerFrame = currentChannels * (currentBitDepth / 8)
        if (currentSampleRate <= 0 || bytesPerFrame <= 0 || lastConfiguredBufferBytes <= 0) {
            return 0L
        }
        return (lastConfiguredBufferBytes.toLong() * 1_000_000L) /
            (currentSampleRate.toLong() * bytesPerFrame.toLong())
    }

    /**
     * Path after [AudioTrack.getTimestamp] that still remains before the sample
     * leaves the audio port. sendspin-js subtracts this as `outputLatency`;
     * Ava's DAC lock cannot see it because pipeline and "audible now" were
     * both derived from the same timestamp.
     *
     * Zero when the OS report matches the live write→DAC lead (built-in
     * speaker). Bluetooth / extra HAL often reports 200–400ms more.
     */
    fun getPostPresentationLatencyUs(): Long {
        refreshReportedOutputLatency()
        val reported = smoothedReportedOutputLatencyUs
        if (reported <= 0L) return 0L
        val toDacUs = liveWriteToDacUs()
        val postUs = (reported - toDacUs).coerceIn(0L, MAX_PIPELINE_LATENCY_US)
        if (kotlin.math.abs(postUs - lastLoggedPostPresentationUs) >= 40_000L) {
            lastLoggedPostPresentationUs = postUs
            Log.i(
                tag,
                "post-presentation ${postUs / 1000L}ms " +
                    "(os ${reported / 1000L}ms − dac ${toDacUs / 1000L}ms)",
            )
        }
        return postUs
    }

    private fun liveWriteToDacUs(): Long {
        val rate = currentSampleRate
        val lead = lastHeadToDacLeadFrames
        if (rate > 0 && lead > 0L) {
            return (lead * 1_000_000L / rate.toLong())
                .coerceIn(0L, MAX_PIPELINE_LATENCY_US)
        }
        return getSchedulingPipelineLatencyUs()
    }

    private fun refreshReportedOutputLatency() {
        val fromTrack = track?.let { trackReportedLatencyUs(it) } ?: 0L
        val fromMixer = mixerReportedLatencyUs()
        val raw = max(fromTrack, fromMixer)
        if (raw <= 0L) return
        val clamped = raw.coerceIn(20_000L, MAX_PIPELINE_LATENCY_US)
        smoothedReportedOutputLatencyUs = if (smoothedReportedOutputLatencyUs == 0L) {
            clamped
        } else {
            (smoothedReportedOutputLatencyUs * 7L + clamped * 3L) / 10L
        }
    }

    private fun trackReportedLatencyUs(trackRef: AudioTrack): Long {
        if (getLatencyUnsupported) return 0L
        return try {
            val method = getLatencyMethod ?: AudioTrack::class.java.getMethod("getLatency").also {
                getLatencyMethod = it
            }
            val ms = method.invoke(trackRef) as? Int ?: 0
            if (ms > 0) ms * 1000L else 0L
        } catch (_: Exception) {
            getLatencyUnsupported = true
            0L
        }
    }

    private fun mixerReportedLatencyUs(): Long {
        val am = audioManager ?: return 0L
        if (getOutputLatencyUnsupported) return 0L
        return try {
            val method = getOutputLatencyMethod ?: AudioManager::class.java
                .getMethod("getOutputLatency", Int::class.javaPrimitiveType)
                .also { getOutputLatencyMethod = it }
            val ms = method.invoke(am, AudioManager.STREAM_MUSIC) as? Int ?: 0
            if (ms > 0) ms * 1000L else 0L
        } catch (_: Exception) {
            getOutputLatencyUnsupported = true
            0L
        }
    }

    /**
     * Estimated pipeline latency from AudioTrack write to speaker output (microseconds).
     * Uses dynamic queue depth (written frames - played frames) with smoothing,
     * and never goes below HAL minimum-buffer latency floor.
     */
    /**
     * Latency estimate safe for playout scheduling. Uses DAC timestamps only when
     * stable; otherwise falls back to configured AudioTrack buffer size.
     */
    fun getSchedulingPipelineLatencyUs(): Long {
        if (lowMemoryMode || !dacTimestampsStable) {
            stableLatencyCacheUs[latencyCacheKey()]?.let { cached ->
                return cached.coerceIn(40_000L, MAX_PIPELINE_LATENCY_US)
            }
            val floor = if (lastMinBufBytes > 0 && currentSampleRate > 0) {
                val bpf = currentChannels * (currentBitDepth / 8)
                if (bpf > 0) (lastMinBufBytes.toLong() * 1_000_000L) / (currentSampleRate.toLong() * bpf) else 80_000L
            } else {
                80_000L
            }
            return (floor + 20_000L).coerceIn(40_000L, MAX_PIPELINE_LATENCY_US)
        }
        return getEstimatedPipelineLatencyUs()
    }

    private fun markDacTimestampGlitch(reason: String) {
        dacTimestampsStable = false
        consecutiveValidTimestamps = 0
        cachedDacPlayedFrames = null
        dacGlitchCooldownUntilNs = System.nanoTime() + DAC_GLITCH_COOLDOWN_NS
        Log.d(tag, "DAC timestamp glitch ($reason); using playbackHeadPosition for ${DAC_GLITCH_COOLDOWN_NS / 1_000_000L}ms")
    }

    fun getEstimatedPipelineLatencyUs(): Long {
        val bytesPerFrame = currentChannels * (currentBitDepth / 8)
        val floorUs = if (lastMinBufBytes > 0 && currentSampleRate > 0 && bytesPerFrame > 0) {
            (lastMinBufBytes.toLong() * 1_000_000L) / (currentSampleRate.toLong() * bytesPerFrame)
        } else {
            40_000L
        }

        val t = track
        if (!started.get() || t == null || currentSampleRate <= 0) {
            return floorUs
        }

        synchronized(lock) {
            val trackRef = track ?: return floorUs
            if (trackRef.state != AudioTrack.STATE_INITIALIZED) return floorUs

            // Precision path: the hardware DAC presentation timestamp gives the
            // true number of frames actually rendered (including downstream HAL/DAC
            // latency), extrapolated to "now". playbackHeadPosition only counts
            // frames handed to the mixer, so it overestimates progress and
            // underestimates remaining latency — the root of the sync drift.
            // Fall back to playbackHeadPosition only until DAC timestamps are stable.
            val playedFrames = dacPlayedFramesLocked(trackRef) ?: playbackHeadFramesLocked(trackRef)
            val writtenFrames = totalFramesWritten.get()
            val queuedFrames = (writtenFrames - playedFrames).coerceAtLeast(0L)

            val dynamicUs = (queuedFrames * 1_000_000L) / currentSampleRate.toLong()
            val combinedUs = max(dynamicUs, floorUs)

            smoothedLatencyUs = if (smoothedLatencyUs == 0L) {
                combinedUs
            } else {
                // 70/30 IIR smoothing to avoid jittery control decisions
                ((smoothedLatencyUs * 7L) + (combinedUs * 3L)) / 10L
            }

            if (dacTimestampsStable && smoothedLatencyUs > 0L) {
                stableLatencyCacheUs[latencyCacheKey()] = smoothedLatencyUs
            }

            return smoothedLatencyUs.coerceIn(20_000L, MAX_PIPELINE_LATENCY_US)
        }
    }

    /** Mixer-handed frames with 32-bit wrap tracking — DAC-timestamp fallback. Hold [lock]. */
    private fun playbackHeadFramesLocked(trackRef: AudioTrack): Long {
        val raw = trackRef.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        if (raw < playbackHeadRaw) {
            playbackHeadWraps++
        }
        playbackHeadRaw = raw
        return raw + (playbackHeadWraps shl 32)
    }

    /**
     * Frame-domain playback position for the LAN peer playback beacon.
     *
     * [playedFrames] is the stream frame index at the DAC "now" (extrapolated);
     * [dacTrusted] is false while on the playbackHeadPosition fallback; that
     * path is corrected by the last DAC-confirmed head→DAC lead
     * ([lastHeadToDacLeadFrames]) but is still only an estimate.
     */
    data class PlaybackPositionSnapshot(
        val writtenFrames: Long,
        val playedFrames: Long,
        val dacTrusted: Boolean,
        val sampleRate: Int,
        val generation: Long,
    )

    fun getPlaybackPositionSnapshot(): PlaybackPositionSnapshot? {
        if (!started.get()) return null
        synchronized(lock) {
            val trackRef = track ?: return null
            if (trackRef.state != AudioTrack.STATE_INITIALIZED) return null
            if (currentSampleRate <= 0) return null
            val dacFrames = dacPlayedFramesLocked(trackRef)
            val headFrames = playbackHeadFramesLocked(trackRef)
            val playedFrames = if (dacFrames != null) {
                // Mixer position minus DAC position is the downstream latency.
                // Remember it for the fallback; clamp so a DAC extrapolation
                // running past the mixer cannot store a negative lead.
                lastHeadToDacLeadFrames = (headFrames - dacFrames)
                    .coerceIn(0L, currentSampleRate.toLong() * 2L)
                dacFrames
            } else {
                // Untrusted window: head leads the speaker by the HAL latency.
                // Subtracting the learned lead keeps the presentation clock (and
                // the lyrics riding it) on the speaker instead of a few hundred
                // ms early, which the monotonic floor then had to freeze off.
                (headFrames - lastHeadToDacLeadFrames).coerceAtLeast(0L)
            }
            return PlaybackPositionSnapshot(
                writtenFrames = totalFramesWritten.get(),
                playedFrames = playedFrames,
                dacTrusted = dacFrames != null,
                sampleRate = currentSampleRate,
                generation = trackGeneration.get(),
            )
        }
    }

    /** (totalFramesWritten, trackGeneration) — capture immediately before a chunk write. */
    fun writtenFramesAndGeneration(): Pair<Long, Long> =
        totalFramesWritten.get() to trackGeneration.get()

    /**
     * Hardware DAC frames played, extrapolated to "now" (System.nanoTime()).
     *
     * Uses [AudioTrack.getTimestamp], which reports the frame that was actually
     * presented at the DAC together with the precise nanoTime it was presented —
     * the authoritative clock for precision sync. Returns null until we have seen
     * [TIMESTAMP_STABLE_READS] consecutive valid readings (warm-up) or when the
     * timestamp is unavailable, so the caller falls back to playbackHeadPosition.
     *
     * Must be called while holding [lock].
     */
    /**
     * [AudioTrack.getTimestamp] exists earlier, but the DAC presentation clock
     * this sync math compares against [System.nanoTime] is reliable from
     * Android 8. On API 25 the same call comes back "valid", the scheduler
     * drops chunks as late, and AudioFlinger then disables the track
     * (`releaseBuffer() track disabled due to previous underrun`).
     */
    private fun dacTimestampSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private fun dacPlayedFramesLocked(trackRef: AudioTrack): Long? {
        if (lowMemoryMode || !dacTimestampSupported()) return null

        val nowNs = System.nanoTime()
        if (nowNs < dacGlitchCooldownUntilNs) return null

        if (trackRef.playState != AudioTrack.PLAYSTATE_PLAYING) {
            consecutiveValidTimestamps = 0
            dacTimestampsStable = false
            cachedDacPlayedFrames = null
            return null
        }

        val effectiveRate = (currentSampleRate * currentPlaybackSpeed).toLong().coerceAtLeast(1L)

        // Reuse last good reading between queries — calling getTimestamp every playout
        // loop tick provokes HAL stall/retrograde corrections on some devices.
        if (dacTimestampsStable && cachedDacPlayedFrames != null &&
            nowNs - lastTimestampQueryNs < TIMESTAMP_QUERY_INTERVAL_NS
        ) {
            val elapsedNs = (nowNs - lastTimestampQueryNs).coerceAtMost(250_000_000L)
            val extraFrames = elapsedNs * effectiveRate / 1_000_000_000L
            return cachedDacPlayedFrames!! + extraFrames
        }

        val ok = try {
            trackRef.getTimestamp(audioTimestamp)
        } catch (_: Exception) {
            false
        }
        if (!ok || audioTimestamp.framePosition <= 0) {
            consecutiveValidTimestamps = 0
            return null
        }

        if (lastDacFramePosition > 0 &&
            audioTimestamp.framePosition + 64 < lastDacFramePosition
        ) {
            markDacTimestampGlitch("retrograde ${audioTimestamp.framePosition} < $lastDacFramePosition")
            return null
        }

        consecutiveValidTimestamps++
        if (consecutiveValidTimestamps >= TIMESTAMP_STABLE_READS) {
            dacTimestampsStable = true
        }
        lastDacFramePosition = audioTimestamp.framePosition
        lastDacTimeUs = audioTimestamp.nanoTime / 1000
        if (!dacTimestampsStable) return null

        val elapsedNs = (System.nanoTime() - audioTimestamp.nanoTime)
            .coerceIn(0L, 250_000_000L)
        val extraFrames = elapsedNs * effectiveRate / 1_000_000_000L
        val playedFrames = audioTimestamp.framePosition + extraFrames
        lastTimestampQueryNs = nowNs
        cachedDacPlayedFrames = playedFrames
        return playedFrames
    }

    /**
     * Get DAC timestamp for precision sync (AirPlay 2 style).
     * 
     * Returns the actual time when audio was/will be played by the DAC,
     * which is more accurate than playbackHeadPosition for sync purposes.
     * 
     * @return Pair of (framePosition, nanoTime) or null if not available
     */
    fun getDacTimestamp(): Pair<Long, Long>? {
        if (!dacTimestampSupported()) return null
        val t = track ?: return null
        if (!started.get()) return null
        
        synchronized(lock) {
            val trackRef = track ?: return null
            if (trackRef.state != AudioTrack.STATE_INITIALIZED) return null
            if (trackRef.playState != AudioTrack.PLAYSTATE_PLAYING) return null
            
            try {
                if (trackRef.getTimestamp(audioTimestamp)) {
                    if (audioTimestamp.framePosition > 0) {
                        consecutiveValidTimestamps++
                        if (consecutiveValidTimestamps >= TIMESTAMP_STABLE_READS && !dacTimestampsStable) {
                            dacTimestampsStable = true
                        }
                        lastDacFramePosition = audioTimestamp.framePosition
                        lastDacTimeUs = audioTimestamp.nanoTime / 1000
                        return Pair(audioTimestamp.framePosition, audioTimestamp.nanoTime)
                    } else {
                        consecutiveValidTimestamps = 0
                    }
                } else {
                    consecutiveValidTimestamps = 0
                }
            } catch (e: Exception) {
                consecutiveValidTimestamps = 0
            }
            return null
        }
    }
    
    /**
     * Check if DAC timestamps are stable and reliable.
     */
    fun isDacTimestampsStable(): Boolean = dacTimestampsStable
    
    /**
     * Get the sync error in microseconds based on DAC timestamp.
     * 
     * This calculates how far off we are from the expected playback position.
     * Positive = we're ahead (DAC has played more than expected)
     * Negative = we're behind (DAC has played less than expected)
     * 
     * @param expectedFramePosition Expected frame position at current time
     * @return Sync error in microseconds, or null if DAC timestamp unavailable
     */
    fun getSyncErrorUs(expectedFramePosition: Long): Long? {
        val dacTs = getDacTimestamp() ?: return null
        val dacFramePosition = dacTs.first
        
        // Calculate frame difference
        val frameDiff = dacFramePosition - expectedFramePosition
        
        // Convert to microseconds
        return if (currentSampleRate > 0) {
            (frameDiff * 1_000_000L) / currentSampleRate.toLong()
        } else {
            null
        }
    }

    fun writeSilenceMs(ms: Int): Boolean {
        if (ms <= 0 || !started.get()) return false
        val bytesPerFrame = currentChannels * (currentBitDepth / 8)
        if (currentSampleRate <= 0 || bytesPerFrame <= 0) return false

        writingCount.incrementAndGet()
        try {
            if (!started.get()) return false
            val t = track ?: return false
            if (t.state == AudioTrack.STATE_UNINITIALIZED) return false
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    t.play()
                } catch (_: Exception) {
                    return false
                }
            }

            val totalBytes = ((currentSampleRate.toLong() * ms * bytesPerFrame) / 1000L)
                .coerceAtLeast(bytesPerFrame.toLong())
                .toInt()
            val buf = ByteArray(min(totalBytes, 8192).coerceAtLeast(bytesPerFrame))
            var written = 0
            val deadline = System.currentTimeMillis() + 40L
            while (written < totalBytes && started.get() && System.currentTimeMillis() < deadline) {
                val toWrite = min(totalBytes - written, buf.size)
                val n = t.write(buf, 0, toWrite, AudioTrack.WRITE_NON_BLOCKING)
                when {
                    n > 0 -> {
                        written += n
                        totalFramesWritten.addAndGet((n / bytesPerFrame).toLong())
                        // Silence occupies the physical playback timeline too. Skipping it
                        // would leave the AEC reference cursor behind: every gap fill would
                        // shift the reference early by [ms], accumulating across the stream.
                        feedAecReference(buf, 0, n)
                    }
                    n == 0 -> {
                        Thread.yield()
                        Thread.sleep(1)
                    }
                    else -> return written > 0
                }
            }
            return written > 0
        } catch (e: Exception) {
            Log.w(tag, "writeSilenceMs failed", e)
            return false
        } finally {
            writingCount.decrementAndGet()
        }
    }

    /** @deprecated Use [writeSilenceMs] */
    fun flushSilence(ms: Int) {
        writeSilenceMs(ms)
    }

    fun setVolume(vol: Float) {
        targetVolume = vol.coerceIn(0f, 1f)
        if (softStartBeganAtMs != 0L) return
        val t = track ?: return
        try { t.setVolume(targetVolume) } catch (_: Exception) {}
    }

    /**
     * Crossfade over the first ~5ms of 16-bit LE output to remove splice clicks.
     * Ramps from the held last-written sample (per channel) into the new signal, so both
     * the step away from the old waveform and the step into the new one are smoothed.
     */
    private fun applySpliceCrossfade16(pcm: ByteArray) {
        val totalSamples = pcm.size / 2
        if (totalSamples == 0) return
        val channels = currentChannels.coerceAtLeast(1)
        val held = lastWrittenFrame
        val fadeSamples = ((currentSampleRate.toLong() * channels * 5L) / 1000L)
            .toInt()
            .coerceIn(1, totalSamples)
        for (i in 0 until fadeSamples) {
            val idx = i * 2
            val lo = pcm[idx].toInt() and 0xFF
            val hi = pcm[idx + 1].toInt()
            val sample = (hi shl 8) or lo
            val heldSample = if (held.size == channels) held[i % channels] else 0
            val mixed = (heldSample * (fadeSamples - i) + sample * i) / fadeSamples
            val clamped = mixed.coerceIn(-32768, 32767)
            pcm[idx] = (clamped and 0xFF).toByte()
            pcm[idx + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
    }

    /** Remember the final frame of each write as the crossfade origin for a future splice. */
    private fun captureLastFrame16(pcm: ByteArray) {
        val channels = currentChannels.coerceAtLeast(1)
        val frameBytes = channels * 2
        if (pcm.size < frameBytes) return
        if (lastWrittenFrame.size != channels) lastWrittenFrame = IntArray(channels)
        val base = pcm.size - frameBytes
        for (ch in 0 until channels) {
            val idx = base + ch * 2
            val lo = pcm[idx].toInt() and 0xFF
            val hi = pcm[idx + 1].toInt()
            lastWrittenFrame[ch] = (hi shl 8) or lo
        }
    }

    /** After flush/stop the line is silent; splices must ramp from zero. */
    private fun clearLastFrameHold() {
        lastWrittenFrame = IntArray(0)
    }

    private fun beginSoftStartLocked(t: AudioTrack) {
        try { t.setVolume(0f) } catch (_: Exception) {}
        softStartBeganAtMs = SystemClock.elapsedRealtime()
    }

    private fun updateSoftStartGain() {
        val began = softStartBeganAtMs
        if (began == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - began
        val ramp = if (elapsed >= SOFT_START_RAMP_MS) 1f else elapsed.toFloat() / SOFT_START_RAMP_MS
        val t = track ?: return
        try { t.setVolume(targetVolume * ramp) } catch (_: Exception) {}
        if (ramp >= 1f) softStartBeganAtMs = 0L
    }

    fun stop() {
        started.set(false)
        synchronized(lock) {
            val t = track
            stopInternal(t)
        }
    }

    private fun stopInternal(t: AudioTrack?) {
        track = null // Clear reference so new writes fail fast
        resetLatencyEstimatorLocked()
        if (t != null) {
            try {
                if (t.state == AudioTrack.STATE_INITIALIZED) {
                    // Pause first to unblock any writers blocked in native write()
                    try { t.pause() } catch (_: Exception) {}
                    try { t.flush() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(tag, "Error accessing AudioTrack state during stop", e)
            }
            
            // Wait for active writers to exit to prevent SIGABRT on release
            var attempts = 50 // 500ms max wait
            while (writingCount.get() > 0 && attempts > 0) {
                try { Thread.sleep(10) } catch (_: Exception) {}
                attempts--
            }
            
            if (writingCount.get() > 0) {
                Log.e(tag, "WARNING: Releasing AudioTrack with ${writingCount.get()} active writers. Crash likely.")
            }

            try {
                t.stop()
            } catch (_: Exception) {}
            
            try {
                t.release()
            } catch (e: Exception) {
                Log.w(tag, "Error releasing AudioTrack", e)
            }
        }
    }

    private fun resetLatencyEstimatorLocked() {
        totalFramesWritten.set(0L)
        trackGeneration.incrementAndGet()
        playbackHeadRaw = 0L
        playbackHeadWraps = 0L
        smoothedLatencyUs = 0L
        currentPlaybackSpeed = 1.0f
        softStartBeganAtMs = 0L
        clearLastFrameHold()
        // Every caller of this reset just flushed/recreated the AudioTrack, i.e. samples
        // already fed to the AEC reference were discarded before reaching the speaker.
        // Drop the writer so the next write re-anchors on the shared timeline instead of
        // carrying that stale offset for the rest of the stream.
        aecRefWriter = null
        aecRefWriterCreatedAtNs = 0L
        // AudioTrack.flush()/play() restarts the DAC frame counter, so the prior
        // timestamp is stale. Force the precision path to re-warm.
        dacTimestampsStable = false
        consecutiveValidTimestamps = 0
        lastDacFramePosition = 0L
        lastDacTimeUs = 0L
        cachedDacPlayedFrames = null
        lastTimestampQueryNs = 0L
    }

    fun checkAudioCapabilities(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val pm = context.packageManager
        val hasLowLatency = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        val hasPro = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)
        val optimalFramesStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        val optimalFrames = optimalFramesStr?.toIntOrNull() ?: 256
        val optimalRateStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val optimalRate = optimalRateStr?.toIntOrNull() ?: 48000
        val deviceOptimalBuffer = optimalRate / optimalFrames
        // For now we won't do anything with this, but we can explore adjusting the current HAL buffer target based on the deviceOptimalBuffer
        // This could look like deviceOptimalBuffer*bufferSizing
        // Buffer sizing might need to be dynamic depending on the spec of the device
        // Will put us more at risk of underruns, chunk drops and recovery events via audibleSyncs
        // The app will also be more sensitive to jitter, which can happen at anypoint on the audio pipeline
        // Benefits to this would be a lower base output latency which will be good for responsiveness
    }
}
