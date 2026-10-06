package com.example.ava.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.min


class CameraCapture(private val context: Context) {
    
    companion object {
        private const val TAG = "CameraCapture"
        private const val JPEG_QUALITY = 80
        private const val LEGACY_HAL_BIND_DELAY_MS = 300L
    }
    
    private val executor = Executors.newSingleThreadExecutor()
    
    
    suspend fun capturePhoto(
        useFrontCamera: Boolean = false,
        targetSize: Int = 500,
        forceOrientation: String = "AUTO"
    ): ByteArray? = suspendCancellableCoroutine { continuation ->
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    
                    
                    val lifecycleOwner = TempLifecycleOwner()
                    lifecycleOwner.start()
                    
                    
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(JPEG_QUALITY)
                        .setTargetRotation(context.displayRotation())
                        .build()
                    
                    
                    val lens = CameraLens.select(cameraProvider, useFrontCamera)
                    
                    cameraProvider.unbindAll()

                    fun bindAndCapture() {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            lens.selector,
                            imageCapture
                        )


                        imageCapture.targetRotation = context.displayRotation()

                        imageCapture.takePicture(
                        executor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                try {
                                    val jpegData = imageProxyToJpeg(image, targetSize, forceOrientation)
                                    image.close()


                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        cameraProvider.unbindAll()
                                        lifecycleOwner.stop()
                                    }

                                    if (continuation.isActive) {
                                        continuation.resume(jpegData)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing captured image", e)
                                    image.close()
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        cameraProvider.unbindAll()
                                        lifecycleOwner.stop()
                                    }
                                    if (continuation.isActive) {
                                        continuation.resume(null)
                                    }
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Log.e(TAG, "Image capture failed", exception)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    cameraProvider.unbindAll()
                                    lifecycleOwner.stop()
                                }
                                if (continuation.isActive) {
                                    continuation.resume(null)
                                }
                            }
                        }
                        )
                    }

                    // Legacy HAL needs a gap between unbind and re-bind. This listener runs
                    // on the main executor, so post the gap rather than sleeping in it.
                    if (CameraLens.isLegacyHal(context, lens.useFrontCamera)) {
                        Log.d(TAG, "Legacy HAL detected, deferring camera bind")
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                if (!continuation.isActive) {
                                    lifecycleOwner.stop()
                                    return@postDelayed
                                }
                                bindAndCapture()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to bind camera after HAL delay", e)
                                runCatching { cameraProvider.unbindAll() }
                                runCatching { lifecycleOwner.stop() }
                                if (continuation.isActive) {
                                    continuation.resume(null)
                                }
                            }
                        }, LEGACY_HAL_BIND_DELAY_MS)
                    } else {
                        bindAndCapture()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind camera", e)
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }, ContextCompat.getMainExecutor(context))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera provider", e)
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }
        
        continuation.invokeOnCancellation {
            Log.d(TAG, "Photo capture cancelled")
        }
    }
    
    
    /**
     * Decodes at the smallest power-of-two scale that still covers [targetSize].
     *
     * The full-resolution decode this replaces allocated the whole sensor image as
     * ARGB_8888 (~48 MB on a 12 MP camera) and then a second copy to rotate it, only
     * to hand back a [targetSize] square — enough to OOM a low-memory panel.
     * [targetSize] 0 means "no resize", so that path still decodes full size.
     */
    private fun decodeSampled(bytes: ByteArray, targetSize: Int): Bitmap? {
        if (targetSize <= 0) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val shortEdge = min(bounds.outWidth, bounds.outHeight)
        if (shortEdge <= 0) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        // Only the short edge has to survive: the image is center-cropped to a
        // shortEdge square before being scaled down to targetSize.
        var sampleSize = 1
        while (shortEdge / (sampleSize * 2) >= targetSize) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun imageProxyToJpeg(image: ImageProxy, targetSize: Int, forceOrientation: String = "AUTO"): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        
        var bitmap = decodeSampled(bytes, targetSize)
            ?: throw IllegalStateException("JPEG decode returned null (${bytes.size} bytes)")
        
        // Calculate rotation based on forced orientation or auto-detect
        val rotationDegrees = when (forceOrientation) {
            "PORTRAIT" -> 0
            "PORTRAIT_FLIP" -> 180
            "LANDSCAPE" -> 90
            "LANDSCAPE_FLIP" -> 270
            else -> image.imageInfo.rotationDegrees
        }
        
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            bitmap = rotatedBitmap
        }
        
        val finalBitmap: Bitmap
        if (targetSize == 0) {
            
            finalBitmap = bitmap
        } else {
            
            val size = min(bitmap.width, bitmap.height)
            val x = (bitmap.width - size) / 2
            val y = (bitmap.height - size) / 2
            val squareBitmap = Bitmap.createBitmap(bitmap, x, y, size, size)
            if (squareBitmap != bitmap) {
                bitmap.recycle()
            }
            
            
            finalBitmap = Bitmap.createScaledBitmap(squareBitmap, targetSize, targetSize, true)
            if (finalBitmap != squareBitmap) {
                squareBitmap.recycle()
            }
        }
        
        
        val outputStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        finalBitmap.recycle()
        
        return outputStream.toByteArray()
    }
    
    fun close() {
        executor.shutdown()
    }
}
