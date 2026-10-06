package com.example.ava.services

import java.net.URI
import java.net.InetAddress
import java.util.Locale

/** Public-web boundary shared by tools, top-level navigation and resource loads. */
object AiBrowserUrlPolicy {
    fun allows(url: String, blockedHosts: Set<String> = emptySet()): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return false
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.removeSurrounding("[", "]") ?: return false
        if (host.isBlank() || host in blockedHosts.map { it.lowercase(Locale.ROOT).trimEnd('.') }) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || !host.contains('.') && !host.contains(':')) return false
        // Reject alternative numeric IPv4 spellings rather than relying on WebView normalization.
        if (host.matches(Regex("[0-9.]+")) && !host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) return false
        if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            if (!isPublic(address)) return false
        }
        return true
    }

    /** Called only on WebView's resource worker, never the UI thread. */
    fun resolvesPublicly(url: String): Boolean = runCatching {
        InetAddress.getAllByName(URI(url).host).let { it.isNotEmpty() && it.all(::isPublic) }
    }.getOrDefault(false)

    private fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        val first = bytes[0].toInt() and 255
        if (bytes.size == 16) return first and 0xfe != 0xfc
        val second = bytes[1].toInt() and 255
        return first != 0 && first < 224 && !(first == 100 && second in 64..127) &&
            !(first == 198 && second in 18..19)
    }
}
