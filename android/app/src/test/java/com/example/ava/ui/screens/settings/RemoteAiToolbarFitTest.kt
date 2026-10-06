package com.example.ava.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAiToolbarFitTest {
    private val dotted = booleanArrayOf(false, true, true)

    @Test
    fun keepsFullSizeWhenTheRowIsWideEnough() {
        val texts = floatArrayOf(46f, 22f, 24f)
        val scales = RemoteAiToolbarFit.scales(
            400f,
            6,
            texts.sum(),
            RemoteAiToolbarFit.chipChrome(dotted),
        )
        assertEquals(1f, scales.slot, 0.001f)
        assertEquals(1f, scales.text, 0.001f)
        assertEquals(1f, scales.pad, 0.001f)
    }

    @Test
    fun shrinksSlotsBeforeChipTextOnAPhoneCard() {
        val textSum = 90f
        val chrome = 70f
        val items = 6
        val minSlots = RemoteAiToolbarFit.slotsWidth(items, RemoteAiToolbarFit.SLOT_MIN)
        val maxSlots = RemoteAiToolbarFit.slotsWidth(items, RemoteAiToolbarFit.SLOT)
        val remain = (minSlots + maxSlots) / 2f
        val available = remain + chrome + textSum + RemoteAiToolbarFit.GROUP_GAP + RemoteAiToolbarFit.SLOT_INSET
        val scales = RemoteAiToolbarFit.scales(available, items, textSum, chrome)
        assertTrue(scales.slot < 1f)
        assertTrue(scales.slot >= RemoteAiToolbarFit.SLOT_MIN / RemoteAiToolbarFit.SLOT)
        assertEquals(1f, scales.text, 0.001f)
        assertEquals(1f, scales.pad, 0.001f)
    }

    @Test
    fun shrinksChipTextAfterSlotsHitTheFloor() {
        val scales = RemoteAiToolbarFit.scales(240f, 6, 100f, 60f)
        assertEquals(RemoteAiToolbarFit.SLOT_MIN / RemoteAiToolbarFit.SLOT, scales.slot, 0.001f)
        assertTrue(scales.text < 1f)
        assertTrue(scales.text >= RemoteAiToolbarFit.CHIP_TEXT_MIN)
    }
}
