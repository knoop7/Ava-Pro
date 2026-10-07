package com.example.ava.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.example.ava.platform.DisplayClockProbe
import com.example.ava.services.OverlayLayerSplit
import com.example.ava.settings.SettingsStyleSession
import com.example.ava.settings.SettingsStyleSettings
import com.example.ava.settings.settingsStyleSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * iOS 26 Liquid Glass for the View / WindowManager world.
 *
 * Three layers make the look:
 *  1. **Backdrop blur** — cross-window [WindowManager.LayoutParams.FLAG_BLUR_BEHIND] on
 *     API 31+ (see [applyWindowBlur]); same-window BlurView through
 *     [com.example.ava.ui.FrostedGlassHost] where a window hosts its own backdrop;
 *     pre-31 in-app sidebars freeze a stack-blurred snapshot
 *     ([com.example.ava.utils.OverviewBackdropCapture]) instead of thinning the slab.
 *  2. **Material** — [LiquidGlassDrawable]: translucent tint, a specular rim that is
 *     brightest at the top-left light source, and a soft top sheen.
 *  3. **Motion** — blur-to-sharp reveals (Dream Clock, screensaver) already exist and
 *     read [LiquidGlass.enabled] to decide whether to blur at all.
 *
 * Everything scales with [SettingsStyleSession.liquidGlassIntensity] and collapses to a
 * flat translucent surface when [SettingsStyleSession.liquidGlassEnabled] is off.
 */
object LiquidGlass {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var loaded = false

    /**
     * Whether the system is actually rendering [WindowManager.LayoutParams.FLAG_BLUR_BEHIND]
     * right now. Many OEM / kiosk builds ship with cross-window blur off, and battery saver
     * can flip it at runtime. Tracked via the platform listener so window-backed glass can
     * frost up (Apple's "Reduce Transparency" behaviour) instead of turning into a bare
     * translucent rectangle.
     */
    @Volatile var crossWindowBlurAvailable: Boolean = false
        private set

    /** Last [applyWindowBlur] chose frost because this window's flags cannot take blur-behind. */
    @Volatile private var overlayFrostInsteadOfBlur = false

