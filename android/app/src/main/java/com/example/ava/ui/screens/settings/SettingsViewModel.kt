package com.example.ava.ui.screens.settings

import android.app.Application
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import com.example.ava.R
import com.example.ava.detection.AudioEventCatalog
import com.example.ava.detection.AudioEventDisplayDuration
import com.example.ava.detection.AudioEventSensitivity
import com.example.ava.microwakeword.WakeWordProviderFactory
import com.example.ava.microwakeword.Micro
import com.example.ava.microwakeword.WakeWord
import com.example.ava.microwakeword.WakeWordProvider
import com.example.ava.microwakeword.WakeWordWithId
import com.example.ava.settings.MicrophoneSettings
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.RecordingPath
import com.example.ava.settings.SoftwareAecRoom
import com.example.ava.settings.SoftwareAecStrength
import com.example.ava.settings.SoftwareNsStrength
import com.example.ava.settings.VoicePrintEnrollmentMode
import com.example.ava.settings.QuickWakeTrigger
import com.example.ava.settings.WakeMode
import com.example.ava.settings.WakeWordEngine
import com.example.ava.openwakeword.OpenWakeWordModel
import com.example.ava.wakewordlibrary.WakeWordFileKind
import com.example.ava.wakewordlibrary.WakeWordImportResult
import com.example.ava.wakewordlibrary.WakeWordLibraryManager
import android.net.Uri
import com.example.ava.settings.NotificationSettingsStore
import com.example.ava.settings.MediaOverlayStyle
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.DreamClockFace
import com.example.ava.settings.DreamClockFlipFont
import com.example.ava.settings.DreamClockFlipStyle
import com.example.ava.settings.DreamClockSeason
import com.example.ava.settings.SimpleClockPortraitStyle
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.HomeLockSession
import com.example.ava.settings.HomeLockSettingsStore
import com.example.ava.settings.HomeLockTarget
import com.example.ava.settings.HomeCornerButton
import com.example.ava.settings.SidebarDockKey
import com.example.ava.settings.SidebarItemKey
import com.example.ava.settings.SidebarPosition
import com.example.ava.settings.SidebarSettingsStore
import com.example.ava.ui.screens.home.canExposeMinimalLauncherAppsToHa
import com.example.ava.ui.screens.home.syncHideHomeChromePrefFromStores
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.UpdateDownloadMethod
import com.example.ava.settings.UpdateSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.activeStopWordForEngine
import com.example.ava.settings.activeWakeWordsForEngine
import com.example.ava.settings.compatibleWakeWordIdsForEngine
import com.example.ava.settings.wakeWordIdCandidatesForEngine
import com.example.ava.settings.resolveAudioSource
import com.example.ava.settings.homeLockSettingsStore
import com.example.ava.settings.microphoneSettingsStore
import com.example.ava.settings.notificationSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.settings.updateSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.sendspin.SendspinFormatCatalog
import com.example.ava.settings.sendspinSettingsStore
import com.example.ava.utils.SoundUriPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import com.example.ava.settings.NotificationSettings
import com.example.ava.services.SatelliteRestartReason
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.voiceprint.VoicePrintStorage
import com.example.ava.voice.AvaVoiceVideoBridge
import com.example.ava.voice.VoiceCallVideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WakeWordCatalogLoadState { Loading, Ready, Failed }

@Immutable
data class UIState(
    val serverName: String,
    val serverPort: Int,
    val encryptionKey: String = "",
    val macAddress: String = "",
    val haMediaPlayerEntity: String = "",
    val sendspinLowMemoryMode: Boolean = true,
    val sendspinSyncOffsetMs: Int = 0,
    val volumeFollowRule: String = com.example.ava.settings.VolumeFollowRule.INDEPENDENT.storageKey,
    val sendspinPreferredFormat: String = "automatic",
    val sendspinCustomDeviceName: String = "",
    val sendspinEnabled: Boolean = true,
    val sendspinAutoConnect: Boolean = true,
    val sendspinAdvertiseAsPlayer: Boolean = true,
    val sendspinServerUrl: String = "",
)

