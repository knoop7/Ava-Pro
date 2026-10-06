package com.example.ava.voice

import android.content.Context
import android.util.Log
import com.example.ava.services.VoiceMessageOverlayService
import com.example.ava.services.VoiceMessagePlaybackOverlayService
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.playerSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HaUiSession(
    val mode: String,
    val targets: List<AvaVoiceDevice>,
    val messageSeconds: Int,
    val delayMinutes: Int,
) {
    val withVideo: Boolean get() = mode == "video_call" || mode == "video"
}

object AvaVoiceHaController {
    private const val TAG = "AvaVoiceHa"
    private const val DISCOVERY_WAIT_MS = 2_000L
    private const val DEFAULT_MESSAGE_SECONDS = 10
    private const val EMPTY_NEARBY_LABEL = "-"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Diagnostic display only — not used by voice_action. */
    private val _nearbyDevicesDisplay = MutableStateFlow(EMPTY_NEARBY_LABEL)
    val nearbyDevicesDisplay: StateFlow<String> = _nearbyDevicesDisplay.asStateFlow()

    private val _uiSession = MutableStateFlow<HaUiSession?>(null)
    val uiSession: StateFlow<HaUiSession?> = _uiSession.asStateFlow()

    private var lastRedialSession: HaUiSession? = null

    @Volatile
    private var hangUpInProgress = false

    /** Ensures HA message auto-record runs once per session (survives Compose relayout). */
    @Volatile
    private var messageAutoRecordConsumed = false

    fun hasActiveSession(): Boolean = _uiSession.value != null

    /** Returns true the first time per HA message session. */
    fun tryConsumeMessageAutoRecord(): Boolean {
        if (messageAutoRecordConsumed) return false
        messageAutoRecordConsumed = true
        return true
    }

    init {
        scope.launch {
            AvaVoiceDiscovery.devices.collect { devices ->
                val names = deviceNames(devices)
                _nearbyDevicesDisplay.value = nearbySelectState(names)
            }
        }
    }

    /** Options for the HA diagnostic select (view nearby devices). */
    fun deviceNameOptions(): List<String> {
        val names = deviceNames(AvaVoiceDiscovery.devices.value)
        return if (names.isEmpty()) listOf(EMPTY_NEARBY_LABEL) else names
    }

    fun enqueue(context: Context, args: Map<String, Any>) {
        scope.launch {
            execute(context.applicationContext, args)
        }
    }

    suspend fun execute(context: Context, args: Map<String, Any>) {
        when (normalize(args["action"])) {
            "start" -> start(context, args)
            "redial", "redial_call" -> redial(context)
            "hang_up", "hangup" -> hangUp(context.applicationContext)
            else -> Log.w(TAG, "Unknown action: ${args["action"]}")
        }
    }

    private suspend fun start(context: Context, args: Map<String, Any>) {
        ensureVoiceStack(context.applicationContext)
        val mode = normalize(args["mode"]).ifBlank { "call" }
        val targets = resolveTargets(args)
        if (targets.isEmpty()) {
            Log.w(TAG, "No voice targets resolved for HA start (mode=$mode)")
            return
        }

        when (mode) {
            "call", "video_call", "video", "message", "intercom" -> Unit
            else -> {
                Log.w(TAG, "Unknown mode: $mode")
                return
            }
        }

        if (_uiSession.value != null) {
            hangUp(context.applicationContext)
            delay(200)
        }

        val settings = PlayerSettingsStore(context.playerSettingsStore).get()
        val messageSeconds = when (mode) {
            "message", "intercom" -> readMessageSeconds(args)
            else -> DEFAULT_MESSAGE_SECONDS
        }
        launchSession(
            context = context,
            session = HaUiSession(
                mode = mode,
                targets = targets,
                messageSeconds = messageSeconds,
                delayMinutes = settings.voiceMessageDelayMinutes.coerceIn(0, 1440),
            ),
        )
    }

    private suspend fun redial(context: Context) {
        val last = lastRedialSession ?: run {
            Log.w(TAG, "No previous HA voice session to redial")
            return
        }
        if (_uiSession.value != null) {
            hangUp(context.applicationContext)
            delay(200)
        }
        ensureVoiceStack(context.applicationContext)
        val targets = resolveTargets(
            mapOf("targets" to last.targets.map { device -> device.name }),
        )
        if (targets.isEmpty()) {
            Log.w(TAG, "Redial targets no longer available")
            return
        }
        launchSession(
            context = context,
            session = last.copy(targets = targets),
        )
        Log.d(TAG, "HA voice redial mode=${last.mode} targets=${targets.size}")
    }

    private suspend fun launchSession(context: Context, session: HaUiSession) {
        lastRedialSession = session
        messageAutoRecordConsumed = false
        _uiSession.value = session
        AvaVoiceMicrophoneGuard.yieldAssistantMicForVoiceSession()
        withContext(Dispatchers.Main) {
            VoiceMessageOverlayService.showHaSession(context.applicationContext)
        }
        Log.d(TAG, "HA voice UI session started mode=${session.mode} targets=${session.targets.size}")
    }

