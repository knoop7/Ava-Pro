package com.example.ava.localllm.remote

/**
 * Home Assistant frontend web components, taken from the official
 * `@customElement` table plus Claw FrontendInspect's HA_INTERACTIVE /
 * HA_SHADOW_HOSTS lists. Snapshot treats every `ha-` / `hui-` / `dialog-`
 * tag as a shadow host so settings rows, theme pickers and windows get idx.
 */
internal object HaFrontendComponents {

    const val HOST_CHECK =
        "tag.indexOf('ha-')===0||tag.indexOf('hui-')===0||tag.indexOf('hass-')===0||" +
            "tag.indexOf('dialog-')===0||tag.indexOf('md-')===0||tag.indexOf('mwc-')===0||" +
            "tag.indexOf('wa-')===0||" +
            "tag==='home-assistant'||tag==='home-assistant-main'||tag==='more-info-content'||" +
            "tag==='step-flow-form'||tag==='step-flow-menu'"

    const val CONTROL_RE =
        "/button|switch|select|slider|textfield|list-item|chip|tab|picker|checkbox|radio|fab|" +
            "toggle|combo|input|menu-item|dropdown|popup|settings-row|theme-row|theme-picker|control-|formfield/"

    const val ISEL =
        "button,a,input,select,textarea,[role=button],[role=menuitem],[role=option],[role=tab]," +
            "[role=link],[role=switch],[role=radio],[role=checkbox],[role=combobox]," +
            "ha-icon-button,mwc-icon-button,ha-button,mwc-button,ha-outlined-button," +
            "ha-list-item,mwc-list-item,ha-md-list-item,ha-check-list-item,ha-clickable-list-item," +
            "ha-dropdown-item,ha-combo-box-item,ha-integration-list-item,ha-radio-list-item," +
            "ha-switch,md-switch,ha-entity-toggle,ha-state-control-toggle,ha-control-switch," +
            "ha-control-button,ha-checkbox,md-checkbox,ha-select,ha-combo-box,ha-theme-picker," +
            "ha-pick-theme-row,ha-settings-row,ha-dropdown,ha-dropdown-item,ha-picker-field," +
            "ha-textfield,ha-input,ha-slider,md-slider," +
            "md-icon-button,md-list-item,md-menu-item,md-filled-button,md-outlined-button," +
            "md-text-button,ha-assist-chip,ha-filter-chip,ha-tab,ha-menu-button,ha-fab," +
            "ha-icon-next,ha-icon-prev,ha-progress-button,ha-button-menu"

    val INTERACTIVE = listOf(
        "ha-icon-button", "mwc-icon-button", "ha-button", "mwc-button", "ha-outlined-button",
        "ha-list-item", "mwc-list-item", "ha-md-list-item", "ha-check-list-item",
        "ha-clickable-list-item", "ha-dropdown-item", "ha-combo-box-item",
        "ha-integration-list-item", "ha-radio-list-item", "ha-list-item-button",
        "ha-switch", "md-switch", "ha-checkbox", "md-checkbox", "ha-radio", "md-radio",
        "ha-entity-toggle", "ha-state-control-toggle", "ha-control-switch", "ha-control-button",
        "ha-control-slider", "ha-control-select", "ha-slider", "md-slider", "ha-labeled-slider",
        "ha-select", "ha-combo-box", "ha-textfield", "ha-input", "ha-textarea",
        "md-filled-text-field", "md-outlined-text-field", "md-filled-select", "md-outlined-select",
        "md-filled-button", "md-outlined-button", "md-text-button", "md-icon-button",
        "md-list-item", "md-menu-item", "md-fab", "md-icon-button-toggle",
        "ha-fab", "ha-chip", "ha-assist-chip", "ha-filter-chip", "ha-tab", "ha-menu-button",
        "ha-icon-next", "ha-icon-prev", "ha-button-menu", "ha-progress-button",
        "ha-form", "ha-form-string", "ha-form-integer", "ha-form-float", "ha-form-boolean",
        "ha-form-select", "ha-formfield", "ha-selector", "ha-selector-select", "ha-selector-text",
        "ha-selector-boolean", "ha-selector-theme",
        "ha-theme-picker", "ha-pick-theme-row", "ha-dropdown", "ha-dropdown-item", "ha-picker-field",
        "ha-settings-row", "ha-entity-picker", "ha-area-picker", "ha-device-picker",
        "hui-tile-card", "hui-button-card", "hui-toggle-entity-row", "hui-generic-entity-row",
    )

    fun interactiveJs(): String =
        INTERACTIVE.joinToString(",") { "'$it':1" }

    fun iselSource(): String = ISEL
}
