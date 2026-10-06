package com.example.ava.ui.screens.settings.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.example.ava.R
import com.example.ava.ui.components.expandTouchTarget
import com.example.ava.ui.rememberAdaptiveSpec

@Composable
fun SettingsHeaderBar(
    title: String,
    titleColor: Color,
    containerColor: Color,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
    showBack: Boolean = true,
    /** Decorative mark in the back slot. Ignored while [showBack] is true. */
    @DrawableRes leadingIconRes: Int? = null,
    extraTopPadding: Dp = 10.dp,
    windowInsets: WindowInsets = WindowInsets.safeDrawing
) {
    // Always read safeDrawing insets for cutout/system bar areas,
    // regardless of what the caller passes for Scaffold coordination.
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current

    val topInset = windowInsets.asPaddingValues().calculateTopPadding()
    // In landscape, the cutout/system bars may be on the left or right side.
    // We must always respect safeDrawing for horizontal insets so the header
    // content is never drawn under the cutout or system gesture area.
    val safeLeft = safeInsets.calculateLeftPadding(layoutDirection)
    val safeRight = safeInsets.calculateRightPadding(layoutDirection)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = titleColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset, bottom = 10.dp)
        ) {
            HeaderContent(
                title = title,
                titleColor = titleColor,
                onBack = onBack,
                isLandscape = isLandscape,
                showBack = showBack,
                leadingIconRes = leadingIconRes,
                safeStartPadding = safeLeft,
                safeEndPadding = safeRight,
                extraTopPadding = extraTopPadding,
            )
        }
    }
}

@Composable
private fun HeaderContent(
    title: String,
    titleColor: Color,
    onBack: () -> Unit,
    isLandscape: Boolean,
    showBack: Boolean = true,
    @DrawableRes leadingIconRes: Int? = null,
    safeStartPadding: Dp = 0.dp,
    safeEndPadding: Dp = 0.dp,
    extraTopPadding: Dp = 0.dp,
) {
    val adaptive = rememberAdaptiveSpec()
    val basePad: Dp = when {
        adaptive.compact -> 30.dp
        isLandscape      -> 40.dp
        else             -> 40.dp
    }
    val startPad = (safeStartPadding + basePad).coerceAtLeast(basePad)
    val endPad = (safeEndPadding + 16.dp).coerceAtLeast(16.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = startPad,
                end = endPad,
                top = (if (isLandscape) 6.dp else 8.dp) +
                    if (isLandscape) extraTopPadding else 0.dp,
                bottom = if (isLandscape) 6.dp else 8.dp
            ),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val headerScale = rememberSettingsTextScale()
        val iconSize = (24f * headerScale.coerceAtMost(1.25f)).dp
        val backInteractionSource = remember { MutableInteractionSource() }
        val titleRowModifier = Modifier
            .weight(1f)
            .expandTouchTarget(horizontal = 8.dp, vertical = 6.dp)
            .then(
                if (showBack) {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        // Remote users go back with the hardware BACK key; keeping
                        // this row focusable made every second-level screen start
                        // its D-pad order on the header instead of the first item.
                        .focusProperties { canFocus = false }
                        .clickable(
                            interactionSource = backInteractionSource,
                            indication = LocalIndication.current,
                            role = Role.Button,
                            onClick = onBack
                        )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .heightIn(min = iconSize)
        Row(
            modifier = titleRowModifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_24px),
                    contentDescription = stringResource(R.string.back),
                    tint = titleColor,
                    modifier = Modifier.size(iconSize)
                )
                Spacer(modifier = Modifier.width((15f * headerScale.coerceAtMost(1.2f)).dp))
            } else if (leadingIconRes != null) {
                Icon(
                    painter = painterResource(leadingIconRes),
                    contentDescription = null,
                    tint = titleColor,
                    modifier = Modifier.size(iconSize)
                )
                Spacer(modifier = Modifier.width((15f * headerScale.coerceAtMost(1.2f)).dp))
            }
            AutoResizeText(
                text = title,
                fontSize = settingsHeaderTitleTextSize(),
                minFontSize = settingsHeaderTitleMinTextSize(),
                fontWeight = FontWeight.Bold,
                color = titleColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
