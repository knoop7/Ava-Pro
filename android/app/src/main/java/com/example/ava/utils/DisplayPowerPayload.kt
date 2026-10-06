package com.example.ava.utils

import android.content.Context
import android.util.Base64
import android.util.Log

/**
 * Panel power control via the bundled `display-toggle` dex, run as `app_process`.
 *
 * `SurfaceControl.setDisplayPowerMode` is the only way to blank the panel while
 * PowerManager stays Awake — no keyguard, no activity pause — but it needs a privileged
 * uid. [ShizukuUtils.setDisplayPower] does the same call in-process and is preferred;
 * this is the fallback for ROMs where the Shizuku user service cannot bind and only a
 * plain shell is available.
 *
 * Payload source and rebuild script live in tools/displaytoggle/.
 */
object DisplayPowerPayload {
    private const val TAG = "DisplayPowerPayload"
    private const val ASSET_NAME = "display-toggle"
    private const val REMOTE_PATH = "/data/local/tmp/ava-display-toggle.dex"

    const val MODE_OFF = 0
    const val MODE_ON = 2

    @Volatile
    private var stagedBytes = -1L

    fun isAvailable(): Boolean =
        RootUtils.isRootAvailable() || ShizukuUtils.isPrivilegedShellUsable()

    fun setMode(context: Context, mode: Int): Boolean {
        if (!isAvailable()) return false
        if (stage(context) && invoke(mode)) return true
        // /data/local/tmp can be wiped between runs, and a truncated write only shows up
        // at exec time, so re-stage once before giving up.
        stagedBytes = -1L
        return stage(context) && invoke(mode)
    }

    private fun invoke(mode: Int): Boolean {
        val (code, output) = exec("CLASSPATH=$REMOTE_PATH app_process / DisplayToggle $mode")
        if (code != 0) {
            Log.w(TAG, "DisplayToggle $mode exit=$code ${output.trim()}")
            return false
        }
        return true
    }

    private fun stage(context: Context): Boolean {
        val dex = runCatching { context.assets.open(ASSET_NAME).use { it.readBytes() } }
            .getOrElse { e ->
                Log.e(TAG, "asset $ASSET_NAME unreadable", e)
                return false
            }
        val want = dex.size.toLong()
        if (stagedBytes == want) return true
        if (remoteSize() == want) {
            stagedBytes = want
            return true
        }

        // The app's private dir is unreadable to the shell uid, so pass the bytes inside
        // the command rather than copying a file across the privilege boundary.
        // NO_WRAP base64 is quote-free, so single quoting is safe.
        val encoded = Base64.encodeToString(dex, Base64.NO_WRAP)
        val (code, output) = exec(
            "echo '$encoded' | base64 -d > '$REMOTE_PATH' && chmod 644 '$REMOTE_PATH'",
        )
        val written = remoteSize()
        if (code != 0 || written != want) {
            Log.e(TAG, "stage failed exit=$code wrote=$written want=$want ${output.trim()}")
            return false
        }
        stagedBytes = want
        Log.i(TAG, "staged $REMOTE_PATH ($want bytes)")
        return true
    }

    private fun remoteSize(): Long {
        val (code, output) = exec("wc -c < '$REMOTE_PATH' 2>/dev/null")
        if (code != 0) return -1L
        return output.trim().takeWhile { it.isDigit() }.toLongOrNull() ?: -1L
    }

    private fun exec(command: String): Pair<Int, String> = when {
        RootUtils.isRootAvailable() -> execRoot(command)
        else -> ShizukuUtils.executeCommandForOutput(command)
    }

    private fun execRoot(command: String): Pair<Int, String> = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        Pair(process.waitFor(), output)
    }.getOrElse { e ->
        Log.w(TAG, "su exec failed", e)
        Pair(-1, e.message ?: "su_failed")
    }
}
