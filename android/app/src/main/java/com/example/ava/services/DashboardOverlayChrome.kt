package com.example.ava.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.example.ava.R
import com.example.ava.settings.DreamClockFace
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.ui.AvaToast
import com.example.ava.ui.glass.LiquidGlass
import com.example.ava.ui.screens.home.HomeSidebarActions
import com.example.ava.utils.TouchSoundHelper
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * In-overlay «Back» chrome. Each fullscreen overlay hosts its own strip as the
 * last child of its existing FrameLayout — never a second WindowManager window.
 *
 * Hidden stays [View.GONE] in the tree (alpha 0) — still attached, never a
 * WindowManager restack. Reveal fades and eases a few dp downward *inside* the
 * strip; hide reverses that. The dock itself does not move in the host, so
 * content layouts and host `clipChildren` stay untouched.
 * [ViewGroup.bringChildToFront] keeps the strip above that overlay's content.
 * GONE avoids stretching WRAP_CONTENT shells (vinyl mini FAB).
 */
object DashboardOverlayChrome {
    const val AUTO_HIDE_MS = 5_000L
    const val SHOW_AUTO_HIDE_MS = 30_000L
    const val MEDIA_AUTO_HIDE_MS = 3_000L
    private const val FADE_IN_MS = 180L
    private const val FADE_OUT_MS = 140L
    /** Peek distance; skipped entirely when the top inset cannot hold it. */
    private const val SLIDE_DP = 6f
    private const val MIN_SLIDE_INSET_PX = 12
    private const val OVERLAY_EDGE_FRACTION = 0.05f
    private const val ICON_VMIN = 0.05f
    private const val ICON_MIN_DP = 16f
    private const val ICON_MAX_DP = 36f
    private const val TEXT_VMIN = 0.0361f
    private const val TEXT_MIN_SP = 12f
    private const val TEXT_MAX_SP = 24f
    private const val ICON_GAP_VMIN = 0.0139f
    private const val ICON_GAP_MIN_DP = 4f
    private const val ICON_GAP_MAX_DP = 14f
    private const val SHADOW_FADE_VMIN = 0.061f
    private const val SHADOW_FADE_MIN_DP = 18f
    private const val SHADOW_FADE_MAX_DP = 56f
    private const val DOCK_PAD_V_VMIN = 0.0278f
    private const val DOCK_PAD_V_MIN_DP = 8f
    private const val DOCK_PAD_V_MAX_DP = 18f
    private const val DOCK_PAD_START_VMIN = 0.0389f
    private const val DOCK_PAD_START_MIN_DP = 12f
    private const val DOCK_PAD_START_MAX_DP = 24f
    private const val DOCK_PAD_END_VMIN = 0.05f
    private const val DOCK_PAD_END_MIN_DP = 14f
    private const val DOCK_PAD_END_MAX_DP = 30f

    enum class Kind {
        WEATHER,
        DREAM_CLOCK,
        SIMPLE_CLOCK,
        QUICK_ENTITY,
        MEDIA_PLAYER,
    }

