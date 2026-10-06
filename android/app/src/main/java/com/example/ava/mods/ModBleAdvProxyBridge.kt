package com.example.ava.mods

import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.example.ava.bluetooth.BleOperationCoordinator
import com.example.ava.bluetooth.BluetoothPresenceManager
import com.example.ava.esphome.entities.Entity
import com.example.ava.esphome.entities.ServiceArg
import com.example.ava.esphome.entities.ServiceEntity
import com.example.ava.services.VoiceSatelliteService
import com.example.esphomeproto.api.ServiceArgType
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.lang.reflect.Method

/**
 * Zero-cost bridge for the optional ble-adv-proxy mod ([ModManifest.bleAdvProxy]).
 *
 * **Standalone** ([isStandalone]): mod owns BLE; Ava [detect_enabled] is off and host scan
 * forwarding is disabled.
 *
 * **Legacy integrated** ([isLegacyIntegrated]): unified proxy scan + [BleOperationCoordinator]
 * (pre-standalone behaviour; opt in via manifest `ble_adv_standalone: false`).
 *
 * **No mod installed / disabled → [isActive] is false; all entry points return immediately**
 * without class loading, entity registration, scan forwarding, or BLE coordinator use.
 */
object ModBleAdvProxyBridge {
    private const val TAG = "ModBleAdvProxy"

    @Volatile
    private var cachedGeneration = -1

    @Volatile
    private var cachedActive = false

    private var cachedBinding: ModBinding? = null

    private val bleAdvEntities = mutableListOf<Entity>()

    @Volatile
    private var hostApi: BleAdvHostApi? = null

    @Volatile
    private var homeassistantServicesSubscribed = false

    fun invalidateCache(context: Context? = null) {
        cachedGeneration = -1
        cachedBinding = null
        cachedActive = false
        bleAdvEntities.clear()
        homeassistantServicesSubscribed = false
        hostApi?.setPresenceAdvertisingSuppressed(false)
        hostApi = null
        context?.applicationContext?.let { appContext ->
            BluetoothPresenceManager.getInstance(appContext).setPresenceAdvertisingSuppressed(false)
        }
    }

    /** Fast path: false when mod is not installed, disabled, or failed to bind. */
    fun isActive(context: Context): Boolean {
        val generation = ModManager.getInstance(context).registryGeneration
        if (generation == cachedGeneration) {
            return cachedActive
        }
        return refreshActivitySnapshot(context.applicationContext, generation)
    }

    /**
     * Standalone ble-adv-proxy: mod owns scan/TX; Ava Bluetooth detect must stay off.
     * Defaults to true for [ModManifest.bleAdvProxy] unless manifest sets
     * `ble_adv_standalone: false`.
     */
    fun isStandalone(context: Context): Boolean {
        val manifest = resolveEnabledBleAdvManifest(context) ?: return false
        return manifest.usesStandaloneBleAdv()
    }

    /** Legacy unified-scan path — only when mod is active and not standalone. */
    fun isLegacyIntegrated(context: Context): Boolean =
        isActive(context) && !isStandalone(context)

    /**
     * Turn off Ava Bluetooth detect when standalone mod is installed so host LE scan
     * does not compete with mod MGMT TX.
     */
    fun applyStandaloneHostPolicy(context: Context) {
        if (!isStandalone(context)) return
        val appContext = context.applicationContext
        BluetoothPresenceManager.getInstance(appContext).setPresenceAdvertisingSuppressed(true)
        val prefs = appContext.getSharedPreferences(BLUETOOTH_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(DETECT_ENABLED_KEY, false)) return
        prefs.edit().putBoolean(DETECT_ENABLED_KEY, false).apply()
        Log.i(TAG, "standalone: disabled Ava detect_enabled (ble-adv-proxy owns BLE)")
        VoiceSatelliteService.getInstance()?.restartVoiceSatellite()
    }

    /** Apply standalone policy for every enabled standalone ble-adv mod (boot / registry reload). */
    fun syncStandalonePolicies(context: Context) {
        if (isStandalone(context)) {
            applyStandaloneHostPolicy(context)
        }
    }

    fun createEntities(context: Context, deviceName: String): List<Entity> {
        if (!isActive(context)) return emptyList()
        val binding = binding(context) ?: return emptyList()
        syncModConfig(context, binding)

        bleAdvEntities.clear()
        bleAdvEntities += buildServiceEntities(context, binding)
        return bleAdvEntities.toList()
    }

    fun onEspHomeConnected(
        context: Context,
        deviceName: String,
        scope: CoroutineScope,
        sendMessage: suspend (MessageLite) -> Unit,
    ) {
        if (!isActive(context)) return
        val binding = binding(context) ?: return
        syncModConfig(context, binding)

        homeassistantServicesSubscribed = false
        hostApi = BleAdvHostApi(context.applicationContext, scope, sendMessage)
        hostApi?.setPresenceAdvertisingSuppressed(true)
        invokeLifecycle(binding, "onEspHomeConnected", context, deviceName, hostApi)
        Log.i(TAG, "ble-adv-proxy connected (adapter=${resolveAdapterName(context, binding, deviceName)})")
    }

