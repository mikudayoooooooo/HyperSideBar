package com.lsp.hypersidebar.ui.fan

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 扇形展开方向。
 */
enum class FanDirection {
    RIGHT,           // 屏幕左边缘触发，向右展开
    LEFT,            // 屏幕右边缘触发，向左展开
    DOWN,            // 屏幕上边缘触发，向下展开
    UP,              // 屏幕下边缘触发，向上展开
    RIGHT_DOWN,      // 屏幕左上角触发，向右下展开
    RIGHT_UP,        // 屏幕左下角触发，向右上展开
    LEFT_DOWN,       // 屏幕右上角触发，向左下展开
    LEFT_UP          // 屏幕右下角触发，向左上展开
}

/**
 * 单个图标的布局信息。
 */
data class FanItemLayout(
    val app: FanAppInfo,
    val index: Int,
    val centerX: Float,
    val centerY: Float,
    val angle: Float,
    val radius: Float,
    val isOuter: Boolean
)

/**
 * 扇形几何计算结果。
 */
data class FanGeometry(
    val anchor: Offset,
    val direction: FanDirection,
    val startAngle: Float,
    val endAngle: Float,
    val spanAngle: Float,
    val innerRadius: Float,
    val outerRadius: Float,
    val iconSize: Float,
    val quickIconSize: Float,
    val quickBarCenterX: Float,
    val quickBarCenterY: Float,
    val quickBarAngle: Float,
    val items: List<FanItemLayout>,
    val apps: List<FanAppInfo>,
    val quickApps: List<FanAppInfo>,
    /** 摇杆有效区半径（像素），触摸超出此范围则取消 */
    val activeZonePx: Float
)

/**
 * 根据锚点、屏幕尺寸和配置计算扇形几何信息。
 */
fun computeFanGeometry(
    anchor: Offset,
    screenSize: IntSize,
    apps: List<FanAppInfo>,
    quickApps: List<FanAppInfo>,
    config: FanConfig,
    density: Float
): FanGeometry {
    val width = screenSize.width.toFloat()
    val height = screenSize.height.toFloat()

    // ── 1. 确定展开方向 ──
    val direction = chooseDirection(anchor.x, width - anchor.x, anchor.y, height - anchor.y)

    // ── 2. 初始角度范围 ──
    val span = config.defaultSpanAngle
    val (initStart, initEnd) = when (direction) {
        FanDirection.RIGHT     -> -span / 2f to span / 2f
        FanDirection.LEFT      -> 180f - span / 2f to 180f + span / 2f
        FanDirection.DOWN      -> 90f  - span / 2f to 90f  + span / 2f
        FanDirection.UP        -> 270f - span / 2f to 270f + span / 2f
        FanDirection.RIGHT_DOWN -> 0f   to 90f
        FanDirection.RIGHT_UP   -> -90f to 0f
        FanDirection.LEFT_DOWN  -> 90f  to 180f
        FanDirection.LEFT_UP    -> 180f to 270f
    }

    // ── 3. 先算最大可用半径，再用于角度裁剪 ──
    val iconPx = config.iconSizeDp * density
    val maxRadius = computeMaxRadius(anchor, width, height, initStart, initEnd, iconPx)
    val effectiveRadius = min(config.outerRadiusDp * density, maxRadius)

    // ── 4. 用实际半径裁剪角度 ──
    val (startAngle, endAngle) = clampAngleRange(
        anchor, width, height, initStart, initEnd, effectiveRadius, iconPx
    )
    val spanAngle = endAngle - startAngle

    // ── 5. 双圈半径计算（紧凑布局）──
    var outerRadius = effectiveRadius
    var innerRadius = config.innerRadiusDp * density

    // 保证双圈有合理间距：内圈 = 外圈的 75%，最小 50dp
    if (innerRadius > outerRadius * 0.8f || innerRadius < 50f * density) {
        innerRadius = outerRadius * 0.75f
    }
    // 如果外圈太小，回退单圈
    var useDualRing = config.useDualRing
    if (outerRadius < config.minRadiusDp * density) {
        useDualRing = false
        innerRadius = outerRadius * 0.6f
    }

    // 动态缩小图标以适应弧长
    var iconSizeDp = config.iconSizeDp
    if (apps.size > 1 && spanAngle > 0f) {
        iconSizeDp = fitIconSize(apps.size, spanAngle, outerRadius, innerRadius, useDualRing, iconSizeDp, density)
    }
    val finalIconPx = iconSizeDp * density

    // ── 6. 布局图标 ──
    val items = layoutFanItems(
        apps = apps,
        anchor = anchor,
        startAngle = startAngle,
        spanAngle = spanAngle,
        innerRadius = innerRadius,
        outerRadius = outerRadius,
        useDualRing = useDualRing
    )

    // ── 7. 快捷栏位置：沿扇形角平分线方向，紧贴外弧外侧 ──
    val quickIconPx = config.quickIconSizeDp * density
    val bisectorAngle = (startAngle + endAngle) / 2f
    // 沿展开方向，外弧外侧留一个图标间距
    val barDistance = outerRadius + quickIconPx * 0.8f
    val rawBarX = anchor.x + barDistance * cos(Math.toRadians(bisectorAngle.toDouble())).toFloat()
    val rawBarY = anchor.y + barDistance * sin(Math.toRadians(bisectorAngle.toDouble())).toFloat()
    val barMargin = quickIconPx * 0.5f
    val quickBarCenterX = rawBarX.coerceIn(barMargin, width - barMargin)
    val quickBarCenterY = rawBarY.coerceIn(barMargin, height - barMargin)

    // ── 8. 摇杆有效区 ──
    val activeZonePx = config.activeZoneDp * density

    return FanGeometry(
        anchor = anchor,
        direction = direction,
        startAngle = startAngle,
        endAngle = endAngle,
        spanAngle = spanAngle,
        innerRadius = innerRadius,
        outerRadius = outerRadius,
        iconSize = iconSizeDp,
        quickIconSize = config.quickIconSizeDp,
        quickBarCenterX = quickBarCenterX,
        quickBarCenterY = quickBarCenterY,
        quickBarAngle = bisectorAngle,
        items = items,
        apps = apps,
        quickApps = quickApps,
        activeZonePx = activeZonePx
    )
}

