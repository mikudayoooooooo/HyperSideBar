package com.lsp.hypersidebar.hook

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 底角斜滑触发判定（N1 方案 + 2026-10-01 防误触收紧，纯逻辑无 Android 依赖，便于 JUnit 覆盖）。
 *
 * 状态机（与 EdgeGestureHook#handleCornerTouch 配对）：
 * - DOWN 命中底角触发区（底部热区带 ∩ 左右角窗）→ 接管（claim）整条手势；
 *   未命中返回 [Action.PASS]，调用方原样放行原生。
 * - 接管后消费事件并缓冲快照：触发成功 → SHOW；**明确竖直（非斜滑意图）→
 *   [Action.ABANDON_REPLAY]**，调用方把缓冲的 DOWN+MOVE 合成重放给原生后透传——
 *   裸 `claimed=false + PASS` 的放弃已被实测否决（原生收到无 DOWN 的 MOVE 流会
 *   把 MOVE 当 DOWN 二次触发 startRecentsAnimationPre，N1 冻结语义的由来）。
 * - 第二指按下（ACTION_POINTER_DOWN）→ 放弃触发但**不重放**（多指意图复杂，
 *   维持消费到结束的旧语义）。
 *
 * 2026-10-01 防误触收紧（用户实测：靠边起手的日常上滑频繁误呼出）：
 * - 角度锥 20~82° → 25~65°（82° 上沿给"贴边竖直滑"开了 14% 内漂比的绿灯）；
 * - 新增内滑绝对量下限 [inwardMinPx]：角度是纯方向量不含幅度，幅度才是硬判据；
 * - 单帧瞬时命中 → 连续 [confirmFrames] 帧一致性（单采样被持指抖动主导）；
 * - 锥外竖直尾巴不再静默吞手势（"回桌面/后台"白做）→ 重放透传。
 */
class CornerTrigger {

    enum class Action {
        /** 非本机制：事件原样交给原生。 */
        PASS,

        /** 已接管：消费事件。触发前=判定中；多指放弃后=消费到结束。 */
        CONSUME,

        /** 达标：呼出扇形（并继续消费到手势结束）。 */
        SHOW,

        /** 明确竖直（非斜滑意图）：调用方把缓冲的 DOWN+MOVE 合成重放给原生，
         *  之后实时事件透传（本状态机对此手势返回 PASS 直到 UP）。 */
        ABANDON_REPLAY,
    }

    /** 本手势是否已被接管（调用方据此决定 `it.result = true`）。 */
    var claimed = false
        private set

    /** 已判定为竖直意图并要求重放（调用方重放后本状态机对此手势恒 PASS）。 */
    var abandoned = false
        private set

    /** 多指放弃（不重放）：消费到结束。 */
    var consumedToEnd = false
        private set

    private var triggered = false
    private var leftCorner = true
    private var downX = 0f
    private var downY = 0f
    private var confirmPx = 0f
    private var inwardMinPx = 0f
    private var minAngleDeg = 0f
    private var maxAngleDeg = 0f
    private var abandonExtraDeg = 0f
    private var confirmFrames = 2
    private var inConeFrames = 0
    private var verticalFrames = 0

    /** 手势结束/新 DOWN 时复位。 */
    fun reset() {
        claimed = false
        triggered = false
        abandoned = false
        consumedToEnd = false
        inConeFrames = 0
        verticalFrames = 0
        downX = 0f
        downY = 0f
    }

    /**
     * DOWN：判定是否命中底角触发区，命中即接管。
     *
     * @param cornerWidth 触发区宽度（px）：左右各以此为界
     * @param hotSpacePx  底部热区高度（px，NavStubView.getHotSpaceHeight 兜底 25dp）
     * @param inwardMinPx 内滑绝对量下限（px，= dp×density；纯方向量之外的幅度硬判据）
     * @param confirmFrames 连续帧数：锥内命中与竖直判定的一致性要求
     * @return 是否命中并接管本手势
     */
    fun onDown(
        x: Float,
        y: Float,
        screenW: Float,
        screenH: Float,
        hotSpacePx: Float,
        cornerWidth: Float,
        confirmPx: Float,
        inwardMinPx: Float,
        minAngleDeg: Float,
        maxAngleDeg: Float,
        abandonExtraDeg: Float,
        confirmFrames: Int,
    ): Boolean {
        reset()
        // 仅竖屏：横屏（W≥H）不接管（横屏触发整体走 B 路线）
        if (screenW <= 0f || screenH <= 0f || screenW >= screenH) return false
        this.confirmPx = confirmPx
        this.inwardMinPx = inwardMinPx
        this.minAngleDeg = minAngleDeg
        this.maxAngleDeg = maxAngleDeg
        this.abandonExtraDeg = abandonExtraDeg
        this.confirmFrames = confirmFrames.coerceAtLeast(1)
        downX = x
        downY = y
        val inBand = y >= screenH - hotSpacePx
        leftCorner = x <= cornerWidth
        claimed = inBand && (leftCorner || x >= screenW - cornerWidth)
        return claimed
    }

    /** 第二指按下：放弃触发，但保持接管（继续消费到结束，不重放）。 */
    fun onPointerDown(): Action {
        if (!claimed) return Action.PASS
        abandoned = true
        consumedToEnd = true
        return Action.CONSUME
    }

    /** MOVE：接管中按方向/位移/幅度/一致性判定。 */
    fun onMove(x: Float, y: Float): Action {
        if (!claimed) return Action.PASS
        if (triggered) return Action.CONSUME
        if (abandoned) return if (consumedToEnd) Action.CONSUME else Action.PASS
        val inward = if (leftCorner) x - downX else downX - x
        val up = downY - y
        if (hypot(inward, up) < confirmPx) return Action.CONSUME
        val deg = Math.toDegrees(atan2(up.toDouble(), inward.toDouble())).toFloat()
        // 锥内 = 方向在锥内 且 内滑幅度达下限（双条件，锁死"贴边竖直滑"）
        val inCone = deg in minAngleDeg..maxAngleDeg && inward >= inwardMinPx
        inConeFrames = if (inCone) inConeFrames + 1 else 0
        if (inConeFrames >= confirmFrames) {
            triggered = true
            return Action.SHOW
        }
        // 明确竖直（超出上限+余量，连续 N 帧）→ 让路：重放给原生
        if (deg > maxAngleDeg + abandonExtraDeg) {
            verticalFrames++
            if (verticalFrames >= confirmFrames) {
                abandoned = true
                return Action.ABANDON_REPLAY
            }
        } else {
            verticalFrames = 0
        }
        return Action.CONSUME
    }

    /** UP/CANCEL：接管且未重放则消费；重放后/未接管放行。调用方随后应 [reset]。 */
    fun onEnd(): Action = when {
        !claimed -> Action.PASS
        abandoned && !consumedToEnd -> Action.PASS
        else -> Action.CONSUME
    }
}
