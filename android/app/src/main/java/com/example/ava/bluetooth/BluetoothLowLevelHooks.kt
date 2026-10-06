package com.example.ava.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.example.ava.mods.ModManager
import java.lang.reflect.Method

object BluetoothLowLevelHooks {
    private const val TAG = "BtLowLevelHooks"
    
    interface BluetoothHookProvider {
        fun isSupported(context: Context): Boolean
        fun getChipVendor(context: Context): String?
        fun getChipModel(context: Context): String?
        fun getMaxRealConnections(context: Context): Int?
        fun getScannerQuirks(context: Context): Set<ScannerQuirk>
        fun applyChipSpecificFix(context: Context, fixType: ChipFix): Boolean
        fun interceptGattOperation(operation: GattOperation): GattOperationResult?
    }
    
    enum class ScannerQuirk {
        NEEDS_LOCATION_ENABLED,
        SCAN_FILTER_BROKEN,
        BATCH_SCAN_BROKEN,
        PASSIVE_SCAN_BROKEN,
        SCAN_STALLS_AFTER_MINUTES,
        NEEDS_PERIODIC_RESTART,
        LOW_RSSI_ACCURACY
    }
    
    enum class ChipFix {
        CLEAR_GATT_CACHE,
        RESET_SCAN_STATE,
        FORCE_LE_ONLY_MODE,
        DISABLE_POWER_SAVE,
        INCREASE_SCAN_WINDOW,
        BYPASS_SCAN_THROTTLE
    }
    
    sealed class GattOperation {
        data class Connect(val address: String, val addressType: Int) : GattOperation()
        data class Disconnect(val address: String) : GattOperation()
        data class DiscoverServices(val address: String) : GattOperation()
        data class ReadCharacteristic(val address: String, val handle: Int) : GattOperation()
        data class WriteCharacteristic(val address: String, val handle: Int, val data: ByteArray) : GattOperation()
        data class SetNotify(val address: String, val handle: Int, val enable: Boolean) : GattOperation()
    }
    
    sealed class GattOperationResult {
        object Proceed : GattOperationResult()
        object Block : GattOperationResult()
        data class Modify(val modifiedOperation: GattOperation) : GattOperationResult()
        data class Delay(val delayMs: Long) : GattOperationResult()
    }
    
    data class ChipInfo(
        val vendor: String,
        val model: String,
        val bleVersion: Int,
        val maxConnections: Int,
        val quirks: Set<ScannerQuirk>
    )
    
    private var hookProvider: BluetoothHookProvider? = null
    private var cachedChipInfo: ChipInfo? = null
    
    fun registerHookProvider(provider: BluetoothHookProvider, context: Context) {
        if (provider.isSupported(context)) {
            hookProvider = provider
            cachedChipInfo = null
            Log.i(TAG, "Registered hook provider: ${provider.javaClass.simpleName}")
        }
    }
    
    fun unregisterHookProvider() {
        hookProvider = null
        cachedChipInfo = null
    }
    
    fun loadModHooks(context: Context) {
        val modManager = ModManager.getInstance(context)
        for (manifest in modManager.getEnabledManifests()) {
            val managerClassName = manifest.manager ?: continue
            val classLoader = modManager.getModClassLoader(manifest.id)
            runCatching {
                val managerClass = classLoader.loadClass(managerClassName)
                if (!hasBluetoothHook(managerClass)) continue
                val instance = createManagerInstance(managerClass, context) ?: return@runCatching
                val supported = invokeBoolean(instance, managerClass, "isBluetoothHookSupported", context) ?: false
                if (!supported) return@runCatching
                
                val provider = ModBluetoothHookProvider(manifest.id, managerClass, instance)
                registerHookProvider(provider, context)
                Log.i(TAG, "Loaded bluetooth hook from mod: ${manifest.id}")
            }.onFailure {
                Log.w(TAG, "Failed to load bluetooth hook from mod: ${manifest.id}", it)
            }
        }
    }
    
