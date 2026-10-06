package com.example.ava.settings

import android.content.Context
import android.media.MediaRecorder
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object WakeWordEngineSerializer : KSerializer<WakeWordEngine> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WakeWordEngine", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: WakeWordEngine) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): WakeWordEngine = when (decoder.decodeString()) {
        "OPEN_WAKE_WORD", "VS_WAKE_WORD" -> WakeWordEngine.OPEN_WAKE_WORD
        else -> WakeWordEngine.MICRO_WAKE_WORD
    }
}

@Serializable(with = WakeWordEngineSerializer::class)
enum class WakeWordEngine {
    MICRO_WAKE_WORD,
    OPEN_WAKE_WORD,
}

/**
 * How the user triggers a voice session.
 * [VOICE] = wake word engine always running (classic).
 * [BUTTON] = wake word engine off; only the floating Quick Wake FAB triggers STT.
 * [HYBRID] = wake word engine + floating FAB both active.
 */
@Serializable
enum class WakeMode {
    VOICE,
    BUTTON,
    HYBRID,
}

/**
 * Gesture the Quick Wake FAB uses to start a voice session.
 * [TAP] = tap once, HA's VAD decides when the utterance ends.
 * [HOLD] = push-to-talk: hold while speaking, release sends the end-of-speech marker.
 */
@Serializable
enum class QuickWakeTrigger {
    TAP,
    HOLD,
}

/** How voiceprint profiles are built: passive wake learning vs guided 5-sample enrollment. */
@Serializable
enum class VoicePrintEnrollmentMode {
    AUTO,
    MANUAL,
}

/** Physical capture device route (orthogonal to [MicrophoneSettings.audioSource]). */
@Serializable
enum class RecordingPath {
    AUTO,
    BUILTIN,
    USB,
}

/**
 * Speex residual-echo NLP strength (preprocess suppress / suppress-active).
 * Defaults match the historical hard-coded -45 / -25 dB.
 */
@Serializable
enum class SoftwareAecStrength {
    LIGHT,
    STANDARD,
    STRONG,
    ;

    val suppressDb: Int
        get() = when (this) {
            LIGHT -> -25
            STANDARD -> -45
            STRONG -> -55
        }

    val suppressActiveDb: Int
        get() = when (this) {
            LIGHT -> -10
            STANDARD -> -25
            STRONG -> -35
        }
}

/**
 * WebRTC software NS policy ([com.example.microfeatures.NoiseSuppressor] modes).
 * [LIGHT] matches the satellite/call default (MODE_MILD).
 */
@Serializable
enum class SoftwareNsStrength {
    LIGHT,
    STANDARD,
    STRONG,
    ;

    /** Maps to NoiseSuppressor.MODE_* integers. */
    val webrtcMode: Int
        get() = when (this) {
            LIGHT -> 0 // MODE_MILD
            STANDARD -> 1 // MODE_MODERATE
            STRONG -> 2 // MODE_AGGRESSIVE
        }
}

/**
 * Soft-AEC adaptive filter tail (room impulse length Speex can model).
 * Changing rebuilds Speex state. Values are chosen for home wall-tablet geometry
 * (speaker≈mic; playback reference bus already absorbs ~100 ms bulk delay):
 *
 * - [NEAR]: bedroom / study / small soft room (RT60 ≈ 0.3–0.4 s)
 * - [LIVING]: adaptive / default home room (RT60 ≈ 0.4–0.55 s living-like)
 * - [OPEN]: open-plan / hard finishes / kitchen-dining (RT60 ≈ 0.55–0.8 s)
 *
 * Tails are kept in the practical AEC band (~160–320 ms): long enough for early/mid
 * reflections, short enough that Speex still converges on-device (late diffuse reverb
 * is left to residual NLP). Rounded to whole 20 ms frames at 16 kHz.
 */
@Serializable
enum class SoftwareAecRoom {
    NEAR,
    LIVING,
    OPEN,
    ;

