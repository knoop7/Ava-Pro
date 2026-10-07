package com.example.ava.ui.screens.home

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import com.example.ava.widgets.AvaTitleDescriptionWidgetProvider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Free-canvas helpers for the minimal launcher workspace.
 *
 * Positions and widget sizes are stored as fractions of the page (0..1).
 * Placement uses finger drop + ruler snap (ruler alignment) by default.
 * Icons never stack: overlaps / zero-gap seats are auto-nudged apart.
 */

/** Soft floor so a free resize can't vanish the host (~40dp on typical phones). */
const val MINIMAL_LAUNCHER_WIDGET_MIN_FRAC = 0.05f

/** Absolute min host edge during forced free-canvas resize (dp). Ignores provider minWidth. */
const val MINIMAL_LAUNCHER_WIDGET_FORCE_MIN_DP = 40f

/** Ava text card first-drop height cap (page fraction). Landscape-only guard; other widgets untouched. */
private const val AVA_WIDGET_MAX_INITIAL_H_FRAC = 0.40f

/** Ava text card first-drop width cap — leave side breathing room on empty desktops. */
private const val AVA_WIDGET_MAX_INITIAL_W_FRAC = 0.58f

/** Landscape first-drop width cap for all widgets — prevents edge-to-edge stretch. */
const val MINIMAL_LAUNCHER_WIDGET_MAX_LAND_W_FRAC = 0.58f

fun isAvaTitleDescriptionWidget(provider: android.content.ComponentName): Boolean =
    provider.className == AvaTitleDescriptionWidgetProvider::class.java.name

/**
 * When Ava is the sole item on an empty page, center horizontally instead of
 * edge-snapping to the page rulers (which reads as full-bleed).
 */
fun avaEmptyPageDropPoint(raw: CanvasPoint, wNorm: Float, hNorm: Float): CanvasPoint =
    CanvasPoint(
        x = ((1f - wNorm) / 2f).coerceAtLeast(0f),
        y = raw.y.coerceIn(0f, (1f - hNorm).coerceAtLeast(0f)),
    )

/**
 * Ruler snap magnetic distance in dp. Strong enough that icons/widgets
 * "want" to align; pull farther than this to free-float.
 */
const val MINIMAL_LAUNCHER_ALIGN_THRESH_DP = 12f

/** Frac fallback when density/page size unavailable. */
const val MINIMAL_LAUNCHER_ALIGN_THRESH_FRAC = 0.03f

/** Master switch — default ON (ruler principle). */
const val MINIMAL_LAUNCHER_ALIGN_ENABLED = true

/**
 * Minimum air between icon seats (dp). Flush edges / overlaps get auto-corrected.
 */
const val MINIMAL_LAUNCHER_ICON_GAP_DP = 4f

/** Landscape gap — a touch tighter; still avoids stacking. */
const val MINIMAL_LAUNCHER_ICON_GAP_LAND_DP = 3f

data class CanvasPoint(val x: Float, val y: Float)

