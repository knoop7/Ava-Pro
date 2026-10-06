package com.example.ava.net

import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * GitHub raw/release URL mirrors — same family as [com.example.ava.mods.ModManager]
 * and [com.example.ava.update.AppUpdater] (ghfast / ghproxy / gh-proxy / llkk).
 */
object GithubProxyUrls {
    val PROXY_PREFIXES: List<String> = listOf(
        "https://gh-proxy.com/",
        "https://gh.llkk.cc/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
    )

    fun shouldPreferProxy(context: Context? = null): Boolean {
        val locale = context?.resources?.configuration?.let { config ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.locales[0]
            } else {
                @Suppress("DEPRECATION")
                config.locale
            }
        } ?: Locale.getDefault()
        val lang = locale.language.lowercase(Locale.ROOT)
        return lang == "zh" || lang == "ru"
    }

    fun isGithubHosted(url: String): Boolean {
        val normalized = url.trim().lowercase(Locale.ROOT)
        return normalized.contains("raw.githubusercontent.com") ||
            normalized.contains("githubusercontent.com") ||
            normalized.contains("github.com/")
    }

    /** Strip a known mirror prefix so callers always start from the direct URL. */
    fun toDirectUrl(url: String): String {
        var direct = url.trim()
        for (prefix in PROXY_PREFIXES) {
            if (direct.startsWith(prefix)) {
                direct = direct.removePrefix(prefix)
                break
            }
        }
        if (direct.startsWith("http://raw.githubusercontent.com/") ||
            direct.startsWith("http://github.com/") ||
            direct.startsWith("http://user-attachments.githubusercontent.com/")
        ) {
            direct = direct.replaceFirst("http://", "https://")
        }
        return direct
    }

    /**
     * Fetch candidates. Only zh/ru may use public mirrors (same gate as
     * [com.example.ava.mods.ModManager]); every other locale is official GitHub only.
     * When [preferredPrefix] is set (sticky winner), that mirror is tried first
     * — still only for zh/ru.
     */
    fun candidates(
        context: Context?,
        directUrl: String,
        preferredPrefix: String? = null,
        preferProxy: Boolean = shouldPreferProxy(context),
    ): List<String> {
        val direct = toDirectUrl(directUrl)
        if (direct.isEmpty()) return emptyList()
        if (!isGithubHosted(direct) || !preferProxy) return listOf(direct)
        val prefixes = buildList {
            if (preferredPrefix != null && preferredPrefix in PROXY_PREFIXES) {
                add(preferredPrefix)
            }
            for (prefix in PROXY_PREFIXES) {
                if (prefix != preferredPrefix) add(prefix)
            }
        }
        return (prefixes.map { prefix -> "$prefix$direct" } + direct).distinct()
    }

    /**
     * Same locale gate as [candidates], then sticky / cooldown / attempt cap.
     */
    fun cautiousCandidates(
        context: Context?,
        directUrl: String,
        maxAttempts: Int = GithubFetchGuard.MAX_ATTEMPTS,
        ignoreCooldown: Boolean = false,
        allowIfAllCooling: Boolean = false,
    ): List<String> {
        val ordered = candidates(
            context,
            directUrl,
            preferredPrefix = GithubFetchGuard.stickyPrefix,
        )
        return GithubFetchGuard.filterCandidates(
            candidates = ordered,
            maxAttempts = maxAttempts,
            ignoreCooldown = ignoreCooldown,
            allowIfAllCooling = allowIfAllCooling,
        )
    }
}
