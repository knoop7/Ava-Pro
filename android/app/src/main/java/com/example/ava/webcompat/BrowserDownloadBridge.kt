package com.example.ava.webcompat

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Bridges programmatic `<a download>` clicks to [BrowserDownloadHandler].
 *
 * Android WebView's [android.webkit.DownloadListener] is not fired for in-page `data:` / `blob:`
 * downloads (e.g. WebRTC camera snapshot). We patch [HTMLAnchorElement.prototype.click] at
 * document start so those saves reach native code.
 */
class BrowserDownloadBridge(private val context: Context) {

    @JavascriptInterface
    fun save(dataUrl: String, fileName: String?) {
        val name = fileName?.trim().orEmpty()
        val disposition = if (name.isNotEmpty()) "attachment; filename=\"$name\"" else null
        BrowserDownloadHandler.handle(context, dataUrl, null, disposition, null)
    }

    companion object {
        const val JS_BRIDGE_NAME = "AvaBrowserDownload"
        /** GeckoView has no JavascriptInterface — large payloads use this navigation hook instead. */
        const val DOWNLOAD_NAV_SCHEME = "ava-download://save"

        private val DOWNLOAD_INTERCEPT_JS = """
            (function() {
              if (window.__avaDownloadIntercept) return;
              window.__avaDownloadIntercept = true;
              function bridgeSave(href, name) {
                try {
                  if (window.$JS_BRIDGE_NAME && window.$JS_BRIDGE_NAME.save) {
                    window.$JS_BRIDGE_NAME.save(href, name || '');
                    return true;
                  }
                } catch (e) {}
                try {
                  var payload = encodeURIComponent(JSON.stringify({h: href, n: name || ''}));
                  if (payload.length < 1500000) {
                    window.location.href = '$DOWNLOAD_NAV_SCHEME#' + payload;
                    return true;
                  }
                } catch (e2) {}
                return false;
              }
              function handleBlob(href, name) {
                try {
                  fetch(href).then(function(r) { return r.blob(); }).then(function(blob) {
                    var reader = new FileReader();
                    reader.onload = function() { bridgeSave(reader.result, name); };
                    reader.readAsDataURL(blob);
                  }).catch(function() {});
                  return true;
                } catch (e) {}
                return false;
              }
              function interceptAnchor(anchor) {
                if (!anchor || !anchor.hasAttribute || !anchor.hasAttribute('download')) return false;
                var href = anchor.href || anchor.getAttribute('href') || '';
                var name = anchor.getAttribute('download') || '';
                if (!href) return false;
                if (href.indexOf('data:') === 0) return bridgeSave(href, name);
                if (href.indexOf('blob:') === 0) return handleBlob(href, name);
                return false;
              }
              var origClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function() {
                if (interceptAnchor(this)) return;
                return origClick.apply(this, arguments);
              };
              document.addEventListener('click', function(event) {
                try {
                  var path = event.composedPath ? event.composedPath() : [];
                  for (var i = 0; i < path.length; i++) {
                    var el = path[i];
                    if (el && el.tagName === 'A' && interceptAnchor(el)) {
                      event.preventDefault();
                      event.stopImmediatePropagation();
                      return;
                    }
                  }
                } catch (e) {}
              }, true);
            })();
        """.trimIndent()

        fun installOnWebView(webView: WebView, bridge: BrowserDownloadBridge) {
            webView.addJavascriptInterface(bridge, JS_BRIDGE_NAME)
            // Best-effort: the boundary can claim support the WebView build
            // doesn't honor, so this is never the sole channel (see below).
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(webView, DOWNLOAD_INTERCEPT_JS, setOf("*"))
                }
            }
        }

        /**
         * Per-navigation install, deliberately unconditional: the script
         * self-guards (__avaDownloadIntercept), so re-evaluation is a no-op,
         * while trusting isFeatureSupported() once left it entirely uninstalled
         * on engines whose boundary lied.
         */
        fun ensureInstalledOnPage(webView: WebView) {
            webView.evaluateJavascript(DOWNLOAD_INTERCEPT_JS, null)
        }

        /** Same intercept script for GeckoView (injected after page load). */
        fun downloadInterceptScript(): String = DOWNLOAD_INTERCEPT_JS

        /** Handle `ava-download://save#...` navigation from the injected download script (Gecko path). */
        fun handleNavigationUri(context: Context, uri: String): Boolean {
            if (!uri.startsWith(DOWNLOAD_NAV_SCHEME)) return false
            return try {
                val payload = uri.substringAfter('#', "")
                if (payload.isEmpty()) return true
                val json = org.json.JSONObject(java.net.URLDecoder.decode(payload, "UTF-8"))
                val dataUrl = json.getString("h")
                val fileName = json.optString("n", "")
                val disposition = if (fileName.isNotBlank()) "attachment; filename=\"$fileName\"" else null
                BrowserDownloadHandler.handle(context, dataUrl, null, disposition, null)
                true
            } catch (e: Exception) {
                android.util.Log.e("BrowserDownloadBridge", "Failed nav-scheme download", e)
                true
            }
        }
    }
}
