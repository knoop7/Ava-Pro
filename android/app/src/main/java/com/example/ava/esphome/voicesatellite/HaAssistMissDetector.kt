package com.example.ava.esphome.voicesatellite

/**
 * Official Assist *miss* replies (`no_valid_targets` family): the intent was recognised
 * but no usable area / floor / domain / entity was found, or the entity is not exposed.
 *
 * **Primary rear-guard trigger:** "Sorry, cannot find a device named {}" (and the same family
 * for area / floor). That sentence is why the local AI gets the user's original
 * transcript — not a reason to stop. TTS often drops the comma or "named"; [isPrimaryDeviceMiss]
 * still hands off. Bare "cannot find" in a long LLM essay does not.
 *
 * Templates: [HaAssistMissPhrases]. When the pipeline debug log is readable,
 * [com.example.ava.homeassistant.HaAssistVerdict] is a second path, not the first.
 */
object HaAssistMissDetector {

    /** Slot text is a spoken name; anything longer than this is not a stock reply. */
    private const val SLOT_MAX = 60
    private const val REPLY_MAX = 240
    /** Spoken miss replies stay short; essays must not trip the family matcher. */
    private const val FAMILY_MAX = 96
    /** Claw often appends "say the name or area"; still one short spoken line. */
    private const val NAMED_MISS_MAX = 140

    private val patterns: List<Regex> by lazy {
        HaAssistMissPhrases.TEMPLATES.map { template -> compile(normalize(template)) }
    }

    fun matches(speech: String): Boolean {
        val text = normalize(speech)
        if (text.isEmpty() || text.length > REPLY_MAX) return false
        return patterns.any { it.matches(text) }
    }

    /**
     * THE handoff: Assist said it could not find that device / area / floor.
     * Official zh-CN is `Sorry, cannot find a device named {}`. Spoken forms without the
     * comma or "named" are the same miss.
     */
    fun isPrimaryDeviceMiss(speech: String): Boolean {
        // First sentence only — an LLM tail ("which area?") must not hide the miss.
        val head = speech.trim().split(Regex("[。！？!?]"), limit = 2).firstOrNull().orEmpty()
        val compact = compact(head)
        if (compact.isEmpty() || compact.length > FAMILY_MAX) return false
        val apology = compact.startsWith("抱歉") ||
            compact.startsWith("对不起") ||
            compact.startsWith("唔好意思") ||
            compact.startsWith("sorry")
        if (!apology) return false
        val miss = compact.contains("找不到") ||
            compact.contains("没有找到") ||
            compact.contains("未能找到") ||
            compact.contains("couldntfind") ||
            compact.contains("couldnotfind") ||
            compact.contains("notawareof")
        val target = compact.contains("设备") ||
            compact.contains("区域") ||
            compact.contains("楼层") ||
            compact.contains("device") ||
            compact.contains("area") ||
            compact.contains("floor")
        return miss && target
    }

    /**
     * Claw / conversation-LLM miss: "I did not find a light fixture named Light 1…".
     * Not the hassil template (no "sorry", says "light fixture" not "device"). Still a failed
     * named-target lookup — the local seat should take the transcript.
     */
    fun isNamedTargetMiss(speech: String): Boolean {
        val head = speech.trim().split(Regex("[。！？!?]"), limit = 2).firstOrNull().orEmpty()
        val compact = compact(head)
        if (compact.isEmpty() || compact.length > NAMED_MISS_MAX) return false
        val miss = compact.contains("找不到") ||
            compact.contains("没有找到") ||
            compact.contains("未能找到") ||
            compact.contains("couldntfind") ||
            compact.contains("couldnotfind") ||
            compact.contains("didnotfind") ||
            compact.contains("didntfind")
        val named = compact.contains("名为") ||
            compact.contains("名叫") ||
            compact.contains("named") ||
            compact.contains("called")
        val kind = compact.contains("灯具") ||
            compact.contains("灯光") ||
            compact.contains("设备") ||
            compact.contains("开关") ||
            compact.contains("窗帘") ||
            compact.contains("风扇") ||
            compact.contains("light") ||
            compact.contains("lamp") ||
            compact.contains("device") ||
            compact.contains("switch") ||
            compact.contains("灯")
        return miss && named && kind
    }

    /** Stock device-miss first, then Claw named-target miss, then `no_intent`, then templates. */
    fun shouldHandoff(speech: String): Boolean =
        isPrimaryDeviceMiss(speech) ||
            isNamedTargetMiss(speech) ||
            HaNoIntentDetector.matches(speech) ||
            matches(speech)

    private fun compile(template: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            when {
                ch == '{' && template.startsWith("{}", i) -> {
                    sb.append("\\s*.{1,").append(SLOT_MAX).append("}?\\s*")
                    i += 2
                    continue
                }
                ch == ' ' -> sb.append("\\s*")
                else -> sb.append(Regex.escape(ch.toString()))
            }
            i++
        }
        // Stock replies may end with or without terminal punctuation depending on TTS.
        sb.append("[\\s.。!！]*$")
        return Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }

    private fun normalize(raw: String): String =
        raw.trim()
            .replace('\u00a0', ' ')
            // TTS / some agents drop the official commas; templates keep them.
            .replace(Regex("[，,、]"), "")
            .replace(Regex("[\\s\\u3000]+"), " ")
            .trim()

    /** Punctuation-free lowercase for the device-miss family. */
    private fun compact(raw: String): String =
        normalize(raw).lowercase().replace(Regex("[\\s.。!！?？:：]+"), "")
}
