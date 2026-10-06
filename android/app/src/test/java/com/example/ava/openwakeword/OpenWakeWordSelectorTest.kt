package com.example.ava.openwakeword

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWakeWordSelectorTest {
    @Test
    fun bundledAlwaysVisible() {
        assertTrue(
            OpenWakeWordSelectorPolicy.visibleInWakeWordPicker(
                id = "ok_nabu",
                bundled = true,
                sourceUrl = "https://github.com/fwartner/home-assistant-wakewords-collection",
                curatedCatalogIds = emptySet(),
                selectedIds = emptySet(),
            ),
        )
    }

    @Test
    fun curatedCatalogDownloadVisible() {
        assertTrue(
            OpenWakeWordSelectorPolicy.visibleInWakeWordPicker(
                id = "hey_jarvis",
                bundled = false,
                sourceUrl = "",
                curatedCatalogIds = setOf("hey_jarvis"),
                selectedIds = emptySet(),
            ),
        )
    }

    @Test
    fun avaRepoSourceVisibleWithoutCatalog() {
        assertTrue(
            OpenWakeWordSelectorPolicy.visibleInWakeWordPicker(
                id = "hey_luna",
                bundled = false,
                sourceUrl = "https://raw.githubusercontent.com/knoop7/Ava/master/openwakeword/models/hey_luna/hey_luna.onnx",
                curatedCatalogIds = emptySet(),
                selectedIds = emptySet(),
            ),
        )
    }

    @Test
    fun communityModelHiddenUntilSelected() {
        assertFalse(
            OpenWakeWordSelectorPolicy.visibleInWakeWordPicker(
                id = "alexa",
                bundled = false,
                sourceUrl = "https://raw.githubusercontent.com/fwartner/home-assistant-wakewords-collection/main/en/alexa/alexa.onnx",
                curatedCatalogIds = setOf("hey_jarvis"),
                selectedIds = emptySet(),
            ),
        )
        assertTrue(
            OpenWakeWordSelectorPolicy.visibleInWakeWordPicker(
                id = "alexa",
                bundled = false,
                sourceUrl = "https://raw.githubusercontent.com/fwartner/home-assistant-wakewords-collection/main/en/alexa/alexa.onnx",
                curatedCatalogIds = setOf("hey_jarvis"),
                selectedIds = setOf("alexa"),
            ),
        )
    }

    @Test
    fun fileImportHiddenUntilSelected() {
        assertFalse(
            OpenWakeWordSelectorPolicy.visibleInWakeWordPicker(
                id = "custom_wake",
                bundled = false,
                sourceUrl = "",
                curatedCatalogIds = setOf("hey_jarvis"),
                selectedIds = emptySet(),
            ),
        )
    }

    @Test
    fun fwartnerIsNotAvaCurated() {
        assertFalse(
            OpenWakeWordSelectorPolicy.isAvaCuratedSource(
                "https://raw.githubusercontent.com/fwartner/home-assistant-wakewords-collection/main/en/ok_nabu/ok_nabu.onnx",
            ),
        )
        assertTrue(
            OpenWakeWordSelectorPolicy.isAvaCuratedSource(
                "https://raw.githubusercontent.com/knoop7/Ava/master/openwakeword/index.json",
            ),
        )
    }

    @Test
    fun pickerDeleteOnlyCuratedDownloads() {
        assertTrue(
            OpenWakeWordSelectorPolicy.removableFromWakeWordPicker(
                id = "hey_jarvis",
                imported = true,
                bundled = false,
                sourceUrl = "https://raw.githubusercontent.com/knoop7/Ava/master/openwakeword/models/hey_jarvis/hey_jarvis.onnx",
                curatedCatalogIds = setOf("hey_jarvis"),
            ),
        )
        assertFalse(
            OpenWakeWordSelectorPolicy.removableFromWakeWordPicker(
                id = "alexa",
                imported = true,
                bundled = false,
                sourceUrl = "https://raw.githubusercontent.com/fwartner/home-assistant-wakewords-collection/main/en/alexa/alexa.onnx",
                curatedCatalogIds = setOf("hey_jarvis"),
            ),
        )
        assertFalse(
            OpenWakeWordSelectorPolicy.removableFromWakeWordPicker(
                id = "ok_nabu",
                imported = false,
                bundled = true,
                sourceUrl = "",
                curatedCatalogIds = emptySet(),
            ),
        )
    }
}
