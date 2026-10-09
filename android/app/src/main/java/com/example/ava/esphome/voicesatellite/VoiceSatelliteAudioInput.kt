package com.example.ava.esphome.voicesatellite

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission
import com.example.ava.audio.AudioEnergy
import com.example.ava.audio.AudioFrameProcessor
import com.example.ava.audio.DeviceAudioProfile
import com.example.ava.audio.MicrophoneInput
import com.example.ava.audio.PlaybackReferenceBus
import com.example.ava.audio.SoftAecProbe
import com.example.ava.audio.SoftwareAecProcessor
import com.example.ava.audio.SoftwareNsProcessor
import com.example.ava.audio.StereoDownmixMode
import com.example.ava.mods.ModPlaybackReference
import com.example.ava.microwakeword.AssetWakeWordProvider
import com.example.ava.microwakeword.WakeWordCutoffPolicy
import com.example.ava.microwakeword.WakeWordDetector
import com.example.ava.microwakeword.WakeWordDetectorFactory
import com.example.ava.microwakeword.WakeWordEngineDetector
import com.example.ava.microwakeword.WakeWordProvider
import com.example.ava.microwakeword.WakeSampleVerifier
import com.example.ava.settings.MicrophoneSettings
import com.example.ava.settings.RecordingPath
import com.example.ava.stopword.StopWordDetector
import com.example.ava.settings.SoftwareAecRoom
import com.example.ava.settings.SoftwareAecStrength
import com.example.ava.settings.SoftwareNsStrength
import com.example.ava.settings.WakeWordEngine
import java.util.concurrent.atomic.AtomicReference
import com.example.ava.voiceprint.VoicePrintPcmCapture
import com.example.ava.voiceprint.VoicePrintWakeWindows
import com.example.ava.voiceprint.VoicePrintRingBuffer
import com.example.ava.voiceprint.WakeDetectAudioSource
import com.example.ava.voiceprint.WakeDetectAudioTap
import com.example.ava.openwakeword.OpenWakeWordCutoffPolicy
import com.example.ava.openwakeword.OpenWakeWordDetector
import com.example.ava.openwakeword.OpenWakeWordProvider
import com.example.ava.openwakeword.WakeEngineBudgetProbe
import com.example.ava.multidevice.WakeWordArbiter
import com.example.ava.services.ChorusWakeBlurService
import com.google.protobuf.ByteString
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

private const val VAD_ASSET_PATH = "vad"

/**
 * Last-known capture diagnostics from the live [MicrophoneInput] / software AEC loop.
 * Mic is scoped to the capture coroutine; this snapshot is the read-only window for stats UI.
 */
data class MicrophoneCaptureSnapshot(
    val activeAudioSource: Int? = null,
    val preferredDeviceId: Int = -1,
    val lastError: String? = null,
    val hwNsAttached: Boolean? = null,
    val hwAgcAttached: Boolean? = null,
    val hwAecAttached: Boolean? = null,
    /** True while Speex inside [SoftwareAecProcessor] is initialized and live. */
    val softwareAecActive: Boolean = false,
    /** Wrapper constructed (setting on + capture loop) even if Speex init failed. */
    val softwareAecConstructed: Boolean = false,
    /** Live mirror: AEC bypassed because uplink speech is streaming. */
    val softwareAecPausedForSpeech: Boolean = false,
    /** True while WebRTC NS inside [SoftwareNsProcessor] is initialized and live. */
    val softwareNsActive: Boolean = false,
    /** Wrapper constructed (setting on + capture loop) even if NS init failed. */
    val softwareNsConstructed: Boolean = false,
    val isRecording: Boolean = false,
    /** Pre-gain detect RMS (±1 float scale) — best raw energy for gain/clip debugging. */
    val preGainRms: Float = 0f,
    val micGainDb: Int = 0,
    val micGainLinear: Float = 1f,
    /** HA / satellite mic volume multiplier (separate from software dB gain). */
    val microphoneVolume: Float = 1f,
    val temporaryPaused: Boolean = false,
    /** vsWakeWord adaptive input gain (1f when not VS). */
    val effectiveProfileId: String = "",
    val captureSampleRateHz: Int = 0,
    val captureChannelCount: Int = 0,
)

/** One microphone preview pass: [processed] is post-AEC/NS, [raw] is the same audio before AEC. */
class MicPreviewClip(
    val processed: ShortArray,
    val raw: ShortArray?,
)

