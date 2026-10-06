package com.example.ava.ui

import android.content.Context
import android.os.Build
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.services.OverlayOrientation
import com.example.ava.services.OverlayZOrderCoordinator
import com.example.ava.ui.glass.LiquidGlass
import com.example.ava.ui.glass.LiquidGlassDrawable
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Soft Sliver in-app toast: flat solid bar above overlays.
 *
 * - No app icon; no touch / focus (unless an [onTap] action is supplied)
 * - Width follows screen with side margins (long copy can use more horizontal space)
 * - Text size scales with shortest screen side, clamped to a modest range
 * - Full message wraps — no ellipsize clamp
 * - Same [tag] replaces the current message instead of stacking
 * - `closable = true`: emphasized hint mode — larger type, ✕ button, long dwell
 */
object AvaToast {
    private const val TAG = "AvaToast"

    /** Untouchable by default; [showOnMain] drops FLAG_NOT_TOUCHABLE only when a tap action exists. */
    private const val BASE_WINDOW_FLAGS =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    private const val DEFAULT_DURATION_MS = 1600L
    private const val FADE_IN_MS = 120L
    private const val FADE_OUT_MS = 140L
    /** Same reel as overlay lyrics: outgoing climbs, incoming rises. */
    private const val TEXT_SCROLL_IN_MS = 110L
    private const val TEXT_SCROLL_OUT_MS = 80L
    private const val TEXT_SCROLL_PX = 8
    private const val MAX_DURATION_MS = 5600L

    /** Closable hint variant: bigger type, a ✕ button, and a long dwell so it is hard to miss.
     *  Still auto-hides eventually — the overlay window must not linger over other apps. */
    private const val CLOSABLE_DURATION_MS = 24_000L
    private const val CLOSABLE_TEXT_EMPHASIS = 1.3f

    /**
     * Text size range (sp), scaled from shortest side.
     * Phone (~360dp) ≈ 13sp; large panel grows toward [TEXT_MAX_SP], never tiny/huge.
     */
    private const val TEXT_MIN_SP = 12f
    private const val TEXT_MAX_SP = 18f
    private const val TEXT_REF_SP = 13f
    private const val TEXT_REF_SHORTEST_DP = 360f

    /** Side inset: fraction of screen width, then clamped (dp). */
    private const val SIDE_MARGIN_FRACTION = 0.06f
    private const val SIDE_MARGIN_MIN_DP = 20f
    private const val SIDE_MARGIN_MAX_DP = 48f

    private const val BOTTOM_MARGIN_MIN_DP = 56f
    private const val BOTTOM_MARGIN_MAX_DP = 96f
    private const val BOTTOM_MARGIN_FRACTION = 0.08f

    /** Explicit short / long durations for call-site migration from system Toast. */
    const val SHORT_MS = 1600L
    const val LONG_MS = 2800L

    /** HA rediscover + follow-up i18n (engine switch) share one sliver — text only, no restack. */
    const val HA_SYNC_TAG = "ha-sync"
    /** Mirror-blocked → freeform fallback: one sliver, scroll the second line. */
    const val APP_WINDOW_TAG = "app-window"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var host: FrameLayout? = null
    private var row: LinearLayout? = null
    private var labelStack: FrameLayout? = null
    private var label: TextView? = null
    private var outgoingLabel: TextView? = null
    private var closeLabel: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var hideRunnable: Runnable? = null
    private var lastTag: String? = null
    private var lastMetricsKey: Int = 0
    private var tapAction: (() -> Unit)? = null

