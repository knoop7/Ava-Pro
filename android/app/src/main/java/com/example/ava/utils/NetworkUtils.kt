package com.example.ava.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.io.File
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Seed for **first-time** ESPHome identity (node MAC, API port, Bluetooth scanner MAC).
 *
 * [Settings.Secure.ANDROID_ID] alone is not unique on every device: GMS-less OEM images
 * (Sonoff NSPanel Pro / PX30, Android 8.1) ship whole batches with the same value, so two
 * panels derived the same node MAC and Home Assistant merged them into one device
 * (Ava-Pro#221). When the wlan0 hardware MAC is readable (Android <= 9, or root) it is
 * mixed in; those units do have distinct Wi-Fi MACs. Where it is not readable the seed is
 * byte-for-byte the old ANDROID_ID-only value, so nothing changes for those devices.
 *
 * Never uses eth0 — NSPanel Pro batches are known to clone that one too
 * (home-assistant/core#166307).
 */
@SuppressLint("HardwareIds")
fun espHomeIdentitySeed(context: Context): String {
    val androidId = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    } catch (_: Exception) {
        null
    }.orEmpty()
    return buildEspHomeIdentitySeed(androidId, readWlanHardwareMac())
}

/** Pure seed composition so unit tests can pin both shapes without Android. */
fun buildEspHomeIdentitySeed(androidId: String, wlanMac: String?): String {
    val base = androidId.ifBlank { "ava-fallback" }
    val mac = normalizeHardwareMac(wlanMac) ?: return base
    return "$base|wlan0=$mac"
}

/**
 * Lowercase `aa:bb:cc:dd:ee:ff`, or null for anything that cannot identify a device:
 * malformed, all-zero, or the `02:00:00:00:00:00` placeholder Android hands to apps.
 */
fun normalizeHardwareMac(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val hex = raw.lowercase().replace(Regex("[^0-9a-f]"), "")
    if (hex.length != 12) return null
    if (hex == "000000000000" || hex == "020000000000") return null
    return hex.chunked(2).joinToString(":")
}

/**
 * Factory wlan0 MAC, or null when the platform hides it (Android 10+ for non-system apps).
 * Read-only lookups; never throws.
 */
private fun readWlanHardwareMac(): String? {
    val fromInterface = try {
        NetworkInterface.getByName("wlan0")?.hardwareAddress
            ?.takeIf { it.size == 6 }
            ?.joinToString(":") { "%02x".format(it) }
    } catch (_: Throwable) {
        null
    }
    normalizeHardwareMac(fromInterface)?.let { return it }
    val fromSysfs = try {
        File("/sys/class/net/wlan0/address").takeIf { it.canRead() }?.readText()?.trim()
    } catch (_: Throwable) {
        null
    }
    return normalizeHardwareMac(fromSysfs)
}

/**
 * Stable ESPHome node MAC for **first-time** identity only (when stored MAC is still
 * [com.example.ava.settings.DEFAULT_MAC_ADDRESS]).
 *
 * Derived from [espHomeIdentitySeed] so the same device gets the same MAC after
 * reinstall / clear data. Existing installs that already stored a random MAC are never
 * rewritten by callers — do not use this to migrate old identities.
 *
 * Uses a dedicated hash namespace so this value stays distinct from the synthetic
 * Bluetooth adapter MAC Ava reports on the same seed
 * ([deriveStableBluetoothMacAddress] / [deriveLegacyBluetoothMacAddress]).
 */
fun getStableEspHomeMacAddressString(context: Context): String =
    deriveStableEspHomeMacAddress(espHomeIdentitySeed(context))

/** Pure hash path so unit tests can pin the algorithm without Android. */
fun deriveStableEspHomeMacAddress(seed: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("ava-esphome-node-mac:${seed.ifBlank { "ava-fallback" }}".toByteArray(Charsets.UTF_8))
    return macFromBytes(digest.copyOf(6))
}

/**
 * Fresh random locally-administered unicast MAC. For the manual "regenerate identity"
 * repair on units whose derived identity collides with another device; random rather
 * than derived because the colliding units may share every readable hardware id.
 */
fun randomLocallyAdministeredMac(): String {
    val bytes = ByteArray(6)
    SecureRandom().nextBytes(bytes)
    return macFromBytes(bytes)
}

private fun macFromBytes(bytes: ByteArray): String {
    // Locally administered + unicast (IEEE 802).
    bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
    // Never emit the unset sentinel 00:00:00:00:00:00.
    if (bytes.all { it == 0.toByte() }) {
        bytes[5] = 0x01
    }
    return bytes.joinToString(":") { "%02X".format(it) }
}

/**
 * Device-stable ESPHome API port for **first-time** identity only.
 *
 * Same seed as [getStableEspHomeMacAddressString], but a different hash
 * namespace, so reinstall / clear-data reuse the port Home Assistant
 * already stored. Existing installs that already stored a port must not call
 * this to migrate.
 *
 * Range is 6054..7052 (ESPHome default 6053 plus 1..999), never 6053.
 */
fun getStableEspHomeApiPort(context: Context): Int =
    deriveStableEspHomeApiPort(espHomeIdentitySeed(context))

/** Pure hash path so unit tests can pin the algorithm without Android. */
fun deriveStableEspHomeApiPort(seed: String): Int {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("ava-esphome-api-port:${seed.ifBlank { "ava-fallback" }}".toByteArray(Charsets.UTF_8))
    var packed = 0
    repeat(4) { i ->
        packed = (packed shl 8) or (digest[i].toInt() and 0xFF)
    }
    val offset = (packed.toLong() and 0xFFFFFFFFL) % STABLE_API_PORT_SPAN
    return STABLE_API_PORT_BASE + offset.toInt()
}

private const val STABLE_API_PORT_BASE = 6054
private const val STABLE_API_PORT_SPAN = 999L

/**
 * SHA-256 Bluetooth adapter MAC for **new** installs only.
 * Different hash namespace from [getStableEspHomeMacAddressString] so HA never
 * sees the ESPHome node and the Bluetooth scanner as the same address.
 */
fun deriveStableBluetoothMacAddress(seed: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("ava-bluetooth-adapter-mac:${seed.ifBlank { "ava-fallback" }}".toByteArray(Charsets.UTF_8))
    return macFromBytes(digest.copyOf(6))
}

/**
 * The 0.7.x `ANDROID_ID.hashCode()` Bluetooth MAC. Existing Home Assistant
 * scanner entries were keyed on this; upgrades must keep reporting it.
 */
fun deriveLegacyBluetoothMacAddress(androidId: String): String {
    val seed = androidId.ifBlank { "ava-fallback" }
    val hash = seed.hashCode().toLong() and 0xFFFFFFFFL
    val bytes = ByteArray(6)
    bytes[0] = ((hash shr 40) and 0xFF).toByte()
    bytes[1] = ((hash shr 32) and 0xFF).toByte()
    bytes[2] = ((hash shr 24) and 0xFF).toByte()
    bytes[3] = ((hash shr 16) and 0xFF).toByte()
    bytes[4] = ((hash shr 8) and 0xFF).toByte()
    bytes[5] = (hash and 0xFF).toByte()
    bytes[0] = (bytes[0].toInt() or 0x02).toByte()
    return bytes.joinToString(":") { "%02X".format(it) }
}

fun getStableBluetoothMacAddressString(context: Context): String =
    deriveStableBluetoothMacAddress(espHomeIdentitySeed(context))

@SuppressLint("HardwareIds")
fun getLegacyBluetoothMacAddressString(context: Context): String {
    val androidId = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    } catch (_: Exception) {
        null
    }.orEmpty()
    return deriveLegacyBluetoothMacAddress(androidId)
}
