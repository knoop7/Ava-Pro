package com.example.ava.ui.screens.home

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted desktop app shortcuts on a free canvas.
 * Portrait and landscape each keep independent normalized (x, y) placements.
 * Orientation MUST be passed from Compose ([LocalConfiguration]) — never inferred
 * from [Context.applicationContext], which often stays stuck in portrait.
 */
data class PlacedMinimalLauncherIcon(
    val id: String,
    val packageName: String,
    val activityName: String,
    /** Portrait page + normalized top-left (0..1 of page). */
    val screen: Int,
    val x: Float,
    val y: Float,
    /** Landscape placement — independent canvas. */
    val landScreen: Int,
    val landX: Float,
    val landY: Float,
) {
    fun forLayout(isLandscape: Boolean): PlacedMinimalLauncherIcon {
        if (!isLandscape) return this
        return copy(screen = landScreen, x = landX, y = landY)
    }

    fun withPlacement(
        isLandscape: Boolean,
        screen: Int,
        x: Float,
        y: Float,
    ): PlacedMinimalLauncherIcon {
        val sc = screen.coerceAtLeast(0)
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        return if (isLandscape) {
            copy(landScreen = sc, landX = nx, landY = ny)
        } else {
            copy(screen = sc, x = nx, y = ny)
        }
    }

    companion object {
        fun idFor(packageName: String, activityName: String): String = "$packageName/$activityName"
    }
}

fun List<PlacedMinimalLauncherIcon>.forLayout(
    isLandscape: Boolean,
): List<PlacedMinimalLauncherIcon> = map { it.forLayout(isLandscape) }

object MinimalLauncherIconsStore {
    private const val PREFS = "minimal_launcher_icons"
    private const val KEY_ITEMS = "items"

