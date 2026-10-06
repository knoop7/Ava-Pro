package com.example.ava.mods

/** Standalone vs legacy integrated behaviour for ble-adv-proxy mods (default standalone). */
fun ModManifest.usesStandaloneBleAdv(): Boolean {
    if (!bleAdvProxy) return false
    return bleAdvStandalone ?: true
}
