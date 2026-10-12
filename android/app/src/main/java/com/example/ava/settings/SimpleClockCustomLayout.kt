package com.example.ava.settings

import kotlinx.serialization.Serializable

/**
 * One draggable element of the Simple Clock custom layout.
 *
 * [x]/[y] are the element's center as a 0..1 fraction of the clock view width/height.
 * A negative value means "not moved": the element stays at its built-in position.
 * [scale] multiplies the built-in automatic size. An element that is missing from the
 * list draws exactly like the built-in layout.
 * [color] is "#RRGGBB" (empty = built-in colors) and tints the whole element.
 * [text] is the date format pattern for the date, or the shown name for a status slot
 * (empty = built-in text). Time / weather are live values and logo is an image, so
 * they take no text; logo takes no color.
 */
@Serializable
data class SimpleClockLayoutElement(
    val id: String = "",
    val x: Float = -1f,
    val y: Float = -1f,
    val scale: Float = 1f,
    val visible: Boolean = true,
    val color: String = "",
    val text: String = "",
    /** Free text blocks only. */
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Free text blocks only: publish this block as a Home Assistant text entity (off by default). */
    val haDisplay: Boolean = false,
) {
    val hasPosition: Boolean
        get() = x >= 0f && y >= 0f

    /** Opaque ARGB for [color], or null when unset / invalid. */
    fun colorArgb(): Int? = SimpleClockCustomLayout.parseColor(color)
}

/** Stable JSON ids for [SimpleClockLayoutElement.id]. */
enum class SimpleClockLayoutElementId(val key: String) {
    TIME("time"),
    DATE("date"),
    WEATHER("weather"),
    // One element per Simple Clock status slot (settings slot 1..3), so each moves on its own.
    STATUS_1("status1"),
    STATUS_2("status2"),
    STATUS_3("status3"),
    LOGO("logo"),
    // User-added free text blocks (custom layout only; absent = not drawn).
    TEXT_1("text1"),
    TEXT_2("text2"),
    TEXT_3("text3"),
    TEXT_4("text4"),
    TEXT_5("text5"),
    TEXT_6("text6"),
    TEXT_7("text7"),
    TEXT_8("text8"),
    ;

    /** A free text block the user added (not part of the built-in clock face). */
    val isCustomText: Boolean
        get() = textNumber > 0

    /** 1-based number for free text blocks, 0 otherwise. */
    val textNumber: Int
        get() = when (this) {
            TEXT_1 -> 1
            TEXT_2 -> 2
            TEXT_3 -> 3
            TEXT_4 -> 4
            TEXT_5 -> 5
            TEXT_6 -> 6
            TEXT_7 -> 7
            TEXT_8 -> 8
            else -> 0
        }

    val isStatusSlot: Boolean
        get() = this == STATUS_1 || this == STATUS_2 || this == STATUS_3

    /** 1-based slot number for status elements, 0 otherwise. */
    val statusSlotNumber: Int
        get() = when (this) {
            STATUS_1 -> 1
            STATUS_2 -> 2
            STATUS_3 -> 3
            else -> 0
        }

    /** Date (format pattern), status slots (shown name) and free text blocks take text. */
    val supportsText: Boolean
        get() = this == DATE || isStatusSlot || isCustomText

    /** Everything drawn as text / icons can be tinted; the HA logo stays as-is. */
    val supportsColor: Boolean
        get() = this != LOGO

    companion object {
        fun fromKey(value: String?): SimpleClockLayoutElementId? =
            entries.find { it.key == value?.trim()?.lowercase() }

        val customTexts: List<SimpleClockLayoutElementId>
            get() = entries.filter { it.isCustomText }

        /** Element for the status slot at settings index [index] (0-based), or null. */
        fun statusSlot(index: Int): SimpleClockLayoutElementId? = when (index) {
            0 -> STATUS_1
            1 -> STATUS_2
            2 -> STATUS_3
            else -> null
        }
    }
}

object SimpleClockCustomLayout {
    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 3f
    const val MAX_TEXT_LENGTH = 48
    /** Free text blocks follow Home Assistant's text entity limit. */
    const val MAX_CUSTOM_TEXT_LENGTH = 255
    fun maxTextLength(id: SimpleClockLayoutElementId): Int =
        if (id.isCustomText) MAX_CUSTOM_TEXT_LENGTH else MAX_TEXT_LENGTH

    /** Default spot for a new free text block: centered, staggered down from the top. */
    fun defaultTextY(id: SimpleClockLayoutElementId): Float =
        (0.12f + 0.09f * (id.textNumber - 1).coerceAtLeast(0)).coerceAtMost(0.9f)

