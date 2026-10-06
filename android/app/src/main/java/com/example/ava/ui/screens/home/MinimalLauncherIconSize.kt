package com.example.ava.ui.screens.home

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-chosen desktop icon size, expressed as a step *offset* from the size the grid
 * computes on its own.
 *
 * Storing an offset rather than an absolute seat count is what makes this safe to add to
 * an app that already has users: step 0 reproduces the previous arithmetic exactly, so
 * nobody's desktop moves until they ask for it. It also travels correctly between
 * orientations, which pack along different edges — portrait packs by width, landscape by
 * height — so one absolute seat count would mean two different icon sizes.
 *
 * A positive step means bigger icons, which means *fewer* seats along the packing edge.
 */
object MinimalLauncherIconSizeStore {

    private const val PREFS = "minimal_launcher_icon_size"
    private const val KEY_STEP = "size_step"

    /**
     * Detents offered by the adjuster, smallest first.
     *
     * Wider than the original ±2 so a phone can actually reach both "two huge icons"
     * and "as many as the 48dp floor allows". Duplicates are still stripped per page
     * in [minimalLauncherIconSizeDetents], so a notch the user feels always changes size.
     */
    val STEPS = (-6..3).toList()

    private val _step = MutableStateFlow(0)
    val stepFlow: StateFlow<Int> = _step.asStateFlow()

    /**
     * Live value while the adjuster is open, null otherwise.
     *
     * Kept out of [stepFlow] and off disk so dragging the slider never commits: the
     * desktop re-renders at the previewed size, and abandoning the adjuster leaves no
     * trace.
     */
    private val _preview = MutableStateFlow<Int?>(null)
    val previewFlow: StateFlow<Int?> = _preview.asStateFlow()

    fun load(context: Context) {
        _step.value = prefs(context).getInt(KEY_STEP, 0).clampToRange()
    }

    /** Synchronous read for the pure layout functions. */
    fun effective(): Int = (_preview.value ?: _step.value).clampToRange()

    fun preview(step: Int?) {
        _preview.value = step?.clampToRange()
    }

    fun commit(context: Context, step: Int) {
        val next = step.clampToRange()
        _step.value = next
        _preview.value = null
        prefs(context).edit().putInt(KEY_STEP, next).apply()
    }

    fun cancelPreview() {
        _preview.value = null
    }

