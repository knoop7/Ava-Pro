package com.example.ava.localllm

import org.json.JSONObject

/**
 * Claw `domain_registry` spoken-locale layer: Chinese numbers, colors, HVAC /
 * fan modes, and argument-key aliases. The model may emit "百分之二十" or
 * "制冷"; the host turns that into HA slots before the service call.
 */
object HaSpokenLocale {

    /**
     * Claw-style spoken ordinals: "灯光一" and "灯光1" are the same key.
     * Replaces each CJK number run with its Arabic value; other characters stay.
     */
    fun foldDigits(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '十' || ch == '百' || ch in DIGIT || ch == '两' || ch == '俩') {
                val end = (i + 3).coerceAtMost(raw.length)
                var taken = 0
                var value: Int? = null
                for (len in (end - i) downTo 1) {
                    val n = parseChineseNumber(raw.substring(i, i + len)) ?: continue
                    if (n != n.toInt().toDouble()) continue
                    taken = len
                    value = n.toInt()
                    break
                }
                if (value != null) {
                    sb.append(value)
                    i += taken
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    fun parseNumber(raw: String): Double? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { return it }
        return parseChineseNumber(s)
    }

    /** First Arabic or Chinese number in [spoken], 0–100 for percents. */
    fun firstNumber(spoken: String): Double? {
        ARABIC.find(spoken)?.value?.toDoubleOrNull()?.let { return it }
        return findChineseNumber(spoken)
    }

    fun firstPercent(spoken: String): Int? {
        PERCENT_ARABIC.find(spoken)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it.coerceIn(0, 100) }
        val idx = spoken.indexOf("百分之")
        if (idx >= 0) {
            parseNumber(spoken.substring(idx + 3).take(4))?.let {
                return it.toInt().coerceIn(0, 100)
            }
        }
        CHENG.find(spoken)?.let { m ->
            parseChineseDigit(m.groupValues[1])?.let { return (it * 10).coerceIn(0, 100) }
        }
        if (spoken.contains("一半") || spoken.contains("半数")) return 50
        return firstNumber(spoken)?.toInt()?.takeIf { it in 0..100 }
    }

    fun climateMode(spoken: String): String? {
        val token = DeviceIndex.normalize(spoken)
        HVAC_EXACT[token]?.let { return it }
        HVAC_EXACT[spoken.trim().lowercase()]?.let { return it }
        for ((cue, mode) in HVAC) {
            if (token.contains(cue)) return mode
        }
        return null
    }

    fun fanLevel(spoken: String): String? {
        val n = DeviceIndex.normalize(spoken)
        for ((cue, mode) in FAN) {
            if (n.contains(cue)) return mode
        }
        return null
    }

    /**
     * Light color from spoken text. Kelvin for white temperatures, otherwise
     * HA `color_name` (English). Null when nothing color-like is present.
     */
    fun lightColor(spoken: String): LightColor? {
        val n = DeviceIndex.normalize(spoken)
        for ((cue, en) in COLOR) {
            if (!n.contains(cue)) continue
            KELVIN[en]?.let { return LightColor.Kelvin(it) }
            RGB[en]?.let { return LightColor.Rgb(it) }
            return LightColor.Name(en)
        }
        return null
    }

