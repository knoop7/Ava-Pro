package com.example.ava.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Hold this long to wake the shared top «返回» strip (no mid-hold toast). */
private const val BACK_HOLD_REVEAL_MS = 2_000L

/**
 * Music floating overlays only (Glass / Detailed): wake the shared top «返回»
 * strip on a **~2s long-press**. Single taps do not reveal — that stole play /
 * seek / Mass controls.
 *
 * Uses [PointerEventPass.Initial] and never consumes events. Release before 2s
 * cancels reveal.
 *
 * Call sites should skip this modifier while the Mass rail is open, and hide
 * the strip when the rail opens — otherwise the top chrome fights the rail ✕.
 *
 * Weather and other dashboards still use any-press
 * [com.example.ava.services.DashboardTouchListener].
 */
fun Modifier.revealMediaOverlayChromeOnLongPress(
    onReveal: () -> Unit,
): Modifier = pointerInput(onReveal) {
    coroutineScope {
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent(PointerEventPass.Initial)
                if (down.type != PointerEventType.Press) continue

                val revealJob = launch {
                    delay(BACK_HOLD_REVEAL_MS)
                    onReveal()
                }
                try {
                    waitUntilPressEnds()
                } finally {
                    revealJob.cancel()
                }
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitUntilPressEnds() {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        when (event.type) {
            PointerEventType.Release,
            PointerEventType.Exit,
            -> return
            else -> {
                if (event.changes.none { it.pressed }) return
            }
        }
    }
}
