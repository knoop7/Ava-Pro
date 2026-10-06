package com.example.ava.sensor

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Short-lived HA text sensor for recognized screen gestures.
 * Emits a token, holds it for [ScreenGestureCatalog.COOLDOWN_MS], then returns to idle.
 */
object ScreenGestureSensor {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(ScreenGestureCatalog.IDLE)
    val state: StateFlow<String> = _state.asStateFlow()

    @Volatile
    private var cooling = false

    private val returnIdle = Runnable {
        cooling = false
        _state.value = ScreenGestureCatalog.IDLE
    }

    fun reset() {
        mainHandler.removeCallbacks(returnIdle)
        cooling = false
        _state.value = ScreenGestureCatalog.IDLE
    }

    fun emit(token: String): Boolean {
        if (token.isBlank() || token == ScreenGestureCatalog.IDLE) return false
        if (cooling) return false
        cooling = true
        _state.value = token
        mainHandler.removeCallbacks(returnIdle)
        mainHandler.postDelayed(returnIdle, ScreenGestureCatalog.COOLDOWN_MS)
        return true
    }
}
