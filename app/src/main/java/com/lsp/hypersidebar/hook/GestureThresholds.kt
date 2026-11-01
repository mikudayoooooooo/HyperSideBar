package com.lsp.hypersidebar.hook

/**
 * 手势阈值常量（PRD §9.5）——边缘通道（launcher）与 B 路线条上状态机（:ui）
 * 分属两个进程，阈值必须同源定义，避免双份常量漂移。
 *
 * 呼出确认线自 2026-10-31 起固定为 [SWIPE_CONFIRM_DP]（设置项已删）；两通道 DOWN 时按密度
 * 换算缓存，本文件同时保留兜底 px 值与滞回系数。
 */
object GestureThresholds {
    /**
     * 呼出确认线（dp）——**唯一来源，禁止内联**（2026-10-31 定案：设置项已删）。
     *
     * 由来：`TRIGGER_MIN_DISTANCE` 设置项删除（用户走查"调到最大也没什么区别"）。
     * 事实上两条通道从来不该共用一个滑条：底角通道（[CORNER_INWARD_MIN_DP]）根本不读它，
     * OS4 底角也用 12dp 常量；只有边缘通道和条上状态机真正吃这条线。
     * 15dp = 旧默认值（≈40px@440dpi），手感与改动前一致。
     */
    const val SWIPE_CONFIRM_DP = 15f

    /** 最小触发距离兜底值（px）：density 不可用时使用（正常路径 = [SWIPE_CONFIRM_DP]×density） */
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
     * 底角触发区宽度占比 / 角度锥上下沿 / 热区高度——**2026-10-16 收回常量区**
     * （原 2026-10-01 迁去 PrefKeys+LayoutDefaults、做成底角 sheet 四个滑条）。
     * 为什么收回：OS4 实测证明这三个参数不是瓶颈——系统在自己的导航热区（横向中段）之外
     * 照样 pilfer 抢流，调宽调窄只改我们的 claim 概率，不解决"三连"；真修法是 native
     * 在 should_handle_event 上按角窗抑制（见 docs/adaptation/os4-hook-point-survey.md）。
     * 滑条于是变成"看起来能调、实际无用"的噪声，删掉。
     *
     * 取值与 OS2/3 完全对齐（同源同值，两边行为一致）：
     *   - 宽度占比 0.12：1080px 屏 = 130px，即 OS2/3 的 130 宽
     *   - 角度锥 25°~65°：OS2/3 现值（原 20°~82° 时靠边起手的日常上滑被 82° 上沿大量误呼出）
     *   - 热区高度 25dp：见 CORNER_BAND_DP；2400px@3x = 75px ≈ OS2/3 的 69px
     * 消费方（全部固定值，不再读 pref）：EdgeGestureHook（OS2/3 launcher 通道）、
     * SystemUiInputSpy（OS4 spy 粗筛 + sysCorner 判定）、CornerGeomBridge（推 native 几何）。
     */
    const val CORNER_WIDTH_RATIO = 0.12f
    // 2026-10-17 放宽（用户实测反馈"呼出的角度可以适当宽一些"）：原 [25,65]（锥宽 40°）收得太紧，
    // 斜滑稍偏就出锥；现 [15,75]（锥宽 60°，对 45° 对称）。放宽的代价是更容易把"斜向拖页"
    // 误判成角滑，仍由 CORNER_INWARD_MIN_DP 的**幅度**下限兜底（角度是方向量、不含幅度）。
    const val CORNER_MIN_ANGLE_DEG = 15f
    const val CORNER_MAX_ANGLE_DEG = 75f

    /**
     * 内滑绝对量下限（dp，2026-10-01 新增）：角度是纯方向量不含幅度——起手贴边+
     * 微小内漂即可凑出锥内角度。要求内滑分量达到此绝对值才可能命中，双条件锁死
     * "贴边竖直滑"。
     */
    const val CORNER_INWARD_MIN_DP = 12f

    /**
     * 连续帧数：锥内命中/竖直判定都要求连续 N 帧一致——单帧瞬时角度被持指抖动主导
     * （对齐边缘通道的速度窗思路；OS4 gesture_pause_detector 亦为多采样一致性）。
     */
    const val CORNER_CONFIRM_FRAMES = 2

