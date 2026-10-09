package com.example.ava.services

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_SONIFICATION
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH
import androidx.media3.common.C.USAGE_MEDIA
import androidx.media3.common.C.USAGE_NOTIFICATION_RINGTONE
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.ava.R
import com.example.ava.audio.DeviceAudioProfile
import com.example.ava.receivers.AvaControlGate
import com.example.ava.audio.EchoTestTone
import com.example.ava.audio.eq.toHaMusicEqGains
import com.example.ava.audio.eq.toMusicEqGains
import com.example.ava.esphome.Connected
import com.example.ava.esphome.Disconnected
import com.example.ava.esphome.ServerError
import com.example.ava.esphome.Stopped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.example.ava.appwindow.FreeformKeepAliveActivity
import com.example.ava.homeassistant.HaManager
import com.example.ava.homeassistant.state.HaStateArbiter
import com.example.ava.homeassistant.state.HaStateSource
import com.example.ava.esphome.voicesatellite.MicPreviewClip
import com.example.ava.esphome.voicesatellite.VoiceAssistantPcmPlayer
import com.example.ava.esphome.voicesatellite.VoiceSatelliteAudioInput
import com.example.ava.esphome.voicesatellite.VoiceSatellitePlayer
import com.example.ava.microwakeword.WakeWordProviderFactory
import com.example.ava.notifications.createVoiceSatelliteServiceNotification
import com.example.ava.notifications.createVoiceSatelliteServiceNotificationChannel
import com.example.ava.nsd.NsdRegistration
import com.example.ava.nsd.registerVoiceSatelliteNsd
import com.example.ava.players.AudioPlayer
import com.example.ava.players.TtsPlayer
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.NotificationSettingsStore
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.ContinueMode
import com.example.ava.settings.SettingState
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.settings.WakeWordEngine
import com.example.ava.settings.VoiceSatelliteSettings
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.MicrophoneSettings
import com.example.ava.settings.activeStopWordForEngine
import com.example.ava.settings.activeWakeWordsForEngine
import com.example.ava.settings.wakeWordIdCandidatesForEngine
import com.example.ava.settings.compatibleWakeWordIdsForEngine
import com.example.ava.settings.isBrowserDisplayActive
import com.example.ava.settings.resolveAudioSource
import com.example.ava.settings.microphoneSettingsStore
import com.example.ava.settings.notificationSettingsStore
import com.example.ava.settings.BluetoothPresenceAlertTrigger
import com.example.ava.bluetooth.BluetoothPresenceAlertSound
import com.example.ava.bluetooth.BluetoothPresenceManager
import com.example.ava.notifications.NotificationScenes
import com.example.ava.notifications.SceneReset
import com.example.ava.settings.SettingsStyleSession
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.settings.settingsStyleSettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.settings.UpdateSettingsStore
import com.example.ava.settings.updateSettingsStore
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.update.AppUpdater
import com.example.ava.update.UpdateInstallPolicy
import com.example.ava.sendspin.SendspinFormatCatalog
import com.example.ava.settings.sendspinSettingsStore
import com.example.ava.settings.voiceChannelSettingsStore
import com.example.ava.settings.VoiceChannelSettingsStore
import com.example.ava.settings.VolumeFollowRule
import com.example.ava.utils.BatteryOptimizationHelper
import com.example.ava.utils.RootHelper
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.utils.translate
import com.example.ava.widgets.AvaActionWidgets
import com.example.ava.widgets.AvaSensorWidgets
import com.example.ava.wakelocks.BluetoothWakeLock
import com.example.ava.wakelocks.CpuScreenOffWakeLock
import com.example.ava.wakelocks.WifiWakeLock
import com.example.ava.esphome.voicesatellite.VoiceSatellite
import com.example.ava.multidevice.WakeWordArbiter

