package com.lsp.hypersidebar.prefs

import android.content.SharedPreferences

/**
 * prefs wire 协议契约：跨进程（设置 App ↔ hook 宿主）的唯一 key 与默认值定义。
 * 两侧读写必须引用本文件，禁止散落字面量；新增 key 先加这里。
 */

object PrefKeys {
    const val ENABLED = "enabled"
    const val THEME_MODE = "themeMode"

    const val ICON_SIZE = "iconSize"
    const val INNER_RADIUS = "innerRadius"
    const val OUTER_RADIUS_MAX = "outerRadiusMax"
    const val MAX_APPS_OUTER = "maxAppsOuter"
    const val MAX_APPS_INNER = "maxAppsInner"

    const val LANDSCAPE_ICON_SIZE = "landscapeIconSize"
    const val LANDSCAPE_MAX_APPS_OUTER = "landscapeMaxAppsOuter"
    const val LANDSCAPE_MAX_APPS_INNER = "landscapeMaxAppsInner"
    const val LANDSCAPE_INNER_RADIUS = "landscapeInnerRadius"
    const val LANDSCAPE_OUTER_RADIUS = "landscapeOuterRadius"

    const val DEAD_ZONE = "deadZone"
    const val TRIGGER_DWELL_MS = "triggerDwellMs"
    const val TRIGGER_MIN_DISTANCE = "triggerMinDistance"

    const val CUSTOM_APPS = "customApps"
    // 已选固定应用的拖动排序（JSON 数组字符串，仅含已选包名；StringSet 不保序，
    // 扇形/面板按此键排序，缺失项排尾部）
    const val CUSTOM_APPS_ORDER = "customAppsOrder"
    const val SHORTCUT_ACTIONS = "shortcut_actions"   // 用户快捷方式 JSON（权威 key）

    /** 穿透失效自动降级状态（1C：:ui 写入，设置页读出标注；非用户设置） */
    const val PASSTHROUGH_DEGRADED = "passthroughDegraded"

    /** 熔断状态（1C 轮二）：按进程分键——一端重启只清自己的键，另一端状态不被误清 */
    const val CIRCUIT_OPEN_HOME = "circuitOpen.home"
    const val CIRCUIT_OPEN_UI = "circuitOpen.ui"

    /** 设置页"手动重试"时间戳：hook 侧比较 resetAt > 本端熔断时刻即解除 */
    const val CIRCUIT_RESET_AT = "circuitResetAt"

    /** 调试开关：模拟 :ui 执行端失联（熔断链路验证用；:ui 自然死亡窗口太短无法实测） */
    const val DEBUG_RELAY_BLACKHOLE = "debugRelayBlackhole"

    // ===== :ui → 模块 App 的快捷方式 root 代发通道（§2.4 实测定案） =====
    // :ui（system uid）对非 exported 目标 startActivityAsUser 静默假成功且无 su 授权；
    // 模块 App 进程持 root（su am start 可启动非导出组件，编辑页测试已验证）。
    // :ui 预检失败时把完整 ShortcutAction JSON 广播给模块 App 代发。
    const val RELAY_LAUNCH_ACTION = "com.lsp.hypersidebar.action.RELAY_LAUNCH_SHORTCUT"
    const val RELAY_LAUNCH_EXTRA_SHORTCUT = "shortcut"
    const val RELAY_LAUNCH_EXTRA_TOKEN = "token"

    /** 代发通道防伪令牌（两侧同源代码共享；防任意 App 伪造广播借 root 启动任意组件） */
    const val RELAY_LAUNCH_TOKEN = "hsRl-2026-08-31-x7k9q2m4"
}

// channelMode（EDGE/HANDLE）已废弃（1B：EDGE 为唯一产品形态，HANDLE 遗留调试通道代码
// 一并移除）；activeZone 已废弃（运行时唯一用途是 1A 拆除的距离门控——正是"完全没有
// 选中反馈"的根因，选中语义由死区/内外取消区派生即可）。旧 key 残留在 prefs 文件中无害。

/** 布局与交互参数默认值（竖屏/横屏独立），与 PRD 参数表对齐。 */
object LayoutDefaults {
    const val ICON_SIZE = 48f
    const val INNER_RADIUS = 110f       // 实测轮六：150→110（双圈过大）
    const val OUTER_RADIUS_MAX = 150f   // 实测轮六：200→150
    const val MAX_APPS_OUTER = 7
    const val MAX_APPS_INNER = 4

    const val LANDSCAPE_ICON_SIZE = 48f
    const val LANDSCAPE_MAX_APPS_OUTER = 5
    const val LANDSCAPE_MAX_APPS_INNER = 3
    const val LANDSCAPE_INNER_RADIUS = 110f   // 实测轮六：150→110
    const val LANDSCAPE_OUTER_RADIUS = 150f   // 实测轮六：200→150

    const val QUICK_ICON_SIZE = 36f

    const val DEAD_ZONE = 12f
    const val TRIGGER_DWELL_MS = 250
    const val TRIGGER_MIN_DISTANCE_DP = 30f

    /** 所有布局相关键。恢复默认时批量写回。 */
    val layoutKeys = listOf(
        PrefKeys.ICON_SIZE,
        PrefKeys.INNER_RADIUS,
        PrefKeys.OUTER_RADIUS_MAX,
        PrefKeys.MAX_APPS_OUTER,
        PrefKeys.MAX_APPS_INNER,
        PrefKeys.LANDSCAPE_ICON_SIZE,
        PrefKeys.LANDSCAPE_MAX_APPS_OUTER,
        PrefKeys.LANDSCAPE_MAX_APPS_INNER,
        PrefKeys.LANDSCAPE_INNER_RADIUS,
        PrefKeys.LANDSCAPE_OUTER_RADIUS
    )
}
