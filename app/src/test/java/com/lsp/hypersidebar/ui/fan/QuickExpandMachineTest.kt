package com.lsp.hypersidebar.ui.fan

import com.lsp.hypersidebar.ui.fan.QuickExpandMachine.ACTION_CANCEL
import com.lsp.hypersidebar.ui.fan.QuickExpandMachine.ACTION_DOWN
import com.lsp.hypersidebar.ui.fan.QuickExpandMachine.ACTION_MOVE
import com.lsp.hypersidebar.ui.fan.QuickExpandMachine.ACTION_UP
import com.lsp.hypersidebar.ui.fan.QuickExpandMachine.Command
import com.lsp.hypersidebar.ui.fan.QuickExpandMachine.Event
import com.lsp.hypersidebar.ui.fan.QuickExpandMachine.QuickHit
import com.lsp.hypersidebar.ui.fan.QuickExpandMachine.State
import com.lsp.hypersidebar.ui.fan.QuickExpandMachine.onEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组展开纯状态机单测（计划 §B3/C6 验收：悬停展开 / 松手展开 / 移出收回 /
 * 展开当帧不误收回（闩锁）/ 悬停展开后松手落子项外收回 / 常驻点选 / 快速划过
 * 不误展开 / CANCEL 回退）。
 */
class QuickExpandMachineTest {

    private val G = QuickHit.Quick("grp", isGroup = true)
    private val SUB1 = QuickHit.Quick("s1", isGroup = false)
    private val SUB2 = QuickHit.Quick("s2", isGroup = false)
    private val ITEM = QuickHit.Quick("item", isGroup = false)

    private fun step(
        s: State, act: Int, hit: QuickHit, t: Long,
        env: Boolean = true, persistent: Boolean = false
    ) = onEvent(s, Event(act, hit, t, env), persistent)

    // ===== 悬停自动展开（普通模式） =====

    @Test
    fun `hover dwell expands group without release`() {
        var s = State()
        s = step(s, ACTION_MOVE, G, 1000).first                 // 选中组
        val (s2, c2) = step(s, ACTION_MOVE, G, 1000 + 150)      // 满 dwell ⇒ 展开
        assertEquals(Command.EXPAND_GROUP, c2)
        assertTrue(s2.expanded && s2.expandedGroupId == "grp")
        assertFalse(s2.sawMoveAfterExpand)                       // 闩锁未置
        assertEquals(null, s2.selectedId)                        // 选中清零重映射
    }

    @Test
    fun `quick swipe past group does not expand`() {
        var s = State()
        s = step(s, ACTION_MOVE, G, 1000).first
        // 100ms 即离开（< dwell 150）
        val (s2, c2) = step(s, ACTION_MOVE, QuickHit.None, 1100)
        assertEquals(Command.NONE, c2)
        assertFalse(s2.expanded)
        val (s3, c3) = step(s, ACTION_UP, QuickHit.None, 1150)
        assertEquals(Command.NONE, c3)
        assertFalse(s3.expanded)
    }

    // ===== 松手展开（普通模式已预选） =====

    @Test
    fun `release on preselected group expands without launch`() {
        var s = State()
        s = step(s, ACTION_MOVE, G, 1000).first
        val (s2, c2) = step(s, ACTION_UP, G, 1080)
        assertEquals(Command.EXPAND_GROUP, c2)
        assertTrue(s2.expanded)
    }

    @Test
    fun `release on unselected group in normal mode no expand`() {
        // 从未选中（如孤儿尾巴事件）直接 UP 在组上：不展开（防误触）
        val (s2, c2) = step(State(), ACTION_UP, G, 1000)
        assertEquals(Command.NONE, c2)
        assertFalse(s2.expanded)
    }

    // ===== 闩锁：展开当帧不误收回 =====

    @Test
    fun `first move after expand sets latch without collapse`() {
        var s = State()
        s = step(s, ACTION_MOVE, G, 1000).first
        s = step(s, ACTION_MOVE, G, 1150).first                  // 展开
        // 展开后首 MOVE 即使在包络外也不收回（豁免），只置闩锁
        val (s2, c2) = step(s, ACTION_MOVE, SUB1, 1200, env = false)
        assertEquals(Command.NONE, c2)
        assertTrue(s2.sawMoveAfterExpand)
        assertTrue(s2.expanded)
    }

    @Test
    fun `move outside envelope after latch collapses to root`() {
        var s = State()
        s = step(s, ACTION_MOVE, G, 1000).first
        s = step(s, ACTION_MOVE, G, 1150).first                  // 展开
        s = step(s, ACTION_MOVE, SUB1, 1200).first               // 置闩锁
        val (s2, c2) = step(s, ACTION_MOVE, QuickHit.None, 1300, env = false)
        assertEquals(Command.COLLAPSE_GROUP, c2)
        assertFalse(s2.expanded)
    }