    private fun Int.clampToRange(): Int = coerceIn(STEPS.first(), STEPS.last())

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * One position on the size slider, resolved against a real page.
 *
 * [seatsAcross] is what the user actually perceives — "how many fit in a row" — so the
 * adjuster can label the detent with a fact rather than a vague "large".
 */
data class MinimalLauncherIconSizeDetent(
    val step: Int,
    val seatsAcross: Int,
    val seatWidthNorm: Float,
)

/**
 * The detents worth offering on this page, smallest icon first.
 *
 * The seat count is clamped to 2..10 and additionally floored at a minimum icon size, so
 * near the ends of the range several steps collapse onto the same result — a 4-across
 * phone cannot go bigger than 2-across however far the slider travels. Probing the real
 * page and dropping duplicates means every notch the user feels changes something, which
 * is what makes a detented slider feel honest instead of broken.
 */
fun minimalLauncherIconSizeDetents(
    pageWidthPx: Float,
    pageHeightPx: Float,
    density: Float,
    showDesktopLabels: Boolean,
): List<MinimalLauncherIconSizeDetent> {
    if (pageWidthPx <= 1f || pageHeightPx <= 1f) return emptyList()
    val landscape = pageWidthPx > pageHeightPx
    val seen = HashSet<Int>()
    val detents = mutableListOf<MinimalLauncherIconSizeDetent>()
    // Ascending step is ascending icon size, which is the order the slider reads in.
    MinimalLauncherIconSizeStore.STEPS.forEach { step ->
        val (seatW, seatH) = iconBoxNorm(
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            showLabel = showDesktopLabels,
            density = density,
            sizeStep = step,
        )
        // Landscape packs along height, so that is the edge whose seat count the user sees.
        val packSeat = if (landscape) seatH else seatW
        val across = if (packSeat > 0f) (1f / packSeat).toInt() else 0
        if (seen.add((packSeat * 10_000f).toInt())) {
            detents += MinimalLauncherIconSizeDetent(
                step = step,
                seatsAcross = across,
                seatWidthNorm = seatW,
            )
        }
    }
    return detents
}

/**
 * The seat lattice a page is packed with: whole seats only, centred on the page.
 *
 * Shared so bulk placement and icon-size reflow can never disagree about where a tidy
 * seat is — a mismatch there shows up as icons that look aligned but refuse to accept a
 * drop, or overlap after a resize.
 */
data class MinimalLauncherSeatGrid(
    val columns: Int,
    val rows: Int,
    val startX: Float,
    val startY: Float,
    val seatW: Float,
    val seatH: Float,
    val gapX: Float,
    val gapY: Float,
) {
    val perPage: Int get() = columns * rows

    fun seat(index: Int): CanvasPoint {
        val column = index % columns
        val row = (index / columns) % rows
        return CanvasPoint(
            x = startX + column * (seatW + gapX),
            y = startY + row * (seatH + gapY),
        )
    }

    fun seats(): List<CanvasPoint> = List(perPage) { seat(it) }
}

/** Rects have to overlap by more than a hair to count as a collision. */
const val MINIMAL_LAUNCHER_COLLISION_EPSILON = 0.005f

/**
 * Builds the seat lattice for one page size, or null when not even one seat fits.
 *
 * [sizeStep] defaults to whatever the user picked, so callers that just want "the current
 * grid" need not know the setting exists.
 */
fun minimalLauncherSeatGrid(
    pageWidthPx: Float,
    pageHeightPx: Float,
    density: Float,
    showDesktopLabels: Boolean,
    sizeStep: Int = MinimalLauncherIconSizeStore.effective(),
): MinimalLauncherSeatGrid? {
    val (seatW, seatH) = iconBoxNorm(
        pageWidthPx = pageWidthPx,
        pageHeightPx = pageHeightPx,
        showLabel = showDesktopLabels,
        density = density,
        sizeStep = sizeStep,
    )
    val (gapX, gapY) = iconGapAxes(pageWidthPx, pageHeightPx, density)
    val columns = seatsAlong(seatW, gapX)
    val rows = seatsAlong(seatH, gapY)
    if (columns <= 0 || rows <= 0) return null
    return MinimalLauncherSeatGrid(
        columns = columns,
        rows = rows,
        startX = (1f - (columns * seatW + (columns - 1) * gapX)) / 2f,
        startY = (1f - (rows * seatH + (rows - 1) * gapY)) / 2f,
        seatW = seatW,
        seatH = seatH,
        gapX = gapX,
        gapY = gapY,
    )
}

/** Largest whole seat count that fits the page along one axis. */
private fun seatsAlong(seat: Float, gap: Float): Int {
    if (seat <= 0f || seat > 1f) return 0
    var count = 1
    while ((count + 1) * seat + count * gap <= 1f) count++
    return count
}

fun minimalLauncherRectsOverlap(
    rect: CanvasRect,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
): Boolean {
    val e = MINIMAL_LAUNCHER_COLLISION_EPSILON
    return rect.x < x + w - e &&
        x < rect.x + rect.w - e &&
        rect.y < y + h - e &&
        y < rect.y + rect.h - e
}

/**
 * Re-fits placed icons after the icon size changes.
 *
 * Icons are stored as a normalized top-left, so growing the seat around that point
 * makes neighbours overlap and the right/bottom edges run off the page. The current
 * page is always resolved first: if every icon still fits at its original point,
 * nothing moves; otherwise the page is packed onto the new seat lattice. Only what
 * still cannot sit on that page walks forward — never backward — and widgets stay put.
 *
 * Always call this against the layout captured when the adjuster opened. Feeding it
 * its own output would compound placements across slider ticks.
 */
object MinimalLauncherIconSizeReflow {

