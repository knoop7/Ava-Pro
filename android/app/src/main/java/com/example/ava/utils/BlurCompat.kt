package com.example.ava.utils

import android.os.Build
import android.view.View

/**
 * Hardware-accelerated canvases do not support [android.graphics.BlurMaskFilter] before API 28.
 * On some devices/emulators (notably the Android 5.x ARM emulator) the system routes such a blur
 * through RenderScript (`ScriptIntrinsicBlur`) inside libhwui's RenderThread and segfaults, taking
 * the whole process down.
 *
 * Forcing the view onto a software layer makes BlurMaskFilter render in software, which is safe on
 * every API level and keeps the visual effect intact. On API 28+ hardware BlurMaskFilter is
 * supported, so we leave those views hardware-accelerated for performance.
 */
object BlurCompat {

    /** True when drawing a [android.graphics.BlurMaskFilter] on a hardware canvas is unsafe. */
    val needsSoftwareLayerForBlur: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.P

    /**
     * Forces [view] onto a software layer on API levels where hardware BlurMaskFilter is unsafe.
     * Call this from the view's init for any view whose `onDraw` uses a BlurMaskFilter on shapes.
     */
    fun forceSoftwareLayerIfNeeded(view: View) {
        if (needsSoftwareLayerForBlur) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
    }
}
