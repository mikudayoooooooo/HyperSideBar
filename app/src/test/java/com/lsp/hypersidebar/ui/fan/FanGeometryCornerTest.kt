package com.lsp.hypersidebar.ui.fan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanGeometryCornerTest {

    private val density = 3f
    private val screenSize = IntSize(1080, 2400)
    private val config = FanConfig()
    private val apps = List(11) { FanAppInfo(packageName = "pkg$it") }
    private val quickApps = List(3) { FanAppInfo(packageName = "quick$it") }

    private fun compute(
        anchor: Offset,
        cornerAnchor: Boolean,
        config: FanConfig = this.config
    ): FanGeometry = computeFanGeometry(
        anchor = anchor,
        screenSize = screenSize,
        apps = apps,
        quickApps = quickApps,
        config = config,
        density = density,
        isLandscape = false,
        cornerAnchor = cornerAnchor
    )

    @Test
    fun `right corner uses fixed upper quadrant geometry`() {
        val anchor = Offset(10f, 2390f)
        val geometry = compute(anchor, cornerAnchor = true)

        assertEquals(FanDirection.RIGHT, geometry.direction)
        assertEquals(-84f, geometry.startAngle, 0.001f)
        assertEquals(-12f, geometry.endAngle, 0.001f)
        assertEquals(CORNER_SPAN_DEG, geometry.spanAngle, 0.001f)
        assertEquals(config.cornerOuterRadiusDp * density, geometry.outerRadius, 0.001f)
        assertEquals(config.cornerInnerRadiusDp * density, geometry.innerRadius, 0.001f)
    }

    @Test
    fun `left corner uses fixed upper quadrant geometry`() {
        val anchor = Offset(1070f, 2390f)
        val geometry = compute(anchor, cornerAnchor = true)

        assertEquals(FanDirection.LEFT, geometry.direction)
        assertEquals(192f, geometry.startAngle, 0.001f)
        assertEquals(264f, geometry.endAngle, 0.001f)
        assertEquals(CORNER_SPAN_DEG, geometry.spanAngle, 0.001f)
        assertEquals(config.cornerOuterRadiusDp * density, geometry.outerRadius, 0.001f)
        assertEquals(config.cornerInnerRadiusDp * density, geometry.innerRadius, 0.001f)
    }

    @Test
    fun `corner keeps every app and places icons above anchor`() {
        val geometry = compute(Offset(10f, 2390f), cornerAnchor = true)

        assertEquals(apps.size, geometry.items.size)
        assertEquals(config.cornerMaxAppsOuter, geometry.items.count { it.isOuter })
        assertEquals(config.cornerMaxAppsInner, geometry.items.count { !it.isOuter })
        assertTrue(geometry.items.all { it.centerY < geometry.anchor.y })
        assertTrue(geometry.items.all { it.centerX > geometry.anchor.x })
        assertTrue(geometry.iconSize <= config.cornerIconSizeDp)
    }

    /** 底角样式键与竖屏完全独立：改 corner* 不影响竖屏几何，反之亦然。 */
    @Test
    fun `corner style keys are independent from portrait`() {
        val custom = FanConfig(
            outerRadiusDp = 150f,
            innerRadiusDp = 110f,
            iconSizeDp = 48f,
            maxAppsOuter = 7,
            maxAppsInner = 4,
            cornerIconSizeDp = 60f,
            cornerInnerRadiusDp = 120f,
            cornerOuterRadiusDp = 200f,
            cornerMaxAppsOuter = 5,
            cornerMaxAppsInner = 2
        )

        val corner = compute(Offset(10f, 2390f), cornerAnchor = true, config = custom)
        assertEquals(200f * density, corner.outerRadius, 0.001f)
        assertEquals(120f * density, corner.innerRadius, 0.001f)
        assertEquals(5, corner.items.count { it.isOuter })
        assertTrue(corner.iconSize <= 60f)

        val portrait = compute(Offset(0f, 1200f), cornerAnchor = false, config = custom)
        assertEquals(150f * density, portrait.outerRadius, 0.001f)
        assertEquals(110f * density, portrait.innerRadius, 0.001f)
        assertEquals(7, portrait.items.count { it.isOuter })
    }

    @Test
    fun `corner quick bar sits above fan upper edge`() {
        val geometry = compute(Offset(10f, 2390f), cornerAnchor = true)
        val upperEdgeY = geometry.anchor.y + geometry.outerRadius * -0.9945219f

        assertTrue(geometry.quickBarY < upperEdgeY)
    }

    @Test
    fun `corner geometry selects first outer item at its center`() {
        val geometry = compute(Offset(10f, 2390f), cornerAnchor = true)
        val first = geometry.items.first()

        val selected = computeSelectedIndex(
            anchor = geometry.anchor,
            touch = Offset(first.centerX, first.centerY),
            geometry = geometry,
            config = config,
            density = density
        )

        assertEquals(0, selected)
    }
}