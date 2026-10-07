package com.example.ava.ui.screens.home

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ava.R
import com.example.ava.appwindow.FreeformAppWindow
import com.example.ava.appwindow.ShellTrustedDisplay
import com.example.ava.sensors.DiagnosticSensorManager
import com.example.ava.services.AppWindowService
import com.example.ava.settings.PlayerSettings
import com.example.ava.ui.AvaToast
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ShizukuUtils
import java.text.Collator
import java.util.Locale

/**
 * Workspace grid dimensions. Invariant across orientation (Launcher3
 * InvariantDeviceProfile): rows/columns are chosen once per device from the
 * smallest screen width, so logical cell coordinates stay meaningful when the
 * device rotates — only the pixel size of each cell changes.
 */
data class MinimalLauncherGrid(val columns: Int, val rows: Int)

object MinimalLauncherGridSpec {
    /** Launcher3 InvariantDeviceProfile-style predefined grid table. */
    val DEFAULT = MinimalLauncherGrid(4, 4)

    /**
     * Comfortable drawable band (dp). Prefer staying in-band over forcing more
     * seats — typical phones stay at 4-across instead of crushing into 5.
     */
    const val ICON_SEAT_MIN_DP = 48f
    const val ICON_SEAT_MAX_DP = 72f
    const val ICON_PACK_GAP_DP = 4f

    /**
     * Per-side breathing room (dp) inside the page when packing icons.
     * Prevents the Nth icon from overflowing when the sidebar handle
     * overlays the edge or float rounding accumulates.
     */
    const val ICON_PACK_PAGE_INSET_DP = 5f

    /**
     * Preferred seat count along an edge of this length (dp).
     *
     * - &lt;300 → 3 (tiny)
     * - &lt;420 → 4 (phones — do not jump to 5)
     * - &lt;560 → 5
     * - &lt;700 → 6
     * - &lt;860 → 7
     * - else → 8
     *
     * [computeIconPack] may lower the count if min-size icons still overflow.
     */
    fun seatsForEdgeDp(edgeDp: Int): Int = when {
        edgeDp <= 0 -> 4
        edgeDp < 300 -> 3
        edgeDp < 420 -> 4
        edgeDp < 560 -> 5
        edgeDp < 700 -> 6
        edgeDp < 860 -> 7
        else -> 8
    }

    fun columnsForWidthDp(widthDp: Int): Int = seatsForEdgeDp(widthDp)

    fun gridForSmallestWidth(smallestWidthDp: Int): MinimalLauncherGrid {
        val n = seatsForEdgeDp(smallestWidthDp)
        return MinimalLauncherGrid(columns = n, rows = n.coerceAtLeast(3))
    }

    /**
     * Exact pack: `n * (icon * seatAlongFactor) + (n - 1) * gap <= edge`.
     *
     * Seat size tiles the edge (no artificial max that fights gaps). If the
     * result would be tinier than [ICON_SEAT_MIN_DP], drop a seat so icons grow.
     * Never inflate past [maxFit] — that was shoving the 4th icon off-page.
     */
    fun computeIconPack(
        edgePx: Float,
        density: Float,
        seatAlongFactor: Float = 1f,
        preferredSeats: Int? = null,
    ): Pair<Int, Float> {
        val d = density.coerceAtLeast(0.01f)
        val edge = edgePx.coerceAtLeast(1f)
        val edgeDp = edge / d
        val gapPx = ICON_PACK_GAP_DP * d
        val minIconPx = ICON_SEAT_MIN_DP * d
        val factor = seatAlongFactor.coerceAtLeast(1f)

        var n = (preferredSeats ?: seatsForEdgeDp(edgeDp.toInt())).coerceIn(2, 10)

        fun exactIcon(count: Int): Float {
            val denom = (count * factor).coerceAtLeast(0.01f)
            return ((edge - (count - 1) * gapPx) / denom).coerceAtLeast(1f)
        }

        var iconPx = exactIcon(n)
        // Too small → fewer seats (larger icons). Floor is 2 so the size adjuster can
        // offer a pair of large icons; auto packing still starts at 3+ via seatsForEdgeDp.
        while (n > 2 && iconPx < minIconPx) {
            n--
            iconPx = exactIcon(n)
        }
        // Absolute safety: never larger than what physically fits.
        val maxFit = exactIcon(n)
        iconPx = minOf(iconPx, maxFit)
        return n to iconPx
    }

