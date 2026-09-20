package com.lsp.hypersidebar.ui.settings

import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.SettingsRepository
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.ui.fan.FanAppInfo
import com.lsp.hypersidebar.ui.fan.FanConfig
import com.lsp.hypersidebar.ui.fan.FanGeometry
import com.lsp.hypersidebar.ui.fan.FanThemeColors
import com.lsp.hypersidebar.ui.fan.computeFanGeometry
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.cos
import kotlin.math.sin

private const val PREVIEW_DENSITY = 0.5f
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

private val previewApps = listOf(
    FanAppInfo("com.android.browser", "浏览器"),
    FanAppInfo("com.miui.gallery", "相册"),
    FanAppInfo("com.android.camera", "相机"),
    FanAppInfo("com.miui.notes", "笔记"),
    FanAppInfo("com.android.settings", "设置"),
    FanAppInfo("com.miui.calculator", "计算器"),
    FanAppInfo("com.miui.securitycenter", "手机管家"),
    FanAppInfo("com.android.contacts", "联系人"),
    FanAppInfo("com.android.calendar", "日历"),
    FanAppInfo("com.android.fileexplorer", "文件管理"),
    FanAppInfo("com.android.deskclock", "时钟"),
    FanAppInfo("com.miui.player", "音乐"),
    FanAppInfo("com.miui.weather2", "天气"),
    FanAppInfo("com.android.mms", "短信"),
    FanAppInfo("com.android.incallui", "电话"),
    FanAppInfo("com.android.email", "邮件"),
    FanAppInfo("com.android.providers.downloads.ui", "下载"),
    FanAppInfo("com.miui.compass", "指南针"),
    FanAppInfo("com.android.soundrecorder", "录音机"),
    FanAppInfo("com.xiaomi.scanner", "扫一扫")
)

private val previewQuickApps = listOf(
    FanAppInfo("com.android.camera", "相机"),
    FanAppInfo("com.miui.notes", "笔记"),
    FanAppInfo("com.miui.calculator", "计算器"),
    FanAppInfo("com.android.settings", "设置")
)

