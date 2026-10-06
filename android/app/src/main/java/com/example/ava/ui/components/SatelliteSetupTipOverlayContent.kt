package com.example.ava.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.parseBoldText
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.AccentBrown
import com.example.ava.utils.TouchSoundHelper

/** Outer list / path group — soft “pillow” radius. */
private val AtmospherePathsRadius = 24.dp
/** Engine cards — one step tighter than the group. */
private val AtmosphereCardRadius = 20.dp
/** Glyph tiles — continuous-ish soft square. */
private val AtmosphereGlyphRadius = 16.dp

/**
 * Style 4 · Soft Atmosphere teaching tip.
 * Light: soft blue atmosphere. Dark: Ava theme (AccentBrown / #1F1F1F family).
 *
 * [SatelliteSetupTipKind.WakeConfig]: two steps (wake wizard + Mod Store engines).
 * [SatelliteSetupTipKind.HaServiceCalls]: one step (enable HA ESPHome actions).
 * [SatelliteSetupTipKind.ConversationNoIntent]: two steps (other agent / ha_claw + install).
 * [SatelliteSetupTipKind.PipelineConfig]: one step (HA voice pipeline STT/TTS/agent check).
 */
@Composable
fun SatelliteSetupTipOverlayContent(
    onGotIt: () -> Unit,
    tipKind: SatelliteSetupTipKind = SatelliteSetupTipKind.WakeConfig,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxStep = when (tipKind) {
        SatelliteSetupTipKind.HaServiceCalls,
        SatelliteSetupTipKind.PipelineConfig -> 1
        SatelliteSetupTipKind.WakeConfig,
        SatelliteSetupTipKind.ConversationNoIntent -> 2
    }
    var step by remember(tipKind) { mutableIntStateOf(1) }

    // Light keeps Atmosphere blue; dark matches Ava dialog theme colors.
    val titleColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF0B1F3A)
    val bodyColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF243652)
    val muteColor = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF6B7F99)
    val accent = if (isDarkMode) AccentBrown else AccentBlue
    val lineColor = if (isDarkMode) Color(0xFF3D3D3D) else Color(0xFF0B1F3A).copy(alpha = 0.10f)
    val glassBg = if (isDarkMode) {
        Color(0xFF2A2A2A).copy(alpha = 0.72f)
    } else {
        Color.White.copy(alpha = 0.55f)
    }
    val glyphBg = if (isDarkMode) {
        Color(0xFF2A2420)
    } else {
        AccentBlue.copy(alpha = 0.10f)
    }
    val glowColor = if (isDarkMode) {
        AccentBrown.copy(alpha = 0.18f)
    } else {
        AccentBlue.copy(alpha = 0.12f)
    }

    val bgBrush = if (isDarkMode) {
        Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color(0xFF26211D),
                0.45f to Color(0xFF1F1F1F),
                1.0f to Color(0xFF1A1A1A),
            ),
            start = Offset(0f, 0f),
            end = Offset(900f, 1600f),
        )
    } else {
        Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color(0xFFDBE7F8),
                0.38f to Color(0xFFEEF3FA),
                1.0f to Color(0xFFF7F9FC),
            ),
            start = Offset(0f, 0f),
            end = Offset(900f, 1600f),
        )
    }

    val horizontalPad = if (isLandscape) 28.dp else 22.dp
    val contentMaxWidth = if (isLandscape) 560.dp else 720.dp

    // Larger screens → larger type (shortest side vs ~360dp phone).
    val shortestDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
    val textScale = (shortestDp / 360f).coerceIn(0.95f, 1.42f)
    fun scaledSp(base: Float): TextUnit = (base * textScale).sp

    val titleSize = scaledSp(if (isLandscape) 20f else 22f)
    val bodySize = scaledSp(if (isLandscape) 14.5f else 15.5f)
    val pathSize = scaledSp(if (isLandscape) 13.5f else 14.5f)
    val finishSize = scaledSp(if (isLandscape) 13.5f else 14.5f)
    val actionSize = scaledSp(if (isLandscape) 15.5f else 16.5f)
    val engineNameSize = scaledSp(if (isLandscape) 15.5f else 16.5f)
    val engineMetaSize = scaledSp(if (isLandscape) 13.5f else 14.5f)
    val glyphLabelSize = scaledSp(12f)
    val glyphBoxSize = (46f * textScale).dp.coerceIn(46.dp, 64.dp)

    // Corner glow: expand with screen, hang further off the edge.
    val glowSize = (shortestDp * 0.85f).dp.coerceIn(280.dp, 480.dp)
    val glowOffsetX = glowSize * 0.28f
    val glowOffsetY = -(glowSize * 0.32f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush),
    ) {
        // Soft ambient orb — atmosphere, not a drag handle
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = glowOffsetX, y = glowOffsetY)
                .size(glowSize)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor, glowColor.copy(alpha = glowColor.alpha * 0.35f), Color.Transparent),
                    ),
                    shape = CircleShape,
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPad)
                    .padding(top = if (isLandscape) 18.dp else 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = contentMaxWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.satellite_setup_tip_title),
                        color = titleColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = titleSize,
                        lineHeight = titleSize * 1.25f,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    when {
                        tipKind == SatelliteSetupTipKind.PipelineConfig -> {
                            AtmosphereTwoPathStep(
                                descRes = R.string.satellite_setup_tip_desc_pipeline,
                                path1LabelRes = R.string.satellite_setup_tip_step1_label,
                                path1Res = R.string.satellite_setup_tip_pipeline_path1,
                                path2LabelRes = R.string.satellite_setup_tip_step2_label,
                                path2Res = R.string.satellite_setup_tip_pipeline_path2,
                                finishRes = R.string.satellite_setup_tip_finish_pipeline,
                                bodyColor = bodyColor,
                                muteColor = muteColor,
                                accent = accent,
                                glassBg = glassBg,
                                lineColor = lineColor,
                                bodySize = bodySize,
                                pathSize = pathSize,
                                finishSize = finishSize,
                                isLandscape = isLandscape,
                                extraPath3LabelRes = R.string.satellite_setup_tip_step3_label,
                                extraPath3Res = R.string.satellite_setup_tip_pipeline_path3,
                            )
                        }
                        tipKind == SatelliteSetupTipKind.ConversationNoIntent && step == 1 -> {
                            AtmosphereTwoPathStep(
                                descRes = R.string.satellite_setup_tip_desc_no_intent,
                                path1LabelRes = R.string.satellite_setup_tip_path1_label,
                                path1Res = R.string.satellite_setup_tip_no_intent_path1,
                                path2LabelRes = R.string.satellite_setup_tip_path2_label,
                                path2Res = R.string.satellite_setup_tip_no_intent_path2,
                                finishRes = R.string.satellite_setup_tip_finish_no_intent,
                                bodyColor = bodyColor,
                                muteColor = muteColor,
                                accent = accent,
                                glassBg = glassBg,
                                lineColor = lineColor,
                                bodySize = bodySize,
                                pathSize = pathSize,
                                finishSize = finishSize,
                                isLandscape = isLandscape,
                            )
                        }
                        tipKind == SatelliteSetupTipKind.ConversationNoIntent && step == 2 -> {
                            AtmosphereTwoPathStep(
                                descRes = R.string.satellite_setup_tip_desc_ha_claw,
                                path1LabelRes = R.string.satellite_setup_tip_step1_label,
                                path1Res = R.string.satellite_setup_tip_ha_claw_install1,
                                path2LabelRes = R.string.satellite_setup_tip_step2_label,
                                path2Res = R.string.satellite_setup_tip_ha_claw_install2,
                                finishRes = R.string.satellite_setup_tip_finish_ha_claw,
                                bodyColor = bodyColor,
                                muteColor = muteColor,
                                accent = accent,
                                glassBg = glassBg,
                                lineColor = lineColor,
                                bodySize = bodySize,
                                pathSize = pathSize,
                                finishSize = finishSize,
                                isLandscape = isLandscape,
                                extraPath3LabelRes = R.string.satellite_setup_tip_step3_label,
                                extraPath3Res = R.string.satellite_setup_tip_ha_claw_install3,
                            )
                        }
                        step == 1 -> {
                            val descRes = if (tipKind == SatelliteSetupTipKind.HaServiceCalls) {
                                R.string.satellite_setup_tip_desc_ha_service
                            } else {
                                R.string.satellite_setup_tip_desc_wake
                            }
                            // HA rows are sequential steps, wake rows are alternative paths.
                            val path1LabelRes = if (tipKind == SatelliteSetupTipKind.HaServiceCalls) {
                                R.string.satellite_setup_tip_step1_label
                            } else {
                                R.string.satellite_setup_tip_path1_label
                            }
                            val path2LabelRes = if (tipKind == SatelliteSetupTipKind.HaServiceCalls) {
                                R.string.satellite_setup_tip_step2_label
                            } else {
                                R.string.satellite_setup_tip_path2_label
                            }
                            val path1Res = if (tipKind == SatelliteSetupTipKind.HaServiceCalls) {
                                R.string.satellite_setup_tip_ha_service_path1
                            } else {
                                R.string.satellite_setup_tip_path1
                            }
                            val path2Res = if (tipKind == SatelliteSetupTipKind.HaServiceCalls) {
                                R.string.satellite_setup_tip_ha_service_path2
                            } else {
                                R.string.satellite_setup_tip_path2
                            }
                            val finishRes = if (tipKind == SatelliteSetupTipKind.HaServiceCalls) {
                                R.string.satellite_setup_tip_finish_ha_service
                            } else {
                                R.string.satellite_setup_tip_finish_wake
                            }
                            AtmosphereTwoPathStep(
                                descRes = descRes,
                                path1LabelRes = path1LabelRes,
                                path1Res = path1Res,
                                path2LabelRes = path2LabelRes,
                                path2Res = path2Res,
                                finishRes = finishRes,
                                bodyColor = bodyColor,
                                muteColor = muteColor,
                                accent = accent,
                                glassBg = glassBg,
                                lineColor = lineColor,
                                bodySize = bodySize,
                                pathSize = pathSize,
                                finishSize = finishSize,
                                isLandscape = isLandscape,
                            )
                        }
                        else -> {
                            Text(
                                text = parseBoldText(
                                    stringResource(R.string.satellite_setup_tip_desc_engines),
                                ),
                                color = bodyColor.copy(alpha = 0.92f),
                                fontSize = bodySize,
                                lineHeight = bodySize * 1.55f,
                                textAlign = TextAlign.Start,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = if (isLandscape) 14.dp else 16.dp),
                            ) {
                                AtmosphereEngineRow(
                                    glyph = stringResource(R.string.satellite_setup_tip_engine_tts_glyph),
                                    name = stringResource(R.string.satellite_setup_tip_engine_tts_name),
                                    meta = stringResource(R.string.satellite_setup_tip_engine_tts_meta),
                                    titleColor = titleColor,
                                    muteColor = muteColor,
                                    accent = accent,
                                    glyphBg = glyphBg,
                                    glassBg = glassBg,
                                    lineColor = lineColor,
                                    compact = isLandscape,
                                    nameSize = engineNameSize,
                                    metaSize = engineMetaSize,
                                    glyphBoxSize = glyphBoxSize,
                                    glyphLabelSize = glyphLabelSize,
                                )
                                AtmosphereEngineRow(
                                    glyph = stringResource(R.string.satellite_setup_tip_engine_stt_glyph),
                                    name = stringResource(R.string.satellite_setup_tip_engine_stt_name),
                                    meta = stringResource(R.string.satellite_setup_tip_engine_stt_meta),
                                    titleColor = titleColor,
                                    muteColor = muteColor,
                                    accent = accent,
                                    glyphBg = glyphBg,
                                    glassBg = glassBg,
                                    lineColor = lineColor,
                                    compact = isLandscape,
                                    nameSize = engineNameSize,
                                    metaSize = engineMetaSize,
                                    glyphBoxSize = glyphBoxSize,
                                    glyphLabelSize = glyphLabelSize,
                                )
                            }
                            Text(
                                text = parseBoldText(stringResource(R.string.satellite_setup_tip_finish_engines)),
                                color = muteColor,
                                fontSize = finishSize,
                                lineHeight = finishSize * 1.5f,
                                textAlign = TextAlign.Start,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = if (isLandscape) 14.dp else 16.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPad)
                    .padding(top = 4.dp, bottom = if (isLandscape) 8.dp else 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (maxStep > 1) {
                    TextButton(
                        onClick = {
                            TouchSoundHelper.playClick(context)
                            if (step < maxStep) step = step + 1
                        },
                        enabled = step < maxStep,
                    ) {
                        Text(
                            text = stringResource(R.string.satellite_setup_tip_next),
                            color = if (step < maxStep) muteColor else muteColor.copy(alpha = 0.4f),
                            fontSize = actionSize,
                        )
                    }
                }
                TextButton(
                    onClick = {
                        TouchSoundHelper.playClick(context)
                        onGotIt()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.satellite_setup_tip_got_it),
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = actionSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun AtmosphereTwoPathStep(
    descRes: Int,
    path1LabelRes: Int,
    path1Res: Int,
    path2LabelRes: Int,
    path2Res: Int,
    finishRes: Int,
    bodyColor: Color,
    muteColor: Color,
    accent: Color,
    glassBg: Color,
    lineColor: Color,
    bodySize: TextUnit,
    pathSize: TextUnit,
    finishSize: TextUnit,
    isLandscape: Boolean,
    extraPath3LabelRes: Int? = null,
    extraPath3Res: Int? = null,
) {
    Text(
        text = parseBoldText(stringResource(descRes)),
        color = bodyColor.copy(alpha = 0.92f),
        fontSize = bodySize,
        lineHeight = bodySize * 1.55f,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isLandscape) 14.dp else 16.dp)
            .clip(RoundedCornerShape(AtmospherePathsRadius))
            .background(glassBg)
            .border(1.dp, lineColor, RoundedCornerShape(AtmospherePathsRadius))
            .padding(vertical = 4.dp),
    ) {
        AtmospherePathRow(
            label = stringResource(path1LabelRes),
            body = stringResource(path1Res),
            accent = accent,
            bodyColor = bodyColor,
            fontSize = pathSize,
            compact = isLandscape,
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 14.dp),
            color = lineColor,
            thickness = 1.dp,
        )
        AtmospherePathRow(
            label = stringResource(path2LabelRes),
            body = stringResource(path2Res),
            accent = accent,
            bodyColor = bodyColor,
            fontSize = pathSize,
            compact = isLandscape,
        )
        if (extraPath3LabelRes != null && extraPath3Res != null) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                color = lineColor,
                thickness = 1.dp,
            )
            AtmospherePathRow(
                label = stringResource(extraPath3LabelRes),
                body = stringResource(extraPath3Res),
                accent = accent,
                bodyColor = bodyColor,
                fontSize = pathSize,
                compact = isLandscape,
            )
        }
    }
    Text(
        text = parseBoldText(stringResource(finishRes)),
        color = muteColor,
        fontSize = finishSize,
        lineHeight = finishSize * 1.5f,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isLandscape) 14.dp else 16.dp),
    )
}