    const val GRID_SPACING_DP = 10f
    const val ICON_SCALE = 0.84f
    const val ICON_MIN_DP = 60f
    const val ICON_MAX_DP = 80f
    const val CELL_PADDING_DP = 4f
    /** Launcher3 `dynamic_grid_edge_margin`. */
    const val EDGE_MARGIN_DP = 6f
    /**
     * Launcher3 phone workspace left/right:
     * `desiredWorkspaceLeftRightMarginPx = edgeMarginPx * 2`.
     */
    const val WORKSPACE_LEFT_RIGHT_MARGIN_DP = EDGE_MARGIN_DP * 2f

    /**
     * All Apps drawer padding. Shared deliberately: the column arithmetic has to subtract
     * exactly what the grid then lays out, and computing the two independently is what
     * used to let an icon come out wider than the cell holding it.
     */
    const val ALL_APPS_GRID_PADDING_H_DP = 14f
    /** Clears the panel's rounded corner so the first row is not cut into by the arc. */
    const val ALL_APPS_GRID_PADDING_TOP_DP = 22f

    /**
     * Lane reserved on the trailing edge for the A–Z strip.
     *
     * Always subtracted from the grid's width, even when too few letters exist to draw the
     * strip, so installing apps cannot silently change the column count.
     */
    const val ALL_APPS_FAST_SCROLLER_WIDTH_DP = 22f

    /** Floor shared with the workspace seat, below which an icon stops being tappable. */
    const val ALL_APPS_ICON_MIN_DP = ICON_SEAT_MIN_DP
    const val ALL_APPS_ICON_MAX_DP = 104f
    const val ALL_APPS_MIN_COLUMNS = 3
    const val ALL_APPS_MAX_COLUMNS = 12

    /** Resolved drawer cell: one source of truth for the grid and for icon sizing. */
    data class AllAppsCellSpec(
        val columns: Int,
        val cellWidthDp: Float,
        val iconDp: Float,
    )

    /**
     * Columns and icon size for a drawer panel [panelWidthDp] wide (screen minus system
     * bar insets), with [reservedTrailingDp] set aside for the fast-scroll strip.
     *
     * The icon fills the cell's content box exactly. That is what makes the gap between
     * two icons — cell padding on each side plus grid spacing — identical to the gap
     * between two rows, so the grid reads as evenly spaced in both directions instead of
     * tight across and loose down.
     *
     * Columns come from the device ladder, then move if the icon would not land in its
     * legible range. A fixed count is what left landscape phones and tablets with a few
     * ceiling-sized icons adrift in very wide cells.
     *
     * The ladder reads the full panel, not what is left after the strip and padding: those
     * are this drawer's own chrome, and letting them feed the lookup means a 320dp phone
     * lands just under a ladder step and quietly loses a column.
     */
    fun allAppsCellSpec(
        panelWidthDp: Float,
        reservedTrailingDp: Float = 0f,
    ): AllAppsCellSpec {
        val usable = (panelWidthDp - reservedTrailingDp - ALL_APPS_GRID_PADDING_H_DP * 2f)
            .coerceAtLeast(1f)
        fun cellFor(columns: Int): Float =
            (usable - GRID_SPACING_DP * (columns - 1)) / columns.coerceAtLeast(1)
        fun iconFor(columns: Int): Float = cellFor(columns) - CELL_PADDING_DP * 2f

        var columns = seatsForEdgeDp(panelWidthDp.toInt())
            .coerceIn(ALL_APPS_MIN_COLUMNS, ALL_APPS_MAX_COLUMNS)
        // Wide canvases: the ladder tops out at 8, which on a tablet leaves cells far
        // wider than an icon is allowed to be. Keep splitting while that is true.
        while (columns < ALL_APPS_MAX_COLUMNS && iconFor(columns) > ALL_APPS_ICON_MAX_DP) {
            columns++
        }
        // Narrow canvases: hand a column back rather than shrink past the legible floor.
        while (columns > ALL_APPS_MIN_COLUMNS && iconFor(columns) < ALL_APPS_ICON_MIN_DP) {
            columns--
        }
        val cellWidth = cellFor(columns)
        return AllAppsCellSpec(
            columns = columns,
            cellWidthDp = cellWidth,
            // Never clamped upward. The loops above already try to keep the cell roomy
            // enough, and on a panel too narrow for that, honouring the cell beats forcing
            // a floor the icon would have to spill out of.
            iconDp = (cellWidth - CELL_PADDING_DP * 2f)
                .coerceIn(1f, ALL_APPS_ICON_MAX_DP),
        )
    }
}

