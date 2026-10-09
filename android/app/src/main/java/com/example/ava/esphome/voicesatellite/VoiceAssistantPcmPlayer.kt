package com.example.ava.esphome.voicesatellite

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.example.ava.audio.AudioEnergy
import com.example.ava.audio.PlaybackEnergyMonitor
import com.example.ava.audio.PlaybackReferenceBus
import com.example.ava.voice.AvaVoiceAudioConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays ESPHome SPEAKER-mode TTS: 16 kHz 16-bit mono PCM from [VoiceAssistantAudio] chunks.
 */
class VoiceAssistantPcmPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Guards every native use of [audioTrack] against [stopInternal] releasing it.
     * Cancelling [writerJob] does not wait for the writer to leave `AudioTrack.write`,
     * so releasing right after cancel can free the native track mid-write and abort
     * the process. Held only around individual native calls, never across a suspend.
     */
    private val trackLock = Any()
    private var audioTrack: AudioTrack? = null
    private var writerJob: Job? = null
    private var drainJob: Job? = null
    private val active = AtomicBoolean(false)
    private val streamEnded = AtomicBoolean(false)
    private val frameQueue = LinkedBlockingQueue<ByteArray>()
    private val bytesSubmitted = AtomicLong(0)
    /** [PlaybackEnergyMonitor] generation this stream feeds; a stopped stream's late writes are dropped. */
    @Volatile
    private var energyGen = -1

    /** Far-end reference writer for software AEC; null until a PCM stream starts. */
    private var aecRefWriter: PlaybackReferenceBus.Writer? = null

    @Volatile
    var volume: Float = 1f
        set(value) {
            val gain = value.coerceIn(0f, 1f)
            field = gain
            synchronized(trackLock) { runCatching { audioTrack?.setVolume(gain) } }
        }

    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackComplete: (() -> Unit)? = null

    fun isActive(): Boolean = active.get()

    /** Queued PCM frames waiting for AudioTrack (diagnostic). */
    fun queueDepth(): Int = frameQueue.size

    /** Cumulative bytes written to AudioTrack this stream (diagnostic). */
    fun bytesSubmitted(): Long = bytesSubmitted.get()

    fun start(): Boolean {
        stopInternal(fireComplete = false)
        val minBuffer = AudioTrack.getMinBufferSize(
            AvaVoiceAudioConfig.SAMPLE_RATE,
            AvaVoiceAudioConfig.PLAYBACK_CHANNEL,
            AvaVoiceAudioConfig.AUDIO_FORMAT,
        ).coerceAtLeast(AvaVoiceAudioConfig.FRAME_BYTES * 12)
        val track = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            // Default USAGE_MEDIA unless a router mod splits TTS (ModAudioRouter).
                            .setUsage(com.example.ava.mods.ModAudioRouter.cachedTtsAudioUsage())
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(AvaVoiceAudioConfig.SAMPLE_RATE)
                            .setEncoding(AvaVoiceAudioConfig.AUDIO_FORMAT)
                            .setChannelMask(AvaVoiceAudioConfig.PLAYBACK_CHANNEL)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBuffer)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC,
                    AvaVoiceAudioConfig.SAMPLE_RATE,
                    AvaVoiceAudioConfig.PLAYBACK_CHANNEL,
                    AvaVoiceAudioConfig.AUDIO_FORMAT,
                    minBuffer,
                    AudioTrack.MODE_STREAM,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack init failed: ${e.message}")
            null
        } ?: return false

        audioTrack = track
        streamEnded.set(false)
        bytesSubmitted.set(0)
        // Feed software AEC the far-end reference so streaming TTS played via AudioTrack
        // (not ExoPlayer) is still echo-cancelled from the mic. Channel mask is mono.
        aecRefWriter = PlaybackReferenceBus.createWriter(AvaVoiceAudioConfig.SAMPLE_RATE, 1)
        active.set(true)
        try {
            track.play()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack play failed: ${e.message}")
            stopInternal(fireComplete = false)
            return false
        }
        runCatching { track.setVolume(volume) }
        onPlaybackStarted?.invoke()
        // onPlaybackStarted enables the monitor (new generation); this stream owns that one.
        energyGen = PlaybackEnergyMonitor.generation()
        writerJob = scope.launch { writeLoop() }
        return true
    }

    fun write(data: ByteArray) {
        if (!active.get() || data.isEmpty()) return
        while (frameQueue.size >= MAX_QUEUE_FRAMES) {
            frameQueue.poll()
        }
        frameQueue.offer(data)
    }

    private fun scalePcm16Le(data: ByteArray, gain: Float): ByteArray {
        if (gain >= 0.999f) return data
        val out = data.copyOf()
        var i = 0
        while (i < out.size - 1) {
            val lo = out[i].toInt() and 0xFF
            val hi = out[i + 1].toInt()
            var sample = lo or (hi shl 8)
            if (sample > 32767) sample -= 65536
            sample = (sample * gain).toInt().coerceIn(-32768, 32767)
            out[i] = (sample and 0xFF).toByte()
            out[i + 1] = ((sample shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    fun markStreamEnded() {
        if (!active.get()) return
        streamEnded.set(true)
        drainJob?.cancel()
        drainJob = scope.launch {
            val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                if (frameQueue.isEmpty() && isPlaybackTailDrained()) {
                    stopInternal(fireComplete = true)
                    return@launch
                }
                delay(20)
            }
            stopInternal(fireComplete = true)
        }
    }

    fun stop() {
        stopInternal(fireComplete = false)
    }

    private suspend fun writeLoop() {
        while (scope.isActive && active.get()) {
            val chunk = frameQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
            val track = audioTrack ?: continue
            try {
                var offset = 0
                while (offset < chunk.size) {
                    // Re-check identity under the lock: stopInternal nulls the field and
                    // releases while holding it, so a surviving local ref is stale there.
                    var headBytes = -1L
                    val written = synchronized(trackLock) {
                        if (!active.get() || audioTrack !== track) {
                            -1
                        } else {
                            track.write(chunk, offset, chunk.size - offset).also {
                                // Head after the write: how much of the queue is still unheard.
                                if (it > 0) headBytes = playbackHeadBytes(track)
                            }
                        }
                    }
                    if (written <= 0) break
                    val farEnd = scalePcm16Le(chunk, volume)
                    feedPlaybackEnergy(farEnd, offset, written, bytesSubmitted.get(), headBytes)
                    feedAecReference(farEnd, offset, written)
                    offset += written
                    bytesSubmitted.addAndGet(written.toLong())
                }
            } catch (e: Exception) {
                Log.w(TAG, "PCM write failed: ${e.message}")
                break
            }
        }
    }

    /**
     * Same scale as ExoPlayer [com.example.ava.audio.PlaybackEnergyTee]. The level is
     * published for the moment the playback head reaches [chunkStartBytes], so the
     * visuals follow what is heard, not what was written. Only this stream's
     * generation is accepted (a write racing a stop does not leak into the next turn).
     */
    private fun feedPlaybackEnergy(
        chunk: ByteArray,
        offset: Int,
        length: Int,
        chunkStartBytes: Long,
        headBytes: Long,
    ) {
        if (length < 2) return
        val gen = energyGen
        if (!PlaybackEnergyMonitor.accepts(gen)) return
        val buffer = ByteBuffer.wrap(chunk, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        val level = AudioEnergy.rmsLevelPlayback(buffer)
        val bytesPerSecond = AvaVoiceAudioConfig.SAMPLE_RATE * bytesPerPlaybackFrame()
        val audibleInMs = PlaybackEnergyMonitor.pcmAudibleInMs(chunkStartBytes, headBytes, bytesPerSecond)
        val durationMs = if (bytesPerSecond > 0) length * 1000L / bytesPerSecond else 0L
        PlaybackEnergyMonitor.onLevel(level, audibleInMs, durationMs, gen)
        com.example.ava.services.QuickWakeFabService.feedAudioLevel(level)
    }

    /** Playback head in bytes (the frame counter is unsigned 32-bit); -1 when unreadable. */
    private fun playbackHeadBytes(track: AudioTrack): Long = try {
        (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) * bytesPerPlaybackFrame()
    } catch (_: Exception) {
        -1L
    }

    /**
     * Software AEC far-end tap. Samples are scaled by [volume] (same gain as
     * [AudioTrack.setVolume]) so the reference matches AudioTrack output.
     * ExoPlayer's [com.example.ava.audio.PlaybackReferenceTee] is the URL-TTS
     * counterpart; streaming TTS must not leak into the mic.
     *
     * Always invokes [PlaybackReferenceBus.Writer.write] so diagnostic far-end signal
     * updates even when soft AEC is off; the writer itself no-ops ring work when `!active`.
     */
    private fun feedAecReference(chunk: ByteArray, offset: Int, length: Int) {
        if (length < 2) return
        aecRefWriter?.write(chunk, offset, length)
    }

    private fun isPlaybackTailDrained(): Boolean {
        if (!frameQueue.isEmpty()) return false
        if (!streamEnded.get()) return false
        return try {
            val submitted = bytesSubmitted.get()
            if (submitted <= 0L) return true
            val headBytes = synchronized(trackLock) {
                val track = audioTrack ?: return true
                track.playbackHeadPosition.toLong() * bytesPerPlaybackFrame()
            }
            headBytes + PLAYBACK_DRAIN_TOLERANCE_BYTES >= submitted
        } catch (_: Exception) {
            true
        }
    }

    private fun bytesPerPlaybackFrame(): Int =
        if (AvaVoiceAudioConfig.PLAYBACK_CHANNEL == AudioFormat.CHANNEL_OUT_MONO) 2 else 4

    private fun stopInternal(fireComplete: Boolean) {
        active.set(false)
        energyGen = -1
        streamEnded.set(false)
        drainJob?.cancel()
        drainJob = null
        writerJob?.cancel()
        writerJob = null
        aecRefWriter = null
        frameQueue.clear()
        val track = audioTrack
        // stop() is safe to call concurrently with write() and makes a blocked write()
        // return, which lets the writer leave the trackLock section below promptly.
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        synchronized(trackLock) {
            audioTrack = null
            try {
                track?.release()
            } catch (_: Exception) {
            }
        }
        if (fireComplete) {
            onPlaybackComplete?.invoke()
        }
    }

    companion object {
        private const val TAG = "VoiceAssistantPcmPlayer"
        private const val POLL_TIMEOUT_MS = 10L
        private const val DRAIN_TIMEOUT_MS = 15_000L
        private const val MAX_QUEUE_FRAMES = 256
        private const val PLAYBACK_DRAIN_TOLERANCE_BYTES = 960L // ~30ms at 16 kHz mono
    }
}
