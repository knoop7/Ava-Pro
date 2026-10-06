package com.example.ava.touchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.ava.R

/**
 * Count pip stays on the star's top-right.
 * Expanded: 1–5 beside the star. No dots.
 *
 * The pad never owns the 1–5 row width. This host is a fixed pixel
 * viewport left of the close (X); the row keeps 26dp cells and scrolls.
 */
internal class TouchPadAutoBadges(context: Context) : FrameLayout(context) {
    interface Listener {
        fun onExpand()
        fun onPlay(index: Int)
        fun onPick(index: Int)
        fun onDelete(index: Int)
    }

    private var listener: Listener? = null
    private var iconColor = 0xFFFFFFFF.toInt()
    private var starHost: FrameLayout? = null
    private var pipHit: FrameLayout? = null
    private var pip: TextView? = null
    private var lastTitlePx = 0
    private var lastPadWidthPx = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        clipChildren = true
        clipToPadding = true
    }

    fun setListener(next: Listener?) {
        listener = next
    }

    fun setColor(next: Int) {
        iconColor = next
    }

    fun inlineTrash(): View? = (getChildAt(0) as? SlotAndTrash)?.trashView()

    fun setInlineTrashVisible(visible: Boolean) {
        inlineTrash()?.visibility = if (visible) VISIBLE else INVISIBLE
    }

    fun attachStar(host: FrameLayout) {
        if (starHost === host && pipHit != null) return
        pipHit?.let { view -> (view.parent as? FrameLayout)?.removeView(view) }
        starHost = host
        ensurePip(host)
    }

    fun bind(
        filled: BooleanArray,
        selected: Int,
        hidden: Boolean,
        expanded: Boolean,
        titlePx: Int,
        compact: Boolean = false,
        padWidthPx: Int = 0,
    ) {
        val count = filled.count { it }
        bindPip(count, show = count > 0 && !hidden)
        if (count == 0 || hidden || !expanded) {
            visibility = GONE
            removeAllViews()
            return
        }
        applyExpandedLayout(titlePx, padWidthPx)
        visibility = VISIBLE
        removeAllViews()
        addView(expandedHost(filled, selected, compact), LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    fun setPadWidth(padWidthPx: Int, titlePx: Int) {
        if (visibility != VISIBLE) return
        applyExpandedLayout(titlePx, padWidthPx)
        requestLayout()
    }

    private fun bindPip(count: Int, show: Boolean) {
        val host = starHost ?: return
        val hit = ensurePip(host)
        hit.visibility = if (show) VISIBLE else GONE
        if (!show) return
        pip?.text = count.coerceIn(1, TouchPadAuto.MAX_TAKES).toString()
        pip?.contentDescription = context.getString(R.string.touch_pad_auto_slot, count)
    }

    private fun ensurePip(host: FrameLayout): FrameLayout {
        pipHit?.let { return it }
        val density = resources.displayMetrics.density
        val visual = TouchPadMath.dp(14, density)
        val hit = TouchPadMath.dp(22, density)
        val label = TextView(context).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setTextColor(TouchPadTheme.HALT_ICON)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(TouchPadTheme.HALT_FILL)
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val wrap = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.touch_pad_auto_slot, 1)
            setOnClickListener { listener?.onExpand() }
            addView(
                label,
                LayoutParams(visual, visual).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = TouchPadMath.dp(1, density)
                    rightMargin = TouchPadMath.dp(1, density)
                },
            )
        }
        host.addView(
            wrap,
            LayoutParams(hit, hit).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = TouchPadMath.dp(3, density)
            },
        )
        pip = label
        pipHit = wrap
        return wrap
    }

    private fun applyExpandedLayout(titlePx: Int, padWidthPx: Int) {
        val density = resources.displayMetrics.density
        val host = parent as? View
        val padW = padWidthPx.takeIf { it > 0 }
            ?: host?.width?.takeIf { it > 0 }
            ?: host?.measuredWidth
            ?: lastPadWidthPx
        lastTitlePx = titlePx
        lastPadWidthPx = padW
        val strip = TouchPadMath.autoSlotStripPx(padW, titlePx, density)
        val params = (layoutParams as? LayoutParams) ?: LayoutParams(strip, titlePx)
        params.width = strip
        params.height = titlePx
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = titlePx * 2 + TouchPadMath.dp(TouchPadMath.AUTO_SLOT_STAR_GAP_DP, density)
        params.topMargin = TouchPadMath.titlePadTopPx(density)
        params.rightMargin = 0
        layoutParams = params
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val lp = layoutParams
        val density = resources.displayMetrics.density
        val title = if (lp.height > 0) lp.height else lastTitlePx
        val width = if (lp.width > 0) {
            lp.width
        } else {
            val host = parent as? View
            val padW = host?.width?.takeIf { it > 0 } ?: host?.measuredWidth ?: lastPadWidthPx
            TouchPadMath.autoSlotStripPx(padW, title, density)
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(title.coerceAtLeast(0), MeasureSpec.EXACTLY),
        )
    }

    private fun expandedHost(filled: BooleanArray, selected: Int, compact: Boolean): View {
        val density = resources.displayMetrics.density
        val gap = TouchPadMath.dp(TouchPadMath.AUTO_SLOT_GAP_DP, density)
        val cell = TouchPadMath.dp(TouchPadMath.AUTO_SLOT_CELL_DP, density)
        val strip = SlotStrip(context).apply {
            addView(
                slotRow(filled, selected, gap, cell),
                ViewGroup.LayoutParams(TouchPadMath.autoSlotRowPx(density), LayoutParams.MATCH_PARENT),
            )
        }
        if (compact) return strip
        return SlotAndTrash(context, strip, trashButton(selected, filled), gap, cell)
    }

    private fun slotRow(
        filled: BooleanArray,
        selected: Int,
        gap: Int,
        cell: Int,
    ): SlotRow {
        val density = resources.displayMetrics.density
        val row = SlotRow(context, cell, gap)
        repeat(TouchPadAuto.MAX_TAKES) { index ->
            val used = index in filled.indices && filled[index]
            val on = index == selected && used
            val cellView = TextView(context).apply {
                text = (index + 1).toString()
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                minWidth = cell
                minHeight = cell
                setTextColor(iconColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                alpha = when {
                    on -> 1f
                    used -> 0.82f
                    else -> 0.28f
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = TouchPadMath.dp(6f, density)
                    setColor(
                        when {
                            on -> (iconColor and 0x00FFFFFF) or 0x3D000000
                            used -> (iconColor and 0x00FFFFFF) or 0x22000000
                            else -> 0x00000000
                        },
                    )
                    if (on) {
                        setStroke(TouchPadMath.dp(1.2f, density).toInt(), iconColor)
                    }
                }
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(R.string.touch_pad_auto_slot, index + 1)
                setOnClickListener {
                    if (used) listener?.onPlay(index) else listener?.onPick(index)
                }
            }
            row.addView(cellView, ViewGroup.LayoutParams(cell, cell))
        }
        return row
    }

    private fun trashButton(selected: Int, filled: BooleanArray): ImageView {
        val density = resources.displayMetrics.density
        val canDelete = selected in filled.indices && filled[selected]
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_touch_pad_auto_delete)
            setColorFilter(iconColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = canDelete
            isFocusable = canDelete
            alpha = if (canDelete) 1f else 0.38f
            contentDescription = context.getString(R.string.touch_pad_auto_delete)
            val inset = TouchPadMath.dp(3, density)
            setPadding(inset, inset, inset, inset)
            setOnClickListener {
                if (!canDelete) return@setOnClickListener
                listener?.onDelete(selected)
            }
        }
    }

    /**
     * Trash stays immediately after 1–5, outside the number window.
     * The strip is a bounded viewport; 1–5 keep their full width and
     * scroll instead of being drawn into the close (X).
     */
    private class SlotAndTrash(
        context: Context,
        private val strip: SlotStrip,
        private val trash: View,
        gap: Int,
        cell: Int,
    ) : LinearLayout(context) {
        fun trashView(): View = trash
        init {
            orientation = HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            clipChildren = true
            clipToPadding = true
            addView(strip, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(
                trash,
                LayoutParams(cell, cell).apply {
                    leftMargin = gap
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val hExact = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            val trashLp = trash.layoutParams as MarginLayoutParams
            trash.measure(
                MeasureSpec.makeMeasureSpec(trashLp.width.coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(trashLp.height.coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
            val reserved = trash.measuredWidth + trashLp.leftMargin + trashLp.rightMargin
            val remain = (width - paddingLeft - paddingRight - reserved).coerceAtLeast(0)
            strip.measure(MeasureSpec.makeMeasureSpec(remain, MeasureSpec.EXACTLY), hExact)
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val height = b - t
            val trashLp = trash.layoutParams as MarginLayoutParams
            val reserved = trash.measuredWidth + trashLp.leftMargin + trashLp.rightMargin
            val stripRight = (r - l - paddingRight - reserved).coerceAtLeast(paddingLeft)
            strip.layout(paddingLeft, paddingTop, stripRight, height - paddingBottom)
            val trashLeft = stripRight + trashLp.leftMargin
            val trashTop = paddingTop +
                (height - paddingTop - paddingBottom - trash.measuredHeight).coerceAtLeast(0) / 2
            trash.layout(
                trashLeft,
                trashTop,
                trashLeft + trash.measuredWidth,
                trashTop + trash.measuredHeight,
            )
        }
    }

    /** Five fixed-size cells. Parent width never shrinks a cell. */
    private class SlotRow(
        context: Context,
        private val cell: Int,
        private val gap: Int,
    ) : ViewGroup(context) {
        init {
            clipChildren = false
            clipToPadding = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(cell)
            val cellSpec = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                getChildAt(i).measure(cellSpec, cellSpec)
            }
            val n = childCount.coerceAtLeast(1)
            setMeasuredDimension(cell * n + gap * (n - 1), h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val h = b - t
            val top = (h - cell).coerceAtLeast(0) / 2
            var x = 0
            for (i in 0 until childCount) {
                getChildAt(i).layout(x, top, x + cell, top + cell)
                x += cell + gap
            }
        }
    }

    /**
     * Viewport between the star and the close (X). Width is always the
     * leftover window, never the 1–5 row. The child stays five cells wide.
     */
    private class SlotStrip(context: Context) : HorizontalScrollView(context) {
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dstIn = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        private val layerPaint = Paint().apply { alpha = 254 }

        init {
            isFillViewport = false
            clipChildren = true
            clipToPadding = true
            isFocusable = false
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            setWillNotDraw(false)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val viewport = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
            val height = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
            val child = getChildAt(0)
            val row = TouchPadMath.autoSlotRowPx(resources.displayMetrics.density)
            if (child != null) {
                val lp = child.layoutParams
                lp.width = row
                lp.height = height
                child.measure(
                    MeasureSpec.makeMeasureSpec(row, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
            }
            setMeasuredDimension(viewport, height)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            val row = getChildAt(0)?.measuredWidth ?: 0
            scrollTo(scrollX.coerceIn(0, (row - width).coerceAtLeast(0)), 0)
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val showStart = showStartFade()
            val showEnd = showEndFade()
            if ((!showStart && !showEnd) || w < 2f || h < 2f) {
                super.dispatchDraw(canvas)
                return
            }
            val save = canvas.saveLayer(0f, 0f, w, h, layerPaint)
            super.dispatchDraw(canvas)
            drawDstInMask(canvas, w, h, showStart, showEnd)
            canvas.restoreToCount(save)
        }

        private fun rowWidth(): Int = getChildAt(0)?.measuredWidth ?: 0

        private fun maxScroll(): Int = (rowWidth() - width).coerceAtLeast(0)

        private fun showStartFade(): Boolean = scrollX > 2

        private fun showEndFade(): Boolean {
            val range = maxScroll()
            return range > 2 && scrollX < range - 2
        }

        private fun fadePx(w: Float): Float =
            TouchPadMath.autoSlotFadePx(w.toInt(), resources.displayMetrics.density).toFloat()

        private fun drawDstInMask(
            canvas: Canvas,
            w: Float,
            h: Float,
            showStart: Boolean,
            showEnd: Boolean,
        ) {
            val fade = fadePx(w)
            if (fade < 1f) return
            val startEnd = if (showStart) (fade / w).coerceIn(0.02f, 0.28f) else 0f
            val endStart = if (showEnd) (1f - fade / w).coerceIn(0.72f, 0.98f) else 1f
            if (showStart && showEnd && startEnd >= endStart) return
            val positions = ArrayList<Float>(6)
            val colors = ArrayList<Int>(6)
            if (showStart) {
                positions += 0f
                colors += 0x00FFFFFF
                positions += startEnd * 0.45f
                colors += 0x59FFFFFF
                positions += startEnd
                colors += 0xFFFFFFFF.toInt()
            } else {
                positions += 0f
                colors += 0xFFFFFFFF.toInt()
            }
            if (showEnd) {
                positions += endStart
                colors += 0xFFFFFFFF.toInt()
                positions += endStart + (1f - endStart) * 0.55f
                colors += 0x59FFFFFF
                positions += 1f
                colors += 0x00FFFFFF
            } else {
                positions += 1f
                colors += 0xFFFFFFFF.toInt()
            }
            maskPaint.shader = LinearGradient(
                0f,
                0f,
                w,
                0f,
                colors.toIntArray(),
                positions.toFloatArray(),
                Shader.TileMode.CLAMP,
            )
            maskPaint.xfermode = dstIn
            canvas.drawRect(0f, 0f, w, h, maskPaint)
            maskPaint.xfermode = null
            maskPaint.shader = null
        }
    }
}
