package com.lsp.hypersidebar.util

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ShortcutModels"

/**
 * 快捷方式类型：
 * - COMPONENT: 统一组件（自动探测 Activity/Service，推荐新建时使用）
 * - ACTIVITY: 显式 Activity（向后兼容旧数据）
 * - SERVICE: 显式 Service（向后兼容旧数据）
 * - INTENT_URI: Intent URI (deep link / action / data)
 * - TOOLBOX: 内置视频/游戏面板快捷项（不占用户名额）
 */
enum class ShortcutKind {
    COMPONENT,
    ACTIVITY,
    INTENT_URI,
    TOOLBOX,
    SERVICE
}

/**
 * 快捷方式来源：
 * - USER: 用户自定义
 * - BUILTIN: 系统内置（如 TOOLBOX）
 */
enum class ShortcutSource {
    USER,
    BUILTIN
}

/**
 * 统一快捷方式数据模型。
 * 持久化时只存结构化字段 / 原始 intentUri 文本，不持久化 Intent 对象。
 */
data class ShortcutAction(
    val id: String,
    val kind: ShortcutKind,
    val source: ShortcutSource = ShortcutSource.USER,
    val label: String,
    val enabled: Boolean = true,
    val packageName: String? = null,
    val activityName: String? = null,
    val serviceName: String? = null,
    val intentUri: String? = null,
    val iconPackageName: String? = null,
    val order: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.name)
        put("source", source.name)
        put("label", label)
        put("enabled", enabled)
        putOpt("packageName", packageName)
        putOpt("activityName", activityName)
        putOpt("serviceName", serviceName)
        putOpt("intentUri", intentUri)
        putOpt("iconPackageName", iconPackageName)
        put("order", order)
    }

    companion object {
        fun fromJson(json: JSONObject): ShortcutAction = ShortcutAction(
            id = json.optString("id", ""),
            kind = runCatching { ShortcutKind.valueOf(json.optString("kind", "ACTIVITY")) }
                .getOrDefault(ShortcutKind.ACTIVITY),
            source = runCatching { ShortcutSource.valueOf(json.optString("source", "USER")) }
                .getOrDefault(ShortcutSource.USER),
            label = json.optString("label", ""),
            enabled = json.optBoolean("enabled", true),
            packageName = json.optString("packageName", "").ifEmpty { null },
            activityName = json.optString("activityName", "").ifEmpty { null },
            serviceName = json.optString("serviceName", "").ifEmpty { null },
            intentUri = json.optString("intentUri", "").ifEmpty { null },
            iconPackageName = json.optString("iconPackageName", "").ifEmpty { null },
            order = json.optInt("order", 0)
        )
    }
}

/**
 * 快捷方式持久化管理。
 * 使用 JSON 数组存储在 SharedPreferences 的单个 key 中。
 * 用户自定义项最多 5 个，TOOLBOX 内置项不计入配额。
 */
object ShortcutStore {

    private const val KEY = "shortcut_actions"
    private const val TOOLBOX_PACKAGE = "com.miui.securitycenter"
    private const val TOOLBOX_ID = "__toolbox__"
    const val MAX_USER_SHORTCUTS = 5

