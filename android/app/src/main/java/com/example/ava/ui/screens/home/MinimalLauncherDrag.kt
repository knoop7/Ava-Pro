package com.example.ava.ui.screens.home

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.example.ava.ui.AvaToast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import com.example.ava.ui.rememberPaneIsLandscape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.ava.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ghost fade-in / fade-out (transparent 渐隐渐出). */
private const val GHOST_FADE_IN_MS = 140
private const val GHOST_FADE_OUT_MS = 180
/** Peak opacity while dragging — ghost stays translucent. */
private const val GHOST_DRAG_ALPHA = 0.78f
/** Launcher3 DragView scale-up duration. */
private const val GHOST_SCALE_MS = 120
/** Launcher3 R.dimen.dragViewScale — grow ghost by this many dp. */
private val DragViewScaleExtra = 8.dp
/** Launcher3 SearchDropTargetBar DEFAULT_DRAG_FADE_DURATION. */
private const val DROP_BAR_FADE_MS = 175
/** CellLayout drag-outline fade (InterruptibleInOutAnimator). */
private const val OUTLINE_FADE_MS = 150

/** Light theme ghost silhouette — gray. */
private val LightGhostTint = Color(0xFF8A8F98)
/** Dark theme ghost silhouette — white. */
private val DarkGhostTint = Color(0xFFFFFFFF)

/** Light theme idle — gray icon + label. */
private val LightIdleTint = Color(0xFF5F6368)
private val LightHoverDelete = Color(0xFF3C4043)
private val LightHoverInfo = Color(0xFF00796B)
private val LightBarBg = Color(0xB3FFFFFF)

/** Dark theme idle — soft white. */
private val DarkIdleTint = Color(0xE6FFFFFF)
private val DarkHoverDelete = Color(0xFFC1C1C1)
private val DarkHoverInfo = Color(0xFF80CBC4)
private val DarkBarBg = Color(0x99000000)

private val LightLabelShadow = Shadow(
    color = Color(0x14000000),
    offset = Offset(0f, 1f),
    blurRadius = 2f,
)
private val DarkLabelShadow = Shadow(
    color = Color(0x99000000),
    offset = Offset(0f, 1f),
    blurRadius = 4f,
)

enum class MinimalLauncherDragPhase {
    Dragging,
    Settling,
}

/** Launcher3 SearchDropTargetBar hit. */
enum class MinimalLauncherDropTarget {
    None,
    Remove,
    Uninstall,
    Info,
}

sealed class MinimalLauncherDragPayload {
    data class DesktopIcon(val icon: PlacedMinimalLauncherIcon) : MinimalLauncherDragPayload()
    data class DesktopWidget(val widget: PlacedMinimalLauncherWidget) : MinimalLauncherDragPayload()
    data class FromAllApps(val app: MinimalLauncherApp) : MinimalLauncherDragPayload()
    /** Widgets tray → workspace (free canvas; size from provider / ghost). */
    data class FromWidgetsTray(
        val appWidgetId: Int,
        val provider: android.content.ComponentName,
        val widthNorm: Float,
        val heightNorm: Float,
    ) : MinimalLauncherDragPayload()
}

/** Launcher3 DragLayer.animateViewIntoPosition target (screen coords). */
data class MinimalLauncherSettleTarget(
    val screenX: Float,
    val screenY: Float,
    val widthPx: Int,
    val heightPx: Int,
)

data class MinimalLauncherDragSession(
    val payload: MinimalLauncherDragPayload,
    val fingerX: Float,
    val fingerY: Float,
    val grabOffsetX: Float,
    val grabOffsetY: Float,
    val widthPx: Int,
    val heightPx: Int,
    val phase: MinimalLauncherDragPhase,
    val previewBitmap: Bitmap? = null,
    val settleTarget: MinimalLauncherSettleTarget? = null,
)

data class WorkspacePageGeometry(
    val originX: Float,
    val originY: Float,
    val widthPx: Float,
    val heightPx: Float,
    val page: Int,
)