// ────────────────────────────────────────────────────
// 方向选择
// ────────────────────────────────────────────────────

private fun chooseDirection(left: Float, right: Float, top: Float, bottom: Float): FanDirection {
    val nearLeft   = left   < 120f
    val nearRight  = right  < 120f
    val nearTop    = top    < 120f
    val nearBottom = bottom < 120f

    return when {
        nearLeft  && nearTop    -> FanDirection.RIGHT_DOWN
        nearLeft  && nearBottom -> FanDirection.RIGHT_UP
        nearRight && nearTop    -> FanDirection.LEFT_DOWN
        nearRight && nearBottom -> FanDirection.LEFT_UP
        nearLeft  -> FanDirection.RIGHT
        nearRight -> FanDirection.LEFT
        nearTop   -> FanDirection.DOWN
        nearBottom -> FanDirection.UP
        right >= left && right >= top && right >= bottom -> FanDirection.RIGHT
        left  >= right && left  >= top && left  >= bottom -> FanDirection.LEFT
        bottom >= top  -> FanDirection.DOWN
        else -> FanDirection.UP
    }
}

// ────────────────────────────────────────────────────
// 最大可用半径：对扇形弧采样，取到屏幕边的最短距离
// ────────────────────────────────────────────────────

private fun computeMaxRadius(
    anchor: Offset,
    width: Float,
    height: Float,
    startAngle: Float,
    endAngle: Float,
    iconSize: Float
): Float {
    val margin = iconSize * 1.5f
    var maxR = Float.MAX_VALUE
    val step = maxOf(1f, (endAngle - startAngle) / 20f)
    var angle = startAngle

    while (angle <= endAngle) {
        val c = cos(Math.toRadians(angle.toDouble())).toFloat()
        val s = sin(Math.toRadians(angle.toDouble())).toFloat()

        val toRight  = if (c >  0.01f) (width  - margin - anchor.x) / c else Float.MAX_VALUE
        val toLeft   = if (c < -0.01f) (margin - anchor.x) / c else Float.MAX_VALUE
        val toBottom = if (s >  0.01f) (height - margin - anchor.y) / s else Float.MAX_VALUE
        val toTop    = if (s < -0.01f) (margin - anchor.y) / s else Float.MAX_VALUE

        val r = minOf(toRight, toLeft, toBottom, toTop)
        if (r > 0 && r < maxR) maxR = r
        angle += step
    }

    return if (maxR == Float.MAX_VALUE) 300f else maxR
}

// ────────────────────────────────────────────────────
// 角度裁剪：确保弧线不超出屏幕
// ────────────────────────────────────────────────────

private fun clampAngleRange(
    anchor: Offset,
    width: Float,
    height: Float,
    startAngle: Float,
    endAngle: Float,
    radius: Float,
    iconSize: Float
): Pair<Float, Float> {
    val margin = iconSize
    var s = startAngle
    var e = endAngle

    repeat(30) {
        val step = (e - s) / 12f
        if (step <= 0f) return@repeat
        var outOfBounds = false
        for (i in 0..12) {
            val a = s + step * i
            val x = anchor.x + radius * cos(Math.toRadians(a.toDouble())).toFloat()
            val y = anchor.y + radius * sin(Math.toRadians(a.toDouble())).toFloat()
            if (x < margin || x > width - margin || y < margin || y > height - margin) {
                outOfBounds = true
                break
            }
        }
        if (!outOfBounds) return@repeat
        s += 3f
        e -= 3f
    }

    // 最小 30° 兜底
    if (e - s < 30f) {
        val mid = (s + e) / 2f
        s = mid - 15f
        e = mid + 15f
    }

    return s to e
}

// ────────────────────────────────────────────────────
// 动态图标缩放：根据弧长和图标数量缩小图标
// ────────────────────────────────────────────────────

