package com.example.ava.sensor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object ScreenGestureTemplates {
    val all: List<GestureTemplate> by lazy {
        listOf(
            DollarPRecognizer.bake("triangle", triangle(), rotate = true),
            DollarPRecognizer.bake("rectangle", rectangle(), rotate = true),
            DollarPRecognizer.bake("check", check(), rotate = true),
            DollarPRecognizer.bake("x", cross(), rotate = true),
            DollarPRecognizer.bake("heart", heart(), rotate = true),
            DollarPRecognizer.bake("wave", wave(2), rotate = false, ordered = true),
            DollarPRecognizer.bake("wave", wave(3), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_0", digit0(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_2", digit2(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_3", digit3(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_3", digit3Round(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_4", digit4(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_5", digit5(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_5", digit5S(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_6", digit6(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_6", digit6Hook(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_7", digit7(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_8", digit8(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_8", digit8FromTop(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_9", digit9(), rotate = false, ordered = true),
            DollarPRecognizer.bake("digit_9", digit9Tail(), rotate = false, ordered = true),
        )
    }

    private fun samplePath(vertices: List<GesturePoint>, steps: Int = 48): List<GesturePoint> {
        if (vertices.size < 2) return vertices
        val out = ArrayList<GesturePoint>(steps)
        val seg = (steps - 1).toFloat() / (vertices.size - 1)
        for (i in 0 until steps) {
            val t = i / seg
            val idx = t.toInt().coerceIn(0, vertices.size - 2)
            val local = t - idx
            val a = vertices[idx]
            val b = vertices[idx + 1]
            out.add(GesturePoint(a.x + (b.x - a.x) * local, a.y + (b.y - a.y) * local))
        }
        return out
    }

    private fun triangle() = samplePath(
        listOf(GesturePoint(50f, 10f), GesturePoint(90f, 90f), GesturePoint(10f, 90f), GesturePoint(50f, 10f)),
    )

    private fun rectangle() = samplePath(
        listOf(
            GesturePoint(10f, 10f),
            GesturePoint(90f, 10f),
            GesturePoint(90f, 90f),
            GesturePoint(10f, 90f),
            GesturePoint(10f, 10f),
        ),
    )

    private fun check() = samplePath(
        listOf(GesturePoint(10f, 50f), GesturePoint(40f, 85f), GesturePoint(90f, 15f)),
    )

    private fun cross() = samplePath(
        listOf(GesturePoint(15f, 15f), GesturePoint(85f, 85f), GesturePoint(50f, 50f), GesturePoint(15f, 85f), GesturePoint(85f, 15f)),
    )

    private fun heart(): List<GesturePoint> {
        val out = ArrayList<GesturePoint>(48)
        for (i in 0 until 48) {
            val t = i * 2f * PI.toFloat() / 47f
            val x = 16f * sin(t) * sin(t) * sin(t)
            val y = 13f * cos(t) - 5f * cos(2f * t) - 2f * cos(3f * t) - cos(4f * t)
            out.add(GesturePoint(50f + x * 2.2f, 45f - y * 2.2f))
        }
        return out
    }

    private fun wave(cycles: Int): List<GesturePoint> {
        val out = ArrayList<GesturePoint>(48)
        for (i in 0 until 48) {
            val t = i / 47f
            out.add(GesturePoint(10f + 80f * t, 50f + 22f * sin(cycles * 2f * PI.toFloat() * t)))
        }
        return out
    }

    private fun digit0() = samplePath(
        listOf(
            GesturePoint(50f, 10f),
            GesturePoint(85f, 25f),
            GesturePoint(90f, 50f),
            GesturePoint(85f, 75f),
            GesturePoint(50f, 90f),
            GesturePoint(15f, 75f),
            GesturePoint(10f, 50f),
            GesturePoint(15f, 25f),
            GesturePoint(50f, 10f),
        ),
    )

    private fun digit2() = samplePath(
        listOf(
            GesturePoint(15f, 25f),
            GesturePoint(50f, 10f),
            GesturePoint(85f, 25f),
            GesturePoint(80f, 50f),
            GesturePoint(20f, 80f),
            GesturePoint(90f, 90f),
        ),
    )

    private fun digit3() = samplePath(
        listOf(
            GesturePoint(18f, 18f),
            GesturePoint(78f, 12f),
            GesturePoint(88f, 32f),
            GesturePoint(58f, 48f),
            GesturePoint(88f, 62f),
            GesturePoint(80f, 86f),
            GesturePoint(22f, 88f),
        ),
    )

    private fun digit3Round() = samplePath(
        listOf(
            GesturePoint(22f, 22f),
            GesturePoint(70f, 10f),
            GesturePoint(90f, 28f),
            GesturePoint(68f, 46f),
            GesturePoint(90f, 64f),
            GesturePoint(72f, 88f),
            GesturePoint(20f, 86f),
        ),
    )

    private fun digit4() = samplePath(
        listOf(
            GesturePoint(70f, 10f),
            GesturePoint(20f, 60f),
            GesturePoint(90f, 60f),
            GesturePoint(70f, 60f),
            GesturePoint(70f, 90f),
        ),
    )

    private fun digit5() = samplePath(
        listOf(
            GesturePoint(88f, 12f),
            GesturePoint(22f, 12f),
            GesturePoint(18f, 48f),
            GesturePoint(72f, 48f),
            GesturePoint(90f, 68f),
            GesturePoint(72f, 90f),
            GesturePoint(22f, 84f),
        ),
    )

    private fun digit5S() = samplePath(
        listOf(
            GesturePoint(86f, 10f),
            GesturePoint(24f, 14f),
            GesturePoint(20f, 42f),
            GesturePoint(78f, 52f),
            GesturePoint(86f, 78f),
            GesturePoint(40f, 92f),
            GesturePoint(16f, 78f),
        ),
    )

    private fun digit6() = samplePath(
        listOf(
            GesturePoint(78f, 10f),
            GesturePoint(28f, 18f),
            GesturePoint(14f, 48f),
            GesturePoint(22f, 82f),
            GesturePoint(58f, 94f),
            GesturePoint(86f, 74f),
            GesturePoint(70f, 52f),
            GesturePoint(36f, 58f),
        ),
    )

    private fun digit6Hook() = samplePath(
        listOf(
            GesturePoint(82f, 8f),
            GesturePoint(40f, 12f),
            GesturePoint(16f, 40f),
            GesturePoint(18f, 78f),
            GesturePoint(50f, 94f),
            GesturePoint(84f, 78f),
            GesturePoint(78f, 56f),
            GesturePoint(42f, 54f),
        ),
    )

    private fun digit7() = samplePath(
        listOf(GesturePoint(15f, 12f), GesturePoint(90f, 12f), GesturePoint(40f, 90f)),
    )

    private fun digit8() = samplePath(
        listOf(
            GesturePoint(50f, 8f),
            GesturePoint(18f, 22f),
            GesturePoint(22f, 42f),
            GesturePoint(50f, 50f),
            GesturePoint(82f, 62f),
            GesturePoint(78f, 86f),
            GesturePoint(50f, 94f),
            GesturePoint(18f, 80f),
            GesturePoint(22f, 60f),
            GesturePoint(50f, 50f),
            GesturePoint(78f, 36f),
            GesturePoint(74f, 16f),
            GesturePoint(50f, 8f),
        ),
    )

    private fun digit8FromTop() = samplePath(
        listOf(
            GesturePoint(48f, 10f),
            GesturePoint(80f, 24f),
            GesturePoint(76f, 44f),
            GesturePoint(48f, 52f),
            GesturePoint(18f, 66f),
            GesturePoint(22f, 88f),
            GesturePoint(50f, 94f),
            GesturePoint(82f, 78f),
            GesturePoint(76f, 58f),
            GesturePoint(48f, 52f),
            GesturePoint(20f, 36f),
            GesturePoint(24f, 16f),
            GesturePoint(48f, 10f),
        ),
    )

    private fun digit9() = samplePath(
        listOf(
            GesturePoint(78f, 36f),
            GesturePoint(58f, 10f),
            GesturePoint(22f, 18f),
            GesturePoint(16f, 40f),
            GesturePoint(48f, 50f),
            GesturePoint(80f, 38f),
            GesturePoint(78f, 92f),
        ),
    )

    private fun digit9Tail() = samplePath(
        listOf(
            GesturePoint(72f, 12f),
            GesturePoint(28f, 14f),
            GesturePoint(16f, 36f),
            GesturePoint(40f, 50f),
            GesturePoint(78f, 36f),
            GesturePoint(80f, 14f),
            GesturePoint(76f, 92f),
        ),
    )
}
