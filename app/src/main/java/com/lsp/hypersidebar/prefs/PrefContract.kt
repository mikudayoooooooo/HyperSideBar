package com.lsp.hypersidebar.prefs

import android.content.SharedPreferences

/**
 * prefs wire 协议契约：跨进程（设置 App ↔ hook 宿主）的唯一 key 与默认值定义。
 * 两侧读写必须引用本文件，禁止散落字面量；新增 key 先加这里。
 */

object PrefKeys {
    const val ENABLED = "enabled"
    const val CHANNEL_MODE = "channelMode"
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

    const val ACTIVE_ZONE = "activeZone"
    const val DEAD_ZONE = "deadZone"
    const val VIBRATE = "vibrateEnabled"
    const val TRIGGER_DWELL_MS = "triggerDwellMs"
    const val TRIGGER_MIN_DISTANCE = "triggerMinDistance"

    const val CUSTOM_APPS = "customApps"
    const val SHORTCUT_ACTIONS = "shortcut_actions"   // 用户快捷方式 JSON（权威 key）
}

object ChannelModes {
    const val EDGE = "EDGE"
    const val HANDLE = "HANDLE"
}

/** 读取当前呼出通道（容错：值类型漂移时回退默认 EDGE）。 */
fun SharedPreferences.readChannelMode(): String {
    return try {
        getString(PrefKeys.CHANNEL_MODE, ChannelModes.EDGE) ?: ChannelModes.EDGE
    } catch (_: Exception) {
        ChannelModes.EDGE
    }
}

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

    const val ACTIVE_ZONE = 200f       // Phase 2 语义修正：真正控制扇形选中半径（原 60f 被快捷栏距离覆盖，从未生效）
    const val DEAD_ZONE = 12f
    const val VIBRATE = true
    const val TRIGGER_DWELL_MS = 250
    const val TRIGGER_MIN_DISTANCE_DP = 30f
    const val CHANNEL_MODE = ChannelModes.EDGE

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
