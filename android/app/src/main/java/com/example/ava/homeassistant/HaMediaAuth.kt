package com.example.ava.homeassistant

/**
 * Process-wide snapshot of the signed-in HA session for still and camera-stream fetches.
 * Token stays in memory only and is attached as Authorization, never as a URL query.
 */
object HaMediaAuth {
    @Volatile
    var serverUrl: String = ""
        private set

    @Volatile
    private var accessToken: String = ""

    @Volatile
    var enabled: Boolean = true
        private set

    val signedIn: Boolean
        get() = enabled && serverUrl.isNotBlank() && accessToken.isNotBlank()

    fun update(url: String, token: String, enabled: Boolean = this.enabled) {
        serverUrl = url.trim().trimEnd('/')
        accessToken = token.trim()
        this.enabled = enabled
    }

    fun clear() {
        serverUrl = ""
        accessToken = ""
        enabled = true
    }

    fun bearerHeader(): String? =
        if (signedIn) "Bearer $accessToken" else null
}
