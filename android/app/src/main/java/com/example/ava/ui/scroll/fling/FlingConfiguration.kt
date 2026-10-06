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

import kotlin.math.ln

/**
 * This class defines all the fling related parameters that can be tweaked by the user.
 *
 * @author Joseph James.
 */
class FlingConfiguration private constructor(
    val scrollFriction: Float,
    val absVelocityThreshold: Float,
    val gravitationalForce: Float,
    val inchesPerMeter: Float,
    val decelerationFriction: Float,
    val decelerationRate: Float,
    val splineInflection: Float,
    val splineStartTension: Float,
    private val splineEndTension: Float,
    val numberOfSplinePoints: Int
) {

    val splineP1: Float by lazy { splineStartTension * splineInflection }

    val splineP2: Float by lazy { 1.0f - splineEndTension * (1.0f - splineInflection) }

    companion object {
        /** Same as Flinger 2.1 [FlingConfiguration.Default] / `FlingPresets.smooth()`. */
        val Default: FlingConfiguration = Builder().build()
    }

    data class Builder(
        /*
         * This variable manages the friction to the scrolls in the LazyColumn
         */
        var scrollViewFriction: Float = 0.008f,

        /*
         * This is the absolute value of a velocity threshold, below which the
         * animation is considered finished.
         */
        var absVelocityThreshold: Float = 0f,

        /*
         * Gravitational obstruction to the scroll.
         */
        var gravitationalForce: Float = 9.80665f,

        /*
         * Scroll Inches per meter
         */
        var inchesPerMeter: Float = 39.37f,

        /*
         * Rate of deceleration of the scrollView.
         */
        var decelerationRate: Float = (ln(0.78) / ln(0.9)).toFloat(),

        /*
         * Friction at the time of deceleration.
         */
        var decelerationFriction: Float = 0.09f,

        /*
         * Inflection is the place where the start and end tension lines cross each other.
         */
        var splineInflection: Float = 0.1f,

        /*
         * Spline's start tension.
         */
        var splineStartTension: Float = 0.1f,

        /*
         * Spline's end tension.
         */
        var splineEndTension: Float = 1.0f,

        /*
         * number of sampling points in the spline
         */
        var numberOfSplinePoints: Int = 100
    ) {

        fun scrollViewFriction(scrollViewFriction: Float) =
            apply { this.scrollViewFriction = scrollViewFriction }

        fun absVelocityThreshold(absVelocityThreshold: Float) =
            apply { this.absVelocityThreshold = absVelocityThreshold }

        fun gravitationalForce(gravitationalForce: Float) =
            apply { this.gravitationalForce = gravitationalForce }

        fun inchesPerMeter(inchesPerMeter: Float) =
            apply { this.inchesPerMeter = inchesPerMeter }

        fun decelerationFriction(decelerationFriction: Float) =
            apply { this.decelerationFriction = decelerationFriction }

        fun decelerationRate(decelerationRate: Float) =
            apply { this.decelerationRate = decelerationRate }

        fun splineInflection(splineInflection: Float) =
            apply { this.splineInflection = splineInflection }

        fun splineStartTension(splineStartTension: Float) =
            apply { this.splineStartTension = splineStartTension }

        fun splineEndTension(splineEndTension: Float) =
            apply { this.splineEndTension = splineEndTension }

        fun numberOfSplinePoints(numberOfSplinePoints: Int) =
            apply { this.numberOfSplinePoints = numberOfSplinePoints }

        fun build() = FlingConfiguration(
            scrollViewFriction,
            absVelocityThreshold,
            gravitationalForce,
            inchesPerMeter,
            decelerationFriction,
            decelerationRate,
            splineInflection,
            splineStartTension,
            splineEndTension,
            numberOfSplinePoints
        )
    }
}