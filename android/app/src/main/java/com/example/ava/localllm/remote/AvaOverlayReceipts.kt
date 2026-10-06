package com.example.ava.localllm.remote

import com.example.ava.services.AiBrowserService
import com.example.ava.services.AppWindowService
import com.example.ava.services.DreamClockService
import com.example.ava.services.QuickEntityOverlayService
import com.example.ava.services.ScreensaverController
import com.example.ava.services.ScreensaverService
import com.example.ava.services.VinylCoverService
import com.example.ava.services.VoiceMessageOverlayService
import com.example.ava.services.WeatherOverlayService
import com.example.ava.services.WebViewService
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Receipts for Ava's own WindowManager overlays. A display-switch write
 * used to return only accepted/`on` — the model never saw what landed.
 * The host peeks the real window and puts `opened_overlays`. No screenshot.
 * Do not dump this table into the prompt.
 *
 * Voice-seat chrome (mic FAB, captions, touch pad, wake ripple, AOD cover)
 * is not a voice target and is not listed.
 */
internal object AvaOverlayReceipts {

    const val RESEARCH = "research"
    const val APP_WINDOW = "app_window"

    data class Spec(
        val id: String,
        val kind: String,
        val name: String,
    )

    val ALL: List<Spec> = listOf(
        Spec("browser_display", "ha_browser", "Home Assistant overlay"),
        Spec("dream_clock_display", "dream_clock", "Dream clock"),
        Spec("simple_clock_display", "simple_clock", "Simple clock"),
        Spec("screensaver_display", "screensaver", "Screensaver"),
        Spec("weather_display", "weather", "Weather overlay"),
        Spec("voice_message_display", "voice_message", "Voice message overlay"),
        Spec("vinyl_cover_display", "vinyl", "Music overlay"),
        Spec("quick_entity_display", "quick_entity", "Quick entity tiles"),
        Spec(RESEARCH, "research", "Research page"),
        Spec(APP_WINDOW, "app_window", "Floating app window"),
    )

    private val BY_ID = ALL.associateBy { it.id }

    fun isOverlayTarget(id: String): Boolean = BY_ID.containsKey(id.trim())

    fun kind(id: String): String? = BY_ID[id.trim()]?.kind

    fun spec(id: String): Spec? = BY_ID[id.trim()]

    fun visible(id: String): Boolean {
        val spec = spec(id) ?: return false
        return visible(spec)
    }

    fun visible(spec: Spec): Boolean = runCatching {
        when (spec.id) {
            "browser_display" -> WebViewService.isBrowserOverlayVisible()
            "dream_clock_display" -> DreamClockService.isOverlayShowing()
            "simple_clock_display" -> ScreensaverService.isOverlayShowing()
            "screensaver_display" -> ScreensaverController.isScreensaverVisible()
            "weather_display" -> WeatherOverlayService.isOverlayShowing()
            "voice_message_display" -> VoiceMessageOverlayService.isOverlayShowing()
            "vinyl_cover_display" -> VinylCoverService.isLiveOverlayShellVisible()
            "quick_entity_display" -> QuickEntityOverlayService.isOverlayShowing()
            RESEARCH -> AiBrowserService.isShowing()
            APP_WINDOW -> AppWindowService.hasWindowAttached()
            else -> false
        }
    }.getOrDefault(false)

    suspend fun awaitAndAttach(body: JSONObject, id: String, wanted: Boolean?): JSONObject {
        val spec = spec(id) ?: return body
        if (wanted != null && visible(spec) != wanted) {
            repeat(POLL) {
                delay(POLL_MS)
                if (visible(spec) == wanted) return attach(body, spec, wanted)
            }
        }
        return attach(body, spec, wanted)
    }

    fun attach(body: JSONObject, id: String, wanted: Boolean?): JSONObject {
        val spec = spec(id) ?: return body
        return attach(body, spec, wanted)
    }

    fun attach(body: JSONObject, spec: Spec, wanted: Boolean?): JSONObject {
        val seen = visible(spec)
        val overlays = JSONArray()
        overlays.put(row(spec, seen))
        for (other in ALL) {
            if (other.id == spec.id) continue
            if (visible(other)) overlays.put(row(other, true))
        }
        body.put("opened_overlays", overlays)
        body.put("overlay_opened", wanted != false && seen)
        if (wanted == false) body.put("overlay_closed", !seen)
        val hint = when {
            wanted == true && seen ->
                "Ava overlay callback — look at opened_overlays for kind/visible. No screenshot."
            wanted == false && !seen ->
                "Ava overlay callback — that window is closed. Look at opened_overlays. No screenshot."
            wanted == true && !seen ->
                "The host asked to show this overlay; the window is not up yet. Look at opened_overlays."
            wanted == false && seen ->
                "The host asked to hide this overlay; the window is still up. Look at opened_overlays."
            else ->
                "Ava overlay callback — look at opened_overlays. No screenshot."
        }
        val prior = body.optString("hint")
        body.put("hint", if (prior.isBlank()) hint else "$prior $hint")
        return body
    }

    private fun row(spec: Spec, seen: Boolean): JSONObject {
        val out = JSONObject()
            .put("kind", spec.kind)
            .put("id", spec.id)
            .put("name", spec.name)
            .put("visible", seen)
        if (spec.id == APP_WINDOW && seen) {
            val pkgs = runCatching { AppWindowService.openPackages() }.getOrDefault(emptyList())
            if (pkgs.isNotEmpty()) {
                val arr = JSONArray()
                pkgs.forEach { arr.put(it) }
                out.put("packages", arr)
            }
        }
        return out
    }

    private const val POLL = 6
    private const val POLL_MS = 200L
}