/** Device grid from the current (application) configuration. */
fun Context.minimalLauncherGrid(): MinimalLauncherGrid =
    MinimalLauncherGridSpec.gridForSmallestWidth(
        resources.configuration.smallestScreenWidthDp
    )

/** Header insets match the first/last icon edges in the grid below. */
data class MinimalLauncherHorizontalLayout(
    val gridHorizontalPadding: Dp,
    val headerStartPadding: Dp,
    val headerEndPadding: Dp
)

fun computeMinimalLauncherHorizontalLayout(
    screenWidthDp: Float,
    gridHorizontalPaddingDp: Float,
    columns: Int = MinimalLauncherGridSpec.DEFAULT.columns,
): MinimalLauncherHorizontalLayout {
    val gridSpacing = MinimalLauncherGridSpec.GRID_SPACING_DP
    val gridPad = gridHorizontalPaddingDp
    val availableWidth = screenWidthDp - gridPad * 2f - gridSpacing * (columns - 1)
    val columnWidth = (availableWidth / columns).coerceAtLeast(1f)
    val iconSize = (columnWidth * MinimalLauncherGridSpec.ICON_SCALE)
        .coerceIn(MinimalLauncherGridSpec.ICON_MIN_DP, MinimalLauncherGridSpec.ICON_MAX_DP)
    val firstIconLeft = gridPad + (columnWidth - iconSize) / 2f
    val lastIconRight =
        gridPad + (columns - 1) * (columnWidth + gridSpacing) + (columnWidth + iconSize) / 2f
    return MinimalLauncherHorizontalLayout(
        gridHorizontalPadding = gridPad.dp,
        headerStartPadding = firstIconLeft.dp,
        headerEndPadding = (screenWidthDp - lastIconRight).coerceAtLeast(0f).dp
    )
}

data class MinimalLauncherApp(
    val label: CharSequence,
    val icon: Drawable,
    val packageName: String,
    val activityName: String
)

/** Resolve a desktop shortcut back to a launcher activity for icon/label/launch. */
fun resolveMinimalLauncherApp(
    context: Context,
    packageName: String,
    activityName: String,
): MinimalLauncherApp? {
    val pm = context.packageManager
    return try {
        val component = android.content.ComponentName(packageName, activityName)
        val info = pm.getActivityInfo(component, 0)
        val defaultIcon = info.loadIcon(pm)
        val icon = MinimalLauncherIconPackManager.getIconWithFallback(
            context, packageName, activityName, defaultIcon,
        )
        MinimalLauncherApp(
            label = info.loadLabel(pm),
            icon = icon,
            packageName = packageName,
            activityName = activityName,
        )
    } catch (_: Exception) {
        null
    }
}

fun loadMinimalLauncherApps(context: Context, excludePackage: String): List<MinimalLauncherApp> {
    val pm = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveList: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, 0)
    return sortMinimalLauncherApps(resolveList.toMinimalLauncherApps(pm, excludePackage))
}

/**
 * Scoped re-query for a single package. Used by [MinimalLauncherAppsWatcher] to react to
 * install/update events without re-scanning every launcher activity on the device, mirroring
 * how real home-screen launchers (Launcher3, Nova, etc.) apply LauncherApps.Callback updates
 * incrementally instead of rebuilding the whole app list.
 */
fun loadMinimalLauncherAppsForPackage(
    context: Context,
    packageName: String,
    excludePackage: String
): List<MinimalLauncherApp> {
    if (packageName == excludePackage) return emptyList()
    val pm = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setPackage(packageName)
    val resolveList: List<ResolveInfo> =
        runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())
    return resolveList.toMinimalLauncherApps(pm, excludePackage)
}

private fun List<ResolveInfo>.toMinimalLauncherApps(
    pm: PackageManager,
    excludePackage: String
): List<MinimalLauncherApp> = mapNotNull { info ->
    val activityInfo = info.activityInfo ?: return@mapNotNull null
    val packageName = activityInfo.packageName
    if (packageName == excludePackage) return@mapNotNull null
    val defaultIcon = activityInfo.loadIcon(pm)
    val icon = MinimalLauncherIconPackManager.getIconWithFallback(
        null, packageName, activityInfo.name, defaultIcon,
    )
    MinimalLauncherApp(
        label = activityInfo.loadLabel(pm),
        icon = icon,
        packageName = packageName,
        activityName = activityInfo.name
    )
}

