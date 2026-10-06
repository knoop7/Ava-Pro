package com.example.ava.localllm.remote

import android.content.Context
import com.example.ava.services.AccessibilityBridge
import com.example.ava.services.AppWindowService
import com.example.ava.services.VinylCoverService
import com.example.ava.services.WebViewService
import com.example.ava.ui.MainNavigationCoordinator
import com.example.ava.ui.Screen
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live "which UI is on top" receipt. The model used to get HA-open, a
 * settings path, or an overlay list in isolation — then wrote the wrong
 * family and the screen did not move. The host peeks the real stack and
 * puts `here_ui`. Do not dump this table into the prompt.
 *
 * Families: system (Android / other apps), ava (Compose), hass (HA
 * browser overlay), overlay (Ava WM windows — clock, weather, vinyl,
 * research, app window). HA is hass, not overlay.
 *
 * When Ava's own UI is gone, [extract] reads the live screen the same
 * way the floating app window already does: package + accessibility
 * text, then the window tree if there is no text. No screenshot. Do
 * not change that window.
 */
internal object AvaUiHere {

    const val SYSTEM = "system"
    const val AVA = "ava"
    const val HASS = "hass"
    const val OVERLAY = "overlay"

    private const val VINYL = "vinyl_cover_display"
    private const val TEXT_CAP = 24
    private const val TREE_CAP = 8
    private val COVERING = listOf(
        AvaOverlayReceipts.APP_WINDOW,
        AvaOverlayReceipts.RESEARCH,
        "voice_message_display",
        VINYL,
        "screensaver_display",
        "dream_clock_display",
    )
    private val PLATES = listOf(
        "weather_display",
        "simple_clock_display",
        "quick_entity_display",
    )

    data class Snapshot(
        val visibleIds: Set<String> = emptySet(),
        val vinylFull: Boolean = false,
        val hassVisible: Boolean = false,
        val hassPath: String = "",
        val avaResumed: Boolean = false,
        val avaRoute: String? = null,
        val systemPackage: String? = null,
    )

    @Volatile
    private var override: Snapshot? = null

    fun peek(): JSONObject = build(override ?: live())

    fun sessionLine(app: Context? = null): String {
        val ui = extract(app)
        val top = ui.optString("top")
        val kind = ui.optString("kind")
        val path = ui.optString("path")
        val use = ui.optString("use")
        val under = ui.optString("under")
        val underPath = ui.optString("under_path")
        val pkg = ui.optString("package")
        val name = ui.optString("name")
        val seen = ui.optString("seen_via")
        return buildString {
            append("Current UI: top=").append(top)
            if (kind.isNotBlank()) append(" kind=").append(kind)
            if (name.isNotBlank()) append(" name=").append(name)
            if (path.isNotBlank()) append(" path=").append(path)
            if (pkg.isNotBlank()) append(" package=").append(pkg)
            append(" use=").append(use)
            if (under.isNotBlank()) {
                append(" under=").append(under)
                if (underPath.isNotBlank()) append(" ").append(underPath)
            }
            if (seen.isNotBlank()) append(" seen=").append(seen)
            firstText(ui)?.let { append(" text=").append(it) }
            append(". Operate this layer. A write underneath does not change the screen.")
        }
    }

    /**
     * What is on the glass right now. Text from the accessibility tree first;
     * if that is empty, the window / package tree the floating app window
     * already uses. Never a screenshot.
     */
    fun extract(app: Context?, deep: Boolean = false): JSONObject {
        val here = peek()
        if (override != null) {
            return extractFrom(here, tree = null, labels = emptyMap(), windowPkgs = emptyList())
        }
        val tree = runCatching {
            JSONObject(AccessibilityBridge.dumpUiTreeJson(includeFallbackScreenshot = false))
        }.getOrNull()
        val windowPkgs = runCatching { AppWindowService.openPackages() }.getOrDefault(emptyList())
        val labels = HashMap<String, String>()
        val pkgs = LinkedHashSet<String>()
        here.optString("package").takeIf { it.isNotBlank() }?.let { pkgs += it }
        windowPkgs.forEach { pkgs += it }
        packagesFrom(tree).forEach { pkgs += it }
        for (pkg in pkgs) {
            val name = appLabel(app, pkg)
            if (name.isNotBlank()) labels[pkg] = name
        }
        val out = extractFrom(here, tree, labels, windowPkgs)
        if (deep) attachDump(app, out)
        return out
    }

