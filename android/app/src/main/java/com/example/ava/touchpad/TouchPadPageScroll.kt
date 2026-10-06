package com.example.ava.touchpad

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.example.ava.services.AiBrowserService
import com.example.ava.services.AppWindowService
import com.example.ava.services.WebViewService

/**
 * Live two-finger scroll for Ava overlay pages and mirrored app windows.
 *
 * Chromium / HA Lovelace rarely expose a usable a11y scroll action, and a
 * scrcpy virtual display has no useful node scroll. The home window behind a
 * full-screen overlay must not steal the gesture.
 */
internal object TouchPadPageScroll {
    /** [MotionEvent.FLAG_IS_GENERATED_GESTURE] (API 29). Numeric so older stubs compile. */
    private const val FLAG_IS_GENERATED_GESTURE = 0x00000008

    /**
     * [AccessibilityService.dispatchGesture] lands as a real screen touch.
     * Our pad and app-window chrome must ignore those, or the injected click
     * fights the finger still on the pad (origin twitch + repeat taps).
     * Local [dispatchScreenTap] uses [MotionEvent.obtain] and is not flagged.
     */
    fun isGeneratedGesture(event: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            event.flags and FLAG_IS_GENERATED_GESTURE != 0

    fun hitsInjectedOverlay(x: Float, y: Float): Boolean =
        AppWindowService.containsScreenPoint(x, y) ||
            WebViewService.containsScreenPoint(x, y) ||
            AiBrowserService.containsScreenPoint(x, y)

    fun overlayUnderCursor(x: Float, y: Float): Boolean {
        if (AppWindowService.containsScreenPoint(x, y)) return false
        return WebViewService.containsScreenPoint(x, y) || AiBrowserService.containsScreenPoint(x, y)
    }

    fun begin(x: Float, y: Float) {
        if (AppWindowService.containsScreenPoint(x, y)) return
        WebViewService.beginPadPageScroll()
        AiBrowserService.beginPadPageScroll()
    }

    fun nudge(x: Float, y: Float, dx: Float, dy: Float): Boolean {
        if (AppWindowService.nudgePadScroll(dx, dy)) return true
        if (AppWindowService.beginPadScroll(x, y) && AppWindowService.nudgePadScroll(dx, dy)) {
            return true
        }
        if (AppWindowService.containsScreenPoint(x, y)) return false
        if (WebViewService.nudgePageScroll(x, y, dx, dy)) return true
        return AiBrowserService.nudgePageScroll(x, y, dx, dy)
    }

    fun end() {
        AppWindowService.endPadScroll()
        WebViewService.endPadPageScroll()
        AiBrowserService.endPadPageScroll()
    }

    private var dragView: View? = null
    private var dragDownAt = 0L
    private var dragLocalX = 0f
    private var dragLocalY = 0f

    fun beginContentDrag(x: Float, y: Float): Boolean {
        if (AppWindowService.beginPadContentDrag(x, y)) return true
        if (WebViewService.beginPadContentDrag(x, y)) return true
        return AiBrowserService.beginPadContentDrag(x, y)
    }

    fun nudgeContentDrag(x: Float, y: Float): Boolean {
        if (AppWindowService.nudgePadContentDrag(x, y)) return true
        if (nudgeViewDrag(x, y)) return true
        return false
    }

    fun endContentDrag() {
        AppWindowService.endPadContentDrag()
        endViewDrag()
    }

    fun beginViewDrag(target: View, screenX: Float, screenY: Float): Boolean {
        val local = screenToLocal(target, screenX, screenY) ?: return false
        endViewDrag()
        dragView = target
        dragDownAt = SystemClock.uptimeMillis()
        dragLocalX = local.first
        dragLocalY = local.second
        return dispatchViewTouch(
            target,
            MotionEvent.ACTION_DOWN,
            dragDownAt,
            dragDownAt,
            dragLocalX,
            dragLocalY,
        )
    }

    fun nudgeViewDrag(screenX: Float, screenY: Float): Boolean {
        val target = dragView ?: return false
        val local = screenToLocal(target, screenX, screenY) ?: return false
        dragLocalX = local.first
        dragLocalY = local.second
        return dispatchViewTouch(
            target,
            MotionEvent.ACTION_MOVE,
            dragDownAt,
            SystemClock.uptimeMillis(),
            dragLocalX,
            dragLocalY,
        )
    }

    fun endViewDrag() {
        val target = dragView ?: return
        dragView = null
        dispatchViewTouch(
            target,
            MotionEvent.ACTION_UP,
            dragDownAt,
            SystemClock.uptimeMillis(),
            dragLocalX,
            dragLocalY,
        )
    }

    private fun screenToLocal(target: View, screenX: Float, screenY: Float): Pair<Float, Float>? {
        if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0) return null
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val x = (screenX - loc[0]).coerceIn(0f, target.width.toFloat())
        val y = (screenY - loc[1]).coerceIn(0f, target.height.toFloat())
        return x to y
    }

