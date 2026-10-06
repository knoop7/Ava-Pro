package com.example.ava.ui.screens.settings

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.ui.components.ExpandedTapTarget
import com.example.ava.ui.screens.settings.components.SettingsCardInnerHorizontalPadding
import com.example.ava.ui.screens.settings.components.SettingsEdgeFadeScrollColumn
import com.example.ava.ui.screens.settings.components.rememberSettingsFlingBehavior
import com.example.ava.ui.screens.settings.components.settingsBodyTextSize
import com.example.ava.ui.screens.settings.components.settingsTitleTextSize

internal const val CATALOG_PAGE_SIZE = 8
internal const val CATALOG_REVEAL_THROTTLE_MS = 400L
private const val CatalogViewportRows = 5
private const val CatalogNearBottomPx = 48
private val CatalogViewportRowHeight = 52.dp
private val CatalogViewportDivider = 17.dp
private val CatalogViewportHeight =
    CatalogViewportRowHeight * CatalogViewportRows +
        CatalogViewportDivider * (CatalogViewportRows - 1)

internal fun shouldRevealNextCatalogPage(
    scrollingDown: Boolean,
    nearBottom: Boolean,
    hasMore: Boolean,
    elapsedSinceLastRevealMs: Long,
    throttleMs: Long = CATALOG_REVEAL_THROTTLE_MS,
): Boolean = scrollingDown && nearBottom && hasMore && elapsedSinceLastRevealMs >= throttleMs

internal data class OnlineCatalogItem(
    val id: String,
    val name: String,
    val author: String,
    val sourceUrl: String,
)

