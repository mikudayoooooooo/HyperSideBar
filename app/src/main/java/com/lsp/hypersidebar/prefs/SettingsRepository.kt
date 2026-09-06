package com.lsp.hypersidebar.prefs

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 设置 App 侧的单一数据源：typed 读写 + revision 失效通道。
 *
 * RemotePreferences 没有入站推送，"外部写入 → UI 失效重读"只能靠本地写回触发——
 * revision 是全 App 唯一的响应式桥（替代各页自管失效计数）。
 * 页面迁移策略：Phase 1 仅 MainScreen 接入；各页逐个迁移到 typed getter 在 Phase 7 完成。
 *
 * prefs 实例可能切换（remote 绑定前后），调用方用 remember(prefs) { SettingsRepository(prefs) }
 * 管理生命周期，并在 onDispose 调用 [dispose]。
 */
class SettingsRepository(val prefs: SharedPreferences) {

    var revision by mutableIntStateOf(0)
        private set

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        revision++
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun dispose() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    // ===== typed getters（默认值一律取自 LayoutDefaults / 契约） =====
    // 布局 10 键走草稿优先读法：BottomSheet 编辑期只改草稿（实时预览跟随），
    // 取消=discard 整体丢弃、保存=commit 批量落盘；非编辑期草稿为空=直读落盘值。

    fun enabled(): Boolean = prefs.getBoolean(PrefKeys.ENABLED, true)

    fun themeMode(): String =
        prefs.getString(PrefKeys.THEME_MODE, "MONET_SYSTEM") ?: "MONET_SYSTEM"

    fun iconSize(): Float = getDraft(PrefKeys.ICON_SIZE) {
        prefs.getFloat(PrefKeys.ICON_SIZE, LayoutDefaults.ICON_SIZE)
    }
    fun innerRadius(): Float = getDraft(PrefKeys.INNER_RADIUS) {
        prefs.getFloat(PrefKeys.INNER_RADIUS, LayoutDefaults.INNER_RADIUS)
    }
    fun outerRadiusMax(): Float = getDraft(PrefKeys.OUTER_RADIUS_MAX) {
        prefs.getFloat(PrefKeys.OUTER_RADIUS_MAX, LayoutDefaults.OUTER_RADIUS_MAX)
    }
    fun maxAppsOuter(): Int = getDraft(PrefKeys.MAX_APPS_OUTER) {
        prefs.getInt(PrefKeys.MAX_APPS_OUTER, LayoutDefaults.MAX_APPS_OUTER)
    }
    fun maxAppsInner(): Int = getDraft(PrefKeys.MAX_APPS_INNER) {
        prefs.getInt(PrefKeys.MAX_APPS_INNER, LayoutDefaults.MAX_APPS_INNER)
    }

    fun landscapeIconSize(): Float = getDraft(PrefKeys.LANDSCAPE_ICON_SIZE) {
        prefs.getFloat(PrefKeys.LANDSCAPE_ICON_SIZE, LayoutDefaults.LANDSCAPE_ICON_SIZE)
    }
    fun landscapeMaxAppsOuter(): Int = getDraft(PrefKeys.LANDSCAPE_MAX_APPS_OUTER) {
        prefs.getInt(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER)
    }
    fun landscapeMaxAppsInner(): Int = getDraft(PrefKeys.LANDSCAPE_MAX_APPS_INNER) {
        prefs.getInt(PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER)
    }
    fun landscapeInnerRadius(): Float = getDraft(PrefKeys.LANDSCAPE_INNER_RADIUS) {
        prefs.getFloat(PrefKeys.LANDSCAPE_INNER_RADIUS, LayoutDefaults.LANDSCAPE_INNER_RADIUS)
    }
    fun landscapeOuterRadius(): Float = getDraft(PrefKeys.LANDSCAPE_OUTER_RADIUS) {
        prefs.getFloat(PrefKeys.LANDSCAPE_OUTER_RADIUS, LayoutDefaults.LANDSCAPE_OUTER_RADIUS)
    }

