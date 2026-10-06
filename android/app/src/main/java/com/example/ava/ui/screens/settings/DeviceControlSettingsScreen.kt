package com.example.ava.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.ui.AvaToast
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.settingsClickable
import com.example.ava.ui.screens.settings.components.settingsListHorizontalPadding
import com.example.ava.ui.screens.settings.components.settingsListVerticalPadding
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.AccentCyan
import com.example.ava.ui.theme.AccentGreen
import com.example.ava.ui.theme.AccentRed
import com.example.ava.utils.AvaProcessControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DeviceControlAction { RESTART, EXIT, PIN }

private const val CONFIRM_TAPS = 3
private const val TAP_WINDOW_MS = 2500L
private const val TAP_TOAST_TAG = "device-control-taps"

/**
 * Replaces the confirm dialogs: an action only fires on the third tap within
 * [TAP_WINDOW_MS]. Progress is echoed as the tile's own label plus an "n / 3"
 * counter, so it reads as a sentence in every language without new copy.
 * Tapping a different tile restarts the count.
 */
private class DeviceControlTapGate {
    private var armed: DeviceControlAction? = null
    private var count = 0
    private var deadline = 0L

    fun tap(context: Context, action: DeviceControlAction, label: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        count = if (action == armed && now < deadline) count + 1 else 1
        armed = action
        deadline = now + TAP_WINDOW_MS
        if (count >= CONFIRM_TAPS) {
            reset()
            return true
        }
        AvaToast.show(context, "$label · $count / $CONFIRM_TAPS", TAP_TOAST_TAG, TAP_WINDOW_MS)
        return false
    }

    fun reset() {
        armed = null
        count = 0
        deadline = 0L
    }
}

