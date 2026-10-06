package com.example.ava.bluetooth

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Resolves BLE Resolvable Private Addresses (RPA) using an Identity Resolving Key (IRK).
 *
 * Algorithm matches Home Assistant Private BLE / bluetooth-data-tools:
 * - RPA when address[0] & 0xC0 == 0x40
 * - AES-128-ECB(irk, 13×0x00 ‖ prand[0..2]) → hash in ciphertext[13..15]
 * - Match when hash == address[3..5]
 *
 * Presence-only helper. Do not use on ESPHome proxy advertisement forwarding paths.
 */
object BleIrkResolver {

    private val HEX_IRK = Regex("^[0-9a-fA-F]{32}$")
    private val PADDING = ByteArray(13)

    /** Parse hex (32 chars) or base64 IRK into 16 bytes. Returns null if invalid. */
    fun parseIrk(input: String): ByteArray? {
        val trimmed = input.trim().replace(" ", "").replace("-", "").replace(":", "")
        if (trimmed.isEmpty()) return null

        if (HEX_IRK.matches(trimmed)) {
            return trimmed.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        // Raw hex may still contain separators already stripped above; try base64 next.
        val base64Candidate = input.trim().replace(" ", "")
        return try {
            val decoded = Base64.decode(base64Candidate, Base64.DEFAULT)
            if (decoded.size == 16) decoded else null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun toNormalizedHex(irk: ByteArray): String {
        require(irk.size == 16) { "IRK must be 16 bytes" }
        return irk.joinToString("") { b -> "%02X".format(b.toInt() and 0xFF) }
    }

    fun isResolvablePrivateAddress(address: String): Boolean {
        val bytes = macToBytes(address) ?: return false
        return (bytes[0].toInt() and 0xC0) == 0x40
    }

    /**
     * Returns true if [address] is an RPA produced by [irk].
     * Non-RPA addresses always return false (use exact MAC match instead).
     */
    fun resolve(irk: ByteArray, address: String): Boolean {
        if (irk.size != 16) return false
        val rpa = macToBytes(address) ?: return false
        if ((rpa[0].toInt() and 0xC0) != 0x40) return false

        val plaintext = ByteArray(16)
        System.arraycopy(PADDING, 0, plaintext, 0, 13)
        System.arraycopy(rpa, 0, plaintext, 13, 3)

        val ciphertext = try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(irk, "AES"))
            cipher.doFinal(plaintext)
        } catch (_: Exception) {
            return false
        }

        // Constant-time compare of hash (ciphertext[13..15]) vs rpa[3..5]
        var diff = 0
        for (i in 0 until 3) {
            diff = diff or (ciphertext[13 + i].toInt() xor rpa[3 + i].toInt())
        }
        return diff == 0
    }

    private fun macToBytes(address: String): ByteArray? {
        val hex = address.trim().replace(":", "").replace("-", "")
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return null
        }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /** Digests IRK for logs without leaking the key. */
    fun irkFingerprint(irkHex: String): String {
        val bytes = parseIrk(irkHex) ?: return "invalid"
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
