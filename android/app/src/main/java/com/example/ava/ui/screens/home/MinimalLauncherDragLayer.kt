package com.example.ava.ui.screens.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

private const val DRAG_LAYER_TAG = "LauncherDragLayer"

/**
 * Launcher3 DragLayer / DragController essence for Compose:
 *
 * After [activateDrag], this layer must own the pointer stream. Compose's
 * AndroidComposeView keeps calling [requestDisallowInterceptTouchEvent](true),
 * which blocks [onInterceptTouchEvent] — so we take over in [dispatchTouchEvent]
 * and never call super while dragging (children / Pager cannot steal MOVE).
 */
class MinimalLauncherDragLayer(context: Context) : FrameLayout(context) {

    var isDragActive: Boolean = false
        private set

    var onDragMove: ((rawX: Float, rawY: Float) -> Unit)? = null
    var onDragEnd: ((rawX: Float, rawY: Float) -> Unit)? = null

    /**
     * Compose [AndroidView] can restore [View.VISIBLE] after [update]. Skipping
     * draw here is what actually keeps HostViews from painting above All Apps
     * and settings destinations (Mod Store Crossfade on API 28–29).
     */
    var suppressDraw: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) invalidate()
        }

    override fun draw(canvas: Canvas) {
        if (suppressDraw) return
        drawDroppingChildOrderFailures(canvas) { super.draw(canvas) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (suppressDraw) return
        drawDroppingChildOrderFailures(canvas) { super.dispatchDraw(canvas) }
    }

    override fun invalidate() {
        if (suppressDraw) return
        super.invalidate()
    }

    override fun invalidate(dirty: Rect?) {
        if (suppressDraw) return
        super.invalidate(dirty)
    }

    @Suppress("deprecation")
    override fun invalidate(l: Int, t: Int, r: Int, b: Int) {
        if (suppressDraw) return
        super.invalidate(l, t, r, b)
    }

    override fun invalidateChildInParent(location: IntArray?, dirty: Rect?): ViewParent? {
        if (suppressDraw) return null
        return super.invalidateChildInParent(location, dirty)
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        if (suppressDraw) return
        super.onDescendantInvalidated(child, target)
    }

    /**
     * Backstop for hosted widgets whose [ViewGroup.getChildDrawingOrder] races a
     * child removal. The HostView already drops the frame; this only runs if the
     * throw still reaches the workspace layer.
     */
    private fun drawDroppingChildOrderFailures(canvas: Canvas, draw: () -> Unit) {
        val saveCount = canvas.saveCount
        try {
            draw()
        } catch (e: IndexOutOfBoundsException) {
            runCatching { canvas.restoreToCount(saveCount) }
            Log.w(DRAG_LAYER_TAG, "Dropped workspace frame (child drawing order)", e)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isDragActive) return super.dispatchTouchEvent(ev)
        // Consume at the root — L3 DragController path.
        // Never end on CANCEL: Compose/Pager steal fires CANCEL and was dropping the drag
        // right after follow-finger started. Only ACTION_UP finishes the drop.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> onDragMove?.invoke(ev.rawX, ev.rawY)
            MotionEvent.ACTION_UP -> {
                val end = onDragEnd
                isDragActive = false
                end?.invoke(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_CANCEL -> Unit
        }
        return true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = isDragActive

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isDragActive) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> onDragMove?.invoke(ev.rawX, ev.rawY)
            MotionEvent.ACTION_UP -> {
                val end = onDragEnd
                isDragActive = false
                end?.invoke(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_CANCEL -> Unit
        }
        return true
    }

    fun activateDrag() {
        isDragActive = true
        // Clear FLAG_DISALLOW_INTERCEPT set by HostView/Compose on this layer.
        requestDisallowInterceptTouchEvent(false)
        // L3 DragLayer: keep ancestors from stealing the stream after startDrag.
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    fun deactivateDrag() {
        isDragActive = false
    }
}

/**
 * Compose host living under [MinimalLauncherDragLayer] so HostViews are true descendants.
 */
class MinimalLauncherDragLayerComposeHost(context: Context) : AbstractComposeView(context) {
    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        // Keep HostViews from painting outside the active pager page.
        clipChildren = true
        clipToPadding = true
    }

    var content: @Composable () -> Unit by mutableStateOf({})

    @Composable
    override fun Content() {
        content()
    }
}

/**
 * Launcher3 Workspace.createDragBitmap / drawDragView.
 *
 * Do NOT force SOFTWARE layer (blanks many AppWidgetHostViews). Do NOT discard
 * sparse widgets as "transparent" — that left DragView null while source went
 * INVISIBLE, so neither the seat nor the ghost was visible.
 */
fun captureDragPreviewBitmap(view: View): Bitmap? {
    val w = view.width
    val h = view.height
    if (w <= 0 || h <= 0) return null
    val restoreVisibility = view.visibility
    if (restoreVisibility != View.VISIBLE) {
        view.visibility = View.VISIBLE
    }
    try {
        // 1) L3 path: translate by scroll, view.draw — no layer-type thrash.
        drawViewToBitmap(view, w, h)?.takeUnless { isMostlyTransparent(it) }?.let { return it }

        // 2) Parent frame (Compose AndroidView wrapper) sometimes composites better.
        val parent = view.parent as? View
        if (parent != null && parent.width > 0 && parent.height > 0) {
            drawViewToBitmap(parent, parent.width, parent.height)
                ?.takeUnless { isMostlyTransparent(it) }
                ?.let { return it }
        }

        // 3) Pre-P drawing cache (still present on many API 21–27 devices).
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 28) {
            try {
                view.isDrawingCacheEnabled = true
                view.buildDrawingCache(true)
                val cache = view.drawingCache
                if (cache != null && !isMostlyTransparent(cache)) {
                    return cache.copy(Bitmap.Config.ARGB_8888, false)
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    view.isDrawingCacheEnabled = false
                    view.destroyDrawingCache()
                } catch (_: Throwable) {
                }
            }
        }
    } finally {
        if (restoreVisibility != View.VISIBLE) {
            view.visibility = restoreVisibility
        }
    }
    return null
}

