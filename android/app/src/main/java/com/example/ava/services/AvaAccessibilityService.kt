package com.example.ava.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.content.res.Configuration
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.ava.settings.playerSettingsStore
import com.example.ava.touchpad.AiPhonePointer
import com.example.ava.touchpad.TouchPadOverlay

private const val TAG = "AvaAccessibility"

class AvaAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        AccessibilityBridge.attach(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TouchPadOverlay.bind(this)
        }
        AccessibilityBridge.tryStartVoiceSatelliteFromBootFallback(this, serviceScope)
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        TouchPadOverlay.onConfigurationChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        AccessibilityBridge.onAccessibilityEvent(event)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        com.example.ava.touchpad.AvaAutoKeys.note(event)
        return false
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        TouchPadOverlay.unbind(this)
        AiPhonePointer.dismiss()
        AccessibilityBridge.detach(this)
        serviceScope.cancel()
        super.onDestroy()
    }
}

object AccessibilityBridge {
    private const val PREFS_NAME = "ava_prefs"
    private const val KEY_PENDING_ACCESSIBILITY_AUTOSTART = "pending_accessibility_autostart"
    private const val KEY_PENDING_ACCESSIBILITY_AUTOSTART_AT = "pending_accessibility_autostart_at"
    private const val KEY_SERVICE_USER_STOPPED = "service_user_stopped"
    private const val PENDING_AUTOSTART_WINDOW_MS = 2 * 60 * 1000L
    /** API 30 `AccessibilityNodeInfo.ACTION_IME_ENTER`. */
    private const val ACTION_IME_ENTER = 0x00000080

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var service: AvaAccessibilityService? = null

    @Volatile
    private var lastTreeJson: String = """{"status":"unavailable"}"""

    @Volatile
    private var lastSnapshotCenters: Map<Int, Pair<Int, Int>> = emptyMap()

    @Volatile
    private var lastFallbackScreenshotAtMs: Long = 0L

    @Volatile
    private var lastDirectScreenshotAtMs: Long = 0L

    @Volatile
    private var notificationsOpen = false

    private const val FALLBACK_SCREENSHOT_INTERVAL_MS = 5_000L
    private const val DIRECT_SCREENSHOT_INTERVAL_MS = 1_500L

    internal fun attach(service: AvaAccessibilityService) {
        this.service = service
    }

    fun isServiceConnected(): Boolean = service != null

    fun getConnectedService(): AvaAccessibilityService? = service

    fun centerOf(index: Int): Pair<Int, Int>? = lastSnapshotCenters[index]


    internal fun detach(service: AvaAccessibilityService) {
        if (this.service === service) {
            this.service = null
            lastTreeJson = """{"status":"detached"}"""
            lastSnapshotCenters = emptyMap()
            notificationsOpen = false
        }
    }

    internal fun tryStartVoiceSatelliteFromBootFallback(
        service: AvaAccessibilityService,
        scope: CoroutineScope
    ) {
        scope.launch {
            delay(1200)

            val prefs = service.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pending = prefs.getBoolean(KEY_PENDING_ACCESSIBILITY_AUTOSTART, false)
            val pendingAt = prefs.getLong(KEY_PENDING_ACCESSIBILITY_AUTOSTART_AT, 0L)
            val withinBootWindow = pendingAt > 0L &&
                System.currentTimeMillis() - pendingAt <= PENDING_AUTOSTART_WINDOW_MS

            if (!pending || !withinBootWindow) {
                if (pending && !withinBootWindow) {
                    clearPendingAccessibilityAutoStart(service)
                }
                return@launch
            }

            if (prefs.getBoolean(KEY_SERVICE_USER_STOPPED, false)) {
                clearPendingAccessibilityAutoStart(service)
                return@launch
            }

            if (VoiceSatelliteService.getInstance() != null) {
                clearPendingAccessibilityAutoStart(service)
                return@launch
            }

            val autoRestartEnabled = runCatching {
                service.applicationContext.playerSettingsStore.data.first().enableAutoRestart
            }.getOrDefault(false)

            if (!autoRestartEnabled) {
                clearPendingAccessibilityAutoStart(service)
                return@launch
            }

            try {
                val intent = Intent(service, VoiceSatelliteService::class.java)
                ContextCompat.startForegroundService(service, intent)
                clearPendingAccessibilityAutoStart(service)
                Log.i(TAG, "Started VoiceSatelliteService from accessibility boot fallback")
            } catch (e: Exception) {
                Log.e(TAG, "Accessibility boot fallback failed to start VoiceSatelliteService", e)
            }
        }
    }

