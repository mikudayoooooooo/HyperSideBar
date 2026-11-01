package com.lsp.hypersidebar.ui.fan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
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
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
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

    /** 无来源：板自身承担全部材质（亚克力板）。 */
    NONE
}

/**
 * 板材质渲染器 —— 扇形与快捷栏共用的唯一板（0915 用户拍板「两者一体化呈现」）。
 *
 * 单条 miuix 管线：`textureBlur(backdrop, shape, blurRadius, noiseCoefficient, colors)`，
 * 弧带（fillMaxSize 节点 + Generic path）与快捷栏胶囊（**胶囊尺寸节点 + 本地坐标 Rounded**）
 * 各起一层，但共享同一 backdrop 与同一套材质参数（见方法内 ②-a / ②-b）。
 * backdrop 由 [source] 决定装什么：
 *
 * | 来源 | backdrop 内容 | 板底混色 | 观感 |
 * |---|---|---|---|
 * | WALLPAPER | 壁纸位图 + 主题混色罩（都只进图层，不上屏） | 浓（×0.80） | 磨砂壁纸被拉回主题色域 + 颗粒 |
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
    /** 窗口原点（屏幕坐标）：把采样壁纸还原到窗口本地系时用——见 [FanBackdropSource.WALLPAPER] 的绘制。 */
    wallpaperOffset: IntOffset,
    /** 屏幕像素尺寸（= 采样壁纸应铺满的范围）。Zero 时退回按窗口尺寸拉伸（旧行为）。 */
    wallpaperDisplay: IntSize,
    sweep: () -> Float,
    modifier: Modifier = Modifier
) {
    val localDensity = LocalDensity.current
    val density = localDensity.density

    // 板底混色：三档 —— 有背后真像素时「混色=玻璃罩」（把背后像素拉回主题色域），
    // 无来源时「混色=板底色本身」。采样壁纸这档必须够浓：亮壁纸配暗主题时，
    // 不压一层主题色就是一块亮晃晃的板（真机 0915 反馈）
    // 混色/提亮/噪点/颜色参数全部取自 boardVeilAlpha / boardBlurColors / boardNoiseCoefficient
    // （单源：组弹层背板用的是同一套，见 GroupPopupLayer）
    val veilAlpha = boardVeilAlpha(source, fogIntensity)
    val noise = boardNoiseCoefficient(fogIntensity)

    Box(modifier = modifier.fillMaxSize()) {
        // 透明档：整块板不画（既不取背后内容也不加材质）——连采集层与 textureBlur 都不建，
        // 免得走「空图层 + miuix 混色」那条未验证路径。只余下方两层描边 + 图标
        if (source != FanBackdropSource.TRANSPARENT) {
            // 采集层：backdrop 内容 = 壁纸（采样路，不透明底）+ 主题混色半透明罩。
            // 混色同时承担两个职责：有背后内容时是玻璃罩着色，无来源时就是板的底色本身。
            // 浓度下限非零 → 图层恒非空（空图层的 miuix 混色路径未经验证，不留隐患）。
            // 只进图层、不上屏：宿主是空 Box，drawContent() 无输出
            //
            // 注：这里**不做径向雾化渐变**。0921 曾按 0906 口径落地过"弧缘 100% → 锚点 35%"
            // 的径向混色（中心=锚点、半径=弧带外缘），真机复验**肉眼不可辨**（弧带已被等半径
            // 高斯压过，混色浓度差被抹平）⇒ 已撤回，勿再重复尝试；层内任何"浓度随位置变化"
            // 的做法都会在板面上留下可见色阶（2026-10-31 血案：模糊强度渐变把板洗成两段色）。
            // 壁纸对位（0921）：采样位图 = 屏幕的**等比居中等比裁剪**（WallpaperSampler 按
            // displayMetrics 裁剪后降到半分辨率），所以把它按「屏幕尺寸」还原、再用窗口原点
            // 平移到窗口本地系，磨砂里的"背后"就与桌面真实壁纸像素对得上。
            // 旧实现 dstSize = windowSize 是"拉伸铺满窗口"——窗口比屏幕矮（insets）时取景被压扁，
            // 与桌面只是形似（真机对比可见接不上）。
            val wallpaperDst = if (wallpaperDisplay.width > 0 && wallpaperDisplay.height > 0) {
                wallpaperDisplay
            } else {
                geometry.windowSize
            }
            val backdrop = rememberLayerBackdrop {
                if (source == FanBackdropSource.WALLPAPER && wallpaper != null) {
                    drawImage(
                        image = wallpaper,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(wallpaper.width, wallpaper.height),
                        dstOffset = IntOffset(-wallpaperOffset.x, -wallpaperOffset.y),
                        dstSize = wallpaperDst,
                        filterQuality = FilterQuality.Medium
                    )
                }
                drawRect(color = colors.surfaceContainerHigh.copy(alpha = veilAlpha))
            }
            // ① 采集层（窗口尺寸空 Box）——把「背后内容」录进 backdrop 图层，自身不上屏
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop))
            // ② 板：miuix textureBlur，按形状裁剪 + 主题混色 + 噪点抖动。
            // 入场随 sweep 渐显：只在 draw-time 改 alpha（GPU 合成），不把 sweep 喂进 shape——
            // 否则每帧重建模糊几何/重跑高斯，首呼出必卡
            //
            // 2026-10-31 起弧带与快捷栏胶囊**共用同一个模糊节点**（boardShape = 两条轮廓的
            // union path）：两次独立取景/混色会在交界处留下可见台阶（真机"上下两端颜色断层/
            // 分层"），而单层材质由构造保证一致。见 [boardShape]。
            val blurRadiusDp = LayoutDefaults.FAN_BOARD_BLUR_RADIUS_PX / density
            val blurColors = boardBlurColors(source, fogIntensity, colors)
            // ② 板材质：弧带 ∪ 快捷栏胶囊 = **一个**形状、一层 miuix textureBlur。
            //
            // 为什么必须一层（2026-10-31 真机复现"浓度拉到最高仍分层/上下两端颜色断层"）：
            // 两个节点 = 两次独立取景 + 两次混色，任何一丁点差异（模糊核相对形状大小、
            // 各节点自己的离屏缓冲尺寸、混合叠加顺序）都会在交界处变成一条可见的"台阶"；
            // 实测在软件渲染下胶囊那一层干脆画不出材质（只剩 ③ 的描边），与弧带并排即两层。
            // 并成一条 union path 后材质由**构造**保证一致——这才是"弧带与胶囊一体"的
            // 正确实现（0920 那句"一体"要的是同一层材质；早期并集失败是把两个形状错误叠加，
            // 这里用 Path.op(UNION)，两个轮廓各自保留）。
            //
            // **等半径**模糊（2026-10-31 定案，取代 0.9.4 的渐进档）：
            // 渐进档 = 模糊强度沿轴渐变。模糊强度对**半透明**内容会改变 alpha（miuix 的
            // 下采样/上采样链在强糊端把 alpha 摊薄），于是同一块板沿渐变方向出现一段
            // "由浓到无"的色阶——用户两次报的"上下两端断层 / 最高浓度也分层"就是它；
            // 而弧带与胶囊分属两个节点时，两边的糊度不同，交界处再叠一条台阶。
            // 现在：弧带 ∪ 胶囊 = 一条 union path + **一个**等半径材质 ⇒ 整块板同色，
            // 任何浓度下都不会出现色阶。代价是失去"越靠边越糊"的径向纵深（该效果在 0921
            // 也只有 wallpaper 档才可能看出，亚克力档根本没有像素可糊）。
            val boardOutline = remember(geometry, density) { boardShape(geometry, density) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = sweep() }
                    // 外层再显式裁一次：miuix 自己那次 clip 走 placeWithLayer 的内部 layerBlock，
                    // 真机上未必作用到「节点自己 draw 出来的模糊」上。没有这层兜底，模糊缓冲会按
                    // 节点包围盒出图 —— 一圈直角边（0921 用户真机：矩形与胶囊框线完全重合）
                    .clip(boardOutline)
                    .textureBlur(
                        backdrop = backdrop,
                        shape = boardOutline,
                        blurRadius = blurRadiusDp,
                        noiseCoefficient = noise,
                        colors = blurColors
                    )
            )
        }
        // ③ 玻璃描边：不参与模糊、始终清晰。用户 2026-09-20 定稿——扇形只描**最外层一条圆弧**
        //（柔光 + 细线，round 收尾），不再描内缘/径向线、也不用半圆端帽包起来；快捷栏单独描胶囊框。
        // 磨砂弧带的填充模糊区仍在（bandPath 供 textureBlur 裁剪），只是不再勾整圈轮廓
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sweepP = sweep()
            if (sweepP <= FanVisuals.SWEEP_VISIBLE_EPSILON) return@Canvas
            // 竖向渐变：亮端在弧带真实顶端、暗端在弧带真实底端（玻璃受顶光）。端点必须按
            // 扫描极值算而非 anchor±outerRadius 的名义范围——底角档弧带只占上半象限，用名义
            // 范围会把绝大部分内容 clamp 到单一端色，渐变等于失效（浅主题白描边不可见，改用 outline）
            val topColor = if (colors.isDark) Color.White.copy(alpha = FanVisuals.BAND_STROKE_TOP_ALPHA_DARK)
            else colors.outline.copy(alpha = FanVisuals.BAND_STROKE_TOP_ALPHA_LIGHT)
            val bottomColor = if (colors.isDark) Color.White.copy(alpha = FanVisuals.BAND_STROKE_BOTTOM_ALPHA_DARK)
            else colors.outline.copy(alpha = FanVisuals.BAND_STROKE_BOTTOM_ALPHA_LIGHT)
            val (_, bandOuterR) = fanBandRadii(geometry, density)
            // 描边弧必须与裁剪弧同一范围（底角档=补满象限），否则外弧描边会停在磨砂区中间
            val (bandStart, bandSpan) = bandArc(geometry)
            val (minSin, maxSin, _, _) = sweepExtremes(bandStart, bandStart + bandSpan)
            val brush = Brush.linearGradient(
                colors = listOf(topColor, bottomColor),
                start = Offset(geometry.anchor.x, geometry.anchor.y + bandOuterR * minSin),
                end = Offset(geometry.anchor.x, geometry.anchor.y + bandOuterR * maxSin)
            )
            val swept = bandSpan * sweepP.coerceAtMost(1f)
            val arcTopLeft = Offset(geometry.anchor.x - bandOuterR, geometry.anchor.y - bandOuterR)
            val arcSize = Size(bandOuterR * 2f, bandOuterR * 2f)
            // 极淡柔光弧（假 bloom）
            drawArc(
                brush = brush, startAngle = bandStart, sweepAngle = swept,
                useCenter = false, topLeft = arcTopLeft, size = arcSize, alpha = FanVisuals.BAND_BLOOM_ALPHA,
                style = Stroke(width = FanVisuals.BAND_BLOOM_STROKE_WIDTH.toPx(), cap = StrokeCap.Round)
            )
            // 最外层单条细弧
            drawArc(
                brush = brush, startAngle = bandStart, sweepAngle = swept,
                useCenter = false, topLeft = arcTopLeft, size = arcSize,
                style = Stroke(width = FanVisuals.BAND_STROKE_WIDTH.toPx(), cap = StrokeCap.Round)
            )
            // 快捷栏胶囊框线：用**恒定可见色**，不蹭弧带那支竖向渐变——底角档胶囊在扇形上缘
            // 之上、落在渐变区间外会被 clamp 到最暗端而"看不见"（0920 反馈框线又没了）。
            // 走 DrawScope.drawRoundRect（Skia 原生圆角矩形光栅化）而**不是** Path.addRoundRect：
            // 真机曾出现"与胶囊框线完全重合的一圈直角边"（0921 用户截图），Path 的圆角表达
            // 不再参与这条链，退化成直角矩形也不可能再发生
            val capsuleColor = if (colors.isDark) Color.White.copy(alpha = FanVisuals.CAPSULE_STROKE_ALPHA_DARK)
            else colors.outline.copy(alpha = FanVisuals.CAPSULE_STROKE_ALPHA_LIGHT)
            quickCapsuleRoundRect(geometry, density)?.let { rr ->
                drawRoundRect(
                    color = capsuleColor,
                    topLeft = Offset(rr.left, rr.top),
                    size = Size(rr.width, rr.height),
                    cornerRadius = rr.topLeftCornerRadius,
                    style = Stroke(width = FanVisuals.CAPSULE_STROKE_WIDTH.toPx())
                )
            }
        }
    }
}

