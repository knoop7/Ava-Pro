package com.example.ava.ui

import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.example.ava.platform.PlatformCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import kotlin.math.min

/**
 * Fullscreen overlay window — 1:1 with [com.example.ava.services.QuickEntityOverlayService].
 *
 * - Window draws edge-to-edge (status bar / cutout / navigation areas).
 * - Background is full-bleed; only interactive content uses 5% of the shorter side.
 */
fun WindowManager.LayoutParams.applyQuickEntityOverlayWindowFlags(
    notFocusable: Boolean = true
) {
    width = WindowManager.LayoutParams.MATCH_PARENT
    height = WindowManager.LayoutParams.MATCH_PARENT
    gravity = Gravity.TOP or Gravity.START
    x = 0
    y = 0
    var windowFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_FULLSCREEN or
        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
        WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
    if (notFocusable) {
        windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }
    flags = windowFlags
    format = PixelFormat.TRANSLUCENT
    PlatformCapabilities.applyDisplayCutoutShortEdges(this)
    com.example.ava.services.OverlayOrientation.apply(this)
}

/**
 * Compose overlay root setup — matches custom [View] overlays (Quick Entity / Weather).
 * Background stays full-bleed; window insets are passed through so Compose can apply
 * cutout / system-bar safe areas to interactive content via [QuickEntityOverlayRoot].
 */
fun View.applyQuickEntityOverlayViewSetup() {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    fitsSystemWindows = false
    if (this is ViewGroup) {
        clipToPadding = false
    }
    applyQuickEntityOverlaySystemUi()
    AvaSystemChrome.trackOverlayRoot(this)
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.post { v.applyQuickEntityOverlaySystemUi() }
        }

        override fun onViewDetachedFromWindow(v: View) = Unit
    })
    @Suppress("DEPRECATION")
    setOnSystemUiVisibilityChangeListener { visibility ->
        if (AvaSystemChrome.overlayBarsNeedReassert(this, visibility)) {
            applyQuickEntityOverlaySystemUi()
        }
    }
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets -> insets }
    ViewCompat.requestApplyInsets(this)
}

@Suppress("DEPRECATION")
private fun View.applyQuickEntityOverlaySystemUi() {
    AvaSystemChrome.applyOverlayStyleSystemUi(this)
}

/** Debounce before re-asserting immersive once the platform revealed a system bar. */
private const val IMMERSIVE_REASSERT_DELAY_MS = 500L

/**
 * For overlays that cover the screen.
 *
 * [applyQuickEntityOverlayViewSetup] only sets the deprecated `systemUiVisibility` bits, which
 * is the weaker half of the request: from API 30 the bars belong to the window insets
 * controller, so both routes are asserted here. Which bars hide follows
 * [SystemBarsMode]. Neither route has any effect unless the host window can take
 * focus — a window carrying `FLAG_NOT_FOCUSABLE` never becomes the focused window and so never
 * controls the bars. The activity behind it does, via [AvaSystemChrome.onFullscreenOverlaysChanged].
 *
 * Re-asserted on attach and after any bar the platform reveals mid-session, the same way
 * [com.example.ava.services.WebViewService] keeps the browser overlay full-bleed.
 */
fun View.installOverlayImmersiveSticky() {
    applyOverlayImmersiveSticky()
    val reassert = Runnable { applyOverlayImmersiveSticky() }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.post(reassert)
        }

        override fun onViewDetachedFromWindow(v: View) {
            v.removeCallbacks(reassert)
        }
    })
    @Suppress("DEPRECATION")
    setOnSystemUiVisibilityChangeListener { visibility ->
        if (AvaSystemChrome.overlayBarsNeedReassert(this, visibility)) {
            removeCallbacks(reassert)
            postDelayed(reassert, IMMERSIVE_REASSERT_DELAY_MS)
        }
    }
}

private fun View.applyOverlayImmersiveSticky() {
    AvaSystemChrome.applyOverlayStyleSystemUi(this)
}

private const val OVERLAY_EDGE_FRACTION = 0.05f

/**
 * Minimum content inset on each edge: 5% of the shorter side **or** the safe-drawing inset
 * (display cutout / punch-hole / system bars), whichever is larger per side.
 */
@Composable
fun rememberQuickEntityOverlayContentPadding(shortSidePx: Int): PaddingValues {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val view = LocalView.current
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val cutoutPadding = readOverlayDisplayCutoutPadding(view, density, layoutDirection)
    val edge = with(density) { (shortSidePx * OVERLAY_EDGE_FRACTION).toDp() }
    val safeStart = safeInsets.calculateStartPadding(layoutDirection)
    val safeEnd = safeInsets.calculateEndPadding(layoutDirection)
    val safeTop = safeInsets.calculateTopPadding()
    val safeBottom = safeInsets.calculateBottomPadding()
    val cutoutStart = cutoutPadding.start
    val cutoutEnd = cutoutPadding.end
    val cutoutTop = cutoutPadding.top
    val cutoutBottom = cutoutPadding.bottom
    return remember(
        edge,
        safeStart,
        safeEnd,
        safeTop,
        safeBottom,
        cutoutStart,
        cutoutEnd,
        cutoutTop,
        cutoutBottom
    ) {
        PaddingValues(
            start = maxOverlayEdgeInset(edge, safeStart.coerceAtLeast(cutoutStart)),
            top = maxOverlayEdgeInset(edge, safeTop.coerceAtLeast(cutoutTop)),
            end = maxOverlayEdgeInset(edge, safeEnd.coerceAtLeast(cutoutEnd)),
            bottom = maxOverlayEdgeInset(edge, safeBottom.coerceAtLeast(cutoutBottom))
        )
    }
}

private data class OverlayEdgeInsets(
    val start: Dp,
    val top: Dp,
    val end: Dp,
    val bottom: Dp
)

private fun readOverlayDisplayCutoutPadding(
    view: View,
    density: Density,
    layoutDirection: LayoutDirection
): OverlayEdgeInsets {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return OverlayEdgeInsets(0.dp, 0.dp, 0.dp, 0.dp)
    }
    val cutout = view.rootWindowInsets?.displayCutout
        ?: return OverlayEdgeInsets(0.dp, 0.dp, 0.dp, 0.dp)
    return with(density) {
        val physicalLeft = cutout.safeInsetLeft.toDp()
        val physicalRight = cutout.safeInsetRight.toDp()
        val (start, end) = when (layoutDirection) {
            LayoutDirection.Ltr -> physicalLeft to physicalRight
            LayoutDirection.Rtl -> physicalRight to physicalLeft
        }
        OverlayEdgeInsets(
            start = start,
            top = cutout.safeInsetTop.toDp(),
            end = end,
            bottom = cutout.safeInsetBottom.toDp()
        )
    }
}

private fun maxOverlayEdgeInset(edge: Dp, safe: Dp): Dp = edge.coerceAtLeast(safe)

/** Top-end close control shared by voice playback and other fullscreen overlays. */
@Composable
fun OverlayFloatingCloseButton(
    onClick: () -> Unit,
    containerColor: Color,
    iconTint: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/**
 * Full-bleed background + [rememberQuickEntityOverlayContentPadding] on interactive content.
 */
@Composable
fun QuickEntityOverlayRoot(
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable (contentPadding: PaddingValues) -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        val shortSidePx = min(constraints.maxWidth, constraints.maxHeight)
        val contentPadding = rememberQuickEntityOverlayContentPadding(shortSidePx)
        content(contentPadding)
    }
}
