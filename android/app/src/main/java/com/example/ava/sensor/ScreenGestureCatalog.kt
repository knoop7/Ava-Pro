package com.example.ava.sensor

import com.example.ava.R
import com.example.ava.settings.ExperimentalSettings

enum class ScreenGestureCategory {
    Spatial,
    Digits,
    Geometry,
}

data class ScreenGestureToken(
    val id: String,
    val category: ScreenGestureCategory,
    val labelRes: Int,
)

object ScreenGestureCatalog {
    const val IDLE = "idle"
    const val COOLDOWN_MS = 2000L

    val SPATIAL_TOKENS: List<ScreenGestureToken> = listOf(
        ScreenGestureToken("five_finger", ScreenGestureCategory.Spatial, R.string.gesture_five_finger),
        ScreenGestureToken("pinch_in", ScreenGestureCategory.Spatial, R.string.gesture_pinch_in),
        ScreenGestureToken("pinch_out", ScreenGestureCategory.Spatial, R.string.gesture_pinch_out),
        ScreenGestureToken("corner_tl", ScreenGestureCategory.Spatial, R.string.gesture_corner_tl),
        ScreenGestureToken("corner_tr", ScreenGestureCategory.Spatial, R.string.gesture_corner_tr),
        ScreenGestureToken("corner_bl", ScreenGestureCategory.Spatial, R.string.gesture_corner_bl),
        ScreenGestureToken("corner_br", ScreenGestureCategory.Spatial, R.string.gesture_corner_br),
        ScreenGestureToken("corner_tl_swipe", ScreenGestureCategory.Spatial, R.string.gesture_corner_tl_swipe),
        ScreenGestureToken("corner_tr_swipe", ScreenGestureCategory.Spatial, R.string.gesture_corner_tr_swipe),
        ScreenGestureToken("corner_bl_swipe", ScreenGestureCategory.Spatial, R.string.gesture_corner_bl_swipe),
        ScreenGestureToken("corner_br_swipe", ScreenGestureCategory.Spatial, R.string.gesture_corner_br_swipe),
        ScreenGestureToken("swipe_up", ScreenGestureCategory.Spatial, R.string.gesture_swipe_up),
        ScreenGestureToken("swipe_down", ScreenGestureCategory.Spatial, R.string.gesture_swipe_down),
        ScreenGestureToken("swipe_left", ScreenGestureCategory.Spatial, R.string.gesture_swipe_left),
        ScreenGestureToken("swipe_right", ScreenGestureCategory.Spatial, R.string.gesture_swipe_right),
    )

    val DIGIT_TOKENS: List<ScreenGestureToken> = listOf(
        ScreenGestureToken("digit_0", ScreenGestureCategory.Digits, R.string.gesture_digit_0),
        ScreenGestureToken("digit_1", ScreenGestureCategory.Digits, R.string.gesture_digit_1),
        ScreenGestureToken("digit_2", ScreenGestureCategory.Digits, R.string.gesture_digit_2),
        ScreenGestureToken("digit_3", ScreenGestureCategory.Digits, R.string.gesture_digit_3),
        ScreenGestureToken("digit_4", ScreenGestureCategory.Digits, R.string.gesture_digit_4),
        ScreenGestureToken("digit_5", ScreenGestureCategory.Digits, R.string.gesture_digit_5),
        ScreenGestureToken("digit_6", ScreenGestureCategory.Digits, R.string.gesture_digit_6),
        ScreenGestureToken("digit_7", ScreenGestureCategory.Digits, R.string.gesture_digit_7),
        ScreenGestureToken("digit_8", ScreenGestureCategory.Digits, R.string.gesture_digit_8),
        ScreenGestureToken("digit_9", ScreenGestureCategory.Digits, R.string.gesture_digit_9),
    )

    val GEOMETRY_TOKENS: List<ScreenGestureToken> = listOf(
        ScreenGestureToken("triangle", ScreenGestureCategory.Geometry, R.string.gesture_triangle),
        ScreenGestureToken("rectangle", ScreenGestureCategory.Geometry, R.string.gesture_rectangle),
        ScreenGestureToken("check", ScreenGestureCategory.Geometry, R.string.gesture_check),
        ScreenGestureToken("x", ScreenGestureCategory.Geometry, R.string.gesture_x),
        ScreenGestureToken("heart", ScreenGestureCategory.Geometry, R.string.gesture_heart),
        ScreenGestureToken("wave", ScreenGestureCategory.Geometry, R.string.gesture_wave),
    )

