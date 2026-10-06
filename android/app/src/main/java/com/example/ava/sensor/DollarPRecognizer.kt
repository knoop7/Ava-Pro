package com.example.ava.sensor

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

internal data class GesturePoint(val x: Float, val y: Float)

internal data class GestureTemplate(
    val id: String,
    val points: List<GesturePoint>,
    val rotate: Boolean,
    val ordered: Boolean,
)

internal data class GestureMatch(
    val id: String,
    val score: Float,
    val secondId: String? = null,
    val secondScore: Float = 0f,
)

/**
 * $P cloud match for rotatable shapes; ordered $1 + uniform scale for digits.
 */
internal object DollarPRecognizer {
    private const val N = 32
    private const val SQUARE = 250f
    private const val CLOUD_THRESHOLD = 0.72f
    private const val ORDERED_THRESHOLD = 0.70f

    fun recognize(
        raw: List<GesturePoint>,
        templates: List<GestureTemplate>,
    ): GestureMatch? {
        if (raw.size < 8 || templates.isEmpty()) return null
        var bestId: String? = null
        var bestScore = 0f
        var secondId: String? = null
        var secondScore = 0f
        for (template in templates) {
            val candidate = normalize(raw, template.rotate, template.ordered)
            val score = if (template.ordered) {
                orderedSimilarity(candidate, template.points)
            } else {
                cloudSimilarity(candidate, template.points)
            }
            if (score > bestScore) {
                secondId = bestId
                secondScore = bestScore
                bestScore = score
                bestId = template.id
            } else if (score > secondScore) {
                secondScore = score
                secondId = template.id
            }
        }
        val id = bestId ?: return null
        val minScore = if (templates.any { it.id == id && it.ordered }) ORDERED_THRESHOLD else CLOUD_THRESHOLD
        if (bestScore < minScore) return null
        return GestureMatch(id, bestScore, secondId, secondScore)
    }

    fun bake(
        id: String,
        raw: List<GesturePoint>,
        rotate: Boolean,
        ordered: Boolean = !rotate,
    ): GestureTemplate =
        GestureTemplate(id, normalize(raw, rotate, ordered), rotate, ordered)

    private fun normalize(
        raw: List<GesturePoint>,
        rotate: Boolean,
        uniform: Boolean,
    ): List<GesturePoint> {
        var pts = resample(raw, N)
        if (rotate) pts = rotateToZero(pts)
        pts = if (uniform) scaleUniform(pts, SQUARE) else scaleToSquare(pts, SQUARE)
        return translateToOrigin(pts)
    }

    private fun cloudSimilarity(a: List<GesturePoint>, b: List<GesturePoint>): Float {
        val d = min(cloudDistance(a, b), cloudDistance(b, a))
        return distanceToScore(d)
    }

    private fun orderedSimilarity(a: List<GesturePoint>, b: List<GesturePoint>): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0f
        var sum = 0f
        for (i in 0 until n) sum += dist(a[i], b[i])
        return distanceToScore(sum / n)
    }

    private fun distanceToScore(d: Float): Float {
        val halfDiag = 0.5f * hypot(SQUARE, SQUARE)
        return ((halfDiag - d) / halfDiag).coerceIn(0f, 1f)
    }

    private fun cloudDistance(pts1: List<GesturePoint>, pts2: List<GesturePoint>): Float {
        val n = min(pts1.size, pts2.size)
        if (n == 0) return Float.MAX_VALUE
        val used = BooleanArray(n)
        var sum = 0f
        for (i in 0 until n) {
            var best = Float.MAX_VALUE
            var bestJ = 0
            for (j in 0 until n) {
                if (used[j]) continue
                val d = dist(pts1[i], pts2[j])
                if (d < best) {
                    best = d
                    bestJ = j
                }
            }
            used[bestJ] = true
            sum += best
        }
        return sum / n
    }

    private fun resample(points: List<GesturePoint>, n: Int): List<GesturePoint> {
        if (points.size < 2) return points
        val interval = pathLength(points) / (n - 1)
        if (interval <= 0f) return List(n) { points.first() }
        val out = ArrayList<GesturePoint>(n)
        out.add(points.first())
        var distAccum = 0f
        var prev = points.first()
        var i = 1
        while (i < points.size && out.size < n) {
            val current = points[i]
            val d = dist(prev, current)
            if (distAccum + d >= interval) {
                val t = (interval - distAccum) / d
                val nx = prev.x + t * (current.x - prev.x)
                val ny = prev.y + t * (current.y - prev.y)
                val added = GesturePoint(nx, ny)
                out.add(added)
                prev = added
                distAccum = 0f
            } else {
                distAccum += d
                prev = current
                i++
            }
        }
        while (out.size < n) out.add(points.last())
        return out
    }

    private fun rotateToZero(points: List<GesturePoint>): List<GesturePoint> {
        val c = centroid(points)
        val first = points.first()
        val angle = kotlin.math.atan2((first.y - c.y).toDouble(), (first.x - c.x).toDouble()).toFloat()
        return rotateBy(points, -angle, c)
    }

    private fun rotateBy(
        points: List<GesturePoint>,
        angle: Float,
        center: GesturePoint,
    ): List<GesturePoint> {
        val ca = cos(angle)
        val sa = sin(angle)
        return points.map { p ->
            val x = p.x - center.x
            val y = p.y - center.y
            GesturePoint(x * ca - y * sa + center.x, x * sa + y * ca + center.y)
        }
    }

    private fun scaleToSquare(points: List<GesturePoint>, size: Float): List<GesturePoint> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val w = (maxX - minX).coerceAtLeast(1f)
        val h = (maxY - minY).coerceAtLeast(1f)
        return points.map { GesturePoint((it.x - minX) * (size / w), (it.y - minY) * (size / h)) }
    }

    private fun scaleUniform(points: List<GesturePoint>, size: Float): List<GesturePoint> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val scale = size / (maxX - minX).coerceAtLeast(maxY - minY).coerceAtLeast(1f)
        return points.map { GesturePoint((it.x - minX) * scale, (it.y - minY) * scale) }
    }

    private fun translateToOrigin(points: List<GesturePoint>): List<GesturePoint> {
        val c = centroid(points)
        return points.map { GesturePoint(it.x - c.x, it.y - c.y) }
    }

    private fun centroid(points: List<GesturePoint>): GesturePoint {
        var sx = 0f
        var sy = 0f
        for (p in points) {
            sx += p.x
            sy += p.y
        }
        val n = points.size.coerceAtLeast(1)
        return GesturePoint(sx / n, sy / n)
    }

    private fun pathLength(points: List<GesturePoint>): Float {
        var sum = 0f
        for (i in 1 until points.size) sum += dist(points[i - 1], points[i])
        return sum
    }

    private fun dist(a: GesturePoint, b: GesturePoint): Float = hypot(a.x - b.x, a.y - b.y)
}
