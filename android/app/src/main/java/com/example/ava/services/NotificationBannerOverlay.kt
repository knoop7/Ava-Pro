package com.example.ava.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.content.res.ColorStateList
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.core.widget.ImageViewCompat
import com.example.ava.R
import com.example.ava.notifications.FontAwesomeHelper
import com.example.ava.notifications.NotificationScene
import com.example.ava.settings.DarkModeManager
import com.example.ava.settings.NotificationSettings
import com.example.ava.ui.glass.LiquidGlass
import com.example.ava.ui.glass.LiquidGlassDrawable
import com.example.ava.utils.BlurCompat

/**
 * Compact banner (displayStyle = banner).
 *
 * Expand follows AOSP notification grammar:
 * - thin chevron at the **end of the title row**
 * - collapsed = 1-line body; expanded = card grows downward
 * - height + chevron rotation animated (system-like ease)
 * - header/card tap toggles when expandable
 *
 * White / tinted color language stays the original banner look.
 */
internal class NotificationBannerOverlay(private val context: Context) {

    var root: FrameLayout? = null
        private set
    var windowParams: WindowManager.LayoutParams? = null
        private set

    /** Fired during/after size changes so the host can [WindowManager.updateViewLayout]. */
    var onLayoutChanged: (() -> Unit)? = null

    /** Fired when the user toggles expand (host may refresh auto-hide). */
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    private var shell: FrameLayout? = null
    private var card: FrameLayout? = null
    private var contentRow: LinearLayout? = null
    private var iconChip: TextView? = null
    private var iconChipBg: View? = null
    private var titleView: TextView? = null
    private var msgView: TextView? = null
    private var logoView: ImageView? = null
    private var expandBtn: ImageView? = null
    private var cardCornerPx = 0f
    private var edgeMarginPx = 0
    private var scaleCached = 1f
    private var iconOriginX = 0f
    private var iconOriginY = 0f
    private var expanded = false
    private var bodyCanExpand = false
    private var heightAnimator: ValueAnimator? = null
    private var darkTheme = false

