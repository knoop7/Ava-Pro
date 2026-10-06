package com.example.ava.ui.screens.settings.components

import com.example.ava.ui.AvaToast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import com.example.ava.R
import com.example.ava.ui.screens.home.PinLockContent
import com.example.ava.ui.screens.home.PinLockMode

/**
 * Change-passcode flow: verify current → enter new (no re-confirm step).
 *
 * Drawn as an in-window fullscreen overlay (same approach as [com.example.ava.ui.screens.home.HomePinLockOverlay]):
 * edge-to-edge under cutout / nav via the Activity chrome — not a separate Dialog window.
 */
@Composable
fun HomePinChangeDialog(
    isDarkMode: Boolean,
    pinHash: String,
    pinLength: Int,
    shuffleKeypad: Boolean = false,
    antiBruteForce: Boolean = false,
    onDismiss: () -> Unit,
    onPinChanged: (newPin: String) -> Unit,
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(PinLockMode.VerifyCurrent) }
    val scrim = if (isDarkMode) Color.Black.copy(alpha = 0.92f) else Color(0xFFF8FAFC).copy(alpha = 0.96f)

    // Once per open.
    LaunchedEffect(Unit) {
        AvaToast.show(
            context,
            context.getString(R.string.home_pin_change_forgot_toast),
            durationMs = AvaToast.LONG_MS,
        )
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1f)
            .background(scrim)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { /* block underlying settings */ },
        contentAlignment = Alignment.Center,
    ) {
        when (step) {
            PinLockMode.VerifyCurrent -> PinLockContent(
                mode = PinLockMode.VerifyCurrent,
                isDarkMode = isDarkMode,
                pinHash = pinHash,
                pinLength = pinLength,
                shuffleKeypad = shuffleKeypad,
                antiBruteForce = antiBruteForce,
                onSuccess = { step = PinLockMode.EnterNew },
            )
            PinLockMode.EnterNew -> PinLockContent(
                mode = PinLockMode.EnterNew,
                isDarkMode = isDarkMode,
                pinHash = pinHash,
                pinLength = pinLength,
                shuffleKeypad = shuffleKeypad,
                antiBruteForce = false,
                onSuccess = { pin ->
                    onPinChanged(pin)
                    onDismiss()
                },
            )
            PinLockMode.Unlock -> Unit
        }
    }
}