data class CanvasRect(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

data class CanvasGuideLines(
    val vertical: FloatArray,
    val horizontal: FloatArray,
)

/** Ruler snap result: placed rect + active guide lines (page-normalized). */
data class AlignSnapResult(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val guideV: Float? = null,
    val guideH: Float? = null,
) {
    val point: CanvasPoint get() = CanvasPoint(x, y)
}

/**
 * Icon seat in page-normalized (w, h).
 *
 * Uses [MinimalLauncherGridSpec.computeIconPack]:
 * - Portrait: pack by **width** → N left-to-right, icon = (W − (N−1)·gap) / N
 * - Landscape: pack by **height** (with label factor) → N top-to-bottom
 *
 * Typical phones (&lt;420dp pack edge) prefer N=4 so five never overflow.
 */
fun iconBoxNorm(
    pageWidthPx: Float,
    pageHeightPx: Float,
    showLabel: Boolean = true,
    density: Float,
    sizeStep: Int = MinimalLauncherIconSizeStore.effective(),
): Pair<Float, Float> {
    val pageW = pageWidthPx.coerceAtLeast(1f)
    val pageH = pageHeightPx.coerceAtLeast(1f)
    val d = density.coerceAtLeast(0.01f)
    val landscape = pageW > pageH
    val labelFactor = when {
        !showLabel -> 1f
        landscape -> 1.24f
        else -> 1.28f
    }
    // Portrait packs horizontally (factor 1). Landscape packs vertically using
    // the labelled seat height so "how many rows fit top to bottom" matches on-screen rows.
    val packEdgePx = if (landscape) pageH else pageW
    val alongFactor = if (landscape) labelFactor else 1f
    // Sidebar handle is on the LEFT/RIGHT edge — only portrait (horizontal)
    // packing needs the per-side inset. Landscape packs vertically where
    // there is no sidebar handle, so no inset is needed.
    val insetPx = if (landscape) 0f
                  else MinimalLauncherGridSpec.ICON_PACK_PAGE_INSET_DP * d * 2f
    val effectiveEdge = (packEdgePx - insetPx).coerceAtLeast(1f)
    // Landscape: ensure at least 4 rows. seatsForEdgeDp might return 3 for
    // small landscape heights (< 300dp) but 4 icons at ~56dp each still fit
    // physically; computeIconPack drops to 3 only if icons hit the 48dp min.
    val autoSeats = if (landscape) {
        MinimalLauncherGridSpec.seatsForEdgeDp((effectiveEdge / d).toInt())
            .coerceAtLeast(4)
    } else {
        MinimalLauncherGridSpec.seatsForEdgeDp((effectiveEdge / d).toInt())
    }
    // Bigger icons mean fewer of them along the packing edge. Step 0 hands
    // computeIconPack exactly the count it would have picked itself, so the default
    // arithmetic — and every desktop already laid out with it — is untouched.
    val preferredSeats = autoSeats - sizeStep
    val (_, iconPx) = MinimalLauncherGridSpec.computeIconPack(
        edgePx = effectiveEdge,
        density = d,
        seatAlongFactor = alongFactor,
        preferredSeats = preferredSeats,
    )
    val seatWPx = iconPx
    val seatHPx = iconPx * labelFactor
    val w = (seatWPx / pageW).coerceIn(0.04f, 0.50f)
    val h = (seatHPx / pageH).coerceIn(0.06f, 0.56f)
    return w to h
}

/** Resolve density then [iconBoxNorm]. */
fun iconBoxNorm(
    context: Context,
    pageWidthPx: Float,
    pageHeightPx: Float,
    showLabel: Boolean = true,
    sizeStep: Int = MinimalLauncherIconSizeStore.effective(),
): Pair<Float, Float> = iconBoxNorm(
    pageWidthPx = pageWidthPx,
    pageHeightPx = pageHeightPx,
    showLabel = showLabel,
    density = context.resources.displayMetrics.density,
    sizeStep = sizeStep,
)

fun alignThreshNorm(pageWidthPx: Float, pageHeightPx: Float, density: Float): Float {
    val short = minOf(pageWidthPx, pageHeightPx).coerceAtLeast(1f)
    val d = density.coerceAtLeast(0.01f)
    return ((MINIMAL_LAUNCHER_ALIGN_THRESH_DP * d) / short).coerceIn(0.02f, 0.06f)
}

/** Equal pixel gap on both axes → separate X/Y fractions (landscape-safe). */
fun iconGapAxes(
    pageWidthPx: Float,
    pageHeightPx: Float,
    density: Float,
): Pair<Float, Float> {
    val landscape = pageWidthPx > pageHeightPx
    val gapDp = if (landscape) MINIMAL_LAUNCHER_ICON_GAP_LAND_DP else MINIMAL_LAUNCHER_ICON_GAP_DP
    val gapPx = gapDp * density.coerceAtLeast(0.01f)
    val gapX = (gapPx / pageWidthPx.coerceAtLeast(1f)).coerceIn(0.0015f, 0.05f)
    val gapY = (gapPx / pageHeightPx.coerceAtLeast(1f)).coerceIn(0.0015f, 0.05f)
    return gapX to gapY
}

/**
 * Grab point in [drawWidthPx]×[drawHeightPx] space — same registration the ghost
 * overlay uses. Scales from session size then clamps into the draw bitmap so
 * drop and follow-finger never disagree.
 */
fun effectiveGrabPx(
    grabOffsetX: Float,
    grabOffsetY: Float,
    sessionWidthPx: Int,
    sessionHeightPx: Int,
    drawWidthPx: Int = sessionWidthPx,
    drawHeightPx: Int = sessionHeightPx,
): Pair<Float, Float> {
    val sw = sessionWidthPx.coerceAtLeast(1).toFloat()
    val sh = sessionHeightPx.coerceAtLeast(1).toFloat()
    val dw = drawWidthPx.coerceAtLeast(1).toFloat()
    val dh = drawHeightPx.coerceAtLeast(1).toFloat()
    val gx = (grabOffsetX * (dw / sw)).coerceIn(0f, dw)
    val gy = (grabOffsetY * (dh / sh)).coerceIn(0f, dh)
    return gx to gy
}

fun effectiveGrabPx(session: MinimalLauncherDragSession): Pair<Float, Float> =
    effectiveGrabPx(
        session.grabOffsetX,
        session.grabOffsetY,
        session.widthPx,
        session.heightPx,
    )

/**
 * Top-left of the dragged item in page-normalized coords, clamped so the item
 * stays fully on the page. Uses the same clamped grab as the ghost overlay.
 */
fun freeDropTopLeft(
    session: MinimalLauncherDragSession,
    geo: WorkspacePageGeometry,
    itemWNorm: Float,
    itemHNorm: Float,
): CanvasPoint {
    val pageW = geo.widthPx.coerceAtLeast(1f)
    val pageH = geo.heightPx.coerceAtLeast(1f)
    val (gx, gy) = effectiveGrabPx(session)
    val localX = session.fingerX - gx - geo.originX
    val localY = session.fingerY - gy - geo.originY
    val w = itemWNorm.coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
    val h = itemHNorm.coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
    val x = (localX / pageW).coerceIn(0f, (1f - w).coerceAtLeast(0f))
    val y = (localY / pageH).coerceIn(0f, (1f - h).coerceAtLeast(0f))
    return CanvasPoint(x, y)
}

fun clampCanvasRect(x: Float, y: Float, w: Float, h: Float): CanvasRect {
    val nw = w.coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
    val nh = h.coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
    val nx = x.coerceIn(0f, (1f - nw).coerceAtLeast(0f))
    val ny = y.coerceIn(0f, (1f - nh).coerceAtLeast(0f))
    return CanvasRect(nx, ny, nw, nh)
}

/** Provider intrinsic size → page-normalized size (follows the widget itself). */
fun widgetSizeNormForProvider(
    context: Context,
    info: AppWidgetProviderInfo,
    pageWidthPx: Float,
    pageHeightPx: Float,
): Pair<Float, Float> {
    val density = context.resources.displayMetrics.density.coerceAtLeast(0.01f)
    val pad = AppWidgetHostView.getDefaultPaddingForWidget(context, info.provider, null)
    val minWDp = info.minWidth + (pad.left + pad.right) / density
    val minHDp = info.minHeight + (pad.top + pad.bottom) / density
    val hintWDp: Float
    val hintHDp: Float
    val isAvaTextWidget =
        info.provider.className == AvaTitleDescriptionWidgetProvider::class.java.name
    if (isAvaTextWidget) {
        // Ava title card: 3×2 on the five-cell reference grid (card, not full-bleed).
        val short = minOf(pageWidthPx, pageHeightPx).coerceAtLeast(1f)
        val cellDp = (short / 5f) / density
        hintWDp = 3f * cellDp
        hintHDp = 2f * cellDp
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        info.targetCellWidth > 0 && info.targetCellHeight > 0
    ) {
        val short = minOf(pageWidthPx, pageHeightPx).coerceAtLeast(1f)
        val cellDp = (short / 5f) / density
        hintWDp = info.targetCellWidth * cellDp
        hintHDp = info.targetCellHeight * cellDp
    } else {
        hintWDp = minWDp
        hintHDp = minHDp
    }
    val wPx = if (isAvaTextWidget) {
        // Skip provider minWidth floor on first drop — 240dp was forcing edge-to-edge width.
        (hintWDp * density).coerceAtLeast(48f)
    } else {
        (hintWDp.coerceAtLeast(minWDp) * density).coerceAtLeast(48f)
    }
    val hPx = if (isAvaTextWidget) {
        (hintHDp * density).coerceAtLeast(48f)
    } else {
        (hintHDp.coerceAtLeast(minHDp) * density).coerceAtLeast(48f)
    }
    val pageW = pageWidthPx.coerceAtLeast(1f)
    val pageH = pageHeightPx.coerceAtLeast(1f)
    val wNormRaw = (wPx / pageW).coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
    val hNormRaw = (hPx / pageH).coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
    val wNorm = if (isAvaTextWidget) {
        wNormRaw.coerceAtMost(AVA_WIDGET_MAX_INITIAL_W_FRAC)
    } else {
        wNormRaw
    }
    val hNorm = if (isAvaTextWidget) {
        hNormRaw.coerceAtMost(AVA_WIDGET_MAX_INITIAL_H_FRAC)
    } else {
        hNormRaw
    }
    return wNorm to hNorm
}

fun widgetMinSizeNorm(
    context: Context,
    info: AppWidgetProviderInfo?,
    pageWidthPx: Float,
    pageHeightPx: Float,
): Pair<Float, Float> {
    if (info == null) {
        return MINIMAL_LAUNCHER_WIDGET_MIN_FRAC to MINIMAL_LAUNCHER_WIDGET_MIN_FRAC
    }
    val density = context.resources.displayMetrics.density.coerceAtLeast(0.01f)
    val pad = AppWidgetHostView.getDefaultPaddingForWidget(context, info.provider, null)
    val minWPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (info.minResizeWidth.takeIf { it > 0 } ?: info.minWidth)
    } else {
        info.minWidth
    } * density + pad.left + pad.right
    val minHPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight)
    } else {
        info.minHeight
    } * density + pad.top + pad.bottom
    val pageW = pageWidthPx.coerceAtLeast(1f)
    val pageH = pageHeightPx.coerceAtLeast(1f)
    return (minWPx / pageW).coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f) to
        (minHPx / pageH).coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
}

