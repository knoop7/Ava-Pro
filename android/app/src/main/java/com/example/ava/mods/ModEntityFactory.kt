package com.example.ava.mods

import android.content.Context
import android.util.Log
import com.example.ava.camera.VideoCapture
import com.example.ava.esphome.entities.BinarySensorEntity
import com.example.ava.esphome.entities.ButtonEntity
import com.example.ava.esphome.entities.CameraEntity
import com.example.ava.esphome.entities.Entity
import com.example.ava.esphome.entities.NumberEntity
import com.example.ava.esphome.entities.SensorEntity
import com.example.ava.esphome.entities.SelectEntity
import com.example.ava.esphome.entities.SwitchEntity
import com.example.ava.esphome.entities.TextEntity
import com.example.ava.esphome.entities.TextSensorEntity
import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.NumberMode
import kotlinx.coroutines.flow.MutableStateFlow

object ModEntityFactory {
    
    private const val TAG = "ModEntityFactory"

    /** ha-ble-adv matches sensor.{device}_ble_adv_proxy_name — do not prefix mod id. */
    private fun modObjectId(modId: String, entityId: String): String {
        if (entityId == "ble_adv_proxy_name") return entityId
        return "${modId}_$entityId"
    }
    
    data class RefreshableEntity(
        val entity: Entity,
        val refresh: (() -> Unit)?,
        val refreshIntervalMs: Long? = null
    )
    
    fun createEntities(
        manifest: ModManifest,
        context: Context? = null,
        classLoader: ClassLoader? = null,
        configValues: Map<String, String> = emptyMap()
    ): List<Entity> {
        return createRefreshableEntities(manifest, context, classLoader, configValues).map { it.entity }
    }
    
    fun createRefreshableEntities(
        manifest: ModManifest,
        context: Context? = null,
        classLoader: ClassLoader? = null,
        configValues: Map<String, String> = emptyMap()
    ): List<RefreshableEntity> {
        val stateStore = context?.let { ModStateStore(it) }
        val invokeAction: (String?) -> Unit = createActionInvoker(
            modId = manifest.id,
            managerClassName = manifest.manager,
            context = context,
            classLoader = classLoader,
            configValues = configValues,
        )
        val readValue: (String?) -> Any? = createValueReader(
            modId = manifest.id,
            managerClassName = manifest.manager,
            context = context,
            classLoader = classLoader,
            configValues = configValues,
        )
        val registerStateListener = createStateListenerRegistrar(
            modId = manifest.id,
            managerClassName = manifest.manager,
            context = context,
            configValues = configValues,
        )
        
        return manifest.entities
            .filter { isEntityEnabled(it, configValues) }
            .mapNotNull { entity ->
            try {
                when (entity.type) {
                    "switch" -> createRefreshableSwitch(manifest.id, entity, invokeAction, readValue, registerStateListener, stateStore)
                    "binary_sensor" -> createRefreshableBinarySensor(manifest.id, entity, readValue, registerStateListener)
                    "sensor" -> createRefreshableSensor(manifest.id, entity, readValue, registerStateListener)
                    "number" -> RefreshableEntity(createNumber(manifest.id, entity, invokeAction, readValue, registerStateListener), null)
                    "button" -> RefreshableEntity(createButton(manifest.id, entity, invokeAction), null)
                    "select" -> RefreshableEntity(createSelect(manifest.id, entity, invokeAction, readValue, registerStateListener), null)
                    "text" -> RefreshableEntity(createText(manifest.id, entity, invokeAction, readValue), null)
                    "text_sensor" -> createRefreshableTextSensor(manifest.id, entity, readValue, registerStateListener)
                    "camera" -> createCamera(
                        modId = manifest.id,
                        entity = entity,
                        context = context,
                        readValue = readValue,
                        registerStateListener = registerStateListener,
                    )
                    else -> {
                        Log.w(TAG, "Unknown entity type: ${entity.type}")
                        null
                    }
                }
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                Log.e(TAG, "Failed to create entity ${entity.id}", t)
                null
            }
        }
    }

