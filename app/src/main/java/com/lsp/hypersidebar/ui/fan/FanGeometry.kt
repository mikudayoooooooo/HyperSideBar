package com.lsp.hypersidebar.ui.fan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class FanDirection {
    RIGHT,
    LEFT
}

data class FanItemLayout(
    val app: FanAppInfo,
    val index: Int,
    val centerX: Float,
    val centerY: Float,
    val angle: Float,
    val radius: Float,
    val isOuter: Boolean
)

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
    val quickBarX: Float,
    val quickBarY: Float,
    val quickBarVertical: Boolean,
    val items: List<FanItemLayout>,
    val apps: List<FanAppInfo>,
    val quickApps: List<FanAppInfo>,
    val activeZonePx: Float,
    val isLandscape: Boolean
)

/** 屏幕安全边距（原 computeMaxRadius 的 48dp×1.5）。 */
private const val SCREEN_MARGIN_DP = 72f

/**
 * 扇形几何（实测轮五定稿）：形状恒定 + 圆心钳制。
 *
 * - 展开角固定（竖 150°/横 75°），以锚点水平轴上下对称——不再向空旷侧偏移（centerOffset 已删），
 *   不再按位置压缩角度/收缩半径（computeValidAngleRange/computeMaxRadius 已删）：
 *   同一配置在任何停顿高度下扇形完全一致（PRD"展开角度相对固定"）
 * - 保证不超屏的手段是平移圆心而非变形扇形：anchorY 钳入
 *   [margin+半高, H−margin−半高−快捷栏高]；仅当可行带不存在（极小屏/横屏大半径）时对半径做
 *   一次性确定性收缩（结果只依赖屏幕与配置，不依赖呼出位置），再重新钳制
 */
fun computeFanGeometry(
    anchor: Offset,
    screenSize: IntSize,
    apps: List<FanAppInfo>,
    quickApps: List<FanAppInfo>,
    config: FanConfig,
    density: Float,
    isLandscape: Boolean
): FanGeometry {
    val width = screenSize.width.toFloat()
    val height = screenSize.height.toFloat()

    val direction = if (anchor.x <= width / 2f) FanDirection.RIGHT else FanDirection.LEFT

    val span = if (isLandscape) config.landscapeSpanAngle else config.defaultSpanAngle
    val startAngle = if (direction == FanDirection.RIGHT) -span / 2f else 180f - span / 2f
    val endAngle = startAngle + span
    val spanAngle = span

    val outerRadiusConfig = if (isLandscape) config.landscapeOuterRadiusDp else config.outerRadiusDp
    val innerRadiusConfig = if (isLandscape) config.landscapeInnerRadiusDp else config.innerRadiusDp
    val marginPx = SCREEN_MARGIN_DP * density

    // 快捷栏度量（与锚点无关，先算，供圆心钳制预留下方空间）
    val quickList = quickApps.take(6)
    val quickIconPx = config.quickIconSizeDp * density
    val quickSpacing = quickIconPx * 0.35f
    val barPadding = quickIconPx * 0.5f
    val barContentWidth = if (quickList.isNotEmpty()) {
        quickList.size * quickIconPx + (quickList.size - 1) * quickSpacing + barPadding * 2
    } else 0f
    val barContentHeight = quickIconPx + barPadding * 2
    val barBlockPx = quickIconPx * 0.6f + barContentHeight   // barGap + 栏高

    val maxAbsSin = abs(sin(Math.toRadians((span / 2f).toDouble())).toFloat()).coerceAtLeast(0.01f)
    var outerRadius = outerRadiusConfig * density
    var halfV = outerRadius * maxAbsSin
    var bandMin = marginPx + halfV
    var bandMax = height - marginPx - halfV - barBlockPx
    if (bandMin > bandMax) {
        val availableHalf = ((height - marginPx * 2f - barBlockPx) / 2f).coerceAtLeast(60f)
        outerRadius = minOf(outerRadius, availableHalf / maxAbsSin)
        halfV = outerRadius * maxAbsSin
        bandMin = marginPx + halfV
        bandMax = height - marginPx - halfV - barBlockPx
    }
    val settledAnchor = Offset(anchor.x, anchor.y.coerceIn(bandMin, bandMax))

    var innerRadius = innerRadiusConfig * density
    if (innerRadius > outerRadius * 0.8f || innerRadius < 50f * density) {
        innerRadius = outerRadius * 0.75f
    }
    if (outerRadius < config.minRadiusDp * density) {
        innerRadius = outerRadius * 0.6f
    }

    val appCount = apps.size
    val outerCount = minOf(if (isLandscape) config.landscapeMaxAppsOuter else config.maxAppsOuter, appCount)
    val innerCount = (appCount - outerCount).coerceIn(0, if (isLandscape) config.landscapeMaxAppsInner else config.maxAppsInner)

    var iconSizeDp = if (isLandscape) config.landscapeIconSizeDp else config.iconSizeDp
    if (appCount > 1 && spanAngle > 0f) {
        iconSizeDp = fitIconSize(outerCount, innerCount, spanAngle, outerRadius, innerRadius, iconSizeDp, density)
    }

    val items = layoutFanItems(
        apps = apps, outerCount = outerCount, innerCount = innerCount,
        anchor = settledAnchor, startAngle = startAngle, spanAngle = spanAngle,
        innerRadius = innerRadius, outerRadius = outerRadius
    )

    // 快捷栏固定在扇形下方（PRD"快捷方式入口在半圆的下面"）；圆心已钳制 ⇒ fanBottom
    // 天然在屏内，此处 coerce 仅作防御。扫描极值不能用端点：左向扇形 startAngle 的 sin
    // 为正，端点命名易反（见 sweepExtremes 注释历史）
    val (_, maxSin, minCos, maxCos) = sweepExtremes(startAngle, endAngle)
    val fanLeftX = settledAnchor.x + outerRadius * minCos
    val fanRightX = settledAnchor.x + outerRadius * maxCos
    val baseY = settledAnchor.y + outerRadius * maxSin + quickIconPx * 0.6f
    val baseX = (fanLeftX + fanRightX) / 2f - barContentWidth / 2f

    val quickBarX = baseX.coerceIn(barPadding, (width - barContentWidth - barPadding).coerceAtLeast(barPadding))
    val quickBarY = baseY.coerceIn(barPadding, (height - barContentHeight - barPadding).coerceAtLeast(barPadding))

    // activeZone 真实控制扇形选中半径（Phase 2 语义修正）
    val activeZonePx = config.activeZoneDp * density

    return FanGeometry(
        anchor = settledAnchor,
        direction = direction,
        startAngle = startAngle,
        endAngle = endAngle,
        spanAngle = spanAngle,
        innerRadius = innerRadius,
        outerRadius = outerRadius,
        iconSize = iconSizeDp,
        quickIconSize = config.quickIconSizeDp,
        quickBarX = quickBarX,
        quickBarY = quickBarY,
        quickBarVertical = false,
        items = items,
        apps = apps,
        quickApps = quickApps,
        activeZonePx = activeZonePx,
        isLandscape = isLandscape
    )
}

