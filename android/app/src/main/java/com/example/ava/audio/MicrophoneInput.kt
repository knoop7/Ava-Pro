package com.example.ava.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.ava.settings.RecordingPath
import java.nio.ByteBuffer

class MicrophoneInput(
    private val appContext: Context? = null,
    val audioSource: Int = DEFAULT_AUDIO_SOURCE,
    val sampleRateInHz: Int = DEFAULT_SAMPLE_RATE_IN_HZ,
    val channelConfig: Int = DEFAULT_CHANNEL_CONFIG,
    val audioFormat: Int = DEFAULT_AUDIO_FORMAT,
    private val noiseSuppressorEnabled: Boolean = true,
    private val automaticGainControlEnabled: Boolean = true,
    private val acousticEchoCancelerEnabled: Boolean = true,
    /** When true, only [audioSource] is used — no MIC / VOICE_RECOGNITION fallback. */
    private val strictAudioSource: Boolean = false,
    private val recordingPath: RecordingPath = RecordingPath.AUTO,
) : AutoCloseable {
    private val bufferSize =
        AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
            .coerceAtLeast(1024)
    private val buffer = ByteBuffer.allocateDirect(bufferSize)
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null

    /** True if AudioRecord was successfully initialized */
    var isInitialized = false
        private set

    /** Last error message if initialization failed */
    var lastError: String? = null
        private set

    /**
     * True once a read reported [AudioRecord.ERROR_DEAD_OBJECT]. The audio server died
     * (HAL crash, vendor audio reload), which invalidates this recorder for good — every
     * later read keeps failing until [restart] rebuilds it.
     */
    var isDeadObject = false
        private set

    /** Audio source actually used after fallback probing in [createAudioRecord]. */
    var activeAudioSource: Int = audioSource
        private set

    /** Preferred device id after [AudioRecord.setPreferredDevice], or -1 when unused. */
    var preferredDeviceId: Int = -1
        private set

    /** True when the platform NoiseSuppressor effect was created and enabled for this session. */
    val isNoiseSuppressorAttached: Boolean get() = runCatching { ns?.enabled == true }.getOrDefault(false)

    /** True when the platform AutomaticGainControl effect was created and enabled for this session. */
    val isAutomaticGainControlAttached: Boolean get() = runCatching { agc?.enabled == true }.getOrDefault(false)

    /** True when the platform AcousticEchoCanceler effect was created and enabled for this session. */
    val isAcousticEchoCancelerAttached: Boolean get() = runCatching { aec?.enabled == true }.getOrDefault(false)

    val isRecording get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        if (audioRecord == null) {
            val record = createAudioRecord()
            if (record == null) {
                Log.e(TAG, "Failed to create AudioRecord: $lastError")
                return false
            }
            audioRecord = record
            isInitialized = true
            setupAudioEffects()
        }
        if (!isRecording) {
            Log.i(
                TAG,
                "Starting microphone source=$activeAudioSource ${sampleRateInHz}Hz " +
                    "ch=$channelConfig path=$recordingPath preferredDeviceId=$preferredDeviceId " +
                    "AEC=$isAcousticEchoCancelerAttached AGC=$isAutomaticGainControlAttached NS=$isNoiseSuppressorAttached",
            )
            try {
                audioRecord?.startRecording()
                Log.d(TAG, "Microphone started, isRecording=$isRecording")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to start recording", e)
                lastError = "Failed to start recording: ${e.message}"
                return false
            }
        } else {
            Log.w(TAG, "Microphone already started")
        }
        return isRecording
    }

    /**
     * Rebuild the recorder from scratch, for callers recovering from [isDeadObject].
     * Nothing of the old instance survives an audio-server restart, so it is released
     * and a fresh [AudioRecord] plus effect chain is created.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun restart(): Boolean {
        close()
        isDeadObject = false
        return start()
    }

    /** Discard the first reads after [start] — many HALs output silence until warmed up. */
    fun discardWarmupReads(count: Int = 12) {
        repeat(count.coerceAtLeast(0)) {
            read()
        }
    }

    fun read(): ByteBuffer {
        val audioRecord = this.audioRecord
        if (audioRecord == null) {
            Log.w(TAG, "read() called but microphone not started")
            buffer.clear()
            buffer.limit(0)
            return buffer
        }
        buffer.clear()
        val read = try {
            audioRecord.read(buffer, bufferSize)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading audio", e)
            -1
        }
        if (read < 0) {
            if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                // Log the transition only: a dead recorder fails on every read afterwards.
                if (!isDeadObject) Log.w(TAG, "AudioRecord died, recorder must be recreated")
                isDeadObject = true
            } else {
                Log.w(TAG, "Error reading audio, read: $read")
            }
            buffer.limit(0)
            return buffer
        }
        buffer.position(0)
        buffer.limit(read)
        return buffer
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord(): AudioRecord? {
        val sourcesToTry = if (strictAudioSource) {
            listOf(audioSource)
        } else {
            buildList {
                add(audioSource)
                if (audioSource != DEFAULT_AUDIO_SOURCE) add(DEFAULT_AUDIO_SOURCE)
                val voiceRecognition = MediaRecorder.AudioSource.VOICE_RECOGNITION
                if (audioSource != voiceRecognition) add(voiceRecognition)
            }.distinct()
        }

        for (source in sourcesToTry) {
            try {
                val requested = buildAudioRecord(source)
                if (requested.state == AudioRecord.STATE_INITIALIZED) {
                    activeAudioSource = source
                    if (source != audioSource) {
                        Log.w(TAG, "Audio source $audioSource unavailable, using $source")
                    }
                    applyPreferredDevice(requested)
                    lastError = null
                    return requested
                }
                requested.release()
            } catch (e: SecurityException) {
                lastError = "Microphone permission denied: ${e.message}"
                Log.e(TAG, lastError!!, e)
                return null
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord creation failed for source $source: ${e.message}", e)
            }
        }

        lastError = "Failed to initialize AudioRecord (permission denied or device busy)"
        Log.e(TAG, lastError!!)
        return null
    }

    private fun buildAudioRecord(source: Int): AudioRecord {
        return AudioRecord(
            source,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            bufferSize * 2
        )
    }

    /**
     * Best-effort device route. Never fails open: missing USB/built-in falls back to
     * the system default so wake/STT keep working.
     */
    private fun applyPreferredDevice(record: AudioRecord) {
        preferredDeviceId = -1
        if (recordingPath == RecordingPath.AUTO) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "recordingPath=$recordingPath ignored: needs API 23+")
            return
        }
        val context = appContext ?: run {
            Log.w(TAG, "recordingPath=$recordingPath ignored: no Context")
            return
        }
        val device = MicCaptureRouting.resolvePreferredInputDevice(context, recordingPath)
        if (device == null) {
            Log.w(TAG, "recordingPath=$recordingPath: using system default input")
            return
        }
        val ok = try {
            record.setPreferredDevice(device)
        } catch (e: Exception) {
            Log.w(TAG, "setPreferredDevice failed for $recordingPath", e)
            false
        }
        if (ok) {
            preferredDeviceId = device.id
            Log.i(
                TAG,
                "recordingPath=$recordingPath preferred " +
                    "${MicCaptureRouting.typeName(device.type)} id=${device.id}",
            )
        } else {
            Log.w(
                TAG,
                "recordingPath=$recordingPath setPreferredDevice returned false for " +
                    "${MicCaptureRouting.typeName(device.type)} id=${device.id}",
            )
        }
    }

    private fun setupAudioEffects() {
        releaseAudioEffects()

        val sessionId = audioRecord?.audioSessionId ?: return
        if (sessionId <= 0) {
            Log.w(TAG, "Audio session id is invalid, skipping audio effects")
            return
        }

        if (acousticEchoCancelerEnabled) {
            aec = createAudioEffect(
                available = AcousticEchoCanceler.isAvailable(),
                label = "AEC"
            ) { AcousticEchoCanceler.create(sessionId) }
        }

        if (automaticGainControlEnabled) {
            agc = createAudioEffect(
                available = AutomaticGainControl.isAvailable(),
                label = "AGC"
            ) { AutomaticGainControl.create(sessionId) }
        }

        if (noiseSuppressorEnabled) {
            ns = createAudioEffect(
                available = NoiseSuppressor.isAvailable(),
                label = "NS"
            ) { NoiseSuppressor.create(sessionId) }
        }
    }

    private fun <T> createAudioEffect(
        available: Boolean,
        label: String,
        factory: () -> T?
    ): T? where T : android.media.audiofx.AudioEffect {
        if (!available) {
            Log.d(TAG, "$label not available on this device")
            return null
        }
        var effect: T? = null
        return try {
            effect = factory()
            val created = effect
            if (created == null) {
                Log.w(TAG, "$label creation returned null")
                return null
            }
            val status = created.setEnabled(true)
            val enabled = created.enabled
            Log.i(TAG, "$label requested=true status=$status enabled=$enabled control=${created.hasControl()}")
            // An enabled platform-owned effect can be useful without exclusive
            // control. A non-null but disabled effect is not a working AEC/NS/AGC.
            if (enabled) created else {
                created.release()
                null
            }
        } catch (e: Exception) {
            runCatching { effect?.release() }
            Log.w(TAG, "Failed to enable $label", e)
            null
        }
    }

    private fun releaseAudioEffects() {
        // Teardown can throw once the audio session is gone; recovery must not abort here.
        runCatching { aec?.release() }
        aec = null
        runCatching { agc?.release() }
        agc = null
        runCatching { ns?.release() }
        ns = null
    }

    override fun close() {
        releaseAudioEffects()

        audioRecord?.let {
            if (isRecording) {
                runCatching { it.stop() }
            }
            runCatching { it.release() }
            audioRecord = null
        }
        isInitialized = false
    }

    companion object {
        const val TAG = "MicrophoneInput"
        const val DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
        const val DEFAULT_SAMPLE_RATE_IN_HZ = 16000
        const val DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val DEFAULT_PROFILE: DeviceAudioProfile = DeviceAudioProfile.DEFAULT
    }
}
