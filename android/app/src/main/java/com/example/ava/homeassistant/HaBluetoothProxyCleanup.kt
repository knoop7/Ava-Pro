package com.example.ava.homeassistant

import android.util.Log
import com.example.ava.settings.VoiceSatelliteSettings

/**
 * Deletes this Ava's remote Bluetooth scanner config entry.
 *
 * HA creates a separate `bluetooth` entry when ESPHome registers the proxy
 * (`source_device_id` / `via_device_id` → this Ava). Core has no
 * `device_registry/remove` WS; the frontend removes that entry with REST
 * `DELETE /api/config/config_entries/entry/{id}`.
 */
object HaBluetoothProxyCleanup {
    private const val TAG = "HaBtCleanup"

    data class Result(
        val removed: Int = 0,
        val failed: Int = 0,
        val reason: Reason? = null,
    ) {
        val ok: Boolean get() = reason == null && failed == 0
    }

    enum class Reason {
        DETECT_ON,
        NOT_CONNECTED,
        NO_DEVICE,
        NO_VIA,
    }

    suspend fun run(
        client: HaWsClient,
        settings: VoiceSatelliteSettings,
        detectEnabled: Boolean,
    ): Result {
        if (detectEnabled) return Result(reason = Reason.DETECT_ON)
        if (client.connectionState.value !is HaWsClient.ConnectionState.Connected) {
            return Result(reason = Reason.NOT_CONNECTED)
        }
        val esphomeEntries = client.listEsphomeEntries()
        val bluetoothEntries = client.listConfigEntries("bluetooth")
        val devices = client.listDevices()
        val identity = HaEsphomeEntryResolver.matchIdentity(esphomeEntries, devices, settings)
        val ownId = identity?.deviceId
            ?: identity?.entryId?.let { HaEsphomeEntryResolver.ownDeviceId(devices, it) }
        if (ownId.isNullOrBlank()) {
            Log.w(TAG, "No unique Ava device in HA registry")
            return Result(reason = Reason.NO_DEVICE)
        }
        val bluetoothIds = bluetoothEntries.map { it.entryId }.filter { it.isNotBlank() }.toSet()
        val esphomeIds = esphomeEntries.map { it.entryId }.filter { it.isNotBlank() }.toSet()
        val entryIds = selectRemoteBluetoothEntries(ownId, devices, bluetoothIds, esphomeIds)
        if (entryIds.isEmpty()) {
            Log.i(TAG, "cleanup via=$ownId no remote bluetooth entry")
            return Result()
        }
        var removed = 0
        var failed = 0
        for (entryId in entryIds) {
            if (client.deleteConfigEntry(entryId)) {
                removed++
            } else {
                failed++
                Log.w(TAG, "REST delete bluetooth entry failed id=$entryId")
            }
        }
        Log.i(TAG, "cleanup via=$ownId entries=$entryIds removed=$removed failed=$failed")
        return Result(removed = removed, failed = failed)
    }

    fun selectRemoteBluetoothEntries(
        ownDeviceId: String,
        devices: List<HaDeviceSummary>,
        bluetoothEntryIds: Set<String>,
        esphomeEntryIds: Set<String>,
    ): List<String> {
        if (ownDeviceId.isBlank() || bluetoothEntryIds.isEmpty()) return emptyList()
        val viaOurs = devices.filter { device ->
            device.id.isNotBlank() &&
                device.id != ownDeviceId &&
                device.viaDeviceId == ownDeviceId &&
                !isEsphomeOwned(device)
        }
        val candidates = viaOurs.flatMap { it.configEntries }
            .filter { it in bluetoothEntryIds && it !in esphomeEntryIds }
            .distinct()
        return candidates.filter { entryId ->
            devices.none { device ->
                entryId in device.configEntries &&
                    device.id != ownDeviceId &&
                    device.viaDeviceId != ownDeviceId
            }
        }
    }

    private fun isEsphomeOwned(device: HaDeviceSummary): Boolean {
        return device.identifiers.any { it.first.equals("esphome", ignoreCase = true) }
    }
}
