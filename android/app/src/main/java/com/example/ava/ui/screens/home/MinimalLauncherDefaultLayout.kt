package com.example.ava.ui.screens.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings

/**
 * First-run desktop: the core-service card next to one block of common apps, so a new
 * user never meets a blank canvas.
 *
 * The card is always exactly as wide as the icon grid and as tall as its two rows, so
 * the two shapes line up on every edge instead of the card floating at its own size.
 *
 * Portrait — card over the grid, edges flush:
 *   [ Service Card (3×2) ]
 *   [Icon]  [Icon]  [Icon]
 *   [Icon]  [Set]   [Apps]
 *
 * Landscape — card beside the grid, tops flush:
 *   [ Service Card ]   [Ico] [Ico] [Ico]
 *   [    (3×2)     ]   [Ico] [Set] [App]
 */
object MinimalLauncherDefaultLayout {

    private const val PORTRAIT_MIN_TOP = 0.10f
    private const val PORTRAIT_LIFT = 0.08f

    private const val ICON_COLUMNS = 3
    private const val ICON_ROWS = 2

    /** Seats held back for the Settings and All Apps tiles, which always come last. */
    private const val TILE_SEATS = 2

    /**
     * Air between the card and the icon grid (dp).
     *
     * Deliberately NOT derived from [iconGapAxes], which returns the 4dp minimum air
     * that only stops icon seats from touching. The card fills its whole rect, so at
     * 4dp it reads as glued to the first icon row.
     */
    private const val BLOCK_SEAM_DP = 22f

    data class PageSize(val widthPx: Float, val heightPx: Float)

    class FirstRunPage(
        val icons: List<PlacedMinimalLauncherIcon>,
        val cards: List<PlacedMinimalLauncherWidget>,
    )

    /**
     * Writes the default page, or returns null if no layout could be computed at all.
     *
     * Null and zero mean different things and the caller depends on the difference: zero
     * is a page that holds only tiles, null is "nothing was written". Reporting both as
     * zero let a single failed attempt burn the one-shot first-run flag, which left the
     * user on a permanently blank desktop.
     */
    fun seedFirstRunDesktop(
        context: Context,
        apps: List<MinimalLauncherApp>,
        portrait: PageSize,
        landscape: PageSize,
        showDesktopLabels: Boolean,
    ): Int? {
        val page = build(context, apps, portrait, landscape, showDesktopLabels) ?: return null
        MinimalLauncherIconsStore.replaceAll(context, page.icons)
        page.cards.forEach { MinimalLauncherWidgetsStore.add(context, it) }
        return page.icons.count { !MinimalLauncherTiles.isTile(it.packageName) }
    }

    fun build(
        context: Context,
        apps: List<MinimalLauncherApp>,
        portrait: PageSize,
        landscape: PageSize,
        showDesktopLabels: Boolean,
    ): FirstRunPage? {
        val picks = resolveCommonApps(context, apps)
        if (picks.isEmpty()) return null
        val density = context.resources.displayMetrics.density
        val portGrid = SeedGrid.of(portrait, density, showDesktopLabels)
        val landGrid = SeedGrid.of(landscape, density, showDesktopLabels)

        val maxIconSeats = ICON_COLUMNS * ICON_ROWS
        val count = minOf(picks.size + TILE_SEATS, maxIconSeats)
        if (count <= TILE_SEATS) return null

        val portBlock = portGrid.portraitStacked(count) ?: return null
        val landBlock = landGrid.landscapeSideBySide(count) ?: return null

        val portSeats = portBlock.iconSeats
        val landSeats = landBlock.iconSeats
        val seatCount = minOf(portSeats.size, landSeats.size, count)

        val appIcons = picks.take(seatCount - TILE_SEATS).mapIndexed { index, app ->
            PlacedMinimalLauncherIcon(
                id = PlacedMinimalLauncherIcon.idFor(app.packageName, app.activityName),
                packageName = app.packageName,
                activityName = app.activityName,
                screen = 0,
                x = portSeats[index].x,
                y = portSeats[index].y,
                landScreen = 0,
                landX = landSeats[index].x,
                landY = landSeats[index].y,
            )
        }
        val settingsSeat = seatCount - 2
        val appsSeat = seatCount - 1
        val icons = appIcons +
            MinimalLauncherTiles.settingsAt(
                screen = 0,
                x = portSeats[settingsSeat].x,
                y = portSeats[settingsSeat].y,
                landScreen = 0,
                landX = landSeats[settingsSeat].x,
                landY = landSeats[settingsSeat].y,
            ) +
            MinimalLauncherTiles.allAppsAt(
                screen = 0,
                x = portSeats[appsSeat].x,
                y = portSeats[appsSeat].y,
                landScreen = 0,
                landX = landSeats[appsSeat].x,
                landY = landSeats[appsSeat].y,
            )

        // The card is shared by both orientations, so a page too tight for it in either
        // one drops it from both and the icons stand alone.
        val card = if (portBlock.card != null && landBlock.card != null) {
            MinimalLauncherTiles.serviceCardAt(
                screen = 0, rect = portBlock.card,
                landScreen = 0, landRect = landBlock.card,
            )
        } else {
            null
        }
        return FirstRunPage(icons = icons, cards = listOfNotNull(card))
    }

