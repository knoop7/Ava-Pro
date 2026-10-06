package com.example.ava.ui.screens.settings.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Phone / small panels stay at 1× (no lift). Growth starts at tablet shortest-side.
 */
private const val SETTINGS_PHONE_LOCK_DP = 600f
/** Soft cap before low-dpi landscape boost. */
private const val SETTINGS_SCALE_MAX = 1.38f
/**
 * Absolute cap after boosts. X08A-class mdpi landscape needs headroom above the
 * old 1.45 so body type and trailing chevrons stay readable on a large physical panel.
 */
private const val SETTINGS_SCALE_ABS_MAX = 1.58f

/**
 * Shared text scale for settings. Phones keep the original size; tablets/large
 * panels grow with shortest-side. Large mdpi landscape (X08A 1280×800 @ 160dpi)
 * gets an extra boost — same class of problem as [com.example.ava.ui.screens.home.HomeSidebarMetrics].
 */
@Composable
fun rememberSettingsTextScale(): Float {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val shortestSideDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
    val landscape = !com.example.ava.ui.rememberCompactSquareScreen() &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (shortestSideDp < SETTINGS_PHONE_LOCK_DP) {
        // Match pre-change detail typography: portrait 1×, landscape +8%.
        return if (landscape) 1.08f else 1f
    }
    // Grow from the 600dp floor so there is no jump the moment we leave the phone band.
    val base = (shortestSideDp / SETTINGS_PHONE_LOCK_DP).coerceIn(1f, SETTINGS_SCALE_MAX)
    val landscapeBoost = if (landscape) 1.06f else 1f
    // mdpi/hdpi- landscape panels: dp≈px on a wide physical screen → type looks tiny.
    val lowDpiLandscapeBoost =
        if (landscape && shortestSideDp >= 720f && density <= 1.15f) 1.14f else 1f
    return (base * landscapeBoost * lowDpiLandscapeBoost).coerceIn(1f, SETTINGS_SCALE_ABS_MAX)
}

/** @deprecated Prefer [rememberSettingsTextScale]; kept for call-site compatibility. */
@Composable
fun rememberSettingsLandscapeTextScale(): Float = rememberSettingsTextScale()

@Composable
fun settingsTitleTextSize(base: Float = 15f): TextUnit =
    (base * rememberSettingsTextScale()).sp

@Composable
fun settingsBodyTextSize(base: Float = 13f): TextUnit =
    (base * rememberSettingsTextScale()).sp

@Composable
fun settingsCaptionTextSize(base: Float = 11f): TextUnit =
    (base * rememberSettingsTextScale()).sp

@Composable
fun settingsBodyLineHeight(base: Float = 15.5f): TextUnit =
    (base * rememberSettingsTextScale()).sp

/** Tracking for wrapped setting hints. `0.035.em` ≈ 0.46sp at 13sp. */
fun settingsBodyLetterSpacing(): TextUnit = 0.035.em

/** Title → description gap used by [CollapsibleDescriptionText]. */
@Composable
fun settingsDescriptionTopPadding(base: Float = 5f): Dp =
    (base * rememberSettingsTextScale().coerceAtMost(1.2f)).dp

@Composable
fun settingsHeaderTitleTextSize(base: Float = 19f): TextUnit =
    (base * rememberSettingsTextScale()).sp

@Composable
fun settingsHeaderTitleMinTextSize(base: Float = 15f): TextUnit =
    (base * rememberSettingsTextScale().coerceAtMost(1.2f)).sp

/** Trailing ? help mark — slightly smaller than chevron icon sizing. */
@Composable
fun settingsHelpMarkTextSize(base: Float = 16f): TextUnit =
    (base * rememberSettingsTextScale()).sp

/** Material [KeyboardArrowRight] size for the trailing settings chevron slot. */
@Composable
fun settingsChevronIconSize(base: Float = 26f): Dp =
    (base * rememberSettingsTextScale()).dp

/** Shared trailing chevron — thick Material arrow; use everywhere in settings. */
@Composable
fun SettingsChevronIcon(
    tint: Color = Color(0xFF94A3B8),
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    base: Float = 26f,
) {
    Icon(
        imageVector = Icons.Filled.KeyboardArrowRight,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(settingsChevronIconSize(base)),
    )
}

/** Root settings use the same scale as detail pages. */
@Composable
fun rememberRootSettingsTextScale(): Float = rememberSettingsTextScale()

@Composable
fun rootSettingsTitleTextSize(base: Float = 15f): TextUnit =
    settingsTitleTextSize(base)

@Composable
fun rootSettingsBodyTextSize(base: Float = 13f): TextUnit =
    settingsBodyTextSize(base)
