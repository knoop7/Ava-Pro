package com.example.ava.touchpad

import androidx.core.graphics.ColorUtils

/** Desaturated night chrome, gray-translucent day chrome. */
internal object TouchPadTheme {
    data class Palette(
        val bodyRgb: Int,
        val stroke: Int,
        val divider: Int,
        val surface: Int,
        val icon: Int,
        val close: Int,
        val handleStroke: Int,
        val handleHalo: Int,
        val cursorRgb: Int,
        val lightGlass: Boolean,
    )

    fun palette(dark: Boolean): Palette =
        if (dark) DARK else LIGHT

    fun bodyColor(dark: Boolean, opacity: Int): Int =
        (overlayAlpha(dark, opacity) shl 24) or palette(dark).bodyRgb

    fun overlayAlpha(dark: Boolean, opacity: Int): Int {
        val base = TouchPadMath.opacityByte(opacity)
        return if (dark) base else (base * 0.78f).toInt().coerceIn(40, 230)
    }

    /**
     * Touch Pad's own glass slab. Rim / sheen come from [com.example.ava.ui.glass.LiquidGlassDrawable];
     * this colour is the floating window itself, not a hole waiting for backdrop blur.
     */
    fun glassTint(dark: Boolean, opacity: Int): Int {
        val rgb = 0xFF000000.toInt() or palette(dark).bodyRgb
        val alpha = if (dark) {
            overlayAlpha(dark, opacity).coerceIn(100, 210)
        } else {
            overlayAlpha(dark, opacity).coerceIn(88, 190)
        }
        return ColorUtils.setAlphaComponent(rgb, alpha)
    }

    private val DARK = Palette(
        bodyRgb = 0x1C1D1F,
        stroke = 0x663A3C3E,
        divider = 0x26D6D6D6,
        surface = 0x141A1B1C,
        icon = 0xFFD6D6D6.toInt(),
        close = 0xFFB8B8B8.toInt(),
        handleStroke = 0xFFE8E8E8.toInt(),
        handleHalo = 0x59000000,
        cursorRgb = 0xE8E8E8,
        lightGlass = false,
    )

    const val HALT_FILL = 0xB3E24A4A.toInt()
    const val HALT_ICON = 0xFFFFFFFF.toInt()
    const val RECORD_FILL = 0x73D94848.toInt()
    const val RECORD_ICON = 0xE6FFFFFF.toInt()
    const val RECORD_TICK = 0x66FFFFFF
    const val RECORD_HAND = 0xE6FFFFFF.toInt()
    const val RECORD_SWEEP_MS = 60_000L

    private val LIGHT = Palette(
        bodyRgb = 0xD8DADC,
        stroke = 0x6690989A,
        divider = 0x1490989A,
        surface = 0x14F2F3F4,
        icon = 0xFF3C4043.toInt(),
        close = 0xFF5C6064.toInt(),
        handleStroke = 0xFF7A7E82.toInt(),
        handleHalo = 0x33000000,
        // Lighter, semi-transparent white-ish cursor for day mode
        cursorRgb = 0xA8ACB0,
        lightGlass = true,
    )
}