/**
 * Collation for the drawer, used for both sorting and fast-scroll bucketing.
 *
 * Follows [Locale.getDefault] rather than a fixed locale: pinning it to one language sorts
 * every other language by that language's rules, which puts accented and Cyrillic names in
 * places their readers would not look for them.
 *
 * The two callers must share it. Buckets are found by searching a sorted list, so a
 * different collator would return positions that do not match the order on screen.
 */
private fun drawerCollator(): Collator = Collator.getInstance(Locale.getDefault())

/** A–Z order under the device's own collation rules. */
fun sortMinimalLauncherApps(apps: List<MinimalLauncherApp>): List<MinimalLauncherApp> {
    val collator = drawerCollator()
    return apps.sortedWith { a, b ->
        collator.compare(a.label.toString(), b.label.toString())
    }
}

/** Fast-scroll slot: [firstIndex] is the list jump, or -1 when the letter is empty. */
data class MinimalLauncherAppSection(
    val label: String,
    val firstIndex: Int,
    val enabled: Boolean = firstIndex >= 0,
)

/**
 * Given a visible item index in the app grid, return the section index on the strip
 * that owns that item. Returns -1 if no match.
 */
fun sectionIndexForVisibleItem(
    itemIndex: Int,
    sections: List<MinimalLauncherAppSection>,
): Int {
    if (itemIndex < 0 || sections.isEmpty()) return -1
    var best = -1
    sections.forEachIndexed { i, section ->
        if (section.enabled && section.firstIndex <= itemIndex) {
            best = i
        }
    }
    return best
}

/** Strip label for anything that sorts ahead of the first letter — digits and symbols. */
internal const val SECTION_SYMBOL_LABEL = "#"

private val LATIN_ANCHORS = ('A'..'Z').map { it.toString() }
private val CYRILLIC_ANCHORS = ('А'..'Я').map { it.toString() }
private val GREEK_ANCHORS = listOf(
    "Α", "Β", "Γ", "Δ", "Ε", "Ζ", "Η", "Θ", "Ι", "Κ", "Λ", "Μ",
    "Ν", "Ξ", "Ο", "Π", "Ρ", "Σ", "Τ", "Υ", "Φ", "Χ", "Ψ", "Ω",
)
private val HEBREW_ANCHORS = listOf(
    "א", "ב", "ג", "ד", "ה", "ו", "ז", "ח", "ט", "י", "כ", "ל",
    "מ", "נ", "ס", "ע", "פ", "צ", "ק", "ר", "ש", "ת",
)
private val ARABIC_ANCHORS = listOf(
    "ا", "ب", "پ", "ت", "ث", "ج", "چ", "ح", "خ", "د", "ذ", "ر", "ز", "ژ",
    "س", "ش", "ص", "ض", "ط", "ظ", "ع", "غ", "ف", "ق", "ك", "گ", "ل", "م",
    "ن", "ه", "و", "ي",
)
private val HANGUL_CHOSEONG = listOf(
    "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ",
    "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
)
private val JAPANESE_GOJUON = listOf(
    "あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ", "ん",
)

private val GREEK_ANCHOR_SET = GREEK_ANCHORS.toHashSet()
private val HEBREW_ANCHOR_SET = HEBREW_ANCHORS.toHashSet()
private val ARABIC_ANCHOR_SET = ARABIC_ANCHORS.toHashSet()

/**
 * Fast-scroll sections for an already-sorted app list.
 *
 * The strip always paints A–Z so an English locale with CJK names still shows the
 * index; empty letters stay in place and are drawn gray. Other scripts append only
 * the index letters that actually have an app — never a raw CJK first character.
 *
 * Latin buckets are located by binary searching the collator, not by reading each
 * label's first character. That is what lets a Chinese locale drive a pinyin A–Z
 * strip with no romanisation table: ICU already collates Beijing between "b" and "c",
 * so asking where "B" would be inserted lands on exactly the right app.
 *
 * @param apps must already be ordered by [sortMinimalLauncherApps].
 */
fun minimalLauncherAppSections(
    apps: List<MinimalLauncherApp>,
): List<MinimalLauncherAppSection> = sectionsForDrawerLabels(apps.map { it.label.toString() })