fun migrateCellToNorm(cell: Int, span: Int, axisCount: Int): Pair<Float, Float> {
    val n = axisCount.coerceAtLeast(1)
    val pos = (cell.coerceAtLeast(0).toFloat() / n).coerceIn(0f, 1f)
    val size = (span.coerceAtLeast(1).toFloat() / n).coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
    return pos to size
}

fun approxDpLabel(widthDp: Int, heightDp: Int): String = "${widthDp}×${heightDp}"

fun providerSizeDpLabel(info: AppWidgetProviderInfo): String {
    val w = info.minWidth.coerceAtLeast(1)
    val h = info.minHeight.coerceAtLeast(1)
    return approxDpLabel(w, h)
}

/**
 * Build edge + center rulers from siblings on the same page and the page itself.
 *
 * When [gapX]/[gapY] &gt; 0 (icon drops), also emit gap-inset sibling edges so
 * snap targets already include the air gap — separation won't nudge off the
 * guide and hide the ruler.
 */
fun buildAlignGuides(
    page: Int,
    icons: List<PlacedMinimalLauncherIcon>,
    widgets: List<PlacedMinimalLauncherWidget>,
    iconW: Float,
    iconH: Float,
    exceptIconId: String? = null,
    exceptWidgetId: Int? = null,
    gapX: Float = 0f,
    gapY: Float = 0f,
): CanvasGuideLines {
    val v = LinkedHashSet<Float>()
    val h = LinkedHashSet<Float>()
    v.add(0f); v.add(0.5f); v.add(1f)
    h.add(0f); h.add(0.5f); h.add(1f)
    val gx = gapX.coerceAtLeast(0f)
    val gy = gapY.coerceAtLeast(0f)
    icons.forEach { icon ->
        if (icon.screen != page) return@forEach
        if (exceptIconId != null && icon.id == exceptIconId) return@forEach
        val l = icon.x
        val r = (icon.x + iconW).coerceAtMost(1f)
        val t = icon.y
        val b = (icon.y + iconH).coerceAtMost(1f)
        v.add(l); v.add((l + r) * 0.5f); v.add(r)
        h.add(t); h.add((t + b) * 0.5f); h.add(b)
        // Park left edge just to the right of sibling / right edge just to the left.
        if (gx > 0f) {
            v.add((r + gx).coerceIn(0f, 1f))
            v.add((l - gx).coerceIn(0f, 1f))
        }
        if (gy > 0f) {
            h.add((b + gy).coerceIn(0f, 1f))
            h.add((t - gy).coerceIn(0f, 1f))
        }
    }
    widgets.forEach { wdg ->
        if (wdg.screen != page) return@forEach
        if (exceptWidgetId != null && wdg.appWidgetId == exceptWidgetId) return@forEach
        val l = wdg.x
        val r = (wdg.x + wdg.w).coerceAtMost(1f)
        val t = wdg.y
        val b = (wdg.y + wdg.h).coerceAtMost(1f)
        v.add(l); v.add((l + r) * 0.5f); v.add(r)
        h.add(t); h.add((t + b) * 0.5f); h.add(b)
    }
    return CanvasGuideLines(
        vertical = v.toFloatArray(),
        horizontal = h.toFloatArray(),
    )
}

