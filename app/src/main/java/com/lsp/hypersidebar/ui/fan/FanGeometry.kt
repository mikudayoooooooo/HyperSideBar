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

/** 屏幕安全边距（PRD §9.5：竖屏 72dp；横屏独立取值见下）。 */
private const val SCREEN_MARGIN_DP = 72f

/** 横屏边距：短轴空间紧张（1080px 内 72dp 边距+扇形投影+快捷栏超出全高），派生值按方向缩至 24dp。 */
private const val LANDSCAPE_SCREEN_MARGIN_DP = 24f

/**
 * 扇形几何（1B 修订 2026-08-30，用户定稿原始设想）：碰到屏幕边缘调展开角，不缩半径。
 *
 * - 圆心 = 呼出位置（竖屏=初始触摸点高度；横屏由 B 路线通道固定为条中心，见 TurboLayout）
 * - 展开角自适应：默认以锚点水平轴上下对称（竖 ±75°/横 ±37.5°）；触碰屏幕边缘的一侧
 *   收窄至可容纳、另一侧放宽补足总角——弧长与图标间距不变，扇形向空侧倾斜
 *   （0.x 轮五"平移圆心保形"与 1A"半径收窄"两条路均废弃）
 * - 半径 = 用户设置（不再主动收窄）；仅双侧同时受限且总角塌缩至配置 60% 以下时
 *   兜底收缩并按上下可容纳空间比例分配剩余总角
 * - 单侧上限 acos(半图标/半径)：图标不得越过锚点后方（x 投影 ≥ 半图标）
 * - 快捷栏图标跟随扇形图标实际生效尺寸（PRD §9.5"与扇形应用图标大小一致，跟随"）
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
    val halfSpan = span / 2f

    val outerRadiusConfig = if (isLandscape) config.landscapeOuterRadiusDp else config.outerRadiusDp
    val innerRadiusConfig = if (isLandscape) config.landscapeInnerRadiusDp else config.innerRadiusDp
    // 横屏短轴空间紧张：边距按方向独立取值（72dp 为竖屏派生值，横屏缩至 24dp）
    val marginPx = (if (isLandscape) LANDSCAPE_SCREEN_MARGIN_DP else SCREEN_MARGIN_DP) * density

    val appCount = apps.size
    val outerCount = minOf(if (isLandscape) config.landscapeMaxAppsOuter else config.maxAppsOuter, appCount)
    val innerCount = (appCount - outerCount).coerceIn(0, if (isLandscape) config.landscapeMaxAppsInner else config.maxAppsInner)

    // 快捷栏占位估算（供下侧房间预留；渲染用生效图标重算，估算偏大属保守）
    val quickList = quickApps.take(6)
    val estBarBlockPx = config.quickIconSizeDp * density * 2.6f   // barGap(0.6) + 栏高(icon+上下各 0.5 padding)

    // ===== 展开角自适应 =====
    // 半径先取配置值（不收窄）
    var outerRadius = outerRadiusConfig * density

    val roomAbove = (anchor.y - marginPx).coerceAtLeast(0f)
    val roomBelow = (height - marginPx - estBarBlockPx - anchor.y).coerceAtLeast(0f)

    // 单侧角上限：图标不得越过锚点后方（x 投影 ≥ 半图标），另设 85° 硬顶
    val halfIconPx = (if (isLandscape) config.landscapeIconSizeDp else config.iconSizeDp) * density / 2f
    fun sideCap(r: Float): Float = Math.toDegrees(
        Math.acos((halfIconPx / r).coerceIn(0.02f, 0.98f).toDouble())
    ).toFloat().coerceAtMost(85f)

    // 该半径下单侧可容纳的最大角（房间投影）
    fun sideMax(room: Float, r: Float): Float = if (r <= 1f) 0f else
        Math.toDegrees(Math.asin((room / r).coerceIn(0f, 1f).toDouble())).toFloat()

    var up = minOf(halfSpan, sideMax(roomAbove, outerRadius))
    var dn = minOf(halfSpan, sideMax(roomBelow, outerRadius))
    // 受限侧让出的预算补到另一侧（总角守恒 → 弧长/图标间距不变）
    if (up < halfSpan) {
        dn = minOf(span - up, sideMax(roomBelow, outerRadius), sideCap(outerRadius))
    } else if (dn < halfSpan) {
        up = minOf(span - dn, sideMax(roomAbove, outerRadius), sideCap(outerRadius))
    }

    // 双侧同时受限兜底：总角塌缩（< 配置 60%）时收缩半径，按房间比例恢复最小总角
    val minTotal = span * 0.6f
    if (up + dn < minTotal && roomAbove + roomBelow > 0f) {
        val fracUp = roomAbove / (roomAbove + roomBelow)
        val wantUp = (minTotal * fracUp).coerceIn(2f, minTotal - 2f)
        val wantDn = minTotal - wantUp
        val rFit = minOf(
            roomAbove / degSin(wantUp),
            roomBelow / degSin(wantDn)
        )
        outerRadius = minOf(outerRadius, rFit).coerceAtLeast(config.minRadiusDp * density)
        up = minOf(wantUp, sideMax(roomAbove, outerRadius))
        dn = minOf(wantDn, sideMax(roomBelow, outerRadius))
    }
    // 房间数据异常的极端防御：退回对称默认
    if (up + dn < 4f) {
        up = halfSpan
        dn = halfSpan
    }

    // 上下不对称展开角 → 起止角（RIGHT：[-up, +dn]；LEFT：[180-dn, 180+up]）
    val startAngle = if (direction == FanDirection.RIGHT) -up else 180f - dn
    val endAngle = startAngle + up + dn
    val spanAngle = up + dn

    var innerRadius = innerRadiusConfig * density
    if (innerRadius > outerRadius * 0.8f || innerRadius < 50f * density) {
        innerRadius = outerRadius * 0.75f
    }

    val settledAnchor = Offset(anchor.x, anchor.y)

    var iconSizeDp = if (isLandscape) config.landscapeIconSizeDp else config.iconSizeDp
    if (appCount > 1 && spanAngle > 0f) {
        iconSizeDp = fitIconSize(outerCount, innerCount, spanAngle, outerRadius, innerRadius, iconSizeDp, density)
    }
    // 快捷栏图标跟随扇形实际生效图标（PRD §9.5"与扇形应用图标大小一致，跟随"）
    val quickIconSizeDpEff = iconSizeDp

    val items = layoutFanItems(
        apps = apps, outerCount = outerCount, innerCount = innerCount,
        anchor = settledAnchor, startAngle = startAngle, spanAngle = spanAngle,
        innerRadius = innerRadius, outerRadius = outerRadius,
        direction = direction
    )

    // 快捷栏固定在扇形下方（PRD"快捷方式入口在半圆的下面"）；半径已按上下空间收缩 ⇒
    // fanBottom 天然在屏内，此处 coerce 仅作防御。扫描极值不能用端点：左向扇形 startAngle
    // 的 sin 为正，端点命名易反（见 sweepExtremes 注释历史）
    val (_, maxSin, minCos, maxCos) = sweepExtremes(startAngle, endAngle)
    val effQuickIconPx = quickIconSizeDpEff * density
    val effSpacing = effQuickIconPx * 0.35f
    val effPadding = effQuickIconPx * 0.5f
    val effBarWidth = if (quickList.isNotEmpty()) {
        quickList.size * effQuickIconPx + (quickList.size - 1) * effSpacing + effPadding * 2f
    } else 0f
    val effBarHeight = effQuickIconPx + effPadding * 2f
    val fanLeftX = settledAnchor.x + outerRadius * minCos
    val fanRightX = settledAnchor.x + outerRadius * maxCos
    val baseY = settledAnchor.y + outerRadius * maxSin + effQuickIconPx * 0.6f
    val baseX = (fanLeftX + fanRightX) / 2f - effBarWidth / 2f

    val quickBarX = baseX.coerceIn(effPadding, (width - effBarWidth - effPadding).coerceAtLeast(effPadding))
    val quickBarY = baseY.coerceIn(effPadding, (height - effBarHeight - effPadding).coerceAtLeast(effPadding))

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
        quickIconSize = quickIconSizeDpEff,
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

private fun degSin(deg: Float): Float = sin(Math.toRadians(deg.toDouble())).toFloat()

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
    outerRadius: Float,
    direction: FanDirection
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

        // 左右镜像对称（2026-08-30 用户定稿，取代 PRD 旧"顺时针"字面）：
        // 两侧首位图标恒在展开区顶部、自上而下——RIGHT 角度自 startAngle 递增
        // （-up→+dn，顶部→底部），LEFT 自 endAngle 递减（180+up→180-dn，顶部→底部）；
        // 单项仍居扇区中线上
        val angle = if (countInRing > 1) {
            if (direction == FanDirection.LEFT) {
                (startAngle + spanAngle) - step * ringIndex - step / 2f
            } else {
                startAngle + step * ringIndex + step / 2f
            }
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
