package com.example.ava.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.R
import com.example.ava.settings.HomeLockPin
import com.example.ava.settings.HomeLockSettings
import com.example.ava.settings.HomeLockSettingsStore
import com.example.ava.settings.homeLockSettingsStore
import com.example.ava.ui.rememberAdaptiveSpec
import com.example.ava.ui.rememberCompactSquareScreen
import com.example.ava.ui.rememberPaneIsLandscape
import com.example.ava.ui.screens.settings.components.AutoResizeText
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.AccentBrown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PinLockMode {
    Unlock,
    VerifyCurrent,
    EnterNew,
}

/** Timing for unlock success beat before parent exit animation starts. */
private const val SUCCESS_BEAT_MS = 90L

/**
 * Layout metrics for the PIN pad.
 *
 * Sizing follows the weather / overlay vmin approach:
 * - [vmin] drives the target key size (fraction of the shorter edge)
 * - distinguish small-square panels, traditional phone landscape, and large/kiosk screens
 * - then fit into the available Compose box so nothing overflows
 */
private data class PinLockLayout(
    val keySize: Dp,
    val gap: Dp,
    val padWidth: Dp,
    val titleSize: TextUnit,
    val titleMinSize: TextUnit,
    val hintSize: TextUnit,
    val digitSize: TextUnit,
    val iconSize: Dp,
    val dotSize: Dp,
    val dotGap: Dp,
    val titleToDots: Dp,
    val dotsToPad: Dp,
    val hintGap: Dp,
    val outerPadH: Dp,
    val outerPadV: Dp,
    val cornerMin: Dp,
    val cornerMax: Dp,
)

