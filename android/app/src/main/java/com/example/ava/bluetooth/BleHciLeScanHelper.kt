package com.example.ava.bluetooth

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.ava.utils.RootUtils
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Root-only LE presence scanner via the bundled native helper [ble_le_scan].
 *
 * Modes (native):
 * - **coop**: MGMT Stop Discovery + HCI prep, then scan (default)
 * - **raw**: HCI only
 * - **own**: stop Android bluetooth, exclusive HCI/MGMT scan, restart BT
 *
 * Tries coop → raw → own. own is last-resort stack takeover and is never
 * locked in as preferred on an empty success. On HAL-only controllers, marks
 * unsupported and lets the API compatibility path take over.
 */
object BleHciLeScanHelper {
    private const val TAG = "BleHciLeScan"
    private const val HELPER_NAME = "ble_le_scan"
    private const val ASSET_PREFIX = "ble_le_scan"

    data class Hit(val address: String, val rssi: Int)
    data class ScanResult(
        val hits: List<Hit>,
        val transport: String,
        val mode: String,
    )

    private val unsupported = AtomicBoolean(false)
    private val preferredMode = AtomicReference("coop")
    @Volatile private var extractedPath: String? = null
    @Volatile private var extractedAbi: String? = null

    fun isStructurallyUnsupported(): Boolean = unsupported.get()

    fun isRunnable(context: Context): Boolean {
        if (unsupported.get()) return false
        if (!RootUtils.isRootBinaryPresent()) return false
        if (!RootUtils.isRootAvailable()) return false
        return ensureExtracted(context) != null
    }

    /**
     * Blocking scan. Returns null when the helper cannot run / controller unavailable.
     */
    fun scanBlocking(context: Context, durationMs: Long, hciIndex: Int = -1): List<Hit>? {
        return scanDetailed(context, durationMs, hciIndex)?.hits
    }

    fun scanDetailed(context: Context, durationMs: Long, hciIndex: Int = -1): ScanResult? {
        if (unsupported.get()) return null
        if (!RootUtils.isRootAvailable()) return null
        val path = ensureExtracted(context) ?: return null
        val duration = durationMs.coerceIn(500L, 60_000L)

        val allowOwn = !BluetoothLowLevelHooks.killingBluetoothWouldDropWifi(context)
        val modes = linkedSetOf(
            preferredMode.get().orEmpty().ifBlank { "coop" },
            "coop",
            "raw",
            if (allowOwn) "own" else "",
        ).filter { it in setOf("coop", "raw", "own") }
        if (!allowOwn && preferredMode.get() == "own") {
            preferredMode.set("coop")
            Log.w(TAG, "own mode disabled — Bluetooth kill would drop Wi-Fi on this chip")
        }

        var lastStructuralFail = false
        for (mode in modes) {
            val result = runOnce(path, duration, hciIndex, mode) ?: continue
            when (result.exitCode) {
                0 -> {
                    // Empty own "success" is how a Reset-without-host-init scan
                    // locks itself in and then kills the stack every cycle.
                    if (mode == "own" && result.hits.isEmpty()) {
                        Log.w(TAG, "own succeeded with 0 hits; not locking as preferred")
                        continue
                    }
                    preferredMode.set(mode)
                    Log.i(
                        TAG,
                        "HCI scan ok mode=$mode transport=${result.transport} hits=${result.hits.size}",
                    )
                    return ScanResult(result.hits, result.transport, mode)
                }
                2, 4 -> {
                    lastStructuralFail = true
                    Log.w(TAG, "mode=$mode structural fail exit=${result.exitCode}: ${result.stderr}")
                }
                else -> {
                    Log.w(TAG, "mode=$mode exit=${result.exitCode} hits=${result.hits.size}: ${result.stderr}")
                    if (result.hits.isNotEmpty()) {
                        preferredMode.set(mode)
                        return ScanResult(result.hits, result.transport, mode)
                    }
                }
            }
        }

        if (lastStructuralFail) {
            unsupported.set(true)
            Log.w(TAG, "HCI/MGMT path unsupported on this device")
            return null
        }
        return ScanResult(emptyList(), "none", preferredMode.get().orEmpty())
    }

    private data class NativeRun(
        val exitCode: Int,
        val hits: List<Hit>,
        val transport: String,
        val stderr: String,
    )

    private fun runOnce(
        path: String,
        durationMs: Long,
        hciIndex: Int,
        mode: String,
    ): NativeRun? {
        return try {
            val args = buildString {
                append(path)
                if (hciIndex >= 0) append(" --hci ").append(hciIndex)
                append(" --ms ").append(durationMs)
                append(" --mode ").append(mode)
                append(" --active")
                if (mode == "own") append(" --reset")
            }
            // own mode stops/starts Android BT — allow extra settle time
            val timeoutMs = if (mode == "own") durationMs + 15_000L else durationMs + 8_000L
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", args))
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                Log.w(TAG, "mode=$mode timed out")
                if (mode == "own") restoreSystemBluetooth("timeout")
                return NativeRun(-1, emptyList(), "timeout", stderr.trim())
            }
            val parsed = parseOutput(stdout)
            NativeRun(process.exitValue(), parsed.first, parsed.second, stderr.trim())
        } catch (e: Exception) {
            Log.w(TAG, "mode=$mode failed", e)
            if (mode == "own") restoreSystemBluetooth("exception")
            null
        }
    }

    /**
     * SIGKILL of the helper skips its atexit/signal restore. Bring the radio back
     * from this side so a hung own-mode scan cannot leave Bluetooth off.
     */
    private fun restoreSystemBluetooth(reason: String) {
        val ok = RootUtils.enableBluetooth()
        Log.w(TAG, "own restore after $reason enable=$ok")
    }

    private fun parseOutput(stdout: String): Pair<List<Hit>, String> {
        if (stdout.isBlank()) return emptyList<Hit>() to "none"
        var transport = "hci"
        val out = ArrayList<Hit>()
        stdout.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith("#meta")) {
                Regex("""transport=([^\s]+)""").find(t)?.groupValues?.getOrNull(1)?.let {
                    transport = it
                }
                return@forEach
            }
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 2) return@forEach
            val mac = parts[0].uppercase()
            val rssi = parts[1].toIntOrNull() ?: return@forEach
            if (mac.length == 17) out.add(Hit(mac, rssi))
        }
        return out to transport
    }

    private fun ensureExtracted(context: Context): String? {
        val abi = preferredAbi() ?: return null
        extractedPath?.let { path ->
            if (extractedAbi == abi && File(path).exists()) return path
        }
        val assetPath = "$ASSET_PREFIX/$abi/$HELPER_NAME"
        return try {
            context.assets.open(assetPath).use { input ->
                val dir = context.getDir("native", Context.MODE_PRIVATE)
                val out = File(dir, HELPER_NAME)
                FileOutputStream(out).use { fos -> input.copyTo(fos) }
                out.setExecutable(true, false)
                out.setReadable(true, false)
                extractedPath = out.absolutePath
                extractedAbi = abi
                Log.i(TAG, "Extracted HCI helper: ${out.absolutePath} ($abi)")
                out.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "HCI helper missing for abi=$abi ($assetPath)", e)
            null
        }
    }

    private fun preferredAbi(): String? {
        val abis = Build.SUPPORTED_ABIS?.filterNotNull().orEmpty()
        for (abi in abis) {
            when (abi) {
                "arm64-v8a", "armeabi-v7a" -> return abi
            }
        }
        return null
    }
}
