package com.example.ava.touchpad

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import com.example.ava.MainActivity
import com.example.ava.services.AiBrowserService
import com.example.ava.services.AppWindowService
import com.example.ava.services.ScreensaverController
import com.example.ava.services.WebViewService
import com.example.ava.ui.MainNavigationCoordinator
import com.example.ava.ui.components.SidebarDrawerRemote
import java.lang.ref.WeakReference

/** Ava-owned page / window / key config captured with a take — not a script. */
internal data class TouchPadAutoScene(
    val route: String = "",
    val packageName: String = "",
    val windows: List<String> = emptyList(),
) {
    fun isBlank(): Boolean = route.isBlank() && packageName.isBlank() && windows.isEmpty()
}

internal object AvaAutoScene {
    fun capture(service: Context, x: Float? = null, y: Float? = null): TouchPadAutoScene {
        val acc = service as? android.accessibilityservice.AccessibilityService
        val ava = service.packageName
        if (x == null || y == null) {
            val pkg = runCatching { acc?.rootInActiveWindow?.packageName?.toString() }
                .getOrNull()
                .orEmpty()
                .ifBlank { ava }
            return TouchPadAutoScene(
                route = if (pkg == ava) MainNavigationCoordinator.currentRoute().orEmpty() else "",
                packageName = pkg,
            )
        }
        val windowPkg = AppWindowService.packageAt(x, y)
        if (windowPkg != null) {
            return TouchPadAutoScene(
                packageName = windowPkg,
                windows = listOf(windowPkg),
            )
        }
        val pkg = if (acc != null) TouchPadScroller.packageUnderPoint(acc, x, y) else ""
        return TouchPadAutoScene(
            route = if (pkg == ava) MainNavigationCoordinator.currentRoute().orEmpty() else "",
            packageName = pkg,
        )
    }

    /** Rewind to the first frame. Later settings pages and apps come from recorded location steps. */
    fun restore(service: Context, scene: TouchPadAutoScene): Boolean {
        var changed = SidebarDrawerRemote.requestClose()
        val route = scene.route
        val settingsLike = MainNavigationCoordinator.isSettingsLikeRoute(route)
        if (settingsLike && WebViewService.isBrowserOverlayVisible()) {
            uncoverAvaPage(service, route)
            changed = true
        }
        if (route.isNotBlank() &&
            route != MainNavigationCoordinator.currentRoute() &&
            MainNavigationCoordinator.isRestorableRoute(route)
        ) {
            uncoverAvaPage(service, route)
            MainNavigationCoordinator.requestNavigation(route)
            runCatching {
                service.startActivity(
                    android.content.Intent(service, MainActivity::class.java).apply {
                        addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        )
                        putExtra("navigate_to", route)
                    },
                )
            }
            changed = true
        }
        val open = AppWindowService.openPackages()
        for (pkg in scene.windows) {
            if (pkg.isBlank() || pkg in open) continue
            AppWindowService.start(service, pkg)
            changed = true
        }
        val pkg = scene.packageName
        if (pkg.isNotBlank() && pkg != service.packageName && pkg !in scene.windows) {
            bringPackage(service, pkg)
            changed = true
        }
        return changed
    }

    fun applyWindow(service: Context, pkg: String, open: Boolean) {
        if (pkg.isBlank()) return
        if (!open) {
            AppWindowService.closeWindow(pkg)
            return
        }
        bringPackage(service, pkg)
    }

    private fun bringPackage(service: Context, pkg: String) {
        if (pkg == service.packageName) {
            runCatching {
                service.startActivity(
                    android.content.Intent(service, MainActivity::class.java).apply {
                        addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        )
                    },
                )
            }
            return
        }
        if (pkg in AppWindowService.openPackages()) {
            AppWindowService.start(service, pkg)
            return
        }
        val launch = service.packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        runCatching { service.startActivity(launch) }
    }

    /** Settings-like Compose must not sit under the HA/browser overlay. */
    private fun uncoverAvaPage(service: Context, route: String) {
        ScreensaverController.onUserInteraction()
        if (!MainNavigationCoordinator.isSettingsLikeRoute(route)) return
        WebViewService.hide(service)
        AiBrowserService.hide(service)
    }
}

internal object AvaAutoKeys {
    fun interface Sink {
        fun onAvaKey(keyCode: Int, action: Int)
    }

    fun interface TextSink {
        fun onAvaText(text: String)
    }

    @Volatile
    var sink: Sink? = null

    @Volatile
    var textSink: TextSink? = null

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    fun bindActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun unbindActivity(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    fun note(event: KeyEvent) {
        if (event.repeatCount > 0) return
        if (event.action != KeyEvent.ACTION_DOWN) return
        if (!isMacroKey(event.keyCode)) return
        sink?.onAvaKey(event.keyCode, event.action)
    }

    fun noteAccessibility(event: android.view.accessibility.AccessibilityEvent) {
        val listener = textSink ?: return
        if (event.eventType != android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            return
        }
        if (event.isPassword) return
        val source = event.source ?: return
        try {
            if (!source.isEditable || source.isPassword) return
            val text = source.text?.toString()
                ?: event.text?.joinToString("").orEmpty()
            listener.onAvaText(text)
        } finally {
            source.recycle()
        }
    }

    fun inject(keyCode: Int, action: Int = KeyEvent.ACTION_DOWN): Boolean {
        if (action != KeyEvent.ACTION_DOWN) return true
        when (keyCode) {
            KeyEvent.KEYCODE_BACK ->
                if (com.example.ava.services.AccessibilityBridge.back()) return true
            KeyEvent.KEYCODE_HOME ->
                if (com.example.ava.services.AccessibilityBridge.home()) return true
            KeyEvent.KEYCODE_APP_SWITCH ->
                if (com.example.ava.services.AccessibilityBridge.recents()) return true
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER ->
                if (com.example.ava.services.AccessibilityBridge.imeEnter()) return true
        }
        val activity = activityRef?.get() ?: return false
        activity.runOnUiThread {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        return true
    }

    fun isMacroKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MOVE_HOME,
        KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_FORWARD,
        KeyEvent.KEYCODE_MENU -> true
        else -> false
    }
}
