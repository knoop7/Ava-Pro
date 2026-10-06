package com.example.ava.platform

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.WindowManager
import org.json.JSONObject

/**
 * In-process HAL vsync vs [System.nanoTime] probe. Same number on every
 * device; logcat's "Frame time is … in the future" is OEM-optional.
 */
object DisplayClockProbe {
    private const val TAG = "DisplayClock"
    private const val CAP = 240
    /** After the first 2s window, do not ride every vsync — that keeps android.anim hot. */
    private const val WATCH_DELAY_MS = 250L

    data class Snapshot(
        val samples: Int = 0,
        val ready: Boolean = false,
        val refreshHz: Int = 0,
        val appVsyncOffsetNs: Long = 0L,
        val deadlineNs: Long = 0L,
        val crossWindowBlur: Boolean = false,
        val skewP50Ns: Long = 0L,
        val skewP95Ns: Long = 0L,
        val skewP99Ns: Long = 0L,
        val futureFrameRatio: Float = 0f,
        val futureOver1ms: Int = 0,
        val lateOver8ms: Int = 0,
        val frostInsteadOfBlur: Boolean = false,
    )

    @Volatile private var started = false
    @Volatile private var frostInsteadOfBlur = false
    @Volatile private var published = Snapshot()
    @Volatile private var decided = false

    private val lock = Any()
    private val deltas = LongArray(CAP)
    private var count = 0
    private var windowStartElapsed = 0L
    private val scratch = LongArray(CAP)

    private val frameCallback: Choreographer.FrameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val delta = frameTimeNanos - System.nanoTime()
                val now = SystemClock.elapsedRealtime()
                var rolled: Snapshot? = null
                synchronized(lock) {
                    if (windowStartElapsed == 0L) windowStartElapsed = now
                    if (count < CAP) {
                        deltas[count] = delta
                        count++
                    }
                    if (now - windowStartElapsed >= DisplayClockStats.WINDOW_MS &&
                        count >= DisplayClockStats.MIN_SAMPLES
                    ) {
                        rolled = rollLocked()
                        windowStartElapsed = now
                        count = 0
                    }
                }
                rolled?.let { publish(it) }
                val choreographer = Choreographer.getInstance()
                if (published.ready) {
                    choreographer.postFrameCallbackDelayed(this, WATCH_DELAY_MS)
                } else {
                    choreographer.postFrameCallback(this)
                }
            }
        }

    fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val main = Handler(Looper.getMainLooper())
        val arm = Runnable {
            windowStartElapsed = SystemClock.elapsedRealtime()
            runCatching { Choreographer.getInstance().postFrameCallback(frameCallback) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) arm.run() else main.post(arm)
    }

    fun shouldFrostInsteadOfBlur(): Boolean = frostInsteadOfBlur

    fun snapshot(context: Context? = null): Snapshot {
        ensureStarted()
        val base = published
        val display = displayMeta(context)
        return base.copy(
            refreshHz = display.refreshHz,
            appVsyncOffsetNs = display.appVsyncOffsetNs,
            deadlineNs = display.deadlineNs,
            crossWindowBlur = display.crossWindowBlur,
            frostInsteadOfBlur = frostInsteadOfBlur,
        )
    }

    fun toJson(context: Context? = null): JSONObject {
        val s = snapshot(context)
        return JSONObject()
            .put("samples", s.samples)
            .put("ready", s.ready)
            .put("refreshHz", s.refreshHz)
            .put("appVsyncOffsetNs", s.appVsyncOffsetNs)
            .put("deadlineNs", s.deadlineNs)
            .put("crossWindowBlur", s.crossWindowBlur)
            .put("skewP50Ns", s.skewP50Ns)
            .put("skewP95Ns", s.skewP95Ns)
            .put("skewP99Ns", s.skewP99Ns)
            .put("futureFrameRatio", (s.futureFrameRatio * 1000).toInt() / 1000.0)
            .put("futureOver1ms", s.futureOver1ms)
            .put("lateOver8ms", s.lateOver8ms)
            .put("frostInsteadOfBlur", s.frostInsteadOfBlur)
    }

    private fun rollLocked(): Snapshot {
        val n = count
        System.arraycopy(deltas, 0, scratch, 0, n)
        scratch.sort(0, n)
        val p95 = DisplayClockStats.percentile(scratch, n, 95)
        val late = DisplayClockStats.lateOver8ms(deltas, n)
        val lateRatio = if (n > 0) late.toFloat() / n.toFloat() else 0f
        val nextFrost = DisplayClockStats.decideFrost(
            previouslyFrost = frostInsteadOfBlur,
            ready = true,
            skewP95Ns = p95,
            lateOver8msRatio = lateRatio,
        )
        return Snapshot(
            samples = n,
            ready = true,
            skewP50Ns = DisplayClockStats.percentile(scratch, n, 50),
            skewP95Ns = p95,
            skewP99Ns = DisplayClockStats.percentile(scratch, n, 99),
            futureFrameRatio = DisplayClockStats.futureRatio(deltas, n),
            futureOver1ms = DisplayClockStats.futureOver1ms(deltas, n),
            lateOver8ms = late,
            frostInsteadOfBlur = nextFrost,
        )
    }

    private fun publish(next: Snapshot) {
        val was = frostInsteadOfBlur
        frostInsteadOfBlur = next.frostInsteadOfBlur
        published = next
        val flipped = decided && next.frostInsteadOfBlur != was
        decided = true
        if (flipped) {
            Log.i(
                TAG,
                "vsync frost=${next.frostInsteadOfBlur} p95=${next.skewP95Ns}ns " +
                    "late8ms=${next.lateOver8ms}/${next.samples}",
            )
        }
    }

    private data class DisplayMeta(
        val refreshHz: Int,
        val appVsyncOffsetNs: Long,
        val deadlineNs: Long,
        val crossWindowBlur: Boolean,
    )

    private fun displayMeta(context: Context?): DisplayMeta {
        var hz = 0
        var offset = 0L
        var deadline = 0L
        var blur = false
        if (context != null) {
            runCatching {
                val wm = context.applicationContext
                    .getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: return@runCatching
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay ?: return@runCatching
                hz = display.refreshRate.toInt().coerceIn(0, 240)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    offset = display.appVsyncOffsetNanos
                    deadline = display.presentationDeadlineNanos
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    blur = wm.isCrossWindowBlurEnabled
                }
            }
        }
        return DisplayMeta(
            refreshHz = hz,
            appVsyncOffsetNs = offset,
            deadlineNs = deadline,
            crossWindowBlur = blur,
        )
    }
}