    /** Terminal window-focus only when they asked which interface. Not the turn-start line. */
    private fun attachDump(app: Context?, out: JSONObject) {
        if (out.optString("seen_via") == "text") return
        val dump = AvaShellTools.peekDump(app) ?: return
        out.put("dump", dump)
        val rows = out.optJSONArray("tree")
        if (rows == null || rows.length() == 0) out.put("seen_via", "dump")
    }

    internal fun extractFrom(
        here: JSONObject,
        tree: JSONObject?,
        labels: Map<String, String>,
        windowPkgs: List<String>,
    ): JSONObject {
        val out = JSONObject(here.toString())
        val texts = textsFrom(tree)
        val pkgs = LinkedHashSet<String>()
        out.optString("package").takeIf { it.isNotBlank() }?.let { pkgs += it }
        windowPkgs.forEach { if (it.isNotBlank()) pkgs += it }
        packagesFrom(tree).forEach { pkgs += it }
        val pkg = pkgs.firstOrNull().orEmpty()
        if (pkg.isNotBlank()) out.put("package", pkg)
        labels[pkg]?.takeIf { it.isNotBlank() }?.let { out.put("name", it) }
        if (texts.isNotEmpty()) {
            val arr = JSONArray()
            texts.take(TEXT_CAP).forEach { arr.put(it) }
            out.put("texts", arr)
            out.put("seen_via", "text")
        } else {
            val rows = windowsFrom(tree, labels, windowPkgs)
            if (rows.length() > 0) out.put("tree", rows)
            out.put("seen_via", if (rows.length() > 0) "tree" else "stack")
        }
        out.put(
            "hint",
            "Current interface is here_ui.top. Speak name/texts when present; " +
                "if texts are missing, speak tree[] (window titles and packages). No screenshot.",
        )
        return out
    }

    fun attach(body: JSONObject): JSONObject = attach(body, peek())

    fun attachResult(result: AvaToolCallback.Result): AvaToolCallback.Result {
        val here = peek()
        return if (result.ok) {
            when (val raw = result.result) {
                is JSONObject -> result.copy(result = attach(raw, here))
                else -> result
            }
        } else {
            val err = result.error ?: return result
            val details = err.details ?: JSONObject()
            attach(details, here)
            result.copy(error = err.copy(details = details))
        }
    }

    private fun attach(body: JSONObject, here: JSONObject): JSONObject {
        if (body.optJSONObject("here_ui")?.optString("seen_via").orEmpty().isNotBlank()) {
            return body
        }
        body.put("here_ui", here)
        val prior = body.optString("hint")
        val line = here.optString("hint")
        if (line.isNotBlank() && !prior.contains("here_ui")) {
            body.put("hint", if (prior.isBlank()) line else "$prior $line")
        }
        return body
    }

