package com.example.ava.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import com.example.ava.R

/**
 * First Ava-owned desktop widget: a responsive title and description card.
 *
 * Every render is derived only from the options for the supplied [appWidgetId].
 * There is deliberately no provider-wide size state, so two instances can keep
 * completely independent dimensions and compact/expanded presentation.
 */
class AvaTitleDescriptionWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId ->
            render(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        render(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { AvaWidgetTextStore.delete(context, it) }
        super.onDeleted(context, appWidgetIds)
    }

    companion object {
        fun render(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            options: Bundle = appWidgetManager.getAppWidgetOptions(appWidgetId),
        ) {
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220)
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 96)
            val paddingDp = when {
                widthDp < 90 || heightDp < 48 -> 2
                widthDp < 150 || heightDp < 72 -> 6
                else -> 10
            }
            val density = context.resources.displayMetrics.density
            val paddingPx = (paddingDp * density).toInt()
            val text = AvaWidgetTextStore.read(context, appWidgetId)
            val availableWidth = (widthDp - paddingDp * 2).coerceAtLeast(24).toFloat()
            val availableHeight = (heightDp - paddingDp * 2).coerceAtLeast(24).toFloat()
            val titleLength = text.title.codePointCount(0, text.title.length).coerceAtLeast(1)
            val descriptionLength =
                text.description.codePointCount(0, text.description.length).coerceAtLeast(1)

            // Grow with the widget, then cap against the actual user text so long
            // custom labels still fit. The slider multiplies the responsive baseline.
            val titleBaseline = minOf(
                availableWidth / 7f,
                availableHeight * 0.22f,
            ).coerceIn(11f, 52f)
            val titleWidthFit = availableWidth / (titleLength * 0.56f)
            val titleSp = minOf(
                titleBaseline * text.textScale,
                titleWidthFit,
                availableHeight * 0.35f,
            ).coerceIn(7f, 64f)

            val descriptionBaseline = minOf(
                availableWidth / 11f,
                availableHeight * 0.12f,
            ).coerceIn(8f, 30f)
            // Description can use up to three lines.
            val descriptionCharsPerLine = (descriptionLength + 2) / 3
            val descriptionWidthFit = availableWidth / (descriptionCharsPerLine * 0.52f)
            val descriptionSp = minOf(
                descriptionBaseline * text.textScale,
                descriptionWidthFit,
                availableHeight * 0.20f,
            ).coerceIn(7f, 40f)

            val views = RemoteViews(context.packageName, R.layout.widget_ava_title_description)
            views.setTextViewText(R.id.ava_widget_title, text.title)
            views.setTextViewText(R.id.ava_widget_description, text.description)
            views.setTextViewTextSize(
                R.id.ava_widget_title,
                TypedValue.COMPLEX_UNIT_SP,
                titleSp,
            )
            views.setTextViewTextSize(
                R.id.ava_widget_description,
                TypedValue.COMPLEX_UNIT_SP,
                descriptionSp,
            )
            views.setViewPadding(
                R.id.ava_widget_content,
                paddingPx,
                paddingPx,
                paddingPx,
                paddingPx,
            )

            val editIntent = Intent(context, AvaTitleDescriptionWidgetConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val editPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                editIntent,
                flags,
            )
            views.setOnClickPendingIntent(R.id.ava_widget_root, editPendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
