package com.example.ava.webcompat

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime syntax-lowering for custom Lovelace cards on frozen WebViews.
 *
 * Old engines (this device: Chromium 83) cannot PARSE modern syntax — logical
 * assignment `??=` needs 85, private methods 84, class static blocks 94. A card
 * that fails to parse never registers its custom element, so HA flashes red
 * "custom element doesn't exist" cards on every render. No polyfill can help:
 * the code is rejected before it runs.
 *
 * The fix is a build step at runtime: [intercept] catches custom-resource
 * scripts in `shouldInterceptRequest`, scans for syntax the engine lacks, and
 * lowers hits through [OxcTranspiler] (native oxc-transform via JNI,
 * milliseconds per card). Lowered output references `babelHelpers.*`, so the
 * helpers prelude asset is prepended, making every output self-sufficient.
 * Results are disk-cached by URL (HACS's `hacstag` cache-buster makes the URL a
 * perfect version key). Clean files pass through untouched; any failure falls
 * back to the original bytes, which is exactly today's behaviour.
 *
 * Coverage is an EXCLUDE list, not an allow list: integrations register
 * frontend modules under their own paths — UIX serves `/uix/uix.js` via
 * add_extra_js_url (its v8.1.0 bundle ships public class fields: parse-dead
 * below 74, and future builds are free to use newer syntax), qweather serves
 * `/qweather/`, claw_assistant `/api/claw_assistant/` — and cards import CDN
 * dependencies cross-origin. Only HA-core bundles (`/frontend_es5/`,
 * `/frontend_latest/`, `/static/` — already built for old engines, and the
 * bulk of page-load traffic) and service-worker scripts (caching semantics
 * must not be altered) are passed through untouched.
 *
 * Second duty ([interceptMainDocument]): on engines without
 * DOCUMENT_START_SCRIPT the compat pack is spliced into <head> of the HA
 * document itself, because evaluateJavascript at commit/finish loses the race
 * against boot-time renders (see the doc on [needsDocStartFallback]).
 */
