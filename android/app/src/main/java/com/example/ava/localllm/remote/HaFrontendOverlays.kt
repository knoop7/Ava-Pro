package com.example.ava.localllm.remote

/**
 * Home Assistant floating UI that must report a callback after tap.
 * Official frontend has ~246 overlay-related `@customElement` tags
 * (`dialog-*`, `hui-dialog-*`, `ha-dialog-*`, `ha-more-info-*`,
 * `more-info-*`, sheets, quick bar, notification drawer, toast).
 * The host classifies by kind — do not dump the tag table into the prompt.
 *
 * Kinds that need a tap receipt:
 * - more_info: entity panel (`ha-more-info-dialog` via `hass-more-info`)
 * - shortcut: home shortcut editor (`dialog-edit-shortcut`, `dialog-shortcuts`)
 * - dialog: `show-dialog` manager hosts (`dialog-*` / `hui-dialog-*` / `ha-dialog-*`)
 * - sheet: `ha-bottom-sheet` / `ha-resizable-bottom-sheet` (phone more-info)
 * - quick_bar: command palette
 * - drawer: notification drawer
 * - menu: `wa-popup` / `ha-dropdown` after a select tap
 * - toast: `ha-toast` success/fail
 */
internal object HaFrontendOverlays {

    const val HOST_CHECK =
        "tag.indexOf('dialog')>=0||tag.indexOf('more-info')>=0||tag.indexOf('sheet')>=0||" +
            "tag==='ha-quick-bar'||tag==='notification-drawer'||tag==='ha-toast'||" +
            "tag==='ha-voice-command-dialog'||tag==='ha-adaptive-dialog'||" +
            "tag==='ha-bottom-sheet'||tag==='ha-resizable-bottom-sheet'||" +
            "tag==='wa-popup'||tag==='ha-dropdown'"

    const val KIND_JS =
        "tag==='ha-more-info-dialog'||tag.indexOf('more-info-')===0?'more_info':" +
            "tag==='dialog-edit-shortcut'||tag==='dialog-shortcuts'?'shortcut':" +
            "tag==='ha-quick-bar'?'quick_bar':" +
            "tag==='notification-drawer'?'drawer':" +
            "tag==='ha-toast'?'toast':" +
            "tag.indexOf('sheet')>=0?'sheet':" +
            "tag==='wa-popup'||tag==='ha-dropdown'?'menu':" +
            "tag.indexOf('dialog')>=0?'dialog':'overlay'"

    const val SHELL_SEL = "ha-adaptive-dialog,ha-dialog,ha-bottom-sheet,ha-resizable-bottom-sheet,ha-md-dialog"

    val KINDS = listOf(
        "more_info", "shortcut", "dialog", "sheet", "quick_bar", "drawer", "menu", "toast",
    )
}
