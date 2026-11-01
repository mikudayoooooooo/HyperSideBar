package com.lsp.hypersidebar.ui.fan

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.util.HLog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface

/** 收起时长（ms）：比入场短，抬手后不该再挡住手指 */
private const val GROUP_POPUP_EXIT_MS = 100L
/** 子项错峰（相对入场进度的份额）：n=3 时末项晚约 1/4 行程，读作"一个个长出来" */
private const val GROUP_POPUP_STAGGER_SHARE = 0.12f
/* 背板圆角已移到 LayoutDefaults.GROUP_POPUP_CORNER_DP（FanBoard 的 union 节点要用同一个值） */

/**
 * 组竖向弹层（2026-10-31）：背板 + 竖列子项 + 入场从 86% 长到 100%。
 *
 * 为什么竖列：组上限 3 项，竖排 + 背板把"这些是组里的"表达得最清楚；横向内联会与同栏普通项
 * 混成一排、层级消失（见 [GroupPopupLayout] 注释）。位置/尺寸/命中全部来自纯几何
 * [GroupPopupLayout]，本文件只负责画与动画，故不参与单测。
 *
 * 触摸一律由宿主处理（`ComposeFanHost.handlePopupTouch`）：overlay 窗口的 DOWN 被宿主消费，
 * Compose 自身的 clickable 不会触发，[QuickAppIcon] 的 onClick 仅为形态复用。
 */
