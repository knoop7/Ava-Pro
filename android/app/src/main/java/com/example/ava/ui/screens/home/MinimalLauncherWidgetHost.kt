package com.example.ava.ui.screens.home

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import com.example.ava.R
import com.example.ava.widgets.AvaDarkModeWidgetProvider
import com.example.ava.widgets.AvaExitWidgetProvider
import com.example.ava.widgets.AvaMusicWidgetProvider
import com.example.ava.widgets.AvaNetworkWidgetProvider
import com.example.ava.widgets.AvaRestartWidgetProvider
import com.example.ava.widgets.AvaSensorWidgetProvider
import com.example.ava.widgets.AvaServiceWidgetProvider
import com.example.ava.widgets.AvaTitleDescriptionWidgetProvider

/**
 * Process-wide [AppWidgetHost] for desktop-icon mode, mirroring Launcher3's single host.
 * Host id is stable so widget ids survive process death when persisted.
 */
object MinimalLauncherWidgetHost {
    private const val HOST_ID = 0x0A7A101D

    @Volatile
    private var host: MinimalLauncherAppWidgetHost? = null

    @Volatile
    private var listening = false

    fun get(context: Context): AppWidgetHost {
        val app = context.applicationContext
        return host ?: synchronized(this) {
            host ?: MinimalLauncherAppWidgetHost(app, HOST_ID).also { host = it }
        }
    }

    /**
     * AppWidgetHost callback registration is lifecycle-sensitive. Re-registering on
     * foreground restores the latest RemoteViews for every allocated id after process,
     * activity, or provider reconnects.
     */
    fun startListening(context: Context) {
        val app = context.applicationContext
        val current = host ?: synchronized(this) {
            host ?: MinimalLauncherAppWidgetHost(app, HOST_ID).also { host = it }
        }
        synchronized(this) {
            if (listening) return
            try {
                current.startListening()
                listening = true
            } catch (_: Exception) {
                listening = false
            }
        }
    }

    fun stopListening() {
        synchronized(this) {
            if (!listening) return
            try {
                host?.stopListening()
            } catch (_: Exception) {
            } finally {
                listening = false
            }
        }
    }

    fun createView(
        context: Context,
        appWidgetId: Int,
        info: AppWidgetProviderInfo?,
    ): AppWidgetHostView {
        // Inflate RemoteViews with the live UI configuration/context. This is important
        // for collection widgets and provider resources that refresh after configuration changes.
        return get(context).createView(context, appWidgetId, info)
    }

    fun deleteAppWidgetId(context: Context, appWidgetId: Int) {
        try {
            get(context).deleteAppWidgetId(appWidgetId)
        } catch (_: Exception) {
        }
    }
}

data class MinimalLauncherWidgetProvider(
    val provider: ComponentName,
    val label: String,
    /** Intrinsic provider size in dp (follows the widget itself — not a grid). */
    val minWidthDp: Int,
    val minHeightDp: Int,
    val info: AppWidgetProviderInfo,
)

/**
 * Launcher3 WidgetsModel grouping: one row per package, widgets of that app listed together.
 */
data class MinimalLauncherWidgetPackageGroup(
    val packageName: String,
    val appLabel: String,
    val appIcon: Drawable?,
    val widgets: List<MinimalLauncherWidgetProvider>,
)

fun loadMinimalLauncherWidgetProviders(context: Context): List<MinimalLauncherWidgetProvider> {
    return loadMinimalLauncherWidgetGroups(context).flatMap { it.widgets }
}

/** Same package → one section; widgets within a package stay side-by-side (Launcher3). */
fun loadMinimalLauncherWidgetGroups(context: Context): List<MinimalLauncherWidgetPackageGroup> {
    val appContext = context.applicationContext
    val pm = appContext.packageManager
    val manager = AppWidgetManager.getInstance(appContext)
    val byPackage = LinkedHashMap<String, MutableList<MinimalLauncherWidgetProvider>>()
    manager.installedProviders.forEach { info ->
        val provider = info.provider ?: return@forEach
        val label = try {
            info.loadLabel(pm)?.toString()
        } catch (_: Exception) {
            null
        } ?: provider.flattenToShortString()
        val pkg = provider.packageName
        byPackage.getOrPut(pkg) { mutableListOf() }.add(
            MinimalLauncherWidgetProvider(
                provider = provider,
                label = label,
                minWidthDp = info.minWidth.coerceAtLeast(1),
                minHeightDp = info.minHeight.coerceAtLeast(1),
                info = info,
            )
        )
    }
    return byPackage.map { (pkg, widgets) ->
        val appInfo = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (_: Exception) {
            null
        }
        val isAvaWidgetGroup = pkg == appContext.packageName
        val appLabel = if (isAvaWidgetGroup) {
            appContext.getString(R.string.ava_widgets_group_name)
        } else {
            appInfo?.let {
                try {
                    pm.getApplicationLabel(it).toString()
                } catch (_: Exception) {
                    null
                }
            } ?: pkg
        }
        val appIcon = appInfo?.let {
            try {
                pm.getApplicationIcon(it)
            } catch (_: Exception) {
                null
            }
        }
        MinimalLauncherWidgetPackageGroup(
            packageName = pkg,
            appLabel = appLabel,
            appIcon = appIcon,
            widgets = if (isAvaWidgetGroup) {
                widgets.sortedWith(
                    compareBy<MinimalLauncherWidgetProvider> {
                        avaWidgetPickerOrder(it.provider.className)
                    }.thenBy { it.label.lowercase() },
                )
            } else {
                widgets.sortedBy { it.label.lowercase() }
            },
        )
    }.sortedWith(
        compareBy<MinimalLauncherWidgetPackageGroup> {
            if (it.packageName == appContext.packageName) 0 else 1
        }.thenBy { it.appLabel.lowercase() }
    )
}

/** Ava Pro (title card) leads the exclusive row; the rest stay in a stable order. */
private fun avaWidgetPickerOrder(className: String): Int = when (className) {
    AvaTitleDescriptionWidgetProvider::class.java.name -> 0
    AvaServiceWidgetProvider::class.java.name -> 1
    AvaSensorWidgetProvider::class.java.name -> 2
    AvaDarkModeWidgetProvider::class.java.name -> 3
    AvaMusicWidgetProvider::class.java.name -> 4
    AvaNetworkWidgetProvider::class.java.name -> 5
    AvaRestartWidgetProvider::class.java.name -> 6
    AvaExitWidgetProvider::class.java.name -> 7
    else -> 8
}

