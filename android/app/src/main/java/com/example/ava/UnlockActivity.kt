package com.example.ava

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Transient helper that dismisses the keyguard then exits.
 *
 * Must not use [android.R.style.Theme_NoDisplay] while waiting for
 * [KeyguardManager.requestDismissKeyguard]: that theme requires finish()
 * before onResume completes, otherwise the activity is destroyed without
 * finish() (Auto.js / instrumentation: "finish() not called before onDestroy").
 */
class UnlockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { safeFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val shouldUnlock = intent.getBooleanExtra("unlock", false)
        if (!shouldUnlock) {
            safeFinish()
            return
        }

        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            safeFinish()
            return
        }

        if (!keyguardManager.isKeyguardLocked) {
            safeFinish()
            return
        }

        keyguardManager.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    handler.removeCallbacks(finishRunnable)
                    safeFinish()
                }

                override fun onDismissCancelled() {
                    handler.removeCallbacks(finishRunnable)
                    safeFinish()
                }

                override fun onDismissError() {
                    handler.removeCallbacks(finishRunnable)
                    safeFinish()
                }
            },
        )
        // Safety net if OEM never delivers the callback.
        handler.postDelayed(finishRunnable, 2_000L)
    }

    override fun onResume() {
        super.onResume()
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager != null && !keyguardManager.isKeyguardLocked) {
            handler.removeCallbacks(finishRunnable)
            safeFinish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        super.onDestroy()
    }

    private fun safeFinish() {
        if (isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed) return
        finish()
    }
}
