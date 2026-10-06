package com.example.ava.utils

import java.io.File
import java.util.Locale

object RootUtils {
    @Volatile private var rootAvailable: Boolean? = null
    private var detectedBacklightPath: String? = null

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/su/bin/su", "/system/sbin/su", "/vendor/bin/su",
        "/data/local/bin/su", "/data/local/xbin/su"
    )

    /**
     * True when an `su` binary is present on the device, i.e. the device is rooted,
     * regardless of whether this app has been granted root yet. Cheap and side-effect free,
     * so it can be used to avoid falling back to other permission flows (e.g. device admin)
     * on a device that is clearly rooted.
     */
    fun isRootBinaryPresent(): Boolean = SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }

    /**
     * Resolve root availability once on a background thread and cache it, so the synchronous
     * [isRootAvailable] check (which may block while the superuser prompt is answered) never
     * runs on the main thread and never caches a premature `false`.
     */
    fun warmUpRootDetection() {
        if (rootAvailable != null) return
        if (!isRootBinaryPresent()) {
            rootAvailable = false
            return
        }
        Thread { runCatching { isRootAvailable() } }.start()
    }

    fun isRootAvailable(): Boolean = rootAvailable ?: runCatching {
        Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0
    }.getOrDefault(false).also { rootAvailable = it }

    fun requestRootPermission() = takeIf { isRootAvailable() }?.let {
        runCatching { Runtime.getRuntime().exec("su").waitFor() }
    }
    
    fun setProcessHighPriority(pid: Int) = takeIf { isRootAvailable() }?.runCatching {
        Runtime.getRuntime().exec("su -c renice -20 $pid").waitFor()
        Runtime.getRuntime().exec("su -c echo -1000 > /proc/$pid/oom_score_adj").waitFor()
    }

    fun acquireCpuWakeLock() = takeIf { isRootAvailable() }?.runCatching {
        Runtime.getRuntime().exec("su -c echo ava_wakelock > /sys/power/wake_lock").waitFor()
    }

    fun releaseCpuWakeLock() = takeIf { isRootAvailable() }?.runCatching {
        Runtime.getRuntime().exec("su -c echo ava_wakelock > /sys/power/wake_unlock").waitFor()
    }

    fun grantLocationPermissionForBluetooth(packageName: String): Boolean =
        grantLocationPermissionViaRoot(packageName).takeIf { it } ?: false

    private fun grantLocationPermissionViaRoot(packageName: String): Boolean = runCatching {
        val process = Runtime.getRuntime().exec("su")
        java.io.DataOutputStream(process.outputStream).use { os ->
            os.writeBytes("pm grant $packageName android.permission.ACCESS_COARSE_LOCATION\n")
            os.writeBytes("pm grant $packageName android.permission.ACCESS_FINE_LOCATION\n")
            os.writeBytes("settings put secure location_mode 3\n")
            os.writeBytes("settings put secure location_providers_allowed +gps,network\n")
            os.writeBytes("settings put global ble_scan_always_enabled 1\n")
            os.writeBytes("exit\n")
        }
        process.waitFor() == 0
    }.getOrDefault(false)

    fun grantLocationPermission(packageName: String): Boolean = grantLocationPermissionForBluetooth(packageName)

    fun disableBleScanLocationCheck(): Boolean = runCatching {
        isRootAvailable() && listOf(
            "settings put global ble_scan_always_enabled 1",
            "settings put secure location_mode 3",
            "settings put secure location_providers_allowed +gps,network"
        ).all { Runtime.getRuntime().exec(arrayOf("su", "-c", it)).waitFor() == 0 }
    }.getOrDefault(false)

    private fun findBacklightPath(): String? = detectedBacklightPath ?: runCatching {
        takeIf { isRootAvailable() }?.let {
            val cmd = "ls /sys/class/leds/*/brightness /sys/class/backlight/*/brightness 2>/dev/null | grep -E 'lcd|backlight' | head -1"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            process.inputStream.bufferedReader().use { it.readText() }.trim().also { process.waitFor() }
                .takeIf { it.isNotEmpty() && it.contains("brightness") }
                ?.also { detectedBacklightPath = it }
        }
    }.getOrNull()

    fun readBacklightBrightness(): Int = runCatching {
        findBacklightPath()?.let { path ->
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
            process.inputStream.bufferedReader().use { it.readText() }.trim().also { process.waitFor() }.toIntOrNull()
        }
    }.getOrNull() ?: -1

    fun writeBacklightBrightness(brightness: Int): Boolean = runCatching {
        findBacklightPath()?.let { path ->
            Runtime.getRuntime().exec(arrayOf("su", "-c", "echo $brightness > $path")).waitFor() == 0
        } ?: false
    }.getOrDefault(false)

    /**
     * Step the backlight from [from] to [to] across [steps] writes inside a single `su`.
     *
     * A caller-side loop over [writeBacklightBrightness] cannot produce a fade: each call
     * spawns its own `su` process, which costs more than the interval a ramp wants between
     * steps, so the shell does the stepping instead. [to] is written unconditionally at the
     * end, so a ROM whose `sleep` rejects fractional seconds lands on the target anyway —
     * abruptly, but never somewhere in between.
     */
    fun rampBacklightBrightness(from: Int, to: Int, steps: Int, stepDelayMs: Long): Boolean = runCatching {
        val path = findBacklightPath() ?: return@runCatching false
        val delaySeconds = String.format(Locale.US, "%.3f", stepDelayMs / 1000.0)
        val script = buildString {
            for (step in 1 until steps.coerceAtLeast(1)) {
                append("echo ${from + (to - from) * step / steps} > $path; sleep $delaySeconds; ")
            }
            append("echo $to > $path")
        }
        Runtime.getRuntime().exec(arrayOf("su", "-c", script)).waitFor() == 0
    }.getOrDefault(false)

    fun rebootDevice() = Thread {
        runCatching {
            when {
                isRootAvailable() -> Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")).waitFor()
                ShizukuUtils.isShizukuPermissionGranted() -> ShizukuUtils.rebootDevice()
            }
        }
    }.start()
    
    fun grantBluetoothLocationPermission(packageName: String): Boolean = runCatching {
        if (!isRootAvailable()) return@runCatching false
        val commands = listOf(
            "pm grant $packageName android.permission.ACCESS_FINE_LOCATION",
            "pm grant $packageName android.permission.ACCESS_COARSE_LOCATION",
            "pm grant $packageName android.permission.BLUETOOTH_PRIVILEGED",
            "settings put secure location_mode 3",
            "settings put global ble_scan_always_enabled 1"
        )
        commands.all { 
            Runtime.getRuntime().exec(arrayOf("su", "-c", it)).waitFor() == 0 
        }
    }.getOrDefault(false)
    
    fun resetBluetoothAdapter(): Boolean = runCatching {
        if (!isRootAvailable()) return@runCatching false
        Runtime.getRuntime().exec(arrayOf("su", "-c", "service call bluetooth_manager 6")).waitFor()
        Thread.sleep(2000)
        Runtime.getRuntime().exec(arrayOf("su", "-c", "service call bluetooth_manager 5")).waitFor()
        Thread.sleep(3000)
        true
    }.getOrDefault(false)

    /**
     * Turn the system Bluetooth radio back on. Prefer portable shell wrappers; fall back to
     * binder transaction codes used by older ROMs. Returns true if any command exited 0 —
     * callers should still wait for [BluetoothAdapter.STATE_ON].
     */
    fun enableBluetooth(): Boolean {
        if (!isRootAvailable()) return false
        val commands = listOf(
            "svc bluetooth enable",
            "cmd bluetooth_manager enable",
            "service call bluetooth_manager 6",
        )
        var anyOk = false
        for (cmd in commands) {
            val ok = runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0
            }.getOrDefault(false)
            if (ok) anyOk = true
        }
        return anyOk
    }
    
    fun killBluetoothProcessAsync(onComplete: () -> Unit) {
        if (!isRootAvailable()) {
            onComplete()
            return
        }
        Thread {
            runCatching {
                val pidProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "ps | grep com.android.bluetooth | grep -v grep"))
                val reader = pidProcess.inputStream.bufferedReader()
                val line = reader.readLine()
                reader.close()
                if (line != null) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        val pid = parts[1]
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -9 $pid")).waitFor()
                    }
                }
                Thread.sleep(3000)
            }
            onComplete()
        }.start()
    }
    
    fun clearGattCache(): Boolean = runCatching {
        if (!isRootAvailable()) return@runCatching false
        Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -rf /data/misc/bluetooth/cache/*")).waitFor() == 0
    }.getOrDefault(false)
    
    fun aggressiveBluetoothRecovery(onComplete: () -> Unit) {
        if (!isRootAvailable()) {
            onComplete()
            return
        }
        Thread {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -rf /data/misc/bluetooth/cache/*")).waitFor()
                val pidProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "ps | grep com.android.bluetooth | grep -v grep"))
                val reader = pidProcess.inputStream.bufferedReader()
                val line = reader.readLine()
                reader.close()
                if (line != null) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -9 ${parts[1]}")).waitFor()
                    }
                }
                Thread.sleep(2000)
                Runtime.getRuntime().exec(arrayOf("su", "-c", "service call bluetooth_manager 5")).waitFor()
                Thread.sleep(3000)
            }
            onComplete()
        }.start()
    }
}