/**
 * 板形（弧带 ∪ 快捷栏胶囊）——**整块板只有这一层材质**（2026-10-31）。
 *
 * 两个轮廓各自由 [bandPath] / [quickCapsuleRoundRect] 生成，用 `Path.op(UNION)` 合成
 * 一条 path：两个互不相连的子轮廓都会保留（不是把 rect 硬加进扇形里——那是早期"胶囊轮廓
 * 被弧带吞掉"的成因）。坐标均为窗口本地系，配 fillMaxSize 节点。
 */
/** 板底混色浓度（单源）：采样壁纸宁淡、无来源直通、透明档不画。 */
internal fun boardVeilAlpha(source: FanBackdropSource, fogIntensity: Float): Float {
    val scale = when (source) {
        FanBackdropSource.WALLPAPER -> LayoutDefaults.FAN_BOARD_VEIL_SCALE_WALLPAPER
        FanBackdropSource.NONE -> LayoutDefaults.FAN_BOARD_VEIL_SCALE_PLAIN
        FanBackdropSource.TRANSPARENT -> 0f
    }
    return (fogIntensity * scale)
        .coerceIn(LayoutDefaults.FAN_BOARD_VEIL_MIN, LayoutDefaults.FAN_BOARD_VEIL_MAX)
}

/** 噪点抗条带系数（单源）。 */
internal fun boardNoiseCoefficient(fogIntensity: Float): Float =
    LayoutDefaults.FAN_BOARD_NOISE_BASE + fogIntensity * LayoutDefaults.FAN_BOARD_NOISE_SCALE

