package com.example.ava.localllm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Claw `normalize_service_data` plus Intent-style service pick on the host:
 * spoken keys become HA fields, and ha_turn_on with a number/mode becomes
 * the real domain service. Faster than ListServices → ServiceHelp.
 */
object HaServicePayload {

    private val RESERVED = setOf(
        "domain", "service", "entity_id", "name", "data",
        "area_id", "device_id", "floor_id", "label_id", "target",
    )

    /** HA match tokens. Not a device name — sending them as entity_id raises `expected 'all' or 'none'`. */
    private val MATCH_TOKENS = setOf("all", "none", "any")

    fun isMatchToken(hint: String): Boolean =
        hint.trim().lowercase() in MATCH_TOKENS

    fun isHaEntityId(raw: String): Boolean =
        HA_ENTITY_ID.matches(raw.trim())
    private val COLOR_WORDS = setOf(
        "white", "red", "green", "blue", "yellow", "purple", "pink", "orange",
        "cyan", "magenta", "warm_white", "cold_white", "warm", "cool",
    )

    fun mergeOverflow(args: JSONObject): JSONObject {
        val out = JSONObject()
        val nested = args.opt("data")
        if (nested is JSONObject) copyInto(nested, out)
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in RESERVED) continue
            if (!out.has(key)) out.put(key, args.opt(key))
        }
        return out
    }

    /** Service fields only. Targeting stays on the host-resolved entity_id. */
    fun withoutTarget(data: Map<String, Any?>): Map<String, Any?> =
        data.filterKeys { it !in RESERVED }

    /**
     * Claw Intent rewrite: power + a field the user said becomes the service
     * that actually carries that field.
     */
    fun route(domain: String, requested: String, raw: JSONObject): String {
        liftBooleanColors(raw)
        val args = HaSpokenLocale.canonicalizeArgs(raw, domain)
        markUnmute(domain, requested, args)
        val resolved = HaDomainActions.resolve(domain, requested)
        fieldService(domain, resolved, args)?.let { return "$domain.$it" }
        return HaDomainActions.qualified(domain, resolved)
    }

    fun normalize(domain: String, service: String, raw: JSONObject): JSONObject {
        liftBooleanColors(raw)
        val args = HaSpokenLocale.canonicalizeArgs(raw, domain)
        markUnmute(domain, service, args)
        return when (domain) {
            "light" -> light(args, service)
            "climate" -> climate(args)
            "fan" -> fan(args, service)
            "cover" -> cover(args)
            "valve" -> valve(args)
            "media_player" -> media(args, service)
            "humidifier" -> humidifier(args)
            "water_heater" -> waterHeater(args)
            "vacuum" -> vacuum(args)
            "lock" -> lock(args)
            "alarm_control_panel" -> codeOnly(args)
            "number", "input_number", "counter" -> number(args)
            "select", "input_select" -> select(args)
            "siren" -> siren(args, service)
            "remote" -> remote(args)
            else -> args
        }
    }

    fun asMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in RESERVED) continue
            out[key] = obj.opt(key)
        }
        return out
    }

    private fun fieldService(domain: String, resolved: String, args: JSONObject): String? {
        if (resolved != "turn_on" && resolved != "turn_off") return null
        if (resolved == "turn_off") return null
        return when (domain) {
            "climate" -> when {
                hasAny(args, "temperature", "target_temp_high", "target_temp_low") -> "set_temperature"
                hasText(args, "hvac_mode") -> "set_hvac_mode"
                hasText(args, "fan_mode") -> "set_fan_mode"
                else -> null
            }
            "cover" -> when {
                args.has("tilt_position") -> "set_cover_tilt_position"
                args.has("position") -> "set_cover_position"
                else -> null
            }
            "valve" -> if (args.has("position")) "set_valve_position" else null
            "fan" -> when {
                args.has("percentage") || args.has("brightness_pct") -> "set_percentage"
                args.has("oscillating") -> "oscillate"
                hasText(args, "preset_mode") -> "set_preset_mode"
                hasText(args, "direction") -> "set_direction"
                else -> null
            }
            "media_player" -> when {
                args.has("volume_pct") || args.has("volume_level") -> "volume_set"
                args.has("is_volume_muted") -> "volume_mute"
                hasText(args, "source") -> "select_source"
                else -> null
            }
            "humidifier" -> when {
                args.has("humidity") -> "set_humidity"
                hasText(args, "mode") -> "set_mode"
                else -> null
            }
            "water_heater" -> when {
                args.has("temperature") -> "set_temperature"
                hasText(args, "operation_mode") -> "set_operation_mode"
                else -> null
            }
            "vacuum" -> if (hasText(args, "fan_speed")) "set_fan_speed" else null
            "number", "input_number" -> if (args.has("value")) "set_value" else null
            "select", "input_select" -> if (hasText(args, "option")) "select_option" else null
            else -> null
        }
    }

    private fun light(args: JSONObject, service: String): JSONObject {
        if (service != "turn_on" && service != "toggle") return args
        val out = JSONObject()
        val pct = HaSpokenLocale.slotInt(args, "brightness_pct", 1..100)
        val raw = HaSpokenLocale.slotInt(args, "brightness", 0..255)
        when {
            pct != null -> out.put("brightness_pct", pct)
            raw != null -> out.put("brightness", raw)
        }
        val kelvin = HaSpokenLocale.slotInt(args, "color_temp_kelvin", 2000..6500)
        if (kelvin != null) out.put("color_temp_kelvin", kelvin)
        copyRgb(args, out)
        copyHs(args, out)
        val spoken = args.optString("color_name").trim()
        if (spoken.isNotEmpty() && !out.has("color_temp_kelvin") && !out.has("rgb_color")) {
            val color = HaSpokenLocale.lightColor(spoken) ?: HaSpokenLocale.LightColor.Name(spoken)
            for ((k, v) in color.asServiceData()) out.put(k, v)
        }
        HaSpokenLocale.slotDouble(args, "transition", 0.0..120.0)?.let { out.put("transition", it) }
        val effect = args.optString("effect").trim()
        if (effect.isNotEmpty()) out.put("effect", effect)
        return out
    }

    private fun climate(args: JSONObject): JSONObject {
        val out = JSONObject()
        HaSpokenLocale.slotDouble(args, "temperature", 5.0..40.0)?.let { out.put("temperature", it) }
        HaSpokenLocale.slotDouble(args, "target_temp_high", 5.0..40.0)?.let { out.put("target_temp_high", it) }
        HaSpokenLocale.slotDouble(args, "target_temp_low", 5.0..40.0)?.let { out.put("target_temp_low", it) }
        HaSpokenLocale.slotInt(args, "humidity", 0..100)?.let { out.put("humidity", it) }
        putMapped(args, out, "hvac_mode") { HaSpokenLocale.climateMode(it) ?: it }
        putMapped(args, out, "preset_mode") { HaSpokenLocale.climateMode(it) ?: it }
        putMapped(args, out, "fan_mode") { HaSpokenLocale.fanLevel(it) ?: it }
        putMapped(args, out, "swing_mode") { it }
        return if (out.length() > 0) out else args
    }

    private fun fan(args: JSONObject, service: String): JSONObject {
        val pct = HaSpokenLocale.slotInt(args, "brightness_pct", 0..100)
            ?: HaSpokenLocale.slotInt(args, "percentage", 0..100)
        if (pct != null && (service == "set_percentage" || service == "turn_on")) {
            return JSONObject().put("percentage", pct)
        }
        if (service == "oscillate" || args.has("oscillating")) {
            val on = HaSpokenLocale.spokenBool(args.opt("oscillating")) ?: true
            return JSONObject().put("oscillating", on)
        }
        if (service == "set_preset_mode" || hasText(args, "preset_mode")) {
            val mode = args.optString("preset_mode").trim()
            return JSONObject().put("preset_mode", HaSpokenLocale.fanLevel(mode) ?: mode)
        }
        if (service == "set_direction" || hasText(args, "direction")) {
            val dir = args.optString("direction").trim().lowercase()
            val mapped = when {
                dir == "reverse" || dir.contains("反") -> "reverse"
                dir == "forward" || dir.contains("正") -> "forward"
                else -> dir
            }
            if (mapped.isNotEmpty()) return JSONObject().put("direction", mapped)
        }
        return args
    }

    private fun cover(args: JSONObject): JSONObject {
        val tilt = HaSpokenLocale.slotInt(args, "tilt_position", 0..100)
        if (tilt != null) return JSONObject().put("tilt_position", tilt)
        val pos = HaSpokenLocale.slotInt(args, "position", 0..100)
        return if (pos != null) JSONObject().put("position", pos) else args
    }

    private fun valve(args: JSONObject): JSONObject {
        val pos = HaSpokenLocale.slotInt(args, "position", 0..100)
        return if (pos != null) JSONObject().put("position", pos) else args
    }

    private fun media(args: JSONObject, service: String): JSONObject {
        if (service == "volume_mute" || service == "unmute" || args.has("is_volume_muted")) {
            val muted = HaSpokenLocale.spokenBool(args.opt("is_volume_muted")) ?: (service != "unmute")
            return JSONObject().put("is_volume_muted", muted)
        }
        if (service == "select_source" || hasText(args, "source")) {
            val source = args.optString("source").trim()
            if (source.isNotEmpty()) return JSONObject().put("source", source)
        }
        if (service != "volume_set") return args
        val pct = HaSpokenLocale.slotInt(args, "volume_pct", 0..100)
        val level = args.optDouble("volume_level", Double.NaN)
        return when {
            pct != null -> JSONObject().put("volume_level", pct / 100.0)
            !level.isNaN() -> JSONObject().put("volume_level", if (level > 1.0) level / 100.0 else level)
            else -> args
        }
    }

    private fun humidifier(args: JSONObject): JSONObject {
        val out = JSONObject()
        HaSpokenLocale.slotInt(args, "humidity", 0..100)?.let { out.put("humidity", it) }
        putMapped(args, out, "mode") { HaSpokenLocale.climateMode(it) ?: it }
        return if (out.length() > 0) out else args
    }

    private fun waterHeater(args: JSONObject): JSONObject {
        val out = JSONObject()
        HaSpokenLocale.slotDouble(args, "temperature", 20.0..80.0)?.let { out.put("temperature", it) }
        putMapped(args, out, "operation_mode") { HaSpokenLocale.climateMode(it) ?: it }
        return if (out.length() > 0) out else args
    }

    private fun vacuum(args: JSONObject): JSONObject {
        val speed = args.optString("fan_speed").trim()
        if (speed.isEmpty()) return args
        return JSONObject().put("fan_speed", HaSpokenLocale.vacuumSpeed(speed) ?: speed)
    }

    private fun lock(args: JSONObject): JSONObject {
        val code = args.optString("code").trim()
        return if (code.isNotEmpty()) JSONObject().put("code", code) else args
    }

    private fun codeOnly(args: JSONObject): JSONObject {
        val code = args.optString("code").trim()
        return if (code.isNotEmpty()) JSONObject().put("code", code) else args
    }

    private fun number(args: JSONObject): JSONObject {
        val n = when (val v = args.opt("value")) {
            is Number -> v.toDouble()
            is String -> HaSpokenLocale.parseNumber(v)
            else -> null
        } ?: return args
        return JSONObject().put("value", n)
    }

    private fun select(args: JSONObject): JSONObject {
        val option = args.optString("option").trim()
        return if (option.isNotEmpty()) JSONObject().put("option", option) else args
    }

    private fun siren(args: JSONObject, service: String): JSONObject {
        if (service != "turn_on") return args
        val out = JSONObject()
        val pct = HaSpokenLocale.slotInt(args, "volume_pct", 0..100)
        val level = args.optDouble("volume_level", Double.NaN)
        when {
            pct != null -> out.put("volume_level", pct / 100.0)
            !level.isNaN() -> out.put("volume_level", if (level > 1.0) level / 100.0 else level)
        }
        val tone = args.optString("tone").trim()
        if (tone.isNotEmpty()) out.put("tone", tone)
        HaSpokenLocale.slotDouble(args, "duration", 0.0..3600.0)?.let { out.put("duration", it) }
        return if (out.length() > 0) out else args
    }

    private fun remote(args: JSONObject): JSONObject {
        val out = JSONObject()
        val command = args.optString("command").trim()
        if (command.isNotEmpty()) out.put("command", command)
        val device = args.optString("device").trim()
        if (device.isNotEmpty()) out.put("device", device)
        HaSpokenLocale.slotInt(args, "num_repeats", 1..20)?.let { out.put("num_repeats", it) }
        return if (out.length() > 0) out else args
    }

    private fun markUnmute(domain: String, requested: String, args: JSONObject) {
        if (domain != "media_player") return
        val text = requested.trim().lowercase()
        if (text != "unmute" && text != "取消静音" && text != "解除静音") return
        if (!args.has("is_volume_muted")) args.put("is_volume_muted", false)
    }

    private fun liftBooleanColors(data: JSONObject) {
        val keys = data.keys()
        val found = ArrayList<String>()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.lowercase() in COLOR_WORDS && data.opt(key) == true) found += key
        }
        if (found.isEmpty()) return
        if (data.optString("color_name").isBlank()) data.put("color_name", found.first())
        for (key in found) data.remove(key)
    }

    private fun copyRgb(args: JSONObject, out: JSONObject) {
        val arr = args.optJSONArray("rgb_color")
        if (arr != null && arr.length() == 3) {
            val parts = (0 until 3).map { arr.optInt(it, -1) }
            if (parts.all { it in 0..255 }) {
                out.put("rgb_color", JSONArray(parts))
                return
            }
        }
        val spoken = args.optString("rgb_color").trim()
        if (spoken.isEmpty()) return
        val parts = spoken.split(',', ' ').mapNotNull { it.toIntOrNull() }
        if (parts.size == 3) out.put("rgb_color", JSONArray(parts))
    }

    private fun copyHs(args: JSONObject, out: JSONObject) {
        val arr = args.optJSONArray("hs_color")
        if (arr != null && arr.length() == 2) {
            out.put("hs_color", arr)
            return
        }
        val spoken = args.optString("hs_color").trim()
        if (spoken.isEmpty()) return
        val parts = spoken.split(',', ' ').mapNotNull { it.toDoubleOrNull() }
        if (parts.size == 2) out.put("hs_color", JSONArray(parts))
    }

    private fun putMapped(args: JSONObject, out: JSONObject, key: String, map: (String) -> String) {
        val raw = args.optString(key).trim()
        if (raw.isNotEmpty()) out.put(key, map(raw))
    }

    private fun hasText(args: JSONObject, key: String): Boolean =
        args.optString(key).trim().isNotEmpty()

    private fun hasAny(args: JSONObject, vararg keys: String): Boolean =
        keys.any { args.has(it) && !args.isNull(it) }

    private fun copyInto(src: JSONObject, dest: JSONObject) {
        val keys = src.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in RESERVED) continue
            dest.put(key, src.opt(key))
        }
    }

    /** Same rule as `homeassistant.core.valid_entity_id`. */
    private val HA_ENTITY_ID = Regex("^(?!.+__)(?!_)[\\da-z_]+(?<!_)\\.(?!_)[\\da-z_]+(?<!_)$")
}