    internal fun build(snap: Snapshot): JSONObject {
        val covering = coveringOf(snap)
        val plates = platesOf(snap)
        val avaKind = avaKind(snap.avaRoute)
        val avaPath = snap.avaRoute?.trim().orEmpty().ifBlank {
            if (snap.avaResumed) Screen.HOME else ""
        }
        val topSpec = covering.firstOrNull() ?: plates.firstOrNull()
        val top = when {
            covering.isNotEmpty() -> OVERLAY
            snap.hassVisible -> HASS
            plates.isNotEmpty() -> OVERLAY
            snap.avaResumed -> AVA
            else -> SYSTEM
        }
        val kind = when (top) {
            OVERLAY -> topSpec?.kind.orEmpty()
            HASS -> "ha_browser"
            AVA -> avaKind
            else -> if (snap.systemPackage.isNullOrBlank()) "android" else "app"
        }
        val id = when (top) {
            OVERLAY -> topSpec?.id.orEmpty()
            HASS -> "browser_display"
            AVA -> avaPath
            else -> snap.systemPackage.orEmpty()
        }
        val path = when (top) {
            HASS -> snap.hassPath
            AVA -> avaPath
            else -> ""
        }
        val use = useOf(top, topSpec)
        val under = when {
            top == OVERLAY && snap.hassVisible -> HASS
            top == OVERLAY && snap.avaResumed -> AVA
            top == HASS && snap.avaResumed -> AVA
            else -> ""
        }
        val underPath = when (under) {
            HASS -> snap.hassPath
            AVA -> avaPath
            else -> ""
        }
        val open = JSONArray()
        for (spec in covering + plates) {
            open.put(layer(OVERLAY, spec.kind, spec.id, spec.name))
        }
        if (snap.hassVisible) {
            open.put(layer(HASS, "ha_browser", "browser_display", "Home Assistant overlay", snap.hassPath))
        }
        if (snap.avaResumed) {
            open.put(layer(AVA, avaKind, avaPath, if (avaKind == "settings") "Ava settings" else "Ava home", avaPath))
        }
        if (top == SYSTEM) {
            val row = layer(SYSTEM, kind, snap.systemPackage.orEmpty(), "Android")
            if (!snap.systemPackage.isNullOrBlank()) row.put("package", snap.systemPackage)
            open.put(row)
        }
        val out = JSONObject()
            .put("top", top)
            .put("kind", kind)
            .put("use", use)
        if (id.isNotBlank()) out.put("id", id)
        if (path.isNotBlank()) out.put("path", path)
        if (top == SYSTEM && !snap.systemPackage.isNullOrBlank()) {
            out.put("package", snap.systemPackage)
        }
        if (under.isNotBlank()) {
            out.put("under", under)
            if (underPath.isNotBlank()) out.put("under_path", underPath)
        }
        out.put("open", open)
        out.put(
            "hint",
            "Current interface is here_ui.top ($top). Operate use=$use. " +
                "A layer underneath cannot be changed until top is gone. No screenshot.",
        )
        return out
    }

    internal fun live(): Snapshot {
        val visible = LinkedHashSet<String>()
        for (spec in AvaOverlayReceipts.ALL) {
            if (spec.id == "browser_display") continue
            if (runCatching { AvaOverlayReceipts.visible(spec) }.getOrDefault(false)) {
                visible += spec.id
            }
        }
        val fg = foregroundPackage()
        val hass = runCatching { WebViewService.isBrowserOverlayVisible() }.getOrDefault(false)
        val hassPath = if (hass) {
            runCatching { WebViewService.hostUrl() }.getOrDefault("").ifBlank {
                runCatching { WebViewService.overlayHomeUrl() }.getOrDefault("")
            }
        } else {
            ""
        }
        return Snapshot(
            visibleIds = visible,
            vinylFull = runCatching { VinylCoverService.isFullPlayerBlockingScreensaver() }.getOrDefault(false),
            hassVisible = hass,
            hassPath = hassPath,
            avaResumed = avaInFront(fg),
            avaRoute = runCatching { MainNavigationCoordinator.currentRoute() }.getOrNull(),
            systemPackage = fg,
        )
    }

    private fun coveringOf(snap: Snapshot): List<AvaOverlayReceipts.Spec> {
        val out = ArrayList<AvaOverlayReceipts.Spec>()
        for (id in COVERING) {
            val seen = id in snap.visibleIds && (id != VINYL || snap.vinylFull)
            if (seen) AvaOverlayReceipts.spec(id)?.let { out += it }
        }
        return out
    }

    private fun platesOf(snap: Snapshot): List<AvaOverlayReceipts.Spec> {
        val out = ArrayList<AvaOverlayReceipts.Spec>()
        for (id in PLATES) {
            if (id in snap.visibleIds) AvaOverlayReceipts.spec(id)?.let { out += it }
        }
        if (VINYL in snap.visibleIds && !snap.vinylFull) {
            AvaOverlayReceipts.spec(VINYL)?.let { out += it }
        }
        return out
    }

    private fun avaKind(route: String?): String {
        return if (MainNavigationCoordinator.isSettingsLikeRoute(route)) "settings" else "home"
    }

    private fun useOf(top: String, spec: AvaOverlayReceipts.Spec?): String {
        return when (top) {
            HASS -> "ava_page"
            AVA -> "ava_self"
            SYSTEM -> "ava_phone"
            OVERLAY -> when (spec?.id) {
                AvaOverlayReceipts.RESEARCH -> "ava_web"
                AvaOverlayReceipts.APP_WINDOW -> "ava_shell"
                else -> "ava_self"
            }
            else -> "ava_self"
        }
    }

