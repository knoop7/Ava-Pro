package com.example.microfeatures

import java.nio.ByteBuffer
import java.nio.ByteOrder

class NoiseSuppressor(
    private val sampleRate: Int = 16000,
    private val mode: Int = MODE_MODERATE
) : AutoCloseable {
    
    private var nativeHandle: Long = 0
    private var isInitialized = false

    /** True when native WebRTC NS created and initialized successfully. */
    val isReady: Boolean get() = isInitialized
    
    private external fun nativeCreate(): Long
    private external fun nativeInit(handle: Long, sampleRate: Int): Int
    private external fun nativeSetPolicy(handle: Long, mode: Int): Int
    private external fun nativeProcess(handle: Long, input: ShortArray, output: ShortArray)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeGetSpeechProbability(handle: Long): Float
    
    init {
        nativeHandle = nativeCreate()
        if (nativeHandle != 0L) {
            val initResult = nativeInit(nativeHandle, sampleRate)
            if (initResult == 0) {
                nativeSetPolicy(nativeHandle, mode)
                isInitialized = true
            }
        }
    }
    
    fun process(input: ShortArray, output: ShortArray) {
        if (!isInitialized || nativeHandle == 0L) return
        if (input.size != FRAME_SIZE || output.size != FRAME_SIZE) {
            throw IllegalArgumentException("Input and output must be $FRAME_SIZE samples (10ms at 16kHz)")
        }
        nativeProcess(nativeHandle, input, output)
    }
    
    fun process(inputBuffer: ByteBuffer): ByteBuffer {
        if (!isInitialized || nativeHandle == 0L) return inputBuffer
        
        val inputShorts = ShortArray(FRAME_SIZE)
        val outputShorts = ShortArray(FRAME_SIZE)
        val outputBuffer = ByteBuffer.allocateDirect(FRAME_SIZE * 2).order(ByteOrder.LITTLE_ENDIAN)
        
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.asShortBuffer().get(inputShorts)
        
        nativeProcess(nativeHandle, inputShorts, outputShorts)
        
        outputBuffer.asShortBuffer().put(outputShorts)
        outputBuffer.rewind()
        return outputBuffer
    }
    
    fun getSpeechProbability(): Float {
        if (!isInitialized || nativeHandle == 0L) return 0f
        return nativeGetSpeechProbability(nativeHandle)
    }
    
    fun setMode(mode: Int) {
        if (!isInitialized || nativeHandle == 0L) return
        nativeSetPolicy(nativeHandle, mode)
    }
    
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
            isInitialized = false
        }
    }
    
    protected fun finalize() {
        close()
    }
    
    companion object {
        const val MODE_MILD = 0
        const val MODE_MODERATE = 1
        const val MODE_AGGRESSIVE = 2
        
        const val FRAME_SIZE = 160
        
        init {
            System.loadLibrary("microfeatures")
        }
    }
}
