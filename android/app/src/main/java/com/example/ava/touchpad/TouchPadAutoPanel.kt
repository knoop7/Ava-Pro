package com.example.ava.touchpad

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.ava.R
import kotlin.math.roundToInt

/** Automation face: arm is play/record; play is three glanceable dials in a row. */
internal class TouchPadAutoPanel(context: Context) : LinearLayout(context) {
    interface Listener {
        fun onRecord()
        fun onPlay()
        fun onPause()
        fun onResume()
        fun onReplay()
        fun onEnd()
        fun onLoop()
    }

    private var listener: Listener? = null
    private var iconColor = 0xFFFFFFFF.toInt()
    private var lineColor = 0x3390989A
    private var page: TouchPadAutoPage? = null
    private var hasTake = false
    private var loop = false
    private var loopPip: ImageView? = null
    private var faceIconDp = TouchPadMath.AUTO_FACE_ICON_MIN_DP
    private var faceTextSp = TouchPadMath.AUTO_FACE_TEXT_MIN_SP
    private var faceWidthDp = TouchPadMath.DEFAULT_SIZE_DP.toFloat()
    private var faceHeightDp = TouchPadMath.DEFAULT_SIZE_DP.toFloat()
    private var breathView: View? = null

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setListener(next: Listener?) {
        listener = next
    }

    fun setColors(icon: Int, divider: Int) {
        iconColor = icon
        lineColor = divider
    }

    fun setFaceMetrics(
        iconDp: Float,
        textSp: Float,
        widthDp: Float = faceWidthDp,
        heightDp: Float = faceHeightDp,
    ) {
        val nextIcon = iconDp.coerceIn(
            TouchPadMath.AUTO_FACE_ICON_MIN_DP,
            TouchPadMath.AUTO_FACE_ICON_MAX_DP,
        )
        val nextText = textSp.coerceIn(
            TouchPadMath.AUTO_FACE_TEXT_MIN_SP,
            TouchPadMath.AUTO_FACE_TEXT_MAX_SP,
        )
        val nextWidth = widthDp.coerceAtLeast(TouchPadMath.MIN_SIZE_DP.toFloat())
        val nextHeight = heightDp.coerceAtLeast(TouchPadMath.MIN_SIZE_DP.toFloat())
        if (kotlin.math.abs(nextIcon - faceIconDp) < 0.4f &&
            kotlin.math.abs(nextText - faceTextSp) < 0.3f &&
            kotlin.math.abs(nextWidth - faceWidthDp) < 2f &&
            kotlin.math.abs(nextHeight - faceHeightDp) < 2f
        ) {
            return
        }
        faceIconDp = nextIcon
        faceTextSp = nextText
        faceWidthDp = nextWidth
        faceHeightDp = nextHeight
        page?.let { bind(it, hasTake) }
    }

    fun setLoop(on: Boolean) {
        if (loop == on) return
        loop = on
        loopPip?.let { paintLoopPip(it, on) }
    }

