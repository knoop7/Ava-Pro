package com.example.ava.webcompat

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object HostSidebarCommandBridge {
    private const val TAG = "HostSidebarCommandBridge"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun send(command: String, payload: String? = null) {
        if (!EngineCapabilities.GECKO_BUNDLED) return
        val context = appContext ?: return
        try {
            context.sendBroadcast(
                Intent(HostSidebarSettingsContract.ACTION_SIDEBAR_COMMAND).apply {
                    setPackage(BrowserEngine.HOST_PACKAGE)
                    putExtra(HostSidebarSettingsContract.EXTRA_SIDEBAR_COMMAND, command)
                    if (payload != null) {
                        putExtra(HostSidebarSettingsContract.EXTRA_SIDEBAR_COMMAND_PAYLOAD, payload)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send sidebar command=$command", e)
            return
        }
        scope.launch {
            delay(150)
            HostSidebarSettingsMirror.pullFromHost(context)
        }
    }
}
