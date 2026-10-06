package com.example.ava.localllm.remote

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolArgumentCase
import com.example.ava.localllm.ToolDef
import com.example.ava.localllm.ToolParam
import com.example.ava.localllm.ToolParamType
import com.example.ava.services.WebViewService
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Claw FrontendInspect on this device's Home Assistant page
 * ([WebViewService]), not the research overlay. Read the screen, then tap.
 * House on/off stays on ha_*.
 */
object AvaPageTools {

    const val READ = "ava_page_read"
    const val ACT = "ava_page_act"
    const val PREFIX = "ava_page_"

    private val TARGET = setOf("idx", "selector", "text")
    private const val SLICE = 800
    private const val CACHE_MAX = 40_000
    private const val MORE_CAP = 2
    private const val INDEX_WAIT_MS = 500L
    private const val HIT_MAX = 3
    private const val SNIP = 60
    private val SPLIT = Regex("[\\s,，。；;！!？?、·~…—\\-_/\\\\()（）\\[\\]【】的了吗呢啊吧呀嘛]+")
    private val STOP = setOf(
        "一下", "看看", "帮我", "帮忙", "打开", "关闭", "怎么样", "怎么", "什么", "那个", "这个",
        "请", "现在", "当前", "页面", "屏幕", "告诉", "没有", "多少",
        "the", "a", "an", "is", "are", "was", "what", "how", "please", "show", "me", "my",
        "on", "off", "turn", "open", "close", "look", "at", "this", "that", "page", "screen",
        "can", "you", "tell", "about", "for", "and", "or",
    )
    private val cacheLock = Any()
    private val lastCaches = ArrayDeque<CacheEntry>()
    private data class CacheEntry(val source: String, val text: String)
    private var lastLongText = ""
    private var lastLongSource = ""
    private var lastExecCode = ""
    private var lastExecAt = 0L
    private var moreSteps = 0
    private val pageKeywords = LinkedHashSet<String>()
    private val extraKeywords = LinkedHashSet<String>()
    @Volatile private var lastUtterance = ""
    @Volatile private var lastReceipt: JSONObject? = null
    @Volatile private var agentReady = false
    private val pathLock = Any()
    private val trail = ArrayDeque<String>()
    private var homePath: String? = null
    private var herePath: String? = null
    private var leftHome = false
    private const val TRAIL_MAX = 24
    private const val PAGE_AGENT_VERSION = 8
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJob: Job? = null
    fun ready(app: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) return false
        return AvaPublishedEntities.browserDisplayOn() != null
    }

    fun sessionLine(): String =
        if (WebViewService.isBrowserOverlayVisible()) "Home Assistant page: open"
        else "Home Assistant page: closed"

    fun rememberUtterance(text: String) {
        lastUtterance = text.trim()
    }

    fun surface(ready: Boolean): HaToolSet {
        if (!ready) return HaToolSet(emptyList())
        return HaToolSet(
            listOf(
                ToolDef(
                    READ,
                    "Read the open Home Assistant page (browser display overlay), not the research page. Host matches spoken words to official HA paths and puts goto — navigate that path. Host also returns hits plus a slice when it can — speak answers from slice. To click this page, tap idx= from interactables on the same read (prefer a row whose entity/text matches). After tap the receipt says what landed; read again, idx is stale. origin/trail are the dashboard and each hop; the host restores origin when the turn ends — do not leave the user on Settings. If there is no slice, search query= a keyword, or scroll if can_scroll_down. House on/off without the page still uses ha_*.",
                    emptyList(),
                ),
                ToolDef(
                    ACT,
                    "Use the Home Assistant page after a read. Speak answers from slice. Click with tap idx= from interactables — slice is not a tap target. After tap, use the receipt then read again. navigate path= from goto (theme=/profile, settings=/config/dashboard). back pops one remembered path; restore returns to origin now. The host still restores origin when the turn ends — do not stay on the last page. Else search query= a keyword. If it is not there and more is below, scroll then ava_page_read. text/exec_js is one 800-character slice. Showing or hiding the overlay is ava_self target=browser_display.",
                    listOf(
                        ToolParam(
                            "action",
                            ToolParamType.Enum(listOf("tap", "type", "key", "scroll", "navigate", "back", "restore", "text", "search", "exec_js", "more")),
                            "tap / type / key / scroll / navigate / back / restore / text / search / exec_js / more",
                            required = true,
                        ),
                        ToolParam("idx", ToolParamType.Int(1, Int.MAX_VALUE), "interactable idx from the last snapshot", required = false),
                        ToolParam("selector", ToolParamType.Str, "CSS selector fallback", required = false),
                        ToolParam("text", ToolParamType.Str, "visible text fallback, or the find text for type", required = false),
                        ToolParam("value", ToolParamType.Str, "text to type (max 4000 characters)", required = false),
                        ToolParam("clear", ToolParamType.Bool, "clear the field before type. Default false", required = false),
                        ToolParam("path", ToolParamType.Str, "HA path for navigate, starting with /", required = false),
                        ToolParam(
                            "key",
                            ToolParamType.Enum(listOf("Enter", "Escape", "Tab", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Backspace", "Delete")),
                            "for key",
                            required = false,
                        ),
                        ToolParam("repeat", ToolParamType.Int(1, 50), "for key. Default 1", required = false),
                        ToolParam(
                            "direction",
                            ToolParamType.Enum(listOf("up", "down", "left", "right")),
                            "for scroll. Default down",
                            required = false,
                        ),
                        ToolParam(
                            "amount",
                            ToolParamType.Int(1, 20_000),
                            "for scroll: pixels. Default 300",
                            required = false,
                        ),
                        ToolParam("query", ToolParamType.Str, "for search: a keyword from the last keywords list (fast). Host returns the matching page slice.", required = false),
                        ToolParam(
                            "js_code",
                            ToolParamType.Str,
                            "for exec_js: an expression or IIFE the host injects (page CSP blocks eval). Return the value. Do not slice/substring. Usual walk: IIFE that walks document + shadow roots, collects unique textContent under 500 chars, joins with newline.",
                            required = false,
                        ),
                        ToolParam("force", ToolParamType.Bool, "for exec_js: run again even if the same js_code just ran. Default false", required = false),
                        ToolParam("offset", ToolParamType.Int(0, 200_000), "for text/exec_js/more: character offset of the next slice. Default 0", required = false),
                    ),
                    argumentCases = listOf(
                        ToolArgumentCase(action = "tap", fields = TARGET, atLeastOne = TARGET),
                        ToolArgumentCase(action = "type", fields = TARGET + setOf("value", "clear"), atLeastOne = TARGET),
                        ToolArgumentCase(action = "key", fields = TARGET + setOf("key", "repeat"), required = setOf("key")),
                        ToolArgumentCase(action = "scroll", fields = TARGET + setOf("direction", "amount")),
                        ToolArgumentCase(action = "navigate", fields = setOf("path"), required = setOf("path")),
                        ToolArgumentCase(action = "back", fields = emptySet()),
                        ToolArgumentCase(action = "restore", fields = emptySet()),
                        ToolArgumentCase(action = "text", fields = setOf("offset")),
                        ToolArgumentCase(action = "search", fields = setOf("query"), required = setOf("query")),
                        ToolArgumentCase(action = "exec_js", fields = setOf("js_code", "force", "offset"), required = setOf("js_code")),
                        ToolArgumentCase(action = "more", fields = setOf("offset"), required = setOf("offset")),
                    ),
                ),
            ),
        )
    }

    suspend fun execute(call: AvaToolCallback.Call, app: Context): AvaToolCallback.Result =
        when (call.name) {
            READ -> snapshot(app)
            ACT -> act(call.arguments, app)
            else -> AvaToolCallback.fail("not_found", "Tool not available: ${call.name}")
        }

    private suspend fun snapshot(app: Context): AvaToolCallback.Result {
        closed(app)?.let { return it }
        if (!WebViewService.awaitReady()) return AvaToolCallback.fail("navigation_failed", "page is not ready")
        ensureAgent()
        ensurePathSession(app)
        val page = takeSnapshot() ?: return AvaToolCallback.fail("tool_error", "page did not respond")
        if (!page.optBoolean("ok", true)) {
            return AvaToolCallback.fail(page.optString("error_code", "tool_error"), page.optString("error", "could not read this page"))
        }
        page.remove("ok")
        peekScroll()?.let {
            page.put("can_scroll_down", it.optBoolean("can_scroll_down"))
            page.put("can_scroll_up", it.optBoolean("can_scroll_up"))
        }
        peekDialogs()?.let {
            page.put("active_dialogs", it)
            rememberCache("dialog", JSONObject().put("dialogs", it))
        }
        rememberCache("snapshot", page)
        rememberKeywords(keywordsFromPage(page), fromPage = true)
        prefetchText()
        var hit = attachHits(page)
        if (!hit && lastUtterance.length >= 2) {
            awaitPrefetch()
            hit = attachHits(page)
        }
        compactForModel(page, hit)
        attachKeywords(page, if (hit) 16 else 24)
        lastReceipt?.let {
            page.put("last_tap", it)
            lastReceipt = null
        }
        attachTrail(page)
        val dialogs = page.optJSONArray("active_dialogs")
        val menus = page.optJSONArray("dropdowns")
        val settingsIntent = AvaSettingsPoints.intent(lastUtterance, WebViewService.isBrowserOverlayVisible())
        val jumped = if (dialogs == null && (menus == null || menus.length() == 0) &&
            settingsIntent != AvaSettingsPoints.Intent.AVA
        ) {
            val here = synchronized(pathLock) { herePath }
            HaFrontendPaths.attach(page, lastUtterance, pathOf(here ?: "") ?: here)
        } else {
            false
        }
        val n = page.optJSONArray("interactables")?.length() ?: 0
        val canDown = page.optBoolean("can_scroll_down")
        page.put("mode", "flip")
        if (!page.has("has_more")) page.put("has_more", canDown)
        if (dialogs != null) {
            val kind = dialogs.optJSONObject(0)?.optString("kind").orEmpty()
            page.put("hint", overlayHint(kind))
        } else if (menus != null && menus.length() > 0) {
            page.put(
                "hint",
                "Dropdowns are in dropdowns[]. Tap the select idx to open, then tap an option idx. Options are listed even when the menu is closed. Do not ignore the list.",
            )
        } else if (settingsIntent == AvaSettingsPoints.Intent.AVA) {
            page.put(
                "hint",
                "They meant this device's Ava settings, not the Home Assistant page. Call ava_self action=settings. The host returns that page as text — no screenshot.",
            )
            page.put(
                "next_action",
                JSONObject()
                    .put("tool", AvaSelfTools.NAME)
                    .put("arguments", JSONObject().put("action", "settings")),
            )
        } else if (jumped) {
            page.put(
                "hint",
                "Host matched an official Home Assistant path — navigate path= from goto, then read. Theme and appearance are /profile. Settings is /config/dashboard. Host restores origin when the turn ends.",
            )
            HaFrontendPaths.nextNavigate(page)?.let { page.put("next_action", it) }
        } else if (hit) {
            page.put(
                "hint",
                "Host matched spoken words in slice — speak answers from it. To click this page, tap idx= from interactables (prefer a matching entity). After tap, read again; the receipt says what landed. Host restores origin when the turn ends. House on/off without the page uses ha_*.",
            )
            if (canDown && n < 3) page.put("next_action", nextScroll())
        } else {
            page.put(
                "hint",
                moreHint(canDown) + " Fast path: search query= a keyword from keywords. House on/off without the page uses ha_*.",
            )
            if (canDown && n < 3) page.put("next_action", nextScroll())
        }
        return AvaToolCallback.ok(page)
    }

    private suspend fun takeSnapshot(): JSONObject? {
        val raw = WebViewService.evaluateJavascript(PAGE_SNAPSHOT_JS) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private suspend fun act(args: JSONObject, app: Context): AvaToolCallback.Result {
        return when (args.optString("action").trim()) {
            "tap", "type", "key" -> interact(args.optString("action"), args, app)
            "scroll" -> scroll(args, app)
            "navigate" -> navigate(args, app)
            "back" -> goBack(app)
            "restore" -> restoreNow(app)
            "text" -> visibleText(args, app)
            "search" -> search(args)
            "exec_js" -> execJs(args, app)
            "more" -> more(args)
            else -> AvaToolCallback.fail("invalid_request", "unsupported page action")
        }
    }

    private suspend fun navigate(args: JSONObject, app: Context): AvaToolCallback.Result {
        val path = HaFrontendPaths.resolve(args.optString("path"))
            ?: return AvaToolCallback.fail("invalid_request", "path must start with /")
        closed(app, openIfNeeded = true)?.let { return it }
        ensurePathSession(app)
        if (!gotoPath(path)) {
            return AvaToolCallback.fail("navigation_failed", "could not change page")
        }
        delay(1_500)
        peekHere()?.let { noteArrival(it) } ?: noteArrival(path)
        resetIndex()
        return AvaToolCallback.ok(
            attachTrail(
                JSONObject().put("action", "navigate").put("path", path).put("next_action", nextRead()),
            ),
            status = "accepted",
        )
    }

    private suspend fun scroll(args: JSONObject, app: Context): AvaToolCallback.Result {
        closed(app)?.let { return it }
        val direction = args.optString("direction").trim().ifEmpty { "down" }
        val amount = if (args.has("amount") && args.opt("amount") != JSONObject.NULL) args.optInt("amount") else 300
        val dx = when (direction) { "right" -> amount; "left" -> -amount; else -> 0 }
        val dy = when (direction) { "down" -> amount; "up" -> -amount; else -> 0 }
        val idx = if (args.has("idx") && args.opt("idx") != JSONObject.NULL) args.optInt("idx") else 0
        val cmd = JSONObject()
            .put("action", "scroll")
            .put("dx", dx)
            .put("dy", dy)
            .put("direction", direction)
            .put("amount", amount)
            .put("idx", idx)
            .put("selector", args.optString("selector"))
            .put("text", args.optString("text"))
        val raw = agentRun(cmd)
            ?: WebViewService.evaluateJavascript(
                "(" + SCROLL_JS + ")(" + dx + "," + dy + "," +
                    JSONObject.quote(direction) + "," + amount + "," +
                    idx + "," + JSONObject.quote(args.optString("selector")) + "," +
                    JSONObject.quote(args.optString("text")) + ")",
            ) ?: return AvaToolCallback.fail("tool_error", "could not scroll this page")
        val moved = runCatching { JSONObject(raw) }.getOrNull()
            ?: return AvaToolCallback.fail("tool_error", "could not scroll this page")
        if (!moved.optBoolean("ok", true)) {
            return AvaToolCallback.fail(moved.optString("error_code", "tool_error"), moved.optString("error", "could not scroll this page"))
        }
        moved.remove("ok")
        peekScroll()?.let { extra ->
            moved.put("can_scroll_down", extra.optBoolean("can_scroll_down"))
            moved.put("can_scroll_up", extra.optBoolean("can_scroll_up"))
        }
        moved.put("mode", "flip")
        moved.put("next_action", nextRead())
        moved.put("hint", "Page flipped. ava_page_read this screen — keywords come with it. Then search query= the word you need.")
        return AvaToolCallback.ok(moved.put("action", "scroll"), status = "applied")
    }

    private suspend fun interact(action: String, args: JSONObject, app: Context): AvaToolCallback.Result {
        closed(app)?.let { return it }
        if (!WebViewService.awaitReady()) return AvaToolCallback.fail("navigation_failed", "page is not ready")
        ensurePathSession(app)
        val value = args.optString("value")
        if (value.length > 4000) return AvaToolCallback.fail("invalid_request", "value exceeds 4000 characters")
        val idx = if (args.has("idx") && args.opt("idx") != JSONObject.NULL) args.optInt("idx") else 0
        val repeat = if (args.has("repeat") && args.opt("repeat") != JSONObject.NULL) args.optInt("repeat").coerceIn(1, 50) else 1
        val cmd = JSONObject()
            .put("action", action)
            .put("idx", idx)
            .put("selector", args.optString("selector"))
            .put("text", args.optString("text"))
            .put("value", value)
            .put("clear", args.optBoolean("clear"))
            .put("key", args.optString("key"))
            .put("repeat", repeat)
        val raw = agentRun(cmd)
            ?: WebViewService.evaluateJavascript(
                "(" + INTERACT_JS + ")(" +
                    JSONObject.quote(action) + "," +
                    idx + "," +
                    JSONObject.quote(args.optString("selector")) + "," +
                    JSONObject.quote(args.optString("text")) + "," +
                    JSONObject.quote(value) + "," +
                    args.optBoolean("clear") + "," +
                    JSONObject.quote(args.optString("key")) + "," +
                    repeat + ")",
            ) ?: return AvaToolCallback.fail("tool_error", "page did not respond")
        val result = runCatching { JSONObject(raw) }.getOrNull()
            ?: return AvaToolCallback.fail("tool_error", "invalid page response")
        if (!result.optBoolean("ok")) {
            return AvaToolCallback.fail(result.optString("error_code", "stale_ref"), result.optString("error"))
        }
        result.remove("ok")
        if (action == "tap") {
            delay(500)
            collectTapReceipt(result)
            peekHere()?.let { noteArrival(it) }
            resetIndex()
        }
        attachTrail(result)
        result.put("requires_observation", true).put("next_action", nextRead())
        return AvaToolCallback.ok(result, status = if (action == "type") "applied" else "accepted")
    }

    private suspend fun collectTapReceipt(result: JSONObject) {
        val overlays = peekOverlaysReady()
        overlays?.let {
            result.put("opened_overlays", it)
            result.put("opened_dialog", it)
            result.put("active_dialogs", it)
            val kind = it.optJSONObject(0)?.optString("kind").orEmpty()
            result.put(
                "dialog_hint",
                overlayHint(kind),
            )
            rememberCache("dialog", JSONObject().put("dialogs", it).put("overlays", it))
        }
        val ping = agentRun(JSONObject().put("action", "ping"))
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (ping?.optBoolean("dialogOpened") == true || ping?.optBoolean("overlayOpened") == true) {
            result.put("dialog_opened", true)
            result.put("overlay_opened", true)
        }
        ping?.optJSONObject("lastOverlay")?.let { result.put("last_overlay", it) }
        if (overlays == null && result.optJSONObject("last_overlay") != null) {
            result.put(
                "dialog_hint",
                overlayHint(result.optJSONObject("last_overlay")?.optString("kind").orEmpty()) +
                    " Host saw the overlay event; read again for the full snapshot.",
            )
        }
        var eid = result.optString("entity_id")
        if (eid.isBlank()) {
            eid = overlays?.optJSONObject(0)?.optString("entity").orEmpty()
                .ifBlank { result.optJSONObject("last_overlay")?.optString("entity").orEmpty() }
            if (eid.isNotBlank()) result.put("entity_id", eid)
        }
        if (eid.isNotBlank()) {
            val raw = agentRun(JSONObject().put("action", "state").put("entity", eid))
                ?: peekEntityState(eid)
            val body = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (body?.optBoolean("ok") == true) {
                result.put("state_after", body.optString("state"))
                val name = body.optString("name")
                if (name.isNotBlank()) result.put("name", name)
            }
        }
        result.put("receipt", true)
        if (!result.has("opened_dialog") && !result.has("last_overlay")) {
            result.put(
                "hint",
                "Tap landed (" + result.optString("kind").ifBlank { result.optString("target") } +
                    "). Read again — idx is stale. Use state_after if present. House on/off without the page uses ha_*.",
            )
        }
        lastReceipt = JSONObject(result.toString())
    }

    private fun overlayHint(kind: String): String = when (kind) {
        "more_info" ->
            "Entity panel is open (more_info). Look at opened_overlays for entity/state/controls, then tap idx= inside it. Do not ignore the panel."
        "shortcut" ->
            "Shortcut panel is open. Look at opened_overlays, then tap idx= or type. Do not ignore the window."
        "sheet" ->
            "A bottom sheet is open (phone entity panel). Look at opened_overlays, then tap idx= inside it."
        "menu" ->
            "A dropdown menu is open. Look at opened_overlays / dropdowns[], then tap an option idx."
        "toast" ->
            "A toast appeared. Read the message in opened_overlays before doing more."
        "quick_bar" ->
            "Quick bar is open. Type or tap a row from opened_overlays."
        "drawer" ->
            "Notification drawer is open. Look at opened_overlays, then tap a row."
        else ->
            "A window is open. Look at opened_overlays / active_dialogs, then tap idx= from its rows. Do not ignore the window."
    }

    private suspend fun peekEntityState(entityId: String): String? {
        if (entityId.isEmpty() || entityId.indexOf('.') < 0) return null
        return WebViewService.evaluateJavascript(
            "(function(){var ha=document.querySelector('home-assistant');var st=ha&&ha.hass&&ha.hass.states&&ha.hass.states[" +
                JSONObject.quote(entityId) +
                "];if(!st)return JSON.stringify({ok:false});return JSON.stringify({ok:true,entity:" +
                JSONObject.quote(entityId) +
                ",state:String(st.state),name:(st.attributes&&st.attributes.friendly_name)||''});})();",
        )
    }

    private suspend fun ensurePathSession(app: Context) {
        val overlay = normalizeHere(WebViewService.overlayHomeUrl())
        val remote = normalizeHere(remoteUrl(app))
        val start = when {
            overlay != null && !isForeignHere(overlay) -> overlay
            remote != null && !isForeignHere(remote) -> remote
            else -> overlay
        }
        if (start != null && synchronized(pathLock) { homePath == null }) {
            noteArrival(start)
        }
        peekHere()?.let { noteArrival(it) }
    }

    private fun isForeignHere(url: String): Boolean {
        val host = originOf(url).lowercase()
        return host.contains("home-assistant.io")
    }

    private suspend fun peekHere(): String? {
        val raw = agentRun(JSONObject().put("action", "path"))
            ?: WebViewService.evaluateJavascript(PATH_JS)
        val body = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val href = body?.optString("href").orEmpty()
        val path = body?.optString("path").orEmpty()
        return normalizeHere(href.ifBlank { path })
            ?: normalizeHere(WebViewService.hostUrl())
    }

    private suspend fun gotoPath(path: String): Boolean {
        val raw = agentRun(JSONObject().put("action", "navigate").put("path", path))
            ?: WebViewService.evaluateJavascript("(" + GOTO_JS + ")(" + JSONObject.quote(path) + ")")
            ?: return false
        val result = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        return result.optBoolean("ok")
    }

    private suspend fun gotoHere(app: Context, target: String): Boolean {
        val here = normalizeHere(target) ?: return false
        if (here.startsWith("http://") || here.startsWith("https://")) {
            val cur = peekHere()
            val path = pathOf(here)
            if (cur != null && path != null && originOf(cur) == originOf(here)) {
                if (gotoPath(path)) return true
            }
            return WebViewService.loadAndAwait(app, here) != null
        }
        return gotoPath(here)
    }

    private fun originOf(url: String): String = runCatching {
        val uri = java.net.URI(url)
        if (uri.scheme.isNullOrBlank() || uri.authority.isNullOrBlank()) ""
        else uri.scheme + "://" + uri.authority
    }.getOrDefault("")

    private fun pathOf(url: String): String? {
        if (url.startsWith("/")) return normalizePath(url)
        return runCatching {
            val uri = java.net.URI(url)
            val path = (uri.path ?: "/").ifBlank { "/" }
            val q = uri.query
            normalizePath(if (q.isNullOrBlank()) path else "$path?$q")
        }.getOrNull()
    }

    private fun noteArrival(path: String, push: Boolean = true) {
        val next = normalizeHere(path.substringBefore('#')) ?: return
        synchronized(pathLock) {
            if (homePath == null) {
                homePath = next
                herePath = next
                leftHome = false
                return
            }
            if (next == herePath) return
            if (push) {
                herePath?.let {
                    trail.addLast(it)
                    while (trail.size > TRAIL_MAX) trail.removeFirst()
                }
            }
            herePath = next
            leftHome = next != homePath
        }
    }

    private fun popTrail(): String? = synchronized(pathLock) {
        val prev = trail.removeLastOrNull() ?: return null
        herePath = prev
        leftHome = homePath != null && prev != homePath
        prev
    }

    private fun markRestored(home: String) = synchronized(pathLock) {
        trail.clear()
        homePath = home
        herePath = home
        leftHome = false
    }

    private fun clearTrailLocked() {
        trail.clear()
        homePath = null
        herePath = null
        leftHome = false
    }

    private fun attachTrail(body: JSONObject): JSONObject {
        synchronized(pathLock) {
            homePath?.let { body.put("origin", it) }
            herePath?.let { here ->
                body.put("href", here)
                body.put("path", pathOf(here) ?: here)
            }
            if (trail.isNotEmpty()) {
                val rows = JSONArray()
                trail.forEach { rows.put(it) }
                body.put("trail", rows)
            }
            if (leftHome) {
                body.put("will_restore", true)
                body.put(
                    "restore_hint",
                    "Host restores origin when this turn ends. back pops one hop, including other sites. restore returns now. Do not stop on this page.",
                )
            }
        }
        return body
    }

    /**
     * Claw: backend queues JS, ha_crack.js (already in the page) runs it and
     * posts the result. Ava cannot load ha_crack, so the host installs
     * [PAGE_AGENT_JS] once, then only calls `window.__avaPage.run(...)`.
     */
    private suspend fun ensureAgent(): Boolean {
        if (agentReady) {
            val ping = WebViewService.evaluateJavascript(
                "window.__avaPage&&window.__avaPage.v===$PAGE_AGENT_VERSION?JSON.stringify({ok:true}):JSON.stringify({ok:false})",
            )
            if (ping?.let { runCatching { JSONObject(it) }.getOrNull() }?.optBoolean("ok") == true) return true
            agentReady = false
        }
        val raw = WebViewService.evaluateJavascript(PAGE_AGENT_JS, timeoutMs = 15_000L)
        val ok = raw?.let { runCatching { JSONObject(it) }.getOrNull() }?.optBoolean("ok") == true
        agentReady = ok
        return ok
    }

    private suspend fun agentRun(cmd: JSONObject, timeoutMs: Long = 5_000L): String? {
        if (!ensureAgent()) return null
        return WebViewService.evaluateJavascript(
            "(window.__avaPage&&window.__avaPage.run)?window.__avaPage.run(" + cmd.toString() +
                "):JSON.stringify({ok:false,error_code:'tool_error',error:'no page agent'})",
            timeoutMs = timeoutMs,
        )
    }

    private suspend fun peekDialogs(): JSONArray? {
        val raw = agentRun(JSONObject().put("action", "dialogs"))
            ?: WebViewService.evaluateJavascript(DIALOG_JS)
            ?: return null
        val body = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val rows = body.optJSONArray("dialogs") ?: body.optJSONArray("overlays") ?: return null
        return if (rows.length() == 0) null else rows
    }

    private suspend fun peekOverlaysReady(): JSONArray? {
        repeat(6) {
            peekDialogs()?.let { return it }
            delay(200)
        }
        return peekDialogs()
    }

    private suspend fun goBack(app: Context): AvaToolCallback.Result {
        closed(app)?.let { return it }
        val prev = popTrail()
        if (prev != null) {
            if (!gotoHere(app, prev)) {
                return AvaToolCallback.fail("navigation_failed", "could not open the previous path")
            }
            delay(400)
            noteArrival(prev, push = false)
        } else {
            WebViewService.navigateAndAwait("back")
                ?: return AvaToolCallback.fail("navigation_failed", "No history, or navigation did not finish")
            delay(400)
            peekHere()?.let { noteArrival(it, push = false) }
        }
        resetIndex()
        return AvaToolCallback.ok(
            attachTrail(JSONObject().put("action", "back").put("next_action", nextRead())),
            status = "accepted",
        )
    }

    private suspend fun restoreNow(app: Context): AvaToolCallback.Result {
        closed(app)?.let { return it }
        val home = synchronized(pathLock) { homePath }
            ?: peekHere()
            ?: return AvaToolCallback.fail("not_found", "no remembered dashboard path")
        if (!gotoHere(app, home)) {
            return AvaToolCallback.fail("navigation_failed", "could not restore the dashboard")
        }
        delay(400)
        markRestored(home)
        resetIndex()
        return AvaToolCallback.ok(
            attachTrail(JSONObject().put("action", "restore").put("path", home).put("next_action", nextRead())),
            status = "accepted",
        )
    }

    suspend fun onTurnFinished(app: Context) {
        val home = synchronized(pathLock) {
            if (!leftHome) {
                clearTrailLocked()
                return
            }
            homePath
        } ?: return
        if (!WebViewService.isBrowserOverlayVisible()) {
            synchronized(pathLock) { clearTrailLocked() }
            return
        }
        runCatching { gotoHere(app, home) }
        delay(400)
        synchronized(pathLock) { clearTrailLocked() }
    }

    private suspend fun visibleText(args: JSONObject, app: Context): AvaToolCallback.Result {
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        awaitPrefetch()
        val reuse = synchronized(cacheLock) {
            lastLongText.isNotBlank() && (offset > 0 || lastLongSource == "prefetch" || lastLongSource == "text")
        }
        if (reuse) {
            return AvaToolCallback.ok(attachKeywords(sliceOut("text", offset)))
        }
        closed(app)?.let { return it }
        val raw = WebViewService.evaluateJavascript(TEXT_JS, timeoutMs = 15_000L)
            ?: return AvaToolCallback.fail("tool_error", "page did not respond")
        val clipped = if (raw.length > CACHE_MAX) raw.take(CACHE_MAX) else raw
        val body = runCatching { JSONObject(clipped) }.getOrNull()
        rememberLong("text", body?.optString("text") ?: clipped)
        rememberKeywords(keywordsFromText(body?.optString("text") ?: clipped))
        return AvaToolCallback.ok(attachKeywords(sliceOut("text", offset)).put("count", body?.optInt("count") ?: 0))
    }

    private fun more(args: JSONObject): AvaToolCallback.Result {
        if (synchronized(cacheLock) { lastLongText.isBlank() }) {
            return AvaToolCallback.fail("not_found", "read or exec_js first")
        }
        val steps = synchronized(cacheLock) { moreSteps }
        if (steps >= MORE_CAP) {
            return AvaToolCallback.ok(
                attachKeywords(
                    JSONObject()
                        .put("action", "more")
                        .put("paused", true)
                        .put("mode", "flip")
                        .put("has_more", true)
                        .put("next_action", nextScroll())
                        .put("hint", moreHint(true) + " Fast path: search query= a keyword from keywords. Do not keep calling more."),
                ),
            )
        }
        synchronized(cacheLock) { moreSteps = steps + 1 }
        return AvaToolCallback.ok(attachKeywords(sliceOut("more", args.optInt("offset", 0).coerceAtLeast(0))))
    }

    private suspend fun execJs(args: JSONObject, app: Context): AvaToolCallback.Result {
        closed(app)?.let { return it }
        val code = args.optString("js_code").trim()
        if (code.isEmpty()) return AvaToolCallback.fail("invalid_request", "js_code is required")
        if (code.length > 16_000) return AvaToolCallback.fail("invalid_request", "js_code exceeds 16000 characters")
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val force = args.optBoolean("force")
        val now = System.currentTimeMillis()
        val reuse = synchronized(cacheLock) {
            !force && code == lastExecCode && lastLongText.isNotBlank() && now - lastExecAt < 10_000L
        }
        if (reuse) return AvaToolCallback.ok(attachKeywords(sliceOut("exec_js", offset).put("_cached", true)))
        val raw = runExecJs(code)
            ?: return AvaToolCallback.fail("tool_error", "page did not respond")
        val clipped = if (raw.length > CACHE_MAX) raw.take(CACHE_MAX) else raw
        val body = runCatching { JSONObject(clipped) }.getOrNull()
        if (body != null && !body.optBoolean("ok", true)) {
            return AvaToolCallback.fail(body.optString("error_code", "tool_error"), body.optString("error", "js_code failed"))
        }
        val stored = if (body != null) resultText(body.opt("result")) else clipped
        rememberLong("exec_js", stored)
        rememberKeywords(keywordsFromText(stored))
        synchronized(cacheLock) {
            lastExecCode = code
            lastExecAt = now
        }
        return AvaToolCallback.ok(attachKeywords(sliceOut("exec_js", offset)))
    }

    private suspend fun runExecJs(code: String): String? {
        val body = stripJsFences(code)
        val first = WebViewService.evaluateJavascript(wrapExecJs(body, asExpression = true), timeoutMs = 15_000L)
        val parsed = first?.let { runCatching { JSONObject(it) }.getOrNull() }
        val syntax = parsed?.optString("error").orEmpty().contains("SyntaxError", ignoreCase = true)
        if (first != null && (parsed == null || parsed.optBoolean("ok", true)) && !syntax) return first
        return WebViewService.evaluateJavascript(wrapExecJs(body, asExpression = false), timeoutMs = 15_000L)
            ?: first
    }

    internal fun stripJsFences(code: String): String =
        code.trim()
            .replace(Regex("^```(?:javascript|js)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()

    /**
     * Host injects [code] as the WebView expression. Do not eval/Function inside
     * the page — Home Assistant CSP blocks both, which looks like "can see, cannot control".
     */
    internal fun wrapExecJs(code: String, asExpression: Boolean = true): String {
        val assign = if (asExpression) "__r=(\n$code\n);" else "__r=(function(){\n$code\n})();"
        return "(function(){try{var __r;" + assign +
            "if(typeof __r==='function') __r=__r();" +
            "if(__r&&__r.nodeType) __r={tag:String(__r.tagName||'').toLowerCase(),text:String(__r.innerText||__r.textContent||''),html:String(__r.outerHTML||'')};" +
            "try{return JSON.stringify({ok:true,result:(__r===undefined?null:__r)});}catch(e2){return JSON.stringify({ok:true,result:String(__r)});}" +
            "}catch(e){return JSON.stringify({ok:false,error_code:'tool_error',error:String(e&&e.message||e)});}})();"
    }

    private fun resultText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONObject -> value.toString()
        is JSONArray -> value.toString()
        else -> value.toString()
    }

    private fun rememberLong(source: String, text: String) = synchronized(cacheLock) {
        lastLongText = if (text.length > CACHE_MAX) text.take(CACHE_MAX) else text
        lastLongSource = source
        lastCaches.addFirst(CacheEntry(source, lastLongText))
        while (lastCaches.size > 10) lastCaches.removeLast()
        moreSteps = 0
    }

    private fun sliceOut(action: String, offset: Int): JSONObject {
        val full = synchronized(cacheLock) { lastLongText }
        val start = offset.coerceAtLeast(0)
        if (start >= full.length) {
            return JSONObject().put("action", action).put("text", "").put("offset", start)
                .put("end", true).put("truncated", false).put("has_more", false)
                .put("total_chars", full.length)
                .put("mode", "cache")
                .put("page", pageOf(start))
                .put("hint", moreHint(true) + " Fast path: search query= a keyword from keywords.")
        }
        val slice = full.drop(start).take(SLICE)
        val next = start + slice.length
        val more = next < full.length
        return JSONObject()
            .put("action", action)
            .put("mode", "cache")
            .put("text", slice)
            .put("offset", start)
            .put("page", pageOf(start))
            .put("end", !more)
            .put("truncated", more)
            .put("has_more", more)
            .put("total_chars", full.length)
            .put("next", if (more) next else JSONObject.NULL)
            .put("hint", moreHint(more) + " Fast path: search query= a keyword from keywords.")
    }

    private suspend fun search(args: JSONObject): AvaToolCallback.Result {
        val query = args.optString("query").trim()
        if (query.length < 2) return AvaToolCallback.fail("invalid_request", "query is too short")
        awaitPrefetch()
        val entries = synchronized(cacheLock) { lastCaches.toList() }
        if (entries.isEmpty()) return AvaToolCallback.fail("not_found", "read the page first")
        val matches = findHits(query)
        val hit = matches.length() > 0
        val body = JSONObject()
            .put("action", "search")
            .put("query", query)
            .put("matches", matches)
            .put("count", matches.length())
            .put(
                "hint",
                if (hit) "Speak from slice. hits list other pages if this is the wrong one. Do not walk the cache."
                else moreHint(true) + " That keyword is not in this capture. search another keyword from keywords, or scroll then ava_page_read.",
            )
        if (hit) attachSlice(body, matches) else body.put("has_more", true)
        return AvaToolCallback.ok(attachKeywords(body))
    }

    private fun rememberCache(source: String, page: JSONObject) = synchronized(cacheLock) {
        val text = flattenForIndex(page)
        if (text.isBlank()) return
        lastCaches.addFirst(CacheEntry(source, text))
        while (lastCaches.size > 10) lastCaches.removeLast()
    }

    private fun flattenForIndex(value: Any?, out: StringBuilder = StringBuilder()): String {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) flattenForIndex(value.opt(keys.next()), out)
            }
            is JSONArray -> {
                for (i in 0 until value.length()) flattenForIndex(value.opt(i), out)
            }
            is String -> {
                val t = value.trim()
                if (t.length >= 2) {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(t)
                }
            }
        }
        return out.toString()
    }

    private suspend fun closed(app: Context, openIfNeeded: Boolean = false): AvaToolCallback.Result? {
        if (WebViewService.isBrowserOverlayVisible() && WebViewService.hostUrl().isNotBlank()) return null
        if (!openIfNeeded) {
            return AvaToolCallback.fail(
                "no_session",
                "Browser display is closed. Open it with ava_self action=set target=browser_display on=true.",
            )
        }
        val url = WebViewService.hostUrl().ifBlank { remoteUrl(app) }
        if (url.isBlank()) {
            return AvaToolCallback.fail(
                "no_session",
                "Browser display has no page. Open it with ava_self action=set target=browser_display on=true.",
            )
        }
        WebViewService.loadAndAwait(app, url) ?: return AvaToolCallback.fail("navigation_failed", "page did not open")
        delay(400)
        return null
    }

    private fun remoteUrl(app: Context): String = runCatching {
        VoiceSatelliteSettingsStore(app.voiceSatelliteSettingsStore).getCached().haRemoteUrl
    }.getOrDefault("")

    private fun nextRead(): JSONObject = JSONObject().put("tool", READ).put("arguments", JSONObject())

    private fun nextScroll(): JSONObject =
        JSONObject().put("tool", ACT).put("arguments", JSONObject().put("action", "scroll").put("direction", "down"))

    private fun pageOf(offset: Int): Int = offset / SLICE + 1

    private fun moreHint(more: Boolean): String =
        if (more) "There is more after this. If it is not here, search query= a keyword from keywords, or scroll then ava_page_read."
        else "If this is not enough, search query= a keyword from keywords, or scroll then ava_page_read."

    private fun rememberKeywords(extra: Collection<String>, fromPage: Boolean = false) = synchronized(cacheLock) {
        if (fromPage) {
            pageKeywords.clear()
            pageKeywords.addAll(extra)
            while (pageKeywords.size > 24) pageKeywords.remove(pageKeywords.first())
        } else {
            extraKeywords.addAll(extra)
            while (extraKeywords.size > 24) extraKeywords.remove(extraKeywords.first())
        }
    }

    private fun keywordList(): List<String> = synchronized(cacheLock) {
        (pageKeywords + extraKeywords).toList()
    }

    private fun attachKeywords(body: JSONObject, cap: Int = 24): JSONObject {
        val rows = JSONArray()
        for (word in keywordList()) {
            if (rows.length() >= cap) break
            rows.put(word)
        }
        if (rows.length() > 0) body.put("keywords", rows)
        return body
    }

    private fun resetIndex() = synchronized(cacheLock) {
        lastCaches.clear()
        pageKeywords.clear()
        extraKeywords.clear()
        lastLongText = ""
        lastLongSource = ""
        moreSteps = 0
    }

    private suspend fun awaitPrefetch() {
        val job = prefetchJob ?: return
        if (!job.isActive) return
        withTimeoutOrNull(INDEX_WAIT_MS) { job.join() }
    }

    private fun attachHits(body: JSONObject, query: String = lastUtterance): Boolean {
        if (query.length < 2) return false
        val hits = findHits(query)
        if (hits.length() == 0) return false
        body.put("hits", hits)
        attachSlice(body, hits)
        return true
    }

    private fun attachSlice(body: JSONObject, hits: JSONArray) {
        val first = hits.optJSONObject(0) ?: return
        val source = first.optString("source")
        val at = first.optInt("offset")
        val entry = synchronized(cacheLock) { lastCaches.firstOrNull { it.source == source } } ?: return
        val q = first.optString("query")
        if (at < 0 || q.isEmpty() || at + q.length > entry.text.length) return
        if (!entry.text.regionMatches(at, q, 0, q.length, ignoreCase = true)) return
        val pageStart = (at / SLICE) * SLICE
        val slice = entry.text.drop(pageStart).take(SLICE)
        if (slice.isBlank()) return
        val next = pageStart + slice.length
        val more = next < entry.text.length
        body.put("slice", slice)
        body.put("page", pageOf(at))
        body.put("offset", pageStart)
        body.put("has_more", more)
        if (more) body.put("next", next)
    }

    private fun findHits(query: String, max: Int = HIT_MAX): JSONArray {
        val matches = JSONArray()
        val seen = HashSet<String>()
        val entries = synchronized(cacheLock) { lastCaches.toList() }
        for (q in dispatchQueries(query)) {
            if (matches.length() >= max) break
            val key = q.lowercase()
            for (entry in entries) {
                if (matches.length() >= max) break
                val hay = entry.text
                val lower = hay.lowercase()
                var start = 0
                var found = 0
                while (matches.length() < max && found < 2) {
                    val at = lower.indexOf(key, start)
                    if (at < 0) break
                    val id = entry.source + ":" + at
                    if (seen.add(id)) {
                        val a = (at - SNIP).coerceAtLeast(0)
                        val b = (at + q.length + SNIP).coerceAtMost(hay.length)
                        matches.put(
                            JSONObject()
                                .put("source", entry.source)
                                .put("page", pageOf(at))
                                .put("offset", at)
                                .put("query", q)
                                .put("text", hay.substring(a, b)),
                        )
                        found++
                    }
                    start = at + q.length.coerceAtLeast(1)
                }
            }
        }
        return matches
    }

    internal fun dispatchQueries(utterance: String): List<String> {
        val raw = utterance.trim()
        if (raw.length < 2) return emptyList()
        val out = LinkedHashSet<String>()
        var cleaned = raw
        for (stop in STOP) {
            if (stop.length >= 2) cleaned = cleaned.replace(stop, " ", ignoreCase = true)
        }
        val tokens = LinkedHashSet<String>()
        for (part in cleaned.split(SPLIT)) {
            val t = part.trim()
            if (t.length in 2..24 && t.lowercase() !in STOP) tokens.add(t)
        }
        for (word in keywordList()) {
            if (word.length < 2) continue
            if (raw.contains(word, ignoreCase = true) || tokens.any { word.contains(it, ignoreCase = true) }) {
                out.add(word)
            }
        }
        out.addAll(tokens)
        if (out.isEmpty() && raw.length in 2..40) out.add(raw)
        return out.sortedByDescending { it.length }
    }

    private fun compactForModel(page: JSONObject, hasHits: Boolean) {
        val queries = dispatchQueries(lastUtterance)
        compactArray(page, "interactables", if (hasHits) 32 else 40) { row ->
            JSONObject().apply {
                put("idx", row.optInt("idx"))
                put("action", row.optString("action"))
                val text = row.optString("text")
                if (text.isNotBlank()) put("text", text.take(40))
                val ctx = row.optString("in")
                if (ctx.isNotBlank()) put("in", ctx.take(40))
                val eid = row.optString("entity")
                if (eid.isNotBlank()) put("entity", eid)
                val pos = row.optString("pos")
                if (pos.isNotBlank()) put("pos", pos)
            }
        }
        compactDropdowns(page)
        if (hasHits) {
            prioritizeMatching(page, "interactables", queries, 32)
            prioritizeDialogInteractables(page)
            page.remove("nav")
            keepMatchingOrDrop(page, "cards", queries, 8)
            keepMatchingOrDrop(page, "entities", queries, 8)
            return
        }
        prioritizeDialogInteractables(page)
        compactArray(page, "nav", 12) { row ->
            JSONObject()
                .put("title", row.optString("title"))
                .put("path", row.optString("path"))
        }
        compactArray(page, "cards", 16) { row ->
            JSONObject()
                .put("title", row.optString("title"))
                .put("type", row.optString("type"))
        }
        compactArray(page, "entities", 16) { row ->
            JSONObject().apply {
                put("name", row.optString("name"))
                val state = row.optString("state")
                if (state.isNotBlank()) put("state", state)
            }
        }
    }

    private fun rowMatches(row: JSONObject, queries: List<String>): Boolean {
        if (queries.isEmpty()) return false
        val fields = listOf(
            row.optString("text"),
            row.optString("in"),
            row.optString("entity"),
            row.optString("name"),
            row.optString("title"),
            row.optString("label"),
            row.optString("value"),
        ).filter { it.length >= 2 }
        return queries.any { q ->
            q.length >= 2 && fields.any { f ->
                f.contains(q, ignoreCase = true) || q.contains(f, ignoreCase = true)
            }
        }
    }

    private fun prioritizeDialogInteractables(page: JSONObject) {
        val arr = page.optJSONArray("interactables") ?: return
        val dialog = JSONArray()
        val menu = JSONArray()
        val rest = JSONArray()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val ctx = row.optString("in")
            when {
                ctx.startsWith("dialog") -> dialog.put(row)
                ctx == "dropdown" -> menu.put(row)
                else -> rest.put(row)
            }
        }
        if (dialog.length() == 0 && menu.length() == 0) return
        val out = JSONArray()
        for (i in 0 until dialog.length()) out.put(dialog.get(i))
        for (i in 0 until menu.length()) out.put(menu.get(i))
        for (i in 0 until rest.length()) out.put(rest.get(i))
        page.put("interactables", out)
    }

    private fun prioritizeMatching(page: JSONObject, key: String, queries: List<String>, cap: Int) {
        val arr = page.optJSONArray(key) ?: return
        if (queries.isEmpty() || arr.length() <= 1) return
        val hit = JSONArray()
        val rest = JSONArray()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            if (rowMatches(row, queries)) hit.put(row) else rest.put(row)
        }
        val out = JSONArray()
        for (i in 0 until hit.length()) if (out.length() < cap) out.put(hit.get(i))
        for (i in 0 until rest.length()) if (out.length() < cap) out.put(rest.get(i))
        page.put(key, out)
    }

    private fun keepMatchingOrDrop(page: JSONObject, key: String, queries: List<String>, cap: Int) {
        val arr = page.optJSONArray(key) ?: return
        if (queries.isEmpty()) {
            page.remove(key)
            return
        }
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            if (rowMatches(row, queries) && out.length() < cap) out.put(row)
        }
        if (out.length() == 0) page.remove(key) else page.put(key, out)
    }

    private fun compactArray(
        page: JSONObject,
        key: String,
        limit: Int,
        keep: (JSONObject) -> JSONObject?,
    ) {
        val src = page.optJSONArray(key) ?: return
        val out = JSONArray()
        val total = src.length()
        for (i in 0 until src.length()) {
            if (out.length() >= limit) break
            val row = src.optJSONObject(i) ?: continue
            keep(row)?.let { out.put(it) }
        }
        page.put(key, out)
        if (total > out.length()) page.put(key + "_total", total)
    }

    private fun compactDropdowns(page: JSONObject) {
        compactArray(page, "dropdowns", 8) { row ->
            JSONObject().apply {
                put("tag", row.optString("tag"))
                if (row.has("idx")) put("idx", row.optInt("idx"))
                val label = row.optString("label")
                if (label.isNotBlank()) put("label", label.take(40))
                val value = row.optString("value")
                if (value.isNotBlank()) put("value", value.take(40))
                if (row.optBoolean("open")) put("open", true)
                row.optJSONArray("options")?.let { opts ->
                    val out = JSONArray()
                    for (i in 0 until opts.length()) {
                        if (out.length() >= 16) break
                        val op = opts.optJSONObject(i) ?: continue
                        val slim = JSONObject().put("text", op.optString("text").take(40))
                        if (op.has("idx")) slim.put("idx", op.optInt("idx"))
                        if (op.optBoolean("selected")) slim.put("selected", true)
                        val v = op.optString("value")
                        if (v.isNotBlank() && v != slim.optString("text")) slim.put("value", v.take(40))
                        out.put(slim)
                    }
                    if (out.length() > 0) put("options", out)
                }
            }
        }
    }

    private fun keywordsFromPage(page: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        addKeyword(out, page.optString("page"))
        addKeyword(out, page.optString("kind"))
        fun walk(arr: JSONArray?, keys: List<String>) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                for (key in keys) addKeyword(out, row.optString(key))
            }
        }
        walk(page.optJSONArray("cards"), listOf("title", "type"))
        walk(page.optJSONArray("entities"), listOf("name"))
        walk(page.optJSONArray("nav"), listOf("title"))
        walk(page.optJSONArray("interactables"), listOf("text", "entity"))
        walk(page.optJSONArray("active_dialogs"), listOf("title", "subtitle", "entity", "kind", "name"))
        val menus = page.optJSONArray("dropdowns")
        walk(menus, listOf("label", "value"))
        if (menus != null) {
            for (i in 0 until menus.length()) {
                walk(menus.optJSONObject(i)?.optJSONArray("options"), listOf("text", "value"))
            }
        }
        return out.toList()
    }

    private fun keywordsFromText(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in text.split('\n', '，', ',', '。', ';', '；')) addKeyword(out, line)
        return out.toList()
    }

    private fun addKeyword(out: MutableSet<String>, raw: String?) {
        val t = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (t.length in 2..24) out.add(t)
    }

    private fun prefetchText() {
        prefetchJob?.cancel()
        prefetchJob = bg.launch {
            runCatching {
                val raw = WebViewService.evaluateJavascript(TEXT_JS, timeoutMs = 15_000L) ?: return@launch
                val clipped = if (raw.length > CACHE_MAX) raw.take(CACHE_MAX) else raw
                val body = runCatching { JSONObject(clipped) }.getOrNull()
                val text = body?.optString("text") ?: clipped
                if (text.isBlank()) return@launch
                rememberLong("prefetch", text)
                rememberKeywords(keywordsFromText(text))
            }
        }
    }

    internal fun resetForTest() = synchronized(cacheLock) {
        lastCaches.clear()
        pageKeywords.clear()
        extraKeywords.clear()
        lastLongText = ""
        lastLongSource = ""
        lastUtterance = ""
        moreSteps = 0
        lastExecCode = ""
        lastExecAt = 0L
        agentReady = false
        lastReceipt = null
        synchronized(pathLock) { clearTrailLocked() }
    }

    internal fun indexForTest(source: String, text: String, keywords: Collection<String> = emptyList()) {
        rememberLong(source, text)
        rememberKeywords(keywords.ifEmpty { keywordsFromText(text) }, fromPage = keywords.isNotEmpty())
    }

    internal fun indexPageForTest(page: JSONObject) {
        rememberCache("snapshot", page)
    }

    internal fun hitsForTest(query: String): JSONArray = findHits(query)

    internal fun attachHitsForTest(query: String): JSONObject {
        val body = JSONObject()
        attachHits(body, query)
        return body
    }

    internal fun compactForTest(page: JSONObject, hasHits: Boolean) {
        compactForModel(page, hasHits)
    }

    private suspend fun peekScroll(): JSONObject? {
        val raw = WebViewService.evaluateJavascript(SCROLL_STATE_JS) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    internal fun normalizePath(path: String): String? {
        val p = path.trim()
        if (!p.startsWith("/") || p.startsWith("//") || p.contains("://")) return null
        return p
    }

    internal fun normalizeHere(raw: String): String? {
        val p = raw.trim()
        if (p.isEmpty()) return null
        if (p.startsWith("http://") || p.startsWith("https://")) {
            return runCatching {
                val uri = java.net.URI(p)
                if (uri.scheme.isNullOrBlank() || uri.authority.isNullOrBlank()) return@runCatching null
                val path = (uri.path ?: "/").ifBlank { "/" }
                val q = uri.query
                val tail = if (q.isNullOrBlank()) path else "$path?$q"
                uri.scheme + "://" + uri.authority + tail
            }.getOrNull()
        }
        return normalizePath(p.substringBefore('#'))
    }

    private val ANNOTATE_JS = """
          function pierce(sel,root){
            var n=(root||document).querySelector(sel);
            return n&&n.shadowRoot?n.shadowRoot:n;
          }
          function ha(){ try { return document.querySelector('home-assistant'); } catch(e){ return null; } }
          function hassOf(){ var n=ha(); return n&&n.hass?n.hass:null; }
          function clean(s){ return String(s||'').replace(/\s+/g,' ').trim().slice(0,80); }
          function loc(hass,key,fallback){
            if(!key) return fallback||'';
            try {
              if(hass&&typeof hass.localize==='function'){
                var t=hass.localize(key);
                if(t&&t!==key&&String(t).indexOf(key)!==0) return clean(t);
              }
            } catch(e){}
            return fallback||'';
          }
          function viewTitle(hass,path){
            var views=hass&&hass.lovelace&&hass.lovelace.config&&hass.lovelace.config.views;
            if(!Array.isArray(views)||!views.length) return '';
            var parts=(path||'').split('/').filter(Boolean);
            var last=parts[parts.length-1]||'';
            var i;
            if(last&&last!=='lovelace'){
              for(i=0;i<views.length;i++){
                if((views[i].path&&views[i].path===last)||String(i)===last)
                  return clean(views[i].title||'');
              }
            }
            var idx=0;
            if(/^\d+$/.test(last)) idx=parseInt(last,10);
            if(idx<0||idx>=views.length) idx=0;
            return clean(views[idx].title||'');
          }
          function panelTitle(hass,key){
            if(!key) return '';
            var p=hass&&hass.panels&&hass.panels[key];
            if(p&&p.title) return clean(p.title);
            return loc(hass,'panel.'+key,'');
          }
          function selectedSidebar(){
            var side=pierce('ha-sidebar');
            if(!side) return '';
            var cur=side.querySelector('[aria-current="page"],[aria-selected="true"],.iron-selected,a[class*="selected"]');
            return clean(cur&&(cur.getAttribute('aria-label')||cur.textContent));
          }
          function pageChrome(){
            var titles=[], seen={};
            function take(t){
              t=clean(t);
              if(!t||t==='Home Assistant'||seen[t]) return;
              seen[t]=1; titles.push(t);
            }
            function walk(root,depth){
              if(!root||depth<0) return;
              try {
                var nodes=root.querySelectorAll?root.querySelectorAll('.toolbar .title,[main-title],.header-title,.page-title,h1,[slot="header"],ha-dialog-header'):[];
                for(var i=0;i<nodes.length&&titles.length<4;i++) take(nodes[i].textContent);
                var kids=root.querySelectorAll?root.querySelectorAll('hass-subpage,ha-config-section,hui-root,ha-top-app-bar-fixed,ha-panel-lovelace,ha-panel-config'):[];
                for(var j=0;j<kids.length&&titles.length<4;j++){
                  if(kids[j].shadowRoot) walk(kids[j].shadowRoot,depth-1);
                }
              } catch(e){}
            }
            var n=ha();
            if(n&&n.shadowRoot) walk(n.shadowRoot,4);
            return titles[0]||'';
          }
          function pageAnnotation(){
            var hass=hassOf();
            var path=location.pathname||'/';
            var segs=path.replace(/^\//,'').split('/').filter(Boolean);
            var first=segs[0]||'';
            var second=segs[1]||'';
            var KIND={
              config:['settings','panel.config'],
              history:['history','panel.history'],
              logbook:['logbook','panel.logbook'],
              'media-browser':['media','panel.media-browser'],
              'developer-tools':['developer','panel.developer-tools'],
              profile:['profile','panel.profile'],
              todo:['todo','panel.todo'],
              energy:['energy','panel.energy'],
              map:['map','panel.map'],
              calendar:['calendar','panel.calendar'],
              hassio:['addons','panel.hassio'],
              supervisor:['addons','panel.hassio'],
              hacs:['hacs',''],
              updates:['updates',''],
              repairs:['repairs',''],
              auth:['sign-in','']
            };
            var kind='dashboard';
            var page='';
            if(first==='config'&&(second==='lovelace'||second==='dashboard')){
              kind='settings';
              page=loc(hass,'ui.panel.config.dashboard.caption','')||panelTitle(hass,'config')||pageChrome()||'Settings';
            } else if(KIND[first]){
              kind=KIND[first][0];
              page=pageChrome()||panelTitle(hass,first)||loc(hass,KIND[first][1],'')||selectedSidebar();
              if(second){
                var sub=panelTitle(hass,second);
                if(sub&&page&&sub.toLowerCase()!==page.toLowerCase()) page=page+' / '+sub;
                else if(sub) page=sub;
              }
            } else {
              page=viewTitle(hass,path)||pageChrome()||selectedSidebar()||panelTitle(hass,first);
              if(!page&&(!first||first==='lovelace')) page=loc(hass,'panel.lovelace','')||'Home';
            }
            page=clean(page);
            if(!page) page=selectedSidebar()||pageChrome()||'Home Assistant';
            return {page:page,kind:kind};
          }
    """.trimIndent()

    private val PAGE_SNAPSHOT_JS = """
        (function(){
          $ANNOTATE_JS
          var SKIP={'ha-sidebar':1};
          var SKIP_TAG={'script':1,'style':1,'link':1,'noscript':1,'svg':1,'path':1,'img':1,'canvas':1,'video':1,'audio':1,'br':1,'hr':1};
          var PASS={'div':1,'span':1,'slot':1,'section':1,'article':1,'main':1,'aside':1,'header':1,'footer':1,'nav':1};
          var HA_ATTRS=['state','entity','entity-id','card-type','type','role','aria-label','title','placeholder','value','name','panel'];
          var MAX_NODES=2500, MAX_CHILDREN=50, nodeCount=0;
          var _idx=0, _interactables=[], _cards=[], _entities=[], _seenEnt={};
          var INTERACTIVE_TAGS={'a':1,'button':1,'details':1,'input':1,'menu':1,'menuitem':1,'select':1,'textarea':1,'summary':1,'dialog':1};
          var INTERACTIVE_ROLES={'button':1,'dialog':1,'treeitem':1,'radio':1,'checkbox':1,'menuitem':1,'option':1,'switch':1,'combobox':1,'textbox':1,'tab':1,'link':1,'slider':1,'listbox':1,'searchbox':1};
          var HA_INTERACTIVE={${HaFrontendComponents.interactiveJs()}};
          function isHaHost(tag){ return ${HaFrontendComponents.HOST_CHECK}; }
          function isControlTag(tag){
            if(HA_INTERACTIVE[tag]) return true;
            if(!(tag.indexOf('ha-')===0||tag.indexOf('md-')===0||tag.indexOf('mwc-')===0||tag.indexOf('hui-')===0||tag.indexOf('wa-')===0)) return false;
            if(tag.indexOf('-card')>0&&tag!=='hui-tile-card'&&tag!=='hui-button-card') return false;
            return ${HaFrontendComponents.CONTROL_RE}.test(tag);
          }
          function isInteractive(el){
            if(!el||el.nodeType!==1) return false;
            var tag=el.tagName.toLowerCase();
            if(INTERACTIVE_TAGS[tag]||isControlTag(tag)) return true;
            var role=el.getAttribute('role');
            if(role&&INTERACTIVE_ROLES[role]) return true;
            var tab=el.getAttribute('tabindex');
            if(tab!==null&&tab!=='-1') return true;
            if(el.hasAttribute('aria-pressed')||el.hasAttribute('aria-checked')||el.hasAttribute('aria-selected')) return true;
            if(el.isContentEditable) return true;
            if(el.classList&&el.classList.contains('clickable')) return true;
            return false;
          }
          function elText(el){
            var al=el.getAttribute('aria-label'); if(al) return al.slice(0,80);
            var lb=el.getAttribute('label'); if(lb) return lb.slice(0,80);
            if(el.label) return String(el.label).slice(0,80);
            var ti=el.getAttribute('title'); if(ti) return ti.slice(0,80);
            var t=(el.innerText||el.textContent||'').trim();
            if(t&&t.length<200) return t.slice(0,80);
            return '';
          }
          function findParentContext(el){
            var walk=el.parentElement, seen=typeof WeakSet!=='undefined'?new WeakSet():null, hops=0;
            while(walk&&hops<40){
              if(seen){ if(seen.has(walk)) break; seen.add(walk); }
              var tn=walk.tagName&&walk.tagName.toLowerCase();
              if(tn==='ha-card'||(tn&&tn.indexOf('hui-')===0&&tn.indexOf('-card')>0)){
                var header=walk.querySelector&&walk.querySelector('.card-header,.name,[slot="header"]');
                var title=header?clean(header.textContent):'';
                var ct=walk.getAttribute('data-card-type')||tn.replace('hui-','').replace('-card','');
                return title?ct+':'+title:ct;
              }
              if(tn==='ha-dialog'||tn==='ha-more-info-dialog'){
                var dt=walk.querySelector&&walk.querySelector('.mdc-dialog__title,ha-dialog-header');
                return 'dialog:'+(dt?clean(dt.textContent):'');
              }
              if(tn==='ha-settings-row'){
                var sr=walk.querySelector&&walk.querySelector('[slot="heading"],.heading');
                return 'setting:'+(sr?clean(sr.textContent):'');
              }
              var next=walk.parentElement;
              if(!next){
                var rn=walk.getRootNode&&walk.getRootNode();
                next=(rn&&rn!==walk&&rn.host)?rn.host:null;
              }
              walk=next; hops++;
            }
            return null;
          }
          function actionType(el,tag){
            if(tag==='input'||tag==='textarea'||tag==='ha-textfield'||tag.indexOf('text-field')>=0) return 'type';
            if(tag==='select'||tag==='ha-select'||tag.indexOf('-select')>=0) return 'select';
            if(tag==='ha-switch'||tag==='md-switch'||tag==='ha-checkbox'||tag==='ha-entity-toggle') return 'toggle';
            if(tag==='ha-slider'||tag==='md-slider') return 'slide';
            if(tag==='a'||el.getAttribute('href')) return 'navigate';
            return 'tap';
          }
          function noteEntity(el){
            try {
              var eid=el.getAttribute&&(el.getAttribute('entity-id')||el.getAttribute('entity')||el.getAttribute('data-entity-id'));
              if(!eid&&el.stateObj) eid=el.stateObj.entity_id;
              if(!eid||_seenEnt[eid]) return null;
              var hass=hassOf(), st=hass&&hass.states&&hass.states[eid];
              var name=st&&st.attributes&&st.attributes.friendly_name;
              var row={name:String(name||eid.split('.').pop().replace(/_/g,' ')),state:st?String(st.state):'',domain:eid.split('.')[0]};
              _seenEnt[eid]=1;
              if(_entities.length<40) _entities.push(row);
              return row;
            } catch(e){ return null; }
          }
          function noteCard(el,tag){
            if(!(tag==='ha-card'||(tag.indexOf('hui-')===0&&tag.indexOf('-card')>0))) return;
            if(_cards.length>=24) return;
            var header=el.querySelector&&el.querySelector('.card-header,.name,[slot="header"]');
            _cards.push({
              title:clean(header&&header.textContent),
              type:el.getAttribute('data-card-type')||tag.replace('hui-','').replace('-card','')
            });
          }
          function ancestorContains(outer, inner){
            var cur=inner, hops=0;
            while(cur&&hops<200){
              if(cur===outer) return true;
              if(outer.contains&&outer.contains(cur)) return true;
              var p=cur.parentNode;
              if(!p) break;
              if(p.nodeType===11&&p.host) cur=p.host;
              else cur=p;
              hops++;
            }
            return false;
          }
          function isTopElement(el){
            var rect=el.getBoundingClientRect();
            if(rect.width===0&&rect.height===0) return false;
            var inVP=rect.left<window.innerWidth&&rect.right>0&&rect.top<window.innerHeight&&rect.bottom>0;
            if(!inVP) return true;
            var cx=rect.left+rect.width/2, cy=rect.top+rect.height/2;
            try {
              var topEl=document.elementFromPoint(cx,cy);
              if(!topEl) return false;
              while(topEl&&topEl.shadowRoot){
                var deeper=topEl.shadowRoot.elementFromPoint(cx,cy);
                if(!deeper||deeper===topEl) break;
                topEl=deeper;
              }
              if(ancestorContains(el, topEl)) return true;
              if(el.contains&&el.contains(topEl)) return true;
              return false;
            } catch(e){ return true; }
          }
          function getPosition(rect){
            var vw=window.innerWidth, vh=window.innerHeight;
            var cx=rect.left+rect.width/2, cy=rect.top+rect.height/2;
            var pos=[];
            if(cy<vh*0.25) pos.push('top');
            else if(cy>vh*0.75) pos.push('bottom');
            if(cx<vw*0.3) pos.push('left');
            else if(cx>vw*0.7) pos.push('right');
            return pos.length?pos.join('-'):'center';
          }
          function entityIdOf(el){
            var walk=el, seen=typeof WeakSet!=='undefined'?new WeakSet():null, hops=0;
            while(walk&&hops<40){
              if(seen){ if(seen.has(walk)) break; seen.add(walk); }
              try {
                var e=walk.getAttribute&&(walk.getAttribute('data-entity-id')||walk.getAttribute('entity-id')||walk.getAttribute('entity'));
                if(e) return e;
                if(walk.stateObj&&walk.stateObj.entity_id) return walk.stateObj.entity_id;
                var cfg=walk._config||walk.config||walk.__config;
                if(cfg&&(cfg.entity||cfg.entity_id)) return cfg.entity||cfg.entity_id;
              } catch(e2){}
              var next=walk.parentElement;
              if(!next){
                var rn=walk.getRootNode&&walk.getRootNode();
                next=(rn&&rn!==walk&&rn.host)?rn.host:null;
              }
              walk=next; hops++;
            }
            return '';
          }
          var _seenEl=typeof WeakSet!=='undefined'?new WeakSet():null;
          function markInteractive(el,o){
            if(!isInteractive(el)) return;
            if(_seenEl){ if(_seenEl.has(el)) return; }
            var rect=el.getBoundingClientRect();
            if(rect.width===0&&rect.height===0) return;
            if(!isTopElement(el)) return;
            if(_interactables.length>=80) return;
            if(_seenEl) _seenEl.add(el);
            _idx++;
            el.setAttribute('data-ava-idx',String(_idx));
            o.idx=_idx;
            var tag=el.tagName.toLowerCase();
            var entry={idx:_idx,tag:tag,action:actionType(el,tag),pos:getPosition(rect)};
            var ctx=findParentContext(el); if(ctx) entry['in']=ctx;
            var eid=entityIdOf(el); if(eid) entry.entity=eid;
            try {
              var rn=el.getRootNode&&el.getRootNode();
              var host=rn&&rn.host;
              if(host&&host.tagName) entry.host=host.tagName.toLowerCase();
            } catch(e3){}
            var text=elText(el); if(text) entry.text=text;
            var role=el.getAttribute('role'); if(role) entry.role=role;
            var ph=el.getAttribute('placeholder'); if(ph) entry.placeholder=ph.slice(0,60);
            var val=el.value;
            if(val!==undefined&&val!==null&&val!==''&&(tag==='input'||tag==='textarea'||tag==='select'))
              entry.value=String(val).slice(0,60);
            if(el.disabled) entry.disabled=true;
            _interactables.push(entry);
          }
          function markMenuItem(el){
            if(!el||el.nodeType!==1) return 0;
            if(_seenEl){ if(_seenEl.has(el)) return parseInt(el.getAttribute('data-ava-idx')||'0',10)||0; }
            if(_interactables.length>=80) return 0;
            if(_seenEl) _seenEl.add(el);
            _idx++;
            el.setAttribute('data-ava-idx',String(_idx));
            var tag=el.tagName.toLowerCase();
            var rect=el.getBoundingClientRect();
            var entry={idx:_idx,tag:tag,action:'tap','in':'dropdown'};
            if(rect.width>0&&rect.height>0) entry.pos=getPosition(rect);
            var text=elText(el); if(text) entry.text=text;
            var val=el.value; if(val!==undefined&&val!==null&&String(val)) entry.value=String(val).slice(0,60);
            _interactables.push(entry);
            return _idx;
          }
          function collectDropdowns(){
            var rows=[], seen=typeof WeakSet!=='undefined'?new WeakSet():null;
            function takeOpts(el){
              var opts=[], raw=el.options;
              if(Array.isArray(raw)){
                for(var oi=0;oi<raw.length&&opts.length<24;oi++){
                  var op=raw[oi];
                  if(op&&typeof op==='object')
                    opts.push({value:String(op.value!=null?op.value:''),text:String(op.label||op.value||'').slice(0,80)});
                  else opts.push({value:String(op),text:String(op).slice(0,80)});
                }
              }
              var items=[];
              try { items=el.querySelectorAll?el.querySelectorAll('ha-dropdown-item,[role=option],ha-list-item,md-menu-item'):[]; } catch(e){}
              for(var ji=0;ji<items.length&&opts.length<24;ji++){
                var it=items[ji];
                var t=elText(it);
                if(!t) continue;
                var ix=markMenuItem(it);
                var row={value:String(it.value||t),text:t.slice(0,80)};
                if(ix) row.idx=ix;
                if(it.selected||it.hasAttribute('selected')||it.getAttribute('aria-selected')==='true') row.selected=true;
                opts.push(row);
              }
              return opts;
            }
            function walk(root, hops){
              if(!root||hops>16||rows.length>=16) return;
              var els=[];
              try { els=root.querySelectorAll?root.querySelectorAll('ha-select,ha-theme-picker,ha-dropdown,ha-combo-box,ha-picker-field,ha-selector-select,ha-selector-theme'):[]; } catch(e){ return; }
              for(var i=0;i<els.length&&rows.length<16;i++){
                var el=els[i];
                if(seen){ if(seen.has(el)) continue; seen.add(el); }
                markInteractive(el,{});
                var tag=el.tagName.toLowerCase();
                var label=el.label||el.getAttribute('label')||el.getAttribute('aria-label')||'';
                var value=el.value;
                if(value===undefined||value===null){
                  try {
                    var pf=el.querySelector&&el.querySelector('ha-picker-field');
                    if(pf&&pf.value!=null) value=pf.value;
                  } catch(e2){}
                }
                var row={tag:tag,label:String(label).slice(0,60),value:String(value||'').slice(0,80)};
                var ix=parseInt(el.getAttribute('data-ava-idx')||'0',10); if(ix) row.idx=ix;
                var opts=takeOpts(el);
                if(opts.length) row.options=opts;
                try { if(el._opened||el.open||el.hasAttribute('open')) row.open=true; } catch(e3){}
                rows.push(row);
              }
              var kids=root.querySelectorAll?root.querySelectorAll('*'):[];
              for(var k=0;k<Math.min(kids.length,80);k++){
                if(kids[k].shadowRoot) walk(kids[k].shadowRoot, hops+1);
              }
            }
            walk(document, 0);
            if(haEl&&haEl.shadowRoot) walk(haEl.shadowRoot, 0);
            return rows;
          }
          function snapOpenMenus(root){
            if(!root) return;
            var kids=[];
            try { kids=root.querySelectorAll?root.querySelectorAll('wa-popup,ha-dropdown'):[]; } catch(e){ return; }
            for(var i=0;i<kids.length;i++){
              var el=kids[i];
              var open=false;
              try { open=!!(el.open||el.active||el.hasAttribute('open')||(el.popup&&el.popup.active)); } catch(e2){}
              var r=el.getBoundingClientRect();
              if(open||(r.width>20&&r.height>20)) snap(el, 8);
            }
            var all=root.querySelectorAll?root.querySelectorAll('*'):[];
            for(var k=0;k<Math.min(all.length,60);k++){
              if(all[k].shadowRoot) snapOpenMenus(all[k].shadowRoot);
            }
          }
          var _snapped=typeof WeakSet!=='undefined'?new WeakSet():null;
          function snap(el,d){
            if(!el) return null;
            if(el.nodeType===1&&_snapped){ if(_snapped.has(el)) return null; _snapped.add(el); }
            var isHost=el.nodeType===1&&isHaHost(el.tagName.toLowerCase());
            if(el.nodeType===1&&!isHost&&(d<=0||nodeCount>=MAX_NODES)){
              var ft=(el.innerText||'').trim();
              if(ft){ nodeCount++; var leaf={tag:el.tagName.toLowerCase(),text:ft.slice(0,150)}; markInteractive(el,leaf); return leaf; }
              return null;
            }
            if(el.nodeType===3){
              var t=(el.textContent||'').trim();
              if(!t) return null;
              nodeCount++;
              return {tag:'#text',text:t.slice(0,120)};
            }
            if(el.nodeType!==1) return null;
            var tag=el.tagName.toLowerCase();
            if(SKIP_TAG[tag]) return null;
            if(el.id&&SKIP[el.id]) return null;
            if(tag==='ha-sidebar') return null;
            nodeCount++;
            var o={tag:tag};
            if(el.id) o.id=el.id;
            var attrs={}, ai;
            for(ai=0;ai<HA_ATTRS.length;ai++){
              var v=el.getAttribute(HA_ATTRS[ai]);
              if(v) attrs[HA_ATTRS[ai]]=v.slice(0,100);
            }
            if(Object.keys(attrs).length) o.attrs=attrs;
            markInteractive(el,o);
            var ent=noteEntity(el);
            if(ent){ o.name=ent.name; o.state=ent.state; o.domain=ent.domain; }
            noteCard(el,tag);
            if(tag==='ha-data-table'){
              try {
                var dtData=el._filteredData||el.data||[];
                var rows=[], max=Math.min(dtData.length,25);
                for(var ri=0;ri<max;ri++){
                  var row=dtData[ri]; if(!row) continue;
                  var name=row.name||row.entity_id||row.id||'';
                  var item={name:String(name).slice(0,80)};
                  if(row.entity_id) item.entity=row.entity_id;
                  if(row.area) item.area=String(row.area).slice(0,40);
                  if(row.status) item.state=String(row.status).slice(0,20);
                  rows.push(item);
                }
                o.total_rows=dtData.length;
                o.rows=rows;
              } catch(e){}
            }
            var effD=isHost?Math.max(d,3):d;
            if(effD>1&&nodeCount<MAX_NODES){
              var children=[];
              var sr=el.shadowRoot;
              if(sr){
                var sn=sr.childNodes;
                for(var i=0;i<Math.min(sn.length,MAX_CHILDREN)&&nodeCount<MAX_NODES;i++){
                  var c=snap(sn[i],effD-1); if(c) children.push(c);
                }
              }
              var ln=el.childNodes;
              for(var j=0;j<Math.min(ln.length,MAX_CHILDREN)&&nodeCount<MAX_NODES;j++){
                var c2=snap(ln[j],effD-1); if(c2) children.push(c2);
              }
              if(children.length){
                if(PASS[tag]&&!o.id&&!o.attrs&&!o.name&&!o.idx&&children.length===1) return children[0];
                o.children=children;
              } else {
                var vt=(el.innerText||'').trim();
                if(vt&&vt.length<200) o.text=vt;
              }
            } else {
              var vt2=(el.innerText||'').trim();
              if(vt2&&vt2.length<200) o.text=vt2;
            }
            if(!o.children&&!o.text&&!o.id&&!o.attrs&&!o.name&&!o.state&&!o.idx&&PASS[tag]) return null;
            return o;
          }
          function deepQuery(root,sel){
            if(!root) return null;
            try { var hit=root.querySelector&&root.querySelector(sel); if(hit) return hit; } catch(e){}
            var sr=root.shadowRoot; if(sr){ hit=deepQuery(sr,sel); if(hit) return hit; }
            var ch=root.querySelectorAll?root.querySelectorAll('*'):[];
            for(var i=0;i<Math.min(ch.length,80);i++){
              if(ch[i].shadowRoot){ hit=deepQuery(ch[i].shadowRoot,sel); if(hit) return hit; }
            }
            return null;
          }
          var haEl=ha();
          var main=haEl&&haEl.shadowRoot?haEl.shadowRoot.querySelector('home-assistant-main'):null;
          var view=deepQuery(main,'hui-view,hui-sections-view,hui-masonry-view,hui-panel-view,hui-sidebar-view');
          if(!view) view=deepQuery(main,'partial-panel-resolver');
          var target=view||main||document.body;
          var nav=[], seenNav={};
          function addNav(title,path,kind){
            title=clean(title);
            if(!path||!title||seenNav[path]||seenNav[title]) return;
            seenNav[path]=1; seenNav[title]=1;
            nav.push({title:title,path:path,kind:kind});
          }
          try {
            var hass=hassOf();
            var views=hass&&hass.lovelace&&hass.lovelace.config&&hass.lovelace.config.views;
            if(Array.isArray(views)){
              for(var vi=0;vi<views.length&&nav.length<24;vi++){
                var v=views[vi]||{};
                addNav(v.title,v.path?('/lovelace/'+v.path):('/lovelace/'+vi),'view');
              }
            }
            if(hass&&hass.panels){
              Object.keys(hass.panels).forEach(function(k){
                if(nav.length>=24) return;
                var p=hass.panels[k];
                if(!p||!p.url_path) return;
                addNav(p.title||loc(hass,'panel.'+k,''),'/'+p.url_path,'panel');
              });
            }
          } catch(e){}
          try {
            var side=pierce('ha-sidebar');
            if(side){
              side.querySelectorAll('a[href]').forEach(function(a){
                if(nav.length>=24) return;
                var href=a.getAttribute('href')||'';
                if(!href||href.charAt(0)!=='/') return;
                var label=a.getAttribute('aria-label')||a.textContent;
                addNav(label,href,'sidebar');
                if(_interactables.length<80){
                  _idx++;
                  a.setAttribute('data-ava-idx',String(_idx));
                  _interactables.push({idx:_idx,tag:'a',action:'navigate',text:clean(label),href:href.slice(0,100)});
                }
              });
            }
          } catch(e){}
          var note=pageAnnotation();
          function dlgVisible(el){
            try {
              if(el.open||(el.hasAttribute&&el.hasAttribute('open'))) return true;
              var r=el.getBoundingClientRect();
              if(r.width>50&&r.height>50) return true;
            } catch(e){}
            return false;
          }
          var haRoot=haEl&&haEl.shadowRoot;
          if(haRoot&&haRoot.children){
            for(var di=0;di<haRoot.children.length;di++){
              var host=haRoot.children[di];
              var tag=(host.tagName||'').toLowerCase();
              if(!(${HaFrontendOverlays.HOST_CHECK})) continue;
              if(!dlgVisible(host)) continue;
              snap(host,10);
            }
          }
          snapOpenMenus(document);
          if(haRoot) snapOpenMenus(haRoot);
          snap(target,8);
          var menus=collectDropdowns();
          return JSON.stringify({
            ok:true,
            page:note.page,
            kind:note.kind,
            nav:nav,
            cards:_cards,
            entities:_entities,
            interactables:_interactables,
            dropdowns:menus
          });
        })();
    """.trimIndent()

    private const val GOTO_JS = """
        function(path){
          if(!path||path.charAt(0)!=='/'||path.indexOf('://')>=0) return JSON.stringify({ok:false,error_code:'invalid_request',error:'path must start with /'});
          var ha=document.querySelector('home-assistant');
          var hass=ha&&ha.hass;
          try {
            if(hass&&typeof hass.navigate==='function'){ hass.navigate(path); return JSON.stringify({ok:true}); }
          } catch(e){}
          try {
            history.pushState(null,'',path);
            window.dispatchEvent(new CustomEvent('location-changed',{detail:{replace:false},bubbles:true,composed:true}));
            return JSON.stringify({ok:true});
          } catch(e2){
            return JSON.stringify({ok:false,error_code:'navigation_failed',error:'could not change view'});
          }
        }
    """

    private const val PATH_JS = """
        (function(){return JSON.stringify({ok:true,href:String(location.href||''),path:String(location.pathname||'')+String(location.search||'')});})();
    """

    private const val SCROLL_STATE_JS = """
        (function(){
          function room(el){
            if(!el) return {down:false,up:false};
            var top=el.scrollTop||0, max=Math.max(0,(el.scrollHeight||0)-(el.clientHeight||0));
            return {down:max-top>20, up:top>20};
          }
          var cx=window.innerWidth/2, cy=window.innerHeight/2, el=null;
          try {
            el=document.elementFromPoint(cx,cy);
            while(el&&el.shadowRoot){
              var d=el.shadowRoot.elementFromPoint(cx,cy);
              if(!d||d===el) break;
              el=d;
            }
            var walk=el, seen=typeof Set!=='undefined'?new Set():null;
            while(walk){
              if(seen){ if(seen.has(walk)) break; seen.add(walk); }
              try {
                var cs=getComputedStyle(walk);
                var ov=cs.overflowY||cs.overflow;
                if((ov==='auto'||ov==='scroll'||ov==='overlay')&&walk.scrollHeight>walk.clientHeight+10){
                  var r=room(walk);
                  return JSON.stringify({ok:true,can_scroll_down:r.down,can_scroll_up:r.up});
                }
              } catch(e){}
              var next=walk.parentElement;
              if(!next){
                var rn=walk.getRootNode&&walk.getRootNode();
                next=(rn&&rn!==walk&&rn.host)?rn.host:null;
              }
              walk=next;
            }
          } catch(e){}
          var se=document.scrollingElement||document.documentElement;
          var r2=room(se);
          return JSON.stringify({ok:true,can_scroll_down:r2.down,can_scroll_up:r2.up});
        })();
    """

    private const val TEXT_JS = """
        (function(){
          function dq(root,sel){
            if(!root) return null;
            try { var el=root.querySelector&&root.querySelector(sel); if(el) return el; } catch(e){}
            var sr=root.shadowRoot; if(sr){ el=dq(sr,sel); if(el) return el; }
            var ch=root.querySelectorAll?root.querySelectorAll('*'):[];
            for(var i=0;i<Math.min(ch.length,80);i++){
              if(ch[i].shadowRoot){ el=dq(ch[i].shadowRoot,sel); if(el) return el; }
            }
            return null;
          }
          var ha=document.querySelector('home-assistant');
          var main=ha&&ha.shadowRoot?ha.shadowRoot.querySelector('home-assistant-main'):null;
          var start=dq(main,'hui-view,hui-sections-view,hui-masonry-view,hui-panel-view,partial-panel-resolver')||main||document.body;
          var roots=[start], texts=[], seen=typeof Set!=='undefined'?new Set():null, joined=0;
          while(roots.length&&texts.length<80&&joined<24000){
            var r=roots.pop();
            if(!r) continue;
            if(seen){ if(seen.has(r)) continue; seen.add(r); }
            try {
              var els=r.querySelectorAll?r.querySelectorAll('*'):[];
              for(var i=0;i<els.length&&texts.length<80&&joined<24000;i++){
                var el=els[i];
                if(el.shadowRoot) roots.push(el.shadowRoot);
                var t=(el.textContent||'').replace(/\s+/g,' ').trim();
                if(t&&t.length>1&&t.length<200&&texts.indexOf(t)<0){
                  texts.push(t);
                  joined+=t.length+1;
                }
              }
            } catch(e){}
          }
          return JSON.stringify({ok:true,text:texts.join('\n'),count:texts.length});
        })();
    """

    private val DIALOG_JS = """
        (function(){
          function dq(root,sel){
            if(!root) return null;
            try { var el=root.querySelector&&root.querySelector(sel); if(el) return el; } catch(e){}
            var sr=root.shadowRoot; if(sr){ el=dq(sr,sel); if(el) return el; }
            var ch=root.querySelectorAll?root.querySelectorAll('*'):[];
            for(var i=0;i<Math.min(ch.length,80);i++){
              if(ch[i].shadowRoot){ el=dq(ch[i].shadowRoot,sel); if(el) return el; }
            }
            return null;
          }
          function dqAll(root,sel,out,seen){
            if(!root||(seen&&seen.has(root))) return out;
            if(seen) seen.add(root);
            try { root.querySelectorAll&&root.querySelectorAll(sel).forEach(function(x){out.push(x);}); } catch(e){}
            var ch=root.querySelectorAll?root.querySelectorAll('*'):[];
            for(var i=0;i<ch.length;i++){
              if(ch[i].shadowRoot) dqAll(ch[i].shadowRoot,sel,out,seen);
              if(ch[i].tagName==='SLOT'){
                var a=ch[i].assignedElements?ch[i].assignedElements({flatten:true}):[];
                for(var j=0;j<a.length;j++) dqAll(a[j],sel,out,seen);
              }
            }
            return out;
          }
          function textOf(el){ return el?String(el.innerText||el.textContent||'').replace(/\s+/g,' ').trim().slice(0,200):''; }
          function hassOf(){ var n=document.querySelector('home-assistant'); return n&&n.hass?n.hass:null; }
          function isOverlayHost(tag){ return ${HaFrontendOverlays.HOST_CHECK}; }
          function overlayKind(tag){ return ${HaFrontendOverlays.KIND_JS}; }
          function entityOf(el){
            try {
              if(el._entityId) return String(el._entityId);
              if(el.entityId) return String(el.entityId);
              if(el._params&&el._params.entityId) return String(el._params.entityId);
              if(el.params&&el.params.entityId) return String(el.params.entityId);
            } catch(e){}
            return '';
          }
          function extractDialog(haDialog){
            var result={type:'unknown'};
            var parent=haDialog.getRootNode&&haDialog.getRootNode();
            if(parent&&parent.host&&parent.host.tagName) result.type=parent.host.tagName.toLowerCase();
            else result.type=(haDialog.tagName||'').toLowerCase();
            result.kind=overlayKind(result.type);
            var hdr=dq(haDialog,'ha-dialog-header');
            if(hdr){
              result.title=textOf(dq(hdr,'[slot="title"],.header-title'))||haDialog.getAttribute('header-title')||'';
              var sub=textOf(dq(hdr,'[slot="subtitle"],.header-subtitle'))||haDialog.getAttribute('header-subtitle')||'';
              if(sub) result.subtitle=sub;
            } else {
              result.title=haDialog.getAttribute('header-title')||textOf(dq(haDialog,'.title,h1,h2'))||'';
            }
            var body=[], seen=typeof Set!=='undefined'?new Set():null;
            var inputs=dqAll(haDialog,'ha-input,ha-textfield,ha-select,ha-combo-box,ha-entity-picker,ha-area-picker,ha-device-picker,ha-selector,ha-form,ha-yaml-editor,ha-code-editor,input,textarea,select,ha-date-input,ha-time-input,ha-icon-picker',[],seen);
            for(var i=0;i<inputs.length&&body.length<24;i++){
              var inp=inputs[i], tag=inp.tagName.toLowerCase(), item={element:tag};
              var label=inp.getAttribute('label')||inp.getAttribute('aria-label')||inp.getAttribute('placeholder')||'';
              if(label) item.label=label.slice(0,80);
              if(tag==='select'||tag==='ha-select'){
                var nativeSelect=tag==='select'?inp:dq(inp,'select');
                if(nativeSelect){
                  item.value=nativeSelect.value||'';
                  var opts=[], oi;
                  for(oi=0;oi<nativeSelect.options.length&&opts.length<20;oi++){
                    var o=nativeSelect.options[oi];
                    opts.push({value:o.value,text:String(o.textContent||'').trim().slice(0,60),selected:!!o.selected});
                  }
                  if(opts.length) item.options=opts;
                } else {
                  item.value=String(inp.value||'').slice(0,200);
                }
              } else if(tag==='ha-form'){
                item.element='ha-form';
                try {
                  var schema=inp.schema;
                  if(schema&&schema.length){
                    var fields=[], fi;
                    for(fi=0;fi<schema.length&&fields.length<20;fi++){
                      var s=schema[fi]||{};
                      var sel=s.selector;
                      var typ=s.type||(sel&&typeof sel==='object'?Object.keys(sel)[0]:'text');
                      fields.push({name:s.name,type:typ,label:s.label||s.name,required:!!s.required});
                    }
                    if(fields.length) item.fields=fields;
                  }
                  var data=inp.data;
                  if(data&&typeof data==='object'){
                    var values={}, keys=Object.keys(data);
                    for(var ki=0;ki<keys.length&&ki<20;ki++) values[keys[ki]]=String(data[keys[ki]]).slice(0,100);
                    item.values=values;
                  }
                } catch(e){}
              } else {
                var native=(tag.indexOf('ha-')===0?dq(inp,'input,textarea'):inp)||inp;
                item.value=String(native.value||inp.value||'').slice(0,200);
                var tp=native.getAttribute&&native.getAttribute('type');
                if(tp) item.input_type=tp;
              }
              if(inp.disabled||inp.hasAttribute('disabled')) item.disabled=true;
              if(inp.required||inp.hasAttribute('required')) item.required=true;
              if(inp.readOnly||inp.hasAttribute('readonly')) item.readonly=true;
              if(label) item.hint='ava_page_act action=type text="'+label.slice(0,40)+'" value="..."';
              var iidx=parseInt(inp.getAttribute('data-ava-idx')||'0',10); if(iidx) item.idx=iidx;
              body.push(item);
            }
            var texts=dqAll(haDialog,'p,.secondary,[id*="description"],ha-alert,ha-markdown',[],typeof Set!=='undefined'?new Set():null);
            for(var t=0;t<texts.length&&body.length<30;t++){
              var txt=textOf(texts[t]);
              if(txt&&txt.length>2) body.push({element:'text',text:txt});
            }
            if(body.length) result.body=body;
            var list=dqAll(haDialog,'ha-list-item,mwc-list-item,ha-md-list-item,ha-check-list-item,ha-clickable-list-item,md-list-item',[],typeof Set!=='undefined'?new Set():null);
            if(list.length){
              result.list_items=list.slice(0,30).map(function(li){
                var o={text:textOf(li).slice(0,100)};
                var rl=li.getAttribute('role'); if(rl) o.role=rl;
                if(li.selected||li.activated||li.hasAttribute('selected')||li.hasAttribute('activated')) o.selected=true;
                if(o.text) o.hint='ava_page_act action=tap text="'+o.text.slice(0,40)+'"';
                var lidx=parseInt(li.getAttribute('data-ava-idx')||'0',10); if(lidx) o.idx=lidx;
                return o;
              });
            }
            var tabs=dqAll(haDialog,'ha-tab,mwc-tab,[role=tab]',[],typeof Set!=='undefined'?new Set():null);
            if(tabs.length){
              result.tabs=tabs.slice(0,12).map(function(tab){
                var o={text:textOf(tab).slice(0,60)};
                if(tab.hasAttribute('active')||tab.getAttribute('aria-selected')==='true') o.selected=true;
                if(o.text) o.hint='ava_page_act action=tap text="'+o.text.slice(0,40)+'"';
                var tidx=parseInt(tab.getAttribute('data-ava-idx')||'0',10); if(tidx) o.idx=tidx;
                return o;
              });
            }
            var ftr=dq(haDialog,'ha-dialog-footer,[slot="footer"],footer');
            var btns=dqAll(ftr||haDialog,'ha-button,mwc-button,ha-icon-button,button',[],typeof Set!=='undefined'?new Set():null);
            var footer=[];
            for(var b=0;b<btns.length;b++){
              var btn=btns[b], txt=textOf(btn);
              if(!txt) continue;
              var item={text:txt.slice(0,60)};
              var slot=btn.getAttribute('slot')||'';
              if(slot.indexOf('primary')>=0) item.role='primary';
              else if(slot.indexOf('secondary')>=0) item.role='secondary';
              if(btn.disabled||btn.hasAttribute('disabled')) item.disabled=true;
              if(btn.getAttribute('data-dialog')==='close') item.action='close';
              if(btn.getAttribute('variant')==='danger') item.variant='danger';
              item.hint='ava_page_act action=tap text="'+txt.slice(0,40)+'"';
              var bidx=parseInt(btn.getAttribute('data-ava-idx')||'0',10); if(bidx) item.idx=bidx;
              footer.push(item);
            }
            if(footer.length) result.buttons=footer;
            return result;
          }
          function extractMoreInfo(host){
            var tag=(host.tagName||'').toLowerCase();
            var shell=host.shadowRoot?dq(host,'${HaFrontendOverlays.SHELL_SEL}')||host:host;
            var result=extractDialog(shell);
            result.type=tag;
            result.kind='more_info';
            var eid=entityOf(host);
            var hass=hassOf();
            var st=eid&&hass&&hass.states?hass.states[eid]:null;
            if(eid) result.entity=eid;
            if(st){
              result.state=String(st.state);
              result.name=(st.attributes&&st.attributes.friendly_name)||eid;
              result.domain=eid.split('.')[0];
              if(!result.title) result.title=result.name;
            }
            try { if(host._currView) result.view=String(host._currView); } catch(e){}
            var controls=dqAll(host,'ha-control-switch,ha-control-button,ha-control-select,ha-control-slider,ha-switch,md-switch,ha-slider,more-info-content,hui-card-features',[],typeof Set!=='undefined'?new Set():null);
            if(controls.length){
              result.controls=controls.slice(0,16).map(function(c){
                var o={tag:(c.tagName||'').toLowerCase()};
                var t=textOf(c); if(t) o.text=t.slice(0,60);
                var ix=parseInt(c.getAttribute('data-ava-idx')||'0',10); if(ix) o.idx=ix;
                return o;
              });
            }
            return result;
          }
          function extractToast(el){
            return {type:(el.tagName||'').toLowerCase(),kind:'toast',title:textOf(el).slice(0,120)};
          }
          function dlgVisible(el){
            try {
              if(el.open||(el.hasAttribute&&el.hasAttribute('open'))) return true;
              var r=el.getBoundingClientRect();
              if(r.width>50&&r.height>50) return true;
              var sr=el.shadowRoot;
              if(sr){
                var inner=sr.querySelector('dialog[open],md-dialog[open],[role="dialog"],.mdc-dialog--open,ha-dialog');
                if(inner){
                  var ir=inner.getBoundingClientRect();
                  if(ir.width>50&&ir.height>50) return true;
                }
              }
            } catch(e){}
            return false;
          }
          var ha=document.querySelector('home-assistant');
          var root=ha&&ha.shadowRoot;
          var out=[];
          function collectHosts(node){
            if(!node||!node.children) return;
            for(var hi=0;hi<node.children.length;hi++){
              var h=node.children[hi];
              var ht=(h.tagName||'').toLowerCase();
              if(isOverlayHost(ht)) hosts.push(h);
            }
          }
          var hosts=[];
          collectHosts(root);
          collectHosts(document.body);
          for(var i=0;i<hosts.length;i++){
            var host=hosts[i];
            var ht=(host.tagName||'').toLowerCase();
            var kind=overlayKind(ht);
            if(kind!=='toast'&&kind!=='menu'&&!dlgVisible(host)) continue;
            var snap;
            if(kind==='more_info') snap=extractMoreInfo(host);
            else if(kind==='toast') snap=extractToast(host);
            else {
              var inner=host.shadowRoot?dq(host,'${HaFrontendOverlays.SHELL_SEL}')||host:host;
              snap=extractDialog(inner);
              snap.type=ht;
              snap.kind=kind;
              var eid=entityOf(host); if(eid) snap.entity=eid;
            }
            if(snap.title||snap.body||snap.buttons||snap.list_items||snap.tabs||snap.entity||snap.controls||kind==='more_info'||kind==='sheet'||kind==='toast'||kind==='drawer'||kind==='menu'||kind==='shortcut')
              out.push(snap);
          }
          return JSON.stringify({dialogs:out,overlays:out});
        })();
    """

    private val FIND_JS = """
          function collectRoots(node, out, visited) {
            if (!node || visited.has(node)) return out;
            visited.add(node);
            out.push(node);
            var els = node.querySelectorAll ? node.querySelectorAll('*') : [];
            for (var i = 0; i < els.length; i++) {
              var e = els[i];
              if (e.shadowRoot && !visited.has(e.shadowRoot)) collectRoots(e.shadowRoot, out, visited);
              if (e.tagName === 'SLOT') {
                var assigned = e.assignedElements ? e.assignedElements({flatten:true}) : [];
                for (var j = 0; j < assigned.length; j++) {
                  if (assigned[j].shadowRoot && !visited.has(assigned[j].shadowRoot)) collectRoots(assigned[j].shadowRoot, out, visited);
                }
              }
            }
            return out;
          }
          function deepQuery(root, sel) {
            var start = root === document ? document : (root.shadowRoot || root);
            var roots = collectRoots(start, [], new Set());
            if (root === document && document.body) {
              var kids = document.body.children;
              for (var k = 0; k < kids.length; k++) if (kids[k].shadowRoot) collectRoots(kids[k].shadowRoot, roots, new Set());
            }
            for (var i = 0; i < roots.length; i++) {
              try { var r = roots[i].querySelector(sel); if (r) return r; } catch (_) {}
            }
            return null;
          }
          function findByText(root, needle) {
            var lc = String(needle || '').toLowerCase();
            if (!lc) return null;
            var best = null, bestLen = Infinity;
            var roots = collectRoots(root === document ? document : (root.shadowRoot || root), [], new Set());
            for (var ri = 0; ri < roots.length; ri++) {
              var els = roots[ri].querySelectorAll ? roots[ri].querySelectorAll('*') : [];
              for (var i = 0; i < els.length; i++) {
                var node = els[i], tag = (node.tagName || '').toLowerCase();
                if (tag === 'script' || tag === 'style' || tag === 'svg') continue;
                var fields = [node.getAttribute && node.getAttribute('aria-label'), node.getAttribute && node.getAttribute('label'), node.getAttribute && node.getAttribute('placeholder'), node.getAttribute && node.getAttribute('title'), (node.textContent || '').trim()];
                for (var f = 0; f < fields.length; f++) {
                  var t = fields[f];
                  if (!t || t.length > 500) continue;
                  var tl = String(t).toLowerCase();
                  if (tl === lc) return node;
                  if (tl.indexOf(lc) !== -1 && t.length < bestLen) { best = node; bestLen = t.length; }
                }
              }
            }
            return best;
          }
          function findEl(idx, selector, text) {
            var el = null;
            if (idx > 0) el = deepQuery(document, '[data-ava-idx="'+idx+'"]');
            if (!el && selector) el = deepQuery(document, selector);
            if (!el && text) el = findByText(document, text);
            return el;
          }
    """.trimIndent()

    private val INTERACT_JS = """
        function(action, idx, selector, text, value, clear, keyName, repeat) {
          $FIND_JS
          var ISEL = '${HaFrontendComponents.ISEL}';
          var el = findEl(idx, selector, text);
          if (action === 'key' && !el) {
            el = document.activeElement;
            while (el && el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
            el = el || document.body;
          }
          if (!el) return JSON.stringify({ok:false,error_code:'stale_ref',error:'element not found; read the page again'});
          function visible(e) {
            var r = e.getBoundingClientRect(), s = getComputedStyle(e);
            return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
          }
          if (action !== 'key' && (!el.isConnected || !visible(el) || el.disabled))
            return JSON.stringify({ok:false,error_code:'stale_ref',error:'control unavailable; read the page again'});
          if (action === 'tap') {
            var SWITCH = 'ha-switch,md-switch,mwc-switch,ha-entity-toggle,ha-state-control-toggle,[role=switch]';
            var CARD = 'ha-card,hui-card,hui-tile-card,hui-entities-card,hui-button-card,hui-entity-card,hui-glance-card,hui-generic-entity-row,hui-toggle-entity-row,ha-integration-card,ha-config-flow-card';
            function deepEFP(x, y) {
              var e = document.elementFromPoint(x, y);
              if (!e) return null;
              while (e && e.shadowRoot) {
                var deeper = e.shadowRoot.elementFromPoint(x, y);
                if (!deeper || deeper === e) break;
                e = deeper;
              }
              return e;
            }
            function drillDown(node) {
              if (!node) return null;
              if (node.matches && node.matches(ISEL)) return node;
              return deepQuery(node, ISEL);
            }
            function climbUp(node) {
              if (!node) return null;
              var walk = node, seen = new Set();
              while (walk && !seen.has(walk)) {
                seen.add(walk);
                if (walk.matches && walk.matches(ISEL)) return walk;
                if (walk.matches && walk.matches(CARD)) {
                  var inner = deepQuery(walk, ISEL);
                  if (inner) return inner;
                }
                var next = walk.parentElement;
                if (!next) {
                  var rn = walk.getRootNode && walk.getRootNode();
                  next = (rn && rn !== walk && rn.host) ? rn.host : null;
                }
                walk = next;
              }
              return null;
            }
            function hostOf(node, sel) {
              var walk = node, seen = new Set();
              while (walk && !seen.has(walk)) {
                seen.add(walk);
                if (walk.matches && walk.matches(sel)) return walk;
                var next = walk.parentElement;
                if (!next) {
                  var rn = walk.getRootNode && walk.getRootNode();
                  next = (rn && rn !== walk && rn.host) ? rn.host : null;
                }
                walk = next;
              }
              return null;
            }
            function entityOf(node) {
              var walk = node, seen = new Set();
              while (walk && !seen.has(walk)) {
                seen.add(walk);
                try {
                  var e = walk.getAttribute && (walk.getAttribute('data-entity-id') || walk.getAttribute('entity-id') || walk.getAttribute('entity'));
                  if (e) return e;
                  if (walk.stateObj && walk.stateObj.entity_id) return walk.stateObj.entity_id;
                  var cfg = walk._config || walk.config || walk.__config;
                  if (cfg && (cfg.entity || cfg.entity_id)) return cfg.entity || cfg.entity_id;
                } catch (e2) {}
                var next = walk.parentElement;
                if (!next) {
                  var rn = walk.getRootNode && walk.getRootNode();
                  next = (rn && rn !== walk && rn.host) ? rn.host : null;
                }
                walk = next;
              }
              return '';
            }
            function showRipple(cx, cy) {
              try {
                var overlay = document.getElementById('__ava_tap_overlay');
                if (!overlay) {
                  overlay = document.createElement('div');
                  overlay.id = '__ava_tap_overlay';
                  overlay.style.cssText = 'position:fixed;top:0;left:0;width:100vw;height:100vh;pointer-events:none;z-index:2147483647;overflow:visible;';
                  document.documentElement.appendChild(overlay);
                }
                var dot = document.createElement('div');
                dot.style.cssText = 'position:fixed;pointer-events:none;border-radius:50%;width:28px;height:28px;left:'+(cx-14)+'px;top:'+(cy-14)+'px;background:rgba(3,169,244,0.5);box-shadow:0 0 12px 4px rgba(3,169,244,0.3);transition:transform .4s cubic-bezier(.2,.8,.3,1),opacity .4s ease-out;transform:scale(1);';
                overlay.appendChild(dot);
                requestAnimationFrame(function(){requestAnimationFrame(function(){dot.style.transform='scale(2.8)';dot.style.opacity='0';});});
                setTimeout(function(){try{dot.remove();}catch(e4){}},450);
              } catch (e5) {}
            }
            function fireHassAction(host, hit) {
              if (!host || !host.dispatchEvent) return;
              var cfg = null;
              try { cfg = host._config || host.config || host.__config || null; } catch (e6) {}
              if (!cfg || typeof cfg !== 'object') {
                cfg = {};
                if (hit.entity_id) {
                  cfg.entity = hit.entity_id;
                  cfg.tap_action = { action: hit.kind === 'entity_toggle' ? 'toggle' : 'more-info' };
                } else {
                  cfg.tap_action = { action: 'more-info' };
                }
              }
              try {
                host.dispatchEvent(new CustomEvent('hass-action', {
                  bubbles: true, composed: true, cancelable: true,
                  detail: { config: cfg, action: 'tap' }
                }));
              } catch (e7) {}
            }
            function classifyTap(target, found) {
              var eid = entityOf(target) || entityOf(found) || '';
              if (target.matches && target.matches(SWITCH))
                return {kind:'entity_toggle', entity_id:eid, host:target, needHassAction:false};
              var card = hostOf(target, CARD) || hostOf(found, CARD);
              if (card) {
                if (!eid) eid = entityOf(card) || '';
                var specific = target.matches && target.matches(ISEL) && !target.matches(CARD) && !target.matches(SWITCH);
                if (specific) return {kind:'control', entity_id:eid, host:target, needHassAction:false};
                return {kind:'card_click', entity_id:eid, host:card, needHassAction:true};
              }
              return {kind:'control', entity_id:eid, host:target, needHassAction:false};
            }
            var rect = el.getBoundingClientRect();
            if (rect.width === 0 && rect.height === 0) {
              var inner = deepQuery(el, ISEL);
              if (inner) { el = inner; rect = el.getBoundingClientRect(); }
            }
            var cx = rect.left + rect.width / 2, cy = rect.top + rect.height / 2;
            showRipple(cx, cy);
            var deep = deepEFP(cx, cy);
            var target = drillDown(el) || climbUp(deep) || drillDown(deep) || el;
            try {
              var touch = new Touch({identifier:Date.now(),target:target,clientX:cx,clientY:cy,pageX:cx+window.scrollX,pageY:cy+window.scrollY,screenX:cx,screenY:cy,radiusX:1,radiusY:1,force:1});
              var touchOpts = {bubbles:true,cancelable:true,composed:true,touches:[touch],targetTouches:[touch],changedTouches:[touch]};
              target.dispatchEvent(new TouchEvent('touchstart', touchOpts));
              target.dispatchEvent(new TouchEvent('touchend', touchOpts));
            } catch (e) {}
            try {
              var ptr = {bubbles:true,cancelable:true,composed:true,clientX:cx,clientY:cy,pointerType:'touch',isPrimary:true,pointerId:1};
              target.dispatchEvent(new PointerEvent('pointerdown', ptr));
              target.dispatchEvent(new PointerEvent('pointerup', ptr));
            } catch (e2) {}
            var mouse = {bubbles:true,cancelable:true,composed:true,clientX:cx,clientY:cy,button:0,buttons:1};
            target.dispatchEvent(new MouseEvent('mousedown', mouse));
            target.dispatchEvent(new MouseEvent('mouseup', mouse));
            target.dispatchEvent(new MouseEvent('click', mouse));
            try { target.click(); } catch (e3) {}
            if (target.focus) target.focus();
            var hit = classifyTap(target, el);
            if (hit.needHassAction) fireHassAction(hit.host, hit);
            return JSON.stringify({ok:true,action:'tap',tag:(el.tagName||'').toLowerCase(),target:(target.tagName||'').toLowerCase(),kind:hit.kind,entity_id:hit.entity_id||undefined,hass_action:!!hit.needHassAction});
          }
          if (action === 'type') {
            function score(x) {
              if (!x) return -1;
              var tag = (x.tagName || '').toLowerCase();
              if (tag === 'textarea') return 100;
              if (tag === 'input') return 95;
              if (x.isContentEditable || (x.getAttribute && x.getAttribute('contenteditable') != null)) return 90;
              if (x.classList && (x.classList.contains('cm-content') || x.classList.contains('monaco-editor'))) return 80;
              if (tag === 'ha-textfield' || tag === 'ha-input' || tag === 'wa-input') return 70;
              return -1;
            }
            function allDeep(root, sel, out, seen) {
              if (!root || seen.has(root)) return out;
              seen.add(root);
              try { root.querySelectorAll && root.querySelectorAll(sel).forEach(function(x){ out.push(x); }); } catch (_) {}
              var nodes = root.querySelectorAll ? root.querySelectorAll('*') : [];
              for (var i = 0; i < nodes.length; i++) {
                if (nodes[i].shadowRoot) allDeep(nodes[i].shadowRoot, sel, out, seen);
                if (nodes[i].tagName === 'SLOT') {
                  var a = nodes[i].assignedElements ? nodes[i].assignedElements({flatten:true}) : [];
                  for (var j = 0; j < a.length; j++) allDeep(a[j], sel, out, seen);
                }
              }
              return out;
            }
            function findInput(host) {
              var found = allDeep(host.shadowRoot || host, 'input,textarea,[contenteditable=true],[contenteditable=""],.cm-content,.monaco-editor textarea,ha-textfield,ha-input', [], new Set());
              var best = score(host) >= 0 ? host : null, bs = score(best);
              for (var i = 0; i < found.length; i++) { var s = score(found[i]); if (s > bs) { best = found[i]; bs = s; } }
              return best || host;
            }
            function fire(x, type) { x.dispatchEvent(new Event(type, {bubbles:true, composed:true})); }
            function sendKey(x, k) {
              x.dispatchEvent(new KeyboardEvent('keydown', {key:k,bubbles:true,cancelable:true,composed:true}));
              if (k.length === 1) x.dispatchEvent(new KeyboardEvent('keypress', {key:k,bubbles:true,cancelable:true,composed:true}));
              x.dispatchEvent(new KeyboardEvent('keyup', {key:k,bubbles:true,cancelable:true,composed:true}));
            }
            var inp = findInput(el);
            if (inp.focus) inp.focus();
            if (inp.select && clear) inp.select();
            var tag = (inp.tagName || '').toLowerCase();
            var isEditable = inp.isContentEditable || (inp.getAttribute && inp.getAttribute('contenteditable') != null) || (inp.classList && inp.classList.contains('cm-content'));
            if (isEditable) {
              if (clear) {
                var sel = window.getSelection();
                if (sel) {
                  var range = document.createRange();
                  range.selectNodeContents(inp);
                  sel.removeAllRanges();
                  sel.addRange(range);
                }
              }
              try { document.execCommand('insertText', false, value); } catch (e) { inp.textContent = value; }
              fire(inp, 'beforeinput'); fire(inp, 'input'); fire(inp, 'change');
              return JSON.stringify({ok:true,action:'type',mode:'contenteditable',tag:tag,value:String(inp.innerText||inp.textContent||'').slice(0,100)});
            }
            if ('value' in inp) {
              var proto = tag === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              var nativeSet = Object.getOwnPropertyDescriptor(proto, 'value');
              nativeSet = nativeSet && nativeSet.set;
              if (clear) { if (nativeSet) nativeSet.call(inp, ''); else inp.value = ''; fire(inp, 'input'); }
              if (nativeSet) nativeSet.call(inp, value); else inp.value = value;
              fire(inp, 'beforeinput'); fire(inp, 'input'); fire(inp, 'change');
              sendKey(inp, 'Enter');
              return JSON.stringify({ok:true,action:'type',mode:'value',tag:tag,value:String(inp.value||'').slice(0,100)});
            }
            return JSON.stringify({ok:false,error:'target is not editable',tag:tag});
          }
          if (action === 'key') {
            function keyCodeFor(k) {
              var m = {Enter:13,Escape:27,Tab:9,Backspace:8,Delete:46,ArrowUp:38,ArrowDown:40,ArrowLeft:37,ArrowRight:39};
              return m[k] || 0;
            }
            if (el.focus) el.focus();
            var k = keyName;
            var opts = {key:k,code:k,keyCode:keyCodeFor(k),which:keyCodeFor(k),bubbles:true,cancelable:true,composed:true};
            for (var i = 0; i < repeat; i++) {
              el.dispatchEvent(new KeyboardEvent('keydown', opts));
              if (k.length === 1) el.dispatchEvent(new KeyboardEvent('keypress', opts));
              el.dispatchEvent(new KeyboardEvent('keyup', opts));
            }
            return JSON.stringify({ok:true,action:'key',key:k,repeat:repeat,target:(el.tagName||'').toLowerCase()});
          }
          return JSON.stringify({ok:false,error:'unsupported action'});
        }
    """.trimIndent()

    private val SCROLL_JS = """
        function(dx, dy, direction, amount, idx, selector, text) {
          $FIND_JS
          var el = findEl(idx, selector, text);
          if (!el) {
            function deepEFP(x, y) {
              var e = document.elementFromPoint(x, y);
              if (!e) return null;
              while (e && e.shadowRoot) {
                var deeper = e.shadowRoot.elementFromPoint(x, y);
                if (!deeper || deeper === e) break;
                e = deeper;
              }
              return e;
            }
            function findScroller(node) {
              var walk = node, seen = new Set();
              while (walk && !seen.has(walk)) {
                seen.add(walk);
                var cs = window.getComputedStyle(walk);
                var ov = cs.overflowY || cs.overflow;
                if ((ov === 'auto' || ov === 'scroll' || ov === 'overlay') && walk.scrollHeight > walk.clientHeight + 10) return walk;
                var next = walk.parentElement;
                if (!next) {
                  var rn = walk.getRootNode && walk.getRootNode();
                  next = (rn && rn !== walk && rn.host) ? rn.host : null;
                }
                walk = next;
              }
              return null;
            }
            function findDataTableScroller() {
              var roots = collectRoots(document, [], new Set());
              for (var ri = 0; ri < roots.length; ri++) {
                try {
                  var lv = roots[ri].querySelector('lit-virtualizer[scroller], .mdc-data-table__content.scroller, .ha-scrollbar, #view, hui-view');
                  if (lv && lv.scrollHeight > lv.clientHeight + 10) return lv;
                } catch (_) {}
              }
              return null;
            }
            el = findScroller(deepEFP(window.innerWidth/2, window.innerHeight/2)) || findDataTableScroller() || document.scrollingElement || document.documentElement;
          }
          if (!el) return JSON.stringify({ok:false,error:'no scroller'});
          if (el.scrollBy) el.scrollBy({left:dx,top:dy,behavior:'smooth'});
          else { el.scrollLeft = (el.scrollLeft||0)+dx; el.scrollTop = (el.scrollTop||0)+dy; }
          try { if (window.__avaScrollGate && window.__avaScrollGate.mark) window.__avaScrollGate.mark(); } catch (e) {}
          return JSON.stringify({ok:true,scrolled:true,direction:direction,amount:amount,target:(el.tagName||'document').toLowerCase()});
        }
    """.trimIndent()

    /**
     * Resident page-world runner. Host installs once (Claw's ha_crack.js role),
     * then only notifies with `window.__avaPage.run({action,...})`.
     * No in-page eval/Function — HA CSP blocks those.
     */
    private val PAGE_AGENT_JS = """
        (function(){
          var VER=$PAGE_AGENT_VERSION;
          if(window.__avaPage&&window.__avaPage.v===VER) return JSON.stringify({ok:true,ready:true});
          var interact=$INTERACT_JS;
          var scrollFn=$SCROLL_JS;
          var gotoFn=$GOTO_JS;
          function run(cmd){
            try{
              cmd=cmd||{};
              var a=cmd.action;
              if(a==='tap'||a==='type'||a==='key')
                return interact(a,+cmd.idx||0,cmd.selector||'',cmd.text||'',cmd.value||'',!!cmd.clear,cmd.key||'',+cmd.repeat||1);
              if(a==='scroll')
                return scrollFn(+cmd.dx||0,+cmd.dy||0,cmd.direction||'down',+cmd.amount||300,+cmd.idx||0,cmd.selector||'',cmd.text||'');
              if(a==='navigate')
                return gotoFn(cmd.path||'');
              if(a==='dialogs')
                return $DIALOG_JS;
              if(a==='path')
                return JSON.stringify({ok:true,href:String(location.href||''),path:String(location.pathname||'')+String(location.search||'')});
              if(a==='ping')
                return JSON.stringify({ok:true,v:VER,dialogOpened:!!(window.__avaPage&&window.__avaPage.dialogOpened),overlayOpened:!!(window.__avaPage&&window.__avaPage.overlayOpened),lastOverlay:window.__avaPage&&window.__avaPage.lastOverlay||null});
              if(a==='state'){
                var id=cmd.entity||'';
                var ha=document.querySelector('home-assistant');
                var st=ha&&ha.hass&&ha.hass.states&&id?ha.hass.states[id]:null;
                if(!st) return JSON.stringify({ok:false,error:'no state'});
                return JSON.stringify({ok:true,entity:id,state:String(st.state),name:(st.attributes&&st.attributes.friendly_name)||''});
              }
              return JSON.stringify({ok:false,error:'unsupported'});
            }catch(e){
              return JSON.stringify({ok:false,error_code:'tool_error',error:String(e&&e.message||e)});
            }
          }
          function hookDialogs(){
            if(window.__avaPageObs) return;
            window.__avaPageObs=true;
            function mark(kind,tag,extra){
              if(!window.__avaPage) return;
              window.__avaPage.dialogOpened=true;
              window.__avaPage.overlayOpened=true;
              var row={kind:kind||'overlay',tag:tag||'',at:Date.now()};
              if(extra){ for(var k in extra) if(extra[k]) row[k]=extra[k]; }
              window.__avaPage.lastOverlay=row;
            }
            function kindOf(tag){ return ${HaFrontendOverlays.KIND_JS}; }
            function isHost(tag){ return ${HaFrontendOverlays.HOST_CHECK}; }
            document.addEventListener('show-dialog',function(ev){
              var d=ev.detail||{};
              var tag=String(d.dialogTag||'');
              var params=d.dialogParams||{};
              mark(kindOf(tag)||'dialog',tag,{entity:params.entityId||params.entity_id||''});
            },true);
            document.addEventListener('hass-more-info',function(ev){
              var d=ev.detail||{};
              mark('more_info','ha-more-info-dialog',{entity:d.entityId||''});
            },true);
            document.addEventListener('opened',function(ev){
              var path=ev.composedPath&&ev.composedPath()||[];
              for(var i=0;i<path.length;i++){
                var tag=String(path[i].tagName||'').toLowerCase();
                if(isHost(tag)){ mark(kindOf(tag),tag,{entity:path[i]._entityId||path[i].entityId||''}); return; }
              }
            },true);
            document.addEventListener('dialog-closed',function(){
              if(!window.__avaPage) return;
              window.__avaPage.dialogOpened=false;
              window.__avaPage.overlayOpened=false;
            },true);
            var ha=document.querySelector('home-assistant');
            var root=ha&&ha.shadowRoot;
            if(!root||!window.MutationObserver) return;
            var mo=new MutationObserver(function(muts){
              for(var i=0;i<muts.length;i++){
                var nodes=muts[i].addedNodes||[];
                for(var j=0;j<nodes.length;j++){
                  var tag=String(nodes[j].tagName||'').toLowerCase();
                  if(isHost(tag)){
                    mark(kindOf(tag),tag,{entity:nodes[j]._entityId||nodes[j].entityId||''});
                    return;
                  }
                }
              }
            });
            mo.observe(root,{childList:true,subtree:true});
          }
          hookDialogs();
          window.__avaPage={v:VER,run:run,dialogOpened:false,overlayOpened:false,lastOverlay:null};
          return JSON.stringify({ok:true,ready:true,installed:true});
        })();
    """.trimIndent()

    internal fun pageAgentSource(): String = PAGE_AGENT_JS

    internal fun interactSource(): String = INTERACT_JS

    internal fun snapshotSource(): String = PAGE_SNAPSHOT_JS

    internal fun dialogSource(): String = DIALOG_JS

    internal fun rememberPathForTest(origin: String) {
        synchronized(pathLock) {
            clearTrailLocked()
            homePath = origin
            herePath = origin
            leftHome = false
        }
    }

    internal fun arriveForTest(path: String) = noteArrival(path)

    internal fun popTrailForTest(): String? = popTrail()

    internal fun originForTest(): String? = synchronized(pathLock) { homePath }

    internal fun hereForTest(): String? = synchronized(pathLock) { herePath }

    internal fun trailForTest(): List<String> = synchronized(pathLock) { trail.toList() }

    internal fun willRestoreForTest(): Boolean = synchronized(pathLock) { leftHome }

    internal fun attachTrailForTest(): JSONObject = attachTrail(JSONObject())
}
