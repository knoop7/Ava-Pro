package com.example.ava.homeassistant

import android.util.Log
import com.example.ava.settings.DEFAULT_MAC_ADDRESS
import com.example.ava.settings.VoiceSatelliteSettings
import com.example.ava.settings.normalizeEspNodeName
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes ESPHome options-flow open/submit/abort. Two syncers must not
 * hold overlapping flows on the same config entry.
 */
internal object HaEsphomeOptionsGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

internal data class HaEsphomeIdentity(
    val entryId: String,
    val deviceId: String?,
)

internal object HaEsphomeEntryResolver {
    private const val TAG = "HaEsphomeEntry"

    suspend fun resolve(
        client: HaWsClient,
        settings: VoiceSatelliteSettings,
    ): String? = resolveIdentity(client, settings)?.entryId

    suspend fun resolveIdentity(
        client: HaWsClient,
        settings: VoiceSatelliteSettings,
    ): HaEsphomeIdentity? {
        val entries = client.listEsphomeEntries()
        if (entries.isEmpty()) return null
        val devices = client.listDevices()
        return matchIdentity(entries, devices, settings)
    }

    fun matchIdentity(
        entries: List<HaConfigEntrySummary>,
        devices: List<HaDeviceSummary>,
        settings: VoiceSatelliteSettings,
    ): HaEsphomeIdentity? {
        if (entries.isEmpty()) return null
        val esphomeIds = entries.map { it.entryId }.toSet()
        val mac = normalizeMac(settings.macAddress)
        if (mac != null && mac != normalizeMac(DEFAULT_MAC_ADDRESS)) {
            val macHits = devices.filter { device ->
                deviceHasMac(device, mac) && device.configEntries.any { it in esphomeIds }
            }
            if (macHits.size == 1) {
                val device = macHits[0]
                val primary = device.primaryConfigEntry
                val entryId = when {
                    primary != null && primary in esphomeIds -> primary
                    else -> device.configEntries.filter { it in esphomeIds }.distinct().singleOrNull()
                }
                if (entryId != null) return HaEsphomeIdentity(entryId, device.id.takeIf { it.isNotBlank() })
            }
            if (macHits.size > 1) {
                Log.w(TAG, "Ambiguous MAC match across ${macHits.size} devices")
                return null
            }
        }
        val name = normalizeEspNodeName(settings.name)
        if (name.isBlank()) return null
        val titleHits = entries.filter { normalizeEspNodeName(it.title) == name }
        if (titleHits.size > 1) {
            Log.w(TAG, "Ambiguous title match for $name")
            return null
        }
        val entryId = titleHits.singleOrNull()?.entryId ?: return null
        return HaEsphomeIdentity(entryId, ownDeviceId(devices, entryId, mac))
    }

    fun ownDeviceId(
        devices: List<HaDeviceSummary>,
        entryId: String,
        mac: String? = null,
    ): String? {
        if (mac != null) {
            val macHits = devices.filter { device ->
                deviceHasMac(device, mac) && entryId in device.configEntries
            }
            if (macHits.size == 1) return macHits[0].id.takeIf { it.isNotBlank() }
            if (macHits.size > 1) return null
        }
        val hits = devices.filter { device ->
            device.id.isNotBlank() &&
                (device.primaryConfigEntry == entryId || entryId in device.configEntries) &&
                device.viaDeviceId.isNullOrBlank() &&
                device.identifiers.any { it.first.equals("esphome", ignoreCase = true) }
        }
        return hits.singleOrNull()?.id
    }

    private fun normalizeMac(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val hex = raw.lowercase().replace(Regex("[^0-9a-f]"), "")
        if (hex.length != 12) return null
        return hex.chunked(2).joinToString(":")
    }

    private fun deviceHasMac(device: HaDeviceSummary, mac: String): Boolean {
        val hit = device.connections.any { (kind, value) ->
            kind.equals("mac", ignoreCase = true) && normalizeMac(value) == mac
        }
        if (hit) return true
        return device.identifiers.any { (kind, value) ->
            kind.equals("esphome", ignoreCase = true) && normalizeMac(value) == mac
        }
    }
}
