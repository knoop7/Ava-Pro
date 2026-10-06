package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * Process-scoped scroll offsets for settings LazyColumns.
 *
 * Navigation Compose 2.6 Crossfade disposes off-stack destinations; default
 * [androidx.compose.foundation.lazy.rememberLazyListState] often fails to restore
 * when users drill into a subpage and pop back. This store survives dispose for
 * the lifetime of the process so return visits reopen at the prior offset.
 */
internal object SettingsLazyListScrollMemory {
    private data class Pos(val index: Int, val offset: Int)

    private val positions = mutableMapOf<String, Pos>()

    fun peek(key: String): Pair<Int, Int>? =
        positions[key]?.let { it.index to it.offset }

    fun put(key: String, index: Int, offset: Int) {
        positions[key] = Pos(index, offset)
    }
}

@Composable
fun rememberSettingsLazyListState(memoryKey: String): LazyListState {
    val state = remember(memoryKey) {
        val saved = SettingsLazyListScrollMemory.peek(memoryKey)
        LazyListState(
            firstVisibleItemIndex = saved?.first ?: 0,
            firstVisibleItemScrollOffset = saved?.second ?: 0,
        )
    }
    DisposableEffect(memoryKey, state) {
        onDispose {
            SettingsLazyListScrollMemory.put(
                memoryKey,
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
            )
        }
    }
    return state
}