    @JvmStatic
    @JvmOverloads
    fun show(
        context: Context,
        message: CharSequence,
        tag: String? = null,
        durationMs: Long = 0L,
        onTap: (() -> Unit)? = null,
        closable: Boolean = false,
    ) {
        val text = message.toString().trim()
        if (text.isEmpty()) return
        val app = context.applicationContext
        mainHandler.post {
            showOnMain(app, text, tag, durationMs, onTap, closable)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun show(
        context: Context,
        @StringRes resId: Int,
        tag: String? = null,
        durationMs: Long = 0L,
        onTap: (() -> Unit)? = null,
        closable: Boolean = false,
    ) {
        val app = context.applicationContext
        show(app, app.getString(resId), tag, durationMs, onTap, closable)
    }

    fun bringToFrontIfVisible() {
        val view = host ?: return
        if (view.visibility != View.VISIBLE || !view.isAttachedToWindow) return
        OverlayZOrderCoordinator.bringToFront(windowManager, view, params, TAG)
    }

    private fun showOnMain(
        app: Context,
        text: String,
        tag: String?,
        durationMs: Long,
        onTap: (() -> Unit)? = null,
        closable: Boolean = false,
    ) {
        if (!PlatformCapabilities.canDrawOverlays(app)) {
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
            return
        }

        val metrics = SoftSliverMetrics.from(app)
        if (!ensureHost(app, metrics)) {
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
            return
        }
        val view = host ?: return
        val tv = label ?: return
        val lp = params ?: return
        val rowView = row ?: return
        val closeView = closeLabel ?: return

        closeView.visibility = if (closable) View.VISIBLE else View.GONE
        applyMetrics(tv, closeView, rowView, lp, metrics, emphasized = closable)
        outgoingLabel?.let { applyLabelMetrics(it, metrics, emphasized = closable) }
        tapAction = onTap
        if (onTap != null || closable) {
            lp.flags = BASE_WINDOW_FLAGS and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags = BASE_WINDOW_FLAGS
        }
        OverlayZOrderCoordinator.applyNotTouchableWindowAlpha(lp)
        if (onTap != null) {
            // Child ✕ has its own listener and wins over the row, so close taps never navigate.
            rowView.setOnClickListener {
                val action = tapAction
                tapAction = null
                fadeOutAndHide()
                action?.invoke()
            }
        } else {
            rowView.setOnClickListener(null)
            rowView.isClickable = false
        }
        runCatching { windowManager?.updateViewLayout(view, lp) }

        lastTag = tag
        val resolvedDuration = if (closable) CLOSABLE_DURATION_MS else resolveDurationMs(text, durationMs)
        val previous = tv.text?.toString().orEmpty()
        val alreadyVisible = view.visibility == View.VISIBLE
        val shouldScroll = alreadyVisible && previous.isNotBlank() && previous != text
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        view.animate().cancel()

        if (shouldScroll) {
            view.alpha = 1f
            scrollText(previous, text)
        } else if (alreadyVisible) {
            cancelTextScroll()
            tv.text = text
            view.alpha = 1f
        } else {
            cancelTextScroll()
            tv.text = text
            view.alpha = 0f
            view.visibility = View.VISIBLE
            bringToFrontIfVisible()
            view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }

        hideRunnable = Runnable { fadeOutAndHide() }
        val dwell = if (shouldScroll) resolvedDuration + TEXT_SCROLL_IN_MS else resolvedDuration
        mainHandler.postDelayed(hideRunnable!!, dwell)
    }

    private fun scrollText(from: String, to: String) {
        val incoming = label ?: return
        val outgoing = outgoingLabel ?: return
        cancelTextScroll()
        outgoing.text = from
        outgoing.visibility = View.VISIBLE
        outgoing.alpha = 1f
        outgoing.translationY = 0f
        incoming.text = to
        incoming.alpha = 0f
        incoming.translationY = TEXT_SCROLL_PX.toFloat()
        val ease = DecelerateInterpolator()
        outgoing.animate()
            .translationY(-TEXT_SCROLL_PX.toFloat())
            .alpha(0f)
            .setDuration(TEXT_SCROLL_OUT_MS)
            .setInterpolator(ease)
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.translationY = 0f
                outgoing.alpha = 1f
            }
            .start()
        incoming.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(TEXT_SCROLL_IN_MS)
            .setInterpolator(ease)
            .start()
    }

    private fun cancelTextScroll() {
        label?.animate()?.cancel()
        outgoingLabel?.animate()?.cancel()
        label?.apply {
            translationY = 0f
            alpha = 1f
        }
        outgoingLabel?.apply {
            animate().setListener(null)
            visibility = View.GONE
            translationY = 0f
            alpha = 1f
        }
    }

    private fun fadeOutAndHide() {
        val view = host ?: return
        // Manual dismiss (✕ / tap) must also cancel the pending auto-hide.
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        hideRunnable = null
        cancelTextScroll()
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
                lastTag = null
                tapAction = null
            }
            .start()
    }

