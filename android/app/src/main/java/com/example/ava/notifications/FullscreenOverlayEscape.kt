package com.example.ava.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.ava.R
import com.example.ava.platform.PlatformCapabilities
import com.example.ava.receivers.FullscreenOverlayEscapeReceiver
import com.example.ava.services.AiBrowserService
import com.example.ava.services.AppWindowService
import com.example.ava.services.DreamClockService
import com.example.ava.services.HaSwitchOverlayService
import com.example.ava.services.NotificationOverlayService
import com.example.ava.services.QuickEntityOverlayService
import com.example.ava.services.SatelliteSetupTipOverlayService
import com.example.ava.services.ScreensaverService
import com.example.ava.services.ScreensaverWebViewService
import com.example.ava.services.VinylCoverService
import com.example.ava.services.VoiceMessageOverlayService
import com.example.ava.services.VoiceMessagePlaybackOverlayService
import com.example.ava.services.WeatherOverlayService
import com.example.ava.services.WebViewService
import com.example.ava.settings.BrowserSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.ui.AvaSystemChrome
import com.example.ava.ui.screens.home.HomeSidebarActions
import com.example.ava.utils.ScreenBlankOverlay
import com.example.ava.webcompat.EngineCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Dedicated shade command for every **touchable fullscreen** overlay.
 *
 * The in-window «Back» chrome can hide or miss a tap. This notification is the
 * second exit: pull the shade, tap the row or Exit, every trapping layer closes
 * and HA `*_display` flags stay in sync.
 *
 * Included (MATCH_PARENT and consumes touches):
 * browser, Simple Clock, Dream Clock, weather, Quick Entity, expanded media,
 * fullscreen notification scene, voice message, inbound voice playback,
 * light-switch reminder, satellite setup tip, Web screensaver, AI page,
 * floating app windows, screen-blank plate.
 *
 * Excluded (cannot trap): voice FAB, floating captions, wake ripple, chorus
 * blur, volume HUD, notification banner, touch pad.
 *
 * Android 5–7: no channel, [NotificationCompat.Builder] without id.
 * Android 8–12: silent DEFAULT channel.
 * Android 13–15: also needs [Manifest.permission.POST_NOTIFICATIONS].
 * Android 16: same plus a promoted-ongoing request so the row can sit at the
 * top of the shade / status-bar chip.
 */
object FullscreenOverlayEscape {
    private const val TAG = "OverlayEscape"
    const val ACTION_EXIT = "com.example.ava.ACTION_EXIT_FULLSCREEN_OVERLAYS"
    const val ACTION_RELOAD = "com.example.ava.ACTION_RELOAD_FULLSCREEN_OVERLAY"
    const val EXTRA_KIND = "overlay_kind"
    const val CHANNEL_ID = "FullscreenOverlayEscape"
    const val NOTIFICATION_ID = 21

    private const val SYNC_DEBOUNCE_MS = 160L
    private const val RESYNC_AFTER_HIDE_MS = 400L
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    enum class Kind(val titleRes: Int) {
        BROWSER(R.string.overlay_escape_title_browser),
        SIMPLE_CLOCK(R.string.overlay_escape_title_simple_clock),
        DREAM_CLOCK(R.string.overlay_escape_title_dream_clock),
        WEATHER(R.string.overlay_escape_title_weather),
        QUICK_ENTITY(R.string.overlay_escape_title_quick_entity),
        MEDIA_PLAYER(R.string.overlay_escape_title_media_player),
        NOTIFICATION_SCENE(R.string.overlay_escape_title_notification_scene),
        VOICE_MESSAGE(R.string.overlay_escape_title_voice_message),
        VOICE_PLAYBACK(R.string.overlay_escape_title_voice_playback),
        HA_SWITCH(R.string.overlay_escape_title_ha_switch),
        SATELLITE_TIP(R.string.overlay_escape_title_satellite_tip),
        SCREENSAVER_WEB(R.string.overlay_escape_title_screensaver_web),
        AI_BROWSER(R.string.overlay_escape_title_ai_browser),
        APP_WINDOW(R.string.overlay_escape_title_app_window),
        SCREEN_BLANK(R.string.overlay_escape_title_screen_blank),
        ;

        fun notificationId(): Int = NOTIFICATION_ID + ordinal
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    /** Sidebar + HA `browser_display`. Off hides the shade even while the WebView is still dying. */
    @Volatile
    private var browserSwitchOn = false

    private val postedKinds = linkedSetOf<Kind>()

    private val syncRunnable = Runnable {
        appContext?.let { syncNow(it) }
    }

    fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        createChannel(app)
        scope.launch {
            BrowserSettingsStore(app).getFlow()
                .map { it.haRemoteUrlEnabled && it.enableBrowserVisible }
                .distinctUntilChanged()
                .collect { on ->
                    browserSwitchOn = on
                    sync(app)
                }
        }
        sync(app)
    }

