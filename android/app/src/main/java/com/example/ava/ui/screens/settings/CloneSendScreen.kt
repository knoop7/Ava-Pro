package com.example.ava.ui.screens.settings

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.backup.AvaBackupIncludeOptions
import com.example.ava.backup.AvaBackupManager
import com.example.ava.backup.AvaCloneServer
import com.example.ava.backup.AvaCloneServerState
import com.example.ava.ui.screens.settings.components.*
import com.example.ava.voice.AvaVoiceDiscovery
import kotlinx.coroutines.delay

/**
 * Clone send window: exports this device's configuration, opens the one-shot TCP
 * server ([AvaCloneServer]) and advertises its port on the Ava presence beacon so a
 * receiver can find it. Torn down when [onClose] runs or this leaves composition.
 */
@Composable
internal fun CloneSendPanel(
    options: AvaBackupIncludeOptions,
    catalog: AvaBackupIncludeOptions? = AvaBackupManager.cloneSendCatalog,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    var server by remember { mutableStateOf<AvaCloneServer?>(null) }
    var port by remember { mutableStateOf(0) }
    var startFailed by remember { mutableStateOf(false) }
    var serverState by remember { mutableStateOf<AvaCloneServerState>(AvaCloneServerState.Waiting) }
    var remainingMs by remember { mutableLongStateOf(AvaCloneServer.WINDOW_MS) }

    LaunchedEffect(options, catalog) {
        val payload = AvaBackupManager.exportForClone(context, options, catalog).getOrNull()
        if (payload == null) {
            startFailed = true
            return@LaunchedEffect
        }
        val candidate = AvaCloneServer(payload)
        val boundPort = candidate.start()
        if (boundPort == null) {
            startFailed = true
            return@LaunchedEffect
        }
        AvaVoiceDiscovery.acquire(context, AvaVoiceDiscovery.HOLDER_CLONE)
        AvaVoiceDiscovery.setAdvertisedClonePort(boundPort)
        AvaVoiceDiscovery.setAdvertisedCloneDeadlineElapsed(
            SystemClock.elapsedRealtime() + AvaCloneServer.WINDOW_MS,
        )
        AvaVoiceDiscovery.refreshBeaconNow()
        port = boundPort
        server = candidate
    }

    LaunchedEffect(server) {
        val active = server ?: return@LaunchedEffect
        active.state.collect { state ->
            serverState = state
            if (state != AvaCloneServerState.Waiting && state != AvaCloneServerState.Transferring) {
                AvaVoiceDiscovery.setAdvertisedClonePort(0)
                AvaVoiceDiscovery.refreshBeaconNow()
            }
        }
    }

    LaunchedEffect(server) {
        if (server == null) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val remaining = AvaCloneServer.WINDOW_MS - (SystemClock.elapsedRealtime() - startedAt)
            remainingMs = remaining.coerceAtLeast(0L)
            if (remaining <= 0L) break
            delay(1_000)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            AvaVoiceDiscovery.setAdvertisedClonePort(0)
            AvaVoiceDiscovery.refreshBeaconNow()
            AvaVoiceDiscovery.release(AvaVoiceDiscovery.HOLDER_CLONE)
            server?.stop()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            startFailed -> {
                CloneSendStatus(
                    icon = Icons.Rounded.ErrorOutline,
                    tint = Color(0xFFE57373),
                    title = stringResource(R.string.settings_backup_clone_start_failed),
                )
            }
            serverState == AvaCloneServerState.Done -> {
                CloneSendStatus(
                    icon = Icons.Rounded.CheckCircle,
                    tint = getAccentColor(),
                    title = stringResource(R.string.settings_backup_clone_sent),
                    subtitle = stringResource(R.string.settings_backup_clone_sent_hint),
                )
            }
            serverState == AvaCloneServerState.Locked -> {
                CloneSendStatus(
                    icon = Icons.Rounded.Lock,
                    tint = Color(0xFFE57373),
                    title = stringResource(R.string.settings_backup_clone_locked),
                )
            }
            serverState == AvaCloneServerState.Stopped -> {
                CloneSendStatus(
                    icon = Icons.Rounded.Schedule,
                    tint = getSettingsDescriptionColor(),
                    title = stringResource(R.string.settings_backup_clone_window_closed),
                )
            }
            else -> {
                Text(
                    text = if (serverState == AvaCloneServerState.Transferring) {
                        stringResource(R.string.settings_backup_clone_transferring)
                    } else {
                        stringResource(R.string.settings_backup_clone_waiting)
                    },
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val code = server?.pairingCode.orEmpty()
                    if (code.length == 6) {
                        PairingCodeBoxes(
                            code = code,
                            digitColor = getAccentColor(),
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                            color = getAccentColor(),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.settings_backup_clone_expires_in,
                        formatCloneCountdown(remainingMs),
                    ),
                    fontSize = 12.sp,
                    color = getSettingsDescriptionColor(),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = if (port > 0) {
                        stringResource(
                            R.string.settings_backup_clone_manual_hint,
                            "${AvaVoiceDiscovery.localHost()}:$port",
                        )
                    } else {
                        " "
                    },
                    fontSize = 11.sp,
                    color = getSettingsDescriptionColor(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun CloneSendScreen(navController: NavController) {
    val sendOptions = remember { AvaBackupManager.cloneSendOptions }
    val sendCatalog = remember { AvaBackupManager.cloneSendCatalog }
    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_backup_clone_send_title),
    ) {
        item {
            SimpleCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(modifier = rememberBackupActionContentWidthModifier()) {
                        CloneSendPanel(
                            options = sendOptions,
                            catalog = sendCatalog,
                            onClose = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
        item {
            BackupIncludePicker(
                options = sendOptions,
                onChange = null,
                available = sendOptions,
                catalog = sendCatalog,
                showIdentityNote = true,
                showTransferNote = true,
            )
        }
    }
}

/** Terminal-state hero: tinted circular badge with a vector icon, title, optional hint. */
@Composable
private fun CloneSendStatus(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String? = null,
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(32.dp),
        )
    }
    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = title,
        fontSize = settingsTitleTextSize(),
        fontWeight = FontWeight.SemiBold,
        color = getLabelColor(),
        textAlign = TextAlign.Center,
        lineHeight = 20.sp,
    )
    if (subtitle != null) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            fontSize = 12.sp,
            color = getSettingsDescriptionColor(),
            textAlign = TextAlign.Center,
            lineHeight = settingsBodyLineHeight(),
        )
    }
}

internal fun formatCloneCountdown(remainingMs: Long): String {
    val totalSeconds = (remainingMs / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
