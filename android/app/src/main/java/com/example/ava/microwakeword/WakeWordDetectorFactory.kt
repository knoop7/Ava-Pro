package com.example.ava.microwakeword

import android.util.Log
import com.example.ava.openwakeword.OpenWakeWordDetector
import com.example.ava.openwakeword.OpenWakeWordProvider
import com.example.ava.settings.WakeWordEngine

object WakeWordDetectorFactory {
    private const val TAG = "WakeWordDetectorFactory"

    fun create(
        engine: WakeWordEngine,
        wakeWordProvider: WakeWordProvider,
        openWakeWordProvider: OpenWakeWordProvider?,
        vadProvider: WakeWordProvider? = null,
        learnStore: com.example.ava.wakelearn.WakeLearnStore? = null,
    ): WakeWordEngineDetector {
        return try {
            when (engine) {
                WakeWordEngine.MICRO_WAKE_WORD ->
                    WakeWordDetector(wakeWordProvider, vadProvider, learnStore)
                WakeWordEngine.OPEN_WAKE_WORD -> {
                    if (openWakeWordProvider == null) {
                        Log.e(TAG, "openWakeWord provider is null, using NoOp detector")
                        return NoOpWakeWordDetector("openWakeWord provider not available")
                    }
                    OpenWakeWordDetector(
                        provider = openWakeWordProvider,
                        vadProvider = vadProvider,
                    )
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library not available for $engine engine", e)
            NoOpWakeWordDetector("Native library not available: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create wake word detector for $engine engine", e)
            NoOpWakeWordDetector("Initialization failed: ${e.message}")
        }
    }
}
