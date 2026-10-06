package com.example.ava.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors generated with the same AES-ECB procedure as
 * bluetooth-data-tools privacy.py / HA Private BLE Device.
 */
class BleIrkResolverTest {

    private val irkHex = "00112233445566778899AABBCCDDEEFF"
    private val irkB64 = "ABEiM0RVZneImaq7zN3u/w=="
    private val matchingRpa = "42:11:22:BD:6A:48"
    private val nonRpa = "00:11:22:33:44:55"
    private val wrongIrkRpa = "42:11:22:9D:92:64"

    @Test
    fun parseIrk_hex() {
        val bytes = BleIrkResolver.parseIrk(irkHex)
        assertNotNull(bytes)
        assertEquals(16, bytes!!.size)
        assertEquals(irkHex, BleIrkResolver.toNormalizedHex(bytes))
    }

    @Test
    fun parseIrk_base64() {
        val bytes = BleIrkResolver.parseIrk(irkB64)
        assertNotNull(bytes)
        assertEquals(irkHex, BleIrkResolver.toNormalizedHex(bytes!!))
    }

    @Test
    fun parseIrk_invalid() {
        assertNull(BleIrkResolver.parseIrk(""))
        assertNull(BleIrkResolver.parseIrk("short"))
        assertNull(BleIrkResolver.parseIrk("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"))
    }

    @Test
    fun isResolvablePrivateAddress() {
        assertTrue(BleIrkResolver.isResolvablePrivateAddress(matchingRpa))
        assertFalse(BleIrkResolver.isResolvablePrivateAddress(nonRpa))
    }

    @Test
    fun resolve_matchingRpa() {
        val irk = BleIrkResolver.parseIrk(irkHex)!!
        assertTrue(BleIrkResolver.resolve(irk, matchingRpa))
        assertTrue(BleIrkResolver.resolve(irk, matchingRpa.lowercase()))
    }

    @Test
    fun resolve_rejectsNonRpaAndWrongKey() {
        val irk = BleIrkResolver.parseIrk(irkHex)!!
        assertFalse(BleIrkResolver.resolve(irk, nonRpa))
        assertFalse(BleIrkResolver.resolve(irk, wrongIrkRpa))
    }
}
