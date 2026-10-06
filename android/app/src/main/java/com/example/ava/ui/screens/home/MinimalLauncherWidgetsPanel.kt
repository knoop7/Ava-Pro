package com.example.ava.ui.screens.home

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.example.ava.ui.AvaToast
import com.example.ava.ui.rememberCompactSquareScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.widgets.AvaDarkModeWidgetProvider
import com.example.ava.widgets.AvaExitWidgetProvider
import com.example.ava.widgets.AvaMusicWidgetProvider
import com.example.ava.widgets.AvaNetworkWidgetProvider
import com.example.ava.widgets.AvaRestartWidgetProvider
import com.example.ava.widgets.AvaSensorWidgetProvider
import com.example.ava.ui.glass.rememberLiquidGlassState
import com.example.ava.widgets.AvaServiceWidgetProvider
import com.example.ava.widgets.AvaTitleDescriptionWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PendingWidgetOp(
    val provider: MinimalLauncherWidgetProvider,
    val appWidgetId: Int,
)

/**
 * Launcher3 WidgetsContainerView picker:
 * - vertical package sections (app icon + title)
 * - same-app widgets sit in one horizontal row (scrollable), not stacked full-width cards
 * - tap → toast long-press hint; long-press → bind then drag-out (not instant place)
 */
