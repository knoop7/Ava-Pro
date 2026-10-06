package com.example.ava.localllm.remote

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.ava.homeassistant.HaManager
import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolArgumentCase
import com.example.ava.localllm.ToolDef
import com.example.ava.localllm.ToolParam
import com.example.ava.localllm.ToolParamType
import com.example.ava.services.AiBrowserUrlPolicy
import com.example.ava.services.AiBrowserService
import com.example.ava.services.OverlayZOrderCoordinator
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.webcompat.EngineCapabilities
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Ava-owned web surface. Mini order, isolated from the HA dashboard
 * ([com.example.ava.services.WebViewService]): hide/reset → show
 * [AiBrowserService] → wait load → read text → hide when done.
 * Gecko pack / no overlay permission → empty schema.
 */
object AvaBrowserTools {

    const val SEARCH = "ava_web_search"
    const val OPEN = "ava_web_open"
    const val READ = "ava_web_read"
    const val HIDE = "ava_web_hide"
    const val ACT = "ava_web_act"

    const val PREFIX = "ava_web_"

    private const val TEXT_CAP = 1_500
    private const val SNIPPET_CAP = 160
    private const val HIT_CAP = 8
    private const val CACHE_CAP = 40

    private val cacheLock = Any()
    private val urls = LinkedHashMap<Int, String>(16, 0.75f, true)
    private var nextN = 1

    @Volatile private var userIsLooking = false

    fun beginTurn() {
        if (!AiBrowserService.isShowing()) userIsLooking = false
    }

    /** Host-generated state only: page text and titles stay in restricted tool results. */
    fun sessionState(): JSONObject = JSONObject()
        .put("open", AiBrowserService.isShowing()).put("keep_visible", userIsLooking)
        .put("clipboard_available", copiedText != null)

    private fun nextRead(mode: String = "article", offset: Int? = null): JSONObject = JSONObject()
        .put("tool", READ).put("arguments", JSONObject().put("mode", mode).also { args -> offset?.let { args.put("offset", it) } })

    private fun finishHint(): String = if (userIsLooking) "Keep the page visible for the user; speak when the requested work is done."
        else "When the requested work is done, call ava_web_hide before speaking."

