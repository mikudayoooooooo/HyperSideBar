package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.SettingsRepository
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.ui.fan.FanAppInfo
import com.lsp.hypersidebar.ui.fan.FanVisuals
import com.lsp.hypersidebar.ui.fan.FanConfig
import com.lsp.hypersidebar.ui.fan.FanGeometry
import com.lsp.hypersidebar.ui.fan.FanThemeColors
import com.lsp.hypersidebar.ui.fan.computeFanGeometry
import com.lsp.hypersidebar.ui.fan.QUICK_BAR_GAP_RATIO
import com.lsp.hypersidebar.ui.fan.QUICK_BAR_MAX_ICONS
import com.lsp.hypersidebar.ui.fan.QUICK_BAR_SIDE_PAD_RATIO
import com.lsp.hypersidebar.ui.fan.QUICK_BAR_VERTICAL_PAD_RATIO
import com.lsp.hypersidebar.ui.fan.fanBandRadii
import com.lsp.hypersidebar.ui.fan.quickCapsuleCornerDp
import com.lsp.hypersidebar.ui.fan.sweepExtremes
import com.lsp.hypersidebar.util.RemotePrefsBridge
import com.lsp.hypersidebar.util.ShortcutStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val PREVIEW_DENSITY = FanVisuals.PREVIEW_DENSITY
private const val PORTRAIT_WIDTH = 360f
private const val PORTRAIT_HEIGHT = 720f
private const val LANDSCAPE_WIDTH = 720f
private const val LANDSCAPE_HEIGHT = 360f

