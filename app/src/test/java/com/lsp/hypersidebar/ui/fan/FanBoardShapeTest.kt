package com.lsp.hypersidebar.ui.fan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷栏胶囊的裁剪契约：miuix `textureBlur(shape = …)` 经 `ShapeProvider` 按**节点尺寸**求值，
 * 再交给 `placeWithLayer(clip = true, shape = …)` 裁剪，所以
 *
 *  ① 胶囊必须有自己的、尺寸自洽的节点（`quickCapsuleMetrics` 是节点尺寸与 ③ 描边框线的唯一
 *     来源），形状必须是**节点本地坐标**（0,0 → w,h）；
 *  ② 该尺寸必须与 `QuickAppsBar` Row 的真实尺寸、`computeQuickAppCenter` 的步进同源。
 *
 * 历史教训（0921 真机）：节点 fillMaxSize + 绝对坐标形状会在胶囊外圈多出一块直角灰矩形；
 * 与 Outline 是 Generic 还是 Rounded 无关，故本测试只钉"尺寸/坐标口径"，不钉 Outline 子类型。
 */
class FanBoardShapeTest {

    private val density = 3f

    private fun geometry(quickCount: Int) = computeFanGeometry(
        anchor = Offset(10f, 2390f),
        screenSize = IntSize(1080, 2400),
        apps = List(11) { FanAppInfo(packageName = "pkg$it") },
        quickApps = List(quickCount) { FanAppInfo(packageName = "quick$it") },
        config = FanConfig(),
        density = density,
        isLandscape = false,
        cornerAnchor = true
    )

    // 板形（弧带 ∪ 胶囊的 union path）本身依赖 android.graphics.Path，纯 JVM 单测建不出来
    // （"Method moveTo in android.graphics.Path not mocked"）⇒ 只在真机/模拟器上肉眼验收：
    // 2026-10-31 在 AVD 上实测同一块板内"弧带内缘"与"胶囊内腔"取到了**完全相同**的像素
    // （231,231,241），此前胶囊那一层是空的（只剩描边），这就是"上下两端颜色断层/分层"。

    @Test
    fun `capsule metrics match the quick bar row`() {
        val g = geometry(5)
        val q = g.quickIconSize * density
        val m = requireNotNull(quickCapsuleMetrics(g, density))

        // 宽 = 左右内边距 + 5 图标 + 4 间距；高 = 上下内边距 + 1 图标（QuickAppsBar Row 同式）
        assertEquals(2f * q * QUICK_BAR_SIDE_PAD_RATIO + 5 * q + 4 * q * QUICK_BAR_GAP_RATIO, m.width, 1f)
        assertEquals(q + 2f * q * QUICK_BAR_VERTICAL_PAD_RATIO, m.height, 1f)
        assertEquals((g.quickIconSize / 2f + 4f) * density, m.corner, 1f)

    }

    @Test
    fun `capsule metrics cap at six icons and vanish when empty`() {
        val five = requireNotNull(quickCapsuleMetrics(geometry(5), density))
        val six = requireNotNull(quickCapsuleMetrics(geometry(6), density))
        val ten = requireNotNull(quickCapsuleMetrics(geometry(10), density))
        assertEquals(six.width, ten.width, 0.01f)
        assertTrue(five.width < six.width)
        assertEquals(null, quickCapsuleMetrics(geometry(0), density))
    }

    private fun geom(anchor: Offset, corner: Boolean) = computeFanGeometry(
        anchor = anchor,
        screenSize = IntSize(1080, 2400),
        apps = List(11) { FanAppInfo(packageName = "pkg$it") },
        quickApps = List(5) { FanAppInfo(packageName = "quick$it") },
        config = FanConfig(),
        density = density,
        isLandscape = false,
        cornerAnchor = corner
    )

    @Test
    fun `corner band radial edges land on the screen axes`() {
        // 底角档：楔形起边下取整、止边上取整到 90° 轴（即补满该角象限），再各越 10° 供窗口裁齐
        assertEquals(-100f to 110f, bandArc(geom(Offset(10f, 2390f), corner = true)))
        assertEquals(170f to 110f, bandArc(geom(Offset(1070f, 2390f), corner = true)))
    }

    @Test
    fun `edge band radial edges reach the top and bottom screen edges`() {
        // 边沿档（锚点在边中段）同一口径：得到跨上下的半环，磨砂区明显变大（0921 用户选定）
        val g = geom(Offset(0f, 1200f), corner = false)
        val (start, span) = bandArc(g)
        assertTrue("楔形必须被包住: wedge=[${g.startAngle},${g.endAngle}] band=[$start,${start + span}]",
            start <= g.startAngle && start + span >= g.endAngle)
        // 去掉两侧各 10° 越量后，两条边必须正好落在 90° 轴上
        assertEquals(0f, (start + 10f) % 90f, 0.001f)
        assertEquals(0f, (start + span - 10f) % 90f, 0.001f)
    }
}