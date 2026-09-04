package com.lsp.hypersidebar.ui.fan

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.prefs.LayoutDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 选中态图标放大倍数（PRD §7.3.2"图标放大1.25倍"）；SelectedLabel 避让计算同源。 */
internal const val SELECTED_ICON_SCALE = 1.25f

@Composable
fun FanMenuCompose(
    geometry: FanGeometry,
    touchState: MutableState<FanTouchState>,
    colors: FanThemeColors,
    onAppSelected: (FanAppInfo) -> Unit,
    onQuickAppSelected: (FanAppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val anchor = geometry.anchor
    val config = FanConfig()

    var selectedIndex by remember { mutableIntStateOf(-1) }
    var selectedQuickIndex by remember { mutableIntStateOf(-1) }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { isVisible = true }

    LaunchedEffect(touchState.value) {
        val state = touchState.value
        // 预选反馈直接跟随命中结果（PRD §7.3.2 框选放大+应用名）。原 activeZone 距离门控
        // 会被设备上残留的旧参数（60dp=180px < 图标距圆心 280-390px）整体关闭——高亮
        // 永不出现，实测"完全没有选中反馈"。该参数已于 1B 废弃删除（PRD"30~120dp 可调"
        // 与"至少覆盖扇形"自相矛盾且任何距离门控都会复活此 bug），选中语义由
        // 死区+内外取消区派生
        if (state.touchAction != 2 && state.touchAction != 3) {
            selectedIndex = state.selectedIndex
            selectedQuickIndex = state.selectedQuickIndex
        } else {
            selectedIndex = -1
            selectedQuickIndex = -1
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.7f,
        animationSpec = tween(200)
    )
    val menuAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(200)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        FanBackground(geometry, colors, menuAlpha, Modifier.scale(scale))

        geometry.items.forEachIndexed { index, item ->
            FanAppIcon(
                context = context,
                item = item,
                isSelected = index == selectedIndex,
                iconSize = geometry.iconSize,
                colors = colors,
                alpha = menuAlpha,
                scale = scale
            )
        }

        if (selectedIndex in geometry.items.indices) {
            SelectedLabel(
                item = geometry.items[selectedIndex],
                iconSize = geometry.iconSize,
                colors = colors
            )
        }

        QuickAppsBar(
            geometry = geometry,
            selectedIndex = selectedQuickIndex,
            colors = colors,
            onQuickAppSelected = onQuickAppSelected
        )
    }
}

@Composable
private fun FanBackground(
    geometry: FanGeometry,
    colors: FanThemeColors,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val context = LocalContext.current
    Box(modifier = modifier.fillMaxSize()) {
        // 批次 1.5 v2：**面板自身材质自糊**——糊的是面板自己画出来的那一层，
        // 不采样任何外部画面。源层 = 主题色渐变底 + 每个应用图标位置的
        // **主色柔光斑**（IconPalette 提取图标平均色）：糊化后呈现"应用背后的
        // 色彩晕染"，底色跟呼出的应用组合动态走（HyperOS 控制中心/文件夹观感）。
        // 扇形与快捷栏共用同一材质层（联合形状一次糊化）——表现一体。
        val backdrop = rememberLayerBackdrop()
        var paletteTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(geometry) {
            val pkgs = geometry.items.map { it.app.packageName } +
                geometry.quickApps.map { it.packageName }
            IconPalette.extractAsync(context, pkgs) { paletteTick++ }
        }
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .clip(FanMaterialShape(geometry, density))
                .layerBackdrop(backdrop)
        ) {
            // ① 渐变底：primaryContainer（Monet 紫系）三段径向渐变，外缘渐隐
            drawArc(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.primaryContainer.copy(alpha = LayoutDefaults.FAN_MATERIAL_ALPHA_CORE),
                        colors.primaryContainer.copy(alpha = LayoutDefaults.FAN_MATERIAL_ALPHA_MID),
                        colors.primaryContainer.copy(alpha = LayoutDefaults.FAN_MATERIAL_ALPHA_EDGE)
                    ),
                    center = Offset(geometry.anchor.x, geometry.anchor.y),
                    radius = geometry.outerRadius
                ),
                startAngle = geometry.startAngle,
                sweepAngle = geometry.spanAngle,
                useCenter = true,
                topLeft = Offset(
                    geometry.anchor.x - geometry.outerRadius,
                    geometry.anchor.y - geometry.outerRadius
                ),
                size = Size(geometry.outerRadius * 2, geometry.outerRadius * 2),
                alpha = alpha
            )
            // ② 图标主色柔光斑（paletteTick 驱动：主色提取到位即出现晕染）
            paletteTick.let {
                val spotR = geometry.iconSize * density * 1.5f
                geometry.items.forEach { item ->
                    IconPalette.colorOf(item.app.packageName)?.let { argb ->
                        val tint = Color(argb)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    tint.copy(alpha = 0.5f),
                                    tint.copy(alpha = 0f)
                                ),
                                center = Offset(item.centerX, item.centerY),
                                radius = spotR
                            ),
                            radius = spotR,
                            center = Offset(item.centerX, item.centerY)
                        )
                    }
                }
                // 快捷栏区域：沿条带均匀分布的快捷应用主色光斑
                val quick = geometry.quickApps.take(6)
                if (quick.isNotEmpty()) {
                    val iconPx = geometry.quickIconSize * density
                    val pad = iconPx * 0.25f
                    val step = iconPx * 1.35f
                    quick.forEachIndexed { i, app ->
                        IconPalette.colorOf(app.packageName)?.let { argb ->
                            val tint = Color(argb)
                            val cx = geometry.quickBarX + pad + i * step + iconPx / 2f
                            val cy = geometry.quickBarY + pad + iconPx / 2f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        tint.copy(alpha = 0.45f),
                                        tint.copy(alpha = 0f)
                                    ),
                                    center = Offset(cx, cy),
                                    radius = iconPx * 1.5f
                                ),
                                radius = iconPx * 1.5f,
                                center = Offset(cx, cy)
                            )
                        }
                    }
                }
            }
        }
        // 自糊层（盖在源层之上）：源层本就是柔和渐变+光斑，即使糊化首帧未就绪
        // 露出的也是渐变底——不会像"清晰桌面"那样突兀
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
                .textureBlur(
                    backdrop = backdrop,
                    shape = FanMaterialShape(geometry, density),
                    blurRadius = LayoutDefaults.FAN_MATERIAL_BLUR_RADIUS_DP * density,
                    noiseCoefficient = BlurDefaults.NoiseCoefficient
                )
        )
        // 描边收口：扇形 + 快捷栏条（一体双段描边）
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = colors.outline.copy(alpha = 0.2f * alpha),
                startAngle = geometry.startAngle,
                sweepAngle = geometry.spanAngle,
                useCenter = false,
                topLeft = Offset(
                    geometry.anchor.x - geometry.outerRadius,
                    geometry.anchor.y - geometry.outerRadius
                ),
                size = Size(geometry.outerRadius * 2, geometry.outerRadius * 2),
                style = Stroke(width = 1.dp.toPx()),
                alpha = alpha
            )
            val quick = geometry.quickApps.take(6)
            if (quick.isNotEmpty()) {
                val iconPx = geometry.quickIconSize * density
                val pad = iconPx * 0.25f
                val w = quick.size * iconPx + (quick.size - 1) * iconPx * 0.35f + pad * 2
                val h = iconPx + pad * 2
                val r = (geometry.quickIconSize / 2f + 4f) * density
                drawRoundRect(
                    color = colors.outline.copy(alpha = 0.2f * alpha),
                    topLeft = Offset(geometry.quickBarX, geometry.quickBarY),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 1.dp.toPx()),
                    alpha = alpha
                )
            }
        }
    }
}

