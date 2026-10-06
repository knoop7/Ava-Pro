package com.example.ava.localllm.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.ava.services.AppWindowService
import com.example.ava.fleet.FleetScreenShot
import com.example.ava.fleet.FleetShell
import com.example.ava.localllm.DeviceIndex
import com.example.ava.localllm.HaSpokenHear
import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolArgumentCase
import com.example.ava.localllm.ToolDef
import com.example.ava.localllm.ToolParam
import com.example.ava.localllm.ToolParamType
import com.example.ava.mods.ModScreenCapture
import com.example.ava.permissions.OverlayPermission
import com.example.ava.permissions.PermissionManager
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.services.AccessibilityBridge
import com.example.ava.services.AvaAccessibilityService
import com.example.ava.touchpad.AiPhonePointer
import com.example.ava.touchpad.AvaInAppHost
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Apps and UI on this Android device. Wires [AccessibilityBridge],
 * [FleetShell], and [AppWindowService] — not Home Assistant, not a mod.
 */
object AvaPhoneTools {

    const val NAME = "ava_phone"
    const val PREFIX = "ava_phone"

    private const val TREE_CAP = 80
    private var liveIndexes: Set<Int> = emptySet()
    private var liveCenters: Map<Int, Pair<Int, Int>> = emptyMap()
    private var incident: String? = null
    private const val OBSERVE_HINT =
        "The screen may have changed. Find the next visible text before speaking. Do not reuse the previous index."
    private const val SPEAK_ENABLE =
        "Ava Accessibility is off. The system Accessibility settings page is open. Tell the user, in their language, " +
            "that you opened that page and they should enable Ava there. If they cannot use that page, speak only the adb line from details. " +
            "Do not mention the touch pad. Do not claim the app was controlled. After they enable it, they can ask again."
    private const val SPEAK_OVERLAY =
        "The floating window needs overlay permission. If the user cannot grant it, speak only the adb line from details."

    fun live(app: Context): Boolean =
        AccessibilityBridge.isEnabled(app) ||
            AccessibilityBridge.isServiceConnected() ||
            FleetShell.backend() != null

    /** Indexes from an earlier turn or a packed tree are dead. */
    fun beginTurn() {
        liveIndexes = emptySet()
        liveCenters = emptyMap()
        incident = null
    }

    fun surface(on: Boolean): HaToolSet {
        if (!on) return HaToolSet(emptyList())
        return HaToolSet(
            listOf(
                ToolDef(
                    NAME,
                    "Control apps and the screen on THIS device. Launch by spoken name. " +
                        "Prefer click/type query= over tree plus index. After click/type/scroll, follow next_action before speaking. " +
                        "A stale_ref means the screen changed: find again; do not reuse the old index. " +
                        "UI actions inside THIS app (settings, home) do not need Accessibility — the host taps our own window. Other apps still need Ava Accessibility. The touch-pad window does not need to be open. The host tries to enable Accessibility silently first when going outside. A need_accessibility error means " +
                        "the system Accessibility settings page is already open (Android intent): tell the user to enable Ava there and stop; the action did not run. Never ha_search an app.",
                    listOf(
                        ToolParam(
                            "action",
                            ToolParamType.Enum(
                                listOf(
                                    "status", "enable", "launch", "tree", "find", "click",
                                    "type", "scroll", "tap", "swipe", "back", "screenshot",
                                ),
                            ),
                            "status=readiness, enable=open the system Accessibility settings page (Android intent), launch=open an app, " +
                                "tree/find=read the current screen, click/type/scroll=act then find again, tap/swipe/back=gestures, screenshot=capture",
                            required = true,
                        ),
                        ToolParam("target", ToolParamType.Str, "launch: spoken app name or package", required = false),
                        ToolParam("window", ToolParamType.Bool, "launch: true opens the floating app window", required = false),
                        ToolParam("query", ToolParamType.Str, "find/click/type: visible text on screen", required = false),
                        ToolParam("index", ToolParamType.Int(0, 9_999), "click/type/scroll: node index from the last tree or find this turn; invalid after a click", required = false),
                        ToolParam("text", ToolParamType.Str, "type: text to set", required = false),
                        ToolParam("forward", ToolParamType.Bool, "scroll: true down, false up. Default true", required = false),
                        ToolParam("x", ToolParamType.Int(0, 10_000), "tap, or swipe start X", required = false),
                        ToolParam("y", ToolParamType.Int(0, 10_000), "tap, or swipe start Y", required = false),
                        ToolParam("x2", ToolParamType.Int(0, 10_000), "swipe end X", required = false),
                        ToolParam("y2", ToolParamType.Int(0, 10_000), "swipe end Y", required = false),
                    ),
                    argumentCases = listOf(
                        ToolArgumentCase(action = "status", fields = emptySet()),
                        ToolArgumentCase(action = "enable", fields = emptySet()),
                        ToolArgumentCase(action = "launch", fields = setOf("target", "window"), required = setOf("target")),
                        ToolArgumentCase(action = "tree", fields = emptySet()),
                        ToolArgumentCase(action = "find", fields = setOf("query"), required = setOf("query")),
                        ToolArgumentCase(action = "click", fields = setOf("index", "query"), atLeastOne = setOf("index", "query")),
                        ToolArgumentCase(action = "type", fields = setOf("index", "query", "text"), required = setOf("text"), atLeastOne = setOf("index", "query")),
                        ToolArgumentCase(action = "scroll", fields = setOf("index", "query", "forward"), atLeastOne = setOf("index", "query")),
                        ToolArgumentCase(action = "tap", fields = setOf("x", "y"), required = setOf("x", "y")),
                        ToolArgumentCase(action = "swipe", fields = setOf("x", "y", "x2", "y2"), required = setOf("x", "y", "x2", "y2")),
                        ToolArgumentCase(action = "back", fields = emptySet()),
                        ToolArgumentCase(action = "screenshot", fields = emptySet()),
                    ),
                ),
            ),
        )
    }

