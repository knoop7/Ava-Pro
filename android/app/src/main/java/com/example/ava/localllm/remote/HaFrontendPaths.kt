package com.example.ava.localllm.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Official Home Assistant frontend routes, taken from
 * `ha-panel-config`, `config-sections`, `ha-panel-my`, `ha-panel-profile`,
 * `tools-router`, and the sidebar panels. The host matches spoken words
 * and returns `goto` — do not dump this table into the model prompt.
 */
internal object HaFrontendPaths {

    data class Page(
        val path: String,
        val name: String,
        val keys: Array<out String>,
        val weight: Int = 0,
        val group: String = "",
    )

    val ALL: List<Page> = listOf(
        page("/home", "Home", "sidebar", 2, "首页", "概览", "home overview", "打开首页"),
        page("/home/overview", "Home overview", "sidebar", 1, "home overview"),
        page("/home/other-devices", "Other devices", "sidebar", 3, "其他设备", "other devices"),
        page("/lovelace", "Lovelace", "sidebar", 1, "lovelace", "旧仪表盘"),
        page("/history", "History", "sidebar", 3, "历史记录", "打开历史", "进历史", "history"),
        page("/logbook", "Logbook", "sidebar", 4, "日志簿", "logbook"),
        page("/map", "Map", "sidebar", 3, "地图", "打开地图", "map"),
        page("/energy", "Energy", "sidebar", 3, "能源", "能耗", "energy"),
        page("/todo", "To-do", "sidebar", 3, "待办", "todo", "to-do"),
        page("/calendar", "Calendar", "sidebar", 3, "日历", "calendar"),
        page("/media-browser", "Media", "sidebar", 3, "媒体浏览器", "media-browser", "media browser"),
        page("/light", "Lights", "sidebar", 4, "灯光页", "灯光面板", "打开灯光页", "lights page"),
        page("/climate", "Climate", "sidebar", 4, "空调页", "气候面板", "climate panel"),
        page("/security", "Security", "sidebar", 4, "安防页", "安防面板", "security panel"),
        page("/maintenance", "Maintenance", "sidebar", 3, "维护", "maintenance"),
        page("/config/dashboard", "Settings", "settings", 6, "设置", "设定", "settings", "进设置", "打开设置", "进入设置"),
        page("/config/integrations", "Devices & services", "devices", 5, "集成", "integrations", "设备与服务"),
        page("/config/integrations/dashboard", "Add integration", "devices", 2, "添加集成"),
        page("/config/automation/dashboard", "Automations", "automations", 8, "自动化", "automations", "automation"),
        page("/config/script/dashboard", "Scripts", "automations", 7, "脚本", "scripts"),
        page("/config/scene/dashboard", "Scenes", "automations", 7, "场景", "scenes"),
        page("/config/blueprint/dashboard", "Blueprints", "automations", 6, "蓝图", "blueprints"),
        page("/config/areas/dashboard", "Areas", "areas", 6, "区域", "areas"),
        page("/config/labels", "Labels", "areas", 5, "标签", "labels"),
        page("/config/zone", "Zones", "areas", 5, "分区", "zones", "zone"),
        page("/config/apps", "Apps", "settings", 5, "插件", "附加组件", "apps", "add-ons", "addons"),
        page("/config/apps/available", "App store", "settings", 4, "应用商店", "addon store"),
        page("/config/lovelace/dashboards", "Dashboards", "settings", 6, "仪表盘管理", "dashboards", "仪表板"),
        page("/config/lovelace/resources", "Dashboard resources", "settings", 3, "仪表盘资源"),
        page("/config/connectivity", "Connectivity", "settings", 4, "连接", "connectivity"),
        page("/config/voice-assistants", "Voice assistants", "settings", 6, "语音助手", "voice assistants", "assist"),
        page("/config/voice-assistants/assistants", "Assistants", "settings", 3, "助手列表"),
        page("/config/voice-assistants/expose", "Expose", "settings", 3, "暴露实体", "expose"),
        page("/config/person", "People", "people", 5, "人员", "people", "家人"),
        page("/config/users", "Users", "people", 5, "用户", "users"),
        page("/config/system", "System", "system", 5, "系统", "system"),
        page("/config/tools", "Tools", "tools", 6, "开发者工具", "工具", "developer tools", "tools"),
        page("/config/info", "About", "settings", 3, "关于", "about", "info"),
        page("/config/devices/dashboard", "Devices", "devices", 7, "设备页", "设备列表", "devices"),
        page("/config/entities", "Entities", "devices", 7, "实体", "entities"),
        page("/config/helpers", "Helpers", "devices", 6, "辅助元素", "helpers"),
        page("/config/tags", "Tags", "settings", 4, "NFC", "tags"),
        page("/config/cloud", "Home Assistant Cloud", "settings", 5, "云", "nabu casa", "cloud"),
        page("/config/general", "General", "system", 5, "常规", "general", "基本设置"),
        page("/config/updates", "Updates", "system", 5, "更新", "updates"),
        page("/config/repairs", "Repairs", "system", 5, "修复", "repairs"),
        page("/config/logs", "Logs", "system", 5, "日志", "logs"),
        page("/config/backup", "Backup", "system", 6, "备份", "backup"),
        page("/config/backup/backups", "Backup list", "system", 3, "备份列表"),
        page("/config/backup/settings", "Backup settings", "system", 3, "备份设置"),
        page("/config/network", "Network", "system", 5, "网络", "network"),
        page("/config/storage", "Storage", "system", 4, "存储", "storage"),
        page("/config/hardware", "Hardware", "system", 4, "硬件", "hardware"),
        page("/config/analytics", "Analytics", "system", 3, "分析", "analytics"),
        page("/config/ai", "AI", "system", 5, "人工智能", "ai 设置", "ai settings"),
        page("/config/labs", "Labs", "system", 4, "实验室", "labs"),
        page("/config/entity-id-format", "Entity ID format", "system", 3, "实体 id", "entity-id-format"),
        page("/config/energy", "Energy config", "system", 3, "能源配置", "energy config"),
        page("/config/application_credentials", "Application credentials", "devices", 3, "应用凭证"),
        page("/config/matter", "Matter", "settings", 4, "matter"),
        page("/config/zha", "Zigbee", "settings", 4, "zigbee", "zha"),
        page("/config/zwave_js", "Z-Wave", "settings", 4, "z-wave", "zwave"),
        page("/config/mqtt", "MQTT", "settings", 3, "mqtt"),
        page("/config/thread", "Thread", "settings", 3, "thread"),
        page("/config/bluetooth", "Bluetooth", "settings", 4, "蓝牙", "bluetooth"),
        page("/config/infrared", "Infrared", "settings", 3, "红外", "infrared"),
        page("/config/radio-frequency", "Radio frequency", "settings", 3, "射频", "radio-frequency"),
        page("/config/serial", "USB / serial", "settings", 2, "串口", "serial"),
        page("/config/dhcp", "DHCP discovery", "system", 2, "dhcp"),
        page("/config/ssdp", "SSDP discovery", "system", 2, "ssdp"),
        page("/config/zeroconf", "Zeroconf", "system", 2, "zeroconf"),
        page("/config/tools/state", "States", "tools", 5, "状态页", "developer states"),
        page("/config/tools/action", "Actions", "tools", 5, "服务调用", "developer services", "perform action"),
        page("/config/tools/template", "Template", "tools", 5, "模板", "template"),
        page("/config/tools/event", "Events", "tools", 4, "事件", "events"),
        page("/config/tools/statistics", "Statistics", "tools", 3, "统计", "statistics"),
        page("/config/tools/yaml", "YAML", "tools", 4, "yaml"),
        page("/config/tools/assist", "Assist debug", "tools", 3, "assist 调试"),
        page("/config/tools/debug", "Debug", "tools", 3, "调试", "debug"),
        page("/profile", "Profile / theme", "profile", 20, "换个主题", "换主题", "改主题", "主题", "换肤", "外观", "theme", "appearance", "深色模式", "浅色模式", "dark mode", "个人资料", "profile"),
        page("/profile/preferences", "Preferences", "profile", 4, "偏好", "preferences"),
        page("/profile/localization", "Localization", "profile", 5, "语言", "本地化", "localization", "language"),
        page("/profile/browser", "Browser settings", "profile", 4, "浏览器设置", "browser settings"),
        page("/profile/security", "Security", "profile", 4, "安全", "profile security"),
    )

