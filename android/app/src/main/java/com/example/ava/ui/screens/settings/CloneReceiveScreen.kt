package com.example.ava.ui.screens.settings

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.backup.AvaBackupIncludeOptions
import com.example.ava.backup.AvaBackupManager
import com.example.ava.backup.AvaCloneBadCodeException
import com.example.ava.backup.AvaCloneClient
import com.example.ava.backup.AvaCloneLockedException
import com.example.ava.backup.AvaCloneServer
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.*
import com.example.ava.voice.AvaVoiceDevice
import com.example.ava.voice.AvaVoiceDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal sealed class CloneReceiveDialog {
    data object Success : CloneReceiveDialog()
    data class Error(val titleRes: Int, val messageRes: Int) : CloneReceiveDialog()
}

internal class CloneReceiveSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSuccess: () -> Unit,
) {
    var selectedKey by mutableStateOf<String?>(null)
    var manualAddress by mutableStateOf("")
    var manualExpanded by mutableStateOf(false)
    var code by mutableStateOf("")
    var fetching by mutableStateOf(false)
    var progressPercent by mutableIntStateOf(0)
    var payload by mutableStateOf<ByteArray?>(null)
    var senderLabel by mutableStateOf("")
    var applyOptions by mutableStateOf(AvaBackupIncludeOptions(includeStores = emptySet(), includeMods = false))
    var available by mutableStateOf<AvaBackupIncludeOptions?>(null)
    var catalog by mutableStateOf<AvaBackupIncludeOptions?>(null)
    var applying by mutableStateOf(false)
    var dialog by mutableStateOf<CloneReceiveDialog?>(null)
    var windowExpired by mutableStateOf(false)
    var confirmDeadlineElapsed by mutableLongStateOf(0L)
    var screenOpenedAt by mutableLongStateOf(SystemClock.elapsedRealtime())
    var nowElapsed by mutableLongStateOf(screenOpenedAt)
    var cloneDevices by mutableStateOf<List<AvaVoiceDevice>>(emptyList())
    var codeError by mutableStateOf(false)
    var codeShakeTick by mutableIntStateOf(0)
    var wrongCodeAttempts by mutableIntStateOf(0)

    val confirmStep: Boolean get() = payload != null && available != null

    val selectedDevice: AvaVoiceDevice?
        get() = cloneDevices.firstOrNull { it.stableKey == selectedKey }

    val manualTarget: Pair<String, Int>?
        get() = parseCloneManualAddress(manualAddress)

    val target: Pair<String, Int>?
        get() {
            val manual = manualTarget
            if (manual != null) return manual
            val device = selectedDevice ?: return null
            return device.host to (device.clonePort ?: 0)
        }

    val remainingMs: Long
        get() {
            val localRemainMs = (AvaCloneServer.WINDOW_MS - (nowElapsed - screenOpenedAt)).coerceAtLeast(0L)
            val deviceRemainMs = selectedDevice?.cloneRemainingMs()
            val confirmRemainMs = if (confirmDeadlineElapsed > 0L) {
                (confirmDeadlineElapsed - nowElapsed).coerceAtLeast(0L)
            } else {
                AvaCloneServer.WINDOW_MS
            }
            return when {
                windowExpired -> 0L
                confirmStep -> confirmRemainMs
                deviceRemainMs != null -> deviceRemainMs
                else -> localRemainMs
            }
        }

    fun reset() {
        selectedKey = null
        manualAddress = ""
        manualExpanded = false
        code = ""
        fetching = false
        progressPercent = 0
        payload = null
        senderLabel = ""
        applyOptions = AvaBackupIncludeOptions(includeStores = emptySet(), includeMods = false)
        available = null
        catalog = null
        applying = false
        dialog = null
        windowExpired = false
        confirmDeadlineElapsed = 0L
        screenOpenedAt = SystemClock.elapsedRealtime()
        nowElapsed = screenOpenedAt
        codeError = false
        codeShakeTick = 0
        wrongCodeAttempts = 0
    }

    fun startFetch() {
        if (windowExpired || remainingMs <= 0L) return
        val (host, port) = target ?: return
        senderLabel = selectedDevice?.name ?: manualAddress.trim()
        fetching = true
        progressPercent = 0
        scope.launch {
            val fetched = AvaCloneClient.fetch(host, port, code) { received, total ->
                progressPercent = if (total > 0) ((received * 100) / total).toInt() else 0
            }
            fetching = false
            fetched.onSuccess { bytes ->
                val peek = AvaBackupManager.peekCloneIncludes(bytes).getOrNull()
                if (peek == null) {
                    dialog = CloneReceiveDialog.Error(
                        R.string.settings_backup_clone_receive_failed_title,
                        R.string.settings_backup_clone_error_connect,
                    )
                    return@launch
                }
                applyOptions = peek.selected
                available = peek.selected
                catalog = peek.catalog
                payload = bytes
                confirmDeadlineElapsed = SystemClock.elapsedRealtime() + AvaCloneServer.WINDOW_MS
            }.onFailure { error ->
                if (error is AvaCloneBadCodeException) {
                    codeError = true
                    codeShakeTick += 1
                    wrongCodeAttempts += 1
                    if (wrongCodeAttempts >= PAIRING_CODE_CLEAR_AFTER_WRONG) {
                        val snapshot = code
                        delay(PAIRING_CODE_SHAKE_MS.toLong())
                        if (code == snapshot) {
                            code = ""
                            wrongCodeAttempts = 0
                        }
                    }
                    return@launch
                }
                dialog = CloneReceiveDialog.Error(
                    R.string.settings_backup_clone_receive_failed_title,
                    when (error) {
                        is AvaCloneLockedException -> R.string.settings_backup_clone_error_locked
                        else -> R.string.settings_backup_clone_error_connect
                    },
                )
            }
        }
    }

    fun startApply() {
        if (windowExpired || remainingMs <= 0L) return
        val bytes = payload ?: return
        applying = true
        scope.launch {
            val result = AvaBackupManager.importFromCloneBytes(context, bytes, applyOptions)
            applying = false
            if (result.success) {
                if (result.needsSatelliteRestart) {
                    restartVoiceSatelliteServiceIfRunning(
                        com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE,
                    )
                }
                dialog = CloneReceiveDialog.Success
            } else {
                dialog = CloneReceiveDialog.Error(
                    R.string.settings_backup_clone_apply_failed,
                    R.string.settings_backup_clone_snapshot_note,
                )
            }
        }
    }

    fun onSuccessConsumed() {
        dialog = null
        onSuccess()
    }
}

