package com.example.ava.services

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.example.ava.R
import com.example.ava.settings.DreamClockSeason
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * StandBy Season Effects 1:1: spring petals, summer motes, autumn leaves, winter snow.
 */
class DreamClockSeasonLayer(private val context: Context) {
    var season: DreamClockSeason = DreamClockSeason.NONE
        private set

    private val density = context.resources.displayMetrics.density
    private val autumn = ArrayList<AutumnLeaf>()
    private val spring = ArrayList<SpringPetal>()
    private val summer = ArrayList<SummerMote>()
    private val winter = ArrayList<Snowflake>()
    private var lastTickMs = 0L
    private var fieldW = 0
    private var fieldH = 0

    fun apply(next: DreamClockSeason, width: Int, height: Int) {
        if (season == next && fieldW == width && fieldH == height) return
        season = next
        fieldW = width
        fieldH = height
        lastTickMs = 0L
        rebuild()
    }

    fun isActive(): Boolean = season != DreamClockSeason.NONE

    fun draw(canvas: Canvas, width: Int, height: Int, nowMs: Long) {
        if (season == DreamClockSeason.NONE || width <= 0 || height <= 0) return
        if (fieldW != width || fieldH != height) {
            apply(season, width, height)
        }
        val rawDt = if (lastTickMs <= 0L) 16L else nowMs - lastTickMs
        lastTickMs = nowMs
        val dtMs = min(rawDt, 48L)
        val dt = dtMs / 1000f
        when (season) {
            DreamClockSeason.NONE -> Unit
            DreamClockSeason.AUTUMN -> {
                autumn.forEach { it.tick(dt, dtMs, width, height) }
                autumn.forEach { it.draw(canvas) }
            }
            DreamClockSeason.SPRING -> {
                spring.forEach { it.tick(dt, width, height) }
                spring.forEach { it.draw(canvas) }
            }
            DreamClockSeason.SUMMER -> {
                summer.forEach { it.tick(dt, width, height) }
                summer.forEach { it.draw(canvas) }
            }
            DreamClockSeason.WINTER -> {
                winter.forEach { it.tick(dtMs, width, height) }
                winter.forEach { it.draw(canvas) }
            }
        }
    }

    private fun rebuild() {
        autumn.clear()
        spring.clear()
        summer.clear()
        winter.clear()
        if (fieldW <= 0 || fieldH <= 0) return
        when (season) {
            DreamClockSeason.NONE -> Unit
            DreamClockSeason.AUTUMN -> spawnAutumn()
            DreamClockSeason.SPRING -> spawnSpring()
            DreamClockSeason.SUMMER -> spawnSummer()
            DreamClockSeason.WINTER -> spawnWinter()
        }
    }

    private fun spawnAutumn() {
        val count = clampCount((fieldW * fieldH * 0.0018f) / 700f, 6, 50)
        val art = load(AUTUMN_IDS)
        if (art.isEmpty()) return
        repeat(count) {
            val drawable = art[Random.nextInt(art.size)]
            val size = (16f + Random.nextFloat() * 14f) * density
            autumn.add(
                AutumnLeaf(
                    drawable = drawable,
                    size = size,
                    seedX = Random.nextFloat() * fieldW,
                    y = -Random.nextFloat() * (fieldH / 3f),
                    fallSpeed = span(65f, 35f),
                    swayAmp = span(40f, 15f),
                    swayFreq = span(0.6f, 0.4f),
                    restSpin = span(3f, -1.5f),
                    flutter = span(25f, 10f),
                    phase = Random.nextFloat() * TAU,
                    spinFreq = span(1.7f, 1.8f),
                    spinAmp = span(0.6f, 0.4f),
                    restMs = span(1400f, 1800f),
                    fadeMs = span(700f, 800f),
                )
            )
        }
    }

    private fun spawnSpring() {
        val count = clampCount((fieldW * fieldH * 0.0018f) / 700f, 10, 50)
        val art = load(SPRING_IDS)
        if (art.isEmpty()) return
        repeat(count) {
            val drawable = art[Random.nextInt(art.size)]
            val size = (12f + Random.nextFloat() * 12f) * density
            spring.add(
                SpringPetal(
                    drawable = drawable,
                    size = size,
                    x = -((Random.nextFloat() * fieldW * 0.15f) + size),
                    y = Random.nextFloat() * fieldH,
                    speed = span(32f, 18f),
                    angle = span(0.4f, -0.15f),
                    swayAmp = span(14f, 8f),
                    swayFreq = span(0.5f, 0.3f),
                    bobAmp = span(10f, 5f),
                    bobFreq = span(0.3f, 0.2f),
                    spin = span(0.6f, -0.3f),
                )
            )
        }
    }

