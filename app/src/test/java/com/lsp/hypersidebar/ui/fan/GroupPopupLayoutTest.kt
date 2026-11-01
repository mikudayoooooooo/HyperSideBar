package com.lsp.hypersidebar.ui.fan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.lsp.hypersidebar.prefs.LayoutDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组竖向弹层的纯几何单测（2026-10-31，用户拍板"竖向弹出 + 背板"）。
 *
 * 弹层位置决定"手指从组图标往上/往下够一次就能点到子项"，错一档就是点不中或点错，
 * 而这段算术又完全在宿主里摸不到——所以把它提成 [GroupPopupLayout] 并在这里锁死：
 * 上下判定、尺寸随项数线性、子项中心竖列对齐、出屏钳制、命中/收层边界。
 */
class GroupPopupLayoutTest {

    private val density = 3f

    private fun geom(anchorY: Float = 1200f, quickApps: Int = 3): FanGeometry =
        computeFanGeometry(
            anchor = Offset(0f, anchorY),
            screenSize = IntSize(1080, 2400),
            apps = List(8) { FanAppInfo(packageName = "pkg$it") },
            quickApps = List(quickApps) { FanAppInfo(packageName = "quick$it") },
            config = FanConfig(),
            density = density,
            isLandscape = false,
            cornerAnchor = false
        )

    @Test
    fun `second child aligns with the group icon and the column sits to its right`() {
        val g = geom(quickApps = 3)
        val p = GroupPopupLayout.compute(g, 1, 3, density)
        val q = g.quickIconSize * density
        val step = q * (1f + QUICK_BAR_GAP_RATIO)
        val groupCx = g.quickBarX + 0.5f * q + 1 * step + 0.5f * q
        val groupCy = g.quickBarY + 0.5f * q + 0.5f * q

        assertEquals(1, p.anchorRow)
        // 第二子项与组图标同高（弹层因此横跨快捷栏）
        assertEquals("第二子项须与组图标同高", groupCy, p.children[1].second, 0.01f)
        // 左缘 = 组图标中心 + 0.35q（让开组图标）
        assertEquals(
            groupCx + GroupPopupLayout.ALIGN_OFFSET_RATIO * q,
            p.x,
            0.01f
        )
        assertTrue("弹层须跨过快捷栏上下", p.y < g.quickBarY && p.y + p.height > g.quickBarY)
    }

    @Test
    fun `popup mirrors to the other side when the group sits on the right half`() {
        val g = geom(quickApps = 6)
        val q = g.quickIconSize * density
        val step = q * (1f + QUICK_BAR_GAP_RATIO)
        fun centerX(i: Int) = g.quickBarX + 0.5f * q + i * step + 0.5f * q
        val rightIdx = (0 until 6).first { centerX(it) > g.windowSize.width / 2f }
        val p = GroupPopupLayout.compute(g, rightIdx, 3, density)
        val expected = centerX(rightIdx) - GroupPopupLayout.ALIGN_OFFSET_RATIO * q - p.width
        assertEquals("右半边应向左镜像展开", expected, p.x, 0.01f)
        assertTrue("镜像后不得越出屏幕", p.x >= 0f)
    }

    @Test
    fun `single child uses itself as the anchor row`() {
        val p = GroupPopupLayout.compute(geom(quickApps = 3), 0, 1, density)
        assertEquals(0, p.anchorRow)
        assertEquals(1, p.children.size)
    }

    @Test
    fun `size grows linearly with child count`() {
        val g = geom()
        val two = GroupPopupLayout.compute(g, 0, 2, density)
        val three = GroupPopupLayout.compute(g, 0, 3, density)
        assertEquals("宽度与项数无关", two.width, three.width, 0.01f)
        assertEquals("高度 = 项数×图标 + 间距 + 内衬",
            three.height - two.height,
            two.iconPx + two.gapPx,
            0.01f
        )
        assertEquals(3, three.children.size)
        // 图标按快捷栏图标缩放（层级：子项 < 组图标）
        assertEquals(g.quickIconSize * density * LayoutDefaults.GROUP_POPUP_ICON_SCALE, three.iconPx, 0.01f)
    }

    @Test
    fun `children are a vertical column centred on the backboard`() {
        val g = geom()
        val p = GroupPopupLayout.compute(g, 1, 3, density)
        val centreX = p.x + p.width / 2f
        p.children.forEach { assertEquals(centreX, it.first, 0.01f) }
        for (i in 1 until p.children.size) {
            assertTrue("子项须自上而下", p.children[i].second > p.children[i - 1].second)
        }
        // 相邻中心距 = 图标 + 间距
        assertEquals(
            p.iconPx + p.gapPx,
            p.children[1].second - p.children[0].second,
            0.01f
        )
    }

    @Test
    fun `popup stays inside the window horizontally`() {
        val g = geom()
        // 右端组图标：不加钳制会画到屏外
        val p = GroupPopupLayout.compute(g, 5, 3, density)
        assertTrue(p.x >= 0f)
        assertTrue(p.x + p.width <= g.windowSize.width + 0.01f)
    }

    @Test
    fun `hit index covers icons plus tolerance and nothing else`() {
        val p = GroupPopupLayout.compute(geom(), 0, 3, density)
        val tol = p.iconPx * GroupPopupLayout.HIT_TOL_RATIO
        p.children.forEachIndexed { i, c ->
            assertEquals("图标中心应命中第 $i 项", i, GroupPopupLayout.hitIndex(p, c.first, c.second))
            // 外扩容差之内仍算命中
            assertEquals(i, GroupPopupLayout.hitIndex(p, c.first + tol * 0.9f, c.second))
        }
        // 图标之间的空隙：容差（0.2 图标）刻意让相邻命中块重叠 ⇒ 空隙归属相邻子项之一，
        // 不允许出现"点不中"的死区（宁可归错一项，也不能点了没反应）
        val gapY = (p.children[0].second + p.children[1].second) / 2f
        val gapHit = GroupPopupLayout.hitIndex(p, p.x + p.width / 2f, gapY)
        assertTrue("图标间空隙应归属相邻子项（实际=$gapHit）", gapHit == 0 || gapHit == 1)
        // 背板之外
        assertEquals(-1, GroupPopupLayout.hitIndex(p, p.x - p.padPx * 4f, p.y))
    }

    @Test
    fun `inside accepts the backboard and its small margin only`() {
        val p = GroupPopupLayout.compute(geom(), 0, 3, density)
        assertTrue(GroupPopupLayout.inside(p, p.x + 1f, p.y + 1f))
        assertTrue(GroupPopupLayout.inside(p, p.x + p.width / 2f, p.y + p.height / 2f))
        assertFalse(GroupPopupLayout.inside(p, p.x - p.width, p.y))
        assertFalse(GroupPopupLayout.inside(p, p.x + p.width / 2f, p.y + p.height + p.padPx * 3f))
    }
}
