package com.example.ava.homeassistant

import com.example.ava.settings.VoiceSatelliteSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaBluetoothProxyCleanupTest {

    @Test
    fun selectRemoteKeepsThisAvasBluetoothEntry() {
        val ava = device(
            "ava",
            identifiers = listOf("esphome" to "aa:bb:cc:dd:ee:ff"),
            entries = listOf("esphome-ava"),
        )
        val remote = device(
            "ble-adapter",
            via = "ava",
            entries = listOf("bt-remote"),
            connections = listOf("bluetooth" to "aa:bb:cc:dd:ee:ff"),
        )
        val otherProxy = device("ble-other", via = "esp32", entries = listOf("bt-other"))
        val local = device("hci0", entries = listOf("bt-local"))
        val selected = HaBluetoothProxyCleanup.selectRemoteBluetoothEntries(
            ownDeviceId = "ava",
            devices = listOf(ava, remote, otherProxy, local),
            bluetoothEntryIds = setOf("bt-remote", "bt-other", "bt-local"),
            esphomeEntryIds = setOf("esphome-ava"),
        )
        assertEquals(listOf("bt-remote"), selected)
    }

    @Test
    fun selectRemoteIgnoresEsphomeChildDevices() {
        val child = device(
            "ava-sub",
            via = "ava",
            identifiers = listOf("esphome" to "sub"),
            entries = listOf("esphome-ava", "bt-remote"),
        )
        val selected = HaBluetoothProxyCleanup.selectRemoteBluetoothEntries(
            ownDeviceId = "ava",
            devices = listOf(child),
            bluetoothEntryIds = setOf("bt-remote"),
            esphomeEntryIds = setOf("esphome-ava"),
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun selectRemoteSkipsBluetoothEntrySharedWithLocalAdapter() {
        val remote = device("ble-adapter", via = "ava", entries = listOf("bt-shared"))
        val local = device("hci0", entries = listOf("bt-shared"))
        val selected = HaBluetoothProxyCleanup.selectRemoteBluetoothEntries(
            ownDeviceId = "ava",
            devices = listOf(remote, local),
            bluetoothEntryIds = setOf("bt-shared"),
            esphomeEntryIds = emptySet(),
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun selectRemoteIgnoresBlankOwnId() {
        assertTrue(
            HaBluetoothProxyCleanup.selectRemoteBluetoothEntries(
                ownDeviceId = "",
                devices = listOf(device("ble-1", via = "ava", entries = listOf("bt-remote"))),
                bluetoothEntryIds = setOf("bt-remote"),
                esphomeEntryIds = emptySet(),
            ).isEmpty(),
        )
    }

    @Test
    fun matchIdentityUsesUniqueMac() {
        val entry = HaConfigEntrySummary("entry-ava", "ava_node", "esphome", "loaded")
        val ava = device(
            id = "ava",
            primary = "entry-ava",
            entries = listOf("entry-ava"),
            connections = listOf("mac" to "AA:BB:CC:DD:EE:FF"),
            identifiers = listOf("esphome" to "aa:bb:cc:dd:ee:ff"),
        )
        val identity = HaEsphomeEntryResolver.matchIdentity(
            listOf(entry),
            listOf(ava, device("ble-1", via = "ava")),
            VoiceSatelliteSettings(name = "ava_node", serverPort = 6053, macAddress = "AA:BB:CC:DD:EE:FF"),
        )
        assertEquals("entry-ava", identity?.entryId)
        assertEquals("ava", identity?.deviceId)
    }

    @Test
    fun matchIdentityAmbiguousMacReturnsNull() {
        val entry = HaConfigEntrySummary("entry-ava", "ava_node", "esphome", "loaded")
        val first = device(
            id = "ava-1",
            entries = listOf("entry-ava"),
            connections = listOf("mac" to "aa:bb:cc:dd:ee:ff"),
        )
        val second = device(
            id = "ava-2",
            entries = listOf("entry-ava"),
            connections = listOf("mac" to "aa:bb:cc:dd:ee:ff"),
        )
        assertNull(
            HaEsphomeEntryResolver.matchIdentity(
                listOf(entry),
                listOf(first, second),
                VoiceSatelliteSettings(name = "ava_node", serverPort = 6053, macAddress = "AA:BB:CC:DD:EE:FF"),
            ),
        )
    }

    private fun device(
        id: String,
        primary: String? = null,
        entries: List<String> = emptyList(),
        connections: List<Pair<String, String>> = emptyList(),
        identifiers: List<Pair<String, String>> = emptyList(),
        via: String? = null,
    ) = HaDeviceSummary(
        id = id,
        name = id,
        primaryConfigEntry = primary,
        configEntries = entries,
        connections = connections,
        identifiers = identifiers,
        viaDeviceId = via,
    )
}
