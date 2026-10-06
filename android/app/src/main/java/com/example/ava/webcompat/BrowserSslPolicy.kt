package com.example.ava.webcompat

import android.net.Uri
import android.util.Log

/**
 * Decides whether a WebView TLS certificate error may be ignored.
 *
 * A self-signed or IP-SAN certificate on a LAN Home Assistant is normal, and the panel
 * still has to load, so certificate errors from private addresses are tolerated. The same
 * error from a routable host is not a homelab quirk — it is an on-path attacker — and
 * blanket `proceed()` there would hand any injected page the JS bridges. Those are refused.
 */
object BrowserSslPolicy {

    /**
     * @param url the failing URL, preferably `SslError.getUrl()`.
     * @return true if [url] points at a private/link-local host and the error may be waived.
     */
    fun allowsCertificateError(url: String?, tag: String): Boolean {
        val host = hostOf(url)
        if (host == null) {
            Log.w(tag, "Refusing SSL error: no host in url")
            return false
        }
        if (isPrivateHost(host)) return true
        Log.w(tag, "Refusing SSL error for routable host $host")
        return false
    }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return null
        return host.trim().trim('[', ']').lowercase().ifEmpty { null }
    }

    /**
     * Classifies [host] by text only. Never resolves names: this runs on the main thread
     * from `onReceivedSslError`, where a DNS lookup would throw NetworkOnMainThreadException.
     */
    fun isPrivateHost(host: String): Boolean {
        if (host == "localhost") return true
        ipv4Octets(host)?.let { return isPrivateIpv4(it) }
        if (host.contains(':')) return isPrivateIpv6(host)
        // A single-label name can only be resolved by the local resolver (mDNS, DHCP,
        // router DNS), so "homeassistant" or "nas" is by definition LAN-only.
        if (!host.contains('.')) return true
        return LOCAL_SUFFIXES.any { host.endsWith(it) }
    }

    private fun ipv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isPrivateIpv4(o: IntArray): Boolean = when {
        o[0] == 10 -> true
        o[0] == 127 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 169 && o[1] == 254 -> true
        // CGNAT range, used by Tailscale and similar overlays; not publicly routable.
        o[0] == 100 && o[1] in 64..127 -> true
        else -> false
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val addr = host.substringBefore('%')
        if (addr == "::1") return true
        // fc00::/7 unique-local, fe80::/10 link-local.
        return addr.startsWith("fc") ||
            addr.startsWith("fd") ||
            addr.startsWith("fe8") ||
            addr.startsWith("fe9") ||
            addr.startsWith("fea") ||
            addr.startsWith("feb")
    }

    private val LOCAL_SUFFIXES = listOf(
        ".local",
        ".lan",
        ".home",
        ".home.arpa",
        ".internal",
        ".localdomain",
    )
}