@Composable
internal fun LayoutPreviewCard(
    repo: SettingsRepository,
    onPortraitClick: () -> Unit,
    onLandscapeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 草稿优先读：BottomSheet 编辑期滑条变化实时反映到本卡（revision 通道驱动重组）
    val rev = repo.revision
    val config = remember(rev) { buildPreviewConfig(repo) }

    val portraitGeometry = remember(config) {
        previewGeometry(
            config = config,
            width = PORTRAIT_WIDTH,
            height = PORTRAIT_HEIGHT,
            isLandscape = false
        )
    }
    val landscapeGeometry = remember(config) {
        previewGeometry(
            config = config,
            width = LANDSCAPE_WIDTH,
            height = LANDSCAPE_HEIGHT,
            isLandscape = true
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
        ) {
            PreviewPane(
                title = stringResource(R.string.portrait_preview),
                geometry = portraitGeometry,
                onClick = onPortraitClick,
                modifier = Modifier.weight(1f)
            )
            PreviewPane(
                title = stringResource(R.string.landscape_preview),
                geometry = landscapeGeometry,
                onClick = onLandscapeClick,
                modifier = Modifier.weight(1f)
            )
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
 * 底角预览卡（效果预览分区第二项，与竖/横屏同级）：单格整宽、点击打开「底角侧滑」sheet。
 * 草稿优先读，故 sheet 内拖动滑条时本卡实时跟随（revision 通道）。
 */
@Composable
internal fun CornerLayoutPreviewCard(
    repo: SettingsRepository,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rev = repo.revision
    val config = remember(rev) { buildPreviewConfig(repo) }
    val geometry = remember(config) {
        previewGeometry(
            config = config,
            width = PORTRAIT_WIDTH,
            height = PORTRAIT_HEIGHT,
            isLandscape = false,
            corner = true
        )
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp)
    ) {
        PreviewPane(
            title = stringResource(R.string.corner_preview),
            geometry = geometry,
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        )
    }
}

/** 单方向静态扇形预览（BottomSheet 内实时预览复用；geometry 与实机 computeFanGeometry 同源）。 */
@Composable
internal fun FanStaticPreview(
    config: FanConfig,
    isLandscape: Boolean,
    modifier: Modifier = Modifier,
    includeQuickBar: Boolean = true,
    /** 底角预览：锚点=精确底角、弧占向上象限（与竖/横屏同构的第三种形态）。 */
    corner: Boolean = false
) {
    val geometry = remember(config, isLandscape, corner) {
        previewGeometry(
            config = config,
            width = if (isLandscape) LANDSCAPE_WIDTH else PORTRAIT_WIDTH,
            height = if (isLandscape) LANDSCAPE_HEIGHT else PORTRAIT_HEIGHT,
            isLandscape = isLandscape,
            corner = corner,
            quickApps = if (includeQuickBar) previewQuickApps else emptyList()
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

@Composable
private fun PreviewPane(
    title: String,
    geometry: FanGeometry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .squircleClip(cornerRadius = 12.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface
        )
        StaticFanPreview(
            geometry = geometry,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
        )
    }
}

private fun previewGeometry(
    config: FanConfig,
    width: Float,
    height: Float,
    isLandscape: Boolean,
    corner: Boolean = false,
    quickApps: List<FanAppInfo> = previewQuickApps
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
        apps = previewApps.take(appLimit.coerceIn(1, previewApps.size)),
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
                cornerRadius = 12.dp
            )
            .squircleBorder(
                width = 1.dp,
                color = colors.outline.copy(alpha = 0.35f),
                cornerRadius = 12.dp
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
            val outerRadius = geometry.outerRadius * scale
            val outerTrackRadius = previewRingRadius(geometry, isOuter = true) * scale
            val innerTrackRadius = previewRingRadius(geometry, isOuter = false) * scale
            val iconSizePx = geometry.iconSize * PREVIEW_DENSITY * scale
            drawArc(
                color = colors.surfaceContainer.copy(alpha = 0.92f),
                startAngle = geometry.startAngle,
                sweepAngle = geometry.spanAngle,
                useCenter = true,
                topLeft = Offset(anchor.x - outerRadius, anchor.y - outerRadius),
                size = Size(outerRadius * 2f, outerRadius * 2f)
            )
            drawArc(
                color = colors.primaryContainer.copy(alpha = 0.34f),
                startAngle = geometry.startAngle,
                sweepAngle = geometry.spanAngle,
                useCenter = false,
                topLeft = Offset(anchor.x - outerTrackRadius, anchor.y - outerTrackRadius),
                size = Size(outerTrackRadius * 2f, outerTrackRadius * 2f),
                style = Stroke(width = iconSizePx * 1.08f)
            )
            if (geometry.items.any { !it.isOuter }) {
                drawArc(
                    color = colors.surfaceContainerHigh.copy(alpha = 0.72f),
                    startAngle = geometry.startAngle,
                    sweepAngle = geometry.spanAngle,
                    useCenter = false,
                    topLeft = Offset(anchor.x - innerTrackRadius, anchor.y - innerTrackRadius),
                    size = Size(innerTrackRadius * 2f, innerTrackRadius * 2f),
                    style = Stroke(width = iconSizePx * 1.08f)
                )
            }
            drawArc(
                color = colors.outline.copy(alpha = 0.45f),
                startAngle = geometry.startAngle,
                sweepAngle = geometry.spanAngle,
                useCenter = false,
                topLeft = Offset(anchor.x - outerRadius, anchor.y - outerRadius),
                size = Size(outerRadius * 2f, outerRadius * 2f),
                style = Stroke(width = 1.dp.toPx())
            )
            val dividerRadius = geometry.innerRadius * scale
            drawArc(
                color = colors.outline.copy(alpha = 0.18f),
                startAngle = geometry.startAngle,
                sweepAngle = geometry.spanAngle,
                useCenter = false,
                topLeft = Offset(anchor.x - dividerRadius, anchor.y - dividerRadius),
                size = Size(dividerRadius * 2f, dividerRadius * 2f),
                style = Stroke(width = 1.dp.toPx())
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
    val quickSpacing = quickIconSize * 0.35f
    val quickPadding = quickIconSize * 0.5f
    val quickCount = geometry.quickApps.take(4).size
    val quickWidth = if (quickCount == 0) 0f else {
        quickCount * quickIconSize + (quickCount - 1) * quickSpacing + quickPadding * 2f
    }
    val quickHeight = quickIconSize + quickPadding * 2f
    val centers = geometry.items.map { Offset(it.centerX, it.centerY) }
    val contentLeft = minOf(
        geometry.anchor.x,
        centers.minOfOrNull { it.x - iconSize / 2f } ?: geometry.anchor.x,
        geometry.quickBarX
    )
    val contentTop = minOf(
        centers.minOfOrNull { it.y - iconSize / 2f } ?: geometry.anchor.y,
        geometry.quickBarY
    )
    val contentRight = maxOf(
        centers.maxOfOrNull { it.x + iconSize / 2f } ?: geometry.anchor.x,
        geometry.quickBarX + quickWidth
    )
    val contentBottom = maxOf(
        centers.maxOfOrNull { it.y + iconSize / 2f } ?: geometry.anchor.y,
        geometry.quickBarY + quickHeight
    )
    val padding = iconSize * 0.55f
    return PreviewViewport(
        left = contentLeft - padding,
        top = contentTop - padding,
        width = contentRight - contentLeft + padding * 2f,
        height = contentBottom - contentTop + padding * 2f
    )
}

/** 轨道装饰弧半径：取实机布局中该圈图标的真实半径（无该圈图标时按实机公式兜底）。 */
private fun previewRingRadius(geometry: FanGeometry, isOuter: Boolean): Float {
    geometry.items.firstOrNull { it.isOuter == isOuter }?.let { return it.radius }
    return if (isOuter) {
        (geometry.innerRadius + geometry.outerRadius) / 2f
    } else {
        geometry.innerRadius * 0.85f
    }
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
                cornerRadius = (size * 0.24f).dp
            )
    )
}

private fun placeholderColor(colors: FanThemeColors): Color =
    colors.primaryContainer.copy(alpha = 0.55f)

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

    Row(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (offsetX + geometry.quickBarX * scale).toInt(),
                    y = (offsetY + geometry.quickBarY * scale).toInt()
                )
            }
            .squircleSurface(
                color = colors.surfaceContainer.copy(alpha = 0.94f),
                cornerRadius = 10.dp
            )
            .squircleBorder(
                width = 1.dp,
                color = colors.outline.copy(alpha = 0.3f),
                cornerRadius = 10.dp
            )
            .padding(horizontal = 5.dp, vertical = 4.dp)
    ) {
        geometry.quickApps.take(4).forEach { _ ->
            Box(
                modifier = Modifier
                    .widthIn(min = (iconSizeDp.value + 4f).dp)
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center
            ) {
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
}
