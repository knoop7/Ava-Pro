package com.example.ava.utils

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri

/**
 * Remote Intent executor for the ESPHome `launch_intent` service.
 *
 * Supports:
 * - Legacy / launch URIs (`spotify://`, `intent:#Intent;…`)
 * - `broadcast:` URI prefix
 * - Broadcast action/package/class/extras encoded in a single URI
 * - Home Assistant Companion-compatible structured fields for internal callers
 *
 * Merge precedence when `broadcast:` is combined with structured fields:
 * URI-local values win; structured `intent_*` fields fill gaps only.
 */
object IntentLauncher {
    private const val TAG = "IntentLauncher"
    const val BROADCAST_PREFIX = "broadcast:"

    data class LaunchResult(
        val success: Boolean,
        val message: String,
        val intentUri: String,
    )

    data class Request(
        val intentUri: String? = null,
        val intentAction: String? = null,
        val intentPackageName: String? = null,
        val intentClassName: String? = null,
        val intentExtras: String? = null,
        val intentType: String? = null,
        /** `broadcast` | `activity` | blank (auto). */
        val intentMode: String? = null,
    )

    private data class BroadcastFields(
        val action: String,
        val packageName: String,
        val className: String,
        val extras: String,
        /** Pre-built intent from `intent:#Intent;…` body, if any. */
        val parsedIntent: Intent? = null,
    )

    fun launch(context: Context, intentUri: String): LaunchResult =
        execute(context, Request(intentUri = intentUri))

