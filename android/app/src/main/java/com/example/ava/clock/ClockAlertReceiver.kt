package com.example.ava.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ava.services.ClockAlertOverlayService

class ClockAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ClockAlertScheduler.ACTION_FIRE) return
        val id = intent.getStringExtra(ClockAlertScheduler.EXTRA_ID)?.trim().orEmpty()
        if (id.isEmpty()) return
        ClockAlertOverlayService.ring(context, id)
    }
}
