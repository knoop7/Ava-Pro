package com.example.ava.localllm

/**
 * Claw `get_action_service` + `fuzzy_resolve_service`: the model may say
 * turn_on, play, or open; the host rewrites that to the domain's real service.
 * Copied in intent, not dumped into the prompt.
 */
object HaDomainActions {

    fun service(domain: String, on: Boolean): String =
        qualified(domain, if (on) "turn_on" else "turn_off")

    /**
     * `domain.service`. Unknown power domains stay on `homeassistant.turn_on/off`
     * so a new HA type still switches.
     */
    fun qualified(domain: String, requested: String): String {
        val action = resolve(domain, requested)
        if (action == "turn_on" || action == "turn_off") {
            val mapped = ACTION[domain]?.get(action)
            return if (mapped != null) "$domain.$mapped" else {
                if (action == "turn_on") "homeassistant.turn_on" else "homeassistant.turn_off"
            }
        }
        return "$domain.$action"
    }

    /** Claw `fuzzy_resolve_service`. Returns the service name only. */
    fun resolve(domain: String, requested: String): String {
        val text = requested.trim().lowercase().replace(' ', '_').replace('-', '_')
        if (text.isEmpty()) return rewrite(domain, "turn_on")
        ALIAS[domain]?.get(text)?.let { return rewrite(domain, it) }
        SPOKEN[domain]?.let { spoken ->
            matchSpoken(text, spoken)?.let { return rewrite(domain, it) }
        }
        matchSpoken(text, SPOKEN_ANY)?.let { return rewrite(domain, it) }
        return rewrite(domain, text)
    }

    fun rewrite(domain: String, action: String): String =
        ACTION[domain]?.get(action) ?: action

    fun hintDomain(spokenNorm: String): String? {
        for ((cue, domain) in DOMAIN_CUES) {
            if (spokenNorm.contains(cue)) return domain
        }
        return null
    }

    fun stripDomainCues(spokenNorm: String): String {
        var s = spokenNorm
        for ((cue, _) in DOMAIN_CUES) s = s.replace(cue, " ")
        return DeviceIndex.normalize(s)
    }

    private fun matchSpoken(text: String, aliases: List<Pair<String, String>>): String? {
        aliases.firstOrNull { it.first == text }?.let { return it.second }
        if (text.length <= 2) return null
        return aliases.firstOrNull { (cue, _) ->
            cue.length >= 2 && (text.contains(cue) || cue.contains(text))
        }?.second
    }

    // Claw action_services + default power. Alarm/camera stay unnamed so
    // ha_turn_on cannot arm or record.
    private val ACTION: Map<String, Map<String, String>> = mapOf(
        "cover" to mapOf("turn_on" to "open_cover", "turn_off" to "close_cover"),
        "valve" to mapOf("turn_on" to "open_valve", "turn_off" to "close_valve"),
        "lock" to mapOf("turn_on" to "unlock", "turn_off" to "lock"),
        "vacuum" to mapOf("turn_on" to "start", "turn_off" to "return_to_base"),
        "lawn_mower" to mapOf("turn_on" to "start_mowing", "turn_off" to "dock"),
        "media_player" to mapOf("turn_on" to "media_play", "turn_off" to "media_stop"),
        "scene" to mapOf("turn_on" to "turn_on"),
        "script" to mapOf("turn_on" to "turn_on", "turn_off" to "turn_off"),
        "automation" to mapOf("turn_on" to "turn_on", "turn_off" to "turn_off"),
        "button" to mapOf("turn_on" to "press"),
        "input_button" to mapOf("turn_on" to "press"),
        "light" to mapOf("turn_on" to "turn_on", "turn_off" to "turn_off"),
        "switch" to mapOf("turn_on" to "turn_on", "turn_off" to "turn_off"),
        "fan" to mapOf("turn_on" to "turn_on", "turn_off" to "turn_off"),
        "climate" to mapOf("turn_on" to "turn_on", "turn_off" to "turn_off"),
        "humidifier" to mapOf("turn_on" to "turn_on", "turn_off" to "turn_off"),
        "water_heater" to mapOf("turn_on" to "turn_on", "turn_off" to "turn_off"),
        "siren" to mapOf("turn_on" to "turn_on", "turn_off" to "turn_off"),
        "remote" to mapOf("turn_on" to "turn_on", "turn_off" to "turn_off"),
        "input_boolean" to mapOf("turn_on" to "turn_on", "turn_off" to "turn_off"),
        "timer" to mapOf("turn_on" to "start", "turn_off" to "cancel"),
    )

