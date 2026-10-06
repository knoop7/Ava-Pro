/*
* MIT License
*
* Copyright (c) 2021 Joseph James
*
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in all
* copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
* SOFTWARE.
*
*/

// Ported from iamjosephmj/flinger 1.3.0 (MIT) — core fling only; snap/pager/adaptive omitted.

package com.example.ava.ui.scroll.fling

import androidx.compose.ui.unit.Density
import kotlin.math.exp
import kotlin.math.sign


/**
 * Configuration for Android-feel flinging motion at the given density.
 *
 * @param density density of the screen. Use LocalDensity to get current density in composition.
 * @param flingConfiguration this contain all parameters need for setting the scroll behaviour.
 *
 * @author Joseph James
 */
class FlingCalculator(
    val density: Density,
    val flingConfiguration: FlingConfiguration
) {

    private val androidFlingSpline: AndroidFlingSpline by lazy {
        AndroidFlingSpline(flingConfiguration = flingConfiguration)
    }

    /**
     * Compute the rate of deceleration based on pixel density, physical gravity
     * and a [coefficient of friction][friction].
     */
    private fun computeDeceleration(friction: Float, density: Float): Float =
        flingConfiguration.gravitationalForce *
                flingConfiguration.inchesPerMeter *
                density *
                160f *
                friction


    /**
     * A density-specific coefficient adjusted to physical values.
     */
    private val magicPhysicalCoefficient: Float by lazy { computeDeceleration(density) }

    /**
     * Computes the rate of deceleration in pixels based on
     * the given [density].
     */
    private fun computeDeceleration(density: Density) =
        computeDeceleration(flingConfiguration.decelerationFriction, density.density)

    private fun getSplineDeceleration(velocity: Float): Double =
        androidFlingSpline.deceleration(
            velocity,
            flingConfiguration.scrollFriction * magicPhysicalCoefficient
        )

    /**
     * Compute the duration in milliseconds of a fling with an initial velocity of [velocity]
     */
    fun flingDuration(velocity: Float): Long {
        val l = getSplineDeceleration(velocity)
        val decelMinusOne = flingConfiguration.decelerationRate - 1.0
        return (1000.0 * exp(l / decelMinusOne)).toLong()
    }

    /**
     * Compute the distance of a fling in units given an initial [velocity] of units/second
     */
    fun flingDistance(velocity: Float): Float {
        val l = getSplineDeceleration(velocity)
        val decelMinusOne = flingConfiguration.decelerationRate - 1.0
        return (
                flingConfiguration.scrollFriction * magicPhysicalCoefficient
                        * exp(flingConfiguration.decelerationRate / decelMinusOne * l)
                ).toFloat()
    }

    /**
     * Compute all interesting information about a fling of initial velocity [velocity].
     */
    fun flingInfo(velocity: Float): FlingInfo {
        val l = getSplineDeceleration(velocity)
        val decelMinusOne = flingConfiguration.decelerationRate - 1.0
        return FlingInfo(
            initialVelocity = velocity,
            distance = (
                    flingConfiguration.scrollFriction * magicPhysicalCoefficient
                            * exp(flingConfiguration.decelerationRate / decelMinusOne * l)
                    ).toFloat(),
            duration = (1000.0 * exp(l / decelMinusOne)).toLong(),
            androidFlingSpline
        )
    }

    /**
     * Info about a fling started with [initialVelocity]. The units of [initialVelocity]
     * determine the distance units of [distance] and the time units of [duration].
     */
    data class FlingInfo(
        val initialVelocity: Float,
        val distance: Float,
        val duration: Long,
        val androidFlingSpline: AndroidFlingSpline
    ) {
        fun position(time: Long): Float {
            val splinePos = if (duration > 0) time / duration.toFloat() else 1f
            return distance * sign(initialVelocity) *
                    androidFlingSpline.flingPosition(splinePos).distanceCoefficient
        }

        fun velocity(time: Long): Float {
            val splinePos = if (duration > 0) time / duration.toFloat() else 1f
            return androidFlingSpline.flingPosition(splinePos).velocityCoefficient *
                    sign(initialVelocity) * distance / duration * 1000.0f
        }
    }
}