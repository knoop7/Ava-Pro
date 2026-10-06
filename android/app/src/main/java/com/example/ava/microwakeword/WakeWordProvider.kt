package com.example.ava.microwakeword

import java.nio.ByteBuffer

data class WakeWordWithId(
    val id: String,
    val wakeWord: WakeWord,
    val hasBuiltInVerifier: Boolean = false,
)

interface WakeWordProvider {

    fun getWakeWords(): List<WakeWordWithId>

    fun loadWakeWordModel(model: String): ByteBuffer
}
