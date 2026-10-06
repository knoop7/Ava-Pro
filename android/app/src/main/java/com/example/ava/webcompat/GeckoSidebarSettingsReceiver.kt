package com.example.ava.webcompat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GeckoSidebarSettingsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "GeckoSidebarSettingsRx"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HostSidebarSettingsContract.ACTION_PUSH_SIDEBAR_SETTINGS) return
        val raw = intent.getStringExtra(HostSidebarSettingsContract.EXTRA_SIDEBAR_SETTINGS_JSON)
            ?: return
        val snapshot = HostSidebarSettingsSnapshotCodec.decode(raw) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                HostSidebarSettingsSnapshotCodec.apply(context.applicationContext, snapshot)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply sidebar settings push", e)
            } finally {
                pending.finish()
            }
        }
    }
}
