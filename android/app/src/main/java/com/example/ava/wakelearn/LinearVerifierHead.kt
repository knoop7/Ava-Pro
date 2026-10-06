package com.example.ava.wakelearn

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * A logistic verifier head `sigmoid(w·x + b)` over a wake engine's classifier input,
 * consulted only at the instant the engine would fire; below the veto threshold the fire
 * is rejected. One format for every producer — the factory calibration exported by
 * `tools/oww-verifier`, and the heads on-device learning writes from the household's own
 * confirmed and rejected wakes — and every consumer (native openWakeWord engine via
 * `Engine::parse_verifier`, micro detector in Kotlin).
 *
 * Container (little-endian): `"OWWLIN1\0"`, int32 dims, float32 bias, float32[dims].
 */
class LinearVerifierHead(val weights: FloatArray, val bias: Float) {
    val dims: Int get() = weights.size

    fun logit(x: FloatArray): Float {
        require(x.size == weights.size) { "verifier expects ${weights.size} dims, got ${x.size}" }
        var z = bias.toDouble()
        for (i in weights.indices) z += weights[i].toDouble() * x[i]
        return z.toFloat()
    }

    fun score(x: FloatArray): Float = (1.0 / (1.0 + exp(-logit(x).toDouble()))).toFloat()

    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_BYTES + weights.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putInt(weights.size)
        buf.putFloat(bias)
        for (w in weights) buf.putFloat(w)
        return buf.array()
    }

    companion object {
        private val MAGIC = byteArrayOf('O'.code.toByte(), 'W'.code.toByte(), 'W'.code.toByte(),
            'L'.code.toByte(), 'I'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte(), 0)
        private const val HEADER_BYTES = 8 + 4 + 4
        private const val MAX_DIMS = 1 shl 20

        /** Null on anything but a well-formed container whose dims match [expectedDims] (if given). */
        fun parse(bytes: ByteArray?, expectedDims: Int? = null): LinearVerifierHead? {
            if (bytes == null || bytes.size < HEADER_BYTES) return null
            for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(MAGIC.size)
            val dims = buf.getInt()
            if (dims <= 0 || dims > MAX_DIMS) return null
            if (bytes.size != HEADER_BYTES + dims * 4) return null
            if (expectedDims != null && dims != expectedDims) return null
            val bias = buf.getFloat()
            val weights = FloatArray(dims) { buf.getFloat() }
            return LinearVerifierHead(weights, bias)
        }
    }
}
