package com.example.ava.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.ava.IShellService
import com.example.ava.shizuku.ShellService
import rikka.shizuku.Shizuku

private const val TAG = "ShizukuUtils"
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHELL_SERVICE_TIMEOUT_MS = 3_000L

object ShizukuUtils {
    
    private var shellService: IShellService? = null
    private var serviceConnected = false
    
    // Bump this whenever IShellService gains/loses a method: Shizuku caches the
    // running user service by (component, version), so a stale process from an
    // older build would answer new calls (e.g. openLocalSocket) with a failed
    // transaction. v3 = added openLocalSocket for the app-window fd handover.
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.example.ava", ShellService::class.java.name)
    ).daemon(false).processNameSuffix("shell").version(3)
    
    @Volatile private var onShellReady: (() -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShellService.Stub.asInterface(service)
            serviceConnected = true
            userServiceUnavailable = false
            Log.d(TAG, "ShellService connected uid=${shizukuUid()}")
            onShellReady?.invoke()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            serviceConnected = false
            Log.d(TAG, "ShellService disconnected")
        }
    }
    
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            bindShellService()
        }
        Log.d(TAG, "Permission result: granted=${grantResult == PackageManager.PERMISSION_GRANTED}")
    }
    
    private var pendingPackageName: String? = null
    private var listenersAttached = false
    @Volatile
    private var bluetoothGrantSucceeded = false

    /**
     * Some ROMs (seen on vivo OriginOS) never hand the Shizuku manager's provider to the
     * starter process — `ShizukuServiceStarter: provider is null` — so [bindShellService]
     * can never complete. Remember that instead of paying [SHELL_SERVICE_TIMEOUT_MS] on
     * every command, and route work through [execViaRemoteProcess] instead.
     */
    @Volatile
    private var userServiceUnavailable = false

    /** null = not probed yet. Reset whenever a fresh binder arrives. */
    @Volatile
    private var remoteProcessWorks: Boolean? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        userServiceUnavailable = false
        remoteProcessWorks = null
        if (isShizukuPermissionGranted()) {
            bindShellService()
            maybeGrantBluetoothPermissions()
        }
    }
    
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        serviceConnected = false
        shellService = null
        bluetoothGrantSucceeded = false
        remoteProcessWorks = null
    }
    
    fun init(packageName: String? = null) {
        if (packageName != null) {
            pendingPackageName = packageName
        }
        try {
            if (!listenersAttached) {
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
                Shizuku.addBinderReceivedListener(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                listenersAttached = true
            }

            if (isShizukuPermissionGranted()) {
                if (!serviceConnected) {
                    bindShellService()
                }
                maybeGrantBluetoothPermissions()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to init Shizuku listeners", e)
        }
    }

    private fun maybeGrantBluetoothPermissions() {
        if (bluetoothGrantSucceeded) {
            return
        }
        val pkg = pendingPackageName ?: return
        Thread {
            if (grantBluetoothPermissions(pkg)) {
                bluetoothGrantSucceeded = true
            }
        }.start()
    }
    
    fun cleanup() {
        try {
            unbindShellService()
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup Shizuku listeners", e)
        }
    }
    
    private fun bindShellService() {
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind ShellService", e)
        }
    }
    
    private fun unbindShellService() {
        try {
            if (serviceConnected) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unbind ShellService", e)
        }
    }
    
    @Suppress("DEPRECATION")
    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getInstalledApplications(0).any { 
                it.packageName.contains("shizuku", ignoreCase = true) 
            }
        } catch (e: Exception) {
            false
        }
    }
    
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }
    
    fun isShizukuPermissionGranted(): Boolean {
        return try {
            isShizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** Shizuku server uid: 0 = root, 2000 = ADB/shell. -1 if the binder is down. */
    fun shizukuUid(): Int {
        return try {
            if (!isShizukuRunning()) -1 else Shizuku.getUid()
        } catch (_: Exception) {
            -1
        }
    }

    fun isShizukuRoot(): Boolean = shizukuUid() == 0

    fun setOnShellReady(listener: (() -> Unit)?) {
        onShellReady = listener
        if (listener != null && serviceConnected && isShizukuPermissionGranted()) {
            listener.invoke()
        }
    }

    private fun waitForShellService(timeoutMs: Long = SHELL_SERVICE_TIMEOUT_MS): IShellService? {
        shellService?.let { return it }
        if (userServiceUnavailable) return null
        bindShellService()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shellService?.let { return it }
            Thread.sleep(100)
        }
        if (shellService == null) {
            userServiceUnavailable = true
            Log.w(TAG, "ShellService cannot bind on this ROM — using Shizuku.newProcess instead")
        }
        return shellService
    }

    /**
     * Pre-user-service Shizuku API: runs a command straight through the Shizuku server
     * binder, so it works even when the user service can never be attached. Private
     * upstream, hence reflection.
     */
    private fun newRemoteProcess(command: Array<String>): Process? = runCatching {
        Shizuku::class.java
            .getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            .apply { isAccessible = true }
            .invoke(null, command, null, null) as? Process
    }.onFailure { e ->
        Log.w(TAG, "Shizuku.newProcess unavailable", e)
    }.getOrNull()

    /**
     * Spawn a privileged shell-domain process whose stdio streams stay live —
     * unlike [executeCommand], which drains both pipes to completion before
     * returning. This is the only elevated channel that survives when the user
     * service can't bind (e.g. vivo OriginOS "provider is null"), and it runs in
     * the shell SELinux domain, so the app-window socket relay rides it to reach
     * a scrcpy socket an untrusted_app is denied `connectto` on. Null when
     * Shizuku can't start a process (permission gone / binder dead).
     */
    fun newShellProcess(command: Array<String>): Process? {
        if (!isShizukuPermissionGranted()) return null
        return newRemoteProcess(command)
    }

    /** Merged stdout+stderr so callers see the same text either channel would produce. */
    private fun execViaRemoteProcess(command: String): Pair<Int, String> {
        val process = newRemoteProcess(arrayOf("sh", "-c", command))
        if (process == null) {
            remoteProcessWorks = false
            return Pair(-1, "no_shell_channel")
        }
        return try {
            val stderr = StringBuilder()
            // Drain both pipes concurrently; a chatty command must not deadlock on a full one.
            val errDrain = Thread {
                runCatching {
                    process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
                }
            }.apply { isDaemon = true; start() }
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            errDrain.join(1_000L)
            remoteProcessWorks = true
            Pair(code, stdout + stderr)
        } catch (e: Exception) {
            Log.e(TAG, "newProcess exec failed: $command", e)
            runCatching { process.destroy() }
            remoteProcessWorks = false
            Pair(-1, e.message ?: "remote_process_failed")
        }
    }

    /**
     * Whether an elevated command can actually run right now — permission alone is not
     * enough, because the user service may be unbindable. Callers deciding whether to
     * fall back to Device Admin must use this, not [isShizukuPermissionGranted].
     */
    fun isPrivilegedShellUsable(): Boolean {
        if (!isShizukuPermissionGranted()) return false
        if (shellService != null) return true
        remoteProcessWorks?.let { return it }
        return executeCommand("true").first == 0
    }

    /**
     * Resolve [isPrivilegedShellUsable] off the main thread, since the first call may wait
     * out the user-service bind timeout.
     */
    fun warmUpPrivilegedShell() {
        if (remoteProcessWorks != null || shellService != null) return
        Thread { runCatching { isPrivilegedShellUsable() } }.start()
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (isShizukuRunning() && !isShizukuPermissionGranted()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request Shizuku permission", e)
        }
    }
    
    fun executeCommand(command: String): Pair<Int, String> {
        if (!isShizukuPermissionGranted()) {
            return Pair(-1, "Shizuku permission not granted")
        }

        val service = waitForShellService()
            ?: return execViaRemoteProcess(command)

        return try {
            val exitCode = service.executeCommand(command)
            Pair(exitCode, "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $command", e)
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * Like [executeCommand] but returns captured stdout+stderr.
     * @return exit code to combined output (merged streams).
     */
    fun executeCommandForOutput(command: String): Pair<Int, String> {
        if (!isShizukuPermissionGranted()) {
            return Pair(-1, "Shizuku permission not granted")
        }

        val service = waitForShellService()
            ?: return execViaRemoteProcess(command)

        return try {
            val raw = service.executeCommandForOutput(command)
            val nl = raw.indexOf('\n')
            if (nl < 0) {
                Pair(raw.toIntOrNull() ?: -1, "")
            } else {
                val code = raw.substring(0, nl).toIntOrNull() ?: -1
                Pair(code, raw.substring(nl + 1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command (output): $command", e)
            Pair(-1, e.message ?: "Unknown error")
        }
    }
    
    /**
     * Connect to a shell-owned abstract [name] socket from inside the shell
     * domain and return the connected fd. This is the SELinux-safe way for an
     * untrusted_app to read a scrcpy socket started under Shizuku: a direct
     * `LocalSocket.connect` from the app is denied `connectto` to the shell
     * domain, but a fd handed over binder is read/written under the app's own
     * label. Null when Shizuku's user service is unavailable (e.g. the
     * newProcess-only fallback ROMs) or the connect failed — callers then try
     * a direct connect and, failing that, degrade off the mirror path.
     */
    fun openLocalSocketFd(name: String): ParcelFileDescriptor? {
        if (!isShizukuPermissionGranted()) {
            Log.w(TAG, "openLocalSocketFd($name): shizuku not granted")
            return null
        }
        val service = waitForShellService()
        if (service == null) {
            Log.w(TAG, "openLocalSocketFd($name): no shell service (userServiceUnavailable=$userServiceUnavailable)")
            return null
        }
        return try {
            val pfd = service.openLocalSocket(name)
            Log.i(TAG, "openLocalSocketFd($name) -> ${pfd?.let { "fd=${it.fd}" } ?: "null"}")
            pfd
        } catch (e: Exception) {
            Log.e(TAG, "openLocalSocket failed: $name", e)
            null
        }
    }

    fun grantLocationPermission(packageName: String): Boolean {
        if (!isShizukuPermissionGranted()) return false
        
        val (code1, _) = executeCommand("pm grant $packageName android.permission.ACCESS_COARSE_LOCATION")
        val (code2, _) = executeCommand("pm grant $packageName android.permission.ACCESS_FINE_LOCATION")
        executeCommand("settings put secure location_mode 3")
        
        return code1 == 0 && code2 == 0
    }
    
    fun grantReadLogsPermission(packageName: String): Boolean {
        if (!isShizukuPermissionGranted()) {
            Log.w(TAG, "grantReadLogsPermission: Shizuku permission not granted")
            return false
        }
        val (code, _) = executeCommand("pm grant $packageName android.permission.READ_LOGS")
        Log.d(TAG, "grantReadLogsPermission: READ_LOGS=${code}")
        return code == 0
    }

    fun grantBluetoothPermissions(packageName: String): Boolean {
        if (!isShizukuPermissionGranted()) {
            Log.w(TAG, "grantBluetoothPermissions: Shizuku permission not granted")
            return false
        }
        
        return try {
            val r1 = executeCommand("pm grant $packageName android.permission.BLUETOOTH_SCAN")
            val r2 = executeCommand("pm grant $packageName android.permission.BLUETOOTH_CONNECT")
            val r3 = executeCommand("pm grant $packageName android.permission.BLUETOOTH_ADVERTISE")
            
            val r4 = executeCommand("pm grant $packageName android.permission.ACCESS_COARSE_LOCATION")
            val r5 = executeCommand("pm grant $packageName android.permission.ACCESS_FINE_LOCATION")
            
            executeCommand("settings put secure location_mode 3")
            
            Log.d(TAG, "grantBluetoothPermissions: SCAN=${r1.first}, CONNECT=${r2.first}, ADVERTISE=${r3.first}, COARSE=${r4.first}, FINE=${r5.first}")
            
            r5.first == 0
        } catch (e: Exception) {
            Log.e(TAG, "grantBluetoothPermissions failed", e)
            false
        }
    }
    
    /**
     * In-process `SurfaceControl.setDisplayPowerMode`, which needs the user service to
     * host the reflection. When it cannot bind, callers should fall back to the
     * `app_process` payload in [DisplayPowerPayload], which only needs a shell.
     */
    fun setDisplayPower(mode: Int): Boolean {
        if (!isShizukuPermissionGranted()) return false
        val service = waitForShellService(timeoutMs = 1_000L) ?: return false

        return try {
            service.setDisplayPower(mode)
        } catch (e: Exception) {
            Log.w(TAG, "setDisplayPower($mode) failed", e)
            false
        }
    }
    
    fun rebootDevice(): Boolean {
        if (!isShizukuPermissionGranted()) return false
        
        val (exitCode, _) = executeCommand("reboot")
        return exitCode == 0
    }

    /** Stream APK bytes into elevated `pm install` (avoids shell being unable to read app-private paths). */
    fun installApk(apkFile: java.io.File): Boolean {
        if (!isShizukuPermissionGranted()) return false
        if (!apkFile.isFile || apkFile.length() <= 0L) return false

        var service = shellService
        if (service == null) {
            bindShellService()
            repeat(10) {
                Thread.sleep(100)
                service = shellService
                if (service != null) return@repeat
            }
        }
        service = shellService ?: return false

        return try {
            val pfd = android.os.ParcelFileDescriptor.open(
                apkFile,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            )
            val code = service.installApk(pfd, apkFile.length())
            Log.i(TAG, "installApk exit=$code path=${apkFile.absolutePath}")
            code == 0
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed", e)
            false
        }
    }

    /**
     * Recover a stuck BLE stack (screen-off / stale scan) without root. The Shizuku shell uid
     * can't signal the bt process directly, but `am force-stop` goes through ActivityManager and
     * respawns the stack, which un-sticks scanning. [onComplete] always runs so callers can restart.
     */
    fun killBluetoothProcessAsync(onComplete: () -> Unit) {
        if (!isShizukuPermissionGranted()) {
            onComplete()
            return
        }
        Thread {
            runCatching {
                executeCommand("am force-stop com.android.bluetooth")
                Thread.sleep(3000)
            }
            onComplete()
        }.start()
    }

    /**
     * Request the system Bluetooth radio to turn on via elevated shell.
     * Exit code 0 on any successful command is enough; wait for adapter STATE_ON separately.
     */
    fun enableBluetooth(): Boolean {
        if (!isShizukuPermissionGranted()) return false
        val commands = listOf(
            "svc bluetooth enable",
            "cmd bluetooth_manager enable",
            "service call bluetooth_manager 6",
        )
        var anyOk = false
        for (cmd in commands) {
            val (code, _) = executeCommand(cmd)
            if (code == 0) anyOk = true
        }
        return anyOk
    }
}
