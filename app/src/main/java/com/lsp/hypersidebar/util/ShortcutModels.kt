package com.lsp.hypersidebar.util

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import com.lsp.hypersidebar.util.HLog

private const val TAG = "ShortcutModels"

/**
 * 快捷方式类型：
 * - COMPONENT: 统一组件（自动探测 Activity/Service，推荐新建时使用）
 * - ACTIVITY: 显式 Activity（向后兼容旧数据）
 * - SERVICE: 显式 Service（向后兼容旧数据）
 * - INTENT_URI: Intent URI (deep link / action / data)
 * - TOOLBOX: 内置视频/游戏面板快捷项（不占用户名额）
 * - QS_TILE: 快捷开关磁贴（TileService 类，SystemUI hook 数据层直点触发——无须 root、
 *   无须固定在控制中心，2026-09-05 定稿；hook 缺席时自动回退 root 兜底）
 * - SHORTCUT_ID: 动态/固定快捷方式（微信扫一扫等 runtime 推送项，2026-09-08 定稿）——
 *   仅默认桌面可 startShortcut，经 launcher 进程桥代发（B2 归档结论因桌面桥的存在过时）
 * - GROUP: 快捷方式组（类文件夹，2~3 子项，悬停展开；计划 §B0/B1，2026-10-25）——
 *   组占一个上栏位置，子项不进根栏；不可嵌套、工具箱不可入组
 */
enum class ShortcutKind {
    COMPONENT,
    ACTIVITY,
    INTENT_URI,
    TOOLBOX,
    SERVICE,
    QS_TILE,
    SHORTCUT_ID,
    GROUP
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
 * GROUP 条目的 children 仅一层（归一化强制，见 [ShortcutGroups]）；旧版本模块读到
 * kind="GROUP" 会经 valueOf 失败回落 ACTIVITY 空条目（计划 §B1 明文降级副作用）。
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
    val shortcutId: String? = null,
    val order: Int = 0,
    val children: List<ShortcutAction>? = null
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
        putOpt("shortcutId", shortcutId)
        put("order", order)
        children?.takeIf { it.isNotEmpty() }?.let { kids ->
            putOpt("children", JSONArray().apply { kids.forEach { put(it.toJson()) } })
        }
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
            shortcutId = json.optString("shortcutId", "").ifEmpty { null },
            order = json.optInt("order", 0),
            children = runCatching {
                json.optJSONArray("children")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
                    }.takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
        )
    }
}

/**
 * 快捷方式组纯核心（计划 §B0/B1，2026-10-25）：校验、配额、加载归一化。
 * 所有规则在此单点，UI（C8）与扇形装配（C7）只消费结果。
 */
object ShortcutGroups {

    /** 组内子项数量上下限（PRD：类文件夹，可同时装入 3 个快捷方式；至少 2 个成组） */
    const val MIN_CHILDREN = 2
    const val MAX_CHILDREN = 3

    /**
     * 建组校验：数量 2~3、子项 kind 合法（非 GROUP 不可嵌套、非 TOOLBOX 不可入组）、
     * id 非空且不重复。UI 建组前调用；扇形装配不再校验（加载时已归一化）。
     */
    fun validateChildren(children: List<ShortcutAction>): Boolean {
        if (children.size < MIN_CHILDREN || children.size > MAX_CHILDREN) return false
        val ids = HashSet<String>()
        for (c in children) {
            if (c.kind == ShortcutKind.GROUP || c.kind == ShortcutKind.TOOLBOX) return false
            if (c.id.isBlank() || !ids.add(c.id)) return false
        }
        return true
    }

    /**
     * 存储配额口径（计划 §B1）：组占一个上栏位置，但子项仍计入
     * [ShortcutStore.MAX_STORED_SHORTCUTS]（=25：5 满组+5 单 / 6 满组+1 候补恰好用满）。
     */
    fun storageUnits(list: List<ShortcutAction>): Int =
        list.sumOf { 1 + (if (it.kind == ShortcutKind.GROUP) it.children?.size ?: 0 else 0) }

