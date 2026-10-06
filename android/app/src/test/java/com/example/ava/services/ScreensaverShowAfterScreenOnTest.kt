package com.example.ava.services

import com.example.ava.settings.ScreensaverSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverShowAfterScreenOnTest {
    private val panelOnAt = 10_000L

    private fun ready(
        timeoutSeconds: Int = 0,
        url: String = "https://example.test/kiosk",
        dawn: Boolean = false,
        haDisplay: Boolean = false,
        twoWay: Boolean = false,
        visible: Boolean = true,
        showAfterScreenOn: Boolean = true,
        enabled: Boolean = true,
    ) = ScreensaverSettings(
        enabled = enabled,
        showAfterScreenOn = showAfterScreenOn,
        timeoutSeconds = timeoutSeconds,
        screensaverUrl = url,
        dawnWallpaperEnabled = dawn,
        enableHaDisplay = haDisplay,
        haSwitchTwoWayEnabled = twoWay,
        visible = visible,
    )

    private fun decide(
        settings: ScreensaverSettings = ready(),
        panelStillOn: Boolean = true,
        satelliteStarted: Boolean = true,
        alreadyVisible: Boolean = false,
        softPaused: Boolean = false,
        suppressed: Boolean = false,
        lastInteractionAt: Long = 0L,
    ) = shouldShowScreensaverAfterScreenOn(
        settings = settings,
        panelStillOn = panelStillOn,
        satelliteStarted = satelliteStarted,
        alreadyVisible = alreadyVisible,
        softPaused = softPaused,
        suppressed = suppressed,
        lastInteractionAt = lastInteractionAt,
        panelOnAt = panelOnAt,
    )

    @Test fun timeoutZeroStillShowsTheConfiguredPage() {
        assertTrue(decide(ready(timeoutSeconds = 0)))
    }

    @Test fun dawnShowsEvenWhenTheCustomUrlIsEmpty() {
        assertTrue(decide(ready(url = "", dawn = true)))
    }

    @Test fun switchOffDoesNotShow() {
        assertFalse(decide(ready(showAfterScreenOn = false)))
        assertFalse(decide(ready(enabled = false)))
    }

    @Test fun blankUrlDoesNotShow() {
        assertFalse(decide(ready(url = "  ")))
    }

    @Test fun touchOrProximityWakeLeavesThePageUnderneath() {
        assertFalse(decide(lastInteractionAt = panelOnAt))
        assertFalse(decide(lastInteractionAt = panelOnAt - SCREEN_ON_USER_WAKE_WINDOW_MS))
    }

    @Test fun olderIdleStillShows() {
        assertTrue(decide(lastInteractionAt = panelOnAt - SCREEN_ON_USER_WAKE_WINDOW_MS - 1))
    }

    @Test fun panelOffOrAlreadyCoveredDoesNotShow() {
        assertFalse(decide(panelStillOn = false))
        assertFalse(decide(alreadyVisible = true))
        assertFalse(decide(softPaused = true))
        assertFalse(decide(suppressed = true))
        assertFalse(decide(satelliteStarted = false))
    }

    @Test fun controlModeOffStaysOff() {
        assertFalse(decide(ready(haDisplay = true, twoWay = false, visible = false)))
    }

    @Test fun twoWayMayShowAgainAfterADismiss() {
        assertTrue(decide(ready(haDisplay = true, twoWay = true, visible = false)))
    }
}
