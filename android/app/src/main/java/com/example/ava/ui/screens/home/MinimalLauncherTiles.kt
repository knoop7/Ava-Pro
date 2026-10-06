package com.example.ava.ui.screens.home

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import com.example.ava.R

/**
 * Desktop seats that are not launcher activities.
 *
 * [PlacedMinimalLauncherIcon] only carries a package + activity, so a tile borrows those
 * two fields with a sentinel package. `#` is illegal in a package name, so [PACKAGE] can
 * never collide with something installed — which is what keeps the tile clear of every
 * "this app is gone, drop the icon" path (uninstall callbacks, the unresolved-activity
 * prune, the launch-failure cleanup).
 *
 * A tile is otherwise an ordinary placed icon: it drags, moves between pages, and can be
 * dropped on Remove like anything else.
 */
object MinimalLauncherTiles {

    const val PACKAGE = "#ava.tile"

    /** Opens the All Apps drawer. */
    const val ALL_APPS = "all_apps"

    /** Opens Ava's own settings. Seeded in place of a browser, which Ava does not need. */
    const val SETTINGS = "settings"

    /**
     * The core-service card, placed on the first-run desktop.
     *
     * It lives in [MinimalLauncherWidgetsStore] because it is card-shaped and resizable
     * like a widget, but it is not one: a real widget has to be bound through
     * `bindAppWidgetIdIfAllowed`, which a brand-new install has no grant for and which
     * would mean a system consent dialog on the very first screen. The launcher draws the
     * widget's own RemoteViews instead, so the card is identical to the one the widget
     * tray offers while needing no binding, no host id and no dialog.
     */
    val SERVICE_CARD: ComponentName = ComponentName(PACKAGE, "service_card")

    /**
     * Stands in for the appWidgetId of a card. Allocated ids are positive, so a negative
     * one can never collide, and every `AppWidgetHost` call keyed by it is a no-op.
     */
    const val SERVICE_CARD_ID = -1

    fun isTile(packageName: String): Boolean = packageName == PACKAGE

    fun isAllApps(packageName: String, activityName: String): Boolean =
        packageName == PACKAGE && activityName == ALL_APPS

    fun isSettings(packageName: String, activityName: String): Boolean =
        packageName == PACKAGE && activityName == SETTINGS

    /** A placed "widget" the launcher draws itself rather than hosting. */
    fun isCard(provider: ComponentName): Boolean = provider.packageName == PACKAGE

    fun serviceCardAt(
        screen: Int,
        rect: CanvasRect,
        landScreen: Int,
        landRect: CanvasRect,
    ): PlacedMinimalLauncherWidget = PlacedMinimalLauncherWidget(
        appWidgetId = SERVICE_CARD_ID,
        provider = SERVICE_CARD,
        screen = screen,
        x = rect.x,
        y = rect.y,
        w = rect.w,
        h = rect.h,
        landScreen = landScreen,
        landX = landRect.x,
        landY = landRect.y,
        landW = landRect.w,
        landH = landRect.h,
    )

    fun allAppsAt(
        screen: Int,
        x: Float,
        y: Float,
        landScreen: Int,
        landX: Float,
        landY: Float,
    ): PlacedMinimalLauncherIcon =
        tileAt(ALL_APPS, screen, x, y, landScreen, landX, landY)

    fun settingsAt(
        screen: Int,
        x: Float,
        y: Float,
        landScreen: Int,
        landX: Float,
        landY: Float,
    ): PlacedMinimalLauncherIcon =
        tileAt(SETTINGS, screen, x, y, landScreen, landX, landY)

    private fun tileAt(
        activityName: String,
        screen: Int,
        x: Float,
        y: Float,
        landScreen: Int,
        landX: Float,
        landY: Float,
    ): PlacedMinimalLauncherIcon = PlacedMinimalLauncherIcon(
        id = PlacedMinimalLauncherIcon.idFor(PACKAGE, activityName),
        packageName = PACKAGE,
        activityName = activityName,
        screen = screen,
        x = x,
        y = y,
        landScreen = landScreen,
        landX = landX,
        landY = landY,
    )

    /**
     * Stand-in for [resolveMinimalLauncherApp], which can only answer for real activities.
     * Returns null for anything that is not a tile, so callers can chain the two.
     */
    fun resolve(
        context: Context,
        packageName: String,
        activityName: String,
    ): MinimalLauncherApp? {
        val (iconRes, labelRes) = when {
            isAllApps(packageName, activityName) ->
                R.drawable.ic_minimal_launcher_all_apps to
                    R.string.minimal_launcher_tile_all_apps
            isSettings(packageName, activityName) ->
                R.drawable.ic_minimal_launcher_settings to
                    R.string.minimal_launcher_tile_settings
            else -> return null
        }
        val icon = ContextCompat.getDrawable(context, iconRes) ?: return null
        return MinimalLauncherApp(
            label = context.getString(labelRes),
            icon = icon,
            packageName = packageName,
            activityName = activityName,
        )
    }
}
