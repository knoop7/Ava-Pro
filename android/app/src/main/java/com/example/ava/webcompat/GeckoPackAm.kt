package com.example.ava.webcompat

/**
 * Host-side `am` lines that start the gecko engine pack. Pack APK is not modified.
 *
 * Android 5–7 only have `am startservice`. Android 8+ prefers
 * `start-foreground-service` (required for a headless FGS) and falls back to
 * `startservice` when that verb is rejected. Bridge `am start` is the last
 * hop on every API.
 */
internal object GeckoPackAm {

    const val PACKAGE = "com.example.ava.gecko"
    const val SERVICE = "com.example.ava.services.WebViewService"
    const val BRIDGE = "com.example.ava.GeckoBrowserBridge"
    const val CHILD = "com.example.ava.GeckoChildControlReceiver"
    const val OREO_SDK = 26

    fun serviceVerbs(sdkInt: Int): List<String> =
        if (sdkInt >= OREO_SDK) {
            listOf("start-foreground-service", "startservice")
        } else {
            listOf("startservice")
        }

    fun shQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    fun serviceCommand(
        verb: String,
        action: String,
        url: String? = null,
        followDark: Boolean? = null,
        darkMode: Boolean? = null,
        receiptToken: Long = 0L,
    ): String = buildString {
        append("am ").append(verb)
        append(" --user 0")
        append(" -n $PACKAGE/$SERVICE")
        append(" -a ").append(action)
        appendExtras(url, followDark, darkMode, receiptToken)
    }

    fun bridgeCommand(
        bridgeAction: String,
        url: String? = null,
        followDark: Boolean? = null,
        darkMode: Boolean? = null,
        receiptToken: Long = 0L,
    ): String = buildString {
        append("am start --user 0")
        append(" -n $PACKAGE/$BRIDGE")
        append(" -a ").append(bridgeAction)
        appendExtras(url, followDark, darkMode, receiptToken)
    }

    /** Explicit broadcast into the child receiver — same plane as the Fleet ADB terminal. */
    fun childBroadcastCommand(
        bridgeAction: String,
        url: String? = null,
        followDark: Boolean? = null,
        darkMode: Boolean? = null,
        receiptToken: Long = 0L,
    ): String = buildString {
        append("am broadcast --user 0")
        append(" -n $PACKAGE/$CHILD")
        append(" -a ").append(bridgeAction)
        appendExtras(url, followDark, darkMode, receiptToken)
    }

    fun commands(
        sdkInt: Int,
        serviceAction: String?,
        bridgeAction: String?,
        url: String? = null,
        followDark: Boolean? = null,
        darkMode: Boolean? = null,
        receiptToken: Long = 0L,
    ): List<String> {
        val out = ArrayList<String>(5)
        if (!bridgeAction.isNullOrBlank()) {
            out.add(childBroadcastCommand(bridgeAction, url, followDark, darkMode, receiptToken))
        }
        if (!serviceAction.isNullOrBlank()) {
            for (verb in serviceVerbs(sdkInt)) {
                out.add(serviceCommand(verb, serviceAction, url, followDark, darkMode, receiptToken))
            }
        }
        if (!bridgeAction.isNullOrBlank()) {
            out.add(bridgeCommand(bridgeAction, url, followDark, darkMode, receiptToken))
        }
        return out
    }

    fun bridgeActionForService(serviceAction: String): String? = when (serviceAction) {
        "ACTION_SHOW" -> "com.example.ava.gecko.action.SHOW_BROWSER"
        "ACTION_HIDE" -> "com.example.ava.gecko.action.HIDE_BROWSER"
        "ACTION_DESTROY" -> "com.example.ava.gecko.action.DESTROY_BROWSER"
        "ACTION_SHOW_OR_REFRESH" -> "com.example.ava.gecko.action.NAVIGATE"
        "ACTION_REFRESH_DARK_MODE" -> "com.example.ava.gecko.action.REFRESH_DARK_MODE"
        "ACTION_CLEAR_CACHE" -> "com.example.ava.gecko.action.CLEAR_CACHE"
        "ACTION_FORCE_REFRESH" -> "com.example.ava.gecko.action.FORCE_REFRESH"
        "ACTION_RESTORE_FROM_SETTINGS" -> "com.example.ava.gecko.action.RESTORE_FROM_SETTINGS"
        else -> null
    }

    private fun StringBuilder.appendExtras(
        url: String?,
        followDark: Boolean?,
        darkMode: Boolean?,
        receiptToken: Long = 0L,
    ) {
        if (!url.isNullOrBlank()) {
            append(" --es url ").append(shQuote(url))
        }
        if (followDark != null) {
            append(" --ez follow_dark_mode ").append(followDark)
        }
        if (darkMode != null) {
            append(" --ez dark_mode ").append(darkMode)
        }
        if (receiptToken != 0L) {
            append(" --el receipt_token ").append(receiptToken)
        }
    }
}
