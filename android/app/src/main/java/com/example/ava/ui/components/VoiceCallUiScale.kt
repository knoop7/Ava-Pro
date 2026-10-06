package com.example.ava.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

/**
 * Responsive scale for voice-call overlays.
 * Normal phones stay at 1×; large / low-DPI kiosk screens scale controls and center text up.
 */
@Immutable
data class VoiceCallUiScale(
    val control: Float,
    val text: Float,
) {
    fun dp(value: Dp): Dp = (value.value * control).dp

    fun dp(value: Float): Dp = (value * control).dp

    fun sp(value: TextUnit): TextUnit = (value.value * text).sp

    fun sp(value: Float): TextUnit = (value * text).sp
}

@Composable
fun rememberVoiceCallUiScale(): VoiceCallUiScale {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat()
    val screenHeight = configuration.screenHeightDp.toFloat()
    return remember(screenWidth, screenHeight) {
        computeVoiceCallUiScale(screenWidth, screenHeight)
    }
}

/** Softer responsive scale for voice-message hold button and status text. */
@Composable
fun rememberVoiceMessageUiScale(): VoiceCallUiScale {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat()
    val screenHeight = configuration.screenHeightDp.toFloat()
    return remember(screenWidth, screenHeight) {
        computeVoiceMessageUiScale(screenWidth, screenHeight)
    }
}

/** Pure function for unit tests and diameter helpers. */
fun computeVoiceCallUiScale(screenWidth: Float, screenHeight: Float): VoiceCallUiScale {
    val shortest = minOf(screenWidth, screenHeight)
    val longest = maxOf(screenWidth, screenHeight)
    val tierBoost = when {
        shortest >= 1440f || longest >= 2560f -> 1.58f
        shortest >= 960f || longest >= 1920f -> 1.40f
        shortest >= 720f || longest >= 1280f -> 1.24f
        shortest >= 600f || longest >= 1024f -> 1.14f
        shortest <= 320f -> 1.06f
        else -> 1f
    }
    val control = tierBoost.coerceIn(1f, 1.65f)
    val text = (tierBoost * 1.04f).coerceIn(1f, 1.68f)
    return VoiceCallUiScale(control = control, text = text)
}

/** Message hold button: grows on large screens, shrinks slightly on small phones. */
fun computeVoiceMessageUiScale(screenWidth: Float, screenHeight: Float): VoiceCallUiScale {
    val shortest = minOf(screenWidth, screenHeight)
    val call = computeVoiceCallUiScale(screenWidth, screenHeight)
    val largeBoost = call.control.coerceIn(1f, 1.30f)
    val smallShrink = when {
        shortest < 320f -> 0.88f
        shortest < 360f -> 0.92f
        shortest < 400f -> 0.96f
        else -> 1f
    }
    val control = (largeBoost * smallShrink).coerceIn(0.85f, 1.30f)
    val text = (call.text * smallShrink.coerceAtLeast(0.94f)).coerceIn(0.90f, 1.26f)
    return VoiceCallUiScale(control = control, text = text)
}

/** [视频 | 挂断] plus the mute button, at control scale 1. */
internal const val VoiceCallConnectedDesignWidthDp = 320f

/** [视频 | 挂断] alone, at control scale 1. */
internal const val VoiceCallBarDesignWidthDp = 248f

/**
 * Full-screen window stays at 1. A smaller pane (split overlay) shrinks toward
 * the pane, and a pane narrower than [designWidthDp] shrinks again so the
 * controls stay inside the window.
 */
fun voiceControlFit(
    paneWidthDp: Float,
    paneHeightDp: Float,
    screenWidthDp: Float,
    screenHeightDp: Float,
    designWidthDp: Float,
): Float {
    val paneArea = (paneWidthDp * paneHeightDp).coerceAtLeast(1f)
    val screenArea = (screenWidthDp * screenHeightDp).coerceAtLeast(1f)
    val linear = sqrt((paneArea / screenArea).coerceIn(0f, 1f))
    val pane = linear.coerceIn(0.62f, 1f)
    val available = (paneWidthDp - 12f).coerceAtLeast(1f)
    val needed = (designWidthDp * pane).coerceAtLeast(1f)
    val widthFit = (available / needed).coerceAtMost(1f)
    return (pane * widthFit).coerceIn(0.46f, 1f)
}

@Composable
fun rememberFittedVoiceScale(
    paneWidthDp: Float,
    paneHeightDp: Float,
    designWidthDp: Float,
    scale: VoiceCallUiScale,
): VoiceCallUiScale {
    val metrics = LocalContext.current.resources.displayMetrics
    val screenWidth = metrics.widthPixels / metrics.density
    val screenHeight = metrics.heightPixels / metrics.density
    return remember(paneWidthDp, paneHeightDp, screenWidth, screenHeight, designWidthDp, scale) {
        val fit = voiceControlFit(
            paneWidthDp = paneWidthDp,
            paneHeightDp = paneHeightDp,
            screenWidthDp = screenWidth,
            screenHeightDp = screenHeight,
            designWidthDp = designWidthDp,
        )
        if (fit == 1f) scale else VoiceCallUiScale(scale.control * fit, scale.text * fit)
    }
}