    private fun createStateListenerRegistrar(
        modId: String,
        managerClassName: String?,
        context: Context?,
        configValues: Map<String, String> = emptyMap()
    ): ((String, Any) -> Boolean)? {
        if (managerClassName.isNullOrBlank() || context == null) {
            return null
        }

        // Resolve manager on every register (same ClassLoader as press/actions) so soft
        // restart / onDestroy→null cannot leave camera callbacks on a dead singleton.
        // Disabled mods resolve to null so a leftover binding cannot call getInstance().
        return registrar@{ entityId, callback ->
            try {
                val resolved = ModManager.getInstance(context)
                    .resolveEnabledManager(modId, managerClassName)
                    ?: return@registrar false
                val managerClass = resolved.managerClass
                val deviceManager = resolved.instance
                applyConfigToManager(managerClass, deviceManager, context, configValues)
                val registerMethod = managerClass.getMethod(
                    "registerStateListener",
                    String::class.java,
                    Any::class.java,
                )
                val result = registerMethod.invoke(deviceManager, entityId, callback)
                result as? Boolean ?: true
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                Log.w(TAG, "Failed to register state listener for $modId/$entityId", t)
                false
            }
        }
    }
    
    private fun createActionInvoker(
        modId: String,
        managerClassName: String?,
        context: Context?,
        @Suppress("UNUSED_PARAMETER") classLoader: ClassLoader? = null,
        configValues: Map<String, String> = emptyMap()
    ): (String?) -> Unit {
        if (managerClassName.isNullOrBlank() || context == null) {
            return { action ->
                if (!action.isNullOrBlank()) {
                    Log.w(TAG, "Dropping action '$action' for $modId — manager unavailable")
                }
            }
        }

        // Resolve manager on every press so a soft restart / ClassLoader refresh cannot leave
        // HA buttons wired to a dead no-op closure from entity creation time.
        return actionInvoker@{ action ->
            if (action.isNullOrBlank()) return@actionInvoker
            try {
                // Always resolve via ModManager so a ClassLoader refresh after reload/restart
                // cannot leave presses bound to a dead DexClassLoader from entity creation.
                val resolved = ModManager.getInstance(context)
                    .resolveEnabledManager(modId, managerClassName)
                    ?: return@actionInvoker
                val managerClass = resolved.managerClass
                val deviceManager = resolved.instance
                applyConfigToManager(managerClass, deviceManager, context, configValues)
                invokeManagerAction(managerClass, deviceManager, action)
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                Log.e(TAG, "Failed action '$action' for $modId ($managerClassName)", t)
            }
        }
    }
    
    private fun createValueReader(
        modId: String,
        managerClassName: String?,
        context: Context?,
        @Suppress("UNUSED_PARAMETER") classLoader: ClassLoader? = null,
        configValues: Map<String, String> = emptyMap()
    ): (String?) -> Any? {
        if (managerClassName.isNullOrBlank() || context == null) {
            return { _ -> null }
        }

        return reader@{ methodName ->
            if (methodName.isNullOrBlank()) return@reader null
            try {
                val resolved = ModManager.getInstance(context)
                    .resolveEnabledManager(modId, managerClassName)
                    ?: return@reader null
                val managerClass = resolved.managerClass
                val deviceManager = resolved.instance
                applyConfigToManager(managerClass, deviceManager, context, configValues)
                managerClass.getMethod(methodName).invoke(deviceManager)
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                Log.e(TAG, "Failed to read: $methodName for $modId", t)
                null
            }
        }
    }

    private fun invokeManagerAction(managerClass: Class<*>, deviceManager: Any, action: String) {
        try {
            val parts = action.split(":", limit = 2)
            val methodName = parts[0]
            val argStr = if (parts.size > 1) parts[1] else null

            if (argStr == null) {
                managerClass.getMethod(methodName).invoke(deviceManager)
                Log.d(TAG, "Invoked: $action")
                return
            }

            // Mods may declare setters with the natural type (boolean/int/float) or as a
            // String. Try the natural type first, then fall back to String so both
            // conventions work — a signature mismatch must never silently drop the action.
            val candidates = buildList<Pair<Class<*>, Any>> {
                when {
                    argStr == "true" || argStr == "false" ->
                        add(Boolean::class.javaPrimitiveType!! to argStr.toBoolean())
                    argStr.toIntOrNull() != null -> {
                        add(Int::class.javaPrimitiveType!! to argStr.toInt())
                        add(Float::class.javaPrimitiveType!! to argStr.toFloat())
                    }
                    argStr.toFloatOrNull() != null ->
                        add(Float::class.javaPrimitiveType!! to argStr.toFloat())
                }
                add(String::class.java to argStr)
            }

            for ((paramType, value) in candidates) {
                val method = runCatching { managerClass.getMethod(methodName, paramType) }.getOrNull()
                    ?: continue
                method.invoke(deviceManager, value)
                Log.d(TAG, "Invoked: $action")
                return
            }
            throw NoSuchMethodException("$methodName has no setter accepting '$argStr'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invoke: $action", e)
            reportManagerError(managerClass, deviceManager, "Invoke failed: $action - ${e.rootCauseMessage()}")
        }
    }
    
