package com.example.ava.ui.screens.settings

import android.Manifest
import android.net.Uri
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.example.ava.ui.AvaToast
import com.example.ava.services.VoiceSatelliteService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.example.ava.ui.haptic.TickSlider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.bluetooth.BluetoothDeviceNames
import com.example.ava.bluetooth.BluetoothPresenceAlertSound
import com.example.ava.bluetooth.BluetoothPresenceManager
import com.example.ava.bluetooth.BluetoothRadioHelper
import com.example.ava.mods.ModBleAdvProxyBridge
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.ui.screens.settings.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Canvas

private const val TAG = "BluetoothSettings"

/**
 * Ava cannot declare `BLUETOOTH_SCAN` with `neverForLocation`.
 *
 * Official docs: Android 12+ can drop the location permission for BLE *only* with that flag.
 * The flag also strips advertisements Android classifies as beacons — including Apple company
 * id `0x004C` packets of the iBeacon shape. Screen-off presence for iPhones leans on exactly
 * those manufacturer ads, so taking the flag would trade a permission dialog for a silent
 * empty scan. Every other path therefore still needs while-in-use fine/coarse location:
 * Android 5–11 always did; Android 12–16 still does for Ava because the flag is off the table.
 *
 * What we deliberately do *not* ask for here: `ACCESS_BACKGROUND_LOCATION`. That used to be
 * the only way to keep a non-location FGS hearing BLE after the activity left the top; it is
 * also the permission that burned users with scary "all the time" prompts across 5–16. The
 * voice service's `location` foreground type covers the screen-off case with while-in-use
 * alone, so the tip only fires when that while-in-use grant is missing.
 */
private fun hasWhileInUseLocation(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (fine) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun ProxyInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = getSettingsDescriptionColor()
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = getTitleColor()
        )
    }
}

@Composable
private fun ProxyStatusValue(enabled: Boolean) {
    val background = if (enabled) getAccentColor().copy(alpha = 0.12f) else getSettingsDescriptionColor().copy(alpha = 0.14f)
    val contentColor = if (enabled) getAccentColor() else getSettingsDescriptionColor()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(if (enabled) R.string.settings_bluetooth_proxy_capability_enabled else R.string.settings_bluetooth_proxy_capability_disabled),
            fontSize = settingsBodyTextSize(),
            fontWeight = FontWeight.Medium,
            color = contentColor
        )
    }
}

@Composable
private fun ProxyCapabilityRow(label: String, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = getSettingsDescriptionColor()
        )
        ProxyStatusValue(enabled = enabled)
    }
}