    /**
     * Applies [transform] to free text block [id] in one orientation's list. A missing
     * block is passed as null; returning null (or blank text) removes it.
     */
    /**
     * Free text blocks published to Home Assistant (one text entity each): present in either
     * orientation with their own "show in Home Assistant" switch on.
     */
    fun customTextIds(
        landscape: List<SimpleClockLayoutElement>,
        portrait: List<SimpleClockLayoutElement>,
    ): List<SimpleClockLayoutElementId> {
        val keys = (landscape + portrait).filter { it.haDisplay }.mapTo(HashSet()) { it.id }
        return SimpleClockLayoutElementId.customTexts.filter { it.key in keys }
    }

    fun updateCustomText(
        elements: List<SimpleClockLayoutElement>,
        id: SimpleClockLayoutElementId,
        transform: (SimpleClockLayoutElement?) -> SimpleClockLayoutElement?,
    ): List<SimpleClockLayoutElement> {
        val map = toMap(elements).toMutableMap()
        val next = transform(map[id])?.copy(id = id.key)
        if (next == null || isBuiltIn(next)) map.remove(id) else map[id] = next
        return fromMap(map)
    }

    /** "#RRGGBB" / "#AARRGGBB" -> opaque ARGB int; anything else -> null. */
    fun parseColor(value: String?): Int? {
        val hex = value?.trim()?.removePrefix("#") ?: return null
        if (hex.length != 6 && hex.length != 8) return null
        val rgb = hex.takeLast(6).toLongOrNull(16) ?: return null
        return (0xFF000000L or rgb).toInt()
    }

    /** Canonical "#RRGGBB" for an ARGB int (alpha dropped: tints are always opaque). */
    fun colorHex(argb: Int): String = String.format(java.util.Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    /**
     * Drops unknown ids and duplicates (first wins), clamps numbers, and removes
     * entries that are identical to the built-in layout so the list stays small.
     */
    fun sanitize(elements: List<SimpleClockLayoutElement>?): List<SimpleClockLayoutElement> {
        if (elements.isNullOrEmpty()) return emptyList()
        val seen = HashSet<SimpleClockLayoutElementId>()
        val out = ArrayList<SimpleClockLayoutElement>(elements.size)
        for (raw in elements) {
            val id = SimpleClockLayoutElementId.fromKey(raw.id) ?: continue
            if (!seen.add(id)) continue
            // Free text always has a spot (default: screen center).
            val moved = (raw.x.isFinite() && raw.y.isFinite() && raw.x >= 0f && raw.y >= 0f) || id.isCustomText
            val scale = if (raw.scale.isFinite()) {
                raw.scale.coerceIn(MIN_SCALE, MAX_SCALE)
            } else {
                1f
            }
            val clean = SimpleClockLayoutElement(
                id = id.key,
                x = if (moved) raw.x.takeIf { it.isFinite() && it >= 0f }?.coerceIn(0f, 1f) ?: 0.5f else -1f,
                y = if (moved) raw.y.takeIf { it.isFinite() && it >= 0f }?.coerceIn(0f, 1f) ?: 0.5f else -1f,
                scale = scale,
                visible = raw.visible,
                color = if (id.supportsColor) {
                    parseColor(raw.color)?.let { colorHex(it) }.orEmpty()
                } else {
                    ""
                },
                text = if (id.supportsText) raw.text.trim().take(maxTextLength(id)) else "",
                bold = id.isCustomText && raw.bold,
                italic = id.isCustomText && raw.italic,
                haDisplay = id.isCustomText && raw.haDisplay,
            )
            if (!isBuiltIn(clean)) out.add(clean)
        }
        return out
    }

    /** True when the entry adds nothing; a free text block without text is simply removed. */
    fun isBuiltIn(element: SimpleClockLayoutElement): Boolean {
        if (SimpleClockLayoutElementId.fromKey(element.id)?.isCustomText == true) {
            return element.text.isBlank()
        }
        return !element.hasPosition && element.scale == 1f && element.visible &&
            element.color.isEmpty() && element.text.isEmpty()
    }

    fun toMap(
        elements: List<SimpleClockLayoutElement>?,
    ): Map<SimpleClockLayoutElementId, SimpleClockLayoutElement> {
        val map = LinkedHashMap<SimpleClockLayoutElementId, SimpleClockLayoutElement>()
        for (element in sanitize(elements)) {
            val id = SimpleClockLayoutElementId.fromKey(element.id) ?: continue
            map[id] = element
        }
        return map
    }

    fun fromMap(
        map: Map<SimpleClockLayoutElementId, SimpleClockLayoutElement>,
    ): List<SimpleClockLayoutElement> = sanitize(
        SimpleClockLayoutElementId.entries.mapNotNull { id -> map[id]?.copy(id = id.key) }
    )
}
