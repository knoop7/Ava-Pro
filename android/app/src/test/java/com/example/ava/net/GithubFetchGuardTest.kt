package com.example.ava.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GithubFetchGuardTest {
    @Before
    fun setUp() {
        GithubFetchGuard.resetForTest()
    }

    @After
    fun tearDown() {
        GithubFetchGuard.resetForTest()
    }

    @Test
    fun cooldownSkipsFailedHostAndCapsAttempts() {
        val now = 1_000_000L
        val llkk = "https://gh.llkk.cc/https://api.github.com/repos/knoop7/Ava/releases"
        val proxy = "https://ghproxy.net/https://api.github.com/repos/knoop7/Ava/releases"
        val fast = "https://ghfast.top/https://api.github.com/repos/knoop7/Ava/releases"
        val direct = "https://api.github.com/repos/knoop7/Ava/releases"
        GithubFetchGuard.rememberFailure(llkk, nowMs = now)
        GithubFetchGuard.rememberFailure(proxy, nowMs = now)

        val next = GithubFetchGuard.filterCandidates(
            candidates = listOf(llkk, proxy, fast, direct),
            nowMs = now + 1_000L,
            maxAttempts = GithubFetchGuard.MAX_ATTEMPTS,
        )
        assertEquals(listOf(fast, direct), next)
        assertTrue(GithubFetchGuard.isCooling(llkk, now + 1_000L))
        assertFalse(GithubFetchGuard.isCooling(llkk, now + GithubFetchGuard.COOLDOWN_MS))
    }

    @Test
    fun allCoolingReturnsEmptyUnlessForced() {
        val now = 2_000_000L
        val urls = listOf(
            "https://gh.llkk.cc/https://api.github.com/x",
            "https://api.github.com/x",
        )
        urls.forEach { GithubFetchGuard.rememberFailure(it, nowMs = now) }

        assertTrue(
            GithubFetchGuard.filterCandidates(
                candidates = urls,
                nowMs = now + 1_000L,
                allowIfAllCooling = false,
            ).isEmpty(),
        )
        assertEquals(
            urls,
            GithubFetchGuard.filterCandidates(
                candidates = urls,
                nowMs = now + 1_000L,
                allowIfAllCooling = true,
            ),
        )
    }

    @Test
    fun successClearsCooldownAndStickyPrefix() {
        val url = "https://ghfast.top/https://api.github.com/repos/knoop7/Ava/releases"
        GithubFetchGuard.rememberFailure(url, nowMs = 0L)
        GithubFetchGuard.rememberSuccess(url)
        assertFalse(GithubFetchGuard.isCooling(url, nowMs = 1L))
        assertEquals("https://ghfast.top/", GithubFetchGuard.stickyPrefix)
    }
}
