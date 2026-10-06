package com.example.ava.voiceprint

import android.content.Context
import android.util.Log
import com.example.ava.audio.PlaybackEnergyMonitor
import com.example.ava.esphome.voicesatellite.VoiceSatelliteAudioInput
import com.example.ava.sensor.PresenceFusionEngine
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.VoicePrintEnrollmentMode
import com.example.ava.settings.WakeWordEngine
import com.example.microfeatures.VoicePrintNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class VoicePrintManager(
    context: Context,
    private val microphoneSettingsStore: MicrophoneSettingsStore,
) {
    private val appContext = context.applicationContext
    private var nativeHandle = VoicePrintNative.create()
    private val outScores = FloatArray(5)

    /** Single low-priority worker — keeps JNI/DFT off the satellite + UI path. */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var activeWakeJob: Job? = null
    private var releaseToIdleJob: Job? = null
    private var wakeGeneration = 0
    /** Generation bound to the current non-idle sensor display for this wake turn. */
    private var displaySessionGeneration = 0
    private var sessionActive = false
    private var lastWakeProcessStartedMs = 0L
    private val verifyWakeMutex = Mutex()

    /** Last processWake scores (diagnostic). */
    @Volatile private var lastMatchStatus: String = "—"
    @Volatile private var lastMatchConfidence = 0f
    @Volatile private var lastMatchQuality = 0f
    @Volatile private var lastMatchU0 = 0f
    @Volatile private var lastMatchU1 = 0f
    @Volatile private var lastMatchMargin = 0f
    @Volatile private var lastMatchAtMs = 0L
    @Volatile private var lastExtractSamples = 0
    @Volatile private var lastExtractWaitedMs = 0

    private val _state = MutableStateFlow<VoicePrintState>(VoicePrintState.Disabled)
    val state = _state.asStateFlow()

    val currentStatusText: String
        get() = _state.value.statusText()

    /** Read-only probe for Voice Stats (Voice Print focus). */
    fun diagnosticsSnapshot(): VoicePrintProbeSnapshot {
        val now = System.currentTimeMillis()
        val identified = (_state.value as? VoicePrintState.Identified)
        return VoicePrintProbeSnapshot(
            statusText = currentStatusText,
            identifiedIndex = identified?.index,
            identifiedConfidence = identified?.confidence ?: 0f,
            lastMatchStatus = lastMatchStatus,
            lastMatchConfidence = lastMatchConfidence,
            lastMatchQuality = lastMatchQuality,
            lastMatchU0 = lastMatchU0,
            lastMatchU1 = lastMatchU1,
            lastMatchMargin = lastMatchMargin,
            lastMatchAgeMs = if (lastMatchAtMs == 0L) -1L else (now - lastMatchAtMs).coerceAtLeast(0L),
            lastExtractSamples = lastExtractSamples,
            lastExtractWaitedMs = lastExtractWaitedMs,
            processGapMs = if (lastWakeProcessStartedMs == 0L) {
                -1L
            } else {
                (now - lastWakeProcessStartedMs).coerceAtLeast(0L)
            },
            manualWakeVerifyRequired = isManualWakeVerifyRequired(),
        )
    }

    private var enabled = false
    private var enrollmentMode = VoicePrintEnrollmentMode.AUTO
    private var manualWakeVerifyEnabled = false
    private var userNames: List<String> = emptyList()
    private var wakeWordEngine = WakeWordEngine.MICRO_WAKE_WORD

    /** Fired when a wake turn resolves to an enrolled speaker (HA esphome event hook). */
    var onIdentifiedUserLabel: ((displayName: String) -> Unit)? = null

    val settingsFlow = microphoneSettingsStore.getFlow()
        .map { settings ->
            SettingsSnapshot(
                enabled = settings.voicePrintEnabled,
                enrollmentMode = settings.voicePrintEnrollmentMode,
                manualWakeVerifyEnabled = settings.voicePrintManualWakeVerifyEnabled,
                userNames = settings.voicePrintUserNames,
                wakeWordEngine = settings.wakeWordEngine,
            )
        }
        .distinctUntilChanged()

    data class SettingsSnapshot(
        val enabled: Boolean,
        val enrollmentMode: VoicePrintEnrollmentMode,
        val manualWakeVerifyEnabled: Boolean,
        val userNames: List<String>,
        val wakeWordEngine: WakeWordEngine,
    )

    data class ManualEnrollResult(
        val accepted: Boolean,
        val quality: Float,
    )

    fun applySettings(snapshot: SettingsSnapshot) {
        val modeChanged = enrollmentMode != snapshot.enrollmentMode
        val engineChanged = wakeWordEngine != snapshot.wakeWordEngine
        enabled = snapshot.enabled
        enrollmentMode = snapshot.enrollmentMode
        manualWakeVerifyEnabled = snapshot.manualWakeVerifyEnabled
        userNames = snapshot.userNames
        wakeWordEngine = snapshot.wakeWordEngine
        if (!enabled) {
            activeWakeJob?.cancel()
            releaseToIdleJob?.cancel()
            displaySessionGeneration = 0
            sessionActive = false
            _state.value = VoicePrintState.Disabled
        } else {
            if (engineChanged && nativeHandle != 0L) {
                VoicePrintNative.reloadProfiles(nativeHandle, storageDir(wakeWordEngine))
            }
            if (!sessionActive || modeChanged || engineChanged) {
                workScope.launch { refreshIdleState() }
            }
        }
    }

    private fun isAutoLearning(): Boolean =
        enrollmentMode == VoicePrintEnrollmentMode.AUTO

    private fun standbyWithoutProfiles(): VoicePrintState =
        if (isAutoLearning()) VoicePrintState.Learning else VoicePrintState.NotEnrolled

    fun isEnabled(): Boolean = enabled

    fun isManualWakeVerifyRequired(): Boolean =
        enabled &&
            enrollmentMode == VoicePrintEnrollmentMode.MANUAL &&
            manualWakeVerifyEnabled

    /** Whether to emit `esphome.voice_print_wake` with the speaker label for HA automations. */
    fun shouldEmitHaWakeUserLabel(): Boolean =
        enabled && (enrollmentMode == VoicePrintEnrollmentMode.AUTO || manualWakeVerifyEnabled)

    /**
     * Manual-mode wake gate: runs voiceprint matching synchronously and only allows wake when an
     * enrolled user is identified. Also updates the HA status sensor.
     */
    suspend fun verifyWakeAllowed(
        audioInput: VoiceSatelliteAudioInput,
        engine: WakeWordEngine,
        wakeConfidence: Float,
    ): Boolean {
        if (!isManualWakeVerifyRequired()) return true
        val confidence = wakeConfidence.coerceIn(0f, 1f)
        return verifyWakeMutex.withLock {
            val generation = ++wakeGeneration
            activeWakeJob?.cancel()
            try {
                val result = processWake(audioInput, engine, confidence, verifyOnly = true)
                val displayState = normalizeDisplayState(result)
                publishWakeDisplay(generation, displayState)
                val allowed = displayState is VoicePrintState.Identified
                if (!allowed) {
                    scheduleReleaseToIdleWithoutPipeline(generation)
                }
                Log.d(
                    TAG,
                    "manual wake gate allowed=$allowed state=${displayState.statusText()} " +
                        "conf=${result.confidence} q=${result.quality}",
                )
                allowed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "manual wake gate verification failed", e)
                false
            }
        }
    }

    /** Primary turn end: User 1/stranger → idle when STT finishes (automation reset). */
    fun onSttEnd() {
        releaseToIdleIfCurrent(displaySessionGeneration, "stt_end")
    }

    /** Sensor-only fallback when the pipeline ends without a matching STT_END. */
    fun onPipelineSessionEnd() {
        releaseToIdleIfCurrent(displaySessionGeneration, "pipeline_end")
    }

    fun scheduleWakeProcessing(
        audioInput: VoiceSatelliteAudioInput,
        engine: WakeWordEngine,
        wakeConfidence: Float = 1f,
        onResult: (VoicePrintResult) -> Unit = {},
    ) {
        if (!enabled) return
        val confidence = wakeConfidence.coerceIn(0f, 1f)
        val generation = ++wakeGeneration
        primeSessionDisplay(confidence)
        activeWakeJob?.cancel()
        activeWakeJob = workScope.launch {
            try {
                val result = processWake(audioInput, engine, confidence, verifyOnly = false)
                if (generation != wakeGeneration) return@launch
                val displayState = normalizeDisplayState(result)
                publishWakeDisplay(generation, displayState)
                onResult(
                    VoicePrintResult(
                        state = displayState,
                        confidence = result.confidence,
                        quality = result.quality,
                        userScores = result.userScores,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == wakeGeneration) {
                    Log.w(TAG, "Voice print processing failed", e)
                }
            }
        }
    }

    private fun primeSessionDisplay(wakeConfidence: Float) {
        if (wakeConfidence < 0.55f) return
        sessionActive = true
        if (!hasEnrolledProfiles() && wakeConfidence >= 0.68f) {
            _state.value = standbyWithoutProfiles()
        }
    }

    private suspend fun processWake(
        audioInput: VoiceSatelliteAudioInput,
        engine: WakeWordEngine,
        wakeConfidence: Float,
        verifyOnly: Boolean,
    ): VoicePrintResult {
        val window = VoicePrintWakeWindows.forEngine(engine)
        // Pin wake anchor before waiting — a later wake must not shift this utterance's window.
        val wakeMark = audioInput.snapshotVoicePrintWakeMark()
        if (wakeMark < 0) {
            Log.w(TAG, "extract failed: no wake mark snap=${audioInput.voicePrintDebugSnapshot()}")
            return VoicePrintResult(state = VoicePrintState.LowQuality, quality = 0f)
        }
        val nowMs = System.currentTimeMillis()
        val gapMs = if (lastWakeProcessStartedMs > 0L) nowMs - lastWakeProcessStartedMs else Long.MAX_VALUE
        val rapidWake = gapMs < WAKE_PROCESS_COOLDOWN_MS
        if (rapidWake) {
            Log.d(TAG, "voiceprint rapid wake gap=${gapMs}ms mark=$wakeMark")
        }
        lastWakeProcessStartedMs = nowMs
        val deadline = window.afterMs + 600
        var waited = 0
        while (waited < deadline) {
            if (audioInput.voicePrintSamplesReady(wakeMark, engine)) break
            delay(20)
            waited += 20
        }

        val pcm = audioInput.extractVoicePrintPcm(wakeMark, engine)
        if (pcm == null || pcm.isEmpty()) {
            Log.w(
                TAG,
                "extract failed waited=${waited}ms mark=$wakeMark " +
                    "ready=${audioInput.voicePrintSamplesReady(wakeMark, engine)} " +
                    "snap=${audioInput.voicePrintDebugSnapshot()} window=${window.beforeMs}+${window.afterMs}ms",
            )
            return VoicePrintResult(state = VoicePrintState.LowQuality, quality = 0f)
        }
        Log.d(
            TAG,
            "extract ok engine=$engine samples=${pcm.size} waited=${waited}ms " +
                "wakeConf=${"%.2f".format(wakeConfidence)} " +
                "durationMs=${pcm.size * 1000 / audioInput.voicePrintSampleRate}",
        )

        val playbackLoud = PlaybackEnergyMonitor.currentLevel() > PLAYBACK_REJECT_LEVEL
        if (playbackLoud) {
            Log.d(TAG, "voiceprint skipped during loud playback level=${PlaybackEnergyMonitor.currentLevel()}")
            return VoicePrintResult(
                state = if (hasEnrolledProfiles(engine)) VoicePrintState.Unknown else standbyWithoutProfiles(),
                quality = 0f,
            )
        }

        val rejectLearning = wakeConfidence < MANUAL_WAKE_CONFIDENCE_CEILING
        val decision = VoicePrintNative.processWake(
            handle = nativeHandle,
            pcm16 = pcm,
            sampleRate = audioInput.voicePrintSampleRate,
            learningEnabled = isAutoLearning(),
            rejectLearning = rejectLearning,
            wakeConfidence = wakeConfidence,
            verifyOnly = verifyOnly,
            storageDir = storageDir(engine),
            outScores = outScores,
        )

        val result = mapDecision(
            decision = decision,
            confidence = outScores[2],
            quality = outScores[3],
        )
        lastMatchStatus = result.statusText
        lastMatchConfidence = result.confidence
        lastMatchQuality = result.quality
        lastMatchU0 = outScores[0]
        lastMatchU1 = outScores[1]
        lastMatchMargin = outScores[4]
        lastMatchAtMs = System.currentTimeMillis()
        lastExtractSamples = pcm.size
        lastExtractWaitedMs = waited
        Log.d(
            TAG,
            "wake decision=${result.statusText} engine=$engine conf=${result.confidence} q=${result.quality} " +
                "mode=$enrollmentMode learn=${isAutoLearning()} verifyOnly=$verifyOnly " +
                "wakeConf=${"%.2f".format(wakeConfidence)} rejectLearning=$rejectLearning " +
                "u0=${"%.2f".format(outScores[0])} u1=${"%.2f".format(outScores[1])} " +
                "margin=${"%.2f".format(outScores[4])}",
        )
        return result
    }

    /**
     * Guided manual enrollment: capture the audio the user just spoke and feed it to the native
     * engine with learning forced on. Runs off the UI thread. Wake detection should be suspended
     * by the caller while the enrollment sheet is open so live wakes do not interfere.
     */
    suspend fun enrollManualSample(
        audioInput: VoiceSatelliteAudioInput,
        userIndex: Int = 0,
    ): ManualEnrollResult = withContext(Dispatchers.Default) {
        if (!enabled || nativeHandle == 0L) {
            return@withContext ManualEnrollResult(accepted = false, quality = 0f)
        }
        val engine = audioInput.wakeWordEngine
        val pcm = audioInput.captureVoicePrintEnrollmentPcm(engine)
        if (pcm == null || pcm.isEmpty()) {
            Log.w(TAG, "manual enroll: empty pcm capture engine=$engine")
            return@withContext ManualEnrollResult(accepted = false, quality = 0f)
        }
        if (PlaybackEnergyMonitor.currentLevel() > PLAYBACK_REJECT_LEVEL) {
            Log.w(TAG, "manual enroll: rejected during loud playback")
            return@withContext ManualEnrollResult(accepted = false, quality = 0f)
        }
        // Dedicated enrollment path: each accepted call adds exactly one template for this user,
        // unlike processWake which gates/reroutes samples and would not reliably store 5 of them.
        val decision = VoicePrintNative.enrollSample(
            handle = nativeHandle,
            pcm16 = pcm,
            sampleRate = audioInput.voicePrintSampleRate,
            userIndex = userIndex.coerceIn(0, 1),
            storageDir = storageDir(engine),
            outScores = outScores,
        )
        val quality = outScores[3]
        val accepted = decision == VoicePrintNative.READY
        releaseToIdleJob?.cancel()
        displaySessionGeneration = 0
        sessionActive = false
        refreshIdleState()
        Log.d(
            TAG,
            "manual enroll engine=$engine user=$userIndex samples=${pcm.size} decision=$decision " +
                "quality=${"%.2f".format(quality)} accepted=$accepted",
        )
        ManualEnrollResult(accepted = accepted, quality = quality)
    }

    fun clearStoredProfiles() {
        activeWakeJob?.cancel()
        releaseToIdleJob?.cancel()
        displaySessionGeneration = 0
        if (nativeHandle != 0L) {
            VoicePrintNative.queryIdleState(nativeHandle, storageDir())
            VoicePrintNative.resetProfiles(nativeHandle)
        }
        VoicePrintStorage.clearProfiles(appContext)
        sessionActive = false
        if (enabled) {
            refreshIdleState()
        } else {
            _state.value = VoicePrintState.Disabled
        }
    }

    fun clearUserProfile(userIndex: Int) {
        if (userIndex !in 0..1) return
        activeWakeJob?.cancel()
        releaseToIdleJob?.cancel()
        displaySessionGeneration = 0
        VoicePrintStorage.clearUserProfile(appContext, userIndex, wakeWordEngine)
        if (nativeHandle != 0L) {
            VoicePrintNative.reloadProfiles(nativeHandle, storageDir())
        }
        sessionActive = false
        if (enabled) {
            refreshIdleState()
        }
    }

    private fun publishWakeDisplay(generation: Int, displayState: VoicePrintState) {
        releaseToIdleJob?.cancel()
        displaySessionGeneration = generation
        sessionActive = isActiveWakeDisplay(displayState)
        _state.value = displayState
        if (displayState is VoicePrintState.Identified) {
            // Presence mark only (two volatile writes) — occupancy math runs elsewhere.
            PresenceFusionEngine.reportEvent(PresenceFusionEngine.Source.VOICEPRINT)
            if (shouldEmitHaWakeUserLabel()) {
                onIdentifiedUserLabel?.invoke(displayState.displayName)
            }
        }
    }

    private fun isActiveWakeDisplay(state: VoicePrintState): Boolean =
        when (state) {
            is VoicePrintState.Identified,
            VoicePrintState.Unknown,
            VoicePrintState.LowQuality,
            -> true
            else -> false
        }

    private fun releaseToIdleIfCurrent(expectedGeneration: Int, reason: String) {
        if (!enabled) return
        if (expectedGeneration == 0 || expectedGeneration != displaySessionGeneration) {
            Log.d(
                TAG,
                "releaseToIdle skipped reason=$reason displayGen=$displaySessionGeneration " +
                    "expected=$expectedGeneration wakeGen=$wakeGeneration",
            )
            return
        }
        if (displaySessionGeneration != wakeGeneration) {
            Log.d(
                TAG,
                "releaseToIdle skipped stale reason=$reason displayGen=$displaySessionGeneration " +
                    "wakeGen=$wakeGeneration",
            )
            return
        }
        Log.d(TAG, "releaseToIdle reason=$reason gen=$expectedGeneration")
        releaseToIdleJob?.cancel()
        displaySessionGeneration = 0
        sessionActive = false
        refreshIdleState()
    }

    private fun scheduleReleaseToIdleWithoutPipeline(generation: Int) {
        releaseToIdleJob?.cancel()
        releaseToIdleJob = workScope.launch {
            delay(RELEASE_TO_IDLE_MS)
            releaseToIdleIfCurrent(generation, "no_pipeline")
        }
    }

    private fun refreshIdleState() {
        if (!enabled || nativeHandle == 0L) return
        _state.value = idleStateFromStore()
    }

    private fun storageDir(engine: WakeWordEngine = wakeWordEngine): String =
        VoicePrintStorage.dir(appContext, engine).absolutePath

    private fun hasEnrolledProfiles(engine: WakeWordEngine = wakeWordEngine): Boolean =
        VoicePrintStorage.hasStoredProfiles(appContext, engine)

    private fun idleStateFromStore(): VoicePrintState {
        if (!hasEnrolledProfiles()) {
            return standbyWithoutProfiles()
        }
        val idle = VoicePrintNative.queryIdleState(nativeHandle, storageDir())
        return when (idle) {
            VoicePrintNative.READY -> VoicePrintState.Ready
            VoicePrintNative.LEARNING -> if (isAutoLearning()) {
                VoicePrintState.Learning
            } else {
                VoicePrintState.NotEnrolled
            }
            else -> VoicePrintState.Ready
        }
    }

    /** Trust native decisions — no Kotlin score override that re-labels unknown as identified. */
    private fun normalizeDisplayState(result: VoicePrintResult): VoicePrintState =
        when (result.state) {
            VoicePrintState.Disabled ->
                if (enabled) idleStateFromStore() else VoicePrintState.Disabled
            else -> result.state
        }

    private fun mapDecision(
        decision: Int,
        confidence: Float,
        quality: Float,
    ): VoicePrintResult {
        val state = when (decision) {
            VoicePrintNative.USER_0 -> VoicePrintState.Identified(
                index = 0,
                displayName = voicePrintDisplayName(0, userNames),
                confidence = confidence,
            )
            VoicePrintNative.USER_1 -> VoicePrintState.Identified(
                index = 1,
                displayName = voicePrintDisplayName(1, userNames),
                confidence = confidence,
            )
            VoicePrintNative.LEARNING -> if (isAutoLearning()) {
                VoicePrintState.Learning
            } else {
                VoicePrintState.NotEnrolled
            }
            VoicePrintNative.READY -> VoicePrintState.Ready
            VoicePrintNative.UNKNOWN -> if (!hasEnrolledProfiles() && !isAutoLearning()) {
                VoicePrintState.NotEnrolled
            } else {
                VoicePrintState.Unknown
            }
            VoicePrintNative.LOW_QUALITY -> VoicePrintState.LowQuality
            else -> VoicePrintState.Disabled
        }
        return VoicePrintResult(
            state = state,
            confidence = confidence,
            quality = quality,
            userScores = floatArrayOf(outScores[0], outScores[1]),
        )
    }

    fun close() {
        activeWakeJob?.cancel()
        releaseToIdleJob?.cancel()
        displaySessionGeneration = 0
        workScope.cancel()
        sessionActive = false
        if (nativeHandle != 0L) {
            VoicePrintNative.destroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    companion object {
        private const val TAG = "VoicePrintManager"
        private const val PLAYBACK_REJECT_LEVEL = 0.12f
        /** manualWake and other non-model triggers stay below native wake-boost tiers. */
        const val MANUAL_WAKE_CONFIDENCE = 0.52f
        private const val MANUAL_WAKE_CONFIDENCE_CEILING = 0.58f
        /** Rapid-wake log threshold — overlaps native identify cooldown window. */
        private const val WAKE_PROCESS_COOLDOWN_MS = 500L
        /** No-pipeline turns (e.g. manual verify fail): stranger → idle after this hold. */
        private const val RELEASE_TO_IDLE_MS = 500L
    }
}
