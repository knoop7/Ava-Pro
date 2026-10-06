package com.example.ava.fleet

import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * Reversible password ↔ 16-char wire token (fixed Base64 of 12 padded bytes).
 *
 * Example (must stay stable forever):
 * - plain `1234` → `MTIzNAAAAAAAAAAA`
 * - `MTIzNAAAAAAAAAAA` → `1234`
 *
 * Algorithm (identical in fleet-console `encodeFleetToken` / `decodeFleetToken`):
 * 1. UTF-8 encode plain password
 * 2. Copy into a 12-byte buffer (truncate or zero-pad)
 * 3. Standard Base64 (no wrap) → exactly 16 ASCII chars
 */
object FleetPasswordCodec {
    const val PLAIN_DEFAULT = "1234"
    /** encode(PLAIN_DEFAULT) — fixed; do not regenerate. */
    const val TOKEN_DEFAULT = "MTIzNAAAAAAAAAAA"
    private const val PADDED_BYTES = 12

    fun encode(plain: String): String {
        val raw = plain.toByteArray(StandardCharsets.UTF_8)
        val padded = ByteArray(PADDED_BYTES)
        val n = minOf(raw.size, PADDED_BYTES)
        System.arraycopy(raw, 0, padded, 0, n)
        return Base64.encodeToString(padded, Base64.NO_WRAP)
    }

    fun decode(token: String): String {
        val t = token.trim()
        if (t.isEmpty()) return ""
        return try {
            val bytes = Base64.decode(t, Base64.DEFAULT)
            var end = bytes.size.coerceAtMost(PADDED_BYTES)
            while (end > 0 && bytes[end - 1] == 0.toByte()) end--
            if (end <= 0) "" else String(bytes, 0, end, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    /** Plain password or already-encoded 16-char token → wire token. */
    fun toWireToken(presented: String): String {
        val t = presented.trim()
        if (t.isEmpty()) return TOKEN_DEFAULT
        if (isWireToken(t)) return t
        return encode(t)
    }

    fun isWireToken(value: String): Boolean {
        val t = value.trim()
        if (t.length != 16) return false
        return t.all { ch ->
            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '+' || ch == '/' || ch == '='
        }
    }
}
