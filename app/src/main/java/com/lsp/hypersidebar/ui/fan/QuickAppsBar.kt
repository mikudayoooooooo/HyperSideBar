package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.prefs.LayoutDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

@Composable
fun QuickAppsBar(
    geometry: FanGeometry,
    selectedIndex: Int,
    colors: FanThemeColors,
    frostedBackdrop: LayerBackdrop?,
    onQuickAppSelected: (FanAppInfo) -> Unit
) {
    val context = LocalContext.current
    val quickApps = geometry.quickApps.take(6)
    if (quickApps.isEmpty()) return

    val iconSizeDp = geometry.quickIconSize
    val density = LocalDensity.current.density
    val pxIconSize = iconSizeDp * density
    val pxSpacing = pxIconSize * 0.35f
    val barPadding = pxIconSize * 0.5f

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // B3 选中反馈对齐扇形：选中图标上方显示应用名标签（样式同扇形 SelectedLabel）
        if (selectedIndex in quickApps.indices) {
            val app = quickApps[selectedIndex]
            val iconPx = pxIconSize
            val pad = barPadding
            val step = iconPx * 1.35f
            val cx = geometry.quickBarX + pad + selectedIndex * step + iconPx / 2f
            var labelSize by remember { mutableStateOf(IntSize.Zero) }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (cx - labelSize.width / 2f).toInt(),
                            (geometry.quickBarY - labelSize.height - 8.dp.roundToPx()).toInt()
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
                    text = app.appName.ifEmpty { app.packageName },
                    color = colors.onSurface,
                    style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.footnote1
                )
            }
        }
        Row(
            modifier = Modifier
                .offset {
                    IntOffset(
                        geometry.quickBarX.toInt(),
                        geometry.quickBarY.toInt()
                    )
                }
                // 毛玻璃底板（0913 路线③）：对 layerBackdrop 录制的"弧+图标"子树做窗口内
                // textureBlur（miuix-blur，AGSL RuntimeShader）；frostedBackdrop=null 时
                // 此节点不参与（enabled=false 跳过特效，零采样成本）。
                // HyperOS 风格=亮磨砂：blur 后叠主题 surface 高调白混（首测"暗色矩形只有
                // 压暗感"——雾化=0 时窗口内背景透明，blur 无感，观感全靠染色；改白混提亮）
                .then(
                    frostedBackdrop?.let { bd ->
                        Modifier.textureBlur(
                            backdrop = bd,
                            shape = RoundedCornerShape((iconSizeDp / 2f + 4f).dp),
                            blurRadius = LayoutDefaults.FAN_FROSTED_QUICK_BAR_BLUR_DP * density,
                            noiseCoefficient = BlurDefaults.NoiseCoefficient,
                            colors = BlurColors(
                                blendColors = listOf(
                                    BlendColorEntry(
                                        colors.surfaceContainerHigh.copy(alpha = 0.5f),
                                        BlurBlendMode.SrcOver
                                    )
                                )
                            ),
                            enabled = true
                        )
                    } ?: Modifier
                )
                .clip(RoundedCornerShape((iconSizeDp / 2f + 4f).dp))
                .background(
                    // 亮混已承担染色：毛玻璃态不再叠暗色底（否则回退成压暗观感）
                    if (frostedBackdrop != null) Color.Transparent
                    else colors.surfaceContainer.copy(alpha = 0.9f)
                )
                .padding(
                    horizontal = (iconSizeDp * 0.25f).dp,
                    vertical = (iconSizeDp * 0.25f).dp
                ),
            horizontalArrangement = Arrangement.spacedBy((iconSizeDp * 0.35f).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            quickApps.forEachIndexed { index, app ->
                QuickAppIcon(
                    context = context,
                    app = app,
                    iconSize = iconSizeDp,
                    isSelected = index == selectedIndex,
                    colors = colors,
                    onClick = { onQuickAppSelected(app) }
                )
            }
        }
    }
}

@Composable
private fun QuickAppIcon(
    context: Context,
    app: FanAppInfo,
    iconSize: Float,
    isSelected: Boolean,
    colors: FanThemeColors,
    onClick: () -> Unit
) {
    val (bitmap, fallbackColor) = rememberAppIcon(context, app)
    val targetScale = if (isSelected) SELECTED_ICON_SCALE else 1f
    val targetAlpha = if (isSelected) 1f else 0.75f
    val iconScale by animateFloatAsState(targetValue = targetScale, animationSpec = tween(100))
    val iconAlpha by animateFloatAsState(targetValue = targetAlpha, animationSpec = tween(100))

    Box(
        modifier = Modifier
            .size(iconSize.dp)
            .scale(iconScale)
            .alpha(iconAlpha)
            // B1 圆角 mask 统一：CircleShape → 圆角方（与扇形图标一致）
            .clip(RoundedCornerShape((iconSize * 0.25f).dp))
            .background(
                if (isSelected) colors.primaryContainer.copy(alpha = 0.9f)
                else colors.surfaceContainerHigh.copy(alpha = 0.35f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AppIconImage(
            bitmap = bitmap,
            fallbackColor = fallbackColor,
            appName = app.appName,
            size = iconSize,
            colors = colors
        )

        if (isSelected) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                // B1：描边随 mask 同形状（圆角方）
                drawRoundRect(
                    color = colors.primary,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                        size.minDimension * 0.25f, size.minDimension * 0.25f
                    ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    alpha = iconAlpha
                )
            }
        }
    }
}

@Composable
fun AppIconImage(
    bitmap: Bitmap?,
    fallbackColor: Int,
    appName: String,
    size: Float,
    colors: FanThemeColors
) {
    if (bitmap != null) {
        val painter = remember(bitmap) { BitmapPainter(bitmap.asImageBitmap()) }
        Image(
            painter = painter,
            contentDescription = appName,
            modifier = Modifier.size(size.dp)
        )
    } else {
        FallbackIcon(appName, size, fallbackColor, colors)
    }
}

@Composable
private fun FallbackIcon(
    appName: String,
    size: Float,
    fallbackColor: Int,
    colors: FanThemeColors
) {
    val text = appName.take(1).ifEmpty { "?" }
    Box(
        modifier = Modifier
            .size(size.dp)
            // B1：兜底头像与全线图标 mask 统一（圆角方）
            .clip(RoundedCornerShape((size * 0.25f).dp))
            .background(androidx.compose.ui.graphics.Color(fallbackColor)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = colors.onPrimary,
            style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body1
        )
    }
}
