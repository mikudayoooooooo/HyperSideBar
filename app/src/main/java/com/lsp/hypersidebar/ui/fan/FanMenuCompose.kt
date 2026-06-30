package com.lsp.hypersidebar.ui.fan

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 扇形菜单 Compose 入口（纯渲染层）。
 *
 * 触摸处理在 ComposeFanHost 的 dispatchTouchEvent 覆写中完成，
 * 本组件只读取 geometry 和 touchState 做视觉反馈。
 */
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
    val config = FanConfig() // 仅用于 deadZoneDp 等常量

    var selectedIndex by remember { mutableIntStateOf(-1) }
    var touchPoint by remember { mutableStateOf(Offset.Unspecified) }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { isVisible = true }

    // 触摸状态由 ComposeFanHost.dispatchTouchEvent 更新，此处仅做视觉响应
    LaunchedEffect(touchState.value) {
        val state = touchState.value
        val pos = Offset(state.x, state.y)
        val dist = distance(anchor, pos)
        if (dist <= geometry.activeZonePx && state.touchAction != 2 && state.touchAction != 3) {
            touchPoint = pos
            selectedIndex = state.selectedIndex
        } else {
            touchPoint = Offset.Unspecified
            selectedIndex = -1
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

    // ── 全屏覆盖：触摸由 ComposeFanHost.dispatchTouchEvent 处理 ──
    Box(modifier = Modifier.fillMaxSize()) {
        // 扇形背景
        FanBackground(geometry, colors, menuAlpha, Modifier.scale(scale))

        // 引导线
        FanGuideLines(geometry, colors, menuAlpha, Modifier.scale(scale))

        // 图标
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

        // 选中标签
        if (selectedIndex in geometry.items.indices) {
            SelectedLabel(
                item = geometry.items[selectedIndex],
                iconSize = geometry.iconSize,
                colors = colors
            )
        }

        // 摇杆指示器
        JoystickIndicator(
            anchor = anchor,
            touchPoint = touchPoint,
            activeZonePx = geometry.activeZonePx,
            deadZonePx = config.deadZoneDp * density,
            colors = colors,
            alpha = menuAlpha
        )

        // 快捷应用栏
        QuickAppsBar(
            geometry = geometry,
            colors = colors,
            onQuickAppSelected = onQuickAppSelected
        )
    }
}

// ════════════════════════════════════════════════════
// 摇杆指示器：锚点处的小圆圈 + 方向线
// ════════════════════════════════════════════════════

@Composable
private fun JoystickIndicator(
    anchor: Offset,
    touchPoint: Offset,
    activeZonePx: Float,
    deadZonePx: Float,
    colors: FanThemeColors,
    alpha: Float
) {
    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val a = alpha * 0.6f

            // 有效区边界
            drawCircle(
                color = colors.outline.copy(alpha = a * 0.4f),
                radius = activeZonePx,
                center = anchor,
                style = Stroke(width = 1.dp.toPx())
            )

            // 死区底色
            drawCircle(
                color = colors.surfaceContainer.copy(alpha = a * 0.5f),
                radius = deadZonePx,
                center = anchor
            )

            // 方向线 + 触摸点
            if (touchPoint != Offset.Unspecified) {
                drawLine(
                    color = colors.primary.copy(alpha = a),
                    start = anchor,
                    end = touchPoint,
                    strokeWidth = 2.dp.toPx()
                )
                drawCircle(
                    color = colors.primary.copy(alpha = a),
                    radius = 6.dp.toPx(),
                    center = touchPoint
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════
// 扇形背景
// ════════════════════════════════════════════════════

@Composable
private fun FanBackground(
    geometry: FanGeometry,
    colors: FanThemeColors,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val brush = Brush.sweepGradient(
                colors = listOf(
                    colors.primary.copy(alpha = 0.04f * alpha),
                    colors.surfaceContainer.copy(alpha = 0.08f * alpha),
                    colors.primary.copy(alpha = 0.04f * alpha)
                ),
                center = geometry.anchor
            )
            drawArc(
                brush = brush,
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
        }
    }
}

// ════════════════════════════════════════════════════
// 引导线
// ════════════════════════════════════════════════════

@Composable
private fun FanGuideLines(
    geometry: FanGeometry,
    colors: FanThemeColors,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val lineColor = colors.outline.copy(alpha = 0.25f * alpha)
            val steps = 5
            for (i in 0..steps) {
                val angle = geometry.startAngle + geometry.spanAngle * i / steps
                val rad = Math.toRadians(angle.toDouble())
                val endX = geometry.anchor.x + geometry.outerRadius * kotlin.math.cos(rad).toFloat()
                val endY = geometry.anchor.y + geometry.outerRadius * kotlin.math.sin(rad).toFloat()
                drawLine(
                    color = lineColor,
                    start = geometry.anchor,
                    end = Offset(endX, endY),
                    strokeWidth = 1.dp.toPx()
                )
            }
            drawArc(
                color = lineColor,
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

// ════════════════════════════════════════════════════
// 应用图标
// ════════════════════════════════════════════════════

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
    val targetScale = if (isSelected) 1.2f else 1f
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
            ) {
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
}

// ════════════════════════════════════════════════════
// 选中标签
// ════════════════════════════════════════════════════

@Composable
private fun SelectedLabel(
    item: FanItemLayout,
    iconSize: Float,
    colors: FanThemeColors
) {
    val density = LocalDensity.current.density
    val pxIconSize = iconSize * density
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (item.centerX - 60f).toInt(),
                    (item.centerY - pxIconSize * 0.9f - 28f).toInt()
                )
            }
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
