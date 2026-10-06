package com.example.ava.localllm

import com.example.ava.homeassistant.entity.HaEntitySummary

/** One concrete Home Assistant service call derived from a model tool call. */
data class HaServiceAction(
    /** `domain.service` */
    val service: String,
    val entity: HaEntitySummary,
    val data: Map<String, Any?> = emptyMap(),
)

data class LocalIntentPlan(
    val actions: List<HaServiceAction>,
    /** Entities whose state the user asked for (get_state). */
    val queries: List<HaEntitySummary>,
    /** Model emitted a device label that [DeviceIndex] could not uniquely match. */
    val rejected: List<String>,
) {
    val isEmpty: Boolean get() = actions.isEmpty() && queries.isEmpty()
}

/**
 * Grounds model output onto real entities and services. Pure function: no I/O,
 * so the settings test console can show exactly what *would* run.
 */
object LocalIntentExecutor {

    fun plan(calls: List<LocalToolCall>, index: DeviceIndex): LocalIntentPlan {
        val actions = ArrayList<HaServiceAction>()
        val queries = ArrayList<HaEntitySummary>()
        val rejected = ArrayList<String>()

        fun device(call: LocalToolCall, key: String = "device"): HaEntitySummary? {
            val label = call.arguments.optString(key).trim()
            if (label.isEmpty()) return null
            val domain = call.arguments.optString("domain").trim().ifEmpty { null }
            // Claw: exact id, then SmartDiscovery(name_contains) + optional domain.
            val entity = index.resolve(label, domain)
            if (entity == null) rejected += label
            return entity
        }

        for (call in calls) {
            when (call.name) {
                HaToolSet.TOOL_POWER -> {
                    val e = device(call) ?: continue
                    val args = HaSpokenLocale.canonicalizeArgs(call.arguments, e.domain)
                    val on = args.optBoolean("on", true)
                    val brightness = HaSpokenLocale.slotInt(args, "brightness_pct", 1..100)
                    val lightData = lightPayload(args, brightness)
                    when {
                        e.domain == "light" && lightData.isNotEmpty() -> {
                            actions += HaServiceAction("light.turn_on", e, lightData)
                        }
                        e.domain == "fan" -> {
                            var acted = false
                            if (brightness != null) {
                                actions += HaServiceAction("fan.set_percentage", e, mapOf("percentage" to brightness))
                                acted = true
                            }
                            if (args.optBoolean("oscillating", false)) {
                                actions += HaServiceAction("fan.oscillate", e, mapOf("oscillating" to true))
                                acted = true
                            }
                            if (!acted) {
                                actions += HaServiceAction(HaDomainActions.service(e.domain, on), e)
                            }
                        }
                        e.domain == "button" || e.domain == "input_button" -> if (on) {
                            actions += HaServiceAction("${e.domain}.press", e)
                        }
                        e.domain == "scene" && on -> actions += HaServiceAction("scene.turn_on", e)
                        e.domain == "camera" -> continue
                        else -> actions += HaServiceAction(HaDomainActions.service(e.domain, on), e)
                    }
                }

                HaToolSet.TOOL_COVER -> {
                    val e = device(call) ?: continue
                    val args = HaSpokenLocale.canonicalizeArgs(call.arguments, e.domain)
                    val position = HaSpokenLocale.slotInt(args, "position", 0..100)
                    if (position != null) {
                        actions += HaServiceAction("cover.set_cover_position", e, mapOf("position" to position))
                    } else {
                        val service = when (call.arguments.optString("action")) {
                            "open" -> "cover.open_cover"
                            "close" -> "cover.close_cover"
                            "stop" -> "cover.stop_cover"
                            else -> continue
                        }
                        actions += HaServiceAction(service, e)
                    }
                }

                HaToolSet.TOOL_VACUUM -> {
                    val e = device(call) ?: continue
                    val service = when (call.arguments.optString("action")) {
                        "start" -> "vacuum.start"
                        "stop" -> "vacuum.stop"
                        "pause" -> "vacuum.pause"
                        "return_to_base" -> "vacuum.return_to_base"
                        "locate" -> "vacuum.locate"
                        else -> continue
                    }
                    actions += HaServiceAction(service, e)
                }

                HaToolSet.TOOL_CLIMATE -> {
                    val e = device(call) ?: continue
                    val args = HaSpokenLocale.canonicalizeArgs(call.arguments, e.domain)
                    val temp = HaSpokenLocale.slotDouble(args, "temperature", 5.0..35.0)
                    val modeRaw = args.optString("hvac_mode").trim()
                    val mode = modeRaw.takeIf { it.isNotEmpty() }?.let { HaSpokenLocale.climateMode(it) ?: it }
                    if (mode != null) {
                        actions += HaServiceAction("climate.set_hvac_mode", e, mapOf("hvac_mode" to mode))
                    }
                    val fanRaw = args.optString("fan_mode").trim()
                    val fan = fanRaw.takeIf { it.isNotEmpty() }?.let { HaSpokenLocale.fanLevel(it) ?: it }
                    if (fan != null) {
                        actions += HaServiceAction("climate.set_fan_mode", e, mapOf("fan_mode" to fan))
                    }
                    if (temp != null) {
                        actions += HaServiceAction("climate.set_temperature", e, mapOf("temperature" to temp))
                    }
                }

                HaToolSet.TOOL_ACTIVATE -> {
                    val e = device(call, key = "name") ?: continue
                    val service = when (e.domain) {
                        "scene" -> "scene.turn_on"
                        "script" -> "script.turn_on"
                        "automation" -> "automation.trigger"
                        else -> continue
                    }
                    actions += HaServiceAction(service, e)
                }

                HaToolSet.TOOL_PLAY -> {
                    val rawQuery = call.arguments.optString("query").trim()
                    val query = HaPlayGuard.songQuery("", rawQuery, index) ?: continue
                    val labeled = call.arguments.optString("device").trim()
                    val player = if (labeled.isNotEmpty()) {
                        device(call) ?: continue
                    } else {
                        HaEntitySummary("", "", "media_player", "unknown")
                    }
                    val artist = call.arguments.optString("artist").trim()
                    val mediaClass = call.arguments.optString("media_class").trim()
                    actions += HaServiceAction(
                        HaToolSet.SERVICE_SEARCH_AND_PLAY,
                        player,
                        buildMap {
                            put("query", query)
                            if (artist.isNotEmpty()) put("artist", artist)
                            if (mediaClass.isNotEmpty()) put("media_class", mediaClass)
                        },
                    )
                }

                HaToolSet.TOOL_BATCH -> {
                    val domain = call.arguments.optString("domain").trim()
                    if (domain.isEmpty()) continue
                    val on = call.arguments.optBoolean("on", true)
                    val area = call.arguments.optString("area").trim().ifEmpty { null }
                    val hits = index.discover(domain = domain, area = area, limit = 80)
                    if (hits.isEmpty()) {
                        rejected += listOfNotNull(area, domain).joinToString(" ")
                        continue
                    }
                    for (e in hits) {
                        actions += HaServiceAction(HaDomainActions.service(e.domain, on), e)
                    }
                }

                HaToolSet.TOOL_MEDIA -> {
                    val e = device(call) ?: continue
                    val args = HaSpokenLocale.canonicalizeArgs(call.arguments, e.domain)
                    when (args.optString("action").ifEmpty { call.arguments.optString("action") }) {
                        "play" -> actions += HaServiceAction("media_player.media_play", e)
                        "pause" -> actions += HaServiceAction("media_player.media_pause", e)
                        "stop" -> actions += HaServiceAction("media_player.media_stop", e)
                        "next" -> actions += HaServiceAction("media_player.media_next_track", e)
                        "previous" -> actions += HaServiceAction("media_player.media_previous_track", e)
                        "volume_up" -> actions += HaServiceAction("media_player.volume_up", e)
                        "volume_down" -> actions += HaServiceAction("media_player.volume_down", e)
                        "mute" -> actions += HaServiceAction("media_player.volume_mute", e, mapOf("is_volume_muted" to true))
                        "unmute" -> actions += HaServiceAction("media_player.volume_mute", e, mapOf("is_volume_muted" to false))
                        "set_volume" -> {
                            val pct = HaSpokenLocale.slotInt(args, "volume_pct", 0..100) ?: continue
                            actions += HaServiceAction(
                                "media_player.volume_set",
                                e,
                                mapOf("volume_level" to pct / 100.0),
                            )
                        }
                    }
                }

                HaToolSet.TOOL_QUERY -> {
                    val e = device(call) ?: continue
                    queries += e
                }
            }
        }
        return LocalIntentPlan(actions, queries, rejected)
    }

