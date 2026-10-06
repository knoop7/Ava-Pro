package com.example.ava.notifications

/**
 * 解析通知场景文本中的 Home Assistant 实体占位符并替换为实时数值。
 *
 * 占位符语法（在 title / desc / subDesc 内任意位置）：
 *   {{sensor.living_room_temp}}               -> 实体当前 state
 *   {{sensor.living_room_temp|unit}}          -> unit_of_measurement（简写）
 *   {{sensor.living_room_temp|attribute}}     -> 任意指定 attribute
 *   {{sensor.living_room_temp|state}}         -> 显式取 state（等价不带分隔符）
 *
 * 容错：
 *   - 占位符两侧的空格允许：{{ sensor.foo | unit }}
 *   - 未订阅 / 还未拿到值 -> 替换成 placeholderFallback（默认 "--"）
 *   - 不匹配 entity_id 命名（缺少域名点）的占位符原样保留，避免误伤
 */
object SceneTemplateResolver {

    private val PLACEHOLDER_REGEX = Regex("""\{\{\s*([^{}|]+?)(?:\s*\|\s*([^{}]+?))?\s*\}\}""")
    private val ENTITY_ID_REGEX = Regex("""^[a-z0-9_]+\.[a-z0-9_]+$""")

    /** 占位符引用：entity_id + 可选 attribute（空 = state，"unit" = unit_of_measurement 简写）。 */
    data class Ref(val entityId: String, val attribute: String) {
        /** 真实的 HA attribute 字段名，把简写 "unit" 翻成 "unit_of_measurement"，"state"/"" 翻成空串。 */
        val haAttribute: String
            get() = when (attribute) {
                "", "state" -> ""
                "unit" -> "unit_of_measurement"
                else -> attribute
            }
    }

    /** 实体值读取接口：state 用空字符串作为 attribute；任何其它字符串视为 attribute。 */
    fun interface ValueProvider {
        fun get(entityId: String, haAttribute: String): String?
    }

    /** 扫描一段文本，返回所有合法的实体引用（去重，保持出现顺序）。 */
    fun extractRefs(text: String): List<Ref> {
        if (text.isEmpty() || !text.contains("{{")) return emptyList()
        val seen = LinkedHashSet<Ref>()
        for (m in PLACEHOLDER_REGEX.findAll(text)) {
            val entityId = m.groupValues[1].trim().lowercase()
            if (!ENTITY_ID_REGEX.matches(entityId)) continue
            val attribute = m.groupValues[2].trim().lowercase()
            seen.add(Ref(entityId, attribute))
        }
        return seen.toList()
    }

    /** 一次扫描多段文本。 */
    fun extractRefs(vararg texts: String): List<Ref> {
        val seen = LinkedHashSet<Ref>()
        for (t in texts) seen.addAll(extractRefs(t))
        return seen.toList()
    }

    /** 文本是否含占位符（用于快速跳过 resolve）。 */
    fun hasPlaceholders(text: String): Boolean =
        text.contains("{{") && PLACEHOLDER_REGEX.containsMatchIn(text)

    /** 把文本中的占位符替换为实时值。未知值用 [placeholderFallback] 兜底（默认 "--"）。 */
    fun resolve(
        text: String,
        placeholderFallback: String = "--",
        provider: ValueProvider,
    ): String {
        if (!hasPlaceholders(text)) return text
        return PLACEHOLDER_REGEX.replace(text) { m ->
            val entityId = m.groupValues[1].trim().lowercase()
            if (!ENTITY_ID_REGEX.matches(entityId)) return@replace m.value
            val ref = Ref(entityId, m.groupValues[2].trim().lowercase())
            val v = provider.get(ref.entityId, ref.haAttribute)
            if (v.isNullOrEmpty()) placeholderFallback else v
        }
    }
}
