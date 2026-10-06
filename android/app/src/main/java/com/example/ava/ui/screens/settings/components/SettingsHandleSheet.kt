package com.example.ava.ui.screens.settings.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ava.ui.rememberCompactSquareScreen
import com.example.ava.ui.screens.settings.LocalSettingsSplitActive

/**
 * Handle-opened sheet. Full-window [ModalBottomSheet] off split; inside the
 * current pane when landscape split is on so left/right stay distinct.
 * Split fills the pane — no height cap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHandleSheet(
    onDismiss: () -> Unit,
    containerColor: Color,
    sheetState: SheetState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fallbackState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    if (LocalSettingsSplitActive.current) {
        SettingsPaneBoundSheet(
            onDismiss = onDismiss,
            containerColor = containerColor,
            content = content,
        )
    } else {
        val fillPane = rememberCompactSquareScreen()
        val maxHeight = settingsHandleSheetMaxHeight()
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            modifier = if (fillPane) {
                Modifier.fillMaxHeight()
            } else {
                Modifier
                    .padding(horizontal = settingsListHorizontalPadding())
                    .wrapContentHeight()
            },
            sheetState = sheetState ?: fallbackState,
            sheetMaxWidth = if (fillPane) Dp.Unspecified else BottomSheetDefaults.SheetMaxWidth,
            containerColor = containerColor,
            shape = if (fillPane) RectangleShape else BottomSheetDefaults.ExpandedShape,
            dragHandle = null,
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (fillPane) {
                                Modifier.fillMaxHeight()
                            } else {
                                Modifier.heightIn(max = maxHeight).wrapContentHeight()
                            },
                        ),
                    content = content,
                )
            },
        )
    }
}

@Composable
private fun SettingsPaneBoundSheet(
    onDismiss: () -> Unit,
    containerColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onDismiss,
                )
                .focusProperties { canFocus = false }
                .background(Color.Black.copy(alpha = 0.32f))
        )
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = containerColor,
            shape = RectangleShape,
            content = {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    content = content,
                )
            },
        )
    }
}

/**
 * Chrome for a handle-opened sheet on a square panel up to 680px.
 * The container fills; the grip is left to the caller.
 */
data class HandleSheetFill(
    val fill: Boolean,
    val modifier: Modifier,
    val sheetMaxWidth: Dp,
    val shape: Shape,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberHandleSheetFill(): HandleSheetFill {
    val fill = rememberCompactSquareScreen()
    return if (fill) {
        HandleSheetFill(
            fill = true,
            modifier = Modifier.fillMaxHeight(),
            sheetMaxWidth = Dp.Unspecified,
            shape = RectangleShape,
        )
    } else {
        HandleSheetFill(
            fill = false,
            modifier = Modifier,
            sheetMaxWidth = BottomSheetDefaults.SheetMaxWidth,
            shape = BottomSheetDefaults.ExpandedShape,
        )
    }
}
