package com.example.ava.mods

import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * Optional device-compatibility hook bridge for mods.
 *
 * A mod manager class may expose any of these optional methods:
 * - isSupported() / isSupported(Context)
 * - getMinBrightness() / getMinBrightness(Context) — lowest brightness Ava will write while the panel is on.
 *   When a mod provides this, brightness 0 on the HA slider means screen off, not backlight 0.
 * - isLowEndBleChip() / isLowEndBleChip(Context)
 * - suppressHostBleAdvertisingDuringProxy() / suppressHostBleAdvertisingDuringProxy(Context)
 * - getBleProxyHandoverDelayMs() / getBleProxyHandoverDelayMs(Context)
 * - recoverBluetoothProxyScanFailure(int) / recoverBluetoothProxyScanFailure(Context, int)
 * - grantOverlayPermissionIfNeeded() / grantOverlayPermissionIfNeeded(Context)
 * - sleepScreenForDark(Context) — screensaver dark-off; mod tries device-specific sleep (e.g. root/Shizuku)
 * - wakeScreenFromDark(Context) — restore screen after dark-off
     * - setScreenPower(Context, boolean) — HA screen switch after the host shell fails.
     *   Off may sleep the panel (device-admin lockNow). On should return false so the host wakes.
     *   Core does not call lockNow itself. When this hook exists and off returns false, the host
     *   leaves the panel on instead of writing a brightness floor.
 *
 * Mods without these methods are ignored by this bridge.
 */
object ModDeviceSupport {
    private const val TAG = "ModDeviceSupport"

    private val hookMethodNames = setOf(
        "isSupported",
        "getMinBrightness",
        "setScreenPower",
        "isLowEndBleChip",
        "suppressHostBleAdvertisingDuringProxy",
        "getBleProxyHandoverDelayMs",
        "recoverBluetoothProxyScanFailure",
        "grantOverlayPermissionIfNeeded",
        "onKeyDown",
        "onKeyUp",
        "sleepScreenForDark",
        "wakeScreenFromDark",
    )

    fun getMinBrightness(context: Context, fallback: Int): Int {
        return installedMinBrightness(context) ?: fallback
    }

    /**
     * Lowest on-brightness from an enabled device mod, or null when none implements the hook.
     * Null keeps the stock slider (1–255). A value opts that device into: slider 0 turns the
     * screen off, and Ava will not write a brightness below this floor.
     */
    fun installedMinBrightness(context: Context): Int? {
        return getSupportedManagers(context)
            .firstNotNullOfOrNull { supported ->
                if (!hasHookMethod(supported.managerClass, "getMinBrightness")) return@firstNotNullOfOrNull null
                invokeInt(supported.instance, supported.managerClass, "getMinBrightness", context)
            }
            ?.coerceIn(1, 255)
    }

    fun isLowEndBleChip(context: Context): Boolean? {
        return getSupportedManagers(context)
            .firstNotNullOfOrNull { supported ->
                invokeBoolean(supported.instance, supported.managerClass, "isLowEndBleChip", context)
            }
    }

    fun suppressHostBleAdvertisingDuringProxy(context: Context): Boolean {
        return getSupportedManagers(context).any { supported ->
            invokeBoolean(
                supported.instance,
                supported.managerClass,
                "suppressHostBleAdvertisingDuringProxy",
                context,
            ) == true
        }
    }

    fun getBleProxyHandoverDelayMs(context: Context): Int {
        return getSupportedManagers(context)
            .firstNotNullOfOrNull { supported ->
                invokeInt(
                    supported.instance,
                    supported.managerClass,
                    "getBleProxyHandoverDelayMs",
                    context,
                )
            }
            ?.coerceIn(0, 5_000)
            ?: 0
    }