    /** Claw `_KEY_ALIASES`: "亮度" → brightness_pct, "模式" → hvac_mode, … */
    fun canonicalizeArgs(raw: JSONObject, domain: String? = null): JSONObject {
        val aliases = KEY_ALIASES[domain].orEmpty()
        val out = JSONObject()
        val keys = raw.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val canon = aliases[k] ?: GLOBAL_KEYS[k] ?: k
            if (!out.has(canon)) out.put(canon, raw.get(k))
        }
        return out
    }

    fun slotInt(obj: JSONObject, key: String, range: IntRange): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val n = when (val v = obj.opt(key)) {
            is Number -> v.toDouble()
            is String -> spokenAmount(v)
            else -> null
        } ?: return null
        return n.toInt().takeIf { it in range }
    }

    fun slotDouble(obj: JSONObject, key: String, range: ClosedFloatingPointRange<Double>): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val n = when (val v = obj.opt(key)) {
            is Number -> v.toDouble()
            is String -> spokenAmount(v) ?: parseNumber(v) ?: firstNumber(v)
            else -> null
        } ?: return null
        return n.takeIf { it in range }
    }

    fun fanPercent(level: String?): Int? = when (level) {
        "silent" -> 20
        "low" -> 33
        "medium" -> 66
        "high", "turbo" -> 100
        else -> null
    }

    fun spokenBool(raw: Any?): Boolean? = when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> {
            val t = raw.trim().lowercase()
            when {
                t in TRUE_WORDS -> true
                t in FALSE_WORDS -> false
                else -> null
            }
        }
        else -> null
    }

    /** Claw vacuum `fan_speed` spoken names. Unknown text stays as-is. */
    fun vacuumSpeed(spoken: String): String? {
        val n = DeviceIndex.normalize(spoken)
        for ((cue, speed) in VACUUM_SPEED) {
            if (n.contains(cue) || n == speed) return speed
        }
        return spoken.trim().ifEmpty { null }
    }

    sealed class LightColor {
        data class Name(val colorName: String) : LightColor()
        data class Kelvin(val kelvin: Int) : LightColor()
        data class Rgb(val rgb: List<Int>) : LightColor()

        fun asServiceData(): Map<String, Any?> = when (this) {
            is Name -> mapOf("color_name" to colorName)
            is Kelvin -> mapOf("color_temp_kelvin" to kelvin)
            is Rgb -> mapOf("rgb_color" to rgb)
        }
    }

    private fun parseChineseNumber(s: String): Double? {
        if (s == "两" || s == "俩") return 2.0
        if (s == "半") return 0.5
        if (s == "十") return 10.0
        if (s == "百" || s == "一百") return 100.0
        var n = 0
        var unit = 0
        var saw = false
        for (ch in s) {
            when (ch) {
                '零' -> continue
                '十' -> {
                    if (unit == 0) unit = 1
                    n += unit * 10
                    unit = 0
                    saw = true
                }
                '百' -> {
                    if (unit == 0) unit = 1
                    n += unit * 100
                    unit = 0
                    saw = true
                }
                else -> {
                    val d = DIGIT[ch] ?: return if (saw && n > 0) n.toDouble() else null
                    unit = d
                    saw = true
                }
            }
        }
        if (!saw) return null
        return (n + unit).toDouble()
    }

    private fun parseChineseDigit(token: String): Int? {
        if (token.length != 1) return parseChineseNumber(token)?.toInt()
        return DIGIT[token[0]]
    }

    private fun findChineseNumber(spoken: String): Double? {
        // Longest-first scan of 1–3 CJK numeral chars.
        var i = 0
        while (i < spoken.length) {
            val ch = spoken[i]
            if (ch !in DIGIT && ch != '十' && ch != '百' && ch != '两' && ch != '俩') {
                i++
                continue
            }
            val end = (i + 3).coerceAtMost(spoken.length)
            for (len in (end - i) downTo 1) {
                parseChineseNumber(spoken.substring(i, i + len))?.let { return it }
            }
            i++
        }
        return null
    }

    private val DIGIT = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '二' to 2, '两' to 2, '俩' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    private val ARABIC = Regex("\\d{1,3}")
    private val PERCENT_ARABIC = Regex("(\\d{1,3})\\s*%")
    private val CHENG = Regex("([一二三四五六七八九两])成")

    // Longest cue first. Exact-token map also covers Needle emitting "关闭"/"制冷".
    private val HVAC_EXACT = mapOf(
        "off" to "off", "关闭" to "off", "关掉" to "off",
        "cool" to "cool", "制冷" to "cool", "冷气" to "cool", "降温" to "cool",
        "heat" to "heat", "制热" to "heat", "暖气" to "heat", "加热" to "heat", "升温" to "heat",
        "dry" to "dry", "除湿" to "dry", "抽湿" to "dry", "干燥" to "dry",
        "fan_only" to "fan_only", "送风" to "fan_only", "通风" to "fan_only",
        "auto" to "auto", "自动" to "auto", "智能" to "auto",
        "heat_cool" to "heat_cool",
        "eco" to "eco", "节能" to "eco", "省电" to "eco",
    )

    private val HVAC = listOf(
        "heat_cool" to "heat_cool",
        "fan_only" to "fan_only",
        "制冷" to "cool", "冷气" to "cool", "降温" to "cool",
        "制热" to "heat", "暖气" to "heat", "加热" to "heat", "升温" to "heat",
        "除湿" to "dry", "抽湿" to "dry", "干燥" to "dry",
        "送风" to "fan_only", "通风" to "fan_only",
        "自动" to "auto", "智能" to "auto",
        "cool" to "cool", "heat" to "heat", "auto" to "auto", "dry" to "dry",
    ).sortedByDescending { it.first.length }

    private val FAN = listOf(
        "微风" to "low", "低速" to "low", "静音" to "silent", "安静" to "silent",
        "中速" to "medium", "中等" to "medium",
        "高速" to "high", "强风" to "high", "强力" to "turbo",
        "自动" to "auto",
        "low" to "low", "medium" to "medium", "high" to "high",
        "低" to "low", "弱" to "low", "小" to "low",
        "中" to "medium",
        "高" to "high", "强" to "high", "大" to "high",
    ).sortedByDescending { it.first.length }

    private val COLOR = listOf(
        "暖白" to "warm_white", "冷白" to "cold_white", "米白" to "linen",
        "白色" to "white", "红色" to "red", "绿色" to "green", "蓝色" to "blue",
        "黄色" to "yellow", "紫色" to "purple", "粉色" to "pink", "橙色" to "orange",
        "青色" to "cyan", "金色" to "gold", "银色" to "silver",
        "品红" to "magenta", "洋红" to "magenta",
        "棕色" to "brown", "灰色" to "gray", "米色" to "wheat",
        "天蓝" to "skyblue", "深蓝" to "navy", "浅蓝" to "lightblue",
        "深红" to "darkred", "玫红" to "hotpink", "酒红" to "maroon",
        "深绿" to "darkgreen", "浅绿" to "lightgreen", "草绿" to "lawngreen",
        "珊瑚" to "coral", "象牙" to "ivory", "薰衣草" to "lavender",
        "indigo" to "indigo", "靛蓝" to "indigo", "靛色" to "indigo",
        "white" to "white", "red" to "red", "green" to "green", "blue" to "blue",
        "yellow" to "yellow", "purple" to "purple", "pink" to "pink",
        "白" to "white", "红" to "red", "绿" to "green", "蓝" to "blue",
        "黄" to "yellow", "紫" to "purple", "粉" to "pink", "橙" to "orange",
        "青" to "cyan", "金" to "gold", "棕" to "brown", "灰" to "gray",
    ).sortedByDescending { it.first.length }

    private val KELVIN = mapOf(
        "white" to 4000, "warm_white" to 3000, "cold_white" to 6000,
        "daylight" to 5500, "warm" to 2700, "cool" to 6500,
        "natural" to 4500, "neutral" to 4000,
        "candlelight" to 2200, "candle" to 2200,
    )
    private val RGB = mapOf(
        "gold" to listOf(255, 215, 0),
        "silver" to listOf(192, 192, 192),
        "brown" to listOf(139, 69, 19),
        "gray" to listOf(128, 128, 128),
        "wheat" to listOf(245, 222, 179),
        "linen" to listOf(250, 240, 230),
        "skyblue" to listOf(135, 206, 235),
        "navy" to listOf(0, 0, 128),
        "lightblue" to listOf(173, 216, 230),
        "darkred" to listOf(139, 0, 0),
        "hotpink" to listOf(255, 105, 180),
        "darkgreen" to listOf(0, 100, 0),
        "lightgreen" to listOf(144, 238, 144),
        "lawngreen" to listOf(124, 252, 0),
        "coral" to listOf(255, 127, 80),
        "ivory" to listOf(255, 255, 240),
        "lavender" to listOf(230, 230, 250),
        "maroon" to listOf(128, 0, 0),
        "magenta" to listOf(255, 0, 255),
        "indigo" to listOf(75, 0, 130),
    )

    private fun spokenAmount(v: String): Double? {
        if (v.contains("百分") || v.contains("一半") || v.contains("半数") ||
            v.contains('%') || v.contains('％') || v.contains('成')
        ) {
            return firstPercent(v)?.toDouble() ?: parseNumber(v)
        }
        return parseNumber(v) ?: firstPercent(v)?.toDouble()
    }

    private val TRUE_WORDS = setOf("true", "1", "on", "yes", "是", "开", "开着", "打开")
    private val FALSE_WORDS = setOf("false", "0", "off", "no", "否", "关", "关着", "关闭")

    private val VACUUM_SPEED = listOf(
        "silent" to "silent", "quiet" to "quiet", "standard" to "standard",
        "strong" to "strong", "turbo" to "turbo", "max" to "max",
        "静音" to "silent", "安静" to "quiet", "标准" to "standard",
        "强力" to "strong", "最大" to "max",
    ).sortedByDescending { it.first.length }

    private val GLOBAL_KEYS = mapOf(
        "颜色" to "color_name", "color" to "color_name", "colour" to "color_name",
        "亮度" to "brightness_pct", "明暗" to "brightness_pct",
        "色温" to "color_temp_kelvin",
        "温度" to "temperature", "目标温度" to "temperature",
        "音量" to "volume_pct", "volume" to "volume_pct",
        "位置" to "position",
        "湿度" to "humidity", "目标湿度" to "humidity",
        "密码" to "code", "pin" to "code",
        "选项" to "option", "数值" to "value",
    )

    private val KEY_ALIASES = mapOf(
        "light" to mapOf(
            "颜色" to "color_name", "color" to "color_name", "colour" to "color_name",
            "亮度" to "brightness_pct", "明暗" to "brightness_pct",
            "色温" to "color_temp_kelvin", "temp" to "color_temp_kelvin",
            "kelvin" to "color_temp_kelvin", "rgb" to "rgb_color",
            "过渡" to "transition", "效果" to "effect",
            "hs" to "hs_color",
        ),
        "climate" to mapOf(
            "温度" to "temperature", "目标温度" to "temperature",
            "模式" to "hvac_mode", "风速" to "fan_mode",
            "temp" to "temperature", "mode" to "hvac_mode",
            "湿度" to "humidity", "target_humidity" to "humidity",
            "预设" to "preset_mode", "preset" to "preset_mode",
            "摆风" to "swing_mode", "swing" to "swing_mode",
        ),
        "fan" to mapOf(
            "速度" to "percentage", "风速" to "percentage",
            "speed" to "percentage", "percent" to "percentage",
            "brightness_pct" to "percentage",
            "preset" to "preset_mode", "模式" to "preset_mode",
            "方向" to "direction", "摇头" to "oscillating", "oscillate" to "oscillating",
        ),
        "cover" to mapOf(
            "位置" to "position", "pos" to "position",
            "倾斜" to "tilt_position", "tilt" to "tilt_position",
        ),
        "valve" to mapOf("位置" to "position", "pos" to "position"),
        "media_player" to mapOf(
            "音量" to "volume_pct", "volume" to "volume_pct",
            "源" to "source", "source" to "source", "输入源" to "source",
        ),
        "humidifier" to mapOf(
            "湿度" to "humidity", "目标湿度" to "humidity",
            "模式" to "mode", "mode" to "mode",
        ),
        "water_heater" to mapOf(
            "温度" to "temperature", "temp" to "temperature",
            "模式" to "operation_mode", "mode" to "operation_mode",
        ),
        "vacuum" to mapOf(
            "吸力" to "fan_speed", "档位" to "fan_speed", "speed" to "fan_speed",
        ),
        "lock" to mapOf("密码" to "code", "pin" to "code", "password" to "code"),
        "alarm_control_panel" to mapOf(
            "密码" to "code", "pin" to "code", "password" to "code",
        ),
        "siren" to mapOf(
            "音量" to "volume_pct", "volume" to "volume_pct",
            "音调" to "tone", "声音" to "tone",
            "时长" to "duration",
        ),
        "number" to mapOf("数值" to "value", "值" to "value"),
        "input_number" to mapOf("数值" to "value", "值" to "value"),
        "select" to mapOf("选项" to "option"),
        "input_select" to mapOf("选项" to "option"),
        "remote" to mapOf(
            "命令" to "command", "cmd" to "command",
            "次数" to "num_repeats",
        ),
    )
}
