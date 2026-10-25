package com.lsp.hypersidebar.ui.fan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组展开锚定几何单测（C6，计划 §B3 定案：子项锚定原组图标中心——几何连续、
 * 无整条重居中平移；仍走出屏钳制）。
 */
class FanGeometryAnchorTest {

    private val density = 3f
    private val screenSize = IntSize(1080, 2400)
    // 真实默认档（32dp；FanConfig() 的 48dp 在 1080 宽下 6 格根栏溢出，会混入钳制效应）
    private val config = FanConfig(iconSizeDp = 32f)
    private val apps = List(11) { FanAppInfo(packageName = "pkg$it") }

    /** 6 格根栏（组在其中）与 3 子项展开栏的宽度差 = 锚定偏移来源 */
    private fun barCenter(g: FanGeometry): Float = g.quickBarX + run {
        val q = g.quickIconSize * density
        val w = g.quickApps.size * q + (g.quickApps.size - 1) * q * 0.35f + q * 0.5f * 2f
        w / 2f
    }

    @Test
    fun `anchored expanded bar centers on given x`() {
        val quick6 = List(6) { FanAppInfo(packageName = "q$it") }
        val quick3 = List(3) { FanAppInfo(packageName = "s$it") }
        val anchor = Offset(540f, 1200f)
        val root = computeFanGeometry(anchor, screenSize, apps, quick6, config, density, false)
        // 组图标在第 4 格（idx=3）：中心 = quickBarX + pad + 3*(icon+spacing) + icon/2
        val q = root.quickIconSize * density
        val groupCenterX = root.quickBarX + q * 0.5f + 3 * (q + q * 0.35f) + q / 2f

        val expanded = computeFanGeometry(
            anchor, screenSize, apps, quick3, config, density, false,
            quickBarCenterX = groupCenterX
        )
        // 锚定生效：展开栏中心=组图标中心（钳制不介入时精确等值；
        // 「与默认路径可区分」由 default-path 测试间接覆盖，不做脆弱的差值断言）
        assertEquals(groupCenterX, barCenter(expanded), 0.5f)
    }

    @Test
    fun `anchored bar clamped to screen when group near edge`() {
        val quick3 = List(3) { FanAppInfo(packageName = "s$it") }
        val anchor = Offset(540f, 1200f)
        // 组在第 6 位靠右缘：锚点中心超出屏 ⇒ coerceIn 钳回屏内（右边界=width-bar-pad）
        val tooFar = 1500f
        val g = computeFanGeometry(
            anchor, screenSize, apps, quick3, config, density, false,
            quickBarCenterX = tooFar
        )
        val q = g.quickIconSize * density
        val pad = q * 0.5f
        val barWidth = 3 * q + 2 * q * 0.35f + pad * 2f
        assertTrue(g.quickBarX + barWidth <= screenSize.width + 0.5f)
        assertTrue(g.quickBarX >= pad - 0.5f)
    }

    @Test
    fun `default path unchanged when anchor null`() {
        val quick3 = List(3) { FanAppInfo(packageName = "s$it") }
        val anchor = Offset(540f, 1200f)
        val a = computeFanGeometry(anchor, screenSize, apps, quick3, config, density, false)
        val b = computeFanGeometry(
            anchor, screenSize, apps, quick3, config, density, false, quickBarCenterX = null
        )
        assertEquals(a.quickBarX, b.quickBarX, 0.001f)
    }
}
