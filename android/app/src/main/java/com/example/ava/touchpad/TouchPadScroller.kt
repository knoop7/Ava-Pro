package com.example.ava.touchpad

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Live two-finger scroll while fingers stay on the pad.
 *
 * [android.accessibilityservice.AccessibilityService.dispatchGesture] is cancelled
 * for any real touch on the display, so Apple-style "fingers down → content
 * moves now" has to use node scroll actions.
 */
internal object TouchPadScroller {
    private const val ACTION_SCROLL_UP = 16908344
    private const val ACTION_SCROLL_LEFT = 16908345
    private const val ACTION_SCROLL_DOWN = 16908346
    private const val ACTION_SCROLL_RIGHT = 16908347
    private const val ACTION_SCROLL_IN_DIRECTION = 16908374
    private const val ACTION_CONTEXT_CLICK = 16908348

    fun packageAt(service: AccessibilityService, x: Float, y: Float): String {
        return packageUnderPoint(service, x, y)
            .ifBlank { service.rootInActiveWindow?.packageName?.toString().orEmpty() }
    }

    fun packageUnderPoint(service: AccessibilityService, x: Float, y: Float): String {
        val px = x.roundToInt()
        val py = y.roundToInt()
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        if (windows.isEmpty()) return ""
        val ordered = windows.sortedWith(
            compareBy<AccessibilityWindowInfo> { if (isAccessibilityOverlay(it)) 1 else 0 }
                .thenBy { if (windowContains(it, px, py)) 0 else 1 }
                .thenByDescending { windowLayer(it) },
        )
        for (window in ordered) {
            if (isAccessibilityOverlay(window)) continue
            if (!windowContains(window, px, py)) continue
            val root = window.root ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            root.recycle()
            if (pkg.isNotBlank()) return pkg
        }
        return ""
    }

    fun findScrollable(service: AccessibilityService, x: Float, y: Float): AccessibilityNodeInfo? {
        val px = x.roundToInt()
        val py = y.roundToInt()
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        if (windows.isNotEmpty()) {
            val ordered = windows.sortedWith(
                compareBy<AccessibilityWindowInfo> { if (isAccessibilityOverlay(it)) 1 else 0 }
                    .thenBy { if (windowContains(it, px, py)) 0 else 1 }
                    .thenByDescending { windowLayer(it) },
            )
            for (window in ordered) {
                if (isAccessibilityOverlay(window)) continue
                val root = window.root ?: continue
                val inWindow = windowContains(window, px, py)
                val found = pickScrollable(root, px, py, allowLargest = inWindow)
                if (found !== root) root.recycle()
                if (found != null) return found
                if (inWindow) return null
            }
        }
        val root = service.rootInActiveWindow ?: return null
        val found = pickScrollable(root, px, py, allowLargest = true)
        if (found !== root) root.recycle()
        return found
    }

