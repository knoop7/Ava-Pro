package com.example.ava.receivers

import android.content.Context
import android.util.Log
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.ava.webcompat.BrowserEngine
import com.example.ava.webcompat.HostSidebarSettingsContract

/**
 * Gates inbound ADB / local-broadcast control of Ava.
 * Gecko host-bridge actions stay available when the switch is off.
 */
object AvaControlGate {
    private const val TAG = "AvaControlGate"

    fun isAdbControlEnabled(context: Context): Boolean {
        return runCatching {
            ExperimentalSettingsStore(context.applicationContext).getCached().adbControlEnabled
        }.getOrElse {
            Log.w(TAG, "ADB control setting unread, allowing", it)
            true
        }
    }

    fun shouldHandle(context: Context, action: String): Boolean {
        if (!isGatedAction(action)) return true
        val enabled = isAdbControlEnabled(context)
        if (!enabled) {
            Log.i(TAG, "Ignored $action: ADB control is off")
        }
        return enabled
    }

    private fun isGatedAction(action: String): Boolean {
        return when (action) {
            BrowserEngine.ACTION_SYNC_BROWSER_VISIBLE,
            BrowserEngine.ACTION_REASSERT_FOREGROUND_OVERLAYS,
            AvaControlReceiver.ACTION_PRY_GECKO_CHILD,
            HostSidebarSettingsContract.ACTION_REQUEST_SIDEBAR_SETTINGS,
            HostSidebarSettingsContract.ACTION_SIDEBAR_COMMAND -> false
            else -> true
        }
    }
}
