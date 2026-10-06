package com.example.ava.sendspin.noise

import com.example.ava.server.noise.MacFailureException
import com.example.ava.server.noise.NoiseTransport
import java.io.ByteArrayOutputStream

/**
 * Post-handshake encrypted WebSocket wrapper. Matches aiosendspin `noise/wire.py`:
 * type byte 0 = JSON UTF-8, 2/3 = fragments, other = role binary (player 4, art 8…).
 */
class SendspinNoiseTransport internal constructor(
    private val session: NoiseTransport,
    /**
     * The peer's `server_id` from `server/init` (its base64url X25519 static
     * key), authenticated by the handshake. The encrypted `server/hello` only
     * carries `name`, so this is the only place the server id is available.
     */
    val serverId: String = "",
) {
    private var reasm: ByteArrayOutputStream? = null
    private var reasmType: Int? = null

    fun encryptJson(json: String): List<ByteArray> {
        val plain = byteArrayOf(MSG_JSON.toByte()) + json.toByteArray(Charsets.UTF_8)
        return encryptPlain(plain)
    }

    fun encryptPlain(typePrefixed: ByteArray): List<ByteArray> {
        require(typePrefixed.isNotEmpty())
        if (typePrefixed.size <= MAX_TRANSPORT_PLAINTEXT) {
            return listOf(session.encrypt(typePrefixed))
        }
        return fragment(typePrefixed).map { session.encrypt(it) }
    }

    /**
     * Decrypt one binary frame. Returns a complete type-prefixed plaintext, or
     * null when this frame is a non-final fragment.
     */
    fun decryptFrame(ciphertext: ByteArray): ByteArray? {
        val plaintext = try {
            session.decrypt(ciphertext)
        } catch (_: MacFailureException) {
            throw SendspinNoiseFailure("transport MAC failure")
        }
        if (plaintext.isEmpty()) {
            throw SendspinNoiseFailure("empty plaintext")
        }
        return when (plaintext[0].toInt() and 0xFF) {
            MSG_FRAGMENT_MORE -> {
                onFragmentMore(plaintext)
                null
            }
            MSG_FRAGMENT_END -> onFragmentEnd(plaintext)
            else -> {
                if (reasm != null) {
                    throw SendspinNoiseFailure("non-fragment while reassembly in flight")
                }
                plaintext
            }
        }
    }

    private fun onFragmentMore(plaintext: ByteArray) {
        if (reasm == null) {
            if (plaintext.size < 2) {
                throw SendspinNoiseFailure("fragment-more missing orig_type")
            }
            reasmType = plaintext[1].toInt() and 0xFF
            reasm = ByteArrayOutputStream().apply {
                if (plaintext.size > 2) write(plaintext, 2, plaintext.size - 2)
            }
        } else {
            val data = plaintext.copyOfRange(1, plaintext.size)
            val buf = reasm!!
            if (buf.size() + data.size > MAX_REASSEMBLED) {
                throw SendspinNoiseFailure("fragment too large")
            }
            buf.write(data)
        }
    }

    private fun onFragmentEnd(plaintext: ByteArray): ByteArray {
        val buf = reasm ?: throw SendspinNoiseFailure("fragment-end with no start")
        val orig = reasmType ?: throw SendspinNoiseFailure("fragment-end missing type")
        val data = plaintext.copyOfRange(1, plaintext.size)
        if (buf.size() + data.size > MAX_REASSEMBLED) {
            throw SendspinNoiseFailure("fragment too large")
        }
        buf.write(data)
        val body = buf.toByteArray()
        reasm = null
        reasmType = null
        return byteArrayOf(orig.toByte()) + body
    }

    companion object {
        const val MSG_JSON = 0
        const val MSG_FRAGMENT_MORE = 2
        const val MSG_FRAGMENT_END = 3
        const val MAX_TRANSPORT_PLAINTEXT = 65535 - 16
        private const val MAX_REASSEMBLED = 64 * 1024 * 1024

        private fun fragment(plaintext: ByteArray): List<ByteArray> {
            val origType = plaintext[0]
            val data = plaintext.copyOfRange(1, plaintext.size)
            val firstCap = MAX_TRANSPORT_PLAINTEXT - 2
            val contCap = MAX_TRANSPORT_PLAINTEXT - 1
            val frames = ArrayList<ByteArray>()
            val firstLen = minOf(firstCap, data.size)
            frames += byteArrayOf(MSG_FRAGMENT_MORE.toByte(), origType) + data.copyOf(firstLen)
            var offset = firstLen
            while (offset < data.size) {
                val remaining = data.size - offset
                val last = remaining <= contCap
                val take = minOf(contCap, remaining)
                val tag = if (last) MSG_FRAGMENT_END else MSG_FRAGMENT_MORE
                frames += byteArrayOf(tag.toByte()) + data.copyOfRange(offset, offset + take)
                offset += take
            }
            if (frames.size == 1) {
                // Entire payload fit in the first fragment-more — still need an end frame.
                frames += byteArrayOf(MSG_FRAGMENT_END.toByte())
            }
            return frames
        }
    }
}

internal class SendspinNoiseFailure(message: String) : Exception(message)