    /**
     * Lets a device mod recover its own Bluetooth stack after a proxy scan failure.
     * The host only forwards the failure; privileged and device-specific work stays in the mod.
     */
    fun recoverBluetoothProxyScanFailure(context: Context, errorCode: Int): Boolean {
        return getSupportedManagers(context).any { supported ->
            val method = findIntMethod(supported.managerClass, "recoverBluetoothProxyScanFailure")
                ?: findContextIntMethod(
                    supported.managerClass,
                    "recoverBluetoothProxyScanFailure",
                )
                ?: return@any false

            runCatching {
                when (method.parameterTypes.size) {
                    1 -> method.invoke(supported.instance, errorCode)
                    2 -> method.invoke(supported.instance, context, errorCode)
                    else -> false
                } as? Boolean ?: false
            }.onFailure {
                Log.w(
                    TAG,
                    "Failed to invoke ${supported.managerClass.name}#recoverBluetoothProxyScanFailure",
                    it,
                )
            }.getOrDefault(false)
        }
    }

    fun grantOverlayPermissionIfNeeded(context: Context): Boolean {
        return getSupportedManagers(context).any { supported ->
            invokeBoolean(
                instance = supported.instance,
                managerClass = supported.managerClass,
                methodName = "grantOverlayPermissionIfNeeded",
                context = context
            ) == true
        }
    }

    /**
     * True when an enabled, supported device mod exposes screensaver dark sleep hook.
     * Used to choose mod-specific overlay handling without affecting other devices.
     */
    fun hasSleepScreenForDarkHook(context: Context): Boolean {
        return getSupportedManagers(context).any { supported ->
            hasHookMethod(supported.managerClass, "sleepScreenForDark")
        }
    }

    /**
     * True when an enabled, supported device mod successfully handled screensaver dark sleep.
     * False when no mod hook, unsupported device, or mod could not sleep — caller may fall back.
     */
    fun trySleepScreenForDark(context: Context): Boolean {
        return invokeFirstSuccessfulBooleanHook(context, "sleepScreenForDark")
    }

    /**
     * True when an enabled device mod successfully woke the screen after dark sleep.
     * False when no mod hook or wake failed — caller may fall back.
     */
    fun tryWakeScreenFromDark(context: Context): Boolean {
        return invokeFirstSuccessfulBooleanHook(context, "wakeScreenFromDark")
    }

    /**
     * True when an enabled device mod owns the HA screen switch.
     * The host must not blank that panel by writing brightness.
     */
    fun hasSetScreenPowerHook(context: Context): Boolean {
        return getSupportedManagers(context).any { supported ->
            findContextBooleanMethod(supported.managerClass, "setScreenPower") != null
        }
    }

    /**
     * HA screen switch, only after the host shell could not drive the panel.
     * True when a device mod handled [screenOn]. A false return on wake lets the host wake lock run.
     * A false return on off means the panel stayed on.
     */
    fun trySetScreenPower(context: Context, screenOn: Boolean): Boolean {
        return getSupportedManagers(context).any { supported ->
            val method = findContextBooleanMethod(supported.managerClass, "setScreenPower")
                ?: return@any false
            runCatching {
                method.invoke(supported.instance, context, screenOn) as? Boolean ?: false
            }.onFailure {
                Log.w(TAG, "Failed to invoke ${supported.managerClass.name}#setScreenPower", it)
            }.getOrDefault(false)
        }
    }

    private fun invokeFirstSuccessfulBooleanHook(context: Context, methodName: String): Boolean {
        return getSupportedManagers(context).any { supported ->
            if (!hasHookMethod(supported.managerClass, methodName)) {
                return@any false
            }
            invokeBoolean(supported.instance, supported.managerClass, methodName, context) == true
        }
    }

    private fun hasHookMethod(managerClass: Class<*>, methodName: String): Boolean {
        return findZeroArgMethod(managerClass, methodName) != null ||
            findContextMethod(managerClass, methodName) != null
    }

