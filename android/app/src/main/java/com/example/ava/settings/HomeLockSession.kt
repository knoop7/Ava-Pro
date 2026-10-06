package com.example.ava.settings

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory passcode session.
 * Survives Activity recreation (rotation); resets only on process death or [lock]/[unlock].
 *
 * [HomeLockTarget.HOME]: idle timeout only counts while Home is foregrounded. Leaving Home for
 * an in-app route (e.g. Settings) pauses the clock and restarts it when Home is shown again.
 *
 * [HomeLockTarget.SETTINGS]: Home stays unlocked; entering a settings-like route requires PIN.
 * Leaving the settings tree re-locks for the next entry.
 */
object HomeLockSession {
    const val DEFAULT_IDLE_LOCK_MS =
        HomeLockPin.DEFAULT_IDLE_TIMEOUT_SECONDS * 1000L

    private val _featureEnabled = MutableStateFlow(false)
    private val _lockTarget = MutableStateFlow(HomeLockTarget.HOME)
    private val _pinHash = MutableStateFlow("")
    private val _pinLength = MutableStateFlow(HomeLockPin.DEFAULT_PIN_LENGTH)
    private val _shuffleKeypad = MutableStateFlow(false)
    private val _antiBruteForce = MutableStateFlow(false)
    private val _unlocked = MutableStateFlow(false)
    private val _idleTimeoutMs = MutableStateFlow(DEFAULT_IDLE_LOCK_MS)

    val featureEnabled: StateFlow<Boolean> = _featureEnabled.asStateFlow()
    val lockTarget: StateFlow<HomeLockTarget> = _lockTarget.asStateFlow()
    val pinHash: StateFlow<String> = _pinHash.asStateFlow()
    val pinLength: StateFlow<Int> = _pinLength.asStateFlow()
    val shuffleKeypad: StateFlow<Boolean> = _shuffleKeypad.asStateFlow()
    val antiBruteForce: StateFlow<Boolean> = _antiBruteForce.asStateFlow()
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    val idleTimeoutMs: StateFlow<Long> = _idleTimeoutMs.asStateFlow()

    @Volatile
    private var lastInteractionElapsedMs: Long = 0L

    /**
     * True when Home was left because the user navigated to another in-app destination
     * (Settings, etc.). Cleared on the next Home resume after restarting the idle clock.
     */
    @Volatile
    private var idlePausedForInAppNav: Boolean = false

    fun locksHome(): Boolean =
        _featureEnabled.value && _lockTarget.value == HomeLockTarget.HOME

    fun locksSettings(): Boolean =
        _featureEnabled.value && _lockTarget.value == HomeLockTarget.SETTINGS

    /** Keep last known settings so rotation does not flash through enabled=false defaults. */
    fun syncFromSettings(settings: HomeLockSettings) {
        _featureEnabled.value = settings.enabled
        _lockTarget.value = HomeLockTarget.resolved(settings)
        _pinHash.value = settings.pinHash
        _pinLength.value = HomeLockPin.resolvedPinLength(settings)
        _shuffleKeypad.value = settings.shuffleKeypad
        _antiBruteForce.value = settings.antiBruteForce
        _idleTimeoutMs.value =
            HomeLockPin.clampIdleTimeoutSeconds(settings.idleTimeoutSeconds) * 1000L
        if (!settings.enabled) {
            _unlocked.value = true
            idlePausedForInAppNav = false
        }
    }

    fun unlock() {
        noteInteraction()
        idlePausedForInAppNav = false
        _unlocked.value = true
    }

    fun lock() {
        if (!_featureEnabled.value) {
            _unlocked.value = true
            return
        }
        idlePausedForInAppNav = false
        _unlocked.value = false
    }

    /** Skip locking while the Activity is recreating for a configuration change. */
    fun lockUnlessChangingConfigurations(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        lock()
    }

    fun noteInteraction() {
        lastInteractionElapsedMs = SystemClock.elapsedRealtime()
    }

    /**
     * @param leftForInAppNavigation true when Home stopped because another NavHost route
     * became current (Settings path). false when the app went to background while still on Home.
     */
    fun onHomeStopped(leftForInAppNavigation: Boolean) {
        if (!locksHome() || !_unlocked.value) return
        if (leftForInAppNavigation) {
            idlePausedForInAppNav = true
        }
    }

    /**
     * Call from Home [Lifecycle.Event.ON_RESUME].
     * In-app return → restart idle countdown (no lock from time spent away).
     * App resume while still on Home → lock only if wall-clock idle timed out.
     */
    fun onHomeResumed() {
        if (!locksHome()) return
        if (!_unlocked.value) return
        if (idlePausedForInAppNav) {
            idlePausedForInAppNav = false
            noteInteraction()
            return
        }
        if (idleTimedOut()) {
            lock()
        } else {
            noteInteraction()
        }
    }

    fun idleTimedOut(): Boolean {
        if (!locksHome() || !_unlocked.value) return false
        // Time away in Settings (etc.) must not count as Home idle.
        if (idlePausedForInAppNav) return false
        // 0 = not initialized yet (avoid locking before first unlock / interaction).
        if (lastInteractionElapsedMs == 0L) return false
        return SystemClock.elapsedRealtime() - lastInteractionElapsedMs >= _idleTimeoutMs.value
    }

    /** Home-scope overlay. Settings-scope never covers Home. */
    fun shouldShowOverlay(): Boolean = locksHome() && !_unlocked.value

    /** Settings-scope overlay while on a settings-like route. */
    fun shouldShowSettingsOverlay(): Boolean = locksSettings() && !_unlocked.value
}
