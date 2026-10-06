package com.example.ava.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ava.notifications.FullscreenOverlayEscape

/**
 * Explicit, non-exported command for the overlay-escape notification.
 * Not gated by ADB control — this is the shade exit when a fullscreen overlay traps input.
 */
class FullscreenOverlayEscapeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra(FullscreenOverlayEscape.EXTRA_KIND)
        val kind = raw?.let { name ->
            runCatching { FullscreenOverlayEscape.Kind.valueOf(name) }.getOrNull()
        }
        when (intent.action) {
            FullscreenOverlayEscape.ACTION_EXIT -> FullscreenOverlayEscape.exit(context, kind)
            FullscreenOverlayEscape.ACTION_RELOAD -> FullscreenOverlayEscape.reload(context, kind)
        }
    }
}