/**
 * 板材质颜色参数（单源）：扇形板、快捷栏胶囊、组弹层背板必须用**同一份**，否则同一屏幕上
 * 会出现几种深浅（2026-11-01 用户："组展开的材质和层级对不上"）。
 */
internal fun boardBlurColors(
    source: FanBackdropSource,
    fogIntensity: Float,
    colors: FanThemeColors
): BlurColors {
    val sheenScale = if (source == FanBackdropSource.NONE) {
        LayoutDefaults.FAN_BOARD_SHEEN_SCALE_PLAIN
    } else {
        LayoutDefaults.FAN_BOARD_SHEEN_SCALE_BLURRED
    }
    return BlurColors(
        blendColors = listOf(
            BlendColorEntry(Color.White.copy(alpha = fogIntensity * sheenScale), BlurBlendMode.Screen)
        ),
        brightness = if (source == FanBackdropSource.NONE) {
            if (colors.isDark) LayoutDefaults.FAN_BOARD_BRIGHTNESS_DARK
            else LayoutDefaults.FAN_BOARD_BRIGHTNESS_LIGHT
        } else 0f,
        contrast = 1f,
        saturation = LayoutDefaults.FAN_BOARD_SATURATION
    )
}

internal fun boardShape(geometry: FanGeometry, density: Float) = object : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = Outline.Generic(boardPath(geometry, density.density, 1f))
}