internal fun sectionsForDrawerLabels(labels: List<String>): List<MinimalLauncherAppSection> {
    if (labels.isEmpty()) {
        return LATIN_ANCHORS.map { MinimalLauncherAppSection(it, -1) }
    }
    val collator = drawerCollator()
    fun lowerBound(key: String): Int {
        var lo = 0
        var hi = labels.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (collator.compare(labels[mid], key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }
    val sections = ArrayList<MinimalLauncherAppSection>(LATIN_ANCHORS.size + 8)
    val firstChar = labels.first().firstOrNull()
    if (firstChar != null && !firstChar.isLetter()) {
        sections += MinimalLauncherAppSection(SECTION_SYMBOL_LABEL, 0)
    }
    LATIN_ANCHORS.forEachIndexed { i, letter ->
        val start = lowerBound(letter)
        val end = if (i + 1 < LATIN_ANCHORS.size) {
            lowerBound(LATIN_ANCHORS[i + 1])
        } else {
            labels.size
        }
        sections += if (start < end) {
            MinimalLauncherAppSection(letter, start)
        } else {
            MinimalLauncherAppSection(letter, -1)
        }
    }
    // English collation parks some scripts entirely before "A". Those names are
    // still reachable through # instead of leaving every Latin slot gray.
    if (sections.none { it.enabled }) {
        sections.add(0, MinimalLauncherAppSection(SECTION_SYMBOL_LABEL, 0))
    }
    appendOccupiedScript(sections, labels, CYRILLIC_ANCHORS, ::cyrillicIndexLetter)
    appendOccupiedScript(sections, labels, GREEK_ANCHORS, ::greekIndexLetter)
    appendOccupiedScript(sections, labels, HANGUL_CHOSEONG, ::hangulIndexLetter)
    appendOccupiedScript(sections, labels, JAPANESE_GOJUON, ::japaneseIndexLetter)
    appendOccupiedScript(sections, labels, HEBREW_ANCHORS, ::hebrewIndexLetter)
    appendOccupiedScript(sections, labels, ARABIC_ANCHORS, ::arabicIndexLetter)
    return sections
}

private fun appendOccupiedScript(
    sections: MutableList<MinimalLauncherAppSection>,
    labels: List<String>,
    anchors: List<String>,
    classify: (String) -> String?,
) {
    val firstIndex = HashMap<String, Int>(anchors.size)
    labels.forEachIndexed { i, label ->
        val key = classify(label) ?: return@forEachIndexed
        if (key !in firstIndex) firstIndex[key] = i
    }
    if (firstIndex.isEmpty()) return
    for (anchor in anchors) {
        val start = firstIndex[anchor] ?: continue
        sections += MinimalLauncherAppSection(anchor, start)
    }
}

private fun firstLetter(label: String): Char? = label.firstOrNull { it.isLetter() }

internal fun cyrillicIndexLetter(label: String): String? {
    val letter = firstLetter(label)?.uppercaseChar() ?: return null
    if (letter == 'Ё') return "Е"
    if (letter in 'А'..'Я') return letter.toString()
    return null
}

internal fun greekIndexLetter(label: String): String? {
    val raw = firstLetter(label) ?: return null
    if (raw == 'ς') return "Σ"
    val letter = raw.uppercaseChar()
    return if (letter.toString() in GREEK_ANCHOR_SET) letter.toString() else null
}

internal fun hangulIndexLetter(label: String): String? {
    val ch = firstLetter(label) ?: return null
    val code = ch.code
    if (code in 0xAC00..0xD7A3) {
        return HANGUL_CHOSEONG.getOrNull((code - 0xAC00) / (21 * 28))
    }
    if (code in 0x1100..0x1112) {
        return HANGUL_CHOSEONG.getOrNull(code - 0x1100)
    }
    return HANGUL_COMPAT_CHOSEONG[ch]
}

internal fun japaneseIndexLetter(label: String): String? {
    val ch = firstLetter(label) ?: return null
    return gojuonRow(toHiragana(ch))
}

internal fun hebrewIndexLetter(label: String): String? {
    val ch = firstLetter(label) ?: return null
    val folded = when (ch) {
        'ך' -> 'כ'
        'ם' -> 'מ'
        'ן' -> 'נ'
        'ף' -> 'פ'
        'ץ' -> 'צ'
        else -> ch
    }
    return if (folded.toString() in HEBREW_ANCHOR_SET) folded.toString() else null
}

internal fun arabicIndexLetter(label: String): String? {
    val ch = firstLetter(label) ?: return null
    val folded = when (ch) {
        'أ', 'إ', 'آ', 'ٱ' -> "ا"
        'ة' -> "ه"
        'ى' -> "ي"
        'ؤ' -> "و"
        'ئ' -> "ي"
        'ک' -> "ك"
        else -> ch.toString()
    }
    return if (folded in ARABIC_ANCHOR_SET) folded else null
}

private val HANGUL_COMPAT_CHOSEONG = mapOf(
    'ㄱ' to "ㄱ", 'ㄲ' to "ㄲ", 'ㄴ' to "ㄴ", 'ㄷ' to "ㄷ", 'ㄸ' to "ㄸ",
    'ㄹ' to "ㄹ", 'ㅁ' to "ㅁ", 'ㅂ' to "ㅂ", 'ㅃ' to "ㅃ", 'ㅅ' to "ㅅ",
    'ㅆ' to "ㅆ", 'ㅇ' to "ㅇ", 'ㅈ' to "ㅈ", 'ㅉ' to "ㅉ", 'ㅊ' to "ㅊ",
    'ㅋ' to "ㅋ", 'ㅌ' to "ㅌ", 'ㅍ' to "ㅍ", 'ㅎ' to "ㅎ",
)

private fun toHiragana(ch: Char): Char {
    val code = ch.code
    if (code in 0x30A1..0x30F4) return (code - 0x60).toChar()
    return ch
}

private fun gojuonRow(ch: Char): String? = when (ch) {
    in 'ぁ'..'お', 'ゔ' -> "あ"
    in 'か'..'ご' -> "か"
    in 'さ'..'ぞ' -> "さ"
    in 'た'..'ど' -> "た"
    in 'な'..'の' -> "な"
    in 'は'..'ぽ' -> "は"
    in 'ま'..'も' -> "ま"
    in 'ゃ'..'よ' -> "や"
    in 'ら'..'ろ' -> "ら"
    in 'ゎ'..'を' -> "わ"
    'ん' -> "ん"
    else -> null
}

/**
 * Empty [visiblePackages] means no filter (show all). Non-empty is a package whitelist.
 */
fun filterMinimalLauncherApps(
    apps: List<MinimalLauncherApp>,
    visiblePackages: Collection<String>,
): List<MinimalLauncherApp> {
    if (visiblePackages.isEmpty()) return apps
    val allowed = visiblePackages.toHashSet()
    return apps.filter { it.packageName in allowed }
}

/**
 * Home Assistant may only see a true user-picked subset.
 * Empty whitelist (desktop "show all") and checking every installed launcher
 * app both count as "all" and must not expose the select entity.
 *
 * When [allLauncherPackages] is empty the installed set is still unknown
 * (cache not loaded); a non-empty whitelist is treated as an individual
 * selection until the real list arrives.
 */
fun canExposeMinimalLauncherAppsToHa(
    visiblePackages: Collection<String>,
    allLauncherPackages: Collection<String>,
): Boolean {
    if (visiblePackages.isEmpty()) return false
    if (allLauncherPackages.isEmpty()) return true
    val selected = visiblePackages.toHashSet()
    val hasUnselected = allLauncherPackages.any { it !in selected }
    val hasInstalledSelection = allLauncherPackages.any { it in selected }
    return hasUnselected && hasInstalledSelection
}

/** Whitelist ∩ installed launcher activities, one row per package. Empty when HA must stay off. */
fun haMinimalLauncherApps(
    context: Context,
    visiblePackages: Collection<String>,
): List<MinimalLauncherApp> {
    if (visiblePackages.isEmpty()) return emptyList()
    val all = loadMinimalLauncherApps(context, context.packageName)
    val allPackages = all.distinctPackagesForPicker().map { it.packageName }
    if (!canExposeMinimalLauncherAppsToHa(visiblePackages, allPackages)) return emptyList()
    return filterMinimalLauncherApps(all, visiblePackages).distinctPackagesForPicker()
}

fun uniqueHaLauncherOptions(
    apps: List<MinimalLauncherApp>,
    reservedLabels: Collection<String> = emptyList(),
): List<Pair<String, MinimalLauncherApp>> {
    val reserved = reservedLabels.toHashSet()
    val counts = apps.groupingBy { it.label.toString() }.eachCount()
    return apps.map { app ->
        val label = app.label.toString()
        val option = if ((counts[label] ?: 0) > 1 || label in reserved) {
            "$label (${app.packageName})"
        } else {
            label
        }
        option to app
    }
}

/** What selecting a launcher row in the Home Assistant select should do. */
enum class HaLauncherAction { LAUNCH, WINDOW_OPEN, WINDOW_CLOSE }

data class HaLauncherOption(
    val option: String,
    val app: MinimalLauncherApp,
    val action: HaLauncherAction,
)

/**
 * Rows for the HA launcher select. Apps on the floating-window list expand
 * into two **static** rows — "label (open window)" / "label (close window)" —
 * because ESPHome only sends a select's option table at entity listing, so
 * labels cannot morph when a window opens or closes at runtime. Every other
 * app keeps its single fullscreen-jump row, unchanged.
 */
fun haLauncherSelectOptions(
    context: Context,
    apps: List<MinimalLauncherApp>,
    windowedPackages: Collection<String>,
    reservedLabels: Collection<String> = emptyList(),
): List<HaLauncherOption> {
    val windowed = windowedPackages.toHashSet()
    return uniqueHaLauncherOptions(apps, reservedLabels).flatMap { (label, app) ->
        if (app.packageName in windowed) {
            listOf(
                HaLauncherOption(
                    context.getString(R.string.entity_minimal_launcher_app_window_open, label),
                    app,
                    HaLauncherAction.WINDOW_OPEN,
                ),
                HaLauncherOption(
                    context.getString(R.string.entity_minimal_launcher_app_window_close, label),
                    app,
                    HaLauncherAction.WINDOW_CLOSE,
                ),
            )
        } else {
            listOf(HaLauncherOption(label, app, HaLauncherAction.LAUNCH))
        }
    }
}

/**
 * Route a whitelisted app into the floating app window instead of a normal
 * launch. Returns true when consumed. Shared by the All Apps drawer and the
 * desktop icons so both entries behave the same.
 */
/**
 * Floating app windows need Android 10 (API 29)+: scrcpy's `new_display`
 * hard-refuses below that ("New virtual display is not supported before
 * Android 10"). Below Android 13 the shell lacks ADD_TRUSTED_DISPLAY, so the
 * virtual display runs *untrusted*: the lock screen occludes it, and an app
 * inside the window that launches another app may land on the main display.
 * We launch via the scrcpy control socket (shell uid = display owner), which
 * is the launch path that works on untrusted displays. On Android 13+ a 2025
 * security patch may have removed ADD_TRUSTED_DISPLAY from the shell entirely
 * (see [ShellTrustedDisplay]); [maybeLaunchInAppWindow] detects that and steps
 * those devices down to the freeform engine.
 */
fun isAppWindowSupported(): Boolean = Build.VERSION.SDK_INT >= 29

fun maybeLaunchInAppWindow(
    context: Context,
    playerSettings: PlayerSettings,
    packageName: String,
): Boolean {
    // No master switch: a package is "windowed" simply by being in the list.
    if (packageName !in playerSettings.appWindowPackages) return false
    // Two engines split by version: Android 10+ mirrors via scrcpy
    // ([AppWindowService], untouched here); Android 7.0–9 uses the system's
    // native freeform windowing ([FreeformAppWindow]). Android 13+ devices
    // whose 2025 security patch removed the shell's virtual-display permission
    // ([ShellTrustedDisplay]) step down to the freeform engine too — a mirror
    // there could only ever stay black. When a hard requirement is missing
    // each path explains via toast and returns false so the caller falls back
    // to a normal fullscreen launch.
    if (isAppWindowSupported()) {
        // canDrawOverlays is API 23+; this branch is already API 29+ but
        // keep the method call itself version-gated so D8 cannot outline it
        // onto a 21–22 path.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(context)
        ) {
            AvaToast.show(context, R.string.app_window_requires_overlay)
            return false
        }
        if (!ShizukuUtils.isShizukuPermissionGranted() && !RootUtils.isRootAvailable()) {
            AvaToast.show(context, R.string.app_window_requires_shizuku)
            return false
        }
        return when (ShellTrustedDisplay.cachedBlocked()) {
            false -> {
                AppWindowService.start(context, packageName)
                true
            }
            true -> launchFreeformInsteadOfMirror(context, packageName)
            null -> {
                // First windowed launch this process: the verdict needs one
                // shell round-trip, so resolve it off-thread and finish the
                // launch from the callback. The tap is consumed either way —
                // every branch below opens the app itself.
                val appContext = context.applicationContext
                ShellTrustedDisplay.resolveAsync { blocked ->
                    if (blocked) {
                        if (!launchFreeformInsteadOfMirror(appContext, packageName)) {
                            launchFullscreenFallback(appContext, packageName)
                        }
                    } else {
                        AppWindowService.start(appContext, packageName)
                    }
                }
                true
            }
        }
    }
    if (FreeformAppWindow.isSupported()) {
        if (!FreeformAppWindow.hasShell()) {
            AvaToast.show(context, R.string.app_window_requires_shizuku)
            return false
        }
        val launched = FreeformAppWindow.launch(context, packageName) {
            AvaToast.show(
                context,
                R.string.app_window_freeform_reboot,
                tag = AvaToast.APP_WINDOW_TAG,
                durationMs = AvaToast.LONG_MS,
            )
        }
        if (!launched) AvaToast.show(context, R.string.app_window_requires_shizuku)
        return launched
    }
    AvaToast.show(context, R.string.app_window_requires_android)
    return false
}

/**
 * The mirror window is blocked by the 2025 shell-permission removal: tell the
 * user why, then step down to the system freeform window (the one client-side
 * bypass that works — `am start --windowingMode 5` needs no display
 * permission). Returns false only when even freeform could not accept the
 * launch (no launchable app), so the caller opens fullscreen instead.
 */
private fun launchFreeformInsteadOfMirror(context: Context, packageName: String): Boolean {
    AvaToast.show(
        context,
        R.string.app_window_mirror_blocked,
        tag = AvaToast.APP_WINDOW_TAG,
        durationMs = AvaToast.LONG_MS,
    )
    return FreeformAppWindow.launch(context, packageName) {
        AvaToast.show(
            context,
            R.string.app_window_freeform_reboot,
            tag = AvaToast.APP_WINDOW_TAG,
            durationMs = AvaToast.LONG_MS,
        )
    }
}

/** Fullscreen bail-out for the async gate path, where the caller's own
 *  "return false → normal launch" fallback has already come and gone. */
private fun launchFullscreenFallback(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } ?: return
    runCatching { context.startActivity(intent) }
}