@Composable
internal fun OnlineCatalogSection(
    engineLabel: String,
    catalog: List<OnlineCatalogItem>,
    downloadingId: String?,
    downloadPercent: Int?,
    onDownload: (String) -> Unit,
    loadState: WakeWordCatalogLoadState = WakeWordCatalogLoadState.Ready,
) {
    val isDark = isDarkModeEnabled()
    var query by remember { mutableStateOf("") }
    var shownCount by remember { mutableIntStateOf(CATALOG_PAGE_SIZE) }
    var revealStartIndex by remember { mutableIntStateOf(0) }
    var infoItem by remember { mutableStateOf<OnlineCatalogItem?>(null) }
    val listScroll = rememberScrollState()
    val catalogFling = rememberSettingsFlingBehavior()

    val filtered by remember(catalog, query) {
        derivedStateOf {
            val q = query.trim().lowercase()
            if (q.isBlank()) catalog
            else catalog.filter { it.name.contains(q, ignoreCase = true) }
        }
    }

    LaunchedEffect(query) {
        shownCount = CATALOG_PAGE_SIZE
        revealStartIndex = 0
        listScroll.scrollTo(0)
    }

    val visible = filtered.take(shownCount)
    val hasMore = shownCount < filtered.size

    LaunchedEffect(query, filtered.size) {
        var lastValue = listScroll.value
        var lastRevealAt = 0L
        snapshotFlow { listScroll.value to listScroll.maxValue }
            .collect { (value, maxValue) ->
                val scrollingDown = value > lastValue
                lastValue = value
                val nearBottom = maxValue > 0 && value >= maxValue - CatalogNearBottomPx
                val now = SystemClock.uptimeMillis()
                if (shouldRevealNextCatalogPage(
                        scrollingDown = scrollingDown,
                        nearBottom = nearBottom,
                        hasMore = shownCount < filtered.size,
                        elapsedSinceLastRevealMs = now - lastRevealAt,
                    )
                ) {
                    lastRevealAt = now
                    revealStartIndex = shownCount
                    shownCount = (shownCount + CATALOG_PAGE_SIZE).coerceAtMost(filtered.size)
                }
            }
    }

    // Section header
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SettingsCardInnerHorizontalPadding, end = 24.dp, top = 18.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.wake_word_catalog_section_online),
            fontSize = settingsBodyTextSize(base = 14f),
            fontWeight = FontWeight.Normal,
            color = getSettingsDescriptionColor(),
        )
        Box(
            modifier = Modifier
                .background(
                    color = if (isDark) Color(0xFF2A2A2A) else Color(0xFFF1F5F9),
                    shape = RoundedCornerShape(50),
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = engineLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = getSettingsDescriptionColor(),
            )
        }
    }

    // Card wrapping search + list
    SimpleCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            CatalogSearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.padding(top = 14.dp, bottom = 22.dp),
            )

            when {
                loadState == WakeWordCatalogLoadState.Loading && catalog.isEmpty() -> {
                    CatalogSkeletonList()
                }
                loadState == WakeWordCatalogLoadState.Failed && catalog.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.wake_word_catalog_load_failed),
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor(),
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                visible.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.wake_word_catalog_no_match),
                        fontSize = settingsBodyTextSize(),
                        color = getSettingsDescriptionColor(),
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                else -> {
                    SettingsEdgeFadeScrollColumn(
                        maxHeight = CatalogViewportHeight,
                        scrollState = listScroll,
                        scrollEnabled = visible.size > CatalogViewportRows || hasMore,
                        fadeHeight = 18.dp,
                        verticalArrangement = Arrangement.Top,
                        flingBehavior = catalogFling,
                    ) {
                        visible.forEachIndexed { index, item ->
                            val appear = revealStartIndex > 0 && index >= revealStartIndex
                            val delayMs = ((index - revealStartIndex) * 40).coerceIn(0, 280)
                            CatalogAppearingBlock(appear = appear, delayMs = delayMs) {
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = Color(0xFFE5E7EB).copy(alpha = if (isDark) 0.15f else 0.7f),
                                        modifier = Modifier.padding(horizontal = 0.dp, vertical = 8.dp),
                                    )
                                }
                                CatalogRow(
                                    name = item.name,
                                    author = item.author,
                                    downloading = downloadingId == item.id,
                                    onDownload = { onDownload(item.id) },
                                    onInfo = { infoItem = item },
                                )
                            }
                        }
                        if (hasMore) {
                            HorizontalDivider(
                                color = Color(0xFFE5E7EB).copy(alpha = if (isDark) 0.15f else 0.7f),
                                modifier = Modifier.padding(horizontal = 0.dp, vertical = 8.dp),
                            )
                            CatalogRevealHint()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    // Source info dialog
    infoItem?.let { item ->
        AlertDialog(
            onDismissRequest = { infoItem = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = getDialogBackground(),
            title = {
                Text(
                    text = item.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = settingsTitleTextSize(),
                    color = getTitleColor(),
                )
            },
            text = {
                Text(
                    text = item.sourceUrl,
                    fontSize = settingsBodyTextSize(),
                    color = getSettingsDescriptionColor(),
                )
            },
            confirmButton = {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                TextButton(onClick = {
                    runCatching { uriHandler.openUri(item.sourceUrl) }
                    infoItem = null
                }) {
                    Text(
                        text = stringResource(R.string.wake_word_catalog_open_source),
                        color = getAccentColor(),
                        fontWeight = FontWeight.Bold,
                        fontSize = settingsTitleTextSize(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { infoItem = null }) {
                    Text(
                        text = stringResource(R.string.label_cancel),
                        fontSize = settingsTitleTextSize(),
                        color = getSettingsDescriptionColor(),
                    )
                }
            },
        )
    }
}

@Composable
private fun CatalogAppearingBlock(
    appear: Boolean,
    delayMs: Int,
    content: @Composable () -> Unit,
) {
    if (!appear) {
        content()
        return
    }
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(
            animationSpec = tween(260, delayMillis = delayMs, easing = FastOutSlowInEasing),
        ) + slideInVertically(
            animationSpec = tween(260, delayMillis = delayMs, easing = FastOutSlowInEasing),
            initialOffsetY = { it / 5 },
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun CatalogRevealHint() {
    CatalogSkeletonList(rows = 1, showLoadingLabel = false)
}

// ---------- Skeleton while the remote catalog is still in flight ----------

@Composable
private fun CatalogSkeletonList(rows: Int = 5, showLoadingLabel: Boolean = true) {
    val isDark = isDarkModeEnabled()
    val pulse = rememberInfiniteTransition(label = "catalog-skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "catalog-skeleton-alpha",
    )
    val bar = if (isDark) Color(0xFF3F3F3F) else Color(0xFFE2E8F0)
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(rows) { index ->
            if (index > 0) {
                HorizontalDivider(
                    color = Color(0xFFE5E7EB).copy(alpha = if (isDark) 0.15f else 0.7f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (index % 2 == 0) 0.55f else 0.42f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(bar.copy(alpha = alpha)),
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth(0.28f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(bar.copy(alpha = alpha * 0.75f)),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(bar.copy(alpha = alpha)),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(bar.copy(alpha = alpha * 0.7f)),
                )
            }
        }
        if (showLoadingLabel) {
            Text(
                text = stringResource(R.string.wake_word_catalog_loading),
                fontSize = settingsBodyTextSize(),
                color = getSettingsDescriptionColor(),
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
        }
    }
}

// ---------- Search capsule ----------

@Composable
private fun CatalogSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isDarkModeEnabled()
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    val bg = when {
        focused -> if (isDark) Color(0xFF3A3A3A) else Color.White
        else -> if (isDark) Color(0xFF2C2C2C) else Color(0xFFF1F5F9)
    }
    val borderColor = when {
        focused -> getAccentColor().copy(alpha = 0.45f)
        isDark -> Color.White.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    val inputColor = getLabelColor()
    val placeholderColor = getSettingsDescriptionColor()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.5.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = if (focused) getAccentColor() else placeholderColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.wake_word_catalog_search_placeholder),
                    fontSize = 15.sp,
                    color = placeholderColor,
                )
            }
            CompositionLocalProvider(LocalContentColor provides inputColor) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = inputColor,
                        fontSize = 15.sp,
                    ),
                    cursorBrush = SolidColor(getAccentColor()),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
        }
        if (query.isNotEmpty()) {
            // Row's rounded clip caps the overflow at the bar bounds — still ~40dp of
            // hit area versus the 18dp chip alone.
            ExpandedTapTarget(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(18.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFCBD5E1))
                        .padding(2.dp),
                )
            }
        }
    }
}

// ---------- One catalog row ----------

@Composable
private fun CatalogRow(
    name: String,
    author: String,
    downloading: Boolean,
    onDownload: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = settingsTitleTextSize(),
                fontWeight = FontWeight.Medium,
                color = getTitleColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (author.isNotBlank()) {
                Text(
                    text = author,
                    fontSize = 12.sp,
                    color = getSettingsDescriptionColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(getAccentColor().copy(alpha = 0.12f))
                .then(
                    if (!downloading) Modifier.clickable(onClick = onDownload)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (downloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = getAccentColor(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    tint = getAccentColor(),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
            onClick = onInfo,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
