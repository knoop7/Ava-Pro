package com.example.ava.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp

private const val TAG = "GenderDetector"
private const val MODEL_FILE = "models/gender.tflite"

enum class Gender {
    MALE, FEMALE, UNKNOWN
}

private var genderInterpreter: Interpreter? = null
private var isGenderInitialized = false
private var genderInputSize = 64

fun initGenderDetector(context: Context): Boolean {
    if (isGenderInitialized) return true
    return try {
        val model = loadGenderModelFile(context)
        genderInterpreter = Interpreter(model)
        
        val inputShape = genderInterpreter!!.getInputTensor(0).shape()
        genderInputSize = inputShape[1]
        Log.d(TAG, "gender model input: ${inputShape.contentToString()}")
        
        isGenderInitialized = true
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to init gender detector: ${e.message}")
        false
    }
}

fun closeGenderDetector() {
    genderInterpreter?.close()
    genderInterpreter = null
    isGenderInitialized = false
}

fun detectGender(faceBitmap: Bitmap): Gender {
    if (!isGenderInitialized) return Gender.UNKNOWN
    
    return try {
        val resized = Bitmap.createScaledBitmap(faceBitmap, genderInputSize, genderInputSize, true)
        val inputBuffer = convertBitmapToByteBuffer(resized)
        if (resized != faceBitmap) resized.recycle()
        
        val output = Array(1) { FloatArray(2) }
        genderInterpreter?.run(inputBuffer, output)
        
        val maleLogit = output[0][0]
        val femaleLogit = output[0][1]
        val maleExp = exp(maleLogit)
        val femaleExp = exp(femaleLogit)
        val sum = (maleExp + femaleExp).coerceAtLeast(1e-6f)
        val maleProb = maleExp / sum
        val femaleProb = femaleExp / sum
        val confidence = maxOf(maleProb, femaleProb)
        val margin = abs(maleProb - femaleProb)

        if (confidence < MIN_CONFIDENCE || margin < MIN_MARGIN) {
            Gender.UNKNOWN
        } else if (femaleProb > maleProb) {
            Gender.FEMALE
        } else {
            Gender.MALE
        }
    } catch (e: Exception) {
        Log.e(TAG, "Gender detection failed: ${e.message}")
        Gender.UNKNOWN
    }
}

fun detectGenderFromFace(originalBitmap: Bitmap, face: FaceDetection): Gender {
    if (!isGenderInitialized) return Gender.UNKNOWN
    
    return try {
        val width = originalBitmap.width
        val height = originalBitmap.height
        
        val left = (face.left * width).toInt().coerceIn(0, width - 1)
        val top = (face.top * height).toInt().coerceIn(0, height - 1)
        val right = (face.right * width).toInt().coerceIn(left + 1, width)
        val bottom = (face.bottom * height).toInt().coerceIn(top + 1, height)
        
        val faceWidth = right - left
        val faceHeight = bottom - top
        
        if (faceWidth < 10 || faceHeight < 10) return Gender.UNKNOWN
        
        val faceBitmap = Bitmap.createBitmap(originalBitmap, left, top, faceWidth, faceHeight)
        val result = detectGender(faceBitmap)
        faceBitmap.recycle()
        result
    } catch (e: Exception) {
        Log.e(TAG, "detectGenderFromFace failed: ${e.message}")
        Gender.UNKNOWN
    }
}

private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
    val byteBuffer = ByteBuffer.allocateDirect(4 * genderInputSize * genderInputSize * 3)
    byteBuffer.order(ByteOrder.nativeOrder())
    
    val pixels = IntArray(genderInputSize * genderInputSize)
    bitmap.getPixels(pixels, 0, genderInputSize, 0, 0, genderInputSize, genderInputSize)
    
    for (pixel in pixels) {
        val r = ((pixel shr 16) and 0xFF) / 255.0f
        val g = ((pixel shr 8) and 0xFF) / 255.0f
        val b = (pixel and 0xFF) / 255.0f
        byteBuffer.putFloat(r)
        byteBuffer.putFloat(g)
        byteBuffer.putFloat(b)
    }
    
    return byteBuffer
}

private fun loadGenderModelFile(context: Context): MappedByteBuffer {
    context.assets.openFd(MODEL_FILE).use { assetFileDescriptor ->
        FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }
}

private const val MIN_CONFIDENCE = 0.65f
private const val MIN_MARGIN = 0.20f
