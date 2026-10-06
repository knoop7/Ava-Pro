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

/**
 * This class is used to locate the spline positions and times for the nbSample points.
 * This is calculated based on the scroll configuration that we had provided in the constructor.
 *
 * @author Joseph James.
 */
internal fun computeSplineInfo(
    splinePositions: FloatArray,
    splineTimes: FloatArray,
    nbSamples: Int,
    flingConfiguration: FlingConfiguration
) {
    var xMin = 0.0f
    var yMin = 0.0f
    for (i in 0 until nbSamples) {
        val alpha = i.toFloat() / nbSamples
        var xMax = 1.0f
        var x: Float
        var tx: Float
        var coef: Float
        while (true) {
            x = xMin + (xMax - xMin) / 2.0f
            coef = 3.0f * x * (1.0f - x)
            tx = coef * ((1.0f - x)
                    * flingConfiguration.splineP1 + x
                    * flingConfiguration.splineP2) + x * x * x
            if (abs(tx - alpha) < 1E-5) break
            if (tx > alpha) xMax = x else xMin = x
        }
        splinePositions[i] =
            coef * ((1.0f - x) * flingConfiguration.splineStartTension + x) + x * x * x
        var yMax = 1.0f
        var y: Float
        var dy: Float
        while (true) {
            y = yMin + (yMax - yMin) / 2.0f
            coef = 3.0f * y * (1.0f - y)
            dy = coef * ((1.0f - y) * flingConfiguration.splineStartTension + y) + y * y * y
            if (abs(dy - alpha) < 1E-5) break
            if (dy > alpha) yMax = y else yMin = y
        }
        splineTimes[i] = coef * ((1.0f - y) * flingConfiguration.splineP1 + y *
                flingConfiguration.splineP2
                ) + y * y * y
    }
    splineTimes[nbSamples] = 1.0f
    splinePositions[nbSamples] = splineTimes[nbSamples]
}
