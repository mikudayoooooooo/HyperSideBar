package com.lsp.hypersidebar.ui.fan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖屏边缘（侧滑停顿）档的几何契约：**张角恒为配置值、以锚点水平轴对称**。
 *
 * 2026-10-21 用户实测反馈：靠触发带 [H/3, 2H/3] 的上下沿呼出时，扇形被"房间自适应"
 * 削掉一侧、歪向另一侧，看起来就是"靠上/靠下留了一段空白"；极端半径/图标配置下连带中部
 * 也会出现。触发带本就限制在屏中部 ⇒ 自适应没有必要，改为固定张角 + 快捷栏避让。
 */
class FanGeometryPortraitTest {

    private val density = 3f
    private val screenSize = IntSize(1080, 2400)
    private val apps = List(11) { FanAppInfo(packageName = "pkg$it") }
    private val quickApps = List(3) { FanAppInfo(packageName = "quick$it") }

    private fun compute(
        anchorY: Float,
        config: FanConfig = FanConfig(),
        quick: List<FanAppInfo> = quickApps,
        isLandscape: Boolean = false,
        anchorX: Float = 0f
    ): FanGeometry = computeFanGeometry(
        anchor = Offset(anchorX, anchorY),
        screenSize = screenSize,
        apps = apps,
        quickApps = quick,
        config = config,
        density = density,
        isLandscape = isLandscape
    )

    /** 触发带 [H/3, 2H/3] 上的任意高度都必须是同一个对称张角（旧实现在下沿只剩 ~128°）。 */
    @Test
    fun `portrait span stays symmetric across the whole trigger band`() {
        val halfSpan = FanConfig().defaultSpanAngle / 2f
        var y = screenSize.height / 3f
        while (y <= screenSize.height * 2f / 3f) {
            val g = compute(y)
            assertEquals("anchorY=$y", FanConfig().defaultSpanAngle, g.spanAngle, 0.001f)
            assertEquals("anchorY=$y", -halfSpan, g.startAngle, 0.001f)
            assertEquals("anchorY=$y", halfSpan, g.endAngle, 0.001f)
            y += 50f
        }
    }

    /** 极端设置（半径滑条上限 220dp + 图标上限 80dp）在带两端同样是配置张角。 */
    @Test
    fun `portrait span survives extreme radius and icon settings`() {
        val config = FanConfig(outerRadiusDp = 220f, iconSizeDp = 80f)
        val halfSpan = config.defaultSpanAngle / 2f
        listOf(800f, 1200f, 1600f).forEach { y ->
            val g = compute(y, config)
            assertEquals("anchorY=$y", config.outerRadiusDp * density, g.outerRadius, 0.001f)
            assertEquals("anchorY=$y", config.defaultSpanAngle, g.spanAngle, 0.001f)
            assertEquals("anchorY=$y", -halfSpan, g.startAngle, 0.001f)
        }
    }

    /** 唯一保留的收窄是对称的图标防越界上限：两侧同角，且没有图标落到锚点后方。 */
    @Test
    fun `portrait shrink from icon cap stays symmetric`() {
        val config = FanConfig(outerRadiusDp = 110f, iconSizeDp = 80f)
        val g = compute(1200f, config)

        assertEquals(-g.spanAngle / 2f, g.startAngle, 0.001f)
        assertEquals(g.spanAngle / 2f, g.endAngle, 0.001f)
        assertTrue("图标不得越过锚点后方", g.items.all { it.centerX > g.anchor.x })
    }

    /** 左右两侧同构：左边缘镜像后张角与锚点距离一致。 */
    @Test
    fun `portrait left and right edges mirror`() {
        val right = compute(1200f, anchorX = 0f)
        val left = compute(1200f, anchorX = screenSize.width.toFloat())

        assertEquals(FanDirection.RIGHT, right.direction)
        assertEquals(FanDirection.LEFT, left.direction)
        assertEquals(right.spanAngle, left.spanAngle, 0.001f)
        assertEquals(-right.startAngle, left.endAngle - 180f, 0.001f)
    }

    /** 带中部默认配置：快捷栏仍在扇形下方（PRD"快捷方式入口在半圆的下面"）。 */
    @Test
    fun `quick bar stays below the fan in the middle of the band`() {
        val g = compute(1200f)
        val fanBottom = g.anchor.y + g.outerRadius * sinDeg(g.endAngle)
        assertTrue("快捷栏应在扇形下缘之下", g.quickBarY >= fanBottom)
    }

    /** 带下沿 + 大半径/大图标：下方放不下 ⇒ 整条翻到扇形上缘之上（不再靠削张角腾地方）。 */
    @Test
    fun `quick bar flips above the fan when below does not fit`() {
        val config = FanConfig(outerRadiusDp = 220f, iconSizeDp = 80f)
        val g = compute(1600f, config)
        val fanTop = g.anchor.y + g.outerRadius * sinDeg(g.startAngle)

        assertTrue("快捷栏应翻到扇形上缘之上", g.quickBarY + quickBarHeight(g) <= fanTop)
        assertTrue("翻上去后仍须留在屏内", g.quickBarY >= 0f)
    }

    /** 横屏口径未变：圆心贴屏顶 ⇒ 仍按上沿房间倾斜（上侧收窄、预算补到下方）。 */
    @Test
    fun `landscape still tilts away from the top edge`() {
        val g = compute(56f * density, isLandscape = true, anchorX = 0f)
        val up = -g.startAngle
        val dn = g.endAngle
        assertTrue("上侧应被屏顶收窄（up=$up, dn=$dn）", up < dn)
        assertTrue("总角不应超过配置值", up + dn <= FanConfig().landscapeSpanAngle + 0.001f)
    }

    private fun quickBarHeight(g: FanGeometry): Float =
        g.quickIconSize * density * 2f

    private fun sinDeg(deg: Float): Float =
        Math.sin(Math.toRadians(deg.toDouble())).toFloat()
}
