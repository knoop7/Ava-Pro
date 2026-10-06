package com.example.ava.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.ui.AvaToast
import com.example.ava.clock.ClockAlert
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.components.SystemRingtoneLoader
import com.example.ava.utils.SoundUriPreview
import com.example.ava.utils.TouchSoundHelper

@Composable
fun ClockAlertGlyph(count: Int, ringing: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val label = stringResource(R.string.clock_alert_icon)
    val config = LocalConfiguration.current
    val scale = clockAlertGrow(
        clockAlertScale(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat()),
    )
    val pulse = rememberInfiniteTransition(label = "clock-alert-ring")
    val breath by pulse.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "clock-alert-breath",
    )
    val painter = painterResource(R.drawable.mdi_alarm_face)
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val dark by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val tint = if (ringing) {
        Color(0xFFFF3B30).copy(alpha = breath)
    } else if (dark) {
        Color.White.copy(alpha = 0.88f)
    } else {
        Color(0xFF5C6370)
    }
    Box(
        modifier = Modifier
            .size((ClockAlertGlyphDp * scale).dp)
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                TouchSoundHelper.playClick(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier
                .fillMaxSize()
                .padding((ClockAlertGlyphPadDp * scale).dp),
        )
        AnimatedVisibility(
            visible = count > 1 && !ringing,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = fadeIn(tween(240)),
            exit = fadeOut(tween(240)),
        ) {
            val badge = (18f * scale).dp
            Box(
                modifier = Modifier
                    .padding(
                        top = ((ClockAlertGlyphPadDp + 2f) * scale).dp,
                        end = ((ClockAlertGlyphPadDp + 2f) * scale).dp,
                    )
                    .size(badge)
                    .background(Color(0xFFFFE08A), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (count > 9) "9+" else count.toString(),
                    color = Color(0xFF3A2E00),
                    fontSize = (10f * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

internal const val ClockAlertGlyphDp = 88f
internal const val ClockAlertGlyphPadDp = 14f

private val LocalClockScale = staticCompositionLocalOf { 1f }

internal fun clockAlertScale(widthDp: Float, heightDp: Float): Float {
    val landscape = widthDp > heightDp * 1.05f
    val basis = if (landscape) heightDp / 360f else widthDp / 360f
    val floor = if (landscape) 1f else 1.08f
    val cap = if (landscape) 1.14f else 1.18f
    return basis.coerceIn(floor, cap)
}

internal fun clockAlertGrow(scale: Float): Float = (scale / 1.1f).coerceIn(1f, 1.12f)

private fun headScale(scale: Float): Float {
    val knee = 1.12f
    return if (scale <= knee) scale else knee + (scale - knee) * 0.5f
}

@Composable
private fun sd(base: Dp): Dp = base * LocalClockScale.current

@Composable
private fun ss(base: TextUnit): TextUnit = base * LocalClockScale.current

private data class SheetMetrics(
    val landscape: Boolean,
    val width: Dp,
    val padH: Dp,
    val padV: Dp,
    val time: TextUnit,
    val body: TextUnit,
    val mute: TextUnit,
    val date: TextUnit,
    val button: TextUnit,
    val buttonPad: Dp,
    val step: Dp,
    val scale: Float,
)

private fun textFit(sheetWidth: Dp, padH: Dp): Float {
    val inner = (sheetWidth - padH * 2).coerceAtLeast(0.dp)
    return (inner.value / 340f).coerceIn(0.82f, 1f)
}

private fun sheetMetrics(maxWidth: Dp, maxHeight: Dp): SheetMetrics {
    val scale = clockAlertScale(maxWidth.value, maxHeight.value)
    val head = headScale(scale)
    val landscape = maxWidth > maxHeight * 1.05f
    val cap = maxWidth * 0.92f
    val width = if (landscape) {
        (maxWidth * 0.60f).coerceIn(480.dp, 800.dp).coerceAtMost(cap)
    } else {
        (maxWidth * 0.90f).coerceIn(320.dp, 540.dp).coerceAtMost(cap)
    }
    val padH = ((if (landscape) 40f else 30f) * scale).dp
    val fit = textFit(width, padH)
    return if (landscape) {
        SheetMetrics(
            landscape = true,
            width = width,
            padH = padH,
            padV = (22f * scale).dp,
            time = (54f * head * fit).sp,
            body = (20f * scale * fit).sp,
            mute = (16f * scale * fit).sp,
            date = (19f * scale * fit).sp,
            button = (20f * scale * fit).sp,
            buttonPad = (14f * scale).dp,
            step = (44f * scale).dp,
            scale = scale,
        )
    } else {
        SheetMetrics(
            landscape = false,
            width = width,
            padH = padH,
            padV = (34f * scale).dp,
            time = (60f * head * fit).sp,
            body = (20f * scale * fit).sp,
            mute = (16f * scale * fit).sp,
            date = (19f * scale * fit).sp,
            button = (20f * scale * fit).sp,
            buttonPad = (14f * scale).dp,
            step = (48f * scale).dp,
            scale = scale,
        )
    }
}

@Composable
fun ClockAlertSheet(
    items: List<ClockAlert>,
    selected: ClockAlert?,
    ringing: Boolean,
    editing: Boolean,
    soundUri: String,
    onDismissSheet: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    onDelete: (String) -> Unit,
    onToggleEdit: () -> Unit,
    onCommitTime: (Int, Int) -> Unit,
    onSnooze: () -> Unit,
    onSoundSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val clearAgain = stringResource(R.string.clock_alert_clear_again)
    var soundPage by remember { mutableStateOf(false) }
    var confirmId by remember { mutableStateOf<String?>(null) }
    fun askDelete(id: String) {
        if (confirmId == id) {
            confirmId = null
            onDelete(id)
        } else {
            confirmId = id
            AvaToast.show(context, clearAgain, tag = "clock-alert-clear", durationMs = AvaToast.LONG_MS)
        }
    }
    LaunchedEffect(editing, ringing) {
        if (editing || ringing) {
            soundPage = false
            confirmId = null
        }
    }
    val sheetShape = RoundedCornerShape(28.dp)
    val dark = true
    val ink = Color(0xFFF4F5F7)
    val mute = Color(0xFFD4D8E0)
    val plate = Color(0xC41C1E24)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val metrics = sheetMetrics(maxWidth, maxHeight)
        CompositionLocalProvider(LocalClockScale provides clockAlertGrow(metrics.scale)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x6606080C))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(metrics.width)
                .heightIn(max = maxHeight * if (soundPage && !metrics.landscape) 0.58f else 0.88f)
                .clip(sheetShape)
                .background(plate)
                .border(1.dp, Color.White.copy(alpha = if (dark) 0.16f else 0.10f), sheetShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = metrics.padH, vertical = metrics.padV),
        ) {
            val item = selected
            if (item == null) {
                Text(
                    text = stringResource(R.string.clock_alert_empty),
                    color = mute,
                    fontSize = metrics.body,
                )
                return@Column
            }
            var draftHour by remember(item.id, editing) { mutableIntStateOf(item.hour) }
            var draftMinute by remember(item.id, editing) { mutableIntStateOf(item.minute) }
            val bodyScroll = rememberScrollState()
            if (soundPage) {
                ClockAlertSoundHeader(
                    ink = ink,
                    body = metrics.body,
                    enlargeBack = !metrics.landscape,
                    onBack = { soundPage = false },
                )
            }
            SettingsEdgeFadeScrollColumn(
                modifier = Modifier.weight(1f, fill = false),
                scrollState = bodyScroll,
                fadeHeight = sd(24.dp),
                handoffOverscrollToParent = false,
            ) {
            if (soundPage) {
                ClockAlertSoundPage(
                    soundUri = soundUri,
                    dark = dark,
                    ink = ink,
                    mute = mute,
                    muteSize = metrics.mute,
                    scrollState = bodyScroll,
                    onSoundSelected = onSoundSelected,
                )
            } else if (items.size > 1 && !ringing && !editing) {
                items.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = sd(10.dp), vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(rowPlate(row.id == item.id, dark))
                            .clickable {
                                TouchSoundHelper.playClick(context)
                                confirmId = null
                                onSelect(row.id)
                            }
                            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.widthIn(min = 112.dp)) {
                            row.dateText()?.let { date ->
                                Text(
                                    text = date,
                                    color = mute,
                                    fontSize = metrics.date,
                                    maxLines = 1,
                                    softWrap = false,
                                    style = TextStyle(fontFeatureSettings = "tnum"),
                                )
                            }
                            Text(
                                row.timeText(),
                                color = ink,
                                fontSize = metrics.body,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                style = TextStyle(fontFeatureSettings = "tnum"),
                            )
                        }
                        if (row.label.isNotBlank()) {
                            Text(
                                row.label,
                                color = ink,
                                fontSize = metrics.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Text(
                            titleFor(row.kind),
                            color = mute,
                            fontSize = metrics.mute,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                        )
                        RowEnd(
                            ink = ink,
                            onOpenSound = {
                                confirmId = null
                                soundPage = true
                            },
                            onAskDelete = { askDelete(row.id) },
                        )
                    }
                }
            } else if (editing && !ringing) {
                item.dateText()?.let { date ->
                    Text(
                        text = date,
                        color = mute,
                        fontSize = metrics.date,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimeColumn(
                        dark = dark,
                        ink = ink,
                        size = metrics.step,
                        timeSize = metrics.time,
                        value = draftHour,
                        modifier = Modifier.weight(1f),
                    ) { delta ->
                        TouchSoundHelper.playClick(context)
                        draftHour = (draftHour + delta).mod(24)
                    }
                    Text(
                        text = ":",
                        color = ink,
                        fontSize = (metrics.time.value * 1.4f).sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    TimeColumn(
                        dark = dark,
                        ink = ink,
                        size = metrics.step,
                        timeSize = metrics.time,
                        value = draftMinute,
                        modifier = Modifier.weight(1f),
                    ) { delta ->
                        TouchSoundHelper.playClick(context)
                        draftMinute = (draftMinute + delta).mod(60)
                    }
                }
            } else {
                ClockReadout(
                    item = item,
                    ink = ink,
                    mute = mute,
                    timeSize = metrics.time,
                    muteSize = metrics.date,
                    ringing = ringing,
                    onOpenSound = {
                        confirmId = null
                        soundPage = true
                    },
                    onAskDelete = { askDelete(item.id) },
                )
            }
            if (!soundPage && item.label.isNotBlank() && (items.size <= 1 || ringing || editing)) {
                Text(
                    text = item.label,
                    color = ink,
                    fontSize = metrics.body,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
            if (!soundPage && ringing) {
                Text(
                    text = stringResource(R.string.clock_alert_ringing),
                    color = mute,
                    fontSize = metrics.mute,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            }
            }
            if (soundPage) {
                SheetButton(
                    stringResource(R.string.back),
                    dark, ink, primary = false, metrics.button, metrics.buttonPad,
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = sd(10.dp))
                        .padding(top = 12.dp),
                ) {
                    TouchSoundHelper.playClick(context)
                    soundPage = false
                }
            } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sd(10.dp))
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (ringing) {
                    SheetButton(
                        stringResource(R.string.clock_alert_snooze),
                        dark, ink, primary = false, metrics.button, metrics.buttonPad, Modifier.weight(1f),
                    ) {
                        TouchSoundHelper.playClick(context)
                        onSnooze()
                    }
                    SheetButton(
                        stringResource(R.string.clock_alert_close),
                        dark, ink, primary = false, metrics.button, metrics.buttonPad, Modifier.weight(1f),
                        icon = R.drawable.ic_appwin_close,
                    ) {
                        TouchSoundHelper.playClick(context)
                        onClose()
                    }
                } else {
                    SheetButton(
                        stringResource(R.string.clock_alert_close),
                        dark, ink, primary = false, metrics.button, metrics.buttonPad, Modifier.weight(1f),
                        icon = R.drawable.ic_appwin_close,
                    ) {
                        TouchSoundHelper.playClick(context)
                        onClose()
                    }
                    SheetButton(
                        stringResource(R.string.clock_alert_change),
                        dark, ink, primary = false, metrics.button, metrics.buttonPad, Modifier.weight(1f),
                        icon = R.drawable.ic_pencil_outline,
                    ) {
                        TouchSoundHelper.playClick(context)
                        if (editing) onCommitTime(draftHour, draftMinute) else onToggleEdit()
                    }
                }
            }
            }
        }
        }
    }
}

@Composable
private fun TimeColumn(
    dark: Boolean,
    ink: Color,
    size: Dp,
    timeSize: TextUnit,
    value: Int,
    modifier: Modifier = Modifier,
    onStep: (Int) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepButton(dark, ink, size, "+") { onStep(1) }
        Text(
            text = "%02d".format(value),
            color = ink,
            style = TextStyle(
                fontSize = timeSize,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        StepButton(dark, ink, size, "−") { onStep(-1) }
    }
}

@Composable
private fun StepButton(dark: Boolean, ink: Color, size: Dp, text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.18f else 0.16f), CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = ink, fontSize = ss(22.sp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun SheetButton(
    text: String,
    dark: Boolean,
    ink: Color,
    primary: Boolean,
    fontSize: TextUnit,
    padV: Dp,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    onClick: () -> Unit,
) {
    val bg = when {
        primary && !dark -> Color(0xFF1C1D22)
        primary -> Color.White.copy(alpha = 0.12f)
        dark -> Color.White.copy(alpha = 0.10f)
        else -> Color(0xFFECEAE4)
    }
    val fg = if (primary && !dark) Color(0xFFF4F5F7) else ink
    Box(
        modifier = modifier
            .height(padV * 2 + 28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(
                    painter = painterResource(icon),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(fg),
                    modifier = Modifier.size(sd(18.dp)),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                color = fg,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ClearMark(ink: Color, onClick: () -> Unit) {
    val context = LocalContext.current
    val clear = stringResource(R.string.clock_alert_clear)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable {
                TouchSoundHelper.playClick(context)
                onClick()
            }
            .semantics { contentDescription = clear },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_trash_outline),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ink),
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun rowPlate(selected: Boolean, dark: Boolean): Color =
    if (selected) Color.White.copy(alpha = if (dark) 0.10f else 0.18f) else Color.Transparent

private val MarkSlotSize = 44.dp

@Composable
private fun MarkSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(sd(MarkSlotSize)),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun ClockReadout(
    item: ClockAlert,
    ink: Color,
    mute: Color,
    timeSize: TextUnit,
    muteSize: TextUnit,
    ringing: Boolean,
    onOpenSound: () -> Unit,
    onAskDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sd(10.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            val date = item.dateText()
            val clockSize = fitLine(maxWidth.value, ems = 3.2f, desired = timeSize.value)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (date != null) {
                    Text(
                        text = date,
                        color = mute,
                        fontSize = muteSize,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                    )
                }
                Text(
                    text = item.timeText(),
                    color = ink,
                    fontSize = clockSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!ringing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SoundMark(ink, onOpenSound)
                ClearMark(ink, onAskDelete)
            }
        }
    }
}

private fun fitLine(widthDp: Float, ems: Float, desired: Float): TextUnit {
    if (widthDp <= 0f || ems <= 0f) return desired.sp
    return (widthDp / ems).coerceIn(desired * 0.62f, desired).sp
}

@Composable
private fun RowEnd(
    ink: Color,
    onOpenSound: () -> Unit,
    onAskDelete: () -> Unit,
) {
    MarkSlot { SoundMark(ink, onOpenSound) }
    MarkSlot { ClearMark(ink, onAskDelete) }
}

@Composable
private fun SoundMark(ink: Color, onClick: () -> Unit) {
    val context = LocalContext.current
    val label = stringResource(R.string.settings_dream_clock_timer_sound)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable {
                TouchSoundHelper.playClick(context)
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_bell_linear_outline),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ink),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ClockAlertSoundHeader(
    ink: Color,
    body: TextUnit,
    enlargeBack: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val back = stringResource(R.string.back)
    val backSlot = if (enlargeBack) sd(52.dp) else sd(MarkSlotSize)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(backSlot)
                .clip(CircleShape)
                .clickable {
                    TouchSoundHelper.playClick(context)
                    onBack()
                }
                .semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "‹",
                color = ink,
                fontSize = if (enlargeBack) 30.sp else 22.sp,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = stringResource(R.string.settings_dream_clock_timer_sound),
            color = ink,
            fontSize = body,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.size(backSlot))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sd(10.dp))
            .padding(top = sd(16.dp), bottom = sd(8.dp))
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.10f)),
    )
}

