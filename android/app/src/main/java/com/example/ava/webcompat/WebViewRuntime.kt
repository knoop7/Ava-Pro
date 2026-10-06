package com.example.ava.webcompat

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Reads the installed System WebView / Chromium major version.
 *
 * Vendor WebView packages (Meta Portal, some Amazon builds) put a product
 * version in [android.content.pm.PackageInfo.versionName] — e.g. Portal's
 * `com.facebook.portal.webview` reports `14.0.4.0.0.…` while the engine is
 * Chromium 131. Syntax gates must use the page-visible Chrome token from
 * [WebSettings.getDefaultUserAgent], not the package major.
 *
 * [cachedInfo] is the single gating entry for engine-version fallbacks (chunked
 * rendering CSS grade, engine-flag reporting). `majorVersion == 0` means the
 * version could not be parsed — treat as unknown and stay permissive, page-side
 * feature detection is the authoritative guard.
 */
object WebViewRuntime {

    data class Info(
        val packageName: String?,
        val versionName: String?,
        val majorVersion: Int
    )

    @Volatile
    private var cached: Info? = null

    /** Per-process cache; the provider only changes on a WebView package update. */
    fun cachedInfo(context: Context): Info =
        cached ?: getInfo(context).also { cached = it }

    fun getInfo(context: Context): Info {
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()
        } else {
            null
        }
        val versionName = pkg?.versionName ?: legacyWebViewVersionName(context)
        val userAgent = defaultUserAgent(context)
        val major = resolveMajorVersion(versionName, userAgent)
        val packageMajor = parseMajorVersion(versionName)
        if (packageMajor > 0 && major > 0 && packageMajor != major) {
            Log.i(
                TAG,
                "WebView ${pkg?.packageName ?: "?"} versionName=$versionName " +
                    "(package chrome $packageMajor) — using UA chrome $major",
            )
        }
        return Info(pkg?.packageName, versionName, major)
    }

    private fun defaultUserAgent(context: Context): String? = try {
        WebSettings.getDefaultUserAgent(context)
    } catch (_: Exception) {
        null
    }

    private fun legacyWebViewVersionName(context: Context): String? {
        val pm = context.packageManager
        for (pkg in listOf("com.google.android.webview", "com.android.webview")) {
            try {
                @Suppress("DEPRECATION")
                return pm.getPackageInfo(pkg, 0).versionName
            } catch (_: Exception) {
                // try next package
            }
        }
        return null
    }

    fun parseMajorVersion(versionName: String?): Int {
        if (versionName.isNullOrBlank()) return 0
        val token = versionName.split('.', ' ', '-', '_').firstOrNull() ?: return 0
        return token.toIntOrNull() ?: 0
    }

    fun parseChromeMajorFromUserAgent(userAgent: String?): Int {
        if (userAgent.isNullOrBlank()) return 0
        val match = CHROME_TOKEN.find(userAgent) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    /**
     * Chromium major for syntax gates. The UA token wins. Package major is only
     * used when it looks like a real Chromium build — standalone Android WebView
     * never shipped below [MIN_PLAUSIBLE_CHROMIUM], so product versions such as
     * Portal `14.0.4…` stay unknown (permissive) when the UA is unreadable.
     */
    fun resolveMajorVersion(packageVersionName: String?, userAgent: String?): Int {
        val uaMajor = parseChromeMajorFromUserAgent(userAgent)
        if (uaMajor > 0) return uaMajor
        val packageMajor = parseMajorVersion(packageVersionName)
        return if (packageMajor >= MIN_PLAUSIBLE_CHROMIUM) packageMajor else 0
    }

    private const val TAG = "WebViewRuntime"

    /** Lollipop's first standalone WebView was Chromium ~37. */
    const val MIN_PLAUSIBLE_CHROMIUM = 37

    private val CHROME_TOKEN = Regex("""(?:Chrome|Chromium)/(\d+)""")
}
