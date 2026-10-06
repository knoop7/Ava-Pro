package com.example.ava.mods

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

object ModManagerBridge {
    private const val TAG = "ModManagerBridge"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun readValue(
        modId: String,
        managerClassName: String?,
        context: Context,
        methodName: String?,
        configValues: Map<String, String> = emptyMap()
    ): Any? {
        if (managerClassName.isNullOrBlank() || methodName.isNullOrBlank()) return null
        return try {
            val resolved = resolve(context, modId, managerClassName) ?: return null
            applyConfig(resolved.managerClass, resolved.instance, context, configValues)
            val readMethod = resolved.managerClass.getMethod(methodName)
            readMethod.invoke(resolved.instance)
        } catch (e: Exception) {
            Log.w(TAG, "readValue failed: $methodName", e)
            null
        }
    }

    fun invokeAction(
        modId: String,
        managerClassName: String?,
        context: Context,
        methodName: String?,
        configValues: Map<String, String> = emptyMap()
    ): Boolean {
        if (managerClassName.isNullOrBlank() || methodName.isNullOrBlank()) return false
        return try {
            val resolved = resolve(context, modId, managerClassName) ?: return false
            applyConfig(resolved.managerClass, resolved.instance, context, configValues)
            val actionMethod = resolved.managerClass.getMethod(methodName)
            val result = actionMethod.invoke(resolved.instance)
            result as? Boolean ?: true
        } catch (e: Exception) {
            Log.w(TAG, "invokeAction failed: $methodName", e)
            false
        }
    }

    fun registerStateListener(
        modId: String,
        managerClassName: String?,
        context: Context,
        listenerId: String,
        callback: ModStateCallback,
        configValues: Map<String, String> = emptyMap()
    ): Boolean {
        if (managerClassName.isNullOrBlank() || listenerId.isBlank()) return false
        return try {
            val resolved = resolve(context, modId, managerClassName) ?: return false
            val managerClass = resolved.managerClass
            val instance = resolved.instance
            applyConfig(managerClass, instance, context, configValues)
            val safeCallback = object : ModStateCallback() {
                override fun onStateChanged(value: Any?) {
                    mainHandler.post { callback.onStateChanged(value) }
                }
            }
            val registerMethod = managerClass.getMethod(
                "registerStateListener",
                String::class.java,
                Any::class.java
            )
            val result = registerMethod.invoke(instance, listenerId, safeCallback)
            result as? Boolean ?: true
        } catch (e: Exception) {
            Log.d(TAG, "registerStateListener unavailable for $listenerId")
            false
        }
    }

    fun syncConfig(
        modId: String,
        managerClassName: String?,
        context: Context,
        configValues: Map<String, String>,
    ) {
        if (managerClassName.isNullOrBlank() || configValues.isEmpty()) return
        try {
            val resolved = resolve(context, modId, managerClassName) ?: return
            applyConfig(resolved.managerClass, resolved.instance, context, configValues)
        } catch (e: Exception) {
            Log.w(TAG, "syncConfig failed", e)
        }
    }

    private fun resolve(
        context: Context,
        modId: String,
        managerClassName: String,
    ): ModManager.ResolvedModManager? {
        val modManager = ModManager.getInstance(context)
        if (!modManager.isEnabled(modId)) return null
        return modManager.resolveEnabledManager(modId, managerClassName)
    }

    private fun applyConfig(
        managerClass: Class<*>,
        instance: Any,
        context: Context,
        configValues: Map<String, String>
    ) {
        if (configValues.isEmpty()) return
        try {
            val applyConfigMethod = managerClass.getMethod("applyConfig", String::class.java, String::class.java)
            configValues.forEach { (key, value) ->
                applyConfigMethod.invoke(instance, key, value)
            }
        } catch (_: Exception) {
            // Optional hook
        }
    }
}
