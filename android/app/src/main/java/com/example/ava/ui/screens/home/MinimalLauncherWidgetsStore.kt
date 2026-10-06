package com.example.ava.ui.screens.home

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Free-canvas workspace widget. Portrait and landscape each keep their own
 * normalized rect (x, y, w, h). Size follows the widget / user resize — not a grid.
 *
 * Orientation MUST be passed explicitly from Compose — never inferred from
 * [Context.applicationContext].
 */
data class PlacedMinimalLauncherWidget(
    val appWidgetId: Int,
    val provider: ComponentName,
    val screen: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val landScreen: Int,
    val landX: Float,
    val landY: Float,
    val landW: Float,
    val landH: Float,
) {
    fun placement(isLandscape: Boolean): WidgetOrientationPlacement =
        if (isLandscape) {
            WidgetOrientationPlacement(landScreen, landX, landY, landW, landH)
        } else {
            WidgetOrientationPlacement(screen, x, y, w, h)
        }

    fun forLayout(isLandscape: Boolean): PlacedMinimalLauncherWidget {
        if (!isLandscape) return this
        return copy(
            screen = landScreen,
            x = landX,
            y = landY,
            w = landW,
            h = landH,
        )
    }

    fun withPlacement(
        isLandscape: Boolean,
        screen: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ): PlacedMinimalLauncherWidget {
        val rect = clampCanvasRect(x, y, w, h)
        val sc = screen.coerceAtLeast(0)
        return if (isLandscape) {
            copy(
                landScreen = sc,
                landX = rect.x,
                landY = rect.y,
                landW = rect.w,
                landH = rect.h,
            )
        } else {
            copy(
                screen = sc,
                x = rect.x,
                y = rect.y,
                w = rect.w,
                h = rect.h,
            )
        }
    }
}