    fun onEspHomeDisconnected(context: Context) {
        homeassistantServicesSubscribed = false
        hostApi?.setPresenceAdvertisingSuppressed(false)
        hostApi = null
        cachedBinding?.let { invokeLifecycle(it, "onEspHomeDisconnected", context.applicationContext) }
    }

    fun onHomeassistantServicesSubscribed(context: Context) {
        if (!isActive(context)) return
        homeassistantServicesSubscribed = true
        binding(context)?.let { invokeLifecycle(it, "onHomeassistantServicesSubscribed", context) }
        Log.d(TAG, "HA subscribed to homeassistant services")
    }

    fun onScanResult(context: Context, result: ScanResult) {
        if (!isActive(context)) return
        if (isStandalone(context)) return
        if (!homeassistantServicesSubscribed) return
        if (BleOperationCoordinator.isExclusiveActive) return

        val binding = binding(context) ?: return
        val record = result.scanRecord ?: return
        val raw = record.bytes ?: return
        if (raw.size < 5) return

        val mac = result.device.address ?: return
        invokeScanResult(binding, context, mac, result.rssi, raw)
    }

    private fun refreshActivitySnapshot(context: Context, generation: Int): Boolean {
        val modManager = ModManager.getInstance(context)
        val manifest = modManager.getEnabledManifests()
            .firstOrNull { it.bleAdvProxy && !it.manager.isNullOrBlank() }

        cachedGeneration = generation

        if (manifest == null) {
            cachedBinding = null
            cachedActive = false
            return false
        }

        if (!modManager.isEnabled(manifest.id)) {
            cachedBinding = null
            cachedActive = false
            return false
        }

        if (!isModPackagePresent(modManager, manifest)) {
            Log.w(TAG, "ble-adv-proxy enabled in registry but package missing: ${manifest.id}")
            cachedBinding = null
            cachedActive = false
            return false
        }

        val binding = loadBinding(context, modManager, manifest)
        cachedBinding = binding
        cachedActive = binding != null
        return cachedActive
    }

    private fun resolveEnabledBleAdvManifest(context: Context): ModManifest? {
        val modManager = ModManager.getInstance(context.applicationContext)
        val manifest = modManager.getEnabledManifests()
            .firstOrNull { it.bleAdvProxy && !it.manager.isNullOrBlank() } ?: return null
        if (!modManager.isEnabled(manifest.id)) return null
        if (!isModPackagePresent(modManager, manifest)) return null
        return manifest
    }

    private fun binding(context: Context): ModBinding? {
        if (!isActive(context)) return null
        return cachedBinding
    }

    private fun isModPackagePresent(modManager: ModManager, manifest: ModManifest): Boolean {
        val modDir = modManager.getModDir(manifest.id) ?: return false
        val libs = manifest.libs.orEmpty()
        if (libs.isEmpty()) return false
        return libs.all { lib ->
            lib.endsWith(".jar") && File(modDir, lib).isFile
        }
    }

    private fun loadBinding(
        context: Context,
        modManager: ModManager,
        manifest: ModManifest,
    ): ModBinding? {
        val managerClassName = manifest.manager ?: return null
        val classLoader = modManager.getModClassLoader(manifest.id)
        return runCatching {
            val managerClass = classLoader.loadClass(managerClassName)
            val instance = getManagerInstance(managerClass, context) ?: return@runCatching null

            val supported = managerClass
                .getMethod("isBleAdvProxySupported", Context::class.java)
                .invoke(instance, context) as? Boolean
            if (supported != true) return@runCatching null

            val enabled = managerClass
                .getMethod("isFeatureEnabled", Context::class.java)
                .invoke(instance, context) as? Boolean
            if (enabled == false) return@runCatching null

            ModBinding(
                modId = manifest.id,
                managerClassName = managerClassName,
                managerClass = managerClass,
                instance = instance,
            )
        }.onFailure {
            Log.w(TAG, "Failed to bind ble-adv-proxy mod: ${manifest.id}", it)
        }.getOrNull()
    }

    private fun getManagerInstance(managerClass: Class<*>, context: Context): Any? {
        return runCatching {
            managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        }.getOrNull()
    }

    private fun syncModConfig(context: Context, binding: ModBinding) {
        val modManager = ModManager.getInstance(context)
        val manifest = modManager.getCachedManifest(binding.modId) ?: return
        val configValues = modManager.getResolvedConfig(binding.modId, manifest)
        ModManagerBridge.syncConfig(binding.modId, binding.managerClassName, context, configValues)
    }

