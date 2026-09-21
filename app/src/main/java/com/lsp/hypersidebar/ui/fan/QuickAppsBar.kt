package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface

@Composable
fun QuickAppsBar(
    geometry: FanGeometry,
    selectedIndex: Int,
    colors: FanThemeColors,
    onQuickAppSelected: (FanAppInfo) -> Unit
) {
    val context = LocalContext.current
    val quickApps = geometry.quickApps.take(QUICK_BAR_MAX_ICONS)
    if (quickApps.isEmpty()) return

    val iconSizeDp = geometry.quickIconSize
    val density = LocalDensity.current.density
    val pxIconSize = iconSizeDp * density
    val barPadding = pxIconSize * QUICK_BAR_SIDE_PAD_RATIO

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // B3 选中反馈对齐扇形：选中图标上方显示应用名标签（样式同扇形 SelectedLabel）
        if (selectedIndex in quickApps.indices) {
            val app = quickApps[selectedIndex]
            val iconPx = pxIconSize
            val pad = barPadding
            val step = iconPx * (1f + QUICK_BAR_GAP_RATIO)
            val cx = geometry.quickBarX + pad + selectedIndex * step + iconPx / 2f
            // 图标中心 Y = 板上边 + 0.25q 上内边距 + 半格图标（板高 1.5q，见 quickCapsuleMetrics）。
            // 与扇形 SelectedLabel 同一套避让式：图标中心 − 放大后半高 − 标签高 − 10dp
            val cy = geometry.quickBarY + iconPx * (QUICK_BAR_VERTICAL_PAD_RATIO + 0.5f)
            var labelSize by remember { mutableStateOf(IntSize.Zero) }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (cx - labelSize.width / 2f).toInt(),
                            (cy - iconPx * SELECTED_ICON_SCALE / 2f -
                                labelSize.height - 10.dp.roundToPx()).toInt()
                        )
                    }
                    .alpha(if (labelSize == IntSize.Zero) 0f else 1f)
                    .onSizeChanged { labelSize = it }
                    // 描边防隐身：板材质与标签同色系（surfaceContainerHigh），无边框时
                    // 标签融进板里（真机 0914 "看不到框选效果"）。squircle：surface 外层
                    // 填充+裁剪，border 内层描边（绘于填充之上）
                    .squircleSurface(
                        color = colors.surfaceContainerHigh.copy(alpha = 0.95f),
                        cornerRadius = 12.dp
                    )
                    .squircleBorder(
                        width = 1.dp,
                        color = colors.outline.copy(alpha = 0.65f),
                        cornerRadius = 12.dp
                    )
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
                // 栏的底由 FanBoard 的**胶囊独立模糊层**提供（与弧带同一 backdrop/混色/提亮/
                // 噪点参数，故两者材质观感一致），此处不叠任何染色、也不套 clip——自己铺平涂底
                // 会让胶囊与扇形板材质分叉（0920 反馈），而套 clip 等于给整条栏再插一层离屏缓冲
                .padding(
                    horizontal = (iconSizeDp * QUICK_BAR_SIDE_PAD_RATIO).dp,
                    vertical = (iconSizeDp * QUICK_BAR_VERTICAL_PAD_RATIO).dp
                ),
            horizontalArrangement = Arrangement.spacedBy((iconSizeDp * QUICK_BAR_GAP_RATIO).dp),
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
    // 同 FanAppIcon：不靠降低透明度做未选中态（真机反馈可读性差），选中由放大+高亮板+描边表达
    val targetScale = if (isSelected) SELECTED_ICON_SCALE else 1f
    val iconScale by animateFloatAsState(targetValue = targetScale, animationSpec = tween(100))

    Box(
        modifier = Modifier
            .size(iconSize.dp)
            .scale(iconScale)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // 选中高亮板仅在选中态绘制（与 FanAppIcon 逐行同构）：常态**不留底框**。
        // 曾恒铺一层 surfaceContainerHigh@0.35 的圆角底，等于给整条栏换了套材质——快捷栏图标
        // 像各自坐在灰底子上，而扇形图标是直接坐在板上（0921 用户反馈"快捷栏材质/背景与扇形不一致"）
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .squircleSurface(
                        color = colors.primaryContainer.copy(alpha = 0.9f),
                        cornerRadius = (iconSize * 0.25f).dp
                    )
            )
        }

        AppIconImage(
            bitmap = bitmap,
            fallbackColor = fallbackColor,
            appName = app.appName,
            // 与 FanAppIcon 同口径 0.92（图标几乎占满格子、留 8% 呼吸）。旧值 1.0 让同尺寸下的
            // 快捷栏图标肉眼比扇形图标更大，是"两者不一致"的另一处来源
            size = iconSize * 0.92f,
            colors = colors
        )

        if (isSelected) {
            // B1：描边随 mask 同形状（squircle 圆角方），绘于图标之上
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .squircleBorder(
                        width = 2.dp,
                        color = colors.primary,
                        cornerRadius = (iconSize * 0.25f).dp
                    )
            )
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
            // B1：兜底头像与全线图标 mask 统一（圆角方）→ squircle
            .squircleSurface(color = Color(fallbackColor), cornerRadius = (size * 0.25f).dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = colors.onPrimary,
            style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body1
        )
    }
}