    private fun createRefreshableSwitch(
        modId: String,
        entity: ModEntity,
        invokeAction: (String?) -> Unit,
        readValue: (String?) -> Any?,
        registerStateListener: ((String, Any) -> Boolean)?,
        stateStore: ModStateStore?
    ): RefreshableEntity {
        val objectId = modObjectId(modId, entity.id)
        val state = MutableStateFlow(stateStore?.getSwitchState(modId, objectId) ?: false)

        val switch = SwitchEntity(
            key = objectId.hashCode(),
            name = entity.name,
            objectId = objectId,
            icon = entity.icon.ifEmpty { "mdi:toggle-switch" },
            getState = state,
            entityCategory = parseEntityCategory(entity.category),
            setState = { newState ->
                val action = if (newState) entity.on else entity.off
                invokeAction(action)
                state.value = newState
                runCatching { stateStore?.saveSwitchState(modId, objectId, newState) }
            }
        )

        val refresh: (() -> Unit)? = if (entity.read != null) {
            {
                val value = readValue(entity.read)
                when (value) {
                    is Boolean -> {
                        state.value = value
                        runCatching { stateStore?.saveSwitchState(modId, objectId, value) }
                    }
                    is Number -> {
                        val normalized = value.toInt() != 0
                        state.value = normalized
                        runCatching { stateStore?.saveSwitchState(modId, objectId, normalized) }
                    }
                    is String -> {
                        val normalized = value.equals("true", ignoreCase = true)
                                || value.equals("on", ignoreCase = true)
                                || value == "1"
                        state.value = normalized
                        runCatching { stateStore?.saveSwitchState(modId, objectId, normalized) }
                    }
                }
            }
        } else null

        refresh?.invoke()

        val callback = object : ModStateCallback() {
            override fun onStateChanged(value: Any?) {
                when (value) {
                    is Boolean -> {
                        state.value = value
                        runCatching { stateStore?.saveSwitchState(modId, objectId, value) }
                    }
                    is Number -> {
                        val normalized = value.toInt() != 0
                        state.value = normalized
                        runCatching { stateStore?.saveSwitchState(modId, objectId, normalized) }
                    }
                    is String -> {
                        val normalized = value.equals("true", ignoreCase = true)
                                || value.equals("on", ignoreCase = true)
                                || value == "1"
                        state.value = normalized
                        runCatching { stateStore?.saveSwitchState(modId, objectId, normalized) }
                    }
                }
            }
        }
        val listenerRegistered = registerStateListener?.invoke(entity.id, callback) == true
        if (listenerRegistered) {
            // Listener-based switches still do one explicit truth-read on startup so they
            // don't sit at the default/persisted value if the callback init path is late.
            refresh?.invoke()
        }

        return RefreshableEntity(switch, refresh, entity.refreshIntervalMs)
    }
    
    private fun createBinarySensor(modId: String, entity: ModEntity): BinarySensorEntity {
        val objectId = modObjectId(modId, entity.id)
        val state = MutableStateFlow(false)
        
        return BinarySensorEntity(
            key = objectId.hashCode(),
            name = entity.name,
            objectId = objectId,
            icon = entity.icon.ifEmpty { "mdi:checkbox-blank-circle" },
            deviceClass = entity.deviceClass ?: "",
            getState = state,
            entityCategory = parseEntityCategory(entity.category)
        )
    }

