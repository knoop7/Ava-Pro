package com.example.ava.net

import org.junit.Assert.assertEquals
import org.junit.Test

class GithubProxyUrlsTest {
    private val api = "https://api.github.com/repos/knoop7/Ava/releases"
    private val raw = "https://raw.githubusercontent.com/knoop7/Ava/master/version.json"

    @Test
    fun otherLocalesOfficialOnly() {
        assertEquals(listOf(api), GithubProxyUrls.candidates(null, api, preferProxy = false))
        assertEquals(listOf(raw), GithubProxyUrls.candidates(null, raw, preferProxy = false))
        assertEquals(
            listOf(api),
            GithubProxyUrls.candidates(
                null,
                api,
                preferredPrefix = "https://ghfast.top/",
                preferProxy = false,
            ),
        )
    }

    @Test
    fun zhRuMirrorsThenOfficial() {
        val urls = GithubProxyUrls.candidates(null, api, preferProxy = true)
        assertEquals(GithubProxyUrls.PROXY_PREFIXES.size + 1, urls.size)
        assertEquals("https://gh-proxy.com/$api", urls.first())
        assertEquals(api, urls.last())
    }
}