@Immutable
data class MicrophoneState(
    val wakeWordEngine: WakeWordEngine = WakeWordEngine.MICRO_WAKE_WORD,
    val availableWakeWordEngines: List<WakeWordEngine> = WakeWordEngine.entries,
    val wakeWord: WakeWordWithId,
    val wakeWord2: WakeWordWithId?,
    val wakeWords: List<WakeWordWithId>,
    val sensitivity1: Float = -1f,
    val sensitivity2: Float = -1f,
    /** Extra-strictness slider zone levels (0 = off, 1 = strict+, 2 = max). */
    val extraStrictness1: Int = 0,
    val extraStrictness2: Int = 0,
    /** Stop slot: builtin pseudo-item, an installed model, or null = off. */
    val stopWord: WakeWordWithId? = null,
    /** Picker list for the stop slot: builtin first, then models not taken by wake 1/2. */
    val stopPickerWakeWords: List<WakeWordWithId> = emptyList(),
    val stopWordSensitivity: Float = -1f,
    val audioSourceAutoDetect: Boolean = true,
    val audioSource: Int = MediaRecorder.AudioSource.MIC,
    val noiseSuppressorEnabled: Boolean = true,
    val softwareNsEnabled: Boolean = false,
    val softwareNsStrength: SoftwareNsStrength = SoftwareNsStrength.LIGHT,
    val automaticGainControlEnabled: Boolean = true,
    val acousticEchoCancelerEnabled: Boolean = true,
    val softwareAecEnabled: Boolean = false,
    val softwareAecPauseDuringSpeech: Boolean = false,
    val softwareAecStrength: SoftwareAecStrength = SoftwareAecStrength.STANDARD,
    val softwareAecRoom: SoftwareAecRoom = SoftwareAecRoom.LIVING,
    val voicePrintEnabled: Boolean = false,
    val voicePrintEnrollmentMode: VoicePrintEnrollmentMode = VoicePrintEnrollmentMode.AUTO,
    val voicePrintManualUser0Samples: Int = 0,
    val voicePrintManualUser1Samples: Int = 0,
    val voicePrintManualWakeVerifyEnabled: Boolean = false,
    val voicePrintUserNames: List<String> = emptyList(),
    val muted: Boolean = false,
    val micGainDb: Int = 0,
    val audioProfileId: String = "",
    val recordingPath: RecordingPath = RecordingPath.AUTO,
    val wakeMode: WakeMode = WakeMode.VOICE,
    val quickWakeTrigger: QuickWakeTrigger = QuickWakeTrigger.TAP,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "SettingsViewModel"
    }

    @Volatile
    private var diagnosticRestartPending = false

    private fun markDiagnosticRestartPending() {
        diagnosticRestartPending = true
    }

    fun applyPendingDiagnosticRestart() {
        if (!diagnosticRestartPending) return
        diagnosticRestartPending = false
        // Diagnostics module only inits during satellite start(); an HA rediscover
        // alone would never register/unregister the sensors.
        restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
    }

    private val satelliteSettingsStore =
        VoiceSatelliteSettingsStore(application.voiceSatelliteSettingsStore)
    private val microphoneSettingsStore =
        MicrophoneSettingsStore(application.microphoneSettingsStore)
    private val playerSettingsStore = PlayerSettingsStore(application.playerSettingsStore)
    private val sidebarSettingsStore = SidebarSettingsStore(application.sidebarSettingsStore)
    private val homeLockSettingsStore = HomeLockSettingsStore(application.homeLockSettingsStore)
    private val updateSettingsStore = UpdateSettingsStore(application.updateSettingsStore)
    private val notificationSettingsStore = NotificationSettingsStore(application.notificationSettingsStore)
    private val screensaverSettingsStore = ScreensaverSettingsStore(application.screensaverSettingsStore)
    private val experimentalSettingsStore = com.example.ava.settings.ExperimentalSettingsStore(application)
    private val sendspinSettingsStore = com.example.ava.settings.SendspinSettingsStore(application.sendspinSettingsStore)
    private val wakeWordLibraryManager = WakeWordLibraryManager.getInstance(application)
    private val microWakeWordProvider: WakeWordProvider =
        WakeWordProviderFactory.microWakeWordProvider(application)
    private val openWakeWordProvider = WakeWordProviderFactory.openWakeWordProvider(application)
    private val wakeWordListEpoch = MutableStateFlow(0)

    val wakeWordLibraryEntries = wakeWordLibraryManager.entries
    private val openWakeWordCatalogManager = com.example.ava.openwakeword.OpenWakeWordCatalogManager(application)
    private val _vsWakeWordCatalog =
        MutableStateFlow(emptyList<com.example.ava.vswakeword.VsWakeWordCatalogEntry>())
    val vsWakeWordCatalog = _vsWakeWordCatalog.asStateFlow()
    private val _vsWakeWordCatalogLoadState =
        MutableStateFlow(WakeWordCatalogLoadState.Loading)
    val vsWakeWordCatalogLoadState = _vsWakeWordCatalogLoadState.asStateFlow()
    private val _vsWakeWordCommunityCatalog =
        MutableStateFlow(emptyList<com.example.ava.vswakeword.VsWakeWordCatalogEntry>())
    val vsWakeWordCommunityCatalog = _vsWakeWordCommunityCatalog.asStateFlow()
    private val _vsWakeWordCommunityLoadState =
        MutableStateFlow(WakeWordCatalogLoadState.Loading)
    val vsWakeWordCommunityLoadState = _vsWakeWordCommunityLoadState.asStateFlow()
    private val _vsWakeWordCatalogDownloadingId = MutableStateFlow<String?>(null)
    val vsWakeWordCatalogDownloadingId = _vsWakeWordCatalogDownloadingId.asStateFlow()
    private val _vsWakeWordCatalogDownloadPercent = MutableStateFlow<Int?>(null)
    val vsWakeWordCatalogDownloadPercent = _vsWakeWordCatalogDownloadPercent.asStateFlow()

    private val microCatalogManager = com.example.ava.microwakeword.MicroWakeWordCatalogManager(application)
    private val _microCatalog =
        MutableStateFlow(emptyList<com.example.ava.microwakeword.MicroWakeWordCatalogEntry>())
    val microWakeWordCatalog = _microCatalog.asStateFlow()
    private val _microCatalogLoadState =
        MutableStateFlow(WakeWordCatalogLoadState.Loading)
    val microCatalogLoadState = _microCatalogLoadState.asStateFlow()
    private val _microCatalogDownloadingId = MutableStateFlow<String?>(null)
    val microCatalogDownloadingId = _microCatalogDownloadingId.asStateFlow()
    private val _microCatalogDownloadPercent = MutableStateFlow<Int?>(null)
    val microCatalogDownloadPercent = _microCatalogDownloadPercent.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordLibraryManager.refresh()
        }
    }

    private fun bumpWakeWordList() {
        openWakeWordProvider.invalidateCache()
        wakeWordListEpoch.value = wakeWordListEpoch.value + 1
    }

    fun refreshVsWakeWordCatalog() {
        if (_vsWakeWordCatalog.value.isEmpty()) {
            _vsWakeWordCatalogLoadState.value = WakeWordCatalogLoadState.Loading
        }
        if (_vsWakeWordCommunityCatalog.value.isEmpty()) {
            _vsWakeWordCommunityLoadState.value = WakeWordCatalogLoadState.Loading
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { openWakeWordCatalogManager.refresh() }
                .onSuccess { catalog ->
                    _vsWakeWordCatalog.value = catalog.curated
                    _vsWakeWordCommunityCatalog.value = catalog.community
                    _vsWakeWordCatalogLoadState.value = WakeWordCatalogLoadState.Ready
                    _vsWakeWordCommunityLoadState.value = WakeWordCatalogLoadState.Ready
                }
                .onFailure {
                    Log.e("SettingsViewModel", "openWakeWord catalog refresh failed", it)
                    _vsWakeWordCatalogLoadState.value =
                        if (_vsWakeWordCatalog.value.isEmpty()) WakeWordCatalogLoadState.Failed
                        else WakeWordCatalogLoadState.Ready
                    if (_vsWakeWordCommunityCatalog.value.isEmpty()) {
                        _vsWakeWordCommunityLoadState.value = WakeWordCatalogLoadState.Failed
                    } else {
                        _vsWakeWordCommunityLoadState.value = WakeWordCatalogLoadState.Ready
                    }
                }
        }
    }

    fun isVsCatalogModelInstalled(id: String): Boolean =
        isCatalogModelLocal(WakeWordEngine.OPEN_WAKE_WORD, id)

    fun isMicroCatalogModelInstalled(id: String): Boolean =
        isCatalogModelLocal(WakeWordEngine.MICRO_WAKE_WORD, id)

    /** Bundled assets + imported library, with ok_/okay_ aliases. */
    private fun isCatalogModelLocal(engine: WakeWordEngine, catalogId: String): Boolean {
        val needle = catalogIdKey(catalogId)
        if (needle.isEmpty()) return false
        return wakeWordsFor(engine).any { catalogIdKey(it.id) == needle }
    }

    private fun catalogIdKey(id: String): String =
        com.example.ava.openwakeword.OpenWakeWordSelectorPolicy.catalogIdKey(id)

    fun isVsCatalogModelRemovable(id: String): Boolean {
        val imported = wakeWordLibraryManager.importedOpenDir.resolve(id).isDirectory
        val bundled = id in openWakeWordProvider.listBundledIds()
        val sourceUrl = runCatching {
            openWakeWordProvider.loadModel(id).manifest.sourceUrl
        }.getOrDefault("")
        val curatedIds = _vsWakeWordCatalog.value.map { it.id }.toSet()
        return com.example.ava.openwakeword.OpenWakeWordSelectorPolicy.removableFromWakeWordPicker(
            id = id,
            imported = imported,
            bundled = bundled,
            sourceUrl = sourceUrl,
            curatedCatalogIds = curatedIds,
        )
    }

    suspend fun downloadVsCatalogModel(id: String): Boolean = withContext(Dispatchers.IO) {
        val entry = _vsWakeWordCatalog.value.firstOrNull { it.id == id }
            ?: _vsWakeWordCommunityCatalog.value.firstOrNull { it.id == id }
            ?: return@withContext false
        _vsWakeWordCatalogDownloadingId.value = id
        _vsWakeWordCatalogDownloadPercent.value = 0
        try {
            val ok = openWakeWordCatalogManager.download(entry) { pct ->
                _vsWakeWordCatalogDownloadPercent.value = pct
            }
            if (ok) {
                wakeWordLibraryManager.refresh()
                bumpWakeWordList()
            }
            ok
        } finally {
            _vsWakeWordCatalogDownloadingId.value = null
            _vsWakeWordCatalogDownloadPercent.value = null
        }
    }

    suspend fun deleteVsCatalogModel(id: String): Boolean {
        val ok = wakeWordLibraryManager.deleteEntry(id, WakeWordEngine.OPEN_WAKE_WORD)
        bumpWakeWordList()
        return ok
    }

    fun refreshMicroWakeWordCatalog() {
        if (_microCatalog.value.isEmpty()) {
            _microCatalogLoadState.value = WakeWordCatalogLoadState.Loading
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { microCatalogManager.refresh() }
                .onSuccess {
                    _microCatalog.value = it
                    _microCatalogLoadState.value = WakeWordCatalogLoadState.Ready
                }
                .onFailure {
                    Log.e("SettingsViewModel", "microWakeWord catalog refresh failed", it)
                    if (_microCatalog.value.isEmpty()) {
                        _microCatalogLoadState.value = WakeWordCatalogLoadState.Failed
                    } else {
                        _microCatalogLoadState.value = WakeWordCatalogLoadState.Ready
                    }
                }
        }
    }

    suspend fun downloadMicroCatalogModel(id: String): Boolean = withContext(Dispatchers.IO) {
        val entry = _microCatalog.value.firstOrNull { it.id == id } ?: return@withContext false
        _microCatalogDownloadingId.value = id
        _microCatalogDownloadPercent.value = 0
        try {
            val ok = microCatalogManager.download(entry) { pct ->
                _microCatalogDownloadPercent.value = pct
            }
            if (ok) {
                wakeWordLibraryManager.refresh()
                bumpWakeWordList()
            }
            ok
        } finally {
            _microCatalogDownloadingId.value = null
            _microCatalogDownloadPercent.value = null
        }
    }

    /**
     * Pseudo picker entry for the model-free native DSP stop detector. Never loaded
     * as a model — [MicrophoneSettings.STOP_WORD_BUILTIN] is resolved before the
     * detector sees it. Micro fields are placeholders for the picker's slider math.
     */
    private fun builtinStopWakeWord(): WakeWordWithId = WakeWordWithId(
        id = MicrophoneSettings.STOP_WORD_BUILTIN,
        wakeWord = WakeWord(
            type = "builtin",
            wake_word = application.getString(R.string.stop_word_builtin_label),
            author = "",
            website = "",
            model = "",
            trained_languages = emptyArray(),
            version = 1,
            micro = Micro(
                probability_cutoff = 0.85f,
                feature_step_size = 10,
                sliding_window_size = 5,
                tensor_arena_size = 0,
                minimum_esphome_version = "",
            ),
        ),
    )

    private fun openModelToWakeWord(model: OpenWakeWordModel): WakeWordWithId =
        WakeWordWithId(
            id = model.id,
            hasBuiltInVerifier = model.verifierHint,
            wakeWord = WakeWord(
                type = "openwakeword",
                wake_word = model.displayName,
                author = model.manifest.author,
                website = model.manifest.sourceUrl.ifBlank { model.manifest.website },
                model = "${model.id}.onnx",
                trained_languages = emptyArray(),
                version = 1,
                micro = Micro(
                    probability_cutoff = model.threshold,
                    feature_step_size = 10,
                    sliding_window_size = model.slidingWindowSize,
                    tensor_arena_size = 0,
                    minimum_esphome_version = "",
                ),
            ),
        )

    private fun wakeWordsFor(engine: WakeWordEngine): List<WakeWordWithId> {
        return when (engine) {
            // VS list = bundled + imported/downloaded only. Never fall back to Micro ids.
            WakeWordEngine.OPEN_WAKE_WORD -> openWakeWordProvider.listModels().map(::openModelToWakeWord)
            WakeWordEngine.MICRO_WAKE_WORD -> microWakeWordProvider.getWakeWords()
        }
    }

    /** Wake models plus dedicated stopClassifier heads — third-slot picker only. */
    private fun stopEligibleWordsFor(engine: WakeWordEngine): List<WakeWordWithId> {
        return when (engine) {
            WakeWordEngine.OPEN_WAKE_WORD ->
                openWakeWordProvider.listStopEligibleModels().map(::openModelToWakeWord)
            WakeWordEngine.MICRO_WAKE_WORD -> microWakeWordProvider.getWakeWords()
        }
    }
    val satelliteSettingsState = kotlinx.coroutines.flow.combine(
        satelliteSettingsStore.getFlow(),
        sendspinSettingsStore.getFlow()
    ) { satellite, sendspin ->
        UIState(
            serverName = satellite.name,
            serverPort = satellite.serverPort,
            encryptionKey = satellite.encryptionKey,
            macAddress = satellite.macAddress,
            haMediaPlayerEntity = satellite.haMediaPlayerEntity,
            sendspinLowMemoryMode = sendspin.lowMemoryMode,
            sendspinSyncOffsetMs = sendspin.syncOffsetMs,
            volumeFollowRule = com.example.ava.settings.VolumeFollowRule.fromSettings(sendspin).storageKey,
            sendspinPreferredFormat = sendspin.preferredFormat,
            sendspinCustomDeviceName = sendspin.customDeviceName,
            sendspinEnabled = sendspin.enabled,
            sendspinAutoConnect = sendspin.autoConnect,
            sendspinAdvertiseAsPlayer = sendspin.advertiseAsPlayer,
            sendspinServerUrl = sendspin.serverUrl,
        )
    }

    val sendspinFormatOptions = flow {
        emit(
            SendspinFormatCatalog.getSelectableFormatOptions(
                application.getString(R.string.settings_sendspin_stream_format_automatic)
            )
        )
    }.flowOn(Dispatchers.IO).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        listOf("automatic" to application.getString(R.string.settings_sendspin_stream_format_automatic))
    )

    val microphoneSettingsState = combine(
        microphoneSettingsStore.getFlow(),
        wakeWordListEpoch,
        _vsWakeWordCatalog,
    ) { settings, _, curatedCatalog ->
        val wakeWords = wakeWordsFor(settings.wakeWordEngine)
        val availableWakeWordIds = wakeWords.map { wakeWord -> wakeWord.id }.toSet()
        val savedWakeWords = compatibleWakeWordIdsForEngine(
            settings.wakeWordEngine,
            settings.activeWakeWordsForEngine(settings.wakeWordEngine),
            availableWakeWordIds
        )
            .ifEmpty { wakeWords.firstOrNull()?.id?.let(::listOf) ?: emptyList() }
        val selectedWakeWord = savedWakeWords.firstOrNull()
        val pickerWakeWords = if (settings.wakeWordEngine == WakeWordEngine.OPEN_WAKE_WORD) {
            val bundledIds = openWakeWordProvider.listBundledIds()
            val curatedIds = curatedCatalog.map { it.id }.toSet()
            val selectedIds = savedWakeWords.toSet()
            wakeWords.filter { item ->
                com.example.ava.openwakeword.OpenWakeWordSelectorPolicy.visibleInWakeWordPicker(
                    id = item.id,
                    bundled = item.id in bundledIds,
                    sourceUrl = item.wakeWord.website,
                    curatedCatalogIds = curatedIds,
                    selectedIds = selectedIds,
                )
            }
        } else {
            wakeWords
        }
        // Stop slot: a model id must exist in this engine's catalog, else display
        // falls back to builtin (mirrors the runtime fallback in the service).
        val stopEligibleWords = stopEligibleWordsFor(settings.wakeWordEngine)
        val stopWordSetting = settings.activeStopWordForEngine(settings.wakeWordEngine)
        val stopModelItem = when (stopWordSetting) {
            MicrophoneSettings.STOP_WORD_BUILTIN, MicrophoneSettings.STOP_WORD_NONE -> null
            else -> stopEligibleWords.firstOrNull { it.id == stopWordSetting }
        }
        val builtinStopItem = builtinStopWakeWord()
        val stopWordSelection = when {
            stopWordSetting == MicrophoneSettings.STOP_WORD_NONE -> null
            else -> stopModelItem ?: builtinStopItem
        }
        val savedWakeWordIds = savedWakeWords.toSet()
        MicrophoneState(
            wakeWordEngine = settings.wakeWordEngine,
            wakeWord = wakeWords.firstOrNull { wakeWord ->
                wakeWord.id == selectedWakeWord
            } ?: wakeWords.first(),
            wakeWord2 = if (savedWakeWords.size > 1) {
                wakeWords.firstOrNull { wakeWord -> wakeWord.id == savedWakeWords.getOrNull(1) }
            } else null,
            // Bidirectional exclusivity: a model in the stop slot leaves the wake pickers.
            wakeWords = pickerWakeWords.filter { it.id != stopModelItem?.id },
            sensitivity1 = settings.wakeWordSensitivity1,
            sensitivity2 = settings.wakeWordSensitivity2,
            extraStrictness1 = settings.wakeWordExtraStrictness1,
            extraStrictness2 = settings.wakeWordExtraStrictness2,
            stopWord = stopWordSelection,
            stopPickerWakeWords = listOf(builtinStopItem) +
                stopEligibleWords.filter { it.id !in savedWakeWordIds },
            stopWordSensitivity = settings.stopWordSensitivity,
            audioSourceAutoDetect = !settings.audioSourceExplicitlySet,
            audioSource = settings.resolveAudioSource(
                com.example.ava.audio.DeviceAudioProfile.resolveById(settings.audioProfileId)
                    ?: com.example.ava.audio.DeviceAudioProfile.DEFAULT
            ),
            noiseSuppressorEnabled = settings.noiseSuppressorEnabled,
            softwareNsEnabled = settings.softwareNsEnabled,
            softwareNsStrength = settings.softwareNsStrength,
            automaticGainControlEnabled = settings.automaticGainControlEnabled,
            acousticEchoCancelerEnabled = settings.acousticEchoCancelerEnabled,
            softwareAecEnabled = settings.softwareAecEnabled,
            softwareAecPauseDuringSpeech = settings.softwareAecPauseDuringSpeech,
            softwareAecStrength = settings.softwareAecStrength,
            softwareAecRoom = settings.softwareAecRoom,
            voicePrintEnabled = settings.voicePrintEnabled,
            voicePrintEnrollmentMode = settings.voicePrintEnrollmentMode,
            voicePrintManualUser0Samples = settings.voicePrintManualUser0Samples,
            voicePrintManualUser1Samples = settings.voicePrintManualUser1Samples,
            voicePrintManualWakeVerifyEnabled = settings.voicePrintManualWakeVerifyEnabled,
            voicePrintUserNames = settings.voicePrintUserNames,
            muted = settings.muted,
            micGainDb = settings.micGainDb,
            audioProfileId = settings.audioProfileId,
            recordingPath = settings.recordingPath,
            wakeMode = settings.wakeMode,
            quickWakeTrigger = settings.quickWakeTrigger,
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val playerSettingsState = playerSettingsStore.getFlow()

    val sendspinSettingsState = sendspinSettingsStore.getFlow()

    val sidebarSettingsState = sidebarSettingsStore.getFlow()

    val homeLockSettingsState = homeLockSettingsStore.getFlow()
    val updateSettingsState = updateSettingsStore.getFlow()
    
    val notificationSettingsState = notificationSettingsStore.getFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, NotificationSettings())
    
    val experimentalSettingsState = experimentalSettingsStore.getFlow()

    val screensaverSettingsState = screensaverSettingsStore.getFlow()
    
    
    fun hasCamera() = experimentalSettingsStore.hasCamera()
    fun hasBackCamera() = experimentalSettingsStore.hasBackCamera()
    fun hasFrontCamera() = experimentalSettingsStore.hasFrontCamera()

    suspend fun saveServerName(name: String) {
        if (validateName(name).isNullOrBlank()) {
            satelliteSettingsStore.saveName(name)
            // mDNS broadcasts and the ESPHome Hello response both read the name at
            // (re)start time, so a running satellite must restart to advertise the new
            // identity - otherwise HA keeps seeing the old name until the next cold start.
            restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
        } else {
            Log.w(TAG, "Cannot save invalid server name: $name")
        }
    }

    suspend fun saveServerPort(port: Int?) {
        if (validatePort(port).isNullOrBlank()) {
            satelliteSettingsStore.saveServerPort(port!!)
            // Same as above: the TCP server and mDNS registration both bind to the port
            // captured at start time, so a running satellite must restart on the new port.
            restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
        } else {
            Log.w(TAG, "Cannot save invalid server port: $port")
        }
    }

    suspend fun saveEncryptionKey(key: String) {
        if (validateEncryptionKey(key).isNullOrBlank()) {
            satelliteSettingsStore.saveEncryptionKey(key)
            restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
        } else {
            Log.w(TAG, "Cannot save invalid ESPHome encryption key")
        }
    }

    fun generateEncryptionKey(): String =
        com.example.ava.server.noise.EspHomeNoisePsk.generateBase64()

    /** Identity collision repair (Ava-Pro#221); HA sees a new device after the restart. */
    suspend fun regenerateEspHomeIdentity() {
        satelliteSettingsStore.regenerateEspHomeIdentity()
        // MAC is read into mDNS TXT, the Noise server hello and DeviceInfo at start time.
        restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
    }

    suspend fun saveWakeWord(wakeWordId: String) {
        val currentSettings = microphoneSettingsStore.get()
        val currentEngine = currentSettings.wakeWordEngine
        if (validateWakeWord(wakeWordId, currentEngine).isNullOrBlank()) {
            val availableWakeWordIds = wakeWordsFor(currentEngine).map { it.id }.toSet()
            val currentWakeWords = compatibleWakeWordIdsForEngine(
                currentEngine,
                currentSettings.activeWakeWordsForEngine(currentEngine),
                availableWakeWordIds
            )
            // Re-check slot 2 against this engine's catalog — never keep a stale
            // cross-engine id (hey_jarvis exists in both Micro and VS).
            val second = currentWakeWords.getOrNull(1)
            val newWakeWords = compatibleWakeWordIdsForEngine(
                currentEngine,
                buildList {
                    add(wakeWordId)
                    if (!second.isNullOrBlank() && second != wakeWordId) add(second)
                },
                availableWakeWordIds,
            ).ifEmpty { listOf(wakeWordId) }
            microphoneSettingsStore.saveActiveWakeWordsForEngine(currentEngine, newWakeWords)
            VoiceSatelliteService.getInstance()?.applyWakeWordsChange(newWakeWords)
        } else {
            Log.w(TAG, "Cannot save invalid wake word: $wakeWordId")
        }
    }

    suspend fun saveWakeWord2(wakeWordId: String?) {
        val currentSettings = microphoneSettingsStore.get()
        val currentEngine = currentSettings.wakeWordEngine
        val availableWakeWordIds = wakeWordsFor(currentEngine).map { it.id }.toSet()
        val currentWakeWords = compatibleWakeWordIdsForEngine(
            currentEngine,
            currentSettings.activeWakeWordsForEngine(currentEngine),
            availableWakeWordIds
        )
        val wakeWord1 = currentWakeWords.firstOrNull()
            ?: wakeWordsFor(currentEngine).firstOrNull()?.id
            ?: return
        val newWakeWords = if (wakeWordId.isNullOrBlank()) {
            listOf(wakeWord1)
        } else if (validateWakeWord(wakeWordId, currentEngine).isNullOrBlank()) {
            compatibleWakeWordIdsForEngine(
                currentEngine,
                listOf(wakeWord1, wakeWordId),
                availableWakeWordIds,
            ).ifEmpty { listOf(wakeWord1) }
        } else {
            Log.w(TAG, "Cannot save invalid wake word 2: $wakeWordId")
            return
        }
        microphoneSettingsStore.saveActiveWakeWordsForEngine(currentEngine, newWakeWords)
        VoiceSatelliteService.getInstance()?.applyWakeWordsChange(newWakeWords)
    }

    suspend fun saveWakeWordEngine(engine: WakeWordEngine) {
        val current = microphoneSettingsStore.get()
        if (current.wakeWordEngine == engine) {
            restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
            return
        }
        val wakeWords = wakeWordsFor(engine)
        val availableWakeWordIds = wakeWords.map { it.id }.toSet()
        // Restore only this engine's saved ids — do not reuse the other engine's list
        // (alexa/hey_* share names but are different Micro vs VS models).
        val savedForEngine = compatibleWakeWordIdsForEngine(
            engine,
            current.activeWakeWordsForEngine(engine),
            availableWakeWordIds
        )
        val preferredWakeWord = savedForEngine.firstOrNull()
            ?: wakeWords.firstOrNull()?.id
            ?: return
        val secondWakeWord = savedForEngine.getOrNull(1)
        val newWakeWords = buildList {
            add(preferredWakeWord)
            if (!secondWakeWord.isNullOrBlank() && secondWakeWord != preferredWakeWord && wakeWords.any { it.id == secondWakeWord }) {
                add(secondWakeWord)
            }
        }
        microphoneSettingsStore.saveWakeWordEngine(engine, newWakeWords)
        restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
    }

    suspend fun saveWakeWordSensitivity1(sensitivity: Float) {
        microphoneSettingsStore.wakeWordSensitivity1.set(sensitivity)
    }
    
    suspend fun saveWakeWordSensitivity2(sensitivity: Float) {
        microphoneSettingsStore.wakeWordSensitivity2.set(sensitivity)
    }

    suspend fun saveWakeWordExtraStrictness1(level: Int) {
        microphoneSettingsStore.wakeWordExtraStrictness1.set(level.coerceIn(0, 2))
    }

    suspend fun saveWakeWordExtraStrictness2(level: Int) {
        microphoneSettingsStore.wakeWordExtraStrictness2.set(level.coerceIn(0, 2))
    }

    /**
     * Stop slot: null = off, [MicrophoneSettings.STOP_WORD_BUILTIN] = native DSP,
     * anything else = model id of the current engine (validated; a wake-slot
     * collision or unknown id keeps builtin instead of a dead slot).
     */
    suspend fun saveStopWord(stopWordId: String?) {
        val currentSettings = microphoneSettingsStore.get()
        val engine = currentSettings.wakeWordEngine
        val value = when {
            stopWordId.isNullOrBlank() -> MicrophoneSettings.STOP_WORD_NONE
            stopWordId == MicrophoneSettings.STOP_WORD_BUILTIN -> MicrophoneSettings.STOP_WORD_BUILTIN
            else -> {
                val availableIds = stopEligibleWordsFor(engine).map { it.id }.toSet()
                val wakeIds = wakeWordsFor(engine).map { it.id }.toSet()
                val activeWakeWords = compatibleWakeWordIdsForEngine(
                    engine,
                    currentSettings.activeWakeWordsForEngine(engine),
                    wakeIds,
                )
                wakeWordIdCandidatesForEngine(engine, stopWordId)
                    .firstOrNull { it in availableIds && it !in activeWakeWords }
                    ?: MicrophoneSettings.STOP_WORD_BUILTIN.also {
                        Log.w(TAG, "stop word $stopWordId invalid for $engine — keeping builtin")
                    }
            }
        }
        microphoneSettingsStore.saveStopWordForEngine(engine, value)
        // Detector list changes require a model (re)load — same restart path as
        // the wake-word engine switch.
        restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
    }

    suspend fun saveStopWordSensitivity(sensitivity: Float) {
        microphoneSettingsStore.stopWordSensitivity.set(sensitivity)
    }

    suspend fun saveAudioSource(audioSource: Int) {
        microphoneSettingsStore.audioSource.set(audioSource)
        microphoneSettingsStore.audioSourceExplicitlySet.set(true)
    }

    suspend fun saveAudioSourceAutoDetect() {
        microphoneSettingsStore.audioSourceExplicitlySet.set(false)
    }

    suspend fun saveRecordingPath(path: RecordingPath) {
        microphoneSettingsStore.recordingPath.set(path)
    }

    suspend fun saveNoiseSuppressorEnabled(enabled: Boolean) {
        microphoneSettingsStore.noiseSuppressorEnabled.set(enabled)
    }

    suspend fun saveSoftwareNsEnabled(enabled: Boolean) {
        microphoneSettingsStore.softwareNsEnabled.set(enabled)
    }

    suspend fun saveSoftwareNsStrength(strength: SoftwareNsStrength) {
        microphoneSettingsStore.softwareNsStrength.set(strength)
    }

    suspend fun saveAutomaticGainControlEnabled(enabled: Boolean) {
        microphoneSettingsStore.automaticGainControlEnabled.set(enabled)
    }

    suspend fun saveSoftwareAecEnabled(enabled: Boolean) {
        microphoneSettingsStore.softwareAecEnabled.set(enabled)
    }

    suspend fun saveSoftwareAecPauseDuringSpeech(enabled: Boolean) {
        microphoneSettingsStore.softwareAecPauseDuringSpeech.set(enabled)
    }

    suspend fun saveSoftwareAecStrength(strength: SoftwareAecStrength) {
        microphoneSettingsStore.softwareAecStrength.set(strength)
    }

    suspend fun saveSoftwareAecRoom(room: SoftwareAecRoom) {
        microphoneSettingsStore.softwareAecRoom.set(room)
    }

    suspend fun saveVoicePrintEnabled(enabled: Boolean) {
        microphoneSettingsStore.voicePrintEnabled.set(enabled)
    }

    suspend fun saveVoicePrintEnrollmentMode(mode: VoicePrintEnrollmentMode) {
        microphoneSettingsStore.voicePrintEnrollmentMode.set(mode)
    }

    suspend fun saveVoicePrintManualProgress(userIndex: Int, samples: Int) {
        val clamped = samples.coerceIn(0, MicrophoneSettingsStore.MANUAL_ENROLLMENT_SAMPLES)
        when (userIndex) {
            0 -> microphoneSettingsStore.voicePrintManualUser0Samples.set(clamped)
            1 -> microphoneSettingsStore.voicePrintManualUser1Samples.set(clamped)
        }
    }

    suspend fun saveVoicePrintManualWakeVerifyEnabled(enabled: Boolean) {
        microphoneSettingsStore.voicePrintManualWakeVerifyEnabled.set(enabled)
    }

    suspend fun clearVoicePrintManualUser(userIndex: Int) {
        if (userIndex !in 0..1) return
        val service = VoiceSatelliteService.getInstance()
        if (service != null) {
            service.clearVoicePrintUserProfile(userIndex)
        } else {
            VoicePrintStorage.clearUserProfile(
                application,
                userIndex,
                microphoneSettingsStore.getCached().wakeWordEngine,
            )
        }
        when (userIndex) {
            0 -> microphoneSettingsStore.voicePrintManualUser0Samples.set(0)
            1 -> microphoneSettingsStore.voicePrintManualUser1Samples.set(0)
        }
        val settings = microphoneSettingsStore.get()
        if (!canEnableManualWakeVerify(
                settings.voicePrintManualUser0Samples,
                settings.voicePrintManualUser1Samples,
            )
        ) {
            microphoneSettingsStore.voicePrintManualWakeVerifyEnabled.set(false)
        }
    }

    fun canEnableManualWakeVerify(user0Samples: Int, user1Samples: Int): Boolean =
        VoicePrintStorage.hasMinimumManualEnrollment(
            application,
            microphoneSettingsStore.getCached().wakeWordEngine,
            user0Samples,
            user1Samples,
            MicrophoneSettingsStore.MANUAL_ENROLLMENT_SAMPLES,
        )

    suspend fun resetVoicePrintManualProgress() {
        microphoneSettingsStore.voicePrintManualUser0Samples.set(0)
        microphoneSettingsStore.voicePrintManualUser1Samples.set(0)
    }

    suspend fun switchVoicePrintToManualAndClearProfiles() {
        clearVoicePrintProfilesAndResetManualProgress()
        microphoneSettingsStore.voicePrintEnrollmentMode.set(VoicePrintEnrollmentMode.MANUAL)
    }

    suspend fun switchVoicePrintToAutoAndClearProfiles() {
        clearVoicePrintProfilesAndResetManualProgress()
        microphoneSettingsStore.voicePrintEnrollmentMode.set(VoicePrintEnrollmentMode.AUTO)
    }

    private suspend fun clearVoicePrintProfilesAndResetManualProgress() {
        val service = VoiceSatelliteService.getInstance()
        if (service != null) {
            service.clearVoicePrintProfiles()
        } else {
            VoicePrintStorage.clearProfiles(application)
        }
        resetVoicePrintManualProgress()
        microphoneSettingsStore.voicePrintManualWakeVerifyEnabled.set(false)
    }

    suspend fun disableVoicePrintAndClearProfiles() {
        val service = VoiceSatelliteService.getInstance()
        if (service != null) {
            service.clearVoicePrintProfiles()
        } else {
            VoicePrintStorage.clearProfiles(application)
        }
        microphoneSettingsStore.voicePrintEnabled.set(false)
        microphoneSettingsStore.voicePrintManualWakeVerifyEnabled.set(false)
    }

    suspend fun saveVoicePrintUserName(index: Int, name: String) {
        if (index !in 0..1) return
        val current = microphoneSettingsStore.get().voicePrintUserNames.toMutableList()
        while (current.size <= index) current.add("")
        current[index] = name.trim()
        microphoneSettingsStore.voicePrintUserNames.set(current)
    }

    suspend fun saveVoicePrintUserNames(names: List<String>) {
        microphoneSettingsStore.voicePrintUserNames.set(names.map(String::trim).take(2))
    }

    fun validateVoicePrintUserName(name: String): String? =
        if (name.length > 24)
            application.getString(R.string.validation_max_length, 24)
        else null

    suspend fun saveAcousticEchoCancelerEnabled(enabled: Boolean) {
        microphoneSettingsStore.acousticEchoCancelerEnabled.set(enabled)
    }

    suspend fun saveAudioProfileId(profileId: String) {
        microphoneSettingsStore.audioProfileId.set(profileId)
    }

    suspend fun saveMicGainDb(gainDb: Int?) {
        if (validateMicGainDb(gainDb).isNullOrBlank()) {
            microphoneSettingsStore.micGainDb.set(
                MicrophoneSettings.clampMicGainDb(gainDb ?: 0),
            )
        } else {
            Log.w(TAG, "Cannot save invalid mic gain: $gainDb")
        }
    }

    fun validateMicGainDb(gainDb: Int?): String? =
        if (gainDb != null &&
            gainDb !in MicrophoneSettings.MIC_GAIN_DB_MIN..MicrophoneSettings.MIC_GAIN_DB_MAX
        ) {
            application.getString(R.string.validation_mic_gain_db_invalid)
        } else {
            null
        }

    suspend fun saveEnableWakeSound(enableWakeSound: Boolean) {
        playerSettingsStore.enableWakeSound.set(enableWakeSound)
    }

    suspend fun saveScreenPowerControlHaDisplayEnabled(enabled: Boolean) {
        experimentalSettingsStore.setScreenPowerControlHaDisplayEnabled(enabled)
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }
    
    suspend fun saveWakeSoundUri(uri: String) {
        val persisted = withContext(Dispatchers.IO) {
            SoundUriPersistence.persist(application, uri, "wake_sound_1")
        }
        playerSettingsStore.wakeSound.set(persisted)
    }
    
    suspend fun saveWakeSound2Uri(uri: String) {
        val persisted = withContext(Dispatchers.IO) {
            SoundUriPersistence.persist(application, uri, "wake_sound_2")
        }
        playerSettingsStore.wakeSound2.set(persisted)
    }

    suspend fun saveContinuousConversation(enabled: Boolean) {
        if (enabled) {
            playerSettingsStore.update { current ->
                val withContinuous = current.copy(enableContinuousConversation = true)
                if (!withContinuous.enableQuestionMarkContinue &&
                    !withContinuous.enableExitKeywordStop &&
                    !withContinuous.enableSmartContinue
                ) {
                    withContinuous.copy(enableExitKeywordStop = true)
                } else {
                    withContinuous
                }
            }
        } else {
            playerSettingsStore.enableContinuousConversation.set(false)
        }
    }

    suspend fun saveQuestionMarkContinue(enabled: Boolean) {
        playerSettingsStore.update {
            if (enabled) {
                it.copy(
                    enableQuestionMarkContinue = true,
                    enableExitKeywordStop = false,
                    enableSmartContinue = false,
                )
            } else {
                it.copy(
                    enableQuestionMarkContinue = false,
                    enableExitKeywordStop = true,
                    enableSmartContinue = false,
                )
            }
        }
    }

    suspend fun saveExitKeywordStop(enabled: Boolean) {
        playerSettingsStore.update {
            if (enabled) {
                it.copy(
                    enableExitKeywordStop = true,
                    enableQuestionMarkContinue = false,
                    enableSmartContinue = false,
                )
            } else {
                it.copy(
                    enableExitKeywordStop = false,
                    enableQuestionMarkContinue = true,
                    enableSmartContinue = false,
                )
            }
        }
    }

    suspend fun saveSmartContinue(enabled: Boolean) {
        playerSettingsStore.update {
            if (enabled) {
                it.copy(
                    enableSmartContinue = true,
                    enableExitKeywordStop = false,
                    enableQuestionMarkContinue = false,
                )
            } else {
                it.copy(
                    enableSmartContinue = false,
                    enableExitKeywordStop = true,
                    enableQuestionMarkContinue = false,
                )
            }
        }
    }

    suspend fun saveFloatingWindow(enabled: Boolean) {
        playerSettingsStore.enableFloatingWindow.set(enabled)
    }

    suspend fun saveStreamingTtsSubtitles(enabled: Boolean) {
        playerSettingsStore.enableStreamingTtsSubtitles.set(enabled)
        restartVoiceSatelliteServiceIfRunning()
    }

    suspend fun saveWhisperResponseVolume(volume: Float) {
        playerSettingsStore.whisperResponseVolume.set(
            volume.coerceIn(PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME, 1f),
        )
    }

    suspend fun saveEnableAmbientAutoGain(enabled: Boolean) {
        playerSettingsStore.enableAmbientAutoGain.set(enabled)
    }

    suspend fun saveExposeWhisperResponseEntity(enabled: Boolean) {
        playerSettingsStore.exposeWhisperResponseEntity.set(enabled)
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }

    suspend fun savePreserveTtsHttps(enabled: Boolean) {
        playerSettingsStore.preserveTtsHttps.set(enabled)
    }

    suspend fun saveVoiceWakeWord1AccentColor(color: String) {
        playerSettingsStore.voiceWakeWord1AccentColor.set(color)
    }

    suspend fun saveVoiceWakeWord2AccentColor(color: String) {
        playerSettingsStore.voiceWakeWord2AccentColor.set(color)
    }

    suspend fun saveVoiceRippleEffect(enabled: Boolean) {
        playerSettingsStore.enableVoiceRippleEffect.set(enabled)
    }

    suspend fun saveVoiceEdgeGlow(enabled: Boolean) {
        playerSettingsStore.enableVoiceEdgeGlow.set(enabled)
    }

    suspend fun saveVoiceEdgeGlowLevelGain(gain: Float) {
        playerSettingsStore.voiceEdgeGlowLevelGain.set(PlayerSettings.clampEdgeGlowLevelGain(gain))
    }

    suspend fun saveVoiceWakeWord1EdgeGlowLevelGain(gain: Float) {
        playerSettingsStore.voiceWakeWord1EdgeGlowLevelGain.set(
            PlayerSettings.clampEdgeGlowLevelGain(gain)
        )
    }

    suspend fun saveVoiceWakeWord2EdgeGlowLevelGain(gain: Float) {
        playerSettingsStore.voiceWakeWord2EdgeGlowLevelGain.set(
            PlayerSettings.clampEdgeGlowLevelGain(gain)
        )
    }

    suspend fun saveVoiceWakeWord1EdgeGlowSheer(sheer: Float) {
        playerSettingsStore.voiceWakeWord1EdgeGlowSheer.set(
            PlayerSettings.clampEdgeGlowSheer(sheer)
        )
    }

    suspend fun saveVoiceWakeWord2EdgeGlowSheer(sheer: Float) {
        playerSettingsStore.voiceWakeWord2EdgeGlowSheer.set(
            PlayerSettings.clampEdgeGlowSheer(sheer)
        )
    }
    
    suspend fun saveVinylCover(enabled: Boolean) {
        playerSettingsStore.enableVinylCover.set(enabled)
        if (enabled) {
            sendspinSettingsStore.volumeFollowRule.set(
                com.example.ava.settings.VolumeFollowRule.FOLLOW_DEVICE
            )
        }
    }

    suspend fun saveExposeEsphomeMediaPlayerEntity(enabled: Boolean) {
        playerSettingsStore.exposeEsphomeMediaPlayerEntity.set(enabled)
    }

    suspend fun saveSendspinEnabled(enabled: Boolean) {
        sendspinSettingsStore.enabled.set(enabled)
    }

    suspend fun saveSendspinAutoConnect(enabled: Boolean) {
        sendspinSettingsStore.autoConnect.set(enabled)
    }

    suspend fun saveSendspinAdvertiseAsPlayer(enabled: Boolean) {
        sendspinSettingsStore.advertiseAsPlayer.set(enabled)
    }

    suspend fun saveSendspinServerUrl(url: String) {
        sendspinSettingsStore.serverUrl.set(url.trim())
    }
    
    suspend fun saveSendspinVinylCover(enabled: Boolean) {
        playerSettingsStore.enableSendspinVinylCover.set(enabled)
    }

    suspend fun saveHaMusicEq(gains: com.example.ava.audio.eq.MusicEqGains) {
        playerSettingsStore.setHaMusicEq(gains)
        com.example.ava.audio.eq.MusicEqRuntime.set(
            com.example.ava.audio.eq.MusicEqSource.HA,
            gains,
        )
    }

    suspend fun saveSendspinMusicEq(gains: com.example.ava.audio.eq.MusicEqGains) {
        sendspinSettingsStore.setMusicEq(gains)
        com.example.ava.audio.eq.MusicEqRuntime.set(
            com.example.ava.audio.eq.MusicEqSource.SENDSPIN,
            gains,
        )
    }
    
    suspend fun saveHaVinylCover(enabled: Boolean) {
        playerSettingsStore.enableHaVinylCover.set(enabled)
    }

    suspend fun saveMediaOverlayStyle(style: MediaOverlayStyle) {
        playerSettingsStore.update {
            it.copy(mediaOverlayStyle = style.storageKey)
        }
    }

    suspend fun saveEqMiniPlayer(enabled: Boolean) {
        playerSettingsStore.enableEqMiniPlayer.set(enabled)
        com.example.ava.services.VinylCoverService.setEqMiniPreferred(enabled)
        if (enabled) {
            // Arm only — VS watcher may refresh if media is already live.
            com.example.ava.services.VinylCoverService.ensurePersistentMini(getApplication())
        } else {
            com.example.ava.services.VinylCoverService.hide(getApplication(), force = true)
        }
    }

    suspend fun saveVinylCoverDisplay(enabled: Boolean) {
        playerSettingsStore.enableVinylCoverDisplay.set(enabled)
    }

    suspend fun saveEqMiniFabPosition(normX: Float, normY: Float) {
        playerSettingsStore.update {
            it.copy(
                eqMiniFabNormX = normX.coerceIn(0f, 1f),
                eqMiniFabNormY = normY.coerceIn(0f, 1f),
                eqMiniFabX = -1,
                eqMiniFabY = -1,
            )
        }
    }
    
    suspend fun saveSendspinLowMemoryMode(enabled: Boolean) {
        sendspinSettingsStore.lowMemoryMode.set(enabled)
    }

    suspend fun saveVolumeFollowRule(rule: com.example.ava.settings.VolumeFollowRule) {
        sendspinSettingsStore.volumeFollowRule.set(rule)
    }

    suspend fun saveSendspinSyncOffsetMs(offsetMs: Int) {
        sendspinSettingsStore.syncOffsetMs.set(offsetMs)
    }

    suspend fun saveSendspinDeviceName(name: String) {
        sendspinSettingsStore.customDeviceName.set(name)
    }

    suspend fun saveSendspinPreferredFormat(format: String) {
        sendspinSettingsStore.preferredFormat.set(
            SendspinFormatCatalog.normalizePreferredFormat(format)
        )
    }
    
    suspend fun saveHaMediaPlayerEntity(entity: String) {
        satelliteSettingsStore.saveHaMediaPlayerEntity(entity)
    }
    
    suspend fun saveDreamClock(enabled: Boolean) {
        playerSettingsStore.update {
            it.copy(enableDreamClock = enabled, enableDreamClockVisible = false)
        }
        if (enabled) {
            sidebarSettingsStore.offerHomeEntry(SidebarItemKey.DreamClock)
        }
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }
    
    suspend fun saveHaWeatherEntity(entity: String) {
        playerSettingsStore.haWeatherEntity.set(entity)
    }
    
    suspend fun saveWeatherOverlay(enabled: Boolean) {
        playerSettingsStore.update {
            it.copy(enableWeatherOverlay = enabled, enableWeatherOverlayVisible = false)
        }
        if (enabled) {
            sidebarSettingsStore.offerHomeEntry(SidebarItemKey.Weather)
        }
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }
    
    suspend fun saveWeatherOverlayDisplay(enabled: Boolean) {
        playerSettingsStore.enableWeatherOverlayDisplay.set(enabled)
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }
    
    suspend fun saveDreamClockDisplay(enabled: Boolean) {
        playerSettingsStore.enableDreamClockDisplay.set(enabled)
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }

    suspend fun saveDreamClockFace(face: DreamClockFace) {
        playerSettingsStore.dreamClockFace.set(face.storageKey)
    }

    suspend fun saveDreamClockFlipStyle(style: DreamClockFlipStyle) {
        playerSettingsStore.dreamClockFlipStyle.set(style.storageKey)
    }

    suspend fun saveDreamClockFlipShowSeconds(show: Boolean) {
        playerSettingsStore.dreamClockFlipShowSeconds.set(show)
    }

    suspend fun saveDreamClockFlipFont(font: DreamClockFlipFont) {
        playerSettingsStore.dreamClockFlipFont.set(font.storageKey)
    }

    suspend fun saveDreamClockTimerSoundEnabled(enabled: Boolean) {
        playerSettingsStore.dreamClockTimerSoundEnabled.set(enabled)
    }

    suspend fun saveDreamClockTimerSound(uri: String) {
        playerSettingsStore.dreamClockTimerSound.set(uri)
    }

    suspend fun saveDreamClockFlipStyleHaSelect(enabled: Boolean) {
        playerSettingsStore.dreamClockFlipStyleHaSelect.set(enabled)
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }

    suspend fun saveDreamClockSmartAodEnabled(enabled: Boolean) {
        playerSettingsStore.dreamClockSmartAodEnabled.set(enabled)
    }

    suspend fun saveDreamClockSmartAodTimeoutSeconds(seconds: Int) {
        playerSettingsStore.dreamClockSmartAodTimeoutSeconds.set(seconds)
    }

    suspend fun saveDreamClockSmartAodMaskPercent(percent: Int) {
        playerSettingsStore.dreamClockSmartAodMaskPercent.set(percent)
    }

    suspend fun saveDreamClockTimerEntityId(entityId: String) {
        playerSettingsStore.dreamClockTimerEntityId.set(entityId.trim())
        com.example.ava.services.VoiceSatelliteService.getInstance()?.resubscribeDreamClockTimer()
    }

    suspend fun saveDreamClockSeason(season: DreamClockSeason) {
        playerSettingsStore.dreamClockSeason.set(season.storageKey)
    }
    
    suspend fun saveScreensaver(enabled: Boolean) {
        playerSettingsStore.update {
            it.copy(enableScreensaver = enabled, enableScreensaverVisible = false)
        }
        if (enabled) {
            sidebarSettingsStore.offerHomeEntry(SidebarItemKey.SimpleClock)
        }
    }
    
    suspend fun saveScreensaverDisplay(enabled: Boolean) {
        playerSettingsStore.enableScreensaverDisplay.set(enabled)
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }

    suspend fun saveScreensaverDoubleTapToggleEnabled(enabled: Boolean) {
        playerSettingsStore.screensaverDoubleTapToggleEnabled.set(enabled)
    }

    suspend fun saveScreensaverWallpaperUrl(url: String) {
        playerSettingsStore.screensaverWallpaperUrl.set(url)
    }

    suspend fun saveScreensaverWallpaperRefreshSeconds(seconds: Int) {
        playerSettingsStore.screensaverWallpaperRefreshSeconds.set(seconds)
    }

    suspend fun saveScreensaverWallpaperDualPane(enabled: Boolean) {
        playerSettingsStore.screensaverWallpaperDualPane.set(enabled)
    }

    suspend fun saveScreensaverWallpaperDarkOverlayEnabled(enabled: Boolean) {
        playerSettingsStore.screensaverWallpaperDarkOverlayEnabled.set(enabled)
    }

    suspend fun saveSimpleClockWeatherEnabled(enabled: Boolean) {
        playerSettingsStore.enableScreensaverWeather.set(enabled)
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }

    suspend fun saveSimpleClockWeatherEntity(entity: String) {
        playerSettingsStore.screensaverWeatherEntityId.set(entity)
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }

    suspend fun saveSimpleClockPortraitStyle(style: SimpleClockPortraitStyle) {
        playerSettingsStore.screensaverPortraitStyle.set(style.storageKey)
    }

    suspend fun saveSimpleClock12HourEnabled(enabled: Boolean) {
        playerSettingsStore.screensaver12HourEnabled.set(enabled)
    }

    suspend fun saveSimpleClockPixelShiftEnabled(enabled: Boolean) {
        playerSettingsStore.screensaverPixelShiftEnabled.set(enabled)
    }

    suspend fun saveSimpleClockDarkOffEnabled(enabled: Boolean) {
        playerSettingsStore.screensaverDarkOffEnabled.set(enabled)
    }

    suspend fun saveSmartPowerSavingAodEnabled(enabled: Boolean) {
        playerSettingsStore.smartPowerSavingAodEnabled.set(enabled)
    }

    suspend fun saveSmartPowerSavingAodTimeoutSeconds(seconds: Int) {
        playerSettingsStore.smartPowerSavingAodTimeoutSeconds.set(seconds)
    }

    suspend fun saveSmartPowerSavingAodMaskPercent(percent: Int) {
        playerSettingsStore.smartPowerSavingAodMaskPercent.set(percent)
    }

    suspend fun saveScreensaverStatusSlotsEnabled(enabled: Boolean) {
        playerSettingsStore.enableScreensaverStatusSlots.set(enabled)
    }

    suspend fun saveScreensaverStatusSlots(slots: List<com.example.ava.settings.SimpleClockStatusSlot>) {
        playerSettingsStore.screensaverStatusSlots.set(slots)
    }
    
    suspend fun saveMinimalLauncherEnabled(enabled: Boolean) {
        val haWasOn = playerSettingsStore.enableMinimalLauncherHaDisplay.get()
        playerSettingsStore.enableMinimalLauncher.set(enabled)
        if (!enabled && haWasOn) {
            playerSettingsStore.enableMinimalLauncherHaDisplay.set(false)
            restartVoiceSatelliteServiceIfRunning()
        }
        refreshHideHomeChromePref()
    }

    suspend fun saveMinimalLauncherVisiblePackages(
        packages: List<String>,
        allLauncherPackages: Collection<String> = emptyList(),
    ) {
        val haWasOn = playerSettingsStore.enableMinimalLauncherHaDisplay.get()
        playerSettingsStore.minimalLauncherVisiblePackages.set(packages)
        val launcherOn = playerSettingsStore.enableMinimalLauncher.get()
        val canExpose = launcherOn &&
            canExposeMinimalLauncherAppsToHa(
                packages,
                allLauncherPackages,
            )
        if (haWasOn && !canExpose) {
            playerSettingsStore.enableMinimalLauncherHaDisplay.set(false)
            restartVoiceSatelliteServiceIfRunning()
        } else if (haWasOn) {
            restartVoiceSatelliteServiceIfRunning()
        }
    }

    suspend fun saveMinimalLauncherHaDisplay(
        enabled: Boolean,
        allLauncherPackages: Collection<String>,
    ) {
        val current = playerSettingsStore.get()
        val canExpose = current.enableMinimalLauncher &&
            canExposeMinimalLauncherAppsToHa(
                current.minimalLauncherVisiblePackages,
                allLauncherPackages,
            )
        val next = enabled && canExpose
        if (next == current.enableMinimalLauncherHaDisplay) return
        playerSettingsStore.enableMinimalLauncherHaDisplay.set(next)
        restartVoiceSatelliteServiceIfRunning()
    }

    suspend fun saveAppWindowPackages(packages: List<String>) {
        playerSettingsStore.appWindowPackages.set(packages)
    }

    suspend fun saveMinimalLauncherIconPack(packageName: String) {
        playerSettingsStore.minimalLauncherIconPack.set(packageName)
    }

    suspend fun saveMinimalLauncherIconShape(shape: String) {
        playerSettingsStore.minimalLauncherIconShape.set(shape)
    }

    suspend fun saveMinimalLauncherShowDesktopLabels(show: Boolean) {
        playerSettingsStore.minimalLauncherShowDesktopLabels.set(show)
    }

    suspend fun saveSidebarEnabled(enabled: Boolean) {
        sidebarSettingsStore.enableSidebar.set(enabled)
        refreshHideHomeChromePref()
    }

    suspend fun saveSidebarPosition(position: SidebarPosition) {
        sidebarSettingsStore.sidebarPosition.set(position)
    }

    suspend fun saveSidebarShowDeviceControl(enabled: Boolean) {
        sidebarSettingsStore.showDeviceControl.set(enabled)
    }

    suspend fun saveSidebarShowTouchPad(enabled: Boolean) {
        sidebarSettingsStore.showTouchPad.set(enabled)
    }

    suspend fun saveSidebarItemName(key: SidebarItemKey, name: String) {
        sidebarSettingsStore.setItemName(key, name)
    }

    suspend fun saveSidebarSettingsLabel(name: String) {
        sidebarSettingsStore.setCustomLabel(
            com.example.ava.settings.SIDEBAR_SETTINGS_LABEL_KEY,
            name,
        )
    }

    suspend fun saveSidebarShowVoiceMessage(enabled: Boolean) {
        sidebarSettingsStore.showVoiceMessage.set(enabled)
    }

    suspend fun saveSidebarShowBrowser(enabled: Boolean) {
        sidebarSettingsStore.showBrowser.set(enabled)
    }

    suspend fun saveSidebarShowWeather(enabled: Boolean) {
        sidebarSettingsStore.showWeather.set(enabled)
    }

    suspend fun saveSidebarShowSimpleClock(enabled: Boolean) {
        sidebarSettingsStore.showSimpleClock.set(enabled)
    }

    suspend fun saveSidebarShowDreamClock(enabled: Boolean) {
        sidebarSettingsStore.showDreamClock.set(enabled)
    }

    suspend fun saveSidebarShowQuickEntity(enabled: Boolean) {
        sidebarSettingsStore.showQuickEntity.set(enabled)
    }

    suspend fun saveSidebarShowVinylCoverDisplay(enabled: Boolean) {
        sidebarSettingsStore.showVinylCoverDisplay.set(enabled)
    }

    suspend fun saveSidebarShowHomeLock(enabled: Boolean) {
        sidebarSettingsStore.showHomeLock.set(enabled)
    }

    suspend fun saveSidebarShowCamera(enabled: Boolean) {
        sidebarSettingsStore.showCamera.set(enabled)
    }

    suspend fun saveSidebarShowMuteMicrophone(enabled: Boolean) {
        sidebarSettingsStore.showMuteMicrophone.set(enabled)
    }

    suspend fun saveSidebarShowDarkMode(enabled: Boolean) {
        sidebarSettingsStore.showDarkMode.set(enabled)
    }

    suspend fun saveSidebarShowHome(enabled: Boolean) {
        sidebarSettingsStore.showHome.set(enabled)
    }

    suspend fun saveSidebarHideHeader(enabled: Boolean) {
        sidebarSettingsStore.hideSidebarHeader.set(enabled)
    }

    suspend fun saveHomeCornerButton(button: HomeCornerButton) {
        sidebarSettingsStore.homeCornerButton.set(button)
    }

    suspend fun saveSidebarHideHomeHeader(enabled: Boolean) {
        sidebarSettingsStore.hideHomeHeader.set(enabled)
        refreshHideHomeChromePref()
    }

    suspend fun saveSidebarItemOrder(order: List<SidebarItemKey>) {
        sidebarSettingsStore.itemOrder.set(order)
    }

    suspend fun saveSidebarDockOrder(order: List<SidebarDockKey>) {
        sidebarSettingsStore.dockOrder.set(order)
    }

    suspend fun saveHomeLockEnabled(enabled: Boolean) {
        homeLockSettingsStore.setEnabled(enabled)
        // Don't lock while still in Settings — wait until Home is shown, then idle-timer.
        HomeLockSession.unlock()
    }

    suspend fun saveHomeLockPin(newPin: String) {
        homeLockSettingsStore.setPin(newPin)
    }

    suspend fun saveHomeLockPinLength(length: Int) {
        homeLockSettingsStore.setPinLength(length)
        // Length change resets the PIN; keep session unlocked while still in Settings.
        HomeLockSession.unlock()
    }

    suspend fun saveHomeLockIdleTimeoutSeconds(seconds: Int) {
        homeLockSettingsStore.setIdleTimeoutSeconds(seconds)
    }

    suspend fun saveHomeLockShuffleKeypad(enabled: Boolean) {
        homeLockSettingsStore.setShuffleKeypad(enabled)
    }

    suspend fun saveHomeLockAntiBruteForce(enabled: Boolean) {
        homeLockSettingsStore.setAntiBruteForce(enabled)
    }

    suspend fun saveHomeLockTarget(target: HomeLockTarget) {
        homeLockSettingsStore.setLockTarget(target)
        // Keep session unlocked while still configuring in Settings.
        HomeLockSession.unlock()
    }

    suspend fun saveUpdateAutoUpdate(enabled: Boolean) {
        updateSettingsStore.setAutoUpdate(enabled)
    }

    suspend fun saveUpdateCheckOnLaunch(enabled: Boolean) {
        updateSettingsStore.setCheckOnLaunch(enabled)
    }

    suspend fun saveUpdateIgnoreUpdate(enabled: Boolean) {
        updateSettingsStore.setIgnoreUpdate(enabled)
    }

    suspend fun saveUpdateHaEntity(enabled: Boolean) {
        updateSettingsStore.setHaUpdateEntity(enabled)
    }

    suspend fun saveUpdateReopenAfterUpdate(enabled: Boolean) {
        updateSettingsStore.setReopenAfterUpdate(enabled)
    }

    suspend fun saveUpdateStartServicesAfterInstall(enabled: Boolean) {
        updateSettingsStore.setStartServicesAfterInstall(enabled)
    }

    suspend fun saveUpdateDownloadMethod(method: UpdateDownloadMethod) {
        updateSettingsStore.setDownloadMethod(method)
    }

    private suspend fun refreshHideHomeChromePref() {
        syncHideHomeChromePrefFromStores(getApplication())
    }

    suspend fun saveAutoRestart(enabled: Boolean) {
        playerSettingsStore.enableAutoRestart.set(enabled)
        // Immediately update boot script based on setting
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (enabled) {
                com.example.ava.utils.RootHelper.installBootScript(
                    "com.example.ava",
                    "com.example.ava.services.VoiceSatelliteService"
                )
            } else {
                com.example.ava.utils.RootHelper.removeBootScript()
            }
        }
    }

    suspend fun saveCrashSelfHeal(enabled: Boolean) {
        playerSettingsStore.enableCrashSelfHeal.set(enabled)
        com.example.ava.crash.CrashSelfHeal.applyEnabled(getApplication(), enabled)
    }

    suspend fun saveKeepCpuAwakeOnScreenOff(enabled: Boolean) {
        playerSettingsStore.keepCpuAwakeOnScreenOff.set(enabled)
    }

    suspend fun saveStartServiceOnAppOpen(enabled: Boolean) {
        playerSettingsStore.startServiceOnAppOpen.set(enabled)
    }

    suspend fun saveStartServiceOnAppOpenDelaySeconds(seconds: Int) {
        playerSettingsStore.startServiceOnAppOpenDelaySeconds.set(
            com.example.ava.settings.PlayerSettings.clampStartServiceOnAppOpenDelaySeconds(seconds),
        )
    }

    suspend fun saveHaSwitchOverlay(enabled: Boolean) {
        playerSettingsStore.enableHaSwitchOverlay.set(enabled)
    }

    suspend fun saveManualDismissButton(enabled: Boolean) {
        playerSettingsStore.enableManualDismissButton.set(enabled)
        // HA button list is built at satellite start (see VoiceSatelliteEntities); restart so
        // Controls rediscovers manual_dismiss — same pattern as notification scene / audio events.
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }

    suspend fun saveVoiceMessageReceive(enabled: Boolean) {
        playerSettingsStore.update { settings ->
            if (!settings.enableVoiceMessageOverlay) return@update settings
            settings.copy(enableVoiceMessageReceive = enabled)
        }
    }

    suspend fun saveVoiceMessageOverlay(enabled: Boolean) {
        if (!enabled) {
            playerSettingsStore.update { settings ->
                settings.copy(
                    enableVoiceMessageOverlay = false,
                    enableVoiceMessageOverlayVisible = false,
                )
            }
        } else {
            playerSettingsStore.enableVoiceMessageOverlay.set(true)
            sidebarSettingsStore.offerHomeEntry(SidebarItemKey.VoiceMessage)
        }
    }

    suspend fun saveVoiceMessageOverlayDisplay(enabled: Boolean) {
        playerSettingsStore.enableVoiceMessageOverlayDisplay.set(enabled)
    }

    suspend fun saveVoiceMessageDisplayName(name: String) {
        playerSettingsStore.voiceMessageDisplayName.set(name)
    }

    suspend fun saveVoiceMessageDelayMinutes(minutes: Int) {
        playerSettingsStore.voiceMessageDelayMinutes.set(minutes)
    }

    suspend fun saveVoiceMessageReceiveMode(mode: String) {
        playerSettingsStore.update { settings ->
            if (!settings.enableVoiceMessageOverlay) return@update settings
            settings.copy(voiceMessageReceiveMode = mode)
        }
    }

    suspend fun saveVoiceOverlayIntercom(enabled: Boolean) {
        playerSettingsStore.update { settings ->
            if (!settings.enableVoiceMessageOverlay) return@update settings
            val nextIntercom = enabled
            val nextCall = settings.enableVoiceOverlayCall
            val call = if (nextIntercom || nextCall) nextCall else true
            settings.copy(
                enableVoiceOverlayIntercom = nextIntercom,
                enableVoiceOverlayCall = call
            )
        }
    }

    suspend fun saveVoiceOverlayCall(enabled: Boolean) {
        playerSettingsStore.update { settings ->
            if (!settings.enableVoiceMessageOverlay) return@update settings
            val nextCall = enabled
            val nextIntercom = settings.enableVoiceOverlayIntercom
            val intercom = if (nextIntercom || nextCall) nextIntercom else true
            settings.copy(
                enableVoiceOverlayIntercom = intercom,
                enableVoiceOverlayCall = nextCall
            )
        }
    }

    suspend fun saveVoiceCallAnswerRequired(enabled: Boolean) {
        playerSettingsStore.update { settings ->
            if (!settings.enableVoiceMessageOverlay) return@update settings
            if (enabled) {
                val ringtone = settings.voiceCallRingtone.ifBlank {
                    PlayerSettings.DEFAULT_VOICE_CALL_RINGTONE
                }
                settings.copy(
                    enableVoiceCallAnswerRequired = true,
                    voiceCallRingtone = ringtone
                )
            } else {
                settings.copy(enableVoiceCallAnswerRequired = false)
            }
        }
    }

    suspend fun saveVoiceCallRingtone(uri: String) {
        val target = uri.ifBlank { PlayerSettings.DEFAULT_VOICE_CALL_RINGTONE }
        val persisted = withContext(Dispatchers.IO) {
            SoundUriPersistence.persist(application, target, "voice_call_ringtone")
        }
        playerSettingsStore.update { settings ->
            if (!settings.enableVoiceMessageOverlay) return@update settings
            settings.copy(voiceCallRingtone = persisted)
        }
    }

    suspend fun saveVoiceCallVideoQuality(quality: VoiceCallVideoQuality) {
        playerSettingsStore.update { settings ->
            if (!settings.enableVoiceMessageOverlay) return@update settings
            settings.copy(voiceCallVideoQuality = quality.storageKey)
        }
        AvaVoiceVideoBridge.applySavedQuality(application)
    }
    
    suspend fun saveSceneDisplayDuration(duration: Int) {
        notificationSettingsStore.sceneDisplayDuration.set(duration)
    }

    suspend fun saveNotificationSceneEnabled(enabled: Boolean) {
        notificationSettingsStore.notificationSceneEnabled.set(enabled)
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }
    
    suspend fun saveCustomSceneUrl(url: String) {
        notificationSettingsStore.customSceneUrl.set(url)
        com.example.ava.notifications.NotificationScenes.loadCustomSceneFromUrl(
            url,
            context = getApplication(),
        )
    }
    
    suspend fun saveNotificationDisplayStyle(style: String) {
        notificationSettingsStore.displayStyle.set(style)
    }

    suspend fun saveBannerPosition(position: Int) {
        notificationSettingsStore.bannerPosition.set(position)
    }

    suspend fun saveBannerColor(color: String) {
        notificationSettingsStore.bannerColor.set(color)
    }

    suspend fun saveBannerLogoEnabled(enabled: Boolean) {
        notificationSettingsStore.bannerLogoEnabled.set(enabled)
    }

    suspend fun saveSoundEnabled(enabled: Boolean) {
        notificationSettingsStore.soundEnabled.set(enabled)
    }
    
    suspend fun saveSoundUri(uri: String) {
        notificationSettingsStore.soundUri.set(uri)
    }

    suspend fun saveScreensaverUrl(url: String) {
        screensaverSettingsStore.screensaverUrl.set(url)
    }

    suspend fun saveScreensaverEnabled(enabled: Boolean) {
        screensaverSettingsStore.enabled.set(enabled)
    }

    suspend fun saveScreensaverTimeout(timeoutSeconds: Int) {
        screensaverSettingsStore.timeoutSeconds.set(timeoutSeconds)
    }

    suspend fun saveScreensaverTimeoutVisible(visible: Boolean) {
        screensaverSettingsStore.screensaverTimeoutVisible.set(visible)
    }

    suspend fun saveScreensaverDarkOff(enabled: Boolean) {
        screensaverSettingsStore.darkOffEnabled.set(enabled)
    }

    suspend fun saveScreensaverPixelShift(enabled: Boolean) {
        screensaverSettingsStore.pixelShiftEnabled.set(enabled)
    }

    suspend fun saveScreensaverSmartAodEnabled(enabled: Boolean) {
        screensaverSettingsStore.smartAodEnabled.set(enabled)
    }

    suspend fun saveScreensaverSmartAodMaskPercent(percent: Int) {
        screensaverSettingsStore.smartAodMaskPercent.set(percent)
    }

    suspend fun saveScreensaverSmartCpuThrottle(enabled: Boolean) {
        screensaverSettingsStore.smartCpuThrottleEnabled.set(enabled)
    }

    suspend fun saveScreensaverPersonWake(enabled: Boolean) {
        if (enabled && com.example.ava.mods.ModCameraStreamBridge.isActive(getApplication())) {
            android.util.Log.i(
                "SettingsViewModel",
                "Ignoring person-wake enable — camera-stream mod owns the camera",
            )
            screensaverSettingsStore.personWakeEnabled.set(false)
            return
        }
        screensaverSettingsStore.personWakeEnabled.set(enabled)
    }

    suspend fun saveScreensaverKeepOnOverlays(enabled: Boolean) {
        screensaverSettingsStore.keepOnOverlays.set(enabled)
    }

    suspend fun saveScreensaverBackgroundPause(enabled: Boolean) {
        screensaverSettingsStore.backgroundPauseEnabled.set(enabled)
    }

    suspend fun saveScreensaverMotionOn(enabled: Boolean) {
        screensaverSettingsStore.motionOnEnabled.set(enabled)
    }

    suspend fun saveScreensaverShowAfterScreenOn(enabled: Boolean) {
        screensaverSettingsStore.showAfterScreenOn.set(enabled)
    }
    
    suspend fun saveScreensaverUrlVisible(visible: Boolean) {
        screensaverSettingsStore.screensaverUrlVisible.set(visible)
    }
    
    suspend fun saveScreensaverHaDisplay(enabled: Boolean) {
        screensaverSettingsStore.enableHaDisplay.set(enabled)
    }

    suspend fun saveScreensaverHaSwitchTwoWay(enabled: Boolean) {
        screensaverSettingsStore.haSwitchTwoWayEnabled.set(enabled)
    }
    
    suspend fun saveDawnWallpaperEnabled(enabled: Boolean) {
        screensaverSettingsStore.dawnWallpaperEnabled.set(enabled)
        if (!enabled) {
            screensaverSettingsStore.enableDawnEntitySlots.set(false)
            screensaverSettingsStore.enableDawnEntityHaSlots.set(false)
        }
    }

    suspend fun saveDawnEntitySlotsEnabled(enabled: Boolean) {
        screensaverSettingsStore.enableDawnEntitySlots.set(enabled)
        if (!enabled) {
            screensaverSettingsStore.enableDawnEntityHaSlots.set(false)
        }
    }

    suspend fun saveDawnEntityHaSlotsEnabled(enabled: Boolean) {
        screensaverSettingsStore.enableDawnEntityHaSlots.set(enabled)
    }

    suspend fun saveDawnEntitySlots(slots: List<com.example.ava.settings.DawnEntitySlot>) {
        screensaverSettingsStore.dawnEntitySlots.set(slots)
    }

    suspend fun saveDawnWallpaperSourceUrl(url: String) {
        screensaverSettingsStore.dawnWallpaperSourceUrl.set(url)
    }

    suspend fun saveDawnIsoTimeEnabled(enabled: Boolean) {
        screensaverSettingsStore.dawnIsoTimeEnabled.set(enabled)
    }

    suspend fun saveDawnMergeWeatherIconsEnabled(enabled: Boolean) {
        screensaverSettingsStore.dawnMergeWeatherIconsEnabled.set(enabled)
    }
    
    suspend fun saveCameraEnabled(enabled: Boolean) {
        experimentalSettingsStore.setCameraEnabled(enabled)
        if (!enabled) {
            sidebarSettingsStore.showCamera.set(false)
        }
        kotlinx.coroutines.delay(100)
        com.example.ava.services.VoiceSatelliteService.getInstance()
            ?.restartVoiceSatellite(com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE)
    }
    
    suspend fun saveCameraMode(mode: com.example.ava.settings.CameraMode) {
        experimentalSettingsStore.setCameraMode(mode)
        if (mode == com.example.ava.settings.CameraMode.SNAPSHOT) {
            com.example.ava.esphome.voicesatellite.VoiceSatelliteCamera.clearSavedRecordingState(application)
            sidebarSettingsStore.showCamera.set(false)
        }
        kotlinx.coroutines.delay(100)
        com.example.ava.services.VoiceSatelliteService.getInstance()
            ?.restartVoiceSatellite(com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE)
    }
    
    suspend fun saveCameraPosition(position: com.example.ava.settings.CameraPosition) {
        experimentalSettingsStore.setCameraPosition(position)
        kotlinx.coroutines.delay(100)
        com.example.ava.services.VoiceSatelliteService.getInstance()
            ?.restartVoiceSatellite(com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE)
    }
    
    suspend fun saveCameraOrientation(orientation: com.example.ava.settings.CameraOrientation) {
        experimentalSettingsStore.setCameraOrientation(orientation)
        kotlinx.coroutines.delay(100)
        com.example.ava.services.VoiceSatelliteService.getInstance()
            ?.restartVoiceSatellite(com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE)
    }
    
    suspend fun saveImageSize(size: Int) {
        experimentalSettingsStore.setImageSize(size)
        kotlinx.coroutines.delay(100)
        com.example.ava.services.VoiceSatelliteService.getInstance()
            ?.restartVoiceSatellite(com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE)
    }
    
    suspend fun saveVideoFps(fps: Int) {
        experimentalSettingsStore.setVideoFps(fps)
        kotlinx.coroutines.delay(100)
        com.example.ava.services.VoiceSatelliteService.getInstance()
            ?.restartVoiceSatellite(com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE)
    }
    
    suspend fun saveVideoResolution(resolution: Int) {
        experimentalSettingsStore.setVideoResolution(resolution)
        kotlinx.coroutines.delay(100)
        com.example.ava.services.VoiceSatelliteService.getInstance()
            ?.restartVoiceSatellite(com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE)
    }
    
    suspend fun savePersonDetectionEnabled(enabled: Boolean) {
        experimentalSettingsStore.setPersonDetectionEnabled(enabled)
        kotlinx.coroutines.delay(100)
        com.example.ava.services.VoiceSatelliteService.getInstance()
            ?.restartVoiceSatellite(com.example.ava.services.SatelliteRestartReason.SATELLITE_PIPELINE)
    }
    
    suspend fun saveFaceBoxEnabled(enabled: Boolean) {
        experimentalSettingsStore.setFaceBoxEnabled(enabled)
    }
    
    suspend fun saveEnvironmentSensorEnabled(enabled: Boolean) {
        experimentalSettingsStore.setEnvironmentSensorEnabled(enabled)
    }
    
    suspend fun saveSensorUpdateInterval(interval: Int) {
        experimentalSettingsStore.setSensorUpdateInterval(interval)
        com.example.ava.services.VoiceSatelliteService.updateSensorInterval(interval)
    }

    suspend fun saveEnvironmentLightSensorEnabled(enabled: Boolean) {
        experimentalSettingsStore.setEnvironmentLightSensorEnabled(enabled)
    }

    suspend fun saveEnvironmentMagneticSensorEnabled(enabled: Boolean) {
        experimentalSettingsStore.setEnvironmentMagneticSensorEnabled(enabled)
    }
    
    
    suspend fun saveProximitySensorEnabled(enabled: Boolean) {
        experimentalSettingsStore.setProximitySensorEnabled(enabled)
    }
    
    suspend fun saveProximitySendToHass(enabled: Boolean) {
        experimentalSettingsStore.setProximitySendToHass(enabled)
    }

    suspend fun saveProximityHassUpdateInterval(interval: Int) {
        experimentalSettingsStore.setProximityHassUpdateInterval(interval)
        com.example.ava.services.VoiceSatelliteService.updateProximityPublishInterval(interval)
    }
    
    suspend fun saveProximityWakeScreen(enabled: Boolean) {
        experimentalSettingsStore.setProximityWakeScreen(enabled)
    }
    
    suspend fun saveProximityAwayDelay(delay: Int) {
        experimentalSettingsStore.setProximityAwayDelay(delay)
    }
    
    suspend fun saveProximityAutoUnlock(enabled: Boolean) {
        experimentalSettingsStore.setProximityAutoUnlock(enabled)
    }
    
    
    suspend fun saveScreenBrightnessEnabled(enabled: Boolean) {
        experimentalSettingsStore.setScreenBrightnessEnabled(enabled)
        kotlinx.coroutines.delay(100)
        // initScreenBrightness() only runs during satellite start().
        restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
    }

    suspend fun saveScreenTouchSensorEnabled(enabled: Boolean) {
        experimentalSettingsStore.setScreenTouchSensorEnabled(enabled)
        kotlinx.coroutines.delay(100)
        // initScreenTouchSensor() only runs during satellite start().
        restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
    }

    suspend fun saveScreenTouchAwayDelay(delay: Int) {
        experimentalSettingsStore.setScreenTouchAwayDelay(delay)
        com.example.ava.sensor.ScreenTouchSensor.setAwayDelaySeconds(delay)
    }

    suspend fun saveScreenGestureEnabled(enabled: Boolean) {
        experimentalSettingsStore.setScreenGestureEnabled(enabled)
        com.example.ava.sensor.ScreenGestureRecognizer.sync(experimentalSettingsStore.getCached())
        kotlinx.coroutines.delay(100)
        // initScreenGestureSensor() only runs during satellite start().
        restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
    }

    suspend fun saveScreenGestureSpatialEnabled(enabled: Boolean): Boolean {
        experimentalSettingsStore.setScreenGestureSpatialEnabled(enabled)
        val toasted = if (enabled) {
            disableGestureOpponents(
                com.example.ava.sensor.ScreenGestureCatalog.resolvedCategoryTokens(
                    experimentalSettingsStore.getCached().screenGestureSpatialTokens,
                    com.example.ava.sensor.ScreenGestureCategory.Spatial,
                ),
            )
        } else {
            false
        }
        com.example.ava.sensor.ScreenGestureRecognizer.sync(experimentalSettingsStore.getCached())
        return toasted
    }

    suspend fun saveScreenGestureDigitsEnabled(enabled: Boolean): Boolean {
        experimentalSettingsStore.setScreenGestureDigitsEnabled(enabled)
        val toasted = if (enabled) {
            disableGestureOpponents(
                com.example.ava.sensor.ScreenGestureCatalog.resolvedCategoryTokens(
                    experimentalSettingsStore.getCached().screenGestureDigitTokens,
                    com.example.ava.sensor.ScreenGestureCategory.Digits,
                ),
            )
        } else {
            false
        }
        com.example.ava.sensor.ScreenGestureRecognizer.sync(experimentalSettingsStore.getCached())
        return toasted
    }

    suspend fun saveScreenGestureGeometryEnabled(enabled: Boolean) {
        experimentalSettingsStore.setScreenGestureGeometryEnabled(enabled)
        com.example.ava.sensor.ScreenGestureRecognizer.sync(experimentalSettingsStore.getCached())
    }

    suspend fun saveScreenGestureToken(
        category: com.example.ava.sensor.ScreenGestureCategory,
        token: String,
        enabled: Boolean,
    ): Boolean {
        val settings = experimentalSettingsStore.getCached()
        val current = when (category) {
            com.example.ava.sensor.ScreenGestureCategory.Spatial ->
                com.example.ava.sensor.ScreenGestureCatalog.resolvedCategoryTokens(
                    settings.screenGestureSpatialTokens,
                    category,
                )
            com.example.ava.sensor.ScreenGestureCategory.Digits ->
                com.example.ava.sensor.ScreenGestureCatalog.resolvedCategoryTokens(
                    settings.screenGestureDigitTokens,
                    category,
                )
            com.example.ava.sensor.ScreenGestureCategory.Geometry ->
                com.example.ava.sensor.ScreenGestureCatalog.resolvedCategoryTokens(
                    settings.screenGestureGeometryTokens,
                    category,
                )
        }
        val next = if (enabled) current + token else current - token
        experimentalSettingsStore.setScreenGestureCategoryTokens(category, next)
        val toasted = if (enabled) disableGestureOpponents(setOf(token)) else false
        com.example.ava.sensor.ScreenGestureRecognizer.sync(experimentalSettingsStore.getCached())
        return toasted
    }

    private suspend fun disableGestureOpponents(justEnabled: Set<String>): Boolean {
        val enabled = com.example.ava.sensor.ScreenGestureCatalog.resolvedTokens(
            experimentalSettingsStore.getCached(),
        )
        val toDisable = justEnabled.flatMap { com.example.ava.sensor.ScreenGestureCatalog.opponentsOf(it) }
            .filter { it in enabled }
            .toSet()
        if (toDisable.isEmpty()) return false
        toDisable.groupBy { token ->
            com.example.ava.sensor.ScreenGestureCatalog.categoryOf(token)
        }.forEach { (category, tokens) ->
            if (category == null) return@forEach
            val current = when (category) {
                com.example.ava.sensor.ScreenGestureCategory.Spatial ->
                    com.example.ava.sensor.ScreenGestureCatalog.resolvedCategoryTokens(
                        experimentalSettingsStore.getCached().screenGestureSpatialTokens,
                        category,
                    )
                com.example.ava.sensor.ScreenGestureCategory.Digits ->
                    com.example.ava.sensor.ScreenGestureCatalog.resolvedCategoryTokens(
                        experimentalSettingsStore.getCached().screenGestureDigitTokens,
                        category,
                    )
                com.example.ava.sensor.ScreenGestureCategory.Geometry ->
                    com.example.ava.sensor.ScreenGestureCatalog.resolvedCategoryTokens(
                        experimentalSettingsStore.getCached().screenGestureGeometryTokens,
                        category,
                    )
            }
            experimentalSettingsStore.setScreenGestureCategoryTokens(category, current - tokens.toSet())
        }
        return true
    }
    
    
    suspend fun saveForceOrientationEnabled(enabled: Boolean) {
        experimentalSettingsStore.setForceOrientationEnabled(enabled)
    }
    
    suspend fun saveForceOrientationMode(mode: String) {
        experimentalSettingsStore.setForceOrientationMode(mode)
    }
    
    
    suspend fun saveDisplaySizeEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDisplaySizeEnabled(enabled)
    }
    
    suspend fun saveDisplaySizeScale(scale: Float) {
        experimentalSettingsStore.setDisplaySizeScale(scale)
    }
    
    
    suspend fun saveDiagnosticSensorEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticSensorEnabled(enabled)
        markDiagnosticRestartPending()
    }
    
    suspend fun saveDiagnosticWifiEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticWifiEnabled(enabled)
        markDiagnosticRestartPending()
    }
    
    suspend fun saveDiagnosticIpEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticIpEnabled(enabled)
        markDiagnosticRestartPending()
    }
    
    suspend fun saveDiagnosticStorageEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticStorageEnabled(enabled)
        markDiagnosticRestartPending()
    }
    
    suspend fun saveDiagnosticMemoryEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticMemoryEnabled(enabled)
        markDiagnosticRestartPending()
    }
    
    suspend fun saveDiagnosticUptimeEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticUptimeEnabled(enabled)
        markDiagnosticRestartPending()
    }
    
    suspend fun saveDiagnosticKillAppEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticKillAppEnabled(enabled)
        markDiagnosticRestartPending()
    }
    
    suspend fun saveDiagnosticRebootEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticRebootEnabled(enabled)
        markDiagnosticRestartPending()
    }
    
    suspend fun saveDiagnosticBatteryLevelEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticBatteryLevelEnabled(enabled)
        markDiagnosticRestartPending()
    }
    
    suspend fun saveDiagnosticBatteryVoltageEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticBatteryVoltageEnabled(enabled)
        markDiagnosticRestartPending()
    }
    
    suspend fun saveDiagnosticChargingStatusEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticChargingStatusEnabled(enabled)
        markDiagnosticRestartPending()
    }

    suspend fun saveDiagnosticMusicActiveEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticMusicActiveEnabled(enabled)
        markDiagnosticRestartPending()
    }

    suspend fun saveDiagnosticLastUsedAppEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticLastUsedAppEnabled(enabled)
        markDiagnosticRestartPending()
    }

    suspend fun saveDiagnosticBluetoothEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticBluetoothEnabled(enabled)
        markDiagnosticRestartPending()
    }

    suspend fun saveDiagnosticNetworkTypeEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDiagnosticNetworkTypeEnabled(enabled)
        markDiagnosticRestartPending()
    }
    
    suspend fun saveIntentLauncherEnabled(enabled: Boolean) {
        experimentalSettingsStore.setIntentLauncherEnabled(enabled)
        kotlinx.coroutines.delay(100)
        // intent_launcher_status / launch_intent are registered during satellite
        // start(), and only when both the launcher and HA display switches are on.
        restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
    }
    
    suspend fun saveIntentLauncherHaDisplayEnabled(enabled: Boolean) {
        experimentalSettingsStore.setIntentLauncherHaDisplayEnabled(enabled)
    }

    suspend fun saveMediaKeyEnabled(enabled: Boolean) {
        experimentalSettingsStore.setMediaKeyEnabled(enabled)
        kotlinx.coroutines.delay(100)
        // pause_media / media_key are registered during satellite start(), and
        // only when both this switch and the Home Assistant display switch are on.
        restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
    }

    suspend fun saveMediaKeyHaDisplayEnabled(enabled: Boolean) {
        experimentalSettingsStore.setMediaKeyHaDisplayEnabled(enabled)
    }

    suspend fun saveAdbControlEnabled(enabled: Boolean) {
        experimentalSettingsStore.setAdbControlEnabled(enabled)
    }

    suspend fun saveClusterManagementEnabled(enabled: Boolean) {
        experimentalSettingsStore.setClusterManagementEnabled(enabled)
        // Also apply immediately — do not wait solely on AvaApplication's Flow
        // (agent→console must flip :8888 without process kill).
        val s = experimentalSettingsStore.get()
        com.example.ava.fleet.FleetManager.apply(
            getApplication(),
            s.clusterManagementEnabled,
            s.webConsoleEnabled,
        )
        // webserverPort in DeviceInfoResponse drives HA's "Visit" link;
        // kick the satellite so HA re-reads the updated port on reconnect.
        restartVoiceSatelliteServiceIfRunning()
    }

    suspend fun saveWebConsoleEnabled(enabled: Boolean) {
        experimentalSettingsStore.setWebConsoleEnabled(enabled)
        val s = experimentalSettingsStore.get()
        com.example.ava.fleet.FleetManager.apply(
            getApplication(),
            s.clusterManagementEnabled,
            s.webConsoleEnabled,
        )
        restartVoiceSatelliteServiceIfRunning()
    }

    suspend fun saveClusterAccessPassword(password: String) {
        // Browser types plain password; device stores fixed 16-char wire token only.
        val wire = com.example.ava.fleet.FleetPasswordCodec.toWireToken(password.trim())
        experimentalSettingsStore.setClusterAccessToken(wire)
        com.example.ava.fleet.FleetAuth.invalidateCache()
    }

    suspend fun saveDeviceIncidentLogEnabled(enabled: Boolean) {
        experimentalSettingsStore.setDeviceIncidentLogEnabled(enabled)
        val s = experimentalSettingsStore.get()
        com.example.ava.crash.MainThreadStallWatchdog.applyFlags(
            getApplication(),
            s.deviceIncidentLogEnabled,
            s.mainThreadStallWatchdogEnabled,
        )
    }

    suspend fun saveMainThreadStallWatchdogEnabled(enabled: Boolean) {
        experimentalSettingsStore.setMainThreadStallWatchdogEnabled(enabled)
        val s = experimentalSettingsStore.get()
        com.example.ava.crash.MainThreadStallWatchdog.applyFlags(
            getApplication(),
            s.deviceIncidentLogEnabled,
            s.mainThreadStallWatchdogEnabled,
        )
    }

    suspend fun saveClarityEnabled(enabled: Boolean) {
        experimentalSettingsStore.setClarityEnabled(enabled)
        com.example.ava.AvaClarity.applyEnabled(getApplication(), enabled)
    }

    suspend fun saveChorusWakeEnabled(enabled: Boolean) {
        experimentalSettingsStore.setMultiDeviceArbiterEnabled(enabled)
    }

    suspend fun saveWakeMode(mode: com.example.ava.settings.WakeMode) {
        microphoneSettingsStore.wakeMode.set(mode)
        VoiceSatelliteService.getInstance()?.applyWakeModeChange(mode)
    }

    suspend fun saveQuickWakeTrigger(trigger: QuickWakeTrigger) {
        microphoneSettingsStore.quickWakeTrigger.set(trigger)
    }

    suspend fun saveAudioEventDetectionEnabled(enabled: Boolean) {
        experimentalSettingsStore.setAudioEventDetectionEnabled(enabled)
        kotlinx.coroutines.delay(100)
        restartVoiceSatelliteServiceIfRunning()
    }

    suspend fun saveAudioEventMonitoredLabel(label: String, enabled: Boolean) {
        val current = experimentalSettingsStore.get().resolvedAudioEventMonitoredLabels()
        val next = AudioEventCatalog.withLabelToggled(current, label, enabled)
        if (next.isEmpty()) return
        experimentalSettingsStore.setAudioEventMonitoredLabels(next)
    }

    suspend fun saveAudioEventSensitivity(sensitivity: AudioEventSensitivity) {
        experimentalSettingsStore.setAudioEventSensitivity(sensitivity)
    }

    fun validateAudioEventDisplaySeconds(seconds: Int?): String? {
        if (seconds == null) return null
        return if (seconds < AudioEventDisplayDuration.MIN_SECONDS ||
            seconds > AudioEventDisplayDuration.MAX_SECONDS
        ) {
            application.getString(
                R.string.validation_range,
                AudioEventDisplayDuration.MIN_SECONDS,
                AudioEventDisplayDuration.MAX_SECONDS,
            )
        } else {
            null
        }
    }

    suspend fun saveAudioEventDisplaySeconds(seconds: Int?) {
        experimentalSettingsStore.setAudioEventDisplaySeconds(
            seconds ?: AudioEventDisplayDuration.DEFAULT_SECONDS,
        )
    }

    suspend fun saveOccupancyEnabled(enabled: Boolean) {
        experimentalSettingsStore.setOccupancyEnabled(enabled)
        kotlinx.coroutines.delay(100)
        // The occupancy module only registers its entity during satellite start().
        restartVoiceSatelliteServiceIfRunning(SatelliteRestartReason.SATELLITE_PIPELINE)
    }

    suspend fun saveOccupancyUseFace(enabled: Boolean) {
        experimentalSettingsStore.setOccupancyUseFace(enabled)
    }

    suspend fun saveOccupancyUseTouch(enabled: Boolean) {
        experimentalSettingsStore.setOccupancyUseTouch(enabled)
    }

    suspend fun saveOccupancyUseProximity(enabled: Boolean) {
        experimentalSettingsStore.setOccupancyUseProximity(enabled)
    }

    suspend fun saveOccupancyUseVoiceprint(enabled: Boolean) {
        experimentalSettingsStore.setOccupancyUseVoiceprint(enabled)
    }

    suspend fun saveOccupancyUseMotion(enabled: Boolean) {
        experimentalSettingsStore.setOccupancyUseMotion(enabled)
    }

    suspend fun saveOccupancyUseVibration(enabled: Boolean) {
        experimentalSettingsStore.setOccupancyUseVibration(enabled)
    }

    suspend fun saveOccupancyThresholdPercent(percent: Int) {
        experimentalSettingsStore.setOccupancyThresholdPercent(percent)
    }

    suspend fun saveOccupancyLeaveSeconds(seconds: Int) {
        experimentalSettingsStore.setOccupancyLeaveSeconds(seconds)
    }
    
    fun validateName(name: String): String? =
        if (name.isBlank())
            application.getString(R.string.validation_voice_satellite_name_empty)
        else null


    fun validatePort(port: Int?): String? =
        if (port == null || port < 1 || port > 65535)
            application.getString(R.string.validation_voice_satellite_port_invalid)
        else null

    fun validateEncryptionKey(key: String): String? =
        if (com.example.ava.server.noise.EspHomeNoisePsk.isValidOrEmpty(key)) null
        else application.getString(R.string.validation_esphome_encryption_key)

    fun validateWakeWord(wakeWordId: String, engine: WakeWordEngine = WakeWordEngine.MICRO_WAKE_WORD): String? {
        val wakeWordWithId = wakeWordsFor(engine).firstOrNull { it.id == wakeWordId }
        return if (wakeWordWithId == null)
            application.getString(R.string.validation_voice_satellite_wake_word_invalid)
        else
            null
    }

    suspend fun importWakeWordFile(uri: Uri): WakeWordImportResult {
        return when (wakeWordLibraryManager.classifyUri(uri)) {
            WakeWordFileKind.Zip -> importWakeWordZip(uri)
            WakeWordFileKind.Json -> importWakeWordJson(uri)
            WakeWordFileKind.Onnx -> importWakeWordJson(uri)
            else -> WakeWordImportResult.Failed("unsupported_file")
        }
    }

    suspend fun importWakeWordZip(uri: Uri): WakeWordImportResult {
        val result = wakeWordLibraryManager.importZipUri(uri)
        if (result is WakeWordImportResult.Complete) {
            bumpWakeWordList()
        }
        return result
    }

    suspend fun importWakeWordJson(uri: Uri): WakeWordImportResult {
        val result = wakeWordLibraryManager.importJsonUri(uri)
        if (result is WakeWordImportResult.Complete || result is WakeWordImportResult.NeedsModel) {
            bumpWakeWordList()
        }
        return result
    }

    suspend fun importWakeWordModel(uri: Uri, targetId: String, engine: WakeWordEngine): WakeWordImportResult {
        val result = wakeWordLibraryManager.importModelUri(uri, targetId, engine)
        if (result is WakeWordImportResult.Complete) {
            bumpWakeWordList()
        }
        return result
    }

    suspend fun deleteWakeWordLibraryEntry(id: String, engine: WakeWordEngine) {
        wakeWordLibraryManager.deleteEntry(id, engine)
        bumpWakeWordList()
    }
}
