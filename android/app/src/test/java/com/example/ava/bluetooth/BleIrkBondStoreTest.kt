package com.example.ava.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleIrkBondStoreTest {

    @Test
    fun parseRemoteIrk_fromBtConfigSection() {
        // LE_KEY_PID first 16 bytes little-endian → reverse for HA/Private BLE order
        // BE IRK: 00112233445566778899AABBCCDDEEFF
        // LE stored: FF EEDD ... 11 00
        val leHex = "FF EE DD CC BB AA 99 88 77 66 55 44 33 22 11 00 AA BB CC DD EE FF 01"
        val conf = """
            [Adapter]
            Address=11:22:33:44:55:66
            [AA:BB:CC:DD:EE:FF]
            Name=TestPhone
            LE_KEY_PID=$leHex
            [11:22:33:44:55:66]
            Name=Other
        """.trimIndent()

        val irk = BleIrkBondStore.parseRemoteIrkFromConfig(conf, "AA:BB:CC:DD:EE:FF")
        assertEquals("00112233445566778899AABBCCDDEEFF", irk)
    }

    @Test
    fun parseRemoteIrkCandidates_includesReversedAndAsStored() {
        val leHex = "FF EE DD CC BB AA 99 88 77 66 55 44 33 22 11 00 00 AA BB CC DD EE FF"
        val conf = """
            [AA:BB:CC:DD:EE:FF]
            LE_KEY_PID=$leHex
        """.trimIndent()

        val candidates = BleIrkBondStore.parseRemoteIrkCandidatesFromConfig(conf, "AA:BB:CC:DD:EE:FF")
        assertEquals(
            listOf(
                "00112233445566778899AABBCCDDEEFF",
                "FFEEDDCCBBAA99887766554433221100",
            ),
            candidates,
        )
    }

    @Test
    fun parseRemoteIrk_matchesEmbeddedIdentityInLeKeyPid() {
        // Section header is unrelated; identity MAC is embedded after IRK + addr_type.
        val identity = "AA BB CC DD EE FF"
        val leHex = "FF EE DD CC BB AA 99 88 77 66 55 44 33 22 11 00 00 $identity"
        val conf = """
            [42:11:22:BD:6A:48]
            Name=RotatedSection
            LE_KEY_PID=$leHex
        """.trimIndent()

        val irk = BleIrkBondStore.parseRemoteIrkFromConfig(conf, "AA:BB:CC:DD:EE:FF")
        assertEquals("00112233445566778899AABBCCDDEEFF", irk)
    }

    @Test
    fun parseRemoteIrk_matchesIdentityAddrField() {
        val leHex = "FF EE DD CC BB AA 99 88 77 66 55 44 33 22 11 00 00 11 22 33 44 55 66"
        val conf = """
            [11:22:33:44:55:66]
            Name=Bonded
            IdentityAddr=AA:BB:CC:DD:EE:FF
            LE_KEY_PID=$leHex
        """.trimIndent()

        val irk = BleIrkBondStore.parseRemoteIrkFromConfig(conf, "AA:BB:CC:DD:EE:FF")
        assertEquals("00112233445566778899AABBCCDDEEFF", irk)
    }

    @Test
    fun parseRemoteIrk_matchesEmbeddedIdentityLittleEndian() {
        // Identity AA:BB:CC:DD:EE:FF stored little-endian as FF EE DD CC BB AA
        val leHex = "FF EE DD CC BB AA 99 88 77 66 55 44 33 22 11 00 00 FF EE DD CC BB AA"
        val conf = """
            [42:11:22:BD:6A:48]
            LE_KEY_PID=$leHex
        """.trimIndent()

        val irk = BleIrkBondStore.parseRemoteIrkFromConfig(conf, "AA:BB:CC:DD:EE:FF")
        assertEquals("00112233445566778899AABBCCDDEEFF", irk)
    }

    @Test
    fun parseRemoteIrk_missingEntry_returnsNullAndNoCandidates() {
        val conf = "[Adapter]\nAddress=11:22:33:44:55:66\n"
        assertNull(BleIrkBondStore.parseRemoteIrkFromConfig(conf, "AA:BB:CC:DD:EE:FF"))
        assertTrue(BleIrkBondStore.parseRemoteIrkCandidatesFromConfig(conf, "AA:BB:CC:DD:EE:FF").isEmpty())
    }
}