    fun scroll(node: AccessibilityNodeInfo, dx: Float, dy: Float, amount: Float): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val args = Bundle().apply {
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_DIRECTION_INT,
                    scrollFocus(dx, dy),
                )
                putFloat(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT,
                    amount.coerceIn(0.008f, 0.045f),
                )
            }
            val ok = runCatching {
                node.performAction(ACTION_SCROLL_IN_DIRECTION, args)
            }.getOrDefault(false)
            if (ok) return true
        }
        val granular = granularAction(dx, dy)
        if (granular != 0 && hasAction(node, granular)) {
            val ok = runCatching { node.performAction(granular) }.getOrDefault(false)
            if (ok) return true
        }
        val action = if (TouchPadMath.scrollActionForward(dx, dy)) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return runCatching { node.performAction(action) }.getOrDefault(false)
    }

    fun stepPx(node: AccessibilityNodeInfo, density: Float): Float {
        if (Build.VERSION.SDK_INT >= 34 && hasAction(node, ACTION_SCROLL_IN_DIRECTION)) {
            return TouchPadMath.dp(10f, density)
        }
        if (hasGranular(node)) {
            return TouchPadMath.dp(14f, density)
        }
        return TouchPadMath.dp(18f, density)
    }

    fun scrollAmount(node: AccessibilityNodeInfo, dx: Float, dy: Float, stepPx: Float): Float {
        val travel = TouchPadMath.hypot(dx, dy).coerceAtLeast(stepPx)
        return (travel / TouchPadMath.SCROLL_WHEEL_REF_PX).coerceIn(0.008f, 0.045f)
    }

    fun recycle(node: AccessibilityNodeInfo?) {
        node ?: return
        runCatching { node.recycle() }
    }

    /**
     * Click the window under an accessibility overlay at [x], [y].
     * Skips the pad itself so the origin can punch through to home / desktop.
     * [skipHostPackage] tries other apps first so Ava chrome (FAB, pad) does not eat an AI tap.
     */
    fun clickThrough(
        service: AccessibilityService,
        x: Float,
        y: Float,
        skipHostPackage: String? = null,
    ): Boolean {
        if (!skipHostPackage.isNullOrBlank()) {
            val foreign = findClickable(service, x, y, skipHostPackage = skipHostPackage)
            if (foreign != null) {
                val ok = runCatching {
                    foreign.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                recycle(foreign)
                if (ok) return true
            }
        }
        val node = findClickable(service, x, y) ?: return false
        val ok = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        recycle(node)
        return ok
    }

    fun contextClick(service: AccessibilityService, x: Float, y: Float): Boolean {
        val node = findContextNode(service, x, y) ?: return false
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            hasAction(node, ACTION_CONTEXT_CLICK)
        ) {
            runCatching {
                node.performAction(ACTION_CONTEXT_CLICK)
            }.getOrDefault(false)
        } else {
            runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }.getOrDefault(false)
        }
        recycle(node)
        return ok
    }

    private fun findContextNode(
        service: AccessibilityService,
        x: Float,
        y: Float,
    ): AccessibilityNodeInfo? {
        val px = x.roundToInt()
        val py = y.roundToInt()
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        if (windows.isNotEmpty()) {
            val ordered = windows.sortedWith(
                compareBy<AccessibilityWindowInfo> { if (isAccessibilityOverlay(it)) 1 else 0 }
                    .thenBy { if (windowContains(it, px, py)) 0 else 1 }
                    .thenByDescending { windowLayer(it) },
            )
            for (window in ordered) {
                if (isAccessibilityOverlay(window)) continue
                val root = window.root ?: continue
                val found = pickContextNode(root, px, py)
                if (found !== root) root.recycle()
                if (found != null) return found
                if (windowContains(window, px, py)) return null
            }
        }
        val root = service.rootInActiveWindow ?: return null
        val found = pickContextNode(root, px, py)
        if (found !== root) root.recycle()
        return found
    }

    private fun findClickable(
        service: AccessibilityService,
        x: Float,
        y: Float,
        skipHostPackage: String? = null,
    ): AccessibilityNodeInfo? {
        val px = x.roundToInt()
        val py = y.roundToInt()
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        if (windows.isNotEmpty()) {
            val ordered = windows.sortedWith(
                compareBy<AccessibilityWindowInfo> { if (isAccessibilityOverlay(it)) 1 else 0 }
                    .thenBy { if (windowContains(it, px, py)) 0 else 1 }
                    .thenByDescending { windowLayer(it) },
            )
            for (window in ordered) {
                if (isAccessibilityOverlay(window)) continue
                val root = window.root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (AiPhonePointerPolicy.skipHostWindow(pkg, skipHostPackage.orEmpty(), preferForeign = true)) {
                    root.recycle()
                    continue
                }
                val found = pickClickable(root, px, py)
                if (found !== root) root.recycle()
                if (found != null) return found
                if (skipHostPackage.isNullOrBlank() && windowContains(window, px, py)) return null
            }
        }
        return null
    }

    private fun pickClickable(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
    ): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null
        var hitDepth = -1
        var hitPref = -1

        fun preference(node: AccessibilityNodeInfo): Int {
            if (!node.isEnabled) return 0
            if (node.isClickable || hasAction(node, AccessibilityNodeInfo.ACTION_CLICK)) {
                return 1
            }
            return 0
        }

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.contains(x, y)) {
                val pref = preference(node)
                if (pref > 0 && (pref > hitPref || (pref == hitPref && depth >= hitDepth))) {
                    val previous = hit
                    if (previous != null && previous !== root) previous.recycle()
                    hit = if (node === root) root else AccessibilityNodeInfo.obtain(node)
                    hitDepth = depth
                    hitPref = pref
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }
        walk(root, 0)
        return hit
    }

    private fun pickContextNode(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
    ): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null
        var hitDepth = -1
        var hitPref = -1

        fun preference(node: AccessibilityNodeInfo): Int {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                hasAction(node, ACTION_CONTEXT_CLICK)
            ) {
                return 2
            }
            if (hasAction(node, AccessibilityNodeInfo.ACTION_LONG_CLICK) || node.isLongClickable) {
                return 1
            }
            return 0
        }

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.contains(x, y)) {
                val pref = preference(node)
                if (pref > 0 && (pref > hitPref || (pref == hitPref && depth >= hitDepth))) {
                    val previous = hit
                    if (previous != null && previous !== root) previous.recycle()
                    hit = if (node === root) root else AccessibilityNodeInfo.obtain(node)
                    hitDepth = depth
                    hitPref = pref
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }
        walk(root, 0)
        return hit
    }

    private fun pickScrollable(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        allowLargest: Boolean,
    ): AccessibilityNodeInfo? {
        var atPoint: AccessibilityNodeInfo? = null
        var atPointDepth = -1
        var largest: AccessibilityNodeInfo? = null
        var bestArea = 0

        fun considerLargest(node: AccessibilityNodeInfo) {
            if (!allowLargest || !canScroll(node)) return
            val area = nodeArea(node)
            if (area <= bestArea) return
            val previous = largest
            if (previous != null && previous !== root) previous.recycle()
            largest = if (node === root) root else AccessibilityNodeInfo.obtain(node)
            bestArea = area
        }

        fun considerPoint(node: AccessibilityNodeInfo, depth: Int) {
            if (!canScroll(node) || depth < atPointDepth) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.contains(x, y)) return
            val previous = atPoint
            if (previous != null && previous !== root) previous.recycle()
            atPoint = if (node === root) root else AccessibilityNodeInfo.obtain(node)
            atPointDepth = depth
        }

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            considerLargest(node)
            considerPoint(node, depth)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }
        walk(root, 0)
        val hit = atPoint
        val extra = largest
        if (hit != null) {
            if (extra != null && extra !== root && extra !== hit) extra.recycle()
            return hit
        }
        return extra
    }

    private fun nodeArea(node: AccessibilityNodeInfo): Int {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds.width().coerceAtLeast(0) * bounds.height().coerceAtLeast(0)
    }

    private fun canScroll(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable) return true
        return node.actionList.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                action.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ||
                action.id == ACTION_SCROLL_UP ||
                action.id == ACTION_SCROLL_DOWN ||
                action.id == ACTION_SCROLL_LEFT ||
                action.id == ACTION_SCROLL_RIGHT ||
                action.id == ACTION_SCROLL_IN_DIRECTION
        }
    }

    private fun hasGranular(node: AccessibilityNodeInfo): Boolean =
        hasAction(node, ACTION_SCROLL_UP) ||
            hasAction(node, ACTION_SCROLL_DOWN) ||
            hasAction(node, ACTION_SCROLL_LEFT) ||
            hasAction(node, ACTION_SCROLL_RIGHT)

    private fun hasAction(node: AccessibilityNodeInfo, actionId: Int): Boolean =
        node.actionList.any { it.id == actionId }

    private fun granularAction(dx: Float, dy: Float): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return 0
        return if (abs(dy) >= abs(dx)) {
            if (dy > 0f) ACTION_SCROLL_UP else ACTION_SCROLL_DOWN
        } else if (dx > 0f) {
            ACTION_SCROLL_LEFT
        } else {
            ACTION_SCROLL_RIGHT
        }
    }

    /** Apple natural: finger down → content down (FOCUS_UP / BACKWARD). */
    private fun scrollFocus(dx: Float, dy: Float): Int =
        if (abs(dy) >= abs(dx)) {
            if (dy > 0f) View.FOCUS_UP else View.FOCUS_DOWN
        } else {
            if (dx > 0f) View.FOCUS_LEFT else View.FOCUS_RIGHT
        }

    private fun windowContains(window: AccessibilityWindowInfo, x: Int, y: Int): Boolean {
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        return bounds.contains(x, y)
    }

    private fun isAccessibilityOverlay(window: AccessibilityWindowInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 &&
            window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY

    private fun windowLayer(window: AccessibilityWindowInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) window.layer else 0
}
