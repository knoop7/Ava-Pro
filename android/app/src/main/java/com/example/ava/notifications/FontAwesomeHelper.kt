package com.example.ava.notifications

import android.content.Context
import android.graphics.Typeface

/**
 * FontAwesome Solid helper against bundled FA 6.4.0 Free (`fonts/fa-solid-900.ttf`).
 * [ICONS] is the full picker catalog generated from that font.
 */
object FontAwesomeHelper {
    private var typeface: Typeface? = null

    /** Ordered (name → unicode) — curated scene icons first, then the rest of the font. */
    val ICONS: List<Pair<String, String>>
        get() = FontAwesomeSolidCatalog.ICONS

    /**
     * Names kept for existing scenes / HA JSON. Not shown as extra picker rows
     * because they alias a Free glyph already in [ICONS].
     */
    private val FALLBACKS: Map<String, String> = mapOf(
        "fa-alarm-clock" to "\uf017",
        "fa-party-horn" to "\uf79f",
        "fa-blinds-raised" to "\ue00d",
        "fa-popcorn" to "\ue131",
        "fa-sprinkler" to "\uf73d",
    )

    private val iconMap: Map<String, String> by lazy { ICONS.toMap() + FALLBACKS }

    fun loadFont(context: Context): Typeface {
        if (typeface == null) {
            typeface = Typeface.createFromAsset(context.assets, "fonts/fa-solid-900.ttf")
        }
        return typeface!!
    }

    fun getIconChar(iconClass: String): String = iconMap[iconClass] ?: "\uf0f3"
}
