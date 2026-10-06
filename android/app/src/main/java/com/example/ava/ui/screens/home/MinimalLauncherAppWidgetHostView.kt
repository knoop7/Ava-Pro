package com.example.ava.ui.screens.home

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.hypot

private const val WIDGET_HOST_VIEW_TAG = "LauncherWidgetHostView"

/** First failure, then roughly every two seconds at 60fps. */
private const val WIDGET_DRAW_FAILURE_LOG_INTERVAL = 120

/**
 * Launcher3: long-press → startDrag immediately; DragLayer owns MOVE/UP.
 * Top Remove is SearchDropTargetBar — not an on-item bubble.
 */
class MinimalLauncherAppWidgetHostView(context: Context) : AppWidgetHostView(context) {

    init {
        // RemoteViews often draw past the host bounds; clip so paging neighbors
        // do not leave a ghost of the previous workspace page.
        clipChildren = true
        clipToPadding = true
    }

    var onWidgetLongPress: (() -> Unit)? = null
    var onWidgetDragStart: ((rawX: Float, rawY: Float) -> Unit)? = null
    var onWidgetDragMove: ((rawX: Float, rawY: Float) -> Unit)? = null
    var onWidgetDragEnd: ((rawX: Float, rawY: Float) -> Unit)? = null
    var onWidgetDragCancel: (() -> Unit)? = null

    var layoutLocked: Boolean = false
        set(value) {
            field = value
            if (value) resetGestureFlags()
        }

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        .coerceIn(250L, 500L)

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var longPressPosted = false
    private var hasPerformedLongPress = false
    private var handedOffToDragLayer = false
    private var drawFailures = 0

    private val longPressRunnable = Runnable {
        if (!longPressPosted || hasPerformedLongPress || handedOffToDragLayer) {
            return@Runnable
        }
        longPressPosted = false
        hasPerformedLongPress = true
        onWidgetLongPress?.invoke()
        // beginDragAt still rejects locked layouts. The long press itself remains
        // available so a locked desktop can reopen overview and be unlocked.
        beginDragAt(lastRawX, lastRawY)
    }

