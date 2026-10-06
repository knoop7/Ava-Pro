package com.example.ava.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Guards access to credential-encrypted storage (SharedPreferences, DataStore, etc.)
 * during Direct Boot before the user unlocks the device.
 */
object DirectBootHelper {
    fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val userManager = context.getSystemService(UserManager::class.java) ?: return true
        return userManager.isUserUnlocked
    }

    /**
     * Runs [block] immediately when CE storage is available, otherwise once after
     * [Intent.ACTION_USER_UNLOCKED].
     */
    fun runWhenUserUnlocked(context: Context, scope: CoroutineScope, block: suspend () -> Unit) {
        if (isUserUnlocked(context)) {
            scope.launch { block() }
            return
        }

        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent?) {
                if (intent?.action != Intent.ACTION_USER_UNLOCKED) return
                if (!isUserUnlocked(ctx)) return
                runCatching { appContext.unregisterReceiver(this) }
                scope.launch { block() }
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }
}
