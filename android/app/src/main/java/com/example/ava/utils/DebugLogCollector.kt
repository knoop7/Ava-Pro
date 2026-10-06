package com.example.ava.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Builds a shareable debug report: device info, a `---` separator,
 * then the last N logcat lines from this process.
 * IP addresses are redacted so users can paste the report publicly.
 */
object DebugLogCollector {

    private const val MAX_LOG_LINES = 50

    private val IPV4_REGEX = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""")

    // Requires >=4 colon groups or a "::" so log timestamps (HH:MM:SS) are not redacted.
    private val IPV6_REGEX = Regex(
        """(?:\b(?:[0-9a-fA-F]{1,4}:){4,7}[0-9a-fA-F]{1,4}\b)|(?:\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})*::(?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})*)?)"""
    )

    /**
     * @param deviceInfo pre-computed label/value rows considered safe to share
     *                   (the caller must exclude IP and author rows).
     */
    fun buildReport(deviceInfo: List<Pair<String, String>>): String {
        val header = deviceInfo.joinToString("\n") { (label, value) ->
            "$label: ${redactIps(value)}"
        }
        val logs = readRecentLogs()
        return buildString {
            append(header)
            append("\n---\n")
            append(logs)
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Ava debug log", text))
    }

    private fun readRecentLogs(): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}")
            )
            val lines = BufferedReader(InputStreamReader(process.inputStream)).useLines { seq ->
                seq.filter { it.isNotBlank() }.toList()
            }
            process.destroy()
            lines.takeLast(MAX_LOG_LINES)
                .joinToString("\n") { redactIps(it) }
                .ifBlank { "(no logs)" }
        } catch (e: Exception) {
            "(failed to read logs: ${e.message})"
        }
    }

    private fun redactIps(text: String): String {
        return text
            .replace(IPV4_REGEX, "[ip]")
            .replace(IPV6_REGEX, "[ip]")
    }
}
