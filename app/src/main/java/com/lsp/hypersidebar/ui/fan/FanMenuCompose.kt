package com.lsp.hypersidebar.ui.fan

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.MutableState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
    Box(modifier = modifier.fillMaxSize()) {
        val backdropImage = FanBackdrop.image
        if (backdropImage != null) {
            // 批次 1.5 毛玻璃：背后画面（壁纸快照）记录为 backdrop 源层，
            // 弧形区域用 textureBlur 糊化 + 噪点抗条带（miuix-blur，RuntimeShader）。
            // 源 Image 裁到扇形：弧内被糊化层覆盖，弧外完全透明透出 launcher
            val backdrop = rememberLayerBackdrop()
            Image(
                bitmap = backdropImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .clip(FanSectorShape(geometry))
                    .layerBackdrop(backdrop)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha }
                    .textureBlur(
                        backdrop = backdrop,
                        shape = FanSectorShape(geometry),
                        blurRadius = 40f * density,
                        noiseCoefficient = BlurDefaults.NoiseCoefficient
                    )
            )
        }
        // 主题色调 veil + 描边（毛玻璃之上压主题色；无壁纸时即原着色弧兜底）
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val veilAlpha = if (backdropImage != null) 0.22f else 0.15f
            drawArc(
                color = colors.surfaceContainer.copy(alpha = veilAlpha * alpha),
                startAngle = geometry.startAngle,
                sweepAngle = geometry.spanAngle,
                useCenter = true,
                topLeft = Offset(
                    geometry.anchor.x - geometry.outerRadius,
                    geometry.anchor.y - geometry.outerRadius
                ),
                size = androidx.compose.ui.geometry.Size(
                    geometry.outerRadius * 2,
                    geometry.outerRadius * 2
                ),
                alpha = alpha
            )
            drawArc(
                color = colors.outline.copy(alpha = 0.2f * alpha),
                startAngle = geometry.startAngle,
                sweepAngle = geometry.spanAngle,
                useCenter = false,
                topLeft = Offset(
                    geometry.anchor.x - geometry.outerRadius,
                    geometry.anchor.y - geometry.outerRadius
                ),
                size = androidx.compose.ui.geometry.Size(
                    geometry.outerRadius * 2,
                    geometry.outerRadius * 2
                ),
                style = Stroke(width = 1.dp.toPx()),
                alpha = alpha
            )
        }
    }
}

/** 扇形 Outline（圆心=呼出锚点，useCenter 闭合），供 textureBlur 裁切。 */
private class FanSectorShape(private val geometry: FanGeometry) : Shape {
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
