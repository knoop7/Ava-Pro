package com.example.ava.ui.screens.home

import android.content.Context

/**
 * Free-canvas workspace page bookkeeping (no cell occupancy).
 * Idle workspace strips empty pages so holes disappear.
 *
 * Portrait and landscape page indices are compacted independently so deleting
 * an empty page in one orientation never renumbers the other.
 */

/** Joint strip after drop / delete — renumber screens so empty pages disappear. */
fun stripEmptyScreens(context: Context) {
    val icons = MinimalLauncherIconsStore.iconsFlow.value
    val widgets = MinimalLauncherWidgetsStore.widgetsFlow.value
    val (nextIcons, nextWidgets) =
        MinimalLauncherWorkspaceLayout.compactScreens(icons, widgets)
    if (nextIcons === icons && nextWidgets === widgets) return
    if (nextIcons != icons) MinimalLauncherIconsStore.replaceAll(context, nextIcons)
    if (nextWidgets != widgets) MinimalLauncherWidgetsStore.replaceAll(context, nextWidgets)
}

object MinimalLauncherWorkspaceLayout {
    fun maxOccupiedScreen(
        icons: List<PlacedMinimalLauncherIcon>,
        widgets: List<PlacedMinimalLauncherWidget>,
    ): Int {
        val maxIcon = icons.maxOfOrNull { it.screen } ?: -1
        val maxWidget = widgets.maxOfOrNull { it.screen } ?: -1
        return maxOf(maxIcon, maxWidget)
    }

    fun occupiedPageCount(
        icons: List<PlacedMinimalLauncherIcon>,
        widgets: List<PlacedMinimalLauncherWidget>,
    ): Int = maxOf(1, maxOccupiedScreen(icons, widgets) + 1)

    fun pageCount(
        icons: List<PlacedMinimalLauncherIcon>,
        widgets: List<PlacedMinimalLauncherWidget>,
        withExtraEmpty: Boolean,
    ): Int {
        val occupied = occupiedPageCount(icons, widgets)
        return if (withExtraEmpty) occupied + 1 else occupied
    }

    fun extraEmptyPageIndex(
        icons: List<PlacedMinimalLauncherIcon>,
        widgets: List<PlacedMinimalLauncherWidget>,
        withExtraEmpty: Boolean,
    ): Int {
        if (!withExtraEmpty) return -1
        return occupiedPageCount(icons, widgets)
    }

    fun compactScreens(
        icons: List<PlacedMinimalLauncherIcon>,
        widgets: List<PlacedMinimalLauncherWidget>,
    ): Pair<List<PlacedMinimalLauncherIcon>, List<PlacedMinimalLauncherWidget>> {
        val portUsed = sortedSetOf<Int>().apply {
            icons.forEach { add(it.screen) }
            widgets.forEach { add(it.screen) }
        }
        val landUsed = sortedSetOf<Int>().apply {
            icons.forEach { add(it.landScreen) }
            widgets.forEach { add(it.landScreen) }
        }
        if (portUsed.isEmpty() && landUsed.isEmpty()) return icons to widgets

        val portRemap = denseRemap(portUsed)
        val landRemap = denseRemap(landUsed)
        val portChanged = portUsed.withIndex().any { (index, screen) -> screen != index }
        val landChanged = landUsed.withIndex().any { (index, screen) -> screen != index }
        if (!portChanged && !landChanged) return icons to widgets

        val nextIcons = icons.map {
            it.copy(
                screen = portRemap.getValue(it.screen),
                landScreen = landRemap.getValue(it.landScreen),
            )
        }.sortedWith(compareBy({ it.screen }, { it.y }, { it.x }))
        val nextWidgets = widgets.map {
            it.copy(
                screen = portRemap.getValue(it.screen),
                landScreen = landRemap.getValue(it.landScreen),
            )
        }.sortedWith(compareBy({ it.screen }, { it.y }, { it.x }))
        return nextIcons to nextWidgets
    }

    /** Map occupied screen indices → 0..n-1 without gaps. Empty → empty map. */
    private fun denseRemap(used: Set<Int>): Map<Int, Int> =
        used.sorted().withIndex().associate { (index, screen) -> screen to index }
}
