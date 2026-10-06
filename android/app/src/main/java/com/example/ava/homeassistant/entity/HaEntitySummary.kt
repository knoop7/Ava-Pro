package com.example.ava.homeassistant.entity

import org.json.JSONArray
import org.json.JSONObject

data class HaEntitySummary(
    val entityId: String,
    val name: String,
    val domain: String,
    val state: String,
) {
    val compactId: String get() = entityId
}

/**
 * One live HA state the host just fetched. Mini dumps the whole attribute bag;
 * we keep only speakable values (brightness, temperature, fan, position, volume, title).
 */
data class HaEntityLiveState(
    val entityId: String,
    val name: String,
    val domain: String,
    val state: String,
    val unit: String = "",
    val deviceClass: String = "",
    val lastChanged: String = "",
    val brightness: Int? = null,
    val brightnessPct: Int? = null,
    val temperature: Double? = null,
    val currentTemperature: Double? = null,
    val humidity: Int? = null,
    val currentHumidity: Int? = null,
    val position: Int? = null,
    val percentage: Int? = null,
    val volumePct: Int? = null,
    val colorTempKelvin: Int? = null,
    val hvacMode: String = "",
    val fanMode: String = "",
    val swingMode: String = "",
    val presetMode: String = "",
    val oscillating: Boolean? = null,
    val direction: String = "",
    val tiltPosition: Int? = null,
    val muted: Boolean? = null,
    val source: String = "",
    val rgbColor: List<Int>? = null,
    val effect: String = "",
    val colorName: String = "",
    val mediaTitle: String = "",
    val mediaArtist: String = "",
    val mediaAlbum: String = "",
) {
    fun putSpeakable(into: JSONObject): JSONObject {
        brightness?.let { into.put("brightness", it) }
        brightnessPct?.let { into.put("brightness_pct", it) }
        temperature?.let { putNumber(into, "temperature", it) }
        currentTemperature?.let { putNumber(into, "current_temperature", it) }
        humidity?.let { into.put("humidity", it) }
        currentHumidity?.let { into.put("current_humidity", it) }
        position?.let { into.put("position", it) }
        tiltPosition?.let { into.put("tilt_position", it) }
        percentage?.let { into.put("percentage", it) }
        volumePct?.let { into.put("volume_pct", it) }
        colorTempKelvin?.let { into.put("color_temp_kelvin", it) }
        rgbColor?.let { into.put("rgb_color", JSONArray(it)) }
        if (hvacMode.isNotBlank()) into.put("hvac_mode", hvacMode)
        if (fanMode.isNotBlank()) into.put("fan_mode", fanMode)
        if (swingMode.isNotBlank()) into.put("swing_mode", swingMode)
        if (presetMode.isNotBlank()) into.put("preset_mode", presetMode)
        oscillating?.let { into.put("oscillating", it) }
        if (direction.isNotBlank()) into.put("direction", direction)
        muted?.let { into.put("muted", it) }
        if (source.isNotBlank()) into.put("source", source)
        if (effect.isNotBlank()) into.put("effect", effect)
        if (colorName.isNotBlank()) into.put("color_name", colorName)
        if (mediaTitle.isNotBlank()) into.put("media_title", mediaTitle)
        if (mediaArtist.isNotBlank()) into.put("media_artist", mediaArtist)
        if (mediaAlbum.isNotBlank()) into.put("media_album", mediaAlbum)
        return into
    }

    companion object {
        fun fromRest(obj: JSONObject): HaEntityLiveState? {
            val id = obj.optString("entity_id").trim()
            if (id.isEmpty() || '.' !in id) return null
            val attrs = obj.optJSONObject("attributes")
            val name = attrs?.optString("friendly_name").orEmpty()
                .ifBlank { id.substringAfterLast('.').replace('_', ' ') }
            val brightness = attrs?.intIn("brightness", 0, 255)
            val brightnessPct = attrs?.intIn("brightness_pct", 0, 100)
                ?: brightness?.let { kotlin.math.round(it * 100.0 / 255.0).toInt().coerceIn(0, 100) }
            val volumePct = attrs?.intIn("volume_pct", 0, 100)
                ?: attrs?.finite("volume_level")?.let { kotlin.math.round(it * 100.0).toInt().coerceIn(0, 100) }
            return HaEntityLiveState(
                entityId = id,
                name = name,
                domain = id.substringBefore('.', ""),
                state = obj.optString("state"),
                unit = attrs?.optString("unit_of_measurement").orEmpty(),
                deviceClass = attrs?.optString("device_class").orEmpty(),
                lastChanged = obj.optString("last_changed").ifBlank { obj.optString("last_updated") },
                brightness = brightness,
                brightnessPct = brightnessPct,
                temperature = attrs?.finite("temperature"),
                currentTemperature = attrs?.finite("current_temperature"),
                humidity = attrs?.intIn("humidity", 0, 100),
                currentHumidity = attrs?.intIn("current_humidity", 0, 100),
                position = attrs?.intIn("current_position", 0, 100) ?: attrs?.intIn("position", 0, 100),
                percentage = attrs?.intIn("percentage", 0, 100),
                volumePct = volumePct,
                colorTempKelvin = attrs?.intIn("color_temp_kelvin", 1000, 10000),
                hvacMode = attrs?.shortText("hvac_mode").orEmpty(),
                fanMode = attrs?.shortText("fan_mode").orEmpty(),
                swingMode = attrs?.shortText("swing_mode").orEmpty(),
                presetMode = attrs?.shortText("preset_mode").orEmpty(),
                oscillating = attrs?.flag("oscillating"),
                direction = attrs?.shortText("direction").orEmpty(),
                tiltPosition = attrs?.intIn("current_tilt_position", 0, 100)
                    ?: attrs?.intIn("tilt_position", 0, 100),
                muted = attrs?.flag("is_volume_muted"),
                source = attrs?.shortText("source", 40).orEmpty(),
                rgbColor = attrs?.rgb(),
                effect = attrs?.shortText("effect", 40).orEmpty(),
                colorName = attrs?.shortText("color_name").orEmpty(),
                mediaTitle = attrs?.shortText("media_title", 80).orEmpty(),
                mediaArtist = attrs?.shortText("media_artist", 80).orEmpty(),
                mediaAlbum = attrs?.shortText("media_album", 80).orEmpty(),
            )
        }

        private fun JSONObject.finite(key: String): Double? {
            if (!has(key) || isNull(key)) return null
            val n = when (val raw = opt(key)) {
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull()
                else -> null
            } ?: return null
            return n.takeIf { it.isFinite() }
        }

        private fun JSONObject.intIn(key: String, min: Int, max: Int): Int? =
            finite(key)?.let { kotlin.math.round(it).toInt().takeIf { n -> n in min..max } }

        private fun JSONObject.shortText(key: String, max: Int = 24): String? {
            val text = optString(key).trim()
            if (text.isEmpty() || text == "null" || text == "unknown" || text == "unavailable") return null
            return text.take(max)
        }

        private fun JSONObject.flag(key: String): Boolean? {
            if (!has(key) || isNull(key)) return null
            return when (val raw = opt(key)) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                is String -> when (raw.trim().lowercase()) {
                    "true", "on", "yes" -> true
                    "false", "off", "no" -> false
                    else -> null
                }
                else -> null
            }
        }

        private fun JSONObject.rgb(): List<Int>? {
            val arr = optJSONArray("rgb_color") ?: return null
            if (arr.length() != 3) return null
            val parts = List(3) { arr.optInt(it, -1) }
            if (parts.any { it !in 0..255 }) return null
            return parts
        }

        private fun putNumber(into: JSONObject, key: String, value: Double) {
            if (kotlin.math.abs(value - value.toLong()) < 1e-9) into.put(key, value.toLong())
            else into.put(key, value)
        }
    }
}