    fun clearWorkspaceCallbacks() {
        onWidgetLongPress = null
        onWidgetDragStart = null
        onWidgetDragMove = null
        onWidgetDragEnd = null
        onWidgetDragCancel = null
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                hasPerformedLongPress = false
                handedOffToDragLayer = false
                postLongPress()
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                if (handedOffToDragLayer) return false
                if (hasPerformedLongPress) return true
                if (hypot(ev.rawX - downRawX, ev.rawY - downRawY) > touchSlop) {
                    cancelLongPressCheck()
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (handedOffToDragLayer) return false
                val steal = hasPerformedLongPress
                if (!steal) cancelLongPressCheck()
                return steal
            }
        }
        return hasPerformedLongPress
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                hasPerformedLongPress = false
                handedOffToDragLayer = false
                postLongPress()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                if (handedOffToDragLayer) return false
                if (hasPerformedLongPress) return true
                if (hypot(ev.rawX - downRawX, ev.rawY - downRawY) > touchSlop) {
                    cancelLongPressCheck()
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                if (handedOffToDragLayer) {
                    resetGestureFlags()
                    return false
                }
                // Children already got the stream if they consumed DOWN. We only land here
                // when nobody did — AppWidgetHostView is not clickable itself, so a short
                // tap would otherwise vanish and the tile would never fire.
                val fireClick = !hasPerformedLongPress
                resetGestureFlags()
                if (fireClick) {
                    var handled = false
                    for (i in 0 until childCount) {
                        val child = getChildAt(i) ?: continue
                        if (child.performClick()) {
                            handled = true
                            break
                        }
                    }
                    if (!handled) performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // Expected when DragLayer takes the stream — do not end the drag session.
                if (handedOffToDragLayer || hasPerformedLongPress) {
                    cancelLongPressCheck()
                    hasPerformedLongPress = false
                    return true
                }
                onWidgetDragCancel?.invoke()
                resetGestureFlags()
                return true
            }
        }
        return hasPerformedLongPress
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        cancelLongPressCheck()
    }

    /**
     * Drops the frame instead of the process when a hosted widget fails to draw.
     *
     * RemoteViews may contain StackView / AdapterViewFlipper / AdapterViewAnimator, and
     * their framework `getChildDrawingOrder` can hand back an index for a child that was
     * already removed — `IndexOutOfBoundsException: getChildDrawingOrder() returned
     * invalid index N (child count is N)`. That is third-party widget content running
     * framework code, so the host cannot prevent it, only refuse to die from it.
     *
     * Wrap both [draw] and [dispatchDraw]: parents call `child.draw()`, and the throw
     * escapes `ViewGroup.drawChild` before its own `restoreToCount`. Leaving the stack
     * unbalanced would corrupt every later draw in this window.
     */
    override fun draw(canvas: Canvas) {
        drawDroppingWidgetFailures(canvas) { super.draw(canvas) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        drawDroppingWidgetFailures(canvas) { super.dispatchDraw(canvas) }
    }

    private fun drawDroppingWidgetFailures(canvas: Canvas, draw: () -> Unit) {
        val saveCount = canvas.saveCount
        try {
            draw()
        } catch (e: RuntimeException) {
            runCatching { canvas.restoreToCount(saveCount) }
            drawFailures++
            if (drawFailures == 1 || drawFailures % WIDGET_DRAW_FAILURE_LOG_INTERVAL == 0) {
                Log.w(
                    WIDGET_HOST_VIEW_TAG,
                    "Widget ${appWidgetInfo?.provider} failed to draw " +
                        "($drawFailures dropped frames)",
                    e,
                )
            }
        }
    }

    private fun beginDragAt(rawX: Float, rawY: Float) {
        if (handedOffToDragLayer || layoutLocked) return
        onWidgetDragStart?.invoke(rawX, rawY)
        val layer = findMinimalLauncherDragLayer()
        if (layer != null) {
            layer.activateDrag()
            handedOffToDragLayer = true
            // Re-assert after Compose applies session/page UI — first MOVE was lost when
            // a same-frame update stomped isDragActive or CANCEL raced the handoff.
            layer.post {
                if (handedOffToDragLayer) layer.activateDrag()
            }
        }
    }

    private fun postLongPress() {
        cancelLongPressCheck()
        longPressPosted = true
        handler.postDelayed(longPressRunnable, longPressTimeout)
    }

    private fun cancelLongPressCheck() {
        if (longPressPosted) {
            handler.removeCallbacks(longPressRunnable)
            longPressPosted = false
        }
    }

    private fun resetGestureFlags() {
        cancelLongPressCheck()
        hasPerformedLongPress = false
        handedOffToDragLayer = false
    }
}

class MinimalLauncherAppWidgetHost(
    context: Context,
    hostId: Int,
) : AppWidgetHost(context, hostId) {
    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?,
    ): AppWidgetHostView = MinimalLauncherAppWidgetHostView(context)
}

fun AppWidgetHostView.asMinimalLauncherHostView(): MinimalLauncherAppWidgetHostView? =
    this as? MinimalLauncherAppWidgetHostView

fun MinimalLauncherAppWidgetHostView.bindWorkspaceGestures(
    layoutLocked: Boolean,
    onLongPress: () -> Unit,
    onDragStart: (rawX: Float, rawY: Float) -> Unit,
    onDragMove: (rawX: Float, rawY: Float) -> Unit,
    onDragEnd: (rawX: Float, rawY: Float) -> Unit,
    onDragCancel: () -> Unit,
) {
    this.layoutLocked = layoutLocked
    onWidgetLongPress = onLongPress
    onWidgetDragStart = onDragStart
    onWidgetDragMove = onDragMove
    onWidgetDragEnd = onDragEnd
    onWidgetDragCancel = onDragCancel
    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
}
