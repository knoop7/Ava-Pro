package com.example.ava.detection

import androidx.annotation.StringRes
import com.example.ava.R

/**
 * VyloEdge-AudioEvents-Production (Edge Impulse) classifier head.
 *
 * The bundled [audio_events.tflite] exposes **7** softmax classes — there is no
 * background/unknown bucket, so quiet-room windows must be rejected by signal-quality
 * gates rather than a model label.
 *
 * | Index | Label           | Notes                          |
 * |-------|-----------------|--------------------------------|
 * | 0     | alarm           | Smoke/CO alarm tones           |
 * | 1     | baby_cry        | Noise / crying (baby)          |
 * | 2     | cough           | Coughing                       |
 * | 3     | doorbell        | Doorbell / chime               |
 * | 4     | glass_breaking  | Glass shatter / impact         |
 * | 5     | siren           | Emergency / outdoor sirens     |
 * | 6     | speech          | Speech and conversation        |
 */
data class AudioEventTypeEntry(
    val label: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
)

object AudioEventCatalog {
    const val MODEL_CLASS_COUNT = 7

    val MODEL_LABELS: List<String> = AUDIO_EVENT_LABELS.toList()

    /** All 7 model classes, in classifier index order. */
    val ENTRIES: List<AudioEventTypeEntry> = listOf(
        AudioEventTypeEntry(
            label = "alarm",
            titleRes = R.string.settings_audio_event_type_alarm,
            descRes = R.string.settings_audio_event_type_alarm_desc,
        ),
        AudioEventTypeEntry(
            label = "baby_cry",
            titleRes = R.string.settings_audio_event_type_baby_cry,
            descRes = R.string.settings_audio_event_type_baby_cry_desc,
        ),
        AudioEventTypeEntry(
            label = "cough",
            titleRes = R.string.settings_audio_event_type_cough,
            descRes = R.string.settings_audio_event_type_cough_desc,
        ),
        AudioEventTypeEntry(
            label = "doorbell",
            titleRes = R.string.settings_audio_event_type_doorbell,
            descRes = R.string.settings_audio_event_type_doorbell_desc,
        ),
        AudioEventTypeEntry(
            label = "glass_breaking",
            titleRes = R.string.settings_audio_event_type_glass_breaking,
            descRes = R.string.settings_audio_event_type_glass_breaking_desc,
        ),
        AudioEventTypeEntry(
            label = "siren",
            titleRes = R.string.settings_audio_event_type_siren,
            descRes = R.string.settings_audio_event_type_siren_desc,
        ),
        AudioEventTypeEntry(
            label = "speech",
            titleRes = R.string.settings_audio_event_type_speech,
            descRes = R.string.settings_audio_event_type_speech_desc,
        ),
    )

    val ALL_LABELS: Set<String> = ENTRIES.map { it.label }.toSet()

    /** Fresh install default: only speech monitoring. */
    val DEFAULT_MONITORED_LABELS: Set<String> = setOf("speech")

    fun sanitizeMonitoredLabels(stored: Set<String>): Set<String> {
        val filtered = stored.filter { it in ALL_LABELS }.toSet()
        return filtered.ifEmpty { DEFAULT_MONITORED_LABELS }
    }

    fun withLabelToggled(monitored: Set<String>, label: String, enabled: Boolean): Set<String> {
        if (label !in ALL_LABELS) return monitored
        val next = monitored.toMutableSet()
        if (enabled) {
            next.add(label)
        } else {
            next.remove(label)
        }
        return next
    }
}
