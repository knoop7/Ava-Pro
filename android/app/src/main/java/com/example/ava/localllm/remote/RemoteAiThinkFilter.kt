package com.example.ava.localllm.remote

/**
 * Split speakable text from a thinking model the way the vendors do.
 *
 * Qwen / NVIDIA / DeepSeek: the last close fence (`</think>` and kin) is the
 * cut. Everything before it is chain-of-thought — even when the opening tag
 * was injected in the prompt and never appears in the output. An open fence
 * with no close means the think block was truncated; drop the speech and keep
 * the body for the next tool round. Qwen3.5 may emit `<tool_call>` inside a
 * think block without closing first; that tag is an implicit end, matching
 * vLLM's qwen3 parser. Official APIs keep the same body in a sibling field
 * we never speak.
 *
 * TTS later strips `<>` `[]` `【】` `（）` and peels chat-template tokens.
 * Call this first, while the close fence is still on the body.
 */
internal object RemoteAiThinkFilter {

    data class Split(val spoken: String, val thought: String)

    /** Fence names. TTS must not peel these as caption / HTML junk. */
    internal const val XML_NAMES = "think|thinking|thought|reasoning|redacted_reasoning"
    internal const val TOKEN_NAMES = "$XML_NAMES|begin_of_thought|end_of_thought"

    fun spoken(raw: String): String = split(raw).spoken

    fun split(raw: String): Split {
        if (raw.isEmpty()) return Split("", "")
        val close = CLOSE.findAll(raw).lastOrNull()
        if (close != null) {
            return finish(raw.substring(close.range.last + 1), raw.substring(0, close.range.first))
        }
        val toolAt = raw.indexOf(TOOL_CALL, ignoreCase = true)
        if (toolAt >= 0) {
            val rest = raw.substring(toolAt)
            val endAt = rest.indexOf(TOOL_CALL_END, ignoreCase = true)
            val spoken = if (endAt >= 0) rest.substring(endAt + TOOL_CALL_END.length) else ""
            return finish(spoken, raw.substring(0, toolAt))
        }
        if (OPEN.any { it.containsMatchIn(raw) }) {
            return Split("", unwrap(raw))
        }
        return finish(raw, "")
    }

    private fun finish(spokenRaw: String, thoughtRaw: String): Split {
        var s = spokenRaw
        for (re in CLOSED) s = re.replace(s, "")
        for (re in OPEN) s = re.replace(s, "")
        return Split(s.trim(), unwrap(thoughtRaw))
    }

    private fun unwrap(thought: String): String = TAG.replace(thought, "").trim()

    private val XML = XML_NAMES
    private val NAME = "$XML_NAMES|思考"
    private val TOKEN = TOKEN_NAMES

    private const val TOOL_CALL = "<tool_call>"
    private const val TOOL_CALL_END = "</tool_call>"

    private val CLOSE = Regex(
        """</(?:$XML)\s*>|＜/(?:$NAME)＞|\[/(?:$NAME)]|【/(?:$NAME)】""" +
            """|<\|(?:/(?:$TOKEN)|end_of_thought)\|>|◁/(?:think|thinking|thought)▷""",
        RegexOption.IGNORE_CASE,
    )

    private val TAG = Regex(
        """</?(?:$XML)\b[^>]*>|＜/?(?:$NAME)＞|\[/?(?:$NAME)]|【/?(?:$NAME)】""" +
            """|<\|/?(?:$TOKEN)\|>|<\|end_of_thought\|>|◁/?(?:think|thinking|thought)▷""",
        RegexOption.IGNORE_CASE,
    )

    private val CLOSED = listOf(
        Regex(
            """<(?:$XML)\b[^>]*>[\s\S]*?</(?:$XML)>""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """＜(?:$NAME)＞[\s\S]*?＜/(?:$NAME)＞""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """\[(?:$NAME)][\s\S]*?\[/(?:$NAME)]""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """【(?:$NAME)】[\s\S]*?【/(?:$NAME)】""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """[（(](?:思考|think|thinking)[：:][^）)]*[）)]""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """<\|(?:$TOKEN)\|>[\s\S]*?<\|(?:/?(?:$TOKEN))\|?>""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """◁(?:think|thinking|thought)▷[\s\S]*?◁/(?:think|thinking|thought)▷""",
            RegexOption.IGNORE_CASE,
        ),
    )

    private val OPEN = listOf(
        Regex(
            """<(?:$XML)\b[^>]*>[\s\S]*$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """＜(?:$NAME)＞[\s\S]*$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """【(?:$NAME)】[\s\S]*$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """<\|(?:begin_of_thought|think|thinking|thought|reasoning|redacted_reasoning)\|>[\s\S]*$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """◁(?:think|thinking|thought)▷[\s\S]*$""",
            RegexOption.IGNORE_CASE,
        ),
    )
}