    private fun createRefreshableBinarySensor(
        modId: String,
        entity: ModEntity,
        readValue: (String?) -> Any?,
        registerStateListener: ((String, Any) -> Boolean)?
    ): RefreshableEntity {
        val objectId = modObjectId(modId, entity.id)
        val state = MutableStateFlow(false)

        val binarySensor = BinarySensorEntity(
            key = objectId.hashCode(),
            name = entity.name,
            objectId = objectId,
            icon = entity.icon.ifEmpty { "mdi:checkbox-blank-circle" },
            deviceClass = entity.deviceClass ?: "",
            getState = state,
            entityCategory = parseEntityCategory(entity.category)
        )

        val refresh: (() -> Unit)? = if (entity.read != null) {
            {
                val value = readValue(entity.read)
                when (value) {
                    is Boolean -> state.value = value
                    is Number -> state.value = value.toInt() != 0
                    is String -> state.value = value.equals("true", ignoreCase = true)
                            || value.equals("on", ignoreCase = true)
                            || value == "1"
                }
            }
        } else null

        refresh?.invoke()
        val callback = object : ModStateCallback() {
            override fun onStateChanged(value: Any?) {
                when (value) {
                    is Boolean -> state.value = value
                    is Number -> state.value = value.toInt() != 0
                    is String -> state.value = value.equals("true", ignoreCase = true)
                            || value.equals("on", ignoreCase = true)
                            || value == "1"
                }
            }
        }
        val listenerRegistered = registerStateListener?.invoke(entity.id, callback) == true
        if (listenerRegistered) {
            refresh?.invoke()
        }

        return RefreshableEntity(binarySensor, refresh, entity.refreshIntervalMs)
    }
    
    private fun createSensor(modId: String, entity: ModEntity, readValue: (String?) -> Any?): SensorEntity {
        return createRefreshableSensor(modId, entity, readValue, null).entity as SensorEntity
    }
    
    private fun createRefreshableSensor(
        modId: String,
        entity: ModEntity,
        readValue: (String?) -> Any?,
        registerStateListener: ((String, Any) -> Boolean)?
    ): RefreshableEntity {
        val objectId = modObjectId(modId, entity.id)
        
        val sensor = SensorEntity(
            key = objectId.hashCode(),
            name = entity.name,
            objectId = objectId,
            icon = entity.icon.ifEmpty { "mdi:gauge" },
            unitOfMeasurement = entity.unit ?: "",
            accuracyDecimals = entity.accuracyDecimals ?: 1,
            deviceClass = entity.deviceClass ?: "",
            entityCategory = parseEntityCategory(entity.category)
        )
        
        val refresh: (() -> Unit)? = if (entity.read != null) {
            {
                val value = readValue(entity.read)
                if (value is Number) {
                    sensor.updateState(value.toFloat())
                }
            }
        } else null
        
        refresh?.invoke()
        val callback = object : ModStateCallback() {
            override fun onStateChanged(value: Any?) {
                if (value is Number) {
                    sensor.updateState(value.toFloat())
                }
            }
        }
        val listenerRegistered = registerStateListener?.invoke(entity.id, callback) == true
        if (listenerRegistered) {
            refresh?.invoke()
        }
        
        return RefreshableEntity(sensor, refresh, entity.refreshIntervalMs)
    }
    
    private fun createNumber(
        modId: String,
        entity: ModEntity,
        invokeAction: (String?) -> Unit,
        readValue: (String?) -> Any?,
        registerStateListener: ((String, Any) -> Boolean)?
    ): NumberEntity {
        val objectId = modObjectId(modId, entity.id)
        val initialValue = (readValue(entity.read) as? Number)?.toFloat() ?: (entity.min ?: 0f)
        val state = MutableStateFlow(initialValue)

        val callback = object : ModStateCallback() {
            override fun onStateChanged(value: Any?) {
                if (value is Number) {
                    state.value = value.toFloat()
                }
            }
        }
        registerStateListener?.invoke(entity.id, callback)

        return NumberEntity(
            key = objectId.hashCode(),
            name = entity.name,
            objectId = objectId,
            icon = entity.icon.ifEmpty { "mdi:numeric" },
            minValue = entity.min ?: 0f,
            maxValue = entity.max ?: 100f,
            step = entity.step ?: 1f,
            unitOfMeasurement = entity.unit ?: "",
            getState = state,
            entityCategory = parseEntityCategory(entity.category),
            mode = parseNumberMode(entity.mode),
            setState = { newValue ->
                entity.set?.let { action ->
                    invokeAction("$action:${newValue.toInt()}")
                }
                state.value = newValue
            }
        )
    }
    
