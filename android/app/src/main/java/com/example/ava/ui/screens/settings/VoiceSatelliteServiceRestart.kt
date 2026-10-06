package com.example.ava.ui.screens.settings

import android.util.Log
import com.example.ava.R
import com.example.ava.services.SatelliteRestartReason
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.ui.AvaToast

private const val TAG = "VoiceSatelliteRestart"

/**
 * After a settings change that needs HA to rediscover entities: if the main
 * voice service is already running, rebuild the entity snapshot and kick the
 * HA client. Does **not** tear Sendspin / the music protocol.
 *
 * Never start the service from here. If it is not running the user is editing
 * during a cold start and the change applies on the next manual start.
 */
internal fun restartVoiceSatelliteServiceIfRunning(
    reason: SatelliteRestartReason = SatelliteRestartReason.SETTINGS,
): Boolean {
    val service = VoiceSatelliteService.getInstance()
    if (service == null) {
        Log.d(TAG, "Skip HA rediscover because VoiceSatelliteService is not running")
        return false
    }
    service.restartVoiceSatellite(reason)
    AvaToast.show(service, R.string.toast_syncing_to_home_assistant, tag = AvaToast.HA_SYNC_TAG)
    return true
}
