package com.example.ava.ui.screens.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import com.example.ava.ui.scroll.fling.FlingConfiguration
import com.example.ava.ui.scroll.fling.flingBehavior
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Letter hops inside this many rows slide; farther ones teleport close first. */
internal const val DRAWER_FAST_SCROLL_NEAR_ROWS = 8

/** Near-letter follow. Ease-out at this length reads faster than a slow-start 100ms. */
internal const val DRAWER_FAST_SCROLL_MS = 120

/**
 * Drawer-grid coast — wetter than settings (0.0075 / 0.075), still inside
 * Flinger's documented ultraSmooth window so a flick does not run forever.
 */
@Composable
internal fun rememberDrawerGridFlingBehavior(): FlingBehavior {
    return flingBehavior(
        scrollConfiguration = FlingConfiguration.Builder()
            .scrollViewFriction(0.0065f)
            .decelerationFriction(0.055f)
            .numberOfSplinePoints(140)
            .build(),
    )
}

/**
 * One in-flight follow for A–Z jumps. A new letter cancels the current slide
 * and heads for the latest index, so hops do not queue up behind each other.
 */
internal class DrawerGridLetterFollow(
    private val state: LazyGridState,
) {
    private val latest = AtomicInteger(-1)
    private var job: Job? = null

    /** True while a [jumpTo] coroutine is actively scrolling the grid. */
    @Volatile
    var isFollowing: Boolean = false
        private set

    fun cancel() {
        job?.cancel()
        job = null
        latest.set(-1)
        isFollowing = false
    }

    fun jumpTo(
        scope: CoroutineScope,
        index: Int,
        columns: Int,
        spacingPx: Float,
        reduceMotion: Boolean = false,
    ) {
        if (index < 0) return
        latest.set(index)
        job?.cancel()
        job = scope.launch {
            isFollowing = true
            try {
                if (reduceMotion) {
                    val target = latest.get()
                    if (target >= 0) state.scrollToItem(target)
                    return@launch
                }
                var hops = 0
                while (hops++ < 12) {
                    val target = latest.get()
                    if (target < 0) break
                    state.followDrawerLetter(target, columns, spacingPx)
                    if (latest.get() == target) break
                }
            } finally {
                isFollowing = false
            }
        }
    }
}

internal fun drawerGridLine(index: Int, columns: Int): Int {
    val col = columns.coerceAtLeast(1)
    return index.coerceAtLeast(0) / col
}

/**
 * Far hops [scrollToItem] onto a nearby row so the last stretch can slide.
 * Near hops stay on the current window and only animate.
 */
internal fun planDrawerGridApproachIndex(
    firstIndex: Int,
    targetIndex: Int,
    columns: Int,
    nearRows: Int = DRAWER_FAST_SCROLL_NEAR_ROWS,
): Int? {
    val col = columns.coerceAtLeast(1)
    val from = drawerGridLine(firstIndex, col)
    val to = drawerGridLine(targetIndex, col)
    if (abs(to - from) <= nearRows.coerceAtLeast(0)) return null
    val tail = (nearRows / 2).coerceAtLeast(1)
    val approachLine = if (to > from) {
        (to - tail).coerceAtLeast(0)
    } else {
        to + tail
    }
    return approachLine * col
}

internal fun drawerGridEstimatedDeltaPx(
    firstIndex: Int,
    firstOffset: Int,
    targetIndex: Int,
    columns: Int,
    rowStridePx: Float,
): Float {
    val from = drawerGridLine(firstIndex, columns)
    val to = drawerGridLine(targetIndex, columns)
    return (to - from) * rowStridePx - firstOffset
}

internal fun drawerGridVisibleLineDeltaPx(lineTopY: Int, beforePadding: Int): Float =
    (lineTopY - beforePadding).toFloat()

internal fun drawerGridRowStridePx(
    sortedLineTops: List<Int>,
    fallbackItemHeight: Int,
    spacingPx: Float,
): Float {
    if (sortedLineTops.size >= 2) {
        val step = sortedLineTops[1] - sortedLineTops[0]
        if (step > 0) return step.toFloat()
    }
    return (fallbackItemHeight.toFloat() + spacingPx).coerceAtLeast(1f)
}

internal suspend fun LazyGridState.followDrawerLetter(
    targetIndex: Int,
    columns: Int,
    spacingPx: Float,
) {
    val total = layoutInfo.totalItemsCount
    if (total <= 0 || targetIndex < 0) return
    val target = targetIndex.coerceAtMost(total - 1)
    val col = columns.coerceAtLeast(1)
    val targetLine = drawerGridLine(target, col)

    if (layoutInfo.visibleItemsInfo.isEmpty()) {
        scrollToItem(target)
        return
    }

    if (
        drawerGridLine(firstVisibleItemIndex, col) == targetLine &&
        firstVisibleItemScrollOffset == 0
    ) {
        return
    }

    val approach = planDrawerGridApproachIndex(
        firstIndex = firstVisibleItemIndex,
        targetIndex = target,
        columns = col,
    )
    if (approach != null) {
        scrollToItem(approach)
        yield()
    }

    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) {
        scrollToItem(target)
        return
    }

    val delta = drawerGridFollowDeltaPx(info, targetLine, col, target, spacingPx)
    if (abs(delta) >= 0.5f) {
        animateScrollBy(
            delta,
            tween(DRAWER_FAST_SCROLL_MS, easing = AvaEaseOut),
        )
    }

    settleDrawerLetterLine(target, col, targetLine)
}

private fun LazyGridState.drawerGridFollowDeltaPx(
    info: LazyGridLayoutInfo,
    targetLine: Int,
    columns: Int,
    targetIndex: Int,
    spacingPx: Float,
): Float {
    val visible = info.visibleItemsInfo
    val visibleLineTop = visible
        .filter { drawerGridLine(it.index, columns) == targetLine }
        .minOfOrNull { it.offset.y }
    if (visibleLineTop != null) {
        return drawerGridVisibleLineDeltaPx(visibleLineTop, info.beforeContentPadding)
    }
    val stride = drawerGridRowStridePx(
        sortedLineTops = visible.map { it.offset.y }.distinct().sorted(),
        fallbackItemHeight = visible.first().size.height,
        spacingPx = spacingPx,
    )
    return drawerGridEstimatedDeltaPx(
        firstIndex = firstVisibleItemIndex,
        firstOffset = firstVisibleItemScrollOffset,
        targetIndex = targetIndex,
        columns = columns,
        rowStridePx = stride,
    )
}

private suspend fun LazyGridState.settleDrawerLetterLine(
    targetIndex: Int,
    columns: Int,
    targetLine: Int,
) {
    if (drawerGridLine(firstVisibleItemIndex, columns) != targetLine) {
        scrollToItem(targetIndex)
        return
    }
    val leftover = firstVisibleItemScrollOffset
    if (leftover != 0) {
        scrollBy(-leftover.toFloat())
    }
}