    private fun createButton(modId: String, entity: ModEntity, invokeAction: (String?) -> Unit): ButtonEntity {
        val objectId = modObjectId(modId, entity.id)
        
        return ButtonEntity(
            key = objectId.hashCode(),
            name = entity.name,
            objectId = objectId,
            icon = entity.icon.ifEmpty { "mdi:gesture-tap-button" },
            entityCategory = parseEntityCategory(entity.category),
            onPress = {
                invokeAction(entity.press)
            }
        )
    }

    /**
     * ESPHome camera entity for mods (JPEG pull from HA).
     *
     * Push frames by calling [registerStateListener] with a [ByteArray] JPEG, or by
     * exposing a no-arg [ModEntity.read] that returns [ByteArray].
     */
    private fun createCamera(
        modId: String,
        entity: ModEntity,
        context: Context?,
        readValue: (String?) -> Any?,
        registerStateListener: ((String, Any) -> Boolean)?,
    ): RefreshableEntity {
        val objectId = modObjectId(modId, entity.id)
        val camera = CameraEntity(
            key = objectId.hashCode(),
            name = entity.name,
            objectId = objectId,
            icon = entity.icon.ifEmpty { "mdi:monitor-screenshot" },
            entityCategory = parseEntityCategory(entity.category),
        )

        fun publish(value: Any?) {
            when (value) {
                is ByteArray -> if (value.isNotEmpty()) camera.sendImage(value)
                is String -> {
                    val file = java.io.File(value)
                    if (file.isFile && file.length() > 64L) {
                        runCatching { file.readBytes() }.getOrNull()?.let { camera.sendImage(it) }
                    }
                }
            }
        }

        if (context != null) {
            runCatching {
                camera.sendImage(
                    VideoCapture.createPlaceholderFromAsset(context, "camera_off.png", 320, 240),
                )
            }.onFailure {
                Log.w(TAG, "camera placeholder failed for $objectId", it)
            }
        }

        if (entity.read != null) {
            publish(readValue(entity.read))
        }

        val callback = object : ModStateCallback() {
            override fun onStateChanged(value: Any?) {
                publish(value)
            }
        }
        registerStateListener?.invoke(entity.id, callback)

        val refresh: (() -> Unit)? = if (entity.read != null) {
            { publish(readValue(entity.read)) }
        } else {
            null
        }
        return RefreshableEntity(camera, refresh, entity.refreshIntervalMs)
    }

    private fun createSelect(
        modId: String,
        entity: ModEntity,
        invokeAction: (String?) -> Unit,
        readValue: (String?) -> Any?,
        registerStateListener: ((String, Any) -> Boolean)?
    ): SelectEntity {
        val objectId = modObjectId(modId, entity.id)
        val options = entity.options ?: emptyList()
        val initialValue = (readValue(entity.read) as? String)
            ?: options.firstOrNull()
            ?: ""
        val state = MutableStateFlow(initialValue)

        val callback = object : ModStateCallback() {
            override fun onStateChanged(value: Any?) {
                (value as? String)?.let { state.value = it }
            }
        }
        registerStateListener?.invoke(entity.id, callback)

        return SelectEntity(
            key = objectId.hashCode(),
            name = entity.name,
            objectId = objectId,
            icon = entity.icon.ifEmpty { "mdi:format-list-bulleted" },
            options = options,
            getState = state,
            entityCategory = parseEntityCategory(entity.category),
            setState = { newValue ->
                entity.set?.let { action ->
                    invokeAction("$action:$newValue")
                }
                state.value = newValue
            }
        )
    }

    private fun createText(
        modId: String,
        entity: ModEntity,
        invokeAction: (String?) -> Unit,
        readValue: (String?) -> Any?
    ): TextEntity {
        val objectId = modObjectId(modId, entity.id)
        val initialValue = (readValue(entity.read) as? String) ?: ""
        val state = MutableStateFlow(initialValue)

        return TextEntity(
            key = objectId.hashCode(),
            name = entity.name,
            objectId = objectId,
            icon = entity.icon.ifEmpty { "mdi:form-textbox" },
            getState = state,
            entityCategory = parseEntityCategory(entity.category),
            setState = { newValue ->
                entity.set?.let { action ->
                    invokeAction("$action:$newValue")
                }
                state.value = newValue
            }
        )
    }

