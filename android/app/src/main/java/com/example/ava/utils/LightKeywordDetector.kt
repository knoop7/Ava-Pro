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

    fun endsWithQuestionMark(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val trimmed = text.trim()
        return trimmed.endsWith('?') || trimmed.endsWith('？')
    }
}
