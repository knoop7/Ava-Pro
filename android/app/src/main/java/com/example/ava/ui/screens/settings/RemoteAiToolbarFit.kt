package com.example.ava.ui.screens.settings

/**
 * One-row remote-AI toolbar: numbered slots on the left, history / SSE / Think
 * on the right. Phones have ~280dp inside the card; five slots plus the chips
 * overflow at the default 22dp. Slots shrink first; chip text shrinks next so
 * the labels are not crushed; padding scales only after the text floor.
 */
internal object RemoteAiToolbarFit {
    const val SLOT = 22f
    const val SLOT_MIN = 16f
    const val SLOT_GAP = 4f
    const val SLOT_POP = 3f
    const val SLOT_INSET = 5f
    const val CHIP_PAD = 9f
    const val CHIP_DOT = 7f
    const val CHIP_DOT_GAP = 5f
    const val CHIP_ROW_GAP = 6f
    const val GROUP_GAP = 8f
    const val CHIP_MIN_SCALE = 0.78f
    const val CHIP_TEXT_MIN = 0.72f

    data class Scales(val slot: Float, val text: Float, val pad: Float = 1f)

    fun chipWidth(text: Float, dotted: Boolean): Float =
        CHIP_PAD * 2f + text + if (dotted) CHIP_DOT + CHIP_DOT_GAP else 0f

    fun chipChrome(dotted: BooleanArray): Float {
        if (dotted.isEmpty()) return 0f
        var w = CHIP_ROW_GAP * (dotted.size - 1)
        for (dot in dotted) {
            w += CHIP_PAD * 2f
            if (dot) w += CHIP_DOT + CHIP_DOT_GAP
        }
        return w
    }

    fun chipsWidth(textWidths: FloatArray, dotted: BooleanArray): Float =
        chipChrome(dotted) + textWidths.sum()

    fun slotsWidth(items: Int, slot: Float = SLOT): Float {
        if (items <= 0) return 0f
        return items * slot + (items - 1) * SLOT_GAP + SLOT_POP
    }

    fun scales(available: Float, items: Int, textSum: Float, chipChrome: Float): Scales {
        if (available <= 0f || items <= 0) return Scales(1f, 1f)
        val usable = (available - SLOT_INSET).coerceAtLeast(0f)
        val chipsFull = chipChrome + textSum
        val maxSlots = slotsWidth(items, SLOT)
        val minSlots = slotsWidth(items, SLOT_MIN)
        val remain = usable - chipsFull - GROUP_GAP
        return when {
            remain >= maxSlots -> Scales(1f, 1f)
            remain >= minSlots -> {
                val inner = remain - SLOT_POP - (items - 1) * SLOT_GAP
                val slot = (inner / items).coerceIn(SLOT_MIN, SLOT)
                Scales(slot / SLOT, 1f)
            }
            else -> {
                val leftover = usable - minSlots - GROUP_GAP
                val textScale = if (textSum <= 0f) 1f
                else ((leftover - chipChrome) / textSum).coerceIn(CHIP_TEXT_MIN, 1f)
                val afterText = chipChrome + textSum * textScale
                if (afterText <= leftover || leftover <= 0f) {
                    Scales(SLOT_MIN / SLOT, textScale)
                } else {
                    val pad = (leftover / afterText).coerceIn(CHIP_MIN_SCALE, 1f)
                    Scales(SLOT_MIN / SLOT, textScale, pad)
                }
            }
        }
    }
}
