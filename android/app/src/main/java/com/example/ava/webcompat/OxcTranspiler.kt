package com.example.ava.webcompat

import android.util.Log

/**
 * JNI binding to the bundled oxc-transform native library (`native/oxc-transpiler`,
 * built by its `build-android.sh` into `jniLibs/`). Lowers modern JS syntax to a
 * target engine level (e.g. `chrome83`) in milliseconds — ~25ms for a 1 MB card
 * on desktop, native-fast on device too.
 *
 * Helpers are emitted in external mode (`babelHelpers.*`); callers must prepend
 * the helpers prelude (assets/ava_transpile/helpers.js) to the returned code.
 */
object OxcTranspiler {

    private const val TAG = "OxcTranspiler"

    val available: Boolean = try {
        System.loadLibrary("oxctranspiler")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "native transpiler unavailable", t)
        false
    }

    /** Lowered code, or null when unavailable or the source failed to lower. */
    fun lower(source: String, target: String): String? {
        if (!available) return null
        val result = try {
            nativeTransform(source, target)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeTransform threw", t)
            null
        } ?: return null
        return when {
            result.startsWith("ok:") -> result.substring(3)
            result.startsWith("err:") -> {
                Log.w(TAG, "transform failed: ${result.substring(4).take(300)}")
                null
            }
            else -> null
        }
    }

    private external fun nativeTransform(source: String, target: String): String?
}