@Composable
internal fun rememberCloneReceiveSession(
    active: Boolean = true,
    onSuccess: () -> Unit,
): CloneReceiveSession {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onSuccessState = rememberUpdatedState(onSuccess)
    val session = remember {
        CloneReceiveSession(context, scope) { onSuccessState.value() }
    }
    val devices by AvaVoiceDiscovery.devices.collectAsState()
    session.cloneDevices = devices.filter { (it.clonePort ?: 0) > 0 }

    LaunchedEffect(active) {
        if (!active) {
            session.reset()
            return@LaunchedEffect
        }
        session.reset()
        AvaVoiceDiscovery.acquire(context, AvaVoiceDiscovery.HOLDER_CLONE)
        try {
            while (true) {
                AvaVoiceDiscovery.pokeLanDiscovery()
                delay(5_000)
            }
        } finally {
            AvaVoiceDiscovery.release(AvaVoiceDiscovery.HOLDER_CLONE)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            session.nowElapsed = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    LaunchedEffect(session.cloneDevices) {
        if (session.selectedKey == null && session.manualAddress.isBlank() && session.cloneDevices.size == 1) {
            session.selectedKey = session.cloneDevices.first().stableKey
        }
    }
    LaunchedEffect(
        session.remainingMs,
        session.fetching,
        session.applying,
        session.confirmStep,
        session.windowExpired,
    ) {
        if (session.remainingMs > 0L || session.fetching || session.applying || session.windowExpired) {
            return@LaunchedEffect
        }
        if (session.confirmStep) {
            session.payload = null
            session.available = null
            session.catalog = null
            session.progressPercent = 0
            session.confirmDeadlineElapsed = 0L
            session.dialog = CloneReceiveDialog.Error(
                R.string.settings_backup_clone_receive_expired,
                R.string.settings_backup_clone_receive_expired_hint,
            )
        }
        session.windowExpired = true
    }
    val deviceRemainMs = session.selectedDevice?.cloneRemainingMs()
    LaunchedEffect(deviceRemainMs, session.windowExpired, session.confirmStep) {
        if (session.windowExpired && !session.confirmStep && deviceRemainMs != null && deviceRemainMs > 0L) {
            session.windowExpired = false
        }
    }
    return session
}

@Composable
internal fun CloneReceiveDiscoverContent(session: CloneReceiveSession) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 168.dp),
    ) {
    Row(
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_backup_clone_step_pick),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = getSettingsDescriptionColor(),
        )
    }
    if (session.cloneDevices.isEmpty()) {
        CloneSearchingHint()
    } else {
        session.cloneDevices.forEachIndexed { index, device ->
            if (index > 0) SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .settingsClickable(
                        onClick = {
                            session.selectedKey = device.stableKey
                            session.manualAddress = ""
                        },
                    )
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = device.stableKey == session.selectedKey && session.manualTarget == null,
                    onClick = {
                        session.selectedKey = device.stableKey
                        session.manualAddress = ""
                    },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = getAccentColor(),
                        unselectedColor = Color(0xFF94A3B8),
                    ),
                )
                Column {
                    Text(
                        text = device.identityLabel(),
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = getLabelColor(),
                    )
                    Text(
                        text = buildString {
                            append(device.host)
                            device.cloneRemainingMs()?.let { remain ->
                                append(" · ")
                                append(
                                    context.getString(
                                        R.string.settings_backup_clone_expires_in,
                                        formatCloneCountdown(remain),
                                    ),
                                )
                            }
                        },
                        fontSize = 11.sp,
                        color = getSettingsDescriptionColor(),
                    )
                }
            }
        }
    }
    SettingsDivider()
    val chevronRotation by animateFloatAsState(
        targetValue = if (session.manualExpanded) 180f else 0f,
        label = "manualChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsClickable(
                onClick = {
                    session.manualExpanded = !session.manualExpanded
                    if (!session.manualExpanded) session.manualAddress = ""
                },
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Keyboard,
            contentDescription = null,
            tint = getSettingsDescriptionColor(),
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.settings_backup_clone_manual_entry),
            fontSize = settingsBodyTextSize(),
            fontWeight = FontWeight.Medium,
            color = getLabelColor(),
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = getSettingsDescriptionColor(),
            modifier = Modifier
                .size(20.dp)
                .rotate(chevronRotation),
        )
    }
    AnimatedVisibility(
        visible = session.manualExpanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val isDarkMode = isDarkModeEnabled()
        OutlinedTextField(
            value = session.manualAddress,
            onValueChange = {
                session.manualAddress = it
                if (it.isNotBlank()) session.selectedKey = null
            },
            label = { Text(stringResource(R.string.settings_backup_clone_manual_entry)) },
            placeholder = { Text("192.168.1.23:40123") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = cloneReceiveFieldColors(isDarkMode),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = !session.fetching && !session.windowExpired,
        )
    }
    }
}