@Composable
fun BluetoothSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val comingSoonText = stringResource(R.string.settings_coming_soon)
    
    fun showComingSoon() {
        AvaToast.show(context, comingSoonText)
    }

    fun isVoiceServiceRunning(): Boolean = VoiceSatelliteService.getInstance() != null

    fun requireVoiceServiceRunning(): Boolean {
        if (isVoiceServiceRunning()) return true
        AvaToast.show(
            context,
            context.getString(R.string.settings_bluetooth_enable_hint),
        )
        return false
    }
    
    val bluetoothManager = remember { BluetoothPresenceManager.getInstance(context) }

    val isServiceRunning by VoiceSatelliteService.isRunning.collectAsStateWithLifecycle()
    var bleAdvStandalone by remember { mutableStateOf(ModBleAdvProxyBridge.isStandalone(context)) }
    var bleAdvLegacyIntegrated by remember { mutableStateOf(ModBleAdvProxyBridge.isLegacyIntegrated(context)) }
    var whileInUseLocationGranted by remember { mutableStateOf(hasWhileInUseLocation(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                bleAdvStandalone = ModBleAdvProxyBridge.isStandalone(context)
                bleAdvLegacyIntegrated = ModBleAdvProxyBridge.isLegacyIntegrated(context)
                whileInUseLocationGranted = hasWhileInUseLocation(context)
                // Coming back from the system settings page is the one moment a location grant
                // can have landed without Ava restarting, and the running service keeps whatever
                // types it started with until something re-applies them.
                VoiceSatelliteService.getInstance()?.refreshForegroundServiceTypes()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val prefs = remember { context.getSharedPreferences("bluetooth_settings", Context.MODE_PRIVATE) }
    var detectEnabled by remember { mutableStateOf(prefs.getBoolean("detect_enabled", false)) }
    var screenOffPersistence by remember {
        mutableStateOf(
            prefs.getBoolean(
                BluetoothPresenceManager.KEY_SCREEN_OFF_PERSISTENCE,
                BluetoothPresenceManager.SCREEN_OFF_PERSISTENCE_DEFAULT,
            )
        )
    }
    var systemBluetoothOn by remember { mutableStateOf(BluetoothRadioHelper.isEnabled(context)) }
    var lastSystemBtPromptAt by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    val enableSystemBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        systemBluetoothOn = BluetoothRadioHelper.isEnabled(context)
        if (systemBluetoothOn) {
            runCatching { bluetoothManager.getProxyCapabilityReport() }
        }
    }

    fun promptSystemBluetoothEnable(force: Boolean = false) {
        if (systemBluetoothOn) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSystemBtPromptAt < 8_000L) return
        lastSystemBtPromptAt = now
        scope.launch {
            val privileged = withContext(Dispatchers.IO) {
                BluetoothRadioHelper.ensureEnabledOrNeedsUserPrompt(context)
            }
            systemBluetoothOn = BluetoothRadioHelper.isEnabled(context)
            if (privileged || systemBluetoothOn) return@launch
            runCatching {
                enableSystemBluetoothLauncher.launch(BluetoothRadioHelper.createRequestEnableIntent())
            }.onFailure {
                Log.w(TAG, "ACTION_REQUEST_ENABLE failed", it)
            }
        }
    }

    DisposableEffect(Unit) {
        systemBluetoothOn = BluetoothRadioHelper.isEnabled(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> systemBluetoothOn = true
                    BluetoothAdapter.STATE_OFF,
                    BluetoothAdapter.STATE_TURNING_OFF -> systemBluetoothOn = false
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    // Detect on + service running + radio off → request enable (privileged or system dialog).
    LaunchedEffect(detectEnabled, systemBluetoothOn, isServiceRunning) {
        if (!detectEnabled || systemBluetoothOn || !isServiceRunning) return@LaunchedEffect
        delay(400)
        if (detectEnabled && !BluetoothRadioHelper.isEnabled(context) && isVoiceServiceRunning()) {
            promptSystemBluetoothEnable(force = false)
        }
    }

    LaunchedEffect(bleAdvStandalone, bleAdvLegacyIntegrated) {
        if (bleAdvStandalone) {
            ModBleAdvProxyBridge.applyStandaloneHostPolicy(context)
            navController.popBackStack()
            return@LaunchedEffect
        }
        // Legacy integrated: ble-adv-proxy needs detect_enabled for host proxy scan.
        if (bleAdvLegacyIntegrated && !prefs.getBoolean("detect_enabled", false)) {
            detectEnabled = true
            prefs.edit().putBoolean("detect_enabled", true).apply()
            if (isVoiceServiceRunning()) {
                restartVoiceSatelliteServiceIfRunning()
            }
        }
    }
    val isLowEndBleChip = remember { bluetoothManager.isLowEndBleChip() }
    val proxyCapabilityReport = remember { bluetoothManager.getProxyCapabilityReport() }
    var showDeviceDialog by remember { mutableStateOf(false) }
    var devicePendingRemoval by remember {
        mutableStateOf<Pair<String, BluetoothPresenceManager.TrackedDevice>?>(null)
    }
    var bondedDevices by remember { mutableStateOf<List<BluetoothPresenceManager.TrackedDevice>>(emptyList()) }
    var discoveredDevices by remember { mutableStateOf<Map<String, BluetoothPresenceManager.TrackedDevice>>(emptyMap()) }
    var isScanning by remember { mutableStateOf(false) }
    
    val trackedDevices by bluetoothManager.trackedDevices.collectAsState()
    val devicePresence by bluetoothManager.devicePresence.collectAsState()
    val presenceAlertAddress by bluetoothManager.presenceAlertAddressFlow.collectAsState()
    var presenceAlertDialogDevice by remember {
        mutableStateOf<BluetoothPresenceManager.TrackedDevice?>(null)
    }
    var presenceAlertDialogAddress by remember { mutableStateOf<String?>(null) }
    var deviceSheetTarget by remember {
        mutableStateOf<Pair<String, BluetoothPresenceManager.TrackedDevice>?>(null)
    }
    var irkEditTarget by remember {
        mutableStateOf<Pair<String, BluetoothPresenceManager.TrackedDevice>?>(null)
    }
    var irkPairHintTarget by remember {
        mutableStateOf<Pair<String, BluetoothPresenceManager.TrackedDevice>?>(null)
    }
    var externalAlertSoundUri by remember { mutableStateOf<String?>(null) }
    var savedCustomAlertUris by remember {
        mutableStateOf(BluetoothPresenceAlertSound.loadSavedCustomAlertSoundUris(context))
    }

    val noneLabel = stringResource(R.string.sound_none)
    val unknownLabel = stringResource(R.string.sound_unknown)
    var presenceAlertRingtones by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val presenceAlertSystemUris = remember(presenceAlertRingtones) {
        presenceAlertRingtones.map { it.second }
    }

    LaunchedEffect(noneLabel, unknownLabel) {
        presenceAlertRingtones = runCatching {
            SystemRingtoneLoader.loadRingtones(
                context = context,
                ringtoneType = RingtoneManager.TYPE_NOTIFICATION,
                prefixEntries = listOf(noneLabel to BluetoothPresenceAlertSound.NONE_URI),
                unknownLabel = unknownLabel,
            )
        }.getOrElse { error ->
            Log.w(TAG, "Failed to load presence alert ringtones", error)
            listOf(noneLabel to BluetoothPresenceAlertSound.NONE_URI)
        }
    }

    LaunchedEffect(presenceAlertDialogDevice) {
        if (presenceAlertDialogDevice != null) {
            BluetoothPresenceAlertSound.mergeAssignedCustomAlertSoundUris(
                context = context,
                assignedUris = trackedDevices.values.map { it.alertSoundUri },
                systemRingtones = presenceAlertSystemUris,
            )
            savedCustomAlertUris = BluetoothPresenceAlertSound.loadSavedCustomAlertSoundUris(context)
        }
    }

    val presenceAlertAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            externalAlertSoundUri = uri.toString()
        }
    }
    
    
    val rssiThreshold by bluetoothManager.rssiThresholdFlow.collectAsState()
    val awayDelaySeconds by bluetoothManager.awayDelaySecondsFlow.collectAsState()

    val classicDiscoveryReceiver = remember {
        object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let {
                            val address = BluetoothDeviceNames.safeAddress(it) ?: return@let
                            val fallback = BluetoothDeviceNames.unknownDiscoveredLabel(ctx, address)
                            val name = BluetoothDeviceNames.safeName(it, fallback)
                            val trackedDevice = BluetoothPresenceManager.TrackedDevice(address, name, false)
                            discoveredDevices = discoveredDevices + (address to trackedDevice)
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        isScanning = false
                    }
                }
            }
        }
    }
    
    
    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Classic discovery results come from the system, so the receiver must be
        // exported; targetSdk 33+ throws SecurityException without an explicit flag.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(classicDiscoveryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(classicDiscoveryReceiver, filter)
        }
        onDispose {
            try {
                context.unregisterReceiver(classicDiscoveryReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister receiver", e)
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    val dialogScanCallback = remember {
        object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val address = BluetoothDeviceNames.safeAddress(result.device) ?: return
                val fallback = BluetoothDeviceNames.unknownDiscoveredLabel(context, address)
                val name = BluetoothDeviceNames.safeName(result.device, fallback)
                val device = BluetoothPresenceManager.TrackedDevice(address, name, false)
                discoveredDevices = discoveredDevices + (address to device)
            }
            
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
                isScanning = false
            }
        }
    }
    
    
    // Location stays on the S+ request list on purpose: Ava cannot use neverForLocation (Apple
    // manufacturer ads for iPhone presence would be filtered), so fine location is still what
    // unlocks scan-result delivery on 12–16. Nearby-devices alone is not enough for this app.
    val permissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else if (!bluetoothManager.isLowEndBleChip() || !com.example.ava.utils.RootUtils.isRootAvailable()) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.toTypedArray()
    
    
    fun hasRequiredBluetoothPermissions(): Boolean {
        val hasLocation = hasWhileInUseLocation(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasNearby =
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    ) == PackageManager.PERMISSION_GRANTED
            return hasNearby && hasLocation
        }
        if (!hasLocation && com.example.ava.utils.RootUtils.isRootAvailable()) {
            com.example.ava.utils.RootUtils.grantBluetoothLocationPermission(context.packageName)
            return true
        }
        return hasLocation
    }
    
    
    var shouldStartDiscovery by remember { mutableStateOf(false) }
    var shouldEnableDetect by remember { mutableStateOf(false) }
    
    
    val discoveryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            results[Manifest.permission.BLUETOOTH_SCAN] == true &&
                results[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        } else {
            results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }
        if (granted) {
            whileInUseLocationGranted = hasWhileInUseLocation(context)
            VoiceSatelliteService.getInstance()?.refreshForegroundServiceTypes()
            shouldStartDiscovery = true
        } else {
            AvaToast.show(context, "Bluetooth permission required to scan devices")
        }
    }
    
    
    val detectPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            results[Manifest.permission.BLUETOOTH_SCAN] == true &&
                results[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        } else {
            results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }
        if (granted) {
            whileInUseLocationGranted = hasWhileInUseLocation(context)
            VoiceSatelliteService.getInstance()?.refreshForegroundServiceTypes()
            shouldEnableDetect = true
        } else {
            AvaToast.show(context, "Bluetooth permission required for device detection")
        }
    }

    // Tip tap: system permission dialog only — never App Info. While-in-use fine location is
    // enough; background location is intentionally not in this request.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        whileInUseLocationGranted = hasWhileInUseLocation(context) ||
            results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (whileInUseLocationGranted) {
            VoiceSatelliteService.getInstance()?.refreshForegroundServiceTypes()
        }
    }
    
    @SuppressLint("MissingPermission")
    fun stopDeviceDiscovery() {
        try {
            bluetoothManager.getScanner()?.stopScan(dialogScanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed", e)
        }
        bluetoothManager.cancelClassicDiscovery()
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    fun startDeviceDiscovery(forceRootScan: Boolean = false) {
        bondedDevices = bluetoothManager.getBondedDevices()
        discoveredDevices = emptyMap()
        showDeviceDialog = true

        if (!hasRequiredBluetoothPermissions()) {
            return
        }

        val scanner = bluetoothManager.getScanner()
        var scanning = false

        if (scanner != null) {
            try {
                scanner.startScan(dialogScanCallback)
                scanning = true
                scope.launch {
                    delay(15000)
                    if (isScanning) {
                        stopDeviceDiscovery()
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "BLE scan denied", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start BLE scan", e)
            }
        }

        if (bluetoothManager.startClassicDiscovery()) {
            scanning = true
        }

        isScanning = scanning
    }

    fun requestBluetoothPermission() {
        if (!requireVoiceServiceRunning()) return
        if (BluetoothRadioHelper.adapter(context) == null) return
        if (!BluetoothRadioHelper.isEnabled(context)) {
            promptSystemBluetoothEnable(force = true)
            return
        }
        val isLowEndBle = bluetoothManager.isLowEndBleChip()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && isLowEndBle && com.example.ava.utils.RootUtils.isRootAvailable()) {
            Thread {
                com.example.ava.utils.RootUtils.grantBluetoothLocationPermission(context.packageName)
                android.os.Handler(context.mainLooper).post { startDeviceDiscovery() }
            }.start()
        } else if (hasRequiredBluetoothPermissions()) {
            startDeviceDiscovery()
        } else if (permissions.isNotEmpty()) {
            discoveryPermissionLauncher.launch(permissions)
        } else {
            startDeviceDiscovery()
        }
    }
    
    fun enableDetectWithPermissionCheck() {
        if (!requireVoiceServiceRunning()) return
        if (BluetoothRadioHelper.adapter(context) == null) return
        if (!BluetoothRadioHelper.isEnabled(context)) {
            promptSystemBluetoothEnable(force = true)
            return
        }
        
        val isLowEndBle = bluetoothManager.isLowEndBleChip()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && isLowEndBle && com.example.ava.utils.RootUtils.isRootAvailable()) {
            Thread {
                com.example.ava.utils.RootUtils.grantBluetoothLocationPermission(context.packageName)
            }.start()
            detectEnabled = true
            prefs.edit().putBoolean("detect_enabled", true).apply()
            restartVoiceSatelliteServiceIfRunning()
        } else if (hasRequiredBluetoothPermissions()) {
            detectEnabled = true
            prefs.edit().putBoolean("detect_enabled", true).apply()
            restartVoiceSatelliteServiceIfRunning()
        } else if (permissions.isNotEmpty()) {
            detectPermissionLauncher.launch(permissions)
        } else {
            detectEnabled = true
            prefs.edit().putBoolean("detect_enabled", true).apply()
            restartVoiceSatelliteServiceIfRunning()
        }
    }
    
    
    LaunchedEffect(shouldStartDiscovery) {
        if (shouldStartDiscovery) {
            if (requireVoiceServiceRunning()) {
                startDeviceDiscovery()
            }
            shouldStartDiscovery = false
        }
    }
    
    LaunchedEffect(shouldEnableDetect) {
        if (shouldEnableDetect) {
            if (requireVoiceServiceRunning()) {
                detectEnabled = true
                prefs.edit().putBoolean("detect_enabled", true).apply()
                restartVoiceSatelliteServiceIfRunning()
            }
            shouldEnableDetect = false
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            if (isScanning) {
                stopDeviceDiscovery()
            }
        }
    }
    
    SettingsDetailScreen(
        navController = navController,
        title = stringResource(R.string.settings_group_bluetooth)
    ) {
        // Standalone top tip — not a card. Only when detect is on and while-in-use location
        // is missing. Never asks for "all the time"; that was the 5–16 user-friction trap.
        if (detectEnabled && !whileInUseLocationGranted) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        }
                        .padding(top = 4.dp, bottom = 10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_bluetooth_location_scan_hint),
                        fontSize = settingsBodyTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFF9800),
                    )
                    CollapsibleDescriptionText(
                        text = stringResource(R.string.settings_bluetooth_location_scan_hint_desc),
                        collapsedLines = 2,
                        fontSize = settingsBodyTextSize(),
                        lineHeight = settingsBodyLineHeight(),
                        color = getSettingsDescriptionColor(),
                        topPadding = settingsDescriptionTopPadding(),
                    )
                }
            }
        }

        if (isLowEndBleChip) {
            item {
                CollapsibleSettingsNote(
                    title = stringResource(R.string.bluetooth_lowend_warning_title),
                    content = stringResource(R.string.bluetooth_lowend_warning_content),
                    leading = {
                        Icon(
                            painter = painterResource(R.drawable.ic_bluetooth_warning),
                            contentDescription = null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        
        item {
            SimpleCard {
                SettingRow(
                    label = stringResource(R.string.settings_bluetooth_detect),
                    subLabel = stringResource(R.string.settings_bluetooth_detect_desc)
                ) {
                    ModernSwitch(
                        checked = detectEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                enableDetectWithPermissionCheck()
                            } else {
                                detectEnabled = false
                                prefs.edit().putBoolean("detect_enabled", false).apply()
                                bluetoothManager.clearAllPresence()
                                bluetoothManager.stopAdvertising()
                                restartVoiceSatelliteServiceIfRunning()
                            }
                        }
                    )
                }
                
                // Deliberately not gated on !isLowEndBleChip: the shared-radio chips are the ones
                // whose ROMs cut the scanner off after a long screen-off, so excluding them would
                // hide this from exactly the devices that need it.
                if (detectEnabled) {
                    SettingsDivider()

                    SettingRow(
                        label = stringResource(R.string.settings_bluetooth_screen_off_persistence),
                        subLabel = stringResource(
                            R.string.settings_bluetooth_screen_off_persistence_desc
                        ),
                    ) {
                        ModernSwitch(
                            checked = screenOffPersistence,
                            onCheckedChange = { enabled ->
                                screenOffPersistence = enabled
                                prefs.edit()
                                    .putBoolean(
                                        BluetoothPresenceManager.KEY_SCREEN_OFF_PERSISTENCE,
                                        enabled,
                                    )
                                    .apply()
                            },
                        )
                    }
                }

                if (detectEnabled && !isLowEndBleChip && !bleAdvLegacyIntegrated) {
                    SettingsDivider()
                    
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SettingSliderLabelRow(
                            title = stringResource(R.string.settings_bluetooth_rssi_threshold),
                            description = stringResource(R.string.settings_bluetooth_rssi_threshold_desc),
                            badgeText = "${rssiThreshold}dBm",
                        )
                        TickSlider(
                            value = (-rssiThreshold).toFloat(),
                            onValueChange = { newValue ->
                                bluetoothManager.rssiThreshold = -(newValue.toInt() / 10 * 10)
                            },
                            valueRange = 0f..120f,
                            steps = 11,
                            colors = SliderDefaults.colors(
                                thumbColor = getAccentColor(),
                                activeTrackColor = getAccentColor(),
                                inactiveTrackColor = getSliderInactiveColor(),
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent
                            ),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    
                    SettingsDivider()
                    
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SettingSliderLabelRow(
                            title = stringResource(R.string.settings_bluetooth_away_delay),
                            description = stringResource(R.string.settings_bluetooth_away_delay_desc),
                            badgeText = "${awayDelaySeconds}s",
                        )
                        TickSlider(
                            value = awayDelaySeconds.toFloat(),
                            onValueChange = { bluetoothManager.awayDelaySeconds = it.toInt() },
                            valueRange = 7f..300f,
                            steps = 58,
                            colors = SliderDefaults.colors(
                                thumbColor = getAccentColor(),
                                activeTrackColor = getAccentColor(),
                                inactiveTrackColor = getSliderInactiveColor(),
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent
                            ),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    
                }
            }
        }


        if (detectEnabled && !isLowEndBleChip && !bleAdvLegacyIntegrated) {
            item {
                SettingsSectionLabel(stringResource(R.string.settings_bluetooth_tracked_devices))
            }
            
            item {
                SettingsDisabledOverlay(
                    disabled = !isServiceRunning,
                    hint = stringResource(R.string.settings_bluetooth_enable_hint),
                ) {
                    SimpleCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (interactionsEnabled) {
                                        Modifier.clickable { requestBluetoothPermission() }
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.bluetooth_24px),
                                contentDescription = null,
                                tint = getAccentColor(),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.settings_bluetooth_add_device),
                                fontSize = settingsTitleTextSize(),
                                fontWeight = FontWeight.Medium,
                                color = getAccentColor()
                            )
                        }

                        CollapsibleDescriptionText(
                            text = stringResource(R.string.settings_bluetooth_add_device_hint),
                            modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
                            fontSize = settingsBodyTextSize(),
                            lineHeight = settingsBodyLineHeight(),
                            topPadding = 0.dp,
                        )

                        if (trackedDevices.isNotEmpty()) {
                            SettingsDivider()
                        }

                        trackedDevices.entries.forEachIndexed { index, (address, device) ->
                            val isPresent = devicePresence[address] ?: false
                            val isUnknownDevice =
                                BluetoothDeviceNames.isUnknownDeviceLabel(context, device.name)
                            val unknownDeviceColor = getSlateMutedColor()

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = interactionsEnabled) {
                                        deviceSheetTarget = address to device
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(
                                            if (isPresent) Color(0xFF22C55E) else Color(0xFFEF4444)
                                        )
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.name,
                                        fontSize = settingsTitleTextSize(),
                                        fontWeight = FontWeight.Medium,
                                        color = if (isUnknownDevice) {
                                            unknownDeviceColor
                                        } else {
                                            getTitleColor()
                                        }
                                    )
                                    Text(
                                        text = buildString {
                                            append(
                                                if (isPresent) {
                                                    stringResource(R.string.settings_bluetooth_nearby)
                                                } else {
                                                    stringResource(R.string.settings_bluetooth_not_nearby)
                                                }
                                            )
                                            append(" · ")
                                            append(
                                                if (device.irk.isNotBlank()) {
                                                    stringResource(
                                                        R.string.settings_bluetooth_irk_configured
                                                    )
                                                } else {
                                                    stringResource(R.string.settings_bluetooth_mac_mode)
                                                }
                                            )
                                        },
                                        fontSize = settingsBodyTextSize(),
                                        color = getSettingsDescriptionColor()
                                    )
                                }

                                SettingsChevronIcon(
                                    tint = Color(0xFF94A3B8),
                                    base = 20f,
                                    contentDescription = stringResource(
                                        R.string.settings_bluetooth_device_sheet_open_cd
                                    ),
                                )
                            }

                            if (index < trackedDevices.size - 1) {
                                SettingsDivider()
                            }
                        }
                    }
                }
            }
        }
        
        if (detectEnabled || bleAdvLegacyIntegrated) {
            item {
                SettingsSectionLabel(stringResource(R.string.settings_bluetooth_proxy))
            }
            
            item {
                    val deviceName = remember { Build.MODEL }
                    val bluetoothAdapterMac = remember {
                        VoiceSatelliteSettingsStore(context.voiceSatelliteSettingsStore)
                            .getCached()
                            .bluetoothMacAddress
                    }
                    val proxyScanMode by bluetoothManager.proxyScanModeFlow.collectAsState()
                    
                    SimpleCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(getAccentColor()),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_bluetooth_proxy_24px),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.settings_bluetooth_proxy_title),
                                fontSize = settingsTitleTextSize(),
                                fontWeight = FontWeight.SemiBold,
                                color = getTitleColor()
                            )
                            Text(
                                text = stringResource(R.string.settings_bluetooth_proxy_desc),
                                fontSize = settingsBodyTextSize(),
                                color = getLabelColor()
                            )
                        }
                    }
                    
                    SettingsDivider()
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        ProxyInfoRow(stringResource(R.string.settings_bluetooth_proxy_device_name), deviceName)
                        if (bluetoothAdapterMac.isNotBlank()) {
                            ProxyInfoRow(
                                stringResource(R.string.settings_bluetooth_proxy_mac_address),
                                bluetoothAdapterMac,
                            )
                        }
                        val scanModeOptions = listOf("auto", "active", "passive")
                        val scanModeLabels = mapOf(
                            "auto" to stringResource(R.string.settings_bluetooth_proxy_scan_auto),
                            "active" to stringResource(R.string.settings_bluetooth_proxy_scan_active),
                            "passive" to stringResource(R.string.settings_bluetooth_proxy_scan_passive)
                        )
                        var scanModeExpanded by remember { mutableStateOf(false) }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_bluetooth_proxy_scan_mode),
                                fontSize = settingsBodyTextSize(),
                                color = getLabelColor()
                            )
                            Box {
                                Text(
                                    text = scanModeLabels[proxyScanMode] ?: scanModeLabels["auto"]!!,
                                    fontSize = settingsBodyTextSize(),
                                    fontWeight = FontWeight.Medium,
                                    color = getAccentColor(),
                                    modifier = Modifier.clickable { scanModeExpanded = true }
                                )
                                ThemedDropdownMenu(
                                    expanded = scanModeExpanded,
                                    onDismissRequest = { scanModeExpanded = false },
                                ) {
                                    scanModeOptions.forEach { key ->
                                        ThemedDropdownMenuItem(
                                            text = scanModeLabels[key] ?: key,
                                            selected = key == proxyScanMode,
                                            onClick = {
                                                bluetoothManager.proxyScanMode = key
                                                scanModeExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val proxyScanPower by bluetoothManager.proxyScanPowerFlow.collectAsState()
                        val powerOptions = listOf("low", "balanced", "high")
                        val powerLabels = mapOf(
                            "high" to stringResource(R.string.bluetooth_scan_power_high),
                            "balanced" to stringResource(R.string.bluetooth_scan_power_balanced),
                            "low" to stringResource(R.string.bluetooth_scan_power_low)
                        )
                        var powerExpanded by remember { mutableStateOf(false) }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_bluetooth_proxy_scan_power),
                                fontSize = settingsBodyTextSize(),
                                color = getLabelColor()
                            )
                            Box {
                                Text(
                                    text = powerLabels[proxyScanPower] ?: powerLabels["low"]!!,
                                    fontSize = settingsBodyTextSize(),
                                    fontWeight = FontWeight.Medium,
                                    color = getAccentColor(),
                                    modifier = Modifier.clickable { powerExpanded = true }
                                )
                                ThemedDropdownMenu(
                                    expanded = powerExpanded,
                                    onDismissRequest = { powerExpanded = false },
                                ) {
                                    powerOptions.forEach { key ->
                                        ThemedDropdownMenuItem(
                                            text = powerLabels[key] ?: key,
                                            selected = key == proxyScanPower,
                                            onClick = {
                                                bluetoothManager.proxyScanPower = key
                                                powerExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    }
                }

            item {
                SimpleCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(getAccentColor()),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.mdi_matrix_arrow_up),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_bluetooth_proxy_capability_title),
                            fontSize = settingsTitleTextSize(),
                            fontWeight = FontWeight.SemiBold,
                            color = getTitleColor()
                        )
                        Text(
                            text = stringResource(R.string.settings_bluetooth_proxy_capability_desc),
                            fontSize = settingsBodyTextSize(),
                            color = getSettingsDescriptionColor()
                        )
                    }
                }

                SettingsDivider()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    ProxyInfoRow(
                        stringResource(R.string.settings_bluetooth_proxy_capability_mode),
                        stringResource(
                            if (proxyCapabilityReport.compatibilityMode) {
                                R.string.settings_bluetooth_proxy_capability_mode_compatibility
                            } else {
                                R.string.settings_bluetooth_proxy_capability_mode_full
                            }
                        )
                    )
                    ProxyInfoRow(
                        stringResource(R.string.settings_bluetooth_proxy_capability_tier),
                        stringResource(
                            when (proxyCapabilityReport.tier) {
                                BluetoothPresenceManager.ProxyCapabilityTier.FULL -> R.string.settings_bluetooth_proxy_capability_tier_full
                                BluetoothPresenceManager.ProxyCapabilityTier.BALANCED -> R.string.settings_bluetooth_proxy_capability_tier_balanced
                                BluetoothPresenceManager.ProxyCapabilityTier.COMPATIBILITY -> R.string.settings_bluetooth_proxy_capability_tier_compatibility
                            }
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ProxyCapabilityRow(
                        stringResource(R.string.settings_bluetooth_proxy_capability_offloaded_filtering),
                        proxyCapabilityReport.offloadedFilteringSupported
                    )
                    ProxyCapabilityRow(
                        stringResource(R.string.settings_bluetooth_proxy_capability_multiple_advertisement),
                        proxyCapabilityReport.multipleAdvertisementSupported
                    )
                    ProxyCapabilityRow(
                        stringResource(R.string.settings_bluetooth_proxy_capability_extended_advertising),
                        proxyCapabilityReport.leExtendedAdvertisingSupported
                    )
                    ProxyCapabilityRow(
                        stringResource(R.string.settings_bluetooth_proxy_capability_le_coded_phy),
                        proxyCapabilityReport.leCodedPhySupported
                    )
                    ProxyCapabilityRow(
                        stringResource(R.string.settings_bluetooth_proxy_capability_le_2m_phy),
                        proxyCapabilityReport.le2MPhySupported
                    )
                }
                }
            }
        }
    }
        
        
        devicePendingRemoval?.let { (address, device) ->
            AlertDialog(
                onDismissRequest = { devicePendingRemoval = null },
                shape = RoundedCornerShape(20.dp),
                containerColor = getDialogBackground(),
                title = {
                    Text(
                        text = stringResource(R.string.settings_bluetooth_remove_device),
                        fontWeight = FontWeight.Bold,
                        fontSize = settingsTitleTextSize(),
                        color = getTitleColor(),
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.settings_bluetooth_remove_device_confirm, device.name),
                        fontSize = settingsBodyTextSize(),
                        color = getLabelColor()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        devicePendingRemoval = null
                        bluetoothManager.removeTrackedDevice(address)
                        restartVoiceSatelliteServiceIfRunning()
                    }) {
                        Text(
                            text = stringResource(R.string.settings_bluetooth_remove_device),
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold,
                            fontSize = settingsTitleTextSize()
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { devicePendingRemoval = null }) {
                        Text(
                            text = stringResource(R.string.label_cancel),
                            color = getLabelColor(),
                            fontSize = settingsTitleTextSize()
                        )
                    }
                }
            )
        }

        if (showDeviceDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeviceDialog = false
                stopDeviceDiscovery()
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground(),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_bluetooth_select_device),
                        fontWeight = FontWeight.Bold,
                        fontSize = settingsTitleTextSize(),
                        color = getTitleColor(),
                    )
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = getAccentColor()
                        )
                    }
                }
            },
            text = {
                val allDevices = (bondedDevices.associateBy { it.address } + discoveredDevices).values.toList()
                val mutedColor = getLabelColor()
                
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    if (allDevices.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(if (isScanning) R.string.settings_bluetooth_searching else R.string.settings_bluetooth_no_device),
                                fontSize = settingsBodyTextSize(),
                                color = mutedColor,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        items(allDevices) { device ->
                            val isAlreadyTracked = trackedDevices.containsKey(device.address)
                            val isBonded = bondedDevices.any { it.address == device.address }
                            val isUnknownDevice = BluetoothDeviceNames.isUnknownDeviceLabel(context, device.name)
                            val unknownDeviceColor = getSlateMutedColor()
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isAlreadyTracked) {
                                        bluetoothManager.addTrackedDevice(device)
                                        showDeviceDialog = false
                                        stopDeviceDiscovery()
                                        restartVoiceSatelliteServiceIfRunning()
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (isBonded) R.drawable.bluetooth_24px else R.drawable.mdi_bluetooth,
                                    ),
                                    contentDescription = null,
                                    tint = when {
                                        isAlreadyTracked -> mutedColor
                                        isUnknownDevice -> unknownDeviceColor
                                        isBonded -> getAccentColor()
                                        else -> mutedColor
                                    },
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = device.name,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = settingsTitleTextSize(),
                                        color = when {
                                            isAlreadyTracked -> mutedColor
                                            isUnknownDevice -> unknownDeviceColor
                                            else -> getTitleColor()
                                        }
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (isAlreadyTracked) stringResource(R.string.settings_bluetooth_already_added) else device.address,
                                            fontSize = settingsBodyTextSize(),
                                            color = mutedColor
                                        )
                                        if (isBonded) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "• " + stringResource(R.string.settings_bluetooth_paired),
                                                fontSize = settingsBodyTextSize(),
                                                color = getAccentColor().copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeviceDialog = false
                    stopDeviceDiscovery()
                }) {
                    Text(
                        text = stringResource(R.string.label_cancel),
                        fontSize = settingsTitleTextSize(),
                        color = getLabelColor(),
                    )
                }
            }
        )
    }

    presenceAlertDialogDevice?.let { device ->
        val dialogAddress = presenceAlertDialogAddress ?: device.address
        BluetoothPresenceAlertDialog(
            currentSoundUri = device.alertSoundUri,
            currentTrigger = device.alertTrigger,
            context = context,
            ringtones = presenceAlertRingtones,
            savedCustomUris = savedCustomAlertUris,
            externalSoundUri = externalAlertSoundUri,
            onExternalSoundUriConsumed = { externalAlertSoundUri = null },
            onRememberCustomUri = { uri ->
                BluetoothPresenceAlertSound.rememberCustomAlertSoundUri(context, uri)
                savedCustomAlertUris = BluetoothPresenceAlertSound.loadSavedCustomAlertSoundUris(context)
            },
            onRemoveSavedCustomUri = { uri ->
                BluetoothPresenceAlertSound.removeSavedCustomAlertSoundUri(context, uri)
                savedCustomAlertUris = BluetoothPresenceAlertSound.loadSavedCustomAlertSoundUris(context)
            },
            onDismiss = {
                presenceAlertDialogDevice = null
                presenceAlertDialogAddress = null
            },
            onConfirm = { soundUri, trigger ->
                bluetoothManager.updateDevicePresenceAlertSettings(dialogAddress, soundUri, trigger)
                if (soundUri.isBlank()) {
                    bluetoothManager.clearPresenceAlertAddressIfMatches(dialogAddress)
                } else {
                    bluetoothManager.setPresenceAlertAddress(dialogAddress)
                }
                presenceAlertDialogDevice = null
                presenceAlertDialogAddress = null
            },
            onSelectExternal = {
                presenceAlertAudioLauncher.launch("audio/*")
            },
        )
    }

    deviceSheetTarget?.let { (address, _) ->
        val liveDevice = trackedDevices[address]
        if (liveDevice == null) {
            LaunchedEffect(address) { deviceSheetTarget = null }
        } else {
            val isAlertSelected = presenceAlertAddress == address
            val isAlertActive = isAlertSelected && liveDevice.alertSoundUri.isNotBlank()
            BluetoothTrackedDeviceSheet(
                device = liveDevice,
                isAlertActive = isAlertActive,
                onDismiss = { deviceSheetTarget = null },
                onPresenceAlert = {
                    presenceAlertDialogAddress = address
                    presenceAlertDialogDevice = liveDevice
                },
                onEditIrk = {
                    irkEditTarget = address to liveDevice
                },
                onRetryAutoIrk = {
                    if (!bluetoothManager.canAutoReadIrkFromBondStore()) {
                        AvaToast.show(
                            context,
                            context.getString(R.string.settings_bluetooth_irk_need_root),
                        )
                    } else if (!bluetoothManager.isAddressBonded(address)) {
                        irkPairHintTarget = address to liveDevice
                    } else {
                        bluetoothManager.tryAttachIrkFromBondStoreAsync(address)
                        AvaToast.show(
                            context,
                            context.getString(R.string.settings_bluetooth_irk_retry_started),
                        )
                    }
                },
                onRemove = {
                    devicePendingRemoval = address to liveDevice
                },
            )
        }
    }

    irkPairHintTarget?.let { (address, _) ->
        AlertDialog(
            onDismissRequest = { irkPairHintTarget = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground(),
            title = {
                Text(
                    text = stringResource(R.string.settings_bluetooth_irk_need_pair_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor(),
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_bluetooth_irk_need_pair_message),
                    fontSize = settingsBodyTextSize(),
                    color = getLabelColor(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        irkPairHintTarget = null
                        bluetoothManager.requestBond(address)
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_bluetooth_irk_need_pair_action),
                        color = getAccentColor(),
                        fontWeight = FontWeight.Bold,
                        fontSize = settingsTitleTextSize(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { irkPairHintTarget = null }) {
                    Text(
                        text = stringResource(R.string.label_cancel),
                        color = getLabelColor(),
                        fontSize = settingsTitleTextSize(),
                    )
                }
            },
        )
    }

    irkEditTarget?.let { (address, device) ->
        val liveDevice = trackedDevices[address] ?: device
        BluetoothIrkEditDialog(
            initialIrk = liveDevice.irk,
            hasExistingIrk = liveDevice.irk.isNotBlank(),
            onDismiss = { irkEditTarget = null },
            onSave = { irkInput ->
                val ok = bluetoothManager.updateTrackedDeviceIrk(address, irkInput)
                if (ok) {
                    AvaToast.show(
                        context,
                        context.getString(R.string.settings_bluetooth_irk_saved),
                    )
                }
                ok
            },
            onClear = {
                bluetoothManager.updateTrackedDeviceIrk(address, "")
                    AvaToast.show(
                        context,
                        context.getString(R.string.settings_bluetooth_irk_cleared),
                    )
            },
        )
    }
}