    private val ALIAS = mapOf(
        "settings" to "/config/dashboard",
        "setting" to "/config/dashboard",
        "config" to "/config/dashboard",
        "设置" to "/config/dashboard",
        "设定" to "/config/dashboard",
        "theme" to "/profile",
        "themes" to "/profile",
        "appearance" to "/profile",
        "主题" to "/profile",
        "换肤" to "/profile",
        "外观" to "/profile",
        "profile" to "/profile",
        "automations" to "/config/automation/dashboard",
        "automation" to "/config/automation/dashboard",
        "自动化" to "/config/automation/dashboard",
        "scripts" to "/config/script/dashboard",
        "脚本" to "/config/script/dashboard",
        "scenes" to "/config/scene/dashboard",
        "场景" to "/config/scene/dashboard",
        "devices" to "/config/devices/dashboard",
        "entities" to "/config/entities",
        "实体" to "/config/entities",
        "helpers" to "/config/helpers",
        "history" to "/history",
        "logbook" to "/logbook",
        "energy" to "/energy",
        "map" to "/map",
        "developer-tools" to "/config/tools",
        "developer_tools" to "/config/tools",
        "开发者工具" to "/config/tools",
    )

    private val REMAP = mapOf(
        "/config" to "/config/dashboard",
        "/config/" to "/config/dashboard",
        "/config/theme" to "/profile",
        "/config/themes" to "/profile",
        "/config/appearance" to "/profile",
        "/config/automation" to "/config/automation/dashboard",
        "/config/script" to "/config/script/dashboard",
        "/config/scene" to "/config/scene/dashboard",
        "/config/blueprint" to "/config/blueprint/dashboard",
        "/config/areas" to "/config/areas/dashboard",
        "/config/devices" to "/config/devices/dashboard",
        "/config/integrations/dashboard/add" to "/config/integrations/dashboard",
        "/developer-tools" to "/config/tools",
        "/developer-tools/state" to "/config/tools/state",
        "/developer-tools/states" to "/config/tools/state",
        "/developer-tools/service" to "/config/tools/action",
        "/developer-tools/services" to "/config/tools/action",
        "/developer-tools/template" to "/config/tools/template",
        "/developer-tools/event" to "/config/tools/event",
        "/developer-tools/events" to "/config/tools/event",
        "/developer-tools/statistics" to "/config/tools/statistics",
        "/developer-tools/yaml" to "/config/tools/yaml",
        "/hassio" to "/config/apps",
        "/hassio/dashboard" to "/config/apps",
        "/hassio/store" to "/config/apps/available",
        "/config/customize" to "/config/dashboard",
        "/lights" to "/light",
        "/profile/dashboard" to "/profile",
        "/lovelace-ui" to "/config/lovelace/dashboards",
    )

