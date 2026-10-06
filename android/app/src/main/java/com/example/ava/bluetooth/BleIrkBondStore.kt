package com.example.ava.bluetooth

import android.content.Context
import android.util.Log
import com.example.ava.utils.RootHelper
import com.example.ava.utils.ShizukuUtils
import java.io.File

/**
 * Reads a bonded peer's IRK from the Android Bluetooth bond store (bt_config).
 *
 * Public Android APIs do not expose IRK. Reading typically requires **root**
 * (Shizuku without root usually cannot access /data/misc/bluedroid).
 * Presence-only — never used on the ESPHome proxy path.
 *
 * LE_KEY_PID (tBTM_LE_PID_KEYS): first 16 bytes = peer IRK (little-endian in file),
 * then identity_addr_type (1) + identity BD_ADDR (6) on most stacks.
 * HA docs: reverse the 16 IRK bytes before use.
 */
object BleIrkBondStore {

    private const val TAG = "BleIrkBondStore"

    private val CONFIG_PATHS = listOf(
        "/data/misc/bluedroid/bt_config.conf",
        "/data/misc/bluedroid/bt_config.bak",
        "/data/misc/bluedroid/bt_config.conf.bak",
        "/data/misc/bluetooth/bt_config.conf",
        "/data/misc/bluetooth/bt_config.bak",
    )

    /**
     * True only when we can actually open the bond store.
     * ADB/wireless Shizuku (uid 2000) is the same as `adb shell` and cannot
     * read `/data/misc/bluedroid` — authorization does not raise that privilege.
     */
    fun canReadBondStore(): Boolean {
        if (RootHelper.hasRootAccess()) return true
        return ShizukuUtils.isShizukuPermissionGranted() && ShizukuUtils.isShizukuRoot()
    }

    /**
     * Preferred IRK (LE bytes reversed), or null if unavailable.
     * Prefer [tryReadRemoteIrkCandidates] when validation can pick endianness.
     */
    fun tryReadRemoteIrk(context: Context, macAddress: String): String? {
        return tryReadRemoteIrkCandidates(context, macAddress).firstOrNull()
    }

    /**
     * Candidates for [macAddress], preferred-first:
     * 1) LE_KEY_PID[0..15] byte-reversed (HA / Private BLE)
     * 2) same 16 bytes as stored (some OEM dumps already match AES order)
     */
    fun tryReadRemoteIrkCandidates(context: Context, macAddress: String): List<String> {
        val normalizedMac = normalizeMac(macAddress) ?: return emptyList()
        val conf = readConfigText(context) ?: return emptyList()
        return parseRemoteIrkCandidatesFromConfig(conf, normalizedMac)
    }

    fun parseRemoteIrkFromConfig(configText: String, normalizedMac: String): String? {
        return parseRemoteIrkCandidatesFromConfig(configText, normalizedMac).firstOrNull()
    }