    // ---- capability resolution ----

    private fun capabilitySlots(): List<Intent> = listOf(
        Intent(Intent.ACTION_DIAL),
        mainCategory(Intent.CATEGORY_APP_MESSAGING),
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        mainCategory(Intent.CATEGORY_APP_GALLERY),
        // No browser slot: Ava's own Settings tile takes that seat instead. A voice
        // satellite is not a phone, and Chrome is the last thing this desktop needs.
        Intent(Settings.ACTION_SETTINGS),
        Intent(AlarmClock.ACTION_SHOW_ALARMS),
        mainCategory(Intent.CATEGORY_APP_CALENDAR),
        mainCategory(Intent.CATEGORY_APP_MUSIC),
        mainCategory(Intent.CATEGORY_APP_CALCULATOR),
        mainCategory(Intent.CATEGORY_APP_MARKET),
        mainCategory(Intent.CATEGORY_APP_MAPS),
        mainCategory(Intent.CATEGORY_APP_EMAIL),
        mainCategory(Intent.CATEGORY_APP_CONTACTS),
    )

    private fun mainCategory(category: String): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(category)

    private fun resolveCommonApps(
        context: Context,
        apps: List<MinimalLauncherApp>,
    ): List<MinimalLauncherApp> {
        if (apps.isEmpty()) return emptyList()
        val packageManager = context.packageManager
        val launcherEntry = LinkedHashMap<String, MinimalLauncherApp>()
        apps.forEach { app ->
            if (!launcherEntry.containsKey(app.packageName)) launcherEntry[app.packageName] = app
        }
        val picked = LinkedHashMap<String, MinimalLauncherApp>()
        capabilitySlots().forEach { intent ->
            val packageName = resolvePackage(packageManager, intent) ?: return@forEach
            if (picked.containsKey(packageName)) return@forEach
            val app = launcherEntry[packageName] ?: return@forEach
            picked[packageName] = app
        }
        return picked.values.toList()
    }

    private fun resolvePackage(packageManager: PackageManager, intent: Intent): String? {
        val preferred = runCatching {
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
        }.getOrNull()
        if (isConcretePackage(preferred)) return preferred
        return runCatching { packageManager.queryIntentActivities(intent, 0) }
            .getOrNull()
            ?.mapNotNull { it.activityInfo?.packageName }
            ?.firstOrNull { isConcretePackage(it) }
    }

    private fun isConcretePackage(packageName: String?): Boolean =
        !packageName.isNullOrBlank() && packageName != "android"

    // ---- layout engine ----

    private class SeedBlock(
        val iconSeats: List<CanvasPoint>,
        val card: CanvasRect?,
    )

