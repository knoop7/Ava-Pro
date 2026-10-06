package com.example.ava.sensor

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Geometric tie-break for digits that $1 still confuses: 3/5/6/8/9.
 * 8 is only accepted when the stroke actually crosses itself.
 */
internal object DigitDisambiguator {
    fun refine(
        raw: List<GesturePoint>,
        match: GestureMatch,
        allowed: Set<String>,
    ): String? {
        if (!match.id.startsWith("digit_")) return match.id
        val f = features(raw)
        val picked = pick(f, match, allowed)
        return picked.takeIf { it in allowed }
    }

    private fun pick(
        f: StrokeFeatures,
        match: GestureMatch,
        allowed: Set<String>,
    ): String {
        if (f.crosses && f.crossY in 0.32f..0.68f && "digit_8" in allowed) return "digit_8"
        if (match.id == "digit_8" && !(f.crosses && f.crossY in 0.32f..0.68f)) {
            return openDigit(f, allowed, match.secondId)
        }

        val close = match.secondId != null && (match.score - match.secondScore) < 0.08f
        val confused = match.id in CONFUSABLE || match.secondId in CONFUSABLE
        if (close || confused) {
            return openDigit(f, allowed, match.id)
        }
        return match.id
    }

    private fun openDigit(
        f: StrokeFeatures,
        allowed: Set<String>,
        fallback: String?,
    ): String {
        val guessed = when {
            f.closed && "digit_0" in allowed -> "digit_0"
            f.startX > 0.55f && f.startY < 0.38f && f.firstDx < -0.25f && "digit_5" in allowed ->
                "digit_5"
            f.startX < 0.48f && f.startY < 0.40f && f.firstDx > 0.20f &&
                f.endX < 0.55f && f.endY > 0.55f && "digit_3" in allowed ->
                "digit_3"
            f.startY < 0.40f && f.startX > 0.38f && f.endY in 0.32f..0.78f &&
                f.endX < 0.62f && f.firstDy > 0.15f && "digit_6" in allowed ->
                "digit_6"
            f.startY < 0.55f && f.endY > 0.78f && f.endX > 0.35f && "digit_9" in allowed ->
                "digit_9"
            f.startX > 0.45f && f.startY < 0.35f && f.firstDx < 0f &&
                f.endY > 0.55f && "digit_5" in allowed ->
                "digit_5"
            f.startY < 0.35f && f.endY > 0.80f && "digit_9" in allowed -> "digit_9"
            f.startY < 0.35f && f.endY < 0.75f && f.firstDy > 0f && "digit_6" in allowed ->
                "digit_6"
            else -> fallback?.takeIf { it != "digit_8" && it in allowed }
        }
        return guessed ?: fallback?.takeIf { it in allowed && it != "digit_8" } ?: fallback ?: "digit_3"
    }

    private val CONFUSABLE = setOf("digit_3", "digit_5", "digit_6", "digit_8", "digit_9")

    private data class StrokeFeatures(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val firstDx: Float,
        val firstDy: Float,
        val crosses: Boolean,
        val crossY: Float,
        val closed: Boolean,
    )

    private fun features(raw: List<GesturePoint>): StrokeFeatures {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in raw) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val w = (maxX - minX).coerceAtLeast(1f)
        val h = (maxY - minY).coerceAtLeast(1f)
        val first = raw.first()
        val last = raw.last()
        val probe = raw[(raw.size * 0.18f).toInt().coerceIn(1, raw.size - 1)]
        val firstLen = hypot(probe.x - first.x, probe.y - first.y).coerceAtLeast(1f)
        val diag = hypot(w, h)
        val crossY = firstSelfIntersectionY(raw)
        return StrokeFeatures(
            startX = (first.x - minX) / w,
            startY = (first.y - minY) / h,
            endX = (last.x - minX) / w,
            endY = (last.y - minY) / h,
            firstDx = (probe.x - first.x) / firstLen,
            firstDy = (probe.y - first.y) / firstLen,
            crosses = crossY != null,
            crossY = if (crossY != null) (crossY - minY) / h else -1f,
            closed = hypot(last.x - first.x, last.y - first.y) / diag < 0.22f,
        )
    }

    private fun firstSelfIntersectionY(points: List<GesturePoint>): Float? {
        if (points.size < 8) return null
        val step = (points.size / 24).coerceAtLeast(1)
        val sampled = points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }
        for (i in 0 until sampled.size - 3) {
            val a = sampled[i]
            val b = sampled[i + 1]
            for (j in i + 2 until sampled.size - 1) {
                if (j == i + 2) continue
                if (segmentsCross(a, b, sampled[j], sampled[j + 1])) {
                    return (a.y + b.y + sampled[j].y + sampled[j + 1].y) / 4f
                }
            }
        }
        return null
    }

    private fun segmentsCross(
        a: GesturePoint,
        b: GesturePoint,
        c: GesturePoint,
        d: GesturePoint,
    ): Boolean {
        val d1 = cross(b.x - a.x, b.y - a.y, c.x - a.x, c.y - a.y)
        val d2 = cross(b.x - a.x, b.y - a.y, d.x - a.x, d.y - a.y)
        val d3 = cross(d.x - c.x, d.y - c.y, a.x - c.x, a.y - c.y)
        val d4 = cross(d.x - c.x, d.y - c.y, b.x - c.x, b.y - c.y)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f)) &&
            abs(d1) > 1e-3f && abs(d2) > 1e-3f
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float): Float = ax * by - ay * bx
}
