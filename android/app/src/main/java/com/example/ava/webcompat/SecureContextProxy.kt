package com.example.ava.webcompat

import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback reverse proxy that turns a plain-http origin into a secure context
 * (same idea as kiosk-satellite [ProxyManager]).
 *
 * Browsers withhold mic / WebRTC / crypto.subtle on `http://<lan-ip>`.
 * Loopback origins are potentially trustworthy, so we serve the real HA
 * origin from loopback only — nothing new is exposed on the LAN.
 *
 * The displayed origin is `http://localhost:<port>` (not `127.0.0.1`): HA's
 * frontend only registers its service worker when `location.hostname ===
 * "localhost"` (or https), and the SW precache makes recovery reloads
 * near-instant. The socket itself stays bound to 127.0.0.1; engines resolve
 * `localhost` to loopback locally and fall back v6→v4 on refused connects.
 *
 * Callers keep storing/syncing the **real** URL; use [mapUrl] before WebView
 * load and [unmapUrl] before entity write-back.
 */
class SecureContextProxy private constructor() {

    companion object {
        private const val TAG = "SecureContextProxy"
        /** Prefer HA-like port on loopback; fall back to ephemeral if taken. */
        private const val PREFERRED_PORT = 8122
        private const val MAX_HEADER_BYTES = 64 * 1024

        private val HOP_BY_HOP = setOf(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
        )

        val instance: SecureContextProxy by lazy { SecureContextProxy() }

        /** True when [url] points at loopback (never safe to persist as HA remote URL). */
        fun isLoopbackHttpUrl(url: String): Boolean {
            val uri = Uri.parse(url) ?: return false
            val host = uri.host?.lowercase(Locale.US) ?: return false
            return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        }

        /** True when [url] is plain http on a non-loopback host (proxy can help). */
        fun isProxyableHttpUrl(url: String): Boolean {
            val uri = Uri.parse(url) ?: return false
            if (!uri.scheme.equals("http", ignoreCase = true)) return false
            val host = uri.host?.lowercase(Locale.US) ?: return false
            if (host.isEmpty() || isLoopbackHttpUrl(url)) {
                return false
            }
            return true
        }

        /**
         * Prefix match that only accepts a real origin boundary
         * (`…origin`, `…origin/`, `…origin?`, `…origin#`) — avoids
         * `http://127.0.0.1:8122` matching `http://127.0.0.1:81225/...`.
         */
        internal fun replaceOriginPrefix(url: String, fromOrigin: String, toOrigin: String): String {
            if (!url.startsWith(fromOrigin)) return url
            if (url.length > fromOrigin.length) {
                val next = url[fromOrigin.length]
                if (next != '/' && next != '?' && next != '#') return url
            }
            return toOrigin + url.substring(fromOrigin.length)
        }

        /**
         * Page-side half: keep media/fetch on the loopback origin so Web Audio
         * is not CORS-silenced (kiosk media_rewrite_script).
         */
        fun mediaRewriteScript(targetOrigin: String, loopbackOrigin: String): String {
            val from = targetOrigin.replace("\\", "\\\\").replace("\"", "\\\"")
            val to = loopbackOrigin.replace("\\", "\\\\").replace("\"", "\\\"")
            return """
                (function(){
                  if (window.__avaSecureProxyRewrite) return;
                  window.__avaSecureProxyRewrite = true;
                  var FROM = "$from";
                  var TO = "$to";
                  function remap(v) {
                    return (typeof v === 'string' && v.indexOf(FROM) === 0) ? TO + v.slice(FROM.length) : v;
                  }
                  function patchSrc(proto) {
                    var desc = Object.getOwnPropertyDescriptor(proto, 'src');
                    if (!desc || !desc.set) return;
                    Object.defineProperty(proto, 'src', {
                      configurable: true,
                      get: desc.get,
                      set: function(v) { desc.set.call(this, remap(v)); }
                    });
                  }
                  patchSrc(HTMLMediaElement.prototype);
                  patchSrc(HTMLSourceElement.prototype);
                  var setAttr = Element.prototype.setAttribute;
                  Element.prototype.setAttribute = function(name, value) {
                    if ((this instanceof HTMLMediaElement || this instanceof HTMLSourceElement)
                        && typeof name === 'string' && name.toLowerCase() === 'src') {
                      value = remap(value);
                    }
                    return setAttr.call(this, name, value);
                  };
                  var NativeAudio = window.Audio;
                  var PatchedAudio = function(src) {
                    return src === undefined ? new NativeAudio() : new NativeAudio(remap(src));
                  };
                  PatchedAudio.prototype = NativeAudio.prototype;
                  window.Audio = PatchedAudio;
                  // Legacy engines without fetch must not gain a broken one — pages
                  // feature-detect `window.fetch` and would take the wrong code path.
                  var nativeFetch = window.fetch;
                  if (typeof nativeFetch === 'function') {
                    window.fetch = function(input, init) {
                      if (typeof input === 'string') input = remap(input);
                      else if (input && typeof input.url === 'string' && input.url.indexOf(FROM) === 0) {
                        input = new Request(remap(input.url), input);
                      }
                      return nativeFetch.call(this, input, init);
                    };
                  }
                  var nativeOpen = XMLHttpRequest.prototype.open;
                  XMLHttpRequest.prototype.open = function(method, url) {
                    var args = Array.prototype.slice.call(arguments);
                    args[1] = remap(url);
                    return nativeOpen.apply(this, args);
                  };
                })();
            """.trimIndent()
        }
    }

