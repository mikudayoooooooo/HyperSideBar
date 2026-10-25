package com.lsp.hypersidebar.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FreeformBlankTest` 单测。
 *
 * 重点守的是**搬运 ROM 实现时最容易错的三处**（见类注释）：
 * 1. 缩放只扩右下角（left/top 不乘）——错法是把 left/top 也乘，窗口一缩就把左上方判成"里面"；
 * 2. 坐标与右下角都按 int 截断——错法是用浮点比较，边界上多/少一个像素就会改变结论；
 * 3. pin 态与不可见要跳过——隐藏的小窗不该把空白处判成"在小窗内"（否则永远收不了窗口）。
 *
 * fixture 值取自真机量级（1080×2340，小窗 `bounds=[100,300][800,1200]`、`scale=0.8`）。
 */
class FreeformBlankTestTest {

    private fun win(
        left: Int = 100,
        top: Int = 300,
        width: Int = 700,
        height: Int = 900,
        scale: Float = 0.8f,
        pinned: Boolean = false,
        visible: Boolean = true,
    ) = FreeformBlankTest.Window(left, top, width, height, scale, pinned, visible)

    @Test
    fun pointInsideScaledWindowIsNotBlank() {
        // right = 100 + (700*0.8)=660 → 760；bottom = 300 + (900*0.8)=720 → 1020
        assertTrue(
            FreeformBlankTest.isTouchedInFreeformWindow(
                500f, 600f, desktopModeActive = false, windowsInZOrder = listOf(win())
            )
        )
        assertFalse(
            FreeformBlankTest.isBlank(
                500f, 600f, desktopModeActive = false, windowsInZOrder = listOf(win())
            )
        )
    }

    @Test
    fun pointOutsideIsBlank() {
        assertTrue(
            FreeformBlankTest.isBlank(
                50f, 50f, desktopModeActive = false, windowsInZOrder = listOf(win())
            )
        )
    }

    @Test
    fun leftTopAreNotScaled() {
        // scale=0.5 ⇒ right=100+350=450, bottom=300+450=750。
        // (120,320) 在缩放后的矩形内；(60,320) 在 left 之外 ⇒ 空白。
        // 若误把 left 也乘（50），(60,320) 会被判成"在里面"——本用例就是守这条。
        val half = win(scale = 0.5f)
        assertTrue(
            FreeformBlankTest.isTouchedInFreeformWindow(120f, 320f, false, listOf(half))
        )
        assertFalse(
            FreeformBlankTest.isTouchedInFreeformWindow(60f, 320f, false, listOf(half))
        )
    }

    @Test
    fun scaledEdgesAreIntegerTruncated() {
        // width=701, scale=0.5 ⇒ 350.5 ⇒ 截断 350 ⇒ right=450。
        // x=449.9 在矩形内；x=450.1 不在（浮点比较会把 450.5 当成仍在里面）。
        val odd = win(width = 701, scale = 0.5f)
        assertTrue(FreeformBlankTest.isTouchedInFreeformWindow(449.9f, 500f, false, listOf(odd)))
        assertFalse(FreeformBlankTest.isTouchedInFreeformWindow(450.1f, 500f, false, listOf(odd)))
    }

    @Test
    fun pinnedWindowIsSkipped() {
        assertFalse(
            FreeformBlankTest.isTouchedInFreeformWindow(
                500f, 600f, desktopModeActive = false, windowsInZOrder = listOf(win(pinned = true))
            )
        )
    }

    @Test
    fun pinnedIconAreaIsNotBlankForUs() {
        // 2026-10-25 乱跳修复：点挂起图标（pin 过渡后 ROM `setBounds` 的图标矩形）必须穿透
        // 给 ROM 原生展开链 ⇒ isBlank 要说"非空白"；移植的那个仍按 ROM 语义跳过 pinned（不判"在里面"）。
        val pin = win(pinned = true)
        assertFalse(FreeformBlankTest.isBlank(500f, 600f, false, listOf(pin)))
        assertFalse(FreeformBlankTest.isTouchedInFreeformWindow(500f, 600f, false, listOf(pin)))
    }

    @Test
    fun pinnedOutsideIconStillBlank() {
        // 图标矩形之外仍是空白（若有正常态小窗在场，这片区域的空白动作照常工作）。
        assertTrue(FreeformBlankTest.isBlank(50f, 50f, false, listOf(win(pinned = true))))
    }

    @Test
    fun invisibleWindowIsSkipped() {
        assertFalse(
            FreeformBlankTest.isTouchedInFreeformWindow(
                500f, 600f, desktopModeActive = false, windowsInZOrder = listOf(win(visible = false))
            )
        )
    }

    @Test
    fun anyEligibleWindowCounts() {
        val pinned = win(pinned = true)
        val hidden = win(visible = false)
        val real = win(left = 800, top = 1500, width = 200, height = 300, scale = 1f)
        assertTrue(
            FreeformBlankTest.isTouchedInFreeformWindow(
                850f, 1600f, desktopModeActive = false, windowsInZOrder = listOf(pinned, hidden, real)
            )
        )
    }

    @Test
    fun desktopModeIsNeverBlankForUs() {
        // ROM 原语义：桌面模式下"不在小窗内"；我们的动作层在桌面模式不动作 ⇒ isBlank 恒 false
        assertFalse(
            FreeformBlankTest.isBlank(
                10f, 10f, desktopModeActive = true, windowsInZOrder = listOf(win())
            )
        )
        // 忠实移植的那个仍然按原语义返回 false（"不在小窗内"）
        assertFalse(
            FreeformBlankTest.isTouchedInFreeformWindow(
                10f, 10f, desktopModeActive = true, windowsInZOrder = listOf(win())
            )
        )
    }

    @Test
    fun emptyWindowListIsBlank() {
        assertTrue(
            FreeformBlankTest.isBlank(5f, 5f, desktopModeActive = false, windowsInZOrder = emptyList())
        )
    }
}