/** Convenience: snap top-left only. */
fun smartAlignTopLeft(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    guides: CanvasGuideLines,
    thresh: Float,
    enabled: Boolean = MINIMAL_LAUNCHER_ALIGN_ENABLED,
): CanvasPoint = smartAlignSnap(x, y, w, h, guides, thresh, enabled).point

/**
 * Icon drop: ruler snap (gap-aware), then separate if still overlapping.
 * Guides are re-matched on the **final** seat so a 1px gap nudge no longer
 * hides the ruler when siblings sit on both left and right.
 */
fun placeIconWithAlign(
    x: Float,
    y: Float,
    iconW: Float,
    iconH: Float,
    page: Int,
    icons: List<PlacedMinimalLauncherIcon>,
    widgets: List<PlacedMinimalLauncherWidget>,
    exceptIconId: String?,
    thresh: Float,
    pageWidthPx: Float,
    pageHeightPx: Float,
    density: Float,
    enabled: Boolean = MINIMAL_LAUNCHER_ALIGN_ENABLED,
): AlignSnapResult {
    val (gapX, gapY) = iconGapAxes(pageWidthPx, pageHeightPx, density)
    val guides = buildAlignGuides(
        page, icons, widgets, iconW, iconH, exceptIconId, exceptWidgetId = null,
        gapX = gapX, gapY = gapY,
    )
    val snap = smartAlignSnap(x, y, iconW, iconH, guides, thresh, enabled)
    val sep = resolveIconSeparation(
        snap.x, snap.y, iconW, iconH,
        page, icons, iconW, iconH, exceptIconId,
        gapX, gapY, pageWidthPx, pageHeightPx,
    )
    // Re-evaluate rulers at the settled seat (don't clear just because gap nudge ran).
    val matched = matchAlignGuides(sep.x, sep.y, iconW, iconH, guides, thresh, enabled)
    return AlignSnapResult(
        x = sep.x,
        y = sep.y,
        w = iconW,
        h = iconH,
        guideV = matched.first,
        guideH = matched.second,
    )
}