    private fun spawnSummer() {
        val count = clampCount((fieldW * fieldH * 0.002f) / 700f, 12, 60)
        val art = load(SUMMER_IDS)
        if (art.isEmpty()) return
        repeat(count) { index ->
            val drawable = art[Random.nextInt(art.size)]
            val small = index % 3 == 0
            val size = (if (small) 4f + Random.nextFloat() * 6f else 10f + Random.nextFloat() * 14f) * density
            val roll = Random.nextFloat()
            val rise: Float
            val sway: Float
            val freq: Float
            val spin: Float
            if (roll < 0.3f) {
                rise = span(6f, 2f)
                sway = span(35f, 25f)
                freq = span(0.2f, 0.15f)
                spin = span(0.6f, 0.6f)
            } else if (roll < 0.7f) {
                rise = span(18f, 10f)
                sway = span(30f, 15f)
                freq = span(0.4f, 0.3f)
                spin = span(1.3f, 1.2f)
            } else {
                rise = span(22f, 28f)
                sway = span(17f, 8f)
                freq = span(0.5f, 0.5f)
                spin = span(2f, 2f)
            }
            summer.add(
                SummerMote(
                    drawable = drawable,
                    size = size,
                    x = Random.nextFloat() * fieldW,
                    y = fieldH * 0.1f + Random.nextFloat() * (fieldH * 0.9f),
                    rise = rise,
                    sway = sway,
                    freq = freq,
                    bobAmp = span(15f, 5f),
                    bobFreq = span(0.6f, 0.4f),
                    spin = spin,
                    alphaMin = if (small) span(0.2f, 0.15f) else span(0.1f, 0.05f),
                    alphaMax = if (small) span(0.3f, 0.7f) else span(0.3f, 0.5f),
                    flicker = span(0.8f, -0.4f),
                    spin2 = span(1f, -0.5f),
                )
            )
        }
    }

    private fun spawnWinter() {
        val count = max(1, ((fieldW * fieldH * 0.002f) / 500f).toInt())
        val art = load(WINTER_IDS)
        if (art.isEmpty()) return
        repeat(count) {
            val drawable = art[Random.nextInt(art.size)]
            winter.add(
                Snowflake(
                    drawable = drawable,
                    size = span(10f, 11f) * density,
                    increment = span(1.6f, 0.4f),
                    angle = ((Random.nextFloat() * 25f / 25f) * 0.5f) + (Math.PI.toFloat() / 2f) - 0.25f,
                    rotation = Random.nextFloat() * 45f,
                    rotationSpeed = span(0.1f, -0.05f),
                    x = Random.nextFloat() * fieldW,
                    y = Random.nextFloat() * fieldH,
                )
            )
        }
    }

    private fun load(ids: IntArray): List<Drawable> =
        ids.asSequence().mapNotNull { id ->
            ContextCompat.getDrawable(context, id)?.mutate()?.also { drawable ->
                drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
            }
        }.toList()