    fun execute(context: Context, request: Request): LaunchResult {
        val uri = request.intentUri?.trim().orEmpty()
        val action = request.intentAction?.trim().orEmpty()
        val packageName = request.intentPackageName?.trim().orEmpty()
        val className = request.intentClassName?.trim().orEmpty()
        val extras = request.intentExtras?.trim().orEmpty()
        val type = request.intentType?.trim().orEmpty()
        val mode = request.intentMode?.trim()?.lowercase().orEmpty()

        val summary = buildSummary(uri, action, packageName, className, extras, type, mode)
        if (summary == "empty") {
            return LaunchResult(false, "Empty intent request", "")
        }

        return try {
            when (resolveMode(uri, action, packageName, className, extras, type, mode)) {
                Mode.BROADCAST -> sendBroadcast(context, uri, action, packageName, className, extras)
                Mode.ACTIVITY_STRUCTURED -> startStructuredActivity(
                    context, uri, action, packageName, className, extras, type,
                )
                Mode.LAUNCH_URI -> launchUri(context, uri)
            }
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Activity not found: $summary", e)
            if (uri.isNotBlank() && !uri.startsWith(BROADCAST_PREFIX, ignoreCase = true)) {
                tryFallback(context, uri, e.message ?: "Activity not found")
            } else {
                LaunchResult(false, e.message ?: "Activity not found", summary)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed intent request: $summary", e)
            LaunchResult(false, e.message ?: "Unknown error", summary)
        }
    }

    private enum class Mode {
        BROADCAST,
        ACTIVITY_STRUCTURED,
        LAUNCH_URI,
    }

    private fun resolveMode(
        uri: String,
        action: String,
        packageName: String,
        className: String,
        extras: String,
        type: String,
        mode: String,
    ): Mode {
        when (mode) {
            "broadcast", "broadcast_intent", "command_broadcast_intent" -> return Mode.BROADCAST
            "activity", "command_activity" -> {
                // Pure URI launch when no structured HA fields are present.
                return if (uri.isNotBlank() && !hasStructuredActivityFields(
                        action, packageName, className, extras, type,
                    )
                ) {
                    Mode.LAUNCH_URI
                } else {
                    Mode.ACTIVITY_STRUCTURED
                }
            }
        }
        if (uri.startsWith(BROADCAST_PREFIX, ignoreCase = true)) return Mode.BROADCAST
        // HA command_broadcast_intent: action + package, no data uri / mime type.
        // class/extras may still be present (HA allows them on broadcast).
        if (action.isNotBlank() && packageName.isNotBlank() && uri.isBlank() && type.isBlank()) {
            return Mode.BROADCAST
        }
        // Structured activity (HA command_activity): data uri + action/type/package/etc.
        // Must not fall through to LAUNCH_URI or package/type/extras are dropped.
        if (uri.isNotBlank() && hasStructuredActivityFields(
                action, packageName, className, extras, type,
            )
        ) {
            return Mode.ACTIVITY_STRUCTURED
        }
        if (uri.isNotBlank()) return Mode.LAUNCH_URI
        if (action.isNotBlank() || packageName.isNotBlank() || type.isNotBlank()) {
            return Mode.ACTIVITY_STRUCTURED
        }
        return Mode.ACTIVITY_STRUCTURED
    }

    private fun hasStructuredActivityFields(
        action: String,
        packageName: String,
        className: String,
        extras: String,
        type: String,
    ): Boolean =
        action.isNotBlank() ||
            packageName.isNotBlank() ||
            className.isNotBlank() ||
            extras.isNotBlank() ||
            type.isNotBlank()

    private fun sendBroadcast(
        context: Context,
        uri: String,
        action: String,
        packageName: String,
        className: String,
        extras: String,
    ): LaunchResult {
        val fields = resolveBroadcastFields(uri, action, packageName, className, extras)
        val intent = fields.parsedIntent?.also { parsed ->
            applyBroadcastTargets(context, parsed, fields)
        } ?: buildHaBroadcastIntent(
            context = context,
            action = fields.action,
            packageName = fields.packageName,
            className = fields.className,
            extras = fields.extras,
        )
        // Never attach activity flags to broadcasts.
        intent.flags = intent.flags and
            (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).inv()

        context.sendBroadcast(intent)
        val desc = intent.action ?: intent.component?.flattenToShortString() ?: "broadcast"
        Log.i(TAG, "Successfully broadcast: $desc package=${intent.`package`}")
        return LaunchResult(true, "OK (broadcast)", desc)
    }

    /**
     * Resolve broadcast fields from optional `broadcast:…` body + structured HA fields.
     *
     * Precedence (URI-local wins, structured fills gaps):
     * 1. Values embedded in the `broadcast:` body (`intent:`, query, or bare action)
     * 2. Structured `intent_action` / `intent_package_name` / `intent_class_name` / `intent_extras`
     * 3. Ava auto-package for `com.example.ava.*` actions (applied later)
     *
     * Non-`broadcast:` [uri] values are ignored here (launch data URIs must not become
     * broadcast actions when `intent_mode=broadcast` forces this path).
     */
    private fun resolveBroadcastFields(
        uri: String,
        structuredAction: String,
        structuredPackage: String,
        structuredClass: String,
        structuredExtras: String,
    ): BroadcastFields {
        if (!uri.startsWith(BROADCAST_PREFIX, ignoreCase = true)) {
            return BroadcastFields(
                action = structuredAction,
                packageName = structuredPackage,
                className = structuredClass,
                extras = structuredExtras,
            )
        }

        val body = uri.removePrefixIgnoreCase(BROADCAST_PREFIX).trim()
        if (body.isEmpty()) {
            // `broadcast:` with empty body → pure structured HA fields.
            return BroadcastFields(
                action = structuredAction,
                packageName = structuredPackage,
                className = structuredClass,
                extras = structuredExtras,
            )
        }

        when {
            body.startsWith("intent:", ignoreCase = true) ||
                body.startsWith("intent:#Intent", ignoreCase = true) -> {
                val parsed = Intent.parseUri(body, Intent.URI_INTENT_SCHEME)
                val action = parsed.action.orEmpty().ifBlank { structuredAction }
                if (action.isNotBlank()) parsed.action = action
                val packageName = parsed.`package`.orEmpty()
                    .ifBlank { parsed.component?.packageName.orEmpty() }
                    .ifBlank { structuredPackage }
                val className = parsed.component?.className.orEmpty().ifBlank { structuredClass }
                // intent: body may already carry extras; structured extras fill only when
                // the URI did not encode any (we cannot cheaply detect that), so apply as
                // additive fallback — same keys overwrite via putExtra.
                return BroadcastFields(
                    action = action,
                    packageName = packageName,
                    className = className,
                    extras = structuredExtras,
                    parsedIntent = parsed,
                )
            }
            isBroadcastQueryBody(body) -> {
                val params = parseBroadcastQuery(body)
                val inlineAction = body.substringBefore('?')
                    .takeIf { body.contains('?') && !it.contains('=') }
                    ?.let(Uri::decode)
                return BroadcastFields(
                    action = firstNonBlank(
                        inlineAction,
                        params["action"],
                        structuredAction,
                    ),
                    packageName = firstNonBlank(
                        params["package"],
                        params["intent_package_name"],
                        structuredPackage,
                    ),
                    className = firstNonBlank(
                        params["class"],
                        params["intent_class_name"],
                        structuredClass,
                    ),
                    extras = firstNonBlank(
                        params["extras"],
                        params["intent_extras"],
                        structuredExtras,
                    ),
                )
            }
            else -> {
                // Bare action string. Structured action must NOT override URI action
                // (HA required-field placeholders like action=packageName must lose).
                return BroadcastFields(
                    action = body,
                    packageName = structuredPackage,
                    className = structuredClass,
                    extras = structuredExtras,
                )
            }
        }
    }

    /** `action=…`, `?action=…`, or `a=1&b=2` style broadcast bodies. */
    private fun isBroadcastQueryBody(body: String): Boolean {
        if (body.contains('?') || body.contains('&')) {
            return body.contains('=')
        }
        // Single pair without delimiters: action=com.foo.BAR
        val eq = body.indexOf('=')
        if (eq <= 0) return false
        val key = body.substring(0, eq)
        return key == "action" || key == "package" || key == "class" || key == "extras" ||
            key == "intent_action" || key == "intent_package_name" ||
            key == "intent_class_name" || key == "intent_extras"
    }

    private fun parseBroadcastQuery(body: String): Map<String, String> {
        val query = body.substringAfter('?')
        return query.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null
            else Uri.decode(part.substring(0, eq)) to Uri.decode(part.substring(eq + 1))
        }.toMap()
    }

