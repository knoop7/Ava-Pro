package com.example.ava.utils

import com.example.ava.homeassistant.HaMediaAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HaMediaUrlTest {

    @Before
    fun resetLearnedPort() {
        HaMediaUrl.clearLearnedPort()
        HaMediaAuth.clear()
    }

    @Test
    fun relativePathUsesPeerHost() {
        assertEquals(
            "http://192.168.1.10:8123/api/tts_proxy/abc.mp3",
            HaMediaUrl.resolve("/api/tts_proxy/abc.mp3", "192.168.1.10"),
        )
    }

    @Test
    fun relativePathWithoutHostStaysRelative() {
        assertEquals("/api/tts_proxy/abc.mp3", HaMediaUrl.resolve("/api/tts_proxy/abc.mp3", null))
    }

    @Test
    fun absoluteTtsProxyHostIsRewrittenToPeer() {
        assertEquals(
            "http://192.168.1.10:8123/api/tts_proxy/abc.mp3",
            HaMediaUrl.resolve(
                "https://wrong.example:8123/api/tts_proxy/abc.mp3",
                "192.168.1.10",
            ),
        )
    }

    @Test
    fun absoluteTtsProxyKeepsQuery() {
        assertEquals(
            "http://192.168.1.10:8123/api/tts_proxy/abc.mp3?token=1",
            HaMediaUrl.resolve(
                "http://ha.local:8123/api/tts_proxy/abc.mp3?token=1",
                "192.168.1.10",
            ),
        )
    }

    @Test
    fun matchingHostIsUnchanged() {
        val url = "http://192.168.1.10:8123/api/tts_proxy/abc.mp3"
        assertEquals(url, HaMediaUrl.resolve(url, "192.168.1.10"))
    }

    @Test
    fun absoluteCameraProxyHostIsRewrittenToPeer() {
        assertEquals(
            "http://192.168.1.10:8123/api/camera_proxy/camera.doorbell?token=abc",
            HaMediaUrl.resolve(
                "https://ha.example:8123/api/camera_proxy/camera.doorbell?token=abc",
                "192.168.1.10",
            ),
        )
    }

    @Test
    fun relativeCameraProxyUsesPeerHost() {
        assertEquals(
            "http://192.168.1.10:8123/api/camera_proxy/camera.doorbell?token=abc",
            HaMediaUrl.resolve(
                "/api/camera_proxy/camera.doorbell?token=abc",
                "192.168.1.10",
            ),
        )
    }

    @Test
    fun nonProxyAbsoluteUrlIsUnchanged() {
        val url = "http://cdn.example/music.mp3"
        assertEquals(url, HaMediaUrl.resolve(url, "192.168.1.10"))
    }

    @Test
    fun localSchemesAreUnchanged() {
        assertEquals("asset:///sounds/a.wav", HaMediaUrl.resolve("asset:///sounds/a.wav", "192.168.1.10"))
    }

    @Test
    fun blankReturnsBlank() {
        assertEquals("", HaMediaUrl.resolve("", "192.168.1.10"))
        assertNull(HaMediaUrl.resolve(null, "192.168.1.10"))
    }

    @Test
    fun preserveHttpsLeavesAbsoluteHttpsUnchanged() {
        val url = "https://ha.example:8123/api/tts_proxy/abc.mp3"
        assertEquals(
            url,
            HaMediaUrl.resolve(url, "192.168.1.10", preserveHttps = true),
        )
    }

    @Test
    fun preserveHttpsStillRewritesAbsoluteHttp() {
        assertEquals(
            "http://192.168.1.10:8123/api/tts_proxy/abc.mp3",
            HaMediaUrl.resolve(
                "http://ha.example:8123/api/tts_proxy/abc.mp3",
                "192.168.1.10",
                preserveHttps = true,
            ),
        )
    }

    @Test
    fun preserveHttpsRelativeUsesHttps() {
        assertEquals(
            "https://192.168.1.10:8123/api/tts_proxy/abc.mp3",
            HaMediaUrl.resolve("/api/tts_proxy/abc.mp3", "192.168.1.10", preserveHttps = true),
        )
    }

    @Test
    fun rewriteKeepsExplicitNonDefaultPort() {
        assertEquals(
            "http://192.168.1.10:9123/api/tts_proxy/abc.mp3",
            HaMediaUrl.resolve(
                "http://ha.example:9123/api/tts_proxy/abc.mp3",
                "192.168.1.10",
            ),
        )
    }

    @Test
    fun rewriteImplicitPortFallsBackTo8123() {
        // Reverse-proxy style URL without an explicit port: the proxy's 80/443
        // is not HA's direct listener, so the historical 8123 fallback applies.
        assertEquals(
            "http://192.168.1.10:8123/api/tts_proxy/abc.mp3",
            HaMediaUrl.resolve(
                "https://ha.example/api/tts_proxy/abc.mp3",
                "192.168.1.10",
            ),
        )
    }

    @Test
    fun relativePathUsesLearnedPort() {
        HaMediaUrl.noteHaUrl("http://ha.example:9123/api/tts_proxy/seen.mp3")
        assertEquals(
            "http://192.168.1.10:9123/api/tts_proxy/abc.mp3",
            HaMediaUrl.resolve("/api/tts_proxy/abc.mp3", "192.168.1.10"),
        )
    }

    @Test
    fun resolveLearnsPortFromAbsoluteProxyUrl() {
        // Seeing an absolute proxy URL with an explicit port teaches the port
        // used later for relative joins.
        HaMediaUrl.resolve("http://ha.example:9123/api/tts_proxy/seen.mp3", "192.168.1.10")
        assertEquals(9123, HaMediaUrl.haPortOrDefault)
        assertEquals(
            "http://192.168.1.10:9123/api/tts_proxy/abc.mp3",
            HaMediaUrl.resolve("/api/tts_proxy/abc.mp3", "192.168.1.10"),
        )
    }

    @Test
    fun implicitPortUrlDoesNotChangeLearnedPort() {
        HaMediaUrl.noteHaUrl("https://ha.example/lovelace")
        assertEquals(8123, HaMediaUrl.haPortOrDefault)
    }

    @Test
    fun nonHttpUrlDoesNotChangeLearnedPort() {
        HaMediaUrl.noteHaUrl("ftp://ha.example:2121/x")
        HaMediaUrl.noteHaUrl("not a url")
        assertEquals(8123, HaMediaUrl.haPortOrDefault)
    }

    @Test
    fun ipv6HostIsBracketedInRelativeJoin() {
        assertEquals(
            "http://[2001:db8::1]:8123/api/camera_proxy/camera.x?token=abc",
            HaMediaUrl.resolve("/api/camera_proxy/camera.x?token=abc", "2001:db8::1"),
        )
    }

    @Test
    fun ipv6ZoneIdIsDropped() {
        assertEquals(
            "http://[fe80::1]:8123/api/tts_proxy/abc.mp3",
            HaMediaUrl.resolve("/api/tts_proxy/abc.mp3", "fe80::1%wlan0"),
        )
    }

    @Test
    fun alreadyBracketedIpv6IsUnchanged() {
        assertEquals(
            "[2001:db8::1]",
            HaMediaUrl.hostForUrl("[2001:db8::1]"),
        )
    }

    @Test
    fun cameraFetchCandidatesAddsHttpsAndRemote() {
        val candidates = HaMediaUrl.cameraFetchCandidates(
            "http://172.30.32.1:8123/api/camera_proxy/camera.x?token=abc",
            "https://ha.example.com:8123/lovelace",
        )
        assertEquals(
            listOf(
                "http://172.30.32.1:8123/api/camera_proxy/camera.x?token=abc",
                "https://172.30.32.1:8123/api/camera_proxy/camera.x?token=abc",
                "https://ha.example.com:8123/api/camera_proxy/camera.x?token=abc",
            ),
            candidates,
        )
    }

    @Test
    fun resolveRedirectJoinsRelativeLocation() {
        assertEquals(
            "https://ha.example:8123/api/camera_proxy_stream/camera.x",
            HaMediaUrl.resolveRedirect(
                "http://ha.example:8123/api/camera_proxy_stream/camera.x",
                "https://ha.example:8123/api/camera_proxy_stream/camera.x",
            ),
        )
        assertEquals(
            "http://192.168.1.10:8123/api/camera_proxy_stream/camera.x",
            HaMediaUrl.resolveRedirect(
                "http://192.168.1.10:8123/api/camera_proxy/camera.x",
                "/api/camera_proxy_stream/camera.x",
            ),
        )
        assertNull(HaMediaUrl.resolveRedirect("http://ha.example:8123/api/camera_proxy/camera.x", ""))
        assertNull(HaMediaUrl.resolveRedirect("http://ha.example:8123/api/camera_proxy/camera.x", "ftp://x"))
    }

    @Test
    fun rejectedStreamTypesSkipHlsAndHtml() {
        assertTrue(HaMediaUrl.isRejectedCameraStreamType("application/vnd.apple.mpegurl"))
        assertTrue(HaMediaUrl.isRejectedCameraStreamType("text/html; charset=utf-8"))
        assertFalse(HaMediaUrl.isRejectedCameraStreamType("multipart/x-mixed-replace;boundary=frame"))
        assertFalse(HaMediaUrl.isRejectedCameraStreamType("image/jpeg"))
        assertFalse(HaMediaUrl.isRejectedCameraStreamType(""))
    }

    @Test
    fun streamUrlFromSnapshotSwapsProxyPath() {
        assertEquals(
            "http://192.168.1.10:8123/api/camera_proxy_stream/camera.x?token=abc",
            HaMediaUrl.streamUrlFromSnapshot(
                "http://192.168.1.10:8123/api/camera_proxy/camera.x?token=abc",
            ),
        )
        val alreadyStream = "http://192.168.1.10:8123/api/camera_proxy_stream/camera.x?token=abc"
        assertEquals(alreadyStream, HaMediaUrl.streamUrlFromSnapshot(alreadyStream))
        assertTrue(HaMediaUrl.isCameraStreamUrl(alreadyStream))
        assertFalse(
            HaMediaUrl.isCameraStreamUrl(
                "http://192.168.1.10:8123/api/camera_proxy/camera.x?token=abc",
            ),
        )
    }

    @Test
    fun cameraUrlIdentityIgnoresRotatingToken() {
        assertEquals(
            HaMediaUrl.cameraUrlIdentity(
                "http://192.168.1.10:8123/api/camera_proxy/camera.x?token=aaa",
            ),
            HaMediaUrl.cameraUrlIdentity(
                "http://192.168.1.10:8123/api/camera_proxy/camera.x?token=bbb",
            ),
        )
        assertEquals(
            "http://192.168.1.10:8123/api/camera_proxy/camera.x",
            HaMediaUrl.cameraUrlIdentity(
                "http://192.168.1.10:8123/api/camera_proxy/camera.x?token=aaa",
            ),
        )
        assertEquals(
            "http://192.168.1.10:8123/api/camera_proxy/camera.x?width=640",
            HaMediaUrl.cameraUrlIdentity(
                "http://192.168.1.10:8123/api/camera_proxy/camera.x?token=aaa&width=640",
            ),
        )
    }

    @Test
    fun signedInRelativePathUsesSessionOrigin() {
        HaMediaAuth.update("https://ha.example:8123", "secret-token")
        assertEquals(
            "https://ha.example:8123/api/camera_proxy/camera.x?token=abc",
            HaMediaUrl.resolvePreferringSignedIn(
                "/api/camera_proxy/camera.x?token=abc",
                "192.168.1.10",
            ),
        )
    }

    @Test
    fun signedInCameraProxyOmitsQueryToken() {
        HaMediaAuth.update("https://ha.example:8123", "secret-token")
        assertEquals(
            "https://ha.example:8123/api/camera_proxy/camera.front",
            HaMediaUrl.signedInCameraProxyUrl("camera.front"),
        )
        assertEquals(
            "https://ha.example:8123/api/camera_proxy_stream/camera.front",
            HaMediaUrl.signedInCameraStreamUrl("camera.front"),
        )
        assertEquals(
            "https://ha.example:8123/api/camera_proxy_stream/camera.front?token=abc",
            HaMediaUrl.signedInCameraStreamUrl("camera.front", "abc"),
        )
    }

    @Test
    fun copyQueryTokenOntoBareStreamUrl() {
        assertEquals(
            "https://ha.example:8123/api/camera_proxy_stream/camera.x?token=abc",
            HaMediaUrl.copyQueryToken(
                "http://192.168.1.10:8123/api/camera_proxy/camera.x?token=abc",
                "https://ha.example:8123/api/camera_proxy_stream/camera.x",
            ),
        )
        assertNull(
            HaMediaUrl.copyQueryToken(
                "http://192.168.1.10:8123/api/camera_proxy/camera.x",
                "https://ha.example:8123/api/camera_proxy_stream/camera.x",
            ),
        )
    }

    @Test
    fun unsignedInRewritesLastSignedInCameraUrlToPeer() {
        HaMediaAuth.clear()
        assertEquals(
            "http://192.168.1.10:8123/api/camera_proxy/camera.x?token=abc",
            HaMediaUrl.resolve(
                "https://ha.example:8123/api/camera_proxy/camera.x?token=abc",
                "192.168.1.10",
            ),
        )
    }

    @Test
    fun unsignedInCameraProxyUrlIsNull() {
        assertNull(HaMediaUrl.signedInCameraProxyUrl("camera.front"))
        assertNull(HaMediaUrl.signedInCameraStreamUrl("camera.front"))
    }

    @Test
    fun hlsPlaylistIsDetectedAndJoinsSignedInOrigin() {
        HaMediaAuth.update("https://ha.example:8123", "secret-token")
        assertTrue(HaMediaUrl.isHlsStreamUrl("/api/hls/abc/master_playlist.m3u8"))
        assertTrue(
            HaMediaUrl.isHlsStreamUrl("https://ha.example:8123/api/hls/abc/master_playlist.m3u8"),
        )
        assertFalse(HaMediaUrl.isCameraStreamUrl("/api/hls/abc/master_playlist.m3u8"))
        assertEquals(
            "https://ha.example:8123/api/hls/abc/master_playlist.m3u8",
            HaMediaUrl.resolvePreferringSignedIn("/api/hls/abc/master_playlist.m3u8", "192.168.1.10"),
        )
        assertTrue(
            HaMediaUrl.shouldAttachBearer(
                "https://ha.example:8123/api/hls/abc/master_playlist.m3u8",
            ),
        )
    }

    fun bearerOnlyAttachesToHaProxyUrls() {
        HaMediaAuth.update("https://ha.example:8123", "secret-token")
        assertTrue(
            HaMediaUrl.shouldAttachBearer(
                "https://ha.example:8123/api/camera_proxy/camera.x",
            ),
        )
        assertTrue(
            HaMediaUrl.shouldAttachBearer(
                "https://ha.example:8123/api/camera_proxy_stream/camera.x",
            ),
        )
        assertTrue(
            HaMediaUrl.shouldAttachBearer(
                "https://ha.example:8123/api/media_player_proxy/media_player.office",
            ),
        )
        assertFalse(HaMediaUrl.shouldAttachBearer("https://cdn.example/cover.jpg"))
    }

    @Test
    fun disabledSwitchDoesNotAttachBearer() {
        HaMediaAuth.update("https://ha.example:8123", "secret-token", enabled = false)
        assertFalse(
            HaMediaUrl.shouldAttachBearer(
                "https://ha.example:8123/api/camera_proxy/camera.x",
            ),
        )
        assertNull(HaMediaUrl.signedInCameraProxyUrl("camera.front"))
        assertNull(HaMediaUrl.signedInCameraStreamUrl("camera.front"))
    }

    @Test
    fun unsignedInDoesNotAttachBearer() {
        assertFalse(
            HaMediaUrl.shouldAttachBearer(
                "http://192.168.1.10:8123/api/camera_proxy/camera.x",
            ),
        )
    }

    @Test
    fun cameraFetchCandidatesPrefersSignedInOrigin() {
        HaMediaAuth.update("https://ha.example.com:8123", "secret-token")
        val candidates = HaMediaUrl.cameraFetchCandidates(
            "http://172.30.32.1:8123/api/camera_proxy/camera.x?token=abc",
            haRemoteUrl = null,
        )
        assertEquals(
            listOf(
                "http://172.30.32.1:8123/api/camera_proxy/camera.x?token=abc",
                "https://172.30.32.1:8123/api/camera_proxy/camera.x?token=abc",
                "https://ha.example.com:8123/api/camera_proxy/camera.x?token=abc",
            ),
            candidates,
        )
    }

    @Test
    fun mediaPlayerProxyHostIsRewrittenToPeer() {
        assertEquals(
            "http://192.168.1.10:8123/api/media_player_proxy/media_player.office",
            HaMediaUrl.resolve(
                "https://wrong.example:8123/api/media_player_proxy/media_player.office",
                "192.168.1.10",
            ),
        )
    }
}