class BrowserLegacyTranspiler(
    private val context: Context,
    private val engineMajor: Int,
    private val onNotice: (String) -> Unit = {},
) {

    /** Query-stripped URLs already reported as missing — one notice per resource. */
    private val missingNoticed = ConcurrentHashMap.newKeySet<String>()

    /** babelHelpers prelude; loaded once, empty when the asset is unreadable. */
    private val helpersPrelude: String by lazy {
        try {
            context.assets.open(HELPERS_ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "helpers prelude missing ($HELPERS_ASSET): $e")
            ""
        }
    }

    /**
     * True document-start for every engine this transpiler serves (< 96).
     *
     * The evaluateJavascript fallback (commit + finish) is too late for whatever
     * renders during boot: Lit constructs each component's stylesheet on FIRST
     * render and caches it on the shared CSSResult — sheets parsed before the
     * CSS-compat hooks install have already had their modern selectors/
     * declarations discarded by the old parser, and the broken sheet is then
     * adopted by every later instance. HA restores the last route at startup,
     * so the page the user actually looks at is exactly the one that boots —
     * and stays broken (observed: integrations page, WebView 83).
     *
     * Deliberately NOT gated on isFeatureSupported(DOCUMENT_START_SCRIPT):
     * that flag reflects the support-library boundary interface, not whether
     * the WebView build actually executes registered scripts — trusting it
     * once switched off every delivery channel at the same time. The <head>
     * splice is the only document-start mechanism whose behaviour we fully
     * own; if addDocumentStartJavaScript also works, the window.__ava* guards
     * make the second delivery a no-op.
     */
    private val needsDocStartFallback: Boolean = true

    /** Compat pack injected into <head>; built once (contents are static). */
    private val docStartPrelude: String by lazy {
        BrowserPlatformCompat.pageCompatScripts().joinToString("\n;\n")
    }

    /** Called on shouldInterceptRequest's background thread. Null = let WebView handle it. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (engineMajor <= 0 || engineMajor >= MODERN_MAJOR) return null
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val url = request.url ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        val path = url.path ?: return null
        if (request.isForMainFrame && !path.endsWith(".js")) {
            return interceptMainDocument(request, path)
        }
        if (!path.endsWith(".js")) return null
        if (EXCLUDED_PATH_MARKERS.any { path.contains(it) }) return null
        if (path.startsWith("/sw-") || path == "/service_worker.js") return null

        val urlText = url.toString()
        val cached = cacheFile(urlText)
        if (cached.isFile) {
            return respond(runCatching { cached.readBytes() }.getOrNull() ?: return null)
        }

        val original = fetch(urlText, request.requestHeaders) ?: return null
        val source = String(original, Charsets.UTF_8)
        // Engines below the scan floor miss so much syntax that scanning is
        // pointless — lower everything. Above it, only touch flagged files.
        if (engineMajor >= SCAN_FLOOR_MAJOR && !needsLowering(source)) {
            return respond(original)
        }

        val startMs = System.currentTimeMillis()
        val lowered = transpile(source)
        if (lowered == null) {
            Log.w(TAG, "transpile failed for $urlText — serving original")
            return respond(original)
        }
        runCatching {
            cached.parentFile?.mkdirs()
            cached.writeBytes(lowered.toByteArray(Charsets.UTF_8))
        }
        val tookMs = System.currentTimeMillis() - startMs
        val target = targetMajor()
        Log.i(TAG, "lowered ${url.lastPathSegment} for chrome $target in ${tookMs}ms")
        onNotice(
            "[Ava] card uses syntax newer than this WebView (chrome $engineMajor) — " +
                "transpiled ${url.lastPathSegment} in ${tookMs}ms (cached)"
        )
        return respond(lowered.toByteArray(Charsets.UTF_8))
    }

    /**
     * Serves the HA document with the compat pack at the top of <head> — runs
     * before core.js and every lazy chunk. Fail-open at every step: any miss
     * returns null and the WebView performs the load itself (the commit/finish
     * evaluateJavascript injection stays on as the safety net).
     */
    private fun interceptMainDocument(
        request: WebResourceRequest,
        path: String,
    ): WebResourceResponse? {
        if (!needsDocStartFallback) return null
        // Documents only: HA routes carry no extension; keep .html for panels.
        val last = path.substringAfterLast('/')
        if (last.contains('.') && !last.endsWith(".html", ignoreCase = true)) return null
        val urlText = request.url.toString()
        val (body, contentType) = fetchDocument(urlText, request.requestHeaders) ?: return null
        if (contentType != null && !contentType.contains("text/html", ignoreCase = true)) return null
        // Splicing requires decode + re-encode; anything not provably UTF-8
        // (e.g. GBK sites in the general browser) must load untouched.
        val declaredCharset = contentType
            ?.substringAfter("charset=", "")
            ?.substringBefore(';')?.trim()?.lowercase()
        if (!declaredCharset.isNullOrEmpty() && declaredCharset != "utf-8" && declaredCharset != "utf8") {
            return null
        }
        val html = decodeStrictUtf8(body) ?: return null
        val headOpen = HEAD_OPEN.find(html) ?: return null
        val at = headOpen.range.last + 1
        val merged = buildString(html.length + docStartPrelude.length + 32) {
            append(html, 0, at)
            append("<script>\n").append(docStartPrelude).append("\n</script>")
            append(html, at, html.length)
        }
        Log.i(TAG, "doc-start compat pack injected into ${request.url.lastPathSegment ?: path}")
        return WebResourceResponse(
            "text/html",
            "utf-8",
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(merged.toByteArray(Charsets.UTF_8)),
        )
    }

    /** Strict decode: null on any malformed byte instead of U+FFFD corruption. */
    private fun decodeStrictUtf8(bytes: ByteArray): String? =
        runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

    /**
     * GET the document body + content type. Conditional headers are stripped
     * (a 304 has no body to inject into) and redirects are NOT followed —
     * auth-flow redirects must stay WebView-visible navigations, so any
     * non-200 turns the interception off for that load.
     */
    private fun fetchDocument(
        url: String,
        headers: Map<String, String>?,
    ): Pair<ByteArray, String?>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = FETCH_CONNECT_TIMEOUT_MS
            conn.readTimeout = FETCH_READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            headers?.forEach { (key, value) ->
                val lower = key.lowercase()
                if (lower !in DOC_HEADER_EXCLUDES) conn.setRequestProperty(key, value)
            }
            conn.setRequestProperty("Accept-Encoding", "identity")
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_SOURCE_BYTES) null else (bytes to conn.contentType)
        } catch (e: Exception) {
            Log.w(TAG, "document fetch failed for $url: $e")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun transpile(source: String): String? {
        val lowered = OxcTranspiler.lower(source, "chrome${targetMajor()}") ?: return null
        // Prepending before import statements is legal (imports are hoisted),
        // and the prelude self-guards, so duplication across cards is harmless.
        return if (helpersPrelude.isEmpty()) lowered else helpersPrelude + "\n" + lowered
    }

    private fun needsLowering(source: String): Boolean =
        MODERN_SYNTAX.any { it.containsMatchIn(source) } || hasPrivateMemberDeclaration(source)

    /**
     * Private field/method declarations (`#name =`, `#name;`, `#name(`) need
     * Chromium 84. Anchored on the `{`/`;`/whitespace that precedes them in a
     * class body, then names shaped like hex colors (3/4/6/8 hex chars — CSS
     * embedded in card JS produces `color: #CE3226;` which the anchor alone
     * cannot tell apart) are skipped. Verified against the 10 installed
     * bundles: zero false positives, and misses (a real private named e.g.
     * `#face`) just fall back to today's serve-as-is behaviour.
     */
    private fun hasPrivateMemberDeclaration(source: String): Boolean =
        PRIVATE_MEMBER.findAll(source).any { !HEX_COLOR_SHAPED.matches(it.groupValues[1]) }

    private fun respond(bytes: ByteArray): WebResourceResponse =
        WebResourceResponse(
            "application/javascript",
            "utf-8",
            200,
            "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store",
            ),
            ByteArrayInputStream(bytes),
        )

    private fun fetch(url: String, headers: Map<String, String>?): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = FETCH_CONNECT_TIMEOUT_MS
            conn.readTimeout = FETCH_READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            headers?.forEach { (key, value) ->
                val lower = key.lowercase()
                if (lower != "accept-encoding" && lower != "range") {
                    conn.setRequestProperty(key, value)
                }
            }
            conn.setRequestProperty("Accept-Encoding", "identity")
            val status = conn.responseCode
            if (status != 200) {
                // A missing Lovelace resource (stale HACS registration) fails on any
                // engine — tell the user it's server-side, not a compatibility bug.
                if ((status == 404 || status == 410) && missingNoticed.add(url.substringBefore('?'))) {
                    onNotice(
                        "[Ava] Lovelace resource is missing on the server (HTTP $status): " +
                            url.substringBefore('?') +
                            " — remove the stale resource entry or reinstall the card via HACS"
                    )
                }
                return null
            }
            val body = conn.inputStream.use { it.readBytes() }
            if (body.size > MAX_SOURCE_BYTES) null else body
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed for $url: $e")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun cacheFile(url: String): File =
        File(File(context.cacheDir, CACHE_DIR), sha256("$url|$ENGINE_TAG|c${targetMajor()}") + ".js")

    /** oxc target never goes below the documented Chromium 69 floor. */
    private fun targetMajor(): Int = engineMajor.coerceAtLeast(TARGET_FLOOR_MAJOR)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "AvaLegacyTranspiler"
        private const val CACHE_DIR = "ava_transpile"
        private const val HELPERS_ASSET = "ava_transpile/helpers.js"

        /** Part of the cache key — bump when the native oxc library or helpers change. */
        private const val ENGINE_TAG = "oxc-0.139.0"

        /** Engines at/above this parse every syntax the scan list covers. */
        private const val MODERN_MAJOR = 96

        /** Documented lowering floor — never emit a pre-class `chrome14` target. */
        private const val TARGET_FLOOR_MAJOR = 69

        /** Below this (no `?.`/`??` etc.) scanning is hopeless — lower unconditionally. */
        private const val SCAN_FLOOR_MAJOR = 80

        private const val MAX_SOURCE_BYTES = 6 * 1024 * 1024
        private const val FETCH_CONNECT_TIMEOUT_MS = 8_000
        private const val FETCH_READ_TIMEOUT_MS = 20_000

        /** HA-core bundle paths — pre-built for old engines, never intercepted. */
        private val EXCLUDED_PATH_MARKERS = listOf(
            "/frontend_es5/",
            "/frontend_latest/",
            "/static/",
        )

        /** Opening head tag, attributes allowed. */
        private val HEAD_OPEN = Regex("""<head(\s[^>]*)?>""", RegexOption.IGNORE_CASE)

        /**
         * Never forwarded on document fetches: conditional headers would yield a
         * bodyless 304, and encoding/range alter the bytes we need to rewrite.
         */
        private val DOC_HEADER_EXCLUDES = setOf(
            "accept-encoding", "range", "if-none-match", "if-modified-since",
        )

        /**
         * Syntax that Chromium in [[SCAN_FLOOR_MAJOR], [MODERN_MAJOR]) fails to parse:
         * logical assignment (85), private brand checks (91), class static
         * blocks (94). Private declarations (84) live in [PRIVATE_MEMBER] +
         * [HEX_COLOR_SHAPED] because a plain regex can't reject embedded-CSS hex
         * colors. False positives just cost one cached transpile; misses fall
         * back to today's behaviour.
         */
        private val MODERN_SYNTAX = listOf(
            Regex("""(\?\?=|\|\|=|&&=)"""),
            Regex("""\bstatic\s*\{"""),
            Regex("""#[A-Za-z_$][A-Za-z0-9_$]*\s+in\s"""),
        )

        private val PRIVATE_MEMBER = Regex("""[{};\s]#([A-Za-z_$][A-Za-z0-9_$]*)\s*[=;(]""")
        private val HEX_COLOR_SHAPED = Regex("""[0-9a-fA-F]{3,8}""")
    }
}