    /** Same ceiling as [MinimalLauncherFillLayout], well past any real desktop. */
    private const val MAX_PAGES = 16

    private data class Move(val page: Int, val point: CanvasPoint)

    fun reflow(
        context: Context,
        snapshot: List<PlacedMinimalLauncherIcon>,
        widgets: List<PlacedMinimalLauncherWidget>,
        portrait: MinimalLauncherDefaultLayout.PageSize,
        landscape: MinimalLauncherDefaultLayout.PageSize,
        showDesktopLabels: Boolean,
        fromStep: Int,
        toStep: Int,
    ): List<PlacedMinimalLauncherIcon> {
        if (snapshot.isEmpty()) return snapshot
        val density = context.resources.displayMetrics.density
        val portraitMoves = remap(
            snapshot, widgets, portrait, density, showDesktopLabels,
            fromStep, toStep, landscapeCanvas = false,
        )
        val landscapeMoves = remap(
            snapshot, widgets, landscape, density, showDesktopLabels,
            fromStep, toStep, landscapeCanvas = true,
        )
        return snapshot.map { icon ->
            val portraitMove = portraitMoves[icon.id]
            val landscapeMove = landscapeMoves[icon.id]
            icon.copy(
                screen = portraitMove?.page ?: icon.screen,
                x = portraitMove?.point?.x ?: icon.x,
                y = portraitMove?.point?.y ?: icon.y,
                landScreen = landscapeMove?.page ?: icon.landScreen,
                landX = landscapeMove?.point?.x ?: icon.landX,
                landY = landscapeMove?.point?.y ?: icon.landY,
            )
        }
    }

    private fun remap(
        icons: List<PlacedMinimalLauncherIcon>,
        widgets: List<PlacedMinimalLauncherWidget>,
        size: MinimalLauncherDefaultLayout.PageSize,
        density: Float,
        showDesktopLabels: Boolean,
        fromStep: Int,
        toStep: Int,
        landscapeCanvas: Boolean,
    ): Map<String, Move> {
        if (fromStep == toStep) return emptyMap()
        val grid = minimalLauncherSeatGrid(
            pageWidthPx = size.widthPx,
            pageHeightPx = size.heightPx,
            density = density,
            showDesktopLabels = showDesktopLabels,
            sizeStep = toStep,
        ) ?: return emptyMap()
        val newW = grid.seatW
        val newH = grid.seatH

        val moves = HashMap<String, Move>(icons.size)
        // Widgets keep their own size, but an icon must not be pushed under one.
        val takenByPage = HashMap<Int, MutableList<CanvasRect>>()
        widgets.forEach { widget ->
            val placement = widget.placement(landscapeCanvas)
            takenByPage.getOrPut(placement.screen) { mutableListOf() }
                .add(CanvasRect(placement.x, placement.y, placement.w, placement.h))
        }

        // Reading order, so a collision pushes the later icon rather than an arbitrary one.
        val ordered = icons.sortedWith(
            compareBy(
                { pageOf(it, landscapeCanvas) },
                { anchorOf(it, landscapeCanvas).y },
                { anchorOf(it, landscapeCanvas).x },
            ),
        )
        val byPage = ordered.groupBy { pageOf(it, landscapeCanvas) }
        val overflow = ArrayList<PlacedMinimalLauncherIcon>()
        // Every home page is settled before anyone spills, so page-0 leftovers cannot
        // steal seats from icons that already live on page 1.
        byPage.keys.sorted().forEach { page ->
            val pageIcons = byPage.getValue(page)
            val taken = takenByPage.getOrPut(page) { mutableListOf() }
            if (allFitAtOriginal(pageIcons, taken, newW, newH, landscapeCanvas)) {
                pageIcons.forEach { icon ->
                    val anchor = anchorOf(icon, landscapeCanvas)
                    taken.add(CanvasRect(anchor.x, anchor.y, newW, newH))
                    moves[icon.id] = Move(page, anchor)
                }
            } else {
                val freeSeats = grid.seats().filter { seat ->
                    taken.none {
                        minimalLauncherRectsOverlap(it, seat.x, seat.y, newW, newH)
                    }
                }
                pageIcons.forEachIndexed { index, icon ->
                    if (index < freeSeats.size) {
                        val seat = freeSeats[index]
                        taken.add(CanvasRect(seat.x, seat.y, newW, newH))
                        moves[icon.id] = Move(page, seat)
                    } else {
                        overflow += icon
                    }
                }
            }
        }
        overflow.forEach { icon ->
            val homePage = pageOf(icon, landscapeCanvas)
            val anchor = anchorOf(icon, landscapeCanvas)
            val resolved = firstFreeSeatFrom(grid, takenByPage, homePage + 1, newW, newH)
                ?: Move(homePage, anchor)
            takenByPage.getOrPut(resolved.page) { mutableListOf() }
                .add(CanvasRect(resolved.point.x, resolved.point.y, newW, newH))
            moves[icon.id] = resolved
        }
        return moves
    }