    val filterLengthMs: Int
        get() = when (this) {
            NEAR -> 160
            LIVING -> 240
            OPEN -> 320
        }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MicrophoneSettings(
    val wakeWord: String = "okay_nabu",
    val wakeWords: List<String> = emptyList(),
    val microWakeWords: List<String> = emptyList(),
    /** Reads legacy `vsWakeWords`; next save writes `openWakeWords`. */
    @JsonNames("vsWakeWords")
    val openWakeWords: List<String> = emptyList(),
    val wakeWordEngine: WakeWordEngine = WakeWordEngine.MICRO_WAKE_WORD,
    /**
     * Stop-word slot, stored per engine like [microWakeWords]/[openWakeWords]
     * (a Micro .tflite id is meaningless to open and vice versa).
     * Values: [STOP_WORD_BUILTIN] (default, model-free DSP), [STOP_WORD_NONE] (off),
     * or an installed model id of that engine (detections route to stop, not wake).
     */
    val microStopWordId: String = STOP_WORD_BUILTIN,
    val openStopWordId: String = STOP_WORD_BUILTIN,
    /** Sensitivity for a model-based stop slot; unused for builtin/none. -1 = manifest default. */
    val stopWordSensitivity: Float = -1f,
    val muted: Boolean = false,
    val wakeWordSensitivity1: Float = -1f,
    val wakeWordSensitivity2: Float = -1f,
    /**
     * Extra strictness level past the engine's native cutoff ceiling (0 = off,
     * 1 = offline re-verify / forced precision, 2 = level 1 + VAD / hit-gate tightening).
     * Stored separately from the sensitivity floats: those are shared between engines
     * and values outside a model's slider range are treated as the other engine's
     * leftovers, so a virtual level must never be encoded into them.
     */
    val wakeWordExtraStrictness1: Int = 0,
    val wakeWordExtraStrictness2: Int = 0,
    val audioSource: Int = MediaRecorder.AudioSource.MIC,
    val audioSourceExplicitlySet: Boolean = false,
    val noiseSuppressorEnabled: Boolean = true,
    /** WebRTC software NS on the satellite capture path; default off (mutex with hardware NS). */
    val softwareNsEnabled: Boolean = false,
    /** WebRTC NS policy tier; only applies when [softwareNsEnabled]. */
    val softwareNsStrength: SoftwareNsStrength = SoftwareNsStrength.LIGHT,
    val automaticGainControlEnabled: Boolean = true,
    val acousticEchoCancelerEnabled: Boolean = true,
    val softwareAecEnabled: Boolean = false,
    /**
     * When software AEC is on: temporarily bypass it while mic audio is streaming
     * to Home Assistant (after wake / while the user speaks). AEC resumes afterward.
     */
    val softwareAecPauseDuringSpeech: Boolean = false,
    /** Speex NLP residual suppress tier; only applies when [softwareAecEnabled]. */
    val softwareAecStrength: SoftwareAecStrength = SoftwareAecStrength.STANDARD,
    /** Adaptive filter tail / room size; only applies when [softwareAecEnabled]. */
    val softwareAecRoom: SoftwareAecRoom = SoftwareAecRoom.LIVING,
    val voicePrintEnabled: Boolean = false,
    val voicePrintEnrollmentMode: VoicePrintEnrollmentMode = VoicePrintEnrollmentMode.AUTO,
    val voicePrintManualUser0Samples: Int = 0,
    val voicePrintManualUser1Samples: Int = 0,
    /** Manual mode only: require enrolled voiceprint match before wake succeeds. */
    val voicePrintManualWakeVerifyEnabled: Boolean = false,
    val voicePrintUserNames: List<String> = emptyList(),
    /** Software PCM gain in dB for HA uplink streaming. Micro wake/stop detect uses ungained PCM. */
    val micGainDb: Int = 0,
    val audioProfileId: String = "",
    /** Preferred input device route; default follows the system. */
    val recordingPath: RecordingPath = RecordingPath.AUTO,
    /** How the user triggers a voice session — wake word, floating button, or both. */
    val wakeMode: WakeMode = WakeMode.VOICE,
    /** Quick Wake FAB gesture; only meaningful when [wakeMode] shows the button. */
    val quickWakeTrigger: QuickWakeTrigger = QuickWakeTrigger.TAP,
) {
    companion object {
        /** Stop slot sentinel: model-free native DSP detector (default). */
        const val STOP_WORD_BUILTIN = "builtin"
        /** Stop slot sentinel: stop-word detection disabled entirely. */
        const val STOP_WORD_NONE = "none"

        /** Inclusive software mic gain range (dB). Negative values attenuate. */
        const val MIC_GAIN_DB_MIN = -24
        const val MIC_GAIN_DB_MAX = 24

        fun clampMicGainDb(value: Int): Int = value.coerceIn(MIC_GAIN_DB_MIN, MIC_GAIN_DB_MAX)
    }
}

fun WakeWordEngine.toHaOption(): String = when (this) {
    WakeWordEngine.MICRO_WAKE_WORD -> "microWakeWord"
    WakeWordEngine.OPEN_WAKE_WORD -> "openWakeWord"
}

fun wakeWordEngineFromHaOption(value: String): WakeWordEngine? = when (value) {
    "microWakeWord", WakeWordEngine.MICRO_WAKE_WORD.name -> WakeWordEngine.MICRO_WAKE_WORD
    "openWakeWord", "vsWakeWord",
    WakeWordEngine.OPEN_WAKE_WORD.name, "VS_WAKE_WORD" -> WakeWordEngine.OPEN_WAKE_WORD
    else -> null
}

/**
 * ok_* (VS) ↔ okay_* (Micro) naming only — not a shared model identity.
 * Do not remap alexa/hey_* across engines; same string, different files.
 * Missing VS catalog ids simply drop out of [compatibleWakeWordIdsForEngine]
 * (caller falls back to ok_nabu) — never expand into a second WW slot.
 */
private fun engineMappedWakeWordId(engine: WakeWordEngine, normalizedId: String): String = when (normalizedId) {
    "ok_nabu", "okay_nabu" -> when (engine) {
        WakeWordEngine.MICRO_WAKE_WORD -> "okay_nabu"
        WakeWordEngine.OPEN_WAKE_WORD -> "ok_nabu"
    }
    "ok_computer", "okay_computer" -> when (engine) {
        WakeWordEngine.MICRO_WAKE_WORD -> "okay_computer"
        WakeWordEngine.OPEN_WAKE_WORD -> "ok_computer"
    }
    else -> normalizedId
}

fun wakeWordIdCandidatesForEngine(engine: WakeWordEngine, wakeWordId: String): List<String> {
    val normalizedId = wakeWordId
        .trim()
        .lowercase()
        .replace(Regex("[\\s-]+"), "_")
    val mappedId = engineMappedWakeWordId(engine, normalizedId)
    return listOf(mappedId, normalizedId, wakeWordId).distinct()
}

fun compatibleWakeWordIdsForEngine(
    engine: WakeWordEngine,
    wakeWordIds: List<String>,
    availableWakeWordIds: Set<String>
): List<String> = wakeWordIds
    .mapNotNull { wakeWordId ->
        // One saved selection → at most one active slot.
        wakeWordIdCandidatesForEngine(engine, wakeWordId)
            .firstOrNull { candidate -> candidate in availableWakeWordIds }
    }
    .distinct()
    .take(2)

fun MicrophoneSettings.activeWakeWordsForEngine(engine: WakeWordEngine): List<String> {
    val engineWakeWords = when (engine) {
        WakeWordEngine.MICRO_WAKE_WORD -> microWakeWords
        WakeWordEngine.OPEN_WAKE_WORD -> openWakeWords
    }
    if (engineWakeWords.isNotEmpty()) {
        return engineWakeWords.distinct().take(2)
    }
    // Legacy single-list migration only. Once either engine bucket is populated,
    // never fall back to shared wakeWords — hey_jarvis/alexa/… share ids across
    // Micro (.tflite) and open (.onnx), so an open selection would otherwise leak into
    // Micro after an engine switch (and vice versa).
    val otherEngineWakeWords = when (engine) {
        WakeWordEngine.MICRO_WAKE_WORD -> openWakeWords
        WakeWordEngine.OPEN_WAKE_WORD -> microWakeWords
    }
    if (otherEngineWakeWords.isNotEmpty()) {
        return emptyList()
    }
    if (wakeWordEngine != engine) {
        return emptyList()
    }
    return wakeWords
        .ifEmpty { if (wakeWord.isNotBlank()) listOf(wakeWord) else emptyList() }
        .distinct()
        .take(2)
}

fun MicrophoneSettings.activeStopWordForEngine(engine: WakeWordEngine): String {
    val value = when (engine) {
        WakeWordEngine.MICRO_WAKE_WORD -> microStopWordId
        WakeWordEngine.OPEN_WAKE_WORD -> openStopWordId
    }
    return value.ifBlank { MicrophoneSettings.STOP_WORD_BUILTIN }
}

fun MicrophoneSettings.withStopWordForEngine(
    engine: WakeWordEngine,
    stopWordId: String
): MicrophoneSettings {
    val normalized = stopWordId.ifBlank { MicrophoneSettings.STOP_WORD_BUILTIN }
    return when (engine) {
        WakeWordEngine.MICRO_WAKE_WORD -> copy(microStopWordId = normalized)
        WakeWordEngine.OPEN_WAKE_WORD -> copy(openStopWordId = normalized)
    }
}

fun MicrophoneSettings.withActiveWakeWordsForEngine(
    engine: WakeWordEngine,
    activeWakeWords: List<String>
): MicrophoneSettings {
    val normalized = activeWakeWords.distinct().take(2)
    val updated = when (engine) {
        WakeWordEngine.MICRO_WAKE_WORD -> copy(microWakeWords = normalized)
        WakeWordEngine.OPEN_WAKE_WORD -> copy(openWakeWords = normalized)
    }
    return if (wakeWordEngine == engine) {
        updated.copy(wakeWord = normalized.firstOrNull().orEmpty(), wakeWords = normalized)
    } else {
        updated
    }
}

fun MicrophoneSettings.withWakeWordEngine(
    engine: WakeWordEngine,
    activeWakeWords: List<String>
): MicrophoneSettings {
    val normalized = activeWakeWords.distinct().take(2)
    val currentEngine = wakeWordEngine
    val currentActiveWakeWords = activeWakeWordsForEngine(currentEngine)
    val withCurrentEngineSaved = if (currentActiveWakeWords.isNotEmpty()) {
        when (currentEngine) {
            WakeWordEngine.MICRO_WAKE_WORD -> copy(microWakeWords = currentActiveWakeWords)
            WakeWordEngine.OPEN_WAKE_WORD -> copy(openWakeWords = currentActiveWakeWords)
        }
    } else {
        this
    }
    val updated = withCurrentEngineSaved.withActiveWakeWordsForEngine(engine, normalized)
    return if (normalized.isNotEmpty()) {
        updated.copy(wakeWordEngine = engine, wakeWord = normalized.first(), wakeWords = normalized)
    } else {
        // Clear shared legacy fields — do not keep the previous engine's ids
        // under wakeWords when the target bucket has nothing to restore.
        updated.copy(wakeWordEngine = engine, wakeWord = "", wakeWords = emptyList())
    }
}

fun MicrophoneSettings.resolveAudioSource(profile: com.example.ava.audio.DeviceAudioProfile): Int {
    return if (audioSourceExplicitlySet) audioSource else profile.captureAudioSource
}

val Context.microphoneSettingsStore: DataStore<MicrophoneSettings> by dataStore(
    fileName = "microphone_settings.json",
    serializer = SettingsSerializer(MicrophoneSettings.serializer(), MicrophoneSettings()),
    corruptionHandler = defaultCorruptionHandler(MicrophoneSettings())
)

class MicrophoneSettingsStore(dataStore: DataStore<MicrophoneSettings>) :
    SettingsStoreImpl<MicrophoneSettings>(dataStore, MicrophoneSettings()) {
    val wakeWord =
        SettingState(getFlow().map { it.wakeWord }) { value ->
            // Route single-id writes into the active engine bucket only.
            update { settings ->
                val engine = settings.wakeWordEngine
                val current = settings.activeWakeWordsForEngine(engine)
                val next = when {
                    current.isEmpty() -> listOf(value)
                    else -> listOf(value) + current.drop(1)
                }.filter { it.isNotBlank() }.distinct().take(2)
                settings.withActiveWakeWordsForEngine(engine, next)
            }
        }
    val wakeWords =
        SettingState(getFlow().map { it.wakeWords }) { value ->
            update { settings ->
                settings.withActiveWakeWordsForEngine(settings.wakeWordEngine, value)
            }
        }
    // Do not bare-copy engine: that leaves shared wakeWords pointing at the other
    // engine's ids (hey_jarvis/alexa share names). Always go through withWakeWordEngine.
    val wakeWordEngine =
        SettingState(getFlow().map { it.wakeWordEngine }) { value ->
            update {
                if (it.wakeWordEngine == value) it
                else {
                    val restored = it.activeWakeWordsForEngine(value)
                    it.withWakeWordEngine(value, restored)
                }
            }
        }
    val muted =
        SettingState(getFlow().map { it.muted }) { value -> update { it.copy(muted = value) } }
    val wakeWordSensitivity1 =
        SettingState(getFlow().map { it.wakeWordSensitivity1 }) { value -> update { it.copy(wakeWordSensitivity1 = value) } }
    val wakeWordSensitivity2 =
        SettingState(getFlow().map { it.wakeWordSensitivity2 }) { value -> update { it.copy(wakeWordSensitivity2 = value) } }
    val wakeWordExtraStrictness1 =
        SettingState(getFlow().map { it.wakeWordExtraStrictness1 }) { value -> update { it.copy(wakeWordExtraStrictness1 = value) } }
    val wakeWordExtraStrictness2 =
        SettingState(getFlow().map { it.wakeWordExtraStrictness2 }) { value -> update { it.copy(wakeWordExtraStrictness2 = value) } }
    val audioSource =
        SettingState(getFlow().map { it.audioSource }) { value -> update { it.copy(audioSource = value) } }
    val audioSourceExplicitlySet =
        SettingState(getFlow().map { it.audioSourceExplicitlySet }) { value -> update { it.copy(audioSourceExplicitlySet = value) } }
    val noiseSuppressorEnabled =
        SettingState(getFlow().map { it.noiseSuppressorEnabled }) { value ->
            update { settings ->
                // Mutex with software NS: enabling hardware turns software off.
                if (value) {
                    settings.copy(
                        noiseSuppressorEnabled = true,
                        softwareNsEnabled = false,
                    )
                } else {
                    settings.copy(noiseSuppressorEnabled = false)
                }
            }
        }
    val softwareNsEnabled =
        SettingState(getFlow().map { it.softwareNsEnabled }) { value ->
            update { settings ->
                // Mutex with hardware NS: enabling software turns hardware off.
                if (value) {
                    settings.copy(
                        softwareNsEnabled = true,
                        noiseSuppressorEnabled = false,
                    )
                } else {
                    settings.copy(softwareNsEnabled = false)
                }
            }
        }
    val softwareNsStrength =
        SettingState(getFlow().map { it.softwareNsStrength }) { value ->
            update { it.copy(softwareNsStrength = value) }
        }
    val automaticGainControlEnabled =
        SettingState(getFlow().map { it.automaticGainControlEnabled }) { value -> update { it.copy(automaticGainControlEnabled = value) } }
    val acousticEchoCancelerEnabled =
        SettingState(getFlow().map { it.acousticEchoCancelerEnabled }) { value ->
            update { settings ->
                // Mutex with software AEC: enabling hardware turns software off.
                if (value) {
                    settings.copy(
                        acousticEchoCancelerEnabled = true,
                        softwareAecEnabled = false,
                    )
                } else {
                    settings.copy(acousticEchoCancelerEnabled = false)
                }
            }
        }
    val softwareAecEnabled =
        SettingState(getFlow().map { it.softwareAecEnabled }) { value ->
            update { settings ->
                // Mutex with hardware AEC: enabling software turns hardware off.
                if (value) {
                    settings.copy(
                        softwareAecEnabled = true,
                        acousticEchoCancelerEnabled = false,
                    )
                } else {
                    settings.copy(softwareAecEnabled = false)
                }
            }
        }
    val softwareAecPauseDuringSpeech =
        SettingState(getFlow().map { it.softwareAecPauseDuringSpeech }) { value ->
            update { it.copy(softwareAecPauseDuringSpeech = value) }
        }
    val softwareAecStrength =
        SettingState(getFlow().map { it.softwareAecStrength }) { value ->
            update { it.copy(softwareAecStrength = value) }
        }
    val softwareAecRoom =
        SettingState(getFlow().map { it.softwareAecRoom }) { value ->
            update { it.copy(softwareAecRoom = value) }
        }
    val voicePrintEnabled =
        SettingState(getFlow().map { it.voicePrintEnabled }) { value -> update { it.copy(voicePrintEnabled = value) } }
    val voicePrintEnrollmentMode =
        SettingState(getFlow().map { it.voicePrintEnrollmentMode }) { value ->
            update { it.copy(voicePrintEnrollmentMode = value) }
        }
    val voicePrintManualUser0Samples =
        SettingState(getFlow().map { it.voicePrintManualUser0Samples }) { value ->
            update { it.copy(voicePrintManualUser0Samples = value.coerceIn(0, MANUAL_ENROLLMENT_SAMPLES)) }
        }
    val voicePrintManualUser1Samples =
        SettingState(getFlow().map { it.voicePrintManualUser1Samples }) { value ->
            update { it.copy(voicePrintManualUser1Samples = value.coerceIn(0, MANUAL_ENROLLMENT_SAMPLES)) }
        }
    val voicePrintManualWakeVerifyEnabled =
        SettingState(getFlow().map { it.voicePrintManualWakeVerifyEnabled }) { value ->
            update { it.copy(voicePrintManualWakeVerifyEnabled = value) }
        }
    val voicePrintUserNames =
        SettingState(getFlow().map { it.voicePrintUserNames }) { value ->
            update { it.copy(voicePrintUserNames = value.map(String::trim).take(2)) }
        }
    val micGainDb =
        SettingState(getFlow().map { it.micGainDb }) { value ->
            update { it.copy(micGainDb = MicrophoneSettings.clampMicGainDb(value)) }
        }
    val audioProfileId =
        SettingState(getFlow().map { it.audioProfileId }) { value -> update { it.copy(audioProfileId = value) } }
    val recordingPath =
        SettingState(getFlow().map { it.recordingPath }) { value -> update { it.copy(recordingPath = value) } }

    val stopWordSensitivity =
        SettingState(getFlow().map { it.stopWordSensitivity }) { value ->
            update { it.copy(stopWordSensitivity = value) }
        }
    val wakeMode =
        SettingState(getFlow().map { it.wakeMode }) { value ->
            update { it.copy(wakeMode = value) }
        }
    val quickWakeTrigger =
        SettingState(getFlow().map { it.quickWakeTrigger }) { value ->
            update { it.copy(quickWakeTrigger = value) }
        }

    suspend fun saveActiveWakeWordsForEngine(engine: WakeWordEngine, activeWakeWords: List<String>) =
        update { it.withActiveWakeWordsForEngine(engine, activeWakeWords) }

    suspend fun saveStopWordForEngine(engine: WakeWordEngine, stopWordId: String) =
        update { it.withStopWordForEngine(engine, stopWordId) }

    suspend fun saveWakeWordEngine(engine: WakeWordEngine, activeWakeWords: List<String>) =
        update { it.withWakeWordEngine(engine, activeWakeWords) }

    companion object {
        const val MANUAL_ENROLLMENT_SAMPLES = 5
    }
}
