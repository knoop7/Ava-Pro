package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class HomeLockSettings(
    /** When true, passcode protection is active for [lockTarget]. */
    val enabled: Boolean = false,
    /**
     * What the passcode protects: [HomeLockTarget.HOME] (default) or [HomeLockTarget.SETTINGS].
     * Missing / unknown values resolve to home so existing installs keep Home lock behavior.
     */
    val lockTarget: String = HomeLockTarget.HOME.storageKey,
    /** SHA-256 hex of salted PIN. Empty means use default PIN for [pinLength] on next enable. */
    val pinHash: String = "",
    /** Idle seconds on Home before the passcode overlay returns. Default 30. */
    val idleTimeoutSeconds: Int = HomeLockPin.DEFAULT_IDLE_TIMEOUT_SECONDS,
    /**
     * Passcode digit count: 4 or 6.
     * `0` / missing = legacy unset (resolved by [HomeLockPin.resolvedPinLength]).
     */
    val pinLength: Int = 0,
    /** When true, digit keys are shuffled to a new layout each time the pad is shown. */
    val shuffleKeypad: Boolean = false,
    /**
     * When true, 5 consecutive wrong unlock attempts lock the keypad;
     * wait escalates: 1 min → 5 min → 30 min → 1 hour.
     */
    val antiBruteForce: Boolean = false,
    /** Current consecutive wrong attempts toward the next lockout (0 until threshold). */
    val failedAttempts: Int = 0,
    /** How many lockouts have been applied; next wait = level minutes. */
    val lockoutLevel: Int = 0,
    /** Wall-clock millis when keypad lockout ends; 0 = not locked out. */
    val lockoutUntilEpochMs: Long = 0L,
)

/** Mutually exclusive passcode scope. Default [HOME] preserves legacy behavior. */
enum class HomeLockTarget(val storageKey: String) {
    HOME("home"),
    SETTINGS("settings"),
    ;

    companion object {
        fun fromStored(value: String?): HomeLockTarget =
            entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) } ?: HOME

        fun resolved(settings: HomeLockSettings): HomeLockTarget = fromStored(settings.lockTarget)
    }
}

object HomeLockPin {
    const val PIN_LENGTH_4 = 4
    const val PIN_LENGTH_6 = 6
    /** New installs / first enable default to 6 digits. */
    const val DEFAULT_PIN_LENGTH = PIN_LENGTH_6
    const val DEFAULT_PIN_4 = "0000"
    const val DEFAULT_PIN_6 = "000000"
    const val DEFAULT_IDLE_TIMEOUT_SECONDS = 30
    const val MIN_IDLE_TIMEOUT_SECONDS = 5
    const val MAX_IDLE_TIMEOUT_SECONDS = 3600
    val PIN_LENGTH_OPTIONS: List<Int> = listOf(PIN_LENGTH_4, PIN_LENGTH_6)
    private const val SALT = "ava.home.lock.v1|"

    fun defaultPinForLength(length: Int): String = when (clampPinLength(length)) {
        PIN_LENGTH_4 -> DEFAULT_PIN_4
        else -> DEFAULT_PIN_6
    }

    fun clampPinLength(length: Int): Int =
        if (length == PIN_LENGTH_4) PIN_LENGTH_4 else PIN_LENGTH_6

    /**
     * Legacy JSON without [HomeLockSettings.pinLength] kept a 4-digit hash;
     * brand-new settings (no hash yet) default to 6.
     */
    fun resolvedPinLength(settings: HomeLockSettings): Int = when (settings.pinLength) {
        PIN_LENGTH_4, PIN_LENGTH_6 -> settings.pinLength
        else -> if (settings.pinHash.isNotBlank()) PIN_LENGTH_4 else DEFAULT_PIN_LENGTH
    }

    fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((SALT + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun matches(pin: String, storedHash: String, pinLength: Int): Boolean {
        val length = clampPinLength(pinLength)
        val expected = storedHash.ifBlank { hash(defaultPinForLength(length)) }
        return hash(pin) == expected
    }

    fun isValidFormat(pin: String, pinLength: Int): Boolean {
        val length = clampPinLength(pinLength)
        return pin.length == length && pin.all { it.isDigit() }
    }

    fun clampIdleTimeoutSeconds(seconds: Int): Int {
        if (seconds <= 0) return DEFAULT_IDLE_TIMEOUT_SECONDS
        return seconds.coerceIn(MIN_IDLE_TIMEOUT_SECONDS, MAX_IDLE_TIMEOUT_SECONDS)
    }

    const val ANTI_BRUTE_MAX_ATTEMPTS = 5
    const val ANTI_BRUTE_MAX_LOCKOUT_LEVEL = 4

    /** Level 1→1m, 2→5m, 3→30m, 4+→1h. */
    fun lockoutDurationMs(level: Int): Long = when (level.coerceIn(1, ANTI_BRUTE_MAX_LOCKOUT_LEVEL)) {
        1 -> 60_000L
        2 -> 5 * 60_000L
        3 -> 30 * 60_000L
        else -> 60 * 60_000L
    }

    fun isKeypadLockedOut(settings: HomeLockSettings, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        settings.antiBruteForce && settings.lockoutUntilEpochMs > nowEpochMs

    fun remainingLockoutMs(settings: HomeLockSettings, nowEpochMs: Long = System.currentTimeMillis()): Long =
        (settings.lockoutUntilEpochMs - nowEpochMs).coerceAtLeast(0L)
}

val Context.homeLockSettingsStore: DataStore<HomeLockSettings> by dataStore(
    fileName = "home_lock_settings.json",
    serializer = SettingsSerializer(HomeLockSettings.serializer(), HomeLockSettings()),
    corruptionHandler = defaultCorruptionHandler(HomeLockSettings()),
)

class HomeLockSettingsStore(dataStore: DataStore<HomeLockSettings>) :
    SettingsStoreImpl<HomeLockSettings>(dataStore, HomeLockSettings()) {

    val enabled = SettingState(getFlow().map { it.enabled }) { value ->
        update { current -> applyEnable(current, value) }
    }

    suspend fun setEnabled(enabled: Boolean) {
        update { current -> applyEnable(current, enabled) }
    }

    suspend fun setPin(newPin: String) {
        update { current ->
            val length = HomeLockPin.resolvedPinLength(current)
            require(HomeLockPin.isValidFormat(newPin, length)) {
                "PIN must be $length digits"
            }
            current.copy(
                pinHash = HomeLockPin.hash(newPin),
                pinLength = length,
            )
        }
    }

    /** Changing length resets the passcode to that length's default (0000 / 000000). */
    suspend fun setPinLength(length: Int) {
        val clamped = HomeLockPin.clampPinLength(length)
        update { current ->
            if (HomeLockPin.resolvedPinLength(current) == clamped) {
                current.copy(pinLength = clamped)
            } else {
                current.copy(
                    pinLength = clamped,
                    pinHash = HomeLockPin.hash(HomeLockPin.defaultPinForLength(clamped)),
                )
            }
        }
    }

    suspend fun setIdleTimeoutSeconds(seconds: Int) {
        val clamped = HomeLockPin.clampIdleTimeoutSeconds(seconds)
        update { it.copy(idleTimeoutSeconds = clamped) }
    }

    suspend fun setShuffleKeypad(enabled: Boolean) {
        update { it.copy(shuffleKeypad = enabled) }
    }

    suspend fun setAntiBruteForce(enabled: Boolean) {
        update { current ->
            if (enabled) current.copy(antiBruteForce = true)
            else current.copy(
                antiBruteForce = false,
                failedAttempts = 0,
                lockoutLevel = 0,
                lockoutUntilEpochMs = 0L,
            )
        }
    }

    suspend fun setLockTarget(target: HomeLockTarget) {
        update { it.copy(lockTarget = target.storageKey) }
    }

    /** Returns true if this failure triggered a new keypad lockout. */
    suspend fun recordFailedUnlockAttempt(): Boolean {
        var triggered = false
        update { current ->
            if (!current.antiBruteForce) return@update current
            val now = System.currentTimeMillis()
            if (current.lockoutUntilEpochMs > now) return@update current
            val attempts = current.failedAttempts + 1
            if (attempts < HomeLockPin.ANTI_BRUTE_MAX_ATTEMPTS) {
                current.copy(failedAttempts = attempts)
            } else {
                triggered = true
                val level = (current.lockoutLevel + 1)
                    .coerceAtMost(HomeLockPin.ANTI_BRUTE_MAX_LOCKOUT_LEVEL)
                current.copy(
                    failedAttempts = 0,
                    lockoutLevel = level,
                    lockoutUntilEpochMs = now + HomeLockPin.lockoutDurationMs(level),
                )
            }
        }
        return triggered
    }

    suspend fun clearBruteForceOnSuccess() {
        update {
            it.copy(
                failedAttempts = 0,
                lockoutLevel = 0,
                lockoutUntilEpochMs = 0L,
            )
        }
    }

    private fun applyEnable(current: HomeLockSettings, enabled: Boolean): HomeLockSettings {
        if (!enabled) return current.copy(enabled = false)
        val length = HomeLockPin.resolvedPinLength(current).let { resolved ->
            if (current.pinLength == 0 && current.pinHash.isBlank()) {
                HomeLockPin.DEFAULT_PIN_LENGTH
            } else {
                resolved
            }
        }
        return if (current.pinHash.isBlank()) {
            current.copy(
                enabled = true,
                pinLength = length,
                pinHash = HomeLockPin.hash(HomeLockPin.defaultPinForLength(length)),
            )
        } else {
            current.copy(enabled = true, pinLength = length)
        }
    }
}