    private fun hasBluetoothHook(managerClass: Class<*>): Boolean {
        val hookMethods = setOf(
            "isBluetoothHookSupported",
            "getChipVendor",
            "getChipModel",
            "getMaxRealConnections",
            "getScannerQuirks",
            "applyChipSpecificFix",
            "interceptGattOperation"
        )
        return managerClass.methods.any { it.name in hookMethods }
    }
    
    private fun createManagerInstance(managerClass: Class<*>, context: Context): Any? {
        return runCatching {
            managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        }.recoverCatching {
            managerClass.getDeclaredConstructor().newInstance()
        }.getOrNull()
    }
    
    private fun invokeBoolean(instance: Any, managerClass: Class<*>, methodName: String, context: Context): Boolean? {
        val method = findMethod(managerClass, methodName, Context::class.java)
            ?: findMethod(managerClass, methodName)
            ?: return null
        return runCatching {
            when (method.parameterTypes.size) {
                0 -> method.invoke(instance) as? Boolean
                1 -> method.invoke(instance, context) as? Boolean
                else -> null
            }
        }.getOrNull()
    }
    
    private fun findMethod(managerClass: Class<*>, methodName: String, vararg paramTypes: Class<*>): Method? {
        return runCatching { managerClass.getMethod(methodName, *paramTypes) }.getOrNull()
    }
    
    private class ModBluetoothHookProvider(
        private val modId: String,
        private val managerClass: Class<*>,
        private val instance: Any
    ) : BluetoothHookProvider {
        override fun isSupported(context: Context): Boolean = true
        
        override fun getChipVendor(context: Context): String? {
            return invokeString("getChipVendor", context)
        }
        
        override fun getChipModel(context: Context): String? {
            return invokeString("getChipModel", context)
        }
        
        override fun getMaxRealConnections(context: Context): Int? {
            return invokeInt("getMaxRealConnections", context)
        }
        
        @Suppress("UNCHECKED_CAST")
        override fun getScannerQuirks(context: Context): Set<ScannerQuirk> {
            val result = invokeAny("getScannerQuirks", context) ?: return emptySet()
            return when (result) {
                is Set<*> -> result.mapNotNull { 
                    when (it) {
                        is ScannerQuirk -> it
                        is String -> runCatching { ScannerQuirk.valueOf(it) }.getOrNull()
                        else -> null
                    }
                }.toSet()
                else -> emptySet()
            }
        }
        
        override fun applyChipSpecificFix(context: Context, fixType: ChipFix): Boolean {
            val method = findMethod(managerClass, "applyChipSpecificFix", Context::class.java, String::class.java)
                ?: return false
            return runCatching {
                method.invoke(instance, context, fixType.name) as? Boolean ?: false
            }.getOrDefault(false)
        }
        
        override fun interceptGattOperation(operation: GattOperation): GattOperationResult? {
            return null
        }
        
        private fun invokeString(methodName: String, context: Context): String? {
            return invokeAny(methodName, context) as? String
        }
        
        private fun invokeInt(methodName: String, context: Context): Int? {
            return when (val result = invokeAny(methodName, context)) {
                is Int -> result
                is Number -> result.toInt()
                else -> null
            }
        }
        
        private fun invokeAny(methodName: String, context: Context): Any? {
            val method = findMethod(managerClass, methodName, Context::class.java)
                ?: findMethod(managerClass, methodName)
                ?: return null
            return runCatching {
                when (method.parameterTypes.size) {
                    0 -> method.invoke(instance)
                    1 -> method.invoke(instance, context)
                    else -> null
                }
            }.getOrNull()
        }
        
        private fun findMethod(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
            return runCatching { cls.getMethod(name, *paramTypes) }.getOrNull()
        }
    }
    
