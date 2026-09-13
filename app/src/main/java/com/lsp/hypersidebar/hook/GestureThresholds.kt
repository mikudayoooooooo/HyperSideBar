package com.lsp.hypersidebar.hook

/**
 * 手势阈值常量（PRD §9.5）——边缘通道（launcher）与 B 路线条上状态机（:ui）
 * 分属两个进程，阈值必须同源定义，避免双份常量漂移。
 *
 * 滑动距离已改用户可配置（PrefKeys.TRIGGER_MIN_DISTANCE，dp，0913 拍板"40px 死值太极端"）：
 * 两通道 DOWN 时按密度换算缓存，本文件只保留兜底值与滞回系数。
 */
object GestureThresholds {
    /** 最小触发距离兜底值（px）：prefs/密度不可用时使用（正常路径=滑动距离 dp×density） */
    const val SWIPE_CONFIRM_PX = 40f

    /**
     * 滑回滞回系数：重置阈值 = 确认距离 × 此值。
     * 0913 真机取证（g#44/g#45）：确认与滑回共用同一条 40px 线、零滞回——近阈值悬停时
     * 1~2px 的向外微漂即整条手势清零重计时，4.4s 才呼出甚至彻底失败。
     * 重置语义回归 PRD"滑回【边缘】才重置"：取消回滑都会滑到贴边，半程不足以误清。
     */
    const val SWIPE_RESET_RATIO = 0.5f

    /** 触发最大夹角：滑动方向与内滑轴的夹角上限 */
    const val MAX_SWIPE_ANGLE_DEG = 60f

    /** 锚点圆半径：dwell 期间位移不超此值视为停顿 */
    const val STALL_RADIUS_PX = 15f
}
