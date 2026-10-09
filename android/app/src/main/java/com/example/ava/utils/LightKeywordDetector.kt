package com.example.ava.utils

object LightKeywordDetector {
    
    enum class DeviceType {
        LIGHT,
        SWITCH,
        BUTTON
    }
    
    data class DeviceAction(
        val type: DeviceType,
        val isOn: Boolean
    )
    
    private val LIGHT_ON_PATTERNS = listOf(
        Regex("开灯"),
        Regex("打开.*灯"),
        Regex("开启.*灯"),
        Regex("灯.*打开"),
        Regex("灯.*开了"),
        Regex("灯.*已打开"),
        Regex("灯.*已开启"),
        Regex("turn\\s+on.*light", RegexOption.IGNORE_CASE),
        Regex("light.*turn.*on", RegexOption.IGNORE_CASE),
        Regex("turned\\s+on.*light", RegexOption.IGNORE_CASE)
    )
    
    private val LIGHT_OFF_PATTERNS = listOf(
        Regex("关灯"),
        Regex("关闭.*灯"),
        Regex("关掉.*灯"),
        Regex("灯.*关闭"),
        Regex("灯.*关了"),
        Regex("灯.*已关闭"),
        Regex("灯.*已关掉"),
        Regex("turn\\s+off.*light", RegexOption.IGNORE_CASE),
        Regex("light.*turn.*off", RegexOption.IGNORE_CASE),
        Regex("turned\\s+off.*light", RegexOption.IGNORE_CASE)
    )
    
    private val SWITCH_ON_PATTERNS = listOf(
        Regex("打开.*开关"),
        Regex("开关.*打开"),
        Regex("开关.*开了"),
        Regex("开关.*已打开"),
        Regex("已.*打开.*开关"),
        Regex("已为.*打开.*开关"),
        Regex("turn\\s+on.*switch", RegexOption.IGNORE_CASE),
        Regex("switch.*turn.*on", RegexOption.IGNORE_CASE),
        Regex("turned\\s+on.*switch", RegexOption.IGNORE_CASE),
        Regex("switch.*is.*on", RegexOption.IGNORE_CASE)
    )
    
    private val SWITCH_OFF_PATTERNS = listOf(
        Regex("关闭.*开关"),
        Regex("关掉.*开关"),
        Regex("开关.*关闭"),
        Regex("开关.*关了"),
        Regex("开关.*已关闭"),
        Regex("已.*关闭.*开关"),
        Regex("已为.*关闭.*开关"),
        Regex("turn\\s+off.*switch", RegexOption.IGNORE_CASE),
        Regex("switch.*turn.*off", RegexOption.IGNORE_CASE),
        Regex("turned\\s+off.*switch", RegexOption.IGNORE_CASE),
        Regex("switch.*is.*off", RegexOption.IGNORE_CASE)
    )
    
    private val BUTTON_PATTERNS = listOf(
        Regex("按下.*按钮"),
        Regex("按钮.*按下"),
        Regex("已按下.*按钮"),
        Regex("触发.*按钮"),
        Regex("按钮.*触发"),
        Regex("press.*button", RegexOption.IGNORE_CASE),
        Regex("button.*press", RegexOption.IGNORE_CASE),
        Regex("trigger.*button", RegexOption.IGNORE_CASE)
    )
    
    fun detectDeviceAction(ttsText: String?): DeviceAction? {
        if (ttsText.isNullOrBlank()) return null
        
        val text = ttsText
        
        for (pattern in SWITCH_ON_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                return DeviceAction(DeviceType.SWITCH, isOn = true)
            }
        }
        
