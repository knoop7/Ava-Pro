package com.example.ava.ui.screens.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import kotlin.math.min

/**
 * Icon shape options:
 * - circle: perfect circle mask
 * - squircle: iOS-style super-ellipse (default, 方圆形)
 * - rounded_square: large corner radius rectangle
 * - system: no mask — raw drawable as device provides
 */
enum class IconShape(val key: String) {
    CIRCLE("circle"),
    SQUIRCLE("squircle"),
    ROUNDED_SQUARE("rounded_square"),
    SYSTEM("system");

    companion object {
        fun fromKey(key: String): IconShape =
            entries.firstOrNull { it.key == key } ?: SQUIRCLE
    }
}

data class IconPackInfo(
    val packageName: String,
    val label: CharSequence,
    val icon: Drawable,
)

/**
 * Manages third-party icon pack discovery, loading, and icon resolution.
 *
 * Standard icon pack protocol:
 * - Declares intent filter: org.adw.launcher.THEMES / com.gau.go.launcherex.theme /
 *   com.novalauncher.THEME / com.anddoes.launcher.THEME
 * - Provides res/xml/appfilter.xml (or assets/appfilter.xml or res/raw/appfilter)
 *   mapping ComponentInfo{pkg/activity} → drawable name
 * - Optional iconback / iconmask / iconupon for fallback compositing
 */
object MinimalLauncherIconPackManager {

    private val ICON_PACK_INTENTS = listOf(
        "org.adw.launcher.THEMES",
        "com.gau.go.launcherex.theme",
        "com.novalauncher.THEME",
        "com.anddoes.launcher.THEME",
    )

    private val _available = MutableStateFlow<List<IconPackInfo>>(emptyList())
    val availableFlow: StateFlow<List<IconPackInfo>> = _available.asStateFlow()

    private val _currentPackage = MutableStateFlow("")
    val currentPackageFlow: StateFlow<String> = _currentPackage.asStateFlow()

    private val _iconShape = MutableStateFlow(IconShape.SQUIRCLE)
    val iconShapeFlow: StateFlow<IconShape> = _iconShape.asStateFlow()

    fun setShape(shape: IconShape) {
        if (_iconShape.value != shape) {
            _iconShape.value = shape
            ShapedIconCache.invalidate()
        }
    }

    @Volatile
    private var loadedPackage: String = ""
    private var componentMap: Map<String, String> = emptyMap()
    private var iconPackResources: Resources? = null
    private var iconPackPackageName: String = ""
    private var iconBackBitmaps: List<Bitmap> = emptyList()
    private var iconMaskBitmap: Bitmap? = null
    private var iconUponBitmap: Bitmap? = null
    private var scaleFactor: Float = 1.0f

    private val loadMutex = Mutex()

    /**
     * Discover installed icon packs. Runs on IO to avoid blocking the main thread
     * on devices with many installed packages.
     */
    suspend fun discoverPacks(context: Context) {
        val pm = context.applicationContext.packageManager
        val packs = withContext(Dispatchers.IO) {
            val seen = linkedSetOf<String>()
            val result = mutableListOf<IconPackInfo>()
            for (action in ICON_PACK_INTENTS) {
                val results = try {
                    pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
                } catch (_: Exception) {
                    emptyList()
                }
                for (ri in results) {
                    val pkg = ri.activityInfo?.packageName ?: continue
                    if (!seen.add(pkg)) continue
                    try {
                        result.add(
                            IconPackInfo(
                                packageName = pkg,
                                label = ri.loadLabel(pm),
                                icon = ri.loadIcon(pm),
                            )
                        )
                    } catch (_: Exception) { /* skip broken entries */ }
                }
            }
            result.sortedBy { it.label.toString().lowercase() }
        }
        _available.value = packs
    }

