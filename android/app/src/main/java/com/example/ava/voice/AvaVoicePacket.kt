package com.example.ava.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RTP-inspired binary audio frame on [AvaVoiceProtocol.AUDIO_PORT]. */
internal object AvaVoicePacket {
    const val HEADER_SIZE = 14
    const val FLAG_DATA = 1

    // Payload codec, carried in the (formerly reserved) header byte at offset 13.
    const val FORMAT_PCM = 0
    const val FORMAT_OPUS = 1

    private val MAGIC = byteArrayOf(0x41, 0x56, 0x41, 0x31) // AVA1

    data class Decoded(
        val sessionId: Int,
        val sequence: Int,
        val flags: Int,
        val format: Int,
        val payload: ByteArray
    )

    fun encode(
        sessionId: Int,
        sequence: Int,
        flags: Int,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size,
        format: Int = FORMAT_PCM
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= payload.size) {
            "Invalid payload range offset=$offset length=$length size=${payload.size}"
        }
        require(length <= UShort.MAX_VALUE.toInt()) {
            "Payload too large for Ava voice packet: $length"
        }
        val packet = ByteArray(HEADER_SIZE + length)
        val header = ByteBuffer.wrap(packet, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(MAGIC)
        header.putInt(sessionId)
        header.putShort(sequence.toShort())
        header.put(flags.toByte())
        header.putShort(length.toShort())
        header.put(format.toByte())
        System.arraycopy(payload, offset, packet, HEADER_SIZE, length)
        return packet
    }

    fun decode(data: ByteArray, length: Int): Decoded? {
        if (length < HEADER_SIZE) return null
        if (data[0] != MAGIC[0] || data[1] != MAGIC[1] || data[2] != MAGIC[2] || data[3] != MAGIC[3]) {
            return null
        }
        val header = ByteBuffer.wrap(data, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.position(4)
        val sessionId = header.int
        val sequence = header.short.toInt() and 0xFFFF
        val flags = header.get().toInt() and 0xFF
        val payloadLen = header.short.toInt() and 0xFFFF
        val format = header.get().toInt() and 0xFF
        if (payloadLen < 0 || HEADER_SIZE + payloadLen > length) return null
        val payload = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen)
        return Decoded(sessionId, sequence, flags, format, payload)
    }
}