    private fun getSupportedManagers(context: Context): List<SupportedManager> {
        val modManager = ModManager.getInstance(context)
        return modManager.getEnabledManifests().mapNotNull { manifest ->
            val managerClassName = manifest.manager ?: return@mapNotNull null
            val classLoader = modManager.getModClassLoader(manifest.id)

            try {
                val managerClass = classLoader.loadClass(managerClassName)
                if (!hasDeviceSupportHook(managerClass)) {
                    return@mapNotNull null
                }
                val instance = createManagerInstance(managerClass, context) ?: return@mapNotNull null
                val supported = invokeBoolean(instance, managerClass, "isSupported", context) ?: true
                if (!supported) {
                    return@mapNotNull null
                }

                SupportedManager(manifest.id, managerClass, instance)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load device support mod: ${manifest.id}", e)
                null
            }
        }
    }

    fun onKeyDown(context: Context, keyCode: Int, event: android.view.KeyEvent?): Boolean {
        return getSupportedManagers(context).any { supported ->
            runCatching {
                val method = supported.managerClass.getMethod("onKeyDown", Context::class.java, Int::class.javaPrimitiveType, android.view.KeyEvent::class.java)
                method.invoke(supported.instance, context, keyCode, event) as? Boolean ?: false
            }.getOrDefault(false)
        }
    }

    fun onKeyUp(context: Context, keyCode: Int, event: android.view.KeyEvent?): Boolean {
        return getSupportedManagers(context).any { supported ->
            runCatching {
                val method = supported.managerClass.getMethod("onKeyUp", Context::class.java, Int::class.javaPrimitiveType, android.view.KeyEvent::class.java)
                method.invoke(supported.instance, context, keyCode, event) as? Boolean ?: false
            }.getOrDefault(false)
        }
    }

    private fun hasDeviceSupportHook(managerClass: Class<*>): Boolean {
        return managerClass.methods.any { it.name in hookMethodNames }
    }

    private fun createManagerInstance(managerClass: Class<*>, context: Context): Any? {
        return runCatching {
            managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        }.recoverCatching {
            managerClass.getDeclaredConstructor().newInstance()
        }.onFailure {
            Log.w(TAG, "Failed to create manager instance: ${managerClass.name}", it)
        }.getOrNull()
    }

    private fun invokeBoolean(
        instance: Any,
        managerClass: Class<*>,
        methodName: String,
        context: Context
    ): Boolean? {
        return invokeMethod(instance, managerClass, methodName, context) as? Boolean
    }

    private fun invokeInt(
        instance: Any,
        managerClass: Class<*>,
        methodName: String,
        context: Context
    ): Int? {
        return when (val result = invokeMethod(instance, managerClass, methodName, context)) {
            is Int -> result
            is Number -> result.toInt()
            else -> null
        }
    }

    private fun invokeMethod(
        instance: Any,
        managerClass: Class<*>,
        methodName: String,
        context: Context
    ): Any? {
        val method = findZeroArgMethod(managerClass, methodName)
            ?: findContextMethod(managerClass, methodName)
            ?: return null

        return runCatching {
            when (method.parameterTypes.size) {
                0 -> method.invoke(instance)
                1 -> method.invoke(instance, context)
                else -> null
            }
        }.onFailure {
            Log.w(TAG, "Failed to invoke ${managerClass.name}#$methodName", it)
        }.getOrNull()
    }

    private fun findZeroArgMethod(managerClass: Class<*>, methodName: String): Method? {
        return runCatching { managerClass.getMethod(methodName) }.getOrNull()
    }

    private fun findContextMethod(managerClass: Class<*>, methodName: String): Method? {
        return runCatching { managerClass.getMethod(methodName, Context::class.java) }.getOrNull()
    }

    private fun findIntMethod(managerClass: Class<*>, methodName: String): Method? {
        return runCatching {
            managerClass.getMethod(methodName, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    private fun findContextBooleanMethod(managerClass: Class<*>, methodName: String): Method? {
        return runCatching {
            managerClass.getMethod(
                methodName,
                Context::class.java,
                Boolean::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    private fun findContextIntMethod(managerClass: Class<*>, methodName: String): Method? {
        return runCatching {
            managerClass.getMethod(
                methodName,
                Context::class.java,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    private data class SupportedManager(
        val modId: String,
        val managerClass: Class<*>,
        val instance: Any
    )
}
