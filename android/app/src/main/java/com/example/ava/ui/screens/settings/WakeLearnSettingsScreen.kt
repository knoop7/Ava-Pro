package com.example.ava.ui.screens.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.WakeMode
import com.example.ava.settings.WakeWordEngine
import com.example.ava.ui.AvaToast
import com.example.ava.ui.safePopBackStack
import com.example.ava.ui.haptic.TickSlider
import com.example.ava.ui.screens.settings.components.LearningRing
import com.example.ava.ui.screens.settings.components.SettingItem
import com.example.ava.ui.screens.settings.components.SettingsCardInnerHorizontalPadding
import com.example.ava.ui.screens.settings.components.VoiceDetailScaffold
import com.example.ava.ui.screens.settings.components.VoiceStatsFocus
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsCaptionTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.wakelearn.OnDeviceVerifierTrainer
import com.example.ava.wakelearn.WakeLearnStore
import com.example.ava.wakelearn.WakeLearnTuning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class WakeLearnStatus(
    val engine: WakeWordEngine,
    val id: String,
    val positives: Int,
    val negatives: Int,
    /** False wakes the verifier head has actually stopped — the user-visible payoff. */
    val vetoes: Int,
    /** Personalized heads published so far; shown as the model version. */
    val versions: Int,
    /** Oldest labelled sample, or 0 — how long this wake word has been learning. */
    val firstSampleMs: Long,
    val hasLearnedHead: Boolean,
    val hasFactoryHead: Boolean,
    val report: WakeLearnTuning.TrainReport?,
)

/**
 * What the user sees instead of training thresholds. Level 1 is still collecting,
 * level 2 has a personalized head, level 3 is a head that both keeps genuine wakes
 * and blocks most held-out false ones.
 */
private enum class WakeLearnStage { LISTENING, PERSONALIZED, MASTERED }

private const val MASTERED_MIN_VETO = 0.90f

private fun WakeLearnStatus.stage(): WakeLearnStage {
    if (!hasLearnedHead) return WakeLearnStage.LISTENING
    val r = report ?: return WakeLearnStage.PERSONALIZED
    return if (r.published && r.cvNegativeVeto >= MASTERED_MIN_VETO &&
        r.cvPositivePass >= WakeLearnTuning.minPositiveRetention
    ) {
        WakeLearnStage.MASTERED
    } else {
        WakeLearnStage.PERSONALIZED
    }
}

/** Arc fill within the current stage — never surfaced as a number. */
private fun WakeLearnStatus.stageProgress(): Float = when (stage()) {
    WakeLearnStage.LISTENING -> (
        (positives.toFloat() / OnDeviceVerifierTrainer.MIN_POSITIVES).coerceAtMost(1f) +
            (negatives.toFloat() / OnDeviceVerifierTrainer.MIN_NEGATIVES).coerceAtMost(1f)
        ) / 2f
    WakeLearnStage.PERSONALIZED -> (report?.cvNegativeVeto ?: 0.5f) / MASTERED_MIN_VETO
    WakeLearnStage.MASTERED -> 1f
}.coerceIn(0f, 1f)

/**
 * Self-learning wake settings: the on/off switch, per-wake-word learning status, the
 * advanced knobs (defaults are the measured calibration) and the reset. Laid out with the
 * same rows, badges and cards as the other voice pages.
 */
