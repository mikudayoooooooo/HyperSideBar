package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 误触口径 v2 判定契约（PRD §9.3 二稿）。
 *
 * 钉死的都是评审时踩过的坑：位移不得作 L1 必要条件（划过式误触会整类漏检）、
 * 修正档只单列不剔除（剔除会低估待测对象）、阈值按通道各自判定（加权总值会稀释底角）。
 */
class MisclickCaliberTest {

    private fun ev(
        channel: FanChannel = FanChannel.CORNER,
        showAt: Long,
        upAt: Long = 0L,
        pre: Boolean = false,
        cause: DismissCause = DismissCause.USER_UP,
        travel: Float = 0f,
        deadZone: Float = 33f
    ) = ExpandEvent(
        traceId = showAt,
        channel = channel,
        showAt = showAt,
        upAt = upAt,
        preselected = pre,
        zone = if (cause == DismissCause.USER_UP) CancelZone.IN_BAND else CancelZone.NONE,
        cause = cause,
        travelPx = travel,
        deadZonePx = deadZone
    )

    @Test
    fun `short cancel without preselect is L1`() {
        assertEquals(Tier.L1, MisclickCaliber.tierOf(ev(showAt = 0, upAt = 200)))
    }

    @Test
    fun `cancel between 350ms and 1s is L2`() {
        assertEquals(Tier.L2, MisclickCaliber.tierOf(ev(showAt = 0, upAt = 600)))
    }

    @Test
    fun `sweep style misclick stays L1 despite large travel`() {
        // 短时长 + 大位移 = 划过式误触：位移只是行为标签，绝不能把它挡在口径外
        val e = ev(showAt = 0, upAt = 200, travel = 1500f)
        assertEquals(Tier.L1, MisclickCaliber.tierOf(e))
        assertTrue("位移出死区应打『划过』标签", e.swept)
    }

    @Test
    fun `preselected or long dwell is L3 browse exit`() {
        assertEquals(Tier.L3, MisclickCaliber.tierOf(ev(showAt = 0, upAt = 120, pre = true)))
        assertEquals(Tier.L3, MisclickCaliber.tierOf(ev(showAt = 0, upAt = 1500)))
    }

    @Test
    fun `launch and forced collapse are not cancel events`() {
        assertEquals(Tier.NOT_CANCEL, MisclickCaliber.tierOf(ev(showAt = 0, upAt = 100, cause = DismissCause.LAUNCHED)))
        assertEquals(Tier.NOT_CANCEL, MisclickCaliber.tierOf(ev(showAt = 0, cause = DismissCause.WATCHDOG)))
    }

    @Test
    fun `retry success within 3s marks fix but keeps it counted`() {
        val events = listOf(
            ev(showAt = 0, upAt = 200),
            ev(showAt = 2000, upAt = 2600, cause = DismissCause.LAUNCHED)
        )
        val verdicts = MisclickCaliber.judge(events)
        val cancel = verdicts.first { it.event.showAt == 0L }
        assertEquals(Tier.L1, cancel.tier)
        assertTrue("3s 内重呼出并成功 → 单列修正档", cancel.fixedByRetry)

        val rate = MisclickCaliber.rollup(verdicts).single()
        assertEquals(1, rate.l1)
        assertEquals(1, rate.fixL1)
        // 含修正档与剔除修正档两种口径并列，分子不因修正档而消失
        assertEquals(50.0, rate.r1, 0.001)
        assertEquals(0.0, rate.r1ExclFix, 0.001)
    }

    @Test
    fun `retry beyond 3s is not a fix`() {
        val events = listOf(
            ev(showAt = 0, upAt = 200),
            ev(showAt = 4000, upAt = 4600, cause = DismissCause.LAUNCHED)
        )
        assertFalse(MisclickCaliber.judge(events).first().fixedByRetry)
    }

    @Test
    fun `consecutive cancels before a success all become fix`() {
        val events = listOf(
            ev(showAt = 0, upAt = 200),
            ev(showAt = 700, upAt = 900),
            ev(showAt = 1400, upAt = 2000, cause = DismissCause.LAUNCHED)
        )
        val fixed = MisclickCaliber.judge(events).count { it.fixedByRetry }
        assertEquals("同链前序各次一并单列", 2, fixed)
    }

    @Test
    fun `three expands in ten seconds with zero launch form one dead chain`() {
        val events = listOf(
            ev(showAt = 0, upAt = 200),
            ev(showAt = 3000, upAt = 3200),
            ev(showAt = 6000, upAt = 6200)
        )
        val verdicts = MisclickCaliber.judge(events)
        assertTrue(verdicts.all { it.chainId == 0 })
        assertEquals(1, MisclickCaliber.rollup(verdicts).single().chains)
    }

    @Test
    fun `two expands are not a chain`() {
        val events = listOf(ev(showAt = 0, upAt = 200), ev(showAt = 1000, upAt = 1200))
        assertTrue(MisclickCaliber.judge(events).all { it.chainId < 0 })
    }

    @Test
    fun `forced collapse leaves the denominator but never the numerator`() {
        val events = listOf(
            ev(showAt = 0, upAt = 200),
            ev(showAt = 5000, cause = DismissCause.WATCHDOG)
        )
        val rate = MisclickCaliber.rollup(MisclickCaliber.judge(events)).single()
        assertEquals(2, rate.expands)
        assertEquals(1, rate.forced)
        assertEquals(1, rate.validExpands)
        assertEquals(100.0, rate.r1, 0.001)
    }

    @Test
    fun `per channel rates hold while weighted total looks clean`() {
        // 底角通道全是误触，边缘通道量大且干净：加权总值会把它摊平——所以 KPI 只看分通道
        val events = List(100) { ev(FanChannel.EDGE, showAt = it * 60_000L, upAt = it * 60_000L + 4000, pre = true) } +
            List(5) { ev(FanChannel.CORNER, showAt = it * 60_000L, upAt = it * 60_000L + 150) }
        val rates = MisclickCaliber.rollup(MisclickCaliber.judge(events))
        val corner = rates.first { it.channel == FanChannel.CORNER }
        val edge = rates.first { it.channel == FanChannel.EDGE }
        assertEquals(100.0, corner.r1, 0.001)
        assertFalse("底角必须判不合格", corner.passR1)
        assertTrue(edge.passR1)
        assertTrue("加权总值看着合格——正是不能拿它判定的原因", MisclickCaliber.weightedR(rates) < 5.0)
    }
}
