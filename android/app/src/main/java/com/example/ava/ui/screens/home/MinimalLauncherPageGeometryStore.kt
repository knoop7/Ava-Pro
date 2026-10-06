package com.example.ava.ui.screens.home

import android.content.Context

/**
 * Last workspace page size the launcher actually measured.
 *
 * Only the live workspace knows how big a page is — it comes out of
 * `onGloballyPositioned`, minus insets, page indicator and whatever chrome is around it.
 * Settings has no way to compute that, so the launcher records it here on every geometry
 * report and the one-tap fill reads it back.
 *
 * Just one size is kept. Like first-run seeding, the inactive orientation is treated as
 * the same window with its edges swapped, which is accurate enough for a seat the user
 * can drag afterwards.
 */
object MinimalLauncherPageGeometryStore {

    private const val PREFS = "minimal_launcher_page_geometry"
    private const val KEY_WIDTH = "width_px"
    private const val KEY_HEIGHT = "height_px"

    fun remember(context: Context, widthPx: Float, heightPx: Float) {
        if (widthPx <= 1f || heightPx <= 1f) return
        val prefs = prefs(context)
        if (prefs.getFloat(KEY_WIDTH, 0f) == widthPx &&
            prefs.getFloat(KEY_HEIGHT, 0f) == heightPx
        ) {
            return
        }
        prefs.edit()
            .putFloat(KEY_WIDTH, widthPx)
            .putFloat(KEY_HEIGHT, heightPx)
            .apply()
    }

    /** Both canvases, named so the two orientations cannot be mixed up at a call site. */
    data class Pages(
        val portrait: MinimalLauncherDefaultLayout.PageSize,
        val landscape: MinimalLauncherDefaultLayout.PageSize,
    )

    /**
     * Portrait and landscape page sizes, or null if the workspace has never been laid
     * out on this install.
     */
    fun read(context: Context): Pages? {
        val prefs = prefs(context)
        val width = prefs.getFloat(KEY_WIDTH, 0f)
        val height = prefs.getFloat(KEY_HEIGHT, 0f)
        if (width <= 1f || height <= 1f) return null
        val shortEdge = minOf(width, height)
        val longEdge = maxOf(width, height)
        return Pages(
            portrait = MinimalLauncherDefaultLayout.PageSize(shortEdge, longEdge),
            landscape = MinimalLauncherDefaultLayout.PageSize(longEdge, shortEdge),
        )
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