@Composable
private fun AtmospherePathRow(
    label: String,
    body: String,
    accent: Color,
    bodyColor: Color,
    fontSize: TextUnit,
    compact: Boolean,
) {
    val bodyAnnotated = parseBoldText(body)
    val annotated = buildAnnotatedString {
        withStyle(
            SpanStyle(
                fontWeight = FontWeight.Bold,
                color = accent,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.3.sp,
            ),
        ) {
            append(label)
        }
        append("  ")
        append(bodyAnnotated)
    }
    Text(
        text = annotated,
        color = bodyColor,
        fontSize = fontSize,
        lineHeight = fontSize * 1.45f,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 16.dp,
                vertical = if (compact) 12.dp else 14.dp,
            ),
    )
}

@Composable
private fun AtmosphereEngineRow(
    glyph: String,
    name: String,
    meta: String,
    titleColor: Color,
    muteColor: Color,
    accent: Color,
    glyphBg: Color,
    glassBg: Color,
    lineColor: Color,
    compact: Boolean,
    nameSize: TextUnit,
    metaSize: TextUnit,
    glyphBoxSize: Dp,
    glyphLabelSize: TextUnit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AtmosphereCardRadius))
            .background(glassBg)
            .border(1.dp, lineColor, RoundedCornerShape(AtmosphereCardRadius))
            .padding(
                horizontal = 14.dp,
                vertical = if (compact) 12.dp else 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(glyphBoxSize)
                .clip(RoundedCornerShape(AtmosphereGlyphRadius))
                .background(glyphBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                fontSize = glyphLabelSize,
                letterSpacing = 0.5.sp,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = titleColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = nameSize,
            )
            Text(
                text = meta,
                color = muteColor,
                fontSize = metaSize,
                lineHeight = metaSize * 1.35f,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