    suspend fun apply(context: Context, iconPackPackage: String) {
        if (iconPackPackage == loadedPackage) {
            _currentPackage.value = iconPackPackage
            return
        }
        val appContext = context.applicationContext
        loadMutex.withLock {
            if (iconPackPackage == loadedPackage) {
                _currentPackage.value = iconPackPackage
                return
            }
            if (iconPackPackage.isBlank()) {
                clearLoaded()
                _currentPackage.value = ""
            } else {
                try {
                    withContext(Dispatchers.IO) {
                        loadIconPack(appContext, iconPackPackage)
                    }
                } catch (_: Exception) {
                    clearLoaded()
                }
                _currentPackage.value = iconPackPackage
            }
        }
        // Pack drawables changed — drop shaped bitmaps and re-resolve app icons.
        // Never leave AppsCache empty without a follow-up load (that spins forever).
        ShapedIconCache.invalidate()
        MinimalLauncherAppsCache.markStale()
        val apps = MinimalLauncherAppsCache.load(appContext, appContext.packageName, force = true)
        val density = appContext.resources.displayMetrics.density
        warmShapedIconCache(apps, (72f * density).toInt())
    }

    fun getIconForComponent(packageName: String, activityName: String): Drawable? {
        if (componentMap.isEmpty()) return null
        val res = iconPackResources ?: return null
        val pkg = iconPackPackageName
        val key1 = "ComponentInfo{$packageName/$activityName}"
        componentMap[key1]?.let { name ->
            loadDrawableFromPack(res, pkg, name)?.let { return it }
        }
        // Some packs use short form when activity is in the app's package.
        if (activityName.startsWith(packageName)) {
            val shortActivity = activityName.removePrefix(packageName)
            val key2 = "ComponentInfo{$packageName/$shortActivity}"
            componentMap[key2]?.let { name ->
                loadDrawableFromPack(res, pkg, name)?.let { return it }
            }
        }
        return null
    }

    fun getIconWithFallback(
        context: Context?,
        packageName: String,
        activityName: String,
        defaultIcon: Drawable,
    ): Drawable {
        if (loadedPackage.isBlank()) return defaultIcon
        getIconForComponent(packageName, activityName)?.let { return it }
        return composeFallbackIcon(defaultIcon) ?: defaultIcon
    }

    private fun clearLoaded() {
        loadedPackage = ""
        componentMap = emptyMap()
        iconPackResources = null
        iconPackPackageName = ""
        iconBackBitmaps = emptyList()
        iconMaskBitmap = null
        iconUponBitmap = null
        scaleFactor = 1.0f
    }

