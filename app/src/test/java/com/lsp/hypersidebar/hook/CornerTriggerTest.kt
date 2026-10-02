package com.lsp.hypersidebar.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CornerTrigger 纯逻辑单测：底角命中、方向锥（含内滑幅度下限）、连续帧一致性、
 * 竖直尾巴重放让路、多指放弃、粘性接管。
 * 坐标系与实机一致：W=1080, H=2400, 热区=68px(≈25dp), cornerW=130px(≈W×0.12)。
 */
class CornerTriggerTest {

    private val w = 1080f
    private val h = 2400f
    private val hot = 68f
    private val cornerW = 130f
    private val confirm = 40f
    private val inwardMin = 33f   // 12dp ≈ 33px @440dpi
    private val minDeg = 25f
    private val maxDeg = 65f
    private val abandonExtra = 3f
    private val frames = 2

    private fun CornerTrigger.down(x: Float, y: Float): Boolean =
        onDown(x, y, w, h, hot, cornerW, confirm, inwardMin, minDeg, maxDeg, abandonExtra, frames)

    @Test
    fun down_leftCorner_claims() {
        val t = CornerTrigger()
        assertTrue(t.down(100f, 2380f))
        assertTrue(t.claimed)
    }

    @Test
    fun down_rightCorner_claims() {
        val t = CornerTrigger()
        assertTrue(t.down(980f, 2380f))
        assertTrue(t.claimed)
    }

    @Test
    fun down_boundaryEdges() {
        val t = CornerTrigger()
        assertTrue(t.down(cornerW, 2380f))
        assertTrue(t.down(w - cornerW, 2380f))
        assertFalse(t.down(cornerW + 1f, 2380f))
    }

    @Test
    fun down_outsideBand_passes() {
        val t = CornerTrigger()
        assertFalse(t.down(100f, h - hot - 1f))
        assertFalse(t.claimed)
    }

    @Test
    fun down_middleX_passes() {
        val t = CornerTrigger()
        assertFalse(t.down(w / 2f, 2380f))
    }

    @Test
    fun down_landscape_passes() {
        val t = CornerTrigger()
        // 横屏：W=2400,H=1080，即使落在"底角"也不接管
        assertFalse(t.onDown(100f, 1000f, 2400f, 1080f, hot, cornerW, confirm, inwardMin, minDeg, maxDeg, abandonExtra, frames))
        assertFalse(t.claimed)
    }

    @Test
    fun diagonalMove_needsTwoConsecutiveInConeFrames() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        // 45° 斜滑：inward=200, up=180 → deg≈42°，内滑 200 ≥ 33 ✓ → 第 1 帧
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(300f, 2200f))
        // 第 2 帧连续在锥内 → 触发
        assertEquals(CornerTrigger.Action.SHOW, t.onMove(320f, 2180f))
    }

    @Test
    fun diagonalMove_right_mirrors() {
        val t = CornerTrigger()
        t.down(980f, 2380f)
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(800f, 2200f))
        assertEquals(CornerTrigger.Action.SHOW, t.onMove(780f, 2180f))
    }

    @Test
    fun singleFrameJitter_doesNotTrigger() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        // 帧内锥（inward=200, up=180 → deg≈42°）
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(300f, 2200f))
        // 抖动帧出锥（近水平 deg≈0.7°）→ 连帧计数清零
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(500f, 2375f))
        // 回到锥内（deg≈25.5°）：只算第 1 帧，仍不触发
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(540f, 2170f))
        // 第 2 帧连续（deg≈27.6°）→ 触发
        assertEquals(CornerTrigger.Action.SHOW, t.onMove(560f, 2140f))
    }

    @Test
    fun show_firesOnlyOnce() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(300f, 2200f))
        assertEquals(CornerTrigger.Action.SHOW, t.onMove(320f, 2180f))
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(400f, 2100f))
    }

    @Test
    fun inwardBelowMinimum_inConeAngleStillUndecided() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        // deg≈60.9° 在锥内但内滑仅 20px < 33px：幅度不达 → 不算锥内帧；
        // deg 又未超 68°（上限+余量）→ 既不触发也不放弃，保持消费判定中
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(120f, 2344f))
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(124f, 2334f))
    }

    @Test
    fun horizontalTail_consumesNeverShows() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        // 近乎水平内滑：θ≈1.4° < 25° 下限（内滑幅度充足，纯方向排除）
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(300f, 2375f))
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(500f, 2370f))
        assertEquals(CornerTrigger.Action.CONSUME, t.onEnd())
    }

    @Test
    fun belowConfirm_noShow() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(110f, 2370f))
    }

    @Test
    fun stickyClaim_untilUp() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(110f, 2370f))
        assertEquals(CornerTrigger.Action.CONSUME, t.onEnd())
    }

    @Test
    fun pointerDown_abandonsTriggerWithoutReplay() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        assertEquals(CornerTrigger.Action.CONSUME, t.onPointerDown())
        // 多指放弃=消费到结束（不重放）：即使出现合格斜滑也不再触发，事件不透传
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(300f, 2200f))
        assertEquals(CornerTrigger.Action.CONSUME, t.onEnd())
    }

    @Test
    fun nonClaimed_movesAndEnd_pass() {
        val t = CornerTrigger()
        assertFalse(t.down(w / 2f, 2380f))
        assertEquals(CornerTrigger.Action.PASS, t.onMove(300f, 2200f))
        assertEquals(CornerTrigger.Action.PASS, t.onEnd())
    }

    @Test
    fun reset_clearsClaim() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        t.reset()
        assertFalse(t.claimed)
        assertEquals(CornerTrigger.Action.PASS, t.onMove(300f, 2200f))
    }
}
