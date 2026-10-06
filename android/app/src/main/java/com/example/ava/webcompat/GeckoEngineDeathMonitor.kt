package com.example.ava.webcompat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * Event-driven monitor for unexpected death of the delegated gecko engine pack process.
 * Uses [Intent.ACTION_PACKAGE_RESTARTED] only (public API, no polling).
 *
 * Note: [ActivityManager.addOnUidImportanceListener] is not available to third-party apps in the
 * public SDK, so we rely on the system broadcast when the gecko pack is force-stopped or restarted
 * after a crash.
 */
class GeckoEngineDeathMonitor {

    companion object {
        private const val TAG = "GeckoEngineDeathMonitor"
        private const val DEFAULT_SUPPRESS_MS = 4_000L

        @Volatile
        private var globalSuppressUntilMs = 0L

        /** Ignore death signals briefly after an intentional destroy/restart. */
        fun suppressBriefly(durationMs: Long = DEFAULT_SUPPRESS_MS) {
            globalSuppressUntilMs = System.currentTimeMillis() + durationMs
        }

        private fun isSuppressed(): Boolean =
            System.currentTimeMillis() < globalSuppressUntilMs
    }

    private var appContext: Context? = null
    private var callback: ((String) -> Unit)? = null
    private var started = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!started || isSuppressed()) return
            val pkg = intent?.data?.schemeSpecificPart ?: return
            if (pkg != BrowserEngine.GECKO_ENGINE_PACKAGE) return
            when (intent.action) {
                Intent.ACTION_PACKAGE_RESTARTED -> dispatchDeath("package_restarted")
            }
        }
    }

    fun start(context: Context, onUnexpectedDeath: (reason: String) -> Unit) {
        if (EngineCapabilities.GECKO_BUNDLED) return
        if (!BrowserEngine.isGeckoEnginePackInstalled(context)) return

        val appCtx = context.applicationContext
        callback = onUnexpectedDeath
        if (started) return

        appContext = appCtx

        val filter = IntentFilter(Intent.ACTION_PACKAGE_RESTARTED).apply {
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            appCtx.registerReceiver(packageReceiver, filter)
        }

        started = true
        Log.d(TAG, "Monitoring gecko pack death via PACKAGE_RESTARTED (event-driven)")
    }

    fun stop() {
        if (!started) {
            callback = null
            return
        }

        appContext?.let { appCtx ->
            try {
                appCtx.unregisterReceiver(packageReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister package receiver", e)
            }
        }

        started = false
        callback = null
        appContext = null
    }

    private fun dispatchDeath(reason: String) {
        if (isSuppressed()) return
        Log.w(TAG, "Gecko engine pack unexpected death: $reason")
        val cb = callback
        stop()
        cb?.invoke(reason)
    }
}
