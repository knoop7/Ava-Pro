package com.example.esphomeproto.api

enum class BluetoothProxyFeature(val flag: Int) {
    PASSIVE_SCAN(1 shl 0),           // 0x01 - passive scan
    ACTIVE_CONNECTIONS(1 shl 1),     // 0x02 - active GATT connections
    REMOTE_CACHING(1 shl 2),         // 0x04 - remote cache
    PAIRING(1 shl 3),                // 0x08 - pairing support
    CACHE_CLEARING(1 shl 4),         // 0x10 - cache clearing
    RAW_ADVERTISEMENTS(1 shl 5),     // 0x20 - raw advertisement data
    FEATURE_STATE_AND_MODE(1 shl 6), // 0x40 - scan state and mode switching
    CONNECTION_PARAMS_SETTING(1 shl 7); // 0x80 - connection parameter setting
    
    companion object {
        // Full feature set: 0xFF = every feature
        val ALL = PASSIVE_SCAN.flag or 
                  ACTIVE_CONNECTIONS.flag or 
                  REMOTE_CACHING.flag or 
                  PAIRING.flag or 
                  CACHE_CLEARING.flag or 
                  RAW_ADVERTISEMENTS.flag or 
                  FEATURE_STATE_AND_MODE.flag or
                  CONNECTION_PARAMS_SETTING.flag
    }
}