    suspend fun ready(app: Context): Boolean {
        if (EngineCapabilities.GECKO_BUNDLED) return false
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) return false
        return true
    }

    fun surface(ready: Boolean): HaToolSet {
        if (!ready) return HaToolSet(emptyList())
        val display = AvaPublishedEntities.browserDisplayOn() != null
        return HaToolSet(
            listOf(
                ToolDef(
                    SEARCH,
                    "Search the web on the research page. Returns numbered hits (title, host, snippet). Use snippets if sufficient; otherwise open the chosen n. Keep pages requested for display; close temporary research when done. Do not guess URLs." +
                        if (display) " Not this device's browser display overlay." else "",
                    listOf(
                        ToolParam("query", ToolParamType.Str, "search words as spoken", required = true),
                        ToolParam("keep", ToolParamType.Bool, "whether to leave the page visible for the user; default preserves the current setting", required = false),
                        ToolParam(
                            "engine",
                            ToolParamType.Enum(listOf("auto", "ddg", "bing", "baidu")),
                            "default auto (zh→bing, else DuckDuckGo html)",
                            required = false,
                        ),
                    ),
                ),
                ToolDef(
                    OPEN,
                    "Open a page on the research page. Pass n from the last search, or an http(s) url. Read after opening. Preserve display pages; close temporary research when done. Navigation preserves history. If that page is already open, do not hide and reopen.",
                    listOf(
                        ToolParam("n", ToolParamType.Int(1, Int.MAX_VALUE), "hit number from ava_web_search", required = false),
                        ToolParam("url", ToolParamType.Str, "http or https url", required = false),
                        ToolParam(
                            "keep",
                            ToolParamType.Bool,
                            "true when the user wants the page displayed; false for temporary research. Omit to preserve the current setting (initially false).",
                            required = false,
                        ),
                    ),
                    argumentCases = listOf(
                        ToolArgumentCase(fields = setOf("n", "url", "keep"), exactlyOne = setOf("n", "url")),
                    ),
                ),
                ToolDef(
                    READ,
                    "Read the current page as text. article=main body slice, links=numbered in-page links. Use offset to continue. When finished, keep display pages visible or close temporary research. Never dumps the whole page.",
                    listOf(
                        ToolParam(
                            "mode",
                            ToolParamType.Enum(listOf("article", "links", "viewport", "elements")),
                            "article=body slice; viewport=visible text; elements=visible interactive refs. Default article",
                            required = false,
                        ),
                        ToolParam("offset", ToolParamType.Int(0, 200_000), "character offset for article", required = false),
                        ToolParam("limit", ToolParamType.Int(200, TEXT_CAP), "max characters, default 1500", required = false),
                    ),
                ),
                ToolDef(
                    ACT,
                    "Control the research page: navigate, scroll, click a ref from read mode=elements, input text, copy a ref into the page clipboard, or paste it into an editable ref. No system clipboard access. Read elements again after page changes. Input replaces the field; it does not submit.",
                    listOf(
                        ToolParam(
                            "action",
                            ToolParamType.Enum(listOf("refresh", "back", "forward", "scroll", "click", "input", "copy", "paste")),
                            "refresh/back/forward/scroll, or click/input/copy/paste using an element ref",
                            required = true,
                        ),
                        ToolParam("ref", ToolParamType.Str, "element ref from the latest elements read", required = false),
                        ToolParam("text", ToolParamType.Str, "text for input, or explicit text to copy (max 4000 characters)", required = false),
                        ToolParam(
                            "direction",
                            ToolParamType.Enum(listOf("up", "down", "top", "bottom")),
                            "for scroll: down=one screen, up=one screen, top/bottom=jump. Default down",
                            required = false,
                        ),
                        ToolParam(
                            "amount",
                            ToolParamType.Int(1, 20_000),
                            "for scroll: pixels. Omit for one viewport",
                            required = false,
                        ),
                    ),
                    argumentCases = listOf(
                        ToolArgumentCase(action = "refresh", fields = emptySet()),
                        ToolArgumentCase(action = "back", fields = emptySet()),
                        ToolArgumentCase(action = "forward", fields = emptySet()),
                        ToolArgumentCase(action = "scroll", fields = setOf("direction", "amount")),
                        ToolArgumentCase(action = "click", fields = setOf("ref"), required = setOf("ref")),
                        ToolArgumentCase(action = "input", fields = setOf("ref", "text"), required = setOf("ref", "text")),
                        ToolArgumentCase(action = "copy", fields = setOf("ref", "text"), exactlyOne = setOf("ref", "text")),
                        ToolArgumentCase(action = "paste", fields = setOf("ref"), required = setOf("ref")),
                    ),
                ),
                ToolDef(
                    HIDE,
                    "Close the research page only. Does not hide the Home Assistant dashboard browser. If already closed, this is a no-op.",
                    emptyList(),
                ),
            ),
        )
    }

    suspend fun execute(call: AvaToolCallback.Call, app: Context): AvaToolCallback.Result {
        if (EngineCapabilities.GECKO_BUNDLED) {
            return AvaToolCallback.fail("browser_engine_unsupported", "AI browser is not on this engine.")
        }
        return when (call.name) {
            SEARCH -> search(call.arguments, app)
            OPEN -> open(call.arguments, app)
            READ -> read(call.arguments, app)
            ACT -> act(call.arguments, app)
            HIDE -> hide(app)
            else -> AvaToolCallback.fail("not_found", "Tool not available: ${call.name}")
        }
    }

    private suspend fun search(args: JSONObject, app: Context): AvaToolCallback.Result {
        val query = args.optString("query").trim()
        if (query.length < 2) return AvaToolCallback.fail("invalid_request", "query is too short")
        val keepVisible = args.optBoolean("keep", userIsLooking)
        val engine = args.optString("engine").trim().ifEmpty { "auto" }
        val url = searchUrl(query, engine, app)
        val loaded = (if (AiBrowserService.isShowing()) AiBrowserService.openAndAwait(app, url) else openFresh(app, url))
            ?: return AvaToolCallback.fail("tool_error", "search page did not load")
        userIsLooking = keepVisible
        delay(700)
        val raw = AiBrowserService.evaluateJavascript(SERP_JS)
        val parsed = parseHits(raw)
        if (parsed.isEmpty()) {
            return AvaToolCallback.fail("not_found", "no search hits on ${hostOf(loaded)}")
        }
        val out = JSONArray()
        for (hit in parsed) {
            val n = remember(hit.url)
            out.put(
                JSONObject()
                    .put("n", n)
                    .put("title", hit.title)
                    .put("host", hostOf(hit.url))
                    .put("snippet", hit.snippet),
            )
        }
        val body = JSONObject()
            .put("query", query)
            .put("engine", engine)
            .put("count", out.length())
            .put("results", out)
            .put("keep_visible", userIsLooking)
            .put("hint", "If the snippets answer the request, finish. Otherwise open the chosen result using its n. " + finishHint())
        AvaOverlayReceipts.awaitAndAttach(body, AvaOverlayReceipts.RESEARCH, true)
        return AvaToolCallback.ok(body)
    }

    private suspend fun open(args: JSONObject, app: Context): AvaToolCallback.Result {
        val n = if (args.has("n") && args.opt("n") != JSONObject.NULL) args.optInt("n") else 0
        val rawUrl = args.optString("url").trim()
        val target = when {
            n > 0 -> lookup(n) ?: return AvaToolCallback.fail("not_found", "unknown hit $n")
            rawUrl.isNotEmpty() -> normalizeUrl(rawUrl)
                ?: return AvaToolCallback.fail("invalid_request", "url must be http or https")
            else -> return AvaToolCallback.fail("invalid_request", "give n or url")
        }
        rejectUrl(target, app)?.let { return it }
        val keepVisible = args.optBoolean("keep", userIsLooking)
        val loaded = if (AiBrowserService.isShowing()) {
            AiBrowserService.openAndAwait(app, target)
        } else {
            openFresh(app, target)
        } ?: return AvaToolCallback.fail("tool_error", "page did not load")
        rejectUrl(loaded, app)?.let { return it }
        userIsLooking = keepVisible
        delay(400)
        val article = AiBrowserService.extractArticle()
        article?.let { rejectUrl(it.url, app)?.let { failure -> return failure } }
        val title = article?.title?.takeIf { it.isNotBlank() } ?: hostOf(loaded)
        val excerpt = article?.excerpt?.trim().orEmpty().take(SNIPPET_CAP)
        remember(loaded)
        val body = JSONObject()
            .put("title", title)
            .put("host", hostOf(loaded))
            .put("excerpt", excerpt)
            .put("keep_visible", userIsLooking)
            .put("hint", "Read the page to answer a question; use mode=elements to interact. " + finishHint())
            .put("next_action", nextRead())
        AvaOverlayReceipts.awaitAndAttach(body, AvaOverlayReceipts.RESEARCH, true)
        return AvaToolCallback.ok(body)
    }

    private suspend fun read(args: JSONObject, app: Context): AvaToolCallback.Result {
        if (!AiBrowserService.awaitReady()) return AvaToolCallback.fail("navigation_failed", "page is not ready")
        val url = AiBrowserService.hostUrl()
        if (url.isBlank()) return AvaToolCallback.fail("no_session", "AI browser is not open.")
        rejectUrl(url, app)?.let { return it }
        val mode = args.optString("mode").trim().ifEmpty { "article" }
        return when (mode) {
            "links" -> readLinks()
            "viewport" -> AiBrowserService.viewport()?.let { AvaToolCallback.ok(JSONObject(it).put("keep_visible", userIsLooking).put("hint", finishHint())) }
                ?: AvaToolCallback.fail("tool_error", "viewport unavailable")
            "elements" -> interaction("elements", args)
            else -> readArticle(args, url, app)
        }
    }

    private suspend fun readArticle(args: JSONObject, url: String, app: Context): AvaToolCallback.Result {
        val article = AiBrowserService.extractArticle()
            ?: return AvaToolCallback.fail("tool_error", "could not read this page")
        rejectUrl(article.url, app)?.let { return it }
        val full = article.textContent.trim()
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val limit = args.optInt("limit", TEXT_CAP).coerceIn(200, TEXT_CAP)
        if (offset >= full.length) {
            return AvaToolCallback.ok(JSONObject().put("text", "").put("offset", offset).put("next", JSONObject.NULL)
                .put("end", true).put("truncated", false).put("keep_visible", userIsLooking).put("hint", finishHint()))
        }
        val slice = full.drop(offset).take(limit)
        val next = offset + slice.length
        return AvaToolCallback.ok(
            JSONObject()
                .put("title", article.title)
                .put("host", hostOf(url))
                .put("text", slice)
                .put("offset", offset)
                .put("next", if (next < full.length) next else JSONObject.NULL)
                .put("truncated", next < full.length)
                .put("keep_visible", userIsLooking)
                .put("hint", finishHint())
                .also { if (next < full.length) it.put("next_action", nextRead(offset = next)) },
        )
    }

    private suspend fun readLinks(): AvaToolCallback.Result {
        val raw = AiBrowserService.evaluateJavascript(LINKS_JS)
        val parsed = parseHits(raw)
        if (parsed.isEmpty()) return AvaToolCallback.fail("not_found", "no links on this page")
        val out = JSONArray()
        for (hit in parsed.take(15)) {
            val n = remember(hit.url)
            out.put(
                JSONObject()
                    .put("n", n)
                    .put("title", hit.title)
                    .put("host", hostOf(hit.url)),
            )
        }
        return AvaToolCallback.ok(
            JSONObject()
                .put("count", out.length())
                .put("links", out)
                .put("keep_visible", userIsLooking)
                .put("hint", "Open the chosen link with its n when needed. " + finishHint()),
        )
    }

    /** Mini: hide this overlay, then SHOW, then wait. Never touches HA WebView. */
    private suspend fun openFresh(app: Context, url: String): String? {
        check(AiBrowserService.hideAndAwait(app)) { "browser did not close" }
        copiedText = null
        elementUrl = ""
        return AiBrowserService.openAndAwait(app, url)
    }

    private suspend fun hide(app: Context): AvaToolCallback.Result {
        userIsLooking = false
        copiedText = null
        elementUrl = ""
        if (!AiBrowserService.isShowing()) {
            val body = JSONObject().put("hidden", true).put("was_open", false)
            AvaOverlayReceipts.attach(body, AvaOverlayReceipts.RESEARCH, false)
            return AvaToolCallback.ok(body, status = "applied")
        }
        putAway(app)
        val body = JSONObject().put("hidden", true).put("was_open", true)
        AvaOverlayReceipts.awaitAndAttach(body, AvaOverlayReceipts.RESEARCH, false)
        return AvaToolCallback.ok(body, status = "applied")
    }

    /**
     * Host cleanup after the turn. The model is told to hide, but voice turns
     * often speak and skip the call. Wait until the window is gone so TTS
     * does not start on top of a leftover page.
     */
    suspend fun closeIfLeft(app: Context): Boolean {
        if (userIsLooking) return false
        if (!AiBrowserService.isShowing()) return false
        putAway(app)
        return true
    }

    private suspend fun putAway(app: Context) {
        copiedText = null
        elementUrl = ""
        check(AiBrowserService.hideAndAwait(app)) { "browser did not close" }
        OverlayZOrderCoordinator.reassertForegroundOverlaysNow(app)
    }

    private suspend fun act(args: JSONObject, app: Context): AvaToolCallback.Result {
        if (!AiBrowserService.isShowing()) {
            return AvaToolCallback.fail("no_session", "AI browser is not open.")
        }
        rejectUrl(AiBrowserService.hostUrl(), app)?.let { return it }
        return when (args.optString("action").trim()) {
            "refresh", "back", "forward" -> {
                val action = args.optString("action")
                val loaded = AiBrowserService.navigateAndAwait(action)
                    ?: return AvaToolCallback.fail("navigation_failed", "No history, blocked page, or navigation did not finish")
                rejectUrl(loaded, app)?.let { return it }
                AvaToolCallback.ok(JSONObject().put("action", action).put("host", hostOf(loaded)).put("keep_visible", userIsLooking).put("next_action", nextRead("viewport")))
            }
            "click", "input", "copy", "paste" -> interaction(args.optString("action"), args)
            "scroll" -> {
                val direction = args.optString("direction").trim().ifEmpty { "down" }
                val amount = if (args.has("amount") && args.opt("amount") != JSONObject.NULL) {
                    args.optInt("amount")
                } else {
                    0
                }
                val moved = AiBrowserService.scroll(direction, amount)
                    ?: return AvaToolCallback.fail("tool_error", "could not scroll this page")
                AvaToolCallback.ok(
                    moved
                        .put("action", "scroll")
                        .put("direction", direction)
                        .put(
                            "hint",
                            "Read mode=viewport for text at the new position. " + finishHint(),
                        )
                        .put("next_action", nextRead("viewport")), status = "applied",
                )
            }
            else -> AvaToolCallback.fail("invalid_request", "unsupported browser action; use one from the tool schema")
        }
    }

    // Deliberately scoped to this browser session; never reads the Android clipboard.
    @Volatile private var copiedText: String? = null
    private var elementGeneration = 0L
    @Volatile private var elementUrl = ""

    fun onBrowserClosed() {
        copiedText = null
        elementUrl = ""
        userIsLooking = false
    }

    private suspend fun interaction(action: String, args: JSONObject): AvaToolCallback.Result {
        if (!AiBrowserService.awaitReady()) return AvaToolCallback.fail("navigation_failed", "page is not ready")
        val url = AiBrowserService.hostUrl()
        if (action == "copy" && args.has("text")) {
            val text = args.optString("text")
            if (text.length > 4000) return AvaToolCallback.fail("invalid_request", "text exceeds 4000 characters")
            copiedText = text
            return AvaToolCallback.ok(JSONObject().put("characters", copiedText.orEmpty().length).put("clipboard", "browser_only"), status = "applied")
        }
        if (action == "elements") {
            elementGeneration++
            elementUrl = url
        } else if (url != elementUrl || elementUrl.isBlank()) {
            return AvaToolCallback.fail("stale_ref", "Read elements on this page first")
        }
        val text = if (action == "paste") copiedText ?: return AvaToolCallback.fail("clipboard_empty", "Copy text before pasting; input replaces a field with explicit text") else args.optString("text")
        if (text.length > 4000) return AvaToolCallback.fail("invalid_request", "text exceeds 4000 characters")
        if (action == "input" && !args.has("text")) return AvaToolCallback.fail("invalid_request", "text is required")
        val raw = AiBrowserService.evaluateJavascript(
            "(" + INTERACTION_JS + ")(" + JSONObject.quote(action) + "," +
                JSONObject.quote(args.optString("ref")) + "," + JSONObject.quote(text) + "," + elementGeneration + ")",
        ) ?: return AvaToolCallback.fail("tool_error", "page did not respond")
        val result = runCatching { JSONObject(raw) }.getOrNull()
            ?: return AvaToolCallback.fail("tool_error", "invalid page response")
        if (!result.optBoolean("ok")) return AvaToolCallback.fail(result.optString("error_code", "invalid_request"), result.optString("error"))
        result.remove("ok")
        result.remove("next_action")
        if (action == "copy") {
            copiedText = result.optString("text").take(4000)
            result.remove("text")
            result.put("characters", copiedText.orEmpty().length)
        }
        if (action in setOf("click", "input", "paste")) {
            elementUrl = ""
            result.put("requires_observation", true).put("next_action", nextRead("elements"))
        }
        result.put("keep_visible", userIsLooking).put("hint", finishHint())
        return AvaToolCallback.ok(result, status = when (action) {
            "click" -> "accepted"
            "input", "paste", "copy" -> "applied"
            else -> "observed"
        })
    }

    private val INTERACTION_JS = """
        function(action, ref, text, generation) {
          function visible(e) {
            var r=e.getBoundingClientRect(), s=getComputedStyle(e);
            return r.width>0 && r.height>0 && s.visibility!=='hidden' && s.display!=='none' && s.opacity!=='0'
              && r.bottom>0 && r.top<innerHeight && r.right>0 && r.left<innerWidth;
          }
          function sensitive(e) { return e.matches('input[type=password],input[type=file],input[type=hidden]'); }
          function editable(e) {
            return !e.disabled && !e.readOnly && e.matches('input:not([type]),input[type=text],input[type=search],input[type=email],input[type=url],input[type=tel],input[type=number],textarea,[contenteditable=true]');
          }
          function label(e) {
            var labelled=(e.getAttribute('aria-labelledby')||'').split(/\s+/).filter(Boolean).map(function(id){
              var n=document.getElementById(id); return n ? n.textContent : '';
            }).join(' ').trim();
            var labels=Array.from(e.labels||[]).map(function(l){return l.textContent||'';}).join(' ').trim();
            return (labelled||e.getAttribute('aria-label')||labels||e.innerText||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('name')||'').trim().slice(0,160);
          }
          if(action==='elements') {
            var all=Array.from(document.querySelectorAll('a[href],button,input,textarea,select,[role=button],[contenteditable=true]'))
              .filter(function(e){return visible(e) && !sensitive(e);});
            var nodes=all.slice(0,60);
            window.__avaElements={generation:generation, nodes:nodes};
            return JSON.stringify({ok:true,title:document.title,snapshot:generation,count:nodes.length,truncated:all.length>nodes.length,elements:nodes.map(function(e,i){
              var item={ref:generation+':'+i,tag:e.tagName.toLowerCase(),type:e.type||'',label:label(e),
                disabled:!!e.disabled,readonly:!!e.readOnly,editable:editable(e)};
              if(e.matches('input,textarea,[contenteditable=true]')) item.value=String(e.isContentEditable?e.textContent:e.value||'').slice(0,500);
              if(e.matches('input[type=checkbox],input[type=radio]')) item.checked=!!e.checked;
              if(e.tagName==='SELECT') item.options=Array.from(e.options||[]).slice(0,30).map(function(o){return {label:o.text,selected:o.selected,disabled:o.disabled};});
              if(e.href) { try { var u=new URL(e.href,document.baseURI);item.link_host=u.host;item.link_path=u.pathname.slice(0,256); } catch(ignore){} }
              return item;
            })});
          }
          var state=window.__avaElements, parts=ref.split(':');
          if(!state || state.generation!==generation || parts[0]!==String(generation) || !/^\d+$/.test(parts[1]||''))
            return JSON.stringify({ok:false,error_code:'stale_ref',error:'stale ref; read elements again'});
          var e=state.nodes[Number(parts[1])];
          if(!e || !e.isConnected || !visible(e) || sensitive(e) || e.disabled)
            return JSON.stringify({ok:false,error_code:'stale_ref',error:'element unavailable; read elements again'});
          if(action==='copy') return JSON.stringify({ok:true,text:(e.value||e.innerText||'').slice(0,4000)});
          if(action==='click') { e.click(); return JSON.stringify({ok:true,action:action,next_action:'read elements or viewport to verify the result'}); }
          if(action==='input' || action==='paste') {
            if(!editable(e))
              return JSON.stringify({ok:false,error:'not an editable text field'});
            e.focus();
            if(e.isContentEditable) e.textContent=text;
            else {
              var proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
              Object.getOwnPropertyDescriptor(proto,'value').set.call(e,text);
            }
            e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
            var applied=String(e.isContentEditable?e.textContent:e.value);
            if(applied!==text) return JSON.stringify({ok:false,error_code:'not_applied',error:'field rejected or normalized the supplied text'});
            return JSON.stringify({ok:true,action:action,value:applied.slice(0,500)});
          }
          return JSON.stringify({ok:false,error:'unsupported action'});
        }
    """.trimIndent()

    private fun searchUrl(query: String, engine: String, app: Context): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val pick = when (engine) {
            "ddg" -> "ddg"
            "bing" -> "bing"
            "baidu" -> "baidu"
            else -> {
                val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    app.resources.configuration.locales[0] ?: Locale.getDefault()
                } else {
                    @Suppress("DEPRECATION")
                    app.resources.configuration.locale ?: Locale.getDefault()
                }
                if (tag.language.equals("zh", ignoreCase = true)) "bing" else "ddg"
            }
        }
        return when (pick) {
            "baidu" -> "https://www.baidu.com/s?wd=$encoded"
            "bing" -> "https://www.bing.com/search?q=$encoded"
            else -> "https://html.duckduckgo.com/html/?q=$encoded"
        }
    }

    private fun rejectUrl(url: String, app: Context): AvaToolCallback.Result? {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: return AvaToolCallback.fail("invalid_request", "bad url")
        val scheme = uri.scheme.orEmpty().lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            return AvaToolCallback.fail("invalid_request", "url must be http or https")
        }
        val host = uri.host.orEmpty()
        if (host.isEmpty()) return AvaToolCallback.fail("invalid_request", "url has no host")
        if (!AiBrowserUrlPolicy.allows(url)) {
            return AvaToolCallback.fail("tool_call_blocked", "Only public http(s) pages are allowed")
        }
        if (isHaHost(host, app)) {
            return AvaToolCallback.fail("tool_call_blocked", "Home Assistant pages stay on ha_* tools")
        }
        return null
    }

    private fun isHaHost(host: String, app: Context): Boolean {
        val ha = HaManager.get()?.settingsStore?.getCached()?.serverUrl.orEmpty()
        val local = runCatching {
            VoiceSatelliteSettingsStore(app.voiceSatelliteSettingsStore).getCached().haRemoteUrl
        }.getOrDefault("")
        return listOf(ha, local).any { raw ->
            val other = runCatching { Uri.parse(raw).host }.getOrNull().orEmpty()
            other.isNotEmpty() && host.equals(other, ignoreCase = true)
        }
    }

    private fun normalizeUrl(raw: String): String? {
        val text = raw.trim()
        val withScheme = if (text.contains("://")) text else "https://$text"
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme.orEmpty().lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return withScheme
    }

    private fun remember(url: String): Int = synchronized(cacheLock) {
        urls.entries.firstOrNull { it.value == url }?.let { return it.key }
        while (urls.size >= CACHE_CAP) {
            val first = urls.keys.firstOrNull() ?: break
            urls.remove(first)
        }
        val n = nextN++
        urls[n] = url
        n
    }

    private fun lookup(n: Int): String? = synchronized(cacheLock) { urls[n] }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull().orEmpty()

    private data class Hit(val title: String, val url: String, val snippet: String)

    private fun parseHits(raw: String?): List<Hit> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || !text.startsWith("[")) return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Hit>()
        for (i in 0 until arr.length()) {
            if (out.size >= HIT_CAP) break
            val item = arr.optJSONObject(i) ?: continue
            val href = item.optString("url").trim()
            val title = item.optString("title").trim()
            if (href.isEmpty() || title.isEmpty()) continue
            if (normalizeUrl(href) == null) continue
            out += Hit(title = title.take(120), url = href, snippet = item.optString("snippet").trim().take(SNIPPET_CAP))
        }
        return out
    }

    private const val SERP_JS = """
        (function(){
          function abs(h){ try { return new URL(h, location.href).href; } catch(e){ return h; } }
          var out = [];
          function push(a, title, snip){
            if (!a || out.length >= 8) return;
            var href = a.getAttribute('href') || '';
            if (!href || href.charAt(0)==='#' || href.indexOf('javascript:')===0) return;
            href = abs(href);
            title = String(title || a.textContent || '').replace(/\s+/g,' ').trim();
            if (title.length < 2) return;
            var host; try { host = new URL(href).host; } catch(e){ host = ''; }
            if (!host) return;
            out.push({title: title, url: href, snippet: String(snip||'').replace(/\s+/g,' ').trim().slice(0,160)});
          }
          document.querySelectorAll('.result__a').forEach(function(a){
            var sn = a.closest('.result');
            push(a, a.textContent, sn && sn.querySelector('.result__snippet') ? sn.querySelector('.result__snippet').textContent : '');
          });
          document.querySelectorAll('li.b_algo h2 a').forEach(function(a){
            var li = a.closest('li.b_algo');
            var p = li ? li.querySelector('.b_caption p, p') : null;
            push(a, a.textContent, p ? p.textContent : '');
          });
          document.querySelectorAll('h3.t a, .c-title a').forEach(function(a){
            var wrap = a.closest('.c-container') || a.parentElement;
            var ab = wrap ? wrap.querySelector('.c-abstract') : null;
            push(a, a.textContent, ab ? ab.textContent : '');
          });
          if (out.length === 0) {
            document.querySelectorAll('a[href]').forEach(function(a){
              if (out.length >= 8) return;
              var t = String(a.textContent||'').replace(/\s+/g,' ').trim();
              if (t.length < 12) return;
              push(a, t, '');
            });
          }
          return JSON.stringify(out);
        })();
    """

    private const val LINKS_JS = """
        (function(){
          function abs(h){ try { return new URL(h, location.href).href; } catch(e){ return h; } }
          var seen = Object.create(null);
          var out = [];
          document.querySelectorAll('a[href]').forEach(function(a){
            if (out.length >= 15) return;
            var href = a.getAttribute('href') || '';
            if (!href || href.charAt(0)==='#' || href.indexOf('javascript:')===0) return;
            href = abs(href);
            if (seen[href]) return;
            var title = String(a.textContent||'').replace(/\s+/g,' ').trim();
            if (title.length < 2) return;
            var host; try { host = new URL(href).host; } catch(e){ host = ''; }
            if (!host) return;
            seen[href] = true;
            out.push({title: title.slice(0,120), url: href, snippet: ''});
          });
          return JSON.stringify(out);
        })();
    """
}
