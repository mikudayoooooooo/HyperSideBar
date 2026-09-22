package com.lsp.hypersidebar.ui.fan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
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
 * 历史教训（0921 真机）：节点 fillMaxSize + 绝对坐标 RoundRect，胶囊外圈会多出一块直角灰矩形；
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

    private fun outlineFor(quickCount: Int): Outline {
        val g = geometry(quickCount)
        val m = requireNotNull(quickCapsuleMetrics(g, density))
        // 节点尺寸就是胶囊尺寸（FanBoard ②-b 用同一个 metrics 布局该节点）
        return capsuleShape(g, density)
            .createOutline(Size(m.width, m.height), LayoutDirection.Ltr, Density(density))
    }

    private fun roundRectOf(quickCount: Int): RoundRect =
        requireNotNull((outlineFor(quickCount) as? Outline.Rounded)?.roundRect) {
            "胶囊裁剪形状必须能被 placeWithLayer 当作轮廓取用（Outline.Rounded）"
        }

    @Test
    fun `capsule outline is node local, not window absolute`() {
        val rr = roundRectOf(5)
        // 本地坐标：从原点起，尺寸 = 节点尺寸
        assertEquals(0f, rr.left, 0.01f)
        assertEquals(0f, rr.top, 0.01f)
        assertTrue("胶囊轮廓不得落在窗口绝对坐标上（那是直角灰矩形的来源）", rr.right < 1080f)
        assertTrue(rr.bottom < 2400f)
    }

    @Test
    fun `capsule metrics match the quick bar row`() {
        val g = geometry(5)
        val q = g.quickIconSize * density
        val m = requireNotNull(quickCapsuleMetrics(g, density))

        // 宽 = 左右内边距 + 5 图标 + 4 间距；高 = 上下内边距 + 1 图标（QuickAppsBar Row 同式）
        assertEquals(2f * q * QUICK_BAR_SIDE_PAD_RATIO + 5 * q + 4 * q * QUICK_BAR_GAP_RATIO, m.width, 1f)
        assertEquals(q + 2f * q * QUICK_BAR_VERTICAL_PAD_RATIO, m.height, 1f)
        assertEquals((g.quickIconSize / 2f + 4f) * density, m.corner, 1f)

        val rr = roundRectOf(5)
        assertEquals(m.width, rr.width, 0.01f)
        assertEquals(m.height, rr.height, 0.01f)
        assertEquals(m.corner, rr.topLeftCornerRadius.x, 0.01f)
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

    @Test
    fun `progressive blur axis and falloff follow the band outward direction`() {
        // 三种呼出档都不该把渐隐轴搞反。miuix 角度约定（0.9.4 字节码实证：Left=0°/Top=90°/
        // Right=180°/Bottom=270°）＝轴向量 (-cos θ, -sin θ)，故反解出的轴必须与"从锚点指向
        // 弧带弧中点"同向；且弧缘（沿轴更远的一侧）要落在 startFraction = 模糊最强端。
        val cases = listOf(
            geom(Offset(0f, 1200f), corner = false),      // 边沿档：左边缘锚点
            geom(Offset(10f, 2390f), corner = true),      // 底角档：左下角锚点
            geom(Offset(1070f, 2390f), corner = true)     // 底角档：右下角锚点
        )
        cases.forEach { g ->
            val p = fanBandProgressiveBlur(g, density)
            val midRad = Math.toRadians((g.startAngle + g.spanAngle / 2f).toDouble())
            val axisX = -Math.cos(Math.toRadians(p.angle.toDouble()))
            val axisY = -Math.sin(Math.toRadians(p.angle.toDouble()))
            val dot = axisX * Math.cos(midRad) + axisY * Math.sin(midRad)
            assertEquals("渐隐轴必须指向弧带外侧（dot=$dot, angle=${p.angle}）", 1.0, dot, 0.01)
            assertTrue(
                "弧缘应比内缘靠外（start=${p.startFraction}, end=${p.endFraction}）",
                p.startFraction > p.endFraction
            )
            assertTrue(p.startFraction in 0f..1f && p.endFraction in 0f..1f)
        }
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