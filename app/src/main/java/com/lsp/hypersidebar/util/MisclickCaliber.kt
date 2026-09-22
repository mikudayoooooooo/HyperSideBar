package com.lsp.hypersidebar.util

/**
 * 误触率口径 v2（PRD §9.3 二稿定稿、§11.3 采集层 v2）的判定与聚合。
 *
 * 零 Android 依赖：hook 侧只负责投喂事件，统计页与单测共用这一份判定——
 * 口径改这里，三端一起改。
 */

/** 触发通道（由 showInternal 的 isLandscape / cornerAnchor 派生，与 assembleFanData 同构） */
enum class FanChannel { EDGE, STRIP, CORNER }

/** 收起原因（doDismiss 的 cause 透传）；来电/切窗/冻结无事件源，只能以 WATCHDOG 近似 */
enum class DismissCause { USER_UP, LAUNCHED, WATCHDOG, PREEMPTED }

/** 松手时指尖所在区（UP 现场判定，非事后推导） */
enum class CancelZone { NONE, DEAD, INNER, OUTER, IN_BAND }

/** 一次展开→收起的完整事件：PRD 六元组 + 位移/死区半径（仅用于派生"原位/划过"标签） */
data class ExpandEvent(
    val traceId: Long,
    val channel: FanChannel,
    val showAt: Long,
    /** 松手时刻；无 UP 的强制收起为 0 */
    val upAt: Long,
    /** 全程是否进过预选（selectedSince != 0）——误触判定的主判据 */
    val preselected: Boolean,
    val zone: CancelZone,
    val cause: DismissCause,
    val travelPx: Float,
    val deadZonePx: Float,
    /** 展开时刻的墙上时间（epoch ms）：showAt/upAt 是 elapsedRealtime，按天归口只能靠它 */
    val wallShowAt: Long = 0L
) {
    /** 展开→松手时长；无 UP 返回 -1 */
    val holdMs: Long get() = if (upAt > showAt) upAt - showAt else -1L

    val isCancel: Boolean get() = cause == DismissCause.USER_UP && upAt > 0L

    /** 松手方式标签：位移未出死区=原位，否则=划过。只打标，不参与分值 */
    val swept: Boolean get() = isCancel && travelPx > deadZonePx
}

enum class Tier { NOT_CANCEL, L1, L2, L3 }

data class Verdict(
    val event: ExpandEvent,
    val tier: Tier,
    /** 误触后修正档：取消→3s 内重呼出并成功启动。单列不剔除（剔除会系统性低估） */
    val fixedByRetry: Boolean,
    /** 无效呼出链编号；-1 = 不在链内 */
    val chainId: Int
)

data class ChannelRate(
    val channel: FanChannel,
    val expands: Int,
    val forced: Int,
    val l1: Int,
    val l2: Int,
    val l3: Int,
    val fixL1: Int,
    val fixL2: Int,
    val chains: Int,
    val sweeps: Int
) {
    /** 有效展开数 = 全部展开 − 强制收起（看门狗近似） */
    val validExpands: Int get() = expands - forced
    val hasSample: Boolean get() = validExpands > 0

    /** 综合参考阈值：R1 < 2%、R < 5%（按通道各自判定，加权总值不得据此判合格） */
    val r1: Double get() = share(l1)
    val r2: Double get() = share(l2)
    val r: Double get() = r1 + MisclickCaliber.SOFT_WEIGHT * r2
    val r1ExclFix: Double get() = share(l1 - fixL1)
    val rExclFix: Double get() = r1ExclFix + MisclickCaliber.SOFT_WEIGHT * share(l2 - fixL2)
    val browseExitRate: Double get() = share(l3)
    val passR1: Boolean get() = r1 < MisclickCaliber.KPI_R1_MAX_PCT
    val passR: Boolean get() = r < MisclickCaliber.KPI_R_MAX_PCT

    private fun share(part: Int): Double =
        if (validExpands <= 0) 0.0 else part * 100.0 / validExpands
}

object MisclickCaliber {

    const val L1_MAX_MS = 350L
    const val L2_MAX_MS = 1000L
    const val FIX_WINDOW_MS = 3000L
    const val CHAIN_WINDOW_MS = 10_000L
    const val CHAIN_MIN_EXPANDS = 3
    const val SOFT_WEIGHT = 0.5
    const val KPI_R1_MAX_PCT = 2.0
    const val KPI_R_MAX_PCT = 5.0

