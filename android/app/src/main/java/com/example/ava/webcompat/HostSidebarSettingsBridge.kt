package com.example.ava.webcompat

import android.content.Context
import android.content.Intent
import android.util.Log

object HostSidebarSettingsBridge {
    private const val TAG = "HostSidebarSettingsBridge"

    fun pushToGeckoPack(context: Context) {
        if (EngineCapabilities.GECKO_BUNDLED) return
        HostSidebarSettingsSnapshotCoordinator.requestPush(context.applicationContext)
    }

    fun requestFromHost(context: Context) {
        if (!EngineCapabilities.GECKO_BUNDLED) return
        try {
            context.sendBroadcast(
                Intent(HostSidebarSettingsContract.ACTION_REQUEST_SIDEBAR_SETTINGS).apply {
                    setPackage(BrowserEngine.HOST_PACKAGE)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request sidebar settings from host", e)
        }
    }
}