    private fun loadIconPack(context: Context, pkg: String) {
        clearLoaded()
        val pm = context.packageManager
        val res: Resources = try {
            pm.getResourcesForApplication(pkg)
        } catch (_: Exception) {
            return
        }
        iconPackResources = res
        iconPackPackageName = pkg

        val map = mutableMapOf<String, String>()
        val backs = mutableListOf<Bitmap>()
        var mask: Bitmap? = null
        var upon: Bitmap? = null
        var factor = 1.0f

        val xpp = getAppFilterParser(res, pkg)
        if (xpp != null) {
            try {
                var eventType = xpp.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        when (xpp.name) {
                            "item" -> {
                                var component: String? = null
                                var drawable: String? = null
                                for (i in 0 until xpp.attributeCount) {
                                    when (xpp.getAttributeName(i)) {
                                        "component" -> component = xpp.getAttributeValue(i)
                                        "drawable" -> drawable = xpp.getAttributeValue(i)
                                    }
                                }
                                if (!component.isNullOrBlank() && !drawable.isNullOrBlank()) {
                                    map[component] = drawable
                                }
                            }
                            "iconback" -> {
                                for (i in 0 until xpp.attributeCount) {
                                    if (xpp.getAttributeName(i).startsWith("img")) {
                                        loadBitmapFromPack(res, pkg, xpp.getAttributeValue(i))
                                            ?.let { backs.add(it) }
                                    }
                                }
                            }
                            "iconmask" -> {
                                for (i in 0 until xpp.attributeCount) {
                                    if (xpp.getAttributeName(i).startsWith("img")) {
                                        mask = loadBitmapFromPack(res, pkg, xpp.getAttributeValue(i))
                                        break
                                    }
                                }
                            }
                            "iconupon" -> {
                                for (i in 0 until xpp.attributeCount) {
                                    if (xpp.getAttributeName(i).startsWith("img")) {
                                        upon = loadBitmapFromPack(res, pkg, xpp.getAttributeValue(i))
                                        break
                                    }
                                }
                            }
                            "scale" -> {
                                for (i in 0 until xpp.attributeCount) {
                                    if (xpp.getAttributeName(i) == "factor") {
                                        factor = xpp.getAttributeValue(i).toFloatOrNull() ?: 1.0f
                                    }
                                }
                            }
                        }
                    }
                    eventType = xpp.next()
                }
            } catch (_: Exception) {
                // Malformed XML — use whatever we parsed so far.
            }
        }
        componentMap = map
        iconBackBitmaps = backs
        iconMaskBitmap = mask
        iconUponBitmap = upon
        scaleFactor = factor
        loadedPackage = pkg
    }

    private fun getAppFilterParser(res: Resources, pkg: String): XmlPullParser? {
        // Try compiled XML resource first (res/xml/appfilter.xml).
        try {
            val id = res.getIdentifier("appfilter", "xml", pkg)
            if (id > 0) return res.getXml(id)
        } catch (_: Exception) { }

        // Some packs put it in res/raw/appfilter (compiled binary XML too).
        try {
            val rawId = res.getIdentifier("appfilter", "raw", pkg)
            if (rawId > 0) {
                val stream = res.openRawResource(rawId)
                return newPullParser(stream)
            }
        } catch (_: Exception) { }

        // Fallback: assets/appfilter.xml (plain-text XML).
        return try {
            val stream: InputStream = res.assets.open("appfilter.xml")
            newPullParser(stream)
        } catch (_: Exception) {
            null
        }
    }

    private fun newPullParser(stream: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newPullParser().apply { setInput(stream, "utf-8") }
    }

    private fun loadDrawableFromPack(res: Resources, pkg: String, name: String): Drawable? {
        if (name.isBlank()) return null
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) return null
        return try {
            ResourcesCompat.getDrawable(res, id, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadBitmapFromPack(res: Resources, pkg: String, name: String): Bitmap? {
        val drawable = loadDrawableFromPack(res, pkg, name) ?: return null
        return try {
            drawable.toBitmap(
                drawable.intrinsicWidth.coerceIn(1, 512),
                drawable.intrinsicHeight.coerceIn(1, 512),
            )
        } catch (_: Exception) { null }
    }

    private fun composeFallbackIcon(original: Drawable): Drawable? {
        if (iconBackBitmaps.isEmpty() && iconMaskBitmap == null && iconUponBitmap == null) {
            return null
        }
        val size = 192
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val back = iconBackBitmaps.randomOrNull()
        if (back != null) {
            canvas.drawBitmap(
                Bitmap.createScaledBitmap(back, size, size, true),
                0f, 0f, paint,
            )
        }

        val iconBmp = safeDrawableToBitmap(original, size)
        val scaledSize = (size * scaleFactor).toInt().coerceIn(1, size)
        val scaled = Bitmap.createScaledBitmap(iconBmp, scaledSize, scaledSize, true)
        val offset = (size - scaledSize) / 2f
        canvas.drawBitmap(scaled, offset, offset, paint)

        val maskBmp = iconMaskBitmap
        if (maskBmp != null) {
            val maskScaled = Bitmap.createScaledBitmap(maskBmp, size, size, true)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            canvas.drawBitmap(maskScaled, 0f, 0f, paint)
            paint.xfermode = null
        }

        val upon = iconUponBitmap
        if (upon != null) {
            canvas.drawBitmap(
                Bitmap.createScaledBitmap(upon, size, size, true),
                0f, 0f, paint,
            )
        }

        return BitmapDrawable(Resources.getSystem(), result)
    }
}

/**
 * Safely convert a [Drawable] to a [Bitmap], handling cases where intrinsicWidth/Height
 * are <= 0 (some vector drawables or color drawables). Never returns null or empty bitmap.
 *
 * For [AdaptiveIconDrawable], this uses the OEM system mask — only appropriate for
 * [IconShape.SYSTEM]. Prefer [renderAdaptiveIconFullBleed] when applying a custom shape.
 */
private fun safeDrawableToBitmap(drawable: Drawable, size: Int): Bitmap {
    val s = size.coerceAtLeast(1)
    return try {
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: s
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: s
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        if (w != s || h != s) {
            Bitmap.createScaledBitmap(bmp, s, s, true)
        } else {
            bmp
        }
    } catch (_: Exception) {
        Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Render adaptive bg + fg layers into a full square **without** the OEM
 * [AdaptiveIconDrawable] mask. Our [buildShapePath] then owns the silhouette
 * (circle / squircle / rounded square), matching the user's shape selection.
 */
private fun renderAdaptiveIconFullBleed(drawable: AdaptiveIconDrawable, size: Int): Bitmap {
    val s = size.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    // Layers are authored for an expanded viewport; negative inset crops to the
    // launcher-visible center (same math as AdaptiveIconDrawable itself).
    val inset = (s * AdaptiveIconDrawable.getExtraInsetFraction()).toInt()
    val left = -inset
    val top = -inset
    val right = s + inset
    val bottom = s + inset
    try {
        drawable.background?.mutate()?.let { layer ->
            layer.setBounds(left, top, right, bottom)
            layer.draw(canvas)
        }
        drawable.foreground?.mutate()?.let { layer ->
            layer.setBounds(left, top, right, bottom)
            layer.draw(canvas)
        }
    } catch (_: Exception) {
        // Fall back to masked system draw if layers fail.
        return safeDrawableToBitmap(drawable, s)
    }
    return bmp
}

/**
 * Apply a uniform shape mask to an icon bitmap.
 *
 * When an icon pack is active, artwork is returned as-is — packs already own
 * silhouette / mask / overlays and must not be re-cropped by our shape.
 *
 * With no pack: legacy icons get a tinted background before masking; adaptive
 * icons are rasterized full-bleed then clipped to the selected shape.
 */
fun applyIconShape(
    drawable: Drawable,
    shape: IconShape,
    sizePx: Int = 192,
    iconPackActive: Boolean = false,
): Bitmap {
    val size = sizePx.coerceAtLeast(1)
    // Pack icons (and SYSTEM) — no custom mask.
    if (iconPackActive || shape == IconShape.SYSTEM) {
        return safeDrawableToBitmap(drawable, size)
    }

    val isAdaptive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        drawable is AdaptiveIconDrawable

    val rawBmp = if (isAdaptive) {
        renderAdaptiveIconFullBleed(drawable as AdaptiveIconDrawable, size)
    } else {
        safeDrawableToBitmap(drawable, size)
    }
    val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val shapePath = buildShapePath(shape, size.toFloat())

    // clipPath is much cheaper than allocating a second mask bitmap + DST_IN.
    canvas.save()
    canvas.clipPath(shapePath)

    val needsBackground = !isAdaptive && needsAdaptiveBackground(rawBmp)
    if (needsBackground) {
        paint.color = extractBackgroundColorFast(rawBmp)
        paint.style = Paint.Style.FILL
        canvas.drawPath(shapePath, paint)
        paint.color = Color.BLACK
        val inset = size * 0.16f
        val iconSize = (size - inset * 2).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(rawBmp, iconSize, iconSize, true)
        canvas.drawBitmap(scaled, inset, inset, paint)
        if (scaled !== rawBmp) scaled.recycle()
    } else {
        canvas.drawBitmap(rawBmp, 0f, 0f, paint)
    }
    canvas.restore()

    return result
}

private fun buildShapePath(shape: IconShape, size: Float): Path {
    val path = Path()
    when (shape) {
        IconShape.CIRCLE -> {
            val r = size / 2f
            path.addCircle(r, r, r, Path.Direction.CW)
        }
        IconShape.SQUIRCLE -> {
            val r = size * 0.44f
            val rect = RectF(0f, 0f, size, size)
            path.addRoundRect(rect, r, r, Path.Direction.CW)
        }
        IconShape.ROUNDED_SQUARE -> {
            val r = size * 0.22f
            val rect = RectF(0f, 0f, size, size)
            path.addRoundRect(rect, r, r, Path.Direction.CW)
        }
        IconShape.SYSTEM -> {
            path.addRect(RectF(0f, 0f, size, size), Path.Direction.CW)
        }
    }
    return path
}

/**
 * Determine if a legacy icon needs an adaptive background.
 * Sparse edge sampling only — avoids dense getPixel loops during scroll.
 */
private fun needsAdaptiveBackground(bmp: Bitmap): Boolean {
    val w = bmp.width
    val h = bmp.height
    if (w < 4 || h < 4) return false

    var edgeTransparent = 0
    var edgeTotal = 0
    val step = (min(w, h) / 16).coerceAtLeast(4)

    for (x in 0 until w step step) {
        if (bmp.getPixel(x, 0) ushr 24 < 30) edgeTransparent++
        if (bmp.getPixel(x, h - 1) ushr 24 < 30) edgeTransparent++
        edgeTotal += 2
    }
    for (y in 0 until h step step) {
        if (bmp.getPixel(0, y) ushr 24 < 30) edgeTransparent++
        if (bmp.getPixel(w - 1, y) ushr 24 < 30) edgeTransparent++
        edgeTotal += 2
    }
    return edgeTransparent.toFloat() / edgeTotal.coerceAtLeast(1) > 0.40f
}

/** Fast opaque-center average — replaces Palette (was a major scroll hitch). */
private fun extractBackgroundColorFast(bmp: Bitmap): Int {
    return try {
        val w = bmp.width
        val h = bmp.height
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var n = 0
        val x0 = w / 4
        val y0 = h / 4
        val x1 = w - x0
        val y1 = h - y0
        val step = ((x1 - x0) / 4).coerceAtLeast(1)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = bmp.getPixel(x, y)
                if (p ushr 24 > 80) {
                    rSum += Color.red(p)
                    gSum += Color.green(p)
                    bSum += Color.blue(p)
                    n++
                }
                x += step
            }
            y += step
        }
        if (n == 0) return 0xFFE0E0E0.toInt()
        val r = (rSum / n * 0.8f + 255 * 0.2f).toInt().coerceIn(0, 255)
        val g = (gSum / n * 0.8f + 255 * 0.2f).toInt().coerceIn(0, 255)
        val b = (bSum / n * 0.8f + 255 * 0.2f).toInt().coerceIn(0, 255)
        Color.rgb(r, g, b)
    } catch (_: Exception) {
        0xFFE0E0E0.toInt()
    }
}

/** Bucket sizes so portrait/landscape near-misses share one cache entry. */
fun quantizeIconSizePx(sizePx: Int): Int =
    ((sizePx.coerceAtLeast(1) + 7) / 8 * 8).coerceIn(48, 256)

/**
 * Process-wide LRU cache for shaped icon bitmaps.
 * Keys on (packageName, activityName, shape, quantizedSize).
 */
object ShapedIconCache {
    private const val MAX_ENTRIES = 480

    private data class Key(
        val pkg: String,
        val activity: String,
        val shape: IconShape,
        val size: Int,
        val packVersion: String,
    )

    private val cache = object : LinkedHashMap<Key, Bitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>?): Boolean =
            size > MAX_ENTRIES
    }

    @Volatile
    private var generation = 0

    fun invalidate() {
        generation++
        synchronized(cache) { cache.clear() }
    }

    fun get(
        packageName: String,
        activityName: String,
        shape: IconShape,
        sizePx: Int,
    ): Bitmap? {
        val key = Key(packageName, activityName, shape, quantizeIconSizePx(sizePx), packKey())
        return synchronized(cache) { cache[key] }
    }

    fun put(
        packageName: String,
        activityName: String,
        shape: IconShape,
        sizePx: Int,
        bitmap: Bitmap,
    ) {
        val key = Key(packageName, activityName, shape, quantizeIconSizePx(sizePx), packKey())
        synchronized(cache) { cache[key] = bitmap }
    }

    private fun packKey(): String =
        "${MinimalLauncherIconPackManager.currentPackageFlow.value}:$generation"
}