/** Same launch path as the desktop All Apps drawer and home-screen icons. */
fun launchMinimalLauncherApp(context: Context, app: MinimalLauncherApp): Boolean {
    if (MinimalLauncherTiles.isTile(app.packageName)) return false
    return try {
        context.startActivity(
            Intent()
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setComponent(ComponentName(app.packageName, app.activityName)),
        )
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Bring Ava's own desktop to the front. Does not force-stop the other app and
 * does not kill Ava — the other task stays in recents.
 */
fun bringAvaDesktopToFront(context: Context): Boolean {
    val app = context.applicationContext
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
    val home = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .setPackage(app.packageName)
        .addFlags(flags)
    if (runCatching { app.startActivity(home); true }.getOrDefault(false)) return true
    val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
    launch.addFlags(flags)
    return runCatching { app.startActivity(launch); true }.getOrDefault(false)
}

/**
 * Close the current *activity* (what the user sees), never Ava.
 *
 * Ava's voice service is a foreground service, so process importance cannot
 * tell which UI is on screen. Usage-stats resume events can. If the resumed
 * package is Ava (or the gecko engine pack) — or cannot be read — this is a
 * no-op so Home Assistant automations stay safe.
 */
fun closeCurrentForegroundIfNotAva(context: Context): Boolean {
    val pkg = foregroundActivityPackage(context) ?: return false
    if (isAvaFamilyPackage(context, pkg)) return false
    return bringAvaDesktopToFront(context)
}

private const val FOREGROUND_ACTIVITY_LOOKBACK_MS = 12L * 60 * 60 * 1000

private fun isAvaFamilyPackage(context: Context, packageName: String): Boolean {
    val mine = context.packageName
    if (packageName == mine) return true
    if (packageName == "$mine.gecko") return true
    if (mine.endsWith(".gecko") && packageName == mine.removeSuffix(".gecko")) return true
    return false
}

/** Last activity that moved to the foreground. Null without usage access. */
private fun foregroundActivityPackage(context: Context): String? {
    if (!DiagnosticSensorManager.hasUsageStatsPermission(context)) return null
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return null
    val now = System.currentTimeMillis()
    val events = runCatching {
        usm.queryEvents(now - FOREGROUND_ACTIVITY_LOOKBACK_MS, now)
    }.getOrNull() ?: return null
    val event = UsageEvents.Event()
    var lastPkg: String? = null
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        if (!isActivityForegroundEvent(event)) continue
        val pkg = event.packageName ?: continue
        if (pkg.isNotBlank()) lastPkg = pkg
    }
    return lastPkg
}

@Suppress("DEPRECATION")
private fun isActivityForegroundEvent(event: UsageEvents.Event): Boolean {
    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
    ) {
        return true
    }
    return false
}

/** One picker row per package (home may still show multiple launcher activities). */
fun List<MinimalLauncherApp>.distinctPackagesForPicker(): List<MinimalLauncherApp> =
    distinctBy { it.packageName }