/** Plate + app icon when HostView pixels cannot be snapshotted (HW RemoteViews). */
fun createWidgetDragFallbackBitmap(
    context: Context,
    packageName: String,
    widthPx: Int,
    heightPx: Int,
): Bitmap {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0xE62A2A2A.toInt())
    try {
        val icon = context.packageManager.getApplicationIcon(packageName)
        val iconSize = (minOf(w, h) * 0.42f).toInt().coerceIn(48, 192)
        val left = (w - iconSize) / 2
        val top = (h - iconSize) / 2
        icon.setBounds(left, top, left + iconSize, top + iconSize)
        icon.draw(canvas)
    } catch (_: Throwable) {
        // Solid plate is enough for a visible DragView.
    }
    return bitmap
}

private fun drawViewToBitmap(view: View, w: Int, h: Int): Bitmap? {
    return try {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(canvas)
        bitmap
    } catch (_: Throwable) {
        null
    }
}

private fun isMostlyTransparent(bitmap: Bitmap): Boolean {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return true
    var opaque = 0
    val stepX = (w / 8).coerceAtLeast(1)
    val stepY = (h / 8).coerceAtLeast(1)
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            if ((bitmap.getPixel(x, y) ushr 24) > 8) {
                opaque++
                // One opaque sample is enough — clock/weather widgets are sparse.
                if (opaque >= 1) return false
            }
            x += stepX
        }
        y += stepY
    }
    return true
}

fun View.findMinimalLauncherDragLayer(): MinimalLauncherDragLayer? {
    var p: Any? = parent
    while (p != null) {
        if (p is MinimalLauncherDragLayer) return p
        p = (p as? View)?.parent
    }
    return null
}

/**
 * All Apps / Widgets tray live outside the workspace [MinimalLauncherDragLayer] tree.
 * Workspace registers here so tray drags can hand off (L3 DragController owns MOVE/UP).
 */
object MinimalLauncherDragLayerRegistry {
    @Volatile
    var layer: MinimalLauncherDragLayer? = null

    /**
     * L3: DragView.show()+move() before original goes INVISIBLE.
     * False until overlay has a real screen origin — avoids a 1-frame ghost at (0,0).
     */
    var ghostReady: Boolean by mutableStateOf(false)

    /**
     * Screen-space bounds of the Remove/Uninstall/Info pill, measured by the drag
     * overlay. Drop-target hit tests use this exact rect so the bar never swallows
     * workspace cells around it (the old full-width top strip ate the whole top row).
     */
    @Volatile
    var dropBarRect: android.graphics.RectF? = null
}
