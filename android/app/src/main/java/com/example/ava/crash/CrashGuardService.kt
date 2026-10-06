package com.example.ava.crash

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * A do-nothing START_STICKY service whose restart is the crash self-heal hook.
 *
 * Not a foreground service: it holds nothing while Ava is alive. After the
 * process dies, Android re-creates it with a null intent (the sticky-restart
 * signature) and that restart puts the UI back.
 *
 * The restart path returns START_NOT_STICKY on purpose: some OEMs kill each
 * post-crash restart at birth; STICKY then retries in a RAM sawtooth. One
 * attempt is taken here; the heartbeat alarm is the retry. A successful
 * relaunch re-arms stickiness from Activity onResume.
 */
class CrashGuardService : Service() {
    companion object {
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            try {
                context.startService(Intent(context, CrashGuardService::class.java))
            } catch (e: Exception) {
                Log.w("CrashGuardService", "start failed: $e")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CrashGuardService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            CrashSelfHeal.maybeRelaunch(this)
            return START_NOT_STICKY
        }
        return START_STICKY
    }
}
