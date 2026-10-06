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

import kotlin.math.abs
import kotlin.math.ln


/**
 * The native Android fling scroll spline and the ability to sample it.
 *
 * Ported from `android.widget.Scroller`.
 * +
 * Addition of ScrollViewConfiguration.
 *
 * @author Joseph James.
 */
class AndroidFlingSpline(private val flingConfiguration: FlingConfiguration) {
    private val samples by lazy { flingConfiguration.numberOfSplinePoints }

    private val splinePositions by lazy {
        FloatArray(flingConfiguration.numberOfSplinePoints + 1)
    }

    private val splineTimes by lazy {
        FloatArray(flingConfiguration.numberOfSplinePoints + 1)
    }

    init {
        computeSplineInfo(splinePositions, splineTimes, samples, flingConfiguration)
    }

    /**
     * Compute an instantaneous fling position along the scroller spline.
     *
     * @param time progress through the fling animation from 0-1
     */
    fun flingPosition(time: Float): FlingResult {
        val index = (samples * time).toInt()
        var distanceCoef = 1f
        var velocityCoef = 0f
        if (index < samples) {
            val tInf = index.toFloat() / samples
            val tSup = (index + 1).toFloat() / samples
            val dInf = splinePositions[index]
            val dSup = splinePositions[index + 1]
            velocityCoef = (dSup - dInf) / (tSup - tInf)
            distanceCoef = dInf + (time - tInf) * velocityCoef
        }
        return FlingResult(
            distanceCoefficient = distanceCoef,
            velocityCoefficient = velocityCoef
        )
    }

    /**
     * The rate of deceleration along the spline motion given [velocity] and [friction].
     */
    fun deceleration(velocity: Float, friction: Float): Double =
        ln(flingConfiguration.splineInflection * abs(velocity) / friction.toDouble())

    /**
     * Result coefficients of a scroll computation
     */
    data class FlingResult(
        /**
         * Linear distance traveled from 0-1, from source (0) to destination (1)
         */
        val distanceCoefficient: Float,
        /**
         * Instantaneous velocity coefficient at this point in the fling expressed in
         * total distance per unit time
         */
        val velocityCoefficient: Float
    )
}
