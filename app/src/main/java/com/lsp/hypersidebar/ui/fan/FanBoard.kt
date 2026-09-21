package com.lsp.hypersidebar.ui.fan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.prefs.LayoutDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import kotlin.math.cos
import kotlin.math.sin

/**
 * 板材质背后的像素来源（Route D 0915 重构）。
 *
 * 存在的意义：Compose 只能采样「本窗口自己画出来的像素」，窗口外的屏幕像素永远读不到。
 * 因此板所依赖的「背后内容」必须显式化为一个来源，而不是散落在三种窗口形态里。
 */
internal enum class FanBackdropSource {
    /**
     * 采样壁纸：把壁纸位图只录进 backdrop 图层（不上屏）再采样模糊——零系统模糊 API，
     * 且板材质（模糊/混色/噪点）全部生效。这是唯一能做出完整 miuix 板的路。
     * 横竖屏共用同一张图（0915 用户拍板）：竖屏桌面=真实背景；横屏 :ui 垫的也是壁纸
     * （内容非游戏画面，用户接受）。
     */
    WALLPAPER,

    /**
     * 透明：既不取背后内容、也不加板材质 —— 只剩弧线与图标，完全透视到后面的画面。
     * 这是最初「某条来源没生效」时的样子，作为一档显式保留。
     */
    TRANSPARENT,

    /**
     * 后方屏幕全屏景深：FLAG_BLUR_BEHIND。像素拿不到；MIUI 把它实现为全屏模糊（非局部），
     * 板只叠极淡色让系统模糊透出。
     */
    BEHIND_SCREEN,

    /** 无来源：板自身承担全部材质（亚克力板）。 */
    NONE
}

/**
 * 板材质渲染器 —— 扇形与快捷栏共用的唯一板（0915 用户拍板「两者一体化呈现」）。
 *
 * 单条 miuix 管线：`textureBlur(backdrop, boardShape, blurRadius, noiseCoefficient, colors)`，
 * backdrop 由 [source] 决定装什么：
 *
 * | 来源 | backdrop 内容 | 板底混色 | 观感 |
 * |---|---|---|---|
 * | WALLPAPER | 壁纸位图 + 主题混色罩（都只进图层，不上屏） | 浓（×0.80） | 磨砂壁纸被拉回主题色域 + 颗粒 |
 * | BEHIND_SCREEN | 主题混色罩（必须极淡） | 极淡（×0.12） | 几乎纯系统模糊，板只留极淡着色 |
 * | NONE | 主题混色罩（浓度高 = 板底色本身） | 即板底色（×1.30） | 主题色板 + 提亮 + 颗粒（亚克力） |
 * | TRANSPARENT | —— 整块板不画 —— | —— | 只剩弧线与图标，完全透视 |
 *
 * 采集层是一个**空 Box**：miuix 的 `Modifier.layerBackdrop` 实现是
 * `drawContent()`（空 → 屏幕无输出）+ `recordLayer(backdrop.graphicsLayer) { onDraw(...) }`，
 * 内容由 onDraw 画进图层，所以屏幕上看不到整屏壁纸/底板——板外仍是真实桌面。
 *
 * 边缘高光：自绘玻璃渐变描边。miuix `Highlight` 只支持圆角矩形（`CornerBasedShape`），
 * 对板形「磨砂弧带∪胶囊」的 `Outline.Generic` 会回退成整窗 SDF，描不到板轮廓（0919 真机反馈：
 * 快捷栏框选线条消失），故改回自绘——沿最外层弧画竖向渐变 Stroke（细锐边 + 极淡柔光）。
 */
