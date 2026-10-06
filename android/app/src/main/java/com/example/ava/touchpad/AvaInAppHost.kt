package com.example.ava.touchpad

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import com.example.ava.services.AiBrowserService
import com.example.ava.services.AppWindowService
import com.example.ava.services.WebViewService
import com.example.ava.ui.MainNavigationCoordinator
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Clicks inside Ava (settings, home) by walking our own views and injecting
 * a tap. No Accessibility service — reboot must not block in-app use.
 */
object AvaInAppHost {
    private const val TREE_CAP = 80

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    fun bind(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun unbind(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    fun isInFront(): Boolean =
        MainNavigationCoordinator.isActivityResumed() && activityRef?.get() != null

    fun needsAccessibility(localHit: Boolean, goingOutside: Boolean): Boolean =
        AiPhonePointerPolicy.needsAccessibility(isInFront(), localHit, goingOutside)

    fun decor(): View? {
        val activity = activityRef?.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        return activity.window?.decorView
    }

    suspend fun dumpTree(): JSONObject? = withContext(Dispatchers.Main.immediate) {
        dumpNow()
    }

    fun tap(x: Float, y: Float): Boolean {
        if (AppWindowService.tapAt(x, y) ||
            WebViewService.tapAt(x, y) ||
            AiBrowserService.tapAt(x, y)
        ) {
            return true
        }
        val decor = decor() ?: return false
        if (!viewContainsScreen(decor, x, y)) return false
        return TouchPadPageScroll.dispatchScreenTap(decor, x, y)
    }

    private fun dumpNow(): JSONObject? {
        val root = decor() ?: return null
        val nodes = ArrayList<LocalNode>(TREE_CAP)
        collect(root, nodes)
        if (nodes.isEmpty()) return null
        val arr = JSONArray()
        for ((index, node) in nodes.withIndex()) {
            arr.put(
                JSONObject()
                    .put("index", index)
                    .put("text", node.text)
                    .put("contentDescription", node.desc)
                    .put("clickable", node.clickable)
                    .put("editable", node.editable)
                    .put("scrollable", node.scrollable)
                    .put("checkable", node.checkable)
                    .put("checked", node.checked)
                    .put(
                        "bounds",
                        JSONObject()
                            .put("left", node.left)
                            .put("top", node.top)
                            .put("right", node.right)
                            .put("bottom", node.bottom)
                            .put("centerX", (node.left + node.right) / 2)
                            .put("centerY", (node.top + node.bottom) / 2),
                    ),
            )
        }
        return JSONObject()
            .put("status", "ok")
            .put("via", "in_app")
            .put("nodeCount", nodes.size)
            .put("nodes", arr)
    }

    private fun collect(view: View, out: ArrayList<LocalNode>) {
        if (out.size >= TREE_CAP) return
        if (view.visibility != View.VISIBLE || !view.isShown) return
        val provider = view.accessibilityNodeProvider
        if (provider != null) {
            collectVirtual(provider, View.NO_ID, out)
            return
        }
        addView(view, out)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collect(view.getChildAt(i), out)
                if (out.size >= TREE_CAP) return
            }
        }
    }

    private fun collectVirtual(
        provider: AccessibilityNodeProvider,
        hostId: Int,
        out: ArrayList<LocalNode>,
    ) {
        val info = runCatching { provider.createAccessibilityNodeInfo(hostId) }.getOrNull()
            ?: return
        try {
            walkInfo(info, out)
        } finally {
            recycle(info)
        }
    }

    private fun walkInfo(info: AccessibilityNodeInfo, out: ArrayList<LocalNode>) {
        if (out.size >= TREE_CAP) return
        addInfo(info, out)
        val n = info.childCount
        for (i in 0 until n) {
            val child = runCatching { info.getChild(i) }.getOrNull() ?: continue
            try {
                walkInfo(child, out)
            } finally {
                recycle(child)
            }
            if (out.size >= TREE_CAP) return
        }
    }

    private fun addView(view: View, out: ArrayList<LocalNode>) {
        val info = view.createAccessibilityNodeInfo() ?: return
        try {
            addInfo(info, out)
        } finally {
            recycle(info)
        }
    }

    private fun addInfo(info: AccessibilityNodeInfo, out: ArrayList<LocalNode>) {
        if (out.size >= TREE_CAP) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && !info.isVisibleToUser) {
            return
        }
        val bounds = Rect()
        info.getBoundsInScreen(bounds)
        if (bounds.isEmpty || bounds.width() < 2 || bounds.height() < 2) return
        val text = info.text?.toString().orEmpty()
        val desc = info.contentDescription?.toString().orEmpty()
        val clickable = info.isClickable ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        val editable = info.isEditable
        val scrollable = info.isScrollable
        val checkable = info.isCheckable
        val checked = info.isChecked
        if (text.isBlank() && desc.isBlank() && !clickable && !editable && !checkable) return
        out += LocalNode(
            text = text,
            desc = desc,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            checkable = checkable,
            checked = checked,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
        )
    }

    private fun viewContainsScreen(view: View, x: Float, y: Float): Boolean {
        if (!view.isAttachedToWindow) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return x >= loc[0] && y >= loc[1] &&
            x < loc[0] + view.width && y < loc[1] + view.height
    }

    private fun recycle(info: AccessibilityNodeInfo) {
        runCatching { info.recycle() }
    }

    private data class LocalNode(
        val text: String,
        val desc: String,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )
}
