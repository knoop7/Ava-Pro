package com.example.ava.webmonkey

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.ava.ui.AvaToast
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class GmApi(
    private val context: Context,
    private val webView: WebView,
    private val scriptNamespace: String
) {
    companion object {
        const val TAG = "GmApi"
        const val JS_BRIDGE_NAME = "GM_API"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("gm_values_$scriptNamespace", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @JavascriptInterface
    fun log(message: String) {
        Log.d(TAG, "[$scriptNamespace] $message")
    }

    @JavascriptInterface
    fun getValue(key: String, defaultValue: String?): String? {
        return prefs.getString(key, defaultValue)
    }

    @JavascriptInterface
    fun setValue(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    @JavascriptInterface
    fun deleteValue(key: String) {
        prefs.edit().remove(key).apply()
    }

    @JavascriptInterface
    fun listValues(): String {
        val keys = prefs.all.keys.toList()
        return keys.joinToString(",")
    }

    @JavascriptInterface
    fun addStyle(css: String) {
        mainHandler.post {
            val js = """
                (function() {
                    var style = document.createElement('style');
                    style.type = 'text/css';
                    style.textContent = ${JSONObject.quote(css)};
                    (document.head || document.documentElement).appendChild(style);
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }

    @JavascriptInterface
    fun toast(message: String, duration: Int) {
        mainHandler.post {
            AvaToast.show(context, message, tag = "gm", durationMs = if (duration > 0) AvaToast.LONG_MS else AvaToast.SHORT_MS)
        }
    }

    @JavascriptInterface
    fun xmlHttpRequest(method: String, url: String, headers: String?, data: String?, callbackId: String) {
        scope.launch {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                
                if (!headers.isNullOrEmpty()) {
                    try {
                        val headersJson = JSONObject(headers)
                        headersJson.keys().forEach { key ->
                            connection.setRequestProperty(key, headersJson.getString(key))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse headers", e)
                    }
                }
                
                if (!data.isNullOrEmpty() && (method == "POST" || method == "PUT")) {
                    connection.doOutput = true
                    connection.outputStream.use { os ->
                        os.write(data.toByteArray())
                    }
                }
                
                val responseCode = connection.responseCode
                val responseText = try {
                    BufferedReader(InputStreamReader(
                        if (responseCode >= 400) connection.errorStream else connection.inputStream
                    )).use { it.readText() }
                } catch (e: Exception) {
                    ""
                }
                
                val responseHeaders = JSONObject()
                connection.headerFields.forEach { (key, values) ->
                    if (key != null && values.isNotEmpty()) {
                        responseHeaders.put(key, values.joinToString(", "))
                    }
                }
                
                mainHandler.post {
                    val callback = """
                        (function() {
                            var callback = window['$callbackId'];
                            if (callback && callback.onload) {
                                callback.onload({
                                    status: $responseCode,
                                    statusText: '${connection.responseMessage ?: ""}',
                                    responseText: ${JSONObject.quote(responseText)},
                                    responseHeaders: ${responseHeaders}
                                });
                            }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(callback, null)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "xmlHttpRequest error", e)
                mainHandler.post {
                    val callback = """
                        (function() {
                            var callback = window['$callbackId'];
                            if (callback && callback.onerror) {
                                callback.onerror({ error: ${JSONObject.quote(e.message ?: "Unknown error")} });
                            }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(callback, null)
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    @JavascriptInterface
    fun getResourceText(resourceName: String): String? {
        return prefs.getString("resource_$resourceName", null)
    }

    @JavascriptInterface
    fun getResourceURL(resourceName: String): String? {
        val text = prefs.getString("resource_$resourceName", null) ?: return null
        return "data:text/plain;base64,${android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)}"
    }

    @JavascriptInterface
    fun exit() {
        mainHandler.post {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        }
    }

    @JavascriptInterface
    fun getUrl(): String? {
        return webView.url
    }

    @JavascriptInterface
    fun getUserAgent(): String? {
        return webView.settings.userAgentString
    }

    @JavascriptInterface
    fun setUserAgent(userAgent: String) {
        mainHandler.post {
            webView.settings.userAgentString = userAgent
        }
    }

    @JavascriptInterface
    fun loadUrl(url: String, headers: String?) {
        mainHandler.post {
            try {
                webView.stopLoading()
                if (!headers.isNullOrEmpty()) {
                    val httpHeaders = mutableMapOf<String, String>()
                    try {
                        val headersJson = JSONObject(headers)
                        headersJson.keys().forEach { key ->
                            httpHeaders[key] = headersJson.getString(key)
                        }
                    } catch (e: Exception) {}
                    webView.loadUrl(url, httpHeaders)
                } else {
                    webView.loadUrl(url)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadUrl error", e)
            }
        }
    }

    @JavascriptInterface
    fun loadFrame(urlFrame: String, urlParent: String) {
        mainHandler.post {
            val html = """
                <html>
                <head><style>iframe{width:100%;height:100%;border:none;}</style></head>
                <body style="margin:0;padding:0;">
                <iframe src="$urlFrame" allowfullscreen></iframe>
                </body>
                </html>
            """.trimIndent()
            webView.loadDataWithBaseURL(urlParent, html, "text/html", "UTF-8", null)
        }
    }

    @JavascriptInterface
    fun resolveUrl(urlRelative: String, urlBase: String?): String {
        return try {
            val base = if (urlBase.isNullOrEmpty()) webView.url ?: "" else urlBase
            URL(URL(base), urlRelative).toString()
        } catch (e: Exception) {
            urlRelative
        }
    }

    @JavascriptInterface
    fun removeAllCookies() {
        mainHandler.post {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
        }
    }

    @JavascriptInterface
    fun toastShort(message: String) {
        mainHandler.post {
            AvaToast.show(context, message, tag = "gm")
        }
    }

    @JavascriptInterface
    fun toastLong(message: String) {
        mainHandler.post {
            AvaToast.show(context, message, tag = "gm", durationMs = AvaToast.LONG_MS)
        }
    }

    @JavascriptInterface
    fun startIntent(action: String?, data: String?, type: String?, extrasJson: String?) {
        try {
            val intent = android.content.Intent()
            if (!action.isNullOrEmpty()) intent.action = action
            if (!data.isNullOrEmpty()) {
                if (!type.isNullOrEmpty()) {
                    intent.setDataAndType(android.net.Uri.parse(data), type)
                } else {
                    intent.data = android.net.Uri.parse(data)
                }
            } else if (!type.isNullOrEmpty()) {
                intent.type = type
            }
            
            if (!extrasJson.isNullOrEmpty()) {
                try {
                    val extras = JSONObject(extrasJson)
                    extras.keys().forEach { key ->
                        intent.putExtra(key, extras.getString(key))
                    }
                } catch (e: Exception) {}
            }
            
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startIntent error", e)
        }
    }

    @JavascriptInterface
    fun getCookie(url: String): String? {
        return android.webkit.CookieManager.getInstance().getCookie(url)
    }

    @JavascriptInterface
    fun setCookie(url: String, cookie: String) {
        mainHandler.post {
            android.webkit.CookieManager.getInstance().setCookie(url, cookie)
        }
    }

    @JavascriptInterface
    fun deleteCookie(url: String, name: String) {
        mainHandler.post {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setCookie(url, "$name=; expires=Thu, 01 Jan 1970 00:00:00 GMT")
        }
    }

    @JavascriptInterface
    fun listCookies(url: String): String {
        val cookies = android.webkit.CookieManager.getInstance().getCookie(url) ?: return "[]"
        val cookieList = cookies.split(";").map { it.trim() }
        val result = JSONObject()
        cookieList.forEach { cookie ->
            val parts = cookie.split("=", limit = 2)
            if (parts.size == 2) {
                result.put(parts[0].trim(), parts[1].trim())
            }
        }
        return result.toString()
    }

    @JavascriptInterface
    fun addElement(tagName: String, attributes: String?, textContent: String?) {
        mainHandler.post {
            val js = buildString {
                append("(function(){")
                append("var el=document.createElement('$tagName');")
                if (!attributes.isNullOrEmpty()) {
                    try {
                        val attrs = JSONObject(attributes)
                        attrs.keys().forEach { key ->
                            append("el.setAttribute('$key',${JSONObject.quote(attrs.getString(key))});")
                        }
                    } catch (e: Exception) {}
                }
                if (!textContent.isNullOrEmpty()) {
                    append("el.textContent=${JSONObject.quote(textContent)};")
                }
                append("(document.head||document.body||document.documentElement).appendChild(el);")
                append("return el;")
                append("})();")
            }
            webView.evaluateJavascript(js, null)
        }
    }

    fun destroy() {
        scope.cancel()
    }
}

object GmApiInjector {
    
    fun getGmApiScript(): String {
        return """
            (function() {
                'use strict';
                
                if (window.GM_info) return;
                
                window.GM_info = {
                    script: { name: 'UserScript', version: '1.0' },
                    scriptHandler: 'Ava WebMonkey',
                    version: '1.0'
                };
                
                window.GM_log = function(message) {
                    ${GmApi.JS_BRIDGE_NAME}.log(String(message));
                };
                
                window.GM_getValue = function(key, defaultValue) {
                    var result = ${GmApi.JS_BRIDGE_NAME}.getValue(key, defaultValue !== undefined ? String(defaultValue) : null);
                    try { return JSON.parse(result); } catch(e) { return result; }
                };
                
                window.GM_setValue = function(key, value) {
                    ${GmApi.JS_BRIDGE_NAME}.setValue(key, JSON.stringify(value));
                };
                
                window.GM_deleteValue = function(key) {
                    ${GmApi.JS_BRIDGE_NAME}.deleteValue(key);
                };
                
                window.GM_listValues = function() {
                    var result = ${GmApi.JS_BRIDGE_NAME}.listValues();
                    return result ? result.split(',') : [];
                };
                
                window.GM_addStyle = function(css) {
                    ${GmApi.JS_BRIDGE_NAME}.addStyle(css);
                    var style = document.createElement('style');
                    style.textContent = css;
                    (document.head || document.documentElement).appendChild(style);
                    return style;
                };
                
                window.GM_notification = function(text, title, image, onclick) {
                    if (typeof text === 'object') {
                        ${GmApi.JS_BRIDGE_NAME}.toast(text.text || text.title || '', 1);
                    } else {
                        ${GmApi.JS_BRIDGE_NAME}.toast(text, 1);
                    }
                };
                
                window.GM_xmlhttpRequest = function(details) {
                    var callbackId = 'gm_xhr_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
                    window[callbackId] = details;
                    
                    var headers = details.headers ? JSON.stringify(details.headers) : null;
                    var data = details.data || null;
                    
                    ${GmApi.JS_BRIDGE_NAME}.xmlHttpRequest(
                        details.method || 'GET',
                        details.url,
                        headers,
                        data,
                        callbackId
                    );
                    
                    return { abort: function() {} };
                };
                
                window.GM_getResourceText = function(resourceName) {
                    return ${GmApi.JS_BRIDGE_NAME}.getResourceText(resourceName);
                };
                
                window.GM_getResourceURL = function(resourceName) {
                    return ${GmApi.JS_BRIDGE_NAME}.getResourceURL(resourceName);
                };
                
                window.GM_openInTab = function(url, options) {
                    window.open(url, '_blank');
                };
                
                window.GM_setClipboard = function(text) {
                    if (navigator.clipboard) {
                        navigator.clipboard.writeText(text);
                    }
                };
                
                window.GM_registerMenuCommand = function(name, fn, accessKey) {
                    console.log('[GM] Menu command registered: ' + name);
                };
                
                window.GM_unregisterMenuCommand = function(name) {};
                
                window.GM_fetch = function(url, options) {
                    options = options || {};
                    return new Promise(function(resolve, reject) {
                        var details = {
                            method: options.method || 'GET',
                            url: url,
                            headers: options.headers || {},
                            data: options.body || null,
                            onload: function(response) {
                                resolve({
                                    ok: response.status >= 200 && response.status < 300,
                                    status: response.status,
                                    statusText: response.statusText,
                                    headers: new Headers(response.responseHeaders || {}),
                                    text: function() { return Promise.resolve(response.responseText); },
                                    json: function() { return Promise.resolve(JSON.parse(response.responseText)); }
                                });
                            },
                            onerror: function(error) {
                                reject(new Error(error.error || 'Network error'));
                            }
                        };
                        window.GM_xmlhttpRequest(details);
                    });
                };
                
                window.GM_addElement = function(tagName, attributes) {
                    ${GmApi.JS_BRIDGE_NAME}.addElement(tagName, attributes ? JSON.stringify(attributes) : null, null);
                };
                
                window.GM_cookie = {
                    list: function(details) {
                        var url = details && details.url ? details.url : window.location.href;
                        var cookies = ${GmApi.JS_BRIDGE_NAME}.listCookies(url);
                        return Promise.resolve(JSON.parse(cookies));
                    },
                    set: function(details) {
                        var url = details.url || window.location.href;
                        var cookie = details.name + '=' + details.value;
                        if (details.path) cookie += '; path=' + details.path;
                        if (details.domain) cookie += '; domain=' + details.domain;
                        if (details.expirationDate) cookie += '; expires=' + new Date(details.expirationDate * 1000).toUTCString();
                        ${GmApi.JS_BRIDGE_NAME}.setCookie(url, cookie);
                        return Promise.resolve();
                    },
                    delete: function(details) {
                        var url = details.url || window.location.href;
                        ${GmApi.JS_BRIDGE_NAME}.deleteCookie(url, details.name);
                        return Promise.resolve();
                    }
                };
                
                window.GM_exit = function() {
                    ${GmApi.JS_BRIDGE_NAME}.exit();
                };
                
                window.GM_getUrl = function() {
                    return ${GmApi.JS_BRIDGE_NAME}.getUrl();
                };
                
                window.GM_getUserAgent = function() {
                    return ${GmApi.JS_BRIDGE_NAME}.getUserAgent();
                };
                
                window.GM_setUserAgent = function(userAgent) {
                    ${GmApi.JS_BRIDGE_NAME}.setUserAgent(userAgent);
                };
                
                window.GM_loadUrl = function(url, headers) {
                    ${GmApi.JS_BRIDGE_NAME}.loadUrl(url, headers ? JSON.stringify(headers) : null);
                };
                
                window.GM_loadFrame = function(urlFrame, urlParent) {
                    ${GmApi.JS_BRIDGE_NAME}.loadFrame(urlFrame, urlParent);
                };
                
                window.GM_resolveUrl = function(urlRelative, urlBase) {
                    return ${GmApi.JS_BRIDGE_NAME}.resolveUrl(urlRelative, urlBase || '');
                };
                
                window.GM_removeAllCookies = function() {
                    ${GmApi.JS_BRIDGE_NAME}.removeAllCookies();
                };
                
                window.GM_toastShort = function(message) {
                    ${GmApi.JS_BRIDGE_NAME}.toastShort(message);
                };
                
                window.GM_toastLong = function(message) {
                    ${GmApi.JS_BRIDGE_NAME}.toastLong(message);
                };
                
                window.GM_startIntent = function(action, data, type, extras) {
                    ${GmApi.JS_BRIDGE_NAME}.startIntent(action || '', data || '', type || '', extras ? JSON.stringify(extras) : null);
                };
                
                window.GM = {
                    info: window.GM_info,
                    getValue: function(key, defaultValue) { return Promise.resolve(window.GM_getValue(key, defaultValue)); },
                    setValue: function(key, value) { window.GM_setValue(key, value); return Promise.resolve(); },
                    deleteValue: function(key) { window.GM_deleteValue(key); return Promise.resolve(); },
                    listValues: function() { return Promise.resolve(window.GM_listValues()); },
                    addStyle: function(css) { return Promise.resolve(window.GM_addStyle(css)); },
                    addElement: function(tagName, attrs) { window.GM_addElement(tagName, attrs); return Promise.resolve(); },
                    notification: function(details) { window.GM_notification(details); return Promise.resolve(); },
                    xmlHttpRequest: function(details) { return window.GM_xmlhttpRequest(details); },
                    fetch: function(url, options) { return window.GM_fetch(url, options); },
                    getResourceText: function(name) { return Promise.resolve(window.GM_getResourceText(name)); },
                    getResourceUrl: function(name) { return Promise.resolve(window.GM_getResourceURL(name)); },
                    openInTab: function(url, options) { window.GM_openInTab(url, options); return Promise.resolve(); },
                    setClipboard: function(text) { window.GM_setClipboard(text); return Promise.resolve(); },
                    registerMenuCommand: function(name, fn) { window.GM_registerMenuCommand(name, fn); return Promise.resolve(); },
                    unregisterMenuCommand: function(name) { window.GM_unregisterMenuCommand(name); return Promise.resolve(); },
                    log: function(message) { window.GM_log(message); },
                    exit: function() { window.GM_exit(); return Promise.resolve(); },
                    getUrl: function() { return Promise.resolve(window.GM_getUrl()); },
                    getUserAgent: function() { return Promise.resolve(window.GM_getUserAgent()); },
                    setUserAgent: function(ua) { window.GM_setUserAgent(ua); return Promise.resolve(); },
                    loadUrl: function(url, headers) { window.GM_loadUrl(url, headers); return Promise.resolve(); },
                    loadFrame: function(urlFrame, urlParent) { window.GM_loadFrame(urlFrame, urlParent); return Promise.resolve(); },
                    resolveUrl: function(urlRel, urlBase) { return Promise.resolve(window.GM_resolveUrl(urlRel, urlBase)); },
                    removeAllCookies: function() { window.GM_removeAllCookies(); return Promise.resolve(); },
                    toastShort: function(msg) { window.GM_toastShort(msg); return Promise.resolve(); },
                    toastLong: function(msg) { window.GM_toastLong(msg); return Promise.resolve(); },
                    startIntent: function(action, data, type, extras) { window.GM_startIntent(action, data, type, extras); return Promise.resolve(); },
                    cookie: window.GM_cookie,
                    cookies: window.GM_cookie
                };
                
                console.log('[GM] Greasemonkey API initialized');
            })();
        """.trimIndent()
    }
}
