package com.example.ava.utils

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes PCM16 mono clips as WAV so a media player can play them back from a file URI. */
object PcmWavFile {
    fun write(target: File, pcm16Mono: ShortArray, sampleRateHz: Int): Boolean {
        if (pcm16Mono.isEmpty() || sampleRateHz <= 0) return false
        val dataBytes = pcm16Mono.size * 2
        return try {
            FileOutputStream(target).use { out ->
                out.write(header(dataBytes, sampleRateHz))
                val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                pcm16Mono.forEach { body.putShort(it) }
                out.write(body.array())
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "write failed ${target.name}: ${e.message}")
            try {
                target.delete()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun header(dataBytes: Int, sampleRateHz: Int): ByteArray {
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(HEADER_BYTES - 8 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRateHz)
        header.putInt(sampleRateHz * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        return header.array()
    }

    private const val HEADER_BYTES = 44
    private const val TAG = "PcmWavFile"
}
