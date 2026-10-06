package com.example.ava.ui.screens.settings.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.example.ava.ui.AvaToast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.ui.rememberCompactSquareScreen
import com.example.ava.ui.screens.settings.LocalSettingsSplitActive
import com.example.ava.ui.screens.settings.getMassChromeAccent
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.rememberSettingsHandleLandscape
import kotlinx.coroutines.delay

private val ColorGood = Color(0xFF4CAF50)
private val ColorWarning = Color(0xFFFFC107)
private val ColorBad = Color(0xFFF44336)

data class SendspinStats(
    val serverName: String = "--",
    val serverAddress: String = "--",
    val connectionState: String = "Unknown",
    val audioCodec: String = "--",
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val streamSource: String = "--",
    val playbackState: String = "Unknown",
    val syncUncertaintyMs: Double = 0.0,
    val rttMs: Double = 0.0,
    val networkQuality: String = "UNKNOWN",
    val clockStability: String = "UNKNOWN",
    val clockDriftPpm: Double = 0.0,
    val audioLatencyMs: Double = 0.0,
    val playoutOffsetMs: Double = 0.0,
    val lateDrops: Long = 0,
    val audibleSyncs: Long = 0,
    val kalmanErrorCount: Long = 0,
    val bufferAheadMs: Long = 0,
    val queuedChunks: Int = 0,
    val chunksReceived: Long = 0,
    val chunksPlayed: Long = 0,
    val chunksDropped: Long = 0,
    val bufferUnderrunCount: Long = 0,
    val outputDevice: String = "--",
    val volumePercent: Int = 0,
    val isMuted: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val isConnected: Boolean = false,
    val driftUncertaintyPpm: Double = 0.0,
    val driftSnr: Double = 0.0,
    val connectionDrops: Int = 0,
    val clockReady: Boolean = false,
    val forceResync: Boolean = false,
    val audioOutputStarted: Boolean = false,
    val serverLatenessMs: Long = 0,
    val networkJitterMs: Long = 0,
    val clockUpdateCount: Int = 0,
    val staticDelayMs: Long = 0,
    val estimatedOffsetMs: Long = 0
)

/**
 * Settings handle sheet. Landscape split keeps it inside the current pane;
 * otherwise [SettingsHandleSheet] uses a window [ModalBottomSheet].
 * Vinyl overlay must use [MediaPlayerStatsPanel] instead (no Dialog).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPlayerStatsSheet(
    onDismiss: () -> Unit,
    isDarkMode: Boolean
) {
    SettingsHandleSheet(
        onDismiss = onDismiss,
        containerColor = if (isDarkMode) Color(0xFF161616) else Color(0xFFF8FAFC),
    ) {
        MediaPlayerStatsPanel(
            onDismiss = onDismiss,
            isDarkMode = isDarkMode,
            showDragHandle = true,
        )
    }
}

/**
 * Sendspin stats card + 500ms poll. No Dialog / WindowManager token —
 * safe inside VinylCoverService ComposeView. Poll runs only while composed
 * (caller must branch so closed = not mounted).
 *
 * [floating]: inset card for vinyl overlay — rounded shell, not edge-snapped.
 * Settings [MediaPlayerStatsSheet] keeps the flush ModalBottomSheet chrome.
 */