    private fun clearPendingAccessibilityAutoStart(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_ACCESSIBILITY_AUTOSTART)
            .remove(KEY_PENDING_ACCESSIBILITY_AUTOSTART_AT)
            .apply()
    }

    internal fun onAccessibilityEvent(event: AccessibilityEvent?) {
        com.example.ava.touchpad.AvaAutoKeys.noteAccessibility(event ?: return)
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AvaAccessibilityService::class.java).flattenToString()
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (manager != null) {
            val enabledServices = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            if (enabledServices.any { it.resolveInfo.serviceInfo?.packageName == context.packageName && it.resolveInfo.serviceInfo?.name == AvaAccessibilityService::class.java.name }) {
                return true
            }
        }
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** Opens the system Accessibility settings list so the user can enable Ava. */
    @JvmStatic
    fun openSettings(context: Context): Boolean = runCatching {
        context.applicationContext.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    }.getOrDefault(false)

    @JvmStatic
    fun getStatus(context: Context): String {
        val hasService = service != null
        return JSONObject().apply {
            put("enabled", isEnabled(context))
            put("connected", hasService)
            put("mode", if (hasService) "accessibility" else "disabled")
            put("supportsScreenshots", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            put("supportsGestures", Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        }.toString()
    }

    @JvmStatic
    @JvmOverloads
    fun dumpUiTreeJson(includeFallbackScreenshot: Boolean = true): String {
        refreshTreeSnapshot(includeFallbackScreenshot = includeFallbackScreenshot)
        return lastTreeJson
    }

    @JvmStatic
    fun clickByIndex(index: Int, fallbackX: Int? = null, fallbackY: Int? = null): String {
        return performIndexedAction(index, "click", fallbackX, fallbackY) { node ->
            performClick(node)
        }
    }

    @JvmStatic
    fun setTextByIndex(index: Int, text: String, fallbackX: Int? = null, fallbackY: Int? = null): String {
        return performIndexedAction(index, "set_text", fallbackX, fallbackY) { node ->
            val args = android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    @JvmStatic
    fun scrollByIndex(index: Int, forward: Boolean = true, fallbackX: Int? = null, fallbackY: Int? = null): String {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return performIndexedAction(index, "scroll", fallbackX, fallbackY) { node ->
            performScroll(node, action)
        }
    }

    @JvmStatic
    fun tap(x: Int, y: Int): Boolean {
        val activeService = service ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return dispatchStroke(activeService, x, y, x, y, 80)
    }

    @JvmStatic
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val activeService = service ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return dispatchStroke(activeService, x1, y1, x2, y2, durationMs.coerceAtLeast(80))
    }

    /** Soft Back via accessibility global action (same as nav-bar back). */
    @JvmStatic
    fun back(): Boolean {
        val activeService = service ?: return false
        return activeService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /** Overview / recents, same as the system Recents button. */
    @JvmStatic
    fun recents(): Boolean {
        val activeService = service ?: return false
        return activeService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    /** Notification shade, same as a status-bar pull-down. */
    @JvmStatic
    fun notifications(): Boolean {
        val activeService = service ?: return false
        return expandNotifications(activeService)
    }

    /**
     * Same three-finger pull-down cycles the shade: open, then close, then open.
     */
    @JvmStatic
    fun toggleNotifications(): Boolean {
        val activeService = service ?: return false
        val open = notificationsAreShowing(activeService)
        val ok = if (open) {
            collapseNotifications(activeService)
        } else {
            expandNotifications(activeService)
        }
        if (ok) notificationsOpen = !open
        return ok
    }

    @JvmStatic
    fun notificationsAreShowing(): Boolean {
        val activeService = service ?: return notificationsOpen
        return notificationsAreShowing(activeService)
    }

    @JvmStatic
    fun dismissNotifications(): Boolean {
        val activeService = service ?: return false
        return collapseNotifications(activeService)
    }

    @JvmStatic
    fun markNotificationsOpen(open: Boolean) {
        notificationsOpen = open
    }

    private fun expandNotifications(activeService: AvaAccessibilityService): Boolean {
        val ok = activeService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        if (ok) notificationsOpen = true
        return ok
    }

    private fun collapseNotifications(activeService: AvaAccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            activeService.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE,
            )
        ) {
            notificationsOpen = false
            return true
        }
        val collapsed = collapseStatusBarPanels(activeService)
        if (collapsed) notificationsOpen = false
        return collapsed
    }

    private fun notificationsAreShowing(activeService: AvaAccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val windows = activeService.windows
                if (windows != null && windows.any { window ->
                    val title = window.title?.toString().orEmpty()
                    title.contains("Notification shade", ignoreCase = true) ||
                        title.contains("NotificationShade", ignoreCase = true)
                }) {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return notificationsOpen
    }

    private fun collapseStatusBarPanels(context: Context): Boolean {
        return try {
            val statusBar = context.getSystemService("statusbar") ?: return false
            statusBar.javaClass.getMethod("collapsePanels").invoke(statusBar)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Home, same as the system Home button. */
    @JvmStatic
    fun home(): Boolean {
        val activeService = service ?: return false
        return activeService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /** Commit the IME on the focused field (Enter). */
    @JvmStatic
    fun imeEnter(): Boolean {
        val activeService = service ?: return false
        val root = activeService.rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return try {
            if (focused == null) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                focused.performAction(ACTION_IME_ENTER)
            ) {
                true
            } else {
                focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        } finally {
            runCatching { focused?.recycle() }
            runCatching { root.recycle() }
        }
    }

    /** Replace text in the focused editable field. Soft-keyboard typing without root. */
    @JvmStatic
    fun setFocusedText(text: String): Boolean {
        val activeService = service ?: return false
        val root = activeService.rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return try {
            val target = focused?.takeIf { it.isEditable && !it.isPassword } ?: return false
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            runCatching { focused?.recycle() }
            runCatching { root.recycle() }
        }
    }

    private fun dispatchStroke(
        activeService: AvaAccessibilityService,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int
    ): Boolean {
        val waitMs = gestureWaitMs(durationMs)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return dispatchStrokeOnMain(activeService, x1, y1, x2, y2, durationMs, waitMs = waitMs)
        }
        val result = AtomicReference(false)
        val accepted = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        mainHandler.post {
            dispatchStrokeOnMain(
                activeService, x1, y1, x2, y2, durationMs,
                result, latch, accepted, cancelled, waitMs,
            )
        }
        val done = latch.await(waitMs, TimeUnit.MILLISECONDS)
        if (cancelled.get()) return false
        if (result.get()) return true
        // Accepted, still running past wait → success (avoid shell double-fire).
        return !done && accepted.get()
    }

    private fun gestureWaitMs(durationMs: Int): Long =
        (durationMs.toLong() + 800L).coerceIn(500L, 4_000L)

    @JvmStatic
    fun takeScreenshot(context: Context): String {
        val activeService = service ?: return """{"ok":false,"error":"service_not_connected"}"""
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return """{"ok":false,"error":"screenshot_requires_api_30"}"""
        }
        val now = System.currentTimeMillis()
        val sinceLast = now - lastDirectScreenshotAtMs
        if (sinceLast in 0 until DIRECT_SCREENSHOT_INTERVAL_MS) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "take_screenshot_interval_time_short")
                put("retryAfterMs", DIRECT_SCREENSHOT_INTERVAL_MS - sinceLast)
            }.toString()
        }
        lastDirectScreenshotAtMs = now
        val outputFile = File(context.filesDir, "accessibility/last_ui.png").apply {
            parentFile?.mkdirs()
        }
        val result = AtomicReference(
            JSONObject().apply {
                put("ok", false)
                put("error", "unknown")
            }
        )
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return takeScreenshotOnMain(activeService, context, outputFile, result)
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            takeScreenshotOnMain(activeService, context, outputFile, result, latch)
        }
        if (!latch.await(4, TimeUnit.SECONDS)) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "take_screenshot_timeout")
            }.toString()
        }
        return result.get().toString()
    }

    private fun performIndexedAction(
        index: Int,
        actionName: String,
        fallbackX: Int?,
        fallbackY: Int?,
        action: (AccessibilityNodeInfo) -> Boolean
    ): String {
        val activeService = service ?: return """{"ok":false,"error":"service_not_connected"}"""
        val result = AtomicReference(
            JSONObject().apply {
                put("ok", false)
                put("action", actionName)
                put("index", index)
            }
        )
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val finalResult = performIndexedActionOnMain(index, actionName, fallbackX, fallbackY, action)
            refreshTreeSnapshot(includeFallbackScreenshot = false)
            return finalResult
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            result.set(JSONObject(performIndexedActionOnMain(index, actionName, fallbackX, fallbackY, action)))
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        refreshTreeSnapshot(includeFallbackScreenshot = false)
        return result.get().toString()
    }

    private fun resolveFallbackPoint(index: Int, fallbackX: Int?, fallbackY: Int?): Pair<Int, Int>? {
        if (fallbackX != null && fallbackY != null) {
            return fallbackX to fallbackY
        }
        return null
    }

    private fun refreshTreeSnapshot(includeFallbackScreenshot: Boolean = false) {
        val activeService = service ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            buildTreeSnapshotOnMain(activeService, includeFallbackScreenshot)?.let {
                lastTreeJson = it.first
                lastSnapshotCenters = it.second
            }
            return
        }
        val result = runOnMainBlocking {
            buildTreeSnapshotOnMain(activeService, includeFallbackScreenshot) ?: ("""{"status":"unavailable"}""" to emptyMap())
        }
        if (result != null) {
            lastTreeJson = result.first
            lastSnapshotCenters = result.second
        }
    }

    private fun performIndexedActionOnMain(
        index: Int,
        actionName: String,
        fallbackX: Int?,
        fallbackY: Int?,
        action: (AccessibilityNodeInfo) -> Boolean
    ): String {
        var usedFallback = false
        val activeService = service
        val roots = if (activeService != null) buildRoots(activeService) else emptyList()
        val indexedNodes = flattenNodes(roots)
        val node = indexedNodes.getOrNull(index)
        val success = if (node != null) action(node.info) else false
        val result = if (!success) {
            val fallback = resolveFallbackPoint(index, fallbackX, fallbackY)
            if (fallback != null && activeService != null && dispatchStrokeOnMain(activeService, fallback.first, fallback.second, fallback.first, fallback.second, 80)) {
                usedFallback = true
                JSONObject().apply {
                    put("ok", true)
                    put("action", actionName)
                    put("index", index)
                    put("mode", "coordinate_fallback")
                    put("x", fallback.first)
                    put("y", fallback.second)
                }.toString()
            } else {
                ""
            }
        } else {
            ""
        }
        indexedNodes.forEach { it.info.recycle() }
        if (usedFallback) return result
        return JSONObject().apply {
            put("ok", success)
            put("action", actionName)
            put("index", index)
            put("mode", if (success) "accessibility_node" else "failed")
        }.toString()
    }

    private fun buildTreeSnapshotOnMain(activeService: AvaAccessibilityService, includeFallbackScreenshot: Boolean): Pair<String, Map<Int, Pair<Int, Int>>>? {
        val roots = buildRoots(activeService)
        val indexedNodes = flattenNodes(roots)
        val centers = LinkedHashMap<Int, Pair<Int, Int>>()
        val nodesJson = JSONArray()
        val windowsJson = JSONArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activeService.windows
                ?.sortedBy { it.layer }
                ?.forEach { window ->
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    windowsJson.put(
                        JSONObject().apply {
                            put("id", window.id)
                            put("layer", window.layer)
                            put("type", window.type)
                            put("title", window.title?.toString().orEmpty())
                            put("active", window.isActive)
                            put("focused", window.isFocused)
                            put("accessibilityFocused", window.isAccessibilityFocused)
                            put("hasRoot", window.root != null)
                            put(
                                "bounds",
                                JSONObject().apply {
                                    put("left", bounds.left)
                                    put("top", bounds.top)
                                    put("right", bounds.right)
                                    put("bottom", bounds.bottom)
                                }
                            )
                        }
                    )
                }
        }
        indexedNodes.forEachIndexed { index, node ->
            val bounds = Rect()
            node.info.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                centers[index] = bounds.centerX() to bounds.centerY()
            }
            nodesJson.put(
                JSONObject().apply {
                    put("index", index)
                    put("windowId", node.windowId)
                    put("depth", node.depth)
                    put("className", node.info.className?.toString().orEmpty())
                    put("viewId", node.info.viewIdResourceName.orEmpty())
                    put("packageName", node.info.packageName?.toString().orEmpty())
                    put("text", node.info.text?.toString().orEmpty())
                    put("contentDescription", node.info.contentDescription?.toString().orEmpty())
                    put("clickable", node.info.isClickable)
                    put("editable", node.info.isEditable)
                    put("scrollable", node.info.isScrollable)
                    put("password", node.info.isPassword)
                    put("enabled", node.info.isEnabled)
                    put("visible", node.info.isVisibleToUser)
                    put(
                        "bounds",
                        JSONObject().apply {
                            put("left", bounds.left)
                            put("top", bounds.top)
                            put("right", bounds.right)
                            put("bottom", bounds.bottom)
                            put("centerX", bounds.centerX())
                            put("centerY", bounds.centerY())
                        }
                    )
                }
            )
        }
        indexedNodes.forEach { it.info.recycle() }
        val screenshotJson = if (includeFallbackScreenshot) {
            maybeCaptureTreeFallbackScreenshot(activeService, nodesJson.length())
        } else {
            null
        }
        return JSONObject().apply {
            put("status", "ok")
            put("windowCount", roots.size)
            put("nodeCount", nodesJson.length())
            put("windows", windowsJson)
            put("nodes", nodesJson)
            if (nodesJson.length() == 0) {
                put("warning", "UI tree is empty or overlay-only. Standard accessibility trees often cannot see SYSTEM_ALERT_WINDOW content.")
            } else if (nodesJson.length() < 4) {
                put("warning", "UI tree is sparse. Overlay content may not be fully represented.")
            }
            screenshotJson?.let { put("fallbackScreenshot", it) }
        }.toString() to centers
    }

    private fun maybeCaptureTreeFallbackScreenshot(activeService: AvaAccessibilityService, nodeCount: Int): JSONObject? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (nodeCount >= 4) return null
        val now = System.currentTimeMillis()
        if (now - lastFallbackScreenshotAtMs < FALLBACK_SCREENSHOT_INTERVAL_MS) {
            return null
        }
        val outputFile = File(activeService.filesDir, "accessibility/last_tree_fallback.png").apply {
            parentFile?.mkdirs()
        }
        val result = AtomicReference(
            JSONObject().apply {
                put("ok", false)
                put("error", "unknown")
            }
        )
        takeScreenshotOnMain(activeService, activeService, outputFile, result)
        lastFallbackScreenshotAtMs = now
        return result.get().takeIf {
            it.optBoolean("ok") || it.has("error")
        }
    }

    private fun dispatchStrokeOnMain(
        activeService: AvaAccessibilityService,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        result: AtomicReference<Boolean>? = null,
        latch: CountDownLatch? = null,
        acceptedOut: AtomicBoolean? = null,
        cancelledOut: AtomicBoolean? = null,
        waitMs: Long = gestureWaitMs(durationMs),
    ): Boolean {
        val internalResult = result ?: AtomicReference(false)
        val internalLatch = latch ?: CountDownLatch(1)
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            durationMs.toLong().coerceAtLeast(1L),
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = activeService.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    internalResult.set(true)
                    internalLatch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    cancelledOut?.set(true)
                    internalResult.set(false)
                    internalLatch.countDown()
                }
            },
            null,
        )
        acceptedOut?.set(accepted)
        if (!accepted) {
            internalResult.set(false)
            internalLatch.countDown()
            return false
        }
        if (latch == null) {
            val done = internalLatch.await(waitMs, TimeUnit.MILLISECONDS)
            if (cancelledOut?.get() == true) return false
            if (internalResult.get()) return true
            // Still running after wait — treat as success so callers skip shell fallback.
            if (!done) {
                internalResult.set(true)
            }
        }
        return internalResult.get()
    }

    private fun takeScreenshotOnMain(
        activeService: AvaAccessibilityService,
        context: Context,
        outputFile: File,
        result: AtomicReference<JSONObject>,
        latch: CountDownLatch? = null
    ): String {
        val internalLatch = latch ?: CountDownLatch(1)
        activeService.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(activeService),
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        saveScreenshot(outputFile, screenshot)
                        result.set(
                            JSONObject().apply {
                                put("ok", true)
                                put("path", outputFile.absolutePath)
                            }
                        )
                    } catch (e: Exception) {
                        result.set(
                            JSONObject().apply {
                                put("ok", false)
                                put("error", e.message ?: "save_failed")
                            }
                        )
                    } finally {
                        internalLatch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    result.set(
                        JSONObject().apply {
                            put("ok", false)
                            put("error", describeTakeScreenshotError(errorCode))
                            put("errorCode", errorCode)
                        }
                    )
                    internalLatch.countDown()
                }
            }
        )
        if (latch == null) {
            internalLatch.await(4, TimeUnit.SECONDS)
        }
        return result.get().toString()
    }

    private fun runOnMainBlocking(block: () -> Pair<String, Map<Int, Pair<Int, Int>>>): Pair<String, Map<Int, Pair<Int, Int>>>? {
        val result = AtomicReference<Pair<String, Map<Int, Pair<Int, Int>>>>()
        val latch = CountDownLatch(1)
        mainHandler.post {
            runCatching { block() }
                .onSuccess { result.set(it) }
                .onFailure {
                    result.set(
                        JSONObject().apply {
                            put("status", "error")
                            put("message", it.message ?: "unknown")
                        }.toString() to emptyMap()
                    )
                }
            latch.countDown()
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private fun buildRoots(service: AvaAccessibilityService): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            service.windows
                ?.sortedBy { it.layer }
                ?.forEach { window ->
                    window.root?.let { roots += AccessibilityNodeInfo.obtain(it) }
                }
        }
        if (roots.isEmpty()) {
            service.rootInActiveWindow?.let { roots += AccessibilityNodeInfo.obtain(it) }
        }
        return roots
    }

    private fun flattenNodes(roots: List<AccessibilityNodeInfo>): List<IndexedNode> {
        val result = mutableListOf<IndexedNode>()
        roots.forEach { root ->
            traverseNode(root, root.windowId, 0, result)
            root.recycle()
        }
        return result
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        windowId: Int,
        depth: Int,
        result: MutableList<IndexedNode>
    ) {
        val copy = AccessibilityNodeInfo.obtain(node)
        result += IndexedNode(copy, windowId, depth)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverseNode(child, windowId, depth + 1, result)
                child.recycle()
            }
        }
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                current.recycle()
                return true
            }
            val parent = current.parent?.let { AccessibilityNodeInfo.obtain(it) }
            current.recycle()
            current = parent
        }
        return false
    }

    private fun performScroll(node: AccessibilityNodeInfo, action: Int): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (current != null) {
            if (current.isScrollable && current.performAction(action)) {
                current.recycle()
                return true
            }
            val parent = current.parent?.let { AccessibilityNodeInfo.obtain(it) }
            current.recycle()
            current = parent
        }
        return false
    }

    @SuppressLint("WrongConstant")
    private fun saveScreenshot(file: File, screenshot: AccessibilityService.ScreenshotResult) {
        val hardwareBuffer = screenshot.hardwareBuffer
        val colorSpace = screenshot.colorSpace
        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
            ?: throw IllegalStateException("Failed to wrap screenshot buffer")
        val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalStateException("Failed to copy screenshot buffer")
        FileOutputStream(file).use { out ->
            softwareBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        softwareBitmap.recycle()
        bitmap.recycle()
        hardwareBuffer.close()
    }

    private fun describeTakeScreenshotError(errorCode: Int): String {
        return when (errorCode) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "take_screenshot_internal_error"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "take_screenshot_no_accessibility_access"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "take_screenshot_interval_time_short"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "take_screenshot_invalid_display"
            5 -> "take_screenshot_invalid_window"
            6 -> "take_screenshot_secure_window"
            else -> "take_screenshot_failed_$errorCode"
        }
    }

    private data class IndexedNode(
        val info: AccessibilityNodeInfo,
        val windowId: Int,
        val depth: Int
    )
}
