package com.example.ava.localllm.remote

import org.junit.Assert.assertTrue
import org.junit.Test

class HaFrontendOverlaysTest {

    @Test fun catalogCoversEveryOverlayKind() {
        val kinds = HaFrontendOverlays.KINDS
        assertTrue(kinds.contains("more_info"))
        assertTrue(kinds.contains("shortcut"))
        assertTrue(kinds.contains("dialog"))
        assertTrue(kinds.contains("sheet"))
        assertTrue(kinds.contains("quick_bar"))
        assertTrue(kinds.contains("drawer"))
        assertTrue(kinds.contains("menu"))
        assertTrue(kinds.contains("toast"))
    }

    @Test fun hostCheckPiercesSheetsAndMoreInfo() {
        val check = HaFrontendOverlays.HOST_CHECK
        assertTrue(check.contains("more-info"))
        assertTrue(check.contains("sheet"))
        assertTrue(check.contains("ha-quick-bar"))
        assertTrue(check.contains("notification-drawer"))
        assertTrue(check.contains("ha-toast"))
        assertTrue(check.contains("wa-popup"))
        assertTrue(HaFrontendOverlays.KIND_JS.contains("more_info"))
        assertTrue(HaFrontendOverlays.KIND_JS.contains("shortcut"))
        assertTrue(HaFrontendOverlays.SHELL_SEL.contains("ha-adaptive-dialog"))
        assertTrue(HaFrontendOverlays.SHELL_SEL.contains("ha-bottom-sheet"))
    }
}
