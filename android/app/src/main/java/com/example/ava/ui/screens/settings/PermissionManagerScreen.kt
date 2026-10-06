package com.example.ava.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.permissions.PermissionGrantAction
import com.example.ava.permissions.PermissionGuideCoordinator
import com.example.ava.permissions.PermissionManager
import com.example.ava.permissions.PermissionNeedFlags
import com.example.ava.permissions.PermissionRowState
import com.example.ava.permissions.PermissionUiStatus
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.VoiceChannelSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.voiceChannelSettingsStore
import com.example.ava.ui.AvaToast
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.CollapsibleDescriptionText
import com.example.ava.ui.screens.settings.components.rootSettingsBodyTextSize
import com.example.ava.ui.screens.settings.components.rootSettingsTitleTextSize
import com.example.ava.ui.screens.settings.components.settingsBodyLineHeight
import com.example.ava.ui.screens.settings.components.settingsFocusHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PermissionManagerScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val splitActive = LocalSettingsSplitActive.current
    // Split right pane: one SimpleCard per permission. Off-split landscape keeps two-column tiles.
    val landscape = !splitActive && deviceLandscape
    val lifecycleOwner = LocalLifecycleOwner.current
    val highlightTarget by PermissionGuideCoordinator.highlight.collectAsStateWithLifecycle()

    var refreshTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val voiceStore = remember { VoiceChannelSettingsStore(context.voiceChannelSettingsStore) }
    val voiceEnabled by voiceStore.enabled.collectAsStateWithLifecycle(initialValue = true)
    val playerStore = remember { PlayerSettingsStore(context.playerSettingsStore) }
    val autoRestart by playerStore.enableAutoRestart.collectAsStateWithLifecycle(initialValue = false)
    val experimental = remember { ExperimentalSettingsStore(context) }
    val cameraEnabled by experimental.cameraEnabled.collectAsStateWithLifecycle(initialValue = false)
    val brightnessEnabled by experimental.screenBrightnessEnabled.collectAsStateWithLifecycle(initialValue = false)
    val screenPowerEnabled by experimental.screenPowerControlHaDisplayEnabled
        .collectAsStateWithLifecycle(initialValue = true)

    val flags = remember(
        voiceEnabled,
        autoRestart,
        cameraEnabled,
        brightnessEnabled,
        screenPowerEnabled,
        refreshTick,
    ) {
        PermissionNeedFlags(
            voiceChannelEnabled = voiceEnabled,
            autoRestartEnabled = autoRestart,
            cameraEnabled = cameraEnabled,
            brightnessEnabled = brightnessEnabled,
            screenPowerControlEnabled = screenPowerEnabled,
        )
    }

    val rows = remember(flags, refreshTick) {
        PermissionManager.buildRows(context, flags)
    }

    LaunchedEffect(highlightTarget) {
        val target = highlightTarget ?: return@LaunchedEffect
        delay(3600)
        if (PermissionGuideCoordinator.highlight.value == target) {
            PermissionGuideCoordinator.clear()
        }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshTick++ }

    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshTick++ }

    fun onGrant(row: PermissionRowState) {
        if (row.status == PermissionUiStatus.GRANTED && !row.reenterable) return
        when (row.action) {
            PermissionGrantAction.RUNTIME -> {
                val perms = PermissionManager.runtimePermissionsFor(row.id)
                if (perms.isNotEmpty()) runtimeLauncher.launch(perms)
            }
            PermissionGrantAction.BATTERY_SETTINGS,
            PermissionGrantAction.WRITE_SETTINGS,
            PermissionGrantAction.ACCESSIBILITY_SETTINGS,
            -> {
                if (row.action == PermissionGrantAction.ACCESSIBILITY_SETTINGS &&
                    row.status != PermissionUiStatus.GRANTED
                ) {
                    AvaToast.show(
                        context,
                        R.string.mod_permission_accessibility_hint,
                        durationMs = AvaToast.LONG_MS,
                    )
                }
                PermissionManager.openSpecialSettings(context, row.id)
                refreshTick++
            }
            PermissionGrantAction.DEVICE_ADMIN -> {
                if (row.status == PermissionUiStatus.GRANTED) {
                    PermissionManager.openDeviceAdminUninstall(context)
                } else {
                    val activity = context.findActivity()
                    if (activity != null) {
                        PermissionManager.launchDeviceAdmin(activity, deviceAdminLauncher)
                    } else {
                        PermissionManager.openDeviceAdminUi(context)
                    }
                }
                refreshTick++
            }
            PermissionGrantAction.SECURE_SETTINGS -> {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        PermissionManager.tryGrantSecureSettings(context)
                    }
                    if (!ok) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.settings_permission_system_ui_needs_elevation),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    refreshTick++
                }
            }
            PermissionGrantAction.NONE -> Unit
        }
    }

    val accent = getAccentColor()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val isDark by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)

    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_permission_manager_title),
    ) {
        if (splitActive) {
            items(rows, key = { it.id }) { row ->
                SimpleCard {
                    PermissionItemCard(
                        row = row,
                        accent = accent,
                        isDark = isDark,
                        tiled = false,
                        highlighted = highlightTarget == row.id,
                        onGrant = { onGrant(row) },
                    )
                }
            }
        } else {
            item {
                SimpleCard {
                    if (landscape) {
                        rows.chunked(2).forEachIndexed { index, pair ->
                            if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                pair.forEach { row ->
                                    PermissionItemCard(
                                        row = row,
                                        accent = accent,
                                        isDark = isDark,
                                        tiled = true,
                                        highlighted = highlightTarget == row.id,
                                        onGrant = { onGrant(row) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        rows.forEachIndexed { index, row ->
                            if (index > 0) SettingsDivider()
                            PermissionItemCard(
                                row = row,
                                accent = accent,
                                isDark = isDark,
                                tiled = false,
                                highlighted = highlightTarget == row.id,
                                onGrant = { onGrant(row) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PermissionItemCard(
    row: PermissionRowState,
    accent: Color,
    isDark: Boolean,
    tiled: Boolean,
    highlighted: Boolean,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = row.status == PermissionUiStatus.GRANTED
    val enabled = !done || row.reenterable
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val inkProgress = remember { Animatable(0f) }
    val cardCorner = if (tiled) 20.dp else 12.dp
    val buttonShape = RoundedCornerShape(14.dp)

    LaunchedEffect(highlighted) {
        if (!highlighted) {
            inkProgress.snapTo(0f)
            return@LaunchedEffect
        }
        delay(80)
        bringIntoViewRequester.bringIntoView()
        // Button Ink Pulse: ink expands from Authorize, card rim soft-pulses with it.
        repeat(4) {
            inkProgress.snapTo(0f)
            inkProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            )
        }
    }

    Box(
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            // D-pad target is the whole card, not the button: a granted permission's
            // button is disabled (unfocusable) and used to break remote navigation.
            .settingsFocusHighlight(cornerRadius = cardCorner, horizontalOutset = 0.dp)
            .onKeyEvent { keyEvent ->
                val activate = keyEvent.type == KeyEventType.KeyUp &&
                    (
                        keyEvent.key == Key.DirectionCenter ||
                            keyEvent.key == Key.Enter ||
                            keyEvent.key == Key.NumPadEnter
                        )
                if (activate) {
                    onGrant()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .drawBehind {
                if (!highlighted) return@drawBehind
                val p = inkProgress.value
                if (p <= 0f) return@drawBehind
                val spread = 10.dp.toPx() * p
                val stroke = 2.dp.toPx() + 4.dp.toPx() * (1f - p)
                drawRoundRect(
                    color = accent.copy(alpha = (1f - p) * 0.35f),
                    topLeft = Offset(-spread / 2f, -spread / 2f),
                    size = Size(size.width + spread, size.height + spread),
                    cornerRadius = CornerRadius(cardCorner.toPx() + spread / 2f),
                    style = Stroke(width = stroke),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .then(
                    if (tiled) {
                        Modifier
                            .background(
                                color = if (isDark) Color(0xFF2A2A2A) else Color(0xFFF8FAFC),
                                shape = RoundedCornerShape(20.dp),
                            )
                            .padding(16.dp)
                    } else {
                        Modifier.padding(vertical = 8.dp)
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(row.titleRes),
                    fontSize = rootSettingsTitleTextSize(16f),
                    fontWeight = FontWeight.SemiBold,
                    color = getTitleColor(),
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusBadge(status = row.status)
            }
            CollapsibleDescriptionText(
                text = stringResource(row.whyRes),
                fontSize = rootSettingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
                topPadding = 0.dp,
            )
            OutlinedButton(
                onClick = onGrant,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    // The card handles remote focus/activation; keep the button
                    // touch-only so each permission is a single D-pad stop.
                    .focusProperties { canFocus = false }
                    .clip(buttonShape)
                    .drawWithContent {
                        drawContent()
                        val p = inkProgress.value
                        if (!highlighted || p <= 0f) return@drawWithContent
                        val maxR = size.maxDimension * 0.95f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accent.copy(alpha = (1f - p) * 0.45f),
                                    accent.copy(alpha = (1f - p) * 0.08f),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = maxR * p.coerceAtLeast(0.01f),
                            ),
                            radius = maxR * p,
                            center = center,
                        )
                    },
                shape = buttonShape,
                border = BorderStroke(
                    width = if (highlighted) 2.dp else 1.5.dp,
                    color = if (enabled) {
                        accent
                    } else if (isDark) {
                        Color(0xFF64748B)
                    } else {
                        Color(0xFFCBD5E1)
                    },
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = accent,
                    disabledContentColor = Color(0xFF94A3B8),
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                ),
            ) {
                Text(
                    text = stringResource(row.actionLabelRes),
                    fontSize = rootSettingsTitleTextSize(),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: PermissionUiStatus) {
    val (labelRes, color, bg) = when (status) {
        PermissionUiStatus.GRANTED -> Triple(
            R.string.settings_permission_status_granted,
            Color(0xFF22C55E),
            Color(0xFF22C55E).copy(alpha = 0.12f),
        )
        PermissionUiStatus.MISSING -> Triple(
            R.string.settings_permission_status_missing,
            Color(0xFFEF4444),
            Color(0xFFEF4444).copy(alpha = 0.12f),
        )
        PermissionUiStatus.UNUSED -> Triple(
            R.string.settings_permission_status_unused,
            getSettingsDescriptionColor(),
            getSettingsDescriptionColor().copy(alpha = 0.14f),
        )
    }
    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape),
        )
        Text(
            text = stringResource(labelRes),
            fontSize = rootSettingsBodyTextSize(11f),
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