    fun deadZone(): Float = prefs.getFloat(PrefKeys.DEAD_ZONE, LayoutDefaults.DEAD_ZONE)
    fun triggerDwellMs(): Int = prefs.getInt(PrefKeys.TRIGGER_DWELL_MS, LayoutDefaults.TRIGGER_DWELL_MS)
    fun triggerMinDistanceDp(): Float =
        prefs.getFloat(PrefKeys.TRIGGER_MIN_DISTANCE, LayoutDefaults.TRIGGER_MIN_DISTANCE_DP)

    fun customApps(): Set<String> = prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty()

    // ===== 草稿/提交层（迭代二 §4 地基，供迭代三 OverlayBottomSheet 消费） =====
    //
    // 语义（refactor-plan §四.4）：编辑中只改草稿不落盘（实时预览跟随草稿态），
    // commit = 单 editor 批量落盘（一次 revision 跳变、hook 侧一次 prefs 同步），
    // discard = 整体丢弃。草稿值优先于落盘值（[getDraft] 系列先查草稿）。

    private val draft = LinkedHashMap<String, Any>()

    val hasDraft: Boolean get() = draft.isNotEmpty()

    fun putDraft(key: String, value: Any) {
        draft[key] = value
        revision++ // 草稿变化也走 revision 通道驱动预览重组
    }

    fun <T> getDraft(key: String, fallback: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return (draft[key] as? T) ?: fallback()
    }

    /** 批量落盘草稿并清空（保存）。 */
    fun commitDraft() {
        if (draft.isEmpty()) return
        prefs.edit().apply {
            draft.forEach { (k, v) ->
                when (v) {
                    is Boolean -> putBoolean(k, v)
                    is Float -> putFloat(k, v)
                    is Int -> putInt(k, v)
                    is String -> putString(k, v)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        putStringSet(k, v as Set<String>)
                    }
                }
            }
        }.apply()
        draft.clear()
    }

    /** 丢弃草稿（取消）。 */
    fun discardDraft() {
        if (draft.isNotEmpty()) {
            draft.clear()
            revision++
        }
    }

    // ===== 写 =====

    fun save(key: String, value: Any) = prefs.savePref(key, value)

    /**
     * 一键恢复默认（§2.5.3，PRD"默认值且可重置"）：全部布局（10）+ 交互（3）参数写回默认值。
     * 写盘即经 LSPosed 推送同步 hook 侧，下次呼出生效（无需重启）。不动用户数据（应用/快捷方式）。
     */
    fun restoreAllDefaults() {
        prefs.edit().apply {
            putFloat(PrefKeys.ICON_SIZE, LayoutDefaults.ICON_SIZE)
            putFloat(PrefKeys.INNER_RADIUS, LayoutDefaults.INNER_RADIUS)
            putFloat(PrefKeys.OUTER_RADIUS_MAX, LayoutDefaults.OUTER_RADIUS_MAX)
            putInt(PrefKeys.MAX_APPS_OUTER, LayoutDefaults.MAX_APPS_OUTER)
            putInt(PrefKeys.MAX_APPS_INNER, LayoutDefaults.MAX_APPS_INNER)
            putFloat(PrefKeys.LANDSCAPE_ICON_SIZE, LayoutDefaults.LANDSCAPE_ICON_SIZE)
            putInt(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER)
            putInt(PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER)
            putFloat(PrefKeys.LANDSCAPE_INNER_RADIUS, LayoutDefaults.LANDSCAPE_INNER_RADIUS)
            putFloat(PrefKeys.LANDSCAPE_OUTER_RADIUS, LayoutDefaults.LANDSCAPE_OUTER_RADIUS)
            putFloat(PrefKeys.DEAD_ZONE, LayoutDefaults.DEAD_ZONE)
            putInt(PrefKeys.TRIGGER_DWELL_MS, LayoutDefaults.TRIGGER_DWELL_MS)
            putFloat(PrefKeys.TRIGGER_MIN_DISTANCE, LayoutDefaults.TRIGGER_MIN_DISTANCE_DP)
        }.apply()
    }
}

/** 类型分发写 prefs（App 侧通用写入口）。 */
fun SharedPreferences.savePref(key: String, value: Any) {
    edit().apply {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Float -> putFloat(key, value)
            is Int -> putInt(key, value)
            is String -> putString(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                putStringSet(key, value as Set<String>)
            }
        }
    }.apply()
}
