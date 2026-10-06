package com.example.ava.services

import android.content.Context
import android.util.Log
import com.example.ava.utils.RootUtils
import java.io.File

/**
 * While the idle screensaver is visible, optionally switch CPU governors to
 * `powersave` (root). Original governors are saved and restored on hide.
 * No-op without root or when never applied.
 */
object ScreensaverCpuThrottle {
    private const val TAG = "ScreensaverCpuThrottle"
    private const val STATE_FILE = "ava_screensaver_cpu_gov_state.txt"

    @Volatile
    private var applied = false

    fun apply(context: Context) {
        if (!RootUtils.isRootAvailable()) return
        synchronized(this) {
            runCatching {
                val stateFile = File(context.applicationContext.filesDir, STATE_FILE)
                val script = buildString {
                    appendLine("STATE='${stateFile.absolutePath}'")
                    // Keep first-saved originals across re-apply while still showing.
                    appendLine("if [ ! -s \"\$STATE\" ]; then")
                    appendLine("  : > \"\$STATE\"")
                    appendLine("  for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do")
                    appendLine("    [ -e \"\$g\" ] || continue")
                    appendLine("    cur=\$(cat \"\$g\" 2>/dev/null) || continue")
                    appendLine("    [ -n \"\$cur\" ] || continue")
                    appendLine("    echo \"\$g|\$cur\" >> \"\$STATE\"")
                    appendLine("  done")
                    appendLine("fi")
                    appendLine("if [ -s \"\$STATE\" ]; then")
                    appendLine("  while IFS= read -r line; do")
                    appendLine("    path=\${line%%|*}")
                    appendLine("    [ -e \"\$path\" ] || continue")
                    appendLine("    echo powersave > \"\$path\" 2>/dev/null || true")
                    appendLine("  done < \"\$STATE\"")
                    appendLine("fi")
                }
                val ok = execSuScript(script)
                applied = ok && stateFile.exists() && stateFile.length() > 0L
                if (applied) {
                    Log.d(TAG, "CPU powersave applied for screensaver")
                } else {
                    Log.w(TAG, "CPU powersave apply skipped or failed")
                }
            }.onFailure { Log.w(TAG, "CPU powersave apply error", it) }
        }
    }

    fun restore(context: Context) {
        if (!applied && !File(context.applicationContext.filesDir, STATE_FILE).exists()) return
        synchronized(this) {
            runCatching {
                if (!RootUtils.isRootAvailable()) {
                    // Cannot restore without root; drop stale state so we don't loop.
                    File(context.applicationContext.filesDir, STATE_FILE).delete()
                    applied = false
                    return
                }
                val stateFile = File(context.applicationContext.filesDir, STATE_FILE)
                val script = buildString {
                    appendLine("STATE='${stateFile.absolutePath}'")
                    appendLine("if [ -s \"\$STATE\" ]; then")
                    appendLine("  while IFS= read -r line; do")
                    appendLine("    path=\${line%%|*}")
                    appendLine("    gov=\${line#*|}")
                    appendLine("    [ -e \"\$path\" ] || continue")
                    appendLine("    [ -n \"\$gov\" ] || continue")
                    appendLine("    echo \"\$gov\" > \"\$path\" 2>/dev/null || true")
                    appendLine("  done < \"\$STATE\"")
                    appendLine("  rm -f \"\$STATE\"")
                    appendLine("fi")
                }
                execSuScript(script)
                applied = false
                Log.d(TAG, "CPU governors restored after screensaver")
            }.onFailure {
                applied = false
                Log.w(TAG, "CPU governor restore error", it)
            }
        }
    }

    private fun execSuScript(script: String): Boolean {
        val process = Runtime.getRuntime().exec("su")
        process.outputStream.bufferedWriter().use { writer ->
            writer.write(script)
            writer.write("\nexit\n")
            writer.flush()
        }
        return process.waitFor() == 0
    }
}
