package com.lsp.hypersidebar.ui.fan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.lsp.hypersidebar.prefs.LayoutDefaults
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
    /** 几何所用的窗口尺寸（锚点/各项坐标均为窗口本地系）——入场动画按此归一化缩放原点 */
    val windowSize: IntSize,
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
    val isLandscape: Boolean
)

/** 屏幕安全边距（PRD §9.5：竖屏 72dp；横屏独立取值见下）。 */
private const val SCREEN_MARGIN_DP = 72f

/** 横屏边距：短轴空间紧张（1080px 内 72dp 边距+扇形投影+快捷栏超出全高），派生值按方向缩至 24dp。 */
private const val LANDSCAPE_SCREEN_MARGIN_DP = 24f

/** 底角扇形上缘离竖直轴的留白：顶端贴近竖直（6°），把张角尽量撑开像 Flyme 四分之一弧。 */
internal const val CORNER_TOP_GAP_DEG = 6f

/** 底角下缘动态 gap 的 sin 上限（≈15°）：半径滑到很小时 asin 会把张角挤塌，封顶保底张角。 */
internal const val CORNER_BOTTOM_GAP_MAX_SIN = 0.26f

/** 布局 sheet 弦长收缩口径：默认底角配置（380dp 半径 / 48dp 图标）下的张角，与几何公式同源。 */
internal fun cornerDefaultSpanDeg(): Float {
    val cfg = FanConfig()
    val safePx = cfg.cornerIconSizeDp / 2f + FanVisuals.BAND_EDGE_PAD_DP
    val gap = Math.toDegrees(
        Math.asin((safePx / cfg.cornerOuterRadiusDp).coerceIn(0f, CORNER_BOTTOM_GAP_MAX_SIN).toDouble())
    ).toFloat()
    return 90f - CORNER_TOP_GAP_DEG - gap
}

/**
 * 扇形几何（1B 修订 2026-08-30；竖屏张角 2026-10-21 改固定，见下）。
 *
 * - 圆心 = 呼出位置（竖屏=初始触摸点高度；横屏由 B 路线通道固定为条中心，见 TurboLayout）
 * - **竖屏张角 = 配置值、以锚点水平轴对称**（默认 ±75°）：触发带 [H/3, 2H/3] 已把锚点限制在
 *   屏中部，扇形本就放得下，故**不做**"按上下房间收窄 + 预算补到另一侧"的自适应——那套在
 *   带两端（及大半径/大图标极端配置）会把一侧削掉、扇形歪向另一侧留出一段空白（用户实测反馈）。
 *   唯一保留的收窄是**对称**的单侧角上限 acos(半图标/半径)：图标不得越过锚点后方
 *   （x 投影 ≥ 半图标），否则大图标 + 小半径会把最外侧图标画到屏外。
 * - **横屏仍按上沿房间倾斜**：圆心固定在条中心 56dp（贴屏顶），±37.5° 对称必然越顶，
 *   受限侧让出的预算补到下方（总角守恒，弧长/图标间距不变）。
 * - 半径 = 用户设置（竖屏不再收窄；横屏仅在双侧同时受限且总角塌缩至配置 60% 以下时兜底）
 * - 快捷栏默认在扇形下方；下方放不下（会被钳制 ⇒ 与末项重叠）且上方放得下时整条翻到上缘之上
 * - 快捷栏图标跟随扇形图标实际生效尺寸（PRD §9.5"与扇形应用图标大小一致，跟随"）
 */
