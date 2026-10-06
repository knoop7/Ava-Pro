package com.example.ava.ui.screens.settings.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import com.example.ava.ui.AvaToast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ava.R
import com.example.ava.ui.prefs.rememberBooleanPreference
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getDialogBackground
import com.example.ava.ui.screens.settings.getLabelColor
import com.example.ava.ui.screens.settings.getTitleColor
import com.example.ava.ui.theme.SlateTertiary as SubLabelColor

/**
 * Near full-screen usage guide panel shared across settings screens.
 * Uses [ModalBottomSheet] with [fillMaxHeight], matching VoicePrint manual enrollment sheets.
 * Height is percentage-free at layout time; the sheet expands via platform sheet behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageGuideDialog(
    onDismissRequest: () -> Unit,
    title: String,
    copyText: String? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val isDarkMode by rememberBooleanPreference(prefs, KEY_DARK_MODE, false)
    val copyLabel = stringResource(R.string.settings_usage_guide_copy)
    val dismissLabel = stringResource(R.string.settings_usage_guide_dismiss)
    val isCustomConfirm = confirmLabel != null && onConfirm != null
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fillPane = rememberHandleSheetFill()
    val sheetBackground = getDialogBackground()
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var viewportHeightPx by remember { mutableStateOf(0) }
    val minThumbPx = with(density) { 18.dp.toPx() }
    val horizontalPad = if (isLandscape) 20.dp else 16.dp

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = fillPane.modifier,
        sheetState = sheetState,
        sheetMaxWidth = fillPane.sheetMaxWidth,
        shape = fillPane.shape,
        containerColor = sheetBackground,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(sheetBackground)
                .statusBarsPadding(),
        ) {
            ModalSheetDragHandle(
                isDarkMode = isDarkMode,
                onClick = onDismissRequest,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = settingsTitleTextSize(if (isLandscape) 16f else 18f),
                color = getTitleColor(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPad)
                    .padding(top = 4.dp, bottom = if (isLandscape) 8.dp else 12.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPad)
                    .onSizeChanged { viewportHeightPx = it.height },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(end = 6.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )

                if (scrollState.maxValue > 0 && viewportHeightPx > 0) {
                    val contentHeightPx = viewportHeightPx + scrollState.maxValue
                    val rawThumb = (viewportHeightPx.toFloat() * viewportHeightPx.toFloat()) /
                        contentHeightPx.toFloat().coerceAtLeast(1f)
                    val thumbHeightPx = rawThumb.coerceIn(minThumbPx, viewportHeightPx.toFloat())
                    val maxThumbOffset = (viewportHeightPx - thumbHeightPx).coerceAtLeast(0f)
                    val thumbOffsetPx = if (scrollState.maxValue == 0) {
                        0f
                    } else {
                        (scrollState.value.toFloat() / scrollState.maxValue.toFloat()) * maxThumbOffset
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = with(density) { thumbOffsetPx.toDp() }, end = 1.dp)
                            .height(with(density) { thumbHeightPx.toDp() })
                            .width(3.dp)
                            .background(
                                color = SubLabelColor.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(sheetBackground)
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (isCustomConfirm) {
                    TextButton(onClick = onDismissRequest) {
                        Text(
                            text = dismissLabel,
                            fontSize = settingsTitleTextSize(),
                            color = getLabelColor(),
                        )
                    }
                    TextButton(onClick = onConfirm) {
                        Text(
                            text = confirmLabel,
                            fontWeight = FontWeight.Bold,
                            fontSize = settingsTitleTextSize(),
                            color = getAccentColor(),
                        )
                    }
                } else {
                    if (!copyText.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("usage_guide", copyText))
                                AvaToast.show(context, R.string.copied_to_clipboard)
                            },
                        ) {
                            Text(
                                text = copyLabel,
                                fontSize = settingsTitleTextSize(),
                                color = getLabelColor(),
                            )
                        }
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(
                            text = dismissLabel,
                            fontWeight = FontWeight.Bold,
                            fontSize = settingsTitleTextSize(),
                            color = getAccentColor(),
                        )
                    }
                }
            }
        }
    }
}
