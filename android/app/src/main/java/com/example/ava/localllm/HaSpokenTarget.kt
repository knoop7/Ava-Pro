package com.example.ava.localllm

/**
 * Claw SmartDiscovery / `_resolve_entity_for_query` spoken hint.
 *
 * "turn on light 1" / "打开灯光一" → domain=light, name=1.
 * "bedroom light" → domain=light, name=bedroom.
 * The model may still pass the raw utterance; the host unwraps it.
 */
data class HaSpokenTarget(
    val raw: String,
    val name: String,
    val domain: String?,
) {
    companion object {
        fun parse(spoken: String, domainHint: String? = null): HaSpokenTarget {
            val unwrapped = unwrap(spoken)
            val norm = DeviceIndex.normalize(unwrapped)
            val inferred = HaDomainActions.hintDomain(norm)
            val domain = domainHint?.trim()?.ifEmpty { null } ?: inferred
            val stripped = HaDomainActions.stripDomainCues(norm)
            val scoped = if (domain != null) DeviceIndex.stripListScope(stripped) else stripped
            val name = when {
                scoped.isEmpty() -> ""
                DeviceIndex.isGenericHint(scoped) && domain != null -> ""
                else -> scoped
            }
            return HaSpokenTarget(spoken.trim(), name, domain)
        }

        fun unwrap(spoken: String): String {
            var t = spoken.trim()
            if (t.isEmpty()) return t
            t = LEAD_MENTION.replace(t, "")
            t = LEAD_POLITE.replace(t, "")
            t = LEAD_ACTION.replace(t, "")
            t = LEAD_ARTICLE.replace(t, "")
            t = PUT_ACTION.replace(t, "$1")
            t = TAIL_ACTION.replace(t, "")
            return t.trim()
        }

        // Wechaty mention: STT often writes Eva for Ava.
        private val LEAD_MENTION = Regex(
            "^(hey|hi|ok|okay|hello)\\s+(ava|eva)[\\s,]+|^(ava|eva)(?:[\\s,]+|(?=[\\u4e00-\\u9fff]))",
            RegexOption.IGNORE_CASE,
        )
        private val LEAD_POLITE = Regex(
            "^(请|帮我|麻烦|please|bitte|por favor|can you|could you|would you)\\s*",
            RegexOption.IGNORE_CASE,
        )
        private val LEAD_ACTION = Regex(
            "^(打开|开启|关闭|关掉|开一下|关一下|看看|看一下|看下|看一眼|瞧瞧|" +
                "升起|拉开|放下|拉上|合上|回充|" +
                "look at|show me|turn on|turn off|switch on|switch off|open|close|start|stop|enable|disable|shut)\\s*",
            RegexOption.IGNORE_CASE,
        )
        private val LEAD_ARTICLE = Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE)
        private val PUT_ACTION = Regex("^把(.+?)(打开|关闭|关掉)$")
        private val TAIL_ACTION = Regex(
            "(打开|关闭|关掉|吧|啊|呀|呢|please|thanks|thank you)$",
            RegexOption.IGNORE_CASE,
        )
    }
}