    private fun lightPayload(args: org.json.JSONObject, brightness: Int?): Map<String, Any?> {
        val data = LinkedHashMap<String, Any?>()
        if (brightness != null) data["brightness_pct"] = brightness
        val kelvin = HaSpokenLocale.slotInt(args, "color_temp_kelvin", 2000..6500)
        if (kelvin != null) data["color_temp_kelvin"] = kelvin
        val rgb = args.optString("rgb_color").trim()
        if (rgb.isNotEmpty()) {
            val parts = rgb.split(',', ' ').mapNotNull { it.toIntOrNull() }
            if (parts.size == 3) data["rgb_color"] = parts
        } else {
            val rgbArr = args.optJSONArray("rgb_color")
            if (rgbArr != null && rgbArr.length() == 3) {
                val parts = (0 until 3).map { rgbArr.optInt(it, -1) }
                if (parts.all { it in 0..255 }) data["rgb_color"] = parts
            }
        }
        val spoken = args.optString("color_name").trim()
        if (spoken.isNotEmpty() && "color_temp_kelvin" !in data && "rgb_color" !in data) {
            data.putAll(
                (HaSpokenLocale.lightColor(spoken) ?: HaSpokenLocale.LightColor.Name(spoken))
                    .asServiceData(),
            )
        }
        return data
    }
}
