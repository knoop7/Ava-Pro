package com.example.ava.localllm

import android.content.Context
import android.util.Log
import com.example.ava.R
import com.example.ava.homeassistant.HaManager
import com.example.ava.homeassistant.HaWsClient
import com.example.ava.settings.LocalLlmPath
import com.example.ava.settings.LocalLlmSettingsStore
import com.example.ava.settings.localLlmSettingsStore
import com.example.ava.settings.resolvedPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Host for remote-AI tool grounding: name index + HA service execution.
 * On-device Needle / LFM inference was removed.
 */
class LocalLlmManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore = LocalLlmSettingsStore(appContext.localLlmSettingsStore)

    private val indexLock = Mutex()
    private var deviceIndex = DeviceIndex()

    init {
        scope.launch { settingsStore.clearLegacyLocalEngine() }
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        scope.launch {
            combine(settingsStore.getFlow(), HaManager.instanceFlow) { set, ha ->
                set.resolvedPath() to ha
            }.flatMapLatest { (path, ha) ->
                if (path != LocalLlmPath.REMOTE || ha == null) {
                    flowOf(null)
                } else {
                    ha.connectionState.map { state ->
                        if (state is HaWsClient.ConnectionState.Connected) ha else null
                    }
                }
            }.distinctUntilChanged { a, b -> a == b }.collectLatest { ha ->
                if (ha == null) {
                    indexLock.withLock { deviceIndex = DeviceIndex() }
                } else {
                    bindIndex(ha)
                }
            }
        }
    }

    fun deviceIndexSnapshot(): DeviceIndex = deviceIndex

    suspend fun refreshTools(force: Boolean = false) {
        val ha = HaManager.get() ?: return
        if (ha.connectionState.value !is HaWsClient.ConnectionState.Connected) return
        bindIndex(ha)
    }

    private suspend fun bindIndex(ha: HaManager) {
        val interest = LocalToolEntities.loadInterest(appContext)
        val known = ha.entities.value.associateBy { it.entityId }
        val index = DeviceIndex()
        index.addAll(interest.map { known[it.entityId] ?: it })
        val ids = ha.fetchAssistExposedEntityIds()
            ?.filterNot { com.example.ava.localllm.remote.AvaPublishedEntities.isOwnEntityId(it) }
        if (ids != null) index.addAll(DeviceIndex.fromExposedIds(ids, known))
        if (index.size > 0) {
            val names = runCatching { ha.fetchAssistNames(index.values.map { it.entityId }) }
                .onFailure { Log.w(TAG, "registry names unavailable", it) }
                .getOrDefault(emptyMap())
            for ((id, n) in names) index.addNames(id, n)
        }
        indexLock.withLock { deviceIndex = index }
        Log.i(TAG, "index ${index.size} entities")
    }

    suspend fun execute(
        plan: LocalIntentPlan,
        caller: suspend (service: String, entityId: String, data: Map<String, Any?>) -> Boolean?,
    ): String {
        val failed = ArrayList<String>()
        for (action in plan.actions) {
            val ok = runCatching {
                if (action.service == HaToolSet.SERVICE_SEARCH_AND_PLAY) {
                    HaMediaPlay.play(
                        query = action.data["query"]?.toString().orEmpty(),
                        entity = action.entity,
                        artist = action.data["artist"]?.toString().orEmpty(),
                        mediaClass = action.data["media_class"]?.toString().orEmpty(),
                        caller = caller,
                    )
                } else {
                    caller(action.service, action.entity.entityId, action.data)
                }
            }
                .onFailure { Log.w(TAG, "service ${action.service} failed", it) }
                .getOrDefault(false)
            if (ok == false) {
                failed += action.entity.name.ifBlank {
                    action.data["query"]?.toString()?.trim().orEmpty()
                        .ifEmpty { action.entity.entityId }
                }
            }
        }
        val parts = ArrayList<String>()
        if (plan.queries.isNotEmpty()) {
            val fresh = HaManager.get()?.entities?.value?.associateBy { it.entityId }.orEmpty()
            for (q in plan.queries) {
                val state = fresh[q.entityId]?.state ?: q.state
                parts += appContext.getString(R.string.local_llm_reply_state, q.name, state)
            }
        }
        if (failed.isNotEmpty()) {
            parts.add(0, appContext.getString(R.string.local_llm_reply_failed, failed.distinct().joinToString(", ")))
        } else if (plan.actions.isNotEmpty()) {
            parts.add(0, appContext.getString(R.string.local_llm_reply_done))
        }
        return parts.joinToString(" ")
    }

    companion object {
        private const val TAG = "LocalLlmManager"

        @Volatile
        private var instance: LocalLlmManager? = null

        fun getInstance(context: Context): LocalLlmManager =
            instance ?: synchronized(this) {
                instance ?: LocalLlmManager(context).also { instance = it }
            }

        fun get(): LocalLlmManager? = instance
    }
}