fun computeFanGeometry(
    anchor: Offset,
    screenSize: IntSize,
    apps: List<FanAppInfo>,
    quickApps: List<FanAppInfo>,
    config: FanConfig,
    density: Float,
    isLandscape: Boolean,
    /** 底角呼出（N1）：锚点贴屏幕底角，半径/图标/数量取底角独立样式键
     *  （默认=竖屏值，可在底角布局 sheet 微调），弧只占向上象限，快捷栏置于扇形上缘之上；
     *  默认 false=常规边缘几何。 */
    cornerAnchor: Boolean = false,
    /** 组展开锚定（C6，计划 §B3 定案）：非 null 时快捷栏以该 X 为中心排布（=原组
     *  图标中心，host 从旧几何算好传入）——子项锚定组图标位置，手指天然落中位子项、
     *  无整条重居中平移；仍走 coerceIn 出屏钳制。null=默认（扇形水平中心）。 */
    quickBarCenterX: Float? = null
): FanGeometry {
    val width = screenSize.width.toFloat()
    val height = screenSize.height.toFloat()

    val direction = if (anchor.x <= width / 2f) FanDirection.RIGHT else FanDirection.LEFT

    val span = if (isLandscape) config.landscapeSpanAngle else config.defaultSpanAngle
    val halfSpan = span / 2f

    val outerRadiusConfig = when {
        cornerAnchor -> config.cornerOuterRadiusDp
        isLandscape -> config.landscapeOuterRadiusDp
        else -> config.outerRadiusDp
    }
    val innerRadiusConfig = when {
        cornerAnchor -> config.cornerInnerRadiusDp
        isLandscape -> config.landscapeInnerRadiusDp
        else -> config.innerRadiusDp
    }
    // 横屏短轴空间紧张：边距按方向独立取值（72dp 为竖屏派生值，横屏缩至 24dp）
    val marginPx = (if (isLandscape) LANDSCAPE_SCREEN_MARGIN_DP else SCREEN_MARGIN_DP) * density

    val appCount = apps.size
    val outerCount = minOf(
        when {
            cornerAnchor -> config.cornerMaxAppsOuter
            isLandscape -> config.landscapeMaxAppsOuter
            else -> config.maxAppsOuter
        },
        appCount
    )
    val innerCount = (appCount - outerCount).coerceIn(
        0,
        when {
            cornerAnchor -> config.cornerMaxAppsInner
            isLandscape -> config.landscapeMaxAppsInner
            else -> config.maxAppsInner
        }
    )

    // 快捷栏占位估算（**仅横屏用**：横屏圆心贴屏顶，下侧房间要扣掉快捷栏块才不越底；
    // 竖屏已改为固定张角 + 快捷栏按实际高度避让，不再需要这个粗估）。跟随用户图标尺寸设置——
    // 快捷栏图标=扇形生效尺寸（PRD §9.5"与扇形应用图标大小一致，跟随"），拟合只会缩小
    // ⇒ 配置值=保守上界，预留偏大不偏小。
    val quickList = quickApps.take(6)
    val estBarBlockPx = (if (isLandscape) config.landscapeIconSizeDp else config.iconSizeDp) *
        density * 2.6f   // barGap(0.6) + 栏高(icon+上下各 0.5 padding)

    // ===== 展开角 =====
    // 半径先取配置值（不收窄）
    var outerRadius = outerRadiusConfig * density

    val startAngle: Float
    val spanAngle: Float
    if (cornerAnchor) {
        // 底角专用几何（N1）：贴底角 + 用底角独立样式键（半径/图标/数量，默认=竖屏值）：
        // 弧只占向上象限，半径固定不收窄；数量保持全部，图标由下方 fitIconSize 按弧长自动
        // 缩小（用户接受图标变小）。
        // 下缘 gap 动态=末项外缘（半图标+板外扩）恰好贴锚点水平轴——anchor 由触发端定为
        // 手势条上沿（EdgeGestureHook.gestureSafeBottomY），固定角度在 heightPixels 语义不同
        // 的设备上（全屏高 vs 可视高）必然一头越手势区、一头留白过大（2026-09-29 双机实锤）。
        // iconSize 取配置值=保守上界（fitIcon 只会缩小）。
        val safePx = config.cornerIconSizeDp * density / 2f +
            FanVisuals.BAND_EDGE_PAD_DP * density
        val bottomGapDeg = Math.toDegrees(
            Math.asin((safePx / outerRadius).coerceIn(0f, CORNER_BOTTOM_GAP_MAX_SIN).toDouble())
        ).toFloat()
        val upper = 90f - CORNER_TOP_GAP_DEG
        spanAngle = 90f - CORNER_TOP_GAP_DEG - bottomGapDeg
        startAngle = if (direction == FanDirection.RIGHT) -upper else 180f + bottomGapDeg
    } else {
        // 单侧角上限：图标不得越过锚点后方（x 投影 ≥ 半图标），另设 85° 硬顶
        val halfIconPx = (if (isLandscape) config.landscapeIconSizeDp else config.iconSizeDp) * density / 2f
        fun sideCap(r: Float): Float = Math.toDegrees(
            Math.acos((halfIconPx / r).coerceIn(0.02f, 0.98f).toDouble())
        ).toFloat().coerceAtMost(85f)

        if (isLandscape) {
            // 横屏：圆心固定条中心 56dp（贴屏顶）⇒ ±37.5° 对称必越顶，仍按上沿房间倾斜
            val roomAbove = (anchor.y - marginPx).coerceAtLeast(0f)
            val roomBelow = (height - marginPx - estBarBlockPx - anchor.y).coerceAtLeast(0f)

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
            startAngle = if (direction == FanDirection.RIGHT) -up else 180f - dn
            spanAngle = up + dn
        } else {
            // 竖屏：张角 = 配置值，恒以锚点水平轴对称（默认 ±75°）。
            // **不再按上下房间收窄/倾斜**（2026-10-21 用户实测反馈）：触发带 [H/3, 2H/3] 已把
            // 锚点限制在屏中部，扇形本就放得下；旧自适应在带两端（以及大半径/大图标等极端配置下
            // 连中间位置）会把受限侧削掉、预算补到另一侧，表现就是"靠上/靠下留一段空白"。
            // 唯一保留的收窄是**对称**的图标防越界上限（图标不得越过锚点后方）。
            val half = minOf(halfSpan, sideCap(outerRadius))
            startAngle = if (direction == FanDirection.RIGHT) -half else 180f - half
            spanAngle = half * 2f
        }
    }
    val endAngle = startAngle + spanAngle

    var innerRadius = innerRadiusConfig * density
    if (innerRadius > outerRadius * 0.8f || innerRadius < 50f * density) {
        innerRadius = outerRadius * 0.75f
    }

    // 窗口高度随导航条避让状态漂移（marble 实测同机 2262/2306 两种），锚点钳回窗口内，
    // 防触发端与渲染端的坐标系错位把图标/板放到窗外。
    val settledAnchor = Offset(anchor.x, anchor.y.coerceAtMost(height))

    var iconSizeDp = when {
        cornerAnchor -> config.cornerIconSizeDp
        isLandscape -> config.landscapeIconSizeDp
        else -> config.iconSizeDp
    }
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

    // 快捷栏位置：常规固定在扇形下方（PRD"快捷方式入口在半圆的下面"）；底角扇形向上展开，
    // 下侧无空间且会与末项重叠，故置于扇形上缘之上。扫描极值不能用端点：左向扇形 startAngle
    // 的 sin 为正，端点命名易反（见 sweepExtremes 注释历史）
    val (minSin, maxSin, minCos, maxCos) = sweepExtremes(startAngle, endAngle)
    val effQuickIconPx = quickIconSizeDpEff * density
    val effSpacing = effQuickIconPx * 0.35f
    val effPadding = effQuickIconPx * 0.5f
    val effBarWidth = if (quickList.isNotEmpty()) {
        quickList.size * effQuickIconPx + (quickList.size - 1) * effSpacing + effPadding * 2f
    } else 0f
    val effBarHeight = effQuickIconPx + effPadding * 2f
    val fanLeftX = settledAnchor.x + outerRadius * minCos
    val fanRightX = settledAnchor.x + outerRadius * maxCos
    val barGap = effQuickIconPx * 0.6f
    val belowY = settledAnchor.y + outerRadius * maxSin + barGap
    val aboveY = settledAnchor.y + outerRadius * minSin - effBarHeight - barGap
    // 下方优先（PRD"快捷方式入口在半圆的下面"）；底角扇形向上展开，恒在上方。
    // 竖屏固定张角后，靠触发带下沿 + 大半径/大图标会把下方顶穿（钳制后与末项/手势条重叠）⇒
    // 此时整条翻到扇形上缘之上（用户 2026-10-21 定案：挤压就移快捷栏，不再改张角）。
    // 判据用 72dp 安全边距而非屏底：下方虽在屏内但压进手势条时同样该翻上去。
    val baseY = when {
        cornerAnchor -> aboveY
        belowY + effBarHeight <= height - marginPx -> belowY
        aboveY >= effPadding -> aboveY
        else -> belowY   // 上下都放不下（极端配置）：保留下方，交给钳制
    }
    // 锚定覆盖（C6）：组展开时以原组图标中心为栏中心（几何连续，闩锁豁免面最小）；
    // 默认=扇形水平中心。两者都走下方 coerceIn 出屏钳制
    val barCenterX = quickBarCenterX ?: (fanLeftX + fanRightX) / 2f
    val baseX = barCenterX - effBarWidth / 2f

    val quickBarX = baseX.coerceIn(effPadding, (width - effBarWidth - effPadding).coerceAtLeast(effPadding))
    val quickBarY = baseY.coerceIn(effPadding, (height - effBarHeight - effPadding).coerceAtLeast(effPadding))

    return FanGeometry(
        anchor = settledAnchor,
        windowSize = IntSize(width.toInt(), height.toInt()),
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
        isLandscape = isLandscape
    )
}

