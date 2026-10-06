package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.ui.glass.liquidGlass
import com.example.ava.ui.glass.rememberLiquidGlassState
import com.example.ava.ui.screens.settings.getAccentColor

/** Scroll clearance so the last list item sits above [SettingsBottomDockCapsule]. */
val SettingsBottomDockClearance: Dp = 108.dp

/**
 * Landscape cap for the capsule itself (not the full-bleed dissolve).
 * Must be applied *before* [Modifier.fillMaxWidth] or the min-width from
 * fill wins and the dock stretches edge-to-edge.
 */
val SettingsBottomDockLandscapeMaxWidth: Dp = 560.dp

/**
 * Style A floating dock: soft page-bg dissolve + glass capsule with optional
 * secondary action and a primary accent button.
 *
 * @param contentMaxWidth when set, the capsule is centered at this max width.
 *   Landscape always caps even if this is omitted, so Preview/Save/New never
 *   stretch edge-to-edge. The dissolve still spans the full screen.
 */
@Composable
fun SettingsBottomDockCapsule(
    pageBg: Color,
    isDarkMode: Boolean,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    secondaryEnabled: Boolean = true,
    contentMaxWidth: Dp = Dp.Unspecified,
) {
    val accent = getAccentColor()
    val capsuleBg = if (isDarkMode) Color(0x8C1A1A1D) else Color(0x99FFFFFF)
    val ghostBg = if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    val ghostFg = Color(0xFF94A3B8)
    val line = if (isDarkMode) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    // Dark accent is warm/light → dark label; light-mode accent is usually saturated → white.
    val primaryFg = if (isDarkMode) Color(0xFF1A1510) else Color.White
    val sidePad = settingsListHorizontalPadding()
    val dissolveEnd = pageBg.copy(alpha = 0.42f)
    val isLandscape = LocalConfiguration.current.screenWidthDp >
        LocalConfiguration.current.screenHeightDp
    val capsuleMaxWidth = when {
        contentMaxWidth != Dp.Unspecified -> contentMaxWidth
        isLandscape -> SettingsBottomDockLandscapeMaxWidth
        else -> Dp.Unspecified
    }
    val glass by rememberLiquidGlassState()
    val capsuleShape = RoundedCornerShape(22.dp)

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // Soft dissolve into the page so the capsule doesn't hard-cut content.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to pageBg.copy(alpha = 0.18f),
                        1f to dissolveEnd,
                    ),
                ),
        )
        // Translucent zone runs to the physical screen bottom (nav-bar inset lives inside).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(dissolveEnd)
                .navigationBarsPadding()
                .padding(bottom = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .then(
                        if (capsuleMaxWidth != Dp.Unspecified) {
                            // widthIn must wrap fillMaxWidth. The reverse sets minWidth
                            // to the parent, so the max cap is ignored on wide panels.
                            Modifier.widthIn(max = capsuleMaxWidth)
                        } else {
                            Modifier
                        },
                    )
                    .fillMaxWidth()
                    .padding(horizontal = sidePad)
                    .then(
                        if (glass.enabled) {
                            // Glass paints its own specular rim; skip the flat hairline.
                            Modifier.liquidGlass(glass, capsuleShape, isDarkMode, capsuleBg)
                        } else {
                            Modifier.border(1.dp, line, capsuleShape)
                        },
                    ),
                shape = capsuleShape,
                color = if (glass.enabled) Color.Transparent else capsuleBg,
                shadowElevation = 3.dp,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (secondaryLabel != null && onSecondary != null) {
                        Button(
                            onClick = onSecondary,
                            enabled = secondaryEnabled,
                            modifier = Modifier,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 0.dp,
                                pressedElevation = 0.dp,
                                disabledElevation = 0.dp,
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ghostBg,
                                contentColor = ghostFg,
                                disabledContainerColor = ghostBg.copy(alpha = 0.45f),
                                disabledContentColor = ghostFg.copy(alpha = 0.45f),
                            ),
                        ) {
                            Text(
                                text = secondaryLabel,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Button(
                        onClick = onPrimary,
                        enabled = primaryEnabled,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp,
                            disabledElevation = 0.dp,
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent.copy(alpha = 0.72f),
                            contentColor = primaryFg,
                            disabledContainerColor = accent.copy(alpha = 0.22f),
                            disabledContentColor = primaryFg.copy(alpha = 0.45f),
                        ),
                    ) {
                        Text(
                            text = primaryLabel,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
