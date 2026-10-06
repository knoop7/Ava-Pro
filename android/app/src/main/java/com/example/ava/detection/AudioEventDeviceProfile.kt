package com.example.ava.detection

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Runtime device tier for audio event detection.
 *
 * Weak devices get a slower classify floor, longer confirm windows, and slightly
 * stricter quality gates — layered on top of the user-facing sensitivity preset.
 */
object AudioEventDeviceProfile {
    enum class Tier {
        STANDARD,
        WEAK,
    }

    data class Timing(
        /** Floor for adaptive classify interval (ms). */
        val minClassifyIntervalMs: Long,
        val fastInferenceMs: Long,
        val slowInferenceMs: Long,
        /** Base confirm window; scaled up with adaptive interval at runtime. */
        val confirmWindowMs: Long,
        val confirmRequired: Int = 2,
    )

    fun resolveTier(context: Context): Tier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Tier.STANDARD
        val lowRam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            am.isLowRamDevice
        } else {
            false
        }
        return if (lowRam || am.memoryClass <= 192) Tier.WEAK else Tier.STANDARD
    }

    fun timing(tier: Tier): Timing = when (tier) {
        Tier.STANDARD -> Timing(
            minClassifyIntervalMs = 1_000L,
            fastInferenceMs = 800L,
            slowInferenceMs = 1_500L,
            confirmWindowMs = 4_000L,
        )
        Tier.WEAK -> Timing(
            minClassifyIntervalMs = 1_500L,
            fastInferenceMs = 800L,
            slowInferenceMs = 1_500L,
            confirmWindowMs = 6_000L,
        )
    }

    /** Layer conservative gates on weak hardware without overriding user sensitivity choice. */
    fun adjustThresholds(
        tier: Tier,
        base: AudioEventSensitivity.Thresholds,
    ): AudioEventSensitivity.Thresholds {
        if (tier == Tier.STANDARD) return base
        return base.copy(
            confirmStrongMin = (base.confirmStrongMin + 0.03f).coerceAtMost(0.95f),
            featureNonZeroMin = (base.featureNonZeroMin + 0.05f).coerceAtMost(0.55f),
        )
    }

    fun adaptiveIntervalMs(tier: Tier, lastInferenceMs: Long): Long {
        val timing = timing(tier)
        val fromInference = when {
            lastInferenceMs < timing.fastInferenceMs -> 1_000L
            lastInferenceMs < timing.slowInferenceMs -> 1_500L
            else -> 2_000L
        }
        return maxOf(timing.minClassifyIntervalMs, fromInference)
    }

    fun confirmWindowMs(tier: Tier, classifyIntervalMs: Long): Long {
        val base = timing(tier).confirmWindowMs
        return maxOf(base, classifyIntervalMs * 4)
    }
}