@Composable
private fun CloneSearchingHint() {
    val pulse = rememberInfiniteTransition(label = "clone_searching")
    val alpha by pulse.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "clone_searching_alpha",
    )
    SettingsHelpBodyText(
        text = stringResource(R.string.settings_backup_clone_nearby_empty),
        modifier = Modifier
            .padding(vertical = 16.dp)
            .alpha(alpha),
    )
}

@Composable
internal fun CloneReceiveCodeContent(session: CloneReceiveSession) {
    Text(
        text = stringResource(R.string.settings_backup_clone_step_code),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = getSettingsDescriptionColor(),
    )
    Spacer(modifier = Modifier.height(14.dp))
    PairingCodeInput(
        value = session.code,
        onValueChange = {
            session.code = it
            session.codeError = false
        },
        enabled = !session.fetching && !session.windowExpired,
        error = session.codeError,
        errorTick = session.codeShakeTick,
        modifier = Modifier.fillMaxWidth(),
    )
    AnimatedVisibility(
        visible = session.codeError,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Text(
            text = stringResource(R.string.settings_backup_clone_error_bad_code),
            fontSize = 12.sp,
            color = Color(0xFFE57373),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
    if (session.windowExpired) {
        BackupActionRow(
            title = stringResource(R.string.settings_backup_clone_receive_expired),
            subtitle = stringResource(R.string.settings_backup_clone_receive_expired_hint),
            icon = Icons.Rounded.Schedule,
            enabled = false,
            showChevron = false,
            onClick = {},
        )
    } else {
        Text(
            text = stringResource(
                R.string.settings_backup_clone_expires_in,
                formatCloneCountdown(session.remainingMs),
            ),
            fontSize = 12.sp,
            color = getSettingsDescriptionColor(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        CloneReceiveStartButton(
            enabled = !session.fetching && session.code.length == 6 && session.target != null,
            armed = session.code.length == 6,
            fetching = session.fetching,
            progressPercent = session.progressPercent,
            onClick = { session.startFetch() },
        )
    }
}

@Composable
private fun CloneReceiveStartButton(
    enabled: Boolean,
    armed: Boolean,
    fetching: Boolean,
    progressPercent: Int,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val muted = getSettingsDescriptionColor().copy(alpha = 0.38f)
    val live = getAccentColor()
    val stroke by animateColorAsState(
        targetValue = if (armed || fetching) live else muted,
        animationSpec = tween(220),
        label = "recv_start_stroke",
    )
    val label by animateColorAsState(
        targetValue = if (armed || fetching) live else muted,
        animationSpec = tween(220),
        label = "recv_start_label",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .border(1.5.dp, stroke, shape)
            .then(
                if (enabled) {
                    Modifier.settingsClickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (fetching) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = live,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        R.string.settings_backup_clone_receiving,
                        progressPercent,
                    ),
                    fontSize = settingsTitleTextSize(),
                    fontWeight = FontWeight.SemiBold,
                    color = label,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.settings_backup_clone_receive_button),
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.SemiBold,
                color = label,
            )
        }
    }
}

@Composable
internal fun CloneReceiveConfirmContent(session: CloneReceiveSession) {
    val expired = session.windowExpired || session.remainingMs <= 0L
    val accent = getAccentColor()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(1.5.dp, accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.settings_backup_clone_received_from,
                    session.senderLabel,
                ),
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
                color = getLabelColor(),
            )
            Text(
                text = if (expired) {
                    stringResource(R.string.settings_backup_clone_receive_expired)
                } else {
                    stringResource(
                        R.string.settings_backup_clone_received_hint,
                        formatCloneCountdown(session.remainingMs),
                    )
                },
                fontSize = settingsCaptionTextSize(12f),
                color = getSettingsDescriptionColor(),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
internal fun CloneReceiveApplyDock(
    session: CloneReceiveSession,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val pageBg = if (isDarkMode) Color.Black else PureWhiteBackground
    AnimatedVisibility(
        visible = session.confirmStep,
        modifier = modifier,
        enter = slideInVertically(
            animationSpec = spring(
                dampingRatio = 0.86f,
                stiffness = Spring.StiffnessMediumLow,
            ),
            initialOffsetY = { it },
        ) + fadeIn(
            animationSpec = tween(durationMillis = 280, delayMillis = 70, easing = FastOutSlowInEasing),
        ),
        exit = slideOutVertically(
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            targetOffsetY = { it },
        ) + fadeOut(tween(160)),
    ) {
        SettingsBottomDockCapsule(
            pageBg = pageBg,
            isDarkMode = isDarkMode,
            primaryLabel = if (session.applying) {
                stringResource(R.string.settings_backup_clone_applying)
            } else {
                stringResource(R.string.settings_backup_clone_apply_short)
            },
            onPrimary = { session.startApply() },
            primaryEnabled = !session.applying &&
                !session.windowExpired &&
                session.remainingMs > 0L &&
                session.applyOptions.hasAnyInclude(),
        )
    }
}

@Composable
internal fun CloneReceiveDialogs(session: CloneReceiveSession) {
    when (val current = session.dialog) {
        is CloneReceiveDialog.Success -> {
            AlertDialog(
                onDismissRequest = { },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = getAccentColor(),
                        modifier = Modifier.size(36.dp),
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.settings_backup_clone_apply_success),
                        fontWeight = FontWeight.Bold,
                        fontSize = settingsTitleTextSize(),
                        color = getTitleColor(),
                    )
                },
                text = {
                    SettingsHelpBodyText(
                        text = stringResource(R.string.settings_backup_clone_post_apply_hint),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { session.onSuccessConsumed() }) {
                        Text(
                            text = stringResource(R.string.confirm),
                            color = getAccentColor(),
                        )
                    }
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = getDialogBackground(),
            )
        }
        is CloneReceiveDialog.Error -> {
            AlertDialog(
                onDismissRequest = { session.dialog = null },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFE57373),
                        modifier = Modifier.size(32.dp),
                    )
                },
                title = {
                    Text(
                        text = stringResource(current.titleRes),
                        fontWeight = FontWeight.Bold,
                        fontSize = settingsTitleTextSize(),
                        color = getTitleColor(),
                    )
                },
                text = {
                    SettingsHelpBodyText(
                        text = stringResource(current.messageRes),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { session.dialog = null }) {
                        Text(
                            text = stringResource(R.string.confirm),
                            color = getAccentColor(),
                        )
                    }
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = getDialogBackground(),
            )
        }
        null -> Unit
    }
}

