package com.example.ava

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.ExperimentalSettingsStore
import com.microsoft.clarity.Clarity
import com.microsoft.clarity.ClarityConfig
import com.microsoft.clarity.models.LogLevel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Microsoft Clarity install-count. Default on; the main-settings handle can turn it off.
 */
object AvaClarity {
    @Volatile
    private var initialized = false

    @Volatile
    private var allowed = true

    private val mainHandler = Handler(Looper.getMainLooper())

    fun maybeInitialize(context: Context) {
        if (initialized || VoiceSatelliteService.getInstance() == null) return
        val app = context.applicationContext
        Thread {
            val enabled = runCatching {
                ExperimentalSettingsStore(app).getCached().clarityEnabled
            }.getOrDefault(true)
            allowed = enabled
            if (!enabled) {
                clearCache(app)
                return@Thread
            }
            if (initialized || !allowed) return@Thread
            clearCache(app)
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://m.clarity.ms")
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "HEAD"
                val responseCode = connection.responseCode
                if (responseCode in 200..399 && !initialized && allowed) {
                    mainHandler.post { initializeIfAllowed(app) }
                }
            } catch (_: Exception) {
                clearCache(app)
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    fun applyEnabled(context: Context, enabled: Boolean) {
        allowed = enabled
        val app = context.applicationContext
        if (!enabled) {
            if (initialized) {
                runCatching { Clarity.pause() }
            }
            clearCache(app)
            return
        }
        if (initialized) {
            runCatching { Clarity.resume() }
            return
        }
        maybeInitialize(app)
    }

    private fun initializeIfAllowed(app: Context) {
        if (initialized || !allowed || VoiceSatelliteService.getInstance() == null) return
        try {
            Clarity.initialize(
                app,
                ClarityConfig(
                    projectId = "upuvf4equo",
                    logLevel = LogLevel.None,
                ),
            )
            initialized = true
        } catch (_: Exception) {
            clearCache(app)
        }
    }

    private fun clearCache(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, "microsoft_clarity")
            if (cacheDir.exists() && cacheDir.isDirectory) {
                cacheDir.deleteRecursively()
            }
            val filesDir = File(context.filesDir, "microsoft_clarity")
            if (filesDir.exists() && filesDir.isDirectory) {
                filesDir.deleteRecursively()
            }
            context.filesDir.parentFile
                ?.let { File(it, "databases") }
                ?.listFiles()
                ?.filter { it.name.contains("clarity", ignoreCase = true) }
                ?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}
