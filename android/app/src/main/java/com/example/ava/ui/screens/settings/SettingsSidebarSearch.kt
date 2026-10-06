package com.example.ava.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.R
import com.example.ava.ui.components.sidebarDrawerFocusable
import com.example.ava.ui.screens.home.HomeSidebarMetrics

@Composable
fun SettingsSidebarSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    textColor: Color,
    secondaryColor: Color,
    dividerColor: Color,
    sidebarScale: Float,
    showDivider: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val rowInteraction = remember { MutableInteractionSource() }
    val underline by animateColorAsState(
        targetValue = if (focused) textColor else dividerColor,
        animationSpec = tween(160),
        label = "settingsSearchUnderline",
    )
    val iconSize = (18f * sidebarScale).dp
    val textSize = (16f * sidebarScale).sp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sidebarDrawerFocusable()
                .clickable(
                    interactionSource = rowInteraction,
                    indication = null,
                    onClick = { focusRequester.requestFocus() },
                )
                .padding(start = 2.dp, end = 2.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = stringResource(R.string.settings_search_cd),
                tint = secondaryColor,
                modifier = Modifier.size(iconSize),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = (24f * sidebarScale).dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.settings_search_placeholder),
                    color = secondaryColor.copy(alpha = if (query.isEmpty()) 1f else 0f),
                    fontSize = textSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = textSize,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(textColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            focusManager.clearFocus(force = true)
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .sidebarDrawerFocusable()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused },
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(color = underline, thickness = 1.dp)
        }
    }
}

@Composable
fun SettingsSidebarSearchResults(
    results: List<SettingsSearchHit>,
    textColor: Color,
    secondaryColor: Color,
    isDarkMode: Boolean,
    sidebarScale: Float,
    onResultClick: (SettingsSearchHit) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    if (results.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_search_empty),
            color = secondaryColor,
            fontSize = HomeSidebarMetrics.entryTextSize(sidebarScale),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        return
    }
    results.forEach { hit ->
        SettingsSidebarSearchResultRow(
            hit = hit,
            textColor = textColor,
            secondaryColor = secondaryColor,
            isDarkMode = isDarkMode,
            sidebarScale = sidebarScale,
            onClick = {
                keyboard?.hide()
                onResultClick(hit)
            },
        )
    }
}

@Composable
private fun SettingsSidebarSearchResultRow(
    hit: SettingsSearchHit,
    textColor: Color,
    secondaryColor: Color,
    isDarkMode: Boolean,
    sidebarScale: Float,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val rippleColor = if (isDarkMode) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }
    val pathSize = (12.5f * sidebarScale).sp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sidebarDrawerFocusable()
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = rippleColor),
                onClick = onClick,
            )
            .padding(
                horizontal = HomeSidebarMetrics.entryPaddingHorizontal(sidebarScale),
                vertical = HomeSidebarMetrics.entryPaddingVertical(sidebarScale),
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = hit.path,
            color = secondaryColor,
            fontSize = pathSize,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = hit.title,
            color = textColor,
            fontSize = HomeSidebarMetrics.entryTextSize(sidebarScale),
            fontWeight = FontWeight.Medium,
        )
    }
}