import com.example.ava.openwakeword.OpenWakeWordProvider
import com.example.ava.webcompat.BrowserEngine
import com.example.ava.webcompat.EngineCapabilities
import com.example.ava.webcompat.GeckoEngineDeathMonitor

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class VoiceSatelliteService() : LifecycleService() {
    private val startupHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val wifiWakeLock = WifiWakeLock()
    private val bluetoothWakeLock = BluetoothWakeLock()
    private val cpuScreenOffWakeLock = CpuScreenOffWakeLock()
    private var screenReceiver: android.content.BroadcastReceiver? = null
    private var restartVoiceSatelliteJob: Job? = null
    private var haRediscoverJob: Job? = null
    private var settingsWatcherJob: Job? = null
    private var echoTestTonePlayer: VoiceAssistantPcmPlayer? = null
    private var echoTestToneJob: Job? = null
    /**
     * Set by [restartVoiceSatellite] so the following start skips show/hide of scene overlays.
     * Otherwise Overlay init would hide-or-re-show windows that soft-stop intentionally left alone.
     */
    @Volatile private var preservePassiveOverlaysOnNextStart = false
    /** Last set handed to `startForeground`, so [refreshForegroundServiceTypes] can diff it. */
    @Volatile private var appliedForegroundServiceTypes = 0
    private val satelliteSettingsStore: VoiceSatelliteSettingsStore by lazy {
        VoiceSatelliteSettingsStore(applicationContext.voiceSatelliteSettingsStore)
    }
    private val microphoneSettingsStore: MicrophoneSettingsStore by lazy {
        MicrophoneSettingsStore(applicationContext.microphoneSettingsStore)
    }
    private val playerSettingsStore: PlayerSettingsStore by lazy {
        PlayerSettingsStore(applicationContext.playerSettingsStore)
    }
    private val notificationSettingsStore: NotificationSettingsStore by lazy {
        NotificationSettingsStore(applicationContext.notificationSettingsStore)
    }
    private val experimentalSettingsStore: com.example.ava.settings.ExperimentalSettingsStore by lazy {
        com.example.ava.settings.ExperimentalSettingsStore(applicationContext)
    }
    private val browserSettingsStore: com.example.ava.settings.BrowserSettingsStore by lazy {
        com.example.ava.settings.BrowserSettingsStore(applicationContext)
    }
    private val sidebarSettingsStore: SidebarSettingsStore by lazy {
        SidebarSettingsStore(applicationContext.sidebarSettingsStore)
    }
    private val sendspinSettingsStore: com.example.ava.settings.SendspinSettingsStore by lazy {
        com.example.ava.settings.SendspinSettingsStore(applicationContext.sendspinSettingsStore)
    }
    private val voiceChannelSettingsStore: VoiceChannelSettingsStore by lazy {
        VoiceChannelSettingsStore(applicationContext.voiceChannelSettingsStore)
    }
    internal var sendspinManager: com.example.ava.sendspin.SendspinManager? = null
    private var suppressSendspinVolumeUpdate = false
    private val suppressSendspinPlaybackConflict = java.util.concurrent.atomic.AtomicBoolean(false)
    private var hideVinylJob: kotlinx.coroutines.Job? = null
    private var haProgressOverlayJob: Job? = null
    /** First HA media_player snapshot after (re)connect — never birth overlay from it. */
    private var haMediaPlaybackStateInitialized = false
    private var cachedHaPlaybackState = false
    private var haProgressJob: Job? = null
    private var voiceSatelliteNsd = AtomicReference<NsdRegistration?>(null)
    internal val _voiceSatellite = MutableStateFlow<VoiceSatellite?>(null)
    private val initializing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val geckoEngineDeathMonitor = GeckoEngineDeathMonitor()
    private var webViewServiceDeathConnection: ServiceConnection? = null
    /** Held only to keep the liveness Binder handed to the gecko pack from being GC'd. */
    private var hostLivenessToken: android.os.IBinder? = null
    /** Ignore browser sub-service death callbacks during intentional destroy/restart. */
    @Volatile private var suppressBrowserDeathReaction = false
    @Volatile private var lastDeathReactionMs = 0L
    @Volatile private var consecutiveBrowserDeaths = 0
    
    
    private var cachedVinylCoverEnabled = false
    private var cachedHaVinylCoverEnabled = true
    private var cachedEqMiniPlayer = false
    private var deviceMusicVolumeMonitor: com.example.ava.utils.DeviceMusicVolumeMonitor? = null
    private var volumeSyncStateJob: Job? = null
    private var nsdReannounceJob: Job? = null
    @Volatile private var voiceReplyStreamOverlayActive = false
    private var savedStreamMusicLevel: Float? = null
    private var queuedStreamMusicLevel: Float? = null
    private var overlayStreamMusicLevel: Float? = null
    private var overlayHeardLevel: Float? = null
    private var voiceReplyRestoreFadeJob: Job? = null
    /**
     * Pending (caption lead-in) speaking glow from onTtsPlaybackStarted. Listening,
     * conversation end and TTS audio end cancel it; the token also stops a job that
     * already passed its delay, so a late SPEAKING cannot repaint after the audio.
     */
    @Volatile
    private var speakingGlowJob: Job? = null
    private val speakingGlowToken = java.util.concurrent.atomic.AtomicInteger(0)

    private fun dropSpeakingGlow() {
        speakingGlowToken.incrementAndGet()
        speakingGlowJob?.cancel()
        speakingGlowJob = null
    }
    private var voiceOverlayDuckFadeJob: Job? = null
    /** True while [voiceOverlayDuckFadeJob] is lifting toward full volume. */
    private var voiceOverlayDuckFadingOut = false
    private suspend fun updateVinylCoverCache() {
        val haMediaEntity = satelliteSettingsStore.get().haMediaPlayerEntity
        val settings = playerSettingsStore.get()
        cachedHaVinylCoverEnabled = settings.enableHaVinylCover
        cachedVinylCoverEnabled = haMediaEntity.isNotEmpty() && settings.enableHaVinylCover
        cachedEqMiniPlayer = settings.enableEqMiniPlayer
        VinylCoverService.setEqMiniPreferred(settings.enableEqMiniPlayer)
    }

    /**
     * Push HA media into [VinylCoverService] only when HA Media Controls are on.
     * Mini FAB is presentation only — it must not keep the overlay alive when
     * both media-control switches are off (power saving).
     */
    private fun shouldPushHaMediaOverlay(): Boolean {
        if (!cachedVinylCoverEnabled || !cachedHaVinylCoverEnabled) return false
        if (sendspinManager?.isActive?.value == true) return false
        // Sendspin SHOW latter-wins: stop HA progress/lyrics even if isActive lags.
        if (VinylCoverService.isSendspinProgressOwner()) return false
        // Audible ground truth: PCM that is (or was seconds ago) leaving the
        // speaker outranks any memory-resident HA entity state. Short protocol
        // gaps must not let a stale HA "playing" claim the overlay via SHOW.
        if (sendspinManager?.wasRecentlyAudible() == true) return false
        // Mass API: same HA yield as SP (voice overlay / bound queue clock).
        if (com.example.ava.massapi.MassApiManager.get()?.isBlockingHaMediaOverlay() == true) {
            return false
        }
        return true
    }

    /**
     * Same Sendspin + Mass gates that suppress HA SHOW — used for
     * queue-transport bridge only.
     */
    private fun isSendspinBlockingHaMediaOverlay(): Boolean {
        if (sendspinManager?.isActive?.value == true) return true
        if (VinylCoverService.isSendspinProgressOwner()) return true
        if (sendspinManager?.wasRecentlyAudible() == true) return true
        if (com.example.ava.massapi.MassApiManager.get()?.isBlockingHaMediaOverlay() == true) {
            return true
        }
        return false
    }

    /** HA playing mirrors this SP/Mass session — do not stop local playback. */
    private fun isHaPlayingMirrorOfProtocol(haTitle: String?): Boolean {
        if (sendspinManager?.isLikelyMirrorOfCurrentTrack(haTitle) == true) return true
        if (com.example.ava.massapi.MassApiManager.get()
                ?.isLikelyMirrorOfCurrentTrack(haTitle) == true
        ) {
            return true
        }
        return false
    }

    /** HA attrs belong to the mirrored SP/Mass queue — UI bridge only. */
    private fun isHaMirrorEntityForTransport(haTitle: String?): Boolean {
        if (sendspinManager?.isLikelyHaMirrorEntity(haTitle) == true) return true
        if (com.example.ava.massapi.MassApiManager.get()
                ?.isLikelyHaMirrorEntity(haTitle) == true
        ) {
            return true
        }
        return false
    }

    /**
     * Legacy users had a single [PlayerSettings.enableVinylCover] master that also gated Sendspin.
     * Preserve "master off" as Sendspin disabled until the user opts in on the split MA card.
     */
    private suspend fun migratePlaybackUiIfNeeded() {
        val player = playerSettingsStore.get()
        if (player.playbackUiMigrated) return
        if (!player.enableVinylCover) {
            sendspinSettingsStore.enabled.set(false)
        }
        playerSettingsStore.update { it.copy(playbackUiMigrated = true) }
        Log.i(
            TAG,
            "Playback UI migration done (legacy enableVinylCover=${player.enableVinylCover}, sendspinEnabled=${sendspinSettingsStore.get().enabled})",
        )
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private var overlayPermissionLost = false

    /**
     * Runtime gate: true when overlays may draw. When the grant is missing this
     * hides any live overlay surfaces (once per loss) but deliberately does NOT
     * persist the enable* preferences to false — "Display over other apps" can
     * disappear temporarily (OS update, OEM permission reset), and writing the
     * toggles off permanently disabled features the user had chosen, with no
     * path back when the grant returned. The settings UI already renders these
     * switches as off while the permission is absent.
     */
    private fun syncOverlayPermissionState(): Boolean {
        if (hasOverlayPermission()) {
            overlayPermissionLost = false
            return true
        }
        if (!overlayPermissionLost) {
            overlayPermissionLost = true
            Log.w(TAG, "Overlay permission missing; hiding overlay surfaces (user settings preserved)")
            FloatingWindowService.hide(this)
            HaSwitchOverlayService.hide(this)
            VoiceMessageOverlayService.hide(this)
            VoiceMessagePlaybackOverlayService.stop(this)
        }
        return false
    }
    
    
    private var lastPlayedUrl: String? = null
    
    
    private fun cancelHideJob() {
        hideVinylJob?.cancel()
        hideVinylJob = null
    }

    private fun pushHaOverlayProgress(player: com.example.ava.esphome.voicesatellite.VoiceSatellitePlayer) {
        if (!VinylCoverService.ownsOverlayProgress(fromSendspin = false)) return
        VinylCoverService.updateProgress(
            this,
            currentTimeMs = player.interpolatedHaPositionMs().coerceAtLeast(0L),
            totalTimeMs = player.haMediaDurationMs.takeIf { it > 0L },
            isSendspinSource = false,
        )
    }

    private fun reconcileHaProgressOverlayTicker(
        player: com.example.ava.esphome.voicesatellite.VoiceSatellitePlayer,
        playing: Boolean,
    ) {
        haProgressOverlayJob?.cancel()
        haProgressOverlayJob = null
        if (!playing || !shouldPushHaMediaOverlay()) return
        haProgressOverlayJob = lifecycleScope.launch {
            while (isActive) {
                // Re-check each tick: Sendspin SHOW may have claimed ownership mid-loop.
                if (!shouldPushHaMediaOverlay()) break
                pushHaOverlayProgress(player)
                kotlinx.coroutines.delay(200)
            }
        }
    }

    private fun isVoiceInteractionActive(): Boolean {
        val sat = _voiceSatellite.value ?: return false
        if (sat.remoteAiHold.value) return true
        if (sat.isConversationEngineHold()) return true
        return when (sat.state.value) {
            is com.example.ava.esphome.voicesatellite.Listening,
            is com.example.ava.esphome.voicesatellite.Processing,
            is com.example.ava.esphome.voicesatellite.Responding -> true
            else -> false
        }
    }

    /**
     * Pause during voice skipped arming pause-idle; when the pipeline returns to
     * [Connected], retry once if HA is still paused and Sendspin does not own the window.
     */
    private fun maybeRearmHaPauseIdleAfterVoice() {
        if (isVoiceInteractionActive()) return
        if (VinylCoverService.isSendspinContentActive) return
        val player = _voiceSatellite.value?.player ?: return
        if (player.haPlaybackState.value) return
        VinylCoverService.schedulePauseIdleTeardown(this, restart = false)
    }

    private var voiceMessageMicHoldCount = 0
    private var isStreamingBeforeVoiceMessage: Boolean? = null
    /** Voice-call dodge: assistant seat / TTS may still be live, capture line is not. */
    @Volatile private var assistantMicYielded = false

    fun isVoiceAssistantPipelineActive(): Boolean {
        if (assistantMicYielded) return false
        return isVoiceInteractionActive()
    }

    fun isVoiceMessageMicHeld(): Boolean = voiceMessageMicHoldCount > 0

    /**
     * Branch: we started a LAN call / message. Drop assistant capture so
     * [acquireMicrophoneForVoiceMessage] can take the mic. Remote AI and TTS stay.
     */
    suspend fun yieldMicrophoneForVoiceCall() {
        assistantMicYielded = true
        _voiceSatellite.value?.yieldCaptureForVoiceCall()
    }

    @Synchronized
    fun acquireMicrophoneForVoiceMessage(): Boolean {
        if (isVoiceAssistantPipelineActive()) return false
        val audioInput = _voiceSatellite.value?.audioInput ?: return false
        if (voiceMessageMicHoldCount == 0) {
            isStreamingBeforeVoiceMessage = audioInput.isStreaming
            audioInput.isStreaming = false
            audioInput.setTemporaryPaused(true)
        }
        voiceMessageMicHoldCount++
        return true
    }

    @Synchronized
    fun releaseMicrophoneForVoiceMessage() {
        if (voiceMessageMicHoldCount <= 0) return
        voiceMessageMicHoldCount--
        if (voiceMessageMicHoldCount > 0) return
        restoreSatelliteMicrophoneAfterVoiceMessage()
    }

    /**
     * Voice-message overlay/call was closed while the wake-word mic was still paused.
     * Resets hold state and restarts the satellite microphone pipeline.
     */
    @Synchronized
    fun forceReleaseVoiceMessageMicrophone() {
        assistantMicYielded = false
        if (voiceMessageMicHoldCount <= 0) {
            _voiceSatellite.value?.audioInput?.setTemporaryPaused(false)
            return
        }
        voiceMessageMicHoldCount = 0
        restoreSatelliteMicrophoneAfterVoiceMessage()
        android.util.Log.d(
            "VoiceSatelliteService",
            "force-released voice-message mic hold; wake-word pipeline restored"
        )
    }

    private fun restoreSatelliteMicrophoneAfterVoiceMessage() {
        assistantMicYielded = false
        val audioInput = _voiceSatellite.value?.audioInput ?: return
        val restoreStreaming = isStreamingBeforeVoiceMessage
        isStreamingBeforeVoiceMessage = null
        audioInput.setTemporaryPaused(false)
        audioInput.resetWakeWordDetector()
        if (restoreStreaming == true && !isVoiceInteractionActive()) {
            audioInput.isStreaming = true
        }
    }

    private suspend fun syncVoiceMessageServices(settings: com.example.ava.settings.PlayerSettings? = null) {
        val playerSettings = settings ?: playerSettingsStore.get()
        val masterEnabled = playerSettings.enableVoiceMessageOverlay

        // 1. Stop active capture/playback before closing UDP sockets (avoids mic/AudioFlinger races).
        if (!masterEnabled) {
            com.example.ava.voice.AvaVoiceMessenger.abortAllSessionsAndReleaseMic()
            com.example.ava.voice.AvaVoiceCallAudioSession.forceLeave(applicationContext)
            forceReleaseVoiceMessageMicrophone()
        }

        com.example.ava.voice.AvaVoiceSessionHub.setMessageBoardMode(
            masterEnabled &&
                playerSettings.voiceMessageReceiveMode == "board" &&
                playerSettings.enableVoiceMessageReceive
        )

        // 2. Start/stop discovery + audio listener — gated solely by master switch + receive sub-flag.
        com.example.ava.voice.AvaVoiceNetwork.sync(
            context = this,
            featureEnabledFlag = masterEnabled,
            receiveEnabledFlag = playerSettings.enableVoiceMessageReceive,
            displayName = playerSettings.voiceMessageDisplayName,
            callAnswerRequiredFlag = playerSettings.enableVoiceCallAnswerRequired
        )

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            // Inbound calls can arrive whenever the master switch is on — including
            // auto-answer mode (answerRequired=false) with receive off, where the hub
            // starts live audio without any ring step. The playback overlay is the only
            // surface that renders inbound call UI, so it must track the master switch
            // alone; the old sub-flag gate left auto-answered calls with audio but no UI.
            if (masterEnabled) {
                VoiceMessagePlaybackOverlayService.ensureStarted(this@VoiceSatelliteService)
            } else {
                VoiceMessagePlaybackOverlayService.stop(this@VoiceSatelliteService)
            }
        }
    }

    private fun suppressSendspinPlaybackConflictTemporarily() {
        suppressSendspinPlaybackConflict.set(true)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1200)
            suppressSendspinPlaybackConflict.set(false)
        }
    }
    

    val voiceSatelliteState = _voiceSatellite.flatMapLatest { sat ->
        if (sat == null) {
            flowOf(Stopped)
        } else {
            // Remote-AI seat: channel may already be Connected while the cloud
            // model is still working. UI follows this hold, not the HA pipeline.
            combine(sat.state, sat.remoteAiHold) { channel, hold ->
                if (hold && (
                    channel is Connected ||
                        channel is com.example.ava.esphome.voicesatellite.Processing ||
                        channel is com.example.ava.esphome.voicesatellite.Responding
                    )
                ) {
                    com.example.ava.esphome.voicesatellite.Processing
                } else {
                    channel
                }
            }
        }
    }

    fun startVoiceSatellite() {
        
        getSharedPreferences("ava_prefs", MODE_PRIVATE).edit()
            .putBoolean("service_user_stopped", false)
            .apply()
        
        
        val packageName = packageName
        val serviceName = "com.example.ava.services.VoiceSatelliteService"
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val autoRestartEnabled = playerSettingsStore.enableAutoRestart.get()
            if (autoRestartEnabled) {
                RootHelper.installBootScript(packageName, serviceName)
            } else {
                RootHelper.removeBootScript()
            }
        }

        // Already up: retry is only to poke HA. Do not re-enter start and restack FAB.
        if (_voiceSatellite.value != null && !initializing.get()) {
            Log.d(TAG, "Voice satellite already running — notify HA only")
            notifyUpstreamWhileAlreadyRunning()
            FreeformKeepAliveActivity.ensureMicVisible(this)
            return
        }
        
        val serviceIntent = Intent(this, this::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(serviceIntent)
        } else {
            applicationContext.startService(serviceIntent)
        }
    }

    private fun notifyUpstreamWhileAlreadyRunning() {
        val sat = _voiceSatellite.value ?: return
        lifecycleScope.launch {
            sat.publishVoiceAssistantConfiguration()
            scheduleHaRediscovery(SatelliteRestartReason.SETTINGS)
        }
    }

    private fun applyScreenToggle(screenOn: Boolean) {
        ScreenControlUtils.setScreenOn(this, screenOn)
    }

    fun stopVoiceSatellite() {
        stopVoiceSatellite(markUserStopped = true)
    }

    private fun stopVoiceSatellite(
        markUserStopped: Boolean,
        destroyBrowser: Boolean = false,
        tearSendspin: Boolean = true,
    ) {
        if (markUserStopped) {
            getSharedPreferences("ava_prefs", MODE_PRIVATE).edit()
                .putBoolean("service_user_stopped", true)
                .apply()
        }

        if (destroyBrowser) {
            suppressBrowserDeathReaction = true
            stopBrowserSubServiceDeathMonitor()
            GeckoEngineDeathMonitor.suppressBriefly()
            WebViewService.destroy(this)
            startupHandler.postDelayed({ suppressBrowserDeathReaction = false }, 5_000L)
        }

        // Drop settings collectors before clearing satellite so stale watchers cannot
        // re-show overlays while stop/restart is in flight.
        settingsWatcherJob?.cancel()
        settingsWatcherJob = null
        haRediscoverJob?.cancel()
        haRediscoverJob = null

        hideVoiceSessionOverlays()
        WakeWordArbiter.stop()
        // Home power-off only: tear down scene/voice-message windows.
        // Internal restartVoiceSatellite must keep them on screen undisturbed.
        if (markUserStopped) {
            preservePassiveOverlaysOnNextStart = false
            hidePassiveDashboardOverlays()
            // Soft-stop does not destroy this Service — tear down idle WebView screensaver
            // so it cannot reappear while isSatelliteStarted() is false.
            ScreensaverController.onHostServiceStopped()
            teardownVoiceMessageRuntime()
            // A press with no satellite behind it would do nothing — take the button away.
            // Internal restarts keep it so the disc does not blink on every settings change.
            QuickWakeFabService.hide(this)
        }

        val satellite = _voiceSatellite.getAndUpdate { null }
        publishSatelliteStarted(false)
        FreeformKeepAliveActivity.releaseMicVisible()
        if (satellite != null) {
            satellite.cancelHaServiceCallsProbe()
            satellite.close()
            // Always unregister mDNS when the satellite stops so HA sees a clean teardown.
            // Re-register happens immediately on the next start/restart path.
            unregisterVoiceSatelliteNsd()
            if (markUserStopped) {
                wifiWakeLock.release()
            }
            // Soft restart: keep vinyl shell and rebind when Sendspin comes back.
            // User power-off: tear Sendspin + allow normal overlay teardown paths.
            // Name/port, wake-engine, CameraX: satellite must rebuild, protocol must not.
            if (tearSendspin) {
                closeSendspin(preserveOverlayForRebind = !markUserStopped)
            }
            stopForegroundCompat()
        }
        
        initializing.set(false)
    }

    private fun hideVoiceSessionOverlays() {
        WakeRippleService.hide(this)
        FloatingWindowService.hide(this)
        ChorusWakeBlurService.hideImmediate(this)
    }

    private fun syncChorusWakeArbiter(enabled: Boolean) {
        if (enabled) {
            WakeWordArbiter.onPeerSessionEnd = {
                ChorusWakeBlurService.fadeOut(this@VoiceSatelliteService)
            }
            WakeWordArbiter.start(this)
        } else {
            WakeWordArbiter.onPeerSessionEnd = null
            WakeWordArbiter.stop()
            ChorusWakeBlurService.hideImmediate(this)
        }
    }

    /** Fullscreen scene overlays that outlive soft stop unless explicitly torn down. */
    private fun hidePassiveDashboardOverlays() {
        DreamClockService.hide(this)
        WeatherOverlayService.hide(this)
        ScreensaverService.hide(this)
        QuickEntityOverlayService.hide(this)
    }

    /** Voice-message send button + incoming call/playback layers. */
    private fun hideVoiceMessageOverlays() {
        VoiceMessageOverlayService.hide(this)
        VoiceMessagePlaybackOverlayService.stop(this)
    }

    /**
     * Abort in-flight LAN voice sessions and drop discovery/audio while the satellite is soft-stopped.
     * Prefer prefs-driven [syncVoiceMessageServices] on the next start.
     */
    private fun teardownVoiceMessageRuntime() {
        runCatching {
            com.example.ava.voice.AvaVoiceMessenger.abortAllSessionsAndReleaseMic()
            com.example.ava.voice.AvaVoiceCallAudioSession.forceLeave(applicationContext)
            forceReleaseVoiceMessageMicrophone()
            com.example.ava.voice.AvaVoiceNetwork.sync(
                context = this,
                featureEnabledFlag = false,
                receiveEnabledFlag = false,
                displayName = "",
                callAnswerRequiredFlag = false,
            )
        }.onFailure { e ->
            Log.w(TAG, "Voice message teardown during soft stop failed", e)
        }
        hideVoiceMessageOverlays()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
    
    
    /**
     * Settings / mods / watchers: keep satellite + Sendspin up, rebuild HA
     * entities, kick the native-API client so HA ListEntities.
     *
     * Only [SatelliteRestartReason.HA_RESTART_ENTITY] may tear the music
     * protocol. [SatelliteRestartReason.SATELLITE_PIPELINE] rebuilds the
     * satellite (name/port, wake library, CameraX), tears the browser so
     * steward boot scripts reinstall, and leaves Sendspin up.
     * The home power button uses [startVoiceSatellite] / [stopVoiceSatellite].
     */
    fun restartVoiceSatellite(
        reason: SatelliteRestartReason = SatelliteRestartReason.SETTINGS,
    ) {
        if (_voiceSatellite.value == null) return
        when (reason) {
            SatelliteRestartReason.HA_RESTART_ENTITY -> {
                haRediscoverJob?.cancel()
                haRediscoverJob = null
                restartVoiceSatelliteFully(tearSendspin = true, destroyBrowser = true)
            }
            SatelliteRestartReason.SATELLITE_PIPELINE -> {
                haRediscoverJob?.cancel()
                haRediscoverJob = null
                // Steward / engine / UA boot scripts are not all live-applied.
                // Rebuild the WebView with the satellite so page policy matches.
                restartVoiceSatelliteFully(tearSendspin = false, destroyBrowser = true)
            }
            SatelliteRestartReason.SETTINGS -> {
                if (restartVoiceSatelliteJob?.isActive == true) {
                    Log.d(TAG, "Skip HA rediscover; satellite rebuild already in flight")
                    return
                }
                scheduleHaRediscovery(reason)
            }
        }
    }

    private fun restartVoiceSatelliteFully(
        tearSendspin: Boolean,
        destroyBrowser: Boolean,
    ) {
        restartVoiceSatelliteJob?.cancel()
        restartVoiceSatelliteJob = lifecycleScope.launch {
            stopVoiceSatellite(
                markUserStopped = false,
                destroyBrowser = destroyBrowser,
                tearSendspin = tearSendspin,
            )

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                satelliteSettingsStore.ensureMacAddressIsSet(applicationContext)
            }
            wifiWakeLock.create(applicationContext, TAG)
            wifiWakeLock.acquire()

            kotlinx.coroutines.delay(500)
            // Arm only for the immediate start that follows — cancelled restarts must not
            // skip overlay reconcile on a later cold start.
            preservePassiveOverlaysOnNextStart = true
            startVoiceSatellite()
        }
    }

    private fun scheduleHaRediscovery(reason: SatelliteRestartReason) {
        haRediscoverJob?.cancel()
        haRediscoverJob = lifecycleScope.launch {
            delay(HA_REDISCOVER_COALESCE_MS)
            val satellite = _voiceSatellite.value ?: return@launch
            if (restartVoiceSatelliteJob?.isActive == true) {
                Log.d(TAG, "Skip HA rediscover after coalesce; full restart in flight")
                return@launch
            }
            Log.i(TAG, "HA rediscover without protocol tear reason=$reason")
            val playerSettings = playerSettingsStore.get()
            val experimentalSettingsData = experimentalSettingsStore.get()
            val browserSettingsData = browserSettingsStore.get()
            val screensaverSettingsData = com.example.ava.settings.ScreensaverSettingsStore(
                applicationContext.screensaverSettingsStore,
            ).get()
            val voiceChannelEnabled = voiceChannelSettingsStore.get().enabled
            satellite.rediscoverHaEntities(
                voiceChannelEnabled = voiceChannelEnabled,
                experimentalSettingsData = experimentalSettingsData,
                browserSettingsData = browserSettingsData,
                screensaverSettingsData = screensaverSettingsData,
                playerSettingsData = playerSettings,
            )
        }
    }

    fun applyVoiceChannelChange(enabled: Boolean) {
        val satellite = _voiceSatellite.value ?: return

        lifecycleScope.launch {
            if (!enabled) {
                satellite.stopVoiceSession()
            }
            // voiceChannelEnabled is a constructor-time value: the mic pipeline,
            // audio input and voice entities all key off it, so an HA rediscover
            // alone would show entities backed by a pipeline that never started.
            restartVoiceSatellite(SatelliteRestartReason.SATELLITE_PIPELINE)
        }
    }

    /**
     * Wake mode changed in settings. The store is already written; re-derive the detector
     * gate through the single owner and show / hide the Quick Wake button.
     */
    fun applyWakeModeChange(mode: com.example.ava.settings.WakeMode) {
        val satellite = _voiceSatellite.value ?: return
        lifecycleScope.launch {
            // Pass the just-saved mode — getCached() can still hold the previous value
            // until the DataStore collector lands, which would leave the wake engine on.
            syncWakeDetectionSuspension(satellite, mode)
            syncQuickWakeFab(mode)
            if (mode == com.example.ava.settings.WakeMode.BUTTON) {
                WakeRippleService.hide(this@VoiceSatelliteService)
            }
        }
    }

    private fun applyInitialWakeMode(satellite: com.example.ava.esphome.voicesatellite.VoiceSatellite) {
        lifecycleScope.launch {
            val mode = microphoneSettingsStore.get().wakeMode
            syncWakeDetectionSuspension(satellite, mode)
            syncQuickWakeFab(mode)
        }
    }

    /** Button appears only when a mode asks for it *and* the voice channel can act on a press. */
    private suspend fun syncQuickWakeFab(mode: com.example.ava.settings.WakeMode) {
        val wantsButton = mode != com.example.ava.settings.WakeMode.VOICE
        val voiceOn = voiceChannelSettingsStore.get().enabled
        if (wantsButton && voiceOn) {
            QuickWakeFabService.show(this)
        } else {
            QuickWakeFabService.hide(this)
        }
    }

    fun reloadWakeWordLibrary() {
        lifecycleScope.launch {
            com.example.ava.wakewordlibrary.WakeWordLibraryManager.getInstance(applicationContext).refresh()
            restartVoiceSatellite(SatelliteRestartReason.SATELLITE_PIPELINE)
        }
    }

    /** Wake self-learning settings page: forget every learned sample and head. */
    fun resetWakeLearning() {
        val satellite = _voiceSatellite.value
        if (satellite != null) {
            satellite.resetWakeLearning()
        } else {
            com.example.ava.wakelearn.WakeLearnStore.forContext(applicationContext).clearAll()
            com.example.ava.wakelearn.WakeLearnTuning.clearReports(applicationContext)
        }
    }

    /** Wake self-learning settings page: a tuning knob changed; re-bake the running heads. */
    fun reloadWakeVerifiers() {
        _voiceSatellite.value?.reloadWakeVerifiers()
    }

    /** Wake self-learning settings page: refit now. Null when no satellite is running. */
    suspend fun retrainWakeVerifier(
        engine: com.example.ava.settings.WakeWordEngine,
        wakeWordId: String,
    ): Boolean? = _voiceSatellite.value?.wakeLearner?.retrainNow(engine, wakeWordId)

    fun applyWakeWordsChange(activeWakeWords: List<String>) {
        val satellite = _voiceSatellite.value ?: return

        lifecycleScope.launch {
            val engine = satellite.audioInput.wakeWordEngine
            // Fresh disk scan — audioInput.availableWakeWords is a snapshot from
            // satellite start and would silently drop a model downloaded after that
            // (empty sanitized list = selection ignored, no restart).
            val available = when (engine) {
                WakeWordEngine.OPEN_WAKE_WORD ->
                    WakeWordProviderFactory.openWakeWordProvider(applicationContext)
                        .listModels().map { it.id }
                WakeWordEngine.MICRO_WAKE_WORD ->
                    WakeWordProviderFactory.microWakeWordProvider(applicationContext)
                        .getWakeWords().map { it.id }
            }.toSet()
            val sanitized = compatibleWakeWordIdsForEngine(engine, activeWakeWords, available)
            if (sanitized.isEmpty()) return@launch
            // Model swap tears the mic (flatMapLatest) then restarts — abort like stop-word
            // so stopRequested blocks late TTS before/during the disruption.
            satellite.abortVoiceSessionIfActive()
            satellite.audioInput.setActiveWakeWords(sanitized)
            microphoneSettingsStore.saveActiveWakeWordsForEngine(engine, sanitized)
            satellite.publishVoiceAssistantConfiguration()
            restartVoiceSatellite(SatelliteRestartReason.SATELLITE_PIPELINE)
        }
    }

    fun clearVoicePrintProfiles() {
        _voiceSatellite.value?.clearVoicePrintProfiles()
    }

    fun clearVoicePrintUserProfile(userIndex: Int) {
        _voiceSatellite.value?.clearVoicePrintUserProfile(userIndex)
    }

    fun currentMicrophoneLevel(): Float =
        _voiceSatellite.value?.audioInput?.currentMicrophoneLevel() ?: 0f

    /** Mic uplink to HA is open (wake / continue chime done, the person is being heard). */
    fun isMicUplinkOpen(): Boolean =
        _voiceSatellite.value?.audioInput?.isStreaming ?: false

    /**
     * Wake earcon is playing and the mic is being pre-rolled for HA: the person is
     * already being heard ("okay nabu, turn on the light" in one breath).
     */
    fun isWakePreRollCapturing(): Boolean =
        _voiceSatellite.value?.audioInput?.wakePreRollCapturing ?: false

    /** Peak pre-AEC float-RMS during the current guided voiceprint listen window. */
    fun currentVoicePrintEnrollmentPeakLevel(): Float =
        _voiceSatellite.value?.audioInput?.currentVoicePrintEnrollmentPeakLevel() ?: 0f

    /** Settings preview cards: buffer processed mic PCM for local playback. */
    fun startMicPreviewCapture(includeRaw: Boolean = false): Boolean =
        _voiceSatellite.value?.audioInput?.startMicPreviewCapture(includeRaw) ?: false

    fun stopMicPreviewCapture(): MicPreviewClip? =
        _voiceSatellite.value?.audioInput?.stopMicPreviewCapture()

    /**
     * Echo cancellation preview: play the test hum through the PCM path that feeds
     * [com.example.ava.audio.PlaybackReferenceBus], otherwise software AEC has no far-end
     * reference and the hum would stay in the recording even when cancellation works.
     */
    fun startEchoTestTone(): Boolean {
        stopEchoTestTone()
        val player = VoiceAssistantPcmPlayer().apply { volume = ECHO_TEST_TONE_VOLUME }
        if (!player.start()) return false
        echoTestTonePlayer = player
        echoTestToneJob = lifecycleScope.launch(Dispatchers.Default) {
            val chunk = EchoTestTone.buildChunkPcm16Mono()
            // Prime one extra chunk so the AudioTrack never underruns mid-test.
            player.write(chunk)
            while (isActive) {
                player.write(chunk)
                kotlinx.coroutines.delay(EchoTestTone.CHUNK_MS.toLong())
            }
        }
        return true
    }

    fun stopEchoTestTone() {
        echoTestToneJob?.cancel()
        echoTestToneJob = null
        echoTestTonePlayer?.stop()
        echoTestTonePlayer = null
    }

    fun ambientMicrophoneLevel(): Float =
        _voiceSatellite.value?.audioInput?.ambientMicLevel() ?: 0f

    fun previewAmbientMicrophoneLevel(): Float =
        _voiceSatellite.value?.audioInput?.previewAmbientMicLevel() ?: 0f

    fun sessionMicrophonePeak(): Float =
        _voiceSatellite.value?.audioInput?.sessionMicPeakPreGainRms() ?: 0f

    fun isMicrophoneStreaming(): Boolean =
        _voiceSatellite.value?.audioInput?.isStreaming == true

    fun isWakeDetectionSuspended(): Boolean =
        wakeDetectionSuspendDepth.get() > 0 ||
            _voiceSatellite.value?.audioInput?.isWakeDetectionSuspended() == true

    /** Read-only PCM TTS probe for diagnostics; does not start the player. */
    fun isPcmTtsActive(): Boolean =
        _voiceSatellite.value?.isPcmTtsActive() == true

    /** Read-only URL/Exo TTS probe; does not start playback. */
    fun isUrlTtsPlaying(): Boolean =
        _voiceSatellite.value?.isUrlTtsPlaying() == true

    /** Read-only whisper playback session probe. */
    fun isWhisperPlaybackActive(): Boolean =
        _voiceSatellite.value?.isWhisperPlaybackActive() == true

    fun isMuted(): Boolean =
        _voiceSatellite.value?.isMuted() == true

    fun microphoneCaptureSnapshot(): com.example.ava.esphome.voicesatellite.MicrophoneCaptureSnapshot? =
        _voiceSatellite.value?.microphoneCaptureSnapshot()

    fun activeAudioSource(): Int? =
        _voiceSatellite.value?.activeAudioSource()

    fun preferredDeviceId(): Int? =
        _voiceSatellite.value?.preferredDeviceId()

    fun microphoneLastError(): String? =
        _voiceSatellite.value?.microphoneLastError()

    fun isSoftwareAecActive(): Boolean =
        _voiceSatellite.value?.isSoftwareAecActive() == true

    fun isSoftwareAecPausedForSpeech(): Boolean =
        _voiceSatellite.value?.isSoftwareAecPausedForSpeech() == true

    fun voicePrintStatusText(): String? =
        _voiceSatellite.value?.voicePrintStatusText()

    fun isManualWakeVerifyRequired(): Boolean =
        _voiceSatellite.value?.isManualWakeVerifyRequired() == true

    fun voicePrintRingDebugSnapshot(): String? =
        _voiceSatellite.value?.voicePrintRingDebugSnapshot()

    fun runtimeActiveWakeWords(): List<String> =
        _voiceSatellite.value?.runtimeActiveWakeWords().orEmpty()

    fun availableWakeWordCount(): Int =
        _voiceSatellite.value?.availableWakeWordCount() ?: 0

    fun lastWakeWordId(ttlMs: Long = 8_000L): String? =
        _voiceSatellite.value?.lastWakeWordId(ttlMs)

    fun lastWakeAtMs(): Long =
        _voiceSatellite.value?.lastWakeAtMs() ?: 0L

    fun lastWakePhrase(ttlMs: Long = 8_000L): String? =
        _voiceSatellite.value?.lastWakePhrase(ttlMs)

    fun lastWakeConfidence(ttlMs: Long = 8_000L): Float? =
        _voiceSatellite.value?.lastWakeConfidence(ttlMs)

    fun playbackEnergyLevel(): Float =
        _voiceSatellite.value?.playbackEnergyLevel() ?: 0f

    fun pcmTtsVolume(): Float? =
        _voiceSatellite.value?.pcmTtsVolume()

    fun lastTtsUrlHost(): String? =
        _voiceSatellite.value?.lastTtsUrlHost()

    fun sessionAccentColorHex(): String? =
        _voiceSatellite.value?.sessionAccentColorHex()

    fun lastAudioEventLabel(): String? =
        _voiceSatellite.value?.lastAudioEventLabel()

    fun lastAudioEventAgeMs(): Long? =
        _voiceSatellite.value?.lastAudioEventAgeMs()

    fun audioEventDeviceTierName(): String? =
        _voiceSatellite.value?.audioEventDeviceTierName()

    fun audioEventProbe(): com.example.ava.detection.AudioEventProbeSnapshot =
        _voiceSatellite.value?.audioEventProbe()
            ?: com.example.ava.detection.AudioEventProbeSnapshot()

    fun voicePrintProbe(): com.example.ava.voiceprint.VoicePrintProbeSnapshot =
        _voiceSatellite.value?.voicePrintProbe()
            ?: com.example.ava.voiceprint.VoicePrintProbeSnapshot()

    fun wakeLiveProbe(): List<com.example.ava.microwakeword.WakeWordLiveProbe> =
        _voiceSatellite.value?.wakeLiveProbe().orEmpty()

    fun wakeBudgetProbe(): com.example.ava.openwakeword.WakeEngineBudgetProbe? =
        _voiceSatellite.value?.wakeBudgetProbe()

    fun pendingPcmTtsChunkCount(): Int =
        _voiceSatellite.value?.pendingPcmTtsChunkCount() ?: 0

    fun pcmTtsQueueDepth(): Int =
        _voiceSatellite.value?.pcmTtsQueueDepth() ?: 0

    fun pcmTtsBytesSubmitted(): Long =
        _voiceSatellite.value?.pcmTtsBytesSubmitted() ?: 0L

    fun sessionAccentWakeIndex(): Int =
        _voiceSatellite.value?.sessionAccentWakeIndex() ?: 0

    fun sessionAccentAgeMs(): Long? =
        _voiceSatellite.value?.sessionAccentAgeMs()

    fun isVoiceSatelliteRunning(): Boolean = _voiceSatellite.value != null

    /** Currently registered ESPHome entities. Off / unpublished features are absent. */
    fun snapshotEsphomeEntities(): List<com.example.ava.esphome.entities.Entity> =
        _voiceSatellite.value?.snapshotEntities().orEmpty()

    /**
     * Pause/resume wake detection (mic keeps running). Ref-counted so nested sheets stay continuous.
     * Depth is process-wide so an enrollment sheet can latch suspend before the satellite exists;
     * satellite create then applies it before [VoiceSatellite.start].
     */
    fun setWakeDetectionSuspended(value: Boolean) {
        requestWakeDetectionSuspended(value)
    }

    /** Capture one guided voiceprint enrollment sample; returns true if accepted. */
    suspend fun enrollVoicePrintSample(userIndex: Int = 0): Boolean =
        _voiceSatellite.value?.enrollVoicePrintSample(userIndex) ?: false

    fun markVoicePrintEnrollmentStart() {
        _voiceSatellite.value?.markVoicePrintEnrollmentStart()
    }

    fun stopVoicePrintEnrollmentListen() {
        _voiceSatellite.value?.stopVoicePrintEnrollmentListen()
    }

    private suspend fun availableWakeWordIdsForEngine(engine: WakeWordEngine): Set<String> {
        return when (engine) {
            WakeWordEngine.OPEN_WAKE_WORD -> WakeWordProviderFactory.vsWakeWordProvider(this)
                .listModels()
                .map { it.id }
                .toSet()
            WakeWordEngine.MICRO_WAKE_WORD -> WakeWordProviderFactory.microWakeWordProvider(this)
                .getWakeWords()
                .map { it.id }
                .toSet()
        }
    }

    private fun applyWakeWordEngineChange(engine: WakeWordEngine) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val current = microphoneSettingsStore.get()
            if (current.wakeWordEngine == engine) {
                _voiceSatellite.value?.publishVoiceAssistantConfiguration()
                return@launch
            }

            val availableWakeWordIds = availableWakeWordIdsForEngine(engine)
            // Engine-scoped restore only — Micro/VS lists stay separate despite shared names.
            val restoredWakeWords = compatibleWakeWordIdsForEngine(
                engine,
                current.activeWakeWordsForEngine(engine),
                availableWakeWordIds
            )
            val activeWakeWords = restoredWakeWords.ifEmpty {
                availableWakeWordIds.firstOrNull()?.let(::listOf) ?: emptyList()
            }
            microphoneSettingsStore.saveWakeWordEngine(engine, activeWakeWords)

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                restartVoiceSatellite(SatelliteRestartReason.SATELLITE_PIPELINE)
            }
        }
    }

    fun restartSendspinSession() {
        if (_voiceSatellite.value == null) return
        if (sendspinRestartJob?.isActive == true) {
            Log.d(TAG, "Sendspin restart already in flight; coalescing")
            return
        }

        sendspinRestartJob = lifecycleScope.launch {
            val stillEnabled = sendspinSettingsStore.get().enabled
            if (!stillEnabled) {
                // Disable / fleet-off must not arm a zombie rebind latch.
                closeSendspin(preserveOverlayForRebind = false)
                reconcileDeviceVolumeSync()
                return@launch
            }
            val existing = sendspinManager
            if (existing != null) {
                existing.disable(preserveOverlayForRebind = true)
                val port = com.example.ava.sendspin.SendspinManager.DEFAULT_CLIENT_PORT
                val freed = withContext(Dispatchers.IO) {
                    com.example.ava.sendspin.SendspinListenPort.awaitFree(port)
                }
                if (!freed) {
                    Log.e(TAG, "Sendspin listen port $port still occupied after disable")
                }
                val url = sendspinSettingsStore.get().serverUrl
                if (url.isNotEmpty()) {
                    existing.enable(url)
                } else {
                    existing.enable()
                }
                existing.prepareOverlayRebindIfNeeded()
                reconcileDeviceVolumeSync()
                return@launch
            }
            initSendspin()
            reconcileDeviceVolumeSync()
        }
    }
    
    fun updateSendspinSyncOffset(offsetMs: Int) {
        sendspinManager?.updateSyncOffset(offsetMs)
    }

    /** Current playback beacon for the LAN differential-alignment broadcast. */
    fun sendspinPeerPlaybackBeacon(): com.example.ava.sendspin.SendspinPeerBeacon? =
        sendspinManager?.peerPlaybackBeaconSnapshot()

    /** Inbound playback beacon from a grouped LAN peer. */
    fun onSendspinPeerPlaybackBeacon(
        peerId: String,
        audibleServerTsUs: Long,
        flags: Int,
        streamKey: Long,
    ) {
        sendspinManager?.onPeerPlaybackBeacon(peerId, audibleServerTsUs, flags, streamKey)
    }

    /** Stream we are attached to, for identity mirroring across a track change. */
    fun sendspinPeerStreamKey(): Long? = sendspinManager?.peerStreamKeySnapshot()

    /** Track identity to mirror to LAN peers, or null when we hold none. */
    fun sendspinPeerMediaIdentity(): com.example.ava.sendspin.SendspinPeerMediaIdentity? =
        sendspinManager?.peerMediaIdentitySnapshot()

    /** Playhead sample for LAN differential overlay alignment. */
    fun sendspinPeerProgressSample(): com.example.ava.sendspin.SendspinPeerProgressSample? =
        sendspinManager?.peerProgressSnapshot()

    /** Inbound identity mirror from a peer rendering the same stream. */
    fun onSendspinPeerMediaMeta(
        title: String,
        artist: String?,
        album: String?,
        artworkUrl: String?,
    ) {
        sendspinManager?.applyPeerMediaIdentity(title, artist, album, artworkUrl)
    }

    /** Inbound differential playhead from a peer on the same stream. */
    fun onSendspinPeerProgress(
        sample: com.example.ava.sendspin.SendspinPeerProgressSample,
        peerOutranksLocal: Boolean,
    ) {
        sendspinManager?.applyPeerProgressSample(sample, peerOutranksLocal)
    }
    
    /** Registered on process-wide singletons; cleared (identity-checked) in onDestroy. */
    private var registeredScenesReloadedCallback: (() -> Unit)? = null
    private var registeredPresenceAlertCallback: BluetoothPresenceManager.PresenceAlertCallback? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        _isRunning.value = true
        // Start the LAN playback beacon/playhead loop with the service itself.
        // It used to ride on presence acquisition (async, settings read first),
        // so a cold-started master could play for a while broadcasting nothing.
        com.example.ava.voice.AvaSyncOffsetPeer.bindAppContext(applicationContext)
        ScreensaverController.start(this)

        bluetoothWakeLock.create(this, TAG)
        cpuScreenOffWakeLock.create(this, TAG)
        registerScreenReceiver()
        syncCpuScreenOffWakeLockToScreenState()
        registerControlReceiver()
        registerMemoryTrimCallback()
        scheduleDeferredStartupInit()

        // Notification scene templates: after the scene list loads or refreshes, resubscribe to the HA entities referenced by placeholders
        // Keep our own reference to each callback: both live on process-wide
        // singletons that outlive this Service, and onDestroy must be able to
        // clear exactly what we registered (never a successor's registration).
        val scenesReloaded: () -> Unit = {
            lifecycleScope.launch { resubscribeSceneEntities() }
        }
        registeredScenesReloadedCallback = scenesReloaded
        com.example.ava.notifications.NotificationScenes.onScenesReloaded = scenesReloaded

        val presenceAlert =
            BluetoothPresenceManager.PresenceAlertCallback { address, wasPresent, isPresent, deviceName ->
                onBluetoothPresenceAlert(address, wasPresent, isPresent, deviceName)
            }
        registeredPresenceAlertCallback = presenceAlert
        BluetoothPresenceManager.getInstance(this).presenceAlertCallback = presenceAlert

        // The home-screen service tile tracks the satellite, not just this process.
        // Pierce into the satellite's inner EspHomeState (voiceSatelliteState) so
        // Connected ↔ Disconnected ↔ ServerError transitions re-render the tile —
        // _voiceSatellite alone only emits on satellite create/destroy. satelliteStarted
        // is combined in because it flips one step after the object is set (label race).
        lifecycleScope.launch {
            combine(satelliteStarted, voiceSatelliteState) { started, state ->
                when {
                    !started -> "stopped"
                    state is Connected -> "running"
                    state is Disconnected -> "disconnected"
                    state is ServerError -> "error"
                    state is Stopped -> "stopped"
                    // Listening / Processing / Responding render the same as running.
                    else -> "running"
                }
            }
                .distinctUntilChanged()
                .collect { key ->
                    // Launcher-drawn service cards recompose off this flow; bound
                    // AppWidgets (if any) are re-rendered explicitly below.
                    _serviceTileStateKey.value = key
                    AvaActionWidgets.refreshAll(this@VoiceSatelliteService)
                }
        }

        // Sensor cards mirror the icon picked in the Quick Entity panel; re-render
        // them when a slot's entity or icon changes so edits show up immediately.
        lifecycleScope.launch {
            quickEntitySettingsStore.data
                .map { settings -> settings.slots.map { it.entityId to it.icon } }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    AvaSensorWidgets.refreshAll(this@VoiceSatelliteService)
                }
        }

        // Ava LAN identity via existing UDP voice beacon (19848), independent of cluster toggle.
        lifecycleScope.launch {
            val name = runCatching { satelliteSettingsStore.get().name }.getOrNull().orEmpty()
            com.example.ava.voice.AvaVoiceDiscovery.acquire(
                this@VoiceSatelliteService,
                com.example.ava.voice.AvaVoiceDiscovery.HOLDER_PRESENCE,
                name,
            )
        }
    }
    
    private var controlReceiver: android.content.BroadcastReceiver? = null
    private var memoryTrimCallback: ComponentCallbacks2? = null
    
    private fun registerMemoryTrimCallback() {
        memoryTrimCallback = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                sendspinManager?.onTrimMemory(level)
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                sendspinManager?.onLowMemory()
            }
        }
        registerComponentCallbacks(memoryTrimCallback)
    }

    private fun registerControlReceiver() {
        controlReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                val action = intent?.action ?: return
                val host = context ?: this@VoiceSatelliteService
                if (!AvaControlGate.shouldHandle(host, action)) return
                Log.i(TAG, "Control action received: $action")
                when (action) {
                    "com.example.ava.ACTION_TOGGLE_MIC" -> toggleMicMute()
                    "com.example.ava.ACTION_MUTE_MIC" -> setMicMute(true)
                    "com.example.ava.ACTION_UNMUTE_MIC" -> setMicMute(false)
                    "com.example.ava.ACTION_WAKE" -> manualWake()
                    "com.example.ava.ACTION_STOP" -> stopVoiceSession()
                }
            }
        }
        
        val filter = android.content.IntentFilter().apply {
            addAction("com.example.ava.ACTION_TOGGLE_MIC")
            addAction("com.example.ava.ACTION_MUTE_MIC")
            addAction("com.example.ava.ACTION_UNMUTE_MIC")
            addAction("com.example.ava.ACTION_WAKE")
            addAction("com.example.ava.ACTION_STOP")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }
        Log.i(TAG, "Control receiver registered")
    }
    
    private fun registerScreenReceiver() {
        screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> {
                        ScreenControlUtils.updateScreenOnState(false)
                        bluetoothWakeLock.acquire()
                        if (playerSettingsStore.getCached().keepCpuAwakeOnScreenOff) {
                            cpuScreenOffWakeLock.acquire()
                        }
                    }
                    android.content.Intent.ACTION_SCREEN_ON -> {
                        ScreenControlUtils.updateScreenOnState(true)
                        // Unconditional: setting may have flipped while the lock was held.
                        cpuScreenOffWakeLock.release()
                    }
                }
            }
        }
        
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    /**
     * The screen receiver only fires on transitions, so a service (re)start while the
     * screen is already off (auto-restart / crash self-heal at night) would leave the
     * CPU unprotected until the next screen-on/off cycle. Reads the setting off the
     * main thread, which also warms [SettingsStoreImpl.getCached] for the receiver.
     */
    private fun syncCpuScreenOffWakeLockToScreenState() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (!playerSettingsStore.get().keepCpuAwakeOnScreenOff) return@launch
                val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager ?: return@launch
                if (!pm.isInteractive) {
                    cpuScreenOffWakeLock.acquire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "CPU screen-off wake lock sync failed", e)
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        // The system dialog steals focus over whatever the user is doing, and this
        // service is (re)created constantly — cold start, swipe-away restart, crash
        // restart. Auto-prompt at most once per install: a denial is an answer, not
        // an invitation to nag. Onboarding, the permission manager, and the service
        // settings screens all offer manual grant paths later.
        val prefs = getSharedPreferences("ava_prefs", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_OPT_PROMPTED, false)) {
            // Invisible to the user, so still worth retrying on rooted/kiosk
            // devices — but off the main thread: su/dumpsys block on waitFor().
            lifecycleScope.launch(Dispatchers.IO) {
                BatteryOptimizationHelper.tryShellWhitelist(this@VoiceSatelliteService)
            }
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Android 7 kiosk ROMs often omit this Settings screen entirely.
            if (intent.resolveActivity(packageManager) == null) {
                Log.d(TAG, "Battery optimization UI unavailable, trying shell whitelist")
                BatteryOptimizationHelper.tryShellWhitelist(this)
                return
            }
            prefs.edit().putBoolean(KEY_BATTERY_OPT_PROMPTED, true).apply()
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.d(TAG, "Battery optimization UI not found (common on kiosk ROMs)")
            BatteryOptimizationHelper.tryShellWhitelist(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request battery optimization exemption", e)
        }
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        
        val userStopped = getSharedPreferences("ava_prefs", MODE_PRIVATE)
            .getBoolean("service_user_stopped", false)
        
        
        if (!userStopped && _voiceSatellite.value != null) {
            val restartIntent = Intent(this, VoiceSatelliteService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
    }

    private fun scheduleDeferredStartupInit() {
        startupHandler.postDelayed({
            requestBatteryOptimizationExemption()
        }, 1200)
        startupHandler.postDelayed({
            com.example.ava.permissions.OverlayPermission.tryPrivilegedGrant(this)
        }, 1800)
        startupHandler.postDelayed({
            com.example.ava.utils.ShizukuUtils.init(packageName)
            com.example.ava.camera.DeviceCameraProfile.grantKioskCameraPermissionsIfNeeded(this)
        }, 2500)
    }

    class VoiceSatelliteBinder(val service: VoiceSatelliteService) : Binder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return VoiceSatelliteBinder(this)
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky restart after a process death: also bring the UI back when
        // crash self-heal is armed and Ava died on screen.
        if (intent == null) {
            com.example.ava.crash.CrashSelfHeal.maybeRelaunch(this)
        }

        if (_voiceSatellite.value != null) {
            return super.onStartCommand(intent, flags, startId)
        }
        
        
        if (!initializing.compareAndSet(false, true)) {
            return super.onStartCommand(intent, flags, startId)
        }

        
        createVoiceSatelliteServiceNotificationChannel(this@VoiceSatelliteService)
        
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val types = voiceSatelliteForegroundServiceTypes()
            startForeground(
                2,
                createVoiceSatelliteServiceNotification(
                    this@VoiceSatelliteService,
                    "Starting..."
                ),
                types
            )
            appliedForegroundServiceTypes = types
        } else {
            startForeground(
                2,
                createVoiceSatelliteServiceNotification(
                    this@VoiceSatelliteService,
                    "Starting..."
                )
            )
        }

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                wifiWakeLock.create(applicationContext, TAG)
                wifiWakeLock.acquire()

                satelliteSettingsStore.ensureMacAddressIsSet(applicationContext)
                val settings = satelliteSettingsStore.get()

                updateNotificationOnStateChanges()

                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
                     2,
                     createVoiceSatelliteServiceNotification(
                         this@VoiceSatelliteService,
                         Stopped.translate(resources)
                     )
                )

                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        com.example.ava.utils.RootUtils.requestRootPermission()

                        com.example.ava.utils.RootUtils.setProcessHighPriority(android.os.Process.myPid())
                        com.example.ava.utils.RootUtils.acquireCpuWakeLock()
                    } catch (e: Exception) {
                        Log.w(TAG, "Root request failed", e)
                    }
                }

                // A64 devices need to wait for audio framework to stabilize
                if (com.example.ava.utils.DeviceFeatureManager.isA64Device()) {
                    waitForAudioFramework()
                }

                // Suspend BEFORE start() when enrollment (or another holder) already latched depth,
                // so the first mic frames cannot fire wake/overlay while the sheet is open.
                val satellite = createVoiceSatellite(settings)
                applyPendingWakeDetectionSuspend(satellite)
                satellite.start()
                _voiceSatellite.value = satellite
                publishSatelliteStarted(true)
                withContext(Dispatchers.Main.immediate) {
                    if (_voiceSatellite.value === satellite) {
                        FreeformKeepAliveActivity.ensureMicVisible(applicationContext)
                    }
                }
                // Soft-stop tears the controller down without destroying this Service —
                // re-arm idle only after the satellite is actually live.
                ScreensaverController.onHostServiceStarted()
                // Sheet may latch during start(); re-apply is idempotent.
                applyPendingWakeDetectionSuspend(satellite)
                applyInitialWakeMode(satellite)
                awaitApiServerListening(satellite)
                ensureVoiceSatelliteNsdRegistered(settings)
                // HA allow_service_calls: async probe on first connect, process-once.
                scheduleHaServiceCallsProbeOnce()
                startWakeLockRenewal()
                startPeriodicUpdateCheck()
                
                
                startSettingsWatcher()
                startVolumeSyncLifecycle()
                startNsdReannounceLifecycle()
                
                
                loadCustomScenesFromSettings()
                
                
                val playerSettings = playerSettingsStore.get()
                syncOverlayPermissionState()
                val preserveOverlays = preservePassiveOverlaysOnNextStart
                preservePassiveOverlaysOnNextStart = false
                Log.d(
                    TAG,
                    "Overlay init: preserve=$preserveOverlays weather=${playerSettings.enableWeatherOverlay}/${playerSettings.enableWeatherOverlayVisible}, clock=${playerSettings.enableDreamClock}/${playerSettings.enableDreamClockVisible}"
                )
                if (preserveOverlays) {
                    // Internal restart: leave windows exactly as they are. Only re-bind QE
                    // state subscriptions to the new satellite (show is a no-op if already up).
                    syncVoiceMessageServices(playerSettings)
                    val quickEntitySettings = this@VoiceSatelliteService.quickEntitySettingsStore.data.first()
                    if (quickEntitySettings.enableQuickEntity && quickEntitySettings.enableQuickEntityDisplay) {
                        QuickEntityOverlayService.show(this@VoiceSatelliteService)
                    }
                } else {
                    // Cold start / home power-on: one restore queue, bottom → top.
                    // Browser is the dashboard base; remembered layers addView above it
                    // so a trailing fullscreen overlay does not kick them off screen.
                    // Music Overlay Display (HA vinyl_cover_display) must not restore ON from
                    // last session — expanded chrome is session UI, not a boot preference.
                    // Internal restart (preserveOverlays) keeps the live expanded/FAB state.
                    if (playerSettings.enableVinylCoverVisible) {
                        playerSettingsStore.enableVinylCoverVisible.set(false)
                    }
                    restoreColdStartOverlays(playerSettings)
                }
                if (playerSettings.enableEqMiniPlayer) {
                    cachedEqMiniPlayer = true
                    VinylCoverService.setEqMiniPreferred(true)
                } else {
                    VinylCoverService.setEqMiniPreferred(false)
                }

                if (preserveOverlays) {
                    reconcileBrowserVisibility()
                }

                migratePlaybackUiIfNeeded()

                withContext(Dispatchers.Main.immediate) {
                    initSendspin()
                }
                
                initializing.set(false)
                
                
                startAutoUpdateChecker()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start voice satellite", e)
                initializing.set(false)
                stopForegroundCompat()
                stopSelf()
            }
        }
        
        return START_STICKY
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.catchLog(name: String): kotlinx.coroutines.flow.Flow<T> = catch { e ->
        Log.e(TAG, "Settings watcher error in $name", e)
    }

    private fun startSettingsWatcher() {
        settingsWatcherJob?.cancel()
        settingsWatcherJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _voiceSatellite.flatMapLatest { satellite ->
                if (satellite == null) emptyFlow()
                else merge(
                    
                    
                    
                    satellite.audioInput.activeWakeWords.drop(1).onEach {
                        if (it.isNotEmpty()) {
                            val runtimeEngine = satellite.audioInput.wakeWordEngine
                            val currentSettings = microphoneSettingsStore.get()
                            val currentWakeWords = compatibleWakeWordIdsForEngine(
                                runtimeEngine,
                                currentSettings.activeWakeWordsForEngine(runtimeEngine),
                                satellite.audioInput.availableWakeWords.map { wakeWord -> wakeWord.id }.toSet()
                            )
                            if (currentWakeWords != it) {
                                microphoneSettingsStore.saveActiveWakeWordsForEngine(runtimeEngine, it)
                            }
                        }
                    }.catchLog("activeWakeWords"),
                    microphoneSettingsStore.getFlow().map { settings ->
                        val runtimeEngine = satellite.audioInput.wakeWordEngine
                        compatibleWakeWordIdsForEngine(
                            runtimeEngine,
                            settings.activeWakeWordsForEngine(runtimeEngine),
                            satellite.audioInput.availableWakeWords.map { it.id }.toSet()
                        )
                    }.distinctUntilChanged().drop(1).onEach { newWakeWords ->
                        val currentActive = satellite.audioInput.activeWakeWords.value
                        if (newWakeWords.isNotEmpty() && newWakeWords != currentActive) {
                            satellite.abortVoiceSessionIfActive()
                            satellite.audioInput.setActiveWakeWords(newWakeWords)
                            satellite.publishVoiceAssistantConfiguration()
                        }
                    }.catchLog("activeWakeWordsForEngine"),
                    microphoneSettingsStore.wakeWordSensitivity1.drop(1).onEach { sensitivity ->
                        val wakeWords = satellite.audioInput.activeWakeWords.value
                        if (wakeWords.isNotEmpty()) {
                            satellite.audioInput.updateWakeWordSensitivity(
                                wakeWords[0],
                                if (sensitivity > 0f) sensitivity else -1f,
                            )
                        }
                    }.catchLog("wakeWordSensitivity1"),
                    microphoneSettingsStore.wakeWordSensitivity2.drop(1).onEach { sensitivity ->
                        val wakeWords = satellite.audioInput.activeWakeWords.value
                        if (wakeWords.size > 1 && sensitivity > 0) {
                            satellite.audioInput.updateWakeWordSensitivity(wakeWords[1], sensitivity)
                        }
                    }.catchLog("wakeWordSensitivity2"),
                    microphoneSettingsStore.wakeWordExtraStrictness1.drop(1).onEach { level ->
                        val wakeWords = satellite.audioInput.activeWakeWords.value
                        if (wakeWords.isNotEmpty()) {
                            satellite.audioInput.updateWakeWordExtraStrictness(wakeWords[0], level)
                        }
                    }.catchLog("wakeWordExtraStrictness1"),
                    microphoneSettingsStore.wakeWordExtraStrictness2.drop(1).onEach { level ->
                        val wakeWords = satellite.audioInput.activeWakeWords.value
                        if (wakeWords.size > 1) {
                            satellite.audioInput.updateWakeWordExtraStrictness(wakeWords[1], level)
                        }
                    }.catchLog("wakeWordExtraStrictness2"),
                    microphoneSettingsStore.stopWordSensitivity.drop(1).onEach { sensitivity ->
                        val stopId = satellite.audioInput.currentStopWakeWordId
                        if (stopId != null) {
                            satellite.audioInput.updateWakeWordSensitivity(
                                stopId,
                                if (sensitivity > 0f) sensitivity else -1f,
                            )
                        }
                    }.catchLog("stopWordSensitivity"),
                    microphoneSettingsStore.getFlow().map { settings ->
                        val profile = DeviceAudioProfile.resolveById(settings.audioProfileId) ?: DeviceAudioProfile.DEFAULT
                        settings.resolveAudioSource(profile)
                    }.distinctUntilChanged().drop(1).onEach {
                        satellite.audioInput.setAudioSource(it)
                    }.catchLog("audioSource"),
                    microphoneSettingsStore.recordingPath.drop(1).onEach {
                        satellite.audioInput.setRecordingPath(it)
                    }.catchLog("recordingPath"),
                    microphoneSettingsStore.noiseSuppressorEnabled.drop(1).onEach {
                        satellite.audioInput.setNoiseSuppressorEnabled(it)
                    }.catchLog("noiseSuppressorEnabled"),
                    microphoneSettingsStore.softwareNsEnabled.drop(1).onEach {
                        satellite.audioInput.setSoftwareNsEnabled(it)
                    }.catchLog("softwareNsEnabled"),
                    microphoneSettingsStore.softwareNsStrength.drop(1).onEach {
                        satellite.audioInput.setSoftwareNsStrength(it)
                    }.catchLog("softwareNsStrength"),
                    microphoneSettingsStore.automaticGainControlEnabled.drop(1).onEach {
                        satellite.audioInput.setAutomaticGainControlEnabled(it)
                    }.catchLog("automaticGainControlEnabled"),
                    microphoneSettingsStore.acousticEchoCancelerEnabled.drop(1).onEach {
                        satellite.audioInput.setAcousticEchoCancelerEnabled(it)
                    }.catchLog("acousticEchoCancelerEnabled"),
                    microphoneSettingsStore.softwareAecEnabled.drop(1).onEach {
                        satellite.audioInput.setSoftwareAecEnabled(it)
                    }.catchLog("softwareAecEnabled"),
                    microphoneSettingsStore.softwareAecPauseDuringSpeech.drop(1).onEach {
                        satellite.audioInput.setSoftwareAecPauseDuringSpeech(it)
                    }.catchLog("softwareAecPauseDuringSpeech"),
                    microphoneSettingsStore.softwareAecStrength.drop(1).onEach {
                        satellite.audioInput.setSoftwareAecStrength(it)
                    }.catchLog("softwareAecStrength"),
                    microphoneSettingsStore.softwareAecRoom.drop(1).onEach {
                        satellite.audioInput.setSoftwareAecRoom(it)
                    }.catchLog("softwareAecRoom"),
                    microphoneSettingsStore.micGainDb.drop(1).onEach {
                        satellite.audioInput.setMicGainDb(it)
                    }.catchLog("micGainDb"),
                    satellite.audioInput.muted.drop(1).distinctUntilChanged().onEach { muted ->
                        val settingsMuted = microphoneSettingsStore.get().muted
                        if (settingsMuted == muted) return@onEach
                        microphoneSettingsStore.muted.set(muted)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val messageRes = if (muted) {
                                com.example.ava.R.string.microphone_muted_toast
                            } else {
                                com.example.ava.R.string.microphone_unmuted_toast
                            }
                            com.example.ava.ui.AvaToast.show(
                                this@VoiceSatelliteService,
                                messageRes,
                                tag = "mic-mute",
                            )
                        }
                    }.catchLog("muted"),
                    satellite.player.volume.drop(1).onEach { vol ->
                        if (satellite.player.isWhisperPlaybackActive) {
                            return@onEach
                        }
                        playerSettingsStore.volume.set(vol)
                        // Hardware / upstream mirrors set suppressSendspinVolumeUpdate so we do not
                        // call updateVolume → setStreamVolume again (percent↔step fight, #106).
                        // HA MediaPlayerCommandRequest volume arrives without suppress and writes once.
                        if (!suppressSendspinVolumeUpdate) {
                            sendspinManager?.updateVolume(vol)
                        }
                        suppressSendspinVolumeUpdate = false
                    }.catchLog("volume"),
                    sendspinSettingsStore.volumeFollowRule.drop(1).distinctUntilChanged().onEach {
                        reconcileDeviceVolumeSync(satellite)
                    }.catchLog("volumeFollowRule"),
                    satellite.player.muted.drop(1).onEach {
                        playerSettingsStore.muted.set(it)
                    }.catchLog("playerMuted"),
                    satellite.player.enableScreenOff.drop(1).onEach {
                        playerSettingsStore.enableScreenOff.set(it)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            applyScreenToggle(it)
                        }
                    }.catchLog("screenOff"),
                    
                    
                    satellite.player.mediaPlayer.state.onEach { _ -> }.catchLog("mediaState"),
                    
                    playerSettingsStore.enableDreamClock.drop(1).distinctUntilChanged().onEach { enabled ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (enabled && playerSettingsStore.get().enableDreamClockVisible) {
                                DreamClockService.show(this@VoiceSatelliteService)
                            } else {
                                DreamClockService.hide(this@VoiceSatelliteService)
                            }
                        }
                    }.catchLog("dreamClock"),
                    
                    playerSettingsStore.enableWeatherOverlay.drop(1).distinctUntilChanged().onEach { enabled ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (enabled && playerSettingsStore.get().enableWeatherOverlayVisible) {
                                WeatherOverlayService.show(this@VoiceSatelliteService)
                            } else {
                                WeatherOverlayService.hide(this@VoiceSatelliteService)
                            }
                        }
                    }.catchLog("weatherOverlay"),
                    
                    playerSettingsStore.enableWeatherOverlayVisible.drop(1).distinctUntilChanged().onEach { enabled ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val settings = playerSettingsStore.get()
                            if (settings.enableWeatherOverlay && enabled) {
                                WeatherOverlayService.show(this@VoiceSatelliteService)
                            } else {
                                // Always hide — do not use setVisible(false), which no-ops when
                                // isEnabled was cleared by a prior hide/swipe/soft-stop.
                                WeatherOverlayService.hide(this@VoiceSatelliteService)
                            }
                        }
                    }.catchLog("weatherVisible"),

                    playerSettingsStore.enableVinylCoverVisible.drop(1).distinctUntilChanged().onEach { visible ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val settings = playerSettingsStore.get()
                            if (!settings.enableVinylCoverDisplay) return@withContext
                            if (visible) {
                                VinylCoverService.showExpandedFromHa(this@VoiceSatelliteService)
                            } else {
                                VinylCoverService.hideExpandedFromHa(this@VoiceSatelliteService)
                            }
                        }
                    }.catchLog("vinylCoverVisible"),
                    
                    playerSettingsStore.enableDreamClockVisible.drop(1).distinctUntilChanged().onEach { enabled ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val settings = playerSettingsStore.get()
                            if (settings.enableDreamClock && enabled) {
                                DreamClockService.show(this@VoiceSatelliteService)
                            } else {
                                DreamClockService.hide(this@VoiceSatelliteService)
                            }
                        }
                    }.catchLog("dreamClockVisible"),
                    
                    playerSettingsStore.enableScreensaver.drop(1).distinctUntilChanged().onEach { enabled ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (enabled) {
                                if (playerSettingsStore.get().enableScreensaverVisible) {
                                    ScreensaverService.show(this@VoiceSatelliteService)
                                } else {
                                    ScreensaverService.hide(this@VoiceSatelliteService)
                                }
                            } else {
                                if (ScreensaverService.isRunning()) {
                                    ScreensaverService.stop(this@VoiceSatelliteService)
                                }
                                playerSettingsStore.enableScreensaverVisible.set(false)
                            }
                            restartVoiceSatellite()
                        }
                    }.catchLog("screensaver"),
                    
                    playerSettingsStore.enableScreensaverVisible.drop(1).distinctUntilChanged().onEach { enabled ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val settings = playerSettingsStore.get()
                            if (settings.enableScreensaver && enabled) {
                                ScreensaverService.show(this@VoiceSatelliteService)
                            } else {
                                ScreensaverService.hide(this@VoiceSatelliteService)
                            }
                        }
                    }.catchLog("screensaverVisible"),

                    playerSettingsStore.enableVoiceMessageReceive.drop(1).distinctUntilChanged().onEach {
                        syncVoiceMessageServices()
                    }.catchLog("voiceMessageReceive"),

                    playerSettingsStore.enableVoiceCallAnswerRequired.drop(1).distinctUntilChanged().onEach {
                        syncVoiceMessageServices()
                    }.catchLog("voiceCallAnswerRequired"),

                    playerSettingsStore.enableVoiceMessageOverlay.drop(1).distinctUntilChanged().onEach { enabled ->
                        syncVoiceMessageServices()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (enabled) {
                                val settings = playerSettingsStore.get()
                                if (settings.enableVoiceMessageOverlayVisible) {
                                    VoiceMessageOverlayService.show(this@VoiceSatelliteService)
                                } else {
                                    VoiceMessageOverlayService.ensureEnabled(this@VoiceSatelliteService, false)
                                }
                            } else {
                                VoiceMessageOverlayService.hide(this@VoiceSatelliteService)
                            }
                        }
                    }.catchLog("voiceMessageOverlay"),

                    playerSettingsStore.voiceMessageDisplayName.drop(1).distinctUntilChanged().onEach {
                        syncVoiceMessageServices()
                    }.catchLog("voiceMessageDisplayName"),

                    playerSettingsStore.voiceMessageReceiveMode.drop(1).distinctUntilChanged().onEach {
                        syncVoiceMessageServices()
                    }.catchLog("voiceMessageReceiveMode"),

                    playerSettingsStore.enableVoiceMessageOverlayVisible.drop(1).distinctUntilChanged().onEach { enabled ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val settings = playerSettingsStore.get()
                            if (settings.enableVoiceMessageOverlay && enabled) {
                                VoiceMessageOverlayService.show(this@VoiceSatelliteService)
                            } else {
                                VoiceMessageOverlayService.hide(this@VoiceSatelliteService)
                            }
                        }
                    }.catchLog("voiceMessageVisible"),
                    
                    satellite.player.notificationSceneRequests.onEach { sceneTitle ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (SceneReset.isHideCommand(sceneTitle)) {
                                NotificationOverlayService.hide(this@VoiceSatelliteService)
                            } else {
                                NotificationOverlayService.showSceneByTitle(this@VoiceSatelliteService, sceneTitle)
                            }
                        }
                    }.catchLog("notificationScene"),
                    
                    browserSettingsStore.enableBrowserVisible.drop(1).distinctUntilChanged().onEach { visible ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (!visible) {
                                WebViewService.hide(this@VoiceSatelliteService)
                            } else {
                                // Switch ON: force gecko overlay grant + start floating browser.
                                val browserSettings = browserSettingsStore.get()
                                com.example.ava.settings.BrowserSettingsStore.rememberEngine(
                                    this@VoiceSatelliteService,
                                    browserSettings.browserEngine,
                                )
                                if (browserSettings.haRemoteUrlEnabled && browserSettings.enableBrowserDisplay) {
                                    val savedVoiceSettings = satelliteSettingsStore.get()
                                    val left = savedVoiceSettings.haRemoteUrl
                                    val right = savedVoiceSettings.haRemoteUrlRight
                                    val splitActive =
                                        browserSettings.splitViewEnabled &&
                                            browserSettings.browserEngine !=
                                            com.example.ava.webcompat.BrowserEngine.GECKO
                                    when {
                                        splitActive && (left.isNotBlank() || right.isNotBlank()) -> {
                                            WebViewService.applyHaRemoteUrls(
                                                this@VoiceSatelliteService,
                                                left,
                                                right,
                                            )
                                        }
                                        left.isNotEmpty() -> {
                                            WebViewService.show(
                                                this@VoiceSatelliteService,
                                                left,
                                            )
                                        }
                                    }
                                }
                            }
                            updateBrowserSubServiceDeathMonitor(visible)
                        }
                    }.catchLog("browserVisible"),
                    
                    browserSettingsStore.initialScale.drop(1).distinctUntilChanged().onEach { scale ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            WebViewService.updateScale(this@VoiceSatelliteService, scale)
                        }
                    }.catchLog("browserInitialScale"),
                    
                    kotlinx.coroutines.flow.combine(
                        playerSettingsStore.enableHaVinylCover,
                        playerSettingsStore.enableSendspinVinylCover,
                        playerSettingsStore.enableEqMiniPlayer,
                        satelliteSettingsStore.getFlow().map { it.haMediaPlayerEntity },
                    ) { haVinyl, sendspinVinyl, eqMini, haEntity ->
                        listOf(haVinyl, sendspinVinyl, eqMini, haEntity)
                    }.distinctUntilChanged().drop(1).onEach { values ->
                        val haVinyl = values[0] as Boolean
                        val sendspinVinyl = values[1] as Boolean
                        val haEntity = values[3] as String
                        updateVinylCoverCache()
                        // Mini FAB is not a third master switch — only HA/MA media controls
                        // may keep the overlay window alive (immediate destroy when both off).
                        val haWanted = haVinyl && haEntity.isNotEmpty()
                        val sendspinWanted = sendspinVinyl
                        val overlayUiWanted = haWanted || sendspinWanted
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (!overlayUiWanted) {
                                cancelHideJob()
                                VinylCoverService.hide(this@VoiceSatelliteService, force = true)
                            } else {
                                val sendspinOwns =
                                    VinylCoverService.isSendspinContentActive ||
                                        sendspinManager?.isActive?.value == true ||
                                        sendspinManager?.wasRecentlyAudible() == true
                                // Drop paint that no longer has its protocol gate.
                                if ((!sendspinWanted && sendspinOwns) ||
                                    (!haWanted && !sendspinOwns)
                                ) {
                                    cancelHideJob()
                                    VinylCoverService.hide(
                                        this@VoiceSatelliteService,
                                        force = true,
                                    )
                                }
                                // Re-hydrate only if a still-enabled gate allows show.
                                pushMediaOverlaySnapshot()
                            }
                        }
                        reconcileDeviceVolumeSync(satellite)
                    }.catchLog("playbackOverlay"),
                    playerSettingsStore.exposeEsphomeMediaPlayerEntity.drop(1).distinctUntilChanged().onEach {
                        restartVoiceSatellite()
                    }.catchLog("exposeEsphomeMediaPlayer"),
                    playerSettingsStore.exposeWhisperResponseEntity.drop(1).distinctUntilChanged().onEach {
                        restartVoiceSatellite()
                    }.catchLog("exposeWhisperResponseEntity"),
                    playerSettingsStore.whisperResponseVolume.drop(1).distinctUntilChanged().onEach { volume ->
                        applyLiveVoiceReplyVolume(volume)
                    }.catchLog("whisperResponseVolume"),
                    playerSettingsStore.enableAmbientAutoGain.drop(1).distinctUntilChanged().onEach { enabled ->
                        applyLiveVoiceReplyVolume(
                            playerSettingsStore.getCached().whisperResponseVolume,
                            autoGain = enabled,
                        )
                    }.catchLog("enableAmbientAutoGain"),
                    playerSettingsStore.getFlow()
                        .map { it.toHaMusicEqGains() }
                        .distinctUntilChanged()
                        .onEach { gains ->
                            com.example.ava.audio.eq.MusicEqRuntime.set(
                                com.example.ava.audio.eq.MusicEqSource.HA,
                                gains,
                            )
                        }
                        .catchLog("haMusicEq"),
                    sendspinSettingsStore.getFlow()
                        .map { it.toMusicEqGains() }
                        .distinctUntilChanged()
                        .onEach { gains ->
                            com.example.ava.audio.eq.MusicEqRuntime.set(
                                com.example.ava.audio.eq.MusicEqSource.SENDSPIN,
                                gains,
                            )
                        }
                        .catchLog("sendspinMusicEq"),
                    sendspinSettingsStore.enabled.drop(1).distinctUntilChanged().onEach { enabled ->
                        if (enabled) {
                            restartSendspinSession()
                        } else {
                            closeSendspin(preserveOverlayForRebind = false)
                            reconcileDeviceVolumeSync(satellite)
                        }
                    }.catchLog("sendspinEnabled"),
                    experimentalSettingsStore.multiDeviceArbiterEnabled
                        .drop(1)
                        .distinctUntilChanged()
                        .onEach { enabled ->
                            satellite.multiDeviceArbiterEnabled = enabled
                            syncChorusWakeArbiter(enabled)
                        }
                        .catchLog("multiDeviceArbiterEnabled"),
                )
            }.collect {}
        }
    }
    
    
    private suspend fun loadCustomScenesFromSettings() {
        val notificationSettings = notificationSettingsStore.get()
        val url = notificationSettings.customSceneUrl
        if (url.isNotBlank()) {
            NotificationScenes.loadCustomSceneFromUrl(url, context = applicationContext)
        }
    }

                    private suspend fun createVoiceSatellite(satelliteSettings: VoiceSatelliteSettings): VoiceSatellite {
                        val microphoneSettings = microphoneSettingsStore.get()
                        val voiceChannelEnabled = voiceChannelSettingsStore.get().enabled
                        val wakeWordEngine = microphoneSettings.wakeWordEngine
                        val vsWakeWordProvider = WakeWordProviderFactory.vsWakeWordProvider(applicationContext)
                        val wakeWordProvider = when (wakeWordEngine) {
                            WakeWordEngine.MICRO_WAKE_WORD ->
                                WakeWordProviderFactory.microWakeWordProvider(applicationContext)
                            // VS runtime uses vsWakeWordProvider; Micro provider is unused.
                            // Do not point AssetWakeWordProvider at vswakeword/ (VS JSON ≠ Micro).
                            WakeWordEngine.OPEN_WAKE_WORD ->
                                WakeWordProviderFactory.microWakeWordProvider(applicationContext)
                        }
                        val availableWakeWordIds = when (wakeWordEngine) {
                            WakeWordEngine.OPEN_WAKE_WORD -> vsWakeWordProvider.listModels()
                                .map { it.id }
                                .toSet()
                            WakeWordEngine.MICRO_WAKE_WORD -> wakeWordProvider.getWakeWords().map { it.id }.toSet()
                        }
                        val availableStopWordIds = when (wakeWordEngine) {
                            WakeWordEngine.OPEN_WAKE_WORD -> vsWakeWordProvider.listStopEligibleModels()
                                .map { it.id }
                                .toSet()
                            WakeWordEngine.MICRO_WAKE_WORD -> availableWakeWordIds
                        }
                        val fallbackWakeWord = when {
                            wakeWordEngine == WakeWordEngine.OPEN_WAKE_WORD &&
                                "ok_nabu" in availableWakeWordIds -> "ok_nabu"
                            else -> availableWakeWordIds.firstOrNull() ?: microphoneSettings.wakeWord
                        }
                        val requestedWakeWords = microphoneSettings.activeWakeWordsForEngine(wakeWordEngine)
                            .distinct()
                            .take(2)
                        val savedWakeWords = compatibleWakeWordIdsForEngine(
                            wakeWordEngine,
                            requestedWakeWords,
                            availableWakeWordIds
                        )
                            .ifEmpty { listOf(fallbackWakeWord) }
                        // Scrub engine bucket when stored ids are not in this engine's
                        // catalog (shared names like hey_jarvis must not keep a VS
                        // selection alive under Micro after a botched switch).
                        if (savedWakeWords != requestedWakeWords) {
                            microphoneSettingsStore.saveActiveWakeWordsForEngine(
                                wakeWordEngine,
                                savedWakeWords,
                            )
                        }
                        // Stop slot: builtin (model-free DSP), none, or a model id of this
                        // engine. A model must exist in this engine's catalog and must not
                        // collide with a wake slot; otherwise fall back to builtin (never
                        // silently lose stop coverage over a deleted/foreign model).
                        val stopWordSetting = microphoneSettings.activeStopWordForEngine(wakeWordEngine)
                        val resolvedStopWakeWordId = when (stopWordSetting) {
                            MicrophoneSettings.STOP_WORD_BUILTIN,
                            MicrophoneSettings.STOP_WORD_NONE -> null
                            else -> wakeWordIdCandidatesForEngine(wakeWordEngine, stopWordSetting)
                                .firstOrNull { it in availableStopWordIds && it !in savedWakeWords }
                        }
                        val builtinStopWordEnabled = when {
                            stopWordSetting == MicrophoneSettings.STOP_WORD_NONE -> false
                            else -> resolvedStopWakeWordId == null
                        }
                        if (stopWordSetting != MicrophoneSettings.STOP_WORD_BUILTIN) {
                            Log.i(
                                TAG,
                                "stop slot setting=$stopWordSetting resolvedModel=$resolvedStopWakeWordId " +
                                    "builtinDsp=$builtinStopWordEnabled",
                            )
                        }
                        // openWakeWord uses the same capture profile as micro; the old
                        // forced 48 kHz stereo raw profile was for the removed vs engine
                        // and disabled HW/SW echo cancellation.
                        val audioProfile = DeviceAudioProfile.resolveById(microphoneSettings.audioProfileId)
                            ?: DeviceAudioProfile.DEFAULT
                        Log.i(TAG, "Using audio profile ${audioProfile.id}")
                        val audioInput = VoiceSatelliteAudioInput(
                            activeWakeWords = savedWakeWords,
                            wakeWordEngine = wakeWordEngine,
                            wakeWordProvider = wakeWordProvider,
                            vsWakeWordProvider = vsWakeWordProvider,
                            stopWakeWordId = resolvedStopWakeWordId,
                            builtinStopWordEnabled = builtinStopWordEnabled,
                            appContext = applicationContext,
                            audioProfile = audioProfile,
                            enabled = voiceChannelEnabled,
                            muted = microphoneSettings.muted,
                            audioSource = microphoneSettings.resolveAudioSource(audioProfile),
                            noiseSuppressorEnabled = microphoneSettings.noiseSuppressorEnabled,
                            softwareNsEnabled = microphoneSettings.softwareNsEnabled,
                            softwareNsStrength = microphoneSettings.softwareNsStrength,
                            automaticGainControlEnabled = microphoneSettings.automaticGainControlEnabled,
                            acousticEchoCancelerEnabled = microphoneSettings.acousticEchoCancelerEnabled,
                            softwareAecEnabled = microphoneSettings.softwareAecEnabled,
                            softwareAecPauseDuringSpeech = microphoneSettings.softwareAecPauseDuringSpeech,
                            softwareAecStrength = microphoneSettings.softwareAecStrength,
                            softwareAecRoom = microphoneSettings.softwareAecRoom,
                            micGainDb = microphoneSettings.micGainDb,
                            recordingPath = microphoneSettings.recordingPath,
                        )
                    
                        val playerSettings = playerSettingsStore.get()
                        com.example.ava.audio.eq.MusicEqRuntime.set(
                            com.example.ava.audio.eq.MusicEqSource.HA,
                            playerSettings.toHaMusicEqGains(),
                        )
                        com.example.ava.audio.eq.MusicEqRuntime.set(
                            com.example.ava.audio.eq.MusicEqSource.SENDSPIN,
                            sendspinSettingsStore.get().toMusicEqGains(),
                        )
                        haMediaPlaybackStateInitialized = false
                        cachedHaPlaybackState = false
                        cachedVinylCoverEnabled = playerSettings.enableHaVinylCover &&
                            satelliteSettings.haMediaPlayerEntity.isNotEmpty()
                        cachedHaVinylCoverEnabled = playerSettings.enableHaVinylCover
                        cachedEqMiniPlayer = playerSettings.enableEqMiniPlayer
                        val experimentalSettingsData = experimentalSettingsStore.get()
                        val browserSettingsData = browserSettingsStore.get()
                        val screensaverSettingsData = com.example.ava.settings.ScreensaverSettingsStore(
                            applicationContext.screensaverSettingsStore
                        ).get()
                        
                        // Continue mode as it is in force (ContinueMode): a stored 超级智能 only
                        // counts while the AI path is cloud, so every runtime gate sees the same
                        // effective value and it comes back with the cloud path. Writes still go
                        // to the stored flag.
                        val continueModeFlow = ContinueMode.flow(
                            playerSettingsStore.getFlow(),
                            com.example.ava.localllm.LocalLlmManager.getInstance(applicationContext).settingsStore.getFlow(),
                        )
                        fun continueModeState(mode: ContinueMode, stored: SettingState<Boolean>) =
                            SettingState(continueModeFlow.map { it == mode }) { stored.set(it) }
                        val player = VoiceSatellitePlayer(
                            ttsPlayer = TtsPlayer(
                                createAudioPlayer(
                                    // Default USAGE_MEDIA unless a router mod splits TTS onto
                                    // its own usage (ModAudioRouter); zero change when inactive.
                                    com.example.ava.mods.ModAudioRouter.ttsAudioUsage(applicationContext),
                                    AUDIO_CONTENT_TYPE_MUSIC,
                                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                                    tapPlaybackEnergy = true,
                                    httpStreamingTts = true,
                                    // Overlay on Sendspin: never fight SP's GAIN.
                                    // Streaming PCM TTS already mixes without focus;
                                    // URL ExoPlayer must do the same or SP ensureHeld
                                    // re-steals GAIN mid-reply and cancels TTS + subtitles.
                                    handleAudioFocus = false,
                                )
                            ),
                            mediaPlayer = createAudioPlayer(
                                USAGE_MEDIA,
                                AUDIO_CONTENT_TYPE_MUSIC,
                                AudioManager.AUDIOFOCUS_GAIN,
                                enableMusicEq = true,
                            ),
                            wakeSoundPlayer = createAudioPlayer(
                                USAGE_MEDIA,
                                AUDIO_CONTENT_TYPE_MUSIC,
                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                            ),
                            volume = playerSettings.volume,
                            muted = playerSettings.muted,
                            enableWakeSound = playerSettingsStore.enableWakeSound,
                            enableScreenOff = playerSettingsStore.enableScreenOff,
                            wakeSound = playerSettingsStore.wakeSound,
                            wakeSound2 = playerSettingsStore.wakeSound2,
                            timerFinishedSound = playerSettingsStore.timerFinishedSound,
                            stopSound = playerSettingsStore.stopSound,
                            enableStopSound = playerSettingsStore.enableStopSound,
                            continuousPromptSound = playerSettingsStore.continuousPromptSound,
                            enableContinuousConversation = playerSettingsStore.enableContinuousConversation,
                            enableQuestionMarkContinue = continueModeState(ContinueMode.QUESTION_MARK, playerSettingsStore.enableQuestionMarkContinue),
                            enableExitKeywordStop = continueModeState(ContinueMode.EXIT_KEYWORD, playerSettingsStore.enableExitKeywordStop),
                            enableSmartContinue = continueModeState(ContinueMode.SMART, playerSettingsStore.enableSmartContinue),
                            enableStreamingTtsSubtitles = playerSettingsStore.enableStreamingTtsSubtitles,
                            preserveTtsHttps = playerSettingsStore.preserveTtsHttps,
                        ).apply {
                            onVoiceReplyStreamVolume = { level ->
                                if (level == null) {
                                    restoreVoiceReplyStreamVolume()
                                } else {
                                    applyVoiceReplyStreamVolume(level)
                                }
                            }
                            onMediaPlay = { url ->
                                lastPlayedUrl = url
                            }
                            onMediaCover = { coverUrl ->
                            }
                            onMediaDuration = { duration ->
                                
                                
                            }
                            // mediaPlayer end must NOT unDuck SP — that races mid-TTS
                            // (VoiceSatellitePlayer wires this to media, not ttsPlayer).
                            // Voice overlay restore is owned by onConversationEnd only.
                            onMediaPause = {
                                if (sendspinManager?.isActive?.value != true) {
                                    sendspinManager?.stopPlayback()
                                }
                            }
                            onMediaResume = {
                            }
                            onMediaStop = {
                                if (sendspinManager?.isActive?.value != true) {
                                    sendspinManager?.stopPlayback()
                                }
                            }
                            onPlaybackStarted = {
                                lifecycleScope.launch {
                                    VinylCoverService.noteSessionPlaybackStarted()
                                    if (sendspinManager?.isActive?.value == true) {
                                        suppressSendspinPlaybackConflictTemporarily()
                                        sendspinManager?.stopPlayback()
                                        Log.d(TAG, "Built-in media started while Sendspin active, stopping Sendspin playback")
                                    } else {
                                        sendspinManager?.stopPlayback()
                                        Log.d(TAG, "Built-in media started playing, stopping Sendspin playback")
                                    }
                                }
                            }
                            onHaCoverUrl = { coverUrl ->
                                lifecycleScope.launch {
                                    if (!shouldPushHaMediaOverlay()) return@launch
                                    if (coverUrl.isEmpty()) {
                                        // Cover cleared with queue — if title already gone, hide FAB.
                                        if (haMediaTitleCache.isEmpty() && !haPlaybackState.value) {
                                            clearHaMediaCache()
                                            cancelHideJob()
                                            VinylCoverService.hide(
                                                this@VoiceSatelliteService,
                                                force = true,
                                            )
                                        }
                                        return@launch
                                    }
                                    VinylCoverService.setHaCover(this@VoiceSatelliteService, coverUrl)
                                }
                            }
                            onHaMediaTitle = { title ->
                                val seededPositionMs = haMediaPositionMs.takeIf { it > 0L }
                                    ?: interpolatedHaPositionMs().takeIf { it > 1_500L }
                                lifecycleScope.launch {
                                    if (!shouldPushHaMediaOverlay()) return@launch
                                    // Clear-queue / no now-playing: empty title must tear down
                                    // the mini FAB — soft hide would keep stale metadata on screen.
                                    if (title.isEmpty()) {
                                        val nothingLeft =
                                            haMediaArtistCache.isEmpty() &&
                                                haMediaAlbumCache.isEmpty() &&
                                                haMediaCoverCache.isEmpty()
                                        if (!haPlaybackState.value || nothingLeft) {
                                            clearHaMediaCache()
                                            cancelHideJob()
                                            VinylCoverService.hide(
                                                this@VoiceSatelliteService,
                                                force = true,
                                            )
                                        }
                                        return@launch
                                    }
                                    VinylCoverService.updateMetadata(
                                        this@VoiceSatelliteService,
                                        songTitle = title,
                                        artistName = null,
                                        isPlaying = haPlaybackState.value,
                                        // Always prefer a real HA playhead on reconnect.
                                        // Omitting currentTimeMs used to force overlay to 0:00.
                                        currentTimeMs = seededPositionMs,
                                        isSendspinSource = false,
                                    )
                                }
                            }
                            onHaMediaArtist = { artist ->
                                lifecycleScope.launch {
                                    if (shouldPushHaMediaOverlay() && artist.isNotEmpty()) {
                                        VinylCoverService.updateMetadata(
                                            this@VoiceSatelliteService,
                                            artistName = artist,
                                            isSendspinSource = false,
                                        )
                                    }
                                }
                            }
                            onHaMediaAlbum = { album ->
                                lifecycleScope.launch {
                                    if (shouldPushHaMediaOverlay() && album.isNotEmpty()) {
                                        VinylCoverService.updateMetadata(
                                            this@VoiceSatelliteService,
                                            albumName = album,
                                            isSendspinSource = false,
                                        )
                                    }
                                }
                            }
                            onHaMediaDuration = { durationMs ->
                                lifecycleScope.launch {
                                    val player = _voiceSatellite.value?.player ?: return@launch
                                    if (shouldPushHaMediaOverlay()) {
                                        VinylCoverService.updateProgress(
                                            this@VoiceSatelliteService,
                                            totalTimeMs = durationMs,
                                            isSendspinSource = false,
                                        )
                                        pushHaOverlayProgress(player)
                                    }
                                }
                            }
                            onHaMediaPosition = { _ ->
                                lifecycleScope.launch {
                                    val player = _voiceSatellite.value?.player ?: return@launch
                                    if (shouldPushHaMediaOverlay()) {
                                        pushHaOverlayProgress(player)
                                    }
                                }
                            }
                            onHaMediaPositionUpdatedAt = { _ ->
                                lifecycleScope.launch {
                                    val player = _voiceSatellite.value?.player ?: return@launch
                                    if (shouldPushHaMediaOverlay()) {
                                        pushHaOverlayProgress(player)
                                    }
                                }
                            }
                            onHaVolumeLevel = { volume ->
                                lifecycleScope.launch {
                                    if (VolumeFollowRule.fromSettings(sendspinSettingsStore.get()).mirrorsHaToDevice) {
                                        applyHaVolumeLevelToDevice(volume)
                                    }
                                    if (shouldPushHaMediaOverlay()) {
                                        VinylCoverService.updatePlaybackSettings(
                                            this@VoiceSatelliteService,
                                            volumeLevel = volume
                                        )
                                    }
                                }
                            }
                            onHaRepeatMode = { mode ->
                                lifecycleScope.launch {
                                    if (shouldPushHaMediaOverlay()) {
                                        // Legacy HA-only vinyl path unchanged.
                                        VinylCoverService.updatePlaybackSettings(
                                            this@VoiceSatelliteService,
                                            repeatMode = mode
                                        )
                                    } else if (
                                        isSendspinBlockingHaMediaOverlay() &&
                                        isHaMirrorEntityForTransport(haMediaTitleCache)
                                    ) {
                                        // New-MA: queue repeat lands on HA attrs; Sendspin
                                        // controller often not republished. UI paint only.
                                        // Mass title mirror counted the same as SP.
                                        sendspinManager?.applyQueueTransportUiBridge(
                                            repeatMode = mode
                                        )
                                    }
                                }
                            }
                            onHaShuffle = { shuffle ->
                                lifecycleScope.launch {
                                    if (shouldPushHaMediaOverlay()) {
                                        VinylCoverService.updatePlaybackSettings(
                                            this@VoiceSatelliteService,
                                            shuffleEnabled = shuffle
                                        )
                                    } else if (
                                        isSendspinBlockingHaMediaOverlay() &&
                                        isHaMirrorEntityForTransport(haMediaTitleCache)
                                    ) {
                                        sendspinManager?.applyQueueTransportUiBridge(
                                            shuffleEnabled = shuffle
                                        )
                                    }
                                }
                            }
                            onHaPlaybackStateWithMetadata = { isPlaying, hasMetadata ->
                                val player = this
                                val coverCache = haMediaCoverCache
                                val titleCache = haMediaTitleCache
                                val artistCache = haMediaArtistCache
                                val albumCache = haMediaAlbumCache
                                val durationMs = haMediaDurationMs.takeIf { it > 0L }
                                val positionMs = interpolatedHaPositionMs().coerceAtLeast(0L)
                                lifecycleScope.launch {
                                    if (isPlaying) {
                                        // Mirror echo guard: HA "playing" for the very track this
                                        // Sendspin/Mass session is playing is our own state reflected
                                        // back through HA — stopping here killed the audible
                                        // player and handed the overlay to the stale HA copy.
                                        if (isHaPlayingMirrorOfProtocol(titleCache)) {
                                            Log.d(
                                                TAG,
                                                "HA playing mirrors current SP/Mass track, keeping Sendspin playback",
                                            )
                                        } else {
                                            sendspinManager?.stopPlayback()
                                            VinylCoverService.cancelPauseIdleTeardown(
                                                this@VoiceSatelliteService,
                                            )
                                            Log.d(TAG, "HA media started playing, stopping Sendspin playback")
                                        }
                                    }
                                    // Pause-idle is shared with Sendspin on VinylCoverService.
                                    // Arm even when shouldPush is false due to sticky Sendspin
                                    // isActive — but never while Sendspin still owns the paint.
                                    if (!isPlaying &&
                                        !isVoiceInteractionActive() &&
                                        !VinylCoverService.isSendspinContentActive
                                    ) {
                                        reconcileHaProgressOverlayTicker(player, false)
                                        // Idempotent: repeated paused HA pushes must not reset yield clock.
                                        VinylCoverService.schedulePauseIdleTeardown(
                                            this@VoiceSatelliteService,
                                            restart = false,
                                        )
                                    }
                                    if (shouldPushHaMediaOverlay()) {
                                        if (isPlaying && hasMetadata) {
                                            if (!haMediaPlaybackStateInitialized) {
                                                haMediaPlaybackStateInitialized = true
                                            } else if (!cachedHaPlaybackState && isPlaying) {
                                                VinylCoverService.noteSessionPlaybackStarted()
                                            }
                                            if (!VinylCoverService.isOverlayBirthSuppressed()) {
                                                cancelHideJob()
                                                VinylCoverService.cancelPauseIdleTeardown(
                                                    this@VoiceSatelliteService,
                                                )
                                                VinylCoverService.show(
                                                    this@VoiceSatelliteService,
                                                    coverUrl = coverCache.ifEmpty { null },
                                                    songTitle = titleCache.ifEmpty { null },
                                                    artistName = artistCache.ifEmpty { null },
                                                    albumName = albumCache.ifEmpty { null },
                                                    currentTimeMs = positionMs,
                                                    totalTimeMs = durationMs,
                                                    isPlaying = true,
                                                )
                                                reconcileHaProgressOverlayTicker(player, true)
                                            }
                                        } else if (!isPlaying) {
                                            cancelHideJob()
                                        }
                                        VinylCoverService.updatePlaybackState(
                                            this@VoiceSatelliteService,
                                            isPlaying = isPlaying,
                                            isSendspinSource = false,
                                        )
                                        pushHaOverlayProgress(player)
                                    }
                                    cachedHaPlaybackState = isPlaying
                                }
                            }
                        }
                    
                        return VoiceSatellite(
                            coroutineContext = lifecycleScope.coroutineContext,
                            name = satelliteSettings.name,
                            port = satelliteSettings.serverPort,
                            audioInput = audioInput,
                            player = player,
                            voiceChannelEnabled = voiceChannelEnabled,
                            settingsStore = satelliteSettingsStore,
                            notificationSettingsStore = notificationSettingsStore,
                            experimentalSettingsStore = experimentalSettingsStore,
                            microphoneSettingsStore = microphoneSettingsStore,
                            playerSettingsStore = playerSettingsStore,
                            browserSettingsData = browserSettingsData,
                            experimentalSettingsData = experimentalSettingsData,
                            screensaverSettingsData = screensaverSettingsData,
                            playerSettingsData = playerSettings,
                            onRestartService = {
                                restartVoiceSatellite(SatelliteRestartReason.HA_RESTART_ENTITY)
                            },
                            onWakeWordEngineChanged = { engine -> applyWakeWordEngineChange(engine) },
                            context = this@VoiceSatelliteService,
                            encryptionKey = satelliteSettings.encryptionKey,
                            deviceMacAddress = satelliteSettings.macAddress,
                        ).apply {
                            isSendspinProtocolActive = {
                                sendspinManager?.isActive?.value == true
                            }
                            val expSettings = experimentalSettingsStore.get()
                            multiDeviceArbiterEnabled = expSettings.multiDeviceArbiterEnabled
                            syncChorusWakeArbiter(expSettings.multiDeviceArbiterEnabled)
                            
                            if (microphoneSettings.wakeWordSensitivity1 > 0 && savedWakeWords.isNotEmpty()) {
                                audioInput.updateWakeWordSensitivity(savedWakeWords[0], microphoneSettings.wakeWordSensitivity1)
                            }
                            if (microphoneSettings.wakeWordSensitivity2 > 0 && savedWakeWords.size > 1) {
                                audioInput.updateWakeWordSensitivity(savedWakeWords[1], microphoneSettings.wakeWordSensitivity2)
                            }
                            if (microphoneSettings.wakeWordExtraStrictness1 > 0 && savedWakeWords.isNotEmpty()) {
                                audioInput.updateWakeWordExtraStrictness(savedWakeWords[0], microphoneSettings.wakeWordExtraStrictness1)
                            }
                            if (microphoneSettings.wakeWordExtraStrictness2 > 0 && savedWakeWords.size > 1) {
                                audioInput.updateWakeWordExtraStrictness(savedWakeWords[1], microphoneSettings.wakeWordExtraStrictness2)
                            }
                            if (microphoneSettings.stopWordSensitivity > 0 && resolvedStopWakeWordId != null) {
                                audioInput.updateWakeWordSensitivity(resolvedStopWakeWordId, microphoneSettings.stopWordSensitivity)
                            }

                            onConversationText = { role, text ->
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    if (!syncOverlayPermissionState()) return@launch
                                    if (playerSettingsStore.enableFloatingWindow.get() && role == "assistant") {
                                        FloatingWindowService.showAssistantText(this@VoiceSatelliteService, text)
                                    }
                                }
                            }
                            
                            onVoiceOverlayDuck = {
                                beginVoiceOverlayDuck()
                            }

                            onListeningStarted = { accentColor ->
                                // Fallback if RUN_START arrives without wakeSatellite duck
                                // (already latched + fading is a no-op).
                                beginVoiceOverlayDuck()
                                dropSpeakingGlow()
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    if (isQuickWakeSessionActive()) return@launch
                                    if (playerSettingsStore.enableFloatingWindow.get()) {
                                        if (!syncOverlayPermissionState()) return@launch
                                        FloatingWindowService.showListening(this@VoiceSatelliteService)
                                        // Edge glow already up (speaking glow of the last
                                        // reply): move it to listening too, or it stays on
                                        // SPEAKING through the continue turn.
                                        if (WakeRippleService.isStateOverlayShowing()) {
                                            val wakeIndex = _voiceSatellite.value?.sessionAccentWakeIndex() ?: 0
                                            WakeRippleService.showListening(
                                                this@VoiceSatelliteService,
                                                accentColor,
                                                wakeIndex,
                                            )
                                        }
                                    } else {
                                        val wakeIndex = _voiceSatellite.value?.sessionAccentWakeIndex() ?: 0
                                        WakeRippleService.showListening(
                                            this@VoiceSatelliteService,
                                            accentColor,
                                            wakeIndex,
                                        )
                                    }
                                }
                            }
                            
                            onProcessingStarted = {
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    if (isQuickWakeSessionActive()) return@launch
                                    if (playerSettingsStore.enableFloatingWindow.get()) {
                                        if (!syncOverlayPermissionState()) return@launch
                                        FloatingWindowService.showProcessing(this@VoiceSatelliteService)
                                        // Same as listening: keep a live edge glow in step.
                                        if (WakeRippleService.isStateOverlayShowing()) {
                                            WakeRippleService.showProcessing(this@VoiceSatelliteService)
                                        }
                                    } else {
                                        WakeRippleService.showProcessing(this@VoiceSatelliteService)
                                    }
                                }
                            }
                            
                            onConversationEnd = {
                                dropSpeakingGlow()
                                com.example.ava.clock.ClockAlertSensor.onVoiceEnded()
                                // TTS overlay restore already rides duck → 1; otherwise fade out here.
                                if (voiceReplyRestoreFadeJob?.isActive != true) {
                                    endVoiceOverlayDuck()
                                }
                                _voiceSatellite.value?.publishChorusSessionEnd()
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    WakeRippleService.hide(this@VoiceSatelliteService)
                                    FloatingWindowService.hide(this@VoiceSatelliteService)
                                }
                            }
                            
                            onDeviceAction = { action ->
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    if (!syncOverlayPermissionState()) return@launch
                                    if (playerSettingsStore.enableHaSwitchOverlay.get()) {
                                        HaSwitchOverlayService.showDeviceAction(
                                            this@VoiceSatelliteService,
                                            action.type,
                                            action.isOn
                                        )
                                    }
                                }
                            }
                            
                            onTtsPlaybackStarted = { _ ->
                                QuickWakeFabService.noteTtsAudible()
                                speakingGlowJob?.cancel()
                                val glowToken = speakingGlowToken.incrementAndGet()
                                speakingGlowJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    val streamingTts =
                                        playerSettingsStore.enableStreamingTtsSubtitles.get()
                                    val floatingWindow = playerSettingsStore.enableFloatingWindow.get()
                                    // Karaoke caption needs a short lead-in. Speaking glow must
                                    // not wait on overlay permission — new users leave floating
                                    // subtitles off and often have no draw-over grant, which
                                    // used to skip showSpeaking entirely and freeze TTS level.
                                    if (!streamingTts && floatingWindow && syncOverlayPermissionState()) {
                                        kotlinx.coroutines.delay(500)
                                    }
                                    // Audio already ended / next listen / session over.
                                    if (speakingGlowToken.get() != glowToken) return@launch
                                    // Pass accent explicitly so speaking glow stays correct when
                                    // wake ripple was skipped (ripple disabled / no prior overlay).
                                    val accent = _voiceSatellite.value?.currentSessionAccentColor
                                        ?: com.example.ava.ui.VoiceAccentColors.WAKE_WORD_1
                                    val wakeIndex = _voiceSatellite.value?.sessionAccentWakeIndex() ?: 0
                                    WakeRippleService.showSpeaking(
                                        this@VoiceSatelliteService,
                                        accent,
                                        wakeIndex,
                                    )
                                }
                            }

                            // One "TTS audio really ended" point (URL end, PCM drain, any
                            // stop, and first thing in onTtsFinished). Clears the speaking
                            // level on the edge glow and the Quick Wake disc right then,
                            // not when the continue decision finally moves the state.
                            onTtsAudioEnded = {
                                dropSpeakingGlow()
                                QuickWakeFabService.noteTtsAudioEnded()
                                FloatingWindowService.noteTtsAudioEnded()
                                WakeRippleService.endSpeakingAudio(this@VoiceSatelliteService)
                            }

                            onTtsDurationReady = { durationMs, text ->
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    if (!syncOverlayPermissionState()) return@launch
                                    // Classic URL often starts with duration=0; still show full
                                    // caption immediately. Late duration (>0) updates paging clock.
                                    if (playerSettingsStore.enableFloatingWindow.get() &&
                                        text.isNotBlank()
                                    ) {
                                        FloatingWindowService.showKaraokeText(
                                            this@VoiceSatelliteService,
                                            text,
                                            durationMs.coerceAtLeast(0L)
                                        )
                                    }
                                }
                            }
                            
                            onTtsProgressUpdate = { currentMs, totalMs, text ->
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    if (!syncOverlayPermissionState()) return@launch
                                    if (playerSettingsStore.enableFloatingWindow.get()) {
                                        FloatingWindowService.updateKaraokeProgress(
                                            this@VoiceSatelliteService,
                                            currentMs,
                                            totalMs
                                        )
                                    }
                                }
                            }
                            
                        }
                    }
                    
                    private fun updateNotificationOnStateChanges() {
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            _voiceSatellite
                                .flatMapLatest {
                                    it?.state ?: emptyFlow()
                                }
                                .onEach {
                                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
                                        2,
                                        createVoiceSatelliteServiceNotification(
                                            this@VoiceSatelliteService,
                                            it.translate(resources)
                                        )
                                    )
                                }.collect {}
                        }
                    }
    private suspend fun waitForAudioFramework() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxWaitMs = 15000L
        val checkIntervalMs = 500L
        var waited = 0L
        
        while (waited < maxWaitMs) {
            try {
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (maxVolume > 0 && currentVolume >= 0) {
                    Log.d(TAG, "Audio framework ready after ${waited}ms (maxVol=$maxVolume, curVol=$currentVolume)")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio framework not ready: ${e.message}")
            }
            kotlinx.coroutines.delay(checkIntervalMs)
            waited += checkIntervalMs
        }
        Log.w(TAG, "Audio framework wait timeout after ${maxWaitMs}ms, proceeding anyway")
    }

    fun createAudioPlayer(
        usage: Int,
        contentType: Int,
        focusGain: Int,
        tapPlaybackEnergy: Boolean = false,
        httpStreamingTts: Boolean = false,
        enableMusicEq: Boolean = false,
        handleAudioFocus: Boolean = (usage == USAGE_MEDIA),
    ): AudioPlayer {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
        // Filled after construction; lets the AEC reference tee read the current app
        // volume (incl. duck ramps) which ExoPlayer applies downstream of the tee.
        val playerForVolume = java.util.concurrent.atomic.AtomicReference<AudioPlayer?>(null)
        val audioPlayer = AudioPlayer(audioManager, focusGain) {
            val dataSourceFactory = if (httpStreamingTts) {
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(TTS_HTTP_STREAM_TIMEOUT_MS)
                    .setReadTimeoutMs(TTS_HTTP_STREAM_TIMEOUT_MS)
                DefaultDataSource.Factory(this@VoiceSatelliteService, httpFactory)
            } else {
                DefaultDataSource.Factory(this@VoiceSatelliteService)
            }
            val loadControl = if (httpStreamingTts) {
                // Low startup latency for streaming TTS: begin playback after only a small
                // amount is buffered instead of 2.5s, so streamed/progressive TTS is not
                // slower than the non-streaming path. The large min/max keep buffering ahead.
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        15_000,
                        30_000,
                        TTS_STREAM_START_BUFFER_MS,
                        TTS_STREAM_REBUFFER_MS,
                    )
                    .build()
            } else {
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5_000, 30_000, 250, 1_000)
                    .build()
            }
            ExoPlayer.Builder(
                this@VoiceSatelliteService,
                buildAecTeeRenderersFactory(
                    tapPlaybackEnergy,
                    aecRefVolumeProvider = { playerForVolume.get()?.volume ?: 1f },
                    enableMusicEq = enableMusicEq,
                ),
            )
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        dataSourceFactory,
                        com.example.ava.players.ContainerExtractorsFactory(),
                    ).setLoadErrorHandlingPolicy(com.example.ava.players.FfmpegProxyLoadPolicy())
                )
                .setLoadControl(loadControl)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build(),
                    handleAudioFocus
                ).build()
        }
        playerForVolume.set(audioPlayer)
        return audioPlayer
    }

    /** Renderers factory whose audio sink tees PCM into [PlaybackReferenceBus] for software AEC. */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun buildAecTeeRenderersFactory(
        tapPlaybackEnergy: Boolean = false,
        aecRefVolumeProvider: (() -> Float)? = null,
        enableMusicEq: Boolean = false,
    ): androidx.media3.exoplayer.DefaultRenderersFactory =
        object : androidx.media3.exoplayer.DefaultRenderersFactory(this@VoiceSatelliteService) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                val processors = buildList {
                    // EQ before AEC tee so far-end reference matches speaker tone.
                    if (enableMusicEq) {
                        add(com.example.ava.audio.eq.EqualizerAudioProcessor())
                    }
                    add(
                        androidx.media3.exoplayer.audio.TeeAudioProcessor(
                            com.example.ava.audio.PlaybackReferenceTee(aecRefVolumeProvider)
                        )
                    )
                    if (tapPlaybackEnergy) {
                        add(
                            androidx.media3.exoplayer.audio.TeeAudioProcessor(
                                com.example.ava.audio.PlaybackEnergyTee()
                            )
                        )
                    }
                }
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(if (tapPlaybackEnergy) false else enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(processors.toTypedArray())
                    .build()
            }
        }
    

    // Note: an OS conflict rename ("name (2)") is deliberately NOT persisted here.
    // NsdRegistration reclaims the requested name by itself; writing the renamed
    // value back to settings turned a transient mDNS collision into permanent
    // identity and stacked a suffix per restart (issue #201).
    private fun registerVoiceSatelliteNsd(settings: VoiceSatelliteSettings, port: Int) =
        registerVoiceSatelliteNsd(
            context = this@VoiceSatelliteService,
            name = settings.name,
            port = port,
            macAddress = settings.macAddress,
            encryptionEnabled = settings.encryptionKey.isNotBlank(),
        )

    private suspend fun awaitApiServerListening(
        satellite: VoiceSatellite,
        timeoutMs: Long = 3_000L,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (satellite.isApiServerListening()) return
            kotlinx.coroutines.delay(10)
        }
    }

    /**
     * Prefer the socket's real bound port so NSD matches TCP even if settings briefly
     * disagreed (e.g. legacy port 0 → ephemeral bind). Fall back to settings when
     * the server is not up yet.
     */
    private fun resolveNsdPort(settings: VoiceSatelliteSettings): Int? {
        val listening = _voiceSatellite.value?.getApiServerListeningPort()
        if (listening != null && listening in 1..65535) {
            if (listening != settings.serverPort) {
                Log.w(
                    TAG,
                    "NSD using listening port $listening (settings.serverPort=${settings.serverPort})",
                )
            }
            return listening
        }
        if (settings.serverPort in 1..65535) return settings.serverPort
        Log.e(
            TAG,
            "Skip NSD registration: invalid port (settings=${settings.serverPort}, listening=$listening)",
        )
        return null
    }

    private fun ensureVoiceSatelliteNsdRegistered(settings: VoiceSatelliteSettings) {
        val port = resolveNsdPort(settings) ?: return
        val current = voiceSatelliteNsd.get()
        if (current != null && current.matches(settings.name, port)) {
            when {
                current.isRegistered() -> return
                current.isRegistrationInFlight() -> return
                else -> {
                    Log.w(TAG, "NSD stale for ${settings.name}:$port, re-registering")
                    current.register(this)
                    return
                }
            }
        }
        current?.unregister(this)
        voiceSatelliteNsd.set(registerVoiceSatelliteNsd(settings, port))
    }

    private fun unregisterVoiceSatelliteNsd() {
        voiceSatelliteNsd.getAndSet(null)?.unregister(this)
    }

    /**
     * While the satellite is up but Home Assistant has no client connected,
     * periodically cycle the mDNS advertisement. HA's reconnect logic retries
     * immediately when any mDNS record for this device arrives, but between
     * attempts it backs off up to 60s — and Android only transmits records on
     * (re)registration, so after a quick device reboot (records still in HA's
     * zeroconf cache) there is nothing on the wire to wake it earlier. Always
     * on; quiesces itself the moment HA connects or the satellite stops.
     */
    private fun startNsdReannounceLifecycle() {
        nsdReannounceJob?.cancel()
        nsdReannounceJob = _voiceSatellite
            .flatMapLatest { satellite -> satellite?.state ?: flowOf(Stopped) }
            .distinctUntilChanged()
            .flatMapLatest { state ->
                if (state is Disconnected) nsdReannounceTicks() else emptyFlow()
            }
            .onEach { reannounceVoiceSatelliteNsd() }
            .launchIn(lifecycleScope)
    }

    /** Fast cadence while the disconnect is fresh (device/HA reboot), then settles. */
    private fun nsdReannounceTicks() = flow {
        delay(NSD_REANNOUNCE_INITIAL_DELAY_MS)
        var tick = 0
        while (true) {
            emit(tick)
            tick++
            delay(
                if (tick < NSD_REANNOUNCE_FAST_TICKS) NSD_REANNOUNCE_FAST_INTERVAL_MS
                else NSD_REANNOUNCE_SLOW_INTERVAL_MS
            )
        }
    }

    private suspend fun reannounceVoiceSatelliteNsd() {
        val settings = satelliteSettingsStore.get()
        // Heals a missing/stale/mismatched registration first; no-ops when healthy.
        ensureVoiceSatelliteNsdRegistered(settings)
        val current = voiceSatelliteNsd.get() ?: return
        if (!current.isRegistered() || current.isRegistrationInFlight()) return
        Log.i(
            TAG,
            "HA not connected; re-announcing NSD '${current.name}' so HA can reconnect without backoff",
        )
        current.reannounce()
    }
    
    
    private fun startVolumeSyncLifecycle() {
        volumeSyncStateJob?.cancel()
        volumeSyncStateJob = _voiceSatellite
            .flatMapLatest { satellite -> satellite?.state ?: flowOf(Disconnected) }
            .distinctUntilChanged()
            .onEach { state ->
                when (state) {
                    is Connected -> reconcileDeviceVolumeSync(_voiceSatellite.value)
                    is Disconnected, is Stopped -> stopVolumeSyncObserver()
                    else -> Unit
                }
            }
            .launchIn(lifecycleScope)
    }

    private suspend fun reconcileDeviceVolumeSync(
        satellite: VoiceSatellite? = _voiceSatellite.value,
    ) {
        val sendspinSettings = sendspinSettingsStore.get()
        val sendspinEnabled = sendspinSettings.enabled
        val followRule = VolumeFollowRule.fromSettings(sendspinSettings)
        val exposeEsphomePlayer = playerSettingsStore.get().exposeEsphomeMediaPlayerEntity
        val haRemoteConfigured = satelliteSettingsStore.get().haMediaPlayerEntity.isNotEmpty()
        val connected = satellite?.state?.value is Connected
        // Device-volume mode: always watch STREAM_MUSIC for MA client/state when Sendspin is on.
        // Device → HA mirror only when Follow device is selected.
        val haMirror = followRule.mirrorsDeviceToHa && (exposeEsphomePlayer || haRemoteConfigured)
        val followHa = followRule.mirrorsHaToDevice && haRemoteConfigured
        val shouldMonitor = sendspinEnabled || haMirror || followHa
        if (shouldMonitor) {
            startVolumeSyncObserver()
            if (connected && haMirror) {
                seedDeviceVolumeToPlayer()
            }
            // Follow HA applies on live volume_level updates only — do not seed from the
            // default haVolumeLevel (1.0) before Home Assistant has published a real value.
        } else {
            stopVolumeSyncObserver()
        }
    }

    fun dispatchHardwareMusicVolumeChanged() {
        lifecycleScope.launch {
            val level = deviceMusicVolumeMonitor?.currentNormalizedLevel()
                ?: com.example.ava.utils.DeviceMusicVolumeMonitor.readNormalizedLevel(applicationContext)
            onDeviceMusicVolumeChanged(level)
        }
    }

    private fun startVolumeSyncObserver() {
        if (deviceMusicVolumeMonitor != null) return
        val monitor = com.example.ava.utils.DeviceMusicVolumeMonitor(
            applicationContext,
            onMusicVolumeChanged = { level -> onDeviceMusicVolumeChanged(level) },
        )
        monitor.start()
        deviceMusicVolumeMonitor = monitor
        Log.d(TAG, "Device volume sync observer started")
    }

    private fun onDeviceMusicVolumeChanged(level: Float) {
        if (voiceReplyStreamOverlayActive) {
            val overlay = overlayStreamMusicLevel
            if (overlay != null && abs(level - overlay) < 0.02f) {
                return
            }
            queuedStreamMusicLevel = level
            if (voiceReplyRestoreFadeJob?.isActive == true) {
                cancelVoiceReplyRestoreFadeAndCommit()
                return
            }
            if (overlay != null) {
                writeStreamMusicSilently(overlay)
            }
            return
        }
        // Speaker level just changed outside the AEC reference path (device mixer
        // gain): flag it so AEC3 re-adapts instead of leaking echo.
        com.example.ava.audio.PlaybackReferenceBus.noteLevelChange()
        lifecycleScope.launch {
            // Always report to MA when Sendspin is active (device-volume = STREAM_MUSIC).
            sendspinManager?.onHardwareMusicVolumeChanged(level)
            if (VolumeFollowRule.fromSettings(sendspinSettingsStore.get()).mirrorsDeviceToHa) {
                applyDeviceVolumeLevelToPlayer(level)
            } else {
                sendspinManager?.refreshOutputRouting()
            }
        }
    }

    private suspend fun applyDeviceVolumeLevelToPlayer(level: Float = deviceMusicVolumeMonitor?.currentNormalizedLevel()
        ?: com.example.ava.utils.DeviceMusicVolumeMonitor.readNormalizedLevel(applicationContext)) {
        if (!VolumeFollowRule.fromSettings(sendspinSettingsStore.get()).mirrorsDeviceToHa) return
        suppressSendspinVolumeUpdate = true
        // Device STREAM_MUSIC → VoiceSatellitePlayer.volume → ESPHome media_player entity.
        // Must not loop into sendspinManager.updateVolume (that rewrites STREAM_MUSIC).
        _voiceSatellite.value?.player?.onSystemVolumeChanged(level)
        val haRemoteConfigured = satelliteSettingsStore.get().haMediaPlayerEntity.isNotEmpty()
        if (haRemoteConfigured) {
            _voiceSatellite.value?.player?.haSetVolume(level)
        }
        sendspinManager?.refreshOutputRouting()
    }

    private suspend fun seedDeviceVolumeToPlayer() {
        applyDeviceVolumeLevelToPlayer()
    }

    /**
     * Home Assistant media-player volume_level → Android STREAM_MUSIC (Follow HA).
     * Suppresses the device volume observer so we do not bounce back as Follow device.
     */
    private fun applyHaVolumeLevelToDevice(level: Float) {
        if (voiceReplyStreamOverlayActive) {
            queuedStreamMusicLevel = level.coerceIn(0f, 1f)
            if (voiceReplyRestoreFadeJob?.isActive == true) {
                cancelVoiceReplyRestoreFadeAndCommit()
            }
            return
        }
        val clamped = level.coerceIn(0f, 1f)
        val manager = sendspinManager
        if (manager != null) {
            manager.updateVolume(clamped)
            return
        }
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume > 0) {
            val target = ((clamped * maxVolume) + 0.5f).toInt().coerceIn(0, maxVolume)
            deviceMusicVolumeMonitor?.suppressNextCallbacks(4)
            deviceMusicVolumeMonitor?.suppressForMs(1000L)
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != target) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
        }
        suppressSendspinVolumeUpdate = true
        _voiceSatellite.value?.player?.onSystemVolumeChanged(clamped)
    }

    /**
     * User set system volume from Voice replies. During a TTS overlay this is
     * queued as the post-speech restore target; otherwise it writes STREAM_MUSIC
     * like a volume key.
     */
    private fun applyUserMediaVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        if (voiceReplyRestoreFadeJob?.isActive == true) {
            queuedStreamMusicLevel = clamped
            cancelVoiceReplyRestoreFadeAndCommit()
            return
        }
        if (voiceReplyStreamOverlayActive) {
            queuedStreamMusicLevel = clamped
            return
        }
        com.example.ava.utils.DeviceMusicVolumeMonitor.writeNormalizedLevel(this, clamped)
    }

    private fun applyLiveVoiceReplyVolume(level: Float, autoGain: Boolean? = null) {
        val clamped = level.coerceIn(
            com.example.ava.settings.PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME,
            1f,
        )
        cancelVoiceReplyRestoreFadeAndCommit()
        val apply = {
            _voiceSatellite.value?.applyLiveVoiceReplyVolume(clamped, autoGain)
            Unit
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            apply()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(apply)
        }
    }

    /**
     * Voice replies: snapshot STREAM_MUSIC, write the nearest device step for the
     * TTS level, apply leftover as software gain so slider/HA changes are live
     * during speech. Volume-key changes during the overlay are queued and
     * applied on restore. User / HA writes stay instant (no restore envelope).
     */
    private fun applyVoiceReplyStreamVolume(level: Float) {
        if (com.example.ava.mods.ModAudioRouter.isActive(applicationContext)) {
            // A router mod owns TTS loudness via its own routed-usage device volume, so the
            // STREAM_MUSIC voice-reply overlay must not run — it would drag media volume.
            return
        }
        cancelVoiceReplyRestoreFadeAndCommit()
        val clamped = level.coerceIn(0f, 1f)
        if (!voiceReplyStreamOverlayActive) {
            voiceReplyStreamOverlayActive = true
            savedStreamMusicLevel = deviceMusicVolumeMonitor?.currentNormalizedLevel()
                ?: com.example.ava.utils.DeviceMusicVolumeMonitor.readNormalizedLevel(applicationContext)
            queuedStreamMusicLevel = null
        }
        overlayHeardLevel = clamped
        val stream = quantizedStreamMusicLevel(clamped)
        overlayStreamMusicLevel = stream
        writeStreamMusicSilently(stream)
        applyVoiceReplySoftwareGain(
            if (stream > 0f) (clamped / stream).coerceIn(0f, 1f) else clamped,
        )
    }

    /** Nearest STREAM_MUSIC step as a 0–1 level. Matches [writeStreamMusicSilently]. */
    private fun quantizedStreamMusicLevel(level: Float): Float {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return level.coerceIn(0f, 1f)
        val target = ((level.coerceIn(0f, 1f) * maxVolume) + 0.5f).toInt().coerceIn(0, maxVolume)
        return target.toFloat() / maxVolume.toFloat()
    }

    private fun streamMusicStep(level: Float): Int {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return 0
        return ((level.coerceIn(0f, 1f) * maxVolume) + 0.5f).toInt().coerceIn(0, maxVolume)
    }

    /**
     * System restore only. Big jump (≥4 steps or ≥8 dB) snaps like Android.
     * Small / mid steps ease in dB at 40 dB/s (×1.2 going up), 16–500ms.
     */
    private fun isVoiceReplyRestoreBigJump(from: Float, to: Float): Boolean {
        val floor = com.example.ava.settings.PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME
        val dbFrom = from.coerceAtLeast(floor)
        val dbTo = to.coerceAtLeast(floor)
        val deltaDb = abs(20f * log10((dbTo / dbFrom).toDouble()).toFloat())
        return abs(streamMusicStep(from) - streamMusicStep(to)) >= 4 || deltaDb >= 8f
    }

    private fun voiceReplyRestoreDurationMs(from: Float, to: Float): Float {
        val floor = com.example.ava.settings.PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME
        val dbFrom = from.coerceAtLeast(floor)
        val dbTo = to.coerceAtLeast(floor)
        val deltaDb = abs(20f * log10((dbTo / dbFrom).toDouble()).toFloat())
        var durationMs = deltaDb / 40f * 1000f
        if (to > from) durationMs *= 1.2f
        return durationMs.coerceIn(16f, 500f)
    }

    /**
     * Sendspin / Mass overlay: latch + exponential PCM envelope.
     * Button STT calls this at wake (same instant as the local player duck), not at RUN_START.
     */
    private fun beginVoiceOverlayDuck() {
        com.example.ava.massapi.MassApiManager.get()?.duck()
        val floor = com.example.ava.sendspin.SendspinManager.VOICE_OVERLAY_DUCK_LINEAR
        fadeVoiceOverlayDuck(target = floor, committingUnduck = false)
    }

    private fun endVoiceOverlayDuck() {
        fadeVoiceOverlayDuck(target = 1f, committingUnduck = true)
    }

    private fun cancelVoiceOverlayDuckFade() {
        val job = voiceOverlayDuckFadeJob ?: return
        if (job.isActive) job.cancel()
        voiceOverlayDuckFadeJob = null
        voiceOverlayDuckFadingOut = false
    }

    private fun voiceOverlayDuckDurationMs(from: Float, to: Float): Float {
        val floor = 0.05f
        val dbFrom = from.coerceAtLeast(floor)
        val dbTo = to.coerceAtLeast(floor)
        val deltaDb = abs(20f * log10((dbTo / dbFrom).toDouble()).toFloat())
        var durationMs = deltaDb / 40f * 1000f
        return if (to > from) {
            (durationMs * 1.25f).coerceIn(400f, 600f)
        } else {
            durationMs.coerceIn(280f, 380f)
        }
    }

    private fun fadeVoiceOverlayDuck(target: Float, committingUnduck: Boolean) {
        val sp = sendspinManager
        if (sp == null || sp.isActive.value != true) {
            cancelVoiceOverlayDuckFade()
            if (committingUnduck) {
                sp?.unDuck()
                com.example.ava.massapi.MassApiManager.get()?.unDuck()
            } else {
                sp?.duck()
            }
            return
        }
        if (!committingUnduck &&
            voiceOverlayDuckFadeJob?.isActive == true &&
            !voiceOverlayDuckFadingOut &&
            sp.isVoiceOverlayDucked()
        ) {
            return
        }
        if (committingUnduck && !sp.isVoiceOverlayDucked()) {
            cancelVoiceOverlayDuckFade()
            com.example.ava.massapi.MassApiManager.get()?.unDuck()
            return
        }
        if (voiceReplyRestoreFadeJob?.isActive == true && committingUnduck) {
            // TTS restore already owns the lift + unDuck.
            return
        }
        if (!committingUnduck && voiceReplyRestoreFadeJob?.isActive == true) {
            cancelVoiceReplyRestoreFadeAndCommit()
        }
        cancelVoiceOverlayDuckFade()
        val from = sp.currentDuckLinear()
        if (!committingUnduck) {
            sp.duck(holdLinear = from)
        }
        if (abs(from - target) < 0.01f) {
            if (committingUnduck) {
                sp.unDuck()
                com.example.ava.massapi.MassApiManager.get()?.unDuck()
            } else {
                sp.setDuckLinearOverride(null)
            }
            return
        }
        voiceOverlayDuckFadingOut = committingUnduck
        voiceOverlayDuckFadeJob = lifecycleScope.launch {
            val durationMs = voiceOverlayDuckDurationMs(from, target)
            val frames = (durationMs / 16f).coerceAtLeast(1f)
            val k = 1f - 0.05f.pow(1f / frames)
            var level = from
            while (coroutineContext.isActive) {
                level += (target - level) * k
                sp.setDuckLinearOverride(level)
                if (abs(level - target) < 0.008f) break
                delay(16)
            }
            if (!coroutineContext.isActive) return@launch
            if (committingUnduck) {
                sp.unDuck()
                com.example.ava.massapi.MassApiManager.get()?.unDuck()
            } else {
                sp.setDuckLinearOverride(null)
            }
            voiceOverlayDuckFadeJob = null
            voiceOverlayDuckFadingOut = false
        }
    }

    private fun restoreVoiceReplyStreamVolume(forceSnap: Boolean = false) {
        if (com.example.ava.mods.ModAudioRouter.isActive(applicationContext)) {
            // Overlay never ran (see applyVoiceReplyStreamVolume); nothing to restore.
            return
        }
        if (forceSnap) {
            cancelVoiceReplyRestoreFadeAndCommit()
            if (!voiceReplyStreamOverlayActive) return
        } else if (!voiceReplyStreamOverlayActive) {
            return
        } else if (voiceReplyRestoreFadeJob?.isActive == true) {
            return
        }
        val queued = queuedStreamMusicLevel
        val restore = (queued ?: savedStreamMusicLevel)?.coerceIn(0f, 1f)
        val from = (overlayHeardLevel ?: overlayStreamMusicLevel)?.coerceIn(0f, 1f)
        if (restore == null) {
            clearVoiceReplyOverlayBookkeeping()
            return
        }
        val sameStep = from != null &&
            streamMusicStep(from) == streamMusicStep(restore) &&
            abs(from - restore) < 0.02f
        if (forceSnap || from == null || sameStep || isVoiceReplyRestoreBigJump(from, restore)) {
            commitVoiceReplyStreamRestore(restore, queued != null)
            return
        }
        voiceReplyRestoreFadeJob?.cancel()
        voiceReplyRestoreFadeJob = lifecycleScope.launch {
            runVoiceReplyRestoreFade(from, restore, queued != null)
        }
    }

    private suspend fun runVoiceReplyRestoreFade(from: Float, to: Float, hadQueued: Boolean) {
        val streamHold = max(quantizedStreamMusicLevel(from), quantizedStreamMusicLevel(to))
        overlayStreamMusicLevel = streamHold
        writeStreamMusicSilently(streamHold)
        applyVoiceReplySoftwareGain(
            if (streamHold > 0f) (from / streamHold).coerceIn(0f, 1f) else from,
        )
        cancelVoiceOverlayDuckFade()
        val ducked = sendspinManager?.isVoiceOverlayDucked() == true
        val duckStart = sendspinManager?.currentDuckLinear()
            ?: com.example.ava.sendspin.SendspinManager.VOICE_OVERLAY_DUCK_LINEAR
        if (ducked) sendspinManager?.setDuckLinearOverride(duckStart)
        val durationMs = voiceReplyRestoreDurationMs(from, to)
        val frames = (durationMs / 16f).coerceAtLeast(1f)
        val k = 1f - 0.05f.pow(1f / frames)
        var heard = from
        var duck = duckStart
        while (coroutineContext.isActive) {
            heard += (to - heard) * k
            applyVoiceReplySoftwareGain(
                if (streamHold > 0f) (heard / streamHold).coerceIn(0f, 1f) else heard,
            )
            if (ducked) {
                duck += (1f - duck) * k
                sendspinManager?.setDuckLinearOverride(duck)
            }
            if (abs(heard - to) < 0.004f && (!ducked || abs(duck - 1f) < 0.01f)) break
            delay(16)
        }
        if (!coroutineContext.isActive) return
        voiceReplyRestoreFadeJob = null
        commitVoiceReplyStreamRestore(to, hadQueued)
    }

    private fun cancelVoiceReplyRestoreFadeAndCommit() {
        val job = voiceReplyRestoreFadeJob ?: return
        if (!job.isActive) {
            voiceReplyRestoreFadeJob = null
            return
        }
        job.cancel()
        voiceReplyRestoreFadeJob = null
        if (!voiceReplyStreamOverlayActive) return
        val queued = queuedStreamMusicLevel
        val restore = (queued ?: savedStreamMusicLevel)?.coerceIn(0f, 1f)
        if (restore == null) {
            clearVoiceReplyOverlayBookkeeping()
            return
        }
        commitVoiceReplyStreamRestore(restore, queued != null)
    }

    private fun commitVoiceReplyStreamRestore(restore: Float, hadQueued: Boolean) {
        clearVoiceReplyOverlayBookkeeping()
        writeStreamMusicSilently(restore)
        sendspinManager?.setDuckLinearOverride(null)
        sendspinManager?.unDuck()
        com.example.ava.massapi.MassApiManager.get()?.unDuck()
        val player = _voiceSatellite.value?.player
        if (player != null && !player.muted.value) {
            player.setVolume(player.currentPlaybackVolume())
        }
        _voiceSatellite.value?.setPcmTtsVolume(
            if (player?.muted?.value == true) 0f else player?.ttsOutputVolume() ?: 1f,
        )
        if (hadQueued) {
            sendspinManager?.onHardwareMusicVolumeChanged(restore)
        }
        if (VolumeFollowRule.fromSettings(sendspinSettingsStore.getCached()).mirrorsDeviceToHa) {
            lifecycleScope.launch {
                applyDeviceVolumeLevelToPlayer(restore)
            }
        }
    }

    private fun clearVoiceReplyOverlayBookkeeping() {
        voiceReplyStreamOverlayActive = false
        savedStreamMusicLevel = null
        queuedStreamMusicLevel = null
        overlayStreamMusicLevel = null
        overlayHeardLevel = null
    }

    private fun applyVoiceReplySoftwareGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        val satellite = _voiceSatellite.value
        satellite?.player?.setOverlaySoftwareGain(clamped)
        satellite?.setPcmTtsVolume(if (satellite.player.muted.value) 0f else clamped)
    }

    private fun writeStreamMusicSilently(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume > 0) {
            val target = ((clamped * maxVolume) + 0.5f).toInt().coerceIn(0, maxVolume)
            deviceMusicVolumeMonitor?.suppressNextCallbacks(4)
            deviceMusicVolumeMonitor?.suppressForMs(1200L)
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != target) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                com.example.ava.audio.PlaybackReferenceBus.noteLevelChange()
            }
        }
        sendspinManager?.noteExternalDeviceVolume(clamped)
    }

    private fun stopVolumeSyncObserver() {
        deviceMusicVolumeMonitor?.stop()
        deviceMusicVolumeMonitor = null
    }

    private fun stopA64VolumeControlService() {
        runCatching {
            stopService(Intent(this, VolumeControlService::class.java))
        }
    }

    override fun onDestroy() {
        QuickWakeFabService.hide(this)
        cancelVoiceOverlayDuckFade()
        restoreVoiceReplyStreamVolume(forceSnap = true)
        startupHandler.removeCallbacksAndMessages(null)
        stopEchoTestTone()
        // Keep wakeDetectionSuspendDepth: enrollment sheet may outlive a brief service death and
        // the next satellite create must still come up suspended.
        instance = null
        _isRunning.value = false
        publishSatelliteStarted(false)
        FreeformKeepAliveActivity.releaseMicVisible()
        // Both singletons outlive this Service: leaving these lambdas registered
        // pins the destroyed instance (and its whole object graph) for process
        // life, once per restart cycle. Identity-checked so a successor instance
        // that already re-registered is never clobbered.
        registeredScenesReloadedCallback?.let { mine ->
            if (com.example.ava.notifications.NotificationScenes.onScenesReloaded === mine) {
                com.example.ava.notifications.NotificationScenes.onScenesReloaded = null
            }
        }
        registeredScenesReloadedCallback = null
        registeredPresenceAlertCallback?.let { mine ->
            val presenceManager = BluetoothPresenceManager.getInstance(this)
            if (presenceManager.presenceAlertCallback === mine) {
                presenceManager.presenceAlertCallback = null
            }
        }
        registeredPresenceAlertCallback = null
        // The onCreate collector is dying with lifecycleScope — reset the tile key here
        // so launcher-drawn cards don't freeze on the last live state.
        _serviceTileStateKey.value = "stopped"
        AvaActionWidgets.refreshAll(this)
        _voiceSatellite.getAndUpdate { null }?.also {
            it.cancelHaServiceCallsProbe()
            it.close()
        }
        unregisterVoiceSatelliteNsd()
        // System-initiated destroy must tear down Sendspin too, otherwise the socket server
        // thread, NSD broadcast, MulticastLock and audio pipeline all leak past the service.
        closeSendspin(preserveOverlayForRebind = false)
        wifiWakeLock.release()
        bluetoothWakeLock.release()
        cpuScreenOffWakeLock.release()
        
        stopVolumeSyncObserver()
        volumeSyncStateJob?.cancel()
        volumeSyncStateJob = null
        nsdReannounceJob?.cancel()
        nsdReannounceJob = null
        stopA64VolumeControlService()

        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
        
        controlReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        controlReceiver = null

        memoryTrimCallback?.let {
            try {
                unregisterComponentCallbacks(it)
            } catch (_: Exception) {
            }
        }
        memoryTrimCallback = null
        
        com.example.ava.utils.RootUtils.releaseCpuWakeLock()
        
        updateCheckHandler.removeCallbacksAndMessages(null)
        settingsWatcherJob?.cancel()
        settingsWatcherJob = null
        haRediscoverJob?.cancel()
        haRediscoverJob = null
        
        DreamClockService.hide(this)
        WeatherOverlayService.hide(this)
        QuickEntityOverlayService.hide(this)
        VoiceMessageOverlayService.hide(this)
        VoiceMessagePlaybackOverlayService.stop(this)
        ScreensaverService.hide(this)
        // Idle WebView screensaver must not outlive the master voice service.
        ScreensaverController.onHostServiceStopped()
        VinylCoverService.hide(this, force = true)
        hideVoiceSessionOverlays()
        WakeWordArbiter.stop()
        stopBrowserSubServiceDeathMonitor()
        WebViewService.destroy(this)
        com.example.ava.mods.ModManager.getInstance(this).destroyEnabledModManagers()
        
        RootHelper.removeBootScript()
        initializing.set(false)
        com.example.ava.massapi.MassApiManager.shutdown()
        com.example.ava.voice.AvaVoiceDiscovery.release(
            com.example.ava.voice.AvaVoiceDiscovery.HOLDER_PRESENCE,
        )
        super.onDestroy()
    }
    
    
    private val updateCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val UPDATE_CHECK_INTERVAL = 60 * 60 * 1000L
    private val WAKELOCK_RENEWAL_INTERVAL = 25 * 60 * 1000L

    fun refreshFirmwareUpdateEntity() {
        _voiceSatellite.value?.refreshFirmwareUpdateEntity()
    }

    private val periodicUpdateCheckRunnable = object : Runnable {
        override fun run() {
            if (_voiceSatellite.value == null) return
            lifecycleScope.launch(Dispatchers.IO) {
                runPeriodicUpdateCheck()
            }
            updateCheckHandler.postDelayed(this, UPDATE_CHECK_INTERVAL)
        }
    }

    private fun startPeriodicUpdateCheck() {
        updateCheckHandler.removeCallbacks(periodicUpdateCheckRunnable)
        updateCheckHandler.postDelayed(periodicUpdateCheckRunnable, UPDATE_CHECK_INTERVAL)
    }

    private suspend fun runPeriodicUpdateCheck() {
        refreshFirmwareUpdateEntity()
        val settings = UpdateSettingsStore(applicationContext.updateSettingsStore).get()
        if (settings.ignoreUpdate || !settings.autoUpdate) return
        Log.i(TAG, "Hourly auto-update: installing latest stable if available")
        AppUpdater.performRemoteUpdate(
            applicationContext,
            force = false,
            policy = UpdateInstallPolicy.fromSettings(settings),
        )
    }
    
    private val wakeLockRenewalRunnable = object : Runnable {
        override fun run() {
            if (_voiceSatellite.value == null) return
            wifiWakeLock.renewIfNeeded()
            bluetoothWakeLock.renewIfNeeded()
            updateCheckHandler.postDelayed(this, WAKELOCK_RENEWAL_INTERVAL)
        }
    }

    private fun startWakeLockRenewal() {
        // Every satellite start calls this; without the removal each restart would leave
        // another renewal chain running (the old one revives once the satellite is back).
        updateCheckHandler.removeCallbacks(wakeLockRenewalRunnable)
        updateCheckHandler.postDelayed(wakeLockRenewalRunnable, WAKELOCK_RENEWAL_INTERVAL)
    }

    /**
     * Async, process-once: probe HA allow_service_calls once HA is actually connected
     * (plus a short settle), instead of a blind wall-clock delay that can fire while the
     * satellite is still offline. Lives on [lifecycleScope] so a satellite soft-restart
     * neither cancels nor re-arms it; the arm is released if we are torn down before probing.
     */
    private fun scheduleHaServiceCallsProbeOnce() {
        if (!haServiceProbeScheduleArmed.compareAndSet(false, true)) {
            Log.d(TAG, "HA service-call probe already scheduled this process")
            return
        }
        lifecycleScope.launch {
            var probed = false
            try {
                while (isActive && !probed) {
                    val satellite = awaitConnectedSatellite()
                    delay(HA_SERVICE_CALLS_PROBE_SETTLE_MS)
                    // Reconnect/soft-restart during the settle window ⇒ wait for the next one.
                    if (_voiceSatellite.value !== satellite) continue
                    if (satellite.state.value !is Connected) continue
                    satellite.runHaServiceCallsProbeOnce()
                    probed = true
                }
            } finally {
                if (!probed) haServiceProbeScheduleArmed.set(false)
            }
        }
        Log.d(TAG, "HA service-call probe armed (waiting for HA connection)")
    }

    /** Suspends until some satellite instance reports [Connected]. */
    private suspend fun awaitConnectedSatellite(): VoiceSatellite {
        val (satellite, _) = _voiceSatellite
            .flatMapLatest { current ->
                current?.state?.map { state -> current to state } ?: emptyFlow()
            }
            .first { (_, state) -> state is Connected }
        return satellite
    }
    
    private fun startAutoUpdateChecker() {
    }

    
    fun triggerManualWake() {
        _voiceSatellite.value?.triggerManualWake()
    }

    @Volatile
    private var lastAssistWakeMs = 0L

    /**
     * Assist/mic key from a remote or overlay. Same mapping as ACTION_WAKE:
     * [VoiceSatellite.manualWake] — panel mic, no Bluetooth capture.
     */
    fun onAssistKeyPressed() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAssistWakeMs <= 1500L) return
        lastAssistWakeMs = now
        _voiceSatellite.value?.manualWake()
    }
    
    
    fun getState(): com.example.ava.esphome.EspHomeState {
        return _voiceSatellite.value?.state?.value ?: com.example.ava.esphome.Disconnected
    }
    
    fun onScreenTouch(isTouching: Boolean) {
        _voiceSatellite.value?.onScreenTouch(isTouching)
    }
    
    suspend fun callHaService(
        service: String,
        entityId: String,
        serviceData: Map<String, Any?> = emptyMap(),
    ) {
        if (HaManager.tryCallService(this, service, entityId, serviceData)) return
        _voiceSatellite.value?.callHaServicePublic(service, entityId, serviceData)
    }
    
    fun getCachedClockWeather(): com.example.ava.weather.WeatherData? {
        return _voiceSatellite.value?.getCachedClockWeather()
    }

    fun getQuickEntityStates(): Map<String, String> {
        return _voiceSatellite.value?.getQuickEntityStateCache() ?: emptyMap()
    }
    
    fun getQuickEntityUnits(): Map<String, String> {
        return _voiceSatellite.value?.getQuickEntityUnitCache() ?: emptyMap()
    }
    
    fun getQuickEntityAttributes(): Map<String, Map<String, String>> {
        return _voiceSatellite.value?.getQuickEntityAttributeCache() ?: emptyMap()
    }

    fun getQuickEntityPictureUrls(): Map<String, String> {
        return _voiceSatellite.value?.getQuickEntityPictureUrlCache() ?: emptyMap()
    }

    fun rebindQuickEntityCameras() {
        _voiceSatellite.value?.rebindHaMediaFetchPipes()
    }
    
    suspend fun resubscribeQuickEntities() {
        _voiceSatellite.value?.subscribeQuickEntities()
        refreshHaEntityInterest()
    }

    fun resubscribeDreamClockTimer() {
        lifecycleScope.launch {
            _voiceSatellite.value?.subscribeDreamClockTimer()
            refreshHaEntityInterest()
        }
    }

    suspend fun resubscribeWidgetSensors() {
        _voiceSatellite.value?.subscribeWidgetSensors()
        refreshHaEntityInterest()
    }

    suspend fun resubscribeScreensaverStatusSlots() {
        _voiceSatellite.value?.subscribeScreensaverStatusSlots()
        refreshHaEntityInterest()
    }

    suspend fun resubscribeDawnEntitySlots() {
        _voiceSatellite.value?.subscribeDawnEntitySlots()
        refreshHaEntityInterest()
    }

    /**
     * Replay cached Dawn magazine screensaver entity/weather into the WebView without
     * requiring a full HA reconnect. Used when the Dawn page becomes ready
     * after the first HA push was dropped (service not up yet).
     */
    fun pushDawnScreensaverData(forceForecastRequest: Boolean = true) {
        _voiceSatellite.value?.pushDawnDataToScreensaver(forceForecastRequest)
    }

    fun getSceneEntityStates(): Map<String, String> =
        _voiceSatellite.value?.getSceneEntityStateCache() ?: emptyMap()

    fun getSceneEntityUnits(): Map<String, String> =
        _voiceSatellite.value?.getSceneEntityUnitCache() ?: emptyMap()

    fun getSceneEntityAttributes(): Map<String, Map<String, String>> =
        _voiceSatellite.value?.getSceneEntityAttributeCache() ?: emptyMap()

    /** Called after the notification scene list is loaded or reloaded; subscribe to newly referenced entities. */
    suspend fun resubscribeSceneEntities() {
        _voiceSatellite.value?.subscribeSceneEntities()
        refreshHaEntityInterest()
    }

    /** Recompute which entities the direct WebSocket feed should be filtered down to. */
    suspend fun refreshHaEntityInterest() {
        _voiceSatellite.value?.refreshHaEntityInterest()
    }
    
    fun triggerTimerFinished() {
        _voiceSatellite.value?.onQuickEntityTimerFinished()
    }

    fun triggerDreamClockTimerFinished(soundUri: String) {
        _voiceSatellite.value?.onDreamClockTimerFinished(soundUri)
    }
    
    fun isTimerRinging(): Boolean {
        return _voiceSatellite.value?.isTimerRinging() == true
    }
    
    fun stopTimerSound() {
        _voiceSatellite.value?.stopTimer()
    }

    fun playVoiceMessageReceiveSound() {
        lifecycleScope.launch {
            val notificationSettings = notificationSettingsStore.get()
            val soundUrl = when {
                notificationSettings.soundEnabled && notificationSettings.soundUri.isNotEmpty() ->
                    notificationSettings.soundUri
                else -> playerSettingsStore.get().wakeSound
            }
            _voiceSatellite.value?.player?.wakeSoundPlayer?.play(soundUrl) {}
        }
    }

    private fun onBluetoothPresenceAlert(
        address: String,
        wasPresent: Boolean,
        isPresent: Boolean,
        deviceName: String,
    ) {
        lifecycleScope.launch {
            val device = BluetoothPresenceManager.getInstance(this@VoiceSatelliteService)
                .trackedDevices.value[address] ?: return@launch
            val trigger = BluetoothPresenceAlertSound.normalizeTrigger(device.alertTrigger)
            val edge = when (trigger) {
                BluetoothPresenceAlertTrigger.NEARBY -> !wasPresent && isPresent
                else -> wasPresent && !isPresent
            }
            if (!edge) return@launch
            val playUri = BluetoothPresenceAlertSound.resolvePlayUri(device.alertSoundUri)
            if (playUri.isNotBlank()) {
                _voiceSatellite.value?.player?.wakeSoundPlayer?.play(playUri) {}
            }
        }
    }

    /** Play the prompt sound configured in the scene JSON (asset / http). */
    fun playSceneNotificationSound(uri: String) {
        if (uri.isBlank()) return
        _voiceSatellite.value?.player?.wakeSoundPlayer?.play(uri) {}
    }

    fun syncBrowserUrlFromWebView(url: String) {
        syncBrowserUrlFromWebView(url, pane = WebViewService.BrowserPane.LEFT)
    }

    fun syncBrowserUrlFromWebView(url: String, pane: WebViewService.BrowserPane) {
        if (url.isBlank()) return
        // A collapsed / staggered split tile parks on about:blank. Persisting that would
        // wipe the dashboard address the user configured for this pane.
        if (WebViewService.isPlaceholderPageUrl(url)) {
            Log.w(TAG, "Refuse to sync placeholder URL to HA remote entity: $url")
            return
        }
        // Belt-and-suspenders: never persist loopback even if a caller forgot to unmap.
        val real = com.example.ava.webcompat.SecureContextProxy.instance.unmapUrl(url)
        if (com.example.ava.webcompat.SecureContextProxy.isLoopbackHttpUrl(real)) {
            Log.w(TAG, "Refuse to sync loopback URL to HA remote entity: $real")
            return
        }
        when (pane) {
            WebViewService.BrowserPane.LEFT ->
                _voiceSatellite.value?.player?.setHaRemoteUrl(real, notifyCallback = false)
            WebViewService.BrowserPane.RIGHT ->
                _voiceSatellite.value?.player?.setHaRemoteUrlRight(real, notifyCallback = false)
        }
    }

    /**
     * UI / overlay path for remote URL: persist + update HA entity + navigate
     * through the existing [VoiceSatellitePlayer.onHaRemoteUrlChanged] dual-sync hook.
     */
    fun applyHaRemoteUrlFromUi(url: String) {
        applyHaRemoteUrlFromUi(url, pane = WebViewService.BrowserPane.LEFT)
    }

    fun applyHaRemoteUrlFromUi(url: String, pane: WebViewService.BrowserPane) {
        if (url.isBlank()) return
        val real = com.example.ava.webcompat.SecureContextProxy.instance.unmapUrl(url)
        if (com.example.ava.webcompat.SecureContextProxy.isLoopbackHttpUrl(real)) {
            Log.w(TAG, "Refuse to apply loopback URL as HA remote: $real")
            return
        }
        when (pane) {
            WebViewService.BrowserPane.LEFT -> {
                _voiceSatellite.value?.player?.setHaRemoteUrl(real, notifyCallback = true)
                    ?: WebViewService.showOrRefreshPane(this, real, WebViewService.BrowserPane.LEFT)
            }
            WebViewService.BrowserPane.RIGHT -> {
                _voiceSatellite.value?.player?.setHaRemoteUrlRight(real, notifyCallback = true)
                    ?: WebViewService.showOrRefreshPane(this, real, WebViewService.BrowserPane.RIGHT)
            }
        }
    }

    /** Restore the browser overlay when the sub-service dies but the user left browser_display ON. */
    private fun onBrowserSubServiceUnexpectedDeath(reason: String) {
        if (suppressBrowserDeathReaction) return
        val now = SystemClock.elapsedRealtime()
        // Crash loops (libxul SIGSEGV) used to re-SHOW every 5s and keep killing gecko. Back off.
        val backoffMs = (5_000L * (1 + consecutiveBrowserDeaths.coerceAtMost(5))).coerceAtMost(30_000L)
        if (now - lastDeathReactionMs < backoffMs) {
            Log.w(TAG, "Browser death ignored (backoff ${backoffMs}ms): $reason")
            return
        }
        lastDeathReactionMs = now
        consecutiveBrowserDeaths += 1
        GeckoEngineDeathMonitor.suppressBriefly(backoffMs)
        lifecycleScope.launch {
            try {
                val settings = browserSettingsStore.get()
                com.example.ava.settings.BrowserSettingsStore.rememberEngine(
                    this@VoiceSatelliteService,
                    settings.browserEngine,
                )
                if (!settings.enableBrowserVisible) return@launch
                if (!settings.haRemoteUrlEnabled || !settings.enableBrowserDisplay) return@launch
                val voice = satelliteSettingsStore.get()
                val left = voice.haRemoteUrl
                val right = voice.haRemoteUrlRight
                val splitActive =
                    settings.splitViewEnabled &&
                        settings.browserEngine != com.example.ava.webcompat.BrowserEngine.GECKO
                if (left.isBlank() && !(splitActive && right.isNotBlank())) return@launch
                Log.w(
                    TAG,
                    "Browser sub-service died ($reason); restoring after ${backoffMs}ms backoff " +
                        "(streak=$consecutiveBrowserDeaths)"
                )
                withContext(Dispatchers.Main) {
                    if (splitActive) {
                        WebViewService.applyHaRemoteUrls(this@VoiceSatelliteService, left, right)
                    } else {
                        WebViewService.show(this@VoiceSatelliteService, left)
                    }
                }
                consecutiveBrowserDeaths = 0
                updateBrowserSubServiceDeathMonitor(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore browser after sub-service death", e)
            }
        }
    }

    /**
     * Event-driven death watch for the browser sub-service (no polling):
     * - Gecko pack: uid importance + PACKAGE_RESTARTED
     * - Local WebViewService: bindService → onServiceDisconnected
     */
    private fun updateBrowserSubServiceDeathMonitor(browserVisible: Boolean) {
        stopBrowserSubServiceDeathMonitor()

        if (!browserVisible) return

        val enginePref = com.example.ava.settings.BrowserSettingsStore.getCachedEngine(this)
        if (BrowserEngine.shouldDelegateToGeckoPack(this, enginePref)) {
            geckoEngineDeathMonitor.start(this) { reason ->
                onBrowserSubServiceUnexpectedDeath(reason)
            }
            val connection = object : ServiceConnection {
                @Volatile var connected = false
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    connected = true
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    val shouldReact = connected
                    connected = false
                    releaseBrowserServiceDeathConnection(this)
                    if (shouldReact) {
                        onBrowserSubServiceUnexpectedDeath("gecko_pack_service_disconnected")
                    }
                }

                override fun onBindingDied(name: ComponentName?) {
                    val shouldReact = connected
                    connected = false
                    releaseBrowserServiceDeathConnection(this)
                    if (shouldReact) {
                        onBrowserSubServiceUnexpectedDeath("gecko_pack_service_binding_died")
                    }
                }
            }
            // Pass a liveness Binder so the pack can linkToDeath() and reap its overlay if this
            // host process dies unexpectedly (the symmetric direction of the monitor above).
            val livenessToken = Binder()
            hostLivenessToken = livenessToken
            val intent = Intent().apply {
                component = ComponentName(
                    BrowserEngine.GECKO_ENGINE_PACKAGE,
                    "com.example.ava.services.WebViewService"
                )
                putExtra(
                    BrowserEngine.EXTRA_HOST_LIVENESS,
                    Bundle().apply {
                        putBinder(BrowserEngine.KEY_HOST_LIVENESS_TOKEN, livenessToken)
                    }
                )
            }
            val bound = try {
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind Gecko pack WebViewService death monitor", e)
                false
            }
            if (bound) {
                webViewServiceDeathConnection = connection
            } else {
                hostLivenessToken = null
                Log.w(TAG, "Gecko pack WebViewService not bindable, skipping death monitor")
            }
            return
        }

        if (EngineCapabilities.GECKO_BUNDLED) return

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

            override fun onServiceDisconnected(name: ComponentName?) {
                webViewServiceDeathConnection = null
                onBrowserSubServiceUnexpectedDeath("webview_service_disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                webViewServiceDeathConnection = null
                onBrowserSubServiceUnexpectedDeath("webview_service_binding_died")
            }
        }
        webViewServiceDeathConnection = connection
        try {
            bindService(
                Intent(this, WebViewService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            webViewServiceDeathConnection = null
            Log.w(TAG, "Failed to bind WebViewService death monitor", e)
        }
    }

    private fun stopBrowserSubServiceDeathMonitor() {
        geckoEngineDeathMonitor.stop()
        webViewServiceDeathConnection?.let(::releaseBrowserServiceDeathConnection)
        hostLivenessToken = null
    }

    /**
     * A crashed bound service remains registered with ActivityManager until its client explicitly
     * unbinds. Dropping the callback reference here leaks the connection and BIND_AUTO_CREATE then
     * relaunches the crashing process. Always unbind the exact connection before forgetting it.
     */
    private fun releaseBrowserServiceDeathConnection(connection: ServiceConnection) {
        try {
            unbindService(connection)
        } catch (e: Exception) {
            // IllegalArgumentException means an overlapping lifecycle transition already unbound.
            if (e !is IllegalArgumentException) {
                Log.w(TAG, "Failed to unbind browser service death monitor", e)
            }
        }
        if (webViewServiceDeathConnection === connection) {
            webViewServiceDeathConnection = null
            hostLivenessToken = null
        }
    }

    /**
     * Cold-start remembered windows, bottom → top (matches [OverlayZOrderCoordinator]):
     * dashboard browser first, then clock / weather / quick entity, then voice chrome.
     * Waits only for the browser *window* to attach — not for the HA page to finish.
     */
    private suspend fun restoreColdStartOverlays(playerSettings: PlayerSettings) {
        val style = withContext(Dispatchers.IO) { settingsStyleSettingsStore.data.first() }
        val browserSettings = browserSettingsStore.get()
        val browserOn = browserSettings.haRemoteUrlEnabled &&
            browserSettings.enableBrowserDisplay &&
            browserSettings.enableBrowserVisible
        val dreamClockOn = playerSettings.enableDreamClock && playerSettings.enableDreamClockVisible
        val simpleClockOn = playerSettings.enableScreensaver && playerSettings.enableScreensaverVisible
        val weatherOn = playerSettings.enableWeatherOverlay && playerSettings.enableWeatherOverlayVisible
        val quickEntitySettings = quickEntitySettingsStore.data.first()
        val quickOn = quickEntitySettings.enableQuickEntity && quickEntitySettings.enableQuickEntityDisplay
        val voiceOn = playerSettings.enableVoiceMessageOverlay &&
            playerSettings.enableVoiceMessageOverlayVisible
        val participants = listOf(
            browserOn,
            dreamClockOn || simpleClockOn,
            weatherOn,
            quickOn,
            voiceOn,
        ).count { it }
        val expectPair = style.overlaySplitEnabled && participants >= 2
        withContext(Dispatchers.Main) {
            // Always the disk copy. A hydrated default-off skips syncFromSettings
            // and the pair is never armed, so every window fades in fullscreen.
            SettingsStyleSession.adoptOverlaySplitFromDisk(style)
            OverlayLayerSplit.markSettingsReady()
            OverlayLayerSplit.beginColdStart(expectPair)
            if (expectPair && browserOn) {
                OverlayLayerSplit.noteOpened(OverlayLayerSplit.Layer.BROWSER)
            }
        }
        try {
        reconcileBrowserVisibility()
        awaitColdStartBrowserWindow()
        // Fullscreen clocks cannot stack. Do not ACTION_HIDE the other — that
        // clears isEnabled while HA still says visible.
        if (dreamClockOn) {
            DreamClockService.show(this)
        } else if (simpleClockOn) {
            ScreensaverService.show(this)
        } else {
            DreamClockService.hide(this)
            ScreensaverService.hide(this)
        }
        if (weatherOn) {
            WeatherOverlayService.show(this)
        } else {
            WeatherOverlayService.hide(this)
        }
        if (quickOn) {
            QuickEntityOverlayService.show(this)
        } else {
            QuickEntityOverlayService.hide(this)
        }
        syncVoiceMessageServices(playerSettings)
        if (playerSettings.enableVoiceMessageOverlay) {
            if (playerSettings.enableVoiceMessageOverlayVisible) {
                VoiceMessageOverlayService.show(this)
            } else {
                VoiceMessageOverlayService.ensureEnabled(this, false)
            }
        } else {
            VoiceMessageOverlayService.hide(this)
        }
        ClockAlertOverlayService.sync(this)
        } finally {
            withContext(Dispatchers.Main) { OverlayLayerSplit.finishColdStart() }
        }
    }

    /** Yield until the dashboard overlay is attached, or the short attach window elapses. */
    private suspend fun awaitColdStartBrowserWindow() {
        val browserSettings = browserSettingsStore.get()
        val featureOn = browserSettings.haRemoteUrlEnabled &&
            browserSettings.enableBrowserDisplay &&
            browserSettings.enableBrowserVisible
        if (!featureOn) return
        val voice = satelliteSettingsStore.get()
        val splitActive =
            browserSettings.splitViewEnabled &&
                browserSettings.browserEngine != com.example.ava.webcompat.BrowserEngine.GECKO
        if (voice.haRemoteUrl.isBlank() && !(splitActive && voice.haRemoteUrlRight.isNotBlank())) {
            return
        }
        val deadline = SystemClock.elapsedRealtime() + COLD_START_BROWSER_ATTACH_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (WebViewService.isBrowserOverlayVisible() ||
                WebViewService.isAnyBrowserOverlayActive()
            ) {
                return
            }
            if (!WebViewService.isBrowserCreating() && WebViewService.isBrowserHidden()) {
                return
            }
            delay(COLD_START_OVERLAY_POLL_MS)
        }
        Log.d(TAG, "Cold-start overlay queue: browser attach wait timed out, continuing")
    }

    /** Align HA browser_display switch, persisted settings, and overlay visibility after restart. */
    private suspend fun reconcileBrowserVisibility() {
        val browserSettings = browserSettingsStore.get()
        val voiceSettings = satelliteSettingsStore.get()
        val featureEnabled = browserSettings.haRemoteUrlEnabled && browserSettings.enableBrowserDisplay
        val wantsVisible = featureEnabled && browserSettings.enableBrowserVisible
        val leftUrl = voiceSettings.haRemoteUrl
        val rightUrl = voiceSettings.haRemoteUrlRight
        val splitActive =
            browserSettings.splitViewEnabled &&
                browserSettings.browserEngine != com.example.ava.webcompat.BrowserEngine.GECKO
        val hasUrl = leftUrl.isNotBlank() || (splitActive && rightUrl.isNotBlank())
        com.example.ava.settings.BrowserSettingsStore.rememberEngine(
            this,
            browserSettings.browserEngine,
        )

        withContext(Dispatchers.Main) {
            when {
                wantsVisible && hasUrl && splitActive -> {
                    WebViewService.applyHaRemoteUrls(
                        this@VoiceSatelliteService,
                        leftUrl,
                        rightUrl,
                    )
                }
                wantsVisible && leftUrl.isNotEmpty() -> {
                    WebViewService.show(this@VoiceSatelliteService, leftUrl)
                }
                else -> {
                    // Hide overlay only — preserve enableBrowserVisible as the user's last choice.
                    WebViewService.hide(this@VoiceSatelliteService)
                }
            }
            updateBrowserSubServiceDeathMonitor(wantsVisible)
        }
        Log.d(
            TAG,
            "Browser visibility reconciled: featureEnabled=$featureEnabled wantsVisible=$wantsVisible " +
                "hasUrl=$hasUrl inProcessOverlay=${WebViewService.isBrowserOverlayVisible()}"
        )
    }
    
    /**
     * Push the latest HA or Sendspin media caches into [VinylCoverService]
     * when the matching media-controls switch still allows the overlay.
     * Never bypasses protocol gates — power-saving off must stay off.
     */
    fun pushMediaOverlaySnapshot() {
        lifecycleScope.launch {
            repeat(6) { attempt ->
                val sendspin = sendspinManager
                if (sendspin != null && sendspin.isActive.value) {
                    sendspin.refreshVinylFromCacheIfNeeded(ignoreEnabledGate = false)
                    return@launch
                }

                // Sendspin may be between tracks / just echo-stopped (isActive
                // false) while its sound is seconds old — hydrating from HA
                // memory here would claim the overlay for stale HA content.
                if (sendspin != null &&
                    (sendspin.wasRecentlyAudible() || VinylCoverService.isSendspinProgressOwner())
                ) {
                    sendspin.refreshVinylFromCacheIfNeeded(ignoreEnabledGate = false)
                    return@launch
                }

                if (!shouldPushHaMediaOverlay()) {
                    return@launch
                }

                val player = _voiceSatellite.value?.player
                if (player != null) {
                    val title = player.haMediaTitleCache
                    val artist = player.haMediaArtistCache
                    val album = player.haMediaAlbumCache
                    val cover = player.haMediaCoverCache
                    val playing = player.haPlaybackState.value
                    val hasMeta = title.isNotEmpty() || artist.isNotEmpty() ||
                        album.isNotEmpty() || cover.isNotEmpty()

                    // Always refresh process memory — mini FAB reads this first.
                    com.example.ava.services.MediaOverlayMemoryCache.putFull(
                        coverUrl = cover.ifEmpty { null },
                        songTitle = title.ifEmpty { null },
                        artistName = artist.ifEmpty { null },
                        albumName = album.ifEmpty { null },
                        isPlaying = playing,
                        currentTimeMs = player.interpolatedHaPositionMs().coerceAtLeast(0L),
                        totalTimeMs = player.haMediaDurationMs.takeIf { it > 0L },
                        isSendspinSource = false,
                    )

                    if (hasMeta) {
                        VinylCoverService.show(
                            context = this@VoiceSatelliteService,
                            coverUrl = cover.ifEmpty { null },
                            songTitle = title.ifEmpty { null },
                            artistName = artist.ifEmpty { null },
                            albumName = album.ifEmpty { null },
                            isPlaying = playing,
                            currentTimeMs = player.interpolatedHaPositionMs().coerceAtLeast(0L),
                            totalTimeMs = player.haMediaDurationMs.takeIf { it > 0L },
                            isSendspinSource = false,
                        )
                    } else {
                        VinylCoverService.updatePlaybackState(
                            this@VoiceSatelliteService,
                            isPlaying = playing,
                            isSendspinSource = false,
                        )
                    }
                    return@launch
                }

                kotlinx.coroutines.delay(250L * (attempt + 1))
            }
            Log.d(TAG, "pushMediaOverlaySnapshot: no media source yet")
        }
    }

    /**
     * The location type is what keeps Bluetooth presence detection alive across a screen-off,
     * and it is worth being explicit about why, because nothing about it looks like Bluetooth.
     *
     * `BLUETOOTH_SCAN` is declared without `neverForLocation`, so the platform treats scan
     * results as location-derived and notes the location app op before delivering them. That op
     * is held as "while in use", which for a service means it is allowed only while the process
     * carries `PROCESS_CAPABILITY_FOREGROUND_LOCATION` — and only this foreground service type
     * grants it. Without it the app is covered just while its activity is top; every screen-off
     * drops it below that, the op evaluates to ignored, and `GattService` discards results with
     * no callback and no error. The scanner does not fail, it goes quiet, which reads exactly
     * like every tracked device having left the building.
     *
     * Added only when a location permission is actually granted: from Android 14 a foreground
     * service that asks for a type it cannot back up is refused outright, and losing the whole
     * service would be far worse than losing presence.
     */
    private fun voiceSatelliteForegroundServiceTypes(): Int {
        val base = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (!hasLocationPermission()) return base
        return base or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    }

    private fun hasLocationPermission(): Boolean {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (fine) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Re-applies the foreground service types after a permission grant, so location taking
     * effect does not have to wait for the next satellite restart. A no-op unless the set
     * actually changed — `startForeground` on an already-foreground service is cheap but not
     * free, and re-posting the notification for nothing makes it flicker on some ROMs.
     */
    fun refreshForegroundServiceTypes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val types = voiceSatelliteForegroundServiceTypes()
        if (types == appliedForegroundServiceTypes) return
        val state = _voiceSatellite.value?.state?.value ?: Stopped
        runCatching {
            startForeground(
                2,
                createVoiceSatelliteServiceNotification(this, state.translate(resources)),
                types,
            )
        }.onSuccess {
            appliedForegroundServiceTypes = types
            Log.i(TAG, "Foreground service types refreshed to $types")
        }.onFailure {
            Log.w(TAG, "Could not refresh foreground service types", it)
        }
    }

    /** Whether the running service is currently covered for background BLE scanning. */
    fun hasLocationForegroundServiceType(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            (
                appliedForegroundServiceTypes and
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                ) != 0

    companion object {
        const val TAG = "VoiceSatelliteService"
        /** Auto-prompt for battery exemption at most once; manual paths remain in settings. */
        private const val KEY_BATTERY_OPT_PROMPTED = "battery_opt_exemption_prompted"
        /** Loud enough that the mic clearly picks the hum up, and independent of media volume. */
        private const val ECHO_TEST_TONE_VOLUME = 0.9f
        private const val TTS_HTTP_STREAM_TIMEOUT_MS = 30_000
        /** Audio buffered before streaming TTS starts; low for fast first-word latency. */
        private const val TTS_STREAM_START_BUFFER_MS = 250
        private const val TTS_STREAM_REBUFFER_MS = 750
        /** Settle window after HA connects before the allow_service_calls probe. */
        private const val HA_SERVICE_CALLS_PROBE_SETTLE_MS = 8_000L
        /** Coalesce settings bursts so HA is kicked once, not per toggle. */
        private const val HA_REDISCOVER_COALESCE_MS = 400L
        /**
         * First re-announce after HA goes (or stays) disconnected. The satellite
         * start path just registered NSD, so give that burst a moment to land.
         */
        private const val NSD_REANNOUNCE_INITIAL_DELAY_MS = 10_000L
        /** Fresh-disconnect cadence; keeps worst-case rediscovery well under HA's 60s backoff cap. */
        private const val NSD_REANNOUNCE_FAST_INTERVAL_MS = 20_000L
        /** Long-outage cadence (e.g. HA down for hours) — stay findable, keep mDNS chatter low. */
        private const val NSD_REANNOUNCE_SLOW_INTERVAL_MS = 60_000L
        /** Fast ticks before settling: 10s + 5×20s ≈ the first two minutes of a disconnect. */
        private const val NSD_REANNOUNCE_FAST_TICKS = 6
        /** Wait for the dashboard overlay window only — not HA first paint. */
        private const val COLD_START_BROWSER_ATTACH_WAIT_MS = 1_200L
        private const val COLD_START_OVERLAY_POLL_MS = 50L
        private val haServiceProbeScheduleArmed = AtomicBoolean(false)
        
        private var instance: VoiceSatelliteService? = null
        private val _isRunning = MutableStateFlow(false)
        /** Emits whenever the foreground voice service is created or destroyed. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        /**
         * Soft-stop tears the satellite down without destroying this process, so [isRunning]
         * stays true. The home-screen tile follows this instead.
         */
        private val _satelliteStarted = MutableStateFlow(false)
        val satelliteStarted: StateFlow<Boolean> = _satelliteStarted.asStateFlow()
        /**
         * Coarse render key for the service tile: "running" / "disconnected" / "error" /
         * "stopped". Launcher-drawn cards (see MinimalLauncherWorkspaceWidgets.DesktopCard)
         * are not bound AppWidgets, so AppWidgetManager refreshes never reach them — they
         * recompose off this flow instead. Kept in sync by the widget-refresh collector in
         * [onCreate] and force-reset in [onDestroy].
         */
        private val _serviceTileStateKey = MutableStateFlow("stopped")
        val serviceTileStateKey: StateFlow<String> = _serviceTileStateKey.asStateFlow()
        @Volatile private var lastOverlayGrantReconcileMs = 0L

        /**
         * Process-wide wake-suspend refcount. Survives a missing service instance and brief
         * service death so "start from enrollment sheet" comes up already suspended.
         */
        private val wakeDetectionSuspendDepth = java.util.concurrent.atomic.AtomicInteger(0)

        fun getInstance(): VoiceSatelliteService? = instance

        fun isVoiceTurnActive(): Boolean = instance?.isVoiceInteractionActive() == true

        /**
         * Feed one HA entity state into the shared ingress. [HaStateArbiter] drops the push
         * when [source] is not the channel currently owning entity state.
         */
        fun applyHaEntityState(
            source: HaStateSource,
            entityId: String,
            attribute: String,
            state: String,
        ) {
            instance?._voiceSatellite?.value?.applyHaEntityState(source, entityId, attribute, state)
        }

        /** Apply TTS volume immediately when a reply is already playing. */
        fun applyVoiceReplyVolumeLive(level: Float, autoGain: Boolean? = null) {
            instance?.applyLiveVoiceReplyVolume(level, autoGain)
        }

        /**
         * System media volume shown in settings: always the live STREAM_MUSIC
         * level, including the temporary TTS overlay write during a reply.
         */
        fun displayedUserMediaVolume(context: Context): Float {
            return com.example.ava.utils.DeviceMusicVolumeMonitor.readNormalizedLevel(context)
        }

        /** User moved the media-volume slider. Same path as a volume key. */
        fun setUserMediaVolume(context: Context, level: Float) {
            val clamped = level.coerceIn(0f, 1f)
            val service = instance
            if (service != null) {
                service.applyUserMediaVolume(clamped)
            } else {
                com.example.ava.utils.DeviceMusicVolumeMonitor.writeNormalizedLevel(context, clamped)
            }
        }

        /**
         * Latch/release wake suspension without requiring a live satellite.
         * When depth rises 0→1, apply to the current satellite if any; create-path applies
         * before start() when depth is already > 0.
         */
        fun requestWakeDetectionSuspended(value: Boolean) {
            if (value) {
                if (wakeDetectionSuspendDepth.getAndIncrement() == 0) {
                    instance?._voiceSatellite?.value?.let { syncWakeDetectionSuspension(it) }
                }
            } else {
                while (true) {
                    val cur = wakeDetectionSuspendDepth.get()
                    if (cur <= 0) return
                    if (wakeDetectionSuspendDepth.compareAndSet(cur, cur - 1)) {
                        if (cur == 1) {
                            instance?._voiceSatellite?.value?.let { syncWakeDetectionSuspension(it) }
                        }
                        return
                    }
                }
            }
        }

        /** Apply latched suspend to a satellite that is about to start (or just started). */
        private fun applyPendingWakeDetectionSuspend(satellite: VoiceSatellite) {
            syncWakeDetectionSuspension(satellite)
        }

        /**
         * Single owner of the wake-engine suspend flag. Two independent reasons can hold it —
         * the voiceprint enrollment sheet (refcount) and button-only wake mode — and neither
         * may clear it while the other still applies. Enrollment additionally arms voiceprint
         * capture, so it goes through [VoiceSatellite.setWakeDetectionSuspended]; button-only
         * mode only needs the detector gate.
         */
        fun syncWakeDetectionSuspension(
            satellite: VoiceSatellite,
            wakeMode: com.example.ava.settings.WakeMode? = null,
        ) {
            val enrollmentHold = wakeDetectionSuspendDepth.get() > 0
            val mode = wakeMode
                ?: instance?.microphoneSettingsStore?.getCached()?.wakeMode
                ?: com.example.ava.settings.WakeMode.VOICE
            val buttonOnly = mode == com.example.ava.settings.WakeMode.BUTTON
            if (enrollmentHold) {
                satellite.setWakeDetectionSuspended(true)
            } else {
                // Enrollment is over either way; only a clear(false) resets its listen flag,
                // so drop it explicitly before possibly keeping the gate closed for button mode.
                satellite.audioInput.stopVoicePrintEnrollmentListen()
                satellite.audioInput.setWakeDetectionSuspended(buttonOnly)
            }
        }

        fun requestVoiceMessageSync() {
            instance?.lifecycleScope?.launch {
                instance?.syncVoiceMessageServices()
            }
        }

        /**
         * After overlay permission is granted, show the floating browser when HA browser_display
         * is already ON so the user is not left with an empty screen.
         */
        fun reconcileBrowserVisibilityAfterOverlayGrant() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastOverlayGrantReconcileMs < 5_000L) return
            lastOverlayGrantReconcileMs = now
            requestBrowserReconcile()
        }

        /** Show the browser overlay now if settings say it should be visible. Not rate limited. */
        fun requestBrowserReconcile() {
            val service = instance ?: return
            service.lifecycleScope.launch {
                try {
                    val browserSettings = service.browserSettingsStore.get()
                    val wantsBrowser = browserSettings.haRemoteUrlEnabled &&
                        browserSettings.enableBrowserDisplay &&
                        browserSettings.enableBrowserVisible
                    if (!wantsBrowser) return@launch
                    service.reconcileBrowserVisibility()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reconcile browser after overlay grant", e)
                }
            }
        }

        fun notifyHardwareMusicVolumeChanged() {
            instance?.dispatchHardwareMusicVolumeChanged()
        }

        fun isSatelliteStarted(): Boolean = _satelliteStarted.value

        private fun publishSatelliteStarted(started: Boolean) {
            _satelliteStarted.value = started
        }

        fun toggleMicMute() {
            instance?._voiceSatellite?.value?.toggleMicMute()
        }
        
        fun setMicMute(muted: Boolean) {
            instance?._voiceSatellite?.value?.setMicMute(muted)
        }

        fun setMicVolume(volume: Float) {
            instance?._voiceSatellite?.value?.setMicVolume(volume)
        }

        fun micVolume(): Float? = instance?._voiceSatellite?.value?.micVolume()

        fun isMicMuted(): Boolean = instance?._voiceSatellite?.value?.isMuted() == true

        fun isTimerRingingNow(): Boolean = instance?.isTimerRinging() == true

        fun setVideoRecording(enabled: Boolean) {
            instance?._voiceSatellite?.value?.setVideoRecording(enabled)
        }

        suspend fun pauseCameraForVoiceVideo() {
            instance?._voiceSatellite?.value?.pauseCameraForVoiceCallVideo()
        }

        suspend fun startVoiceCallVideo(
            useFrontCamera: Boolean,
            onFrame: (ByteArray) -> Unit
        ): Boolean {
            return instance?._voiceSatellite?.value?.startVoiceCallVideo(useFrontCamera, onFrame) ?: false
        }

        suspend fun restartVoiceCallVideo(
            useFrontCamera: Boolean,
            onFrame: (ByteArray) -> Unit
        ) {
            instance?._voiceSatellite?.value?.restartVoiceCallVideo(useFrontCamera, onFrame)
        }

        suspend fun stopVoiceCallVideo() {
            instance?._voiceSatellite?.value?.stopVoiceCallVideo()
        }

        suspend fun resumeCameraAfterVoiceVideo() {
            instance?._voiceSatellite?.value?.resumeCameraAfterVoiceCallVideo()
        }
        
        /**
         * Text the Assist pipeline transcribed, for UI that wants the words rather than the
         * intent — currently the Music Assistant rail's voice search.
         *
         * Replay 0 and a 1-slot buffer on purpose: a late collector must not be handed a
         * transcript from an earlier, unrelated utterance.
         */
        private val _sttText = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val sttText: SharedFlow<String> = _sttText.asSharedFlow()

        internal fun publishSttText(text: String) {
            _sttText.tryEmit(com.example.ava.localllm.SttTranscript.forDisplay(text))
        }

        fun searchListen() {
            instance?._voiceSatellite?.value?.searchListen()
        }

        fun manualWake() {
            instance?._voiceSatellite?.value?.manualWake()
        }

        /**
         * Quick Wake FAB: silent manual listen when idle. A tap during an active
         * turn is [stopVoiceSession] (Just stop), not barge-in-and-listen.
         */
        fun quickWake() {
            instance?._voiceSatellite?.value?.triggerManualWake(silent = true)
        }

        /** STT, intent, or TTS is in flight — a FAB tap should cut it. */
        fun isAssistTurnActive(): Boolean =
            instance?._voiceSatellite?.value?.isAssistTurnActive() == true

        /** Mic is opening or open — hold-to-talk can latch without calling [quickWake]. */
        fun isCollectingSpeech(): Boolean =
            instance?._voiceSatellite?.value?.isCollectingSpeech() == true

        /** Push-to-talk release: finalize STT now (not an abort). */
        fun finishManualSpeech() {
            instance?._voiceSatellite?.value?.finishManualSpeech()
        }

        /**
         * True while the current turn came from the Quick Wake button. The wake ripple, Esper
         * sphere and floating captions check this and stay hidden: the button itself shows
         * voice level and transcript, and two status surfaces at once would fight.
         */
        fun isQuickWakeSessionActive(): Boolean {
            val sat = instance?._voiceSatellite?.value
            if (sat?.isQuickWakeSession == true) return true
            // Button-only: HA entity / assist / announce can emit chrome callbacks
            // before wakeSatellite latches the flag. Hybrid is unchanged (flag only).
            return instance?.microphoneSettingsStore?.getCached()?.wakeMode ==
                com.example.ava.settings.WakeMode.BUTTON &&
                sat?.isAssistTurnActive() == true
        }
        
        fun stopVoiceSession() {
            instance?._voiceSatellite?.value?.stopVoiceSession()
        }
        
        fun updateProximityPublishInterval(intervalSeconds: Int) {
            instance?._voiceSatellite?.value?.updateProximityPublishInterval(intervalSeconds)
        }
        
        fun updateSensorInterval(intervalSeconds: Int) {
            instance?._voiceSatellite?.value?.updateSensorInterval(intervalSeconds)
        }
    }
    
    fun getSendspinStats(): Map<String, Any?>? {
        return sendspinManager?.getStats()
    }
    
    private var sendspinStateJob: kotlinx.coroutines.Job? = null
    private var sendspinRestartJob: kotlinx.coroutines.Job? = null

    /**
     * One-time migration: software volume used persisted player % while STREAM_MUSIC was often max.
     * Map that saved level onto system media volume, then lock to device-volume-only.
     */
    private suspend fun migrateSendspinSoftwareVolumeIfNeeded() {
        val settings = sendspinSettingsStore.get()
        if (settings.volumeFollowRule.isBlank()) {
            // Persist resolved rule so fleet/UI see an explicit key (and keep legacy boolean aligned).
            sendspinSettingsStore.volumeFollowRule.set(VolumeFollowRule.fromSettings(settings))
        }
        if (settings.useDeviceVolume) return
        val percent = settings.volume.coerceIn(0, 100)
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume > 0) {
            val target = (percent * maxVolume / 100).coerceIn(0, maxVolume)
            deviceMusicVolumeMonitor?.suppressNextCallbacks(4)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            Log.i(TAG, "Migrated Sendspin software volume $percent% → STREAM_MUSIC step $target/$maxVolume")
        }
        sendspinSettingsStore.useDeviceVolume.set(true)
    }
    
    private suspend fun initSendspin() {
        migrateSendspinSoftwareVolumeIfNeeded()
        // Prewarm hot-path snapshots so the first Sendspin callback never blocks
        // on disk from the main/audio thread.
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            sendspinSettingsStore.getCached()
            playerSettingsStore.getCached()
        }
        val settings = sendspinSettingsStore.get()
        if (!settings.enabled) {
            Log.d(TAG, "Sendspin disabled")
            VinylCoverService.clearAwaitingPipelineRebind()
            return
        }
        val existing = sendspinManager
        if (existing != null) {
            Log.d(TAG, "Sendspin manager already live; reusing instead of recreating")
            val currentSettings = sendspinSettingsStore.get()
            if (currentSettings.serverUrl.isNotEmpty()) {
                existing.enable(currentSettings.serverUrl)
            } else {
                existing.enable()
                existing.ensureInboundListening()
            }
            ensureSendspinHaStateJob()
            existing.prepareOverlayRebindIfNeeded()
            initMassApiSideChannel()
            Log.d(TAG, "Sendspin reused: enabled=${settings.enabled}")
            return
        }
        sendspinManager = com.example.ava.sendspin.SendspinManager(
            context = applicationContext,
            scope = lifecycleScope
        ).apply {
            setVolumeCallback { volume ->
                lifecycleScope.launch {
                    // Mirror MA/upstream volume onto ESPHome media_player without looping
                    // back into updateVolume → setStreamVolume.
                    suppressSendspinVolumeUpdate = true
                    _voiceSatellite.value?.player?.setVolume(volume)
                }
            }
            setSuppressDeviceVolumeObserverCallback { count ->
                deviceMusicVolumeMonitor?.suppressNextCallbacks(count)
                deviceMusicVolumeMonitor?.suppressForMs(1000L)
            }
            setMuteCallback { muted ->
                _voiceSatellite.value?.player?.setMuted(muted)
            }
            setPlayingCallback { sendspinPlaying ->
                if (sendspinPlaying) {
                    if (suppressSendspinPlaybackConflict.get()) {
                        Log.d(TAG, "Ignoring Sendspin playback callback during HA handoff")
                        return@setPlayingCallback
                    }
                    val mediaPlayer = _voiceSatellite.value?.player?.mediaPlayer
                    if (mediaPlayer?.isPlaying == true || mediaPlayer?.isPaused == true) {
                        suppressSendspinPlaybackConflictTemporarily()
                        sendspinManager?.stopPlayback()
                        Log.d(TAG, "Sendspin started while HA media active, stopping Sendspin playback")
                    }
                }
            }
            // Hot-path callbacks (main/audio threads): read the in-memory settings
            // snapshot instead of runBlocking { DataStore } to avoid jank/underrun/ANR.
            setVinylCoverEnabledCallback {
                // MA Media Controls only — Mini FAB must not keep Sendspin overlay alive.
                playerSettingsStore.getCached().enableSendspinVinylCover
            }
            setLowMemoryModeCallback {
                sendspinSettingsStore.getCached().lowMemoryMode
            }
            setPreferredFormatCallback {
                SendspinFormatCatalog.normalizePreferredFormat(
                    sendspinSettingsStore.getCached().preferredFormat
                )
            }
            setDeviceNameCallback {
                sendspinSettingsStore.getCached().customDeviceName
            }
            setSyncOffsetMsCallback {
                sendspinSettingsStore.getCached().syncOffsetMs
            }
            setVolumePersistenceCallbacks(
                getVolume = {
                    sendspinSettingsStore.getCached().volume
                },
                setVolume = { volume ->
                    lifecycleScope.launch {
                        sendspinSettingsStore.volume.set(volume)
                    }
                },
                getMuted = {
                    sendspinSettingsStore.getCached().muted
                },
                setMuted = { muted ->
                    lifecycleScope.launch {
                        sendspinSettingsStore.muted.set(muted)
                    }
                }
            )
            setPairedDeviceCallbacks(
                savePairedDevice = { deviceUrl, deviceName ->
                    lifecycleScope.launch {
                        sendspinSettingsStore.addPairedDevice(deviceUrl, deviceName)
                    }
                },
                getPairedDevices = {
                    sendspinSettingsStore.getCached().pairedDevices
                }
            )
            setLastPlayedServerIdCallbacks(
                save = { serverId ->
                    lifecycleScope.launch {
                        sendspinSettingsStore.lastPlayedServerId.set(serverId)
                    }
                },
                get = {
                    sendspinSettingsStore.getCached().lastPlayedServerId
                }
            )
        }
        
        val currentSettings = sendspinSettingsStore.get()
        // autoConnect / advertiseAsPlayer are always on (UI hidden); start discovery/client
        // whenever Sendspin master switch is enabled.
        if (currentSettings.serverUrl.isNotEmpty()) {
            sendspinManager?.enable(currentSettings.serverUrl)
        } else {
            sendspinManager?.enable()
        }

        ensureSendspinHaStateJob()

        // Soft restart left a painted shell — hydrate + arm one-shot rebind so the
        // next stream/metadata reconnects without tearing the overlay down.
        sendspinManager?.prepareOverlayRebindIfNeeded()

        // MA API side-channel: frontend queue repeat/shuffle UI only.
        // Mass API: UI transport (+ optional progress enhance). SP keeps audio/seek.
        initMassApiSideChannel()

        Log.d(TAG, "Sendspin initialized: enabled=${settings.enabled}, waiting for HA connection")
    }

    private fun ensureSendspinHaStateJob() {
        if (sendspinStateJob?.isActive == true) return
        sendspinStateJob = _voiceSatellite
            .flatMapLatest { satellite -> satellite?.state ?: flowOf(Disconnected) }
            .distinctUntilChanged()
            .onEach { state ->
                val manager = sendspinManager
                when (state) {
                    is Connected -> {
                        if (manager != null) {
                            Log.i(TAG, "Sendspin session active: Home Assistant connection established, synchronizing remote playback state")
                        }
                        // Voice session ended: if HA media is still paused, arm pause-idle
                        // (missed at pause time while Listening/Processing/Responding).
                        maybeRearmHaPauseIdleAfterVoice()
                    }
                    is Disconnected, is Stopped -> {
                        Log.d(TAG, "HA disconnected, keeping Sendspin discovery/client alive")
                        stopA64VolumeControlService()
                    }
                    // Keep Sendspin running during Listening, Processing, Responding states
                    // duck/unDuck handles volume, pause/play commands handle playback
                    else -> Unit
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun initMassApiSideChannel() {
        val manager = com.example.ava.massapi.MassApiManager.ensure(applicationContext)
        // Paint hook only — MassApi must not drive Sendspin protocol commands.
        // HA media_player duck/restore intentionally NOT bridged here: the
        // VoiceSatellite listening path already ducks/restores the HA entity
        // for every source; a second async path only duplicates volume_set.
        com.example.ava.massapi.MassApiManager.bindSendspin { sendspinManager }
        Log.d(TAG, "Mass API UI side-channel ready (connectionState=${manager.connectionState.value})")
    }
    
    private fun closeSendspin(preserveOverlayForRebind: Boolean = false) {
        sendspinStateJob?.cancel()
        sendspinStateJob = null
        sendspinManager?.close(preserveOverlayForRebind = preserveOverlayForRebind)
        sendspinManager = null
        // Soft rebind only when MA Media Controls are still on — otherwise a
        // later PCM tick must not birth FAB via ignoreEnabledGate.
        val mayRebind =
            preserveOverlayForRebind &&
                runCatching {
                    playerSettingsStore.getCached().enableSendspinVinylCover
                }.getOrDefault(false)
        if (mayRebind) {
            VinylCoverService.markAwaitingPipelineRebind()
        } else {
            VinylCoverService.clearAwaitingPipelineRebind()
        }
        stopA64VolumeControlService()
        lifecycleScope.launch {
            reconcileDeviceVolumeSync()
        }
    }
}

/** Who asked [VoiceSatelliteService.restartVoiceSatellite] to run. */
enum class SatelliteRestartReason {
    /** Settings, watchers, mods — HA rediscover only, protocol stays up. */
    SETTINGS,
    /** Name/port, wake library, CameraX — rebuild satellite, leave Sendspin up. */
    SATELLITE_PIPELINE,
    /** HA `restart_service` button — full satellite + protocol tear is allowed. */
    HA_RESTART_ENTITY,
}