    /**
     * 从 SharedPreferences 读取所有用户快捷方式（按 order 排序）。
     * 不包含 TOOLBOX 内置项。
     */
    fun loadUserShortcuts(prefs: SharedPreferences): List<ShortcutAction> {
        val jsonStr = try {
            prefs.getString(KEY, null)
        } catch (e: Exception) {
            Log.w(TAG, "loadUserShortcuts: read failed", e)
            null
        } ?: return emptyList()

        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<ShortcutAction>()
            for (i in 0 until arr.length()) {
                val item = ShortcutAction.fromJson(arr.getJSONObject(i))
                if (item.source == ShortcutSource.USER) {
                    list.add(item)
                }
            }
            list.sortedBy { it.order }
        } catch (e: Exception) {
            Log.e(TAG, "loadUserShortcuts: parse failed", e)
            emptyList()
        }
    }

    /**
     * 保存用户快捷方式列表到 SharedPreferences。
     * 自动截断到 MAX_USER_SHORTCUTS 个。
     */
    fun saveUserShortcuts(prefs: SharedPreferences, shortcuts: List<ShortcutAction>) {
        val userItems = shortcuts
            .filter { it.source == ShortcutSource.USER }
            .take(MAX_USER_SHORTCUTS)
            .mapIndexed { index, item -> item.copy(order = index) }

        val arr = JSONArray()
        userItems.forEach { arr.put(it.toJson()) }

        try {
            prefs.edit().putString(KEY, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveUserShortcuts: write failed", e)
        }
    }

    /**
     * 添加一个用户快捷方式。如果已达上限则返回 false。
     * NOTE: Must be called from main thread (not thread-safe).
     */
    fun addShortcut(prefs: SharedPreferences, shortcut: ShortcutAction): Boolean {
        val current = loadUserShortcuts(prefs).toMutableList()
        if (current.size >= MAX_USER_SHORTCUTS) return false
        val newItem = shortcut.copy(
            source = ShortcutSource.USER,
            order = current.size
        )
        current.add(newItem)
        saveUserShortcuts(prefs, current)
        return true
    }

    /**
     * 更新指定 id 的快捷方式。Returns true if found and updated.
     * NOTE: Must be called from main thread (not thread-safe).
     */
    fun updateShortcut(prefs: SharedPreferences, updated: ShortcutAction): Boolean {
        val current = loadUserShortcuts(prefs).toMutableList()
        val idx = current.indexOfFirst { it.id == updated.id }
        return if (idx >= 0) {
            current[idx] = updated
            saveUserShortcuts(prefs, current)
            true
        } else {
            false
        }
    }

    /**
     * 删除指定 id 的快捷方式。Returns true if found and removed.
     * NOTE: Must be called from main thread (not thread-safe).
     */
    fun removeShortcut(prefs: SharedPreferences, id: String): Boolean {
        val current = loadUserShortcuts(prefs)
        val newList = current.filter { it.id != id }
        return if (newList.size < current.size) {
            saveUserShortcuts(prefs, newList)
            true
        } else {
            false
        }
    }

    /**
     * 移动排序：将 fromIndex 处的项移到 toIndex。
     */
    fun moveShortcut(prefs: SharedPreferences, fromIndex: Int, toIndex: Int): Boolean {
        val current = loadUserShortcuts(prefs).toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return false
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        saveUserShortcuts(prefs, current)
        return true
    }

    /**
     * 构建运行时快捷栏完整列表：
     * 用户启用的快捷方式 + 条件性 TOOLBOX 内置项。
     *
     * @param toolboxAvailable 当前场景是否支持视频/游戏面板
     * @param toolboxLabel 面板显示名称（游戏工具箱/视频工具箱/打开面板）
     */
    fun buildRuntimeQuickList(
        prefs: SharedPreferences,
        toolboxAvailable: Boolean,
        toolboxLabel: String
    ): List<ShortcutAction> {
        val result = mutableListOf<ShortcutAction>()

        // 用户启用的快捷方式
        loadUserShortcuts(prefs)
            .filter { it.enabled }
            .forEach { result.add(it) }

        // 条件性内置 TOOLBOX 项
        if (toolboxAvailable) {
            result.add(ShortcutAction(
                id = TOOLBOX_ID,
                kind = ShortcutKind.TOOLBOX,
                source = ShortcutSource.BUILTIN,
                label = toolboxLabel,
                enabled = true,
                packageName = TOOLBOX_PACKAGE
            ))
        }

        return result
    }

    /**
     * 判断当前场景是否有可用的面板（游戏/视频加速模式）。
     */
    fun isToolboxAvailable(context: android.content.Context): Boolean {
        val cr = context.contentResolver
        return android.provider.Settings.Secure.getInt(cr, "gb_boosting", 0) == 1 ||
            android.provider.Settings.Secure.getInt(cr, "vtb_boosting", 0) == 1
    }

    /**
     * 获取面板标签。
     */
    fun getToolboxLabel(context: android.content.Context): String {
        val cr = context.contentResolver
        return when {
            android.provider.Settings.Secure.getInt(cr, "gb_boosting", 0) == 1 -> "游戏工具箱"
            android.provider.Settings.Secure.getInt(cr, "vtb_boosting", 0) == 1 -> "视频工具箱"
            else -> "打开面板"
        }
    }
}
