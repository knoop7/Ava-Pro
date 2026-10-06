package com.example.ava.ui.screens.home

import android.content.Context

/**
 * One-tap "put every app on the desktop".
 *
 * Additive on purpose. This is the only route an existing install has to a populated
 * desktop — first-run seeding is one-shot and permanently gated — so it must not throw
 * away a layout somebody already arranged by hand. Apps already on the desktop are left
 * exactly where they are, and new icons only land on grid seats that collide with
 * nothing, spilling onto fresh pages when the current ones are busy.
 *
 * Portrait and landscape are independent canvases with different capacities, so seats are
 * walked separately per orientation and paired by index.
 */
object MinimalLauncherFillLayout {

    /** Ceiling on how far the fill will spill. Well past any real app drawer. */
    private const val MAX_PAGES = 16

    private data class Seat(val page: Int, val x: Float, val y: Float)

    /**
     * Places every app in [apps] that is not on the desktop yet.
     *
     * [apps] is expected to be the *visible* app list — the one the "choose apps"
     * whitelist already filtered — so the fill never contradicts that setting.
     *
     * @return how many icons were added; 0 when the desktop already holds them all.
     */
    fun fill(
        context: Context,
        apps: List<MinimalLauncherApp>,
        portrait: MinimalLauncherDefaultLayout.PageSize,
        landscape: MinimalLauncherDefaultLayout.PageSize,
        showDesktopLabels: Boolean,
    ): Int {
        val placed = MinimalLauncherIconsStore.iconsFlow.value
        val alreadyOnDesktop = placed.mapTo(HashSet()) { it.id }
        val missing = apps
            .distinctBy { PlacedMinimalLauncherIcon.idFor(it.packageName, it.activityName) }
            .filterNot {
                PlacedMinimalLauncherIcon.idFor(it.packageName, it.activityName) in alreadyOnDesktop
            }
        if (missing.isEmpty()) return 0

        val widgets = MinimalLauncherWidgetsStore.widgetsFlow.value
        val density = context.resources.displayMetrics.density
        val portraitSeats = freeSeats(
            portrait, density, showDesktopLabels, placed, widgets,
            landscape = false, needed = missing.size,
        )
        val landscapeSeats = freeSeats(
            landscape, density, showDesktopLabels, placed, widgets,
            landscape = true, needed = missing.size,
        )
        // Every icon needs a seat in both canvases, so the tighter orientation caps it.
        val room = minOf(portraitSeats.size, landscapeSeats.size, missing.size)
        if (room <= 0) return 0

        val additions = missing.take(room).mapIndexed { index, app ->
            PlacedMinimalLauncherIcon(
                id = PlacedMinimalLauncherIcon.idFor(app.packageName, app.activityName),
                packageName = app.packageName,
                activityName = app.activityName,
                screen = portraitSeats[index].page,
                x = portraitSeats[index].x,
                y = portraitSeats[index].y,
                landScreen = landscapeSeats[index].page,
                landX = landscapeSeats[index].x,
                landY = landscapeSeats[index].y,
            )
        }
        MinimalLauncherIconsStore.replaceAll(context, placed + additions)
        return additions.size
    }

    /**
     * How many apps a tap would add right now — drives the settings subtitle so the row
     * can say up front that there is nothing left to place.
     */
    fun pendingCount(apps: List<MinimalLauncherApp>): Int {
        val alreadyOnDesktop = MinimalLauncherIconsStore.iconsFlow.value.mapTo(HashSet()) { it.id }
        return apps
            .distinctBy { PlacedMinimalLauncherIcon.idFor(it.packageName, it.activityName) }
            .count {
                PlacedMinimalLauncherIcon.idFor(it.packageName, it.activityName) !in alreadyOnDesktop
            }
    }

    /**
     * Grid seats that nothing already occupies, in reading order, page by page.
     *
     * The grid is the same seat size the workspace draws with, centred on the page, so
     * added icons line up with anything first-run seeding put there.
     */
    private fun freeSeats(
        size: MinimalLauncherDefaultLayout.PageSize,
        density: Float,
        showDesktopLabels: Boolean,
        icons: List<PlacedMinimalLauncherIcon>,
        widgets: List<PlacedMinimalLauncherWidget>,
        landscape: Boolean,
        needed: Int,
    ): List<Seat> {
        val grid = minimalLauncherSeatGrid(
            pageWidthPx = size.widthPx,
            pageHeightPx = size.heightPx,
            density = density,
            showDesktopLabels = showDesktopLabels,
        ) ?: return emptyList()
        val iconW = grid.seatW
        val iconH = grid.seatH

        val occupied = HashMap<Int, MutableList<CanvasRect>>()
        icons.forEach { icon ->
            val page = if (landscape) icon.landScreen else icon.screen
            val x = if (landscape) icon.landX else icon.x
            val y = if (landscape) icon.landY else icon.y
            occupied.getOrPut(page) { mutableListOf() }.add(CanvasRect(x, y, iconW, iconH))
        }
        widgets.forEach { widget ->
            val placement = widget.placement(landscape)
            occupied.getOrPut(placement.screen) { mutableListOf() }
                .add(CanvasRect(placement.x, placement.y, placement.w, placement.h))
        }

        val seats = mutableListOf<Seat>()
        val lattice = grid.seats()
        for (page in 0 until MAX_PAGES) {
            val taken = occupied[page].orEmpty()
            lattice.forEach { seat ->
                if (taken.none { minimalLauncherRectsOverlap(it, seat.x, seat.y, iconW, iconH) }) {
                    seats += Seat(page, seat.x, seat.y)
                    if (seats.size >= needed) return seats
                }
            }
        }
        return seats
    }
}
