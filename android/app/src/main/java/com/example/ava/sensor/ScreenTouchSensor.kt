package com.example.ava.sensor

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Touch-only activity for the optional HA [binary_sensor.screen_touch].
 * Separate from [com.example.ava.services.ScreensaverController] idle timing
 * so camera / voice / URL changes do not look like a finger on the glass.
 */
object ScreenTouchSensor {
    const val MIN_AWAY_DELAY_SECONDS = 2
    const val MAX_AWAY_DELAY_SECONDS = 60
    const val DEFAULT_AWAY_DELAY_SECONDS = 5

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _touched = MutableStateFlow(false)
    val touched: StateFlow<Boolean> = _touched.asStateFlow()

    @Volatile
    private var idleOffMs = DEFAULT_AWAY_DELAY_SECONDS * 1000L

    @Volatile
    private var lastTouchAtMs = 0L

    private val turnOff = Runnable { _touched.value = false }

    fun clampAwayDelaySeconds(seconds: Int): Int =
        seconds.coerceIn(MIN_AWAY_DELAY_SECONDS, MAX_AWAY_DELAY_SECONDS)

    fun setAwayDelaySeconds(seconds: Int) {
        idleOffMs = clampAwayDelaySeconds(seconds) * 1000L
        if (!_touched.value || lastTouchAtMs == 0L) return
        val remaining = idleOffMs - (SystemClock.uptimeMillis() - lastTouchAtMs)
        mainHandler.removeCallbacks(turnOff)
        if (remaining <= 0L) {
            _touched.value = false
        } else {
            mainHandler.postDelayed(turnOff, remaining)
        }
    }

    fun onUserTouch() {
        // Touch-only is exactly what a dark wake pulse needs to tell our own wake from a person's:
        // it can produce camera motion and proximity events, never a finger.
        com.example.ava.bluetooth.DarkWakePulse.onUserTouch()
        lastTouchAtMs = SystemClock.uptimeMillis()
        _touched.value = true
        mainHandler.removeCallbacks(turnOff)
        mainHandler.postDelayed(turnOff, idleOffMs)
    }
}
