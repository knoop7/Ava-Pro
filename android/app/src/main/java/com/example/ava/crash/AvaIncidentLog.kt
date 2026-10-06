package com.example.ava.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.example.ava.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * The one record every abnormal end of Ava lands in.
 *
 * Ava already survived crashes ([CrashSelfHeal]) and could restart itself, but
 * neither left anything behind: the crash log file had no reader, and a
 * deliberate restart wrote nothing at all. This is the shared trace — a crash
 * with its stack, or a watchdog reacting to a wedged main thread and for how
 * long it was wedged.
 *
 * Written from a dying process, so the append path is deliberately dumb:
 *
 *  - one line per incident, appended; never read-modify-write. An OOM crash
 *    cannot afford to parse the whole history and build it again.
 *  - JSON is hand-built into one [StringBuilder]; no collections, no
 *    [JSONObject] on the write side.
 *  - internal [Context.filesDir], not the external dir the legacy crash log
 *    uses: always mounted, and stack traces stay out of reach of other apps.
 *  - over the size cap the file is *truncated*, not compacted. O(1) with no
 *    read, which is what a crash loop needs.
 *  - every entry point swallows everything, [Throwable] included. Losing a
 *    record must never break the restart it was describing.
 *
 * Parsing only ever happens on the read side, where a failure is harmless.
 */
object AvaIncidentLog {

    /** Uncaught Java/Kotlin exception, with stack. */
    const val KIND_CRASH_JAVA = "crash_java"

    /** Process death only the OS saw: native, signal, LMK, ANR. Claimed on read. */
    const val KIND_CRASH_NATIVE = "crash_native"

    /** Main thread stopped answering and the watchdog restarted Ava. */
    const val KIND_STALL_RESTART = "stall_restart"

    /** Main thread stopped answering; the watchdog only recorded it. */
    const val KIND_STALL_ONLY = "stall_only"

    /** Browser render process died; the overlay was rebuilt underneath. */
    const val KIND_RENDERER_CRASH = "renderer_crash"

    const val KIND_RESTART_USER = "restart_user"
    const val KIND_EXIT_USER = "exit_user"

    /** A settings file failed to parse and was replaced with defaults (original quarantined). */
    const val KIND_SETTINGS_CORRUPT = "settings_corrupt"

    /** Killed from outside: the Home Assistant diagnostic button. */
    const val KIND_KILL_REMOTE = "kill_remote"

    private const val FILE_NAME = "ava_incidents.log"
    private const val PREFS = "ava_incidents"
    private const val KEY_LAST_CLAIMED_EXIT = "last_claimed_exit_ts"

    /** Truncate rather than grow without bound; a crash loop must stay cheap. */
    private const val MAX_BYTES = 64L * 1024L

    /**
     * Keep at most this many. A crash still only appends; the extra lines are
     * dropped the next time someone reads (the log page or an authorised console).
     */
    private const val MAX_RECORDS = 20

    /** Per-record stack budget on disk. */
    private const val MAX_DETAIL_CHARS = 4_000

    /** Per-record stack budget on the wire, so a fleet response stays small. */
    private const val WIRE_DETAIL_CHARS = 2_000

    @Volatile
    private var file: File? = null

    @Volatile
    var exportEnabled: Boolean = false
        private set

    fun setExportEnabled(enabled: Boolean) {
        exportEnabled = enabled
    }

    data class Incident(
        val ts: Long,
        val kind: String,
        val reason: String,
        val detail: String,
        val stuckMs: Long,
        val uptimeMs: Long,
        val version: String,
    )

    /**
     * Resolves the log path up front so the crash path never has to. Cheap: a
     * path lookup, no I/O and no PackageManager call ([BuildConfig] carries the
     * version name).
     */
    fun init(context: Context) {
        runCatching { file = File(context.applicationContext.filesDir, FILE_NAME) }
    }