    internal data class ChromeLayoutSpec(
        val dockHeightPx: Int,
        val shadowFadePx: Int,
        val iconSizePx: Int,
        val textSizeSp: Float,
        val iconLabelGapPx: Int,
        val dockPadVPx: Int,
        val dockPadStartPx: Int,
        val dockPadEndPx: Int,
        val insetTopPx: Int,
        val insetStartPx: Int,
        val insetEndPx: Int,
        val stripHeightPx: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val docks = HashMap<Kind, OverlayBackDock>()
    private val shownStates: Map<Kind, MutableStateFlow<Boolean>> =
        Kind.values().associateWith { MutableStateFlow(false) }

    /** Media stats handle follows the player strip. Other overlays use [shownFlow]. */
    val stripVisible: StateFlow<Boolean> = shownStates.getValue(Kind.MEDIA_PLAYER).asStateFlow()

    private var appContext: Context? = null
    private var quickEntityLayoutLocked = false
    private var dreamClockSettingsLocked = false
    private var dreamClockFace: DreamClockFace? = null

    fun shownFlow(kind: Kind): StateFlow<Boolean> = shownStates.getValue(kind).asStateFlow()

    fun isShown(kind: Kind): Boolean = shownStates.getValue(kind).value

    fun attach(host: ViewGroup, kind: Kind) {
        val ctx = host.context.applicationContext
        appContext = ctx
        LiquidGlass.ensureLoaded(ctx)
        val existing = docks[kind]
        if (existing != null && existing.parent === host) {
            host.bringChildToFront(existing)
            return
        }
        val dock = existing ?: OverlayBackDock(host.context, kind).also { docks[kind] = it }
        (dock.parent as? ViewGroup)?.removeView(dock)
        host.addView(
            dock,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        host.bringChildToFront(dock)
        dock.applyMetrics()
        dock.applyContent()
    }

    fun bind(context: Context, kind: Kind) {
        appContext = context.applicationContext
        LiquidGlass.ensureLoaded(appContext!!)
        docks[kind]?.applyContent()
    }

    fun unbind(kind: Kind) {
        docks[kind]?.hide(animated = false)
    }

    fun onUserTouch(context: Context, kind: Kind, autoHideMs: Long = AUTO_HIDE_MS) {
        ScreensaverService.notifySmartAodInterrupt()
        QuickEntityOverlayService.notifySmartAodInterrupt()
        appContext = context.applicationContext
        reveal(kind, autoHideMs)
    }

    fun keepVisibleIfShown(kind: Kind, autoHideMs: Long = AUTO_HIDE_MS) {
        docks[kind]?.keepVisibleIfShown(autoHideMs)
    }

    fun hideIfTop(kind: Kind, animated: Boolean = true) {
        hide(kind, animated)
    }

    fun revealIfTop(kind: Kind, autoHideMs: Long = AUTO_HIDE_MS) {
        reveal(kind, autoHideMs)
    }

    fun revealOnShow(kind: Kind) {
        reveal(kind, SHOW_AUTO_HIDE_MS)
    }

    fun reveal(kind: Kind, autoHideMs: Long = AUTO_HIDE_MS) {
        docks[kind]?.reveal(autoHideMs)
    }

    fun hide(kind: Kind, animated: Boolean = true) {
        docks[kind]?.hide(animated)
    }

    /** Mass rail: hide the media strip only. */
    fun hide(animated: Boolean = true) {
        hide(Kind.MEDIA_PLAYER, animated)
    }

    /** No-op: the strip lives inside its overlay; z-order is [ViewGroup.bringChildToFront]. */
    fun bringToFront() {
        docks.values.forEach { dock ->
            (dock.parent as? ViewGroup)?.bringChildToFront(dock)
        }
    }

    fun syncQuickEntityLock(locked: Boolean) {
        if (quickEntityLayoutLocked == locked) {
            docks[Kind.QUICK_ENTITY]?.applyLockVisuals()
            return
        }
        quickEntityLayoutLocked = locked
        docks[Kind.QUICK_ENTITY]?.applyLockVisuals()
    }

    fun syncDreamClockLock(locked: Boolean) {
        if (dreamClockSettingsLocked == locked) {
            docks[Kind.DREAM_CLOCK]?.applyLockVisuals()
            return
        }
        dreamClockSettingsLocked = locked
        docks[Kind.DREAM_CLOCK]?.applyLockVisuals()
    }

    fun syncDreamClockFace(face: DreamClockFace) {
        if (dreamClockFace == face) return
        dreamClockFace = face
        docks[Kind.DREAM_CLOCK]?.applyContent()
    }

    internal fun publishShown(kind: Kind, shown: Boolean) {
        val flow = shownStates.getValue(kind)
        if (flow.value != shown) flow.value = shown
    }

    internal fun dreamClockShowsLock(): Boolean = when (dreamClockFace) {
        DreamClockFace.FILL, DreamClockFace.FLIP -> true
        DreamClockFace.MECHANICAL, null -> false
    }

    internal fun isKindLocked(kind: Kind): Boolean = when (kind) {
        Kind.QUICK_ENTITY -> quickEntityLayoutLocked
        Kind.DREAM_CLOCK -> dreamClockSettingsLocked
        else -> false
    }

    internal fun onLockPressed(kind: Kind) {
        val ctx = appContext ?: return
        when (kind) {
            Kind.QUICK_ENTITY -> {
                TouchSoundHelper.playClick(ctx)
                val next = !quickEntityLayoutLocked
                quickEntityLayoutLocked = next
                docks[kind]?.applyLockVisuals()
                QuickEntityOverlayService.getInstance()?.setLayoutLocked(next)
                    ?: run {
                        scope.launch {
                            QuickEntitySettingsStore(ctx.quickEntitySettingsStore).layoutLocked.set(next)
                        }
                    }
                AvaToast.show(
                    ctx,
                    if (next) R.string.quick_entity_layout_locked else R.string.quick_entity_layout_unlocked,
                    tag = "quick-entity-lock",
                )
            }
            Kind.DREAM_CLOCK -> {
                if (!dreamClockShowsLock()) return
                TouchSoundHelper.playClick(ctx)
                val next = !dreamClockSettingsLocked
                dreamClockSettingsLocked = next
                docks[kind]?.applyLockVisuals()
                DreamClockService.setSettingsLocked(next)
                scope.launch {
                    PlayerSettingsStore(ctx.playerSettingsStore).dreamClockSettingsLocked.set(next)
                }
                AvaToast.show(
                    ctx,
                    if (next) R.string.dream_clock_settings_locked else R.string.dream_clock_settings_unlocked,
                    tag = "dream-clock-lock",
                )
            }
            else -> return
        }
        docks[kind]?.keepVisibleIfShown(AUTO_HIDE_MS)
    }

    internal fun onBackPressed(kind: Kind) {
        val ctx = appContext ?: return
        TouchSoundHelper.playClick(ctx)
        dismissOverlayForKind(ctx, kind)
        scope.launch {
            when (kind) {
                Kind.WEATHER -> HomeSidebarActions.setWeatherVisible(
                    PlayerSettingsStore(ctx.playerSettingsStore),
                    false,
                )
                Kind.DREAM_CLOCK -> HomeSidebarActions.setDreamClockVisible(
                    PlayerSettingsStore(ctx.playerSettingsStore),
                    false,
                )
                Kind.SIMPLE_CLOCK -> HomeSidebarActions.setSimpleClockVisible(
                    PlayerSettingsStore(ctx.playerSettingsStore),
                    false,
                )
                Kind.QUICK_ENTITY -> HomeSidebarActions.setQuickEntityVisible(
                    ctx,
                    QuickEntitySettingsStore(ctx.quickEntitySettingsStore),
                    false,
                )
                Kind.MEDIA_PLAYER -> HomeSidebarActions.setVinylCoverDisplayVisible(
                    PlayerSettingsStore(ctx.playerSettingsStore),
                    false,
                )
            }
        }
        docks[kind]?.hide(animated = false)
    }

    private fun dismissOverlayForKind(context: Context, kind: Kind) {
        OverlayZOrderCoordinator.cancelScheduledVoiceRaise()
        when (kind) {
            Kind.WEATHER -> WeatherOverlayService.hide(context)
            Kind.DREAM_CLOCK -> DreamClockService.hide(context)
            Kind.SIMPLE_CLOCK -> ScreensaverService.hide(context)
            Kind.QUICK_ENTITY -> QuickEntityOverlayService.hide(context)
            Kind.MEDIA_PLAYER -> VinylCoverService.dismissViaChrome()
        }
    }

    internal fun computeLayoutSpec(view: View): ChromeLayoutSpec {
        val display = realDisplayMetrics(view.context)
        val vminPx = minOf(display.widthPixels, display.heightPixels).toFloat().coerceAtLeast(1f)
        val density = display.density.coerceAtLeast(0.75f)
        val scaledDensity = display.scaledDensity.coerceAtLeast(0.75f)
        val edgeInsetPx = (vminPx * OVERLAY_EDGE_FRACTION).roundToInt()
        var insetTop = edgeInsetPx
        var insetStart = edgeInsetPx
        var insetEnd = edgeInsetPx
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cutout = view.rootWindowInsets?.displayCutout
            if (cutout != null) {
                insetTop = maxOf(edgeInsetPx, cutout.safeInsetTop)
                insetStart = maxOf(edgeInsetPx, cutout.safeInsetLeft)
                insetEnd = maxOf(edgeInsetPx, cutout.safeInsetRight)
            }
        }
        val iconSizePx = clampPxFromVmin(vminPx, ICON_VMIN, ICON_MIN_DP, ICON_MAX_DP, density)
        val iconLabelGapPx = clampPxFromVmin(vminPx, ICON_GAP_VMIN, ICON_GAP_MIN_DP, ICON_GAP_MAX_DP, density)
        val dockPadVPx = clampPxFromVmin(vminPx, DOCK_PAD_V_VMIN, DOCK_PAD_V_MIN_DP, DOCK_PAD_V_MAX_DP, density)
        val dockPadStartPx = clampPxFromVmin(
            vminPx, DOCK_PAD_START_VMIN, DOCK_PAD_START_MIN_DP, DOCK_PAD_START_MAX_DP, density,
        )
        val dockPadEndPx = clampPxFromVmin(
            vminPx, DOCK_PAD_END_VMIN, DOCK_PAD_END_MIN_DP, DOCK_PAD_END_MAX_DP, density,
        )
        val shadowFadePx = clampPxFromVmin(
            vminPx, SHADOW_FADE_VMIN, SHADOW_FADE_MIN_DP, SHADOW_FADE_MAX_DP, density,
        )
        val textSizeSp = ((vminPx * TEXT_VMIN) / scaledDensity).coerceIn(TEXT_MIN_SP, TEXT_MAX_SP)
        val textLinePx = (textSizeSp * scaledDensity).roundToInt()
        val dockHeightPx = maxOf(iconSizePx, textLinePx) + dockPadVPx * 2
        return ChromeLayoutSpec(
            dockHeightPx = dockHeightPx,
            shadowFadePx = shadowFadePx,
            iconSizePx = iconSizePx,
            textSizeSp = textSizeSp,
            iconLabelGapPx = iconLabelGapPx,
            dockPadVPx = dockPadVPx,
            dockPadStartPx = dockPadStartPx,
            dockPadEndPx = dockPadEndPx,
            insetTopPx = insetTop,
            insetStartPx = insetStart,
            insetEndPx = insetEnd,
            stripHeightPx = insetTop + dockHeightPx + shadowFadePx,
        )
    }

    private fun clampPxFromVmin(
        vminPx: Float,
        fraction: Float,
        minDp: Float,
        maxDp: Float,
        density: Float,
    ): Int {
        val raw = (vminPx * fraction).roundToInt()
        val minPx = (minDp * density).roundToInt()
        val maxPx = (maxDp * density).roundToInt()
        return raw.coerceIn(minPx, maxPx).coerceAtLeast(1)
    }

    private fun realDisplayMetrics(context: Context): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            wm.defaultDisplay.getRealMetrics(metrics)
        } else {
            wm.defaultDisplay.getMetrics(metrics)
        }
        return metrics
    }

    internal const val FADE_IN = FADE_IN_MS
    internal const val FADE_OUT = FADE_OUT_MS
    internal const val SLIDE_DP_AMOUNT = SLIDE_DP
    internal const val MIN_SLIDE_INSET = MIN_SLIDE_INSET_PX
}