@Composable
fun MinimalLauncherWidgetsPanel(
    isDarkMode: Boolean,
    contentPadding: PaddingValues,
    onDismiss: () -> Unit,
    onWidgetDragReady: (
        appWidgetId: Int,
        provider: android.content.ComponentName,
        widthNorm: Float,
        heightNorm: Float,
        preview: Bitmap?,
        rawX: Float,
        rawY: Float,
    ) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember { context.applicationContext }
    val density = LocalDensity.current
    var groups by remember { mutableStateOf<List<MinimalLauncherWidgetPackageGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<PendingWidgetOp?>(null) }
    var placeTick by remember { mutableIntStateOf(0) }
    var lastLongPressRaw by remember { mutableStateOf(0f to 0f) }

    val panelColor = if (isDarkMode) Color(0xFF1F1F1F) else Color.White
    val ink = if (isDarkMode) Color.White else Color(0xFF1E293B)
    val muted = if (isDarkMode) Color(0xFF9CA3AF) else Color(0xFF64748B)

    fun beginDragOut(op: PendingWidgetOp) {
        val previewPx = with(density) { 120.dp.roundToPx() }
        val group = groups.firstOrNull {
            it.packageName == op.provider.provider.packageName
        }
        val preview = loadWidgetPreviewBitmap(
            context,
            op.provider,
            group?.appLabel ?: "",
            group?.appIcon,
            previewPx,
            isDarkMode,
        )
        val (rawX, rawY) = lastLongPressRaw
        val screenMetrics = context.resources.displayMetrics
        val (widthNorm, heightNorm) = widgetSizeNormForProvider(
            context,
            op.provider.info,
            screenMetrics.widthPixels.toFloat(),
            screenMetrics.heightPixels.toFloat(),
        )
        onWidgetDragReady(
            op.appWidgetId,
            op.provider.provider,
            widthNorm,
            heightNorm,
            preview,
            rawX,
            rawY,
        )
        pending = null
        // Content closes the panel in onWidgetDragReady; keep dismiss for other hosts.
        onDismiss()
    }

    val configureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val op = pending ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            beginDragOut(op)
        } else {
            MinimalLauncherWidgetHost.deleteAppWidgetId(appContext, op.appWidgetId)
            pending = null
        }
    }

    val bindLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val op = pending ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            placeTick++
        } else {
            MinimalLauncherWidgetHost.deleteAppWidgetId(appContext, op.appWidgetId)
            pending = null
        }
    }

    fun continueAfterBound(op: PendingWidgetOp) {
        val configure = op.provider.info.configure
        if (configure != null) {
            pending = op
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setComponent(configure)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, op.appWidgetId)
            try {
                configureLauncher.launch(intent)
            } catch (_: Exception) {
                beginDragOut(op)
            }
        } else {
            beginDragOut(op)
        }
    }

    LaunchedEffect(placeTick) {
        if (placeTick == 0) return@LaunchedEffect
        val op = pending ?: return@LaunchedEffect
        continueAfterBound(op)
    }

    fun requestBind(provider: MinimalLauncherWidgetProvider) {
        val host = MinimalLauncherWidgetHost.get(appContext)
        val manager = AppWidgetManager.getInstance(appContext)
        val appWidgetId = host.allocateAppWidgetId()
        val op = PendingWidgetOp(provider, appWidgetId)
        val allowed = try {
            manager.bindAppWidgetIdIfAllowed(appWidgetId, provider.provider)
        } catch (_: Exception) {
            false
        }
        if (allowed) {
            continueAfterBound(op)
        } else {
            pending = op
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            try {
                bindLauncher.launch(intent)
            } catch (_: Exception) {
                MinimalLauncherWidgetHost.deleteAppWidgetId(appContext, appWidgetId)
                pending = null
                AvaToast.show(context, R.string.widget_bind_failed)
            }
        }
    }

    LaunchedEffect(Unit) {
        loading = true
        groups = withContext(Dispatchers.IO) {
            loadMinimalLauncherWidgetGroups(appContext)
        }
        loading = false
    }

    val fillPane = rememberCompactSquareScreen()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(if (fillPane) PaddingValues(0.dp) else contentPadding)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = if (fillPane) 0.dp else 10.dp)
                .clip(RoundedCornerShape(if (fillPane) 0.dp else 20.dp))
                .background(panelColor)
                // Eat taps on the sheet so they don't dismiss through the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                when {
                    loading -> {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            HomeCenterLoadingRing(isDarkMode = isDarkMode)
                        }
                    }
                    groups.isEmpty() -> {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.widgets_list_empty),
                                color = muted,
                                fontSize = 14.sp
                            )
                        }
                    }
                    else -> {
                        val listState = rememberLazyListState()
                        val showTopFade by remember {
                            derivedStateOf { listState.drawerShowTopFade() }
                        }
                        val showBottomFade by remember {
                            derivedStateOf { listState.drawerShowBottomFade() }
                        }
                        val widgetGlass by rememberLiquidGlassState()
                        // Launcher3: Lazy vertical package rows; each row has a
                        // horizontal widget strip. Edge dissolve matches the app drawer.
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .drawerVerticalEdgeDissolve(
                                    showTop = showTopFade,
                                    showBottom = showBottomFade,
                                    fadeToColor = panelColor,
                                    glassMode = widgetGlass.enabled,
                                ),
                            contentPadding = PaddingValues(
                                bottom = MinimalLauncherDrawerHandleHeight,
                            ),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(groups, key = { it.packageName }) { group ->
                                WidgetPackageSection(
                                    group = group,
                                    ink = ink,
                                    muted = muted,
                                    isDarkMode = isDarkMode,
                                    onWidgetClick = {
                                        AvaToast.show(context, R.string.long_press_widget_to_add)
                                    },
                                    onWidgetLongClick = { provider, rawX, rawY ->
                                        lastLongPressRaw = rawX to rawY
                                        requestBind(provider)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            MinimalLauncherDrawerCloseBar(
                isDarkMode = isDarkMode,
                onClose = onDismiss,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun WidgetPackageSection(
    group: MinimalLauncherWidgetPackageGroup,
    ink: Color,
    muted: Color,
    isDarkMode: Boolean,
    onWidgetClick: () -> Unit,
    onWidgetLongClick: (MinimalLauncherWidgetProvider, rawX: Float, rawY: Float) -> Unit,
) {
    val density = LocalDensity.current
    val iconPx = with(density) { 28.dp.roundToPx() }
    val appIconBitmap = remember(group.packageName, iconPx) {
        drawableToBitmap(group.appIcon, iconPx)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        // Section header — Launcher3 BubbleTextView / package row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (appIconBitmap != null) {
                Image(
                    bitmap = appIconBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(muted.copy(alpha = 0.25f))
                )
            }
            Text(
                text = group.appLabel,
                color = ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Same-app widgets share one horizontal strip (Launcher3 widgets_cell_list).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            group.widgets.forEach { item ->
                WidgetPickerCell(
                    item = item,
                    appLabel = group.appLabel,
                    appIcon = group.appIcon,
                    ink = ink,
                    muted = muted,
                    isDarkMode = isDarkMode,
                    onClick = onWidgetClick,
                    onLongClick = { rawX, rawY -> onWidgetLongClick(item, rawX, rawY) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetPickerCell(
    item: MinimalLauncherWidgetProvider,
    appLabel: String,
    appIcon: Drawable?,
    ink: Color,
    muted: Color,
    isDarkMode: Boolean,
    onClick: () -> Unit,
    onLongClick: (rawX: Float, rawY: Float) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var layoutCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Launcher3 WidgetCell: ~fixed square preview (not full-width stacked cards).
    val cellDp = 112.dp
    val previewSizePx = with(density) { 88.dp.roundToPx() }
    val previewBitmap = remember(item.provider, previewSizePx, isDarkMode) {
        loadWidgetPreviewBitmap(context, item, appLabel, appIcon, previewSizePx, isDarkMode)
    }
    val cellBg = if (isDarkMode) Color(0xFF000000) else Color(0xFFF1F5F9)
    Column(
        modifier = Modifier
            .width(cellDp)
            .onGloballyPositioned { layoutCoords = it }
            .clip(RoundedCornerShape(14.dp))
            .background(cellBg)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = onClick,
                onLongClick = {
                    val origin = layoutCoords?.takeIf { it.isAttached }?.positionOnScreen()
                        ?: Offset.Zero
                    onLongClick(
                        origin.x + with(density) { cellDp.toPx() } * 0.5f,
                        origin.y + with(density) { 56.dp.toPx() },
                    )
                }
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.label,
                color = ink,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = providerSizeDpLabel(item.info),
                color = muted,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isDarkMode) Color(0xFF1F1F1F) else Color.White),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = item.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            )
        }
    }
}

/**
 * Preview order: Ava's exclusive widgets all share the text-card cover so the row is one
 * family (the action widgets' previewLayout would otherwise drop in a coloured screenshot).
 * Then the provider's real preview, then a generated cover. The framework stand-in — the
 * app icon blown up to fill the cell — is never used.
 */
private fun loadWidgetPreviewBitmap(
    context: Context,
    item: MinimalLauncherWidgetProvider,
    appLabel: String,
    appIcon: Drawable?,
    sizePx: Int,
    isDarkMode: Boolean,
): Bitmap {
    avaExclusiveCover(context, item, sizePx, isDarkMode)?.let { return it }
    val app = context.applicationContext
    val preview = try {
        item.info.loadPreviewImage(app, app.resources.displayMetrics.densityDpi)
    } catch (_: Exception) {
        null
    }
    val bitmap = try {
        when (preview) {
            null -> null
            is BitmapDrawable -> preview.bitmap
            else -> drawableToBitmap(preview, sizePx)
        }
    } catch (_: Exception) {
        null
    }
    if (bitmap != null) return bitmap
    // Ava's mark is a bare capsule + dot; the app icon would drop a black plate in here.
    val brand = if (item.provider.packageName == app.packageName) {
        MinimalLauncherWidgetCover.avaMark(context)
    } else {
        appIcon
    }
    return MinimalLauncherWidgetCover.default(
        label = item.label,
        appLabel = appLabel,
        brand = brand,
        sizePx = sizePx,
        isDarkMode = isDarkMode,
    )
}

/** Same sheet as the Ava Pro text card: kicker, mark, title, description, rule. */
private fun avaExclusiveCover(
    context: Context,
    item: MinimalLauncherWidgetProvider,
    sizePx: Int,
    isDarkMode: Boolean,
): Bitmap? {
    val className = item.provider.className
    val (kicker, title, description) = when (className) {
        AvaTitleDescriptionWidgetProvider::class.java.name -> Triple(
            "TEXT",
            context.getString(R.string.ava_widget_default_title),
            context.getString(R.string.ava_widget_default_description),
        )
        AvaServiceWidgetProvider::class.java.name -> Triple(
            "SERVICE",
            context.getString(R.string.label_start_service),
            context.getString(R.string.ava_widget_service_desc),
        )
        AvaRestartWidgetProvider::class.java.name -> Triple(
            "RESTART",
            context.getString(R.string.settings_device_control_restart),
            context.getString(R.string.ava_widget_restart_desc),
        )
        AvaExitWidgetProvider::class.java.name -> Triple(
            "EXIT",
            context.getString(R.string.settings_device_control_kill),
            context.getString(R.string.ava_widget_exit_desc),
        )
        AvaDarkModeWidgetProvider::class.java.name -> Triple(
            "DISPLAY",
            context.getString(R.string.ava_tile_dark_mode),
            context.getString(R.string.ava_widget_dark_mode_desc),
        )
        AvaMusicWidgetProvider::class.java.name -> Triple(
            "MUSIC",
            context.getString(R.string.ava_widget_music_name),
            context.getString(R.string.ava_widget_music_desc),
        )
        AvaNetworkWidgetProvider::class.java.name -> Triple(
            "NETWORK",
            context.getString(R.string.ava_widget_network_name),
            context.getString(R.string.ava_widget_network_desc),
        )
        AvaSensorWidgetProvider::class.java.name -> Triple(
            "SENSOR",
            context.getString(R.string.ava_widget_sensor_name),
            context.getString(R.string.ava_widget_sensor_desc),
        )
        else -> return null
    }
    return MinimalLauncherWidgetCover.avaCard(
        context = context,
        kicker = kicker,
        title = title,
        description = description,
        sizePx = sizePx,
        isDarkMode = isDarkMode,
    )
}

private fun drawableToBitmap(drawable: Drawable?, sizePx: Int): Bitmap? {
    if (drawable == null) return null
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: sizePx
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: sizePx
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
