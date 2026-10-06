package com.example.ava.ui.haptic

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Overlay / window haptics with three strengths. View feedback is tried first
 * (same CLOCK_TICK / VIRTUAL_KEY / LONG_PRESS split as the wake button). If the
 * window cannot play it — common on accessibility overlays — fall back to the
 * motor with API-correct calls from Android 5 through 17.
 */
object OverlayHaptics {
    enum class Intensity { TICK, CLICK, HEAVY }

    fun tick(context: Context, view: View? = null) =
        play(context, Intensity.TICK, view)

    fun click(context: Context, view: View? = null) =
        play(context, Intensity.CLICK, view)

    fun heavy(context: Context, view: View? = null) =
        play(context, Intensity.HEAVY, view)

    fun play(context: Context, intensity: Intensity, view: View? = null) {
        if (intensity == Intensity.TICK && !tickGate()) return
        val attached = view?.takeIf { it.isAttachedToWindow }
        if (attached != null) {
            try {
                if (attached.performHapticFeedback(overlayHapticConstant(intensity))) return
            } catch (_: Exception) {
            }
        }
        vibrate(context, intensity)
    }

    private fun vibrate(context: Context, intensity: Intensity) {
        val vibrator = appVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = oneShot(vibrator, intensity)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(
                        effect,
                        VibrationAttributes.Builder()
                            .setUsage(VibrationAttributes.USAGE_TOUCH)
                            .build(),
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(
                        effect,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                } else {
                    vibrator.vibrate(effect)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(overlayHapticDurationMs(intensity))
            }
        } catch (_: Exception) {
        }
    }

    private fun oneShot(vibrator: Vibrator, intensity: Intensity): VibrationEffect {
        val amplitude =
            if (vibrator.hasAmplitudeControl()) overlayHapticAmplitude(intensity)
            else VibrationEffect.DEFAULT_AMPLITUDE
        return VibrationEffect.createOneShot(overlayHapticDurationMs(intensity), amplitude)
    }

    private fun tickGate(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastTickAt < TICK_GAP_MS) return false
        lastTickAt = now
        return true
    }

    @Volatile
    private var lastTickAt = 0L
    private const val TICK_GAP_MS = 32L
}

internal fun overlayHapticDurationMs(intensity: OverlayHaptics.Intensity): Long = when (intensity) {
    OverlayHaptics.Intensity.TICK -> 12L
    OverlayHaptics.Intensity.CLICK -> 16L
    OverlayHaptics.Intensity.HEAVY -> 24L
}

internal fun overlayHapticAmplitude(intensity: OverlayHaptics.Intensity): Int = when (intensity) {
    OverlayHaptics.Intensity.TICK -> 48
    OverlayHaptics.Intensity.CLICK -> 120
    OverlayHaptics.Intensity.HEAVY -> 200
}

internal fun overlayHapticConstant(intensity: OverlayHaptics.Intensity): Int = when (intensity) {
    OverlayHaptics.Intensity.TICK -> HapticFeedbackConstants.CLOCK_TICK
    OverlayHaptics.Intensity.CLICK -> HapticFeedbackConstants.VIRTUAL_KEY
    OverlayHaptics.Intensity.HEAVY -> HapticFeedbackConstants.LONG_PRESS
}

/** Press is HEAVY, first slide detent is CLICK, the rest are TICK — same 5–17 split. */
internal fun overlayScrollHapticIntensity(step: Int): OverlayHaptics.Intensity = when {
    step <= 0 -> OverlayHaptics.Intensity.HEAVY
    step == 1 -> OverlayHaptics.Intensity.CLICK
    else -> OverlayHaptics.Intensity.TICK
}

/** True when accumulated travel crossed another [stepPx] detent. */
internal fun travelTickChanged(previousPx: Float, nextPx: Float, stepPx: Float): Boolean {
    if (stepPx <= 0f || previousPx == nextPx) return false
    if (nextPx < stepPx && previousPx < stepPx) return false
    return (previousPx / stepPx).toInt() != (nextPx / stepPx).toInt()
}

internal fun appVibrator(context: Context): Vibrator? {
    val app = context.applicationContext
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}
