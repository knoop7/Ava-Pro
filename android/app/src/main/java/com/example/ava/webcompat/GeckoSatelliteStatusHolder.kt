package com.example.ava.webcompat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GeckoSatelliteStatusHolder {
    private val _started = MutableStateFlow(false)
    private val _statusText = MutableStateFlow("")

    val started: StateFlow<Boolean> = _started.asStateFlow()
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    fun applySnapshot(snapshot: HostSidebarSettingsSnapshot) {
        _started.value = snapshot.satelliteStarted
        _statusText.value = snapshot.satelliteStatusText
    }

    fun isStarted(): Boolean = _started.value
}
