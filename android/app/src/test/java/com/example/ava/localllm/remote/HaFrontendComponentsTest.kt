package com.example.ava.localllm.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaFrontendComponentsTest {

    @Test fun catalogKnowsThemeAndSettingsControls() {
        val tags = HaFrontendComponents.INTERACTIVE
        assertTrue(tags.contains("ha-pick-theme-row"))
        assertTrue(tags.contains("ha-theme-picker"))
        assertTrue(tags.contains("ha-settings-row"))
        assertTrue(tags.contains("ha-md-list-item"))
        assertTrue(tags.contains("ha-selector-theme"))
        assertTrue(tags.contains("ha-dropdown"))
        assertTrue(tags.contains("ha-dropdown-item"))
        assertFalse(tags.contains("ha-card"))
    }

    @Test fun everyHaTagIsAShadowHost() {
        assertTrue(HaFrontendComponents.HOST_CHECK.contains("tag.indexOf('ha-')===0"))
        assertTrue(HaFrontendComponents.HOST_CHECK.contains("tag.indexOf('dialog-')===0"))
        assertTrue(HaFrontendComponents.HOST_CHECK.contains("tag.indexOf('hui-')===0"))
        assertTrue(HaFrontendComponents.HOST_CHECK.contains("tag.indexOf('wa-')===0"))
        assertTrue(HaFrontendComponents.CONTROL_RE.contains("dropdown"))
    }

    @Test fun tapSelectorIncludesDialogControls() {
        val isel = HaFrontendComponents.ISEL
        assertTrue(isel.contains("ha-md-list-item"))
        assertTrue(isel.contains("ha-pick-theme-row"))
        assertTrue(isel.contains("ha-dropdown-item"))
        assertFalse(isel.contains("ha-card"))
    }
}