    private fun resolveAdapterName(context: Context, binding: ModBinding, deviceName: String): String {
        return runCatching {
            binding.managerClass.getMethod("getAdapterName", Context::class.java)
                .invoke(binding.instance, context) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: deviceName
    }

    private fun buildServiceEntities(context: Context, binding: ModBinding): List<ServiceEntity> {
        return listOf(
            ServiceEntity(
                key = "setup_svc_v0".hashCode(),
                name = "setup_svc_v0",
                args = listOf(
                    ServiceArg("ignored_duration", ServiceArgType.SERVICE_ARG_TYPE_FLOAT),
                    ServiceArg("ignored_cids", ServiceArgType.SERVICE_ARG_TYPE_INT_ARRAY),
                    ServiceArg("ignored_macs", ServiceArgType.SERVICE_ARG_TYPE_STRING_ARRAY),
                ),
                description = "BLE ADV proxy setup",
                onExecute = { args ->
                    forwardServiceCall(binding, context, "setup_svc_v0", args)
                },
            ),
            ServiceEntity(
                key = "adv_svc".hashCode(),
                name = "adv_svc",
                args = listOf(
                    ServiceArg("raw", ServiceArgType.SERVICE_ARG_TYPE_STRING),
                    ServiceArg("duration", ServiceArgType.SERVICE_ARG_TYPE_FLOAT),
                ),
                description = "BLE ADV transmit (legacy)",
                onExecute = { args ->
                    forwardServiceCall(binding, context, "adv_svc", args)
                },
            ),
            ServiceEntity(
                key = "adv_svc_v1".hashCode(),
                name = "adv_svc_v1",
                args = listOf(
                    ServiceArg("raw", ServiceArgType.SERVICE_ARG_TYPE_STRING),
                    ServiceArg("duration", ServiceArgType.SERVICE_ARG_TYPE_FLOAT),
                    ServiceArg("repeat", ServiceArgType.SERVICE_ARG_TYPE_FLOAT),
                    ServiceArg("ignored_advs", ServiceArgType.SERVICE_ARG_TYPE_STRING_ARRAY),
                    ServiceArg("ignored_duration", ServiceArgType.SERVICE_ARG_TYPE_FLOAT),
                ),
                description = "BLE ADV transmit",
                onExecute = { args ->
                    forwardServiceCall(binding, context, "adv_svc_v1", args)
                },
            ),
        )
    }

    private suspend fun forwardServiceCall(
        binding: ModBinding,
        context: Context,
        serviceName: String,
        args: Map<String, Any>,
    ) {
        if (!isActive(context)) return
        runCatching {
            binding.managerClass.getMethod(
                "onServiceCall",
                Context::class.java,
                String::class.java,
                Map::class.java,
            ).invoke(binding.instance, context, serviceName, args)
        }.onFailure {
            Log.w(TAG, "onServiceCall failed for $serviceName", it)
        }
    }

    private fun invokeScanResult(
        binding: ModBinding,
        context: Context,
        mac: String,
        rssi: Int,
        raw: ByteArray,
    ) {
        runCatching {
            binding.managerClass.getMethod(
                "onScanResult",
                Context::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
            ).invoke(binding.instance, context, mac, rssi, raw)
        }.onFailure {
            Log.w(TAG, "onScanResult failed", it)
        }
    }

    private fun invokeLifecycle(binding: ModBinding, methodName: String, vararg args: Any?) {
        runCatching {
            val method = findMethod(binding.managerClass, methodName, args)
                ?: return@runCatching
            method.invoke(binding.instance, *args)
        }.onFailure {
            Log.w(TAG, "$methodName failed for ${binding.modId}", it)
        }
    }

    private fun findMethod(managerClass: Class<*>, methodName: String, args: Array<out Any?>): Method? {
        if (args.isEmpty()) {
            return runCatching { managerClass.getMethod(methodName) }.getOrNull()
        }
        val paramTypes = args.map { arg ->
            when (arg) {
                null -> Any::class.java
                is Context -> Context::class.java
                is String -> String::class.java
                is BleAdvHostApi -> BleAdvHostApi::class.java
                else -> arg.javaClass
            }
        }.toTypedArray()
        return runCatching { managerClass.getMethod(methodName, *paramTypes) }.getOrNull()
            ?: runCatching {
                managerClass.methods.firstOrNull { method ->
                    method.name == methodName && method.parameterTypes.size == args.size
                }
            }.getOrNull()
    }

    private data class ModBinding(
        val modId: String,
        val managerClassName: String,
        val managerClass: Class<*>,
        val instance: Any,
    )

    private const val BLUETOOTH_PREFS = "bluetooth_settings"
    private const val DETECT_ENABLED_KEY = "detect_enabled"
}
