package com.example.ava.sensors

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

class DiagnosticSensorManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DiagnosticSensorManager"
        private const val UPDATE_INTERVAL_MS = 35_000L
        private const val UPTIME_INTERVAL_MS = 300_000L
        private const val SMOOTHING_SAMPLES = 3
        private const val UNAVAILABLE = "unavailable"

        fun hasUsageStatsPermission(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun openUsageAccessSettings(context: Context) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "Unable to open usage access settings", e)
            }
        }
    }
    
    private val _wifiSignal = MutableStateFlow(0)
    val wifiSignal: StateFlow<Int> = _wifiSignal
    
    private val _deviceIp = MutableStateFlow("")
    val deviceIp: StateFlow<String> = _deviceIp
    
    private val _storageFree = MutableStateFlow(0f)
    val storageFree: StateFlow<Float> = _storageFree
    
    private val _memoryUsage = MutableStateFlow(0f)
    val memoryUsage: StateFlow<Float> = _memoryUsage
    
    private val _uptime = MutableStateFlow("")
    val uptime: StateFlow<String> = _uptime
    
    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel
    
    private val _batteryVoltage = MutableStateFlow(0f)
    val batteryVoltage: StateFlow<Float> = _batteryVoltage
    
    private val _chargingStatus = MutableStateFlow("None")
    val chargingStatus: StateFlow<String> = _chargingStatus

    private val _musicActive = MutableStateFlow(false)
    val musicActive: StateFlow<Boolean> = _musicActive

    private val _lastUsedApp = MutableStateFlow(UNAVAILABLE)
    val lastUsedApp: StateFlow<String> = _lastUsedApp

    private val _bluetoothOn = MutableStateFlow(false)
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn

    private val _networkType = MutableStateFlow("none")
    val networkType: StateFlow<String> = _networkType
    
    private var updateJob: Job? = null
    private val wifiSamples = mutableListOf<Int>()
    private val memorySamples = mutableListOf<Float>()
    
    private var uptimeJob: Job? = null
    
    fun start() {
        updateJob?.cancel()
        uptimeJob?.cancel()
        
        updateAllSensors()
        updateUptime()
        
        updateJob = scope.launch {
            while (isActive) {
                delay(UPDATE_INTERVAL_MS)
                updateAllSensors()
            }
        }
        
        uptimeJob = scope.launch {
            while (isActive) {
                delay(UPTIME_INTERVAL_MS)
                updateUptime()
            }
        }
    }
    
    fun stop() {
        updateJob?.cancel()
        uptimeJob?.cancel()
        updateJob = null
        uptimeJob = null
    }
    
    private fun updateAllSensors() {
        updateWifiSignal()
        updateDeviceIp()
        updateStorageFree()
        updateMemoryUsage()
        updateBattery()
        refreshLiveSensors()
    }

    fun refreshLiveSensors() {
        updateMusicActive()
        updateLastUsedApp()
        updateBluetoothState()
        updateNetworkType()
    }
    
    private fun updateBattery() {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return
        
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level >= 0) {
            _batteryLevel.value = level
        }
        
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let {
            val voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (voltage > 0) {
                _batteryVoltage.value = voltage / 1000f
            }
            
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            _chargingStatus.value = when {
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL -> {
                    when (plugged) {
                        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                        else -> "Charging"
                    }
                }
                else -> "None"
            }
        }
    }
    
    private fun updateWifiSignal() {
        try {
            var rssi = -100
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    rssi = capabilities.signalStrength
                    if (rssi == Int.MIN_VALUE) rssi = -100
                }
            }
            
            if (rssi == -100) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val wifiInfo = wifiManager?.connectionInfo
                rssi = wifiInfo?.rssi ?: -100
            }
            
            wifiSamples.add(rssi)
            if (wifiSamples.size > SMOOTHING_SAMPLES) {
                wifiSamples.removeAt(0)
            }
            _wifiSignal.value = wifiSamples.average().toInt()
        } catch (e: Exception) {
            _wifiSignal.value = -100
        }
    }
    
    private fun updateDeviceIp() {
        try {
            val ip = getLocalIpAddress()
            _deviceIp.value = ip ?: "Unknown"
        } catch (e: Exception) {
            _deviceIp.value = "Unknown"
        }
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val isWifiOrEthernet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            } else {
                @Suppress("DEPRECATION")
                val type = connectivityManager.activeNetworkInfo?.type
                @Suppress("DEPRECATION")
                (type == ConnectivityManager.TYPE_WIFI || type == ConnectivityManager.TYPE_ETHERNET)
            }

            if (isWifiOrEthernet) {
                
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            return address.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            
        }
        return null
    }
    
    private fun updateStorageFree() {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val availableGb = availableBytes / (1024f * 1024f * 1024f)
            _storageFree.value = (availableGb * 10).toInt() / 10f
        } catch (e: Exception) {
            _storageFree.value = 0f
        }
    }
    
    private fun updateMemoryUsage() {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            
            val usedMemory = memInfo.totalMem - memInfo.availMem
            val usedGb = usedMemory / (1024f * 1024f * 1024f)
            
            memorySamples.add(usedGb)
            if (memorySamples.size > SMOOTHING_SAMPLES) {
                memorySamples.removeAt(0)
            }
            _memoryUsage.value = (memorySamples.average() * 10).toInt() / 10f
        } catch (e: Exception) {
            _memoryUsage.value = 0f
        }
    }
    
    private fun updateMusicActive() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            _musicActive.value = audioManager?.isMusicActive == true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read music active state", e)
            _musicActive.value = false
        }
    }

    private fun updateLastUsedApp() {
        if (!hasUsageStatsPermission(context)) {
            _lastUsedApp.value = UNAVAILABLE
            return
        }
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usageStatsManager == null) {
                _lastUsedApp.value = UNAVAILABLE
                return
            }
            val now = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                now - 24 * 60 * 60 * 1000L,
                now,
            )
            val last = stats?.maxByOrNull { it.lastTimeUsed }
            _lastUsedApp.value = last?.packageName?.takeIf { it.isNotBlank() } ?: UNAVAILABLE
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read last used app", e)
            _lastUsedApp.value = UNAVAILABLE
        }
    }

    private fun updateBluetoothState() {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            _bluetoothOn.value = bluetoothManager?.adapter?.isEnabled == true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read bluetooth state", e)
            _bluetoothOn.value = false
        }
    }

    @Suppress("DEPRECATION")
    private fun updateNetworkType() {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                _networkType.value = "none"
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                _networkType.value = when {
                    capabilities == null -> "none"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                    else -> "none"
                }
            } else {
                val info = connectivityManager.activeNetworkInfo
                _networkType.value = when {
                    info == null || !info.isConnected -> "none"
                    info.type == ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                    info.type == ConnectivityManager.TYPE_WIFI -> "wifi"
                    info.type == ConnectivityManager.TYPE_MOBILE -> "cellular"
                    info.type == ConnectivityManager.TYPE_VPN -> "vpn"
                    info.type == ConnectivityManager.TYPE_BLUETOOTH -> "bluetooth"
                    else -> "none"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read network type", e)
            _networkType.value = "none"
        }
    }

    private fun updateUptime() {
        val uptimeMs = SystemClock.elapsedRealtime()
        val totalSeconds = uptimeMs / 1000
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        
        _uptime.value = String.format("%d:%02d:%02d", days, hours, minutes)
    }
    
}
