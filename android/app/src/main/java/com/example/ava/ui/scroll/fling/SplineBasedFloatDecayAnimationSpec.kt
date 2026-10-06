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

import androidx.compose.animation.core.FloatDecayAnimationSpec
import androidx.compose.ui.unit.Density
import kotlin.math.sign

/**
 * Spline based decay animation based on {@see FloatDecayAnimationSpec}
 *
 *  @author Joseph James.
 */
class SplineBasedFloatDecayAnimationSpec(
    density: Density,
    scrollConfiguration: FlingConfiguration
) :
    FloatDecayAnimationSpec {

    /*
     * Fling calculation.
     */
    private val flingCalculator = FlingCalculator(
        density = density,
        flingConfiguration = scrollConfiguration
    )

    /*
     * This is the absolute value of a velocity threshold, below which the animation is
     * considered finished.
     */
    override val absVelocityThreshold: Float by lazy { scrollConfiguration.absVelocityThreshold }

    /*
     * Distance that is to be covered by the fling.
     */
    private fun flingDistance(startVelocity: Float): Float =
        flingCalculator.flingDistance(startVelocity) * sign(startVelocity)

    /*
     * Target value at the end of the fling.
     */
    override fun getTargetValue(initialValue: Float, initialVelocity: Float): Float =
        initialValue + flingDistance(initialVelocity)

    @Suppress("MethodNameUnits")
    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        initialVelocity: Float
    ): Float {
        val playTimeMillis = playTimeNanos / 1_000_000L
        return initialValue + flingCalculator.flingInfo(initialVelocity).position(playTimeMillis)
    }

    @Suppress("MethodNameUnits")
    override fun getDurationNanos(initialValue: Float, initialVelocity: Float): Long =
        flingCalculator.flingDuration(initialVelocity) * 1_000_000L

    @Suppress("MethodNameUnits")
    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        initialVelocity: Float
    ): Float {
        val playTimeMillis = playTimeNanos / 1_000_000L
        return flingCalculator.flingInfo(initialVelocity).velocity(playTimeMillis)
    }
}
