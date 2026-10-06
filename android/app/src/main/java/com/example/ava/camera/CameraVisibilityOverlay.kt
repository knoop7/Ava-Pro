package com.example.ava.camera

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.services.OverlayOrientation

/**
 * 1×1 invisible overlay so a background service can open the camera on kiosk devices.
 * Mirrors portal-ha-bridge's camera overlay without changing Ava's ESPHome streaming path.
 */
object CameraVisibilityOverlay {
    private const val TAG = "CameraVisibilityOverlay"

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var overlayView: View? = null
    @Volatile private var holdCount = 0

    fun acquire(context: Context) {
        mainHandler.post {
            holdCount++
            if (overlayView != null) return@post
            if (!PlatformCapabilities.canDrawOverlays(context)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — background camera may fail on this device")
                return@post
            }
            runCatching {
                val appContext = context.applicationContext
                val wm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    appContext.getSystemService(WindowManager::class.java)
                } else {
                    appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                } ?: return@runCatching
                val view = View(appContext)
                val params = WindowManager.LayoutParams(
                    1,
                    1,
                    PlatformCapabilities.overlayWindowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply { alpha = 0f }
                OverlayOrientation.apply(params)
                wm.addView(view, params)
                overlayView = view
                Log.i(TAG, "Camera visibility overlay shown")
            }.onFailure {
                Log.w(TAG, "Could not show camera visibility overlay", it)
            }
        }
    }

    fun release(context: Context) {
        mainHandler.post {
            if (holdCount > 0) holdCount--
            if (holdCount > 0) return@post
            removeOverlayView(context)
        }
    }

    /** Drop the overlay regardless of hold count — used when the camera module is torn down. */
    fun forceRelease(context: Context) {
        mainHandler.post {
            holdCount = 0
            removeOverlayView(context)
        }
    }

    private fun removeOverlayView(context: Context) {
        val view = overlayView ?: return
        overlayView = null
        runCatching {
            val appContext = context.applicationContext
            val wm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                appContext.getSystemService(WindowManager::class.java)
            } else {
                appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            }
            wm?.removeView(view)
            Log.i(TAG, "Camera visibility overlay hidden")
        }.onFailure {
            Log.w(TAG, "Could not remove camera visibility overlay", it)
        }
    }
}