    /**
     * 加载时归一化（不靠 UI 兜，计划 §B1；2026-10-25 布局定稿修订）：
     * - 组缺 children / <2 子 ⇒ **解散**（子项提升回顶层普通条目，保持原相对顺序）
     * - 子项里嵌套 GROUP / TOOLBOX / 重复 id / 空 id ⇒ 剥除该子项（其余保留）
     * - 剥除后不足 MIN_CHILDREN 的组同样解散
     * - **全部成员 disabled 不再解散**（布局文档 §5.3：该状态合法——隐藏整组走组开关；
     *   扇形端由 [enabledChildren] 空即隐藏承接，见 FanMenuController 装配）
     * 顶层非组条目原样透传。
     */
    fun normalize(list: List<ShortcutAction>): List<ShortcutAction> {
        val out = ArrayList<ShortcutAction>(list.size)
        for (item in list) {
            if (item.kind != ShortcutKind.GROUP) {
                out.add(item)
                continue
            }
            val cleaned = item.children.orEmpty()
                .filter { it.kind != ShortcutKind.GROUP && it.kind != ShortcutKind.TOOLBOX && it.id.isNotBlank() }
                .distinctBy { it.id }
            if (cleaned.size < MIN_CHILDREN) {
                // 解散：子项提升为顶层
                cleaned.forEach { out.add(it.copy(source = ShortcutSource.USER)) }
            } else {
                out.add(item.copy(children = cleaned))
            }
        }
        return out
    }

    /**
     * 组的**可展示**成员（enabled 过滤；非组返回 null）。扇形装配/组详情展开共用：
     * 空列表=空成员组——扇形端隐藏（2026-10-25 用户拍板），组详情端由 UI 层提示。
     */
    fun enabledChildren(group: ShortcutAction): List<ShortcutAction>? =
        if (group.kind != ShortcutKind.GROUP) null
        else group.children.orEmpty().filter { it.enabled }.takeIf { it.isNotEmpty() }
}

/**
 * 快捷方式持久化管理。
 * 使用 JSON 数组存储在 SharedPreferences 的单个 key 中。
 * 存储不设上限（§2.4 解耦拍板）：扇形只展示 enabled 且按 order 排序的前
 * MAX_USER_SHORTCUTS 个（占位在场少 1），用户通过启用开关与排序控制上栏项。
 */
object ShortcutStore {

    private val KEY = com.lsp.hypersidebar.prefs.PrefKeys.SHORTCUT_ACTIONS
    private const val TOOLBOX_PACKAGE = "com.miui.securitycenter"
    private const val TOOLBOX_ID = "__toolbox__"

    /** 游戏箱/视频箱 boost 状态键（MIUI 私有 Settings.Secure；两函数四处读取，收口于此） */
    private const val KEY_GB_BOOSTING = "gb_boosting"
    private const val KEY_VTB_BOOSTING = "vtb_boosting"

    /**
     * 扇形快捷栏展示上限（PRD §7.1：6 个**含面板占位**——占位在场时运行时
     * 只取前 5 个用户项，占位隐藏时 6 个全上）。仅约束展示，不约束存储。
     */
    const val MAX_USER_SHORTCUTS = 6

    /**
     * 设置页启用上限（布局定稿 2026-10-25：取面板在场时的最坏容量=5，不随 gb/vtb
     * 动态变）：已启用顶层项达到 5 后禁止再启用（toast 原因），杜绝"候补不显示"态；
     * 存量超额数据不强制禁用，由首页顶部提醒卡兜底。扇形运行时截断逻辑不变。
     */
    const val ENABLED_CAP = MAX_USER_SHORTCUTS - 1

    /**
     * 存储上限（单位=storageUnits：组占 1+子项数、单项占 1）。§2.4 原定 10；
     * 2026-10-25 用户放宽为 25——组的占位算术（1+3）把 10 位挤得太紧，25 恰好
     * 覆盖「5 满组+5 单项」与「6 满组+1 候补」（6=上栏槽位上限）两种满配。
     * 仅约束存储池；上栏展示仍由 [MAX_USER_SHORTCUTS]=6 收口。
     */
    const val MAX_STORED_SHORTCUTS = 25

    /**
     * 从 SharedPreferences 读取所有用户快捷方式（按 order 排序）。
     * 不包含 TOOLBOX 内置项。
     */
    fun loadUserShortcuts(prefs: SharedPreferences): List<ShortcutAction> {
        val jsonStr = try {
            prefs.getString(KEY, null)
        } catch (e: Exception) {
            HLog.w(TAG, "loadUserShortcuts: read failed", e)
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
            // 组归一化（§B1）：解散不合法组/剥非法子项——排序后做，解散子项保持在原组位置附近
            ShortcutGroups.normalize(list.sortedBy { it.order })
        } catch (e: Exception) {
            HLog.e(TAG, "loadUserShortcuts: parse failed", e)
            emptyList()
        }
    }