private fun boardPath(geometry: FanGeometry, density: Float, sweepP: Float): Path {
    var merged = bandPath(geometry, density, sweepP)
    quickCapsuleRoundRect(geometry, density)?.let { capsule ->
        merged = Path().apply { op(merged, Path().apply { addRoundRect(capsule) }, PathOperation.Union) }
    }
    return merged
}

/** 弧带 Shape（sweep=1 终态）——miuix textureBlur 的模糊区域之一。环扇形铺满整窗坐标，故配 fillMaxSize 节点。 */
private fun bandShape(geometry: FanGeometry, density: Float) = object : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = Outline.Generic(bandPath(geometry, density.density, 1f))
}

/** 快捷栏最多并排展示的图标数（与 [QuickAppsBar] 的 `take(6)` 同源）。 */
internal const val QUICK_BAR_MAX_ICONS = 6

/** 图标间距 / 图标边长（与 [QuickAppsBar] Row 的 `spacedBy(0.35q)` + 每侧 0.5q 内边距同源）。 */
internal const val QUICK_BAR_GAP_RATIO = 0.35f

/** 胶囊内边距（相对图标边长）：左右各 0.5q、上下各 0.25q —— 与 [QuickAppsBar] Row 的 padding 同源。 */
internal const val QUICK_BAR_SIDE_PAD_RATIO = 0.5f
internal const val QUICK_BAR_VERTICAL_PAD_RATIO = 0.25f

