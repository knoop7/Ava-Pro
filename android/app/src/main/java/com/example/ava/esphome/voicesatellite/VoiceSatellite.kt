package com.example.ava.esphome.voicesatellite

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.ava.R
import com.example.ava.audio.AmbientAutoGain
import com.example.ava.audio.AudioEnergy
import com.example.ava.mods.ModConversationEngine
import com.example.ava.mods.ModVoicePipeline
import com.example.ava.esphome.Connected
import com.example.ava.esphome.Disconnected
import com.example.ava.esphome.EspHomeDevice
import com.example.ava.esphome.EspHomeState
import com.example.ava.esphome.Stopped
import com.example.ava.esphome.entities.ButtonEntity
import com.example.ava.esphome.entities.MediaPlayerEntity
import com.example.ava.esphome.entities.NumberEntity
import com.example.ava.esphome.entities.SelectEntity
import com.example.ava.esphome.entities.BinarySensorEntity
import com.example.ava.esphome.entities.SensorEntity
import com.example.ava.esphome.entities.SwitchEntity
import com.example.ava.esphome.entities.TextEntity
import com.example.ava.esphome.entities.ServiceEntity
import com.example.ava.esphome.entities.ServiceArg
import com.example.ava.esphome.entities.TextSensorEntity
import com.example.ava.sensor.ScreenGestureRecognizer
import com.example.ava.fleet.FleetManager
import com.example.ava.homeassistant.HaManager
import com.example.ava.localllm.remote.AvaTurnTools
import com.example.ava.localllm.remote.TtsMdFilter
import com.example.ava.homeassistant.HaWsClient
import com.example.ava.homeassistant.HaMediaAuth
import com.example.ava.homeassistant.state.HaEntityInterest
import com.example.ava.homeassistant.state.HaStateArbiter
import com.example.ava.homeassistant.state.HaStateSource
import com.example.ava.settings.HaSettingsStore
import com.example.ava.settings.haSettingsStore
import com.example.ava.utils.HaMediaUrl
import com.example.ava.notifications.NotificationScenes
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import com.example.ava.services.FloatingWindowService
import com.example.ava.services.ScreensaverWebViewService
import com.example.ava.settings.NotificationSettingsStore
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.UpdateSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.ExperimentalSettings
import com.example.ava.settings.WakeMode
import com.example.ava.voice.FabHaInject
import com.example.ava.voice.FabListenRenew
import com.example.ava.voice.QuickWakePushToTalk
import com.example.ava.settings.WakeWordEngine
import com.example.ava.settings.compatibleWakeWordIdsForEngine
import com.example.ava.settings.migrateScreensaverWeatherIfNeeded
import com.example.ava.settings.resolveScreensaverWeatherEntityId
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.settings.updateSettingsStore
import com.example.esphomeproto.api.DeviceInfoResponse
import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.VoiceAssistantAnnounceRequest
import com.example.esphomeproto.api.VoiceAssistantConfigurationRequest
import com.example.esphomeproto.api.VoiceAssistantEvent
import com.example.esphomeproto.api.VoiceAssistantEventResponse
import com.example.esphomeproto.api.VoiceAssistantFeature
import com.example.esphomeproto.api.BluetoothProxyFeature
import com.example.esphomeproto.api.VoiceAssistantSetConfiguration
import com.example.esphomeproto.api.VoiceAssistantTimerEvent
import com.example.esphomeproto.api.VoiceAssistantTimerEventResponse
import com.example.esphomeproto.api.VoiceAssistantAudio
import com.example.esphomeproto.api.VoiceAssistantResponse
import com.example.esphomeproto.api.SubscribeVoiceAssistantRequest
import com.example.esphomeproto.api.SubscribeBluetoothLEAdvertisementsRequest
import com.example.esphomeproto.api.UnsubscribeBluetoothLEAdvertisementsRequest
import com.example.esphomeproto.api.SubscribeBluetoothConnectionsFreeRequest
import com.example.esphomeproto.api.BluetoothDeviceRequest
import com.example.esphomeproto.api.BluetoothGATTGetServicesRequest
import com.example.esphomeproto.api.BluetoothGATTReadRequest
import com.example.esphomeproto.api.BluetoothGATTWriteRequest
import com.example.esphomeproto.api.BluetoothGATTReadDescriptorRequest
import com.example.esphomeproto.api.BluetoothGATTWriteDescriptorRequest
import com.example.esphomeproto.api.BluetoothGATTNotifyRequest
import com.example.esphomeproto.api.BluetoothScannerSetModeRequest
import com.example.esphomeproto.api.BluetoothSetConnectionParamsRequest
import com.example.esphomeproto.api.bluetoothLERawAdvertisementsResponse
import com.example.esphomeproto.api.bluetoothLERawAdvertisement
import com.example.esphomeproto.api.bluetoothScannerStateResponse
import com.example.esphomeproto.api.BluetoothScannerState
import com.example.esphomeproto.api.BluetoothScannerMode
import com.example.esphomeproto.api.HomeAssistantStateResponse
import com.example.esphomeproto.api.HomeassistantActionResponse
import com.example.esphomeproto.api.SubscribeHomeassistantServicesRequest
import com.example.esphomeproto.api.SubscribeHomeAssistantStatesRequest
import com.example.esphomeproto.api.subscribeHomeAssistantStateResponse
import com.example.esphomeproto.api.homeassistantServiceResponse
import com.example.esphomeproto.api.homeassistantServiceMap
import java.util.concurrent.atomic.AtomicInteger
import com.example.esphomeproto.api.deviceInfoResponse
import com.example.esphomeproto.api.voiceAssistantAnnounceFinished
import com.example.esphomeproto.api.voiceAssistantAudio
import com.example.esphomeproto.api.voiceAssistantConfigurationResponse
import com.example.esphomeproto.api.voiceAssistantRequest
import com.example.ava.multidevice.WakeWordArbiter
import com.example.ava.services.ChorusWakeBlurService
import com.example.ava.audio.PlaybackEnergyMonitor
import com.example.ava.detection.AudioEventManager
import com.example.ava.voiceprint.VoicePrintManager
import com.example.ava.voiceprint.statusText
import com.example.ava.voice.AvaVoiceVideoBridge
import com.example.ava.voice.VoiceCallVideoQuality
import com.example.esphomeproto.api.voiceAssistantWakeWord
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.example.ava.mods.ModDeviceSupport
import com.example.ava.bluetooth.BluetoothPresenceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import com.example.ava.settings.BrowserSettings
import com.example.ava.settings.ScreensaverSettings
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.services.ClockAlertOverlayService
import com.example.ava.services.DreamClockService
import com.example.ava.services.QuickEntityCameraRenderer
import com.example.ava.services.QuickEntityOverlayService
import com.example.ava.services.ScreensaverService
import com.example.ava.mods.ModManager
import com.example.ava.mods.ModEntityFactory
import com.example.ava.mods.ModBleAdvProxyBridge
import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import com.example.ava.ui.AvaToast

data object Listening : EspHomeState
data object Responding : EspHomeState
data object Processing : EspHomeState

