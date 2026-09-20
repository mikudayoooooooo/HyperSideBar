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

    /**
     * 底角触发区宽度占比（N1 底角斜滑，仅竖屏）：左右各 W×此值。
     * 0.2 = W/5，实机 spike（S1）在 W=1080 下 cornerW=216px 判定正确。
     */
    const val CORNER_WIDTH_RATIO = 0.2f

    /** 底角斜滑接受锥下限（度）：与内滑轴夹角低于此=水平尾巴，接管但静默忽略。 */
    const val CORNER_MIN_ANGLE_DEG = 20f

    /** 底角斜滑接受锥上限（度）：高于此=竖直尾巴，接管但静默忽略。 */
    const val CORNER_MAX_ANGLE_DEG = 82f

    /** 底角热区高度兜底（dp）：反射 NavStubView.getHotSpaceHeight() 失败时使用。 */
    const val CORNER_BAND_DP = 25f

    /**
     * dwell 期间「还在动」的判据：**速度**（px/s），而不是"位移超过某个半径"。
     *
     * 0915 真机实测三轮定案：位移式判据（15px / 后来放宽到 40px + 连续 3 次）在横屏持续失效——
     * 「确认→STALL」实测均值 661ms，且数值几乎都是 250ms 的整数倍（s#9=1270≈250×5、
     * s#10=768≈250×3），即计时被反复整轮重置。根因：**缓慢持续漂移会周期性越过位移阈值**，
     * 每越一次赔掉整个 dwell；把阈值放大只能降低频率，不能消除。
     * 换成速度后，慢漂移速度低 → 不再重置；只有"真的还在快速滑动"才重置。
     */
    const val STALL_MAX_SPEED_PX_S = 500f

    /**
     * 速度窗口（ms）：在这么长的时间窗上算平均速度。
     * 必须用窗口而不是单采样差值——单采样的瞬时速度被持指抖动主导
     * （抖动 20px/采样 @120Hz ≈ 2500px/s，会把"停住"误判成"在动"）；
     * 取窗均值则抖动相互抵消，真实位移才浮出来。
     */
    const val STALL_SPEED_WINDOW_MS = 150L
}
