package com.example.ava.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarHomeEntryTest {

    @Test
    fun offerHomeEntryTurnsOnMatchingShortcutOnly() {
        val seed = SidebarSettings()
        assertFalse(seed.showWeather)
        assertFalse(seed.showSimpleClock)
        assertFalse(seed.showDreamClock)

        val weather = seed.withOfferedHomeEntry(SidebarItemKey.Weather)
        assertTrue(weather.showWeather)
        assertFalse(weather.showSimpleClock)
        assertFalse(weather.showDreamClock)

        val clocks = weather
            .withOfferedHomeEntry(SidebarItemKey.SimpleClock)
            .withOfferedHomeEntry(SidebarItemKey.DreamClock)
        assertTrue(clocks.showWeather)
        assertTrue(clocks.showSimpleClock)
        assertTrue(clocks.showDreamClock)
    }

    @Test
    fun offerHomeEntryIgnoresSwitchRows() {
        val seed = SidebarSettings()
        assertSame(seed, seed.withOfferedHomeEntry(SidebarItemKey.DarkMode))
        assertSame(seed, seed.withOfferedHomeEntry(SidebarItemKey.Camera))
    }

    @Test
    fun newUserShowsTouchPadByDefault() {
        assertTrue(NEW_USER_SIDEBAR_SETTINGS.enableSidebar)
        assertTrue(NEW_USER_SIDEBAR_SETTINGS.showTouchPad)
        assertTrue(NEW_USER_SIDEBAR_SETTINGS.showDeviceControl)
        assertTrue(NEW_USER_SIDEBAR_SETTINGS.showHome)
        assertEquals(SidebarItemKey.Home, DEFAULT_SIDEBAR_ITEM_ORDER[0])
        assertEquals(SidebarItemKey.DeviceControl, DEFAULT_SIDEBAR_ITEM_ORDER[1])
        assertEquals(SidebarItemKey.TouchPad, DEFAULT_SIDEBAR_ITEM_ORDER[2])
    }

    @Test
    fun upgradeKeepsTouchPadOffUntilEnabled() {
        assertFalse(SidebarSettings().showTouchPad)
    }

    @Test
    fun headerStaysVisibleUntilHidden() {
        assertFalse(SidebarSettings().hideSidebarHeader)
        assertFalse(NEW_USER_SIDEBAR_SETTINGS.hideSidebarHeader)
    }

    @Test
    fun homeSwitchAndCornerButtonDefaultOnSettings() {
        assertTrue(SidebarSettings().showHome)
        assertTrue(NEW_USER_SIDEBAR_SETTINGS.showHome)
        assertEquals(HomeCornerButton.SETTINGS, SidebarSettings().homeCornerButton)
        assertEquals(HomeCornerButton.SETTINGS, NEW_USER_SIDEBAR_SETTINGS.homeCornerButton)
    }

    @Test
    fun homeDefaultsOnAboveDeviceControl() {
        assertTrue(SidebarSettings().showHome)
        val order = sidebarItemOrderOrDefault(
            listOf(SidebarItemKey.DeviceControl, SidebarItemKey.DarkMode),
        )
        assertEquals(SidebarItemKey.Home, order[0])
        assertEquals(SidebarItemKey.DeviceControl, order[1])
    }

    @Test
    fun dockOrderDefaultsHomeThenSettings() {
        assertEquals(
            listOf(SidebarDockKey.Home, SidebarDockKey.Settings),
            sidebarDockOrderOrDefault(null),
        )
    }

    @Test
    fun dockOrderKeepsSwapAndFillsMissing() {
        assertEquals(
            listOf(SidebarDockKey.Settings, SidebarDockKey.Home),
            sidebarDockOrderOrDefault(listOf(SidebarDockKey.Settings)),
        )
    }

    @Test
    fun missingTouchPadInsertsAfterDeviceControl() {
        val order = sidebarItemOrderOrDefault(
            listOf(SidebarItemKey.DeviceControl, SidebarItemKey.DarkMode),
        )
        assertEquals(SidebarItemKey.Home, order[0])
        assertEquals(SidebarItemKey.DeviceControl, order[1])
        assertEquals(SidebarItemKey.TouchPad, order[2])
    }
}