    private fun ensureHost(app: Context, metrics: SoftSliverMetrics): Boolean {
        if (host != null && host?.isAttachedToWindow == true && lastMetricsKey == metrics.key) {
            return true
        }
        // Screen size / density changed — rebuild so width & type size stay correct.
        tearDownHost()

        val wm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            app.getSystemService(WindowManager::class.java)
        } else {
            app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        } ?: return false
        windowManager = wm

        fun makeLabel(): TextView = TextView(app).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = Integer.MAX_VALUE
            ellipsize = null
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setLineSpacing(0f, 1.15f)
        }
        val textView = makeLabel()
        val outgoing = makeLabel().apply { visibility = View.GONE }
        label = textView
        outgoingLabel = outgoing
        val stack = FrameLayout(app).apply {
            clipChildren = true
            clipToPadding = true
            addView(
                outgoing,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            addView(
                textView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        labelStack = stack

        // Hidden for plain toasts; the closable hint variant reveals it. Its own
        // listener wins over the row's, so a close tap never triggers the action.
        val closeView = TextView(app).apply {
            text = "✕"
            setTextColor(Color.rgb(156, 163, 175))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            visibility = View.GONE
            setOnClickListener {
                tapAction = null
                fadeOutAndHide()
            }
        }
        closeLabel = closeView

        val rowView = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            LiquidGlass.ensureLoaded(app)
            background = if (LiquidGlass.enabled) {
                LiquidGlassDrawable(cornerRadiusPx = 0f, tint = 0xD9161A20.toInt(), windowBacked = true)
                    .also { it.setDensity(app.resources.displayMetrics.density) }
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.rgb(22, 26, 32))
                    setStroke(1, Color.rgb(48, 54, 64))
                }
            }
            if (LiquidGlass.enabled) {
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            addView(
                stack,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                closeView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        row = rowView

        val container = FrameLayout(app).apply {
            addView(
                rowView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        host = container

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            PlatformCapabilities.overlayWindowType(),
            BASE_WINDOW_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            title = TAG
            OverlayZOrderCoordinator.applyNotTouchableWindowAlpha(this)
            OverlayOrientation.apply(this)
        }
        params = lp
        applyMetrics(textView, closeView, rowView, lp, metrics, emphasized = false)
        lastMetricsKey = metrics.key

        return try {
            wm.addView(container, lp)
            OverlayZOrderCoordinator.noteWindowAdded()
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to add Soft Sliver toast window", e)
            tearDownHost()
            false
        }
    }

    private fun applyMetrics(
        textView: TextView,
        closeView: TextView,
        rowView: LinearLayout,
        lp: WindowManager.LayoutParams,
        m: SoftSliverMetrics,
        emphasized: Boolean,
    ) {
        val emphasis = if (emphasized) CLOSABLE_TEXT_EMPHASIS else 1f
        val padHPx = (m.padHPx * emphasis).roundToInt()
        val padVPx = (m.padVPx * emphasis).roundToInt()
        applyLabelMetrics(textView, m, emphasized)
        outgoingLabel?.let { applyLabelMetrics(it, m, emphasized) }
        closeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, m.textSp * emphasis)
        rowView.setPadding(padHPx, padVPx, padHPx, padVPx)
        // Gap before ✕ doubles as extra hit area for the close tap.
        closeView.setPadding(padHPx, 0, 0, 0)
        val closeReservePx = if (emphasized) (closeView.paint.textSize + padHPx).roundToInt() else 0
        textView.maxWidth = (m.maxTextWidthPx - padHPx * 2 - closeReservePx)
            .coerceAtLeast((m.maxTextWidthPx * 2) / 5)
        (rowView.background as? GradientDrawable)?.cornerRadius = m.cornerPx * emphasis
        (rowView.background as? GradientDrawable)?.setStroke(
            m.strokePx.coerceAtLeast(1),
            Color.rgb(48, 54, 64),
        )
        (rowView.background as? LiquidGlassDrawable)?.cornerRadiusPx = m.cornerPx * emphasis
        // Only the toast's own footprint is blurred (WRAP_CONTENT window).
        LiquidGlass.applyWindowBlur(lp, rowView.resources.displayMetrics.density)
        lp.y = m.bottomMarginPx
    }

    private fun applyLabelMetrics(textView: TextView, m: SoftSliverMetrics, emphasized: Boolean) {
        val emphasis = if (emphasized) CLOSABLE_TEXT_EMPHASIS else 1f
        val padHPx = (m.padHPx * emphasis).roundToInt()
        val closeReservePx = if (emphasized) {
            ((closeLabel?.paint?.textSize ?: 0f) + padHPx).roundToInt()
        } else {
            0
        }
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, m.textSp * emphasis)
        textView.maxWidth = (m.maxTextWidthPx - padHPx * 2 - closeReservePx)
            .coerceAtLeast((m.maxTextWidthPx * 2) / 5)
    }

    private fun tearDownHost() {
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        hideRunnable = null
        val view = host
        host = null
        row = null
        labelStack = null
        label = null
        outgoingLabel = null
        closeLabel = null
        params = null
        lastTag = null
        lastMetricsKey = 0
        tapAction = null
        if (view != null) {
            runCatching { windowManager?.removeView(view) }
        }
        windowManager = null
    }

    private fun resolveDurationMs(text: String, overrideMs: Long): Long {
        if (overrideMs > 0L) return overrideMs.coerceAtMost(MAX_DURATION_MS)
        val scaled = DEFAULT_DURATION_MS + text.length * 28L
        return scaled.coerceIn(SHORT_MS, MAX_DURATION_MS)
    }

    /**
     * Layout derived from current display.
     *
     * - Side margins grow on large screens so the bar stays inset, but the **content width**
     *   is `screenWidth - 2×margin` (not a fixed 300dp), so long i18n can expand left/right.
     * - Text: ~13sp at 360dp shortest side, scales with screen, clamped [12, 18]sp.
     */
    private data class SoftSliverMetrics(
        val key: Int,
        val textSp: Float,
        val maxTextWidthPx: Int,
        val padHPx: Int,
        val padVPx: Int,
        val cornerPx: Int,
        val strokePx: Int,
        val bottomMarginPx: Int,
    ) {
        companion object {
            fun from(app: Context): SoftSliverMetrics {
                val dm = app.resources.displayMetrics
                val density = dm.density.coerceAtLeast(0.75f)
                val widthPx = dm.widthPixels.coerceAtLeast(1)
                val heightPx = dm.heightPixels.coerceAtLeast(1)
                val shortestDp = min(widthPx, heightPx) / density

                val textSp = (TEXT_REF_SP * (shortestDp / TEXT_REF_SHORTEST_DP))
                    .coerceIn(TEXT_MIN_SP, TEXT_MAX_SP)

                val sideMarginDp = (widthPx / density * SIDE_MARGIN_FRACTION)
                    .coerceIn(SIDE_MARGIN_MIN_DP, SIDE_MARGIN_MAX_DP)
                val sideMarginPx = (sideMarginDp * density).roundToInt()
                val maxTextWidthPx = (widthPx - sideMarginPx * 2).coerceAtLeast((200f * density).roundToInt())

                val scale = (textSp / TEXT_REF_SP).coerceIn(0.92f, 1.35f)
                val padHPx = (14f * density * scale).roundToInt()
                val padVPx = (9f * density * scale).roundToInt()
                val cornerPx = (8f * density * scale.coerceAtMost(1.2f)).roundToInt()
                val strokePx = (1f * density).roundToInt().coerceAtLeast(1)

                val bottomMarginDp = (heightPx / density * BOTTOM_MARGIN_FRACTION)
                    .coerceIn(BOTTOM_MARGIN_MIN_DP, BOTTOM_MARGIN_MAX_DP)
                val bottomMarginPx = (bottomMarginDp * density).roundToInt()

                val key = widthPx xor (heightPx shl 1) xor (density * 100).roundToInt()
                return SoftSliverMetrics(
                    key = key,
                    textSp = textSp,
                    maxTextWidthPx = maxTextWidthPx,
                    padHPx = padHPx,
                    padVPx = padVPx,
                    cornerPx = cornerPx,
                    strokePx = strokePx,
                    bottomMarginPx = bottomMarginPx,
                )
            }
        }
    }
}
