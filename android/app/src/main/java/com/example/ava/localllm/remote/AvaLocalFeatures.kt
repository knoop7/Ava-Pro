package com.example.ava.localllm.remote

import android.content.Context
import com.example.ava.R
import com.example.ava.localllm.HaSpokenHear
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.playerSettingsStore
import org.json.JSONObject
import java.util.Locale

/**
 * The overlays this device can show without being published to Home
 * Assistant, under the names the user says out loud.
 *
 * These are features of this app, so hearing one must never cost a house
 * search. [AvaPublishedEntities] already covers every overlay whose Home
 * Assistant switch is registered; this fills the gap underneath it, for the
 * user who turned an overlay on but left "publish to Home Assistant" off.
 *
 * Only four overlays belong here, and the reason is in `VoiceSatelliteService`:
 * each one watches its own visible flag and shows or hides on the feature
 * switch alone. The browser, the music overlay, and the screensaver watch the
 * same kind of flag but return early unless their Home Assistant display flag
 * is also on — and once it is on they are published, so they are already
 * reachable the other way. Listing them here would either duplicate that or
 * let the model report a success for a write that did nothing.
 *
 * Ids are the ESPHome object ids, so a feature keeps one target either way and
 * the two lists dedupe exactly.
 */
object AvaLocalFeatures {

    /** A switchable overlay: what the user calls it, what to target, its state. */
    data class Feature(val id: String, val name: String, val on: Boolean) {
        /** Same shape a published entity reports, so one list reads as one kind. */
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("kind", "switch")
            .put("category", "none")
            .put("state", if (on) "on" else "off")
    }

    const val DREAM_CLOCK = "dream_clock_display"
    const val SIMPLE_CLOCK = "simple_clock_display"
    const val WEATHER = "weather_display"
    const val VOICE_MESSAGE = "voice_message_display"

    fun catalog(context: Context): List<Feature> {
        val app = context.applicationContext
        val player = runCatching {
            PlayerSettingsStore(app.playerSettingsStore).getCached()
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<Feature>(4)
        if (player.enableDreamClock) {
            out += Feature(
                DREAM_CLOCK,
                app.getString(R.string.entity_dream_clock_display),
                player.enableDreamClockVisible,
            )
        }
        if (player.enableScreensaver) {
            out += Feature(
                SIMPLE_CLOCK,
                app.getString(R.string.entity_simple_clock_display),
                player.enableScreensaverVisible,
            )
        }
        if (player.enableWeatherOverlay) {
            out += Feature(
                WEATHER,
                app.getString(R.string.entity_weather_display),
                player.enableWeatherOverlayVisible,
            )
        }
        if (player.enableVoiceMessageOverlay) {
            out += Feature(
                VOICE_MESSAGE,
                app.getString(R.string.entity_voice_message_display),
                player.enableVoiceMessageOverlayVisible,
            )
        }
        return out
    }

    fun hasAny(context: Context): Boolean = catalog(context).isNotEmpty()

    fun search(context: Context, spoken: String): List<Feature> {
        val key = normalize(spoken)
        if (key.isEmpty()) return emptyList()
        return catalog(context).filter { feature ->
            keys(feature).any { candidate -> HaSpokenHear.meets(key, candidate) }
        }
    }

    fun resolve(context: Context, spoken: String): List<Feature> {
        val hits = search(context, spoken)
        if (hits.size <= 1) return hits
        val key = normalize(spoken)
        hits.firstOrNull { normalize(it.id) == key || normalize(it.name) == key }?.let {
            return listOf(it)
        }
        val tight = hits.filter { key.contains(normalize(it.name)) }
        if (tight.size == 1) return tight
        return hits
    }

    fun nextAction(feature: Feature): JSONObject = JSONObject()
        .put("hint", "Call ava_self action=set target=${feature.id} on=true|false.")
        .put(
            "next_action",
            JSONObject()
                .put("tool", AvaSelfTools.NAME)
                .put("arguments", JSONObject().put("action", "set").put("target", feature.id))
                .put("required_arguments", org.json.JSONArray(listOf("on"))),
        )

    /** Null when the id is not one of these, so the caller can look elsewhere. */
    suspend fun set(context: Context, id: String, on: Boolean): Boolean? {
        val store = PlayerSettingsStore(context.applicationContext.playerSettingsStore)
        return when (id.trim()) {
            DREAM_CLOCK -> true.also { store.enableDreamClockVisible.set(on) }
            SIMPLE_CLOCK -> true.also { store.enableScreensaverVisible.set(on) }
            WEATHER -> true.also { store.enableWeatherOverlayVisible.set(on) }
            VOICE_MESSAGE -> true.also { store.enableVoiceMessageOverlayVisible.set(on) }
            else -> null
        }
    }

    private fun keys(feature: Feature): List<String> = listOf(
        normalize(feature.name),
        normalize("${feature.name} display"),
        normalize("${feature.name}显示"),
        normalize(feature.id),
        normalize(feature.id.replace('_', ' ')),
    ).filter { it.isNotEmpty() }

    private fun normalize(raw: String): String {
        val lower = raw.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return ""
        val sb = StringBuilder(lower.length)
        var space = false
        for (ch in lower) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
                space = false
            } else if (!space) {
                sb.append(' ')
                space = true
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }
}
