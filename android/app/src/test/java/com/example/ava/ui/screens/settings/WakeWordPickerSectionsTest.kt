package com.example.ava.ui.screens.settings

import com.example.ava.microwakeword.Micro
import com.example.ava.microwakeword.WakeWord
import com.example.ava.microwakeword.WakeWordWithId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordPickerSectionsTest {
    @Test
    fun pinsImportedOrDownloadedIdToTop() {
        val bundled = word("ok_nabu")
        val imported = word("custom_hey")
        val (pinned, rest) = wakeWordPickerSections(
            items = listOf(bundled, imported),
            pinnedId = "custom_hey",
        )
        assertEquals("custom_hey", pinned?.id)
        assertEquals(listOf("ok_nabu"), rest.map { it.id })
    }

    @Test
    fun noneLeavesOriginalOrder() {
        val first = word("ok_nabu")
        val second = word("hey_jarvis")
        val (pinned, rest) = wakeWordPickerSections(
            items = listOf(first, second),
            pinnedId = null,
        )
        assertNull(pinned)
        assertEquals(listOf("ok_nabu", "hey_jarvis"), rest.map { it.id })
    }

    @Test
    fun fallbackWhenMissingFromItems() {
        val bundled = word("ok_nabu")
        val imported = word("custom_hey")
        val (pinned, rest) = wakeWordPickerSections(
            items = listOf(bundled),
            pinnedId = "custom_hey",
            fallback = imported,
        )
        assertEquals("custom_hey", pinned?.id)
        assertEquals(listOf("ok_nabu"), rest.map { it.id })
    }

    private fun word(id: String) = WakeWordWithId(
        id = id,
        wakeWord = WakeWord(
            type = "openwakeword",
            wake_word = id,
            author = "",
            website = "",
            model = "$id.onnx",
            trained_languages = emptyArray(),
            version = 1,
            micro = Micro(0.5f, 10, 5, 0, ""),
        ),
    )
}