    /**
     * 保存用户快捷方式列表到 SharedPreferences。
     * 存储不截断（§2.4 解耦）；order 按当前列表序整体重排。
     */
    fun saveUserShortcuts(prefs: SharedPreferences, shortcuts: List<ShortcutAction>) {
        // 防覆盖闸（2026-10-19 数据事故）：冷启动时桥未绑定，传入的 prefs 可能是本地
        // 回退（APP_LOCAL，重装后为空）——用它保存=把 remotePrefs 里的完整列表整表覆盖
        // （真机事故：6 条快捷方式被 1 条覆盖）。平台 SharedPreferencesImpl = 本地回退的
        // 实现类；remotePrefs 是 LSPosed 的远端实现（类名不同）。本地回退上只允许"没有
        // 可保存内容"的空操作，真实保存必须落在远端。
        if (prefs.javaClass.name == "android.app.SharedPreferencesImpl") {
            HLog.w(TAG, "saveUserShortcuts REFUSED: 桥未绑定（本地回退不可作保存目标，" +
                "防止覆盖 remotePrefs 完整列表）")
            return
        }
        val userItems = shortcuts
            .filter { it.source == ShortcutSource.USER }
            .mapIndexed { index, item -> item.copy(order = index) }

        val arr = JSONArray()
        userItems.forEach { arr.put(it.toJson()) }

        try {
            prefs.edit().putString(KEY, arr.toString()).apply()
            // 快捷方式列表是 hook 侧直接读的键，写完显式推一次配置同步（见 ConfigSync.notifyConfigChanged）
            com.lsp.hypersidebar.util.ConfigSync.notifyConfigChanged(KEY)
            // 诊断锚点：快捷栏缺失问题时区分"保存没落盘"（无此行/条目缺）vs"扇形没读到"
            HLog.i(TAG, "saved ${userItems.size} shortcuts: " +
                userItems.joinToString { "${it.kind}:${it.label}(${if (it.enabled) "on" else "off"})" })
        } catch (e: Exception) {
            HLog.e(TAG, "saveUserShortcuts: write failed", e)
        }
    }

    /**
     * 添加一个用户快捷方式（存储上限按 storageUnits 计——组占 1+子项数，与
     * GroupEditPage/设置页同口径，2026-10-25 统一）。
     * NOTE: Must be called from main thread (not thread-safe).
     */
    fun addShortcut(prefs: SharedPreferences, shortcut: ShortcutAction): Boolean {
        val current = loadUserShortcuts(prefs).toMutableList()
        // 组经 GroupEditPage 建（占位算术在彼处校验）；此处防御：极端情况下
        // 直接 add 组也按 storageUnits 计
        if (ShortcutGroups.storageUnits(current) >= MAX_STORED_SHORTCUTS) return false
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
     * 构建运行时快捷栏完整列表（PRD §7.1：面板占位固定第一位，无面板时用户项前移）：
     * 条件性 TOOLBOX 内置项（第一位）+ 用户启用的快捷方式。
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

        // 条件性内置 TOOLBOX 项（第一位）
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

        // 用户启用的快捷方式：栏上限 6 含占位（PRD §7.1）——占位在场取 5 个，隐藏时 6 个全上
        val maxUser = if (toolboxAvailable) 5 else 6
        val userEnabled = loadUserShortcuts(prefs).filter { it.enabled }
        val picked = userEnabled.take(maxUser)
        // 诊断锚点：与 saveUserShortcuts 的 "saved N" 配对——读端缓存陈旧时
        // enabled 数与最近一次 saved 数不一致
        HLog.i(TAG, "runtimeQuick: user=${userEnabled.size} out=${picked.size} " +
            "kinds=${picked.joinToString { it.kind.name }}")
        picked.forEach { result.add(it) }

        return result
    }

    /**
     * 判断当前场景是否有可用的面板（游戏/视频加速模式）。
     */
    fun isToolboxAvailable(context: android.content.Context): Boolean {
        val cr = context.contentResolver
        return android.provider.Settings.Secure.getInt(cr, KEY_GB_BOOSTING, 0) == 1 ||
            android.provider.Settings.Secure.getInt(cr, KEY_VTB_BOOSTING, 0) == 1
    }

    /**
     * 获取面板标签。
     */
    fun getToolboxLabel(context: android.content.Context): String {
        val cr = context.contentResolver
        return when {
            android.provider.Settings.Secure.getInt(cr, KEY_GB_BOOSTING, 0) == 1 -> "游戏工具箱"
            android.provider.Settings.Secure.getInt(cr, KEY_VTB_BOOSTING, 0) == 1 -> "视频工具箱"
            else -> "打开面板"
        }
    }
}
