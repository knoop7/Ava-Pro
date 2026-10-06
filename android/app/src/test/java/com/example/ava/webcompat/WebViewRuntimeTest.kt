package com.example.ava.webcompat

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewRuntimeTest {

    @Test
    fun parseMajorVersionReadsFirstToken() {
        assertEquals(131, WebViewRuntime.parseMajorVersion("131.0.6778.39"))
        assertEquals(83, WebViewRuntime.parseMajorVersion("83.0.4103.106"))
        assertEquals(14, WebViewRuntime.parseMajorVersion("14.0.4.0.0.767920214"))
        assertEquals(0, WebViewRuntime.parseMajorVersion(null))
        assertEquals(0, WebViewRuntime.parseMajorVersion(""))
    }

    @Test
    fun parseChromeMajorFromPortalUserAgent() {
        val ua =
            "Mozilla/5.0 (Linux; Android 10; Portal Build/QKQ1.210213.001; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/131.0.6778.39 Safari/537.36 AvaWebView"
        assertEquals(131, WebViewRuntime.parseChromeMajorFromUserAgent(ua))
    }

    @Test
    fun parseChromeMajorAcceptsChromiumToken() {
        assertEquals(
            118,
            WebViewRuntime.parseChromeMajorFromUserAgent(
                "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chromium/118.0.5993.88 Safari/537.36",
            ),
        )
    }

    @Test
    fun parseChromeMajorFromMissingTokenIsUnknown() {
        assertEquals(0, WebViewRuntime.parseChromeMajorFromUserAgent(null))
        assertEquals(0, WebViewRuntime.parseChromeMajorFromUserAgent("Mozilla/5.0 Safari/537.36"))
    }

    @Test
    fun resolveMajorPrefersUserAgentOverVendorPackageVersion() {
        val portalPackage = "14.0.4.0.0.767920214"
        val portalUa =
            "Mozilla/5.0 (Linux; Android 10; Portal; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.39 Safari/537.36"
        assertEquals(131, WebViewRuntime.resolveMajorVersion(portalPackage, portalUa))
    }

    @Test
    fun resolveMajorKeepsRealChromiumPackageWhenUaIsMissing() {
        assertEquals(83, WebViewRuntime.resolveMajorVersion("83.0.4103.106", null))
        assertEquals(131, WebViewRuntime.resolveMajorVersion("131.0.6778.39", null))
    }

    @Test
    fun resolveMajorTreatsImplausiblePackageAsUnknownWithoutUa() {
        assertEquals(0, WebViewRuntime.resolveMajorVersion("14.0.4.0.0.767920214", null))
        assertEquals(0, WebViewRuntime.resolveMajorVersion("14.0.4.0.0.767920214", "Mozilla/5.0"))
        assertEquals(0, WebViewRuntime.resolveMajorVersion(null, null))
    }

    @Test
    fun resolveMajorUsesUaWhenPackageLooksLikeChromium() {
        val ua =
            "Mozilla/5.0 (Linux; Android 9; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.106 Mobile Safari/537.36"
        assertEquals(83, WebViewRuntime.resolveMajorVersion("83.0.4103.106", ua))
    }
}
