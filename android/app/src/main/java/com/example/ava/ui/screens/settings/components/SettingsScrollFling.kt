package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.runtime.Composable
import com.example.ava.ui.scroll.fling.FlingConfiguration
import com.example.ava.ui.scroll.fling.flingBehavior

/**
 * Settings list fling — vendored Flinger core, no Maven dep.
 *
 * Physics: a light nudge toward Flinger **2.1** [FlingPresets.ultraSmooth]
 * (friction 0.006 / decel 0.05 / 150 pts), not the full preset — just enough
 * for a bit more physical coast than stock Default (0.008 / 0.09 / 100).
 */
@Composable
fun rememberSettingsFlingBehavior(): FlingBehavior {
    return flingBehavior(
        scrollConfiguration = FlingConfiguration.Builder()
            .scrollViewFriction(0.0075f)
            .decelerationFriction(0.075f)
            .numberOfSplinePoints(120)
            .build(),
    )
}