/** [startAngle, endAngle] 扫描区间内 sin/cos 的极值（4° 步进采样，布局精度足够）。 */
private fun sweepExtremes(startAngle: Float, endAngle: Float): FloatArray {
    var minSin = 1f
    var maxSin = -1f
    var minCos = 1f
    var maxCos = -1f
    fun fold(angle: Float) {
        val rad = Math.toRadians(angle.toDouble())
        val s = sin(rad).toFloat()
        val c = cos(rad).toFloat()
        if (s < minSin) minSin = s
        if (s > maxSin) maxSin = s
        if (c < minCos) minCos = c
        if (c > maxCos) maxCos = c
    }
    val step = maxOf(1f, (endAngle - startAngle) / 90f)
    var a = startAngle
    while (a <= endAngle) {
        fold(a)
        a += step
    }
    fold(endAngle)   // 循环可能因步进跳过终点
    return floatArrayOf(minSin, maxSin, minCos, maxCos)
}

/** 供设置页显示"实际生效图标尺寸"：与扇形渲染同一弦长收缩逻辑（数量按用户上限取最坏情况）。 */
fun effectiveIconSizeDp(
    outerCount: Int, innerCount: Int, spanAngleDeg: Float,
    outerRadiusDp: Float, innerRadiusDp: Float, targetDp: Float, density: Float
): Float = fitIconSize(
    outerCount, innerCount, spanAngleDeg,
    outerRadiusDp * density, innerRadiusDp * density, targetDp, density
)

private fun fitIconSize(
    outerCount: Int,
    innerCount: Int,
    spanAngle: Float,
    outerRadius: Float,
    innerRadius: Float,
    maxSizeDp: Float,
    density: Float
): Float {

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

private fun layoutFanItems(
    apps: List<FanAppInfo>,
    outerCount: Int,
    innerCount: Int,
    anchor: Offset,
    startAngle: Float,
    spanAngle: Float,
    innerRadius: Float,
    outerRadius: Float
): List<FanItemLayout> {
    if (apps.isEmpty()) return emptyList()

    val outerMid = (innerRadius + outerRadius) / 2f
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

fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

fun angleDiffDeg(a: Float, b: Float): Float {
    var d = abs(a - b)
    while (d > 180f) d -= 360f
    return abs(d)
}

fun computeSelectedIndex(
    anchor: Offset,
    touch: Offset,
    geometry: FanGeometry,
    config: FanConfig,
    density: Float
): Int {
    val d = distance(anchor, touch)

    val deadZone = config.deadZoneDp * density
    if (d < deadZone) return -1

    if (d > geometry.outerRadius + geometry.iconSize * density * 1.5f) return -1

    val touchDeg = Math.toDegrees(
        atan2((touch.y - anchor.y).toDouble(), (touch.x - anchor.x).toDouble())
    ).toFloat()

    val start = geometry.startAngle
    val end = start + geometry.spanAngle
    var ta = touchDeg
    while (ta < start) ta += 360f
    while (ta > start + 360f) ta -= 360f
    if (ta < start || ta > end) return -1

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
