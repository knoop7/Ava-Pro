package com.example.ava.webcompat

import android.content.Context

object HostSidebarSettingsPublisher {
    fun start(context: Context) {
        HostSidebarSettingsSnapshotCoordinator.start(context)
    }
}