    private val ALIAS: Map<String, Map<String, String>> = mapOf(
        "media_player" to mapOf(
            "play" to "media_play",
            "pause" to "media_pause",
            "stop" to "media_stop",
            "next" to "media_next_track",
            "next_track" to "media_next_track",
            "previous" to "media_previous_track",
            "previous_track" to "media_previous_track",
            "prev" to "media_previous_track",
            "volume" to "volume_set",
            "set_volume" to "volume_set",
            "set_volume_level" to "volume_set",
            "mute" to "volume_mute",
            "unmute" to "volume_mute",
            "volume_up" to "volume_up",
            "volume_down" to "volume_down",
        ),
        "cover" to mapOf(
            "open" to "open_cover",
            "close" to "close_cover",
            "stop" to "stop_cover",
            "set_position" to "set_cover_position",
            "position" to "set_cover_position",
            "tilt" to "set_cover_tilt_position",
            "open_tilt" to "open_cover_tilt",
            "close_tilt" to "close_cover_tilt",
        ),
        "valve" to mapOf(
            "open" to "open_valve",
            "close" to "close_valve",
            "stop" to "stop_valve",
            "set_position" to "set_valve_position",
            "position" to "set_valve_position",
        ),
        "vacuum" to mapOf(
            "dock" to "return_to_base",
            "return" to "return_to_base",
            "return_home" to "return_to_base",
            "home" to "return_to_base",
            "clean" to "start",
            "speed" to "set_fan_speed",
            "set_speed" to "set_fan_speed",
        ),
        "lawn_mower" to mapOf(
            "start" to "start_mowing",
            "mow" to "start_mowing",
            "return" to "dock",
            "return_to_base" to "dock",
            "home" to "dock",
        ),
        "lock" to mapOf(
            "open" to "unlock",
            "close" to "lock",
        ),
        "climate" to mapOf(
            "set_temp" to "set_temperature",
            "temperature" to "set_temperature",
            "mode" to "set_hvac_mode",
            "set_mode" to "set_hvac_mode",
            "fan" to "set_fan_mode",
        ),
        "humidifier" to mapOf(
            "humidity" to "set_humidity",
            "set_mode" to "set_mode",
        ),
        "water_heater" to mapOf(
            "set_temp" to "set_temperature",
            "temperature" to "set_temperature",
            "mode" to "set_operation_mode",
            "set_mode" to "set_operation_mode",
        ),
        "fan" to mapOf(
            "speed" to "set_percentage",
            "set_speed" to "set_percentage",
            "percent" to "set_percentage",
            "oscillate" to "oscillate",
        ),
        "scene" to mapOf("activate" to "turn_on"),
        "automation" to mapOf("run" to "trigger", "activate" to "trigger"),
        "button" to mapOf("click" to "press", "push" to "press"),
        "input_button" to mapOf("click" to "press", "push" to "press"),
        "number" to mapOf("set" to "set_value", "value" to "set_value"),
        "input_number" to mapOf("set" to "set_value", "value" to "set_value"),
        "select" to mapOf("select" to "select_option", "option" to "select_option"),
        "input_select" to mapOf("select" to "select_option", "option" to "select_option"),
        "timer" to mapOf("stop" to "cancel"),
        "alarm_control_panel" to mapOf(
            "disarm" to "alarm_disarm",
            "arm_home" to "alarm_arm_home",
            "arm_away" to "alarm_arm_away",
            "arm_night" to "alarm_arm_night",
            "trigger" to "alarm_trigger",
        ),
        "camera" to mapOf(
            "photo" to "snapshot",
            "picture" to "snapshot",
            "video" to "record",
        ),
    )