/**
 * Warm the shaped-icon cache on a background dispatcher so All Apps scroll
 * hits memory instead of shaping on first paint.
 */
suspend fun warmShapedIconCache(
    apps: List<MinimalLauncherApp>,
    sizePx: Int,
) {
    val packActive = MinimalLauncherIconPackManager.currentPackageFlow.value.isNotBlank()
    // Pack artwork is never re-shaped; cache under SYSTEM to avoid shape churn.
    val shape = if (packActive) {
        IconShape.SYSTEM
    } else {
        MinimalLauncherIconPackManager.iconShapeFlow.value
    }
    val size = quantizeIconSizePx(sizePx)
    withContext(Dispatchers.Default) {
        for (app in apps) {
            if (ShapedIconCache.get(app.packageName, app.activityName, shape, size) != null) {
                continue
            }
            try {
                val bmp = applyIconShape(
                    app.icon,
                    shape,
                    size,
                    iconPackActive = packActive,
                )
                ShapedIconCache.put(app.packageName, app.activityName, shape, size, bmp)
            } catch (_: Exception) {
                // Skip broken drawables — cell will fall back at compose time.
            }
        }
    }
}

/**
 * Composable that provides a shaped icon bitmap asynchronously.
 * Cache hits return immediately with a remembered [ImageBitmap]-ready Bitmap.
 * Misses shape off the main thread; [shape]/[packPkg] should be collected once
 * by the parent to avoid N StateFlow subscriptions in a scrolling grid.
 *
 * When [packPkg] is non-blank, icons are rasterized as-is (pack design wins).
 */