    private val _icons = MutableStateFlow<List<PlacedMinimalLauncherIcon>>(emptyList())
    val iconsFlow: StateFlow<List<PlacedMinimalLauncherIcon>> = _icons.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoadedFlow: StateFlow<Boolean> = _isLoaded.asStateFlow()

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ITEMS, null) ?: "[]"
        _icons.value = sortItems(parse(raw))
        _isLoaded.value = true
    }

    /**
     * True once this install has written a desktop layout at least once — including a
     * layout the user has since emptied. [persist] is the only writer, so this is the
     * signal that keeps first-run seeding away from existing installs.
     */
    fun hasPersistedLayout(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .contains(KEY_ITEMS)

    fun snapshotRaw(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]") ?: "[]"

    fun restoreFromRaw(context: Context, raw: String) {
        replaceAll(context, parse(raw))
    }

    fun replaceAll(context: Context, items: List<PlacedMinimalLauncherIcon>) {
        persist(context, sortItems(items))
    }

    /**
     * Place / replace at an exact free-canvas point in [isLandscape].
     * The other orientation is seeded with the same normalized point once
     * (independent thereafter — no grid vacancy hunting).
     */
    fun placeAt(
        context: Context,
        isLandscape: Boolean,
        packageName: String,
        activityName: String,
        screen: Int,
        x: Float,
        y: Float,
    ): PlacedMinimalLauncherIcon {
        val id = PlacedMinimalLauncherIcon.idFor(packageName, activityName)
        // Capture existing BEFORE filter — otherwise the other orientation is always re-seeded.
        val existing = _icons.value.find { it.id == id }
        val without = _icons.value.filterNot { it.id == id }
        val item = newDualPlacement(
            id, packageName, activityName, isLandscape, screen, x, y, existing,
        )
        persist(context, sortItems(without + item))
        return item
    }

    fun placeNew(
        context: Context,
        isLandscape: Boolean,
        packageName: String,
        activityName: String,
    ): PlacedMinimalLauncherIcon {
        val lastScreen = maxOf(
            _icons.value.maxOfOrNull { maxOf(it.screen, it.landScreen) } ?: -1,
            MinimalLauncherWidgetsStore.widgetsFlow.value
                .maxOfOrNull { maxOf(it.screen, it.landScreen) } ?: -1,
        )
        val screen = lastScreen.coerceAtLeast(0)
        val n = _icons.value.size
        val x = (0.06f + (n % 4) * 0.2f).coerceIn(0f, 0.82f)
        val y = (0.08f + (n / 4) * 0.22f).coerceIn(0f, 0.78f)
        return placeAt(context, isLandscape, packageName, activityName, screen, x, y)
    }

    /** Move in [isLandscape] only — other orientation untouched. */
    fun move(
        context: Context,
        isLandscape: Boolean,
        id: String,
        screen: Int,
        x: Float,
        y: Float,
    ): Boolean {
        val current = _icons.value.find { it.id == id } ?: return false
        val others = _icons.value.filterNot { it.id == id }
        persist(
            context,
            sortItems(others + current.withPlacement(isLandscape, screen, x, y)),
        )
        return true
    }

    fun remove(context: Context, id: String) {
        removeQuiet(context, id)
        stripEmptyScreens(context)
    }

    fun removeQuiet(context: Context, id: String) {
        persist(context, sortItems(_icons.value.filterNot { it.id == id }))
    }

    fun removePackage(context: Context, packageName: String) {
        persist(context, sortItems(_icons.value.filterNot { it.packageName == packageName }))
        stripEmptyScreens(context)
    }

    private fun newDualPlacement(
        id: String,
        packageName: String,
        activityName: String,
        isLandscape: Boolean,
        screen: Int,
        x: Float,
        y: Float,
        existing: PlacedMinimalLauncherIcon?,
    ): PlacedMinimalLauncherIcon {
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        val sc = screen.coerceAtLeast(0)
        // Seed the other orientation with the same normalized point when first created;
        // if the icon already exists, keep the other orientation untouched.
        return if (isLandscape) {
            PlacedMinimalLauncherIcon(
                id = id,
                packageName = packageName,
                activityName = activityName,
                screen = existing?.screen ?: sc,
                x = existing?.x ?: nx,
                y = existing?.y ?: ny,
                landScreen = sc,
                landX = nx,
                landY = ny,
            )
        } else {
            PlacedMinimalLauncherIcon(
                id = id,
                packageName = packageName,
                activityName = activityName,
                screen = sc,
                x = nx,
                y = ny,
                landScreen = existing?.landScreen ?: sc,
                landX = existing?.landX ?: nx,
                landY = existing?.landY ?: ny,
            )
        }
    }

    private fun sortItems(items: List<PlacedMinimalLauncherIcon>): List<PlacedMinimalLauncherIcon> =
        items.sortedWith(compareBy({ it.screen }, { it.y }, { it.x }))

    private fun persist(context: Context, items: List<PlacedMinimalLauncherIcon>) {
        _icons.value = items
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("packageName", item.packageName)
                    .put("activityName", item.activityName)
                    .put("screen", item.screen)
                    .put("x", item.x.toDouble())
                    .put("y", item.y.toDouble())
                    .put("landScreen", item.landScreen)
                    .put("landX", item.landX.toDouble())
                    .put("landY", item.landY.toDouble())
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
    }

    private fun parse(raw: String): List<PlacedMinimalLauncherIcon> {
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val packageName = o.getString("packageName")
                    val activityName = o.getString("activityName")
                    val screen = o.optInt("screen", 0).coerceAtLeast(0)
                    val (x, y, landScreen, landX, landY) = parsePlacement(o, screen)
                    add(
                        PlacedMinimalLauncherIcon(
                            id = o.optString("id").ifEmpty {
                                PlacedMinimalLauncherIcon.idFor(packageName, activityName)
                            },
                            packageName = packageName,
                            activityName = activityName,
                            screen = screen,
                            x = x,
                            y = y,
                            landScreen = landScreen,
                            landX = landX,
                            landY = landY,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Free-canvas floats, or one-shot migrate from legacy cellX/cellY. */
    private fun parsePlacement(
        o: JSONObject,
        screen: Int,
    ): IconPlacementParsed {
        if (o.has("x") && o.has("y")) {
            val x = o.optDouble("x", 0.0).toFloat().coerceIn(0f, 1f)
            val y = o.optDouble("y", 0.0).toFloat().coerceIn(0f, 1f)
            val landScreen = o.optInt("landScreen", screen).coerceAtLeast(0)
            val landX = o.optDouble("landX", x.toDouble()).toFloat().coerceIn(0f, 1f)
            val landY = o.optDouble("landY", y.toDouble()).toFloat().coerceIn(0f, 1f)
            return IconPlacementParsed(x, y, landScreen, landX, landY)
        }
        // Legacy grid → free canvas (5-col / 5-row assumption for migration only).
        val cols = 5
        val rows = 5
        val cellX = o.optInt("cellX", 0)
        val cellY = o.optInt("cellY", 0)
        val (x, _) = migrateCellToNorm(cellX, 1, cols)
        val (y, _) = migrateCellToNorm(cellY, 1, rows)
        val landScreen = o.optInt("landScreen", screen).coerceAtLeast(0)
        val landCellX = o.optInt("landCellX", cellX)
        val landCellY = o.optInt("landCellY", cellY)
        val (landX, _) = migrateCellToNorm(landCellX, 1, cols)
        val (landY, _) = migrateCellToNorm(landCellY, 1, rows)
        return IconPlacementParsed(x, y, landScreen, landX, landY)
    }

    private data class IconPlacementParsed(
        val x: Float,
        val y: Float,
        val landScreen: Int,
        val landX: Float,
        val landY: Float,
    )
}
