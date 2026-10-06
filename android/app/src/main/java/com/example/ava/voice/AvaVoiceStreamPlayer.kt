package com.example.ava.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.example.ava.audio.PlaybackReferenceBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PCM playback for voice messages and live calls — same [USAGE_MEDIA] / [STREAM_MUSIC] path as
 * buffered message playback (not [USAGE_VOICE_COMMUNICATION], which couples to call-mode routing).
 */
internal class AvaVoiceStreamPlayer(
    private val sessionId: Int,
    sampleRate: Int,
    private val playbackGain: Float = AvaVoiceAudioConfig.MESSAGE_PLAYBACK_GAIN
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)

    /**
     * Guards every native use of [audioTrack] against [release]. Cancelling [writerJob]
     * does not wait for the writer to leave `AudioTrack.write`, so releasing right after
     * cancel can free the native track mid-write and abort the process.
     */
    private val trackLock = Any()
    private var audioTrack: AudioTrack? = null
    private val bytesQueued = AtomicLong(0)
    private val bytesWritten = AtomicLong(0)
    private val expectedBytes = AtomicLong(0)
    private val ended = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private var progressJob: Job? = null
    private var writerJob: Job? = null
    private val sampleRateHz = sampleRate

    private val frameQueue = LinkedBlockingQueue<ByteArray>()
    private val queuedBytes = AtomicLong(0)

    /** Far-end reference writer for software AEC; null until [start]. */
    private var aecRefWriter: PlaybackReferenceBus.Writer? = null
    private val busHeld = AtomicBoolean(false)

    fun start(): Boolean {
        if (released.get()) return false
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AvaVoiceAudioConfig.PLAYBACK_CHANNEL,
            AvaVoiceAudioConfig.AUDIO_FORMAT
        ).coerceAtLeast(AvaVoiceAudioConfig.FRAME_BYTES * 12)
        val track = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRateHz)
                            .setEncoding(AvaVoiceAudioConfig.AUDIO_FORMAT)
                            .setChannelMask(AvaVoiceAudioConfig.PLAYBACK_CHANNEL)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBuffer)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC,
                    sampleRateHz,
                    AvaVoiceAudioConfig.PLAYBACK_CHANNEL,
                    AvaVoiceAudioConfig.AUDIO_FORMAT,
                    minBuffer,
                    AudioTrack.MODE_STREAM
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack init failed session=$sessionId: ${e.message}")
            null
        } ?: return false
        audioTrack = track
        // Hold the far-end bus for the life of this track so call AEC still receives
        // reference after the satellite mic pauses and drops its own hold.
        aecRefWriter = PlaybackReferenceBus.createWriter(sampleRateHz, 1)
        if (busHeld.compareAndSet(false, true)) {
            PlaybackReferenceBus.acquire()
        }
        try {
            track.setVolume(1f)
            track.play()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack play failed session=$sessionId: ${e.message}")
            releaseBusHold()
            try {
                track.release()
            } catch (_: Exception) {
            }
            audioTrack = null
            return false
        }
        writerJob = scope.launch { writeLoop() }
        progressJob = scope.launch { reportProgressLoop() }
        return true
    }

    fun writePcm(data: ByteArray) {
        if (released.get()) return
        while (queuedBytes.get() + data.size > MAX_QUEUE_BYTES) {
            val dropped = frameQueue.poll() ?: break
            queuedBytes.addAndGet(-dropped.size.toLong())
        }
        if (frameQueue.offer(data)) {
            queuedBytes.addAndGet(data.size.toLong())
            bytesQueued.addAndGet(data.size.toLong())
        }
    }

    private suspend fun writeLoop() {
        while (scope.isActive && !released.get()) {
            val raw = frameQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
            queuedBytes.addAndGet(-raw.size.toLong())
            val frame = amplifyIfNeeded(raw)
            val track = audioTrack ?: continue
            try {
                var offset = 0
                while (offset < frame.size) {
                    // Re-check identity under the lock: release() nulls the field and frees
                    // the track while holding it, so a surviving local ref is stale there.
                    val written = synchronized(trackLock) {
                        if (released.get() || audioTrack !== track) {
                            -1
                        } else {
                            track.write(frame, offset, frame.size - offset)
                        }
                    }
                    if (written <= 0) break
                    feedAecReference(frame, offset, written)
                    offset += written
                    bytesWritten.addAndGet(written.toLong())
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    fun markEnded(totalBytes: Long, durationMs: Long) {
        expectedBytes.set(totalBytes.coerceAtLeast(1L))
        ended.set(true)
        if (totalBytes <= 0L && durationMs > 0L) {
            val estimated = durationMs * sampleRateHz / 1000L * AvaVoiceAudioConfig.BYTES_PER_SAMPLE
            expectedBytes.set(estimated.coerceAtLeast(1L))
        }
    }

    suspend fun awaitDrain(timeoutMs: Long = 15_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (frameQueue.isEmpty() && isDrained()) return true
            delay(20)
        }
        return isDrained()
    }

    private fun isDrained(): Boolean {
        if (!ended.get()) return false
        val framesPlayed = playedBytes() ?: return true
        return framesPlayed >= expectedBytes.get()
    }

    /** Bytes played out so far, or null once the track is gone. Locked against [release]. */
    private fun playedBytes(): Long? = synchronized(trackLock) {
        val track = audioTrack ?: return null
        runCatching {
            track.playbackHeadPosition.toLong() * AvaVoiceAudioConfig.BYTES_PER_SAMPLE
        }.getOrNull()
    }

    private suspend fun reportProgressLoop() {
        while (scope.isActive && !released.get()) {
            val expected = expectedBytes.get()
            val progress = if (expected > 0L) {
                val played = playedBytes() ?: 0L
                (played.toFloat() / expected).coerceIn(0f, 1f)
            } else {
                0f
            }
            AvaVoiceInboundBus.updatePlaybackProgress(progress)
            if (ended.get() && isDrained()) {
                AvaVoiceInboundBus.updatePlaybackProgress(1f)
                break
            }
            delay(32)
        }
    }

    /**
     * Software AEC far-end tap: the post-gain bytes actually accepted by the
     * AudioTrack, mirroring [com.example.ava.esphome.voicesatellite.VoiceAssistantPcmPlayer].
     */
    private fun feedAecReference(chunk: ByteArray, offset: Int, length: Int) {
        if (!PlaybackReferenceBus.active || length < 2) return
        aecRefWriter?.write(chunk, offset, length)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        progressJob?.cancel()
        writerJob?.cancel()
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
        aecRefWriter = null
        releaseBusHold()
        frameQueue.clear()
        queuedBytes.set(0)
        supervisor.cancel()
        Log.d(TAG, "released session=$sessionId")
    }

    private fun releaseBusHold() {
        if (busHeld.compareAndSet(true, false)) {
            PlaybackReferenceBus.release()
        }
    }

    private fun amplifyIfNeeded(frame: ByteArray): ByteArray {
        val gain = playbackGain
        if (gain == 1f || frame.isEmpty()) return frame
        val out = ByteArray(frame.size)
        val inp = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val samples = frame.size / 2
        for (i in 0 until samples) {
            val sample = inp.short.toFloat() * gain
            // Soft-limit hot samples instead of hard clipping (reduces crackle / "electric" harshness).
            val limited = (sample / (1f + kotlin.math.abs(sample) / 28_000f))
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buf.putShort(limited.toShort())
        }
        return out
    }

    companion object {
        private const val TAG = "AvaVoiceStreamPlayer"
        private const val POLL_TIMEOUT_MS = 50L
        private const val MAX_QUEUE_BYTES = 2_500_000L
    }
}