    suspend fun execute(call: AvaToolCallback.Call, app: Context): AvaToolCallback.Result {
        val ctx = app.applicationContext
        return when (call.arguments.optString("action").trim()) {
            "status" -> status(ctx)
            "enable" -> enable(ctx)
            "launch" -> launch(call.arguments, ctx)
            "tree" -> tree(ctx)
            "find" -> find(call.arguments, ctx)
            "click" -> click(call.arguments, ctx)
            "type" -> type(call.arguments, ctx)
            "scroll" -> scroll(call.arguments, ctx)
            "tap" -> tap(call.arguments, ctx)
            "swipe" -> swipe(call.arguments, ctx)
            "back" -> back(ctx)
            "screenshot" -> screenshot(ctx)
            else -> AvaToolCallback.fail("invalid_request", "unknown action")
        }
    }

    private fun status(app: Context): AvaToolCallback.Result {
        val connected = AccessibilityBridge.isServiceConnected()
        val shell = FleetShell.backend() != null
        val raw = JSONObject()
            .put("enabled", AccessibilityBridge.isEnabled(app))
            .put("connected", connected)
            .put("shell", shell)
            .put("gestures", (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && connected) || shell)
            .put("screenshot", shell || FleetScreenShot.canAccessibilityCapture())
        if (!connected && !shell) {
            raw.put("hint", "Ava Accessibility is not connected. The touch pad does not need to be open. Call enable: the host tries a silent grant, and otherwise opens the system Accessibility settings page with an Android intent and returns need_accessibility with what to tell the user.")
        }
        return AvaToolCallback.ok(raw)
    }

    private suspend fun enable(app: Context): AvaToolCallback.Result {
        if (ensureUiReady(app)) {
            return AvaToolCallback.ok(
                JSONObject()
                    .put("enabled", true)
                    .put("connected", AccessibilityBridge.isServiceConnected()),
            )
        }
        return needAccessibility(app)
    }