    /**
     * Overlay services can start before any Compose screen syncs the style session. Call
     * once with any context; the DataStore is mirrored into [SettingsStyleSession] so
     * later toggles restyle live windows. Idempotent.
     */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
        }
        val app = context.applicationContext
        val store = app.settingsStyleSettingsStore
        scope.launch {
            store.data.collectLatest { settings ->
                val splitWas = SettingsStyleSession.overlaySplitEnabled.value
                val leftWas = SettingsStyleSession.overlaySplitRatioLeft.value
                val rightWas = SettingsStyleSession.overlaySplitRatioRight.value
                SettingsStyleSession.syncFromSettings(settings)
                SettingsStyleSession.retainOverlaySplitFromDisk()
                if (
                    splitWas != settings.overlaySplitEnabled ||
                    leftWas != settings.overlaySplitRatioLeft ||
                    rightWas != settings.overlaySplitRatioRight
                ) {
                    OverlayLayerSplit.sync()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            crossWindowBlurAvailable = runCatching { wm?.isCrossWindowBlurEnabled == true }.getOrDefault(false)
            runCatching {
                wm?.addCrossWindowBlurEnabledListener { enabled -> crossWindowBlurAvailable = enabled }
            }
        }
        DisplayClockProbe.ensureStarted()
    }

    /**
     * True when a window-blur-backed surface has no real blur behind it (user turned blur
     * off, or the device refuses cross-window blur). The material then thickens its body so
     * text on top stays legible over busy content.
     */
    val frostWindowBacked: Boolean
        get() = enabled && (
            !blurEnabled ||
                !crossWindowBlurAvailable ||
                overlayFrostInsteadOfBlur ||
                DisplayClockProbe.shouldFrostInsteadOfBlur()
            )

    /**
     * Backdrop effect for same-window glass: Gaussian blur chained with a saturation boost.
     * Blurring averages colours and drains their saturation — the raw result reads as grey
     * plastic. Pushing saturation back (1.35–1.7× with intensity) is what makes it glass.
     * Returns null for radii that would not be visible.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun backdropEffect(radiusPx: Float): RenderEffect? {
        if (radiusPx <= 0.5f) return null
        val blur = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
        val saturation = piecewise(
            userIntensity,
            atZero = SATURATION_MIN,
            atHalf = SATURATION_MID,
            atFull = SATURATION_MAX,
        )
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        return RenderEffect.createChainEffect(
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix)),
            blur,
        )
    }

    val enabled: Boolean
        get() = SettingsStyleSession.liquidGlassEnabled.value

    /**
     * Enabled **and** allowed to blur. When the user turns [SettingsStyleSession.liquidGlassBlur]
     * off the material keeps its finish but every blur consumer collapses to 0 radius.
     */
    val blurEnabled: Boolean
        get() = enabled && SettingsStyleSession.liquidGlassBlur.value

    /** Specular bloom under the finger while a glass surface is pressed. */
    val pressGlow: Boolean
        get() = enabled && SettingsStyleSession.liquidGlassPressGlow.value

    /** Slider position 0..1, before the 50% = design-look remap. */
    val userIntensity: Float
        get() = SettingsStyleSession.liquidGlassIntensity.value
            .coerceIn(
                SettingsStyleSettings.LIQUID_GLASS_MIN_INTENSITY,
                SettingsStyleSettings.LIQUID_GLASS_MAX_INTENSITY,
            ) / 100f

    /**
     * 0f..1f material amount. Slider 50% is the design look (the old 70% default);
     * 100% is the old cap, so the extra headroom is spent on blur, not a harsher rim.
     */
    val intensity: Float
        get() = remapMaterialIntensity(userIntensity)

    /** Cross-window blur radius in px for [applyWindowBlur]. 0 when disabled. */
    fun windowBlurRadiusPx(density: Float): Int {
        if (!blurEnabled) return 0
        return (piecewise(
            userIntensity,
            atZero = WINDOW_BLUR_MIN_DP,
            atHalf = WINDOW_BLUR_MID_DP,
            atFull = WINDOW_BLUR_MAX_DP,
        ) * density).roundToInt()
    }

    /** Same-window BlurView / RenderEffect radius. 0 when disabled. */
    fun viewBlurRadiusPx(): Float {
        if (!blurEnabled) return 0f
        return piecewise(
            userIntensity,
            atZero = VIEW_BLUR_MIN_PX,
            atHalf = VIEW_BLUR_MID_PX,
            atFull = VIEW_BLUR_MAX_PX,
        )
    }

    /**
     * Pre-31 freeze-frame stack blur, matched to the same intensity slider as
     * [viewBlurRadiusPx]. [maxEdgePx] shrinks as intensity rises (downscale is
     * itself a blur); [radius] is the stack-blur kernel. Null when blur is off.
     */
    fun fakeBlurSpec(viewBlurRadiusPx: Float = viewBlurRadiusPx()): FakeBlurSpec? {
        if (viewBlurRadiusPx <= 0.5f) return null
        val t = sliderTFromViewBlur(viewBlurRadiusPx)
        return FakeBlurSpec(
            maxEdgePx = piecewise(
                t,
                atZero = FAKE_BLUR_EDGE_MIN_PX,
                atHalf = FAKE_BLUR_EDGE_MID_PX,
                atFull = FAKE_BLUR_EDGE_MAX_PX,
            ).roundToInt(),
            radius = piecewise(
                t,
                atZero = FAKE_BLUR_RADIUS_MIN,
                atHalf = FAKE_BLUR_RADIUS_MID,
                atFull = FAKE_BLUR_RADIUS_MAX,
            ).roundToInt().coerceIn(1, 25),
        )
    }

    /** 0..1 amount of frost for scrims, following the intensity slider. */
    fun fakeBlurAmount(viewBlurRadiusPx: Float): Float {
        if (viewBlurRadiusPx <= 0.5f) return 0f
        return sliderTFromViewBlur(viewBlurRadiusPx)
    }

    data class FakeBlurSpec(
        val maxEdgePx: Int,
        val radius: Int,
    ) {
        val quantKey: Int get() = (maxEdgePx / 16) * 100 + radius
    }

    /** RenderEffect radius used by blur-to-sharp reveals (Dream Clock, screensaver). */
    fun revealBlurRadiusPx(): Float {
        if (!blurEnabled) return 0f
        return piecewise(
            userIntensity,
            atZero = REVEAL_BLUR_MIN_PX,
            atHalf = REVEAL_BLUR_MID_PX,
            atFull = REVEAL_BLUR_MAX_PX,
        )
    }

    /**
     * Configure [params] for cross-window backdrop blur. Only the window's own bounds are
     * blurred, so call this for panels / pills / cards — never for a full-screen overlay,
     * or the whole screen behind it goes soft.
     *
     * Safe on every API level: below 31 the flag is stripped and nothing else changes.
     * The system honours the request only when the device has window blur enabled
     * (`WindowManager.isCrossWindowBlurEnabled`); otherwise the translucent material alone
     * still reads as glass.
     */
    fun applyWindowBlur(params: WindowManager.LayoutParams, density: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = windowBlurRadiusPx(density)
        val accept = radius > 0 &&
            crossWindowBlurAvailable &&
            windowAcceptsBlurBehind(params) &&
            !DisplayClockProbe.shouldFrostInsteadOfBlur()
        overlayFrostInsteadOfBlur = enabled && blurEnabled && !accept
        if (!accept) {
            clearWindowBlur(params)
            return
        }
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        params.blurBehindRadius = radius
        if (params.format != PixelFormat.TRANSLUCENT) params.format = PixelFormat.TRANSLUCENT
    }

    /**
     * Cross-window blur follows window flags and measured vsync skew,
     * not the device name. WRAP_CONTENT pills (toast / volume) bloom a
     * disc that slips vsync next to the FAB. FLAG_FULLSCREEN softens the
     * whole screen. A HAL clock that is more than 2ms ahead, or frames
     * that miss by 8ms, frosts instead. Bounded panels keep blur when
     * the system flag is on and the clock is stable.
     */
    private fun windowAcceptsBlurBehind(params: WindowManager.LayoutParams): Boolean {
        if (params.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0) return false
        val w = params.width
        val h = params.height
        if (w == WindowManager.LayoutParams.WRAP_CONTENT ||
            h == WindowManager.LayoutParams.WRAP_CONTENT
        ) {
            return false
        }
        return w != 0 && h != 0
    }

    /**
     * Strip [WindowManager.LayoutParams.FLAG_BLUR_BEHIND]. Floating overlays that paint
     * their own glass must call this: on some OEMs the flag blooms a soft disc behind
     * the window instead of staying inside its bounds.
     *
     * @return true when [params] actually changed.
     */
    fun clearWindowBlur(params: WindowManager.LayoutParams): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val hadFlag = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
        val hadRadius = params.blurBehindRadius != 0
        if (!hadFlag && !hadRadius) return false
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        params.blurBehindRadius = 0
        return true
    }

    /** Whether cross-window blur is actually rendered by this device right now. */
    fun isCrossWindowBlurAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        return runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
    }

    /** Elevation for the outer drop shadow — the cue that grounds glass on a flat page. */
    fun shadowElevationDp(): Float = lerp(SHADOW_MIN_DP, SHADOW_MAX_DP, intensity)

    /**
     * Convenience: install a [LiquidGlassDrawable] as [view]'s background and clip to it so
     * child ripples / images follow the rounded rim. Installs the press-glow touch observer
     * unless [withPressGlow] is false — the observer never consumes events, so clickable
     * rows / dials on top of the glass keep working untouched.
     *
     * @param windowBacked true when the host window uses [applyWindowBlur]; the material then
     *   frosts up automatically on devices that refuse cross-window blur.
     * @param ownedMaterial the host supplies the slab colour and alpha ([tint] as painted).
     *   Use this for floating windows that must not depend on backdrop blur.
     * @param shadow install a drop shadow through view elevation. Opt-in because elevation
     *   also re-orders siblings; only pass true where the view is already topmost and its
     *   parent does not clip children.
     */
    fun applyTo(
        view: View,
        cornerRadiusPx: Float,
        tint: Int = LiquidGlassDrawable.DEFAULT_DARK_TINT,
        light: Boolean = false,
        withPressGlow: Boolean = true,
        windowBacked: Boolean = false,
        ownedMaterial: Boolean = false,
        shadow: Boolean = false,
    ): LiquidGlassDrawable {
        val drawable = LiquidGlassDrawable(
            cornerRadiusPx,
            tint,
            light,
            windowBacked,
            ownedMaterial,
        )
        view.background = drawable
        // API 21–27: an outline with alpha 0 is an empty clip. [getOutline] keeps
        // alpha at 0 unless a shadow is requested, so clipToOutline hides the view
        // (the overlay «Back» pill never appears). Clip only where alpha is shadow-only.
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) {
            view.clipToOutline = true
            view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        } else {
            view.clipToOutline = false
        }
        if (shadow) applyShadow(view, light)
        if (withPressGlow) installPressGlow(view, drawable)
        return drawable
    }

    /**
     * Drop shadow via elevation. Light glass over a pale page needs only a whisper of
     * shadow (Apple lowers shadow opacity over solid light backgrounds); dark glass takes
     * a deeper one. Colours need API 28; below that the platform shadow is plain black.
     */
    fun applyShadow(view: View, light: Boolean) {
        val density = view.resources.displayMetrics.density
        (view.background as? LiquidGlassDrawable)?.castsShadow = true
        view.elevation = if (enabled) shadowElevationDp() * density else 0f
        view.invalidateOutline()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val alpha = if (light) SHADOW_ALPHA_LIGHT else SHADOW_ALPHA_DARK
            val color = Color.argb((alpha * 255).roundToInt(), 0, 0, 0)
            view.outlineAmbientShadowColor = color
            view.outlineSpotShadowColor = color
        }
    }

    /**
     * Observe touches on [view] and bloom the glass under the finger. Non-consuming:
     * `onTouch` always returns false so the view's own click / gesture handling and its
     * children are unaffected. Safe to call on already-interactive views.
     */
    fun installPressGlow(view: View, drawable: LiquidGlassDrawable? = view.background as? LiquidGlassDrawable) {
        val glass = drawable ?: return
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> glass.showPress(event.x, event.y)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> glass.clearPress()
            }
            false
        }
    }

    internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    /**
     * Slider 50% = the look we shipped at the old 70% default. 0% is a whisper;
     * 100% is the old material cap (blur uses a separate, taller curve).
     */
    internal fun remapMaterialIntensity(user01: Float): Float {
        val u = user01.coerceIn(0f, 1f)
        return if (u <= 0.5f) lerp(MATERIAL_AT_ZERO, MATERIAL_AT_HALF, u / 0.5f)
        else lerp(MATERIAL_AT_HALF, MATERIAL_AT_FULL, (u - 0.5f) / 0.5f)
    }

    /** 0 / 50 / 100 on the user slider. */
    private fun piecewise(user01: Float, atZero: Float, atHalf: Float, atFull: Float): Float {
        val u = user01.coerceIn(0f, 1f)
        return if (u <= 0.5f) lerp(atZero, atHalf, u / 0.5f)
        else lerp(atHalf, atFull, (u - 0.5f) / 0.5f)
    }

    /** Inverse of [viewBlurRadiusPx]'s 4 / 17 / 42 piecewise, so fake blur hits the same 50% look. */
    private fun sliderTFromViewBlur(radiusPx: Float): Float {
        val r = radiusPx.coerceIn(VIEW_BLUR_MIN_PX, VIEW_BLUR_MAX_PX)
        return if (r <= VIEW_BLUR_MID_PX) {
            0.5f * (r - VIEW_BLUR_MIN_PX) / (VIEW_BLUR_MID_PX - VIEW_BLUR_MIN_PX)
        } else {
            0.5f + 0.5f * (r - VIEW_BLUR_MID_PX) / (VIEW_BLUR_MAX_PX - VIEW_BLUR_MID_PX)
        }
    }

    /** Old default (70%) material — this is what slider 50% reproduces. */
    private const val MATERIAL_AT_ZERO = 0.18f
    private const val MATERIAL_AT_HALF = 0.70f
    private const val MATERIAL_AT_FULL = 1f

    // Blur: 50% matches the old 70% radius; 100% is a clearly stronger frost.
    private const val WINDOW_BLUR_MIN_DP = 8f
    private const val WINDOW_BLUR_MID_DP = 30f
    private const val WINDOW_BLUR_MAX_DP = 64f
    private const val VIEW_BLUR_MIN_PX = 4f
    private const val VIEW_BLUR_MID_PX = 17f
    private const val VIEW_BLUR_MAX_PX = 42f
    // Pre-31 snapshot blur. 0% stays almost sharp (large edge, 1px stack);
    // 50% is the design frost; 100% is a heavy downsample + kernel.
    private const val FAKE_BLUR_EDGE_MIN_PX = 640f
    private const val FAKE_BLUR_EDGE_MID_PX = 220f
    private const val FAKE_BLUR_EDGE_MAX_PX = 96f
    private const val FAKE_BLUR_RADIUS_MIN = 1f
    private const val FAKE_BLUR_RADIUS_MID = 11f
    private const val FAKE_BLUR_RADIUS_MAX = 24f
    private const val REVEAL_BLUR_MIN_PX = 12f
    private const val REVEAL_BLUR_MID_PX = 28f
    private const val REVEAL_BLUR_MAX_PX = 52f
    private const val SATURATION_MIN = 1.25f
    private const val SATURATION_MID = 1.60f
    private const val SATURATION_MAX = 1.95f
    private const val SHADOW_MIN_DP = 4f
    private const val SHADOW_MAX_DP = 10f
    /** Shadow opacity: whisper on light glass over pale pages, deeper on dark glass. */
    const val SHADOW_ALPHA_LIGHT = 0.14f
    const val SHADOW_ALPHA_DARK = 0.32f
}

