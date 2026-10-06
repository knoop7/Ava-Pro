package com.example.ava.settings

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import kotlin.math.roundToInt

/**
 * User-adjustable interface scale for devices that misreport display density.
 *
 * White-label panels often ship a too-low ro.sf.lcd_density: every sp/dp renders
 * physically tiny and the reported screenWidthDp inflates past every layout
 * breakpoint (settings split, AdaptiveSpec tiers). There is no trustworthy
 * device-side signal to auto-correct this — xdpi/ydpi are just as often garbage,
 * and a huge TV legitimately reports the same numbers — so the fix is a manual
 * scale the user sets once, plus a one-time hint when the configuration looks
 * out of envelope (see [shouldOfferHint]).
 *
 * Stored in SharedPreferences (not DataStore) because the value must be read
 * synchronously in attachBaseContext, before any coroutine machinery exists.
 *
 * The default scale short-circuits [wrap] to the untouched context, so devices
 * that never touch the setting keep today's rendering bit for bit.
 */
object DisplayScale {
    const val MIN_SCALE = 0.75f
    const val MAX_SCALE = 2.0f
    const val DEFAULT_SCALE = 1.0f

    /**
     * Longest-side threshold for the "UI looks tiny?" hint. The largest honest
     * tablets report ~1480dp in landscape (14.6" QHD class); misconfigured
     * panels seen in the field land at 1700dp+. TVs also exceed this but are
     * filtered by uiMode and the touchscreen feature check in [shouldOfferHint].
     */
    private const val HINT_LONGEST_SIDE_MIN_DP = 1560

    private const val PREFS_NAME = "display_scale_prefs"
    private const val KEY_SCALE = "scale"

    private var hintShownThisSession = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getScale(context: Context): Float =
        prefs(context).getFloat(KEY_SCALE, DEFAULT_SCALE).coerceIn(MIN_SCALE, MAX_SCALE)

    fun setScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_SCALE, scale.coerceIn(MIN_SCALE, MAX_SCALE)).apply()
    }

    fun isCustomized(context: Context): Boolean = getScale(context) != DEFAULT_SCALE

    /**
     * Wraps [context] so density and the dp-based Configuration fields move
     * together: scaling up multiplies densityDpi and shrinks screenWidthDp /
     * screenHeightDp / smallestScreenWidthDp by the same factor, keeping
     * LocalDensity and LocalConfiguration consistent for every breakpoint.
     * Returns [context] untouched at the default scale.
     */
    fun wrap(context: Context): Context {
        val scale = getScale(context)
        if (scale == DEFAULT_SCALE) return context
        val base = context.resources.configuration
        val config = Configuration(base).apply {
            densityDpi = (base.densityDpi * scale).roundToInt().coerceAtLeast(72)
            if (base.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
                screenWidthDp = (base.screenWidthDp / scale).roundToInt()
            }
            if (base.screenHeightDp != Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
                screenHeightDp = (base.screenHeightDp / scale).roundToInt()
            }
            if (base.smallestScreenWidthDp != Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
                smallestScreenWidthDp = (base.smallestScreenWidthDp / scale).roundToInt()
            }
        }
        return context.createConfigurationContext(config)
    }

    /**
     * True when the reported configuration is outside the design envelope and
     * the user has never adjusted the scale. TVs legitimately report huge dp
     * widths, so television uiMode and touchless devices are excluded. Fires at
     * most once per process so the hint stays a nudge, not a nag.
     */
    fun shouldOfferHint(context: Context): Boolean {
        if (hintShownThisSession) return false
        if (isCustomized(context)) return false
        val cfg = context.resources.configuration
        if (maxOf(cfg.screenWidthDp, cfg.screenHeightDp) < HINT_LONGEST_SIDE_MIN_DP) return false
        if (cfg.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION) return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return false
        return true
    }

    fun markHintShown() {
        hintShownThisSession = true
    }
}