data class WidgetOrientationPlacement(
    val screen: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

fun Context.isMinimalLauncherLandscape(): Boolean {
    val configuration = resources.configuration
    if (com.example.ava.ui.isCompactSquareDisplay(
            this,
            configuration.screenWidthDp,
            configuration.screenHeightDp,
        )
    ) {
        return false
    }
    val height = configuration.screenHeightDp.coerceAtLeast(1)
    val width = configuration.screenWidthDp
    if (width <= height * 1.2f) return false
    if (configuration.orientation != Configuration.ORIENTATION_LANDSCAPE &&
        width <= height * 1.28f
    ) {
        return false
    }
    return true
}

fun List<PlacedMinimalLauncherWidget>.forLayout(isLandscape: Boolean): List<PlacedMinimalLauncherWidget> =
    map { it.forLayout(isLandscape) }

object MinimalLauncherWidgetsStore {
    private const val PREFS = "minimal_launcher_widgets"
    private const val KEY_ITEMS = "items"

    private val _widgets = MutableStateFlow<List<PlacedMinimalLauncherWidget>>(emptyList())
    val widgetsFlow: StateFlow<List<PlacedMinimalLauncherWidget>> = _widgets.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoadedFlow: StateFlow<Boolean> = _isLoaded.asStateFlow()

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ITEMS, null) ?: "[]"
        _widgets.value = sortItems(parse(raw))
        _isLoaded.value = true
    }

    fun hasPersistedLayout(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .contains(KEY_ITEMS)

    fun snapshotRaw(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]") ?: "[]"

    fun itemsFromRaw(raw: String): List<PlacedMinimalLauncherWidget> = parse(raw)

    fun replaceAll(context: Context, items: List<PlacedMinimalLauncherWidget>) {
        persist(context, sortItems(items))
    }

    fun add(context: Context, item: PlacedMinimalLauncherWidget) {
        val next = _widgets.value.filterNot { it.appWidgetId == item.appWidgetId } + item
        persist(context, sortItems(next))
    }

    fun placeNew(
        context: Context,
        isLandscape: Boolean,
        appWidgetId: Int,
        provider: ComponentName,
        w: Float,
        h: Float,
    ): PlacedMinimalLauncherWidget {
        val lastScreen = maxOf(
            _widgets.value.maxOfOrNull { maxOf(it.screen, it.landScreen) } ?: -1,
            MinimalLauncherIconsStore.iconsFlow.value
                .maxOfOrNull { maxOf(it.screen, it.landScreen) } ?: -1,
        )
        val screen = lastScreen.coerceAtLeast(0)
        val n = _widgets.value.size
        val x = (0.05f + (n % 3) * 0.08f).coerceIn(0f, 0.7f)
        val y = (0.05f + (n % 3) * 0.08f).coerceIn(0f, 0.7f)
        return placeAt(context, isLandscape, appWidgetId, provider, screen, x, y, w, h)
            ?: newDualPlacement(appWidgetId, provider, isLandscape, screen, x, y, w, h).also {
                add(context, it)
            }
    }

    fun placeAt(
        context: Context,
        isLandscape: Boolean,
        appWidgetId: Int,
        provider: ComponentName,
        screen: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ): PlacedMinimalLauncherWidget? {
        val without = _widgets.value.filterNot { it.appWidgetId == appWidgetId }
        val item = newDualPlacement(
            appWidgetId, provider, isLandscape, screen, x, y, w, h,
            existing = _widgets.value.find { it.appWidgetId == appWidgetId },
        )
        persist(context, sortItems(without + item))
        return item
    }

    fun move(
        context: Context,
        isLandscape: Boolean,
        appWidgetId: Int,
        screen: Int,
        x: Float,
        y: Float,
    ): Boolean {
        val current = _widgets.value.find { it.appWidgetId == appWidgetId } ?: return false
        val active = current.placement(isLandscape)
        val others = _widgets.value.filterNot { it.appWidgetId == appWidgetId }
        val next = current.withPlacement(
            isLandscape, screen, x, y, active.w, active.h,
        )
        persist(context, sortItems(others + next))
        return true
    }

    fun remove(context: Context, appWidgetId: Int) {
        val next = _widgets.value.filterNot { it.appWidgetId == appWidgetId }
        persist(context, sortItems(next))
        MinimalLauncherWidgetHost.deleteAppWidgetId(context, appWidgetId)
        stripEmptyScreens(context)
    }

    /**
     * Free continuous resize for the active orientation only.
     * [persistDisk]=false updates the in-memory flow only (live finger resize);
     * pass true on gesture end so SharedPreferences is written once.
     */
    fun updateBounds(
        context: Context,
        isLandscape: Boolean,
        appWidgetId: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        persistDisk: Boolean = true,
    ): Boolean {
        val current = _widgets.value.find { it.appWidgetId == appWidgetId } ?: return false
        val activeScreen = current.placement(isLandscape).screen
        val next = _widgets.value.map { item ->
            if (item.appWidgetId != appWidgetId) item
            else item.withPlacement(isLandscape, activeScreen, x, y, w, h)
        }
        if (persistDisk) {
            persist(context, next)
        } else {
            _widgets.value = next
        }
        return true
    }

    private fun newDualPlacement(
        appWidgetId: Int,
        provider: ComponentName,
        isLandscape: Boolean,
        screen: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        existing: PlacedMinimalLauncherWidget? = null,
    ): PlacedMinimalLauncherWidget {
        val rect = clampCanvasRect(x, y, w, h)
        val sc = screen.coerceAtLeast(0)
        return if (isLandscape) {
            PlacedMinimalLauncherWidget(
                appWidgetId = appWidgetId,
                provider = provider,
                screen = existing?.screen ?: sc,
                x = existing?.x ?: rect.x,
                y = existing?.y ?: rect.y,
                w = existing?.w ?: rect.w.coerceAtMost(MINIMAL_LAUNCHER_WIDGET_MAX_LAND_W_FRAC),
                h = existing?.h ?: rect.h,
                landScreen = sc,
                landX = rect.x,
                landY = rect.y,
                landW = rect.w,
                landH = rect.h,
            )
        } else {
            PlacedMinimalLauncherWidget(
                appWidgetId = appWidgetId,
                provider = provider,
                screen = sc,
                x = rect.x,
                y = rect.y,
                w = rect.w,
                h = rect.h,
                landScreen = existing?.landScreen ?: sc,
                landX = existing?.landX ?: rect.x,
                landY = existing?.landY ?: rect.y,
                landW = existing?.landW ?: rect.w.coerceAtMost(MINIMAL_LAUNCHER_WIDGET_MAX_LAND_W_FRAC),
                landH = existing?.landH ?: rect.h,
            )
        }
    }

    private fun sortItems(
        items: List<PlacedMinimalLauncherWidget>,
    ): List<PlacedMinimalLauncherWidget> =
        items.sortedWith(compareBy({ it.screen }, { it.y }, { it.x }))

    private fun persist(context: Context, items: List<PlacedMinimalLauncherWidget>) {
        _widgets.value = items
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.appWidgetId)
                    .put("provider", item.provider.flattenToString())
                    .put("screen", item.screen)
                    .put("x", item.x.toDouble())
                    .put("y", item.y.toDouble())
                    .put("w", item.w.toDouble())
                    .put("h", item.h.toDouble())
                    .put("landScreen", item.landScreen)
                    .put("landX", item.landX.toDouble())
                    .put("landY", item.landY.toDouble())
                    .put("landW", item.landW.toDouble())
                    .put("landH", item.landH.toDouble())
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
    }

    private fun parse(raw: String): List<PlacedMinimalLauncherWidget> {
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val provider = ComponentName.unflattenFromString(o.getString("provider"))
                        ?: continue
                    val screen = o.optInt("screen", 0).coerceAtLeast(0)
                    val parsed = parsePlacement(o, screen)
                    add(
                        PlacedMinimalLauncherWidget(
                            appWidgetId = o.getInt("id"),
                            provider = provider,
                            screen = screen,
                            x = parsed.x,
                            y = parsed.y,
                            w = parsed.w,
                            h = parsed.h,
                            landScreen = parsed.landScreen,
                            landX = parsed.landX,
                            landY = parsed.landY,
                            landW = parsed.landW,
                            landH = parsed.landH,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePlacement(o: JSONObject, screen: Int): WidgetPlacementParsed {
        if (o.has("x") && o.has("w")) {
            val x = o.optDouble("x", 0.0).toFloat()
            val y = o.optDouble("y", 0.0).toFloat()
            val w = o.optDouble("w", 0.35).toFloat()
            val h = o.optDouble("h", 0.25).toFloat()
            val rect = clampCanvasRect(x, y, w, h)
            val landScreen = o.optInt("landScreen", screen).coerceAtLeast(0)
            val landRect = clampCanvasRect(
                o.optDouble("landX", rect.x.toDouble()).toFloat(),
                o.optDouble("landY", rect.y.toDouble()).toFloat(),
                o.optDouble("landW", rect.w.toDouble()).toFloat(),
                o.optDouble("landH", rect.h.toDouble()).toFloat(),
            )
            return WidgetPlacementParsed(
                rect.x, rect.y, rect.w, rect.h,
                landScreen, landRect.x, landRect.y, landRect.w, landRect.h,
            )
        }
        // Legacy grid cells → free canvas norms (migration only).
        val cols = 5
        val rows = 5
        val cellX = o.optInt("cellX", 0)
        val cellY = o.optInt("cellY", 0)
        val spanX = o.optInt("spanX", 1)
        val spanY = o.optInt("spanY", 1)
        val (x, w) = migrateCellToNorm(cellX, spanX, cols)
        val (y, h) = migrateCellToNorm(cellY, spanY, rows)
        val landScreen = o.optInt("landScreen", screen).coerceAtLeast(0)
        val (landX, landW) = migrateCellToNorm(
            o.optInt("landCellX", cellX), o.optInt("landSpanX", spanX), cols,
        )
        val (landY, landH) = migrateCellToNorm(
            o.optInt("landCellY", cellY), o.optInt("landSpanY", spanY), rows,
        )
        return WidgetPlacementParsed(x, y, w, h, landScreen, landX, landY, landW, landH)
    }

    private data class WidgetPlacementParsed(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val landScreen: Int,
        val landX: Float,
        val landY: Float,
        val landW: Float,
        val landH: Float,
    )
}
