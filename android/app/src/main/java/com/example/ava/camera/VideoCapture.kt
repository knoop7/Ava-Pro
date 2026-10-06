package com.example.ava.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.OrientationEventListener
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt


class VideoCapture(private val context: Context) {
    
    companion object {
        private const val TAG = "VideoCapture"
        private const val JPEG_QUALITY = 75
        private const val LEGACY_HAL_BIND_DELAY_MS = 300L
        private const val RETRY_DELAY_MS = 200L
        private const val MAX_RETRY_COUNT = 3
        private const val UNBIND_AWAIT_MS = 2_000L
        
        fun createPlaceholderFromAsset(context: Context, assetName: String = "camera_off.png", width: Int = 320, height: Int = 240): ByteArray {
            return try {
                val inputStream = context.assets.open(assetName)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                
                val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, width, height, true)
                if (scaledBitmap != originalBitmap) {
                    originalBitmap.recycle()
                }
                
                ByteArrayOutputStream().apply {
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, this)
                    scaledBitmap.recycle()
                }.toByteArray()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load asset $assetName", e)
                
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.BLACK)
                ByteArrayOutputStream().apply {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, this)
                    bitmap.recycle()
                }.toByteArray()
            }
        }
    }
    
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Bumped on every stop/close so delayed bind/retry runnables become no-ops. */
    private val bindGeneration = AtomicInteger(0)
    private var cameraProvider: ProcessCameraProvider? = null
    private var lifecycleOwner: TempLifecycleOwner? = null
    @Volatile private var isRecording = false
    @Volatile private var lastFrameTime = 0L
    private var onFrameCallback: ((ByteArray) -> Unit)? = null
    private var frameIntervalMs = 200L
    private var jpegQuality = JPEG_QUALITY
    private var imageAnalysis: ImageAnalysis? = null
    private var forcedOrientation: String = "AUTO"
    /** Final JPEG short edge (software scale target). */
    private var outputShortEdge = 480
    /** Max short edge requested from the camera HAL — never upscale at HAL level. */
    private var captureCapShortEdge = 480
    private var applyVoicePolish = false
    
    // Track device orientation for proper rotation
    @Volatile private var deviceRotation = Surface.ROTATION_0
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val newRotation = when {
                orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                orientation in 45 until 135 -> Surface.ROTATION_270
                orientation in 135 until 225 -> Surface.ROTATION_180
                else -> Surface.ROTATION_90
            }
            if (deviceRotation != newRotation) {
                deviceRotation = newRotation
                imageAnalysis?.targetRotation = newRotation
            }
        }
    }
    
    fun startRecording(
        useFrontCamera: Boolean = false,
        fps: Int = 5,
        resolution: Int = 480,
        forceOrientation: String = "AUTO",
        jpegQuality: Int = JPEG_QUALITY,
        captureCapShortEdge: Int? = null,
        applyVoicePolish: Boolean = false,
        onFrame: (ByteArray) -> Unit
    ) {
        forcedOrientation = forceOrientation
        this.jpegQuality = jpegQuality.coerceIn(40, 95)
        this.applyVoicePolish = applyVoicePolish
        outputShortEdge = resolution.coerceIn(120, 1080)
        if (isRecording) return
        
        val sessionGen = bindGeneration.incrementAndGet()
        isRecording = true
        onFrameCallback = onFrame
        frameIntervalMs = 1000L / fps.coerceIn(1, 15)
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            if (!isRecording || sessionGen != bindGeneration.get()) {
                Log.d(TAG, "Ignoring camera provider callback after stop (gen=$sessionGen)")
                return@addListener
            }
            runCatching {
                cameraProvider = cameraProviderFuture.get()
                lifecycleOwner = TempLifecycleOwner().apply { start() }

                val lens = CameraLens.select(cameraProvider!!, useFrontCamera)
                val isLegacyHal = CameraLens.isLegacyHal(context, lens.useFrontCamera)
                val capShortEdge = (captureCapShortEdge ?: when {
                    isLegacyHal -> minOf(outputShortEdge, 240)
                    else -> outputShortEdge
                }).coerceIn(120, outputShortEdge)
                this.captureCapShortEdge = capShortEdge

                deviceRotation = context.displayRotation()

                // Ask HAL for a small/stable size only; never pick a higher sensor mode.
                val captureSize = Size(capShortEdge * 4 / 3, capShortEdge)
                Log.d(
                    TAG,
                    "capture cap=${captureSize.width}x${captureSize.height} " +
                        "output short=$outputShortEdge legacy=$isLegacyHal"
                )
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(deviceRotation)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    captureSize,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                                )
                            )
                            .build()
                    )
                    .build()
                    .apply { setAnalyzer(executor, ::processFrame) }
                
                orientationListener.enable()
                
                cameraProvider?.unbindAll()
                
                // Legacy HAL workaround: add delay before binding to let camera fully release
                // This fixes CAMERA_ERROR_EVICTED on devices like Galaxy Tab S2 with legacy HAL
                val bindDelay = if (isLegacyHal) LEGACY_HAL_BIND_DELAY_MS else 0L
                
                if (bindDelay > 0) {
                    Log.d(TAG, "Legacy HAL detected, adding ${bindDelay}ms delay before camera bind")
                }
                
                mainHandler.postDelayed({
                    if (!isRecording || sessionGen != bindGeneration.get()) return@postDelayed
                    bindCameraWithRetry(
                        lens.selector,
                        lens.useFrontCamera,
                        retryCount = if (isLegacyHal) MAX_RETRY_COUNT else 1,
                        sessionGen = sessionGen,
                    )
                }, bindDelay)
            }.onFailure { Log.e(TAG, "Failed to start", it) }
        }, ContextCompat.getMainExecutor(context))
    }
    
    private fun bindCameraWithRetry(
        selector: CameraSelector,
        useFrontCamera: Boolean,
        retryCount: Int,
        sessionGen: Int,
    ) {
        if (!isRecording || sessionGen != bindGeneration.get() || retryCount <= 0) {
            if (retryCount <= 0) {
                Log.e(TAG, "Failed to bind camera after all retries")
            }
            return
        }
        
        runCatching {
            val owner = lifecycleOwner ?: return
            val analysis = imageAnalysis ?: return
            cameraProvider?.bindToLifecycle(owner, selector, analysis)
            Log.d(TAG, "Camera bound successfully (front=$useFrontCamera)")
        }.onFailure { e ->
            Log.w(TAG, "Camera bind failed, retries left: ${retryCount - 1}", e)
            if (retryCount > 1) {
                mainHandler.postDelayed({
                    if (!isRecording || sessionGen != bindGeneration.get()) return@postDelayed
                    cameraProvider?.unbindAll()
                    mainHandler.postDelayed({
                        bindCameraWithRetry(selector, useFrontCamera, retryCount - 1, sessionGen)
                    }, LEGACY_HAL_BIND_DELAY_MS)
                }, RETRY_DELAY_MS)
            }
        }
    }
    
    private fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (isRecording && now - lastFrameTime >= frameIntervalMs) {
            lastFrameTime = now
            runCatching {
                convertToJpeg(imageProxy)?.let { onFrameCallback?.invoke(it) }
            }
        }
        imageProxy.close()
    }
    
    private fun convertToJpeg(imageProxy: ImageProxy): ByteArray? = runCatching {
        val width = imageProxy.width
        val height = imageProxy.height
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        
        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0
        
        
        for (row in 0 until height) {
            val yOffset = row * yRowStride
            if (yOffset + width <= yBuffer.capacity()) {
                yBuffer.position(yOffset)
                yBuffer.get(nv21, pos, width)
            }
            pos += width
        }
        
        
        val uvHeight = height / 2
        val uvWidth = width / 2
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uvOffset = row * uvRowStride + col * uvPixelStride
                if (uvOffset < vBuffer.capacity() && uvOffset < uBuffer.capacity()) {
                    nv21[pos++] = vBuffer.get(uvOffset)  
                    nv21[pos++] = uBuffer.get(uvOffset)  
                } else {
                    nv21[pos++] = 128.toByte()  
                    nv21[pos++] = 128.toByte()
                }
            }
        }
        
        
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), jpegQuality, out)
        
        var jpeg = out.toByteArray()
        
        // Calculate rotation based on forced orientation or auto-detect
        val rotation = when (forcedOrientation) {
            "PORTRAIT" -> 0
            "PORTRAIT_FLIP" -> 180
            "LANDSCAPE" -> 90
            "LANDSCAPE_FLIP" -> 270
            else -> imageProxy.imageInfo.rotationDegrees
        }
        
        if (rotation != 0) {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            bmp.recycle()
            val rotOut = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, jpegQuality, rotOut)
            rotated.recycle()
            jpeg = rotOut.toByteArray()
        }

        return finalizeOutputJpeg(jpeg)
    }.getOrNull()

    /** Scale to output short edge while preserving aspect ratio (portrait-safe for HA). */
    private fun finalizeOutputJpeg(jpeg: ByteArray): ByteArray {
        var bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val shortEdge = min(bitmap.width, bitmap.height)
        if (shortEdge != outputShortEdge) {
            val scale = outputShortEdge.toFloat() / shortEdge
            val targetW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            if (scaled != bitmap) bitmap.recycle()
            bitmap = scaled
        }
        if (applyVoicePolish) {
            val enhanced = VoiceCallVideoEnhancer.enhanceForCapture(bitmap)
            if (enhanced != bitmap) bitmap.recycle()
            bitmap = enhanced
        }
        return ByteArrayOutputStream().apply {
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, this)
            bitmap.recycle()
        }.toByteArray()
    }
    
    /**
     * Stop analysis and unbind CameraX.
     * @param awaitUnbind when true (teardown/close), block until main-thread unbind finishes
     * so the HAL is released before the next client binds.
     */
    fun stopRecording(awaitUnbind: Boolean = false) {
        // Invalidate every in-flight provider callback / delayed bind / retry.
        bindGeneration.incrementAndGet()
        isRecording = false
        onFrameCallback = null
        orientationListener.disable()
        mainHandler.removeCallbacksAndMessages(null)
        runUnbindOnMain(await = awaitUnbind)
    }
    
    fun close() {
        stopRecording(awaitUnbind = true)
        executor.shutdown()
    }

    private fun runUnbindOnMain(await: Boolean) {
        val teardown = Runnable {
            runCatching { imageAnalysis?.clearAnalyzer() }
            runCatching { cameraProvider?.unbindAll() }
            runCatching { lifecycleOwner?.stop() }
            lifecycleOwner = null
            cameraProvider = null
            imageAnalysis = null
            Log.d(TAG, "CameraX unbound")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            teardown.run()
            return
        }
        if (!await) {
            mainHandler.post(teardown)
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                teardown.run()
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(UNBIND_AWAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Timed out waiting for CameraX unbind")
        }
    }
    
}