    fun resolve(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        ALIAS[t.lowercase()]?.let { return it }
        val path = t.substringBefore('#').ifBlank { return null }
        REMAP[path]?.let { return it }
        REMAP[path.trimEnd('/')]?.let { return it }
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://")) return null
        return path
    }

    fun match(utterance: String, limit: Int = 3): List<Page> {
        val q = utterance.trim()
        if (q.length < 2) return emptyList()
        val hits = ArrayList<Pair<Page, Int>>()
        for (page in ALL) {
            var best = 0
            for (key in page.keys) {
                if (key.length < 2) continue
                if (isGenericSettingsKey(key) && !AvaSettingsPoints.isBareSettings(q)) continue
                if (q.contains(key, ignoreCase = true)) {
                    best = maxOf(best, key.length * 10 + page.weight)
                }
            }
            if (best > 0) hits.add(page to best)
        }
        return hits.sortedWith(compareByDescending<Pair<Page, Int>> { it.second }.thenBy { it.first.path })
            .map { it.first }
            .distinctBy { it.path }
            .take(limit)
    }

    fun attach(body: JSONObject, utterance: String, here: String? = null): Boolean {
        val found = match(utterance)
        if (found.isEmpty()) return false
        val goto = JSONArray()
        for (page in found) {
            if (page.path == here) continue
            goto.put(row(page))
        }
        if (goto.length() == 0) return false
        body.put("goto", goto)
        val group = found.first().group
        if (group.isNotEmpty()) {
            val pages = JSONArray()
            for (page in ALL) {
                if (page.group != group) continue
                if (pages.length() >= 12) break
                pages.put(row(page))
            }
            if (pages.length() > 0) body.put("pages", pages)
        }
        return true
    }

    fun nextNavigate(body: JSONObject): JSONObject? {
        val path = body.optJSONArray("goto")?.optJSONObject(0)?.optString("path").orEmpty()
        if (path.isEmpty()) return null
        return JSONObject()
            .put("tool", AvaPageTools.ACT)
            .put("arguments", JSONObject().put("action", "navigate").put("path", path))
    }

    private fun isGenericSettingsKey(key: String): Boolean {
        val k = key.lowercase()
        return k == "设置" || k == "设定" || k == "settings"
    }

    private fun row(page: Page): JSONObject =
        JSONObject().put("path", page.path).put("name", page.name)

    private fun page(
        path: String,
        name: String,
        group: String,
        weight: Int,
        vararg keys: String,
    ): Page = Page(path, name, keys, weight, group)
}
