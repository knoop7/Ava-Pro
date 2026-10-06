package com.example.ava.fleet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import com.example.ava.services.AccessibilityBridge
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot screen capture for the fleet console (no continuous stream).
 *
 * Preference order:
 * 1. Shell `screencap -p` via Shizuku/root — silent, no dialog.
 * 2. Accessibility [takeScreenshot] (API 30+) — no MediaProjection dialog; requires the
 *    user to have already enabled Ava's accessibility service (never auto-prompted here).
 */
object FleetScreenShot {
    private const val TAG = "FleetScreenShot"
    private const val REMOTE_PNG = "/data/local/tmp/ava-fleet-shot.png"
    private const val LOCAL_NAME = "fleet-shot.png"
    private const val DEFAULT_MAX_WIDTH = 720
    private const val DEFAULT_QUALITY = 40

    data class Shot(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val atMs: Long,
        val backend: String,
    )

    private val latest = AtomicReference<Shot?>(null)
    private val lastError = AtomicReference<String?>(null)
    private val lock = Any()

    fun latest(): Shot? = latest.get()

    fun clear() {
        latest.set(null)
        lastError.set(null)
    }

    fun canAccessibilityCapture(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && AccessibilityBridge.isServiceConnected()

    fun statusJson(): JSONObject {
        val s = latest.get()
        val shell = shellBackend()
        val a11y = canAccessibilityCapture()
        return JSONObject()
            .put("mode", "oneshot")
            .put("hasShot", s != null)
            .put("backend", s?.backend ?: shell ?: if (a11y) "accessibility" else JSONObject.NULL)
            .put("canCapture", shell != null || a11y)
            .put("canShellCapture", shell != null)
            .put("canAccessibilityCapture", a11y)
            .put("width", s?.width ?: 0)
            .put("height", s?.height ?: 0)
            .put("bytes", s?.jpeg?.size ?: 0)
            .put("ageMs", s?.let { System.currentTimeMillis() - it.atMs } ?: JSONObject.NULL)
            .put("lastError", lastError.get() ?: JSONObject.NULL)
    }

    /**
     * Capture once. If [force] is false and a shot already exists, return the cache.
     */
    fun captureOnce(
        context: Context,
        force: Boolean = false,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
        quality: Int = DEFAULT_QUALITY,
    ): Shot? {
        if (!force) {
            latest.get()?.let { return it }
        }
        synchronized(lock) {
            if (!force) {
                latest.get()?.let { return it }
            }
            return runCapture(context.applicationContext, maxWidth, quality)
        }
    }

    private fun runCapture(context: Context, maxWidth: Int, quality: Int): Shot? {
        lastError.set(null)
        val shell = shellBackend()
        if (shell != null) {
            val shot = runCaptureShell(context, maxWidth, quality, shell)
            if (shot != null) return shot
            // Fall through to a11y if shell screencap failed.
        }
        if (canAccessibilityCapture()) {
            val shot = runCaptureAccessibility(context, maxWidth, quality)
            if (shot != null) return shot
            // Rate-limited: keep serving the previous frame.
            if (lastError.get() == "take_screenshot_interval_time_short") {
                latest.get()?.let { return it }
            }
        }
        if (shell == null && !canAccessibilityCapture()) {
            lastError.set(
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    "need_shizuku_or_root"
                } else {
                    "need_shizuku_root_or_accessibility"
                },
            )
        }
        return null
    }

    private fun runCaptureShell(
        context: Context,
        maxWidth: Int,
        quality: Int,
        backend: String,
    ): Shot? {
        return try {
            val local = File(context.getExternalFilesDir(null) ?: context.filesDir, LOCAL_NAME)
            val primary = local.absolutePath
            var code = shellExec(backend, "screencap -p '$primary'")
            if (code != 0 || !local.isFile || local.length() < 64L) {
                code = shellExec(
                    backend,
                    "screencap -p '$REMOTE_PNG' && cp '$REMOTE_PNG' '$primary' && chmod 644 '$primary'",
                )
            }
            if (code != 0 || !local.isFile || local.length() < 64L) {
                lastError.set("screencap_failed_$code")
                return null
            }
            val shot = pngFileToJpeg(local, maxWidth, quality.coerceIn(25, 85), backend)
                ?: run {
                    lastError.set("jpeg_encode_failed")
                    null
                }
            if (shot != null) {
                latest.set(shot)
                Log.i(TAG, "oneshot ${shot.width}x${shot.height} ${shot.jpeg.size}B via $backend")
            }
            runCatching { local.delete() }
            shellExec(backend, "rm -f '$REMOTE_PNG' || true")
            shot
        } catch (e: Exception) {
            Log.e(TAG, "shell capture failed", e)
            lastError.set(e.message ?: "capture_exception")
            null
        }
    }

    private fun runCaptureAccessibility(context: Context, maxWidth: Int, quality: Int): Shot? {
        return try {
            val raw = AccessibilityBridge.takeScreenshot(context)
            val json = JSONObject(raw)
            if (!json.optBoolean("ok")) {
                lastError.set(json.optString("error", "a11y_screenshot_failed"))
                return null
            }
            val path = json.optString("path").trim()
            if (path.isEmpty()) {
                lastError.set("a11y_screenshot_no_path")
                return null
            }
            val file = File(path)
            if (!file.isFile || file.length() < 64L) {
                lastError.set("a11y_screenshot_empty")
                return null
            }
            val shot = pngFileToJpeg(
                file,
                maxWidth,
                quality.coerceIn(25, 85),
                "accessibility",
            ) ?: run {
                lastError.set("jpeg_encode_failed")
                null
            }
            if (shot != null) {
                latest.set(shot)
                Log.i(TAG, "oneshot ${shot.width}x${shot.height} ${shot.jpeg.size}B via accessibility")
            }
            shot
        } catch (e: Exception) {
            Log.e(TAG, "a11y capture failed", e)
            lastError.set(e.message ?: "a11y_capture_exception")
            null
        }
    }

    /**
     * Encode PNG file to JPEG.
     *
     * [maxSide] is the longest edge after resize (portrait or landscape).
     * Pass `<= 0` to keep the original pixel size (still JPEG-compressed).
     */
    private fun pngFileToJpeg(file: File, maxSide: Int, quality: Int, backend: String): Shot? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val limit = if (maxSide <= 0) Int.MAX_VALUE else maxSide.coerceIn(240, 4096)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longSide / sample > limit * 2 && sample < 32) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        try {
            val currentLong = maxOf(bmp.width, bmp.height)
            if (currentLong > limit) {
                val scale = limit.toDouble() / currentLong
                val tw = (bmp.width * scale).toInt().coerceAtLeast(1)
                val th = (bmp.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
                if (scaled !== bmp) {
                    bmp.recycle()
                    bmp = scaled
                }
            }
            val out = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
            return Shot(
                jpeg = out.toByteArray(),
                width = bmp.width,
                height = bmp.height,
                atMs = System.currentTimeMillis(),
                backend = backend,
            )
        } finally {
            runCatching { bmp.recycle() }
        }
    }

    fun shellBackend(): String? = when {
        ShizukuUtils.isShizukuPermissionGranted() -> "shizuku"
        RootUtils.isRootAvailable() -> "root"
        else -> null
    }

    private fun shellExec(backend: String, command: String): Int {
        return when (backend) {
            "shizuku" -> ShizukuUtils.executeCommand(command).first
            "root" -> runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
            }.getOrDefault(1)
            else -> -1
        }
    }
}