    @Test
    fun `move inside envelope keeps expanded and tracks selection`() {
        var s = State()
        s = step(s, ACTION_MOVE, G, 1000).first
        s = step(s, ACTION_MOVE, G, 1150).first
        s = step(s, ACTION_MOVE, SUB1, 1200).first               // 闩锁+选中子项
        val (s2, c2) = step(s, ACTION_MOVE, SUB2, 1400)
        assertEquals(Command.NONE, c2)
        assertTrue(s2.expanded)
        assertEquals("s2", s2.selectedId)
    }

    // ===== 展开态子项启动 =====

    @Test
    fun `sub item dwell then release launches`() {
        var s = State()
        s = step(s, ACTION_MOVE, G, 1000).first
        s = step(s, ACTION_MOVE, G, 1150).first                  // 展开
        s = step(s, ACTION_MOVE, SUB1, 1200).first               // 闩锁+选中
        val (s2, c2) = step(s, ACTION_UP, SUB1, 1200 + 150)      // 满 dwell ⇒ 启动
        assertEquals(Command.LAUNCH_QUICK, c2)
        assertFalse(s2.expanded)                                  // 启动后状态归零（host 负责收窗）
    }

    @Test
    fun `sub item release before dwell none`() {
        var s = State()
        s = step(s, ACTION_MOVE, G, 1000).first
        s = step(s, ACTION_MOVE, G, 1150).first
        s = step(s, ACTION_MOVE, SUB1, 1200).first
        val (_, c) = step(s, ACTION_UP, SUB1, 1250)              // 50ms < 150
        assertEquals(Command.NONE, c)
    }

    // ===== 悬停展开后松手落子项外 → 收回根栏 =====

    @Test
    fun `release outside sub items collapses to root not dismiss`() {
        var s = State()
        s = step(s, ACTION_MOVE, G, 1000).first
        s = step(s, ACTION_MOVE, G, 1150).first
        s = step(s, ACTION_MOVE, SUB1, 1200).first               // 闩锁
        val (s2, c2) = step(s, ACTION_UP, QuickHit.None, 1400, env = true)
        assertEquals(Command.COLLAPSE_GROUP, c2)
        assertFalse(s2.expanded)
    }

    // ===== 常驻（persistent/released）模式 =====

    @Test
    fun `persistent tap on group expands`() {
        var s = State()
        s = step(s, ACTION_DOWN, G, 1000, persistent = true).first
        val (s2, c2) = step(s, ACTION_UP, G, 1050, persistent = true)
        assertEquals(Command.EXPAND_GROUP, c2)
        assertTrue(s2.expanded)
    }

    @Test
    fun `persistent expanded tap sub launches with zero gate`() {
        var s = State()
        s = step(s, ACTION_DOWN, G, 1000, persistent = true).first
        s = step(s, ACTION_UP, G, 1050, persistent = true).first  // 展开
        s = step(s, ACTION_DOWN, SUB1, 1100, persistent = true).first
        val (_, c) = step(s, ACTION_UP, SUB1, 1120, persistent = true)  // 20ms 也启动
        assertEquals(Command.LAUNCH_QUICK, c)
    }

    @Test
    fun `persistent down miss is tap outside even when expanded`() {
        var s = State()
        s = step(s, ACTION_DOWN, G, 1000, persistent = true).first
        s = step(s, ACTION_UP, G, 1050, persistent = true).first  // 展开
        val (s2, c2) = step(s, ACTION_DOWN, QuickHit.None, 1200, persistent = true)
        assertEquals(Command.TAP_OUTSIDE, c2)                     // 点空白关整扇
        assertFalse(s2.expanded)
    }

    @Test
    fun `persistent collapsed tap item launches with zero gate`() {
        var s = State()
        s = step(s, ACTION_DOWN, ITEM, 1000, persistent = true).first
        val (_, c) = step(s, ACTION_UP, ITEM, 1010, persistent = true)
        assertEquals(Command.LAUNCH_QUICK, c)
    }

    // ===== 折叠态普通项（回归现有语义） =====

    @Test
    fun `normal item dwell release launches`() {
        var s = State()
        s = step(s, ACTION_MOVE, ITEM, 1000).first
        val (_, c) = step(s, ACTION_UP, ITEM, 1200)
        assertEquals(Command.LAUNCH_QUICK, c)
    }

    @Test
    fun `fan item passes gates launches app`() {
        var s = State()
        s = step(s, ACTION_MOVE, QuickHit.FanItem(0), 1000).first
        val (_, c) = step(s, ACTION_UP, QuickHit.FanItem(0), 1200)
        assertEquals(Command.LAUNCH_APP, c)
    }

    @Test
    fun `cancel resets selection and collapses expanded`() {
        var s = State()
        s = step(s, ACTION_MOVE, G, 1000).first
        s = step(s, ACTION_MOVE, G, 1150).first                  // 展开
        val (s2, c2) = step(s, ACTION_CANCEL, QuickHit.None, 1200)
        assertEquals(Command.COLLAPSE_GROUP, c2)
        assertEquals(State(), s2)
    }
}
