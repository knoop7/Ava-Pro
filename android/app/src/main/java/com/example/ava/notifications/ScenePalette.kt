package com.example.ava.notifications

/**
 * Color chips for banner / scene editors.
 * Names match [NotificationScene.parseAnyColor] keys (without text-/bg- prefixes).
 */
object ScenePalette {
    data class Swatch(val name: String, val hex: String)

    /**
     * Six pastel presets (soft / fresh, not deep). Custom colors go through
     * the HSV picker; the banner default (white) is a dedicated slot in the
     * pickers, not part of this palette.
     */
    val SWATCHES: List<Swatch> = listOf(
        Swatch("amber-200", "#fde68a"),
        Swatch("rose-300", "#fda4af"),
        Swatch("purple-300", "#c4b5fd"),
        Swatch("sky-200", "#bae6fd"),
        Swatch("emerald-200", "#a7f3d0"),
        Swatch("stone-200", "#e7e5e4"),
    )

    /** Derive themeColors / icon / beam / divider / dot from a single primary hex. */
    fun deriveTheme(primaryHex: String): DerivedTheme {
        val primary = NotificationScene.parseHexColor(primaryHex)
        fun shade(factor: Float): String {
            val r = (android.graphics.Color.red(primary) * factor).toInt().coerceIn(0, 255)
            val g = (android.graphics.Color.green(primary) * factor).toInt().coerceIn(0, 255)
            val b = (android.graphics.Color.blue(primary) * factor).toInt().coerceIn(0, 255)
            return String.format("#%02x%02x%02x", r, g, b)
        }
        fun rgba(a: Float): String {
            val r = android.graphics.Color.red(primary)
            val g = android.graphics.Color.green(primary)
            val b = android.graphics.Color.blue(primary)
            return "rgba($r, $g, $b, $a)"
        }
        return DerivedTheme(
            themeColors = listOf(shade(1f), shade(0.85f), shade(0.55f), shade(0.4f)),
            iconColor = shade(1.05f.coerceAtMost(1.2f)).let {
                // Prefer lighter tint for icon on dark fullscreen bg
                String.format(
                    "#%02x%02x%02x",
                    (android.graphics.Color.red(primary) + (255 - android.graphics.Color.red(primary)) * 0.45f).toInt().coerceIn(0, 255),
                    (android.graphics.Color.green(primary) + (255 - android.graphics.Color.green(primary)) * 0.45f).toInt().coerceIn(0, 255),
                    (android.graphics.Color.blue(primary) + (255 - android.graphics.Color.blue(primary)) * 0.45f).toInt().coerceIn(0, 255),
                )
            },
            beamColor = rgba(0.8f),
            dividerColor = rgba(0.8f),
            dotColor = shade(1f),
        )
    }

    data class DerivedTheme(
        val themeColors: List<String>,
        val iconColor: String,
        val beamColor: String,
        val dividerColor: String,
        val dotColor: String,
    )

    fun isDarkHex(hex: String): Boolean {
        val c = NotificationScene.parseHexColor(hex)
        val r = android.graphics.Color.red(c)
        val g = android.graphics.Color.green(c)
        val b = android.graphics.Color.blue(c)
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) < 160.0
    }
}
