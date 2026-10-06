package com.example.ava.utils

import android.util.Log
import com.example.ava.homeassistant.HaMediaAuth
import java.net.URI
import java.net.URLConnection

/**
 * Resolve Home Assistant media URLs for local playback.
 *
 * Matches cover-art joining: relative `/...` paths are prefixed with the live
 * ESPHome peer address (`http://{haHost}:{port}`, port learned from HA URLs,
 * falling back to 8123). Absolute TTS/ffmpeg proxy URLs
 * whose host differs from that peer are rewritten to the same peer — HA's
 * Network Local/External URL is often unreachable from the phone even though
 * the ESPHome TCP connection already proves a working LAN address.
 *
 * [preserveHttps]: when true, absolute `https://` HA proxy URLs are left unchanged
 * (avoids rewriting to plaintext HTTP on TLS-only HA), and relative paths use HTTPS.
 * Default false keeps the historical LAN HTTP rewrite for existing installs.
 */
object HaMediaUrl {
    private const val TAG = "HaMediaUrl"
    private const val DEFAULT_HA_PORT = 8123

    /**
     * HA's direct HTTP(S) port, learned from HA-provided URLs that carry an
     * explicit port (tts_proxy/ffmpeg_proxy URLs, ha_remote_url). Null until
     * learned; consumers fall back to the historical 8123 default.
     */
    @Volatile
    private var learnedHaPort: Int? = null

    /** Best-known HA port for building URLs from scratch. Falls back to 8123. */
    val haPortOrDefault: Int
        get() = learnedHaPort ?: DEFAULT_HA_PORT