    private val expandInterpolator =
        PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f) // close to system expand ease

    fun ensureAttached(windowManager: WindowManager) {
        if (root != null) return
        val density = context.resources.displayMetrics.density
        val scale = bannerScale()
        scaleCached = scale
        fun d(dp: Float) = (dp * density * scale).toInt()
        fun sp(value: Float) = value * scale

        val widthPx = d(348f)
        val padH = d(14f)
        val padV = d(14f)
        val chip = d(48f)
        val logoSize = d(140f)
        cardCornerPx = 26f * density * scale
        edgeMarginPx = d(16f)
        iconOriginX = padH + chip / 2f
        iconOriginY = padV + d(1f) + chip / 2f

        val shellView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(widthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
            elevation = 0f
        }

        val logo = ImageView(context).apply {
            val lp = FrameLayout.LayoutParams(logoSize, logoSize)
            lp.gravity = Gravity.BOTTOM or Gravity.END
            lp.rightMargin = -d(30f)
            lp.bottomMargin = -d(34f)
            layoutParams = lp
            alpha = 0.12f
            scaleType = ImageView.ScaleType.FIT_CENTER
            try {
                context.assets.open("ha_logo.png").use { stream ->
                    setImageBitmap(android.graphics.BitmapFactory.decodeStream(stream))
                }
            } catch (_: Exception) {
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(padH, padV, d(10f), padV)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val cardView = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Watermark is oversized on purpose; it must not stretch wrap_content.
                measureChildWithMargins(row, widthMeasureSpec, 0, heightMeasureSpec, 0)
                val rlp = row.layoutParams as MarginLayoutParams
                val width = row.measuredWidth + rlp.leftMargin + rlp.rightMargin +
                    paddingLeft + paddingRight
                val height = row.measuredHeight + rlp.topMargin + rlp.bottomMargin +
                    paddingTop + paddingBottom
                measureChildWithMargins(logo, widthMeasureSpec, 0, heightMeasureSpec, 0)
                setMeasuredDimension(
                    resolveSize(width.coerceAtLeast(suggestedMinimumWidth), widthMeasureSpec),
                    resolveSize(height.coerceAtLeast(suggestedMinimumHeight), heightMeasureSpec),
                )
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            clipChildren = true
            clipToPadding = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = if (cardCornerPx > 0f) cardCornerPx else 26f * density * scale
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            background = if (LiquidGlass.enabled) {
                LiquidGlassDrawable(
                    cornerRadiusPx = cardCornerPx,
                    tint = LiquidGlassDrawable.DEFAULT_LIGHT_TINT,
                    light = true,
                    windowBacked = true,
                ).also { it.setDensity(density) }
            } else {
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.WHITE, Color.parseColor("#f6f7f9")),
                ).also { it.cornerRadius = cardCornerPx }
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleExpanded(animated = true) }
        }
        cardView.addView(logo)
        logoView = logo

        val chipBox = FrameLayout(context).apply {
            val lp = LinearLayout.LayoutParams(chip, chip)
            lp.rightMargin = d(12f)
            lp.topMargin = d(1f)
            layoutParams = lp
        }
        val chipBg = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            background = GradientDrawable().also {
                it.shape = GradientDrawable.RECTANGLE
                it.cornerRadius = 14f * density * scale
                it.setColor(Color.argb((0.22f * 255).toInt(), 245, 158, 11))
                it.setStroke(
                    maxOf(1, (1f * density * scale).toInt()),
                    Color.argb((0.18f * 255).toInt(), 245, 158, 11),
                )
            }
        }
        chipBox.addView(chipBg)
        iconChipBg = chipBg

        val fa = FontAwesomeHelper.loadFont(context)
        val icon = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            gravity = Gravity.CENTER
            typeface = fa
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(22f))
            setTextColor(Color.parseColor("#f59e0b"))
            text = FontAwesomeHelper.getIconChar("fa-bell")
        }
        chipBox.addView(icon)
        iconChip = icon
        row.addView(chipBox)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val title = TextView(context).apply {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16f))
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0f172a"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // AOSP-style expand at end of title row — thin chevron, rotates on expand.
        val expand = ImageView(context).apply {
            val size = d(28f)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.leftMargin = d(2f)
            layoutParams = lp
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(
                ContextCompat.getDrawable(context, R.drawable.ic_notification_expand_chevron),
            )
            ImageViewCompat.setImageTintList(
                this,
                ColorStateList.valueOf(Color.parseColor("#64748b")),
            )
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            contentDescription = "Expand"
            setOnClickListener { toggleExpanded(animated = true) }
            // Slight top pad matches AOSP expand button optical alignment with title.
            setPadding(d(4f), d(2f), d(4f), d(2f))
            rotation = 0f
        }

        titleRow.addView(title)
        titleRow.addView(expand)
        titleView = title
        expandBtn = expand

        val msg = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13.5f))
            setTextColor(Color.parseColor("#475569"))
            setLineSpacing(0f, 1.4f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, d(2f), 0, 0)
        }
        textCol.addView(titleRow)
        textCol.addView(msg)
        titleView = title
        msgView = msg
        row.addView(textCol)

        cardView.addView(row)
        contentRow = row
        card = cardView
        shellView.addView(cardView)
        shell = shellView

        val rootView = FrameLayout(context).apply {
            val shadowPad = d(8f)
            setPadding(shadowPad, shadowPad, shadowPad, shadowPad)
            clipToPadding = false
            clipChildren = false
            addView(shellView)
            visibility = View.GONE
            alpha = 0f
        }
        BlurCompat.forceSoftwareLayerIfNeeded(rootView)
        root = rootView

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = edgeMarginPx
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // WRAP_CONTENT window = only the banner's own footprint gets blurred.
            LiquidGlass.applyWindowBlur(this, density)
            OverlayOrientation.apply(this)
        }
        windowParams = params
        try {
            windowManager.addView(rootView, params)
        } catch (e: Exception) {
            android.util.Log.e("NotificationBanner", "addView failed", e)
        }
    }

    fun applyPosition(settings: NotificationSettings) {
        val params = windowParams ?: return
        val m = if (edgeMarginPx > 0) {
            edgeMarginPx
        } else {
            val density = context.resources.displayMetrics.density
            (16f * density * bannerScale()).toInt()
        }
        val pos = settings.bannerPosition.coerceIn(0, 8)
        params.gravity = when (pos) {
            0 -> Gravity.TOP or Gravity.START
            1 -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            2 -> Gravity.TOP or Gravity.END
            3 -> Gravity.CENTER_VERTICAL or Gravity.START
            4 -> Gravity.CENTER
            5 -> Gravity.CENTER_VERTICAL or Gravity.END
            6 -> Gravity.BOTTOM or Gravity.START
            7 -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else -> Gravity.BOTTOM or Gravity.END
        }
        params.x = 0
        params.y = when {
            pos in 0..2 -> m
            pos in 6..8 -> m
            else -> 0
        }
        if (pos % 3 == 0 || pos % 3 == 2) {
            params.x = m
        }
    }

    fun bind(
        scene: NotificationScene,
        settings: NotificationSettings,
        resolve: (String) -> String,
    ) {
        heightAnimator?.cancel()
        heightAnimator = null
        expanded = false
        bodyCanExpand = false
        expandBtn?.rotation = 0f
        shell?.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
        shell?.requestLayout()

        val title = resolve(scene.title)
        val desc = resolve(scene.desc)
        val sub = resolve(scene.subDesc)
        val body = listOf(desc, sub).filter { it.isNotBlank() }.joinToString(" ")

        iconChip?.text = FontAwesomeHelper.getIconChar(scene.icon)
        titleView?.text = title
        msgView?.let { v ->
            if (body.isBlank()) {
                v.visibility = View.GONE
                v.text = ""
            } else {
                v.visibility = View.VISIBLE
                v.text = body
                v.maxLines = 1
            }
        }

        // Card base follows the app theme; the user color is only a wash on top of it.
        darkTheme = DarkModeManager.getInstance(context).isDarkMode()
        val hex = settings.bannerColor.trim()
        val tinted = hex.isNotEmpty() &&
            !hex.equals("#ffffff", true) && !hex.equals("#fff", true)
        val base = if (darkTheme) Color.parseColor("#1a1a1d") else Color.WHITE

        val cardBg = if (!tinted) {
            // White means white — no wash, no gray tail.
            if (darkTheme) {
                washDrawable(
                    intArrayOf(base, Color.parseColor("#222226")),
                    floatArrayOf(0f, 1f),
                )
            } else {
                washDrawable(intArrayOf(Color.WHITE, Color.WHITE), floatArrayOf(0f, 1f))
            }
        } else {
            val pick = NotificationScene.parseHexColor(hex)
            val glow = washTone(pick, darkTheme)
            if (darkTheme) {
                washFromIcon(
                    intArrayOf(
                        ColorUtils.blendARGB(base, glow, 0.38f),
                        ColorUtils.blendARGB(base, glow, 0.24f),
                        ColorUtils.blendARGB(base, glow, 0.12f),
                        ColorUtils.blendARGB(base, glow, 0.05f),
                        base,
                    ),
                    floatArrayOf(0f, 0.22f, 0.48f, 0.76f, 1f),
                )
            } else {
                washFromIcon(
                    intArrayOf(
                        ColorUtils.blendARGB(Color.WHITE, glow, 0.50f),
                        ColorUtils.blendARGB(Color.WHITE, glow, 0.32f),
                        ColorUtils.blendARGB(Color.WHITE, glow, 0.16f),
                        ColorUtils.blendARGB(Color.WHITE, glow, 0.06f),
                        Color.WHITE,
                    ),
                    floatArrayOf(0f, 0.22f, 0.48f, 0.76f, 1f),
                )
            }
        }
        cardBg.setCornerRadius(
            if (cardCornerPx > 0f) {
                cardCornerPx
            } else {
                26f * context.resources.displayMetrics.density * bannerScale()
            },
        )
        card?.background = cardBg
        card?.invalidateOutline()

        val accent = if (tinted) {
            NotificationScene.parseHexColor(hex)
        } else {
            scene.getPrimaryColor()
        }
        val density = context.resources.displayMetrics.density
        val scale = scaleCached.takeIf { it > 0f } ?: bannerScale()
        if (darkTheme) {
            card?.elevation = 0f
        } else {
            card?.elevation = 5f * density * scale
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                card?.outlineAmbientShadowColor = Color.argb(22, 15, 23, 42)
                card?.outlineSpotShadowColor = Color.argb(16, 15, 23, 42)
            }
        }
        (iconChipBg?.background as? GradientDrawable)?.apply {
            cornerRadius = 14f * density * scale
            setColor(adjustAlpha(accent, if (darkTheme) 0.24f else 0.22f))
            setStroke(
                maxOf(1, (1f * density * scale).toInt()),
                adjustAlpha(accent, if (darkTheme) 0.22f else 0.18f),
            )
        }
        // Keep the scene hue on dark cards instead of flattening the glyph to white.
        iconChip?.setTextColor(
            if (darkTheme) ColorUtils.blendARGB(accent, Color.WHITE, 0.35f) else accent,
        )
        titleView?.setTextColor(if (darkTheme) Color.WHITE else Color.parseColor("#0f172a"))
        msgView?.setTextColor(
            if (darkTheme) Color.argb(209, 255, 255, 255) else Color.parseColor("#475569"),
        )
        expandBtn?.let { btn ->
            ImageViewCompat.setImageTintList(
                btn,
                ColorStateList.valueOf(
                    if (darkTheme) Color.argb(220, 255, 255, 255)
                    else Color.parseColor("#64748b"),
                ),
            )
        }

        // Original watermark language: brand blue on light cards, reversed out on dark ones.
        if (settings.bannerLogoEnabled) {
            logoView?.visibility = View.VISIBLE
            logoView?.alpha = if (darkTheme) 0.18f else 0.12f
            if (darkTheme) {
                logoView?.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            } else {
                logoView?.clearColorFilter()
            }
        } else {
            logoView?.visibility = View.GONE
        }

        updateExpandChrome()
        scheduleExpandProbe()
    }

    private fun toggleExpanded(animated: Boolean) {
        if (!bodyCanExpand) return
        val target = !expanded
        if (animated) {
            animateExpandTo(target)
        } else {
            expanded = target
            msgView?.maxLines = if (expanded) EXPANDED_MAX_LINES else 1
            expandBtn?.rotation = if (expanded) 180f else 0f
            updateExpandChrome()
            onExpandedChanged?.invoke(expanded)
            onLayoutChanged?.invoke()
        }
    }

    private fun animateExpandTo(toExpanded: Boolean) {
        val msg = msgView ?: return
        val shellView = shell ?: return
        if (shellView.width <= 0) {
            expanded = toExpanded
            msg.maxLines = if (toExpanded) EXPANDED_MAX_LINES else 1
            expandBtn?.rotation = if (toExpanded) 180f else 0f
            updateExpandChrome()
            onExpandedChanged?.invoke(toExpanded)
            onLayoutChanged?.invoke()
            return
        }

        heightAnimator?.cancel()

        val startH = if (shellView.height > 0) {
            shellView.height
        } else {
            shellView.measuredHeight
        }

        // Measure destination height with the target maxLines.
        msg.maxLines = if (toExpanded) EXPANDED_MAX_LINES else 1
        shellView.measure(
            View.MeasureSpec.makeMeasureSpec(shellView.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val endH = shellView.measuredHeight
        if (startH <= 0 || endH <= 0 || startH == endH) {
            expanded = toExpanded
            expandBtn?.animate()?.cancel()
            expandBtn?.rotation = if (toExpanded) 180f else 0f
            updateExpandChrome()
            onExpandedChanged?.invoke(toExpanded)
            shellView.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            shellView.requestLayout()
            onLayoutChanged?.invoke()
            return
        }

        expanded = toExpanded
        updateExpandChrome()
        onExpandedChanged?.invoke(toExpanded)

        // Chevron rotation — short, same family as system expand indicator.
        expandBtn?.animate()?.cancel()
        expandBtn?.animate()
            ?.rotation(if (toExpanded) 180f else 0f)
            ?.setDuration(220L)
            ?.setInterpolator(expandInterpolator)
            ?.start()

        val lp = shellView.layoutParams
        lp.height = startH
        shellView.layoutParams = lp

        heightAnimator = ValueAnimator.ofInt(startH, endH).apply {
            duration = 280L
            interpolator = expandInterpolator
            addUpdateListener { anim ->
                lp.height = anim.animatedValue as Int
                shellView.layoutParams = lp
                onLayoutChanged?.invoke()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    shellView.layoutParams = lp
                    shellView.requestLayout()
                    onLayoutChanged?.invoke()
                    heightAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    heightAnimator = null
                }
            })
            start()
        }
    }

    private fun updateExpandChrome() {
        val btn = expandBtn ?: return
        btn.visibility = if (bodyCanExpand) View.VISIBLE else View.GONE
        btn.contentDescription = if (expanded) {
            "Collapse"
        } else {
            "Expand"
        }
        btn.isEnabled = bodyCanExpand
        card?.isClickable = bodyCanExpand
    }

    private fun scheduleExpandProbe() {
        val msg = msgView ?: return
        if (msg.visibility != View.VISIBLE || msg.text.isNullOrBlank()) {
            bodyCanExpand = false
            updateExpandChrome()
            return
        }
        // Measure overflow without changing maxLines (that would inflate the card).
        msg.post {
            val width = msg.width
            if (width <= 0) {
                bodyCanExpand = false
                updateExpandChrome()
                return@post
            }
            val text = msg.text ?: ""
            val layout = android.text.StaticLayout.Builder
                .obtain(text, 0, text.length, msg.paint, width)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(msg.lineSpacingExtra, msg.lineSpacingMultiplier)
                .setIncludePad(msg.includeFontPadding)
                .build()
            bodyCanExpand = layout.lineCount > 1
            if (!expanded) {
                msg.maxLines = 1
            }
            expandBtn?.rotation = if (expanded) 180f else 0f
            updateExpandChrome()
            onLayoutChanged?.invoke()
        }
    }

    fun detach(windowManager: WindowManager?) {
        heightAnimator?.cancel()
        heightAnimator = null
        expandBtn?.animate()?.cancel()
        root?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
        windowParams = null
        shell = null
        card = null
        contentRow = null
        iconChip = null
        iconChipBg = null
        titleView = null
        msgView = null
        logoView = null
        expandBtn = null
        cardCornerPx = 0f
        edgeMarginPx = 0
        iconOriginX = 0f
        iconOriginY = 0f
        expanded = false
        bodyCanExpand = false
        onLayoutChanged = null
        onExpandedChanged = null
    }

    private fun bannerScale(): Float {
        val metrics = context.resources.displayMetrics
        val shortestDp = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density
        return (shortestDp / 360f).coerceIn(0.95f, 1.35f)
    }

    /**
     * Soft wash that starts at the icon chip and fades to the theme base.
     */
    private fun washFromIcon(colors: IntArray, stops: FloatArray): PaintDrawable =
        PaintDrawable().apply {
            shape = RectShape()
            shaderFactory = object : ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int): Shader {
                    val cx = iconOriginX.coerceIn(0f, width.toFloat())
                    val cy = iconOriginY.coerceIn(0f, height.toFloat())
                    val radius = (kotlin.math.hypot(
                        (width - cx).toDouble(),
                        (height - cy).toDouble(),
                    ).toFloat() * 1.45f).coerceAtLeast(1f)
                    return RadialGradient(
                        cx,
                        cy,
                        radius,
                        colors,
                        stops,
                        Shader.TileMode.CLAMP,
                    )
                }
            }
        }

    /**
     * Rounded card fill with explicit gradient stops — [GradientDrawable] can only
     * space its colors evenly, and the wash needs a long, late-starting ramp.
     */
    private fun washDrawable(colors: IntArray, stops: FloatArray): PaintDrawable =
        PaintDrawable().apply {
            shape = RectShape()
            shaderFactory = object : ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int): Shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    height.toFloat(),
                    colors,
                    stops,
                    Shader.TileMode.CLAMP,
                )
            }
        }

    /**
     * Light: keep a readable pastel of the pick (fresh and light — white into color).
     * Dark: quieter ink wash so the card stays a dark surface.
     */
    private fun washTone(color: Int, isDark: Boolean): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        if (isDark) {
            val k = 0.33f
            hsl[1] = minOf(0.20f, hsl[1] * (0.14f + k * 0.18f))
            hsl[2] = 0.125f + k * 0.045f
        } else {
            hsl[1] = minOf(hsl[1], 0.28f)
            hsl[2] = 0.90f
        }
        return ColorUtils.HSLToColor(hsl)
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (255 * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    companion object {
        private const val EXPANDED_MAX_LINES = 8
    }
}
