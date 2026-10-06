package com.example.ava.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Chunked JPEG frame on [AvaVoiceProtocol.VIDEO_PORT] (AVA2 magic). */
internal object AvaVoiceVideoPacket {
    const val HEADER_SIZE = 16
    const val MAX_CHUNK_PAYLOAD = 1200

    private val MAGIC = byteArrayOf(0x41, 0x56, 0x41, 0x32) // AVA2

    data class Decoded(
        val sessionId: Int,
        val frameSequence: Int,
        val chunkIndex: Int,
        val chunkCount: Int,
        val payload: ByteArray
    )

    fun encode(
        sessionId: Int,
        frameSequence: Int,
        chunkIndex: Int,
        chunkCount: Int,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= payload.size) {
            "Invalid payload range offset=$offset length=$length size=${payload.size}"
        }
        require(length <= MAX_CHUNK_PAYLOAD) { "Video chunk too large: $length" }
        val packet = ByteArray(HEADER_SIZE + length)
        val header = ByteBuffer.wrap(packet, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(MAGIC)
        header.putInt(sessionId)
        header.putShort(frameSequence.toShort())
        header.putShort(chunkIndex.toShort())
        header.putShort(chunkCount.toShort())
        header.putShort(length.toShort())
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
        val frameSequence = header.short.toInt() and 0xFFFF
        val chunkIndex = header.short.toInt() and 0xFFFF
        val chunkCount = header.short.toInt() and 0xFFFF
        val payloadLen = header.short.toInt() and 0xFFFF
        if (payloadLen < 0 || HEADER_SIZE + payloadLen > length) return null
        if (chunkCount <= 0 || chunkIndex >= chunkCount) return null
        val payload = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen)
        return Decoded(sessionId, frameSequence, chunkIndex, chunkCount, payload)
    }
}
