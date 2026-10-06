package com.example.ava.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

private const val TAG = "FaceDetector"
private const val MODEL_FILE = "models/face.tflite"
private const val INPUT_SIZE = 128
private const val CONFIDENCE_THRESHOLD = 0.5f

data class FaceDetection(
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val gender: Gender = Gender.UNKNOWN
)

data class FaceDetectionResult(
    val faces: List<FaceDetection>,
    val faceCount: Int,
    val hasFace: Boolean,
    val inferenceTimeMs: Long,
    val maleCount: Int = 0,
    val femaleCount: Int = 0
)

private var faceInterpreter: Interpreter? = null
private var isFaceInitialized = false
private var anchors: List<FloatArray> = emptyList()

fun initFaceDetector(context: Context): Boolean {
    if (isFaceInitialized) return true
    return try {
        val model = loadFaceModelFile(context)
        faceInterpreter = Interpreter(model)
        anchors = generateAnchors()
        isFaceInitialized = true
        Log.d(TAG, "face detector initialized, anchors=${anchors.size}")
        true
    } catch (e: Exception) {
        Log.e(TAG, "init failed: ${e.message}")
        false
    }
}

fun closeFaceDetector() {
    faceInterpreter?.close()
    faceInterpreter = null
    isFaceInitialized = false
    anchors = emptyList()
}

private fun generateAnchors(): List<FloatArray> {
    val result = mutableListOf<FloatArray>()
    val strides = intArrayOf(8, 16)
    val anchorCounts = intArrayOf(2, 6)
    
    for ((idx, stride) in strides.withIndex()) {
        val gridSize = INPUT_SIZE / stride
        val numAnchors = anchorCounts[idx]
        for (y in 0 until gridSize) {
            for (x in 0 until gridSize) {
                val cx = (x + 0.5f) * stride
                val cy = (y + 0.5f) * stride
                for (n in 0 until numAnchors) {
                    result.add(floatArrayOf(cx, cy))
                }
            }
        }
    }
    return result
}

fun detectFaces(bitmap: Bitmap): FaceDetectionResult? {
    val interp = faceInterpreter ?: return null
    if (anchors.isEmpty()) return null
    val startTime = System.currentTimeMillis()
    
    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
    val inputBuffer = faceToByteBuffer(scaledBitmap)
    if (scaledBitmap != bitmap) scaledBitmap.recycle()
    
    val outputRegressors = Array(1) { Array(896) { FloatArray(16) } }
    val outputClassifiers = Array(1) { Array(896) { FloatArray(1) } }
    
    val outputs = mapOf(
        0 to outputRegressors,
        1 to outputClassifiers
    )
    
    interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
    
    val faces = mutableListOf<FaceDetection>()
    
    for (i in 0 until minOf(896, anchors.size)) {
        val score = 1f / (1f + kotlin.math.exp(-outputClassifiers[0][i][0]))
        if (score < CONFIDENCE_THRESHOLD) continue
        
        val anchor = anchors[i]
        val box = outputRegressors[0][i]
        
        val cx = (anchor[0] + box[0]) / INPUT_SIZE
        val cy = (anchor[1] + box[1]) / INPUT_SIZE
        val w = box[2] / INPUT_SIZE
        val h = box[3] / INPUT_SIZE
        
        val left = (cx - w / 2).coerceIn(0f, 1f)
        val top = (cy - h / 2).coerceIn(0f, 1f)
        val right = (cx + w / 2).coerceIn(0f, 1f)
        val bottom = (cy + h / 2).coerceIn(0f, 1f)
        
        if (right > left && bottom > top && w > 0.02f && h > 0.02f) {
            faces.add(FaceDetection(score, left, top, right, bottom))
        }
    }
    
    val nmsResult = nonMaxSuppression(faces, 0.3f)
    val inferenceTime = System.currentTimeMillis() - startTime
    
    
    return FaceDetectionResult(
        faces = nmsResult,
        faceCount = nmsResult.size,
        hasFace = nmsResult.isNotEmpty(),
        inferenceTimeMs = inferenceTime
    )
}

