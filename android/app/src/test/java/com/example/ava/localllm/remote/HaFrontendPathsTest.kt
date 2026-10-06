package com.example.ava.localllm.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaFrontendPathsTest {

    @Test fun catalogCoversFrontendRoutes() {
        assertTrue(HaFrontendPaths.ALL.size >= 70)
        val paths = HaFrontendPaths.ALL.map { it.path }.toSet()
        assertTrue(paths.contains("/config/dashboard"))
        assertTrue(paths.contains("/profile"))
        assertTrue(paths.contains("/config/automation/dashboard"))
        assertTrue(paths.contains("/config/tools"))
        assertTrue(paths.contains("/history"))
    }

    @Test fun spokenThemeGoesToProfileNotSettings() {
        val first = HaFrontendPaths.match("你好，你帮我现在进设置换个主题吧。").first()
        assertEquals("/profile", first.path)
    }

    @Test fun spokenSettingsGoesToConfigDashboard() {
        assertEquals("/config/dashboard", HaFrontendPaths.match("打开设置").first().path)
        assertEquals("/config/automation/dashboard", HaFrontendPaths.match("打开自动化").first().path)
        assertTrue(HaFrontendPaths.match("设置闹钟").none { it.path == "/config/dashboard" })
        assertTrue(HaFrontendPaths.match("设置提醒，下午3:30开会").none { it.path == "/config/dashboard" })
    }

    @Test fun spokenDeviceQuestionDoesNotOpenLightPanel() {
        assertTrue(HaFrontendPaths.match("客厅灯怎么样").none { it.path == "/light" })
    }

    @Test fun resolveRemapsOldFrontendPaths() {
        assertEquals("/profile", HaFrontendPaths.resolve("主题"))
        assertEquals("/profile", HaFrontendPaths.resolve("/config/theme"))
        assertEquals("/config/dashboard", HaFrontendPaths.resolve("/config"))
        assertEquals("/config/tools", HaFrontendPaths.resolve("/developer-tools"))
        assertEquals("/config/tools/state", HaFrontendPaths.resolve("/developer-tools/state"))
        assertEquals("/config/apps", HaFrontendPaths.resolve("/hassio"))
        assertEquals("/light", HaFrontendPaths.resolve("/lights"))
    }
}