@Composable
internal fun GroupPopupLayer(
    ui: GroupPopupUi?,
    colors: FanThemeColors,
    density: Float,
    /** 板材质输入（与 FanBoard 同源；参数一律走 boardVeilAlpha/boardBlurColors/boardNoiseCoefficient） */
    source: FanBackdropSource,
    fogIntensity: Float,
    wallpaper: androidx.compose.ui.graphics.ImageBitmap?,
    wallpaperOffset: IntOffset,
    wallpaperDisplay: IntSize
) {
    // 收层时先淡出 100ms 再移除（直接置空会硬闪）。
    // 池化 composition 的 remember 跨呼出存活 ⇒ disappear 分支必须把 shown 清掉，
    // 否则下一轮首次打开会沿用旧背板尺寸（resetInteractionState 置 null 正好触发这段）。
    var shown by remember { mutableStateOf<GroupPopupUi?>(null) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(ui != null) {
        if (ui != null) {
            val isNew = shown?.popup != ui.popup
            shown = ui
            if (isNew) {
                appear.snapTo(0f)
                appear.animateTo(
                    1f,
                    tween(LayoutDefaults.GROUP_POPUP_ENTER_MS, easing = FastOutSlowInEasing)
                )
            }
        } else {
            appear.animateTo(0f, tween(GROUP_POPUP_EXIT_MS.toInt()))
            shown = null
        }
    }
    val current = shown ?: return
    // 展示期间选中项变化（MOVE）只改高亮，不重播入场
    val display = if (ui != null && ui.popup == current.popup) ui else current

    val p = current.popup
    val iconSizeDp = p.iconPx / density
    val context = LocalContext.current
    val screenWidthPx = context.resources.displayMetrics.widthPixels.toFloat()
    val popupShape = remember { androidx.compose.foundation.shape.RoundedCornerShape(LayoutDefaults.GROUP_POPUP_CORNER_DP.dp) }

    // 诊断（一次/每次开层）：材质档位与尺寸——真机上若背板发白/发透明，先看这一行
    LaunchedEffect(current.popup) {
        HLog.i(
            "GroupPopup",
            "material source=$source fog=$fogIntensity veil=${boardVeilAlpha(source, fogIntensity)} " +
                "noise=${boardNoiseCoefficient(fogIntensity)} size=${p.width.toInt()}x${p.height.toInt()}"
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 弹层本体：**独立一层材质**，参数与扇形板同源（boardVeilAlpha/boardBlurColors/
        // boardNoiseCoefficient）。2026-11-01：并进 FanBoard 的 union 在真机上整块变成
        // "透明档"（只剩描边+图标）且没有"浮起来"的层级感 ⇒ 独立节点 + 同一套参数。
        Box(
            modifier = Modifier
                .offset { IntOffset(p.x.toInt(), p.y.toInt()) }
                .size((p.width / density).dp, (p.height / density).dp)
                .graphicsLayer { alpha = appear.value }
        ) {
            // 兜底底色（材质缺席时也不透空）：材质正常时被其覆盖，几乎不可见
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(popupShape)
                    .background(
                        colors.surfaceContainerHigh.copy(
                            alpha = LayoutDefaults.GROUP_POPUP_FALLBACK_ALPHA
                        )
                    )
            )
            if (source != FanBackdropSource.TRANSPARENT) {
                // 采集层：壁纸按窗口坐标还原后用弹层原点平移到节点本地系；丙烯档只有平铺混色
                val backdrop = rememberLayerBackdrop {
                    if (source == FanBackdropSource.WALLPAPER && wallpaper != null) {
                        drawImage(
                            image = wallpaper,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(wallpaper.width, wallpaper.height),
                            dstOffset = IntOffset(
                                -wallpaperOffset.x - p.x.toInt(),
                                -wallpaperOffset.y - p.y.toInt()
                            ),
                            dstSize = if (wallpaperDisplay.width > 0) wallpaperDisplay else IntSize(
                                (p.x + p.width).toInt(), (p.y + p.height).toInt()
                            ),
                            filterQuality = FilterQuality.Medium
                        )
                    }
                    drawRect(
                        color = colors.surfaceContainerHigh.copy(
                            alpha = boardVeilAlpha(source, fogIntensity)
                        )
                    )
                }
                Box(modifier = Modifier.matchParentSize().layerBackdrop(backdrop))
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(popupShape)
                        .textureBlur(
                            backdrop = backdrop,
                            shape = popupShape,
                            blurRadius = LayoutDefaults.FAN_BOARD_BLUR_RADIUS_PX / density,
                            noiseCoefficient = boardNoiseCoefficient(fogIntensity),
                            colors = boardBlurColors(source, fogIntensity, colors)
                        )
                )
            }
            // 内容层：描边 + 子项（描边始终在最上，材质在其下）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .squircleBorder(
                        width = 1.dp,
                        color = colors.outline.copy(alpha = 0.4f),
                        cornerRadius = LayoutDefaults.GROUP_POPUP_CORNER_DP.dp
                    )
                    .padding((p.padPx / density).dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy((p.gapPx / density).dp)
                ) {
                    display.children.forEachIndexed { index, app ->
                        val childAlpha = ((appear.value - index * GROUP_POPUP_STAGGER_SHARE) /
                            (1f - GROUP_POPUP_STAGGER_SHARE * 2f)).coerceIn(0f, 1f)
                        Box(modifier = Modifier.graphicsLayer { alpha = childAlpha }) {
                            QuickAppIcon(
                                context = context,
                                app = app,
                                iconSize = iconSizeDp,
                                isSelected = index == display.selectedIndex,
                                colors = colors,
                                onClick = { /* 触摸由宿主 handlePopupTouch 统一处理 */ }
                            )
                        }
                    }
                }
            }
        }

        // 选中子项的名称（2026-11-01 用户："悬停时子项没有展示快捷方式的名称"）。
        // 与扇形 SelectedLabel 同款；贴在背板外侧（右，越界改左），与选中子项垂直居中。
        val sel = display.selectedIndex
        if (sel in display.children.indices) {
            val app = display.children[sel]
            val childCenterY = p.children[sel].second
            var labelSize by remember { mutableStateOf(IntSize.Zero) }
            val gapPx = 10f * density
            val rightX = p.x + p.width + gapPx
            val leftX = p.x - gapPx - labelSize.width
            // **与弹层同向镜像**（2026-11-01 用户："名称也要对称"）：弹层向右展开 ⇒ 名称放
            // 弹层右侧（远离手指）；弹层向左展开 ⇒ 名称放左侧。贴屏边时退回另一侧。
            val fitsRight = rightX + labelSize.width <= screenWidthPx - 8f * density
            val useRight = if (p.onRight) fitsRight else false
            val labelX = (if (useRight) rightX else leftX).coerceAtLeast(8f * density)
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            labelX.toInt(),
                            (childCenterY - labelSize.height / 2f).toInt()
                        )
                    }
                    .graphicsLayer { alpha = appear.value }
                    .alpha(if (labelSize == IntSize.Zero) 0f else 1f)
                    .onSizeChanged { labelSize = it }
                    .squircleSurface(
                        color = colors.surfaceContainerHigh.copy(alpha = FanVisuals.LABEL_FILL_ALPHA),
                        cornerRadius = FanVisuals.LABEL_CORNER
                    )
                    .squircleBorder(
                        width = FanVisuals.LABEL_BORDER_WIDTH,
                        color = colors.outline.copy(alpha = FanVisuals.LABEL_BORDER_ALPHA),
                        cornerRadius = FanVisuals.LABEL_CORNER
                    )
                    .padding(
                        horizontal = FanVisuals.LABEL_PADDING_H,
                        vertical = FanVisuals.LABEL_PADDING_V
                    )
            ) {
                Text(
                    text = app.appName.ifEmpty { app.packageName },
                    color = colors.onSurface,
                    style = MiuixTheme.textStyles.footnote1
                )
            }
        }
    }
}
