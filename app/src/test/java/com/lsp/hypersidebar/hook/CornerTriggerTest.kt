package com.lsp.hypersidebar.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CornerTrigger 纯逻辑单测：底角命中、方向锥、尾巴静默、粘性接管、多指放弃。
 * 坐标系与实机一致：W=1080, H=2400, 热区=68px(≈25dp), cornerW=216px(W/5)。
 */
class CornerTriggerTest {

    private val w = 1080f
    private val h = 2400f
    private val hot = 68f
    private val cornerW = 216f
    private val confirm = 40f
    private val minDeg = 20f
    private val maxDeg = 82f

    private fun CornerTrigger.down(x: Float, y: Float): Boolean =
        onDown(x, y, w, h, hot, cornerW, confirm, minDeg, maxDeg)

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
        assertFalse(t.onDown(100f, 1000f, 2400f, 1080f, hot, cornerW, confirm, minDeg, maxDeg))
        assertFalse(t.claimed)
    }

    @Test
    fun diagonalMove_left_triggers() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        assertEquals(CornerTrigger.Action.SHOW, t.onMove(300f, 2200f))
    }

    @Test
    fun diagonalMove_right_mirrors() {
        val t = CornerTrigger()
        t.down(980f, 2380f)
        assertEquals(CornerTrigger.Action.SHOW, t.onMove(800f, 2200f))
    }

    @Test
    fun show_firesOnlyOnce() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        assertEquals(CornerTrigger.Action.SHOW, t.onMove(300f, 2200f))
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(400f, 2100f))
    }

    @Test
    fun verticalTail_consumesNeverShows() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        // 纯竖直上滑：θ≈84° > 82° 上限
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(110f, 2280f))
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(120f, 2100f))
        assertEquals(CornerTrigger.Action.CONSUME, t.onEnd())
    }

    @Test
    fun horizontalTail_consumesNeverShows() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        // 近乎水平内滑：θ≈1.4° < 20° 下限
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
    fun pointerDown_abandonsTrigger() {
        val t = CornerTrigger()
        t.down(100f, 2380f)
        assertEquals(CornerTrigger.Action.CONSUME, t.onPointerDown())
        // 放弃后即使出现合格斜滑也不再触发
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