    private fun firstNonBlank(vararg values: String?): String {
        for (value in values) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isNotBlank()) return trimmed
        }
        return ""
    }

    private fun applyBroadcastTargets(
        context: Context,
        intent: Intent,
        fields: BroadcastFields,
    ) {
        if (fields.action.isNotBlank() && intent.action.isNullOrBlank()) {
            intent.action = fields.action
        }
        if (intent.`package`.isNullOrBlank() &&
            intent.component == null &&
            fields.packageName.isNotBlank()
        ) {
            intent.`package` = fields.packageName
        }
        if (fields.className.isNotBlank() && intent.component == null) {
            val pkg = intent.`package`.orEmpty().ifBlank { fields.packageName }
            require(pkg.isNotBlank()) {
                "Broadcast URI requires a package when a class is set"
            }
            intent.setClassName(pkg, fields.className)
        }
        if (fields.extras.isNotBlank()) {
            HaIntentExtras.addExtrasToIntent(intent, fields.extras)
        }
        ensureExplicitPackage(context, intent)
    }

    private fun buildHaBroadcastIntent(
        context: Context,
        action: String,
        packageName: String,
        className: String,
        extras: String,
    ): Intent {
        require(action.isNotBlank()) {
            "Broadcast URI requires an action"
        }
        val resolvedPackage = packageName.ifBlank {
            // Keep Ava control actions explicit; other actions may be implicit broadcasts.
            if (action.startsWith("com.example.ava.")) context.packageName else ""
        }
        require(className.isBlank() || resolvedPackage.isNotBlank()) {
            "Broadcast requires a package when a class is set"
        }

        val intent = Intent(action)
        if (extras.isNotBlank()) {
            HaIntentExtras.addExtrasToIntent(intent, extras)
        }
        if (resolvedPackage.isNotBlank()) {
            intent.`package` = resolvedPackage
        }
        if (className.isNotBlank()) {
            intent.setClassName(resolvedPackage, className)
        }
        return intent
    }

    private fun ensureExplicitPackage(context: Context, intent: Intent) {
        if (!intent.`package`.isNullOrBlank() || intent.component != null) return
        val action = intent.action.orEmpty()
        if (action.startsWith("com.example.ava.")) {
            intent.`package` = context.packageName
        }
    }

    private fun startStructuredActivity(
        context: Context,
        uri: String,
        action: String,
        packageName: String,
        className: String,
        extras: String,
        type: String,
    ): LaunchResult {
        // Mirrors HA MessagingManager.processActivityCommand.
        // [uri] is the data URI here — never a `broadcast:` body (those resolve to BROADCAST).
        val intent = Intent()
        if (action.isNotBlank()) intent.action = action
        val dataUri = uri.takeIf { it.isNotBlank() }?.toUri()
        if (dataUri != null || type.isNotBlank()) {
            intent.setDataAndType(dataUri, type.ifBlank { null })
        }
        if (className.isNotBlank() && packageName.isNotBlank()) {
            intent.component = ComponentName(packageName, className)
        }
        if (extras.isNotBlank()) {
            HaIntentExtras.addExtrasToIntent(intent, extras)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (packageName.isNotBlank()) {
            intent.setPackage(packageName)
            context.startActivity(intent)
        } else if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            return LaunchResult(
                false,
                "No activity resolves this intent (need intent_package_name or a resolvable action/uri)",
                action.ifBlank { uri },
            )
        }
        Log.i(TAG, "Successfully started activity: action=$action package=$packageName uri=$uri")
        return LaunchResult(true, "OK (activity)", action.ifBlank { uri })
    }

    private fun launchUri(context: Context, intentUri: String): LaunchResult {
        val intent = parseIntent(intentUri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(intent)
        Log.i(TAG, "Successfully launched: $intentUri")
        return LaunchResult(true, "OK", intentUri)
    }

    private fun parseIntent(intentUri: String): Intent {
        return when {
            intentUri.startsWith("intent:") || intentUri.startsWith("intent:#Intent") -> {
                Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
            }
            intentUri.contains("://") -> {
                Intent(Intent.ACTION_VIEW, Uri.parse(intentUri))
            }
            intentUri.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$", RegexOption.IGNORE_CASE)) -> {
                Intent().apply {
                    setPackage(intentUri)
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
            }
            else -> {
                Intent(Intent.ACTION_VIEW, Uri.parse(intentUri))
            }
        }
    }

    private fun tryFallback(context: Context, intentUri: String, originalError: String): LaunchResult {
        val packageName = extractPackageName(intentUri)
        if (packageName != null) {
            return try {
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(marketIntent)
                LaunchResult(false, "App not installed, opened store: $packageName", intentUri)
            } catch (_: Exception) {
                LaunchResult(false, originalError, intentUri)
            }
        }
        return LaunchResult(false, originalError, intentUri)
    }

    private fun extractPackageName(intentUri: String): String? {
        val packagePattern = Regex("package=([^;]+)")
        packagePattern.find(intentUri)?.let {
            return it.groupValues[1]
        }
        if (intentUri.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$", RegexOption.IGNORE_CASE))) {
            return intentUri
        }
        return null
    }

    private fun buildSummary(
        uri: String,
        action: String,
        packageName: String,
        className: String,
        extras: String,
        type: String,
        mode: String,
    ): String {
        if (uri.isBlank() && action.isBlank() && packageName.isBlank()) return "empty"
        return buildString {
            if (mode.isNotBlank()) append("mode=$mode;")
            if (uri.isNotBlank()) append("uri=$uri;")
            if (action.isNotBlank()) append("action=$action;")
            if (packageName.isNotBlank()) append("package=$packageName;")
            if (className.isNotBlank()) append("class=$className;")
            if (type.isNotBlank()) append("type=$type;")
            if (extras.isNotBlank()) append("extras=${extras.take(80)};")
        }
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
}
