package com.lsp.hypersidebar.ui.fan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
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

    val topSpace = anchor.y
    val bottomSpace = height - anchor.y
    val totalSpace = topSpace + bottomSpace
    val centerOffset = if (totalSpace > 0f) {
        (bottomSpace - topSpace) / totalSpace * span / 2f
    } else 0f

    val (initStart, initEnd) = when (direction) {
        FanDirection.RIGHT -> (centerOffset - span / 2f) to (centerOffset + span / 2f)
        FanDirection.LEFT  -> (180f + centerOffset - span / 2f) to (180f + centerOffset + span / 2f)
    }

    val iconPx = (if (isLandscape) config.landscapeIconSizeDp else config.iconSizeDp) * density
    val maxRadius = computeMaxRadius(anchor, width, height, initStart, initEnd, iconPx, density)
    val effectiveRadius = min(config.outerRadiusDp * density, maxRadius)

    val (startAngle, endAngle) = computeValidAngleRange(
        anchor, effectiveRadius, initStart, initEnd, height, iconPx * 0.5f
    )
    val spanAngle = endAngle - startAngle

    val outerRadiusConfig = if (isLandscape) config.landscapeOuterRadiusDp else config.outerRadiusDp
    val innerRadiusConfig = if (isLandscape) config.landscapeInnerRadiusDp else config.innerRadiusDp

    var outerRadius = effectiveRadius.coerceAtMost(outerRadiusConfig * density)
    var innerRadius = innerRadiusConfig * density

    if (innerRadius > outerRadius * 0.8f || innerRadius < 50f * density) {
        innerRadius = outerRadius * 0.75f
    }
    var useDualRing = config.useDualRing
    if (outerRadius < config.minRadiusDp * density) {
        useDualRing = false
        innerRadius = outerRadius * 0.6f
    }

    val appCount = apps.size
    val outerCount = minOf(if (isLandscape) config.landscapeMaxAppsOuter else config.maxAppsOuter, appCount)
    val innerCount = (appCount - outerCount).coerceIn(0, if (isLandscape) config.landscapeMaxAppsInner else config.maxAppsInner)

    var iconSizeDp = if (isLandscape) config.landscapeIconSizeDp else config.iconSizeDp
    if (appCount > 1 && spanAngle > 0f) {
        iconSizeDp = fitIconSize(outerCount, innerCount, spanAngle, outerRadius, innerRadius, iconSizeDp, density)
    }
    val finalIconPx = iconSizeDp * density

    val items = layoutFanItems(
        apps = apps, outerCount = outerCount, innerCount = innerCount,
        anchor = anchor, startAngle = startAngle, spanAngle = spanAngle,
        innerRadius = innerRadius, outerRadius = outerRadius
    )

    val quickIconPx = config.quickIconSizeDp * density
    val quickAppsList = quickApps.take(6)
    val quickSpacing = quickIconPx * 0.35f
    val quickBarPadding = quickIconPx * 0.5f
    val quickBarContentWidth = if (quickAppsList.isNotEmpty()) {
        quickAppsList.size * quickIconPx + (quickAppsList.size - 1) * quickSpacing + quickBarPadding * 2
    } else 0f
    val quickBarContentHeight = quickIconPx + quickBarPadding * 2

    val fanTopY = anchor.y + outerRadius * sin(Math.toRadians(startAngle.toDouble())).toFloat()
    val fanBottomY = anchor.y + outerRadius * sin(Math.toRadians(endAngle.toDouble())).toFloat()
    val fanLeftX = anchor.x + outerRadius * cos(Math.toRadians(startAngle.toDouble())).toFloat()
    val fanRightX = anchor.x + outerRadius * cos(Math.toRadians(endAngle.toDouble())).toFloat()

    val barGap = quickIconPx * 0.6f
    val baseY = if (isLandscape) fanBottomY + barGap else fanTopY - quickBarContentHeight - barGap
    val baseX = (fanLeftX + fanRightX) / 2f - quickBarContentWidth / 2f

    val quickBarX = baseX.coerceIn(quickBarPadding, width - quickBarContentWidth - quickBarPadding)
    val quickBarY = baseY.coerceIn(quickBarPadding, height - quickBarContentHeight - quickBarPadding)
    val quickBarVertical = false

    val quickBarCenterX = quickBarX + quickBarContentWidth / 2f
    val quickBarCenterY = quickBarY + quickBarContentHeight / 2f
    val quickBarDistance = distance(anchor, Offset(quickBarCenterX, quickBarCenterY))
    val activeZonePx = maxOf(config.activeZoneDp * density, quickBarDistance + quickIconPx * 0.8f)

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
        quickBarX = quickBarX,
        quickBarY = quickBarY,
        quickBarVertical = quickBarVertical,
        items = items,
        apps = apps,
        quickApps = quickApps,
        activeZonePx = activeZonePx,
        isLandscape = isLandscape
    )
}

private fun computeMaxRadius(
    anchor: Offset,
    width: Float,
    height: Float,
    startAngle: Float,
    endAngle: Float,
    iconSize: Float,
    density: Float
): Float {
    val margin = 48f * density * 1.5f
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

private fun computeValidAngleRange(
    anchor: Offset,
    radius: Float,
    initStart: Float,
    initEnd: Float,
    height: Float,
    margin: Float
): Pair<Float, Float> {
    if (radius <= 0f) return initStart to initEnd

    val topRatio = ((margin - anchor.y) / radius).coerceIn(-1f, 1f)
    val bottomRatio = ((height - margin - anchor.y) / radius).coerceIn(-1f, 1f)

    val topAngle = Math.toDegrees(Math.asin(topRatio.toDouble())).toFloat()
    val bottomAngle = Math.toDegrees(Math.asin(bottomRatio.toDouble())).toFloat()

    val adjustedStart = maxOf(initStart, topAngle)
    val adjustedEnd = minOf(initEnd, bottomAngle)

    return if (adjustedEnd - adjustedStart < 30f) {
        val mid = (initStart + initEnd) / 2f
        val halfSpan = maxOf(15f, (adjustedEnd - adjustedStart) / 2f)
        (mid - halfSpan) to (mid + halfSpan)
    } else {
        adjustedStart to adjustedEnd
    }
}

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