    // Host-only spoken aliases. Not taught to the model.
    private val SPOKEN: Map<String, List<Pair<String, String>>> = mapOf(
        "vacuum" to listOf(
            "回充" to "return_to_base", "回家" to "return_to_base", "返回基座" to "return_to_base",
            "清扫" to "start", "定位" to "locate", "找" to "locate",
        ),
        "cover" to listOf(
            "升起" to "open_cover", "拉开" to "open_cover",
            "放下" to "close_cover", "拉上" to "close_cover", "合上" to "close_cover",
        ),
        "lock" to listOf("解锁" to "unlock", "开锁" to "unlock", "上锁" to "lock", "锁门" to "lock"),
        "media_player" to listOf(
            "下一首" to "media_next_track", "下一曲" to "media_next_track",
            "上一首" to "media_previous_track", "上一曲" to "media_previous_track",
            "静音" to "volume_mute",
        ),
        "alarm_control_panel" to listOf(
            "撤防" to "alarm_disarm", "在家布防" to "alarm_arm_home",
            "离家布防" to "alarm_arm_away", "夜间布防" to "alarm_arm_night",
        ),
        "lawn_mower" to listOf("割草" to "start_mowing", "回充" to "dock"),
    )

    private val SPOKEN_ANY = listOf(
        "打开" to "turn_on", "开启" to "turn_on", "关闭" to "turn_off", "关掉" to "turn_off",
        "播放" to "media_play", "暂停" to "media_pause",
        "按下" to "press", "触发" to "trigger",
    )

    // Longest cue first so "窗帘" wins over "窗", "热水器" wins over "水".
    private val DOMAIN_CUES: List<Pair<String, String>> = listOf(
        "media player" to "media_player",
        "media_player" to "media_player",
        "water heater" to "water_heater",
        "lawn mower" to "lawn_mower",
        "扫地机器人" to "vacuum",
        "摄像头" to "camera",
        "监控" to "camera",
        "吸尘器" to "vacuum",
        "扫地机" to "vacuum",
        "割草机" to "lawn_mower",
        "加湿器" to "humidifier",
        "除湿机" to "humidifier",
        "热水器" to "water_heater",
        "空调" to "climate",
        "温控" to "climate",
        "窗帘" to "cover",
        "卷帘" to "cover",
        "百叶" to "cover",
        "遮阳" to "cover",
        "车库门" to "cover",
        "车库" to "cover",
        "阀门" to "valve",
        "风扇" to "fan",
        "门锁" to "lock",
        "电视" to "media_player",
        "音箱" to "media_player",
        "音响" to "media_player",
        "场景" to "scene",
        "灯光" to "light",
        "灯具" to "light",
        "灯带" to "light",
        "插座" to "switch",
        "开关" to "switch",
        "humidifier" to "humidifier",
        "water_heater" to "water_heater",
        "lawn_mower" to "lawn_mower",
        "vacuum" to "vacuum",
        "camera" to "camera",
        "cam" to "camera",
        "climate" to "climate",
        "curtain" to "cover",
        "blind" to "cover",
        "garage" to "cover",
        "cover" to "cover",
        "valve" to "valve",
        "scene" to "scene",
        "lock" to "lock",
        "light" to "light",
        "switch" to "switch",
        "fan" to "fan",
        "tv" to "media_player",
        "灯" to "light",
        "锁" to "lock",
        "阀" to "valve",
    ).sortedByDescending { it.first.length }
}
