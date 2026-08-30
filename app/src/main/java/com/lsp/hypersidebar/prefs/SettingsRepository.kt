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

    fun enabled(): Boolean = prefs.getBoolean(PrefKeys.ENABLED, true)

    fun themeMode(): String =
        prefs.getString(PrefKeys.THEME_MODE, "MONET_SYSTEM") ?: "MONET_SYSTEM"

    fun iconSize(): Float = prefs.getFloat(PrefKeys.ICON_SIZE, LayoutDefaults.ICON_SIZE)
    fun innerRadius(): Float = prefs.getFloat(PrefKeys.INNER_RADIUS, LayoutDefaults.INNER_RADIUS)
    fun outerRadiusMax(): Float = prefs.getFloat(PrefKeys.OUTER_RADIUS_MAX, LayoutDefaults.OUTER_RADIUS_MAX)
    fun maxAppsOuter(): Int = prefs.getInt(PrefKeys.MAX_APPS_OUTER, LayoutDefaults.MAX_APPS_OUTER)
    fun maxAppsInner(): Int = prefs.getInt(PrefKeys.MAX_APPS_INNER, LayoutDefaults.MAX_APPS_INNER)

    fun landscapeIconSize(): Float = prefs.getFloat(PrefKeys.LANDSCAPE_ICON_SIZE, LayoutDefaults.LANDSCAPE_ICON_SIZE)
    fun landscapeMaxAppsOuter(): Int = prefs.getInt(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER)
    fun landscapeMaxAppsInner(): Int = prefs.getInt(PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER)
    fun landscapeInnerRadius(): Float = prefs.getFloat(PrefKeys.LANDSCAPE_INNER_RADIUS, LayoutDefaults.LANDSCAPE_INNER_RADIUS)
    fun landscapeOuterRadius(): Float = prefs.getFloat(PrefKeys.LANDSCAPE_OUTER_RADIUS, LayoutDefaults.LANDSCAPE_OUTER_RADIUS)

    fun deadZone(): Float = prefs.getFloat(PrefKeys.DEAD_ZONE, LayoutDefaults.DEAD_ZONE)
    fun vibrate(): Boolean = prefs.getBoolean(PrefKeys.VIBRATE, LayoutDefaults.VIBRATE)
    fun triggerDwellMs(): Int = prefs.getInt(PrefKeys.TRIGGER_DWELL_MS, LayoutDefaults.TRIGGER_DWELL_MS)
    fun triggerMinDistanceDp(): Float =
        prefs.getFloat(PrefKeys.TRIGGER_MIN_DISTANCE, LayoutDefaults.TRIGGER_MIN_DISTANCE_DP)

    fun customApps(): Set<String> = prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty()

    // ===== 写 =====

    fun save(key: String, value: Any) = prefs.savePref(key, value)

    fun restoreLayoutDefaults() {
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