/** 胶囊几何（px）：宽 = n 图标 + 间距 + 左右各半格内边距；高 = 图标 + 上下各 0.25q = 1.5q。 */
internal data class CapsuleMetrics(val width: Float, val height: Float, val corner: Float)

/**
 * 胶囊圆角（dp）——[quickCapsuleMetrics] 的 `corner` 与设置页示意图盒子共用同一口径
 * （px = 本值 × density），避免预览/真机各抄一份 10dp 之类的数字。
 */
internal fun quickCapsuleCornerDp(quickIconSizeDp: Float): Float = quickIconSizeDp / 2f + 4f

/**
 * 胶囊尺寸唯一来源——模糊裁剪节点的**节点尺寸**与 ③ 描边框线共用。
 * 与 [QuickAppsBar] 的 Row 实际尺寸、`computeQuickAppCenter` 的步进必须同源，改一处即三处。
 */
internal fun quickCapsuleMetrics(geometry: FanGeometry, density: Float): CapsuleMetrics? {
    val n = minOf(QUICK_BAR_MAX_ICONS, geometry.quickApps.size)
    if (n <= 0) return null
    val q = geometry.quickIconSize * density
    return CapsuleMetrics(
        width = n * q + (n - 1) * q * QUICK_BAR_GAP_RATIO + 2f * q * QUICK_BAR_SIDE_PAD_RATIO,
        height = q + 2f * q * QUICK_BAR_VERTICAL_PAD_RATIO,
        corner = quickCapsuleCornerDp(geometry.quickIconSize) * density
    )
}

/** ③ 描边层用的胶囊轮廓（**窗口绝对坐标**，与 [quickCapsuleMetrics] 同源）；无快捷项时 null。 */
private fun quickCapsuleRoundRect(geometry: FanGeometry, density: Float): RoundRect? =
    quickCapsuleMetrics(geometry, density)?.let { m ->
        RoundRect(
            geometry.quickBarX, geometry.quickBarY,
            geometry.quickBarX + m.width, geometry.quickBarY + m.height,
            CornerRadius(m.corner, m.corner)
        )
    }

