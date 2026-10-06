package com.example.ava.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SliderDefaults
import com.example.ava.ui.haptic.TickSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.services.OverlayLayerSplit
import com.example.ava.settings.DisplayScale
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.ava.settings.SettingsStyleSession
import com.example.ava.settings.SettingsStyleSettings
import com.example.ava.settings.SettingsStyleSettingsStore
import com.example.ava.settings.settingsStyleSettingsStore
import com.example.ava.ui.Screen
import com.example.ava.ui.SystemBarsMode
import com.example.ava.ui.glass.LiquidGlassState
import com.example.ava.ui.glass.liquidGlass
import com.example.ava.ui.glass.rememberLiquidGlassState
import com.example.ava.ui.prefs.rememberStringPreference
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.DropdownSelectSetting
import com.example.ava.ui.screens.settings.components.SettingsChevronIcon
import com.example.ava.ui.screens.settings.components.SettingSliderLabelRow
import com.example.ava.ui.screens.settings.components.SettingsCardInnerHorizontalPadding
import com.example.ava.ui.screens.settings.components.SettingsSimpleCardVerticalPadding
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsStyleSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStyleSettingsStore(context.settingsStyleSettingsStore) }
    val experimentalSettingsStore = remember { ExperimentalSettingsStore(context) }
    val systemBarsHaEnabled by experimentalSettingsStore.systemBarsHaEnabled.collectAsStateWithLifecycle(false)
    val landscapeSplit by SettingsStyleSession.landscapeSplit.collectAsStateWithLifecycle()
    val mainHandle by SettingsStyleSession.mainHandle.collectAsStateWithLifecycle()
    val liquidGlassEnabled by SettingsStyleSession.liquidGlassEnabled.collectAsStateWithLifecycle()
    val liquidGlassIntensity by SettingsStyleSession.liquidGlassIntensity.collectAsStateWithLifecycle()
    val liquidGlassBlur by SettingsStyleSession.liquidGlassBlur.collectAsStateWithLifecycle()
    val liquidGlassPressGlow by SettingsStyleSession.liquidGlassPressGlow.collectAsStateWithLifecycle()

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_style),
    ) {
        item(key = "overlay_control") {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_style_overlay_control),
                    subLabel = stringResource(R.string.settings_style_overlay_control_entry_desc),
                    onClick = {
                        navController.navigate(Screen.SETTINGS_STYLE_OVERLAY) { launchSingleTop = true }
                    },
                ) {
                    SettingsChevronIcon(tint = Color(0xFF94A3B8))
                }
            }
        }
        item(key = "system_bars") {
            SystemBarsStyleCard(
                haEnabled = systemBarsHaEnabled,
                onHaEnabledChange = { enabled ->
                    scope.launch {
                        experimentalSettingsStore.setSystemBarsHaEnabled(enabled)
                        restartVoiceSatelliteServiceIfRunning()
                    }
                },
            )
        }
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_style_landscape_split),
                    subLabel = stringResource(R.string.settings_style_landscape_split_desc),
                ) {
                    ModernSwitch(
                        checked = landscapeSplit,
                        onCheckedChange = { enabled ->
                            SettingsStyleSession.setLandscapeSplit(enabled)
                            scope.launch { store.landscapeSplit.set(enabled) }
                        },
                    )
                }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_style_main_handle),
                    subLabel = stringResource(R.string.settings_style_main_handle_desc),
                ) {
                    ModernSwitch(
                        checked = mainHandle,
                        onCheckedChange = { enabled ->
                            SettingsStyleSession.setMainHandle(enabled)
                            scope.launch { store.mainHandle.set(enabled) }
                        },
                    )
                }
                SettingsDivider()
                Spacer(modifier = Modifier.height(8.dp))

                val appliedScale = remember { DisplayScale.getScale(context) }
                var scaleSlider by remember { mutableFloatStateOf(appliedScale) }
                SettingSliderLabelRow(
                    title = stringResource(R.string.settings_style_display_scale),
                    description = stringResource(R.string.settings_style_display_scale_desc),
                    badgeText = "${(scaleSlider * 100).roundToInt()}%",
                )
                TickSlider(
                    value = scaleSlider,
                    onValueChange = { scaleSlider = it },
                    onValueChangeFinished = {
                        // 5% detents; 100% is exactly reachable and short-circuits DisplayScale.wrap.
                        val snapped = (scaleSlider * 20f).roundToInt() / 20f
                        scaleSlider = snapped
                        if (snapped != appliedScale) {
                            DisplayScale.setScale(context, snapped)
                            context.findActivity()?.recreate()
                        }
                    },
                    valueRange = DisplayScale.MIN_SCALE..DisplayScale.MAX_SCALE,
                    steps = 24,
                    colors = SliderDefaults.colors(
                        thumbColor = getAccentColor(),
                        activeTrackColor = getAccentColor(),
                        inactiveTrackColor = getSliderInactiveColor(),
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item(key = "liquid_glass") {
            // Preview: this one card wears the glass while the user is interacting
            // (toggle on / slider drag), holds ~2 s, then fades back to the flat card.
            // Every interaction restarts the hold, so dragging keeps it visible.
            val glass by rememberLiquidGlassState()
            val previewAlpha = remember { Animatable(0f) }
            var previewNonce by remember { mutableIntStateOf(0) }
            LaunchedEffect(liquidGlassEnabled) {
                if (!liquidGlassEnabled) {
                    previewAlpha.animateTo(0f, tween(PREVIEW_FADE_OUT_MS, easing = FastOutSlowInEasing))
                }
            }
            LaunchedEffect(previewNonce, liquidGlassEnabled) {
                if (!liquidGlassEnabled || previewNonce == 0) return@LaunchedEffect
                // Already showing: only reset the hold. Restarting fade-in from
                // 0 on every slider tick is what made the card pop.
                if (previewAlpha.value < 0.995f) {
                    previewAlpha.animateTo(1f, tween(PREVIEW_FADE_IN_MS, easing = FastOutSlowInEasing))
                }
                delay(PREVIEW_HOLD_MS)
                previewAlpha.animateTo(0f, tween(PREVIEW_FADE_OUT_MS, easing = FastOutSlowInEasing))
            }
            val bumpPreview = { previewNonce++ }

            LiquidGlassPreviewCard(glass = glass, glassAlpha = previewAlpha.value) {
                SettingRow(
                    label = stringResource(R.string.settings_style_liquid_glass),
                    subLabel = stringResource(R.string.settings_style_liquid_glass_desc),
                ) {
                    ModernSwitch(
                        checked = liquidGlassEnabled,
                        onCheckedChange = { enabled ->
                            SettingsStyleSession.setLiquidGlassEnabled(enabled)
                            scope.launch { store.liquidGlassEnabled.set(enabled) }
                            if (enabled) bumpPreview()
                        },
                    )
                }
                if (liquidGlassEnabled) {
                    SettingsDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    var intensitySlider by remember(liquidGlassIntensity) {
                        mutableFloatStateOf(liquidGlassIntensity.toFloat())
                    }
                    SettingSliderLabelRow(
                        title = stringResource(R.string.settings_style_liquid_glass_intensity),
                        description = stringResource(R.string.settings_style_liquid_glass_intensity_desc),
                        badgeText = "${intensitySlider.roundToInt()}%",
                    )
                    TickSlider(
                        value = intensitySlider,
                        onValueChange = {
                            intensitySlider = it
                            // Session updates live so the glass on this card tracks the thumb.
                            SettingsStyleSession.setLiquidGlassIntensity(it.roundToInt())
                            bumpPreview()
                        },
                        onValueChangeFinished = {
                            val snapped = (intensitySlider / 5f).roundToInt() * 5
                            intensitySlider = snapped.toFloat()
                            SettingsStyleSession.setLiquidGlassIntensity(snapped)
                            scope.launch { store.liquidGlassIntensity.set(snapped) }
                            bumpPreview()
                        },
                        valueRange = SettingsStyleSettings.LIQUID_GLASS_MIN_INTENSITY.toFloat()..
                            SettingsStyleSettings.LIQUID_GLASS_MAX_INTENSITY.toFloat(),
                        steps = 19,
                        colors = SliderDefaults.colors(
                            thumbColor = getAccentColor(),
                            activeTrackColor = getAccentColor(),
                            inactiveTrackColor = getSliderInactiveColor(),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent,
                        ),
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    SettingsDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingRow(
                        label = stringResource(R.string.settings_style_liquid_glass_blur),
                        subLabel = stringResource(R.string.settings_style_liquid_glass_blur_desc),
                    ) {
                        ModernSwitch(
                            checked = liquidGlassBlur,
                            onCheckedChange = { enabled ->
                                SettingsStyleSession.setLiquidGlassBlur(enabled)
                                scope.launch { store.liquidGlassBlur.set(enabled) }
                                bumpPreview()
                            },
                        )
                    }
                    SettingsDivider()
                    SettingRow(
                        label = stringResource(R.string.settings_style_liquid_glass_press_glow),
                        subLabel = stringResource(R.string.settings_style_liquid_glass_press_glow_desc),
                    ) {
                        ModernSwitch(
                            checked = liquidGlassPressGlow,
                            onCheckedChange = { enabled ->
                                SettingsStyleSession.setLiquidGlassPressGlow(enabled)
                                scope.launch { store.liquidGlassPressGlow.set(enabled) }
                                bumpPreview()
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SystemBarsStyleCard(
    haEnabled: Boolean,
    onHaEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val stored by rememberStringPreference(
        prefs,
        SystemBarsMode.PREF_KEY,
        SystemBarsMode.HIDE_NAV.storage,
    )
    val selected = SystemBarsMode.fromStorage(stored)
    val hideNavLabel = stringResource(R.string.settings_system_bars_hide_nav)
    val showLabel = stringResource(R.string.settings_system_bars_show)
    val hideStatusLabel = stringResource(R.string.settings_system_bars_hide_status)
    val hideBothLabel = stringResource(R.string.settings_system_bars_hide_both)
    val hideNavDesc = stringResource(R.string.settings_system_bars_hide_nav_desc)
    val showDesc = stringResource(R.string.settings_system_bars_show_desc)
    val hideStatusDesc = stringResource(R.string.settings_system_bars_hide_status_desc)
    val hideBothDesc = stringResource(R.string.settings_system_bars_hide_both_desc)
    SimpleCard {
        DropdownSelectSetting(
            name = stringResource(R.string.settings_system_bars),
            description = when (selected) {
                SystemBarsMode.HIDE_NAV -> hideNavDesc
                SystemBarsMode.SHOW -> showDesc
                SystemBarsMode.HIDE_STATUS -> hideStatusDesc
                SystemBarsMode.HIDE_BOTH -> hideBothDesc
            },
            selected = selected,
            items = listOf(
                SystemBarsMode.HIDE_NAV,
                SystemBarsMode.SHOW,
                SystemBarsMode.HIDE_STATUS,
                SystemBarsMode.HIDE_BOTH,
            ),
            value = { mode ->
                when (mode) {
                    SystemBarsMode.HIDE_NAV -> hideNavLabel
                    SystemBarsMode.SHOW -> showLabel
                    SystemBarsMode.HIDE_STATUS -> hideStatusLabel
                    SystemBarsMode.HIDE_BOTH -> hideBothLabel
                }
            },
            onSelect = { mode ->
                prefs.edit().putString(SystemBarsMode.PREF_KEY, mode.storage).apply()
            },
        )
        SettingsDivider()
        SettingRow(
            label = stringResource(R.string.settings_system_bars_ha_display),
            subLabel = stringResource(R.string.settings_system_bars_ha_display_desc),
        ) {
            ModernSwitch(
                checked = haEnabled,
                onCheckedChange = onHaEnabledChange,
            )
        }
    }
}

@Composable
fun OverlayControlSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStyleSettingsStore(context.settingsStyleSettingsStore) }
    val enabled by SettingsStyleSession.overlaySplitEnabled.collectAsStateWithLifecycle()
    val ratioLeft by SettingsStyleSession.overlaySplitRatioLeft.collectAsStateWithLifecycle()
    val ratioRight by SettingsStyleSession.overlaySplitRatioRight.collectAsStateWithLifecycle()

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_style_overlay_control),
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_style_overlay_control),
                    subLabel = stringResource(R.string.settings_style_overlay_control_desc),
                ) {
                    ModernSwitch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            SettingsStyleSession.setOverlaySplitEnabled(on)
                            scope.launch { store.overlaySplitEnabled.set(on) }
                            OverlayLayerSplit.sync()
                        },
                    )
                }
            }
        }
        if (enabled) {
            item { SettingsSectionLabel(stringResource(R.string.settings_browser_section_split_layout)) }
            item {
                SimpleCard {
                    SettingRow(
                        label = stringResource(R.string.settings_browser_split_view_ratio),
                        subLabel = stringResource(R.string.settings_browser_split_view_ratio_desc),
                    ) {
                        SplitRatioEditor(
                            left = ratioLeft,
                            right = ratioRight,
                            onCommit = { left, right ->
                                SettingsStyleSession.setOverlaySplitRatio(left, right)
                                scope.launch { store.setOverlaySplitRatio(left, right) }
                                OverlayLayerSplit.sync()
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val PREVIEW_FADE_IN_MS = 360
private const val PREVIEW_HOLD_MS = 2_000L
private const val PREVIEW_FADE_OUT_MS = 480

/**
 * Same geometry as [SimpleCard] (32dp corners, identical paddings). Always the
 * same tree — swapping to [SimpleCard] at alpha 0 remounted the rows and popped.
 * [Modifier.liquidGlass] at alpha 0 is already a flat fill.
 */
@Composable
private fun LiquidGlassPreviewCard(
    glass: LiquidGlassState,
    glassAlpha: Float,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDarkMode = isDarkModeEnabled()
    val flat = getDialogBackground()
    val shape = RoundedCornerShape(32.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SettingsSimpleCardVerticalPadding)
            .liquidGlass(glass, shape, isDarkMode, flat, alpha = glassAlpha)
            .padding(SettingsCardInnerHorizontalPadding),
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