    fun sync(context: Context? = appContext) {
        val app = context?.applicationContext ?: return
        appContext = app
        mainHandler.removeCallbacks(syncRunnable)
        mainHandler.postDelayed(syncRunnable, SYNC_DEBOUNCE_MS)
    }

    fun exitAll(context: Context) {
        exit(context, kind = null)
    }

    fun reload(context: Context, kind: Kind? = null) {
        if (kind != null && kind != Kind.BROWSER) return
        WebViewService.relaunch(context)
    }

    fun exit(context: Context, kind: Kind? = null) {
        val app = context.applicationContext
        appContext = app
        val run = Runnable {
            val targets = if (kind == null) collectActive() else listOf(kind)
            Log.i(TAG, "Exit command kinds=$targets")
            dismiss(app, targets)
            persistHidden(app, targets)
            syncNow(app)
            mainHandler.removeCallbacks(syncRunnable)
            mainHandler.postDelayed({ syncNow(app) }, RESYNC_AFTER_HIDE_MS)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run()
        } else {
            mainHandler.post(run)
        }
    }

    fun collectActive(): List<Kind> {
        val kinds = ArrayList<Kind>(Kind.entries.size)
        if (browserSwitchOn && WebViewService.isAnyBrowserOverlayActive()) kinds.add(Kind.BROWSER)
        if (ScreensaverService.isOverlayShowing()) kinds.add(Kind.SIMPLE_CLOCK)
        if (DreamClockService.isOverlayShowing()) kinds.add(Kind.DREAM_CLOCK)
        if (WeatherOverlayService.isOverlayShowing()) kinds.add(Kind.WEATHER)
        if (QuickEntityOverlayService.isOverlayShowing()) kinds.add(Kind.QUICK_ENTITY)
        if (VinylCoverService.isExpandedShowing()) kinds.add(Kind.MEDIA_PLAYER)
        if (NotificationOverlayService.isFullscreenShowing()) kinds.add(Kind.NOTIFICATION_SCENE)
        if (VoiceMessageOverlayService.isOverlayShowing()) kinds.add(Kind.VOICE_MESSAGE)
        if (VoiceMessagePlaybackOverlayService.isActivelyShowing()) kinds.add(Kind.VOICE_PLAYBACK)
        if (HaSwitchOverlayService.isOverlayShowing()) kinds.add(Kind.HA_SWITCH)
        if (SatelliteSetupTipOverlayService.isVisible()) kinds.add(Kind.SATELLITE_TIP)
        if (ScreensaverWebViewService.isOverlayShowing()) kinds.add(Kind.SCREENSAVER_WEB)
        if (AiBrowserService.isShowing()) kinds.add(Kind.AI_BROWSER)
        if (AppWindowService.hasWindowAttached()) kinds.add(Kind.APP_WINDOW)
        if (ScreenBlankOverlay.isShowing()) kinds.add(Kind.SCREEN_BLANK)
        return kinds
    }

