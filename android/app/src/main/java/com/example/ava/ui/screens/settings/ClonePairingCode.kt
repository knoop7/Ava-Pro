package com.example.ava.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Six-box pairing code, shared by both clone ends so the sender's display and the
 * receiver's input are visually identical: same box metrics, same track-tone
 * background as the Backup & Restore segmented control, digits grouped 3+3.
 *
 * Every box takes an equal [Row] weight so narrow screens shrink all six together —
 * the last box can never be squeezed smaller than its siblings.
 */
private val PairingBoxShape = RoundedCornerShape(12.dp)
private const val PAIRING_CODE_LENGTH = 6
internal const val PAIRING_CODE_SHAKE_MS = 420
internal const val PAIRING_CODE_CLEAR_AFTER_WRONG = 3
private val PairingErrorColor = Color(0xFFE57373)

@Composable
internal fun PairingCodeBoxes(
    code: String,
    modifier: Modifier = Modifier,
    activeIndex: Int = -1,
    digitColor: Color = getLabelColor(),
    error: Boolean = false,
) {
    val resolvedDigit by animateColorAsState(
        targetValue = if (error) PairingErrorColor else digitColor,
        animationSpec = tween(160),
        label = "pairing_digit",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            error -> PairingErrorColor
            activeIndex >= 0 -> getAccentColor()
            else -> Color.Transparent
        },
        animationSpec = tween(160),
        label = "pairing_border",
    )
    Row(
        modifier = modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(PAIRING_CODE_LENGTH) { index ->
            if (index == PAIRING_CODE_LENGTH / 2) {
                Spacer(modifier = Modifier.width(18.dp))
            } else if (index > 0) {
                Spacer(modifier = Modifier.width(8.dp))
            }
            val active = index == activeIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(PairingBoxShape)
                    .background(getSliderInactiveColor())
                    .then(
                        if (error || active) {
                            Modifier.border(1.5.dp, borderColor, PairingBoxShape)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = code.getOrNull(index)?.toString().orEmpty(),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = resolvedDigit,
                )
            }
        }
    }
}

/**
 * OTP-style pairing code input: an invisible text field stretched over the six boxes
 * captures taps and keyboard input; the box at the caret is outlined in accent.
 */
@Composable
internal fun PairingCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    errorTick: Int = 0,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(errorTick) {
        if (errorTick <= 0) return@LaunchedEffect
        haptic.performHapticFeedback(HapticFeedbackType.Reject)
        val travel = with(density) { 18.dp.toPx() }
        shake.snapTo(0f)
        shake.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = PAIRING_CODE_SHAKE_MS
                0f at 0
                travel at 50
                -travel at 100
                travel * 0.75f at 150
                -travel * 0.75f at 200
                travel * 0.45f at 260
                -travel * 0.45f at 320
                0f at PAIRING_CODE_SHAKE_MS
            },
        )
    }
    BasicTextField(
        value = value,
        onValueChange = { raw ->
            onValueChange(raw.filter { it.isDigit() }.take(PAIRING_CODE_LENGTH))
        },
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationX = shake.value },
                contentAlignment = Alignment.Center,
            ) {
                PairingCodeBoxes(
                    code = value,
                    activeIndex = if (enabled && !error) {
                        value.length.takeIf { it < PAIRING_CODE_LENGTH } ?: -1
                    } else {
                        -1
                    },
                    error = error,
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0f),
                ) {
                    innerTextField()
                }
            }
        },
    )
}