@Composable
fun DeviceControlSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val dark by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    // Device orientation only: landscape split from Settings keeps the three tiles in a row.
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var tier by remember { mutableStateOf(AvaProcessControl.Tier.NONE) }
    var pinned by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    val taps = remember { DeviceControlTapGate() }

    LaunchedEffect(refreshKey) {
        tier = withContext(Dispatchers.IO) { AvaProcessControl.currentTier(context) }
        pinned = AvaProcessControl.isPinned(context)
    }

    val canForceStop = tier == AvaProcessControl.Tier.ROOT || tier == AvaProcessControl.Tier.SHIZUKU

    val restartTitle = stringResource(R.string.settings_device_control_restart)
    val killTitle = stringResource(R.string.settings_device_control_kill)
    val pinLabel = stringResource(R.string.settings_device_control_pin)
    val unpinLabel = stringResource(R.string.settings_device_control_unpin)
    val pinTitle = if (pinned) unpinLabel else pinLabel

    // Locked reads as a firmer green tile with a padlock; unlocked stays the quiet
    // cyan pin. Hue and weight cross-fade together so the state change is visible
    // even though nothing else on the screen moves.
    val pinAccent by animateColorAsState(
        targetValue = if (pinned) AccentGreen else AccentCyan,
        animationSpec = tween(durationMillis = 260),
        label = "pinAccent",
    )
    val pinEmphasis by animateFloatAsState(
        targetValue = if (pinned) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "pinEmphasis",
    )

    val onRestart: () -> Unit = {
        if (taps.tap(context, DeviceControlAction.RESTART, restartTitle)) {
            AvaProcessControl.restartAva(context, reason = "device_control")
        }
    }
    val onKill: () -> Unit = {
        if (taps.tap(context, DeviceControlAction.EXIT, killTitle)) {
            AvaProcessControl.exitAva(context, reason = "device_control")
        }
    }
    val onPin: () -> Unit = onPin@{
        val activity = context.findActivity()
        if (activity == null) {
            taps.reset()
            AvaToast.show(context, context.getString(R.string.settings_device_control_pin_failed))
            return@onPin
        }
        // Unpinning is the way back out of the wall, so it stays a single tap.
        if (pinned) {
            taps.reset()
            AvaProcessControl.unpinFromWall(activity)
            AvaToast.show(context, unpinLabel, TAP_TOAST_TAG)
            refreshKey++
            return@onPin
        }
        if (!taps.tap(context, DeviceControlAction.PIN, pinLabel)) return@onPin
        scope.launch {
            if (canForceStop) {
                withContext(Dispatchers.IO) { AvaProcessControl.tryBecomeDeviceOwner(context) }
            }
            val ok = AvaProcessControl.pinToWall(activity)
            AvaToast.show(
                context,
                if (ok) pinLabel else context.getString(R.string.settings_device_control_pin_failed),
                TAP_TOAST_TAG,
            )
            refreshKey++
        }
    }

    val listPadH = settingsListHorizontalPadding()
    val listPadV = settingsListVerticalPadding()
    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_device_control_title),
        contentPadding = PaddingValues(horizontal = listPadH, vertical = 0.dp),
        userScrollEnabled = false,
    ) {
        item {
            val gap = if (landscape) 14.dp else 12.dp
            val fillViewport = Modifier
                .fillParentMaxHeight()
                .fillMaxWidth()
                .padding(vertical = listPadV)
            val restart = @Composable { modifier: Modifier ->
                DeviceControlTile(
                    title = restartTitle,
                    icon = Icons.Outlined.Refresh,
                    accent = AccentBlue,
                    dark = dark,
                    onClick = onRestart,
                    modifier = modifier,
                )
            }
            val kill = @Composable { modifier: Modifier ->
                DeviceControlTile(
                    title = killTitle,
                    icon = Icons.Outlined.PowerSettingsNew,
                    accent = AccentRed,
                    dark = dark,
                    onClick = onKill,
                    modifier = modifier,
                )
            }
            val pin = @Composable { modifier: Modifier ->
                DeviceControlTile(
                    title = pinTitle,
                    icon = if (pinned) Icons.Outlined.Lock else Icons.Outlined.PushPin,
                    accent = pinAccent,
                    dark = dark,
                    onClick = onPin,
                    modifier = modifier,
                    emphasis = pinEmphasis,
                )
            }

            if (landscape) {
                Row(
                    modifier = fillViewport,
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    restart(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    kill(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    pin(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                Column(
                    modifier = fillViewport,
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    restart(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    kill(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    pin(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }

}

/**
 * Tile colours for one accent. Both themes aim for the same weight — a translucent
 * tint plus a hairline edge, never a solid slab.
 *
 * Dark mode lifts the accent toward white before applying alpha: the raw accents are
 * dark to begin with (AccentBlue is near navy), so a low-alpha wash straight over the
 * black page sinks in and the tile reads as a hole instead of a surface.
 *
 * [emphasis] (0..1) firms up fill and edge for an engaged state, e.g. pinned to wall.
 */
private data class TilePalette(val badge: Color, val wash: Color, val edge: Color)

private fun tilePalette(accent: Color, dark: Boolean, emphasis: Float): TilePalette {
    val boost = 1f + 0.45f * emphasis
    return if (dark) {
        val lifted = lerp(accent, Color.White, 0.58f)
        TilePalette(
            badge = lerp(accent, Color.White, 0.16f),
            wash = lifted.copy(alpha = 0.15f * boost),
            edge = lifted.copy(alpha = 0.22f * boost),
        )
    } else {
        TilePalette(
            badge = accent,
            wash = accent.copy(alpha = 0.085f * boost),
            edge = accent.copy(alpha = 0.16f * boost),
        )
    }
}

@Composable
private fun DeviceControlTile(
    title: String,
    icon: ImageVector,
    accent: Color,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: Float = 0f,
) {
    val palette = tilePalette(accent, dark, emphasis)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val baseIcon = if (landscape) 72.dp else 66.dp
    val baseGlyph = if (landscape) 32.dp else 28.dp
    val basePad = 16.dp
    val baseGap = if (landscape) 14.dp else 12.dp
    val baseTitleSp = if (landscape) 18.5f else 17.5f
    val glyphFraction = baseGlyph.value / baseIcon.value
    Surface(
        modifier = modifier.border(1.dp, palette.edge, RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        color = palette.wash,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .settingsClickable(onClick = onClick),
        ) {
            // Both orientations scale the whole cluster (icon + gap + label)
            // together so nothing looks top-heavy. The scale is derived from
            // the tile's shorter edge; overflow pulls everything back.
            val minIcon = 20.dp
            val minPad = 6.dp
            val minGap = 4.dp
            val padH = 12.dp
            val tileMin = minOf(maxWidth, maxHeight)
            val localScale = (tileMin.value / (baseIcon.value * 2.2f))
                .coerceIn(1f, 1.32f)
            val titleSize = settingsTitleTextSize(baseTitleSp * localScale)
            val textReserve = with(LocalDensity.current) { titleSize.toDp() } * 1.35f
            val desiredIcon = (baseIcon.value * localScale).dp
            val roomW = (maxWidth - padH * 2).coerceAtLeast(0.dp)
            fun leftover(pad: Dp, gap: Dp) =
                (maxHeight - pad * 2 - gap - textReserve).coerceAtLeast(0.dp)
            val preferredLeftover = leftover(basePad, baseGap)
            val roomForPreferredPad = preferredLeftover >= minIcon
            val padV = if (roomForPreferredPad) basePad else minPad
            val gap = if (roomForPreferredPad) {
                (baseGap.value * localScale).dp
            } else {
                minGap
            }
            val iconBox = minOf(desiredIcon, leftover(padV, gap), roomW)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = padH, vertical = padV),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(iconBox)
                        .background(palette.badge, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = Color.White,
                        modifier = Modifier.fillMaxSize(glyphFraction),
                    )
                }
                Text(
                    text = title,
                    color = getTitleColor(),
                    fontSize = titleSize,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = gap),
                )
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
