package com.example.ava.touchpad

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.ava.R
import com.example.ava.services.SatelliteRestartReason
import com.example.ava.services.VoiceSatelliteService

/** Home Assistant select for recorded takes. Play is headless — no pad window. */
internal object TouchPadHa {
    const val OBJECT_ID = "touch_pad_take"

    fun idleLabel(context: Context): String =
        context.getString(R.string.entity_touch_pad_take_idle)

    fun takeLabel(context: Context, index: Int): String =
        context.getString(R.string.touch_pad_auto_slot, index + 1)

    fun filledIndexes(library: String): List<Int> {
        val takes = TouchPadAutoStore.decode(library).first
        return takes.indices.filter { takes[it]?.hasContent() == true }
    }

    fun options(context: Context): List<String> {
        return listOf(idleLabel(context)) +
            (0 until TouchPadAuto.MAX_TAKES).map { takeLabel(context, it) }
    }

    fun play(context: Context, option: String) {
        val index = (0 until TouchPadAuto.MAX_TAKES)
            .firstOrNull { takeLabel(context, it) == option }
            ?: return
        val run = Runnable { TouchPadOverlay.playTakeHeadless(index) }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run()
        else Handler(Looper.getMainLooper()).post(run)
    }

    fun notifyLibraryChanged(context: Context) {
        if (!TouchPadPrefs(context).haSelectEnabled) return
        VoiceSatelliteService.getInstance()
            ?.restartVoiceSatellite(SatelliteRestartReason.SETTINGS)
    }
}
