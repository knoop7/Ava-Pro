package com.example.ava.localllm.remote

import android.content.Context
import com.example.ava.fleet.FleetShell
import com.example.ava.localllm.HaToolSet
import com.example.ava.localllm.ToolArgumentCase
import com.example.ava.localllm.ToolDef
import com.example.ava.localllm.ToolParam
import com.example.ava.localllm.ToolParamType
import org.json.JSONObject

/**
 * Voice-seat reuse of the Fleet console terminal ([FleetShell.exec], same
 * plane as `POST /v1/shell/exec`). Dump then input then dump; stdout is
 * the callback. No free command, no screenshot, no click into the
 * floating app window, no window toggle.
 */
object AvaShellTools {

    const val NAME = "ava_shell"
    const val PREFIX = "ava_shell"

    internal enum class DumpTarget { WINDOW, ACTIVITY, DISPLAY, TREE }

    private const val OUT_CAP = 4_000
    private const val LINE_CAP = 48
    private const val TEXT_CAP = 40
    private val KEEP_SYS = Regex(
        "mCurrentFocus|mFocusedApp|mFocusedWindow|mDisplayId|mOwnerPackage|" +
            "mActivityRecord|packageName=|imeLayeringTarget|mInputMethodWindow|" +
            "isVisible=true|VisibleRequested",
    )
    private val INPUT_TAP = Regex("^input tap \\d+ \\d+$")
    private val INPUT_SWIPE = Regex("^input swipe \\d+ \\d+ \\d+ \\d+( \\d+)?$")
    private val INPUT_KEY = Regex("^input keyevent \\d+$")
    private val INPUT_D_TAP = Regex("^input -d \\d+ tap \\d+ \\d+$")
    private val INPUT_D_SWIPE = Regex("^input -d \\d+ swipe \\d+ \\d+ \\d+ \\d+( \\d+)?$")
    private val INPUT_D_KEY = Regex("^input -d \\d+ keyevent \\d+$")
    private val KEYS = mapOf(
        "back" to 4,
        "home" to 3,
        "enter" to 66,
        "menu" to 82,
        "app_switch" to 187,
    )
    private val XML_TEXT = Regex("""(?:text|content-desc)="([^"]+)"""")
    private val XML_PKG = Regex("""package="([^"]+)"""")

    fun ready(): Boolean = runCatching { FleetShell.backend() != null }.getOrDefault(false)

    fun surface(): HaToolSet =
        if (!ready()) HaToolSet(emptyList()) else HaToolSet(listOf(schema()))

    fun schema(): ToolDef = ToolDef(
        NAME,
        "This device's displays: window focus and input through the built-in scrcpy/Fleet terminal. " +
            "The floating app window is a virtual display on that plane. Look at here_ui.kind / use — that is the object. " +
            "stdout is the callback. dump first, then tap/swipe/key, then dump again. " +
            "For a virtual display, dump display then tap display= that id. " +
            "Not ava_phone (apps / Accessibility nodes), not a computer adb session, not the Ava settings inbound-broadcast switch. " +
            "Do not screenshot. Do not type a free command.",
        listOf(
            ToolParam(
                "action",
                ToolParamType.Enum(listOf("dump", "tap", "swipe", "key")),
                "dump=read windows/activity/display/tree; tap/swipe/key=inject then dump again",
                required = true,
            ),
            ToolParam(
                "target",
                ToolParamType.Enum(listOf("window", "activity", "display", "tree")),
                "dump: window (default), activity, display, or tree",
                required = false,
            ),
            ToolParam("x", ToolParamType.Int(0, 10_000), "tap, or swipe start X", required = false),
            ToolParam("y", ToolParamType.Int(0, 10_000), "tap, or swipe start Y", required = false),
            ToolParam("x2", ToolParamType.Int(0, 10_000), "swipe end X", required = false),
            ToolParam("y2", ToolParamType.Int(0, 10_000), "swipe end Y", required = false),
            ToolParam("key", ToolParamType.Str, "key: BACK, HOME, ENTER, or a keyevent code", required = false),
            ToolParam("display", ToolParamType.Int(0, 99), "virtual display id from dump display", required = false),
        ),
        argumentCases = listOf(
            ToolArgumentCase(action = "dump", fields = setOf("target")),
            ToolArgumentCase(action = "tap", fields = setOf("x", "y", "display"), required = setOf("x", "y")),
            ToolArgumentCase(action = "swipe", fields = setOf("x", "y", "x2", "y2", "display"), required = setOf("x", "y", "x2", "y2")),
            ToolArgumentCase(action = "key", fields = setOf("key", "display"), required = setOf("key")),
        ),
    )

    suspend fun execute(call: AvaToolCallback.Call, app: Context): AvaToolCallback.Result {
        if (!ready()) {
            return AvaToolCallback.fail(
                "need_privilege",
                "Host terminal needs Shizuku or root — the same backend as the Fleet console.",
            )
        }
        val args = call.arguments
        return when (args.optString("action").trim()) {
            "dump" -> dump(args, app)
            "tap" -> tap(args, app)
            "swipe" -> swipe(args, app)
            "key" -> key(args, app)
            else -> AvaToolCallback.fail("invalid_request", "unknown action")
        }
    }

    /** Compact window focus for a here-question. Never called from the turn-start line. */
    fun peekDump(app: Context?): String? {
        val ctx = app ?: return null
        if (!ready()) return null
        val ran = runCatching { FleetShell.exec(ctx, dumpCommand(DumpTarget.WINDOW), 12) }.getOrNull()
            ?: return null
        return compactDump(DumpTarget.WINDOW, ran.stdout).takeIf { it.isNotBlank() }
    }

    internal fun dumpCommand(target: DumpTarget): String = when (target) {
        DumpTarget.WINDOW -> "dumpsys window windows"
        DumpTarget.ACTIVITY -> "dumpsys activity activities"
        DumpTarget.DISPLAY -> "dumpsys display"
        DumpTarget.TREE ->
            "uiautomator dump /data/local/tmp/ava-ui.xml >/dev/null && cat /data/local/tmp/ava-ui.xml"
    }

    internal fun tapCommand(x: Int, y: Int, display: Int?): String =
        "input ${displayOpt(display)}tap $x $y"

    internal fun swipeCommand(x: Int, y: Int, x2: Int, y2: Int, display: Int?, ms: Int = 300): String =
        "input ${displayOpt(display)}swipe $x $y $x2 $y2 $ms"

    internal fun keyCommand(code: Int, display: Int?): String =
        "input ${displayOpt(display)}keyevent $code"

    internal fun allowed(command: String): Boolean {
        val c = command.trim().replace(Regex("\\s+"), " ")
        if (c.isEmpty() || c.length > 400) return false
        if (c == dumpCommand(DumpTarget.WINDOW) ||
            c == dumpCommand(DumpTarget.ACTIVITY) ||
            c == dumpCommand(DumpTarget.DISPLAY) ||
            c == dumpCommand(DumpTarget.TREE)
        ) {
            return true
        }
        val lower = c.lowercase()
        return INPUT_TAP.matches(lower) ||
            INPUT_SWIPE.matches(lower) ||
            INPUT_KEY.matches(lower) ||
            INPUT_D_TAP.matches(lower) ||
            INPUT_D_SWIPE.matches(lower) ||
            INPUT_D_KEY.matches(lower)
    }

    internal fun compactDump(target: DumpTarget, stdout: String): String {
        val raw = stdout.replace("\r\n", "\n").trim()
        if (raw.isBlank()) return ""
        return when (target) {
            DumpTarget.TREE -> compactTree(raw)
            else -> compactSys(raw)
        }
    }

    internal fun parseDumpTarget(raw: String): DumpTarget? {
        val key = raw.trim().lowercase()
        if (key.isEmpty() || key == "window") return DumpTarget.WINDOW
        return when (key) {
            "activity" -> DumpTarget.ACTIVITY
            "display" -> DumpTarget.DISPLAY
            "tree" -> DumpTarget.TREE
            else -> null
        }
    }

    internal fun parseKey(raw: String): Int? {
        val key = raw.trim()
        if (key.isEmpty()) return null
        KEYS[key.lowercase()]?.let { return it }
        return key.toIntOrNull()?.takeIf { it in 0..288 }
    }

    internal fun nextDump(display: Int?): JSONObject {
        val target = if (display != null && display > 0) "display" else "window"
        return JSONObject()
            .put("tool", NAME)
            .put("arguments", JSONObject().put("action", "dump").put("target", target))
    }

    private fun dump(args: JSONObject, app: Context): AvaToolCallback.Result {
        val target = parseDumpTarget(args.optString("target"))
            ?: return AvaToolCallback.fail("invalid_request", "dump target is window, activity, display, or tree")
        val command = dumpCommand(target)
        val ran = run(app, command, 20) ?: return blocked()
        val stdout = compactDump(target, ran.stdout)
        val body = receipt(ran, "dump", command)
            .put("target", target.name.lowercase())
            .put("stdout", stdout)
            .put("hint", "stdout is the callback. Then tap/swipe/key; dump again. No screenshot.")
        return if (ran.ok || stdout.isNotBlank()) {
            AvaToolCallback.ok(body, status = "observed")
        } else {
            AvaToolCallback.fail("shell_failed", ran.error ?: "dump failed", body)
        }
    }

    private fun tap(args: JSONObject, app: Context): AvaToolCallback.Result {
        val x = intArg(args, "x") ?: return AvaToolCallback.fail("invalid_request", "tap needs x and y")
        val y = intArg(args, "y") ?: return AvaToolCallback.fail("invalid_request", "tap needs x and y")
        val display = displayArg(args)
        return inject(app, tapCommand(x, y, display), display, "tap")
    }

    private fun swipe(args: JSONObject, app: Context): AvaToolCallback.Result {
        val x = intArg(args, "x") ?: return AvaToolCallback.fail("invalid_request", "swipe needs x y x2 y2")
        val y = intArg(args, "y") ?: return AvaToolCallback.fail("invalid_request", "swipe needs x y x2 y2")
        val x2 = intArg(args, "x2") ?: return AvaToolCallback.fail("invalid_request", "swipe needs x y x2 y2")
        val y2 = intArg(args, "y2") ?: return AvaToolCallback.fail("invalid_request", "swipe needs x y x2 y2")
        val display = displayArg(args)
        return inject(app, swipeCommand(x, y, x2, y2, display), display, "swipe")
    }

    private fun key(args: JSONObject, app: Context): AvaToolCallback.Result {
        val code = parseKey(args.optString("key"))
            ?: return AvaToolCallback.fail("invalid_request", "key is BACK, HOME, ENTER, or a keyevent code")
        val display = displayArg(args)
        return inject(app, keyCommand(code, display), display, "key")
    }

    private fun inject(app: Context, command: String, display: Int?, action: String): AvaToolCallback.Result {
        val ran = run(app, command, 8) ?: return blocked()
        val body = receipt(ran, action, command)
            .put("accepted", ran.ok)
            .put("next_action", nextDump(display))
            .put("hint", "Input is not a land proof. dump again; stdout is the callback.")
        return if (ran.ok) {
            AvaToolCallback.ok(body, status = "accepted")
        } else {
            AvaToolCallback.fail("shell_failed", ran.error ?: "input failed", body)
        }
    }

    private fun run(app: Context, command: String, timeoutSec: Int): FleetShell.ExecResult? {
        if (!allowed(command)) return null
        return FleetShell.exec(app.applicationContext, command, timeoutSec)
    }

    private fun blocked(): AvaToolCallback.Result =
        AvaToolCallback.fail("invalid_request", "That command is not on the host terminal allowlist.")

    private fun receipt(ran: FleetShell.ExecResult, action: String, command: String): JSONObject {
        val body = JSONObject()
            .put("action", action)
            .put("ok", ran.ok)
            .put("code", ran.code)
            .put("via", "fleet_shell")
            .put("backend", ran.backend ?: JSONObject.NULL)
            .put("command", command)
            .put("stderr", ran.stderr.take(400))
        if (ran.error != null) body.put("error", ran.error)
        return body
    }

    private fun compactSys(raw: String): String {
        val kept = raw.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && KEEP_SYS.containsMatchIn(it) }
            .take(LINE_CAP)
            .toList()
        val src = if (kept.isNotEmpty()) {
            kept
        } else {
            val lines = raw.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
            lines.take(12) + if (lines.size > 24) listOf("…") + lines.takeLast(8) else lines.drop(12).take(8)
        }
        return clip(src.joinToString("\n"))
    }

    private fun compactTree(raw: String): String {
        val texts = LinkedHashSet<String>()
        for (match in XML_TEXT.findAll(raw)) {
            val t = match.groupValues[1].trim()
            if (t.isNotEmpty()) texts += t
            if (texts.size >= TEXT_CAP) break
        }
        val pkgs = LinkedHashSet<String>()
        for (match in XML_PKG.findAll(raw)) {
            val p = match.groupValues[1].trim()
            if (p.isNotEmpty()) pkgs += p
            if (pkgs.size >= 12) break
        }
        if (texts.isEmpty() && pkgs.isEmpty()) return clip(raw.take(200))
        return clip(
            buildString {
                if (pkgs.isNotEmpty()) append("packages: ").append(pkgs.joinToString(", ")).append('\n')
                if (texts.isNotEmpty()) append("texts: ").append(texts.joinToString(" | "))
            }.trim(),
        )
    }

    private fun clip(s: String): String =
        if (s.length <= OUT_CAP) s else s.take(OUT_CAP) + "\n…[truncated]"

    private fun displayOpt(display: Int?): String = display?.let { "-d $it " } ?: ""

    private fun displayArg(args: JSONObject): Int? = if (args.has("display") && !args.isNull("display")) {
        intArg(args, "display")
    } else {
        null
    }

    private fun intArg(args: JSONObject, key: String): Int? {
        if (!args.has(key) || args.isNull(key)) return null
        return when (val v = args.opt(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }
}
