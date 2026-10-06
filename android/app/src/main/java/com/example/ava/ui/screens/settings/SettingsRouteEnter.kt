package com.example.ava.ui.screens.settings

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import com.example.ava.ui.glass.LiquidGlass
import com.example.ava.ui.glass.SettingsGlassEnter
import com.example.ava.ui.glass.rememberLiquidGlassState
import kotlinx.coroutines.launch

/**
 * Enter motion for settings-like destinations.
 *
 * Navigation Compose 2.6 already cross-dissolves destinations inside its own Crossfade
 * (default tween, 300ms). Do not animate alpha here as well: a second alpha layer
 * multiplies with that crossfade, so the arriving page stays dim through its first frames,
 * and once the leaving page reaches 0 before the crossfade ends the screen drops to the
 * bare window color on the way back.
 *
 * Scale does not interact that way, so the arriving page grows the last few percent and
 * settles ahead of the crossfade rather than tracking its full 300ms, which is what keeps
 * entry feeling quick. Leaving pages hold full size and simply dissolve. No horizontal
 * slide — old devices.
 *
 * Liquid Glass: a frosted sidebar remounting into Settings would snap sharp.
 * [SettingsGlassEnter] arms only for that jump; in-settings hops stay scale-only.
 */
private const val SETTINGS_ROUTE_ENTER_MS = 200
private const val SETTINGS_ROUTE_BLUR_CLEAR_MS = 320
private const val SETTINGS_ROUTE_INITIAL_SCALE = 0.97f

@Composable
fun SettingsRouteEnter(content: @Composable () -> Unit) {
    if (LocalSettingsSplitActive.current) {
        content()
        return
    }

    val glass by rememberLiquidGlassState()
    val scale = remember { Animatable(SETTINGS_ROUTE_INITIAL_SCALE) }
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val clearBlur = remember {
        blurSupported &&
            SettingsGlassEnter.consume() &&
            LiquidGlass.viewBlurRadiusPx() > 0.5f
    }
    val blurProgress = remember { Animatable(if (clearBlur) 1f else 0f) }

    LaunchedEffect(Unit) {
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = SETTINGS_ROUTE_ENTER_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
        }
        if (clearBlur) {
            blurProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = SETTINGS_ROUTE_BLUR_CLEAR_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                if (clearBlur && blurProgress.value > 0.001f && glass.viewBlurRadiusPx > 0.5f) {
                    val radius = glass.viewBlurRadiusPx * blurProgress.value
                    renderEffect = LiquidGlass.backdropEffect(radius)?.asComposeRenderEffect()
                } else {
                    renderEffect = null
                }
            },
    ) {
        content()
    }
}