    fun parseRemoteIrkCandidatesFromConfig(configText: String, normalizedMac: String): List<String> {
        val section = findDeviceSection(configText, normalizedMac) ?: return emptyList()
        val pidLine = section.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("LE_KEY_PID", ignoreCase = true) }
            ?: return emptyList()
        val raw = pidLine.substringAfter('=', "").trim()
        val bytes = parseHexBytes(raw) ?: return emptyList()
        if (bytes.size < 16) return emptyList()
        val irkLe = bytes.copyOfRange(0, 16)
        val reversed = BleIrkResolver.toNormalizedHex(irkLe.reversedArray())
        val asStored = BleIrkResolver.toNormalizedHex(irkLe)
        return if (reversed == asStored) listOf(reversed) else listOf(reversed, asStored)
    }

    private fun readConfigText(context: Context): String? {
        // Try root and Shizuku independently — do not skip Shizuku when root
        // is present but bt_config paths are empty / inaccessible.
        if (RootHelper.hasRootAccess()) {
            for (path in CONFIG_PATHS) {
                val text = suCapture("cat \"$path\" 2>/dev/null")
                if (looksLikeBtConfig(text)) {
                    Log.i(TAG, "Read bt_config via root ($path, ${text!!.length} bytes)")
                    return text
                }
            }
            Log.d(TAG, "Root present but bt_config paths empty; trying Shizuku")
        }

        if (ShizukuUtils.isShizukuPermissionGranted()) {
            val uid = ShizukuUtils.shizukuUid()
            // uid 2000 == adb shell. Do not cat/cp: every path is bluetooth:660
            // and SELinux bluetooth_data_file. Trying looks like a failure.
            if (uid != 0) {
                Log.i(
                    TAG,
                    "Skip Shizuku bt_config (uid=$uid, ADB/shell). " +
                        "IRK stays empty; presence uses MAC / system resolver.",
                )
                return null
            }
            for (path in CONFIG_PATHS) {
                val (code, out) = ShizukuUtils.executeCommandForOutput("cat \"$path\"")
                if (code == 0 && looksLikeBtConfig(out)) {
                    Log.i(TAG, "Read bt_config via Shizuku cat ($path, ${out.length} bytes, uid=$uid)")
                    return out
                }
            }
            // Fallback: copy then read. Fragile on scoped storage; keep for older ROMs.
            val dest = File(context.getExternalFilesDir(null) ?: context.cacheDir, "ava_bt_config_probe.conf")
            for (path in CONFIG_PATHS) {
                val cmd = "cp \"$path\" \"${dest.absolutePath}\" && chmod 666 \"${dest.absolutePath}\""
                val (code, _) = ShizukuUtils.executeCommand(cmd)
                if (code == 0 && dest.isFile && dest.length() > 0L) {
                    return try {
                        dest.readText().takeIf { looksLikeBtConfig(it) }?.also {
                            Log.i(TAG, "Read bt_config via Shizuku cp ($path, ${it.length} bytes)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read copied bt_config", e)
                        null
                    } finally {
                        dest.delete()
                    }
                }
            }
        }

        Log.d(TAG, "bt_config unavailable (need root, or Shizuku started as root)")
        return null
    }

    private fun looksLikeBtConfig(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.contains('[') &&
            (text.contains("LE_KEY", ignoreCase = true) || text.contains("Address=", ignoreCase = true))
    }

    private fun suCapture(command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val exited = process.waitFor()
            if (exited == 0 && stdout.isNotBlank()) stdout else null
        } catch (e: Exception) {
            Log.w(TAG, "suCapture failed", e)
            null
        }
    }

    /**
     * Locate the bonded-device section for [normalizedMac].
     * Match order:
     * 1) section header == MAC
     * 2) IdentityAddr / LeIdentityAddr / Address field in section body
     * 3) identity BD_ADDR embedded in LE_KEY_PID (bytes 17..22)
     */
    private fun findDeviceSection(configText: String, normalizedMac: String): String? {
        val macNoColon = normalizedMac.replace(":", "")
        val sectionHeader = Regex("""^\[([^\]]+)\]\s*$""", RegexOption.MULTILINE)
        val matches = sectionHeader.findAll(configText).toList()
        var identityEmbeddedFallback: String? = null

        for (i in matches.indices) {
            val header = matches[i].groupValues[1].trim()
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else configText.length
            val body = configText.substring(start, end)

            val headerNorm = normalizeMac(header) ?: compactToMac(header)
            val headerCompact = header.replace(":", "").replace("-", "").uppercase()
            if (headerNorm == normalizedMac || headerCompact == macNoColon) {
                return body
            }

            if (sectionBodyMentionsMac(body, normalizedMac, macNoColon)) {
                return body
            }

            if (identityEmbeddedFallback == null &&
                leKeyPidIdentityMatches(body, normalizedMac, macNoColon)
            ) {
                identityEmbeddedFallback = body
            }
        }
        return identityEmbeddedFallback
    }

    private fun sectionBodyMentionsMac(
        body: String,
        normalizedMac: String,
        macNoColon: String,
    ): Boolean {
        val keys = listOf("IdentityAddr", "LeIdentityAddr", "Address", "DevAddr")
        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim()
            if (keys.none { it.equals(key, ignoreCase = true) }) continue
            val value = trimmed.substring(eq + 1).trim().removeSurrounding("\"")
            val valueNorm = normalizeMac(value) ?: compactToMac(value)
            val valueCompact = value.replace(":", "").replace("-", "").uppercase()
            if (valueNorm == normalizedMac || valueCompact == macNoColon) return true
        }
        return false
    }

    /** tBTM_LE_PID_KEYS: irk[16] + addr_type[1] + static_addr[6] (addr may be LE). */
    private fun leKeyPidIdentityMatches(
        body: String,
        normalizedMac: String,
        macNoColon: String,
    ): Boolean {
        val pidLine = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("LE_KEY_PID", ignoreCase = true) }
            ?: return false
        val raw = pidLine.substringAfter('=', "").trim()
        val bytes = parseHexBytes(raw) ?: return false
        if (bytes.size < 23) return false
        val identity = bytes.copyOfRange(17, 23)
        fun matches(addr: ByteArray): Boolean {
            val mac = addr.joinToString(":") { b -> "%02X".format(b.toInt() and 0xFF) }
            val compact = addr.joinToString("") { b -> "%02X".format(b.toInt() and 0xFF) }
            return mac == normalizedMac || compact == macNoColon
        }
        return matches(identity) || matches(identity.reversedArray())
    }

    private fun compactToMac(raw: String): String? {
        val hex = raw.trim().replace(":", "").replace("-", "").uppercase()
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return hex.chunked(2).joinToString(":")
    }

    private fun normalizeMac(address: String): String? {
        val hex = address.trim().replace(":", "").replace("-", "").uppercase()
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return hex.chunked(2).joinToString(":")
    }

    private fun parseHexBytes(raw: String): ByteArray? {
        val cleaned = raw.trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace(",", " ")
            .replace("0x", "", ignoreCase = true)
        val parts = if (cleaned.contains(' ') || cleaned.contains('\t')) {
            cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        } else {
            if (cleaned.length % 2 != 0) return null
            cleaned.chunked(2)
        }
        if (parts.isEmpty()) return null
        return try {
            parts.map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: NumberFormatException) {
            null
        }
    }
}
