package com.example.ava.ui.screens.settings.components

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ava.ui.rememberAdaptiveSpec
import com.example.ava.ui.screens.settings.LocalSettingsSplitActive

/** Horizontal inset inside [com.example.ava.ui.screens.settings.SimpleCard]. */
val SettingsCardInnerHorizontalPadding = 24.dp

/** Outer top/bottom inset on [com.example.ava.ui.screens.settings.SimpleCard]. */
val SettingsSimpleCardVerticalPadding = 8.dp

@Composable
fun settingsCardInnerHorizontalPadding(): Dp = SettingsCardInnerHorizontalPadding

/** Aligns section labels (outside cards) with card content text. */
@Composable
fun settingsSectionLabelStartPadding(): Dp = SettingsCardInnerHorizontalPadding

enum class SettingsScaleTier {
    PHONE,
    TABLET,
    LARGE,
    XLARGE,
}

@Composable
fun rememberSettingsScaleTier(): SettingsScaleTier {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val shortestSideDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val longestSideDp = maxOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val landscape = !com.example.ava.ui.rememberCompactSquareScreen() &&
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // X08A-class: sw≈800 @ mdpi landscape — TABLET tier left cards/chrome looking undersized.
    val lowDpiLandscapePromote =
        landscape && shortestSideDp >= 720 && density <= 1.15f
    return when {
        shortestSideDp >= 1440 || longestSideDp >= 2560 -> SettingsScaleTier.XLARGE
        shortestSideDp >= 960 || longestSideDp >= 1920 -> SettingsScaleTier.LARGE
        lowDpiLandscapePromote -> SettingsScaleTier.LARGE
        shortestSideDp >= 600 || longestSideDp >= 1280 -> SettingsScaleTier.TABLET
        else -> SettingsScaleTier.PHONE
    }
}

@Composable
fun settingsListHorizontalPadding(): Dp {
    val adaptive = rememberAdaptiveSpec()
    return when (rememberSettingsScaleTier()) {
        SettingsScaleTier.XLARGE -> 34.dp
        SettingsScaleTier.LARGE -> 30.dp
        SettingsScaleTier.TABLET -> 26.dp
        SettingsScaleTier.PHONE -> if (adaptive.compact) 16.dp else 20.dp
    }
}

@Composable
fun settingsListVerticalPadding(): Dp {
    val adaptive = rememberAdaptiveSpec()
    return when (rememberSettingsScaleTier()) {
        SettingsScaleTier.XLARGE -> 18.dp
        SettingsScaleTier.LARGE -> 16.dp
        SettingsScaleTier.TABLET -> 14.dp
        SettingsScaleTier.PHONE -> if (adaptive.compact) 10.dp else 12.dp
    }
}

@Composable
fun settingsDetailListContentPadding(extraBottom: Dp = 0.dp): PaddingValues {
    val vertical = settingsListVerticalPadding()
    val horizontal = settingsListHorizontalPadding()
    val top = if (LocalSettingsSplitActive.current) {
        (vertical - SettingsSimpleCardVerticalPadding).coerceAtLeast(0.dp)
    } else {
        vertical
    }
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = top,
        // Extra clearance when a bottom pull-handle overlays the list (NS / AEC preview cards).
        bottom = vertical + extraBottom,
    )
}

@Composable
fun settingsMainListContentPadding(handleClearance: Boolean = true): PaddingValues {
    // Split index cards already pad inside the card. Only a 10dp nudge
    // toward the detail pane — not a second gutter on both sides.
    val split = LocalSettingsSplitActive.current
    val horizontal = if (split) 0.dp else settingsListHorizontalPadding()
    return PaddingValues(
        start = if (split) 10.dp else horizontal,
        end = horizontal,
        top = settingsListVerticalPadding(),
        bottom = if (handleClearance) 40.dp else settingsListVerticalPadding(),
    )
}

@Composable
fun settingsMainGridContentPadding(handleClearance: Boolean = true): PaddingValues {
    val horizontal = settingsListHorizontalPadding()
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = when (rememberSettingsScaleTier()) {
            SettingsScaleTier.XLARGE -> 26.dp
            SettingsScaleTier.LARGE -> 22.dp
            SettingsScaleTier.TABLET -> 20.dp
            SettingsScaleTier.PHONE -> if (rememberAdaptiveSpec().expanded) 18.dp else 16.dp
        },
        bottom = if (handleClearance) 40.dp else settingsListVerticalPadding(),
    )
}

/**
 * Bottom inset of the system navigation bar for handle / list chrome.
 *
 * Immersive sticky on API 28 often leaves the 3-button bar visible while
 * [WindowInsets.navigationBars] reports 0 (the hide flag is set, the OEM
 * never actually hid the bar). Fall back to the framework dimen on Pie.
 */
@Composable
fun rememberSettingsNavigationBarBottom(): Dp {
    val visible = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    if (visible > 0.dp) return visible
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return 0.dp
    val context = LocalContext.current
    val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    if (id <= 0) return 0.dp
    val px = context.resources.getDimensionPixelSize(id)
    return with(LocalDensity.current) { px.toDp() }
}

/** Nav-bar space not already applied as Scaffold [consumedBottom]. */
@Composable
fun rememberUnconsumedNavigationBarBottom(consumedBottom: Dp): Dp =
    (rememberSettingsNavigationBarBottom() - consumedBottom).coerceAtLeast(0.dp)

/** Cap for handle-opened sheets off split: wrap content, don't stretch toward the top. */
@Composable
fun settingsHandleSheetMaxHeight(): Dp {
    val configuration = LocalConfiguration.current
    val height = configuration.screenHeightDp
    val landscape = !com.example.ava.ui.rememberCompactSquareScreen() &&
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val fraction = when {
        landscape -> 0.64f
        rememberSettingsScaleTier() == SettingsScaleTier.PHONE -> 0.78f
        else -> 0.70f
    }
    return (height * fraction).dp
}