@Composable
private fun rememberPinLockLayout(maxWidth: Dp, maxHeight: Dp): PinLockLayout {
    val adaptive = rememberAdaptiveSpec()
    val configuration = LocalConfiguration.current
    // Same clamp idea as MinimalLauncherContent — protect against 0.5x / 2x system font.
    val fontScale = configuration.fontScale.coerceIn(0.85f, 1.35f)

    val w = maxWidth.value
    val h = maxHeight.value
    // Weather overlay: scale from the shorter edge, not longest.
    val vmin = minOf(w, h)
    val vmax = maxOf(w, h)
    // Weather uses width > height * 1.2 — ignores near-square “landscape”.
    val compactSquare = rememberCompactSquareScreen()
    val isLandscape = rememberPaneIsLandscape()
    val isSmallSquare = compactSquare || (kotlin.math.abs(w - h) < 50f && w <= 500f)
    // Traditional phone shortest side (portrait ~360–430, landscape height same).
    val isPhone = vmin < 520f
    val isPhoneLandscape = isPhone && isLandscape
    val tiny = adaptive.compact || vmin < 360f
    val shortHeight = h < 400f

    // Large / low-DPI panels get an extra boost (same tiers as voice-call overlays).
    val tierBoost = when {
        vmin >= 1440f || vmax >= 2560f -> 1.22f
        vmin >= 960f || vmax >= 1920f -> 1.16f
        vmin >= 720f || vmax >= 1280f -> 1.10f
        vmin >= 600f || vmax >= 1024f -> 1.06f
        else -> 1f
    }

    // Target key ≈ vmin fraction — keep portrait calmer so keys don't overflow.
    val keyFraction = when {
        isSmallSquare -> 0.185f
        isPhoneLandscape -> 0.205f
        isPhone -> 0.200f
        vmin >= 960f -> 0.130f
        vmin >= 600f -> 0.148f
        else -> 0.185f
    }
    val enlarge = when {
        isPhoneLandscape -> 1.10f
        isPhone -> 1.06f
        isSmallSquare -> 1.06f
        else -> 1.05f
    }
    val targetKey = vmin * keyFraction * enlarge * tierBoost

    val maxKeyDp = when {
        vmin >= 960f -> if (isLandscape) 108f else 112f
        vmin >= 600f -> if (isLandscape) 96f else 100f
        isPhoneLandscape -> 86f
        tiny -> if (isLandscape) 68f else 72f
        else -> if (isLandscape) 88f else 92f
    }
    val minKeyDp = when {
        isPhoneLandscape -> 52f
        tiny && isLandscape -> 44f
        tiny -> 48f
        shortHeight && isLandscape -> 50f
        else -> 54f
    }

    // Vertical rhythm: portrait needs more air between title / dots / pad.
    val titleToDots = when {
        isPhoneLandscape -> 10.dp
        isLandscape -> 12.dp
        isSmallSquare || tiny -> 18.dp
        else -> 24.dp
    }
    val dotsToPad = when {
        isPhoneLandscape -> 12.dp
        isLandscape -> 14.dp
        isSmallSquare || tiny -> 20.dp
        else -> 28.dp
    }
    val hintGap = if (isLandscape) 4.dp else 8.dp
    val gapDp = when {
        isPhoneLandscape -> (vmin * 0.020f).coerceIn(7f, 11f)
        isSmallSquare || tiny -> (vmin * 0.022f).coerceIn(8f, 12f)
        vmin >= 600f -> (vmin * 0.018f).coerceIn(12f, 16f)
        else -> (vmin * 0.026f).coerceIn(10f, 14f)
    }.dp
    val outerPadH = when {
        isPhoneLandscape -> 20.dp
        tiny || isSmallSquare -> 16.dp
        vmin >= 600f -> 28.dp
        else -> 20.dp
    }
    val outerPadV = when {
        isPhoneLandscape && shortHeight -> 6.dp
        isPhoneLandscape -> 8.dp
        isLandscape && shortHeight -> 8.dp
        tiny || isSmallSquare -> 12.dp
        isLandscape -> 12.dp
        else -> 20.dp
    }

    // Reserve real header block before sizing keys so title/dots/pad never collide.
    val titleLineApprox = when {
        isLandscape -> 28.dp
        tiny || isSmallSquare -> 30.dp
        else -> 34.dp
    }
    val dotsRowApprox = 18.dp
    // Always reserve hint-line room (change-PIN shows it; unlock just gets a bit more air).
    val headerBlock =
        titleLineApprox + hintGap + 16.dp + titleToDots + dotsRowApprox + dotsToPad

    val widthBudget = (maxWidth - outerPadH * 2).coerceAtLeast(160.dp)
    val heightBudget = (maxHeight - outerPadV * 2 - headerBlock).coerceAtLeast(120.dp)

    // Width cap stays inside the padded box so 4 keys never clip horizontally.
    val widthCapDp = min(
        widthBudget,
        when {
            vmin >= 960f -> minOf(w * 0.50f, 520f).dp
            vmin >= 600f -> minOf(w * 0.56f, 460f).dp
            isPhoneLandscape -> minOf(w * 0.68f, 420f).dp
            isLandscape -> minOf(w * 0.58f, 400f).dp
            isSmallSquare -> minOf(w * 0.86f, 340f).dp
            tiny -> minOf(w * 0.88f, 320f).dp
            isPhone -> minOf(w * 0.88f, 380f).dp
            else -> minOf(w * 0.86f, 400f).dp
        },
    )

    val keyByWidth = (widthCapDp - gapDp * 3) / 4
    val keyByHeight = (heightBudget - gapDp * 2) / 3
    val fitted = min(keyByWidth, keyByHeight)
    val desired = targetKey.coerceIn(minKeyDp, maxKeyDp).dp
    val keySize = min(fitted, desired)
    val padWidth = keySize * 4 + gapDp * 3

    fun scaledSp(designAt1x: Float, minSp: Float, maxSp: Float): TextUnit {
        val clamped = designAt1x.coerceIn(minSp, maxSp)
        return (clamped / fontScale).sp
    }

    val keyV = keySize.value
    val titleDesign = (keyV * 0.28f).coerceIn(
        if (tiny || isSmallSquare) 14f else 16f,
        if (vmin >= 600f) 24f else 20f,
    )
    val hintDesign = (keyV * 0.18f).coerceIn(10f, 14f)
    // Keep digits inside the key face (border + padding headroom).
    val digitDesign = (keyV * 0.34f).coerceIn(
        if (tiny || isSmallSquare) 14f else 15f,
        if (vmin >= 600f) 28f else 24f,
    )

    return PinLockLayout(
        keySize = keySize,
        gap = gapDp,
        padWidth = padWidth,
        titleSize = scaledSp(titleDesign, 13f, 24f),
        titleMinSize = scaledSp(titleDesign * 0.78f, 11f, 16f),
        hintSize = scaledSp(hintDesign, 10f, 14f),
        digitSize = scaledSp(digitDesign, 13f, 28f),
        iconSize = (keySize * 0.34f).coerceIn(16.dp, if (vmin >= 600f) 30.dp else 26.dp),
        dotSize = (keySize * 0.15f).coerceIn(9.dp, 14.dp),
        dotGap = (keySize * 0.18f).coerceIn(10.dp, 16.dp),
        titleToDots = titleToDots,
        dotsToPad = dotsToPad,
        hintGap = hintGap,
        outerPadH = outerPadH,
        outerPadV = outerPadV,
        cornerMin = if (tiny || isSmallSquare) 10.dp else 12.dp,
        cornerMax = if (vmin >= 600f) 18.dp else 16.dp,
    )
}