    private fun createTextSensor(
        modId: String,
        entity: ModEntity,
        readValue: (String?) -> Any?
    ): TextSensorEntity {
        return createRefreshableTextSensor(modId, entity, readValue, null).entity as TextSensorEntity
    }
    
    private fun createRefreshableTextSensor(
        modId: String,
        entity: ModEntity,
        readValue: (String?) -> Any?,
        registerStateListener: ((String, Any) -> Boolean)?
    ): RefreshableEntity {
        val objectId = modObjectId(modId, entity.id)
        val sensor = TextSensorEntity(
            key = objectId.hashCode(),
            name = entity.name,
            objectId = objectId,
            icon = entity.icon.ifEmpty { "mdi:text-box-outline" },
            entityCategory = parseEntityCategory(entity.category)
        )

        val refresh: () -> Unit = {
            val value = readValue(entity.read)
            when (value) {
                is String -> sensor.updateState(value)
                is Number -> sensor.updateState(value.toString())
                is Boolean -> sensor.updateState(value.toString())
            }
        }
        
        refresh()
        val callback = object : ModStateCallback() {
            override fun onStateChanged(value: Any?) {
                when (value) {
                    is String -> sensor.updateState(value)
                    is Number -> sensor.updateState(value.toString())
                    is Boolean -> sensor.updateState(value.toString())
                }
            }
        }
        val listenerRegistered = registerStateListener?.invoke(entity.id, callback) == true
        if (listenerRegistered) {
            refresh()
        }

        return RefreshableEntity(sensor, refresh, entity.refreshIntervalMs)
    }

    private fun parseEntityCategory(category: String?): EntityCategory {
        return when (category?.trim()?.lowercase()) {
            "config", "configuration" -> EntityCategory.ENTITY_CATEGORY_CONFIG
            "diagnostic", "diagnostics" -> EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC
            else -> EntityCategory.ENTITY_CATEGORY_NONE
        }
    }

    private fun parseNumberMode(mode: String?): NumberMode {
        return when (mode?.trim()?.lowercase()) {
            "slider" -> NumberMode.NUMBER_MODE_SLIDER
            "box" -> NumberMode.NUMBER_MODE_BOX
            "auto" -> NumberMode.NUMBER_MODE_AUTO
            else -> NumberMode.NUMBER_MODE_BOX
        }
    }

    private fun isEntityEnabled(entity: ModEntity, configValues: Map<String, String>): Boolean {
        // enabledByConfig: entity only enabled when config key is "true"
        val configKey = entity.enabledByConfig
        if (configKey != null) {
            val configValue = configValues[configKey]
            if (configValue != "true") {
                return false
            }
        }
        // enabledWhen: legacy support
        val key = entity.enabledWhen ?: return true
        return configValues[key]?.toBooleanStrictOrNull() ?: false
    }

    private fun applyConfigToManager(
        managerClass: Class<*>,
        deviceManager: Any,
        context: Context,
        configValues: Map<String, String>
    ) {
        if (configValues.isEmpty()) return

        val contextMethod = runCatching {
            managerClass.getMethod("applyConfig", Context::class.java, String::class.java, String::class.java)
        }.getOrNull()
        val plainMethod = runCatching {
            managerClass.getMethod("applyConfig", String::class.java, String::class.java)
        }.getOrNull()

        if (contextMethod == null && plainMethod == null) {
            return
        }

        configValues.forEach { (key, value) ->
            runCatching {
                when {
                    contextMethod != null -> contextMethod.invoke(deviceManager, context, key, value)
                    plainMethod != null -> plainMethod.invoke(deviceManager, key, value)
                }
            }.onFailure {
                Log.w(TAG, "Failed to apply config $key for ${managerClass.name}", it)
                reportManagerError(managerClass, deviceManager, "Config failed: $key - ${it.rootCauseMessage()}")
            }
        }
    }

    private fun reportManagerError(managerClass: Class<*>, deviceManager: Any, message: String) {
        runCatching {
            val method = managerClass.getMethod("setLastError", String::class.java)
            method.invoke(deviceManager, message)
        }.onFailure {
            Log.w(TAG, "Manager does not expose setLastError: ${managerClass.name}", it)
        }
    }

    private fun Throwable.rootCauseMessage(): String {
        var current: Throwable = this
        while (current.cause != null) {
            current = current.cause!!
        }
        return current.message ?: current.javaClass.simpleName
    }
}