    /**
     * 分层判定。主判据统一为「全程未进入预选」；时长只用于 L1/L2 分档，
     * 位移不参与判定（划过式误触是"短时长 + 大位移"，位移作必要条件会整类漏检）。
     */
    fun tierOf(e: ExpandEvent): Tier = when {
        !e.isCancel -> Tier.NOT_CANCEL
        e.preselected || e.holdMs >= L2_MAX_MS -> Tier.L3
        e.holdMs < L1_MAX_MS -> Tier.L1
        else -> Tier.L2
    }

    /** 逐事件判定 + 修正档回溯 + 无效链打标；输入顺序无关，内部按展开时刻排序 */
    fun judge(events: List<ExpandEvent>): List<Verdict> {
        val sorted = events.sortedBy { it.showAt }
        val fix = BooleanArray(sorted.size)
        markRetryFixes(sorted, fix)
        val chainIds = IntArray(sorted.size) { -1 }
        val chainCount = markDeadChains(sorted, chainIds)

        return sorted.mapIndexed { i, e ->
            val tier = tierOf(e)
            Verdict(
                event = e,
                tier = tier,
                fixedByRetry = fix[i] && (tier == Tier.L1 || tier == Tier.L2),
                chainId = if (chainCount > 0) chainIds[i] else -1
            )
        }
    }

    /**
     * 取消后 3s 内重呼出并最终成功启动 → 该段连续取消全部标"误触后修正成功"。
     * 段=紧挨着的连续 USER_UP 取消；被强制收起或超 3s 断链。
     */
    private fun markRetryFixes(sorted: List<ExpandEvent>, fix: BooleanArray) {
        var runStart = -1
        sorted.forEachIndexed { i, e ->
            when {
                e.isCancel -> if (runStart < 0) runStart = i
                e.cause == DismissCause.LAUNCHED && runStart >= 0 -> {
                    var nextShow = e.showAt
                    var j = i - 1
                    while (j >= runStart) {
                        val c = sorted[j]
                        if (nextShow - c.upAt > FIX_WINDOW_MS) break
                        fix[j] = true
                        nextShow = c.showAt
                        j--
                    }
                    runStart = -1
                }
                else -> runStart = -1
            }
        }
    }

    /** 相邻展开间隔 >10s 断段；段内 ≥3 次展开且零成功启动 → 整段同链 */
    private fun markDeadChains(sorted: List<ExpandEvent>, chainIds: IntArray): Int {
        if (sorted.isEmpty()) return 0
        var chain = -1
        var start = 0
        for (i in sorted.indices + sorted.size) {
            val breaks = i == sorted.size ||
                (i > 0 && sorted[i].showAt - sorted[i - 1].showAt > CHAIN_WINDOW_MS)
            if (!breaks) continue
            if (i - start >= CHAIN_MIN_EXPANDS &&
                (start until i).none { sorted[it].cause == DismissCause.LAUNCHED }
            ) {
                chain++
                (start until i).forEach { chainIds[it] = chain }
            }
            start = i
        }
        return chain + 1
    }

    /** 分通道聚合（PRD：通道独立计算；链按"该通道参与的链"计，跨通道链在两侧各计一次） */
    fun rollup(verdicts: List<Verdict>): List<ChannelRate> =
        FanChannel.entries.map { ch ->
            val inCh = verdicts.filter { it.event.channel == ch }
            ChannelRate(
                channel = ch,
                expands = inCh.size,
                forced = inCh.count { it.event.cause == DismissCause.WATCHDOG },
                l1 = inCh.count { it.tier == Tier.L1 },
                l2 = inCh.count { it.tier == Tier.L2 },
                l3 = inCh.count { it.tier == Tier.L3 },
                fixL1 = inCh.count { it.tier == Tier.L1 && it.fixedByRetry },
                fixL2 = inCh.count { it.tier == Tier.L2 && it.fixedByRetry },
                chains = inCh.mapNotNull { it.chainId.takeIf { id -> id >= 0 } }.distinct().size,
                sweeps = inCh.count { it.event.swept }
            )
        }.filter { it.expands > 0 }

    /** 加权总误触率：仅作趋势参考，不得据此判合格（底角展开量小，加权会稀释其问题） */
    fun weightedR(rates: List<ChannelRate>): Double {
        val total = rates.sumOf { it.expands }
        if (total <= 0) return 0.0
        return rates.sumOf { it.r * it.expands } / total
    }
}