/**
 * 材质联合形状：扇形（圆心=呼出锚点）+ 快捷栏圆角条——一次糊化覆盖两者，
 * 表现一体。快捷栏条形尺寸与 QuickAppsBar 的 Row 布局同源（图标数×步进+内边距）。
 */
private class FanMaterialShape(
    private val geometry: FanGeometry,
    private val density: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path().apply {
            moveTo(geometry.anchor.x, geometry.anchor.y)
            arcTo(
                rect = Rect(
                    left = geometry.anchor.x - geometry.outerRadius,
                    top = geometry.anchor.y - geometry.outerRadius,
                    right = geometry.anchor.x + geometry.outerRadius,
                    bottom = geometry.anchor.y + geometry.outerRadius
                ),
                startAngleDegrees = geometry.startAngle,
                sweepAngleDegrees = geometry.spanAngle,
                forceMoveTo = false
            )
            close()
            val quick = geometry.quickApps.take(6)
            if (quick.isNotEmpty()) {
                val iconPx = geometry.quickIconSize * density.density
                val pad = iconPx * 0.25f
                val w = quick.size * iconPx + (quick.size - 1) * iconPx * 0.35f + pad * 2
                val h = iconPx + pad * 2
                val r = (geometry.quickIconSize / 2f + 4f) * density.density
                addRoundRect(
                    RoundRect(
                        geometry.quickBarX, geometry.quickBarY,
                        geometry.quickBarX + w, geometry.quickBarY + h,
                        CornerRadius(r, r)
                    )
                )
            }
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun FanAppIcon(
    context: Context,
    item: FanItemLayout,
    isSelected: Boolean,
    iconSize: Float,
    colors: FanThemeColors,
    alpha: Float,
    scale: Float
) {
    val (drawable, fallbackColor) = rememberAppIcon(context, item.app)
    val density = LocalDensity.current.density
    val pxIconSize = iconSize * density
    val targetScale = if (isSelected) SELECTED_ICON_SCALE else 1f
    val targetAlpha = if (isSelected) 1f else 0.75f
    val iconScale by animateFloatAsState(targetValue = targetScale, animationSpec = tween(100))
    val iconAlpha by animateFloatAsState(targetValue = targetAlpha, animationSpec = tween(100))

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (item.centerX - pxIconSize / 2f).toInt(),
                    (item.centerY - pxIconSize / 2f).toInt()
                )
            }
            .size(iconSize.dp)
            .scale(scale * iconScale)
            .alpha(alpha * iconAlpha)
            .clip(CircleShape)
            .background(
                if (isSelected) colors.primaryContainer.copy(alpha = 0.9f)
                else colors.surfaceContainer.copy(alpha = 0.85f)
            ),
        contentAlignment = Alignment.Center
    ) {
        AppIconImage(
            drawable = drawable,
            fallbackColor = fallbackColor,
            appName = item.app.appName,
            size = iconSize * 0.7f,
            colors = colors
        )

        if (isSelected) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = colors.primary,
                    radius = size.minDimension / 2f,
                    style = Stroke(width = 2.dp.toPx()),
                    alpha = alpha
                )
            }
        }
    }
}

@Composable
private fun SelectedLabel(
    item: FanItemLayout,
    iconSize: Float,
    colors: FanThemeColors
) {
    val density = LocalDensity.current.density
    val pxIconSize = iconSize * density
    // 实测量标签尺寸再定位：水平以图标圆心真居中（硬编码偏移在长应用名下会偏出圆心），
    // 垂直贴"放大后图标顶边"再留 10dp——此前按估算高度写死偏移，图标被弦长钳制到
    // 最小生效尺寸且选中放大 1.25 后，标签底边会压住图标顶边
    var labelSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (item.centerX - labelSize.width / 2f).toInt(),
                    (item.centerY - pxIconSize * SELECTED_ICON_SCALE / 2f -
                        labelSize.height - 10.dp.roundToPx()).toInt()
                )
            }
            .alpha(if (labelSize == IntSize.Zero) 0f else 1f)
            .onSizeChanged { labelSize = it }
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerHigh.copy(alpha = 0.95f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = item.app.appName.ifEmpty { item.app.packageName },
            color = colors.onSurface,
            style = MiuixTheme.textStyles.footnote1
        )
    }
}
