package com.example.ava.microwakeword

import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

abstract class TensorBuffer(
    dataType: DataType,
    shape: IntArray,
    val scale: Float,
    val zeroPoint: Int
) {
    private val _flatSize: Int = shape.reduce { acc, i -> acc * i }
    protected val buffer: ByteBuffer =
        ByteBuffer.allocateDirect(_flatSize * dataType.byteSize()).order(ByteOrder.nativeOrder())

    val flatSize get() = _flatSize
    val isComplete get() = !buffer.hasRemaining()

    abstract fun put(src: FloatArray)

    fun getTensor(): ByteBuffer {
        val tensor = buffer.duplicate()
            .order(ByteOrder.nativeOrder())
            .apply { flip() }
        return tensor
    }

    fun clear() {
        buffer.clear()
    }

    fun quantize(value: Float): Float {
        return (value / scale) + zeroPoint
    }

    companion object {
        fun create(
            dataType: DataType,
            shape: IntArray,
            scale: Float,
            zeroPoint: Int
        ): TensorBuffer {
            return when (dataType) {
                DataType.FLOAT32 -> TensorBufferFloat(shape, scale, zeroPoint)
                DataType.UINT8, DataType.INT8 -> TensorBufferUint8(shape, scale, zeroPoint, dataType)
                else -> throw IllegalArgumentException("Unsupported data type: $dataType")
            }
        }
    }
}

class TensorBufferUint8(
    shape: IntArray,
    scale: Float,
    zeroPoint: Int,
    dataType: DataType = DataType.UINT8,
) : TensorBuffer(dataType, shape, scale, zeroPoint) {
    private val minimum = if (dataType == DataType.INT8) -128 else 0
    private val maximum = if (dataType == DataType.INT8) 127 else 255

    init {
        require(scale.isFinite() && scale > 0f) { "Quantized tensor scale must be positive" }
        require(zeroPoint in minimum..maximum) { "Zero point outside tensor range" }
    }

    override fun put(src: FloatArray) {
        for (value in src) {
            buffer.put(quantize(value).roundToInt().coerceIn(minimum, maximum).toByte())
        }
    }
}

class TensorBufferFloat(shape: IntArray, scale: Float, zeroPoint: Int) :
    TensorBuffer(DataType.FLOAT32, shape, scale, zeroPoint) {
    override fun put(src: FloatArray) {
        for (value in src) {
            buffer.putFloat(value)
        }
    }
}

/** Scalar model output, decoded with the tensor's actual storage type. */
internal class ProbabilityTensorBuffer(
    private val dataType: DataType,
    private val scale: Float,
    private val zeroPoint: Int,
) {
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(dataType.byteSize()).order(ByteOrder.nativeOrder())

    init {
        require(dataType == DataType.FLOAT32 || dataType == DataType.INT8 || dataType == DataType.UINT8)
        require(dataType == DataType.FLOAT32 || (scale.isFinite() && scale > 0f))
    }

    fun probability(): Float {
        val value = when (dataType) {
            DataType.FLOAT32 -> buffer.getFloat(0)
            DataType.INT8 -> (buffer.get(0).toInt() - zeroPoint) * scale
            else -> ((buffer.get(0).toInt() and 255) - zeroPoint) * scale
        }
        return if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    }
}
