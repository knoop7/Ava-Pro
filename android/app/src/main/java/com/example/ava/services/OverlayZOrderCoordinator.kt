package com.example.ava.services

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.example.ava.mods.ModMediaOverlayExclusive
import com.example.ava.settings.SettingsStyleSession
import com.example.ava.mods.ModOverlayZOrderBridge
import com.example.ava.notifications.FullscreenOverlayEscape
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.touchpad.TouchPadOverlay
import com.example.ava.ui.AvaToast
import com.example.ava.utils.OverlayRaiseCover
import com.example.ava.utils.ScreenBlankOverlay

/**
 * Reasserts Ava overlay z-order after the browser overlay is attached or refreshed.
 *
 * Android orders equal-type overlay windows by recent WindowManager operations. Browser Display is
 * a base/dashboard layer; voice, notification and control overlays must stay above it.
 *
 * Passive dashboard tier (below vinyl FAB): screensaver clock, dream clock, weather, quick entity.
 * Vinyl / mini cover FAB stays above that tier. The in-overlay «Back» dock rides inside
 * each of those windows (no second overlay). Active UI (HA switch, notifications, volume)
 * stays above vinyl. The Quick Wake mic is raised last among interactive overlays so Feishu /
 * captions / toasts cannot bury it. Smart AOD and the screen-blank plate still cover the mic.
 *
 * Browser vs expanded music:
 * - Mini FAB on: expanded player soft-collapses to FAB, then FAB stays above browser.
 * - Mini FAB off: fullscreen music stays, but browser is raised above it (like voice message),
 *   so HA browser_display / custom URL automation remains usable.
 */
object OverlayZOrderCoordinator {
    private const val TAG = "OverlayZOrder"
    private const val MIN_REASSERT_INTERVAL_MS = 250L

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastReassertAt = 0L

    /**
     * Bumped on every successful [bringToFront]. A restack is remove + re-add, which
     * destroys and recreates the window surface — repeating it on a *visible* window is
     * a blink. Windows that only need to stay above some other overlay can remember this
     * value at their own last restack and skip the work while nothing else has moved.
     */
    @Volatile
    var stackGeneration: Long = 0L
        private set

    /**
     * A fresh [WindowManager.addView] lands on top of every same-type window without
     * going through [bringToFront]. Call after that add so the mic can climb it.
     * [raiseVoiceButton] still no-ops for Smart AOD, the screen-blank plate,
     * a fullscreen notification scene, and app floating windows.
     */
    fun noteWindowAdded() {
        stackGeneration++
        FullscreenOverlayEscape.sync()
        if (QuickWakeFabService.isUserMoving()) return
        if (shouldSkipVoiceClimb()) return
        scheduleVoiceRaise()
    }

    private val voiceRaise = Runnable { raiseVoiceButton() }

    /**
     * Same-type overlays stack purely by add order: whatever was (re)added last
     * is on top. After any add or restack the mic has to go up again. Coalesced
     * onto the snapshot queue so the climb is covered (no blank frame). AOD /
     * blank still bail inside [raiseVoiceButton].
     */
    fun scheduleVoiceRaise() {
        mainHandler.removeCallbacks(voiceRaise)
        if (QuickWakeFabService.isUserMoving()) return
        if (shouldSkipVoiceClimb()) return
        OverlayRaiseCover.whenIdle(voiceRaise)
    }

    /**
     * Overlay is leaving. GONE / removeView does not bury the mic; a queued
     * remove+add only blanks the disc and the windows underneath.
     */
    fun cancelScheduledVoiceRaise() {
        mainHandler.removeCallbacks(voiceRaise)
        OverlayRaiseCover.cancelIdle(voiceRaise)
        FullscreenOverlayEscape.sync()
    }

    /**
     * FLAG_FULLSCREEN + layout-in-screen is the Esper / FAB / caption WM sublayer.
     * FLAG_HARDWARE_ACCELERATED matches Feishu TextureView windows so software-composited
     * mic windows can actually climb them. FLAG_NOT_TOUCH_MODAL is load-bearing:
     * vinyl / Feishu / AI page all carry it; without it many ROMs keep those
     * windows above a NOT_FOCUSABLE-only mic no matter how often we remove+add.
     */
    fun voiceSublayerFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS

    /**
     * Overlay + [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] + alpha > 0.80
     * makes WindowManager rewrite the alpha (and log). Mid-restack that rewrite
     * can drop the disc. Meet the cap ourselves; restore 1 when touchable.
     */
    fun applyNotTouchableWindowAlpha(params: WindowManager.LayoutParams) {
        val passThrough =
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        params.alpha = if (passThrough) 0.8f else 1f
    }