    /**
     * Learn HA's direct port from an absolute HA URL. Only explicit ports are
     * trusted: implicit 80/443 usually means a reverse proxy, whose port says
     * nothing about HA's own listener.
     */
    fun noteHaUrl(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase() ?: return
            if (scheme != "http" && scheme != "https") return
            val port = uri.port
            if (port <= 0) return
            if (learnedHaPort != port) {
                learnedHaPort = port
                logDebug("Learned HA port $port from $url")
            }
        } catch (_: Exception) {
            // Not a parseable URL; keep the current value.
        }
    }

    /** Test-only: reset learned state between unit tests. */
    fun clearLearnedPort() {
        learnedHaPort = null
    }

    fun resolve(
        url: String?,
        haHost: String?,
        preserveHttps: Boolean = false,
    ): String? {
        if (url.isNullOrBlank()) return url
        val trimmed = url.trim()
        when {
            trimmed.startsWith("asset://", ignoreCase = true) ||
                trimmed.startsWith("file://", ignoreCase = true) ||
                trimmed.startsWith("content://", ignoreCase = true) ||
                trimmed.startsWith("media-source://", ignoreCase = true) -> return trimmed
        }

        if (trimmed.startsWith("/")) {
            if (haHost.isNullOrBlank()) return trimmed
            val scheme = if (preserveHttps) "https" else "http"
            val resolved = "$scheme://${hostForUrl(haHost)}:$haPortOrDefault$trimmed"
            logDebug("Relative HA URL -> $resolved")
            return resolved
        }

        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }

        if (haHost.isNullOrBlank()) return trimmed
        return rewriteHaProxyHost(trimmed, haHost, preserveHttps) ?: trimmed
    }

    /**
     * Prefer the signed-in HA origin when joining relative proxy paths.
     * Falls back to the ESPHome peer host so unsigned-in installs stay unchanged.
     */
    fun resolvePreferringSignedIn(url: String?, esphomeHost: String?): String? {
        if (url.isNullOrBlank()) return url
        val origin = HaMediaAuth.serverUrl.takeIf { HaMediaAuth.signedIn }
        val trimmed = url.trim()
        if (origin != null) {
            if (trimmed.startsWith("/")) {
                noteHaUrl(origin)
                return origin + trimmed
            }
            rebaseOnRemote(trimmed, origin)?.let { return it }
        }
        val preserveHttps = origin?.startsWith("https", ignoreCase = true) == true
        return resolve(trimmed, esphomeHost, preserveHttps = preserveHttps)
    }

    fun signedInCameraProxyUrl(entityId: String): String? {
        if (!entityId.startsWith("camera.")) return null
        val origin = HaMediaAuth.serverUrl.takeIf { HaMediaAuth.signedIn } ?: return null
        return "$origin/api/camera_proxy/$entityId"
    }

    fun signedInCameraStreamUrl(entityId: String, accessToken: String? = null): String? {
        if (!entityId.startsWith("camera.")) return null
        val origin = HaMediaAuth.serverUrl.takeIf { HaMediaAuth.signedIn } ?: return null
        val base = "$origin/api/camera_proxy_stream/$entityId"
        val token = accessToken?.trim().orEmpty()
        return if (token.isEmpty()) base else "$base?token=$token"
    }

    /**
     * Copy a rotating `token=` from [from] onto [onto] when the destination
     * has none. Used so login-origin streams still work on proxies that only
     * accept the entity short token.
     */
    fun copyQueryToken(from: String, onto: String): String? {
        if (onto.contains("token=", ignoreCase = true)) return null
        val q = from.indexOf('?')
        if (q < 0) return null
        val token = from.substring(q + 1).split('&').firstOrNull { part ->
            part.startsWith("token=", ignoreCase = true) && part.length > 6
        } ?: return null
        return if (onto.contains('?')) "$onto&$token" else "$onto?$token"
    }

    fun isCameraStreamUrl(url: String): Boolean =
        url.contains("/api/camera_proxy_stream/")

    fun isHlsStreamUrl(url: String): Boolean =
        url.contains("/api/hls/") || url.contains(".m3u8", ignoreCase = true)

    fun shouldAttachBearer(url: String): Boolean =
        HaMediaAuth.signedIn && isHaApiMediaUrl(url)

    fun applyBearer(connection: URLConnection, url: String = connection.url?.toString().orEmpty()) {
        if (!shouldAttachBearer(url)) return
        val header = HaMediaAuth.bearerHeader() ?: return
        connection.setRequestProperty("Authorization", header)
    }

    fun isHaApiMediaUrl(url: String): Boolean {
        return try {
            val path = URI(url.trim()).rawPath ?: return false
            isHaProxyPath(path)
        } catch (_: Exception) {
            false
        }
    }

    private fun rewriteHaProxyHost(
        url: String,
        haHost: String,
        preserveHttps: Boolean,
    ): String? {
        return try {
            val uri = URI(url)
            val path = uri.rawPath ?: uri.path ?: return null
            if (!isHaProxyPath(path)) return null
            // HA itself produced this URL, so an explicit port here is HA's
            // real listener port — remember it for URLs we must build ourselves.
            noteHaUrl(url)
            val host = uri.host ?: return null
            if (hostEquals(host, haHost)) return null

            val isHttps = uri.scheme.equals("https", ignoreCase = true)
            // TLS-only HA: keep the original HTTPS URL so ExoPlayer can use a valid
            // certificate hostname (rewriting to https://LAN_IP usually fails SNI/CN).
            if (preserveHttps && isHttps) {
                logDebug("HA proxy HTTPS preserved (no host rewrite): $url")
                return null
            }

            // Keep the URL's own explicit port (HA 2026.8+ allows non-8123
            // ports). An implicit 80/443 usually means a reverse proxy whose
            // port isn't HA's direct listener, so fall back to the best-known
            // direct port (learned, else 8123 — the historical behavior).
            val targetPort = if (uri.port > 0) uri.port else haPortOrDefault
            val query = uri.rawQuery
            val fragment = uri.rawFragment
            buildString {
                append("http://")
                append(hostForUrl(haHost))
                append(':')
                append(targetPort)
                append(path)
                if (!query.isNullOrEmpty()) {
                    append('?')
                    append(query)
                }
                if (!fragment.isNullOrEmpty()) {
                    append('#')
                    append(fragment)
                }
            }.also { logDebug("HA proxy host rewrite: $host -> $haHost ($it)") }
        } catch (e: Exception) {
            logWarn("Failed to rewrite HA URL: $url", e)
            null
        }
    }

    /**
     * Snapshot URLs to try in order: ESPHome-peer HTTP first (same as cover art),
     * then HTTPS on that host, then the browser [haRemoteUrl] origin.
     */
    fun cameraFetchCandidates(snapshotUrl: String, haRemoteUrl: String? = null): List<String> {
        val out = linkedSetOf<String>()
        if (snapshotUrl.isNotBlank()) out.add(snapshotUrl)
        httpsAlternate(snapshotUrl)?.let { out.add(it) }
        val signedInOrigin = HaMediaAuth.serverUrl.takeIf { HaMediaAuth.signedIn }
        rebaseOnRemote(snapshotUrl, signedInOrigin)?.let { rebased ->
            if (out.add(rebased)) {
                httpsAlternate(rebased)?.let { out.add(it) }
            }
        }
        rebaseOnRemote(snapshotUrl, haRemoteUrl)?.let { rebased ->
            if (out.add(rebased)) {
                httpsAlternate(rebased)?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    fun streamUrlFromSnapshot(url: String): String? {
        if (isCameraStreamUrl(url)) return url
        if (!url.contains("/api/camera_proxy/")) return null
        return url.replace("/api/camera_proxy/", "/api/camera_proxy_stream/")
    }

    /**
     * Join an HTTP Location against the request we just sent. Relative
     * locations are common on HA reverse proxies.
     */
    fun resolveRedirect(currentUrl: String, location: String?): String? {
        val loc = location?.trim().orEmpty()
        if (loc.isEmpty()) return null
        return try {
            val resolved = URI(currentUrl).resolve(loc)
            val scheme = resolved.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            resolved.toString()
        } catch (_: Exception) {
            null
        }
    }

    fun isRejectedCameraStreamType(contentType: String): Boolean {
        val type = contentType.lowercase()
        if (type.isEmpty()) return false
        return type.contains("mpegurl") ||
            type.contains("application/dash") ||
            type.contains("application/vnd.apple") ||
            type.contains("text/html") ||
            type.contains("application/json") ||
            type.contains("video/mp4")
    }

    /**
     * Compare camera proxy URLs ignoring rotating `token=` cache-busters so a
     * live MJPEG session is not torn down on every HA state push.
     */
    fun cameraUrlIdentity(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val base = url.substring(0, q)
        val params = url.substring(q + 1).split('&').filter { part ->
            part.isNotEmpty() && !part.startsWith("token=", ignoreCase = true)
        }
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    /**
     * Bracket IPv6 literals for URL hosts. Zone IDs (`%wlan0`) are dropped —
     * they are not valid in HTTP URLs.
     */
    fun hostForUrl(host: String): String {
        val cleaned = host.trim()
            .removePrefix("[")
            .substringBefore(']')
            .substringBefore('%')
            .trim()
        return if (cleaned.contains(':')) "[$cleaned]" else cleaned
    }

    private fun httpsAlternate(url: String): String? {
        if (!url.startsWith("http://", ignoreCase = true)) return null
        return "https://" + url.substring("http://".length)
    }

    private fun rebaseOnRemote(snapshotUrl: String, haRemoteUrl: String?): String? {
        if (haRemoteUrl.isNullOrBlank()) return null
        return try {
            val snap = URI(snapshotUrl)
            val remote = URI(haRemoteUrl.trim())
            val scheme = remote.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = remote.host ?: return null
            val path = snap.rawPath ?: snap.path ?: return null
            if (!isHaProxyPath(path)) return null
            noteHaUrl(haRemoteUrl)
            val port = remote.port
            val query = snap.rawQuery
            buildString {
                append(scheme)
                append("://")
                append(hostForUrl(host))
                if (port > 0) {
                    append(':')
                    append(port)
                }
                append(path)
                if (!query.isNullOrEmpty()) {
                    append('?')
                    append(query)
                }
            }.takeUnless { it == snapshotUrl }
        } catch (_: Exception) {
            null
        }
    }

    private fun isHaProxyPath(path: String): Boolean =
        path.startsWith("/api/tts_proxy") ||
            path.startsWith("/api/ffmpeg_proxy") ||
            path.startsWith("/api/tts_get") ||
            path.startsWith("/api/camera_proxy") ||
            path.startsWith("/api/hls") ||
            path.startsWith("/api/media_player_proxy") ||
            path.startsWith("/api/image_proxy")

    private fun hostEquals(a: String, b: String): Boolean {
        val na = hostForUrl(a).removePrefix("[").removeSuffix("]").lowercase()
        val nb = hostForUrl(b).removePrefix("[").removeSuffix("]").lowercase()
        return na == nb
    }

    private fun logDebug(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: Throwable) {
            // Unit tests run without Android Log stubs.
        }
    }

    private fun logWarn(message: String, error: Throwable) {
        try {
            Log.w(TAG, message, error)
        } catch (_: Throwable) {
            // Unit tests run without Android Log stubs.
        }
    }
}