    private fun layer(
        surface: String,
        kind: String,
        id: String,
        name: String,
        path: String = "",
    ): JSONObject {
        val row = JSONObject()
            .put("surface", surface)
            .put("kind", kind)
            .put("visible", true)
        if (id.isNotBlank()) row.put("id", id)
        if (name.isNotBlank()) row.put("name", name)
        if (path.isNotBlank()) row.put("path", path)
        return row
    }

    private fun foregroundPackage(): String? {
        val svc = runCatching { AccessibilityBridge.getConnectedService() }.getOrNull() ?: return null
        val node = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return null
        return try {
            node.packageName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } finally {
            runCatching { node.recycle() }
        }
    }

    private fun avaInFront(fg: String?): Boolean {
        if (!runCatching { MainNavigationCoordinator.isActivityResumed() }.getOrDefault(false)) {
            return false
        }
        val ava = runCatching { AccessibilityBridge.getConnectedService()?.packageName }.getOrNull()
        return fg.isNullOrBlank() || ava.isNullOrBlank() || fg == ava
    }

    private fun textsFrom(tree: JSONObject?): List<String> {
        if (tree == null) return emptyList()
        val nodes = tree.optJSONArray("nodes") ?: return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            if (node.optBoolean("password")) continue
            if (node.has("visible") && !node.optBoolean("visible")) continue
            val text = node.optString("text").trim()
            val desc = node.optString("contentDescription").trim()
            for (raw in arrayOf(text, desc)) {
                if (raw.length < 2) continue
                if (!seen.add(raw)) continue
                out += raw
                if (out.size >= TEXT_CAP) return out
            }
        }
        return out
    }

    private fun packagesFrom(tree: JSONObject?): List<String> {
        if (tree == null) return emptyList()
        val nodes = tree.optJSONArray("nodes") ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until nodes.length()) {
            val pkg = nodes.optJSONObject(i)?.optString("packageName").orEmpty().trim()
            if (pkg.isNotBlank()) out += pkg
        }
        return out.toList()
    }

    private fun windowsFrom(
        tree: JSONObject?,
        labels: Map<String, String>,
        windowPkgs: List<String>,
    ): JSONArray {
        val rows = JSONArray()
        val windows = tree?.optJSONArray("windows")
        if (windows != null) {
            for (i in 0 until windows.length()) {
                if (rows.length() >= TREE_CAP) break
                val win = windows.optJSONObject(i) ?: continue
                val title = win.optString("title").trim()
                val row = JSONObject()
                    .put("title", title)
                    .put("focused", win.optBoolean("focused") || win.optBoolean("active"))
                if (win.has("layer")) row.put("layer", win.optInt("layer"))
                rows.put(row)
            }
        }
        for (pkg in windowPkgs) {
            if (rows.length() >= TREE_CAP) break
            if (pkg.isBlank()) continue
            val row = JSONObject().put("package", pkg)
            labels[pkg]?.let { row.put("name", it) }
            rows.put(row)
        }
        if (rows.length() == 0) {
            for (pkg in packagesFrom(tree)) {
                if (rows.length() >= TREE_CAP) break
                val row = JSONObject().put("package", pkg)
                labels[pkg]?.let { row.put("name", it) }
                rows.put(row)
            }
        }
        return rows
    }

    private fun firstText(ui: JSONObject): String? {
        val texts = ui.optJSONArray("texts")
        if (texts != null && texts.length() > 0) {
            return texts.optString(0).takeIf { it.isNotBlank() }
        }
        val tree = ui.optJSONArray("tree") ?: return null
        val first = tree.optJSONObject(0) ?: return null
        return first.optString("name").ifBlank { first.optString("title") }.ifBlank { first.optString("package") }
            .takeIf { it.isNotBlank() }
    }

    private fun appLabel(app: Context?, pkg: String): String {
        if (app == null || pkg.isBlank()) return ""
        return runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
        }.getOrDefault("")
    }

    internal fun overrideForTest(snap: Snapshot?) {
        override = snap
    }
}
