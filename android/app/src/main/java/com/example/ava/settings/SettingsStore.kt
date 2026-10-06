package com.example.ava.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

interface SettingsStore<T> {
    fun getFlow(): Flow<T>
    suspend fun get(): T
    suspend fun update(transform: suspend (T) -> T)
}

abstract class SettingsStoreImpl<T>(val dataStore: DataStore<T>, private val default: T) :
    SettingsStore<T> {
    override fun getFlow() = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading settings, returning defaults", exception)
                emit(default)
            } else throw exception
        }

    override suspend fun get(): T = getFlow().first()

    /**
     * Latest known settings snapshot for hot paths (main/audio threads, high-frequency
     * callbacks). Unlike [get] via `runBlocking`, this reads a memory snapshot kept fresh
     * by a single background collector per underlying [DataStore]; only the very first
     * access per process seeds the snapshot with one blocking IO read.
     */
    fun getCached(): T {
        if (hotCollectorsStarted.add(dataStore)) {
            hotSnapshotScope.launch {
                getFlow().collect { hotSnapshots[dataStore] = it as Any }
            }
        }
        @Suppress("UNCHECKED_CAST")
        (hotSnapshots[dataStore] as? T)?.let { return it }
        return try {
            runBlocking(Dispatchers.IO) { get() }.also {
                hotSnapshots.putIfAbsent(dataStore, it as Any)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error seeding settings snapshot, returning defaults", e)
            default
        }
    }

    override suspend fun update(transform: suspend (T) -> T) {
        dataStore.updateData(transform)
    }

    companion object {
        private const val TAG = "SettingsStore"

        // DataStore instances are process-wide singletons (property delegates), so keying
        // by them bounds this to one collector per settings file for the app's lifetime.
        private val hotSnapshots = ConcurrentHashMap<DataStore<*>, Any>()
        private val hotCollectorsStarted = ConcurrentHashMap.newKeySet<DataStore<*>>()
        private val hotSnapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}