package com.example.ava.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * One receiver per action so each tile is its own entry in the widget picker.
 * All of the behaviour lives in [AvaActionWidgets].
 */
abstract class AvaActionWidgetProvider : AppWidgetProvider() {

    protected abstract val action: AvaWidgetAction

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId ->
            AvaActionWidgets.render(context, appWidgetManager, appWidgetId, action)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        AvaActionWidgets.render(context, appWidgetManager, appWidgetId, action, newOptions)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { AvaActionWidgets.forget(context, it) }
        super.onDeleted(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val known = appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        when {
            intent.action == AvaActionWidgets.ACTION_TAP && known ->
                AvaActionWidgets.onTap(context, appWidgetId, action)

            intent.action == AvaActionWidgets.ACTION_DISARM && known ->
                AvaActionWidgets.onDisarm(context, appWidgetId, action)

            else -> super.onReceive(context, intent)
        }
    }
}

class AvaRestartWidgetProvider : AvaActionWidgetProvider() {
    override val action = AvaWidgetAction.RESTART
}

class AvaExitWidgetProvider : AvaActionWidgetProvider() {
    override val action = AvaWidgetAction.EXIT
}

class AvaServiceWidgetProvider : AvaActionWidgetProvider() {
    override val action = AvaWidgetAction.SERVICE
}

class AvaDarkModeWidgetProvider : AvaActionWidgetProvider() {
    override val action = AvaWidgetAction.DARK_MODE
}

class AvaMusicWidgetProvider : AvaActionWidgetProvider() {
    override val action = AvaWidgetAction.MUSIC
}

class AvaNetworkWidgetProvider : AvaActionWidgetProvider() {
    override val action = AvaWidgetAction.NETWORK
}
