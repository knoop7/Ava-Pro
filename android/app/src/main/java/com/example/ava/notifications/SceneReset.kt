package com.example.ava.notifications

/**
 * Rest value for HA `select.notification_scene`. The current option cannot be
 * re-selected, so Ava snaps back to [IDLE] after firing a scene.
 * `reset` / `...` still hide if an older automation sends them.
 */
object SceneReset {
    const val IDLE = "idle"
    const val ID = "reset"
    const val TITLE = "..."

    fun matchesToken(idOrTitle: String): Boolean {
        val token = idOrTitle.trim()
        return token.equals(IDLE, ignoreCase = true) ||
            token.equals(ID, ignoreCase = true) ||
            token == TITLE
    }

    fun isHideCommand(token: String): Boolean {
        val trimmed = token.trim()
        return trimmed.isEmpty() || matchesToken(trimmed)
    }

    fun selectOptions(sceneTitles: List<String>): List<String> =
        listOf(IDLE) + sceneTitles.filterNot { isHideCommand(it) }
}
