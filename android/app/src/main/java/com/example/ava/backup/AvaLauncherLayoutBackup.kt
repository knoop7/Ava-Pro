package com.example.ava.backup

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import com.example.ava.ui.screens.home.MinimalLauncherIconsStore
import com.example.ava.ui.screens.home.MinimalLauncherTiles
import com.example.ava.ui.screens.home.MinimalLauncherWidgetHost
import com.example.ava.ui.screens.home.MinimalLauncherWidgetsStore
import com.example.ava.ui.screens.home.PlacedMinimalLauncherWidget
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * Desktop icon / widget placements live in SharedPreferences, not DataStore.
 * Icon layout copies as-is. Widget ids are device-local, so import reallocates
 * and rebinds when [AppWidgetManager.bindAppWidgetIdIfAllowed] allows it;
 * Ava-drawn cards keep their sentinel ids.
 */
internal object AvaLauncherLayoutBackup {
    private const val TAG = "AvaLauncherLayoutBackup"

    fun exportDesktop(context: Context): JsonElement =
        JsonParser.parseString(MinimalLauncherIconsStore.snapshotRaw(context))

    fun exportWidgets(context: Context): JsonElement =
        JsonParser.parseString(MinimalLauncherWidgetsStore.snapshotRaw(context))

    fun importDesktop(context: Context, element: JsonElement) {
        MinimalLauncherIconsStore.restoreFromRaw(context, element.toString())
    }

    fun importWidgets(context: Context, element: JsonElement) {
        val raw = if (element.isJsonArray) element.asJsonArray.toString() else "[]"
        val incoming = MinimalLauncherWidgetsStore.itemsFromRaw(raw)
        MinimalLauncherWidgetsStore.load(context)
        MinimalLauncherWidgetsStore.widgetsFlow.value.forEach { existing ->
            if (!MinimalLauncherTiles.isCard(existing.provider) && existing.appWidgetId > 0) {
                MinimalLauncherWidgetHost.deleteAppWidgetId(context, existing.appWidgetId)
            }
        }
        val restored = incoming.mapNotNull { item -> rebindOrKeep(context, item) }
        MinimalLauncherWidgetsStore.replaceAll(context, restored)
    }

    private fun rebindOrKeep(
        context: Context,
        item: PlacedMinimalLauncherWidget,
    ): PlacedMinimalLauncherWidget? {
        if (MinimalLauncherTiles.isCard(item.provider) || item.appWidgetId < 0) {
            return item
        }
        val host = MinimalLauncherWidgetHost.get(context)
        val manager = AppWidgetManager.getInstance(context)
        val newId = try {
            host.allocateAppWidgetId()
        } catch (e: Exception) {
            Log.w(TAG, "allocate widget id failed: ${e.message}")
            return null
        }
        val allowed = try {
            manager.bindAppWidgetIdIfAllowed(newId, item.provider)
        } catch (e: Exception) {
            Log.w(TAG, "bind widget failed: ${e.message}")
            false
        }
        if (!allowed) {
            MinimalLauncherWidgetHost.deleteAppWidgetId(context, newId)
            Log.w(TAG, "skip widget ${item.provider.flattenToShortString()}: bind not allowed")
            return null
        }
        return item.copy(appWidgetId = newId)
    }
}
