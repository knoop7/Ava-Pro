package com.example.ava.crash

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.example.ava.utils.AvaProcessControl

/**
 * Detects a wedged main thread without touching the render pipeline.
 *
 * [android.view.Choreographer] would keep vsync awake on idle wall panels.
 * This posts an empty runnable to the main [Handler] every [PROBE_MS] and
 * only acts if it does not come back. Cost while armed: one sleeping
 * [HandlerThread] and one delayed message. Nothing runs while the setting
 * is off, the activity is paused, or the fuse has blown.
 *
 * A restart is the most dangerous thing this object can do. Safest path:
 * default off, require crash self-heal (heartbeat as a second net), and
 * blow a persistent fuse after two recoveries in ten minutes.
 */
object MainThreadStallWatchdog {
    private const val TAG = "MainThreadStall"
    private const val PREFS = "ava_stall_watchdog"
    private const val KEY_FUSED = "fused"
    private const val KEY_LAST_TRIP = "last_trip"

    private const val PROBE_MS = 10_000L
    private const val STALL_MS = 15_000L
    private const val FUSE_WINDOW_MS = 10 * 60_000L
    private const val RESUME_SUPPRESS_MS = 20_000L
    private const val COOLDOWN_MS = 60_000L

    @Volatile private var exportEnabled = false
    @Volatile private var watchdogEnabled = false
    @Volatile private var activityResumed = false
    @Volatile private var suppressUntilElapsed = 0L
    @Volatile private var lastAckElapsed = 0L
    @Volatile private var fused = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var app: Context? = null
    private var thread: HandlerThread? = null
    private var bgHandler: Handler? = null

    fun applyFlags(context: Context, export: Boolean, watchdog: Boolean) {
        exportEnabled = export
        watchdogEnabled = export && watchdog
        AvaIncidentLog.setExportEnabled(export)
        val appCtx = context.applicationContext
        fused = prefs(appCtx).getBoolean(KEY_FUSED, false)
        if (!watchdog) {
            // Turning the switch off is the escape hatch from a blown fuse.
            if (fused) {
                prefs(appCtx).edit().putBoolean(KEY_FUSED, false).apply()
                fused = false
            }
            stop()
            return
        }
        if (activityResumed) start(appCtx) else stop()
    }

    fun onActivityResumed(context: Context) {
        activityResumed = true
        suppressUntilElapsed = SystemClock.elapsedRealtime() + RESUME_SUPPRESS_MS
        lastAckElapsed = SystemClock.elapsedRealtime()
        if (watchdogEnabled) start(context.applicationContext)
    }

    fun onActivityPaused() {
        activityResumed = false
        stop()
    }

    fun suppressBriefly(durationMs: Long = 8_000L) {
        val until = SystemClock.elapsedRealtime() + durationMs
        if (until > suppressUntilElapsed) suppressUntilElapsed = until
        lastAckElapsed = SystemClock.elapsedRealtime()
    }

    fun isFused(context: Context): Boolean =
        fused || prefs(context).getBoolean(KEY_FUSED, false)

    private fun start(context: Context) {
        synchronized(lock) {
            if (thread != null) {
                app = context.applicationContext
                return
            }
            fused = prefs(context).getBoolean(KEY_FUSED, false)
            if (fused || !watchdogEnabled) return
            app = context.applicationContext
            lastAckElapsed = SystemClock.elapsedRealtime()
            val t = HandlerThread("ava-stall-wd")
            t.start()
            thread = t
            val h = Handler(t.looper)
            bgHandler = h
            h.postDelayed(probe, PROBE_MS)
        }
    }

    private fun stop() {
        synchronized(lock) {
            bgHandler?.removeCallbacksAndMessages(null)
            thread?.quitSafely()
            bgHandler = null
            thread = null
        }
    }

    private val probe = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            if (!activityResumed || !watchdogEnabled || fused) return
            val now = SystemClock.elapsedRealtime()
            if (now < suppressUntilElapsed || !isScreenOn(ctx)) {
                lastAckElapsed = now
                bgHandler?.postDelayed(this, PROBE_MS)
                return
            }
            if (now - lastAckElapsed >= STALL_MS) {
                onStall(ctx, now - lastAckElapsed)
                return
            }
            mainHandler.post {
                lastAckElapsed = SystemClock.elapsedRealtime()
            }
            bgHandler?.postDelayed(this, PROBE_MS)
        }
    }

    private fun onStall(context: Context, stuckMs: Long) {
        Log.w(TAG, "main thread stall ${stuckMs}ms")
        if (fused || !CrashSelfHeal.isEnabled(context)) {
            AvaIncidentLog.record(
                context,
                kind = AvaIncidentLog.KIND_STALL_ONLY,
                reason = if (fused) "main_thread_stall_fused" else "main_thread_stall_record_only",
                stuckMs = stuckMs,
            )
            lastAckElapsed = SystemClock.elapsedRealtime()
            bgHandler?.postDelayed(probe, COOLDOWN_MS)
            return
        }
        // Persist the fuse before killing the process so a crash loop cannot
        // outrun the write. The current restart still runs; the next one will not.
        markTripAndMaybeFuse(context)
        AvaIncidentLog.record(
            context,
            kind = AvaIncidentLog.KIND_STALL_RESTART,
            reason = "main_thread_stall",
            stuckMs = stuckMs,
        )
        stop()
        runCatching {
            AvaProcessControl.restartAva(
                context,
                reason = "watchdog_stall",
                recordIncident = false,
            )
        }
    }

    /** Two recoveries inside [FUSE_WINDOW_MS] blow the fuse for the next process. */
    private fun markTripAndMaybeFuse(context: Context) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val last = p.getLong(KEY_LAST_TRIP, 0L)
        val editor = p.edit().putLong(KEY_LAST_TRIP, now)
        if (last > 0L && now - last < FUSE_WINDOW_MS) {
            fused = true
            editor.putBoolean(KEY_FUSED, true)
            Log.w(TAG, "fuse armed after two stall recoveries")
        }
        editor.commit()
    }

    private fun isScreenOn(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
