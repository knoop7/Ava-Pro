package com.example.ava.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local throttle for GitHub and public-mirror fetches.
 *
 * Hourly update checks must not walk every proxy after a timeout or 403 —
 * those mirrors share exit IPs and 403 is usually the proxy, not the user.
 */
object GithubFetchGuard {
    const val COOLDOWN_MS = 30L * 60 * 1000
    const val MAX_ATTEMPTS = 2

    @Volatile
    var stickyPrefix: String? = null
        private set

    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()

    fun hostKey(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()
            ?.lowercase()
            .orEmpty()
            .ifBlank { url }

    fun rememberSuccess(url: String) {
        stickyPrefix = GithubProxyUrls.PROXY_PREFIXES.firstOrNull { url.startsWith(it) }
        cooldownUntilMs.remove(hostKey(url))
    }

    fun rememberFailure(
        url: String,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = COOLDOWN_MS,
    ) {
        cooldownUntilMs[hostKey(url)] = nowMs + cooldownMs
    }

    fun isCooling(url: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = cooldownUntilMs[hostKey(url)] ?: return false
        if (nowMs >= until) {
            cooldownUntilMs.remove(hostKey(url))
            return false
        }
        return true
    }

    fun filterCandidates(
        candidates: List<String>,
        nowMs: Long = System.currentTimeMillis(),
        maxAttempts: Int = MAX_ATTEMPTS,
        ignoreCooldown: Boolean = false,
        allowIfAllCooling: Boolean = false,
    ): List<String> {
        val limit = maxAttempts.coerceAtLeast(0)
        val available = if (ignoreCooldown) {
            candidates
        } else {
            candidates.filter { !isCooling(it, nowMs) }
        }
        if (available.isEmpty()) {
            return if (allowIfAllCooling) candidates.take(limit) else emptyList()
        }
        return available.take(limit)
    }

    fun resetForTest() {
        stickyPrefix = null
        cooldownUntilMs.clear()
    }
}