@Composable
fun WakeLearnSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val microphoneState by viewModel.microphoneSettingsState.collectAsStateWithLifecycle(null)
    val engine = microphoneState?.wakeWordEngine ?: WakeWordEngine.MICRO_WAKE_WORD
    val wakeEngineDisabled = microphoneState?.wakeMode == WakeMode.BUTTON

    LaunchedEffect(wakeEngineDisabled) {
        if (wakeEngineDisabled) navController.safePopBackStack()
    }
    val activeIds = remember(microphoneState) {
        listOfNotNull(microphoneState?.wakeWord, microphoneState?.wakeWord2)
            .map { it.id }
            .distinct()
    }

    LaunchedEffect(Unit) { WakeLearnTuning.load(context) }
    var enabled by remember { mutableStateOf(WakeLearnTuning.enabled) }
    var vetoOffset by remember { mutableFloatStateOf(WakeLearnTuning.vetoOffset) }
    var retrainEvery by remember { mutableIntStateOf(WakeLearnTuning.retrainEvery) }
    var minRetention by remember { mutableFloatStateOf(WakeLearnTuning.minPositiveRetention) }
    var statuses by remember { mutableStateOf<List<WakeLearnStatus>>(emptyList()) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var busyId by remember { mutableStateOf<String?>(null) }
    val storeRevision by WakeLearnStore.revision.collectAsStateWithLifecycle()

    LaunchedEffect(engine, activeIds, refreshTick, storeRevision) {
        statuses = loadWakeLearnStatuses(context, engine, activeIds)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshTick++
    }

    val sliderColors = SliderDefaults.colors(
        thumbColor = getAccentColor(),
        activeTrackColor = getAccentColor(),
        inactiveTrackColor = getSliderInactiveColor(),
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
    )

    VoiceDetailScaffold(
        navController = navController,
        title = stringResource(R.string.wake_learn_title),
        focus = VoiceStatsFocus.Wake,
    ) {
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.wake_learn_enable),
                    subLabel = stringResource(R.string.wake_learn_enable_desc),
                ) {
                    ModernSwitch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            WakeLearnTuning.setEnabled(context, it)
                        },
                    )
                }
            }
        }

        item { WakeLearnSectionLabel(stringResource(R.string.wake_learn_section_status)) }
        item {
            SimpleCard {
                if (statuses.isEmpty()) {
                    Text(
                        text = stringResource(R.string.wake_learn_no_active),
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor(),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                val totalVetoes = statuses.sumOf { it.vetoes }
                val totalLearned = statuses.sumOf { it.positives }
                val firstSample = statuses.map { it.firstSampleMs }.filter { it > 0 }.minOrNull()
                val showHero = totalVetoes > 0 || totalLearned > 0
                if (showHero) {
                    WakeLearnHero(
                        blocked = totalVetoes,
                        learned = totalLearned,
                        days = firstSample?.let {
                            ((System.currentTimeMillis() - it) / 86_400_000L).toInt().coerceAtLeast(1)
                        } ?: 0,
                    )
                }
                statuses.forEachIndexed { index, st ->
                    if (index > 0 || showHero) {
                        Spacer(Modifier.height(6.dp))
                        SettingsDivider()
                        Spacer(Modifier.height(6.dp))
                    }
                    WakeLearnStatusBlock(
                        status = st,
                        busy = busyId == st.id,
                        onRetrain = {
                            busyId = st.id
                            scope.launch {
                                val ok = VoiceSatelliteService.getInstance()?.retrainWakeVerifier(st.engine, st.id)
                                AvaToast.show(
                                    context,
                                    context.getString(
                                        when (ok) {
                                            true -> R.string.wake_learn_retrain_published
                                            false -> R.string.wake_learn_retrain_refused
                                            null -> R.string.wake_learn_retrain_unavailable
                                        },
                                    ),
                                )
                                busyId = null
                                refreshTick++
                            }
                        },
                    )
                }
            }
        }

        item { WakeLearnSectionLabel(stringResource(R.string.wake_learn_section_advanced)) }
        item {
            SimpleCard {
                Column {
                    SettingItem(
                        name = stringResource(R.string.wake_learn_veto_offset),
                        description = stringResource(R.string.wake_learn_veto_offset_desc),
                        action = { WakeLearnCapsule(text = "%+.2f".format(vetoOffset)) },
                    )
                    TickSlider(
                        value = vetoOffset,
                        onValueChange = { vetoOffset = it },
                        onValueChangeFinished = {
                            val snapped = Math.round(vetoOffset * 100f) / 100f
                            vetoOffset = snapped
                            WakeLearnTuning.setVetoOffset(context, snapped)
                            VoiceSatelliteService.getInstance()?.reloadWakeVerifiers()
                        },
                        valueRange = WakeLearnTuning.MIN_VETO_OFFSET..WakeLearnTuning.MAX_VETO_OFFSET,
                        steps = 34,
                        colors = sliderColors,
                    )
                    SettingItem(
                        name = stringResource(R.string.wake_learn_retrain_every),
                        description = stringResource(R.string.wake_learn_retrain_every_desc),
                        action = { WakeLearnCapsule(text = "$retrainEvery") },
                    )
                    TickSlider(
                        value = retrainEvery.toFloat(),
                        onValueChange = { retrainEvery = Math.round(it) },
                        onValueChangeFinished = { WakeLearnTuning.setRetrainEvery(context, retrainEvery) },
                        valueRange = WakeLearnTuning.MIN_RETRAIN_EVERY.toFloat()..WakeLearnTuning.MAX_RETRAIN_EVERY.toFloat(),
                        steps = WakeLearnTuning.MAX_RETRAIN_EVERY - WakeLearnTuning.MIN_RETRAIN_EVERY - 1,
                        colors = sliderColors,
                    )
                    SettingItem(
                        name = stringResource(R.string.wake_learn_min_retention),
                        description = stringResource(R.string.wake_learn_min_retention_desc),
                        action = { WakeLearnCapsule(text = "${(minRetention * 100).toInt()}%") },
                    )
                    TickSlider(
                        value = minRetention,
                        onValueChange = { minRetention = it },
                        onValueChangeFinished = {
                            val snapped = Math.round(minRetention * 100f) / 100f
                            minRetention = snapped
                            WakeLearnTuning.setMinPositiveRetention(context, snapped)
                        },
                        valueRange = WakeLearnTuning.MIN_MIN_RETENTION..WakeLearnTuning.MAX_MIN_RETENTION,
                        steps = 17,
                        colors = sliderColors,
                    )
                }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.wake_learn_restore_defaults),
                    subLabel = stringResource(R.string.wake_learn_advanced_note),
                    onClick = {
                        WakeLearnTuning.resetToDefaults(context)
                        enabled = WakeLearnTuning.enabled
                        vetoOffset = WakeLearnTuning.vetoOffset
                        retrainEvery = WakeLearnTuning.retrainEvery
                        minRetention = WakeLearnTuning.minPositiveRetention
                        VoiceSatelliteService.getInstance()?.reloadWakeVerifiers()
                    },
                ) { SettingRowChevron() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private suspend fun loadWakeLearnStatuses(
    context: android.content.Context,
    engine: WakeWordEngine,
    activeIds: List<String>,
): List<WakeLearnStatus> = withContext(Dispatchers.IO) {
    val store = WakeLearnStore.forContext(context)
    val openProvider = com.example.ava.microwakeword.WakeWordProviderFactory.openWakeWordProvider(context)
    activeIds.map { id ->
        val samples = store.load(engine, id)
        WakeLearnStatus(
            engine = engine,
            id = id,
            positives = samples.count { it.positive },
            negatives = samples.count { !it.positive },
            vetoes = WakeLearnTuning.vetoCount(context, engine, id),
            versions = WakeLearnTuning.publishedCount(context, engine, id),
            firstSampleMs = samples.minOfOrNull { it.timestampMs } ?: 0L,
            hasLearnedHead = store.headFile(engine, id).isFile,
            hasFactoryHead = engine == WakeWordEngine.OPEN_WAKE_WORD &&
                openProvider.loadFactoryVerifier(id) != null,
            report = WakeLearnTuning.lastReport(context, engine, id),
        )
    }
}

/**
 * Numerals without Material's 24sp line box or the platform font padding — that box is
 * what made a 34sp figure sit low and a 16sp / 10sp pair look off-baseline.
 */
private fun numeralStyle(size: TextUnit, weight: FontWeight, tracking: TextUnit = TextUnit.Unspecified) = TextStyle(
    fontSize = size,
    lineHeight = size,
    fontWeight = weight,
    letterSpacing = tracking,
    fontFeatureSettings = "tnum",
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

private fun captionStyle(size: TextUnit, weight: FontWeight = FontWeight.Medium) = TextStyle(
    fontSize = size,
    lineHeight = size,
    fontWeight = weight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/**
 * Achievement strip above the per-word blocks: three equal columns, numbers on one
 * baseline, captions under them. Blocked false wakes lead in the accent color.
 */
@Composable
private fun WakeLearnHero(
    blocked: Int,
    learned: Int,
    days: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WakeLearnHeroChip(
            value = "$blocked",
            label = stringResource(R.string.wake_learn_hero_blocked),
            valueColor = if (blocked > 0) getAccentColor() else getLabelColor(),
        )
        WakeLearnHeroChip(
            value = "$learned",
            label = stringResource(R.string.wake_learn_hero_learned),
            valueColor = getLabelColor(),
        )
        WakeLearnHeroChip(
            value = if (days > 0) "$days" else "—",
            label = stringResource(R.string.wake_learn_hero_days),
            valueColor = getLabelColor(),
        )
    }
}

@Composable
private fun WakeLearnHeroChip(
    value: String,
    label: String,
    valueColor: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(value, color = valueColor, maxLines = 1,
            style = numeralStyle(settingsTitleTextSize(base = 20f), FontWeight.Bold))
        Text(label, color = getSettingsDescriptionColor(), maxLines = 1,
            style = captionStyle(settingsCaptionTextSize(base = 11f)),
            modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * Dashboard block: ring + wake word id, then a two-cell instrument strip that always
 * reports what happened — genuine wakes learned, false wakes blocked. Once a fit
 * exists the cells also carry its held-out retention / veto rates as hairline bars.
 * Training thresholds stay internal: the ring alone conveys "still learning".
 */
@Composable
private fun WakeLearnStatusBlock(
    status: WakeLearnStatus,
    busy: Boolean,
    onRetrain: () -> Unit,
) {
    val stage = status.stage()
    val report = status.report
    val genuineGreen = Color(0xFF30D158)
    val masteredGold = Color(0xFFE0A526)
    val tint = when (stage) {
        WakeLearnStage.MASTERED -> masteredGold
        WakeLearnStage.PERSONALIZED -> genuineGreen
        WakeLearnStage.LISTENING -> if (status.hasFactoryHead) getAccentColor() else Color(0xFF8E8E93)
    }
    val level = when (stage) {
        WakeLearnStage.LISTENING -> 1
        WakeLearnStage.PERSONALIZED -> 2
        WakeLearnStage.MASTERED -> 3
    }
    val hasSamples = status.positives > 0 || status.negatives > 0
    val stageText = when (stage) {
        WakeLearnStage.MASTERED -> stringResource(R.string.wake_learn_stage_mastered, status.versions)
        WakeLearnStage.PERSONALIZED -> stringResource(R.string.wake_learn_stage_personalized, status.versions.coerceAtLeast(1))
        WakeLearnStage.LISTENING -> when {
            hasSamples -> stringResource(R.string.wake_learn_stage_learning)
            status.hasFactoryHead -> stringResource(R.string.wake_learn_head_factory)
            else -> stringResource(R.string.wake_learn_head_none)
        }
    }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LearningRing(
                progress = status.stageProgress(),
                tint = tint,
                label = stringResource(R.string.wake_learn_level, level),
                size = 48.dp,
                stroke = 3.5.dp,
                live = stage != WakeLearnStage.LISTENING || busy,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = status.id,
                color = getLabelColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = numeralStyle(settingsTitleTextSize(), FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            WakeLearnStateTag(text = stageText, tint = tint)
            WakeLearnRetrainIcon(
                busy = busy,
                enabled = !busy,
                onClick = onRetrain,
            )
        }
        Spacer(Modifier.height(6.dp))
        WakeLearnMetricCluster(
            leftLabel = stringResource(R.string.wake_learn_metric_learned),
            leftReadout = "${status.positives}",
            leftRate = report?.let {
                stringResource(R.string.wake_learn_metric_recall_pct, (it.cvPositivePass * 100).toInt())
            },
            leftProgress = report?.cvPositivePass,
            leftBar = genuineGreen,
            rightLabel = stringResource(R.string.wake_learn_metric_blocked),
            rightReadout = "${status.vetoes}",
            rightRate = report?.let {
                stringResource(R.string.wake_learn_metric_veto_pct, (it.cvNegativeVeto * 100).toInt())
            },
            rightProgress = report?.cvNegativeVeto,
            rightBar = getAccentColor(),
        )
        if (report != null && !report.published) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                WakeLearnStateTag(
                    text = stringResource(R.string.wake_learn_stat_refused),
                    tint = getSettingsDescriptionColor(),
                )
            }
        }
    }
}

@Composable
private fun WakeLearnMetricCluster(
    leftLabel: String,
    leftReadout: String,
    leftRate: String?,
    leftProgress: Float?,
    leftBar: Color,
    rightLabel: String,
    rightReadout: String,
    rightRate: String?,
    rightProgress: Float?,
    rightBar: Color,
) {
    val split = if (isDarkModeEnabled()) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(getSettingsInsetWellColor()),
    ) {
        WakeLearnMetricCell(
            label = leftLabel,
            readout = leftReadout,
            rate = leftRate,
            progress = leftProgress,
            bar = leftBar,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(split),
        )
        WakeLearnMetricCell(
            label = rightLabel,
            readout = rightReadout,
            rate = rightRate,
            progress = rightProgress,
            bar = rightBar,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WakeLearnMetricCell(
    label: String,
    readout: String,
    rate: String?,
    progress: Float?,
    bar: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, color = getSettingsDescriptionColor(), maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = captionStyle(settingsCaptionTextSize(base = 10f)))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(readout, color = getLabelColor(), maxLines = 1,
                style = numeralStyle(settingsTitleTextSize(base = 16f), FontWeight.SemiBold),
                modifier = Modifier.weight(1f))
            if (rate != null) {
                Text(rate, color = bar, maxLines = 1,
                    style = numeralStyle(settingsCaptionTextSize(base = 10.5f), FontWeight.SemiBold))
            }
        }
        if (progress != null) {
            WakeLearnHairline(progress = progress, color = bar,
                modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun WakeLearnHairline(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 700),
        label = "wake-hairline",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(getSliderInactiveColor()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(99.dp))
                .background(color),
        )
    }
}

@Composable
private fun WakeLearnCapsule(
    text: String,
    tint: Color = getAccentColor(),
    fill: Color = getSliderInactiveColor(),
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = settingsCaptionTextSize(base = 12f),
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun WakeLearnStateTag(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = if (isDarkModeEnabled()) 0.18f else 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = settingsCaptionTextSize(base = 10.5f),
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun WakeLearnRetrainIcon(
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val spin = rememberInfiniteTransition(label = "wake-retrain")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "wake-retrain-angle",
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = stringResource(R.string.wake_learn_retrain_short),
            tint = if (enabled) getAccentColor() else getSettingsDescriptionColor().copy(alpha = 0.45f),
            modifier = Modifier
                .size(18.dp)
                .then(if (busy) Modifier.rotate(angle) else Modifier),
        )
    }
}

@Composable
private fun WakeLearnSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = settingsBodyTextSize(base = 14f),
        fontWeight = FontWeight(650),
        color = getSettingsDescriptionColor(),
        modifier = Modifier.padding(start = SettingsCardInnerHorizontalPadding, top = 16.dp, bottom = 4.dp),
    )
}