fun detectFacesAndDraw(jpegData: ByteArray, drawBox: Boolean = true, detectGenderEnabled: Boolean = false): Pair<ByteArray, FaceDetectionResult>? {
    if (!isFaceInitialized) return null
    return try {
        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
        var result = detectFaces(bitmap) ?: return null
        
        if (detectGenderEnabled && result.faces.isNotEmpty()) {
            val facesWithGender = result.faces.map { face ->
                val gender = detectGenderFromFace(bitmap, face)
                face.copy(gender = gender)
            }
            val maleCount = facesWithGender.count { it.gender == Gender.MALE }
            val femaleCount = facesWithGender.count { it.gender == Gender.FEMALE }
            result = result.copy(faces = facesWithGender, maleCount = maleCount, femaleCount = femaleCount)
        }
        
        if (drawBox && result.faces.isNotEmpty()) {
            val annotated = drawFaceBoxes(bitmap, result.faces)
            bitmap.recycle()
            val output = ByteArrayOutputStream()
            annotated.compress(Bitmap.CompressFormat.JPEG, 80, output)
            annotated.recycle()
            Pair(output.toByteArray(), result)
        } else {
            Pair(jpegData, result)
        }
    } catch (e: Exception) {
        Log.e(TAG, "detectFacesAndDraw failed: ${e.message}")
        null
    }
}

private fun drawFaceBoxes(bitmap: Bitmap, faces: List<FaceDetection>): Bitmap {
    val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(mutableBitmap)
    val width = mutableBitmap.width.toFloat()
    val height = mutableBitmap.height.toFloat()
    
    val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.RED
        isAntiAlias = true
    }
    
    val textPaint = Paint().apply {
        textSize = 28f
        color = Color.WHITE
        isAntiAlias = true
        isFakeBoldText = true
    }
    
    val bgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.RED
    }
    
    for (face in faces) {
        val genderColor = when (face.gender) {
            Gender.MALE -> Color.BLUE
            Gender.FEMALE -> Color.MAGENTA
            Gender.UNKNOWN -> Color.RED
        }
        boxPaint.color = genderColor
        bgPaint.color = genderColor
        
        val rect = RectF(
            face.left * width,
            face.top * height,
            face.right * width,
            face.bottom * height
        )
        canvas.drawRect(rect, boxPaint)
        
        val genderLabel = when (face.gender) {
            Gender.MALE -> "♂"
            Gender.FEMALE -> "♀"
            Gender.UNKNOWN -> ""
        }
        val label = "$genderLabel ${(face.confidence * 100).toInt()}%"
        val textWidth = textPaint.measureText(label)
        canvas.drawRect(rect.left, rect.top - 32f, rect.left + textWidth + 8, rect.top, bgPaint)
        canvas.drawText(label, rect.left + 4, rect.top - 8, textPaint)
    }
    
    return mutableBitmap
}

private fun nonMaxSuppression(detections: List<FaceDetection>, iouThreshold: Float): List<FaceDetection> {
    if (detections.isEmpty()) return emptyList()
    
    val sorted = detections.sortedByDescending { it.confidence }
    val result = mutableListOf<FaceDetection>()
    val suppressed = BooleanArray(sorted.size)
    
    for (i in sorted.indices) {
        if (suppressed[i]) continue
        result.add(sorted[i])
        
        for (j in i + 1 until sorted.size) {
            if (suppressed[j]) continue
            if (iou(sorted[i], sorted[j]) > iouThreshold) {
                suppressed[j] = true
            }
        }
    }
    return result
}

private fun iou(a: FaceDetection, b: FaceDetection): Float {
    val interLeft = maxOf(a.left, b.left)
    val interTop = maxOf(a.top, b.top)
    val interRight = minOf(a.right, b.right)
    val interBottom = minOf(a.bottom, b.bottom)
    
    if (interRight <= interLeft || interBottom <= interTop) return 0f
    
    val interArea = (interRight - interLeft) * (interBottom - interTop)
    val aArea = (a.right - a.left) * (a.bottom - a.top)
    val bArea = (b.right - b.left) * (b.bottom - b.top)
    
    return interArea / (aArea + bArea - interArea)
}

private fun loadFaceModelFile(context: Context): MappedByteBuffer {
    context.assets.openFd(MODEL_FILE).use { fd ->
        FileInputStream(fd.fileDescriptor).use { input ->
            val channel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
}

private fun faceToByteBuffer(bitmap: Bitmap): ByteBuffer {
    val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
    buffer.order(ByteOrder.nativeOrder())
    
    val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
    
    for (pixel in pixels) {
        buffer.putFloat(((pixel shr 16) and 0xFF) / 127.5f - 1f)
        buffer.putFloat(((pixel shr 8) and 0xFF) / 127.5f - 1f)
        buffer.putFloat((pixel and 0xFF) / 127.5f - 1f)
    }
    
    buffer.rewind()
    return buffer
}
