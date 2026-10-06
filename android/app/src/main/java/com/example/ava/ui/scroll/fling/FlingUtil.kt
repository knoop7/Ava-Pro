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

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.generateDecayAnimationSpec
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity

/**
 *  Simple utility class for Fling based decay animations.
 *
 *  @author Joseph James.
 */

@Composable
fun flingBehavior(
    scrollConfiguration: FlingConfiguration =
        FlingConfiguration.Builder()
            .build()
): FlingBehavior {
    val flingSpec = rememberSplineBasedDecay<Float>(scrollConfiguration)
    return remember(flingSpec) {
        FlingerFlingBehavior(flingSpec)
    }
}

@Composable
fun <T> rememberSplineBasedDecay(scrollConfiguration: FlingConfiguration): DecayAnimationSpec<T> {
    // This function will internally update the calculation of fling decay when the density changes,
    // but the reference to the returned spec will not change across calls.
    val density = LocalDensity.current
    return remember(density.density) {
        SplineBasedFloatDecayAnimationSpec(
            density,
            scrollConfiguration
        ).generateDecayAnimationSpec()
    }
}