package com.example.ava.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri


class DeviceAdminReceiver : DeviceAdminReceiver() {
    
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }
    
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }

    /**
     * API 29+: device/profile owner intercepts [android.security.KeyChain] picks.
     * Returning null still shows the system chooser. Do not return
     * [android.security.KeyChain.KEYCHAIN_DISABLE_ALIAS] — that swallows the UI.
     */
    override fun onChoosePrivateKeyAlias(
        context: Context,
        intent: Intent,
        uid: Int,
        uri: Uri?,
        alias: String?,
    ): String? = null
}
