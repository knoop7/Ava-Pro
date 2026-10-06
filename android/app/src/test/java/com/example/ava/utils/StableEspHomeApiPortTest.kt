package com.example.ava.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [deriveStableEspHomeApiPort] so reinstalls keep the same API port.
 */
class StableEspHomeApiPortTest {

    @Test
    fun sameSeed_samePort() {
        assertEquals(deriveStableEspHomeApiPort("abc123"), deriveStableEspHomeApiPort("abc123"))
    }

    @Test
    fun blankSeed_matchesFallback() {
        assertEquals(deriveStableEspHomeApiPort(""), deriveStableEspHomeApiPort("ava-fallback"))
    }

    @Test
    fun differentSeed_differentPort() {
        assertNotEquals(deriveStableEspHomeApiPort("device-a"), deriveStableEspHomeApiPort("device-b"))
    }

    @Test
    fun neverFactoryDefault6053() {
        val seeds = listOf("", "ava-fallback", "abc123", "device-a", "pixel-test")
        seeds.forEach { seed ->
            val port = deriveStableEspHomeApiPort(seed)
            assertTrue("port $port for seed $seed", port in 6054..7052)
            assertNotEquals(6053, port)
        }
    }
}
