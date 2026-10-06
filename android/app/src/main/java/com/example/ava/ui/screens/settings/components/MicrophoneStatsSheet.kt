package com.example.ava.ui.screens.settings.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaRecorder
import com.example.ava.ui.AvaToast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.audio.DeviceAudioProfile
import com.example.ava.audio.PlaybackEnergyMonitor
import com.example.ava.audio.PlaybackReferenceBus
import com.example.ava.audio.SoftAecProbe
import com.example.ava.audio.SoftAecProbeSnapshot
import com.example.ava.detection.AudioEventProbeSnapshot
import com.example.ava.esphome.Connected
import com.example.ava.esphome.Disconnected
import com.example.ava.esphome.ServerError
import com.example.ava.esphome.Stopped
import com.example.ava.esphome.voicesatellite.Listening
import com.example.ava.esphome.voicesatellite.MicrophoneCaptureSnapshot
import com.example.ava.esphome.voicesatellite.Processing
import com.example.ava.esphome.voicesatellite.Responding
import com.example.ava.microwakeword.WakeWordLiveProbe
import com.example.ava.openwakeword.WakeEngineBudgetProbe
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.MicrophoneSettings
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.RecordingPath
import com.example.ava.settings.WakeWordEngine
import com.example.ava.settings.microphoneSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.ui.VoiceAccentColors
import com.example.ava.ui.rememberCompactSquareScreen
import com.example.ava.ui.screens.settings.LocalSettingsSplitActive
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.rememberSettingsHandleLandscape
import com.example.ava.voiceprint.VoicePrintProbeSnapshot
import kotlin.math.log10
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/** Card surface color for collapsible value fade (matches MicStats row card). */
private val LocalMicStatsFadeColor = staticCompositionLocalOf { Color(0xFF1F1F1F) }

private val ColorGood = Color(0xFF4CAF50)
private val ColorWarning = Color(0xFFFFC107)
private val ColorBad = Color(0xFFF44336)
/** LIVE badge — green (matches preview `--good`), not red. */
private val ColorLive = ColorGood

private const val HISTORY_LEN = 160
private const val POLL_MS = 50L
/** After sheet hits Expanded, wait for spring settle before composing heavy rows. */
private const val SHEET_SETTLE_MS = 120L
/** Prefetch poll ticks while sheet animates (data only — UI stays light). */
private const val PREFETCH_TICKS = 2
/** Frames of full-scale crest after wake — matches preview highway pulse. */
private const val WAKE_PULSE_FRAMES = 14
/**
 * Float RMS from the capture path is typically 0.02–0.15 for speech.
 * Expand for the scope so green→yellow movement is visible; Instant RMS stays raw.
 */
private const val SCOPE_LEVEL_GAIN = 5f

