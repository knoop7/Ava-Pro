package com.example.ava.sendspin

import android.util.Log

class SendspinOpusDecoder(
    private val sampleRate: Int,
    private val channels: Int
) {
    private val tag = "OpusDecoder"
    private var lastDecodeErrorLogMs = 0L

    private val lock = Any()
    private var nativeHandle: Long = if (nativeLoaded) nativeCreate(sampleRate, channels) else 0L

    @Volatile
    private var released = false

    init {
        if (nativeHandle != 0L) {
            Log.i(tag, "Using official libopus native decoder ($sampleRate Hz, $channels ch)")
        } else {
            Log.e(tag, "Failed to create official libopus native decoder ($sampleRate Hz, $channels ch)")
        }
    }

    fun decode(opusData: ByteArray): ByteArray = decode(opusData, 0, opusData.size)

    fun decode(opusData: ByteArray, offset: Int, length: Int): ByteArray {
        if (length <= 0 || offset < 0 || offset + length > opusData.size) return EMPTY
        val handle = synchronized(lock) {
            if (released) return EMPTY
            nativeHandle
        }
        if (handle == 0L) return EMPTY

        val decoded = try {
            nativeDecode(handle, opusData, offset, length)
        } catch (e: UnsatisfiedLinkError) {
            logDecodeError("Native libopus unavailable: ${e.message}")
            EMPTY
        } catch (e: Exception) {
            logDecodeError("Native libopus decode exception: ${e.message}")
            EMPTY
        }
        if (decoded.isEmpty()) {
            logDecodeError("Native libopus decode returned empty output")
        }
        return decoded
    }

    fun reset() {
        val handle = synchronized(lock) {
            if (released) return
            nativeHandle
        }
        if (handle != 0L) runCatching { nativeReset(handle) }
    }

    fun release() {
        val handle = synchronized(lock) {
            if (released) return
            released = true
            val handle = nativeHandle
            nativeHandle = 0L
            handle
        }
        if (handle != 0L) runCatching { nativeDestroy(handle) }
    }

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeDecode(handle: Long, opusData: ByteArray, offset: Int, length: Int): ByteArray
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)

    private fun logDecodeError(message: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - lastDecodeErrorLogMs < 2_000L) return
            lastDecodeErrorLogMs = now
        }
        Log.w(tag, message)
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private val nativeLoaded = runCatching {
            System.loadLibrary("sendspinopus")
        }.isSuccess
    }
}