    private suspend fun launch(args: JSONObject, app: Context): AvaToolCallback.Result {
        val spoken = args.optString("target").trim()
        if (spoken.isEmpty()) return AvaToolCallback.fail("invalid_request", "target is required")
        val hits = resolveApps(app, spoken)
        if (hits.isEmpty()) return AvaToolCallback.fail("not_found", "no app named \"$spoken\"")
        if (hits.size > 1) {
            val names = JSONArray()
            for (hit in hits.take(8)) names.put(JSONObject().put("name", hit.label).put("package", hit.pkg))
            return AvaToolCallback.fail(
                "ambiguous",
                "More than one app matches; ask the user which one.",
                JSONObject().put("candidates", names),
            )
        }
        val hit = hits.first()
        val window = args.optBoolean("window", false)
        return if (window) {
            if (!PlatformCapabilities.canDrawOverlays(app)) OverlayPermission.tryPrivilegedGrant(app)
            if (!PlatformCapabilities.canDrawOverlays(app)) return needOverlay(app)
            runCatching { AppWindowService.start(app, hit.pkg) }
                .onFailure { return AvaToolCallback.fail("tool_error", "could not open the floating window") }
            val body = JSONObject().put("name", hit.label).put("window", true)
            AvaOverlayReceipts.awaitAndAttach(body, AvaOverlayReceipts.APP_WINDOW, true)
            accepted(body)
        } else {
            val launch = app.packageManager.getLaunchIntentForPackage(hit.pkg)
                ?: return AvaToolCallback.fail("not_found", "${hit.label} is not launchable")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(launch) }
                .onFailure { return AvaToolCallback.fail("tool_error", "could not launch") }
            accepted(JSONObject().put("name", hit.label).put("window", false))
        }
    }

    private suspend fun tree(app: Context): AvaToolCallback.Result {
        if (AvaInAppHost.needsAccessibility(localHit = false, goingOutside = false)) {
            needUi(app)?.let { return it }
        }
        val raw = dumpPreferred() ?: return AvaToolCallback.fail("tool_error", "could not read the UI tree")
        val compact = compactTree(raw)
        rememberNodes(compact.optJSONArray("nodes") ?: JSONArray())
        if (raw.optString("via") == "in_app") compact.put("via", "in_app")
        return AvaToolCallback.ok(compact)
    }

    private suspend fun find(args: JSONObject, app: Context): AvaToolCallback.Result {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return AvaToolCallback.fail("invalid_request", "query is required")
        if (!AvaInAppHost.isInFront()) {
            needUi(app)?.let { return it }
        }
        val raw = dumpPreferred() ?: return AvaToolCallback.fail("tool_error", "could not read the UI tree")
        val hits = findNodes(raw, query)
        if (hits.length() > 0) {
            rememberNodes(hits)
            return AvaToolCallback.ok(JSONObject().put("count", hits.length()).put("nodes", hits))
        }
        val outside = queryLooksLikeApp(app, query)
        if (AvaInAppHost.needsAccessibility(localHit = false, goingOutside = outside)) {
            needUi(app)?.let { return it }
            val other = dumpTree() ?: return miss("no on-screen text matches \"$query\"")
            val next = findNodes(other, query)
            if (next.length() == 0) return miss("no on-screen text matches \"$query\"")
            rememberNodes(next)
            return AvaToolCallback.ok(JSONObject().put("count", next.length()).put("nodes", next))
        }
        return miss("no on-screen text matches \"$query\"")
    }

    private suspend fun click(args: JSONObject, app: Context): AvaToolCallback.Result {
        val query = args.optString("query").trim()
        if (!AvaInAppHost.isInFront()) {
            needUi(app)?.let { return it }
        }
        val index = when (val target = resolveTarget(args, app)) {
            is Target.Ok -> target.index
            is Target.Fail -> {
                if (AvaInAppHost.isInFront() && queryLooksLikeApp(app, query)) {
                    needUi(app)?.let { return it }
                    when (val retry = resolveTarget(args, app, preferLocal = false)) {
                        is Target.Ok -> retry.index
                        is Target.Fail -> return retry.result
                    }
                } else {
                    return target.result
                }
            }
        }
        val point = liveCenter(index)
        if (point != null) {
            AiPhonePointer.appear(app, point.first.toFloat(), point.second.toFloat())
            if (AvaInAppHost.tap(point.first.toFloat(), point.second.toFloat())) {
                AiPhonePointer.confirmTap()
                return accepted(JSONObject().put("index", index).put("via", "in_app"), query)
            }
        }
        if (!AccessibilityBridge.isServiceConnected()) {
            if (point != null) AiPhonePointer.dismiss()
            if (AvaInAppHost.isInFront()) {
                return miss("could not tap that on this screen")
            }
            needUi(app)?.let { return it }
            return gestureFallback(app, "click failed")
        }
        val result = parseIndexed(
            AccessibilityBridge.clickByIndex(index, point?.first, point?.second),
            index,
            query,
        )
        if (result.ok) AiPhonePointer.confirmTap() else AiPhonePointer.dismiss()
        return result
    }

    private suspend fun type(args: JSONObject, app: Context): AvaToolCallback.Result {
        if (!AvaInAppHost.isInFront()) {
            needUi(app)?.let { return it }
        } else if (!AccessibilityBridge.isServiceConnected()) {
            return AvaToolCallback.fail("tool_error", "typing that field needs Accessibility")
        }
        val text = args.optString("text")
        val query = args.optString("query").trim()
        val index = when (val target = resolveTarget(args, app, none = "no editable match")) {
            is Target.Ok -> target.index
            is Target.Fail -> return target.result
        }
        return parseIndexed(AccessibilityBridge.setTextByIndex(index, text), index, query)
    }

    private suspend fun scroll(args: JSONObject, app: Context): AvaToolCallback.Result {
        if (!AvaInAppHost.isInFront()) {
            needUi(app)?.let { return it }
        } else if (!AccessibilityBridge.isServiceConnected()) {
            return AvaToolCallback.fail("tool_error", "scroll outside this app needs Accessibility")
        }
        val query = args.optString("query").trim()
        val index = when (val target = resolveTarget(args, app, none = "no scrollable match")) {
            is Target.Ok -> target.index
            is Target.Fail -> return target.result
        }
        val forward = if (args.has("forward")) args.optBoolean("forward") else true
        return parseIndexed(AccessibilityBridge.scrollByIndex(index, forward), index, query)
    }

    private suspend fun tap(args: JSONObject, app: Context): AvaToolCallback.Result {
        val x = args.optInt("x")
        val y = args.optInt("y")
        if (!canA11yGesture() && FleetShell.backend() == null) {
            AiPhonePointer.appear(app, x.toFloat(), y.toFloat())
            if (AiPhonePointer.tryInjectLocal(x.toFloat(), y.toFloat())) {
                AiPhonePointer.confirmTap()
                return accepted(JSONObject().put("x", x).put("y", y).put("via", "local"))
            }
            AiPhonePointer.dismiss()
            return needUi(app) ?: gestureFallback(app, "tap failed")
        }
        AiPhonePointer.appear(app, x.toFloat(), y.toFloat())
        val via = when {
            AiPhonePointer.tryInjectLocal(x.toFloat(), y.toFloat()) -> "local"
            AiPhonePointer.tryClickThrough(app, x.toFloat(), y.toFloat()) -> "click_through"
            canA11yGesture() && AccessibilityBridge.tap(x, y) -> "accessibility"
            shellTap(app, "input tap $x $y", 4) -> "shell"
            else -> {
                AiPhonePointer.dismiss()
                return gestureFallback(app, "tap failed")
            }
        }
        AiPhonePointer.confirmTap()
        return accepted(JSONObject().put("x", x).put("y", y).put("via", via))
    }

    private suspend fun swipe(args: JSONObject, app: Context): AvaToolCallback.Result {
        val x1 = args.optInt("x")
        val y1 = args.optInt("y")
        val x2 = args.optInt("x2")
        val y2 = args.optInt("y2")
        if (!canA11yGesture() && FleetShell.backend() == null) {
            return needUi(app) ?: gestureFallback(app, "swipe failed")
        }
        AiPhonePointer.appear(app, x1.toFloat(), y1.toFloat())
        val via = coroutineScope {
            val glide = launch {
                AiPhonePointer.glide(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), 280L)
            }
            val path = when {
                canA11yGesture() && AccessibilityBridge.swipe(x1, y1, x2, y2, 280) -> "accessibility"
                shellTap(app, "input swipe $x1 $y1 $x2 $y2 280", 6) -> "shell"
                else -> null
            }
            glide.join()
            path
        }
        if (via == null) {
            AiPhonePointer.dismiss()
            return gestureFallback(app, "swipe failed")
        }
        AiPhonePointer.confirmTap()
        return accepted(
            JSONObject().put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2).put("via", via),
        )
    }

    private suspend fun back(app: Context): AvaToolCallback.Result {
        if (AccessibilityBridge.isServiceConnected() && AccessibilityBridge.back()) {
            return accepted(JSONObject().put("via", "accessibility"))
        }
        if (shellTap(app, "input keyevent 4", 4)) {
            return accepted(JSONObject().put("via", "shell"))
        }
        return gestureFallback(app, "back failed")
    }

    private suspend fun screenshot(app: Context): AvaToolCallback.Result {
        val shot = runCatching { FleetScreenShot.captureOnce(app, force = true) }.getOrNull()
        if (shot != null) {
            return AvaToolCallback.ok(
                JSONObject()
                    .put("captured", true)
                    .put("width", shot.width)
                    .put("height", shot.height)
                    .put("note", "Pixels stay on device. Do not describe the screen."),
                status = "observed",
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && FleetShell.backend() == null) {
            return AvaToolCallback.fail(
                "tool_error",
                "Screenshot needs Shizuku or root on this Android.",
            )
        }
        needUi(app)?.let { return it }
        return AvaToolCallback.fail("tool_error", "screenshot failed")
    }

    private suspend fun gestureFallback(app: Context, failed: String): AvaToolCallback.Result {
        if (FleetShell.backend() != null) return AvaToolCallback.fail("tool_error", failed)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return AvaToolCallback.fail(
                "tool_error",
                "Gestures need Android 7, or Shizuku/root. Accessibility cannot tap here.",
            )
        }
        return needUi(app) ?: AvaToolCallback.fail("tool_error", failed)
    }

    private suspend fun needUi(app: Context): AvaToolCallback.Result? {
        if (ensureUiReady(app)) return null
        if (AccessibilityBridge.isEnabled(app)) {
            return AvaToolCallback.fail(
                "tool_error",
                "Ava Accessibility is enabled but the service is not connected. Ask the user to toggle Ava off and on, then retry.",
            )
        }
        return needAccessibility(app)
    }

    private suspend fun ensureUiReady(app: Context): Boolean {
        if (AccessibilityBridge.isServiceConnected()) return true
        PermissionManager.tryGrantSecureSettings(app)
        if (ModScreenCapture.ensureAccessibility(app, openSettingsIfNeeded = false)) {
            if (AccessibilityBridge.isServiceConnected()) return true
            return waitConnected(800)
        }
        return AccessibilityBridge.isServiceConnected()
    }

    private suspend fun waitConnected(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (AccessibilityBridge.isServiceConnected()) return true
            try {
                Thread.sleep(80)
            } catch (_: InterruptedException) {
                break
            }
        }
        return AccessibilityBridge.isServiceConnected()
    }

    private fun needAccessibility(app: Context): AvaToolCallback.Result {
        openAccessibilityGuide(app)
        return AvaToolCallback.fail(
            "need_accessibility",
            SPEAK_ENABLE,
            JSONObject()
                .put("settings_opened", true)
                .put("via", "intent")
                .put("intent", "android.settings.ACCESSIBILITY_SETTINGS")
                .put("enabled", AccessibilityBridge.isEnabled(app))
                .put("connected", AccessibilityBridge.isServiceConnected())
                .put("adb", adbEnableLine(app)),
        )
    }

    private fun needOverlay(app: Context): AvaToolCallback.Result =
        AvaToolCallback.fail(
            "need_overlay",
            SPEAK_OVERLAY,
            JSONObject().put("adb", OverlayPermission.adbGrantCommand(app.packageName)),
        )

    private fun adbEnableLine(app: Context): String {
        val component = ComponentName(app, AvaAccessibilityService::class.java).flattenToString()
        return "adb shell settings put secure enabled_accessibility_services $component"
    }

    private fun canA11yGesture(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && AccessibilityBridge.isServiceConnected()

    private fun shellTap(app: Context, command: String, timeoutSec: Int): Boolean {
        if (FleetShell.backend() == null) return false
        return FleetShell.exec(app, command, timeoutSec = timeoutSec).ok
    }

    private fun dumpTree(): JSONObject? =
        runCatching { JSONObject(AccessibilityBridge.dumpUiTreeJson(includeFallbackScreenshot = false)) }.getOrNull()

    private suspend fun dumpPreferred(): JSONObject? {
        if (AvaInAppHost.isInFront()) {
            AvaInAppHost.dumpTree()?.let { return it }
        }
        return dumpTree()
    }

    private fun queryLooksLikeApp(app: Context, query: String): Boolean =
        query.isNotBlank() && resolveApps(app, query).isNotEmpty()

    private fun openAccessibilityGuide(app: Context) {
        val run = Runnable {
            if (AccessibilityBridge.openSettings(app)) return@Runnable
            ModScreenCapture.guideUserToGrantAccessibility(app)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run()
        else Handler(Looper.getMainLooper()).post(run)
    }

    private sealed class Target {
        class Ok(val index: Int) : Target()
        class Fail(val result: AvaToolCallback.Result) : Target()
    }

    private suspend fun resolveTarget(
        args: JSONObject,
        app: Context,
        none: String = "no clickable match",
        preferLocal: Boolean = true,
    ): Target {
        if (args.has("index") && args.opt("index") != JSONObject.NULL) {
            val index = args.optInt("index")
            if (index < 0) return Target.Fail(AvaToolCallback.fail("invalid_request", "index must be >= 0"))
            staleIndex(index)?.let { return Target.Fail(it) }
            return Target.Ok(index)
        }
        val query = args.optString("query").trim()
        if (query.isEmpty()) return Target.Fail(AvaToolCallback.fail("invalid_request", "query is required"))
        val raw = (if (preferLocal) dumpPreferred() else dumpTree())
            ?: return Target.Fail(AvaToolCallback.fail("tool_error", "could not read the UI tree"))
        val hits = findNodes(raw, query)
        val index = hits.optJSONObject(0)?.optInt("index", -1)?.takeIf { it >= 0 }
            ?: return Target.Fail(miss(none))
        rememberNodes(hits)
        return Target.Ok(index)
    }

    private fun parseIndexed(raw: String, index: Int, query: String, status: String = "accepted"): AvaToolCallback.Result {
        val json = runCatching { JSONObject(raw) }.getOrElse {
            return AvaToolCallback.fail("tool_error", "could not read the result")
        }
        json.remove("path")
        if (json.optBoolean("ok", json.optString("status") == "ok")) {
            return accepted(json, query, status)
        }
        return staleMiss(index)
    }

    private fun accepted(raw: JSONObject, query: String = "", status: String = "accepted"): AvaToolCallback.Result {
        liveIndexes = emptySet()
        incident = null
        return AvaToolCallback.ok(withObserve(raw, query), status = status)
    }

    private fun miss(why: String): AvaToolCallback.Result =
        failObserve("not_found", "$why. Find again; do not reuse an old index.")

    private fun staleMiss(index: Int): AvaToolCallback.Result {
        liveIndexes = emptySet()
        return failObserve(
            "stale_ref",
            "The screen changed; index $index is gone. Find again; do not reuse that index.",
            JSONObject().put("index", index),
        )
    }

    private fun failObserve(type: String, message: String, extra: JSONObject = JSONObject()): AvaToolCallback.Result {
        incident = message
        extra.put("incident", message)
        extra.put("next_action", nextObserve(""))
        return AvaToolCallback.fail(type, message, extra)
    }

    internal fun rememberNodes(nodes: JSONArray) {
        val kept = LinkedHashSet<Int>()
        val centers = LinkedHashMap<Int, Pair<Int, Int>>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val index = node.optInt("index", -1)
            if (index < 0) continue
            kept += index
            if (node.has("x") && node.has("y")) {
                centers[index] = node.optInt("x") to node.optInt("y")
            }
        }
        liveIndexes = kept
        liveCenters = centers
        incident = null
    }

    internal fun invalidateNodes() {
        liveIndexes = emptySet()
        liveCenters = emptyMap()
    }

    internal fun centerOf(index: Int): Pair<Int, Int>? =
        liveCenters[index] ?: AccessibilityBridge.centerOf(index)

    private fun liveCenter(index: Int): Pair<Int, Int>? = centerOf(index)

    internal fun staleIndex(index: Int): AvaToolCallback.Result? {
        if (index in liveIndexes) return null
        return failObserve(
            "stale_ref",
            "Index $index is not from the last find or tree. Find again; do not reuse that index.",
            JSONObject().put("index", index),
        )
    }

    internal fun withObserve(raw: JSONObject, query: String = ""): JSONObject {
        incident?.let { raw.put("incident", it) }
        return raw
            .put("requires_observation", true)
            .put("next_action", nextObserve(query))
            .put("hint", OBSERVE_HINT)
    }

    internal fun nextObserve(query: String): JSONObject {
        val args = JSONObject().put("action", if (query.isNotBlank()) "find" else "tree")
        if (query.isNotBlank()) args.put("query", query)
        return JSONObject().put("tool", NAME).put("arguments", args)
    }

    internal fun currentIncident(): String? = incident

    private data class AppHit(val label: String, val pkg: String)

    private fun resolveApps(app: Context, spoken: String): List<AppHit> {
        val needle = DeviceIndex.normalize(spoken)
        if (needle.isEmpty()) return emptyList()
        val pm = app.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolves = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.queryIntentActivities(launch, PackageManager.MATCH_DEFAULT_ONLY)
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launch, 0)
            }
        }.getOrDefault(emptyList())
        val out = ArrayList<AppHit>()
        for (info in resolves) {
            val pkg = info.activityInfo?.packageName ?: continue
            val label = info.loadLabel(pm).toString().trim()
            if (label.isEmpty()) continue
            val key = DeviceIndex.normalize(label)
            val pkgKey = pkg.lowercase(Locale.ROOT)
            val hit = key == needle || pkgKey == needle ||
                HaSpokenHear.meets(needle, key) ||
                (needle.length >= 3 && (key.contains(needle) || pkgKey.contains(needle)))
            if (hit) out += AppHit(label, pkg)
        }
        val exact = out.filter {
            DeviceIndex.normalize(it.label) == needle || it.pkg.lowercase(Locale.ROOT) == needle
        }
        return exact.ifEmpty { out }.distinctBy { it.pkg }
    }

    internal fun compactTree(raw: JSONObject): JSONObject {
        val nodes = raw.optJSONArray("nodes") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until nodes.length()) {
            if (kept.length() >= TREE_CAP) break
            val node = nodes.optJSONObject(i) ?: continue
            val text = node.optString("text")
            val desc = node.optString("contentDescription")
            if (text.isBlank() && desc.isBlank() &&
                !node.optBoolean("clickable") &&
                !node.optBoolean("editable") &&
                !node.optBoolean("checkable")
            ) {
                continue
            }
            kept.put(sanitizeNode(node))
        }
        val out = JSONObject()
            .put("status", raw.optString("status"))
            .put("nodeCount", raw.optInt("nodeCount"))
            .put("shown", kept.length())
            .put("nodes", kept)
        val warning = raw.optString("warning")
        if (warning.isNotBlank()) out.put("warning", warning)
        return out
    }

    internal fun findNodes(tree: JSONObject, query: String): JSONArray {
        val needle = DeviceIndex.normalize(query)
        val nodes = tree.optJSONArray("nodes") ?: JSONArray()
        val exact = JSONArray()
        val fuzzy = JSONArray()
        if (needle.isEmpty()) return exact
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            if (isPasswordNode(node)) continue
            val text = DeviceIndex.normalize(node.optString("text"))
            val desc = DeviceIndex.normalize(node.optString("contentDescription"))
            when {
                text == needle || desc == needle -> exact.put(sanitizeNode(node))
                needle.length >= 2 && (text.contains(needle) || desc.contains(needle)) -> fuzzy.put(sanitizeNode(node))
                HaSpokenHear.meets(needle, text) -> fuzzy.put(sanitizeNode(node))
                desc.isNotEmpty() && HaSpokenHear.meets(needle, desc) -> fuzzy.put(sanitizeNode(node))
            }
        }
        return if (exact.length() > 0) exact else fuzzy
    }

    internal fun sanitizeNode(node: JSONObject): JSONObject {
        val password = isPasswordNode(node)
        val bounds = node.optJSONObject("bounds")
        val out = JSONObject()
            .put("index", node.optInt("index"))
            .put("text", if (password) "" else node.optString("text"))
            .put("contentDescription", if (password) "" else node.optString("contentDescription"))
            .put("clickable", node.optBoolean("clickable"))
            .put("editable", node.optBoolean("editable"))
            .put("scrollable", node.optBoolean("scrollable"))
        if (node.has("checkable")) out.put("checkable", node.optBoolean("checkable"))
        if (node.has("checked")) out.put("checked", node.optBoolean("checked"))
        if (password) out.put("password", true)
        if (bounds != null) {
            out.put("x", bounds.optInt("centerX"))
            out.put("y", bounds.optInt("centerY"))
        }
        return out
    }

    private fun isPasswordNode(node: JSONObject): Boolean =
        node.optBoolean("password") ||
            node.optString("className").contains("Password", ignoreCase = true)
}