private fun degSin(deg: Float): Float = sin(Math.toRadians(deg.toDouble())).toFloat()

/** [startAngle, endAngle] 扫描区间内 sin/cos 的极值（4° 步进采样，布局精度足够）。
 *  internal=毛玻璃包围盒（ComposeFanHost）复用同一极值源，勿再各写一份角度采样。 */
internal fun sweepExtremes(startAngle: Float, endAngle: Float): FloatArray {
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

    // 图标间隙比例（2026-09-04 收紧 0.2→0.12）：此前 11 应用布局下钳制上限
    // ≈40.3dp，滑条 40 以上的调整全部无效（体感"图标大小失效"）；收紧间隙后
    // 上限提升约 7%，同时调小方向（< 上限）本就有效。图标间仍有 12% 间隙防重叠。
    val gapFraction = 0.12f
    var minChord = Float.MAX_VALUE

    if (outerCount > 1) {
        val midR = (innerRadius + outerRadius) / 2f
        val stepRad = Math.toRadians((spanAngle / (outerCount - 1)).toDouble())
        val chord = 2f * midR * sin(stepRad / 2f).toFloat()
        minChord = minOf(minChord, chord)
    }
    if (innerCount > 1) {
        val midR = innerRadius * 0.85f
        val stepRad = Math.toRadians((spanAngle / (innerCount - 1)).toDouble())
        val chord = 2f * midR * sin(stepRad / 2f).toFloat()
        minChord = minOf(minChord, chord)
    }

    val required = maxSizeDp * density * (1f + gapFraction)
    return if (minChord < required) {
        (minChord / density / (1f + gapFraction))
            .coerceIn(LayoutDefaults.ICON_SIZE_HARD_MIN, maxSizeDp)
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

    return apps.mapIndexed { index, app ->
        val isOuter = index < outerCount
        val ringIndex = if (isOuter) index else index - outerCount
        val countInRing = if (isOuter) outerCount else innerCount

        // 左右镜像对称（2026-08-30 定稿）+ 内外圈端点对齐（2026-09-20 用户：两圈展开角须一致）：
        // 每圈首/末图标正好落在 startAngle/endAngle 上（按 count−1 等分，不再各按 count 等分后
        // 内缩半个扇区——那会让图标少的内圈缩得更多、看起来展开角比外圈小）。RIGHT 自 startAngle
        // 递增（顶部→底部），LEFT 自 endAngle 递减；单项居扇区中线。
        val angle = if (countInRing > 1) {
            val t = ringIndex.toFloat() / (countInRing - 1)
            if (direction == FanDirection.LEFT) {
                (startAngle + spanAngle) - spanAngle * t
            } else {
                startAngle + spanAngle * t
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