/**
 * Report which guide lines the rect currently sits on (no position change).
 */
fun matchAlignGuides(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    guides: CanvasGuideLines,
    thresh: Float,
    enabled: Boolean = MINIMAL_LAUNCHER_ALIGN_ENABLED,
): Pair<Float?, Float?> {
    if (!enabled || thresh <= 0f) return null to null
    val rect = clampCanvasRect(x, y, w, h)
    val left = rect.x
    val right = rect.x + rect.w
    val cx = rect.x + rect.w * 0.5f
    val top = rect.y
    val bottom = rect.y + rect.h
    val cy = rect.y + rect.h * 0.5f

    fun nearest(samples: FloatArray, guideValues: FloatArray): Float? {
        var bestG: Float? = null
        var bestD = Float.MAX_VALUE
        for (g in guideValues) {
            for (value in samples) {
                val a = abs(g - value)
                if (a <= thresh && a < bestD) {
                    bestD = a
                    bestG = g
                }
            }
        }
        return bestG
    }

    return nearest(floatArrayOf(left, right, cx), guides.vertical) to
        nearest(floatArrayOf(top, bottom, cy), guides.horizontal)
}

/**
 * Push an icon seat clear of siblings (with per-axis gaps). Uses MTV nudges
 * in pixel space; if still jammed, searches the nearest free seat.
 */
