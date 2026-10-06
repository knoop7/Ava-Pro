package com.example.esphomeproto.api

enum class BluetoothProxyFeature(val flag: Int) {
    PASSIVE_SCAN(1 shl 0),           // 0x01 - 被动扫描
    ACTIVE_CONNECTIONS(1 shl 1),     // 0x02 - 主动GATT连接
    REMOTE_CACHING(1 shl 2),         // 0x04 - 远程缓存
    PAIRING(1 shl 3),                // 0x08 - 配对支持
    CACHE_CLEARING(1 shl 4),         // 0x10 - 缓存清除
    RAW_ADVERTISEMENTS(1 shl 5),     // 0x20 - 原始广播数据
    FEATURE_STATE_AND_MODE(1 shl 6), // 0x40 - 支持扫描状态和模式切换
    CONNECTION_PARAMS_SETTING(1 shl 7); // 0x80 - 连接参数设置
    
    companion object {
        // 完整功能集: 0xFF = 所有功能
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
