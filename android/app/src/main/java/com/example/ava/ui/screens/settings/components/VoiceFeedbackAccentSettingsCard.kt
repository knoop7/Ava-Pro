package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.SliderDefaults
import com.example.ava.ui.haptic.TickSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ava.R
import com.example.ava.services.WakeRippleService
import com.example.ava.settings.PlayerSettings
import com.example.ava.ui.VoiceAccentColors
import com.example.ava.ui.screens.settings.ModernSwitch
import com.example.ava.ui.screens.settings.SettingRow
import com.example.ava.ui.screens.settings.SettingsDivider
import com.example.ava.ui.screens.settings.SimpleCard
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.getInputBackground
import com.example.ava.ui.screens.settings.getSliderInactiveColor
import com.example.ava.ui.screens.settings.settingsLabelColor
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController

@Composable
fun VoiceFeedbackAccentSettingsCard(
    playerState: PlayerSettings?,
    enabled: Boolean,
    onWakeWord1ColorChange: (String) -> Unit,
    onWakeWord2ColorChange: (String) -> Unit,
    onRippleEffectChange: (Boolean) -> Unit = {},
    onEdgeGlowChange: (Boolean) -> Unit = {},
    onWakeWord1LevelGainChange: (Float) -> Unit = {},
    onWakeWord2LevelGainChange: (Float) -> Unit = {},
    onWakeWord1SheerChange: (Float) -> Unit = {},
    onWakeWord2SheerChange: (Float) -> Unit = {},
    showHeader: Boolean = true,
) {
    SimpleCard {
        if (showHeader) {
            SettingRow(
                label = stringResource(R.string.settings_voice_feedback_accent_title),
                subLabel = stringResource(R.string.settings_voice_feedback_accent_desc)
            ) {
                Spacer(modifier = Modifier.size(1.dp))
            }

            SettingsDivider()
        }

        SettingRow(
            label = stringResource(R.string.settings_voice_ripple_effect),
            subLabel = stringResource(R.string.settings_voice_ripple_effect_desc),
        ) {
            ModernSwitch(
                checked = playerState?.enableVoiceRippleEffect ?: true,
                enabled = enabled,
                onCheckedChange = onRippleEffectChange,
            )
        }

        SettingsDivider()

        SettingRow(
            label = stringResource(R.string.settings_voice_edge_glow),
            subLabel = stringResource(R.string.settings_voice_edge_glow_desc),
        ) {
            ModernSwitch(
                checked = playerState?.enableVoiceEdgeGlow ?: true,
                enabled = enabled,
                onCheckedChange = onEdgeGlowChange,
            )
        }

        SettingsDivider()

        WakeWordAccentColorPicker(
            label = stringResource(R.string.settings_voice_wake_word_1_color),
            description = stringResource(R.string.settings_voice_wake_word_1_color_desc),
            selectedColor = playerState?.voiceWakeWord1AccentColor ?: "",
            defaultColor = VoiceAccentColors.WAKE_WORD_1,
            defaultHex = VoiceAccentColors.DEFAULT_WAKE_WORD_1_HEX,
            enabled = enabled,
            onColorSelected = onWakeWord1ColorChange
        )

        Spacer(modifier = Modifier.height(8.dp))

        EdgeGlowLevelGainSlider(
            gain = playerState?.edgeGlowLevelGainForWakeWord(0)
                ?: PlayerSettings.DEFAULT_EDGE_GLOW_LEVEL_GAIN,
            opacityMul = playerState?.edgeGlowOpacityMulForWakeWord(0) ?: 1f,
            enabled = enabled && (playerState?.enableVoiceEdgeGlow ?: true),
            previewColor = VoiceAccentColors.resolve(
                playerState?.voiceWakeWord1AccentColor.orEmpty(),
                VoiceAccentColors.WAKE_WORD_1
            ),
            onGainChange = onWakeWord1LevelGainChange,
        )

        Spacer(modifier = Modifier.height(4.dp))

        EdgeGlowSheerSlider(
            sheer = playerState?.edgeGlowSheerForWakeWord(0)
                ?: PlayerSettings.DEFAULT_EDGE_GLOW_SHEER,
            previewGain = playerState?.edgeGlowLevelGainForWakeWord(0)
                ?: PlayerSettings.DEFAULT_EDGE_GLOW_LEVEL_GAIN,
            enabled = enabled && (playerState?.enableVoiceEdgeGlow ?: true),
            previewColor = VoiceAccentColors.resolve(
                playerState?.voiceWakeWord1AccentColor.orEmpty(),
                VoiceAccentColors.WAKE_WORD_1
            ),
            onSheerChange = onWakeWord1SheerChange,
        )

        Spacer(modifier = Modifier.height(15.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(15.dp))

        WakeWordAccentColorPicker(
            label = stringResource(R.string.settings_voice_wake_word_2_color),
            description = stringResource(R.string.settings_voice_wake_word_2_color_desc),
            selectedColor = playerState?.voiceWakeWord2AccentColor ?: "",
            defaultColor = VoiceAccentColors.WAKE_WORD_2,
            defaultHex = VoiceAccentColors.DEFAULT_WAKE_WORD_2_HEX,
            enabled = enabled,
            onColorSelected = onWakeWord2ColorChange
        )

        Spacer(modifier = Modifier.height(8.dp))

        EdgeGlowLevelGainSlider(
            gain = playerState?.edgeGlowLevelGainForWakeWord(1)
                ?: PlayerSettings.DEFAULT_EDGE_GLOW_LEVEL_GAIN,
            opacityMul = playerState?.edgeGlowOpacityMulForWakeWord(1) ?: 1f,
            enabled = enabled && (playerState?.enableVoiceEdgeGlow ?: true),
            previewColor = VoiceAccentColors.resolve(
                playerState?.voiceWakeWord2AccentColor.orEmpty(),
                VoiceAccentColors.WAKE_WORD_2
            ),
            onGainChange = onWakeWord2LevelGainChange,
        )

        Spacer(modifier = Modifier.height(4.dp))

        EdgeGlowSheerSlider(
            sheer = playerState?.edgeGlowSheerForWakeWord(1)
                ?: PlayerSettings.DEFAULT_EDGE_GLOW_SHEER,
            previewGain = playerState?.edgeGlowLevelGainForWakeWord(1)
                ?: PlayerSettings.DEFAULT_EDGE_GLOW_LEVEL_GAIN,
            enabled = enabled && (playerState?.enableVoiceEdgeGlow ?: true),
            previewColor = VoiceAccentColors.resolve(
                playerState?.voiceWakeWord2AccentColor.orEmpty(),
                VoiceAccentColors.WAKE_WORD_2
            ),
            onSheerChange = onWakeWord2SheerChange,
        )
    }
}

@Composable
private fun EdgeGlowLevelGainSlider(
    gain: Float,
    opacityMul: Float,
    enabled: Boolean,
    previewColor: Int,
    onGainChange: (Float) -> Unit,
) {
    val context = LocalContext.current
    val stored = PlayerSettings.clampEdgeGlowLevelGain(gain)
    var slider by remember(stored) { mutableFloatStateOf(stored) }

    DisposableEffect(Unit) {
        onDispose {
            WakeRippleService.endEdgeGlowLevelPreview(context)
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled) {
            WakeRippleService.endEdgeGlowLevelPreview(context)
        }
    }

    SettingSliderLabelRow(
        title = stringResource(R.string.settings_voice_edge_glow_level),
        description = stringResource(R.string.settings_voice_edge_glow_level_desc),
    )
    TickSlider(
        value = slider,
        onValueChange = { value ->
            slider = value
            if (enabled) {
                WakeRippleService.previewEdgeGlowLevel(context, previewColor, value, opacityMul)
            }
        },
        onValueChangeFinished = {
            val clamped = PlayerSettings.clampEdgeGlowLevelGain(slider)
            slider = clamped
            onGainChange(clamped)
            WakeRippleService.endEdgeGlowLevelPreview(context)
        },
        enabled = enabled,
        valueRange = PlayerSettings.MIN_EDGE_GLOW_LEVEL_GAIN..PlayerSettings.MAX_EDGE_GLOW_LEVEL_GAIN,
        colors = SliderDefaults.colors(
            thumbColor = getAccentColor(),
            activeTrackColor = getAccentColor(),
            inactiveTrackColor = getSliderInactiveColor(),
            disabledThumbColor = getSliderInactiveColor(),
            disabledActiveTrackColor = getSliderInactiveColor(),
            disabledInactiveTrackColor = getSliderInactiveColor(),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledActiveTickColor = Color.Transparent,
            disabledInactiveTickColor = Color.Transparent,
        ),
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun EdgeGlowSheerSlider(
    sheer: Float,
    previewGain: Float,
    enabled: Boolean,
    previewColor: Int,
    onSheerChange: (Float) -> Unit,
) {
    val context = LocalContext.current
    val stored = PlayerSettings.clampEdgeGlowSheer(sheer)
    var slider by remember(stored) { mutableFloatStateOf(stored) }

    DisposableEffect(Unit) {
        onDispose {
            WakeRippleService.endEdgeGlowLevelPreview(context)
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled) {
            WakeRippleService.endEdgeGlowLevelPreview(context)
        }
    }

    SettingSliderLabelRow(
        title = stringResource(R.string.settings_voice_edge_glow_opacity),
        description = stringResource(R.string.settings_voice_edge_glow_opacity_desc),
    )
    TickSlider(
        value = slider,
        onValueChange = { value ->
            slider = value
            if (enabled) {
                WakeRippleService.previewEdgeGlowLevel(
                    context,
                    previewColor,
                    previewGain,
                    PlayerSettings.edgeGlowOpacityMul(value),
                )
            }
        },
        onValueChangeFinished = {
            val clamped = PlayerSettings.clampEdgeGlowSheer(slider)
            slider = clamped
            onSheerChange(clamped)
            WakeRippleService.endEdgeGlowLevelPreview(context)
        },
        enabled = enabled,
        valueRange = PlayerSettings.MIN_EDGE_GLOW_SHEER..PlayerSettings.MAX_EDGE_GLOW_SHEER,
        colors = SliderDefaults.colors(
            thumbColor = getAccentColor(),
            activeTrackColor = getAccentColor(),
            inactiveTrackColor = getSliderInactiveColor(),
            disabledThumbColor = getSliderInactiveColor(),
            disabledActiveTrackColor = getSliderInactiveColor(),
            disabledInactiveTrackColor = getSliderInactiveColor(),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledActiveTickColor = Color.Transparent,
            disabledInactiveTickColor = Color.Transparent,
        ),
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun WakeWordAccentColorPicker(
    label: String,
    description: String,
    selectedColor: String,
    defaultColor: Int,
    defaultHex: String,
    enabled: Boolean,
    onColorSelected: (String) -> Unit
) {
    var showCustomColorDialog by remember { mutableStateOf(false) }
    val isCustom = selectedColor.startsWith("#")
    val labelColor = settingsLabelColor()

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingRow(
            label = label,
            subLabel = description
        ) {
            Spacer(modifier = Modifier.size(1.dp))
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 0 — custom color (opens picker)
            item {
                CustomColorSwatch(
                    selectedColor = selectedColor,
                    selected = isCustom,
                    enabled = enabled,
                    onClick = { showCustomColorDialog = true }
                )
            }
            // 1 — default (built-in green / blue)
            item {
                PresetColorSwatch(
                    color = Color(defaultColor),
                    selected = selectedColor.isEmpty(),
                    enabled = enabled,
                    onClick = { onColorSelected("") }
                )
            }
            // 2–8 — seven rainbow presets
            items(VoiceAccentColors.RAINBOW_PRESETS) { (key, colorInt) ->
                PresetColorSwatch(
                    color = Color(colorInt),
                    selected = selectedColor == key,
                    enabled = enabled,
                    onClick = { onColorSelected(key) }
                )
            }
        }

        when {
            isCustom -> {
                Text(
                    text = stringResource(R.string.settings_quick_entity_color_custom) + " " + selectedColor,
                    fontSize = settingsBodyTextSize(base = 11f),
                    color = labelColor,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }
            selectedColor.isEmpty() -> {
                Text(
                    text = stringResource(R.string.settings_quick_entity_color_auto),
                    fontSize = settingsBodyTextSize(base = 11f),
                    color = getSettingsDescriptionColor(),
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }
        }

        if (showCustomColorDialog) {
            CustomAccentColorDialog(
                initialHex = if (isCustom) selectedColor else defaultHex,
                onDismiss = { showCustomColorDialog = false },
                onConfirm = { hex ->
                    onColorSelected(hex)
                    showCustomColorDialog = false
                }
            )
        }
    }
}

/** Index 0 — tap to open the HSV custom color picker. Reused by notification color pickers. */
@Composable
fun CustomColorSwatch(
    selectedColor: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val ringColor = settingsLabelColor()
    val inputBg = getInputBackground()
    val isCustom = selectedColor.startsWith("#")
    val customColor = if (isCustom) {
        try {
            Color(android.graphics.Color.parseColor(selectedColor))
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }
    val rainbowBrush = Brush.linearGradient(
        colors = VoiceAccentColors.RAINBOW_PRESETS.map { Color(it.second) }
    )

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(
                if (customColor != null) {
                    Modifier.background(customColor)
                } else {
                    Modifier.background(inputBg)
                }
            )
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) ringColor else Color.White.copy(alpha = 0.35f),
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (customColor == null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(rainbowBrush)
            )
            Icon(
                painter = painterResource(R.drawable.mdi_plus),
                contentDescription = stringResource(R.string.settings_quick_entity_color_custom),
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun PresetColorSwatch(
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val ringColor = settingsLabelColor()
    val lightTheme = getInputBackground().luminance() > 0.5f
    val nearWhite = color.luminance() > 0.92f
    val idleRing = if (lightTheme && nearWhite) {
        Color(0xFFD1D5DB)
    } else {
        Color.White.copy(alpha = 0.35f)
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) ringColor else idleRing,
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick)
    )
}

@Composable
fun CustomAccentColorDialog(
    initialHex: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val controller = rememberColorPickerController()
    var hexColor by remember { mutableStateOf(initialHex) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val pickerSize = if (isLandscape) 140.dp else 200.dp
    val dialogScope = remember { DialogScope() }
    val accent = getAccentColor()

    dialogScope.ActionDialog(
        title = stringResource(R.string.settings_quick_entity_color_custom),
        confirmLabel = stringResource(R.string.label_ok),
        dismissLabel = stringResource(R.string.label_cancel),
        confirmColor = accent,
        compact = true,
        maxWidth = if (isLandscape) 420.dp else null,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = !isLandscape,
        ),
        onDismissRequest = onDismiss,
        onConfirmRequest = { onConfirm(hexColor) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HsvColorPicker(
                modifier = Modifier.size(pickerSize),
                controller = controller,
                initialColor = runCatching {
                    Color(android.graphics.Color.parseColor(initialHex))
                }.getOrElse { accent },
                onColorChanged = { colorEnvelope ->
                    hexColor = "#" + colorEnvelope.hexCode.takeLast(6)
                },
            )
            Spacer(modifier = Modifier.height(10.dp))
            BrightnessSlider(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
                controller = controller,
            )
        }
    }
}