fun resolveIconSeparation(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    page: Int,
    icons: List<PlacedMinimalLauncherIcon>,
    iconW: Float,
    iconH: Float,
    exceptIconId: String?,
    gapX: Float,
    gapY: Float,
    pageWidthPx: Float,
    pageHeightPx: Float,
): CanvasPoint {
    var cur = clampCanvasRect(x, y, w, h)
    val others = icons.filter { it.screen == page && it.id != exceptIconId }
    if (others.isEmpty()) return CanvasPoint(cur.x, cur.y)
    val pageW = pageWidthPx.coerceAtLeast(1f)
    val pageH = pageHeightPx.coerceAtLeast(1f)

    fun conflicts(px: Float, py: Float): Boolean {
        for (other in others) {
            if (rectsOverlapWithGap(
                    px, py, w, h, other.x, other.y, iconW, iconH, gapX, gapY,
                )
            ) {
                return true
            }
        }
        return false
    }

    repeat(20) {
        var pushed = false
        for (other in others) {
            if (!rectsOverlapWithGap(
                    cur.x, cur.y, cur.w, cur.h,
                    other.x, other.y, iconW, iconH, gapX, gapY,
                )
            ) {
                continue
            }
            val ol = other.x - gapX
            val or = other.x + iconW + gapX
            val ot = other.y - gapY
            val ob = other.y + iconH + gapY
            val pushRight = or - cur.x
            val pushLeft = (cur.x + cur.w) - ol
            val pushDown = ob - cur.y
            val pushUp = (cur.y + cur.h) - ot
            val ox = min(pushRight, pushLeft)
            val oy = min(pushDown, pushUp)
            // Prefer the shorter push in pixels (norm X/Y are not comparable).
            cur = if (ox * pageW <= oy * pageH) {
                val dx = if (pushRight < pushLeft) pushRight else -pushLeft
                clampCanvasRect(cur.x + dx, cur.y, cur.w, cur.h)
            } else {
                val dy = if (pushDown < pushUp) pushDown else -pushUp
                clampCanvasRect(cur.x, cur.y + dy, cur.w, cur.h)
            }
            pushed = true
        }
        if (!pushed) return CanvasPoint(cur.x, cur.y)
        if (!conflicts(cur.x, cur.y)) return CanvasPoint(cur.x, cur.y)
    }

    return findNearestFreeIconSeat(
        x, y, w, h, page, icons, iconW, iconH, exceptIconId, gapX, gapY, pageW, pageH,
    ) ?: CanvasPoint(cur.x, cur.y)
}

private fun rectsOverlapWithGap(
    ax: Float, ay: Float, aw: Float, ah: Float,
    bx: Float, by: Float, bw: Float, bh: Float,
    gapX: Float,
    gapY: Float,
): Boolean {
    val gx = gapX.coerceAtLeast(0f)
    val gy = gapY.coerceAtLeast(0f)
    return ax < bx + bw + gx &&
        ax + aw + gx > bx &&
        ay < by + bh + gy &&
        ay + ah + gy > by
}

private fun findNearestFreeIconSeat(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    page: Int,
    icons: List<PlacedMinimalLauncherIcon>,
    iconW: Float,
    iconH: Float,
    exceptIconId: String?,
    gapX: Float,
    gapY: Float,
    pageWidthPx: Float,
    pageHeightPx: Float,
): CanvasPoint? {
    val prefer = clampCanvasRect(x, y, w, h)
    val others = icons.filter { it.screen == page && it.id != exceptIconId }
    fun free(px: Float, py: Float): CanvasPoint? {
        val r = clampCanvasRect(px, py, w, h)
        for (other in others) {
            if (rectsOverlapWithGap(
                    r.x, r.y, r.w, r.h, other.x, other.y, iconW, iconH, gapX, gapY,
                )
            ) {
                return null
            }
        }
        return CanvasPoint(r.x, r.y)
    }
    free(prefer.x, prefer.y)?.let { return it }

    val pageW = pageWidthPx.coerceAtLeast(1f)
    val pageH = pageHeightPx.coerceAtLeast(1f)
    // Spiral in roughly equal pixel rings (not equal norm rings).
    val stepPx = maxOf(w * pageW, h * pageH, gapX * pageW * 2f, gapY * pageH * 2f) * 0.45f
    var best: CanvasPoint? = null
    var bestDist = Float.MAX_VALUE
    fun consider(px: Float, py: Float) {
        val seat = free(px, py) ?: return
        val dx = (seat.x - prefer.x) * pageW
        val dy = (seat.y - prefer.y) * pageH
        val d = dx * dx + dy * dy
        if (d < bestDist) {
            bestDist = d
            best = seat
        }
    }
    for (ring in 1..16) {
        val samples = 8 + ring * 4
        val distPx = ring * stepPx
        for (i in 0 until samples) {
            val a = (i.toFloat() / samples) * (2f * PI.toFloat())
            consider(
                prefer.x + cos(a) * distPx / pageW,
                prefer.y + sin(a) * distPx / pageH,
            )
        }
        best?.let { return it }
    }
    // Dense fallback — walk a coarse grid of icon seats.
    val cols = (1f / w).toInt().coerceIn(2, 16)
    val rows = (1f / h).toInt().coerceIn(2, 16)
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            consider(c * w, r * h)
        }
    }
    return best
}