    /**
     * OS4 底角专用帧数（1007 晚用户拍板）：抢跑模型下 2 帧累计纯是自缚——Rust 侧滑
     * 检测 ~10-30ms 即认领快扫流（201757 导出：83 次尝试仅 10 成，失败样本 DOWN→UP
     * 2ms 零 MOVE=流被 Rust 收走），确认必须压到单帧。防误触主力=内滑下限 12dp +
     * 底角热区（低频触摸区），单帧抖动风险由「抖出锥即清零 + 锥外竖直永不 pilfer」
     * 兜底。OS3（DOWN 即接管、无竞争）仍用 CORNER_CONFIRM_FRAMES。
     */
    const val CORNER_CONFIRM_FRAMES_FAST = 1

    /** 竖直判定余量（度）：deg > 锥上限+此值 连续 N 帧 = 明确竖直意图 → 重放透传。 */
    const val CORNER_ABANDON_EXTRA_DEG = 3f

    /**
     * 小窗「点击空白处」的 tap 判据（2026-10-16，三代 ROM 通用）。
     *
     * 语义：小窗打开时，在小窗**之外**的空白处轻点一下 ⇒ 缩小/关闭这个小窗。
     * 检测走 SystemUiInputSpy 已有的全局 monitor 通道（它看得到全部触摸），本组常量只
     * 定义"什么算一次轻点"——判定核是纯 JVM 的 `hook/BlankTapDetector.kt`。
     *
     * 为什么是这两个值：
     * - `SLOP_DP = 12`：与 `CORNER_INWARD_MIN_DP` 同量级，指按住时的手抖/微漂上限；
     *   超过就不是"点"而是"拖"（拖动是用户在小窗外做其它事，绝不能误收小窗）。
     * - `MAX_MS = 300`：与 Android `ViewConfiguration.getLongPressTimeout()`(500ms) 拉开
     *   距离，保证长按不会被当成点。两者都是"宁可不动作，也不误动作"的方向。
     */
    const val BLANK_TAP_SLOP_DP = 12f
    const val BLANK_TAP_MAX_MS = 300L

    /** 底角热区高度（dp）：三 ROM 统一用本值（原 OS2/3 的 `NavStubView.getHotSpaceHeight()`
     *  反射已于 2026-10-16 删除——实测该 ROM 值=25dp，与默认值相同，而 OS4 spy 一直用常量；
     *  角区已实测与系统导航热区 0 重叠，"对齐 ROM 热区"不再是安全线）。
     *  2400px@3x = 75px；推给 native 的 hot_pct 也由它折算（75/2400×100 = 2.8646）。 */
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

    /**
     * 竖屏触发区 Y 范围（PRD §9.5 行为规则 3 的比例形式）：[H/3, 2H/3]。
     * 唯一定义源——OS2/3 launcher 通道（EdgeGestureHook.zoneBounds）与 OS4 B 路线
     * spy 带内粗筛（SystemUiInputSpy.inTriggerBand）共用，防两处各写一份漂移
     * （zoneBounds 注释里"唯一定义源"规矩的落实；1005 曾在 spy 重复定义被用户指出）。
     */
    const val TRIGGER_ZONE_TOP_RATIO = 1f / 3f
    const val TRIGGER_ZONE_BOTTOM_RATIO = 2f / 3f

    /**
     * 系统手势带（dp）：屏幕四边的系统手势识别区 —— 左/右缘 = 返回手势、顶缘 = 通知栏、
     * 底缘 = 桌面手势（小横条）。这些带子里的触摸**属于系统管线**，输入接管不得抢
     * （2026-10-19：返回手势起点在边缘空白 ⇒ 被接管抢走后系统的返回识别链断流，
     * 小窗应用收不到 BACK ⇒ 应用内返回失效；这正是用户要的"小窗获焦时返回手势 =
     * 应用内返回"的对立面）。
     *
     * 取值：Android 返回手势区典型 ≈20–24dp（`systemGestures` insets），取 24dp 留裕量；
     * 带内**轻点**仍走空白动作（tap 不触发返回识别，两者不冲突），只排除"抢流"。
     */
    const val SYSTEM_GESTURE_EDGE_DP = 24f

    /** 纯判据：DOWN 是否落在四边系统手势带内（[density] 换算 dp→px，调用方取 displayMetrics）。 */
    fun inSystemGestureBand(x: Float, y: Float, w: Int, h: Int, density: Float): Boolean {
        val e = SYSTEM_GESTURE_EDGE_DP * density
        return x < e || y < e || x > w - e || y > h - e
    }
}