    fun show(next: TouchPadAutoPage, take: Boolean, loopOn: Boolean = false, animate: Boolean = false) {
        hasTake = take
        loop = loopOn
        if (animate && isAttachedToWindow && page != null && page != next) {
            animate().cancel()
            animate()
                .alpha(0f)
                .setDuration(90L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    bind(next, take)
                    alpha = 0f
                    animate()
                        .alpha(1f)
                        .setDuration(160L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()
            return
        }
        animate().cancel()
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        bind(next, take)
    }

    override fun onDetachedFromWindow() {
        stopBreath()
        loopPip = null
        animate().cancel()
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        super.onDetachedFromWindow()
    }

    private fun bind(next: TouchPadAutoPage, take: Boolean) {
        stopBreath()
        loopPip = null
        page = next
        hasTake = take
        removeAllViews()
        when (next) {
            TouchPadAutoPage.ARM -> {
                if (take) {
                    addAction(
                        R.string.touch_pad_auto_play,
                        R.drawable.ic_touch_pad_auto_play,
                    ) { listener?.onPlay() }
                    addSplit()
                    addAction(
                        R.string.touch_pad_auto_record,
                        R.drawable.ic_touch_pad_auto_record,
                    ) { listener?.onRecord() }
                } else {
                    addAction(
                        R.string.touch_pad_auto_record,
                        R.drawable.ic_touch_pad_auto_record,
                        prominent = true,
                    ) { listener?.onRecord() }
                }
            }
            TouchPadAutoPage.PLAY -> {
                addDialRow(
                    Dial(
                        R.string.touch_pad_auto_replay,
                        R.drawable.ic_touch_pad_auto_replay,
                        loopMark = true,
                    ) { listener?.onReplay() },
                    Dial(
                        R.string.touch_pad_auto_pause,
                        R.drawable.ic_touch_pad_auto_pause,
                    ) { listener?.onPause() },
                    Dial(
                        R.string.touch_pad_auto_end,
                        R.drawable.ic_touch_pad_auto_end,
                    ) { listener?.onEnd() },
                )
                startBreath(dialGlyph(1))
            }
            TouchPadAutoPage.PAUSED -> {
                addDialRow(
                    Dial(
                        R.string.touch_pad_auto_replay,
                        R.drawable.ic_touch_pad_auto_replay,
                        loopMark = true,
                    ) { listener?.onReplay() },
                    Dial(
                        R.string.touch_pad_auto_resume,
                        R.drawable.ic_touch_pad_auto_resume,
                    ) { listener?.onResume() },
                    Dial(
                        R.string.touch_pad_auto_end,
                        R.drawable.ic_touch_pad_auto_end,
                    ) { listener?.onEnd() },
                )
                startBreath(dialGlyph(1))
            }
            TouchPadAutoPage.RECORD -> Unit
        }
    }

    private data class Dial(
        val label: Int,
        val icon: Int,
        val loopMark: Boolean = false,
        val click: () -> Unit,
    )

    private fun addDialRow(vararg dials: Dial) {
        val density = resources.displayMetrics.density
        val face = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        val side = TouchPadMath.dp(8, density)
        dials.forEach { dial ->
            face.addView(
                dialButton(dial),
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
        }
        addView(
            face,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                leftMargin = side
                rightMargin = side
            },
        )
    }

    private fun dialButton(dial: Dial): LinearLayout {
        val density = resources.displayMetrics.density
        val circleDp = TouchPadMath.autoFaceDialCircleDp(faceWidthDp, faceIconDp)
        val textSp = TouchPadMath.autoFaceDialTextSp(faceTextSp)
        val pipDp = TouchPadMath.autoFaceDialPipDp(circleDp)
        val circlePx = TouchPadMath.dp(circleDp, density).roundToInt()
        val inset = TouchPadMath.dp((circleDp * 0.22f).coerceIn(6f, 12f), density).roundToInt()
        val gap = TouchPadMath.dp((circleDp * 0.21f).coerceIn(6f, 10f), density).roundToInt()
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(dial.label)
            val typed = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = typed.getDrawable(0)
            typed.recycle()
            setOnClickListener { dial.click() }
            val glyph = ImageView(context).apply {
                setImageResource(dial.icon)
                setColorFilter(iconColor)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = dialPlate()
                setPadding(inset, inset, inset, inset)
            }
            val face = if (dial.loopMark) {
                clipChildren = false
                clipToPadding = false
                FrameLayout(context).apply {
                    clipChildren = false
                    clipToPadding = false
                    addView(glyph, FrameLayout.LayoutParams(circlePx, circlePx))
                    val pip = loopPipView()
                    loopPip = pip
                    val pipSize = TouchPadMath.dp(pipDp, density).roundToInt()
                    val pipHang = TouchPadMath.dp((pipDp * 0.22f).coerceIn(2f, 4f), density).roundToInt()
                    addView(
                        pip,
                        FrameLayout.LayoutParams(pipSize, pipSize).apply {
                            gravity = Gravity.TOP or Gravity.END
                            topMargin = -pipHang
                            rightMargin = -pipHang
                        },
                    )
                }
            } else {
                glyph
            }
            addView(face, LayoutParams(circlePx, circlePx).apply { bottomMargin = gap })
            addView(
                TextView(context).apply {
                    text = context.getString(dial.label)
                    gravity = Gravity.CENTER
                    setTextColor(iconColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                },
            )
        }
    }

    private fun loopPipView(): ImageView {
        val density = resources.displayMetrics.density
        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.touch_pad_auto_loop)
            val pad = TouchPadMath.dp(4, density)
            setPadding(pad, pad, pad, pad)
            paintLoopPip(this, loop)
            setOnClickListener { listener?.onLoop() }
        }
    }

