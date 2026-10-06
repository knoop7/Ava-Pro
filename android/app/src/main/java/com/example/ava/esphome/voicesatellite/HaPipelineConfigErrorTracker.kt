package com.example.ava.esphome.voicesatellite

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Toast stays with the caller. The full-screen pipeline tip waits for
 * [THRESHOLD] consecutive upstream misses (HA line down, no subscribe,
 * timeout, or a permanent STT/TTS/agent code). A live STT or a fresh
 * subscribe clears the streak. "Got it" also clears it.
 *
 * Wake-wizard teaching is a different overlay and is not gated here.
 */
object HaPipelineConfigErrorTracker {

    private const val TAG = "HaPipelineConfigErr"
    internal const val THRESHOLD = 3

    const val HA_DISCONNECTED = "ha-disconnected"
    const val HA_NOT_SUBSCRIBED = "ha-not-subscribed"

    /** HA prepare/lookup codes that indicate pipeline engines are misconfigured. */
    private val PERMANENT_CODES = setOf(
        "stt-provider-missing",
        "stt-provider-unsupported-metadata",
        "tts-not-supported",
        "intent-not-supported",
        "intent-agent-not-found",
        "validation-error",
        "pipeline_not_found",
    )

    private val LINE_MISS = setOf(HA_DISCONNECTED, HA_NOT_SUBSCRIBED)

    private val consecutive = AtomicInteger(0)
    private val tipShownThisStreak = AtomicBoolean(false)

    fun isPermanentConfigCode(code: String): Boolean =
        code.trim().lowercase() in PERMANENT_CODES

    /** Call after toast. Shows the big tip only when the streak reaches [THRESHOLD]. */
    fun onPipelineError(context: Context, code: String) {
        if (!note(code)) return
        if (consecutive.get() < THRESHOLD) return
        if (tipShownThisStreak.get()) return
        com.example.ava.services.SatelliteSetupTipOverlayService
            .maybeShowPipelineConfigTip(context)
    }

    /** @return true when this code added to the streak. */
    internal fun note(code: String): Boolean {
        val c = code.trim().lowercase()
        if (c.startsWith("stt-no-text")) {
            onPipelineRecovered()
            return false
        }
        if (c.startsWith("wake") || c.startsWith("duplicate")) return false
        if (!countsTowardTip(c)) return false
        val count = consecutive.incrementAndGet()
        Log.d(TAG, "Upstream miss streak=$count code=$c")
        return true
    }

    /** Successful speech recognition (or a live HA subscribe) clears the streak. */
    fun onPipelineRecovered() {
        if (consecutive.get() == 0 && !tipShownThisStreak.get()) return
        consecutive.set(0)
        tipShownThisStreak.set(false)
        Log.d(TAG, "Upstream miss streak cleared")
    }

    /** Overlay actually started — do not re-show until dismiss or recovery. */
    fun onTipShown() {
        tipShownThisStreak.set(true)
    }

    /** User dismissed the tip — allow a fresh streak later. */
    fun onTipDismissed() {
        consecutive.set(0)
        tipShownThisStreak.set(false)
    }

    internal fun debugStreak(): Int = consecutive.get()

    internal fun resetForTest() {
        consecutive.set(0)
        tipShownThisStreak.set(false)
    }

    private fun countsTowardTip(code: String): Boolean {
        if (code in PERMANENT_CODES || code in LINE_MISS) return true
        if (code.contains("timeout") || code.contains("timed-out")) return true
        if (code.startsWith("stt-") || code.startsWith("intent-") || code.startsWith("tts")) return true
        return false
    }
}