/**
 * Decoration strip: top of its host only. [GONE] when idle so it never
 * eats the overlay's layout or touches. Pills are the only click targets.
 * Peek/retract slides the pills a few dp only when the top inset can hold that
 * motion with a buffer. Otherwise fade only. The dock frame never translates.
 */
@SuppressLint("ViewConstructor")
private class OverlayBackDock(
    context: Context,
    private val kind: DashboardOverlayChrome.Kind,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide(animated = true) }
    private var lastSpec: DashboardOverlayChrome.ChromeLayoutSpec? = null
    private var live = false
    private var fadeGeneration = 0

    private val backIcon = ImageView(context).apply {
        isClickable = false
        isFocusable = false
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.chevron_left_24px)
    }
    private val exitLabel = TextView(context).apply {
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private val actionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = false
        isFocusable = false
        applyPillGlass()
        addView(backIcon, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(exitLabel, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val lockIcon = ImageView(context).apply {
        isClickable = false
        isFocusable = false
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.mdi_lock_open)
    }
    private val lockRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = false
        isFocusable = false
        visibility = GONE
        applyPillGlass()
        addView(lockIcon, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener { DashboardOverlayChrome.onLockPressed(kind) }
    }

    init {
        visibility = GONE
        alpha = 0f
        isClickable = false
        isFocusable = false
        clipToPadding = true
        clipChildren = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(
            actionRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
        addView(
            lockRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END),
        )
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applyMetrics()
            insets
        }
    }

    fun reveal(autoHideMs: Long) {
        (parent as? ViewGroup)?.bringChildToFront(this)
        handler.removeCallbacks(hideRunnable)
        fadeGeneration++
        live = true
        DashboardOverlayChrome.publishShown(kind, true)
        applyMetrics()
        applyContent()
        setPillsInteractive(true)
        cancelMotion()
        if (visibility == VISIBLE && alpha >= 0.99f && abs(actionRow.translationY) < 0.5f) {
            scheduleAutoHide(autoHideMs)
            return
        }
        if (visibility != VISIBLE || alpha <= 0.02f) {
            alpha = 0f
            snapMotion(hidden = true)
            visibility = VISIBLE
        }
        animateMotion(
            hidden = false,
            duration = DashboardOverlayChrome.FADE_IN,
            interpolator = DecelerateInterpolator(),
        )
        scheduleAutoHide(autoHideMs)
    }

    fun keepVisibleIfShown(autoHideMs: Long) {
        if (!live || visibility != VISIBLE) return
        (parent as? ViewGroup)?.bringChildToFront(this)
        scheduleAutoHide(autoHideMs)
    }

    fun hide(animated: Boolean) {
        handler.removeCallbacks(hideRunnable)
        live = false
        DashboardOverlayChrome.publishShown(kind, false)
        setPillsInteractive(false)
        if (!animated || visibility != VISIBLE || alpha <= 0.02f) {
            restHidden()
            return
        }
        val generation = ++fadeGeneration
        cancelMotion()
        animateMotion(
            hidden = true,
            duration = DashboardOverlayChrome.FADE_OUT,
            interpolator = AccelerateInterpolator(),
            onEnd = {
                if (generation == fadeGeneration && !live) {
                    restHidden()
                }
            },
        )
    }

    fun applyMetrics() {
        val spec = DashboardOverlayChrome.computeLayoutSpec(this)
        if (spec == lastSpec) return
        lastSpec = spec
        val density = resources.displayMetrics.density.coerceAtLeast(0.75f)
        elevation = 24f * density
        actionRow.setPadding(spec.dockPadStartPx, spec.dockPadVPx, spec.dockPadEndPx, spec.dockPadVPx)
        actionRow.elevation = 8f * density
        lockRow.setPadding(spec.dockPadVPx, spec.dockPadVPx, spec.dockPadVPx, spec.dockPadVPx)
        lockRow.elevation = 8f * density
        exitLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, spec.textSizeSp)
        sizeIcon(backIcon, spec.iconSizePx)
        sizeIcon(lockIcon, spec.iconSizePx)
        (exitLabel.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.marginStart = spec.iconLabelGapPx
            exitLabel.layoutParams = lp
        }
        actionRow.layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            spec.dockHeightPx,
            Gravity.TOP or Gravity.START,
        ).apply {
            leftMargin = spec.insetStartPx
            topMargin = spec.insetTopPx
        }
        applyLockRowLayout(spec)
        val lp = layoutParams
        if (lp != null && lp.height != spec.stripHeightPx) {
            lp.height = spec.stripHeightPx
            layoutParams = lp
        }
        applyStripColors()
    }

    fun applyContent() {
        val backLabel = context.getString(R.string.dashboard_overlay_exit)
        if (exitLabel.text?.toString() != backLabel) {
            exitLabel.text = backLabel
            actionRow.contentDescription = backLabel
        }
        lastSpec?.let { applyLockRowLayout(it) }
        applyLockVisuals()
        actionRow.setOnClickListener { DashboardOverlayChrome.onBackPressed(kind) }
    }

    fun applyLockVisuals() {
        lockIcon.setImageResource(
            if (DashboardOverlayChrome.isKindLocked(kind)) R.drawable.mdi_lock else R.drawable.mdi_lock_open,
        )
        lockIcon.imageTintList = ColorStateList.valueOf(0xCCFFFFFF.toInt())
    }

    private fun applyLockRowLayout(spec: DashboardOverlayChrome.ChromeLayoutSpec) {
        val showLock = when (kind) {
            DashboardOverlayChrome.Kind.QUICK_ENTITY -> true
            DashboardOverlayChrome.Kind.DREAM_CLOCK -> DashboardOverlayChrome.dreamClockShowsLock()
            else -> false
        }
        lockRow.visibility = if (showLock) VISIBLE else GONE
        if (!showLock) return
        lockRow.layoutParams = LayoutParams(
            spec.dockHeightPx,
            spec.dockHeightPx,
            Gravity.TOP or Gravity.END,
        ).apply {
            rightMargin = spec.insetEndPx
            topMargin = spec.insetTopPx
        }
    }

    private fun applyStripColors() {
        val titleColor = 0xCCFFFFFF.toInt()
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                0x38400000,
                0x30000000,
                0x24000000,
                0x18000000,
                0x0C000000,
                0x04000000,
                0x00000000,
            ),
        )
        backIcon.imageTintList = ColorStateList.valueOf(titleColor)
        exitLabel.setTextColor(titleColor)
        applyLockVisuals()
    }

    private fun setPillsInteractive(enabled: Boolean) {
        actionRow.isClickable = enabled
        actionRow.isFocusable = enabled
        lockRow.isClickable = enabled && lockRow.visibility == VISIBLE
        lockRow.isFocusable = lockRow.isClickable
    }

    private fun scheduleAutoHide(autoHideMs: Long) {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, autoHideMs.coerceAtLeast(0L))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (visibility != VISIBLE || alpha <= 0.02f || !live) return false
        return super.dispatchTouchEvent(ev)
    }

    private fun motionViews(): Array<View> = arrayOf(actionRow, lockRow)

    private fun slidePx(): Float {
        pinDockFrame()
        if (!isAttachedToWindow || width <= 0 || height <= 0) return 0f
        val spec = lastSpec ?: return 0f
        val parent = parent as? ViewGroup ?: return 0f
        if (parent !is FrameLayout) return 0f
        val parentLp = parent.layoutParams
        if (parentLp != null &&
            parentLp.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
            parent.height > 0 &&
            parent.height < spec.stripHeightPx * 2
        ) {
            return 0f
        }
        val inset = spec.insetTopPx
        if (inset < DashboardOverlayChrome.MIN_SLIDE_INSET) return 0f
        val density = resources.displayMetrics.density.coerceAtLeast(0.75f)
        val wanted = DashboardOverlayChrome.SLIDE_DP_AMOUNT * density
        val room = inset / 4f
        if (room < 2f) return 0f
        val hostClips = parent.clipChildren || parent.clipToPadding
        val cap = if (hostClips) minOf(room, inset / 5f) else room
        if (cap < 2f) return 0f
        return minOf(wanted, cap)
    }

    private fun pinDockFrame() {
        if (translationX != 0f) translationX = 0f
        if (translationY != 0f) translationY = 0f
    }

    private fun cancelMotion() {
        pinDockFrame()
        animate().cancel()
        motionViews().forEach { it.animate().cancel() }
    }

    private fun snapMotion(hidden: Boolean) {
        pinDockFrame()
        val slide = slidePx()
        val y = if (hidden && slide > 0f) -slide else 0f
        motionViews().forEach { it.translationY = y }
    }

    private fun animateMotion(
        hidden: Boolean,
        duration: Long,
        interpolator: android.view.animation.Interpolator,
        onEnd: (() -> Unit)? = null,
    ) {
        pinDockFrame()
        val slide = slidePx()
        val y = if (hidden && slide > 0f) -slide else 0f
        if (slide > 0f) {
            motionViews().forEach { view ->
                view.animate()
                    .translationY(y)
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .setListener(null)
                    .start()
            }
        } else {
            motionViews().forEach { it.translationY = 0f }
        }
        animate()
            .alpha(if (hidden) 0f else 1f)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .setListener(
                if (onEnd == null) {
                    null
                } else {
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            onEnd()
                        }
                    }
                },
            )
            .start()
    }

    private fun restHidden() {
        cancelMotion()
        snapMotion(hidden = true)
        alpha = 0f
        visibility = GONE
        setPillsInteractive(false)
    }

    private fun sizeIcon(icon: ImageView, size: Int) {
        val lp = (icon.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(size, size)
        lp.width = size
        lp.height = size
        icon.layoutParams = lp
    }

    private fun View.applyPillGlass() {
        val density = resources.displayMetrics.density
        LiquidGlass.applyTo(this, cornerRadiusPx = 999f * density).setDensity(density)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) applyMetrics()
    }
}

/**
 * Wakes dashboard chrome on touch without consuming gestures. Optional swipe-left callback is kept
 * for weather / clock transitions.
 */
class DashboardTouchListener(
    private val context: Context,
    private val kind: DashboardOverlayChrome.Kind,
    private val onSwipeLeft: (() -> Unit)? = null,
) : View.OnTouchListener {
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val swipeTriggerPx: Float
    private val swipeMaxVerticalPx: Float

    init {
        val real = realDisplayMetricsForTouch(context)
        val shortestPx = minOf(real.widthPixels, real.heightPixels).toFloat()
        swipeTriggerPx = shortestPx * 0.10f
        swipeMaxVerticalPx = shortestPx * 0.08f
    }

    private fun realDisplayMetricsForTouch(context: Context): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            wm.defaultDisplay.getRealMetrics(metrics)
        } else {
            wm.defaultDisplay.getMetrics(metrics)
        }
        return metrics
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        when (event?.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                DashboardOverlayChrome.onUserTouch(context, kind)
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (dx < -swipeTriggerPx && abs(dy) < swipeMaxVerticalPx) {
                    TouchSoundHelper.playClick(context)
                    onSwipeLeft?.invoke()
                    return true
                }
            }
        }
        return false
    }
}