/**
 * Voice Stats diagnostic console. Composition = switch ON:
 * poll / level history start only while this sheet is shown; dismiss tears it down.
 * [focus] selects which primary sections compose and which settings Flows are collected.
 * Labels stay English (same pattern as Sendspin Stats) — no i18n on this branch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicrophoneStatsSheet(
    onDismiss: () -> Unit,
    isDarkMode: Boolean,
    focus: VoiceStatsFocus = VoiceStatsFocus.Microphone,
) {
    val isLandscape = rememberSettingsHandleLandscape()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val splitActive = LocalSettingsSplitActive.current
    val context = LocalContext.current

    val needMic = focus == VoiceStatsFocus.VoiceConfig ||
        focus == VoiceStatsFocus.Microphone ||
        focus == VoiceStatsFocus.Echo ||
        focus == VoiceStatsFocus.Wake ||
        focus == VoiceStatsFocus.WakeLibrary ||
        focus == VoiceStatsFocus.VoicePrint
    val needPlayer = focus == VoiceStatsFocus.VoiceConfig ||
        focus == VoiceStatsFocus.StreamingTts ||
        focus == VoiceStatsFocus.FeedbackAccent

    // Only subscribe to stores the active focus needs (null Flow when unused).
    val micFlow: Flow<MicrophoneSettings?> = remember(needMic) {
        if (needMic) MicrophoneSettingsStore(context.microphoneSettingsStore).getFlow()
        else flowOf(null)
    }
    val micSettings by micFlow.collectAsStateWithLifecycle(initialValue = null)

    val playerFlow: Flow<PlayerSettings?> = remember(needPlayer) {
        if (needPlayer) PlayerSettingsStore(context.playerSettingsStore).getFlow()
        else flowOf(null)
    }
    val playerSettings by playerFlow.collectAsStateWithLifecycle(initialValue = null)

    var ready by remember { mutableStateOf(false) }
    var instantRms by remember { mutableFloatStateOf(0f) }
    var sessionPeak by remember { mutableFloatStateOf(0f) }
    var ambient by remember { mutableFloatStateOf(0f) }
    var streaming by remember { mutableStateOf(false) }
    var wakeSuspended by remember { mutableStateOf(false) }
    var serviceRunning by remember { mutableStateOf(false) }
    var stateLabel by remember { mutableStateOf("—") }
    var farEndSignal by remember { mutableStateOf(false) }
    var pcmTtsActive by remember { mutableStateOf(false) }
    var urlTtsPlaying by remember { mutableStateOf(false) }
    var whisperPlaybackActive by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var captureSnap by remember { mutableStateOf(MicrophoneCaptureSnapshot()) }
    var aecProbe by remember { mutableStateOf(SoftAecProbe.snapshot()) }
    var voicePrintProbe by remember { mutableStateOf(VoicePrintProbeSnapshot()) }
    var voicePrintRingSnap by remember { mutableStateOf("—") }
    var wakeLiveProbe by remember { mutableStateOf<List<WakeWordLiveProbe>>(emptyList()) }
    var wakeBudgetProbe by remember { mutableStateOf<WakeEngineBudgetProbe?>(null) }
    var runtimeWakeWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var availableWakeCount by remember { mutableStateOf(0) }
    var lastWakeId by remember { mutableStateOf<String?>(null) }
    var lastWakePhrase by remember { mutableStateOf<String?>(null) }
    var lastWakeConfidence by remember { mutableStateOf<Float?>(null) }
    var audioEventProbe by remember { mutableStateOf(AudioEventProbeSnapshot()) }
    var lastAudioEventLabel by remember { mutableStateOf<String?>(null) }
    var lastAudioEventAgeMs by remember { mutableStateOf<Long?>(null) }
    var playbackEnergy by remember { mutableFloatStateOf(0f) }
    var pcmTtsVolume by remember { mutableStateOf<Float?>(null) }
    var lastTtsHost by remember { mutableStateOf<String?>(null) }
    var pcmTtsQueueDepth by remember { mutableIntStateOf(0) }
    var pcmTtsBytesSubmitted by remember { mutableLongStateOf(0L) }
    var pendingPcmChunks by remember { mutableIntStateOf(0) }
    var sessionAccentHex by remember { mutableStateOf<String?>(null) }
    var sessionAccentWakeIndex by remember { mutableIntStateOf(0) }
    var sessionAccentAgeMs by remember { mutableStateOf<Long?>(null) }
    /** Remaining full-scale scope samples after a wake edge (UI-only crest). */
    var wakePulseLeft by remember { mutableIntStateOf(0) }
    var seenWakeAtMs by remember { mutableLongStateOf(0L) }

    val history = remember {
        mutableStateListOf<Float>().apply {
            repeat(HISTORY_LEN) { add(0.04f) }
        }
    }
    /** Soft-AEC out RMS mapped like [history]; drawn as a ghost overlay on LevelScope only. */
    val aecHistory = remember {
        mutableStateListOf<Float>().apply {
            repeat(HISTORY_LEN) { add(0.04f) }
        }
    }

    // Soft-AEC / bus probes only while this sheet is open (no background cost).
    DisposableEffect(Unit) {
        SoftAecProbe.enabled = true
        SoftAecProbe.reset()
        onDispose {
            SoftAecProbe.enabled = false
        }
    }

    fun dismissSheet() {
        // Drop heavy rows before teardown so close animation stays smooth.
        ready = false
        SoftAecProbe.enabled = false
        onDismiss()
    }

    // Open path: keep UI light until ModalBottomSheet spring settles, but prefetch samples.
    LaunchedEffect(Unit) {
        ready = false
        SoftAecProbe.enabled = true
        // Prefetch while the sheet is still animating (state updates only; body not composed).
        repeat(PREFETCH_TICKS) {
            val service = VoiceSatelliteService.getInstance()
            captureSnap = service?.microphoneCaptureSnapshot() ?: MicrophoneCaptureSnapshot()
            SoftAecProbe.noteEngineActive(captureSnap.softwareAecActive)
            aecProbe = SoftAecProbe.snapshot()
            instantRms = service?.currentMicrophoneLevel() ?: 0f
            ambient = service?.ambientMicrophoneLevel() ?: 0f
            serviceRunning = service?.isVoiceSatelliteRunning() == true
            stateLabel = formatEspHomeState(service?.getState())
            delay(POLL_MS)
        }
        if (!splitActive) {
            snapshotFlow { sheetState.currentValue }
                .first { it == SheetValue.Expanded }
        }
        delay(SHEET_SETTLE_MS)
        seenWakeAtMs = VoiceSatelliteService.getInstance()?.lastWakeAtMs() ?: 0L
        ready = true
    }

    // LIVE poll only after reveal — avoids mid-animation recomposition jank.
    LaunchedEffect(ready, focus) {
        if (!ready) return@LaunchedEffect
        history.clear()
        aecHistory.clear()
        repeat(HISTORY_LEN) {
            history.add(0.04f)
            aecHistory.add(0.04f)
        }
        sessionPeak = 0f
        wakePulseLeft = 0
        seenWakeAtMs = VoiceSatelliteService.getInstance()?.lastWakeAtMs() ?: 0L
        while (true) {
            val service = VoiceSatelliteService.getInstance()
            serviceRunning = service?.isVoiceSatelliteRunning() == true
            val rms = service?.currentMicrophoneLevel() ?: 0f
            val amb = service?.ambientMicrophoneLevel() ?: 0f
            instantRms = rms
            ambient = amb
            // Sheet-local peak of Instant RMS for this open session (not uplink speech peak).
            sessionPeak = max(sessionPeak, rms)
            streaming = service?.isMicrophoneStreaming() == true
            wakeSuspended = service?.isWakeDetectionSuspended() == true
            stateLabel = formatEspHomeState(service?.getState())

            val needCaptureProbe = focus == VoiceStatsFocus.Microphone ||
                focus == VoiceStatsFocus.Echo ||
                focus == VoiceStatsFocus.VoiceConfig
            val needAecProbe = focus == VoiceStatsFocus.Microphone ||
                focus == VoiceStatsFocus.Echo ||
                focus == VoiceStatsFocus.StreamingTts ||
                focus == VoiceStatsFocus.VoiceConfig
            val needTtsProbe = focus == VoiceStatsFocus.StreamingTts ||
                focus == VoiceStatsFocus.Echo ||
                focus == VoiceStatsFocus.VoiceConfig
            val needFarEnd = needTtsProbe
            val needWakeProbe = focus == VoiceStatsFocus.Wake ||
                focus == VoiceStatsFocus.WakeLibrary ||
                focus == VoiceStatsFocus.VoiceConfig
            val needVpProbe = focus == VoiceStatsFocus.VoicePrint ||
                focus == VoiceStatsFocus.VoiceConfig
            val needAeProbe = focus == VoiceStatsFocus.AudioEvent ||
                focus == VoiceStatsFocus.VoiceConfig
            val needMuteProbe = needCaptureProbe || focus == VoiceStatsFocus.VoiceConfig

            if (needFarEnd) {
                farEndSignal = PlaybackReferenceBus.hasRecentPlaybackSignal()
            }
            if (needTtsProbe) {
                pcmTtsActive = service?.isPcmTtsActive() == true
                urlTtsPlaying = service?.isUrlTtsPlaying() == true
                whisperPlaybackActive = service?.isWhisperPlaybackActive() == true
            }
            if (needMuteProbe) {
                muted = service?.isMuted() == true
            }
            if (needCaptureProbe || needAecProbe) {
                captureSnap = service?.microphoneCaptureSnapshot() ?: MicrophoneCaptureSnapshot()
            }
            if (needAecProbe) {
                SoftAecProbe.noteEngineActive(captureSnap.softwareAecActive)
                aecProbe = SoftAecProbe.snapshot()
            }
            val wakeAt = service?.lastWakeAtMs() ?: 0L
            if (wakeAt > seenWakeAtMs) {
                seenWakeAtMs = wakeAt
                wakePulseLeft = WAKE_PULSE_FRAMES
            }
            lastWakeId = service?.lastWakeWordId()
            lastWakePhrase = service?.lastWakePhrase()
            lastWakeConfidence = service?.lastWakeConfidence()
            if (needWakeProbe) {
                runtimeWakeWords = service?.runtimeActiveWakeWords().orEmpty()
                availableWakeCount = service?.availableWakeWordCount() ?: 0
                wakeLiveProbe = service?.wakeLiveProbe().orEmpty()
                wakeBudgetProbe = service?.wakeBudgetProbe()
            }
            if (needVpProbe) {
                voicePrintProbe = service?.voicePrintProbe() ?: VoicePrintProbeSnapshot()
                voicePrintRingSnap = service?.voicePrintRingDebugSnapshot() ?: "—"
            }
            if (needAeProbe) {
                audioEventProbe = service?.audioEventProbe() ?: AudioEventProbeSnapshot()
                lastAudioEventLabel = service?.lastAudioEventLabel()
                lastAudioEventAgeMs = service?.lastAudioEventAgeMs()
            }
            if (needTtsProbe) {
                playbackEnergy = service?.playbackEnergyLevel() ?: 0f
                pcmTtsVolume = service?.pcmTtsVolume()
                lastTtsHost = service?.lastTtsUrlHost()
                if (focus == VoiceStatsFocus.StreamingTts) {
                    pcmTtsQueueDepth = service?.pcmTtsQueueDepth() ?: 0
                    pcmTtsBytesSubmitted = service?.pcmTtsBytesSubmitted() ?: 0L
                    pendingPcmChunks = service?.pendingPcmTtsChunkCount() ?: 0
                }
            }
            if (focus == VoiceStatsFocus.FeedbackAccent || focus == VoiceStatsFocus.VoiceConfig) {
                sessionAccentHex = service?.sessionAccentColorHex()
                sessionAccentWakeIndex = service?.sessionAccentWakeIndex() ?: 0
                sessionAccentAgeMs = service?.sessionAccentAgeMs()
            }

            // Mic scope keeps Instant RMS (original animation). AEC out is a separate ghost layer.
            val scopeSample = if (wakePulseLeft > 0) {
                wakePulseLeft--
                0.98f
            } else {
                scopeDisplayLevel(rms)
            }
            history.removeAt(0)
            history.add(scopeSample)

            val aecSample = if (needAecProbe && softAecHasFrames(aecProbe)) {
                scopeDisplayLevel(aecProbe.outRms)
            } else {
                0.04f
            }
            aecHistory.removeAt(0)
            aecHistory.add(aecSample)

            delay(POLL_MS)
        }
    }

    val cardBackground = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val borderColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFE2E8F0)
    val titleColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF1E293B)
    val labelColor = getSettingsDescriptionColor()
    val valueColor = if (isDarkMode) Color(0xFFE2E8F0) else Color(0xFF334155)
    val accentColor = getAccentColor()
    val dividerColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFE2E8F0)
    val scopeBg = Color.Black

    val showHubOverview = focus == VoiceStatsFocus.VoiceConfig
    val showLevelFull = focus == VoiceStatsFocus.Microphone
    val showCaptureFull = focus == VoiceStatsFocus.Microphone
    val showPipelineFull = focus == VoiceStatsFocus.Echo
    val showPipelineSummary = focus == VoiceStatsFocus.Microphone
    val showFarEndBlock = focus == VoiceStatsFocus.Echo || focus == VoiceStatsFocus.StreamingTts
    val showWakePrimary = focus == VoiceStatsFocus.Wake || focus == VoiceStatsFocus.WakeLibrary
    val showVoicePrintPrimary = focus == VoiceStatsFocus.VoicePrint
    val showAudioEventPrimary = focus == VoiceStatsFocus.AudioEvent
    val showStreamingTtsPrimary = focus == VoiceStatsFocus.StreamingTts
    val showFeedbackAccent = focus == VoiceStatsFocus.FeedbackAccent

    val headroomDb = headroomDbfs(sessionPeak)
    val clipRisk = clippingRisk(sessionPeak)
    val softAecEngineText = softAecEngineLabel(
        settingEnabled = micSettings?.softwareAecEnabled,
        constructed = captureSnap.softwareAecConstructed,
        active = captureSnap.softwareAecActive,
        pausedForSpeech = captureSnap.softwareAecPausedForSpeech,
        isAec3 = aecProbe.engineIsAec3,
    )
    val softNsEngineText = softNsEngineLabel(
        settingEnabled = micSettings?.softwareNsEnabled,
        constructed = captureSnap.softwareNsConstructed,
        active = captureSnap.softwareNsActive,
    )
    val aecHasFrames = softAecHasFrames(aecProbe)
    val ttsModeLabel = if (playerSettings?.enableStreamingTtsSubtitles == true) {
        "Streaming + captions"
    } else {
        "Standard"
    }
    val engineLabel = when (micSettings?.wakeWordEngine) {
        WakeWordEngine.OPEN_WAKE_WORD -> "openWakeWord"
        WakeWordEngine.MICRO_WAKE_WORD -> "microWakeWord"
        null -> "—"
    }
    val audioSourceLabel = if (micSettings?.audioSourceExplicitlySet != true) {
        "AUTO → ${audioSourceName(micSettings?.audioSource ?: MediaRecorder.AudioSource.MIC)}"
    } else {
        audioSourceName(micSettings?.audioSource ?: MediaRecorder.AudioSource.MIC)
    }
    val pathLabel = when (micSettings?.recordingPath) {
        RecordingPath.BUILTIN -> "BUILTIN"
        RecordingPath.USB -> "USB"
        RecordingPath.AUTO, null -> "AUTO"
    }
    val profile = DeviceAudioProfile.resolveById(micSettings?.audioProfileId)
        ?: DeviceAudioProfile.DEFAULT
    val profileLabel =
        "${profile.outputSampleRateInHz / 1000} kHz " +
            if (profile.outputChannelCount == 1) "Mono" else "${profile.outputChannelCount}ch"
    val micGain = "${micSettings?.micGainDb ?: 0} dB"
    val wakeDetect = when {
        !serviceRunning -> "Idle"
        wakeSuspended -> "Suspended"
        else -> "Active"
    }
    val ttsPlayingLabel = when {
        pcmTtsActive && urlTtsPlaying -> "PCM+URL"
        pcmTtsActive -> "PCM"
        urlTtsPlaying -> "URL"
        whisperPlaybackActive -> "Whisper"
        farEndSignal -> "Signal"
        stateLabel == "Responding" -> "Responding"
        else -> "Idle"
    }
    val activeSourceLabel = captureSnap.activeAudioSource?.let { audioSourceName(it) } ?: "—"
    val preferredDeviceLabel =
        if (captureSnap.preferredDeviceId >= 0) "${captureSnap.preferredDeviceId}" else "—"
    val micErrorLabel = captureSnap.lastError?.take(48) ?: "—"
    val hwAttachedLabel = listOf(
        attachedFlag(captureSnap.hwNsAttached),
        attachedFlag(captureSnap.hwAgcAttached),
        attachedFlag(captureSnap.hwAecAttached),
    ).joinToString(" / ")
    val runtimeWakeLabel = runtimeWakeWords.filter { it.isNotBlank() }.joinToString(" / ")
        .ifBlank { "—" }
    val showWakeBudget = micSettings?.wakeWordEngine == WakeWordEngine.OPEN_WAKE_WORD
    val wakeBudgetLabel = if (showWakeBudget) {
        wakeBudgetProbe?.formatDisplay() ?: "—"
    } else {
        null
    }
    val wakeBudgetColor = wakeBudgetValueColor(wakeBudgetProbe, valueColor)
    val lastWakeLabel = lastWakeId ?: "—"
    val wakeAgeLabel = run {
        val at = seenWakeAtMs
        if (at <= 0L || lastWakeId == null) "—"
        else formatAgeMs((System.currentTimeMillis() - at).coerceAtLeast(0L))
    }
    val lastAudioEventDisplay = when {
        lastAudioEventLabel == null -> "—"
        lastAudioEventAgeMs == null -> lastAudioEventLabel!!
        else -> "$lastAudioEventLabel · ${formatAgeMs(lastAudioEventAgeMs!!)}"
    }
    val ww1AccentLabel = accentHexToken(
        playerSettings?.voiceWakeWord1AccentColor.orEmpty(),
        VoiceAccentColors.DEFAULT_WAKE_WORD_1_HEX,
    )
    val ww2AccentLabel = accentHexToken(
        playerSettings?.voiceWakeWord2AccentColor.orEmpty(),
        VoiceAccentColors.DEFAULT_WAKE_WORD_2_HEX,
    )
    val sens1Label = sensitivityLabel(
        micSettings?.wakeWordSensitivity1,
        micSettings?.wakeWordExtraStrictness1 ?: 0,
    )
    val sens2Label = sensitivityLabel(
        micSettings?.wakeWordSensitivity2,
        micSettings?.wakeWordExtraStrictness2 ?: 0,
    )
    val wakeWordsSnapshot = buildString {
        val w1 = micSettings?.wakeWord?.ifBlank { null } ?: "—"
        val words = micSettings?.wakeWords.orEmpty().filter { it.isNotBlank() }
        append(w1)
        if (words.size > 1) {
            append(" / ")
            append(words.drop(1).joinToString(" / "))
        }
    }

    // Absorb leftover fling so the expanded sheet handle doesn't bounce.
    val sheetScrollFlingGuard = rememberModalSheetScrollFlingGuard()

    SettingsHandleSheet(
        onDismiss = { dismissSheet() },
        containerColor = if (isDarkMode) Color(0xFF161616) else Color(0xFFF8FAFC),
        sheetState = sheetState,
    ) {
        val fillSheet = splitActive || rememberCompactSquareScreen()
        ModalSheetDragHandle(isDarkMode = isDarkMode, onClick = { dismissSheet() })

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillSheet) Modifier.weight(1f) else Modifier)
                .nestedScroll(sheetScrollFlingGuard)
                .padding(horizontal = if (isLandscape) 24.dp else 16.dp)
                .padding(bottom = if (isLandscape) 14.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 10.dp else 14.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (fillSheet) Modifier.fillMaxHeight() else Modifier),
                shape = RoundedCornerShape(24.dp),
                color = cardBackground,
                shadowElevation = 0.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
            ) {
                CompositionLocalProvider(LocalMicStatsFadeColor provides cardBackground) {
                SettingsEdgeFadeScrollColumn(
                    modifier = Modifier
                        .then(if (fillSheet) Modifier.fillMaxSize() else Modifier)
                        .padding(
                            horizontal = if (isLandscape) 24.dp else 16.dp,
                            vertical = if (isLandscape) 18.dp else 14.dp,
                        ),
                    fadeHeight = 18.dp,
                    verticalArrangement = Arrangement.spacedBy(if (isLandscape) 6.dp else 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Voice Stats",
                                fontSize = if (isLandscape) 16.sp else 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = titleColor,
                            )
                            Text(
                                text = "Focus: ${focus.displayLabel()}",
                                fontSize = 11.sp,
                                color = labelColor,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ready && serviceRunning) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 5.dp)
                                        .size(6.dp)
                                        .background(ColorLive, shape = RoundedCornerShape(50)),
                                )
                                Text(
                                    text = "LIVE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ColorLive,
                                    letterSpacing = 0.6.sp,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    val text = formatVoiceStatsForCopy(
                                        focus = focus,
                                        rms = instantRms,
                                        peak = sessionPeak,
                                        ambient = ambient,
                                        headroomDb = headroomDb,
                                        clipRisk = clipRisk,
                                        engine = engineLabel,
                                        activeSource = activeSourceLabel,
                                        muted = muted,
                                        hwAttached = hwAttachedLabel,
                                        aecProbe = aecProbe,
                                        wakeDetect = wakeDetect,
                                        runtimeWakeWords = runtimeWakeLabel,
                                        availableWakeCount = availableWakeCount,
                                        sens1 = sens1Label,
                                        sens2 = sens2Label,
                                        lastWake = lastWakeLabel,
                                        lastWakeConf = lastWakeConfidence,
                                        wakeLive = wakeLiveProbe,
                                        wakeBudget = wakeBudgetProbe,
                                        voicePrint = voicePrintProbe,
                                        voicePrintRingSnap = voicePrintRingSnap,
                                        voicePrintSamples = "${micSettings?.voicePrintManualUser0Samples ?: 0} / ${micSettings?.voicePrintManualUser1Samples ?: 0}",
                                        audioEvent = audioEventProbe,
                                        lastAudioEvent = lastAudioEventDisplay,
                                        pcmTtsActive = pcmTtsActive,
                                        urlTtsPlaying = urlTtsPlaying,
                                        whisperActive = whisperPlaybackActive,
                                        pcmQueue = pcmTtsQueueDepth,
                                        pendingChunks = pendingPcmChunks,
                                        pcmBytes = pcmTtsBytesSubmitted,
                                        pcmVolume = pcmTtsVolume,
                                        playbackEnergy = playbackEnergy,
                                        lastTtsHost = lastTtsHost,
                                        ww1Accent = ww1AccentLabel,
                                        ww2Accent = ww2AccentLabel,
                                        sessionAccent = sessionAccentHex,
                                        sessionAccentWakeIndex = sessionAccentWakeIndex,
                                        sessionAccentAgeMs = sessionAccentAgeMs,
                                        state = stateLabel,
                                        streaming = streaming,
                                        service = if (serviceRunning) "Running" else "Stopped",
                                        showHubOverview = showHubOverview,
                                        showLevelFull = showLevelFull,
                                        showCaptureFull = showCaptureFull,
                                        showPipelineFull = showPipelineFull,
                                        showPipelineSummary = showPipelineSummary,
                                        showFarEndBlock = showFarEndBlock,
                                        showWakePrimary = showWakePrimary,
                                        showVoicePrintPrimary = showVoicePrintPrimary,
                                        showAudioEventPrimary = showAudioEventPrimary,
                                        showStreamingTtsPrimary = showStreamingTtsPrimary,
                                        showFeedbackAccent = showFeedbackAccent,
                                        wakeFocus = focus == VoiceStatsFocus.Wake,
                                        wakeLibraryFocus = focus == VoiceStatsFocus.WakeLibrary,
                                        captureSnap = captureSnap,
                                    )
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Voice Stats", text))
                                    AvaToast.show(context, "Stats copied", tag = "clipboard")
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy stats",
                                    tint = labelColor,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    if (!ready) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Initializing…",
                                fontSize = if (isLandscape) 18.sp else 16.sp,
                                color = labelColor,
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = ready,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 6.dp else 8.dp),
                        ) {
                        // —— Shared core: Level scope + Instant RMS ——
                        MicSectionHeader("Level", accentColor, isLandscape)
                        LevelScope(
                            history = history,
                            // Same window: keep existing mic stroke; overlay AEC out when Speex is live.
                            overlayHistory = if (softAecHasFrames(aecProbe)) aecHistory else null,
                            background = scopeBg,
                            border = borderColor,
                        )
                        MicStatsRow(
                            "Instant RMS",
                            String.format("%.3f", instantRms),
                            labelColor,
                            // Color by scope-mapped amplitude so green/yellow/red match the line.
                            levelValueColor(scopeDisplayLevel(instantRms), valueColor),
                            isLandscape,
                        )
                        MicStatsRow(
                            "Scope Display",
                            String.format("%.3f", scopeDisplayLevel(instantRms)),
                            labelColor,
                            levelValueColor(scopeDisplayLevel(instantRms), valueColor),
                            isLandscape,
                        )
                        MicStatsRow(
                            "Pre-gain RMS",
                            String.format("%.3f", captureSnap.preGainRms),
                            labelColor,
                            levelValueColor(scopeDisplayLevel(captureSnap.preGainRms), valueColor),
                            isLandscape,
                        )
                        if (showLevelFull) {
                            MicStatsRow(
                                "Session Peak",
                                String.format("%.3f", sessionPeak),
                                labelColor,
                                levelValueColor(scopeDisplayLevel(sessionPeak), valueColor),
                                isLandscape,
                            )
                            MicStatsRow(
                                "Ambient",
                                String.format("%.3f", ambient),
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Headroom",
                                headroomDb?.let { String.format("%.1f dBFS", it) } ?: "—",
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Clipping Risk",
                                clipRisk,
                                labelColor,
                                when (clipRisk) {
                                    "High" -> ColorBad
                                    "Medium" -> ColorWarning
                                    else -> ColorGood
                                },
                                isLandscape,
                            )
                        }

                        if (showHubOverview) {
                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                            MicSectionHeader("Overview", accentColor, isLandscape)
                            MicStatsRow(
                                "Channel",
                                if (serviceRunning) "On" else "Off",
                                labelColor,
                                if (serviceRunning) ColorGood else ColorBad,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Muted",
                                if (muted) "Yes" else "No",
                                labelColor,
                                if (muted) ColorWarning else ColorGood,
                                isLandscape,
                            )
                            MicStatsRow("Engine", engineLabel, labelColor, valueColor, isLandscape)
                            if (wakeBudgetLabel != null) {
                                MicStatsRow(
                                    "Chunk Budget",
                                    wakeBudgetLabel,
                                    labelColor,
                                    wakeBudgetColor,
                                    isLandscape,
                                )
                            }
                            MicStatsRow(
                                "Active Source",
                                activeSourceLabel,
                                labelColor,
                                if (activeSourceLabel != "—") ColorGood else valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Recording",
                                if (captureSnap.isRecording) "Yes" else "No",
                                labelColor,
                                if (captureSnap.isRecording) ColorGood else ColorBad,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Pre-gain / Scope",
                                String.format(
                                    "%.3f / %.3f",
                                    captureSnap.preGainRms,
                                    scopeDisplayLevel(instantRms),
                                ),
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Soft AEC",
                                softAecEngineText,
                                labelColor,
                                softAecEngineColor(softAecEngineText, valueColor),
                                isLandscape,
                            )
                            MicStatsRow(
                                "Soft NS",
                                softNsEngineText,
                                labelColor,
                                softNsEngineColor(softNsEngineText, valueColor),
                                isLandscape,
                            )
                            MicStatsRow(
                                "HW Attached",
                                hwAttachedLabel,
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Far-end / TTS",
                                "${ageMsLabel(aecProbe.signalAgeMs)} · $ttsPlayingLabel",
                                labelColor,
                                if (aecProbe.signalAgeMs in 0..500 || ttsPlayingLabel != "Idle") {
                                    ColorWarning
                                } else {
                                    valueColor
                                },
                                isLandscape,
                            )
                            MicStatsRow(
                                "TTS Mode",
                                ttsModeLabel,
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Wake",
                                "$wakeDetect · conf ${lastWakeConfidence?.let { String.format("%.2f", it) } ?: "—"}",
                                labelColor,
                                when (wakeDetect) {
                                    "Active" -> ColorGood
                                    "Suspended" -> ColorWarning
                                    else -> valueColor
                                },
                                isLandscape,
                            )
                            MicStatsRow(
                                "Voiceprint",
                                voicePrintProbe.statusText,
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Audio Event",
                                lastAudioEventDisplay,
                                labelColor,
                                if (lastAudioEventLabel != null) ColorGood else valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Session Accent",
                                "${sessionAccentHex ?: "—"} · WW${sessionAccentWakeIndex + 1}",
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                        }

                        if (showCaptureFull) {
                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                            MicSectionHeader("Capture", accentColor, isLandscape)
                            MicStatsRow("Engine", engineLabel, labelColor, valueColor, isLandscape)
                            MicStatsRow("AudioSource", audioSourceLabel, labelColor, valueColor, isLandscape)
                            MicStatsRow(
                                "Active Source",
                                activeSourceLabel,
                                labelColor,
                                if (activeSourceLabel != "—") ColorGood else valueColor,
                                isLandscape,
                            )
                            MicStatsRow("Preferred Device", preferredDeviceLabel, labelColor, valueColor, isLandscape)
                            MicStatsRow("Recording Path", pathLabel, labelColor, valueColor, isLandscape)
                            MicStatsRow("Profile", profileLabel, labelColor, valueColor, isLandscape)
                            MicStatsRow(
                                "Muted",
                                if (muted) "Yes" else "No",
                                labelColor,
                                if (muted) ColorWarning else ColorGood,
                                isLandscape,
                            )
                            MicStatsRow("Mic Gain", micGain, labelColor, valueColor, isLandscape)
                            MicStatsRow(
                                "HW Attached NS/AGC/AEC",
                                hwAttachedLabel,
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Last Error",
                                micErrorLabel,
                                labelColor,
                                if (micErrorLabel != "—") ColorBad else valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Recording",
                                if (captureSnap.isRecording) "Yes" else "No",
                                labelColor,
                                if (captureSnap.isRecording) ColorGood else ColorBad,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Temp Paused",
                                if (captureSnap.temporaryPaused) "Yes" else "No",
                                labelColor,
                                if (captureSnap.temporaryPaused) ColorWarning else valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Mic Gain Linear",
                                String.format("%.3f", captureSnap.micGainLinear),
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Mic Volume ×",
                                String.format("%.2f", captureSnap.microphoneVolume),
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Effective Profile",
                                captureSnap.effectiveProfileId.ifBlank { "—" },
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Capture Format",
                                if (captureSnap.captureSampleRateHz > 0) {
                                    "${captureSnap.captureSampleRateHz} Hz / ${captureSnap.captureChannelCount}ch"
                                } else {
                                    "—"
                                },
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                        }

                        if (showPipelineSummary) {
                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                            MicSectionHeader("Pipeline", accentColor, isLandscape)
                            MicStatsRow(
                                "HW Attached NS/AGC/AEC",
                                hwAttachedLabel,
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Soft AEC Engine",
                                softAecEngineText,
                                labelColor,
                                softAecEngineColor(softAecEngineText, valueColor),
                                isLandscape,
                            )
                            MicStatsRow(
                                "Cancel (ERLE)",
                                if (aecHasFrames) {
                                    String.format("%+.1f dB", aecProbe.cancelDb)
                                } else {
                                    "—"
                                },
                                labelColor,
                                if (aecHasFrames) {
                                    cancelDbColor(aecProbe.cancelDb, aecProbe.refRms, valueColor)
                                } else {
                                    valueColor
                                },
                                isLandscape,
                            )
                            MicStatsRow(
                                "Mic / Ref / Out",
                                if (aecHasFrames) {
                                    String.format(
                                        "%.3f / %.3f / %.3f",
                                        aecProbe.micRms,
                                        aecProbe.refRms,
                                        aecProbe.outRms,
                                    )
                                } else {
                                    "—"
                                },
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                        }

                        if (showPipelineFull) {
                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                            MicSectionHeader("Soft AEC Probe", accentColor, isLandscape)
                            SoftAecProbeRows(
                                probe = aecProbe,
                                engineLabel = softAecEngineText,
                                hwAttachedLabel = hwAttachedLabel,
                                labelColor = labelColor,
                                valueColor = valueColor,
                                isLandscape = isLandscape,
                            )
                        }

                        if (showFarEndBlock) {
                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                            MicSectionHeader(
                                if (showPipelineFull) "Far-end Bus" else "Far-end / TTS",
                                accentColor,
                                isLandscape,
                            )
                            if (showPipelineFull) {
                                FarEndBusProbeRows(
                                    probe = aecProbe,
                                    playbackEnergy = playbackEnergy,
                                    labelColor = labelColor,
                                    valueColor = valueColor,
                                    isLandscape = isLandscape,
                                )
                            } else {
                                FarEndBusProbeRows(
                                    probe = aecProbe,
                                    playbackEnergy = playbackEnergy,
                                    labelColor = labelColor,
                                    valueColor = valueColor,
                                    isLandscape = isLandscape,
                                )
                            }
                        }

                        if (showWakePrimary) {
                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                            MicSectionHeader("Wake Probe", accentColor, isLandscape)
                            MicStatsRow(
                                "Detection",
                                wakeDetect,
                                labelColor,
                                when (wakeDetect) {
                                    "Active" -> ColorGood
                                    "Suspended" -> ColorWarning
                                    else -> valueColor
                                },
                                isLandscape,
                            )
                            MicStatsRow("Engine", engineLabel, labelColor, valueColor, isLandscape)
                            if (wakeBudgetLabel != null) {
                                MicStatsRow(
                                    "Chunk Budget",
                                    wakeBudgetLabel,
                                    labelColor,
                                    wakeBudgetColor,
                                    isLandscape,
                                )
                            }
                            MicStatsRow("Active Wake Words", runtimeWakeLabel, labelColor, valueColor, isLandscape)
                            if (focus == VoiceStatsFocus.Wake) {
                                MicStatsRow("Cutoff 1 (setting)", sens1Label, labelColor, valueColor, isLandscape)
                                MicStatsRow("Cutoff 2 (setting)", sens2Label, labelColor, valueColor, isLandscape)
                            }
                            if (focus == VoiceStatsFocus.WakeLibrary) {
                                MicStatsRow(
                                    "Available Models",
                                    "$availableWakeCount",
                                    labelColor,
                                    valueColor,
                                    isLandscape,
                                )
                            }
                            wakeLiveProbe.forEachIndexed { index, probe ->
                                val tag = if (wakeLiveProbe.size > 1) " ${index + 1}" else ""
                                MicStatsRow(
                                    "Window Avg$tag",
                                    String.format("%.3f  (%s)", probe.windowAvg, probe.phrase.take(16)),
                                    labelColor,
                                    when {
                                        probe.windowAvg >= probe.cutoff -> ColorGood
                                        probe.windowAvg >= probe.cutoff * 0.6f -> ColorWarning
                                        else -> valueColor
                                    },
                                    isLandscape,
                                )
                                MicStatsRow(
                                    "Last Frame / Cutoff$tag",
                                    String.format("%.3f / %.3f", probe.lastProb, probe.cutoff),
                                    labelColor,
                                    when {
                                        probe.lastProb >= probe.cutoff -> ColorGood
                                        probe.lastProb >= probe.cutoff * 0.6f -> ColorWarning
                                        else -> valueColor
                                    },
                                    isLandscape,
                                )
                            }
                            if (wakeLiveProbe.isEmpty()) {
                                MicStatsRow(
                                    "Live Prob",
                                    "—",
                                    labelColor,
                                    valueColor,
                                    isLandscape,
                                )
                            }
                            MicStatsRow(
                                "Last Wake",
                                lastWakeLabel,
                                labelColor,
                                if (lastWakeLabel != "—") ColorGood else valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Last Wake Phrase",
                                lastWakePhrase?.take(40) ?: "—",
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Last Wake Conf",
                                lastWakeConfidence?.let { String.format("%.2f", it) } ?: "—",
                                labelColor,
                                when (val conf = lastWakeConfidence) {
                                    null -> valueColor
                                    else -> when {
                                        conf >= 0.7f -> ColorGood
                                        conf >= 0.4f -> ColorWarning
                                        else -> ColorBad
                                    }
                                },
                                isLandscape,
                            )
                            MicStatsRow(
                                "Wake Age",
                                wakeAgeLabel,
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                        }

                        if (showVoicePrintPrimary) {
                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                            MicSectionHeader("Voice Print Probe", accentColor, isLandscape)
                            MicStatsRow(
                                "Status",
                                voicePrintProbe.statusText,
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Identified",
                                voicePrintProbe.identifiedIndex?.let { idx ->
                                    "U$idx · ${String.format("%.2f", voicePrintProbe.identifiedConfidence)}"
                                } ?: "—",
                                labelColor,
                                if (voicePrintProbe.identifiedIndex != null) ColorGood else valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Last Match",
                                voicePrintProbe.lastMatchStatus,
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            val vpHasMatch = voicePrintProbe.lastMatchAgeMs >= 0L
                            MicStatsRow(
                                "Match Conf / Quality",
                                if (vpHasMatch) {
                                    String.format(
                                        "%.2f / %.2f",
                                        voicePrintProbe.lastMatchConfidence,
                                        voicePrintProbe.lastMatchQuality,
                                    )
                                } else {
                                    "—"
                                },
                                labelColor,
                                when {
                                    !vpHasMatch -> valueColor
                                    voicePrintProbe.lastMatchConfidence >= 0.7f -> ColorGood
                                    voicePrintProbe.lastMatchConfidence >= 0.4f -> ColorWarning
                                    else -> ColorBad
                                },
                                isLandscape,
                            )
                            MicStatsRow(
                                "U0 / U1 / Margin",
                                if (vpHasMatch) {
                                    String.format(
                                        "%.2f / %.2f / %.2f",
                                        voicePrintProbe.lastMatchU0,
                                        voicePrintProbe.lastMatchU1,
                                        voicePrintProbe.lastMatchMargin,
                                    )
                                } else {
                                    "—"
                                },
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Match Age",
                                ageMsLabel(voicePrintProbe.lastMatchAgeMs),
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Process Gap",
                                ageMsLabel(voicePrintProbe.processGapMs),
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Extract",
                                if (voicePrintProbe.lastExtractSamples > 0) {
                                    "${voicePrintProbe.lastExtractSamples} smp · waited ${compactAgeMs(voicePrintProbe.lastExtractWaitedMs.toLong())}"
                                } else {
                                    "—"
                                },
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Manual Samples",
                                "${micSettings?.voicePrintManualUser0Samples ?: 0} / ${micSettings?.voicePrintManualUser1Samples ?: 0}",
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Wake Verify Gate",
                                if (voicePrintProbe.manualWakeVerifyRequired) "armed" else "open",
                                labelColor,
                                if (voicePrintProbe.manualWakeVerifyRequired) ColorWarning else valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Ring Snapshot",
                                voicePrintRingSnap,
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                        }

                        if (showAudioEventPrimary) {
                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                            MicSectionHeader("Audio Event Probe", accentColor, isLandscape)
                            val ae = audioEventProbe
                            MicStatsRow(
                                "Capture",
                                if (ae.captureEnabled) "running" else "stopped",
                                labelColor,
                                if (ae.captureEnabled) ColorGood else valueColor,
                                isLandscape,
                            )
                            MicStatsRow("Device Tier", ae.deviceTier, labelColor, valueColor, isLandscape)
                            MicStatsRow(
                                "Windows R/P/S/T",
                                "${ae.windowsReceived}/${ae.windowsProcessed}/${ae.windowsSkipped}/${ae.windowsThrottled}",
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Infer / Interval",
                                if (ae.windowsProcessed > 0L) {
                                    "${compactAgeMs(ae.lastInferenceMs)} / ${compactAgeMs(ae.adaptiveIntervalMs)}"
                                } else {
                                    "—"
                                },
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Top Score",
                                ae.lastTopLabel?.let {
                                    "$it · ${String.format("%.2f", ae.lastTopScore)}" +
                                        if (ae.lastPasses) " · pass" else " · fail"
                                } ?: "—",
                                labelColor,
                                when {
                                    ae.lastTopLabel == null -> valueColor
                                    ae.lastPasses -> ColorGood
                                    else -> ColorWarning
                                },
                                isLandscape,
                            )
                            MicStatsRow(
                                "Feat / NZ / PCM",
                                if (ae.windowsProcessed > 0L) {
                                    String.format(
                                        "%.3f / %.2f / %.3f",
                                        ae.lastFeatureMean,
                                        ae.lastFeatureNz,
                                        ae.lastPcmRms,
                                    )
                                } else {
                                    "—"
                                },
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Pending",
                                ae.pendingLabel?.let {
                                    "$it · ${ae.pendingCount}/${ae.confirmRequired}"
                                } ?: "—",
                                labelColor,
                                if (ae.pendingLabel != null) ColorWarning else valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Active / Display",
                                "${ae.activeLabel ?: "idle"} · ${compactAgeMs(ae.displayMs)}",
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Last Event",
                                lastAudioEventDisplay,
                                labelColor,
                                if (lastAudioEventLabel != null) ColorGood else valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Monitored",
                                "${ae.monitoredCount}",
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                        }

                        if (showStreamingTtsPrimary) {
                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                            MicSectionHeader("Streaming TTS Probe", accentColor, isLandscape)
                            MicStatsRow(
                                "PCM / URL / Whisper",
                                listOf(
                                    if (pcmTtsActive) "PCM" else null,
                                    if (urlTtsPlaying) "URL" else null,
                                    if (whisperPlaybackActive) "Whisper" else null,
                                ).filterNotNull().joinToString("+").ifBlank { "Idle" },
                                labelColor,
                                if (pcmTtsActive || urlTtsPlaying || whisperPlaybackActive) {
                                    ColorWarning
                                } else {
                                    valueColor
                                },
                                isLandscape,
                            )
                            MicStatsRow(
                                "PCM Queue / Pending",
                                "$pcmTtsQueueDepth frames · $pendingPcmChunks chunks",
                                labelColor,
                                if (pcmTtsQueueDepth > 8 || pendingPcmChunks > 0) ColorWarning else valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "PCM Bytes Out",
                                pcmTtsBytesSubmitted.toString(),
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "PCM Volume",
                                pcmTtsVolume?.let { String.format("%.2f", it) } ?: "—",
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Playback Energy",
                                playbackEnergyLabel(playbackEnergy),
                                labelColor,
                                if (PlaybackEnergyMonitor.isEnabled()) {
                                    levelValueColor(playbackEnergy, valueColor)
                                } else {
                                    valueColor
                                },
                                isLandscape,
                            )
                            MicStatsRow(
                                "Last TTS Host",
                                lastTtsHost ?: "—",
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                        }

                        if (showFeedbackAccent) {
                            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                            MicSectionHeader("Accent Probe", accentColor, isLandscape)
                            MicStatsRow("WW1 Accent", ww1AccentLabel, labelColor, valueColor, isLandscape)
                            MicStatsRow("WW2 Accent", ww2AccentLabel, labelColor, valueColor, isLandscape)
                            MicStatsRow(
                                "Session Accent",
                                sessionAccentHex ?: "—",
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Wake Index",
                                "WW${sessionAccentWakeIndex + 1}",
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                            MicStatsRow(
                                "Accent Age",
                                sessionAccentAgeMs?.let { formatAgeMs(it) } ?: "—",
                                labelColor,
                                valueColor,
                                isLandscape,
                            )
                        }

                        // —— Shared core: Session compact ——
                        HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                        MicSectionHeader("Session", accentColor, isLandscape)
                        MicStatsRow("State", stateLabel, labelColor, stateColor(stateLabel, valueColor), isLandscape)
                        MicStatsRow(
                            "Service",
                            if (serviceRunning) "Running" else "Stopped",
                            labelColor,
                            if (serviceRunning) ColorGood else ColorBad,
                            isLandscape,
                        )
                        MicStatsRow(
                            "Streaming",
                            if (streaming) "Yes" else "No",
                            labelColor,
                            if (streaming) ColorGood else valueColor,
                            isLandscape,
                        )
                        MicStatsRow(
                            "Wake Suspended",
                            if (wakeSuspended) "Yes" else "No",
                            labelColor,
                            if (wakeSuspended) ColorWarning else valueColor,
                            isLandscape,
                        )
                        MicStatsRow(
                            "Last Wake",
                            if (lastWakeLabel == "—") "—" else "$lastWakeLabel · $wakeAgeLabel",
                            labelColor,
                            if (lastWakeLabel != "—") ColorGood else valueColor,
                            isLandscape,
                        )
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun LevelScope(
    history: List<Float>,
    background: Color,
    border: Color,
    /** Soft-AEC out level (same 0–1 mapping). Ghost fill under the original mic stroke. */
    overlayHistory: List<Float>? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(vertical = 4.dp)
            .background(background, RoundedCornerShape(10.dp))
            .border(1.dp, border, RoundedCornerShape(10.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 4.dp, vertical = 6.dp)) {
            val w = size.width
            val h = size.height
            if (w < 2f || h < 2f || history.size < 2) return@Canvas
            val last = history.size - 1

            // Style B ghost: filled band under AEC out — does not replace the mic stroke.
            val overlay = overlayHistory
            if (overlay != null && overlay.size == history.size) {
                val fill = Path().apply {
                    moveTo(0f, h)
                    for (i in 0..last) {
                        val n = overlay[i].coerceIn(0f, 1f)
                        val x = i.toFloat() / last * w
                        lineTo(x, h - n * h)
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(
                    path = fill,
                    color = Color(0xFF38BDF8).copy(alpha = 0.28f),
                    style = Fill,
                )
                for (i in 1..last) {
                    val n0 = overlay[i - 1].coerceIn(0f, 1f)
                    val n1 = overlay[i].coerceIn(0f, 1f)
                    drawLine(
                        color = Color(0xFF7DD3FC).copy(alpha = 0.55f),
                        start = Offset((i - 1).toFloat() / last * w, h - n0 * h),
                        end = Offset(i.toFloat() / last * w, h - n1 * h),
                        strokeWidth = 1.5f,
                        cap = StrokeCap.Round,
                    )
                }
            }

            // Original mic scrolling stroke — same color mapping and 1.85f width as before.
            for (i in 1..last) {
                val n0 = history[i - 1].coerceIn(0f, 1f)
                val n1 = history[i].coerceIn(0f, 1f)
                val x0 = (i - 1).toFloat() / last * w
                val x1 = i.toFloat() / last * w
                val y0 = h - n0 * h
                val y1 = h - n1 * h
                drawLine(
                    color = levelStrokeColor((n0 + n1) * 0.5f),
                    start = Offset(x0, y0),
                    end = Offset(x1, y1),
                    strokeWidth = 1.85f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun MicSectionHeader(title: String, color: Color, isLandscape: Boolean) {
    Text(
        text = title,
        fontSize = if (isLandscape) 13.sp else 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun SoftAecProbeRows(
    probe: SoftAecProbeSnapshot,
    engineLabel: String,
    hwAttachedLabel: String,
    labelColor: Color,
    valueColor: Color,
    isLandscape: Boolean,
) {
    val hasFrames = softAecHasFrames(probe)
    MicStatsRow(
        "Engine",
        engineLabel,
        labelColor,
        softAecEngineColor(engineLabel, valueColor),
        isLandscape,
    )
    MicStatsRow(
        "Design",
        "${probe.sampleRateHz / 1000}k · frame ${probe.frameSize} · " +
            "filter ${probe.filterLengthMs}ms · bulk ${probe.bulkDelayMs}ms",
        labelColor,
        valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Residual Suppress",
        "${probe.suppressDb} / ${probe.suppressActiveDb} dB",
        labelColor,
        valueColor,
        isLandscape,
    )
    MicStatsRow("HW Attached NS/AGC/AEC", hwAttachedLabel, labelColor, valueColor, isLandscape)
    MicStatsRow(
        "Mic Frame RMS",
        if (hasFrames) String.format("%.4f", probe.micRms) else "—",
        labelColor,
        if (hasFrames) levelValueColor(scopeDisplayLevel(probe.micRms), valueColor) else valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Ref Frame RMS",
        if (hasFrames) String.format("%.4f", probe.refRms) else "—",
        labelColor,
        if (hasFrames) levelValueColor(scopeDisplayLevel(probe.refRms), valueColor) else valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Out Frame RMS",
        if (hasFrames) String.format("%.4f", probe.outRms) else "—",
        labelColor,
        if (hasFrames) levelValueColor(scopeDisplayLevel(probe.outRms), valueColor) else valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Cancel (ERLE)",
        if (hasFrames) String.format("%+.1f dB", probe.cancelDb) else "—",
        labelColor,
        if (hasFrames) cancelDbColor(probe.cancelDb, probe.refRms, valueColor) else valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Ref Occupancy",
        if (hasFrames) String.format("%.0f%%", probe.refNonZeroPct) else "—",
        labelColor,
        if (hasFrames && probe.refNonZeroPct > 5f) ColorWarning else valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Mic Clip / Peak",
        if (hasFrames) {
            String.format("%.2f%% · %d", probe.micClipPct, probe.micPeak)
        } else {
            "—"
        },
        labelColor,
        when {
            !hasFrames -> valueColor
            probe.micClipPct >= 1f -> ColorBad
            probe.micClipPct > 0.1f -> ColorWarning
            else -> ColorGood
        },
        isLandscape,
    )
    MicStatsRow(
        "AEC3 ERL/ERLE/Delay",
        if (hasFrames) {
            String.format(
                "%.1f / %.1f dB · %.0fms",
                probe.aec3ErlDb,
                probe.aec3ErleDb,
                probe.aec3DelayMs,
            )
        } else {
            "—"
        },
        labelColor,
        valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Frames Proc / Bypass",
        "${probe.framesProcessed} / ${probe.framesBypassed}",
        labelColor,
        valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Process Age",
        ageMsLabel(probe.processAgeMs),
        labelColor,
        valueColor,
        isLandscape,
    )
}

@Composable
private fun FarEndBusProbeRows(
    probe: SoftAecProbeSnapshot,
    playbackEnergy: Float,
    labelColor: Color,
    valueColor: Color,
    isLandscape: Boolean,
) {
    MicStatsRow(
        "Signal Age",
        ageMsLabel(probe.signalAgeMs),
        labelColor,
        if (probe.signalAgeMs in 0..500) ColorWarning else valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Write Age",
        if (probe.busActive) ageMsLabel(probe.writeAgeMs) else "n/a (gate closed)",
        labelColor,
        if (probe.busActive && probe.writeAgeMs in 0..500) ColorGood else valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Writer Ahead",
        if (!probe.busActive) {
            "n/a (gate closed)"
        } else if (probe.writerAheadSamples <= 0L) {
            "0ms"
        } else {
            "${compactAgeMs(probe.writerAheadMs)} · ${probe.writerAheadSamples} smp"
        },
        labelColor,
        valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Samples Written",
        if (probe.busActive) probe.samplesWritten.toString() else "n/a (gate closed)",
        labelColor,
        valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Reanchors",
        if (probe.busActive) probe.reanchorCount.toString() else "n/a (gate closed)",
        labelColor,
        if (probe.busActive && probe.reanchorCount > 0) ColorWarning else valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Bus Gate",
        if (probe.busActive) "open" else "closed",
        labelColor,
        if (probe.busActive) ColorGood else valueColor,
        isLandscape,
    )
    MicStatsRow(
        "Playback Energy",
        playbackEnergyLabel(playbackEnergy),
        labelColor,
        if (PlaybackEnergyMonitor.isEnabled()) {
            levelValueColor(playbackEnergy, valueColor)
        } else {
            valueColor
        },
        isLandscape,
    )
}

/** Compact age/duration for stats UI — avoids runaway "203095 ms" anxiety. */
private fun compactAgeMs(ageMs: Long): String = when {
    ageMs < 0L -> "—"
    ageMs < 1_000L -> "${ageMs}ms"
    ageMs < 10_000L -> String.format("%.1fs", ageMs / 1000.0)
    ageMs < 60_000L -> "${ageMs / 1000L}s"
    ageMs < 3_600_000L -> {
        val m = ageMs / 60_000L
        val s = (ageMs % 60_000L) / 1000L
        if (s == 0L) "${m}m" else "${m}m ${s}s"
    }
    else -> {
        val h = ageMs / 3_600_000L
        val m = (ageMs % 3_600_000L) / 60_000L
        if (m == 0L) "${h}h" else "${h}h ${m}m"
    }
}

private fun ageMsLabel(ageMs: Long): String = compactAgeMs(ageMs)

private fun softAecHasFrames(probe: SoftAecProbeSnapshot): Boolean =
    probe.framesProcessed > 0L && probe.processAgeMs >= 0L

private fun softAecEngineLabel(
    settingEnabled: Boolean?,
    constructed: Boolean,
    active: Boolean,
    isAec3: Boolean,
    pausedForSpeech: Boolean,
): String = when {
    settingEnabled != true -> "Off (setting)"
    pausedForSpeech -> "Bypass (speech)"
    active -> if (isAec3) "AEC3 live" else "Speex live"
    constructed -> "Init failed"
    else -> "Idle"
}

private fun softAecEngineColor(label: String, fallback: Color): Color = when (label) {
    "AEC3 live", "Speex live" -> ColorGood
    "Bypass (speech)" -> ColorWarning
    "Init failed" -> ColorBad
    else -> fallback
}

private fun softNsEngineLabel(
    settingEnabled: Boolean?,
    constructed: Boolean,
    active: Boolean,
): String = when {
    settingEnabled != true -> "Off (setting)"
    active -> "WebRTC live"
    constructed -> "Init failed"
    else -> "Idle"
}

private fun softNsEngineColor(label: String, fallback: Color): Color = when (label) {
    "WebRTC live" -> ColorGood
    "Init failed" -> ColorBad
    else -> fallback
}

private fun playbackEnergyLabel(level: Float): String =
    if (PlaybackEnergyMonitor.isEnabled()) String.format("%.3f", level) else "—"

private fun cancelDbColor(cancelDb: Float, refRms: Float, fallback: Color): Color {
    if (refRms < 0.005f) return fallback
    return when {
        cancelDb >= 6f -> ColorGood
        cancelDb >= 2f -> ColorWarning
        else -> ColorBad
    }
}

@Composable
private fun MicStatsRow(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color,
    isLandscape: Boolean,
) {
    val labelSize = if (isLandscape) 13.sp else 12.sp
    val valueSize = if (isLandscape) 13.sp else 12.sp
    val fadeToColor = LocalMicStatsFadeColor.current
    // Long paths / hosts / design lines: keep right-aligned row, fold with fade (settings style).
    val longValue = value.length > 24 ||
        value.contains('/') ||
        value.count { it == '.' } >= 3

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            fontSize = labelSize,
            color = labelColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(min = 88.dp)
                .weight(0.40f)
                .padding(end = 8.dp),
        )
        if (longValue) {
            CollapsibleDescriptionText(
                text = value,
                modifier = Modifier.weight(0.60f),
                collapsedLines = 2,
                fontSize = valueSize,
                lineHeight = valueSize * 1.25f,
                color = valueColor,
                fadeToColor = fadeToColor,
                topPadding = 0.dp,
                textAlign = TextAlign.End,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        } else {
            Text(
                text = value,
                fontSize = valueSize,
                color = valueColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                softWrap = true,
                modifier = Modifier.weight(0.60f),
            )
        }
    }
}

/** Map capture float-RMS onto scope amplitude so normal speech moves the line. */
private fun scopeDisplayLevel(rms: Float): Float =
    (rms.coerceIn(0f, 1f) * SCOPE_LEVEL_GAIN).coerceIn(0f, 1f)

/** Height → green / yellow / red on the scrolling level line. */
private fun levelStrokeColor(n: Float): Color {
    val t = n.coerceIn(0f, 1f)
    val yAt = 0.28f
    val rAt = 0.62f
    return when {
        t < yAt -> lerpRgb(Color(0xFF22C55E), Color(0xFFEAB308), t / yAt)
        t < rAt -> lerpRgb(Color(0xFFEAB308), Color(0xFFEF4444), (t - yAt) / (rAt - yAt))
        else -> lerpRgb(Color(0xFFEF4444), Color(0xFFDC2626), (t - rAt) / (1f - rAt))
    }
}

private fun lerpRgb(a: Color, b: Color, u: Float): Color {
    val t = u.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )
}

/** Status text colors aligned with [levelStrokeColor] bands (green / yellow / red). */
private fun levelValueColor(n: Float, fallback: Color): Color {
    val t = n.coerceIn(0f, 1f)
    return when {
        t >= 0.62f -> ColorBad
        t >= 0.28f -> ColorWarning
        t > 0.02f -> ColorGood
        else -> fallback
    }
}

/** Headroom below full-scale for a 0–1 peak RMS; null when no peak yet. */
private fun headroomDbfs(peak: Float): Float? {
    if (peak <= 1e-6f) return null
    return (-20f * log10(peak.coerceIn(1e-6f, 1f)))
}

private fun clippingRisk(peak: Float): String = when {
    peak > 0.85f -> "High"
    peak > 0.55f -> "Medium"
    else -> "Low"
}

private fun audioSourceName(source: Int): String = when (source) {
    MediaRecorder.AudioSource.MIC -> "MIC"
    MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
    MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
    MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
    else -> "SRC_$source"
}

private fun attachedFlag(attached: Boolean?): String = when (attached) {
    true -> "On"
    false -> "Off"
    null -> "—"
}

private fun sensitivityLabel(value: Float?, extraLevel: Int = 0): String {
    val base = when {
        value == null || value < 0f -> "Default"
        else -> String.format("%.2f", value)
    }
    return when (extraLevel) {
        1 -> "$base · Strict+"
        2 -> "$base · Max"
        else -> base
    }
}

private fun formatAgeMs(ageMs: Long): String {
    val compact = compactAgeMs(ageMs)
    return if (compact == "—") compact else "$compact ago"
}

/** Resolve preset key / #RRGGBB / empty → display hex token. */
private fun accentHexToken(spec: String, defaultHex: String): String {
    val trimmed = spec.trim()
    if (trimmed.isEmpty()) return defaultHex
    if (trimmed.startsWith("#")) return trimmed.uppercase()
    val resolved = VoiceAccentColors.resolve(
        trimmed,
        android.graphics.Color.parseColor(defaultHex),
    )
    return String.format("#%06X", 0xFFFFFF and resolved)
}

private fun formatEspHomeState(state: Any?): String = when (state) {
    is Listening -> "Listening"
    is Processing -> "Processing"
    is Responding -> "Responding"
    is Connected -> "Idle"
    is Disconnected -> "Disconnected"
    is Stopped -> "Stopped"
    is ServerError -> "Error"
    null -> "—"
    else -> state::class.simpleName ?: "—"
}

private fun stateColor(label: String, fallback: Color): Color = when (label) {
    "Listening", "Responding" -> ColorGood
    "Processing" -> ColorWarning
    "Disconnected", "Stopped", "Error" -> ColorBad
    else -> fallback
}

private fun wakeBudgetValueColor(probe: WakeEngineBudgetProbe?, fallback: Color): Color = when {
    probe == null || probe.samples <= 0 -> fallback
    probe.overBudget -> ColorBad
    else -> ColorGood
}

private fun formatVoiceStatsForCopy(
    focus: VoiceStatsFocus,
    rms: Float,
    peak: Float,
    ambient: Float,
    headroomDb: Float?,
    clipRisk: String,
    engine: String,
    activeSource: String,
    muted: Boolean,
    hwAttached: String,
    aecProbe: SoftAecProbeSnapshot,
    wakeDetect: String,
    runtimeWakeWords: String,
    availableWakeCount: Int,
    sens1: String,
    sens2: String,
    lastWake: String,
    lastWakeConf: Float?,
    wakeLive: List<WakeWordLiveProbe>,
    wakeBudget: WakeEngineBudgetProbe? = null,
    voicePrint: VoicePrintProbeSnapshot,
    voicePrintRingSnap: String,
    voicePrintSamples: String,
    audioEvent: AudioEventProbeSnapshot,
    lastAudioEvent: String,
    pcmTtsActive: Boolean,
    urlTtsPlaying: Boolean,
    whisperActive: Boolean,
    pcmQueue: Int,
    pendingChunks: Int,
    pcmBytes: Long,
    pcmVolume: Float?,
    playbackEnergy: Float,
    lastTtsHost: String?,
    ww1Accent: String,
    ww2Accent: String,
    sessionAccent: String?,
    sessionAccentWakeIndex: Int,
    sessionAccentAgeMs: Long?,
    state: String,
    streaming: Boolean,
    service: String,
    showHubOverview: Boolean,
    showLevelFull: Boolean,
    showCaptureFull: Boolean,
    showPipelineFull: Boolean,
    showPipelineSummary: Boolean,
    showFarEndBlock: Boolean,
    showWakePrimary: Boolean,
    showVoicePrintPrimary: Boolean,
    showAudioEventPrimary: Boolean,
    showStreamingTtsPrimary: Boolean,
    showFeedbackAccent: Boolean,
    wakeFocus: Boolean,
    wakeLibraryFocus: Boolean,
    captureSnap: MicrophoneCaptureSnapshot,
): String = buildString {
    appendLine("=== Voice Stats ===")
    appendLine("Focus: ${focus.displayLabel()}")
    appendLine()
    appendLine("[Level]")
    appendLine("Instant RMS: ${String.format("%.3f", rms)}")
    if (showLevelFull) {
        appendLine("Session Peak: ${String.format("%.3f", peak)}")
        appendLine("Ambient: ${String.format("%.3f", ambient)}")
        appendLine("Headroom: ${headroomDb?.let { String.format("%.1f dBFS", it) } ?: "—"}")
        appendLine("Clipping Risk: $clipRisk")
    }
    if (showHubOverview) {
        appendLine()
        appendLine("[Overview]")
        appendLine("Channel: ${if (service == "Running") "On" else "Off"}")
        appendLine("Muted: ${if (muted) "Yes" else "No"}")
        appendLine("Engine: $engine")
        if (wakeBudget != null && wakeBudget.samples > 0) {
            appendLine(
                "Chunk Budget: ${"%.2f".format(wakeBudget.avgMs)} avg / ${wakeBudget.budgetMs} ms " +
                    "(peak ${"%.2f".format(wakeBudget.peakMs)}" +
                    if (wakeBudget.overBudget) ", over budget)" else ")",
            )
        }
        appendLine("Active Source: $activeSource")
        appendLine("Soft AEC: ${if (captureSnap.softwareAecActive) (if (aecProbe.engineIsAec3) "AEC3 live" else "Speex live") else if (captureSnap.softwareAecPausedForSpeech) "Bypass (speech)" else if (captureSnap.softwareAecConstructed) "Init failed" else "Off/Idle"}")
        appendLine("Soft NS: ${if (captureSnap.softwareNsActive) "WebRTC live" else if (captureSnap.softwareNsConstructed) "Init failed" else "Off/Idle"}")
        appendLine("HW Attached: $hwAttached")
        appendLine("Far-end Age: ${ageMsLabel(aecProbe.signalAgeMs)}")
        appendLine("Wake: $wakeDetect · conf ${lastWakeConf?.let { String.format("%.2f", it) } ?: "—"}")
        appendLine("Voiceprint: ${voicePrint.statusText}")
        appendLine("Audio Event: $lastAudioEvent")
        appendLine("Session Accent: ${sessionAccent ?: "—"}")
    }
    if (showCaptureFull) {
        appendLine()
        appendLine("[Capture]")
        appendLine("Active Source: $activeSource")
        appendLine("HW Attached: $hwAttached")
        appendLine("Recording: ${captureSnap.isRecording}")
        appendLine("Mic Gain Linear: ${String.format("%.3f", captureSnap.micGainLinear)}")
        appendLine("Mic Volume: ${String.format("%.2f", captureSnap.microphoneVolume)}")
        appendLine("Capture Format: ${captureSnap.captureSampleRateHz} Hz / ${captureSnap.captureChannelCount}ch")
    }
    if (showPipelineSummary) {
        appendLine()
        appendLine("[Pipeline]")
        appendLine("HW Attached: $hwAttached")
        appendLine(
            "Soft AEC: active=${captureSnap.softwareAecActive} " +
                "paused=${captureSnap.softwareAecPausedForSpeech} " +
                "constructed=${captureSnap.softwareAecConstructed}",
        )
        appendLine(
            "Soft NS: active=${captureSnap.softwareNsActive} " +
                "constructed=${captureSnap.softwareNsConstructed}",
        )
        if (softAecHasFrames(aecProbe)) {
            appendLine("Cancel ERLE: ${String.format("%+.1f dB", aecProbe.cancelDb)}")
            appendLine(
                "Mic/Ref/Out: ${String.format("%.3f / %.3f / %.3f", aecProbe.micRms, aecProbe.refRms, aecProbe.outRms)}",
            )
        } else {
            appendLine("Cancel ERLE: —")
            appendLine("Mic/Ref/Out: —")
        }
    }
    if (showPipelineFull) {
        appendLine()
        appendLine("[Soft AEC Probe]")
        appendLine("Engine active=${aecProbe.engineActive} frames=${aecProbe.framesProcessed}")
        if (softAecHasFrames(aecProbe)) {
            appendLine("Mic Frame RMS: ${String.format("%.4f", aecProbe.micRms)}")
            appendLine("Ref Frame RMS: ${String.format("%.4f", aecProbe.refRms)}")
            appendLine("Out Frame RMS: ${String.format("%.4f", aecProbe.outRms)}")
            appendLine("Cancel (ERLE): ${String.format("%+.1f dB", aecProbe.cancelDb)}")
            appendLine(
                "Mic Clip / Peak: ${String.format("%.2f%% · %d", aecProbe.micClipPct, aecProbe.micPeak)}",
            )
            appendLine(
                "AEC3 ERL/ERLE/Delay: ${
                    String.format(
                        "%.1f / %.1f dB · %.0fms",
                        aecProbe.aec3ErlDb,
                        aecProbe.aec3ErleDb,
                        aecProbe.aec3DelayMs,
                    )
                }",
            )
        } else {
            appendLine("Mic/Ref/Out/ERLE: — (no frames)")
        }
        appendLine("Frames Proc / Bypass: ${aecProbe.framesProcessed} / ${aecProbe.framesBypassed}")
    }
    if (showFarEndBlock) {
        appendLine()
        appendLine("[Far-end Bus]")
        appendLine("Signal Age: ${ageMsLabel(aecProbe.signalAgeMs)}")
        appendLine("Write Age: ${if (aecProbe.busActive) ageMsLabel(aecProbe.writeAgeMs) else "n/a"}")
        appendLine(
            "Writer Ahead: ${if (aecProbe.busActive) compactAgeMs(aecProbe.writerAheadMs) else "n/a"}",
        )
        appendLine(
            "Samples Written: ${if (aecProbe.busActive) aecProbe.samplesWritten else "n/a"}",
        )
        appendLine("Reanchors: ${if (aecProbe.busActive) aecProbe.reanchorCount else "n/a"}")
        appendLine("Bus Gate: ${if (aecProbe.busActive) "open" else "closed"}")
        appendLine("Playback Energy: ${playbackEnergyLabel(playbackEnergy)}")
    }
    if (showWakePrimary) {
        appendLine()
        appendLine("[Wake Probe]")
        appendLine("Detection: $wakeDetect")
        appendLine("Engine: $engine")
        if (wakeBudget != null && wakeBudget.samples > 0) {
            appendLine(
                "Chunk Budget: ${"%.2f".format(wakeBudget.avgMs)} avg / ${wakeBudget.budgetMs} ms " +
                    "(peak ${"%.2f".format(wakeBudget.peakMs)}" +
                    if (wakeBudget.overBudget) ", over budget)" else ")",
            )
        }
        appendLine("Active Wake Words: $runtimeWakeWords")
        if (wakeFocus) {
            // "(setting)" = the stored slider value; the effective per-model cutoff is
            // the Last Frame/Cutoff line (out-of-range settings fall back to manifest).
            appendLine("Cutoff 1 (setting): $sens1")
            appendLine("Cutoff 2 (setting): $sens2")
        }
        if (wakeLibraryFocus) appendLine("Available Models: $availableWakeCount")
        wakeLive.forEachIndexed { i, p ->
            appendLine("Window Avg[$i]: ${String.format("%.3f", p.windowAvg)} (${p.phrase})")
            appendLine("Last Frame/Cutoff[$i]: ${String.format("%.3f / %.3f", p.lastProb, p.cutoff)}")
        }
        appendLine("Last Wake: $lastWake · conf ${lastWakeConf?.let { String.format("%.2f", it) } ?: "—"}")
    }
    if (showVoicePrintPrimary) {
        appendLine()
        appendLine("[Voice Print Probe]")
        appendLine("Status: ${voicePrint.statusText}")
        appendLine(
            "Identified: ${voicePrint.identifiedIndex?.let { "U$it · ${String.format("%.2f", voicePrint.identifiedConfidence)}" } ?: "—"}",
        )
        appendLine("Last Match: ${voicePrint.lastMatchStatus}")
        if (voicePrint.lastMatchAgeMs >= 0L) {
            appendLine(
                "Conf/Quality: ${String.format("%.2f / %.2f", voicePrint.lastMatchConfidence, voicePrint.lastMatchQuality)}",
            )
            appendLine(
                "U0/U1/Margin: ${String.format("%.2f / %.2f / %.2f", voicePrint.lastMatchU0, voicePrint.lastMatchU1, voicePrint.lastMatchMargin)}",
            )
        } else {
            appendLine("Conf/Quality: —")
            appendLine("U0/U1/Margin: —")
        }
        appendLine("Match Age: ${ageMsLabel(voicePrint.lastMatchAgeMs)}")
        appendLine("Process Gap: ${ageMsLabel(voicePrint.processGapMs)}")
        appendLine(
            "Extract: ${voicePrint.lastExtractSamples} smp · ${compactAgeMs(voicePrint.lastExtractWaitedMs.toLong())}",
        )
        appendLine("Manual Samples: $voicePrintSamples")
        appendLine("Ring Snapshot: $voicePrintRingSnap")
    }
    if (showAudioEventPrimary) {
        appendLine()
        appendLine("[Audio Event Probe]")
        appendLine("Capture: ${if (audioEvent.captureEnabled) "running" else "stopped"}")
        appendLine("Tier: ${audioEvent.deviceTier}")
        appendLine(
            "Windows R/P/S/T: ${audioEvent.windowsReceived}/${audioEvent.windowsProcessed}/" +
                "${audioEvent.windowsSkipped}/${audioEvent.windowsThrottled}",
        )
        if (audioEvent.windowsProcessed > 0L) {
            appendLine(
                "Infer/Interval: ${compactAgeMs(audioEvent.lastInferenceMs)} / ${compactAgeMs(audioEvent.adaptiveIntervalMs)}",
            )
            appendLine(
                "Top: ${audioEvent.lastTopLabel ?: "—"} ${String.format("%.2f", audioEvent.lastTopScore)} " +
                    "pass=${audioEvent.lastPasses}",
            )
        } else {
            appendLine("Infer/Interval: —")
            appendLine("Top: —")
        }
        appendLine("Last Event: $lastAudioEvent")
    }
    if (showStreamingTtsPrimary) {
        appendLine()
        appendLine("[Streaming TTS Probe]")
        appendLine("PCM/URL/Whisper: $pcmTtsActive/$urlTtsPlaying/$whisperActive")
        appendLine("Queue/Pending: $pcmQueue / $pendingChunks")
        appendLine("Bytes Out: $pcmBytes")
        appendLine("PCM Volume: ${pcmVolume?.let { String.format("%.2f", it) } ?: "—"}")
        appendLine("Playback Energy: ${playbackEnergyLabel(playbackEnergy)}")
        appendLine("Last TTS Host: ${lastTtsHost ?: "—"}")
    }
    if (showFeedbackAccent) {
        appendLine()
        appendLine("[Accent Probe]")
        appendLine("WW1: $ww1Accent")
        appendLine("WW2: $ww2Accent")
        appendLine("Session: ${sessionAccent ?: "—"} · WW${sessionAccentWakeIndex + 1}")
        appendLine("Accent Age: ${sessionAccentAgeMs?.let { formatAgeMs(it) } ?: "—"}")
    }
    appendLine()
    appendLine("[Session]")
    appendLine("State: $state")
    appendLine("Service: $service")
    appendLine("Streaming: ${if (streaming) "Yes" else "No"}")
}
