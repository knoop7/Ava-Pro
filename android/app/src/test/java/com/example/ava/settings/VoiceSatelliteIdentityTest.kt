package com.example.ava.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Upgrade / first-run rules for ESPHome identity. Existing Home Assistant
 * entries must keep their stored MAC and port.
 */
class VoiceSatelliteIdentityTest {

    private val derivedMac = "02:AA:BB:CC:DD:EE"
    private val derivedPort = 6420
    private val stockName = "pixel_voice_assistant"
    private val stockNameWithMac = "pixel_ddee_voice_assistant"

    @Test
    fun existingMacAndRandomPort_frozen() {
        val current = settings(mac = "AA:BB:CC:DD:EE:FF", port = 6888, name = "living_room")
        assertEquals(current, next(current))
    }

    @Test
    fun existingMacAndFactory6053_frozen() {
        val current = settings(mac = "AA:BB:CC:DD:EE:FF", port = 6053, name = "living_room")
        assertEquals(current, next(current))
    }

    @Test
    fun existingMacAndUserPort_frozen() {
        val current = settings(
            mac = "AA:BB:CC:DD:EE:FF",
            port = 6200,
            name = "living_room",
            userConfigured = true,
        )
        assertEquals(current, next(current))
    }

    @Test
    fun existingMacAndInvalidPort_healedOnly() {
        val current = settings(mac = "AA:BB:CC:DD:EE:FF", port = 0, name = "living_room")
        val next = next(current)
        assertEquals(current.macAddress, next.macAddress)
        assertEquals(current.name, next.name)
        assertEquals(derivedPort, next.serverPort)
    }

    @Test
    fun newDeviceFactoryPort_getsDerivedIdentity() {
        val current = settings(mac = DEFAULT_MAC_ADDRESS, port = 6053, name = stockName)
        val next = next(current)
        assertEquals(derivedMac, next.macAddress)
        assertEquals(derivedPort, next.serverPort)
        assertEquals(stockNameWithMac, next.name)
    }

    @Test
    fun newDeviceUserConfigured6053_keepsPort() {
        val current = settings(
            mac = DEFAULT_MAC_ADDRESS,
            port = 6053,
            name = stockName,
            userConfigured = true,
        )
        val next = next(current)
        assertEquals(derivedMac, next.macAddress)
        assertEquals(6053, next.serverPort)
    }

    @Test
    fun newDeviceUserPort_keepsPort() {
        val current = settings(
            mac = DEFAULT_MAC_ADDRESS,
            port = 6200,
            name = stockName,
            userConfigured = true,
        )
        val next = next(current)
        assertEquals(derivedMac, next.macAddress)
        assertEquals(6200, next.serverPort)
        assertEquals(stockNameWithMac, next.name)
    }

    @Test
    fun newDeviceRestoredAutoPort_keepsPort() {
        val current = settings(mac = DEFAULT_MAC_ADDRESS, port = 6888, name = stockName)
        val next = next(current)
        assertEquals(derivedMac, next.macAddress)
        assertEquals(6888, next.serverPort)
    }

    @Test
    fun newDeviceCustomName_preserved() {
        val current = settings(mac = DEFAULT_MAC_ADDRESS, port = 6053, name = "kitchen_panel")
        val next = next(current)
        assertEquals("kitchen_panel", next.name)
        assertEquals(derivedPort, next.serverPort)
    }

    @Test
    fun encryptionKeyDefaultsOffAndStaysOff() {
        val current = settings(mac = DEFAULT_MAC_ADDRESS, port = 6053, name = stockName)
        assertEquals("", current.encryptionKey)
        assertEquals("", next(current).encryptionKey)
    }

    @Test
    fun bluetoothMac_frozenOnceSet() {
        assertEquals(
            "02:11:22:33:44:55",
            nextBluetoothMacAddress(
                currentNodeMac = "AA:BB:CC:DD:EE:FF",
                currentBluetoothMac = "02:11:22:33:44:55",
                legacyMac = "02:00:AA:BB:CC:DD",
                derivedMac = "02:FE:00:00:00:01",
            ),
        )
    }

