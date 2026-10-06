package com.example.ava.webcompat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeckoPackAmTest {

    @Test
    fun android5OnlyHasStartService() {
        assertEquals(listOf("startservice"), GeckoPackAm.serviceVerbs(21))
        assertEquals(listOf("startservice"), GeckoPackAm.serviceVerbs(25))
    }

    @Test
    fun android8And16PreferForegroundThenStartService() {
        val expected = listOf("start-foreground-service", "startservice")
        assertEquals(expected, GeckoPackAm.serviceVerbs(26))
        assertEquals(expected, GeckoPackAm.serviceVerbs(31))
        assertEquals(expected, GeckoPackAm.serviceVerbs(36))
    }

    @Test
    fun showCommandsOnAndroid5CarryUrlAndDarkExtras() {
        val cmds = GeckoPackAm.commands(
            sdkInt = 21,
            serviceAction = "ACTION_SHOW",
            bridgeAction = GeckoPackAm.bridgeActionForService("ACTION_SHOW"),
            url = "http://192.168.0.254:8123/dashboard-home/",
            followDark = true,
            darkMode = false,
        )
        assertEquals(3, cmds.size)
        assertTrue(cmds[0].startsWith("am broadcast --user 0"))
        assertTrue(cmds[0].contains("GeckoChildControlReceiver"))
        assertTrue(cmds[0].contains("SHOW_BROWSER"))
        assertTrue(cmds[1].startsWith("am startservice --user 0"))
        assertTrue(cmds[1].contains("-a ACTION_SHOW"))
        assertTrue(cmds[1].contains("--es url 'http://192.168.0.254:8123/dashboard-home/'"))
        assertTrue(cmds[1].contains("--ez follow_dark_mode true"))
        assertTrue(cmds[1].contains("--ez dark_mode false"))
        assertFalse(cmds[1].contains("receipt_token"))
        assertTrue(cmds[2].startsWith("am start --user 0"))
        assertTrue(cmds[2].contains("SHOW_BROWSER"))
        assertFalse(cmds[1].contains("start-foreground-service"))
    }

    @Test
    fun showCommandsOnAndroid16TryForegroundFirst() {
        val cmds = GeckoPackAm.commands(
            sdkInt = 36,
            serviceAction = "ACTION_HIDE",
            bridgeAction = GeckoPackAm.bridgeActionForService("ACTION_HIDE"),
        )
        assertEquals(4, cmds.size)
        assertTrue(cmds[0].startsWith("am broadcast --user 0"))
        assertTrue(cmds[0].contains("HIDE_BROWSER"))
        assertTrue(cmds[1].startsWith("am start-foreground-service --user 0"))
        assertTrue(cmds[1].contains("-a ACTION_HIDE"))
        assertTrue(cmds[2].startsWith("am startservice --user 0"))
        assertTrue(cmds[3].contains("GeckoBrowserBridge"))
    }

    @Test
    fun quotesSingleQuotesInUrl() {
        val quoted = GeckoPackAm.shQuote("http://x/'a")
        assertEquals("'http://x/'\\''a'", quoted)
    }

    @Test
    fun showCommandsCarryReceiptToken() {
        val cmds = GeckoPackAm.commands(
            sdkInt = 26,
            serviceAction = "ACTION_SHOW",
            bridgeAction = GeckoPackAm.bridgeActionForService("ACTION_SHOW"),
            url = "http://ha.local:8123/",
            receiptToken = 42L,
        )
        assertTrue(cmds.any { it.contains("--el receipt_token 42") && it.startsWith("am broadcast") })
        assertTrue(cmds.last().contains("--el receipt_token 42"))
    }

    @Test
    fun navigateMapsToBridgeNavigate() {
        assertEquals(
            "com.example.ava.gecko.action.NAVIGATE",
            GeckoPackAm.bridgeActionForService("ACTION_SHOW_OR_REFRESH"),
        )
    }
}