fun openAppDetails(context: android.content.Context, packageName: String) {
    try {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}

fun packageNameOf(payload: MinimalLauncherDragPayload): String? = when (payload) {
    is MinimalLauncherDragPayload.DesktopIcon -> payload.icon.packageName
    is MinimalLauncherDragPayload.DesktopWidget -> payload.widget.provider.packageName
    is MinimalLauncherDragPayload.FromAllApps -> payload.app.packageName
    is MinimalLauncherDragPayload.FromWidgetsTray -> payload.provider.packageName
}

/** Icons / AllApps: Remove + App info. Widgets and tiles: Remove only (no Info chip). */
fun dropTargetShowsInfo(payload: MinimalLauncherDragPayload): Boolean = when (payload) {
    // A tile has no package for the settings screen to open.
    is MinimalLauncherDragPayload.DesktopIcon ->
        !MinimalLauncherTiles.isTile(payload.icon.packageName)
    is MinimalLauncherDragPayload.FromAllApps -> true
    is MinimalLauncherDragPayload.DesktopWidget,
    is MinimalLauncherDragPayload.FromWidgetsTray -> false
}

/**
 * Launcher3 UninstallDropTarget.supportsDrop: icon payloads whose package is a
 * removable (non-system, non-self) app.
 */
fun dropTargetShowsUninstall(
    context: android.content.Context,
    payload: MinimalLauncherDragPayload,
): Boolean {
    val packageName = when (payload) {
        is MinimalLauncherDragPayload.DesktopIcon -> payload.icon.packageName
        is MinimalLauncherDragPayload.FromAllApps -> payload.app.packageName
        else -> return false
    }
    if (packageName == context.packageName) return false
    return try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
    } catch (_: Exception) {
        false
    }
}

/** Launch the system uninstall confirmation dialog. */
fun requestUninstall(context: android.content.Context, packageName: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_DELETE)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}

/**
 * Launcher3 config_dropAnim: duration = clamp(500 * decelerate(dist/800), 100, 500).
 */
fun settleDurationMs(distPx: Float): Int {
    val maxDist = 800f
    val maxMs = 500
    val minMs = 100
    if (distPx >= maxDist) return maxMs
    val t = (distPx / maxDist).coerceIn(0f, 1f)
    // DecelerateInterpolator(1.5) approximation: 1 - (1-t)^1.5
    val eased = 1f - (1f - t).let { it * kotlin.math.sqrt(it) }
    return (maxMs * eased).toInt().coerceIn(minMs, maxMs)
}

/**
 * Hit-test the ACTUAL measured drop pill (screen coords from
 * [MinimalLauncherDragLayerRegistry.dropBarRect]) — never a full-width strip,
 * which used to swallow the entire top workspace row.
 * Outside → None (normal workspace drop).
 */
fun resolveDropTarget(
    session: MinimalLauncherDragSession,
    screenWidthPx: Float,
    density: Float,
    isLandscape: Boolean = false,
    showUninstall: Boolean = false,
): MinimalLauncherDropTarget {
    if (session.phase != MinimalLauncherDragPhase.Dragging) return MinimalLauncherDropTarget.None
    val showInfo = dropTargetShowsInfo(session.payload)
    val targets = buildList {
        add(MinimalLauncherDropTarget.Remove)
        if (showUninstall) add(MinimalLauncherDropTarget.Uninstall)
        if (showInfo) add(MinimalLauncherDropTarget.Info)
    }
    val slop = 6f * density
    val bar = MinimalLauncherDragLayerRegistry.dropBarRect
    val left: Float
    val right: Float
    if (bar != null && bar.width() > 0f && bar.height() > 0f) {
        if (session.fingerY < bar.top - slop || session.fingerY > bar.bottom + slop) {
            return MinimalLauncherDropTarget.None
        }
        if (session.fingerX < bar.left - slop || session.fingerX > bar.right + slop) {
            return MinimalLauncherDropTarget.None
        }
        left = bar.left
        right = bar.right
    } else {
        // Pre-layout fallback: a compact centered pill estimate, NOT full width.
        val topPad = (if (isLandscape) 12f else 20f) * density
        val stripH = (if (isLandscape) 52f else 64f) * density
        if (session.fingerY < topPad || session.fingerY > topPad + stripH) {
            return MinimalLauncherDropTarget.None
        }
        val pillHalf = (targets.size * 110f) * 0.5f * density
        left = screenWidthPx * 0.5f - pillHalf
        right = screenWidthPx * 0.5f + pillHalf
        if (session.fingerX < left || session.fingerX > right) {
            return MinimalLauncherDropTarget.None
        }
    }
    if (targets.size == 1) return targets[0]
    val fraction = ((session.fingerX - left) / (right - left)).coerceIn(0f, 0.9999f)
    return targets[(fraction * targets.size).toInt().coerceIn(0, targets.size - 1)]
}

