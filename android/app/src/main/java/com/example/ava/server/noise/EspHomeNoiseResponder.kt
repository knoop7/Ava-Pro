package com.example.ava.server.noise

/**
 * ESPHome native-API Noise session, device/responder side.
 *
 * Matches firmware `api_frame_helper_noise.cpp`:
 * prologue is `NoiseAPIInit` + big-endian client-hello length + hello body
 * (empty hello ⇒ `NoiseAPIInit\x00\x00`, which is what Home Assistant hardcodes).
 */
internal class EspHomeNoiseResponder(
    psk: ByteArray,
    private val deviceName: String,
    private val macAddress: String,
) {
    private val psk = psk.copyOf()
    private var handshake: NoiseNNpsk0? = null
    private var transport: NoiseTransport? = null

    fun serverHello(clientHello: ByteArray): ByteArray {
        handshake = NoiseNNpsk0.responder(psk, buildPrologue(clientHello))
        return buildServerHello(deviceName, macAddress)
    }

    /**
     * @return handshake payload starting with `0x00` (success).
     * @throws MacFailureException when the PSK does not match.
     */
    fun handshakeReply(frame: ByteArray): ByteArray {
        if (frame.isEmpty()) {
            throw IllegalArgumentException("Empty handshake message")
        }
        if (frame[0] != 0.toByte()) {
            throw IllegalArgumentException("Bad handshake error byte")
        }
        val hs = handshake ?: error("serverHello must run first")
        hs.readMessage(frame.copyOfRange(1, frame.size))
        val msg = hs.writeMessage()
        transport = hs.split()
        return byteArrayOf(0x00) + msg
    }

    fun encryptPacket(type: Int, payload: ByteArray): ByteArray {
        val t = transport ?: error("handshake is not finished")
        if (payload.size > MAX_PLAINTEXT) {
            throw IllegalArgumentException(
                "Noise payload ${payload.size} exceeds ESPHome uint16 max $MAX_PLAINTEXT",
            )
        }
        val header = byteArrayOf(
            ((type ushr 8) and 0xff).toByte(),
            (type and 0xff).toByte(),
            ((payload.size ushr 8) and 0xff).toByte(),
            (payload.size and 0xff).toByte(),
        )
        return t.encrypt(header + payload)
    }

    fun decryptPacket(encrypted: ByteArray): Pair<Int, ByteArray> {
        val t = transport ?: error("handshake is not finished")
        return decodeEspHomeNoisePacket(t.decrypt(encrypted))
    }

    companion object {
        const val HANDSHAKE_MAC_FAILURE = "Handshake MAC failure"
        const val MAX_NOISE_FRAME = 0xFFFF
        private const val AEAD_TAG = 16
        private const val INNER_HEADER = 4
        /** Inner protobuf after `type_be16 + len_be16`, so the outer 0x01 frame stays in uint16. */
        const val MAX_PLAINTEXT = MAX_NOISE_FRAME - AEAD_TAG - INNER_HEADER
        val PROLOGUE_PREFIX = "NoiseAPIInit".toByteArray(Charsets.US_ASCII)

        fun buildPrologue(clientHello: ByteArray): ByteArray {
            val out = ByteArray(PROLOGUE_PREFIX.size + 2 + clientHello.size)
            System.arraycopy(PROLOGUE_PREFIX, 0, out, 0, PROLOGUE_PREFIX.size)
            out[PROLOGUE_PREFIX.size] = ((clientHello.size ushr 8) and 0xff).toByte()
            out[PROLOGUE_PREFIX.size + 1] = (clientHello.size and 0xff).toByte()
            if (clientHello.isNotEmpty()) {
                System.arraycopy(clientHello, 0, out, PROLOGUE_PREFIX.size + 2, clientHello.size)
            }
            return out
        }

        fun emptyHelloPrologue(): ByteArray = buildPrologue(ByteArray(0))

        fun buildServerHello(name: String, mac: String): ByteArray {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val macBytes = mac.toByteArray(Charsets.US_ASCII)
            val out = ByteArray(1 + nameBytes.size + 1 + macBytes.size + 1)
            out[0] = 0x01
            System.arraycopy(nameBytes, 0, out, 1, nameBytes.size)
            out[1 + nameBytes.size] = 0
            System.arraycopy(macBytes, 0, out, 2 + nameBytes.size, macBytes.size)
            return out
        }

        fun handshakeFailureFrame(reason: String = HANDSHAKE_MAC_FAILURE): ByteArray =
            byteArrayOf(0x01) + reason.toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Home Assistant / aioesphomeapi initiator, used by tests to prove the
 * responder transcript matches the client that will actually connect.
 */
internal class EspHomeNoiseInitiator(psk: ByteArray) {
    private val handshake = NoiseNNpsk0.initiator(psk, EspHomeNoiseResponder.emptyHelloPrologue())
    private var transport: NoiseTransport? = null

    fun clientHello(): ByteArray = ByteArray(0)

    fun firstHandshake(): ByteArray = byteArrayOf(0x00) + handshake.writeMessage()

    fun finish(serverHandshake: ByteArray) {
        if (serverHandshake.isEmpty() || serverHandshake[0] != 0.toByte()) {
            val reason = if (serverHandshake.size > 1) {
                String(serverHandshake, 1, serverHandshake.size - 1, Charsets.US_ASCII)
            } else {
                "empty"
            }
            throw MacFailureException(reason)
        }
        handshake.readMessage(serverHandshake.copyOfRange(1, serverHandshake.size))
        transport = handshake.split()
    }

    fun encryptPacket(type: Int, payload: ByteArray): ByteArray {
        val t = transport ?: error("handshake is not finished")
        if (payload.size > EspHomeNoiseResponder.MAX_PLAINTEXT) {
            throw IllegalArgumentException(
                "Noise payload ${payload.size} exceeds ESPHome uint16 max ${EspHomeNoiseResponder.MAX_PLAINTEXT}",
            )
        }
        val header = byteArrayOf(
            ((type ushr 8) and 0xff).toByte(),
            (type and 0xff).toByte(),
            ((payload.size ushr 8) and 0xff).toByte(),
            (payload.size and 0xff).toByte(),
        )
        return t.encrypt(header + payload)
    }

    fun decryptPacket(encrypted: ByteArray): Pair<Int, ByteArray> {
        val t = transport ?: error("handshake is not finished")
        return decodeEspHomeNoisePacket(t.decrypt(encrypted))
    }
}

internal fun decodeEspHomeNoisePacket(plain: ByteArray): Pair<Int, ByteArray> {
    if (plain.size < 4) throw IllegalArgumentException("Decrypted message too short")
    val type = ((plain[0].toInt() and 0xff) shl 8) or (plain[1].toInt() and 0xff)
    val dataLen = ((plain[2].toInt() and 0xff) shl 8) or (plain[3].toInt() and 0xff)
    if (plain.size < 4 + dataLen) {
        throw IllegalArgumentException(
            "Decrypted message truncated: have ${plain.size - 4}, declared $dataLen",
        )
    }
    return type to plain.copyOfRange(4, 4 + dataLen)
}