    private class AutumnLeaf(
        private val drawable: Drawable,
        val size: Float,
        private var seedX: Float,
        var y: Float,
        private val fallSpeed: Float,
        private val swayAmp: Float,
        private val swayFreq: Float,
        private val restSpin: Float,
        private val flutter: Float,
        private var phase: Float,
        private val spinFreq: Float,
        private val spinAmp: Float,
        private val restMs: Float,
        private val fadeMs: Float,
    ) {
        private enum class Phase { FALLING, RESTING, FADING }
        private var mode = Phase.FALLING
        private var time = Random.nextFloat() * 8f
        private var rotation = Random.nextFloat() * TAU
        private var x = seedX
        private var alpha = 1f
        private var hold = 0f
        private val scale = size / max(1f, drawable.intrinsicHeight.toFloat())

        fun tick(dt: Float, dtMs: Long, width: Int, height: Int) {
            time += dt
            when (mode) {
                Phase.FALLING -> {
                    val depth = ((y / max(1f, height.toFloat())).coerceIn(0f, 1f) * 0.8f) + 0.4f
                    val sway = sin(phase + (time * swayFreq)).toFloat() * swayAmp * depth
                    val flutterX = (
                        (sin(phase + time * 0.4f).toFloat() * 0.6f) +
                            (sin((phase * 1.7f) + (time * 0.9f)).toFloat() * 0.3f) +
                            (cos((phase * 0.5f) + (time * 1.6f)).toFloat() * 0.1f)
                        ) * flutter
                    x = (seedX + sway + flutterX).coerceIn(-size, width + size)
                    y += ((((sin(time * 0.6f).toFloat() * 0.25f) + 0.85f) * fallSpeed) * dt)
                    rotation += (((abs(sin(time * 0.8f).toFloat()) * 0.5f) + 0.7f) *
                        (spinFreq + (sin(time * swayFreq).toFloat() * spinAmp)) * dt)
                    val fadeIn = height * 0.06f
                    alpha = if (y < fadeIn) ((y + size) / fadeIn).coerceIn(0f, 1f) else 1f
                    val floor = (height - size * 0.5f).coerceAtLeast(0f)
                    if (y >= floor) {
                        y = floor
                        mode = Phase.RESTING
                        hold = 0f
                        alpha = 1f
                    }
                    if (x < -size * 2f || x > width + size * 2f) {
                        seedX = Random.nextFloat() * width
                    }
                }
                Phase.RESTING -> {
                    hold += dtMs
                    rotation += restSpin * dt * 0.05f
                    if (hold >= restMs) {
                        mode = Phase.FADING
                        hold = 0f
                    }
                }
                Phase.FADING -> {
                    hold += dtMs
                    alpha = (1f - hold / fadeMs).coerceAtLeast(0f)
                    rotation += restSpin * dt * 0.03f
                    if (alpha <= 0f) {
                        seedX = Random.nextFloat() * width
                        x = seedX
                        y = -((Random.nextFloat() * height * 0.25f) + 10f)
                        phase = Random.nextFloat() * TAU
                        rotation = Random.nextFloat() * TAU
                        mode = Phase.FALLING
                        hold = 0f
                        alpha = 0f
                    }
                }
            }
        }

        fun draw(canvas: Canvas) = paint(canvas, drawable, x, y, scale, rotation, alpha)
    }

    private class SpringPetal(
        private val drawable: Drawable,
        val size: Float,
        var x: Float,
        var y: Float,
        private val speed: Float,
        private val angle: Float,
        private val swayAmp: Float,
        private val swayFreq: Float,
        private val bobAmp: Float,
        private val bobFreq: Float,
        private val spin: Float,
    ) {
        private var time = Random.nextFloat() * 20f
        private var rotation = Random.nextFloat() * TAU
        private var phase = Random.nextFloat() * TAU
        private var alpha = 0f
        private val scale = size / max(1f, drawable.intrinsicHeight.toFloat())

        fun tick(dt: Float, width: Int, height: Int) {
            time += dt
            val vx = cos(angle.toDouble()).toFloat() * speed
            val vy = sin(angle.toDouble()).toFloat() * speed
            val gust = sin(phase + (time * swayFreq)).toFloat() * swayAmp
            val bob = sin((phase * 0.6f) + (time * bobFreq)).toFloat() * bobAmp
            val pace = (sin((phase * 1.3f) + (time * 0.25f)).toFloat() * 0.25f) + 0.85f
            x += (vx * pace) * dt
            y += ((vy * pace) * dt) - (bob * dt) + (gust * dt * 0.4f)
            rotation += spin * dt
            val edge = width * 0.1f
            alpha = when {
                x < edge -> (x / edge).coerceIn(0f, 1f)
                x > width * 0.9f -> ((width - x) / edge).coerceIn(0f, 1f)
                else -> 1f
            }
            if (x > width + size * 2f || y < -size * 3f || y > height + size * 3f) {
                x = -((Random.nextFloat() * width * 0.2f) + size)
                y = Random.nextFloat() * height
                phase = Random.nextFloat() * TAU
                rotation = Random.nextFloat() * TAU
                alpha = 0f
            }
        }

        fun draw(canvas: Canvas) = paint(canvas, drawable, x, y, scale, rotation, alpha)
    }