/**
 * The Liquid Glass material as a [Drawable]: rounded rect with
 *  - a translucent [tint] whose alpha follows the user intensity,
 *  - a top sheen (white → transparent over the upper 55%),
 *  - a specular rim: 1dp stroke lit from the top-left, dimming toward the bottom-right,
 *  - a faint inner bottom shadow so the slab reads as having thickness.
 *
 * When Liquid Glass is disabled it degrades to the flat tint + hairline rim that the
 * overlays used before, so layouts never change — only the finish does.
 */
class LiquidGlassDrawable(
    cornerRadiusPx: Float,
    private val tint: Int = DEFAULT_DARK_TINT,
    /** Light glass (for light-mode settings) uses a darker rim and stronger white fill. */
    private val light: Boolean = false,
    /**
     * The host window relies on [LiquidGlass.applyWindowBlur]. When that blur is not
     * actually rendered (user switched it off, or the device refuses cross-window blur)
     * the body frosts up so foreground text stays legible — the same move iOS makes
     * under "Reduce Transparency".
     */
    private val windowBacked: Boolean = false,
    /**
     * Paint [tint] as the body (RGB + alpha). Rim, lift and press glow still follow
     * intensity. Floating overlays use this so the slab is theirs, not a hole waiting
     * for [WindowManager.LayoutParams.FLAG_BLUR_BEHIND].
     */
    private val ownedMaterial: Boolean = false,
) : Drawable() {

    /** Mutable so hosts with metric-driven radii (toast, pills) can retune without rebuilding. */
    var cornerRadiusPx: Float = cornerRadiusPx
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Uniform luminous lift over the fill (glass only); flat color, no gradient. */
    private val liftPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val rimRect = RectF()
    private val bevelRect = RectF()
    private val clipPath = android.graphics.Path()
    private var shaderKey = -1L
    private var density = 1f

    // Press glow: a specular bloom that follows the finger. Progress 0..1 is animated so
    // the light swells under the touch and melts away on release. The radial shader and
    // the clip path are cached — only rebuilt when the (quantized) touch point, size or
    // intensity changes — while the animation itself just modulates the paint alpha, so
    // a press never allocates per frame on low-end hardware.
    private var pressProgress = 0f
    private var pressAnimator: android.animation.ValueAnimator? = null
    private var pressX = 0f
    private var pressY = 0f
    private var pressShaderKey = -1L
    private var clipPathKey = -1L

    /** Bloom the glass at ([x], [y]) in view coordinates. No-op when press glow is off. */
    fun showPress(x: Float, y: Float) {
        pressX = x
        pressY = y
        animatePressTo(1f, PRESS_IN_MS)
    }

    /** Melt the press glow away. Safe to call without a prior [showPress]. */
    fun clearPress() {
        animatePressTo(0f, PRESS_OUT_MS)
    }

    private fun animatePressTo(target: Float, durationMs: Long) {
        pressAnimator?.cancel()
        if (!LiquidGlass.enabled) {
            pressProgress = 0f
            invalidateSelf()
            return
        }
        if (target == pressProgress) return
        pressAnimator = android.animation.ValueAnimator.ofFloat(pressProgress, target).apply {
            duration = durationMs
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener {
                pressProgress = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    fun setDensity(value: Float) {
        if (density != value) {
            density = value
            shaderKey = -1L
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        rect.set(bounds)
        val glass = LiquidGlass.enabled
        val t = if (glass) LiquidGlass.intensity else 0f
        val frosted = glass && !ownedMaterial && windowBacked && LiquidGlass.frostWindowBacked
        rebuildIfNeeded(rect, glass, t, frosted)

        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, fillPaint)
        if (glass) {
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, liftPaint)
        }
        if (glass && LiquidGlass.pressGlow && pressProgress > 0.005f) drawPressGlow(canvas, t)
        // Two-tone glass edge. Light: a dark outer lip on the silhouette, then the
        // white specular rim inset inside it — two distinct layers, so the pale slab
        // actually reads on a pale page. Dark: white rim at the edge, inner bevel
        // just inside. Same-path stacking used to hide the dark lip under the white.
        if (glass && light && Color.alpha(bevelPaint.color) > 2 && bevelPaint.shader == null) {
            val outerHalf = bevelPaint.strokeWidth / 2f
            bevelRect.set(
                rect.left + outerHalf,
                rect.top + outerHalf,
                rect.right - outerHalf,
                rect.bottom - outerHalf,
            )
            val outerRadius = (cornerRadiusPx - outerHalf).coerceAtLeast(0f)
            canvas.drawRoundRect(bevelRect, outerRadius, outerRadius, bevelPaint)
            val rimInset = bevelPaint.strokeWidth + rimPaint.strokeWidth / 2f
            rimRect.set(
                rect.left + rimInset,
                rect.top + rimInset,
                rect.right - rimInset,
                rect.bottom - rimInset,
            )
            val rimRadius = (cornerRadiusPx - rimInset).coerceAtLeast(0f)
            canvas.drawRoundRect(rimRect, rimRadius, rimRadius, rimPaint)
        } else {
            val inset = rimPaint.strokeWidth / 2f
            rimRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            val rimRadius = (cornerRadiusPx - inset).coerceAtLeast(0f)
            if (glass && bevelPaint.shader != null) {
                val bevelInset = inset + bevelPaint.strokeWidth / 2f
                bevelRect.set(
                    rect.left + bevelInset,
                    rect.top + bevelInset,
                    rect.right - bevelInset,
                    rect.bottom - bevelInset,
                )
                val bevelRadius = (cornerRadiusPx - bevelInset).coerceAtLeast(0f)
                canvas.drawRoundRect(bevelRect, bevelRadius, bevelRadius, bevelPaint)
            }
            canvas.drawRoundRect(rimRect, rimRadius, rimRadius, rimPaint)
        }
    }

    /**
     * Radial specular bloom centred on the last touch, clipped to the rounded body.
     * Deliberately soft: a wide, dim falloff rather than a hot spot. The shader is keyed
     * on an 8px-quantized touch point (a subtle step no one feels, far fewer rebuilds),
     * and the press animation only scales the paint alpha.
     */
    private fun drawPressGlow(canvas: Canvas, t: Float) {
        // Soft radius — not too wide, like a gentle fingerprint on glass
        val radius = (maxOf(rect.width(), rect.height()) * 0.4f)
            .coerceIn(50f * density, 110f * density)
        val qx = (pressX / 8f).toInt()
        val qy = (pressY / 8f).toInt()
        val glowKey = qx.toLong() or (qy.toLong() shl 20) or
            ((radius.toRawBits().toLong() and 0xFFFFFL) shl 40) or
            ((t * 10).toLong() shl 60) xor (if (light) 1L else 0L)
        if (glowKey != pressShaderKey) {
            pressShaderKey = glowKey
            // Very subtle glow — glass feel: light touch leaves barely visible warmth
            val peak = if (light) {
                LiquidGlass.lerp(0.012f, 0.028f, t)
            } else {
                LiquidGlass.lerp(0.015f, 0.035f, t)
            }
            pressPaint.shader = RadialGradient(
                qx * 8f, qy * 8f, radius,
                intArrayOf(
                    Color.argb((peak * 255).roundToInt(), 255, 255, 255),
                    Color.argb((peak * 30).roundToInt(), 240, 245, 255),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.30f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        pressPaint.alpha = (pressProgress * 255).roundToInt()
        val pathKey = (rect.width().toLong() shl 32) xor rect.height().toLong() xor
            cornerRadiusPx.toRawBits().toLong()
        if (pathKey != clipPathKey) {
            clipPathKey = pathKey
            clipPath.reset()
            clipPath.addRoundRect(rect, cornerRadiusPx, cornerRadiusPx, android.graphics.Path.Direction.CW)
        }
        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, pressPaint)
        canvas.restoreToCount(save)
    }

    private fun rebuildIfNeeded(r: RectF, glass: Boolean, t: Float, frosted: Boolean) {
        val key = (r.width().toLong() shl 32) xor r.height().toLong() xor
            ((t * 1000).toLong() shl 20) xor (if (glass) 1L shl 60 else 0L) xor
            (if (light) 1L shl 61 else 0L) xor (if (frosted) 1L shl 62 else 0L) xor
            (if (ownedMaterial) 1L shl 59 else 0L)
        if (key == shaderKey) return
        shaderKey = key

        val baseAlpha = Color.alpha(tint) / 255f
        // Body: a thin, even tint — the backdrop blur, rim and shadow carry the look, not
        // the fill. Light glass is pure white (a grey-white on a white page only dims);
        // dark glass is a lifted charcoal, not near-black, so it sits *above* a dark page.
        // Intensity no longer thins the body; it drives blur / rim / shadow instead.
        // Frosted (window blur unavailable): thicken so foreground text stays legible.
        // Dark glass is a smoked black-grey: deeper, near-neutral body at a denser alpha.
        // A mid-alpha blue-grey plus a white lift reads as dishwater grey over content.
        // Owned material: the host already chose the slab; do not replace it with frost
        // or the window-backed light fill.
        val fillAlpha = when {
            ownedMaterial -> baseAlpha
            glass && frosted -> if (light) 0.72f else 0.82f
            glass && light -> 0.30f
            glass -> (baseAlpha * 1.1f).coerceIn(0.52f, 0.72f)
            else -> baseAlpha
        }
        fillPaint.shader = null
        fillPaint.color = ColorUtils.setAlphaComponent(
            when {
                ownedMaterial || !glass -> tint
                light -> 0xFFFFFFFF.toInt()
                else -> ColorUtils.blendARGB(tint or 0xFF000000.toInt(), 0xFF0F1114.toInt(), 0.7f)
            },
            (fillAlpha * 255).roundToInt(),
        )
        // Even luminous lift across the whole face. Kept very low on dark glass — the rim
        // and shadow define the slab; a stronger lift only greys it.
        liftPaint.shader = null
        liftPaint.color = if (glass) {
            ColorUtils.setAlphaComponent(
                0xFFFFFFFF.toInt(),
                (LiquidGlass.lerp(if (light) 0.04f else 0.025f, if (light) 0.06f else 0.045f, t) * 255).roundToInt(),
            )
        } else {
            Color.TRANSPARENT
        }

        rimPaint.strokeWidth = (if (glass) 1.35f else 1f) * density
        bevelPaint.strokeWidth = (if (glass) 1f else 0f) * density
        if (glass) {
            val rimBright = LiquidGlass.lerp(if (light) 0.50f else 0.26f, if (light) 0.85f else 0.55f, t)
            val hi = Color.argb((rimBright * 255).roundToInt(), 255, 255, 255)
            val lo = Color.argb(
                (rimBright * (if (light) 0.70f else 0.18f) * 255).roundToInt(),
                255, 255, 255,
            )
            val hi2 = Color.argb(
                (rimBright * (if (light) 0.82f else 0.60f) * 255).roundToInt(),
                255, 255, 255,
            )
            // Specular rim: the light source sits top-left, so the highlight is brightest
            // on the top-left arc, falls off across the middle, and picks up a weaker
            // second glint at the bottom-right — light travelling around the silhouette.
            rimPaint.shader = LinearGradient(
                r.left, r.top, r.right, r.bottom,
                intArrayOf(hi, lo, hi2),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            // Inner hairline tucked inside the rim — dark glass only. On light glass a
            // black lower-edge wash reads as a dirty band on a tall sidebar, not thickness.
            if (!light) {
                val bevelAlpha = LiquidGlass.lerp(0.05f, 0.09f, t)
                bevelPaint.shader = LinearGradient(
                    r.left, r.top + r.height() * 0.4f, r.left, r.bottom,
                    ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (bevelAlpha * 0.35f * 255).roundToInt()),
                    ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (bevelAlpha * 255).roundToInt()),
                    Shader.TileMode.CLAMP,
                )
            } else {
                // Outer dark lip, drawn on its own path outside the white rim.
                bevelPaint.shader = null
                bevelPaint.strokeWidth = 1.2f * density
                bevelPaint.color = ColorUtils.setAlphaComponent(
                    0xFF1E293B.toInt(),
                    (LiquidGlass.lerp(0.18f, 0.28f, t) * 255).roundToInt(),
                )
            }
        } else {
            rimPaint.shader = null
            rimPaint.color = if (light) 0x1A000000 else 0x14FFFFFF
        }
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        shaderKey = -1L
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        liftPaint.alpha = alpha
        rimPaint.alpha = alpha
        bevelPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /**
     * Set by [LiquidGlass.applyShadow]. Outline alpha scales the platform shadow, so hosts
     * that already carry elevation for z-order keep their shadow-free look unless they opt in.
     */
    var castsShadow: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    override fun getOutline(outline: android.graphics.Outline) {
        outline.setRoundRect(bounds, cornerRadiusPx)
        // Never 1.0: an opaque outline lets the compositor skip the backdrop.
        // In light mode that skipped region is the window's white background, so
        // translucent glass reads as a solid white sheet behind scrolling rows.
        outline.alpha = if (castsShadow && LiquidGlass.enabled) 0.99f else 0f
    }

    companion object {
        /** Deep navy-black, matches the legacy `dashboard_overlay_dock_bg` body. */
        const val DEFAULT_DARK_TINT = 0x66121824
        /** Frosted white — thin enough that a daytime page still reads through. */
        const val DEFAULT_LIGHT_TINT = 0x66F2F4F8.toInt()

        private const val PRESS_IN_MS = 100L
        private const val PRESS_OUT_MS = 60L
    }
}