    private fun syncNow(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { syncNow(context) }
            return
        }
        val active = collectActive()
        AvaSystemChrome.onFullscreenOverlaysChanged(active.map { it.name })
        if (EngineCapabilities.GECKO_BUNDLED) return
        if (active.isEmpty()) {
            cancelNotification(context)
            return
        }
        if (!canPostNotifications(context)) {
            Log.w(TAG, "Cannot post overlay-escape notification (permission or app notifications off)")
            return
        }
        createChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val gone = postedKinds.filterNot { it in active }
        gone.forEach { manager.cancel(it.notificationId()) }
        active.forEach { kind ->
            manager.notify(kind.notificationId(), buildNotification(context, kind))
        }
        postedKinds.clear()
        postedKinds.addAll(active)
    }

    private fun dismiss(context: Context, kinds: List<Kind>) {
        kinds.forEach { kind ->
            runCatching {
                when (kind) {
                    Kind.BROWSER -> WebViewService.hide(context)
                    Kind.SIMPLE_CLOCK -> ScreensaverService.hide(context)
                    Kind.DREAM_CLOCK -> DreamClockService.hide(context)
                    Kind.WEATHER -> WeatherOverlayService.hide(context)
                    Kind.QUICK_ENTITY -> QuickEntityOverlayService.hide(context)
                    Kind.MEDIA_PLAYER -> VinylCoverService.dismissViaChrome()
                    Kind.NOTIFICATION_SCENE -> NotificationOverlayService.hide(context)
                    Kind.VOICE_MESSAGE -> VoiceMessageOverlayService.hide(context)
                    Kind.VOICE_PLAYBACK -> VoiceMessagePlaybackOverlayService.dismissActiveSession(context)
                    Kind.HA_SWITCH -> HaSwitchOverlayService.hide(context)
                    Kind.SATELLITE_TIP -> SatelliteSetupTipOverlayService.hide(context)
                    Kind.SCREENSAVER_WEB -> ScreensaverWebViewService.hide(context)
                    Kind.AI_BROWSER -> AiBrowserService.hide(context)
                    Kind.APP_WINDOW -> AppWindowService.closeAllWindows()
                    Kind.SCREEN_BLANK -> ScreenBlankOverlay.hide()
                }
            }.onFailure { Log.w(TAG, "Failed to dismiss $kind", it) }
        }
    }

    private fun persistHidden(context: Context, kinds: List<Kind>) {
        if (kinds.isEmpty()) return
        scope.launch {
            runCatching {
                if (Kind.BROWSER in kinds) {
                    HomeSidebarActions.setBrowserVisible(BrowserSettingsStore(context), false)
                }
                val player = PlayerSettingsStore(context.playerSettingsStore)
                if (Kind.SIMPLE_CLOCK in kinds) {
                    HomeSidebarActions.setSimpleClockVisible(player, false)
                }
                if (Kind.DREAM_CLOCK in kinds) {
                    HomeSidebarActions.setDreamClockVisible(player, false)
                }
                if (Kind.WEATHER in kinds) {
                    HomeSidebarActions.setWeatherVisible(player, false)
                }
                if (Kind.VOICE_MESSAGE in kinds) {
                    HomeSidebarActions.setVoiceMessageVisible(player, false)
                }
                if (Kind.MEDIA_PLAYER in kinds) {
                    HomeSidebarActions.setVinylCoverDisplayVisible(player, false)
                }
                if (Kind.QUICK_ENTITY in kinds) {
                    HomeSidebarActions.setQuickEntityVisible(
                        context,
                        QuickEntitySettingsStore(context.quickEntitySettingsStore),
                        false,
                    )
                }
            }.onFailure { Log.w(TAG, "Failed to persist overlay hide", it) }
        }
    }

    private fun cancelNotification(context: Context) {
        if (postedKinds.isEmpty()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        postedKinds.forEach { manager?.cancel(it.notificationId()) }
        postedKinds.clear()
    }

    private fun createChannel(context: Context) {
        if (!PlatformCapabilities.requiresNotificationChannels) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val chan = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.overlay_escape_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            lightColor = Color.BLUE
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            description = context.getString(R.string.overlay_escape_notification_channel_desc)
        }
        manager.createNotificationChannel(chan)
    }

    private fun buildNotification(context: Context, kind: Kind): Notification {
        val builder = if (PlatformCapabilities.requiresNotificationChannels) {
            NotificationCompat.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            NotificationCompat.Builder(context)
        }
        val exitIntent = commandPendingIntent(context, kind, ACTION_EXIT, kind.notificationId())
        val title = context.getString(kind.titleRes)
        val exitLabel = context.getString(R.string.overlay_escape_notification_action)
        builder.setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setSmallIcon(NOTIFICATION_SMALL_ICON)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (kind == Kind.BROWSER) {
            // Body tap is just a notice. Exit / Reload match the closed sidebar.
            builder.addAction(
                android.R.drawable.ic_menu_rotate,
                context.getString(R.string.overlay_escape_notification_reload),
                commandPendingIntent(
                    context,
                    kind,
                    ACTION_RELOAD,
                    kind.notificationId() + Kind.entries.size,
                ),
            )
        } else {
            builder.setContentIntent(exitIntent)
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, exitLabel, exitIntent)
        if (PlatformCapabilities.supportsPromotedOngoingNotifications) {
            builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        }
        return builder.build()
    }

    private fun commandPendingIntent(
        context: Context,
        kind: Kind,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, FullscreenOverlayEscapeReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_KIND, kind.name)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PlatformCapabilities.pendingIntentFlags(),
        )
    }

    internal fun canPostNotifications(context: Context): Boolean {
        if (PlatformCapabilities.requiresPostNotificationsPermission) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        if (PlatformCapabilities.supportsAreNotificationsEnabled) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            if (!manager.areNotificationsEnabled()) return false
        }
        return true
    }
}
