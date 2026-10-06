package com.example.ava.notifications

import android.app.PendingIntent
import android.os.Build
import com.example.ava.platform.PlatformCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenOverlayEscapeTest {

    @Test
    fun pendingIntentFlagsOnLollipopOmitImmutable() {
        val flags = PlatformCapabilities.pendingIntentFlagsForSdk(Build.VERSION_CODES.LOLLIPOP)
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT, flags)
        assertEquals(0, flags and immutableBit())
    }

    @Test
    fun pendingIntentFlagsFromMarshmallowIncludeImmutable() {
        val m = PlatformCapabilities.pendingIntentFlagsForSdk(Build.VERSION_CODES.M)
        val s = PlatformCapabilities.pendingIntentFlagsForSdk(Build.VERSION_CODES.S)
        val baklava = PlatformCapabilities.pendingIntentFlagsForSdk(36)
        assertTrue(m and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(s and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(baklava and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(m and PendingIntent.FLAG_UPDATE_CURRENT != 0)
    }

    @Test
    fun notificationChannelRequiredFromOreo() {
        assertTrue(usesNotificationChannel(26))
        assertFalse(usesNotificationChannel(25))
        assertTrue(usesNotificationChannel(36))
    }

    @Test
    fun postNotificationsRequiredFromTiramisu() {
        assertFalse(needsPostNotifications(32))
        assertTrue(needsPostNotifications(33))
        assertTrue(needsPostNotifications(36))
    }

    @Test
    fun promotedOngoingOnlyOnAndroid16() {
        assertFalse(requestPromotedOngoing(35))
        assertTrue(requestPromotedOngoing(36))
    }

    @Test
    fun escapeKindsCoverEveryTouchableFullscreenLayer() {
        val kinds = FullscreenOverlayEscape.Kind.entries.map { it.name }.toSet()
        assertEquals(
            setOf(
                "BROWSER",
                "SIMPLE_CLOCK",
                "DREAM_CLOCK",
                "WEATHER",
                "QUICK_ENTITY",
                "MEDIA_PLAYER",
                "NOTIFICATION_SCENE",
                "VOICE_MESSAGE",
                "VOICE_PLAYBACK",
                "HA_SWITCH",
                "SATELLITE_TIP",
                "SCREENSAVER_WEB",
                "AI_BROWSER",
                "APP_WINDOW",
                "SCREEN_BLANK",
            ),
            kinds,
        )
    }

    @Test
    fun actionIsACompleteBroadcastCommand() {
        assertEquals(
            "com.example.ava.ACTION_EXIT_FULLSCREEN_OVERLAYS",
            FullscreenOverlayEscape.ACTION_EXIT,
        )
        assertEquals(
            "com.example.ava.ACTION_RELOAD_FULLSCREEN_OVERLAY",
            FullscreenOverlayEscape.ACTION_RELOAD,
        )
        assertEquals(21, FullscreenOverlayEscape.NOTIFICATION_ID)
        assertEquals("FullscreenOverlayEscape", FullscreenOverlayEscape.CHANNEL_ID)
        assertEquals("overlay_kind", FullscreenOverlayEscape.EXTRA_KIND)
    }

    @Test
    fun eachKindHasItsOwnTitle() {
        val titles = FullscreenOverlayEscape.Kind.entries.map { it.titleRes }
        assertEquals(titles.size, titles.toSet().size)
        FullscreenOverlayEscape.Kind.entries.forEach { kind ->
            assertEquals(FullscreenOverlayEscape.NOTIFICATION_ID + kind.ordinal, kind.notificationId())
        }
    }

    private fun usesNotificationChannel(sdkInt: Int): Boolean = sdkInt >= 26

    private fun needsPostNotifications(sdkInt: Int): Boolean = sdkInt >= 33

    private fun requestPromotedOngoing(sdkInt: Int): Boolean = sdkInt >= 36

    private fun immutableBit(): Int = PendingIntent.FLAG_IMMUTABLE
}
