package com.example.ava.ui.screens.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.ava.ui.screens.settings.getAccentColor
import com.example.ava.ui.screens.settings.getDialogBackground
import com.example.ava.ui.screens.settings.getInputBackground
import com.example.ava.ui.screens.settings.getSettingsDescriptionColor
import com.example.ava.ui.screens.settings.getSliderInactiveColor
import com.example.ava.ui.screens.settings.getSlateMutedColor
import com.example.ava.ui.screens.settings.getTitleColor

/** One picker row ≈ 12+12 padding + 14sp. */
private val BoxedSelectRowHeight = 46.dp
private val BoxedSelectMenuGap = 8.dp
private const val BoxedSelectVisibleRows = 3

private data class BoxedSelectPopupPlacement(
    val alignment: Alignment,
    val offsetY: Int,
    val menuMaxHeight: Dp,
    val openUpward: Boolean,
)

/**
 * Keep the menu off the trigger: 3 visible rows, shrink to leftover screen
 * height, open upward when the space below is too tight.
 */
@Composable
private fun rememberBoxedSelectPopupPlacement(
    triggerHeightPx: Int,
    triggerTopInWindowPx: Int,
    triggerBottomInWindowPx: Int,
): BoxedSelectPopupPlacement {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHpx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val navBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val statusTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val gapPx = with(density) { BoxedSelectMenuGap.toPx() }
    val remainingBelow = with(density) {
        (screenHpx - triggerBottomInWindowPx - navBottomPx - gapPx)
            .coerceAtLeast(0f)
            .toDp()
    }
    val remainingAbove = with(density) {
        (triggerTopInWindowPx - statusTopPx - gapPx)
            .coerceAtLeast(0f)
            .toDp()
    }
    val openUpward = remainingBelow < BoxedSelectRowHeight * 2 &&
        remainingAbove > remainingBelow
    val menuMaxH = minOf(
        BoxedSelectRowHeight * BoxedSelectVisibleRows,
        if (openUpward) remainingAbove else remainingBelow,
    ).coerceAtLeast(BoxedSelectRowHeight)
    val menuGapPx = with(density) { BoxedSelectMenuGap.roundToPx() }
    return if (openUpward) {
        BoxedSelectPopupPlacement(
            alignment = Alignment.BottomStart,
            offsetY = -triggerHeightPx - menuGapPx,
            menuMaxHeight = menuMaxH,
            openUpward = true,
        )
    } else {
        BoxedSelectPopupPlacement(
            alignment = Alignment.TopStart,
            offsetY = triggerHeightPx + menuGapPx,
            menuMaxHeight = menuMaxH,
            openUpward = false,
        )
    }
}

@Composable
fun BoxedSelectPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    triggerWidthPx: Int,
    triggerHeightPx: Int,
    triggerTopInWindowPx: Int,
    triggerBottomInWindowPx: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded || triggerWidthPx <= 0) return
    val density = LocalDensity.current
    val placement = rememberBoxedSelectPopupPlacement(
        triggerHeightPx = triggerHeightPx,
        triggerTopInWindowPx = triggerTopInWindowPx,
        triggerBottomInWindowPx = triggerBottomInWindowPx,
    )
    val border = getSliderInactiveColor()
    Popup(
        alignment = placement.alignment,
        offset = IntOffset(0, placement.offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier
                .width(with(density) { triggerWidthPx.toDp() })
                .heightIn(max = placement.menuMaxHeight)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(16.dp),
                    ambientColor = Color(0x2E0F172A),
                    spotColor = Color(0x2E0F172A),
                ),
            shape = RoundedCornerShape(16.dp),
            color = getDialogBackground(),
            border = BorderStroke(1.dp, border),
        ) {
            SettingsEdgeFadeScrollColumn(
                maxHeight = placement.menuMaxHeight,
                fadeHeight = 16.dp,
                verticalArrangement = Arrangement.Top,
                content = content,
            )
        }
    }
}

/**
 * Same chrome as the equalizer preset picker and the software-update version list:
 * title, then a bordered box with the current value and a chevron.
 */
@Composable
fun <T> BoxedSelectPicker(
    title: String,
    selected: T,
    items: List<T>,
    value: (T) -> String,
    onSelect: (T) -> Unit,
    description: String = "",
    enabled: Boolean = true,
    titleStartPadding: Dp = 0.dp,
    titleTrailing: (@Composable () -> Unit)? = null,
    verticalPadding: Dp = 12.dp,
    valueFontSize: TextUnit = 13.sp,
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableIntStateOf(0) }
    var triggerHeightPx by remember { mutableIntStateOf(0) }
    var triggerTopInWindowPx by remember { mutableIntStateOf(0) }
    var triggerBottomInWindowPx by remember { mutableIntStateOf(0) }
    val border = getSliderInactiveColor()
    val pickBg = getInputBackground()
    val labelColor = getTitleColor()
    val subColor = getSlateMutedColor()
    val accent = getAccentColor()
    val selectedLabel = value(selected)

    Column(modifier = Modifier.padding(vertical = verticalPadding)) {
        if (title.isNotBlank() || titleTrailing != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = titleStartPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        fontSize = settingsTitleTextSize(),
                        fontWeight = FontWeight.Medium,
                        color = labelColor,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                titleTrailing?.invoke()
            }
        }
        if (description.isNotBlank()) {
            CollapsibleDescriptionText(
                text = description,
                fontSize = settingsBodyTextSize(),
                lineHeight = settingsBodyLineHeight(),
                color = getSettingsDescriptionColor(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (title.isNotBlank() || description.isNotBlank()) 10.dp else 0.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        triggerWidthPx = coords.size.width
                        triggerHeightPx = coords.size.height
                        val bounds = coords.boundsInWindow()
                        triggerTopInWindowPx = bounds.top.toInt()
                        triggerBottomInWindowPx = bounds.bottom.toInt()
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, border, RoundedCornerShape(14.dp))
                    .background(pickBg)
                    .clickable(enabled = enabled) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = selectedLabel,
                    modifier = Modifier.weight(1f),
                    color = labelColor,
                    fontSize = valueFontSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SettingsChevronIcon(tint = subColor, base = 20f)
            }
            BoxedSelectPopup(
                expanded = expanded,
                onDismiss = { expanded = false },
                triggerWidthPx = triggerWidthPx,
                triggerHeightPx = triggerHeightPx,
                triggerTopInWindowPx = triggerTopInWindowPx,
                triggerBottomInWindowPx = triggerBottomInWindowPx,
            ) {
                items.forEach { item ->
                    val isCurrent = item == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = false
                                onSelect(item)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = value(item),
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            color = if (isCurrent) accent else labelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