    val ALL_TOKENS: List<ScreenGestureToken> = SPATIAL_TOKENS + DIGIT_TOKENS + GEOMETRY_TOKENS
    val ALL_IDS: Set<String> = ALL_TOKENS.map { it.id }.toSet()
    val ALL_SPATIAL_IDS: Set<String> = SPATIAL_TOKENS.map { it.id }.toSet()
    val ALL_DIGIT_IDS: Set<String> = DIGIT_TOKENS.map { it.id }.toSet()
    val ALL_GEOMETRY_IDS: Set<String> = GEOMETRY_TOKENS.map { it.id }.toSet()

    val DEFAULT_SPATIAL_IDS: Set<String> = setOf(
        "corner_tl", "corner_tr", "corner_bl", "corner_br",
    )

    val CONSUME_IDS: Set<String> = setOf("five_finger", "pinch_in", "pinch_out")

    val VERTICAL_SWIPE_IDS: Set<String> = setOf("swipe_up", "swipe_down")

    /** Same vertical stroke: up/down swipe and digit 1. */
    fun opponentsOf(token: String): Set<String> = when (token) {
        "swipe_up", "swipe_down" -> setOf("digit_1")
        "digit_1" -> VERTICAL_SWIPE_IDS
        else -> emptySet()
    }

    fun categoryOf(token: String): ScreenGestureCategory? = when {
        token in ALL_SPATIAL_IDS -> ScreenGestureCategory.Spatial
        token in ALL_DIGIT_IDS -> ScreenGestureCategory.Digits
        token in ALL_GEOMETRY_IDS -> ScreenGestureCategory.Geometry
        else -> null
    }

    fun tokensFor(category: ScreenGestureCategory): List<ScreenGestureToken> = when (category) {
        ScreenGestureCategory.Spatial -> SPATIAL_TOKENS
        ScreenGestureCategory.Digits -> DIGIT_TOKENS
        ScreenGestureCategory.Geometry -> GEOMETRY_TOKENS
    }

    fun defaultIdsFor(category: ScreenGestureCategory): Set<String> = when (category) {
        ScreenGestureCategory.Spatial -> DEFAULT_SPATIAL_IDS
        ScreenGestureCategory.Digits -> ALL_DIGIT_IDS
        ScreenGestureCategory.Geometry -> ALL_GEOMETRY_IDS
    }

    fun sanitize(ids: Set<String>, category: ScreenGestureCategory): Set<String> {
        val allowed = when (category) {
            ScreenGestureCategory.Spatial -> ALL_SPATIAL_IDS
            ScreenGestureCategory.Digits -> ALL_DIGIT_IDS
            ScreenGestureCategory.Geometry -> ALL_GEOMETRY_IDS
        }
        return ids.intersect(allowed)
    }

    fun resolvedTokens(settings: ExperimentalSettings): Set<String> {
        if (!settings.screenGestureEnabled) return emptySet()
        val out = linkedSetOf<String>()
        if (settings.screenGestureSpatialEnabled) {
            out += resolvedCategoryTokens(settings.screenGestureSpatialTokens, ScreenGestureCategory.Spatial)
        }
        if (settings.screenGestureDigitsEnabled) {
            out += resolvedCategoryTokens(settings.screenGestureDigitTokens, ScreenGestureCategory.Digits)
        }
        if (settings.screenGestureGeometryEnabled) {
            out += resolvedCategoryTokens(settings.screenGestureGeometryTokens, ScreenGestureCategory.Geometry)
        }
        return out
    }

    fun resolvedCategoryTokens(
        stored: Set<String>,
        category: ScreenGestureCategory,
    ): Set<String> {
        val clean = sanitize(stored, category)
        if (clean.isEmpty()) return defaultIdsFor(category)
        // Pre-1 tokens were 0 and 2–9; treat that full legacy set as “all digits”.
        if (category == ScreenGestureCategory.Digits &&
            clean.containsAll(ALL_DIGIT_IDS - setOf("digit_1"))
        ) {
            return clean + "digit_1"
        }
        return clean
    }
}