private fun fitIconSize(
    appCount: Int,
    spanAngle: Float,
    outerRadius: Float,
    innerRadius: Float,
    useDualRing: Boolean,
    maxSizeDp: Float,
    density: Float
): Float {
    val outerCount: Int
    val innerCount: Int
    if (useDualRing && appCount >= 4) {
        outerCount = (appCount * 0.6f).toInt().coerceAtLeast(2)
        innerCount = appCount - outerCount
    } else {
        outerCount = appCount
        innerCount = 0
    }

    val gapFraction = 0.2f
    var minChord = Float.MAX_VALUE

    if (outerCount > 1) {
        val midR = (innerRadius + outerRadius) / 2f
        val stepRad = Math.toRadians((spanAngle / outerCount).toDouble())
        val chord = 2f * midR * sin(stepRad / 2f).toFloat()
        minChord = minOf(minChord, chord)
    }
    if (innerCount > 1) {
        val midR = innerRadius * 0.85f
        val stepRad = Math.toRadians((spanAngle / innerCount).toDouble())
        val chord = 2f * midR * sin(stepRad / 2f).toFloat()
        minChord = minOf(minChord, chord)
    }

    val required = maxSizeDp * density * (1f + gapFraction)
    return if (minChord < required) {
        (minChord / density / (1f + gapFraction)).coerceIn(24f, maxSizeDp)
    } else {
        maxSizeDp
    }
}

// ────────────────────────────────────────────────────
// 双圈图标布局
// ────────────────────────────────────────────────────

private fun layoutFanItems(
    apps: List<FanAppInfo>,
    anchor: Offset,
    startAngle: Float,
    spanAngle: Float,
    innerRadius: Float,
    outerRadius: Float,
    useDualRing: Boolean
): List<FanItemLayout> {
    if (apps.isEmpty()) return emptyList()

    val count = apps.size
    val outerCount: Int
    val innerCount: Int

    if (useDualRing && count >= 4) {
        outerCount = (count * 0.6f).toInt().coerceAtLeast(2)
        innerCount = count - outerCount
    } else {
        outerCount = count
        innerCount = 0
    }

    // 外圈：内外半径的中点
    val outerMid = (innerRadius + outerRadius) / 2f
    // 内圈：紧贴内半径内侧
    val innerMid = innerRadius * 0.85f

    val outerStep = if (outerCount > 1) spanAngle / outerCount else 0f
    val innerStep = if (innerCount > 1) spanAngle / innerCount else 0f

    return apps.mapIndexed { index, app ->
        val isOuter = index < outerCount
        val ringIndex = if (isOuter) index else index - outerCount
        val countInRing = if (isOuter) outerCount else innerCount
        val step = if (isOuter) outerStep else innerStep

        val angle = if (countInRing > 1) {
            startAngle + step * ringIndex + step / 2f
        } else {
            startAngle + spanAngle / 2f
        }
        val radius = if (isOuter) outerMid else innerMid

        val x = anchor.x + radius * cos(Math.toRadians(angle.toDouble())).toFloat()
        val y = anchor.y + radius * sin(Math.toRadians(angle.toDouble())).toFloat()

        FanItemLayout(
            app = app,
            index = index,
            centerX = x,
            centerY = y,
            angle = angle,
            radius = radius,
            isOuter = isOuter
        )
    }
}

// ────────────────────────────────────────────────────
// 工具函数
// ────────────────────────────────────────────────────

/** 计算两点距离 */
fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/**
 * 根据触摸位置计算选中索引，返回 -1 表示未选中。
 * 使用简单的角度环绕处理，兼容所有方向。
 */
fun computeSelectedIndex(
    anchor: Offset,
    touch: Offset,
    geometry: FanGeometry,
    config: FanConfig,
    density: Float
): Int {
    val d = distance(anchor, touch)

    // 死区内不选中
    val deadZone = config.deadZoneDp * density
    if (d < deadZone) return -1

    // 超出外圈太远不选中
    if (d > geometry.outerRadius + geometry.iconSize * density * 1.5f) return -1

    // 计算触摸角度
    val touchDeg = Math.toDegrees(
        atan2((touch.y - anchor.y).toDouble(), (touch.x - anchor.x).toDouble())
    ).toFloat()

    // 映射到 [startAngle, startAngle + 360) 空间
    val start = geometry.startAngle
    val end = start + geometry.spanAngle
    var ta = touchDeg
    while (ta < start) ta += 360f
    while (ta > start + 360f) ta -= 360f
    if (ta < start || ta > end) return -1

    // 找角度最接近的图标
    var bestIndex = -1
    var bestDiff = Float.MAX_VALUE
    geometry.items.forEachIndexed { index, item ->
        var ia = item.angle
        while (ia < start) ia += 360f
        while (ia > start + 360f) ia -= 360f
        val diff = abs(ta - ia)
        if (diff < bestDiff) {
            bestDiff = diff
            bestIndex = index
        }
    }

    return bestIndex
}
