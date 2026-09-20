package com.lsp.hypersidebar.hook

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 底角斜滑触发判定（N1 方案，纯逻辑无 Android 依赖，便于 JUnit 覆盖）。
 *
 * 状态机（与 EdgeGestureHook#handleCornerTouch 配对）：
 * - DOWN 命中底角触发区（底部热区带 ∩ 左右 W/5 区）→ 接管（claim）整条手势；
 *   未命中返回 [Action.PASS]，调用方原样放行原生。
 * - 接管后一路消费到 UP/CANCEL：即使触发失败（竖直/水平尾巴）也**不得**中途放行。
 *   实测教训：DOWN 消费而 MOVE 放行会让原生把 MOVE 当 DOWN 再触发一次
 *   `startRecentsAnimationPre`（stale mCurrAction/mDownEvent）。
 * - 第二指按下（ACTION_POINTER_DOWN）→ 放弃触发但维持接管（消费）。
 * - 触发判据：位移 ≥ confirmPx 且方向角 θ=atan2(up, inward) 落在接受锥内 →
 *   [Action.SHOW]（只报一次）。角度在锥外（竖直尾巴 θ>max / 水平尾巴 θ<min）→ 静默忽略。
 *
 * 方向相对 DOWN 点计算；扇形锚点由调用方取精确底角 (0,H)/(W,H)，与本类无关。
 */
class CornerTrigger {

    enum class Action {
        /** 非本机制：事件原样交给原生。 */
        PASS,

        /** 已接管但未达标：消费事件，静默忽略。 */
        CONSUME,

        /** 达标：呼出扇形（并继续消费到手势结束）。 */
        SHOW,
    }

    /** 本手势是否已被接管（调用方据此决定 `it.result = true`）。 */
    var claimed = false
        private set

    private var triggered = false
    private var abandoned = false
    private var leftCorner = true
    private var downX = 0f
    private var downY = 0f
    private var confirmPx = 0f
    private var minAngleDeg = 0f
    private var maxAngleDeg = 0f

    /** 手势结束/新 DOWN 时复位。 */
    fun reset() {
        claimed = false
        triggered = false
        abandoned = false
        downX = 0f
        downY = 0f
    }

    /**
     * DOWN：判定是否命中底角触发区，命中即接管。
     *
     * @param cornerWidth 触发区宽度（px）：左右各以此为界
     * @param hotSpacePx  底部热区高度（px，NavStubView.getHotSpaceHeight 兜底 25dp）
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
        minAngleDeg: Float,
        maxAngleDeg: Float,
    ): Boolean {
        reset()
        // 仅竖屏：横屏（W≥H）不接管（横屏触发整体走 B 路线）
        if (screenW <= 0f || screenH <= 0f || screenW >= screenH) return false
        this.confirmPx = confirmPx
        this.minAngleDeg = minAngleDeg
        this.maxAngleDeg = maxAngleDeg
        downX = x
        downY = y
        val inBand = y >= screenH - hotSpacePx
        leftCorner = x <= cornerWidth
        claimed = inBand && (leftCorner || x >= screenW - cornerWidth)
        return claimed
    }

    /** 第二指按下：放弃触发，但保持接管（继续消费到手势结束）。 */
    fun onPointerDown(): Action {
        if (!claimed) return Action.PASS
        abandoned = true
        return Action.CONSUME
    }

    /** MOVE：接管中按方向/位移判定；已接管未达标恒 [Action.CONSUME]。 */
    fun onMove(x: Float, y: Float): Action {
        if (!claimed) return Action.PASS
        if (abandoned || triggered) return Action.CONSUME
        val inward = if (leftCorner) x - downX else downX - x
        val up = downY - y
        if (hypot(inward, up) < confirmPx) return Action.CONSUME
        val deg = Math.toDegrees(atan2(up.toDouble(), inward.toDouble())).toFloat()
        return if (deg in minAngleDeg..maxAngleDeg) {
            triggered = true
            Action.SHOW
        } else {
            Action.CONSUME
        }
    }

    /** UP/CANCEL：接管中消费，否则放行。调用方随后应 [reset]。 */
    fun onEnd(): Action = if (claimed) Action.CONSUME else Action.PASS
}