class VoiceSatelliteAudioInput(
    activeWakeWords: List<String>,
    val wakeWordEngine: WakeWordEngine,
    private val wakeWordProvider: WakeWordProvider,
    private val vsWakeWordProvider: OpenWakeWordProvider? = null,
    stopWakeWordId: String? = null,
    builtinStopWordEnabled: Boolean = true,
    private val appContext: Context,
    private val audioProfile: DeviceAudioProfile = MicrophoneInput.DEFAULT_PROFILE,
    private val enabled: Boolean = true,
    muted: Boolean = false,
    audioSource: Int = audioProfile.captureAudioSource,
    noiseSuppressorEnabled: Boolean = true,
    softwareNsEnabled: Boolean = false,
    softwareNsStrength: SoftwareNsStrength = SoftwareNsStrength.LIGHT,
    automaticGainControlEnabled: Boolean = true,
    acousticEchoCancelerEnabled: Boolean = true,
    softwareAecEnabled: Boolean = false,
    softwareAecPauseDuringSpeech: Boolean = false,
    softwareAecStrength: SoftwareAecStrength = SoftwareAecStrength.STANDARD,
    softwareAecRoom: SoftwareAecRoom = SoftwareAecRoom.LIVING,
    micGainDb: Int = 0,
    recordingPath: RecordingPath = RecordingPath.AUTO,
) {
    // Snapshot at AudioInput construction (after satellite restart). Do not rescan on every
    // read — live getters flap settings watchers and tear down the mic pipeline.
    val availableWakeWords by lazy {
        if (wakeWordEngine == WakeWordEngine.OPEN_WAKE_WORD) {
            vsWakeWordProvider?.listModels()
                ?.filterNot { it.manifest.stopClassifier }
                ?.map {
                    com.example.ava.microwakeword.WakeWordWithId(
                        it.id,
                        com.example.ava.microwakeword.WakeWord(
                            type = "vswakeword",
                            wake_word = it.displayName,
                            author = "",
                            website = "",
                            model = "${it.id}.onnx",
                            trained_languages = emptyArray(),
                            version = 1,
                            micro = com.example.ava.microwakeword.Micro(
                                probability_cutoff = it.threshold,
                                feature_step_size = 10,
                                sliding_window_size = it.slidingWindowSize,
                                tensor_arena_size = 0,
                                minimum_esphome_version = "",
                            ),
                        ),
                    )
                } ?: emptyList()
        } else {
            wakeWordProvider.getWakeWords()
        }
    }
    private var currentWakeWordDetector: WakeWordEngineDetector? = null

    private val _activeWakeWords = MutableStateFlow(activeWakeWords)
    val activeWakeWords = _activeWakeWords.asStateFlow()
    fun setActiveWakeWords(value: List<String>) {
        _activeWakeWords.value = value
    }

    /**
     * Stop-slot model id (never surfaces in [activeWakeWords] — HA config and the
     * settings write-back observer must not see it). Detections of this id route to
     * [AudioResult.StopDetected] instead of wake. Null = no model in the stop slot.
     */
    private val _stopWakeWordId = MutableStateFlow(stopWakeWordId)
    val currentStopWakeWordId: String? get() = _stopWakeWordId.value

    /** Model-free DSP stop path; off when the stop slot is a model or "none". */
    private val _builtinStopWordEnabled = AtomicBoolean(builtinStopWordEnabled)

    fun setStopWordConfig(builtinEnabled: Boolean, modelId: String?) {
        _builtinStopWordEnabled.set(builtinEnabled)
        _stopWakeWordId.value = modelId
    }

    private val _muted = MutableStateFlow(muted)
    val muted = _muted.asStateFlow()
    fun setMuted(value: Boolean) {
        if (_muted.value == value) return
        _muted.value = value
    }

    private val _temporaryPaused = MutableStateFlow(false)
    fun setTemporaryPaused(value: Boolean) {
        _temporaryPaused.value = value
    }

    private val _audioSource = MutableStateFlow(audioSource)
    fun setAudioSource(value: Int) {
        _audioSource.value = value
    }

    private val _recordingPath = MutableStateFlow(recordingPath)
    fun setRecordingPath(value: RecordingPath) {
        _recordingPath.value = value
    }

    private val _noiseSuppressorEnabled = MutableStateFlow(noiseSuppressorEnabled)
    fun setNoiseSuppressorEnabled(value: Boolean) {
        _noiseSuppressorEnabled.value = value
    }

    private val _softwareNsEnabled = MutableStateFlow(softwareNsEnabled)
    fun setSoftwareNsEnabled(value: Boolean) {
        _softwareNsEnabled.value = value
    }

    private val _softwareNsStrength = MutableStateFlow(softwareNsStrength)
    private val liveSoftwareNs = AtomicReference<SoftwareNsProcessor?>(null)
    fun setSoftwareNsStrength(value: SoftwareNsStrength) {
        _softwareNsStrength.value = value
        // Hot policy change — no capture restart.
        liveSoftwareNs.get()?.setMode(value.webrtcMode)
    }

    private val _automaticGainControlEnabled = MutableStateFlow(automaticGainControlEnabled)
    fun setAutomaticGainControlEnabled(value: Boolean) {
        _automaticGainControlEnabled.value = value
    }

    private val _acousticEchoCancelerEnabled = MutableStateFlow(acousticEchoCancelerEnabled)
    fun setAcousticEchoCancelerEnabled(value: Boolean) {
        _acousticEchoCancelerEnabled.value = value
    }

    private val _softwareAecEnabled = MutableStateFlow(softwareAecEnabled)
    fun setSoftwareAecEnabled(value: Boolean) {
        _softwareAecEnabled.value = value
    }

    /** Hot flag: does not restart the capture loop. Read each frame with [isStreaming]. */
    private val _softwareAecPauseDuringSpeech = AtomicBoolean(softwareAecPauseDuringSpeech)
    fun setSoftwareAecPauseDuringSpeech(value: Boolean) {
        _softwareAecPauseDuringSpeech.set(value)
    }

    private val _softwareAecStrength = MutableStateFlow(softwareAecStrength)
    fun setSoftwareAecStrength(value: SoftwareAecStrength) {
        _softwareAecStrength.value = value
    }

    private val _softwareAecRoom = MutableStateFlow(softwareAecRoom)
    fun setSoftwareAecRoom(value: SoftwareAecRoom) {
        _softwareAecRoom.value = value
    }

    private val _micGainDb = MutableStateFlow(MicrophoneSettings.clampMicGainDb(micGainDb))
    fun setMicGainDb(value: Int) {
        _micGainDb.value = MicrophoneSettings.clampMicGainDb(value)
    }

    private val _microphoneVolume = MutableStateFlow(1.0f)
    val microphoneVolume = _microphoneVolume.asStateFlow()
    fun setMicrophoneVolume(value: Float) {
        _microphoneVolume.value = value.coerceIn(0.0f, 2.0f)
    }

    private val _isStreaming = AtomicBoolean(false)
    var isStreaming: Boolean
        get() = _isStreaming.get()
        set(value) = _isStreaming.set(value)

    /**
     * Continuous conversation is in Processing, Responding, or a remote turn.
     * Playback inserts still use the echo residual. This flag is the no-playback
     * path: a new sentence while the reply is only being worked on.
     */
    private val speechInsertOpen = AtomicBoolean(false)
    fun setSpeechInsertOpen(value: Boolean) {
        speechInsertOpen.set(value)
    }

    /**
     * Emit [AudioResult.Audio] frames while the mic uplink is still closed for the wake
     * earcon, so the satellite can pre-roll them. Without this the stream gate below
     * drops the frames at the source and speech spoken over the earcon ("hey ava,
     * what's for dinner") can never reach HA. Frames produced under this flag follow
     * the exact stream path (AEC, gain), only the consumer decides their fate.
     */
    private val _wakePreRollCapturing = AtomicBoolean(false)
    var wakePreRollCapturing: Boolean
        get() = _wakePreRollCapturing.get()
        set(value) = _wakePreRollCapturing.set(value)

    private val _stopWordDetectionEnabled = AtomicBoolean(false)
    fun setStopWordDetectionEnabled(enabled: Boolean) {
        _stopWordDetectionEnabled.set(enabled)
    }

    /**
     * When true, the microphone, live level meter and voiceprint ring buffer keep running,
     * but wake/stop-word detection is skipped. Used during manual voiceprint enrollment so a
     * spoken wake word records a sample instead of triggering a wake (and its overlay animation).
     */
    private val wakeDetectionSuspended = AtomicBoolean(false)
    fun setWakeDetectionSuspended(value: Boolean) {
        wakeDetectionSuspended.set(value)
        if (!value) {
            enrollmentListenActive.set(false)
        }
    }

    fun isWakeDetectionSuspended(): Boolean = wakeDetectionSuspended.get()
    
    private val pendingSensitivities = mutableMapOf<String, Float>()

    /**
     * Extra-strictness levels per wake word id (0 = off, 1 = offline re-verify /
     * forced precision, 2 = level 1 + engine-side tightening). A parallel channel to
     * [pendingSensitivities] — never encoded into the shared sensitivity floats.
     */
    private val pendingExtraStrictness = mutableMapOf<String, Int>()

    private val voicePrintRingBuffer = VoicePrintRingBuffer()
    private val voicePrintCaptureEnabled = AtomicBoolean(false)
    val voicePrintSampleRate: Int get() = voicePrintRingBuffer.sampleRate

    // Allocated on first preview so devices that never open the settings cards pay nothing.
    private val micPreviewRingBuffer by lazy { newMicPreviewRingBuffer() }
    private val micPreviewRawRingBuffer by lazy { newMicPreviewRingBuffer() }
    private val micPreviewCaptureEnabled = AtomicBoolean(false)
    private val micPreviewRawEnabled = AtomicBoolean(false)

    @Volatile
    private var micPreviewSamples = 0

    @Volatile
    private var liveMicLevel: Float = 0f

    @Volatile
    private var userSpeechPeakPreGainRms: Float = 0f

    @Volatile
    private var idleAmbientRms: Float = 0f

    /** Faster idle RMS for live TTS volume (settings + HA). Frozen while speaking. */
    @Volatile
    private var previewAmbientRms: Float = 0f

    private val _previewAmbient = MutableStateFlow(0f)
    fun previewAmbientFlow() = _previewAmbient.asStateFlow()

    private var lastPreviewAmbientEmitMs = 0L

    private val ambientHold = AtomicBoolean(false)

    @Volatile
    private var lastPreGainDetectRms: Float = 0f

    // Trailing peak of pre-gain detect RMS, for chorus proximity ranking.
    // The instantaneous frame RMS is the wrong ruler there: a streaming
    // detector crosses its threshold on the quiet frame *after* the phrase,
    // so the arbiter read 0.000 and packScore fell back to confidence jitter
    // (measured: score=850 from conf=0.85 with rms=0.000). Peak over the wake
    // utterance is what "who heard it louder" actually means.
    @Volatile
    private var wakeWindowPeakRms: Float = 0f

    @Volatile
    private var wakeWindowPeakAtMs: Long = 0L

    /**
     * Peak instant float-RMS of pre-AEC mic frames while a guided enrollment listen is open.
     * Uses the same PCM path as [voicePrintRingBuffer] (not the EMA-smoothed meter).
     */
    @Volatile
    private var enrollmentPeakRms: Float = 0f

    @Volatile
    private var enrollmentStartMark = -1L

    private val enrollmentListenActive = AtomicBoolean(false)

    private val userSpeechMicPeakTracking = AtomicBoolean(false)

    fun currentMicrophoneLevel(): Float = liveMicLevel

    /** Peak pre-AEC float-RMS since [markVoicePrintEnrollmentStart]; 0 when not listening. */
    fun currentVoicePrintEnrollmentPeakLevel(): Float = enrollmentPeakRms

    /** Ambient mic level while idle (excludes wake word and uplink speech). */
    fun ambientMicLevel(): Float = idleAmbientRms

    /** Snappy idle RMS for the settings preview rail. */
    fun previewAmbientMicLevel(): Float = previewAmbientRms

    fun holdAmbientSampling(hold: Boolean) {
        if (hold) {
            _previewAmbient.value = previewAmbientRms
        }
        ambientHold.set(hold)
    }

    @Volatile
    private var captureSnapshot = MicrophoneCaptureSnapshot()

    /** Read-only last-known mic / AEC capture diagnostics (updated from the capture loop). */
    fun microphoneCaptureSnapshot(): MicrophoneCaptureSnapshot = captureSnapshot

    @Volatile
    private var lastWakeWordIdDiagnostic: String? = null

    @Volatile
    private var lastWakePhraseDiagnostic: String? = null

    @Volatile
    private var lastWakeConfidenceDiagnostic: Float = 0f

    @Volatile
    private var lastWakeAtMsDiagnostic: Long = 0L

    @Volatile
    private var diagMicGainLinear: Float = 1f

    @Volatile
    private var diagEffectiveProfileId: String = ""

    @Volatile
    private var diagCaptureSampleRateHz: Int = 0

    @Volatile
    private var diagCaptureChannelCount: Int = 0

    /**
     * Last wake word id marked within [ttlMs], or null when aged out / never fired.
     * Diagnostic only — does not affect wake routing.
     */
    fun lastWakeWordId(ttlMs: Long = 8_000L): String? {
        val id = lastWakeWordIdDiagnostic ?: return null
        if (System.currentTimeMillis() - lastWakeAtMsDiagnostic > ttlMs) return null
        return id
    }

    fun lastWakePhrase(ttlMs: Long = 8_000L): String? {
        val phrase = lastWakePhraseDiagnostic ?: return null
        if (System.currentTimeMillis() - lastWakeAtMsDiagnostic > ttlMs) return null
        return phrase
    }

    fun lastWakeConfidence(ttlMs: Long = 8_000L): Float? {
        if (lastWakeWordIdDiagnostic == null) return null
        if (System.currentTimeMillis() - lastWakeAtMsDiagnostic > ttlMs) return null
        return lastWakeConfidenceDiagnostic
    }

    /** Wall-clock of last wake mark (0 if never). Used by stats UI edge-detect for level crest. */
    fun lastWakeAtMs(): Long = lastWakeAtMsDiagnostic

    /**
     * Loudest pre-gain detect frame over the last [WAKE_PEAK_WINDOW_MS] (0–1).
     * Chorus proximity ruler — see [wakeWindowPeakRms] for why this is a peak
     * and not the latest frame.
     */
    fun lastDetectRms(): Float =
        if (System.currentTimeMillis() - wakeWindowPeakAtMs <= WAKE_PEAK_WINDOW_MS) {
            maxOf(wakeWindowPeakRms, lastPreGainDetectRms)
        } else {
            lastPreGainDetectRms
        }

    /** Live wake window/prob/cutoff probe (microWakeWord or vsWakeWord). */
    fun wakeLiveProbe(): List<com.example.ava.microwakeword.WakeWordLiveProbe> =
        when (val detector = currentWakeWordDetector) {
            is WakeWordDetector -> detector.liveProbe()
            is OpenWakeWordDetector -> detector.liveProbe()
            else -> emptyList()
        }

    fun wakeBudgetProbe(): WakeEngineBudgetProbe? =
        (currentWakeWordDetector as? OpenWakeWordDetector)?.budgetProbe()

    /**
     * Record a wake that already passed sample / voiceprint gates. Do not call on raw
     * streaming hits — that made Voice Stats "Last Wake" claim success for rejected wakes.
     */
    fun markLastWakeDiagnostic(
        wakeWordId: String,
        wakeWordPhrase: String,
        confidence: Float,
    ) {
        lastWakeWordIdDiagnostic = wakeWordId
        lastWakePhraseDiagnostic = wakeWordPhrase
        lastWakeConfidenceDiagnostic = confidence
        lastWakeAtMsDiagnostic = System.currentTimeMillis()
    }

    private fun publishCaptureSnapshot(
        mic: MicrophoneInput?,
        softwareAec: SoftwareAecProcessor?,
        softwareAecPausedForSpeech: Boolean,
        softwareNs: SoftwareNsProcessor? = null,
    ) {
        captureSnapshot = MicrophoneCaptureSnapshot(
            activeAudioSource = mic?.activeAudioSource,
            preferredDeviceId = mic?.preferredDeviceId ?: -1,
            lastError = mic?.lastError,
            hwNsAttached = mic?.isNoiseSuppressorAttached,
            hwAgcAttached = mic?.isAutomaticGainControlAttached,
            hwAecAttached = mic?.isAcousticEchoCancelerAttached,
            // isActive reflects Speex init success, not merely the wrapper reference.
            softwareAecActive = softwareAec?.isActive == true,
            softwareAecConstructed = softwareAec != null,
            softwareAecPausedForSpeech = softwareAecPausedForSpeech,
            softwareNsActive = softwareNs?.isActive == true,
            softwareNsConstructed = softwareNs != null,
            isRecording = mic?.isRecording == true,
            preGainRms = lastPreGainDetectRms,
            micGainDb = _micGainDb.value,
            micGainLinear = diagMicGainLinear,
            microphoneVolume = _microphoneVolume.value,
            temporaryPaused = _temporaryPaused.value,
            effectiveProfileId = diagEffectiveProfileId,
            captureSampleRateHz = diagCaptureSampleRateHz,
            captureChannelCount = diagCaptureChannelCount,
        )
    }

    /** Start tracking peak RMS of user command audio sent to HA (not wake word). */
    fun beginUserSpeechMicPeakTracking() {
        userSpeechPeakPreGainRms = 0f
        userSpeechMicPeakTracking.set(true)
    }

    fun ensureUserSpeechMicPeakTracking() {
        if (!userSpeechMicPeakTracking.get()) {
            beginUserSpeechMicPeakTracking()
        }
    }

    fun endSessionMicPeakTracking() {
        userSpeechMicPeakTracking.set(false)
    }

    fun sessionMicPeakPreGainRms(): Float = userSpeechPeakPreGainRms

    /**
     * Peak-hold over a trailing window. Once the held peak ages out we cannot
     * recover the true max of what remains without keeping history, so the
     * current frame becomes the new peak — accurate enough for ranking two
     * devices that heard the same phrase.
     */
    private fun recordWakeWindowPeak(preGainRms: Float) {
        val now = System.currentTimeMillis()
        if (preGainRms >= wakeWindowPeakRms || now - wakeWindowPeakAtMs > WAKE_PEAK_WINDOW_MS) {
            wakeWindowPeakRms = preGainRms
            wakeWindowPeakAtMs = now
        }
    }

    private fun recordUserSpeechMicPeak(preGainRms: Float) {
        if (!userSpeechMicPeakTracking.get() || !isStreaming) return
        val sample = preGainRms.coerceIn(0f, 1f)
        if (sample > userSpeechPeakPreGainRms) {
            userSpeechPeakPreGainRms = sample
        }
    }

    private fun updateIdleAmbientRms(rawRms: Float) {
        if (ambientHold.get() || userSpeechMicPeakTracking.get() || isStreaming) return
        val sample = rawRms.coerceIn(0f, 1f)
        idleAmbientRms = (idleAmbientRms * 0.992f + sample * 0.008f).coerceIn(0f, 1f)
        previewAmbientRms = (previewAmbientRms * 0.78f + sample * 0.22f).coerceIn(0f, 1f)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPreviewAmbientEmitMs >= 100L) {
            lastPreviewAmbientEmitMs = now
            _previewAmbient.value = previewAmbientRms
        }
    }

    private val audioEventWindowAccumulator = com.example.ava.detection.AudioEventWindowAccumulator()
    private val audioEventCaptureEnabled = AtomicBoolean(false)
    var audioEventWindowListener: ((java.nio.ByteBuffer) -> Unit)? = null

    fun setVoicePrintCaptureEnabled(enabled: Boolean) {
        voicePrintCaptureEnabled.set(enabled)
    }

    fun setAudioEventCaptureEnabled(enabled: Boolean) {
        audioEventCaptureEnabled.set(enabled)
        if (!enabled) {
            audioEventWindowAccumulator.reset()
        }
    }

    /**
     * Set when [markWakeSample] runs while far-end audio is active. Strong streaming scores
     * may skip offline verify; weaker ones fall through to the sample ring, which follows
     * the streaming detection source (echo-cancelled during far-end; same idle
     * tap the detector hears — raw unless software NS is on).
     */
    private val wakeMarkDuringFarEnd = AtomicBoolean(false)

    /**
     * Deadline until which wake dispatch is withheld during far-end playback because the
     * assistant's own response text mentions an active wake phrase (e.g. "I'm Jarvis").
     * The echo of that response contains a genuine utterance of the phrase — the one
     * self-trigger class no AEC can be expected to cancel, and offline sample verify
     * would confirm it too (it re-scores the same echo). Mic-side only; the playback
     * path is untouched. Wakes recover the moment far-end goes quiet, so a stale
     * deadline cannot lock the detector out while nothing is playing.
     */
    @Volatile
    private var ttsWakeEchoRiskUntilMs = 0L

    /**
     * Same hold for the stop slot, armed only when the response text mentions the
     * stop-slot model phrase. Deliberately separate from [ttsWakeEchoRiskUntilMs]:
     * a response that speaks the wake phrase must not lock the stop channel — stop
     * is exactly the barge-in the user needs while that response plays — and a
     * stop-phrase mention must not lock wakes.
     */
    @Volatile
    private var ttsStopEchoRiskUntilMs = 0L

    /**
     * Screen assistant response text before it is spoken. A mention of an active wake
     * phrase arms [ttsWakeEchoRiskUntilMs], a mention of the stop-slot model phrase
     * arms [ttsStopEchoRiskUntilMs], each for the estimated playback duration;
     * non-matching text clears the corresponding hold. Called by the state machine on
     * INTENT_END speech and TTS_START text (same funnel for both TTS modes).
     */
    fun noteTtsTextForWakeEchoRisk(text: String) {
        fun phrasesFor(ids: List<String>) = ids.map { id ->
            availableWakeWords.firstOrNull { it.id == id }?.wakeWord?.wake_word
                ?: id.replace('_', ' ')
        }
        val cjkChars = text.count { Character.isIdeographic(it.code) }
        val holdMs = (
            TTS_ECHO_RISK_BASE_MS +
                (text.length - cjkChars) * TTS_ECHO_RISK_PER_CHAR_MS +
                cjkChars * TTS_ECHO_RISK_PER_CJK_CHAR_MS
            ).coerceAtMost(TTS_ECHO_RISK_MAX_MS)
        val now = System.currentTimeMillis()
        val wakeMentioned =
            ttsTextMentionsWakePhrase(text, phrasesFor(_activeWakeWords.value))
        ttsWakeEchoRiskUntilMs = if (wakeMentioned) now + holdMs else 0L
        val stopIds = listOfNotNull(_stopWakeWordId.value)
        val stopMentioned = stopIds.isNotEmpty() &&
            ttsTextMentionsWakePhrase(text, phrasesFor(stopIds))
        ttsStopEchoRiskUntilMs = if (stopMentioned) now + holdMs else 0L
        if (wakeMentioned || stopMentioned) {
            val which = listOfNotNull(
                "wake".takeIf { wakeMentioned },
                "stop".takeIf { stopMentioned },
            ).joinToString("+")
            Log.i(TAG, "tts text mentions $which phrase — dispatch held ${holdMs}ms while far-end active")
        }
    }

    /** Session teardown: the risky response is no longer pending or playing. */
    fun clearTtsWakeEchoRisk() {
        ttsWakeEchoRiskUntilMs = 0L
        ttsStopEchoRiskUntilMs = 0L
    }

    /**
     * Event-driven arm of the playback-onset guard, called when TTS playback
     * actually starts (URL and PCM paths). The loop's far-end EDGE arming alone
     * misses one real sequence: wake chime -> short command -> fast HA response.
     * The far-end write-hold (1.5 s) bridges the chime into the TTS start, so
     * playback onset never shows as a fresh edge and the guard armed at chime
     * time is already spent — exactly where isolated opening words ("hello")
     * were reported firing, and where speech-insert's raw energy test hears
     * the speaker as a person.
     *
     * Stop dispatch and speech-insert both read this deadline. No double-talk
     * override: during the transient the residual itself would qualify.
     */
    @Volatile
    private var stopOnsetGuardEventUntilMs = 0L

    fun noteStopOnsetGuard() {
        stopOnsetGuardEventUntilMs = System.currentTimeMillis() + STOP_FAR_END_ONSET_GUARD_MS
    }

    /** Always mark the wake sample ring (voiceprint + offline sample verify share this buffer). */
    fun markWakeSample(engine: WakeWordEngine = wakeWordEngine) {
        voicePrintRingBuffer.markWake(VoicePrintWakeWindows.markLeadMs(engine))
        // Latch at mark time: verify waits ~afterMs later, by which lastWrite may have aged out.
        wakeMarkDuringFarEnd.set(PlaybackReferenceBus.isFarEndActive())
    }

    fun markVoicePrintWake(engine: WakeWordEngine = wakeWordEngine) {
        if (!voicePrintCaptureEnabled.get()) return
        markWakeSample(engine)
    }

    fun snapshotVoicePrintWakeMark(): Long = voicePrintRingBuffer.currentWakeMark()

    fun voicePrintSamplesReady(engine: WakeWordEngine): Boolean {
        val window = VoicePrintWakeWindows.forEngine(engine)
        return voicePrintRingBuffer.samplesAvailableAfterMark(window.afterMs)
    }

    fun voicePrintSamplesReady(mark: Long, engine: WakeWordEngine): Boolean {
        val window = VoicePrintWakeWindows.forEngine(engine)
        return voicePrintRingBuffer.samplesAvailableAfterMark(mark, window.afterMs)
    }

    /** Anchor the start of a guided enrollment utterance (call when the user taps record). */
    fun markVoicePrintEnrollmentStart() {
        if (!voicePrintCaptureEnabled.get()) return
        enrollmentPeakRms = 0f
        enrollmentListenActive.set(true)
        voicePrintRingBuffer.markWake(0)
        enrollmentStartMark = voicePrintRingBuffer.currentWakeMark()
    }

    /** Freeze enrollment peak tracking after the guided listen window (peak value is retained). */
    fun stopVoicePrintEnrollmentListen() {
        enrollmentListenActive.set(false)
    }

    /**
     * Same extract as a live wake: engine window seated on the guided listen.
     * Mark sits [beforeMs] after the tap so the clip is phrase + tail, not a 2 s dump.
     */
    fun captureVoicePrintEnrollmentPcm(engine: WakeWordEngine = wakeWordEngine): ShortArray? {
        enrollmentListenActive.set(false)
        if (!voicePrintCaptureEnabled.get()) return null
        val window = VoicePrintWakeWindows.forEngine(engine)
        val start = enrollmentStartMark
        if (start >= 0) {
            val mark = start + window.beforeMs.toLong() * voicePrintSampleRate / 1000L
            extractVoicePrintPcm(mark, engine)?.takeIf { it.isNotEmpty() }?.let { return it }
            voicePrintRingBuffer.extractWindow(start, beforeMs = 0, afterMs = window.totalMs)
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return voicePrintRingBuffer.extractTrailingWindow(window.totalMs)
    }

    /**
     * Buffer processed mic PCM for the noise suppression / echo cancellation settings cards, so
     * the user hears exactly what the current setup hands to detection and Home Assistant.
     * [includeRaw] also keeps the pre-AEC frame of the same pass for an A/B echo comparison.
     */
    fun startMicPreviewCapture(includeRaw: Boolean = false): Boolean {
        if (!enabled) return false
        micPreviewSamples = 0
        micPreviewRawEnabled.set(includeRaw)
        micPreviewCaptureEnabled.set(true)
        return true
    }

    /** @return the clip recorded since [startMicPreviewCapture], or null when it is too short. */
    fun stopMicPreviewCapture(): MicPreviewClip? {
        micPreviewCaptureEnabled.set(false)
        val withRaw = micPreviewRawEnabled.getAndSet(false)
        val durationMs = micPreviewSamples * 1000 / MIC_PREVIEW_SAMPLE_RATE
        if (durationMs <= 0) return null
        val processed = micPreviewRingBuffer.extractTrailingWindow(durationMs) ?: return null
        val raw = if (withRaw) micPreviewRawRingBuffer.extractTrailingWindow(durationMs) else null
        return MicPreviewClip(processed = processed, raw = raw)
    }

    fun extractVoicePrintPcm(engine: WakeWordEngine): ShortArray? {
        val window = VoicePrintWakeWindows.forEngine(engine)
        return voicePrintRingBuffer.extractWindow(window.beforeMs, window.afterMs)
    }

    fun extractVoicePrintPcm(mark: Long, engine: WakeWordEngine): ShortArray? {
        val window = VoicePrintWakeWindows.forEngine(engine)
        return voicePrintRingBuffer.extractWindow(mark, window.beforeMs, window.afterMs)
    }

    fun voicePrintDebugSnapshot(): String = voicePrintRingBuffer.debugSnapshot()

    /**
     * Offline sample compare: extract the wake PCM window and re-score the same
     * model on that contiguous clip. Missing audio / load errors only fail-open when
     * streaming already cleared the model-recommended bar — otherwise reject.
     */
    suspend fun verifyWakeSample(
        wakeWordId: String,
        wakeWordPhrase: String,
        streamingConfidence: Float,
    ): Boolean {
        val duringFarEnd = wakeMarkDuringFarEnd.getAndSet(false)

        // Echo screen against the playback reference (both engines). The near-word
        // class — TTS saying "hey Travis" fires a hey_jarvis model at 0.94+ even at a
        // −30 dB residual — passes AEC, VAD, and the TTS-text screen, because no gate
        // knows what the speaker actually said. The reference does: if the audio we
        // just played scores the same model, this wake is our own playback.
        // A genuine barge-in wake leaves the reference clean, so it still passes.
        if (duringFarEnd && referenceEchoScoresWake(wakeWordId, wakeWordPhrase)) {
            Log.i(
                TAG,
                "wake sample gate allowed=false streamConf=${"%.2f".format(streamingConfidence)} " +
                    "reason=reference_echo (playback itself scores id=$wakeWordId)",
            )
            return false
        }

        val extraLevel = extraStrictnessFor(wakeWordId)

        if (wakeWordEngine == WakeWordEngine.OPEN_WAKE_WORD) {
            // At default strictness openWakeWord keeps its historical behavior: streaming
            // gates (hit gate, VAD, energy lookback) carry the false-positive work and no
            // offline re-score runs. Extra strictness level 1+ re-scores the mic clip on
            // the persistent offline engine — the old reason to skip (a fresh ~1 s engine
            // build per wake) no longer holds, the engine persists per active-set and is
            // prewarmed when a level is set.
            if (extraLevel < 1) return true
            return verifyOpenWakeSample(wakeWordId, streamingConfidence, extraLevel)
        }
        if (wakeWordEngine != WakeWordEngine.MICRO_WAKE_WORD) return true
        val forcePrecision = extraLevel >= 1

        val wakeMeta = availableWakeWords.firstOrNull { it.id == wakeWordId }
            ?: availableWakeWords.firstOrNull {
                it.wakeWord.wake_word.equals(wakeWordPhrase, ignoreCase = true)
            }
        if (wakeMeta == null || wakeMeta.wakeWord.type != "micro") {
            Log.d(TAG, "wake sample verify: no micro model for id=$wakeWordId — allow")
            return true
        }

        val manifestCutoff = wakeMeta.wakeWord.micro.probability_cutoff
        val streamingCutoff = currentWakeWordDetector
            ?.let { (it as? WakeWordDetector)?.getProbabilityCutoff(wakeWordId) }
            ?: manifestCutoff

        // Strong far-end scores can skip offline verify (residual echo still muddies the
        // clip a little). Weaker scores fall through: the sample ring follows the streaming
        // detection source (cancelled during far-end), so offline re-score stays meaningful.
        if (duringFarEnd &&
            WakeWordCutoffPolicy.allowFarEndVerifySkip(
                streamingConfidence,
                manifestCutoff,
                streamingCutoff,
                forcePrecision = forcePrecision,
            )
        ) {
            Log.i(
                TAG,
                "wake sample gate allowed=true streamConf=${"%.2f".format(streamingConfidence)} " +
                    "reason=skip_during_far_end (strong stream)",
            )
            return true
        }

        fun allowUnverified(reason: String): Boolean {
            // Level 1 still uses the tight offline slack when a clip exists;
            // missing evidence fail-opens so Strict+ cannot go fully deaf.
            val allowed = WakeWordCutoffPolicy.allowUnverifiedStreamingWake(
                streamingConfidence,
                manifestCutoff,
                streamingCutoff,
                forcePrecision = extraLevel >= 2,
            )
            val floor = WakeWordCutoffPolicy.unverifiedWakeFloor(manifestCutoff, streamingCutoff)
            Log.w(
                TAG,
                "wake sample verify: $reason — " +
                    if (allowed) {
                        "fail-open streamConf=${"%.2f".format(streamingConfidence)} " +
                            "floor=${"%.2f".format(floor)}"
                    } else {
                        "fail-closed streamConf=${"%.2f".format(streamingConfidence)} " +
                            "floor=${"%.2f".format(floor)}"
                    },
            )
            return allowed
        }

        val engine = wakeWordEngine
        val window = VoicePrintWakeWindows.forEngine(engine)
        val wakeMark = snapshotVoicePrintWakeMark()
        if (wakeMark < 0) {
            return allowUnverified("no mark snap=${voicePrintDebugSnapshot()}")
        }

        val deadline = window.afterMs + 400
        var waited = 0
        while (
            waited < deadline &&
            !voicePrintRingBuffer.samplesAvailableAfterMark(wakeMark, window.afterMs)
        ) {
            kotlinx.coroutines.delay(10)
            waited += 10
        }

        if (!voicePrintRingBuffer.samplesAvailableAfterMark(wakeMark, window.afterMs)) {
            return allowUnverified(
                "full window unavailable waited=${waited}ms mark=$wakeMark " +
                    "snap=${voicePrintDebugSnapshot()}",
            )
        }

        val pcm = extractVoicePrintPcm(wakeMark, engine)

        if (pcm == null || pcm.isEmpty()) {
            return allowUnverified(
                "extract failed waited=${waited}ms mark=$wakeMark " +
                    "snap=${voicePrintDebugSnapshot()}",
            )
        }

        return try {
            val model = wakeWordProvider.loadWakeWordModel(wakeMeta.wakeWord.model)
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                WakeSampleVerifier.verify(
                    pcm16Mono = pcm,
                    model = model,
                    wakeWordId = wakeMeta.id,
                    wakeWordPhrase = wakeMeta.wakeWord.wake_word,
                    probabilityCutoff = streamingCutoff,
                    slidingWindowSize = wakeMeta.wakeWord.micro.sliding_window_size,
                    manifestCutoff = manifestCutoff,
                    forcePrecision = forcePrecision,
                )
            }
            Log.i(
                TAG,
                "wake sample gate allowed=${result.confirmed} streamConf=${"%.2f".format(streamingConfidence)} " +
                    "peak=${"%.3f".format(result.peakAverage)} detections=${result.detections} " +
                    "verifyCutoff=${"%.3f".format(result.verifyCutoff)} " +
                    "${result.elapsedMs}ms waited=${waited}ms",
            )
            result.confirmed
        } catch (e: Exception) {
            Log.w(TAG, "wake sample verify error", e)
            allowUnverified("exception ${e.javaClass.simpleName}")
        }
    }

    /**
     * Extra-strictness (level 1+) offline confirmation for openWakeWord: extract the wake
     * PCM window from the sample ring and burst-score it on the detector's persistent
     * offline engine (same thresholds, gates, and verifier heads as live streaming).
     *
     * Level 1 fail-opens on missing evidence (no mark / ring underrun / extract
     * failure) so Strict+ cannot go deaf when streaming already cleared. Level 2
     * stays fail-closed — that is the Max contract.
     */
    private suspend fun verifyOpenWakeSample(
        wakeWordId: String,
        streamingConfidence: Float,
        extraLevel: Int,
    ): Boolean {
        fun rejected(reason: String): Boolean {
            val allow = OpenWakeWordCutoffPolicy.extraStrictnessAllowUnverified(extraLevel)
            Log.w(
                TAG,
                "open wake sample verify: $reason — " +
                    if (allow) {
                        "fail-open (extra L1, no clip) "
                    } else {
                        "fail-closed (extra strictness) "
                    } +
                    "streamConf=${"%.2f".format(streamingConfidence)}",
            )
            return allow
        }

        val detector = currentWakeWordDetector as? OpenWakeWordDetector
            ?: return rejected("no open detector")

        val engine = wakeWordEngine
        val window = VoicePrintWakeWindows.forEngine(engine)
        val wakeMark = snapshotVoicePrintWakeMark()
        if (wakeMark < 0) return rejected("no mark snap=${voicePrintDebugSnapshot()}")

        val deadline = window.afterMs + 400
        var waited = 0
        // Official hey_jarvis spikes 40–120 ms AFTER detect. The 80 ms early
        // tail can cut that peak off; wait for the full 550 ms window so the
        // verifier judges phrase + quiet tail, not a truncated prefix.
        val earlyAfterMs = if (detector.hasBuiltInVerifier(wakeWordId)) {
            window.afterMs
        } else {
            80
        }
        val confirmed = com.example.ava.openwakeword.ProgressiveWakeVerification.confirm(
            fullAfterMs = window.afterMs,
            earlyAfterMs = earlyAfterMs,
            readWindow = { afterMs ->
                while (waited < deadline &&
                    !voicePrintRingBuffer.samplesAvailableAfterMark(wakeMark, afterMs)
                ) {
                    kotlinx.coroutines.delay(10)
                    waited += 10
                }
                if (voicePrintRingBuffer.samplesAvailableAfterMark(wakeMark, afterMs)) {
                    // extractWindow clamps only the end to available audio. The
                    // same pinned start keeps this a prefix of the final window.
                    extractVoicePrintPcm(wakeMark, engine)?.takeIf { it.isNotEmpty() }
                } else null
            },
            classify = { pcm ->
                val startedNs = System.nanoTime()
                val accepted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    detector.scoreOfflinePcm(pcm, wakeWordId, extraLevel = extraLevel)
                }
                Log.i(TAG, "open wake sample confirmed=$accepted " +
                    "streamConf=${"%.2f".format(streamingConfidence)} " +
                    "samples=${pcm.size} waited=${waited}ms " +
                    "${(System.nanoTime() - startedNs) / 1_000_000}ms")
                accepted
            },
        )
        return confirmed ?: rejected("window unavailable waited=${waited}ms mark=$wakeMark")
    }

    /**
     * True when the far-end reference — the audio this device just played — itself
     * fires [wakeWordId]'s model. Only meaningful right after a wake during far-end
     * playback; every unavailable-evidence path (no software AEC pulling the bus,
     * stale history, near-silent reference, model load failure) returns false so the
     * wake falls through to the normal gates instead of being suppressed blindly.
     */
    private suspend fun referenceEchoScoresWake(
        wakeWordId: String,
        wakeWordPhrase: String,
    ): Boolean {
        val refPcm = PlaybackReferenceBus.recentReferencePcm(REF_ECHO_WINDOW_MS) ?: return false
        var energy = 0.0
        for (sample in refPcm) energy += sample.toDouble() * sample
        val rms = kotlin.math.sqrt(energy / refPcm.size)
        if (rms < REF_ECHO_MIN_RMS) return false
        return when (wakeWordEngine) {
            WakeWordEngine.OPEN_WAKE_WORD -> {
                val detector = currentWakeWordDetector as? OpenWakeWordDetector ?: return false
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    detector.scoreOfflinePcm(refPcm, wakeWordId)
                }
            }
            WakeWordEngine.MICRO_WAKE_WORD -> {
                val wakeMeta = availableWakeWords.firstOrNull { it.id == wakeWordId }
                    ?: availableWakeWords.firstOrNull {
                        it.wakeWord.wake_word.equals(wakeWordPhrase, ignoreCase = true)
                    }
                if (wakeMeta == null || wakeMeta.wakeWord.type != "micro") return false
                val streamingCutoff = currentWakeWordDetector
                    ?.let { (it as? WakeWordDetector)?.getProbabilityCutoff(wakeWordId) }
                    ?: wakeMeta.wakeWord.micro.probability_cutoff
                try {
                    val model = wakeWordProvider.loadWakeWordModel(wakeMeta.wakeWord.model)
                    val result =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            WakeSampleVerifier.verify(
                                pcm16Mono = refPcm,
                                model = model,
                                wakeWordId = wakeMeta.id,
                                wakeWordPhrase = wakeMeta.wakeWord.wake_word,
                                probabilityCutoff = streamingCutoff,
                                slidingWindowSize = wakeMeta.wakeWord.micro.sliding_window_size,
                                manifestCutoff = wakeMeta.wakeWord.micro.probability_cutoff,
                            )
                        }
                    result.confirmed
                } catch (e: Exception) {
                    Log.w(TAG, "reference echo scoring failed — allow normal wake path", e)
                    false
                }
            }
        }
    }

    /** Spec alias: AEC PCM window around the last wake mark. */
    fun recentAecPcmAroundWake(engine: WakeWordEngine = wakeWordEngine): ShortArray? =
        extractVoicePrintPcm(engine)

    fun resetWakeWordDetector() {
        currentWakeWordDetector?.reset()
    }

    /** On-device learning published a new verifier head for [wakeWordId]; pick it up live. */
    fun reloadWakeWordVerifier(wakeWordId: String) {
        currentWakeWordDetector?.reloadVerifier(wakeWordId)
    }
    
    fun updateWakeWordSensitivity(wakeWordId: String, sensitivity: Float) {
        if (sensitivity > 0f) {
            pendingSensitivities[wakeWordId] = sensitivity
            currentWakeWordDetector?.updateProbabilityCutoff(wakeWordId, sensitivity)
        }
    }
    
    private fun applyPendingSensitivities() {
        pendingSensitivities.forEach { (id, sensitivity) ->
            currentWakeWordDetector?.updateProbabilityCutoff(id, sensitivity)
        }
    }

    fun updateWakeWordExtraStrictness(wakeWordId: String, level: Int) {
        val clamped = level.coerceIn(0, 2)
        if (clamped > 0) {
            pendingExtraStrictness[wakeWordId] = clamped
        } else {
            pendingExtraStrictness.remove(wakeWordId)
        }
        currentWakeWordDetector?.updateExtraStrictness(wakeWordId, clamped)
        if (clamped >= 1) prewarmOpenVerifierAsync()
    }

    fun extraStrictnessFor(wakeWordId: String): Int {
        pendingExtraStrictness[wakeWordId]?.let { return it }
        return pendingExtraStrictness.entries.firstOrNull {
            it.key.equals(wakeWordId, ignoreCase = true)
        }?.value ?: 0
    }

    /**
     * Build the open engine's persistent offline verify engine off the audio path.
     * ~1 s of native init that must not land inside a live wake's confirmation window
     * (this class has no coroutine scope, hence the one-shot thread; the call is
     * idempotent and internally locked).
     */
    private fun prewarmOpenVerifierAsync() {
        val detector = currentWakeWordDetector as? OpenWakeWordDetector ?: return
        Thread({ runCatching { detector.prewarmOfflineVerify() } }, "oww-verify-prewarm").start()
    }

    sealed class AudioResult {
        data class Audio(val audio: ByteString) : AudioResult()
        data class WakeDetected(
            val wakeWord: String,
            val wakeWordId: String = "",
            val confidence: Float = 1f,
            /** Classifier input behind the fire, for on-device verifier learning. */
            val verifierWindow: FloatArray? = null,
        ) : AudioResult()
        data class StopDetected(val stopWord: String) : AudioResult()
        /** Near-end speech during playback or processing. [leadIn] is the recent cancelled uplink. */
        data class SpeechInsert(val leadIn: ByteString) : AudioResult()
        data class Error(val message: String, val recoverable: Boolean = false) : AudioResult()
    }

    companion object {
        private const val TAG = "VoiceSatelliteAudioInput"

        /** Covers a full wake phrase, so the chorus peak spans the whole utterance. */
        private const val WAKE_PEAK_WINDOW_MS = 1_200L

        /**
         * Wake suppression while the echo canceller is genuinely unconverged,
         * counted in far-end-active time since the last true filter reset
         * (pipeline start / speech-bypass resume) — not wall-clock, and never
         * re-armed by sentence gaps or new playback sessions: the filter keeps
         * its coefficients across pauses, so re-arming only starved barge-in.
         * Double-talk evidence ([DTD_ENERGY_MARGIN]) can pierce this window.
         */
        private const val WAKE_AEC_CONVERGE_MS = 1_500L

        /**
         * The chorus peer hold silently skips wake dispatch (by design — the seat
         * belongs to a peer). It must never be invisible in logs again: emit a
         * muted-notice at most this often while the hold is engaged.
         */
        private const val PEER_HOLD_LOG_INTERVAL_MS = 5_000L

        /**
         * Builtin-stop dispatch hold after a far-end ONSET (playback start or
         * device-volume change). Unlike [WAKE_AEC_CONVERGE_MS] this re-arms on
         * every onset: the hardware HAL canceller re-converges each time
         * playback starts, and the machine floor's learned gains under-predict
         * that transient (its double-talk gate skips learning on exactly those
         * frames), letting isolated opening words ("hello", "OK.") walk the
         * template. No DTD override — during the transient the residual itself
         * would qualify as evidence. Sentence gaps (~400 ms) do not re-arm:
         * the far-end ruler holds through them.
         */
        private const val STOP_FAR_END_ONSET_GUARD_MS = 1_500L

        /**
         * Cap on per-iteration credit toward [WAKE_AEC_CONVERGE_MS]; a scheduling
         * stall between mic reads is not adaptation time.
         */
        private const val AEC_ADAPT_MAX_TICK_MS = 100L

        /**
         * Backoff bounds for rebuilding a recorder that reported ERROR_DEAD_OBJECT. The audio
         * server needs a moment to come back after it dies, and the vendor stack sometimes
         * needs several tries, so retries widen up to the ceiling and then keep going there
         * for as long as capture is wanted.
         */
        private const val MIC_DEAD_RETRY_MIN_MS = 250L
        private const val MIC_DEAD_RETRY_MAX_MS = 5_000L

        /**
         * Double-talk margin: the detect-tap phrase-window peak must exceed the
         * predicted echo residual (echo-path gain × reference RMS) by this factor
         * (~8 dB) to count as proof of near-end speech. Echo tracks the prediction
         * by construction — including through TTS loudness swings, since the
         * prediction scales with the live reference — while a real voice on top
         * of playback does not.
         */
        private const val DTD_ENERGY_MARGIN = 2.5f

        /** Absolute detect-RMS floor for the override — near-silence proves nothing. */
        private const val DTD_MIN_TAP_RMS = 0.010f

        /** Reference RMS floor below which the echo-path ratio is not learned (silence gaps). */
        private const val DTD_MIN_REF_RMS = 0.004f

        /** EMA weight for accepted echo-path gain updates (~7 frames to settle). */
        private const val DTD_GAIN_ALPHA = 0.15f

        /**
         * Echo-path gain before any measurement: assume the echo reaches the tap
         * at unity, i.e. the override starts pessimistic and only opens up once
         * echo-only frames have established the real (lower) residual level.
         */
        private const val DTD_GAIN_INIT = 1.0f

        /** Per-update ratio ceiling, bounds one anomalous frame's pull on the EMA. */
        private const val DTD_GAIN_MAX = 8f

        /** How long near-end speech must hold before an insert fires. */
        private const val SPEECH_INSERT_HOLD_MS = 280L

        /**
         * After the uplink closes, ignore this long so the tail of the sentence
         * just sent is not heard as a new one. Processing has no playback to
         * mask that tail.
         */
        private const val SPEECH_INSERT_ARM_MS = 450L

        /** Detect-RMS floor for an insert while nothing is playing. */
        private const val SPEECH_INSERT_OPEN_RMS = 0.02f

        /** Cancelled uplink kept so the sentence onset is not dropped. ~700 ms at 16 kHz. */
        private const val SPEECH_INSERT_MAX_BYTES = 16_000 * 2 * 700 / 1000

        /** Ignore wake tokens shorter than this when screening TTS text. */
        private const val MIN_WAKE_TOKEN_LEN = 3

        /**
         * Playback-duration estimate for the TTS-mentions-wake-phrase hold: a fixed
         * base plus per-character speaking time (CJK characters are whole syllables,
         * Latin characters are not). Deliberately generous — the hold only bites
         * while far-end is actually playing, so overshoot costs nothing once the
         * response ends. Capped so continuous music after a risky response cannot
         * suppress wakes for long.
         */
        private const val TTS_ECHO_RISK_BASE_MS = 5_000L
        private const val TTS_ECHO_RISK_PER_CHAR_MS = 80L
        private const val TTS_ECHO_RISK_PER_CJK_CHAR_MS = 300L
        private const val TTS_ECHO_RISK_MAX_MS = 30_000L

        /**
         * Reference window for the echo screen: a wake phrase (~1.5 s) plus the
         * reference-vs-echo lead (bulk delay + device output latency, ~0.3 s) plus
         * detector emission lag, ending at the wake fire. Micro's verifier also
         * skips its first ~350 ms as frontend warmup, which this window absorbs.
         */
        private const val REF_ECHO_WINDOW_MS = 2_600

        /**
         * Below this int16 RMS the reference window is effectively silence and cannot
         * have caused the wake — skip the model pass. Playing TTS/media measures in
         * the hundreds-to-thousands (the reference is pre-speaker, always full level).
         */
        private const val REF_ECHO_MIN_RMS = 50.0

        /**
         * True when [text] mentions any of [phrases]: either the full phrase with
         * spacing/punctuation squashed ("Hey, Jarvis!" ~ "hey jarvis"), or the
         * phrase's final word on its own token boundary — streaming models score
         * the name, so "I'm Jarvis" self-wakes a "hey jarvis" model. Boundaries
         * only exclude adjacent Latin letters/digits: CJK text sets Latin names
         * without spaces ("I am Jarvis" with no space), which must still match, while "available"
         * must not match a wake word "Ava".
         */
        internal fun ttsTextMentionsWakePhrase(text: String, phrases: List<String>): Boolean {
            if (text.isBlank()) return false
            val lower = text.lowercase()
            val squashed = lower.filter { it.isLetterOrDigit() }
            for (phrase in phrases) {
                val tokens = phrase.trim().split(WAKE_PHRASE_SEPARATOR).filter { it.isNotBlank() }
                // Squashed full-phrase match only for multi-token phrases ("Hey, Jarvis!"
                // ~ "heyjarvis"): a single-token phrase has no boundaries after squashing,
                // so "Ava" would match inside "available". Single tokens are covered by
                // the boundary regex below.
                if (tokens.size > 1) {
                    val phraseSquashed = phrase.lowercase().filter { it.isLetterOrDigit() }
                    if (phraseSquashed.length >= MIN_WAKE_TOKEN_LEN &&
                        squashed.contains(phraseSquashed)
                    ) {
                        return true
                    }
                }
                val name = tokens.lastOrNull()?.lowercase()
                if (name == null || name.length < MIN_WAKE_TOKEN_LEN) continue
                val boundary = Regex("(?<![a-z0-9])${Regex.escape(name)}(?![a-z0-9])")
                if (boundary.containsMatchIn(lower)) return true
                val aliases = WAKE_NAME_CJK_ALIASES[name]
                if (aliases != null && aliases.any { alias -> lower.contains(alias) }) return true
            }
            return false
        }

        private val WAKE_PHRASE_SEPARATOR = Regex("[\\s_]+")

        /**
         * CJK transliterations of wake-word name tokens. The Latin screen above cannot
         * match these (the transliteration "Jiaweisi" never contains "jarvis"), yet their TTS echo can score on
         * the model — "Jiaweisi" measured 0.57 against hey_jarvis at a −12 dB echo residual.
         * Keyed by the Latin name token; entries are added on measured evidence plus
         * their common translation variants, since a spurious hold only bites while
         * far-end audio is actually playing.
         */
        private val WAKE_NAME_CJK_ALIASES = mapOf(
            "jarvis" to listOf("贾维斯", "佳维斯", "加维斯"),
        )

        /** Preview clips are normalised by [VoicePrintPcmCapture], which always targets 16 kHz mono. */
        const val MIC_PREVIEW_SAMPLE_RATE = 16_000
        const val MIC_PREVIEW_MAX_MS = 10_000
        private const val MIC_PREVIEW_MAX_SAMPLES = MIC_PREVIEW_MAX_MS * MIC_PREVIEW_SAMPLE_RATE / 1000

        private fun newMicPreviewRingBuffer() = VoicePrintRingBuffer(
            sampleRate = MIC_PREVIEW_SAMPLE_RATE,
            capacitySeconds = MIC_PREVIEW_MAX_MS / 1000f + 1f,
        )

        /** Target RMS — slightly below reference so casual/unstressed speech is not overdriven. */
        private const val VS_TARGET_FLOAT_RMS = 0.065f
        private const val VS_GAIN_MIN_LINEAR = 0.45f   // ~-7 dB
        private const val VS_GAIN_MAX_LINEAR = 3.5f    // adaptive probe component
        private const val VS_GAIN_MAX_TOTAL = 5.5f     // hard cap — 8× clipped phonemes badly

        private fun isPreferredVsCaptureProfile(profile: DeviceAudioProfile): Boolean =
            profile.id == DeviceAudioProfile.VOICE_RECOGNITION_STEREO_48K.id

        /**
         * Initial vsWakeWord gain from probe levels + a capped portion of user stream mic gain.
         * Probe windows often catch speech; cap the reference so runtime quiet speech is not left
         * at gain=1.0 (which happened when procRms≥0.035 forced no boost).
         */
        private fun computeVsInputGainLinear(
            probeProcessedRms: Float,
            probeProcessedPeak: Float,
            streamMicGainLinear: Float,
        ): Float {
            val quietReference = minOf(
                probeProcessedRms,
                probeProcessedPeak * 0.12f,
                0.012f,
            ).coerceAtLeast(0.0008f)
            val adaptive = (VS_TARGET_FLOAT_RMS / quietReference)
                .coerceIn(VS_GAIN_MIN_LINEAR, VS_GAIN_MAX_LINEAR)
            val userBoost = if (streamMicGainLinear <= 1f) {
                1f
            } else {
                kotlin.math.sqrt(streamMicGainLinear).coerceIn(1f, 3.5f)
            }
            // No probe-based clip cap: probe windows frequently catch TTS/media
            // playback (0.13–0.22 RMS), not user speech. Capping on that killed the
            // gain (0.6–1.2×) and starved the CTC decode — jarvis stopped decoding
            // entirely. Device evidence: gain 5.5 matched at conf 9–12 without issue.
            return (adaptive * userBoost).coerceIn(VS_GAIN_MIN_LINEAR, VS_GAIN_MAX_TOTAL)
        }

        /** Slow runtime trim so loud speech does not clip after a quiet probe. */
        private fun adaptVsInputGain(current: Float, speechPreGainRms: Float): Float {
            if (speechPreGainRms < 0.0015f) return current
            val desired = (VS_TARGET_FLOAT_RMS / speechPreGainRms)
                .coerceIn(VS_GAIN_MIN_LINEAR, VS_GAIN_MAX_TOTAL)
            return (current * 0.9f + desired * 0.1f).coerceIn(VS_GAIN_MIN_LINEAR, VS_GAIN_MAX_TOTAL)
        }

        /**
         * vsWakeWord models expect ±1 float PCM like the browser reference. Keep each
         * profile's native AudioSource (VOICE_RECOGNITION, MIC, UNPROCESSED, …) but
         * disable hardware NS/AGC/AEC — those effects often gate the mic to near-silence.
         */
        private fun vsWakeWordCaptureConfig(
            config: AudioConfig,
            profile: DeviceAudioProfile,
            audioSource: Int = profile.captureAudioSource,
        ): AudioConfig = config.copy(
            audioSource = audioSource,
            noiseSuppressorEnabled = false,
            automaticGainControlEnabled = false,
            acousticEchoCancelerEnabled = false,
        )

        /**
         * Ordered capture profiles for vsWakeWord. Probe known-good presets first; many
         * devices only deliver live mic PCM at 48 kHz stereo VOICE_RECOGNITION/MIC while
         * UNPROCESSED or 16 kHz mono reads all-zero.
         */
        private fun vsWakeWordCaptureProfileCandidates(userProfile: DeviceAudioProfile): List<DeviceAudioProfile> {
            val knownIds = setOf(
                DeviceAudioProfile.VOICE_RECOGNITION_STEREO_48K.id,
                DeviceAudioProfile.STEREO_INPUT_48K.id,
                DeviceAudioProfile.BROADCAST_48K_MONO.id,
                DeviceAudioProfile.STD_16K_MONO.id,
                DeviceAudioProfile.UNPROCESSED_48K.id,
                DeviceAudioProfile.LOW_LATENCY_16K.id,
                DeviceAudioProfile.VOICE_CALL_16K.id,
            )
            return buildList {
                add(DeviceAudioProfile.VOICE_RECOGNITION_STEREO_48K)
                add(DeviceAudioProfile.STEREO_INPUT_48K)
                add(DeviceAudioProfile.BROADCAST_48K_MONO)
                add(DeviceAudioProfile.STD_16K_MONO)
                add(DeviceAudioProfile.UNPROCESSED_48K)
                add(DeviceAudioProfile.LOW_LATENCY_16K)
                add(DeviceAudioProfile.VOICE_CALL_16K)
                if (userProfile.id !in knownIds) add(userProfile)
            }.distinctBy { it.id }
        }

        private data class VsCaptureProbeTarget(
            val profile: DeviceAudioProfile,
            val audioSource: Int,
            val label: String,
        )

        /** Primary profile source plus UNPROCESSED/MIC alternates when they differ. */
        private fun vsCaptureProbeTargets(profiles: List<DeviceAudioProfile>): List<VsCaptureProbeTarget> {
            val targets = mutableListOf<VsCaptureProbeTarget>()
            for (profile in profiles) {
                targets += VsCaptureProbeTarget(
                    profile = profile,
                    audioSource = profile.captureAudioSource,
                    label = "${profile.id}@${profile.captureAudioSource}",
                )
                val alternate = when (profile.captureAudioSource) {
                    MediaRecorder.AudioSource.UNPROCESSED -> MediaRecorder.AudioSource.MIC
                    MediaRecorder.AudioSource.MIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        MediaRecorder.AudioSource.UNPROCESSED
                    } else {
                        null
                    }
                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        MediaRecorder.AudioSource.UNPROCESSED
                    } else {
                        MediaRecorder.AudioSource.MIC
                    }
                }
                if (alternate != null && alternate != profile.captureAudioSource) {
                    targets += VsCaptureProbeTarget(
                        profile = profile,
                        audioSource = alternate,
                        label = "${profile.id}@$alternate",
                    )
                }
            }
            return targets.distinctBy { "${it.profile.id}:${it.audioSource}" }
        }

        private data class VsProbeResult(
            val profile: DeviceAudioProfile,
            val mic: MicrophoneInput,
            val audioSource: Int,
            val downmixMode: StereoDownmixMode,
            val processedMaxRms: Float,
            val processedMaxPeak: Float,
        )

        private const val PROBE_READS_QUICK = 36
        private const val PROBE_READS_REFINE = 48
        /** Good enough to start wake-word immediately (skip remaining probe targets). */
        private const val PROBE_STRONG_PEAK = 0.08f
        private const val PROBE_STRONG_RMS = 0.03f
        /** Good enough to skip alternate AudioSources for the same profile. */
        private const val PROBE_GOOD_PEAK = 0.02f
        private const val PROBE_GOOD_RMS = 0.008f

        private fun quickStereoDownmixCandidates(profile: DeviceAudioProfile): List<StereoDownmixMode> {
            if (profile.captureChannelCount < 2) return listOf(StereoDownmixMode.DOMINANT_OR_AVERAGE)
            return listOf(StereoDownmixMode.DOMINANT_OR_AVERAGE, StereoDownmixMode.MAX_CHANNEL)
        }

        private fun stereoDownmixCandidates(profile: DeviceAudioProfile): List<StereoDownmixMode> {
            if (profile.captureChannelCount < 2) return listOf(StereoDownmixMode.DOMINANT_OR_AVERAGE)
            return listOf(
                StereoDownmixMode.DOMINANT_OR_AVERAGE,
                StereoDownmixMode.MAX_CHANNEL,
                StereoDownmixMode.LEFT_ONLY,
                StereoDownmixMode.RIGHT_ONLY,
                StereoDownmixMode.AVERAGE,
            )
        }

        private fun isStrongCapture(processedMaxPeak: Float, processedMaxRms: Float): Boolean =
            processedMaxPeak >= PROBE_STRONG_PEAK || processedMaxRms >= PROBE_STRONG_RMS

        private fun isGoodCapture(processedMaxPeak: Float, processedMaxRms: Float): Boolean =
            processedMaxPeak >= PROBE_GOOD_PEAK || processedMaxRms >= PROBE_GOOD_RMS

        private fun vsCapturePrimaryTargets(profiles: List<DeviceAudioProfile>): List<VsCaptureProbeTarget> =
            profiles.map { profile ->
                VsCaptureProbeTarget(
                    profile = profile,
                    audioSource = profile.captureAudioSource,
                    label = "${profile.id}@${profile.captureAudioSource}",
                )
            }

        private fun vsCaptureAlternateTargets(profiles: List<DeviceAudioProfile>): List<VsCaptureProbeTarget> =
            vsCaptureProbeTargets(profiles).filter { it.audioSource != it.profile.captureAudioSource }

        private fun probeScore(
            processedMaxRms: Float,
            processedMaxPeak: Float,
            rawMaxRms: Float,
            rawMaxPeak: Float,
            processedNonZeroRatio: Float,
            rawNonZeroRatio: Float,
            alive: Boolean,
        ): Float {
            var score = processedMaxRms * 10000f +
                processedMaxPeak * 1000f +
                rawMaxRms * 5000f +
                rawMaxPeak * 500f +
                processedNonZeroRatio * 200f +
                rawNonZeroRatio * 100f
            if (!alive) score *= 0.01f
            return score
        }

        private fun isAliveCapture(
            processedMaxPeak: Float,
            processedMaxRms: Float,
            processedNonZeroRatio: Float,
            rawMaxPeak: Float,
        ): Boolean = processedMaxPeak >= 0.0002f ||
            processedMaxRms >= 0.00005f ||
            processedNonZeroRatio >= 0.02f ||
            rawMaxPeak >= 0.0002f

        private fun applyVsInputGain(detector: WakeWordEngineDetector?, gainLinear: Float) {
            (detector as? OpenWakeWordDetector)?.setInputGainLinear(gainLinear)
        }

        private data class ProbeMetrics(
            val processedMaxRms: Float,
            val processedMaxPeak: Float,
            val rawMaxRms: Float,
            val rawMaxPeak: Float,
            val processedNonZeroRatio: Float,
            val rawNonZeroRatio: Float,
            val alive: Boolean,
        )

        private suspend fun measureProbeReads(
            mic: MicrophoneInput,
            profile: DeviceAudioProfile,
            downmix: StereoDownmixMode,
            reads: Int,
        ): ProbeMetrics {
            var rawMaxRms = 0f
            var rawMaxPeak = 0f
            var processedMaxRms = 0f
            var processedMaxPeak = 0f
            var processedSamples = 0
            var processedNonZero = 0
            var rawSamples = 0
            var rawNonZero = 0
            repeat(reads) {
                val raw = mic.read()
                if (raw.remaining() > 0) {
                    rawMaxRms = maxOf(rawMaxRms, AudioEnergy.pcm16LeFloatRms(raw))
                    rawMaxPeak = maxOf(rawMaxPeak, AudioEnergy.pcm16LeFloatPeak(raw))
                    val rawNz = AudioEnergy.pcm16LeNonZeroRatio(raw)
                    rawSamples++
                    if (rawNz > 0f) rawNonZero++
                    val processed = AudioFrameProcessor.process(
                        copyPcm16LeStatic(raw),
                        profile,
                        downmix,
                    )
                    if (processed.remaining() >= 2) {
                        processedMaxRms = maxOf(processedMaxRms, AudioEnergy.pcm16LeFloatRms(processed))
                        processedMaxPeak = maxOf(processedMaxPeak, AudioEnergy.pcm16LeFloatPeak(processed))
                        val procNz = AudioEnergy.pcm16LeNonZeroRatio(processed)
                        processedSamples++
                        if (procNz > 0f) processedNonZero++
                    }
                }
                yield()
            }
            val processedNonZeroRatio = if (processedSamples > 0) {
                processedNonZero.toFloat() / processedSamples.toFloat()
            } else {
                0f
            }
            val rawNonZeroRatio = if (rawSamples > 0) {
                rawNonZero.toFloat() / rawSamples.toFloat()
            } else {
                0f
            }
            val alive = isAliveCapture(
                processedMaxPeak,
                processedMaxRms,
                processedNonZeroRatio,
                rawMaxPeak,
            )
            return ProbeMetrics(
                processedMaxRms = processedMaxRms,
                processedMaxPeak = processedMaxPeak,
                rawMaxRms = rawMaxRms,
                rawMaxPeak = rawMaxPeak,
                processedNonZeroRatio = processedNonZeroRatio,
                rawNonZeroRatio = rawNonZeroRatio,
                alive = alive,
            )
        }

        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        private suspend fun probeMicrophoneProfile(
            context: Context,
            profiles: List<DeviceAudioProfile>,
            captureConfig: AudioConfig,
        ): VsProbeResult? {
            data class ProbeScore(
                val profile: DeviceAudioProfile,
                val audioSource: Int,
                val mic: MicrophoneInput,
                val downmixMode: StereoDownmixMode,
                val processedMaxRms: Float,
                val processedMaxPeak: Float,
                val rawMaxRms: Float,
                val rawMaxPeak: Float,
                val processedNonZeroRatio: Float,
                val rawNonZeroRatio: Float,
                val alive: Boolean,
            )

            fun probeResultFrom(winner: ProbeScore, reason: String): VsProbeResult {
                if (!winner.alive) {
                    Log.w(
                        TAG,
                        "vs mic probe ($reason): weak PCM — ${winner.profile.id} " +
                            "source=${winner.audioSource} downmix=${winner.downmixMode} " +
                            "procRms=${winner.processedMaxRms} procPeak=${winner.processedMaxPeak}",
                    )
                } else {
                    Log.i(
                        TAG,
                        "vs mic probe winner ($reason) profile=${winner.profile.id} source=${winner.audioSource} " +
                            "downmix=${winner.downmixMode} activeSource=${winner.mic.activeAudioSource} " +
                            "procRms=${winner.processedMaxRms} procPeak=${winner.processedMaxPeak}",
                    )
                }
                return VsProbeResult(
                    profile = winner.profile,
                    mic = winner.mic,
                    audioSource = winner.audioSource,
                    downmixMode = winner.downmixMode,
                    processedMaxRms = winner.processedMaxRms,
                    processedMaxPeak = winner.processedMaxPeak,
                )
            }

            fun scoreOf(candidate: ProbeScore): Float = probeScore(
                candidate.processedMaxRms,
                candidate.processedMaxPeak,
                candidate.rawMaxRms,
                candidate.rawMaxPeak,
                candidate.processedNonZeroRatio,
                candidate.rawNonZeroRatio,
                candidate.alive,
            )

            fun betterThan(candidate: ProbeScore, current: ProbeScore?): Boolean {
                if (current == null) return true
                return scoreOf(candidate) > scoreOf(current)
            }

            fun candidateFrom(
                target: VsCaptureProbeTarget,
                mic: MicrophoneInput,
                downmix: StereoDownmixMode,
                metrics: ProbeMetrics,
            ) = ProbeScore(
                profile = target.profile,
                audioSource = mic.activeAudioSource,
                mic = mic,
                downmixMode = downmix,
                processedMaxRms = metrics.processedMaxRms,
                processedMaxPeak = metrics.processedMaxPeak,
                rawMaxRms = metrics.rawMaxRms,
                rawMaxPeak = metrics.rawMaxPeak,
                processedNonZeroRatio = metrics.processedNonZeroRatio,
                rawNonZeroRatio = metrics.rawNonZeroRatio,
                alive = metrics.alive,
            )

            fun logProbeLine(target: VsCaptureProbeTarget, downmix: StereoDownmixMode, mic: MicrophoneInput, metrics: ProbeMetrics) {
                Log.i(
                    TAG,
                    "vs mic probe ${target.label} downmix=$downmix activeSource=${mic.activeAudioSource} " +
                        "capture=${target.profile.captureSampleRateInHz}Hz/${target.profile.captureChannelCount}ch " +
                        "rawRms=${metrics.rawMaxRms} rawPeak=${metrics.rawMaxPeak} " +
                        "rawNz=${"%.4f".format(metrics.rawNonZeroRatio)} " +
                        "procRms=${metrics.processedMaxRms} procPeak=${metrics.processedMaxPeak} " +
                        "procNz=${"%.4f".format(metrics.processedNonZeroRatio)} alive=${metrics.alive}",
                )
            }

            fun adoptCandidate(candidate: ProbeScore, best: ProbeScore?, bestAlive: ProbeScore?): Pair<ProbeScore?, ProbeScore?> {
                var nextBest = best
                var nextAlive = bestAlive
                if (candidate.alive && betterThan(candidate, nextAlive)) {
                    nextAlive?.mic?.takeIf { it !== candidate.mic }?.close()
                    nextAlive = candidate
                }
                if (betterThan(candidate, nextBest)) {
                    nextBest?.mic?.takeIf { it !== candidate.mic }?.close()
                    nextBest = candidate
                }
                return nextBest to nextAlive
            }

            Log.i(TAG, "vs mic probe quick scan — speak toward the device if you can")
            var best: ProbeScore? = null
            var bestAlive: ProbeScore? = null

            suspend fun probeTargets(
                targets: List<VsCaptureProbeTarget>,
                downmixModes: (DeviceAudioProfile) -> List<StereoDownmixMode>,
                reads: Int,
            ): ProbeScore? {
                var localStrong: ProbeScore? = null
                for (target in targets) {
                    val profile = target.profile
                    val profileCapture = vsWakeWordCaptureConfig(captureConfig, profile, target.audioSource)
                    val mic = MicrophoneInput(
                        appContext = context,
                        audioSource = profileCapture.audioSource,
                        sampleRateInHz = profile.captureSampleRateInHz,
                        channelConfig = profile.captureChannelConfig,
                        noiseSuppressorEnabled = profileCapture.noiseSuppressorEnabled,
                        automaticGainControlEnabled = profileCapture.automaticGainControlEnabled,
                        acousticEchoCancelerEnabled = profileCapture.acousticEchoCancelerEnabled,
                        strictAudioSource = true,
                        recordingPath = captureConfig.recordingPath,
                    )
                    if (!mic.start()) {
                        Log.w(TAG, "vs mic probe failed to start ${target.label}: ${mic.lastError}")
                        mic.close()
                        continue
                    }
                    mic.discardWarmupReads(12)

                    var bestPreferredForTarget: ProbeScore? = null
                    for (downmix in downmixModes(profile)) {
                        val metrics = measureProbeReads(mic, profile, downmix, reads)
                        logProbeLine(target, downmix, mic, metrics)
                        val candidate = candidateFrom(target, mic, downmix, metrics)
                        val adopted = adoptCandidate(candidate, best, bestAlive)
                        best = adopted.first
                        bestAlive = adopted.second
                        if (isPreferredVsCaptureProfile(profile) && metrics.alive) {
                            if (bestPreferredForTarget == null ||
                                scoreOf(candidate) > scoreOf(bestPreferredForTarget)
                            ) {
                                bestPreferredForTarget = candidate
                            }
                        }
                        if (metrics.alive &&
                            isStrongCapture(metrics.processedMaxPeak, metrics.processedMaxRms) &&
                            isPreferredVsCaptureProfile(profile)
                        ) {
                            localStrong = candidate
                            break
                        }
                    }
                    if (localStrong != null) {
                        if (best?.mic !== mic) mic.close()
                        return localStrong
                    }
                    if (isPreferredVsCaptureProfile(profile) && bestPreferredForTarget != null) {
                        if (best?.mic !== mic && best?.mic !== bestPreferredForTarget.mic) best?.mic?.close()
                        if (mic !== bestPreferredForTarget.mic) mic.close()
                        return bestPreferredForTarget
                    }
                    if (best?.mic !== mic) mic.close()
                }
                return null
            }

            // Phase 1: primary AudioSource + quick downmix only (~2–5 s on typical hardware).
            val strongEarly = probeTargets(
                targets = vsCapturePrimaryTargets(profiles),
                downmixModes = ::quickStereoDownmixCandidates,
                reads = PROBE_READS_QUICK,
            )
            if (strongEarly != null) {
                return probeResultFrom(strongEarly, "strong signal")
            }

            val aliveWinner = bestAlive
            if (aliveWinner != null && isGoodCapture(aliveWinner.processedMaxPeak, aliveWinner.processedMaxRms)) {
                return probeResultFrom(aliveWinner, "good primary source")
            }

            // Phase 2: refine downmix on the current best profile only.
            val refineProfile = aliveWinner?.profile ?: best?.profile
            if (refineProfile != null) {
                val refineTarget = VsCaptureProbeTarget(
                    profile = refineProfile,
                    audioSource = aliveWinner?.audioSource ?: refineProfile.captureAudioSource,
                    label = "${refineProfile.id}@refine",
                )
                val extraDownmix = stereoDownmixCandidates(refineProfile)
                    .filter { mode ->
                        mode != aliveWinner?.downmixMode && mode != best?.downmixMode
                    }
                if (extraDownmix.isNotEmpty()) {
                    val profileCapture = vsWakeWordCaptureConfig(
                        captureConfig,
                        refineProfile,
                        refineTarget.audioSource,
                    )
                    val mic = MicrophoneInput(
                        appContext = context,
                        audioSource = profileCapture.audioSource,
                        sampleRateInHz = refineProfile.captureSampleRateInHz,
                        channelConfig = refineProfile.captureChannelConfig,
                        noiseSuppressorEnabled = profileCapture.noiseSuppressorEnabled,
                        automaticGainControlEnabled = profileCapture.automaticGainControlEnabled,
                        acousticEchoCancelerEnabled = profileCapture.acousticEchoCancelerEnabled,
                        strictAudioSource = true,
                        recordingPath = captureConfig.recordingPath,
                    )
                    if (mic.start()) {
                        mic.discardWarmupReads(12)
                        for (downmix in extraDownmix) {
                            val metrics = measureProbeReads(mic, refineProfile, downmix, PROBE_READS_REFINE)
                            logProbeLine(refineTarget, downmix, mic, metrics)
                            val candidate = candidateFrom(refineTarget, mic, downmix, metrics)
                            val adopted = adoptCandidate(candidate, best, bestAlive)
                            best = adopted.first
                            bestAlive = adopted.second
                            if (metrics.alive &&
                                isStrongCapture(metrics.processedMaxPeak, metrics.processedMaxRms) &&
                                isPreferredVsCaptureProfile(refineProfile)
                            ) {
                                if (best?.mic !== mic) mic.close()
                                return probeResultFrom(candidate, "refined downmix")
                            }
                        }
                        if (bestAlive?.mic !== mic && best?.mic !== mic) mic.close()
                    } else {
                        mic.close()
                    }
                }
            }

            if (aliveWinner != null && isGoodCapture(
                    aliveWinner.processedMaxPeak,
                    aliveWinner.processedMaxRms,
                )
            ) {
                return probeResultFrom(aliveWinner, "good after refine")
            }

            // Phase 3: alternate AudioSources only when primary paths were weak.
            probeTargets(
                targets = vsCaptureAlternateTargets(profiles),
                downmixModes = ::quickStereoDownmixCandidates,
                reads = PROBE_READS_QUICK,
            )

            val winner = bestAlive ?: best ?: return null
            return probeResultFrom(winner, if (winner.alive) "fallback" else "weak fallback")
        }

        private fun copyPcm16LeStatic(source: ByteBuffer): ByteBuffer {
            val input = source.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val output = ByteBuffer.allocateDirect(input.remaining())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            output.put(input)
            output.flip()
            return output
        }
    }

    private data class AudioConfig(
        val muted: Boolean,
        val audioSource: Int,
        val noiseSuppressorEnabled: Boolean,
        val softwareNsEnabled: Boolean,
        val automaticGainControlEnabled: Boolean,
        val acousticEchoCancelerEnabled: Boolean,
        val softwareAecEnabled: Boolean,
        val softwareAecFilterLengthMs: Int,
        val softwareAecSuppressDb: Int,
        val softwareAecSuppressActiveDb: Int,
        val wakeWords: List<String>,
        val stopWakeWordId: String?,
        val micGainLinear: Float,
        val recordingPath: RecordingPath,
    )

    private data class AudioHardwareConfig(
        val audioSource: Int,
        val noiseSuppressorEnabled: Boolean,
        val softwareNsEnabled: Boolean,
        val automaticGainControlEnabled: Boolean,
        val acousticEchoCancelerEnabled: Boolean,
        val softwareAecEnabled: Boolean,
        val softwareAecFilterLengthMs: Int,
        val softwareAecSuppressDb: Int,
        val softwareAecSuppressActiveDb: Int,
        val recordingPath: RecordingPath,
    )

    private data class DetectorConfig(
        val wakeWords: List<String>,
        val stopWakeWordId: String?,
        val micGainLinear: Float
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): kotlinx.coroutines.flow.Flow<AudioResult> {
        // Both engines (micro + openWakeWord) consume the same processed 16 kHz mono
        // stream, so hardware NS/AGC/AEC and software AEC/NS follow user settings for
        // both. The old openWakeWord branch here forced HW effects off (a leftover from
        // the removed vs CTC engine) which made echo cancellation unusable on that engine.
        val hardwareFlow =
            combine(
                _audioSource,
                _noiseSuppressorEnabled,
                _automaticGainControlEnabled,
                _acousticEchoCancelerEnabled,
                _softwareAecEnabled,
            ) { audioSourceValue, nsEnabled, agcEnabled, aecEnabled, softwareAec ->
                AudioHardwareConfig(
                    audioSource = audioSourceValue,
                    noiseSuppressorEnabled = nsEnabled,
                    softwareNsEnabled = false,
                    automaticGainControlEnabled = agcEnabled,
                    // Defense in depth: never stack platform AEC with software AEC.
                    acousticEchoCancelerEnabled = aecEnabled && !softwareAec,
                    softwareAecEnabled = softwareAec,
                    softwareAecFilterLengthMs = SoftwareAecRoom.LIVING.filterLengthMs,
                    softwareAecSuppressDb = SoftwareAecStrength.STANDARD.suppressDb,
                    softwareAecSuppressActiveDb = SoftwareAecStrength.STANDARD.suppressActiveDb,
                    recordingPath = RecordingPath.AUTO, // replaced below
                )
            }.combine(_softwareNsEnabled) { hardware, softwareNs ->
                hardware.copy(
                    // Defense in depth: never stack platform NS with software NS.
                    noiseSuppressorEnabled = hardware.noiseSuppressorEnabled && !softwareNs,
                    softwareNsEnabled = softwareNs,
                )
            }.combine(_recordingPath) { hardware, path ->
                hardware.copy(recordingPath = path)
            }.combine(_softwareAecStrength) { hardware, strength ->
                hardware.copy(
                    softwareAecSuppressDb = strength.suppressDb,
                    softwareAecSuppressActiveDb = strength.suppressActiveDb,
                )
            }.combine(_softwareAecRoom) { hardware, room ->
                hardware.copy(softwareAecFilterLengthMs = room.filterLengthMs)
            }

        return hardwareFlow.combine(
        combine(
            activeWakeWords,
            _stopWakeWordId,
            _micGainDb
        ) { wakeWords, stopWakeWordId, micGainValue ->
            DetectorConfig(
                wakeWords = wakeWords,
                stopWakeWordId = stopWakeWordId,
                micGainLinear = 10f.pow(micGainValue / 20f)
            )
        }
    ) { hardware, detector ->
        hardware to detector
    }.combine(
        combine(muted, _temporaryPaused) { mutedValue, pausedValue ->
            mutedValue || pausedValue
        }
    ) { (hardware, detector), paused ->
        AudioConfig(
            muted = paused,
            audioSource = hardware.audioSource,
            noiseSuppressorEnabled = hardware.noiseSuppressorEnabled,
            softwareNsEnabled = hardware.softwareNsEnabled,
            automaticGainControlEnabled = hardware.automaticGainControlEnabled,
            acousticEchoCancelerEnabled = hardware.acousticEchoCancelerEnabled,
            softwareAecEnabled = hardware.softwareAecEnabled,
            softwareAecFilterLengthMs = hardware.softwareAecFilterLengthMs,
            softwareAecSuppressDb = hardware.softwareAecSuppressDb,
            softwareAecSuppressActiveDb = hardware.softwareAecSuppressActiveDb,
            wakeWords = detector.wakeWords,
            stopWakeWordId = detector.stopWakeWordId,
            micGainLinear = detector.micGainLinear,
            recordingPath = hardware.recordingPath,
        )
    }.flatMapLatest { config ->
        if (!enabled || config.muted) emptyFlow()
        else flow {
            // The pipeline body lives in [CaptureSession]. As one flow lambda the
            // whole loop compiled to a single ~24k-instruction invokeSuspend, past
            // ART's JIT method limit ("Method exceeds compiler instruction limit"),
            // so this hot path ran interpreted for the life of the process.
            val session = CaptureSession(config)
            try {
                if (session.open(this)) {
                    while (true) {
                        session.step(this)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission denied", e)
                emit(AudioResult.Error("Microphone permission denied", recoverable = true))
            } catch (e: Exception) {
                Log.e(TAG, "Audio input error", e)
                emit(AudioResult.Error("Audio error: ${e.message}", recoverable = false))
            } finally {
                session.close()
            }
        }
    }
    }

    /** Per-frame values shared by the capture stages of one [CaptureSession.step]. */
    private class CaptureFrame(
        val rawAudio: ByteBuffer,
        val processedAudio: ByteBuffer,
        val vsWakeWordPcm: ByteBuffer?,
        val pauseAecForSpeech: Boolean,
        val streamAudio: ByteBuffer,
        /** Far-end ruler: software AEC live and playback reference active. */
        val aecBargeInActive: Boolean,
        val nowMs: Long,
        val aecConverging: Boolean,
        val ttsEchoAudible: Boolean,
        /** Frame-aligned wake-detection tap from the AEC; null when bypassed. */
        val detectTap: ByteBuffer?,
    )

    private class CaptureMetrics(
        val preGainDetectRms: Float,
        /** Hardware-AEC ring chunk length read into the scratch buffer (0 = none). */
        val stopRefCount: Int,
        /** Same far-end ruler the stop onset guard uses. */
        val farEndActive: Boolean,
    )

    private class DetectGates(
        val dtdExpectedNow: Float,
        val dtdBargeInEvidence: Boolean,
        val stopGateOpen: Boolean,
    )

    /**
     * One run of the capture pipeline for a single [AudioConfig]: mic + AEC/NS +
     * wake/stop detectors, driven frame by frame from the `flow {}` in [start].
     *
     * Kept as small suspend methods on purpose: ART refuses to JIT-compile a
     * method above ~10k dex instructions, and the previous single-lambda form of
     * this loop was ~24k.
     */
    private inner class CaptureSession(private val config: AudioConfig) {
        private var microphoneInput: MicrophoneInput? = null
        private var softwareAec: SoftwareAecProcessor? = null
        private var softwareNs: SoftwareNsProcessor? = null
        private var wakeWordDetector: WakeWordEngineDetector? = null
        private var stopWordDetector: StopWordDetector? = null
        private var stopWordDetectionWasEnabled = false
        private val speechInsertRing = ArrayDeque<ByteArray>()
        private var speechInsertRingBytes = 0
        private var speechInsertSinceMs = 0L
        private var speechInsertArmAtMs = 0L
        private var speechInsertLatched = false
        private var dispatchClaimed = false
        // Far-end onset guard for the builtin stop dispatch: wall-clock deadline
        // and the previous far-end state for edge detection.
        private var stopFarEndGuardUntilMs = 0L
        private var stopFarEndWasActive = false
        // Rate limiter for the peer-chorus-hold mute log in the detection loop.
        private var lastPeerHoldLogMs = 0L

        // openWakeWord runs on the standard capture path (same as micro): user's
        // audio profile, HW NS/AGC/AEC per settings, software AEC/NS applied, and
        // the detector fed the echo-cancelled tap during playback. The vs capture
        // override (48 kHz raw probe, HW effects forced off, float auto-gain) was
        // for the removed CTC engine and must stay disabled — it blocked both
        // hardware and software echo cancellation on openWakeWord.
        private val usesVsWakeWordEngine = false
        private var captureConfig: AudioConfig = config
        private var profileCandidates: List<DeviceAudioProfile> = listOf(audioProfile)
        private var vsProbeResult: VsProbeResult? = null
        private var vsFloatGain = 1f
        private var vsActiveGain = 1f
        private var vsAppliedGain = 1f
        private var effectiveProfile: DeviceAudioProfile = audioProfile
        private var effectiveAudioSource: Int = config.audioSource
        private var effectiveDownmix: StereoDownmixMode = StereoDownmixMode.DOMINANT_OR_AVERAGE
        private var effectiveCapture: AudioConfig = config

        private var softwareAecPausedForSpeech = false
        // Hardware-AEC ring-reader scratch (see [measure]).
        private var stopRefScratch = ShortArray(0)
        // Far-end-active time accumulated since the last true filter reset
        // (pipeline start here, or the speech-bypass resume in the loop).
        // The adaptive filter converges only while it sees far-end signal
        // and keeps its coefficients across playback pauses, so sentence
        // gaps and new TTS sessions must NOT re-arm the wake suppression
        // window — the old gap-based re-arm starved barge-in for every
        // sentence of a multi-sentence response.
        private var aecAdaptedMs = 0L
        private var aecAdaptLastTickMs = 0L
        // Double-talk ruler state: echo-path gain (detect-tap RMS per unit
        // of reference RMS) and a windowed peak of the predicted residual.
        private var dtdEchoPathGain = DTD_GAIN_INIT
        private var dtdExpectedPeak = 0f
        private var dtdExpectedPeakAtMs = 0L

        private var detectFrameCount = 0
        private var vsLowEnergyStreak = 0
        private var vsPeakDetectRms = 0f
        private var vsPeakRawRms = 0f
        private val triedVsProfileIds = mutableSetOf<String>()
        private val triedDownmixModes = mutableSetOf<StereoDownmixMode>()
        private var vsProfileSwaps = 0
        private var vsDownmixSwaps = 0
        private var micDeadRetryDelayMs = MIC_DEAD_RETRY_MIN_MS

        /**
         * Opens mic, AEC/NS and detectors. Returns false after emitting the start
         * error; the caller still runs [close].
         */
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        suspend fun open(out: FlowCollector<AudioResult>): Boolean {
            if (usesVsWakeWordEngine) {
                captureConfig = vsWakeWordCaptureConfig(config, audioProfile)
                profileCandidates = vsWakeWordCaptureProfileCandidates(audioProfile)
                vsProbeResult = probeMicrophoneProfile(appContext, profileCandidates, captureConfig)
            }
            val probe = vsProbeResult
            vsFloatGain = if (usesVsWakeWordEngine) {
                computeVsInputGainLinear(
                    probeProcessedRms = probe?.processedMaxRms ?: 0f,
                    probeProcessedPeak = probe?.processedMaxPeak ?: 0f,
                    streamMicGainLinear = captureConfig.micGainLinear,
                )
            } else {
                // Detection stays at natural mic level: user mic gain is for the HA
                // uplink only. Boosting detect PCM lifts background into false wakes
                // (same doctrine as micro/audio-event, see comments further down).
                1f
            }
            vsActiveGain = vsFloatGain
            vsAppliedGain = vsFloatGain
            effectiveProfile = probe?.profile ?: profileCandidates.first()
            effectiveAudioSource = probe?.audioSource
                ?: vsWakeWordCaptureConfig(captureConfig, effectiveProfile).audioSource
            effectiveDownmix = probe?.downmixMode ?: StereoDownmixMode.DOMINANT_OR_AVERAGE
            effectiveCapture = if (usesVsWakeWordEngine) {
                vsWakeWordCaptureConfig(captureConfig, effectiveProfile, effectiveAudioSource)
            } else {
                captureConfig
            }

            val mic = probe?.mic ?: MicrophoneInput(
                appContext = appContext,
                audioSource = effectiveCapture.audioSource,
                sampleRateInHz = effectiveProfile.captureSampleRateInHz,
                channelConfig = effectiveProfile.captureChannelConfig,
                noiseSuppressorEnabled = effectiveCapture.noiseSuppressorEnabled,
                automaticGainControlEnabled = effectiveCapture.automaticGainControlEnabled,
                acousticEchoCancelerEnabled = effectiveCapture.acousticEchoCancelerEnabled,
                strictAudioSource = usesVsWakeWordEngine,
                recordingPath = effectiveCapture.recordingPath,
            )
            microphoneInput = mic

            if (usesVsWakeWordEngine && probe == null) {
                Log.w(
                    TAG,
                    "vs mic probe could not open any profile — using profile=${effectiveProfile.id} " +
                        "source=${effectiveCapture.audioSource} (strict, no VOICE_RECOGNITION fallback)",
                )
            }

            val aec = if (config.softwareAecEnabled) {
                SoftwareAecProcessor(
                    filterLengthMs = config.softwareAecFilterLengthMs,
                    suppressDb = config.softwareAecSuppressDb,
                    suppressActiveDb = config.softwareAecSuppressActiveDb,
                )
            } else {
                null
            }
            softwareAec = aec
            // Hold the far-end bus for the life of the capture flow. With software
            // AEC the canceller consumes the ring; on the hardware-AEC profile (no
            // SoftwareAecProcessor) the builtin stop DSP reads it instead — its
            // machine floor needs the playback reference, and writers skip mixing
            // entirely while nobody holds the bus, which left that path with an
            // all-zero reference (env=0) and every barge-in "stop" during TTS
            // rejected as continuing speech.
            if (aec == null) {
                // Ring-reader anchor for the hardware-AEC path: keep the
                // reference only slightly ahead of the writers so it always
                // LEADS the acoustic echo (HAL output latency is >= ~60 ms).
                // The stop DSP's reference hold window covers lead up to
                // 160 ms; lag it cannot cover. A software canceller sets
                // this itself to match its engine.
                PlaybackReferenceBus.bulkDelayMs = PlaybackReferenceBus.AEC3_BULK_DELAY_MS
            }
            PlaybackReferenceBus.resetReader()
            if (PlaybackReferenceBus.acquire()) {
                ModPlaybackReference.onBusActiveChanged(appContext, true)
            }

            val ns = if (config.softwareNsEnabled) {
                SoftwareNsProcessor(mode = _softwareNsStrength.value.webrtcMode)
            } else {
                null
            }
            softwareNs = ns
            liveSoftwareNs.set(ns)

            val detector = WakeWordDetectorFactory.create(
                engine = wakeWordEngine,
                wakeWordProvider = wakeWordProvider,
                openWakeWordProvider = vsWakeWordProvider,
                vadProvider = AssetWakeWordProvider(appContext.assets, VAD_ASSET_PATH),
                learnStore = com.example.ava.wakelearn.WakeLearnStore.forContext(appContext),
            )
            wakeWordDetector = detector
            applyVsInputGain(detector, vsActiveGain)
            detector.apply {
                // Extra strictness first: the open engine bakes the hit gate into its
                // native keyword configs, so levels known before the load avoid an
                // immediate rebuild.
                pendingExtraStrictness.forEach { (id, level) ->
                    updateExtraStrictness(id, level)
                }
                // Stop-slot model rides the same engine pass; its id never enters
                // activeWakeWords (HA config / settings write-back must not see it).
                setActiveWakeWords(
                    (config.wakeWords + listOfNotNull(config.stopWakeWordId)).distinct(),
                )
            }
            currentWakeWordDetector = detector
            applyPendingSensitivities()
            if (pendingExtraStrictness.values.any { it >= 1 }) prewarmOpenVerifierAsync()

            triedVsProfileIds.add("${effectiveProfile.id}@${effectiveAudioSource}")
            triedDownmixModes.add(effectiveDownmix)

            // Model-free native DSP "stop" detector — no TFLite model, no manifest.
            // Gain-invariant by design, so mic-gain adaptation is not applied to it.
            stopWordDetector = StopWordDetector()

            val started = probe != null || mic.start()
            if (!started) {
                val errorMsg = mic.lastError ?: "Unknown error starting microphone"
                Log.e(TAG, "Failed to start microphone: $errorMsg")
                publishCaptureSnapshot(
                    mic = mic,
                    softwareAec = aec,
                    softwareAecPausedForSpeech = false,
                    softwareNs = ns,
                )
                out.emit(AudioResult.Error(errorMsg, recoverable = true))
                return false
            }
            if (probe == null) {
                mic.discardWarmupReads(16)
            }
            diagMicGainLinear = captureConfig.micGainLinear
            diagEffectiveProfileId = effectiveProfile.id
            diagCaptureSampleRateHz = effectiveProfile.captureSampleRateInHz
            diagCaptureChannelCount = effectiveProfile.captureChannelCount
            publishCaptureSnapshot(
                mic = mic,
                softwareAec = aec,
                softwareAecPausedForSpeech = false,
                softwareNs = ns,
            )

            Log.i(
                TAG,
                "audio pipeline start userProfile=${audioProfile.id} effectiveProfile=${effectiveProfile.id} " +
                    "capture=${effectiveProfile.captureSampleRateInHz}Hz/${effectiveProfile.captureChannelCount}ch " +
                    "output=${effectiveProfile.outputSampleRateInHz}Hz/${effectiveProfile.outputChannelCount}ch " +
                    "requiresProcessing=${effectiveProfile.requiresProcessing} " +
                    "activeSource=${mic.activeAudioSource} " +
                    "source=$effectiveAudioSource ns=${effectiveCapture.noiseSuppressorEnabled} " +
                    "agc=${effectiveCapture.automaticGainControlEnabled} aec=${effectiveCapture.acousticEchoCancelerEnabled} " +
                    "swAec=${captureConfig.softwareAecEnabled} swNs=${captureConfig.softwareNsEnabled} " +
                    "path=${captureConfig.recordingPath} " +
                    "micGainLinear=${captureConfig.micGainLinear} " +
                    "vsTap=$usesVsWakeWordEngine vsFloatGain=$vsFloatGain vsActiveGain=$vsActiveGain " +
                    "streamMicGain=${captureConfig.micGainLinear} " +
                    "probeProcRms=${probe?.processedMaxRms} " +
                    "downmix=$effectiveDownmix vsCaptureOverride=$usesVsWakeWordEngine wake=${config.wakeWords}",
            )
            return true
        }

        /** Mirrors the unconditional acquire in [open]; safe to call after a failed open. */
        fun close() {
            currentWakeWordDetector = null
            // Hardware-AEC profile holds the bus for the stop DSP reference too.
            if (PlaybackReferenceBus.release()) {
                ModPlaybackReference.onBusActiveChanged(appContext, false)
            }
            softwareAec?.close()
            liveSoftwareNs.set(null)
            softwareNs?.close()
            // Keep last mic fields; clear live AEC/NS flags so stats do not imply an active processor.
            publishCaptureSnapshot(
                mic = microphoneInput,
                softwareAec = null,
                softwareAecPausedForSpeech = false,
                softwareNs = null,
            )
            microphoneInput?.close()
            wakeWordDetector?.close()
            stopWordDetector?.close()
        }

        /** One mic read: preprocess, meter, stream to HA, run wake/stop detection. */
        suspend fun step(out: FlowCollector<AudioResult>) {
            val mic = microphoneInput
            if (mic == null) {
                yield()
                return
            }
            val rawAudio = mic.read()
            if (rawAudio.remaining() == 0) {
                if (mic.isDeadObject) {
                    recoverDeadMicrophone()
                } else {
                    yield()
                }
                return
            }

            val frame = preprocess(rawAudio)
            if (frame == null) {
                yield()
                return
            }
            val detectionAudio = selectDetectionAudio(frame)
            if (detectionAudio.remaining() == 0) {
                yield()
                return
            }
            val metrics = measure(frame, detectionAudio)
            rememberSpeechInsert(frame)

            if ((isStreaming || wakePreRollCapturing) && frame.streamAudio.remaining() > 0) {
                val streamGain = config.micGainLinear * _microphoneVolume.value
                val streamOut = if (streamGain != 1.0f) {
                    applyGain(frame.streamAudio, streamGain)
                } else {
                    frame.streamAudio
                }
                out.emit(AudioResult.Audio(ByteString.copyFrom(streamOut.asReadOnlyBuffer())))
            }

            // Manual voiceprint enrollment suspends detection: mic + level + voiceprint
            // ring buffer above keep running, but no wake/stop word fires (and no overlay).
            // The chorus peer hold is NOT part of this gate anymore — it mutes wake
            // dispatch only (see [runWakeDetector]); the builtin stop DSP keeps
            // running under it.
            if (!wakeDetectionSuspended.get()) {
                detect(out, frame, detectionAudio, metrics)
            }

            yield()
        }

        /**
         * An audio-server restart (HAL crash, vendor audio reload) invalidates the
         * recorder for good: every later read returns ERROR_DEAD_OBJECT, which reads
         * as an empty frame and would leave the loop spinning with the mic
         * permanently deaf. Rebuild the recorder instead.
         */
        private suspend fun recoverDeadMicrophone() {
            val mic = microphoneInput ?: return
            kotlinx.coroutines.delay(micDeadRetryDelayMs)
            if (!mic.restart()) {
                Log.e(TAG, "microphone recreate failed: ${mic.lastError}")
                micDeadRetryDelayMs =
                    (micDeadRetryDelayMs * 2).coerceAtMost(MIC_DEAD_RETRY_MAX_MS)
                return
            }
            micDeadRetryDelayMs = MIC_DEAD_RETRY_MIN_MS
            mic.discardWarmupReads(16)
            // The gap in the stream invalidates every piece of state that spans
            // frames: detector windows, the adaptive filter, and the far-end reader.
            detectFrameCount = 0
            vsLowEnergyStreak = 0
            vsPeakDetectRms = 0f
            vsPeakRawRms = 0f
            wakeWordDetector?.reset()
            stopWordDetector?.reset()
            softwareAec?.reset()
            softwareNs?.reset()
            aecAdaptedMs = 0L
            aecAdaptLastTickMs = 0L
            PlaybackReferenceBus.resetReader()
            publishCaptureSnapshot(
                mic = mic,
                softwareAec = softwareAec,
                softwareAecPausedForSpeech = softwareAecPausedForSpeech,
                softwareNs = softwareNs,
            )
            Log.i(
                TAG,
                "microphone recovered after audio server restart " +
                    "source=${mic.activeAudioSource} profile=${effectiveProfile.id}",
            )
        }

        /** Downmix, AEC/NS, far-end rulers, and the always-on ring buffers. Null = empty frame. */
        private fun preprocess(rawAudio: ByteBuffer): CaptureFrame? {
            val processedAudio = AudioFrameProcessor.process(
                copyPcm16Le(rawAudio),
                effectiveProfile,
                effectiveDownmix,
            )
            if (processedAudio.remaining() == 0) return null

            val vsWakeWordPcm = if (usesVsWakeWordEngine) {
                copyPcm16Le(processedAudio)
            } else {
                null
            }

            val aec = softwareAec
            // Optional: bypass software AEC while uploading speech to HA, then resume.
            val pauseAecForSpeech = aec != null &&
                _softwareAecPauseDuringSpeech.get() &&
                isStreaming
            if (aec != null && softwareAecPausedForSpeech && !pauseAecForSpeech) {
                aec.reset()
                PlaybackReferenceBus.resetReader()
                // True filter reset: convergence protection starts over.
                aecAdaptedMs = 0L
                aecAdaptLastTickMs = 0L
            }
            if (softwareAecPausedForSpeech != pauseAecForSpeech) {
                softwareAecPausedForSpeech = pauseAecForSpeech
                publishCaptureSnapshot(
                    mic = microphoneInput,
                    softwareAec = aec,
                    softwareAecPausedForSpeech = softwareAecPausedForSpeech,
                    softwareNs = softwareNs,
                )
            } else {
                softwareAecPausedForSpeech = pauseAecForSpeech
            }
            val afterAec = if (aec != null && !pauseAecForSpeech) {
                aec.process(copyPcm16Le(processedAudio))
            } else {
                if (aec != null && pauseAecForSpeech) {
                    SoftAecProbe.noteBypass()
                }
                copyPcm16Le(processedAudio)
            }
            // Soft NS after AEC; no delay line (unlike AvaVoice call uplink).
            val ns = softwareNs
            val streamAudio = if (ns != null) {
                ns.process(afterAec)
            } else {
                afterAec
            }

            // Same far-end ruler as SoftwareAecProcessor: write hold + ring
            // energy + room tail. A write-only window missed burst-queued PCM
            // and fed the wake ring raw mic while the canceller was still live.
            val aecBargeInActive = aec != null && PlaybackReferenceBus.isFarEndActive()

            // Wake protection while the canceller is genuinely unconverged:
            // a freshly reset filter passes our own TTS to the detect tap,
            // where it scores as a wake. Convergence is a property of the
            // filter, not of playback — count far-end-active time since the
            // last real reset, so the window covers only the first
            // [WAKE_AEC_CONVERGE_MS] of adaptation ever, not the start of
            // every response or sentence. Barge-in works normally after it.
            val nowMs = System.currentTimeMillis()
            if (aecBargeInActive) {
                if (aecAdaptLastTickMs != 0L) {
                    aecAdaptedMs += (nowMs - aecAdaptLastTickMs)
                        .coerceIn(0L, AEC_ADAPT_MAX_TICK_MS)
                }
                aecAdaptLastTickMs = nowMs
            } else {
                aecAdaptLastTickMs = 0L
            }
            val aecConverging = aecBargeInActive &&
                aecAdaptedMs < WAKE_AEC_CONVERGE_MS

            // Far-end signal for the TTS-text wake hold. [aecBargeInActive] is
            // only meaningful with software AEC (the bus is not acquired
            // otherwise), which used to leave hardware-AEC / no-AEC users with
            // no self-wake protection at all — measured: "Hey Jarvis" TTS echo
            // still scores 0.95+ at a −30 dB residual, i.e. even a good
            // canceller cannot stop it, only this text hold can. Fall back to
            // the writers' diagnostic stamp, which updates regardless of bus
            // state; the write-hold window covers sentence gaps and the
            // buffered tail of burst-queued playback.
            val ttsEchoAudible = if (aec != null) {
                aecBargeInActive
            } else {
                PlaybackReferenceBus.hasRecentPlaybackSignal(
                    PlaybackReferenceBus.FAR_END_WRITE_HOLD_MS,
                )
            }

            // Wake-detection tap: same AEC pass as the uplink but remixed with a
            // much higher linear-filter share (bus_model EXP8b: +15 dB wake SNR at
            // loud volume vs the uplink mix, tier-independent). Frame-aligned with
            // afterAec; null when the AEC is bypassed so callers fall back.
            val detectTap = if (aec != null && !pauseAecForSpeech) {
                aec.detectOutput
            } else {
                null
            }

            val pcmRate = VoicePrintPcmCapture.pcmRateAfterCaptureProcessing(effectiveProfile)
            val frame = CaptureFrame(
                rawAudio = rawAudio,
                processedAudio = processedAudio,
                vsWakeWordPcm = vsWakeWordPcm,
                pauseAecForSpeech = pauseAecForSpeech,
                streamAudio = streamAudio,
                aecBargeInActive = aecBargeInActive,
                nowMs = nowMs,
                aecConverging = aecConverging,
                ttsEchoAudible = ttsEchoAudible,
                detectTap = detectTap,
            )
            // Wake-sample ring is always filled (voiceprint + offline sample verify).
            // Same tap as [preGainSource]: cancelled detect mix during far-end,
            // and the idle path the detector actually hears (raw, or stream
            // when software NS is on). A raw-only idle ring made extra-
            // strictness re-score a different signal than the one that fired.
            VoicePrintPcmCapture.writeToRingBuffer(
                voicePrintRingBuffer,
                copyPcm16Le(detectionTapPcm(frame)),
                pcmRate,
            )

            if (micPreviewCaptureEnabled.get()) {
                // Post-AEC/NS and pre mic-gain: the preview must match what detection hears.
                val previewPcm = VoicePrintPcmCapture.normalizeTo16kMono(
                    copyPcm16Le(streamAudio),
                    pcmRate,
                )
                micPreviewSamples += previewPcm.remaining() / 2
                micPreviewRingBuffer.write(previewPcm)
                if (micPreviewRawEnabled.get()) {
                    // Same frame before AEC, so the echo card can A/B the two signals.
                    VoicePrintPcmCapture.writeToRingBuffer(
                        micPreviewRawRingBuffer,
                        copyPcm16Le(processedAudio),
                        pcmRate,
                    )
                }
                if (micPreviewSamples >= MIC_PREVIEW_MAX_SAMPLES) {
                    micPreviewCaptureEnabled.set(false)
                }
            }

            if (audioEventCaptureEnabled.get()) {
                // Pre-AEC (environmental sounds). Do NOT apply the user mic
                // gain: the Edge Impulse MFE model expects the natural mic level it was trained
                // on. Boosting +N dB shifts every MFE feature up and lifts background noise above
                // the model's noise floor, which causes constant high-confidence false positives.
                val audioEventPcm = processedAudio.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (window in audioEventWindowAccumulator.write(audioEventPcm, pcmRate)) {
                    audioEventWindowListener?.invoke(window)
                }
            }

            return frame
        }

        /**
         * Which buffer the detectors hear (before user mic gain).
         *
         * microWakeWord / stop models train on natural mic level. User mic gain is
         * for HA uplink only — boosting detect PCM lifts background into high-
         * confidence false wakes (same failure mode documented for audio-event).
         *
         * Barge-in source: during far-end playback micro detects on the echo-
         * cancelled stream. Host-model measurement (bus_model EXP2): at loud
         * volume the raw mic gives ~-18 dB speech-to-echo SNR while the AEC
         * output gives ~+10 dB — no model rides out -18 dB, which is why wake
         * needed several shouted attempts over music. The old "NLP residual ≠
         * training distribution" concern predates the AEC fixes (stale-frame
         * passthrough, reference clipping, ungated makeup gain, linear blend);
         * the cancelled stream is now near-natural speech plus residual. When
         * idle, keep raw mic for quiet-room accuracy (AEC path costs ~2 dB)
         * unless software NS is on — that path already feeds [streamAudio],
         * and the verify ring must follow it.
         */
        private fun detectionTap(frame: CaptureFrame): WakeDetectAudioTap =
            WakeDetectAudioSource.tap(
                openEngine = usesVsWakeWordEngine,
                softwareNsOn = softwareNs != null,
                softwareAecOn = softwareAec != null,
                bargeIn = frame.aecBargeInActive,
                detectTapAvailable = frame.detectTap != null,
            )

        private fun detectionTapPcm(frame: CaptureFrame): ByteBuffer =
            when (detectionTap(frame)) {
                // Dedicated AEC remix while TTS/media plays. NS stays on uplink.
                WakeDetectAudioTap.DETECT_TAP -> frame.detectTap!!
                WakeDetectAudioTap.STREAM -> frame.streamAudio
                WakeDetectAudioTap.PROCESSED -> frame.processedAudio
                WakeDetectAudioTap.VS_WAKE -> frame.vsWakeWordPcm!!
            }

        private fun preGainSource(frame: CaptureFrame): ByteBuffer = detectionTapPcm(frame)

        /** Detector input: a private copy where the source is shared with the uplink. */
        private fun selectDetectionAudio(frame: CaptureFrame): ByteBuffer {
            val source = detectionTapPcm(frame)
            val alreadyPrivate = usesVsWakeWordEngine && source === frame.vsWakeWordPcm
            return if (alreadyPrivate || (!usesVsWakeWordEngine && softwareAec == null)) {
                source
            } else {
                copyPcm16Le(source)
            }
        }

        /** Far-end ring read, onset guard, level meters, DTD learning, periodic diagnostics. */
        private suspend fun measure(frame: CaptureFrame, detectionAudio: ByteBuffer): CaptureMetrics {
            val aec = softwareAec
            val nowMs = frame.nowMs

            // Hardware-AEC profile: no SoftwareAecProcessor owns the far-end
            // ring, so the capture loop is the ring reader — paced by the mic
            // clock, same bulk-delay anchoring the software canceller relies
            // on. The chunk feeds the builtin stop DSP's machine floor.
            // Reading is unconditional on detection state so the writer
            // alignment never drifts mid-playback; when nothing plays the
            // far-end ruler is false and the ring stays untouched (writers
            // re-anchor on their next write).
            //
            // A constructed-but-dead software canceller (both engines failed
            // to init: isActive == false) never consumes the ring and hands
            // the stop DSP an empty referenceOutput — the DSP then scored
            // raw echo with no machine floor at all. Treat that exactly
            // like the hardware-AEC profile and read the ring here.
            val softwareAecLive = aec != null && aec.isActive
            var stopRefCount = 0
            if (!softwareAecLive && PlaybackReferenceBus.isFarEndActive()) {
                stopRefCount = detectionAudio.remaining() / 2
                if (stopRefScratch.size < stopRefCount) {
                    stopRefScratch = ShortArray(stopRefCount)
                }
                PlaybackReferenceBus.read(stopRefScratch, stopRefCount)
            }

            // Far-end onset guard for stop and speech-insert. At playback
            // start — and after a device-volume change — the canceller is
            // re-converging: the residual runs several times above the learned
            // prediction, and that transient would qualify as double-talk.
            // Isolated opening words walk the stop template through the same
            // gap. Detection and gain learning stay continuous; only dispatch
            // is withheld. The TTS playback-started callback arms the same
            // deadline, because a wake-chime write-hold can hide this edge.
            val farEndForStop =
                if (softwareAecLive) frame.aecBargeInActive else stopRefCount > 0
            if (farEndForStop &&
                (!stopFarEndWasActive || PlaybackReferenceBus.hasRecentLevelChange())
            ) {
                val guard = nowMs + STOP_FAR_END_ONSET_GUARD_MS
                if (guard > stopFarEndGuardUntilMs) stopFarEndGuardUntilMs = guard
            }
            stopFarEndWasActive = farEndForStop

            // True pre-gain: measure before user mic-gain (matches detection path).
            val preGainDetectRms = AudioEnergy.pcm16LeFloatRms(preGainSource(frame))
            lastPreGainDetectRms = preGainDetectRms.coerceIn(0f, 1f)
            diagMicGainLinear = config.micGainLinear
            recordWakeWindowPeak(lastPreGainDetectRms)
            recordUserSpeechMicPeak(preGainDetectRms)
            updateIdleAmbientRms(AudioEnergy.pcm16LeFloatRms(frame.rawAudio))
            if (aec != null && !frame.pauseAecForSpeech && frame.aecBargeInActive) {
                learnEchoPath(nowMs, preGainDetectRms, aec.lastRefRms)
            } else if (!softwareAecLive && stopRefCount > 0 && !frame.pauseAecForSpeech) {
                // Hardware canceller: the ring chunk is the same reference the
                // stop floor subtracts. Learn the echo path against it so
                // speech-insert can use that comparison instead of raw energy.
                learnEchoPath(nowMs, preGainDetectRms, shortRms(stopRefScratch, stopRefCount))
            }
            // NOTE: no per-frame anti-clip here. It looked right on paper but a
            // single loud TTS/media frame (preGainRms 0.20) trimmed the gain to
            // 0.45 and starved the CTC decode for minutes (luna barely at gate,
            // jarvis dead). Device evidence says clipping at gain 5.5 never
            // actually hurt this model — do not re-add without on-device proof.
            // Instant RMS tracks post-gain detect energy (VS applies gain in detector).
            val detectRmsForLevel = if (usesVsWakeWordEngine) {
                preGainDetectRms * vsActiveGain
            } else {
                AudioEnergy.pcm16LeFloatRms(detectionAudio)
            }
            liveMicLevel = (liveMicLevel * 0.72f + detectRmsForLevel.coerceIn(0f, 1f) * 0.28f)
                .coerceIn(0f, 1f)
            ChorusWakeBlurService.feedAudioEnergy(liveMicLevel)
            // Enrollment soft-gate must see true frame energy on the ring-buffer path;
            // the EMA meter above under-reads short wake-word bursts.
            if (enrollmentListenActive.get()) {
                val ringFrameRms = AudioEnergy.pcm16LeFloatRms(frame.processedAudio).coerceIn(0f, 1f)
                if (ringFrameRms > enrollmentPeakRms) {
                    enrollmentPeakRms = ringFrameRms
                }
            }

            if (++detectFrameCount % 50 == 1) {
                publishPeriodicDiagnostics(frame, preGainDetectRms, detectRmsForLevel)
            }

            return CaptureMetrics(
                preGainDetectRms = preGainDetectRms,
                stopRefCount = stopRefCount,
                farEndActive = farEndForStop,
            )
        }

        /**
         * Echo-path gain: how much of the playback reference is still in the
         * detect tap. Frames that already look like double-talk are not learned,
         * so the user's voice never becomes the baseline it is judged against.
         * A device-volume change rescales the echo for real and is adopted.
         */
        private fun learnEchoPath(nowMs: Long, detectRms: Float, refRms: Float) {
            if (refRms <= DTD_MIN_REF_RMS) return
            val ratio = (detectRms / refRms).coerceAtMost(DTD_GAIN_MAX)
            val doubleTalkSuspected = ratio > dtdEchoPathGain * DTD_ENERGY_MARGIN
            if (!doubleTalkSuspected || PlaybackReferenceBus.hasRecentLevelChange()) {
                dtdEchoPathGain += DTD_GAIN_ALPHA * (ratio - dtdEchoPathGain)
            }
            val expected = dtdEchoPathGain * refRms
            if (expected >= dtdExpectedPeak ||
                nowMs - dtdExpectedPeakAtMs > WAKE_PEAK_WINDOW_MS
            ) {
                dtdExpectedPeak = expected
                dtdExpectedPeakAtMs = nowMs
            }
        }

        /** Int16 RMS on the same ±1 scale as [SoftwareAecProcessor.lastRefRms]. */
        private fun shortRms(samples: ShortArray, count: Int): Float {
            if (count <= 0) return 0f
            var sum = 0.0
            val n = count.coerceAtMost(samples.size)
            for (i in 0 until n) {
                val v = samples[i].toDouble()
                sum += v * v
            }
            return (kotlin.math.sqrt(sum / n) / Short.MAX_VALUE).toFloat()
        }

        /** Refresh deep diagnostics ~every 50 frames for the stats sheet (+ vs gain trim). */
        private suspend fun publishPeriodicDiagnostics(
            frame: CaptureFrame,
            preGainDetectRms: Float,
            detectRms: Float,
        ) {
            publishCaptureSnapshot(
                mic = microphoneInput,
                softwareAec = softwareAec,
                softwareAecPausedForSpeech = softwareAecPausedForSpeech,
                softwareNs = softwareNs,
            )
            if (!usesVsWakeWordEngine) return
            val rawRms = AudioEnergy.pcm16LeFloatRms(frame.rawAudio)
            val processedRms = AudioEnergy.pcm16LeFloatRms(frame.processedAudio)
            vsPeakDetectRms = maxOf(vsPeakDetectRms, detectRms)
            vsPeakRawRms = maxOf(vsPeakRawRms, rawRms * vsActiveGain)
            val adapted = adaptVsInputGain(vsActiveGain, preGainDetectRms)
            if (kotlin.math.abs(adapted - vsAppliedGain) / vsAppliedGain.coerceAtLeast(0.1f) > 0.12f) {
                vsActiveGain = adapted
                vsAppliedGain = adapted
                applyVsInputGain(wakeWordDetector, vsActiveGain)
            } else {
                vsActiveGain = adapted
            }
            if (detectRms < 0.001f) {
                vsLowEnergyStreak++
            } else {
                vsLowEnergyStreak = 0
            }
            if (detectFrameCount == 101 && vsPeakDetectRms < 0.001f) {
                if (!swapVsDownmixMode() &&
                    vsProfileSwaps < vsCaptureProbeTargets(profileCandidates).size - 1
                ) {
                    swapVsCaptureProfile()
                }
            }
            if (rawRms > 0.003f && processedRms < rawRms / 20f) {
                swapVsDownmixMode()
            }
        }

        /** Gate computation shared by the stop and wake dispatch paths. */
        private suspend fun detect(
            out: FlowCollector<AudioResult>,
            frame: CaptureFrame,
            detectionAudio: ByteBuffer,
            metrics: CaptureMetrics,
        ) {
            val aec = softwareAec
            // Physics override for the echo-suppression gates below. The
            // echo residual at the detect tap cannot exceed the predicted
            // residual (echo-path gain × reference) by [DTD_ENERGY_MARGIN]
            // — a detection whose phrase window peaked well above it can
            // only be near-end speech, so it passes the convergence window
            // and the TTS-text hold instead of starving barge-in. A
            // same-phrase echo that slips through is still rejected by
            // offline verify, which re-scores the playback reference.
            val dtdExpectedNow =
                if (frame.nowMs - dtdExpectedPeakAtMs <= WAKE_PEAK_WINDOW_MS) {
                    dtdExpectedPeak
                } else {
                    0f
                }
            // isActive guard: with no live canceller engine the tap is raw
            // mic and lastRefRms is never fed — every echo would qualify
            // as "evidence". Those devices keep the plain time windows.
            val dtdBargeInEvidence = aec != null && aec.isActive &&
                !frame.pauseAecForSpeech &&
                lastDetectRms() > maxOf(dtdExpectedNow * DTD_ENERGY_MARGIN, DTD_MIN_TAP_RMS)

            // With the AEC bypassed for uplink speech, detectionAudio is raw mic:
            // our own TTS would walk the /s/->vowel template. Skip those frames
            // rather than score echo, and re-arm so the partial utterance that
            // spans the bypass window cannot complete against clean audio.
            val stopAudioEchoContaminated = frame.pauseAecForSpeech && frame.aecBargeInActive
            val stopGateOpen = _stopWordDetectionEnabled.get() &&
                !stopAudioEchoContaminated
            val gates = DetectGates(
                dtdExpectedNow = dtdExpectedNow,
                dtdBargeInEvidence = dtdBargeInEvidence,
                stopGateOpen = stopGateOpen,
            )

            // Stop detection first: it reads a duplicate of the frame, while the
            // micro wake detector consumes detectionAudio to its limit.
            // Both engines share this detector and this frame — vsTap is off, so
            // micro and open feed it the identical echo-cancelled audio.
            dispatchClaimed = false
            runBuiltinStop(out, frame, detectionAudio, metrics, gates)
            runWakeDetector(out, frame, detectionAudio, metrics, gates)
            maybeEmitSpeechInsert(out, frame, metrics, gates)
        }

        /** Keep the cancelled uplink while a turn is in flight and the mic is not uploading. */
        private fun rememberSpeechInsert(frame: CaptureFrame) {
            val open = frame.aecBargeInActive || speechInsertOpen.get()
            if (isStreaming || !open || frame.streamAudio.remaining() == 0) {
                if (isStreaming || !open) {
                    speechInsertRing.clear()
                    speechInsertRingBytes = 0
                    speechInsertArmAtMs = 0L
                    if (!frame.aecBargeInActive) speechInsertLatched = false
                }
                return
            }
            if (frame.aecBargeInActive) {
                speechInsertArmAtMs = 0L
            } else if (speechInsertArmAtMs == 0L) {
                speechInsertArmAtMs = frame.nowMs + SPEECH_INSERT_ARM_MS
            }
            val streamGain = config.micGainLinear * _microphoneVolume.value
            val streamOut = if (streamGain != 1.0f) {
                applyGain(frame.streamAudio, streamGain)
            } else {
                frame.streamAudio.duplicate()
            }
            val bytes = ByteArray(streamOut.remaining())
            streamOut.duplicate().get(bytes)
            speechInsertRing.addLast(bytes)
            speechInsertRingBytes += bytes.size
            while (speechInsertRingBytes > SPEECH_INSERT_MAX_BYTES && speechInsertRing.isNotEmpty()) {
                speechInsertRingBytes -= speechInsertRing.removeFirst().size
            }
        }

        /**
         * Near-end speech the playback reference does not explain.
         * An unlearned window is not evidence: the silence floor (0.01) is
         * exactly what the speaker clears at onset.
         */
        private fun nearEndBeatsReference(gates: DetectGates, detectRms: Float): Boolean {
            val expected = gates.dtdExpectedNow
            if (expected <= DTD_MIN_REF_RMS) return false
            // Current frame, not the wake-window peak. The peak holds the TTS
            // onset and then clears a quieter prediction for the rest of the window.
            return detectRms > maxOf(expected * DTD_ENERGY_MARGIN, DTD_MIN_TAP_RMS)
        }

        private fun playbackOnsetGuardActive(nowMs: Long): Boolean {
            val until = maxOf(stopFarEndGuardUntilMs, stopOnsetGuardEventUntilMs)
            return nowMs < until
        }

        private suspend fun maybeEmitSpeechInsert(
            out: FlowCollector<AudioResult>,
            frame: CaptureFrame,
            metrics: CaptureMetrics,
            gates: DetectGates,
        ) {
            // TTS playback-started and the far-end edge share one deadline with
            // stop. While it holds, the residual itself beats the comparison.
            val onset = playbackOnsetGuardActive(frame.nowMs)
            // Speaker up: software far-end, the hardware reference read, or the
            // diagnostic stamp that leads the bus. Raw energy is closed for
            // all three. A real insert has to clear the learned echo path.
            // The short write stamp covers the frames before isFarEndActive,
            // which is when TTS first hits the mic and the raw path used to fire.
            // Zero the processing arm so the room tail after playback cannot
            // fall through the moment the stamp drops.
            val playbackStamp = PlaybackReferenceBus.hasRecentPlaybackSignal(
                PlaybackReferenceBus.PLAYBACK_RECENT_MS,
            )
            val speakerUp = metrics.farEndActive || frame.aecBargeInActive ||
                frame.ttsEchoAudible || playbackStamp
            if (onset || speakerUp) speechInsertArmAtMs = 0L
            val duringPlayback = !onset && speakerUp && !frame.pauseAecForSpeech &&
                nearEndBeatsReference(gates, metrics.preGainDetectRms)
            val duringProcessing = !onset && !speakerUp && speechInsertOpen.get() &&
                speechInsertArmAtMs != 0L && frame.nowMs >= speechInsertArmAtMs &&
                lastDetectRms() > SPEECH_INSERT_OPEN_RMS
            if (dispatchClaimed || isStreaming || speechInsertLatched ||
                (!duringPlayback && !duringProcessing)
            ) {
                if (!duringPlayback && !duringProcessing) speechInsertSinceMs = 0L
                return
            }
            if (speechInsertSinceMs == 0L) speechInsertSinceMs = frame.nowMs
            if (frame.nowMs - speechInsertSinceMs < SPEECH_INSERT_HOLD_MS) return
            speechInsertLatched = true
            speechInsertSinceMs = 0L
            var total = 0
            for (chunk in speechInsertRing) total += chunk.size
            val joined = ByteArray(total)
            var cursor = 0
            for (chunk in speechInsertRing) {
                chunk.copyInto(joined, cursor)
                cursor += chunk.size
            }
            Log.i(TAG, "speech insert ${joined.size} bytes during ${if (duringPlayback) "playback" else "processing"}")
            out.emit(AudioResult.SpeechInsert(ByteString.copyFrom(joined)))
        }

        private suspend fun runBuiltinStop(
            out: FlowCollector<AudioResult>,
            frame: CaptureFrame,
            detectionAudio: ByteBuffer,
            metrics: CaptureMetrics,
            gates: DetectGates,
        ) {
            val stopDetector = stopWordDetector ?: return
            // Builtin DSP path only runs when the stop slot is "builtin";
            // a model-based slot (or "none") leaves the DSP disarmed.
            val builtinStopActive = gates.stopGateOpen && _builtinStopWordEnabled.get()
            if (builtinStopActive && !stopWordDetectionWasEnabled) {
                stopDetector.arm()
            }
            stopWordDetectionWasEnabled = builtinStopActive
            // Machine floor: hand the DSP the clean playback reference so it
            // subtracts the predicted per-band machine contribution — a
            // barge-in "stop" stays detectable through continuous TTS/media
            // (host-validated: 0% -> 81-99% mid-sentence recall at converged
            // residual levels) and the playback itself can never self-stop.
            // Software AEC path: frame-aligned reference from the same AEC
            // pass as the detect tap. Hardware-AEC path: the ring chunk read
            // in [measure] (bulk-delay aligned; the DSP's release envelope
            // absorbs the remaining offset).
            val aec = softwareAec
            val stopPlaybackRef =
                if (frame.aecBargeInActive && frame.detectTap != null &&
                    aec != null && aec.isActive
                ) {
                    aec.referenceOutput
                } else {
                    null
                }
            val stopHit = when {
                !builtinStopActive -> false
                stopPlaybackRef != null ->
                    stopDetector.detect(detectionAudio, stopPlaybackRef)
                metrics.stopRefCount > 0 ->
                    stopDetector.detect(detectionAudio, stopRefScratch, metrics.stopRefCount)
                else -> stopDetector.detect(detectionAudio)
            }
            if (!stopHit) return
            val nowMs = frame.nowMs
            val stopOnsetGuardUntil =
                maxOf(stopFarEndGuardUntilMs, stopOnsetGuardEventUntilMs)
            if (nowMs < stopOnsetGuardUntil) {
                Log.i(
                    TAG,
                    "stop suppressed: far-end onset guard " +
                        "(${stopOnsetGuardUntil - nowMs}ms left, " +
                        "canceller re-converging)",
                )
            } else if (frame.aecConverging && !gates.dtdBargeInEvidence) {
                // Same convergence window the wake and stop-slot
                // model honor: a freshly reset software filter
                // passes near-raw echo to the detect tap, and the
                // DSP's machine floor cannot subtract what the
                // canceller failed to remove. The double-talk
                // energy proof overrides — physics says a peak
                // above the predicted residual is near-end speech.
                Log.i(
                    TAG,
                    "stop suppressed: aec converging " +
                        "${aecAdaptedMs}ms/${WAKE_AEC_CONVERGE_MS}ms",
                )
            } else {
                Log.i(
                    TAG,
                    "stop fired conf=${"%.2f".format(stopDetector.lastConfidence())} " +
                        "detectRms=${"%.3f".format(metrics.preGainDetectRms)}",
                )
                dispatchClaimed = true
                out.emit(AudioResult.StopDetected(stopDetector.phrase))
            }
        }

        private suspend fun runWakeDetector(
            out: FlowCollector<AudioResult>,
            frame: CaptureFrame,
            detectionAudio: ByteBuffer,
            metrics: CaptureMetrics,
            gates: DetectGates,
        ) {
            val detector = wakeWordDetector ?: return
            val nowMs = frame.nowMs
            // Chorus loser hold mutes the WAKE side only: the seat belongs
            // to a peer, so this device must not answer a re-wake — but
            // "stop" is a local command (its own gate already limits it to
            // an active session or a ringing alarm). Builtin DSP already
            // ran; the stop-slot MODEL rides this same detect() pass, so we
            // keep feeding the engine and only drop wake dispatch below.
            // Skipping detect() here used to deafen a model stop word for
            // the whole hold.
            val peerChorusHold = WakeWordArbiter.isPeerChorusSessionActive()
            if (peerChorusHold &&
                nowMs - lastPeerHoldLogMs >= PEER_HOLD_LOG_INTERVAL_MS
            ) {
                lastPeerHoldLogMs = nowMs
                Log.i(TAG, "wake detection muted: peer chorus session hold")
            }
            // Keep feeding the detector through the convergence window
            // and the peer hold: skipping frames would splice pre-hold
            // audio onto post-hold audio and invite a bogus match, and
            // would also starve the stop-slot model. Only wake dispatch
            // is withheld.
            val wakeDetections = detector.detect(detectionAudio)
            if (wakeDetections.isEmpty()) return
            for (detection in wakeDetections) {
                // Stop-slot model: device-local stop, never a wake. Runs
                // before the wake gates below so chorus arbitration and
                // voiceprint (both downstream of WakeDetected) cannot eat
                // it, but honors the session/ring gate and echo guards.
                if (detection.wakeWordId == config.stopWakeWordId) {
                    dispatchStopModel(out, frame, metrics, gates, detection)
                    continue
                }
                if (peerChorusHold) {
                    continue
                }
                // Opening of playback: the room path is not learned yet, so a
                // loud TTS frame clears the double-talk test. No override.
                if (playbackOnsetGuardActive(nowMs)) {
                    Log.i(
                        TAG,
                        "wake suppressed id=${detection.wakeWordId} " +
                            "playback onset guard",
                    )
                    continue
                }
                if (frame.aecConverging && !gates.dtdBargeInEvidence) {
                    Log.i(
                        TAG,
                        "wake suppressed id=${detection.wakeWordId} " +
                            "conf=${"%.2f".format(detection.confidence)} " +
                            "aec converging ${aecAdaptedMs}ms" +
                            "/${WAKE_AEC_CONVERGE_MS}ms",
                    )
                    continue
                }
                // Residual echo of a response that says the wake phrase is a
                // genuine utterance of it; no cutoff can reject it — only the
                // double-talk energy proof (and offline verify's reference
                // re-score downstream) can tell a real barge-in apart.
                if (frame.ttsEchoAudible && nowMs < ttsWakeEchoRiskUntilMs &&
                    !gates.dtdBargeInEvidence
                ) {
                    Log.i(
                        TAG,
                        "wake suppressed id=${detection.wakeWordId} " +
                            "conf=${"%.2f".format(detection.confidence)} " +
                            "tts text mentions wake phrase",
                    )
                    continue
                }
                if (frame.aecConverging ||
                    (frame.ttsEchoAudible && nowMs < ttsWakeEchoRiskUntilMs)
                ) {
                    Log.i(
                        TAG,
                        "barge-in override (wake) " +
                            "detectPeak=${"%.3f".format(lastDetectRms())} " +
                            "expectedResidual=${"%.3f".format(gates.dtdExpectedNow)} " +
                            "gain=${"%.2f".format(dtdEchoPathGain)}",
                    )
                }
                // Telemetry: correlates with SoftwareAecProcessor farend lines
                // when diagnosing volume-dependent wake failures.
                Log.i(
                    TAG,
                    "wake fired id=${detection.wakeWordId} " +
                        "conf=${"%.2f".format(detection.confidence)} " +
                        "source=${WakeDetectAudioSource.logLabel(detectionTap(frame))} " +
                        "detectRms=${"%.3f".format(metrics.preGainDetectRms)}",
                )
                // Anchor ring buffer for offline sample verify (and voiceprint).
                // Last-wake Voice Stats is recorded only after gates pass.
                markWakeSample(wakeWordEngine)
                dispatchClaimed = true
                out.emit(
                    AudioResult.WakeDetected(
                        wakeWord = detection.wakeWordPhrase,
                        wakeWordId = detection.wakeWordId,
                        confidence = detection.confidence,
                        verifierWindow = detection.verifierWindow,
                    ),
                )
            }
        }

        private suspend fun dispatchStopModel(
            out: FlowCollector<AudioResult>,
            frame: CaptureFrame,
            metrics: CaptureMetrics,
            gates: DetectGates,
            detection: WakeWordEngineDetector.DetectionResult,
        ) {
            val nowMs = frame.nowMs
            when {
                playbackOnsetGuardActive(nowMs) ->
                    Log.i(
                        TAG,
                        "stop model suppressed id=${detection.wakeWordId} " +
                            "playback onset guard",
                    )
                !gates.stopGateOpen ->
                    Log.d(
                        TAG,
                        "stop model ignored id=${detection.wakeWordId} " +
                            "gate closed (no session/ring or echo-contaminated)",
                    )
                frame.aecConverging && !gates.dtdBargeInEvidence ->
                    Log.i(
                        TAG,
                        "stop model suppressed id=${detection.wakeWordId} " +
                            "aec converging ${aecAdaptedMs}ms" +
                            "/${WAKE_AEC_CONVERGE_MS}ms",
                    )
                frame.ttsEchoAudible && nowMs < ttsStopEchoRiskUntilMs &&
                    !gates.dtdBargeInEvidence ->
                    Log.i(
                        TAG,
                        "stop model suppressed id=${detection.wakeWordId} " +
                            "tts text mentions stop phrase",
                    )
                else -> {
                    if (frame.aecConverging ||
                        (frame.ttsEchoAudible && nowMs < ttsStopEchoRiskUntilMs)
                    ) {
                        Log.i(
                            TAG,
                            "barge-in override (stop) " +
                                "detectPeak=${"%.3f".format(lastDetectRms())} " +
                                "expectedResidual=${"%.3f".format(gates.dtdExpectedNow)} " +
                                "gain=${"%.2f".format(dtdEchoPathGain)}",
                        )
                    }
                    Log.i(
                        TAG,
                        "stop fired (model) id=${detection.wakeWordId} " +
                            "conf=${"%.2f".format(detection.confidence)} " +
                            "detectRms=${"%.3f".format(metrics.preGainDetectRms)}",
                    )
                    dispatchClaimed = true
                    out.emit(AudioResult.StopDetected(detection.wakeWordPhrase))
                }
            }
        }

        private suspend fun swapVsDownmixMode(): Boolean {
            if (!usesVsWakeWordEngine) return false
            if (effectiveProfile.captureChannelCount < 2) return false
            val next = stereoDownmixCandidates(effectiveProfile)
                .firstOrNull { it !in triedDownmixModes }
                ?: return false
            Log.w(
                TAG,
                "vsWakeWord swapping downmix $effectiveDownmix -> $next " +
                    "(peakRawRms=$vsPeakRawRms peakDetectRms=$vsPeakDetectRms profile=${effectiveProfile.id})",
            )
            effectiveDownmix = next
            triedDownmixModes.add(next)
            vsDownmixSwaps++
            detectFrameCount = 0
            vsPeakDetectRms = 0f
            vsPeakRawRms = 0f
            vsLowEnergyStreak = 0
            wakeWordDetector?.reset()
            stopWordDetector?.reset()
            vsActiveGain = vsFloatGain
            vsAppliedGain = vsFloatGain
            applyVsInputGain(wakeWordDetector, vsActiveGain)
            return true
        }

        private suspend fun swapVsCaptureProfile(): Boolean {
            if (!usesVsWakeWordEngine) return false
            val nextTarget = vsCaptureProbeTargets(profileCandidates)
                .firstOrNull { "${it.profile.id}@${it.audioSource}" !in triedVsProfileIds }
                ?: return false
            Log.w(
                TAG,
                "vsWakeWord swapping capture ${effectiveProfile.id}@$effectiveAudioSource -> " +
                    "${nextTarget.label} (peakDetectRms=$vsPeakDetectRms after $detectFrameCount frames)",
            )
            microphoneInput?.close()
            effectiveProfile = nextTarget.profile
            effectiveAudioSource = nextTarget.audioSource
            effectiveDownmix = StereoDownmixMode.DOMINANT_OR_AVERAGE
            triedDownmixModes.clear()
            triedDownmixModes.add(effectiveDownmix)
            effectiveCapture = vsWakeWordCaptureConfig(captureConfig, effectiveProfile, effectiveAudioSource)
            val nextMic = MicrophoneInput(
                appContext = appContext,
                audioSource = effectiveCapture.audioSource,
                sampleRateInHz = effectiveProfile.captureSampleRateInHz,
                channelConfig = effectiveProfile.captureChannelConfig,
                noiseSuppressorEnabled = effectiveCapture.noiseSuppressorEnabled,
                automaticGainControlEnabled = effectiveCapture.automaticGainControlEnabled,
                acousticEchoCancelerEnabled = effectiveCapture.acousticEchoCancelerEnabled,
                strictAudioSource = true,
                recordingPath = effectiveCapture.recordingPath,
            )
            if (!nextMic.start()) {
                Log.e(TAG, "vs profile swap failed ${nextTarget.label}: ${nextMic.lastError}")
                nextMic.close()
                triedVsProfileIds.add("${nextTarget.profile.id}@${nextTarget.audioSource}")
                return false
            }
            nextMic.discardWarmupReads(16)
            microphoneInput = nextMic
            effectiveAudioSource = nextMic.activeAudioSource
            effectiveCapture = vsWakeWordCaptureConfig(captureConfig, effectiveProfile, effectiveAudioSource)
            triedVsProfileIds.add("${effectiveProfile.id}@${effectiveAudioSource}")
            vsProfileSwaps++
            diagEffectiveProfileId = effectiveProfile.id
            diagCaptureSampleRateHz = effectiveProfile.captureSampleRateInHz
            diagCaptureChannelCount = effectiveProfile.captureChannelCount
            detectFrameCount = 0
            vsPeakDetectRms = 0f
            vsPeakRawRms = 0f
            vsLowEnergyStreak = 0
            wakeWordDetector?.reset()
            stopWordDetector?.reset()
            vsActiveGain = vsFloatGain
            vsAppliedGain = vsFloatGain
            applyVsInputGain(wakeWordDetector, vsActiveGain)
            publishCaptureSnapshot(
                mic = nextMic,
                softwareAec = softwareAec,
                softwareAecPausedForSpeech = softwareAecPausedForSpeech,
                softwareNs = softwareNs,
            )
            Log.i(
                TAG,
                "vsWakeWord profile swap active profile=${effectiveProfile.id} " +
                    "source=$effectiveAudioSource downmix=$effectiveDownmix " +
                    "activeSource=${nextMic.activeAudioSource} swaps=$vsProfileSwaps",
            )
            return true
        }
    }

    private fun copyPcm16Le(source: ByteBuffer): ByteBuffer {
        val input = source.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocateDirect(input.remaining())
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        output.put(input)
        output.flip()
        return output
    }

    private fun applyGain(source: java.nio.ByteBuffer, gainLinear: Float): java.nio.ByteBuffer {
        val input = source.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val output = java.nio.ByteBuffer.allocateDirect(source.remaining())
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= 2) {
            val sample = input.short.toInt()
            val amplified = (sample * gainLinear).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(amplified.toShort())
        }
        output.flip()
        return output
    }
}
