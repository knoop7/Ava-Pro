package com.example.ava.ui.haptic

import androidx.annotation.IntRange
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Drop-in [Slider] that pulses the vibration motor when the value crosses a detent.
 * Devices without a motor stay silent. Caller snap / save logic is unchanged.
 */
@Composable
fun TickSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    @IntRange(from = 0) steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val tick = rememberSliderTickHaptic()
    val lastTick = remember { mutableFloatStateOf(value) }
    Slider(
        value = value,
        onValueChange = { next ->
            if (sliderTickChanged(lastTick.floatValue, next, valueRange, steps)) {
                tick()
            }
            lastTick.floatValue = next
            onValueChange(next)
        },
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
    )
}