    @Test
    fun bluetoothMac_upgradeKeepsLegacyHash() {
        assertEquals(
            "02:00:AA:BB:CC:DD",
            nextBluetoothMacAddress(
                currentNodeMac = "AA:BB:CC:DD:EE:FF",
                currentBluetoothMac = "",
                legacyMac = "02:00:AA:BB:CC:DD",
                derivedMac = "02:FE:00:00:00:01",
            ),
        )
    }

    @Test
    fun bluetoothMac_newInstallUsesDerived() {
        assertEquals(
            "02:FE:00:00:00:01",
            nextBluetoothMacAddress(
                currentNodeMac = DEFAULT_MAC_ADDRESS,
                currentBluetoothMac = "",
                legacyMac = "02:00:AA:BB:CC:DD",
                derivedMac = "02:FE:00:00:00:01",
            ),
        )
    }

    @Test
    fun regenerate_stockMacSuffixedName_followsNewMac() {
        val current = settings(mac = "02:AA:BB:CC:46:38", port = 6420, name = "px30_evb_4638_voice_assistant")
            .copy(bluetoothMacAddress = "02:11:22:33:44:55")
        val next = regeneratedVoiceSatelliteIdentity(
            current = current,
            newMac = "02:12:34:56:78:9A",
            newBluetoothMac = "02:FE:DC:BA:98:76",
            model = "px30 evb",
        )
        assertEquals("02:12:34:56:78:9A", next.macAddress)
        assertEquals("02:FE:DC:BA:98:76", next.bluetoothMacAddress)
        assertEquals("px30_evb_789a_voice_assistant", next.name)
        assertEquals(6420, next.serverPort)
    }

    @Test
    fun regenerate_bareStockName_followsNewMac() {
        val current = settings(mac = "02:AA:BB:CC:46:38", port = 6053, name = "px30_evb_voice_assistant")
        val next = regeneratedVoiceSatelliteIdentity(current, "02:12:34:56:78:9A", "02:FE:DC:BA:98:76", "px30 evb")
        assertEquals("px30_evb_789a_voice_assistant", next.name)
    }

    @Test
    fun regenerate_customName_keptAndPortKept() {
        val current = settings(mac = "02:AA:BB:CC:46:38", port = 6888, name = "kitchen_panel", userConfigured = true)
            .copy(encryptionKey = "psk", haRemoteUrl = "http://ha")
        val next = regeneratedVoiceSatelliteIdentity(current, "02:12:34:56:78:9A", "02:FE:DC:BA:98:76", "px30 evb")
        assertEquals("kitchen_panel", next.name)
        assertEquals(6888, next.serverPort)
        assertEquals(true, next.serverPortUserConfigured)
        assertEquals("psk", next.encryptionKey)
        assertEquals("http://ha", next.haRemoteUrl)
    }

    @Test
    fun defaultNameForMac_matchesFirstRunShape() {
        assertEquals("pixel_ddee_voice_assistant", defaultNameForMac("Pixel", "02:AA:BB:CC:DD:EE"))
        assertEquals("android_ddee_voice_assistant", defaultNameForMac(null, "02:AA:BB:CC:DD:EE"))
    }

    private fun settings(
        mac: String,
        port: Int,
        name: String,
        userConfigured: Boolean = false,
    ) = VoiceSatelliteSettings(
        name = name,
        serverPort = port,
        macAddress = mac,
        serverPortUserConfigured = userConfigured,
    )

    private fun next(current: VoiceSatelliteSettings) = nextVoiceSatelliteIdentity(
        current = current,
        newMac = derivedMac,
        derivedPort = derivedPort,
        defaultDeviceName = stockName,
        nextDefaultName = stockNameWithMac,
    )
}