@Composable
fun rememberShapedIconBitmap(
    drawable: Drawable,
    packageName: String,
    activityName: String,
    sizePx: Int,
    shape: IconShape = MinimalLauncherIconPackManager.iconShapeFlow.collectAsState().value,
    packPkg: String = MinimalLauncherIconPackManager.currentPackageFlow.collectAsState().value,
): Bitmap {
    val packActive = packPkg.isNotBlank()
    val effectiveShape = if (packActive) IconShape.SYSTEM else shape
    val size = quantizeIconSizePx(sizePx)
    val cached = ShapedIconCache.get(packageName, activityName, effectiveShape, size)
    if (cached != null) return cached
    val state = produceState(
        initialValue = when {
            packActive || effectiveShape == IconShape.SYSTEM ->
                safeDrawableToBitmap(drawable, size)
            // Custom shapes wait for full-bleed render so we never flash the OEM silhouette.
            else -> Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        },
        packageName,
        activityName,
        effectiveShape,
        size,
        packPkg,
    ) {
        val hit = ShapedIconCache.get(packageName, activityName, effectiveShape, size)
        if (hit != null) {
            value = hit
            return@produceState
        }
        val result = withContext(Dispatchers.Default) {
            applyIconShape(
                drawable,
                effectiveShape,
                size,
                iconPackActive = packActive,
            )
        }
        ShapedIconCache.put(packageName, activityName, effectiveShape, size, result)
        value = result
    }
    return state.value
}
