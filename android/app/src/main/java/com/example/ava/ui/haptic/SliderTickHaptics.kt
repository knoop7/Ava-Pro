package com.example.ava.ui.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/**
 * Short motor pulse for slider detents. Compose TextHandleMove / SegmentTick stay
 * silent on Android 12 and below when the device has an ERM motor but no haptic actuator.
 * No motor is a no-op.
 */
object SliderTickHaptics {
    private const val PULSE_MS = 16L

    fun tick(context: Context) {
        val vibrator = appVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(PULSE_MS)
        }
    }
}

/** True when [next] crossed a detent relative to [previous]. */
internal fun sliderTickChanged(
    previous: Float,
    next: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Boolean {
    if (previous == next) return false
    return sliderTickBucket(previous, range, steps) != sliderTickBucket(next, range, steps)
}

internal fun sliderTickBucket(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Int {
    if (steps > 0) {
        val span = (range.endInclusive - range.start).coerceAtLeast(0.0001f)
        val t = ((value - range.start) / span).coerceIn(0f, 1f)
        return (t * (steps + 1)).roundToInt()
    }
    val span = range.endInclusive - range.start
    return if (span >= 20f) value.roundToInt() else (value * 100f).roundToInt()
}

@Composable
fun rememberSliderTickHaptic(): () -> Unit {
    val context = LocalContext.current.applicationContext
    return remember(context) { { SliderTickHaptics.tick(context) } }
}