    private class SeedGrid(
        private val iconW: Float,
        private val iconH: Float,
        private val gapX: Float,
        private val gapY: Float,
        private val blockSeamX: Float,
        private val blockSeamY: Float,
    ) {
        /**
         * Portrait — card stacked over the grid, both exactly the grid's width so their
         * left and right edges line up:
         *   [ Service Card (3 cols × 2 rows) ]
         *              BLOCK_SEAM
         *   [Icon]  [Icon]  [Icon]
         *   [Icon]  [Icon]  [AllApps]
         *
         * Falls back to icons only rather than failing outright on a page too short to
         * hold the card as well.
         */
        fun portraitStacked(iconCount: Int): SeedBlock? {
            val cols = ICON_COLUMNS
            val blockW = span(cols, iconW, gapX)
            val startX = centered(blockW) ?: return null
            val cardH = span(ICON_ROWS, iconH, gapY)
            val gridH = span(ceilDiv(iconCount, cols), iconH, gapY)

            portraitTop(cardH + blockSeamY + gridH)?.let { startY ->
                return SeedBlock(
                    iconSeats = iconSeats(
                        iconCount, cols, startX, startY + cardH + blockSeamY,
                    ),
                    card = CanvasRect(startX, startY, blockW, cardH),
                )
            }
            val startY = portraitTop(gridH) ?: return null
            return SeedBlock(
                iconSeats = iconSeats(iconCount, cols, startX, startY),
                card = null,
            )
        }

        /**
         * Landscape — card left, grid right, as two identical rectangles: same three
         * columns of width, same two rows of height, tops flush.
         *   [ Service Card ]   [Ico] [Ico] [Ico]
         *   [    (3×2)     ]   [Ico] [Ico] [App]
         */
        fun landscapeSideBySide(iconCount: Int): SeedBlock? {
            val cols = ICON_COLUMNS
            val cardW = span(cols, iconW, gapX)
            val cardH = span(ICON_ROWS, iconH, gapY)
            val iconCols = minOf(ICON_COLUMNS, iconCount).coerceAtLeast(1)
            val gridW = span(iconCols, iconW, gapX)
            val gridH = span(ceilDiv(iconCount, iconCols), iconH, gapY)

            centered(cardW + blockSeamX + gridW)?.let { startX ->
                // One shared top edge — the card and the grid are the same height.
                val startY = centered(maxOf(cardH, gridH)) ?: return@let
                return SeedBlock(
                    iconSeats = iconSeats(
                        iconCount, iconCols, startX + cardW + blockSeamX, startY,
                    ),
                    card = CanvasRect(startX, startY, cardW, cardH),
                )
            }
            val gridX = centered(gridW) ?: return null
            val gridY = centered(gridH) ?: return null
            return SeedBlock(
                iconSeats = iconSeats(iconCount, iconCols, gridX, gridY),
                card = null,
            )
        }

        private fun iconSeats(
            count: Int,
            columns: Int,
            startX: Float,
            startY: Float,
        ): List<CanvasPoint> {
            val maxX = (1f - iconW).coerceAtLeast(0f)
            val maxY = (1f - iconH).coerceAtLeast(0f)
            return List(count) { index ->
                CanvasPoint(
                    x = (startX + (index % columns) * (iconW + gapX)).coerceIn(0f, maxX),
                    y = (startY + (index / columns) * (iconH + gapY)).coerceIn(0f, maxY),
                )
            }
        }

        private fun span(seats: Int, seat: Float, gap: Float): Float =
            seats * seat + (seats - 1).coerceAtLeast(0) * gap

        private fun centered(extent: Float): Float? =
            if (extent > 1f) null else (1f - extent) / 2f

        private fun portraitTop(extent: Float): Float? {
            if (extent > 1f - PORTRAIT_MIN_TOP) return null
            return ((1f - extent) / 2f - PORTRAIT_LIFT).coerceAtLeast(PORTRAIT_MIN_TOP)
        }

        private fun ceilDiv(value: Int, divisor: Int): Int {
            val d = divisor.coerceAtLeast(1)
            return ((value + d - 1) / d).coerceAtLeast(1)
        }

        companion object {
            fun of(
                size: PageSize,
                density: Float,
                showDesktopLabels: Boolean,
            ): SeedGrid {
                val (iconW, iconH) = iconBoxNorm(
                    pageWidthPx = size.widthPx,
                    pageHeightPx = size.heightPx,
                    showLabel = showDesktopLabels,
                    density = density,
                )
                val (gapX, gapY) = iconGapAxes(size.widthPx, size.heightPx, density)
                val d = density.coerceAtLeast(0.01f)
                val pageW = size.widthPx.coerceAtLeast(1f)
                val pageH = size.heightPx.coerceAtLeast(1f)
                return SeedGrid(
                    iconW = iconW,
                    iconH = iconH,
                    gapX = gapX,
                    gapY = gapY,
                    blockSeamX = BLOCK_SEAM_DP * d / pageW,
                    blockSeamY = BLOCK_SEAM_DP * d / pageH,
                )
            }
        }
    }
}
