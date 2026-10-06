package com.example.ava.permissions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app guide for the Permission Manager screen: scroll to a row and pulse a ripple
 * so the user can tap Authorize (which opens system Accessibility settings).
 */
object PermissionGuideCoordinator {
    private val _highlight = MutableStateFlow<ManagedPermission?>(null)
    val highlight: StateFlow<ManagedPermission?> = _highlight.asStateFlow()

    fun requestHighlight(permission: ManagedPermission) {
        _highlight.value = permission
    }

    fun clear() {
        _highlight.value = null
    }
}