@Composable
internal fun FanBoard(
    source: FanBackdropSource,
    geometry: FanGeometry,
    colors: FanThemeColors,
    fogIntensity: Float,
    wallpaper: ImageBitmap?,
    wallpaperOffset: IntOffset,
    sweep: () -> Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density

    // 板底混色：三档 —— 有背后真像素时「混色=玻璃罩」（把背后像素拉回主题色域），
    // 无来源时「混色=板底色本身」。采样壁纸这档必须够浓：亮壁纸配暗主题时，
    // 不压一层主题色就是一块亮晃晃的板（真机 0915 反馈）
    val veilScale = when (source) {
        FanBackdropSource.WALLPAPER -> LayoutDefaults.FAN_BOARD_VEIL_SCALE_WALLPAPER
        FanBackdropSource.BEHIND_SCREEN -> LayoutDefaults.FAN_BOARD_VEIL_SCALE_SYSTEM
        FanBackdropSource.NONE -> LayoutDefaults.FAN_BOARD_VEIL_SCALE_PLAIN
        // 透明档：材质整块不画（返回值用不到，给 0 以免误用）
        FanBackdropSource.TRANSPARENT -> 0f
    }
    val veilAlpha = (fogIntensity * veilScale)
        .coerceIn(LayoutDefaults.FAN_BOARD_VEIL_MIN, LayoutDefaults.FAN_BOARD_VEIL_MAX)
    // 提亮同理：有背后内容时宁淡勿浓，否则把真模糊/真像素洗白
    val sheenScale = if (source == FanBackdropSource.NONE) {
        LayoutDefaults.FAN_BOARD_SHEEN_SCALE_PLAIN
    } else {
        LayoutDefaults.FAN_BOARD_SHEEN_SCALE_BLURRED
    }
    val sheen = fogIntensity * sheenScale
    val noise = LayoutDefaults.FAN_BOARD_NOISE_BASE + fogIntensity * LayoutDefaults.FAN_BOARD_NOISE_SCALE

    Box(modifier = modifier.fillMaxSize()) {
        // 透明档：整块板不画（既不取背后内容也不加材质）——连采集层与 textureBlur 都不建，
        // 免得走「空图层 + miuix 混色」那条未验证路径。只余下方两层描边 + 图标
        if (source != FanBackdropSource.TRANSPARENT) {
            // 采集层：backdrop 内容 = 壁纸（采样路，不透明底）+ 主题混色半透明罩。
            // 混色同时承担两个职责：有背后内容时是玻璃罩着色，无来源时就是板的底色本身。
            // 浓度下限非零 → 图层恒非空（空图层的 miuix 混色路径未经验证，不留隐患）。
            // 只进图层、不上屏：宿主是空 Box，drawContent() 无输出
            val backdrop = rememberLayerBackdrop {
                if (source == FanBackdropSource.WALLPAPER && wallpaper != null) {
                    drawImage(
                        image = wallpaper,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(wallpaper.width, wallpaper.height),
                        dstOffset = IntOffset(-wallpaperOffset.x, -wallpaperOffset.y),
                        dstSize = geometry.windowSize,
                        filterQuality = FilterQuality.Medium
                    )
                }
                drawRect(color = colors.surfaceContainerHigh.copy(alpha = veilAlpha))
            }
            // ① 采集层（窗口尺寸空 Box）——把「背后内容」录进 backdrop 图层，自身不上屏
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop))
            // ② 板：miuix textureBlur，按板形裁剪 + 主题混色 + 噪点抖动。
            // 入场随 sweep 渐显：只在 draw-time 改 alpha（GPU 合成），不把 sweep 喂进 shape——
            // 否则每帧重建模糊几何/重跑高斯，首呼出必卡
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = sweep() }
                    .textureBlur(
                        backdrop = backdrop,
                        shape = remember(geometry, density) { boardShape(geometry, density) },
                        blurRadius = LayoutDefaults.FAN_BOARD_BLUR_RADIUS_PX / density,
                        noiseCoefficient = noise,
                        colors = BlurColors(
                            blendColors = listOf(
                                // Screen 提亮（滤色不压灰，比 SrcOver 更接近玻璃质感）
                                BlendColorEntry(Color.White.copy(alpha = sheen), BlurBlendMode.Screen)
                            ),
                            // 亮度补偿只给无来源的板：自绘底色需要按主题微调。有背后真像素时
                            // 不额外提亮/压暗 —— 亮壁纸会被 +0.04 推得更亮，正是真机反馈的问题
                            brightness = if (source == FanBackdropSource.NONE) {
                                if (colors.isDark) LayoutDefaults.FAN_BOARD_BRIGHTNESS_DARK
                                else LayoutDefaults.FAN_BOARD_BRIGHTNESS_LIGHT
                            } else 0f,
                            contrast = 1f,
                            saturation = LayoutDefaults.FAN_BOARD_SATURATION
                        )
                    )
            )
        }
        // ③ 玻璃描边：不参与模糊、始终清晰。用户 2026-09-20 定稿——扇形只描**最外层一条圆弧**
        //（柔光 + 细线，round 收尾），不再描内缘/径向线、也不用半圆端帽包起来；快捷栏单独描胶囊框。
        // 磨砂弧带的填充模糊区仍在（bandPath 供 textureBlur 裁剪），只是不再勾整圈轮廓
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sweepP = sweep()
            if (sweepP <= 0.01f) return@Canvas
            // 竖向渐变：亮端在扇顶、暗端在快捷栏底（玻璃受顶光）。浅主题白描边不可见，改用 outline
            val topColor = if (colors.isDark) Color.White.copy(alpha = 0.50f)
            else colors.outline.copy(alpha = 0.45f)
            val bottomColor = if (colors.isDark) Color.White.copy(alpha = 0.06f)
            else colors.outline.copy(alpha = 0.10f)
            val brush = Brush.linearGradient(
                colors = listOf(topColor, bottomColor),
                start = Offset(geometry.anchor.x, geometry.anchor.y - geometry.outerRadius),
                end = Offset(
                    geometry.anchor.x,
                    geometry.quickBarY + geometry.quickIconSize * density * 1.5f
                )
            )
            val (_, bandOuterR) = fanBandRadii(geometry, density)
            val swept = geometry.spanAngle * sweepP.coerceAtMost(1f)
            val arcTopLeft = Offset(geometry.anchor.x - bandOuterR, geometry.anchor.y - bandOuterR)
            val arcSize = Size(bandOuterR * 2f, bandOuterR * 2f)
            // 极淡柔光弧（假 bloom）
            drawArc(
                brush = brush, startAngle = geometry.startAngle, sweepAngle = swept,
                useCenter = false, topLeft = arcTopLeft, size = arcSize, alpha = 0.18f,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            // 最外层单条细弧
            drawArc(
                brush = brush, startAngle = geometry.startAngle, sweepAngle = swept,
                useCenter = false, topLeft = arcTopLeft, size = arcSize,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

/** 板形 Shape（磨砂弧带，sweep=1 终态）——miuix textureBlur 的模糊区域。
 *  快捷栏胶囊**不在其中**：它自带底（见 QuickAppsBar），与扇形板各自独立成块。 */
private fun boardShape(geometry: FanGeometry, density: Float) = object : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = Outline.Generic(bandPath(geometry, density.density, 1f))
}

/**
 * 磨砂弧带轮廓：外缘弧 start→end、内缘弧 end→start，两端径向直边不描边（模糊柔化）。
 * 内外缘**紧贴实际图标群**（[fanBandRadii]），而非裸 inner/outer 半径——否则弧线会比外圈图标
 * 外飘 (外-中)/2 的距离，看着像一根乱飘的曲线（0920 真机截图反馈）。
 */
private fun bandPath(geometry: FanGeometry, density: Float, sweepP: Float): Path {
    val anchor = geometry.anchor
    val (bandInnerR, bandOuterR) = fanBandRadii(geometry, density)
    val start = geometry.startAngle
    val swept = geometry.spanAngle * sweepP.coerceAtMost(1f)
    val end = start + swept
    return Path().apply {
        val outerOval = Rect(anchor.x - bandOuterR, anchor.y - bandOuterR, anchor.x + bandOuterR, anchor.y + bandOuterR)
        val innerOval = Rect(anchor.x - bandInnerR, anchor.y - bandInnerR, anchor.x + bandInnerR, anchor.y + bandInnerR)
        val startRad = Math.toRadians(start.toDouble())
        moveTo(
            anchor.x + bandOuterR * cos(startRad).toFloat(),
            anchor.y + bandOuterR * sin(startRad).toFloat()
        )
        arcTo(outerOval, start, swept, false)
        arcTo(innerOval, end, -swept, false)
        close()
    }
}

/**
 * 弧带内外缘半径：外缘 = 最外圈图标外缘 + 内边距，内缘 = 最内圈图标内缘 - 内边距。
 * 让磨砂带与描边弧"包住"图标群（图标坐在带子里、弧线贴着外圈图标），不随裸半径外飘。
 * internal=设置页预览（FanPreview）复用同一口径，保证预览与真机弧带几何一致。
 */
internal fun fanBandRadii(geometry: FanGeometry, density: Float): Pair<Float, Float> {
    val iconHalf = geometry.iconSize * density / 2f
    val pad = 12f * density
    if (geometry.items.isEmpty()) {
        return geometry.innerRadius.coerceAtLeast(24f * density) to geometry.outerRadius
    }
    val maxR = geometry.items.maxOf { it.radius }
    val minR = geometry.items.minOf { it.radius }
    val outerEdge = maxR + iconHalf + pad
    val innerEdge = (minR - iconHalf - pad).coerceAtLeast(24f * density)
    return innerEdge to outerEdge
}
