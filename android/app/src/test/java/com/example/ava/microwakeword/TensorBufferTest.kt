package com.example.ava.microwakeword

import org.junit.Assert.assertEquals
import org.junit.Test
import org.tensorflow.lite.DataType

class TensorBufferTest {
    @Test
    fun signedInputSaturatesInsteadOfWrapping() {
        val buffer = TensorBuffer.create(DataType.INT8, intArrayOf(5), 0.1f, -128)
        buffer.put(floatArrayOf(-1f, 0f, 12.8f, 25.5f, 40f))
        val input = buffer.getTensor()
        assertEquals(listOf(-128, -128, 0, 127, 127), List(5) { input.get().toInt() })
    }

    @Test
    fun unsignedInputSaturatesInsteadOfWrapping() {
        val buffer = TensorBuffer.create(DataType.UINT8, intArrayOf(4), 0.5f, 0)
        buffer.put(floatArrayOf(-10f, 0f, 64f, 200f))
        val input = buffer.getTensor()
        assertEquals(listOf(0, 0, 128, 255), List(4) { input.get().toInt() and 255 })
    }

    @Test
    fun floatInputIsNotQuantized() {
        val buffer = TensorBuffer.create(DataType.FLOAT32, intArrayOf(3), 0f, 0)
        buffer.put(floatArrayOf(0f, 12.5f, 26f))
        val input = buffer.getTensor()
        assertEquals(0f, input.float, 0f)
        assertEquals(12.5f, input.float, 0f)
        assertEquals(26f, input.float, 0f)
    }

    @Test
    fun signedOutputDoesNotTurnLowScoresIntoProbabilitiesAboveOne() {
        val output = ProbabilityTensorBuffer(DataType.INT8, 1f / 256f, -128)
        output.buffer.put(0, (-128).toByte())
        assertEquals(0f, output.probability(), 0f)
        output.buffer.put(0, (-64).toByte())
        assertEquals(0.25f, output.probability(), 0f)
        output.buffer.put(0, 127)
        assertEquals(255f / 256f, output.probability(), 0f)
    }

    @Test
    fun unsignedOutputKeepsBundledModelProbabilities() {
        val output = ProbabilityTensorBuffer(DataType.UINT8, 1f / 256f, 0)
        output.buffer.put(0, 255.toByte())
        assertEquals(255f / 256f, output.probability(), 0f)
    }

    @Test
    fun floatOutputRejectsNonFiniteScores() {
        val output = ProbabilityTensorBuffer(DataType.FLOAT32, 0f, 0)
        output.buffer.putFloat(0, 0.75f)
        assertEquals(0.75f, output.probability(), 0f)
        output.buffer.putFloat(0, Float.NaN)
        assertEquals(0f, output.probability(), 0f)
        output.buffer.putFloat(0, Float.POSITIVE_INFINITY)
        assertEquals(0f, output.probability(), 0f)
    }
}
