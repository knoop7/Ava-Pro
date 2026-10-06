package com.example.ava.services

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.ava.sensor.PresenceFusionEngine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Camera motion for screensaver: 人来退、人走开后再进。
 *
 * Kept deliberately sensitive enough to work on wall-panel HA video (often 1–5 fps):
 * - Two-frame luma diff + illumination compensate (three-frame pixel-AND was too strict).
 * - Spatial grid so whole-frame AE does not count as a person.
 * - While screensaver is visible, **any confirmed motion** dismisses (not only rising edge —
 *   rising-edge-only failed when “present” was already true before the screensaver showed).
 * - After [QUIET_HOLD_MS] without motion ⇒ left ⇒ idle may start again.
 */
object ScreensaverFrameMotionProbe {
    private const val TAG = "SsFrameMotionProbe"

    private const val TARGET_SHORT_EDGE = 48
    private const val GRID = 6

    private const val PIXEL_DIFF_THRESH = 16
    private const val CELL_HOT_RATIO = 0.18f
    private const val MIN_HOT_CELLS = 3
    private const val MAX_HOT_CELLS = 30

    private const val ENTER_STREAK = 2
    private const val QUIET_HOLD_MS = 6_500L

    private val lock = Any()

    private var width = 0
    private var height = 0
    private var prevLuma: ByteArray? = null
    private var motionStreak = 0
    private var lastMotionAt = 0L
    @Volatile private var present = false
    private var lastLogAt = 0L

    /** Second consumer: Bayesian occupancy wants motion even when the screensaver doesn't. */
    @Volatile private var occupancyConsumer = false

    fun isSceneOccupied(): Boolean = present

    fun setOccupancyConsumer(enabled: Boolean) {
        occupancyConsumer = enabled
        if (!enabled) {
            PresenceFusionEngine.report(PresenceFusionEngine.Source.MOTION, false)
        }
    }

    fun reset() {
        synchronized(lock) {
            width = 0
            height = 0
            prevLuma = null
            motionStreak = 0
            lastMotionAt = 0L
            present = false
        }
        PresenceFusionEngine.report(PresenceFusionEngine.Source.MOTION, false)
    }

    fun onJpegFrame(jpeg: ByteArray) {
        val screensaverWants = ScreensaverController.shouldProbeRecordingMotion()
        if (!screensaverWants && !occupancyConsumer) {
            if (prevLuma != null || present) reset()
            return
        }
        synchronized(lock) {
            val luma = extractLuma(jpeg) ?: return
            val prev = prevLuma
            if (prev == null || width != luma.width || height != luma.height ||
                prev.size != luma.bytes.size
            ) {
                width = luma.width
                height = luma.height
                prevLuma = luma.bytes.copyOf()
                motionStreak = 0
                return
            }

            val n = luma.bytes.size
            var sumF = 0.0
            var sumP = 0.0
            for (i in 0 until n) {
                sumF += (luma.bytes[i].toInt() and 0xff)
                sumP += (prev[i].toInt() and 0xff)
            }
            val meanShift = ((sumF - sumP) / n).toFloat()

            val cellW = max(1, width / GRID)
            val cellH = max(1, height / GRID)
            val changed = IntArray(GRID * GRID)
            val tot = IntArray(GRID * GRID)

            for (y in 0 until height) {
                val cy = min(GRID - 1, y / cellH)
                val row = y * width
                for (x in 0 until width) {
                    val i = row + x
                    val f = (luma.bytes[i].toInt() and 0xff).toFloat()
                    val p = (prev[i].toInt() and 0xff).toFloat()
                    val c = cy * GRID + min(GRID - 1, x / cellW)
                    tot[c]++
                    if (abs(f - p - meanShift) >= PIXEL_DIFF_THRESH) changed[c]++
                }
            }

            var hot = 0
            for (c in changed.indices) {
                if (tot[c] > 0 && changed[c].toFloat() / tot[c] >= CELL_HOT_RATIO) hot++
            }

            val motionNow = hot in MIN_HOT_CELLS..MAX_HOT_CELLS
            val now = System.currentTimeMillis()
            prevLuma = luma.bytes.copyOf()

            if (motionNow) {
                motionStreak++
                lastMotionAt = now
            } else {
                motionStreak = 0
            }

            val confirmed = motionStreak >= ENTER_STREAK
            val previously = present
            if (confirmed) {
                present = true
                // Dismiss whenever screensaver is up — do not require rising edge of present.
                if (screensaverWants) {
                    ScreensaverController.onRecordingMotionDetected()
                }
            } else if (present && lastMotionAt > 0L && now - lastMotionAt >= QUIET_HOLD_MS) {
                present = false
            }
            if (occupancyConsumer && present != previously) {
                PresenceFusionEngine.report(PresenceFusionEngine.Source.MOTION, present)
            }

            if (now - lastLogAt > 2500L) {
                lastLogAt = now
                Log.d(
                    TAG,
                    "hot=$hot/${GRID * GRID} motion=$motionNow confirmed=$confirmed present=$present"
                )
            }

            if (!present && previously) {
                Log.i(TAG, "left after quiet ${QUIET_HOLD_MS}ms")
                if (screensaverWants) {
                    ScreensaverController.onSceneOccupancyCleared()
                }
            }
        }
    }

    private data class LumaFrame(val width: Int, val height: Int, val bytes: ByteArray)

    private fun extractLuma(jpeg: ByteArray): LumaFrame? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = max(1, max(bounds.outWidth, bounds.outHeight) / TARGET_SHORT_EDGE)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w < GRID || h < GRID) return null
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val out = ByteArray(w * h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                out[i] = ((r * 30 + g * 59 + b * 11) / 100).toByte()
            }
            LumaFrame(w, h, out)
        } finally {
            bitmap.recycle()
        }
    }
}
