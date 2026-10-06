package com.example.ava.webcompat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ava.settings.BrowserSettings
import com.example.ava.settings.ExperimentalSettings
import com.example.ava.settings.MicrophoneSettings
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.QuickEntitySettings
import com.example.ava.settings.SidebarSettings
import com.example.ava.settings.browserSettingsDataStore
import com.example.ava.settings.experimentalSettingsDataStore
import com.example.ava.settings.microphoneSettingsStore
import com.example.ava.settings.playerSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.settings.sidebarSettingsStore
import com.example.ava.settings.voiceChannelSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single app-wide queue for the six host sidebar JSON DataStores.
 *
 * Subscribes once via [combine], builds snapshots from in-memory values (no per-push
 * re-read of all six files), and serializes Gecko pack pushes behind one mutex.
 */
object HostSidebarSettingsSnapshotCoordinator {
    private const val TAG = "HostSidebarSnapshotCoord"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureMutex = Mutex()
    private val pushMutex = Mutex()

    @Volatile
    private var started = false

    @Volatile
    private var lastBundle: SettingsStoreBundle? = null

    @Volatile
    private var cachedSnapshot: HostSidebarSettingsSnapshot? = null

    @Volatile
    private var cachedEncodedJson: String? = null

    @Volatile
    private var lastSatelliteKey: String = ""

    fun peekEncodedJson(): String? = cachedEncodedJson

    fun peekSnapshot(): HostSidebarSettingsSnapshot? = cachedSnapshot

    fun start(context: Context) {
        if (EngineCapabilities.GECKO_BUNDLED) return
        if (started) return
        started = true
        val appContext = context.applicationContext

        scope.launch {
            combine(
                combine(
                    appContext.sidebarSettingsStore.data,
                    appContext.playerSettingsStore.data,
                    appContext.browserSettingsDataStore.data,
                    appContext.quickEntitySettingsStore.data,
                    appContext.experimentalSettingsDataStore.data,
                ) { sidebar, player, browser, quickEntity, experimental ->
                    SettingsStoreBundleCore(
                        sidebar = sidebar,
                        player = player,
                        browser = browser,
                        quickEntity = quickEntity,
                        experimental = experimental,
                    )
                },
                appContext.microphoneSettingsStore.data,
                appContext.voiceChannelSettingsStore.data,
            ) { core, microphone, voiceChannel ->
                SettingsStoreBundle(
                    sidebar = core.sidebar,
                    player = core.player,
                    browser = core.browser,
                    quickEntity = core.quickEntity,
                    experimental = core.experimental,
                    microphone = microphone,
                    voiceChannelEnabled = voiceChannel.enabled,
                )
            }
                .debounce(50)
                .collect { bundle ->
                    updateCache(appContext, bundle)
                    enqueuePush(appContext)
                }
        }

        scope.launch {
            while (true) {
                delay(3000)
                if (!BrowserEngine.isGeckoEnginePackInstalled(appContext)) continue
                val bundle = lastBundle ?: continue
                val refreshed = HostSidebarSettingsSnapshotCodec.buildSnapshot(appContext, bundle)
                val key = "${refreshed.satelliteStarted}:${refreshed.satelliteStatusText}"
                if (key == lastSatelliteKey) continue
                captureMutex.withLock {
                    cachedSnapshot = refreshed
                    cachedEncodedJson = HostSidebarSettingsSnapshotCodec.encode(refreshed)
                    lastSatelliteKey = key
                }
                enqueuePush(appContext)
            }
        }
    }

    fun requestPush(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            captureMutex.withLock {
                val bundle = lastBundle ?: readBundleOnce(appContext)
                updateCacheLocked(appContext, bundle)
            }
            enqueuePush(appContext)
        }
    }

    suspend fun capture(context: Context): HostSidebarSettingsSnapshot {
        peekSnapshot()?.let { return it }
        return captureMutex.withLock {
            peekSnapshot() ?: run {
                val appContext = context.applicationContext
                val bundle = lastBundle ?: readBundleOnce(appContext)
                updateCacheLocked(appContext, bundle)
                requireNotNull(cachedSnapshot)
            }
        }
    }

    private suspend fun readBundleOnce(appContext: Context): SettingsStoreBundle {
        return combine(
            combine(
                appContext.sidebarSettingsStore.data,
                appContext.playerSettingsStore.data,
                appContext.browserSettingsDataStore.data,
                appContext.quickEntitySettingsStore.data,
                appContext.experimentalSettingsDataStore.data,
            ) { sidebar, player, browser, quickEntity, experimental ->
                SettingsStoreBundleCore(
                    sidebar = sidebar,
                    player = player,
                    browser = browser,
                    quickEntity = quickEntity,
                    experimental = experimental,
                )
            },
            appContext.microphoneSettingsStore.data,
            appContext.voiceChannelSettingsStore.data,
        ) { core, microphone, voiceChannel ->
            SettingsStoreBundle(
                sidebar = core.sidebar,
                player = core.player,
                browser = core.browser,
                quickEntity = core.quickEntity,
                experimental = core.experimental,
                microphone = microphone,
                voiceChannelEnabled = voiceChannel.enabled,
            )
        }.first()
    }

    private suspend fun updateCache(appContext: Context, bundle: SettingsStoreBundle) {
        captureMutex.withLock {
            updateCacheLocked(appContext, bundle)
        }
    }

    private suspend fun updateCacheLocked(appContext: Context, bundle: SettingsStoreBundle) {
        lastBundle = bundle
        val snapshot = HostSidebarSettingsSnapshotCodec.buildSnapshot(appContext, bundle)
        cachedSnapshot = snapshot
        cachedEncodedJson = HostSidebarSettingsSnapshotCodec.encode(snapshot)
        lastSatelliteKey = "${snapshot.satelliteStarted}:${snapshot.satelliteStatusText}"
    }

    private fun enqueuePush(appContext: Context) {
        scope.launch {
            pushMutex.withLock {
                if (!BrowserEngine.isGeckoEnginePackInstalled(appContext)) return@withLock
                val settleDelay = BrowserEngine.geckoPackageSettleDelayMs(appContext)
                if (settleDelay > 0L) {
                    Log.i(TAG, "Deferring gecko settings push for ${settleDelay}ms after package update")
                    delay(settleDelay)
                    if (!BrowserEngine.isGeckoEnginePackInstalled(appContext)) return@withLock
                }
                val payload = cachedEncodedJson ?: return@withLock
                try {
                    appContext.sendBroadcast(
                        Intent(HostSidebarSettingsContract.ACTION_PUSH_SIDEBAR_SETTINGS).apply {
                            component = ComponentName(
                                BrowserEngine.GECKO_ENGINE_PACKAGE,
                                HostSidebarSettingsContract.RECEIVER_CLASS
                            )
                            putExtra(
                                HostSidebarSettingsContract.EXTRA_SIDEBAR_SETTINGS_JSON,
                                payload
                            )
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to push sidebar settings to gecko pack", e)
                }
            }
        }
    }
}

data class SettingsStoreBundle(
    val sidebar: SidebarSettings,
    val player: PlayerSettings,
    val browser: BrowserSettings,
    val quickEntity: QuickEntitySettings,
    val experimental: ExperimentalSettings,
    val microphone: MicrophoneSettings,
    val voiceChannelEnabled: Boolean,
)

private data class SettingsStoreBundleCore(
    val sidebar: SidebarSettings,
    val player: PlayerSettings,
    val browser: BrowserSettings,
    val quickEntity: QuickEntitySettings,
    val experimental: ExperimentalSettings,
)