    private fun paintLoopPip(pip: ImageView, on: Boolean) {
        pip.setImageResource(if (on) R.drawable.mdi_check else 0)
        pip.setColorFilter(TouchPadTheme.HALT_ICON)
        pip.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (on) {
                setColor(TouchPadTheme.HALT_FILL)
            } else {
                setColor((iconColor and 0x00FFFFFF) or 0x66000000)
                setStroke(TouchPadMath.dp(1, resources.displayMetrics.density), iconColor)
            }
        }
        pip.alpha = if (on) 1f else 0.9f
    }

    private fun dialPlate(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((iconColor and 0x00FFFFFF) or 0x2A000000)
        }

    private fun addSplit() {
        val density = resources.displayMetrics.density
        val inset = TouchPadMath.dp(22, density)
        val line = View(context).apply {
            background = GradientDrawable().apply {
                setColor(lineColor)
                cornerRadius = density
            }
        }
        addView(
            line,
            LayoutParams(LayoutParams.MATCH_PARENT, TouchPadMath.dp(1, density)).apply {
                leftMargin = inset
                rightMargin = inset
            },
        )
    }

    private fun addAction(
        label: Int,
        icon: Int,
        halt: Boolean = false,
        enabled: Boolean = true,
        prominent: Boolean = false,
        click: () -> Unit,
    ) {
        addView(actionButton(label, icon, halt, enabled, prominent, click), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun actionButton(
        label: Int,
        icon: Int,
        halt: Boolean = false,
        enabled: Boolean = true,
        prominent: Boolean = false,
        click: () -> Unit,
    ): LinearLayout {
        val density = resources.displayMetrics.density
        val rows = if (prominent) 1 else 2
        val iconDp = TouchPadMath.autoFaceActionIconDp(faceHeightDp, rows, faceIconDp, prominent)
        val textSp = TouchPadMath.autoFaceActionTextSp(iconDp, prominent)
        val iconPx = TouchPadMath.dp(iconDp, density).roundToInt()
        val gap = TouchPadMath.dp((iconDp * 0.18f).coerceIn(4f, 18f), density).roundToInt()
        val inset = TouchPadMath.dp((iconDp * 0.16f).coerceIn(3f, 14f), density).roundToInt()
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isClickable = enabled
            isFocusable = enabled
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.34f
            val typed = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = typed.getDrawable(0)
            typed.recycle()
            if (enabled) setOnClickListener { click() }
            val glyph = ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(if (halt) TouchPadTheme.HALT_ICON else iconColor)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = glyphPlate(halt, density)
                setPadding(inset, inset, inset, inset)
            }
            addView(glyph, LayoutParams(iconPx, iconPx).apply { bottomMargin = gap })
            addView(
                TextView(context).apply {
                    text = context.getString(label)
                    gravity = Gravity.CENTER
                    setTextColor(if (halt) TouchPadTheme.HALT_FILL or 0xFF000000.toInt() else iconColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                },
            )
        }
    }

    private fun glyphPlate(halt: Boolean, density: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = TouchPadMath.dp(if (halt) 4f else 7f, density)
            setColor(
                if (halt) {
                    TouchPadTheme.HALT_FILL
                } else {
                    (iconColor and 0x00FFFFFF) or 0x2A000000
                },
            )
        }

    private fun dialGlyph(index: Int): View? {
        val row = getChildAt(0) as? LinearLayout ?: return null
        val dial = row.getChildAt(index) as? LinearLayout ?: return null
        return dial.getChildAt(0)
    }

    private fun startBreath(view: View?) {
        stopBreath()
        val target = view ?: return
        breathView = target
        pulse(target, dim = true)
    }

    private fun pulse(view: View, dim: Boolean) {
        if (breathView !== view) return
        view.animate().cancel()
        view.animate()
            .alpha(if (dim) 0.42f else 1f)
            .setDuration(720L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { pulse(view, !dim) }
            .start()
    }

    private fun stopBreath() {
        val view = breathView
        breathView = null
        view?.animate()?.cancel()
        view?.alpha = 1f
    }
}
