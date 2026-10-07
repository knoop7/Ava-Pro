package com.example.ava.notifications

/**
 * Resolve Home Assistant entity placeholders in notification scene text
 * and replace them with live values.
 *
 * Placeholder syntax (anywhere in title / desc / subDesc):
 *   {{sensor.living_room_temp}}               -> the entity's current state
 *   {{sensor.living_room_temp|unit}}          -> unit_of_measurement (shorthand)
 *   {{sensor.living_room_temp|attribute}}     -> any named attribute
 *   {{sensor.living_room_temp|state}}         -> state explicitly (same as no separator)
 *
 * Tolerance:
 *   - Spaces around the placeholder are allowed: {{ sensor.foo | unit }}
 *   - Not subscribed, or no value yet -> placeholderFallback (default "--")
 *   - A placeholder that is not an entity_id (no domain dot) is left as-is
 */
object SceneTemplateResolver {

    private val PLACEHOLDER_REGEX = Regex("""\{\{\s*([^{}|]+?)(?:\s*\|\s*([^{}]+?))?\s*\}\}""")
    private val ENTITY_ID_REGEX = Regex("""^[a-z0-9_]+\.[a-z0-9_]+$""")

    /** Placeholder ref: entity_id plus an optional attribute (empty = state, "unit" = unit_of_measurement shorthand). */
    data class Ref(val entityId: String, val attribute: String) {
        /** Real HA attribute name: "unit" becomes "unit_of_measurement"; "state" and "" become empty. */
        val haAttribute: String
            get() = when (attribute) {
                "", "state" -> ""
                "unit" -> "unit_of_measurement"
                else -> attribute
            }
    }

    /** Entity value lookup: empty attribute means state; any other string is an attribute. */
    fun interface ValueProvider {
        fun get(entityId: String, haAttribute: String): String?
    }

    /** Scan one text and return every valid entity ref (deduped, in appearance order). */
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

    /** Scan several texts in one pass. */
    fun extractRefs(vararg texts: String): List<Ref> {
        val seen = LinkedHashSet<Ref>()
        for (t in texts) seen.addAll(extractRefs(t))
        return seen.toList()
    }

    /** Whether the text contains a placeholder (used to skip resolve). */
    fun hasPlaceholders(text: String): Boolean =
        text.contains("{{") && PLACEHOLDER_REGEX.containsMatchIn(text)

    /** Replace placeholders with live values. Unknown values use [placeholderFallback] (default "--"). */
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