class VoiceSatellite(
    coroutineContext: CoroutineContext,
    name: String,
    port: Int,
    val audioInput: VoiceSatelliteAudioInput,
    val player: VoiceSatellitePlayer,
    private val voiceChannelEnabled: Boolean,
    val settingsStore: VoiceSatelliteSettingsStore,
    val notificationSettingsStore: NotificationSettingsStore,
    val experimentalSettingsStore: com.example.ava.settings.ExperimentalSettingsStore,
    val microphoneSettingsStore: com.example.ava.settings.MicrophoneSettingsStore,
    val playerSettingsStore: PlayerSettingsStore,
    browserSettingsData: BrowserSettings,
    experimentalSettingsData: ExperimentalSettings,
    screensaverSettingsData: ScreensaverSettings,
    playerSettingsData: PlayerSettings,
    val onRestartService: (() -> Unit)? = null,
    val onWakeWordEngineChanged: ((WakeWordEngine) -> Unit)? = null,
    private val context: Context,
    encryptionKey: String = "",
    deviceMacAddress: String = "",
) : EspHomeDevice(
    coroutineContext,
    name,
    port,
    VoiceSatelliteEntities.buildEntities(
        // Warm availableWakeWords: force the lazy load to finish synchronously before Server accept, so an HA config request returns the full list immediately
        audioInput.also { Log.d(TAG, "Preloaded ${it.availableWakeWords.size} wake words") },
        player,
        voiceChannelEnabled,
        notificationSettingsStore,
        onRestartService,
        context,
        experimentalSettingsData,
        browserSettingsData,
        screensaverSettingsData,
        playerSettingsStore,
        playerSettingsData,
        microphoneSettingsStore.getFlow().map { it.wakeWordEngine },
        onWakeWordEngineChanged,
        { volume ->
            kotlinx.coroutines.CoroutineScope(coroutineContext).launch { experimentalSettingsStore.setMicrophoneVolume(volume) }
        }
    ),
    encryptionKey = encryptionKey,
    noiseMacAddress = deviceMacAddress,
) {
    init {
        // Same HA peer address used for cover art — make TTS/media play URLs reachable.
        player.haHostProvider = { server.getClientAddress() }
        // Arbiter listen thread → satellite scope. A restarted satellite overwrites the
        // stale hook; a cancelled scope makes the stale hook a no-op.
        WakeWordArbiter.onSeatRevoked = { wakeKey ->
            scope.launch { onChorusSeatRevoked(wakeKey) }
        }
        ModConversationEngine.attachHost { reason ->
            finishConversationEngineSeat(reason)
        }
    }

    private var timerFinished = false
    private var timerFinishedSoundUri: String? = null
    private var savedMusicPosition: Long = 0L  
    private var wasMusicPlaying = false
    var multiDeviceArbiterEnabled = true
    @Volatile private var chorusWonSession = false
    @Volatile private var chorusSessionWakeKey = ""

    /**
     * Remote-AI route only. Channel [_state] may already be [Connected] while the
     * cloud model is still working; UI reads this hold instead of following the
     * HA pipeline. The HA route never sets it.
     */
    private val _remoteAiHold = MutableStateFlow(false)
    val remoteAiHold = _remoteAiHold.asStateFlow()
    private var localReplyWatchdogJob: Job? = null

    /**
     * True while a re-wake tears down the old run after the new arbitration already
     * re-claimed the seat. Old-run completion callbacks fire inside this window and
     * must not announce chorus end for the session that just started.
     */
    @Volatile private var chorusRewakeHandoff = false

    /** Winner-side keepalive loop; sustains the losers' short hold (see [startChorusAliveHeartbeat]). */
    private var chorusAliveJob: Job? = null

    /**
     * A conversation-engine mod owns this wake: HA pipeline must not start, host
     * capture is paused so the mod can open AudioRecord, chorus seat stays ours
     * until release / revoke / timeout.
     */
    @Volatile private var conversationEngineHold = false
    private var conversationEngineLeaseJob: Job? = null

    /**
     * Beat [WakeWordArbiter.ALIVE_INTERVAL_MS] while this device owns the chorus
     * seat. Losers expire their hold seconds after the beats stop, so a lost END
     * broadcast (or a killed winner) can no longer deafen them for a fixed 90 s —
     * and a conversation longer than any fixed grant keeps them muted throughout.
     * The loop exits on its own when the seat is released ([announceChorusSessionEnd]
     * and [onChorusSeatRevoked] clear [chorusWonSession]); cancellation is just tidy.
     */
    private fun startChorusAliveHeartbeat() {
        chorusAliveJob?.cancel()
        chorusAliveJob = scope.launch {
            while (chorusWonSession && multiDeviceArbiterEnabled) {
                WakeWordArbiter.announceSessionAlive(context, chorusSessionWakeKey)
                delay(WakeWordArbiter.ALIVE_INTERVAL_MS)
            }
        }
    }

    private fun stopChorusAliveHeartbeat() {
        chorusAliveJob?.cancel()
        chorusAliveJob = null
    }

    /** Winner finished talking (or the run aborted). Losers fade on [AVA_WAKE_END]. */
    fun publishChorusSessionEnd() {
        announceChorusSessionEnd()
    }

    private fun announceChorusSessionEnd() {
        if (!multiDeviceArbiterEnabled) return
        val key = chorusSessionWakeKey.ifBlank { "wake" }
        if (!chorusWonSession) {
            Log.d(TAG, "chorus session end skipped: not winner key=$key")
            return
        }
        // A re-wake interrupts the old run after the new wake re-claimed the seat.
        // Stale teardown events (TTS stop, late RUN_END) would otherwise consume the
        // new session's winner flag and broadcast END mid-session, un-dimming the
        // yielding peers. Defer without consuming: the new run's own end announces.
        val satelliteState = _state.value
        if (chorusRewakeHandoff ||
            stateMachine.isWaking ||
            satelliteState == Listening ||
            satelliteState == Processing
        ) {
            Log.d(TAG, "chorus session end deferred: session active state=$satelliteState key=$key")
            return
        }
        chorusWonSession = false
        stopChorusAliveHeartbeat()
        WakeWordArbiter.setLocalChorusWinner(false)
        Log.d(TAG, "chorus session end announce key=$key")
        WakeWordArbiter.announceSessionEnd(context, key)
    }

    /**
     * A clearly-louder device detected the same wake after our collect window closed
     * and out-scored us. Yield the seat: this only fires while still Listening — once
     * we are Responding, cutting audible speech is worse than a double answer.
     */
    private suspend fun onChorusSeatRevoked(wakeKey: String) {
        if (!multiDeviceArbiterEnabled || !chorusWonSession) return
        if (conversationEngineHold) {
            Log.d(TAG, "chorus seat revoked mid conversation-engine key=$wakeKey")
            chorusWonSession = false
            stopChorusAliveHeartbeat()
            WakeWordArbiter.setLocalChorusWinner(false)
            WakeWordArbiter.markPeerChorusSession(wakeKey)
            ChorusWakeBlurService.show(context, sessionAccentColor)
            ModConversationEngine.revokeCurrent(ModConversationEngine.Reasons.REVOKED_CHORUS)
            return
        }
        if (_state.value != Listening) {
            Log.d(TAG, "chorus seat revoke ignored: state=${_state.value}")
            return
        }
        Log.d(TAG, "chorus seat revoked mid-listen key=$wakeKey, yielding")
        chorusWonSession = false
        stopChorusAliveHeartbeat()
        WakeWordArbiter.setLocalChorusWinner(false)
        WakeWordArbiter.markPeerChorusSession(wakeKey)
        ChorusWakeBlurService.show(context, sessionAccentColor)
        // Not the seat owner anymore: stopSatellite's announce is skipped ("not winner"),
        // so no END is broadcast while the real winner is still serving the wake.
        stopSatellite()
    }
    @Volatile private var pipelineAcceptingAudio = false
    private val pendingMicAudioBuffer = ArrayDeque<ByteString>()
    private val pendingMicAudioLock = Any()
    private val micAudioDeliveryGate = MicAudioDeliveryGate()
    private val pendingMicAudioFlushMutex = kotlinx.coroutines.sync.Mutex()
    private var pendingMicAudioBytes = 0

    /**
     * Mic frames captured while the wake earcon holds the uplink closed, plus a local
     * speech-onset gate over them. On uplink open the buffer is flushed to HA only when
     * the gate latched real speech — a silent wake sends nothing, so HA's VAD starts
     * from a clean stream and the user can take their time. See [captureWakePreRollFrame].
     */
    private val wakePreRollLock = Any()
    private val wakePreRollBuffer = WakePreRollBuffer()
    private var wakePreRollSupportsSpeechRescue = false
    private var wakePreRollStartedAtMs = 0L
    /** Bundled VAD, loaded once; shared by pre-roll rescue and the wake-cue scan. */
    private val wakePreRollVad by lazy {
        com.example.ava.microwakeword.WakePreRollSpeechDetector.Loader(context.assets)
    }
    /**
     * Did anyone audibly speak during the current wake session? Feeds the wake
     * learner so a blank transcript is only a negative when the room was quiet.
     */
    private val wakeSessionSpeechEvidence = com.example.ava.wakelearn.WakeSessionSpeechEvidence()
    
    private var savedHaVolumeLevel: Float = -1f

    private var isAskQuestionMode = false
    private var askQuestionTimeoutJob: kotlinx.coroutines.Job? = null
    
    private val sttEntity = TextSensorEntity(
        key = "voice_command".hashCode(),
        name = context.getString(R.string.entity_voice_command),
        objectId = "voice_command",
        icon = "mdi:microphone-message",
        entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC
    )
    
    private val ttsEntity = TextSensorEntity(
        key = "assistant_response".hashCode(),
        name = context.getString(R.string.entity_assistant_response),
        objectId = "assistant_response",
        icon = "mdi:message-reply-text",
        entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC
    )

    private val voicePrintStatusEntity = TextSensorEntity(
        key = "voice_print_status".hashCode(),
        name = context.getString(R.string.entity_voice_print_status),
        objectId = "voice_print_status",
        icon = "mdi:account-check",
        entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC,
        initialState = "disabled"
    )

    private val voicePrintManager by lazy {
        VoicePrintManager(context, microphoneSettingsStore)
    }

    /**
     * On-device wake personalization: labels every accepted wake's classifier window from
     * the session that follows (transcript = genuine, no speech / immediate "stop" =
     * false wake) and refits the engine's verifier head from the household's own audio.
     */
    val wakeLearner: com.example.ava.wakelearn.WakeLearner by lazy {
        com.example.ava.wakelearn.WakeLearnTuning.load(context)
        val store = com.example.ava.wakelearn.WakeLearnStore.forContext(context)
        val openProvider = com.example.ava.microwakeword.WakeWordProviderFactory.openWakeWordProvider(context)
        com.example.ava.wakelearn.WakeLearner(
            store = store,
            scope = scope,
            priorHead = { engine, id ->
                when (engine) {
                    com.example.ava.settings.WakeWordEngine.OPEN_WAKE_WORD -> openProvider.loadFactoryVerifier(id)
                    com.example.ava.settings.WakeWordEngine.MICRO_WAKE_WORD -> null
                }
            },
            vetoThreshold = { _, id ->
                com.example.ava.openwakeword.OpenWakeWordCutoffPolicy.verifierThreshold(
                    audioInput.extraStrictnessFor(id),
                )
            },
            onHeadUpdated = { _, id -> audioInput.reloadWakeWordVerifier(id) },
            onReport = { engine, id, report ->
                com.example.ava.wakelearn.WakeLearnTuning.recordReport(context, engine, id, report)
            },
        )
    }

    /** Settings page: forget learned wakes and heads for every wake word and fall back to factory. */
    fun resetWakeLearning() {
        com.example.ava.wakelearn.WakeLearnStore.forContext(context).clearAll()
        com.example.ava.wakelearn.WakeLearnTuning.clearReports(context)
        for (id in audioInput.activeWakeWords.value) audioInput.reloadWakeWordVerifier(id)
    }

    /** Settings page: veto offset / strictness changed; rebuild the running detector's heads. */
    fun reloadWakeVerifiers() {
        for (id in audioInput.activeWakeWords.value) audioInput.reloadWakeWordVerifier(id)
    }

    private var voicePrintEntityRegistered = false
    private var audioEventEntityRegistered = false

    private val audioEventManager by lazy {
        AudioEventManager(context, scope).also { manager ->
            manager.bindEntity(sensorsModule.audioEventEntity)
        }
    }

    /**
     * HA native intent first. On a pipeline miss, remote AI tools get the transcript.
     */
    private val localIntentFallback: com.example.ava.localllm.LocalIntentFallback by lazy {
        com.example.ava.localllm.LocalLlmManager.getInstance(context)
        com.example.ava.localllm.remote.RemoteAiManager.getInstance(context)
        com.example.ava.localllm.LocalIntentFallback(
            context = context,
            scope = scope,
            callService = { service, entityId, data -> callHaServiceReporting(service, entityId, data) },
            isSessionIdle = ::isRemoteAiSessionIdle,
            speak = { text, url -> playLocalReply(text, url) },
            speakStream = { source -> playLocalReplyStream(source) },
            onBusy = { hold -> setRemoteAiHold(hold) },
        )
    }

    private fun isRemoteAiSessionIdle(): Boolean =
        _state.value == Connected ||
            (_remoteAiHold.value && stateMachine.haPipelinePhase == HaPipelinePhase.Idle)

    private val stateMachine: VoiceSatelliteStateMachine by lazy {
        VoiceSatelliteStateMachine(
            scope = scope,
            audioInput = audioInput,
            player = player,
            state = _state,
            onStopSatellite = { stopSatellite() },
            onConfigInterceptAbort = {
                // Config wizard intercept: no Assist session — keep wake animation off.
                suppressWakeAnimationForConfigIntercept()
                stopSatellite(skipHaStop = true)
                // Teach wake-word wizard + Mod Store engines on every config intercept.
                com.example.ava.services.SatelliteSetupTipOverlayService
                    .maybeShowOnConfigIntercept(context)
            },
            shouldRenewListen = { shouldRenewFabListen() },
            onRenewListen = { renewFabListen() },
            onFabInjectOldSettled = { noteFabInjectOldSettled() },
            onTtsFinished = { onTtsFinished() },
            onConversationText = { role, text ->
                if (!searchListenOnly) {
                    this@VoiceSatellite.onConversationText?.invoke(role, text)
                }
            },
            onProcessingStarted = {
                if (!searchListenOnly) {
                    onProcessingStarted?.invoke()
                    dispatchVoicePipeline(ModVoicePipeline.Events.PROCESSING_STARTED)
                }
            },
            onConversationId = { id -> haConversationId = id },
            onTtsStreamStart = { if (!searchListenOnly) startPcmTtsStream() },
            onTtsStreamEnd = { finishPcmTtsStream() },
            onDiscardPendingPcmTts = { clearPendingPcmTtsChunks() },
            onFlushPendingPcmTts = { flushPendingPcmTtsChunks() },
            onAbandonPcmTts = { abandonPcmTtsStream() },
            onDeviceAction = { action -> onDeviceAction?.invoke(action) },
            onSendAudioEnd = { sendAudioEnd() },
            onSttText = { text: String ->
                val spoken = noteFabListenStt(text)
                if (spoken.isNotBlank()) HaPipelineConfigErrorTracker.onPipelineRecovered()
                sttEntity.updateState(com.example.ava.localllm.SttTranscript.forDisplay(spoken))
                localIntentFallback.onSttText(spoken)
                // Button turns: QuickWakeFabService collects this and shows the transcript in
                // its bubble; the reply goes to the bottom caption (caption-only mode).
                com.example.ava.services.VoiceSatelliteService.publishSttText(spoken)
            },
            onTtsText = { text ->
                if (!searchListenOnly) {
                    ttsEntity.updateState(text)
                    // HA default Assist intent pack: exact no_intent stock reply → teach better agents.
                    if (HaNoIntentDetector.matches(text)) {
                        com.example.ava.services.SatelliteSetupTipOverlayService
                            .maybeShowConversationNoIntentTip(context)
                    }
                }
            },
            onPipelineError = { code: String, message: String ->
                Log.e(TAG, "Pipeline error: [$code] $message")
                dispatchVoicePipeline(ModVoicePipeline.Events.PIPELINE_ERROR) {
                    putString(ModVoicePipeline.Extras.ERROR_CODE, code)
                    putString(ModVoicePipeline.Extras.ERROR_MESSAGE, message)
                }
                val handedOff = !searchListenOnly && localIntentFallback.onPipelineError(code)
                if (handedOff) {
                    Log.i(TAG, "pipeline error handed to local intent fallback code=$code")
                } else {
                    val hint = when {
                        code.startsWith("stt-no-text") -> context.getString(R.string.pipeline_error_no_speech)
                        code.contains("timeout") || code.contains("timed-out") -> context.getString(R.string.pipeline_error_no_response)
                        code.startsWith("stt-") || code.startsWith("intent-") || code.startsWith("tts") -> context.getString(R.string.pipeline_error_config)
                        code.startsWith("cloud-auth") -> context.getString(R.string.pipeline_error_cloud_auth)
                        code.startsWith("wake") || code.startsWith("duplicate") -> context.getString(R.string.pipeline_error_wake)
                        else -> context.getString(R.string.pipeline_error_unknown)
                    }
                    showErrorToast(hint)
                    HaPipelineConfigErrorTracker.onPipelineError(context, code)
                }
            },
            onTtsDurationReady = { durationMs, text ->
                if (!searchListenOnly) onTtsDurationReady?.invoke(durationMs, text)
            },
            onTtsPlaybackStarted = { text ->
                // Arm the builtin-stop onset guard at the moment audio actually
                // starts: the far-end edge in the capture loop can be bridged by
                // the wake chime's write-hold and miss this exact onset.
                audioInput.noteStopOnsetGuard()
                if (!searchListenOnly) {
                    onTtsPlaybackStarted?.invoke(text)
                    dispatchVoicePipeline(ModVoicePipeline.Events.TTS_PLAYBACK_STARTED) {
                        putString(ModVoicePipeline.Extras.TTS_TEXT, text)
                    }
                }
            },
            onTtsProgressUpdate = { currentMs, totalMs, text ->
                if (!searchListenOnly) onTtsProgressUpdate?.invoke(currentMs, totalMs, text)
            },
            onTtsPlaybackError = { showErrorToast(context.getString(R.string.pipeline_error_tts_playback)) },
            onTtsPlaybackSettled = { announceChorusSessionEnd() },
        ).also { machine ->
            machine.replyInterceptor = {
                speech: String, stage: VoiceSatelliteStateMachine.ReplyStage ->
                if (searchListenOnly) {
                    VoiceSatelliteStateMachine.ReplyDecision.Pass
                } else {
                    localIntentFallback.intercept(speech, stage)
                }
            }
        }
    }

    private fun setRemoteAiHold(hold: Boolean) {
        if (_remoteAiHold.value == hold) return
        _remoteAiHold.value = hold
        Log.d(TAG, "remoteAiHold=$hold channel=${_state.value}")
        scope.launch { syncStopWordDetection() }
    }

    /**
     * Speak a reply Ava produced itself (local intent fallback). Runs as a self-contained
     * announcement after the HA run has unwound; never tells HA anything about it.
     */
    private suspend fun playLocalReply(text: String, url: String?) {
        ttsEntity.updateState(text)
        onConversationText?.invoke("assistant", text)
        if (url.isNullOrBlank()) return
        val current = _state.value
        if (current != Connected && current != Responding) {
            Log.w(TAG, "local reply skipped, satellite busy state=$current")
            return
        }
        beginLocalReplySession(text)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish: () -> Unit = {
            if (finished.compareAndSet(false, true)) {
                localReplyWatchdogJob?.cancel()
                localReplyWatchdogJob = null
                endLocalReplySession()
            }
        }
        player.ttsPlayer.playAnnouncement(mediaUrl = url, preannounceUrl = null) { finish() }
        armLocalReplyWatchdog(finished, finish)
    }

    /**
     * Streamed counterpart of [playLocalReply]: one Responding session, one or
     * more whole phrases. This side only plays what it is handed, in order, and
     * ends the session once the source runs dry — or at once if the turn is cancelled.
     */
    private suspend fun playLocalReplyStream(source: com.example.ava.localllm.LocalReplySource) {
        var segment = source.next() ?: return
        val current = _state.value
        if (current != Connected && current != Responding) {
            Log.w(TAG, "streamed local reply skipped, satellite busy state=$current")
            while (source.next() != null) { /* drain so the producer can finish */ }
            return
        }
        beginLocalReplySession(segment.text)
        try {
            while (true) {
                val spoken = source.spoken
                ttsEntity.updateState(spoken)
                // Caption follows the phrase being spoken, not the accumulated transcript.
                onConversationText?.invoke("assistant", segment.text)
                stateMachine.prepareLocalReplyPlayback(segment.text)
                audioInput.noteTtsTextForWakeEchoRisk(segment.text)
                val url = segment.url
                if (!url.isNullOrBlank()) playLocalReplySegment(url)
                segment = source.next() ?: break
            }
        } finally {
            localReplyWatchdogJob?.cancel()
            localReplyWatchdogJob = null
            if (kotlinx.coroutines.currentCoroutineContext()[Job]?.isCancelled == true) player.ttsPlayer.stop()
            endLocalReplySession()
        }
    }

    /** Plays one synthesised sentence and returns when it ended, stalled, or was cancelled. */
    private suspend fun playLocalReplySegment(url: String) {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            val finish: () -> Unit = {
                if (finished.compareAndSet(false, true)) {
                    localReplyWatchdogJob?.cancel()
                    localReplyWatchdogJob = null
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
            }
            cont.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) player.ttsPlayer.stop()
            }
            player.ttsPlayer.playAnnouncement(mediaUrl = url, preannounceUrl = null) { finish() }
            armLocalReplyWatchdog(finished, finish)
        }
    }

    private fun beginLocalReplySession(firstText: String) {
        _state.value = Responding
        // Smart continue follows ava_turn. Claimed HA replies force this false;
        // the model's call is what reopens the mic after this playback.
        stateMachine.continueConversation =
            stateMachine.smartContinueOn() && AvaTurnTools.keepListening()
        stateMachine.prepareLocalReplyPlayback(firstText)
        audioInput.noteTtsTextForWakeEchoRisk(firstText)
        player.duck()
        maybeApplyWhisperResponse()
    }

    @Volatile private var speechInsertHandoff = false

    private fun endLocalReplySession() {
        if (speechInsertHandoff) return
        scope.launch {
            if (speechInsertHandoff || _state.value == Listening) return@launch
            val keepListening = continuousForVoiceWake() &&
                player.enableContinuousConversation.get() &&
                stateMachine.smartContinueOn() &&
                stateMachine.continueConversation
            endWhisperSession()
            if (keepListening) {
                stateMachine.continueConversation = true
                if (!suppressWakeChrome()) {
                    val listening = onListeningStarted
                    if (listening != null) {
                        listening.invoke(sessionAccentColor)
                        wakeAnimationPresented = true
                    } else {
                        wakeAnimationPresented = false
                    }
                }
                player.playContinuousPromptSound {
                    scope.launch { wakeSatellite(isContinueConversation = true) }
                }
                return@launch
            }
            player.unDuck()
            restoreHaMediaPlayerVolume()
            if (_state.value == Responding) _state.value = Connected
            restorePreferredMediaRoute()
            audioInput.resetWakeWordDetector()
            dispatchVoicePipeline(ModVoicePipeline.Events.SESSION_ENDED)
            onConversationEnd?.invoke()
        }
    }

    /**
     * Same tts_proxy URLs as HA: ExoPlayer often never reaches STATE_ENDED,
     * and a swallowed miss can leave the player looking like it is still
     * fetching a file that was already returned.
     */
    private fun armLocalReplyWatchdog(finished: java.util.concurrent.atomic.AtomicBoolean, finish: () -> Unit) {
        localReplyWatchdogJob?.cancel()
        localReplyWatchdogJob = scope.launch {
            delay(10_000L)
            if (finished.get()) return@launch
            if (!player.ttsPlayer.isPlaying && !player.ttsPlayer.isPaused) {
                Log.w(TAG, "local reply TTS never started after URL was fetched; finishing")
                player.ttsPlayer.stop()
                finish()
                return@launch
            }
            var lastPos = player.ttsPlayer.currentPosition
            var lastAdvanceAt = android.os.SystemClock.elapsedRealtime()
            while (!finished.get()) {
                delay(500L)
                if (player.ttsPlayer.isPlaying) {
                    lastAdvanceAt = android.os.SystemClock.elapsedRealtime()
                    continue
                }
                val pos = player.ttsPlayer.currentPosition
                if (pos > lastPos) {
                    lastPos = pos
                    lastAdvanceAt = android.os.SystemClock.elapsedRealtime()
                    continue
                }
                if (android.os.SystemClock.elapsedRealtime() - lastAdvanceAt >= 12_000L) {
                    Log.w(TAG, "local reply TTS stalled; finishing")
                    player.ttsPlayer.stop()
                    finish()
                    return@launch
                }
            }
        }
    }
    
    
    private val bluetoothManager = BluetoothPresenceManager.getInstance(context)
    private val bluetoothModule by lazy { VoiceSatelliteBluetooth(context, scope, this, bluetoothManager) }
    private val sensorsModule by lazy { VoiceSatelliteSensors(context, scope, this, experimentalSettingsStore) }
    private val cameraModuleLazy = lazy { VoiceSatelliteCamera(context, scope, this, experimentalSettingsStore) }
    private val cameraModule by cameraModuleLazy
    private val screenModule by lazy { VoiceSatelliteScreen(context, scope, this, experimentalSettingsStore) }
    private val diagnosticsModule by lazy { VoiceSatelliteDiagnostics(context, scope, this, experimentalSettingsStore) }
    private val occupancyModule by lazy { VoiceSatelliteOccupancy(context, scope, this, experimentalSettingsStore) }
    private val updateModule by lazy { VoiceSatelliteUpdate(context, scope, this) }

    fun refreshFirmwareUpdateEntity() {
        if (updateModule.isRegistered()) {
            updateModule.requestCheck()
        }
    }
    
    
    private fun initBluetoothTracking() {
        bluetoothManager.startClaimSync(scope)
        bluetoothModule.init { sendMessage(it) }
        startScannerConfiguredModeSync()
    }
    
    private fun initSnapshotFeature() {
        cameraModule.initSnapshot()
    }
    
    
    private fun initVideoFeature() {
        cameraModule.initVideo()
    }
    
    var onConversationText: ((role: String, text: String) -> Unit)? = null

    var onListeningStarted: ((accentColor: Int) -> Unit)? = null

    /**
     * Music overlay (Sendspin / Mass) should duck at the same instant as
     * [player.duck] — button STT must not wait for HA RUN_START.
     */
    var onVoiceOverlayDuck: (() -> Unit)? = null

    private var sessionAccentColor = com.example.ava.ui.VoiceAccentColors.WAKE_WORD_1
    @Volatile private var sessionAccentWakeIndexDiag = 0
    @Volatile private var sessionAccentAtMs = 0L
    /** True after wake ripple / floating listening UI was shown for this run. */
    private var wakeAnimationPresented = false

    /** Accent for the active wake session (ripple / edge glow). */
    val currentSessionAccentColor: Int
        get() = sessionAccentColor

    /**
     * Search-box listen: same HA STT uplink as a wake, but the run stops at STT_END.
     * Intent / TTS / wake ripple / captions never start. Cleared in [stopSatellite].
     */
    @Volatile
    private var searchListenOnly = false

    /**
     * True while the current turn was started from the Quick Wake button. The button is
     * its own status surface (voice level + transcript), so the wake ripple, Esper sphere
     * and floating captions yield for the whole turn. Set in [wakeSatellite] for a fresh
     * turn, kept across continue-conversation, cleared in [stopSatellite].
     * Button-only wake mode also sets this: every listen is button-owned, including the
     * HA entity, assist key, and announce→listen — there is no wake-word chrome.
     */
    @Volatile
    var isQuickWakeSession = false
        private set

    /** Joined STT from spliced FAB listen windows. */
    private val fabListenStitch = StringBuilder()
    private var fabListenRenewing = false
    /** [System.currentTimeMillis] of the current HA RUN_START. */
    private var fabListenOpenedAt = 0L
    /** First FAB listen of this turn — not reset on each 15s splice. */
    private var fabListenSessionAt = 0L
    /** This turn already punched past HA's first 15s window. */
    private var fabListenSpliced = false
    /** I2 inject is queued: wait I1 settle + STT open + not Processing. */
    private var fabTextInjectArmed = false
    private var fabTextInjected = false
    private var fabInjectConfirmed = false
    private var fabInjectInFlight = false
    private var fabInjectOldSettled = false
    private var fabInjectSttOpen = false
    private var fabInjectJob: Job? = null
    private var fabInjectMonitor: Job? = null

    /** Button-only mode: the wake engine is off; no Esper / ripple for any turn. */
    private fun isButtonWakeMode(): Boolean =
        microphoneSettingsStore.getCached().wakeMode == WakeMode.BUTTON

    /** Hide wake-word chrome for FAB turns and for every turn while in button-only mode. */
    private fun suppressWakeChrome(): Boolean = isQuickWakeSession || isButtonWakeMode()

    /**
     * Continuous conversation follows a voice wake only. A press of the voice
     * button — hybrid or button-only — keeps that turn to itself.
     */
    private fun continuousForVoiceWake(): Boolean = !isQuickWakeSession && !isButtonWakeMode()

    private fun shouldRenewFabListen(): Boolean {
        if (searchListenOnly) return false
        val holdReleased = QuickWakePushToTalk.isHoldTurn && !QuickWakePushToTalk.isHolding
        if (
            !holdReleased &&
            FabHaInject.shouldHoldWindow(
                injectArmed = fabTextInjectArmed,
                injectDone = fabTextInjected,
                listening = _state.value == Listening,
                processing = _state.value == Processing,
                confirmed = fabInjectConfirmed,
            )
        ) {
            return true
        }
        val openedAt = fabListenOpenedAt
        val elapsed = if (openedAt == 0L) 0L else System.currentTimeMillis() - openedAt
        val sessionAt = fabListenSessionAt
        val sessionElapsed =
            if (sessionAt == 0L) elapsed else System.currentTimeMillis() - sessionAt
        return FabListenRenew.shouldRenew(
            holding = QuickWakePushToTalk.isHolding,
            fabSession = isQuickWakeSession,
            listening = _state.value == Listening,
            msSinceRunStart = elapsed,
            holdTurn = QuickWakePushToTalk.isHoldTurn,
            processing = _state.value == Processing,
            msSinceSessionStart = sessionElapsed,
            alreadySpliced = fabListenSpliced || fabTextInjectArmed || fabTextInjected,
        )
    }

    private fun noteFabListenStt(chunk: String): String {
        val next = FabListenRenew.stitch(fabListenStitch.toString(), chunk)
        fabListenStitch.setLength(0)
        if (next.isNotEmpty()) fabListenStitch.append(next)
        return next
    }

    private fun clearFabListenStitch() {
        fabListenStitch.setLength(0)
        fabListenOpenedAt = 0L
        fabListenSessionAt = 0L
        fabListenSpliced = false
        fabTextInjectArmed = false
        fabTextInjected = false
        fabInjectConfirmed = false
        fabInjectInFlight = false
        fabInjectOldSettled = false
        fabInjectSttOpen = false
        fabInjectJob?.cancel()
        fabInjectJob = null
        fabInjectMonitor?.cancel()
        fabInjectMonitor = null
        clearFabCapWatchdog()
    }

    /**
     * HA's 15s VAD window closed. Open the next STT window on the same FAB turn.
     *
     * Do not send [VoiceAssistantRequest] start=false here. aioesphomeapi treats
     * that as abort, which only enqueues audio-end on the **shared** queue.
     * handle_start and handle_stop run as concurrent HA tasks, so that None can
     * land in the new window and close it before the first spoken frame.
     * The old run has already left STT (VAD_END / STT_END); START-only lets
     * the next window own the queue. Leftover INTENT / TTS / stt-no-text
     * from the old run is dropped until the new window's own STT_END.
     */
    private suspend fun renewFabListen(): Boolean {
        // A concurrent caller (STT_END vs. 2 s fallback vs. stt-no-text error) is
        // already splicing; report success so nobody settles the turn under it.
        if (fabListenRenewing) return true
        if (!shouldRenewFabListen()) return false
        fabListenRenewing = true
        fabListenSpliced = true
        try {
            val seedInject = !fabInjectConfirmed
            clearFabCapWatchdog()
            reopenFabUplinkForPunchIn()
            fabInjectSttOpen = false
            fabInjectOldSettled = false
            if (seedInject) {
                fabTextInjectArmed = true
                armFabInjectMonitor()
                Log.d(TAG, "FAB listen renew — queue inject after old settle + next STT")
            } else {
                fabInjectJob?.cancel()
                fabInjectJob = null
                fabTextInjectArmed = false
                fabTextInjected = false
                Log.d(TAG, "FAB listen renew — next window after HA inject")
            }
            // Phase first so the old run's RUN_END cannot look like a finished turn.
            stateMachine.prepareForNewWake()
            stateMachine.markInterruptedPipeline()
            stateMachine.markRenewInFlight()
            _state.value = Listening
            haReceivedPipelineEvent = false
            listeningStartedAt = System.currentTimeMillis()
            sendVoiceAssistantStartRequest(useConversationId = true)
            stateMachine.setWakePhase(false)
            audioInput.isStreaming = true
            synchronized(wakePreRollLock) { wakeSessionSpeechEvidence.arm() }
            armFabRenewRunStartWatchdog()
        } finally {
            fabListenRenewing = false
        }
        return true
    }

    private var fabRenewRunStartWatchdog: kotlinx.coroutines.Job? = null

    /**
     * The spliced start request has no reply path of its own: if HA never answers
     * with RUN_START (busy tearing down the aborted run, pipeline refused, link
     * hiccup) the turn would sit in Listening forever with the mic streaming to
     * nobody and the FAB stuck "active". End it like a normal failed turn instead.
     */
    private fun armFabRenewRunStartWatchdog() {
        fabRenewRunStartWatchdog?.cancel()
        fabRenewRunStartWatchdog = scope.launch {
            kotlinx.coroutines.delay(FAB_RENEW_RUN_START_TIMEOUT_MS)
            if (_state.value == Listening && stateMachine.isWaking && isQuickWakeSession) {
                Log.w(TAG, "FAB listen renew: no RUN_START within ${FAB_RENEW_RUN_START_TIMEOUT_MS}ms, ending turn")
                stopSatellite()
            }
        }
    }

    private fun clearFabRenewRunStartWatchdog() {
        fabRenewRunStartWatchdog?.cancel()
        fabRenewRunStartWatchdog = null
    }

    private var fabCapWatchdog: Job? = null

    /**
     * HA's 15s cap sometimes emits STT_END with no VAD_END, and sometimes
     * emits neither. If we are still in a FAB listen past the cap, splice
     * instead of sitting deaf on a closed window.
     */
    private fun armFabCapWatchdog() {
        fabCapWatchdog?.cancel()
        fabCapWatchdog = scope.launch {
            delay(FabListenRenew.HA_VAD_TIMEOUT_MS + 400L)
            if (!isQuickWakeSession || fabListenRenewing) return@launch
            if (!stateMachine.heardHaVad) return@launch
            if (stateMachine.isFabRenewArmed()) return@launch
            if (!shouldRenewFabListen()) return@launch
            Log.d(TAG, "FAB listen cap watchdog — HA sent no VAD/STT end, splicing")
            renewFabListen()
        }
    }

    private fun clearFabCapWatchdog() {
        fabCapWatchdog?.cancel()
        fabCapWatchdog = null
    }

    private fun reopenFabUplinkForPunchIn() {
        synchronized(pendingMicAudioLock) {
            pipelineAcceptingAudio = false
            if (!micAudioDeliveryGate.canBuffer) micAudioDeliveryGate.reset()
        }
    }

    /**
     * HA satellite STT is open. One short spacer on the Assist uplink, then
     * the previous window's text goes in as a conversation user turn — not
     * LAN voice, not house-TTS PCM.
     */
    fun noteFabInjectOldSettled() {
        if (!fabTextInjectArmed) return
        fabInjectOldSettled = true
        flushFabInjectQueue()
    }

    private fun isPastFabHaCap(): Boolean {
        if (fabListenSpliced) return true
        val sessionAt = fabListenSessionAt
        if (sessionAt == 0L) return false
        return System.currentTimeMillis() - sessionAt >= FabListenRenew.HA_VAD_TIMEOUT_MS
    }

    private fun armFabInjectMonitor() {
        if (fabInjectMonitor?.isActive == true) return
        fabInjectMonitor = scope.launch {
            while (isQuickWakeSession && !fabInjectConfirmed) {
                val text = fabListenStitch.toString().trim()
                if (
                    !FabHaInject.shouldRetry(
                        pastHaCap = isPastFabHaCap(),
                        confirmed = fabInjectConfirmed,
                        hasText = text.isNotEmpty(),
                    )
                ) {
                    delay(FabHaInject.RETRY_MS)
                    continue
                }
                Log.d(TAG, "FAB HA inject monitor — not confirmed, punch again")
                if (_state.value == Processing) _state.value = Listening
                injectFabListenIntoPipeline()
                if (!fabInjectConfirmed) delay(FabHaInject.RETRY_MS)
            }
        }
    }

    private fun flushFabInjectQueue() {
        if (!fabTextInjectArmed || fabInjectConfirmed) return
        if (fabInjectJob?.isActive == true) return
        val pastCap = isPastFabHaCap()
        if (!pastCap && !fabInjectOldSettled && !fabInjectSttOpen) {
            Log.d(TAG, "FAB HA inject queued — waiting for I1 settle / I2 STT")
            return
        }
        Log.d(
            TAG,
            "FAB HA inject queued — settled=$fabInjectOldSettled stt=$fabInjectSttOpen " +
                "ui=${_state.value} pastCap=$pastCap",
        )
        fabInjectJob = scope.launch {
            delay(FabHaInject.SETTLE_MS)
            while (fabTextInjectArmed && !fabInjectConfirmed) {
                val processing = _state.value == Processing
                val past = isPastFabHaCap()
                if (
                    !FabHaInject.canInsert(
                        oldWindowSettled = fabInjectOldSettled,
                        sttOpen = fabInjectSttOpen,
                        processing = processing,
                        pastHaCap = past,
                    )
                ) {
                    Log.d(
                        TAG,
                        "FAB HA inject wait — settled=$fabInjectOldSettled " +
                            "stt=$fabInjectSttOpen processing=$processing",
                    )
                    delay(FabHaInject.SETTLE_MS)
                    continue
                }
                injectFabListenIntoPipeline()
                if (fabInjectConfirmed) return@launch
                delay(FabHaInject.RETRY_MS)
            }
        }
    }

    private suspend fun injectFabListenIntoPipeline() {
        if (fabInjectInFlight || fabInjectConfirmed) return
        val text = fabListenStitch.toString().trim()
        if (text.isEmpty()) return
        fabInjectInFlight = true
        try {
            if (fabInjectSttOpen) {
                reopenFabUplinkForPunchIn()
                synchronized(pendingMicAudioLock) { micAudioDeliveryGate.onRunStart() }
                sendMessage(voiceAssistantAudio { data = FAB_SPACER_PCM })
                audioInput.isStreaming = true
                synchronized(pendingMicAudioLock) { pipelineAcceptingAudio = true }
            }
            val ok = HaManager.get()?.processConversationAck(
                text = text,
                conversationId = haConversationId.takeIf { it.isNotBlank() },
            ) == true
            if (ok) {
                fabTextInjectArmed = false
                fabTextInjected = true
                fabInjectConfirmed = true
                if (_state.value == Processing) _state.value = Listening
                Log.d(TAG, "FAB HA inject confirmed — conversation text len=${text.length}")
            } else {
                fabTextInjected = false
                fabTextInjectArmed = true
                if (_state.value == Processing) _state.value = Listening
                Log.w(TAG, "FAB HA inject missed — will retry text len=${text.length}")
            }
        } finally {
            fabInjectInFlight = false
        }
    }

    var onProcessingStarted: (() -> Unit)? = null

    var onDeviceAction: ((com.example.ava.utils.LightKeywordDetector.DeviceAction) -> Unit)? = null
    
    var onConversationEnd: (() -> Unit)? = null
    
    var onTtsDurationReady: ((durationMs: Long, text: String) -> Unit)? = null
    var onTtsPlaybackStarted: ((text: String) -> Unit)? = null
    var onTtsProgressUpdate: ((currentMs: Long, totalMs: Long, text: String) -> Unit)? = null

    /** When Sendspin is actively playing; used to skip resume side-effects that steal focus. */
    var isSendspinProtocolActive: () -> Boolean = { false }

    private fun dispatchVoicePipeline(event: String, configure: Bundle.() -> Unit = {}) {
        if (!ModVoicePipeline.isActive(context)) return
        ModVoicePipeline.dispatch(context, event, Bundle().apply(configure))
    }

    private fun initEnvironmentSensors() {
        sensorsModule.init()
    }
    
    private fun stopEnvironmentSensors() {
        sensorsModule.stop()
    }
    
    fun updateSensorInterval(intervalSeconds: Int) {
        sensorsModule.updateSensorInterval(intervalSeconds)
    }
    
    private val modEntities = mutableListOf<com.example.ava.esphome.entities.Entity>()
    private data class ModEntityRefresher(
        val refresh: () -> Unit,
        val intervalMs: Long
    )
    private val modEntityRefreshers = mutableListOf<ModEntityRefresher>()
    private val modEntityRefreshJobs = mutableListOf<kotlinx.coroutines.Job>()
    private val modEntityLoadLock = Any()

    /** Cancel refresh loops so they cannot outlive this satellite (or a reload). */
    private fun stopModEntityRefreshers() {
        modEntityRefreshJobs.forEach { it.cancel() }
        modEntityRefreshJobs.clear()
        modEntityRefreshers.clear()
    }
    
    private fun loadModEntities() {
        synchronized(modEntityLoadLock) {
        try {
            modEntities.forEach { removeEntity(it) }
            modEntities.clear()
            stopModEntityRefreshers()
            
            val modManager = ModManager.getInstance(context)
            val manifests = modManager.getEnabledManifests()
            for (manifest in manifests) {
                val classLoader = modManager.getModClassLoader(manifest.id)
                val configValues = modManager.getResolvedConfig(manifest.id, manifest)
                val refreshableEntities = ModEntityFactory.createRefreshableEntities(manifest, context, classLoader, configValues)
                for (re in refreshableEntities) {
                    addEntity(re.entity)
                    modEntities.add(re.entity)
                    re.refresh?.let { refresh ->
                        modEntityRefreshers.add(
                            ModEntityRefresher(
                                refresh = refresh,
                                intervalMs = (re.refreshIntervalMs ?: 30_000L).coerceAtLeast(50L)
                            )
                        )
                    }
                }
            }
            if (modEntities.isNotEmpty()) {
                Log.d(TAG, "Loaded ${modEntities.size} entities from ${manifests.size} mods")
            }

            if (ModBleAdvProxyBridge.isActive(context)) {
                ModBleAdvProxyBridge.createEntities(context, name).forEach { entity ->
                    addEntity(entity)
                    modEntities.add(entity)
                }
                Log.d(TAG, "Loaded ble-adv-proxy entities")
            }
            
            if (modEntityRefreshers.isNotEmpty()) {
                modEntityRefreshers
                    .groupBy { it.intervalMs }
                    .forEach { (intervalMs, refreshers) ->
                        // Child of EspHomeDevice.scope — cancelled by close() / scope.cancel().
                        val job = scope.launch(Dispatchers.IO) {
                            while (isActive) {
                                delay(intervalMs)
                                if (!isActive) break
                                refreshers.forEach { refresher ->
                                    try {
                                        refresher.refresh()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Mod entity refresh failed", e)
                                    }
                                }
                            }
                        }
                        modEntityRefreshJobs.add(job)
                    }
            }
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            Log.e(TAG, "Failed to load mod entities", t)
        }
        }
    }

    /**
     * HA-link idle timeout follows platform wakefulness, not panel power. A blanked
     * panel still has a live CPU and does not delay pings the way doze does.
     */
    override fun isDisplayInteractiveForHaLink(): Boolean {
        if (!com.example.ava.utils.ScreenControlUtils.deviceAwakeState.value) return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE)
            as? android.os.PowerManager ?: return true
        return powerManager.isInteractive
    }

    /**
     * Rebuild settings-driven HA entities and kick the native-API client.
     * Does not stop this satellite or Sendspin.
     */
    fun rediscoverHaEntities(
        voiceChannelEnabled: Boolean,
        experimentalSettingsData: ExperimentalSettings,
        browserSettingsData: BrowserSettings,
        screensaverSettingsData: ScreensaverSettings,
        playerSettingsData: PlayerSettings,
    ) {
        val rebuilt = VoiceSatelliteEntities.buildEntities(
            audioInput,
            player,
            voiceChannelEnabled,
            notificationSettingsStore,
            onRestartService,
            context,
            experimentalSettingsData,
            browserSettingsData,
            screensaverSettingsData,
            playerSettingsStore,
            playerSettingsData,
            microphoneSettingsStore.getFlow().map { it.wakeWordEngine },
            onWakeWordEngineChanged,
            { volume ->
                scope.launch { experimentalSettingsStore.setMicrophoneVolume(volume) }
            },
        )
        val (added, removed) = replaceStaticEntities(rebuilt)
        loadModEntities()
        requestHaClientReconnect()
        Log.i(
            TAG,
            "HA rediscover without protocol tear: static=${rebuilt.size} added=$added removed=$removed",
        )
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        HaManager.ensure(context)
        // Refills caches when the WebSocket hands entity state back to this channel.
        HaStateArbiter.setEsphomeResync { resubscribeAllHomeAssistantStates() }
        scope.launch {
            // Prime the routing sets and the WebSocket interest snapshot. These are normally
            // built when HA connects over ESPHome, but the WebSocket channel can be the only
            // one available and its consumers route through those same sets. Sending is a
            // no-op until a client attaches, so this is safe to run before any connection.
            resubscribeAllHomeAssistantStates()
        }
        scope.launch {
            val haSettings = HaSettingsStore(context.haSettingsStore)
            combine(
                haSettings.serverUrl,
                haSettings.accessToken,
                haSettings.mediaBearerEnabled,
            ) { url, token, mediaBearer ->
                Triple(url, token, mediaBearer)
            }.collect { (url, token, mediaBearer) ->
                HaMediaAuth.update(url, token, mediaBearer)
                val now = HaMediaAuth.signedIn
                val prev = lastCameraFetchSignedIn
                lastCameraFetchSignedIn = now
                if (prev != null && prev != now) {
                    rebindHaMediaFetchPipes()
                }
            }
        }
        if (voiceChannelEnabled) {
            addEntity(sttEntity)
            addEntity(ttsEntity)
            if (microphoneSettingsStore.getCached().voicePrintEnabled) {
                addEntity(voicePrintStatusEntity)
                voicePrintEntityRegistered = true
            }
            addEntity(ButtonEntity(
                key = "manual_wake".hashCode(),
                name = context.getString(R.string.entity_manual_wake),
                objectId = "manual_wake",
                icon = "mdi:microphone-message",
                entityCategory = com.example.esphomeproto.api.EntityCategory.ENTITY_CATEGORY_NONE,
                onPress = { triggerManualWake() }
            ))
        }
        
        val modsAtStart = ModManager.getInstance(context)
        val modEntitiesLoadedAt = modsAtStart.registryGeneration
        loadModEntities()
        scope.launch {
            // no_restart leaves this satellite up. Registry changes must drop the
            // disabled mod's entities, or the next sensor refresh calls getInstance()
            // and the manager starts again.
            modsAtStart.registryGenerationFlow.collect { generation ->
                if (generation == modEntitiesLoadedAt) return@collect
                loadModEntities()
                requestHaClientReconnect()
            }
        }

        if (voiceChannelEnabled) {
            audioInput.audioEventWindowListener = { pcm ->
                audioEventManager.onWindowReady(pcm)
            }
            applyAudioEventSettings(experimentalSettingsStore.getCached())
            scope.launch {
                experimentalSettingsStore.getFlow().collect { expSettings ->
                    applyAudioEventSettings(expSettings)
                }
            }
        }
        
        var haRemoteUrlInitialized = false
        var haRemoteUrlRightInitialized = false

        /**
         * Dual-pane HA URL policy:
         * - one side blank → collapse that pane (other fills)
         * - both blank → turn off browser_display and hide the overlay
         * - identical left/right addresses → [WebViewService] shares one live session
         *   (avoids dual HA WS / steward competition on the same dashboard)
         */
        suspend fun applyHaRemoteBrowserUrls(leftUrl: String, rightUrl: String, isRestoring: Boolean) {
            val browserSettingsStore = com.example.ava.settings.BrowserSettingsStore(context)
            val browserSettings = browserSettingsStore.get()
            val splitActive =
                browserSettings.splitViewEnabled &&
                    browserSettings.browserEngine != com.example.ava.webcompat.BrowserEngine.GECKO
            val left = leftUrl.trim()
            val right = if (splitActive) rightUrl.trim() else ""
            val anyUrl = left.isNotBlank() || right.isNotBlank()

            if (!anyUrl) {
                if (!isRestoring) {
                    browserSettingsStore.enableBrowserVisible.set(false)
                }
                com.example.ava.services.WebViewService.hide(context)
                return
            }

            if (isRestoring && !browserSettings.enableBrowserVisible) {
                return
            }
            if (!isRestoring) {
                browserSettingsStore.enableBrowserVisible.set(true)
            }
            if (isRestoring) {
                // Seed the URL only. Cold-start overlay restore in VoiceSatelliteService
                // attaches the window first so later remembered layers stack above it.
                return
            }

            if (!splitActive) {
                com.example.ava.services.WebViewService.showOrRefresh(context, left)
            } else {
                com.example.ava.services.WebViewService.applyHaRemoteUrls(context, left, right)
            }
        }

        player.onHaRemoteUrlChanged = { url ->
            scope.launch {
                val savedUrl = settingsStore.get().haRemoteUrl
                // First callback is the boot seed. It must not open the window:
                // cold-start restore has not armed the split yet, so a show here
                // paints the saved full-screen frame. Equality with the stored
                // string fails once the proxy unmaps the URL, and that took the
                // show-now branch.
                val isRestoring = !haRemoteUrlInitialized
                haRemoteUrlInitialized = true
                if (isRestoring && url != savedUrl) {
                    Log.d(TAG, "Cold-start HA URL differs from stored after unmap; still deferring overlay show")
                }

                settingsStore.saveHaRemoteUrl(url)
                val right = settingsStore.get().haRemoteUrlRight
                applyHaRemoteBrowserUrls(url, right, isRestoring)
            }
        }
        player.onHaRemoteUrlRightChanged = { url ->
            scope.launch {
                val isRestoring = !haRemoteUrlRightInitialized
                haRemoteUrlRightInitialized = true

                settingsStore.saveHaRemoteUrlRight(url)
                val browserSettingsStore = com.example.ava.settings.BrowserSettingsStore(context)
                val browserSettings = browserSettingsStore.get()
                val splitActive =
                    browserSettings.splitViewEnabled &&
                        browserSettings.browserEngine != com.example.ava.webcompat.BrowserEngine.GECKO
                if (!splitActive) return@launch

                val left = settingsStore.get().haRemoteUrl
                applyHaRemoteBrowserUrls(left, url, isRestoring)
            }
        }
        player.onHaMediaPlayPause = {
            scope.launch { callHaMediaPlayerService("media_play_pause") }
        }
        player.onHaMediaPrevious = {
            scope.launch { callHaMediaPlayerService("media_previous_track") }
        }
        player.onHaMediaNext = {
            scope.launch { callHaMediaPlayerService("media_next_track") }
        }
        player.onHaSetVolume = { volume ->
            scope.launch { callHaMediaPlayerServiceWithData("volume_set", mapOf("volume_level" to volume.toString())) }
        }
        player.onHaSetRepeat = { mode ->
            scope.launch { callHaMediaPlayerServiceWithData("repeat_set", mapOf("repeat" to mode)) }
        }
        player.onHaSetShuffle = { shuffle ->
            scope.launch { callHaMediaPlayerServiceWithData("shuffle_set", mapOf("shuffle" to shuffle.toString())) }
        }
        
        val settings = experimentalSettingsStore.getCached()
        val updateSettings =
            UpdateSettingsStore(context.applicationContext.updateSettingsStore).getCached()

        // HA update entity is on by default; can be disabled from Software Update settings.
        if (updateSettings.haUpdateEntity) {
            updateModule.init()
        } else {
            updateModule.dispose()
        }
        
        if (settings.diagnosticSensorEnabled) {
            initDiagnosticSensors(settings)
        } else {
            // Intent Launcher lives on the diagnostics module, but it is not a
            // diagnostic sensor. Publish it even when that master switch is off.
            diagnosticsModule.publishIntentLauncher(settings)
        }
        // Media keys are not diagnostic sensors. They publish only when the
        // media-key switch and its Home Assistant display switch are both on.
        diagnosticsModule.publishMediaControls(settings)
        if (settings.occupancyEnabled) {
            occupancyModule.init(settings)
        }
        if (settings.environmentSensorEnabled) {
            sensorsModule.ensureEntitiesRegistered(settings)
        }
        if (settings.proximitySensorEnabled) {
            screenModule.ensureEntitiesRegistered(settings)
        }
        
        scope.launch {
            val savedSettings = settingsStore.get()
            if (savedSettings.haRemoteUrl.isNotEmpty()) {
                player.setHaRemoteUrl(savedSettings.haRemoteUrl)
            }
            if (savedSettings.haRemoteUrlRight.isNotEmpty()) {
                player.setHaRemoteUrlRight(savedSettings.haRemoteUrlRight, notifyCallback = false)
            }
            val capabilities = com.example.ava.utils.DeviceCapabilities
            
            if (voiceChannelEnabled) {
                audioInput.setMicrophoneVolume(settings.microphoneVolume)
            }

            if (voiceChannelEnabled) {
                scope.launch {
                    val initial = microphoneSettingsStore.get()
                    voicePrintManager.onIdentifiedUserLabel = { user ->
                        if (voiceChannelEnabled && voicePrintManager.shouldEmitHaWakeUserLabel()) {
                            scope.launch { fireVoicePrintWakeEvent(user) }
                        }
                    }
                    voicePrintManager.applySettings(
                        VoicePrintManager.SettingsSnapshot(
                            enabled = initial.voicePrintEnabled,
                            enrollmentMode = initial.voicePrintEnrollmentMode,
                            manualWakeVerifyEnabled = initial.voicePrintManualWakeVerifyEnabled,
                            userNames = initial.voicePrintUserNames,
                            wakeWordEngine = initial.wakeWordEngine,
                        ),
                    )
                    audioInput.setVoicePrintCaptureEnabled(initial.voicePrintEnabled)
                    voicePrintManager.settingsFlow.collect { snapshot ->
                        voicePrintManager.applySettings(snapshot)
                        audioInput.setVoicePrintCaptureEnabled(snapshot.enabled)
                        if (snapshot.enabled) {
                            if (!voicePrintEntityRegistered) {
                                addEntity(voicePrintStatusEntity)
                                voicePrintEntityRegistered = true
                            }
                        } else if (voicePrintEntityRegistered) {
                            removeEntity(voicePrintStatusEntity)
                            voicePrintEntityRegistered = false
                        }
                    }
                }
                scope.launch {
                    combine(
                        voicePrintManager.settingsFlow,
                        voicePrintManager.state,
                    ) { settings, state ->
                        if (!settings.enabled) {
                            "disabled"
                        } else {
                            state.statusText()
                        }
                    }.distinctUntilChanged().collect { status ->
                        voicePrintStatusEntity.updateState(status)
                    }
                }

            }
            
            // Camera Stream mod owns Camera2 — force core remote camera off for upgrades.
            // No service restart here (we are already inside start).
            com.example.ava.mods.ModCameraStreamBridge.applyHostPolicy(
                context,
                restartService = false,
            )

            if (settings.cameraEnabled
                && !com.example.ava.mods.ModCameraStreamBridge.isActive(context)
                && capabilities.hasCamera(context)
            ) {
                Log.d(TAG, "camera check: enabled=true hasCamera=true personDetection=${settings.personDetectionEnabled}")
                val hasSavedRecording = VoiceSatelliteCamera.hasSavedRecordingState(context)
                val cameraMode = try {
                    com.example.ava.settings.CameraMode.valueOf(settings.cameraMode)
                } catch (e: Exception) {
                    com.example.ava.settings.CameraMode.SNAPSHOT
                }
                Log.d(TAG, "camera mode=$cameraMode hasSavedRecording=$hasSavedRecording")
                val isVideoMode = hasSavedRecording || cameraMode == com.example.ava.settings.CameraMode.VIDEO
                when {
                    hasSavedRecording -> initVideoFeature()
                    cameraMode == com.example.ava.settings.CameraMode.SNAPSHOT -> initSnapshotFeature()
                    cameraMode == com.example.ava.settings.CameraMode.VIDEO -> initVideoFeature()
                }
                if (isVideoMode && settings.personDetectionEnabled) {
                    Log.d(TAG, "initializing person detection")
                    cameraModule.initDetection()
                    cameraModule.setFaceBoxEnabled(settings.faceBoxEnabled)
                    
                    scope.launch {
                        experimentalSettingsStore.faceBoxEnabled.collect { enabled ->
                            cameraModule.setFaceBoxEnabled(enabled)
                        }
                    }
                }
            }
            
            if (settings.environmentSensorEnabled && capabilities.hasAnyEnvironmentSensor(context)) {
                initEnvironmentSensors()
            }
            
            if (settings.proximitySensorEnabled && capabilities.hasProximitySensor(context)) {
                initProximitySensor()
            }
            if (settings.screenBrightnessEnabled) {
                initScreenBrightness()
            }
            if (settings.screenTouchSensorEnabled) {
                initScreenTouchSensor()
            }
            ScreenGestureRecognizer.sync(settings)
            if (settings.screenGestureEnabled) {
                initScreenGestureSensor()
            }
            initForceOrientation()
            
            if (bluetoothManager.isBluetoothAvailable()) {
                bluetoothModule.init { sendMessage(it) }
                startScannerConfiguredModeSync()
            }
            if (bluetoothManager.isDetectEnabled) {
                bluetoothManager.startClaimSync(scope)
            }
        }
        super.start()
        if (voiceChannelEnabled) {
            scope.launch {
                _state.collect { syncStopWordDetection() }
            }
            startAudioInput()
            startWakeWordSync()
            scope.launch(Dispatchers.IO) {
                // Clear custom wake cues ahead of the first wake so pre-roll rescue is
                // available from the start, not only from the second wake on.
                for (uri in listOf(player.wakeSound.get(), player.wakeSound2.get())) {
                    if (uri.isNotBlank() && !WakePreRollCuePolicy.isKnown(uri)) scanWakeCueForSpeech(uri)
                }
            }
        }
    }

    private fun applyAudioEventSettings(settings: ExperimentalSettings) {
        val config = settings.toAudioEventDetectionConfig()
        Log.i(TAG, "applyAudioEventSettings enabled=${config.enabled} sensitivity=${config.sensitivity}")
        audioEventManager.applySettings(config)
        audioInput.setAudioEventCaptureEnabled(config.enabled)
        if (config.enabled) {
            if (!audioEventEntityRegistered) {
                sensorsModule.registerAudioEventEntity()
                audioEventEntityRegistered = true
            }
        } else if (audioEventEntityRegistered) {
            sensorsModule.unregisterAudioEventEntity()
            audioEventEntityRegistered = false
        }
    }

    /** Stop inference starts after capture/listening, so the wake phrase cannot consume it. */
    private fun syncStopWordDetection() {
        if (!voiceChannelEnabled) {
            audioInput.setStopWordDetectionEnabled(false)
            audioInput.setSpeechInsertOpen(false)
            return
        }
        val voiceSessionActive = _state.value == Processing ||
            _state.value == Responding ||
            _remoteAiHold.value
        audioInput.setStopWordDetectionEnabled(timerFinished || voiceSessionActive)
        audioInput.setSpeechInsertOpen(
            voiceSessionActive &&
                continuousForVoiceWake() &&
                playerSettingsStore.getCached().enableContinuousConversation,
        )
    }

    private fun setTimerRinging(ringing: Boolean) {
        if (timerFinished == ringing) return
        timerFinished = ringing
        syncStopWordDetection()
        if (!ringing) {
            timerFinishedSoundUri = null
            // Every dismissal path funnels through here (stop word, wake word,
            // HA button, tile tap, disconnect); the tile tap is the only one
            // that clears the panel's ringing animation itself, so tell the
            // panel — otherwise its tiles keep breathing forever.
            QuickEntityOverlayService.notifyTimerRingStopped()
            ClockAlertOverlayService.onChimeStopped()
        }
    }
    
    /**
     * Push configured_mode whenever Ava's own scan-mode pref changes, from any
     * writer (HA select entity, settings screen, fleet).
     *
     * HA never asks for it again: once its config entry has a saved
     * bluetooth_scanning_mode it stops subscribing to scanner state entirely,
     * and while the entry is still unset its one-shot migration takes the first
     * configured_mode it sees. Without this, a mode picked in Ava's UI would
     * only reach HA on the next subscribe.
     */
    private fun startScannerConfiguredModeSync() {
        if (scannerConfiguredModeSyncJob?.isActive == true) return
        scannerConfiguredModeSyncJob = bluetoothManager.proxyScanModeFlow
            .drop(1)
            .distinctUntilChanged()
            .onEach {
                if (!advertisementsSubscribed) return@onEach
                sendMessage(bluetoothScannerStateResponse {
                    state = BluetoothScannerState.BLUETOOTH_SCANNER_STATE_RUNNING
                    mode = haRequestedScannerMode ?: configuredScannerModeProto()
                    configuredMode = configuredScannerModeProto()
                })
            }
            .launchIn(scope)
    }

    private fun startWakeWordSync() {
        audioInput.activeWakeWords.drop(1).onEach { newWakeWords ->
            val currentState = _state.value
            if (currentState != Disconnected && currentState != Stopped && newWakeWords.isNotEmpty()) {
                sendVoiceAssistantConfiguration()
            }
        }.launchIn(scope)
    }

    private suspend fun sendVoiceAssistantConfiguration() {
        val wakeWords = audioInput.availableWakeWords.distinctBy { it.id }
        val activeWakeWords = audioInput.activeWakeWords.value.distinct().take(2)

        // Never reply with an empty list: if wake words are not ready, skip the response so HA times out and keeps the previous options
        if (wakeWords.isEmpty()) {
            Log.w(TAG, "sendVoiceAssistantConfiguration: availableWakeWords is empty, skipping response to prevent HA clearing options")
            return
        }

        sendMessage(
            voiceAssistantConfigurationResponse {
                availableWakeWords += wakeWords.map {
                    voiceAssistantWakeWord {
                        id = it.id
                        wakeWord = it.wakeWord.wake_word
                        trainedLanguages += it.wakeWord.trained_languages.toList()
                    }
                }
                this.activeWakeWords += activeWakeWords
                maxActiveWakeWords = 2
            }
        )
    }

    suspend fun publishVoiceAssistantConfiguration() {
        if (voiceChannelEnabled) sendVoiceAssistantConfiguration()
    }
    
    private fun initDiagnosticSensors(settings: com.example.ava.settings.ExperimentalSettings) {
        diagnosticsModule.init(settings)
    }
    
    private fun initProximitySensor() {
        screenModule.initProximitySensor()
    }
    
    private fun stopProximitySensor() {
        screenModule.stopProximitySensor()
    }
    
    fun updateProximityPublishInterval(intervalSeconds: Int) {
        screenModule.updateProximityPublishInterval(intervalSeconds)
    }
    
    private fun initScreenBrightness() {
        screenModule.initScreenBrightness()
    }

    private fun initScreenTouchSensor() {
        screenModule.initScreenTouchSensor()
    }

    private fun initScreenGestureSensor() {
        screenModule.initScreenGestureSensor()
    }
    
    private fun initForceOrientation() {
        screenModule.initForceOrientation()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioInput() = server.isConnected
        .flatMapLatest { isConnected ->
            if (isConnected) audioInput.start() else emptyFlow()
        }
        .flowOn(Dispatchers.IO)
        .onEach {
            handleAudioResult(audioResult = it)
        }
        .launchIn(scope)

    override suspend fun onDisconnected() {
        localIntentFallback.cancel()
        val wasActive = _state.value == Listening || _state.value == Processing || _state.value == Responding
        isAskQuestionMode = false
        askQuestionTimeoutJob?.cancel()
        askQuestionTimeoutJob = null
        // Do not leave a hanging probe callId on this instance.
        cancelHaServiceCallsProbe()
        super.onDisconnected()
        ModBleAdvProxyBridge.onEspHomeDisconnected(context)
        haRequestedScannerMode = null
        advertisementsSubscribed = false
        stopBluetoothProxyScan()
        stateMachine.reset()
        resetPendingMicAudio()
        stopWakePreRoll()
        audioInput.isStreaming = false
        setTimerRinging(false)
        player.ttsPlayer.stop()
        
        if (wasActive) {
            showErrorToast(context.getString(R.string.pipeline_error_ha_disconnected))
        }

        // After HA disconnects, clear scene-placeholder subscription records; otherwise subscribeSceneEntities skips those entities on reconnect
        subscribedSceneRefs.clear()
        sceneEntityStateCache.clear()
        sceneEntityUnitCache.clear()
        sceneEntityAttributeCache.clear()
        
        announceChorusSessionEnd()
        onConversationEnd?.invoke()
        voicePrintManager.onPipelineSessionEnd()
    }

    override suspend fun getDeviceInfo(): DeviceInfoResponse = deviceInfoResponse {
        val settings = settingsStore.get()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val buildTimestamp = packageInfo.lastUpdateTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val buildTimeText = SimpleDateFormat("MMM dd yyyy, HH:mm:ss", Locale.ENGLISH)
            .format(Date(buildTimestamp))
        // ESPHome node names must be a lowercase slug so HA's (lowercased) service registration
        // matches ha-ble-adv's case-sensitive `<name>_adv_svc_v1` lookup. Existing installs may
        // still hold an uppercase stored name, so normalize on the way out.
        name = com.example.ava.settings.normalizeEspNodeName(this@VoiceSatellite.name)
        // Real ESPHome devices report YAML `esphome.friendly_name`; without it HA logs
        // "No friendly_name set..." on every reconnect. Send the stored name as typed
        // (spaces/case preserved) — for slug defaults this equals the node name, and for
        // hand-typed names HA finally displays what the user actually entered.
        friendlyName = this@VoiceSatellite.name
        usesPassword = false
        macAddress = settings.macAddress
        esphomeVersion = "2026.11.1"
        compilationTime = buildTimeText
        manufacturer = "Voice Assistant"
        model = "Android ${android.os.Build.VERSION.RELEASE}"
        if (voiceChannelEnabled) {
            voiceAssistantFeatureFlags = buildVoiceAssistantFeatureFlags()
        }
        if (FleetManager.isServingWebConsole()) {
            webserverPort = FleetManager.DEFAULT_PORT
        }
        if (bluetoothManager.isBluetoothAvailable() && bluetoothManager.isDetectEnabled) {
            bluetoothProxyFeatureFlags = BluetoothProxyFeature.PASSIVE_SCAN.flag or
                    BluetoothProxyFeature.ACTIVE_CONNECTIONS.flag or
                    BluetoothProxyFeature.REMOTE_CACHING.flag or
                    BluetoothProxyFeature.PAIRING.flag or
                    BluetoothProxyFeature.CACHE_CLEARING.flag or
                    BluetoothProxyFeature.RAW_ADVERTISEMENTS.flag or
                    BluetoothProxyFeature.FEATURE_STATE_AND_MODE.flag or
                    BluetoothProxyFeature.CONNECTION_PARAMS_SETTING.flag
            bluetoothMacAddress = settings.bluetoothMacAddress.ifBlank {
                com.example.ava.utils.getLegacyBluetoothMacAddressString(context)
            }
        }
    }
    
    private fun buildVoiceAssistantFeatureFlags(): Int {
        var flags = VoiceAssistantFeature.VOICE_ASSISTANT.flag or
            VoiceAssistantFeature.API_AUDIO.flag or
            VoiceAssistantFeature.TIMERS.flag or
            VoiceAssistantFeature.ANNOUNCE.flag or
            VoiceAssistantFeature.START_CONVERSATION.flag
        if (playerSettingsStore.getCached().enableStreamingTtsSubtitles) {
            flags = flags or VoiceAssistantFeature.SPEAKER.flag
        }
        return flags
    }


    /** Last scanner mode HA pinned via BluetoothScannerSetModeRequest (runtime echo). */
    private var haRequestedScannerMode: BluetoothScannerMode? = null

    /**
     * Whether HA currently holds the advertisement subscription.
     *
     * bleak_esphome treats any BluetoothScannerStateResponse as proof that the
     * subscription landed and stops its resubscribe watchdog, so unsolicited
     * pushes outside a live subscription would mask a rejected subscribe.
     */
    @Volatile private var advertisementsSubscribed = false
    private var scannerConfiguredModeSyncJob: Job? = null

    /**
     * configured_mode for BluetoothScannerStateResponse, from Ava's own selector.
     * Real ESPHome firmware reports its compiled YAML setting here, never a
     * SetModeRequest value; HA's one-shot options migration reads it and maps
     * PASSIVE to "passive" and anything else to "auto" — so "auto" and "active"
     * both report ACTIVE (Android's public scan API is active at the radio level
     * anyway), and only an explicit user "passive" reports PASSIVE.
     */
    private fun configuredScannerModeProto(): BluetoothScannerMode =
        if (bluetoothManager.proxyScanMode == "passive")
            BluetoothScannerMode.BLUETOOTH_SCANNER_MODE_PASSIVE
        else
            BluetoothScannerMode.BLUETOOTH_SCANNER_MODE_ACTIVE
    private var isVoiceAssistantSubscribed = false
    private var haVoiceAssistantSubscribeFlags = 0
    private var haReceivedPipelineEvent = false
    private var listeningStartedAt = 0L
    private var lastErrorToastMs = 0L
    private val errorToastCooldownMs = 3000L

    private fun showErrorToast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastErrorToastMs < errorToastCooldownMs) return
        lastErrorToastMs = now
        scope.launch(Dispatchers.Main) {
            AvaToast.show(context, msg, durationMs = AvaToast.LONG_MS)
        }
    }

    override suspend fun onConnected() {
        super.onConnected()
        ModBleAdvProxyBridge.onEspHomeConnected(context, name, scope, ::sendMessage)
        if (voiceChannelEnabled) sendVoiceAssistantConfiguration()
    }

    override suspend fun handleMessage(message: MessageLite) {
        val bluetoothProxyEnabled = bluetoothManager.isBluetoothAvailable() && bluetoothManager.isDetectEnabled
        if (!voiceChannelEnabled && (
                message is SubscribeVoiceAssistantRequest ||
                message is VoiceAssistantConfigurationRequest ||
                message is VoiceAssistantSetConfiguration ||
                message is VoiceAssistantAnnounceRequest ||
                message is VoiceAssistantEventResponse ||
                message is VoiceAssistantResponse ||
                message is VoiceAssistantAudio ||
                message is VoiceAssistantTimerEventResponse
            )
        ) {
            return
        }
        when (message) {
            is SubscribeVoiceAssistantRequest -> {
                isVoiceAssistantSubscribed = message.subscribe
                haVoiceAssistantSubscribeFlags = message.flags
                Log.d(
                    TAG,
                    "SubscribeVoiceAssistantRequest: subscribe=${message.subscribe}, flags=${message.flags}",
                )
                if (message.subscribe) {
                    sendVoiceAssistantConfiguration()
                    HaPipelineConfigErrorTracker.onPipelineRecovered()
                }
            }

            is VoiceAssistantConfigurationRequest -> {
                sendVoiceAssistantConfiguration()
            }

            is VoiceAssistantSetConfiguration -> {
                val wakeWordEngine = audioInput.wakeWordEngine
                val availableWakeWordIds = audioInput.availableWakeWords.map { it.id }.toSet()
                val requestedWakeWords =
                    compatibleWakeWordIdsForEngine(
                        wakeWordEngine,
                        message.activeWakeWordsList,
                        availableWakeWordIds
                    )
                val currentActive = audioInput.activeWakeWords.value
                // Init caution: if HA sends ids that do not map into this engine's catalog,
                // keep the current active set instead of wiping to empty. An explicit empty
                // list (user chose "No wake word") is still honored.
                val activeWakeWords = when {
                    requestedWakeWords.isNotEmpty() -> requestedWakeWords
                    message.activeWakeWordsList.isEmpty() -> emptyList()
                    else -> {
                        Log.w(
                            TAG,
                            "VoiceAssistantSetConfiguration ids ${message.activeWakeWordsList} " +
                                "not in catalog; keeping current $currentActive",
                        )
                        currentActive
                    }
                }
                if (activeWakeWords != currentActive) {
                    // flatMapLatest rebuilds the mic — abort first so late TTS cannot play.
                    abortVoiceSessionIfActive()
                    audioInput.setActiveWakeWords(activeWakeWords)
                }
                microphoneSettingsStore.saveActiveWakeWordsForEngine(
                    wakeWordEngine,
                    activeWakeWords
                )
                sendVoiceAssistantConfiguration()
                val ignoredWakeWords =
                    message.activeWakeWordsList.filter { wakeWordId ->
                        compatibleWakeWordIdsForEngine(
                            wakeWordEngine,
                            listOf(wakeWordId),
                            availableWakeWordIds
                        ).isEmpty()
                    }
                if (ignoredWakeWords.isNotEmpty())
                    Log.w(TAG, "Ignoring wake words: $ignoredWakeWords")
            }

            is VoiceAssistantAnnounceRequest -> handleAnnouncement(
                startConversation = message.startConversation,
                mediaId = message.mediaId,
                preannounceId = message.preannounceMediaId,
                announceText = message.text
            )

            is VoiceAssistantEventResponse -> handleVoiceAssistantMessage(message)

            is VoiceAssistantResponse -> {
                if (message.error) {
                    Log.e(TAG, "VoiceAssistantResponse error=true, HA pipeline failed to start")
                    showErrorToast(context.getString(R.string.pipeline_error_ha_start_failed))
                    stopSatellite()
                } else {
                    Log.d(TAG, "VoiceAssistantResponse port=${message.port}, pipeline started")
                    if (message.port > 0) {
                        Log.w(
                            TAG,
                            "HA requested UDP audio on port ${message.port}; API_AUDIO is preferred and UDP is not implemented",
                        )
                    }
                }
            }

            is VoiceAssistantAudio -> {
                if (message.data.size() > 0) {
                    val pcm = message.data.toByteArray()
                    when {
                        !stateMachine.shouldAcceptIncomingPcmTts() -> {
                            // URL fallback already committed — drop late SPEAKER chunks.
                        }
                        !stateMachine.onIncomingPcmTtsBytes(pcm) -> {
                            // Invalid PCM frame (odd size / empty after format check).
                        }
                        stateMachine.isReceivingPcmStream() -> {
                            pcmTtsPlayer.write(pcm)
                        }
                        stateMachine.shouldBufferIncomingPcmTts() -> {
                            bufferPendingPcmTtsChunk(pcm)
                            stateMachine.tryActivatePcmTtsStreamEarly()
                        }
                    }
                    message.data2?.let { secondary ->
                        if (secondary.size() > 0) {
                            Log.d(TAG, "VoiceAssistantAudio data2 ignored (${secondary.size()} bytes)")
                        }
                    }
                    return
                }
                if (message.end && _state.value == Listening) {
                    Log.d(TAG, "VoiceAssistantAudio end=true, switching to Processing")
                    audioInput.isStreaming = false
                    _state.value = Processing
                }
            }

            is VoiceAssistantTimerEventResponse -> handleTimerMessage(message)

            is SubscribeHomeassistantServicesRequest -> {
                ModBleAdvProxyBridge.onHomeassistantServicesSubscribed(context)
            }

            is SubscribeBluetoothLEAdvertisementsRequest -> {
                if (!bluetoothProxyEnabled) {
                    stopBluetoothProxyScan()
                    return
                }
                startBluetoothProxyScan()
                advertisementsSubscribed = true
                Log.d(TAG, "SubscribeBluetoothLEAdvertisementsRequest: reporting merged connection slots")
                bluetoothModule.sendConnectionsFree()
                sendMessage(bluetoothScannerStateResponse {
                    state = BluetoothScannerState.BLUETOOTH_SCANNER_STATE_RUNNING
                    mode = haRequestedScannerMode ?: configuredScannerModeProto()
                    configuredMode = configuredScannerModeProto()
                })
            }
            
            is UnsubscribeBluetoothLEAdvertisementsRequest -> {
                stopBluetoothProxyScan()
                advertisementsSubscribed = false
                sendMessage(bluetoothScannerStateResponse {
                    state = BluetoothScannerState.BLUETOOTH_SCANNER_STATE_IDLE
                    mode = haRequestedScannerMode ?: configuredScannerModeProto()
                    configuredMode = configuredScannerModeProto()
                })
            }
            
            is SubscribeBluetoothConnectionsFreeRequest -> {
                if (!bluetoothProxyEnabled) {
                    stopBluetoothProxyScan()
                    return
                }
                Log.d(TAG, "SubscribeBluetoothConnectionsFreeRequest: reporting merged connection slots")
                bluetoothModule.sendConnectionsFree()
            }
            
            is BluetoothDeviceRequest -> {
                if (!bluetoothProxyEnabled) return
                bluetoothModule.handleDeviceRequest(message)
            }
            
            is BluetoothGATTGetServicesRequest -> {
                if (!bluetoothProxyEnabled) return
                bluetoothModule.handleGetServicesRequest(message)
            }
            
            is BluetoothGATTReadRequest -> {
                if (!bluetoothProxyEnabled) return
                bluetoothModule.handleReadRequest(message)
            }
            
            is BluetoothGATTWriteRequest -> {
                if (!bluetoothProxyEnabled) return
                bluetoothModule.handleWriteRequest(message)
            }
            
            is BluetoothGATTReadDescriptorRequest -> {
                if (!bluetoothProxyEnabled) return
                bluetoothModule.handleReadDescriptorRequest(message)
            }
            
            is BluetoothGATTWriteDescriptorRequest -> {
                if (!bluetoothProxyEnabled) return
                bluetoothModule.handleWriteDescriptorRequest(message)
            }
            
            is BluetoothGATTNotifyRequest -> {
                if (!bluetoothProxyEnabled) return
                bluetoothModule.handleNotifyRequest(message)
            }
            
            is BluetoothScannerSetModeRequest -> {
                if (!bluetoothProxyEnabled) return
                // Runtime pin only. This must never write proxyScanMode: HA maps its
                // own "auto" to PASSIVE on the wire, so mirroring the pin would turn
                // every Auto (or Active) user into "passive" and then feed that back
                // as configured_mode, which is exactly what makes HA's one-shot
                // options migration lock the entry to "passive".
                haRequestedScannerMode = message.mode
                sendMessage(bluetoothScannerStateResponse {
                    state = BluetoothScannerState.BLUETOOTH_SCANNER_STATE_RUNNING
                    mode = message.mode
                    configuredMode = configuredScannerModeProto()
                })
            }

            is BluetoothSetConnectionParamsRequest -> {
                if (!bluetoothProxyEnabled) return
                bluetoothModule.handleSetConnectionParamsRequest(message)
            }
            
            is SubscribeHomeAssistantStatesRequest -> {
                resubscribeAllHomeAssistantStates()
            }

            is HomeAssistantStateResponse -> {
                handleHomeAssistantState(message)
            }

            is HomeassistantActionResponse -> {
                handleHomeassistantActionResponse(message)
            }

            else -> super.handleMessage(message)
        }
    }
    
    /**
     * (Re)arm every HA entity state subscription.
     *
     * Runs when HA connects over the ESPHome channel, and again whenever that channel wins
     * ownership back from the WebSocket: HA answers each subscription with the entity's
     * current value, so resubscribing is what refills caches that went stale in the meantime.
     */
    suspend fun resubscribeAllHomeAssistantStates() {
        // subscribedSceneRefs is also populated when scenes reload before HA connects;
        // without clearing here those entries block resubscribe and placeholders stay "--".
        subscribedSceneRefs.clear()

        val mediaPlayerEntity = settingsStore.get().haMediaPlayerEntity
        if (mediaPlayerEntity.isNotEmpty()) {
            val detailedOverlay = com.example.ava.settings.MediaOverlayStyle.fromStored(
                playerSettingsStore.get().mediaOverlayStyle
            ) == com.example.ava.settings.MediaOverlayStyle.DETAILED
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = mediaPlayerEntity
                attribute = "entity_picture"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = mediaPlayerEntity
                attribute = "media_title"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = mediaPlayerEntity
                attribute = "media_artist"
            })
            // Album / duration / position are only rendered by the detailed
            // overlay style; skip the subscriptions otherwise to save bandwidth.
            if (detailedOverlay) {
                sendMessage(subscribeHomeAssistantStateResponse {
                    entityId = mediaPlayerEntity
                    attribute = "media_album_name"
                })
                sendMessage(subscribeHomeAssistantStateResponse {
                    entityId = mediaPlayerEntity
                    attribute = "media_duration"
                })
                sendMessage(subscribeHomeAssistantStateResponse {
                    entityId = mediaPlayerEntity
                    attribute = "media_position"
                })
                sendMessage(subscribeHomeAssistantStateResponse {
                    entityId = mediaPlayerEntity
                    attribute = "media_position_updated_at"
                })
            }
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = mediaPlayerEntity
                attribute = "volume_level"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = mediaPlayerEntity
                attribute = "repeat"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = mediaPlayerEntity
                attribute = "shuffle"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = mediaPlayerEntity
                attribute = ""
            })
            Log.d(TAG, "Subscribed to HA media player: $mediaPlayerEntity (detailedOverlay=$detailedOverlay)")
        }

        subscribeQuickEntities()

        subscribeDreamClockTimer()

        subscribeWidgetSensors()

        subscribeScreensaverStatusSlots()

        subscribeDawnEntitySlots()

        subscribeSceneEntities()

        val weatherEntity = playerSettingsStore.haWeatherEntity.get()
        if (weatherEntity.isNotEmpty()) {
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = weatherEntity
                attribute = ""
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = weatherEntity
                attribute = "temperature"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = weatherEntity
                attribute = "humidity"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = weatherEntity
                attribute = "wind_speed"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = weatherEntity
                attribute = "wind_bearing"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = weatherEntity
                attribute = "friendly_name"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = weatherEntity
                attribute = "aqi"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = weatherEntity
                attribute = "pm25"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = weatherEntity
                attribute = "visibility"
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                entityId = weatherEntity
                attribute = "pressure"
            })
            Log.d(TAG, "Subscribed to HA weather entity: $weatherEntity")
        }
        // Simple Clock weather: dedicated PlayerSettings.screensaverWeatherEntityId
        // (never silently reuse haWeatherEntity). Same attr set as overlay, own cache.
        migrateScreensaverWeatherIfNeeded(playerSettingsStore.dataStore)
        subscribeSimpleClockWeather()
        // Dawn magazine weather strip: only ScreensaverSettings.dawnWeatherEntityId
        // (never silently reuse PlayerSettings.haWeatherEntity).
        subscribeDawnWeatherForecast()

        refreshHaEntityInterest()
    }

    private fun startBluetoothProxyScan() {
        if (!bluetoothManager.isDetectEnabled) return
        // Claim sync is Ava-to-Ava, not HA-subscription-scoped. Restarting it here
        // recovers a listener that detect-enable already wanted, without tying
        // ownership to Subscribe/Unsubscribe advertisement churn.
        bluetoothManager.startClaimSync(scope)
        bluetoothModule.startProxyScan { sendMessage(it) }
    }
    
    private fun stopBluetoothProxyScan() {
        bluetoothModule.stopProxyScan()
    }
    
    private val weatherStateCache = mutableMapOf<String, String>()
    /** Attr cache for Simple Clock's dedicated weather entity (decoupled from overlay). */
    private val simpleClockWeatherStateCache = mutableMapOf<String, String>()
    private var simpleClockWeatherEntityId = ""
    private val quickEntityStateCache = mutableMapOf<String, String>()
    private val quickEntityUnitCache = mutableMapOf<String, String>()
    private val quickEntityAttributeCache = mutableMapOf<String, MutableMap<String, String>>()
    private val quickEntityPictureUrlCache = mutableMapOf<String, String>()
    @Volatile private var lastCameraFetchSignedIn: Boolean? = null
    @Volatile private var lastHaCoverRaw: String = ""
    private val screensaverStatusEntityIds = mutableSetOf<String>()
    private val screensaverStatusLabelCache = mutableMapOf<String, String>()
    /**
     * Quick Entity / sensor-card entity ids, tracked purely so [interestedHaEntityIds] can
     * pre-filter the direct WebSocket feed. Unlike the screensaver sets these are not used
     * for routing — those consumers are dispatched to unconditionally.
     */
    private val quickEntitySubscribedIds = mutableSetOf<String>()
    private val widgetSensorSubscribedIds = mutableSetOf<String>()
    private val dawnEntityIds = mutableSetOf<String>()
    private val dawnEntityLabelCache = mutableMapOf<String, String>()
    /** Brightness / temperature etc. for Dawn capsules (kept even when WebView service is down). */
    private val dawnEntityAttributeCache = mutableMapOf<String, MutableMap<String, String>>()
    /** Last forecast JSON for the Dawn weather entity — replayed when screensaver starts. */
    private var lastDawnForecastEntityId: String = ""
    private var lastDawnForecastRaw: String = ""
    /** Current condition/temp for weather.* when get_forecasts has no hourly (daily-only demos). */
    private var dawnWeatherConditionCache = ""
    private var dawnWeatherTemperatureCache = ""
    private var cachedHaMediaIsPlaying = false
    private val haActionCallId = AtomicInteger(1)
    /** callId → (weather entity id, forecast type hourly|daily). */
    private val pendingWeatherForecastCallIds = mutableMapOf<Int, Pair<String, String>>()
    private var lastWeatherForecastRequestAt = 0L
    private var lastWeatherForecastRequestEntity = ""
    private var lastWeatherForecastRequestType = ""
    private var lastDawnWeatherSubscribeAt = 0L
    private var lastDawnWeatherSubscribeEntity = ""
    /** Refresh Dawn hourly forecast once per civil hour while the strip is active. */
    private var dawnWeatherHourlyRefreshJob: Job? = null

    /**
     * Independent of weather/media: HA drops service calls silently when
     * allow_service_calls is off (no ActionResponse). Any response ⇒ allowed.
     * Send is process-once ([haServiceProbeSent]); schedule lives on VoiceSatelliteService.
     */
    @Volatile
    private var haServiceCallsAllowed: Boolean? = null
    /** Written by the probe coroutine, read by the API reader thread. */
    private val pendingHaServiceProbeCallId = AtomicInteger(HA_SERVICE_CALLS_PROBE_CALL_ID_NONE)
    @Volatile
    private var haServiceProbeTimeoutJob: Job? = null

    // Entity cache referenced by notification-scene placeholders (distinct from quickEntity; any sensor and similar entities may use it)
    private val sceneEntityStateCache = mutableMapOf<String, String>()
    private val sceneEntityUnitCache = mutableMapOf<String, String>()
    private val sceneEntityAttributeCache = mutableMapOf<String, MutableMap<String, String>>()
    // Set of (entity_id, attribute) pairs already subscribed, so subscribe responses are not sent twice
    private val subscribedSceneRefs = mutableSetOf<Pair<String, String>>()

    fun getQuickEntityStateCache(): Map<String, String> = quickEntityStateCache.toMap()
    fun getQuickEntityUnitCache(): Map<String, String> = quickEntityUnitCache.toMap()
    fun getQuickEntityAttributeCache(): Map<String, Map<String, String>> = quickEntityAttributeCache.mapValues { it.value.toMap() }
    fun getQuickEntityPictureUrlCache(): Map<String, String> = quickEntityPictureUrlCache.toMap()

    fun getCachedClockWeather(): com.example.ava.weather.WeatherData? {
        if (simpleClockWeatherStateCache.isEmpty()) return null
        return com.example.ava.weather.WeatherService.parseFromHa(
            state = simpleClockWeatherStateCache["state"] ?: "",
            temperature = simpleClockWeatherStateCache["temperature"],
            humidity = simpleClockWeatherStateCache["humidity"],
            windSpeed = simpleClockWeatherStateCache["wind_speed"],
            windBearing = simpleClockWeatherStateCache["wind_bearing"],
            friendlyName = simpleClockWeatherStateCache["friendly_name"],
            aqi = simpleClockWeatherStateCache["aqi"],
            pm25 = simpleClockWeatherStateCache["pm25"],
            visibility = simpleClockWeatherStateCache["visibility"],
            pressure = simpleClockWeatherStateCache["pressure"],
            temperatureUnit = simpleClockWeatherStateCache["temperature_unit"],
            pressureUnit = simpleClockWeatherStateCache["pressure_unit"],
            windSpeedUnit = simpleClockWeatherStateCache["wind_speed_unit"],
            visibilityUnit = simpleClockWeatherStateCache["visibility_unit"],
            precipitationUnit = simpleClockWeatherStateCache["precipitation_unit"]
        )
    }

    fun getSceneEntityStateCache(): Map<String, String> = sceneEntityStateCache.toMap()
    fun getSceneEntityUnitCache(): Map<String, String> = sceneEntityUnitCache.toMap()
    fun getSceneEntityAttributeCache(): Map<String, Map<String, String>> = sceneEntityAttributeCache.mapValues { it.value.toMap() }

    /**
     * Entity ids some consumer currently cares about.
     *
     * The ESPHome channel only ever delivers what this device asked for, but the direct
     * WebSocket subscription carries HA's entire state machine. Applying all of it would
     * mean a coroutine plus several DataStore reads per entity per attribute, so the
     * WebSocket ingress is pre-filtered against this snapshot.
     */
    @Volatile
    private var haEntityInterest: Set<String> = emptySet()

    /** Recomputes [haEntityInterest]. Call after anything that changes a watched entity. */
    suspend fun refreshHaEntityInterest() {
        try {
            val ids = mutableSetOf<String>()
            ids += quickEntitySubscribedIds
            dreamClockTimerEntityId?.let { ids += it }
            ids += widgetSensorSubscribedIds
            ids += screensaverStatusEntityIds
            ids += dawnEntityIds
            subscribedSceneRefs.forEach { ids += it.first }
            ids += settingsStore.get().haMediaPlayerEntity
            ids += playerSettingsStore.haWeatherEntity.get()
            ids += resolveScreensaverWeatherEntityId(playerSettingsStore.get())
            ids += com.example.ava.settings.resolveDawnWeatherEntityId(
                ScreensaverSettingsStore(context.screensaverSettingsStore).get()
            )
            ids.remove("")
            if (ids == haEntityInterest) return
            haEntityInterest = ids
            Log.d(TAG, "HA entity interest: ${ids.size} entities")
            HaEntityInterest.bump()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh HA entity interest", e)
        }
    }

    /**
     * Scan every loaded notification-scene text and subscribe to the HA entities it references (state + required attributes).
     * Safe to call again: an (entity_id, attribute) pair that is already subscribed is not sent twice.
     */
    suspend fun subscribeSceneEntities() {
        try {
            val refs = com.example.ava.notifications.SceneTemplateResolver.extractRefs(
                *com.example.ava.notifications.NotificationScenes.ALL_SCENES.flatMap {
                    listOf(it.title, it.desc, it.subDesc)
                }.toTypedArray()
            )
            if (refs.isEmpty()) return
            // Subscribe every entity to state at minimum; sensors also subscribe to unit_of_measurement (for the {{...|unit}} shorthand)
            val attrsByEntity = mutableMapOf<String, MutableSet<String>>()
            refs.forEach { ref ->
                val set = attrsByEntity.getOrPut(ref.entityId) { mutableSetOf("") }
                if (ref.haAttribute.isNotEmpty()) set.add(ref.haAttribute)
                if (ref.entityId.startsWith("sensor.") || ref.entityId.startsWith("binary_sensor.")) {
                    set.add("unit_of_measurement")
                }
            }
            attrsByEntity.forEach { (eid, attrs) ->
                attrs.forEach { attr ->
                    val key = eid to attr
                    if (subscribedSceneRefs.add(key)) {
                        sendMessage(subscribeHomeAssistantStateResponse {
                            entityId = eid
                            attribute = attr
                        })
                    }
                }
            }
            Log.d(TAG, "Subscribed scene entities: ${attrsByEntity.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe scene entities", e)
        }
    }
    
    /** Currently subscribed `timer.*` for the Dream Clock flip countdown; null when unbound. */
    private var dreamClockTimerEntityId: String? = null

    suspend fun subscribeDreamClockTimer() {
        try {
            val entityId = playerSettingsStore.dreamClockTimerEntityId.get().trim()
            dreamClockTimerEntityId = entityId.takeIf { it.startsWith("timer.") }
            val id = dreamClockTimerEntityId ?: return
            for (attr in listOf("", "remaining", "finishes_at")) {
                sendMessage(subscribeHomeAssistantStateResponse {
                    this.entityId = id
                    attribute = attr
                })
            }
            Log.d(TAG, "Subscribed to dream clock timer: $id")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe dream clock timer", e)
        }
    }

    suspend fun subscribeQuickEntities() {
        try {
            quickEntitySubscribedIds.clear()
            val quickEntitySettings = context.quickEntitySettingsStore.data.first()
            if (!quickEntitySettings.enableQuickEntity) return
            
            quickEntitySettings.slots.forEach { slot ->
                if (slot.entityId.isNotEmpty()) {
                    quickEntitySubscribedIds.add(slot.entityId)
                    sendMessage(subscribeHomeAssistantStateResponse {
                        entityId = slot.entityId
                        attribute = ""
                    })
                    sendMessage(subscribeHomeAssistantStateResponse {
                        entityId = slot.entityId
                        attribute = "friendly_name"
                    })
                    if (slot.entityId.startsWith("sensor.") || slot.entityId.startsWith("binary_sensor.")) {
                        sendMessage(subscribeHomeAssistantStateResponse {
                            entityId = slot.entityId
                            attribute = "unit_of_measurement"
                        })
                        sendMessage(subscribeHomeAssistantStateResponse {
                            entityId = slot.entityId
                            attribute = "device_class"
                        })
                    }
                    if (slot.entityId.startsWith("timer.")) {
                        sendMessage(subscribeHomeAssistantStateResponse {
                            entityId = slot.entityId
                            attribute = "remaining"
                        })
                        sendMessage(subscribeHomeAssistantStateResponse {
                            entityId = slot.entityId
                            attribute = "finishes_at"
                        })
                    }
                    if (slot.entityId.startsWith("camera.") || slot.entityType == "camera") {
                        sendMessage(subscribeHomeAssistantStateResponse {
                            entityId = slot.entityId
                            attribute = "entity_picture"
                        })
                        sendMessage(subscribeHomeAssistantStateResponse {
                            entityId = slot.entityId
                            attribute = "access_token"
                        })
                    }
                    Log.d(TAG, "Subscribed to quick entity: ${slot.entityId}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe quick entities", e)
        }
    }

    /**
     * Home-screen sensor cards. Each placed card carries its own entity id, so the set
     * is whatever the user picked — any domain, nothing hardcoded. Called again whenever
     * a card is added, reconfigured or removed.
     */
    suspend fun subscribeWidgetSensors() {
        try {
            widgetSensorSubscribedIds.clear()
            com.example.ava.widgets.AvaSensorWidgetStore.entityIds(context).forEach { entityId ->
                widgetSensorSubscribedIds.add(entityId)
                sendMessage(subscribeHomeAssistantStateResponse {
                    this.entityId = entityId
                    attribute = ""
                })
                sendMessage(subscribeHomeAssistantStateResponse {
                    this.entityId = entityId
                    attribute = "friendly_name"
                })
                sendMessage(subscribeHomeAssistantStateResponse {
                    this.entityId = entityId
                    attribute = "unit_of_measurement"
                })
                sendMessage(subscribeHomeAssistantStateResponse {
                    this.entityId = entityId
                    attribute = "device_class"
                })
                Log.d(TAG, "Subscribed to sensor card entity: $entityId")
            }
            if (widgetSensorSubscribedIds.isNotEmpty()) {
                com.example.ava.widgets.AvaSensorWidgets.backfillFromHistory(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe sensor card entities", e)
        }
    }

    suspend fun subscribeScreensaverStatusSlots() {
        try {
            screensaverStatusEntityIds.clear()
            val settings = playerSettingsStore.get()
            if (!settings.enableScreensaver || !settings.enableScreensaverStatusSlots) return
            settings.screensaverStatusSlots.forEach { slot ->
                val entityId = slot.entityId.trim()
                if (entityId.isEmpty()) return@forEach
                screensaverStatusEntityIds.add(entityId)
                sendMessage(subscribeHomeAssistantStateResponse {
                    this.entityId = entityId
                    attribute = ""
                })
                sendMessage(subscribeHomeAssistantStateResponse {
                    this.entityId = entityId
                    attribute = "friendly_name"
                })
                if (entityId.startsWith("sensor.") ||
                    entityId.startsWith("binary_sensor.") ||
                    entityId.startsWith("number.")
                ) {
                    sendMessage(subscribeHomeAssistantStateResponse {
                        this.entityId = entityId
                        attribute = "unit_of_measurement"
                    })
                }
                Log.d(TAG, "Subscribed to screensaver status entity: $entityId")
            }
            ScreensaverService.restoreStatusEntityCaches(
                states = quickEntityStateCache.filterKeys { it in screensaverStatusEntityIds },
                labels = screensaverStatusLabelCache.filterKeys { it in screensaverStatusEntityIds },
                units = quickEntityUnitCache.filterKeys { it in screensaverStatusEntityIds }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe screensaver status slots", e)
        }
    }

    suspend fun subscribeDawnEntitySlots() {
        try {
            dawnEntityIds.clear()
            val settings = ScreensaverSettingsStore(context.screensaverSettingsStore).get()
            if (!settings.dawnWallpaperEnabled || !settings.enableDawnEntitySlots) {
                ScreensaverWebViewService.pushDawnSlotsFromSettings(context)
                return
            }
            // Capsules: sensor / binary_sensor / light / switch (weather.* → bottom hourly strip).
            settings.dawnEntitySlots.forEach { slot ->
                val entityId = slot.entityId.trim()
                if (entityId.isEmpty()) return@forEach
                if (!com.example.ava.settings.isAllowedDawnSlotEntityId(entityId)) return@forEach
                if (entityId.startsWith("weather.")) return@forEach
                dawnEntityIds.add(entityId)
                sendMessage(subscribeHomeAssistantStateResponse {
                    this.entityId = entityId
                    attribute = ""
                })
                sendMessage(subscribeHomeAssistantStateResponse {
                    this.entityId = entityId
                    attribute = "friendly_name"
                })
                if (entityId.startsWith("sensor.") ||
                    entityId.startsWith("binary_sensor.") ||
                    entityId.startsWith("number.")
                ) {
                    sendMessage(subscribeHomeAssistantStateResponse {
                        this.entityId = entityId
                        attribute = "unit_of_measurement"
                    })
                }
                // light.*: brightness (0–255) for "On · N%" capsule text — not the primary state.
                if (entityId.startsWith("light.")) {
                    sendMessage(subscribeHomeAssistantStateResponse {
                        this.entityId = entityId
                        attribute = "brightness"
                    })
                }
                Log.d(TAG, "Subscribed to Dawn magazine screensaver entity: $entityId")
            }
            // Replay caches without stacking another force forecast; weather pull is below.
            pushDawnDataToScreensaver(forceForecastRequest = false)
            subscribeDawnWeatherForecast(forceRequest = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe Dawn entity slots", e)
        }
    }

    /**
     * Replay cached Xiaomi capsule / hourly data into [ScreensaverWebViewService].
     *
     * HA only sends state on subscribe or change. If the first push happened while the
     * screensaver WebView was not running, those updates were dropped and the UI stayed
     * on "--" until the next change. Call this when the Dawn page becomes ready.
     */
    fun pushDawnDataToScreensaver(forceForecastRequest: Boolean = false) {
        try {
            val attrs = dawnEntityAttributeCache
                .filterKeys { it in dawnEntityIds }
                .mapValues { it.value.toMap() }
            ScreensaverWebViewService.restoreDawnEntityCaches(
                states = quickEntityStateCache.filterKeys { it in dawnEntityIds },
                labels = dawnEntityLabelCache.filterKeys { it in dawnEntityIds },
                units = quickEntityUnitCache.filterKeys { it in dawnEntityIds },
                attributes = attrs,
            )
            ScreensaverWebViewService.pushDawnSlotsFromSettings(context)

            val forecastEntity = lastDawnForecastEntityId.trim()
            val forecastRaw = lastDawnForecastRaw
            if (forecastEntity.isNotEmpty() && forecastRaw.isNotBlank()) {
                ScreensaverWebViewService.updateDawnHourlyForecastJson(forecastEntity, forecastRaw)
            }

            if (forceForecastRequest) {
                scope.launch {
                    subscribeDawnWeatherForecast(forceRequest = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to push Dawn data to screensaver", e)
        }
    }
    
    private fun handleHomeAssistantState(message: HomeAssistantStateResponse) {
        applyHaEntityState(
            source = HaStateSource.ESPHOME,
            entityId = message.entityId,
            attribute = message.attribute,
            state = message.state,
        )
    }

    /**
     * Single ingress for HA entity state, shared by the ESPHome reverse channel and the
     * direct WebSocket subscription.
     *
     * [HaStateArbiter] decides which channel is authoritative; pushes from the other are
     * dropped here before touching any cache, so the two can never race over one entity.
     */
    fun applyHaEntityState(
        source: HaStateSource,
        entityId: String,
        attribute: String,
        state: String,
    ) {
        if (!HaStateArbiter.accepts(source)) return
        if (source == HaStateSource.WEBSOCKET && entityId !in haEntityInterest) return

        scope.launch {
            val settings = settingsStore.get()
            val mediaPlayerEntity = settings.haMediaPlayerEntity
            val weatherEntity = playerSettingsStore.haWeatherEntity.get()
            val simpleClockWeatherEntity = resolveScreensaverWeatherEntityId(playerSettingsStore.get())
            
            if (entityId == mediaPlayerEntity) {
                // Empty media_* / entity_picture must still apply — clear-queue clears
                // attributes to "" and we need that to tear down the mini FAB.
                val allowEmptyAttribute = attribute == "media_title" ||
                    attribute == "media_artist" ||
                    attribute == "media_album_name" ||
                    attribute == "entity_picture"
                if (state.isEmpty() && !allowEmptyAttribute) {
                    // Non-media attributes with empty payload are noise.
                } else when (attribute) {
                    "entity_picture" -> {
                        lastHaCoverRaw = state
                        applyHaCoverUrl(state)
                    }
                    "media_title" -> {
                        player.setHaMediaTitle(state)
                    }
                    "media_artist" -> {
                        player.setHaMediaArtist(state)
                    }
                    "media_album_name" -> {
                        player.setHaMediaAlbum(state)
                    }
                    "media_duration" -> {
                        state.toFloatOrNull()?.let { seconds ->
                            player.setHaMediaDuration((seconds * 1000f).toLong())
                        }
                    }
                    "media_position" -> {
                        state.toFloatOrNull()?.let { seconds ->
                            player.setHaMediaPosition((seconds * 1000f).toLong())
                        }
                    }
                    "media_position_updated_at" -> {
                        player.setHaMediaPositionUpdatedAt(state)
                    }
                    "volume_level" -> {
                        val volume = state.toFloatOrNull() ?: 1.0f
                        player.setHaVolumeLevel(volume)
                    }
                    "repeat" -> {
                        player.setHaRepeatMode(state)
                    }
                    "shuffle" -> {
                        val shuffle = state == "true" || state == "on"
                        player.setHaShuffle(shuffle)
                    }
                    "" -> {
                        val notPlayingStates = listOf("paused", "off", "standby", "unavailable", "idle", "unknown")
                        val isPlaying = state !in notPlayingStates && state.isNotEmpty()
                        // Entity gone: its metadata must not live on as an immortal
                        // "now playing" that later claims the overlay. HA re-pushes
                        // everything when the entity returns.
                        if (state == "unavailable" || state == "unknown") {
                            player.clearHaMediaCache()
                        }
                        player.setHaPlaybackState(isPlaying)
                        cachedHaMediaIsPlaying = isPlaying
                    }
                    else -> {
                        if (attribute.isEmpty() || attribute.isBlank()) {
                            val notPlayingStates = listOf("paused", "off", "standby", "unavailable", "idle", "unknown")
                            val isPlaying = state !in notPlayingStates && state.isNotEmpty()
                            if (state == "unavailable" || state == "unknown") {
                                player.clearHaMediaCache()
                            }
                            player.setHaPlaybackState(isPlaying)
                            cachedHaMediaIsPlaying = isPlaying
                        }
                    }
                }
            }
            
            if (entityId == weatherEntity && state.isNotEmpty()) {
                val key = attribute.ifEmpty { "state" }
                weatherStateCache[key] = state

                com.example.ava.weather.WeatherService.updateFromHa(
                    state = weatherStateCache["state"] ?: "",
                    temperature = weatherStateCache["temperature"],
                    humidity = weatherStateCache["humidity"],
                    windSpeed = weatherStateCache["wind_speed"],
                    windBearing = weatherStateCache["wind_bearing"],
                    friendlyName = weatherStateCache["friendly_name"],
                    aqi = weatherStateCache["aqi"],
                    pm25 = weatherStateCache["pm25"],
                    visibility = weatherStateCache["visibility"],
                    pressure = weatherStateCache["pressure"],
                    temperatureUnit = weatherStateCache["temperature_unit"],
                    pressureUnit = weatherStateCache["pressure_unit"],
                    windSpeedUnit = weatherStateCache["wind_speed_unit"],
                    visibilityUnit = weatherStateCache["visibility_unit"],
                    precipitationUnit = weatherStateCache["precipitation_unit"]
                )
            }

            // Simple Clock weather: dedicated entity + cache → ScreensaverService only.
            if (simpleClockWeatherEntity.isNotEmpty() &&
                entityId == simpleClockWeatherEntity &&
                state.isNotEmpty()
            ) {
                val key = attribute.ifEmpty { "state" }
                simpleClockWeatherStateCache[key] = state
                val clockWeather = com.example.ava.weather.WeatherService.parseFromHa(
                    state = simpleClockWeatherStateCache["state"] ?: "",
                    temperature = simpleClockWeatherStateCache["temperature"],
                    humidity = simpleClockWeatherStateCache["humidity"],
                    windSpeed = simpleClockWeatherStateCache["wind_speed"],
                    windBearing = simpleClockWeatherStateCache["wind_bearing"],
                    friendlyName = simpleClockWeatherStateCache["friendly_name"],
                    aqi = simpleClockWeatherStateCache["aqi"],
                    pm25 = simpleClockWeatherStateCache["pm25"],
                    visibility = simpleClockWeatherStateCache["visibility"],
                    pressure = simpleClockWeatherStateCache["pressure"],
                    temperatureUnit = simpleClockWeatherStateCache["temperature_unit"],
                    pressureUnit = simpleClockWeatherStateCache["pressure_unit"],
                    windSpeedUnit = simpleClockWeatherStateCache["wind_speed_unit"],
                    visibilityUnit = simpleClockWeatherStateCache["visibility_unit"],
                    precipitationUnit = simpleClockWeatherStateCache["precipitation_unit"]
                )
                com.example.ava.services.ScreensaverService.updateClockWeather(clockWeather)
            }

            // Dawn weather strip: forecast attrs + current condition/temp cache.
            run {
                val settings = ScreensaverSettingsStore(context.screensaverSettingsStore).get()
                val dawnWeather = com.example.ava.settings.resolveDawnWeatherEntityId(settings)
                if (dawnWeather.isNotEmpty() && entityId == dawnWeather) {
                    when {
                        attribute.isEmpty() || attribute.isBlank() -> {
                            if (state.isNotEmpty()) dawnWeatherConditionCache = state
                        }
                        attribute == "temperature" -> {
                            if (state.isNotEmpty()) dawnWeatherTemperatureCache = state
                        }
                        (attribute == "forecast" || attribute == "hourly_forecast" ||
                            attribute == "forecast_hourly" || attribute == "hourly") &&
                            state.isNotEmpty() -> {
                            lastDawnForecastEntityId = entityId
                            lastDawnForecastRaw = state
                            ScreensaverWebViewService.updateDawnHourlyForecastJson(entityId, state)
                        }
                    }
                }
            }
            
            // Sensor cards watch their own entity and need attributes (unit_of_measurement,
            // device_class) that no other consumer caches, so they see the raw push.
            com.example.ava.widgets.AvaSensorWidgets.onEntityState(context, entityId, attribute, state)

            // Flip-clock countdown: forward even blank finishes_at (HA clears it on idle/pause).
            if (entityId == dreamClockTimerEntityId) {
                when (attribute) {
                    "" -> if (state.isNotEmpty()) DreamClockService.updateTimerState(state)
                    "finishes_at" -> DreamClockService.updateTimerFinishesAt(state)
                    "remaining" -> if (state.isNotEmpty()) DreamClockService.updateTimerRemaining(state)
                }
            }

            if (state.isNotEmpty()) {
                when {
                    attribute.isEmpty() || attribute.isBlank() -> {
                        quickEntityStateCache[entityId] = state
                        QuickEntityOverlayService.getInstance()?.updateEntityState(entityId, state)
                        if (entityId in screensaverStatusEntityIds) {
                            ScreensaverService.updateStatusEntityState(entityId, state)
                        }
                        if (entityId in dawnEntityIds) {
                            ScreensaverWebViewService.updateDawnEntityState(entityId, state)
                        }
                        if (isQuickEntityCamera(entityId)) {
                            // The WS subscription already carries every attribute; the
                            // one-shot pull is only needed on the ESPHome channel, which
                            // delivers attributes strictly on request.
                            if (source == HaStateSource.ESPHOME) {
                                requestCameraAttributeOnce(entityId, "entity_picture")
                                requestCameraAttributeOnce(entityId, "access_token")
                            }
                            pushQuickEntityCameraPicture(entityId)
                        }
                    }
                    attribute == "unit_of_measurement" || attribute == "temperature_unit" -> {
                        quickEntityUnitCache[entityId] = state
                        QuickEntityOverlayService.getInstance()?.updateEntityUnit(entityId, state)
                        if (entityId in screensaverStatusEntityIds) {
                            ScreensaverService.updateStatusEntityUnit(entityId, state)
                        }
                        if (entityId in dawnEntityIds) {
                            ScreensaverWebViewService.updateDawnEntityUnit(entityId, state)
                        }
                    }
                    attribute == "temperature" && entityId in dawnEntityIds -> {
                        // Prefer temperature as the capsule value for weather.* (and any slot with temp).
                        dawnEntityAttributeCache.getOrPut(entityId) { mutableMapOf() }["temperature"] = state
                        ScreensaverWebViewService.updateDawnEntityAttribute(entityId, "temperature", state)
                    }
                    attribute == "brightness" && entityId in dawnEntityIds -> {
                        // light.* only — used to enrich "On · N%", never replaces on/off state.
                        dawnEntityAttributeCache.getOrPut(entityId) { mutableMapOf() }["brightness"] = state
                        ScreensaverWebViewService.updateDawnEntityAttribute(entityId, "brightness", state)
                    }
                    attribute == "friendly_name" -> {
                        val hasChinese = state.any { it.code in 0x4E00..0x9FFF }
                        val raw = if (hasChinese && state.contains(" ")) {
                            state.substringAfterLast(" ").ifEmpty { state }
                        } else {
                            state
                        }
                        val maxLen = if (hasChinese) 7 else 20
                        val label = if (raw.length > maxLen) raw.take(maxLen) else raw
                        QuickEntityOverlayService.getInstance()?.updateEntityLabel(entityId, label)
                        if (entityId in screensaverStatusEntityIds) {
                            // Keep the raw friendly name; clock truncates English to 2 words at draw time.
                            screensaverStatusLabelCache[entityId] = state.trim()
                            ScreensaverService.updateStatusEntityLabel(entityId, state.trim())
                        }
                        if (entityId in dawnEntityIds) {
                            dawnEntityLabelCache[entityId] = state.trim()
                            ScreensaverWebViewService.updateDawnEntityLabel(entityId, state.trim())
                        }
                    }
                    attribute == "remaining" && entityId.startsWith("timer.") -> {
                        quickEntityAttributeCache.getOrPut(entityId) { mutableMapOf() }["remaining"] = state
                        QuickEntityOverlayService.getInstance()?.updateTimerRemaining(entityId, state)
                    }
                    attribute == "finishes_at" && entityId.startsWith("timer.") -> {
                        quickEntityAttributeCache.getOrPut(entityId) { mutableMapOf() }["finishes_at"] = state
                        QuickEntityOverlayService.getInstance()?.updateTimerFinishesAt(entityId, state)
                    }
                    attribute == "entity_picture" && isQuickEntityCamera(entityId) -> {
                        val cache = quickEntityAttributeCache.getOrPut(entityId) { mutableMapOf() }
                        if (cache["entity_picture"] == state) return@launch
                        cache["entity_picture"] = state
                        pushQuickEntityCameraPicture(entityId)
                    }
                    attribute == "access_token" && isQuickEntityCamera(entityId) -> {
                        val cache = quickEntityAttributeCache.getOrPut(entityId) { mutableMapOf() }
                        if (cache["access_token"] == state) return@launch
                        cache["access_token"] = state
                        pushQuickEntityCameraPicture(entityId)
                    }
                }
            }

            // Entities referenced by notification-scene templates: every subscribed entity is also written into the scene cache (even if quickEntity uses it too)
            val sceneEntityId = entityId.lowercase()
            if (state.isNotEmpty() && subscribedSceneRefs.any { it.first == sceneEntityId }) {
                when {
                    attribute.isEmpty() || attribute.isBlank() -> {
                        sceneEntityStateCache[sceneEntityId] = state
                    }
                    attribute == "unit_of_measurement" -> {
                        sceneEntityUnitCache[sceneEntityId] = state
                    }
                    else -> {
                        sceneEntityAttributeCache.getOrPut(sceneEntityId) { mutableMapOf() }[attribute] = state
                    }
                }
                com.example.ava.services.NotificationOverlayService
                    .getInstance()
                    ?.onSceneEntityChanged(sceneEntityId)
            }
        }
    }

    private fun isQuickEntityCamera(entityId: String): Boolean =
        entityId.startsWith("camera.")

    private suspend fun requestCameraAttributeOnce(entityId: String, attribute: String) {
        if (!entityId.startsWith("camera.")) return
        sendMessage(subscribeHomeAssistantStateResponse {
            this.entityId = entityId
            this.attribute = attribute
            once = true
        })
    }

    fun rebindHaMediaFetchPipes() {
        if (lastHaCoverRaw.isNotEmpty()) {
            applyHaCoverUrl(lastHaCoverRaw)
        }
        val ids = linkedSetOf<String>()
        quickEntityPictureUrlCache.keys.filterTo(ids) { isQuickEntityCamera(it) }
        quickEntityAttributeCache.keys.filterTo(ids) { isQuickEntityCamera(it) }
        QuickEntityOverlayService.getInstance()?.activeCameraEntityIds()?.let { ids.addAll(it) }
        if (ids.isEmpty()) return
        val lastUrls = ids.associateWith { quickEntityPictureUrlCache[it] }
        ids.forEach { entityId ->
            pushQuickEntityCameraPicture(
                entityId = entityId,
                forceRestart = false,
                lastUrl = lastUrls[entityId],
            )
        }
    }

    private fun applyHaCoverUrl(raw: String) {
        if (raw.isEmpty()) {
            player.setHaCoverUrl("")
            return
        }
        val clientAddr = server.getClientAddress()
        val fullUrl = HaMediaUrl.resolvePreferringSignedIn(raw, clientAddr)
        if (fullUrl.isNullOrBlank()) return
        if (raw.startsWith("/") && fullUrl.startsWith("/")) {
            Log.w(TAG, "No client connected, cannot load cover")
            return
        }
        player.setHaCoverUrl(fullUrl)
    }

    private fun pushQuickEntityCameraPicture(
        entityId: String,
        forceRestart: Boolean = false,
        lastUrl: String? = null,
    ) {
        val attrs = quickEntityAttributeCache[entityId]
        val haHost = server.getClientAddress()
        val overlay = QuickEntityOverlayService.getInstance()
        val haRemoteUrl = settingsStore.getCached().haRemoteUrl.ifBlank { null }
        haRemoteUrl?.let { HaMediaUrl.noteHaUrl(it) }
        val fullUrl = QuickEntityCameraRenderer.resolveCameraPollUrl(
            entityId = entityId,
            entityPicture = attrs?.get("entity_picture"),
            accessToken = attrs?.get("access_token"),
            haHost = haHost,
            lastUrl = lastUrl ?: quickEntityPictureUrlCache[entityId],
        )
        if (fullUrl == null) {
            if (quickEntityDisplayExpected()) {
                Log.w(TAG, "display no url entity=$entityId haHost=$haHost")
            }
            return
        }
        if (!forceRestart && quickEntityPictureUrlCache[entityId] == fullUrl) return
        if (overlay == null) {
            // Panel off is normal — keep the URL so SHOW can restore immediately.
            quickEntityPictureUrlCache[entityId] = fullUrl
            if (quickEntityDisplayExpected()) {
                Log.w(TAG, "display overlay service null entity=$entityId haHost=$haHost")
            }
            return
        }
        if (!overlay.updateEntityPicture(entityId, fullUrl, haRemoteUrl, forceRestart)) return
        quickEntityPictureUrlCache[entityId] = fullUrl
    }

    private fun quickEntityDisplayExpected(): Boolean =
        QuickEntitySettingsStore(context.quickEntitySettingsStore)
            .getCached()
            .let { it.enableQuickEntity && it.enableQuickEntityDisplay }
    
    private suspend fun fireVoicePrintWakeEvent(user: String) {
        if (user.isBlank()) return
        sendMessage(
            homeassistantServiceResponse {
                service = "esphome.voice_print_wake"
                isEvent = true
                data += homeassistantServiceMap {
                    key = "user"
                    value = user
                }
            },
        )
        Log.d(TAG, "Fired esphome.voice_print_wake user=$user")
    }

    private suspend fun callHaMediaPlayerService(service: String) {
        val mediaPlayerEntity = settingsStore.get().haMediaPlayerEntity
        if (mediaPlayerEntity.isEmpty()) {
            Log.w(TAG, "No HA media player configured")
            return
        }
        if (tryWsHaService("media_player.$service", mediaPlayerEntity)) return

        sendMessage(homeassistantServiceResponse {
            this.service = "media_player.$service"
            data += homeassistantServiceMap {
                key = "entity_id"
                value = mediaPlayerEntity
            }
        })
    }
    
    private suspend fun callHaMediaPlayerServiceWithData(service: String, extraData: Map<String, String>) {
        val mediaPlayerEntity = settingsStore.get().haMediaPlayerEntity
        if (mediaPlayerEntity.isEmpty()) {
            Log.w(TAG, "No HA media player configured")
            return
        }
        if (tryWsHaService("media_player.$service", mediaPlayerEntity, extraData)) return

        sendMessage(homeassistantServiceResponse {
            this.service = "media_player.$service"
            data += homeassistantServiceMap {
                key = "entity_id"
                value = mediaPlayerEntity
            }
            extraData.forEach { (k, v) ->
                data += homeassistantServiceMap {
                    key = k
                    value = v
                }
            }
        })
    }
    
    suspend fun callHaServicePublic(
        service: String,
        entityId: String,
        serviceData: Map<String, Any?> = emptyMap(),
    ) {
        if (entityId.isEmpty()) {
            Log.w(TAG, "No entity_id provided")
            return
        }
        if (tryWsHaService(service, entityId, serviceData)) {
            Log.d(TAG, "Called HA service via WS: $service for entity: $entityId")
            return
        }

        sendMessage(homeassistantServiceResponse {
            this.service = service
            data += homeassistantServiceMap {
                key = "entity_id"
                value = entityId
            }
            // ESPHome action data is string-only; HA coerces on the receiving side.
            // Never copy entity_id here — a spoken leftover would overwrite the resolved id
            // and HA answers `expected 'all' or 'none'`.
            com.example.ava.localllm.HaServicePayload.withoutTarget(serviceData).forEach { (k, v) ->
                if (v != null) {
                    data += homeassistantServiceMap {
                        key = k
                        value = v.toString()
                    }
                }
            }
        })
        Log.d(TAG, "Called HA service: $service for entity: $entityId")
    }

    /**
     * [callHaServicePublic] that reports the outcome: true / false when the WebSocket
     * answered (so "service not found" is not announced as done), null when ESPHome
     * has no acknowledgement or a WebSocket acknowledgement was lost.
     */
    private suspend fun callHaServiceReporting(
        service: String,
        entityId: String,
        serviceData: Map<String, Any?>,
    ): Boolean? {
        if (entityId.isEmpty()) return false
        val ha = HaManager.get()
        val wsReady = ha != null &&
            ha.connectionState.value is HaWsClient.ConnectionState.Connected &&
            ha.settingsStore.wsCallServiceEnabled.get()
        if (wsReady) return ha!!.tryCallServiceReporting(service, entityId, serviceData)
        callHaServicePublic(service, entityId, serviceData)
        return null
    }

    private suspend fun tryWsHaService(
        service: String,
        entityId: String,
        extraData: Map<String, Any?> = emptyMap(),
    ): Boolean {
        val data = extraData.mapValues { (_, value) ->
            if (value is String) coerceHaServiceValue(value) else value
        }
        return HaManager.tryCallService(context, service, entityId, data)
    }

    private fun coerceHaServiceValue(raw: String): Any {
        val lower = raw.lowercase()
        if (lower == "true") return true
        if (lower == "false") return false
        raw.toDoubleOrNull()?.let { return it }
        return raw
    }

    /**
     * Subscribe Simple Clock's dedicated weather entity (same attr set as overlay weather).
     * Empty entity → clear clock weather and skip subscribe.
     */
    private suspend fun subscribeSimpleClockWeather() {
        try {
            migrateScreensaverWeatherIfNeeded(playerSettingsStore.dataStore)
            val settings = playerSettingsStore.get()
            val entityId = resolveScreensaverWeatherEntityId(settings)
            simpleClockWeatherEntityId = entityId
            if (!settings.enableScreensaverWeather || entityId.isEmpty()) {
                simpleClockWeatherStateCache.clear()
                com.example.ava.services.ScreensaverService.updateClockWeather(null)
                return
            }
            for (attr in listOf(
                "",
                "temperature",
                "humidity",
                "wind_speed",
                "wind_bearing",
                "friendly_name",
                "aqi",
                "pm25",
                "visibility",
                "pressure"
            )) {
                sendMessage(subscribeHomeAssistantStateResponse {
                    this.entityId = entityId
                    attribute = attr
                })
            }
            Log.d(TAG, "Subscribed to Simple Clock weather entity: $entityId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to subscribe Simple Clock weather", e)
        }
    }

    /**
     * Subscribe forecast attributes + request get_forecasts for the Dawn magazine
     * weather entity: dedicated field or first `weather.*` among the 4 slots.
     */
    private suspend fun subscribeDawnWeatherForecast(forceRequest: Boolean = false) {
        try {
            val settings = ScreensaverSettingsStore(context.screensaverSettingsStore).get()
            val entityId = com.example.ava.settings.resolveDawnWeatherEntityId(settings)
            if (!settings.dawnWallpaperEnabled ||
                !settings.enableDawnEntitySlots ||
                entityId.isEmpty()
            ) {
                stopDawnWeatherHourlyRefresh()
                return
            }
            val now = System.currentTimeMillis()
            // Slot edits / page-ready can fire this many times in one second.
            if (entityId == lastDawnWeatherSubscribeEntity &&
                now - lastDawnWeatherSubscribeAt < 2_500L
            ) {
                if (forceRequest) {
                    requestWeatherGetForecasts(entityId, type = "hourly", force = true)
                }
                return
            }
            lastDawnWeatherSubscribeAt = now
            lastDawnWeatherSubscribeEntity = entityId

            // Current condition/temp (state = condition for weather.*); forecast attrs may be empty on modern HA.
            sendMessage(subscribeHomeAssistantStateResponse {
                this.entityId = entityId
                attribute = ""
            })
            sendMessage(subscribeHomeAssistantStateResponse {
                this.entityId = entityId
                attribute = "temperature"
            })
            // HA integrations expose different attr names; subscribe the common set.
            for (attr in listOf("forecast", "hourly_forecast", "forecast_hourly", "hourly")) {
                sendMessage(subscribeHomeAssistantStateResponse {
                    this.entityId = entityId
                    attribute = attr
                })
            }
            Log.d(TAG, "Subscribed Dawn magazine screensaver weather forecasts: $entityId")
            // Replay last forecast immediately (covers WebView not ready on first HA push).
            // Prefer matching entity; otherwise still try parse (keys may differ slightly).
            if (lastDawnForecastRaw.isNotBlank()) {
                ScreensaverWebViewService.updateDawnHourlyForecastJson(
                    weatherEntityId = entityId,
                    rawJson = lastDawnForecastRaw,
                    parseEntityId = lastDawnForecastEntityId.ifBlank { entityId },
                )
            }
            // Prefer hourly; daily-only entities (supported_features=1, e.g. demo_weather_south)
            // fall back inside the action-response handler.
            requestWeatherGetForecasts(entityId, type = "hourly", force = forceRequest)
            startDawnWeatherHourlyRefresh(entityId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to subscribe Dawn weather forecast", e)
        }
    }

    /** Pull weather.get_forecasts near each hour boundary so the strip can slide dynamically. */
    private fun startDawnWeatherHourlyRefresh(entityId: String) {
        val id = entityId.trim()
        if (id.isEmpty()) {
            stopDawnWeatherHourlyRefresh()
            return
        }
        dawnWeatherHourlyRefreshJob?.cancel()
        dawnWeatherHourlyRefreshJob = scope.launch {
            while (isActive) {
                val cal = java.util.Calendar.getInstance()
                val msIntoHour =
                    cal.get(java.util.Calendar.MINUTE) * 60_000L +
                        cal.get(java.util.Calendar.SECOND) * 1_000L +
                        cal.get(java.util.Calendar.MILLISECOND)
                // Fire ~5s into the new hour (avoids racing the exact boundary).
                val waitMs = (3_600_000L - msIntoHour + 5_000L).coerceAtLeast(30_000L)
                kotlinx.coroutines.delay(waitMs)
                try {
                    val settings = ScreensaverSettingsStore(context.screensaverSettingsStore).get()
                    val activeId = com.example.ava.settings.resolveDawnWeatherEntityId(settings)
                    if (!settings.dawnWallpaperEnabled ||
                        !settings.enableDawnEntitySlots ||
                        activeId.isEmpty()
                    ) {
                        break
                    }
                    requestWeatherGetForecasts(activeId, type = "hourly", force = true)
                } catch (e: Exception) {
                    Log.w(TAG, "Dawn hourly forecast refresh failed", e)
                }
            }
        }
    }

    private fun stopDawnWeatherHourlyRefresh() {
        dawnWeatherHourlyRefreshJob?.cancel()
        dawnWeatherHourlyRefreshJob = null
    }

    /**
     * Ask HA for forecast via weather.get_forecasts (needs HA action-response support).
     * [type] is `hourly` or `daily`. Hourly failure → automatic daily retry.
     */
    private fun requestWeatherGetForecasts(
        weatherEntity: String,
        type: String,
        force: Boolean = false,
    ) {
        val entityId = weatherEntity.trim()
        val forecastType = if (type == "daily") "daily" else "hourly"
        if (entityId.isEmpty()) return
        val now = System.currentTimeMillis()
        // Collapse bursts for the SAME entity+type. Entity/type changes always go through.
        val minGap = if (force) 2_500L else 60_000L
        if (entityId == lastWeatherForecastRequestEntity &&
            forecastType == lastWeatherForecastRequestType &&
            now - lastWeatherForecastRequestAt < minGap
        ) {
            Log.d(
                TAG,
                "Skip weather.get_forecasts $forecastType for $entityId " +
                    "(debounce ${now - lastWeatherForecastRequestAt}ms)"
            )
            return
        }
        lastWeatherForecastRequestAt = now
        lastWeatherForecastRequestEntity = entityId
        lastWeatherForecastRequestType = forecastType
        scope.launch {
            try {
                val callId = nextHaActionCallId()
                pendingWeatherForecastCallIds[callId] = entityId to forecastType
                sendMessage(homeassistantServiceResponse {
                    service = "weather.get_forecasts"
                    this.callId = callId
                    wantsResponse = true
                    data += homeassistantServiceMap {
                        key = "entity_id"
                        value = entityId
                    }
                    data += homeassistantServiceMap {
                        key = "type"
                        value = forecastType
                    }
                })
                Log.d(
                    TAG,
                    "Requested weather.get_forecasts $forecastType for $entityId callId=$callId force=$force"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to request weather $forecastType forecast", e)
            }
        }
    }

    private fun pushDawnCurrentWeatherFallback(entityId: String) {
        val condition = dawnWeatherConditionCache.trim()
        if (condition.isEmpty()) {
            Log.d(TAG, "Dawn weather fallback skipped: no current condition for $entityId")
            return
        }
        val tempRaw = dawnWeatherTemperatureCache.trim()
            .replace("°C", "").replace("°F", "").replace("°", "").trim()
        val tempNum = tempRaw.toDoubleOrNull()
        val tempLabel = if (tempNum != null) {
            "${kotlin.math.round(tempNum).toInt()}°"
        } else {
            ""
        }
        val hours = org.json.JSONArray().put(
            org.json.JSONObject().apply {
                put("t", "Now")
                put("temp", tempLabel)
                // Single-chip fallback after daily-only path — keep day palette, not night dark.
                put("daily", true)
                put("condition", condition)
            }
        )
        ScreensaverWebViewService.updateDawnHourlyForecast(entityId, hours)
        Log.d(TAG, "Dawn weather fallback (current) for $entityId: $condition $tempLabel")
    }

    private fun nextHaActionCallId(): Int =
        haActionCallId.getAndIncrement().let { if (it == 0) haActionCallId.getAndIncrement() else it }

    /** Cancel in-flight probe timeout/pending for this instance (schedule is on the Service). */
    fun cancelHaServiceCallsProbe() {
        haServiceProbeTimeoutJob?.cancel()
        haServiceProbeTimeoutJob = null
        pendingHaServiceProbeCallId.set(HA_SERVICE_CALLS_PROBE_CALL_ID_NONE)
    }

    /**
     * Process-once async probe, invoked by VoiceSatelliteService once HA is connected.
     * - Any ActionResponse ⇒ allow gate open.
     * - Timeout with no response ⇒ tip.
     * Never blocks; [haServiceProbeSent] ensures a single send attempt per process. The
     * one-shot is only consumed once we are actually able to send, so a disconnect before
     * that leaves the probe available for the next connect.
     */
    fun runHaServiceCallsProbeOnce() {
        if (haServiceCallsAllowed == true) {
            Log.d(TAG, "Skip HA service-call probe: already allowed")
            return
        }
        if (_state.value !is Connected) {
            Log.d(TAG, "Skip HA service-call probe: not Connected")
            return
        }
        if (!clientSupportsHaActionResponses()) {
            // Old HA never answers any action call — a timeout would blame the wrong thing.
            Log.d(TAG, "Skip HA service-call probe: client too old for action responses")
            return
        }
        if (!haServiceProbeSent.compareAndSet(false, true)) {
            Log.d(TAG, "Skip HA service-call probe: already attempted this process")
            return
        }
        val callId = nextHaActionCallId()
        pendingHaServiceProbeCallId.set(callId)
        scope.launch {
            try {
                sendMessage(
                    homeassistantServiceResponse {
                        service = HA_SERVICE_CALLS_PROBE_SERVICE
                        this.callId = callId
                        wantsResponse = true
                    },
                )
                Log.d(TAG, "HA service-call probe sent callId=$callId")
            } catch (e: CancellationException) {
                pendingHaServiceProbeCallId.compareAndSet(callId, HA_SERVICE_CALLS_PROBE_CALL_ID_NONE)
                throw e
            } catch (e: Exception) {
                pendingHaServiceProbeCallId.compareAndSet(callId, HA_SERVICE_CALLS_PROBE_CALL_ID_NONE)
                Log.w(TAG, "HA service-call probe send failed", e)
                return@launch
            }
            if (pendingHaServiceProbeCallId.get() != callId) return@launch
            haServiceProbeTimeoutJob?.cancel()
            haServiceProbeTimeoutJob = scope.launch {
                delay(HA_SERVICE_CALLS_PROBE_TIMEOUT_MS)
                // CAS: loses to a concurrent response/cancel, so the tip cannot double-fire.
                if (!pendingHaServiceProbeCallId.compareAndSet(
                        callId,
                        HA_SERVICE_CALLS_PROBE_CALL_ID_NONE,
                    )
                ) {
                    return@launch
                }
                haServiceProbeTimeoutJob = null
                haServiceCallsAllowed = false
                Log.w(
                    TAG,
                    "HA service-call probe timed out — allow_service_calls likely disabled",
                )
                com.example.ava.services.SatelliteSetupTipOverlayService
                    .maybeShowHaServiceCallsTip(context)
            }
        }
    }

    private fun markHaServiceCallsAllowed(from: String) {
        // Consume one-shot even if weather/action responded before the scheduled probe.
        haServiceProbeSent.set(true)
        val previous = haServiceCallsAllowed
        haServiceCallsAllowed = true
        if (previous == true) return
        cancelHaServiceCallsProbe()
        if (previous == false) {
            com.example.ava.services.SatelliteSetupTipOverlayService.clearHaServiceTipShown()
        }
        Log.d(TAG, "HA service calls allowed ($from)")
    }

    private fun handleHomeassistantActionResponse(message: HomeassistantActionResponse) {
        val callId = message.callId
        if (callId != HA_SERVICE_CALLS_PROBE_CALL_ID_NONE &&
            pendingHaServiceProbeCallId.compareAndSet(
                callId,
                HA_SERVICE_CALLS_PROBE_CALL_ID_NONE,
            )
        ) {
            // ServiceNotFound / validation error still proves the allow gate is open.
            markHaServiceCallsAllowed(
                "probe success=${message.success} err=${message.errorMessage}",
            )
            return
        }
        val pending = pendingWeatherForecastCallIds.remove(callId) ?: return
        // Weather (or any other) action response also proves allow_service_calls is on.
        markHaServiceCallsAllowed("weather action response")
        val requestedEntity = pending.first
        val forecastType = pending.second
        if (!message.success) {
            Log.w(
                TAG,
                "weather.get_forecasts $forecastType failed for $requestedEntity: ${message.errorMessage}"
            )
            if (forecastType == "hourly") {
                // Demo / daily-only entities (supported_features=1) reject hourly with 500.
                requestWeatherGetForecasts(requestedEntity, type = "daily", force = true)
            } else {
                pushDawnCurrentWeatherFallback(requestedEntity)
            }
            return
        }
        val raw = try {
            message.responseData.toStringUtf8()
        } catch (_: Exception) {
            String(message.responseData.toByteArray(), Charsets.UTF_8)
        }
        if (raw.isBlank()) {
            Log.w(TAG, "weather.get_forecasts $forecastType empty response for $requestedEntity")
            if (forecastType == "hourly") {
                requestWeatherGetForecasts(requestedEntity, type = "daily", force = true)
            } else {
                pushDawnCurrentWeatherFallback(requestedEntity)
            }
            return
        }
        scope.launch {
            val settings = ScreensaverSettingsStore(context.screensaverSettingsStore).get()
            val currentWeather = com.example.ava.settings.resolveDawnWeatherEntityId(settings)
            // Prefer current settings id; parse with requested key first (HA response keyed by target).
            val applyEntity = currentWeather.ifEmpty { requestedEntity }
            if (applyEntity.isEmpty()) return@launch
            lastDawnForecastEntityId = applyEntity
            lastDawnForecastRaw = raw
            // Parse with the HA response key; attach hours to the currently configured id.
            val applied = ScreensaverWebViewService.updateDawnHourlyForecastJson(
                weatherEntityId = applyEntity,
                rawJson = raw,
                parseEntityId = requestedEntity,
            )
            // If hourly payload parsed to 0 rows, still try daily (some integrations return {}).
            if (!applied && forecastType == "hourly") {
                Log.d(TAG, "Hourly forecast empty for $requestedEntity — trying daily")
                requestWeatherGetForecasts(requestedEntity, type = "daily", force = true)
                return@launch
            }
            if (!applied && forecastType == "daily") {
                pushDawnCurrentWeatherFallback(applyEntity)
                return@launch
            }
            Log.d(
                TAG,
                "Applied weather.get_forecasts $forecastType for $requestedEntity → $applyEntity (${raw.length} chars)"
            )
        }
    }

    private suspend fun handleTimerMessage(timerEvent: VoiceAssistantTimerEventResponse) {
        Log.d(
            TAG,
            "VoiceAssistantTimerEventResponse: eventType=${timerEvent.eventType}, " +
                "id=${timerEvent.timerId}, name=${timerEvent.name}, " +
                "total=${timerEvent.totalSeconds}s, left=${timerEvent.secondsLeft}s, " +
                "active=${timerEvent.isActive}"
        )
        when (timerEvent.eventType) {
            VoiceAssistantTimerEvent.VOICE_ASSISTANT_TIMER_FINISHED -> {
                if (!timerFinished) {
                    setTimerRinging(true)
                    player.duck()
                    player.playTimerFinishedSound {
                        scope.launch { onTimerFinished() }
                    }
                }
            }

            // STARTED/UPDATED carry countdown state we do not render yet. CANCELLED must not
            // stop the ring either: HA drops a timer from its registry the moment it fires
            // (TimerManager._timer_finished pops it), so a cancel can only ever refer to a
            // still-pending timer, never the one ringing here.
            else -> {}
        }
    }

    private fun handleAnnouncement(
        startConversation: Boolean,
        mediaId: String,
        preannounceId: String,
        announceText: String
    ) {
        Log.d(
            TAG,
            "VoiceAssistantAnnounceRequest: startConversation=$startConversation, " +
                "mediaId=${mediaId.take(80)}, preannounce=${preannounceId.take(80)}"
        )
        _state.value = Responding
        stateMachine.prepareTtsPlayback()
        // Same self-wake screen as pipeline TTS: an announcement that speaks the wake
        // phrase ("Hey Jarvis, dinner is ready") echoes a genuine utterance of it.
        // Empty / non-matching text clears any stale hold, which is also correct here.
        audioInput.noteTtsTextForWakeEchoRisk(announceText)
        player.duck()
        maybeApplyWhisperResponse()
        player.ttsPlayer.playAnnouncement(
            mediaUrl = mediaId,
            preannounceUrl = preannounceId
        ) {
            scope.launch {
                if (startConversation) {
                    // ask_question / start_conversation:
                    // Notify HA that announcement playback is done, then start pipeline to listen for user's answer
                    isAskQuestionMode = true
                    sendMessage(voiceAssistantAnnounceFinished { })
                    wakeSatellite(isContinueConversation = true)
                    // Start 10s timeout — if user doesn't speak, stop pipeline
                    askQuestionTimeoutJob?.cancel()
                    askQuestionTimeoutJob = scope.launch {
                        delay(10_000)
                        if (isAskQuestionMode && (_state.value == Listening || _state.value == Processing)) {
                            Log.d(TAG, "ask_question: 10s timeout, no user response, stopping pipeline")
                            stopSatellite()
                        }
                    }
                } else {
                    // Normal announce: full cleanup (onTtsFinished sends AnnounceFinished internally)
                    onTtsFinished()
                }
            }
        }
    }

    private suspend fun handleVoiceAssistantMessage(voiceEvent: VoiceAssistantEventResponse) {
        Log.d(TAG, "VoiceAssistantEventResponse: eventType=${voiceEvent.eventType}, data=${voiceEvent.dataList.map { "${it.name}=${it.value}" }}")
        haReceivedPipelineEvent = true
        stateMachine.handleVoiceEvent(voiceEvent)
        when (voiceEvent.eventType) {
            VoiceAssistantEvent.VOICE_ASSISTANT_RUN_START -> {
                fabListenOpenedAt = System.currentTimeMillis()
                if (isQuickWakeSession && fabListenSessionAt == 0L) {
                    fabListenSessionAt = fabListenOpenedAt
                }
                clearFabRenewRunStartWatchdog()
                if (isQuickWakeSession) armFabCapWatchdog()
                audioInput.ensureUserSpeechMicPeakTracking()
                synchronized(pendingMicAudioLock) { micAudioDeliveryGate.onRunStart() }
                flushPendingMicAudio()
                localIntentFallback.onRunStart()
                // Real Assist pipeline — show wake UI now. Config intercept never reaches here.
                presentWakeAnimation()
                dispatchVoicePipeline(ModVoicePipeline.Events.RUN_START)
            }
                VoiceAssistantEvent.VOICE_ASSISTANT_STT_START -> {
                    if (fabTextInjectArmed) {
                        fabInjectSttOpen = true
                        flushFabInjectQueue()
                    }
                }
            VoiceAssistantEvent.VOICE_ASSISTANT_STT_VAD_START -> {
                audioInput.beginUserSpeechMicPeakTracking()
                // HA heard speech; a later blank transcript is not a false wake.
                synchronized(wakePreRollLock) { wakeSessionSpeechEvidence.note() }
                wakeLearner.onSpeechEvidence()
                dispatchVoicePipeline(ModVoicePipeline.Events.STT_VAD_START)
                // User started speaking — cancel ask_question timeout
                if (isAskQuestionMode) {
                    Log.d(TAG, "ask_question: user started speaking, cancelling timeout")
                    askQuestionTimeoutJob?.cancel()
                    askQuestionTimeoutJob = null
                }
            }
            VoiceAssistantEvent.VOICE_ASSISTANT_STT_END -> {
                // Do not cut the uplink here. HA often finalizes STT while the user
                // is still finishing a phrase (worse after a late openWakeWord fire).
                // sendAudioEnd() / TTS_START / session reset close the pipe.
                voicePrintManager.onSttEnd()
                wakeLearner.onSttText(voiceEvent.stringField("text"))
                voiceEvent.stringField("text")?.let { sttText ->
                    dispatchVoicePipeline(ModVoicePipeline.Events.STT_END) {
                        putString(ModVoicePipeline.Extras.STT_TEXT, sttText)
                    }
                }
                if (searchListenOnly) {
                    // Branch ends here: the search box already has the transcript via
                    // onSttText. Do not let intent / TTS / captions run.
                    Log.d(TAG, "search-listen branch: STT_END, stop before intent/tts")
                    closePendingMicAudio()
                    stateMachine.setStopRequested(true)
                    scope.launch { stopSatellite() }
                }
            }
            VoiceAssistantEvent.VOICE_ASSISTANT_STT_VAD_END -> {
                // Keep sending for the state-machine hangover. Cutting here made the
                // 2–3.5 s delay local-only — HA never heard the rest of the sentence.
                dispatchVoicePipeline(ModVoicePipeline.Events.STT_VAD_END)
            }
            VoiceAssistantEvent.VOICE_ASSISTANT_TTS_START -> {
                if (stateMachine.isDroppingOldReply()) return
                closePendingMicAudio()
                maybeApplyWhisperResponse()
                val ttsText = voiceEvent.stringField("text")
                dispatchVoicePipeline(ModVoicePipeline.Events.RESPONDING)
                dispatchVoicePipeline(ModVoicePipeline.Events.TTS_START) {
                    val shown = ttsText?.let { TtsMdFilter.apply(it) }.orEmpty()
                    if (shown.isNotBlank()) {
                        putString(ModVoicePipeline.Extras.TTS_TEXT, shown)
                    }
                }
            }
            VoiceAssistantEvent.VOICE_ASSISTANT_RUN_END,
            VoiceAssistantEvent.VOICE_ASSISTANT_ERROR -> {
                if (stateMachine.isDroppingOldReply()) return
                closePendingMicAudio()
                if (voiceEvent.eventType == VoiceAssistantEvent.VOICE_ASSISTANT_RUN_END) {
                    // A run that ended without STT_END never produced a verdict.
                    wakeLearner.onSessionDiscarded("run_end")
                    dispatchVoicePipeline(ModVoicePipeline.Events.RUN_END)
                } else {
                    wakeLearner.onPipelineError(voiceEvent.stringField("code"))
                    dispatchVoicePipeline(ModVoicePipeline.Events.PIPELINE_ERROR) {
                        putString(
                            ModVoicePipeline.Extras.ERROR_CODE,
                            voiceEvent.stringField("code") ?: "unknown",
                        )
                        putString(
                            ModVoicePipeline.Extras.ERROR_MESSAGE,
                            voiceEvent.stringField("message") ?: "",
                        )
                    }
                }
                if (voiceEvent.eventType == VoiceAssistantEvent.VOICE_ASSISTANT_RUN_END ||
                    voiceEvent.eventType == VoiceAssistantEvent.VOICE_ASSISTANT_ERROR
                ) {
                    voicePrintManager.onPipelineSessionEnd()
                }
            }
            else -> {}
        }
    }

    private suspend fun handleAudioResult(audioResult: VoiceSatelliteAudioInput.AudioResult) {
        when (audioResult) {
            is VoiceSatelliteAudioInput.AudioResult.Audio -> {
                if (audioInput.isStreaming) {
                    // Order is kept without a mutex: flushPendingMicAudio only sets
                    // pipelineAcceptingAudio once the queue is empty, inside this same
                    // lock, so a frame either queues behind the backlog or sees an empty
                    // queue. The live path must never suspend on the flush mutex — a slow
                    // network send would stall the mic collector.
                    val sendNow = synchronized(pendingMicAudioLock) {
                        if (pipelineAcceptingAudio && micAudioDeliveryGate.canFlush) {
                            true
                        } else {
                            if (micAudioDeliveryGate.canBuffer) bufferPendingMicAudioLocked(audioResult.audio)
                            false
                        }
                    }
                    if (sendNow) {
                        sendMessage(voiceAssistantAudio { data = audioResult.audio })
                    }
                    val heardSpeech = synchronized(wakePreRollLock) {
                        wakeSessionSpeechEvidence.wantsFrames && wakeSessionSpeechEvidence.onFrame(
                            com.example.ava.audio.AudioEnergy.pcm16LeFloatRms(audioResult.audio.asReadOnlyByteBuffer()),
                            audioResult.audio.size() / WAKE_PRE_ROLL_BYTES_PER_MS,
                        )
                    }
                    if (heardSpeech) wakeLearner.onSpeechEvidence()
                } else if (audioInput.wakePreRollCapturing) {
                    captureWakePreRollFrame(audioResult.audio)
                }
                // Skip local silence detection in ask_question mode — rely on HA VAD + timeout
                if (!isAskQuestionMode) {
                    val audioBytes = audioResult.audio.toByteArray()
                    stateMachine.processAudioEnergy(audioBytes)
                    val buttonChrome = suppressWakeChrome()
                    val feedRipple = !playerSettingsStore.enableFloatingWindow.get() &&
                        !buttonChrome
                    if (feedRipple || buttonChrome) {
                        val level = com.example.ava.audio.AudioEnergy.rmsLevelMic(audioBytes)
                        if (feedRipple) com.example.ava.services.WakeRippleService.feedAudioEnergy(level)
                        if (buttonChrome) com.example.ava.services.QuickWakeFabService.feedAudioLevel(level)
                    }
                }
            }

            is VoiceSatelliteAudioInput.AudioResult.WakeDetected ->
                onWakeDetected(
                    audioResult.wakeWord,
                    audioResult.wakeWordId,
                    audioResult.confidence,
                    verifierWindow = audioResult.verifierWindow,
                )

            is VoiceSatelliteAudioInput.AudioResult.StopDetected ->
                onStopDetected()

            is VoiceSatelliteAudioInput.AudioResult.SpeechInsert ->
                onSpeechInsert(audioResult.leadIn)

            is VoiceSatelliteAudioInput.AudioResult.Error -> {
                Log.e(TAG, "Audio input error: ${audioResult.message}, recoverable=${audioResult.recoverable}")
                // Don't crash - just log the error and continue
                // The flow will end naturally after emitting this error
            }
        }
    }
    
    private suspend fun onWakeDetected(
        wakeWordPhrase: String,
        wakeWordId: String = "",
        wakeConfidence: Float = 1f,
        // True for manual / sidebar triggers that bypass the detector (no producer-side wake mark).
        // Model wakes are already marked in VoiceSatelliteAudioInput the instant they fire.
        syntheticWake: Boolean = false,
        /** Rail search box: STT only, never interrupt an Assist turn already in flight. */
        searchListen: Boolean = false,
        /** Classifier input behind a model wake, for on-device verifier learning. */
        verifierWindow: FloatArray? = null,
        /** Quick Wake button: tactile trigger, so no earcon — uplink opens at once. */
        silentWake: Boolean = false,
    ) {
        if (com.example.ava.voice.AvaVoiceMicrophoneGuard.isHeld()) {
            Log.d(TAG, "wake ignored: voice message holds microphone")
            return
        }
        if (!voiceChannelEnabled) {
            return
        }
        if (!syntheticWake &&
            !searchListen &&
            multiDeviceArbiterEnabled &&
            WakeWordArbiter.isPeerChorusSessionActive()
        ) {
            Log.d(TAG, "wake ignored: peer chorus session active")
            return
        }
        val currentState = _state.value
        if (searchListen && (
                currentState == Listening ||
                    currentState == Processing ||
                    currentState == Responding ||
                    stateMachine.isWaking
                )
        ) {
            Log.d(TAG, "search listen ignored: assist session already active")
            return
        }

        // Cold-start continuity: capture mic frames from the moment the wake fired, so
        // speech spoken while the gates below run (sample verify, voiceprint, arbiter)
        // survives to uplink-open instead of being lost before the earcon even starts.
        // Never restart a capture an active session already owns (re-wake while the
        // earcon plays), and never for search listen (its uplink opens immediately).
        var startedPreRollHere = false
        if (!searchListen &&
            currentState != Listening &&
            !conversationEngineHold &&
            !audioInput.wakePreRollCapturing
        ) {
            startWakePreRoll()
            startedPreRollHere = true
            // Build the earcon player in parallel with the gates: the first play after
            // process start otherwise pays ExoPlayer construction at chime time.
            player.prewarmWakeSound()
        }

        val activeWakeWordsList = audioInput.activeWakeWords.value
        val wakeWordIndex = if (activeWakeWordsList.size > 1 && wakeWordId.isNotEmpty()) {
            val idx = activeWakeWordsList.indexOf(wakeWordId)
            if (idx > 0) idx else 0
        } else 0
        Log.d(TAG, "onWakeDetected: wakeWordPhrase=$wakeWordPhrase, wakeWordId=$wakeWordId, " +
            "confidence=${"%.2f".format(wakeConfidence)}, activeWakeWords=$activeWakeWordsList, wakeWordIndex=$wakeWordIndex")

        if (!passWakeSampleGate(wakeWordPhrase, wakeWordId, wakeConfidence, syntheticWake)) {
            Log.d(TAG, "wake blocked: offline sample verify rejected")
            if (startedPreRollHere) stopWakePreRoll()
            return
        }

        if (!passVoicePrintWakeGate(wakeConfidence, syntheticWake)) {
            Log.d(TAG, "wake blocked: voiceprint verification failed or not enrolled")
            if (startedPreRollHere) stopWakePreRoll()
            return
        }

        // Only accepted wakes update Voice Stats "Last Wake" (streaming hits alone do not).
        audioInput.markLastWakeDiagnostic(
            wakeWordId = wakeWordId,
            wakeWordPhrase = wakeWordPhrase,
            confidence = wakeConfidence,
        )
        // Every gate has passed: this is the wake the user experiences. Park its window;
        // the session outcome below decides whether it was genuine.
        if (!syntheticWake && !searchListen) {
            wakeLearner.onWakeAccepted(audioInput.wakeWordEngine, wakeWordId, verifierWindow)
        }

        if (!searchListen) {
            dispatchVoicePipeline(ModVoicePipeline.Events.WAKE_DETECTED) {
                putString(ModVoicePipeline.Extras.WAKE_WORD, wakeWordPhrase)
                putString(ModVoicePipeline.Extras.WAKE_WORD_ID, wakeWordId)
                putFloat(ModVoicePipeline.Extras.WAKE_CONFIDENCE, wakeConfidence)
                putBoolean(ModVoicePipeline.Extras.SYNTHETIC_WAKE, syntheticWake)
            }
        }
        
        if (timerFinished) {
            if (searchListen) return
            stopTimer()
            return
        }
        
        if (!searchListen && multiDeviceArbiterEnabled) {
            val arbiterResult = WakeWordArbiter.arbitrate(
                context = context,
                wakeWordId = wakeWordId,
                wakeWordPhrase = wakeWordPhrase,
                confidence = wakeConfidence,
                detectRms = audioInput.lastDetectRms(),
            )
            Log.d(TAG, "arbiter result: shouldRespond=${arbiterResult.shouldRespond} reason=${arbiterResult.reason} competitors=${arbiterResult.competitorCount}")
            val wakeKey = WakeWordArbiter.normalizeWakeKey(wakeWordId, wakeWordPhrase)
            chorusSessionWakeKey = wakeKey
            if (!arbiterResult.shouldRespond) {
                chorusWonSession = false
                WakeWordArbiter.setLocalChorusWinner(false)
                WakeWordArbiter.markPeerChorusSession(wakeKey)
                Log.d(TAG, "lost arbitration, covering with chorus blur")
                if (conversationEngineHold) {
                    ModConversationEngine.revokeCurrent(ModConversationEngine.Reasons.REVOKED_CHORUS)
                }
                if (startedPreRollHere) stopWakePreRoll()
                val accentSettings = playerSettingsStore.getCached()
                ChorusWakeBlurService.show(
                    context,
                    com.example.ava.ui.VoiceAccentColors.forWakeWordIndex(
                        wakeWordIndex,
                        accentSettings.voiceWakeWord1AccentColor,
                        accentSettings.voiceWakeWord2AccentColor,
                    ),
                )
                return
            }
            chorusWonSession = true
            WakeWordArbiter.setLocalChorusWinner(true)
            startChorusAliveHeartbeat()
            ChorusWakeBlurService.hideImmediate(context)
        }
        
        if (currentState == Responding || currentState == Processing) {
            // Old-run teardown fires completion callbacks while state is still
            // Responding; hold chorus END announcements until the new run owns the
            // state (wakeSatellite sets Listening + isWaking before returning).
            if (_remoteAiHold.value) localIntentFallback.cancel()
            chorusRewakeHandoff = true
            try {
                player.ttsPlayer.stop()
                if (pcmTtsPlayerLazy.isInitialized()) {
                    clearPendingPcmTtsChunks()
                    pcmTtsPlayer.stop()
                }
                audioInput.isStreaming = false
                endWhisperSession()
                player.unDuck()
                stateMachine.reset()
                sendVoiceAssistantStopRequest()
                // Previous Active run may still emit RUN_END after this new start.
                stateMachine.markInterruptedPipeline()
                searchListenOnly = false
                startWonWake(
                    wakeWordPhrase = wakeWordPhrase,
                    wakeWordId = wakeWordId,
                    wakeConfidence = wakeConfidence,
                    syntheticWake = syntheticWake,
                    wakeWordIndex = wakeWordIndex,
                    silentWake = silentWake,
                    searchListen = false,
                )
            } finally {
                chorusRewakeHandoff = false
            }
            return
        }
        
        if (currentState == Listening) {
            if (!syntheticWake) {
                Log.d(
                    TAG,
                    "Ignoring wake - already listening (haPhase=${stateMachine.haPipelinePhase})",
                )
                return
            }
            // FAB / assist key: the user is cutting this listen to start another.
            chorusRewakeHandoff = true
            try {
                player.ttsPlayer.stop()
                if (pcmTtsPlayerLazy.isInitialized()) {
                    clearPendingPcmTtsChunks()
                    pcmTtsPlayer.stop()
                }
                audioInput.isStreaming = false
                endWhisperSession()
                player.unDuck()
                stateMachine.reset()
                sendVoiceAssistantStopRequest()
                stateMachine.markInterruptedPipeline()
                searchListenOnly = false
                startWonWake(
                    wakeWordPhrase = wakeWordPhrase,
                    wakeWordId = wakeWordId,
                    wakeConfidence = wakeConfidence,
                    syntheticWake = syntheticWake,
                    wakeWordIndex = wakeWordIndex,
                    silentWake = silentWake,
                    searchListen = false,
                )
            } finally {
                chorusRewakeHandoff = false
            }
            return
        }
        
        if (currentState == Connected) {
            if (stateMachine.isWaking && !syntheticWake) {
                if (startedPreRollHere) stopWakePreRoll()
                return
            }
            // New HA wake while the remote seat is still thinking: drop that seat.
            if (_remoteAiHold.value) localIntentFallback.cancel()
            searchListenOnly = searchListen
            startWonWake(
                wakeWordPhrase = wakeWordPhrase,
                wakeWordId = wakeWordId,
                wakeConfidence = wakeConfidence,
                syntheticWake = syntheticWake,
                wakeWordIndex = wakeWordIndex,
                silentWake = silentWake,
                searchListen = searchListen,
            )
            return
        }
    }

    /**
     * After chorus won (or single-device). Ask an opt-in conversation-engine
     * mod to claim before [wakeSatellite] opens the HA pipeline.
     */
    private suspend fun startWonWake(
        wakeWordPhrase: String,
        wakeWordId: String,
        wakeConfidence: Float,
        syntheticWake: Boolean,
        wakeWordIndex: Int,
        silentWake: Boolean,
        searchListen: Boolean,
    ) {
        if (!searchListen && tryClaimConversationEngine(
                wakeWordPhrase = wakeWordPhrase,
                wakeWordId = wakeWordId,
                wakeConfidence = wakeConfidence,
                syntheticWake = syntheticWake,
            )
        ) {
            return
        }
        wakeSatellite(wakeWordPhrase, wakeWordIndex = wakeWordIndex, silent = silentWake)
    }

    /**
     * Yield the mic, then ask the bound conversation-engine mod. Fail-closed:
     * missing opt-in, false, or throw → unpause and let HA start.
     */
    private fun tryClaimConversationEngine(
        wakeWordPhrase: String,
        wakeWordId: String,
        wakeConfidence: Float,
        syntheticWake: Boolean,
    ): Boolean {
        if (!ModConversationEngine.isActive(context)) return false
        stopWakePreRoll()
        audioInput.isStreaming = false
        audioInput.setTemporaryPaused(true)
        val claimed = runCatching {
            ModConversationEngine.offerWake(
                context = context,
                wakeWordPhrase = wakeWordPhrase,
                wakeWordId = wakeWordId,
                wakeConfidence = wakeConfidence,
                syntheticWake = syntheticWake,
            )
        }.onFailure {
            Log.w(TAG, "conversation engine offer failed", it)
        }.getOrDefault(false)
        if (!claimed || !ModConversationEngine.isSeatHeld()) {
            audioInput.setTemporaryPaused(false)
            return false
        }
        conversationEngineHold = true
        startConversationEngineLeaseWatch()
        dispatchVoicePipeline(ModVoicePipeline.Events.WAKE_CLAIMED) {
            putString(ModVoicePipeline.Extras.WAKE_WORD, wakeWordPhrase)
            putString(ModVoicePipeline.Extras.WAKE_WORD_ID, wakeWordId)
            putFloat(ModVoicePipeline.Extras.WAKE_CONFIDENCE, wakeConfidence)
            putBoolean(ModVoicePipeline.Extras.SYNTHETIC_WAKE, syntheticWake)
            putString(ModConversationEngine.Extras.TOKEN, ModConversationEngine.currentToken())
        }
        Log.i(TAG, "conversation engine claimed wake; HA pipeline skipped")
        return true
    }

    private fun startConversationEngineLeaseWatch() {
        conversationEngineLeaseJob?.cancel()
        conversationEngineLeaseJob = scope.launch {
            while (conversationEngineHold) {
                delay(1_000)
                if (ModConversationEngine.expireIfNeeded()) return@launch
            }
        }
    }

    private fun finishConversationEngineSeat(reason: String) {
        if (!conversationEngineHold) return
        conversationEngineHold = false
        conversationEngineLeaseJob?.cancel()
        conversationEngineLeaseJob = null
        audioInput.setTemporaryPaused(false)
        audioInput.resetWakeWordDetector()
        if (reason != ModConversationEngine.Reasons.REVOKED_CHORUS) {
            announceChorusSessionEnd()
        }
        dispatchVoicePipeline(ModVoicePipeline.Events.SESSION_ENDED)
        onConversationEnd?.invoke()
        Log.i(TAG, "conversation engine seat finished reason=$reason")
    }

    fun isConversationEngineHold(): Boolean =
        conversationEngineHold || ModConversationEngine.isSeatHeld()

    
    fun triggerManualWake(silent: Boolean = false) {
        if (!voiceChannelEnabled) return
        if (silent && !QuickWakePushToTalk.isHolding) {
            QuickWakePushToTalk.noteTapWake()
        }
        scope.launch {
            if (isAssistTurnActive()) {
                // Already in a turn: this is "be quiet for a moment", not barge-in-and-listen.
                // Re-waking here cleared stopRequested and let stale TTS keep talking.
                localIntentFallback.cancel()
                stopVoiceSession()
                return@launch
            }
            onWakeDetected(
                wakeWordPhrase = "",
                wakeConfidence = com.example.ava.voiceprint.VoicePrintManager.MANUAL_WAKE_CONFIDENCE,
                syntheticWake = true,
                silentWake = silent,
            )
        }
    }

    fun isAssistTurnActive(): Boolean {
        val current = _state.value
        return stateMachine.isWaking ||
            current == Listening ||
            current == Processing ||
            current == Responding ||
            _remoteAiHold.value ||
            conversationEngineHold
    }

    /**
     * Mic is opening or already open. A hold-to-talk can latch onto this listen.
     * Processing / TTS / a remote-AI seat are not collecting — latching there
     * would have to hush the reply.
     */
    fun isCollectingSpeech(): Boolean {
        if (_remoteAiHold.value || conversationEngineHold) return false
        val current = _state.value
        if (current == Processing || current == Responding) return false
        return stateMachine.isWaking || current == Listening
    }
    
    private suspend fun passWakeSampleGate(
        wakeWordPhrase: String,
        wakeWordId: String,
        wakeConfidence: Float,
        syntheticWake: Boolean,
    ): Boolean {
        if (!voiceChannelEnabled) return true
        // Manual / sidebar wake has no wake-phrase audio to compare.
        if (syntheticWake) return true
        return audioInput.verifyWakeSample(
            wakeWordId = wakeWordId,
            wakeWordPhrase = wakeWordPhrase,
            streamingConfidence = wakeConfidence,
        )
    }

    private suspend fun passVoicePrintWakeGate(
        wakeConfidence: Float,
        syntheticWake: Boolean,
    ): Boolean {
        if (!voiceChannelEnabled || !voicePrintManager.isEnabled()) return true
        // Sidebar / button manual wake: no wake-phrase audio — must never be blocked by verify.
        if (syntheticWake) return true
        if (voicePrintManager.isManualWakeVerifyRequired()) {
            return voicePrintManager.verifyWakeAllowed(
                audioInput = audioInput,
                engine = audioInput.wakeWordEngine,
                wakeConfidence = wakeConfidence,
            )
        }
        scheduleVoicePrintProcessing(wakeConfidence)
        return true
    }

    private fun scheduleVoicePrintProcessing(wakeConfidence: Float = 1f) {
        if (!voiceChannelEnabled || !voicePrintManager.isEnabled()) return
        // Model wakes are marked in VoiceSatelliteAudioInput the instant they fire.
        voicePrintManager.scheduleWakeProcessing(
            audioInput = audioInput,
            engine = audioInput.wakeWordEngine,
            wakeConfidence = wakeConfidence,
        )
    }

    /**
     * A sentence spoken while the reply is being worked on or played. Stop that
     * work and send this audio as the next turn of the same conversation.
     */
    private suspend fun onSpeechInsert(leadIn: com.google.protobuf.ByteString) {
        if (!continuousForVoiceWake() || !player.enableContinuousConversation.get()) return
        if (_state.value != Processing && _state.value != Responding && !_remoteAiHold.value) return
        if (stateMachine.isWaking || stateMachine.isWakePhase) return
        if (speechInsertHandoff) return
        speechInsertHandoff = true
        try {
            Log.i(TAG, "speech insert state=${_state.value} hold=${_remoteAiHold.value}, ${leadIn.size()} bytes")
            if (!audioInput.wakePreRollCapturing) startWakePreRoll()
            if (leadIn.size() > 0) {
                synchronized(wakePreRollLock) { wakePreRollBuffer.append(leadIn) }
            }
            localIntentFallback.cancel()
            player.ttsPlayer.stop()
            if (pcmTtsPlayerLazy.isInitialized()) {
                clearPendingPcmTtsChunks()
                pcmTtsPlayer.stop()
            }
            audioInput.isStreaming = false
            endWhisperSession()
            stateMachine.markInterruptedPipeline()
            sendVoiceAssistantStopRequest()
            if (!suppressWakeChrome()) {
                onListeningStarted?.invoke(sessionAccentColor)
            }
            wakeSatellite(isContinueConversation = true)
        } finally {
            speechInsertHandoff = false
        }
    }

    private fun takePreRollIntoPending() {
        audioInput.wakePreRollCapturing = false
        val frames = synchronized(wakePreRollLock) {
            val saved = wakePreRollBuffer.snapshot().frames
            clearWakePreRollLocked()
            saved
        }
        synchronized(pendingMicAudioLock) {
            for (frame in frames) bufferPendingMicAudioLocked(frame)
        }
    }

    private suspend fun onStopDetected() {
        if (stateMachine.isWakePhase) {
            Log.d(TAG, "Ignoring stop during wake phase")
            return
        }
        
        if (stateMachine.isWaking) {
            Log.d(TAG, "Ignoring stop during waking")
            return
        }
        
        if (stateMachine.isStopWordProtected()) {
            Log.d(TAG, "Ignoring stop during protection period")
            return
        }
        
        if (timerFinished) {
            // stopTimer() first so the ring (ttsPlayer) is silenced before the
            // earcon (wakeSoundPlayer) starts: audible confirmation that the
            // spoken stop landed, same sound as the voice-session abort below.
            stopTimer()
            player.playStopSound()
            return
        }
        
        val currentState = _state.value
        val remoteSeat = _remoteAiHold.value
        // Remote AI keeps the spoken-stop gate armed after HA RUN_END, but the
        // channel is already Connected. Same abort as FAB hush / abortVoiceSession.
        if (currentState == Listening ||
            currentState == Processing ||
            currentState == Responding ||
            remoteSeat
        ) {
            Log.d(
                TAG,
                "Stop word detected in state $currentState hold=$remoteSeat - stopping satellite",
            )
            // "Stop" while still listening, before any transcript, is the user telling the
            // device it should not have woken; STT_END (if any) has already cleared a
            // genuine wake, so this only ever labels an unanswered one.
            if (currentState == Listening) wakeLearner.onStopWordWhileListening()
            player.playStopSound()
            // Drop the seat first: stopSatellite keeps chrome/TTS while hold is still true.
            localIntentFallback.cancel()
            stopSatellite()
        } else {
            Log.d(TAG, "Ignoring stop in state $currentState hold=$remoteSeat")
        }
    }

    private fun shouldApplyWhisperWakeSound(_settings: PlayerSettings): Boolean = true

    private fun shouldApplyWhisperResponse(_settings: PlayerSettings): Boolean = true

    private fun resolveVoiceReplyVolume(base: Float): Float {
        val slider = base.coerceIn(PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME, 1f)
        if (!playerSettingsStore.getCached().enableAmbientAutoGain) return slider
        return AmbientAutoGain.output(slider, audioInput.previewAmbientMicLevel())
    }

    private fun maybeApplyWhisperWakeSound() {
        val settings = playerSettingsStore.getCached()
        if (!shouldApplyWhisperWakeSound(settings)) return
        val whisperVolume = settings.whisperResponseVolume
            .coerceIn(PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME, 1f)
        Log.d(TAG, "whisper wake sound: volume=$whisperVolume")
        player.applyWhisperWakeSoundOnly(whisperVolume)
    }

    private fun maybeApplyWhisperResponse() {
        val settings = playerSettingsStore.getCached()
        if (!shouldApplyWhisperResponse(settings)) {
            if (player.isWhisperPlaybackActive) {
                player.clearWhisperPlayback()
                applyPcmTtsOutputVolume()
            }
            audioInput.holdAmbientSampling(false)
            return
        }

        val slider = settings.whisperResponseVolume
            .coerceIn(PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME, 1f)
        audioInput.holdAmbientSampling(true)
        val whisperVolume = resolveVoiceReplyVolume(slider)
        Log.d(
            TAG,
            "voice overlay TTS: stream=$whisperVolume slider=$slider " +
                "ambient=${"%.3f".format(audioInput.previewAmbientMicLevel())} " +
                "autoGain=${settings.enableAmbientAutoGain}",
        )
        player.applyWhisperPlayback(whisperVolume)
        applyPcmTtsOutputVolume()
    }

    fun applyLiveVoiceReplyVolume(level: Float, autoGain: Boolean? = null) {
        if (!player.isWhisperPlaybackActive) return
        val slider = level.coerceIn(PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME, 1f)
        val useGain = autoGain ?: playerSettingsStore.getCached().enableAmbientAutoGain
        if (useGain) audioInput.holdAmbientSampling(true)
        val whisperVolume = if (useGain) {
            AmbientAutoGain.output(slider, audioInput.previewAmbientMicLevel())
        } else {
            slider
        }
        player.applyWhisperPlayback(whisperVolume)
        applyPcmTtsOutputVolume()
    }

    fun setPcmTtsVolume(level: Float) {
        if (!pcmTtsPlayerLazy.isInitialized()) return
        pcmTtsPlayer.volume = level.coerceIn(0f, 1f)
    }

    private fun applyPcmTtsOutputVolume() {
        if (!pcmTtsPlayerLazy.isInitialized()) return
        pcmTtsPlayer.volume = player.ttsOutputVolume()
    }

    private fun endWhisperSession() {
        player.clearWhisperPlayback()
        applyPcmTtsOutputVolume()
        audioInput.endSessionMicPeakTracking()
        audioInput.holdAmbientSampling(false)
    }

    /**
     * Show wake ripple / floating listening UI only after HA RUN_START.
     * Config intercept never gets RUN_START, so animation stays off for that branch.
     */
    private fun presentWakeAnimation() {
        if (searchListenOnly) return
        if (wakeAnimationPresented) return
        if (stateMachine.haPipelinePhase != HaPipelinePhase.Active) return
        // Button turn / button-only mode: the FAB owns listen chrome.
        if (suppressWakeChrome()) return
        wakeAnimationPresented = true
        onListeningStarted?.invoke(sessionAccentColor)
        dispatchVoicePipeline(ModVoicePipeline.Events.LISTENING_STARTED) {
            putInt(ModVoicePipeline.Extras.ACCENT_COLOR, sessionAccentColor)
        }
        scope.launch(Dispatchers.Main) {
            val rippleColor = sessionAccentColor
            if (playerSettingsStore.enableFloatingWindow.get()) {
                com.example.ava.services.WakeRippleService.show(context, rippleColor)
            } else {
                com.example.ava.services.WakeRippleService.showWakeSession(context, rippleColor)
            }
        }
    }

    /** Config intercept abort: ensure wake animation is not left on screen. */
    private fun suppressWakeAnimationForConfigIntercept() {
        wakeAnimationPresented = false
        scope.launch(Dispatchers.Main) {
            com.example.ava.services.WakeRippleService.hide(context)
            com.example.ava.services.FloatingWindowService.hide(context)
        }
    }

    private suspend fun wakeSatellite(
        wakeWordPhrase: String = "",
        isContinueConversation: Boolean = false,
        wakeWordIndex: Int = 0,
        /** No earcon: the user already pressed something, so open the uplink immediately. */
        silent: Boolean = false,
    ) {
        if (!voiceChannelEnabled) {
            return
        }

        val priorState = _state.value
        if (priorState == Disconnected || priorState == Stopped) {
            Log.e(TAG, "HA disconnected, cannot start pipeline")
            stopWakePreRoll()
            showErrorToast(context.getString(R.string.pipeline_error_ha_disconnected))
            HaPipelineConfigErrorTracker.onPipelineError(
                context,
                HaPipelineConfigErrorTracker.HA_DISCONNECTED,
            )
            return
        }
        // Hard gate only — do not poll/retry on every wake. Subscribe is established
        // once when HA connects; if it is missing the pipeline simply cannot start.
        if (!isVoiceAssistantSubscribed) {
            Log.e(TAG, "HA not subscribed to voice assistant, cannot start pipeline")
            stopWakePreRoll()
            showErrorToast(context.getString(R.string.pipeline_error_no_response))
            HaPipelineConfigErrorTracker.onPipelineError(
                context,
                HaPipelineConfigErrorTracker.HA_NOT_SUBSCRIBED,
            )
            return
        }

        // Local UI → Listening. HA phase → AwaitingHaDecision until:
        //   RUN_START  → normal Assist pipeline (Active)
        //   RUN_END    → config intercept / HA abort branch (leave Listening)
        // Ownership must be visible before observers see Listening: the Quick Wake button
        // decides whether to animate at all from this flag on the very first state emission.
        // Button-only: every listen is FAB-owned, including HA entity / assist /
        // announce→listen (those paths are not silent, but there is no wake chrome).
        if (!fabListenRenewing) clearFabListenStitch()
        if (isButtonWakeMode()) {
            isQuickWakeSession = true
        } else if (!isContinueConversation) {
            isQuickWakeSession = silent
        }
        if (isQuickWakeSession && fabListenSessionAt == 0L) {
            fabListenSessionAt = System.currentTimeMillis()
        }

        _state.value = Listening
        resetPendingMicAudio()
        if (!isContinueConversation) {
            // Fresh wake: the learner's pending sample starts with no speech evidence.
            // Continue-conversation turns keep whatever the wake turn already heard.
            synchronized(wakePreRollLock) { wakeSessionSpeechEvidence.reset() }
        }
        stateMachine.prepareForNewWake()
        stateMachine.setStopRequested(false)
        stateMachine.setWaking(true)

        if (!isContinueConversation) {
            val accentSettings = playerSettingsStore.get()
            sessionAccentColor = com.example.ava.ui.VoiceAccentColors.forWakeWordIndex(
                wakeWordIndex,
                accentSettings.voiceWakeWord1AccentColor,
                accentSettings.voiceWakeWord2AccentColor
            )
            sessionAccentWakeIndexDiag = wakeWordIndex
            sessionAccentAtMs = System.currentTimeMillis()
        }
        // Do not show wake animation yet. Config intercept (RUN_END without RUN_START)
        // must not flash ripple/floating listening UI; presentWakeAnimation() runs on RUN_START.
        // Continuous: keep flag if we already bridged speaking→listening with the continue chime.
        if (!isContinueConversation) {
            wakeAnimationPresented = false
        }

        // Search-listen is STT only: no wake chime, no duck, no pause. Music in the
        // rail should keep playing while the user speaks a query.
        if (!searchListenOnly) {
            if (player.mediaPlayer.isPlaying && !wasMusicPlaying) {
                wasMusicPlaying = true
                savedMusicPosition = player.currentPosition
                player.mediaPlayer.pause()
            }

            if (cachedHaMediaIsPlaying && !isContinueConversation) {
                haMediaWasPlaying = true
            }

            cancelHaMediaResume()
            player.duck()
            duckHaMediaPlayerVolume()
            onVoiceOverlayDuck?.invoke()
        }

        haReceivedPipelineEvent = false
        listeningStartedAt = System.currentTimeMillis()
        resetPendingMicAudio()
        if (isContinueConversation && audioInput.wakePreRollCapturing) {
            takePreRollIntoPending()
        }
        if (!isContinueConversation) {
            haConversationId = ""
        }
        sendVoiceAssistantStartRequest(wakeWordPhrase, useConversationId = isContinueConversation)
        audioInput.isStreaming = isContinueConversation
        if (isContinueConversation) {
            // No earcon hold: the mic is live from the first frame.
            synchronized(wakePreRollLock) { wakeSessionSpeechEvidence.arm() }
        }

        if (!isContinueConversation) {
            if (searchListenOnly) {
                stateMachine.setWakePhase(false)
                // Search listen never holds the uplink, so pre-roll has no consumer.
                stopWakePreRoll()
                audioInput.isStreaming = _state.value == Listening
            } else if (silent) {
                // Quick Wake button: the press is the acknowledgement. Route through the
                // same splice as the earcon path so speech spoken during the tap (already
                // in the pre-roll) still reaches HA ahead of the live stream.
                stateMachine.setWakePhase(true)
                if (!audioInput.wakePreRollCapturing) {
                    startWakePreRoll()
                }
                wakeSoundUplinkWatchdog?.cancel()
                wakeSoundUplinkWatchdog = null
                openUplinkAfterWakeSound("silent quick wake")
            } else {
                stateMachine.setWakePhase(true)
                // Capture (don't drop) mic frames while the earcon holds the uplink
                // closed. Normally already running since wake-detect (gate-chain
                // coverage); start here only for entries that skipped onWakeDetected.
                if (!audioInput.wakePreRollCapturing) {
                    startWakePreRoll()
                }
                maybeApplyWhisperWakeSound()
                // The uplink stays closed during the earcon (RUN_START defers to
                // this callback), so HA never hears the chime's echo residual.
                // Watchdog caps the hold: custom wake sounds can be arbitrarily
                // long (system ringtones), and a lost completion callback must
                // not leave the session deaf.
                wakeSoundUplinkWatchdog?.cancel()
                val cueGeneration = synchronized(pendingMicAudioLock) { micAudioDeliveryGate.generation }
                val cueWatchdog = scope.launch {
                    kotlinx.coroutines.delay(WAKE_SOUND_UPLINK_MAX_HOLD_MS)
                    if (stateMachine.isWakePhase && synchronized(pendingMicAudioLock) {
                            micAudioDeliveryGate.isCurrent(cueGeneration)
                        }) {
                        Log.w(TAG, "wake sound still playing after ${WAKE_SOUND_UPLINK_MAX_HOLD_MS}ms — opening uplink")
                        openUplinkAfterWakeSound("watchdog", cueGeneration)
                    }
                }
                wakeSoundUplinkWatchdog = cueWatchdog
                player.playWakeSound(wakeWordIndex, onStarting = { uri ->
                    // A cue the VAD has not cleared yet keeps the legacy onset for this
                    // wake; the scan runs once in the background so the next wake with
                    // the same cue can use rescue.
                    if (uri != null && !WakePreRollCuePolicy.isKnown(uri)) scanWakeCueForSpeech(uri)
                    synchronized(pendingMicAudioLock) {
                        if (!micAudioDeliveryGate.isCurrent(cueGeneration)) false else {
                            synchronized(wakePreRollLock) {
                                wakePreRollSupportsSpeechRescue = WakePreRollCuePolicy.supportsSpeechRescue(uri)
                            }
                            true
                        }
                    }
                }) {
                    cueWatchdog.cancel()
                    scope.launch { openUplinkAfterWakeSound("wake sound complete", cueGeneration) }
                }
            }
        }
    }

    private var wakeSoundUplinkWatchdog: kotlinx.coroutines.Job? = null

    /**
     * Keep the bounded audio intact. The energy onset is a replay hint, not
     * permission to destroy quiet words before a later, louder syllable.
     */
    private fun captureWakePreRollFrame(audio: ByteString) {
        val frameBytes = audio.size()
        if (frameBytes < 2) return
        // Leak insurance: no session path should leave capture running this long, but
        // a missed teardown must degrade to "no pre-roll", never to a hot mic loop.
        val startedAt = synchronized(wakePreRollLock) { wakePreRollStartedAtMs }
        if (startedAt > 0 && System.currentTimeMillis() - startedAt > WAKE_PRE_ROLL_MAX_CAPTURE_MS) {
            Log.w(TAG, "wake pre-roll capture exceeded ${WAKE_PRE_ROLL_MAX_CAPTURE_MS}ms — stopping")
            stopWakePreRoll()
            return
        }
        synchronized(wakePreRollLock) {
            wakePreRollBuffer.append(audio)
        }
    }

    /**
     * Open the mic uplink after the wake earcon (completion callback or watchdog).
     * A speech-latched pre-roll is spliced in front of the pending-audio queue so HA
     * hears the words spoken over the chime, in order, ahead of the live stream.
     * A silent pre-roll is discarded: HA's VAD then starts from a clean stream and
     * its own timeout gives the user time to think before speaking.
     *
     * Flag order is load-bearing for a gapless splice: pass-through is closed first
     * (live frames queue), streaming turns on second (frames route to the queue
     * instead of pre-roll), capture stops last. A frame arriving at any point lands
     * either in the pre-roll (moved to the queue head below) or in the queue tail —
     * never on the floor.
     */
    private suspend fun openUplinkAfterWakeSound(reason: String, expectedGeneration: Long? = null) {
        if (expectedGeneration != null && !synchronized(pendingMicAudioLock) {
                micAudioDeliveryGate.isCurrent(expectedGeneration)
            }) return
        if (!stateMachine.isWakePhase) return
        stateMachine.setWakePhase(false)
        if (_state.value != Listening) {
            stopWakePreRoll()
            audioInput.isStreaming = false
            Log.d(TAG, "wake uplink open ($reason): session gone, pre-roll discarded")
            return
        }
        val generation = synchronized(pendingMicAudioLock) {
            if (!micAudioDeliveryGate.canBuffer) null
            else {
                pipelineAcceptingAudio = false
                micAudioDeliveryGate.beginPreRoll()
            }
        }
        if (generation == null) {
            // The run already closed its uplink (TTS_START / RUN_END / audio end). Nothing
            // will consume the pre-roll, so stop capturing instead of leaking it into the
            // 15 s leak-insurance path.
            stopWakePreRoll()
            Log.d(TAG, "wake uplink open ($reason): uplink already closed, pre-roll discarded")
            return
        }
        audioInput.isStreaming = true
        audioInput.wakePreRollCapturing = false
        val (snapshot, mayRescue) = synchronized(wakePreRollLock) {
            val saved = wakePreRollBuffer.snapshot() to wakePreRollSupportsSpeechRescue
            clearWakePreRollLocked()
            // From here the mic no longer hears the earcon: local energy counts as speech.
            wakeSessionSpeechEvidence.arm()
            saved
        }
        // Own the commit in the satellite scope: sound completion can cancel the
        // watchdog that called us, but must not cancel a half-finished splice.
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val rescueStartedNs = System.nanoTime()
            val speechStart = if (mayRescue && snapshot.needsSpeechRescue) {
                try {
                    rescueWakePreRoll(snapshot, generation)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "pre-roll speech rescue unavailable; keeping existing onset", error)
                    null
                } catch (error: LinkageError) {
                    Log.w(TAG, "pre-roll VAD runtime unavailable; keeping existing onset", error)
                    null
                }
            } else null
            val replay = snapshot.replay(speechStart)
            if (replay.isNotEmpty()) {
                // Speech over the earcon is speech evidence for the learner even if HA
                // later fails to transcribe it.
                synchronized(wakePreRollLock) { wakeSessionSpeechEvidence.note() }
                wakeLearner.onSpeechEvidence()
            }
            val flushNow = synchronized(pendingMicAudioLock) {
                if (generation != micAudioDeliveryGate.generation) return@launch
                if (_state.value != Listening) {
                    micAudioDeliveryGate.finishPreRoll(generation)
                    return@launch
                }
                for (frame in coalescePreRollFrames(replay).asReversed()) {
                    pendingMicAudioBuffer.addFirst(frame)
                    pendingMicAudioBytes += frame.size()
                }
                micAudioDeliveryGate.finishPreRoll(generation)
                micAudioDeliveryGate.canFlush
            }
            val preRollMs = replay.sumOf { it.size() } / WAKE_PRE_ROLL_BYTES_PER_MS
            Log.d(TAG, "wake uplink open ($reason): preRollMs=$preRollMs " +
                "speechRescue=${speechStart != null} " +
                "rescueMs=${(System.nanoTime() - rescueStartedNs) / 1_000_000} flushNow=$flushNow")
            if (flushNow) flushPendingMicAudio()
        }.join()
    }

    /**
     * One-off background check of a custom wake cue. Runs on IO, never on the audio
     * path; the result is cached for the process lifetime in [WakePreRollCuePolicy].
     */
    private fun scanWakeCueForSpeech(uri: String) {
        if (!WakePreRollCuePolicy.beginScan(uri)) return
        scope.launch(Dispatchers.IO) {
            val speechFree = try {
                WakeCueSpeechScanner.isSpeechFree(context, uri, wakePreRollVad)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                WakePreRollCuePolicy.abandonScan(uri)
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "wake cue scan failed for $uri", error)
                null
            }
            WakePreRollCuePolicy.endScan(uri, speechFree)
            Log.i(TAG, "wake cue $uri speechFree=$speechFree -> preRollRescue=${speechFree == true}")
        }
    }

    private suspend fun rescueWakePreRoll(snapshot: WakePreRollBuffer.Snapshot, generation: Long): Int? =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            val session = wakePreRollVad.newSession() ?: return@withContext null
            session.use { detector ->
                detector.push(snapshot.pcm())?.let { return@withContext it }
                // Existing accepted speech never waits for more audio. Only the
                // otherwise-discarded tail gets a bounded chance to resolve.
                if (snapshot.legacyStart != null) return@withContext null
                val deadlineNs = System.nanoTime() + 320_000_000L
                var fedBytes = 0
                for (afterMs in listOf(80, 160, 240)) {
                    val wantedBytes = afterMs * WAKE_PRE_ROLL_BYTES_PER_MS
                    while (System.nanoTime() < deadlineNs) {
                        val available = synchronized(pendingMicAudioLock) {
                            if (generation != micAudioDeliveryGate.generation || _state.value != Listening) {
                                return@withContext null
                            }
                            pendingMicAudioBytes
                        }
                        if (available >= wantedBytes) break
                        delay(10)
                    }
                    val tail = synchronized(pendingMicAudioLock) {
                        if (generation != micAudioDeliveryGate.generation || _state.value != Listening) {
                            return@withContext null
                        }
                        val out = ByteArray(minOf(wantedBytes, pendingMicAudioBytes))
                        var offset = 0
                        for (frame in pendingMicAudioBuffer) {
                            val count = minOf(frame.size(), out.size - offset)
                            if (count == 0) break
                            frame.copyTo(out, 0, offset, count)
                            offset += count
                        }
                        out
                    }
                    if (tail.size > fedBytes) {
                        val start = detector.push(tail.copyOfRange(fedBytes, tail.size))
                        fedBytes = tail.size
                        if (start != null) return@withContext minOf(start, snapshot.bytes)
                    }
                }
                null
            }
        }

    /**
     * Coalesce small mic frames into pending-queue chunks. Raw pre-roll can exceed the
     * pending queue's chunk cap when capture frames are short; coalescing keeps the
     * transfer far below the cap so [bufferPendingMicAudioLocked] never drops the head
     * (the first phonemes) of the utterance.
     */
    private fun coalescePreRollFrames(frames: Collection<ByteString>): List<ByteString> {
        val out = ArrayList<ByteString>()
        var acc = ByteString.EMPTY
        for (frame in frames) {
            acc = acc.concat(frame)
            if (acc.size() >= WAKE_PRE_ROLL_FLUSH_CHUNK_BYTES) {
                out.add(acc)
                acc = ByteString.EMPTY
            }
        }
        if (acc.size() > 0) {
            out.add(acc)
        }
        return out
    }

    private fun clearWakePreRollLocked() {
        wakePreRollBuffer.clear()
        wakePreRollSupportsSpeechRescue = false
        wakePreRollStartedAtMs = 0L
    }

    /** Begin a fresh pre-roll capture (wake accepted for arbitration / earcon hold). */
    private fun startWakePreRoll() {
        synchronized(wakePreRollLock) {
            clearWakePreRollLocked()
            wakePreRollStartedAtMs = System.currentTimeMillis()
        }
        audioInput.wakePreRollCapturing = true
    }

    /** Abort capture and discard the buffer (wake rejected / session torn down). */
    private fun stopWakePreRoll() {
        audioInput.wakePreRollCapturing = false
        synchronized(wakePreRollLock) { clearWakePreRollLocked() }
    }

    private var haConversationId: String = ""

    private val pendingPcmTtsChunks = ArrayDeque<ByteArray>()
    private val pendingPcmTtsLock = Any()

    private val pcmTtsPlayerLazy = lazy {
        VoiceAssistantPcmPlayer().apply {
            onPlaybackComplete = {
                disablePcmPlaybackEnergy()
                announceChorusSessionEnd()
                scope.launch {
                    player.ttsPlayer.triggerCompletion()
                }
            }
        }
    }
    private val pcmTtsPlayer by pcmTtsPlayerLazy

    /** Read-only: true while the PCM TTS player is actively playing. Does not start the player. */
    fun isPcmTtsActive(): Boolean =
        pcmTtsPlayerLazy.isInitialized() && pcmTtsPlayer.isActive()

    /** Read-only: true while classic URL/Exo TTS is playing. Does not start playback. */
    fun isUrlTtsPlaying(): Boolean = player.ttsPlayer.isPlaying

    /** Read-only: whisper volume session currently applied. */
    fun isWhisperPlaybackActive(): Boolean = player.isWhisperPlaybackActive

    /** Read-only mic mute (settings / satellite muted flow). */
    fun isMuted(): Boolean = audioInput.muted.value

    fun microphoneCaptureSnapshot(): MicrophoneCaptureSnapshot =
        audioInput.microphoneCaptureSnapshot()

    fun activeAudioSource(): Int? = audioInput.microphoneCaptureSnapshot().activeAudioSource

    fun preferredDeviceId(): Int = audioInput.microphoneCaptureSnapshot().preferredDeviceId

    fun microphoneLastError(): String? = audioInput.microphoneCaptureSnapshot().lastError

    fun isSoftwareAecActive(): Boolean =
        audioInput.microphoneCaptureSnapshot().softwareAecActive

    fun isSoftwareAecPausedForSpeech(): Boolean =
        audioInput.microphoneCaptureSnapshot().softwareAecPausedForSpeech

    fun voicePrintStatusText(): String = voicePrintManager.currentStatusText

    fun isManualWakeVerifyRequired(): Boolean = voicePrintManager.isManualWakeVerifyRequired()

    fun voicePrintRingDebugSnapshot(): String = audioInput.voicePrintDebugSnapshot()

    fun runtimeActiveWakeWords(): List<String> = audioInput.activeWakeWords.value

    fun availableWakeWordCount(): Int = audioInput.availableWakeWords.size

    fun lastWakeWordId(ttlMs: Long = 8_000L): String? = audioInput.lastWakeWordId(ttlMs)

    fun lastWakeAtMs(): Long = audioInput.lastWakeAtMs()

    fun lastWakePhrase(ttlMs: Long = 8_000L): String? = audioInput.lastWakePhrase(ttlMs)

    fun lastWakeConfidence(ttlMs: Long = 8_000L): Float? = audioInput.lastWakeConfidence(ttlMs)

    fun playbackEnergyLevel(): Float = PlaybackEnergyMonitor.currentLevel()

    fun pcmTtsVolume(): Float? =
        if (pcmTtsPlayerLazy.isInitialized()) pcmTtsPlayer.volume else null

    /** Host-only TTS URL for diagnostics (no query/token). */
    fun lastTtsUrlHost(): String? {
        val raw = player.ttsPlayer.lastPlayedUrl ?: return null
        return runCatching {
            val uri = android.net.Uri.parse(raw)
            buildString {
                append(uri.host ?: "—")
                val path = uri.path
                if (!path.isNullOrBlank() && path != "/") {
                    append(path.take(48))
                    if (path.length > 48) append("…")
                }
            }
        }.getOrNull()
    }

    fun sessionAccentColorHex(): String =
        String.format("#%06X", 0xFFFFFF and currentSessionAccentColor)

    fun sessionAccentWakeIndex(): Int = sessionAccentWakeIndexDiag

    fun sessionAccentAgeMs(): Long? {
        val at = sessionAccentAtMs
        if (at == 0L) return null
        return (System.currentTimeMillis() - at).coerceAtLeast(0L)
    }

    fun lastAudioEventLabel(): String? = audioEventManager.lastPublishedLabel()

    fun lastAudioEventAgeMs(): Long? = audioEventManager.lastPublishedAgeMs()

    fun audioEventDeviceTierName(): String = audioEventManager.deviceTier().name

    fun audioEventProbe(): com.example.ava.detection.AudioEventProbeSnapshot =
        audioEventManager.diagnosticsSnapshot()

    fun voicePrintProbe(): com.example.ava.voiceprint.VoicePrintProbeSnapshot =
        voicePrintManager.diagnosticsSnapshot()

    fun wakeLiveProbe(): List<com.example.ava.microwakeword.WakeWordLiveProbe> =
        audioInput.wakeLiveProbe()

    fun wakeBudgetProbe(): com.example.ava.openwakeword.WakeEngineBudgetProbe? =
        audioInput.wakeBudgetProbe()

    fun pendingPcmTtsChunkCount(): Int =
        synchronized(pendingPcmTtsLock) { pendingPcmTtsChunks.size }

    fun pcmTtsQueueDepth(): Int =
        if (pcmTtsPlayerLazy.isInitialized()) pcmTtsPlayer.queueDepth() else 0

    fun pcmTtsBytesSubmitted(): Long =
        if (pcmTtsPlayerLazy.isInitialized()) pcmTtsPlayer.bytesSubmitted() else 0L

    private fun disablePcmPlaybackEnergy() {
        PlaybackEnergyMonitor.setEnabled(false)
        PlaybackEnergyMonitor.reset()
    }

    private fun startPcmTtsStream() {
        if (pcmTtsPlayer.isActive()) {
            Log.d(TAG, "PCM TTS stream already active, flushing pending chunks")
            flushPendingPcmTtsChunks()
            return
        }
        player.ttsPlayer.cancelActivePlayback()
        maybeApplyWhisperResponse()
        pcmTtsPlayer.volume = player.ttsOutputVolume()
        pcmTtsPlayer.onPlaybackStarted = {
            PlaybackEnergyMonitor.setEnabled(true)
            player.ttsPlayer.onTtsPlaybackStarted?.invoke()
        }
        if (!pcmTtsPlayer.start()) {
            Log.e(TAG, "PCM TTS stream failed to start")
            clearPendingPcmTtsChunks()
            scope.launch { player.ttsPlayer.triggerCompletion() }
            return
        }
        flushPendingPcmTtsChunks()
    }

    private fun bufferPendingPcmTtsChunk(pcm: ByteArray) {
        synchronized(pendingPcmTtsLock) {
            while (pendingPcmTtsChunks.size >= MAX_PENDING_PCM_TTS_CHUNKS) {
                pendingPcmTtsChunks.removeFirst()
            }
            pendingPcmTtsChunks.addLast(pcm)
        }
    }

    private fun flushPendingPcmTtsChunks() {
        val chunks = synchronized(pendingPcmTtsLock) {
            pendingPcmTtsChunks.toList().also { pendingPcmTtsChunks.clear() }
        }
        if (chunks.isNotEmpty()) {
            Log.d(TAG, "Flushing ${chunks.size} buffered PCM TTS chunks")
        }
        chunks.forEach { pcmTtsPlayer.write(it) }
    }

    private fun clearPendingPcmTtsChunks() {
        synchronized(pendingPcmTtsLock) {
            pendingPcmTtsChunks.clear()
        }
    }

    private fun finishPcmTtsStream() {
        pcmTtsPlayer.markStreamEnded()
    }

    /** Stop speaker PCM without firing completion — used when falling back to URL. */
    private fun abandonPcmTtsStream() {
        clearPendingPcmTtsChunks()
        pcmTtsPlayer.stop()
        disablePcmPlaybackEnergy()
    }

    private suspend fun sendVoiceAssistantStartRequest(
        wakeWordPhrase: String = "",
        useConversationId: Boolean = false,
    ) {
        if (!voiceChannelEnabled) {
            return
        }
        sendMessage(
            voiceAssistantRequest
            {
                start = true
                this.wakeWordPhrase = wakeWordPhrase
                if (useConversationId && haConversationId.isNotBlank()) {
                    conversationId = haConversationId
                }
            })
    }
    
    private suspend fun sendVoiceAssistantStopRequest() {
        if (!voiceChannelEnabled) {
            return
        }
        Log.d(TAG, "Sending voice assistant stop request")
        sendMessage(
            voiceAssistantRequest
            {
                start = false
            })
    }

    private suspend fun sendAudioEnd(expectedGeneration: Long? = null): Boolean {
        val closedGeneration = synchronized(pendingMicAudioLock) {
            if (expectedGeneration != null && !micAudioDeliveryGate.isCurrent(expectedGeneration)) return false
            closePendingMicAudio()
            micAudioDeliveryGate.generation
        }
        return pendingMicAudioFlushMutex.withLock {
            if (synchronized(pendingMicAudioLock) { closedGeneration != micAudioDeliveryGate.generation }) {
                return@withLock false
            }
            Log.d(TAG, "Sending audio end signal to HASS")
            sendMessage(voiceAssistantAudio { end = true })
            true
        }
    }

    /**
     * Voice-call dodge: drop the capture / HA listen line so AvaVoice can take
     * the mic. Does not cancel the remote seat and does not stop TTS — the
     * later [playLocalReply] callback still plays, and hang-up restores wake.
     */
    suspend fun yieldCaptureForVoiceCall() {
        audioInput.isStreaming = false
        val current = _state.value
        if (current != Listening && current != Processing && !stateMachine.isWaking) {
            Log.d(TAG, "yieldCaptureForVoiceCall: channel idle state=$current hold=${_remoteAiHold.value}")
            return
        }
        Log.d(TAG, "yieldCaptureForVoiceCall: unwind capture state=$current hold=${_remoteAiHold.value}")
        stopSatellite(yieldCaptureOnly = true)
    }

    private suspend fun stopSatellite(skipHaStop: Boolean = false, yieldCaptureOnly: Boolean = false) {
        isAskQuestionMode = false
        askQuestionTimeoutJob?.cancel()
        askQuestionTimeoutJob = null
        val listeningDurationMs = System.currentTimeMillis() - listeningStartedAt
        val wasListeningWithoutEvent = !searchListenOnly &&
            (_state.value == Listening) && !haReceivedPipelineEvent && listeningDurationMs > 1500
        // Remote seat still owns the turn: unwind the HA channel only. Do not
        // drop chrome / duck / chorus — that would flash idle under the cloud model.
        // Yield-for-call is the same keep: TTS / remote job must outlive the mic handoff.
        val remoteSeat = _remoteAiHold.value
        val keepSeat = remoteSeat || yieldCaptureOnly
        if (!keepSeat) {
            wakeAnimationPresented = false
        }
        stateMachine.reset()
        stateMachine.setStopRequested(true)
        clearFabRenewRunStartWatchdog()
        clearFabCapWatchdog()
        resetPendingMicAudio()
        stopWakePreRoll()
        if (!yieldCaptureOnly) {
            clearPendingPcmTtsChunks()
            pcmTtsPlayer.stop()
        }
        audioInput.isStreaming = false
        if (!keepSeat) {
            localReplyWatchdogJob?.cancel()
            localReplyWatchdogJob = null
            player.ttsPlayer.stop()
        }
        endWhisperSession()
        if (!keepSeat) {
            player.unDuck()
            restoreHaMediaPlayerVolume()
        }
        if (!skipHaStop) {
            sendVoiceAssistantStopRequest()
        }
        _state.value = Connected
        // Clear mel/hit state after dialogue so TTS residue cannot false-trigger.
        // Re-arm waits only for the mel window to refill (~1.3s), not an extra
        // stream_lag_frames warmup (that was a JS-field misuse).
        if (!keepSeat) {
            audioInput.resetWakeWordDetector()
            announceChorusSessionEnd()
        }
        
        if (wasListeningWithoutEvent) {
            Log.e(TAG, "Pipeline stopped without receiving any events - HA pipeline may not be configured")
            showErrorToast(context.getString(R.string.pipeline_error_no_response))
        }

        if (!keepSeat) {
            restorePreferredMediaRoute()
        }
        
        sendMessage(voiceAssistantAnnounceFinished { })
        
        voicePrintManager.onPipelineSessionEnd()
        if (!keepSeat) {
            dispatchVoicePipeline(ModVoicePipeline.Events.SESSION_ENDED)
            onConversationEnd?.invoke()
            searchListenOnly = false
            isQuickWakeSession = false
            clearFabListenStitch()
            QuickWakePushToTalk.clearHoldTurn()
        }
    }

    fun isTimerRinging(): Boolean = timerFinished
    
    fun stopTimer() {
        if (timerFinished) {
            setTimerRinging(false)
            stateMachine.reset()
            resetPendingMicAudio()
            stopWakePreRoll()
            audioInput.isStreaming = false
            player.ttsPlayer.stop()
            endWhisperSession()
            player.unDuck()
            _state.value = Connected
            onConversationEnd?.invoke()
        }
    }
    
    fun onQuickEntityTimerFinished() {
        if (!timerFinished) {
            timerFinishedSoundUri = null
            setTimerRinging(true)
            player.duck()
            scope.launch {
                player.playTimerFinishedSound {
                    scope.launch { onTimerFinished() }
                }
            }
        }
    }

    fun onDreamClockTimerFinished(soundUri: String) {
        if (timerFinished) return
        timerFinishedSoundUri = soundUri.takeIf { it.isNotBlank() }
        setTimerRinging(true)
        player.duck()
        scope.launch {
            player.playTimerFinishedSound(soundUri) {
                scope.launch { onTimerFinished() }
            }
        }
    }

    private suspend fun onTtsFinished() {
        dispatchVoicePipeline(ModVoicePipeline.Events.TTS_FINISHED)
        endWhisperSession()
        sendMessage(voiceAssistantAnnounceFinished { })
        
        val shouldContinue = continuousForVoiceWake() &&
            player.enableContinuousConversation.get() &&
            stateMachine.continueConversation
        stateMachine.continueConversation = true
        
        if (!_remoteAiHold.value) {
            announceChorusSessionEnd()
        }
        if (shouldContinue) {
            var waitCount = 0
            while (!stateMachine.intentEnded && waitCount < 50) {
                delay(100)
                waitCount++
            }
            // Start speaking→listening motion and chime together (not after settle / blind wait).
            // Button continue / button-only mode: do not open Esper / ripple — the FAB listens.
            if (!suppressWakeChrome()) {
                val listening = onListeningStarted
                if (listening != null) {
                    listening.invoke(sessionAccentColor)
                    wakeAnimationPresented = true
                } else {
                    wakeAnimationPresented = false
                }
            }
            player.playContinuousPromptSound {
                scope.launch { wakeSatellite(isContinueConversation = true) }
            }
        } else {
            val remoteSeat = _remoteAiHold.value
            if (!remoteSeat) {
                player.unDuck()
                restoreHaMediaPlayerVolume()
            }
            _state.value = Connected

            if (!remoteSeat) {
                restorePreferredMediaRoute()
                audioInput.resetWakeWordDetector()
                onConversationEnd?.invoke()
            }
        }
    }
    
    private var haMediaWasPlaying = false
    private var haMediaResumeJob: kotlinx.coroutines.Job? = null
    
    
    private fun cancelHaMediaResume() {
        haMediaResumeJob?.cancel()
        haMediaResumeJob = null
    }

    private fun restorePreferredMediaRoute() {
        val decision = MediaResumeArbiter.decide(
            MediaRouteSignals(
                builtInMediaWasPlaying = wasMusicPlaying && savedMusicPosition > 0,
                haMediaWasPlaying = haMediaWasPlaying,
                sendspinProtocolActive = isSendspinProtocolActive(),
                voiceTtsActive = player.ttsPlayer.isPlaying,
                allowOtherMediaResume = true
            )
        )

        when (decision.resumeAction) {
            MediaResumeAction.RESUME_BUILT_IN_MEDIA -> {
                player.seekTo(savedMusicPosition)
                player.mediaPlayer.unpause()
                player.onMediaResume?.invoke()
                wasMusicPlaying = false
                savedMusicPosition = 0L
                haMediaWasPlaying = false
            }
            MediaResumeAction.RESUME_HA_MEDIA -> {
                wasMusicPlaying = false
                savedMusicPosition = 0L
                resumeHaMediaPlayer()
            }
            MediaResumeAction.RESUME_OTHER_MEDIA -> {
                wasMusicPlaying = false
                savedMusicPosition = 0L
                haMediaWasPlaying = false
                triggerOtherMediaResume()
            }
            MediaResumeAction.NONE -> {
                wasMusicPlaying = false
                savedMusicPosition = 0L
                haMediaWasPlaying = false
            }
        }
    }
    
    private fun resumeHaMediaPlayer() {
        if (!haMediaWasPlaying) return
        
        haMediaWasPlaying = false
        haMediaResumeJob?.cancel()
        haMediaResumeJob = scope.launch {
            val mediaPlayerEntity = settingsStore.get().haMediaPlayerEntity
            if (mediaPlayerEntity.isEmpty()) return@launch
            
            callHaMediaPlayerService("media_pause")
            delay(1000)
            callHaMediaPlayerService("media_play_pause")
        }
    }
    
    private suspend fun duckHaMediaPlayerVolume() {
        val settings = settingsStore.get()
        if (settings.haMediaPlayerEntity.isEmpty()) return
        // Already holding a saved level (e.g. continue-conversation re-wake before
        // restore ran): don't resend volume_set or clobber the saved loud volume.
        if (savedHaVolumeLevel >= 0) return
        
        val currentVolume = player.haVolumeLevel.value
        if (currentVolume <= settings.haMediaPlayerDuckVolume) return
        
        savedHaVolumeLevel = currentVolume
        callHaMediaPlayerServiceWithData("volume_set", mapOf("volume_level" to settings.haMediaPlayerDuckVolume.toString()))
    }
    
    private suspend fun restoreHaMediaPlayerVolume() {
        val settings = settingsStore.get()
        if (settings.haMediaPlayerEntity.isEmpty()) return
        if (savedHaVolumeLevel < 0) return
        
        callHaMediaPlayerServiceWithData("volume_set", mapOf("volume_level" to savedHaVolumeLevel.toString()))
        savedHaVolumeLevel = -1f
    }

    
    private fun triggerOtherMediaResume() {
        scope.launch {
            delay(300)
            try {
                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    triggerOtherMediaResumeApi26(audioManager)
                } else {
                    @Suppress("DEPRECATION")
                    val result = audioManager.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    if (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        Log.d(TAG, "Trigger media resume (legacy): focus granted, releasing immediately")
                        delay(50)
                        @Suppress("DEPRECATION")
                        audioManager.abandonAudioFocus(null)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to trigger other media resume", e)
            }
        }
    }
    
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private suspend fun triggerOtherMediaResumeApi26(audioManager: android.media.AudioManager) {
        val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .build()
        
        val result = audioManager.requestAudioFocus(focusRequest)
        if (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(TAG, "Trigger media resume: focus granted, releasing immediately")
            delay(50)
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private suspend fun onTimerFinished() {
        // Ring cadence gates the voice "stop": the chime's loud first second
        // masks the stop template entirely, so the DSP can only complete
        // inside the quiet part of the cycle. Host probe (ring_probe.cc,
        // real chime as echo at -6/-20/-30 dB): with a 1 s pause the usable
        // window is ~1 s per cycle and a reactive "stop" almost always
        // collides with the next chime; 2.5 s widens it to ~2.25 s and the
        // template completes across all echo levels.
        delay(2500)
        if (timerFinished) {
            val uri = timerFinishedSoundUri
            if (!uri.isNullOrBlank()) {
                player.playTimerFinishedSound(uri) {
                    scope.launch { onTimerFinished() }
                }
            } else {
                player.playTimerFinishedSound {
                    scope.launch { onTimerFinished() }
                }
            }
        } else {
            player.unDuck()
        }
    }

    fun toggleMicMute() {
        if (!voiceChannelEnabled) return
        val current = audioInput.muted.value
        audioInput.setMuted(!current)
        Log.i(TAG, "Mic mute toggled: ${!current}")
    }
    
    fun setMicMute(muted: Boolean) {
        if (!voiceChannelEnabled) return
        if (audioInput.muted.value == muted) return
        audioInput.setMuted(muted)
        Log.i(TAG, "Mic mute set: $muted")
    }

    fun setMicVolume(volume: Float) {
        val v = volume.coerceIn(0f, 2f)
        audioInput.setMicrophoneVolume(v)
        scope.launch { experimentalSettingsStore.setMicrophoneVolume(v) }
    }

    fun micVolume(): Float = audioInput.microphoneVolume.value

    fun setVideoRecording(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            cameraModule.setVideoRecordingEnabled(enabled)
        }
    }

    suspend fun pauseCameraForVoiceCallVideo() {
        cameraModule.pauseForVoiceCallVideo()
    }

    suspend fun startVoiceCallVideo(
        useFrontCamera: Boolean,
        onFrame: (ByteArray) -> Unit
    ): Boolean {
        val quality = VoiceCallVideoQuality.fromStored(playerSettingsStore.get().voiceCallVideoQuality)
        val params = quality.params()
        AvaVoiceVideoBridge.setRemoteUiMinInterval(params.remoteUiMinIntervalMs)
        return cameraModule.startVoiceCallVideo(useFrontCamera, params, onFrame)
    }

    suspend fun restartVoiceCallVideo(
        useFrontCamera: Boolean,
        onFrame: (ByteArray) -> Unit
    ) {
        val quality = VoiceCallVideoQuality.fromStored(playerSettingsStore.get().voiceCallVideoQuality)
        val params = quality.params()
        AvaVoiceVideoBridge.setRemoteUiMinInterval(params.remoteUiMinIntervalMs)
        cameraModule.restartVoiceCallVideo(useFrontCamera, params, onFrame)
    }

    suspend fun stopVoiceCallVideo() {
        cameraModule.stopVoiceCallVideo()
    }

    suspend fun resumeCameraAfterVoiceCallVideo() {
        cameraModule.resumeAfterVoiceCallVideo()
    }
    
    fun manualWake() {
        if (!voiceChannelEnabled) return
        scope.launch {
            onWakeDetected(
                wakeWordPhrase = "manual",
                wakeWordId = "",
                wakeConfidence = com.example.ava.voiceprint.VoicePrintManager.MANUAL_WAKE_CONFIDENCE,
                syntheticWake = true,
            )
        }
    }

    /**
     * Push-to-talk release. Closes the mic uplink and sends HA the end-of-audio marker so
     * STT finalizes on the spot instead of waiting for server-side VAD, and the pipeline
     * continues into intent / TTS. Unlike [stopVoiceSession] this is not an abort — the
     * words the user just said are still recognized.
     *
     * If the earcon is still holding the uplink (very short hold) it is opened first so the
     * pre-roll speech reaches HA before the marker. If RUN_START has not arrived yet, waits
     * briefly for it: an end marker before HA opens its audio queue would be dropped.
     */
    fun finishManualSpeech() {
        if (!voiceChannelEnabled) return
        scope.launch {
            // A quick hold can release before the wake coroutine has even set Listening.
            // Wait for the turn to exist rather than dropping the marker on the floor.
            var waitedForListening = 0L
            while (_state.value != Listening && waitedForListening < PTT_RUN_START_WAIT_MS) {
                if (_state.value == Processing || _state.value == Responding) return@launch
                delay(PTT_RUN_START_POLL_MS)
                waitedForListening += PTT_RUN_START_POLL_MS
            }
            if (_state.value != Listening) return@launch
            clearFabCapWatchdog()
            val generation = synchronized(pendingMicAudioLock) { micAudioDeliveryGate.generation }
            if (stateMachine.isWakePhase) {
                wakeSoundUplinkWatchdog?.cancel()
                wakeSoundUplinkWatchdog = null
                openUplinkAfterWakeSound("push-to-talk release", generation)
            }
            var waitedMs = 0L
            while (_state.value == Listening && !pipelineAcceptingAudio && waitedMs < PTT_RUN_START_WAIT_MS) {
                if (!synchronized(pendingMicAudioLock) { micAudioDeliveryGate.isCurrent(generation) }) return@launch
                delay(PTT_RUN_START_POLL_MS)
                waitedMs += PTT_RUN_START_POLL_MS
            }
            if (_state.value != Listening) return@launch
            val spliceAge = if (fabListenOpenedAt == 0L) Long.MAX_VALUE
                else System.currentTimeMillis() - fabListenOpenedAt
            val skipEndOnFreshSplice = isQuickWakeSession &&
                fabListenSpliced &&
                (
                    FabListenRenew.isFreshSpliceWindow(spliceAge) ||
                        !pipelineAcceptingAudio
                    )
            // holdTurn stays set: shouldRenew treats finger-up as commit, not a tap splice.
            stateMachine.noteManualSpeechEnd(acceptReplies = !skipEndOnFreshSplice)
            if (skipEndOnFreshSplice) {
                audioInput.isStreaming = false
                _state.value = Processing
                Log.d(TAG, "push-to-talk: skip audio end, splice window still opening")
                return@launch
            }
            // Give the last queued frames a beat to leave before the marker.
            delay(PTT_TAIL_FLUSH_MS)
            if (!synchronized(pendingMicAudioLock) { micAudioDeliveryGate.isCurrent(generation) }) return@launch
            audioInput.isStreaming = false
            if (!sendAudioEnd(generation)) return@launch
            _state.value = Processing
            Log.d(TAG, "push-to-talk: audio end sent after ${waitedMs}ms wait")
        }
    }

    /**
     * STT-only branch for the Music Assistant search box. Reuses the HA uplink,
     * then [stopSatellite] on STT_END so intent / TTS / wake UI never run.
     */
    fun searchListen() {
        if (!voiceChannelEnabled) return
        scope.launch {
            onWakeDetected(
                wakeWordPhrase = "search",
                wakeWordId = "",
                wakeConfidence = com.example.ava.voiceprint.VoicePrintManager.MANUAL_WAKE_CONFIDENCE,
                syntheticWake = true,
                searchListen = true,
            )
        }
    }

    fun clearVoicePrintProfiles() {
        voicePrintManager.clearStoredProfiles()
    }

    fun clearVoicePrintUserProfile(userIndex: Int) {
        voicePrintManager.clearUserProfile(userIndex)
    }

    /**
     * Suspend/resume wake + stop word detection without stopping the mic. Used by the manual
     * voiceprint enrollment sheet so spoken samples don't trigger a wake (or its overlay).
     */
    fun setWakeDetectionSuspended(value: Boolean) {
        if (value) {
            audioInput.setVoicePrintCaptureEnabled(true)
        }
        audioInput.setWakeDetectionSuspended(value)
    }

    /** Capture one guided enrollment sample. Returns true if the engine accepted it. */
    suspend fun enrollVoicePrintSample(userIndex: Int = 0): Boolean {
        audioInput.setVoicePrintCaptureEnabled(true)
        return voicePrintManager.enrollManualSample(audioInput, userIndex = userIndex).accepted
    }

    fun markVoicePrintEnrollmentStart() {
        audioInput.setVoicePrintCaptureEnabled(true)
        audioInput.markVoicePrintEnrollmentStart()
    }

    fun stopVoicePrintEnrollmentListen() {
        audioInput.stopVoicePrintEnrollmentListen()
    }
    
    fun stopVoiceSession() {
        if (!voiceChannelEnabled) return
        if (conversationEngineHold) {
            ModConversationEngine.revokeCurrent(ModConversationEngine.Reasons.REVOKED_STOP)
            return
        }
        if (!isAssistTurnActive()) return
        localIntentFallback.cancel()
        player.ttsPlayer.stop()
        if (pcmTtsPlayerLazy.isInitialized()) {
            clearPendingPcmTtsChunks()
            pcmTtsPlayer.stop()
        }
        scope.launch {
            stopSatellite()
        }
    }

    /**
     * Stop-word style abort when a small disruption (e.g. wake-word model change)
     * is about to tear down the mic via flatMapLatest. Sets [stopRequested] so late
     * TTS_* events are ignored the same way RUN_START already is.
     */
    suspend fun abortVoiceSessionIfActive() {
        if (!voiceChannelEnabled) return
        if (conversationEngineHold) {
            ModConversationEngine.revokeCurrent(ModConversationEngine.Reasons.REVOKED_STOP)
            return
        }
        val currentState = _state.value
        if (currentState != Listening &&
            currentState != Processing &&
            currentState != Responding &&
            !stateMachine.isWaking &&
            !_remoteAiHold.value
        ) {
            return
        }
        Log.d(TAG, "Aborting active voice session before mic disruption (state=$currentState hold=${_remoteAiHold.value})")
        localIntentFallback.cancel()
        stopSatellite()
    }
    
    override fun close() {
        conversationEngineLeaseJob?.cancel()
        conversationEngineLeaseJob = null
        ModConversationEngine.detachHost()
        val held = conversationEngineHold
        conversationEngineHold = false
        ModConversationEngine.revokeCurrent(ModConversationEngine.Reasons.HOST_TEARDOWN)
        if (held) {
            audioInput.setTemporaryPaused(false)
        }
        HaStateArbiter.clearEsphomeResync()
        localReplyWatchdogJob?.cancel()
        localReplyWatchdogJob = null
        if (voiceChannelEnabled) {
            runCatching { localIntentFallback.release() }
        }
        // Stop before scope.cancel() so we clear the job list even if a reload races.
        stopModEntityRefreshers()
        // CameraX must be unbound here — stopVoiceSatellite() only calls close(), not
        // stopSatellite(). Leaving the lazy camera module alive keeps the HAL streaming
        // across service stop / restartVoiceSatellite().
        if (cameraModuleLazy.isInitialized()) {
            runCatching { cameraModule.close() }
                .onFailure { Log.w(TAG, "cameraModule.close failed", it) }
        }
        if (pcmTtsPlayerLazy.isInitialized()) {
            clearPendingPcmTtsChunks()
            runCatching { pcmTtsPlayer.stop() }
                .onFailure { Log.w(TAG, "pcmTtsPlayer.stop failed", it) }
        }
        super.close()
        audioEventManager.close()
        voicePrintManager.close()
        player.close()
        bluetoothModule.stop()
        bluetoothManager.stopClaimSync()
        sensorsModule.stop()
        screenModule.close()
        diagnosticsModule.stop()
        occupancyModule.stop()
    }

    
    private fun isSTTError(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        
        val errorPatterns = listOf(
            "list index out of range",
            "index out of range",
            "indexerror",
            "exception",
            "failed to",
            "unable to",
            "could not"
        )
        
        val lowerText = text.lowercase()
        return errorPatterns.any { pattern -> lowerText.contains(pattern) }
    }

    
    private fun isTTSAboutError(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        
        val lowerText = text.lowercase()
        
        return lowerText.contains("list index out of range") ||
               lowerText.contains("索引超出范围") ||
               lowerText.contains("索引错误") ||
               (lowerText.contains("index") && lowerText.contains("range") && lowerText.contains("error"))
    }

    companion object {
        private const val TAG = "VoiceSatellite"
        /** Push-to-talk: how long a release waits for HA RUN_START before giving up on the marker. */
        private const val PTT_RUN_START_WAIT_MS = 2_000L
        private const val PTT_RUN_START_POLL_MS = 40L
        /** Push-to-talk: lets the frames captured up to release leave the socket before end=true. */
        private const val PTT_TAIL_FLUSH_MS = 120L
        /**
         * Longest the mic uplink may stay closed for the wake earcon. The stock
         * chime is ~1.3 s; anything longer (custom ringtone) reverts to the old
         * behaviour of streaming over the sound rather than starving HA's STT.
         */
        private const val WAKE_SOUND_UPLINK_MAX_HOLD_MS = 2_500L

        /** 16 kHz mono PCM16 → 32 bytes per millisecond. */
        private const val WAKE_PRE_ROLL_BYTES_PER_MS = 32
        /**
         * Pending HA queue cap (~15 s of 16 kHz mono PCM16). Only fills while HA has
         * not yet sent RUN_START — a cold pipeline (STT provider loading) can take
         * seconds, and the queue must hold pre-roll plus live frames without dropping
         * the head of the utterance.
         */
        private const val MAX_PENDING_MIC_AUDIO_BYTES = 15_000 * WAKE_PRE_ROLL_BYTES_PER_MS
        /** Leak insurance: capture self-stops if no session opened or tore it down. */
        private const val WAKE_PRE_ROLL_MAX_CAPTURE_MS = 15_000L
        /** HA normally answers a start request in well under a second; 4 s is a dead splice. */
        private const val FAB_RENEW_RUN_START_TIMEOUT_MS = 4_000L
        /** One 20ms silence frame @ 16 kHz s16le — pry HA STT, then inject text. */
        private val FAB_SPACER_PCM: ByteString = ByteString.copyFrom(ByteArray(640))
        /** Pre-roll frames are coalesced to this chunk size before the pending queue. */
        private const val WAKE_PRE_ROLL_FLUSH_CHUNK_BYTES = 4_096
        private const val MAX_PENDING_PCM_TTS_CHUNKS = 256
        /** Non-existent service: any ActionResponse means allow_service_calls is on. */
        private const val HA_SERVICE_CALLS_PROBE_SERVICE = "ava.ha_service_probe"
        private const val HA_SERVICE_CALLS_PROBE_TIMEOUT_MS = 10_000L
        /** Sentinel for "no probe in flight"; real call ids are always positive. */
        private const val HA_SERVICE_CALLS_PROBE_CALL_ID_NONE = -1

        /** Process-wide: at most one probe send (or skipped attempt) per app process. */
        private val haServiceProbeSent = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    
    fun onScreenTouch(isTouching: Boolean) {
    }

    private fun bufferPendingMicAudioLocked(audio: ByteString) {
        pendingMicAudioBuffer.addLast(audio)
        pendingMicAudioBytes += audio.size()
        // Byte-based cap: a cold HA pipeline can hold RUN_START for seconds while the
        // queue absorbs pre-roll plus live frames; a small chunk-count cap silently
        // dropped the head of the utterance in exactly that window.
        while (pendingMicAudioBytes > MAX_PENDING_MIC_AUDIO_BYTES && pendingMicAudioBuffer.size > 1) {
            pendingMicAudioBytes -= pendingMicAudioBuffer.removeFirst().size()
        }
    }

    private suspend fun flushPendingMicAudio() = pendingMicAudioFlushMutex.withLock {
        val generation = synchronized(pendingMicAudioLock) { micAudioDeliveryGate.generation }
        while (true) {
            val chunk = synchronized(pendingMicAudioLock) {
                if (generation != micAudioDeliveryGate.generation || !micAudioDeliveryGate.canFlush) {
                    null
                } else if (pendingMicAudioBuffer.isEmpty()) {
                    pipelineAcceptingAudio = true
                    null
                } else {
                    val next = pendingMicAudioBuffer.removeFirst()
                    pendingMicAudioBytes -= next.size()
                    next
                }
            }
            if (chunk == null) return@withLock
            sendMessage(voiceAssistantAudio { data = chunk })
        }
    }

    /**
     * Pending HA queue only. Pre-roll capture has its own explicit lifecycle
     * ([startWakePreRoll] / [stopWakePreRoll] / [openUplinkAfterWakeSound]) — clearing
     * it here would wipe the gate-chain audio right as wakeSatellite starts.
     */
    private fun resetPendingMicAudio() {
        synchronized(pendingMicAudioLock) {
            pipelineAcceptingAudio = false
            micAudioDeliveryGate.reset()
            pendingMicAudioBuffer.clear()
            pendingMicAudioBytes = 0
        }
    }

    private fun closePendingMicAudio() {
        synchronized(pendingMicAudioLock) {
            pipelineAcceptingAudio = false
            micAudioDeliveryGate.close()
            pendingMicAudioBuffer.clear()
            pendingMicAudioBytes = 0
        }
    }
}
