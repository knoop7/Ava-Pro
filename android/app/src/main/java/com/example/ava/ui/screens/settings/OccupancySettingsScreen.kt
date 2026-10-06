package com.example.ava.ui.screens.settings

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SliderDefaults
import com.example.ava.ui.haptic.TickSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.sensor.PresenceFusionEngine
import com.example.ava.settings.CameraMode
import com.example.ava.settings.ExperimentalSettings
import com.example.ava.ui.AvaToast
import com.example.ava.ui.screens.settings.components.SettingSliderLabelRow
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsCaptionTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private enum class OccupancySource {
    Face,
    Motion,
    Touch,
    Vibration,
    Proximity,
    Voiceprint,
}

@Composable
fun OccupancySettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val experimentalState by viewModel.experimentalSettingsState.collectAsStateWithLifecycle(null)
    val settings = experimentalState ?: ExperimentalSettings()
    val microphoneState by viewModel.microphoneSettingsState.collectAsStateWithLifecycle(null)

    val cameraOn = settings.cameraEnabled
    val videoMode = try {
        CameraMode.valueOf(settings.cameraMode)
    } catch (_: Exception) {
        CameraMode.SNAPSHOT
    } == CameraMode.VIDEO
    val faceAvailable = cameraOn && videoMode && settings.personDetectionEnabled
    val motionAvailable = cameraOn && videoMode
    val touchAvailable = settings.screenTouchSensorEnabled
    val proximityAvailable = settings.proximitySensorEnabled
    val voiceprintAvailable = microphoneState?.voicePrintEnabled == true
    // Vibration needs no companion feature, only the (nearly universal) accelerometer.
    val vibrationAvailable = remember {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    val settingsRoot = stringResource(R.string.label_settings)
    val facePath = occupancyEnableHint(
        stringResource(R.string.settings_occupancy_source_face),
        settingsRoot,
        stringResource(R.string.settings_group_experimental),
        stringResource(R.string.settings_camera_enabled),
        stringResource(R.string.settings_camera_mode_video),
        stringResource(R.string.settings_person_detection),
    )
    val motionPath = occupancyEnableHint(
        stringResource(R.string.settings_occupancy_source_motion),
        settingsRoot,
        stringResource(R.string.settings_group_experimental),
        stringResource(R.string.settings_camera_enabled),
        stringResource(R.string.settings_camera_mode_video),
    )
    val touchPath = occupancyEnableHint(
        stringResource(R.string.settings_occupancy_source_touch),
        settingsRoot,
        stringResource(R.string.settings_group_service),
        stringResource(R.string.settings_screen_touch),
    )
    val proximityPath = occupancyEnableHint(
        stringResource(R.string.settings_occupancy_source_proximity),
        settingsRoot,
        stringResource(R.string.settings_group_service),
        stringResource(R.string.settings_proximity_sensor),
    )
    val voiceprintPath = occupancyEnableHint(
        stringResource(R.string.settings_occupancy_source_voiceprint),
        settingsRoot,
        stringResource(R.string.settings_group_connection),
        stringResource(R.string.settings_voice_print_entry_title),
        stringResource(R.string.settings_voice_print_enabled),
    )
    // No settings path can fix missing hardware; the tile explains itself instead.
    val vibrationHint = stringResource(R.string.settings_occupancy_no_accelerometer)

    val threshold = settings.resolvedOccupancyThresholdPercent()
    val leaveIndex = ExperimentalSettings.occupancyLeaveLadderIndex(settings.occupancyLeaveSeconds)
    var thresholdSlider by remember(threshold) { mutableFloatStateOf(threshold.toFloat()) }
    var leaveSlider by remember(leaveIndex) { mutableFloatStateOf(leaveIndex.toFloat()) }
    val leaveLadder = ExperimentalSettings.OCCUPANCY_LEAVE_LADDER
    val leaveSeconds = leaveLadder[
        leaveSlider.toInt().coerceIn(0, leaveLadder.lastIndex)
    ]

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_occupancy),
    ) {
        item(key = "occupancy_master") {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_occupancy),
                    subLabel = stringResource(R.string.settings_occupancy_desc),
                ) {
                    ModernSwitch(
                        checked = settings.occupancyEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                viewModel.saveOccupancyEnabled(enabled)
                            }
                        },
                    )
                }
            }
        }

        if (settings.occupancyEnabled) {
            item(key = "occupancy_params") {
                val liveProbability by PresenceFusionEngine.probability.collectAsStateWithLifecycle()
                val liveOccupied by PresenceFusionEngine.occupied.collectAsStateWithLifecycle()
                SettingsSectionLabel(stringResource(R.string.settings_occupancy_params))
                SimpleCard {
                    OccupancyGauge(
                        percent = (liveProbability * 100f).roundToInt().coerceIn(0, 100),
                        occupied = liveOccupied,
                    )
                    OccupancyDividerLabel(stringResource(R.string.settings_occupancy_threshold_group))
                    SettingsInsetWell {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            SettingSliderLabelRow(
                                title = stringResource(R.string.settings_occupancy_threshold),
                                badgeText = "${thresholdSlider.toInt()}%",
                            )
                            TickSlider(
                                value = thresholdSlider,
                                onValueChange = { thresholdSlider = it },
                                onValueChangeFinished = {
                                    val snapped = ExperimentalSettings.coerceOccupancyThresholdPercent(
                                        thresholdSlider.toInt(),
                                    )
                                    thresholdSlider = snapped.toFloat()
                                    coroutineScope.launch {
                                        viewModel.saveOccupancyThresholdPercent(snapped)
                                    }
                                },
                                valueRange = ExperimentalSettings.OCCUPANCY_THRESHOLD_MIN.toFloat()..
                                    ExperimentalSettings.OCCUPANCY_THRESHOLD_MAX.toFloat(),
                                steps = occupancySliderSteps(
                                    ExperimentalSettings.OCCUPANCY_THRESHOLD_MIN,
                                    ExperimentalSettings.OCCUPANCY_THRESHOLD_MAX,
                                    ExperimentalSettings.OCCUPANCY_THRESHOLD_STEP,
                                ),
                                colors = occupancySliderColors(),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    OccupancyDividerLabel(stringResource(R.string.settings_occupancy_leave_group))
                    SettingsInsetWell {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            SettingSliderLabelRow(
                                title = stringResource(R.string.settings_occupancy_leave),
                                badgeText = "${leaveSeconds}s",
                            )
                            TickSlider(
                                value = leaveSlider,
                                onValueChange = { leaveSlider = it },
                                onValueChangeFinished = {
                                    val index = leaveSlider.toInt().coerceIn(0, leaveLadder.lastIndex)
                                    leaveSlider = index.toFloat()
                                    coroutineScope.launch {
                                        viewModel.saveOccupancyLeaveSeconds(leaveLadder[index])
                                    }
                                },
                                valueRange = 0f..leaveLadder.lastIndex.toFloat(),
                                steps = (leaveLadder.size - 2).coerceAtLeast(0),
                                colors = occupancySliderColors(),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            item(key = "occupancy_sources") {
                SettingsSectionLabel(stringResource(R.string.settings_occupancy_sources))
                SimpleCard {
                    OccupancySourceGrid(
                        sources = listOf(
                            OccupancySourceUi(
                                source = OccupancySource.Face,
                                label = stringResource(R.string.settings_occupancy_source_face),
                                iconRes = R.drawable.mdi_account,
                                available = faceAvailable,
                                selected = settings.occupancyUseFace,
                                hint = facePath,
                            ),
                            OccupancySourceUi(
                                source = OccupancySource.Motion,
                                label = stringResource(R.string.settings_occupancy_source_motion),
                                iconRes = R.drawable.mdi_dots_circle,
                                available = motionAvailable,
                                selected = settings.occupancyUseMotion,
                                hint = motionPath,
                            ),
                            OccupancySourceUi(
                                source = OccupancySource.Touch,
                                label = stringResource(R.string.settings_occupancy_source_touch),
                                iconRes = R.drawable.mdi_gesture_tap_button,
                                available = touchAvailable,
                                selected = settings.occupancyUseTouch,
                                hint = touchPath,
                            ),
                            OccupancySourceUi(
                                source = OccupancySource.Vibration,
                                label = stringResource(R.string.settings_occupancy_source_vibration),
                                iconRes = R.drawable.mdi_vibrate,
                                available = vibrationAvailable,
                                selected = settings.occupancyUseVibration,
                                hint = vibrationHint,
                            ),
                            OccupancySourceUi(
                                source = OccupancySource.Proximity,
                                label = stringResource(R.string.settings_occupancy_source_proximity),
                                iconRes = R.drawable.mdi_motion_sensor,
                                available = proximityAvailable,
                                selected = settings.occupancyUseProximity,
                                hint = proximityPath,
                            ),
                            OccupancySourceUi(
                                source = OccupancySource.Voiceprint,
                                label = stringResource(R.string.settings_occupancy_source_voiceprint),
                                iconRes = R.drawable.mdi_fingerprint,
                                available = voiceprintAvailable,
                                selected = settings.occupancyUseVoiceprint,
                                hint = voiceprintPath,
                            ),
                        ),
                        onToggle = { source, enabled ->
                            coroutineScope.launch {
                                when (source) {
                                    OccupancySource.Face -> viewModel.saveOccupancyUseFace(enabled)
                                    OccupancySource.Motion -> viewModel.saveOccupancyUseMotion(enabled)
                                    OccupancySource.Touch -> viewModel.saveOccupancyUseTouch(enabled)
                                    OccupancySource.Vibration -> viewModel.saveOccupancyUseVibration(enabled)
                                    OccupancySource.Proximity -> viewModel.saveOccupancyUseProximity(enabled)
                                    OccupancySource.Voiceprint -> viewModel.saveOccupancyUseVoiceprint(enabled)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Ring dial + live presence badge, mirroring the mockup's inference header. */
@Composable
private fun OccupancyGauge(percent: Int, occupied: Boolean) {
    val accent = getAccentColor()
    val track = getSliderInactiveColor()
    val stateColor = if (occupied) accent else getSettingsDescriptionColor()
    val sweep by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(durationMillis = 380),
        label = "occupancy-gauge",
    )
    val pulseAlpha by rememberInfiniteTransition(label = "occupancy-pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "occupancy-pulse-alpha",
    )
    // Breathe only while someone is detected; a quiet room gets a still, dim dot.
    val dotAlpha = if (occupied) pulseAlpha else 0.4f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = track,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$percent",
                    fontSize = settingsTitleTextSize(20f),
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                Text(
                    text = "%",
                    fontSize = settingsTitleTextSize(12f),
                    fontWeight = FontWeight.Medium,
                    color = accent,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(dotAlpha)
                        .background(stateColor, CircleShape),
                )
                Text(
                    text = stringResource(
                        if (occupied) {
                            R.string.settings_occupancy_state_present
                        } else {
                            R.string.settings_occupancy_state_absent
                        },
                    ),
                    fontSize = settingsTitleTextSize(14f),
                    fontWeight = FontWeight.SemiBold,
                    color = stateColor,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                text = stringResource(R.string.settings_occupancy_gauge_desc),
                fontSize = settingsBodyTextSize(12f),
                color = getSettingsDescriptionColor(),
                lineHeight = settingsBodyLineHeight(),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Hairline rule with a centered caption, used to split the two inference knobs. */
@Composable
private fun OccupancyDividerLabel(text: String) {
    val line = getSliderInactiveColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = 1.dp, color = line)
        Text(
            text = text,
            fontSize = settingsCaptionTextSize(base = 10f),
            fontWeight = FontWeight.SemiBold,
            color = getSettingsDescriptionColor(),
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = 1.dp, color = line)
    }
}

private data class OccupancySourceUi(
    val source: OccupancySource,
    val label: String,
    @DrawableRes val iconRes: Int,
    val available: Boolean,
    val selected: Boolean,
    val hint: String,
)

@Composable
private fun OccupancySourceGrid(
    sources: List<OccupancySourceUi>,
    onToggle: (OccupancySource, Boolean) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        sources.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { item ->
                    OccupancySourceTile(
                        label = item.label,
                        iconRes = item.iconRes,
                        available = item.available,
                        selected = item.available && item.selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (!item.available) {
                                AvaToast.show(context, item.hint, durationMs = AvaToast.LONG_MS)
                            } else {
                                onToggle(item.source, !item.selected)
                            }
                        },
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun OccupancySourceTile(
    label: String,
    @DrawableRes iconRes: Int,
    available: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = getAccentColor()
    val muted = getSettingsDescriptionColor()
    val title = getLabelColor()
    val well = getSettingsInsetWellColor()
    val inactive = getSliderInactiveColor()
    val border = when {
        !available -> inactive
        selected -> accent
        else -> inactive
    }
    val contentColor = when {
        !available -> muted
        selected -> accent
        else -> title
    }

    Column(
        modifier = modifier
            .alpha(if (available) 1f else 0.42f)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent)
            .border(width = 1.5.dp, color = border, shape = RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) accent.copy(alpha = 0.12f) else well),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = if (selected) accent else muted,
                    modifier = Modifier.size(18.dp),
                )
            }
            OccupancyCheckMark(selected = selected, accent = accent)
        }
        Text(
            text = label,
            fontSize = settingsTitleTextSize(14f),
            fontWeight = FontWeight.Medium,
            color = contentColor,
        )
    }
}

@Composable
private fun OccupancyCheckMark(
    selected: Boolean,
    accent: Color,
) {
    val ring = if (selected) accent else getSliderInactiveColor()
    Box(
        modifier = Modifier
            .size(20.dp)
            .border(width = 2.dp, color = ring, shape = CircleShape)
            .background(if (selected) accent else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.mdi_check),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun occupancySliderColors() = SliderDefaults.colors(
    thumbColor = getAccentColor(),
    activeTrackColor = getAccentColor(),
    inactiveTrackColor = getSliderInactiveColor(),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

private fun occupancySliderSteps(min: Int, max: Int, step: Int): Int {
    val count = (max - min) / step
    return (count - 1).coerceAtLeast(0)
}

/** Toast copy for a gray tile: which source is off, then the exact settings path to turn it on. */
@Composable
private fun occupancyEnableHint(source: String, vararg pathSegments: String): String {
    return stringResource(
        R.string.settings_occupancy_enable_hint,
        source,
        pathSegments.joinToString(" - "),
    )
}
