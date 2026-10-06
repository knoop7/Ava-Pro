package com.example.ava.utils

import android.os.Build
import android.util.Log
import java.io.File

object DeviceFeatureManager {
    
    private const val TAG = "DeviceFeatureManager"
    
    private var _deviceType: DeviceType? = null
    
    enum class DeviceType {
        A64,
        SAMSUNG_S10_LITE,
        GENERIC
    }
    
    fun getDeviceType(): DeviceType {
        if (_deviceType != null) return _deviceType!!
        
        _deviceType = detectDeviceType()
        return _deviceType!!
    }
    
    private fun detectDeviceType(): DeviceType {
        val modelLower = (Build.MODEL ?: "").lowercase()
        val deviceLower = (Build.DEVICE ?: "").lowercase()
        val manufacturerLower = (Build.MANUFACTURER ?: "").lowercase()

        val isSamsungS10Lite = manufacturerLower == "samsung" && (
            modelLower.startsWith("sm-g770") ||
            modelLower.contains("s10 lite") ||
            deviceLower == "r1q"
        )
        if (isSamsungS10Lite) {
            Log.i(TAG, "Detected Samsung Galaxy S10 Lite ($modelLower / $deviceLower)")
            return DeviceType.SAMSUNG_S10_LITE
        }

        try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            val cpuInfoLower = cpuInfo.lowercase()
            val boardLower = (Build.BOARD ?: "").lowercase()
            val hardwareLower = (Build.HARDWARE ?: "").lowercase()
            
            val isA64 = cpuInfoLower.contains("a64") ||
                cpuInfoLower.contains("sun50i") ||
                cpuInfoLower.contains("allwinner") ||
                modelLower.contains("a64") ||
                modelLower.contains("ococci") ||
                boardLower.contains("a64") ||
                boardLower.contains("sun50i") ||
                hardwareLower.contains("a64") ||
                hardwareLower.contains("sun50i") ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT <= Build.VERSION_CODES.O)
            
            if (isA64) return DeviceType.A64
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect device type", e)
        }
        
        return DeviceType.GENERIC
    }
    
    fun isA64Device(): Boolean = getDeviceType() == DeviceType.A64

    fun isSamsungS10LiteDevice(): Boolean = getDeviceType() == DeviceType.SAMSUNG_S10_LITE
    
    fun shouldShowScreensaverSettings(): Boolean {
        return when (getDeviceType()) {
            DeviceType.A64 -> false
            DeviceType.SAMSUNG_S10_LITE, DeviceType.GENERIC -> true
        }
    }
    
    fun shouldShowBrowserSettings(): Boolean {
        return true
    }
    
    fun shouldShowExperimentalSettings(): Boolean {
        return true
    }
    
    fun shouldShowCameraSettings(): Boolean {
        return when (getDeviceType()) {
            DeviceType.A64 -> false
            DeviceType.SAMSUNG_S10_LITE, DeviceType.GENERIC -> true
        }
    }
    
    fun clearCache() {
        _deviceType = null
    }
}