    fun applyVoiceSublayerLayout(params: WindowManager.LayoutParams) {
        PlatformCapabilities.applyDisplayCutoutShortEdges(params)
        OverlayOrientation.apply(params)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0)
        }
    }

    /**
     * Call at the end of a passive dashboard overlay's own [bringToFront]. The idle WebView
     * screensaver must cover passive dashboards, while the mini cover FAB stays
     * clickable above the screensaver.
     *
     * When the full now-playing surface is up, do **not** reassert the screensaver — it would
     * cover the song container (expanded player owns the stack via [VinylCoverService.isFullPlayerBlockingScreensaver]).
     *
     * After raising the screensaver, interactive overlays (mini FAB, Esper sphere /
     * captions, wake ripple, notification scenes) must be hard-raised again — otherwise a
     * screensaver restack buries them under the same TYPE_APPLICATION_OVERLAY tier.
     */
    fun raiseVinylFabAbovePassiveDashboard(raiseMic: Boolean = true) {
        if (!VinylCoverService.isFullPlayerBlockingScreensaver() &&
            !ScreensaverWebViewService.isSmartAodShowing()
        ) {
            ScreensaverWebViewService.bringToFrontIfVisible()
        }
        raiseInteractiveAboveScreensaver(raiseMic)
    }

    /**
     * Idle Smart AOD lives on the passive dashboard windows. remove+add of those
     * hosts destroys their surface for a frame (full-bright hole). Callers that
     * only need the FAB above that dimmed plate should skip restacking them.
     */
    fun isPassiveAodCovering(): Boolean {
        return ScreensaverService.isSmartAodCovering() ||
            QuickEntityOverlayService.isSmartAodCovering() ||
            ScreensaverWebViewService.isSmartAodShowing() ||
            DreamClockService.isSmartAodCovering()
    }

    /**
     * Idle Smart AOD covers the mic. Clock / vinyl / entity plates are the
     * cover. The Web screensaver (even with its own dim) is not — the disc
     * stays; the touch pad yields separately. A pinned Feishu / AI page does
     * not cancel that yield — opening the window used to restore the disc
     * on top of the plate.
     */
    fun shouldYieldVoiceToAod(): Boolean {
        return ScreensaverService.isSmartAodCovering() ||
            QuickEntityOverlayService.isSmartAodCovering() ||
            DreamClockService.isSmartAodCovering()
    }

    fun syncFabForAod() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { syncFabForAod() }
            return
        }
        if (shouldYieldVoiceToAod() || ScreenBlankOverlay.isShowing()) {
            QuickWakeFabService.yieldToAod()
        } else {
            QuickWakeFabService.restoreFromAod()
        }
        TouchPadOverlay.syncForCover()
    }

    /**
     * Voice mic above every Ava overlay except Smart AOD, the screen-blank plate,
     * a fullscreen notification scene, and app floating windows. Climbing the disc
     * over those is a flash; they are allowed to cover it.
     */
    fun raiseVoiceButton() {
        if (ScreenBlankOverlay.isShowing()) return
        if (shouldYieldVoiceToAod()) {
            QuickWakeFabService.yieldToAod()
            return
        }
        if (shouldSkipVoiceClimb()) return
        if (QuickWakeFabService.isUserMoving()) return
        QuickWakeFabService.raiseAboveVoiceOverlay()
    }

    /**
     * Fullscreen notification HUD may sit on top of the mic. Do not remove+add the
     * disc — that was the glyph flash when a scene opened over the screensaver.
     */
    fun shouldHoldVoiceUnderNotificationScene(): Boolean =
        NotificationOverlayService.isFullscreenShowing()

    /**
     * Do not remove+add the voice disc. Fullscreen notification scenes and app
     * floating windows may cover it; climbing them is a twitch.
     */
    fun shouldSkipVoiceClimb(): Boolean =
        shouldHoldVoiceUnderNotificationScene() || AppWindowService.hasWindowAttached()

    /**
     * Pinned app windows (Feishu etc.) land on top of the same overlay type via
     * [WindowManager.addView]. Voice captions may climb them; the mic stays put —
     * a small floating window does not need the disc restacked over it.
     */
    fun raiseVoiceAboveAppWindows() {
        if (ScreenBlankOverlay.isShowing()) return
        if (shouldYieldVoiceToAod()) {
            QuickWakeFabService.yieldToAod()
            return
        }
        if (FloatingWindowService.isOverlayShowing()) {
            FloatingWindowService.bringToFrontIfVisible(force = true, raiseMic = false)
        }
        if (shouldSkipVoiceClimb()) return
        raiseVoiceButton()
    }

    /**
     * Raise mini FAB / voice above an already-stacked idle screensaver.
     * Does not touch the screensaver window — safe to call from [ScreensaverWebViewService]
     * after its own hard raise (avoids recursion through [raiseVinylFabAbovePassiveDashboard]).
     */
    fun raiseInteractiveAboveScreensaver(raiseMic: Boolean = true) {
        if (ScreenBlankOverlay.isShowing()) {
            ScreenBlankOverlay.bringToFrontIfVisible(stackGeneration)
            return
        }
        // Vinyl mini FAB / Feishu / Esper first. The mic is its own window —
        // do not piggy-back it on the vinyl raise or it gets buried by the
        // rest of a later reassert pass.
        AppWindowService.bringPinnedToFront()
        VinylCoverService.bringToFrontIfVisible()
        DashboardOverlayChrome.bringToFront()
        FloatingWindowService.bringToFrontIfVisible(raiseMic = false)
        WakeRippleService.bringToFrontIfVisible()
        HaSwitchOverlayService.bringToFrontIfVisible()
        NotificationOverlayService.bringToFrontIfVisible()
        VoiceMessageOverlayService.bringToFrontIfVisible()
        VoiceMessagePlaybackOverlayService.bringToFrontIfVisible()
        VolumeControlService.bringToFrontIfVisible()
        ClockAlertOverlayService.bringToFrontIfVisible()
        SatelliteSetupTipOverlayService.bringToFrontIfVisible()
        ChorusWakeBlurService.bringToFrontIfVisible()
        if (raiseMic && !shouldSkipVoiceClimb()) scheduleVoiceRaise()
        ScreenBlankOverlay.bringToFrontIfVisible(stackGeneration)
    }

    /**
     * Call after the music overlay freshly appears (or is re-shown). Boot often raises
     * weather/browser before Sendspin metadata arrives; without a follow-up stack pass
     * the media layer can stay buried under passive dashboards until the next track.
     * Bypasses the reassert throttle so a show that lands right after browser boot still
     * gets a correct stack (browser above fullscreen music / FAB above browser).
     */
    fun onMediaOverlayPresented(context: Context) {
        lastReassertAt = 0L
        reassertForegroundOverlays(context)
    }

    /** Same as [reassertForegroundOverlays] but ignores the 250ms throttle. */
    fun reassertForegroundOverlaysNow(context: Context) {
        lastReassertAt = 0L
        reassertForegroundOverlays(context)
    }

    fun reassertForegroundOverlays(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastReassertAt < MIN_REASSERT_INTERVAL_MS) return
        lastReassertAt = now

        val appContext = context.applicationContext
        mainHandler.post {
            try {
                if (ScreenBlankOverlay.isShowing()) {
                    // Other restacks would jump above the blank plate and flash a bright frame.
                    ScreenBlankOverlay.bringToFrontIfVisible(stackGeneration)
                    FullscreenOverlayEscape.sync(appContext)
                    return@post
                }
                val browserVisible = WebViewService.isAnyBrowserOverlayActive() ||
                    AiBrowserService.isShowing()
                val miniArmed = VinylCoverService.isEqMiniArmed()

                // Mini FAB + browser: collapse expanded glass → FAB (does not hide music).
                if (
                    browserVisible &&
                    miniArmed &&
                    !SettingsStyleSession.overlaySplitEnabled.value
                ) {
                    VinylCoverService.yieldExpandedToForegroundOverlay()
                }

                // Passive dashboard overlays first (below vinyl FAB). Skip the
                // remove+add while Smart AOD is covering — restacking weather
                // would also jump it above the dimmed clock.
                if (!isPassiveAodCovering()) {
                    ScreensaverService.bringToFrontStatic()
                    DreamClockService.bringToFrontStatic()
                    WeatherOverlayService.bringToFrontStatic()
                    QuickEntityOverlayService.bringToFrontStatic()
                }

                // AirPlay / DLNA cinema owns media UI — do not raise Sendspin vinyl FAB.
                val modMediaExclusive = ModMediaOverlayExclusive.isActive(appContext)
                if (!modMediaExclusive) {
                    if (browserVisible &&
                        !miniArmed &&
                        VinylCoverService.isFullscreenWithoutMiniFab()
                    ) {
                        // No FAB: keep fullscreen music above weather, then let in-process
                        // browser cover it (voice-message pattern). Always raise vinyl first —
                        // skipping that left music buried under weather after boot reassert,
                        // especially with a gecko peer (foreign process) marked visible.
                        raiseVinylFabAbovePassiveDashboard(raiseMic = false)
                        if (WebViewService.isBrowserOverlayVisible() &&
                            !OverlayLayerSplit.holdsPanes()
                        ) {
                            WebViewService.bringToFrontIfVisible()
                        }
                        // Gecko pack (peer process): vinyl stays above weather; the foreign
                        // browser window may still sit on top via its own WM order.
                    } else {
                        raiseVinylFabAbovePassiveDashboard(raiseMic = false)
                    }
                }

                // Media-style mod overlays (DLNA Cinema, AirPlay, etc.) — above dashboard, below voice UI.
                ModOverlayZOrderBridge.reassertBelowVoiceOverlays(appContext)

                // The AI page stays above passive dashboards regardless of music mode.
                AiBrowserService.bringToFrontIfVisible()

                // Pinned floating app windows: above the dashboard / browser / media
                // tier, below the transient interaction overlays raised next.
                AppWindowService.bringPinnedToFront()

                FloatingWindowService.bringToFrontIfVisible(
                    force = AiBrowserService.isShowing(),
                    raiseMic = false,
                )
                WakeRippleService.bringToFrontIfVisible()
                HaSwitchOverlayService.bringToFrontIfVisible()
                NotificationOverlayService.bringToFrontIfVisible()
                VoiceMessageOverlayService.bringToFrontIfVisible()
                VoiceMessagePlaybackOverlayService.bringToFrontIfVisible()
                VolumeControlService.bringToFrontIfVisible()
                ClockAlertOverlayService.bringToFrontIfVisible()
                SatelliteSetupTipOverlayService.bringToFrontIfVisible()
                AvaToast.bringToFrontIfVisible()
                ModOverlayZOrderBridge.reassertTopOverlays(appContext)
                ChorusWakeBlurService.bringToFrontIfVisible()
                // Mic last — after the snapshot queue, so disc / STT sit above Esper.
                // Fullscreen scenes / app floating windows may cover the disc; do not climb it.
                if (!shouldSkipVoiceClimb()) scheduleVoiceRaise()
                ScreenBlankOverlay.bringToFrontIfVisible(stackGeneration)
                FullscreenOverlayEscape.sync(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reassert overlay order", e)
            }
        }
    }

    fun requestHostReassert(context: Context) {
        try {
            context.sendBroadcast(
                android.content.Intent(com.example.ava.webcompat.BrowserEngine.ACTION_REASSERT_FOREGROUND_OVERLAYS)
                    .setPackage(com.example.ava.webcompat.BrowserEngine.HOST_PACKAGE)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request host overlay reassert", e)
        }
    }

    fun bringToFront(
        windowManager: WindowManager?,
        view: View?,
        params: WindowManager.LayoutParams?,
        tag: String,
        isVoiceWindow: Boolean = false,
        mask: Boolean = true,
        raiseMic: Boolean = true,
        onDone: ((Boolean) -> Unit)? = null,
    ): Boolean {
        if (windowManager == null || view == null || params == null || !view.isAttachedToWindow) {
            onDone?.invoke(false)
            return false
        }
        // Split panes sit side by side or stacked. Restacking one puts it over the other.
        // The web screensaver is not a pane and still climbs above both.
        if (OverlayLayerSplit.isPaneView(view)) {
            onDone?.invoke(true)
            return true
        }
        OverlayOrientation.apply(params)
        val restack = {
            // The pane may have been applied after this raise was queued.
            // remove+add here puts the fullscreen params back on top.
            if (OverlayLayerSplit.isPaneView(view)) {
                true
            } else {
                val visibility = view.visibility
                val alpha = view.alpha
                try {
                    windowManager.removeView(view)
                    windowManager.addView(view, params)
                    view.visibility = visibility
                    view.alpha = alpha
                    if (ScreenBlankOverlay.isShowing()) {
                        ScreenBlankOverlay.bringToFrontIfVisible(stackGeneration)
                    }
                    true
                } catch (e: Exception) {
                    Log.w(tag, "Failed to bring overlay to front", e)
                    false
                }
            }
        }
        // GONE / faded windows still occupy the tier, but climbing the mic
        // after they hide is a blank frame — hide paths call [cancelScheduledVoiceRaise].
        val climbMic = raiseMic &&
            !isVoiceWindow &&
            !shouldSkipVoiceClimb() &&
            view.visibility == View.VISIBLE &&
            view.alpha > 0.02f
        // Caller already has a snapshot up (Feishu TextureView / mic restack lambda).
        if (!mask) {
            val ok = restack()
            if (ok && climbMic) {
                stackGeneration++
                scheduleVoiceRaise()
            }
            onDone?.invoke(ok)
            return ok
        }
        // Voice windows still take the snapshot queue so disc → STT → Esper stay
        // in raise order. They must not bump generation or the whole dashboard
        // cascade restacks on every mic raise.
        if (climbMic) {
            stackGeneration++
            scheduleVoiceRaise()
        }
        return OverlayRaiseCover.run(windowManager, view, params, restack, onDone)
    }
}
