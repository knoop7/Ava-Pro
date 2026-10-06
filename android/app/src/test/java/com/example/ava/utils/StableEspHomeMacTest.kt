package com.example.ava.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Pure hash path for [getStableEspHomeMacAddressString] (no Android runtime).
 * Keeps the algorithm pinned so new-install MACs stay reproducible.
 */
class StableEspHomeMacTest {

    @Test
    fun sameSeed_sameMac() {
        assertEquals(derive("abc123"), derive("abc123"))
    }

    @Test
    fun differentSeed_differentMac() {
        assertNotEquals(derive("device-a"), derive("device-b"))
    }

    @Test
    fun neverDefaultSentinel() {
        assertNotEquals("00:00:00:00:00:00", derive("ava-fallback"))
        assertNotEquals("00:00:00:00:00:00", derive(""))
    }

    @Test
    fun locallyAdministeredUnicast() {
        val mac = derive("pixel-test")
        val first = mac.substringBefore(":").toInt(16)
        assertEquals(0x02, first and 0x02)
        assertEquals(0x00, first and 0x01)
    }

    @Test
    fun formatIsUppercaseColonSeparated() {
        val mac = derive("format-check")
        assertTrue(mac.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")))
        assertFalse(mac.any { it.isLowerCase() })
    }

    @Test
    fun bluetoothMac_usesDifferentNamespaceThanNodeMac() {
        val seed = "same-android-id"
        val node = derive(seed)
        val bluetooth = com.example.ava.utils.deriveStableBluetoothMacAddress(seed)
        val legacy = com.example.ava.utils.deriveLegacyBluetoothMacAddress(seed)
        assertNotEquals(node, bluetooth)
        assertNotEquals(bluetooth, legacy)
        val first = bluetooth.substringBefore(":").toInt(16)
        assertEquals(0x02, first and 0x02)
        assertEquals(0x00, first and 0x01)
    }

    @Test
    fun legacyBluetoothMac_matchesOldHashCodeShape() {
        val mac = com.example.ava.utils.deriveLegacyBluetoothMacAddress("abc123")
        assertTrue(mac.startsWith("02:00:"))
        assertTrue(mac.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")))
    }

    /** Production path must equal the pinned pre-#221 algorithm when wlan0 is unreadable. */
    @Test
    fun productionDerive_matchesPinnedAlgorithm() {
        for (id in listOf("abc123", "", "9774d56d682e549c")) {
            assertEquals(derive(id), deriveStableEspHomeMacAddress(buildEspHomeIdentitySeed(id, null)))
        }
    }

    @Test
    fun seed_withoutWlanMac_isAndroidIdOnly() {
        assertEquals("abc123", buildEspHomeIdentitySeed("abc123", null))
        assertEquals("abc123", buildEspHomeIdentitySeed("abc123", ""))
        assertEquals("abc123", buildEspHomeIdentitySeed("abc123", "02:00:00:00:00:00"))
        assertEquals("abc123", buildEspHomeIdentitySeed("abc123", "00:00:00:00:00:00"))
        assertEquals("abc123", buildEspHomeIdentitySeed("abc123", "not-a-mac"))
        assertEquals("ava-fallback", buildEspHomeIdentitySeed("", null))
    }

    @Test
    fun seed_withWlanMac_isNormalizedAndAppended() {
        assertEquals("abc123|wlan0=28:f5:2b:a8:67:34", buildEspHomeIdentitySeed("abc123", "28:F5:2B:A8:67:34"))
        assertEquals("abc123|wlan0=28:f5:2b:a8:67:34", buildEspHomeIdentitySeed("abc123", "28-f5-2b-a8-67-34\n"))
        assertEquals("ava-fallback|wlan0=28:f5:2b:a8:67:34", buildEspHomeIdentitySeed("", "28:f5:2b:a8:67:34"))
    }

    /** Ava-Pro#221: identical ANDROID_ID on two units must still yield different identities. */
    @Test
    fun sameAndroidId_differentWlanMac_differentIdentity() {
        val a = buildEspHomeIdentitySeed("shared-oem-id", "28:f5:2b:a8:67:34")
        val b = buildEspHomeIdentitySeed("shared-oem-id", "28:f5:2b:a8:67:35")
        assertNotEquals(deriveStableEspHomeMacAddress(a), deriveStableEspHomeMacAddress(b))
        assertNotEquals(deriveStableBluetoothMacAddress(a), deriveStableBluetoothMacAddress(b))
        assertNotEquals(a, b)
    }

    @Test
    fun randomMac_isLocallyAdministeredUnicastAndFresh() {
        val macs = (1..50).map { randomLocallyAdministeredMac() }
        macs.forEach { mac ->
            assertTrue(mac.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")))
            val first = mac.substringBefore(":").toInt(16)
            assertEquals(0x02, first and 0x02)
            assertEquals(0x00, first and 0x01)
            assertNotEquals("00:00:00:00:00:00", mac)
        }
        assertEquals(macs.size, macs.toSet().size)
    }

    /** Mirrors the pre-#221 [getStableEspHomeMacAddressString] without Context. */
    private fun derive(androidId: String): String {
        val seed = androidId.ifBlank { "ava-fallback" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("ava-esphome-node-mac:$seed".toByteArray(Charsets.UTF_8))
        val bytes = digest.copyOf(6)
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        if (bytes.all { it == 0.toByte() }) {
            bytes[5] = 0x01
        }
        return bytes.joinToString(":") { "%02X".format(it) }
    }
}
