package com.example.ava.sendspin.noise

import android.os.Build
import android.util.Base64
import java.security.MessageDigest

/**
 * Spec wire helpers for Sendspin Noise: base64url (no padding), Sentinel PSK,
 * and `psk_id`. Values match aiosendspin `noise/constants.py` + `noise/keys.py`.
 */
internal object SendspinNoiseCodec {
    const val SUITE_CHACHA = "25519_ChaChaPoly_SHA256"
    const val PROTOCOL_VERSION = 1
    const val PEER_ID_CHARS = 43
    const val KEY_SIZE = 32

    private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP
    private const val URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val PSK_ID_LABEL = "sendspin-psk-id-v1".toByteArray(Charsets.US_ASCII)

    /** SHA-256("sendspin-sentinel-psk-v1") — public constant, authenticates nothing. */
    val SENTINEL_PSK: ByteArray =
        sha256("sendspin-sentinel-psk-v1".toByteArray(Charsets.US_ASCII))

    /**
     * Published Sentinel `psk_id` (spec).
     * `base64url(SHA-256("sendspin-psk-id-v1" || Sentinel PSK))`
     */
    const val SENTINEL_PSK_ID = "GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo"

    fun b64urlEncode(data: ByteArray): String {
        // Android 7's android.util.Base64 URL_SAFE path has shipped OEM builds
        // that insert newlines or reject `-` / `_`. Peer ids and handshake
        // frames are base64url, so a bad codec fails client/init outright.
        if (legacyWireCodec()) return encodeUrl(data)
        return try {
            Base64.encodeToString(data, B64).trimEnd('=').trim()
        } catch (_: Throwable) {
            // android.util.Base64 is a stub in JVM unit tests.
            encodeUrl(data)
        }
    }

    fun b64urlDecode(value: String): ByteArray? {
        if (value.isEmpty()) return null
        if (legacyWireCodec()) return decodeUrl(value)
        return try {
            val pad = (4 - value.length % 4) % 4
            val padded = value + "=".repeat(pad)
            try {
                Base64.decode(padded, B64)
            } catch (_: Throwable) {
                decodeUrl(value)
            }
        } catch (_: IllegalArgumentException) {
            decodeUrl(value)
        }
    }

    /** Android 5–7.1. SDK 0 (unit tests) keeps the java.util path via the catch above. */
    private fun legacyWireCodec(): Boolean {
        val sdk = try {
            Build.VERSION.SDK_INT
        } catch (_: Throwable) {
            0
        }
        return sdk in 1..Build.VERSION_CODES.N_MR1
    }

    internal fun encodeUrl(data: ByteArray): String {
        val out = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            out.append(URL_ALPHABET[b0 shr 2])
            out.append(URL_ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) {
                out.append(URL_ALPHABET[((b1 and 0x0f) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            }
            if (b2 >= 0) {
                out.append(URL_ALPHABET[b2 and 0x3f])
            }
            i += 3
        }
        return out.toString()
    }

    internal fun decodeUrl(value: String): ByteArray? {
        val cleaned = value.trim().trimEnd('=')
        if (cleaned.isEmpty() || cleaned.length % 4 == 1) return null
        val bytes = ArrayList<Byte>((cleaned.length * 3) / 4)
        var i = 0
        while (i < cleaned.length) {
            val c0 = urlValue(cleaned[i])
            val c1 = if (i + 1 < cleaned.length) urlValue(cleaned[i + 1]) else 0
            val c2 = if (i + 2 < cleaned.length) urlValue(cleaned[i + 2]) else 0
            val c3 = if (i + 3 < cleaned.length) urlValue(cleaned[i + 3]) else 0
            if (c0 < 0 || c1 < 0) return null
            bytes.add(((c0 shl 2) or (c1 shr 4)).toByte())
            if (i + 2 < cleaned.length) {
                if (c2 < 0) return null
                bytes.add((((c1 and 0x0f) shl 4) or (c2 shr 2)).toByte())
            }
            if (i + 3 < cleaned.length) {
                if (c3 < 0) return null
                bytes.add((((c2 and 0x03) shl 6) or c3).toByte())
            }
            i += 4
        }
        return bytes.toByteArray()
    }

    private fun urlValue(c: Char): Int {
        val i = URL_ALPHABET.indexOf(c)
        return if (i >= 0) i else -1
    }

    fun pskIdFor(psk: ByteArray): String {
        require(psk.size == KEY_SIZE)
        return b64urlEncode(sha256(PSK_ID_LABEL + psk))
    }

    fun peerPublicKey(peerId: String): ByteArray? {
        val cleaned = peerId.trim().trimEnd('=')
        if (cleaned.length != PEER_ID_CHARS) return null
        val raw = b64urlDecode(cleaned) ?: return null
        return raw.takeIf { it.size == KEY_SIZE }
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