    private val lock = Any()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var worker = Executors.newCachedThreadPool { r ->
        Thread(r, "secure-ctx-proxy").apply { isDaemon = true }
    }

    @Volatile private var targetHost: String? = null
    @Volatile private var targetPort: Int = 80

    /** Last known origins for unmap after stop (toggle / rebuild). */
    @Volatile private var lastTargetOrigin: String? = null
    @Volatile private var lastLoopbackOrigin: String? = null
    /** One-generation history so a port rebind can still unmap in-flight WebView URLs. */
    @Volatile private var prevTargetOrigin: String? = null
    @Volatile private var prevLoopbackOrigin: String? = null

    val isRunning: Boolean get() = running.get() && serverSocket?.isClosed == false

    fun targetOrigin(): String? {
        val host = targetHost ?: return null
        if (!isRunning) return null
        return "http://$host:$targetPort"
    }

    fun loopbackOrigin(): String? {
        val ss = serverSocket ?: return null
        if (!isRunning) return null
        // localhost (not 127.0.0.1) so HA's frontend registers its service worker.
        return "http://localhost:${ss.localPort}"
    }

    /**
     * Ensure the proxy is listening for [realHttpUrl]'s origin.
     * @return false if URL is not proxyable or bind failed
     */
    fun ensureRunningFor(realHttpUrl: String): Boolean {
        if (!isProxyableHttpUrl(realHttpUrl)) return false
        val uri = Uri.parse(realHttpUrl)
        val host = uri.host ?: return false
        val port = if (uri.port != -1) uri.port else 80
        synchronized(lock) {
            if (isRunning && targetHost == host && targetPort == port) return true
            stopLocked()
            return startLocked(host, port)
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    /** Real HA URL → loopback when running and same origin; else pass-through. */
    fun mapUrl(url: String): String {
        val target = targetOrigin() ?: return url
        val loopback = loopbackOrigin() ?: return url
        return replaceOriginPrefix(url, target, loopback)
    }

    /**
     * Loopback URL → real HA origin; uses last mapping after stop.
     * Also tries the previous loopback port after a rebind so write-back
     * does not leak 127.0.0.1 when the WebView URL is briefly stale.
     */
    fun unmapUrl(url: String): String {
        fun tryPair(from: String?, to: String?): String? {
            if (from == null || to == null) return null
            val mapped = replaceOriginPrefix(url, from, to)
            return if (mapped != url) mapped else null
        }
        tryPair(loopbackOrigin(), targetOrigin())?.let { return it }
        tryPair(lastLoopbackOrigin, lastTargetOrigin)?.let { return it }
        tryPair(prevLoopbackOrigin, prevTargetOrigin)?.let { return it }
        return url
    }

    private fun startLocked(host: String, port: Int): Boolean {
        if (worker.isShutdown) {
            worker = Executors.newCachedThreadPool { r ->
                Thread(r, "secure-ctx-proxy").apply { isDaemon = true }
            }
        }
        val loopback = InetAddress.getByName("127.0.0.1")
        var bound: ServerSocket? = null
        try {
            val preferred = ServerSocket()
            preferred.reuseAddress = true
            preferred.soTimeout = 1_000
            preferred.bind(InetSocketAddress(loopback, PREFERRED_PORT))
            bound = preferred
        } catch (e: Exception) {
            Log.w(TAG, "bind :$PREFERRED_PORT failed (${e.message}); using ephemeral")
            try {
                val any = ServerSocket()
                any.reuseAddress = true
                any.soTimeout = 1_000
                any.bind(InetSocketAddress(loopback, 0))
                bound = any
            } catch (e2: Exception) {
                Log.e(TAG, "bind loopback failed", e2)
                return false
            }
        }
        targetHost = host
        targetPort = port
        serverSocket = bound
        running.set(true)
        val newTarget = "http://$host:$port"
        val newLoopback = "http://localhost:${bound.localPort}"
        if (lastLoopbackOrigin != null && lastLoopbackOrigin != newLoopback) {
            prevLoopbackOrigin = lastLoopbackOrigin
            prevTargetOrigin = lastTargetOrigin
        }
        lastTargetOrigin = newTarget
        lastLoopbackOrigin = newLoopback
        acceptThread = Thread({ acceptLoop(bound) }, "secure-ctx-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "secure context proxy on 127.0.0.1:${bound.localPort} for $host:$port")
        return true
    }

    private fun stopLocked() {
        // Snapshot live origins into last* before clearing so unmap still works.
        loopbackOrigin()?.let { lastLoopbackOrigin = it }
        targetOrigin()?.let { lastTargetOrigin = it }
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        val t = acceptThread
        acceptThread = null
        if (t != null) {
            try {
                t.join(1_500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        targetHost = null
        Log.i(TAG, "secure context proxy stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            try {
                val client = ss.accept()
                worker.execute {
                    try {
                        handleClient(client)
                    } catch (e: Exception) {
                        Log.d(TAG, "client failed: ${e.message}")
                        try {
                            client.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
                // soTimeout wake for stop()
            } catch (_: SocketException) {
                if (!running.get()) break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept error: ${e.message}")
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        val input = BufferedInputStream(client.getInputStream())
        val headerBytes = readHttpHeaders(input) ?: run {
            client.close()
            return
        }
        val headerText = String(headerBytes, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) {
            client.close()
            return
        }
        val requestLine = lines[0]
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            client.close()
            return
        }
        val method = parts[0]
        val pathAndQuery = parts[1]
        val headers = linkedMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            headers[name] = value
        }
        val host = targetHost
        val port = targetPort
        if (host == null) {
            client.close()
            return
        }
        val connection = headers.entries.firstOrNull { it.key.equals("connection", true) }?.value.orEmpty()
        val upgrade = headers.entries.firstOrNull { it.key.equals("upgrade", true) }?.value.orEmpty()
        val isWebSocket = connection.contains("upgrade", ignoreCase = true) &&
            upgrade.equals("websocket", ignoreCase = true)

        if (isWebSocket) {
            tunnelWebSocket(client, input, method, pathAndQuery, headers, host, port)
        } else {
            forwardHttp(client, input, method, pathAndQuery, headers, host, port)
        }
    }

    private fun forwardHttp(
        client: Socket,
        clientIn: InputStream,
        method: String,
        pathAndQuery: String,
        headers: Map<String, String>,
        host: String,
        port: Int,
    ) {
        val upstream = Socket()
        try {
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(host, port), 20_000)
            val upOut = upstream.getOutputStream()
            val upIn = BufferedInputStream(upstream.getInputStream())

            val hostHeader = if (port == 80) host else "$host:$port"
            val outHeaders = StringBuilder()
            outHeaders.append(method).append(' ').append(pathAndQuery).append(" HTTP/1.1\r\n")
            outHeaders.append("Host: ").append(hostHeader).append("\r\n")
            for ((name, value) in headers) {
                val lower = name.lowercase(Locale.US)
                if (lower in HOP_BY_HOP || lower == "host" || lower == "content-length") continue
                // Keep content-length separately below.
                outHeaders.append(name).append(": ").append(value).append("\r\n")
            }
            val contentLength = headers.entries.firstOrNull {
                it.key.equals("content-length", true)
            }?.value?.toLongOrNull() ?: -1L
            if (contentLength >= 0) {
                outHeaders.append("Content-Length: ").append(contentLength).append("\r\n")
            }
            outHeaders.append("Connection: close\r\n\r\n")
            upOut.write(outHeaders.toString().toByteArray(Charsets.ISO_8859_1))
            if (contentLength > 0) {
                copyExact(clientIn, upOut, contentLength)
            }
            upOut.flush()

            // Stream response; rewrite Location onto loopback when needed.
            val clientOut = client.getOutputStream()
            val respHeaders = readHttpHeaders(upIn)
            if (respHeaders == null) {
                client.close()
                upstream.close()
                return
            }
            val rewritten = rewriteResponseHeaders(String(respHeaders, Charsets.ISO_8859_1))
            clientOut.write(rewritten.toByteArray(Charsets.ISO_8859_1))
            copyFully(upIn, clientOut)
            clientOut.flush()
        } finally {
            try {
                upstream.close()
            } catch (_: Exception) {
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun rewriteResponseHeaders(headerText: String): String {
        val lines = headerText.split("\r\n").toMutableList()
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("Location:", ignoreCase = true)) {
                val value = line.substringAfter(':').trim()
                lines[i] = "Location: ${mapUrl(value)}"
            }
        }
        return lines.joinToString("\r\n")
    }

    private fun tunnelWebSocket(
        client: Socket,
        clientIn: InputStream,
        method: String,
        pathAndQuery: String,
        headers: Map<String, String>,
        host: String,
        port: Int,
    ) {
        val upstream = Socket()
        try {
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(host, port), 20_000)
            val upOut = upstream.getOutputStream()
            val hostHeader = if (port == 80) host else "$host:$port"
            // Rebuild upgrade request with correct Host (do not forward hop leftovers oddly).
            val rebuilt = StringBuilder()
            rebuilt.append(method).append(' ').append(pathAndQuery).append(" HTTP/1.1\r\n")
            rebuilt.append("Host: ").append(hostHeader).append("\r\n")
            for ((name, value) in headers) {
                if (name.equals("host", true)) continue
                rebuilt.append(name).append(": ").append(value).append("\r\n")
            }
            rebuilt.append("\r\n")
            upOut.write(rebuilt.toString().toByteArray(Charsets.ISO_8859_1))
            upOut.flush()
            // Any body already in clientIn after headers is rare for WS; pump both ways.
            val upIn = upstream.getInputStream()
            val clientOut = client.getOutputStream()
            val t1 = Thread({
                try {
                    copyFully(upIn, clientOut)
                } catch (_: Exception) {
                } finally {
                    try {
                        client.shutdownOutput()
                    } catch (_: Exception) {
                    }
                }
            }, "secure-ctx-ws-up").apply { isDaemon = true }
            t1.start()
            try {
                copyFully(clientIn, upOut)
            } catch (_: Exception) {
            } finally {
                try {
                    upstream.shutdownOutput()
                } catch (_: Exception) {
                }
            }
            t1.join(30_000L)
        } finally {
            try {
                upstream.close()
            } catch (_: Exception) {
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readHttpHeaders(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream(4096)
        var state = 0 // count trailing \r\n\r\n
        while (buf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toByteArray()
            buf.write(b)
            when (state) {
                0 -> state = if (b == '\r'.code) 1 else 0
                1 -> state = if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> state = if (b == '\r'.code) 3 else 0
                3 -> {
                    if (b == '\n'.code) return buf.toByteArray()
                    state = 0
                }
            }
        }
        throw IOException("headers too large")
    }

    private fun copyExact(from: InputStream, to: OutputStream, length: Long) {
        val buf = ByteArray(16 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = from.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            to.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun copyFully(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = from.read(buf)
            if (n < 0) break
            to.write(buf, 0, n)
            to.flush()
        }
    }
}