/**
 * 弧带径向边越过坐标轴方向的角度：让直边真的穿出窗口、再由窗口裁齐。
 * 按弧带外缘 ~900px 计，10° 的横向外飘 ≈156px，够覆盖锚点距屏幕边 0~150px 的内缩。
 */
private const val BAND_AXIS_OVERSHOOT_DEG = 10f

/**
 * 弧带真正参与裁剪/描边的弧范围（start, span）——0921 用户定案"保外弧、不要径向边，
 * 屏幕边缘可作为径向边"，两种呼出档同一口径：
 *
 * 把楔形的起边**向下取整到 90° 轴**、止边**向上取整到 90° 轴**，再各越 [BAND_AXIS_OVERSHOOT_DEG]，
 * 最后由 [bandPath] 按窗口矩形裁一次。于是两条径向边必然与屏幕边重合、屏内不再留直边切口，
 * 双环图标必在板内。底角档（锚点在角上）得到 90° 象限；边沿档（锚点在边中段）得到 180° 半环
 * ——磨砂区明显变大，是用户看过口径后选的。
 *
 * 图标布局的 6°/12° 避让留白（CORNER_TOP/BOTTOM_GAP_DEG）不受影响：这里只放宽材质弧。
 */
internal fun bandArc(geometry: FanGeometry): Pair<Float, Float> {
    val start = floor(geometry.startAngle / 90f) * 90f - BAND_AXIS_OVERSHOOT_DEG
    val end = ceil(geometry.endAngle / 90f) * 90f + BAND_AXIS_OVERSHOOT_DEG
    return start to (end - start)
}

/**
 * 磨砂弧带轮廓：外缘弧 start→end、内缘弧 end→start，两端径向直边不描边（模糊柔化）。
 * 内外缘**紧贴实际图标群**（[fanBandRadii]），而非裸 inner/outer 半径——否则弧线会比外圈图标
 * 外飘 (外-中)/2 的距离，看着像一根乱飘的曲线（0920 真机截图反馈）。
 */
private fun bandPath(geometry: FanGeometry, density: Float, sweepP: Float): Path {
    val anchor = geometry.anchor
    val (bandInnerR, bandOuterR) = fanBandRadii(geometry, density)
    val (start, span) = bandArc(geometry)
    val swept = span * sweepP.coerceAtMost(1f)
    val end = start + swept
    val sector = Path().apply {
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
    // 越过坐标轴的那部分（含锚点内缩造成的贴屏直切口）交给窗口裁掉，直边才真正落在屏幕边上
    val window = Path().apply {
        addRect(Rect(0f, 0f, geometry.windowSize.width.toFloat(), geometry.windowSize.height.toFloat()))
    }
    return Path().apply { op(sector, window, PathOperation.Intersect) }
}

/**
 * 弧带内外缘半径：外缘 = 最外圈图标外缘 + 内边距，内缘 = 最内圈图标内缘 - 内边距。
 * 让磨砂带与描边弧"包住"图标群（图标坐在带子里、弧线贴着外圈图标），不随裸半径外飘。
 * internal=设置页预览（FanPreview）复用同一口径，保证预览与真机弧带几何一致。
 */
internal fun fanBandRadii(geometry: FanGeometry, density: Float): Pair<Float, Float> {
    val iconHalf = geometry.iconSize * density / 2f
    val pad = FanVisuals.BAND_EDGE_PAD_DP * density
    val minInner = FanVisuals.BAND_MIN_INNER_RADIUS_DP * density
    if (geometry.items.isEmpty()) {
        return geometry.innerRadius.coerceAtLeast(minInner) to geometry.outerRadius
    }
    val maxR = geometry.items.maxOf { it.radius }
    val minR = geometry.items.minOf { it.radius }
    val outerEdge = maxR + iconHalf + pad
    val innerEdge = (minR - iconHalf - pad).coerceAtLeast(minInner)
    return innerEdge to outerEdge
}
