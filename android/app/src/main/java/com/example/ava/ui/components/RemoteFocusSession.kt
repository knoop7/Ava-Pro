package com.example.ava.ui.components

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remote D-pad focus is off until a nav key actually arrives. Cold start
 * must not paint a selection box just because Compose assigned a default
 * focusable — only MENU / arrows / enter arm the session.
 */
object RemoteFocusSession {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    val isActive: Boolean
        get() = _active.value

    fun isRemoteNavKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MOVE_HOME,
        KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_MENU,
        -> true
        else -> false
    }

    /** @return true the first time a remote nav key arms the session. */
    fun noteRemoteNav(): Boolean {
        if (_active.value) return false
        _active.value = true
        return true
    }
}