    fun getChipInfo(context: Context): ChipInfo {
        cachedChipInfo?.let { return it }
        
        val provider = hookProvider
        val vendor = provider?.getChipVendor(context) ?: detectChipVendor()
        val model = provider?.getChipModel(context) ?: detectChipModel()
        val maxConn = provider?.getMaxRealConnections(context) ?: detectMaxConnections(context)
        val quirks = provider?.getScannerQuirks(context) ?: detectQuirks(context)
        val bleVersion = detectBleVersion(context)
        
        return ChipInfo(vendor, model, bleVersion, maxConn, quirks).also { cachedChipInfo = it }
    }
    
    fun applyFix(context: Context, fix: ChipFix): Boolean {
        return hookProvider?.applyChipSpecificFix(context, fix) ?: false
    }

    /**
     * True when taking down [com.android.bluetooth] (kill -9, force-stop, HCI own/Reset)
     * can also drop Wi-Fi. MediaTek / Unisoc share conninfra with the WLAN firmware —
     * observed on vivo V2164KA (mt6833): Bluetooth Java process restart lined up with
     * a Wi-Fi disconnect. Satellites live on Wi-Fi, so a BLE-scan "fix" that yanks
     * the house network is never worth it.
     */
    fun killingBluetoothWouldDropWifi(context: Context): Boolean {
        val vendor = hookProvider?.getChipVendor(context) ?: detectChipVendor()
        if (vendor == "MediaTek" || vendor == "Spreadtrum") return true
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? WifiManager ?: return false
        return runCatching { wifi.isWifiEnabled }.getOrDefault(false)
    }
    
    fun interceptOperation(operation: GattOperation): GattOperationResult {
        return hookProvider?.interceptGattOperation(operation) ?: GattOperationResult.Proceed
    }
    
    fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        return runCatching {
            val method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt) as? Boolean ?: false
        }.getOrElse {
            Log.w(TAG, "BluetoothGatt.refresh() failed: ${it.message}")
            false
        }
    }
    
    private fun detectChipVendor(): String {
        val board = Build.BOARD.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        return when {
            board.contains("mt") || hardware.contains("mt") -> "MediaTek"
            board.contains("qcom") || hardware.contains("qcom") -> "Qualcomm"
            board.contains("exynos") || hardware.contains("exynos") -> "Samsung"
            board.contains("sp") || hardware.contains("spreadtrum") -> "Spreadtrum"
            board.contains("rk") || hardware.contains("rockchip") -> "Rockchip"
            board.contains("allwinner") || hardware.contains("sun") -> "Allwinner"
            else -> "Unknown"
        }
    }
    
    private fun detectChipModel(): String {
        return Build.HARDWARE
    }
    
    private fun detectMaxConnections(context: Context): Int {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return 3
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (adapter.isLe2MPhySupported || adapter.isLeExtendedAdvertisingSupported) 5 else 3
        } else {
            3
        }
    }
    
    private fun detectBleVersion(context: Context): Int {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return 4
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when {
                adapter.isLeCodedPhySupported -> 5
                adapter.isLe2MPhySupported -> 5
                adapter.isLeExtendedAdvertisingSupported -> 5
                else -> 4
            }
        } else {
            4
        }
    }
    
    private fun detectQuirks(context: Context): Set<ScannerQuirk> {
        val quirks = mutableSetOf<ScannerQuirk>()
        val vendor = detectChipVendor()
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            quirks.add(ScannerQuirk.NEEDS_LOCATION_ENABLED)
        }
        
        when (vendor) {
            "MediaTek" -> {
                quirks.add(ScannerQuirk.SCAN_STALLS_AFTER_MINUTES)
                quirks.add(ScannerQuirk.NEEDS_PERIODIC_RESTART)
            }
            "Spreadtrum" -> {
                quirks.add(ScannerQuirk.SCAN_FILTER_BROKEN)
                quirks.add(ScannerQuirk.LOW_RSSI_ACCURACY)
            }
            "Allwinner" -> {
                quirks.add(ScannerQuirk.PASSIVE_SCAN_BROKEN)
                quirks.add(ScannerQuirk.BATCH_SCAN_BROKEN)
            }
        }
        
        return quirks
    }
}
