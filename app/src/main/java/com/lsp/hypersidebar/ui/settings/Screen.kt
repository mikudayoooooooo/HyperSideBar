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

    /** C3（批次 3）：QS 磁贴专属选择器（平铺全部扫到的 TileService，编辑页磁贴分支进入） */
    data object QsTilePicker : SettingsKey

    /** 迭代六 §11.2：运行日志页（三进程 HLog 缓冲汇聚，诊断与统计页进入） */
    data object Logs : SettingsKey

    /** 迭代六 §11.3：使用统计页（§9.2 指标 + §9.3 派生口径，诊断与统计页进入） */
    data object Stats : SettingsKey

    /** 诊断与统计汇聚页（0909 用户拍板：诊断类功能收进关于页调试区，加一层父入口） */
    data object Diagnostics : SettingsKey

    /** 呼出设置二级页（0913 用户拍板）：滑动距离 + 呼出停顿，首页交互区入口进入 */
    data object InvokeSettings : SettingsKey

    /** 扇形背景二级页（0913 用户拍板）：雾化 + 压暗，首页交互区入口进入 */
    data object FanBackground : SettingsKey
}
