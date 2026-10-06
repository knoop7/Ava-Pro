package com.example.ava.voice

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.example.ava.audio.PlaybackReferenceBus
import com.example.ava.audio.SoftwareAecProcessor
import com.example.ava.settings.SoftwareAecRoom
import com.example.ava.settings.SoftwareAecStrength
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PCM capture for voice message and live call outbound streams.
 *
 * Always uses [AvaVoiceAudioConfig.AUDIO_SOURCE] (MIC) — the same path as intercom / hold-to-talk
 * voice messages. Call mode adds hardware AEC/NS, software AEC against
 * [PlaybackReferenceBus], and a [AvaVoiceDelayedNoiseProcessor] (~300ms).
 */
internal class AvaVoiceStreamRecorder(
    /** Live call: hardware AEC/NS + software AEC on MIC, then ~300ms delayed NS. */
    private val callCaptureEnhance: Boolean = false,
    private val onPcmFrame: (ByteArray) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null

    /**
     * Guards native use of [audioRecord] against [releaseAudioRecord]. [stopImmediate]
     * cancels the capture job without joining it, so releasing right after cancel can
     * free the native record object while [captureLoop] sits inside `read` and abort
     * the process.
     */
    private val recordLock = Any()
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var noiseProcessor: AvaVoiceDelayedNoiseProcessor? = null
    private var softwareAec: SoftwareAecProcessor? = null
    private val aecBusHeld = AtomicBoolean(false)
    private val aecInput = ByteBuffer.allocateDirect(AvaVoiceAudioConfig.FRAME_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
    private val running = AtomicBoolean(false)
    private val micMuted = AtomicBoolean(false)
    private val totalBytes = AtomicLong(0)
    private val startedAtMs = AtomicLong(0)
    private val pendingFrame = ByteArray(AvaVoiceAudioConfig.FRAME_BYTES)
    private var pendingLen = 0

    fun setMicMuted(muted: Boolean) {
        micMuted.set(muted)
    }

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val minBuffer = AudioRecord.getMinBufferSize(
            AvaVoiceAudioConfig.SAMPLE_RATE,
            AvaVoiceAudioConfig.CHANNEL_CONFIG,
            AvaVoiceAudioConfig.AUDIO_FORMAT
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "invalid min buffer size=$minBuffer")
            running.set(false)
            return false
        }
        val bufferSize = minBuffer.coerceAtLeast(AvaVoiceAudioConfig.FRAME_BYTES * 4)
        val record = try {
            AudioRecord(
                AvaVoiceAudioConfig.AUDIO_SOURCE,
                AvaVoiceAudioConfig.SAMPLE_RATE,
                AvaVoiceAudioConfig.CHANNEL_CONFIG,
                AvaVoiceAudioConfig.AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            running.set(false)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized, state=${record.state}")
            record.release()
            running.set(false)
            return false
        }
        audioRecord = record
        totalBytes.set(0)
        pendingLen = 0
        startedAtMs.set(System.currentTimeMillis())
        if (callCaptureEnhance) {
            noiseProcessor = AvaVoiceDelayedNoiseProcessor()
            attachVoiceEffects(record.audioSessionId)
            attachSoftwareAec()
            Log.d(
                TAG,
                "call capture enhance: HW_AEC=${echoCanceler != null} HW_NS=${noiseSuppressor != null} " +
                    "SW_AEC=${softwareAec?.isActive == true} aec3=${softwareAec?.isAec3Active == true} " +
                    "delay=${AvaVoiceAudioConfig.CALL_NOISE_DELAY_MS}ms"
            )
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            releaseCallEnhance()
            record.release()
            audioRecord = null
            running.set(false)
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "not recording, state=${record.recordingState}")
            releaseCallEnhance()
            record.release()
            audioRecord = null
            running.set(false)
            return false
        }
        recordJob = scope.launch { captureLoop(record) }
        return true
    }

    private fun attachVoiceEffects(audioSessionId: Int) {
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
        }.onFailure { Log.w(TAG, "voice effects attach failed: ${it.message}") }
    }

    fun stopImmediate() {
        if (!running.compareAndSet(true, false)) return
        recordJob?.cancel()
        recordJob = null
        releaseAudioRecord()
    }

    suspend fun stopAndJoin(): Pair<Long, Long> {
        if (!running.compareAndSet(true, false)) {
            return 0L to 0L
        }
        val job = recordJob
        recordJob = null
        job?.join()
        releaseAudioRecord()
        val bytes = totalBytes.get()
        val duration = (System.currentTimeMillis() - startedAtMs.get()).coerceAtLeast(0L)
        return bytes to duration
    }

    private fun releaseAudioRecord() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        releaseCallEnhance()
        pendingLen = 0
        val record = audioRecord
        // stop() is safe to call concurrently with read() and makes a blocked read()
        // return, which lets captureLoop leave the recordLock section promptly.
        try {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord stop failed: ${e.message}")
        }
        synchronized(recordLock) {
            audioRecord = null
            try {
                record?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseNoiseProcessor() {
        noiseProcessor?.close()
        noiseProcessor = null
    }

    /**
     * Live calls always run software AEC. Voice Config AEC toggles only feed the
     * satellite mic; this path is MIC + USAGE_MEDIA, so hardware AEC often no-ops
     * on OEM HALs (Xiaomi tablets especially). Strong NLP matches speakerphone
     * coupling; do not reset the far-end ring here — inbound playback may already
     * be writing reference before this recorder starts.
     */
    private fun attachSoftwareAec() {
        val processor = SoftwareAecProcessor(
            filterLengthMs = SoftwareAecRoom.LIVING.filterLengthMs,
            suppressDb = SoftwareAecStrength.STRONG.suppressDb,
            suppressActiveDb = SoftwareAecStrength.STRONG.suppressActiveDb,
        )
        if (!processor.isActive) {
            processor.close()
            Log.w(TAG, "software AEC unavailable; call uplink will pass raw mic")
            return
        }
        softwareAec = processor
        if (aecBusHeld.compareAndSet(false, true)) {
            PlaybackReferenceBus.acquire()
        }
    }

    private fun releaseCallEnhance() {
        releaseSoftwareAec()
        releaseNoiseProcessor()
    }

    private fun releaseSoftwareAec() {
        if (aecBusHeld.compareAndSet(true, false)) {
            PlaybackReferenceBus.release()
        }
        softwareAec?.close()
        softwareAec = null
    }

    /** Echo-cancel one 20 ms frame in place. Leaves [frame] unchanged if AEC is down. */
    private fun applySoftwareAec(frame: ByteArray) {
        val processor = softwareAec ?: return
        if (frame.size != AvaVoiceAudioConfig.FRAME_BYTES) return
        aecInput.clear()
        aecInput.put(frame)
        aecInput.flip()
        val cancelled = processor.process(aecInput)
        if (cancelled.remaining() == frame.size) {
            cancelled.get(frame)
        }
    }

    private suspend fun captureLoop(record: AudioRecord) {
        val readBuffer = ByteArray(AvaVoiceAudioConfig.FRAME_BYTES * 4)
        var consecutiveErrors = 0
        try {
            while (running.get() && scope.isActive) {
                // Re-check identity under the lock: releaseAudioRecord nulls the field and
                // frees the object while holding it, so a surviving local ref is stale there.
                val read = synchronized(recordLock) {
                    if (!running.get() || audioRecord !== record) {
                        null
                    } else {
                        record.read(readBuffer, 0, readBuffer.size)
                    }
                } ?: break
                when {
                    read > 0 -> {
                        consecutiveErrors = 0
                        feedPcm(readBuffer, read)
                    }
                    read == 0 -> kotlinx.coroutines.delay(5)
                    // A dead AudioRecord can never recover; re-creating it is the caller's job.
                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.w(TAG, "AudioRecord dead, ending capture")
                        break
                    }
                    // Other errors are usually transient driver hiccups (mic handoff, HAL churn).
                    // Bailing out on the first one silences this device for the rest of the call.
                    else -> {
                        consecutiveErrors++
                        if (consecutiveErrors > MAX_CONSECUTIVE_READ_ERRORS) {
                            Log.w(TAG, "AudioRecord read error=$read, giving up after $consecutiveErrors")
                            break
                        }
                        Log.w(TAG, "AudioRecord read error=$read, retry $consecutiveErrors")
                        kotlinx.coroutines.delay(READ_ERROR_RETRY_MS)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (running.get()) {
                Log.w(TAG, "capture loop error: ${e.javaClass.simpleName} ${e.message}")
            }
        } finally {
            flushPendingFrame()
            flushNoiseDelayLine()
        }
    }

    private fun feedPcm(data: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val space = AvaVoiceAudioConfig.FRAME_BYTES - pendingLen
            val copy = minOf(space, length - offset)
            System.arraycopy(data, offset, pendingFrame, pendingLen, copy)
            pendingLen += copy
            offset += copy
            totalBytes.addAndGet(copy.toLong())
            if (pendingLen == AvaVoiceAudioConfig.FRAME_BYTES) {
                emitFrame(pendingFrame.copyOf())
                pendingLen = 0
            }
        }
    }

    private fun flushPendingFrame() {
        if (pendingLen <= 0) return
        while (pendingLen < AvaVoiceAudioConfig.FRAME_BYTES) {
            pendingFrame[pendingLen++] = 0
        }
        emitFrame(pendingFrame.copyOf())
        pendingLen = 0
    }

    private fun flushNoiseDelayLine() {
        val processor = noiseProcessor ?: return
        processor.flushRemaining().forEach { onPcmFrame(it) }
        processor.close()
        noiseProcessor = null
    }

    private fun emitFrame(frame: ByteArray) {
        applySoftwareAec(frame)
        val output = if (micMuted.get()) {
            ByteArray(frame.size)
        } else {
            frame
        }
        val processor = noiseProcessor
        if (processor == null) {
            onPcmFrame(output)
            return
        }
        processor.push(output)?.let { onPcmFrame(it) }
    }

    companion object {
        private const val TAG = "AvaVoiceStreamRecorder"
        private const val MAX_CONSECUTIVE_READ_ERRORS = 10
        private const val READ_ERROR_RETRY_MS = 20L
    }
}
