package com.lsp.hypersidebar.hook

/**
 * 手势阈值常量（PRD §9.5）——边缘通道（launcher）与 B 路线条上状态机（:ui）
 * 分属两个进程，阈值必须同源定义，避免双份常量漂移。
 */
object GestureThresholds {
    /** 最小触发距离：内滑超此值进入"触发确认中" */
    const val SWIPE_CONFIRM_PX = 40f

    /** 触发最大夹角：滑动方向与内滑轴的夹角上限 */
    const val MAX_SWIPE_ANGLE_DEG = 60f

    /** 锚点圆半径：dwell 期间位移不超此值视为停顿 */
    const val STALL_RADIUS_PX = 15f
}