@Composable
fun HomePinLockOverlay(
    isDarkMode: Boolean,
    pinHash: String,
    pinLength: Int,
    shuffleKeypad: Boolean = false,
    antiBruteForce: Boolean = false,
    /** When true, fully opaque cover (settings lock). Home lock keeps a light scrim. */
    opaqueBackground: Boolean = false,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrim = when {
        opaqueBackground && isDarkMode -> Color.Black
        opaqueBackground -> Color(0xFFF8FAFC)
        isDarkMode -> Color.Black.copy(alpha = 0.86f)
        else -> Color(0xFFF8FAFC).copy(alpha = 0.84f)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scrim)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { /* block underlying UI */ },
        contentAlignment = Alignment.Center,
    ) {
        PinLockContent(
            mode = PinLockMode.Unlock,
            isDarkMode = isDarkMode,
            pinHash = pinHash,
            pinLength = pinLength,
            shuffleKeypad = shuffleKeypad,
            antiBruteForce = antiBruteForce,
            onSuccess = { onUnlocked() },
        )
    }
}

@Composable
fun PinLockContent(
    mode: PinLockMode,
    isDarkMode: Boolean,
    pinHash: String,
    pinLength: Int,
    onSuccess: (pin: String) -> Unit,
    modifier: Modifier = Modifier,
    shuffleKeypad: Boolean = false,
    antiBruteForce: Boolean = false,
) {
    val context = LocalContext.current
    val homeLockStore = remember { HomeLockSettingsStore(context.homeLockSettingsStore) }
    val homeLockSettings by homeLockStore.getFlow().collectAsStateWithLifecycle(HomeLockSettings())
    val scope = rememberCoroutineScope()

    val resolvedLength = HomeLockPin.clampPinLength(pinLength)
    val accent = if (isDarkMode) AccentBrown else AccentBlue
    val textColor = if (isDarkMode) Color.White else Color(0xFF0F172A)
    val muted = if (isDarkMode) Color(0xFF9CA3AF) else Color(0xFF64748B)
    val title = when (mode) {
        PinLockMode.Unlock -> stringResource(R.string.home_pin_lock_title)
        PinLockMode.VerifyCurrent -> stringResource(R.string.home_pin_change_current)
        PinLockMode.EnterNew -> stringResource(R.string.home_pin_change_new)
    }
    val applyBruteForce =
        antiBruteForce && (mode == PinLockMode.Unlock || mode == PinLockMode.VerifyCurrent)

    var pin by remember(mode, resolvedLength) { mutableStateOf("") }
    var shakeToken by remember { mutableIntStateOf(0) }
    var errorActive by remember { mutableStateOf(false) }
    var submitting by remember(mode, resolvedLength) { mutableStateOf(false) }
    var successPulse by remember(mode, resolvedLength) { mutableStateOf(false) }
    var pendingSuccessPin by remember(mode, resolvedLength) { mutableStateOf<String?>(null) }
    val shakeOffset = remember { Animatable(0f) }

    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lockedOut = applyBruteForce && HomeLockPin.isKeypadLockedOut(homeLockSettings, nowEpochMs)
    val remainingLockoutMs =
        if (lockedOut) HomeLockPin.remainingLockoutMs(homeLockSettings, nowEpochMs) else 0L
    val remainingLockoutSec = ((remainingLockoutMs + 999L) / 1000L).toInt()

    LaunchedEffect(lockedOut, homeLockSettings.lockoutUntilEpochMs) {
        if (!lockedOut) return@LaunchedEffect
        while (true) {
            nowEpochMs = System.currentTimeMillis()
            if (!HomeLockPin.isKeypadLockedOut(homeLockSettings, nowEpochMs)) break
            delay(250)
        }
        nowEpochMs = System.currentTimeMillis()
    }

    val hint = when {
        lockedOut -> stringResource(R.string.home_pin_lockout_wait, remainingLockoutSec)
        mode == PinLockMode.Unlock -> null
        else -> stringResource(R.string.home_pin_lock_hint_change, resolvedLength)
    }

    fun submit(candidate: String) {
        if (submitting || errorActive || lockedOut) return
        if (candidate.length != resolvedLength) return
        when (mode) {
            PinLockMode.Unlock, PinLockMode.VerifyCurrent -> {
                if (HomeLockPin.matches(candidate, pinHash, resolvedLength)) {
                    submitting = true
                    pendingSuccessPin = candidate
                    successPulse = true
                    if (applyBruteForce) {
                        scope.launch { homeLockStore.clearBruteForceOnSuccess() }
                    }
                } else {
                    errorActive = true
                    shakeToken++
                    if (applyBruteForce) {
                        scope.launch { homeLockStore.recordFailedUnlockAttempt() }
                    }
                }
            }
            PinLockMode.EnterNew -> {
                if (HomeLockPin.isValidFormat(candidate, resolvedLength)) {
                    submitting = true
                    pendingSuccessPin = candidate
                    successPulse = true
                } else {
                    errorActive = true
                    shakeToken++
                }
            }
        }
    }

    // Short success beat, then hand off so parent can run the fast exit fade.
    LaunchedEffect(pendingSuccessPin) {
        val successPin = pendingSuccessPin ?: return@LaunchedEffect
        delay(SUCCESS_BEAT_MS)
        onSuccess(successPin)
    }

    LaunchedEffect(shakeToken) {
        if (shakeToken == 0) return@LaunchedEffect
        val offsets = listOf(-14f, 14f, -10f, 10f, -5f, 5f, 0f)
        for (x in offsets) {
            shakeOffset.animateTo(
                targetValue = x,
                animationSpec = tween(durationMillis = 36, easing = FastOutSlowInEasing),
            )
        }
        pin = ""
        errorActive = false
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { translationX = shakeOffset.value },
        contentAlignment = Alignment.Center,
    ) {
        val layout = rememberPinLockLayout(maxWidth = maxWidth, maxHeight = maxHeight)
        // Six dots need a slightly tighter row so they stay centered on narrow widths.
        val dotScale = if (resolvedLength >= HomeLockPin.PIN_LENGTH_6) 0.88f else 1f
        val dotSize = layout.dotSize * dotScale
        val dotGap = layout.dotGap * dotScale
        val keysEnabled = !submitting && !errorActive && !lockedOut

        Column(
            modifier = Modifier
                .padding(horizontal = layout.outerPadH, vertical = layout.outerPadV)
                .width(layout.padWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AutoResizeText(
                text = title,
                fontSize = layout.titleSize,
                minFontSize = layout.titleMinSize,
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1,
                style = TextStyle(textAlign = TextAlign.Center),
                modifier = Modifier.width(layout.padWidth),
            )
            if (hint != null) {
                Spacer(Modifier.height(layout.hintGap))
                AutoResizeText(
                    text = hint,
                    fontSize = layout.hintSize,
                    minFontSize = (layout.hintSize.value * 0.85f).sp,
                    color = muted,
                    maxLines = 2,
                    style = TextStyle(textAlign = TextAlign.Center),
                    modifier = Modifier.width(layout.padWidth),
                )
            }
            Spacer(modifier.height(layout.titleToDots))
            PinDots(
                filled = if (errorActive) resolvedLength else pin.length,
                total = resolvedLength,
                accent = accent,
                emptyBorder = muted,
                isDarkMode = isDarkMode,
                error = errorActive,
                success = successPulse,
                dotSize = dotSize,
                dotGap = dotGap,
            )
            Spacer(modifier.height(layout.dotsToPad))
            PinKeypad4x3(
                keySize = layout.keySize,
                gap = layout.gap,
                digitSize = layout.digitSize,
                iconSize = layout.iconSize,
                cornerMin = layout.cornerMin,
                cornerMax = layout.cornerMax,
                isDarkMode = isDarkMode,
                accent = accent,
                shuffleKeypad = shuffleKeypad,
                layoutToken = mode,
                confirmEnabled = pin.length == resolvedLength && keysEnabled,
                enabled = keysEnabled,
                onDigit = { d ->
                    if (!keysEnabled || pin.length >= resolvedLength) return@PinKeypad4x3
                    val next = pin + d
                    pin = next
                    if (next.length == resolvedLength) {
                        submit(next)
                    }
                },
                onDelete = {
                    if (!keysEnabled || pin.isEmpty()) return@PinKeypad4x3
                    pin = pin.dropLast(1)
                },
                onConfirm = {
                    if (pin.length == resolvedLength) submit(pin)
                },
            )
        }
    }
}

@Composable
private fun PinDots(
    filled: Int,
    total: Int,
    accent: Color,
    emptyBorder: Color,
    isDarkMode: Boolean,
    error: Boolean,
    success: Boolean,
    dotSize: Dp,
    dotGap: Dp,
) {
    val emptyStroke = emptyBorder.copy(alpha = if (isDarkMode) 0.42f else 0.38f)
    val emptyWash = if (isDarkMode) {
        Color.White.copy(alpha = 0.04f)
    } else {
        Color.Black.copy(alpha = 0.03f)
    }
    val shadowAmbient = if (isDarkMode) {
        Color.Black.copy(alpha = 0.45f)
    } else {
        Color.Black.copy(alpha = 0.14f)
    }
    val shadowSpot = if (isDarkMode) {
        Color.Black.copy(alpha = 0.28f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(dotGap)) {
        repeat(total) { index ->
            val on = index < filled || success
            val targetScale = when {
                error -> 1.04f
                success -> 1.06f
                on -> 1.03f
                else -> 1f
            }
            val scale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "pinDotScale$index",
            )
            val coreColor by animateColorAsState(
                targetValue = when {
                    error && on -> Color(0xFFD98B8B)
                    on -> accent
                    else -> emptyWash
                },
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "pinDotFill$index",
            )
            val strokeColor by animateColorAsState(
                targetValue = when {
                    error && on -> Color(0xFFD98B8B).copy(alpha = 0.85f)
                    on -> accent.copy(alpha = 0.72f)
                    else -> emptyStroke
                },
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "pinDotBorder$index",
            )
            val haloAlpha by animateFloatAsState(
                targetValue = when {
                    error && on -> 0.16f
                    success && on -> 0.20f
                    on -> 0.14f
                    else -> 0f
                },
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "pinDotHalo$index",
            )
            val elevation = when {
                on -> 3.dp
                else -> 1.dp
            }

            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Soft outer glow — kept faint so it reads premium, not neon.
                Box(
                    modifier = Modifier
                        .size(dotSize * 1.55f)
                        .graphicsLayer { alpha = haloAlpha }
                        .background(
                            color = when {
                                error && on -> Color(0xFFD98B8B)
                                else -> accent
                            }.copy(alpha = 0.55f),
                            shape = CircleShape,
                        ),
                )
                Box(
                    modifier = Modifier
                        .size(dotSize)
                        .shadow(
                            elevation = elevation,
                            shape = CircleShape,
                            clip = false,
                            ambientColor = shadowAmbient,
                            spotColor = shadowSpot,
                        )
                        .clip(CircleShape)
                        .background(coreColor)
                        .border(
                            width = 1.dp,
                            color = strokeColor,
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

@Composable
private fun PinKeypad4x3(
    keySize: Dp,
    gap: Dp,
    digitSize: TextUnit,
    iconSize: Dp,
    cornerMin: Dp,
    cornerMax: Dp,
    isDarkMode: Boolean,
    accent: Color,
    shuffleKeypad: Boolean,
    layoutToken: Any,
    confirmEnabled: Boolean,
    enabled: Boolean,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
) {
    // Fixed action keys; only the 10 digit slots reshuffle when enabled.
    val digitOrder = remember(shuffleKeypad, layoutToken) {
        val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        if (shuffleKeypad) digits.shuffled() else digits
    }
    val rows = remember(digitOrder) {
        listOf(
            digitOrder.subList(0, 4),
            digitOrder.subList(4, 8),
            listOf(digitOrder[8], digitOrder[9], "del", "ok"),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        // 4×3 dense grid: digits / digits / digits + ⌫ + check
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { key ->
                    when (key) {
                        "del" -> PadKey(
                            size = keySize,
                            digitSize = digitSize,
                            iconSize = iconSize,
                            cornerMin = cornerMin,
                            cornerMax = cornerMax,
                            label = null,
                            iconResId = R.drawable.mdi_backspace_outline,
                            role = PadKeyRole.Delete,
                            isDarkMode = isDarkMode,
                            accent = accent,
                            enabled = enabled,
                            onClick = onDelete,
                        )
                        "ok" -> PadKey(
                            size = keySize,
                            digitSize = digitSize,
                            iconSize = iconSize,
                            cornerMin = cornerMin,
                            cornerMax = cornerMax,
                            label = null,
                            iconResId = R.drawable.mdi_check,
                            role = PadKeyRole.Confirm,
                            isDarkMode = isDarkMode,
                            accent = accent,
                            enabled = enabled && confirmEnabled,
                            onClick = onConfirm,
                        )
                        else -> PadKey(
                            size = keySize,
                            digitSize = digitSize,
                            iconSize = iconSize,
                            cornerMin = cornerMin,
                            cornerMax = cornerMax,
                            label = key,
                            role = PadKeyRole.Digit,
                            isDarkMode = isDarkMode,
                            accent = accent,
                            enabled = enabled,
                            onClick = { onDigit(key) },
                        )
                    }
                }
            }
        }
    }
}

private enum class PadKeyRole { Digit, Delete, Confirm }

@Composable
private fun PadKey(
    size: Dp,
    digitSize: TextUnit,
    iconSize: Dp,
    cornerMin: Dp,
    cornerMax: Dp,
    role: PadKeyRole,
    isDarkMode: Boolean,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    label: String? = null,
    iconResId: Int? = null,
) {
    val shape = RoundedCornerShape((size * 0.22f).coerceIn(cornerMin, cornerMax))
    val bg: Color
    val fg: Color
    val border: Color
    when (role) {
        PadKeyRole.Digit -> {
            if (enabled) {
                bg = if (isDarkMode) Color(0xFF1C1C1C) else Color.White
                fg = if (isDarkMode) Color.White else Color(0xFF0F172A)
                border = if (isDarkMode) Color(0xFF333333) else Color(0xFFD7DEE8)
            } else {
                bg = if (isDarkMode) Color(0xFF141414) else Color(0xFFF1F5F9)
                fg = if (isDarkMode) Color(0xFF4B5563) else Color(0xFF94A3B8)
                border = if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE2E8F0)
            }
        }
        PadKeyRole.Delete -> {
            if (enabled) {
                bg = if (isDarkMode) Color(0xFF1C1C1C) else Color.White
                fg = if (isDarkMode) Color(0xFF9CA3AF) else Color(0xFF64748B)
                border = if (isDarkMode) Color(0xFF333333) else Color(0xFFD7DEE8)
            } else {
                bg = if (isDarkMode) Color(0xFF141414) else Color(0xFFF1F5F9)
                fg = if (isDarkMode) Color(0xFF4B5563) else Color(0xFF94A3B8)
                border = if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE2E8F0)
            }
        }
        PadKeyRole.Confirm -> {
            if (enabled) {
                bg = if (isDarkMode) Color(0xFF1C1C1C) else Color.White
                fg = accent
                border = if (isDarkMode) accent.copy(alpha = 0.48f) else accent.copy(alpha = 0.42f)
            } else {
                bg = if (isDarkMode) Color(0xFF141414) else Color(0xFFF1F5F9)
                fg = if (isDarkMode) Color(0xFF6B7280) else Color(0xFF94A3B8)
                border = if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE2E8F0)
            }
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = tween(durationMillis = if (pressed) 60 else 120),
        label = "padKeyPress",
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(bg)
            .border(1.5.dp, border, shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (iconResId != null) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(iconSize),
            )
        } else if (label != null) {
            Text(
                text = label,
                color = fg,
                fontSize = if (role == PadKeyRole.Digit) digitSize else (digitSize.value * 0.78f).sp,
                // Platform sans-serif + bold on all Android; not the Material theme body font.
                fontFamily = if (role == PadKeyRole.Digit) FontFamily.SansSerif else FontFamily.Default,
                fontWeight = if (role == PadKeyRole.Digit) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
