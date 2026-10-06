package com.example.ava.utils

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.example.ava.R
import com.example.ava.mods.ModDeviceSupport
import com.example.ava.receiver.DeviceAdminReceiver
import com.example.esphomeproto.api.LockCommand
import com.example.esphomeproto.api.LockState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object ScreenControlUtils {
    private const val TAG = "ScreenControlUtils"
    private const val WAKE_TAG = "Ava:ScreenWake"
    private const val WAKE_DURATION_MS = 5_000L
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1002
    private const val BRIGHTEN_STEPS = 2
    private const val BRIGHTEN_STEP_DELAY_MS = 20L
    /** Wake is 2 fast steps; going dark is the direction a user watches, so it gets a fade. */
    private const val DIM_STEPS = 12
    private const val DIM_STEP_DELAY_MS = 25L
    private var cachedBacklightBrightness: Int = 128
    private val backlightRampExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Ava-BacklightRamp").apply { isDaemon = true }
    }
    /** Bumped by every wake, so a dim ramp still running in the shell can tell it was overtaken. */
    private val backlightWakeEpoch = AtomicInteger(0)
    @Volatile private var lastPermissionRequestAt = 0L
    @Volatile private var stateInitialized = false
    @Volatile private var deviceAdminUiChecked = false
    @Volatile private var deviceAdminUiAvailable = false
    @Volatile private var unprivilegedDpmAttempted = false

    /**
     * Screen state has two dimensions that move independently, and conflating them is why
     * a blanked panel used to report itself back as "on":
     *
     *  - panel power (`SurfaceControl.setDisplayPowerMode`) blanks the display while the
     *    device stays awake. Measured on vivo OriginOS: `powerMode=Off`, yet
     *    `mWakefulness=Awake`, `Display Power: state=ON`, `isInteractive == true`, and the
     *    platform emits no ACTION_SCREEN_OFF at all.
     *  - platform wakefulness (sleep + keyguard) is what PowerManager and the screen
     *    broadcasts actually report.
     *
     * So [panelOnState] can only ever be our own bookkeeping — no Android API exposes it to
     * an app — while [deviceAwakeState] is the one PowerManager can be trusted for.
     */
    private val _panelOnState = MutableStateFlow(true)
    val panelOnState: StateFlow<Boolean> = _panelOnState

    private val _deviceAwakeState = MutableStateFlow(false)
    val deviceAwakeState: StateFlow<Boolean> = _deviceAwakeState

    /** Lock entity state: driven by wakefulness/keyguard only, never by panel power. */
    val lockState = _deviceAwakeState.map { awake ->
        if (awake) LockState.LOCK_STATE_UNLOCKED else LockState.LOCK_STATE_LOCKED
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        return dpm.isAdminActive(admin)
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun deviceAdminComponent(context: Context): ComponentName =
        ComponentName(context, DeviceAdminReceiver::class.java)

    /**
     * Device Admin is worth holding on *every* device, not just the ones without a privileged
     * shell, so this only asks whether we still lack it.
     *
     * The old rule skipped acquisition whenever root/Shizuku worked, which inverted the
     * durability of the two capabilities: a shell is the more powerful one but the least
     * permanent (Shizuku in ADB mode dies on reboot), while Device Admin is narrower —
     * `lockNow()` and nothing else — but permanent once granted. Suppressing it while a shell
     * happened to work meant the lock entity lost its only shell-free path at the exact moment
     * it was cheapest to acquire.
     */
    fun shouldRequestDeviceAdmin(context: Context): Boolean = !isDeviceAdminActive(context)

    /** Whether the system exposes an Activity for [DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN]. */
    fun canShowDeviceAdminUi(context: Context): Boolean {
        if (deviceAdminUiChecked) return deviceAdminUiAvailable
        val intent = buildDeviceAdminIntent(context)
        val resolved = runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                ) != null
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
            }
        }.getOrDefault(false)
        deviceAdminUiChecked = true
        deviceAdminUiAvailable = resolved
        if (!resolved) {
            Log.w(TAG, "Device admin activation UI not available on this device")
        }
        return resolved
    }

    /**
     * Activate Device Admin with `dpm set-active-admin`, silently and without any dialog.
     *
     * `MANAGE_DEVICE_ADMINS` is held by `com.android.shell`, not by us, so this only works
     * when routed through a *privileged* shell. Root or Shizuku is therefore the channel that
     * makes it succeed — the opposite of the old rule, which ran `dpm` under the app's own uid
     * and only bothered on devices that had no shell at all. Trading the perishable capability
     * for the permanent one is the whole point: after this, locking keeps working even once
     * Shizuku is gone.
     *
     * The unprivileged attempt is kept as a last try, since a few stripped ROMs do expose
     * `dpm` to app uids.
     */
    fun tryActivateDeviceAdminViaShell(context: Context, force: Boolean = false): Boolean {
        if (isDeviceAdminActive(context)) return true
        if (unprivilegedDpmAttempted && !force) return false
        if (!force) unprivilegedDpmAttempted = true

        val component = deviceAdminComponent(context).flattenToString()
        val commands = buildList {
            add("dpm set-active-admin $component")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add("dpm set-active-admin --user current $component")
            }
        }
        for (command in commands) {
            if (runPrivilegedShell(command) && isDeviceAdminActive(context)) {
                Log.i(TAG, "Device admin activated via privileged shell")
                return true
            }
            if (runUnprivilegedShell(command) && isDeviceAdminActive(context)) {
                Log.i(TAG, "Device admin activated via unprivileged shell")
                return true
            }
        }
        Log.d(TAG, "dpm activation did not succeed")
        return false
    }

    /**
     * Acquire Device Admin if we lack it: silent shell first, system dialog only if that fails.
     * Safe to call from feature-enable paths; throttled so it cannot spam the dialog.
     */
    fun ensureDeviceAdmin(context: Context): Boolean {
        if (isDeviceAdminActive(context)) return true
        if (tryActivateDeviceAdminViaShell(context)) return true
        return requestDeviceAdmin(context)
    }

    /**
     * Silent shell activation → system UI (if any). Never throws.
     * @return true when device admin is already active or shell activation succeeded
     */
    fun requestDeviceAdmin(context: Context, forceShellRetry: Boolean = false): Boolean {
        if (isDeviceAdminActive(context)) return true
        if (tryActivateDeviceAdminViaShell(context, force = forceShellRetry)) return true
        if (!shouldLaunchPermissionFlow()) return false
        if (!canShowDeviceAdminUi(context)) return false
        runCatching {
            context.startActivity(buildDeviceAdminIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { e ->
            Log.w(TAG, "Failed to open device admin UI", e)
        }
        return isDeviceAdminActive(context)
    }

    /**
     * Activity variant for hosts that need a result callback after the user confirms.
     * Never throws.
     */
    fun requestDeviceAdminWithActivity(
        activity: Activity,
        launcher: ActivityResultLauncher<Intent>,
        forceShellRetry: Boolean = false
    ): Boolean {
        if (isDeviceAdminActive(activity)) return true
        if (tryActivateDeviceAdminViaShell(activity, force = forceShellRetry)) return true
        if (!shouldLaunchPermissionFlow()) return false
        if (!canShowDeviceAdminUi(activity)) return false
        return runCatching {
            launcher.launch(buildDeviceAdminIntent(activity))
            true
        }.onFailure { e ->
            Log.w(TAG, "Failed to launch device admin UI", e)
            false
        }.getOrDefault(false)
    }

    fun buildDeviceAdminIntent(context: Context): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(context, DeviceAdminReceiver::class.java)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.screen_control_permission_desc)
            )
        }
    }

    /**
     * Panel power only: the display blanks while the device stays awake, so no keyguard
     * appears and foreground work keeps running. A real lock is [lockScreen].
     *
     * Off prefers a privileged shell (`setDisplayPowerMode` / backlight sysfs). When that
     * channel is missing, an enabled device mod may turn the panel off via
     * [ModDeviceSupport.trySetScreenPower]. This method itself still does not call [sleepScreen]:
     * a global `lockNow()` would perform the lock entity's action on every other device.
     * If that mod is present and cannot sleep the panel, the screen stays on. [ScreenBlankOverlay]
     * is only for devices with no mod hook, because its brightness-1 write leaves some panels lit.
     */
    fun setScreenOn(context: Context, screenOn: Boolean): Boolean {
        syncScreenState(context)
        val app = context.applicationContext
        if (screenOn) {
            if (ScreenBlankOverlay.isShowing()) ScreenBlankOverlay.hide()
            if (RootUtils.isRootAvailable() && setRootScreenState(context, true)) return true
            if (setPrivilegedDisplayPower(context, true)) return true
            if (ModDeviceSupport.trySetScreenPower(app, true)) {
                _panelOnState.value = true
                val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm?.isInteractive == true) _deviceAwakeState.value = true
                return true
            }
            _panelOnState.value = true
            return wakeScreen(context).also { woke -> if (woke) _deviceAwakeState.value = true }
        }
        if (RootUtils.isRootAvailable() && setRootScreenState(context, false)) {
            if (ScreenBlankOverlay.isShowing()) ScreenBlankOverlay.hide()
            return true
        }
        if (setPrivilegedDisplayPower(context, false)) {
            if (ScreenBlankOverlay.isShowing()) ScreenBlankOverlay.hide()
            return true
        }
        if (ModDeviceSupport.hasSetScreenPowerHook(app)) {
            if (!isDeviceAdminActive(app)) {
                ensureDeviceAdmin(app)
            }
            if (isDeviceAdminActive(app) && ModDeviceSupport.trySetScreenPower(app, false)) {
                if (ScreenBlankOverlay.isShowing()) ScreenBlankOverlay.hide()
                _panelOnState.value = false
                return true
            }
            if (ScreenBlankOverlay.isShowing()) ScreenBlankOverlay.hide()
            if (!isDeviceAdminActive(app)) {
                Log.w(TAG, "Panel stayed on: device admin is not active, so brightness was left unchanged")
            } else {
                Log.w(TAG, "Panel stayed on: device mod did not sleep the display, so brightness was left unchanged")
            }
            return false
        }
        if (ScreenBlankOverlay.show(app)) {
            _panelOnState.value = false
            return true
        }
        Log.w(TAG, "Cannot blank panel: no privileged shell (${panelPowerBackend() ?: "none"}) and overlay failed")
        return false
    }

    /** Which channel, if any, can drive panel power right now. Null means the switch cannot work. */
    fun panelPowerBackend(): String? = when {
        RootUtils.isRootAvailable() -> "root"
        ShizukuUtils.isPrivilegedShellUsable() -> "shizuku"
        else -> null
    }

    suspend fun handleLockCommand(context: Context, command: LockCommand, code: String? = null) {
        when (command) {
            LockCommand.LOCK_LOCK -> lockScreen(context)
            LockCommand.LOCK_UNLOCK, LockCommand.LOCK_OPEN -> unlockScreen(context, code)
            else -> Unit
        }
    }
    
    fun unlockScreen(context: Context, code: String? = null): Boolean {
        if (RootUtils.isRootAvailable()) {
            return runCatching {
                Runtime.getRuntime()
                    .exec(arrayOf("su", "-c", "input keyevent 26"))
                    .waitFor()
                Thread.sleep(200)
                Runtime.getRuntime()
                    .exec(arrayOf("su", "-c", "wm dismiss-keyguard"))
                    .waitFor()
                markUnlocked()
                true
            }.getOrDefault(false)
        }
        if (ShizukuUtils.isShizukuPermissionGranted()) {
            return runCatching {
                ShizukuUtils.executeCommand("input keyevent 26")
                Thread.sleep(200)
                ShizukuUtils.executeCommand("wm dismiss-keyguard")
                markUnlocked()
                true
            }.getOrDefault(false)
        }
        wakeScreen(context)
        runCatching {
            val intent = Intent(context, com.example.ava.UnlockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
                putExtra("unlock", true)
            }
            context.startActivity(intent)
            markUnlocked()
        }
        return true
    }

    private fun markUnlocked() {
        if (ScreenBlankOverlay.isShowing()) ScreenBlankOverlay.hide()
        _deviceAwakeState.value = true
        _panelOnState.value = true
    }

    fun wakeScreen(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val flags = PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE
        val wakeLock = pm.newWakeLock(flags, WAKE_TAG)
        wakeLock.acquire(WAKE_DURATION_MS)
        true
    }.getOrDefault(false)

    /**
     * `DevicePolicyManager.lockNow()`, attempted only when admin is already active so this
     * stays free of side effects and can be tried first on every lock.
     */
    private fun lockViaDeviceAdmin(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        if (!dpm.isAdminActive(deviceAdminComponent(context))) return false
        return runCatching {
            dpm.lockNow()
            true
        }.onFailure { e -> Log.w(TAG, "lockNow failed", e) }.getOrDefault(false)
    }

    /** Lock via Device Admin, acquiring the policy first if we do not hold it yet. */
    fun sleepScreen(context: Context): Boolean {
        if (lockViaDeviceAdmin(context)) return true
        ensureDeviceAdmin(context)
        return lockViaDeviceAdmin(context)
    }

    /**
     * A real lock: the device sleeps and the keyguard is armed. Deliberately never blanks the
     * panel on its own — that is [setScreenOn], which leaves the device awake and unlocked.
     *
     * Device Admin leads because it is the only durable capability in this file. `lockNow()`
     * needs no shell and survives reboots, whereas Shizuku started in ADB mode (the common
     * case on unrooted devices — measured on this vivo: `shizuku_server` running as uid
     * `shell`) dies on every restart until the user re-runs the pairing.
     */
    fun lockScreen(context: Context): Boolean {
        val success = lockViaDeviceAdmin(context) || privilegedSleep() || sleepScreen(context)
        if (success) {
            // A genuine sleep blanks the panel too, unlike the panel-only path.
            if (ScreenBlankOverlay.isShowing()) ScreenBlankOverlay.hide()
            _deviceAwakeState.value = false
            _panelOnState.value = false
        }
        return success
    }

    /** KEYCODE_SLEEP goes through PowerManagerService, so the keyguard comes up as usual. */
    private fun privilegedSleep(): Boolean {
        if (RootUtils.isRootAvailable()) {
            val viaRoot = runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 223")).waitFor() == 0
            }.getOrDefault(false)
            if (viaRoot) return true
        }
        return ShizukuUtils.isShizukuPermissionGranted() &&
            ShizukuUtils.executeCommand("input keyevent 223").first == 0
    }

    fun rebootDevice(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            dpm != null &&
            dpm.isAdminActive(admin) &&
            dpm.isDeviceOwnerApp(context.packageName)
        ) {
            return runCatching {
                dpm.reboot(admin)
                true
            }.getOrElse {
                false
            }
        }

        if (ShizukuUtils.isShizukuPermissionGranted()) {
            return ShizukuUtils.rebootDevice()
        }

        if (RootUtils.isRootAvailable()) {
            return runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")).waitFor() == 0
            }.getOrDefault(false)
        }

        return false
    }

    /**
     * Called when the user enables a feature that needs to darken the screen. Acquires a
     * privileged shell (for panel power) *and* Device Admin (for locking), because the two
     * cover different dimensions and neither substitutes for the other.
     */
    fun ensureScreenOffPermission(context: Context): Boolean {
        val hasShell = RootUtils.isRootAvailable() ||
            ShizukuUtils.isPrivilegedShellUsable() ||
            run {
                // Shizuku present but not granted yet — ask before deciding it is unusable.
                if (ShizukuUtils.isShizukuRunning()) {
                    ShizukuUtils.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                    ShizukuUtils.isPrivilegedShellUsable()
                } else {
                    false
                }
            }

        // Take Device Admin even when the shell works: the shell can silently grant it, and it
        // is the only thing that still locks the screen once Shizuku is gone after a reboot.
        val hasAdmin = ensureDeviceAdmin(context)
        return hasShell || hasAdmin
    }

    fun ensurePermissionOnColdStart(context: Context) {
        syncScreenState(context)
        if (!ShizukuUtils.isPrivilegedShellUsable() &&
            !RootUtils.isRootAvailable() &&
            ShizukuUtils.isShizukuRunning() &&
            shouldLaunchPermissionFlow()
        ) {
            ShizukuUtils.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
        // Silent only: a cold start must not throw the Device Admin dialog at the user, but if
        // a shell is available this grabs the durable capability for free.
        if (!isDeviceAdminActive(context)) {
            tryActivateDeviceAdminViaShell(context)
        }
    }

    /**
     * Refresh the platform dimension from PowerManager. Deliberately does **not** touch
     * [panelOnState]: `isInteractive` stays true while the panel is blanked, so letting it
     * write panel state is what used to make a dark screen report itself as on.
     */
    fun syncScreenState(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val awake = pm.isInteractive
        if (!stateInitialized || _deviceAwakeState.value != awake) {
            _deviceAwakeState.value = awake
            stateInitialized = true
        }
        // Sleep implies the panel is off. The reverse is not true: a blanked panel still
        // reports isInteractive=true, so this must never write panel=true from PowerManager.
        if (!awake) {
            if (ScreenBlankOverlay.isShowing()) ScreenBlankOverlay.hide()
            _panelOnState.value = false
        }
    }

    /**
     * From ACTION_SCREEN_ON/OFF. Those only fire on real wakefulness changes — measured: a
     * `setDisplayPowerMode` blank emits nothing — so a broadcast implies both dimensions moved.
     */
    fun updateScreenOnState(isScreenOn: Boolean) {
        _deviceAwakeState.value = isScreenOn
        _panelOnState.value = isScreenOn
        // Real sleep already blanks the panel. Leave the overlay up across SCREEN_ON so a
        // fallback blank is not cleared by an unrelated wake broadcast.
        if (!isScreenOn && ScreenBlankOverlay.isShowing()) ScreenBlankOverlay.hide()
    }

    /**
     * Backlight sysfs write. Preferred on rooted panels (notably Echo Show style devices)
     * where it dims smoothly, but the node does not exist everywhere, so a false return
     * hands off to [setPrivilegedDisplayPower].
     */
    private fun setRootScreenState(context: Context, screenOn: Boolean): Boolean {
        val backlightResult = runCatching {
            // Slider floor may be 1 (0 means screen off). The backlight node on these panels
            // still rejects anything under the ROM minimum, so the dim path keeps that floor.
            val minBrightness = maxOf(
                ModDeviceSupport.getMinBrightness(
                    context = context,
                    fallback = EchoShowSupport.getMinBrightness()
                ),
                EchoShowSupport.getMinBrightness()
            )
            if (screenOn) {
                backlightWakeEpoch.incrementAndGet()
                smoothSetBacklightBrightness(
                    start = minBrightness,
                    end = cachedBacklightBrightness.takeIf { it > 0 } ?: 128
                )
            } else {
                dimBacklightToMinimum(minBrightness)
            }
        }.getOrDefault(false)
        if (backlightResult) {
            _panelOnState.value = screenOn
        }
        return backlightResult
    }

    /**
     * Fade the panel down rather than dropping the backlight in one write.
     *
     * The first step runs on the caller's thread because its exit code is the only proof the
     * sysfs node is writable, and a false return has to reach [setPrivilegedDisplayPower]
     * before anything reports the screen as off. The remaining steps cannot: this is reached
     * from `VoiceSatelliteService.applyScreenToggle` on `Dispatchers.Main`, and the ramp
     * sleeps for [DIM_STEPS] × [DIM_STEP_DELAY_MS].
     */
    private fun dimBacklightToMinimum(minBrightness: Int): Boolean {
        val start = RootUtils.readBacklightBrightness()
            .takeIf { it > 0 }
            ?.also { cachedBacklightBrightness = it }
            ?: cachedBacklightBrightness
        if (start <= minBrightness) return RootUtils.writeBacklightBrightness(minBrightness)

        val firstStep = start + (minBrightness - start) / DIM_STEPS
        if (!RootUtils.writeBacklightBrightness(firstStep)) return false
        val epoch = backlightWakeEpoch.get()
        backlightRampExecutor.execute {
            RootUtils.rampBacklightBrightness(
                from = firstStep,
                to = minBrightness,
                steps = DIM_STEPS - 1,
                stepDelayMs = DIM_STEP_DELAY_MS
            )
            // A wake that landed mid-ramp already brightened the panel, and the tail of the
            // ramp then darkened it again behind that wake's back. Put it back.
            if (backlightWakeEpoch.get() != epoch) {
                RootUtils.writeBacklightBrightness(cachedBacklightBrightness.takeIf { it > 0 } ?: 128)
            }
        }
        return true
    }

    /**
     * `SurfaceControl.setDisplayPowerMode`. The bundled dex is first: it only needs a shell,
     * which is the channel that still works when the Shizuku user service cannot bind.
     * In-process reflection is a faster second try on ROMs where the user service does bind.
     */
    private fun setPrivilegedDisplayPower(context: Context, screenOn: Boolean): Boolean {
        val mode = if (screenOn) DisplayPowerPayload.MODE_ON else DisplayPowerPayload.MODE_OFF
        val success = DisplayPowerPayload.setMode(context, mode) ||
            ShizukuUtils.setDisplayPower(mode)
        if (success) {
            _panelOnState.value = screenOn
        }
        return success
    }

    private fun shouldLaunchPermissionFlow(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPermissionRequestAt < 1500L) {
            return false
        }
        lastPermissionRequestAt = now
        return true
    }

    private fun runUnprivilegedShell(command: String): Boolean = runCatching {
        Runtime.getRuntime().exec(arrayOf("sh", "-c", command)).waitFor() == 0
    }.getOrDefault(false)

    /** Root first, then Shizuku (including the newProcess fallback). */
    private fun runPrivilegedShell(command: String): Boolean {
        if (RootUtils.isRootAvailable()) {
            val viaRoot = runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
            }.getOrDefault(false)
            if (viaRoot) return true
        }
        if (!ShizukuUtils.isShizukuPermissionGranted()) return false
        return ShizukuUtils.executeCommand(command).first == 0
    }

    private fun smoothSetSystemBrightness(start: Int, end: Int): Boolean {
        val safeStart = start.coerceAtLeast(0)
        val safeEnd = end.coerceAtLeast(0)
        if (safeEnd <= safeStart) {
            return Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "settings put system screen_brightness $safeEnd"))
                .waitFor() == 0
        }
        for (step in 1..BRIGHTEN_STEPS) {
            val value = safeStart + ((safeEnd - safeStart) * step / BRIGHTEN_STEPS)
            val success = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "settings put system screen_brightness $value"))
                .waitFor() == 0
            if (!success) return false
            Thread.sleep(BRIGHTEN_STEP_DELAY_MS)
        }
        return true
    }

    private fun smoothSetBacklightBrightness(start: Int, end: Int): Boolean {
        val safeStart = start.coerceAtLeast(0)
        val safeEnd = end.coerceAtLeast(0)
        if (safeEnd <= safeStart) {
            return RootUtils.writeBacklightBrightness(safeEnd)
        }
        for (step in 1..BRIGHTEN_STEPS) {
            val value = safeStart + ((safeEnd - safeStart) * step / BRIGHTEN_STEPS)
            if (!RootUtils.writeBacklightBrightness(value)) return false
            Thread.sleep(BRIGHTEN_STEP_DELAY_MS)
        }
        return true
    }
}