    private fun dispatchViewTouch(
        target: View,
        action: Int,
        downAt: Long,
        eventAt: Long,
        x: Float,
        y: Float,
    ): Boolean {
        if (!target.isAttachedToWindow) return false
        val ev = MotionEvent.obtain(downAt, eventAt, action, x, y, 0)
        val accepted = target.dispatchTouchEvent(ev)
        ev.recycle()
        return accepted
    }

    fun dispatchScreenTap(target: View, screenX: Float, screenY: Float): Boolean {
        if (!target.isAttachedToWindow) return false
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val x = screenX - loc[0]
        val y = screenY - loc[1]
        if (x < 0f || y < 0f || x > target.width || y > target.height) return false
        val downAt = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downAt, downAt + 16L, MotionEvent.ACTION_UP, x, y, 0)
        val accepted = target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        return accepted
    }

    fun dispatchScreenSecondaryTap(target: View, screenX: Float, screenY: Float): Boolean {
        if (!target.isAttachedToWindow) return false
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val x = screenX - loc[0]
        val y = screenY - loc[1]
        if (x < 0f || y < 0f || x > target.width || y > target.height) return false
        val downAt = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            },
        )
        val down = MotionEvent.obtain(
            downAt,
            downAt,
            MotionEvent.ACTION_DOWN,
            1,
            props,
            coords,
            0,
            MotionEvent.BUTTON_SECONDARY,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0,
        )
        val up = MotionEvent.obtain(
            downAt,
            downAt + 16L,
            MotionEvent.ACTION_UP,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0,
        )
        val accepted = target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            target.showContextMenu(x, y)
        }
        return accepted
    }

    fun jsPin(down: Boolean): String =
        if (down) {
            "try{window.__avaScrollGate&&window.__avaScrollGate.pin(true)}catch(e){}"
        } else {
            "try{window.__avaScrollGate&&window.__avaScrollGate.pin(false,90)}catch(e){}"
        }

    fun jsNudge(dx: Float, dy: Float): String {
        val x = finite(dx)
        val y = finite(dy)
        return """
            (function(dx,dy){
              function overflowed(el,vert){
                if(!el||el.nodeType!==1) return false;
                var st;
                try{st=window.getComputedStyle(el);}catch(e){return false;}
                if(!st) return false;
                var ox=st.overflowX, oy=st.overflowY;
                var root=el===document.scrollingElement||el===document.documentElement||el===document.body;
                if(vert){
                  if(!(oy==='auto'||oy==='scroll'||oy==='overlay'||root)) return false;
                  return el.scrollHeight>el.clientHeight+2;
                }
                if(!(ox==='auto'||ox==='scroll'||ox==='overlay'||root)) return false;
                return el.scrollWidth>el.clientWidth+2;
              }
              function pierce(sel,root){
                var n=(root||document).querySelector(sel);
                return n&&n.shadowRoot?n.shadowRoot:n;
              }
              function haTarget(vert){
                var n=pierce('home-assistant');
                if(!n) return null;
                n=pierce('home-assistant-main',n)||n;
                n=pierce('ha-drawer',n)||n;
                var root=pierce('hui-root',n)||pierce('ha-panel-lovelace',n)||n;
                var hit=root&&(root.querySelector('#view')||root.querySelector('.content')||root.querySelector('hui-view'));
                if(hit&&overflowed(hit,vert)) return hit;
                return (root&&overflowed(root,vert))?root:null;
              }
              function walk(node,vert,acc){
                if(!node) return;
                if(node.nodeType===1){
                  if(overflowed(node,vert)) acc.push(node);
                  if(node.shadowRoot) walk(node.shadowRoot,vert,acc);
                }
                var kids=node.children;
                if(!kids) return;
                for(var i=0;i<kids.length;i++) walk(kids[i],vert,acc);
              }
              var vert=Math.abs(dy)>=Math.abs(dx);
              var cached=window.__avaPadScroller;
              var t=cached&&cached.isConnected&&overflowed(cached,vert)?cached:null;
              if(!t) t=haTarget(vert);
              if(!t){
                var acc=[];
                walk(document.documentElement,vert,acc);
                t=acc.length?acc[acc.length-1]:(document.scrollingElement||document.documentElement);
              }
              if(!t) return false;
              window.__avaPadScroller=t;
              if(t.scrollBy) t.scrollBy(dx,dy);
              else {t.scrollLeft+=dx;t.scrollTop+=dy;}
              try{window.__avaScrollGate&&window.__avaScrollGate.mark&&window.__avaScrollGate.mark();}catch(e){}
              return true;
            })($x,$y)
        """.trimIndent()
    }

    private fun finite(value: Float): String {
        val v = if (value.isFinite()) value else 0f
        return v.toDouble().toString()
    }
}
