package com.example.ava.localllm.remote

/**
 * Speech-side strip for AI TTS. Markdown markers never reach the engine.
 * Spoken pauses keep only `,` `，` `、`. Brackets, quotes, and sentence stops
 * (`.。!?！？;；` and kin) are dropped. Decimal points and clock colons stay.
 *
 * Display uses this same string. A BOM, zero-width mark, chat-template token,
 * or `[spk]` tag at the front would show as a garbled identifier on the caption.
 */
object TtsMdFilter {

    fun apply(raw: String): String {
        if (raw.isBlank()) return ""
        var s = stripInvisible(raw.replace("\r\n", "\n").replace('\r', '\n'))
        // Fences first, while the tag is still on the body. Peeling
        // `<|think|>` as a caption token would leave the analysis as speech.
        s = RemoteAiThinkFilter.spoken(s)
        if (s.isBlank()) return ""
        s = stripChatTokens(s)
        s = stripMarkdown(s)
        s = RemoteAiThinkFilter.spoken(s)
        if (s.isBlank()) return ""
        s = stripLeadingIdTag(s)
        s = stripWrappersAndStops(s)
        s = stripInvisible(s)
        s = stripLeadingMarks(s)
        s = collapseSpace(s)
        return s.trim().trim(',', '，', '、')
    }

    private fun stripInvisible(src: String): String {
        val out = StringBuilder(src.length)
        for (c in src) {
            if (c == '\n' || c == '\t') {
                out.append(c)
                continue
            }
            if (c == '\uFFFD' || c == '\uFFFC' || c == '\uFEFF') continue
            when (Character.getType(c).toByte()) {
                Character.FORMAT,
                Character.CONTROL,
                Character.PRIVATE_USE,
                Character.UNASSIGNED,
                -> continue
            }
            out.append(c)
        }
        return out.toString()
    }

    private fun stripChatTokens(src: String): String {
        var s = CHAT_HEADER.replace(src, "")
        s = CHAT_TOKEN.replace(s, "")
        s = LOOSE_CHAT_TOKEN.replace(s, "")
        s = LEADING_ROLE.replaceFirst(s.trimStart(), "")
        return s
    }

    private fun stripLeadingIdTag(src: String): String =
        LEADING_ID_TAG.replaceFirst(src.trimStart(), "")

    private fun stripLeadingMarks(src: String): String =
        LEADING_MARKS.replaceFirst(src.trimStart(), "")

    private fun stripMarkdown(src: String): String {
        var s = src
        s = FENCE.replace(s) { m -> m.groupValues[1].trim() }
        s = IMAGE.replace(s) { it.groupValues[1] }
        s = LINK.replace(s) { it.groupValues[1] }
        s = AUTOLINK.replace(s) { it.groupValues[1] }
        s = HTML_TAG.replace(s, "")
        s = BOLD.replace(s) { it.groupValues[2] }
        s = STRIKE.replace(s) { it.groupValues[1] }
        s = CODE.replace(s) { it.groupValues[1] }
        s = ITALIC_STAR.replace(s) { it.groupValues[1] }
        s = HEADING.replace(s, "")
        s = QUOTE.replace(s, "")
        s = LIST.replace(s, "")
        s = HR.replace(s, "")
        return s.replace("*", "").replace("`", "").replace("~", "")
    }

    private fun stripWrappersAndStops(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == ',' || c == '，' || c == '、' -> out.append(c)
                c == '.' -> {
                    val prev = out.lastOrNull()
                    val next = src.getOrNull(i + 1)
                    if (prev != null && prev.isDigit() && next != null && next.isDigit()) {
                        out.append(c)
                    }
                }
                c == ':' -> {
                    val prev = out.lastOrNull()
                    val next = src.getOrNull(i + 1)
                    if (prev != null && prev.isDigit() && next != null && next.isDigit()) {
                        out.append(c)
                    }
                }
                c == '\'' || c == '’' -> {
                    val prev = out.lastOrNull()
                    val next = src.getOrNull(i + 1)
                    if (prev != null && prev.isLetter() && next != null && next.isLetter()) {
                        out.append(c)
                    }
                }
                c == '\n' || c == '\t' -> out.append(' ')
                c in DROP -> { }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun collapseSpace(src: String): String {
        var s = src.replace(Regex("[ \\t\\x0B\\f]+"), " ")
        s = s.replace(Regex("\\s+,\\s*"), ", ")
        s = s.replace(Regex("\\s+，\\s*"), "，")
        s = s.replace(Regex("\\s+、\\s*"), "、")
        return s
    }

    private val FENCE = Regex("```(?:[a-zA-Z0-9_+-]*)\\n?([\\s\\S]*?)```")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val AUTOLINK = Regex("<(https?://[^>]+)>")
    private val HTML_TAG = Regex(
        """</?(?!(?:${RemoteAiThinkFilter.XML_NAMES})\b)[A-Za-z][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BOLD = Regex("(\\*\\*|__)(.+?)\\1")
    private val STRIKE = Regex("~~(.+?)~~")
    private val CODE = Regex("`([^`]+)`")
    private val ITALIC_STAR = Regex("\\*([^*\\n]+)\\*")
    private val HEADING = Regex("(?m)^#{1,6}\\s*")
    private val QUOTE = Regex("(?m)^>\\s?")
    private val LIST = Regex("(?m)^(?:[-*+]\\s+|\\d+\\.\\s+)")
    private val HR = Regex("(?m)^(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val CHAT_HEADER = Regex(
        """<\|\s*im_start\s*\|>\s*(?:assistant|user|system)?\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val CHAT_TOKEN = Regex(
        """<\|\s*/?(?!(?:${RemoteAiThinkFilter.TOKEN_NAMES})\b)[A-Za-z0-9_.-]+\s*\|>|</?s>|\[/?INST]|<<SYS>>|<</SYS>>""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_ROLE = Regex(
        """^(?:assistant|user|system)(?:\s*[:：]\s*|\s*\n\s*)""",
        RegexOption.IGNORE_CASE,
    )
    private val LOOSE_CHAT_TOKEN = Regex(
        """\|(?:im_start|im_end|assistant|user|system|endoftext|eot_id)\|""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_ID_TAG = Regex(
        """^(?:\[[^\]\n]{0,24}]|【[^】\n]{0,24}】)\s*""",
    )
    private val LEADING_MARKS = Regex("""^[#＃|>｜]+""")

    private val DROP = charArrayOf(
        '。', '！', '!', '？', '?', ';', '；', '：',
        '…', '⋯', '·', '•', '—', '–', '―',
        '(', ')', '（', '）', '[', ']', '【', '】', '［', '］',
        '{', '}', '｛', '｝',
        '「', '」', '『', '』', '〈', '〉', '《', '》',
        '<', '>',
        '"', '“', '”', '„', '‟',
        '‘',
    ).toSet()
}