    /**
     * Appends one incident. Safe to call from an uncaught-exception handler.
     *
     * @param stuckMs how long the main thread was unresponsive, watchdog only.
     */
    fun record(
        context: Context?,
        kind: String,
        reason: String,
        detail: String? = null,
        stuckMs: Long = 0L,
        ts: Long = System.currentTimeMillis(),
    ) {
        try {
            val target = file ?: context?.let {
                File(it.applicationContext.filesDir, FILE_NAME).also { f -> file = f }
            } ?: return
            // stat only, no read. Dropping history beats an unbounded file.
            if (target.length() > MAX_BYTES) {
                runCatching { FileOutputStream(target, false).use { it.write(ByteArray(0)) } }
            }
            val line = buildLine(ts, kind, reason, detail, stuckMs)
            FileOutputStream(target, true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {
            // A lost record must never break the caller that was about to die.
        }
    }

    /**
     * Newest first, capped at [MAX_RECORDS]. Claims OS-recorded process deaths
     * on the way through, so nothing has to run at startup for them.
     */
    fun snapshot(context: Context): List<Incident> {
        claimSystemExits(context)
        compactIfNeeded(context)
        return readRecords(context)
    }

    /** Same records as [snapshot], as JSON with shorter stacks for the fleet API. */
    fun snapshotJson(context: Context, limit: Int = MAX_RECORDS): List<JSONObject> =
        snapshot(context).take(limit.coerceIn(1, MAX_RECORDS)).map { incident ->
            JSONObject()
                .put("ts", incident.ts)
                .put("kind", incident.kind)
                .put("reason", incident.reason)
                .put("detail", incident.detail.take(WIRE_DETAIL_CHARS))
                .put("stuckMs", incident.stuckMs)
                .put("uptimeMs", incident.uptimeMs)
                .put("version", incident.version)
        }

    fun clear(context: Context) {
        runCatching {
            val target = file ?: File(context.applicationContext.filesDir, FILE_NAME)
            if (target.exists()) target.delete()
        }
    }

    // ------------------------------------------------------------------
    // Write side — no collections, no JSONObject, no logging.
    // ------------------------------------------------------------------

    private fun buildLine(
        ts: Long,
        kind: String,
        reason: String,
        detail: String?,
        stuckMs: Long,
    ): String {
        val trimmed = detail?.take(MAX_DETAIL_CHARS).orEmpty()
        val sb = StringBuilder(trimmed.length + 256)
        sb.append("{\"ts\":").append(ts)
        sb.append(",\"kind\":\"").appendEscaped(kind).append('"')
        sb.append(",\"reason\":\"").appendEscaped(reason).append('"')
        sb.append(",\"stuckMs\":").append(stuckMs)
        sb.append(",\"uptimeMs\":").append(SystemClock.elapsedRealtime())
        sb.append(",\"version\":\"").appendEscaped(BuildConfig.VERSION_NAME).append('"')
        if (trimmed.isNotEmpty()) {
            sb.append(",\"detail\":\"").appendEscaped(trimmed).append('"')
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun StringBuilder.appendEscaped(raw: String): StringBuilder {
        for (c in raw) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(' ') else append(c)
            }
        }
        return this
    }

    // ------------------------------------------------------------------
    // Read side — parsing failures here are harmless.
    // ------------------------------------------------------------------

    /**
     * Drop the oldest lines once we are over [MAX_RECORDS]. Done on read, never
     * on the crash append path: rewriting a file while dying is how records get
     * lost. Original lines are kept as-is so timestamps and stacks stay exact.
     */
    private fun compactIfNeeded(context: Context) {
        val target = file ?: File(context.applicationContext.filesDir, FILE_NAME).also { file = it }
        if (!target.exists()) return
        val lines = runCatching { target.readLines() }.getOrNull() ?: return
        val parsed = ArrayList<Pair<Long, String>>(lines.size)
        for (raw in lines) {
            if (raw.isBlank()) continue
            val ts = runCatching { JSONObject(raw).optLong("ts") }.getOrNull() ?: continue
            parsed += ts to raw
        }
        if (parsed.size <= MAX_RECORDS) return
        val kept = parsed.sortedByDescending { it.first }.take(MAX_RECORDS).sortedBy { it.first }
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        runCatching {
            FileOutputStream(tmp, false).use { out ->
                for ((_, raw) in kept) {
                    out.write(raw.toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                }
            }
            if (target.exists() && !target.delete()) {
                tmp.delete()
                return
            }
            if (!tmp.renameTo(target)) tmp.delete()
        }.onFailure {
            runCatching { tmp.delete() }
        }
    }

    private fun readRecords(context: Context): List<Incident> {
        val target = file ?: File(context.applicationContext.filesDir, FILE_NAME).also { file = it }
        if (!target.exists()) return emptyList()
        val lines = runCatching { target.readLines() }.getOrNull() ?: return emptyList()
        val out = ArrayList<Incident>(MAX_RECORDS)
        for (raw in lines.asReversed()) {
            if (out.size >= MAX_RECORDS) break
            if (raw.isBlank()) continue
            val parsed = runCatching {
                val o = JSONObject(raw)
                Incident(
                    ts = o.optLong("ts"),
                    kind = o.optString("kind"),
                    reason = o.optString("reason"),
                    detail = o.optString("detail"),
                    stuckMs = o.optLong("stuckMs"),
                    uptimeMs = o.optLong("uptimeMs"),
                    version = o.optString("version"),
                )
            }.getOrNull() ?: continue
            out += parsed
        }
        // Claimed OS exits are appended after the fact, so file order is not
        // strictly chronological.
        return out.sortedByDescending { it.ts }
    }

    /**
     * Native crashes, signals, LMK kills and ANRs never reach a Java handler —
     * the OS is the only witness. [ActivityManager.getHistoricalProcessExitReasons]
     * is a binder call, so it runs here (first read) rather than at startup.
     * Already-claimed deaths are skipped by timestamp.
     */
    private fun claimSystemExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastClaimed = prefs.getLong(KEY_LAST_CLAIMED_EXIT, 0L)
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val history = am.getHistoricalProcessExitReasons(app.packageName, 0, 8)
            var newest = lastClaimed
            // Oldest first so the file keeps chronological order.
            for (info in history.asReversed()) {
                if (info.timestamp <= lastClaimed) continue
                if (info.timestamp > newest) newest = info.timestamp
                val kind = systemExitKind(info.reason) ?: continue
                record(
                    app,
                    kind = kind,
                    reason = systemExitReason(info),
                    detail = info.description.orEmpty(),
                    ts = info.timestamp,
                )
            }
            if (newest > lastClaimed) {
                prefs.edit().putLong(KEY_LAST_CLAIMED_EXIT, newest).apply()
            }
        } catch (_: Throwable) {
        }
    }

    /** Only deaths nothing else records. Java crashes and our own kills already have a line. */
    private fun systemExitKind(reason: Int): String? = when (reason) {
        ApplicationExitInfoCompat.REASON_CRASH_NATIVE,
        ApplicationExitInfoCompat.REASON_SIGNALED,
        -> KIND_CRASH_NATIVE
        ApplicationExitInfoCompat.REASON_LOW_MEMORY -> KIND_CRASH_NATIVE
        ApplicationExitInfoCompat.REASON_ANR -> KIND_STALL_ONLY
        else -> null
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun systemExitReason(info: android.app.ApplicationExitInfo): String = when (info.reason) {
        ApplicationExitInfoCompat.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfoCompat.REASON_SIGNALED -> "signal_${info.status}"
        ApplicationExitInfoCompat.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfoCompat.REASON_ANR -> "system_anr"
        else -> "exit_${info.reason}"
    }

    /** Constants exist only on API 30+; naming them here keeps the `when` readable. */
    private object ApplicationExitInfoCompat {
        const val REASON_LOW_MEMORY = 3
        const val REASON_CRASH_NATIVE = 5
        const val REASON_ANR = 6
        const val REASON_SIGNALED = 9
    }
}
