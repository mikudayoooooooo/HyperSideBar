package com.lsp.hypersidebar.ui.settings

import androidx.navigation3.runtime.NavKey
import com.lsp.hypersidebar.util.ShortcutAction

/**
 * nav3 导航键（U0 定案：全量迁移）。
 * 设置/关于双根 Tab 各持独立 NavBackStack；详情页（AppSelection/快捷方式/布局/交互）
 * 与快捷方式内部三级（列表→编辑→选择器，U0 ③ 验证模式）全部折叠进所在 Tab 的栈。
 */
internal sealed interface SettingsKey : NavKey {
    data object TabSettings : SettingsKey
    data object TabAbout : SettingsKey
    data class AppSelection(val prefsKey: String, val title: String) : SettingsKey
    data object ShortcutList : SettingsKey

    /**
     * 编辑中的快捷方式数据随键携带。选择器回填 = 原地替换栈中该键（contentKey 随数据变），
     * 编辑页重新组合并从更新后的 shortcut 重建字段状态——等价旧状态机"选择器期间编辑页
     * 被 when() 销毁、返回时从 editingShortcut 重建"的语义。
     */
    data class ShortcutEdit(val shortcut: ShortcutAction, val isNew: Boolean) : SettingsKey
    data object ShortcutPicker : SettingsKey
    data object LayoutSettings : SettingsKey
    data object InteractionSettings : SettingsKey
}