@Composable
private fun ClockAlertSoundPage(
    soundUri: String,
    dark: Boolean,
    ink: Color,
    mute: Color,
    muteSize: TextUnit,
    scrollState: androidx.compose.foundation.ScrollState,
    onSoundSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val defaultLabel = stringResource(R.string.timer_sound_default)
    val unknownLabel = stringResource(R.string.sound_unknown)
    val rowStride = sd(MarkSlotSize) + sd(4.dp)
    var preview by remember { mutableStateOf<SoundUriPreview.Handle?>(null) }
    DisposableEffect(Unit) {
        onDispose { preview?.stop() }
    }
    var ringtones by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) {
        ringtones = SystemRingtoneLoader.loadRingtones(
            context,
            android.media.RingtoneManager.TYPE_NOTIFICATION or android.media.RingtoneManager.TYPE_ALARM,
            listOf(defaultLabel to ClockAlert.DEFAULT_SOUND),
            unknownLabel,
        ).distinctBy { it.second }
    }
    LaunchedEffect(ringtones, soundUri) {
        val list = ringtones ?: return@LaunchedEffect
        val index = list.indexOfFirst {
            it.second == soundUri || (soundUri.isBlank() && it.second == ClockAlert.DEFAULT_SOUND)
        }
        if (index <= 0) return@LaunchedEffect
        val px = rowStride.value * context.resources.displayMetrics.density
        repeat(6) {
            if (scrollState.maxValue > 0) {
                scrollState.scrollTo((index * px).toInt().coerceAtMost(scrollState.maxValue))
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(32)
        }
    }
    ringtones?.forEach { (title, uri) ->
        val selected = uri == soundUri || (soundUri.isBlank() && uri == ClockAlert.DEFAULT_SOUND)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sd(10.dp), vertical = sd(2.dp))
                .height(sd(MarkSlotSize))
                .clip(RoundedCornerShape(16.dp))
                .background(rowPlate(selected, dark))
                .clickable {
                    TouchSoundHelper.playClick(context)
                    onSoundSelected(uri)
                    preview?.stop()
                    preview = if (uri.isNotEmpty()) SoundUriPreview.play(context, uri) else null
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MarkSlot {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(ink),
                    )
                }
            }
            Text(
                text = title,
                color = if (selected) ink else mute,
                fontSize = muteSize,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.size(sd(MarkSlotSize)))
        }
    }
}

@Composable
private fun titleFor(kind: ClockAlert.Kind): String =
    stringResource(
        if (kind == ClockAlert.Kind.REMINDER) R.string.clock_alert_title_reminder
        else R.string.clock_alert_title_alarm,
    )
