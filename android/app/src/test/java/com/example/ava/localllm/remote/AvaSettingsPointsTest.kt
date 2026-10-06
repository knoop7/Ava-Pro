package com.example.ava.localllm.remote

import com.example.ava.ui.MainNavigationCoordinator
import com.example.ava.ui.Screen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvaSettingsPointsTest {

    @Test fun avaSettingsIsAlwaysAva() {
        assertEquals(AvaSettingsPoints.Intent.AVA, AvaSettingsPoints.intent("我们需要 Ava 设置", overlayOpen = true))
        assertEquals(AvaSettingsPoints.Intent.AVA, AvaSettingsPoints.intent("打开阿瓦设置", overlayOpen = false))
        assertEquals(AvaSettingsPoints.Intent.AVA, AvaSettingsPoints.intent("本机设置", overlayOpen = true))
    }

    @Test fun hashOrHaSettingsIsAlwaysHa() {
        assertEquals(AvaSettingsPoints.Intent.HA, AvaSettingsPoints.intent("hash设置", overlayOpen = false))
        assertEquals(AvaSettingsPoints.Intent.HA, AvaSettingsPoints.intent("打开 HA 设置", overlayOpen = false))
        assertEquals(AvaSettingsPoints.Intent.HA, AvaSettingsPoints.intent("Home Assistant 设置", overlayOpen = true))
    }

    @Test fun bareSettingsFollowsOverlay() {
        assertEquals(AvaSettingsPoints.Intent.AVA, AvaSettingsPoints.intent("设置", overlayOpen = false))
        assertEquals(AvaSettingsPoints.Intent.AVA, AvaSettingsPoints.intent("打开设置", overlayOpen = false))
        assertEquals(AvaSettingsPoints.Intent.HA, AvaSettingsPoints.intent("设置", overlayOpen = true))
        assertEquals(AvaSettingsPoints.Intent.HA, AvaSettingsPoints.intent("打开设置", overlayOpen = true))
        assertTrue(AvaSettingsPoints.isBareSettings("设置"))
        assertTrue(AvaSettingsPoints.isBareSettings("打开设置"))
        assertTrue(AvaSettingsPoints.isBareSettings("please open settings"))
    }

    @Test fun clockTimeAfterSettingsIsNotASettingsPage() {
        assertEquals(AvaSettingsPoints.Intent.NONE, AvaSettingsPoints.intent("设置闹钟", overlayOpen = false))
        assertEquals(AvaSettingsPoints.Intent.NONE, AvaSettingsPoints.intent("设置早上7点的闹钟", overlayOpen = false))
        assertEquals(AvaSettingsPoints.Intent.NONE, AvaSettingsPoints.intent("设置提醒，下午3:30开会", overlayOpen = true))
        assertFalse(AvaSettingsPoints.isBareSettings("设置闹钟"))
        assertTrue(AvaSettingsPoints.refuseSettingsOpen("设置闹钟"))
        assertFalse(AvaSettingsPoints.refuseSettingsOpen("设置"))
        assertFalse(AvaSettingsPoints.refuseSettingsOpen("打开悬浮窗设置"))
    }

    @Test fun themeStaysHaEvenWhenOverlayClosed() {
        assertEquals(AvaSettingsPoints.Intent.HA, AvaSettingsPoints.intent("进设置换个主题", overlayOpen = false))
        assertEquals(AvaSettingsPoints.Intent.HA, AvaSettingsPoints.intent("换肤", overlayOpen = false))
    }

    @Test fun specificAvaPageBeatsBareSettingsWhileOverlayOpen() {
        assertEquals(AvaSettingsPoints.Intent.AVA, AvaSettingsPoints.intent("打开悬浮窗设置", overlayOpen = true))
        assertEquals(AvaSettingsPoints.Intent.AVA, AvaSettingsPoints.intent("改 talking", overlayOpen = true))
        assertEquals(Screen.SETTINGS_BROWSER, AvaSettingsPoints.resolve("悬浮窗设置")?.route)
        assertEquals(Screen.MOD_STORE, AvaSettingsPoints.resolve("模组商店")?.route)
        assertEquals(Screen.MOD_STORE, AvaSettingsPoints.resolve("模组")?.route)
        assertEquals(Screen.SETTINGS_SCREENSAVER, AvaSettingsPoints.resolve("动态屏保")?.route)
        assertEquals(Screen.SETTINGS_SCREENSAVER, AvaSettingsPoints.resolve("动态频表")?.route)
        assertTrue(
            AvaSettingsPoints.resolve("动态屏保")!!.points.contains("enable_idle_screensaver"),
        )
        assertEquals(Screen.SETTINGS_SCREENSAVER, AvaSettingsPoints.resolve("enable_idle_screensaver")?.route)
    }

    @Test fun overlayWordsStayOnThePage() {
        assertEquals(AvaSettingsPoints.Intent.OVERLAY, AvaSettingsPoints.intent("在这个悬浮窗里改一下", overlayOpen = true))
        assertEquals(AvaSettingsPoints.Intent.OVERLAY, AvaSettingsPoints.intent("当前这个页面", overlayOpen = false))
    }

    @Test fun inboundAdbGateIsASettingsPageNotASpokenToken() {
        assertEquals(AvaSettingsPoints.Intent.NONE, AvaSettingsPoints.intent("帮我用 ADB 控制", overlayOpen = false))
        assertEquals(AvaSettingsPoints.Intent.NONE, AvaSettingsPoints.intent("终端控制点一下", overlayOpen = false))
        assertEquals(AvaSettingsPoints.Intent.AVA, AvaSettingsPoints.intent("打开ADB总控", overlayOpen = false))
        assertEquals(Screen.SETTINGS_INTENT_LAUNCHER, AvaSettingsPoints.resolve("adb总控")?.route)
        assertNull(AvaSettingsPoints.resolve("ADB控制"))
    }

    @Test fun talkingOpensTtsPage() {
        val page = AvaSettingsPoints.match("改一下 talking").first()
        assertEquals(Screen.SETTINGS_VOICE_TTS, page.route)
        assertTrue(page.points.contains("tts_volume"))
        assertEquals(Screen.SETTINGS_VOICE_TTS, AvaSettingsPoints.resolve("tts_volume")?.route)
    }

    @Test fun voiceSeatKillButtonsAreBlocked() {
        assertTrue(AvaSettingsPoints.isBlocked("restart_service"))
        assertTrue(AvaSettingsPoints.isBlocked("kill_app"))
        assertTrue(AvaSettingsPoints.isBlocked("manual_wake"))
        assertTrue(AvaSettingsPoints.isBlocked("take_snapshot"))
        assertFalse(AvaSettingsPoints.isBlocked("tts_volume"))
        assertFalse(AvaSettingsPoints.isBlocked("browser_display"))
        assertFalse(AvaSettingsPoints.ALL.any { page -> page.points.any { AvaSettingsPoints.isBlocked(it) } })
    }

    @Test fun currentSettingsPathIsTheLiveRoute() {
        assertEquals(Screen.SETTINGS_VOICE_TTS, AvaSettingsPoints.pageAt("settings/voice/tts")?.route)
        val crumbs = AvaSettingsPoints.crumbs("settings/voice/tts")
        val routes = (0 until crumbs.length()).map { crumbs.getJSONObject(it).getString("route") }
        assertTrue(routes.contains(Screen.SETTINGS))
        assertTrue(routes.contains(Screen.SETTINGS_VOICE_TTS))
        assertTrue(AvaSettingsPoints.isHere("现在在哪个页面"))
        assertTrue(AvaSettingsPoints.isHere("当前路径"))
        assertTrue(AvaSettingsPoints.isHere("当前我们在什么界面下"))
        assertTrue(AvaSettingsPoints.isHere("现在在哪个界面"))
        assertFalse(AvaSettingsPoints.inSettings(Screen.HOME))
        assertTrue(AvaSettingsPoints.inSettings(Screen.SETTINGS_VOICE_TTS))
    }

    @Test fun settingsOriginIsHomeAndRestoresWhenTurnEnds() {
        AvaSettingsPoints.resetForTest()
        MainNavigationCoordinator.clearPending()
        AvaSettingsPoints.markOpenedForTest(Screen.HOME)
        assertEquals(Screen.HOME, AvaSettingsPoints.originForTest())
        assertTrue(AvaSettingsPoints.willRestoreForTest())
        AvaSettingsPoints.markOpenedForTest(Screen.SETTINGS_VOICE_TTS)
        assertEquals(Screen.HOME, AvaSettingsPoints.originForTest())
        AvaSettingsPoints.onTurnFinished()
        assertEquals(Screen.HOME, MainNavigationCoordinator.pendingRoute.value)
        assertFalse(AvaSettingsPoints.willRestoreForTest())
        assertNull(AvaSettingsPoints.originForTest())
        MainNavigationCoordinator.clearPending()
    }

    @Test fun alreadyInSettingsStillRestoresHome() {
        AvaSettingsPoints.resetForTest()
        MainNavigationCoordinator.clearPending()
        AvaSettingsPoints.markOpenedForTest(Screen.SETTINGS_VOICE_TTS)
        assertEquals(Screen.HOME, AvaSettingsPoints.originForTest())
        assertTrue(AvaSettingsPoints.willRestoreForTest())
        AvaSettingsPoints.onTurnFinished()
        assertEquals(Screen.HOME, MainNavigationCoordinator.pendingRoute.value)
        MainNavigationCoordinator.clearPending()
    }

    @Test fun unfinishedTurnDoesNotClearOrigin() {
        AvaSettingsPoints.resetForTest()
        AvaSettingsPoints.markOpenedForTest(Screen.HOME)
        assertTrue(AvaSettingsPoints.willRestoreForTest())
        assertEquals(Screen.HOME, AvaSettingsPoints.originForTest())
    }

    @Test fun talkingPointIsWritableThroughVolume() {
        val row = AvaSettingsLive.pointRow(
            id = "tts_volume",
            name = "TTS volume",
            kind = "number",
            state = "40",
            options = null,
            writable = AvaSettingsLive.writable("tts_volume", hasSetter = false, local = false),
        )
        assertTrue(row.getBoolean("writable"))
        assertEquals("ava_volume", row.getString("via"))
        assertFalse(row.has("screenshot"))
        assertTrue(AvaSettingsLive.writable("browser_display", hasSetter = true, local = false))
        assertFalse(AvaSettingsLive.writable("restart_service", hasSetter = true, local = false))
        assertFalse(AvaSettingsLive.writable("unknown_row", hasSetter = false, local = false))
    }

    @Test fun schemaAcceptsSettingsWithoutATarget() {
        val def = AvaSelfTools.schema(listOf("read", "settings", "set")).tools.single()
        assertTrue(def.params.single { it.name == "action" }.description.contains("settings="))
        assertNull(AvaToolCallback.validate(def, JSONObject().put("action", "settings")))
        assertNull(AvaToolCallback.validate(def, JSONObject().put("action", "settings").put("target", "talking")))
        assertNotNull(AvaToolCallback.validate(def, JSONObject().put("action", "settings").put("on", true)))
    }
}