    suspend fun hangUp(context: Context) {
        if (hangUpInProgress) return
        hangUpInProgress = true
        try {
            hangUpInternal(context)
        } finally {
            hangUpInProgress = false
        }
    }

    /**
     * Fire-and-forget hang-up on the controller's own scope. For teardown paths
     * (e.g. Service.onDestroy) where runBlocking on the main thread would ANR on
     * slow networks. hangUp is idempotent, so late completion is safe.
     */
    fun hangUpAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                hangUp(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Async hangUp failed", e)
            }
        }
    }

    private suspend fun hangUpInternal(context: Context) = withContext(Dispatchers.IO) {
        val session = _uiSession.value
        _uiSession.value = null
        messageAutoRecordConsumed = false

        val localId = AvaVoiceDiscovery.localId()
        // Union the UI session targets with every live call peer known to the hub.
        // HA hang_up is the operator's recovery hammer: it must notify peers of calls
        // that were started from the device UI or whose UI-session tracking desynced —
        // otherwise those peers stay "in call" forever (issue #203).
        val targets = (session?.targets.orEmpty() + AvaVoiceSessionHub.activeCallPeers())
            .distinctBy { it.id }

        if (targets.isNotEmpty()) {
            AvaVoiceMessenger.hangupVoiceCall(localId, targets)
        }

        AvaVoiceNetwork.leaveCallDuplex()
        AvaVoiceCallAudioSession.forceLeave(context.applicationContext)
        AvaVoiceVideoBridge.resetAll()
        AvaVoiceMessenger.abortAllSessionsAndReleaseMic()

        withContext(Dispatchers.Main) {
            VoiceMessagePlaybackOverlayService.dismissActiveSession(context)
            VoiceMessageOverlayService.dismissHaSession(context)
            if (session != null) {
                PlayerSettingsStore(context.playerSettingsStore)
                    .enableVoiceMessageOverlayVisible
                    .set(false)
            }
        }
        Log.d(TAG, "HA voice session torn down")
    }

    private suspend fun ensureVoiceStack(context: Context) {
        val store = PlayerSettingsStore(context.playerSettingsStore)
        val settings = store.get()
        if (!settings.enableVoiceMessageOverlay) {
            store.enableVoiceMessageOverlay.set(true)
        }
        if (!store.get().enableVoiceMessageReceive) {
            store.enableVoiceMessageReceive.set(true)
        }
        VoiceSatelliteService.requestVoiceMessageSync()
        delay(400)
        if (AvaVoiceDiscovery.devices.value.isEmpty()) {
            delay(DISCOVERY_WAIT_MS)
        }
    }

    /**
     * Resolves call targets from service args only.
     * - Explicit [targets] in the service call: use those (supports multiple).
     * - Omitted / empty: all currently discovered nearby devices.
     * The HA select entity is diagnostic-only and never affects this.
     */
    private fun resolveTargets(args: Map<String, Any>): List<AvaVoiceDevice> {
        val requested = readTargetNames(args)
        val devices = AvaVoiceDiscovery.devices.value.filter { it.offersVoiceMessaging }
        val names = requested.ifEmpty {
            deviceNames(devices)
        }
        if (names.isEmpty()) return emptyList()

        val resolved = linkedSetOf<AvaVoiceDevice>()
        names.forEach { name ->
            val exact = devices.filter { device ->
                device.name.equals(name, ignoreCase = true) ||
                    device.id.equals(name, ignoreCase = true)
            }
            if (exact.isNotEmpty()) {
                resolved.addAll(exact)
            } else {
                devices.firstOrNull { device ->
                    device.name.contains(name, ignoreCase = true)
                }?.let(resolved::add)
            }
        }
        return resolved.toList()
    }

    private fun readMessageSeconds(args: Map<String, Any>): Int {
        return when (val raw = args["message_seconds"] ?: args["messageSeconds"]) {
            is Number -> raw.toInt().coerceIn(1, 120)
            is String -> raw.toIntOrNull()?.coerceIn(1, 120) ?: DEFAULT_MESSAGE_SECONDS
            else -> DEFAULT_MESSAGE_SECONDS
        }
    }

    private fun readTargetNames(args: Map<String, Any>): List<String> {
        return when (val raw = args["targets"]) {
            is List<*> -> raw.filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() }
            is String -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    private fun nearbySelectState(names: List<String>): String = when {
        names.isEmpty() -> EMPTY_NEARBY_LABEL
        else -> names.first()
    }

    private fun deviceNames(devices: List<AvaVoiceDevice>): List<String> =
        devices.filter { it.offersVoiceMessaging }.map { it.name }.distinct().sorted()

    private fun normalize(value: Any?): String =
        (value as? String)?.trim()?.lowercase().orEmpty()
}