/**
 * Ruler snap: item L/C/R and T/C/B stick to sibling/page guides.
 * Edge matches beat center matches when equally close (flush align first).
 */
fun smartAlignSnap(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    guides: CanvasGuideLines,
    thresh: Float,
    enabled: Boolean = MINIMAL_LAUNCHER_ALIGN_ENABLED,
): AlignSnapResult {
    val rect = clampCanvasRect(x, y, w, h)
    if (!enabled || thresh <= 0f) {
        return AlignSnapResult(rect.x, rect.y, rect.w, rect.h)
    }
    val left = rect.x
    val right = rect.x + rect.w
    val cx = rect.x + rect.w * 0.5f
    val top = rect.y
    val bottom = rect.y + rect.h
    val cy = rect.y + rect.h * 0.5f

    data class Cand(val delta: Float, val guide: Float, val priority: Int)

    fun bestAxis(
        samples: List<Pair<Float, Int>>,
        guideValues: FloatArray,
    ): Cand? {
        var best: Cand? = null
        for (g in guideValues) {
            for ((value, pri) in samples) {
                val d = g - value
                val a = abs(d)
                if (a > thresh) continue
                val cand = Cand(d, g, pri)
                val cur = best
                if (cur == null ||
                    a < abs(cur.delta) - 1e-6f ||
                    (abs(a - abs(cur.delta)) <= 1e-6f && pri < cur.priority)
                ) {
                    best = cand
                }
            }
        }
        return best
    }

    val xSnap = bestAxis(listOf(left to 0, right to 0, cx to 1), guides.vertical)
    val ySnap = bestAxis(listOf(top to 0, bottom to 0, cy to 1), guides.horizontal)
    val out = clampCanvasRect(
        rect.x + (xSnap?.delta ?: 0f),
        rect.y + (ySnap?.delta ?: 0f),
        rect.w,
        rect.h,
    )
    return AlignSnapResult(
        x = out.x,
        y = out.y,
        w = out.w,
        h = out.h,
        guideV = xSnap?.guide,
        guideH = ySnap?.guide,
    )
}

/**
 * Snap a resize rect so the moving edges hug guides.
 * [pinLeft]/[pinTop] keep the opposite edge fixed when snapping width/height.
 */
fun smartAlignRect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    guides: CanvasGuideLines,
    thresh: Float,
    pinLeft: Boolean = false,
    pinRight: Boolean = false,
    pinTop: Boolean = false,
    pinBottom: Boolean = false,
    enabled: Boolean = MINIMAL_LAUNCHER_ALIGN_ENABLED,
): CanvasRect {
    var rect = clampCanvasRect(x, y, w, h)
    if (!enabled || thresh <= 0f) return rect
    if (!pinLeft) {
        val snap = nearestGuide(rect.x, guides.vertical, thresh)
        if (snap != null) {
            val right = rect.x + rect.w
            rect = clampCanvasRect(
                snap, rect.y,
                (right - snap).coerceAtLeast(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC), rect.h,
            )
        }
    }
    if (!pinRight) {
        val right = rect.x + rect.w
        val snap = nearestGuide(right, guides.vertical, thresh)
        if (snap != null) {
            rect = clampCanvasRect(
                rect.x, rect.y,
                (snap - rect.x).coerceAtLeast(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC), rect.h,
            )
        }
    }
    if (!pinTop) {
        val snap = nearestGuide(rect.y, guides.horizontal, thresh)
        if (snap != null) {
            val bottom = rect.y + rect.h
            rect = clampCanvasRect(
                rect.x, snap, rect.w,
                (bottom - snap).coerceAtLeast(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC),
            )
        }
    }
    if (!pinBottom) {
        val bottom = rect.y + rect.h
        val snap = nearestGuide(bottom, guides.horizontal, thresh)
        if (snap != null) {
            rect = clampCanvasRect(
                rect.x, rect.y, rect.w,
                (snap - rect.y).coerceAtLeast(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC),
            )
        }
    }
    return clampCanvasRect(rect.x, rect.y, rect.w, rect.h)
}

private fun nearestGuide(value: Float, guides: FloatArray, thresh: Float): Float? {
    var best: Float? = null
    var bestAbs = thresh
    for (g in guides) {
        val a = abs(g - value)
        if (a <= bestAbs) {
            bestAbs = a
            best = g
        }
    }
    return best
}
