package com.example.ava.esphome.voicesatellite

import android.content.Context
import android.util.Log
import com.example.ava.R
import com.example.ava.esphome.EspHomeDevice
import com.example.ava.esphome.entities.ButtonEntity
import com.example.ava.esphome.entities.CameraEntity
import com.example.ava.esphome.entities.BinarySensorEntity
import com.example.ava.esphome.entities.SensorEntity
import com.example.ava.esphome.entities.SwitchEntity
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.ava.settings.VideoRecordingStateManager
import com.example.esphomeproto.api.EntityCategory
import com.example.ava.detection.initFaceDetector
import com.example.ava.detection.closeFaceDetector
import com.example.ava.detection.detectFacesAndDraw
import com.example.ava.detection.initGenderDetector
import com.example.ava.detection.closeGenderDetector
import com.example.ava.camera.CameraVisibilityOverlay
import com.example.ava.camera.DeviceCameraProfile
import com.example.ava.utils.DeviceCapabilities
import com.example.ava.sensor.PresenceFusionEngine
import com.example.ava.services.ScreensaverFrameMotionProbe
import com.example.ava.voice.AvaVoiceVideoBridge
import com.example.ava.voice.VoiceCallVideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.example.ava.ui.AvaToast

class VoiceSatelliteCamera(
    private val context: Context,
    private val scope: CoroutineScope,
    private val device: EspHomeDevice,
    private val experimentalSettingsStore: ExperimentalSettingsStore
) {
    private var cameraCapture: com.example.ava.camera.CameraCapture? = null
    private var cameraEntity: CameraEntity? = null
    private var videoCapture: com.example.ava.camera.VideoCapture? = null
    private var videoCameraEntity: CameraEntity? = null
    private var isRecording = false
    private var haRecordingPausedForCall = false
    private var haRecordingWasActiveBeforePause = false
    private var voiceCallActive = false
    private var voiceCallFrameHandler: ((ByteArray) -> Unit)? = null
    private var detectionEnabled = false
    private var personDetectedState = MutableStateFlow(false)
    private var personDetectedEntity: BinarySensorEntity? = null
    
    private var lastHasHuman = false
    private var faceBoxEnabled = true
    private var genderDetectionEnabled = false
    private var maleCountEntity: SensorEntity? = null
    private var femaleCountEntity: SensorEntity? = null
    private var lastMaleCount = -1
    private var lastFemaleCount = -1
    private val maleHistory = ArrayDeque<Int>()
    private val femaleHistory = ArrayDeque<Int>()
    
    private val recordingState = VideoRecordingStateManager.getInstance(context)
    private var videoRecordingStateFlow: MutableStateFlow<Boolean>? = null

    private val cameraProfile by lazy { DeviceCameraProfile.resolve(context) }
    private val frontLensAvailable by lazy { DeviceCapabilities.hasFrontCamera(context) }
    private val backLensAvailable by lazy { DeviceCapabilities.hasBackCamera(context) }

    companion object {
        private const val TAG = "VoiceSatelliteCamera"
        private const val COUNT_SMOOTH_WINDOW = 5
        private const val VISIBILITY_OVERLAY_SETTLE_MS = 100L
        private const val CAMERA_RELEASE_SETTLE_MS = 550L
        private const val LEGACY_CAMERA_RELEASE_SETTLE_MS = 900L
        
        fun hasSavedRecordingState(context: Context): Boolean =
            VideoRecordingStateManager.getInstance(context).isEnabled()

        fun saveRecordingState(context: Context, enabled: Boolean) {
            VideoRecordingStateManager.getInstance(context).setEnabled(enabled)
        }
        
        fun clearSavedRecordingState(context: Context) {
            VideoRecordingStateManager.getInstance(context).clear()
        }
    }

    fun isVideoRecordingEnabled(): Boolean = recordingState.isEnabled()

    suspend fun setVideoRecordingEnabled(enabled: Boolean) {
        val prefsMatch = isVideoRecordingEnabled() == enabled
        val recordingMatches = isRecording == enabled
        if (prefsMatch && recordingMatches) return

        if (!prefsMatch) {
            recordingState.setEnabled(enabled)
        }
        videoRecordingStateFlow?.value = enabled
        when (enabled) {
            true -> if (!isRecording) startVideoRecording(showToast = true)
            false -> {
                if (isRecording) stopVideoRecording(showToast = true)
                // Fully drop the CameraX client (not just stopRecording) so the HAL
                // is released when the user turns the switch off.
                recycleVideoCapture()
                videoCameraEntity?.sendImage(
                    com.example.ava.camera.VideoCapture.createPlaceholderFromAsset(context, "camera_off.png", 320, 240)
                )
            }
        }
    }

    fun isVideoRecordingActive(): Boolean = isRecording

    /** Pause HA camera stream while a voice-call video leg owns the camera. */
    suspend fun pauseForVoiceCallVideo() {
        if (haRecordingPausedForCall) return
        haRecordingPausedForCall = true
        haRecordingWasActiveBeforePause = isRecording
        if (isRecording) {
            stopVideoRecording(showToast = false)
            awaitCameraRelease()
        }
    }

    /** Start voice-call JPEG capture on the shared [videoCapture] instance (no second CameraX client). */
    suspend fun startVoiceCallVideo(
        useFrontCamera: Boolean,
        videoParams: VoiceCallVideoQuality.Params,
        onFrame: (ByteArray) -> Unit
    ): Boolean {
        if (voiceCallActive) {
            voiceCallFrameHandler = onFrame
            return true
        }
        pauseForVoiceCallVideo()
        recycleVideoCapture()
        awaitCameraRelease()

        voiceCallFrameHandler = onFrame
        voiceCallActive = true

        prepareBackgroundCameraAccess()

        val capture = ensureVideoCapture()
        capture.startRecording(
            useFrontCamera = resolveVoiceCallFrontCamera(useFrontCamera),
            fps = videoParams.fps,
            resolution = videoParams.shortEdge,
            forceOrientation = "AUTO",
            jpegQuality = videoParams.jpegQuality,
            captureCapShortEdge = videoParams.captureCapShortEdge,
            applyVoicePolish = videoParams.applyPolish
        ) { frame ->
            if (voiceCallActive) {
                voiceCallFrameHandler?.invoke(frame)
            }
        }
        Log.d(
            TAG,
            "voice call video started front=$useFrontCamera " +
                "${videoParams.shortEdge}p@${videoParams.fps}fps"
        )
        return true
    }

    suspend fun restartVoiceCallVideo(
        useFrontCamera: Boolean,
        videoParams: VoiceCallVideoQuality.Params,
        onFrame: (ByteArray) -> Unit
    ) {
        if (!voiceCallActive) return
        voiceCallActive = false
        voiceCallFrameHandler = null
        recycleVideoCapture()
        awaitCameraRelease()
        startVoiceCallVideo(useFrontCamera, videoParams, onFrame)
    }

    suspend fun stopVoiceCallVideo() {
        if (!voiceCallActive) return
        voiceCallActive = false
        voiceCallFrameHandler = null
        recycleVideoCapture()
        awaitCameraRelease()
        releaseBackgroundCameraAccess()
        resumeAfterVoiceCallVideo()
        Log.d(TAG, "voice call video stopped")
    }

    /** Restore HA camera stream after voice-call video ends. */
    suspend fun resumeAfterVoiceCallVideo() {
        if (!haRecordingPausedForCall) return
        haRecordingPausedForCall = false
        val shouldResume = haRecordingWasActiveBeforePause && isVideoRecordingEnabled()
        haRecordingWasActiveBeforePause = false
        if (shouldResume && !isRecording) {
            startVideoRecording(showToast = false)
        }
    }

    fun initSnapshot() {
        cameraCapture = com.example.ava.camera.CameraCapture(context)
        cameraEntity = CameraEntity(
            key = 10,
            name = context.getString(R.string.entity_camera_snapshot),
            objectId = "camera_snapshot",
            icon = "mdi:camera",
            entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC
        )
        
        device.addEntity(ButtonEntity(
            key = 11,
            name = context.getString(R.string.entity_take_snapshot),
            objectId = "take_snapshot",
            icon = "mdi:camera",
            entityCategory = EntityCategory.ENTITY_CATEGORY_NONE
        ) {
            scope.launch(Dispatchers.IO) {
                takeSnapshot()
            }
        })
        
        cameraEntity?.let { entity ->
            device.addEntity(entity)
            scope.launch(Dispatchers.IO) {
                entity.sendImage(com.example.ava.camera.VideoCapture.createPlaceholderFromAsset(context, "camera_off.png", 320, 240))
            }
        }
    }

    private suspend fun takeSnapshot() {
        val capture = cameraCapture ?: return
        val entity = cameraEntity ?: return
        
        Log.d(TAG, "Taking snapshot...")
        
        val settings = experimentalSettingsStore.get()
        val useFrontCamera = resolveUseFrontCamera(settings.cameraPosition)
        val targetSize = settings.imageSize
        val orientation = settings.cameraOrientation

        prepareBackgroundCameraAccess()
        try {
            val imageData = capture.capturePhoto(useFrontCamera, targetSize, orientation)
            if (imageData != null) {
                entity.sendImage(imageData)
                Log.d(TAG, "Snapshot sent: ${imageData.size} bytes, targetSize: $targetSize")
            } else {
                Log.e(TAG, "Failed to capture snapshot")
            }
        } finally {
            releaseBackgroundCameraAccess()
        }
    }

    fun initVideo() {
        videoCapture = com.example.ava.camera.VideoCapture(context)
        videoCameraEntity = CameraEntity(
            key = 12,
            name = context.getString(R.string.entity_camera_video),
            objectId = "video_camera",
            icon = "mdi:video",
            entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC
        )
        
        val savedRecordingState = recordingState.isEnabled()
        videoRecordingStateFlow = MutableStateFlow(savedRecordingState)
        device.addEntity(SwitchEntity(
            key = 13,
            name = context.getString(R.string.entity_video_recording),
            objectId = "video_recording",
            icon = "mdi:record-rec",
            getState = videoRecordingStateFlow!!,
            entityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
            setState = { enabled ->
                scope.launch(Dispatchers.IO) {
                    setVideoRecordingEnabled(enabled)
                }
            }
        ))
        videoCameraEntity?.let { entity ->
            device.addEntity(entity)
            scope.launch(Dispatchers.IO) {
                if (savedRecordingState) {
                    Log.d(TAG, "Restoring video recording state: enabled")
                    startVideoRecording()
                } else {
                    entity.sendImage(com.example.ava.camera.VideoCapture.createPlaceholderFromAsset(context, "camera_off.png", 320, 240))
                }
            }
        }
    }
    
    private suspend fun startVideoRecording(showToast: Boolean = false) {
        if (isRecording || voiceCallActive) return
        val capture = ensureVideoCapture()
        val entity = videoCameraEntity ?: return
        
        Log.d(TAG, "Starting video recording...")
        isRecording = true

        prepareBackgroundCameraAccess()
        
        val settings = experimentalSettingsStore.get()
        val useFrontCamera = resolveUseFrontCamera(settings.cameraPosition)
        val fps = settings.videoFps.coerceIn(1, 15)
        val resolution = settings.videoResolution.coerceIn(240, 720)
        val orientation = settings.cameraOrientation
        
        capture.startRecording(useFrontCamera, fps, resolution, orientation) { frameData ->
            // Lightweight activity probe for screensaver dismiss — not face/person detection.
            ScreensaverFrameMotionProbe.onJpegFrame(frameData)
            val outputFrame = processFrameForDetection(frameData)
            entity.sendImage(outputFrame)
        }
        if (showToast) {
            showRecordingToast(started = true)
        }
    }
    
    private fun stopVideoRecording(showToast: Boolean = false) {
        if (!isRecording) return
        val capture = videoCapture
        
        Log.d(TAG, "Stopping video recording...")
        isRecording = false
        ScreensaverFrameMotionProbe.reset()
        // Always request unbind even if the capture instance was already swapped —
        // leave no orphan CameraX session when the feature is turned off.
        capture?.stopRecording(awaitUnbind = false)
        releaseBackgroundCameraAccess()
        if (showToast) {
            showRecordingToast(started = false)
        }
    }

    private suspend fun prepareBackgroundCameraAccess() {
        if (!cameraProfile.requiresVisibilityOverlay) return
        CameraVisibilityOverlay.acquire(context)
        delay(VISIBILITY_OVERLAY_SETTLE_MS)
    }

    private fun releaseBackgroundCameraAccess() {
        if (!cameraProfile.requiresVisibilityOverlay) return
        if (voiceCallActive || isRecording) return
        CameraVisibilityOverlay.release(context)
    }

    private fun ensureVideoCapture(): com.example.ava.camera.VideoCapture {
        if (videoCapture == null) {
            videoCapture = com.example.ava.camera.VideoCapture(context)
        }
        return videoCapture!!
    }

    /** Drop the CameraX client so legacy HAL devices do not bind-then-evict on reuse. */
    private suspend fun recycleVideoCapture() {
        val capture = videoCapture ?: return
        videoCapture = null
        isRecording = false
        // close() awaits main-thread unbind so the next bind does not race the HAL.
        capture.close()
        delay(VISIBILITY_OVERLAY_SETTLE_MS)
    }

    private fun resolveVoiceCallFrontCamera(requested: Boolean): Boolean {
        if (cameraProfile.forceFrontCamera) return true
        return clampToAvailableLens(requested)
    }

    private suspend fun awaitCameraRelease() {
        val settleMs = if (cameraProfile.requiresVisibilityOverlay) {
            LEGACY_CAMERA_RELEASE_SETTLE_MS
        } else {
            CAMERA_RELEASE_SETTLE_MS
        }
        delay(settleMs)
    }

    private fun resolveUseFrontCamera(cameraPosition: String): Boolean {
        if (cameraProfile.forceFrontCamera) return true
        return clampToAvailableLens(cameraPosition == com.example.ava.settings.CameraPosition.FRONT.name)
    }

    /**
     * The stored position defaults to FRONT and can also be pushed from the fleet console, so it
     * may name a lens this device does not have (reported by @gilcu2, knoop7/Ava#163).
     */
    private fun clampToAvailableLens(useFrontCamera: Boolean): Boolean {
        if (hasLens(useFrontCamera)) return useFrontCamera
        if (!hasLens(!useFrontCamera)) return useFrontCamera
        Log.w(TAG, "No ${if (useFrontCamera) "front" else "back"} lens on this device, using the other one")
        return !useFrontCamera
    }

    private fun hasLens(useFrontCamera: Boolean): Boolean = when (useFrontCamera) {
        true -> frontLensAvailable
        false -> backLensAvailable
    }

    private fun showRecordingToast(started: Boolean) {
        scope.launch(Dispatchers.Main) {
            val messageRes = if (started) {
                R.string.video_recording_started_toast
            } else {
                R.string.video_recording_stopped_toast
            }
            AvaToast.show(context, messageRes, tag = "camera-record")
        }
    }

    fun initDetection() {
        Log.d(TAG, "initDetection called")
        val initialized = initFaceDetector(context)
        if (!initialized) {
            Log.e(TAG, "Failed to init face detector")
            return
        }
        
        initGenderDetector(context)
        genderDetectionEnabled = true
        
        personDetectedEntity = BinarySensorEntity(
            key = 21,
            name = context.getString(R.string.entity_person_detected),
            objectId = "face_detected",
            icon = "mdi:face-recognition",
            deviceClass = "occupancy",
            getState = personDetectedState
        )
        device.addEntity(personDetectedEntity!!)
        
        maleCountEntity = SensorEntity(
            key = 23,
            name = context.getString(R.string.entity_male_count),
            objectId = "male_count",
            icon = "mdi:face-man",
            entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC
        )
        device.addEntity(maleCountEntity!!)
        
        femaleCountEntity = SensorEntity(
            key = 24,
            name = context.getString(R.string.entity_female_count),
            objectId = "female_count",
            icon = "mdi:face-woman",
            entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC
        )
        device.addEntity(femaleCountEntity!!)
        
        detectionEnabled = true
    }
    
    fun setFaceBoxEnabled(enabled: Boolean) {
        faceBoxEnabled = enabled
    }
    
    fun processFrameForDetection(frameData: ByteArray): ByteArray {
        if (!detectionEnabled) {
            return frameData
        }
        val pair = detectFacesAndDraw(frameData, faceBoxEnabled, genderDetectionEnabled) ?: return frameData
        val (annotatedFrame, result) = pair
        
        if (result.hasFace != lastHasHuman) {
            personDetectedState.value = result.hasFace
            lastHasHuman = result.hasFace
            PresenceFusionEngine.report(PresenceFusionEngine.Source.FACE, result.hasFace)
        }
        
        val stableMale = stableCount(maleHistory, result.maleCount)
        if (stableMale != lastMaleCount) {
            maleCountEntity?.updateState(stableMale.toFloat())
            lastMaleCount = stableMale
        }
        val stableFemale = stableCount(femaleHistory, result.femaleCount)
        if (stableFemale != lastFemaleCount) {
            femaleCountEntity?.updateState(stableFemale.toFloat())
            lastFemaleCount = stableFemale
        }
        
        return annotatedFrame
    }

    fun closeDetection() {
        personDetectedEntity?.let { device.removeEntity(it) }
        maleCountEntity?.let { device.removeEntity(it) }
        femaleCountEntity?.let { device.removeEntity(it) }
        personDetectedEntity = null
        maleCountEntity = null
        femaleCountEntity = null
        detectionEnabled = false
        genderDetectionEnabled = false
        closeFaceDetector()
        closeGenderDetector()
        maleHistory.clear()
        femaleHistory.clear()
        lastHasHuman = false
        lastMaleCount = -1
        lastFemaleCount = -1
        personDetectedState.value = false
        PresenceFusionEngine.report(PresenceFusionEngine.Source.FACE, false)
    }
    
    /**
     * Full teardown for satellite stop / restart.
     * Must actually close CameraX clients — nulling references alone leaves the HAL streaming.
     */
    fun close() {
        Log.d(TAG, "Closing camera module (recording=$isRecording voiceCall=$voiceCallActive)")
        voiceCallActive = false
        voiceCallFrameHandler = null
        haRecordingPausedForCall = false
        haRecordingWasActiveBeforePause = false
        isRecording = false
        ScreensaverFrameMotionProbe.reset()

        videoCapture?.let { capture ->
            runCatching { capture.close() }
                .onFailure { Log.w(TAG, "videoCapture.close failed", it) }
        }
        videoCapture = null

        cameraCapture?.let { capture ->
            runCatching { capture.close() }
                .onFailure { Log.w(TAG, "cameraCapture.close failed", it) }
        }
        cameraCapture = null

        closeDetection()
        CameraVisibilityOverlay.forceRelease(context)
    }

    private fun stableCount(history: ArrayDeque<Int>, value: Int): Int {
        history.addLast(value)
        while (history.size > COUNT_SMOOTH_WINDOW) {
            history.removeFirst()
        }
        val sorted = history.sorted()
        return sorted[sorted.size / 2]
    }
}
