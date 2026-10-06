package com.example.microfeatures

import java.nio.ByteBuffer

data class ProcessOutput(val features: FloatArray, val samplesRead: Int)

class MicroFrontend : AutoCloseable {
    private external fun newNativeFrontend(): Long
    private external fun deleteNativeFrontend(nativeFrontend: Long)
    private external fun processSamples(nativeFrontend: Long, audio: ByteBuffer): ProcessOutput
    private external fun resetNativeFrontend(nativeFrontend: Long)

    private var nativeFrontend = newNativeFrontend()

    fun processSamples(audio: ByteBuffer): ProcessOutput {
        return processSamples(nativeFrontend, audio)
    }

    /** Clears frontend ring/noise-reduction state so post-TTS silence cannot poison the next wake. */
    fun reset() {
        if (nativeFrontend != -1L) {
            resetNativeFrontend(nativeFrontend)
        }
    }

    private fun delete() {
        if (nativeFrontend != -1L) {
            deleteNativeFrontend(nativeFrontend)
            nativeFrontend = -1L
        }
    }

    override fun close() {
        delete()
    }

    protected fun finalize() {
        delete()
    }

    companion object {
        external fun initJni(processOutputClass: Class<ProcessOutput>)

        init {
            System.loadLibrary("microfeatures")
            initJni(ProcessOutput::class.java)
        }
    }
}