@Composable
fun MediaPlayerStatsPanel(
    onDismiss: () -> Unit,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    showDragHandle: Boolean = true,
    floating: Boolean = false,
) {
    val isLandscape = rememberSettingsHandleLandscape()
    val maxSheetHeight = settingsHandleSheetMaxHeight()
    val compactSquare = rememberCompactSquareScreen()
    val fillSquare = floating && compactSquare
    val splitFill = !floating && LocalSettingsSplitActive.current
    val occupy = splitFill || compactSquare
    val sheetFlingGuard = rememberModalSheetScrollFlingGuard()

    var stats by remember { mutableStateOf(SendspinStats()) }

    LaunchedEffect(Unit) {
        while (true) {
            val service = VoiceSatelliteService.getInstance()
            val sendspinStats = service?.getSendspinStats()
            if (sendspinStats != null) {
                stats = SendspinStats(
                    serverName = sendspinStats["server_name"] as? String ?: "--",
                    serverAddress = sendspinStats["server_address"] as? String ?: "--",
                    connectionState = sendspinStats["connection_state"] as? String ?: "Unknown",
                    audioCodec = sendspinStats["audio_codec"] as? String ?: "--",
                    sampleRate = sendspinStats["sample_rate"] as? Int ?: 0,
                    bitDepth = sendspinStats["bit_depth"] as? Int ?: 0,
                    channels = sendspinStats["channels"] as? Int ?: 0,
                    streamSource = sendspinStats["stream_source"] as? String ?: "--",
                    playbackState = sendspinStats["playback_state"] as? String ?: "Unknown",
                    syncUncertaintyMs = (sendspinStats["sync_uncertainty_us"] as? Long ?: 0L) / 1000.0,
                    rttMs = (sendspinStats["rtt_us"] as? Long ?: 0L) / 1000.0,
                    networkQuality = sendspinStats["network_quality"] as? String ?: "UNKNOWN",
                    clockStability = sendspinStats["clock_stability"] as? String ?: "UNKNOWN",
                    clockDriftPpm = sendspinStats["clock_drift_ppm"] as? Double ?: 0.0,
                    audioLatencyMs = (sendspinStats["audio_latency_us"] as? Long ?: 0L) / 1000.0,
                    playoutOffsetMs = (sendspinStats["playout_offset_us"] as? Long ?: 0L) / 1000.0,
                    lateDrops = sendspinStats["late_drops"] as? Long ?: 0L,
                    audibleSyncs = sendspinStats["audible_syncs"] as? Long ?: 0L,
                    kalmanErrorCount = sendspinStats["kalman_error_count"] as? Long ?: 0L,
                    bufferAheadMs = sendspinStats["buffer_ahead_ms"] as? Long ?: 0L,
                    queuedChunks = sendspinStats["queued_chunks"] as? Int ?: 0,
                    chunksReceived = sendspinStats["chunks_received"] as? Long ?: 0L,
                    chunksPlayed = sendspinStats["chunks_played"] as? Long ?: 0L,
                    chunksDropped = sendspinStats["chunks_dropped"] as? Long ?: 0L,
                    bufferUnderrunCount = sendspinStats["buffer_underrun_count"] as? Long ?: 0L,
                    outputDevice = sendspinStats["output_device"] as? String ?: "--",
                    volumePercent = sendspinStats["volume_percent"] as? Int ?: 0,
                    isMuted = sendspinStats["is_muted"] as? Boolean ?: false,
                    playbackSpeed = sendspinStats["playback_speed"] as? Float ?: 1.0f,
                    isConnected = sendspinStats["is_connected"] as? Boolean ?: false,
                    driftUncertaintyPpm = sendspinStats["drift_uncertainty_ppm"] as? Double ?: 0.0,
                    driftSnr = sendspinStats["drift_snr"] as? Double ?: 0.0,
                    connectionDrops = sendspinStats["connection_drops"] as? Int ?: 0,
                    clockReady = sendspinStats["clock_ready"] as? Boolean ?: false,
                    forceResync = sendspinStats["force_resync"] as? Boolean ?: false,
                    audioOutputStarted = sendspinStats["audio_output_started"] as? Boolean ?: false,
                    serverLatenessMs = sendspinStats["server_lateness_ms"] as? Long ?: 0L,
                    networkJitterMs = sendspinStats["network_jitter_ms"] as? Long ?: 0L,
                    clockUpdateCount = sendspinStats["clock_update_count"] as? Int ?: 0,
                    staticDelayMs = sendspinStats["static_delay_ms"] as? Long ?: 0L,
                    estimatedOffsetMs = sendspinStats["estimated_offset_ms"] as? Long ?: 0L
                )
            }
            delay(500)
        }
    }

    val cardBackground = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val borderColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFE2E8F0)
    val titleColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF1E293B)
    val labelColor = getSettingsDescriptionColor()
    val valueColor = if (isDarkMode) Color(0xFFE2E8F0) else Color(0xFF334155)
    val accentColor = getMassChromeAccent()
    val dividerColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFE2E8F0)
    val panelBackground = if (isDarkMode) Color(0xFF161616) else Color(0xFFF8FAFC)
    // Floating vinyl card: all-corner radius (not edge-snapped). Flush sheet: square.
    val panelShape = if (floating && !fillSquare) RoundedCornerShape(28.dp) else RoundedCornerShape(0.dp)
    val cardShape = RoundedCornerShape(
        if (floating && !fillSquare) 28.dp else if (fillSquare) 0.dp else 24.dp,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    splitFill -> Modifier.fillMaxSize()
                    compactSquare -> Modifier.fillMaxHeight()
                    else -> Modifier
                },
            )
            .clip(panelShape)
            .background(panelBackground)
    ) {
        if (showDragHandle) {
            ModalSheetDragHandle(isDarkMode = isDarkMode, onClick = onDismiss)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    when {
                        occupy -> Modifier.weight(1f)
                        floating -> Modifier.heightIn(max = maxSheetHeight)
                        else -> Modifier
                    }
                )
                .nestedScroll(sheetFlingGuard)
                .padding(horizontal = if (isLandscape) 24.dp else 16.dp)
                .padding(bottom = if (isLandscape) 14.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 10.dp else 14.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (occupy) Modifier.fillMaxHeight() else Modifier),
                shape = cardShape,
                color = cardBackground,
                shadowElevation = 0.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
            ) {
                SettingsEdgeFadeScrollColumn(
                    modifier = Modifier
                        .then(if (occupy) Modifier.fillMaxSize() else Modifier)
                        .padding(
                            horizontal = if (isLandscape) 24.dp else 16.dp,
                            vertical = if (isLandscape) 18.dp else 14.dp
                        ),
                    fadeHeight = 18.dp,
                    verticalArrangement = Arrangement.spacedBy(if (isLandscape) 6.dp else 8.dp),
                ) {
                    val context = LocalContext.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sendspin Stats",
                            fontSize = if (isLandscape) 16.sp else 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = titleColor
                        )
                        IconButton(
                            onClick = {
                                val text = formatStatsForCopy(stats)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Sendspin Stats", text))
                                AvaToast.show(context, "Stats copied", tag = "clipboard")
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy stats",
                                tint = labelColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    SectionHeader("Stream", accentColor, isLandscape)
                    StatsRow("Source", stats.streamSource, labelColor, valueColor, isLandscape)
                    StatsRow("Codec", stats.audioCodec, labelColor, valueColor, isLandscape)
                    StatsRow("Sample Rate", if (stats.sampleRate > 0) "${stats.sampleRate} Hz" else "--", labelColor, valueColor, isLandscape)
                    StatsRow("Bit Depth", if (stats.bitDepth > 0) "${stats.bitDepth} bit" else "--", labelColor, valueColor, isLandscape)
                    StatsRow(
                        "Audio",
                        when (stats.channels) {
                            1 -> "Mono"
                            2 -> "Stereo"
                            in 3..8 -> "${stats.channels} channels"
                            else -> "--"
                        },
                        labelColor,
                        valueColor,
                        isLandscape
                    )

                    HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))

                    SectionHeader("Sync", accentColor, isLandscape)
                    StatsRow("Connection", stats.connectionState, labelColor,
                        getConnectionColor(stats.connectionState), isLandscape)
                    StatsRow("Server", stats.serverName, labelColor, valueColor, isLandscape)
                    StatsRow("Network", stats.networkQuality, labelColor,
                        getNetworkQualityColor(stats.networkQuality), isLandscape)
                    StatsRow("Stability", stats.clockStability, labelColor,
                        getClockStabilityColor(stats.clockStability), isLandscape)
                    StatsRow("Sync Uncertainty", "±${String.format("%.2f ms", stats.syncUncertaintyMs)}", labelColor,
                        getSyncUncertaintyColor(stats.syncUncertaintyMs), isLandscape)
                    StatsRow("RTT", "~${String.format("%.2f ms", stats.rttMs)}", labelColor,
                        getRttColor(stats.rttMs), isLandscape)
                    StatsRow("Clock Drift", String.format("%+.3f ppm", stats.clockDriftPpm), labelColor,
                        getClockDriftColor(stats.clockDriftPpm), isLandscape)
                    StatsRow("Drift Uncertainty", String.format("%.3f ppm", stats.driftUncertaintyPpm), labelColor, valueColor, isLandscape)
                    StatsRow("Drift SNR", String.format("%.2f", stats.driftSnr), labelColor,
                        if (stats.driftSnr >= 2.0) ColorGood else if (stats.driftSnr >= 1.0) ColorWarning else valueColor, isLandscape)
                    StatsRow("Jitter", "${stats.networkJitterMs}ms", labelColor, valueColor, isLandscape)
                    StatsRow("Clock Updates", stats.clockUpdateCount.toString(), labelColor, valueColor, isLandscape)
                    StatsRow(
                        "Clock Offset",
                        String.format("%+.2f ms", stats.estimatedOffsetMs.toDouble()),
                        labelColor,
                        valueColor,
                        isLandscape
                    )
                    StatsRow("Static Delay", "${stats.staticDelayMs}ms", labelColor, valueColor, isLandscape)
                    StatsRow("Kalman Errors", stats.kalmanErrorCount.toString(), labelColor,
                        if (stats.kalmanErrorCount > 0) ColorBad else ColorGood, isLandscape)
                    StatsRow("Conn Drops", stats.connectionDrops.toString(), labelColor,
                        if (stats.connectionDrops > 0) ColorWarning else ColorGood, isLandscape)

                    HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))

                    SectionHeader("Buffer", accentColor, isLandscape)
                    StatsRow("Queued", "${stats.queuedChunks} chunks", labelColor,
                        getQueuedChunksColor(stats.queuedChunks), isLandscape)
                    StatsRow("Head Ahead", String.format("%+d ms", stats.bufferAheadMs), labelColor,
                        getBufferAheadColor(stats.bufferAheadMs), isLandscape)
                    StatsRow(
                        "Late Fixes",
                        "${stats.lateDrops} / ${stats.audibleSyncs}",
                        labelColor,
                        valueColor,
                        isLandscape
                    )
                    StatsRow("Received", stats.chunksReceived.toString(), labelColor, valueColor, isLandscape)
                    StatsRow("Played", stats.chunksPlayed.toString(), labelColor, valueColor, isLandscape)
                    StatsRow(
                        "Losses",
                        "${stats.chunksDropped} / ${stats.bufferUnderrunCount}",
                        labelColor,
                        valueColor,
                        isLandscape
                    )

                    HorizontalDivider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))

                    SectionHeader("Output", accentColor, isLandscape)
                    StatsRow("Playback", stats.playbackState, labelColor,
                        getPlaybackColor(stats.playbackState), isLandscape)
                    StatsRow("Audio Output", if (stats.audioOutputStarted) "Started" else "Stopped", labelColor,
                        if (stats.audioOutputStarted) ColorGood else ColorWarning, isLandscape)
                    StatsRow("Clock Ready", if (stats.clockReady) "Yes" else "No", labelColor,
                        if (stats.clockReady) ColorGood else ColorBad, isLandscape)
                    StatsRow("Force Resync", if (stats.forceResync) "Active" else "No", labelColor,
                        if (stats.forceResync) ColorBad else ColorGood, isLandscape)
                    if (stats.serverLatenessMs > 0) {
                        StatsRow("Server Late", "${stats.serverLatenessMs}ms", labelColor, ColorBad, isLandscape)
                    }
                    StatsRow("Audio Latency", String.format("%.1f ms", stats.audioLatencyMs), labelColor,
                        getDelayColor(stats.audioLatencyMs), isLandscape)
                    StatsRow("Playout Offset", String.format("%+.1f ms", stats.playoutOffsetMs), labelColor,
                        valueColor, isLandscape)
                    StatsRow(
                        "Speed",
                        String.format("%.3fx", stats.playbackSpeed),
                        labelColor,
                        valueColor,
                        isLandscape
                    )
                    StatsRow("Output Device", stats.outputDevice, labelColor, valueColor, isLandscape)
                    StatsRow(
                        "Volume",
                        if (stats.isMuted) "${stats.volumePercent}% (Muted)" else "${stats.volumePercent}%",
                        labelColor,
                        valueColor,
                        isLandscape
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, color: Color, isLandscape: Boolean) {
    Text(
        text = title,
        fontSize = if (isLandscape) 13.sp else 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun StatsRow(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color,
    isLandscape: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = if (isLandscape) 13.sp else 12.sp,
            color = labelColor,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = if (isLandscape) 13.sp else 12.sp,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun getConnectionColor(state: String): Color {
    return when {
        state.contains("Connected", ignoreCase = true) -> ColorGood
        state.contains("Server Mode", ignoreCase = true) -> ColorGood
        state.contains("Connecting", ignoreCase = true) -> ColorWarning
        else -> ColorBad
    }
}

private fun getPlaybackColor(state: String): Color {
    val normalized = state.uppercase()
    return when {
        normalized == "PLAYING" -> ColorGood
        normalized == "PAUSED" || normalized.contains("WAITING") || normalized == "DRAINING" -> ColorWarning
        normalized == "INITIALIZING" -> Color(0xFF94A3B8)
        else -> Color(0xFF94A3B8)
    }
}

private fun getSyncUncertaintyColor(errorMs: Double): Color {
    val absError = kotlin.math.abs(errorMs)
    return when {
        absError < 1.0 -> ColorGood
        absError < 5.0 -> ColorWarning
        else -> ColorBad
    }
}

private fun getClockDriftColor(driftPpm: Double): Color {
    val absDrift = kotlin.math.abs(driftPpm)
    return when {
        absDrift < 10.0 -> ColorGood
        absDrift < 50.0 -> ColorWarning
        else -> ColorBad
    }
}

private fun getNetworkQualityColor(quality: String): Color {
    return when (quality.uppercase()) {
        "GOOD" -> ColorGood
        "FAIR" -> ColorWarning
        "POOR" -> ColorBad
        else -> Color(0xFF94A3B8)
    }
}

private fun getClockStabilityColor(stability: String): Color {
    return when (stability.uppercase()) {
        "STABLE" -> ColorGood
        "CONVERGING" -> ColorWarning
        "UNSTABLE" -> ColorBad
        else -> Color(0xFF94A3B8)
    }
}

private fun getRttColor(rttMs: Double): Color {
    return when {
        rttMs < 10.0 -> ColorGood
        rttMs < 50.0 -> ColorWarning
        else -> ColorBad
    }
}

private fun getDelayColor(delayMs: Double): Color {
    return when {
        delayMs <= 50.0 -> ColorGood
        delayMs <= 100.0 -> ColorWarning
        else -> ColorBad
    }
}

private fun getBufferAheadColor(bufferAheadMs: Long): Color {
    return when {
        bufferAheadMs >= 0L -> ColorGood
        bufferAheadMs >= -50L -> ColorWarning
        else -> ColorBad
    }
}

private fun getQueuedChunksColor(chunks: Int): Color {
    return when {
        chunks >= 190 -> ColorGood
        chunks >= 100 -> ColorWarning
        else -> ColorBad
    }
}

private fun getNetworkTypeColor(type: String): Color {
    return when (type.uppercase()) {
        "WIFI", "ETHERNET" -> ColorGood
        "CELLULAR" -> ColorWarning
        else -> Color(0xFF94A3B8)
    }
}

private fun getWifiRssiColor(rssi: Int): Color {
    return when {
        rssi > -50 -> ColorGood
        rssi > -70 -> ColorWarning
        else -> ColorBad
    }
}

private fun formatStatsForCopy(s: SendspinStats): String = buildString {
    appendLine("=== Sendspin Stats ===")
    appendLine("[Stream]")
    appendLine("Source: ${s.streamSource}")
    appendLine("Codec: ${s.audioCodec}")
    appendLine("Format: ${s.sampleRate}Hz / ${s.bitDepth}bit / ${s.channels}ch")
    appendLine()
    appendLine("[Sync]")
    appendLine("Connection: ${s.connectionState}")
    appendLine("Server: ${s.serverName} (${s.serverAddress})")
    appendLine("Network: ${s.networkQuality}")
    appendLine("Stability: ${s.clockStability}")
    appendLine("Uncertainty: ±${String.format("%.2f", s.syncUncertaintyMs)}ms")
    appendLine("RTT: ~${String.format("%.2f", s.rttMs)}ms")
    appendLine("Drift: ${String.format("%+.3f", s.clockDriftPpm)}ppm (±${String.format("%.3f", s.driftUncertaintyPpm)} SNR=${String.format("%.2f", s.driftSnr)})")
    appendLine("Jitter: ${s.networkJitterMs}ms")
    appendLine("Clock Offset: ${String.format("%+.2f", s.estimatedOffsetMs.toDouble())}ms")
    appendLine("Static Delay: ${s.staticDelayMs}ms")
    appendLine("Clock Updates: ${s.clockUpdateCount}")
    appendLine("Kalman Errors: ${s.kalmanErrorCount}")
    appendLine("Conn Drops: ${s.connectionDrops}")
    appendLine()
    appendLine("[Buffer]")
    appendLine("Queued: ${s.queuedChunks} chunks")
    appendLine("Head Ahead: ${String.format("%+d", s.bufferAheadMs)}ms")
    appendLine("Late/Syncs: ${s.lateDrops} / ${s.audibleSyncs}")
    appendLine("Rx/Played: ${s.chunksReceived} / ${s.chunksPlayed}")
    appendLine("Drops/Underruns: ${s.chunksDropped} / ${s.bufferUnderrunCount}")
    appendLine()
    appendLine("[Output]")
    appendLine("Playback: ${s.playbackState}")
    appendLine("Audio Output: ${if (s.audioOutputStarted) "Started" else "Stopped"}")
    appendLine("Clock Ready: ${if (s.clockReady) "Yes" else "No"}")
    appendLine("Force Resync: ${if (s.forceResync) "Active" else "No"}")
    if (s.serverLatenessMs > 0) appendLine("Server Late: ${s.serverLatenessMs}ms")
    appendLine("Latency: ${String.format("%.1f", s.audioLatencyMs)}ms")
    appendLine("Playout Offset: ${String.format("%+.1f", s.playoutOffsetMs)}ms")
    appendLine("Speed: ${String.format("%.3f", s.playbackSpeed)}x")
    appendLine("Device: ${s.outputDevice}")
    appendLine("Volume: ${s.volumePercent}%${if (s.isMuted) " (Muted)" else ""}")
}
