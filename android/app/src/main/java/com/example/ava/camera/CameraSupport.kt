package com.example.ava.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Lens resolution shared by [CameraCapture] and [VideoCapture].
 *
 * [CameraSelector.filter] returns an empty list when the requested lens is absent instead of
 * throwing, so the filtered result must be inspected before a selector can be trusted — otherwise
 * bindToLifecycle() fails with "No available camera can be found" on single-lens hardware
 * (reported by @gilcu2, knoop7/Ava#163).
 */
internal object CameraLens {

    private const val TAG = "CameraLens"

    /** Selector safe to bind, plus the lens it actually resolved to. */
    data class Selection(val selector: CameraSelector, val useFrontCamera: Boolean)

    fun select(provider: ProcessCameraProvider, useFrontCamera: Boolean): Selection {
        val available = runCatching { provider.availableCameraInfos }.getOrElse { emptyList() }
        for (front in listOf(useFrontCamera, !useFrontCamera)) {
            val selector = selectorFor(front)
            val matches = runCatching { selector.filter(available) }.getOrElse { emptyList() }
            if (matches.isEmpty()) continue
            if (front != useFrontCamera) {
                Log.w(TAG, "No ${lensName(useFrontCamera)} lens, falling back to ${lensName(front)}")
            }
            return Selection(selector, front)
        }
        Log.w(TAG, "No lens matched, binding the first available camera")
        return Selection(CameraSelector.Builder().build(), useFrontCamera)
    }

    fun isLegacyHal(context: Context, useFrontCamera: Boolean): Boolean {
        return runCatching {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraIdFor(cameraManager, useFrontCamera) ?: return false
            val hardwareLevel = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val isLegacy = hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
            Log.d(TAG, "Camera $cameraId hardware level: $hardwareLevel (legacy=$isLegacy)")
            isLegacy
        }.getOrElse {
            Log.w(TAG, "Failed to check camera hardware level", it)
            false
        }
    }

    private fun selectorFor(useFrontCamera: Boolean): CameraSelector = when (useFrontCamera) {
        true -> CameraSelector.DEFAULT_FRONT_CAMERA
        false -> CameraSelector.DEFAULT_BACK_CAMERA
    }

    /** Ids are not pinned to 0/1: a single-lens device only exposes the lens it has. */
    private fun cameraIdFor(cameraManager: CameraManager, useFrontCamera: Boolean): String? {
        val facing = when (useFrontCamera) {
            true -> CameraCharacteristics.LENS_FACING_FRONT
            false -> CameraCharacteristics.LENS_FACING_BACK
        }
        val cameraIds = cameraManager.cameraIdList
        return cameraIds.firstOrNull { id ->
            runCatching {
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
            }.getOrDefault(false)
        } ?: cameraIds.firstOrNull()
    }

    private fun lensName(useFrontCamera: Boolean): String = if (useFrontCamera) "front" else "back"
}

internal fun Context.displayRotation(): Int {
    return runCatching {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation
                ?: (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
                    ?.rotation
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay
                ?.rotation
        }
        rotation ?: Surface.ROTATION_0
    }.getOrDefault(Surface.ROTATION_0)
}

internal class TempLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun start() { registry.currentState = Lifecycle.State.STARTED }
    fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
}