@Composable
private fun cloneReceiveFieldColors(isDarkMode: Boolean) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = getAccentColor(),
    unfocusedBorderColor = if (isDarkMode) Color(0xFF3D3D3D) else Color(0xFFE2E8F0),
    focusedContainerColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFF8FAFC),
    unfocusedContainerColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFF8FAFC),
    cursorColor = getAccentColor(),
    focusedTextColor = getLabelColor(),
    unfocusedTextColor = getLabelColor(),
    focusedLabelColor = getAccentColor(),
    unfocusedLabelColor = getSettingsDescriptionColor(),
)

@Composable
fun CloneReceiveScreen(navController: NavController) {
    val session = rememberCloneReceiveSession(onSuccess = { navController.popBackStack() })
    val dockClearance by animateDpAsState(
        targetValue = if (session.confirmStep) SettingsBottomDockClearance else 0.dp,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "clone_receive_dock_clearance",
    )
    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_backup_clone_receive_title),
        listExtraBottom = dockClearance,
        bottomOverlay = {
            CloneReceiveApplyDock(
                session = session,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        },
    ) {
        if (!session.confirmStep) {
            item {
                SimpleCard {
                    CloneReceiveDiscoverContent(session)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
            item {
                SimpleCard {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(modifier = rememberBackupActionContentWidthModifier()) {
                            CloneReceiveCodeContent(session)
                        }
                    }
                }
            }
        } else {
            item {
                SimpleCard {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(modifier = rememberBackupActionContentWidthModifier()) {
                            CloneReceiveConfirmContent(session)
                        }
                    }
                }
            }
            item {
                BackupIncludePicker(
                    options = session.applyOptions,
                    onChange = { session.applyOptions = it },
                    available = session.available,
                    catalog = session.catalog,
                    enabled = !session.applying,
                    titleRes = R.string.settings_backup_clone_apply_includes_title,
                )
            }
        }
    }
    CloneReceiveDialogs(session)
}

internal fun parseCloneManualAddress(raw: String): Pair<String, Int>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val colon = trimmed.lastIndexOf(':')
    if (colon <= 0 || colon == trimmed.length - 1) return null
    val host = trimmed.substring(0, colon)
    val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
    if (port !in 1..65535 || host.isBlank()) return null
    return host to port
}