    /**
     * True when this page's icons can all keep their original points at the new seat
     * size — on the page, and not overlapping a widget or each other.
     */
    private fun allFitAtOriginal(
        pageIcons: List<PlacedMinimalLauncherIcon>,
        alreadyTaken: List<CanvasRect>,
        w: Float,
        h: Float,
        landscapeCanvas: Boolean,
    ): Boolean {
        val trial = alreadyTaken.toMutableList()
        pageIcons.forEach { icon ->
            val anchor = anchorOf(icon, landscapeCanvas)
            if (!boxFitsOnPage(anchor.x, anchor.y, w, h)) return false
            if (trial.any { minimalLauncherRectsOverlap(it, anchor.x, anchor.y, w, h) }) {
                return false
            }
            trial.add(CanvasRect(anchor.x, anchor.y, w, h))
        }
        return true
    }

    /** True when the grown/shrunk box still lies entirely on the page. */
    private fun boxFitsOnPage(x: Float, y: Float, w: Float, h: Float): Boolean {
        val e = MINIMAL_LAUNCHER_COLLISION_EPSILON
        return x >= -e && y >= -e && x + w <= 1f + e && y + h <= 1f + e
    }

    /**
     * First empty grid seat on [startPage] or any page after it, reading order.
     *
     * Walking forward (and not wrapping) is what stops a grow from stealing seats
     * on earlier screens.
     */
    private fun firstFreeSeatFrom(
        grid: MinimalLauncherSeatGrid,
        takenByPage: Map<Int, List<CanvasRect>>,
        startPage: Int,
        w: Float,
        h: Float,
    ): Move? {
        if (startPage >= MAX_PAGES) return null
        val lattice = grid.seats()
        for (page in startPage until MAX_PAGES) {
            val taken = takenByPage[page].orEmpty()
            lattice.forEach { seat ->
                if (taken.none { minimalLauncherRectsOverlap(it, seat.x, seat.y, w, h) }) {
                    return Move(page, seat)
                }
            }
        }
        return null
    }

    private fun pageOf(icon: PlacedMinimalLauncherIcon, landscapeCanvas: Boolean): Int =
        if (landscapeCanvas) icon.landScreen else icon.screen

    private fun anchorOf(icon: PlacedMinimalLauncherIcon, landscapeCanvas: Boolean): CanvasPoint =
        if (landscapeCanvas) CanvasPoint(icon.landX, icon.landY) else CanvasPoint(icon.x, icon.y)
}