/**
 * DragView ghost + CellLayout holographic drop outline (white circular stroke for round icons)
 * + top Remove / App info bar.
 */
@Composable
fun MinimalLauncherDragOverlay(
    session: MinimalLauncherDragSession,
    isDarkMode: Boolean,
    screenWidthPx: Float,
    showDesktopLabels: Boolean = true,
    pageGeometry: WorkspacePageGeometry? = null,
    alignIcons: List<PlacedMinimalLauncherIcon> = emptyList(),
    alignWidgets: List<PlacedMinimalLauncherWidget> = emptyList(),
    onSettled: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isLandscape = rememberPaneIsLandscape()
    val ghostAlpha = remember { Animatable(0f) }
    val ghostScale = remember { Animatable(1f) }
    val barAlpha = remember { Animatable(0f) }
    val outlineAlpha = remember { Animatable(0f) }
    val guideAlpha = remember { Animatable(0f) }
    // L3 DragView: layout at (0,0) + translation; never paint until origin is known.
    var overlayOriginOnScreen by remember { mutableStateOf<Offset?>(null) }
    var settleDriving by remember { mutableStateOf(false) }
    val densityPx = density.density
    val showUninstall = remember(session.payload) {
        dropTargetShowsUninstall(context.applicationContext, session.payload)
    }
    val hover = resolveDropTarget(session, screenWidthPx, densityPx, isLandscape, showUninstall)
    val showInfo = dropTargetShowsInfo(session.payload)
    val isIconGhost = when (session.payload) {
        is MinimalLauncherDragPayload.DesktopIcon,
        is MinimalLauncherDragPayload.FromAllApps -> true
        else -> false
    }
    // Light → gray ghost; dark → white ghost (silhouette via SrcIn).
    val ghostTint = if (isDarkMode) DarkGhostTint else LightGhostTint
    val ghostColorFilter = remember(ghostTint) {
        ColorFilter.tint(ghostTint, BlendMode.SrcIn)
    }

    val idleTint = if (isDarkMode) DarkIdleTint else LightIdleTint
    val deleteHover = if (isDarkMode) DarkHoverDelete else LightHoverDelete
    val infoHover = if (isDarkMode) DarkHoverInfo else LightHoverInfo
    val barBg = if (isDarkMode) DarkBarBg else LightBarBg
    val labelShadow = if (isDarkMode) DarkLabelShadow else LightLabelShadow

    DisposableEffect(session.payload) {
        MinimalLauncherDragLayerRegistry.ghostReady = false
        overlayOriginOnScreen = null
        settleDriving = false
        onDispose {
            MinimalLauncherDragLayerRegistry.ghostReady = false
            MinimalLauncherDragLayerRegistry.dropBarRect = null
        }
    }

    val ghostBitmap = remember(session.payload, session.previewBitmap, session.widthPx, session.heightPx) {
        session.previewBitmap?.takeIf { !it.isRecycled }
            ?: when (val p = session.payload) {
                is MinimalLauncherDragPayload.FromAllApps -> {
                    val size = session.widthPx.coerceIn(48, 192)
                    p.app.icon.toBitmap(size, size)
                }
                is MinimalLauncherDragPayload.DesktopIcon -> {
                    val app = MinimalLauncherTiles.resolve(
                        context.applicationContext, p.icon.packageName, p.icon.activityName
                    ) ?: resolveMinimalLauncherApp(
                        context.applicationContext, p.icon.packageName, p.icon.activityName
                    )
                    val size = session.widthPx.coerceIn(48, 192)
                    app?.icon?.toBitmap(size, size)
                }
                is MinimalLauncherDragPayload.DesktopWidget -> {
                    createWidgetDragFallbackBitmap(
                        context.applicationContext,
                        p.widget.provider.packageName,
                        session.widthPx,
                        session.heightPx,
                    )
                }
                is MinimalLauncherDragPayload.FromWidgetsTray -> {
                    createWidgetDragFallbackBitmap(
                        context.applicationContext,
                        p.provider.packageName,
                        session.widthPx,
                        session.heightPx,
                    )
                }
            }
    }

    val drawW = ghostBitmap?.width?.coerceAtLeast(1) ?: session.widthPx
    val drawH = ghostBitmap?.height?.coerceAtLeast(1) ?: session.heightPx
    // Same registration math as freeDropTopLeft — never let ghost and drop diverge.
    val (grabX, grabY) = effectiveGrabPx(
        session.grabOffsetX,
        session.grabOffsetY,
        session.widthPx,
        session.heightPx,
        drawW,
        drawH,
    )
    val scale = ghostScale.value
    val origin = overlayOriginOnScreen
    // Direct 1:1 follow: scale around the grab point so the finger never drifts.
    val followX = if (origin != null) {
        session.fingerX - origin.x - grabX
    } else {
        0f
    }
    val followY = if (origin != null) {
        session.fingerY - origin.y - grabY
    } else {
        0f
    }
    val settleAnimX = remember { Animatable(0f) }
    val settleAnimY = remember { Animatable(0f) }
    // Never read settle animatables before snapTo — their 0f was the top-left flash.
    val ghostX = if (settleDriving) settleAnimX.value else followX
    val ghostY = if (settleDriving) settleAnimY.value else followY
    val ghostReady = origin != null
    val scaleTarget = if (isIconGhost && drawW > 0) {
        (drawW + with(density) { DragViewScaleExtra.toPx() }) / drawW.toFloat()
    } else {
        1f
    }

    // L3 CellLayout.visualizeDropLocation — white outline at hover cell (not a fill shadow).
    val outlineBitmap = remember(ghostBitmap, densityPx, isIconGhost) {
        if (!isIconGhost) null
        else ghostBitmap?.takeIf { !it.isRecycled }?.let {
            createHolographicIconOutline(it, densityPx)
        }
    }
    // Free-canvas drop seat + ruler snap (same math as tryPlaceDrag).
    val dropSnap = remember(
        session.fingerX, session.fingerY, session.grabOffsetX, session.grabOffsetY,
        session.widthPx, session.heightPx, session.payload, pageGeometry, hover,
        alignIcons, alignWidgets, densityPx, isLandscape, showDesktopLabels,
    ) {
        if (hover != MinimalLauncherDropTarget.None) null
        else pageGeometry?.let { geo ->
            resolveAlignedDropSeat(
                context, session, geo, alignIcons, alignWidgets, densityPx, isLandscape,
                showDesktopLabels,
            )
        }
    }
    val dropSeat = dropSnap?.let { CanvasPoint(it.x, it.y) }

    LaunchedEffect(session.phase) {
        when (session.phase) {
            MinimalLauncherDragPhase.Dragging -> {
                settleDriving = false
                // Start invisible — fade in once overlay origin is known (no pop-in).
                ghostAlpha.snapTo(0f)
                ghostScale.snapTo(1f)
                barAlpha.snapTo(0f)
                outlineAlpha.snapTo(0f)
                guideAlpha.snapTo(0f)
                launch {
                    ghostScale.animateTo(
                        scaleTarget,
                        tween(GHOST_SCALE_MS, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    barAlpha.animateTo(
                        1f,
                        tween(DROP_BAR_FADE_MS, easing = FastOutSlowInEasing),
                    )
                }
            }
            MinimalLauncherDragPhase.Settling -> {
                launch {
                    barAlpha.animateTo(0f, tween(DROP_BAR_FADE_MS, easing = FastOutSlowInEasing))
                }
                launch {
                    outlineAlpha.animateTo(0f, tween(OUTLINE_FADE_MS, easing = FastOutSlowInEasing))
                }
                launch {
                    guideAlpha.animateTo(0f, tween(OUTLINE_FADE_MS, easing = FastOutSlowInEasing))
                }
                val target = session.settleTarget
                val laidOutOrigin = overlayOriginOnScreen
                if (target != null && laidOutOrigin != null) {
                    // Stay on followX/Y until snap — Animatable defaults are (0,0) top-left flash.
                    val startX = session.fingerX - laidOutOrigin.x - grabX
                    val startY = session.fingerY - laidOutOrigin.y - grabY
                    val destX = target.screenX - laidOutOrigin.x
                    val destY = target.screenY - laidOutOrigin.y
                    settleAnimX.snapTo(startX)
                    settleAnimY.snapTo(startY)
                    settleDriving = true
                    val dist = kotlin.math.hypot(destX - startX, destY - startY)
                    val ms = settleDurationMs(dist)
                    launch {
                        settleAnimX.animateTo(destX, tween(ms, easing = FastOutSlowInEasing))
                    }
                    launch {
                        settleAnimY.animateTo(destY, tween(ms, easing = FastOutSlowInEasing))
                    }
                    launch {
                        ghostScale.animateTo(
                            1f,
                            tween(ms.coerceAtMost(180), easing = FastOutSlowInEasing),
                        )
                    }
                    val fadeDelay = (ms - GHOST_FADE_OUT_MS).coerceAtLeast(0).toLong()
                    delay(fadeDelay)
                    ghostAlpha.animateTo(
                        0f,
                        tween(GHOST_FADE_OUT_MS, easing = LinearOutSlowInEasing),
                    )
                } else {
                    settleDriving = false
                    ghostAlpha.animateTo(
                        0f,
                        tween(GHOST_FADE_OUT_MS, easing = LinearOutSlowInEasing),
                    )
                }
                onSettled()
            }
        }
    }

    // Transparent fade-in once the ghost can be placed under the finger.
    LaunchedEffect(ghostReady, session.phase) {
        if (session.phase == MinimalLauncherDragPhase.Dragging && ghostReady) {
            ghostAlpha.animateTo(
                GHOST_DRAG_ALPHA,
                tween(GHOST_FADE_IN_MS, easing = FastOutSlowInEasing),
            )
        }
    }

    val showOutline = session.phase == MinimalLauncherDragPhase.Dragging &&
        outlineBitmap != null &&
        dropSeat != null
    val showGuides = session.phase == MinimalLauncherDragPhase.Dragging &&
        dropSnap != null &&
        (dropSnap.guideV != null || dropSnap.guideH != null)
    LaunchedEffect(showOutline) {
        if (showOutline) {
            outlineAlpha.animateTo(1f, tween(OUTLINE_FADE_MS, easing = FastOutSlowInEasing))
        } else {
            outlineAlpha.animateTo(0f, tween(OUTLINE_FADE_MS, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(showGuides) {
        if (showGuides) {
            guideAlpha.animateTo(1f, tween(OUTLINE_FADE_MS, easing = FastOutSlowInEasing))
        } else {
            guideAlpha.animateTo(0f, tween(OUTLINE_FADE_MS, easing = FastOutSlowInEasing))
        }
    }

    val hPad = if (isLandscape) 48.dp else 20.dp
    val topPad = if (isLandscape) 12.dp else 20.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                // Screen origin for rawX/rawY → L3 DragLayer coords (then show is ready).
                val o = coords.positionOnScreen()
                overlayOriginOnScreen = o
                MinimalLauncherDragLayerRegistry.ghostReady = true
            }
    ) {
        if (session.phase == MinimalLauncherDragPhase.Dragging ||
            session.phase == MinimalLauncherDragPhase.Settling
        ) {
            // Compact centered pill (L3 DropTargetBar): a full-width bar used to make
            // the whole top strip a drop target, swallowing the top workspace row.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .wrapContentSize()
                    .padding(start = hPad, end = hPad, top = topPad)
                    .graphicsLayer {
                        alpha = barAlpha.value
                        clip = false
                    }
                    .background(barBg, RoundedCornerShape(18.dp))
                    .onGloballyPositioned { coords ->
                        val p = coords.positionOnScreen()
                        MinimalLauncherDragLayerRegistry.dropBarRect = android.graphics.RectF(
                            p.x,
                            p.y,
                            p.x + coords.size.width,
                            p.y + coords.size.height,
                        )
                    }
                    .padding(
                        horizontal = if (isLandscape) 24.dp else 16.dp,
                        vertical = if (isLandscape) 10.dp else 12.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 44.dp else 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.wrapContentSize(),
                ) {
                    DropTargetItem(
                        icon = Icons.Outlined.Delete,
                        label = stringResource(R.string.delete_target_label),
                        tint = if (hover == MinimalLauncherDropTarget.Remove) deleteHover else idleTint,
                        emphasized = hover == MinimalLauncherDropTarget.Remove,
                        landscape = isLandscape,
                        labelShadow = labelShadow,
                        modifier = Modifier.wrapContentSize(),
                    )
                    if (showUninstall) {
                        DropTargetItem(
                            icon = Icons.Outlined.DeleteForever,
                            label = stringResource(R.string.uninstall_target_label),
                            tint = if (hover == MinimalLauncherDropTarget.Uninstall) {
                                deleteHover
                            } else {
                                idleTint
                            },
                            emphasized = hover == MinimalLauncherDropTarget.Uninstall,
                            landscape = isLandscape,
                            labelShadow = labelShadow,
                            modifier = Modifier.wrapContentSize(),
                        )
                    }
                    if (showInfo) {
                        DropTargetItem(
                            icon = Icons.Outlined.Info,
                            label = stringResource(R.string.info_target_label),
                            tint = if (hover == MinimalLauncherDropTarget.Info) infoHover else idleTint,
                            emphasized = hover == MinimalLauncherDropTarget.Info,
                            landscape = isLandscape,
                            labelShadow = labelShadow,
                            modifier = Modifier.wrapContentSize(),
                        )
                    }
                }
            }
        }

        // Ruler lines when magnetically snapped (标尺对齐).
        val snap = dropSnap
        val geo = pageGeometry
        val laidOut = origin
        if (ghostReady &&
            laidOut != null &&
            snap != null &&
            geo != null &&
            guideAlpha.value > 0.01f &&
            (snap.guideV != null || snap.guideH != null)
        ) {
            val guideColor = if (isDarkMode) {
                Color(0xFFFFC107).copy(alpha = 0.85f)
            } else {
                Color(0xFFE65100).copy(alpha = 0.80f)
            }
            val stroke = with(density) { 1.5.dp.toPx() }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = guideAlpha.value * ghostAlpha.value }
            ) {
                val ox = geo.originX - laidOut.x
                val oy = geo.originY - laidOut.y
                snap.guideV?.let { gx ->
                    val x = ox + gx * geo.widthPx
                    drawLine(
                        color = guideColor,
                        start = Offset(x, oy),
                        end = Offset(x, oy + geo.heightPx),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
                snap.guideH?.let { gy ->
                    val y = oy + gy * geo.heightPx
                    drawLine(
                        color = guideColor,
                        start = Offset(ox, y),
                        end = Offset(ox + geo.widthPx, y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        // Drop seat outline under the ghost (free canvas + ruler snap).
        val outline = outlineBitmap
        val seat = dropSeat
        if (ghostReady &&
            laidOut != null &&
            outline != null &&
            seat != null &&
            geo != null &&
            outlineAlpha.value > 0.01f
        ) {
            val (iw, _) = iconBoxNorm(context, geo.widthPx, geo.heightPx)
            val boxW = geo.widthPx * iw
            val oW = outline.width.coerceAtLeast(1)
            val oH = outline.height.coerceAtLeast(1)
            val iconTopPad = with(density) { 4.dp.toPx() }
            val screenX = geo.originX + seat.x * geo.widthPx + (boxW - oW) * 0.5f
            val screenY = geo.originY + seat.y * geo.heightPx + iconTopPad
            val ox = screenX - laidOut.x
            val oy = screenY - laidOut.y
            Image(
                bitmap = outline.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier
                    .size(with(density) { oW.toDp() }, with(density) { oH.toDp() })
                    .graphicsLayer {
                        translationX = ox
                        translationY = oy
                        alpha = outlineAlpha.value * ghostAlpha.value
                        clip = false
                    },
            )
        }

        val wDp = with(density) { drawW.toDp() }
        val hDp = with(density) { drawH.toDp() }
        // Direct finger follow: translation = touch - grab (+ scale center). Alpha 0 until ready.
        Box(
            modifier = Modifier
                .size(wDp, hDp)
                .graphicsLayer {
                    translationX = ghostX
                    translationY = ghostY
                    this.alpha = if (ghostReady) ghostAlpha.value else 0f
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(
                        (grabX / drawW.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
                        (grabY / drawH.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
                    )
                    clip = false
                },
            contentAlignment = Alignment.Center
        ) {
            if (ghostBitmap != null) {
                Image(
                    bitmap = ghostBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.Medium,
                    // Theme ghost: light=gray, dark=white; alpha from outer fade.
                    colorFilter = ghostColorFilter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}


/**
 * Portrait: icon over label. Landscape: icon + label in a row (shorter top chrome).
 * Both orientations always keep icon + label (wrap content — never flex-squeezed).
 */
/**
 * Finger drop + ruler snap — same seat used by tryPlaceDrag / settle animation.
 */
private fun resolveAlignedDropSeat(
    context: android.content.Context,
    session: MinimalLauncherDragSession,
    geo: WorkspacePageGeometry,
    icons: List<PlacedMinimalLauncherIcon>,
    widgets: List<PlacedMinimalLauncherWidget>,
    density: Float,
    isLandscape: Boolean,
    showDesktopLabels: Boolean,
): AlignSnapResult {
    val (iconW, iconH) = iconBoxNorm(
        context, geo.widthPx, geo.heightPx, showLabel = showDesktopLabels,
    )
    val (iw, ih) = when (val p = session.payload) {
        is MinimalLauncherDragPayload.FromWidgetsTray -> {
            (session.widthPx / geo.widthPx).coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f) to
                (session.heightPx / geo.heightPx).coerceIn(MINIMAL_LAUNCHER_WIDGET_MIN_FRAC, 1f)
        }
        is MinimalLauncherDragPayload.DesktopWidget -> {
            val active = p.widget.placement(isLandscape)
            active.w to active.h
        }
        else -> iconW to iconH
    }
    val exceptIcon = (session.payload as? MinimalLauncherDragPayload.DesktopIcon)?.icon?.id
    val exceptWidget = when (val p = session.payload) {
        is MinimalLauncherDragPayload.DesktopWidget -> p.widget.appWidgetId
        is MinimalLauncherDragPayload.FromWidgetsTray -> p.appWidgetId
        else -> null
    }
    val raw = freeDropTopLeft(session, geo, iw, ih)
    val thresh = alignThreshNorm(geo.widthPx, geo.heightPx, density)
    val isIconDrop = when (session.payload) {
        is MinimalLauncherDragPayload.DesktopIcon,
        is MinimalLauncherDragPayload.FromAllApps -> true
        else -> false
    }
    return if (isIconDrop) {
        placeIconWithAlign(
            raw.x, raw.y, iconW, iconH,
            geo.page, icons, widgets, exceptIcon,
            thresh, geo.widthPx, geo.heightPx, density,
        )
    } else {
        val payload = session.payload
        val pageEmpty = icons.isEmpty() && widgets.isEmpty()
        if (pageEmpty &&
            payload is MinimalLauncherDragPayload.FromWidgetsTray &&
            isAvaTitleDescriptionWidget(payload.provider)
        ) {
            val point = avaEmptyPageDropPoint(raw, iw, ih)
            AlignSnapResult(point.x, point.y, iw, ih)
        } else {
            val guides = buildAlignGuides(
                geo.page, emptyList(), widgets, iconW, iconH, exceptIcon, exceptWidget,
            )
            smartAlignSnap(raw.x, raw.y, iw, ih, guides, thresh)
        }
    }
}

@Composable
private fun DropTargetItem(
    icon: ImageVector,
    label: String,
    tint: Color,
    emphasized: Boolean,
    landscape: Boolean,
    labelShadow: Shadow,
    modifier: Modifier = Modifier,
) {
    val contentModifier = modifier.graphicsLayer {
        scaleX = if (emphasized) 1.05f else 1f
        scaleY = if (emphasized) 1.05f else 1f
        alpha = if (emphasized) 1f else 0.88f
        clip = false
    }
    if (landscape) {
        Row(
            modifier = contentModifier.wrapContentSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = label,
                color = tint,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                textAlign = TextAlign.Start,
                style = TextStyle(shadow = labelShadow),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .wrapContentWidth(unbounded = true),
            )
        }
    } else {
        Column(
            modifier = contentModifier.wrapContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                color = tint,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = labelShadow),
                modifier = Modifier
                    .padding(top = 5.dp)
                    .wrapContentWidth(),
            )
        }
    }
}

fun toastItemRemoved(context: android.content.Context) {
    AvaToast.show(context, R.string.item_removed)
}
