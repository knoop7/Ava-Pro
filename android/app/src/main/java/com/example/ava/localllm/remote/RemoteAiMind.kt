package com.example.ava.localllm.remote

import android.content.Context
import com.example.ava.homeassistant.HaManager
import com.example.ava.localllm.LocalLlmManager
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.microphoneSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.voiceprint.VoicePrintStorage
import com.example.ava.voiceprint.voicePrintDisplayName
/**
 * Live prompt facts. A feature that is off writes nothing — no section, no
 * status token, no "disabled" line. Same rule for every later Ava marker.
 */
object RemoteAiMind {

    data class Facts(
        val speaker: String?,
        val deviceName: String?,
        /** Registry area of this Ava's own media_player, when Home Assistant knows it. */
        val area: String?,
        val haSignedIn: Boolean,
    )

    fun snapshot(context: Context): Facts {
        val app = context.applicationContext
        return Facts(
            speaker = speakerOrNull(app),
            deviceName = thisAvaName(app),
            area = areaOrNull(app),
            haSignedIn = haSignedIn(),
        )
    }

    fun thisAvaName(context: Context): String? {
        val name = runCatching {
            VoiceSatelliteSettingsStore(context.applicationContext.voiceSatelliteSettingsStore)
                .getCached().name.trim()
        }.getOrDefault("")
        return name.ifBlank { null }
    }

    /**
     * This Ava's room. Derived from the media_player it publishes to Home
     * Assistant, so an unconfigured or offline install simply writes nothing.
     */
    fun areaOrNull(context: Context): String? {
        val app = context.applicationContext
        val entityId = runCatching {
            VoiceSatelliteSettingsStore(app.voiceSatelliteSettingsStore)
                .getCached().haMediaPlayerEntity.trim()
        }.getOrDefault("")
        if (entityId.isEmpty()) return null
        return LocalLlmManager.get()?.deviceIndexSnapshot()?.areaOf(entityId)?.ifBlank { null }
    }

    /**
     * Voice print off / no template / two people and nobody identified → null.
     * One enrolled person → that name. Two enrolled → only the current one.
     * Names stay [voicePrintDisplayName].
     */
    fun speakerOrNull(context: Context): String? {
        val app = context.applicationContext
        val mic = runCatching {
            MicrophoneSettingsStore(app.microphoneSettingsStore).getCached()
        }.getOrNull()
        if (mic?.voicePrintEnabled != true) return null
        val names = mic.voicePrintUserNames
        val engine = mic.wakeWordEngine
        val enrolled = (0..1).filter { VoicePrintStorage.hasUserProfile(app, it, engine) }
        if (enrolled.isEmpty()) return null
        if (enrolled.size == 1) return voicePrintDisplayName(enrolled[0], names)
        return resolveCurrentSpeaker(app, enrolled, names)
    }

    private fun resolveCurrentSpeaker(
        app: Context,
        enrolled: List<Int>,
        names: List<String>,
    ): String? {
        val probe = VoiceSatelliteService.getInstance()?.voicePrintProbe() ?: return null
        val live = probe.identifiedIndex?.takeIf { it in enrolled }
        if (live != null) return voicePrintDisplayName(live, names)
        val age = probe.lastMatchAgeMs
        if (age < 0L || age >= SPEAKER_MATCH_TTL_MS) return null
        val last = probe.lastMatchStatus.trim()
        return enrolled.firstNotNullOfOrNull { index ->
            val label = voicePrintDisplayName(index, names)
            label.takeIf { it == last }
        }
    }

    fun haSignedIn(): Boolean {
        val ha = HaManager.get()?.settingsStore?.getCached() ?: return false
        return ha.serverUrl.isNotBlank() && ha.accessToken.isNotBlank()
    }

    private const val SPEAKER_MATCH_TTL_MS = 120_000L
}