private data class PreviewViewport(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

/**
 * 抽象预览的占位项（0921 收口）：预览画的是**占位圆角方块**（[PreviewIcon]）与抽象弧带剪影，
 * 不描摹任何真实应用，所以几何只需要「数量」——`computeFanGeometry` 按 appCount / quickList.size
 * 收缩（数量到顶 = 最坏收缩，正是要预览的东西）。
 *
 * 两处数量**都取自实际配置，没有占位常数**：
 *  · 扇形项数 = 该方向的 `maxOuter + maxInner`（真机末位"全部应用"哨兵已计入该值）；
 *  · 快捷栏项数 = 真机运行时列表项数（[rememberRuntimeQuickCount]）。
 * 原先这里挂着两份真机应用名清单（20 条 + 4 条）——既不显示、又会在改名/换包时误导维护者；0921 删除。
 */
private fun previewPlaceholderApps(count: Int): List<FanAppInfo> =
    List(count) { FanAppInfo(packageName = "preview.app.$it") }

/**
 * 预览要画的快捷栏项数 = **真机运行时列表的项数**：条件性面板占位 + 已启用用户项，
 * 上限 [ShortcutStore.MAX_USER_SHORTCUTS] 含占位。与真机走同一个函数，不再各写一份数量。
 *
 * 读「生效存储」：真机侧读的是遥控侧那一份，故优先 [RemotePrefsBridge.prefs]，
 * 未接上时退回本进程存储（设置页写入的源）。
 */
@Composable
private fun rememberRuntimeQuickCount(prefs: SharedPreferences): Int {
    val context = LocalContext.current
    val effectivePrefs = RemotePrefsBridge.prefs ?: prefs
    return remember(effectivePrefs, context) {
        ShortcutStore.buildRuntimeQuickList(
            effectivePrefs,
            ShortcutStore.isToolboxAvailable(context),
            ShortcutStore.getToolboxLabel(context)
        ).size
    }
}

/**
 * 效果预览分区唯一卡片：miuix TabRow（竖屏/横屏/底角侧滑）+ 一块铺满的抽象扇形预览。
 * 取代旧"双格卡+整宽单格卡"两张卡（三形态两张卡不对称）。点击预览进当前 tab 方向的布局 sheet；
 * 草稿优先读（revision 通道）→ sheet 内拖滑条时本卡实时跟随。
 */
@Composable
internal fun FanPreviewTabsCard(
    repo: SettingsRepository,
    onEditLayout: (LayoutOrientation) -> Unit,
    modifier: Modifier = Modifier
) {
    val rev = repo.revision
    val config = remember(rev) { buildPreviewConfig(repo) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val orientation = when (selectedTab) {
        1 -> LayoutOrientation.LANDSCAPE
        2 -> LayoutOrientation.CORNER
        else -> LayoutOrientation.PORTRAIT
    }
    val tabs = listOf(
        stringResource(R.string.portrait_preview),
        stringResource(R.string.landscape_preview),
        stringResource(R.string.corner_preview)
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp)
    ) {
        Column {
            TabRowWithContour(
                tabs = tabs,
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .sinkClickable(onClick = { onEditLayout(orientation) })
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                // 切 tab：新旧预览交叉淡入，避免硬切
                Crossfade(targetState = orientation, label = "fanPreviewTab") { tab ->
                    FanStaticPreview(
                        config = config,
                        isLandscape = tab == LayoutOrientation.LANDSCAPE,
                        prefs = repo.prefs,
                        corner = tab == LayoutOrientation.CORNER,
                        includeQuickBar = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

internal fun buildPreviewConfig(repo: SettingsRepository): FanConfig = FanConfig(
    iconSizeDp = repo.iconSize(),
    innerRadiusDp = repo.innerRadius(),
    outerRadiusDp = repo.outerRadiusMax(),
    deadZoneDp = repo.deadZone(),
    maxAppsOuter = repo.maxAppsOuter(),
    maxAppsInner = repo.maxAppsInner(),
    landscapeIconSizeDp = repo.landscapeIconSize(),
    landscapeMaxAppsOuter = repo.landscapeMaxAppsOuter(),
    landscapeMaxAppsInner = repo.landscapeMaxAppsInner(),
    landscapeInnerRadiusDp = repo.landscapeInnerRadius(),
    landscapeOuterRadiusDp = repo.landscapeOuterRadius(),
    cornerIconSizeDp = repo.cornerIconSize(),
    cornerInnerRadiusDp = repo.cornerInnerRadius(),
    cornerOuterRadiusDp = repo.cornerOuterRadius(),
    cornerMaxAppsOuter = repo.cornerMaxAppsOuter(),
    cornerMaxAppsInner = repo.cornerMaxAppsInner()
)

/**
 * 预览数值缓动：半径滑条按 10dp 步进落值，直接重算会让预览"一格一格"跳。对当前方向的
 * 图标/内外半径做短 tween，拖拽时弧带平滑生长。应用数不补间（中途增减图标会闪跳）。
 */
@Composable
private fun animatedPreviewConfig(
    config: FanConfig,
    isLandscape: Boolean,
    corner: Boolean
): FanConfig {
    val spec = tween<Float>(durationMillis = 180, easing = FastOutSlowInEasing)
    val icon = animateFloatAsState(
        when {
            corner -> config.cornerIconSizeDp
            isLandscape -> config.landscapeIconSizeDp
            else -> config.iconSizeDp
        }, spec, label = "previewIcon"
    ).value
    val inner = animateFloatAsState(
        when {
            corner -> config.cornerInnerRadiusDp
            isLandscape -> config.landscapeInnerRadiusDp
            else -> config.innerRadiusDp
        }, spec, label = "previewInner"
    ).value
    val outer = animateFloatAsState(
        when {
            corner -> config.cornerOuterRadiusDp
            isLandscape -> config.landscapeOuterRadiusDp
            else -> config.outerRadiusDp
        }, spec, label = "previewOuter"
    ).value
    return when {
        corner -> config.copy(
            cornerIconSizeDp = icon, cornerInnerRadiusDp = inner, cornerOuterRadiusDp = outer
        )
        isLandscape -> config.copy(
            landscapeIconSizeDp = icon, landscapeInnerRadiusDp = inner, landscapeOuterRadiusDp = outer
        )
        else -> config.copy(iconSizeDp = icon, innerRadiusDp = inner, outerRadiusDp = outer)
    }
}

/** 单方向静态扇形预览（BottomSheet 内实时预览复用；geometry 与实机 computeFanGeometry 同源）。 */
@Composable
internal fun FanStaticPreview(
    config: FanConfig,
    isLandscape: Boolean,
    /** 生效存储：[rememberRuntimeQuickCount] 从中实读快捷栏项数，故预览数量恒与真机一致。 */
    prefs: SharedPreferences,
    modifier: Modifier = Modifier,
    includeQuickBar: Boolean = true,
    /** 底角预览：锚点=精确底角、弧占向上象限（与竖/横屏同构的第三种形态）。 */
    corner: Boolean = false
) {
    val quickCount = rememberRuntimeQuickCount(prefs)
    val animatedConfig = animatedPreviewConfig(config, isLandscape, corner)
    val geometry = remember(animatedConfig, isLandscape, corner, quickCount, includeQuickBar) {
        previewGeometry(
            config = animatedConfig,
            width = if (isLandscape) LANDSCAPE_WIDTH else PORTRAIT_WIDTH,
            height = if (isLandscape) LANDSCAPE_HEIGHT else PORTRAIT_HEIGHT,
            isLandscape = isLandscape,
            corner = corner,
            quickApps = if (includeQuickBar) previewPlaceholderApps(quickCount) else emptyList()
        )
    }
    // 固定宽高比预览框（竖屏 3:4 / 横屏 4:3 / 底角近方形）：框形稳定不抖，扇形按内容适配缩放居中
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        StaticFanPreview(
            geometry = geometry,
            modifier = Modifier.aspectRatio(
                when {
                    corner -> 1f
                    isLandscape -> 4f / 3f
                    else -> 3f / 4f
                }
            )
        )
    }
}

private fun previewGeometry(
    config: FanConfig,
    width: Float,
    height: Float,
    isLandscape: Boolean,
    corner: Boolean = false,
    quickApps: List<FanAppInfo>
): FanGeometry {
    val appLimit = when {
        corner -> config.cornerMaxAppsOuter + config.cornerMaxAppsInner
        isLandscape -> config.landscapeMaxAppsOuter + config.landscapeMaxAppsInner
        else -> config.maxAppsOuter + config.maxAppsInner
    }
    return computeFanGeometry(
        // 底角预览：锚点=精确底角 (0,H)，与实机 postShowFanCorner 同源
        anchor = if (corner) Offset(0f, height) else Offset(0f, height / 2f),
        screenSize = IntSize(width.toInt(), height.toInt()),
        // 项数 = 该方向配置上限（含真机末位"全部应用"哨兵），不再截到人为常数：
        // 数量直接决定环上图标收缩，预览必须与真机同数。下限 1 = 真机配置为 0 时只剩哨兵项
        apps = previewPlaceholderApps(appLimit.coerceAtLeast(1)),
        quickApps = quickApps,
        config = config,
        density = PREVIEW_DENSITY,
        isLandscape = isLandscape,
        cornerAnchor = corner
    )
}

@Composable
private fun StaticFanPreview(
    geometry: FanGeometry,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val colors = currentFanThemeColors()
    val viewport = remember(geometry) { previewViewport(geometry) }

    BoxWithConstraints(
        modifier = modifier
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                cornerRadius = FanVisuals.PREVIEW_FRAME_CORNER
            )
            .squircleBorder(
                width = FanVisuals.PREVIEW_FRAME_BORDER_WIDTH,
                color = colors.outline.copy(alpha = FanVisuals.PREVIEW_FRAME_BORDER_ALPHA),
                cornerRadius = FanVisuals.PREVIEW_FRAME_CORNER
            )
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val scale = minOf(widthPx / viewport.width, heightPx / viewport.height)
        val offsetX = (widthPx - viewport.width * scale) / 2f - viewport.left * scale
        val offsetY = (heightPx - viewport.height * scale) / 2f - viewport.top * scale

        fun map(point: Offset) = Offset(
            x = offsetX + point.x * scale,
            y = offsetY + point.y * scale
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val anchor = map(geometry.anchor)
            // 弧带内外缘与真机同源（fanBandRadii），虚拟系算好再乘 scale
            val (bandInnerR, bandOuterR) = fanBandRadii(geometry, PREVIEW_DENSITY)
            val bandMidPx = (bandInnerR + bandOuterR) / 2f * scale
            val bandThicknessPx = (bandOuterR - bandInnerR) * scale
            val outerPx = bandOuterR * scale
            // 磨砂弧带剪影：抽象平涂（粗描边弧 = 圆角端扇环），替代旧填充饼 + 双轨道
            drawArc(
                color = colors.surfaceContainer.copy(alpha = FanVisuals.PREVIEW_BAND_FILL_ALPHA),
                startAngle = geometry.startAngle,
                sweepAngle = geometry.spanAngle,
                useCenter = false,
                topLeft = Offset(anchor.x - bandMidPx, anchor.y - bandMidPx),
                size = Size(bandMidPx * 2f, bandMidPx * 2f),
                style = Stroke(width = bandThicknessPx, cap = StrokeCap.Round)
            )
            // 最外层单条弧线（与真机 FanBoard ③ 层同口径）
            drawArc(
                color = colors.outline.copy(alpha = FanVisuals.BAND_STROKE_TOP_ALPHA_LIGHT),
                startAngle = geometry.startAngle,
                sweepAngle = geometry.spanAngle,
                useCenter = false,
                topLeft = Offset(anchor.x - outerPx, anchor.y - outerPx),
                size = Size(outerPx * 2f, outerPx * 2f),
                style = Stroke(width = FanVisuals.BAND_STROKE_WIDTH.toPx(), cap = StrokeCap.Round)
            )
        }

        geometry.items.forEach { item ->
            // 直接使用实机 layoutFanItems 的真实坐标（消除预览自算半径的漂移）
            val center = map(Offset(item.centerX, item.centerY))
            val iconSizePx = geometry.iconSize * PREVIEW_DENSITY * scale
            val iconSizeDp = with(density) { iconSizePx.toDp() }
            PreviewIcon(
                size = iconSizeDp.value,
                modifier = Modifier.offset {
                    IntOffset(
                        x = (center.x - iconSizePx / 2f).toInt(),
                        y = (center.y - iconSizePx / 2f).toInt()
                    )
                }
            )
        }

        if (geometry.quickApps.isNotEmpty()) {
            PreviewQuickBar(
                geometry = geometry,
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY
            )
        }
    }
}

private fun previewViewport(geometry: FanGeometry): PreviewViewport {
    val iconSize = geometry.iconSize * PREVIEW_DENSITY
    val quickIconSize = geometry.quickIconSize * PREVIEW_DENSITY
    // 与实机同源：间距/内边距/数量上限全部走 FanBoard 导出的 QUICK_BAR_* 常量（旧值手抄
    // 0.35/0.5 一份、且数量按 take(4) 估宽 ⇒ 预览包围盒比实机窄、整幅缩放略偏大）
    val quickCount = geometry.quickApps.take(QUICK_BAR_MAX_ICONS).size
    val quickWidth = if (quickCount == 0) 0f else {
        quickCount * quickIconSize + (quickCount - 1) * quickIconSize * QUICK_BAR_GAP_RATIO +
            2f * quickIconSize * QUICK_BAR_SIDE_PAD_RATIO
    }
    val quickHeight = quickIconSize + 2f * quickIconSize * QUICK_BAR_VERTICAL_PAD_RATIO
    val centers = geometry.items.map { Offset(it.centerX, it.centerY) }
    // 弧带外缘纳入包围盒（预览画的是弧带剪影，不能只按图标中心裁，否则带边被切）
    val (_, bandOuterR) = fanBandRadii(geometry, PREVIEW_DENSITY)
    val (minSin, maxSin, minCos, maxCos) = sweepExtremes(geometry.startAngle, geometry.endAngle)
    val bandLeft = geometry.anchor.x + bandOuterR * minCos
    val bandRight = geometry.anchor.x + bandOuterR * maxCos
    val bandTop = geometry.anchor.y + bandOuterR * minSin
    val bandBottom = geometry.anchor.y + bandOuterR * maxSin
    val contentLeft = minOf(
        geometry.anchor.x,
        centers.minOfOrNull { it.x - iconSize / 2f } ?: geometry.anchor.x,
        geometry.quickBarX,
        bandLeft
    )
    val contentTop = minOf(
        centers.minOfOrNull { it.y - iconSize / 2f } ?: geometry.anchor.y,
        geometry.quickBarY,
        bandTop
    )
    val contentRight = maxOf(
        centers.maxOfOrNull { it.x + iconSize / 2f } ?: geometry.anchor.x,
        geometry.quickBarX + quickWidth,
        bandRight
    )
    val contentBottom = maxOf(
        centers.maxOfOrNull { it.y + iconSize / 2f } ?: geometry.anchor.y,
        geometry.quickBarY + quickHeight,
        bandBottom
    )
    val padding = iconSize * FanVisuals.PREVIEW_VIEWPORT_PAD_RATIO
    return PreviewViewport(
        left = contentLeft - padding,
        top = contentTop - padding,
        width = contentRight - contentLeft + padding * 2f,
        height = contentBottom - contentTop + padding * 2f
    )
}

@Composable
private fun PreviewIcon(
    size: Float,
    modifier: Modifier = Modifier
) {
    // 占位图标：统一配色的圆角方块（不描摹真实应用图标，仅示意排布）
    val colors = currentFanThemeColors()
    Box(
        modifier = modifier
            .size(size.dp)
            .squircleSurface(
                color = placeholderColor(colors),
                cornerRadius = (size * FanVisuals.ICON_CORNER_RATIO).dp
            )
    )
}

private fun placeholderColor(colors: FanThemeColors): Color =
    colors.primaryContainer.copy(alpha = FanVisuals.PREVIEW_PLACEHOLDER_ALPHA)

@Composable
private fun PreviewQuickBar(
    geometry: FanGeometry,
    scale: Float,
    offsetX: Float,
    offsetY: Float
) {
    val density = LocalDensity.current
    val colors = currentFanThemeColors()
    val iconSizePx = geometry.quickIconSize * PREVIEW_DENSITY * scale
    val iconSizeDp = with(density) { iconSizePx.toDp() }
    // 示意盒子也和实机同源：圆角取 capsule 圆角口径（px = dp × density），内边距/间距取
    // QUICK_BAR_* 比例。旧值 10.dp / 5.dp / 4.dp / 2.dp 是手抄的另一套数字，滑条一动就对不上
    val capsuleCorner = quickCapsuleCornerDp(geometry.quickIconSize).dp

    Row(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (offsetX + geometry.quickBarX * scale).toInt(),
                    y = (offsetY + geometry.quickBarY * scale).toInt()
                )
            }
            .squircleSurface(
                color = colors.surfaceContainer.copy(alpha = FanVisuals.PREVIEW_CAPSULE_FILL_ALPHA),
                cornerRadius = capsuleCorner
            )
            .squircleBorder(
                width = 1.dp,
                color = colors.outline.copy(alpha = FanVisuals.PREVIEW_CAPSULE_BORDER_ALPHA),
                cornerRadius = capsuleCorner
            )
            .padding(
                horizontal = (iconSizeDp.value * QUICK_BAR_SIDE_PAD_RATIO).dp,
                vertical = (iconSizeDp.value * QUICK_BAR_VERTICAL_PAD_RATIO).dp
            ),
        horizontalArrangement = Arrangement.spacedBy((iconSizeDp.value * QUICK_BAR_GAP_RATIO).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        geometry.quickApps.take(QUICK_BAR_MAX_ICONS).forEach { _ ->
            Box(
                modifier = Modifier
                    .size(iconSizeDp.value.dp)
                    .squircleSurface(
                        color = placeholderColor(colors),
                        cornerRadius = (iconSizeDp.value * 0.25f).dp
                    )
            )
        }
    }
}