        for (pattern in SWITCH_OFF_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                return DeviceAction(DeviceType.SWITCH, isOn = false)
            }
        }
        
        for (pattern in LIGHT_ON_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                return DeviceAction(DeviceType.LIGHT, isOn = true)
            }
        }
        
        for (pattern in LIGHT_OFF_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                return DeviceAction(DeviceType.LIGHT, isOn = false)
            }
        }
        
        for (pattern in BUTTON_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                return DeviceAction(DeviceType.BUTTON, isOn = true)
            }
        }
        
        return null
    }
    
    private val EXIT_PATTERNS = listOf(
        Regex("再见"),
        Regex("拜拜"),
        Regex("告辞"),
        Regex("下次见"),
        Regex("回头见"),
        Regex("退下"),
        Regex("退出"),
        Regex("有什么.*随时.*问"),
        Regex("随时.*找我"),
        Regex("祝你.*愉快"),
        Regex("祝.*顺利"),
        Regex("goodbye", RegexOption.IGNORE_CASE),
        Regex("bye\\s*bye", RegexOption.IGNORE_CASE),
        Regex("see\\s*you", RegexOption.IGNORE_CASE),
        Regex("take\\s*care", RegexOption.IGNORE_CASE),
        Regex("farewell", RegexOption.IGNORE_CASE),
        Regex("so\\s+long", RegexOption.IGNORE_CASE),
        Regex("talk\\s+to\\s+you\\s+later", RegexOption.IGNORE_CASE),
        Regex("until\\s+next\\s+time", RegexOption.IGNORE_CASE),
        Regex("have\\s*a\\s*(good|nice|great)", RegexOption.IGNORE_CASE)
    )

    fun isExitKeyword(ttsText: String?): Boolean {
        if (ttsText.isNullOrBlank()) return false
        val text = ttsText.trim()
        return EXIT_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Farewells that end the session wherever they appear in what the user said.
     * Narrower than [EXIT_PATTERNS]: those also match reply phrasing such as
     * "随时找我", and 退出 is a command word ("退出音乐"), not a sign-off.
     */
    private val USER_FAREWELL_PATTERNS = listOf(
        Regex("再见"),
        Regex("拜拜"),
        Regex("下次见"),
        Regex("回头见"),
        Regex("退下"),
        Regex("goodbye", RegexOption.IGNORE_CASE),
        Regex("bye\\s*bye", RegexOption.IGNORE_CASE),
        Regex("\\bsee\\s*you\\b", RegexOption.IGNORE_CASE),
    )

    /**
     * Whole-utterance sign-offs, matched after dropping spaces and punctuation.
     * 好了 / 谢谢 / 没事了 alone end the session; 谢谢，再把灯调暗 does not. A bare
     * 好 / 好的 / 行 is a yes to a follow-up offer, so it only counts as a prefix.
     */
    private val USER_DONE_WHOLE = Regex(
        "^(嗯|哦|噢|那|好的|好|ok|okay)*" +
            "(好了|行了|可以了|够了|没事了|没事|没有了|没了|不用了|不用|就这样|没别的了|没有别的了|" +
            "谢谢你|谢谢|谢了|多谢|辛苦了|再见|拜拜|晚安|" +
            "thanks|thankyou|thatsall|thatisall|thatsit|nothanks|nothingelse|imdone|iamdone|goodnight|goodbye|byebye|bye)+" +
            "(啦|了|吧|呀|啊|哈|哦)*$",
    )

    /** True when the user's own utterance signs off: "that's all", 好了, 谢谢, 再见. */
    fun isUserDone(userText: String?): Boolean {
        if (userText.isNullOrBlank()) return false
        val text = userText.trim()
        if (USER_FAREWELL_PATTERNS.any { it.containsMatchIn(text) }) return true
        val compact = text.lowercase().replace(Regex("[\\s\\p{P}\\p{S}]"), "")
        return compact.isNotEmpty() && USER_DONE_WHOLE.matches(compact)
    }

    /** Sentence ends: CJK / Latin terminators and line breaks; a "." only before a space or the end, so 3.5 stays whole. */
    private val SENTENCE_END = Regex("[。！？!?；;\\n]+|\\.(?=\\s|$)")

    /** The last non-blank sentence of [text], trimmed; "" when there is none. */
    fun lastSentence(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.split(SENTENCE_END).lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    /**
     * True when the reply signs off at its end: [isExitKeyword] on the last sentence
     * only. "已退出音乐。还要调什么？" keeps going; "灯关好了。再见！" ends.
     */
    fun replyEndsWithGoodbye(replyText: String?): Boolean = isExitKeyword(lastSentence(replyText))

    fun endsWithQuestionMark(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val trimmed = text.trim()
        return trimmed.endsWith('?') || trimmed.endsWith('？')
    }
}
