package com.example.ava.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetAdbHostParseTest {

    @Test
    fun presentStatesAreLive() {
        assertTrue(FleetAdbHost.isPresentAdbState("device"))
        assertTrue(FleetAdbHost.isPresentAdbState("unauthorized"))
        assertTrue(FleetAdbHost.isPresentAdbState(" Device "))
    }

    @Test
    fun offlineAndEmptyAreNotLive() {
        assertFalse(FleetAdbHost.isPresentAdbState("offline"))
        assertFalse(FleetAdbHost.isPresentAdbState("unknown"))
        assertFalse(FleetAdbHost.isPresentAdbState(""))
    }

    @Test
    fun parseDropsOfflineFromMeaningButKeepsLine() {
        val parsed = FleetAdbHost.parseDevices(
            """
            List of devices attached
            192.168.0.121:5555    offline
            192.168.0.8:5555      device product:foo model:Bar
            emulator-5554         device
            """.trimIndent(),
        )
        assertEquals(3, parsed.size)
        assertEquals("offline", parsed[0].state)
        assertEquals("device", parsed[1].state)
        val live = parsed.filter { FleetAdbHost.isPresentAdbState(it.state) }
        assertEquals(listOf("192.168.0.8:5555", "emulator-5554"), live.map { it.serial })
    }

    @Test
    fun networkSerialDetectsWireless() {
        assertTrue(FleetAdbHost.isNetworkSerial("192.168.0.121:5555"))
        assertFalse(FleetAdbHost.isNetworkSerial("emulator-5554"))
        assertFalse(FleetAdbHost.isNetworkSerial("R58M123ABC"))
    }

    @Test
    fun lostGraceWaitsTwoMinutes() {
        val now = 1_000_000L
        assertFalse(FleetAdbHost.shouldAutoCleanLost(0L, now))
        assertFalse(FleetAdbHost.shouldAutoCleanLost(now - 119_000L, now))
        assertTrue(FleetAdbHost.shouldAutoCleanLost(now - FleetAdbHost.LOST_GRACE_MS, now))
    }
}
