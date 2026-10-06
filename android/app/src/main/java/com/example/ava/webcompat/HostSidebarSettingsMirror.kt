package com.example.ava.webcompat

import android.content.Context
import android.util.Log
import com.example.ava.webcompat.HostSidebarSettingsContract.COLUMN_JSON
import com.example.ava.webcompat.HostSidebarSettingsContract.SNAPSHOT_URI

object HostSidebarSettingsMirror {
    private const val TAG = "HostSidebarSettingsMirror"

    suspend fun pullFromHost(context: Context): Boolean {
        if (!EngineCapabilities.GECKO_BUNDLED) return true
        val appContext = context.applicationContext
        val cursor = appContext.contentResolver.query(
            HostSidebarSettingsContract.SNAPSHOT_URI,
            null,
            null,
            null,
            null
        ) ?: return false
        return try {
            if (!cursor.moveToFirst()) return false
            val index = cursor.getColumnIndex(HostSidebarSettingsContract.COLUMN_JSON)
            if (index < 0) return false
            val raw = cursor.getString(index) ?: return false
            val snapshot = HostSidebarSettingsSnapshotCodec.decode(raw) ?: return false
            HostSidebarSettingsSnapshotCodec.apply(appContext, snapshot)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull sidebar settings from host", e)
            false
        } finally {
            cursor.close()
        }
    }
}
