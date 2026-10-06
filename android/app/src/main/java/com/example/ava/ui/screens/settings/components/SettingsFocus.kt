package com.example.ava.ui.screens.settings.components

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ava.ui.components.LocalSidebarFocusRingVisible
import com.example.ava.ui.components.RemoteFocusSession
import com.example.ava.ui.components.sidebarDrawerFocusable
import com.example.ava.ui.screens.home.KEY_DARK_MODE
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.theme.AccentBlue
import com.example.ava.ui.theme.AccentBrown
import kotlinx.coroutines.launch

/**
 * D-pad / keyboard focus ring for settings surfaces, so kiosk panels driven by
 * a remote control (DPAD key events) can see where focus is. Compose already
 * moves focus between clickable/toggleable nodes on DPAD input; what it does
 * not do is draw anything, because the custom settings ripple only renders
 * presses. This ring appears while the element — or an interactive child such
 * as a [androidx.compose.material3.Switch] — holds focus, and is invisible to
 * touch users (touch mode never focuses these nodes).
 *
 * Color follows the theme accent ([AccentBlue] light / [AccentBrown] dark),
 * matching [com.example.ava.ui.screens.settings.getAccentColor]. The node reads
 * the app's own dark-mode preference — not the system theme — because that is
 * what drives every settings screen.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.settingsFocusHighlight(
    cornerRadius: Dp = 14.dp,
    /** Rows sit flush against their card padding; bleed slightly so the ring doesn't hug text. */
    horizontalOutset: Dp = 6.dp,
): Modifier = this
    .composed {
        val requester = remember { BringIntoViewRequester() }
        val scope = rememberCoroutineScope()
        val sidebarRingVisible = LocalSidebarFocusRingVisible.current
        this
            .sidebarDrawerFocusable()
            .bringIntoViewRequester(requester)
            .onFocusChanged { state ->
                if (state.hasFocus && RemoteFocusSession.isActive) {
                    scope.launch { requester.bringIntoView() }
                }
            }
            .then(
                SettingsFocusHighlightElement(
                    cornerRadius,
                    horizontalOutset,
                    sidebarRingVisible,
                ),
            )
    }

private const val FocusFillAlpha = 0.10f
private val FocusStrokeWidth = 2.dp

private data class SettingsFocusHighlightElement(
    val cornerRadius: Dp,
    val horizontalOutset: Dp,
    val sidebarRingVisible: Boolean,
) : ModifierNodeElement<SettingsFocusHighlightNode>() {

    override fun create(): SettingsFocusHighlightNode =
        SettingsFocusHighlightNode(cornerRadius, horizontalOutset, sidebarRingVisible)

    override fun update(node: SettingsFocusHighlightNode) {
        node.cornerRadius = cornerRadius
        node.horizontalOutset = horizontalOutset
        node.sidebarRingVisible = sidebarRingVisible
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "settingsFocusHighlight"
        properties["cornerRadius"] = cornerRadius
        properties["horizontalOutset"] = horizontalOutset
        properties["sidebarRingVisible"] = sidebarRingVisible
    }
}

private class SettingsFocusHighlightNode(
    var cornerRadius: Dp,
    var horizontalOutset: Dp,
    var sidebarRingVisible: Boolean,
) : Modifier.Node(), FocusEventModifierNode, DrawModifierNode, CompositionLocalConsumerModifierNode {

    private var focused = false
    private var remoteArmed = false

    override fun onAttach() {
        coroutineScope.launch {
            RemoteFocusSession.active.collect { armed ->
                if (remoteArmed != armed) {
                    remoteArmed = armed
                    invalidateDraw()
                }
            }
        }
    }

    override fun onFocusEvent(focusState: FocusState) {
        // hasFocus also covers descendants, e.g. the Switch inside a SwitchSetting row.
        val nowFocused = focusState.hasFocus
        if (focused != nowFocused) {
            focused = nowFocused
            invalidateDraw()
        }
    }

    private fun isDarkMode(): Boolean =
        currentValueOf(LocalContext)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, false)

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!paintSettingsFocusRing(focused, remoteArmed, sidebarRingVisible)) return
        val accent = if (isDarkMode()) AccentBrown else AccentBlue
        val outset = horizontalOutset.toPx()
        val strokeWidth = FocusStrokeWidth.toPx()
        val radius = CornerRadius(cornerRadius.toPx())
        val topLeft = Offset(-outset, 0f)
        val ringSize = Size(size.width + outset * 2f, size.height)
        drawRoundRect(
            color = accent.copy(alpha = FocusFillAlpha),
            topLeft = topLeft,
            size = ringSize,
            cornerRadius = radius,
        )
        drawRoundRect(
            color = accent,
            topLeft = Offset(topLeft.x + strokeWidth / 2f, strokeWidth / 2f),
            size = Size(ringSize.width - strokeWidth, ringSize.height - strokeWidth),
            cornerRadius = radius,
            style = Stroke(width = strokeWidth),
        )
    }
}

/** Settings pages pass [sidebarRingVisible] = true. The drawer starts false. */
internal fun paintSettingsFocusRing(
    focused: Boolean,
    remoteArmed: Boolean,
    sidebarRingVisible: Boolean,
): Boolean = focused && remoteArmed && sidebarRingVisible