    private class SummerMote(
        private val drawable: Drawable,
        val size: Float,
        var x: Float,
        var y: Float,
        private val rise: Float,
        private val sway: Float,
        private val freq: Float,
        private val bobAmp: Float,
        private val bobFreq: Float,
        private val spin: Float,
        private val alphaMin: Float,
        private val alphaMax: Float,
        private val flicker: Float,
        private val spin2: Float,
    ) {
        private var time = Random.nextFloat() * 10f
        private var rotation = Random.nextFloat() * TAU
        private var phase = Random.nextFloat() * TAU
        private var alpha = alphaMin
        private var homeX = x
        private val scale = size / max(1f, drawable.intrinsicHeight.toFloat())

        fun tick(dt: Float, width: Int, height: Int) {
            time += dt
            val wander = (
                (sin(phase + (time * freq)).toFloat() * 0.6f) +
                    (sin((phase * 2.3f) + (time * freq * 1.7f)).toFloat() * 0.3f) +
                    (cos((phase * 0.7f) + (time * freq * 0.4f)).toFloat() * 0.1f)
                ) * sway
            val bob = bobAmp * sin((phase * 1.5f) + (time * bobFreq)).toFloat()
            x = (homeX + wander + (time * sin(spin2.toDouble()).toFloat() * rise * 0.03f)).coerceIn(-size, width + size)
            y -= rise * dt
            y += bob * dt
            rotation += spin2 * dt
            alpha = ((((sin(phase + (time * flicker)).toFloat() * 0.5f) + 0.5f) * (alphaMax - alphaMin)) + alphaMin)
                .coerceIn(0f, 1f)
            val fade = height * 0.15f
            if (y < fade) alpha *= (y / fade).coerceIn(0f, 1f)
            if (y > height * 0.9f) alpha *= ((height - y) / (height * 0.1f)).coerceIn(0f, 1f)
            if (y <= -size * 2f || x < -size * 3f || x > width + size * 3f) {
                homeX = Random.nextFloat() * width
                x = homeX
                y = height + Random.nextFloat() * (height * 0.1f)
                phase = Random.nextFloat() * TAU
                rotation = Random.nextFloat() * TAU
                alpha = alphaMin
            }
        }

        fun draw(canvas: Canvas) = paint(canvas, drawable, x, y, scale, rotation, alpha)
    }

    private class Snowflake(
        private val drawable: Drawable,
        val size: Float,
        private val increment: Float,
        var angle: Float,
        var rotation: Float,
        private val rotationSpeed: Float,
        var x: Float,
        var y: Float,
    ) {
        private val scale = size / max(1f, drawable.intrinsicHeight.toFloat())

        fun tick(dtMs: Long, width: Int, height: Int) {
            val step = (dtMs / 16f) * increment * 2.23f
            x += cos(angle.toDouble()).toFloat() * step
            y += sin(angle.toDouble()).toFloat() * step
            angle += span(50f, -25f) / 10000f
            rotation += rotationSpeed
            if (rotation > TAU) rotation = 0f
            if (y - size > height) {
                y = -size
            }
            if (x < -size || x > width + size) {
                x = Random.nextFloat() * width
                y = -size
            }
        }

        fun draw(canvas: Canvas) = paint(canvas, drawable, x, y, scale, rotation, 1f)
    }

    companion object {
        private const val TAU = 6.2831855f
        private val AUTUMN_IDS = intArrayOf(
            R.drawable.dream_season_autumn_01,
            R.drawable.dream_season_autumn_02,
            R.drawable.dream_season_autumn_03,
            R.drawable.dream_season_autumn_04,
            R.drawable.dream_season_autumn_05,
        )
        private val SPRING_IDS = intArrayOf(
            R.drawable.dream_season_spring_01,
            R.drawable.dream_season_spring_02,
            R.drawable.dream_season_spring_03,
            R.drawable.dream_season_spring_04,
            R.drawable.dream_season_spring_05,
        )
        private val SUMMER_IDS = intArrayOf(
            R.drawable.dream_season_summer_01,
            R.drawable.dream_season_summer_02,
            R.drawable.dream_season_summer_03,
            R.drawable.dream_season_summer_04,
            R.drawable.dream_season_summer_05,
        )
        private val WINTER_IDS = intArrayOf(
            R.drawable.dream_season_winter_01,
            R.drawable.dream_season_winter_02,
            R.drawable.dream_season_winter_03,
            R.drawable.dream_season_winter_04,
        )

        private fun span(span: Float, base: Float): Float = Random.nextFloat() * span + base

        private fun clampCount(raw: Float, lo: Int, hi: Int): Int =
            min(hi, max(lo, raw.toInt()))

        private fun paint(
            canvas: Canvas,
            drawable: Drawable,
            x: Float,
            y: Float,
            scale: Float,
            rotation: Float,
            alpha: Float,
        ) {
            val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
            if (a <= 0) return
            val cx = drawable.intrinsicWidth / 2f
            val cy = drawable.intrinsicHeight / 2f
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            canvas.rotate(Math.toDegrees(rotation.toDouble()).toFloat(), cx, cy)
            drawable.alpha = a
            drawable.draw(canvas)
            canvas.restore()
        }
    }
}
