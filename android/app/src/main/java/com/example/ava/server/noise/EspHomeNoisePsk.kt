package com.example.ava.server.noise

import java.security.SecureRandom
import java.util.Base64

/**
 * ESPHome API encryption key: 32 raw bytes, stored and shown as standard
 * base64 (`openssl rand -base64 32`). Empty means encryption is off.
 */
object EspHomeNoisePsk {
    const val SIZE = 32

    fun decodeOrNull(text: String): ByteArray? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        // aioesphomeapi uses binascii.a2b_base64: drop anything outside the
        // standard alphabet so a HA-copied key with newlines still works.
        val cleaned = buildString(trimmed.length) {
            for (c in trimmed) {
                if (c in BASE64_ALPHABET) append(c)
            }
        }
        if (cleaned.isEmpty()) return null
        return try {
            val padded = padBase64(cleaned)
            Base64.getDecoder().decode(padded).takeIf { it.size == SIZE }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun padBase64(value: String): String {
        val rem = value.length % 4
        if (rem == 0) return value
        return value + "=".repeat(4 - rem)
    }

    fun isValidOrEmpty(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isEmpty() || decodeOrNull(trimmed) != null
    }

    fun generateBase64(): String {
        val bytes = ByteArray(SIZE)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun encode(bytes: ByteArray): String {
        require(bytes.size == SIZE)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
}
